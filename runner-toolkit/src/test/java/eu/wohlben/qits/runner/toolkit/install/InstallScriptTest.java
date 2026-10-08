package eu.wohlben.qits.runner.toolkit.install;

import static eu.wohlben.qits.runner.toolkit.install.Identities.HARNESS;
import static eu.wohlben.qits.runner.toolkit.install.Identities.HARNESS_PAGE;
import static eu.wohlben.qits.runner.toolkit.install.Identities.REGISTRY;
import static eu.wohlben.qits.runner.toolkit.install.Identities.WORKSPACES;
import static eu.wohlben.qits.runner.toolkit.install.Identities.WORKSPACES_PAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The two halves of an install as text: the generic script — every placeholder filled from the
 * identity, no token and no runner in it, {@code sh -n} happy with it, and nothing of one runner's
 * namespace in another's — and the install line, which carries the token exactly twice. What the
 * script does when it runs is {@link InstallScriptContractTest}'s.
 */
class InstallScriptTest {

  private static final String TOKEN = "qits_tok_FIXTURE";

  private static final String ID = "00000000-0000-0000-0000-000000000001";

  @Test
  void everyPlaceholderIsFilledAndNoneRemains() {
    String script = InstallScript.script(WORKSPACES, WORKSPACES_PAGE, REGISTRY);

    assertFalse(script.contains("{{"), script);
    assertFalse(script.contains("}}"), script);
    assertTrue(script.startsWith("#!/bin/sh\n"));
    assertTrue(script.contains("\nset -eu\n"));
    assertTrue(
        script.contains("  image='registry.qits.example.org/qits/qits-workspaces-runner:"
            + Identities.VERSION
            + "'\n"),
        script);
    for (String name :
        List.of(
            "QITS_WORKSPACES_RUNNER_URL",
            "QITS_WORKSPACES_RUNNER_ID",
            "QITS_WORKSPACES_RUNNER_REGISTRATION_TOKEN",
            "QITS_WORKSPACES_RUNNER_SLOTS")) {
      assertTrue(script.contains("require " + name + " \"${" + name + ":-}\""), name);
    }
    assertTrue(script.contains("name=\"qits-workspaces-runner-$id8-$version\""), script);
    assertTrue(script.contains("volume=\"qits-workspaces-runner-state-$id8\""), script);
    assertTrue(
        script.contains("--filter \"label=qits.workspaces.runner.process=$QITS_WORKSPACES_RUNNER_ID\""),
        script);
    assertTrue(script.contains("-f /var/lib/qits-workspaces-runner/client.json"), script);
    assertTrue(script.contains("paste the whole line from the Workspaces UI's Runners page."));
    assertTrue(
        script.contains(
            "for tuning in QITS_WORKSPACES_RUNNER_DOCKER_TIMEOUT"
                + " QITS_WORKSPACES_RUNNER_PULL_TIMEOUT"
                + " QITS_WORKSPACES_RUNNER_ROLLOVER_TIMEOUT; do"),
        script);
    assertTrue(
        script.contains("echo \"qits-workspaces-runner started; watch: docker logs -f $name\""));
    // Nothing of the CI template it was derived from is left.
    for (String ci :
        List.of(
            "QITS_CI_RUNNER_", "qits-ci-runner", "qits.ci.runner", "/var/lib/qits-ci-runner", "CI UI")) {
      assertFalse(script.contains(ci), ci);
    }
    assertFalse(script.contains("systemctl"), script);
    assertFalse(script.contains("systemd"), script);
    // Everything runs from the last line, so a download cut short runs nothing.
    assertTrue(script.endsWith("\nmain \"$@\"\n"), script);

    // A page's own tuning follows the three every runner reads.
    assertTrue(
        InstallScript.script(HARNESS, HARNESS_PAGE, REGISTRY)
            .contains(
                "for tuning in QITS_HARNESS_RUNNER_DOCKER_TIMEOUT QITS_HARNESS_RUNNER_PULL_TIMEOUT"
                    + " QITS_HARNESS_RUNNER_ROLLOVER_TIMEOUT"
                    + " QITS_HARNESS_RUNNER_BUILDKIT_HTTP_REGISTRIES; do"));
  }

