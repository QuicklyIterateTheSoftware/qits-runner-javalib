package eu.wohlben.qits.runner.toolkit;

import eu.wohlben.qits.runner.protocol.Retire;
import eu.wohlben.qits.runner.protocol.Upgrade;
import io.vertx.core.json.JsonArray;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.jboss.logging.Logger;

/**
 * Self-update: a runner is a container, and becoming another version is starting another container
 * and getting out of its way. There is no supervisor process — docker's restart policy supervises,
 * and the runner already holds the host's docker socket.
 *
 * <pre>
 *   Upgrade{version, image, sha256}  → drain (no more Reserve)          [RunnerRuntime]
 *                                    → pull image under a throwaway login, check its digest
 *   the domain is quiescent          → docker run the successor: own parameters, new image/name/label
 *   Retire within the watch          → docker update --restart=no self, close, exit 0
 *   no Retire within the watch       → docker rm -f the successor, stay draining, retry later
 * </pre>
 *
 * and, in the successor, after its first {@code Ack}: remove the exited predecessors, then their
 * images ({@link RunnerImages}) — a node would otherwise keep every runner version it ever ran.
 *
 * <p><b>Quiescence is the domain's question</b> ({@link RunnerDomain#quiescent()}), asked again
 * every {@link Settings#pollMillis} and whenever a slot is freed or the socket drops. CI answers
 * "no held run"; a runner whose workload containers outlive it answers "no operation in flight",
 * because a drain until it holds nothing would never finish.
 *
 * <p><b>The rollback is the watch.</b> A successor that cannot connect never says {@code Hello} in
 * the pinned version, so the host never sends {@code Retire}, and the old process removes it and
 * carries on draining. Nothing is stopped before the new one has proven itself on the wire.
 *
 * <p><b>Retries back off to every ten minutes.</b> A registry that is down, an image that is not
 * pushed yet, a docker that refuses: none of those is fixed by asking again at once.
 *
 * <p>All docker work runs on one thread of its own, so a pull that takes a minute holds up no frame.
 */
public final class SelfUpdate {

  private static final Logger LOG = Logger.getLogger(SelfUpdate.class);

  /** Timings. {@link #defaults} is production's; the suite shrinks every one. */
  public record Settings(
      long firstRetryMillis,
      long maxRetryMillis,
      long successorTimeoutMillis,
      long predecessorWaitMillis,
      long pollMillis) {

    /** 30 s doubling to 10 min; the watch as configured; a minute for a predecessor to leave. */
    public static Settings defaults(long successorTimeoutSeconds) {
      return new Settings(30_000, 600_000, successorTimeoutSeconds * 1000, 60_000, 1_000);
    }
  }

  private final RunnerIdentity identity;
  private final RunnerDocker docker;
  private final String runnerId;
  private final Optional<String> self;
  private final Settings settings;
  private final RegistryLogin login;
  private final BooleanSupplier quiescent;
  private final RunnerImages runnerImages;
  private final String who;
  private final AtomicBoolean predecessorsSwept = new AtomicBoolean();
  private final ScheduledThreadPoolExecutor executor;

  // Guarded by `this`.
  private Upgrade target;
  private boolean pulled;
  private String successor;
  private ScheduledFuture<?> watch;
  private boolean recheckDue;
  private int failures;

  public SelfUpdate(
      RunnerIdentity identity,
      RunnerDocker docker,
      String runnerId,
      Optional<String> self,
      Settings settings,
      RegistryLogin login,
      BooleanSupplier quiescent,
      RunnerImages runnerImages) {
    this.identity = identity;
    this.docker = docker;
    this.runnerId = runnerId;
    this.self = self;
    this.settings = settings;
    this.login = login;
    this.quiescent = quiescent;
    this.runnerImages = runnerImages;
    this.who = identity.logName();
    this.executor =
        new ScheduledThreadPoolExecutor(
            1,
            runnable -> {
              Thread thread = new Thread(runnable, who + "-self-update");
              thread.setDaemon(true);
              return thread;
            });
    // A shutdown drops every retry, recheck and watch not yet due.
    this.executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
  }

