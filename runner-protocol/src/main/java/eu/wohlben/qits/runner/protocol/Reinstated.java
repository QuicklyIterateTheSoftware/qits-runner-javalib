package eu.wohlben.qits.runner.protocol;

/**
 * Host → runner: "the quarantine is lifted." Sent when a {@link Quarantined} runner is greenlit
 * again — either a person pressed the button on the host's runners page, or a health check the host
 * retried while quarantined passed. The host resumes handing the runner work on its own next {@link
 * Reserve}; this frame is for the log and the runner's own {@code quarantined} flag, the same way
 * {@link Quarantined} is. It is in the base because it is {@code Quarantined}'s other half.
 *
 * <p>Additive, like {@link Quarantined}: an older runner drops it as an unknown type and stays
 * connected.
 *
 * @param by {@code "admin"} or {@code "healthcheck"} — for the log only, nothing branches on it.
 */
public record Reinstated(String by) implements LifecycleMessage {}
