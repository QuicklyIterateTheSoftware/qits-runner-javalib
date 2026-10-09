package eu.wohlben.qits.runner.toolkit;

import java.time.Duration;
import java.util.List;

/**
 * <b>The narrowest docker seam</b>: one argv, run under a deadline, stdout and stderr apart. {@link
 * RunnerDocker} is one; an embedder that is not on {@code RunnerDocker} — qits-ci-runner-daemon,
 * whose own {@code Docker} forks the CLI, until qits-772 moves it — adapts its own with {@link #of}
 * and a lambda, which is what lets the health checks ({@code toolkit.health}) run on both.
 *
 * <p>Every argv begins with {@link #binary()}, exactly as {@link RunnerDocker#run} expects; a caller
 * builds it, so the argv a test asserts is the argv that is forked.
 */
public interface DockerCommand {

  /** The docker binary every argv begins with. */
  String binary();

  /** The deadline a call is made under when the caller names none. */
  Duration deadline();

  /** Run one argv under {@link #deadline()}. */
  default RunnerDocker.Result run(List<String> argv) {
    return run(argv, deadline());
  }

  /**
   * Run one argv under {@code deadline}. A spawn failure is a {@link RunnerDocker.Result} that is
   * not {@link RunnerDocker.Result#ok() ok}, never an exception, so a caller only branches on it.
   */
  RunnerDocker.Result run(List<String> argv, Duration deadline);

  /** {@link #run(List, Duration)} as a lambda, for {@link #of}. */
  @FunctionalInterface
  interface Runner {
    RunnerDocker.Result run(List<String> argv, Duration deadline);
  }

  /**
   * A docker seam from a binary, a default deadline and a function that runs an argv — the adapter
   * an embedder with its own docker client writes in one expression, e.g. {@code
   * DockerCommand.of("docker", Duration.ofSeconds(120), (argv, d) -> toResult(ciDocker.run(argv)))}.
   */
  static DockerCommand of(String binary, Duration deadline, Runner runner) {
    return new DockerCommand() {
      @Override
      public String binary() {
        return binary;
      }

      @Override
      public Duration deadline() {
        return deadline;
      }

      @Override
      public RunnerDocker.Result run(List<String> argv, Duration callDeadline) {
        return runner.run(argv, callDeadline);
      }
    };
  }
}
