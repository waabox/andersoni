---
name: andersoni
description: Use when a Java project depends on or wants to adopt Andersoni (io.github.waabox, package org.waabox.andersoni) — defining catalogs, regular/sorted/multi/graph indexes, search/range/compound/graph queries, views, build hooks, Spring Boot starter, Kafka/HTTP/DB sync, K8s lease leader election, S3/filesystem snapshots, reconciliation, Datadog metrics — or when diagnosing CatalogNotAvailableException, IndexKeyLimitExceededException, empty graph query results, nodes out of sync, or refreshAndSync having no effect.
---

# Andersoni

In-memory indexed cache for Java 21. A `Catalog<T>` loads a dataset with a `DataLoader`, builds every index into an immutable `Snapshot<T>`, and swaps it atomically: reads are lock-free map lookups, refreshes rebuild the whole snapshot (no per-item updates, no TTL). Multi-node consistency comes from pluggable sync + leader election + an authoritative snapshot store.

**Never guess this API.** Method names are not what you would expect (`indexGraph`, not `graphIndex`; `graphQuery().where(f).eq(v)`, not `search(...).graph(...)`; catalogs are registered through `CatalogRegistrar`, not as `Catalog` beans; there are only three `andersoni.*` Spring properties). Before writing code, open the reference for the area you touch. Version documented: **1.12.0**.

## Reference map

| Task | Read |
|---|---|
| Define a catalog, indexes, views, hooks, `data(List)`, `refreshEvery`, `Snapshot`, `CatalogInfo` | `references/catalog-dsl.md` |
| `search`, `query` (ranges, startsWith/endsWith/contains), `compound` (AND across indexes), result guarantees, errors | `references/querying.md` |
| Graph index: traversals, hotpaths, `CompositeKey`, `graphQuery`, query planner, design recipe | `references/graph-index.md` |
| `Andersoni.builder()`, lifecycle, `refresh` vs `refreshAndSync`, leader/follower, retry, reconciliation, `status()`, metrics SPI, custom `SyncStrategy`/`SnapshotStore`/`LeaderElectionStrategy`, troubleshooting | `references/engine-and-operations.md` |
| Kafka / HTTP / DB sync, K8s lease, S3 / FS store, Jackson serializer, Datadog, admin console, production topologies, infra (topic, DDL, RBAC, IAM) | `references/cluster-modules.md` |
| Spring Boot starter, `CatalogRegistrar`, properties, Spring Kafka sync, wiring modules as beans, tests | `references/spring-boot.md` |

## Integration workflow

1. **Confirm fit.** Read-heavy reference data that fits in heap and changes on the order of minutes. Not for per-entry TTL/eviction, data changing every few seconds, or cross-node strong consistency.
2. **Pick modules** (all `io.github.waabox`, same version):

   | Need | artifactId |
   |---|---|
   | Plain Java | `andersoni-core` |
   | Spring Boot | `andersoni-spring-boot-starter` (+ `andersoni-spring-sync-kafka` for auto-configured Kafka sync) |
   | Sync between nodes | `andersoni-sync-kafka` / `andersoni-sync-http` / `andersoni-sync-db` |
   | Leader election on K8s | `andersoni-leader-k8s` |
   | Snapshot store | `andersoni-snapshot-s3` (prod) / `andersoni-snapshot-fs` (dev) |
   | Serializer (required for any store) | `andersoni-json-serializer` |
   | Metrics | `andersoni-metrics-datadog` |

   Only the starter and spring-sync-kafka are auto-configured; every other module is declared as a `@Bean` (see `spring-boot.md`).
