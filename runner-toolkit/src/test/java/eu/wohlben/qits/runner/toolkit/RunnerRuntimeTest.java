package eu.wohlben.qits.runner.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.runner.protocol.Backlog;
import eu.wohlben.qits.runner.protocol.Heartbeat;
import eu.wohlben.qits.runner.protocol.Hello;
import eu.wohlben.qits.runner.protocol.Nothing;
import eu.wohlben.qits.runner.protocol.Quarantined;
import eu.wohlben.qits.runner.protocol.Reinstated;
import eu.wohlben.qits.runner.protocol.Reserve;
import eu.wohlben.qits.runner.protocol.Retire;
import eu.wohlben.qits.runner.protocol.RunnerCapabilities;
import eu.wohlben.qits.runner.protocol.RunnerWire;
import eu.wohlben.qits.runner.protocol.Upgrade;
import eu.wohlben.qits.runner.toolkit.testing.FakeDocker;
import eu.wohlben.qits.runner.toolkit.testing.FakeRunnerHost;
import io.vertx.core.Vertx;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The whole runtime against a real in-JVM host (register door, token endpoint, control socket) and
 * a shell script answering like docker, with the two-frame {@link Toy} domain. Real socket, real
 * frames, real codec, real processes.
 */
@EnabledOnOs(OS.LINUX)
class RunnerRuntimeTest {

  private final Vertx vertx = Vertx.vertx();
  private FakeRunnerHost host;
  private FakeDocker docker;
  private Toy.Domain domain;
  private RunnerRuntime<Toy.Message> runner;
  private CompletableFuture<Integer> exit;
  private Telemetry telemetry = Telemetry.off();

  @TempDir Path state;
  @TempDir Path dockerDir;

  /** {@code <envPrefix>SELF_UPDATE} as the environment would carry it; null is unset. */
  private String selfUpdate;

  /** How long an invalid_client streak must last to be believed; production's is five minutes. */
  private long refusalConfirmMillis = 300_000;

