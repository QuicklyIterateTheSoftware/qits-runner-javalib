package eu.wohlben.qits.runner.toolkit;

import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.runner.protocol.Backlog;
import eu.wohlben.qits.runner.protocol.Hello;
import eu.wohlben.qits.runner.protocol.LifecycleMessage;
import eu.wohlben.qits.runner.protocol.Nothing;
import eu.wohlben.qits.runner.protocol.Quarantined;
import eu.wohlben.qits.runner.protocol.Reinstated;
import eu.wohlben.qits.runner.protocol.Reserve;
import eu.wohlben.qits.runner.protocol.Retire;
import eu.wohlben.qits.runner.protocol.RunnerCapabilities;
import eu.wohlben.qits.runner.protocol.RunnerCodec;
import eu.wohlben.qits.runner.protocol.RunnerMessage;
import eu.wohlben.qits.runner.protocol.Upgrade;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jboss.logging.Logger;

/**
 * A runner's whole life, for any domain:
 *
 * <pre>
 *   register (once) → bearer → log shipper → [ dial → onSessionStart → Hello → Ack{slots} →
 *   Backlog / Reserve / domain frames … → close ] until Retire, until the platform says the
 *   runner was deleted, or until its credential is refused for good
 * </pre>
 *
 * <p><b>The toolkit handles the lifecycle frames; the domain handles its own.</b> {@code Ack},
 * {@code Backlog}, {@code Nothing}, {@code Upgrade}, {@code Retire}, {@code Quarantined} and {@code
 * Reinstated} never reach {@link RunnerDomain#onMessage}; every domain frame does, on a cached
 * worker pool, because a domain's answers are docker calls that block.
 *
 * <p><b>A deleted runner decommissions itself</b>: told so by a {@code Retire} of kind {@code
 * DELETED} while connected, or by a 1008 {@code RUNNER_DELETED} close — the platform's two explicit
 * words, and the only two. It takes no more work, lets the domain stop its own ({@link
 * RunnerDomain#onDecommission}), hands the node half to its {@link NodeLifecycle}, and exits 0.
 *
 * <p><b>A runner whose credential is revoked only stops</b> (qits-1592): an {@code invalid_client}
 * streak on its dials ({@link ControlSocket.Listener#onCredentialsRevoked}) says the idp no longer
 * knows its client, not that the platform deleted the runner. It takes no more work, takes its own
 * restart policy away and exits 0 — the way a superseded {@code Retire} leaves — and removes
 * nothing: no {@link RunnerDomain#onDecommission}, no helper, no container, no volume, not even its
 * {@code client.json}. Purging it takes an explicit retire.
 *
 * <p>A plain class with a plain constructor: {@link RunnerLauncher} builds it from the
 * environment, and a suite drives it against {@code FakeRunnerHost} and a script standing in for
 * docker. This is qits-ci-runner-daemon's {@code RunnerMain}, its lifecycle half.
 *
 * @param <D> the runner's own sealed message set
 */
public final class RunnerRuntime<D extends RunnerMessage> implements ControlSocket.Listener {

  private static final Logger LOG = Logger.getLogger(RunnerRuntime.class);

  /** How long an exiting runner waits for its last log lines to be shipped. */
  static final long TELEMETRY_LAST_WORDS_MILLIS = 3_000;

  /** How long an exiting runner waits for its threads once it has had to interrupt them. */
  static final long INTERRUPTED_THREADS_MILLIS = 5_000;

  private final Vertx vertx;
  private final RunnerIdentity identity;
  private final RunnerSettings settings;
  private final RunnerDomain<D> domain;
  private final HostFacts host;
  private final Telemetry telemetry;
  private final NodeLifecycle lifecycle;
  private final RunnerDocker docker;
  private final RegistryLogin registry;
  private final RunnerCodec<D> codec;
  private final Class<D> domainType;
  private final Http http;
  private final SlotLedger ledger = new SlotLedger();
  private final RunnerSession session;
  private final String who;

  /**
   * Docker calls block, and frames arrive on an event loop, so every domain answer runs here.
   * Unbounded on purpose: the host never has more work in flight than slots it granted.
   */
  private final ExecutorService workers;

