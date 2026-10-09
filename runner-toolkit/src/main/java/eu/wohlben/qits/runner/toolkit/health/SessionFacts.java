package eu.wohlben.qits.runner.toolkit.health;

import java.time.Instant;
import java.util.List;

/**
 * What a runner knows of its session, as the {@code session} check reports it.
 *
 * @param connected whether the control socket is up
 * @param connectedSince since when; null while not connected
 * @param slots the slots of the last {@code Ack}; null before the first
 * @param held the work items holding a slot (the slot ledger's rows); empty, never null
 */
public record SessionFacts(
    boolean connected, Instant connectedSince, Integer slots, List<String> held) {

  public SessionFacts {
    held = held == null ? List.of() : List.copyOf(held);
  }

  /**
   * The facts without the held rows — for the {@code RunnerSession} flavour of {@link
   * HealthContext#of}, which reads them off the session's slot ledger.
   */
  public SessionFacts(boolean connected, Instant connectedSince, Integer slots) {
    this(connected, connectedSince, slots, List.of());
  }

  /** These facts with {@code held} replaced. */
  public SessionFacts withHeld(List<String> held) {
    return new SessionFacts(connected, connectedSince, slots, held);
  }
}
