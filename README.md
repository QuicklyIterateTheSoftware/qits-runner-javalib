# qits-runner-javalib

The platform's **pull-based runner scaffolding as a library**: what every runner that installs
itself on an outside host — a workspaces runner, `qits-projects-desk-runner-daemon` (qits-767),
later the CI runner — needs in common, written once rather than copied per kind.

- **`eu.wohlben.qits:qits-runner-protocol`** — the runner lifecycle frames (`hello`, `ack`,
  `heartbeat`, `backlog`, `reserve`, `nothing`, `upgrade`, `retire`, `quarantined`, `reinstated`)
  and `RunnerCodec`, which encodes those itself and hands every other frame to the runner's own
  `DomainCodec`. No dependency, ever.
- **`eu.wohlben.qits:qits-runner-toolkit`** — the daemon around a runner's domain: register-once,
  the bearer, the control socket, slot accounting, self-update, decommission, health, housekeeping,
  the runner's own log shipping, per-runner namespacing on a shared node, and the install-script
  template a server renders. Depends on the protocol, `io.vertx:vertx-core`,
  `org.jboss.logging:jboss-logging` and `eu.wohlben.qits:qits-containers-driver`. No CDI, no
  Quarkus, no Jackson databind, no reflection: it links into a static musl native image.

Both are extracted from qits-ci-runner-daemon `2026.1003.50846`, generalised behind one embedder
contract (`RunnerIdentity` + `RunnerDomain`). qits-ci-runner-daemon itself is not on this library
yet; it moves in qits-772.

The third module, `runner-harness`, is never published: it is a complete runner embedding the
toolkit, with a toy domain, and its suite runs that runner's whole life against a fake host. It is
the worked example below.

## Depending on it

A **runner** pins both, in the module that builds the daemon:

    <dependency>
        <groupId>eu.wohlben.qits</groupId>
        <artifactId>qits-runner-protocol</artifactId>
        <version><!-- a released calver --></version>
    </dependency>
    <dependency>
        <groupId>eu.wohlben.qits</groupId>
        <artifactId>qits-runner-toolkit</artifactId>
        <version><!-- the same calver --></version>
    </dependency>

A **server** that renders the install script (qits-workspaces-service for qits-624) pins **both
jars too, each with a `*:*` exclusion**:

    <dependency>
        <groupId>eu.wohlben.qits</groupId>
        <artifactId>qits-runner-toolkit</artifactId>
        <version>${qits.runner.version}</version>
        <exclusions><exclusion><groupId>*</groupId><artifactId>*</artifactId></exclusion></exclusions>
    </dependency>
    <dependency>
        <groupId>eu.wohlben.qits</groupId>
        <artifactId>qits-runner-protocol</artifactId>
        <version>${qits.runner.version}</version>
        <exclusions><exclusion><groupId>*</groupId><artifactId>*</artifactId></exclusion></exclusions>
    </dependency>

The exclusion on the toolkit keeps Vert.x, the containers driver and jboss-logging out of a Quarkus
service. It also strips the protocol jar, which a server still needs: `RunnerIdentity`'s constructor
takes a `RunnerVocabulary`, and that record lives in the protocol. Pinning the toolkit alone
compiles and then fails at runtime with a `NoClassDefFoundError` the first time an identity is
built. What a server may reach is `eu.wohlben.qits.runner.toolkit.install.InstallScript` and
`RunnerIdentity`, and `InstallPackageIsDependencyFreeTest` checks that their whole class closure
refers to nothing but `java.*`, each other and the protocol.

## The embedder's contract

An embedder writes four things and is handed the rest:

| The embedder writes | The toolkit gives |
|---|---|
| one `RunnerIdentity` (twelve values: kind, env prefix, label root, name prefix, image repository, default state dir, register path, service name, version, capability version, vocabulary, work-label suffix) | every label, container, volume and variable name derived from it, and the constructor belts that refuse a shared one |
| a sealed `XxxMessage extends RunnerMessage`, its `DomainCodec`, a `RunnerVocabulary` | `RunnerCodec`: the ten lifecycle frames, close 1008 `RUNNER_DELETED`, `WireMaps`, `RunnerBinaryVersion` |
| a `RunnerDomain<D>` | the `RunnerRuntime` loop and a `RunnerSession`: `send`, `taken`/`released`, `held`, `quarantined`, `driver()`, `own()`, `registry()`, `bearer()`, `stateDir()` |
| a `main` calling `RunnerLauncher.main` | `health`, `<prefix>PRINT_VERSION`, the decommission helper mode, settings parsed from the environment, the exit codes |

