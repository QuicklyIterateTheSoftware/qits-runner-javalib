package eu.wohlben.qits.runner.toolkit.health;

import eu.wohlben.qits.runner.protocol.health.CheckResult;
import eu.wohlben.qits.runner.protocol.health.HealthReport;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The registry of a runner's {@link RunnerHealthCheck}s and the one way they are run: in
 * registration order, each on its own thread under its own deadline, so a check that throws or
 * hangs is reported as failed — with the error — and every other check is still run and reported.
 * A health check is a diagnosis; one broken probe must not hide the rest of the picture.
 *
 * <p>The registry imposes no order of its own: the wire carries the checks in registration order,
 * and the workspaces runner's order (docker, workspaceImage, selfTest, nodeInventory, login,
 * session, …) is its own. {@link #withDefaults} is the convenience for an embedder that has no
 * order to keep.
 */
public final class HealthChecks {

  /** One check's outcome, under its name. */
  public record Outcome(String name, boolean ok, String detail, Map<String, Object> data) {}

  /**
   * What the default {@code nodeInventory} check needs to know of the node's runner.
   *
   * @param identity the runner's identity — its labels and container name
   * @param runnerId the runner's id
   * @param runnerVersion the runner's own version, which names its own container
   */
  public record NodeFacts(RunnerIdentity identity, String runnerId, String runnerVersion) {}

  private final List<RunnerHealthCheck> checks = new CopyOnWriteArrayList<>();
  private final Duration defaultDeadline;

  /** Daemon threads: a check that ignores its interrupt never holds the process. */
  private final ExecutorService pool =
      Executors.newCachedThreadPool(
          r -> {
            Thread t = new Thread(r, "runner-health-check");
            t.setDaemon(true);
            return t;
          });

  /** @param defaultDeadline a check's deadline when it names none */
  public HealthChecks(Duration defaultDeadline) {
    this.defaultDeadline = Objects.requireNonNull(defaultDeadline, "defaultDeadline");
  }

  /**
   * A registry holding the defaults in this order — {@code docker}, {@code nodeInventory}, {@code
   * session} — for an embedder with no order of its own to keep (CI). Register further checks on
   * the result; they run after these.
   */
  public static HealthChecks withDefaults(Duration defaultDeadline, NodeFacts facts) {
    return new HealthChecks(defaultDeadline)
        .register(DefaultHealthChecks.docker())
        .register(
            DefaultHealthChecks.nodeInventory(
                facts.identity(), facts.runnerId(), facts.runnerVersion(), null))
        .register(DefaultHealthChecks.session());
  }

  /** Add a check; it runs after every check registered before it. A name is unique. */
  public HealthChecks register(RunnerHealthCheck check) {
    Objects.requireNonNull(check, "check");
    Objects.requireNonNull(check.name(), "check.name()");
    for (RunnerHealthCheck existing : checks) {
      if (existing.name().equals(check.name())) {
        throw new IllegalArgumentException("a health check named " + check.name() + " exists");
      }
    }
    checks.add(check);
    return this;
  }

  /**
   * Add each check in the collection's order — an ArC {@code @All List<RunnerHealthCheck>} in a
   * Quarkus embedder. A duplicate name is refused as by {@link #register}, and the checks before
   * it stay registered.
   */
  public HealthChecks registerAll(Collection<? extends RunnerHealthCheck> more) {
    for (RunnerHealthCheck check : more) {
      register(check);
    }
    return this;
  }

  /** The registered checks' names, in order. */
  public List<String> names() {
    return checks.stream().map(RunnerHealthCheck::name).toList();
  }

  /** Run every check, in order, each under its deadline; never throws for a check's failure. */
  public List<Outcome> run(HealthContext ctx) {
    List<Outcome> outcomes = new ArrayList<>();
    for (RunnerHealthCheck check : checks) {
      outcomes.add(runOne(check, ctx));
    }
    return outcomes;
  }

  /**
   * The report for a run of checks: ok only when all of them are; its detail {@code "all N checks
   * passed"}, or each failure as {@code "<name>: <detail>"} joined with {@code "; "}.
   */
  public static HealthReport answer(String requestId, List<Outcome> outcomes) {
    List<String> failed = new ArrayList<>();
    for (Outcome o : outcomes) {
      if (!o.ok()) {
        failed.add(o.name() + ": " + o.detail());
      }
    }
    String detail =
        failed.isEmpty() ? "all " + outcomes.size() + " checks passed" : String.join("; ", failed);
    return new HealthReport(
        failed.isEmpty(),
        detail,
        requestId,
        outcomes.stream().map(o -> new CheckResult(o.name(), o.ok(), o.detail(), o.data())).toList());
  }

  /**
   * Stop the threads; for a suite's teardown and the runner's end. A check under way gets a few
   * seconds to end before it is interrupted, since an interrupted docker call leaves its process
   * running on.
   */
  public void close() {
    pool.shutdown();
    try {
      if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
        pool.shutdownNow();
      }
    } catch (InterruptedException interrupted) {
      pool.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  private Outcome runOne(RunnerHealthCheck check, HealthContext ctx) {
    Duration deadline = check.deadline() == null ? defaultDeadline : check.deadline();
    Future<RunnerHealthCheck.Result> running;
    try {
      running = pool.submit(() -> check.run(ctx));
    } catch (RuntimeException closed) {
      return failed(check, "could not run: " + closed.getMessage());
    }
    try {
      RunnerHealthCheck.Result result = running.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
      if (result == null) {
        return failed(check, "the check answered nothing");
      }
      return new Outcome(
          check.name(),
          result.ok(),
          result.detail(),
          result.data() == null ? Map.of() : result.data());
    } catch (TimeoutException e) {
      running.cancel(true);
      return failed(check, "timed out after " + deadline.toMillis() + " ms");
    } catch (ExecutionException e) {
      Throwable cause = e.getCause() == null ? e : e.getCause();
      return failed(check, "failed: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
    } catch (InterruptedException e) {
      running.cancel(true);
      Thread.currentThread().interrupt();
      return failed(check, "interrupted");
    }
  }

  private static Outcome failed(RunnerHealthCheck check, String detail) {
    return new Outcome(check.name(), false, detail, Map.of());
  }
}
