package eu.wohlben.qits.runner.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The first frame a runner sends after the upgrade: which binary it is, which protocol it speaks,
 * how many slots its operator configured it for and what its host can do. The host answers with
 * {@link Ack}.
 *
 * <p>{@code slots} is the operator's number and it is advisory: the runner row on the host is what
 * an admin edits, and {@link Ack#slots()} is the cap the runner obeys. It is on the wire so the
 * host can show — and an admin can see — a runner whose machine was set up for fewer slots than the
 * row grants it.
 *
 * <p><b>Identity is not in it.</b> The connection was authenticated by the bearer on the upgrade,
 * minted from the client this runner registered — the host knows which runner this is before it
 * reads a frame, and a runner id here would only be a claim to check against that.
 *
 * <p><b>{@code runnerVersion} is FROZEN</b> — the field name {@code "runnerVersion"}, a string, the
 * binary's version. It is what a host compares with its pin to decide on {@link Upgrade}, so a
 * runner of any age must keep being recognisable by it; see {@link Upgrade}.
 *
 * <p><b>{@code held} is what a runner that lost its socket still holds</b> — the work it took on an
 * earlier connection of this same process, kept through the blip — and is empty on a runner's first
 * connection. It is a claim, not a request: the host keeps what it is still waiting on the runner
 * for and names it in {@link Ack#adopted()}, and everything else the runner claimed is its own to
 * cancel. Its wire name is the runner's ({@link RunnerVocabulary#heldField()}, CI's {@code
 * heldRuns}), and it is written only when non-empty, so a first connection's {@code Hello} is the
 * frame it always was.
 *
 * <p><b>{@code extensions} are the runner's own keys</b>, written after the lifecycle keys and
 * before the held list, in their insertion order; decoding puts every key the base does not know
 * here, in arrival order. Unmodifiable, ordered, and a null value is a key with null.
 */
public record Hello(
    String runnerVersion,
    int capabilityVersion,
    int slots,
    RunnerCapabilities capabilities,
    List<String> held,
    Map<String, Object> extensions)
    implements LifecycleMessage {

  public Hello {
    held = held == null ? List.of() : List.copyOf(held);
    extensions =
        extensions == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(extensions));
  }

  /** A first connection with no runner-specific keys: nothing carried across. */
  public Hello(
      String runnerVersion, int capabilityVersion, int slots, RunnerCapabilities capabilities) {
    this(runnerVersion, capabilityVersion, slots, capabilities, List.of(), Map.of());
  }
}
