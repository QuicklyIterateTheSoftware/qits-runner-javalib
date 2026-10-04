package eu.wohlben.qits.runner.toolkit.testing;

import eu.wohlben.qits.runner.protocol.RunnerCodec;
import eu.wohlben.qits.runner.protocol.RunnerMessage;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.runner.toolkit.Telemetry;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A runner's host, on one real in-JVM Vert.x server: the identity's register door, the idp's token
 * endpoint, the runner control socket and the log collector, each with its half of the conversation
 * scripted by the suite. Frames cross a real socket as JSON through the runner's own {@link
 * RunnerCodec}, so what the suite asserts is what a real host would read.
 *
 * <p><b>Shipped in the toolkit's main jar, not a test jar</b>, so an embedder's suite (the harness,
 * qits-624's runner) drives its runner against the same fake the toolkit's own suite does. It
 * depends on nothing beyond the toolkit's own dependencies and never on JUnit: a failed {@link
 * #await} is an {@link AssertionError}, which every test framework reports as a failure. A native
 * embedder never reaches this class, so it is dead code there.
 *
 * <p>The token endpoint answers {@code 401} to any request carrying an {@code Authorization}
 * header, which is what the live edge does: it consumes the header as its own credential, so a
 * client authenticating with Basic reaches the idp as nobody. A bearer that sends its pair any way
 * but in the form body fails here as it would live.
 */
public final class FakeRunnerHost implements AutoCloseable {

  /** The token endpoint's path. */
  public static final String TOKEN_PATH = "/token";

  /** The control socket's path. */
  public static final String SOCKET_PATH = "/runner/socket";

  /** The log collector's path: {@code base() + OTEL_PATH} is a runner's telemetry url. */
  public static final String OTEL_PATH = "/otel";

  private final RunnerIdentity identity;
  private final RunnerCodec<?> codec;
  private final Pattern registerDoor;
  private final HttpServer server;

  /** Every frame the runner sent, decoded, in arrival order. */
  public final List<RunnerMessage> received = Collections.synchronizedList(new ArrayList<>());

  /** Each register request's path, body and Authorization header, in arrival order. */
  public final List<String> registerPaths = Collections.synchronizedList(new ArrayList<>());

  public final List<String> registerBodies = Collections.synchronizedList(new ArrayList<>());
  public final List<String> registerAuthorizations =
      Collections.synchronizedList(new ArrayList<>());

  /** Each token request's form body, and the Authorization header it carried ({@code null}). */
  public final List<String> tokenBodies = Collections.synchronizedList(new ArrayList<>());

  public final List<String> tokenAuthorizations = Collections.synchronizedList(new ArrayList<>());

  /** How many socket upgrades arrived. */
  public final AtomicInteger upgrades = new AtomicInteger();

  /** Each log export's body and its Authorization header, in arrival order. */
  public final List<byte[]> telemetryBodies = Collections.synchronizedList(new ArrayList<>());

  public final List<String> telemetryAuthorizations =
      Collections.synchronizedList(new ArrayList<>());

  public volatile int telemetryStatus = 200;

  /** The register door's status, and its body; {@code null} answers {@link #clientJson()}. */
  public volatile int registerStatus = 200;

  public volatile String registerBody;
  public volatile String accessToken = "runner-access-token";
  public volatile long expiresIn = 300;

  /** When not 200, the token endpoint answers this status with {@link #tokenErrorBody}. */
  public volatile int tokenStatus = 200;

  public volatile String tokenErrorBody = "";

  /** The latest upgrade's headers, and its socket. */
  public volatile MultiMap upgradeHeaders;

  public volatile ServerWebSocket socket;

  /** What the host does with each frame it receives, on the server's event loop. */
  public volatile BiConsumer<FakeRunnerHost, RunnerMessage> script = (h, m) -> {};

  /**
   * A host for {@code identity}'s runner on an ephemeral port, decoding and encoding through {@code
   * codec} — the runner's own, so its domain frames cross too.
   */
  public FakeRunnerHost(Vertx vertx, RunnerIdentity identity, RunnerCodec<?> codec)
      throws Exception {
    this.identity = identity;
    this.codec = codec;
    this.registerDoor = registerDoor(identity.registerPath());
    this.server =
        vertx
            .createHttpServer()
            .requestHandler(this::onRequest)
            .webSocketHandler(this::onUpgrade)
            .listen(0)
            .toCompletionStage()
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
  }

  /** The host's url — what {@code <envPrefix>URL} is set to. */
  public String base() {
    return "http://127.0.0.1:" + server.actualPort();
  }

  /** The client JSON the register door answers with by default, for runner {@code runnerId}. */
  public String clientJson(String runnerId) {
    return new JsonObject()
        .put("clientId", identity.logName() + "-" + runnerId)
        .put("secret", "client-secret")
        .put("tokenUrl", base() + TOKEN_PATH)
        .put("audience", identity.hostName())
        .put("socketUrl", "ws://127.0.0.1:" + server.actualPort() + SOCKET_PATH)
        .encode();
  }

  /** {@link #clientJson(String)} for runner {@code r1}, the suites' usual id. */
  public String clientJson() {
    return clientJson("r1");
  }

  private void onRequest(HttpServerRequest request) {
    request
        .body()
        .onSuccess(
            body -> {
              Matcher door = registerDoor.matcher(request.path());
              if (door.matches()) {
                registerPaths.add(request.path());
                registerBodies.add(body.toString());
                registerAuthorizations.add(request.getHeader("Authorization"));
                request
                    .response()
                    .setStatusCode(registerStatus)
                    .end(registerBody != null ? registerBody : clientJson(door.group(1)));
              } else if (request.path().equals(TOKEN_PATH)) {
                tokenBodies.add(body.toString());
                tokenAuthorizations.add(request.getHeader("Authorization"));
                if (request.getHeader("Authorization") != null) {
                  request
                      .response()
                      .setStatusCode(401)
                      .end("{\"error\":\"client authentication is required\"}");
                  return;
                }
                if (tokenStatus != 200) {
                  request.response().setStatusCode(tokenStatus).end(tokenErrorBody);
                  return;
                }
                request
                    .response()
                    .end(
                        new JsonObject()
                            .put("access_token", accessToken)
                            .put("expires_in", expiresIn)
                            .encode());
              } else if (request.path().equals(OTEL_PATH + Telemetry.LOGS_PATH)) {
                // qits-observability's receiver, as the edge forwards it.
                telemetryBodies.add(body.getBytes());
                telemetryAuthorizations.add(request.getHeader("Authorization"));
                request.response().setStatusCode(telemetryStatus).end();
              } else {
                request.response().setStatusCode(404).end();
              }
            });
  }

  private void onUpgrade(ServerWebSocket ws) {
    if (!SOCKET_PATH.equals(ws.path())) {
      ws.reject(404);
      return;
    }
    upgrades.incrementAndGet();
    upgradeHeaders = ws.headers();
    socket = ws;
    ws.textMessageHandler(
        json -> {
          RunnerMessage message = codec.decode(new JsonObject(json).getMap());
          received.add(message);
          script.accept(this, message);
        });
  }

  /** A frame as text, bypassing the codec — what a host a version ahead of the runner sends. */
  public void sendRaw(String json) {
    socket.writeTextMessage(json);
  }

  /** Close the current socket the way a host does a refusal: a code and a reason. */
  public void closeSocket(int code, String reason) {
    socket.close((short) code, reason);
  }

  /** Send a frame — lifecycle or domain — through the runner's codec. */
  public void send(RunnerMessage message) {
    socket.writeTextMessage(new JsonObject(codec.encode(message)).encode());
  }

  /** Wait until {@code count} frames of {@code type} have arrived; returns the last. */
  public <T extends RunnerMessage> T await(Class<T> type, int count) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < deadline) {
      List<T> matching = all(type);
      if (matching.size() >= count) {
        return matching.get(count - 1);
      }
      Thread.sleep(20);
    }
    throw new AssertionError(
        "expected " + count + " " + type.getSimpleName() + ", saw " + snapshot());
  }

  public <T extends RunnerMessage> T await(Class<T> type) throws InterruptedException {
    return await(type, 1);
  }

  /** Every received frame of {@code type}, in arrival order. */
  public <T extends RunnerMessage> List<T> all(Class<T> type) {
    synchronized (received) {
      return received.stream().filter(type::isInstance).map(type::cast).toList();
    }
  }

  private List<RunnerMessage> snapshot() {
    synchronized (received) {
      return List.copyOf(received);
    }
  }

  @Override
  public void close() throws Exception {
    server.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  /** The identity's register path as a pattern, the runner id captured where {@code {id}} is. */
  private static Pattern registerDoor(String registerPath) {
    int at = registerPath.indexOf(RunnerIdentity.ID_PLACEHOLDER);
    return Pattern.compile(
        Pattern.quote(registerPath.substring(0, at))
            + "([^/]+)"
            + Pattern.quote(registerPath.substring(at + RunnerIdentity.ID_PLACEHOLDER.length())));
  }
}
