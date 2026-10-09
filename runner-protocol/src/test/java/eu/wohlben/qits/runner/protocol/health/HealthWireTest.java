package eu.wohlben.qits.runner.protocol.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.protocol.DomainCodec;
import eu.wohlben.qits.runner.protocol.RunnerCodec;
import eu.wohlben.qits.runner.protocol.RunnerDecodeException;
import eu.wohlben.qits.runner.protocol.RunnerMessage;
import eu.wohlben.qits.runner.protocol.RunnerVocabulary;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The health frames' wire, lifted from qits-workspaces-runner-daemon's {@code
 * WorkspacesRunnerCodecTest}: the same spellings, key order included, so the workspaces runner's
 * codec can delegate here without one byte moving; the original (id-less, check-less) frames read
 * and write exactly as before; and a domain codec whose arms delegate here round-trips through
 * {@link RunnerCodec}.
 */
class HealthWireTest {

  private static HealthReport fullReport() {
    Map<String, Object> container = new LinkedHashMap<>();
    container.put("name", "qits-workspaces-runner-ws-2201-1a2b3c4d");
    container.put("rowId", 2201L);
    container.put("finishedAt", null);
    Map<String, Object> inventory = new LinkedHashMap<>();
    inventory.put("containers", List.of(container));
    inventory.put("runnerContainer", null);
    return new HealthReport(
        false,
        "session: timed out after 1s",
        "req-1",
        List.of(
            new CheckResult("docker", true, "docker 27.3.1", Map.of("serverVersion", "27.3.1")),
            new CheckResult("nodeInventory", true, "1 container, 0 volumes", inventory),
            new CheckResult("session", false, "timed out after 1s", Map.of())));
  }

  // --- spellings ---------------------------------------------------------------------------------

  @Test
  void theHealthFramesAreSpelledExactlyAsTheWorkspacesCodecSpellsThem() {
    assertEquals("{\"type\":\"healthCheck\"}", Json.write(HealthWire.encodeCheck(null)));
    assertEquals(
        "{\"type\":\"healthCheck\",\"requestId\":\"req-1\"}",
        Json.write(HealthWire.encodeCheck("req-1")));
    assertEquals(
        "{\"type\":\"healthChecked\",\"ok\":true,\"detail\":\"fine\"}",
        Json.write(HealthWire.encodeChecked(new HealthReport(true, "fine"))));
    assertEquals(
        "{\"type\":\"healthChecked\",\"ok\":false,\"detail\":\"session: timed out after 1s\","
            + "\"requestId\":\"req-1\",\"checks\":["
            + "{\"name\":\"docker\",\"ok\":true,\"detail\":\"docker 27.3.1\","
            + "\"data\":{\"serverVersion\":\"27.3.1\"}},"
            + "{\"name\":\"nodeInventory\",\"ok\":true,\"detail\":\"1 container, 0 volumes\","
            + "\"data\":{\"containers\":[{\"name\":\"qits-workspaces-runner-ws-2201-1a2b3c4d\","
            + "\"rowId\":2201,\"finishedAt\":null}],\"runnerContainer\":null}},"
            + "{\"name\":\"session\",\"ok\":false,\"detail\":\"timed out after 1s\",\"data\":{}}]}",
        Json.write(HealthWire.encodeChecked(fullReport())));
  }

  @Test
  void anAbsentRequestIdAndAbsentChecksAreLeftOffTheWireEachOnItsOwn() {
    assertEquals(
        List.of("type", "ok", "detail", "requestId"),
        new ArrayList<>(
            HealthWire.encodeChecked(new HealthReport(true, "fine", "req-2", List.of())).keySet()));
    assertEquals(
        List.of("type", "ok", "detail", "checks"),
        new ArrayList<>(
            HealthWire.encodeChecked(
                    new HealthReport(
                        true, "fine", null, List.of(new CheckResult("docker", true, "x", null))))
                .keySet()));
    assertEquals(
        List.of("type", "ok", "detail"),
        new ArrayList<>(HealthWire.encodeChecked(new HealthReport(true, "fine", null, null)).keySet()));
  }

  @Test
  void aChecksDataKeepsItsInsertionOrderAndItsNullValues() {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("zeta", 1);
    data.put("alpha", null);
    data.put("mid", List.of("a"));
    CheckResult result = new CheckResult("c", true, "d", data);
    data.put("late", "not seen"); // the record holds a copy
    assertEquals(List.of("zeta", "alpha", "mid"), new ArrayList<>(result.data().keySet()));
    assertThrows(UnsupportedOperationException.class, () -> result.data().put("x", 1));
    assertEquals(
        "{\"type\":\"healthChecked\",\"ok\":true,\"detail\":\"d\",\"checks\":[{\"name\":\"c\","
            + "\"ok\":true,\"detail\":\"d\",\"data\":{\"zeta\":1,\"alpha\":null,\"mid\":[\"a\"]}}]}",
        Json.write(HealthWire.encodeChecked(new HealthReport(true, "d", null, List.of(result)))));
    assertEquals(Map.of(), new CheckResult("c", true, "d", null).data());
  }

  // --- reading -----------------------------------------------------------------------------------

  @Test
  void everyShapeRoundTrips() {
    for (HealthReport report :
        List.of(
            new HealthReport(true, "fine"),
            new HealthReport(false, "it never ran"),
            new HealthReport(true, "all 0 checks passed", "req-3", List.of()),
            fullReport())) {
      assertEquals(report, HealthWire.decodeChecked(HealthWire.encodeChecked(report)));
    }
    assertNull(HealthWire.decodeCheck(HealthWire.encodeCheck(null)));
    assertEquals("req-1", HealthWire.decodeCheck(HealthWire.encodeCheck("req-1")));
  }

