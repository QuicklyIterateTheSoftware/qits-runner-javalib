package eu.wohlben.qits.runner.toolkit.contracts;

import eu.wohlben.qits.pact.consumer.ConsumerPact;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import eu.wohlben.qits.runner.toolkit.contracts.RunnerToolkitPact.Feature;
import org.junit.jupiter.api.Assumptions;

/**
 * One row of a daemon's toolkit pacts, with the drive that makes its call through the real toolkit.
 *
 * @param feature the toolkit feature the row belongs to
 * @param pact the daemon's pact with the row's provider, through {@link RunnerToolkitPact#VIA}
 * @param row the (state, operation) and what the toolkit reads of the answer
 * @param drive the toolkit making the call against the pact mock server
 */
public record ToolkitRow(
    Feature feature, ConsumerPact pact, GoldenInteraction row, ConsumerPact.Call drive) {

  /** Whether the provider recorded the row's state and operation, so the pact file holds it. */
  public boolean recorded() {
    return pact.masters().recorded(row.state(), row.operationId());
  }

  /** {@code needs provider state '<state>' for <operationId> in <provider>}. */
  public String needs() {
    return pact.needs(row);
  }

  /** {@code <feature> <provider>: <description> [<state>]}, a test's display name. */
  public String name() {
    return feature + " " + pact.masters().repository() + ": " + row.description() + " [" + row.state() + "]";
  }

  /**
   * Runs the drive against a mock server answering this row's interaction. A row the provider has
   * not recorded is aborted (skipped) with {@link #needs()}, as {@code ConsumerPact} documents: it
   * stays out of the pact file until the provider records its state.
   */
  public void run() {
    Assumptions.assumeTrue(recorded(), this::needs);
    pact.run(row, drive);
  }
}
