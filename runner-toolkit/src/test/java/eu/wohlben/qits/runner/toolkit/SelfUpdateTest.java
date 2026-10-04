package eu.wohlben.qits.runner.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.protocol.Retire;
import eu.wohlben.qits.runner.protocol.Upgrade;
import eu.wohlben.qits.runner.toolkit.testing.FakeDocker;
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
}
