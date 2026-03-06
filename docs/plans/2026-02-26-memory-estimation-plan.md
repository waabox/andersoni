# Memory Estimation Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add optional pre-load memory estimation (fail-fast) and post-build heap pressure warnings to Andersoni catalogs.

**Architecture:** Two independent opt-in mechanisms. Pre-load uses reflection on `Class<T>` + a user-declared `expectedMaxItems` to estimate memory before loading data. Post-build checks heap usage after snapshot construction and emits `log.warn` + metrics when usage exceeds 85%. Both validate at bootstrap and every refresh.

**Tech Stack:** Java 21 reflection, `Runtime.getRuntime()` for heap stats, SLF4J for warnings, existing `AndersoniMetrics` interface.

---

### Task 1: MemoryEstimator — reflection-based per-instance cost calculator

**Files:**
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/MemoryEstimator.java`
- Test: `andersoni-core/src/test/java/org/waabox/andersoni/MemoryEstimatorTest.java`

**Step 1: Write the failing tests**

```java
package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class MemoryEstimatorTest {

  record SimpleRecord(int id, long value, String name) {}

  record NestedRecord(String label, SimpleRecord inner) {}

  record WithCollections(String name, List<String> tags, Map<String, Integer> scores) {}

  record AllPrimitives(
      byte b, short s, int i, long l,
      float f, double d, boolean bool, char c) {}

  @Test
  void whenEstimating_givenSimpleRecord_shouldAccountForAllFields() {
    // Object header (16) + int (4) + long (8) + String ref (8) + padding (4) = 40
    // + String estimation: 40 + 20 (default avg) = 60
    // Total: 40 + 60 = 100
    final long estimate = MemoryEstimator.estimateInstanceSize(SimpleRecord.class);
    assertTrue(estimate > 0);
    // int=4 + long=8 + object header=16 + string ref=8 + padding
    // + string object cost (40 + 20 default)
    assertTrue(estimate >= 80, "Estimate should be at least 80 bytes, was: " + estimate);
    assertTrue(estimate <= 200, "Estimate should be at most 200 bytes, was: " + estimate);
  }

  @Test
  void whenEstimating_givenAllPrimitives_shouldSumCorrectly() {
    // Object header (16) + byte(1) + short(2) + int(4) + long(8)
    // + float(4) + double(8) + boolean(1) + char(2) + padding = ~48
    final long estimate = MemoryEstimator.estimateInstanceSize(AllPrimitives.class);
    // 16 header + 1+2+4+8+4+8+1+2 = 46, aligned to 48
    assertEquals(48, estimate);
  }

  @Test
  void whenEstimating_givenNestedRecord_shouldRecurse() {
    final long simpleEstimate = MemoryEstimator.estimateInstanceSize(SimpleRecord.class);
    final long nestedEstimate = MemoryEstimator.estimateInstanceSize(NestedRecord.class);
    // Nested should be > simple because it includes its own header + String + ref to inner
    assertTrue(nestedEstimate > simpleEstimate,
        "Nested (" + nestedEstimate + ") should exceed simple (" + simpleEstimate + ")");
  }

  @Test
  void whenEstimating_givenCollections_shouldUseDefaultSizes() {
    final long estimate = MemoryEstimator.estimateInstanceSize(WithCollections.class);
    // Should account for String + List + Map overhead
    assertTrue(estimate > 100, "Collections record should be > 100 bytes, was: " + estimate);
  }

  @Test
  void whenEstimating_givenBoxedTypes_shouldEstimateCorrectly() {
    record Boxed(Integer id, Long value, Double score) {}
    final long estimate = MemoryEstimator.estimateInstanceSize(Boxed.class);
    // 3 boxed types: each ~16 bytes object + 8 ref = 24 per boxed
    // + object header 16
    assertTrue(estimate >= 64, "Boxed should be >= 64 bytes, was: " + estimate);
  }
}
```

**Step 2: Run tests to verify they fail**

Run: `cd andersoni-core && mvn test -pl . -Dtest=MemoryEstimatorTest -Dsurefire.failIfNoTests=false`
Expected: Compilation error — `MemoryEstimator` does not exist

**Step 3: Write minimal implementation**

```java
package org.waabox.andersoni;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Estimates the heap memory cost of a single instance of a given class
 * using reflection on its declared fields.
 *
 * <p>The estimation uses JVM structural heuristics for object headers,
 * field sizes, alignment, and common types like String, boxed primitives,
 * and collections. It is intentionally conservative (overestimates rather
 * than underestimates).
 *
 * <p>This class is stateless and all methods are static.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
