package eu.wohlben.qits.runner.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.runner.protocol.Backlog;
import eu.wohlben.qits.runner.protocol.Hello;
import eu.wohlben.qits.runner.protocol.Reserve;
import eu.wohlben.qits.runner.protocol.Retire;
import eu.wohlben.qits.runner.protocol.RunnerCapabilities;
import eu.wohlben.qits.runner.protocol.RunnerCodec;
import eu.wohlben.qits.runner.protocol.RunnerWire;
import eu.wohlben.qits.runner.protocol.Upgrade;
import eu.wohlben.qits.runner.toolkit.Decommission;
import eu.wohlben.qits.runner.toolkit.HostFacts;
import eu.wohlben.qits.runner.toolkit.Housekeeping;
import eu.wohlben.qits.runner.toolkit.RunnerExit;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.runner.toolkit.RunnerLauncher;
import eu.wohlben.qits.runner.toolkit.RunnerRuntime;
import eu.wohlben.qits.runner.toolkit.RunnerSettings;
import eu.wohlben.qits.runner.toolkit.SelfUpdate;
import eu.wohlben.qits.runner.toolkit.Telemetry;
import eu.wohlben.qits.runner.toolkit.testing.FakeDocker;
import eu.wohlben.qits.runner.toolkit.testing.FakeRunnerHost;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The harness runner's whole life, end to end, against the toolkit's own fixtures: {@link
 * FakeRunnerHost} (register door, token endpoint, control socket, each scripted here) and {@link
 * FakeDocker} (a script the production process seam forks as docker).
 *
 * <p><b>Written from outside the toolkit's package on purpose.</b> Everything here is the toolkit's
 * public API and its {@code testing} package — what qits-624's suite has to work with — so a case
 * that could only be reached through a package-private seam would be a gap in that API, not in this
 * test. One numbered case of qits-827 per test (case 8 is two, one per way of being told).
 */
@EnabledOnOs(OS.LINUX)
class HarnessRunnerTest {

  private static final RunnerIdentity IDENTITY = HarnessIdentity.IDENTITY;

  private static final RunnerCodec<HarnessMessage> CODEC =
      new RunnerCodec<>(IDENTITY.vocabulary(), new HarnessCodec());

  /** The runner's own container, as the suite names it ({@link RunnerRuntime.NodeOptions#self}). */
  private static final String SELF = "0123456789ab";

  private static final String RUNNER_ID = "r1";

  private static final String ITEM_IMAGE = "registry.example:5000/qits/harness-item:1";

  private final Vertx vertx = Vertx.vertx();
  private FakeRunnerHost host;
  private FakeDocker docker;
  private RunnerRuntime<HarnessMessage> runner;
  private CompletableFuture<Integer> exit;

  @TempDir Path state;
  @TempDir Path dockerDir;

  /** {@link RunnerSettings.Timing}'s refusal streak, shortened per test. */
  private RunnerSettings.Timing timing = timing(300_000, 5);

  @BeforeEach
  void setUp() throws Exception {
    host = new FakeRunnerHost(vertx, IDENTITY, CODEC);
    docker = new FakeDocker(dockerDir);
  }