  /**
   * The host asked for {@code upgrade.version()}. The caller has already stopped reserving; this
   * starts the pull and, once the domain is quiescent, the successor. A repeat of the version
   * already under way — the host sends one on every reconnect — changes nothing.
   */
  public void upgrade(Upgrade upgrade) {
    try {
      LifecycleArgv.requireVersion(upgrade.version());
      LifecycleArgv.requireImage(upgrade.image());
    } catch (IllegalArgumentException refused) {
      LOG.warnf("%s cannot act on an Upgrade: %s; it stays draining", who, refused.getMessage());
      return;
    }
    if (upgrade.version().equals(identity.version())) {
      LOG.infof("%s was asked to upgrade to %s, which it already is", who, identity.version());
      return;
    }
    synchronized (this) {
      if (target != null && target.version().equals(upgrade.version())) {
        return;
      }
      if (successor != null) {
        LOG.infof(
            "%s ignored an upgrade to %s: its successor %s is taking over",
            who, upgrade.version(), successor);
        return;
      }
      target = upgrade;
      pulled = false;
      failures = 0;
    }
    LOG.infof("%s upgrade to %s requested; draining", who, upgrade.version());
    if (self.isEmpty()) {
      LOG.warnf(
          "%s is not running in a docker container, so it cannot start a successor; it stays"
              + " draining until it is replaced by hand",
          who);
      return;
    }
    poke();
  }

  /** Something that could let the self-update proceed happened — a slot freed, a socket closed. */
  public void poke() {
    try {
      executor.execute(this::attempt);
    } catch (RejectedExecutionException shutDown) {
      // On the way out; nothing left to roll over.
    }
  }

  /**
   * The host says a runner of the pinned version has taken over. Answers whether this process
   * should now leave: yes when no upgrade was ever asked for (an operator's retirement) or when
   * this process's successor is running; no when an upgrade is under way with no successor of it
   * alive — the connection the host saw is then not this runner's successor, and leaving would
   * leave the host with nothing.
   */
  public boolean retire(Retire retire) {
    synchronized (this) {
      if (target == null) {
        LOG.infof("%s retired by the host (%s)", who, retire.reason());
        return true;
      }
      if (successor != null) {
        if (watch != null) {
          watch.cancel(false);
          watch = null;
        }
        LOG.infof("%s retired by the host (%s); %s has taken over", who, retire.reason(), successor);
        return true;
      }
      LOG.warnf(
          "%s ignored a Retire (%s): its upgrade to %s has no successor running",
          who, retire.reason(), target.version());
      return false;
    }
  }

  /**
   * The exit path's docker half: take this container's restart policy away, so the exit that
   * follows is its last. A failure is logged and the exit goes ahead anyway — docker restarting
   * this container is then the one consequence, and the successor's predecessor sweep removes it.
   */
  public void leave() {
    if (self.isEmpty()) {
      return;
    }
    RunnerDocker.Result updated = docker.run(LifecycleArgv.noRestart(docker.binary(), self.get()));
    if (!updated.ok()) {
      LOG.warnf("%s could not take its own restart policy away: %s", who, updated.detail());
    }
  }

  /**
   * Once per process, after its first {@code Ack}: remove this runner's containers of any other
   * version — the predecessor that just handed over, and any older one a failed exit left behind.
   * A predecessor still running is given {@link Settings#predecessorWaitMillis} to exit on its own
   * {@code Retire} first. Never this process's own container: its version label is this process's
   * version, and it is also recognised by id.
   *
   * <p>Then each removed predecessor's image, best-effort and only once every container is gone.
   */
  public void removePredecessors() {
    if (!predecessorsSwept.compareAndSet(false, true)) {
      return;
    }
    RunnerDocker.Result listed =
        docker.run(LifecycleArgv.psProcess(identity, docker.binary(), runnerId));
    if (!listed.ok()) {
      LOG.warnf("%s could not list its predecessors: %s", who, listed.detail());
      return;
    }
    Set<String> images = new LinkedHashSet<>();
    for (String line : listed.stdout().split("\\R")) {
      String[] fields = line.strip().split("\\|", -1);
      if (fields.length < 3 || fields[0].isBlank()) {
        continue;
      }
      String id = fields[0].strip();
      String version = fields[1].strip();
      String state = fields[2].strip();
      if (version.equals(identity.version())
          || self.map(s -> SelfContainer.same(s, id)).orElse(false)) {
        continue;
      }
      try {
        if (state.equals("running") || state.equals("restarting")) {
          state = awaitExit(identity, docker, id, settings.predecessorWaitMillis(), settings.pollMillis());
        }
        RunnerDocker.Result gone = docker.run(LifecycleArgv.rm(docker.binary(), id));
        if (gone.ok()) {
          LOG.infof("%s removed its predecessor %s (%s, %s)", who, id, version, state);
          if (fields.length > 3 && !fields[3].isBlank()) {
            images.add(fields[3].strip());
          }
        } else {
          LOG.warnf("%s could not remove its predecessor %s: %s", who, id, gone.detail());
        }
      } catch (IllegalArgumentException notAnId) {
        LOG.warnf("%s ignored a line docker ps answered: %s", who, notAnId.getMessage());
      }
    }
    for (String image : images) {
      runnerImages.remove(image, "its predecessor's image");
    }
  }

