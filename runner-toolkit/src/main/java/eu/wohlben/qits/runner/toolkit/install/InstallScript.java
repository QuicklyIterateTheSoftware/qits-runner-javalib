package eu.wohlben.qits.runner.toolkit.install;

import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.runner.toolkit.RunnerSettings;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * How a runner is installed, in two parts that never meet on the server's side: the <b>generic
 * script</b> and the <b>install line</b>. Rendered by each server a runner registers with, for its
 * own {@link RunnerIdentity}, so no server keeps a copy of the template — qits-ci-service's {@code
 * runnerhost/RunnerInstallScript}, moved here without CDI and with every CI spelling replaced by a
 * name the identity derives.
 *
 * <p>The generic script is {@code runner-install.sh.tmpl} with the pinned runner image and the
 * identity's roots filled in, and nothing else: no runner id, no token, no secret. The server serves
 * it at a door of its own ({@code GET <service>/api/runners/install.sh}). It logs in to the image's
 * registry with the registration token, pulls the image, removes every container of the runner and
 * starts one ({@code docker run -d}) with the four values. The install line is what a server answers
 * a create or a rotation with: one line to paste, which fetches that script with the registration
 * token and pipes it into {@code sudo env <the four values> sh}. Piped, the script runs in a process
 * of its own, so its {@code set -eu} refusing ends that process and never the shell the line was
 * pasted into. The token is in the line twice, as the fetch's bearer and as the script's value, and
 * in no other body anywhere.
 *
 * <p><b>The template's {@code docker run} is the container contract</b>, the same one {@code
 * LifecycleArgv.runSuccessor} rolls a runner over into. Both sit in this artifact, and {@code
 * InstallScriptContractTest} runs the rendered script against a stub docker and compares the two
 * element for element — no fixture copy refreshed by hand in another repository.
 *
 * <p><b>Every value lands inside single quotes</b> — in the line, and in the script's own
 * assignment — and in an argument of {@code docker}, so each is held to a charset that is literal
 * in all of them: no quote, backslash, whitespace, {@code $} or brace. A value outside it is refused
 * here rather than rendered into something that parses differently on a root shell. The token is
 * checked by {@link #requireCarriable(String)}, which a server calls straight after minting it, while
 * it can still be given back. The script checks the four values again on the host, since there they
 * arrive from an environment rather than from this class.
 *
 * <p><b>Pure {@code java.*}, by rule.</b> A server links this package and {@link RunnerIdentity}
 * from the toolkit jar with a {@code *:*} exclusion, so neither Vert.x nor the containers driver nor
 * a logging API reaches a Quarkus service; {@code InstallPackageIsDependencyFreeTest} keeps it so.
 */
public final class InstallScript {

  /** The template, beside this class on the classpath (a native server lists it as a resource). */
  static final String TEMPLATE = "runner-install.sh.tmpl";

  private static final Pattern URL =
      Pattern.compile("https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?(/[A-Za-z0-9._~/-]*)?");

  /** {@code qits_tok_} plus base64 or base64url, and nothing a quote or a line could trip on. */
  private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._~+/=-]{1,1024}");

  /** A docker tag: what the pinned version becomes in the image reference. */
  private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]{0,127}");

  /** A registry authority, {@code host[:port]}, as docker reads the first segment of a reference. */
  private static final Pattern REGISTRY = Pattern.compile("[A-Za-z0-9.-]+(:[0-9]{1,5})?");

  /** The script's door below the service base. */
  private static final Pattern SCRIPT_PATH = Pattern.compile("/[A-Za-z0-9._~/-]*");

  /** A runner id as the server's row spells it; it becomes a label value and a container name. */
  private static final Pattern RUNNER_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

  /** A tuning variable's name without the prefix. */
  private static final Pattern TUNING_NAME = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

  /**
   * A page as a person names it. It lands inside a double-quoted refusal and in comments, so
   * nothing a shell expands there: no {@code $}, backquote, backslash or double quote.
   */
  private static final Pattern PAGE_NAME = Pattern.compile("[A-Za-z0-9 .,'()-]{1,120}");

  /**
   * The values the script sets itself — and {@code STATE_DIR}, which would move the runner's state
   * off the volume the script mounts. None of them is an operator's tuning.
   */
  private static final Set<String> NOT_TUNING =
      Set.of(
          RunnerSettings.URL,
          RunnerSettings.ID,
          RunnerSettings.SLOTS,
          RunnerSettings.REGISTRATION_TOKEN,
          RunnerSettings.STATE_DIR);

  /** The tuning every runner reads, ahead of a page's own. */
  private static final List<String> BASE_TUNING =
      List.of(RunnerSettings.DOCKER_TIMEOUT, RunnerSettings.ROLLOVER_TIMEOUT);

  private static final String TEXT = load();

  private InstallScript() {}

  /**
   * Where a runner's install line comes from, as the script names it to a person, and the tuning
   * variables (without the identity's prefix) an operator may add to the line beyond {@code
   * DOCKER_TIMEOUT} and {@code ROLLOVER_TIMEOUT} — qits-ci's would be the {@code BUILDKIT_*} ones.
   *
   * @param name e.g. {@code the Workspaces UI's Runners page}
   * @param tuning e.g. {@code BUILDKIT_HTTP_REGISTRIES}; may be empty
   */
  public record InstallPage(String name, List<String> tuning) {
    public InstallPage {
      require(PAGE_NAME, name, "the install page's name");
      tuning = List.copyOf(tuning == null ? List.of() : tuning);
      for (String variable : tuning) {
        require(TUNING_NAME, variable, "the tuning variable");
        if (NOT_TUNING.contains(variable)) {
          throw new IllegalStateException(
              "A runner install script sets " + variable + " itself; it is not a tuning variable");
        }
      }
    }
  }

  /**
   * The generic script for {@code identity}, naming its pinned image on {@code registryHost}: {@code
   * <registryHost>/<imageRepository>:<identity.version>}, composed exactly as the server composes
   * an {@code Upgrade}'s image.
   *
   * @throws IllegalStateException when the registry host or the identity's version cannot be
   *     carried, or the template has a placeholder nothing fills
   */
  public static String script(RunnerIdentity identity, InstallPage page, String registryHost) {
    return render(TEXT, identity, page, registryHost);
  }

  /**
   * The one line to paste, dialling {@code serviceBase + scriptPath} for the script. Slots are at
   * least 1: a drained row (0) is still a runner that should connect, the runner refuses {@code
   * <prefix>SLOTS=0} outright, and the row's own number is what holds it once it says hello whatever
   * its env says.
   *
   * @throws IllegalStateException when the service base or the script path cannot be carried
   * @throws IllegalArgumentException when the token cannot be carried or the runner id is missing
   */
  public static String line(
      RunnerIdentity identity,
      String serviceBase,
      String scriptPath,
      String runnerId,
      String registrationToken,
      int slots) {
    require(URL, serviceBase, "the service base");
    require(SCRIPT_PATH, scriptPath, "the script path");
    requireCarriable(registrationToken);
    if (runnerId == null || !RUNNER_ID.matcher(runnerId).matches()) {
      throw new IllegalArgumentException(
          "A runner install line needs the runner's id, not '" + runnerId + "'");
    }
    String base = serviceBase.replaceAll("/+$", "");
    return "curl -fsSL -H 'Authorization: Bearer "
        + registrationToken
        + "' "
        + base
        + scriptPath
        + " | sudo env "
        + identity.env(RunnerSettings.URL)
        + "='"
        + base
        + "' "
        + identity.env(RunnerSettings.ID)
        + "='"
        + runnerId
        + "' "
        + identity.env(RunnerSettings.REGISTRATION_TOKEN)
        + "='"
        + registrationToken
        + "' "
        + identity.env(RunnerSettings.SLOTS)
        + "='"
        + Math.max(1, slots)
        + "' sh";
  }

  /** Refuses a token the identity provider minted that the line could not carry verbatim. */
  public static void requireCarriable(String registrationToken) {
    if (registrationToken == null || !TOKEN.matcher(registrationToken).matches()) {
      throw new IllegalArgumentException(
          "the identity provider answered a registration token the install line cannot carry"
              + " verbatim");
    }
  }

  /** {@link #script} over any template text — the seam the template's own belts are tested at. */
  static String render(
      String template, RunnerIdentity identity, InstallPage page, String registryHost) {
    require(REGISTRY, registryHost, "the registry host");
    require(VERSION, identity.version(), "the runner version");
    List<String> tuning = new ArrayList<>();
    for (String variable : new LinkedHashSet<>(concat(BASE_TUNING, page.tuning()))) {
      tuning.add(identity.env(variable));
    }
    String script = template;
    script =
        fill(
            script,
            "IMAGE",
            registryHost + "/" + identity.imageRepository() + ":" + identity.version());
    script = fill(script, "ENV_PREFIX", identity.envPrefix());
    script = fill(script, "NAME_PREFIX", identity.namePrefix());
    script = fill(script, "LABEL_ROOT", identity.labelRoot());
    script = fill(script, "STATE_DIR", identity.defaultStateDir());
    script = fill(script, "PAGE", page.name());
    script = fill(script, "TUNING", String.join(" ", tuning));
    if (script.contains("{{")) {
      throw new IllegalStateException(TEMPLATE + " has a placeholder nothing fills");
    }
    return script;
  }

  private static List<String> concat(List<String> first, List<String> second) {
    List<String> out = new ArrayList<>(first);
    out.addAll(second);
    return out;
  }

  private static String fill(String script, String name, String value) {
    String marker = "{{" + name + "}}";
    if (!script.contains(marker)) {
      throw new IllegalStateException(TEMPLATE + " has no " + marker);
    }
    return script.replace(marker, value);
  }

  private static void require(Pattern shape, String value, String what) {
    if (value == null || !shape.matcher(value).matches()) {
      throw new IllegalStateException(
          "A runner install script cannot carry " + what + " '" + value + "'");
    }
  }

  private static String load() {
    try (InputStream in = InstallScript.class.getResourceAsStream(TEMPLATE)) {
      if (in == null) {
        throw new IllegalStateException(TEMPLATE + " is not on the classpath");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + TEMPLATE, e);
    }
  }
}
