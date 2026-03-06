# Memory Estimation and Protection

## Problem

Andersoni is a full-dataset in-memory cache. If the data doesn't fit in heap, the JVM
throws an `OutOfMemoryError` with no useful context. The library should have a clear
posture for this scenario instead of silently exploding.

## Design

Two independent, optional mechanisms:

### 1. Pre-load estimation (fail-fast)

Opt-in via the Catalog DSL. Validates **before loading data**, at bootstrap and every
refresh. If the estimation exceeds available resources, throws
`CatalogMemoryExceededException` with full diagnostics.

**How it works:**

1. User declares `.expectedMaxItems(N)` on the Catalog builder.
2. Andersoni uses reflection on `Class<T>` to estimate the heap cost of one instance:
   - Object header (~16 bytes)
   - Each field by type: primitives (known size), `String` (~40 bytes base + estimated
     avg length), boxed types, nested objects (recursive estimation), references (~8 bytes)
   - Alignment/padding
3. Andersoni estimates index overhead from the `IndexDefinition` list:
   - HashMap/TreeMap node overhead per estimated unique key
   - ArrayList headers + reference lists per bucket
   - Key object sizes (derived from key extractor return type via reflection)
4. Total estimate = `(perInstanceCost * expectedMaxItems) + indexOverhead + snapshotOverhead`
5. Compares against:
   - A user-declared limit if provided via `.maxHeapUsage(Size.mb(512))`
   - Otherwise, against current available heap
6. If exceeded: throws `CatalogMemoryExceededException` with message containing:
   - Expected item count
   - Estimated per-item cost (bytes)
   - Estimated total memory (data + indices)
   - Available heap
   - Specific recommendation: "reduce expectedMaxItems, increase heap (-Xmx), or
     simplify the data model"

**If not configured:** Andersoni behaves exactly as today. No validation, no overhead.

### 2. Post-build heap warning (observability)

After constructing a snapshot (bootstrap or refresh), Andersoni checks heap state and
emits warnings if the JVM is under memory pressure.

**How it works:**

1. After snapshot construction, read the real index sizes from `Snapshot.indexInfo()`
   (already implemented).
2. Check heap usage via `Runtime.getRuntime()`: `totalMemory() - freeMemory()` vs
   `maxMemory()`.
3. If heap usage exceeds **85%** of max heap (hardcoded threshold):
   - Emit `log.warn` with: catalog name, snapshot item count, real index sizes, heap
     usage percentage, max heap.
   - Report via `AndersoniMetrics` (new metric: `heapPressureWarning` or similar).
4. The snapshot **continues to serve**. This is informational, not a gate.

## DSL Changes

```java
// Pre-load estimation (opt-in)
Catalog.of(Product.class)
    .named("products")
    .loadWith(loader)
    .expectedMaxItems(500_000)            // enables pre-load estimation
    .maxHeapUsage(Size.mb(512))           // optional explicit limit
    .index("sku").by(Product::sku)
    .build();

// No estimation (default, current behavior)
Catalog.of(Product.class)
    .named("products")
    .loadWith(loader)
    .index("sku").by(Product::sku)
    .build();
```

## Exception

```java
public class CatalogMemoryExceededException extends RuntimeException {

  private final String catalogName;
  private final long expectedItems;
  private final long estimatedBytesPerItem;
  private final long estimatedTotalBytes;
  private final long availableHeapBytes;

  // Constructor, getters, detailed message formatting
}
```

## Memory Estimation by Field Type

| Java Type | Estimated Heap Cost |
|-----------|-------------------|
| `byte`, `boolean` | 1 byte |
| `short`, `char` | 2 bytes |
| `int`, `float` | 4 bytes |
| `long`, `double` | 8 bytes |
| `String` | 40 bytes + avg length (configurable, default 20 chars) |
| `Integer`, `Long`, etc. | 16 bytes (object header + primitive) |
| `List<X>`, `Set<X>` | 40 bytes + (estimatedSize * referenceSize) |
| `Map<K,V>` | 48 bytes + (estimatedSize * (entryNode + keySize + valueSize)) |
| Object reference | 8 bytes (compressed oops) |
| Object header | 16 bytes |
| Any other object | Recursive field analysis |

## What This Does NOT Do

- Does not load data partially or evict items.
- Does not use off-heap memory.
- Does not make decisions for the user.
- Simply says "no" (pre-load) or "watch out" (post-build) so the user decides:
  increase heap, simplify the model, or reduce the dataset.
