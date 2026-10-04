package eu.wohlben.qits.runner.toolkit;

import eu.wohlben.qits.runner.protocol.RunnerVocabulary;
import java.util.regex.Pattern;

/**
 * <b>Who a runner is, as one value</b> — the one thing an embedder supplies besides its {@link
 * RunnerDomain}. Every name the runner uses on its node, on the wire and in its environment is
 * derived from these roots and the runner id; none is configured name by name, so two runners on
 * one node cannot be told to share one.
 *
 * <p>For CI (qits-772) the values are {@code ci}, {@code QITS_CI_RUNNER_}, {@code qits.ci.runner},
 * {@code qits-ci-runner}, {@code qits/qits-ci-runner}, {@code /var/lib/qits-ci-runner}, {@code
 * /ci/api/runners/{id}/register}, {@code qits-ci-runner}, {@code CiRunnerBinary.VERSION}, {@code
 * 1}, {@code heldRuns}/{@code adoptedRuns} and {@code run} — each of which reproduces today's
 * spelling exactly.
 *
 * <p><b>The constructor belts are safety, not style.</b> A runner shares its node with other
 * runners and with qits-containers-service: a label root under {@code qits.containers} would hand
 * the runner's volumes to that service's volume collection, and a name prefix of {@code
 * qits-containers} or {@code qits-buildkitd} would let a runner's teardown reach a name the node's
 * other tenants own. Refusing those roots here makes a shared name impossible to express rather
 * than merely unlikely.
 *
 * @param kind the runner kind, {@code [a-z][a-z0-9-]{1,30}} — {@code workspaces}
 * @param envPrefix every environment variable's prefix, {@code QITS_[A-Z0-9_]+_} — {@code
 *     QITS_WORKSPACES_RUNNER_}
 * @param labelRoot every docker label's root, {@code qits(\.[a-z0-9-]+)+} and never {@code
 *     qits.containers} or under it — {@code qits.workspaces.runner}
 * @param namePrefix every container and volume name's prefix, {@code qits-[a-z0-9-]+} and never
 *     {@code qits-containers*} or {@code qits-buildkitd*} — {@code qits-workspaces-runner}
 * @param imageRepository the runner's own image, without registry or tag — {@code
 *     qits/qits-workspaces-runner}
 * @param defaultStateDir where the state volume is mounted when {@code <envPrefix>STATE_DIR} says
 *     nothing — {@code /var/lib/qits-workspaces-runner}
 * @param registerPath the host's register door below its url, with {@code {id}} where the runner
 *     id goes — {@code /workspaces/api/runners/{id}/register}
 * @param serviceName the telemetry source the runner's own log is bucketed under ({@code
 *     _service/<serviceName>}) — {@code qits-workspaces-runner}
 * @param version the runner's own protocol jar's version ({@code RunnerBinaryVersion}), which is
 *     its image tag
 * @param capabilityVersion the protocol capability this runner speaks; an {@code Ack} in another
 *     one ends the process
 * @param vocabulary the runner's wire names for its held and adopted lists
 * @param workLabelSuffix the work-item label's last segment — {@code workspace}, CI's {@code run}
 */
