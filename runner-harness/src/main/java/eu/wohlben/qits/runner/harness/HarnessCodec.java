package eu.wohlben.qits.runner.harness;

import eu.wohlben.qits.runner.protocol.DomainCodec;
import eu.wohlben.qits.runner.protocol.WireMaps;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The harness's {@link DomainCodec}: its four frames over {@code Map<String,Object>}, read through
 * {@link WireMaps} so a frame tolerates what a lifecycle frame does. A missing field is a refusal
 * here — {@code RunnerCodec} reports it as a malformed frame — never a record with a null in it.
 */
public final class HarnessCodec implements DomainCodec<HarnessMessage> {

  /** The wire {@code type}s. */
  public static final String TAKE = "take";

  public static final String STARTED = "started";
  public static final String RELEASE = "release";
  public static final String RELEASED = "released";

  /** The wire field names. */
  public static final String ITEM_ID = "itemId";

  public static final String IMAGE = "image";
  public static final String CONTAINER_ID = "containerId";

  @Override
  public Class<HarnessMessage> messageType() {
    return HarnessMessage.class;
  }

  @Override
  public Set<String> types() {
    return Set.of(TAKE, STARTED, RELEASE, RELEASED);
  }

  @Override
  public Map<String, Object> encode(HarnessMessage message) {
    Map<String, Object> map = new LinkedHashMap<>();
    switch (message) {
      case HarnessMessage.Take take -> {
        map.put("type", TAKE);
        map.put(ITEM_ID, take.itemId());
        map.put(IMAGE, take.image());
      }
      case HarnessMessage.Started started -> {
        map.put("type", STARTED);
        map.put(ITEM_ID, started.itemId());
        map.put(CONTAINER_ID, started.containerId());
      }
      case HarnessMessage.Release release -> {
        map.put("type", RELEASE);
        map.put(ITEM_ID, release.itemId());
      }
      case HarnessMessage.Released released -> {
        map.put("type", RELEASED);
        map.put(ITEM_ID, released.itemId());
      }
    }
    return map;
  }

  @Override
  public HarnessMessage decode(String type, Map<String, Object> map) {
    return switch (type) {
      case TAKE -> new HarnessMessage.Take(required(map, ITEM_ID), required(map, IMAGE));
      case STARTED ->
          new HarnessMessage.Started(required(map, ITEM_ID), required(map, CONTAINER_ID));
      case RELEASE -> new HarnessMessage.Release(required(map, ITEM_ID));
      case RELEASED -> new HarnessMessage.Released(required(map, ITEM_ID));
      default -> throw new IllegalArgumentException("not a harness frame: " + type);
    };
  }

  private static String required(Map<String, Object> map, String key) {
    String value = WireMaps.str(map, key);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(key + " is missing");
    }
    return value;
  }
}
