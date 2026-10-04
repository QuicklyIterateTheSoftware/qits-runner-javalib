package eu.wohlben.qits.runner.toolkit;

import eu.wohlben.qits.containers.driver.ContainersDriver;
import eu.wohlben.qits.containers.driver.DockerContainersDriver;
import eu.wohlben.qits.containers.driver.DockerSocketGroup;
import eu.wohlben.qits.containers.driver.docker.ContainerProcess;
import eu.wohlben.qits.containers.driver.spec.LabelNamespace;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * <b>A runner's one way to docker</b>, both halves of it under the runner's own deadline:
 *
 * <ul>
 *   <li>{@link #driver()} — qits-containers-driver's {@link DockerContainersDriver}, under the label
 *       namespace {@code <labelRoot>.} ({@link RunnerIdentity#driverNamespace()}), never {@code
 *       qits.containers.}: listings by label, volumes, and everything a domain launches;
 *   <li>{@link #run} — the driver's process seam, {@link ContainerProcess#runSeparate}, for the
 *       lifecycle argv the driver does not model ({@link LifecycleArgv}).
 * </ul>
 *
 * <p><b>Why the separate-streams entry and not the merged one.</b> qits-ci-runner-daemon's {@code
 * Docker} kept stdout and stderr apart because docker writes the thing a caller reads to stdout and
 * its diagnosis to stderr, and a {@code docker inspect} with no {@code --format} answers a whole
 * JSON document: merged, one warning line on stderr makes it invalid JSON, and a tail of it is
 * missing its opening {@code [{}. {@link ContainerProcess#STDOUT_CAP} keeps stdout from its head,
 * whole up to 1 MiB, past any document docker answers; a document past the cap is reported as such
 * ({@link Result#stdoutTruncated}) rather than cut silently.
 */
public final class RunnerDocker {

  /**
   * One invocation's outcome. {@code timedOut} is its own flag so a kill is not a mystery exit.
   *
   * @param stdoutTruncated stdout went past {@link ContainerProcess#STDOUT_CAP}; a document read
   *     off it is not whole
   */
  public record Result(
      int exitCode, String stdout, String stderr, boolean timedOut, boolean stdoutTruncated) {

    public boolean ok() {
      return exitCode == 0 && !timedOut;
    }

    /** What a human should read: stderr when there is any, else stdout. */
    public String detail() {
      String text = stderr == null || stderr.isBlank() ? stdout : stderr;
      return text == null ? "" : text.strip();
    }
  }

  private final String binary;
  private final Duration deadline;
  private final ContainersDriver driver;

  /**
   * @param binary the docker CLI — {@code <envPrefix>DOCKER_BINARY}
   * @param deadline every call's — {@code <envPrefix>DOCKER_TIMEOUT}
   */
  public RunnerDocker(RunnerIdentity identity, String binary, Duration deadline) {
    this.binary = binary;
    this.deadline = deadline;
    this.driver =
        new DockerContainersDriver(
            binary,
            new LabelNamespace(identity.driverNamespace()),
            new DockerSocketGroup(Optional.empty())::value);
  }

  /** The runner's docker, from its settings. */
  public static RunnerDocker of(RunnerIdentity identity, RunnerSettings settings) {
    return new RunnerDocker(
        identity, settings.dockerBinary(), Duration.ofSeconds(settings.dockerTimeoutSeconds()));
  }

  /** The docker binary every argv begins with. */
  public String binary() {
    return binary;
  }

  /** The deadline every call is made under; the driver's methods take it explicitly. */
  public Duration deadline() {
    return deadline;
  }

  /** The runner-namespaced qits-containers driver. */
  public ContainersDriver driver() {
    return driver;
  }

  /**
   * Run one lifecycle argv under the deadline, stdout and stderr apart. A spawn failure (no docker
   * binary) is exit {@code -1} with the reason as stderr, so a caller only ever branches on {@link
   * Result#ok()}.
   */
  public Result run(List<String> argv) {
    ContainerProcess.Separated answered =
        ContainerProcess.runSeparate(argv, deadline, ContainerProcess.STDOUT_CAP);
    if (answered.timedOut()) {
      return new Result(
          -1,
          answered.stdout(),
          "docker "
              + String.join(" ", argv.subList(1, Math.min(argv.size(), 3)))
              + " did not answer within "
              + deadline.toSeconds()
              + "s",
          true,
          answered.stdoutTruncated());
    }
    String stderr = answered.stderrTail();
    if (answered.exitCode() == -1 && answered.stdout().isEmpty()) {
      stderr = "could not run " + argv.getFirst() + ": " + stderr;
    }
    return new Result(
        answered.exitCode(), answered.stdout(), stderr, false, answered.stdoutTruncated());
  }
}
