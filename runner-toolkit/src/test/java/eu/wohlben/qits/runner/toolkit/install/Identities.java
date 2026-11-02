package eu.wohlben.qits.runner.toolkit.install;

import eu.wohlben.qits.runner.protocol.RunnerVocabulary;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import java.util.List;

/**
 * Three test-only identities the install script is rendered for: a harness-shaped one mirroring
 * runner-harness's {@code HarnessIdentity} (qits-827), a workspaces-shaped one with qits-624's
 * spellings, and a system-shaped one with qits-system-runner-daemon's roots — the one kind that
 * {@linkplain RunnerIdentity#readsHostCgroup() reads the host's cgroups} (qits-1818). None is an
 * embedder's own; the version is the fixture's, so the image it names is
 * {@code registry.qits.example.org/qits/qits-harness-runner:0.0.0-fixture}.
 */
final class Identities {

  private Identities() {}

  static final String VERSION = "0.0.0-fixture";

  static final String REGISTRY = "registry.qits.example.org";

  static final RunnerIdentity HARNESS =
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

  static final InstallScript.InstallPage HARNESS_PAGE =
      new InstallScript.InstallPage(
          "the Harness UI's Runners page", List.of("BUILDKIT_HTTP_REGISTRIES"));

  static final RunnerIdentity WORKSPACES =
      new RunnerIdentity(
          "workspaces",
          "QITS_WORKSPACES_RUNNER_",
          "qits.workspaces.runner",
          "qits-workspaces-runner",
          "qits/qits-workspaces-runner",
          "/var/lib/qits-workspaces-runner",
          "/workspaces/api/runners/{id}/register",
          "qits-workspaces-runner",
          VERSION,
          1,
          new RunnerVocabulary("heldWorkspaces", "adoptedWorkspaces"),
          "workspace");

  static final InstallScript.InstallPage WORKSPACES_PAGE =
      new InstallScript.InstallPage("the Workspaces UI's Runners page", List.of());

  static final RunnerIdentity SYSTEM =
      new RunnerIdentity(
          "targets",
          "QITS_SYSTEM_RUNNER_",
          "qits.system.runner",
          "qits-system-runner",
          "qits/qits-system-runner",
          "/var/lib/qits-system-runner",
          "/system/api/targets/{id}/register",
          "qits-system-runner",
          VERSION,
          1,
          new RunnerVocabulary("heldLeases", "adoptedLeases"),
          "lease");

  static final InstallScript.InstallPage SYSTEM_PAGE =
      new InstallScript.InstallPage(
          "the System UI's Targets page", List.of("CREDENTIAL_VOLUME", "M2_VOLUME", "PNPM_VOLUME"));
}