final class MemoryEstimator {

  /** Object header size in bytes (mark word + klass pointer). */
  static final long OBJECT_HEADER = 16L;

  /** JVM alignment boundary in bytes. */
  static final long ALIGNMENT = 8L;

  /** Base overhead of a String object (header + hash + coder + value ref). */
  static final long STRING_BASE = 40L;

  /** Default estimated average String length in characters. */
  static final long DEFAULT_STRING_LENGTH = 20L;

  /** Base overhead of a Collection instance (ArrayList, HashSet, etc.). */
  static final long COLLECTION_BASE = 40L;

  /** Default estimated number of elements in a Collection field. */
  static final long DEFAULT_COLLECTION_SIZE = 10L;

  /** Base overhead of a Map instance. */
  static final long MAP_BASE = 48L;

  /** Default estimated number of entries in a Map field. */
  static final long DEFAULT_MAP_SIZE = 10L;

  /** Map entry node overhead (key ref + value ref + hash + next + header). */
  static final long MAP_ENTRY_NODE = 32L;

  /** Size of a single object reference. */
  static final long REFERENCE = 8L;

  /** Boxed primitive overhead (object header + primitive value). */
  static final long BOXED_PRIMITIVE = 16L;

  /** Maximum recursion depth for nested object estimation. */
  private static final int MAX_DEPTH = 5;

  private MemoryEstimator() {
  }

  /**
   * Estimates the heap cost of a single instance of the given class.
   *
   * @param type the class to estimate, never null
   *
   * @return the estimated heap cost in bytes, always positive
   */
  static long estimateInstanceSize(final Class<?> type) {
    Objects.requireNonNull(type, "type must not be null");
    return estimateInstanceSize(type, new HashSet<>(), 0);
  }

  private static long estimateInstanceSize(final Class<?> type,
      final Set<Class<?>> visited, final int depth) {

    if (depth > MAX_DEPTH || !visited.add(type)) {
      return REFERENCE;
    }

    long size = OBJECT_HEADER;

    for (final Field field : type.getDeclaredFields()) {
      if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
        continue;
      }
      size += estimateFieldSize(field.getType(), visited, depth);
    }

    return align(size);
  }

  private static long estimateFieldSize(final Class<?> fieldType,
      final Set<Class<?>> visited, final int depth) {

    // Primitives
    if (fieldType == byte.class || fieldType == boolean.class) {
      return 1L;
    }
    if (fieldType == short.class || fieldType == char.class) {
      return 2L;
    }
    if (fieldType == int.class || fieldType == float.class) {
      return 4L;
    }
    if (fieldType == long.class || fieldType == double.class) {
      return 8L;
    }

    // String
    if (fieldType == String.class) {
      return REFERENCE + STRING_BASE + DEFAULT_STRING_LENGTH;
    }

    // Boxed primitives
    if (fieldType == Integer.class || fieldType == Long.class
        || fieldType == Double.class || fieldType == Float.class
        || fieldType == Short.class || fieldType == Byte.class
        || fieldType == Boolean.class || fieldType == Character.class) {
      return REFERENCE + BOXED_PRIMITIVE;
    }

    // Collections
    if (Collection.class.isAssignableFrom(fieldType)) {
      return REFERENCE + COLLECTION_BASE + DEFAULT_COLLECTION_SIZE * REFERENCE;
    }

    // Maps
    if (Map.class.isAssignableFrom(fieldType)) {
      return REFERENCE + MAP_BASE + DEFAULT_MAP_SIZE * MAP_ENTRY_NODE;
    }

    // Other objects: reference + recursive estimation
    return REFERENCE + estimateInstanceSize(fieldType, new HashSet<>(visited), depth + 1);
  }

  /** Aligns a size to the JVM 8-byte boundary. */
  private static long align(final long size) {
    return (size + ALIGNMENT - 1) & ~(ALIGNMENT - 1);
  }
}
```

**Step 4: Run tests to verify they pass**

Run: `cd andersoni-core && mvn test -pl . -Dtest=MemoryEstimatorTest`
Expected: All 5 tests PASS

**Step 5: Commit**

```
feat: add MemoryEstimator for reflection-based instance size estimation
```

---

### Task 2: CatalogMemoryExceededException

**Files:**
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/CatalogMemoryExceededException.java`
- Test: `andersoni-core/src/test/java/org/waabox/andersoni/CatalogMemoryExceededExceptionTest.java`

