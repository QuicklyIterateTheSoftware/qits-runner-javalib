package eu.wohlben.qits.runner.protocol;

import java.util.regex.Pattern;

/**
 * A runner's wire names for the two lists it carries across a lost socket: {@link Hello#held()} and
 * {@link Ack#adopted()}. CI's are {@code heldRuns}/{@code adoptedRuns}; a workspace runner picks
 * its own. They are the runner's because what is held is: a run, a workspace, an item.
 *
 * <p>Both must be a plain camelCase JSON key ({@code [a-z][A-Za-z]{2,40}}) and must not be a base
 * field, which the frame they sit in would then carry twice.
 */
public record RunnerVocabulary(String heldField, String adoptedField) {

  private static final Pattern NAME = Pattern.compile("[a-z][A-Za-z]{2,40}");

  public RunnerVocabulary {
    check("heldField", heldField);
    check("adoptedField", adoptedField);
  }

  private static void check(String what, String name) {
    if (name == null || !NAME.matcher(name).matches()) {
      throw new IllegalArgumentException(what + " must match " + NAME + ": " + name);
    }
    if (RunnerWire.Field.ALL.contains(name)) {
      throw new IllegalArgumentException(what + " collides with a base field: " + name);
    }
  }
}
