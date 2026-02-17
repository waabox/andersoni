# Observability

## AndersoniMetrics

Andersoni provides an `AndersoniMetrics` interface for integrating with your monitoring system (Micrometer, Prometheus, Datadog, etc.).

```java
public interface AndersoniMetrics {

    /** Called when a snapshot is loaded (bootstrap, refresh, or restore). */
    void snapshotLoaded(String catalogName, String source);

    /** Called when a catalog refresh fails. */
    void refreshFailed(String catalogName, Throwable cause);

    /** Called when index size is computed after a refresh. */
    void indexSizeReported(String catalogName, String indexName, long estimatedSizeBytes);
}
```

### Default: NoopAndersoniMetrics

If no `AndersoniMetrics` bean is provided, Andersoni uses a no-op implementation that does nothing. No metrics overhead unless you opt in.

### Example: Micrometer Implementation

```java
@Component
public class MicrometerAndersoniMetrics implements AndersoniMetrics {

    private final MeterRegistry registry;

    public MicrometerAndersoniMetrics(final MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
    }

    @Override
    public void snapshotLoaded(final String catalogName, final String source) {
        registry.counter("andersoni.snapshot.loaded",
            "catalog", catalogName,
            "source", source
        ).increment();
    }

    @Override
    public void refreshFailed(final String catalogName, final Throwable cause) {
        registry.counter("andersoni.refresh.failed",
            "catalog", catalogName,
            "exception", cause.getClass().getSimpleName()
        ).increment();
    }

    @Override
    public void indexSizeReported(final String catalogName,
            final String indexName, final long estimatedSizeBytes) {
        registry.gauge("andersoni.index.size.bytes",
            Tags.of("catalog", catalogName, "index", indexName),
            estimatedSizeBytes
        );
    }
}
```

### Metric Events

| Event | When | Tags |
|-------|------|------|
| `snapshotLoaded` | Bootstrap from snapshot, DataLoader, or restore from sync | `catalogName`, `source` ("snapshot", "dataloader") |
| `refreshFailed` | DataLoader throws during refresh | `catalogName`, `cause` |
| `indexSizeReported` | After every refresh, per index | `catalogName`, `indexName`, `estimatedSizeBytes` |

---

## CatalogInfo & IndexInfo

Use `andersoni.info(catalogName)` to get runtime statistics about a catalog:

```java
CatalogInfo info = andersoni.info("events");

info.catalogName();              // "events"
info.itemCount();                // 50000
info.totalEstimatedSizeBytes();  // 14680064
info.totalEstimatedSizeMB();     // 14.0

for (IndexInfo index : info.indices()) {
    index.name();                // "by-sport"
    index.uniqueKeys();          // 12
    index.totalEntries();        // 50000
    index.estimatedSizeBytes();  // 4893354
    index.estimatedSizeMB();     // 4.67
}
```

### Memory Estimation

The memory estimation accounts for:
- **HashMap overhead**: base size + bucket array + entry nodes
- **ArrayList overhead**: per-list base size + element references
- **String keys**: estimated character count per key

This is a structural estimate — it does not include the actual item object sizes, only the index data structures.

### REST Endpoint Example

Expose catalog info as a health/monitoring endpoint:

```java
@GetMapping("/events/info")
public Map<String, Object> info() {
    final CatalogInfo info = andersoni.info("events");
    final Snapshot<?> snapshot = andersoni.catalogs().stream()
        .filter(c -> c.name().equals("events"))
        .findFirst()
        .map(Catalog::currentSnapshot)
        .orElseThrow();

    return Map.of(
        "catalogName", info.catalogName(),
        "version", snapshot.version(),
        "hash", snapshot.hash(),
        "createdAt", snapshot.createdAt(),
        "itemCount", info.itemCount(),
        "indices", info.indices(),
        "totalEstimatedSizeMB", info.totalEstimatedSizeMB()
    );
}
```

---

## Logging

Andersoni uses SLF4J for all internal logging. Key log messages:

| Level | Message | When |
|-------|---------|------|
| INFO | `Catalog '{name}' bootstrapped from snapshot store` | Snapshot restored |
| INFO | `Catalog '{name}' bootstrapped from data loader` | DataLoader loaded |
| INFO | `Catalog '{name}' refreshed, version {n}, {count} items` | Successful refresh |
| WARN | `Catalog '{name}' bootstrap failed, attempt {n}/{max}` | Retry during bootstrap |
| WARN | `Catalog '{name}' marked as FAILED` | All retries exhausted |
| ERROR | `Catalog '{name}' refresh failed` | DataLoader throws during refresh |
| DEBUG | `Ignoring refresh event for '{name}' (same hash)` | Hash match, skip |
| DEBUG | `Ignoring refresh event for '{name}' (self-originated)` | Self-node event |
