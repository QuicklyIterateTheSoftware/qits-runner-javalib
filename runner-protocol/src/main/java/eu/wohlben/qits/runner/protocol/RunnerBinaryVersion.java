package eu.wohlben.qits.runner.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * <b>What a runner's protocol jar was released with</b>: the {@code version} a build filtered into
 * a properties file beside an anchor class, so "the jar's version is the image tag" holds for every
 * runner kind as it does for CI's {@code CiRunnerBinary}.
 *
 * <p>A runner is a container on its host, started from an image this jar's release pushes beside
 * it. A host renders the install script and names the {@link Upgrade} image from this version,
 * which makes the pin a line in the host's pom that its own release request gated, rather than
 * whatever happened to be newest in the registry — and it is the same release as the wire contract
 * the host compiles against, so the host and the runner it hands out cannot disagree about the
 * protocol by a deployment act.
 *
 * <p>The embedder's module filters {@code ${project.version}} into the resource (and only that
 * resource) at build time. A build from a working tree therefore names a {@code -SNAPSHOT} or the
 * previous release, which is honest: nothing has been published for the tree in hand.
 *
 * <p><b>The contract this names is an image tag</b>, so a missing, blank or unfiltered value is
 * refused loudly here rather than defaulted: a fallback would compose a {@code docker pull} that
 * fails on a machine nobody on the platform can see.
 */
public final class RunnerBinaryVersion {

  private RunnerBinaryVersion() {}

  /**
   * Read the {@code version} property of {@code resource}, resolved against {@code anchor} as
   * {@link Class#getResourceAsStream} does (so a bare name is beside the anchor class).
   *
   * @throws IllegalStateException when the resource is missing, unreadable, blank or unfiltered
   */
  public static String read(Class<?> anchor, String resource) {
    Properties p = new Properties();
    try (InputStream in = anchor.getResourceAsStream(resource)) {
      if (in == null) {
        // A jar with the resource missing is a broken build, and an install script that silently
        // pulled "" or "latest" is the failure this class exists to remove.
        throw new IllegalStateException(
            resource + " is not on the classpath beside " + anchor.getName());
      }
      p.load(in);
    } catch (IOException e) {
      throw new IllegalStateException("cannot read " + resource, e);
    }
    String version = p.getProperty("version", "");
    if (version.isBlank() || version.startsWith("$")) {
      // `$` catches resource filtering switched off, leaving the literal `${project.version}`.
      throw new IllegalStateException(resource + " carries no resolved version: '" + version + "'");
    }
    return version;
  }
}