Never provided: anything on the server's side — its runner registry, socket endpoint, quarantine
state machine, entity, migrations. Each service keeps its own.

### The worked example: the harness runner

`runner-harness/src/main/java/eu/wohlben/qits/runner/harness/` is a whole runner in five classes,
and exactly the shape a workspaces runner or `qits-projects-desk-runner-daemon` fills in.

**1. The identity** (`HarnessIdentity`). In a real runner these are constants in its own protocol
jar, so the daemon and its server build the same value:

    public static final String VERSION =
        RunnerBinaryVersion.read(HarnessIdentity.class, "harness-binary.properties");

    public static final RunnerIdentity IDENTITY =
        new RunnerIdentity(
            "harness",                                // kind
            "QITS_HARNESS_RUNNER_",                   // env prefix
            "qits.harness.runner",                    // label root
            "qits-harness-runner",                    // name prefix
            "qits/qits-harness-runner",               // image repository
            "/var/lib/qits-harness-runner",           // default state dir
            "/harness/api/runners/{id}/register",     // register door
            "qits-harness-runner",                    // telemetry service name
            VERSION,                                  // the image tag
            1,                                        // capability version
            new RunnerVocabulary("heldItems", "adoptedItems"),
            "item");                                  // work-label suffix

The version is a `${project.version}`-filtered resource read through `RunnerBinaryVersion`, which
refuses a missing or unfiltered value: the jar's version is the image tag, so a server's pin of the
protocol jar is the pin of the image it hands out.

The constructor refuses a label root at or under `qits.containers` (qits-containers-service's volume
collection would take the runner's volumes) and a name prefix starting with `qits-containers` or
`qits-buildkitd` (another tenant of the node owns those). A new kind also joins
`KnownRunnerNamespaces` in the commit that introduces it; a test refuses any root that is a prefix
of another.

**2. The frames** (`HarnessMessage`, `HarnessCodec`). A sealed set of its own, extending the
base's non-sealed `RunnerMessage` — a `permits` list cannot span artifacts, so the codec is handed
in, not inherited:

    public sealed interface HarnessMessage extends RunnerMessage
        permits Take, Started, Release, Released { … }
    // host → runner: take{itemId, image}, release{itemId}
    // runner → host: started{itemId, containerId}, released{itemId}

The codec reads every field through `WireMaps`, so a domain frame tolerates what a lifecycle frame
does, and throws on a missing field; `RunnerCodec` reports that as `MALFORMED`. A domain `type`
equal to a lifecycle type is refused when the `RunnerCodec` is built. `take` and `released` exist in
more than one runner with different payloads; that is fine, because each socket has exactly one
domain codec.

**3. The domain** (`HarnessDomain implements RunnerDomain<HarnessMessage>`), built from the parsed
`RunnerSettings` (its constructor is the factory `RunnerLauncher` calls):

- `onMessage(take)`: `session.taken(itemId)`, then `session.registry().pull(image)` — a pull under
  the runner's own client pair in a throwaway `docker --config`, never the host's docker config —
  then `session.driver().run(spec, identity.nodeName(itemId, "item"), session.own().labels(itemId),
  …)`, then `session.send(new Started(…))`. The labels go in the driver's own-namespace `labels`
  argument, never `ContainerSpec.extraLabels`: the work-item key sits inside `<labelRoot>.`, and the
  driver refuses an extra label there. The derived name must also pass the driver's own
  container-name rule (a DNS label), so the harness refuses an item id that cannot name a container
  before it takes the slot.
- `onMessage(release)`: remove that container, `session.released(itemId)`, answer `released`.
- `quiescent()`: no take or release half done. This is the domain's call, and the important one: CI
  answers "no held run"; a runner whose containers outlive its process answers "no operation in
  flight", because draining until it holds nothing would never finish.
- `onSessionStart(session, carried)`: `session.own().reconcile(Set.copyOf(carried))` — the keep set
  is the domain's decision (CI: the carried runs; workspaces: the server's desired list). The toolkit
  never sweeps a workload the domain has not released.
