package eu.wohlben.qits.runner.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.driver.spec.VolumeSpec;
import eu.wohlben.qits.runner.toolkit.testing.FakeDocker;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** The seam every lifecycle docker call goes through, against a real process. */
@EnabledOnOs(OS.LINUX)
class RunnerDockerTest {

  @TempDir Path dir;

  private RunnerDocker docker(FakeDocker fake, int seconds) {
    return new RunnerDocker(Toy.IDENTITY, fake.binary(), Duration.ofSeconds(seconds));
  }

  @Test
  void stdoutAndStderrStayApart() throws Exception {
    FakeDocker fake = new FakeDocker(dir).answer("pull", 1, "progress…\n", "manifest unknown\n");
    RunnerDocker.Result result = docker(fake, 10).run(List.of(fake.binary(), "pull", "nope:1"));
    assertFalse(result.ok());
    assertEquals(1, result.exitCode());
    assertEquals("progress…\n", result.stdout());
    assertEquals("manifest unknown", result.detail(), "the diagnosis is stderr");
  }

  /**
   * A whole {@code docker inspect} document far past the 8 KiB a tail-keeping capture held, with
   * docker talking on stderr at the same time: the document arrives whole, from its opening {@code
   * [{}, and parses; not one byte of stderr is in it.
   */
  @Test
  void anInspectDocumentOverEightKibArrivesWholeAndStderrDoesNotLeakIntoIt() throws Exception {
    JsonObject container = new JsonObject().put("Id", "0123456789ab").put("Image", "sha256:old");
    JsonArray env = new JsonArray();
    for (int i = 0; i < 400; i++) {
      env.add("QITS_TOY_RUNNER_PADDING_" + i + "=" + "x".repeat(40));
    }
    container.put("Config", new JsonObject().put("Env", env));
    String document = new JsonArray().add(container).encode() + "\n";
    assertTrue(document.length() > 8 * 1024 * 2, "the fixture is well past 8 KiB");
    FakeDocker fake =
        new FakeDocker(dir)
            .answerFor(
                "inspect",
                "0123456789ab",
                0,
                document,
                "WARNING: the stderr line docker prints beside a document\n".repeat(50));

    RunnerDocker.Result result =
        docker(fake, 10).run(LifecycleArgv.inspectSelf(fake.binary(), "0123456789ab"));

    assertTrue(result.ok(), result::detail);
    assertFalse(result.stdoutTruncated());
    assertEquals(document, result.stdout());
    assertFalse(result.stdout().contains("WARNING"));
    assertEquals("sha256:old", SelfSpec.imageIdOf(result.stdout()));
    assertEquals(400, new JsonArray(result.stdout()).getJsonObject(0).getJsonObject("Config")
        .getJsonArray("Env").size());
  }

  @Test
  void aCallPastItsDeadlineIsKilledAndSaysSo() throws Exception {
    FakeDocker fake = new FakeDocker(dir).hang("pull", 30);
    long started = System.nanoTime();
    RunnerDocker.Result result = docker(fake, 1).run(List.of(fake.binary(), "pull", "slow:1"));
    long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
    assertTrue(result.timedOut());
    assertFalse(result.ok());
    assertTrue(result.detail().contains("did not answer within 1s"), result::detail);
    assertTrue(
        elapsedMillis < 15_000, () -> "the deadline did not bound the call: " + elapsedMillis);
  }

  @Test
  void aMissingBinaryIsAnAnswerRatherThanAnException() throws Exception {
    RunnerDocker.Result result =
        new RunnerDocker(Toy.IDENTITY, dir.resolve("no-such-docker").toString(), Duration.ofSeconds(5))
            .run(List.of(dir.resolve("no-such-docker").toString(), "ps"));
    assertFalse(result.ok());
    assertTrue(result.detail().contains("could not run"), result::detail);
  }

  /**
   * The driver's label namespace is the runner's own {@code <labelRoot>.}: an extra label inside it
   * is refused as the driver's own, and one inside {@code qits.containers.} is just a label — so
   * qits-containers-service's collection is never handed a runner's volume.
   */
  @Test
  void theDriverIsNamespacedUnderTheRunnersOwnLabelRoot() throws Exception {
    FakeDocker fake = new FakeDocker(dir);
    RunnerDocker docker = docker(fake, 10);
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            docker
                .driver()
                .ensureVolume(
                    new VolumeSpec("qits-toy-runner-v-r1", Map.of("qits.toy.runner.forged", "x")),
                    Map.of(),
                    Duration.ofSeconds(10)));
    docker
        .driver()
        .ensureVolume(
            new VolumeSpec("qits-toy-runner-v-r1", Map.of()),
            Map.of("qits.toy.runner", "r1"),
            Duration.ofSeconds(10));
    assertEquals(
        List.of("volume", "create", "--label", "qits.toy.runner=r1", "qits-toy-runner-v-r1"),
        fake.calls("volume").getFirst());
  }
}
