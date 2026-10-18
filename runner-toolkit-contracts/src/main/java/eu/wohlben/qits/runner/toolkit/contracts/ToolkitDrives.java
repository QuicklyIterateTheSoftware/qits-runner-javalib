package eu.wohlben.qits.runner.toolkit.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.pact.consumer.ConsumerPact;
import eu.wohlben.qits.pact.consumer.GoldenMasters;
import eu.wohlben.qits.pact.consumer.GoldenMasters.Operation;
import eu.wohlben.qits.runner.protocol.RunnerCapabilities;
import eu.wohlben.qits.runner.protocol.RunnerVocabulary;
import eu.wohlben.qits.runner.toolkit.Bearer;
import eu.wohlben.qits.runner.toolkit.ClientCredentials;
import eu.wohlben.qits.runner.toolkit.Http;
import eu.wohlben.qits.runner.toolkit.Registration;
import eu.wohlben.qits.runner.toolkit.RunnerExit;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.runner.toolkit.RunnerSettings;
import eu.wohlben.qits.runner.toolkit.Telemetry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.stream.Stream;

/**
 * The toolkit's REAL classes making each call against the pact mock server, so what a daemon's
 * pact records is what {@link Bearer}, {@link Telemetry} and {@link Registration} put on the wire.
 * Each drive reads the recorded status to know which answer to expect — a 200's payload, or the
 * refusal the toolkit turns a 4xx into — and asserts what the toolkit made of it.
 */
final class ToolkitDrives {

  private static final long TIMEOUT_MILLIS = 10_000;

  private ToolkitDrives() {}

  /** {@link Bearer#token()} with the state's client, at the recorded token path. */
  static ConsumerPact.Call token(GoldenMasters masters) {
    return (url, recorded) -> {
      Map<String, String> params = recorded.params();
      ClientCredentials client =
          new ClientCredentials(
              params.get("clientId"),
              params.getOrDefault("clientSecret", params.get("secret")),
              url + recorded.examplePath(),
              params.get("audience"),
              "wss://runner-host.suite.example/socket",
              "");
      withVertx(
          vertx -> {
            Bearer bearer = new Bearer(new Http(vertx, TIMEOUT_MILLIS), client, System::currentTimeMillis);
            if (recorded.status() == 200) {
              String token = await(bearer.token());
              assertEquals(answer(masters, recorded).path("access_token").asText(), token);
            } else {
              ExecutionException failed =
                  assertThrows(ExecutionException.class, () -> await(bearer.token()));
              if ("invalid_client".equals(answer(masters, recorded).path("error").asText())) {
                assertInstanceOf(Bearer.ClientRefused.class, failed.getCause());
              }
            }
          });
    };
  }

  /**
   * One line through {@link Telemetry}, to the endpoint {@link RunnerSettings#telemetryUrl} derives
   * ({@link RunnerSettings#TELEMETRY_PATH} below the host), sent by {@link Telemetry#stop}: the
   * timer is set an hour out so that the stop's send is the only one.
   */
  static ConsumerPact.Call logExport() {
    return (url, recorded) -> {
      String token = bearer(recorded.params());
      withVertx(
          vertx -> {
            Telemetry telemetry =
                new Telemetry(
                    vertx,
                    "pact-runner",
                    new Http(vertx, TIMEOUT_MILLIS),
                    url + RunnerSettings.TELEMETRY_PATH,
                    Map.of("service.name", "qits-pact-runner"),
                    RunnerToolkitPact.VIA.version(),
                    new Telemetry.Settings(16, 16, 3_600_000),
                    System::nanoTime);
            telemetry.start(() -> Future.succeededFuture(token));
            LogRecord line = new LogRecord(Level.INFO, "pact-runner ships one line");
            line.setLoggerName(ToolkitDrives.class.getName());
            telemetry.publish(line);
            telemetry.stop(TIMEOUT_MILLIS);
          });
    };
  }

