package eu.wohlben.qits.runner.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Vertx;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The decisions {@link RunnerLauncher} makes before {@link RunnerRuntime} is built. */
class RunnerLauncherTest {

  private final Vertx vertx = Vertx.vertx();
  private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
  private final PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
  private final AtomicBoolean built = new AtomicBoolean();

  @TempDir Path state;

  @AfterEach
  void tearDown() throws Exception {
    vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  private int launch(Map<String, String> env, String... args) {
    return RunnerLauncher.main(
        Toy.IDENTITY,
        settings -> {
          built.set(true);
          return new Toy.Domain();
        },
        vertx,
        args,
        env::get,
        out);
  }

  private String printed() {
    return bytes.toString(StandardCharsets.UTF_8);
  }

  @Test
  void healthIsAnsweredFromTheHeartbeatFileAloneAndBeforeAnythingElse() throws Exception {
    // The version probe's variable is set too: health is checked first, and nothing is parsed.
    Map<String, String> env =
        Map.of("QITS_TOY_RUNNER_STATE_DIR", state.toString(), "QITS_TOY_RUNNER_PRINT_VERSION", "1");
    assertEquals(Heartbeat.UNHEALTHY, launch(env, "health"), "never connected");
    assertTrue(printed().startsWith("unhealthy: no heartbeat at "), printed());

    Heartbeat.touch(state);
    bytes.reset();
    assertEquals(Heartbeat.HEALTHY, launch(env, "health"));
    assertTrue(printed().startsWith("healthy: "), printed());
    assertFalse(built.get());
  }

  @Test
  void printVersionPrintsTheIdentitysVersionAndNothingElseStarts() {
    assertEquals(RunnerExit.OK, launch(Map.of("QITS_TOY_RUNNER_PRINT_VERSION", "1")));
    assertEquals(Toy.VERSION + "\n", printed(), "exactly one line, no url needed");
    assertFalse(built.get());
  }

  @Test
  void noEnvironmentAtAllIsExitTwoBeforeTheDomainIsBuilt() {
    assertEquals(RunnerExit.MISCONFIGURED, launch(Map.of()));
    assertFalse(built.get());
  }

  @Test
  void theDecommissionVariableIsHelperModeNotARunner() {
    int code =
        launch(
            Map.of(
                "QITS_TOY_RUNNER_DECOMMISSION", "r1",
                "QITS_TOY_RUNNER_URL", "http://127.0.0.1:1",
                "QITS_TOY_RUNNER_ID", "r1"));
    assertEquals(1, code, "no helper in this build: it fails, and stays with its log");
    assertTrue(printed().startsWith("qits-toy-runner decommission: "), printed());
    assertFalse(built.get(), "no runner was started");
  }

  @Test
  void everyShippedLineSaysWhichRunnerOnWhichContainerAndArch() {
    HostFacts host = new HostFacts(true, "arm64", "linux");
    Map<String, String> resource =
        RunnerLauncher.telemetryResource(Toy.IDENTITY, "r1", Optional.of("0123456789ab"), host);
    assertEquals("qits-toy-runner", resource.get("service.name"), "the identity's serviceName");
    assertEquals(Toy.VERSION, resource.get("service.version"));
    assertEquals("0123456789ab", resource.get("service.instance.id"));
    assertEquals("r1", resource.get("qits.toy.runner.id"));
    assertEquals("arm64", resource.get("host.arch"));
    assertEquals("linux", resource.get("os.type"));
    assertFalse(
        resource.containsKey("qits.workspace.id"),
        "the workspace pair would bucket the runner away from its service.name");
    assertEquals(
        "r1",
        RunnerLauncher.telemetryResource(Toy.IDENTITY, "r1", Optional.empty(), host)
            .get("service.instance.id"));
  }

  @Test
  void theHostFactsSpellThePlatformTheWayAnImageDoes() {
    HostFacts host = HostFacts.detect();
    assertFalse(host.arch().isEmpty());
    assertFalse(host.arch().equals("x86_64") || host.arch().equals("aarch64"), host::arch);
    assertEquals(host.os().toLowerCase(), host.os());
  }
}
