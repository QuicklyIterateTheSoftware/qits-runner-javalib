# qits-runner-javalib

The platform's **pull-based runner scaffolding as a library**: the two pieces every runner that
installs itself on an outside host — a workspaces runner, a front-desk runner, later the CI runner
— needs in common, so that scaffolding is written once rather than copied per kind.

    <dependency>
        <groupId>eu.wohlben.qits</groupId>
        <artifactId>qits-runner-protocol</artifactId>
        <version><!-- a released calver --></version>
    </dependency>
    <dependency>
        <groupId>eu.wohlben.qits</groupId>
        <artifactId>qits-runner-toolkit</artifactId>
        <version><!-- a released calver --></version>
    </dependency>

- **`eu.wohlben.qits:qits-runner-protocol`** — the runner lifecycle frames (`hello`, `ack`,
  `heartbeat`, `backlog`, `reserve`, `nothing`, `upgrade`, `retire`, `quarantined`, `reinstated`)
  and `RunnerCodec`, which delegates every other frame to the runner's own `DomainCodec`. No
  dependency, ever.
- **`eu.wohlben.qits:qits-runner-toolkit`** — the daemon scaffolding every pull-based runner needs:
  register-once, the bearer, the control socket, slot accounting, self-update, decommission,
  health, GC scheduling, per-runner namespacing on a shared node, and the install-script template a
  server renders.

Both are extracted from qits-ci-runner-daemon `2026.1003.50846`, generalised behind an embedder
contract (`RunnerIdentity` + `RunnerDomain`) rather than copied per runner kind. qits-ci-runner-daemon
itself is not on this library yet — see qits-772.

This is a skeleton commit (qits-855): the reactor, `.config/qits/release.yml` and these
conventions. The protocol and toolkit's real content land in the features that follow it.
