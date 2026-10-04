package eu.wohlben.qits.runner.toolkit;

/**
 * Images of the runner's own repository, on any registry host, that are not the running image and
 * that no container uses — removed with {@code image rm}, never {@code -f}. See {@link
 * RunnerImages}.
 */
public final class RunnerImagesPass implements HousekeepingPass {

  private final RunnerImages images;

  public RunnerImagesPass(RunnerImages images) {
    this.images = images;
  }

  @Override
  public String name() {
    return "runner-images";
  }

  @Override
  public void run(HousekeepingContext context) {
    images.sweepLeftovers();
  }
}
