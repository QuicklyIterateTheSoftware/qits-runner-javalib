package eu.wohlben.qits.runner.toolkit;

import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.runner.protocol.DomainCodec;
import eu.wohlben.qits.runner.protocol.RunnerCapabilities;
import eu.wohlben.qits.runner.protocol.RunnerMessage;
import java.util.List;
import java.util.Map;

/**
 * <b>What a runner is for</b> — the one interface an embedder implements. The toolkit owns the loop
 * ({@link RunnerRuntime}): registration, the bearer, the socket, the lifecycle frames, slots,
 * health. The domain owns its own frames and its own containers, and answers the two questions only
 * it can: whether it is quiescent enough to hand over to a successor, and what survives a session
 * start.
 *
 * <p>The order the runtime calls these in, per session: {@link #onSessionStart}, then {@link
 * #capabilities} and {@link #helloExtensions} go out in the {@code Hello}; at the {@code Ack},
 * {@link #onDropped} (for the carried items the host did not adopt), then {@link #onAck}; every
 * domain frame to {@link #onMessage}; and {@link #onSessionLost} when the socket drops. {@link
 * #onDecommission} once, when the runner learns it was deleted.
 *
 * @param <D> the runner's own sealed message set
 */
public interface RunnerDomain<D extends RunnerMessage> {

  /** The runner's own frames' codec; the toolkit wraps it in a {@code RunnerCodec}. */
  DomainCodec<D> codec();

  /**
   * What this host can do, as the registration and every {@code Hello} say it. {@code host} is the
   * toolkit's view; the domain adds its labels and extensions. The toolkit adds {@code
   * <labelRoot>.self-update=false} itself when self-update is off.
   */
  RunnerCapabilities capabilities(HostFacts host);

  /** The domain's own {@code Hello} keys, written after the lifecycle keys in their order. */
  default Map<String, Object> helloExtensions() {
    return Map.of();
  }

  /**
   * A session is starting: called before the {@code Hello}, off the event loop, with the work items
   * carried across a lost socket (empty on a first connection). The place to reconcile the domain's
   * own containers; nothing the domain has not released is ever swept by the toolkit.
   */
  void onSessionStart(RunnerSession session, List<String> carried);

  /** The host's {@code Ack}, after the toolkit's own handling of it. Off the event loop. */
  void onAck(Ack ack, RunnerSession session);

  /**
   * Carried items the host did not adopt: their slots are already freed, and their containers are
   * the domain's to remove. The session reserves nothing until this returns.
   */
  default void onDropped(List<String> workIds, RunnerSession session) {}

  /** Every domain frame, on a worker thread of a cached pool. */
  void onMessage(D message, RunnerSession session);

  /**
   * May the runner hand over to a successor NOW? CI answers "no held run"; a runner whose
   * containers outlive it answers "no operation in flight", because draining until it holds nothing
   * would never finish.
   */
  boolean quiescent();

  /** This runner was deleted: stop and remove the domain's own work before the helper runs. */
  void onDecommission(RunnerSession session);

  /**
   * Extra named resources the decommission helper removes, beyond what carries this runner's
   * labels. Each name must start with the identity's {@code <namePrefix>-}, or it is refused.
   */
  default List<TeardownItem> teardown() {
    return List.of();
  }

  /** The socket dropped: held items are carried, slots and backlog are zero until the next Ack. */
  default void onSessionLost() {}
}