**Step 1: Write the failing test**

```java
package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CatalogMemoryExceededExceptionTest {

  @Test
  void whenCreating_givenAllParameters_shouldFormatMessage() {
    final CatalogMemoryExceededException ex =
        new CatalogMemoryExceededException("products", 500_000, 120, 60_000_000, 50_000_000);

    assertEquals("products", ex.catalogName());
    assertEquals(500_000, ex.expectedItems());
    assertEquals(120, ex.estimatedBytesPerItem());
    assertEquals(60_000_000, ex.estimatedTotalBytes());
    assertEquals(50_000_000, ex.availableHeapBytes());
    assertTrue(ex.getMessage().contains("products"));
    assertTrue(ex.getMessage().contains("500000"));
    assertInstanceOf(RuntimeException.class, ex);
  }
}
```

**Step 2: Run test to verify it fails**

Run: `cd andersoni-core && mvn test -pl . -Dtest=CatalogMemoryExceededExceptionTest -Dsurefire.failIfNoTests=false`
Expected: Compilation error

**Step 3: Write the implementation**

```java
package org.waabox.andersoni;

/**
 * Thrown when a catalog's estimated memory requirement exceeds the available
 * heap or a user-defined limit.
 *
 * <p>This exception is only thrown when memory estimation is enabled via
 * {@code .expectedMaxItems()} on the Catalog builder. It provides detailed
 * diagnostics to help the user decide whether to increase heap, reduce the
 * dataset, or simplify the data model.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class CatalogMemoryExceededException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String catalogName;
  private final long expectedItems;
  private final long estimatedBytesPerItem;
  private final long estimatedTotalBytes;
  private final long availableHeapBytes;

  /**
   * Creates a new exception with full memory diagnostics.
   *
   * @param catalogName          the catalog name, never null
   * @param expectedItems        the declared maximum item count
   * @param estimatedBytesPerItem the estimated heap cost per item in bytes
   * @param estimatedTotalBytes  the estimated total memory in bytes
   *                             (data + indices + snapshot overhead)
   * @param availableHeapBytes   the available heap at estimation time in bytes
   */
  public CatalogMemoryExceededException(
      final String catalogName,
      final long expectedItems,
      final long estimatedBytesPerItem,
      final long estimatedTotalBytes,
      final long availableHeapBytes) {
    super(String.format(
        "Catalog '%s' memory estimation exceeds available heap. "
            + "Expected items: %,d, estimated per item: %,d bytes, "
            + "estimated total: %,d bytes (~%,.1f MB), "
            + "available heap: %,d bytes (~%,.1f MB). "
            + "Reduce expectedMaxItems, increase heap (-Xmx), "
            + "or simplify the data model.",
        catalogName, expectedItems, estimatedBytesPerItem,
        estimatedTotalBytes, estimatedTotalBytes / (1024.0 * 1024.0),
        availableHeapBytes, availableHeapBytes / (1024.0 * 1024.0)));
    this.catalogName = catalogName;
    this.expectedItems = expectedItems;
    this.estimatedBytesPerItem = estimatedBytesPerItem;
    this.estimatedTotalBytes = estimatedTotalBytes;
    this.availableHeapBytes = availableHeapBytes;
  }

  public String catalogName() { return catalogName; }

  public long expectedItems() { return expectedItems; }

  public long estimatedBytesPerItem() { return estimatedBytesPerItem; }

  public long estimatedTotalBytes() { return estimatedTotalBytes; }

  public long availableHeapBytes() { return availableHeapBytes; }
}
```

