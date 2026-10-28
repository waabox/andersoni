---
name: andersoni
description: Use when a Java project depends on or wants to adopt Andersoni (io.github.waabox, package org.waabox.andersoni) — defining catalogs, regular/sorted/multi/graph indexes, search/range/compound/graph queries, views, build hooks, Spring Boot starter, Kafka/HTTP/DB sync, K8s lease leader election, S3/filesystem snapshots, reconciliation, single-item patches (replace/insert/remove/upsert, replaceAndSync, PatchOp batches), Datadog metrics — or when diagnosing CatalogNotAvailableException, IndexKeyLimitExceededException, AmbiguousPatchTargetException, empty graph query results, nodes out of sync, refreshAndSync having no effect, or a patch returning NOT_LEADER.
---

# Andersoni

In-memory indexed cache for Java 21. A `Catalog<T>` loads a dataset with a `DataLoader`, builds every index into an immutable `Snapshot<T>`, and swaps it atomically: reads are lock-free map lookups, refreshes rebuild the whole snapshot (no per-item updates, no TTL). Multi-node consistency comes from pluggable sync + leader election + an authoritative snapshot store.

**Never guess this API.** Method names are not what you would expect (`indexGraph`, not `graphIndex`; `graphQuery().where(f).eq(v)`, not `search(...).graph(...)`; catalogs are registered through `CatalogRegistrar`, not as `Catalog` beans; there are only three `andersoni.*` Spring properties). Before writing code, open the reference for the area you touch. Version documented: **1.13.0**.

## Reference map

| Task | Read |
|---|---|
| Define a catalog, indexes, views, hooks, `data(List)`, `refreshEvery`, `Snapshot`, `CatalogInfo`, local patches (`replace`/`insert`/`remove`/`upsert`/`apply`) | `references/catalog-dsl.md` |
| `search`, `query` (ranges, startsWith/endsWith/contains), `compound` (AND across indexes), result guarantees, errors | `references/querying.md` |
| Graph index: traversals, hotpaths, `CompositeKey`, `graphQuery`, query planner, design recipe | `references/graph-index.md` |
| `Andersoni.builder()`, lifecycle, `refresh` vs `refreshAndSync`, cluster patches (`replaceAndSync`, `applyAndSync`, `PatchOutcome`), leader/follower, retry, reconciliation, `status()`, metrics SPI, custom `SyncStrategy`/`SnapshotStore`/`LeaderElectionStrategy`, troubleshooting | `references/engine-and-operations.md` |
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
5. **Cluster** (only if >1 node): stable unique `nodeId` per node (pod name), one sync strategy, a leader election, a snapshot store, and a `.serializer(...)` on every catalog. Trigger reloads after writes with `andersoni.refreshAndSync(name)`. Since 1.13.0 a single-item write can instead go through `andersoni.replaceAndSync(name, index, key, item)` (and `insertAndSync`/`removeAndSync`/`upsertAndSync`/`applyAndSync`), which patches the snapshot copy-on-write and broadcasts only the delta over Kafka or HTTP sync; every node must already run 1.13.0+ before the first patch call (`engine-and-operations.md`, section 4).
6. **Verify**: `andersoni.status()` (per-catalog `available`, `syncState`, `inSync()`), `andersoni.info(name)` for index sizes and memory.

## Minimal shape (compiles against 1.13.0)

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
| Looking up a graph index with `search`/`compound` and a `CompositeKey.of(...)` | finds nothing (graph keys are internal and field-qualified) | Always query graph indexes with `graphQuery()` |
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
| `replaceAndSync`/`insertAndSync`/... on a follower (1.13.0) | returns `PatchOutcome.NOT_LEADER`, nothing happens, nothing is forwarded to the leader | Expected: route writes to the leader (`status().leader()`). When any node may take the write, call `refreshAndSync(name)` on `NOT_LEADER`: it publishes a `REQUEST` and the leader runs the full reload. Log `NOT_FOUND` |
| Patch lookup on a non-unique index (1.13.0) | `AmbiguousPatchTargetException` when the key matches more than one item | Look items up through a uniquely-keyed index (an id index) |
| Patch lookup on an `indexGraph` name (1.13.0) | accepted, but no key ever matches (graph keys are internal composite keys): `NOT_FOUND`/`false`, nothing changes, nothing is logged | Declare a plain `.index("by-id")` next to the graph index and patch through it |
| Patch on a catalog with a hook that does not override `supportsPatch()` (1.13.0) | every patch rebuilds the whole snapshot (correct, no reload, but no surgical index work) | Return `true` from `supportsPatch()` only for per-item hooks; check `catalog.appliesPatchesSurgically()` |
| Patch call on a fleet with nodes on 1.12.2 or earlier (1.13.0) | old nodes cannot decode the message and keep serving stale data while logging deserialization errors | Upgrade every node first; installing 1.13.0 changes nothing until a patch method is called |

More per-area mistakes are at the end of each reference file.
