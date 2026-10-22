# Andersoni

In-memory indexed cache library for Java 21 with lock-free reads via immutable snapshots and pluggable multi-node sync.

## Build & Test

```bash
mvn clean verify              # Full build + tests
mvn clean test                # Tests only
mvn clean install             # Install to local repo
mvn clean deploy -Prelease    # Publish to Maven Central (CI does this on release)
```

Requires Java 21+ and Maven 3.9+.

## Project Structure

Multi-module Maven project. Version managed in parent pom, inherited by all children.

| Module | Purpose |
|--------|---------|
| `andersoni-core` | Engine, DSL, Snapshot, core interfaces (deps: slf4j-api + org.json, used by the sync wire codec) |
| `andersoni-sync-kafka` | Raw Kafka broadcast sync (no Spring dependency) |
| `andersoni-spring-sync-kafka` | Spring Kafka-based sync with auto-configuration |
| `andersoni-sync-http` | HTTP peer-to-peer sync |
| `andersoni-sync-db` | JDBC polling sync |
| `andersoni-leader-k8s` | K8s Lease leader election |
| `andersoni-snapshot-s3` | S3 snapshot persistence |
| `andersoni-snapshot-fs` | Filesystem snapshot (dev/test) |
| `andersoni-json-serializer` | Jackson-based `SnapshotSerializer` implementation |
| `andersoni-spring-boot-starter` | Spring Boot auto-configuration |
| `andersoni-metrics-datadog` | Datadog DogStatsD metrics |
| `andersoni-admin` | K8s admin console (Spring Boot app, not published) |
| `andersoni-cluster-it` | Docker-based multi-node integration test (Testcontainers; not published) |
| `andersoni-example` | Full-stack demo app (standalone, not in parent modules) |

## Package Convention

Maven groupId: `io.github.waabox`
Root Java package: `org.waabox.andersoni`

```
org.waabox.andersoni                    # Core: Andersoni, Catalog, Snapshot, IndexDefinition, ViewDefinition
org.waabox.andersoni.sync               # SyncStrategy, RefreshEvent
org.waabox.andersoni.sync.kafka         # Raw Kafka sync (no Spring)
org.waabox.andersoni.sync.kafka.spring  # Spring Kafka sync with auto-configuration
org.waabox.andersoni.sync.http          # HTTP peer-to-peer sync
org.waabox.andersoni.sync.db            # JDBC polling sync
org.waabox.andersoni.snapshot           # SnapshotStore, SnapshotSerializer
org.waabox.andersoni.snapshot.s3        # S3 snapshot store
org.waabox.andersoni.snapshot.fs        # Filesystem snapshot store
org.waabox.andersoni.leader             # LeaderElectionStrategy
org.waabox.andersoni.leader.k8s         # K8s Lease leader election
org.waabox.andersoni.metrics            # AndersoniMetrics
org.waabox.andersoni.metrics.datadog    # Datadog DogStatsD metrics
org.waabox.andersoni.spring             # Spring Boot auto-configuration
org.waabox.andersoni.admin              # Admin console
org.waabox.andersoni.example            # Example app
```

## Code Style

- **Java 21**: records, sealed classes, pattern matching
- **No Lombok**: explicit constructors and accessors
- **Immutable everything**: all parameters and variables are `final`
- **Defensive programming**: `Objects.requireNonNull()` on public APIs
- **Static factories + private constructors** (Builder pattern)
- **Thread-safe primitives**: `AtomicReference<Snapshot>`, `ConcurrentHashMap`
- **Unmodifiable collections** returned from public APIs
- **JavaDoc** on all public methods with `@author waabox(waabox[at]gmail[dot]com)`

## Testing

- **JUnit 5** + **EasyMock**
- Naming: `whenDoingSomething_givenSomeScenario_shouldDoOrHappenSomething()`
- No given/when/then comments
- Each test creates its own mocks and objects
- Prefer real objects when possible

## Architecture

