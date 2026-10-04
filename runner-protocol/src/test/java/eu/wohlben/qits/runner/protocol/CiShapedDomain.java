package eu.wohlben.qits.runner.protocol;

import eu.wohlben.qits.cirunner.protocol.CiRunnerMessage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The CI runner as the base sees it, for {@link CiWireCompatibilityTest}: vocabulary {@code
 * heldRuns}/{@code adoptedRuns}, {@code registryMirrors} in {@link Ack}'s extensions (always, a
 * null one as a key with null — CI writes it that way), {@code idRange} in the capabilities'
 * extensions (only when known), and no domain frames, since only the lifecycle frames are compared.
 * Plus the two translations between CI's records and the base's, which is the whole of what
 * qits-772 will have to write on that side.
 */
final class CiShapedDomain implements DomainCodec<CiShapedDomain.NoCiFrames> {

  static final RunnerVocabulary VOCABULARY = new RunnerVocabulary("heldRuns", "adoptedRuns");

  static final String REGISTRY_MIRRORS = "registryMirrors";
  static final String ID_RANGE = "idRange";

  /** CI's own frames are not under test here. */
  interface NoCiFrames extends RunnerMessage {}

  static RunnerCodec<NoCiFrames> codec() {
    return new RunnerCodec<>(VOCABULARY, new CiShapedDomain());
  }

  @Override
  public Class<NoCiFrames> messageType() {
    return NoCiFrames.class;
  }

  @Override
  public Set<String> types() {
    return Set.of();
  }

  @Override
  public Map<String, Object> encode(NoCiFrames message) {
    throw new AssertionError("no domain frames");
  }

  @Override
  public NoCiFrames decode(String type, Map<String, Object> map) {
    throw new AssertionError("no domain frames");
  }

  // --- CI record → base record
  // --------------------------------------------------------------------

  static LifecycleMessage toBase(CiRunnerMessage message) {
    return switch (message) {
      case eu.wohlben.qits.cirunner.protocol.Hello m ->
          new Hello(
              m.runnerVersion(),
              m.capabilityVersion(),
              m.slots(),
              toBase(m.capabilities()),
              m.heldRuns(),
              Map.of());
      case eu.wohlben.qits.cirunner.protocol.Ack m -> {
        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put(REGISTRY_MIRRORS, m.registryMirrors());
        yield new Ack(m.capabilityVersion(), m.slots(), m.adoptedRuns(), extensions);
      }
      case eu.wohlben.qits.cirunner.protocol.Heartbeat _ -> new Heartbeat();
      case eu.wohlben.qits.cirunner.protocol.Backlog m -> new Backlog(m.queued());
      case eu.wohlben.qits.cirunner.protocol.Reserve _ -> new Reserve();
      case eu.wohlben.qits.cirunner.protocol.Nothing _ -> new Nothing();
      case eu.wohlben.qits.cirunner.protocol.Upgrade m ->
          new Upgrade(m.version(), m.image(), m.sha256());
      case eu.wohlben.qits.cirunner.protocol.Retire m ->
          new Retire(m.reason(), Retire.Kind.valueOf(m.kind().name()));
      case eu.wohlben.qits.cirunner.protocol.Quarantined m ->
          new Quarantined(m.reason(), m.since());
      case eu.wohlben.qits.cirunner.protocol.Reinstated m -> new Reinstated(m.by());
      default -> throw new IllegalArgumentException("not a lifecycle frame: " + message);
    };
  }

  private static RunnerCapabilities toBase(eu.wohlben.qits.cirunner.protocol.Capabilities c) {
    if (c == null) {
      return null;
    }
    Map<String, Object> extensions = new LinkedHashMap<>();
    if (c.idRange() != null) {
      extensions.put(ID_RANGE, c.idRange());
    }
    return new RunnerCapabilities(c.docker(), c.arch(), c.os(), c.labels(), extensions);
  }
}
