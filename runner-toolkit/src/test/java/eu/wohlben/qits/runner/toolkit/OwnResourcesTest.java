package eu.wohlben.qits.runner.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.driver.ContainersDriver;
import eu.wohlben.qits.containers.driver.spec.ContainerSpec;
import eu.wohlben.qits.containers.driver.spec.LifecyclePolicy;
import eu.wohlben.qits.containers.driver.spec.VolumeSpec;
import eu.wohlben.qits.runner.protocol.RunnerVocabulary;
import eu.wohlben.qits.runner.toolkit.testing.FakeDocker;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The sweep touches its own label and nothing else — qits-ci-runner-daemon's {@code BootSweepTest},
 * generalised. "Nothing else" is the filter itself: the only listing is by {@code
 * <labelRoot>=<runnerId>}, and the only removals are of ids that listing answered. Against the
 * fake's state model, so what is left on the node is read back rather than inferred from argv.
 */
@EnabledOnOs(OS.LINUX)
class OwnResourcesTest {

  private static final Duration DEADLINE = Duration.ofSeconds(10);

  @TempDir Path dir;
  private FakeDocker fake;

  @BeforeEach
  void setUp() throws Exception {
    fake = new FakeDocker(dir);
  }

  private OwnResources own(RunnerIdentity identity, String runnerId) {
    return new OwnResources(
        identity, runnerId, new RunnerDocker(identity, fake.binary(), DEADLINE));
  }

  @Test
  void theLabelsAreTheOwnershipLabelAndTheWorkItemLabel() {
    assertEquals(
        Map.of("qits.toy.runner", "r1", "qits.toy.runner.item", "w1"),
        own(Toy.IDENTITY, "r1").labels("w1"));
    assertThrows(IllegalArgumentException.class, () -> own(Toy.IDENTITY, "r1").labels("a b"));
  }

  @Test
  void reconcileRemovesOwnContainersOutsideTheKeepSetAndNothingElse() throws Exception {
    fake.container(
        "qits-toy-runner-item-keep",
        Map.of("qits.toy.runner", "r1", "qits.toy.runner.item", "keep"));
    fake.container(
        "qits-toy-runner-item-stale",
        Map.of("qits.toy.runner", "r1", "qits.toy.runner.item", "stale"));
    fake.container("qits-toy-runner-unworked", Map.of("qits.toy.runner", "r1"));
    // Not this runner's: another runner of the kind, a runner of another kind, the runner's own
    // container, qits-containers-service's, and one with the very prefix but no label.
    fake.container(
        "qits-toy-runner-item-other",
        Map.of("qits.toy.runner", "r2", "qits.toy.runner.item", "stale"));
    fake.container(
        "qits-ci-runner-item-x", Map.of("qits.ci.runner", "r1", "qits.ci.runner.run", "stale"));
    fake.container("qits-toy-runner-r1-2026.1.1", Map.of("qits.toy.runner.process", "r1"));
    fake.container("cs-workload", Map.of("qits.containers.managed", "container"));
    fake.container("qits-toy-runner-unlabelled", Map.of());
    // What the one `docker ps --filter <owner> --format ...` snapshot would answer: this
    // runner's three containers, id and work-item label together, nothing else reached.
    fake.answer(
        "ps",
        0,
        String.join(
            "\n",
            "qits-toy-runner-item-keep keep",
            "qits-toy-runner-item-stale stale",
            "qits-toy-runner-unworked ",
            ""),
        "");

    assertEquals(2, own(Toy.IDENTITY, "r1").reconcile(Set.of("keep")));

    assertEquals(
        Set.of(
            "qits-toy-runner-item-keep",
            "qits-toy-runner-item-other",
            "qits-ci-runner-item-x",
            "qits-toy-runner-r1-2026.1.1",
            "cs-workload",
            "qits-toy-runner-unlabelled"),
        fake.containers().keySet());
    assertEquals(
        List.of(
            List.of(
                "ps",
                "-a",
                "--filter",
                "label=qits.toy.runner=r1",
                "--format",
                "{{.ID}} {{.Label \"qits.toy.runner.item\"}}")),
        fake.calls("ps"));
    assertEquals(
        List.of(
            List.of("rm", "-f", "qits-toy-runner-item-stale"),
            List.of("rm", "-f", "qits-toy-runner-unworked")),
        fake.calls("rm"));
  }

