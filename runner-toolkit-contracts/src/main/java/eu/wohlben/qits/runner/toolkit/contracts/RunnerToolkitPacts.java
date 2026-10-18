package eu.wohlben.qits.runner.toolkit.contracts;

import eu.wohlben.qits.pact.consumer.ConsumerPact;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import eu.wohlben.qits.pact.consumer.GoldenMasters;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.runner.toolkit.contracts.RunnerToolkitPact.Feature;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;

/**
 * <b>One daemon's pacts of the toolkit's calls</b> (qits-1163): the features it declares, one
 * {@link ConsumerPact} per provider through {@link RunnerToolkitPact#VIA}, a {@link ToolkitRow} per
 * row, and the guard that the declaration is the daemon's real use. Immutable; each feature method
 * answers a new value.
 *
 * <p>A feature is in only when the daemon declares it, and {@link #assertDeclaresWhatTheMainClassesUse}
 * fails both ways — a toolkit call the main classes can make that is not declared, and a declared
 * one they cannot make — so the pact holds exactly what the daemon's use sends.
 */
public final class RunnerToolkitPacts {

  /** Where a maven module's main classes are compiled to, relative to the module (surefire's cwd). */
  public static final Path MAIN_CLASSES = Path.of("target", "classes");

  private record Declared(GoldenMasters masters, List<GoldenInteraction> rows, ConsumerPact.Call drive) {}

  private final String consumer;
  private final Map<Feature, Declared> features;

  private RunnerToolkitPacts(String consumer, Map<Feature, Declared> features) {
    this.consumer = Objects.requireNonNull(consumer, "consumer");
    this.features = Collections.unmodifiableMap(new EnumMap<>(features));
  }

  static RunnerToolkitPacts of(String consumer) {
    if (consumer == null || consumer.isBlank()) {
      throw new IllegalArgumentException("the consumer is the embedding daemon's repository name");
    }
    return new RunnerToolkitPacts(consumer, new EnumMap<>(Feature.class));
  }

  /** {@link Feature#TOKEN} against {@link RunnerToolkitPact#IDP}. */
  public RunnerToolkitPacts token() {
    return token(RunnerToolkitPact.IDP);
  }

  /** {@link Feature#TOKEN} against qits-idp's recordings at another root: a suite's fixtures. */
  public RunnerToolkitPacts token(GoldenMasters idp) {
    return with(Feature.TOKEN, new Declared(idp, RunnerToolkitPact.tokenRows(), ToolkitDrives.token(idp)));
  }

  /** {@link Feature#LOG_EXPORT} against {@link RunnerToolkitPact#OBSERVABILITY}. */
  public RunnerToolkitPacts logExport() {
    return logExport(RunnerToolkitPact.OBSERVABILITY);
  }

  /** {@link Feature#LOG_EXPORT} against qits-observability's recordings at another root. */
  public RunnerToolkitPacts logExport(GoldenMasters observability) {
    return with(
        Feature.LOG_EXPORT,
        new Declared(observability, RunnerToolkitPact.logExportRows(), ToolkitDrives.logExport()));
  }

  /**
   * {@link Feature#REGISTER} at {@code host}, whose register door is {@code operationId}, with the
   * daemon's own {@code identity}: its {@link RunnerIdentity#registerPath} is what is sent, so a
   * daemon whose path the host does not serve fails its pact. The preferred form.
   */
  public RunnerToolkitPacts register(GoldenMasters host, String operationId, RunnerIdentity identity) {
    Objects.requireNonNull(host, "host");
    Objects.requireNonNull(operationId, "operationId");
    return with(
        Feature.REGISTER,
        new Declared(host, RunnerToolkitPact.registerRows(operationId), ToolkitDrives.register(host, identity)));
  }

  /**
   * {@link Feature#REGISTER} without the daemon's identity: the path sent is the recorded one, its
   * {@code {runnerId}} the runner id. Prefer {@link #register(GoldenMasters, String, RunnerIdentity)},
   * which pacts the daemon's real path.
   */
  public RunnerToolkitPacts register(GoldenMasters host, String operationId) {
    return register(host, operationId, null);
  }

  private RunnerToolkitPacts with(Feature feature, Declared declared) {
    Map<Feature, Declared> next = new EnumMap<>(Feature.class);
    next.putAll(features);
    next.put(feature, declared);
    return new RunnerToolkitPacts(consumer, next);
  }

  public String consumer() {
    return consumer;
  }

  /** The features declared so far. */
  public Set<Feature> features() {
    return features.isEmpty() ? EnumSet.noneOf(Feature.class) : EnumSet.copyOf(features.keySet());
  }

