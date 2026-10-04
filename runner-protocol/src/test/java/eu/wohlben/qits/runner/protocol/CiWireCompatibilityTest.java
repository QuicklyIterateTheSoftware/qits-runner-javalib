package eu.wohlben.qits.runner.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.cirunner.protocol.Capabilities;
import eu.wohlben.qits.cirunner.protocol.CiRunnerCodec;
import eu.wohlben.qits.cirunner.protocol.CiRunnerMessage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * <b>Byte identity with the CI runner's wire, against {@code
 * eu.wohlben.qits:qits-ci-runner-protocol:2026.1003.50846} itself</b> — the release the base was
 * moved from. Every lifecycle frame is encoded through CI's own {@code CiRunnerCodec} and through
 * {@link RunnerCodec} with a {@link CiShapedDomain}, and the two maps must be equal as ORDERED
 * entry lists, recursively (map equality alone would forgive a reordered key, and the JSON a peer
 * reads is written in this order); then each codec must decode the other's map to the equivalent
 * frame.
 *
 * <p><b>This test is qits-772's precondition</b>: moving qits-ci-runner-daemon and qits-ci-service
 * onto this jar must not change one byte that an installed runner or a deployed host reads. Never
 * loosen it — not to an unordered comparison, not by dropping a case, not by moving the pin to a CI
 * release that has drifted. A base change that fails here is a wire change for every CI runner in
 * the field.
 *
 * <p>Labels carry at most one entry in these cases: CI's {@code Capabilities} copies them with
 * {@code Map.copyOf}, whose iteration order is salted per JVM, so CI itself has no stable order for
 * two labels to be compared against.
 */
class CiWireCompatibilityTest {

  private static final RunnerCodec<CiShapedDomain.NoCiFrames> CODEC = CiShapedDomain.codec();

  private static final String SHA =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  private static Map<String, String> mirrors() {
    Map<String, String> mirrors = new LinkedHashMap<>();
    mirrors.put("mirror.dev.localhost:8080", "mirror.qits.wohlben.eu");
    mirrors.put("registry.dev.localhost:8080", "registry.qits.wohlben.eu");
    return mirrors;
  }

  static Stream<Arguments> frames() {
    var caps = new Capabilities(true, "amd64", "linux", Map.of("site", "home"));
    var capsWithRange =
        new Capabilities(true, "arm64", "linux", Map.of(), Capabilities.FULL_ID_RANGE);
    var capsNarrow = new Capabilities(false, "amd64", "linux", Map.of("disk", "ssd"), 65536L);
    return Stream.of(
        Arguments.of(
            "Hello, no held runs",
            new eu.wohlben.qits.cirunner.protocol.Hello("2026.1003.50846", 1, 2, caps)),
        Arguments.of(
            "Hello, held runs",
            new eu.wohlben.qits.cirunner.protocol.Hello(
                "2026.1003.50846", 1, 2, caps, List.of("run-1", "run-2"))),
        Arguments.of(
            "Hello, capabilities without idRange",
            new eu.wohlben.qits.cirunner.protocol.Hello(
                "2026.1003.50846", 1, 1, new Capabilities(true, "amd64", "linux", Map.of()))),
        Arguments.of(
            "Hello, capabilities with idRange",
            new eu.wohlben.qits.cirunner.protocol.Hello("2026.1003.50846", 1, 4, capsWithRange)),
        Arguments.of(
            "Hello, narrow idRange and held runs",
            new eu.wohlben.qits.cirunner.protocol.Hello(
                "2026.1003.50846", 1, 4, capsNarrow, List.of("run-9"))),
        Arguments.of(
            "Hello, no capabilities",
            new eu.wohlben.qits.cirunner.protocol.Hello("2026.1003.50846", 1, 1, null)),
        Arguments.of("Ack, registryMirrors null", new eu.wohlben.qits.cirunner.protocol.Ack(1, 3)),
        Arguments.of(
            "Ack, registryMirrors set", new eu.wohlben.qits.cirunner.protocol.Ack(1, 3, mirrors())),
        Arguments.of(
            "Ack, registryMirrors empty",
            new eu.wohlben.qits.cirunner.protocol.Ack(1, 3, Map.of())),
        Arguments.of(
            "Ack, adoptedRuns absent",
            new eu.wohlben.qits.cirunner.protocol.Ack(1, 3, mirrors(), null)),
        Arguments.of(
            "Ack, adoptedRuns empty",
            new eu.wohlben.qits.cirunner.protocol.Ack(1, 3, null, List.of())),
        Arguments.of(
            "Ack, adoptedRuns set",
            new eu.wohlben.qits.cirunner.protocol.Ack(1, 0, mirrors(), List.of("run-1", "run-2"))),
        Arguments.of(
            "Upgrade, with sha256",
            new eu.wohlben.qits.cirunner.protocol.Upgrade(
                "2026.1003.50846",
                "registry.qits.wohlben.eu/qits/qits-ci-runner:2026.1003.50846",
                SHA)),
        Arguments.of(
            "Upgrade, without sha256",
            new eu.wohlben.qits.cirunner.protocol.Upgrade(
                "2026.1003.50846",
                "registry.qits.wohlben.eu/qits/qits-ci-runner@sha256:" + SHA,
                null)),
        Arguments.of(
            "Retire, SUPERSEDED",
            new eu.wohlben.qits.cirunner.protocol.Retire("superseded by 2026.1003.50846")),
        Arguments.of(
            "Retire, DELETED",
            eu.wohlben.qits.cirunner.protocol.Retire.deleted("deleted by admin")),
        Arguments.of(
            "Quarantined",
            new eu.wohlben.qits.cirunner.protocol.Quarantined(
                "3 runner-caused failures in a row", "2026-10-03T12:00:00Z")),
        Arguments.of("Reinstated", new eu.wohlben.qits.cirunner.protocol.Reinstated("admin")),
        Arguments.of("Backlog", new eu.wohlben.qits.cirunner.protocol.Backlog(7)),
        Arguments.of("Reserve", new eu.wohlben.qits.cirunner.protocol.Reserve()),
        Arguments.of("Nothing", new eu.wohlben.qits.cirunner.protocol.Nothing()),
        Arguments.of("Heartbeat", new eu.wohlben.qits.cirunner.protocol.Heartbeat()));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("frames")
  void theBaseWritesWhatCiWritesAndEachReadsTheOther(String name, CiRunnerMessage ci) {
    LifecycleMessage base = CiShapedDomain.toBase(ci);

    Map<String, Object> ciWire = CiRunnerCodec.encode(ci);
    Map<String, Object> baseWire = CODEC.encode(base);
    assertEquals(entries(ciWire), entries(baseWire), "the ordered wire map differs from CI's");

    assertEquals(base, CODEC.decode(ciWire), "the base read CI's frame differently");
    assertEquals(ci, CiRunnerCodec.decode(baseWire), "CI read the base's frame differently");
  }

  /** A map as its ordered entry list, recursively, so key order is part of the comparison. */
  private static Object entries(Object value) {
    if (value instanceof Map<?, ?> map) {
      List<Object> list = new ArrayList<>();
      for (Map.Entry<?, ?> e : map.entrySet()) {
        list.add(Map.entry(e.getKey(), new Holder(entries(e.getValue()))));
      }
      return list;
    }
    if (value instanceof List<?> items) {
      List<Object> list = new ArrayList<>();
      for (Object item : items) {
        list.add(entries(item));
      }
      return list;
    }
    return value;
  }

  /** {@code Map.entry} refuses a null value, and a null value is a key CI writes. */
  private record Holder(Object value) {}
}
