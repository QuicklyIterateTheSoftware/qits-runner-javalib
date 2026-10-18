# qits-runner-javalib — working notes

Read `README.md` first: it is the embedder's contract. These are the rules that bite when you
change either jar. It deliberately mirrors qits-ci-runner-daemon's `AGENTS.md`, narrowed to a
library that ships no container of its own; where the two differ, it says why.

## The rules that shape everything

**A clone of this repo alone builds and tests green.** No monorepo, no docker, no prior `mvn
install` elsewhere, no credentials, no network. `./mvnw verify` is the gate. That is why
`runner-protocol` has no dependency at all, and why `runner-toolkit`'s suite and the harness
runner's own suite stand docker in with a script and a fake host rather than needing a daemon or a
socket that is actually root-equivalent.

**This is a library, not a runner.** Nothing here ships as an image or runs as a container by
itself: every artifact is a jar an embedder links — qits-workspaces-runner-daemon, qits-767's
front-desk runner, and later qits-772's own qits-ci-runner-daemon. The harness module proves the
toolkit works by playing embedder itself, inside this repository's own suite, and is never
published.

## Module conventions

`eu.wohlben.qits.runner.{protocol,toolkit,harness}` and `eu.wohlben.qits.runner.toolkit.contracts`,
one package per module, no split packages.

**`runner-toolkit-contracts` is test-scope only, and writes no pact file** (qits-1163). The
toolkit's REST calls (token, log export, register) are pacted by each embedding daemon as consumer,
through `RunnerToolkitPact.VIA`; this module only ships the rows, the drives through the real
`Bearer`/`Telemetry`/`Registration`, and the guard that a daemon declares what its main classes use
(README "Pacting the toolkit's calls"). It may depend on Jackson and JUnit — it never links into a
runner. Never add a `contracts.pacts` entry to `release.yml`, and never call
`compareOrWritePactFile()` from its own suite. A change to what `Bearer`, `Telemetry` or
`Registration` sends is a change to every embedder's pact: keep the drives and rows in step, and
expect each daemon's pact file to move on its next bump.

**`runner-protocol` is framework-free and dependency-free, forever.** Plain records, plain
constructors, no annotations. `maven-enforcer-plugin` fails the build the moment a compile- or
runtime-scope dependency appears; it is not a reviewer's job to catch that. A runner's protocol jar
is the one thing every peer of every kind links, so it must never drag in Quarkus, Vert.x or a JSON
library. Wire fields are read through `Map<String,Object>`/`WireMaps`, never reflection.

**`runner-toolkit` has no CDI, no Quarkus dependency, no Jackson databind.** Enforced the same
way — `jackson-databind`, `io.quarkus:*`, `jakarta.enterprise:*` and `jakarta.inject:*` are banned
dependencies, checked transitively. The embedders are not all Quarkus applications; a workspaces
runner is a static musl binary, and a bean annotation here would either do nothing or drag a
container into every embedder. Configuration arrives through constructors
(`RunnerIdentity`/`RunnerSettings`), never `ConfigProvider`.

**Every docker call goes through the driver's process seam, under a deadline.** `runner-toolkit`
embeds `eu.wohlben.qits:qits-containers-driver`; it never forks `docker` itself. Every argv the
driver builds is a pure function, asserted element by element. `--rm` appears nowhere.

## Testing

Plain JUnit 5. **No Mockito, no `@QuarkusTest`.** Real sockets and a script standing in for docker
are preferred over seams: `FakeRunnerHost` and `FakeDocker`, which ship in the toolkit's main jar
under `eu.wohlben.qits.runner.toolkit.testing` so an embedder's suite uses the same ones.

**The harness suite stays outside the toolkit's package.** `HarnessRunnerTest` may use only the
toolkit's public API and its `testing` package, because that is all qits-624's suite gets. A case
it cannot express that way is a gap in the API, fixed there (public and documented, as
`RunnerRuntime.NodeOptions` was), never reached through a package-private seam.

Test names are sentences describing the behaviour
(`aDeletedRetireAddsItsKindAndAnUnknownKindReadsAsSuperseded`).

## Adding a lifecycle frame or field

1. A record in `runner-protocol`, added to `LifecycleMessage`'s `permits` list.
2. `RunnerWire.Type`/`Field` constants — never a bare string at a call site.
3. Encode and decode arms in `RunnerCodec`, and the round-trip list in the codec test (the
   "every permitted subclass is covered" test fails until all of them exist).
4. A frame CI also speaks keeps CI's byte-identical spelling and key order:
   `CiWireCompatibilityTest` compares against `qits-ci-runner-protocol:2026.1003.50846`.
5. `Upgrade` and `Retire` are **frozen** for every installed runner of every kind: a new field is
   additive and never a capability bump. A capability bump is each runner's own
   `capabilityVersion`, never a base constant.
6. **Release this repository, then bump the dependency in each embedder** and handle the new case
   there. This repo is the protocol's only author; the published jar is what enforces it.

## Untrusted input

Whatever a `RunnerDomain` frame carries and turns into a docker argv is untrusted input from a
server, exactly as qits-ci-runner-daemon's `WorkloadSpec` is:

- **The shape is the boundary.** No free-form argv, no volume, no host path beyond what the
  embedder's own `RunnerIdentity` fixes.
- **Belts at the argv**, checked against charset, before any docker call.
- **A runner's own label namespace and name prefix are never shared with another embedder.**
  `RunnerIdentity.labelRoot`/`namePrefix` must never collide with `qits.containers*`,
  `qits-containers*` or `qits-buildkitd*` — a shared node (qits-772's CI runner beside a workspaces
  runner) makes that collision a live hazard, not a theoretical one.
- **Secrets stay put.** The registration token is read once and never logged; state carrying a
  bearer is created 0600; a registry login — for a self-update pull and for a domain's workload
  pull through `RunnerSession.registry()` alike — is a throwaway `docker --config` deleted in a
  `finally`.

**qits-772 depends on `CiWireCompatibilityTest` and
`RunnerIdentityTest.theCiShapedIdentityDerivesTodaysCiSpellingsExactly`.** Never weaken or delete
either to make a change pass: they are what keeps installed CI runners readable once qits-772 moves
qits-ci-runner-daemon onto this library.

## Formatting

`google-java-format`, 100 columns, two-space indent. Javadoc explains *why* — the tradeoff, the
alternative rejected, the failure it prevents. What the code does is the code's job.

## Building

Every maven invocation on the shared host goes through the lock:

    flock /tmp/qits-maven.lock ./mvnw -B -ntp -s .qits-maven-settings.xml verify
