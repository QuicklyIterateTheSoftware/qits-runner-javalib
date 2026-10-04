package eu.wohlben.qits.runner.harness;

import eu.wohlben.qits.containers.driver.ContainersDriver;
import eu.wohlben.qits.containers.driver.spec.ContainerSpec;
import eu.wohlben.qits.containers.driver.spec.ContainersIdentifiers;
import eu.wohlben.qits.containers.driver.spec.LifecyclePolicy;
import eu.wohlben.qits.containers.driver.spec.VolumeSpec;
import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.runner.protocol.DomainCodec;
import eu.wohlben.qits.runner.protocol.RunnerCapabilities;
import eu.wohlben.qits.runner.toolkit.HostFacts;
import eu.wohlben.qits.runner.toolkit.HousekeepingContext;
import eu.wohlben.qits.runner.toolkit.HousekeepingPass;
import eu.wohlben.qits.runner.toolkit.RunnerDomain;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.runner.toolkit.RunnerSession;
import eu.wohlben.qits.runner.toolkit.RunnerSettings;
import eu.wohlben.qits.runner.toolkit.TeardownItem;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.jboss.logging.Logger;

/**
 * The harness runner's domain: a toy that is exactly the shape a workspaces or front-desk runner
 * fills in, so the toolkit is proven by an embedder before qits-624 is one.
 *
 * <ul>
 *   <li>{@code take{itemId, image}}: the item holds a slot ({@link RunnerSession#taken}), its image
 *       is pulled under the runner's own login ({@link RunnerSession#registry()}), and a container
 *       named {@code qits-harness-runner-item-<itemId8>} runs carrying {@link
 *       eu.wohlben.qits.runner.toolkit.OwnResources#labels} in the driver's own-namespace {@code
 *       labels} argument; then {@code started{itemId, containerId}}.
 *   <li>{@code release{itemId}}: that container is removed, the slot freed ({@link
 *       RunnerSession#released}), and {@code released{itemId}} answered.
 *   <li><b>Quiescent when nothing is in flight</b> — no take or release half done. An item whose
 *       container is merely running does not hold a rollover up: like a workspace's, its container
 *       outlives the runner process, and the successor adopts it through the host's {@code Ack}.
 * </ul>
 *
 * <p>Its node hooks are one of each, so both are exercised: a {@link HousekeepingPass} that keeps
 * the runner's scratch volume ({@code qits-harness-runner-scratch-<id8>}) on the node, and that
 * same volume as a {@link TeardownItem} — it carries no runner label, so only the item reaches it at
 * decommission.
 */
public final class HarnessDomain implements RunnerDomain<HarnessMessage> {

  private static final Logger LOG = Logger.getLogger(HarnessDomain.class);

  /** The purpose segment of every item container's name. */
  static final String ITEM = "item";

  /** The purpose segment of the scratch volume's name. */
  static final String SCRATCH = "scratch";

  private static final HarnessCodec CODEC = new HarnessCodec();

  private final RunnerIdentity identity = HarnessIdentity.IDENTITY;
  private final String runnerId;
  private final Duration deadline;

  /** Takes and releases under way: the rollover waits for zero. */
  private final AtomicInteger inFlight = new AtomicInteger();

  /** Built by {@code RunnerLauncher} from the parsed settings, as every embedder's domain is. */
  public HarnessDomain(RunnerSettings settings) {
    this.runnerId = settings.runnerId();
    this.deadline = Duration.ofSeconds(settings.dockerTimeoutSeconds());
  }

  @Override
  public DomainCodec<HarnessMessage> codec() {
    return CODEC;
  }

  @Override
  public RunnerCapabilities capabilities(HostFacts host) {
    return new RunnerCapabilities(host.docker(), host.arch(), host.os(), Map.of());
  }

  /** The carried items are the keep set; any other item container of this runner goes. */
  @Override
  public void onSessionStart(RunnerSession session, List<String> carried) {
    session.own().reconcile(Set.copyOf(carried));
  }

  @Override
  public void onAck(Ack ack, RunnerSession session) {}

  /** The host did not keep these: their containers are this runner's to remove. */
  @Override
  public void onDropped(List<String> workIds, RunnerSession session) {
    for (String itemId : workIds) {
      remove(session, itemId);
    }
  }