  @Test
  void aMissingPlaceholderInTheTemplateIsAnError() throws IOException {
    String template = template();
    for (String placeholder :
        List.of(
            "IMAGE", "ENV_PREFIX", "NAME_PREFIX", "LABEL_ROOT", "STATE_DIR", "PAGE", "TUNING")) {
      String marker = "{{" + placeholder + "}}";
      assertTrue(template.contains(marker), marker);
      String without = template.replace(marker, "x");

      IllegalStateException refused =
          assertThrows(
              IllegalStateException.class,
              () -> InstallScript.render(without, WORKSPACES, WORKSPACES_PAGE, REGISTRY),
              marker);
      assertTrue(refused.getMessage().contains(marker), refused.getMessage());
    }

    // And a placeholder nothing fills is one too: no `{{` leaves the renderer.
    IllegalStateException unfilled =
        assertThrows(
            IllegalStateException.class,
            () ->
                InstallScript.render(
                    template + "# {{RUNNER_ID}}\n", WORKSPACES, WORKSPACES_PAGE, REGISTRY));
    assertTrue(unfilled.getMessage().contains("nothing fills"), unfilled.getMessage());
  }

  @Test
  void theTokenAppearsOnlyInTheLineNeverInTheScript() {
    String script = InstallScript.script(WORKSPACES, WORKSPACES_PAGE, REGISTRY);
    String line =
        InstallScript.line(
            WORKSPACES, "https://workspaces.qits.example.org/", "/workspaces/api/runners/install.sh",
            ID, TOKEN, 2);

    assertEquals(
        "curl -fsSL -H 'Authorization: Bearer qits_tok_FIXTURE'"
            + " https://workspaces.qits.example.org/workspaces/api/runners/install.sh"
            + " | sudo env QITS_WORKSPACES_RUNNER_URL='https://workspaces.qits.example.org'"
            + " QITS_WORKSPACES_RUNNER_ID='00000000-0000-0000-0000-000000000001'"
            + " QITS_WORKSPACES_RUNNER_REGISTRATION_TOKEN='qits_tok_FIXTURE'"
            + " QITS_WORKSPACES_RUNNER_SLOTS='2' sh",
        line);
    assertFalse(line.contains("\n"), "one line");
    assertEquals(2, occurrences(line, TOKEN), "the token appears twice in the line");
    assertFalse(script.contains(TOKEN), script);
    assertFalse(script.contains("qits_tok_"), script);
    assertFalse(script.contains(ID), "the script names no runner");

    // A drained row still renders a slot the runner accepts.
    assertTrue(
        InstallScript.line(
                WORKSPACES, "https://w.example.org", "/workspaces/api/runners/install.sh", ID,
                TOKEN, 0)
            .contains(" QITS_WORKSPACES_RUNNER_SLOTS='1' sh"));
  }

  @Test
  void aTokenTheLineCannotCarryIsRefused() {
    for (String token :
        List.of("qits_tok_'x", "qits_tok_ x", "qits_tok_$x", "qits_tok_\nx", "qits_tok_{x}", "",
            "a".repeat(1025))) {
      assertThrows(
          IllegalArgumentException.class,
          () -> InstallScript.requireCarriable(token),
          () -> "token " + token);
      assertThrows(
          IllegalArgumentException.class,
          () ->
              InstallScript.line(
                  WORKSPACES, "https://w.example.org", "/workspaces/api/runners/install.sh", ID,
                  token, 2),
          () -> "line token " + token);
    }
    assertThrows(IllegalArgumentException.class, () -> InstallScript.requireCarriable(null));
    InstallScript.requireCarriable("qits_tok_AZaz09._~+/=-");
    InstallScript.requireCarriable("a".repeat(1024));
  }

