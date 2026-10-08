package eu.wohlben.qits.runner.toolkit;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Everything the toolkit is handed before anything is dialled, already parsed. Built by {@link
 * #parse} from the raw environment strings so that a bad value is a sentence naming the variable,
 * not a stack trace. <b>The only class in the toolkit that reads configuration</b>, and it reads it
 * from the function it is handed, never from {@code System.getenv} or a config provider.
 *
 * <p>Every variable is {@code <envPrefix><NAME>} ({@link RunnerIdentity#env}). A domain's own
 * settings are the embedder's, parsed beside these from the same environment.
 *
 * <p>The registration token is here and nowhere else: {@link Registration} reads it once, when there
 * is no client yet (or the token changed), and nothing ever logs it.
 *
 * @param telemetryUrl the OTLP endpoint the runner's own log is shipped to, or null for none
 * @param timing the socket's and the HTTP calls' clocks; {@link Timing#defaults()} from {@link
 *     #parse}, overridden by a suite through {@link #withTiming}, never through the environment
 */
public record RunnerSettings(
    String url,
    String runnerId,
    String registrationToken,
    Path stateDir,
    int slots,
    String dockerBinary,
    long dockerTimeoutSeconds,
    long pullTimeoutSeconds,
    long rolloverTimeoutSeconds,
    boolean selfUpdate,
    String telemetryUrl,
    Timing timing) {

  /** The variables, without the prefix. */
  public static final String URL = "URL";

  public static final String ID = "ID";
  public static final String REGISTRATION_TOKEN = "REGISTRATION_TOKEN";
  public static final String STATE_DIR = "STATE_DIR";
  public static final String SLOTS = "SLOTS";
  public static final String DOCKER_BINARY = "DOCKER_BINARY";
  public static final String DOCKER_TIMEOUT = "DOCKER_TIMEOUT";

  /**
   * A pull's own deadline, wider than {@link #DOCKER_TIMEOUT}: a cold pull of a large image (layers
   * fetched and decompressed, not just a daemon round trip) can run well past the general call's
   * budget without docker itself being stuck, and {@link #DOCKER_TIMEOUT} still bounds every other
   * call — including the digest check right after a pull.
   */
  public static final String PULL_TIMEOUT = "PULL_TIMEOUT";

  public static final String ROLLOVER_TIMEOUT = "ROLLOVER_TIMEOUT";

  /**
   * Whether an {@code Upgrade} makes this runner roll itself over. Default true — a runner a person
   * installed has nobody else to update it. False is a deployer-managed runner: it ignores every
   * {@code Upgrade} and says so in its capability labels ({@link RunnerIdentity#selfUpdateLabel}).
   */
  public static final String SELF_UPDATE = "SELF_UPDATE";

  /**
   * Read raw, because its empty value means something: set and empty switches the log export off,
   * where unset derives it (see {@link #telemetryUrl(RunnerIdentity, String, String)}).
   */
  public static final String TELEMETRY_URL = "TELEMETRY_URL";

  /** {@code 1} prints the version and exits 0 — the image's smoke probe ({@link RunnerLauncher}). */
  public static final String PRINT_VERSION = "PRINT_VERSION";

  /** The advertised slot count when the operator set none. */
  public static final int DEFAULT_SLOTS = 1;

  /** A docker call's deadline when the operator set none. */
  public static final int DEFAULT_DOCKER_TIMEOUT_SECONDS = 120;

  /**
   * A pull's deadline when the operator set none. Half an hour, not two minutes: a cold pull of a
   * multi-gigabyte image over a slow or congested link is routine, not a hang, and a budget sized
   * for a daemon round trip ({@link #DEFAULT_DOCKER_TIMEOUT_SECONDS}) kills it mid-download every
   * time.
   */
  public static final int DEFAULT_PULL_TIMEOUT_SECONDS = 1800;

  /**
   * How long a successor has to take over before it is removed. Three minutes is a pull-free
   * container start, a registration that is not needed, a token mint and a WebSocket upgrade, with
   * room for a slow host.
   */
  public static final int DEFAULT_ROLLOVER_TIMEOUT_SECONDS = 180;

  /** Where qits-observability's OTLP receiver lives below its host — its own root path. */
  public static final String TELEMETRY_PATH = "/observability/api/otel";

  private static final Pattern HTTP_URL = Pattern.compile("(?i)https?://[^/\\s]+(/\\S*)?");

  /** A path segment of the register door and a docker label value: both without escaping. */
  private static final Pattern RUNNER_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

  /**
   * The clocks under the session: CI's values by default. Not environment: a suite shortens them
   * through the constructor, a production runner never has a reason to.
   *
   * @param heartbeatMillis how often a connected socket sends {@code Heartbeat}; 0 sends none
   * @param initialBackoffMillis the first redial's delay, doubling per failed dial
   * @param maxBackoffMillis the redial delay's cap
   * @param httpTimeoutMillis the register door's, the token endpoint's and the log export's
   * @param refusalConfirmMillis how long an unbroken {@code invalid_client} streak must last
   * @param refusalConfirmAttempts and how many dials it must hold, before it is believed
   */
  public record Timing(
      long heartbeatMillis,
      long initialBackoffMillis,
      long maxBackoffMillis,
      long httpTimeoutMillis,
      long refusalConfirmMillis,
      int refusalConfirmAttempts) {

    /**
     * 10 s heartbeat, 500 ms doubling to a 30 s cap, 30 s per HTTP call, and a refusal streak of
     * five minutes and five dials: longer than any idp restart, short of a runner that lingers.
     */
    public static Timing defaults() {
      return new Timing(10_000, 500, 30_000, 30_000, 300_000, 5);
    }
  }

  /** A misconfiguration, carrying the one line the runner prints before exiting 2. */
  public static final class Invalid extends Exception {
    public Invalid(String message) {
      super(message);
    }
  }

  /** The same settings with other clocks — a suite's way to shorten them. */
  public RunnerSettings withTiming(Timing timing) {
    return new RunnerSettings(
        url, runnerId, registrationToken, stateDir, slots, dockerBinary, dockerTimeoutSeconds,
        pullTimeoutSeconds, rolloverTimeoutSeconds, selfUpdate, telemetryUrl, timing);
  }

  /**
   * Parse the identity's variables out of {@code env}. {@code null} and blank both mean "unset".
   * The url and the id are the two values nothing can default; the token's absence is only a
   * problem when there is no client yet, which {@link Registration} decides.
   */
  public static RunnerSettings parse(RunnerIdentity identity, Function<String, String> env)
      throws Invalid {
    String urlVariable = identity.env(URL);
    String url = env.apply(urlVariable);
    if (blank(url)) {
      throw new Invalid(urlVariable + " is not set");
    }
    if (!HTTP_URL.matcher(url.trim()).matches()) {
      throw new Invalid(urlVariable + " is not an http(s) url: '" + url.trim() + "'");
    }
    String idVariable = identity.env(ID);
    String runnerId = env.apply(idVariable);
    if (blank(runnerId)) {
      throw new Invalid(idVariable + " is not set");
    }
    if (!RUNNER_ID.matcher(runnerId.trim()).matches()) {
      throw new Invalid(idVariable + " is not a runner id: '" + runnerId.trim() + "'");
    }
    String token = env.apply(identity.env(REGISTRATION_TOKEN));
    String stateDir = env.apply(identity.env(STATE_DIR));
    String dockerBinary = env.apply(identity.env(DOCKER_BINARY));
    String trimmedUrl = url.trim().replaceAll("/+$", "");
    return new RunnerSettings(
        trimmedUrl,
        runnerId.trim(),
        blank(token) ? "" : token.trim(),
        Path.of(blank(stateDir) ? identity.defaultStateDir() : stateDir.trim()),
        positive(identity.env(SLOTS), env.apply(identity.env(SLOTS)), DEFAULT_SLOTS),
        blank(dockerBinary) ? "docker" : dockerBinary.trim(),
        positive(
            identity.env(DOCKER_TIMEOUT),
            stripSeconds(env.apply(identity.env(DOCKER_TIMEOUT))),
            DEFAULT_DOCKER_TIMEOUT_SECONDS),
        positive(
            identity.env(PULL_TIMEOUT),
            stripSeconds(env.apply(identity.env(PULL_TIMEOUT))),
            DEFAULT_PULL_TIMEOUT_SECONDS),
        positive(
            identity.env(ROLLOVER_TIMEOUT),
            stripSeconds(env.apply(identity.env(ROLLOVER_TIMEOUT))),
            DEFAULT_ROLLOVER_TIMEOUT_SECONDS),
        bool(identity.env(SELF_UPDATE), env.apply(identity.env(SELF_UPDATE)), true),
        telemetryUrl(identity, env.apply(identity.env(TELEMETRY_URL)), trimmedUrl),
        Timing.defaults());
  }

  /**
   * {@code <envPrefix>TELEMETRY_URL}: the OTLP endpoint the runner's log is shipped to, or null for
   * none.
   *
   * <p>Unset ({@code null}) derives it from the host's url the way the edge names every
   * application: the url's first host label is replaced by {@code observability} — {@code
   * https://ci.qits.example.eu} → {@code https://observability.qits.example.eu} + {@link
   * #TELEMETRY_PATH}, which is CI's {@code ci.} derivation, and the same for any other application
   * name. A url whose host has one label, or is an address, has no such sibling and derives nothing.
   * Set but empty switches it off.
   */
  public static String telemetryUrl(RunnerIdentity identity, String raw, String hostUrl)
      throws Invalid {
    if (raw != null) {
      if (raw.isBlank()) {
        return null;
      }
      String url = raw.trim().replaceAll("/+$", "");
      if (!HTTP_URL.matcher(url).matches()) {
        throw new Invalid(
            identity.env(TELEMETRY_URL) + " is not an http(s) url: '" + url + "'");
      }
      return url;
    }
    if (hostUrl == null) {
      return null;
    }
    URI uri;
    try {
      uri = URI.create(hostUrl.trim());
    } catch (IllegalArgumentException e) {
      return null;
    }
    String host = uri.getHost();
    if (uri.getScheme() == null || host == null || isAddress(host)) {
      return null;
    }
    int dot = host.indexOf('.');
    if (dot <= 0 || dot == host.length() - 1) {
      return null;
    }
    return uri.getScheme().toLowerCase(Locale.ROOT)
        + "://observability"
        + host.substring(dot).toLowerCase(Locale.ROOT)
        + (uri.getPort() == -1 ? "" : ":" + uri.getPort())
        + TELEMETRY_PATH;
  }

  /** {@code 1} (or {@code true}) asks for the version; anything else, unset included, does not. */
  public static boolean printsVersion(String value) {
    return value != null && (value.strip().equals("1") || value.strip().equalsIgnoreCase("true"));
  }

  /** An IPv4 address or a bracketed IPv6 one: no application name to replace. */
  private static boolean isAddress(String host) {
    return host.startsWith("[") || host.matches("[0-9.]+");
  }

  private static boolean bool(String variable, String value, boolean fallback) throws Invalid {
    if (blank(value)) {
      return fallback;
    }
    return switch (value.trim().toLowerCase(Locale.ROOT)) {
      case "true", "1" -> true;
      case "false", "0" -> false;
      default -> throw new Invalid(variable + " is not true or false: '" + value.trim() + "'");
    };
  }

  /** {@code 120} and {@code 120s} both mean seconds; nothing else is accepted. */
  private static String stripSeconds(String value) {
    return value == null ? null : value.trim().replaceFirst("(?i)s$", "");
  }

  private static int positive(String variable, String value, int fallback) throws Invalid {
    if (blank(value)) {
      return fallback;
    }
    try {
      int parsed = Integer.parseInt(value.trim());
      if (parsed > 0) {
        return parsed;
      }
    } catch (NumberFormatException ignored) {
      // falls through to the one sentence below
    }
    throw new Invalid(variable + " is not a positive whole number: '" + value.trim() + "'");
  }

  static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
