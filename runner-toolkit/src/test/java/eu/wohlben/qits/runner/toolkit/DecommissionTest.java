package eu.wohlben.qits.runner.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.toolkit.testing.FakeDocker;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The decommission helper's job against a shell script answering like docker: every container of
 * the runner, waited for and removed, its workloads, its labelled volumes, the domain's teardown
 * items that are its own, its state volume, and itself last — and nothing that is not its own.
 */
@EnabledOnOs(OS.LINUX)
class DecommissionTest {

  private static final Path STATE = Path.of("/var/lib/qits-toy-runner");
  private static final String HELPER = "dddddddddddd";
  private static final String STATE_VOLUME = "qits-toy-runner-state-r1";
  private static final Decommission.Settings FAST = new Decommission.Settings(500, 20, 3);

  @TempDir Path dockerDir;
  private FakeDocker docker;
  private final List<String> said = new ArrayList<>();

  @BeforeEach
  void setUp() throws Exception {
    docker = new FakeDocker(dockerDir);
  }

  private int finish(String volume) {
    return finish(volume, List.of());
  }

  private int finish(String volume, List<TeardownItem> teardown) {
    return DecommissionHelper.finish(
        Toy.IDENTITY,
        new RunnerDocker(Toy.IDENTITY, docker.binary(), Duration.ofSeconds(10)),
        "r1",
        volume,
        teardown,
        Optional.of(HELPER),
        FAST,
        said::add);
  }

  /** The removals, in order, without the reads between them. */
  private List<List<String>> removals() {
    return docker.calls().stream()
        .filter(
            c ->
                c.getFirst().equals("rm")
                    || (c.size() > 1
                        && (c.getFirst().equals("volume") || c.getFirst().equals("network"))
                        && c.get(1).equals("rm")))
        .toList();
  }

  @Test
  void theHelperRemovesEveryRunnerContainerThenTheWorkloadsThenTheVolumeThenItself()
      throws Exception {
    docker.answerFor(
        "ps",
        "label=qits.toy.runner.process=r1",
        0,
        "aaaaaaaaaaaa|2026.928.1|running\nbbbbbbbbbbbb|2026.928.2|exited\n",
        "");
    docker.answerFor("ps", "label=qits.toy.runner=r1", 0, "work1\nwork2\n", "");
    docker.answerFor("inspect", "aaaaaaaaaaaa", 0, "exited\n", "");
    docker.answerFor("inspect", "bbbbbbbbbbbb", 0, "exited\n", "");
    for (String name : List.of("aaaaaaaaaaaa", "bbbbbbbbbbbb", "work1", "work2")) {
      docker.container(name, Map.of());
    }

    assertEquals(0, finish(STATE_VOLUME), () -> String.join("\n", said));

    assertEquals(
        List.of(
            List.of("rm", "-f", "aaaaaaaaaaaa"),
            List.of("rm", "-f", "bbbbbbbbbbbb"),
            List.of("rm", "-f", "work1"),
            List.of("rm", "-f", "work2"),
            List.of("volume", "rm", STATE_VOLUME),
            List.of("rm", "-f", HELPER)),
        removals());
    assertTrue(
        docker.calls().contains(List.of("ps", "-aq", "--filter", "label=qits.toy.runner=r1")),
        "the workloads are selected by the exact ownership label: " + docker.calls());
    int waited =
        docker.calls().indexOf(List.of("inspect", "--format", "{{.State.Status}}", "aaaaaaaaaaaa"));
    assertTrue(
        waited >= 0 && waited < docker.calls().indexOf(List.of("rm", "-f", "aaaaaaaaaaaa")),
        "a running runner is waited for before it is removed");
  }

