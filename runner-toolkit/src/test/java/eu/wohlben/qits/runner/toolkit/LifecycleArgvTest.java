package eu.wohlben.qits.runner.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.driver.docker.DockerArgv;
import eu.wohlben.qits.runner.protocol.RunnerVocabulary;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The argv IS the sandbox, so it is asserted element for element. The CI-shaped identity below is
 * test-only: it proves the identity-derived argv spells exactly what qits-ci-runner-daemon's {@code
 * RunnerArgv} spelled at 2026.1003.50846, so qits-772 moves the CI runner onto it unchanged.
 */
class LifecycleArgvTest {

  static final RunnerIdentity CI =
      new RunnerIdentity(
          "ci",
          "QITS_CI_RUNNER_",
          "qits.ci.runner",
          "qits-ci-runner",
          "qits/qits-ci-runner",
          "/var/lib/qits-ci-runner",
          "/ci/api/runners/{id}/register",
          "qits-ci-runner",
          "2026.1003.50846",
          1,
          new RunnerVocabulary("heldRuns", "adoptedRuns"),
          "run");

  private static SelfSpec self(Map<String, String> labels) {
    return new SelfSpec(
        "0123456789ab",
        "sha256:old",
        Map.of(
            "QITS_CI_RUNNER_URL", "https://ci.qits.example.eu",
            "QITS_CI_RUNNER_ID", "3f2a9c1e-0000-4000-8000-000000000001",
            "QITS_CI_RUNNER_SLOTS", "2",
            "QITS_CI_RUNNER_REGISTRATION_TOKEN", "qits_tok_SPENT"),
        labels,
        List.of(
            "/var/run/docker.sock:/var/run/docker.sock",
            "qits-ci-runner-state-3f2a9c1e:/var/lib/qits-ci-runner"),
        List.of(),
        "unless-stopped",
        "default");
  }

  @Test
  void theSuccessorIsTheContainerContractWithANewImageNameAndVersionAndNoToken() {
    assertEquals(
        List.of(
            "docker",
            "run",
            "-d",
            "--name",
            "qits-ci-runner-3f2a9c1e-2026.929.1",
            "--restart",
            "unless-stopped",
            "--label",
            "qits.ci.runner.process=3f2a9c1e-0000-4000-8000-000000000001",
            "--label",
            "qits.ci.runner.version=2026.929.1",
            "-v",
            "/var/run/docker.sock:/var/run/docker.sock",
            "-v",
            "qits-ci-runner-state-3f2a9c1e:/var/lib/qits-ci-runner",
            "-e",
            "QITS_CI_RUNNER_ID=3f2a9c1e-0000-4000-8000-000000000001",
            "-e",
            "QITS_CI_RUNNER_SLOTS=2",
            "-e",
            "QITS_CI_RUNNER_URL=https://ci.qits.example.eu",
            "registry.qits.example.eu/qits/qits-ci-runner:2026.929.1"),
        LifecycleArgv.runSuccessor(
            CI,
            "docker",
            "3f2a9c1e-0000-4000-8000-000000000001",
            "2026.929.1",
            "registry.qits.example.eu/qits/qits-ci-runner:2026.929.1",
            self(
                Map.of(
                    "qits.ci.runner.process", "3f2a9c1e-0000-4000-8000-000000000001",
                    "qits.ci.runner.version", "2026.928.93942"))));
  }

  /**
   * The decommission helper removes every container labelled {@code <labelRoot>=<id>}. A runner
   * container that carried it would be removed by its own runner — so no runner container ever
   * does, even when the one it inherits from somehow did.
   */
  @Test
  void aRunnerContainerCanNeverMatchItsOwnSweep() {
    String sweep = DockerArgv.psByLabels("docker", Map.of(CI.ownerLabel(), "r1")).getLast();
    assertEquals("label=qits.ci.runner=r1", sweep);
    List<String> successor =
        LifecycleArgv.runSuccessor(
            CI,
            "docker",
            "r1",
            "2",
            "r/qits/qits-ci-runner:2",
            self(Map.of("qits.ci.runner", "r1", "qits.ci.runner.run", "run-1", "keep", "me")));
    for (int i = 0; i < successor.size() - 1; i++) {
      if (successor.get(i).equals("--label")) {
        String label = successor.get(i + 1);
        assertFalse(label.startsWith("qits.ci.runner="), label);
        assertFalse(label.startsWith("qits.ci.runner.run="), label);
      }
    }
    assertTrue(successor.contains("keep=me"), "an operator's own label is carried");
    assertTrue(successor.contains("qits.ci.runner.process=r1"));
    assertEquals(
        "label=qits.ci.runner.process=r1", LifecycleArgv.psProcess(CI, "docker", "r1").getLast());
  }

