package eu.wohlben.qits.runner.toolkit.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.toolkit.contracts.RunnerToolkitPact.Feature;
import eu.wohlben.qits.runner.toolkit.contracts.usage.UsesBearer;
import eu.wohlben.qits.runner.toolkit.contracts.usage.UsesLauncher;
import eu.wohlben.qits.runner.toolkit.contracts.usage.UsesNoCall;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.AssertionFailedError;

/** The guard: a daemon's declared features are exactly those its main classes can send. */
class ToolkitUsageTest {

  @TempDir Path classes;

  /** A directory holding only {@code type}'s compiled class, as a daemon's target/classes would. */
  private Path compiled(Class<?> type) throws Exception {
    String file = type.getName().replace('.', '/') + ".class";
    Path source = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).resolve(file);
    Path target = classes.resolve(file);
    Files.createDirectories(target.getParent());
    Files.copy(source, target);
    return classes;
  }

  @Test
  void theLauncherWiresAllThreeCalls() throws Exception {
    assertEquals(EnumSet.allOf(Feature.class), ToolkitUsage.used(compiled(UsesLauncher.class)));
  }

  @Test
  void aBearerAloneIsTheTokenCall() throws Exception {
    Path dir = compiled(UsesBearer.class);
    assertEquals(Set.of("Bearer"), ToolkitUsage.referenced(dir));
    assertEquals(EnumSet.of(Feature.TOKEN), ToolkitUsage.used(dir));
  }

  @Test
  void aToolkitClassThatMakesNoCallRequiresNothing() throws Exception {
    assertTrue(ToolkitUsage.used(compiled(UsesNoCall.class)).isEmpty());
  }

  @Test
  void thisModulesOwnDrivesReachAllThreeCalls() {
    assertEquals(EnumSet.allOf(Feature.class), ToolkitUsage.used(RunnerToolkitPacts.MAIN_CLASSES));
  }

  @Test
  void aDaemonOnTheLauncherThatDeclaresAllThreePasses() throws Exception {
    RunnerToolkitPact.forConsumer("qits-selftest-runner-daemon")
        .token()
        .logExport()
        .register(RunnerToolkitPact.WORKSPACES, "registerRunner")
        .assertDeclaresWhatTheMainClassesUse(compiled(UsesLauncher.class));
  }

  @Test
  void anUndeclaredCallFailsTheGuardNamingIt() throws Exception {
    Path dir = compiled(UsesLauncher.class);
    AssertionFailedError failed =
        assertThrows(
            AssertionFailedError.class,
            () ->
                RunnerToolkitPact.forConsumer("qits-selftest-runner-daemon")
                    .token()
                    .assertDeclaresWhatTheMainClassesUse(dir));
    assertTrue(failed.getMessage().contains("LOG_EXPORT"), failed.getMessage());
    assertTrue(failed.getMessage().contains("REGISTER"), failed.getMessage());
  }

  @Test
  void aDeclaredCallTheDaemonNeverMakesFailsTheGuard() throws Exception {
    Path dir = compiled(UsesBearer.class);
    AssertionFailedError failed =
        assertThrows(
            AssertionFailedError.class,
            () ->
                RunnerToolkitPact.forConsumer("qits-selftest-runner-daemon")
                    .token()
                    .logExport()
                    .assertDeclaresWhatTheMainClassesUse(dir));
    assertTrue(failed.getMessage().contains("declares [LOG_EXPORT]"), failed.getMessage());
  }

  @Test
  void aMissingClassesDirectoryFailsRatherThanPassingEmpty() {
    assertThrows(
        AssertionFailedError.class,
        () ->
            RunnerToolkitPact.forConsumer("qits-selftest-runner-daemon")
                .assertDeclaresWhatTheMainClassesUse(classes.resolve("absent")));
  }
}
