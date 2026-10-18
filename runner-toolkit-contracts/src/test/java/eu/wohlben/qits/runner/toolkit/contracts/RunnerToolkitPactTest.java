package eu.wohlben.qits.runner.toolkit.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.pact.consumer.ConsumerPact;
import eu.wohlben.qits.pact.consumer.GoldenMasters;
import eu.wohlben.qits.runner.protocol.RunnerVocabulary;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.runner.toolkit.contracts.RunnerToolkitPact.Feature;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * The rows stay checked here, through the real toolkit, under a dummy consumer — and no pact file is
 * ever written: a library's calls are pacted by the daemons that embed it (qits-1163).
 *
 * <p>Twice over: against the providers' published golden masters, where a row whose state the
 * provider has not recorded is skipped naming that state; and against fixture recordings shaped as
 * each provider would record its state, so every drive runs end to end even before it is recorded.
 */
class RunnerToolkitPactTest {

  static final String SELFTEST = "qits-selftest-runner-daemon";

  /** A workspaces-shaped identity: its register path is the one the drive sends. */
  static final RunnerIdentity WORKSPACES_SHAPED =
      new RunnerIdentity(
          "workspaces",
          "QITS_WORKSPACES_RUNNER_",
          "qits.workspaces.runner",
          "qits-workspaces-runner",
          "qits/qits-workspaces-runner",
          "/var/lib/qits-workspaces-runner",
          "/workspaces/api/runners/{id}/register",
          "qits-workspaces-runner",
          "2026.1018.1",
          1,
          new RunnerVocabulary("heldWorkspaces", "adoptedWorkspaces"),
          "workspace");

  static final GoldenMasters FIXTURE_OBSERVABILITY =
      GoldenMasters.at("fixture-golden-masters/observability/", "qits-observability-service", "qits-observability");

  static final GoldenMasters FIXTURE_WORKSPACES =
      GoldenMasters.at("fixture-golden-masters/workspaces/", "qits-workspaces-service", "qits-workspaces");

  static final GoldenMasters FIXTURE_PROJECTS =
      GoldenMasters.at("fixture-golden-masters/projects/", "qits-projects-service", "qits-projects");

  static Stream<DynamicTest> run(List<ToolkitRow> rows) {
    return rows.stream().map(row -> DynamicTest.dynamicTest(row.name(), row::run));
  }

  @Test
  void theViaIsTheToolkitAtThisBuildsVersion() {
    assertEquals("qits-runner-javalib", RunnerToolkitPact.VIA.repository());
    assertEquals("eu.wohlben.qits:qits-runner-toolkit", RunnerToolkitPact.VIA.artifact());
    assertFalse(RunnerToolkitPact.VIA.version().isBlank());
    assertFalse(RunnerToolkitPact.VIA.version().startsWith("$"));
    String toolkit = RunnerToolkitPact.toolkitVersionOnClasspath();
    if (toolkit != null) {
      assertEquals(toolkit, RunnerToolkitPact.VIA.version());
    }
  }

  @TestFactory
  Stream<DynamicTest> theWorkspacesRunnersRowsAgainstThePublishedRecordings() {
    return run(
        RunnerToolkitPact.forConsumer(SELFTEST)
            .token()
            .logExport()
            .register(RunnerToolkitPact.WORKSPACES, "registerRunner", WORKSPACES_SHAPED)
            .rows());
  }

  @TestFactory
  Stream<DynamicTest> theDeskRunnersRegisterRowsAgainstThePublishedRecordings() {
    return run(
        RunnerToolkitPact.forConsumer(SELFTEST).register(RunnerToolkitPact.PROJECTS, "registerDeskRunner").rows());
  }

  @TestFactory
  Stream<DynamicTest> everyDriveAgainstRecordingsOfEveryState() {
    List<ToolkitRow> rows =
        RunnerToolkitPact.forConsumer(SELFTEST)
            .token()
            .logExport(FIXTURE_OBSERVABILITY)
            .register(FIXTURE_WORKSPACES, "registerRunner", WORKSPACES_SHAPED)
            .rows();
    assertTrue(rows.stream().allMatch(ToolkitRow::recorded), "every row has a recording here");
    return run(rows);
  }

  @TestFactory
  Stream<DynamicTest> theRegisterDriveWithoutAnIdentitySendsTheRecordedPath() {
    List<ToolkitRow> rows =
        RunnerToolkitPact.forConsumer(SELFTEST).register(FIXTURE_PROJECTS, "registerDeskRunner").rows();
    assertTrue(rows.stream().allMatch(ToolkitRow::recorded));
    return run(rows);
  }

  @Test
  void aFeatureIsInOnlyWhenDeclared() {
    RunnerToolkitPacts none = RunnerToolkitPact.forConsumer(SELFTEST);
    assertTrue(none.pacts().isEmpty());
    assertTrue(none.rows().isEmpty());
    RunnerToolkitPacts token = none.token();
    assertEquals(java.util.Set.of(Feature.TOKEN), token.features());
    assertEquals(List.of("qits-idp-service"), token.pacts().stream().map(p -> p.masters().repository()).toList());
    assertEquals(RunnerToolkitPact.tokenRows(), token.pacts().getFirst().rows());
  }

  @Test
  void everyPactIsTheDaemonsThroughTheToolkit() {
    List<ConsumerPact> pacts =
        RunnerToolkitPact.forConsumer(SELFTEST)
            .token()
            .logExport(FIXTURE_OBSERVABILITY)
            .register(FIXTURE_WORKSPACES, "registerRunner", WORKSPACES_SHAPED)
            .pacts();
    assertEquals(
        List.of(
            SELFTEST + "_qits-runner-javalib_qits-idp-service.json",
            SELFTEST + "_qits-runner-javalib_qits-observability-service.json",
            SELFTEST + "_qits-runner-javalib_qits-workspaces-service.json"),
        pacts.stream().map(ConsumerPact::file).toList());
    for (ConsumerPact pact : pacts) {
      assertEquals(RunnerToolkitPact.VIA, pact.via().orElseThrow());
      // Normalised in memory only: the library writes no pact file.
      pact.assertEveryInteractionCarriesBothReferences();
    }
    assertFalse(Files.exists(ConsumerPact.pactsDirectory()), "the library committed a pacts/ directory");
  }
}