  @Test
  void aSuccessorOnAnOperatorsNetworkStaysOnIt() {
    SelfSpec onNet =
        new SelfSpec(
            "i",
            "sha256:old",
            Map.of(),
            Map.of(),
            List.of(),
            List.of("type=volume,source=x,target=/x"),
            "always",
            "qits-net");
    List<String> argv = LifecycleArgv.runSuccessor(CI, "docker", "r1", "2", "r/i:2", onNet);
    assertEquals(
        List.of(
            "docker", "run", "-d", "--name", "qits-ci-runner-r1-2", "--restart", "always",
            "--network", "qits-net", "--label", "qits.ci.runner.process=r1", "--label",
            "qits.ci.runner.version=2", "--mount", "type=volume,source=x,target=/x", "r/i:2"),
        argv);
  }

  @Test
  void aVersionOutsideItsCharsetNamesNoContainer() {
    assertThrows(
        IllegalArgumentException.class,
        () -> LifecycleArgv.containerName(CI, "r1", "2;rm -rf /"));
    assertThrows(IllegalArgumentException.class, () -> LifecycleArgv.containerName(CI, "r1", ""));
    assertEquals(
        "qits-ci-runner-abcdefgh-1.2", LifecycleArgv.containerName(CI, "abcdefghijk", "1.2"));
    assertEquals(
        "qits-toy-runner-abcdefgh-1.2",
        LifecycleArgv.containerName(Toy.IDENTITY, "abcdefghijk", "1.2"),
        "every runner its own prefix");
  }

  @Test
  void theSelfUpdateReadsAreWholeDocumentsAndOneLineAnswers() {
    assertEquals(List.of("docker", "inspect", "0123456789ab"), LifecycleArgv.inspectSelf("docker", "0123456789ab"));
    assertEquals(
        List.of("docker", "image", "inspect", "sha256:old"),
        LifecycleArgv.imageConfig("docker", "sha256:old"));
    assertEquals(
        List.of("docker", "image", "inspect", "--format", "{{json .RepoDigests}}", "r/i:2"),
        LifecycleArgv.repoDigests("docker", "r/i:2"));
    assertEquals(
        List.of("docker", "inspect", "--format", "{{.State.Status}}", "abc"),
        LifecycleArgv.state("docker", "abc"));
    assertEquals(
        List.of("docker", "update", "--restart=no", "abc"), LifecycleArgv.noRestart("docker", "abc"));
    assertEquals(
        List.of("docker", "--config", "/tmp/login", "pull", "r/i:2"),
        LifecycleArgv.pull("docker", Path.of("/tmp/login"), "r/i:2"));
    assertEquals(List.of("docker", "pull", "r/i:2"), LifecycleArgv.pull("docker", null, "r/i:2"));
    assertEquals(
        List.of(
            "docker",
            "ps",
            "-a",
            "--format",
            "{{.ID}}|{{.Label \"qits.ci.runner.version\"}}|{{.State}}|{{.Image}}",
            "--filter",
            "label=qits.ci.runner.process=r1"),
        LifecycleArgv.psProcess(CI, "docker", "r1"));
    assertThrows(IllegalArgumentException.class, () -> LifecycleArgv.inspectSelf("docker", "-f"));
    assertThrows(IllegalArgumentException.class, () -> LifecycleArgv.noRestart("docker", "--all"));
  }