3. **Map each query the user needs to an index type**: exact key → `index`; key collection → `indexMulti`; range/prefix/suffix → `indexSorted`; AND of several → `compound`; composite keys across relationships (`A → B[] → C`, category paths) → `indexGraph`. Add a `view` for each projection returned by an API.
4. **Build the catalog** (`catalog-dsl.md`), then register and start it: plain Java `andersoni.register(c); andersoni.start();`, Spring through a `CatalogRegistrar` bean.
5. **Cluster** (only if >1 node): stable unique `nodeId` per node (pod name), one sync strategy, a leader election, a snapshot store, and a `.serializer(...)` on every catalog. Trigger reloads after writes with `andersoni.refreshAndSync(name)`.
6. **Verify**: `andersoni.status()` (per-catalog `available`, `syncState`, `inSync()`), `andersoni.info(name)` for index sizes and memory.

## Minimal shape (compiles against 1.12.0)

```java
// import org.waabox.andersoni.*;  (Andersoni, Catalog, DataLoader, CompositeKey, RetryPolicy, ... all live in the root package)
final Catalog<Publication> catalog = Catalog.of(Publication.class)
    .named("publications")
    .loadWith(repository::findAll)
    .index("by-slug").by(Publication::slug, Function.identity())
    .indexSorted("by-date").by(Publication::publishedOn, Function.identity())
    .indexGraph("by-country-category")
        .traverseMany("country", Publication::events, e -> e.country().code())
        .traversePath("category", "/", Publication::categoryPath)
        .hotpath("country", "category")
        .done()
    .view(PublicationCard.class, p -> new PublicationCard(p.id(), p.slug()))
    .build();

final List<PublicationCard> cards = andersoni.graphQuery("publications", Publication.class)
    .where("country").eq("AR")
    .and("category").eq("sports/football")   // any path prefix
    .execute(PublicationCard.class);
```

## Traps that bite first

| Trap | Consequence | Fix |
|---|---|---|
| Graph query whose fields don't start with a hotpath's first field | **empty list, no error** | Every query's field set must be the leading fields of some hotpath |
| Graph query skipping a middle hotpath field | `UnsupportedOperationException` | Add a hotpath with that exact prefix |
| Two hotpaths in one `indexGraph` whose values can be equal (e.g. country code vs team code) | wrong items returned: `CompositeKey` stores values only | Put them in separate `indexGraph`s |
| Key of a different type than the extracted value (`1` vs `1L`, `LocalDate` vs `String`) | no match | Query with the exact extracted type |
| `search` on a misspelled index | empty list (while `query`/`compound` throw) | Use constants for index names |
| `refreshAndSync` on a follower | only publishes a `REQUEST`; with DB polling sync it is a no-op | Expected; the leader does the reload |
| Calling `refresh(name)` on a follower with a store configured | overwritten by the store on the next reconciliation pass | Use `refreshAndSync` |
| Catalog built with `.data(List)` | `refresh(name)`/leader `refreshAndSync` throw `IllegalStateException` | Use `loadWith` for anything refreshable |
| Missing `.serializer(...)` with a snapshot store | catalog is not persisted/reconciled | Add `JacksonSnapshotSerializer` (deterministic output) |
| Non-deterministic serializer or unordered `DataLoader` | hashes differ across nodes → every event/reconciliation pass reloads | Deterministic serializer + `ORDER BY` a stable key |
| Same or missing `nodeId` across nodes | nodes ignore each other's events; Kafka creates orphan consumer groups | Set `nodeId` (and `andersoni.sync.kafka.node-id`) to the pod name |
| Bootstrap exhausted retries | catalog FAILED, `CatalogNotAvailableException` on queries until a reload succeeds | Fix the source, then `refreshAndSync(name)` (leader) or `refresh(name)`; followers also recover on the next EVENT or reconciliation pass. In 1.12.0 only a leader promotion, a follower store repair or a restart clears it |
| Large `traverseMany` × deep paths | `IndexKeyLimitExceededException` (default `maxKeysPerItem` 100) → catalog FAILED | Put fan-out fields last, raise `maxKeysPerItem` with headroom |

More per-area mistakes are at the end of each reference file.