  @BeforeEach
  void setUp() throws Exception {
    host = new FakeRunnerHost(vertx, Toy.IDENTITY, Toy.codec());
    docker = new FakeDocker(dockerDir);
    domain = new Toy.Domain();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (runner != null) {
      runner.stop(RunnerExit.OK);
      exit.get(10, TimeUnit.SECONDS);
    }
    host.close();
    vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  private RunnerSettings settings(String token, long heartbeatMillis) throws Exception {
    Map<String, String> env = new HashMap<>();
    env.put("QITS_TOY_RUNNER_URL", host.base());
    env.put("QITS_TOY_RUNNER_ID", "r1");
    env.put("QITS_TOY_RUNNER_REGISTRATION_TOKEN", token);
    env.put("QITS_TOY_RUNNER_STATE_DIR", state.toString());
    env.put("QITS_TOY_RUNNER_SLOTS", "2");
    env.put("QITS_TOY_RUNNER_DOCKER_BINARY", docker.binary());
    env.put("QITS_TOY_RUNNER_DOCKER_TIMEOUT", "10");
    env.put("QITS_TOY_RUNNER_SELF_UPDATE", selfUpdate);
    return RunnerSettings.parse(Toy.IDENTITY, env::get)
        .withTiming(
            new RunnerSettings.Timing(heartbeatMillis, 50, 200, 5_000, refusalConfirmMillis, 3));
  }

  private CompletableFuture<Integer> start(String token, long heartbeatMillis) throws Exception {
    runner =
        new RunnerRuntime<>(
            vertx,
            Toy.IDENTITY,
            settings(token, heartbeatMillis),
            domain,
            new HostFacts(true, "amd64", "linux"),
            telemetry);
    exit = CompletableFuture.supplyAsync(runner::run);
    return exit;
  }

  /** The default host: Ack every Hello with these slots and this backlog. */
  private void ackWith(int slots, int backlog) {
    host.script =
        (h, message) -> {
          if (message instanceof Hello) {
            h.send(new Ack(Toy.IDENTITY.capabilityVersion(), slots));
            h.send(new Backlog(backlog));
          }
        };
  }

  @Test
  void anUnregisteredRunnerWithoutATokenExitsTwoBeforeDiallingAnything() throws Exception {
    int code = start(null, 10_000).get(20, TimeUnit.SECONDS);
    runner = null;
    assertEquals(RunnerExit.MISCONFIGURED, code);
    assertEquals(0, host.upgrades.get());
    assertEquals(0, host.registerBodies.size());
    assertTrue(domain.events.isEmpty(), "the domain heard nothing");
  }

  @Test
  void itRegistersSweepsDialsWithTheBearerAndSaysHello() throws Exception {
    ackWith(2, 0);
    host.script =
        host.script.andThen(
            (h, message) -> {
              if (message instanceof Hello) {
                domain.events.add("hello");
              }
            });
    start("one-time-token", 10_000);

    Hello hello = host.await(Hello.class);
    assertEquals(
        new Hello(Toy.VERSION, 1, 2, Toy.CAPS, List.of(), Map.of("toyFeature", "on")), hello);
    assertEquals("Bearer runner-access-token", host.upgradeHeaders.get("Authorization"));
    assertTrue(Files.exists(state.resolve("client.json")));
    awaitTrue(() -> domain.events.contains("ack2"));
    // The domain's session start is the sweep: before the Hello, with nothing carried; its Ack
    // after the toolkit's own handling.
    assertEquals(List.of("sessionStart[]", "hello", "ack2"), domain.events);
  }

  @Test
  void aBacklogAsksForARunAndATakeAsksForTheNextWhileASlotIsFree() throws Exception {
    host.script =
        (h, message) -> {
          if (message instanceof Hello) {
            h.send(new Ack(1, 2));
            h.send(new Backlog(3));
          } else if (message instanceof Reserve) {
            int reserves = h.all(Reserve.class).size();
            h.send(new Toy.Take("item-" + reserves));
          }
        };
    start("t", 10_000);

    host.await(Reserve.class, 2);
    Thread.sleep(300);
    assertEquals(2, host.all(Reserve.class).size(), "two slots, two items, no third Reserve");
    assertEquals(List.of("item-1", "item-2"), runner.session().held());
  }

  @Test
  void nothingWaitsForTheNextBacklogAndAReleasedRunFreesItsSlot() throws Exception {
    host.script =
        (h, message) -> {
          if (message instanceof Hello) {
            h.send(new Ack(1, 1));
            h.send(new Backlog(1));
          } else if (message instanceof Reserve) {
            if (h.all(Reserve.class).size() == 1) {
              h.send(new Nothing());
            } else {
              h.send(new Toy.Take("item-a"));
            }
          }
        };
    start("t", 10_000);

    host.await(Reserve.class, 1);
    Thread.sleep(300);
    assertEquals(1, host.all(Reserve.class).size(), "Nothing parks the runner");
    host.send(new Backlog(2));
    host.await(Reserve.class, 2);
    Thread.sleep(300);
    assertEquals(2, host.all(Reserve.class).size(), "one slot, held by item-a");
    host.send(new Toy.Release("item-a"));
    host.await(Reserve.class, 3);
  }

  /**
   * The host re-sends {@code Ack} whenever an admin changes the slot cap, and the runner must pick
   * up every one, not just the one that answered its {@code Hello}: a cap of 3 lets three
   * Reserve/take round trips happen with no release between them.
   */
  @Test
  void aLaterAckWithANewSlotsCapUpdatesTheRunnersCap() throws Exception {
    host.script =
        (h, message) -> {
          if (message instanceof Hello) {
            h.send(new Ack(1, 1));
            h.send(new Ack(1, 3));
            h.send(new Backlog(3));
          } else if (message instanceof Reserve) {
            int reserves = h.all(Reserve.class).size();
            h.send(new Toy.Take("item-" + reserves));
          }
        };
    start("t", 10_000);

    host.await(Reserve.class, 3);
    Thread.sleep(300);
    assertEquals(3, host.all(Reserve.class).size(), "three slots, three items, no fourth Reserve");
  }

  @Test
  void anAckInAnotherCapabilityVersionEndsTheProcessNonzero() throws Exception {
    host.script =
        (h, message) -> {
          if (message instanceof Hello) {
            h.send(new Ack(Toy.IDENTITY.capabilityVersion() + 1, 2));
          }
        };
    int code = start("t", 10_000).get(20, TimeUnit.SECONDS);
    runner = null;
    assertEquals(RunnerExit.CAPABILITY_MISMATCH, code);
  }

  /**
   * A host that does not adopt — whose {@code Ack} carries no adopted list — gets what it always
   * got: the carried item is claimed, then forgotten, and the domain's {@code onDropped} hears it,
   * before the session reserves anything.
   */
  @Test
  void aLostSocketIsRedialledAndACarriedRunTheHostDidNotKeepIsCancelled() throws Exception {
    ackWith(2, 0);
    start("t", 10_000);
    host.await(Hello.class, 1);
    host.send(new Backlog(1));
    host.await(Reserve.class);
    host.send(new Toy.Take("item-a"));
    awaitHeld(1);
    host.socket.close();

    host.await(Hello.class, 2);
    assertEquals(2, host.upgrades.get());
    assertEquals(List.of("item-a"), host.all(Hello.class).get(1).held(), "the Hello claims it");
    awaitHeld(0);
    awaitTrue(() -> domain.dropped.contains(List.of("item-a")));
    List<String> events = List.copyOf(domain.events);
    assertTrue(events.contains("sessionLost"), events::toString);
    assertTrue(events.contains("sessionStart[item-a]"), events::toString);
    assertTrue(
        events.indexOf("dropped[item-a]") < events.lastIndexOf("ack2"),
        "the domain drops before it hears the Ack: " + events);
  }

  @Test
  void aCarriedItemTheHostAdoptsKeepsItsSlotAndIsNotDropped() throws Exception {
    host.script =
        (h, message) -> {
          if (message instanceof Hello hello) {
            h.send(new Ack(1, 2, hello.held(), Map.of()));
          }
        };
    start("t", 10_000);
    host.await(Hello.class, 1);
    host.send(new Backlog(1));
    host.await(Reserve.class);
    host.send(new Toy.Take("item-a"));
    awaitHeld(1);
    host.socket.close();

    host.await(Hello.class, 2);
    awaitTrue(() -> domain.events.stream().filter(e -> e.equals("ack2")).count() == 2);
    assertEquals(1, runner.ledger().held(), "the adopted item still holds its slot");
    assertTrue(domain.dropped.isEmpty());
  }

  private void awaitHeld(int held) throws InterruptedException {
    awaitTrue(() -> runner.ledger().held() == held);
    assertEquals(held, runner.ledger().held());
  }

  @Test
  void heartbeatsRunUnderneathTheSession() throws Exception {
    ackWith(1, 0);
    start("t", 100);
    host.await(Heartbeat.class, 3);
  }

  @Test
  void theAckAndEveryHeartbeatSentTouchTheHeartbeatFile() throws Exception {
    Path beat = state.resolve(eu.wohlben.qits.runner.toolkit.Heartbeat.FILE);
    ackWith(1, 0);
    start("t", 10_000);
    host.await(Hello.class);
    awaitTrue(() -> Files.exists(beat));
    assertEquals(
        eu.wohlben.qits.runner.toolkit.Heartbeat.HEALTHY,
        eu.wohlben.qits.runner.toolkit.Heartbeat.check(
                state, java.time.Instant.now(), eu.wohlben.qits.runner.toolkit.Heartbeat.MAX_AGE)
            .exitCode(),
        "admitted is healthy at once, a whole heartbeat interval early");
  }

  @Test
  void aHeartbeatOnALiveSessionMovesTheMtimeForward() throws Exception {
    Path beat = state.resolve(eu.wohlben.qits.runner.toolkit.Heartbeat.FILE);
    ackWith(1, 0);
    start("t", 100);
    host.await(Heartbeat.class);
    awaitTrue(() -> Files.exists(beat));
    Files.setLastModifiedTime(beat, java.nio.file.attribute.FileTime.fromMillis(0));
    int seen = host.all(Heartbeat.class).size();
    host.await(Heartbeat.class, seen + 2);
    awaitTrue(
        () -> {
          try {
            return Files.getLastModifiedTime(beat).toMillis() > 0;
          } catch (java.io.IOException e) {
            return false;
          }
        });
  }

  @Test
  void aRunnerThatNeverConnectsWritesNoHeartbeat() throws Exception {
    // No Ack: a Hello the host never answers is not admission, and heartbeats are off.
    host.script = (h, message) -> {};
    start("t", 0);
    host.await(Hello.class);
    Thread.sleep(300);
    assertFalse(Files.exists(state.resolve(eu.wohlben.qits.runner.toolkit.Heartbeat.FILE)));
  }

  @Test
  void aRunnerWithSelfUpdateOffIgnoresAnUpgradeAndKeepsReserving() throws Exception {
    selfUpdate = "false";
    ackWith(1, 0);
    start("t", 10_000);
    Hello hello = host.await(Hello.class);
    assertEquals(
        Map.of("site", "home", "qits.toy.runner.self-update", "false"),
        hello.capabilities().labels(),
        "it says so in every Hello");
    host.send(new Backlog(1));
    host.await(Reserve.class, 1);
    host.send(new Toy.Take("item-a"));
    awaitHeld(1);

    host.send(new Upgrade("2026.999.1", "registry.example/qits/qits-toy-runner:2026.999.1", null));
    host.send(new Toy.Release("item-a"));
    host.send(new Backlog(1));
    host.await(Reserve.class, 2);
    Thread.sleep(300);
    assertFalse(runner.ledger().draining(), "nothing to drain for");
    assertTrue(docker.calls().isEmpty(), "no pull, no successor: " + docker.calls());
    assertFalse(exit.isDone());
  }

  @Test
  void aSelfUpdatingRunnerAdvertisesNoSelfUpdateLabelAndDrainsOnAnUpgrade() throws Exception {
    ackWith(1, 0);
    start("t", 10_000);
    assertEquals(Map.of("site", "home"), host.await(Hello.class).capabilities().labels());
    host.send(new Upgrade("2026.999.1", "registry.example/qits/qits-toy-runner:2026.999.1", null));
    awaitTrue(() -> runner.ledger().draining());
    host.send(new Backlog(3));
    Thread.sleep(300);
    assertTrue(host.all(Reserve.class).isEmpty(), "not one more Reserve after the Upgrade");
  }

  @Test
  void theCapabilitiesKeepTheDomainsLabelsAndExtensionsAndAddOnlyTheSelfUpdateLabel()
      throws Exception {
    domain =
        new Toy.Domain() {
          @Override
          public RunnerCapabilities capabilities(HostFacts host) {
            return new RunnerCapabilities(
                host.docker(), host.arch(), host.os(), Map.of("site", "home"), Map.of("x", 1));
          }
        };
    selfUpdate = "false";
    start("t", 10_000);
    RunnerCapabilities caps = runner.capabilities();
    assertEquals(
        Map.of("site", "home", "qits.toy.runner.self-update", "false"), caps.labels());
    assertEquals(Map.of("x", 1), caps.extensions());
    assertEquals("amd64", caps.arch());
  }

  @Test
  void anUnknownFrameTypeIsDroppedAndTheSessionCarriesOn() throws Exception {
    ackWith(1, 0);
    start("t", 10_000);
    host.await(Hello.class);

    host.sendRaw("{\"type\":\"upgradeV2\",\"version\":\"2\",\"image\":\"r/qits/x:2\"}");
    host.send(new Backlog(1));
    host.await(Reserve.class);
    assertEquals(1, host.upgrades.get(), "still the first session: only the frame was dropped");
    assertFalse(exit.isDone());
  }

  @Test
  void aDomainFrameReachesOnMessageAndALifecycleFrameNeverDoes() throws Exception {
    ackWith(1, 0);
    start("t", 10_000);
    host.await(Hello.class);

    host.send(new Backlog(0));
    host.send(new Nothing());
    host.send(new Quarantined("why", "2026-10-04T00:00:00Z"));
    host.send(new Reinstated("admin"));
    host.send(new Ack(1, 1));
    host.send(new Toy.Take("item-a"));
    host.send(new Toy.Release("item-a"));
    awaitTrue(() -> domain.messages.size() == 2);
    Thread.sleep(200);
    assertEquals(
        List.of(new Toy.Take("item-a"), new Toy.Release("item-a")),
        domain.messages,
        "only the domain's own frames, in the domain's own types");
  }

  @Test
  void theHostClosingRunnerDeletedDecommissionsAtOnce() throws Exception {
    ackWith(1, 0);
    start("t", 10_000);
    host.await(Hello.class);

    host.closeSocket(RunnerWire.CLOSE_POLICY_VIOLATION, RunnerWire.CloseReason.RUNNER_DELETED);
    assertEquals(RunnerExit.OK, exit.get(10, TimeUnit.SECONDS));
    runner = null;
    assertDecommissioned();
    assertEquals(1, host.upgrades.get(), "no redial");
  }

  @Test
  void aDeletedRetireDecommissionsAndASupersededOneLeavesWithZero() throws Exception {
    ackWith(1, 0);
    start("t", 10_000);
    host.await(Hello.class);
    host.send(Retire.deleted("deleted by admin"));
    assertEquals(RunnerExit.OK, exit.get(10, TimeUnit.SECONDS));
    runner = null;
    assertDecommissioned();
    host.send(new Backlog(3));
    Thread.sleep(200);
    assertTrue(host.all(Reserve.class).isEmpty(), "a deleted runner takes no more work");

    // Superseded: an orderly exit, and the client stays for the successor that took over.
    host.close();
    host = new FakeRunnerHost(vertx, Toy.IDENTITY, Toy.codec());
    domain = new Toy.Domain();
    ackWith(1, 0);
    start("t2", 10_000);
    host.await(Hello.class);
    host.send(new Retire("superseded by 2026.999.1"));
    assertEquals(RunnerExit.OK, exit.get(10, TimeUnit.SECONDS));
    runner = null;
    assertFalse(domain.events.contains("decommission"), domain.events::toString);
    assertTrue(Files.exists(state.resolve("client.json")), "a successor dials with this client");
  }

  /** The decommission's toolkit half: the domain stopped its work, and the client is forgotten. */
  private void assertDecommissioned() {
    assertTrue(domain.events.contains("decommission"), domain.events::toString);
    assertFalse(Files.exists(state.resolve("client.json")), "the revoked client is forgotten");
  }

  @Test
  void anyOtherRefusalCloseIsRedialledAsEver() throws Exception {
    ackWith(1, 0);
    start("t", 10_000);
    host.await(Hello.class);

    // UNKNOWN_RUNNER is also what a host that cannot read identity at all says: not the runner's.
    host.closeSocket(RunnerWire.CLOSE_POLICY_VIOLATION, "UNKNOWN_RUNNER");
    host.await(Hello.class, 2);
    assertFalse(exit.isDone());
    assertFalse(domain.events.contains("decommission"));
    assertTrue(Files.exists(state.resolve("client.json")));
  }

  @Test
  void aTokenEndpointThatKeepsRefusingTheClientIsReadAsDeletedAfterTheStreak() throws Exception {
    refusalConfirmMillis = 400;
    host.tokenStatus = 401;
    host.tokenErrorBody =
        "{\"error\":\"invalid_client\",\"error_description\":\"client authentication failed\"}";
    start("t", 10_000);

    assertEquals(RunnerExit.OK, exit.get(15, TimeUnit.SECONDS));
    runner = null;
    assertDecommissioned();
    assertTrue(host.tokenBodies.size() >= 3, "a streak, not one answer: " + host.tokenBodies.size());
    assertEquals(0, host.upgrades.get());
  }

  @Test
  void aTokenEndpointThatIsDownOrAnEdgeRefusalIsRetriedForever() throws Exception {
    refusalConfirmMillis = 200;
    host.tokenStatus = 502;
    host.tokenErrorBody = "Bad Gateway";
    start("t", 10_000);

    awaitTrue(() -> host.tokenBodies.size() >= 6);
    host.tokenStatus = 401;
    host.tokenErrorBody = "{\"error\":\"client authentication is required\"}";
    int before = host.tokenBodies.size();
    awaitTrue(() -> host.tokenBodies.size() >= before + 6);
    assertFalse(exit.isDone(), "transient failures never decommission");
    assertFalse(domain.events.contains("decommission"));
    assertTrue(Files.exists(state.resolve("client.json")));

    // And the platform coming back is a runner that connects as if nothing happened.
    ackWith(1, 0);
    host.tokenStatus = 200;
    host.await(Hello.class);
    assertFalse(exit.isDone());
  }

  @Test
  void aQuarantinedFrameSetsTheRunnersOwnFlagAndAReinstatedClearsIt() throws Exception {
    ackWith(1, 0);
    start("t", 10_000);
    host.await(Hello.class);
    assertFalse(runner.quarantined());
    assertFalse(runner.session().quarantined());

    host.send(new Quarantined("3 runner-caused failures in a row", "2026-09-28T12:00:00Z"));
    awaitTrue(runner::quarantined);
    assertTrue(runner.session().quarantined(), "the domain can read it too");

    host.send(new Reinstated("admin"));
    awaitTrue(() -> !runner.quarantined());
  }

  /**
   * The hop a TelemetryTest cannot see: the runtime hands its log shipper the bearer it dials with,
   * and the lines logged before it had one — its registration — are shipped with it.
   */
  @Test
  void itShipsItsLogWithTheBearerItDialsWith() throws Exception {
    telemetry =
        new Telemetry(
            vertx,
            Toy.IDENTITY.logName(),
            new Http(vertx, 5_000),
            host.base() + FakeRunnerHost.OTEL_PATH,
            Map.of("service.name", Toy.IDENTITY.serviceName()),
            "test",
            new Telemetry.Settings(100, 50, 50),
            () -> 1L);
    telemetry.publish(new LogRecord(Level.INFO, "logged before the bearer"));
    ackWith(1, 0);
    start("t", 10_000);
    host.await(Hello.class);

    awaitTrue(() -> !host.telemetryBodies.isEmpty());
    assertEquals("Bearer runner-access-token", host.telemetryAuthorizations.getFirst());
    assertEquals(
        "logged before the bearer",
        OtlpDecode.decode(host.telemetryBodies.getFirst()).lines().getFirst().body());
  }

  private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertTrue(condition.getAsBoolean(), "the condition never held");
  }
}
