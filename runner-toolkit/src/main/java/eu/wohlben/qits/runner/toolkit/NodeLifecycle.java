package eu.wohlben.qits.runner.toolkit;

import eu.wohlben.qits.runner.protocol.Retire;
import eu.wohlben.qits.runner.protocol.Upgrade;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * What a runner does to its own node, as {@link RunnerRuntime} calls it: replace itself ({@link
 * SelfUpdate}), remove itself ({@link Decommission}), clean up after itself ({@link Housekeeping}).
 * The runtime owns <em>when</em> — the frame, the drain, the domain's hooks, the exit code — and
 * this seam owns <em>what</em> happens on the node.
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
   * The runner learned it was deleted: stop the self-update and the housekeeping at once, on the
   * loop, before the domain is asked to stop its work — neither may start anything after this.
   */
  void halt();

  /**
   * The node half of a decommission, after the ledger drained, the socket stopped and the domain's
   * {@link RunnerDomain#onDecommission} returned. Never throws.
   */
  void decommission(RunnerSession session);

  /** The process is ending: stop whatever this seam scheduled. */
  void shutdown();

  /**
   * What the runtime cannot read off its settings: which container it is in, and the clocks under
   * the node's work. {@link #defaults} is production's; a suite names its own container and shrinks
   * every clock.
   */
  record Options(
      Optional<String> self,
      SelfUpdate.Settings selfUpdate,
      Decommission.Settings decommission,
      Housekeeping.Settings housekeeping,
      Clock clock) {

    static Options defaults(RunnerSettings settings) {
      return new Options(
          SelfContainer.detect(),
          SelfUpdate.Settings.defaults(settings.rolloverTimeoutSeconds()),
          Decommission.Settings.defaults(),
          Housekeeping.Settings.defaults(),
          Clock.systemUTC());
    }
  }

  /** The node lifecycle on the runner's own docker. */
  static NodeLifecycle onDocker(
      RunnerIdentity identity,
      RunnerSettings settings,
      RunnerDomain<?> domain,
      RunnerDocker docker,
      RegistryLogin login,
      Options options) {
    RunnerImages images = new RunnerImages(identity, docker, options.self());
    SelfUpdate selfUpdate =
        new SelfUpdate(
            identity,
            docker,
            settings.runnerId(),
            options.self(),
            options.selfUpdate(),
            login,
            domain::quiescent,
            images);
    Decommission decommission =
        new Decommission(
            identity, docker, settings.runnerId(), options.self(), settings.stateDir(),
            domain::teardown);
    List<HousekeepingPass> passes = new ArrayList<>();
    passes.add(new RunnerImagesPass(images));
    passes.add(new DanglingImagesPass(docker));
    passes.addAll(domain.housekeeping());
    Housekeeping housekeeping =
        new Housekeeping(
            passes,
            new HousekeepingContext(
                identity,
                settings.runnerId(),
                docker.driver(),
                new OwnResources(identity, settings.runnerId(), docker),
                docker.deadline(),
                settings.stateDir(),
                options.clock(),
                Logger.getLogger(Housekeeping.class)),
            options.housekeeping());
    return new NodeLifecycle() {
      @Override
      public void upgrade(Upgrade upgrade) {
        selfUpdate.upgrade(upgrade);
      }

      @Override
      public boolean retire(Retire retire) {
        return selfUpdate.retire(retire);
      }

      @Override
      public void leave() {
        selfUpdate.leave();
      }

      @Override
      public void poke() {
        selfUpdate.poke();
      }

      @Override
      public void firstAck() {
        // Predecessors first, so the leftover-image sweep finds them gone.
        selfUpdate.removePredecessors();
        housekeeping.start();
      }

      @Override
      public void halt() {
        selfUpdate.shutdown();
        housekeeping.shutdown();
      }

      @Override
      public void decommission(RunnerSession session) {
        halt();
        decommission.leave();
      }

      @Override
      public void shutdown() {
        halt();
      }
    };
  }
}
