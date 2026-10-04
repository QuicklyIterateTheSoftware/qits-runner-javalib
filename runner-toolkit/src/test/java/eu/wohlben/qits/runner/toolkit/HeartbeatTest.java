package eu.wohlben.qits.runner.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code <binary> health}: the heartbeat file's age, as an exit code and one line. */
class HeartbeatTest {

  @TempDir Path state;

  private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");

  private void beatAt(Instant when) throws Exception {
    Path file = state.resolve(Heartbeat.FILE);
    if (!Files.exists(file)) {
      Files.createFile(file);
    }
    Files.setLastModifiedTime(file, FileTime.from(when));
  }

  @Test
  void aFreshHeartbeatIsHealthy() throws Exception {
    beatAt(NOW.minusSeconds(10));
    Heartbeat.Verdict verdict = Heartbeat.check(state, NOW, Duration.ofSeconds(60));
    assertEquals(Heartbeat.HEALTHY, verdict.exitCode());
    assertEquals("healthy: last heartbeat 10s ago", verdict.reason());
  }

  @Test
  void aStaleHeartbeatIsUnhealthy() throws Exception {
    beatAt(NOW.minusSeconds(61));
    Heartbeat.Verdict verdict = Heartbeat.check(state, NOW, Duration.ofSeconds(60));
    assertEquals(Heartbeat.UNHEALTHY, verdict.exitCode());
    assertEquals("unhealthy: last heartbeat 61s ago (limit 60s)", verdict.reason());
  }

  @Test
  void theThresholdItselfIsStillHealthy() throws Exception {
    beatAt(NOW.minusSeconds(60));
    assertEquals(
        Heartbeat.HEALTHY, Heartbeat.check(state, NOW, Duration.ofSeconds(60)).exitCode());
  }

  @Test
  void aMissingHeartbeatIsUnhealthy() {
    Heartbeat.Verdict verdict = Heartbeat.check(state, NOW, Duration.ofSeconds(60));
    assertEquals(Heartbeat.UNHEALTHY, verdict.exitCode());
    assertTrue(verdict.reason().startsWith("unhealthy: no heartbeat at "), verdict.reason());
  }

  @Test
  void aMissingStateDirectoryIsExitTwo() {
    Heartbeat.Verdict verdict =
        Heartbeat.check(state.resolve("absent"), NOW, Duration.ofSeconds(60));
    assertEquals(Heartbeat.NO_STATE_DIR, verdict.exitCode());
    assertEquals(2, verdict.exitCode());
  }

  @Test
  void touchCreatesTheFileAndThenMovesItsMtime() throws Exception {
    Path file = state.resolve(Heartbeat.FILE);
    Heartbeat.touch(state);
    assertTrue(Files.exists(file));
    Files.setLastModifiedTime(file, FileTime.fromMillis(0));
    Heartbeat.touch(state);
    assertEquals(
        Heartbeat.HEALTHY,
        Heartbeat.check(state, Instant.now(), Heartbeat.MAX_AGE).exitCode());
  }

  @Test
  void runReadsTheStateDirAndPrintsOneLine() throws Exception {
    Heartbeat.touch(state);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
    int code = Heartbeat.run(state.toString(), "/nowhere", out);
    assertEquals(0, code);
    String printed = bytes.toString(StandardCharsets.UTF_8);
    assertTrue(printed.startsWith("healthy: "), printed);
    assertEquals(1, printed.lines().count());

    bytes.reset();
    assertEquals(2, Heartbeat.run(state.resolve("absent").toString(), "/nowhere", out));
  }

  @Test
  void anUnsetStateDirIsTheIdentitysDefault() throws Exception {
    PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    assertEquals(Heartbeat.UNHEALTHY, Heartbeat.run(null, state.toString(), out), "no beat yet");
    Heartbeat.touch(state);
    assertEquals(Heartbeat.HEALTHY, Heartbeat.run("  ", state.toString(), out));
  }
}