  @Test
  void theOriginalHealthFramesStillRead() {
    assertNull(HealthWire.decodeCheck(Map.of("type", "healthCheck")));
    assertEquals(
        new HealthReport(true, "fine", null, List.of()),
        HealthWire.decodeChecked(Map.of("type", "healthChecked", "ok", true, "detail", "fine")));
    // A check with no data reads as one with an empty object.
    assertEquals(
        new HealthReport(
            false, null, "req-4", List.of(new CheckResult("docker", false, null, Map.of()))),
        HealthWire.decodeChecked(
            Map.of(
                "type",
                "healthChecked",
                "requestId",
                "req-4",
                "checks",
                List.of(Map.of("name", "docker")))));
  }

  @Test
  void aDecodedChecksDataKeepsItsNullValuesAndUnknownFieldsAreIgnored() {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("serverVersion", null);
    data.put("other", 3);
    Map<String, Object> check = new HashMap<>();
    check.put("name", "docker");
    check.put("ok", false);
    check.put("data", data);
    check.put("severity", "high");
    Map<String, Object> wire = new HashMap<>();
    wire.put("type", "healthChecked");
    wire.put("ok", false);
    wire.put("detail", "docker: down");
    wire.put("checks", List.of(check));
    wire.put("extra", Map.of("x", 1));
    HealthReport report = HealthWire.decodeChecked(wire);
    assertEquals(
        List.of("serverVersion", "other"),
        new ArrayList<>(report.checks().getFirst().data().keySet()));
    assertTrue(report.checks().getFirst().data().containsKey("serverVersion"));
    assertNull(report.checks().getFirst().data().get("serverVersion"));
  }

  @Test
  void checksThatAreNotAListOfObjectsAreMalformed() {
    assertThrows(
        IllegalArgumentException.class,
        () -> HealthWire.decodeChecked(Map.of("type", "healthChecked", "checks", "docker")));
    assertThrows(
        IllegalArgumentException.class,
        () -> HealthWire.decodeChecked(Map.of("type", "healthChecked", "checks", List.of(1))));
  }

  // --- an embedder's codec, delegating -----------------------------------------------------------

  sealed interface ToyMessage extends RunnerMessage permits ToyCheck, ToyChecked {}

  record ToyCheck(String requestId) implements ToyMessage {}

  record ToyChecked(HealthReport report) implements ToyMessage {}

  /** The shape an embedder's arms take: one line each way. */
  static final class ToyCodec implements DomainCodec<ToyMessage> {
    @Override
    public Class<ToyMessage> messageType() {
      return ToyMessage.class;
    }

    @Override
    public Set<String> types() {
      return Set.of(HealthWire.Type.HEALTH_CHECK, HealthWire.Type.HEALTH_CHECKED);
    }

    @Override
    public Map<String, Object> encode(ToyMessage message) {
      return switch (message) {
        case ToyCheck m -> HealthWire.encodeCheck(m.requestId());
        case ToyChecked m -> HealthWire.encodeChecked(m.report());
      };
    }

    @Override
    public ToyMessage decode(String type, Map<String, Object> map) {
      return switch (type) {
        case HealthWire.Type.HEALTH_CHECK -> new ToyCheck(HealthWire.decodeCheck(map));
        case HealthWire.Type.HEALTH_CHECKED -> new ToyChecked(HealthWire.decodeChecked(map));
        default -> throw new IllegalArgumentException(type);
      };
    }
  }

  @Test
  void aDomainCodecThatDelegatesRoundTripsThroughTheRunnerCodec() {
    RunnerCodec<ToyMessage> codec =
        new RunnerCodec<>(new RunnerVocabulary("heldItems", "adoptedItems"), new ToyCodec());
    for (ToyMessage message :
        List.<ToyMessage>of(
            new ToyCheck(null),
            new ToyCheck("req-5"),
            new ToyChecked(new HealthReport(true, "fine")),
            new ToyChecked(fullReport()))) {
      assertEquals(message, codec.decode(codec.encode(message)));
    }
    RunnerDecodeException malformed =
        assertThrows(
            RunnerDecodeException.class,
            () -> codec.decode(Map.of("type", "healthChecked", "checks", "nope")));
    assertEquals(RunnerDecodeException.Reason.MALFORMED, malformed.reason());
  }

  /**
   * The JSON a map becomes, in its own iteration order — what Jackson and Vert.x both write for
   * these shapes. Test-only and deliberately minimal: strings, numbers, booleans, null, maps, lists.
   */
  static final class Json {

    static String write(Object value) {
      StringBuilder out = new StringBuilder();
      write(value, out);
      return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
      switch (value) {
        case null -> out.append("null");
        case String s -> {
          out.append('"');
          for (char c : s.toCharArray()) {
            switch (c) {
              case '"' -> out.append("\\\"");
              case '\\' -> out.append("\\\\");
              case '\n' -> out.append("\\n");
              default -> out.append(c);
            }
          }
          out.append('"');
        }
        case Number n -> out.append(n);
        case Boolean b -> out.append(b);
        case Map<?, ?> m -> {
          out.append('{');
          boolean first = true;
          for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!first) {
              out.append(',');
            }
            first = false;
            write(String.valueOf(e.getKey()), out);
            out.append(':');
            write(e.getValue(), out);
          }
          out.append('}');
        }
        case List<?> l -> {
          out.append('[');
          for (int i = 0; i < l.size(); i++) {
            if (i > 0) {
              out.append(',');
            }
            write(l.get(i), out);
          }
          out.append(']');
        }
        default -> throw new IllegalArgumentException("not a wire value: " + value.getClass());
      }
    }
  }
}
