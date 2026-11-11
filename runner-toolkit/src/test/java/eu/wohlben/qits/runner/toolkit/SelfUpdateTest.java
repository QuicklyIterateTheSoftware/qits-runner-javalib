package eu.wohlben.qits.runner.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.protocol.Retire;
import eu.wohlben.qits.runner.protocol.Upgrade;
import eu.wohlben.qits.runner.toolkit.testing.FakeDocker;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link SelfUpdate} in isolation: the self-update image pull is the runner's own commissioned
 * client pair, never the short-lived bearer it dials the control socket with — see {@link
 * RegistryLogin} for why a JWT is refused by the edge's docker realm — and a successor removes its
 * predecessors and their images.
 */
@EnabledOnOs(OS.LINUX)
class SelfUpdateTest {

  private static final ClientCredentials CLIENT =
      new ClientCredentials(
          "toy-runner-r1",
          "s3cr3t-client-secret",
          "https://idp.example/token",
          "aud",
          "wss://toy.example/socket",
          "hash");

  private static final SelfUpdate.Settings FAST = new SelfUpdate.Settings(100, 400, 3_000, 3_000, 50);

  private static RunnerDocker docker(FakeDocker fake) {
    return new RunnerDocker(Toy.IDENTITY, fake.binary(), Duration.ofSeconds(10));
  }

  private static SelfUpdate selfUpdate(FakeDocker fake, Optional<String> self) {
    RunnerDocker docker = docker(fake);
    return new SelfUpdate(
        Toy.IDENTITY,
        docker,
        "r1",
        self,
        FAST,
        new RegistryLogin(Toy.IDENTITY, docker, () -> CLIENT),
        () -> true,
        new RunnerImages(Toy.IDENTITY, docker, self));
  }

  @Test
  void aPullLogsInWithTheRunnersOwnClientPairNeverAJwt(@TempDir Path dir) throws Exception {
    FakeDocker docker = new FakeDocker(dir);
    docker.answer("pull", 0, "", "");

    Optional<String> failure =
        selfUpdate(docker, Optional.empty())
            .pull(
                new Upgrade(
                    "2026.1.2", "registry.example:5000/qits/qits-toy-runner:2026.1.2", null));

    assertTrue(failure.isEmpty(), failure::toString);
    List<String> config = docker.configs().getFirst();
    String expectedAuth =
        Base64.getEncoder()
            .encodeToString(
                (CLIENT.clientId() + ":" + CLIENT.secret()).getBytes(StandardCharsets.UTF_8));
    JsonObject document = new JsonObject(config.get(1));
    String actualAuth =
        document.getJsonObject("auths").getJsonObject("registry.example:5000").getString("auth");
    assertEquals(expectedAuth, actualAuth);
    assertFalse(
        new String(Base64.getDecoder().decode(actualAuth), StandardCharsets.UTF_8)
            .startsWith("token:"),
        "the login is the client pair, not a bearer token");

    List<String> pull =
        docker.calls().stream().filter(c -> c.contains("pull")).findFirst().orElseThrow();
    assertFalse(Files.exists(Path.of(pull.get(1))), "the throwaway config directory is removed");
    assertTrue(
        Path.of(pull.get(1)).getFileName().toString().startsWith("qits-toy-runner-pull-"),
        pull::toString);
  }

  @Test
  void aPullFailureNeverEchoesTheClientSecret(@TempDir Path dir) throws Exception {
    FakeDocker docker = new FakeDocker(dir);
    docker.answer(
        "pull",
        1,
        "",
        "unauthorized: the identity provider refused these credentials for " + CLIENT.secret());

    Optional<String> failure =
        selfUpdate(docker, Optional.empty())
            .pull(
                new Upgrade(
                    "2026.1.2", "registry.example:5000/qits/qits-toy-runner:2026.1.2", null));

    assertTrue(failure.isPresent());
    assertFalse(failure.get().contains(CLIENT.secret()), failure::get);
    assertTrue(failure.get().contains("[redacted]"), failure::get);
  }

  /**
   * A cold pull routinely outlives the general {@code DOCKER_TIMEOUT} without docker itself being
   * stuck: {@link RegistryLogin#pull}, which this self-update's pull runs under, uses {@link
   * RunnerDocker#pullDeadline()} rather than the docker's general one.
   */
  @Test
  void aColdPullLongerThanTheGeneralDeadlineStillSucceedsUnderThePullDeadline(@TempDir Path dir)
      throws Exception {
    FakeDocker fake = new FakeDocker(dir).hang("pull", 2);
    RunnerDocker docker =
        new RunnerDocker(
            Toy.IDENTITY, fake.binary(), Duration.ofSeconds(1), Duration.ofSeconds(10));
    SelfUpdate selfUpdate =
        new SelfUpdate(
            Toy.IDENTITY,
            docker,
            "r1",
            Optional.empty(),
            FAST,
            new RegistryLogin(Toy.IDENTITY, docker, () -> CLIENT),
            () -> true,
            new RunnerImages(Toy.IDENTITY, docker, Optional.empty()));

    Optional<String> failure =
        selfUpdate.pull(
            new Upgrade("2026.1.2", "registry.example:5000/qits/qits-toy-runner:2026.1.2", null));

    assertTrue(failure.isEmpty(), failure::toString);
  }

