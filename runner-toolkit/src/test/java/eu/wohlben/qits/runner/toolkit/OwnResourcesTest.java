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
            List.of("ps", "-aq", "--filter", "label=qits.toy.runner=r1"),
            List.of(
                "ps",
                "-aq",
                "--filter",
                "label=qits.toy.runner=r1",
                "--filter",
                "label=qits.toy.runner.item=keep")),
        fake.calls("ps"));
    assertEquals(
        List.of(
            List.of("rm", "-f", "qits-toy-runner-item-stale"),
            List.of("rm", "-f", "qits-toy-runner-unworked")),
        fake.calls("rm"));
  }

  @Test
  void aContainerTheCandidateListingDidNotAnswerIsNeverRemoved() throws Exception {
    // qits-1233: a launch for a kept item lands during the pass. The candidates are listed before
    // the kept, so its container is in neither listing, or in the kept one — never a candidate the
    // kept listing missed.
    fake.container(
        "qits-toy-runner-item-stale",
        Map.of("qits.toy.runner", "r1", "qits.toy.runner.item", "stale"));
    fake.container(
        "qits-toy-runner-item-launched",
        Map.of("qits.toy.runner", "r1", "qits.toy.runner.item", "launched"));
    fake.answerFor("ps", "label=qits.toy.runner=r1", 0, "qits-toy-runner-item-stale\n", "");

    assertEquals(1, own(Toy.IDENTITY, "r1").reconcile(Set.of("launched")));

    assertEquals(Set.of("qits-toy-runner-item-launched"), fake.containers().keySet());
    assertEquals(List.of(List.of("rm", "-f", "qits-toy-runner-item-stale")), fake.calls("rm"));
    assertEquals(
        List.of("ps", "-aq", "--filter", "label=qits.toy.runner=r1"), fake.calls("ps").getFirst());
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
    fake.answerFor(
        "ps", "label=qits.toy.runner.item=keep", 1, "", "Cannot connect to the Docker daemon");

    // A kept item that cannot be listed: a wrong guess would kill live work, so nothing goes.
    assertEquals(0, own(Toy.IDENTITY, "r1").reconcile(Set.of("keep")));
    assertTrue(fake.calls("rm").isEmpty(), fake.calls()::toString);

    // The own listing itself failing: not read as "nothing there", and nothing goes either.
    fake.answer("ps", 1, "", "Cannot connect to the Docker daemon");
    assertEquals(0, own(Toy.IDENTITY, "r1").reconcile(Set.of()));
    assertTrue(fake.calls("rm").isEmpty(), fake.calls()::toString);
    OwnResources.ListingFailed failed =
        assertThrows(OwnResources.ListingFailed.class, () -> own(Toy.IDENTITY, "r1").containers());
    assertTrue(failed.getMessage().contains("Cannot connect"), failed::getMessage);
    assertEquals(Set.of("qits-toy-runner-item-stale"), fake.containers().keySet());
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
