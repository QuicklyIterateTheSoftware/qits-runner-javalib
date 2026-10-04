package eu.wohlben.qits.runner.toolkit;

import eu.wohlben.qits.runner.protocol.RunnerMessage;
import io.vertx.core.Vertx;
import java.io.PrintStream;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.jboss.logging.Logger;

/**
 * <b>A runner's process entry</b>: the generic half of qits-ci-runner-daemon's {@code Main}. A
 * Quarkus command-mode embedder calls {@link #main} from its one {@code @QuarkusMain} with the
 * managed {@code Vertx}; a plain-Java embedder passes {@code Vertx.vertx()}. Either way the answer
 * is the process's exit code.
 *
 * <p>The arguments and the environment are read in this order, and each answers before anything
 * after it starts:
 *
 * <ol>
 *   <li>{@code health} — the container healthcheck ({@link Heartbeat}): one file's mtime, no other
 *       variable, nothing dialled;
 *   <li>{@code <envPrefix>PRINT_VERSION} — print the identity's version and return 0, the image's
 *       smoke probe, with no log line beside it;
 *   <li>{@code <envPrefix>DECOMMISSION} — helper mode: this process is a deleted runner's
 *       decommission helper, not a runner;
 *   <li>otherwise the settings are parsed (exit 2 with one sentence naming the variable when they
 *       are not), the domain is built from them, and {@link RunnerRuntime} runs.
 * </ol>
 */
public final class RunnerLauncher {

  private static final Logger LOG = Logger.getLogger(RunnerLauncher.class);

  /** Set to a runner id, the process is that runner's decommission helper. */
  public static final String DECOMMISSION = "DECOMMISSION";

  private RunnerLauncher() {}

  /** The entry, reading this process's environment and printing to its stdout. */
  public static <D extends RunnerMessage> int main(
      RunnerIdentity identity,
      Function<RunnerSettings, RunnerDomain<D>> factory,
      Vertx vertx,
      String[] args) {
    return main(identity, factory, vertx, args, System::getenv, System.out);
  }

  /** The same entry with the environment and stdout handed in — a suite's, or a harness's. */
  public static <D extends RunnerMessage> int main(
      RunnerIdentity identity,
      Function<RunnerSettings, RunnerDomain<D>> factory,
      Vertx vertx,
      String[] args,
      Function<String, String> env,
      PrintStream out) {
    if (args != null && args.length > 0 && Heartbeat.ARG.equals(args[0])) {
      // Only the explicit argument selects it — the image's bare start passes none.
      return Heartbeat.run(
          env.apply(identity.env(RunnerSettings.STATE_DIR)), identity.defaultStateDir(), out);
    }
    if (RunnerSettings.printsVersion(env.apply(identity.env(RunnerSettings.PRINT_VERSION)))) {
      out.println(identity.version());
      return RunnerExit.OK;
    }
    String decommission = env.apply(identity.env(DECOMMISSION));
    if (decommission != null && !decommission.isBlank()) {
      return decommissionHelper(identity, decommission.strip(), env, out);
    }
    RunnerSettings settings;
    try {
      settings = RunnerSettings.parse(identity, env);
    } catch (RunnerSettings.Invalid invalid) {
      // The container's log is the only channel before anything is dialled, so this line is the
      // whole diagnosis an operator gets. It names the variable, never a value that could be a
      // secret.
      LOG.errorf("%s cannot start: %s. Exiting.", identity.logName(), invalid.getMessage());
      return RunnerExit.MISCONFIGURED;
    }
    HostFacts host = HostFacts.detect();
    Http http = new Http(vertx, settings.timing().httpTimeoutMillis());
    Telemetry telemetry =
        settings.telemetryUrl() == null
            ? Telemetry.off()
            : new Telemetry(
                vertx,
                identity.logName(),
                http,
                settings.telemetryUrl(),
                telemetryResource(identity, settings.runnerId(), Optional.empty(), host),
                identity.version(),
                Telemetry.Settings.defaults(),
                () -> System.currentTimeMillis() * 1_000_000L);
    // On the root logger, so every line this process writes is shipped — queued from here, sent
    // once the runner has a bearer.
    java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
    root.addHandler(telemetry);
    try {
      if (!settings.selfUpdate()) {
        LOG.infof(
            "%s is deployer-managed (%s=false): it ignores every Upgrade and is replaced by its"
                + " deployer",
            identity.logName(), identity.env(RunnerSettings.SELF_UPDATE));
      }
      if (settings.telemetryUrl() == null) {
        String raw = env.apply(identity.env(RunnerSettings.TELEMETRY_URL));
        LOG.infof(
            "%s ships its log nowhere (%s is %s)",
            identity.logName(),
            identity.env(RunnerSettings.TELEMETRY_URL),
            raw == null
                ? "unset and " + settings.url() + " has no application host to derive it from"
                : "empty");
      }
      RunnerDomain<D> domain = factory.apply(settings);
      return new RunnerRuntime<>(vertx, identity, settings, domain, host, telemetry).run();
    } finally {
      root.removeHandler(telemetry);
      http.close();
    }
  }

  /**
   * Helper mode: this process is a deleted runner's decommission helper ({@link Decommission}), not
   * a runner. Like the version probe it needs no other setting — the docker binary, the state
   * volume and the domain's teardown items arrive in its own environment — and writes plain lines:
   * the helper's {@code docker logs} is the whole record of what it did.
   */
  static int decommissionHelper(
      RunnerIdentity identity, String runnerId, Function<String, String> env, PrintStream out) {
    String say = identity.namePrefix() + " decommission: ";
    String binary = env.apply(identity.env(RunnerSettings.DOCKER_BINARY));
    String volume = env.apply(identity.env(LifecycleArgv.DECOMMISSION_VOLUME));
    try {
      return DecommissionHelper.finish(
          identity,
          new RunnerDocker(
              identity,
              binary == null || binary.isBlank() ? "docker" : binary.strip(),
              Duration.ofSeconds(RunnerSettings.DEFAULT_DOCKER_TIMEOUT_SECONDS)),
          runnerId,
          volume == null || volume.isBlank() ? null : volume.strip(),
          LifecycleArgv.decodeTeardown(env.apply(identity.env(LifecycleArgv.DECOMMISSION_TEARDOWN))),
          SelfContainer.detect(),
          Decommission.Settings.defaults(),
          line -> out.println(say + line));
    } catch (RuntimeException e) {
      out.println(say + "failed: " + e.getMessage());
      return 1;
    }
  }

  /**
   * Who is speaking, on every shipped line: one bucket, the identity's {@link
   * RunnerIdentity#serviceName()}, for every runner of the kind, told apart by {@code
   * <labelRoot>.id} — the id an operator sees in the host's runner list — and by the container it
   * runs in.
   */
  static Map<String, String> telemetryResource(
      RunnerIdentity identity, String runnerId, Optional<String> self, HostFacts host) {
    Map<String, String> resource = new LinkedHashMap<>();
    resource.put("service.name", identity.serviceName());
    resource.put("service.version", identity.version());
    resource.put("service.instance.id", self.orElse(runnerId));
    resource.put(identity.labelRoot() + ".id", runnerId);
    resource.put("host.arch", host.arch());
    resource.put("os.type", host.os());
    return resource;
  }
}