  /**
   * Stop the self-update thread: no attempt, retry or watch starts after this, and an attempt in
   * flight starts no successor. Never an interrupt — an interrupted docker call returns while the
   * docker process it forked runs on, unwatched — so the call in flight finishes under its deadline
   * and {@link #awaitTermination} is how the runtime knows the thread is done.
   */
  public void shutdown() {
    executor.shutdown();
  }

  /** Wait up to {@code nanos} for the attempt in flight to finish; true when the thread is done. */
  boolean awaitTermination(long nanos) throws InterruptedException {
    return executor.awaitTermination(nanos, TimeUnit.NANOSECONDS);
  }

  /** The last resort, past the wait: interrupt the attempt, whatever it is waiting on. */
  void shutdownNow() {
    executor.shutdownNow();
  }

  /**
   * Wait up to {@code waitMillis} for a runner container to stop running, polling its state;
   * answers the last state seen ({@code gone} when docker no longer knows it). Shared with the
   * decommission helper, which waits for the runner it removes the same way.
   */
  static String awaitExit(
      RunnerIdentity identity, RunnerDocker docker, String id, long waitMillis, long pollMillis) {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis);
    String state = "running";
    while (System.nanoTime() < deadline) {
      RunnerDocker.Result answered = docker.run(LifecycleArgv.state(docker.binary(), id));
      state = answered.ok() ? answered.stdout().strip() : "gone";
      if (!state.equals("running") && !state.equals("restarting")) {
        return state;
      }
      try {
        Thread.sleep(pollMillis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return state;
      }
    }
    LOG.warnf(
        "%s container %s is still %s after %ds; removing it anyway",
        identity.logName(), id, state, waitMillis / 1000);
    return state;
  }

  private void attempt() {
    Upgrade upgrade;
    boolean havePulled;
    synchronized (this) {
      if (target == null || successor != null || self.isEmpty()) {
        return;
      }
      upgrade = target;
      havePulled = pulled;
    }
    if (!havePulled) {
      Optional<String> failure = pull(upgrade);
      if (failure.isPresent()) {
        retry(upgrade, failure.get());
        return;
      }
      synchronized (this) {
        pulled = true;
      }
      LOG.infof("%s pulled %s", who, upgrade.image());
    }
    if (executor.isShutdown()) {
      // Halted, or the process is ending, while the pull ran: no successor from here on.
      return;
    }
    if (!quiescent()) {
      // A freed slot and a closed socket both poke; the poll is for a domain whose quiescence
      // changes without either — a workspace runner's last in-flight stop finishing.
      LOG.debugf("%s waits for its domain to be quiescent before starting %s", who, upgrade.version());
      schedule(upgrade);
      return;
    }
    Optional<String> failure = startSuccessor(upgrade);
    if (failure.isPresent()) {
      retry(upgrade, failure.get());
    }
  }

  private boolean quiescent() {
    try {
      return quiescent.getAsBoolean();
    } catch (RuntimeException e) {
      LOG.warnf("%s's domain could not say whether it is quiescent: %s", who, e.getMessage());
      return false;
    }
  }

  /** The next look at quiescence, unless one is already due. */
  private void schedule(Upgrade upgrade) {
    synchronized (this) {
      if (target != upgrade || recheckDue) {
        return;
      }
      try {
        executor.schedule(this::recheck, settings.pollMillis(), TimeUnit.MILLISECONDS);
        recheckDue = true;
      } catch (RejectedExecutionException shutDown) {
        // On the way out.
      }
    }
  }

  private void recheck() {
    synchronized (this) {
      recheckDue = false;
    }
    attempt();
  }

