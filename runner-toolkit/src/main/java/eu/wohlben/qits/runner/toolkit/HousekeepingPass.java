package eu.wohlben.qits.runner.toolkit;

/**
 * One piece of a runner's garbage collection on its node, run by {@link Housekeeping} after the
 * first {@code Ack} and then every interval. The toolkit's own are {@link RunnerImagesPass} and
 * {@link DanglingImagesPass}; a domain adds its own through {@link RunnerDomain#housekeeping()} —
 * CI's step-image record is one, in qits-772.
 *
 * <p><b>What a pass may do is bounded by the node it shares.</b> It may remove what carries this
 * runner's labels or names, and may lean on qits-containers-driver's pure keep/remove decisions fed
 * with its own protected set. It never runs {@code docker system prune}, {@code image prune -a},
 * {@code volume prune} or {@code container prune}: the node holds other runners' and other people's
 * things. Image removal always relies on docker refusing an image a container still uses — that
 * refusal is what keeps a co-located runner's stopped containers safe.
 *
 * <p>A pass that throws is logged and the next one runs; it never cancels the schedule.
 */
public interface HousekeepingPass {

  /** What the log calls it. */
  String name();

  /** One pass, on the housekeeping thread, under the docker deadline of every call it makes. */
  void run(HousekeepingContext context);
}
