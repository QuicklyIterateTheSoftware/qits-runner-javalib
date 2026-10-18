package eu.wohlben.qits.runner.toolkit.contracts.usage;

import eu.wohlben.qits.runner.toolkit.RunnerLauncher;

/** A daemon's entry as qits-workspaces-runner-daemon's Main is: the launcher wires every call. */
public final class UsesLauncher {
  private UsesLauncher() {}

  public static String entry() {
    return RunnerLauncher.DECOMMISSION;
  }

  public static Class<?> launcher() {
    return RunnerLauncher.class;
  }
}
