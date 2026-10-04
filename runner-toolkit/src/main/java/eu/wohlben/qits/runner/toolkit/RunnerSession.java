package eu.wohlben.qits.runner.toolkit;

import eu.wohlben.qits.containers.driver.ContainersDriver;
import eu.wohlben.qits.containers.driver.DockerContainersDriver;
import eu.wohlben.qits.containers.driver.DockerSocketGroup;
import eu.wohlben.qits.containers.driver.spec.LabelNamespace;
import eu.wohlben.qits.runner.protocol.RunnerMessage;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * <b>What the toolkit hands the domain</b>: the runner's handle on its socket, its slots and its
 * node. One per process, the same across reconnects — a frame sent while the socket is down fails
 * rather than queueing, and the domain learns of the drop through {@link
 * RunnerDomain#onSessionLost}.
 *
 * <p>{@link #taken} and {@link #released} are the slot ledger's only inputs from the domain, and
 * each may send a {@code Reserve} on the domain's behalf: the domain says what holds a slot, the
 * toolkit decides when to ask for more.
 */
public final class RunnerSession {

  /** The runtime's side of the session; the domain only ever sees the methods below. */
  interface Ops {
    CompletionStage<Void> send(RunnerMessage message);

    void taken(String workId);

    void released(String workId);

    List<String> held();

    boolean quarantined();

    CompletionStage<String> bearer();
  }

  private final RunnerIdentity identity;
  private final RunnerSettings settings;
  private final Ops ops;
  private volatile ContainersDriver driver;

  RunnerSession(RunnerIdentity identity, RunnerSettings settings, Ops ops) {
    this.identity = identity;
    this.settings = settings;
    this.ops = ops;
  }

  /** Send a frame — lifecycle or the domain's own. Fails when the socket is not open. */
  public CompletionStage<Void> send(RunnerMessage message) {
    return ops.send(message);
  }

  /** {@code workId} now holds a slot. May send the next {@code Reserve}. */
  public void taken(String workId) {
    ops.taken(workId);
  }

  /** {@code workId} no longer holds its slot. May send the next {@code Reserve}. */
  public void released(String workId) {
    ops.released(workId);
  }

  /** The work items holding a slot now, in the order they were taken. */
  public List<String> held() {
    return ops.held();
  }

  /** Whether the host's last word on quarantine was {@code Quarantined}. Policy is the host's. */
  public boolean quarantined() {
    return ops.quarantined();
  }

  public RunnerIdentity identity() {
    return identity;
  }

  public String runnerId() {
    return settings.runnerId();
  }

  public Path stateDir() {
    return settings.stateDir();
  }

  /**
   * The runner-namespaced qits-containers driver: {@code <dockerBinary>} under the label namespace
   * {@code <labelRoot>.} ({@link RunnerIdentity#driverNamespace()}), never {@code qits.containers.},
   * so qits-containers-service's collection never sees a runner's workloads or volumes.
   */
  public ContainersDriver driver() {
    ContainersDriver d = driver;
    if (d == null) {
      synchronized (this) {
        d = driver;
        if (d == null) {
          d =
              new DockerContainersDriver(
                  settings.dockerBinary(),
                  new LabelNamespace(identity.driverNamespace()),
                  new DockerSocketGroup(Optional.empty())::value);
          driver = d;
        }
      }
    }
    return d;
  }

  /** The runner's access token — the cached one, or a fresh mint. Never logged, never stored. */
  public Supplier<CompletionStage<String>> bearer() {
    return ops::bearer;
  }
}