  private final CompletableFuture<Integer> exit = new CompletableFuture<>();

  /**
   * Set by {@link #run}'s exit, under its own lock: from then on the heartbeat file is not touched
   * again, so a runner that has returned writes nothing more to its state directory.
   */
  private final Object stateDirLock = new Object();

  private boolean stopped;
  private volatile ControlSocket<D> socket;
  private volatile Bearer bearer;

  /** The runner's own client pair, once registered: the registry login every pull is made under. */
  private volatile ClientCredentials client;

  /**
   * The domain's removal of the carried items the host did not adopt, which every {@code Ack}'s
   * slots wait behind — see {@link #onAck}. Complete when there was nothing to remove.
   */
  private volatile CompletableFuture<Void> carriedDropped = CompletableFuture.completedFuture(null);

  /** The slot cap of the newest {@code Ack}. */
  private volatile int ackedSlots;

  /** Set by the first {@code Ack}: predecessors and housekeeping are once per process. */
  private final AtomicBoolean acked = new AtomicBoolean();

  /**
   * Set once, by the first of the ways a runner ends for good with no successor: learning it was
   * deleted, or its credential being refused for good.
   */
  private final AtomicBoolean ending = new AtomicBoolean();

  /**
   * For this runner's own status only. The host answers every {@code Reserve} with {@code Nothing}
   * while a runner is quarantined, so nothing here branches on it — the frames exist to tell the
   * person at the machine, via the log, why the runner sits idle (or no longer does).
   */
  private volatile boolean quarantined;

  /** What this host can do, as the registration and every Hello say it; fixed at {@link #run}. */
  private volatile RunnerCapabilities capabilities;

  /**
   * @param host the toolkit's view of the host, handed to {@link RunnerDomain#capabilities}
   * @param telemetry the runner's own log shipper, started once the bearer exists; {@link
   *     Telemetry#off()} for none
   */
  public RunnerRuntime(
      Vertx vertx,
      RunnerIdentity identity,
      RunnerSettings settings,
      RunnerDomain<D> domain,
      HostFacts host,
      Telemetry telemetry) {
    this(
        vertx,
        identity,
        settings,
        domain,
        host,
        telemetry,
        NodeOptions.defaults(settings));
  }

  /**
   * What the runtime cannot read off its settings: which container it is in, and the clocks under
   * the node's work. {@link #defaults} is production's — the container detected from {@code
   * /proc/self/mountinfo}, CI's clocks.
   *
   * <p><b>Public for an embedder's suite</b>, which drives its whole runner against {@code
   * FakeRunnerHost} and {@code FakeDocker} from outside this package (the harness, qits-624's
   * runner): it names the container the runner is "in", so a self-update and a decommission have a
   * container to inspect, and it shrinks the clocks. Production never builds one by hand; {@link
   * RunnerLauncher} uses the defaults.
   *
   * @param self the runner's own container id, or empty when it is not in one (then there is no
   *     self-update and no helper)
   * @param selfUpdate the rollover's retry, watch and poll clocks
   * @param decommission the helper's waits
   * @param housekeeping the GC interval
   * @param clock what a housekeeping pass reads as now
   */
  public record NodeOptions(
      Optional<String> self,
      SelfUpdate.Settings selfUpdate,
      Decommission.Settings decommission,
      Housekeeping.Settings housekeeping,
      Clock clock) {

    /** Production's: the detected container and every default clock. */
    public static NodeOptions defaults(RunnerSettings settings) {
      return new NodeOptions(
          SelfContainer.detect(),
          SelfUpdate.Settings.defaults(settings.rolloverTimeoutSeconds()),
          Decommission.Settings.defaults(),
          Housekeeping.Settings.defaults(),
          Clock.systemUTC());
    }
  }

