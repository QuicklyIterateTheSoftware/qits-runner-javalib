package eu.wohlben.qits.runner.toolkit;

import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.Optional;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * <b>A pull under the runner's own login</b> — for its successor's image and for a domain's
 * workload images alike ({@link RunnerSession#registry()}).
 *
 * <p><b>The login is a throwaway {@code docker --config} directory</b>: {@code auths[<registry
 * host>].auth} is {@code base64(clientId + ":" + secret)}, the directory 0700 and the file 0600 from
 * creation, deleted in the {@code finally}. This is the runner's own commissioned client pair from
 * {@code client.json} — a {@code client_id}/{@code client_secret} the edge's docker realm spends at
 * the idp with {@code client_credentials} — never the short-lived JWT {@link Bearer} mints for the
 * control socket: that JWT is a bearer token, and the realm's Basic password slot only accepts a
 * client secret or an opaque {@code qits_tok_…}, so a JWT there is refused. The host's docker
 * config is never written, the secret is never logged, and a failure's detail has both it and its
 * base64 taken out.
 */
public final class RegistryLogin {

  private static final Logger LOG = Logger.getLogger(RegistryLogin.class);

  private final RunnerIdentity identity;
  private final RunnerDocker docker;
  private final Supplier<ClientCredentials> client;

  /**
   * @param client the runner's client pair once it is registered; null before, when nothing can be
   *     pulled under it
   */
  RegistryLogin(RunnerIdentity identity, RunnerDocker docker, Supplier<ClientCredentials> client) {
    this.identity = identity;
    this.docker = docker;
    this.client = client;
  }

  /**
   * Pull {@code image} under the runner's own login. Answers empty when it is here now, else the
   * failure's detail with the secret redacted.
   */
  public Optional<String> pull(String image) {
    try {
      LifecycleArgv.requireImage(image);
    } catch (IllegalArgumentException refused) {
      return Optional.of(refused.getMessage());
    }
    ClientCredentials pair = client.get();
    if (pair == null) {
      return Optional.of("the runner is not registered yet, so it has no login to pull with");
    }
    String auth =
        Base64.getEncoder()
            .encodeToString(
                (pair.clientId() + ":" + pair.secret()).getBytes(StandardCharsets.UTF_8));
    String document =
        new JsonObject()
            .put(
                "auths",
                new JsonObject().put(registryHost(image), new JsonObject().put("auth", auth)))
            .encode();
    Path configDir = null;
    try {
      try {
        configDir = stageConfig(identity, document);
      } catch (IOException | UnsupportedOperationException staging) {
        return Optional.of(
            "could not stage its registry login: " + staging.getClass().getSimpleName());
      }
      RunnerDocker.Result pulled = docker.run(LifecycleArgv.pull(docker.binary(), configDir, image));
      if (!pulled.ok()) {
        return Optional.of(
            "docker pull "
                + image
                + " failed: "
                + pulled.detail().replace(pair.secret(), "[redacted]").replace(auth, "[redacted]"));
      }
      return Optional.empty();
    } finally {
      removeConfig(identity, configDir);
    }
  }

  /**
   * The registry an image reference names — the {@code auths} key docker looks its login up by. A
   * first path segment with a dot, a colon or {@code localhost} is a host; anything else is Docker
   * Hub, whose key is the historical index url.
   */
  static String registryHost(String image) {
    int slash = image.indexOf('/');
    if (slash > 0) {
      String first = image.substring(0, slash);
      if (first.contains(".") || first.contains(":") || first.equals("localhost")) {
        return first;
      }
    }
    return "https://index.docker.io/v1/";
  }

  /** A fresh 0700 directory holding {@code config.json} at 0600, created with those modes. */
  static Path stageConfig(RunnerIdentity identity, String document) throws IOException {
    Path dir =
        Files.createTempDirectory(
            identity.namePrefix() + "-pull-",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    Path file =
        Files.createFile(
            dir.resolve("config.json"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    Files.writeString(file, document, StandardCharsets.UTF_8);
    return dir;
  }

  static void removeConfig(RunnerIdentity identity, Path dir) {
    if (dir == null) {
      return;
    }
    try {
      Files.deleteIfExists(dir.resolve("config.json"));
      Files.deleteIfExists(dir);
    } catch (IOException e) {
      // The path only, never the content: a stranded 0600 file in a 0700 directory is the worst.
      LOG.warnf(
          "%s could not remove its pull login at %s: %s", identity.logName(), dir, e.getMessage());
    }
  }
}