  private void retry(Upgrade upgrade, String why) {
    long delay;
    synchronized (this) {
      if (target != upgrade) {
        return;
      }
      failures++;
      long doubled = settings.firstRetryMillis() << Math.min(failures - 1, 20);
      delay = Math.min(settings.maxRetryMillis(), Math.max(1, doubled));
    }
    LOG.warnf(
        "%s could not roll over to %s: %s; still draining, retrying in %ds",
        who, upgrade.version(), why, Math.max(1, delay / 1000));
    try {
      executor.schedule(this::attempt, delay, TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException shutDown) {
      // On the way out.
    }
  }

  /** Pull the image under this runner's own login, and check its digest when the host sent one. */
  Optional<String> pull(Upgrade upgrade) {
    Optional<String> failure = login.pull(upgrade.image());
    if (failure.isPresent()) {
      return failure;
    }
    return verifyDigest(upgrade);
  }

  private Optional<String> verifyDigest(Upgrade upgrade) {
    if (upgrade.sha256() == null || upgrade.sha256().isBlank()) {
      return Optional.empty();
    }
    String expected = upgrade.sha256().strip().toLowerCase(Locale.ROOT);
    if (!expected.startsWith("sha256:")) {
      expected = "sha256:" + expected;
    }
    RunnerDocker.Result digests =
        docker.run(LifecycleArgv.repoDigests(docker.binary(), upgrade.image()));
    if (!digests.ok()) {
      return Optional.of("could not read the pulled image's digest: " + digests.detail());
    }
    JsonArray known;
    try {
      known = new JsonArray(digests.stdout().strip());
    } catch (RuntimeException e) {
      return Optional.of("docker answered no digest list for " + upgrade.image());
    }
    for (Object digest : known) {
      if (digest instanceof String repoDigest && repoDigest.endsWith("@" + expected)) {
        return Optional.empty();
      }
    }
    return Optional.of(
        "the pulled image is not " + expected + " (docker knows it as " + known.encode() + ")");
  }

  private Optional<String> startSuccessor(Upgrade upgrade) {
    String selfId = self.orElseThrow();
    RunnerDocker.Result inspected = docker.run(LifecycleArgv.inspectSelf(docker.binary(), selfId));
    if (!inspected.ok() || inspected.stdoutTruncated()) {
      return Optional.of("could not inspect its own container: " + inspected.detail());
    }
    List<String> argv;
    try {
      String imageId = SelfSpec.imageIdOf(inspected.stdout());
      RunnerDocker.Result image = docker.run(LifecycleArgv.imageConfig(docker.binary(), imageId));
      if (!image.ok() || image.stdoutTruncated()) {
        return Optional.of("could not inspect its own image: " + image.detail());
      }
      SelfSpec spec = SelfSpec.parse(inspected.stdout(), image.stdout());
      argv =
          LifecycleArgv.runSuccessor(
              identity, docker.binary(), runnerId, upgrade.version(), upgrade.image(), spec);
    } catch (RuntimeException e) {
      return Optional.of("could not derive its successor's parameters: " + e.getMessage());
    }
    String name = LifecycleArgv.containerName(identity, runnerId, upgrade.version());
    if (executor.isShutdown()) {
      return Optional.empty();
    }
    synchronized (this) {
      // Recorded before the run, not after: the successor can say Hello — and the host send this
      // process Retire — before `docker run -d` has even returned here.
      successor = name;
    }
    // A leftover under the name — an earlier attempt that docker created and could not start — is
    // this runner's by the naming contract, and would make the run below a name conflict.
    docker.run(LifecycleArgv.rm(docker.binary(), name));
    RunnerDocker.Result started = docker.run(argv);
    if (!started.ok()) {
      synchronized (this) {
        successor = null;
      }
      return Optional.of("docker run of its successor failed: " + started.detail());
    }
    synchronized (this) {
      if (successor != null && watch == null) {
        try {
          watch =
              executor.schedule(
                  this::timedOut, settings.successorTimeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException shutDown) {
          // On the way out.
        }
      }
    }
    LOG.infof(
        "%s started its successor %s on %s; waiting up to %ds for it to take over",
        who, name, upgrade.image(), settings.successorTimeoutMillis() / 1000);
    return Optional.empty();
  }

  private void timedOut() {
    String name;
    Upgrade upgrade;
    synchronized (this) {
      if (successor == null) {
        return;
      }
      name = successor;
      upgrade = target;
      successor = null;
      watch = null;
    }
    RunnerDocker.Result gone = docker.run(LifecycleArgv.rm(docker.binary(), name));
    if (!gone.ok()) {
      LOG.warnf("%s could not remove its successor %s: %s", who, name, gone.detail());
    }
    retry(
        upgrade,
        "its successor "
            + name
            + " did not take over within "
            + settings.successorTimeoutMillis() / 1000
            + "s and was removed");
  }

  /** For the suite: the successor being watched, or null. */
  synchronized String successor() {
    return successor;
  }
}
