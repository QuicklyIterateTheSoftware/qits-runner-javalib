package eu.wohlben.qits.runner.protocol.health;

import eu.wohlben.qits.runner.protocol.DomainCodec;
import eu.wohlben.qits.runner.protocol.WireMaps;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The health frames' wire shape, written once for every runner kind:
 *
 * <pre>
 *   healthCheck{requestId?} → healthChecked{ok, detail, requestId?, checks?[{name, ok, detail, data}]}
 * </pre>
 *
 * <p><b>The frames stay the embedder's.</b> {@code healthCheck}/{@code healthChecked} are domain
 * frames, not lifecycle ones: qits-projects-desk-runner-daemon already claims both type strings
 * with another payload, and a record shipped here cannot join an embedder's sealed {@code permits}
 * list. So an embedder keeps its own two records and its {@link DomainCodec}'s arms delegate here.
 * The maps are shaped as a {@link DomainCodec#encode} body is — {@code type} first, then the
 * fields — so an arm is one line: {@code case HealthChecked m -> HealthWire.encodeChecked(m.report())}.
 *
 * <p><b>Byte-identical to qits-workspaces-runner-daemon's codec</b>, which these were lifted from:
 * {@code requestId} and {@code checks} were added after the frames were released, so they are left
 * out when absent (null, empty) and read as absent from a peer that never sends them — an original
 * {@code healthChecked{ok, detail}} is still exactly that, both ways.
 */
public final class HealthWire {

  /** The frames' {@code type} values. */
  public static final class Type {
    public static final String HEALTH_CHECK = "healthCheck";
    public static final String HEALTH_CHECKED = "healthChecked";

    private Type() {}
  }

  /** The field names of the frames and of each named check. */
  public static final class Field {
    public static final String TYPE = "type";
    public static final String REQUEST_ID = "requestId";
    public static final String CHECKS = "checks";
    public static final String DATA = "data";
    public static final String NAME = "name";
    public static final String OK = "ok";
    public static final String DETAIL = "detail";

    private Field() {}
  }

  private HealthWire() {}

  /** A {@code healthCheck}'s wire map; {@code requestId} left out when null. */
  public static Map<String, Object> encodeCheck(String requestId) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put(Field.TYPE, Type.HEALTH_CHECK);
    if (requestId != null) {
      map.put(Field.REQUEST_ID, requestId);
    }
    return map;
  }

  /** A {@code healthChecked}'s wire map; {@code requestId} and {@code checks} left out when absent. */
  public static Map<String, Object> encodeChecked(HealthReport report) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put(Field.TYPE, Type.HEALTH_CHECKED);
    map.put(Field.OK, report.ok());
    map.put(Field.DETAIL, report.detail());
    if (report.requestId() != null) {
      map.put(Field.REQUEST_ID, report.requestId());
    }
    if (!report.checks().isEmpty()) {
      List<Object> checks = new ArrayList<>();
      for (CheckResult c : report.checks()) {
        Map<String, Object> check = new LinkedHashMap<>();
        check.put(Field.NAME, c.name());
        check.put(Field.OK, c.ok());
        check.put(Field.DETAIL, c.detail());
        check.put(Field.DATA, new LinkedHashMap<>(c.data()));
        checks.add(check);
      }
      map.put(Field.CHECKS, checks);
    }
    return map;
  }

  /** A {@code healthCheck}'s request id; null when the asker sent none. */
  public static String decodeCheck(Map<String, Object> map) {
    return WireMaps.str(map, Field.REQUEST_ID);
  }

  /**
   * A {@code healthChecked}. Its named checks are empty when absent (a runner older than the
   * field); a check's {@code data} is taken as the peer's JSON decoder gave it — the object is the
   * check's own, and nothing here interprets it. A {@code RuntimeException} here is what {@link
   * DomainCodec#decode} reports as a malformed frame.
   */
  public static HealthReport decodeChecked(Map<String, Object> map) {
    return new HealthReport(
        WireMaps.boolVal(map, Field.OK),
        WireMaps.str(map, Field.DETAIL),
        WireMaps.str(map, Field.REQUEST_ID),
        checks(map));
  }

  private static List<CheckResult> checks(Map<String, Object> map) {
    List<CheckResult> checks = new ArrayList<>();
    for (Map<String, Object> c : objects(map, Field.CHECKS)) {
      checks.add(
          new CheckResult(
              WireMaps.str(c, Field.NAME),
              WireMaps.boolVal(c, Field.OK),
              WireMaps.str(c, Field.DETAIL),
              WireMaps.object(c, Field.DATA)));
    }
    return checks;
  }

  /** An optional list of nested objects, each a {@code Map} or a Vert.x {@code JsonObject}. */
  private static List<Map<String, Object>> objects(Map<String, Object> map, String key) {
    Object value = map.get(key);
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof Iterable<?> items) || value instanceof Map<?, ?>) {
      throw new IllegalArgumentException("'" + key + "' is not a list");
    }
    List<Map<String, Object>> objects = new ArrayList<>();
    for (Object item : items) {
      // WireMaps reads a nested object out of a map by key; wrapping the item reuses that reading,
      // so a JsonObject inside a JsonArray is accepted exactly as a nested one is.
      Map<String, Object> holder = new LinkedHashMap<>();
      holder.put(key, item);
      Map<String, Object> object = WireMaps.object(holder, key);
      if (object == null) {
        throw new IllegalArgumentException("'" + key + "' holds a null");
      }
      objects.add(object);
    }
    return objects;
  }
}
