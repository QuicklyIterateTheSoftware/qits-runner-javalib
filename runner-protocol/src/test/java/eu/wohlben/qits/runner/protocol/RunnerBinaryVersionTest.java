package eu.wohlben.qits.runner.protocol;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The pin resolves — and refuses what an embedder's broken build looks like. Carried from
 * qits-ci-runner-daemon's {@code CiRunnerBinaryTest}: it asserts the shape and not a value, since
 * the version is whatever stamped this tree, a CalVer after a release and a {@code -SNAPSHOT}
 * before the first one. The filtered resource is this module's own test resource, filtered exactly
 * as an embedder's protocol jar filters its main one.
 */
class RunnerBinaryVersionTest {

  @Test
  void theVersionIsFilteredInAndIsACalVerOrASnapshot() {
    String version =
        RunnerBinaryVersion.read(RunnerBinaryVersionTest.class, "filtered-binary.properties");
    assertFalse(version.isBlank(), "the version is blank");
    assertFalse(
        version.startsWith("$"),
        () -> "an unfiltered ${project.version} reached the jar: " + version);
    assertTrue(
        version.matches("[0-9][0-9.]*[0-9](-SNAPSHOT)?"),
        () -> "not a CalVer — is resource filtering still on? got: " + version);
  }

  @Test
  void anUnfilteredBlankOrMissingVersionIsRefusedRatherThanDefaulted() {
    assertTrue(
        assertThrows(
                IllegalStateException.class,
                () ->
                    RunnerBinaryVersion.read(
                        RunnerBinaryVersionTest.class, "unfiltered-binary.properties"))
            .getMessage()
            .contains("${project.version}"));
    assertThrows(
        IllegalStateException.class,
        () -> RunnerBinaryVersion.read(RunnerBinaryVersionTest.class, "blank-binary.properties"));
    assertThrows(
        IllegalStateException.class,
        () -> RunnerBinaryVersion.read(RunnerBinaryVersionTest.class, "no-such-binary.properties"));
  }
}
