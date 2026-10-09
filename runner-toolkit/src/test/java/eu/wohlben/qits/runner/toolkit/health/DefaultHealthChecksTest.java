package eu.wohlben.qits.runner.toolkit.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.protocol.RunnerVocabulary;
import eu.wohlben.qits.runner.toolkit.DockerCommand;
import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.runner.toolkit.testing.FakeDocker;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The default checks' data, key for key and in order — what qits-workspaces-runner-daemon's host
 * already reads under {@code docker}, {@code nodeInventory} and {@code session} — against a real
 * process seam standing in for docker.
 */
@EnabledOnOs(OS.LINUX)
class DefaultHealthChecksTest {

  static final String VERSION = "2026.1.1";
  static final String RUNNER_ID = "r1abcdef-0000";

  static final RunnerIdentity IDENTITY =
      new RunnerIdentity(
          "toy",
          "QITS_TOY_RUNNER_",
          "qits.toy.runner",
          "qits-toy-runner",
          "qits/qits-toy-runner",
          "/var/lib/qits-toy-runner",
          "/toy/api/runners/{id}/register",
          "qits-toy-runner",
          VERSION,
          1,
          new RunnerVocabulary("heldItems", "adoptedItems"),
          "item");

  @TempDir Path dir;

  private HealthContext ctx(FakeDocker fake, SessionFacts facts) {
    return HealthContext.of(
        new RunnerDocker(IDENTITY, fake.binary(), Duration.ofSeconds(10)), () -> facts, Map.of());
  }

  private static RunnerHealthCheck.Result run(RunnerHealthCheck check, HealthContext ctx)
      throws Exception {
    return check.run(ctx);
  }

  // --- docker ------------------------------------------------------------------------------------

  @Test
  void dockerReportsTheServerVersion() throws Exception {
    FakeDocker fake = new FakeDocker(dir).answer("version", 0, "27.3.1\n", "");
    RunnerHealthCheck docker = DefaultHealthChecks.docker();
    assertEquals("docker", docker.name());
    RunnerHealthCheck.Result result = run(docker, ctx(fake, null));
    assertEquals(
        new RunnerHealthCheck.Result(true, "docker 27.3.1", Map.of("serverVersion", "27.3.1")),
        result);
    assertEquals(
        List.of(List.of("version", "--format", "{{.Server.Version}}")), fake.calls());
  }

  @Test
  void dockerThatDoesNotAnswerOrAnswersNoVersionFailsWithANullVersion() throws Exception {
    FakeDocker fake = new FakeDocker(dir).answer("version", 1, "", "Cannot connect\n");
    RunnerHealthCheck.Result down = run(DefaultHealthChecks.docker(), ctx(fake, null));
    assertFalse(down.ok());
    assertEquals("docker did not answer: Cannot connect", down.detail());
    assertTrue(down.data().containsKey("serverVersion"));
    assertNull(down.data().get("serverVersion"));

    fake.answer("version", 0, "\n", "");
    RunnerHealthCheck.Result blank = run(DefaultHealthChecks.docker(), ctx(fake, null));
    assertEquals("docker answered no server version", blank.detail());
    assertFalse(blank.ok());
  }

  @Test
  void anEmbeddersOwnDockerAdaptsWithALambda() throws Exception {
    List<List<String>> seen = new ArrayList<>();
    DockerCommand own =
        DockerCommand.of(
            "/usr/bin/docker",
            Duration.ofSeconds(3),
            (argv, deadline) -> {
              seen.add(argv);
              assertEquals(Duration.ofSeconds(3), deadline);
              return new RunnerDocker.Result(0, "28.0.0\n", "", false, false);
            });
    RunnerHealthCheck.Result result =
        run(
            DefaultHealthChecks.docker(),
            HealthContext.of(own, () -> new SessionFacts(false, null, null), Map.of()));
    assertEquals("docker 28.0.0", result.detail());
    assertEquals(
        List.of(List.of("/usr/bin/docker", "version", "--format", "{{.Server.Version}}")), seen);
  }

  // --- session -----------------------------------------------------------------------------------

