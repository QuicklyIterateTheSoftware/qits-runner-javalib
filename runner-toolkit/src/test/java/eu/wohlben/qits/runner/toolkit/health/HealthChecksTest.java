package eu.wohlben.qits.runner.toolkit.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.protocol.health.CheckResult;
import eu.wohlben.qits.runner.protocol.health.HealthReport;
import eu.wohlben.qits.runner.toolkit.DockerCommand;
import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The registry's contract, lifted from qits-workspaces-runner-daemon's health cases: a check that
 * throws, hangs or answers nothing fails on its own and every other check is still reported, in
 * registration order; a name is unique; and the report's ok/detail rules.
 */
class HealthChecksTest {

  private static final DockerCommand NO_DOCKER =
      DockerCommand.of(
          "docker",
          Duration.ofSeconds(1),
          (argv, deadline) -> new RunnerDocker.Result(-1, "", "no docker here", false, false));

  private static final HealthContext CTX =
      HealthContext.of(NO_DOCKER, () -> new SessionFacts(false, null, null), Map.of());

  private final HealthChecks checks = new HealthChecks(Duration.ofSeconds(5));

  @AfterEach
  void close() {
    checks.close();
  }

  private static RunnerHealthCheck passing(String name) {
    return RunnerHealthCheck.named(
        name, null, ctx -> RunnerHealthCheck.Result.ok(name + " is fine", Map.of("n", name)));
  }

  @Test
  void aCheckThatThrowsOrHangsFailsAndEveryOtherCheckIsStillReportedInOrder() {
    checks
        .register(passing("first"))
        .register(
            RunnerHealthCheck.named(
                "throws",
                null,
                ctx -> {
                  throw new IllegalStateException("broken on purpose");
                }))
        .register(
            RunnerHealthCheck.named(
                "hangs",
                Duration.ofMillis(200),
                ctx -> {
                  Thread.sleep(60_000);
                  return RunnerHealthCheck.Result.ok("never", Map.of());
                }))
        .register(passing("last"));

    List<HealthChecks.Outcome> outcomes = checks.run(CTX);

    assertEquals(
        List.of("first", "throws", "hangs", "last"),
        outcomes.stream().map(HealthChecks.Outcome::name).toList());
    assertTrue(outcomes.get(0).ok());
    assertEquals(Map.of("n", "first"), outcomes.get(0).data());
    assertFalse(outcomes.get(1).ok());
    assertEquals(
        "failed: IllegalStateException: broken on purpose", outcomes.get(1).detail());
    assertEquals(Map.of(), outcomes.get(1).data());
    assertFalse(outcomes.get(2).ok());
    assertEquals("timed out after 200 ms", outcomes.get(2).detail());
    assertTrue(outcomes.get(3).ok());

    HealthReport report = HealthChecks.answer("req-7", outcomes);
    assertFalse(report.ok());
    assertEquals("req-7", report.requestId());
    assertEquals(
        "throws: failed: IllegalStateException: broken on purpose; hangs: timed out after 200 ms",
        report.detail());
    assertEquals(4, report.checks().size());
  }

  @Test
  void aCheckThatAnswersNothingFailsAndNullDataReadsAsEmpty() {
    checks
        .register(RunnerHealthCheck.named("silent", null, ctx -> null))
        .register(
            RunnerHealthCheck.named(
                "noData", null, ctx -> RunnerHealthCheck.Result.ok("fine", null)));
    List<HealthChecks.Outcome> outcomes = checks.run(CTX);
    assertEquals(
        new HealthChecks.Outcome("silent", false, "the check answered nothing", Map.of()),
        outcomes.get(0));
    assertEquals(new HealthChecks.Outcome("noData", true, "fine", Map.of()), outcomes.get(1));
  }

  @Test
  void aChecksOwnDeadlineWinsAndTheRegistrysIsTheDefault() {
    HealthChecks quick = new HealthChecks(Duration.ofMillis(100));
    try {
      quick.register(
          RunnerHealthCheck.named(
              "slow",
              null,
              ctx -> {
                Thread.sleep(10_000);
                return null;
              }));
      quick.register(
          RunnerHealthCheck.named(
              "patient",
              Duration.ofSeconds(5),
              ctx -> {
                Thread.sleep(300);
                return RunnerHealthCheck.Result.ok("waited", Map.of());
              }));
      List<HealthChecks.Outcome> outcomes = quick.run(CTX);
      assertEquals("timed out after 100 ms", outcomes.get(0).detail());
      assertTrue(outcomes.get(1).ok(), outcomes.get(1)::toString);
    } finally {
      quick.close();
    }
  }

  @Test
  void aDuplicateNameIsRefusedAndRegistrationOrderIsKept() {
    checks.register(passing("b")).register(passing("a"));
    IllegalArgumentException refused =
        assertThrows(IllegalArgumentException.class, () -> checks.register(passing("a")));
    assertEquals("a health check named a exists", refused.getMessage());
    checks.registerAll(List.of(passing("d"), passing("c")));
    assertEquals(List.of("b", "a", "d", "c"), checks.names());
    assertThrows(
        IllegalArgumentException.class, () -> checks.registerAll(List.of(passing("e"), passing("b"))));
    assertEquals(List.of("b", "a", "d", "c", "e"), checks.names());
  }

  @Test
  void theReportIsOkOnlyWhenEveryCheckIsAndCarriesEachInOrder() {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("z", 1);
    data.put("a", null);
    HealthReport all =
        HealthChecks.answer(
            null,
            List.of(
                new HealthChecks.Outcome("docker", true, "docker 27.3.1", data),
                new HealthChecks.Outcome("session", true, "connected", Map.of())));
    assertTrue(all.ok());
    assertEquals("all 2 checks passed", all.detail());
    assertEquals(null, all.requestId(), "an id-less request gets an id-less answer");
    assertEquals(
        List.of(
            new CheckResult("docker", true, "docker 27.3.1", data),
            new CheckResult("session", true, "connected", Map.of())),
        all.checks());
    assertEquals(List.of("z", "a"), new ArrayList<>(all.checks().getFirst().data().keySet()));

    HealthReport none = HealthChecks.answer("r", List.of());
    assertTrue(none.ok());
    assertEquals("all 0 checks passed", none.detail());

    HealthReport one =
        HealthChecks.answer(
            "r",
            List.of(
                new HealthChecks.Outcome("docker", false, "docker did not answer: x", Map.of()),
                new HealthChecks.Outcome("session", true, "connected", Map.of())));
    assertFalse(one.ok());
    assertEquals("docker: docker did not answer: x", one.detail());
  }

  @Test
  void aClosedRegistryReportsEveryCheckAsUnableToRun() {
    checks.register(passing("first"));
    checks.close();
    HealthChecks.Outcome outcome = checks.run(CTX).getFirst();
    assertFalse(outcome.ok());
    assertTrue(outcome.detail().startsWith("could not run: "), outcome::detail);
  }

  @Test
  void aCheckLooksUpWhatTheEmbedderRegisteredByItsExactType() {
    StringBuilder service = new StringBuilder("a service");
    HealthContext ctx =
        HealthContext.of(
            NO_DOCKER,
            () -> new SessionFacts(false, null, null),
            Map.of(StringBuilder.class, service));
    assertSame(service, ctx.lookup(StringBuilder.class).orElseThrow());
    assertEquals(Optional.empty(), ctx.lookup(CharSequence.class));
    assertSame(NO_DOCKER, ctx.docker());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HealthContext.of(
                NO_DOCKER, () -> new SessionFacts(false, null, null), Map.of(Integer.class, "x")));
  }
}
