package eu.wohlben.qits.runner.protocol;

import java.util.Set;

/**
 * The single source of truth for the lifecycle frames' tags and field names, byte-identical to
 * {@code qits-ci-runner-protocol} 2026.1003.50846's {@code CiRunnerProtocol}.
 *
 * <p>Messages are JSON objects with a {@code "type"} discriminator ({@link Type}) and a set of
 * fields ({@link Field}); {@link Hello} nests {@link RunnerCapabilities}, which every JSON layer
 * bridges as a nested map. The two lists a runner carries across a lost socket are not here: their
 * names are each runner's own ({@link RunnerVocabulary}).
 *
 * <p>The conversation, for as long as the runner's host is up:
 *
 * <pre>
 *   Hello → Ack{slots} → Backlog{queued}* …
 *   Reserve → (a domain frame) | Nothing     (one outstanding at a time)
 *   Upgrade → (drain) … Retire              (self-update; see Upgrade)
 *   Retire{kind: DELETED}                    (the runner was deleted; it decommissions itself)
 *   Quarantined … Reinstated                 (host stops giving work, then resumes; see Quarantined)
 * </pre>
 *
 * with {@link Heartbeat} underneath from connect to close, and the runner's own frames in between.
 *
 * <p><b>Identity is not on the wire.</b> The runner presents a bearer minted from the idp client it
 * registered; the host knows which runner this is before the first frame is read.
 *
 * <p>There is no capability version here: each runner owns its own and passes it in.
 */
public final class RunnerWire {

  /** The close code ("policy violation") every {@link CloseReason} is sent with. */
  public static final int CLOSE_POLICY_VIOLATION = 1008;

  private RunnerWire() {}

  /**
   * The reasons a host closes a runner's socket with, where the runner branches on one. Close code
   * {@link #CLOSE_POLICY_VIOLATION} for every one.
   */
  public static final class CloseReason {

    /**
     * The bearer on the upgrade is valid and names a client, and no runner here is registered with
     * it — the runner was deleted (or re-registered elsewhere under a new client). Said only when
     * the token HAD a subject: a token with none is a host that cannot read identity, which says
     * nothing about the runner. A runner reading this reason decommissions itself, as on a {@link
     * Retire.Kind#DELETED} retirement, and does not redial.
     */
    public static final String RUNNER_DELETED = "RUNNER_DELETED";

    private CloseReason() {}
  }

  /** The {@code "type"} discriminator values of the lifecycle frames. */
  public static final class Type {
    // runner -> host
    public static final String HELLO = "hello";
    public static final String RESERVE = "reserve";
    public static final String HEARTBEAT = "heartbeat";
    // host -> runner
    public static final String ACK = "ack";
    public static final String BACKLOG = "backlog";
    public static final String NOTHING = "nothing";
    // host -> runner, self-update. FROZEN: see Upgrade and Retire.
    public static final String UPGRADE = "upgrade";
    public static final String RETIRE = "retire";
    // host -> runner, quarantine. Additive: see Quarantined and Reinstated.
    public static final String QUARANTINED = "quarantined";
    public static final String REINSTATED = "reinstated";

    /** Every lifecycle type; a domain type equal to one is refused by {@link RunnerCodec}. */
    static final Set<String> ALL =
        Set.of(
            HELLO,
            RESERVE,
            HEARTBEAT,
            ACK,
            BACKLOG,
            NOTHING,
            UPGRADE,
            RETIRE,
            QUARANTINED,
            REINSTATED);

    private Type() {}
  }

  /** The JSON field names of the lifecycle frames. */
  public static final class Field {
    public static final String TYPE = "type";
    public static final String RUNNER_VERSION = "runnerVersion";
    public static final String CAPABILITY_VERSION = "capabilityVersion";
    public static final String CAPABILITIES = "capabilities";
    public static final String SLOTS = "slots";
    public static final String QUEUED = "queued";

    // Upgrade and Retire. FROZEN, with RUNNER_VERSION above: see Upgrade.
    public static final String VERSION = "version";
    public static final String IMAGE = "image";
    public static final String SHA256 = "sha256";
    public static final String REASON = "reason";

    /** Retire's kind — added, not frozen, and absent for a SUPERSEDED retirement: see Retire. */
    public static final String KIND = "kind";

    // Quarantined and Reinstated.
    public static final String SINCE = "since";
    public static final String BY = "by";

    // RunnerCapabilities
    public static final String DOCKER = "docker";
    public static final String ARCH = "arch";
    public static final String OS = "os";
    public static final String LABELS = "labels";

    /** Every base field; a {@link RunnerVocabulary} name equal to one is refused. */
    static final Set<String> ALL =
        Set.of(
            TYPE,
            RUNNER_VERSION,
            CAPABILITY_VERSION,
            CAPABILITIES,
            SLOTS,
            QUEUED,
            VERSION,
            IMAGE,
            SHA256,
            REASON,
            KIND,
            SINCE,
            BY,
            DOCKER,
            ARCH,
            OS,
            LABELS);

    private Field() {}
  }
}
