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
   * <p><b>One snapshot, decided against whole.</b> Every own container and the work item it
   * carries come from the very same {@code docker ps} answer — id and {@code
   * --filter}-independent work-item label read off one line — so a container a concurrent launch
   * creates for a kept work item after that snapshot was taken is simply absent from it, never a
   * removal candidate. The earlier shape listed once per kept work id and once more, separately,
   * for the owner filter alone: a container for a kept work item created in the gap between those
   * two listings carried a label the first listing could not yet have matched, and the second,
   * broader listing then read it as outside the keep set — this runner killing its own live work
   * mid-launch (qits-1233).
   *
   * <p>A listing that fails keeps the whole pass from removing anything: a leftover waits for the
   * next reconcile, where a wrong guess would kill live work. Volumes are never touched — see the
   * class javadoc.
   */
  public int reconcile(Set<String> keepWorkIds) {
    Set<String> keep = new HashSet<>();
    for (String workId : keepWorkIds) {
      try {
        keep.add(LifecycleArgv.requireWorkId(workId));
      } catch (IllegalArgumentException notAnId) {
        LOG.warnf("%s ignored a work item it cannot name: %s", who, notAnId.getMessage());
      }
    }
    List<Snapshot> own;
    try {
      own = snapshot();
    } catch (ListingFailed failed) {
      // docker not answering is a restarted host's ordinary state; the next session looks again.
      LOG.warn(failed.getMessage());
      return 0;
    }
    int removed = 0;
    int kept = 0;
    for (Snapshot container : own) {
      if (keep.contains(container.workId())) {
        kept++;
        continue;
      }
      RunnerDocker.Result gone;
      try {
        lastWords(container.id());
        gone = docker.run(LifecycleArgv.rm(docker.binary(), container.id()));
      } catch (IllegalArgumentException notAnId) {
        LOG.warnf("%s ignored a line docker ps answered: %s", who, notAnId.getMessage());
        continue;
      }
      if (gone.ok()) {
        removed++;
      } else {
        LOG.warnf("%s could not remove its container %s: %s", who, container.id(), gone.detail());
      }
    }
    if (removed > 0) {
      LOG.infof("%s removed %d container(s) outside its keep set", who, removed);
    }
    if (kept > 0) {
      LOG.infof("%s kept %d container(s) of %d work item(s)", who, kept, keepWorkIds.size());
    }
    return removed;
  }

  /** One line of {@link #snapshot}: a container's id and its work-item label's value. */
  private record Snapshot(String id, String workId) {}

  /**
   * Every own container, id and work-item label together, in the one listing {@link #reconcile}
   * decides against — {@code --format}, not a second {@code --filter} pass, so nothing created
   * after this call can ever be read back by it. {@code workId} is {@code ""} for a container
   * that carries no work-item label at all.
   *
   * @throws ListingFailed when docker did not answer
   */
  private List<Snapshot> snapshot() {
    List<String> argv =
        List.of(
            docker.binary(),
            "ps",
            "-a",
            "--filter",
            ownerFilter(),
            "--format",
            "{{.ID}} {{.Label \"" + identity.workLabel() + "\"}}");
    RunnerDocker.Result listed = docker.run(argv);
    if (!listed.ok()) {
      throw new ListingFailed(
          who + " could not list runner " + runnerId + "'s containers: " + listed.detail());
    }
    List<Snapshot> rows = new ArrayList<>();
    for (String line : listed.stdout().split("\n", -1)) {
      if (line.isBlank()) {
        continue;
      }
      String trimmed = line.strip();
      int space = trimmed.indexOf(' ');
      rows.add(
          space < 0
              ? new Snapshot(trimmed, "")
              : new Snapshot(trimmed.substring(0, space), trimmed.substring(space + 1).strip()));
    }
    return rows;
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