  @Test
  void sessionReportsItsFactsInTheWorkspacesShape() throws Exception {
    Instant since = Instant.parse("2026-10-05T04:38:19Z");
    RunnerHealthCheck session = DefaultHealthChecks.session();
    assertEquals("session", session.name());
    RunnerHealthCheck.Result connected =
        run(session, ctx(new FakeDocker(dir), new SessionFacts(true, since, 2, List.of("7"))));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("connected", true);
    data.put("connectedSince", "2026-10-05T04:38:19Z");
    data.put("slots", 2);
    data.put("held", List.of("7"));
    assertEquals(new ArrayList<>(data.entrySet()), new ArrayList<>(connected.data().entrySet()));
    assertTrue(connected.ok());
    assertEquals("connected since 2026-10-05T04:38:19Z, 1 of 2 slot(s) held", connected.detail());

    RunnerHealthCheck.Result down =
        run(session, ctx(new FakeDocker(dir), new SessionFacts(false, null, null)));
    Map<String, Object> none = new LinkedHashMap<>();
    none.put("connected", false);
    none.put("connectedSince", null);
    none.put("slots", null);
    none.put("held", List.of());
    assertEquals(new ArrayList<>(none.entrySet()), new ArrayList<>(down.data().entrySet()));
    assertFalse(down.ok());
    assertEquals("not connected", down.detail());
  }

  // --- nodeInventory -----------------------------------------------------------------------------

  private static String item(int n) {
    return "qits-toy-runner-item-" + n;
  }

  @Test
  void nodeInventoryReportsTheRunnersLabelledObjectsAndNothingElse() throws Exception {
    String self = IDENTITY.containerName(RUNNER_ID, VERSION);
    Map<String, String> item1 = Map.of(IDENTITY.ownerLabel(), RUNNER_ID, IDENTITY.workLabel(), "1");
    FakeDocker fake =
        new FakeDocker(dir)
            .container(item(1), item1)
            .volume(item(1), item1)
            .volume("qits-toy-runner-scratch-r1abcdef", Map.of(IDENTITY.ownerLabel(), RUNNER_ID))
            .container(self, Map.of(IDENTITY.processLabel(), RUNNER_ID))
            // The node's other tenants.
            .container("qits-ci-runner-x", Map.of("qits.ci.runner", RUNNER_ID))
            .container("qits-toy-runner-item-9", Map.of(IDENTITY.ownerLabel(), "r2"))
            .container("qits-toy-runner-r2-1", Map.of(IDENTITY.processLabel(), "r2"))
            .volume("my-data", Map.of())
            .answerFor(
                "inspect",
                item(1),
                0,
                "c1full|/"
                    + item(1)
                    + "|exited|2026-10-05T04:00:00.123456789Z|2026-10-05T04:30:00Z|137.0|"
                    + "img:1|1|<no value>\n",
                "")
            .answerFor(
                "inspect",
                self,
                0,
                "selffull|/"
                    + self
                    + "|running|2026-10-05T03:00:00Z|"
                    + DefaultHealthChecks.DOCKER_ZERO_TIME
                    + "|0|registry.example:5000/qits/qits-toy-runner:"
                    + VERSION
                    + "|<no value>|"
                    + VERSION
                    + "\n",
                "")
            .answerFor("volume-inspect", item(1), 0, item(1) + "|2026-10-04T10:00:00+02:00|1\n", "")
            .answerFor(
                "volume-inspect",
                "qits-toy-runner-scratch-r1abcdef",
                0,
                "qits-toy-runner-scratch-r1abcdef|2026-10-01T09:00:00Z|\n",
                "");

    RunnerHealthCheck inventory =
        DefaultHealthChecks.nodeInventory(IDENTITY, RUNNER_ID, VERSION, null);
    assertEquals("nodeInventory", inventory.name());
    RunnerHealthCheck.Result result = run(inventory, ctx(fake, null));

    Map<String, Object> container = new LinkedHashMap<>();
    container.put("name", item(1));
    container.put("id", "c1full");
    container.put("rowId", 1L);
    container.put("state", "exited");
    container.put("startedAt", "2026-10-05T04:00:00.123456789Z");
    container.put("finishedAt", "2026-10-05T04:30:00Z");
    container.put("exitCode", 137);
    container.put("image", "img:1");
    Map<String, Object> runner = new LinkedHashMap<>();
    runner.put("name", self);
    runner.put("id", "selffull");
    runner.put("version", VERSION);
    runner.put("startedAt", "2026-10-05T03:00:00Z");
    Map<String, Object> expected = new LinkedHashMap<>();
    expected.put("containers", List.of(container));
    expected.put(
        "volumes",
        List.of(
            volume(item(1), 1L, "2026-10-04T10:00:00+02:00"),
            volume("qits-toy-runner-scratch-r1abcdef", null, "2026-10-01T09:00:00Z")));
    expected.put("runnerContainer", runner);
    assertEquals(ordered(expected), ordered(result.data()));
    Map<?, ?> read = (Map<?, ?>) ((List<?>) result.data().get("containers")).getFirst();
    assertEquals(Long.class, read.get("rowId").getClass());
    assertEquals(Integer.class, read.get("exitCode").getClass());
    assertTrue(result.ok(), result.detail());
    assertEquals(
        "1 container(s), 2 volume(s), runner container " + self, result.detail());

    Set<String> foreign =
        Set.of("qits-ci-runner-x", "qits-toy-runner-item-9", "qits-toy-runner-r2-1", "my-data");
    for (List<String> call : fake.calls()) {
      assertTrue(
          Set.of("ps", "volume", "inspect").contains(call.getFirst()), () -> "a write: " + call);
      for (String f : foreign) {
        assertFalse(call.contains(f), () -> "touched " + f + ": " + call);
      }
    }
  }

