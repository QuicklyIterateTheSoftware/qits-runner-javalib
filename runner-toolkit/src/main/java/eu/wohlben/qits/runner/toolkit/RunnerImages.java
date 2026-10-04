package eu.wohlben.qits.runner.toolkit;

import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * The runner's own images, {@code <registry>/<imageRepository>:<version>}: one is pulled per
 * rollover and nothing else ever removes the one it replaced, so a node that has seen twenty
 * releases holds twenty runner images.
 *
 * <p><b>Two passes, both best-effort.</b> A successor removes its predecessor's image right after
 * removing the predecessor's container ({@link SelfUpdate#removePredecessors}); and every
 * housekeeping pass ({@link RunnerImagesPass}) removes any other image of this runner's repository
 * no container uses. A failure is a log line and the next image, never an exception.
 *
 * <p><b>Never the running image.</b> Three belts, any one of which suffices: the image id this
 * process's own container runs is skipped by id; any image some container still uses — this one,
 * another runner's on the same node, a predecessor not yet gone — is skipped by {@code docker ps
 * --filter ancestor=}; and {@code docker image rm} without {@code -f} refuses an image a container
 * uses. A process that cannot tell which image is its own removes nothing at all.
 */
public final class RunnerImages {

  private static final Logger LOG = Logger.getLogger(RunnerImages.class);

  private final RunnerIdentity identity;
  private final RunnerDocker docker;
  private final Optional<String> self;
  private final String who;

  public RunnerImages(RunnerIdentity identity, RunnerDocker docker, Optional<String> self) {
    this.identity = identity;
    this.docker = docker;
    this.self = self;
    this.who = identity.logName();
  }

  /**
   * Remove {@code ref} when it is an image no container uses and not this process's own. Answers
   * whether it was removed.
   */
  public boolean remove(String ref, String why) {
    Optional<String> own = ownImageId();
    if (own.isEmpty()) {
      return false;
    }
    return remove(ref, own.get(), why);
  }

  /**
   * One pass over every image of this runner's repository on the node: remove each that is
   * neither this process's own nor used by any container. Answers how many went.
   */
  public int sweepLeftovers() {
    Optional<String> own = ownImageId();
    if (own.isEmpty()) {
      return 0;
    }
    RunnerDocker.Result listed = docker.run(LifecycleArgv.imageList(docker.binary()));
    if (!listed.ok() || listed.stdoutTruncated()) {
      LOG.warnf("%s could not list the node's images: %s", who, listed.detail());
      return 0;
    }
    int removed = 0;
    for (String line : listed.stdout().split("\\R")) {
      String[] fields = line.strip().split("\\|", -1);
      if (fields.length < 2) {
        continue;
      }
      String ref = fields[0].strip();
      String id = fields[1].strip();
      int colon = ref.lastIndexOf(':');
      if (colon <= 0 || !isRunnerRepository(ref.substring(0, colon))) {
        continue;
      }
      if (ref.endsWith(":<none>") || sameImage(id, own.get())) {
        // Untagged is dangling — the prune's, not this sweep's; the running image is never touched.
        continue;
      }
      if (remove(ref, own.get(), "a leftover runner image")) {
        removed++;
      }
    }
    if (removed > 0) {
      LOG.infof("%s removed %d leftover runner image(s)", who, removed);
    }
    return removed;
  }

  /** {@code registry.x/<imageRepository>}, or the bare repository on Docker Hub's spelling. */
  boolean isRunnerRepository(String repository) {
    return repository.equals(identity.imageRepository())
        || repository.endsWith("/" + identity.imageRepository());
  }

  private boolean remove(String ref, String own, String why) {
    try {
      RunnerDocker.Result inspected = docker.run(LifecycleArgv.imageInspect(docker.binary(), ref));
      if (!inspected.ok()) {
        // Already gone — removed by hand, or by another runner on the node.
        return false;
      }
      String id = inspected.stdout().strip();
      if (id.isEmpty() || sameImage(id, own)) {
        return false;
      }
      RunnerDocker.Result users = docker.run(LifecycleArgv.psAncestor(docker.binary(), id));
      if (!users.ok()) {
        LOG.warnf("%s left %s: could not ask which containers use it: %s", who, ref, users.detail());
        return false;
      }
      if (!users.stdout().isBlank()) {
        LOG.infof("%s left %s: a container still uses it", who, ref);
        return false;
      }
      RunnerDocker.Result gone = docker.run(LifecycleArgv.imageRm(docker.binary(), ref));
      if (!gone.ok()) {
        LOG.warnf("%s could not remove %s (%s): %s", who, ref, why, gone.detail());
        return false;
      }
      LOG.infof("%s removed %s (%s)", who, ref, why);
      return true;
    } catch (IllegalArgumentException notAnImage) {
      LOG.warnf("%s ignored an image reference: %s", who, notAnImage.getMessage());
      return false;
    }
  }

  /** The full id of the image this process's container runs; empty when it cannot be told. */
  Optional<String> ownImageId() {
    if (self.isEmpty()) {
      return Optional.empty();
    }
    RunnerDocker.Result answered =
        docker.run(LifecycleArgv.containerImage(docker.binary(), self.get()));
    String id = answered.ok() ? answered.stdout().strip() : "";
    if (id.isEmpty()) {
      LOG.warnf(
          "%s cannot tell which image it runs (%s); it removes no runner image",
          who, answered.ok() ? "docker answered nothing" : answered.detail());
      return Optional.empty();
    }
    return Optional.of(id);
  }

  /** Two image ids, either possibly without its {@code sha256:} prefix or truncated. */
  static boolean sameImage(String a, String b) {
    String x = a.startsWith("sha256:") ? a.substring(7) : a;
    String y = b.startsWith("sha256:") ? b.substring(7) : b;
    if (x.isEmpty() || y.isEmpty()) {
      return false;
    }
    return x.startsWith(y) || y.startsWith(x);
  }
}
