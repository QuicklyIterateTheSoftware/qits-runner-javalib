package eu.wohlben.qits.runner.toolkit.health;

import eu.wohlben.qits.runner.toolkit.DockerCommand;
import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The checks every runner kind can run, each a factory so an embedder registers them where its own
 * wire order puts them — the workspaces runner interleaves its own between them, and its names and
 * order are what its host already reads. {@link HealthChecks#withDefaults} registers all three in
 * one order for an embedder that keeps none.
 *
 * <ul>
 *   <li>{@code docker} — the daemon answers; {@code data: {serverVersion}}. Read-only.
 *   <li>{@code nodeInventory} — {@link NodeInventoryCheck}: this runner's labelled containers and
 *       volumes and its own container; {@code data: {containers, volumes, runnerContainer}}.
 *       Read-only.
 *   <li>{@code session} — {@code data: {connected, connectedSince, slots, held}}: the slots of the
 *       last {@code Ack} and the rows holding a slot. Ok only while connected. Read-only.
 * </ul>
 */
public final class DefaultHealthChecks {

  /** Docker's "never" for a start or finish time; the inventory reports it as null. */
  public static final String DOCKER_ZERO_TIME = "0001-01-01T00:00:00Z";

  private DefaultHealthChecks() {}

  /** {@code docker version --format {{.Server.Version}}}, under the docker's own deadline. */
  public static RunnerHealthCheck docker() {
    return RunnerHealthCheck.named("docker", null, ctx -> docker(ctx.docker()));
  }

  /**
   * The {@code nodeInventory} check of the runner {@code runnerId} at {@code runnerVersion}.
   *
   * @param absent docker's words for "that object went between the listing and its inspect"; null
   *     for {@link #dockerSaysAbsent}
   */
  public static RunnerHealthCheck nodeInventory(
      RunnerIdentity identity, String runnerId, String runnerVersion, Predicate<String> absent) {
    return new NodeInventoryCheck(
        identity,
        runnerId,
        runnerVersion,
        absent == null ? DefaultHealthChecks::dockerSaysAbsent : absent);
  }

  /** The {@code session} check, over {@link HealthContext#session()}. */
  public static RunnerHealthCheck session() {
    return RunnerHealthCheck.named("session", null, ctx -> session(ctx.session()));
  }

  /** Docker's own words for "there is no such thing", in any case. */
  public static boolean dockerSaysAbsent(String detail) {
    String text = detail == null ? "" : detail.toLowerCase(Locale.ROOT);
    return text.contains("no such container")
        || text.contains("no such object")
        || text.contains("no such volume");
  }

  // --- the bodies --------------------------------------------------------------------------------

  private static RunnerHealthCheck.Result docker(DockerCommand docker) {
    RunnerDocker.Result answered =
        docker.run(List.of(docker.binary(), "version", "--format", "{{.Server.Version}}"));
    Map<String, Object> data = new LinkedHashMap<>();
    String version = answered.ok() ? answered.stdout().strip() : "";
    data.put("serverVersion", version.isEmpty() ? null : version);
    if (!answered.ok()) {
      return RunnerHealthCheck.Result.failed("docker did not answer: " + answered.detail(), data);
    }
    if (version.isEmpty()) {
      return RunnerHealthCheck.Result.failed("docker answered no server version", data);
    }
    return RunnerHealthCheck.Result.ok("docker " + version, data);
  }

  private static RunnerHealthCheck.Result session(SessionFacts facts) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("connected", facts.connected());
    data.put(
        "connectedSince", facts.connectedSince() == null ? null : facts.connectedSince().toString());
    data.put("slots", facts.slots());
    data.put("held", new ArrayList<>(facts.held()));
    String detail =
        facts.connected()
            ? "connected since "
                + facts.connectedSince()
                + ", "
                + facts.held().size()
                + " of "
                + facts.slots()
                + " slot(s) held"
            : "not connected";
    return new RunnerHealthCheck.Result(facts.connected(), detail, data);
  }
}
