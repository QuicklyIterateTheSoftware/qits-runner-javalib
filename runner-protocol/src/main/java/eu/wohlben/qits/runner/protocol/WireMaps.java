package eu.wohlben.qits.runner.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The readers every frame is decoded through — the lifecycle frames by {@link RunnerCodec}, a
 * runner's own frames by its {@link DomainCodec} — so both inherit the same tolerance.
 *
 * <p>Numbers are read through {@link Number}, so it does not matter whether the JSON layer decoded
 * an {@code int} as {@code Integer} (Jackson) or {@code Long} (Vert.x). Nested objects are read as
 * a {@code Map} or as any {@code Iterable} of {@code Map.Entry} — which is what a Vert.x {@code
 * JsonObject} nested inside another one is — and lists as any {@code Iterable}, which covers a
 * {@code JsonArray}; so a caller hands over {@code jsonObject.getMap()} and nothing deeper needs
 * unwrapping. A value of the wrong shape is an {@link IllegalArgumentException}, never a {@code
 * ClassCastException}, and the codec turns it into {@link RunnerDecodeException.Reason#MALFORMED}.
 */
public final class WireMaps {

  private WireMaps() {}

  public static String str(Map<String, Object> map, String key) {
    Object value = map.get(key);
    return value == null ? null : value.toString();
  }

  public static int intVal(Map<String, Object> map, String key) {
    Object value = map.get(key);
    return value instanceof Number number ? number.intValue() : 0;
  }

  public static Number number(Map<String, Object> map, String key) {
    Object value = map.get(key);
    if (value == null) {
      return null;
    }
    if (value instanceof Number number) {
      return number;
    }
    throw new IllegalArgumentException("'" + key + "' is not a number");
  }

  public static boolean boolVal(Map<String, Object> map, String key) {
    Object value = map.get(key);
    return value instanceof Boolean bool && bool;
  }

  /**
   * A nested object, as a {@code Map} or as an {@code Iterable} of entries (a Vert.x {@code
   * JsonObject} nested in another one). Anything else under the key is a malformed frame.
   */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> object(Map<String, Object> map, String key) {
    Object value = map.get(key);
    if (value == null) {
      return null;
    }
    if (value instanceof Map<?, ?> nested) {
      return (Map<String, Object>) nested;
    }
    if (value instanceof Iterable<?> entries) {
      Map<String, Object> copy = new LinkedHashMap<>();
      for (Object entry : entries) {
        if (!(entry instanceof Map.Entry<?, ?> e)) {
          throw new IllegalArgumentException("'" + key + "' is not an object");
        }
        copy.put(String.valueOf(e.getKey()), e.getValue());
      }
      return copy;
    }
    throw new IllegalArgumentException("'" + key + "' is not an object");
  }

  public static List<String> stringList(Map<String, Object> map, String key) {
    Object value = map.get(key);
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof Iterable<?> items) || value instanceof Map<?, ?>) {
      throw new IllegalArgumentException("'" + key + "' is not a list");
    }
    List<String> list = new ArrayList<>();
    for (Object item : items) {
      list.add(item == null ? "" : item.toString());
    }
    return list;
  }

  public static Map<String, String> stringMap(Map<String, Object> map, String key) {
    Map<String, Object> nested = object(map, key);
    if (nested == null) {
      return Map.of();
    }
    Map<String, String> strings = new LinkedHashMap<>();
    nested.forEach((k, v) -> strings.put(k, v == null ? "" : v.toString()));
    return strings;
  }

  /**
   * Like {@link #stringMap}, but absent stays {@code null} rather than collapsing to empty —
   * "nobody has sent one yet" is not the same thing as "sent, and empty" (CI's {@code
   * registryMirrors}).
   */
  public static Map<String, String> nullableStringMap(Map<String, Object> map, String key) {
    Map<String, Object> nested = object(map, key);
    if (nested == null) {
      return null;
    }
    Map<String, String> strings = new LinkedHashMap<>();
    nested.forEach((k, v) -> strings.put(k, v == null ? "" : v.toString()));
    return strings;
  }
}