  @Test
  void aSupersededRetirementTouchesNothingButItsOwnRestartPolicy(@TempDir Path dir)
      throws Exception {
    FakeDocker docker = new FakeDocker(dir);
    SelfUpdate selfUpdate = selfUpdate(docker, Optional.of("selfid"));

    selfUpdate.leave();
    // No upgrade was ever asked for, so this is the operator's own retirement — the SUPERSEDED
    // path.
    assertTrue(selfUpdate.retire(new Retire("operator retirement", Retire.Kind.SUPERSEDED)));

    assertEquals(List.of(List.of("update", "--restart=no", "selfid")), docker.calls());
  }

  private static final String OLD_IMAGE = "registry.example:5000/qits/qits-toy-runner:2026.1.0";

  @Test
  void theSuccessorRemovesItsPredecessorsImageOnceTheContainerIsGone(@TempDir Path dir)
      throws Exception {
    FakeDocker docker = new FakeDocker(dir);
    docker.answerFor(
        "ps",
        "label=qits.toy.runner.process=r1",
        0,
        "0123456789ab|"
            + Toy.VERSION
            + "|running|registry.example:5000/qits/qits-toy-runner:"
            + Toy.VERSION
            + "\n"
            + "aaaaaaaaaaaa|2026.1.0|exited|"
            + OLD_IMAGE
            + "\n",
        "");
    docker.answerFor("inspect", "0123456789ab", 0, "sha256:own\n", "");
    docker.answerFor("image-inspect", OLD_IMAGE, 0, "sha256:old\n", "");
    docker.container("aaaaaaaaaaaa", Map.of("qits.toy.runner.process", "r1"));

    selfUpdate(docker, Optional.of("0123456789ab")).removePredecessors();

    List<List<String>> calls = docker.calls();
    int removedContainer = calls.indexOf(List.of("rm", "-f", "aaaaaaaaaaaa"));
    int removedImage = calls.indexOf(List.of("image", "rm", OLD_IMAGE));
    assertTrue(removedContainer >= 0, calls::toString);
    assertTrue(
        removedImage > removedContainer, () -> "the image goes after its container: " + calls);
    assertTrue(
        calls.contains(List.of("ps", "-a", "-q", "--filter", "ancestor=sha256:old")),
        () -> "it asks whether any container still uses it: " + calls);
    assertEquals(1, docker.calls("image").stream().filter(c -> c.get(1).equals("rm")).count());
    assertFalse(calls.contains(List.of("rm", "-f", "0123456789ab")), "never itself");
  }

  @Test
  void aPredecessorImageDockerRefusesToRemoveIsLeftAndNothingFails(@TempDir Path dir)
      throws Exception {
    FakeDocker docker = new FakeDocker(dir);
    docker.answerFor(
        "ps",
        "label=qits.toy.runner.process=r1",
        0,
        "aaaaaaaaaaaa|2026.1.0|exited|" + OLD_IMAGE + "\n",
        "");
    docker.answerFor("inspect", "0123456789ab", 0, "sha256:own\n", "");
    docker.answerFor("image-inspect", OLD_IMAGE, 0, "sha256:old\n", "");
    docker.container("aaaaaaaaaaaa", Map.of("qits.toy.runner.process", "r1"));
    docker.answer("image-rm", 1, "", "conflict: unable to remove repository reference");

    selfUpdate(docker, Optional.of("0123456789ab")).removePredecessors();

    assertTrue(docker.calls().contains(List.of("image", "rm", OLD_IMAGE)));
  }

  @Test
  void aPredecessorOnTheRunningImageKeepsIt(@TempDir Path dir) throws Exception {
    FakeDocker docker = new FakeDocker(dir);
    docker.answerFor(
        "ps",
        "label=qits.toy.runner.process=r1",
        0,
        "aaaaaaaaaaaa|2026.1.0|exited|" + OLD_IMAGE + "\n",
        "");
    docker.answerFor("inspect", "0123456789ab", 0, "sha256:same\n", "");
    docker.answerFor("image-inspect", OLD_IMAGE, 0, "sha256:same\n", "");
    docker.container("aaaaaaaaaaaa", Map.of("qits.toy.runner.process", "r1"));

    selfUpdate(docker, Optional.of("0123456789ab")).removePredecessors();

    assertTrue(docker.calls().contains(List.of("rm", "-f", "aaaaaaaaaaaa")));
    assertTrue(
        docker.calls("image").stream().noneMatch(c -> c.get(1).equals("rm")),
        () -> "never the running image: " + docker.calls("image"));
  }

