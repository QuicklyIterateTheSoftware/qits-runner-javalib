package eu.wohlben.qits.runner.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.protocol.RunnerVocabulary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * The constructor belts — a shared or foreign root is refused with a sentence naming it — and the
 * derived names, which reproduce today's CI spellings exactly for a CI-shaped identity.
 */
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

  /** One argument of the canonical constructor replaced, the rest the toy's. */
  private static RunnerIdentity with(int index, Object value) {
    Object[] args = {
      "toy",
      "QITS_TOY_",
      "qits.toy.runner",
      "qits-toy-runner",
      "qits/qits-toy-runner",
      "/var/lib/qits-toy-runner",
      "/toy/api/runners/{id}/register",
      "qits-toy-runner",
      "2026.1.1",
      1,
      new RunnerVocabulary("heldItems", "adoptedItems"),
      "item"
    };
    args[index] = value;
    return new RunnerIdentity(
        (String) args[0],
        (String) args[1],
        (String) args[2],
        (String) args[3],
        (String) args[4],
        (String) args[5],
        (String) args[6],
        (String) args[7],
        (String) args[8],
        (Integer) args[9],
        (RunnerVocabulary) args[10],
        (String) args[11]);
  }

  @Test
  void everyBeltIsRefusedWithASentenceNamingWhatItRefused() {
    Map<String, Executable> belts = new LinkedHashMap<>();
    belts.put("kind", () -> with(0, "Toy"));
    belts.put("envPrefix", () -> with(1, "QITS_TOY"));
    belts.put("labelRoot", () -> with(2, "toy.runner"));
    belts.put("labelRoot 'qits.containers'", () -> with(2, "qits.containers"));
    belts.put("labelRoot 'qits.containers.x'", () -> with(2, "qits.containers.x"));
    belts.put("namePrefix", () -> with(3, "toy-runner"));
    belts.put("namePrefix 'qits-containers-x'", () -> with(3, "qits-containers-x"));
    belts.put("namePrefix 'qits-buildkitd-x'", () -> with(3, "qits-buildkitd-x"));
    belts.put("imageRepository", () -> with(4, "Qits/Toy"));
    belts.put("defaultStateDir", () -> with(5, "var/lib/toy"));
    belts.put("registerPath", () -> with(6, "toy/api"));
    belts.put(
        "registerPath '/toy/api/runners/register'", () -> with(6, "/toy/api/runners/register"));
    belts.put("serviceName", () -> with(7, "Qits-Toy"));
    belts.put("version", () -> with(8, "${project.version}"));
    belts.put("capabilityVersion", () -> with(9, 0));
    belts.put("vocabulary", () -> with(10, null));
    belts.put("workLabelSuffix", () -> with(11, "Item"));
    belts.put("purpose", () -> Toy.IDENTITY.nodeName("r1", "Dot Claude"));
    belts.put("purpose 'x/y'", () -> Toy.IDENTITY.nodeVolume("r1", "x/y"));
    belts.put("a derived name", () -> Toy.IDENTITY.stateVolume(""));
    List<String> unsaid = new ArrayList<>();
    belts.forEach(
        (what, belt) -> {
          String message = assertThrows(IllegalArgumentException.class, belt, what).getMessage();
          // A sentence: it opens with what was refused and says why in words, not a bare value.
          if (!message.startsWith(what) || message.split(" ").length < 4) {
            unsaid.add(what + " -> " + message);
          }
        });
    assertTrue(unsaid.isEmpty(), () -> String.join("\n", unsaid));
  }

  /**
   * Every row of qits-825's CI column, against today's qits-ci-runner-daemon literals ({@code
   * RunnerArgv.java:36-73}, the install script): qits-772 must be able to move the CI runner onto
   * this toolkit without one installed name changing.
   */
  @Test
  void theCiShapedIdentityDerivesTodaysCiSpellingsExactly() {
    RunnerIdentity ci = Shapes.CI;
    String id = "1a2b3c4d-5e6f-7a8b-9c0d-112233445566";
    assertEquals("qits.ci.runner", ci.ownerLabel());
    assertEquals("qits.ci.runner.run", ci.workLabel());
    assertEquals("qits.ci.runner.process", ci.processLabel());
    assertEquals("qits.ci.runner.version", ci.versionLabel());
    assertEquals("qits.ci.runner.decommission", ci.decommissionLabel());
    assertEquals("qits.ci.runner.self-update", ci.selfUpdateLabel());
    assertEquals("qits.ci.runner.", ci.driverNamespace());
    assertEquals("qits-ci-runner-1a2b3c4d-2026.1003.50846", ci.containerName(id, Shapes.VERSION));
    assertEquals("qits-ci-runner-1a2b3c4d-decommission", ci.helperName(id));
    assertEquals("qits-ci-runner-state-1a2b3c4d", ci.stateVolume(id));
    assertEquals("qits-ci-runner-buildkitd-1a2b3c4d", ci.nodeName(id, "buildkitd"));
    assertEquals("QITS_CI_RUNNER_URL", ci.env("URL"));
    assertEquals("/var/lib/qits-ci-runner", ci.defaultStateDir());
    assertEquals("qits/qits-ci-runner", ci.imageRepository());
    assertEquals("/ci/api/runners/" + id + "/register", ci.registerPath(id));
    assertEquals("ci-runner", ci.logName());
    assertEquals("qits-ci", ci.hostName());
    assertTrue(KnownRunnerNamespaces.CI.matches(ci), "the CI-shaped identity has CI's roots");
  }

  @Test
  void theWorkspacesShapedIdentityDerivesQits825sWorkspacesColumn() {
    RunnerIdentity ws = Shapes.WORKSPACES;
    String id = "1a2b3c4d-5e6f-7a8b-9c0d-112233445566";
    assertEquals("qits.workspaces.runner", ws.ownerLabel());
    assertEquals("qits.workspaces.runner.workspace", ws.workLabel());
    assertEquals("qits.workspaces.runner.process", ws.processLabel());
    assertEquals("qits.workspaces.runner.version", ws.versionLabel());
    assertEquals("qits.workspaces.runner.decommission", ws.decommissionLabel());
    assertEquals("qits.workspaces.runner.self-update", ws.selfUpdateLabel());
    assertEquals("qits.workspaces.runner.", ws.driverNamespace());
    assertEquals("qits-workspaces-runner-1a2b3c4d-2026.1", ws.containerName(id, "2026.1"));
    assertEquals("qits-workspaces-runner-1a2b3c4d-decommission", ws.helperName(id));
    assertEquals("qits-workspaces-runner-state-1a2b3c4d", ws.stateVolume(id));
    assertEquals("qits-workspaces-runner-dot-claude-1a2b3c4d", ws.nodeVolume(id, "dot-claude"));
    assertEquals("QITS_WORKSPACES_RUNNER_URL", ws.env("URL"));
    assertEquals("/var/lib/qits-workspaces-runner", ws.defaultStateDir());
    assertTrue(KnownRunnerNamespaces.WORKSPACES.matches(ws));
  }

  /**
   * qits-1524: the roots and derived names qits-system-runner-daemon (qits-1481) declares itself
   * with, against the exact values qits-1481's dossier "Identity" and "Derived names" give.
   */
  @Test
  void theSystemShapedIdentityDerivesQits1481sSystemColumn() {
    RunnerIdentity system = Shapes.SYSTEM;
    String id = "1a2b3c4d-5e6f-7a8b-9c0d-112233445566";
    assertEquals("qits.system.runner", system.ownerLabel());
    assertEquals("qits.system.runner.lease", system.workLabel());
    assertEquals("qits-system-runner-1a2b3c4d-2026.1", system.containerName(id, "2026.1"));
    assertEquals("qits-system-runner-state-1a2b3c4d", system.stateVolume(id));
    assertEquals("qits-system-runner-dot-claude-1a2b3c4d", system.nodeVolume(id, "dot-claude"));
    assertEquals("QITS_SYSTEM_RUNNER_URL", system.env("URL"));
    assertEquals("/var/lib/qits-system-runner", system.defaultStateDir());
    assertEquals("qits/qits-system-runner", system.imageRepository());
    assertEquals("/system/api/targets/" + id + "/register", system.registerPath(id));
    assertTrue(
        KnownRunnerNamespaces.ALL.contains(KnownRunnerNamespaces.SYSTEM),
        "SYSTEM must be listed in ALL");
    assertTrue(KnownRunnerNamespaces.SYSTEM.matches(system), "the system-shaped identity has SYSTEM's roots");
  }

  /**
   * qits-1098: the roots qits-projects-desk-runner-daemon (qits-767) declares itself with are
   * listed here, and a {@link RunnerIdentity} built from them is accepted by the same belts as any
   * other runner's.
   */
  @Test
  void theProjectsDeskRootsAreInAllAndBuildAnAcceptedIdentity() {
    assertTrue(
        KnownRunnerNamespaces.ALL.contains(KnownRunnerNamespaces.PROJECTS_DESK),
        "PROJECTS_DESK must be listed in ALL");
    KnownRunnerNamespaces.Root desk = KnownRunnerNamespaces.PROJECTS_DESK;
    RunnerIdentity deskIdentity =
        identity(desk.kind(), desk.envPrefix(), desk.labelRoot(), desk.namePrefix());
    assertTrue(desk.matches(deskIdentity), "the projects-desk-shaped identity has PROJECTS_DESK's roots");
  }

  @Test
  void noKnownNamespaceIsAPrefixOfAnother() {
    List<KnownRunnerNamespaces.Root> roots = KnownRunnerNamespaces.ALL;
    assertTrue(roots.size() >= 5, "ci, workspaces, projects-desk, harness and system at least");
    List<String> overlaps = new ArrayList<>();
    for (KnownRunnerNamespaces.Root a : roots) {
      // Each is a root an identity accepts: none is a tenant's, none is outside the grammar.
      with(0, a.kind());
      with(1, a.envPrefix());
      with(2, a.labelRoot());
      with(3, a.namePrefix());
      for (KnownRunnerNamespaces.Root b : roots) {
        if (a == b) {
          continue;
        }
        if (a.kind().equals(b.kind())) {
          overlaps.add("kind " + a.kind() + " twice");
        }
        if (b.labelRoot().startsWith(a.labelRoot())) {
          overlaps.add("label root " + a.labelRoot() + " is a prefix of " + b.labelRoot());
        }
        if (b.namePrefix().startsWith(a.namePrefix())) {
          overlaps.add("name prefix " + a.namePrefix() + " is a prefix of " + b.namePrefix());
        }
        if (b.envPrefix().startsWith(a.envPrefix())) {
          overlaps.add("env prefix " + a.envPrefix() + " is a prefix of " + b.envPrefix());
        }
      }
    }
    assertTrue(overlaps.isEmpty(), () -> String.join("\n", overlaps));
  }
}