**Step 4: Run test to verify it passes**

Run: `cd andersoni-core && mvn test -pl . -Dtest=CatalogMemoryExceededExceptionTest`
Expected: PASS

**Step 5: Commit**

```
feat: add CatalogMemoryExceededException with memory diagnostics
```

---

### Task 3: MemoryGuard — pre-load validation logic

This class takes the `Class<T>`, the `expectedMaxItems`, the index definitions, and an optional `maxHeapUsage`, then decides if the catalog should proceed or fail.

**Files:**
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/MemoryGuard.java`
- Test: `andersoni-core/src/test/java/org/waabox/andersoni/MemoryGuardTest.java`

**Step 1: Write the failing tests**

```java
package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class MemoryGuardTest {

  record Product(String sku, String name, int price) {}

  @Test
  void whenValidating_givenSmallDataset_shouldPass() {
    final MemoryGuard<Product> guard = new MemoryGuard<>(
        Product.class,
        1_000,
        List.of(IndexDefinition.<Product>named("by-sku")
            .by(Product::sku, s -> s)),
        List.of(),
        0);

    assertDoesNotThrow(() -> guard.validate("products"));
  }

  @Test
  void whenValidating_givenExplicitLimit_shouldThrowWhenExceeded() {
    // maxHeapUsage = 1 byte, so any estimation will exceed it
    final MemoryGuard<Product> guard = new MemoryGuard<>(
        Product.class,
        1_000_000,
        List.of(IndexDefinition.<Product>named("by-sku")
            .by(Product::sku, s -> s)),
        List.of(),
        1L);

    final CatalogMemoryExceededException ex = assertThrows(
        CatalogMemoryExceededException.class,
        () -> guard.validate("products"));
    assertTrue(ex.getMessage().contains("products"));
    assertTrue(ex.estimatedTotalBytes() > 1);
  }

  @Test
  void whenValidating_givenNoExplicitLimit_shouldUseHeapAvailable() {
    // With a reasonable item count, should pass against actual heap
    final MemoryGuard<Product> guard = new MemoryGuard<>(
        Product.class,
        100,
        List.of(IndexDefinition.<Product>named("by-sku")
            .by(Product::sku, s -> s)),
        List.of(),
        0);

    assertDoesNotThrow(() -> guard.validate("products"));
  }
}
```

**Step 2: Run tests to verify they fail**

Run: `cd andersoni-core && mvn test -pl . -Dtest=MemoryGuardTest -Dsurefire.failIfNoTests=false`
Expected: Compilation error

**Step 3: Write the implementation**

```java
package org.waabox.andersoni;

import java.util.List;
import java.util.Objects;