- `onDropped(items)`: the carried items the host's `Ack` did not adopt; their containers are the
  domain's to remove.
- `onDecommission(session)`: stop the held items' containers; the helper removes them afterwards.
- `housekeeping()`: one `HousekeepingPass` (it keeps the scratch volume on the node). A pass may
  touch only what carries the runner's labels or names; never `system prune`, `image prune -a`,
  `volume prune` or `container prune`.
- `teardown()`: one `TeardownItem` — `VOLUME qits-harness-runner-scratch-<id8>`. It carries no
  runner label, so only the item reaches it at decommission. An item whose name does not start with
  `<namePrefix>-` is refused, which is what makes "remove the node's buildkitd" impossible to write
  for a runner that does not own it.

**4. The entry** (`HarnessMain`):

    public static void main(String[] args) {
      Vertx vertx = Vertx.vertx();
      int code;
      try {
        code = RunnerLauncher.main(HarnessIdentity.IDENTITY, HarnessDomain::new, vertx, args);
      } finally {
        vertx.close();
      }
      System.exit(code);
    }

A Quarkus command-mode embedder makes the same call from its one `@QuarkusMain` with the managed
`Vertx`.

**5. The suite** (`HarnessRunnerTest`) uses only the toolkit's public API and its
`eu.wohlben.qits.runner.toolkit.testing` package, which ships in the main jar so an embedder's
suite gets the same fixtures:

- `FakeRunnerHost(vertx, identity, codec)` — a real in-JVM Vert.x server with the identity's
  register door, the token endpoint (it answers 401 to any `Authorization` header, as the live edge
  does), the control socket and the log collector, each scriptable (`script`, `send`, `await`,
  `closeSocket`, `tokenStatus`, …).
- `FakeDocker(dir)` — a shell script the production process seam forks as docker. It records every
  argv, answers from files (`answer`, `answerFor`, `hang`) or from a small state model of labelled
  containers and volumes (`containers()`, `volumes()`), and gives two tenants of one node a binary
  each (`binaryFor`, `callsBy`).
- `new RunnerRuntime<>(vertx, identity, settings, domain, host, Telemetry.off(),
  new RunnerRuntime.NodeOptions(Optional.of(selfId), selfUpdateSettings, decommissionSettings,
  housekeepingSettings, clock))` — the runtime with a container the suite names and the node's
  clocks shrunk; `RunnerSettings.withTiming` shrinks the socket's. Production uses
  `NodeOptions.defaults`, through `RunnerLauncher`.

Its ten cases are the whole life: register-once and a new token, client_secret_post, `Hello`/`Ack`,
backlog/reserve/take/release, a lost socket and adoption, an upgrade waiting on quiescence, a
deleted `Retire` and the helper's removal order, 1008 `RUNNER_DELETED` and the `invalid_client`
streak, `health`, and the version probe. A copy of it is the right start for a new runner's suite.

### The order the runtime calls the domain

Per session: `onSessionStart(session, carried)` (off the event loop, before the `Hello`);
`capabilities(host)` and `helloExtensions()` go out in the `Hello`; at the `Ack`, the toolkit
settles the carried items, `onDropped(notAdopted)`, then counts the slots and calls `onAck`; every
domain frame to `onMessage`, on a cached worker pool; `onSessionLost()` when the socket drops (held
items become carried). On an `Upgrade`: drain, pull, digest check, poll `quiescent()`, start the
successor, and leave on its `Retire{SUPERSEDED}`. On `Retire{DELETED}`, a 1008 `RUNNER_DELETED`
close or a believed `invalid_client` streak: `onDecommission(session)` once, then `client.json` is
deleted, the restart policy taken away, the helper started, exit 0.

### Environment, health and exit codes

