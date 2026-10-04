package eu.wohlben.qits.runner.toolkit;

import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.runner.protocol.DomainCodec;
import eu.wohlben.qits.runner.protocol.RunnerCapabilities;
import eu.wohlben.qits.runner.protocol.RunnerCodec;
import eu.wohlben.qits.runner.protocol.RunnerMessage;
import eu.wohlben.qits.runner.protocol.RunnerVocabulary;
import eu.wohlben.qits.runner.protocol.WireMaps;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The suite's runner: a test-only identity and a two-frame domain — host → runner {@code take}
 * (the item now holds a slot) and {@code release} (it no longer does). Every hook the runtime calls
 * is recorded in {@link Domain#events}, in call order, so a test can assert the order the dossier
 * gives.
 */
final class Toy {

  private Toy() {}

  static final String VERSION = "2026.1.1";

  static final RunnerIdentity IDENTITY =
      new RunnerIdentity(
          "toy",
          "QITS_TOY_RUNNER_",
          "qits.toy.runner",
          "qits-toy-runner",
          "qits/qits-toy-runner",
          "/var/lib/qits-toy-runner",
          "/toy/api/runners/{id}/register",
          "qits-toy-runner",
          VERSION,
          1,
          new RunnerVocabulary("heldItems", "adoptedItems"),
          "item");

  static final RunnerCapabilities CAPS =
      new RunnerCapabilities(true, "amd64", "linux", Map.of("site", "home"));

  /** The toy's own frames. */
  sealed interface Message extends RunnerMessage permits Take, Release {}

  /** Host → runner: the item now holds a slot. */
  record Take(String itemId) implements Message {}

  /** Host → runner: the item no longer does. */
  record Release(String itemId) implements Message {}

  static final DomainCodec<Message> CODEC =
      new DomainCodec<>() {
        @Override
        public Class<Message> messageType() {
          return Message.class;
        }

        @Override
        public Set<String> types() {
          return Set.of("take", "release");
        }

        @Override
        public Map<String, Object> encode(Message message) {
          Map<String, Object> map = new LinkedHashMap<>();
          switch (message) {
            case Take t -> {
              map.put("type", "take");
              map.put("itemId", t.itemId());
            }
            case Release r -> {
              map.put("type", "release");
              map.put("itemId", r.itemId());
            }
          }
          return map;
        }

        @Override
        public Message decode(String type, Map<String, Object> map) {
          return switch (type) {
            case "take" -> new Take(WireMaps.str(map, "itemId"));
            case "release" -> new Release(WireMaps.str(map, "itemId"));
            default -> throw new IllegalArgumentException(type);
          };
        }
      };

  static RunnerCodec<Message> codec() {
    return new RunnerCodec<>(IDENTITY.vocabulary(), CODEC);
  }

  /** The two-frame domain, recording every hook it is called with. */
  static class Domain implements RunnerDomain<Message> {

    final List<String> events = Collections.synchronizedList(new ArrayList<>());
    final List<Message> messages = Collections.synchronizedList(new ArrayList<>());
    final List<List<String>> dropped = Collections.synchronizedList(new ArrayList<>());
    volatile boolean quiescent = true;

    @Override
    public DomainCodec<Message> codec() {
      return CODEC;
    }

    @Override
    public RunnerCapabilities capabilities(HostFacts host) {
      return CAPS;
    }

    @Override
    public Map<String, Object> helloExtensions() {
      return Map.of("toyFeature", "on");
    }

    @Override
    public void onSessionStart(RunnerSession session, List<String> carried) {
      events.add("sessionStart" + carried);
    }

    @Override
    public void onAck(Ack ack, RunnerSession session) {
      events.add("ack" + ack.slots());
    }

    @Override
    public void onDropped(List<String> workIds, RunnerSession session) {
      events.add("dropped" + workIds);
      dropped.add(workIds);
    }

    @Override
    public void onMessage(Message message, RunnerSession session) {
      messages.add(message);
      switch (message) {
        case Take t -> session.taken(t.itemId());
        case Release r -> session.released(r.itemId());
      }
    }

    @Override
    public boolean quiescent() {
      return quiescent;
    }

    @Override
    public void onDecommission(RunnerSession session) {
      events.add("decommission");
    }

    @Override
    public void onSessionLost() {
      events.add("sessionLost");
    }
  }
}
