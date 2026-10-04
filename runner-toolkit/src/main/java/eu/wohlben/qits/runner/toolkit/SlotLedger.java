package eu.wohlben.qits.runner.toolkit;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The slot arithmetic, and the one rule it enforces: <b>send {@code Reserve} when a slot is free,
 * the backlog says there is work, and no {@code Reserve} is outstanding</b>.
 *
 * <p>Every method answers "send a Reserve now?", and a {@code true} has already counted the request
 * as outstanding — so the caller sends exactly what it was told to and never decides anything
 * itself. That is what makes the rule testable without a socket.
 *
 * <ul>
 *   <li>{@code Nothing} parks the runner until the next {@code Backlog} or {@link #released}: the
 *       host just said there is nothing this runner can take, and asking again at once would spin
 *       on a queue it cannot serve.
 *   <li>{@link #taken} decrements the remembered backlog, because the host's next push says the same
 *       thing a round trip later; without it a runner with two free slots and a backlog of one
 *       would reserve twice and collect a guaranteed {@code Nothing}.
 *   <li>{@link #released} is the only thing that frees a slot. <b>What holds a slot is the
 *       domain's</b>: for CI a run from its {@code Take} to its {@code Released}; for a workspace
 *       runner a running container, from its start to its stop.
 *   <li>{@code Upgrade} drains: from then on nothing is reserved, for the rest of the process's life
 *       and across reconnects — a process that is being replaced never takes work again.
 * </ul>
 *
 * <p>Synchronized, because frames arrive on the event loop and the domain answers from workers.
 */
public final class SlotLedger {

  private int slots;
  private int backlog;
  private boolean outstanding;
  private boolean parked;
  private boolean draining;
  private final Set<String> held = new LinkedHashSet<>();

  /**
   * The items held when the socket dropped, until the next session's first {@code Ack} says which
   * of them the host kept ({@link #settleCarried}); empty on a first connection and once settled.
   */
  private final Set<String> carried = new LinkedHashSet<>();

  /** The host's cap: {@code Ack.slots} wins over the operator's. Held items are kept. */
  public synchronized boolean onAck(int slots) {
    this.slots = Math.max(0, slots);
    return decide();
  }

  public synchronized boolean onBacklog(int queued) {
    backlog = Math.max(0, queued);
    parked = false;
    return decide();
  }

  /** A work item now holds a slot: the outstanding {@code Reserve} is answered. */
  public synchronized boolean taken(String workId) {
    outstanding = false;
    held.add(workId);
    backlog = Math.max(0, backlog - 1);
    return decide();
  }

  public synchronized boolean onNothing() {
    outstanding = false;
    parked = true;
    return false;
  }

  /** A work item no longer holds its slot. */
  public synchronized boolean released(String workId) {
    held.remove(workId);
    carried.remove(workId);
    parked = false;
    return decide();
  }

  /** Never reserve again. Held items are kept: they finish, and their release still counts. */
  public synchronized void drain() {
    draining = true;
  }

  public synchronized boolean draining() {
    return draining;
  }

  /**
   * The socket closed: no slots and nothing outstanding until the next {@code Ack}, and every held
   * item is <b>carried</b> — kept for the host to adopt on the next connection. Draining survives
   * it too — a reconnect does not un-ask the upgrade.
   */
  public synchronized void suspend() {
    slots = 0;
    backlog = 0;
    outstanding = false;
    parked = false;
    carried.addAll(held);
  }

  /** The items to claim in the next {@code Hello}, in the order they were taken — a copy. */
  public synchronized List<String> carried() {
    return List.copyOf(carried);
  }

  /**
   * The host answered the claim: keep the carried items it {@code adopted} and forget the rest,
   * which are returned for the domain to remove. {@code null} — a host that never read the claim —
   * adopts nothing. Only the first {@code Ack} of a session settles; after it there is nothing
   * carried and this returns nothing.
   */
  public synchronized List<String> settleCarried(List<String> adopted) {
    List<String> dropped = new ArrayList<>();
    for (String workId : carried) {
      if (adopted == null || !adopted.contains(workId)) {
        held.remove(workId);
        dropped.add(workId);
      }
    }
    carried.clear();
    return dropped;
  }

  public synchronized int held() {
    return held.size();
  }

  /** The items held now, in the order they were taken — a copy. */
  public synchronized List<String> heldIds() {
    return List.copyOf(held);
  }

  public synchronized boolean holds(String workId) {
    return held.contains(workId);
  }

  private boolean decide() {
    if (draining || outstanding || parked || held.size() >= slots || backlog <= 0) {
      return false;
    }
    outstanding = true;
    return true;
  }
}
