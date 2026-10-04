package eu.wohlben.qits.runner.toolkit;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jboss.logging.Logger;

/**
 * The runner's disk: nothing else on a runner node collects garbage, so without this its docker
 * images grow forever. One pass once the runner is proven on the wire (the first {@code Ack}, after
 * the predecessors are gone) and then every {@link Settings#intervalMillis}: the toolkit's {@link
 * RunnerImagesPass} and {@link DanglingImagesPass}, then the domain's own passes, in order.
 *
 * <p><b>Defaults in code, no knob.</b> Every six hours is often enough that a busy node never
 * accumulates more than a few hours of dangling layers and rarely enough that a quiet one is not
 * woken for nothing.
 *
 * <p>Its own daemon thread, never the socket's or a domain's: every call is a docker call under the
 * docker deadline, and a pass that takes minutes delays nothing. It stops on decommission.
 *
 * <p><b>Stopping never interrupts a pass.</b> An interrupted docker call returns at once while the
 * docker process it forked runs on, unwatched; so {@link #shutdown} lets the call in flight finish
 * under its deadline, the pass then sees the shutdown and starts no next step, and {@link
 * #awaitTermination} is how the runtime knows the thread is done.
 */
public final class Housekeeping {

  private static final Logger LOG = Logger.getLogger(Housekeeping.class);

  /** Timings; {@link #defaults} is production's, and the suite shrinks it. */
  public record Settings(long intervalMillis) {

    /** A pass every 6 h. */
    public static Settings defaults() {
      return new Settings(TimeUnit.HOURS.toMillis(6));
    }
  }

  private final List<HousekeepingPass> passes;
  private final HousekeepingContext context;
  private final Settings settings;
  private final AtomicBoolean started = new AtomicBoolean();
  private final ScheduledThreadPoolExecutor executor;

  public Housekeeping(List<HousekeepingPass> passes, HousekeepingContext context, Settings settings) {
    this.passes = List.copyOf(passes);
    this.context = context;
    this.settings = settings;
    String thread = context.identity().logName() + "-housekeeping";
    this.executor =
        new ScheduledThreadPoolExecutor(
            1,
            runnable -> {
              Thread t = new Thread(runnable, thread);
              t.setDaemon(true);
              return t;
            });
    // A shutdown drops the schedule: no pass begins after it, only the one running finishes.
    this.executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
  }

  /** Begin: a pass now, then one every interval. Once per process; a later call changes nothing. */
  public void start() {
    if (!started.compareAndSet(false, true)) {
      return;
    }
    try {
      executor.scheduleWithFixedDelay(
          this::pass, 0, settings.intervalMillis(), TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException shutDown) {
      // The runner is on its way out; there is nothing left to keep.
    }
  }

  /** One pass. Failures are logged, never thrown: a thrown one would cancel the schedule. */
  void pass() {
    for (HousekeepingPass pass : passes) {
      if (executor.isShutdown()) {
        return;
      }
      try {
        pass.run(context);
      } catch (RuntimeException e) {
        LOG.warnf(
            "%s's %s housekeeping failed: %s",
            context.identity().logName(), pass.name(), e.getMessage());
      }
    }
  }

  /** Start no further pass or step; the docker call in flight finishes under its deadline. */
  public void shutdown() {
    executor.shutdown();
  }

  /** Wait up to {@code nanos} for the pass in flight to finish; true when the thread is done. */
  boolean awaitTermination(long nanos) throws InterruptedException {
    return executor.awaitTermination(nanos, TimeUnit.NANOSECONDS);
  }

  /** The last resort, past the wait: interrupt the pass, whatever it is waiting on. */
  void shutdownNow() {
    executor.shutdownNow();
  }
}
