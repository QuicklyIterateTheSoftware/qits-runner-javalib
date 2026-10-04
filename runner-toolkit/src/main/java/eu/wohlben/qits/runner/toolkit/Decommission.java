package eu.wohlben.qits.runner.toolkit;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * A deleted runner removing itself from its node: its container, every other container of its id,
 * its workloads, its labelled volumes, the domain's teardown items and its state volume — and
 * nothing else. The difference from a {@link SelfUpdate} retirement is that nothing takes over:
 * there is no successor to remove the exited predecessor, and the state volume holds a client the
 * platform has already revoked, so it is dead weight rather than something to hand on.
 *
 * <pre>
 *   the runner process ({@link #leave}), after the domain's onDecommission:
 *     client.json deleted          → a restart of anything under this volume cannot dial as it
 *     docker update --restart=no   → the exit that follows is its last
 *     docker run -d the helper     → the runner's own image, in helper mode, with the docker socket
 *     exit 0
 *
 *   the helper ({@link DecommissionHelper#finish}):
 *     every container labelled &lt;labelRoot&gt;.process=&lt;id&gt; → wait for it to exit, docker rm -f
 *     every container labelled &lt;labelRoot&gt;=&lt;id&gt;         → docker rm -f (the workloads)
 *     every volume labelled &lt;labelRoot&gt;=&lt;id&gt;            → docker volume rm
 *     each teardown item, refused unless it starts with &lt;namePrefix&gt;-
 *     docker volume rm &lt;namePrefix&gt;-state-&lt;id8&gt;
 *     docker rm -f itself
 * </pre>
 *
 * <p><b>Why a helper at all.</b> A container can take its own restart policy away, but it cannot
 * remove the volume it has mounted: docker refuses to remove a volume a container still uses, and
 * the container is the one thing that cannot outlive its own removal. So the last steps run from a
 * second container — the same image, already on the host, so there is nothing to pull.
 *
 * <p><b>No fixed node-wide name exists here.</b> qits-ci-runner-daemon's helper removed "the
 * node's builder" because a node ran one runner; a node now runs several, so the helper reaches
 * only what carries this runner's id label or this runner's name prefix.
 *
 * <p><b>What survives a failure.</b> The restart policy goes before anything else can fail, so the
 * worst case is an exited container and a volume a person removes by hand — never a runner that
 * restarts and redials forever. A helper that fails stays, exited, with its log.
 */
public final class Decommission {

  private static final Logger LOG = Logger.getLogger(Decommission.class);

  /** How long the helper waits for a runner container to exit, how often it looks, and retries. */
  public record Settings(long exitWaitMillis, long pollMillis, int volumeAttempts) {

    public static Settings defaults() {
      return new Settings(60_000, 1_000, 10);
    }
  }

  private final RunnerIdentity identity;
  private final RunnerDocker docker;
  private final String runnerId;
  private final Optional<String> self;
  private final Path stateDir;
  private final Supplier<List<TeardownItem>> teardown;
  private final String who;

  /** @param teardown the domain's own items ({@link RunnerDomain#teardown()}), read at the time */
  public Decommission(
      RunnerIdentity identity,
      RunnerDocker docker,
      String runnerId,
      Optional<String> self,
      Path stateDir,
      Supplier<List<TeardownItem>> teardown) {
    this.identity = identity;
    this.docker = docker;
    this.runnerId = runnerId;
    this.self = self;
    this.stateDir = stateDir;
    this.teardown = teardown;
    this.who = identity.logName();
  }

  /**
   * The runner process's half: forget the client, stay down, start the helper. Never throws; every
   * failure is logged and the process exits regardless — see the class javadoc for what is left.
   */
  public void leave() {
    try {
      Files.deleteIfExists(stateDir.resolve(Registration.CLIENT_FILE));
    } catch (IOException | RuntimeException e) {
      LOG.warnf("%s could not delete its revoked client: %s", who, e.getMessage());
    }
    if (self.isEmpty()) {
      LOG.warnf(
          "%s is not running in a docker container, so there is no container to remove; stop"
              + " whatever supervises this process",
          who);
      return;
    }
    String selfId = self.get();
    RunnerDocker.Result stayDown = docker.run(LifecycleArgv.noRestart(docker.binary(), selfId));
    if (!stayDown.ok()) {
      LOG.warnf("%s could not take its own restart policy away: %s", who, stayDown.detail());
    }
    RunnerDocker.Result inspected = docker.run(LifecycleArgv.inspectSelf(docker.binary(), selfId));
    if (!inspected.ok() || inspected.stdoutTruncated()) {
      LOG.warnf(
          "%s could not inspect its own container, so it cannot start the helper that removes it:"
              + " %s; remove %s by hand",
          who, inspected.detail(), selfId);
      return;
    }
    String image;
    String volume;
    try {
      image = SelfSpec.imageIdOf(inspected.stdout());
      volume = stateVolume(inspected.stdout(), stateDir).orElse(null);
    } catch (RuntimeException e) {
      LOG.warnf("%s could not read its own container: %s; remove it by hand", who, e.getMessage());
      return;
    }
    if (volume == null) {
      LOG.infof("%s's state %s is no named volume; it is left where it is", who, stateDir);
    }
    List<TeardownItem> items;
    try {
      items = teardown.get();
    } catch (RuntimeException e) {
      LOG.warnf("%s's domain could not name its teardown items: %s", who, e.getMessage());
      items = List.of();
    }
    RunnerDocker.Result started;
    try {
      started =
          docker.run(
              LifecycleArgv.runDecommissioner(
                  identity, docker.binary(), runnerId, image, volume, items));
    } catch (IllegalArgumentException refused) {
      LOG.warnf("%s cannot start its decommission helper: %s", who, refused.getMessage());
      return;
    }
    if (started.ok()) {
      LOG.infof(
          "%s started %s to remove its container%s",
          who,
          LifecycleArgv.helperName(identity, runnerId),
          volume == null ? "" : " and the volume " + volume);
    } else {
      LOG.warnf(
          "%s could not start its decommission helper (%s); its container stays, exited, and"
              + " never restarts",
          who, started.detail());
    }
  }

  /**
   * The named volume mounted at {@code stateDir} in the container {@code docker inspect} answered
   * for — the install contract's {@code <namePrefix>-state-<id8>}, read rather than composed. The
   * top-level {@code Mounts} first, which lists every mount however it was declared; {@code
   * HostConfig.Binds} and {@code HostConfig.Mounts} after, for a document that lacks it. A bind of
   * a host path is not a volume and answers empty: a host directory is the operator's, not the
   * runner's to delete. A volume of another prefix is refused by the helper, for the same reason.
   */
  static Optional<String> stateVolume(String containerJson, Path stateDir) {
    JsonArray array = new JsonArray(containerJson == null ? "[]" : containerJson.strip());
    if (array.isEmpty() || !(array.getValue(0) instanceof JsonObject container)) {
      return Optional.empty();
    }
    String target = stateDir.toString();
    JsonArray mounts = container.getJsonArray("Mounts");
    if (mounts != null) {
      for (Object item : mounts) {
        if (item instanceof JsonObject mount && target.equals(mount.getString("Destination"))) {
          return "volume".equals(mount.getString("Type"))
              ? Optional.ofNullable(mount.getString("Name")).filter(n -> !n.isBlank())
              : Optional.empty();
        }
      }
    }
    JsonObject hostConfig = container.getJsonObject("HostConfig");
    if (hostConfig == null) {
      return Optional.empty();
    }
    JsonArray binds = hostConfig.getJsonArray("Binds");
    if (binds != null) {
      for (Object item : binds) {
        if (item instanceof String bind) {
          String[] parts = bind.split(":");
          if (parts.length >= 2 && parts[1].equals(target)) {
            return parts[0].startsWith("/") ? Optional.empty() : Optional.of(parts[0]);
          }
        }
      }
    }
    JsonArray declared = hostConfig.getJsonArray("Mounts");
    if (declared != null) {
      for (Object item : declared) {
        if (item instanceof JsonObject mount && target.equals(mount.getString("Target"))) {
          return "volume".equals(mount.getString("Type"))
              ? Optional.ofNullable(mount.getString("Source")).filter(n -> !n.isBlank())
              : Optional.empty();
        }
      }
    }
    return Optional.empty();
  }
}
