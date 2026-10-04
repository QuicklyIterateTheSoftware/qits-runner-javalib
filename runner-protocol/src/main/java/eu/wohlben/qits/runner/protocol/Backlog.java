package eu.wohlben.qits.runner.protocol;

/**
 * Host → runner: how much work is queued for runners of this kind, pushed whenever that number
 * changes and once after {@link Ack}. It is what lets a runner learn of work without polling, and
 * it is a hint rather than a promise: a positive backlog is a reason to {@link Reserve}, never a
 * guarantee the answer is work — other runners and the host's own workers compete for the same
 * rows.
 */
public record Backlog(int queued) implements LifecycleMessage {}
