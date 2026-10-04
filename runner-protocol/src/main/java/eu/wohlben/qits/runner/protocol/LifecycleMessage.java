package eu.wohlben.qits.runner.protocol;

/**
 * The frames every pull-based runner speaks, whatever it runs: the handshake, liveness, the reserve
 * loop, self-update, retirement and quarantine. {@link RunnerCodec} encodes any of these to a
 * framework-free {@code Map} and decodes one back, byte-identical to {@code
 * qits-ci-runner-protocol} 2026.1003.50846 (guarded by {@code CiWireCompatibilityTest}).
 *
 * <p>A new lifecycle frame needs a record, an entry here, the {@link RunnerWire} constants, both
 * codec arms and the round-trip list; the "every permitted subclass is covered" test fails until
 * all of them exist.
 */
public sealed interface LifecycleMessage extends RunnerMessage
    permits Hello,
        Ack,
        Heartbeat,
        Backlog,
        Reserve,
        Nothing,
        Upgrade,
        Retire,
        Quarantined,
        Reinstated {}
