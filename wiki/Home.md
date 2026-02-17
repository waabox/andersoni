# Andersoni

**In-memory indexed cache for Java 21 — lock-free reads, multi-index search, pluggable cross-node sync.**

Andersoni holds entire datasets in memory as immutable snapshots. Readers never block. Writes atomically swap the snapshot. Multiple indices let you query the same data by different keys — all at nanosecond latency.

## Performance

Benchmarked on Apple M-series, JDK 21, 8 threads, 3 indices:

| Items | Build Time | Avg Search Latency | Concurrent Throughput | Memory |
|------:|----------:|---------:|---------:|-------:|
| 10,000 | 9 ms | ~55 ns | ~10 M ops/s | ~2 MB |
| 100,000 | 38 ms | ~29 ns | ~85 M ops/s | ~14 MB |
| 500,000 | 112 ms | ~28 ns | ~93 M ops/s | ~70 MB |

Search latency is effectively a `HashMap.get()` on an immutable snapshot — no locks, no synchronization, no copying.

## When to Use Andersoni

- Read-heavy reference data queried by multiple criteria
- Product catalogs, event listings, pricing tables, sports fixtures
- Configuration data that changes infrequently (minutes/hours, not seconds)
- Datasets that fit in memory (thousands to low millions of items)
- Systems where cross-index consistency matters

## When NOT to Use Andersoni

- Need per-entry TTL or size-bounded eviction — use [Caffeine](https://github.com/ben-manes/caffeine)
- Data changes every few seconds — full snapshot rebuilds are wasteful
- Already have Redis/Hazelcast and it works
- Need strong consistency across nodes
- Dataset is very large (tens of millions+)

## Comparison

| Aspect | Caffeine | Redis / Hazelcast | Spring Cache | **Andersoni** |
|--------|----------|-------------------|--------------|---------------|
| Model | key → value | mutable shared state | cache method results | **immutable snapshot** |
| Multi-index | No | No | No | **Yes** |
| Read latency | ~1 μs | ~0.5–1 ms | per backend | **~30 ns** |
| Consistency | manual | strong | per method/key | **snapshot-level** |
| Cross-node sync | No | built-in | No | **pluggable** |

## Modules

| Module | Purpose |
|--------|---------|
| `andersoni-core` | Engine, DSL, Snapshot, core interfaces (zero dependencies) |
| `andersoni-sync-kafka` | Kafka broadcast sync |
| `andersoni-sync-http` | HTTP peer-to-peer sync |
| `andersoni-sync-db` | JDBC polling sync |
| `andersoni-leader-k8s` | Kubernetes Lease leader election |
| `andersoni-snapshot-s3` | S3 snapshot persistence |
| `andersoni-snapshot-fs` | Filesystem snapshot (dev/test) |
| `andersoni-spring-boot-starter` | Spring Boot auto-configuration |
| `andersoni-admin` | Kubernetes admin console |

## Maven Central

```xml
<dependency>
  <groupId>io.github.waabox</groupId>
  <artifactId>andersoni-core</artifactId>
  <version>1.1.2</version>
</dependency>
```

## Wiki Contents

1. [[Getting Started]]
2. [[Core Concepts]]
3. [[The Catalog DSL]]
4. [[Spring Boot Integration]]
5. [[Sync Strategies]]
6. [[Snapshot Persistence]]
7. [[Leader Election]]
8. [[Observability]]
9. [[DevOps & Kubernetes]]
10. [[Deployment Guide]]
11. [[FAQ & Troubleshooting]]
