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
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

  /** The runner's own container, as HOSTNAME would name it. */
  private static final String SELF = "0123456789ab";

  private Optional<String> self = Optional.of(SELF);
  private SelfUpdate.Settings selfUpdateSettings =
      new SelfUpdate.Settings(100, 400, 3_000, 3_000, 50);
  private Housekeeping.Settings housekeepingSettings = Housekeeping.Settings.defaults();
  private Clock clock = Clock.systemUTC();

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
            telemetry,
            new RunnerRuntime.NodeOptions(
                self,
                selfUpdateSettings,
                new Decommission.Settings(200, 20, 2),
                housekeepingSettings,
                clock));
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
    assertTrue(
        docker.calls("pull").isEmpty() && docker.calls("run").isEmpty(),
        "no pull, no successor: " + docker.calls());
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
    assertEquals(2, domain.messages.size(), domain.messages::toString);
    // As a set: domain frames run on a cached worker pool, which promises no order between two.
    assertEquals(
        java.util.Set.of(new Toy.Take("item-a"), new Toy.Release("item-a")),
        java.util.Set.copyOf(domain.messages),
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

  // ---- self-update ------------------------------------------------------------------------------

  private static final String NEXT = "2026.999.1";
  private static final String IMAGE = "registry.example:5000/qits/qits-toy-runner:" + NEXT;
  private static final String DIGEST =
      "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final String SUCCESSOR = "qits-toy-runner-r1-" + NEXT;

  /**
   * A fake docker that answers like the runner's own container: its inspect (what the install
   * script started), its old image's config, and the pulled image's digest.
   *
   * <p>Deliberately no {@code Mounts} key on the container at all — {@code HostConfig} carries
   * {@code Binds} only, which is docker's real shape for a container started with {@code -v} and
   * never {@code --mount}, and is exactly what broke CI's old {@code --format} template (qits-463).
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
                                        "DOCKER_VERSION=29.8.1",
                                        "QITS_TOY_RUNNER_URL=https://toy.qits.example.eu",
                                        "QITS_TOY_RUNNER_ID=r1",
                                        "QITS_TOY_RUNNER_SLOTS=2",
                                        "QITS_TOY_RUNNER_REGISTRATION_TOKEN=qits_tok_SPENT"))
                                .put(
                                    "Labels",
                                    Map.of(
                                        "qits.toy.runner.process", "r1",
                                        "qits.toy.runner.version", Toy.VERSION,
                                        "org.opencontainers.image.version", "29.8.1")))
                        .put(
                            "HostConfig",
                            new JsonObject()
                                .put(
                                    "Binds",
                                    List.of(
                                        "/var/run/docker.sock:/var/run/docker.sock",
                                        "qits-toy-runner-state-r1:/var/lib/qits-toy-runner"))
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
                                        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                                        "DOCKER_VERSION=29.8.1"))
                                .put("Labels", Map.of("org.opencontainers.image.version", "29.8.1"))))
                .encode()
            + "\n",
        "");
    docker.answerFor(
        "image-inspect",
        IMAGE,
        0,
        "[\"registry.example:5000/qits/qits-toy-runner@" + DIGEST + "\"]\n",
        "");
  }

  private static final List<String> SUCCESSOR_RUN =
      List.of(
          "run",
          "-d",
          "--name",
          SUCCESSOR,
          "--restart",
          "unless-stopped",
          "--label",
          "qits.toy.runner.process=r1",
          "--label",
          "qits.toy.runner.version=" + NEXT,
          "-v",
          "/var/run/docker.sock:/var/run/docker.sock",
          "-v",
          "qits-toy-runner-state-r1:/var/lib/qits-toy-runner",
          "-e",
          "QITS_TOY_RUNNER_ID=r1",
          "-e",
          "QITS_TOY_RUNNER_SLOTS=2",
          "-e",
          "QITS_TOY_RUNNER_URL=https://toy.qits.example.eu",
          IMAGE);

  private void awaitCall(java.util.function.Predicate<List<List<String>>> condition)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (!condition.test(docker.calls()) && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertTrue(condition.test(docker.calls()), () -> "docker calls: " + docker.calls());
  }

  private static boolean has(List<List<String>> calls, List<String> call) {
    return calls.stream().map(FakeDocker::withoutConfig).anyMatch(call::equals);
  }

  /** Take one item and hold it; answers once the runner counts it. */
  private void holdAnItem() throws Exception {
    host.script =
        (h, message) -> {
          if (message instanceof Hello) {
            h.send(new Ack(Toy.IDENTITY.capabilityVersion(), 2));
            h.send(new Backlog(1));
          } else if (message instanceof Reserve) {
            h.send(new Toy.Take("item-a"));
          }
        };
    start("t", 10_000);
    host.await(Reserve.class);
    awaitHeld(1);
  }

  @Test
  void anUpgradeDrainsAndStartsTheSuccessorOnlyOnceTheDomainIsQuiescent() throws Exception {
    domain.quiescent = false;
    runnerContainer();
    holdAnItem();

    host.send(new Upgrade(NEXT, IMAGE, DIGEST));
    host.send(new Backlog(5));
    awaitCall(c -> has(c, List.of("pull", IMAGE)));
    Thread.sleep(300);
    assertEquals(1, host.all(Reserve.class).size(), "a draining runner reserves nothing");
    assertTrue(docker.calls("run").isEmpty(), "no successor while the domain is busy");

    // No frame, no freed slot: the domain merely says it is quiescent now, and the poll sees it.
    domain.quiescent = true;
    awaitCall(c -> !docker.calls("run").isEmpty());
    assertEquals(SUCCESSOR_RUN, docker.calls("run").getFirst());
    assertEquals(1, host.all(Reserve.class).size(), "nothing is refilled while draining");
    assertFalse(exit.isDone(), "the old process stays until it is retired");
  }

  /**
   * A workspace runner's containers outlive the runner process, so it holds work and is quiescent
   * at once: the rollover goes ahead without a drain that would never finish.
   */
  @Test
  void aDomainThatHoldsWorkButIsQuiescentRollsOverWithoutWaiting() throws Exception {
    runnerContainer();
    holdAnItem();

    host.send(new Upgrade(NEXT, IMAGE, DIGEST));
    awaitCall(c -> !docker.calls("run").isEmpty());
    assertEquals(SUCCESSOR_RUN, docker.calls("run").getFirst());
    assertEquals(List.of("item-a"), runner.session().held(), "the item is still held");
  }

  @Test
  void theUpgradePullRunsUnderAThrowawayLoginMadeOfTheRunnersOwnClientPair() throws Exception {
    runnerContainer();
    ackWith(2, 0);
    start("t", 10_000);
    host.await(Hello.class);

    host.send(new Upgrade(NEXT, IMAGE, null));
    awaitCall(c -> !docker.calls("run").isEmpty());

    List<String> pull =
        docker.calls().stream().filter(c -> c.contains("pull")).findFirst().orElseThrow();
    assertEquals("--config", pull.get(0));
    assertEquals(List.of("pull", IMAGE), pull.subList(2, 4));
    assertFalse(Files.exists(Path.of(pull.get(1))), "the login directory is removed after the pull");
    List<String> config = docker.configs().getFirst();
    assertEquals("700 600", config.get(0), "directory 0700, config.json 0600");
    String auth =
        Base64.getEncoder()
            .encodeToString(
                "toy-runner-r1:client-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertEquals(
        new JsonObject()
            .put(
                "auths",
                new JsonObject()
                    .put("registry.example:5000", new JsonObject().put("auth", auth))),
        new JsonObject(config.get(1)));
    assertTrue(
        docker.calls().stream()
            .flatMap(List::stream)
            .noneMatch(a -> a.contains("runner-access-token")),
        "the bearer is in no argv");
    assertTrue(
        docker.calls().stream().flatMap(List::stream).noneMatch(a -> a.contains("client-secret")),
        "the client secret is in no argv");
  }

  @Test
  void aPulledImageWithAnotherDigestIsNeverStartedAndThePullIsRetried() throws Exception {
    runnerContainer();
    docker.answerFor(
        "image-inspect",
        IMAGE,
        0,
        "[\"registry.example:5000/qits/qits-toy-runner@sha256:beef\"]\n",
        "");
    ackWith(2, 0);
    start("t", 10_000);
    host.await(Hello.class);

    host.send(new Upgrade(NEXT, IMAGE, DIGEST.substring("sha256:".length())));
    awaitCall(c -> docker.calls("pull").size() >= 2);
    assertTrue(docker.calls("run").isEmpty(), "an image that is not the expected one is not run");
  }

  @Test
  void aRetireWithinTheWatchTakesTheRestartPolicyAwayAndExitsZero() throws Exception {
    runnerContainer();
    ackWith(2, 0);
    start("t", 10_000);
    host.await(Hello.class);
    host.send(new Upgrade(NEXT, IMAGE, DIGEST));
    awaitCall(c -> !docker.calls("run").isEmpty());

    host.send(new Retire("superseded by " + NEXT));
    assertEquals(RunnerExit.OK, exit.get(10, TimeUnit.SECONDS));
    runner = null;
    assertTrue(has(docker.calls(), List.of("update", "--restart=no", SELF)), docker.calls()::toString);
    List<List<String>> calls = docker.calls();
    assertFalse(
        calls.subList(calls.indexOf(SUCCESSOR_RUN) + 1, calls.size())
            .contains(List.of("rm", "-f", SUCCESSOR)),
        "the successor is left running");
  }

  @Test
  void aSuccessorThatDoesNotTakeOverIsRemovedAndTheRunnerStaysDrainingAndRetries()
      throws Exception {
    selfUpdateSettings = new SelfUpdate.Settings(100, 400, 300, 3_000, 50);
    runnerContainer();
    ackWith(2, 0);
    start("t", 10_000);
    host.await(Hello.class);
    host.send(new Upgrade(NEXT, IMAGE, DIGEST));

    // Started, not retired within the watch, removed — and started again on the retry.
    awaitCall(c -> docker.calls("run").size() >= 2);
    List<List<String>> calls = docker.calls();
    int firstRun = calls.indexOf(SUCCESSOR_RUN);
    assertTrue(
        calls.subList(firstRun + 1, calls.size()).contains(List.of("rm", "-f", SUCCESSOR)),
        calls::toString);
    assertFalse(has(calls, List.of("update", "--restart=no", SELF)), "it never took itself out");
    host.send(new Backlog(3));
    Thread.sleep(300);
    assertTrue(host.all(Reserve.class).isEmpty(), "still draining");
    assertFalse(exit.isDone());
  }

  @Test
  void aRetireWhileTheUpgradeHasNoSuccessorRunningIsIgnored() throws Exception {
    domain.quiescent = false;
    runnerContainer();
    holdAnItem();
    host.send(new Upgrade(NEXT, IMAGE, DIGEST));
    awaitCall(c -> has(c, List.of("pull", IMAGE)));

    host.send(new Retire("a runner of " + NEXT + " said Hello"));
    Thread.sleep(500);
    assertFalse(exit.isDone(), "leaving now would leave the host with no runner at all");
    assertFalse(has(docker.calls(), List.of("update", "--restart=no", SELF)));
  }

  @Test
  void aRetireWithNoUpgradeAskedForIsTheSameOrderlyExit() throws Exception {
    ackWith(1, 0);
    start("t", 10_000);
    host.await(Hello.class);

    host.send(new Retire("decommissioned"));
    assertEquals(RunnerExit.OK, exit.get(10, TimeUnit.SECONDS));
    runner = null;
    assertTrue(has(docker.calls(), List.of("update", "--restart=no", SELF)), docker.calls()::toString);
    assertNoHelper();
  }

  // --- a deleted runner ---------------------------------------------------------------------------

  private static final List<String> HELPER_RUN =
      List.of(
          "run",
          "-d",
          "--name",
          "qits-toy-runner-r1-decommission",
          "--network",
          "none",
          "--label",
          "qits.toy.runner.decommission=r1",
          "-v",
          "/var/run/docker.sock:/var/run/docker.sock",
          "-e",
          "QITS_TOY_RUNNER_DECOMMISSION=r1",
          "-e",
          "QITS_TOY_RUNNER_DECOMMISSION_VOLUME=qits-toy-runner-state-r1",
          "sha256:old");

  /**
   * The runner's own container as {@code docker inspect} answers it, with the state volume mounted
   * at this test's state directory — where {@link Decommission} looks for it.
   */
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
                                        .put("Name", "qits-toy-runner-state-r1")
                                        .put("Destination", state.toString())))
                        .put(
                            "HostConfig",
                            new JsonObject()
                                .put("RestartPolicy", Map.of("Name", "unless-stopped"))))
                .encode()
            + "\n",
        "");
  }

  /** Neither the helper nor a volume removal: what a self-update's retirement must never do. */
  private void assertNoHelper() {
    assertFalse(docker.calls().contains(HELPER_RUN), docker.calls()::toString);
    assertTrue(
        docker.calls().stream()
            .noneMatch(c -> c.contains("volume") || String.join(" ", c).contains("DECOMMISSION")),
        docker.calls()::toString);
    assertTrue(Files.exists(state.resolve("client.json")), "a successor dials with this client");
  }

  /** The decommission's docker half, in order: stay down, then the helper. */
  private void assertHandedToTheHelper() {
    List<List<String>> calls = docker.calls();
    int stayDown = calls.indexOf(List.of("update", "--restart=no", SELF));
    int helper = calls.indexOf(HELPER_RUN);
    assertTrue(stayDown >= 0, calls::toString);
    assertTrue(helper > stayDown, calls::toString);
    assertDecommissioned();
  }

  @Test
  void aDeletedRetireCancelsHeldRunsStaysDownAndHandsContainerAndVolumeToTheHelper()
      throws Exception {
    containerWithStateVolume();
    // The domain's own stop: its held item's container, through the session's driver.
    domain.decommissioning =
        session -> session.driver().remove("toy-item-a", Duration.ofSeconds(5));
    holdAnItem();

    host.send(Retire.deleted("deleted by admin"));
    assertEquals(RunnerExit.OK, exit.get(10, TimeUnit.SECONDS));
    runner = null;
    assertHandedToTheHelper();
    List<List<String>> calls = docker.calls();
    assertTrue(
        calls.indexOf(List.of("rm", "-f", "toy-item-a"))
            < calls.indexOf(List.of("update", "--restart=no", SELF)),
        "the domain's work goes first: " + calls);
    host.send(new Backlog(3));
    Thread.sleep(200);
    assertEquals(1, host.all(Reserve.class).size(), "a deleted runner takes no more work");
    assertEquals(1, host.upgrades.get(), "and never dials again");
  }

  @Test
  void aDeletedRetireMidRolloverStillDecommissionsWhereASupersededOneWouldBeIgnored()
      throws Exception {
    domain.quiescent = false;
    runnerContainer();
    containerWithStateVolume();
    holdAnItem();
    host.send(new Upgrade(NEXT, IMAGE, DIGEST));
    awaitCall(c -> has(c, List.of("pull", IMAGE)));

    host.send(Retire.deleted("deleted by admin"));
    assertEquals(RunnerExit.OK, exit.get(10, TimeUnit.SECONDS));
    runner = null;
    assertHandedToTheHelper();
    assertTrue(docker.calls("run").stream().noneMatch(c -> c.contains(SUCCESSOR)), "no successor");
  }

  @Test
  void theDomainsTeardownItemsTravelToTheHelper() throws Exception {
    containerWithStateVolume();
    domain.teardown =
        List.of(new TeardownItem(TeardownItem.Type.CONTAINER, "qits-toy-runner-builder-r1"));
    ackWith(1, 0);
    start("t", 10_000);
    host.await(Hello.class);

    host.send(Retire.deleted("deleted by admin"));
    assertEquals(RunnerExit.OK, exit.get(10, TimeUnit.SECONDS));
    runner = null;
    List<String> helper = docker.calls("run").getFirst();
    assertTrue(
        helper.contains("QITS_TOY_RUNNER_DECOMMISSION_TEARDOWN=container:qits-toy-runner-builder-r1"),
        helper::toString);
  }

  /**
   * The first belt: a teardown item without the runner's prefix never reaches the helper's
   * environment, so a neighbour's name is in no argv of this runner at all.
   */
  @Test
  void aTeardownItemWithoutThePrefixNeverReachesTheHelper() throws Exception {
    containerWithStateVolume();
    domain.teardown =
        List.of(
            new TeardownItem(TeardownItem.Type.CONTAINER, "qits-ci-runner-buildkitd"),
            new TeardownItem(TeardownItem.Type.NETWORK, "qits-toy-runner-net-r1"));
    ackWith(1, 0);
    start("t", 10_000);
    host.await(Hello.class);

    host.send(Retire.deleted("deleted by admin"));
    assertEquals(RunnerExit.OK, exit.get(10, TimeUnit.SECONDS));
    runner = null;
    List<String> helper = docker.calls("run").getFirst();
    assertTrue(
        helper.contains("QITS_TOY_RUNNER_DECOMMISSION_TEARDOWN=network:qits-toy-runner-net-r1"),
        helper::toString);
    assertTrue(
        docker.calls().stream()
            .flatMap(List::stream)
            .noneMatch(a -> a.contains("qits-ci-runner-buildkitd")),
        docker.calls()::toString);
  }

  @Test
  void theFirstAckRemovesExitedAndLingeringPredecessorsAndNeverItself() throws Exception {
    selfUpdateSettings = new SelfUpdate.Settings(100, 400, 3_000, 400, 50);
    docker.answerFor(
        "ps",
        "label=qits.toy.runner.process=r1",
        0,
        String.join(
            "\n",
            "0123456789ab|" + Toy.VERSION + "|running",
            "aaaaaaaaaaaa|2026.900.1|exited",
            "bbbbbbbbbbbb|2026.900.2|running",
            "cccccccccccc|2026.900.3|running",
            ""),
        "");
    docker.answerFor("inspect", "bbbbbbbbbbbb", 0, "exited\n", "");
    docker.answerFor("inspect", "cccccccccccc", 0, "running\n", "");
    ackWith(1, 0);
    start("t", 10_000);

    awaitCall(c -> has(c, List.of("rm", "-f", "cccccccccccc")));
    List<List<String>> calls = docker.calls();
    assertTrue(
        calls.contains(
            List.of(
                "ps",
                "-a",
                "--format",
                "{{.ID}}|{{.Label \"qits.toy.runner.version\"}}|{{.State}}|{{.Image}}",
                "--filter",
                "label=qits.toy.runner.process=r1")),
        calls::toString);
    assertTrue(calls.contains(List.of("rm", "-f", "aaaaaaaaaaaa")), "the exited predecessor");
    assertTrue(
        calls.contains(List.of("rm", "-f", "bbbbbbbbbbbb")), "the one that exited while waited on");
    int waited =
        calls.indexOf(List.of("inspect", "--format", "{{.State.Status}}", "bbbbbbbbbbbb"));
    assertTrue(waited >= 0 && waited < calls.indexOf(List.of("rm", "-f", "bbbbbbbbbbbb")));
    assertFalse(calls.contains(List.of("rm", "-f", SELF)), "never itself");
    // A reconnect's Ack does not look for predecessors again: once per process.
    host.socket.close();
    host.await(Hello.class, 2);
    Thread.sleep(300);
    assertEquals(
        1,
        docker.calls("ps").stream()
            .filter(c -> c.contains("label=qits.toy.runner.process=r1"))
            .count(),
        docker.calls()::toString);
  }

  // --- housekeeping -------------------------------------------------------------------------------

  @Test
  void housekeepingRunsAfterTheFirstAckThenOnItsIntervalAndRunsTheDomainsPasses()
      throws Exception {
    housekeepingSettings = new Housekeeping.Settings(150);
    clock = Clock.fixed(Instant.parse("2026-10-04T00:00:00Z"), ZoneOffset.UTC);
    List<HousekeepingContext> seen = Collections.synchronizedList(new ArrayList<>());
    domain.passes.add(
        new HousekeepingPass() {
          @Override
          public String name() {
            return "toy";
          }

          @Override
          public void run(HousekeepingContext context) {
            seen.add(context);
          }
        });
    host.script = (h, message) -> {};
    start("t", 0);
    host.await(Hello.class);
    Thread.sleep(400);
    assertTrue(seen.isEmpty(), "not before the runner is proven on the wire");
    assertTrue(docker.calls("image").isEmpty(), docker.calls()::toString);

    host.send(new Ack(Toy.IDENTITY.capabilityVersion(), 1));
    awaitTrue(() -> seen.size() >= 3);
    HousekeepingContext context = seen.getFirst();
    assertEquals(clock, context.clock(), "the injected clock");
    assertEquals("r1", context.runnerId());
    assertEquals(state, context.stateDir());
    List<List<String>> prunes =
        docker.calls("image").stream().filter(c -> c.contains("prune")).toList();
    assertTrue(prunes.size() >= 3, docker.calls()::toString);
    assertTrue(prunes.stream().allMatch(c -> c.equals(List.of("image", "prune", "-f"))));

    // Decommissioned: no pass after it.
    host.send(Retire.deleted("deleted by admin"));
    assertEquals(RunnerExit.OK, exit.get(10, TimeUnit.SECONDS));
    runner = null;
    int after = seen.size();
    Thread.sleep(500);
    assertEquals(after, seen.size(), "housekeeping stops on decommission");
  }

  private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertTrue(condition.getAsBoolean(), "the condition never held");
  }
}
