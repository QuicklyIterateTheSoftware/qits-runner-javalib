package eu.wohlben.qits.runner.toolkit;

import java.util.List;

/**
 * The roots of every runner kind on the estate, in one list — so that two kinds sharing one node
 * cannot be given roots where one reaches into the other. {@link RunnerIdentity}'s belts keep a
 * runner out of the node's other tenants' namespaces ({@code qits.containers}, {@code
 * qits-containers}, {@code qits-buildkitd}); this list is what keeps runners out of each other's,
 * and its test asserts that no root here is a prefix of another, by label, by name or by
 * environment.
 *
 * <p><b>A new runner kind joins this list in the same commit that introduces it</b> — qits-767's
 * {@code projects-desk} is next. A kind missing here is a kind nobody checked against its
 * neighbours.
 *
 * <p>Roots, not identities: an identity also carries a version and a vocabulary, which belong to
 * the embedder's own protocol jar and would make this toolkit depend on every one of them.
 */
public final class KnownRunnerNamespaces {

  /**
   * One runner kind's roots, as its {@link RunnerIdentity} spells them.
   *
   * @param kind {@link RunnerIdentity#kind()}
   * @param envPrefix {@link RunnerIdentity#envPrefix()}
   * @param labelRoot {@link RunnerIdentity#labelRoot()}
   * @param namePrefix {@link RunnerIdentity#namePrefix()}
   */
  public record Root(String kind, String envPrefix, String labelRoot, String namePrefix) {

    /** Whether {@code identity} has exactly these roots. */
    public boolean matches(RunnerIdentity identity) {
      return kind.equals(identity.kind())
          && envPrefix.equals(identity.envPrefix())
          && labelRoot.equals(identity.labelRoot())
          && namePrefix.equals(identity.namePrefix());
    }
  }

  /** qits-ci-runner-daemon's roots, today's spellings exactly (qits-772 moves it onto these). */
  public static final Root CI =
      new Root("ci", "QITS_CI_RUNNER_", "qits.ci.runner", "qits-ci-runner");

  /** qits-workspaces-runner-daemon's (qits-624). */
  public static final Root WORKSPACES =
      new Root(
          "workspaces",
          "QITS_WORKSPACES_RUNNER_",
          "qits.workspaces.runner",
          "qits-workspaces-runner");

  /** The harness runner this repository proves the toolkit with (qits-869). Never published. */
  public static final Root HARNESS =
      new Root("harness", "QITS_HARNESS_RUNNER_", "qits.harness.runner", "qits-harness-runner");

  /** Every kind on the estate. */
  public static final List<Root> ALL = List.of(CI, WORKSPACES, HARNESS);

  private KnownRunnerNamespaces() {}
}
