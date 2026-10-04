package eu.wohlben.qits.runner.toolkit.install;

import static eu.wohlben.qits.runner.toolkit.install.Identities.HARNESS;
import static eu.wohlben.qits.runner.toolkit.install.Identities.HARNESS_PAGE;
import static eu.wohlben.qits.runner.toolkit.install.Identities.REGISTRY;
import static eu.wohlben.qits.runner.toolkit.install.Identities.VERSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.toolkit.LifecycleArgv;
import eu.wohlben.qits.runner.toolkit.SelfSpec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The install contract, run: the rendered script against a stub docker, the way the install line
 * runs it — piped into {@code sh} with the four per-runner values in its environment. A first
 * install, then a re-run with a new token (a page's "replace registration token", which is also how
 * a runner is replaced). Nothing it runs reaches a network or a real docker. qits-ci-runner-daemon's
 * {@code scripts/test-install-contract.sh}, every assertion of it kept, rendered for a harness-shaped
 * identity rather than read from a fixture copy.
 *
 * <p>THE CONTRACT:
 *
 * <ol start="0">
 *   <li>It carries no secret and names no runner: {@code <prefix>URL}, {@code <prefix>ID}, {@code
 *       <prefix>REGISTRATION_TOKEN} and {@code <prefix>SLOTS} come from its environment, and one
 *       missing is a refusal (nonzero, naming it) before docker is asked anything.
 *   <li>It needs docker, and asks: {@code docker version} must answer, or it refuses and runs
 *       nothing else. docker comes from PATH, so the stub stands in for it.
 *   <li>It logs in to the image's registry as {@code docker --config <tmp> login <registry host> -u
 *       token --password-stdin}, the token on stdin, in a 0700 directory of its own; pulls under
 *       that same {@code --config}; and deletes the directory. A failed login or pull is a refusal
 *       that leaves whatever runner is installed alone.
 *   <li>It removes ({@code docker rm -f}) every container {@code docker ps -aq --filter
 *       label=<labelRoot>.process=<id>} lists — and never selects by the owner label.
 *   <li>It keeps the state volume but removes {@code client.json} from it, with a throwaway
 *       container of the image, so the token in the line is the one that registers.
 *   <li>It starts the runner with exactly the container contract's {@code docker run}, element for
 *       element — and that is {@link LifecycleArgv#runSuccessor} fed the {@code docker inspect} of
 *       the container it starts, plus the token as {@code -e <prefix>REGISTRATION_TOKEN}, which a
 *       successor alone never carries. The two live in this artifact and cannot drift apart.
 *   <li>It prints {@code <namePrefix> started; watch: docker logs -f <name>}.
 *   <li>The registration token appears in no line it prints and in no docker argv; it reaches docker
 *       only on the login's stdin and in the environment of the {@code run}.
 * </ol>
 */
@EnabledOnOs(OS.LINUX)
class InstallScriptContractTest {

  private static final String PREFIX = HARNESS.envPrefix();
  private static final String URL_VALUE = "https://harness.qits.example.org";
  private static final String ID_VALUE = "3f2a9c1e-0000-4000-8000-000000000001";
  private static final String ID8 = "3f2a9c1e";
  private static final String SLOTS_VALUE = "2";
  private static final String IMAGE = REGISTRY + "/qits/qits-harness-runner:" + VERSION;
  private static final String NAME = "qits-harness-runner-" + ID8 + "-" + VERSION;
  private static final String VOLUME = "qits-harness-runner-state-" + ID8;
  private static final String STATE_DIR = "/var/lib/qits-harness-runner";

  /**
   * The stub docker. Records every argv (one line per call, never to stdout), the {@code --config}
   * directory each call was given and that directory's mode, the login's stdin, and the detached
   * run's argv (one element per line) and {@code <prefix>*} environment. Answers {@code ps} from
   * {@code ps-answer}, and fails {@code version} or {@code pull} when {@code version-code} or {@code
   * pull-code} says so.
   */
  private static final String STUB =
      """
      printf 'docker %s\\n' "$*" >> "$W/calls.log"
      if [ "$1" = "--config" ]; then
        printf '%s\\n' "$2" >> "$W/config-dirs"
        stat -c %a "$2" >> "$W/config-modes" 2>/dev/null || stat -f %Lp "$2" >> "$W/config-modes"
        shift 2
      fi
      case "$1" in
        version) exit "$(cat "$W/version-code" 2>/dev/null || echo 0)" ;;
        login) cat > "$W/login-stdin" ;;
        pull) exit "$(cat "$W/pull-code" 2>/dev/null || echo 0)" ;;
        ps) cat "$W/ps-answer" 2>/dev/null || true ;;
        run)
          if [ "$2" = "-d" ]; then
            : > "$W/run-argv"
            for a in "$@"; do printf '%s\\n' "$a" >> "$W/run-argv"; done
            env | grep "^$P" | sort > "$W/run-env"
            echo 4f6c1e0b2a9d
          fi
          ;;
      esac
      exit 0
      """;

  @TempDir Path work;

  private Path stubs;
  private Path script;
  private String token;

  @BeforeEach
  void stubDocker() throws IOException {
    stubs = Files.createDirectories(work.resolve("stubs"));
    Path docker = stubs.resolve("docker");
    Files.writeString(
        docker, "#!/bin/sh\nW='" + work + "'\nP='" + PREFIX + "'\n" + STUB, StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(docker, PosixFilePermissions.fromString("rwxr-xr-x"));
    script = work.resolve("runner-install.sh");
    Files.writeString(script, InstallScript.script(HARNESS, HARNESS_PAGE, REGISTRY));
    token = "qits_tok_CONTRACT-" + ProcessHandle.current().pid() + "-DO-NOT-PRINT";
    reset();
  }

  @Test
  void theGenericScriptParsesAndCarriesNoTokenNoPlaceholderAndNoSystemd() throws Exception {
    String text = Files.readString(script);

    assertEquals(0, sh(List.of("sh", "-n", script.toString()), Map.of(), null).exit());
    assertFalse(text.contains("qits_tok_"), "the generic script carries a token");
    assertFalse(text.contains("{{"), "the generic script has a placeholder nothing filled");
    assertFalse(text.toLowerCase().contains("systemctl"), "the script still speaks systemd");
    assertFalse(text.toLowerCase().contains("systemd"), "the script still speaks systemd");
  }

  @Test
  void aMissingValueIsRefusedByNameBeforeDockerIsAskedAnything() throws Exception {
    Ran ran =
        sh(
            List.of("sh"),
            Map.of(
                PREFIX + "URL", URL_VALUE,
                PREFIX + "REGISTRATION_TOKEN", token,
                PREFIX + "SLOTS", SLOTS_VALUE),
            script);

    assertNotEquals(0, ran.exit(), "the install script ran with " + PREFIX + "ID unset");
    assertTrue(ran.output().contains(PREFIX + "ID is not set"), ran.output());
    assertTrue(
        ran.output().contains("paste the whole line from the Harness UI's Runners page."),
        ran.output());
    assertEquals(List.of(), calls(), "a refused install ran docker");
  }

  @Test
  void aDockerThatDoesNotAnswerIsRefusedAndNothingElseRuns() throws Exception {
    Files.writeString(work.resolve("version-code"), "1\n");

    Ran ran = install(token, Map.of());

    assertNotEquals(0, ran.exit(), "the install script ran against a docker that does not answer");
    assertTrue(ran.output().contains("docker does not answer"), ran.output());
    assertEquals(
        List.of("docker version"), calls(), "a docker that does not answer was asked more");
  }

  @Test
  void aPullThatFailsLeavesTheInstalledRunnerAlone() throws Exception {
    Files.writeString(work.resolve("pull-code"), "1\n");
    Files.writeString(work.resolve("ps-answer"), "aaaaaaaaaaaa\n");

    Ran ran = install(token, Map.of());

    assertNotEquals(0, ran.exit(), "the install script carried on after a failed pull");
    assertTrue(ran.output().contains("could not pull " + IMAGE), ran.output());
    for (String call : calls()) {
      assertFalse(
          call.startsWith("docker rm") || call.startsWith("docker run") || call.startsWith("docker ps"),
          "a failed pull still removed or started a container: " + call);
    }
    for (String dir : lines("config-dirs")) {
      assertFalse(Files.exists(Path.of(dir)), "the login directory " + dir + " outlived a failed pull");
    }
  }

  @Test
  void aFirstInstallStartsExactlyTheContainerASuccessorWouldBe() throws Exception {
    Ran ran = install(token, Map.of());

    assertEquals(0, ran.exit(), ran.output());
    assertTrue(called("version"), "docker version was not asked");

    // 2. login and pull, under one throwaway config, deleted afterwards.
    List<String> configs = lines("config-dirs");
    assertFalse(configs.isEmpty(), "no docker call was given --config");
    String config = configs.getFirst();
    assertEquals(1, new HashSet<>(configs).size(), "the login and the pull used different configs");
    assertTrue(
        called("--config", config, "login", REGISTRY, "-u", "token", "--password-stdin"),
        "the login is not 'docker --config <tmp> login " + REGISTRY + " -u token --password-stdin'");
    assertEquals(token, Files.readString(work.resolve("login-stdin")), "the token was not on stdin");
    assertTrue(called("--config", config, "pull", IMAGE), "the image was not pulled under --config");
    assertEquals(List.of("700"), lines("config-modes").stream().distinct().toList());
    assertFalse(Files.exists(Path.of(config)), "the login directory " + config + " was not deleted");
    assertTrue(indexOf(" login ") < indexOf(" pull "), "the pull ran before the login");

    // 3. the runner's containers, by the process label, never the owner label.
    assertTrue(
        called("ps", "-aq", "--filter", "label=" + HARNESS.processLabel() + "=" + ID_VALUE),
        "the runner's containers were not listed by " + HARNESS.processLabel());
    assertTrue(
        calls().stream().noneMatch(c -> c.contains("label=" + HARNESS.ownerLabel() + "=")),
        "the script selected by the owner label");

    // 4. the client goes, the volume stays.
    assertClientRemovedAndVolumeKept();

    // 5. the container contract, element for element — as written down, and as a successor is.
    List<String> run = lines("run-argv");
    assertEquals(
        List.of(
            "run",
            "-d",
            "--name",
            NAME,
            "--restart",
            "unless-stopped",
            "--label",
            "qits.harness.runner.process=" + ID_VALUE,
            "--label",
            "qits.harness.runner.version=" + VERSION,
            "-v",
            "/var/run/docker.sock:/var/run/docker.sock",
            "-v",
            VOLUME + ":" + STATE_DIR,
            "-e",
            PREFIX + "ID=" + ID_VALUE,
            "-e",
            PREFIX + "SLOTS=" + SLOTS_VALUE,
            "-e",
            PREFIX + "URL=" + URL_VALUE,
            "-e",
            PREFIX + "REGISTRATION_TOKEN",
            IMAGE),
        run,
        "the runner's docker run is not the container contract");
    assertEquals(successorOfTheInstalledContainer(), run, "the install and a successor differ");
    assertTrue(
        lines("run-env").contains(PREFIX + "REGISTRATION_TOKEN=" + token),
        "docker run did not have the token in its environment");
    assertTrue(indexOf(" pull ") < indexOf("docker run -d"), "the runner started before the pull");

    // 6. the one line.
    List<String> output = ran.output().lines().toList();
    assertEquals(
        "qits-harness-runner started; watch: docker logs -f " + NAME,
        output.getLast(),
        ran.output());

    // 7. the token: not printed, not in any argv.
    assertFalse(ran.output().contains(token), "the token appeared in the script's output");
    assertTrue(calls().stream().noneMatch(c -> c.contains(token)), "the token is in a docker argv");
  }

  @Test
  void aReRunWithANewTokenReplacesTheRunnerAndCarriesTheOperatorsTuning() throws Exception {
    assertEquals(0, install(token, Map.of()).exit());
    String newToken = "qits_tok_ROTATED-" + ProcessHandle.current().pid() + "-DO-NOT-PRINT";
    reset();
    Files.writeString(work.resolve("ps-answer"), "aaaaaaaaaaaa\nbbbbbbbbbbbb\n");
    String tuning =
        PREFIX + "BUILDKIT_HTTP_REGISTRIES=dev-qits-artifacts:8080,dev-qits-platform-mirror:8080";

    Ran ran =
        install(
            newToken,
            Map.of(
                PREFIX + "BUILDKIT_HTTP_REGISTRIES",
                "dev-qits-artifacts:8080,dev-qits-platform-mirror:8080"));

    assertEquals(0, ran.exit(), ran.output());
    assertTrue(called("rm", "-f", "aaaaaaaaaaaa"), "a re-run did not remove the runner already there");
    assertTrue(called("rm", "-f", "bbbbbbbbbbbb"), "a re-run did not remove the runner already there");
    assertTrue(
        indexOf("docker rm -f bbbbbbbbbbbb") < indexOf("docker run -d"),
        "the old runner was removed after the new one started");
    assertEquals(newToken, Files.readString(work.resolve("login-stdin")), "no login with the new token");
    assertTrue(
        lines("run-env").contains(PREFIX + "REGISTRATION_TOKEN=" + newToken),
        "a re-run did not start the runner with the new token");
    assertClientRemovedAndVolumeKept();
    List<String> run = lines("run-argv");
    assertEquals(tuning, run.get(run.size() - 2), "a tuning variable that was set did not arrive");
    assertTrue(
        run.stream()
            .noneMatch(
                a -> a.contains(PREFIX + "ROLLOVER_TIMEOUT") || a.contains(PREFIX + "DOCKER_TIMEOUT")),
        "a tuning variable that was not set reached the container");
    assertFalse(ran.output().contains(newToken), "the new token appeared in the output");
    assertTrue(
        calls().stream().noneMatch(c -> c.contains(newToken)), "the new token is in a docker argv");
  }

  /**
   * What {@link LifecycleArgv#runSuccessor} starts in place of the container the install script
   * started — read from a {@code docker inspect} of exactly that container (the fixture beside this
   * class: the four values, the two labels, the two binds, {@code unless-stopped}, the default
   * bridge) — with the token added back as the install's {@code -e NAME}, the one element a
   * registered successor never carries. Same version: a rollover onto the same image.
   */
  private static List<String> successorOfTheInstalledContainer() throws IOException {
    SelfSpec self =
        SelfSpec.parse(resource("harness-runner.inspect.json"), resource("harness-runner.image-inspect.json"));
    List<String> successor =
        LifecycleArgv.runSuccessor(HARNESS, "docker", ID_VALUE, VERSION, IMAGE, self);
    List<String> expected = new ArrayList<>(successor.subList(1, successor.size() - 1));
    expected.add("-e");
    expected.add(PREFIX + "REGISTRATION_TOKEN");
    expected.add(successor.getLast());
    return expected;
  }

  private void assertClientRemovedAndVolumeKept() throws IOException {
    assertTrue(
        called(
            "run", "--rm", "--entrypoint", "rm", "-v", VOLUME + ":" + STATE_DIR, IMAGE, "-f",
            STATE_DIR + "/client.json"),
        "client.json was not removed from the state volume");
    assertTrue(
        calls().stream().noneMatch(c -> c.startsWith("docker volume rm")),
        "the state volume was removed");
  }

  // ---- running the script --------------------------------------------------------------------

  private record Ran(int exit, String output) {}

  /** As the install line runs it: the script on stdin, the four values (and any extra) in env. */
  private Ran install(String withToken, Map<String, String> extra) throws Exception {
    Map<String, String> env = new java.util.HashMap<>(extra);
    env.put(PREFIX + "URL", URL_VALUE);
    env.put(PREFIX + "ID", ID_VALUE);
    env.put(PREFIX + "REGISTRATION_TOKEN", withToken);
    env.put(PREFIX + "SLOTS", SLOTS_VALUE);
    return sh(List.of("sh"), env, script);
  }

  private Ran sh(List<String> command, Map<String, String> env, Path stdin) throws Exception {
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    builder.environment().keySet().removeIf(key -> key.startsWith(PREFIX));
    builder.environment().put("PATH", stubs + ":" + System.getenv("PATH"));
    builder.environment().putAll(env);
    if (stdin != null) {
      builder.redirectInput(stdin.toFile());
    }
    Process process = builder.start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the script did not finish");
    return new Ran(process.exitValue(), output);
  }

  private void reset() throws IOException {
    Files.writeString(work.resolve("calls.log"), "");
    for (String file :
        List.of(
            "config-dirs", "config-modes", "login-stdin", "run-argv", "run-env", "version-code",
            "pull-code", "ps-answer")) {
      Files.deleteIfExists(work.resolve(file));
    }
  }

  private List<String> calls() throws IOException {
    return lines("calls.log");
  }

  private List<String> lines(String file) throws IOException {
    Path path = work.resolve(file);
    return Files.exists(path) ? Files.readAllLines(path) : List.of();
  }

  private boolean called(String... argv) throws IOException {
    return calls().contains("docker " + String.join(" ", argv));
  }

  /** The line number of the first call containing {@code needle}; fails when there is none. */
  private int indexOf(String needle) throws IOException {
    List<String> calls = calls();
    for (int i = 0; i < calls.size(); i++) {
      if (calls.get(i).contains(needle)) {
        return i;
      }
    }
    throw new AssertionError("no docker call contains '" + needle + "': " + calls);
  }

  private static String resource(String name) throws IOException {
    try (InputStream in = InstallScriptContractTest.class.getResourceAsStream(name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