  @Test
  void reconcileNeverRemovesAVolume() throws Exception {
    fake.container(
        "qits-toy-runner-item-stale",
        Map.of("qits.toy.runner", "r1", "qits.toy.runner.item", "stale"));
    fake.volume(
        "qits-toy-runner-item-stale",
        Map.of("qits.toy.runner", "r1", "qits.toy.runner.item", "stale"));
    fake.volume("qits-toy-runner-dot-claude-r1", Map.of("qits.toy.runner", "r1"));
    fake.answer("ps", 0, "qits-toy-runner-item-stale stale\n", "");

    assertEquals(1, own(Toy.IDENTITY, "r1").reconcile(Set.of()));

    assertEquals(
        Set.of("qits-toy-runner-item-stale", "qits-toy-runner-dot-claude-r1"),
        fake.volumes().keySet());
    assertTrue(fake.calls("volume").isEmpty(), fake.calls()::toString);
    // The listing a domain reaches for instead, by the same exact filter.
    assertEquals(
        List.of("qits-toy-runner-dot-claude-r1", "qits-toy-runner-item-stale"),
        own(Toy.IDENTITY, "r1").volumes().stream().sorted().toList());
    assertEquals(
        List.of("volume", "ls", "-q", "--filter", "label=qits.toy.runner=r1"),
        fake.calls("volume").getLast());
  }

  @Test
  void aListingFailureSweepsNothing() throws Exception {
    fake.container(
        "qits-toy-runner-item-stale",
        Map.of("qits.toy.runner", "r1", "qits.toy.runner.item", "stale"));
    fake.answer("ps", 1, "", "Cannot connect to the Docker daemon");

    // The one snapshot listing failing is not read as "nothing there": nothing goes either way,
    // a wrong guess being worse than a leftover that waits for the next pass.
    assertEquals(0, own(Toy.IDENTITY, "r1").reconcile(Set.of("keep")));
    assertTrue(fake.calls("rm").isEmpty(), fake.calls()::toString);
    OwnResources.ListingFailed failed =
        assertThrows(OwnResources.ListingFailed.class, () -> own(Toy.IDENTITY, "r1").containers());
    assertTrue(failed.getMessage().contains("Cannot connect"), failed::getMessage);
    assertEquals(Set.of("qits-toy-runner-item-stale"), fake.containers().keySet());
  }

  @Test
  void reconcileNeverActsOnAContainerItsSnapshotNeverSaw() throws Exception {
    // A container for a kept work item, present on the node — launched concurrently with the
    // sweep, say — but the one docker ps snapshot never named it: whether that is because it did
    // not exist yet when docker answered, or any other reason, reconcile has nothing to decide
    // it against and so cannot touch it. This is qits-1233: the old two-listing shape instead
    // read such a container as outside the keep set and removed it mid-launch.
    fake.container(
        "qits-toy-runner-item-3801",
        Map.of("qits.toy.runner", "r1", "qits.toy.runner.item", "3801"));
    fake.answer("ps", 0, "", "");

    assertEquals(0, own(Toy.IDENTITY, "r1").reconcile(Set.of("3801")));

    assertTrue(fake.calls("rm").isEmpty(), fake.calls()::toString);
    assertEquals(Set.of("qits-toy-runner-item-3801"), fake.containers().keySet());
  }