  @Test
  void aShapeTheScriptCannotCarryIsRefused() {
    for (String url :
        List.of("http://dev-qits-w:8080'; rm -rf /", "ftp://dev-qits-w", "http://h/{{X}}", "")) {
      assertThrows(
          IllegalStateException.class,
          () ->
              InstallScript.line(
                  WORKSPACES, url, "/workspaces/api/runners/install.sh", ID, TOKEN, 2),
          () -> "url " + url);
      assertThrows(
          IllegalStateException.class,
          () -> InstallScript.script(WORKSPACES, WORKSPACES_PAGE, url),
          () -> "registry " + url);
    }
    assertThrows(
        IllegalStateException.class,
        () -> InstallScript.line(WORKSPACES, "https://w.example.org", "install.sh", ID, TOKEN, 2));
    for (String id : List.of("", "a b", "x'", "-x")) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              InstallScript.line(
                  WORKSPACES, "https://w.example.org", "/install.sh", id, TOKEN, 2),
          () -> "runner id " + id);
    }
    // A version a tag cannot carry: the identity takes it, the script does not.
    assertThrows(
        IllegalStateException.class,
        () -> InstallScript.script(withVersion(WORKSPACES, "1.0+b"), WORKSPACES_PAGE, REGISTRY));
    // A page name a double-quoted refusal would expand, and tuning the script sets itself.
    for (String page : List.of("the $HOME page", "the `id` page", "the \"x\" page", "")) {
      assertThrows(
          IllegalStateException.class,
          () -> new InstallScript.InstallPage(page, List.of()),
          () -> "page " + page);
    }
    for (String tuning : List.of("REGISTRATION_TOKEN", "STATE_DIR", "lower", "X Y")) {
      assertThrows(
          IllegalStateException.class,
          () -> new InstallScript.InstallPage("the page", List.of(tuning)),
          () -> "tuning " + tuning);
    }
  }

  @Test
  void theRenderedScriptParses(@TempDir Path dir) throws Exception {
    Path script = dir.resolve("runner-install.sh");
    Files.writeString(script, InstallScript.script(HARNESS, HARNESS_PAGE, REGISTRY));
    Path line = dir.resolve("line.sh");
    Files.writeString(
        line,
        InstallScript.line(
                HARNESS, "https://harness.qits.example.org", "/harness/api/runners/install.sh", ID,
                TOKEN, 2)
            + "\n");

    assertParses(script);
    assertParses(line);
  }

  @Test
  void twoIdentitiesRenderTwoDisjointScripts() {
    String harness = InstallScript.script(HARNESS, HARNESS_PAGE, REGISTRY);
    String workspaces = InstallScript.script(WORKSPACES, WORKSPACES_PAGE, REGISTRY);
    String harnessLine =
        InstallScript.line(HARNESS, "https://h.example.org", "/install.sh", ID, TOKEN, 2);
    String workspacesLine =
        InstallScript.line(WORKSPACES, "https://w.example.org", "/install.sh", ID, TOKEN, 2);

    assertDisjoint(HARNESS, workspaces + workspacesLine);
    assertDisjoint(WORKSPACES, harness + harnessLine);
  }

  /** No label, name, variable, path or image of {@code identity} appears in {@code text}. */
  private static void assertDisjoint(RunnerIdentity identity, String text) {
    for (String own :
        List.of(
            identity.envPrefix(),
            identity.labelRoot(),
            identity.namePrefix(),
            identity.imageRepository(),
            identity.defaultStateDir())) {
      assertTrue(own.length() > 5, own);
      assertFalse(text.contains(own), () -> own + " appears in another runner's script:\n" + text);
    }
  }

  private static void assertParses(Path file) throws Exception {
    Process process =
        new ProcessBuilder("sh", "-n", file.toString()).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(process.waitFor(30, TimeUnit.SECONDS), "sh -n did not finish");
    assertEquals(0, process.exitValue(), file + ": " + output);
  }

  private static RunnerIdentity withVersion(RunnerIdentity identity, String version) {
    return new RunnerIdentity(
        identity.kind(),
        identity.envPrefix(),
        identity.labelRoot(),
        identity.namePrefix(),
        identity.imageRepository(),
        identity.defaultStateDir(),
        identity.registerPath(),
        identity.serviceName(),
        version,
        identity.capabilityVersion(),
        identity.vocabulary(),
        identity.workLabelSuffix());
  }

  static String template() throws IOException {
    try (InputStream in = InstallScript.class.getResourceAsStream(InstallScript.TEMPLATE)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static int occurrences(String text, String needle) {
    int count = 0;
    for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
      count++;
    }
    return count;
  }
}