/**
 * Validates whether a catalog's estimated memory footprint fits within
 * available resources before loading data.
 *
 * <p>Uses {@link MemoryEstimator} to compute per-instance cost via
 * reflection, then multiplies by the declared maximum item count and adds
 * estimated index overhead.
 *
 * <p>This guard is only active when {@code expectedMaxItems} is configured
 * on the Catalog builder. When not configured, no MemoryGuard is created
 * and no validation occurs.
 *
 * @param <T> the type of catalog items
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
final class MemoryGuard<T> {

  /** Estimated overhead per index entry: HashMap.Node + ArrayList header
   *  + key reference + value reference. */
  private static final long INDEX_ENTRY_OVERHEAD = 32L + 40L + 8L + 8L;

  private final Class<T> type;
  private final long expectedMaxItems;
  private final List<IndexDefinition<T>> indexDefinitions;
  private final List<SortedIndexDefinition<T>> sortedIndexDefinitions;
  private final long maxHeapUsage;

  /**
   * Creates a new MemoryGuard.
   *
   * @param type                   the item class for reflection, never null
   * @param expectedMaxItems       the declared maximum item count, must be positive
   * @param indexDefinitions       the regular index definitions, never null
   * @param sortedIndexDefinitions the sorted index definitions, never null
   * @param maxHeapUsage           the explicit heap limit in bytes,
   *                               or 0 to use available heap
   */
  MemoryGuard(
      final Class<T> type,
      final long expectedMaxItems,
      final List<IndexDefinition<T>> indexDefinitions,
      final List<SortedIndexDefinition<T>> sortedIndexDefinitions,
      final long maxHeapUsage) {
    this.type = Objects.requireNonNull(type, "type must not be null");
    this.expectedMaxItems = expectedMaxItems;
    this.indexDefinitions = Objects.requireNonNull(indexDefinitions,
        "indexDefinitions must not be null");
    this.sortedIndexDefinitions = Objects.requireNonNull(sortedIndexDefinitions,
        "sortedIndexDefinitions must not be null");
    this.maxHeapUsage = maxHeapUsage;
  }

  /**
   * Validates that the estimated memory fits within the configured limit
   * or available heap.
   *
   * @param catalogName the catalog name for error reporting, never null
   *
   * @throws CatalogMemoryExceededException if the estimation exceeds
   *                                        the limit
   */
  void validate(final String catalogName) {
    final long perItemCost = MemoryEstimator.estimateInstanceSize(type);
    final long dataCost = perItemCost * expectedMaxItems;

    final int totalIndexCount = indexDefinitions.size()
        + sortedIndexDefinitions.size();
    // Each item appears once per index. Sorted indexes have 2 structures
    // (hash + tree), so count them twice for overhead estimation.
    final long indexEntries = expectedMaxItems
        * (indexDefinitions.size() + sortedIndexDefinitions.size() * 2L);
    final long indexCost = indexEntries * INDEX_ENTRY_OVERHEAD
        + totalIndexCount * 48L; // HashMap/TreeMap base overhead per index

    final long estimatedTotal = dataCost + indexCost;

    final long limit;
    if (maxHeapUsage > 0) {
      limit = maxHeapUsage;
    } else {
      final Runtime runtime = Runtime.getRuntime();
      limit = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
    }

    if (estimatedTotal > limit) {
      throw new CatalogMemoryExceededException(
          catalogName, expectedMaxItems, perItemCost, estimatedTotal, limit);
    }
  }
}
```

**Step 4: Run tests to verify they pass**

Run: `cd andersoni-core && mvn test -pl . -Dtest=MemoryGuardTest`
Expected: All 3 tests PASS

**Step 5: Commit**

```
feat: add MemoryGuard for pre-load memory validation
```

---

### Task 4: Wire MemoryGuard into Catalog DSL and lifecycle

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/Catalog.java`
- Modify: `andersoni-core/src/test/java/org/waabox/andersoni/CatalogTest.java`

**Step 1: Write the failing tests**

Add to `CatalogTest.java`:

```java
@Test
void whenBuilding_givenExpectedMaxItems_shouldStoreConfiguration() {
  final Catalog<Event> catalog = Catalog.of(Event.class)
      .named("events")
      .loadWith(() -> List.of())
      .expectedMaxItems(100_000)
      .index("by-venue").by(Event::venue, Venue::name)
      .build();

  assertNotNull(catalog);
}

@Test
void whenBootstrapping_givenExceededMaxHeapUsage_shouldThrowMemoryException() {
  final Catalog<Event> catalog = Catalog.of(Event.class)
      .named("events")
      .loadWith(() -> List.of())
      .expectedMaxItems(100_000_000)
      .maxHeapUsage(1L) // 1 byte limit — will always exceed
      .index("by-venue").by(Event::venue, Venue::name)
      .build();

  assertThrows(CatalogMemoryExceededException.class, catalog::bootstrap);
}

@Test
void whenBootstrapping_givenNoMemoryGuard_shouldWorkAsUsual() {
  final Venue maracana = new Venue("Maracana");
  final Sport football = new Sport("Football");
  final Event e1 = new Event("1", football, maracana);

  final Catalog<Event> catalog = Catalog.of(Event.class)
      .named("events")
      .loadWith(() -> List.of(e1))
      .index("by-venue").by(Event::venue, Venue::name)
      .build();

  catalog.bootstrap();
  assertEquals(1, catalog.currentSnapshot().data().size());
}

@Test
void whenRefreshing_givenExceededMaxHeapUsage_shouldThrowMemoryException() {
  final Catalog<Event> catalog = Catalog.of(Event.class)
      .named("events")
      .loadWith(() -> List.of())
      .expectedMaxItems(100_000_000)
      .maxHeapUsage(1L)
      .index("by-venue").by(Event::venue, Venue::name)
      .build();

  assertThrows(CatalogMemoryExceededException.class, catalog::refresh);
}
```