  @Override
  public void onMessage(HarnessMessage message, RunnerSession session) {
    switch (message) {
      case HarnessMessage.Take take -> take(take, session);
      case HarnessMessage.Release release -> release(release, session);
      case HarnessMessage.Started started ->
          LOG.debugf("the harness ignored a started from its host: %s", started.itemId());
      case HarnessMessage.Released released ->
          LOG.debugf("the harness ignored a released from its host: %s", released.itemId());
    }
  }

  @Override
  public boolean quiescent() {
    return inFlight.get() == 0;
  }

  /**
   * Stop every held item's container, so nothing of this runner runs on past its deletion; the
   * helper removes the containers, by the runner's label, once this process has gone.
   */
  @Override
  public void onDecommission(RunnerSession session) {
    for (String itemId : session.held()) {
      String name = containerName(itemId);
      ContainersDriver.OpResult stopped = session.driver().stop(name, deadline);
      if (!stopped.ok()) {
        LOG.warnf("the harness could not stop %s: %s", name, stopped.detail());
      }
    }
  }

  @Override
  public List<TeardownItem> teardown() {
    return List.of(new TeardownItem(TeardownItem.Type.VOLUME, scratchVolume()));
  }

  @Override
  public List<HousekeepingPass> housekeeping() {
    return List.of(new ScratchPass());
  }

  private void take(HarnessMessage.Take take, RunnerSession session) {
    inFlight.incrementAndGet();
    try {
      String itemId = take.itemId();
      String name;
      try {
        // Checked before the slot is counted: an id that cannot name a container under the
        // driver's own rules is refused, rather than taken and never started.
        name = ContainersIdentifiers.requireContainerName(containerName(itemId));
      } catch (IllegalArgumentException refused) {
        LOG.warnf("the harness refused a take: %s", refused.getMessage());
        return;
      }
      session.taken(itemId);
      Optional<String> notPulled = session.registry().pull(take.image());
      if (notPulled.isPresent()) {
        LOG.warnf("the harness could not pull for %s: %s", itemId, notPulled.get());
        session.released(itemId);
        return;
      }
      ContainersDriver.Started started =
          session
              .driver()
              .run(
                  ContainerSpec.builder(take.image())
                      .pullPolicy(ContainerSpec.PullPolicy.NEVER)
                      .build(),
                  name,
                  session.own().labels(itemId),
                  LifecyclePolicy.explicitLifetime(),
                  deadline);
      if (!started.started()) {
        LOG.warnf("the harness could not start %s: %s", name, started.detail());
        session.released(itemId);
        return;
      }
      session.send(new HarnessMessage.Started(itemId, started.containerId()));
    } finally {
      inFlight.decrementAndGet();
    }
  }

  private void release(HarnessMessage.Release release, RunnerSession session) {
    inFlight.incrementAndGet();
    try {
      remove(session, release.itemId());
      session.released(release.itemId());
      session.send(new HarnessMessage.Released(release.itemId()));
    } finally {
      inFlight.decrementAndGet();
    }
  }

  private void remove(RunnerSession session, String itemId) {
    String name = containerName(itemId);
    ContainersDriver.OpResult gone = session.driver().remove(name, deadline);
    if (!gone.ok()) {
      LOG.warnf("the harness could not remove %s: %s", name, gone.detail());
    }
  }

  /** {@code qits-harness-runner-item-<itemId8>}, derived — never a name a frame supplied. */
  private String containerName(String itemId) {
    return identity.nodeName(itemId, ITEM);
  }

  /** {@code qits-harness-runner-scratch-<id8>}. */
  private String scratchVolume() {
    return identity.nodeVolume(runnerId, SCRATCH);
  }

  /**
   * Keeps the scratch volume on the node — a person who pruned volumes by hand gets it back within
   * one interval. Creating a volume that exists is a no-op in docker, so the pass is idempotent.
   */
  private final class ScratchPass implements HousekeepingPass {

    @Override
    public String name() {
      return "harness scratch";
    }

    @Override
    public void run(HousekeepingContext context) {
      ContainersDriver.OpResult made =
          context
              .driver()
              .ensureVolume(new VolumeSpec(scratchVolume()), Map.of(), context.deadline());
      if (!made.ok()) {
        context.log().warnf("the harness could not keep %s: %s", scratchVolume(), made.detail());
      }
    }
  }
}
