package eu.wohlben.qits.runner.toolkit.contracts;

import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import eu.wohlben.qits.pact.consumer.GoldenMasters;
import eu.wohlben.qits.pact.consumer.Trigger;
import eu.wohlben.qits.pact.consumer.Via;
import eu.wohlben.qits.runner.protocol.RunnerBinaryVersion;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;

/**
 * <b>The REST calls qits-runner-toolkit makes on an embedder's behalf, as consumer pact rows</b>
 * (ticket qits-1163).
 *
 * <p>A call a library makes is pacted by the service that embeds it, not by the library: the
 * embedding daemon is the consumer, {@link #VIA} names the toolkit at the version the daemon pins,
 * and the daemon's own suite writes {@code pacts/<daemon>_qits-runner-javalib_<provider>.json}.
 * This class holds what every embedder shares — the rows per {@link Feature}, the providers'
 * golden masters and the version — and {@link #forConsumer} is where a daemon says which
 * features its real use sends:
 *
 * <pre>{@code
 * @TestFactory
 * Stream<DynamicTest> theToolkitsCalls() {
 *   return RunnerToolkitPact.forConsumer("qits-workspaces-runner-daemon")
 *       .register(RunnerToolkitPact.WORKSPACES, "registerRunner", WorkspacesRunnerIdentity.IDENTITY)
 *       .token()
 *       .logExport()
 *       .tests();
 * }
 * }</pre>
 *
 * <p>The three features are the toolkit's three POSTs ({@code Http}): the control WebSocket is not
 * REST and is not pacted here.
 */
public final class RunnerToolkitPact {

  /** The toolkit's repository, as a pact's {@code qits-via} and its file name spell it. */
  public static final String REPOSITORY = "qits-runner-javalib";

  /** The toolkit's maven coordinate, {@code groupId:artifactId}. */
  public static final String ARTIFACT = "eu.wohlben.qits:qits-runner-toolkit";

  /**
   * The library every pact here goes through: this module's own version, filtered in at build time,
   * which is the toolkit's — one release carries both jars, and {@link RunnerToolkitPacts} refuses a
   * classpath whose toolkit is another version.
   */
  public static final Via VIA =
      new Via(
          REPOSITORY,
          ARTIFACT,
          RunnerBinaryVersion.read(RunnerToolkitPact.class, "runner-toolkit-contracts.properties"));

  /** {@link Feature#TOKEN}'s provider. */
  public static final GoldenMasters IDP = GoldenMasters.of("qits-idp-service", "qits-idp");

  /** {@link Feature#LOG_EXPORT}'s provider. */
  public static final GoldenMasters OBSERVABILITY =
      GoldenMasters.of("qits-observability-service", "qits-observability");

  /** qits-workspaces-runner-daemon's register host; its golden masters are the daemon's to pin. */
  public static final GoldenMasters WORKSPACES =
      GoldenMasters.of("qits-workspaces-service", "qits-workspaces");

  /** qits-projects-desk-runner-daemon's register host; its golden masters are the daemon's to pin. */
  public static final GoldenMasters PROJECTS =
      GoldenMasters.of("qits-projects-service", "qits-projects");

  /** Every dial mints through {@code Bearer#token()}, and so does every log export batch. */
  public static final Trigger DIAL = Trigger.event("the control socket dial");

  /** {@code Telemetry} sends a batch on its timer, and the rest when the runner stops. */
  public static final Trigger LOG_INTERVAL = Trigger.schedule("the log export interval");

  /** {@code RunnerRuntime} registers once, at start, when it has a token and no client for it. */
  public static final Trigger START = Trigger.event("the runner's start");

  /** The token mint: {@code client_credentials}, the pair in the form body (qits-idp recorded it). */
  public static final GoldenInteraction MINT =
      GoldenInteraction.of(DIAL, "a commissioned client", "issueToken")
          .consumes("access_token", "expires_in");

  /**
   * A deleted runner's client: {@code Bearer} reads the OAuth {@code error}, and only {@code
   * invalid_client} makes it a {@code ClientRefused}, so the value is bound exactly.
   */
  public static final GoldenInteraction UNKNOWN_CLIENT =
      GoldenInteraction.of(DIAL, "no commissioned client with the given id", "issueToken")
          .consumes("error")
          .exact("error");

  /**
   * One OTLP/HTTP protobuf batch. The body is not pacted (pact matches JSON and text, not
   * protobuf), so the row binds the route, the content type and the bearer, and reads the status
   * only. qits-observability has not recorded its receiver yet: the route is {@code @Operation(hidden
   * = true)} and carries no operationId.
   */
  public static final GoldenInteraction EXPORT_LOGS =
      GoldenInteraction.of(LOG_INTERVAL, "an empty telemetry buffer", "exportLogs")
          .header("Content-Type", "application/x-protobuf")
          .header("Authorization", "{authorization}");

  /** The register door's provider states, which every host shares (qits-ci recorded them first). */
  public static final String UNREGISTERED = "an unregistered runner";

  public static final String REGISTERED = "a registered runner";

  /** The toolkit's REST features, each a set of rows against one provider. */
  public enum Feature {
    /** {@code Bearer}: POST {@code tokenUrl} at qits-idp. */
    TOKEN,
    /** {@code Telemetry}: POST {@code /observability/api/otel/v1/logs} at qits-observability. */
    LOG_EXPORT,
    /** {@code Registration}: POST {@code RunnerIdentity#registerPath} at the embedder's host. */
    REGISTER
  }

  /** {@link Feature#TOKEN}'s rows. */
  public static List<GoldenInteraction> tokenRows() {
    return List.of(MINT, UNKNOWN_CLIENT);
  }

  /** {@link Feature#LOG_EXPORT}'s rows. */
  public static List<GoldenInteraction> logExportRows() {
    return List.of(EXPORT_LOGS);
  }

  /**
   * {@link Feature#REGISTER}'s rows at a host whose register operation is {@code operationId}: the
   * client a 200 answers (every field {@code ClientCredentials} reads), and the refusal of a runner
   * already registered (status only).
   */
  public static List<GoldenInteraction> registerRows(String operationId) {
    return List.of(
        GoldenInteraction.of(START, UNREGISTERED, operationId)
            .header("Authorization", "{authorization}")
            .consumes("clientId", "secret", "tokenUrl", "audience", "socketUrl"),
        GoldenInteraction.of(START, REGISTERED, operationId)
            .header("Authorization", "{authorization}"));
  }

  /** Where a daemon declares the features its real use sends. */
  public static RunnerToolkitPacts forConsumer(String consumer) {
    return RunnerToolkitPacts.of(consumer);
  }

  /**
   * The version of qits-runner-toolkit on the classpath, from the jar's own maven metadata; null
   * when the toolkit is a directory of classes (an IDE, or this reactor before {@code package}).
   */
  static String toolkitVersionOnClasspath() {
    String resource = "META-INF/maven/eu.wohlben.qits/qits-runner-toolkit/pom.properties";
    ClassLoader loader = RunnerToolkitPact.class.getClassLoader();
    try (InputStream in = loader.getResourceAsStream(resource)) {
      if (in == null) {
        return null;
      }
      Properties p = new Properties();
      p.load(in);
      return p.getProperty("version");
    } catch (IOException e) {
      return null;
    }
  }

  private RunnerToolkitPact() {}
}