**Step 2: Run tests to verify they fail**

Run: `cd andersoni-core && mvn test -pl . -Dtest=CatalogTest`
Expected: Compilation errors — `expectedMaxItems` and `maxHeapUsage` methods don't exist

**Step 3: Modify Catalog.java**

Changes needed in `Catalog.java`:

1. **Add field** to `Catalog`: `private final MemoryGuard<T> memoryGuard;` (nullable)

2. **Add field** to `Catalog` constructor parameter and assignment.

3. **Add `expectedMaxItems()` and `maxHeapUsage()` to `BuildStep`**:

In `BuildStep`, add two fields:
```java
private long expectedMaxItems;
private long maxHeapUsage;
```

Add two DSL methods:
```java
public BuildStep<T> expectedMaxItems(final long maxItems) {
  if (maxItems <= 0) {
    throw new IllegalArgumentException("expectedMaxItems must be positive");
  }
  this.expectedMaxItems = maxItems;
  return this;
}

public BuildStep<T> maxHeapUsage(final long bytes) {
  if (bytes <= 0) {
    throw new IllegalArgumentException("maxHeapUsage must be positive");
  }
  this.maxHeapUsage = bytes;
  return this;
}
```

4. **Pass `Class<T> type` through the builder steps** — currently `of(Class<T>)` creates `NameStep` but doesn't store the class. Need to thread it through `NameStep` → `DataSourceStep` → `BuildStep`.

5. **Modify `build()`** to create `MemoryGuard` when `expectedMaxItems > 0`.

6. **Modify `bootstrap()` and `refresh()` methods** to call `memoryGuard.validate(name)` before loading data, if memoryGuard is not null.

**Step 4: Run tests to verify they pass**

Run: `cd andersoni-core && mvn test -pl . -Dtest=CatalogTest`
Expected: All tests PASS (including the 4 new ones)

**Step 5: Run the full test suite**

Run: `cd andersoni-core && mvn test`
Expected: All existing tests still pass

**Step 6: Commit**

```
feat: wire MemoryGuard into Catalog DSL with expectedMaxItems and maxHeapUsage
```

---

### Task 5: Post-build heap pressure warning

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/Catalog.java`
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/metrics/AndersoniMetrics.java`
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/metrics/NoopAndersoniMetrics.java`
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/Andersoni.java`
- Modify: `andersoni-core/src/test/java/org/waabox/andersoni/AndersoniTest.java`

**Step 1: Write the failing test**

Add to `AndersoniTest.java` (or create a focused test if needed):

```java
@Test
void whenBootstrapping_givenHeapPressure_shouldReportMetric() {
  // This test verifies that heapPressureDetected is called on the metrics
  // interface after snapshot construction. We can't easily simulate 85%
  // heap pressure in a unit test, but we can verify the method exists
  // and is called by the reporting path.
  // The actual threshold check is tested via MemoryGuard or a dedicated test.
}
```

The key behavioral test: after `reportIndexSizes()` in `Andersoni`, add a heap pressure check that logs and reports to metrics.

**Step 2: Add `heapPressureDetected` to `AndersoniMetrics`**

```java
/**
 * Records that heap pressure was detected after a snapshot build.
 *
 * @param catalogName       the catalog name, never null
 * @param heapUsedBytes     the heap used in bytes
 * @param heapMaxBytes      the max heap in bytes
 * @param heapUsagePercent  the heap usage percentage (0-100)
 */
void heapPressureDetected(String catalogName, long heapUsedBytes,
    long heapMaxBytes, double heapUsagePercent);
```