  /**
   * Volumes are selected by {@code label=<labelRoot>=<id>} and nothing else: another runner of the
   * same kind, a runner of another kind, qits-containers-service's own and an unlabelled one with
   * this runner's very prefix all stay.
   */
  @Test
  void theHelperRemovesTheRunnersLabelledVolumesAndNoOther() throws Exception {
    docker.volume("qits-toy-runner-dot-claude-r1", Map.of("qits.toy.runner", "r1"));
    docker.volume(
        "qits-toy-runner-work-1", Map.of("qits.toy.runner", "r1", "qits.toy.runner.item", "w1"));
    docker.volume("qits-toy-runner-dot-claude-r2", Map.of("qits.toy.runner", "r2"));
    docker.volume("qits-ci-runner-state-r1", Map.of("qits.ci.runner", "r1"));
    docker.volume("qits-buildkitd-state", Map.of());
    docker.volume("cs-volume", Map.of("qits.containers.managed", "volume"));
    docker.volume("qits-toy-runner-unlabelled", Map.of());
    docker.container("qits-toy-runner-work-r2", Map.of("qits.toy.runner", "r2"));
    docker.container("work-r1", Map.of("qits.toy.runner", "r1"));

    assertEquals(0, finish(null), () -> String.join("\n", said));

    assertEquals(
        Set.of(
            "qits-toy-runner-dot-claude-r2",
            "qits-ci-runner-state-r1",
            "qits-buildkitd-state",
            "cs-volume",
            "qits-toy-runner-unlabelled"),
        docker.volumes().keySet());
    assertEquals(Set.of("qits-toy-runner-work-r2"), docker.containers().keySet());
    assertTrue(
        docker.calls().contains(List.of("volume", "ls", "-q", "--filter", "label=qits.toy.runner=r1")),
        docker.calls()::toString);
  }

  @Test
  void aTeardownItemWithoutThePrefixIsRefused() throws Exception {
    List<TeardownItem> offered =
        List.of(
            new TeardownItem(TeardownItem.Type.CONTAINER, "qits-ci-runner-buildkitd"),
            new TeardownItem(TeardownItem.Type.VOLUME, "qits-buildkitd-state"),
            new TeardownItem(TeardownItem.Type.NETWORK, "qits-ci-runner"),
            new TeardownItem(TeardownItem.Type.CONTAINER, "qits-toy-runnerx-builder"),
            new TeardownItem(TeardownItem.Type.CONTAINER, "qits-toy-runner-builder-r1"),
            new TeardownItem(TeardownItem.Type.NETWORK, "qits-toy-runner-net-r1"));

    assertEquals(0, finish(null, offered), () -> String.join("\n", said));

    assertEquals(
        List.of(
            List.of("rm", "-f", "qits-toy-runner-builder-r1"),
            List.of("network", "rm", "qits-toy-runner-net-r1"),
            List.of("rm", "-f", HELPER)),
        removals(),
        "only the items with this runner's prefix are removed");
    for (String foreign :
        List.of("qits-ci-runner-buildkitd", "qits-buildkitd-state", "qits-ci-runner")) {
      assertTrue(
          docker.calls().stream().noneMatch(c -> c.contains(foreign)),
          () -> foreign + " was named in " + docker.calls());
      assertTrue(
          said.stream().anyMatch(line -> line.startsWith("refused") && line.contains(foreign)),
          () -> String.join("\n", said));
    }
  }

  @Test
  void aStateVolumeOfAnotherNameIsTheOperatorsAndIsLeft() throws Exception {
    assertEquals(0, finish("operators-own-state"), () -> String.join("\n", said));
    assertTrue(docker.calls().stream().noneMatch(c -> c.contains("operators-own-state")));
    assertTrue(said.stream().anyMatch(line -> line.contains("refused to remove the state volume")));
  }

  @Test
  void aFailingTeardownRemovalIsSaidAndDoesNotFailTheDecommission() throws Exception {
    docker.answerFor(
        "rm",
        "qits-toy-runner-builder-r1",
        1,
        "",
        "Error response from daemon: cannot remove container");

    assertEquals(
        0,
        finish(
            null,
            List.of(new TeardownItem(TeardownItem.Type.CONTAINER, "qits-toy-runner-builder-r1"))),
        () -> String.join("\n", said));
    assertTrue(
        said.stream().anyMatch(line -> line.contains("could not remove the container")),
        () -> String.join("\n", said));
  }