  @Test
  void theDecommissionHelperIsTheRunnersOwnImageInHelperModeWithTheSocketAndNothingElse() {
    assertEquals(
        List.of(
            "docker",
            "run",
            "-d",
            "--name",
            "qits-ci-runner-c4374992-decommission",
            "--network",
            "none",
            "--label",
            "qits.ci.runner.decommission=c4374992-0000-4000-8000-000000000000",
            "-v",
            "/var/run/docker.sock:/var/run/docker.sock",
            "-e",
            "QITS_CI_RUNNER_DECOMMISSION=c4374992-0000-4000-8000-000000000000",
            "-e",
            "QITS_CI_RUNNER_DECOMMISSION_VOLUME=qits-ci-runner-state-c4374992",
            "sha256:0123abcd"),
        LifecycleArgv.runDecommissioner(
            CI,
            "docker",
            "c4374992-0000-4000-8000-000000000000",
            "sha256:0123abcd",
            "qits-ci-runner-state-c4374992",
            List.of()));
    List<String> noVolume =
        LifecycleArgv.runDecommissioner(CI, "docker", "r1", "sha256:0123abcd", null, List.of());
    assertTrue(noVolume.stream().noneMatch(a -> a.contains("VOLUME")), noVolume::toString);
    assertFalse(noVolume.contains("--rm"), "the helper removes itself only once it succeeded");
    assertFalse(
        noVolume.stream()
            .anyMatch(a -> a.startsWith("qits.ci.runner.process") || a.startsWith("qits.ci.runner=")),
        "a helper carrying a label it removes by would remove itself mid-job");
    assertEquals(
        List.of("docker", "volume", "rm", "qits-ci-runner-state-r1"),
        LifecycleArgv.volumeRm(CI, "docker", "qits-ci-runner-state-r1"));
    assertThrows(IllegalArgumentException.class, () -> LifecycleArgv.volumeRm(CI, "docker", "-f"));
    assertThrows(
        IllegalArgumentException.class,
        () -> LifecycleArgv.runDecommissioner(CI, "docker", "r1", "sha256:x", "--privileged", List.of()));
  }

  @Test
  void theDecommissionHelperCarriesTheDomainsTeardownItemsOnlyWhenThereAreAny() {
    List<TeardownItem> items =
        List.of(
            new TeardownItem(TeardownItem.Type.CONTAINER, "qits-toy-runner-builder-r1"),
            new TeardownItem(TeardownItem.Type.NETWORK, "qits-toy-runner-net-r1"));
    List<String> argv =
        LifecycleArgv.runDecommissioner(Toy.IDENTITY, "docker", "r1", "sha256:x", null, items);
    assertTrue(
        argv.contains(
            "QITS_TOY_RUNNER_DECOMMISSION_TEARDOWN="
                + "container:qits-toy-runner-builder-r1,network:qits-toy-runner-net-r1"),
        argv::toString);
    assertEquals(
        items,
        LifecycleArgv.decodeTeardown(
            "container:qits-toy-runner-builder-r1,network:qits-toy-runner-net-r1"));
    assertTrue(
        LifecycleArgv.runDecommissioner(Toy.IDENTITY, "docker", "r1", "sha256:x", null, List.of())
            .stream()
            .noneMatch(a -> a.contains("TEARDOWN")));
    assertThrows(IllegalArgumentException.class, () -> LifecycleArgv.decodeTeardown("image:x"));
  }

