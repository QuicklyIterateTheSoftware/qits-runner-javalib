package eu.wohlben.qits.runner.protocol.health;

import java.util.List;

/**
 * A runner's answer to a health check, as every runner kind reports it: {@code ok} only when every
 * check passed, {@code detail} a line for a person, and each named check's outcome in the runner's
 * order — extensible by design: a new check is a new entry with its own {@code data}, never a new
 * field.
 *
 * <p>Deliberately <b>not</b> a {@code RunnerMessage}. {@code healthCheck}/{@code healthChecked}
 * stay each runner's own domain frames: a record here could not sit in an embedder's sealed {@code
 * permits} list, and another runner kind already spells those type strings with another payload.
 * An embedder keeps its own thin frame record and hands this shape to {@link HealthWire}.
 *
 * @param ok every check passed
 * @param detail a line for a person
 * @param requestId the request this answers, or null when the asker sent none
 * @param checks each check's outcome; empty from a runner older than the field
 */
public record HealthReport(boolean ok, String detail, String requestId, List<CheckResult> checks) {

  public HealthReport {
    checks = checks == null ? List.of() : List.copyOf(checks);
  }

  /** The original shape: no request id, no named checks. */
  public HealthReport(boolean ok, String detail) {
    this(ok, detail, null, List.of());
  }
}