  @Test
  void reconcileDecidesEveryContainerFromTheOneSnapshotWithItsWorkLabel() throws Exception {
    // The live shape of qits-1233: a sweep deciding against work items 1, 2, 3 and a concurrently
    // launched workspace 3801, all four correctly read off the one snapshot and kept.
    fake.container("a", Map.of("qits.toy.runner", "r1", "qits.toy.runner.item", "1"));
    fake.container("b", Map.of("qits.toy.runner", "r1", "qits.toy.runner.item", "2"));
    fake.container("c", Map.of("qits.toy.runner", "r1", "qits.toy.runner.item", "3"));
    fake.container("d", Map.of("qits.toy.runner", "r1", "qits.toy.runner.item", "3801"));
    // Outside the keep set, and carrying no work-item label at all: removed either way.
    fake.container("e", Map.of("qits.toy.runner", "r1", "qits.toy.runner.item", "9"));
    fake.container("f", Map.of("qits.toy.runner", "r1"));
    fake.answer(
        "ps",
        0,
        String.join("\n", "a 1", "b 2", "c 3", "d 3801", "e 9", "f ", ""),
        "");

    assertEquals(2, own(Toy.IDENTITY, "r1").reconcile(Set.of("1", "2", "3", "3801")));

    assertEquals(Set.of("a", "b", "c", "d"), fake.containers().keySet());
    assertEquals(List.of(List.of("rm", "-f", "e"), List.of("rm", "-f", "f")), fake.calls("rm"));
  }

  /**
   * The labels travel as the driver's own-namespace {@code labels} argument, and a harness-shaped
   * launch through the real {@code DockerContainersDriver} is accepted that way. As {@code
   * ContainerSpec.extraLabels} the same map is refused — the work-item key sits inside the driver's
   * namespace {@code <labelRoot>.} — which is why it must never be passed there.
   */
  @Test
  void aWorkloadLaunchedThroughTheDriverWithTheseLabelsIsAccepted() throws Exception {
    RunnerIdentity harness =
        new RunnerIdentity(
            "harness",
            "QITS_HARNESS_RUNNER_",
            "qits.harness.runner",
            "qits-harness-runner",
            "qits/qits-harness-runner",
            "/var/lib/qits-harness-runner",
            "/harness/api/runners/{id}/register",
            "qits-harness-runner",
            "2026.1.1",
            1,
            new RunnerVocabulary("heldItems", "adoptedItems"),
            "item");
    String runnerId = "5e6f7a8b-0000-0000-0000-000000000001";
    RunnerDocker docker = new RunnerDocker(harness, fake.binary(), DEADLINE);
    OwnResources own = new OwnResources(harness, runnerId, docker);
    ContainersDriver driver = docker.driver();
    String name = harness.nodeName("itemabcd-1", "item");
    String volume = harness.nodeVolume("itemabcd-1", "scratch");
    Map<String, String> labels = own.labels("itemabcd-1");

    assertTrue(driver.ensureVolume(new VolumeSpec(volume), labels, DEADLINE).ok());
    ContainersDriver.Started started =
        driver.run(
            ContainerSpec.builder("registry.example/qits/harness-item:1")
                .mount(volume, "/scratch")
                .build(),
            name,
            labels,
            LifecyclePolicy.explicitLifetime(),
            DEADLINE);

    assertTrue(started.started(), () -> String.valueOf(started.detail()));
    assertEquals("qits-harness-runner-item-itemabcd", name);
    Map<String, String> expected =
        Map.of("qits.harness.runner", runnerId, "qits.harness.runner.item", "itemabcd-1");
    assertEquals(expected, fake.containers().get(name));
    assertEquals(expected, fake.volumes().get("qits-harness-runner-scratch-itemabcd"));
    assertEquals(List.of(name), own.containers());
    assertEquals(List.of(volume), own.volumes());

    IllegalArgumentException refused =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                driver.run(
                    ContainerSpec.builder("registry.example/qits/harness-item:1")
                        .label("qits.harness.runner.item", "itemabcd-1")
                        .build(),
                    harness.nodeName("itemabcd-2", "item"),
                    Map.of(),
                    LifecyclePolicy.explicitLifetime(),
                    DEADLINE));
    assertTrue(refused.getMessage().contains("qits.harness.runner."), refused::getMessage);
  }
}
