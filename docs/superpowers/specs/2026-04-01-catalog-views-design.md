# Catalog Views — Design Spec

## Context

Andersoni stores full domain objects `T` in immutable snapshots with lock-free reads. Every query (search, range, compound) returns `List<T>` — always the full object. Consumers that need a smaller projection must transform results manually after each query, repeatedly producing the same transformation for the same data.

## Problem

There is no way to define pre-computed projections (views) of a catalog's domain object. Consumers are forced to transform query results into smaller DTOs on every access, even though these projections are deterministic and never change between snapshot refreshes.

## Solution

Introduce a **views system** at the catalog level. Views are pre-computed projections of `T` into smaller typed objects `V`, materialized at snapshot build time and stored alongside the original item. Queries accept an optional view class to return `List<V>` instead of `List<T>`.

## Design

### AndersoniCatalogItem\<T\>

Internal wrapper (never exposed in the public API) that holds the full domain object and its pre-computed views:

```java
// Package-private — internal to Andersoni
class AndersoniCatalogItem<T> {
    private final T item;
    private final Map<Class<?>, Object> views;  // pre-computed, immutable
}
```

- Immutable. Views map is created at build time and never mutated.
- The user never sees, instantiates, or types `AndersoniCatalogItem`. All public APIs speak in terms of `T` and `V`.

### ViewDefinition\<T, V\>

Associates a view type with its transformation function:

```java
public record ViewDefinition<T, V>(Class<V> viewType, Function<T, V> mapper) {}
```

- `viewType`: the class of the view (e.g., `EventSummary.class`)
- `mapper`: `Function<T, V>` — simple, stateless mapping. No access to snapshot context.

### Catalog DSL

Views are defined at the catalog level using `.view()` in the builder:

```java
Catalog<Event> catalog = Catalog.of(Event.class)
    .named("events")
    .loadWith(() -> repository.findAll())
    .index("by-venue").by(Event::venue, Venue::name)
    .indexSorted("by-date").by(Event::eventDate, EventDate::value)
    .view(EventSummary.class, e -> new EventSummary(e.id(), e.name()))
    .view(EventCard.class, e -> new EventCard(e.id(), e.name(), e.imageUrl()))
    .build();
```

- Views are catalog-wide: any view defined is accessible from any index/query.
- Views are never indexed. Indexes always operate on `T`.
- Duplicate view class registration on the same catalog throws `IllegalArgumentException`.

### Snapshot Build

When building a snapshot:

1. Load `List<T>` from the data source (loader).
2. For each `T` item:
   a. Apply each `ViewDefinition` mapper to produce the view objects.
   b. Create an `AndersoniCatalogItem<T>` with the item and its views map.
3. Build all indexes using `item` from each `AndersoniCatalogItem<T>` (indexes operate on `T`, not on the wrapper).
4. Store `List<AndersoniCatalogItem<T>>` in the snapshot.

Views are materialized eagerly — no lazy computation, no re-computation on access.

### Query API

All existing query operations gain a view variant. The view class is always the last parameter (or the parameter of the terminal method):

```java
// Full object (existing API, unchanged)
List<Event> r = andersoni.search("events", "by-venue", "Maracana");

// View
List<EventSummary> r = andersoni.search("events", "by-venue", "Maracana", EventSummary.class);

// Range query with view
List<EventSummary> r = andersoni.query("events", "by-date")
    .between(from, to, EventSummary.class);

// startsWith with view
List<EventSummary> r = andersoni.query("events", "by-name")
    .startsWith("Arg", EventSummary.class);

// Compound query with view
List<EventSummary> r = andersoni.compound("events")
    .where("by-venue").equalTo("Maracana")
    .and("by-sport").equalTo("Football")
    .execute(EventSummary.class);

// All data with view
List<EventSummary> r = andersoni.all("events", EventSummary.class);
```

When no view class is provided, the API returns `List<T>` as today — fully backward compatible.

### Internal Resolution

1. Index search returns `List<AndersoniCatalogItem<T>>` (internal).
2. If no view class requested: extract `.item()` from each wrapper, return `List<T>`.
3. If view class requested: extract `.views().get(viewClass)` from each wrapper, return `List<V>`.
4. If the view class is not registered in the catalog: throw `IllegalArgumentException` with a clear message (e.g., `"View EventSummary is not registered in catalog 'events'"`).

### Serialization

- The `SnapshotSerializer<T>` interface does not change.
- Andersoni already has a built-in JSON serializer. It serializes `AndersoniCatalogItem<T>` (item + views) transparently.
- The user does not implement anything for serialization.
- Views are persisted alongside the item. On restore, views are deserialized — not re-computed.
- `AndersoniCatalogItem` remains an internal detail even in the serialization layer.

### Memory Estimation

The existing `estimateMemory()` in `Snapshot` is extended to account for:
- The `AndersoniCatalogItem` wrapper overhead per element.
- Each view object per element (reference + estimated object size).
- The views `Map` overhead per element.

### CatalogInfo

`catalog.info()` is extended to report:
- Number of registered views per catalog.
- View type names.
- Estimated memory per view (included in the total memory estimation).

## Constraints

- Views are always defined at the catalog level, accessible from all indexes.
- Views are never indexed — search always operates on `T`.
- View mapping is `Function<T, V>` — stateless, no snapshot context.
- View types must be static classes/records with compile-time type safety.
- `AndersoniCatalogItem<T>` is internal — never leaked to public API.
- Existing API without view class parameter remains unchanged (backward compatible).

## Rejected Alternatives

### Lazy computation with cache
Views computed on first `.as()` call and cached. Rejected because it contradicts Andersoni's philosophy of pre-computed immutable snapshots and introduces non-deterministic first-access latency.

### Parallel lists with positional mapping
Views stored in `Map<Class<?>, List<?>>` with same positional index as `List<T> data`. Rejected because it requires a separate position-lookup mechanism (identity map or index wrapping) and the views don't live alongside the item.

### Duplicate indexes per view
Each view type gets its own full index structure. Rejected due to massive memory duplication and build-time overhead — indexes always operate on the same keys regardless of projection.

### Java Serialization for views
Using Java's built-in serialization for view objects. Rejected because it is fragile, tightly coupled to class structure, and problematic for type evolution.

### Per-view serializers
Each `.view()` definition includes its own `SnapshotSerializer<V>`. Rejected because the existing catalog-level serializer handles the full payload and the interface should not change.

### Re-computation on restore
Persist only `T`, re-apply view mappers on deserialization. Rejected because the user explicitly requires views to be persisted and restored without re-computation.
