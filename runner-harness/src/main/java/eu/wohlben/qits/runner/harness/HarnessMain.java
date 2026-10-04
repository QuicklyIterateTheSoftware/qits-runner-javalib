package eu.wohlben.qits.runner.harness;

import eu.wohlben.qits.runner.toolkit.RunnerLauncher;
import io.vertx.core.Vertx;

/**
 * The harness runner's process entry, exactly as small as a real embedder's: a plain {@code main}
 * handing its identity, its domain's constructor and a Vert.x to {@link RunnerLauncher}, which
 * answers {@code health}, the version probe, helper mode and the runner itself. A Quarkus embedder's
 * {@code @QuarkusMain} is the same line with the managed {@code Vertx}.
 */
public final class HarnessMain {

  private HarnessMain() {}

  public static void main(String[] args) {
    Vertx vertx = Vertx.vertx();
    int code;
    try {
      code = RunnerLauncher.main(HarnessIdentity.IDENTITY, HarnessDomain::new, vertx, args);
    } finally {
      vertx.close();
    }
    System.exit(code);
  }
}
