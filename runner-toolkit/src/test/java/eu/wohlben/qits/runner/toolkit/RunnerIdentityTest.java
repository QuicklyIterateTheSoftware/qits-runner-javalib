package eu.wohlben.qits.runner.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.protocol.RunnerVocabulary;
import org.junit.jupiter.api.Test;

/** The constructor belts: a shared or foreign root is refused with a sentence naming it. */
class RunnerIdentityTest {

  private static RunnerIdentity identity(String kind, String env, String label, String name) {
    return new RunnerIdentity(
        kind,
        env,
        label,
        name,
        "qits/qits-toy-runner",
        "/var/lib/qits-toy-runner",
        "/toy/api/runners/{id}/register",
        "qits-toy-runner",
        "2026.1.1",
        1,
        new RunnerVocabulary("heldItems", "adoptedItems"),
        "item");
  }

  private static String refusal(String kind, String env, String label, String name) {
    return assertThrows(IllegalArgumentException.class, () -> identity(kind, env, label, name))
        .getMessage();
  }

  @Test
  void theToyIdentityIsAccepted() {
    assertEquals("QITS_TOY_RUNNER_URL", Toy.IDENTITY.env("URL"));
    assertEquals("qits.toy.runner.self-update", Toy.IDENTITY.selfUpdateLabel());
    assertEquals("qits.toy.runner.", Toy.IDENTITY.driverNamespace());
    assertEquals("toy-runner", Toy.IDENTITY.logName());
    assertEquals("qits-toy", Toy.IDENTITY.hostName());
  }

  @Test
  void theContainersServicesLabelNamespaceIsRefusedAndSoIsAnythingUnderIt() {
    assertTrue(
        refusal("toy", "QITS_TOY_", "qits.containers", "qits-toy").contains("qits-containers"));
    assertTrue(
        refusal("toy", "QITS_TOY_", "qits.containers.runner", "qits-toy")
            .startsWith("labelRoot 'qits.containers.runner'"));
    // A root that merely starts with the same letters is somebody else's, and fine.
    identity("toy", "QITS_TOY_", "qits.containersfoo", "qits-toy");
  }

  @Test
  void aNamePrefixAnotherTenantOwnsIsRefused() {
    assertTrue(
        refusal("toy", "QITS_TOY_", "qits.toy", "qits-containers-runner")
            .contains("another tenant of the node"));
    assertTrue(
        refusal("toy", "QITS_TOY_", "qits.toy", "qits-buildkitd").contains("qits-buildkitd"));
  }

  @Test
  void everyRootOutsideItsGrammarIsRefusedNamingTheField() {
    assertTrue(refusal("Toy", "QITS_TOY_", "qits.toy", "qits-toy").startsWith("kind "));
    assertTrue(refusal("toy", "QITS_TOY", "qits.toy", "qits-toy").startsWith("envPrefix "));
    assertTrue(refusal("toy", "QITS_TOY_", "toy.runner", "qits-toy").startsWith("labelRoot "));
    assertTrue(refusal("toy", "QITS_TOY_", "qits", "qits-toy").startsWith("labelRoot "));
    assertTrue(refusal("toy", "QITS_TOY_", "qits.toy", "toy-runner").startsWith("namePrefix "));
  }

  @Test
  void aRegisterPathWithoutTheIdPlaceholderIsRefused() {
    IllegalArgumentException refused =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new RunnerIdentity(
                    "toy",
                    "QITS_TOY_",
                    "qits.toy",
                    "qits-toy",
                    "qits/qits-toy",
                    "/var/lib/qits-toy",
                    "/toy/api/runners/register",
                    "qits-toy",
                    "1",
                    1,
                    new RunnerVocabulary("heldItems", "adoptedItems"),
                    "item"));
    assertTrue(refused.getMessage().contains("{id}"), refused::getMessage);
  }
}
