package eu.wohlben.qits.runner.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a runner says about the host it runs on, in its {@link Hello}.
 *
 * <p>{@code arch} and {@code os} are the Go/OCI spellings ({@code amd64}, {@code linux}) because
 * that is the vocabulary an image's platform is already written in, and a later match against one
 * should not need a translation table.
 *
 * <p><b>{@code labels} is a map, not a list</b>: a label that carries a value ({@code disk=ssd},
 * {@code site=home}) is the shape a matcher eventually needs, and a bare tag is expressible as a
 * key with an empty value while the reverse is not. Kept in insertion order, so the wire is a
 * function of the record rather than of a per-JVM hash salt.
 *
 * <p><b>{@code extensions} are the runner's own capability keys</b> (CI's {@code idRange}, written
 * only when known), after {@code labels} and in insertion order. Decoding puts every key the base
 * does not know here, in arrival order. Unmodifiable, ordered, and a null value is a key with null.
 */
public record RunnerCapabilities(
    boolean docker,
    String arch,
    String os,
    Map<String, String> labels,
    Map<String, Object> extensions) {

  public RunnerCapabilities {
    labels = labels == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(labels));
    extensions =
        extensions == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(extensions));
  }

  /** A host that says nothing beyond the base's four keys. */
  public RunnerCapabilities(boolean docker, String arch, String os, Map<String, String> labels) {
    this(docker, arch, os, labels, Map.of());
  }
}
