package eu.wohlben.qits.runner.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The reserve gate: a free slot, a positive backlog, and nothing outstanding — all three. */
class SlotLedgerTest {

  @Test
  void noReserveBeforeTheBacklogSaysThereIsWork() {
    SlotLedger r = new SlotLedger();
    assertFalse(r.onAck(2), "an empty backlog is no reason to ask");
    assertTrue(r.onBacklog(1));
  }

  @Test
  void onlyOneReserveIsOutstandingAtATime() {
    SlotLedger r = new SlotLedger();
    r.onAck(3);
    assertTrue(r.onBacklog(5));
    assertFalse(r.onBacklog(6), "a second Reserve while the first is unanswered");
  }

  @Test
  void aTakeAsksAgainWhileSlotsAndBacklogRemain() {
    SlotLedger r = new SlotLedger();
    r.onAck(2);
    assertTrue(r.onBacklog(3));
    assertTrue(r.taken("a"), "one held of two, backlog still positive");
    assertFalse(r.taken("b"), "both slots held");
    assertEquals(2, r.held());
  }

  @Test
  void aTakeCountsDownTheRememberedBacklogSoTheLastRunIsNotReservedTwice() {
    SlotLedger r = new SlotLedger();
    r.onAck(2);
    assertTrue(r.onBacklog(1));
    assertFalse(r.taken("a"), "the one queued run is the one just taken");
  }

  @Test
  void nothingParksTheRunnerUntilTheNextBacklog() {
    SlotLedger r = new SlotLedger();
    r.onAck(1);
    assertTrue(r.onBacklog(2));
    assertFalse(r.onNothing());
    assertFalse(r.onBacklog(0), "a backlog that emptied is still no reason");
  }

  @Test
  void aParkedRunnerAsksAgainOnTheNextBacklogPush() {
    SlotLedger r = new SlotLedger();
    r.onAck(1);
    assertTrue(r.onBacklog(2));
    r.onNothing();
    assertTrue(r.onBacklog(2), "the host pushed again; the queue may hold something new");
  }

  @Test
  void aBacklogWhileTheReserveIsOutstandingSurvivesTheTakeThatAnswersIt() {
    SlotLedger r = new SlotLedger();
    r.onAck(6);
    assertTrue(r.onBacklog(1));
    assertFalse(r.onBacklog(1), "the host's post-claim count, while the Reserve is still out");
    assertTrue(r.taken("A"), "the take must not wipe out the backlog the host just sent");
  }

  @Test
  void aBacklogWhileTheReserveIsOutstandingSurvivesTheNothingThatAnswersIt() {
    SlotLedger r = new SlotLedger();
    r.onAck(6);
    assertTrue(r.onBacklog(1));
    assertFalse(r.onBacklog(2), "a newer row queued while the Reserve is still out");
    assertTrue(r.onNothing(), "that newer Backlog outranks the park");
  }

  @Test
  void onceTheNothingRaceReReservesAPlainNothingWithNoNewerBacklogStillParks() {
    SlotLedger r = new SlotLedger();
    r.onAck(6);
    assertTrue(r.onBacklog(1));
    assertFalse(r.onBacklog(2));
    assertTrue(r.onNothing(), "re-reserves on the newer Backlog");
    assertFalse(r.onNothing(), "no further Backlog arrived this time: an ordinary park");
    assertTrue(r.onBacklog(1), "and the next Backlog un-parks it as ever");
  }

  @Test
  void aReleasedRunFreesItsSlotAndAsksAgainWhenThereIsWork() {
    SlotLedger r = new SlotLedger();
    r.onAck(1);
    assertTrue(r.onBacklog(3));
    assertFalse(r.taken("a"), "the one slot is held");
    assertTrue(r.released("a"));
    assertFalse(r.holds("a"));
  }

  @Test
  void theHostsSlotCountIsTheCap() {
    SlotLedger r = new SlotLedger();
    r.onAck(0);
    assertFalse(r.onBacklog(10), "an Ack granting no slots grants no runs");
  }

  @Test
  void aClosedSessionCarriesWhatItHeldUntilTheHostSaysWhichItKept() {
    SlotLedger r = new SlotLedger();
    r.onAck(2);
    r.onBacklog(3);
    r.taken("a");
    r.taken("b");
    r.suspend();
    assertEquals(List.of("a", "b"), r.carried(), "claimed by the next Hello");
    assertEquals(2, r.held(), "and still held while the host decides");
    assertFalse(r.onBacklog(5), "no Ack yet in the new session, so no slots");

    assertEquals(List.of("b"), r.settleCarried(List.of("a")), "the one the host did not keep");
    assertEquals(1, r.held());
    assertEquals(List.of(), r.carried());
    assertTrue(r.onAck(2), "the new session's Ack: one slot still free, the backlog known");
    assertEquals(List.of(), r.settleCarried(null), "only the first Ack of a session settles");
    assertEquals(1, r.held());
  }

  @Test
  void aHostThatSaysNothingAboutCarriedRunsKeptNone() {
    SlotLedger r = new SlotLedger();
    r.onAck(1);
    r.onBacklog(1);
    r.taken("a");
    r.suspend();
    assertEquals(List.of("a"), r.settleCarried(null));
    assertEquals(0, r.held());
  }

  @Test
  void aDrainingRunnerNeverReservesAgainAndAReconnectDoesNotUndoIt() {
    SlotLedger r = new SlotLedger();
    r.onAck(2);
    assertTrue(r.onBacklog(3));
    r.taken("a");
    r.drain();
    assertFalse(r.onBacklog(3), "no Reserve after the Upgrade");
    assertFalse(r.released("a"), "a freed slot is not refilled");
    assertEquals(0, r.held());
    r.suspend();
    assertFalse(r.onAck(2));
    assertFalse(r.onBacklog(9));
    assertTrue(r.draining());
  }
}
