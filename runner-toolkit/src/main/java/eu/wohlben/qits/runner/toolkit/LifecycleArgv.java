package eu.wohlben.qits.runner.toolkit;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Every docker command line a runner runs on its own node — replacing itself, removing itself,
 * cleaning up after itself — as pure functions: an identity and values in, a {@code List<String>}
 * out, asserted element for element in a docker-free suite. qits-ci-runner-daemon's {@code
 * RunnerArgv}, its self-update and teardown verbs, with every CI constant replaced by a name
 * derived from the {@link RunnerIdentity}.
 *
 * <p><b>The belts run here</b>, on every value that reaches an element: a value outside its charset
 * is an {@link IllegalArgumentException}. Nothing is ever assembled into a shell line.
 *
 * <p><b>What is not here, on purpose.</b> No {@code --rm}: a self-removing container races the
 * diagnosis. No {@code name=} filter: docker matches it as a substring, so it could select a
 * neighbour's container; every listing is an exact {@code label=} filter. No {@code -f} on {@code
 * image rm} and no {@code -a} on a prune: docker refusing an image a container still uses is the
 * belt that keeps a co-located runner's stopped containers safe. No {@code system}, {@code volume}
 * or {@code container prune}: the node holds other runners' and other people's things. And no
 * named {@code volume rm} or {@code network rm} on a name that does not start with {@code
 * <namePrefix>-} ({@link #volumeRm}, {@link #teardown}).
 */
public final class LifecycleArgv {

  /** The host docker socket, on both sides of the bind — a constant, never a caller's path. */
  public static final String DOCKER_SOCKET = "/var/run/docker.sock";

  /** Set on the decommission helper, to the runner id it removes: {@code <envPrefix>DECOMMISSION}. */
  public static final String DECOMMISSION = RunnerLauncher.DECOMMISSION;

  /** The state volume the helper removes last: {@code <envPrefix>DECOMMISSION_VOLUME}. */
  public static final String DECOMMISSION_VOLUME = "DECOMMISSION_VOLUME";

  /**
   * The domain's teardown items the helper removes: {@code <envPrefix>DECOMMISSION_TEARDOWN}, each
   * {@code <type>:<name>}, comma separated — {@code container:qits-x-runner-builder-1a2b3c4d}.
   * Absent when there are none, so a runner with no teardown starts a helper byte-identical to
   * CI's.
   */
  public static final String DECOMMISSION_TEARDOWN = "DECOMMISSION_TEARDOWN";

  private static final Pattern NAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}");
  private static final Pattern IMAGE = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._:/@+-]{0,511}");
  private static final Pattern ENV_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,255}");
  private static final Pattern LABEL_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,255}");
  private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

  private LifecycleArgv() {}

  /**
   * One line per runner container: id, version label, state and image — the last so a successor
   * can remove its predecessor's image once the container is gone.
   */
  static String processFormat(RunnerIdentity identity) {
    return "{{.ID}}|{{.Label \"" + identity.versionLabel() + "\"}}|{{.State}}|{{.Image}}";
  }

  /**
   * The runner's own container, whole — never {@code --format}: a Go template refuses to render a
   * key the container's shape omits ({@code HostConfig.Mounts} is absent on a container started
   * with {@code -v} binds only), where {@link SelfSpec#parse} reads the document defensively. Read
   * through {@link RunnerDocker#run}, whose stdout is kept from its head.
   */
  public static List<String> inspectSelf(String dockerBinary, String containerId) {
    return List.of(dockerBinary, "inspect", require(NAME, "container id", containerId));
  }

  /** The image the runner's own container was started from, whole — see {@link #inspectSelf}. */
  public static List<String> imageConfig(String dockerBinary, String image) {
    return List.of(dockerBinary, "image", "inspect", requireImage(image));
  }

  /** The registry digests a pulled image is known under — what an expected digest is checked in. */
  public static List<String> repoDigests(String dockerBinary, String image) {
    return List.of(
        dockerBinary, "image", "inspect", "--format", "{{json .RepoDigests}}", requireImage(image));
  }

  /** Where a container is in its life: {@code running}, {@code exited}, {@code restarting}, … */
  public static List<String> state(String dockerBinary, String containerId) {
    return List.of(
        dockerBinary,
        "inspect",
        "--format",
        "{{.State.Status}}",
        require(NAME, "container id", containerId));
  }

  /** The full image id a container runs — how the runner knows which image is its own. */
  public static List<String> containerImage(String dockerBinary, String containerId) {
    return List.of(
        dockerBinary,
        "inspect",
        "--format",
        "{{.Image}}",
        require(NAME, "container id", containerId));
  }

  /** An image's full id; a nonzero answer is "not here". */
  public static List<String> imageInspect(String dockerBinary, String image) {
    return List.of(
        dockerBinary, "image", "inspect", "--format", "{{.Id}}", requireImage(image));
  }

  /**
   * Fetch the image under the docker client config in {@code configDir} — a throwaway registry
   * login ({@link RegistryLogin}), {@code docker --config <dir>} being the one way to hand a single
   * invocation a credential without writing it into the host's config — or with the host's own
   * config when it is null.
   */
  public static List<String> pull(String dockerBinary, Path configDir, String image) {
    List<String> argv = new ArrayList<>();
    argv.add(dockerBinary);
    if (configDir != null) {
      // A global option, so it goes between the binary and the subcommand.
      argv.add("--config");
      argv.add(configDir.toString());
    }
    argv.add("pull");
    argv.add(requireImage(image));
    return List.copyOf(argv);
  }

  /**
   * Take the restart policy off a container, so the exit it is about to make is its last. The
   * retiring runner's own step out of the way of its successor.
   */
  public static List<String> noRestart(String dockerBinary, String containerId) {
    return List.of(
        dockerBinary, "update", "--restart=no", require(NAME, "container id", containerId));
  }

  /**
   * Remove a container, running or not: an id this runner just listed by its own label, or a name
   * it derived from its identity ({@link RunnerIdentity#containerName}) — never a name a frame or
   * a domain supplied, which goes through {@link #teardown}'s prefix belt instead.
   */
  public static List<String> rm(String dockerBinary, String idOrDerivedName) {
    return List.of(dockerBinary, "rm", "-f", require(NAME, "container", idOrDerivedName));
  }

  /**
   * Every runner container of this runner id — {@link RunnerIdentity#processLabel()}, never the
   * owner label — one line each in {@link #processFormat}. The filter is the last element.
   */
  public static List<String> psProcess(
      RunnerIdentity identity, String dockerBinary, String runnerId) {
    return List.of(
        dockerBinary,
        "ps",
        "-a",
        "--format",
        processFormat(identity),
        "--filter",
        "label=" + identity.processLabel() + "=" + require(NAME, "runner id", runnerId));
  }

  /**
   * The successor's whole {@code docker run}: the container contract, with everything that does
   * not depend on the image taken from the running container ({@code self}) and only the name, the
   * image and the version label new.
   *
   * <ul>
   *   <li>The restart policy and the mounts are carried as they are — the docker socket and the
   *       state volume holding {@code client.json}, and whatever an operator added.
   *   <li>The network is carried only when it is not docker's default bridge.
   *   <li>The environment is the one the container was <em>given</em> ({@link SelfSpec} subtracts
   *       the image's), and never {@code <envPrefix>REGISTRATION_TOKEN}: a successor starts
   *       registered.
   *   <li>Labels likewise, with the process and version labels set here and the workload labels
   *       ({@link RunnerIdentity#ownerLabel()}, {@link RunnerIdentity#workLabel()}) dropped should
   *       the old container somehow carry one — a runner its own sweep can match removes itself.
   * </ul>
   */
  public static List<String> runSuccessor(
      RunnerIdentity identity,
      String dockerBinary,
      String runnerId,
      String version,
      String image,
      SelfSpec self) {
    List<String> argv = new ArrayList<>();
    argv.add(dockerBinary);
    argv.add("run");
    argv.add("-d");
    argv.add("--name");
    argv.add(containerName(identity, runnerId, version));
    argv.add("--restart");
    argv.add(oneLine("restart policy", self.restart()));
    if (self.network() != null
        && !self.network().isEmpty()
        && !self.network().equals("default")
        && !self.network().equals("bridge")) {
      argv.add("--network");
      argv.add(require(NAME, "network", self.network()));
    }
    Map<String, String> labels = new TreeMap<>();
    for (Map.Entry<String, String> label : self.labels().entrySet()) {
      String key = label.getKey();
      if (key.equals(identity.ownerLabel()) || key.equals(identity.workLabel())) {
        continue;
      }
      labels.put(require(LABEL_KEY, "label key", key), oneLine("label value", label.getValue()));
    }
    labels.put(identity.processLabel(), require(NAME, "runner id", runnerId));
    labels.put(identity.versionLabel(), requireVersion(version));
    for (Map.Entry<String, String> label : labels.entrySet()) {
      argv.add("--label");
      argv.add(label.getKey() + "=" + label.getValue());
    }
    for (String bind : self.binds()) {
      argv.add("-v");
      argv.add(oneLine("bind", bind));
    }
    for (String mount : self.mounts()) {
      argv.add("--mount");
      argv.add(oneLine("mount", mount));
    }
    String token = identity.env(RunnerSettings.REGISTRATION_TOKEN);
    for (Map.Entry<String, String> variable : new TreeMap<>(self.env()).entrySet()) {
      if (variable.getKey().equals(token)) {
        continue;
      }
      argv.add("-e");
      argv.add(
          require(ENV_KEY, "environment key", variable.getKey())
              + "="
              + oneLine("environment value", variable.getValue()));
    }
    argv.add(requireImage(image));
    return List.copyOf(argv);
  }

  /**
   * The decommission helper's whole {@code docker run}: the runner's own image (by id — the one
   * this process is running, so no pull and no login), in helper mode through {@code
   * <envPrefix>DECOMMISSION}, with the docker socket, which is all it needs. No restart policy and
   * no network: it has one job, and talks only to the local daemon. <b>No {@code --rm}</b>: the
   * helper removes itself last, only once everything else is gone, so a helper that failed stays
   * with its log.
   *
   * <p>{@code volume} is passed only when there is one; {@code teardown} only when it is not
   * empty. Both are re-checked against the prefix by the helper itself ({@link
   * DecommissionHelper}), which is where a refusal is said.
   */
  public static List<String> runDecommissioner(
      RunnerIdentity identity,
      String dockerBinary,
      String runnerId,
      String image,
      String volume,
      List<TeardownItem> teardown) {
    List<String> argv = new ArrayList<>();
    argv.add(dockerBinary);
    argv.add("run");
    argv.add("-d");
    argv.add("--name");
    argv.add(helperName(identity, runnerId));
    argv.add("--network");
    argv.add("none");
    argv.add("--label");
    argv.add(identity.decommissionLabel() + "=" + require(NAME, "runner id", runnerId));
    argv.add("-v");
    argv.add(DOCKER_SOCKET + ":" + DOCKER_SOCKET);
    argv.add("-e");
    argv.add(identity.env(DECOMMISSION) + "=" + runnerId);
    if (volume != null) {
      argv.add("-e");
      argv.add(identity.env(DECOMMISSION_VOLUME) + "=" + require(NAME, "volume", volume));
    }
    if (teardown != null && !teardown.isEmpty()) {
      argv.add("-e");
      argv.add(identity.env(DECOMMISSION_TEARDOWN) + "=" + encodeTeardown(teardown));
    }
    argv.add(requireImage(image));
    return List.copyOf(argv);
  }

  /**
   * Remove a named volume — the decommissioned runner's state. <b>Refused unless the name starts
   * with {@code <namePrefix>-}</b>: a state volume an operator mounted under another name is the
   * operator's.
   */
  public static List<String> volumeRm(RunnerIdentity identity, String dockerBinary, String volume) {
    return List.of(dockerBinary, "volume", "rm", requireOwned(identity, "volume", volume));
  }

  /**
   * Remove one of the domain's {@link TeardownItem}s by its exact name, with the verb its type
   * picks. <b>Refused unless the name starts with {@code <namePrefix>-}</b> — the belt that makes
   * CI's "remove the node's buildkitd" impossible to write for a runner that does not own it.
   */
  public static List<String> teardown(
      RunnerIdentity identity, String dockerBinary, TeardownItem item) {
    String name = requireOwned(identity, "teardown " + item.type().name().toLowerCase(java.util.Locale.ROOT), item.name());
    return switch (item.type()) {
      case CONTAINER -> List.of(dockerBinary, "rm", "-f", name);
      case VOLUME -> List.of(dockerBinary, "volume", "rm", name);
      case NETWORK -> List.of(dockerBinary, "network", "rm", name);
    };
  }

  /**
   * Every image on the host as {@code repository:tag|full id}, one per line — read by the leftover
   * runner-image sweep, which picks out the identity's image repository itself rather than trusting
   * a {@code reference} filter's glob to span a registry host with a port.
   */
  public static List<String> imageList(String dockerBinary) {
    return List.of(
        dockerBinary, "image", "ls", "--no-trunc", "--format", "{{.Repository}}:{{.Tag}}|{{.ID}}");
  }

  /** Every container, running or not, created from {@code image} (an id or a reference). */
  public static List<String> psAncestor(String dockerBinary, String image) {
    return List.of(
        dockerBinary, "ps", "-a", "-q", "--filter", "ancestor=" + requireImage(image));
  }

  /**
   * Untag, and delete when it was the last tag, an image — never {@code -f}: docker refusing an
   * image a container still uses is the belt every garbage collection here leans on.
   */
  public static List<String> imageRm(String dockerBinary, String image) {
    return List.of(dockerBinary, "image", "rm", requireImage(image));
  }

  /**
   * Dangling images only. Never {@code -a}: that would delete every image no container uses, and
   * the node is somebody's machine holding things this runner never pulled.
   */
  public static List<String> imagePruneDangling(String dockerBinary) {
    return List.of(dockerBinary, "image", "prune", "-f");
  }

  /** {@link RunnerIdentity#containerName}, belted: the install script composes the same string. */
  public static String containerName(RunnerIdentity identity, String runnerId, String version) {
    return require(
        NAME,
        "container name",
        identity.containerName(require(NAME, "runner id", runnerId), requireVersion(version)));
  }

  /** {@link RunnerIdentity#helperName}, belted. */
  public static String helperName(RunnerIdentity identity, String runnerId) {
    return require(
        NAME, "container name", identity.helperName(require(NAME, "runner id", runnerId)));
  }

  /** A runner version, as a container name and a label value both take it. */
  public static String requireVersion(String version) {
    return require(VERSION, "runner version", version);
  }

  /** The image belt. */
  public static String requireImage(String image) {
    return require(IMAGE, "image", image);
  }

  /** A name this runner owns: its charset, and the identity's {@code <namePrefix>-}. */
  static String requireOwned(RunnerIdentity identity, String what, String name) {
    require(NAME, what, name);
    if (!identity.ownsName(name)) {
      throw new IllegalArgumentException(
          what
              + " '"
              + name
              + "' does not start with "
              + identity.namePrefix()
              + "-, so it is not this runner's to remove");
    }
    return name;
  }

  /** {@code <type>:<name>,…} — see {@link #DECOMMISSION_TEARDOWN}. */
  static String encodeTeardown(List<TeardownItem> items) {
    List<String> encoded = new ArrayList<>();
    for (TeardownItem item : items) {
      encoded.add(
          item.type().name().toLowerCase(java.util.Locale.ROOT)
              + ":"
              + require(NAME, "teardown " + item.type().name().toLowerCase(java.util.Locale.ROOT), item.name()));
    }
    return String.join(",", encoded);
  }

  /**
   * The inverse of {@link #encodeTeardown}, for the helper. An entry it cannot read is refused
   * with the reason, never guessed at.
   */
  static List<TeardownItem> decodeTeardown(String value) {
    List<TeardownItem> items = new ArrayList<>();
    if (value == null || value.isBlank()) {
      return items;
    }
    for (String entry : value.strip().split(",")) {
      int colon = entry.indexOf(':');
      if (colon <= 0) {
        throw new IllegalArgumentException("teardown entry '" + entry + "' is not <type>:<name>");
      }
      TeardownItem.Type type;
      try {
        type = TeardownItem.Type.valueOf(entry.substring(0, colon).toUpperCase(java.util.Locale.ROOT));
      } catch (IllegalArgumentException unknown) {
        throw new IllegalArgumentException("teardown entry '" + entry + "' has no known type");
      }
      items.add(new TeardownItem(type, require(NAME, "teardown name", entry.substring(colon + 1))));
    }
    return items;
  }

  /** Belt-check a value against its charset; the message names what it was. */
  static String require(Pattern pattern, String what, String value) {
    if (value == null || !pattern.matcher(value).matches()) {
      throw new IllegalArgumentException("invalid " + what + ": '" + value + "'");
    }
    return value;
  }

  private static String oneLine(String what, String value) {
    if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
      throw new IllegalArgumentException("invalid " + what + ": it spans lines");
    }
    return value;
  }
}
