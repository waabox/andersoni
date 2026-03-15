# andersoni-metrics-datadog — Design Spec

## Context

Andersoni is an in-memory indexed cache library for Java 21. It has a pluggable `AndersoniMetrics` interface for monitoring, with a no-op default. Users running Andersoni in Kubernetes with Datadog need visibility into catalog memory consumption, item counts, index sizes, and operational events (refreshes, failures).

## Goal

Create a new module `andersoni-metrics-datadog` that implements `AndersoniMetrics` using the DogStatsD protocol (`java-dogstatsd-client`) to report custom metrics to Datadog. The module must support K8s autodiscovery (zero-config) and custom configuration.

## Core Change: Lifecycle Methods on AndersoniMetrics

The existing `AndersoniMetrics` interface is a passive callback — Andersoni calls it on events. For gauge polling (periodic reporting of catalog state), the metrics implementation needs active access to catalogs.

**Add to `AndersoniMetrics`:**

```java
default void start(Collection<Catalog<?>> catalogs, String nodeId) {}
default void stop() {}
```

**Invocation points in `Andersoni`:**

- `start()` (line 208): call `metrics.start(catalogsByName.values(), nodeId)` **after** `schedulePeriodicRefreshes()` (line 216), as the very last operation before the method returns. This ensures all catalogs are bootstrapped, sync is wired, and refreshes are scheduled before metrics polling begins.
- `stop()` (line 507): call `metrics.stop()` **as the first operation** (after the `compareAndSet` guard at line 508), before `cancelScheduledRefreshes()`. This ensures the gauge polling thread finishes cleanly before catalogs are potentially invalidated.

`NoopAndersoniMetrics` inherits the default empty implementations — no change needed.

**Backward compatibility note:** This is a behavioral contract evolution. Existing custom `AndersoniMetrics` implementations will compile without changes (default methods), but will silently receive no-op lifecycle behavior. This is safe — lifecycle is opt-in. Document as a minor behavioral change in release notes.

## Module Structure

```
andersoni-metrics-datadog/
├── pom.xml
└── src/
    ├── main/java/org/waabox/andersoni/metrics/datadog/
    │   ├── DatadogAndersoniMetrics.java
    │   └── DatadogMetricsConfig.java
    └── test/java/org/waabox/andersoni/metrics/datadog/
        └── DatadogAndersoniMetricsTest.java
```

**Package:** `org.waabox.andersoni.metrics.datadog`

## Dependencies

| Dependency | Scope | Purpose |
|------------|-------|---------|
| `andersoni-core` | compile | `AndersoniMetrics`, `Catalog`, `Snapshot` |
| `com.datadoghq:java-dogstatsd-client` | compile | DogStatsD client |
| `org.slf4j:slf4j-api` | compile | Logging (error handling in gauge polling) |
| `junit-jupiter` | test | Testing |
| `easymock` | test | Mocking |
| `slf4j-simple` | test | Test logging |

## Construction API

Three static factory methods, ordered by complexity:

```java
// 1. K8s autodiscovery — zero config
//    Host from DD_AGENT_HOST / DD_DOGSTATSD_URL, port 8125, 30s polling
DatadogAndersoniMetrics.create();

// 2. Custom configuration
DatadogAndersoniMetrics.create(DatadogMetricsConfig.builder()
    .host("custom-host")
    .port(9125)
    .prefix("myapp.andersoni")
    .constantTags("service:my-app", "env:staging")
    .pollingInterval(Duration.ofSeconds(60))
    .build());

// 3. User-provided StatsDClient (module does NOT close it on stop)
DatadogAndersoniMetrics.create(statsDClient);
```

## DatadogMetricsConfig

Immutable config with builder pattern. Defaults exposed as `public static final` constants.

| Field | Type | Default | Constant | Description |
|-------|------|---------|----------|-------------|
| `host` | `String` | `null` (autodiscovery via `DD_AGENT_HOST`) | — | DogStatsD Agent host |
| `port` | `int` | `8125` | `DEFAULT_PORT` | DogStatsD Agent port |
| `prefix` | `String` | `"andersoni"` | `DEFAULT_PREFIX` | Metric name prefix |
| `constantTags` | `String[]` | `{}` | — | Tags added to every metric |
| `pollingInterval` | `Duration` | `30s` | `DEFAULT_POLLING_INTERVAL` | Gauge reporting interval |

**K8s autodiscovery behavior:** When `host` is null, `java-dogstatsd-client` resolves the Agent address from `DD_DOGSTATSD_URL` or `DD_AGENT_HOST` environment variables. If neither is set, it falls back to `localhost:8125`. This means the zero-config `create()` factory will not fail at construction — it will silently send UDP packets to localhost, which may be unreachable if no Agent is running there. This is acceptable for DogStatsD (fire-and-forget UDP).