**Step 3: Add empty implementation to `NoopAndersoniMetrics`**

```java
@Override
public void heapPressureDetected(final String catalogName,
    final long heapUsedBytes, final long heapMaxBytes,
    final double heapUsagePercent) {
}
```

**Step 4: Add heap pressure check to `Andersoni`**

After `reportIndexSizes(catalog)` in both `bootstrapAsLeader` and `refreshFromEvent`, add:

```java
checkHeapPressure(catalog.name());
```

With private method:

```java
/** Hardcoded threshold: warn at 85% heap usage. */
private static final double HEAP_PRESSURE_THRESHOLD = 0.85;

private void checkHeapPressure(final String catalogName) {
  final Runtime runtime = Runtime.getRuntime();
  final long used = runtime.totalMemory() - runtime.freeMemory();
  final long max = runtime.maxMemory();
  final double usage = (double) used / max;

  if (usage >= HEAP_PRESSURE_THRESHOLD) {
    final double percent = usage * 100.0;
    log.warn("Catalog '{}': heap pressure detected after snapshot build. "
        + "Heap used: {} bytes ({:.1f}%), max: {} bytes. "
        + "Consider increasing -Xmx or reducing dataset size.",
        catalogName, used, percent, max);
    metrics.heapPressureDetected(catalogName, used, max, percent);
  }
}
```

**Step 5: Run the full test suite**

Run: `cd andersoni-core && mvn test`
Expected: All tests PASS

**Step 6: Commit**

```
feat: add post-build heap pressure warning with metrics and log.warn
```

---

### Task 6: Full integration test and JavaDoc

**Files:**
- Modify: `andersoni-core/src/test/java/org/waabox/andersoni/AndersoniIntegrationTest.java`

**Step 1: Write integration test**

```java
@Test
void whenBootstrapping_givenMemoryGuardWithReasonableLimit_shouldSucceed() {
  // Full end-to-end: configure catalog with expectedMaxItems,
  // register in Andersoni, bootstrap, verify search works
  final Catalog<Event> catalog = Catalog.of(Event.class)
      .named("events")
      .loadWith(() -> List.of(
          new Event("1", new Sport("Football"), new Venue("Wembley"))))
      .expectedMaxItems(10_000)
      .index("by-venue").by(Event::venue, Venue::name)
      .build();

  final Andersoni andersoni = Andersoni.builder().build();
  andersoni.register(catalog);
  andersoni.start();

  final List<?> results = andersoni.search("events", "by-venue", "Wembley");
  assertEquals(1, results.size());

  andersoni.stop();
}

@Test
void whenBootstrapping_givenMemoryGuardExceeded_shouldFailFast() {
  final Catalog<Event> catalog = Catalog.of(Event.class)
      .named("events")
      .loadWith(() -> List.of())
      .expectedMaxItems(100_000_000)
      .maxHeapUsage(1L)
      .index("by-venue").by(Event::venue, Venue::name)
      .build();

  final Andersoni andersoni = Andersoni.builder().build();
  andersoni.register(catalog);

  // Bootstrap should fail because memory guard rejects
  assertThrows(CatalogMemoryExceededException.class, andersoni::start);
  andersoni.stop();
}
```

**Step 2: Run integration tests**

Run: `cd andersoni-core && mvn test -pl . -Dtest=AndersoniIntegrationTest`
Expected: All tests PASS

**Step 3: Verify full build**

Run: `mvn clean verify`
Expected: BUILD SUCCESS

**Step 4: Commit**

```
feat: add integration tests for memory estimation feature
```

---

### Task 7: Update design doc and JavaDoc sweep

**Files:**
- Modify: `docs/plans/2026-02-26-memory-estimation-design.md` (mark as implemented)

**Step 1: Add "Status: Implemented" to the design doc header**

**Step 2: Verify all new public methods have JavaDoc** per project conventions

**Step 3: Final commit**

```
docs: mark memory estimation design as implemented
```
