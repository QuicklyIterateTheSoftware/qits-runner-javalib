package eu.wohlben.qits.runner.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.driver.ContainersDriver;
import eu.wohlben.qits.containers.driver.spec.ContainerSpec;
import eu.wohlben.qits.containers.driver.spec.LifecyclePolicy;
import eu.wohlben.qits.containers.driver.spec.VolumeSpec;
import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.runner.protocol.Backlog;
import eu.wohlben.qits.runner.protocol.DomainCodec;
import eu.wohlben.qits.runner.protocol.Hello;
import eu.wohlben.qits.runner.protocol.Reserve;
import eu.wohlben.qits.runner.protocol.Retire;
import eu.wohlben.qits.runner.protocol.RunnerCapabilities;
import eu.wohlben.qits.runner.protocol.RunnerCodec;
import eu.wohlben.qits.runner.protocol.Upgrade;
import eu.wohlben.qits.runner.toolkit.testing.FakeDocker;
import eu.wohlben.qits.runner.toolkit.testing.FakeRunnerHost;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>Two runners on one node never touch each other</b> (qits-825). A workspaces-shaped and a
 * CI-shaped {@link RunnerRuntime}, each complete — its own {@link FakeRunnerHost}, its own state
 * directory, its own docker binary — share ONE {@link FakeDocker}: one node, one set of containers
 * and volumes, every argv recorded and attributed to the runner that issued it ({@link
 * FakeDocker#binaryFor}). Both are driven through session start, a workload launch through {@link
 * OwnResources#labels}, housekeeping, an upgrade and a decommission.
 *
 * <p>The two runner ids share their first eight characters on purpose: every derived name carries
 * {@code <id8>}, so only the roots keep them apart — which is the claim under test. The node also
 * holds what neither runner owns: an earlier CI runner's workload and volume (same label root,
 * another id), CI's builder, and qits-containers-service's own container and volume.
 */
@EnabledOnOs(OS.LINUX)
class TwoRunnersOneNodeTest {

  private static final String WS_ID = "1a2b3c4d-0000-4000-8000-00000000a001";
  private static final String CI_ID = "1a2b3c4d-0000-4000-8000-00000000c001";
  private static final String EARLIER_CI_ID = "9f8e7d6c-0000-4000-8000-00000000c000";
  private static final String NEXT = "2026.1004.1";
  private static final String REGISTRY = "registry.example:5000";
  private static final String DIGEST =
      "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final String CI_BUILDER = "qits-ci-runner-buildkitd";
  private static final TeardownItem CI_BUILDER_ITEM =
      new TeardownItem(TeardownItem.Type.CONTAINER, CI_BUILDER);
  private static final Duration DEADLINE = Duration.ofSeconds(10);

  private final Vertx vertx = Vertx.vertx();
  private FakeDocker node;
  private Runner ws;
  private Runner ci;

  @TempDir Path nodeDir;
  @TempDir Path wsState;
  @TempDir Path ciState;

  /** One tenant of the node: its identity, its host, its domain and its running runtime. */
  private final class Runner {
    final String caller;
    final RunnerIdentity identity;
    final String id;
    final Path state;
    final String self;
    final String imageId;
    final String itemId;
    final Tenant domain;
    final FakeRunnerHost host;
    RunnerRuntime<Toy.Message> runtime;
    CompletableFuture<Integer> exit;
    final List<String> said = Collections.synchronizedList(new ArrayList<>());

    Runner(
        String caller,
        RunnerIdentity identity,
        String id,
        Path state,
        String self,
        String itemId,
        List<TeardownItem> teardown)
        throws Exception {
      this.caller = caller;
      this.identity = identity;
      this.id = id;
      this.state = state;
      this.self = self;
      this.imageId = "sha256:" + caller + "0".repeat(64 - caller.length());
      this.itemId = itemId;
      this.domain = new Tenant(identity, teardown);
      this.host =
          new FakeRunnerHost(vertx, identity, new RunnerCodec<>(identity.vocabulary(), Toy.CODEC));
      // Ack every Hello; hand over one work item on the first Reserve, and nothing after.
      host.script =
          (h, message) -> {
            if (message instanceof Hello) {
              h.send(new Ack(identity.capabilityVersion(), 2));
              h.send(new Backlog(1));
            } else if (message instanceof Reserve && h.all(Reserve.class).size() == 1) {
              h.send(new Toy.Take(itemId));
            }
          };
    }

    String image(String version) {
      return REGISTRY + "/" + identity.imageRepository() + ":" + version;
    }

    void start() throws Exception {
      Map<String, String> env = new HashMap<>();
      env.put(identity.env(RunnerSettings.URL), host.base());
      env.put(identity.env(RunnerSettings.ID), id);
      env.put(identity.env(RunnerSettings.REGISTRATION_TOKEN), "one-time-token");
      env.put(identity.env(RunnerSettings.STATE_DIR), state.toString());
      env.put(identity.env(RunnerSettings.SLOTS), "2");
      env.put(identity.env(RunnerSettings.DOCKER_BINARY), node.binaryFor(caller));
      env.put(identity.env(RunnerSettings.DOCKER_TIMEOUT), "10");
      RunnerSettings settings =
          RunnerSettings.parse(identity, env::get)
              .withTiming(new RunnerSettings.Timing(10_000, 50, 200, 5_000, 300_000, 3));
      runtime =
          new RunnerRuntime<>(
              vertx,
              identity,
              settings,
              domain,
              new HostFacts(true, "amd64", "linux"),
              Telemetry.off(),
              new NodeLifecycle.Options(
                  Optional.of(self),
                  // The successor's watch outlasts the suite: the old runner is still there,
                  // draining, when it is deleted — the decommission has two runner containers.
                  new SelfUpdate.Settings(100, 400, 60_000, 400, 50),
                  new Decommission.Settings(200, 20, 2),
                  Housekeeping.Settings.defaults(),
                  Clock.systemUTC()));
      exit = CompletableFuture.supplyAsync(runtime::run);
    }

    /** Every argv this runner issued, a leading {@code --config <dir>} skipped. */
    List<List<String>> argv() {
      return node.callsBy(caller).stream().map(FakeDocker::withoutConfig).toList();
    }

    /**
     * The decommission helper, as its container would run it: from the environment the runner gave
     * the helper's {@code docker run}, with this runner's own docker binary.
     */
    int runHelper(List<TeardownItem> offeredStraightToTheHelper) throws Exception {
      String helper = identity.helperName(id);
      List<String> run =
          argv().stream()
              .filter(c -> c.getFirst().equals("run") && c.contains(helper))
              .findFirst()
              .orElseThrow(() -> new AssertionError("no helper started: " + argv()));
      Map<String, String> env = new HashMap<>();
      for (int i = 0; i < run.size() - 1; i++) {
        if (run.get(i).equals("-e")) {
          String[] kv = run.get(i + 1).split("=", 2);
          env.put(kv[0], kv[1]);
        }
      }
      assertEquals(id, env.get(identity.env(LifecycleArgv.DECOMMISSION)));
      List<TeardownItem> teardown =
          new ArrayList<>(
              LifecycleArgv.decodeTeardown(
                  env.get(identity.env(LifecycleArgv.DECOMMISSION_TEARDOWN))));
      teardown.addAll(offeredStraightToTheHelper);
      return DecommissionHelper.finish(
          identity,
          new RunnerDocker(identity, node.binaryFor(caller), DEADLINE),
          id,
          env.get(identity.env(LifecycleArgv.DECOMMISSION_VOLUME)),
          teardown,
          Optional.of(helper),
          new Decommission.Settings(500, 20, 3),
          said::add);
    }
  }

  /**
   * A domain that does what a real one does on a shared node: reconciles its own containers at
   * session start, launches each work item as a container with a volume through the session's
   * driver and {@code own().labels(workId)}, lists its own volumes in a housekeeping pass, and
   * offers teardown items.
   */
  private static final class Tenant implements RunnerDomain<Toy.Message> {

    final RunnerIdentity identity;
    final List<TeardownItem> teardown;
    final List<String> launched = Collections.synchronizedList(new ArrayList<>());
    final List<String> launchFailures = Collections.synchronizedList(new ArrayList<>());
    final List<Integer> reconciled = Collections.synchronizedList(new ArrayList<>());
    final CountDownLatch housekept = new CountDownLatch(1);
    volatile List<String> housekeptVolumes = List.of();

    Tenant(RunnerIdentity identity, List<TeardownItem> teardown) {
      this.identity = identity;
      this.teardown = teardown;
    }

    @Override
    public DomainCodec<Toy.Message> codec() {
      return Toy.CODEC;
    }

    @Override
    public RunnerCapabilities capabilities(HostFacts host) {
      return new RunnerCapabilities(true, "amd64", "linux", Map.of());
    }

    @Override
    public void onSessionStart(RunnerSession session, List<String> carried) {
      reconciled.add(session.own().reconcile(Set.copyOf(carried)));
    }

    @Override
    public void onAck(Ack ack, RunnerSession session) {}

    @Override
    public void onMessage(Toy.Message message, RunnerSession session) {
      if (!(message instanceof Toy.Take take)) {
        return;
      }
      String workId = take.itemId();
      Map<String, String> labels = session.own().labels(workId);
      ContainersDriver driver = session.driver();
      String volume = identity.nodeVolume(workId, "data");
      ContainersDriver.OpResult made =
          driver.ensureVolume(new VolumeSpec(volume), labels, DEADLINE);
      ContainersDriver.Started started =
          driver.run(
              ContainerSpec.builder(REGISTRY + "/qits/workload:1").mount(volume, "/data").build(),
              identity.nodeName(workId, "item"),
              labels,
              LifecyclePolicy.explicitLifetime(),
              DEADLINE);
      if (made.ok() && started.started()) {
        session.taken(workId);
        launched.add(workId);
      } else {
        launchFailures.add(made.detail() + " / " + started.detail());
      }
    }

    @Override
    public boolean quiescent() {
      return true;
    }

    @Override
    public void onDecommission(RunnerSession session) {}

    @Override
    public List<TeardownItem> teardown() {
      return teardown;
    }

    @Override
    public List<HousekeepingPass> housekeeping() {
      return List.of(
          new HousekeepingPass() {
            @Override
            public String name() {
              return "tenant";
            }

            @Override
            public void run(HousekeepingContext context) {
              housekeptVolumes = context.own().volumes();
              housekept.countDown();
            }
          });
    }
  }

  @BeforeEach
  void setUp() throws Exception {
    node = new FakeDocker(nodeDir);
    ws =
        new Runner(
            "ws",
            Shapes.WORKSPACES,
            WS_ID,
            wsState,
            "a1a1a1a1a1a1",
            "e1e1e1e1-workspace",
            // Offered CI's builder: refused before it reaches any argv of this runner.
            List.of(CI_BUILDER_ITEM));
    ci =
        new Runner(
            "ci",
            Shapes.CI,
            CI_ID,
            ciState,
            "c1c1c1c1c1c1",
            "c2c2c2c2-run",
            List.of(CI_BUILDER_ITEM));
    for (Runner r : List.of(ws, ci)) {
      seed(r);
    }
    // What neither runner owns. An earlier CI runner's workload and volume: CI's very label root,
    // another runner id — only the `=<runnerId>` in every filter keeps them out of reach.
    node.container(
        "qits-ci-runner-item-9f8e7d6c",
        Map.of("qits.ci.runner", EARLIER_CI_ID, "qits.ci.runner.run", "old-run"));
    node.volume("qits-ci-runner-data-9f8e7d6c", Map.of("qits.ci.runner", EARLIER_CI_ID));
    node.container(CI_BUILDER, Map.of());
    node.volume("qits-buildkitd-state", Map.of());
    node.container("cs-workload", Map.of("qits.containers.managed", "container"));
    node.volume("cs-volume", Map.of("qits.containers.managed", "volume"));
    // Housekeeping's view of the node's images: one leftover of each runner, and a stranger's.
    node.answer(
        "image-ls",
        0,
        String.join(
            "\n",
            ws.image("2026.900.1") + "|sha256:" + "e".repeat(64),
            ci.image("2026.900.1") + "|sha256:" + "f".repeat(64),
            "busybox:1|sha256:" + "b".repeat(64),
            ""),
        "");
    node.answerFor(
        "image-inspect", ws.image("2026.900.1"), 0, "sha256:" + "e".repeat(64) + "\n", "");
    node.answerFor(
        "image-inspect", ci.image("2026.900.1"), 0, "sha256:" + "f".repeat(64) + "\n", "");
  }

  /**
   * One runner's footprint before it starts: its own container and state volume as the install
   * script leaves them, a leftover workload of an earlier session, and docker's answers about its
   * own container and image.
   */
  private void seed(Runner r) throws Exception {
    RunnerIdentity id = r.identity;
    node.container(
        id.containerName(r.id, Shapes.VERSION),
        Map.of(id.processLabel(), r.id, id.versionLabel(), Shapes.VERSION));
    node.volume(id.stateVolume(r.id), Map.of());
    node.container(
        id.nodeName("deadbeef-left", "item"),
        Map.of(id.ownerLabel(), r.id, id.workLabel(), "deadbeef-left"));
    node.answerFor(
        "inspect",
        r.self,
        0,
        new JsonArray()
                .add(
                    new JsonObject()
                        .put("Id", r.self + "0".repeat(52))
                        .put("Image", r.imageId)
                        .put(
                            "Config",
                            new JsonObject()
                                .put(
                                    "Env",
                                    List.of(
                                        id.env("URL") + "=https://" + r.caller + ".example.eu",
                                        id.env("ID") + "=" + r.id,
                                        id.env("REGISTRATION_TOKEN") + "=qits_tok_SPENT"))
                                .put(
                                    "Labels",
                                    Map.of(
                                        id.processLabel(), r.id,
                                        id.versionLabel(), Shapes.VERSION)))
                        .put(
                            "Mounts",
                            new JsonArray()
                                .add(
                                    new JsonObject()
                                        .put("Type", "volume")
                                        .put("Name", id.stateVolume(r.id))
                                        .put("Destination", r.state.toString())))
                        .put(
                            "HostConfig",
                            new JsonObject()
                                .put(
                                    "Binds",
                                    List.of(
                                        LifecycleArgv.DOCKER_SOCKET
                                            + ":"
                                            + LifecycleArgv.DOCKER_SOCKET,
                                        id.stateVolume(r.id) + ":" + r.state))
                                .put("RestartPolicy", Map.of("Name", "unless-stopped"))
                                .put("NetworkMode", "default")))
                .encode()
            + "\n",
        "");
    node.answerFor(
        "image-inspect",
        r.imageId,
        0,
        new JsonArray()
                .add(new JsonObject().put("Id", r.imageId).put("Config", new JsonObject()))
                .encode()
            + "\n",
        "");
    node.answerFor(
        "image-inspect",
        r.image(NEXT),
        0,
        "[\"" + REGISTRY + "/" + id.imageRepository() + "@" + DIGEST + "\"]\n",
        "");
  }

  @AfterEach
  void tearDown() throws Exception {
    for (Runner r : List.of(ws, ci)) {
      if (r.runtime != null && !r.exit.isDone()) {
        r.runtime.stop(RunnerExit.OK);
        r.exit.get(10, TimeUnit.SECONDS);
      }
      r.host.close();
    }
    vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  @Test
  void twoRunnersOnOneNodeNeverTouchEachOther() throws Exception {
    // --- session start, a workload, housekeeping ------------------------------------------------
    ws.start();
    ci.start();
    for (Runner r : List.of(ws, ci)) {
      awaitTrue(
          () -> r.domain.launched.contains(r.itemId),
          () -> r.caller + ": " + r.domain.launchFailures);
      assertTrue(r.domain.housekept.await(15, TimeUnit.SECONDS), r.caller + " never housekept");
      assertEquals(List.of(1), r.domain.reconciled, r.caller + " swept its own leftover, only");
      assertEquals(
          List.of(r.identity.nodeVolume(r.itemId, "data")),
          r.domain.housekeptVolumes,
          r.caller + "'s own volumes, as a pass sees them");
    }
    for (Runner r : List.of(ws, ci)) {
      Map<String, String> labels =
          Map.of(r.identity.ownerLabel(), r.id, r.identity.workLabel(), r.itemId);
      assertEquals(labels, node.containers().get(r.identity.nodeName(r.itemId, "item")));
      assertEquals(labels, node.volumes().get(r.identity.nodeVolume(r.itemId, "data")));
      assertFalse(
          node.containers().containsKey(r.identity.nodeName("deadbeef-left", "item")),
          r.caller + "'s leftover is gone");
    }
    awaitTrue(
        () ->
            ws.argv().contains(List.of("image", "rm", ws.image("2026.900.1")))
                && ci.argv().contains(List.of("image", "rm", ci.image("2026.900.1"))),
        () -> "each removes its own leftover image");

    // --- an upgrade -----------------------------------------------------------------------------
    for (Runner r : List.of(ws, ci)) {
      r.host.send(new Upgrade(NEXT, r.image(NEXT), DIGEST));
    }
    for (Runner r : List.of(ws, ci)) {
      String successor = r.identity.containerName(r.id, NEXT);
      awaitTrue(
          () -> node.containers().containsKey(successor),
          () -> r.caller + " started no successor: " + r.argv());
      assertEquals(
          Map.of(r.identity.processLabel(), r.id, r.identity.versionLabel(), NEXT),
          node.containers().get(successor));
    }

    // --- the workspaces runner is deleted -------------------------------------------------------
    Map<String, Map<String, String>> ciContainers = owned(node.containers(), ci);
    Map<String, Map<String, String>> ciVolumes = owned(node.volumes(), ci);
    assertTrue(ciContainers.size() >= 3 && ciVolumes.size() == 2, ciContainers + " " + ciVolumes);

    ws.host.send(Retire.deleted("deleted by admin"));
    assertEquals(RunnerExit.OK, ws.exit.get(10, TimeUnit.SECONDS));
    // CI's builder, offered to the workspaces runner twice — by its domain, which the runner
    // filters before the helper's environment, and straight to the helper — is refused both times.
    assertEquals(0, ws.runHelper(List.of(CI_BUILDER_ITEM)), () -> String.join("\n", ws.said));
    assertTrue(
        ws.said.stream()
            .anyMatch(l -> l.startsWith("refused to remove the container " + CI_BUILDER)),
        () -> String.join("\n", ws.said));
    assertThrows(
        IllegalArgumentException.class,
        () -> LifecycleArgv.teardown(ws.identity, "docker", CI_BUILDER_ITEM));

    assertEquals(Map.of(), owned(node.containers(), ws), "every container of ws is gone");
    assertEquals(Map.of(), owned(node.volumes(), ws), "every volume of ws is gone, state too");
    assertEquals(ciContainers, owned(node.containers(), ci), "ci's containers are untouched");
    assertEquals(ciVolumes, owned(node.volumes(), ci), "ci's volumes, state volume included");
    assertTrue(node.containers().containsKey(CI_BUILDER), "CI's builder is CI's");

    // --- the CI runner is deleted ---------------------------------------------------------------
    ci.host.send(Retire.deleted("deleted by admin"));
    assertEquals(RunnerExit.OK, ci.exit.get(10, TimeUnit.SECONDS));
    assertEquals(0, ci.runHelper(List.of()), () -> String.join("\n", ci.said));
    assertEquals(Map.of(), owned(node.containers(), ci));
    assertEquals(Map.of(), owned(node.volumes(), ci));

    // What neither owned is all that is left — the earlier CI runner's things included.
    assertEquals(Set.of("qits-ci-runner-item-9f8e7d6c", "cs-workload"), node.containers().keySet());
    assertEquals(
        Set.of("qits-ci-runner-data-9f8e7d6c", "qits-buildkitd-state", "cs-volume"),
        node.volumes().keySet());

    // --- what each runner said to docker --------------------------------------------------------
    for (Runner r : List.of(ws, ci)) {
      assertOnlyItsOwn(r);
    }
    assertTrue(
        ws.argv().stream().flatMap(List::stream).noneMatch(a -> a.contains(CI_BUILDER)),
        () -> "CI's builder is in no argv of the workspaces runner: " + ws.argv());
    assertTrue(
        ci.argv().contains(List.of("rm", "-f", CI_BUILDER)), "and CI's own teardown removed it");
    for (List<String> call : node.calls()) {
      String line = String.join(" ", call);
      assertTrue(call.stream().noneMatch(a -> a.contains("name=")), line);
      for (String forbidden :
          List.of("prune -a", "system prune", "volume prune", "container prune", "network prune")) {
        assertFalse(line.contains(forbidden), line);
      }
    }
  }

  /**
   * Every listing and every removal {@code r} issued names only its own: a {@code ps} or {@code
   * volume ls} filter is {@code label=<labelRoot>=<its id>} or {@code label=<labelRoot>.<x>=…} (the
   * process label valued with its id), or an image {@code ancestor=} read; a container, volume or
   * network removed by name starts with {@code <namePrefix>-}; an image removed is of its own
   * repository.
   */
  private static void assertOnlyItsOwn(Runner r) {
    RunnerIdentity id = r.identity;
    String root = id.labelRoot();
    List<String> wrong = new ArrayList<>();
    int listings = 0;
    int removals = 0;
    for (List<String> call : r.argv()) {
      String verb = call.getFirst() + (call.size() > 1 ? " " + call.get(1) : "");
      if (call.getFirst().equals("ps") || verb.equals("volume ls")) {
        listings++;
        List<String> filters = new ArrayList<>();
        for (int i = 0; i < call.size() - 1; i++) {
          if (call.get(i).equals("--filter")) {
            filters.add(call.get(i + 1));
          }
        }
        if (filters.isEmpty()) {
          wrong.add("unfiltered: " + call);
        }
        for (String filter : filters) {
          if (filter.startsWith("ancestor=") && call.getFirst().equals("ps")) {
            continue;
          }
          if (!filter.startsWith("label=")) {
            wrong.add("not a label filter: " + call);
            continue;
          }
          String[] kv = filter.substring("label=".length()).split("=", 2);
          boolean ownKey = kv[0].equals(root) || kv[0].startsWith(root + ".");
          boolean idKey = kv[0].equals(root) || kv[0].equals(id.processLabel());
          if (!ownKey || kv.length < 2 || (idKey && !kv[1].equals(r.id))) {
            wrong.add("not its own label: " + call);
          }
        }
      } else if (call.getFirst().equals("rm")
          || verb.equals("volume rm")
          || verb.equals("network rm")) {
        removals++;
        for (String target : call.subList(call.getFirst().equals("rm") ? 1 : 2, call.size())) {
          if (!target.startsWith("-") && !id.ownsName(target)) {
            wrong.add("not its own name: " + call);
          }
        }
      } else if (verb.equals("image rm")) {
        if (!call.getLast().contains("/" + id.imageRepository() + ":")) {
          wrong.add("not its own image: " + call);
        }
      }
    }
    assertTrue(listings > 0 && removals > 0, r.caller + " was not exercised: " + r.argv());
    assertTrue(wrong.isEmpty(), () -> r.caller + ":\n" + String.join("\n", wrong));
  }

  /**
   * The entries of {@code state} that are {@code r}'s: labelled with its id — its runner
   * containers, its helper, its workloads and their volumes — or its state volume, by name.
   */
  private static Map<String, Map<String, String>> owned(
      Map<String, Map<String, String>> state, Runner r) {
    Map<String, Map<String, String>> owned = new HashMap<>();
    state.forEach(
        (name, labels) -> {
          if (labels.containsValue(r.id) || name.equals(r.identity.stateVolume(r.id))) {
            owned.put(name, labels);
          }
        });
    return owned;
  }

  private static void awaitTrue(BooleanSupplier condition, java.util.function.Supplier<String> what)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertTrue(condition.getAsBoolean(), what);
  }
}
