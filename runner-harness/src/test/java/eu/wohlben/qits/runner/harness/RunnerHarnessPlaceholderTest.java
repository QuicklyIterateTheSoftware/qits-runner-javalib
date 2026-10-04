package eu.wohlben.qits.runner.harness;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

/** Proves the module compiles and surefire runs at least one test, until the real harness lands. */
class RunnerHarnessPlaceholderTest {

    @Test
    void theModuleCompilesAndItsSuiteRuns() {
        assertNotNull(RunnerHarnessPlaceholder.class);
    }
}
