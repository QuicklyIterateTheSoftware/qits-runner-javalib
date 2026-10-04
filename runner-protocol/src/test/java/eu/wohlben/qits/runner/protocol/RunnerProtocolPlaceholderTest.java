package eu.wohlben.qits.runner.protocol;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

/** Proves the module compiles and surefire runs at least one test, until qits-857 replaces it. */
class RunnerProtocolPlaceholderTest {

    @Test
    void theModuleCompilesAndItsSuiteRuns() {
        assertNotNull(RunnerProtocolPlaceholder.class);
    }
}
