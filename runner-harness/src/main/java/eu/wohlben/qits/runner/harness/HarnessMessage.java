package eu.wohlben.qits.runner.harness;

import eu.wohlben.qits.runner.protocol.RunnerMessage;

/**
 * The harness runner's own frames — the shape every runner fills in beside the lifecycle frames.
 * Sealed here, and extending the base's non-sealed {@link RunnerMessage}, because a {@code permits}
 * list cannot span artifacts; {@link HarnessCodec} is what {@code RunnerCodec} hands them to.
 */
public sealed interface HarnessMessage extends RunnerMessage
    permits HarnessMessage.Take,
        HarnessMessage.Started,
        HarnessMessage.Release,
        HarnessMessage.Released {

  /** Host → runner: run {@code image} for {@code itemId}; the item holds a slot from now. */
  record Take(String itemId, String image) implements HarnessMessage {}

  /** Runner → host: the item's container is running, as {@code containerId}. */
  record Started(String itemId, String containerId) implements HarnessMessage {}

  /** Host → runner: the item is done with; remove its container and free its slot. */
  record Release(String itemId) implements HarnessMessage {}

  /** Runner → host: the item's container is gone and its slot is free. */
  record Released(String itemId) implements HarnessMessage {}
}