  /**
   * The same runtime in a container a suite names, with the node's clocks a suite shrinks — the
   * entry an embedder's suite drives its runner through ({@link NodeOptions}).
   */
  public RunnerRuntime(
      Vertx vertx,
      RunnerIdentity identity,
      RunnerSettings settings,
      RunnerDomain<D> domain,
      HostFacts host,
      Telemetry telemetry,
      NodeOptions node) {
    this.vertx = vertx;
    this.identity = identity;
    this.settings = settings;
    this.domain = domain;
    this.host = host;
    this.telemetry = telemetry;
    this.docker = RunnerDocker.of(identity, settings);
    this.registry = new RegistryLogin(identity, docker, () -> client);
    this.lifecycle =
        NodeLifecycle.onDocker(identity, settings, domain, docker, registry, node);
    this.codec = new RunnerCodec<>(identity.vocabulary(), domain.codec());
    this.domainType = domain.codec().messageType();
    this.http = new Http(vertx, settings.timing().httpTimeoutMillis());
    this.who = identity.logName();
    this.workers =
        Executors.newCachedThreadPool(
            runnable -> {
              Thread thread = new Thread(runnable, who + "-worker");
              thread.setDaemon(true);
              return thread;
            });
    this.session = new RunnerSession(identity, settings, docker, registry, new SessionOps());
  }

  /**
   * Register if needed, then run until something ends the process; the answer is its exit code.
   * Blocks the calling thread — the application's main thread, which has nothing else to do.
   */
  public int run() {
    try {
      capabilities = capabilities();
      ClientCredentials client;
      try {
        client =
            new Registration(identity, http, settings.timing().httpTimeoutMillis())
                .ensure(settings, capabilities);
        this.client = client;
      } catch (Registration.Failed failed) {
        LOG.errorf("%s cannot start: %s. Exiting.", who, failed.getMessage());
        return failed.exitCode();
      }
      Bearer minted = new Bearer(http, client, System::currentTimeMillis);
      bearer = minted;
      // The log export needs the bearer, so it begins here; what was logged before waited for it.
      telemetry.start(minted::token);
      socket =
          new ControlSocket<>(
              vertx, who, client.socketUrl(), minted::token, settings.timing(), codec, this);
      socket.start();
      return exit.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return RunnerExit.OK;
    } catch (Exception e) {
      LOG.error(who + " ended abnormally", e);
      return RunnerExit.STATE_UNUSABLE;
    } finally {
      synchronized (stateDirLock) {
        stopped = true;
      }
      ControlSocket<D> s = socket;
      if (s != null) {
        s.stop();
      }
      quiesce();
      telemetry.stop(TELEMETRY_LAST_WORDS_MILLIS);
      http.close();
    }
  }

