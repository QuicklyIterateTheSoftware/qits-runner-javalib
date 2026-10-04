package eu.wohlben.qits.runner.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The env contract. A missing or unparseable value is one sentence naming the VARIABLE — what the
 * operator edits in the install line — and never a value, which could be the token.
 */
class RunnerSettingsTest {

  private static final RunnerIdentity ID = Toy.IDENTITY;

  /** The toy identity's environment from bare names: {@code URL} becomes its prefixed variable. */
  private static Map<String, String> env(String... pairs) {
    Map<String, String> env = new HashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      env.put(ID.env(pairs[i]), pairs[i + 1]);
    }
    return env;
  }

  private static RunnerSettings parse(String... pairs) throws RunnerSettings.Invalid {
    return RunnerSettings.parse(ID, env(pairs)::get);
  }

  private static RunnerSettings.Invalid invalid(String... pairs) {
    return assertThrows(RunnerSettings.Invalid.class, () -> parse(pairs));
  }

  @Test
  void noUrlIsNamedByItsVariable() {
    assertEquals("QITS_TOY_RUNNER_URL is not set", invalid("ID", "r1").getMessage());
    assertEquals("QITS_TOY_RUNNER_URL is not set", invalid("URL", "  ", "ID", "r1").getMessage());
  }

  @Test
  void aUrlThatIsNotHttpIsRefusedBeforeAnythingDialsIt() {
    assertTrue(
        invalid("URL", "toy.example", "ID", "r1").getMessage().startsWith("QITS_TOY_RUNNER_URL"));
  }

  @Test
  void noIdIsNamedByItsVariable() {
    assertEquals(
        "QITS_TOY_RUNNER_ID is not set", invalid("URL", "https://toy.example").getMessage());
  }

  @Test
  void anIdOutsideTheLabelCharsetIsRefused() {
    // It becomes a path segment and a docker label value; neither may need escaping.
    assertTrue(
        invalid("URL", "https://toy.example", "ID", "r1/../x")
            .getMessage()
            .startsWith("QITS_TOY_RUNNER_ID"));
  }

  @Test
  void aNonNumericSlotCountOrTimeoutNamesItsVariable() {
    assertTrue(
        invalid("URL", "https://toy.example", "ID", "r1", "SLOTS", "two")
            .getMessage()
            .startsWith("QITS_TOY_RUNNER_SLOTS"));
    assertTrue(
        invalid("URL", "https://toy.example", "ID", "r1", "SLOTS", "0")
            .getMessage()
            .startsWith("QITS_TOY_RUNNER_SLOTS"));
    assertTrue(
        invalid("URL", "https://toy.example", "ID", "r1", "DOCKER_TIMEOUT", "soon")
            .getMessage()
            .startsWith("QITS_TOY_RUNNER_DOCKER_TIMEOUT"));
  }

  @Test
  void theDefaultsAreTheDocumentedOnes() throws Exception {
    RunnerSettings settings = parse("URL", "https://toy.example/", "ID", "r1");
    assertEquals("https://toy.example", settings.url(), "a trailing slash is dropped once, here");
    assertEquals("", settings.registrationToken());
    assertEquals(Path.of("/var/lib/qits-toy-runner"), settings.stateDir(), "the identity's");
    assertEquals(1, settings.slots());
    assertEquals("docker", settings.dockerBinary());
    assertEquals(120, settings.dockerTimeoutSeconds());
    assertEquals(180, settings.rolloverTimeoutSeconds());
    assertTrue(settings.selfUpdate());
    assertEquals(RunnerSettings.Timing.defaults(), settings.timing());
  }

  @Test
  void theTimingIsCisAndOnlyTheConstructorChangesIt() throws Exception {
    assertEquals(
        new RunnerSettings.Timing(10_000, 500, 30_000, 30_000, 300_000, 5),
        RunnerSettings.Timing.defaults());
    RunnerSettings.Timing fast = new RunnerSettings.Timing(100, 50, 200, 5_000, 400, 3);
    RunnerSettings settings = parse("URL", "https://toy.example", "ID", "r1").withTiming(fast);
    assertEquals(fast, settings.timing());
    assertEquals("r1", settings.runnerId());
  }

  @Test
  void aTimeoutMayCarryItsUnit() throws Exception {
    assertEquals(
        45,
        parse("URL", "http://dev-qits-toy:8080", "ID", "r1", "SLOTS", "3", "DOCKER_TIMEOUT", "45s")
            .dockerTimeoutSeconds());
  }

  @Test
  void theRolloverTimeoutDefaultsToThreeMinutesAndTakesSeconds() throws Exception {
    assertEquals(
        45,
        parse("URL", "https://toy.example", "ID", "r1", "ROLLOVER_TIMEOUT", "45s")
            .rolloverTimeoutSeconds());
    assertTrue(
        invalid("URL", "https://toy.example", "ID", "r1", "ROLLOVER_TIMEOUT", "soon")
            .getMessage()
            .startsWith("QITS_TOY_RUNNER_ROLLOVER_TIMEOUT"));
  }

  @Test
  void theTelemetryUrlIsTheHostsEdgeSiblingUnlessSetAndOffWhenSetEmpty() throws Exception {
    assertNull(
        RunnerSettings.telemetryUrl(ID, null, "http://dev-qits-toy:8080"),
        "an address with one label has no sibling to derive");
    assertNull(RunnerSettings.telemetryUrl(ID, "", "https://toy.qits.example.eu"), "empty is off");
    assertNull(RunnerSettings.telemetryUrl(ID, "  ", "https://toy.qits.example.eu"));
    assertEquals(
        "http://collector:4318",
        RunnerSettings.telemetryUrl(ID, "http://collector:4318/", "https://toy.qits.example.eu"));
    assertTrue(
        assertThrows(
                RunnerSettings.Invalid.class,
                () -> RunnerSettings.telemetryUrl(ID, "collector:4318", "https://toy.example.eu"))
            .getMessage()
            .startsWith("QITS_TOY_RUNNER_TELEMETRY_URL"));
    assertEquals(
        "https://observability.qits.example.eu/observability/api/otel",
        parse("URL", "https://toy.qits.example.eu", "ID", "r1").telemetryUrl(),
        "parse derives it");
    assertNull(
        parse("URL", "https://toy.qits.example.eu", "ID", "r1", "TELEMETRY_URL", "")
            .telemetryUrl());
  }

  @Test
  void theTelemetryUrlIsDerivedByReplacingTheFirstHostLabel() throws Exception {
    // CI's own derivation, reproduced exactly: ci. becomes observability.
    assertEquals(
        "https://observability.qits.example.eu/observability/api/otel",
        RunnerSettings.telemetryUrl(ID, null, "https://ci.qits.example.eu"));
    assertEquals(
        "https://observability.qits.example.eu:8443/observability/api/otel",
        RunnerSettings.telemetryUrl(ID, null, "https://CI.qits.example.eu:8443/ci"));
    // And any other application's name, which is what CI's derivation could not do.
    assertEquals(
        "https://observability.qits.wohlben.eu/observability/api/otel",
        RunnerSettings.telemetryUrl(ID, null, "https://workspaces.qits.wohlben.eu"));
    assertNull(RunnerSettings.telemetryUrl(ID, null, "http://127.0.0.1:8080"), "an address");
    assertNull(RunnerSettings.telemetryUrl(ID, null, "http://[::1]:8080"), "an address");
  }

  @Test
  void selfUpdateDefaultsOnAndParsesTrueOrFalse() throws Exception {
    assertTrue(selfUpdate(null));
    assertTrue(selfUpdate(" "));
    assertTrue(selfUpdate("true"));
    assertTrue(selfUpdate("1"));
    assertFalse(selfUpdate("false"));
    assertFalse(selfUpdate("FALSE"));
    assertFalse(selfUpdate("0"));
    RunnerSettings.Invalid invalid =
        assertThrows(RunnerSettings.Invalid.class, () -> selfUpdate("no"));
    assertEquals("QITS_TOY_RUNNER_SELF_UPDATE is not true or false: 'no'", invalid.getMessage());
  }

  private static boolean selfUpdate(String value) throws RunnerSettings.Invalid {
    Map<String, String> env = env("URL", "https://toy.example", "ID", "r1");
    env.put(ID.env("SELF_UPDATE"), value);
    return RunnerSettings.parse(ID, env::get).selfUpdate();
  }

  @Test
  void everyVariableNameCarriesTheIdentitysPrefix() throws Exception {
    List<String> asked = new ArrayList<>();
    Map<String, String> env = env("URL", "https://toy.qits.example.eu", "ID", "r1");
    RunnerSettings.parse(
        ID,
        name -> {
          asked.add(name);
          return env.get(name);
        });
    assertEquals(
        List.of(
            "QITS_TOY_RUNNER_URL",
            "QITS_TOY_RUNNER_ID",
            "QITS_TOY_RUNNER_REGISTRATION_TOKEN",
            "QITS_TOY_RUNNER_STATE_DIR",
            "QITS_TOY_RUNNER_DOCKER_BINARY",
            "QITS_TOY_RUNNER_SLOTS",
            "QITS_TOY_RUNNER_DOCKER_TIMEOUT",
            "QITS_TOY_RUNNER_ROLLOVER_TIMEOUT",
            "QITS_TOY_RUNNER_SELF_UPDATE",
            "QITS_TOY_RUNNER_TELEMETRY_URL"),
        asked,
        "nothing but the identity's own variables is read");
    // And every refusal names a variable of the same prefix: another runner's install line on the
    // same node is never what this one complains about.
    for (RunnerSettings.Invalid refusal :
        List.of(
            invalid(),
            invalid("URL", "x"),
            invalid("URL", "https://t.example"),
            invalid("URL", "https://t.example", "ID", "?"),
            invalid("URL", "https://t.example", "ID", "r1", "SLOTS", "-1"),
            invalid("URL", "https://t.example", "ID", "r1", "SELF_UPDATE", "maybe"),
            invalid("URL", "https://t.example", "ID", "r1", "TELEMETRY_URL", "nope"))) {
      assertTrue(refusal.getMessage().startsWith(ID.envPrefix()), refusal::getMessage);
    }
  }

  @Test
  void onlyAnExplicitOneAsksForTheVersion() {
    assertTrue(RunnerSettings.printsVersion("1"));
    assertTrue(RunnerSettings.printsVersion("true"));
    assertFalse(RunnerSettings.printsVersion(null));
    assertFalse(RunnerSettings.printsVersion(""));
    assertFalse(RunnerSettings.printsVersion("0"));
  }
}