  private static final String SELF = "0123456789ab";
  private static final String NEXT = "2026.1.2";
  private static final String NEXT_IMAGE = "registry.example:5000/qits/qits-toy-runner:" + NEXT;

  /**
   * The runner's own container as docker answers its inspect — with {@code HostConfig.Dns} set,
   * as on a predecessor that was itself started with resolvers — and its image's config.
   */
  private static void runnerContainer(FakeDocker docker) throws Exception {
    docker.answerFor(
        "inspect",
        SELF,
        0,
        new JsonArray()
                .add(
                    new JsonObject()
                        .put("Id", SELF + "0".repeat(52))
                        .put("Image", "sha256:old")
                        .put(
                            "Config",
                            new JsonObject()
                                .put("Env", List.of("QITS_TOY_RUNNER_ID=r1"))
                                .put("Labels", Map.of("qits.toy.runner.process", "r1")))
                        .put(
                            "HostConfig",
                            new JsonObject()
                                .put("Binds", List.of("/var/run/docker.sock:/var/run/docker.sock"))
                                .put("Dns", List.of("100.96.0.1", "192.168.188.1"))
                                .put("RestartPolicy", Map.of("Name", "unless-stopped"))
                                .put("NetworkMode", "default")))
                .encode()
            + "\n",
        "");
    docker.answerFor(
        "image-inspect",
        "sha256:old",
        0,
        new JsonArray().add(new JsonObject().put("Id", "sha256:old")).encode() + "\n",
        "");
    docker.answer("pull", 0, "", "");
  }

  /** Drive one upgrade to its successor's {@code docker run}, and answer that run's argv. */
  private static List<String> successorRun(FakeDocker docker, Supplier<List<String>> dns)
      throws Exception {
    runnerContainer(docker);
    RunnerDocker runner = docker(docker);
    SelfUpdate selfUpdate =
        new SelfUpdate(
            Toy.IDENTITY,
            runner,
            "r1",
            Optional.of(SELF),
            FAST,
            new RegistryLogin(Toy.IDENTITY, runner, () -> CLIENT),
            () -> true,
            new RunnerImages(Toy.IDENTITY, runner, Optional.of(SELF)),
            dns);
    try {
      selfUpdate.upgrade(new Upgrade(NEXT, NEXT_IMAGE, null));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
      while (docker.calls("run").isEmpty() && System.nanoTime() < deadline) {
        Thread.sleep(20);
      }
      assertFalse(docker.calls("run").isEmpty(), () -> "no successor started: " + docker.calls());
      return docker.calls("run").getFirst();
    } finally {
      selfUpdate.shutdownNow();
    }
  }

  /** qits-2090: the seam's answer, asked at the rollover, is the successor's resolvers. */
  @Test
  void theSeamsResolversReachTheSuccessorInOrder(@TempDir Path dir) throws Exception {
    AtomicInteger asked = new AtomicInteger();
    List<String> run =
        successorRun(
            new FakeDocker(dir),
            () -> {
              asked.incrementAndGet();
              return List.of("100.96.0.1", "fd00::53");
            });

    assertEquals(
        List.of("--restart", "unless-stopped", "--dns", "100.96.0.1", "--dns", "fd00::53"),
        run.subList(4, 10),
        run::toString);
    assertEquals(1, asked.get(), "asked once, when the successor's argv is built");
  }

  /**
   * A predecessor started with resolvers does not hand them on: with the seam answering none, the
   * successor has no {@code --dns}, so a node whose tunnel went away converges at this rollover.
   */
  @Test
  void aPredecessorsResolversAreNeverInherited(@TempDir Path dir) throws Exception {
    List<String> run = successorRun(new FakeDocker(dir), List::of);

    assertFalse(run.contains("--dns"), run::toString);
    assertEquals(NEXT_IMAGE, run.getLast());
  }

  @Test
  void aSeamThatThrowsStartsTheSuccessorWithNoResolvers(@TempDir Path dir) throws Exception {
    List<String> run =
        successorRun(
            new FakeDocker(dir),
            () -> {
              throw new IllegalStateException("the tunnel's state is unreadable");
            });

    assertFalse(run.contains("--dns"), run::toString);
    assertEquals("qits-toy-runner-r1-" + NEXT, run.get(3), "the rollover went ahead");
    assertEquals(NEXT_IMAGE, run.getLast());
  }
}
