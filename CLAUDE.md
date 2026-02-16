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
| `andersoni-core` | Engine, DSL, Snapshot, core interfaces (zero dependencies) |
| `andersoni-sync-kafka` | Kafka broadcast sync |
| `andersoni-sync-http` | HTTP peer-to-peer sync |
| `andersoni-sync-db` | JDBC polling sync |
| `andersoni-leader-k8s` | K8s Lease leader election |
| `andersoni-snapshot-s3` | S3 snapshot persistence |
| `andersoni-snapshot-fs` | Filesystem snapshot (dev/test) |
| `andersoni-spring-boot-starter` | Spring Boot auto-configuration |
| `andersoni-admin` | K8s admin console (Spring Boot app) |
| `andersoni-example` | Full-stack demo app (standalone, not in parent modules) |

## Package Convention

Root package: `org.waabox.andersoni`

```
org.waabox.andersoni              # Core: Andersoni, Catalog, Snapshot, IndexDefinition
org.waabox.andersoni.sync         # SyncStrategy, RefreshEvent
org.waabox.andersoni.snapshot     # SnapshotStore, SnapshotSerializer
org.waabox.andersoni.leader       # LeaderElectionStrategy
org.waabox.andersoni.metrics      # AndersoniMetrics
org.waabox.andersoni.admin        # Admin console
org.waabox.andersoni.example      # Example app
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
- **Builder/Fluent DSL**: `Catalog.of(T).named("x").loadWith(loader).index("y").by(fn).build()`
- **Retry with backoff**: `RetryPolicy` for bootstrap and refresh failures

## CI/CD

- **ci.yml**: runs `mvn clean verify` on push to main and PRs
- **publish.yml**: triggered on GitHub release → sets version from tag → `mvn deploy -Prelease` to Maven Central

## Git Conventions

- Clear, professional commit messages (what + why)
- Never add Co-Authored-By
- Tag format: `v{major}.{minor}.{patch}` (e.g., `v1.0.2`)
- GitHub release triggers Maven Central publication automatically
