package eu.wohlben.qits.runner.toolkit.contracts.usage;

import eu.wohlben.qits.runner.toolkit.RunnerIdentity;

/** A class that names the toolkit but makes no REST call through it. */
public final class UsesNoCall {
  private UsesNoCall() {}

  public static String placeholder() {
    return RunnerIdentity.ID_PLACEHOLDER + RunnerIdentity.class.getSimpleName();
  }
}
