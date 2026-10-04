package eu.wohlben.qits.runner.toolkit;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

/** Proves the module compiles and surefire runs at least one test, until later tasks replace it. */
class RunnerToolkitPlaceholderTest {

    @Test
    void theModuleCompilesAndItsSuiteRuns() {
        assertNotNull(RunnerToolkitPlaceholder.class);
    }
}