  /**
   * One pact per provider, each through {@link RunnerToolkitPact#VIA}: its {@code file()} is {@code
   * <consumer>_qits-runner-javalib_<provider>.json} and every interaction carries {@code qits-via}.
   *
   * @throws IllegalStateException when the qits-runner-toolkit on the classpath is not this
   *     module's version: the pact would name a toolkit the daemon does not run
   */
  public List<ConsumerPact> pacts() {
    String onClasspath = RunnerToolkitPact.toolkitVersionOnClasspath();
    if (onClasspath != null && !onClasspath.equals(RunnerToolkitPact.VIA.version())) {
      throw new IllegalStateException(
          "qits-runner-toolkit-contracts is "
              + RunnerToolkitPact.VIA.version()
              + " but the qits-runner-toolkit on the classpath is "
              + onClasspath
              + ": pin both at the same version");
    }
    Map<String, GoldenMasters> masters = new LinkedHashMap<>();
    Map<String, List<GoldenInteraction>> rows = new LinkedHashMap<>();
    features.values().forEach(d -> {
      masters.putIfAbsent(d.masters().repository(), d.masters());
      rows.computeIfAbsent(d.masters().repository(), k -> new ArrayList<>()).addAll(d.rows());
    });
    return masters.keySet().stream()
        .map(repo -> ConsumerPact.of(consumer, masters.get(repo), rows.get(repo)).via(RunnerToolkitPact.VIA))
        .toList();
  }

  /** Every declared row with its drive and the pact it belongs to. */
  public List<ToolkitRow> rows() {
    Map<String, ConsumerPact> byProvider = new LinkedHashMap<>();
    pacts().forEach(p -> byProvider.put(p.masters().repository(), p));
    List<ToolkitRow> out = new ArrayList<>();
    features.forEach((feature, d) -> {
      ConsumerPact pact = byProvider.get(d.masters().repository());
      d.rows().forEach(row -> out.add(new ToolkitRow(feature, pact, row, d.drive())));
    });
    return out;
  }

  /** {@link #assertDeclaresWhatTheMainClassesUse(Path)} on {@link #MAIN_CLASSES}. */
  public void assertDeclaresWhatTheMainClassesUse() {
    assertDeclaresWhatTheMainClassesUse(MAIN_CLASSES);
  }

  /**
   * Fails unless the declared features are exactly those the class files under {@code classes} can
   * send through the toolkit (see {@link ToolkitUsage}): an undeclared one would leave a real call
   * out of the pact, and a declared unused one would pact a call the daemon never makes.
   */
  public void assertDeclaresWhatTheMainClassesUse(Path classes) {
    Assertions.assertTrue(Files.isDirectory(classes),
        classes.toAbsolutePath() + " is not a directory of compiled main classes; pass the daemon's own");
    Set<Feature> used = ToolkitUsage.used(classes);
    Set<String> referenced = ToolkitUsage.referenced(classes);
    Set<Feature> missing = EnumSet.noneOf(Feature.class);
    missing.addAll(used);
    missing.removeAll(features.keySet());
    Set<Feature> unused = features();
    unused.removeAll(used);
    Assertions.assertTrue(missing.isEmpty(), consumer + "'s main classes use " + referenced
        + " of qits-runner-toolkit, which send " + missing + ", and those are not declared");
    Assertions.assertTrue(unused.isEmpty(), consumer + " declares " + unused
        + ", but its main classes use only " + referenced + " of qits-runner-toolkit, which send " + used);
  }

  /**
   * Everything a daemon's suite needs, as one {@code @TestFactory}: the guard on {@link
   * #MAIN_CLASSES}, each pact's committed file ({@code ConsumerPact#compareOrWritePactFile}, skipped
   * while none of its rows is recorded) and references, and each row through its drive (skipped,
   * naming the provider state it needs, while unrecorded).
   */
  public Stream<DynamicTest> tests() {
    List<DynamicTest> tests = new ArrayList<>();
    tests.add(DynamicTest.dynamicTest(
        "the declared features " + features.keySet() + " are what " + consumer + "'s main classes use",
        this::assertDeclaresWhatTheMainClassesUse));
    for (ConsumerPact pact : pacts()) {
      tests.add(DynamicTest.dynamicTest("pacts/" + pact.file() + " is what the rows write", () -> {
        pact.compareOrWritePactFile();
        pact.assertEveryInteractionCarriesBothReferences();
      }));
    }
    for (ToolkitRow row : rows()) {
      tests.add(DynamicTest.dynamicTest(row.name(), row::run));
    }
    return tests.stream();
  }
}
