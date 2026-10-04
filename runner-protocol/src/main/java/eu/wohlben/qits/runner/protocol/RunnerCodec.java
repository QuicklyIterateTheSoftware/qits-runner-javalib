package eu.wohlben.qits.runner.protocol;

import static eu.wohlben.qits.runner.protocol.WireMaps.boolVal;
import static eu.wohlben.qits.runner.protocol.WireMaps.intVal;
import static eu.wohlben.qits.runner.protocol.WireMaps.object;
import static eu.wohlben.qits.runner.protocol.WireMaps.str;
import static eu.wohlben.qits.runner.protocol.WireMaps.stringList;
import static eu.wohlben.qits.runner.protocol.WireMaps.stringMap;

import eu.wohlben.qits.runner.protocol.RunnerDecodeException.Reason;
import eu.wohlben.qits.runner.protocol.RunnerWire.Field;
import eu.wohlben.qits.runner.protocol.RunnerWire.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The one place a runner's control-socket frames become (and un-become) a {@code Map<String,
 * Object>} — the wire's lowest common denominator. Framework-free on purpose: a host bridges the
 * map to and from JSON with its Jackson {@code ObjectMapper}, a runner with a Vert.x {@code
 * JsonObject}, so neither side reimplements the field mapping.
 *
 * <p><b>Delegating.</b> The ten {@link LifecycleMessage} frames are encoded and decoded here; every
 * other {@code type} goes to the runner's own {@link DomainCodec}. The lists a runner carries
 * across a lost socket are named by its {@link RunnerVocabulary}, and its own keys on {@link
 * Hello}, {@link Ack} and {@link RunnerCapabilities} travel in their ordered {@code extensions},
 * written after the lifecycle keys — which is CI's key order, so a CI-shaped domain is
 * byte-identical to {@code qits-ci-runner-protocol} 2026.1003.50846.
 *
 * <p><b>Tolerant of unknown fields, in both directions.</b> Decoding reads the keys it knows and
 * keeps the rest as extensions where a frame has them (ignores them where it has not), so a peer
 * that grew a field does not break one that has not — adding a field is not a capability bump, only
 * changing the meaning of one is. <b>An unknown {@code type} is not tolerated</b>: it throws {@link
 * RunnerDecodeException} with {@link Reason#UNKNOWN_TYPE}, because a message nobody here
 * understands cannot be turned into a message; what the receiver does with that (drop and log) is
 * its decision, not the codec's.
 *
 * @param <D> the runner's own sealed message set
 */
public final class RunnerCodec<D extends RunnerMessage> {

  private final RunnerVocabulary vocabulary;
  private final DomainCodec<D> domain;
  private final Class<D> domainType;
  private final Set<String> domainTypes;

  /**
   * @throws IllegalArgumentException when the domain claims a lifecycle {@code type}, which would
   *     make that type mean two things on one socket
   */
  public RunnerCodec(RunnerVocabulary vocabulary, DomainCodec<D> domain) {
    this.vocabulary = Objects.requireNonNull(vocabulary, "vocabulary");
    this.domain = Objects.requireNonNull(domain, "domain");
    this.domainType = Objects.requireNonNull(domain.messageType(), "domain.messageType()");
    this.domainTypes = Set.copyOf(domain.types());
    for (String type : domainTypes) {
      if (Type.ALL.contains(type)) {
        throw new IllegalArgumentException("domain type '" + type + "' is a lifecycle type");
      }
    }
  }

  public RunnerVocabulary vocabulary() {
    return vocabulary;
  }

  /**
   * Flatten a frame to its wire map, including the {@code "type"} discriminator.
   *
   * @throws IllegalArgumentException for a message that is neither a lifecycle frame nor the
   *     domain's, or an extension key that would overwrite a lifecycle key
   */
  public Map<String, Object> encode(RunnerMessage message) {
    if (message instanceof LifecycleMessage lifecycle) {
      return encodeLifecycle(lifecycle);
    }
    if (domainType.isInstance(message)) {
      return domain.encode(domainType.cast(message));
    }
    throw new IllegalArgumentException(
        "neither a lifecycle frame nor a " + domainType.getName() + ": " + message);
  }

  private Map<String, Object> encodeLifecycle(LifecycleMessage message) {
    Map<String, Object> map = new LinkedHashMap<>();
    switch (message) {
      case Hello m -> {
        map.put(Field.TYPE, Type.HELLO);
        map.put(Field.RUNNER_VERSION, m.runnerVersion());
        map.put(Field.CAPABILITY_VERSION, m.capabilityVersion());
        map.put(Field.SLOTS, m.slots());
        map.put(
            Field.CAPABILITIES, m.capabilities() == null ? null : capabilities(m.capabilities()));
        putExtensions(map, m.extensions(), vocabulary.heldField());
        // Only when there is something to say: a first connection's Hello is the frame it always
        // was.
        if (!m.held().isEmpty()) {
          map.put(vocabulary.heldField(), List.copyOf(m.held()));
        }
      }
      case Reserve _ -> map.put(Field.TYPE, Type.RESERVE);
      case Heartbeat _ -> map.put(Field.TYPE, Type.HEARTBEAT);
      case Ack m -> {
        map.put(Field.TYPE, Type.ACK);
        map.put(Field.CAPABILITY_VERSION, m.capabilityVersion());
        map.put(Field.SLOTS, m.slots());
        putExtensions(map, m.extensions(), vocabulary.adoptedField());
        if (m.adopted() != null) {
          map.put(vocabulary.adoptedField(), List.copyOf(m.adopted()));
        }
      }
      case Backlog m -> {
        map.put(Field.TYPE, Type.BACKLOG);
        map.put(Field.QUEUED, m.queued());
      }
      case Nothing _ -> map.put(Field.TYPE, Type.NOTHING);
      case Upgrade m -> {
        map.put(Field.TYPE, Type.UPGRADE);
        map.put(Field.VERSION, m.version());
        map.put(Field.IMAGE, m.image());
        map.put(Field.SHA256, m.sha256());
      }
      case Retire m -> {
        map.put(Field.TYPE, Type.RETIRE);
        map.put(Field.REASON, m.reason());
        // Only when it says something a SUPERSEDED retirement did not: see Retire.
        if (m.kind() != Retire.Kind.SUPERSEDED) {
          map.put(Field.KIND, m.kind().name());
        }
      }
      case Quarantined m -> {
        map.put(Field.TYPE, Type.QUARANTINED);
        map.put(Field.REASON, m.reason());
        map.put(Field.SINCE, m.since());
      }
      case Reinstated m -> {
        map.put(Field.TYPE, Type.REINSTATED);
        map.put(Field.BY, m.by());
      }
    }
    return map;
  }

  /**
   * Rebuild a frame from its wire map, dispatching on the {@code "type"} discriminator: a lifecycle
   * type here, a domain type through {@link DomainCodec#decode}.
   *
   * @throws RunnerDecodeException with {@link Reason#MISSING_TYPE}, {@link Reason#UNKNOWN_TYPE} or
   *     {@link Reason#MALFORMED}
   */
  public RunnerMessage decode(Map<String, Object> map) {
    String type = map == null ? null : str(map, Field.TYPE);
    if (type == null) {
      throw new RunnerDecodeException(
          Reason.MISSING_TYPE, null, "runner message has no '" + Field.TYPE + "' field");
    }
    try {
      if (domainTypes.contains(type)) {
        return domain.decode(type, map);
      }
      return switch (type) {
        case Type.HELLO ->
            new Hello(
                str(map, Field.RUNNER_VERSION),
                intVal(map, Field.CAPABILITY_VERSION),
                intVal(map, Field.SLOTS),
                capabilities(object(map, Field.CAPABILITIES)),
                stringList(map, vocabulary.heldField()),
                extensions(
                    map,
                    Set.of(
                        Field.TYPE,
                        Field.RUNNER_VERSION,
                        Field.CAPABILITY_VERSION,
                        Field.SLOTS,
                        Field.CAPABILITIES,
                        vocabulary.heldField())));
        case Type.RESERVE -> new Reserve();
        case Type.HEARTBEAT -> new Heartbeat();
        case Type.ACK ->
            new Ack(
                intVal(map, Field.CAPABILITY_VERSION),
                intVal(map, Field.SLOTS),
                map.get(vocabulary.adoptedField()) == null
                    ? null
                    : stringList(map, vocabulary.adoptedField()),
                extensions(
                    map,
                    Set.of(
                        Field.TYPE,
                        Field.CAPABILITY_VERSION,
                        Field.SLOTS,
                        vocabulary.adoptedField())));
        case Type.BACKLOG -> new Backlog(intVal(map, Field.QUEUED));
        case Type.NOTHING -> new Nothing();
        case Type.UPGRADE ->
            new Upgrade(str(map, Field.VERSION), str(map, Field.IMAGE), str(map, Field.SHA256));
        case Type.RETIRE ->
            new Retire(str(map, Field.REASON), Retire.Kind.fromWire(str(map, Field.KIND)));
        case Type.QUARANTINED -> new Quarantined(str(map, Field.REASON), str(map, Field.SINCE));
        case Type.REINSTATED -> new Reinstated(str(map, Field.BY));
        default ->
            throw new RunnerDecodeException(
                Reason.UNKNOWN_TYPE, type, "unknown runner message type: " + type);
      };
    } catch (RunnerDecodeException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new RunnerDecodeException(
          Reason.MALFORMED, type, "malformed runner '" + type + "' message: " + e.getMessage());
    }
  }

  // --- nested objects
  // -----------------------------------------------------------------------------

  private static Map<String, Object> capabilities(RunnerCapabilities c) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put(Field.DOCKER, c.docker());
    map.put(Field.ARCH, c.arch());
    map.put(Field.OS, c.os());
    map.put(Field.LABELS, new LinkedHashMap<>(c.labels()));
    putExtensions(map, c.extensions(), null);
    return map;
  }

  private static RunnerCapabilities capabilities(Map<String, Object> map) {
    if (map == null) {
      return null;
    }
    return new RunnerCapabilities(
        boolVal(map, Field.DOCKER),
        str(map, Field.ARCH),
        str(map, Field.OS),
        stringMap(map, Field.LABELS),
        extensions(map, Set.of(Field.DOCKER, Field.ARCH, Field.OS, Field.LABELS)));
  }

  // --- extensions
  // ---------------------------------------------------------------------------------

  /**
   * Append a frame's extensions after its lifecycle keys, in their order. An extension may not
   * overwrite a key already written, nor take the vocabulary's list name that follows it: either
   * would silently change what a peer reads under a base key.
   */
  private static void putExtensions(
      Map<String, Object> map, Map<String, Object> extensions, String reserved) {
    for (Map.Entry<String, Object> e : extensions.entrySet()) {
      if (map.containsKey(e.getKey()) || e.getKey().equals(reserved)) {
        throw new IllegalArgumentException(
            "extension '" + e.getKey() + "' collides with a lifecycle key");
      }
      map.put(e.getKey(), e.getValue());
    }
  }

  /** Every key the base does not know, in arrival order, with its value as it arrived. */
  private static Map<String, Object> extensions(Map<String, Object> map, Set<String> known) {
    Map<String, Object> extensions = new LinkedHashMap<>();
    for (Map.Entry<String, Object> e : map.entrySet()) {
      if (!known.contains(e.getKey())) {
        extensions.put(e.getKey(), e.getValue());
      }
    }
    return extensions;
  }
}
