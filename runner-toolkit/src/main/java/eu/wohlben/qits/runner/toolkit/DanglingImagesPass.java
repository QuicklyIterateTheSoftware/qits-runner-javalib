package eu.wohlben.qits.runner.toolkit;

/**
 * {@code docker image prune -f}: dangling images only — the untagged layers a pull or a rollover
 * left behind. Never {@code -a}, which would delete every image no container uses on a node that
 * holds things this runner never pulled. One of the two node-wide commands a runner runs, and safe
 * beside a neighbour because a dangling image is nobody's by name.
 */
public final class DanglingImagesPass implements HousekeepingPass {

  private final RunnerDocker docker;

  public DanglingImagesPass(RunnerDocker docker) {
    this.docker = docker;
  }

  @Override
  public String name() {
    return "dangling-images";
  }

  @Override
  public void run(HousekeepingContext context) {
    RunnerDocker.Result pruned = docker.run(LifecycleArgv.imagePruneDangling(docker.binary()));
    if (!pruned.ok()) {
      context
          .log()
          .warnf(
              "%s could not prune dangling images: %s",
              context.identity().logName(), pruned.detail());
    }
  }
}
