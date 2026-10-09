package eu.wohlben.qits.runner.toolkit.health;

import java.time.Duration;
import java.util.Map;

/**
 * One named check a runner runs when its host asks for a health check. It speaks only the {@link
 * HealthContext} and plain JSON-shaped data, so every runner kind registers the same kind of thing
 * — lifted from qits-workspaces-runner-daemon so the CI runner can share it.
 *
 * <p>A check reports facts; it does not act on them. Whatever it writes to the node is its own
 * declared business and documented on the implementation: most checks only read.
 */
public interface RunnerHealthCheck {

  /** The stable name the report carries, lowercase-camel ({@code docker}, {@code session}). */
  String name();

  /**
   * How long {@link #run} may take before {@link HealthChecks} reports it timed out; null for the
   * registry's default.
   */
  default Duration deadline() {
    return null;
  }

  /**
   * Run the check. Throwing is allowed and is reported as a failed check carrying the error, so an
   * implementation need not guard against every failure of what it reads.
   */
  Result run(HealthContext ctx) throws Exception;

  /**
   * A check's outcome.
   *
   * @param ok whether it found what it looks for
   * @param detail one line for a person
   * @param data JSON-shaped facts — strings, numbers, booleans, null, lists and maps of those
   */
  record Result(boolean ok, String detail, Map<String, Object> data) {

    public static Result ok(String detail, Map<String, Object> data) {
      return new Result(true, detail, data);
    }

    public static Result failed(String detail, Map<String, Object> data) {
      return new Result(false, detail, data);
    }
  }

  /** A check body; {@link RunnerHealthCheck#run} without the name, for {@link #named}. */
  @FunctionalInterface
  interface Body {
    Result run(HealthContext ctx) throws Exception;
  }

  /**
   * A check from a name, a deadline (null for the registry's default) and a lambda — so a check
   * needs no class of its own.
   */
  static RunnerHealthCheck named(String name, Duration deadline, Body body) {
    return new RunnerHealthCheck() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public Duration deadline() {
        return deadline;
      }

      @Override
      public Result run(HealthContext ctx) throws Exception {
        return body.run(ctx);
      }

      @Override
      public String toString() {
        return "RunnerHealthCheck[" + name + "]";
      }
    };
  }
}
