package eu.wohlben.qits.runner.toolkit;

/**
 * The process exit codes a runner can end with. <b>A healthy runner never exits</b> — it is a
 * long-lived container under docker's {@code --restart=unless-stopped} — so every code here is a
 * reason {@code docker logs} should name, and each one's restart is either pointless (fix the
 * install line) or exactly right (the platform came back). The clean exits are a {@code Retire},
 * which takes the restart policy away first, and a decommission.
 */
public final class RunnerExit {

  /** Only on an orderly shutdown — a {@code Retire}, or a decommission. */
  public static final int OK = 0;

  /**
   * The env contract was not satisfied — no url, no id, an unparseable number, or an unregistered
   * runner with no registration token. The one line before it names the variable.
   */
  public static final int MISCONFIGURED = 2;

  /**
   * Registration could not reach the host, or the host answered 5xx. Worth a restart: docker's
   * restart policy is the retry.
   */
  public static final int REGISTRATION_UNREACHABLE = 3;

  /** The host's {@code Ack} carried a capability version this binary does not speak. */
  public static final int CAPABILITY_MISMATCH = 4;

  /**
   * The host refused the registration (4xx) — a used, rotated or mistyped token, or a runner id the
   * host does not know. The body is quoted in the log; a restart cannot fix it, a new token can.
   */
  public static final int REGISTRATION_REFUSED = 5;

  /**
   * The state directory could not be read or written, or {@code client.json} is not a client this
   * binary can use. Deleting the file and restarting with a fresh registration token is the fix.
   */
  public static final int STATE_UNUSABLE = 6;

  private RunnerExit() {}
}
