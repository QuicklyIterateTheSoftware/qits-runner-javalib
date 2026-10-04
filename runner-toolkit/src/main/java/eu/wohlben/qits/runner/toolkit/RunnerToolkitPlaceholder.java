package eu.wohlben.qits.runner.toolkit;

/**
 * Stands in for the toolkit's embedder contract ({@code RunnerIdentity}, {@code RunnerDomain},
 * register/bearer/socket/slots/self-update/decommission/health/GC) that later tasks in this epic
 * move here from qits-ci-runner-daemon. Its only job is to give this module a class to compile and
 * a test to run, so the reactor's skeleton builds green before any real content lands. Delete it
 * once the real types land.
 */
public final class RunnerToolkitPlaceholder {

    private RunnerToolkitPlaceholder() {
    }
}