  @Test
  void aNamedRemovalOutsideTheRunnersPrefixIsRefusedAtTheArgv() {
    assertEquals(
        List.of("docker", "rm", "-f", "qits-toy-runner-builder-r1"),
        LifecycleArgv.teardown(
            Toy.IDENTITY,
            "docker",
            new TeardownItem(TeardownItem.Type.CONTAINER, "qits-toy-runner-builder-r1")));
    for (TeardownItem.Type type : TeardownItem.Type.values()) {
      IllegalArgumentException refused =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  LifecycleArgv.teardown(
                      Toy.IDENTITY, "docker", new TeardownItem(type, "qits-ci-runner-buildkitd")));
      assertTrue(refused.getMessage().contains("qits-toy-runner-"), refused::getMessage);
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> LifecycleArgv.volumeRm(Toy.IDENTITY, "docker", "qits-buildkitd-state"));
    assertThrows(
        IllegalArgumentException.class,
        () -> LifecycleArgv.volumeRm(Toy.IDENTITY, "docker", "qits-toy-runnerx-state"),
        "the prefix is a whole segment, not a substring");
  }

  @Test
  void theNodeWideImageVerbsNeverForceAndNeverPruneAll() {
    assertEquals(List.of("docker", "image", "rm", "r/i:1"), LifecycleArgv.imageRm("docker", "r/i:1"));
    assertEquals(List.of("docker", "image", "prune", "-f"), LifecycleArgv.imagePruneDangling("docker"));
    assertEquals(
        List.of("docker", "ps", "-a", "-q", "--filter", "ancestor=sha256:old"),
        LifecycleArgv.psAncestor("docker", "sha256:old"));
    assertEquals(
        List.of("docker", "image", "ls", "--no-trunc", "--format", "{{.Repository}}:{{.Tag}}|{{.ID}}"),
        LifecycleArgv.imageList("docker"));
  }

  /**
   * Every argv the toolkit builds, over a fixture identity: none prunes everything, none prunes the
   * system, a volume or a container, none filters by name, none self-removes. The set of methods is
   * read off the class, so a verb added without being listed here fails this test.
   */
  @Test
  void noArgvTheToolkitBuildsContainsPruneMinusAOrSystemPrune() {
    RunnerIdentity id = Toy.IDENTITY;
    SelfSpec self = self(Map.of("k", "v"));
    Map<String, List<String>> every = new LinkedHashMap<>();
    every.put("inspectSelf", LifecycleArgv.inspectSelf("docker", "c1"));
    every.put("imageConfig", LifecycleArgv.imageConfig("docker", "sha256:old"));
    every.put("repoDigests", LifecycleArgv.repoDigests("docker", "r/i:2"));
    every.put("state", LifecycleArgv.state("docker", "c1"));
    every.put("containerImage", LifecycleArgv.containerImage("docker", "c1"));
    every.put("imageInspect", LifecycleArgv.imageInspect("docker", "r/i:2"));
    every.put("pull", LifecycleArgv.pull("docker", Path.of("/tmp/x"), "r/i:2"));
    every.put("noRestart", LifecycleArgv.noRestart("docker", "c1"));
    every.put("rm", LifecycleArgv.rm("docker", "c1"));
    every.put("psProcess", LifecycleArgv.psProcess(id, "docker", "r1"));
    every.put("runSuccessor", LifecycleArgv.runSuccessor(id, "docker", "r1", "2", "r/i:2", self));
    every.put(
        "runDecommissioner",
        LifecycleArgv.runDecommissioner(
            id,
            "docker",
            "r1",
            "sha256:old",
            id.stateVolume("r1"),
            List.of(new TeardownItem(TeardownItem.Type.VOLUME, id.namePrefix() + "-x-r1"))));
    every.put("volumeRm", LifecycleArgv.volumeRm(id, "docker", id.stateVolume("r1")));
    every.put(
        "teardown",
        LifecycleArgv.teardown(
            id, "docker", new TeardownItem(TeardownItem.Type.NETWORK, id.namePrefix() + "-n-r1")));
    every.put("imageList", LifecycleArgv.imageList("docker"));
    every.put("psAncestor", LifecycleArgv.psAncestor("docker", "sha256:old"));
    every.put("imageRm", LifecycleArgv.imageRm("docker", "r/i:1"));
    every.put("imagePruneDangling", LifecycleArgv.imagePruneDangling("docker"));

    Set<String> argvMethods =
        Arrays.stream(LifecycleArgv.class.getDeclaredMethods())
            .filter(m -> Modifier.isStatic(m.getModifiers()) && Modifier.isPublic(m.getModifiers()))
            .filter(m -> m.getReturnType() == List.class)
            .map(Method::getName)
            .collect(Collectors.toCollection(TreeSet::new));
    assertEquals(argvMethods, new TreeSet<>(every.keySet()), "every argv verb is listed here");

    every.forEach(
        (verb, argv) -> {
          if (argv.contains("prune")) {
            assertFalse(argv.contains("-a") || argv.contains("--all"), verb + ": " + argv);
            assertEquals("image", argv.get(argv.indexOf("prune") - 1), verb + ": " + argv);
          }
          assertFalse(argv.contains("system"), verb + ": " + argv);
          assertFalse(argv.contains("--rm"), verb + ": " + argv);
          assertTrue(argv.stream().noneMatch(a -> a.startsWith("name=")), verb + ": " + argv);
          if (argv.size() > 2 && argv.get(1).equals("image") && argv.get(2).equals("rm")) {
            assertFalse(argv.contains("-f") || argv.contains("--force"), verb + ": " + argv);
          }
        });
  }
}
