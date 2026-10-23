package eu.wohlben.qits.runner.toolkit;

import eu.wohlben.qits.runner.protocol.RunnerVocabulary;

/**
 * Test-only identities shaped like the estate's real runners: a CI-shaped one with today's
 * qits-ci-runner-daemon spellings, a workspaces-shaped one with qits-624's, and a system-shaped
 * one with qits-system-runner-daemon's (qits-1524, qits-1481's dossier "Identity"). None is the
 * embedder's own (those live in each runner's protocol jar); they are what a suite of this toolkit
 * proves the derivation against.
 */
final class Shapes {

  private Shapes() {}

  static final String VERSION = "2026.1003.50846";

  static final RunnerIdentity CI =
      new RunnerIdentity(
          "ci",
          "QITS_CI_RUNNER_",
          "qits.ci.runner",
          "qits-ci-runner",
          "qits/qits-ci-runner",
          "/var/lib/qits-ci-runner",
          "/ci/api/runners/{id}/register",
          "qits-ci-runner",
          VERSION,
          1,
          new RunnerVocabulary("heldRuns", "adoptedRuns"),
          "run");

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
}
