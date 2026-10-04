package eu.wohlben.qits.runner.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The wire contract's framework-free guard: every lifecycle frame survives {@code encode → decode}
 * unchanged, an unknown type is a typed error, unknown fields are tolerated, and every other frame
 * is the domain's. Carried from qits-ci-runner-daemon's {@code CiRunnerCodecTest} at
 * 2026.1003.50846 and narrowed to the lifecycle frames; byte identity with CI is {@link
 * CiWireCompatibilityTest}'s.
 */
class RunnerCodecTest {

  private static final int CAPABILITY_VERSION = 1;

  private static final RunnerVocabulary VOCABULARY =
      new RunnerVocabulary("heldItems", "adoptedItems");

  /** A runner with no frames of its own: the lifecycle frames alone. */
  interface NoFrames extends RunnerMessage {}

  static final class NoDomain implements DomainCodec<NoFrames> {
    @Override
    public Class<NoFrames> messageType() {
      return NoFrames.class;
    }

    @Override
    public Set<String> types() {
      return Set.of();
    }

    @Override
    public Map<String, Object> encode(NoFrames message) {
      throw new AssertionError("no domain frames");
    }

    @Override
    public NoFrames decode(String type, Map<String, Object> map) {
      throw new AssertionError("no domain frames");
    }
  }

  /** A harness-shaped domain: two frames, read through WireMaps like any domain codec. */
  sealed interface ItemMessage extends RunnerMessage {}

  record Take(String itemId, String image) implements ItemMessage {}

  record Released(String itemId) implements ItemMessage {}

  static class ItemDomain implements DomainCodec<ItemMessage> {
    @Override
    public Class<ItemMessage> messageType() {
      return ItemMessage.class;
    }

    @Override
    public Set<String> types() {
      return Set.of("take", "released");
    }

    @Override
    public Map<String, Object> encode(ItemMessage message) {
      Map<String, Object> map = new LinkedHashMap<>();
      switch (message) {
        case Take m -> {
          map.put("type", "take");
          map.put("itemId", m.itemId());
          map.put("image", m.image());
        }
        case Released m -> {
          map.put("type", "released");
          map.put("itemId", m.itemId());
        }
      }
      return map;
    }

    @Override
    public ItemMessage decode(String type, Map<String, Object> map) {
      return switch (type) {
        case "take" -> new Take(WireMaps.str(map, "itemId"), WireMaps.str(map, "image"));
        case "released" -> new Released(WireMaps.str(map, "itemId"));
        default -> throw new AssertionError("only ever called with a type in types(): " + type);
      };
    }
  }

  private static final RunnerCodec<NoFrames> CODEC = new RunnerCodec<>(VOCABULARY, new NoDomain());

  private static RunnerMessage roundTrip(RunnerMessage message) {
    return CODEC.decode(CODEC.encode(message));
  }

