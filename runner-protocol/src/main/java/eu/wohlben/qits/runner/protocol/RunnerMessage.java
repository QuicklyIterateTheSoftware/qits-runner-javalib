package eu.wohlben.qits.runner.protocol;

/**
 * Every frame on a runner's control socket: the base's {@link LifecycleMessage}s and each runner's
 * own frames.
 *
 * <p><b>Deliberately not sealed.</b> A {@code sealed … permits} list cannot name a class in another
 * artifact (the unnamed module confines it to this package), so a runner's own sealed set — CI's
 * {@code take}/{@code launch}/{@code reaped}, a workspace runner's {@code estate}/{@code take} —
 * extends this interface instead, and its {@link DomainCodec} is handed to {@link RunnerCodec}
 * rather than inherited. Each side still {@code switch}es exhaustively: over {@link
 * LifecycleMessage} for the base's frames, over its own sealed set for the rest.
 */
public interface RunnerMessage {}
