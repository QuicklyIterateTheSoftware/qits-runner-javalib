package eu.wohlben.qits.runner.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.driver.ContainersDriver;
import eu.wohlben.qits.containers.driver.spec.ContainerSpec;
import eu.wohlben.qits.containers.driver.spec.LifecyclePolicy;
import eu.wohlben.qits.containers.driver.spec.VolumeSpec;
import eu.wohlben.qits.runner.toolkit.testing.FakeDocker;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The fake docker the suites stand on, driven through the production seam — the session's
 * runner-namespaced qits-containers driver forking the script — so its argv record and its small
 * state model are what a two-runners-on-one-node suite can trust.
 */
@EnabledOnOs(OS.LINUX)
class FakeDockerTest {

  private static final Duration T = Duration.ofSeconds(10);

  @TempDir Path dir;

  private ContainersDriver driver(FakeDocker docker) throws Exception {
    RunnerSettings settings =
        RunnerSettings.parse(
            Toy.IDENTITY,
            Map.of(
                    "QITS_TOY_RUNNER_URL", "http://127.0.0.1:1",
                    "QITS_TOY_RUNNER_ID", "r1",
                    "QITS_TOY_RUNNER_DOCKER_BINARY", docker.binary())
                ::get);
    return new RunnerSession(Toy.IDENTITY, settings, null).driver();
  }

  @Test
  void theSessionsDriverRunsTheScriptAndEveryArgvIsRecorded() throws Exception {
    FakeDocker docker = new FakeDocker(dir);
    ContainersDriver driver = driver(docker);

    ContainersDriver.Started started =
        driver.run(
            ContainerSpec.builder("alpine:3").build(),
            "qits-toy-runner-item-a",
            Map.of("qits.toy.runner", "r1", "qits.toy.runner.item", "a"),
            LifecyclePolicy.explicitLifetime(),
            T);
    assertTrue(started.started(), started::toString);
    assertEquals("qits-toy-runner-item-a", started.containerId());

    List<String> run = docker.calls("run").getFirst();
    assertTrue(run.contains("qits.toy.runner=r1"), run::toString);
    assertEquals(
        Map.of("qits.toy.runner", "r1", "qits.toy.runner.item", "a"),
        docker.containers().get("qits-toy-runner-item-a"));

    assertFalse(
        driver.run(
                ContainerSpec.builder("alpine:3").build(),
                "qits-toy-runner-item-a",
                Map.of("qits.toy.runner", "r1"),
                LifecyclePolicy.explicitLifetime(),
                T)
            .started(),
        "a taken name is docker's own conflict");
  }

  @Test
  void listingAndRemovalAreByExactLabelAndExactName() throws Exception {
    FakeDocker docker = new FakeDocker(dir);
    docker
        .container("mine", Map.of("qits.toy.runner", "r1"))
        .container("theirs", Map.of("qits.toy.runner", "r2"))
        .container("ci", Map.of("qits.ci.runner", "r1"));
    ContainersDriver driver = driver(docker);

    assertEquals(List.of("mine"), driver.listByLabels(Map.of("qits.toy.runner", "r1"), T));
    assertTrue(driver.remove("mine", T).ok());
    assertEquals(List.of("ci", "theirs"), List.copyOf(docker.containers().keySet()));
    assertFalse(driver.remove("mine", T).ok(), "gone is docker's no-such-container");
  }

  @Test
  void aKeyOnlyLabelFilterMatchesEveryValueAndANameFilterMatchesNothing() throws Exception {
    FakeDocker docker = new FakeDocker(dir);
    docker
        .container("mine", Map.of("qits.toy.runner", "r1"))
        .container("theirs", Map.of("qits.toy.runner", "r2"));
    assertEquals(
        List.of("mine", "theirs"), ps(docker, "ps", "-aq", "--filter", "label=qits.toy.runner"));
    // A name= filter is a substring match on real docker: the fake matches nothing, so a caller
    // that relied on one finds out here rather than on a shared node.
    assertEquals(List.of(), ps(docker, "ps", "-aq", "--filter", "name=mine"));
  }

  private static List<String> ps(FakeDocker docker, String... argv) throws Exception {
    List<String> command = new java.util.ArrayList<>(List.of(docker.binary()));
    command.addAll(List.of(argv));
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String out = new String(process.getInputStream().readAllBytes());
    assertEquals(0, process.waitFor());
    return out.lines().filter(l -> !l.isBlank()).toList();
  }

  @Test
  void volumesAreCreatedListedByLabelAndRemoved() throws Exception {
    FakeDocker docker = new FakeDocker(dir);
    ContainersDriver driver = driver(docker);

    assertTrue(
        driver
            .ensureVolume(
                new VolumeSpec("qits-toy-runner-state-r1"), Map.of("qits.toy.runner", "r1"), T)
            .ok());
    docker.volume("qits-ci-runner-state-r1", Map.of("qits.ci.runner", "r1"));
    assertEquals(
        List.of("qits-toy-runner-state-r1"),
        driver.listVolumesByLabels(Map.of("qits.toy.runner", "r1"), T));
    assertTrue(driver.removeVolume("qits-toy-runner-state-r1", T).ok());
    assertEquals(List.of("qits-ci-runner-state-r1"), List.copyOf(docker.volumes().keySet()));
    assertTrue(
        docker.calls().contains(List.of("volume", "rm", "qits-toy-runner-state-r1")),
        docker.calls()::toString);
  }

  @Test
  void aScriptedAnswerWinsOverTheStateAndTheMoreSpecificOneWinsOverTheGeneral() throws Exception {
    FakeDocker docker = new FakeDocker(dir);
    docker.container("c1", Map.of("qits.toy.runner", "r1"));
    docker.answer("ps", 0, "scripted\n", "");
    docker.answerFor("ps", "label=qits.toy.runner=r9", 0, "specific\n", "");
    ContainersDriver driver = driver(docker);

    assertEquals(List.of("scripted"), driver.listByLabels(Map.of("qits.toy.runner", "r1"), T));
    assertEquals(List.of("specific"), driver.listByLabels(Map.of("qits.toy.runner", "r9"), T));
  }
}