  /**
   * Stop every thread this runtime started and wait for them, so {@link #run} returns — and a
   * suite deletes the state directory — only once nothing of this runner is still at work on the
   * node. Nothing is interrupted at first: an interrupted docker call returns at once while the
   * docker process it forked runs on, unwatched, still writing wherever it writes. So the work in
   * flight is let finish, up to one docker deadline — no single docker call runs longer — and only
   * a thread still busy past that is interrupted, as the last resort it is.
   */
  private void quiesce() {
    lifecycle.shutdown();
    workers.shutdown();
    long deadline =
        System.nanoTime() + TimeUnit.SECONDS.toNanos(settings.dockerTimeoutSeconds());
    try {
      boolean done =
          lifecycle.awaitTermination(deadline - System.nanoTime())
              && workers.awaitTermination(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
      if (done) {
        return;
      }
      LOG.warnf(
          "%s's work was still running %ds after it stopped; interrupting it",
          who, settings.dockerTimeoutSeconds());
      lifecycle.shutdownNow();
      workers.shutdownNow();
      long last = TimeUnit.MILLISECONDS.toNanos(INTERRUPTED_THREADS_MILLIS);
      if (!lifecycle.awaitTermination(last)
          || !workers.awaitTermination(last, TimeUnit.NANOSECONDS)) {
        LOG.warnf("%s exits with work still running", who);
      }
    } catch (InterruptedException e) {
      lifecycle.shutdownNow();
      workers.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  /** End the run loop with {@code code}; a suite's way out, and an orderly shutdown's. */
  public void stop(int code) {
    exit.complete(code);
  }

  /** The handle the domain is given — the same one, every session. */
  public RunnerSession session() {
    return session;
  }

  /**
   * The domain's capabilities for this host, with {@code <labelRoot>.self-update=false} added when
   * self-update is off — in the registration and in every {@code Hello}, so the host can tell a
   * deployer-managed runner from one that will become the version it is told to. A self-updating
   * runner adds no label at all.
   */
  RunnerCapabilities capabilities() {
    RunnerCapabilities own = domain.capabilities(host);
    if (settings.selfUpdate()) {
      return own;
    }
    Map<String, String> labels = new LinkedHashMap<>(own.labels());
    labels.put(identity.selfUpdateLabel(), "false");
    return new RunnerCapabilities(own.docker(), own.arch(), own.os(), labels, own.extensions());
  }

  @Override
  public void onConnected() {
    // The domain's session start is docker work, so it runs off the loop and the Hello waits for
    // it: the host must never hand work into a session whose leftovers are still being reconciled
    // under the same labels. The Hello claims what was carried.
    work(
        () -> {
          List<String> carried = ledger.carried();
          try {
            domain.onSessionStart(session, carried);
          } catch (RuntimeException e) {
            LOG.warnf("%s's session start failed: %s", who, e.getMessage());
          }
          send(
              new Hello(
                  identity.version(),
                  identity.capabilityVersion(),
                  settings.slots(),
                  capabilities,
                  carried,
                  domain.helloExtensions()));
        });
  }

  @Override
  public void onMessage(RunnerMessage message) {
    if (message instanceof LifecycleMessage lifecycleMessage) {
      onLifecycle(lifecycleMessage);
    } else if (domainType.isInstance(message)) {
      D frame = domainType.cast(message);
      work(
          () -> {
            try {
              domain.onMessage(frame, session);
            } catch (RuntimeException e) {
              LOG.warnf(
                  "%s could not handle a %s: %s",
                  who, message.getClass().getSimpleName(), e.getMessage());
            }
          });
    } else {
      LOG.debugf("%s ignored a %s from the host", who, message.getClass().getSimpleName());
    }
  }

  private void onLifecycle(LifecycleMessage message) {
    switch (message) {
      case Ack ack -> onAck(ack);
      case Backlog backlog -> reserveIf(ledger.onBacklog(backlog.queued()));
      case Nothing _ -> reserveIf(ledger.onNothing());
      case Upgrade upgrade when !settings.selfUpdate() ->
          // Deployer-managed: the deployer replaces this container, so there is nothing to drain
          // for and no successor to start. The frame has no refusal to answer with; the capability
          // label this runner advertises is how the host knows.
          LOG.infof(
              "%s ignored an upgrade to %s: it is deployer-managed (%s=false), and its"
                  + " deployer, not the runner, replaces it",
              who, upgrade.version(), identity.env(RunnerSettings.SELF_UPDATE));
      case Upgrade upgrade -> {
        // At once, on the loop: not one more Reserve goes out after this frame.
        ledger.drain();
        lifecycle.upgrade(upgrade);
      }
      case Retire retire when retire.kind() == Retire.Kind.DELETED ->
          decommission("the host deleted this runner (" + retire.reason() + ")");
      case Retire retire -> {
        if (lifecycle.retire(retire)) {
          work(this::leave);
        }
      }
      case Quarantined quarantine -> {
        quarantined = true;
        LOG.warnf(
            "%s is quarantined since %s: %s; it takes no new work until reinstated",
            who, quarantine.since(), quarantine.reason());
      }
      case Reinstated reinstated -> {
        quarantined = false;
        LOG.infof("%s reinstated by %s", who, reinstated.by());
      }
      default ->
          // Hello, Heartbeat and Reserve are runner→host; a host echoing one is not a conversation
          // this version has.
          LOG.debugf("%s ignored a %s from the host", who, message.getClass().getSimpleName());
    }
  }

  private void onAck(Ack ack) {
    if (ack.capabilityVersion() != identity.capabilityVersion()) {
      LOG.errorf(
          "%s speaks capability version %d, the host answered %d — exiting rather than guessing.",
          who, identity.capabilityVersion(), ack.capabilityVersion());
      exit.complete(RunnerExit.CAPABILITY_MISMATCH);
      return;
    }
    LOG.infof("%s connected slots=%d", who, ack.slots());
    // Before the slots are counted: a carried item the host did not adopt holds no slot any more.
    // And drop-then-reserve: the domain has removed it before this session can reserve anything. A
    // host that did not keep an item has usually put it back in its queue, so the very next take
    // can be the same item — and a removal still running then would find the new attempt's
    // container under the same work label and remove that too. The slots stay at the suspended
    // zero until the domain is done, so no Backlog in between reserves either.
    List<String> dropped = ledger.settleCarried(ack.adopted());
    if (!dropped.isEmpty()) {
      carriedDropped =
          CompletableFuture.runAsync(
              () -> {
                LOG.infof("%s's host did not keep carried %s; dropping them", who, dropped);
                try {
                  domain.onDropped(dropped, session);
                } catch (RuntimeException e) {
                  LOG.warnf("%s could not drop %s: %s", who, dropped, e.getMessage());
                }
              },
              workers);
    }
    // The newest Ack's number, whichever callback applies it: a re-sent Ack during the drop waits
    // for it too.
    ackedSlots = ack.slots();
    // Admitted: healthy from now, rather than one heartbeat interval from now.
    beat();
    // The first Ack is this version proven on the wire, so whatever this runner ran before it is
    // done with: once per process, and off the loop, because it can wait a minute.
    boolean first = acked.compareAndSet(false, true);
    carriedDropped.whenComplete(
        (done, failure) -> {
          reserveIf(ledger.onAck(ackedSlots));
          work(
              () -> {
                try {
                  domain.onAck(ack, session);
                } catch (RuntimeException e) {
                  LOG.warnf("%s's domain could not handle the Ack: %s", who, e.getMessage());
                }
                if (first && !ending.get()) {
                  lifecycle.firstAck();
                }
              });
        });
  }

  /**
   * The one orderly exit: the node half first (no restart for this container), then the socket
   * closed for good — no redial on the way out, because the containers under this runner's labels
   * are the successor's now — then exit 0.
   */
  private void leave() {
    lifecycle.leave();
    ControlSocket<D> s = socket;
    if (s != null) {
      s.stop();
    }
    LOG.infof("%s exits: retired", who);
    exit.complete(RunnerExit.OK);
  }

  @Override
  public void onHeartbeatSent() {
    beat();
  }

  /**
   * Touch the heartbeat file {@link Heartbeat} reads. Never fatal: a runner that cannot write its
   * state directory says so on its next registration or rollover, not here, every 10 s.
   */
  private void beat() {
    synchronized (stateDirLock) {
      if (stopped) {
        // A heartbeat that was in flight when the run ended: the runner has stopped.
        return;
      }
      try {
        Heartbeat.touch(settings.stateDir());
      } catch (java.io.IOException | RuntimeException e) {
        LOG.debugf("%s could not touch its heartbeat file: %s", who, e.getMessage());
      }
    }
  }

  @Override
  public void onDeleted(String why) {
    decommission(why);
  }

  /**
   * The token endpoint refused this runner's client for a whole streak: the credential is revoked,
   * which is not a deletion. On the loop, at once: not one more {@code Reserve}, no successor, no
   * housekeeping — the socket has already stopped itself. Then, off the loop, the orderly exit a
   * superseded {@code Retire} takes: the restart policy goes, so the exit is the container's last,
   * and the process exits 0. Nothing is removed — not the domain's work, not a container, not a
   * volume, not the client — because a revoked credential is not the platform's word that any of it
   * may go; an explicit retire is.
   */
  @Override
  public void onCredentialsRevoked(String why) {
    if (!ending.compareAndSet(false, true)) {
      return;
    }
    LOG.warnf(
        "%s's credential was revoked (%s): it stops and removes nothing, no container and no"
            + " volume; purging this runner takes an explicit retire (Retire DELETED)",
        who, why);
    ledger.drain();
    // No successor and no housekeeping pass may start from here on.
    lifecycle.halt();
    ControlSocket<D> s = socket;
    if (s != null) {
      s.stop();
    }
    work(
        () -> {
          try {
            lifecycle.leave();
          } catch (RuntimeException e) {
            LOG.warnf("%s could not take its restart policy away: %s", who, e.getMessage());
          } finally {
            LOG.infof("%s exits: credential revoked", who);
            exit.complete(RunnerExit.OK);
          }
        });
  }

  /**
   * This runner was deleted. On the loop, at once: not one more {@code Reserve}, and no redial — the
   * host is about to close the socket, and a dial could only mint on a revoked client. Then, off the
   * loop: the domain stops and removes its own work, the node half runs, and the process exits 0.
   */
  private void decommission(String why) {
    if (!ending.compareAndSet(false, true)) {
      return;
    }
    LOG.warnf("%s decommissions itself: %s", who, why);
    ledger.drain();
    // No successor and no housekeeping pass may start from here on.
    lifecycle.halt();
    ControlSocket<D> s = socket;
    if (s != null) {
      s.stop();
    }
    work(
        () -> {
          try {
            domain.onDecommission(session);
          } catch (RuntimeException e) {
            LOG.warnf("%s's domain could not stop its work: %s", who, e.getMessage());
          }
          try {
            lifecycle.decommission(session);
          } catch (RuntimeException e) {
            LOG.warnf("%s's decommission failed part way: %s", who, e.getMessage());
          } finally {
            LOG.infof("%s exits: decommissioned", who);
            exit.complete(RunnerExit.OK);
          }
        });
  }

  @Override
  public void onClosed() {
    // The held items are carried to the next session, whose Hello claims them and whose first Ack
    // says which the host kept.
    ledger.suspend();
    lifecycle.poke();
    if (!ending.get()) {
      work(
          () -> {
            try {
              domain.onSessionLost();
            } catch (RuntimeException e) {
              LOG.debugf("%s's domain failed on the lost session: %s", who, e.getMessage());
            }
          });
    }
  }

  /**
   * Submit to {@link #workers}, dropping the job rather than throwing when the pool is already
   * shut down: a socket close or a late frame can land on the event loop after {@link #run}'s
   * {@code finally} has stopped it, and a stopping runner has nothing left to do with it.
   */
  private void work(Runnable job) {
    try {
      workers.execute(job);
    } catch (RejectedExecutionException e) {
      LOG.debugf("%s dropped work after stopping", who);
    }
  }

  private void reserveIf(boolean reserve) {
    if (reserve) {
      send(new Reserve());
    }
  }

  private CompletionStage<Void> send(RunnerMessage message) {
    ControlSocket<D> s = socket;
    if (s == null) {
      return CompletableFuture.failedFuture(
          new IllegalStateException(who + " control socket is not open"));
    }
    Future<Void> sent =
        s.send(message)
            .onFailure(
                t ->
                    LOG.warnf(
                        "%s could not send %s: %s",
                        who, message.getClass().getSimpleName(), t.getMessage()));
    return sent.toCompletionStage();
  }

  /** For the suite: the slot state. */
  SlotLedger ledger() {
    return ledger;
  }

  /** For the suite: whether the host's last word on quarantine was {@link Quarantined}. */
  boolean quarantined() {
    return quarantined;
  }

  /** The runtime's side of {@link RunnerSession}. */
  private final class SessionOps implements RunnerSession.Ops {

    @Override
    public CompletionStage<Void> send(RunnerMessage message) {
      return RunnerRuntime.this.send(message);
    }

    @Override
    public void taken(String workId) {
      reserveIf(ledger.taken(workId));
    }

    @Override
    public void released(String workId) {
      reserveIf(ledger.released(workId));
      lifecycle.poke();
    }

    @Override
    public List<String> held() {
      return ledger.heldIds();
    }

    @Override
    public boolean quarantined() {
      return quarantined;
    }

    @Override
    public CompletionStage<String> bearer() {
      Bearer b = bearer;
      if (b == null) {
        return CompletableFuture.failedFuture(
            new IllegalStateException(who + " is not registered yet"));
      }
      return b.token().toCompletionStage();
    }
  }
}
