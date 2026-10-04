package eu.wohlben.qits.runner.toolkit;

import eu.wohlben.qits.runner.protocol.Retire;
import eu.wohlben.qits.runner.protocol.Upgrade;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jboss.logging.Logger;

/**
 * What a runner does to its own node, as {@link RunnerRuntime} calls it: replace itself, remove
 * itself, clean up after itself. The runtime owns <em>when</em> — the frame, the drain, the domain's
 * hooks, the exit code — and this seam owns <em>what</em> happens on the node.
 *
 * <p>The self-update ({@code SelfUpdate}), decommission helper ({@code Decommission}) and
 * housekeeping of the node-lifecycle feature plug in here without the runtime changing shape. Until
 * then {@link #minimal} is the whole of it: an {@code Upgrade} is drained for and logged, a
 * superseding {@code Retire} is an orderly exit, and a decommission forgets the revoked client.
 */
interface NodeLifecycle {

  /** An {@code Upgrade} arrived on a self-updating runner; the ledger is already draining. */
  void upgrade(Upgrade upgrade);

  /**
   * A superseding {@code Retire} arrived. True when the runner should leave now ({@link #leave},
   * then exit 0); false when this retirement is not one to act on.
   */
  boolean retire(Retire retire);

  /** The orderly exit's node half — for a self-updating runner, taking its restart policy away. */
  void leave();

  /** A slot was freed or the socket dropped: a self-update waiting on quiescence looks again. */
  void poke();

  /**
   * The first {@code Ack} of this process: the version is proven on the wire, so predecessors can
   * go and housekeeping can start. Once per process, off the event loop.
   */
  void firstAck();

  /**
   * The node half of a decommission, after the ledger drained, the socket stopped and the domain's
   * {@link RunnerDomain#onDecommission} returned. Never throws.
   */
  void decommission(RunnerSession session);

  /** The process is ending: stop whatever this seam scheduled. */
  void shutdown();

  /**
   * The node lifecycle with no self-update, no helper and no housekeeping: the client is forgotten
   * on a decommission, so nothing under this state directory can dial again, and everything else is
   * the person's who installed the runner.
   */
  static NodeLifecycle minimal(RunnerIdentity identity, Path stateDir) {
    Logger log = Logger.getLogger(NodeLifecycle.class);
    return new NodeLifecycle() {
      @Override
      public void upgrade(Upgrade upgrade) {
        log.infof(
            "%s drains for an upgrade to %s; it has no self-update to start a successor with",
            identity.logName(), upgrade.version());
      }

      @Override
      public boolean retire(Retire retire) {
        return true;
      }

      @Override
      public void leave() {}

      @Override
      public void poke() {}

      @Override
      public void firstAck() {}

      @Override
      public void decommission(RunnerSession session) {
        try {
          Files.deleteIfExists(stateDir.resolve(Registration.CLIENT_FILE));
        } catch (IOException | RuntimeException e) {
          log.warnf("%s could not delete its revoked client: %s", identity.logName(), e.getMessage());
        }
      }

      @Override
      public void shutdown() {}
    };
  }
}
