package eu.wohlben.qits.runner.harness;

import eu.wohlben.qits.runner.protocol.RunnerBinaryVersion;
import eu.wohlben.qits.runner.protocol.RunnerVocabulary;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;

/**
 * Who the harness runner is: the one value an embedder supplies besides its domain, spelled the way
 * a real runner's protocol jar spells its own. Every label, container, volume and variable name the
 * harness touches is derived from these roots.
 *
 * <p>The version is read the way a real runner's is — {@link RunnerBinaryVersion} over a {@code
 * ${project.version}}-filtered resource — so the harness proves that path too, rather than a
 * constant standing in for it.
 */
public final class HarnessIdentity {

  private HarnessIdentity() {}

  /** The harness's own version, filtered in at build time; its image tag, were it shipped. */
  public static final String VERSION =
      RunnerBinaryVersion.read(HarnessIdentity.class, "harness-binary.properties");

  /** The harness runner's identity. */
  public static final RunnerIdentity IDENTITY =
      new RunnerIdentity(
          "harness",
          "QITS_HARNESS_RUNNER_",
          "qits.harness.runner",
          "qits-harness-runner",
          "qits/qits-harness-runner",
          "/var/lib/qits-harness-runner",
          "/harness/api/runners/{id}/register",
          "qits-harness-runner",
          VERSION,
          1,
          new RunnerVocabulary("heldItems", "adoptedItems"),
          "item");
}