  /**
   * {@link Registration#ensure} with the state's token and runner id, and the capabilities the host
   * recorded. {@code identity} is the embedder's own, so its register path is what is pacted; null
   * derives one from the recorded path.
   */
  static ConsumerPact.Call register(GoldenMasters masters, RunnerIdentity identity) {
    return (url, recorded) -> {
      Map<String, String> params = recorded.params();
      RunnerIdentity who = identity != null ? identity : identityFor(recorded);
      Path state = Files.createTempDirectory("runner-toolkit-pact-");
      try {
        Map<String, String> env = new HashMap<>();
        env.put(who.env(RunnerSettings.URL), url);
        env.put(who.env(RunnerSettings.ID), runnerId(params));
        env.put(who.env(RunnerSettings.REGISTRATION_TOKEN), bearer(params));
        env.put(who.env(RunnerSettings.STATE_DIR), state.toString());
        RunnerSettings settings = RunnerSettings.parse(who, env::get);
        RunnerCapabilities capabilities = capabilities(recorded);
        withVertx(
            vertx -> {
              Registration registration =
                  new Registration(who, new Http(vertx, TIMEOUT_MILLIS), TIMEOUT_MILLIS);
              int status = recorded.status();
              if (status == 200) {
                ClientCredentials client = registration.ensure(settings, capabilities);
                JsonNode answer = answer(masters, recorded);
                assertEquals(answer.path("clientId").asText(), client.clientId());
                assertEquals(answer.path("secret").asText(), client.secret());
                assertEquals(answer.path("tokenUrl").asText(), client.tokenUrl());
                assertEquals(answer.path("audience").asText(null), client.audience());
                assertEquals(answer.path("socketUrl").asText(), client.socketUrl());
              } else {
                Registration.Failed failed =
                    assertThrows(
                        Registration.Failed.class, () -> registration.ensure(settings, capabilities));
                assertEquals(
                    status >= 400 && status < 500
                        ? RunnerExit.REGISTRATION_REFUSED
                        : RunnerExit.REGISTRATION_UNREACHABLE,
                    failed.exitCode(),
                    failed.getMessage());
              }
            });
      } finally {
        deleteTree(state);
      }
    };
  }

  /**
   * The capabilities the host recorded in the request body, so the toolkit sends exactly them.
   * {@link Registration} always writes all four keys, so a recording that holds fewer is a
   * mismatch this drive surfaces rather than hides.
   */
  static RunnerCapabilities capabilities(Operation recorded) {
    JsonNode caps = recorded.body() == null ? null : recorded.body().path("capabilities");
    if (caps == null || !caps.isObject()) {
      return new RunnerCapabilities(true, "amd64", "linux", Map.of());
    }
    Map<String, String> labels = new LinkedHashMap<>();
    caps.path("labels").properties().forEach(e -> labels.put(e.getKey(), e.getValue().asText()));
    return new RunnerCapabilities(
        caps.path("docker").asBoolean(true),
        caps.path("arch").isMissingNode() ? null : caps.path("arch").asText(),
        caps.path("os").isMissingNode() ? null : caps.path("os").asText(),
        labels);
  }

  /**
   * A stand-in identity whose register path is the recorded one, the runner id param as {@link
   * RunnerIdentity#ID_PLACEHOLDER}: for a suite that has no embedder's identity to hand.
   */
  static RunnerIdentity identityFor(Operation recorded) {
    String path = recorded.path();
    for (String name : new String[] {"runnerId", "id"}) {
      path = path.replace("{" + name + "}", RunnerIdentity.ID_PLACEHOLDER);
    }
    return new RunnerIdentity(
        "pact",
        "QITS_PACT_RUNNER_",
        "qits.pact.runner",
        "qits-pact-runner",
        "qits/qits-pact-runner",
        "/var/lib/qits-pact-runner",
        path,
        "qits-pact-runner",
        RunnerToolkitPact.VIA.version(),
        1,
        new RunnerVocabulary("heldWork", "adoptedWork"),
        "work");
  }

  private static String runnerId(Map<String, String> params) {
    String id = params.getOrDefault("runnerId", params.get("id"));
    if (id == null) {
      throw new IllegalStateException("the provider state names no runnerId: " + params.keySet());
    }
    return id;
  }

  /** The bearer the state's {@code authorization} param carries, without its scheme. */
  private static String bearer(Map<String, String> params) {
    String authorization = params.get("authorization");
    if (authorization == null || !authorization.startsWith("Bearer ")) {
      throw new IllegalStateException(
          "the provider state carries no 'authorization' param of the form 'Bearer <token>': "
              + params.keySet());
    }
    String token = authorization.substring("Bearer ".length());
    assertFalse(token.isBlank(), "the state's bearer is blank");
    return token;
  }

  private static JsonNode answer(GoldenMasters masters, Operation recorded) {
    return masters.json(recorded.state(), recorded.operationId());
  }

  private static <T> T await(Future<T> future) throws Exception {
    return future.toCompletionStage().toCompletableFuture().get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
  }

  @FunctionalInterface
  private interface WithVertx {
    void run(Vertx vertx) throws Exception;
  }

  private static void withVertx(WithVertx body) throws Exception {
    Vertx vertx = Vertx.vertx();
    try {
      body.run(vertx);
    } finally {
      vertx.close().toCompletionStage().toCompletableFuture().get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    }
  }

  private static void deleteTree(Path root) throws IOException {
    try (Stream<Path> walk = Files.walk(root)) {
      for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(p);
      }
    }
  }
}