  @Test
  void anObjectThatWentIsLeftOutAndAnInspectDockerDidNotAnswerFailsTheCheck() throws Exception {
    Map<String, String> owned = Map.of(IDENTITY.ownerLabel(), RUNNER_ID);
    FakeDocker fake =
        new FakeDocker(dir)
            .container(item(1), owned)
            .container(item(2), owned)
            .answerFor("inspect", item(1), 1, "", "Error: No such object: " + item(1) + "\n")
            .answerFor("inspect", item(2), 1, "", "permission denied\n");
    RunnerHealthCheck.Result result =
        run(DefaultHealthChecks.nodeInventory(IDENTITY, RUNNER_ID, VERSION, null), ctx(fake, null));
    assertFalse(result.ok());
    assertEquals(
        "docker did not answer an inspect of "
            + item(2)
            + ": permission denied (read: 0 container(s), 0 volume(s), no runner container)",
        result.detail());
    assertEquals(List.of(), result.data().get("containers"));
    assertTrue(result.data().containsKey("runnerContainer"));
    assertNull(result.data().get("runnerContainer"));

    // The embedder's own words for "gone" replace docker's.
    RunnerHealthCheck.Result lenient =
        run(
            DefaultHealthChecks.nodeInventory(
                IDENTITY, RUNNER_ID, VERSION, detail -> detail.contains("permission")),
            ctx(fake, null));
    assertEquals(
        "docker did not answer an inspect of "
            + item(1)
            + ": Error: No such object: "
            + item(1)
            + " (read: 0 container(s), 0 volume(s), no runner container)",
        lenient.detail());
  }

  @Test
  void dockerSaysAbsentInItsOwnWords() {
    assertTrue(DefaultHealthChecks.dockerSaysAbsent("Error: No such container: x"));
    assertTrue(DefaultHealthChecks.dockerSaysAbsent("Error: No such object: x"));
    assertTrue(DefaultHealthChecks.dockerSaysAbsent("get x: no such volume"));
    assertFalse(DefaultHealthChecks.dockerSaysAbsent("permission denied"));
    assertFalse(DefaultHealthChecks.dockerSaysAbsent(null));
  }

  @Test
  void withDefaultsRegistersDockerNodeInventoryAndSessionInThatOrder() {
    HealthChecks checks =
        HealthChecks.withDefaults(
            Duration.ofSeconds(5), new HealthChecks.NodeFacts(IDENTITY, RUNNER_ID, VERSION));
    try {
      assertEquals(List.of("docker", "nodeInventory", "session"), checks.names());
    } finally {
      checks.close();
    }
  }

  private static Map<String, Object> volume(String name, Long rowId, String createdAt) {
    Map<String, Object> v = new LinkedHashMap<>();
    v.put("name", name);
    v.put("rowId", rowId);
    v.put("createdAt", createdAt);
    return v;
  }

  /** A data map with its key order made part of equality, nested maps and lists included. */
  private static Object ordered(Object value) {
    if (value instanceof Map<?, ?> m) {
      List<Object> entries = new ArrayList<>();
      m.forEach((k, v) -> entries.add(List.of(k, String.valueOf(ordered(v)))));
      return entries;
    }
    if (value instanceof List<?> l) {
      return l.stream().map(DefaultHealthChecksTest::ordered).toList();
    }
    return value;
  }
}