  private static Map<String, Object> ordered(Object... keysAndValues) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      map.put((String) keysAndValues[i], keysAndValues[i + 1]);
    }
    return map;
  }

  private static final List<LifecycleMessage> ALL =
      List.of(
          new Hello(
              "2026.927.1",
              CAPABILITY_VERSION,
              2,
              new RunnerCapabilities(true, "amd64", "linux", Map.of("site", "home"))),
          new Hello(
              "2026.927.1",
              CAPABILITY_VERSION,
              2,
              new RunnerCapabilities(true, "amd64", "linux", Map.of()),
              List.of("item-1", "item-2"),
              Map.of()),
          new Hello(
              "2026.929.1",
              CAPABILITY_VERSION,
              2,
              new RunnerCapabilities(
                  true, "amd64", "linux", Map.of(), ordered("idRange", 4294967295L)),
              List.of(),
              ordered("flavour", "harness", "nothing", null)),
          new Hello("2026.929.1", CAPABILITY_VERSION, 2, null),
          new Reserve(),
          new Heartbeat(),
          new Ack(CAPABILITY_VERSION, 3),
          new Ack(
              CAPABILITY_VERSION,
              3,
              null,
              ordered(
                  "registryMirrors",
                  ordered(
                      "mirror.dev.localhost:8080", "mirror.qits.wohlben.eu",
                      "registry.dev.localhost:8080", "registry.qits.wohlben.eu"))),
          new Ack(CAPABILITY_VERSION, 3, null, ordered("registryMirrors", null)),
          new Ack(CAPABILITY_VERSION, 3, List.of("item-1"), Map.of()),
          new Ack(CAPABILITY_VERSION, 3, List.of(), Map.of()),
          new Backlog(7),
          new Nothing(),
          new Upgrade(
              "2026.928.120000",
              "registry.qits.example.eu/qits/qits-ci-runner:2026.928.120000",
              "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"),
          new Upgrade(
              "2026.928.120000",
              "registry.qits.example.eu/qits/qits-ci-runner@sha256:"
                  + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
              null),
          new Retire("superseded by 2026.928.120000"),
          Retire.deleted("deleted by admin"),
          new Quarantined("3 runner-caused failures in a row", "2026-09-28T12:00:00Z"),
          new Reinstated("admin"),
          new Reinstated("healthcheck"));

  @Test
  void everyMessageRoundTrips() {
    for (LifecycleMessage message : ALL) {
      assertEquals(message, roundTrip(message), () -> "did not round-trip: " + message);
    }
  }

  @Test
  void everyPermittedTypeIsCoveredByTheRoundTrip() {
    // A new record added to the sealed set without a case above would still compile the codec's
    // switch only if it had an arm; this pins that the test list grows with it.
    assertEquals(10, LifecycleMessage.class.getPermittedSubclasses().length);
    assertEquals(
        Set.of(LifecycleMessage.class.getPermittedSubclasses()),
        ALL.stream().map(Object::getClass).collect(Collectors.toSet()));
  }

  @Test
  void theDiscriminatorIsTheProtocolConstant() {
    assertEquals(
        RunnerWire.Type.QUARANTINED,
        CODEC.encode(new Quarantined("r", "s")).get(RunnerWire.Field.TYPE));
    assertEquals(RunnerWire.Type.BACKLOG, CODEC.encode(new Backlog(1)).get(RunnerWire.Field.TYPE));
  }

  /**
   * The self-update frames are how a runner of ANY older version learns it must update, so their
   * wire shape is pinned here as literals — not through the constants a rename would move with it.
   */
  @Test
  void theSelfUpdateFramesKeepTheirFrozenWireShape() {
    Map<String, Object> upgrade = new LinkedHashMap<>();
    upgrade.put("type", "upgrade");
    upgrade.put("version", "2");
    upgrade.put("image", "r/qits/qits-ci-runner:2");
    upgrade.put("sha256", "ab");
    assertEquals(upgrade, CODEC.encode(new Upgrade("2", "r/qits/qits-ci-runner:2", "ab")));
    assertEquals(new Upgrade("2", "r/qits/qits-ci-runner:2", "ab"), CODEC.decode(upgrade));

    assertEquals(
        Map.of("type", "retire", "reason", "superseded"), CODEC.encode(new Retire("superseded")));
    assertEquals(
        new Retire("superseded"), CODEC.decode(Map.of("type", "retire", "reason", "superseded")));

    assertEquals(
        Retire.Kind.SUPERSEDED,
        ((Retire) CODEC.decode(Map.of("type", "retire", "reason", "superseded"))).kind(),
        "a Retire without a kind — every host before kinds — is a self-update's");

    assertEquals(
        "1.0",
        CODEC.encode(new Hello("1.0", 1, 1, null)).get("runnerVersion"),
        "Hello.runnerVersion is what the host compares with its pin");
  }

  /**
   * A deleted runner's {@code Retire} adds {@code kind}, and only that: {@code reason} stays where
   * an older runner reads it, and a kind this binary does not know is read as the safe one — a
   * self-update's, which never removes a state volume.
   */
  @Test
  void aDeletedRetireAddsItsKindAndAnUnknownKindReadsAsSuperseded() {
    Map<String, Object> deleted = new LinkedHashMap<>();
    deleted.put("type", "retire");
    deleted.put("reason", "deleted");
    deleted.put("kind", "DELETED");
    assertEquals(deleted, CODEC.encode(Retire.deleted("deleted")));
    assertEquals(Retire.deleted("deleted"), CODEC.decode(deleted));
    assertEquals(
        new Retire("x", Retire.Kind.SUPERSEDED),
        CODEC.decode(Map.of("type", "retire", "reason", "x", "kind", "SOMETHING_NEWER")));
    assertEquals(Retire.Kind.SUPERSEDED, new Retire("x", null).kind());
  }

  /**
   * The quarantine frames are additive, unlike the self-update ones: an older runner just drops
   * them as an unknown type. Still worth pinning their wire shape by literal, the way the
   * self-update frames are, since {@code RunnerWire}'s constants are what a rename would move with
   * them — this catches a rename that forgot the wire is what a runner in the field already
   * understands.
   */
  @Test
  void theQuarantineFramesHaveTheDocumentedWireShape() {
    Map<String, Object> quarantined = new LinkedHashMap<>();
    quarantined.put("type", "quarantined");
    quarantined.put("reason", "3 runner-caused failures in a row");
    quarantined.put("since", "2026-09-28T12:00:00Z");
    assertEquals(
        quarantined,
        CODEC.encode(new Quarantined("3 runner-caused failures in a row", "2026-09-28T12:00:00Z")));
    assertEquals(
        new Quarantined("3 runner-caused failures in a row", "2026-09-28T12:00:00Z"),
        CODEC.decode(quarantined));

    assertEquals(
        Map.of("type", "reinstated", "by", "admin"), CODEC.encode(new Reinstated("admin")));
    assertEquals(
        new Reinstated("admin"), CODEC.decode(Map.of("type", "reinstated", "by", "admin")));
  }

  /**
   * The lists carried across a lost socket are added fields, named by the runner's vocabulary, and
   * each side's older peer is the case that matters: a first connection's {@code Hello} carries no
   * key at all (the frame it always was), and an {@code Ack} with no adopted key — every host
   * before the field — is {@code null}, "said nothing", which is not the same as "adopted none".
   */
  @Test
  void heldAndAdoptedRunsAreAbsentUnlessSaidAndAnAbsentAdoptionIsNull() {
    assertFalse(CODEC.encode(new Hello("v", 1, 1, null)).containsKey("heldItems"));
    assertEquals(
        List.of("item-1"),
        CODEC.encode(new Hello("v", 1, 1, null, List.of("item-1"), Map.of())).get("heldItems"));
    assertFalse(CODEC.encode(new Ack(1, 2)).containsKey("adoptedItems"));
    assertEquals(
        List.of(),
        CODEC.encode(new Ack(1, 2, List.of(), Map.of())).get("adoptedItems"),
        "adopted none is said, unlike said nothing");
    assertNull(
        ((Ack) CODEC.decode(Map.of("type", "ack", "capabilityVersion", 1, "slots", 2))).adopted());
    assertEquals(
        List.of(),
        ((Ack)
                CODEC.decode(
                    Map.of(
                        "type",
                        "ack",
                        "capabilityVersion",
                        1,
                        "slots",
                        2,
                        "adoptedItems",
                        List.of())))
            .adopted());
    assertEquals(
        List.of("item-1"),
        ((Hello) CODEC.decode(Map.of("type", "hello", "heldItems", List.of("item-1")))).held());
  }

  @Test
  void anUpgradeWithoutAChecksumDecodesToANullOneAndAFutureFieldIsIgnored() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("type", "upgrade");
    map.put("version", "2");
    map.put("image", "r/qits/qits-ci-runner:2");
    map.put("signature", "a field a later host may add");
    assertEquals(new Upgrade("2", "r/qits/qits-ci-runner:2", null), CODEC.decode(map));
  }

  @Test
  void anUnknownTypeIsATypedDecodeErrorNamingTheType() {
    RunnerDecodeException e =
        assertThrows(
            RunnerDecodeException.class,
            () -> CODEC.decode(Map.of("type", "teleport", "runId", "r")));
    assertEquals(RunnerDecodeException.Reason.UNKNOWN_TYPE, e.reason());
    assertEquals("teleport", e.type());
  }

  @Test
  void aMissingTypeIsATypedDecodeError() {
    RunnerDecodeException e =
        assertThrows(RunnerDecodeException.class, () -> CODEC.decode(Map.of("runId", "r")));
    assertEquals(RunnerDecodeException.Reason.MISSING_TYPE, e.reason());
    assertNull(e.type());
    assertEquals(
        RunnerDecodeException.Reason.MISSING_TYPE,
        assertThrows(RunnerDecodeException.class, () -> CODEC.decode(null)).reason());
  }

  @Test
  void aNestedFieldOfTheWrongShapeIsMalformedRatherThanAClassCast() {
    RunnerDecodeException e =
        assertThrows(
            RunnerDecodeException.class,
            () -> CODEC.decode(Map.of("type", "hello", "capabilities", "not an object")));
    assertEquals(RunnerDecodeException.Reason.MALFORMED, e.reason());
    assertEquals("hello", e.type());
    assertEquals(
        RunnerDecodeException.Reason.MALFORMED,
        assertThrows(
                RunnerDecodeException.class,
                () -> CODEC.decode(Map.of("type", "hello", "heldItems", "not a list")))
            .reason());
  }

  /**
   * Unknown fields never fail a frame. Where a frame has extensions they are kept there, in arrival
   * order, so a domain can read a key the base does not know; everywhere else they are dropped.
   */
  @Test
  void unknownFieldsAreIgnoredAtEveryLevel() {
    Map<String, Object> hello =
        new LinkedHashMap<>(
            CODEC.encode(
                new Hello("v", 1, 2, new RunnerCapabilities(true, "amd64", "linux", Map.of()))));
    @SuppressWarnings("unchecked")
    Map<String, Object> caps = new LinkedHashMap<>((Map<String, Object>) hello.get("capabilities"));
    caps.put("gpu", "please");
    hello.put("capabilities", caps);
    hello.put("priority", 9);
    assertEquals(
        new Hello(
            "v",
            1,
            2,
            new RunnerCapabilities(true, "amd64", "linux", Map.of(), Map.of("gpu", "please")),
            List.of(),
            Map.of("priority", 9)),
        CODEC.decode(hello));

    Map<String, Object> ack = new LinkedHashMap<>(CODEC.encode(new Ack(1, 2)));
    ack.put("motd", "hello from a newer host");
    Ack decoded = (Ack) CODEC.decode(ack);
    assertEquals(1, decoded.capabilityVersion());
    assertEquals(2, decoded.slots());
    assertNull(decoded.adopted());

    Map<String, Object> backlog = new LinkedHashMap<>(CODEC.encode(new Backlog(3)));
    backlog.put("eta", "soon");
    assertEquals(new Backlog(3), CODEC.decode(backlog));
  }

  @Test
  void numbersDecodeWhicheverBoxTheJsonLayerChose() {
    // Vert.x decodes JSON integers as Long, Jackson as Integer; both must read the same.
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("type", "ack");
    map.put("capabilityVersion", 1L);
    map.put("slots", 2L);
    assertEquals(new Ack(1, 2), CODEC.decode(map));
  }

  @Test
  void aNestedObjectMayArriveAsAnIterableOfEntriesTheWayAVertxJsonObjectIs() {
    Map<String, Object> caps = new LinkedHashMap<>();
    caps.put("docker", true);
    caps.put("arch", "amd64");
    caps.put("os", "linux");
    Iterable<Map.Entry<String, Object>> asEntries = caps.entrySet();
    Map<String, Object> hello = new LinkedHashMap<>();
    hello.put("type", "hello");
    hello.put("runnerVersion", "v");
    hello.put("capabilityVersion", 1);
    hello.put("capabilities", asEntries);
    assertEquals(
        new Hello("v", 1, 0, new RunnerCapabilities(true, "amd64", "linux", Map.of())),
        CODEC.decode(hello));
    // And an entry that is not an entry is malformed, not silently dropped.
    Map<String, Object> bad = new LinkedHashMap<>(hello);
    bad.put("capabilities", List.of(new AbstractMap.SimpleEntry<>("docker", true), "junk"));
    assertEquals(
        RunnerDecodeException.Reason.MALFORMED,
        assertThrows(RunnerDecodeException.class, () -> CODEC.decode(bad)).reason());
  }

  // --- delegation, extensions and the vocabulary -------------------------------------------------

  @Test
  void aDomainFrameIsEncodedAndDecodedByTheDomainCodec() {
    RunnerCodec<ItemMessage> codec = new RunnerCodec<>(VOCABULARY, new ItemDomain());
    Map<String, Object> take = codec.encode(new Take("item-1", "alpine:3"));
    assertEquals(ordered("type", "take", "itemId", "item-1", "image", "alpine:3"), take);
    assertEquals(new Take("item-1", "alpine:3"), codec.decode(take));
    assertEquals(new Released("item-1"), codec.decode(codec.encode(new Released("item-1"))));
    // The lifecycle frames still go through the base on a codec with a domain.
    assertEquals(new Backlog(2), codec.decode(codec.encode(new Backlog(2))));

    // A message that is neither the base's nor this domain's has no encoder.
    assertThrows(IllegalArgumentException.class, () -> codec.encode(new NoFrames() {}));
    // A type neither side declares is unknown, not handed to the domain to guess at.
    RunnerDecodeException e =
        assertThrows(RunnerDecodeException.class, () -> codec.decode(Map.of("type", "launch")));
    assertEquals(RunnerDecodeException.Reason.UNKNOWN_TYPE, e.reason());
    assertEquals("launch", e.type());
  }

  @Test
  void aDomainTypeThatIsALifecycleTypeIsRefusedAtConstruction() {
    ItemDomain claimsHello =
        new ItemDomain() {
          @Override
          public Set<String> types() {
            return Set.of("take", "hello");
          }
        };
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class, () -> new RunnerCodec<>(VOCABULARY, claimsHello));
    assertTrue(e.getMessage().contains("hello"), e::getMessage);
  }

  @Test
  void aDomainDecodeThatThrowsIsMalformed() {
    ItemDomain throwing =
        new ItemDomain() {
          @Override
          public ItemMessage decode(String type, Map<String, Object> map) {
            WireMaps.number(map, "itemId");
            return super.decode(type, map);
          }
        };
    RunnerCodec<ItemMessage> codec = new RunnerCodec<>(VOCABULARY, throwing);
    RunnerDecodeException e =
        assertThrows(
            RunnerDecodeException.class,
            () -> codec.decode(Map.of("type", "released", "itemId", "not a number")));
    assertEquals(RunnerDecodeException.Reason.MALFORMED, e.reason());
    assertEquals("released", e.type());

    // A typed decode error the domain throws itself passes through as it is.
    ItemDomain typed =
        new ItemDomain() {
          @Override
          public ItemMessage decode(String type, Map<String, Object> map) {
            throw new RunnerDecodeException(
                RunnerDecodeException.Reason.UNKNOWN_TYPE, "take", "a take this version refuses");
          }
        };
    assertEquals(
        RunnerDecodeException.Reason.UNKNOWN_TYPE,
        assertThrows(
                RunnerDecodeException.class,
                () -> new RunnerCodec<>(VOCABULARY, typed).decode(Map.of("type", "take")))
            .reason());
  }

  @Test
  void extensionsKeepTheirOrderAndTheirNullValues() {
    Map<String, Object> extensions = new LinkedHashMap<>();
    extensions.put("zulu", 1);
    extensions.put("alpha", null);
    extensions.put("mike", "m");
    Map<String, Object> capExtensions = new LinkedHashMap<>();
    capExtensions.put("zz", null);
    capExtensions.put("aa", 2L);
    Map<String, String> labels = new LinkedHashMap<>();
    labels.put("site", "home");
    labels.put("disk", "ssd");
    labels.put("arch", "x");

    Hello hello =
        new Hello(
            "v",
            1,
            2,
            new RunnerCapabilities(true, "amd64", "linux", labels, capExtensions),
            List.of("item-1"),
            extensions);
    Map<String, Object> encoded = CODEC.encode(hello);
    assertEquals(
        List.of(
            "type",
            "runnerVersion",
            "capabilityVersion",
            "slots",
            "capabilities",
            "zulu",
            "alpha",
            "mike",
            "heldItems"),
        new ArrayList<>(encoded.keySet()));
    assertTrue(encoded.containsKey("alpha"));
    assertNull(encoded.get("alpha"));
    @SuppressWarnings("unchecked")
    Map<String, Object> caps = (Map<String, Object>) encoded.get("capabilities");
    assertEquals(
        List.of("docker", "arch", "os", "labels", "zz", "aa"), new ArrayList<>(caps.keySet()));
    @SuppressWarnings("unchecked")
    Map<String, Object> encodedLabels = (Map<String, Object>) caps.get("labels");
    assertEquals(List.of("site", "disk", "arch"), new ArrayList<>(encodedLabels.keySet()));

    Hello decoded = (Hello) CODEC.decode(encoded);
    assertEquals(hello, decoded);
    assertEquals(List.of("zulu", "alpha", "mike"), new ArrayList<>(decoded.extensions().keySet()));
    assertEquals(
        List.of("zz", "aa"), new ArrayList<>(decoded.capabilities().extensions().keySet()));
    assertEquals(
        List.of("site", "disk", "arch"), new ArrayList<>(decoded.capabilities().labels().keySet()));

    Ack ack = new Ack(1, 2, List.of(), extensions);
    Map<String, Object> encodedAck = CODEC.encode(ack);
    assertEquals(
        List.of("type", "capabilityVersion", "slots", "zulu", "alpha", "mike", "adoptedItems"),
        new ArrayList<>(encodedAck.keySet()));
    assertEquals(ack, CODEC.decode(encodedAck));

    // The record's copy is unmodifiable and detached from the caller's map.
    extensions.put("late", "x");
    assertFalse(hello.extensions().containsKey("late"));
    assertThrows(UnsupportedOperationException.class, () -> hello.extensions().put("x", 1));

    // An extension never overwrites a lifecycle key, nor takes the vocabulary's list name.
    assertThrows(
        IllegalArgumentException.class,
        () -> CODEC.encode(new Ack(1, 2, null, Map.of("slots", 9))));
    assertThrows(
        IllegalArgumentException.class,
        () -> CODEC.encode(new Hello("v", 1, 2, null, List.of(), Map.of("heldItems", List.of()))));
  }

  @Test
  void aVocabularyThatCollidesWithABaseFieldIsRefused() {
    assertThrows(
        IllegalArgumentException.class, () -> new RunnerVocabulary("slots", "adoptedRuns"));
    assertThrows(IllegalArgumentException.class, () -> new RunnerVocabulary("heldRuns", "labels"));
    assertThrows(
        IllegalArgumentException.class, () -> new RunnerVocabulary("runnerVersion", "adoptedRuns"));
    // And the name itself is a plain camelCase JSON key.
    assertThrows(IllegalArgumentException.class, () -> new RunnerVocabulary(null, "adoptedRuns"));
    assertThrows(IllegalArgumentException.class, () -> new RunnerVocabulary("ab", "adoptedRuns"));
    assertThrows(
        IllegalArgumentException.class, () -> new RunnerVocabulary("HeldRuns", "adoptedRuns"));
    assertThrows(
        IllegalArgumentException.class, () -> new RunnerVocabulary("held_runs", "adoptedRuns"));
    assertThrows(
        IllegalArgumentException.class, () -> new RunnerVocabulary("held", "a" + "b".repeat(41)));
    new RunnerVocabulary("heldRuns", "adoptedRuns");
    new RunnerVocabulary("heldWorkspaces", "adoptedWorkspaces");
  }
}