- **Immutable Snapshot Pattern**: data in `AtomicReference<Snapshot<T>>`, readers never blocked
- **Strategy Pattern**: pluggable `SyncStrategy`, `LeaderElectionStrategy`, `SnapshotStore`
- **Builder/Fluent DSL**: `Catalog.of(T).named("x").loadWith(loader).index("y").by(fn).view(V.class, mapper).build()`
- **Catalog Views**: pre-computed projections of `T` into smaller typed objects `V`, materialized at snapshot build time via `AndersoniCatalogItem<T>` (internal wrapper, never exposed). Query any index with a view class to get `List<V>` instead of `List<T>`
- **Build Hooks**: `SnapshotBuildHook<T>` — per-item hooks during snapshot build with priority ordering. Single-loop build: views → indexation → hooks in one pass. `supportsPatch()` (default `false`) declares a hook safe to run for a single patched item; any hook that has not opted in makes patches rebuild the snapshot fully, since a hook accumulating across a build would otherwise see only the delta
- **Snapshot Patches**: `Catalog.replace/insert/remove/upsert` change a single item via copy-on-write, and `Catalog.apply(List<PatchOp>)` applies several as one atomic swap — only buckets whose keys change are rebuilt, the rest are shared by reference. `Andersoni.replaceAndSync(...)` runs leader-only, saves the snapshot to S3, and publishes a `PatchEvent` (sealed `SyncMessage` hierarchy alongside `RefreshEvent`). Followers apply hash-keyed dispatch (already-applied discard / surgical apply / full-reload fallback); versions are per-node counters and are never preconditions. Patches are queued in arrival order and never coalesced, on a separate path from the coalescing full-refresh dispatcher. Lookup must resolve to exactly one item — `AmbiguousPatchTargetException` otherwise. Kafka and HTTP transports carry patch events; the DB polling transport remains refresh-only. A patch whose encoded payload exceeds `maxPatchPayloadBytes` (default 256KB) is published as a full-refresh event instead, since a message the transport refuses would leave followers silently stale. **Patches are not backward compatible on the wire**: a node on 1.12.2 or earlier cannot decode a patch message (it carries `fromHash`/`toHash`, not the `version`/`hash` the old codec requires) and will go on serving stale data while logging deserialization errors. Upgrade the whole fleet before switching any call site to a patch operation; installing the version alone changes nothing until a patch method is called. With a `SnapshotStore`, patches and reconciliation share the applied store hash: the leader's save records the post-patch hash and a follower's surgical apply records the event's `toHash` (`SnapshotStoreBridge.markApplied`), so a delivered patch is not drift and a missed one is repaired from the store on the next pass; a failed leader save propagates to the caller like `refreshAndSync` and the next pass re-saves and re-publishes; the local `insert/remove/replace` mark the catalog unknown like a local `refresh`
- **Retry with backoff**: `RetryPolicy` for bootstrap and refresh failures
- **Refresh request propagation**: `RefreshEvent` carries a `RefreshKind` (`EVENT` = result, `REQUEST` = command). The authoritative refresh runs **only on the leader**. `refreshAndSync` on a follower publishes a `REQUEST`; **only the leader acts on requests**, followers ignore them, so the propagation graph `REQUEST → EVENT → reload` stays acyclic (no loop). DB polling is a state channel and cannot carry requests — it drops them (follower refresh becomes a no-op there). See `.claude/docs/use-cases/cluster-refresh-request-propagation.md`
- **Snapshot reconciliation (anti-entropy)**: the `SnapshotStore` is the cluster's authoritative state. `SnapshotReconciler` runs every `ReconciliationPolicy.interval()` (default 30s, on by default when a store is configured): followers reload from the store when its hash differs from the hash they last applied (`SnapshotStoreBridge.appliedStoreHash`), the leader re-saves and re-publishes when its applied hash is missing or differs, a promoted leader runs an authoritative refresh of every catalog. Repairs go through `AsyncRefreshDispatcher`. `Andersoni.reconcile()` forces a pass; `status()` exposes `syncState` per catalog. A follower's local `refresh()` is overwritten by the store on the next pass; a leader repair never publishes a `REQUEST`. Use case: `.claude/docs/use-cases/cluster-snapshot-reconciliation.md`. Design: `docs/superpowers/specs/2026-09-11-snapshot-reconciliation-design.md`

## Claude Code Skill

The repo is a Claude Code plugin marketplace (`.claude-plugin/marketplace.json`) shipping the `andersoni` skill in `claude-plugin/skills/andersoni/` (`SKILL.md` + `references/*.md`). Rules:

- Any change to the public API, defaults, Spring properties, module wiring or observable behavior MUST update the affected skill files in the same change. Everything documented there must match the code.
- `claude-plugin/.claude-plugin/plugin.json` `version` MUST equal the pom version; bump both together (CI enforces it).
- Validate with `claude plugin validate .` and `claude plugin validate ./claude-plugin`.
- Use case: `.claude/docs/use-cases/claude-code-skill-distribution.md`.

## CI/CD

- **ci.yml**: runs `mvn clean verify` on push to main and PRs; fails if the plugin version differs from the pom version
- **publish.yml**: triggered on GitHub release → sets version from tag → `mvn deploy -Prelease` to Maven Central

## Git Conventions

- Clear, professional commit messages (what + why)
- Tag format: `v{major}.{minor}.{patch}` (e.g., `v1.0.2`)
- GitHub release triggers Maven Central publication automatically