Every variable is `<envPrefix><NAME>`: `URL`, `ID` (both required), `REGISTRATION_TOKEN` (needed
until there is a `client.json`, and again to rotate it), `STATE_DIR`, `SLOTS`, `DOCKER_BINARY`,
`DOCKER_TIMEOUT` (120s default; every docker call but a pull), `PULL_TIMEOUT` (1800s default; a
pull's own wider budget — a cold pull of a large image routinely outlives `DOCKER_TIMEOUT` without
docker itself being stuck), `ROLLOVER_TIMEOUT`, `SELF_UPDATE`, `TELEMETRY_URL`; `PRINT_VERSION=1`
prints the version and exits 0; `DECOMMISSION=<id>` (with `DECOMMISSION_VOLUME` and
`DECOMMISSION_TEARDOWN`) is helper mode. `<binary> health` exits 0 while the heartbeat file is
younger than 60 s and 1 before the first `Ack`. Exit codes: 0 done, 2 misconfigured, 3 registration
unreachable, 4 capability mismatch, 5 registration refused, 6 state unusable.

## Health checks

A host asks a runner for a health check and the runner answers with every named check's outcome.
The machinery is lifted from qits-workspaces-runner-daemon so every runner kind shares it — the CI
runner included, which is not on `RunnerRuntime` yet. It lives in two packages:

- `eu.wohlben.qits.runner.protocol.health` — `CheckResult(name, ok, detail, data)`,
  `HealthReport(ok, detail, requestId, checks)` and `HealthWire`: the `Type`/`Field` constants and
  `encodeCheck(requestId)`, `encodeChecked(report)`, `decodeCheck(map)`, `decodeChecked(map)`. The
  wire is byte-identical to the workspaces codec's: `type` first, then the fields;
  `requestId` and `checks` are left off when absent.
- `eu.wohlben.qits.runner.toolkit.health` — `RunnerHealthCheck` (`name()`, an optional
  `deadline()`, `run(HealthContext)`), the `HealthChecks` registry, `HealthContext`, `SessionFacts`
  and `DefaultHealthChecks`.

