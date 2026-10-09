package eu.wohlben.qits.runner.toolkit.health;

import eu.wohlben.qits.runner.toolkit.DockerCommand;
import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.RunnerSession;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * What a {@link RunnerHealthCheck} is handed: the runner's docker, its session's facts, and
 * whatever else the embedder chose to expose by type.
 *
 * <p><b>Why not the {@link RunnerSession} itself</b>, as the checks took when they lived in the
 * workspaces runner: the CI runner is not on {@code RunnerRuntime} yet (qits-772), so it has no
 * session to hand, and the default checks need only a docker seam and three facts. An embedder that
 * is on the runtime still reaches its session — {@code ctx.lookup(RunnerSession.class)} — through
 * {@link #of(RunnerSession, RunnerDocker, Supplier)}.
 */
public interface HealthContext {

  /** The docker every default check reads through. */
  DockerCommand docker();

  /** The session's facts, read now. */
  SessionFacts session();

  /** A service the embedder registered under exactly {@code type}; empty when it did not. */
  <T> Optional<T> lookup(Class<T> type);

  /**
   * A context from a docker seam, a source of session facts and any services an embedder's own
   * checks look up by their exact class.
   */
  static HealthContext of(
      DockerCommand docker, Supplier<SessionFacts> session, Map<Class<?>, Object> services) {
    Objects.requireNonNull(docker, "docker");
    Objects.requireNonNull(session, "session");
    Map<Class<?>, Object> copy = new LinkedHashMap<>(services == null ? Map.of() : services);
    copy.forEach(
        (type, service) -> {
          if (!type.isInstance(service)) {
            throw new IllegalArgumentException(
                "the service registered as " + type.getName() + " is not one: " + service);
          }
        });
    return new HealthContext() {
      @Override
      public DockerCommand docker() {
        return docker;
      }

      @Override
      public SessionFacts session() {
        return session.get();
      }

      @Override
      public <T> Optional<T> lookup(Class<T> type) {
        return Optional.ofNullable(copy.get(type)).map(type::cast);
      }
    };
  }

  /**
   * The context of an embedder on {@code RunnerRuntime}: {@code held} is always the session's own
   * slot ledger ({@link RunnerSession#held()}), read when the facts are, whatever the supplier
   * says — the ledger is the one truth of what holds a slot. {@code lookup(RunnerSession.class)}
   * answers the session, {@code lookup(RunnerDocker.class)} the docker.
   */
  static HealthContext of(
      RunnerSession session, RunnerDocker docker, Supplier<SessionFacts> facts) {
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(facts, "facts");
    Map<Class<?>, Object> services = new LinkedHashMap<>();
    services.put(RunnerSession.class, session);
    services.put(RunnerDocker.class, docker);
    return of(docker, () -> facts.get().withHeld(session.held()), services);
  }
}