  @AfterEach
  void tearDown() throws Exception {
    stopRunner();
    host.close();
    vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  // ---- 1. registration -------------------------------------------------------------------------

  @Test
  void itRegistersWithTheTokenOnceWritesItsClientAt0600AndRegistersAgainOnlyForANewToken()
      throws Exception {
    ackWith(2, 0);
    start("token-1");
    host.await(Hello.class, 1);

    assertEquals(List.of("/harness/api/runners/r1/register"), host.registerPaths);
    assertEquals("Bearer token-1", host.registerAuthorizations.getFirst());
    Path client = state.resolve("client.json");
    assertEquals(
        PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(client));

    // A restart with the same token is the same container coming back: no registration.
    stopRunner();
    start("token-1");
    host.await(Hello.class, 2);
    assertEquals(1, host.registerPaths.size(), "a restart does not register again");

    // A fresh install line is a new token, and a new token registers.
    stopRunner();
    start("token-2");
    host.await(Hello.class, 3);
    assertEquals(2, host.registerPaths.size(), "a new token registers again");
    assertEquals("Bearer token-2", host.registerAuthorizations.getLast());
    assertEquals(
        PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(client));
  }

  // ---- 2. the bearer ---------------------------------------------------------------------------

  @Test
  void theBearerIsMintedWithClientSecretPostAndNeverWithBasic() throws Exception {
    ackWith(2, 0);
    start("t");
    host.await(Hello.class);

    String form = host.tokenBodies.getFirst();
    assertTrue(form.startsWith("grant_type=client_credentials"), form);
    assertTrue(form.contains("client_id=harness-runner-r1"), form);
    assertTrue(form.contains("client_secret=client-secret"), form);
    assertTrue(form.contains("audience=qits-platform"), form);
    assertTrue(
        host.tokenAuthorizations.stream().allMatch(a -> a == null),
        "no Authorization header, Basic or otherwise: " + host.tokenAuthorizations);
    assertEquals("Bearer runner-access-token", host.upgradeHeaders.get("Authorization"));
  }

  // ---- 3. Hello and Ack ------------------------------------------------------------------------

  @Test
  void theFirstHelloCarriesNoHeldItemsTheCapabilitiesAndTheVersionAndTheAckAdmitsIt()
      throws Exception {
    ackWith(2, 0);
    start("t");

    Hello hello = host.await(Hello.class);
    assertEquals(HarnessIdentity.VERSION, hello.runnerVersion());
    assertEquals(1, hello.capabilityVersion());
    assertEquals(2, hello.slots());
    assertEquals(new RunnerCapabilities(true, "amd64", "linux", Map.of()), hello.capabilities());
    assertEquals(List.of(), hello.held());
    Map<String, Object> wire = CODEC.encode(hello);
    assertFalse(wire.containsKey("heldItems"), "absent on a first connection, not empty: " + wire);

    // The Ack{slots 2} admits it: healthy at once, and a backlog is answered with a Reserve.
    awaitTrue(() -> Files.exists(state.resolve("heartbeat")));
    host.send(new Backlog(1));
    host.await(Reserve.class);
  }

  // ---- 4. Backlog, Reserve, take, release ------------------------------------------------------

  @Test
  void aBacklogOfThreeFillsBothSlotsAndAReleaseFreesOneForTheNextReserve() throws Exception {
    host.script =
        (h, message) -> {
          if (message instanceof Hello) {
            h.send(new Ack(1, 2));
            h.send(new Backlog(3));
          } else if (message instanceof Reserve) {
            h.send(new HarnessMessage.Take(item(h.all(Reserve.class).size()), ITEM_IMAGE));
          }
        };
    start("t");

    HarnessMessage.Started first = awaitStarted(item(1));
    assertEquals("qits-harness-runner-item-item0001", first.containerId());
    awaitStarted(item(2));
    assertTrue(
        host.received.indexOf(host.all(Reserve.class).getFirst()) < host.received.indexOf(first),
        "Reserve, take, then started: " + host.received);
    Thread.sleep(300);
    assertEquals(2, host.all(Reserve.class).size(), "two held items, no third Reserve");
    assertEquals(
        Map.of("qits.harness.runner", "r1", "qits.harness.runner.item", "item0001"),
        docker.containers().get("qits-harness-runner-item-item0001"),
        "the item carries the runner's own labels, through the driver's labels argument");
    assertTrue(
        docker.calls().stream()
            .anyMatch(
                c ->
                    c.getFirst().equals("--config")
                        && c.subList(2, c.size()).equals(List.of("pull", ITEM_IMAGE))),
        "the item's image is pulled under the runner's throwaway login: " + docker.calls());

    host.send(new HarnessMessage.Release(item(1)));
    host.await(HarnessMessage.Released.class);
    host.await(Reserve.class, 3);
    assertFalse(docker.containers().containsKey("qits-harness-runner-item-item0001"));
    assertTrue(docker.containers().containsKey("qits-harness-runner-item-item0002"));
    // The freed slot is refilled: the third item takes it.
    awaitStarted(item(3));
    assertEquals(List.of(item(2), item(3)), runner.session().held());
  }

  // ---- 5. a lost socket ------------------------------------------------------------------------

  @Test
  void aRedialledHelloClaimsTheHeldItemsAndTheOneTheAckDoesNotAdoptIsDropped() throws Exception {
    host.script =
        (h, message) -> {
          if (message instanceof Hello hello && hello.held().isEmpty()) {
            h.send(new Ack(1, 2));
            h.send(new Backlog(2));
          } else if (message instanceof Hello) {
            h.send(new Ack(1, 2, List.of(item(1)), Map.of()));
          } else if (message instanceof Reserve && h.all(Reserve.class).size() <= 2) {
            h.send(new HarnessMessage.Take(item(h.all(Reserve.class).size()), ITEM_IMAGE));
          }
        };
    start("t");
    awaitStarted(item(1));
    awaitStarted(item(2));

    host.socket.close();
    Hello again = host.await(Hello.class, 2);
    assertEquals(Set.of(item(1), item(2)), Set.copyOf(again.held()));
    assertEquals(
        Set.of(item(1), item(2)),
        Set.copyOf(WireLists.strings(CODEC.encode(again).get("heldItems"))),
        "under the harness's own wire name");

    // onDropped hears item 2: its container goes, item 1's stays and keeps its slot.
    // The state, not the call: the fake records a call before it has answered it.
    awaitTrue(() -> !docker.containers().containsKey("qits-harness-runner-item-item0002"));
    assertTrue(docker.calls().contains(List.of("rm", "-f", "qits-harness-runner-item-item0002")));
    awaitTrue(() -> runner.session().held().equals(List.of(item(1))));
    assertEquals(
        Set.of("qits-harness-runner-item-item0001"), docker.containers().keySet(), "adopted kept");
  }

  // ---- 6. self-update --------------------------------------------------------------------------

  @Test
  void anUpgradeWaitsForTheItemInFlightThenPullsChecksAndStartsTheSuccessorAndRetiresCleanly()
      throws Exception {
    runnerContainer();
    // The item's pull hangs, so its take is in flight when the Upgrade lands.
    docker.answerFor("pull", ITEM_IMAGE, 0, "", "");
    docker.hang("pull+" + ITEM_IMAGE.replaceAll("[^A-Za-z0-9._-]", "_"), 3);
    host.script =
        (h, message) -> {
          if (message instanceof Hello) {
            h.send(new Ack(1, 2));
            h.send(new Backlog(1));
          } else if (message instanceof Reserve && h.all(Reserve.class).size() == 1) {
            h.send(new HarnessMessage.Take(item(1), ITEM_IMAGE));
          }
        };
    start("t");
    awaitCall(c -> has(c, List.of("pull", ITEM_IMAGE)));

    host.send(new Upgrade(NEXT, IMAGE, DIGEST));
    awaitCall(c -> has(c, List.of("image", "inspect", "--format", "{{json .RepoDigests}}", IMAGE)));
    List<List<String>> before = docker.calls();
    assertTrue(host.all(HarnessMessage.Started.class).isEmpty(), "the take is still in flight");
    assertFalse(before.contains(SUCCESSOR_RUN), "no successor while an item is in flight");

    // It settles, and only then does the successor start.
    awaitStarted(item(1));
    awaitCall(c -> c.contains(SUCCESSOR_RUN));
    List<List<String>> calls = docker.calls();
    int itemRun =
        calls.indexOf(
            calls.stream()
                .filter(c -> c.contains("qits-harness-runner-item-item0001"))
                .filter(c -> c.getFirst().equals("run"))
                .findFirst()
                .orElseThrow());
    assertTrue(itemRun < calls.indexOf(SUCCESSOR_RUN), calls::toString);
    List<String> pull =
        calls.stream()
            .filter(c -> c.contains(IMAGE) && c.contains("pull"))
            .findFirst()
            .orElseThrow();
    assertEquals("--config", pull.getFirst(), "the successor is pulled under a throwaway login");
    assertFalse(Files.exists(Path.of(pull.get(1))), "and the login is gone after the pull");

    host.send(new Retire("superseded by " + NEXT));
    assertEquals(RunnerExit.OK, exit.get(10, TimeUnit.SECONDS));
    runner = null;
    assertTrue(
        has(docker.calls(), List.of("update", "--restart=no", SELF)), docker.calls()::toString);
  }

  // ---- 7. decommission -------------------------------------------------------------------------

  @Test
  void aDeletedRetireStopsTheItemsForgetsTheClientAndTheHelperRemovesEverythingInOrder()
      throws Exception {
    containerWithStateVolume();
    docker.volume("qits-harness-runner-data-r1", Map.of(IDENTITY.ownerLabel(), RUNNER_ID));
    docker.volume(IDENTITY.stateVolume(RUNNER_ID), Map.of());
    host.script =
        (h, message) -> {
          if (message instanceof Hello) {
            h.send(new Ack(1, 2));
            h.send(new Backlog(1));
          } else if (message instanceof Reserve && h.all(Reserve.class).size() == 1) {
            h.send(new HarnessMessage.Take(item(1), ITEM_IMAGE));
          }
        };
    start("t");
    awaitStarted(item(1));
    // The housekeeping pass keeps the scratch volume, the teardown item that removes it.
    awaitTrue(() -> docker.volumes().containsKey("qits-harness-runner-scratch-r1"));

    host.send(Retire.deleted("deleted by admin"));
    assertEquals(RunnerExit.OK, exit.get(10, TimeUnit.SECONDS));
    runner = null;
    List<List<String>> calls = docker.calls();
    int stopped = calls.indexOf(List.of("stop", "qits-harness-runner-item-item0001"));
    int stayDown = calls.indexOf(List.of("update", "--restart=no", SELF));
    int helper = calls.indexOf(HELPER_RUN);
    assertTrue(0 <= stopped && stopped < stayDown && stayDown < helper, calls::toString);
    assertFalse(Files.exists(state.resolve("client.json")), "the revoked client is forgotten");

    // The runner's container, exited now, as the helper finds it on the node.
    String process = IDENTITY.containerName(RUNNER_ID, HarnessIdentity.VERSION);
    docker.container(process, Map.of(IDENTITY.processLabel(), RUNNER_ID));

    // The helper, as its container runs it: helper mode, from the environment the runner gave it.
    Map<String, String> env = new HashMap<>();
    for (int i = 0; i < HELPER_RUN.size() - 1; i++) {
      if (HELPER_RUN.get(i).equals("-e")) {
        String[] kv = HELPER_RUN.get(i + 1).split("=", 2);
        env.put(kv[0], kv[1]);
      }
    }
    env.put(IDENTITY.env(RunnerSettings.DOCKER_BINARY), docker.binaryFor("helper"));
    docker.answer("inspect", 0, "exited\n", "");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int code =
        RunnerLauncher.main(
            IDENTITY,
            HarnessDomain::new,
            vertx,
            new String[0],
            env::get,
            new PrintStream(out, true, StandardCharsets.UTF_8));
    String said = out.toString(StandardCharsets.UTF_8);
    assertEquals(0, code, said);

    List<List<String>> removals =
        docker.callsBy("helper").stream()
            .filter(
                c -> c.get(0).equals("rm") || (c.get(0).equals("volume") && c.get(1).equals("rm")))
            .toList();
    List<List<String>> expected =
        List.of(
            List.of("rm", "-f", process),
            List.of("rm", "-f", "qits-harness-runner-item-item0001"),
            List.of("volume", "rm", "qits-harness-runner-data-r1"),
            List.of("volume", "rm", "qits-harness-runner-scratch-r1"),
            List.of("volume", "rm", "qits-harness-runner-state-r1"));
    // Anything after these is the helper removing its own container, which only a real one has.
    assertEquals(expected, removals.subList(0, Math.min(removals.size(), expected.size())), said);
    // Nothing of the runner is left but the helper's own container, which a real helper removes
    // last, as itself — this one runs in the suite's JVM, not in it.
    assertEquals(Set.of(IDENTITY.helperName(RUNNER_ID)), docker.containers().keySet(), said);
    assertEquals(Map.of(), docker.volumes(), said);
  }

  // ---- 8. the other way a runner learns it was deleted, and the way it is merely refused --------

  @Test
  void aRunnerDeletedCloseDecommissionsAtOnce() throws Exception {
    containerWithStateVolume();
    ackWith(2, 0);
    start("t");
    host.await(Hello.class);

    host.closeSocket(RunnerWire.CLOSE_POLICY_VIOLATION, RunnerWire.CloseReason.RUNNER_DELETED);
    assertEquals(RunnerExit.OK, exit.get(10, TimeUnit.SECONDS));
    runner = null;
    assertFalse(Files.exists(state.resolve("client.json")));
    assertTrue(
        has(docker.calls(), List.of("update", "--restart=no", SELF)), docker.calls()::toString);
    assertTrue(docker.calls().contains(HELPER_RUN), "the helper purges: " + docker.calls());
    assertEquals(1, host.upgrades.get(), "no redial");
  }

  @Test
  void anInvalidClientStreakStopsTheRunnerOnlyOnceItHasLastedItsTimeAndItsDials()
      throws Exception {
    containerWithStateVolume();
    host.tokenStatus = 401;
    host.tokenErrorBody = "{\"error\":\"invalid_client\"}";

    // Five dials come quickly; the streak has not lasted its two seconds yet.
    timing = new RunnerSettings.Timing(10_000, 50, 100, 5_000, 2_000, 5);
    long began = System.nanoTime();
    start("t");
    awaitTrue(() -> host.tokenBodies.size() >= 5);
    assertFalse(exit.isDone(), "five dials are not enough on their own");
    assertEquals(RunnerExit.OK, exit.get(15, TimeUnit.SECONDS));
    runner = null;
    assertTrue(System.nanoTime() - began >= TimeUnit.MILLISECONDS.toNanos(2_000));
    // A revoked credential is not a deletion (qits-1592): it stays down, and removes nothing.
    assertTrue(Files.exists(state.resolve("client.json")), "the client is kept");
    assertTrue(
        has(docker.calls(), List.of("update", "--restart=no", SELF)), docker.calls()::toString);
    assertFalse(docker.calls().contains(HELPER_RUN), docker.calls()::toString);
    assertTrue(
        docker.calls().stream()
            .noneMatch(
                c -> c.get(0).equals("rm") || c.get(0).equals("stop") || c.contains("volume")),
        docker.calls()::toString);

    // The time passes at once; the dials are slow, and three are not enough on their own.
    host.close();
    host = new FakeRunnerHost(vertx, IDENTITY, CODEC);
    host.tokenStatus = 401;
    host.tokenErrorBody = "{\"error\":\"invalid_client\"}";
    timing = new RunnerSettings.Timing(10_000, 400, 400, 5_000, 1, 5);
    start("t2");
    awaitTrue(() -> host.tokenBodies.size() >= 3);
    assertFalse(exit.isDone(), "the streak is old enough but three dials short");
    assertEquals(RunnerExit.OK, exit.get(15, TimeUnit.SECONDS));
    runner = null;
    assertTrue(host.tokenBodies.size() >= 5, "five dials: " + host.tokenBodies.size());
    assertEquals(0, host.upgrades.get());
  }

  // ---- 9. health -------------------------------------------------------------------------------

  @Test
  void healthExitsOneBeforeTheFirstAckAndZeroAfterIt() throws Exception {
    host.script = (h, message) -> {};
    start("t");
    host.await(Hello.class);
    assertEquals(1, health(), "a Hello the host never answered is not admission");

    host.send(new Ack(1, 2));
    awaitTrue(() -> health() == 0);
  }

  // ---- 10. the version probe and a missing environment ------------------------------------------

  @Test
  void printVersionPrintsTheVersionAndNoEnvironmentExitsTwo() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int code =
        RunnerLauncher.main(
            IDENTITY,
            HarnessDomain::new,
            vertx,
            new String[0],
            Map.of("QITS_HARNESS_RUNNER_PRINT_VERSION", "1")::get,
            new PrintStream(out, true, StandardCharsets.UTF_8));
    assertEquals(0, code);
    assertEquals(HarnessIdentity.VERSION + "\n", out.toString(StandardCharsets.UTF_8));
    assertFalse(HarnessIdentity.VERSION.startsWith("$"), "the resource was filtered");

    int bare =
        RunnerLauncher.main(
            IDENTITY,
            HarnessDomain::new,
            vertx,
            new String[0],
            name -> null,
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    assertEquals(RunnerExit.MISCONFIGURED, bare);
  }

  // ---- the runner, the host's scripts and the node ---------------------------------------------

  private static RunnerSettings.Timing timing(long refusalMillis, int refusalDials) {
    return new RunnerSettings.Timing(10_000, 50, 200, 5_000, refusalMillis, refusalDials);
  }

  private Map<String, String> env(String token) {
    Map<String, String> env = new HashMap<>();
    env.put(IDENTITY.env(RunnerSettings.URL), host.base());
    env.put(IDENTITY.env(RunnerSettings.ID), RUNNER_ID);
    env.put(IDENTITY.env(RunnerSettings.REGISTRATION_TOKEN), token);
    env.put(IDENTITY.env(RunnerSettings.STATE_DIR), state.toString());
    env.put(IDENTITY.env(RunnerSettings.SLOTS), "2");
    env.put(IDENTITY.env(RunnerSettings.DOCKER_BINARY), docker.binary());
    env.put(IDENTITY.env(RunnerSettings.DOCKER_TIMEOUT), "10");
    return env;
  }

  /**
   * The harness runner as {@code RunnerLauncher} would build it, through the toolkit's public
   * entry for a suite: its own container named, every node clock shrunk.
   */
  private void start(String token) throws Exception {
    RunnerSettings settings =
        RunnerSettings.parse(IDENTITY, env(token)::get).withTiming(timing);
    runner =
        new RunnerRuntime<>(
            vertx,
            IDENTITY,
            settings,
            new HarnessDomain(settings),
            new HostFacts(true, "amd64", "linux"),
            Telemetry.off(),
            new RunnerRuntime.NodeOptions(
                Optional.of(SELF),
                new SelfUpdate.Settings(100, 400, 3_000, 400, 50),
                new Decommission.Settings(200, 20, 2),
                Housekeeping.Settings.defaults(),
                Clock.systemUTC()));
    exit = CompletableFuture.supplyAsync(runner::run);
  }

  private void stopRunner() throws Exception {
    if (runner != null) {
      runner.stop(RunnerExit.OK);
      exit.get(10, TimeUnit.SECONDS);
      runner = null;
    }
  }

  /** Ack every Hello with these slots and this backlog. */
  private void ackWith(int slots, int backlog) {
    host.script =
        (h, message) -> {
          if (message instanceof Hello) {
            h.send(new Ack(IDENTITY.capabilityVersion(), slots));
            h.send(new Backlog(backlog));
          }
        };
  }

  private int health() {
    return RunnerLauncher.main(
        IDENTITY,
        HarnessDomain::new,
        vertx,
        new String[] {"health"},
        Map.of(IDENTITY.env(RunnerSettings.STATE_DIR), state.toString())::get,
        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
  }

  private static String item(int n) {
    return String.format("item%04d", n);
  }

  private HarnessMessage.Started awaitStarted(String itemId) throws InterruptedException {
    awaitTrue(
        () ->
            host.all(HarnessMessage.Started.class).stream()
                .anyMatch(s -> s.itemId().equals(itemId)));
    return host.all(HarnessMessage.Started.class).stream()
        .filter(s -> s.itemId().equals(itemId))
        .findFirst()
        .orElseThrow();
  }

  private void awaitCall(Predicate<List<List<String>>> condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (!condition.test(docker.calls()) && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertTrue(condition.test(docker.calls()), () -> "docker calls: " + docker.calls());
  }

  private static boolean has(List<List<String>> calls, List<String> call) {
    return calls.stream().map(FakeDocker::withoutConfig).anyMatch(call::equals);
  }

  private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertTrue(condition.getAsBoolean(), "the condition never held");
  }

  /** A wire list as the codec writes it. */
  private static final class WireLists {
    static List<String> strings(Object value) {
      assertNotNull(value, "the list is on the wire");
      return ((List<?>) value).stream().map(String::valueOf).toList();
    }
  }

  // ---- the node as docker answers it -----------------------------------------------------------

  private static final String NEXT = "2026.999.1";
  private static final String IMAGE = "registry.example:5000/qits/qits-harness-runner:" + NEXT;
  private static final String DIGEST =
      "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  private static final List<String> SUCCESSOR_RUN =
      List.of(
          "run",
          "-d",
          "--name",
          "qits-harness-runner-r1-" + NEXT,
          "--restart",
          "unless-stopped",
          "--label",
          "qits.harness.runner.process=r1",
          "--label",
          "qits.harness.runner.version=" + NEXT,
          "-v",
          "/var/run/docker.sock:/var/run/docker.sock",
          "-v",
          "qits-harness-runner-state-r1:/var/lib/qits-harness-runner",
          "-e",
          "QITS_HARNESS_RUNNER_ID=r1",
          "-e",
          "QITS_HARNESS_RUNNER_SLOTS=2",
          "-e",
          "QITS_HARNESS_RUNNER_URL=https://harness.qits.example.eu",
          IMAGE);

  private static final List<String> HELPER_RUN =
      List.of(
          "run",
          "-d",
          "--name",
          "qits-harness-runner-r1-decommission",
          "--network",
          "none",
          "--label",
          "qits.harness.runner.decommission=r1",
          "-v",
          "/var/run/docker.sock:/var/run/docker.sock",
          "-e",
          "QITS_HARNESS_RUNNER_DECOMMISSION=r1",
          "-e",
          "QITS_HARNESS_RUNNER_DECOMMISSION_VOLUME=qits-harness-runner-state-r1",
          "-e",
          "QITS_HARNESS_RUNNER_DECOMMISSION_TEARDOWN=volume:qits-harness-runner-scratch-r1",
          "sha256:old");

  /**
   * The runner's own container as {@code docker inspect} answers it for a successor (what the
   * install script started), its old image's config, and the pulled image's digest.
   */
  private void runnerContainer() throws Exception {
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
                                .put(
                                    "Env",
                                    List.of(
                                        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                                        "QITS_HARNESS_RUNNER_URL=https://harness.qits.example.eu",
                                        "QITS_HARNESS_RUNNER_ID=r1",
                                        "QITS_HARNESS_RUNNER_SLOTS=2",
                                        "QITS_HARNESS_RUNNER_REGISTRATION_TOKEN=qits_tok_SPENT"))
                                .put(
                                    "Labels",
                                    Map.of(
                                        "qits.harness.runner.process", "r1",
                                        "qits.harness.runner.version", HarnessIdentity.VERSION)))
                        .put(
                            "HostConfig",
                            new JsonObject()
                                .put(
                                    "Binds",
                                    List.of(
                                        "/var/run/docker.sock:/var/run/docker.sock",
                                        "qits-harness-runner-state-r1:/var/lib/qits-harness-runner"))
                                .put(
                                    "RestartPolicy",
                                    Map.of("Name", "unless-stopped", "MaximumRetryCount", 0))
                                .put("NetworkMode", "default")))
                .encode()
            + "\n",
        "");
    docker.answerFor(
        "image-inspect",
        "sha256:old",
        0,
        new JsonArray()
                .add(
                    new JsonObject()
                        .put("Id", "sha256:old")
                        .put(
                            "Config",
                            new JsonObject()
                                .put(
                                    "Env",
                                    List.of(
                                        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"))))
                .encode()
            + "\n",
        "");
    docker.answerFor(
        "image-inspect",
        IMAGE,
        0,
        "[\"registry.example:5000/qits/qits-harness-runner@" + DIGEST + "\"]\n",
        "");
  }

  /** The runner's own container with its state volume mounted at this test's state directory. */
  private void containerWithStateVolume() throws Exception {
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
                                        .put("Name", "qits-harness-runner-state-r1")
                                        .put("Destination", state.toString())))
                        .put(
                            "HostConfig",
                            new JsonObject()
                                .put("RestartPolicy", Map.of("Name", "unless-stopped"))))
                .encode()
            + "\n",
        "");
  }
}