## Metrics

### Gauges (reported every polling interval)

| Metric | Tags | Description |
|--------|------|-------------|
| `andersoni.catalog.items` | `catalog`, `node` | Number of items in the current snapshot |
| `andersoni.catalog.memory.bytes` | `catalog`, `node` | Estimated total memory of the catalog (`CatalogInfo.totalEstimatedSizeBytes()`) |
| `andersoni.catalog.version` | `catalog`, `node` | Current snapshot version number |
| `andersoni.index.memory.bytes` | `catalog`, `index`, `node` | Estimated memory per index |
| `andersoni.index.keys` | `catalog`, `index`, `node` | Number of unique keys per index |

**Memory estimation caveat:** `Snapshot.indexInfo()` currently estimates memory for equality-based indices only. Sorted indices and reversed-key indices are not included. The `catalog.memory.bytes` gauge reflects this partial estimation. A follow-up enhancement to `Snapshot.indexInfo()` can extend coverage to all index types.

### Counters (reported on event)

| Metric | Tags | Description |
|--------|------|-------------|
| `andersoni.catalog.snapshot.loaded` | `catalog`, `node`, `source` | Incremented on each successful snapshot load |
| `andersoni.catalog.refresh.failed` | `catalog`, `node` | Incremented on each refresh failure |

### `indexSizeReported` callback

The `indexSizeReported(catalogName, indexName, estimatedSizeBytes)` callback also records the `estimatedSizeBytes` value as a gauge (`andersoni.index.memory.bytes`) immediately, providing an event-driven update in addition to the periodic polling. This ensures index memory data is captured even between polling intervals. The polling serves as a continuous heartbeat to keep gauges alive in Datadog.

### Tag values

- `catalog`: catalog name from `Catalog.name()`
- `index`: index name from `IndexInfo.name()`
- `node`: nodeId passed via `start(catalogs, nodeId)`
- `source`: snapshot source (e.g., "s3", "filesystem", "dataLoader")

## DatadogAndersoniMetrics — Behavior

### Lifecycle

- **`start(catalogs, nodeId)`**: stores catalog references and nodeId, creates the `StatsDClient` (if not user-provided), starts `ScheduledExecutorService` with 1 daemon thread named `andersoni-metrics-datadog-<nodeId>`, schedules gauge polling at the configured interval.
- **`stop()`**: shuts down the scheduler (5s graceful timeout), closes the `StatsDClient` only if the module created it.

### Event callbacks (from AndersoniMetrics interface)

- **`snapshotLoaded(catalogName, source)`**: increments `andersoni.catalog.snapshot.loaded` counter with `catalog` and `source` tags.
- **`refreshFailed(catalogName, cause)`**: increments `andersoni.catalog.refresh.failed` counter with `catalog` tag.
- **`indexSizeReported(catalogName, indexName, estimatedSizeBytes)`**: records `andersoni.index.memory.bytes` gauge with the reported value, providing event-driven updates between polling intervals.

### Gauge polling

Every polling interval, iterates all catalogs using `Catalog.info()` (which returns `CatalogInfo`):

1. Call `catalog.info()` — returns `CatalogInfo` with itemCount, indices, and totalEstimatedSizeBytes
2. Report `catalog.items` = `catalogInfo.itemCount()`
3. Report `catalog.memory.bytes` = `catalogInfo.totalEstimatedSizeBytes()`
4. Report `catalog.version` = `catalog.currentSnapshot().version()`
5. Iterate `catalogInfo.indices()`:
   - Report `index.memory.bytes` = `indexInfo.estimatedSizeBytes()`
   - Report `index.keys` = `indexInfo.uniqueKeys()`

### Error handling

- Gauge polling catches and logs exceptions per catalog (one catalog failing doesn't skip the rest).
- StatsDClient errors are handled by the client itself (fire-and-forget UDP).

### Thread safety

- Catalog snapshots are read via `AtomicReference` — lock-free.
- The scheduler runs a single thread — no concurrent gauge reporting.
- Event callbacks (counters) are thread-safe via `StatsDClient` (which is thread-safe).

## Constraints

- Zero mandatory configuration for K8s deployments with Datadog Agent DaemonSet.
- The module must not close a user-provided `StatsDClient`.
- All collections and parameters are immutable/final per project conventions.
- JavaDoc on all public methods.

## Rejected Alternatives

- **Micrometer registry**: adds an abstraction layer. If needed, it will be a separate module (`andersoni-metrics-micrometer`).
- **Reporting gauges only on refresh events**: Datadog gauges go stale if not pushed periodically. Polling ensures continuous visibility.
- **Sharing Andersoni's internal scheduler**: would require exposing internal state from core. A dedicated daemon thread is lightweight and self-contained.

## Open Questions

None — all design decisions resolved during brainstorming.