  @Test
  void aVolumeStillInUseIsRetriedAndOneThatStaysLeavesTheHelperStanding() throws Exception {
    docker.answerFor(
        "volume-rm", STATE_VOLUME, 1, "", "Error response from daemon: remove x: volume is in use");

    assertEquals(1, finish(STATE_VOLUME));
    assertEquals(
        3,
        docker.calls("volume").stream().filter(c -> c.contains(STATE_VOLUME)).count(),
        "retried, since a volume is released late");
    assertFalse(
        docker.calls().contains(List.of("rm", "-f", HELPER)),
        "a helper that failed stays, with its log");
  }

  @Test
  void anAbsentVolumeIsAsGoodAsARemovedOne() throws Exception {
    docker.answer("volume-rm", 1, "", "Error response from daemon: get x: no such volume");
    assertEquals(0, finish(STATE_VOLUME));
    assertTrue(docker.calls().contains(List.of("rm", "-f", HELPER)));
  }

  @Test
  void aListingThatFailsLeavesTheHelperStanding() throws Exception {
    docker.answerFor("ps", "label=qits.toy.runner=r1", 1, "", "Cannot connect to the daemon");
    assertEquals(1, finish(null));
    assertFalse(docker.calls().contains(List.of("rm", "-f", HELPER)));
  }

  @Test
  void withNoVolumeNamedNoNamedVolumeIsTouched() throws Exception {
    assertEquals(0, finish(null));
    assertEquals(
        List.of(List.of("volume", "ls", "-q", "--filter", "label=qits.toy.runner=r1")),
        docker.calls("volume"));
  }

  @Test
  void theStateVolumeIsReadFromTheMountAtTheStateDirectory() {
    String mounts =
        new JsonArray()
            .add(
                new JsonObject()
                    .put(
                        "Mounts",
                        new JsonArray()
                            .add(
                                new JsonObject()
                                    .put("Type", "bind")
                                    .put("Source", "/var/run/docker.sock")
                                    .put("Destination", "/var/run/docker.sock"))
                            .add(
                                new JsonObject()
                                    .put("Type", "volume")
                                    .put("Name", "my-state")
                                    .put("Destination", STATE.toString()))))
            .encode();
    assertEquals(Optional.of("my-state"), Decommission.stateVolume(mounts, STATE));

    String bindsOnly =
        new JsonArray()
            .add(
                new JsonObject()
                    .put(
                        "HostConfig",
                        new JsonObject()
                            .put(
                                "Binds",
                                List.of(
                                    "/var/run/docker.sock:/var/run/docker.sock",
                                    STATE_VOLUME + ":" + STATE))))
            .encode();
    assertEquals(Optional.of(STATE_VOLUME), Decommission.stateVolume(bindsOnly, STATE));

    String hostPath =
        new JsonArray()
            .add(
                new JsonObject()
                    .put(
                        "HostConfig",
                        new JsonObject().put("Binds", List.of("/srv/runner:" + STATE))))
            .encode();
    assertEquals(
        Optional.empty(),
        Decommission.stateVolume(hostPath, STATE),
        "a host directory is the operator's, never the runner's to delete");

    String declared =
        new JsonArray()
            .add(
                new JsonObject()
                    .put(
                        "HostConfig",
                        new JsonObject()
                            .put(
                                "Mounts",
                                new JsonArray()
                                    .add(
                                        new JsonObject(
                                            Map.of(
                                                "Type", "volume",
                                                "Source", "declared-state",
                                                "Target", STATE.toString()))))))
            .encode();
    assertEquals(Optional.of("declared-state"), Decommission.stateVolume(declared, STATE));
    assertEquals(Optional.empty(), Decommission.stateVolume("[{}]", STATE));
  }
}
