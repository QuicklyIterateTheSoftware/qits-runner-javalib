package eu.wohlben.qits.runner.protocol;

/**
 * Stands in for the lifecycle frames and {@code RunnerCodec} that qits-857 moves here from
 * qits-ci-runner-daemon's {@code ci-runner-protocol}. Its only job is to give this module a class
 * to compile and a test to run, so the reactor's skeleton builds green before any real content
 * lands. Delete it once qits-857 adds the real types.
 */
public final class RunnerProtocolPlaceholder {

    private RunnerProtocolPlaceholder() {
    }
}
