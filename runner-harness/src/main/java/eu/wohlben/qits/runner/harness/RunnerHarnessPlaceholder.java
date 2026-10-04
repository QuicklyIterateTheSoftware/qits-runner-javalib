package eu.wohlben.qits.runner.harness;

/**
 * Stands in for the harness runner and fake host that the "Proof" feature adds here to exercise
 * {@code qits-runner-toolkit} before qits-workspaces-runner-daemon does it live. Its only job is to
 * give this module a class to compile and a test to run, so the reactor's skeleton builds green
 * before any real content lands. Delete it once the real harness lands.
 */
public final class RunnerHarnessPlaceholder {

    private RunnerHarnessPlaceholder() {
    }
}
