package eu.wohlben.qits.runner.toolkit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * What the toolkit knows about the host before the domain adds anything: whether the docker socket
 * is here, and the platform in an image's own spelling. The domain turns it into its {@code
 * RunnerCapabilities} ({@link RunnerDomain#capabilities}).
 *
 * @param docker whether the docker socket a workload would be handed exists on this host
 * @param arch {@code amd64}, {@code arm64}, or the JVM's own word for anything else
 * @param os {@code linux}, lower-cased
 */
public record HostFacts(boolean docker, String arch, String os) {

  /** Where the host's docker socket is mounted into a runner's container. */
  public static final String DOCKER_SOCKET = "/var/run/docker.sock";

  /**
   * This host. The platform is spelled the way an image's is ({@code amd64}, {@code arm64}, {@code
   * linux}), so a later match against an image's platform needs no table.
   */
  public static HostFacts detect() {
    String arch =
        switch (System.getProperty("os.arch", "").toLowerCase(Locale.ROOT)) {
          case "amd64", "x86_64" -> "amd64";
          case "aarch64", "arm64" -> "arm64";
          case String other -> other;
        };
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    return new HostFacts(Files.exists(Path.of(DOCKER_SOCKET)), arch, os);
  }
}