`HealthChecks` runs every check in registration order, each on its own thread under its own
deadline (or the registry's default). A check that throws, hangs or answers nothing fails by
itself, and every other check is still run and reported. Names are unique.
`HealthChecks.answer(requestId, outcomes)` is ok only when every check is. Its detail reads `all N
checks passed`, or else `<name>: <detail>` for each failure, joined with `; `.

**The defaults**, each a factory so an embedder puts it where its own wire order needs it:

| Check | Data | Ok when |
|---|---|---|
| `DefaultHealthChecks.docker()` | `{serverVersion}` | `docker version` answers a server version |
| `DefaultHealthChecks.nodeInventory(identity, runnerId, runnerVersion, absent)` | `{containers, volumes, runnerContainer}` | every listing and inspect of the runner's own labelled objects was answered |
| `DefaultHealthChecks.session()` | `{connected, connectedSince, slots, held}` | connected |

All three are read-only. `absent` is docker's wording for an object that went between the listing
and its inspect. Pass null for `DefaultHealthChecks::dockerSaysAbsent`.
`HealthChecks.withDefaults(defaultDeadline, new NodeFacts(identity, runnerId, version))` registers
the three in that order, for an embedder that has no order of its own to keep. The workspaces
runner does have one (docker, workspaceImage, selfTest, nodeInventory, login, session,
workspaceMemory), so it registers each check where it belongs.

**The context.** A check reads `ctx.docker()` (a `DockerCommand`), `ctx.session()` and
`ctx.lookup(Type.class)`. You build it in one of two ways:

- `HealthContext.of(dockerCommand, factsSupplier, Map.of(Type.class, service, …))`, for any
  embedder. A docker client that is not a `RunnerDocker` adapts with
  `DockerCommand.of(binary, deadline, (argv, d) -> …)`.
- `HealthContext.of(session, runnerDocker, factsSupplier)`, for an embedder on `RunnerRuntime`.
  `held` then comes from the session's slot ledger, and `lookup(RunnerSession.class)` returns the
  session.

**Adding a check.** Plainly:

    checks.register(RunnerHealthCheck.named("scratch", null, ctx -> Result.ok("…", Map.of(…))));

In a Quarkus embedder the checks can be ArC beans that the entry point collects:

    @Singleton class ScratchCheck implements RunnerHealthCheck { … }

    @Inject @All List<RunnerHealthCheck> extra;   // in Main
    checks.registerAll(extra);

ArC resolves `@All` at build time, so the native image needs no reflection for it. The toolkit
itself stays free of CDI. `runner-harness`'s `HarnessHealthChecksTest` is the worked example.

**`healthCheck`/`healthChecked` stay domain frames.** They are not lifecycle frames, for two
reasons. qits-projects-desk-runner-daemon already uses both type strings with a different payload.
And a record shipped here cannot appear in an embedder's sealed `permits` list. So each embedder
keeps its own thin frame records and has its `DomainCodec` arms delegate to `HealthWire`:

    case HealthChecked m -> HealthWire.encodeChecked(new HealthReport(m.ok(), m.detail(), m.requestId(), m.checks()));
    case Type.HEALTH_CHECKED -> fromReport(HealthWire.decodeChecked(map));

## Derived names

For runner id `<id>`, `<id8>` its first eight characters. Nothing else exists: the toolkit lists
and removes only by an exact `label=` filter or a name it derived, never by `name=` (docker matches
that as a substring).

| What | Derived as | Harness | Workspaces (qits-624) | CI (qits-772, today's spelling) |
|---|---|---|---|---|
| ownership label (workloads, volumes) | `<labelRoot>=<id>` | `qits.harness.runner` | `qits.workspaces.runner` | `qits.ci.runner` |
| work-item label | `<labelRoot>.<workLabelSuffix>=<work id>` | `qits.harness.runner.item` | `qits.workspaces.runner.workspace` | `qits.ci.runner.run` |
| runner process label | `<labelRoot>.process=<id>` | `qits.harness.runner.process` | `qits.workspaces.runner.process` | `qits.ci.runner.process` |
| version label | `<labelRoot>.version=<v>` | `qits.harness.runner.version` | `qits.workspaces.runner.version` | `qits.ci.runner.version` |
| helper label | `<labelRoot>.decommission=<id>` | `qits.harness.runner.decommission` | `qits.workspaces.runner.decommission` | `qits.ci.runner.decommission` |
| self-update capability label | `<labelRoot>.self-update` | `qits.harness.runner.self-update` | `qits.workspaces.runner.self-update` | `qits.ci.runner.self-update` |
| driver label namespace | `<labelRoot>.` | `qits.harness.runner.` | `qits.workspaces.runner.` | `qits.ci.runner.` |
| runner container | `<namePrefix>-<id8>-<version>` | `qits-harness-runner-<id8>-<v>` | `qits-workspaces-runner-<id8>-<v>` | `qits-ci-runner-<id8>-<v>` |
| helper container | `<namePrefix>-<id8>-decommission` | `qits-harness-runner-<id8>-decommission` | `qits-workspaces-runner-<id8>-decommission` | `qits-ci-runner-<id8>-decommission` |
| state volume | `<namePrefix>-state-<id8>` | `qits-harness-runner-state-<id8>` | `qits-workspaces-runner-state-<id8>` | `qits-ci-runner-state-<id8>` |
| a per-node volume (`nodeVolume(id, purpose)`) | `<namePrefix>-<purpose>-<id8>` | `qits-harness-runner-scratch-<id8>` | `qits-workspaces-runner-dot-claude-<id8>` | — |
| any other named resource (`nodeName(id, purpose)`) | `<namePrefix>-<purpose>-<id8>` | `qits-harness-runner-item-<item8>` | — | its builder, if qits-772 renames it |
| environment | `<envPrefix><NAME>` | `QITS_HARNESS_RUNNER_URL`, … | `QITS_WORKSPACES_RUNNER_URL`, … | `QITS_CI_RUNNER_URL`, … |
| state dir in the container | `defaultStateDir` | `/var/lib/qits-harness-runner` | `/var/lib/qits-workspaces-runner` | `/var/lib/qits-ci-runner` |
| image | `<registry>/<imageRepository>:<version>` | `qits/qits-harness-runner` | `qits/qits-workspaces-runner` | `qits/qits-ci-runner` |
| log bucket | `_service/<serviceName>` | `_service/qits-harness-runner` | `_service/qits-workspaces-runner` | `_service/qits-ci-runner` |

Node-wide commands are limited to `image prune -f` (dangling only) and `image rm` without `-f`,
both safe beside a neighbour because docker refuses to remove an image a container still uses.

Known residue for qits-772: CI's builder volume is spelled `qits-buildkitd-state`, without the
prefix, so the CI embedder cannot express it as a teardown item; qits-772 renames or migrates it.

## Rendering the install script (a server)

A runner is installed by one pasted line: it downloads a script the server renders and pipes it
into `sudo env <prefix>URL=… <prefix>ID=… <prefix>REGISTRATION_TOKEN=… <prefix>SLOTS=… sh`. The
template ships in the toolkit (`install/runner-install.sh.tmpl`); the server owns the door
(`GET <service>/api/runners/install.sh`), its roles, its addresses and its version pin:

    InstallScript.requireCarriable(token);                       // straight after minting it
    String script = InstallScript.script(
        WorkspacesRunnerIdentity.IDENTITY,                       // the runner protocol jar's
        new InstallScript.InstallPage("the Workspaces UI's Runners page", List.of()),
        registryHost);                                           // registry.qits.<domain>
    String line = InstallScript.line(
        WorkspacesRunnerIdentity.IDENTITY, serviceBase, "/workspaces/api/runners/install.sh",
        runnerId, token, slots);

The script logs in with the token in a throwaway docker config, pulls, removes the containers
labelled `<labelRoot>.process=<id>`, keeps the state volume but deletes `client.json` from it (the
rotation path), and starts the runner on the default bridge — never `qits-net`. Its `docker run`
and the toolkit's successor argv (`LifecycleArgv.runSuccessor`) are one contract, asserted together
by `InstallScriptContractTest`, so they cannot drift.

## Rules for changing the protocol

**The frozen frames.** `Upgrade{version, image, sha256}` and `Retire{reason, kind}` are what every
installed runner of every kind, at any age, must still read: they are how a host replaces or
removes a runner it can no longer otherwise talk to. A change to either is additive only — a new
optional field, never a renamed or retyped one, and never a capability bump. `Hello.runnerVersion`
is frozen for the same reason: it is how a host recognises a runner it must upgrade. A capability
bump is each runner's own `RunnerIdentity.capabilityVersion`, never a base constant.

**Adding a lifecycle frame or field:**

1. A record in `runner-protocol`, added to `LifecycleMessage`'s `permits` list.
2. `RunnerWire.Type`/`Field` constants — never a bare string at a call site.
3. Encode and decode arms in `RunnerCodec`, and the round-trip list in `RunnerCodecTest` (the
   "every permitted subclass is covered" test fails until all of them exist).
4. A frame CI also speaks keeps CI's byte-identical spelling and key order:
   `CiWireCompatibilityTest` encodes through both this codec and `qits-ci-runner-protocol`
   `2026.1003.50846` and compares.
5. Handle it in `RunnerRuntime` (lifecycle frames never reach `RunnerDomain.onMessage`).
6. Release this repository, then bump the dependency in each embedder and handle the new case
   there.

**qits-772 depends on two tests.** Moving qits-ci-runner-daemon onto this library is safe only
while `CiWireCompatibilityTest` (runner-protocol: the base frames are byte-identical to CI's) and
`RunnerIdentityTest.theCiShapedIdentityDerivesTodaysCiSpellingsExactly` (runner-toolkit: a CI-shaped
identity derives every label, name and variable today's CI runner uses) stay green. Neither may be
weakened or deleted to make a change pass; a red one means installed CI runners would stop being
recognised by their own host or would lose track of their own containers.

## Building

    flock /tmp/qits-maven.lock ./mvnw -B -ntp -s .qits-maven-settings.xml verify

A clone alone builds green: no docker, no network beyond the maven mirror, no credentials. The
release request's QA gate (`maven-library`) runs the same `verify`, every module's suite included.
See `AGENTS.md` for the working rules.
