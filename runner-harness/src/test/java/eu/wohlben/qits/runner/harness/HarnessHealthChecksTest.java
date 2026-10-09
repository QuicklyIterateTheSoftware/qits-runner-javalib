package eu.wohlben.qits.runner.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.protocol.health.CheckResult;
import eu.wohlben.qits.runner.protocol.health.HealthReport;
import eu.wohlben.qits.runner.protocol.health.HealthWire;
import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.health.HealthChecks;
import eu.wohlben.qits.runner.toolkit.health.HealthContext;
import eu.wohlben.qits.runner.toolkit.health.RunnerHealthCheck;
import eu.wohlben.qits.runner.toolkit.health.SessionFacts;
import eu.wohlben.qits.runner.toolkit.testing.FakeDocker;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * An embedder adds a check of its own beside the toolkit's defaults, through the public API alone
 * — exactly what the CI runner does before it is on {@code RunnerRuntime}: its own docker seam, its
 * own session facts, one extra check that reads an embedder service, and the report read back off
 * the wire by name.
 */
@EnabledOnOs(OS.LINUX)
class HarnessHealthChecksTest {

  /** A service of the embedder's own, which its check looks up by type. */
  record Scratch(String volume) {}

  @TempDir Path dir;

  @Test
  void anEmbeddersOwnCheckRunsAfterTheDefaultsAndIsReportedByName() throws Exception {
    FakeDocker fake = new FakeDocker(dir).answer("version", 0, "27.3.1\n", "");
    RunnerDocker docker =
        new RunnerDocker(HarnessIdentity.IDENTITY, fake.binary(), Duration.ofSeconds(10));
    Instant since = Instant.parse("2026-10-09T08:00:00Z");
    HealthContext ctx =
        HealthContext.of(
            docker,
            () -> new SessionFacts(true, since, 1, List.of()),
            Map.of(Scratch.class, new Scratch("qits-harness-runner-scratch-r1")));

    HealthChecks checks =
        HealthChecks.withDefaults(
                Duration.ofSeconds(30),
                new HealthChecks.NodeFacts(HarnessIdentity.IDENTITY, "r1", HarnessIdentity.VERSION))
            .register(
                RunnerHealthCheck.named(
                    "scratch",
                    null,
                    c -> {
                      Scratch scratch = c.lookup(Scratch.class).orElseThrow();
                      return RunnerHealthCheck.Result.ok(
                          scratch.volume() + " is configured", Map.of("volume", scratch.volume()));
                    }));
    HealthReport report;
    try {
      report = HealthChecks.answer("req-h", checks.run(ctx));
    } finally {
      checks.close();
    }

    // Through the wire an embedder's codec delegates to, and back.
    HealthReport read = HealthWire.decodeChecked(HealthWire.encodeChecked(report));
    assertEquals(report, read);
    assertTrue(read.ok(), read.detail());
    assertEquals("all 4 checks passed", read.detail());
    assertEquals("req-h", read.requestId());
    assertEquals(
        List.of("docker", "nodeInventory", "session", "scratch"),
        read.checks().stream().map(CheckResult::name).toList());
    CheckResult scratch =
        read.checks().stream().filter(c -> c.name().equals("scratch")).findFirst().orElseThrow();
    assertEquals(
        new CheckResult(
            "scratch",
            true,
            "qits-harness-runner-scratch-r1 is configured",
            Map.of("volume", "qits-harness-runner-scratch-r1")),
        scratch);
  }
}