public record RunnerIdentity(
    String kind,
    String envPrefix,
    String labelRoot,
    String namePrefix,
    String imageRepository,
    String defaultStateDir,
    String registerPath,
    String serviceName,
    String version,
    int capabilityVersion,
    RunnerVocabulary vocabulary,
    String workLabelSuffix) {

  private static final Pattern KIND = Pattern.compile("[a-z][a-z0-9-]{1,30}");
  private static final Pattern ENV_PREFIX = Pattern.compile("QITS_[A-Z0-9_]+_");
  private static final Pattern LABEL_ROOT = Pattern.compile("qits(\\.[a-z0-9-]+)+");
  private static final Pattern NAME_PREFIX = Pattern.compile("qits-[a-z0-9-]+");
  private static final Pattern IMAGE_REPOSITORY =
      Pattern.compile("[a-z0-9]+([._-][a-z0-9]+)*(/[a-z0-9]+([._-][a-z0-9]+)*)*");
  private static final Pattern STATE_DIR = Pattern.compile("/[A-Za-z0-9._/-]+");
  private static final Pattern REGISTER_PATH = Pattern.compile("/[A-Za-z0-9._~/{}-]*");
  private static final Pattern SERVICE_NAME = Pattern.compile("[a-z][a-z0-9-]{1,62}");
  private static final Pattern WORK_LABEL_SUFFIX = Pattern.compile("[a-z][a-z0-9-]{0,30}");

  /** Where the runner id goes in {@link #registerPath}. */
  public static final String ID_PLACEHOLDER = "{id}";

  /** The label root qits-containers-service owns; no runner's may be it or sit under it. */
  static final String CONTAINERS_LABEL_ROOT = "qits.containers";

  /** The name prefixes the node's other tenants own; no runner's may start with one. */
  static final String[] FOREIGN_NAME_PREFIXES = {"qits-containers", "qits-buildkitd"};

  public RunnerIdentity {
    require("kind", kind, KIND);
    require("envPrefix", envPrefix, ENV_PREFIX);
    require("labelRoot", labelRoot, LABEL_ROOT);
    if (labelRoot.equals(CONTAINERS_LABEL_ROOT)
        || labelRoot.startsWith(CONTAINERS_LABEL_ROOT + ".")) {
      throw new IllegalArgumentException(
          "labelRoot '"
              + labelRoot
              + "' is qits-containers-service's namespace, whose volume collection would take this"
              + " runner's volumes for its own");
    }
    require("namePrefix", namePrefix, NAME_PREFIX);
    for (String foreign : FOREIGN_NAME_PREFIXES) {
      if (namePrefix.startsWith(foreign)) {
        throw new IllegalArgumentException(
            "namePrefix '"
                + namePrefix
                + "' starts with "
                + foreign
                + ", a name another tenant of the node owns");
      }
    }
    require("imageRepository", imageRepository, IMAGE_REPOSITORY);
    require("defaultStateDir", defaultStateDir, STATE_DIR);
    require("registerPath", registerPath, REGISTER_PATH);
    if (!registerPath.contains(ID_PLACEHOLDER)) {
      throw new IllegalArgumentException(
          "registerPath '" + registerPath + "' has no " + ID_PLACEHOLDER + " for the runner id");
    }
    require("serviceName", serviceName, SERVICE_NAME);
    if (version == null || version.isBlank() || version.startsWith("$")) {
      throw new IllegalArgumentException("version is not a resolved version: '" + version + "'");
    }
    if (capabilityVersion < 1) {
      throw new IllegalArgumentException(
          "capabilityVersion must be at least 1, not " + capabilityVersion);
    }
    if (vocabulary == null) {
      throw new IllegalArgumentException("vocabulary is not set");
    }
    require("workLabelSuffix", workLabelSuffix, WORK_LABEL_SUFFIX);
  }

  /** {@code <envPrefix><name>} — every variable the runner reads is spelled through here. */
  public String env(String name) {
    return envPrefix + name;
  }

  /** The register door for {@code runnerId}: {@link #registerPath} with the id filled in. */
  public String registerPath(String runnerId) {
    return registerPath.replace(ID_PLACEHOLDER, runnerId);
  }

  /**
   * {@code <labelRoot>.self-update} — the capability label a runner with self-update switched off
   * advertises as {@code false}.
   */
  public String selfUpdateLabel() {
    return labelRoot + ".self-update";
  }

  /**
   * {@code <labelRoot>.} — the qits-containers-driver label namespace of this runner's workloads,
   * never {@code qits.containers.}.
   */
  public String driverNamespace() {
    return labelRoot + ".";
  }

  /**
   * How the runner names itself at the head of its log lines: {@code <kind>-runner}, which is CI's
   * {@code ci-runner} exactly.
   */
  public String logName() {
    return kind + "-runner";
  }

  /** The host service this runner registers with, as a person names it: {@code qits-<kind>}. */
  public String hostName() {
    return "qits-" + kind;
  }

  private static void require(String what, String value, Pattern pattern) {
    if (value == null || !pattern.matcher(value).matches()) {
      throw new IllegalArgumentException(
          what + " '" + value + "' does not match " + pattern.pattern());
    }
  }
}
