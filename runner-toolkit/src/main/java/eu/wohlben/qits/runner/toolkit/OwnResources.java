package eu.wohlben.qits.runner.toolkit;

import eu.wohlben.qits.containers.driver.ContainersDriver;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * <b>What on the node is this runner's</b>, and the one sweep the toolkit offers over it.
 * qits-ci-runner-daemon's {@code BootSweep}, with its {@code qits.ci.runner}/{@code .run} labels
 * derived from the {@link RunnerIdentity} and its carried runs generalised to a keep set the domain
 * decides.
 *
 * <ul>
 *   <li>{@link #labels} — the labels every workload container and volume the domain creates
 *       carries: {@code <labelRoot>=<runnerId>} and {@code <labelRoot>.<workLabelSuffix>=<workId>}.
 *       They go in the driver's own-namespace {@code labels} argument of {@link
 *       ContainersDriver#run} and {@link ContainersDriver#ensureVolume}, <b>never</b> in {@code
 *       ContainerSpec.extraLabels}: the work-item key sits inside {@code <labelRoot>.}, the
 *       driver's namespace for this runner, and the driver refuses an extra label there.
 *   <li>{@link #containers()} and {@link #volumes()} — listed by {@code label=<labelRoot>=<id>} and
 *       nothing else.
 *   <li>{@link #reconcile} — remove own containers whose work item is not in the keep set.
 * </ul>
 *
 * <p><b>Its own label, and only its own.</b> A runner shares its node with other runners, with
 * whatever else the person runs there and, on the platform host, with the platform itself. A
 * listing is an exact {@code label=<labelRoot>=<runnerId>} filter — never a {@code name=} filter,
 * which docker matches as a substring, and never the bare {@code label=<labelRoot>}, which would
 * reach another runner of the same kind — and a removal is of an id that listing just answered.
 *
 * <p><b>It never removes a volume.</b> A workspace's {@code /workspace} may hold unpushed work, and
 * a container is cheap to make again where a volume is not. A volume goes only by the domain's own
 * explicit {@code driver().removeVolume(name)}, or with the whole runner at decommission.
 *
 * <p><b>The listings go through the runner's process seam</b> ({@link RunnerDocker#run}), not the
 * driver's listing methods, which degrade a failed listing to empty: here "could not list" must
 * never read as "nothing is there" — not to a domain deciding what to recreate, and not to a sweep
 * deciding what to keep.
 */
public final class OwnResources {

  private static final Logger LOG = Logger.getLogger(OwnResources.class);

  /** A listing docker did not answer — never read as an empty one. */
  public static final class ListingFailed extends RuntimeException {
    ListingFailed(String message) {
      super(message);
    }
  }

  private final RunnerIdentity identity;
  private final String runnerId;
  private final RunnerDocker docker;
  private final String who;

  public OwnResources(RunnerIdentity identity, String runnerId, RunnerDocker docker) {
    this.identity = identity;
    this.runnerId = runnerId;
    this.docker = docker;
    this.who = identity.logName();
  }

  /**
   * The labels a workload of {@code workId} carries — container and volume alike. Pass them as the
   * driver's {@code labels} argument, never as {@code ContainerSpec.extraLabels}.
   */
  public Map<String, String> labels(String workId) {
    Map<String, String> labels = new LinkedHashMap<>();
    labels.put(identity.ownerLabel(), runnerId);
    labels.put(identity.workLabel(), LifecycleArgv.requireWorkId(workId));
    return labels;
  }

  /**
   * Every container carrying {@code <labelRoot>=<runnerId>}, running or not, by id.
   *
   * @throws ListingFailed when docker did not answer
   */
  public List<String> containers() {
    return list(List.of(docker.binary(), "ps", "-aq", "--filter", ownerFilter()), "containers");
  }

  /**
   * Every volume carrying {@code <labelRoot>=<runnerId>}, by name.
   *
   * @throws ListingFailed when docker did not answer
   */
  public List<String> volumes() {
    return list(
        List.of(docker.binary(), "volume", "ls", "-q", "--filter", ownerFilter()), "volumes");
  }

  /**
   * Remove every own container whose work item is not in {@code keepWorkIds}; answers how many
   * went. For CI the keep set is the carried runs; for a workspace runner the server's desired
   * list. Failures are logged, never thrown.
   *
   * <p>The candidates are listed first, the kept after (qits-1233): a container a concurrent launch
   * creates for a kept item during the pass is either in neither listing, or in the listing of the
   * kept — never a candidate the kept listing missed, which is how a runner removed a workspace it
   * had just launched.
   *
   * <p>A kept item whose containers cannot be listed keeps the whole pass from removing anything: a
   * leftover waits for the next reconcile, where a wrong guess would kill live work. Volumes are
   * never touched — see the class javadoc.
   */
  public int reconcile(Set<String> keepWorkIds) {
    List<String> own;
    try {
      own = containers();
    } catch (ListingFailed failed) {
      // docker not answering is a restarted host's ordinary state; the next session looks again.
      LOG.warn(failed.getMessage());
      return 0;
    }
    Set<String> kept = new HashSet<>();
    for (String workId : keepWorkIds) {
      List<String> argv;
      try {
        argv =
            List.of(
                docker.binary(),
                "ps",
                "-aq",
                "--filter",
                ownerFilter(),
                "--filter",
                "label=" + identity.workLabel() + "=" + LifecycleArgv.requireWorkId(workId));
      } catch (IllegalArgumentException notAnId) {
        LOG.warnf("%s ignored a work item it cannot name: %s", who, notAnId.getMessage());
        continue;
      }
      try {
        kept.addAll(list(argv, "containers of " + workId));
      } catch (ListingFailed failed) {
        LOG.warnf("%s; reconciling nothing this time", failed.getMessage());
        return 0;
      }
    }
    int removed = 0;
    for (String id : own) {
      if (kept.contains(id)) {
        continue;
      }
      RunnerDocker.Result gone;
      try {
        lastWords(id);
        gone = docker.run(LifecycleArgv.rm(docker.binary(), id));
      } catch (IllegalArgumentException notAnId) {
        LOG.warnf("%s ignored a line docker ps answered: %s", who, notAnId.getMessage());
        continue;
      }
      if (gone.ok()) {
        removed++;
      } else {
        LOG.warnf("%s could not remove its container %s: %s", who, id, gone.detail());
      }
    }
    if (removed > 0) {
      LOG.infof("%s removed %d container(s) outside its keep set", who, removed);
    }
    if (!kept.isEmpty()) {
      LOG.infof("%s kept %d container(s) of %d work item(s)", who, kept.size(), keepWorkIds.size());
    }
    return removed;
  }

  /** {@code label=<labelRoot>=<runnerId>} — the exact ownership filter, the only one used here. */
  private String ownerFilter() {
    return "label=" + identity.ownerLabel() + "=" + LifecycleArgv.requireRunnerId(runnerId);
  }

  /**
   * The container's output, into the runner's log before it goes: the work it belonged to has
   * already been given up on, and the removal destroys the only copy.
   */
  private void lastWords(String id) {
    ContainersDriver.LogTail tail = docker.driver().logsTail(id, 200, docker.deadline(), 16_384);
    if (!tail.text().isBlank()) {
      LOG.infof("%s removes %s, whose last output was:%n%s", who, id, tail.text().strip());
    }
  }

  private List<String> list(List<String> argv, String what) {
    RunnerDocker.Result listed = docker.run(argv);
    if (!listed.ok()) {
      throw new ListingFailed(
          who + " could not list runner " + runnerId + "'s " + what + ": " + listed.detail());
    }
    List<String> ids = new ArrayList<>();
    for (String line : listed.stdout().split("\\s+")) {
      if (!line.isBlank()) {
        ids.add(line.strip());
      }
    }
    return ids;
  }
}
