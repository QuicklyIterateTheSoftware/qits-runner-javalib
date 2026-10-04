package eu.wohlben.qits.runner.toolkit;

import eu.wohlben.qits.containers.driver.ContainersDriver;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.jboss.logging.Logger;

/**
 * What a {@link HousekeepingPass} is handed: the runner's namespaced driver and its deadline, who
 * it is, where its state lives, a clock, and a log.
 *
 * @param identity the runner's roots, which every name a pass may touch is derived from
 * @param runnerId this runner's id, the value of its ownership label
 * @param driver the qits-containers driver under {@code <labelRoot>.}
 * @param deadline every docker call's, which the driver's methods take explicitly
 * @param stateDir the state volume's mount, which a successor inherits — where a pass keeps a
 *     record it needs across versions
 * @param clock the pass's notion of now; a suite's is fixed
 * @param log the housekeeping log
 */
public record HousekeepingContext(
    RunnerIdentity identity,
    String runnerId,
    ContainersDriver driver,
    Duration deadline,
    Path stateDir,
    Clock clock,
    Logger log) {}
