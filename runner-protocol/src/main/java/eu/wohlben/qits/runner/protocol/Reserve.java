package eu.wohlben.qits.runner.protocol;

/**
 * Runner → host: "I have a free slot; claim me some work." The host answers with exactly one of the
 * runner's own domain frames (CI's {@code take}) or {@link Nothing}. A runner has at most one
 * {@code Reserve} outstanding, so the answer needs no correlation id.
 */
public record Reserve() implements LifecycleMessage {}
