package eu.wohlben.qits.runner.toolkit;

import eu.wohlben.qits.runner.protocol.RunnerCodec;
import eu.wohlben.qits.runner.protocol.RunnerDecodeException;
import eu.wohlben.qits.runner.protocol.RunnerMessage;
import eu.wohlben.qits.runner.protocol.RunnerWire;
import eu.wohlben.qits.runner.protocol.RunnerWire.CloseReason;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.json.JsonObject;
import java.net.URI;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * The runner's one connection: an outbound WebSocket to the host's {@code socketUrl}, with the
 * client's bearer on the upgrade. Nothing listens on the runner host — no inbound port, which is the
 * property that lets a runner live behind any NAT a person's VM happens to sit behind.
 *
 * <p>qits-ci-daemon's {@code ControlSocket} in its mechanics — vert.x {@link WebSocketClient}, capped
 * backoff, writes marshalled onto the connection's context — and the qits-workspace-daemon's in its
 * one invariant: <b>it reconnects forever</b>. A runner is its host's reason to be on the platform,
 * and the only right response to a lost socket is to get it back; 500 ms doubling to a 30 s cap,
 * reset by a connection that succeeds. Every reconnect mints its bearer first, so a token that
 * expired while the socket was up is replaced rather than presented. The edge's JWT expiry on an
 * open socket is the server's to tolerate; the runner only re-mints per dial.
 *
 * <p><b>Forever ends at one thing: the platform saying this runner no longer exists.</b> A runner
 * deleted while it was offline is never sent its {@code Retire}; it finds out on its next dial, and
 * would otherwise redial for as long as its host is up. Two answers say it, and only two:
 *
 * <ul>
 *   <li>the host closes the socket 1008 {@link CloseReason#RUNNER_DELETED} — the host's own word
 *       that the bearer is valid and no runner is registered with it. Believed at once.
 *   <li>the token endpoint refuses the client with {@code invalid_client} ({@link
 *       Bearer.ClientRefused}) — what a deleted runner's revoked client gets. Believed only as a
 *       streak: every dial for at least {@link RunnerSettings.Timing#refusalConfirmMillis} and at
 *       least {@link RunnerSettings.Timing#refusalConfirmAttempts} dials, with nothing else in
 *       between, since one such answer could be an idp that is not ready yet.
 * </ul>
 *
 * Everything else — a 502 from the edge, a refused connection, a timeout, a 401 on the upgrade, a
 * 5xx from the idp, any other close — is the platform being down or restarting, breaks a streak, and
 * is retried as it always was. On either answer the socket stops for good and tells its listener
 * {@link Listener#onDeleted}.
 *
 * <p>Frames are decoded through the runner's {@link RunnerCodec}: lifecycle and domain alike. An
 * undecodable or unknown frame is dropped and logged, never fatal.
 *
 * @param <D> the runner's own sealed message set
 */
public final class ControlSocket<D extends RunnerMessage> {

  private static final Logger LOG = Logger.getLogger(ControlSocket.class);

  /** What the socket tells its owner. */
  public interface Listener {

    /** A session began. Say {@code Hello}. Called off the event loop is not guaranteed. */
    void onConnected();

    /** A decodable frame arrived. */
    void onMessage(RunnerMessage message);

    /** The session ended. The socket is already scheduling its own reconnect. */
    void onClosed();

    /**
     * The platform said this runner no longer exists (see the class javadoc). The socket has stopped
     * for good and will not redial; {@code why} is for the log.
     */
    default void onDeleted(String why) {}

    /**
     * A {@code Heartbeat} was written on a connected socket — the periodic proof this runner is
     * talking to its host, which {@link RunnerRuntime} turns into the heartbeat file {@link
     * Heartbeat} reads. Never called while disconnected.
     */
    default void onHeartbeatSent() {}
  }

  private final Vertx vertx;
  private final String who;
  private final String url;
  private final Supplier<Future<String>> bearer;
  private final RunnerSettings.Timing timing;
  private final RunnerCodec<D> codec;
  private final Listener listener;

  private volatile WebSocketClient client;
  private volatile WebSocket socket;
  private volatile Context socketContext;
  private volatile boolean stopped;
  private long heartbeatTimer = -1;

  /** The {@code invalid_client} streak: when it began (0 for none) and how many dials it holds. */
  private long refusedSinceMillis;

  private int refusals;

  /**
   * @param who how the runner names itself in the log ({@link RunnerIdentity#logName()})
   */
  public ControlSocket(
      Vertx vertx,
      String who,
      String url,
      Supplier<Future<String>> bearer,
      RunnerSettings.Timing timing,
      RunnerCodec<D> codec,
      Listener listener) {
    this.vertx = vertx;
    this.who = who;
    this.url = url;
    this.bearer = bearer;
    this.timing = timing;
    this.codec = codec;
    this.listener = listener;
  }

  /** Begin dialling. Returns at once; sessions are reported to the listener. */
  public void start() {
    client = vertx.createWebSocketClient();
    if (timing.heartbeatMillis() > 0) {
      heartbeatTimer = vertx.setPeriodic(timing.heartbeatMillis(), id -> heartbeat());
    }
    connect(0);
  }

  private void connect(int attempt) {
    if (stopped) {
      return;
    }
    WebSocketConnectOptions options = optionsFor(url);
    bearer
        .get()
        .compose(
            token -> {
              options.putHeader("Authorization", "Bearer " + token);
              return client.connect(options);
            })
        .onSuccess(this::onConnected)
        .onFailure(
            t -> {
              if (confirmsDeletion(t)) {
                return;
              }
              long backoff = backoffFor(attempt);
              LOG.warnf(
                  "%s could not connect to %s (attempt %d): %s; retrying in %dms",
                  who, url, attempt + 1, t.getMessage(), backoff);
              vertx.setTimer(backoff, id -> connect(attempt + 1));
            });
  }

  /**
   * Count a failed dial toward the {@code invalid_client} streak, or break the streak; true when the
   * streak is now long enough to believe, in which case the socket has stopped and the listener has
   * been told.
   */
  private boolean confirmsDeletion(Throwable failure) {
    if (!(failure instanceof Bearer.ClientRefused refused)) {
      refusedSinceMillis = 0;
      refusals = 0;
      return false;
    }
    long now = System.currentTimeMillis();
    if (refusals == 0) {
      refusedSinceMillis = now;
    }
    refusals++;
    long lasted = now - refusedSinceMillis;
    if (refusals < timing.refusalConfirmAttempts() || lasted < timing.refusalConfirmMillis()) {
      LOG.warnf(
          "%s's client was refused (%s), %d time(s) in a row over %ds; if it goes on for"
              + " %ds this runner reads itself as deleted",
          who, refused.getMessage(), refusals, lasted / 1000, timing.refusalConfirmMillis() / 1000);
      return false;
    }
    deleted(
        "the token endpoint has refused this runner's client for "
            + lasted / 1000
            + "s ("
            + refusals
            + " dials): "
            + refused.getMessage());
    return true;
  }

  /** The platform said this runner is gone: stop for good, then say so. Once. */
  private void deleted(String why) {
    if (stopped) {
      return;
    }
    stop();
    listener.onDeleted(why);
  }

  long backoffFor(int attempt) {
    long backoff = timing.initialBackoffMillis() * (1L << Math.min(attempt, 20));
    return Math.min(timing.maxBackoffMillis(), Math.max(1, backoff));
  }

  private void onConnected(WebSocket ws) {
    refusedSinceMillis = 0;
    refusals = 0;
    socketContext = vertx.getOrCreateContext();
    ws.textMessageHandler(this::onFrame);
    ws.exceptionHandler(t -> LOG.debugf("%s control socket error: %s", who, t.getMessage()));
    ws.closeHandler(
        v -> {
          socket = null;
          listener.onClosed();
          if (isDeletedClose(ws.closeStatusCode(), ws.closeReason())) {
            LOG.warnf("%s's socket was closed %s by the host", who, CloseReason.RUNNER_DELETED);
            deleted("the host closed the socket " + CloseReason.RUNNER_DELETED);
            return;
          }
          if (!stopped) {
            LOG.warnf("%s lost its connection to %s; reconnecting", who, url);
            vertx.setTimer(timing.initialBackoffMillis(), id -> connect(0));
          }
        });
    socket = ws;
    listener.onConnected();
  }

  private void onFrame(String json) {
    RunnerMessage message;
    try {
      message = codec.decode(new JsonObject(json).getMap());
    } catch (RunnerDecodeException e) {
      // Dropped, not fatal: a host one capability ahead sends frames this binary cannot know, and
      // a runner that died of them would take every item it holds with it.
      LOG.warnf(
          "%s dropped a frame it does not understand (%s): %s", who, e.reason(), e.getMessage());
      return;
    } catch (RuntimeException e) {
      LOG.warnf("%s dropped an undecodable frame: %s", who, e.getMessage());
      return;
    }
    listener.onMessage(message);
  }

  /** Write a frame, marshalled onto the connection's context; fails when there is no session. */
  public Future<Void> send(RunnerMessage message) {
    WebSocket ws = socket;
    if (ws == null || ws.isClosed()) {
      return Future.failedFuture(who + " control socket is not open");
    }
    String json = new JsonObject(codec.encode(message)).encode();
    Context context = socketContext;
    if (context != null && Vertx.currentContext() != context) {
      Promise<Void> promise = Promise.promise();
      context.runOnContext(v -> ws.writeTextMessage(json).onComplete(promise));
      return promise.future();
    }
    return ws.writeTextMessage(json);
  }

  /** End for good: no reconnect after this. */
  public Future<Void> stop() {
    stopped = true;
    if (heartbeatTimer >= 0) {
      vertx.cancelTimer(heartbeatTimer);
    }
    WebSocket ws = socket;
    Future<Void> closed = ws == null || ws.isClosed() ? Future.succeededFuture() : ws.close();
    return closed
        .otherwiseEmpty()
        .onComplete(
            v -> {
              WebSocketClient c = client;
              if (c != null) {
                c.close();
              }
            });
  }

  private void heartbeat() {
    WebSocket ws = socket;
    if (ws != null && !ws.isClosed()) {
      send(new eu.wohlben.qits.runner.protocol.Heartbeat())
          .onSuccess(v -> listener.onHeartbeatSent());
    }
  }

  /** 1008 {@link CloseReason#RUNNER_DELETED}: the host's word that this runner does not exist. */
  static boolean isDeletedClose(Short status, String reason) {
    return status != null
        && status == RunnerWire.CLOSE_POLICY_VIOLATION
        && CloseReason.RUNNER_DELETED.equals(reason);
  }

  /**
   * The url as vert.x wants it, path and query carried across untouched. {@code http(s)} is read as
   * {@code ws(s)}, so a register door that answers the service's base url still dials.
   */
  static WebSocketConnectOptions optionsFor(String url) {
    URI uri = URI.create(url);
    if (uri.getHost() == null) {
      throw new IllegalArgumentException("no host in '" + url + "'");
    }
    String scheme = uri.getScheme() == null ? "ws" : uri.getScheme().toLowerCase();
    boolean ssl = scheme.equals("wss") || scheme.equals("https");
    int port = uri.getPort() != -1 ? uri.getPort() : (ssl ? 443 : 80);
    String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
    if (uri.getRawQuery() != null) {
      path = path + "?" + uri.getRawQuery();
    }
    return new WebSocketConnectOptions()
        .setHost(uri.getHost())
        .setPort(port)
        .setURI(path)
        .setSsl(ssl);
  }
}
