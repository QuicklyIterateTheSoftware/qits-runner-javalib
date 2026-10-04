package eu.wohlben.qits.runner.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The host's answer to {@link Hello}: its own capability version and the number of slots this
 * runner may fill at once.
 *
 * <p><b>{@code slots} is the host's number and it wins.</b> The runner advertises what its operator
 * configured, but the runner row on the host is what an admin edits, so the cap arrives here and
 * the runner never reserves past it. A runner that reads a capability version it does not know
 * exits rather than guessing. {@code slots} is not only the first {@code Ack}'s word either: the
 * host re-sends {@code Ack} whenever the admin-edited cap changes, and the runner picks up every
 * one.
 *
 * <p><b>{@link #adopted} answers {@link Hello#held()}</b>: which of the items the runner carried
 * across a lost socket the host is still driving, and so kept for it. Only the {@code Ack} that
 * answers a {@code Hello} carries it; {@code null} — absent, a host older than the field, or a
 * re-send — adopts nothing and says nothing about held items at all, which is not the same as an
 * empty list. Its wire name is the runner's ({@link RunnerVocabulary#adoptedField()}, CI's {@code
 * adoptedRuns}), and it is written only when non-null.
 *
 * <p><b>{@code extensions} are the host's runner-specific keys</b> (CI's {@code registryMirrors}),
 * written after {@code slots} in insertion order. A null value is written as a key with null —
 * which is how CI's {@code registryMirrors} says "not sent" — so the copy allows one. Decoding puts
 * every key the base does not know here, in arrival order.
 */
public record Ack(
    int capabilityVersion, int slots, List<String> adopted, Map<String, Object> extensions)
    implements LifecycleMessage {

  public Ack {
    adopted = adopted == null ? null : List.copyOf(adopted);
    extensions =
        extensions == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(extensions));
  }

  /** An {@code Ack} that says nothing about held items and carries no runner-specific key. */
  public Ack(int capabilityVersion, int slots) {
    this(capabilityVersion, slots, null, Map.of());
  }
}
