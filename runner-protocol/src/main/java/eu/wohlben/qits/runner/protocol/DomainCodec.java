package eu.wohlben.qits.runner.protocol;

import java.util.Map;
import java.util.Set;

/**
 * A runner's own frames, as {@link RunnerCodec} hands them over: everything that is not a {@link
 * LifecycleMessage}. Handed in rather than inherited because a {@code sealed … permits} list cannot
 * span artifacts; see {@link RunnerMessage}.
 *
 * <p>Read fields through {@link WireMaps}, so a domain frame tolerates exactly what a lifecycle
 * frame does: unknown fields, either box for a number, a Vert.x {@code JsonObject} as a nested
 * object.
 *
 * @param <D> the runner's own sealed message set
 */
public interface DomainCodec<D extends RunnerMessage> {

  /** The runner's own message type; {@link RunnerCodec#encode} hands an instance of it here. */
  Class<D> messageType();

  /** The domain's own {@code "type"} values. None may be a lifecycle type. */
  Set<String> types();

  /** Flatten a domain frame to its wire map, including the {@code "type"} discriminator. */
  Map<String, Object> encode(D message);

  /**
   * Rebuild a domain frame. Only ever called with a {@code type} in {@link #types()}. A {@code
   * RuntimeException} thrown here is a {@link RunnerDecodeException.Reason#MALFORMED} frame.
   */
  D decode(String type, Map<String, Object> map);
}
