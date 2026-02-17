package org.waabox.andersoni;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Performance benchmark for the Andersoni in-memory indexed cache library.
 *
 * <p>Measures snapshot build time, search latency for both HashMap and sorted
 * index operations (range queries, text pattern matching), concurrent read
 * throughput, and approximate memory footprint across different dataset sizes.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class AndersoniBenchmark {

  /** Number of distinct category values. */
  private static final int CATEGORY_COUNT = 100;

  /** Number of distinct region values. */
  private static final int REGION_COUNT = 50;

  /** Number of distinct status values. */
  private static final int STATUS_COUNT = 5;

  /** Number of distinct score values for sorted numeric index. */
  private static final int SCORE_COUNT = 1_000;

  /** Number of distinct date values (~6 years of daily dates). */
  private static final int DATE_COUNT = 2_190;

  /** Pre-generated ISO date strings ("2020-01-01" to "2025-12-31"). */
  private static final String[] DATES = new String[DATE_COUNT];

  /** Pre-generated year-month prefixes for startsWith benchmarks. */
  private static final String[] YEAR_MONTHS;

  /** Start date for date generation. */
  private static final LocalDate DATE_START = LocalDate.of(2020, 1, 1);

  /** Number of search iterations for latency measurement. */
  private static final int SEARCH_ITERATIONS = 100_000;

  /** Number of threads for concurrent read throughput. */
  private static final int THREAD_COUNT = 8;

  /** Total reads across all threads for throughput measurement. */
  private static final int TOTAL_READS = 1_000_000;

  /** Dataset sizes to benchmark. */
  private static final int[] DATASET_SIZES = {10_000, 100_000, 500_000};

  /** Pre-generated category keys. */
  private static final String[] CATEGORIES = new String[CATEGORY_COUNT];

  /** Pre-generated region keys. */
  private static final String[] REGIONS = new String[REGION_COUNT];

  /** Pre-generated status keys. */
  private static final String[] STATUSES = new String[STATUS_COUNT];

  static {
    for (int i = 0; i < CATEGORY_COUNT; i++) {
      CATEGORIES[i] = "category-" + i;
    }
    for (int i = 0; i < REGION_COUNT; i++) {
      REGIONS[i] = "region-" + i;
    }
    for (int i = 0; i < STATUS_COUNT; i++) {
      STATUSES[i] = "status-" + i;
    }
    for (int i = 0; i < DATE_COUNT; i++) {
      DATES[i] = DATE_START.plusDays(i).toString();
    }

    // Pre-compute year-month prefixes from 2020-01 to 2025-12 (72 months).
    final List<String> ymList = new ArrayList<>();
    LocalDate cursor = DATE_START;
    final LocalDate end = LocalDate.of(2025, 12, 1);
    while (!cursor.isAfter(end)) {
      ymList.add(cursor.toString().substring(0, 7));
      cursor = cursor.plusMonths(1);
    }
    YEAR_MONTHS = ymList.toArray(new String[0]);
  }

  /** A simple item record for benchmarking. */
  record Item(String id, String category, String region, String status,
      int score, String date) {

    @Override
    public String toString() {
      return id;
    }
  }

  /** Private constructor to prevent instantiation. */
  private AndersoniBenchmark() {
  }

  /**
   * Entry point for the benchmark.
   *
   * @param args command line arguments (unused)
   *
   * @throws Exception if any benchmark step fails
   */
  public static void main(final String[] args) throws Exception {
    System.out.println(
        "==========================================================");
    System.out.println("  Andersoni Benchmark");
    System.out.println(
        "==========================================================");
    System.out.println();

    warmup();

    printGeneralBenchmarks();

    printQueryDslBenchmarks();

    System.out.println();
    System.out.println(
        "==========================================================");
    System.out.println("  Benchmark complete");
    System.out.println(
        "==========================================================");
  }

  /**
   * Warms up the JVM by exercising all code paths with a small dataset.
   */
  private static void warmup() {
    System.out.println("Warming up JVM...");
    final List<Item> warmupData = generateData(1_000);
    final Catalog<Item> warmupCatalog = buildCatalog(warmupData);
    warmupCatalog.bootstrap();

    for (int i = 0; i < 10_000; i++) {
      warmupCatalog.search("by-category",
          CATEGORIES[i % CATEGORY_COUNT]);
      warmupCatalog.search("by-region",
          REGIONS[i % REGION_COUNT]);
      warmupCatalog.search("by-status",
          STATUSES[i % STATUS_COUNT]);

      final int score = i % (SCORE_COUNT - 20);
      warmupCatalog.query("by-score").equalTo(score);
      warmupCatalog.query("by-score").between(score, score + 20);
      warmupCatalog.query("by-score").greaterThan(SCORE_COUNT - 20);
      warmupCatalog.query("by-score").greaterOrEqual(SCORE_COUNT - 20);
      warmupCatalog.query("by-score").lessThan(20);
      warmupCatalog.query("by-score").lessOrEqual(20);

      warmupCatalog.query("by-category-sorted")
          .startsWith("category-" + (i % 10));
      warmupCatalog.query("by-category-sorted")
          .endsWith("-" + (i % 10));
      warmupCatalog.query("by-category-sorted")
          .contains("gory-" + (i % 10));

      final int dateIdx = i % (DATE_COUNT - 90);
      warmupCatalog.query("by-date")
          .between(DATES[dateIdx], DATES[dateIdx + 90]);
      warmupCatalog.query("by-date")
          .startsWith(YEAR_MONTHS[i % YEAR_MONTHS.length]);
      warmupCatalog.query("by-date")
          .contains("-" + String.format("%02d", (i % 28) + 1));
    }

    System.out.println("Warmup complete.");
    System.out.println();
  }

  /**
   * Prints the general performance table: build time, HashMap search
   * latency, concurrent throughput, and memory.
   *
   * @throws Exception if any step fails
   */
  private static void printGeneralBenchmarks() throws Exception {
    System.out.println(
        "----------------------------------------------------------");
    System.out.println(
        "  General Performance (HashMap + Sorted indexes)");
    System.out.println(
        "----------------------------------------------------------");
    System.out.printf("%-12s | %-18s | %-22s | %-22s | %-14s%n",
        "Items", "Build Time", "Avg Search Latency",
        "Concurrent Throughput", "Memory (approx)");
    System.out.println(
        "-------------|--------------------"
            + "|------------------------"
            + "|------------------------"
            + "|----------------");

    for (final int size : DATASET_SIZES) {
      runGeneralBenchmark(size);
    }

    System.out.println();
  }

  /**
   * Runs general benchmarks for a given dataset size.
   *
   * @param size the number of items in the dataset
   *
   * @throws Exception if any step fails
   */
  private static void runGeneralBenchmark(final int size) throws Exception {
    System.gc();
    Thread.sleep(200);

    final List<Item> data = generateData(size);
    final Catalog<Item> catalog = buildCatalog(data);

    // 1. Measure snapshot build time (includes sorted indexes).
    final long buildStart = System.nanoTime();
    catalog.bootstrap();
    final long buildElapsed = System.nanoTime() - buildStart;
    final double buildMs = buildElapsed / 1_000_000.0;

    // 2. Measure HashMap search latency.
    final Random searchRng = new Random(42);
    final long searchStart = System.nanoTime();
    for (int i = 0; i < SEARCH_ITERATIONS; i++) {
      final int indexChoice = searchRng.nextInt(3);
      switch (indexChoice) {
        case 0 -> catalog.search("by-category",
            CATEGORIES[searchRng.nextInt(CATEGORY_COUNT)]);
        case 1 -> catalog.search("by-region",
            REGIONS[searchRng.nextInt(REGION_COUNT)]);
        case 2 -> catalog.search("by-status",
            STATUSES[searchRng.nextInt(STATUS_COUNT)]);
        default -> throw new IllegalStateException();
      }
    }
    final long searchElapsed = System.nanoTime() - searchStart;
    final double avgSearchNs = (double) searchElapsed / SEARCH_ITERATIONS;

    // 3. Measure concurrent read throughput.
    final int readsPerThread = TOTAL_READS / THREAD_COUNT;
    final ExecutorService executor =
        Executors.newFixedThreadPool(THREAD_COUNT);
    final CountDownLatch ready = new CountDownLatch(THREAD_COUNT);
    final CountDownLatch start = new CountDownLatch(1);
    final CountDownLatch done = new CountDownLatch(THREAD_COUNT);
    final AtomicLong totalOps = new AtomicLong(0);

    for (int t = 0; t < THREAD_COUNT; t++) {
      final int threadSeed = t;
      executor.submit(() -> {
        final Random rng = new Random(threadSeed);
        ready.countDown();
        try {
          start.await();
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }

        long ops = 0;
        for (int i = 0; i < readsPerThread; i++) {
          final int indexChoice = rng.nextInt(3);
          switch (indexChoice) {
            case 0 -> catalog.search("by-category",
                CATEGORIES[rng.nextInt(CATEGORY_COUNT)]);
            case 1 -> catalog.search("by-region",
                REGIONS[rng.nextInt(REGION_COUNT)]);
            case 2 -> catalog.search("by-status",
                STATUSES[rng.nextInt(STATUS_COUNT)]);
            default -> { }
          }
          ops++;
        }
        totalOps.addAndGet(ops);
        done.countDown();
      });
    }

    ready.await();
    final long concurrentStart = System.nanoTime();
    start.countDown();
    done.await();
    final long concurrentElapsed = System.nanoTime() - concurrentStart;
    executor.shutdown();

    final double concurrentSeconds =
        concurrentElapsed / 1_000_000_000.0;
    final double opsPerSec = totalOps.get() / concurrentSeconds;

    // 4. Approximate memory footprint.
    System.gc();
    Thread.sleep(200);
    final Runtime runtime = Runtime.getRuntime();
    final long usedMemory = runtime.totalMemory() - runtime.freeMemory();
    final double usedMb = usedMemory / (1024.0 * 1024.0);

    System.out.printf(
        "%-12s | %14.2f ms | %18.0f ns | %18.0f ops/s | %10.1f MB%n",
        formatNumber(size), buildMs, avgSearchNs, opsPerSec, usedMb);
  }

  /**
   * Prints per-operation latency benchmarks for all Query DSL operations
   * across all dataset sizes.
   *
   * @throws Exception if any step fails
   */
  private static void printQueryDslBenchmarks() throws Exception {
    System.out.println(
        "----------------------------------------------------------");
    System.out.println(
        "  Query DSL Operations (avg latency per query, ns)");
    System.out.println(
        "----------------------------------------------------------");

    final String[] operations = {
        "equalTo", "between", "greaterThan", "greaterOrEqual",
        "lessThan", "lessOrEqual", "startsWith", "endsWith", "contains",
        "date:between", "date:startsWith", "date:contains"
    };

    final double[][] results =
        new double[operations.length][DATASET_SIZES.length];

    for (int s = 0; s < DATASET_SIZES.length; s++) {
      System.gc();
      Thread.sleep(200);

      final int size = DATASET_SIZES[s];
      final List<Item> data = generateData(size);
      final Catalog<Item> catalog = buildCatalog(data);
      catalog.bootstrap();

      results[0][s] = benchmarkEqualTo(catalog);
      results[1][s] = benchmarkBetween(catalog);
      results[2][s] = benchmarkGreaterThan(catalog);
      results[3][s] = benchmarkGreaterOrEqual(catalog);
      results[4][s] = benchmarkLessThan(catalog);
      results[5][s] = benchmarkLessOrEqual(catalog);
      results[6][s] = benchmarkStartsWith(catalog);
      results[7][s] = benchmarkEndsWith(catalog);
      results[8][s] = benchmarkContains(catalog);
      results[9][s] = benchmarkDateBetween(catalog);
      results[10][s] = benchmarkDateStartsWith(catalog);
      results[11][s] = benchmarkDateContains(catalog);
    }

    System.out.printf("%-18s |", "Operation");
    for (final int size : DATASET_SIZES) {
      System.out.printf(" %14s |", formatNumber(size));
    }
    System.out.println();

    System.out.print("-------------------|");
    for (int i = 0; i < DATASET_SIZES.length; i++) {
      System.out.print("----------------|");
    }
    System.out.println();

    for (int op = 0; op < operations.length; op++) {
      System.out.printf("%-18s |", operations[op]);
      for (int s = 0; s < DATASET_SIZES.length; s++) {
        System.out.printf(" %11.0f ns |", results[op][s]);
      }
      System.out.println();
    }
  }

  /**
   * Benchmarks equalTo on the sorted numeric index (HashMap O(1)).
   *
   * @param catalog the bootstrapped catalog
   *
   * @return the average latency in nanoseconds
   */
  private static double benchmarkEqualTo(final Catalog<Item> catalog) {
    final Random rng = new Random(42);
    final long start = System.nanoTime();
    for (int i = 0; i < SEARCH_ITERATIONS; i++) {
      catalog.query("by-score").equalTo(rng.nextInt(SCORE_COUNT));
    }
    return (double) (System.nanoTime() - start) / SEARCH_ITERATIONS;
  }

  /**
   * Benchmarks between on the sorted numeric index (TreeMap subMap).
   * Uses a range of 20 score values per query.
   *
   * @param catalog the bootstrapped catalog
   *
   * @return the average latency in nanoseconds
   */
  private static double benchmarkBetween(final Catalog<Item> catalog) {
    final Random rng = new Random(42);
    final long start = System.nanoTime();
    for (int i = 0; i < SEARCH_ITERATIONS; i++) {
      final int from = rng.nextInt(SCORE_COUNT - 20);
      catalog.query("by-score").between(from, from + 20);
    }
    return (double) (System.nanoTime() - start) / SEARCH_ITERATIONS;
  }

  /**
   * Benchmarks greaterThan on the sorted numeric index (TreeMap tailMap).
   * Queries near the top of the score range for a small result set (~20
   * buckets).
   *
   * @param catalog the bootstrapped catalog
   *
   * @return the average latency in nanoseconds
   */
  private static double benchmarkGreaterThan(final Catalog<Item> catalog) {
    final Random rng = new Random(42);
    final long start = System.nanoTime();
    for (int i = 0; i < SEARCH_ITERATIONS; i++) {
      catalog.query("by-score")
          .greaterThan(SCORE_COUNT - 20 - rng.nextInt(5));
    }
    return (double) (System.nanoTime() - start) / SEARCH_ITERATIONS;
  }

  /**
   * Benchmarks greaterOrEqual on the sorted numeric index
   * (TreeMap tailMap). Queries near the top of the score range.
   *
   * @param catalog the bootstrapped catalog
   *
   * @return the average latency in nanoseconds
   */
  private static double benchmarkGreaterOrEqual(
      final Catalog<Item> catalog) {
    final Random rng = new Random(42);
    final long start = System.nanoTime();
    for (int i = 0; i < SEARCH_ITERATIONS; i++) {
      catalog.query("by-score")
          .greaterOrEqual(SCORE_COUNT - 20 - rng.nextInt(5));
    }
    return (double) (System.nanoTime() - start) / SEARCH_ITERATIONS;
  }

  /**
   * Benchmarks lessThan on the sorted numeric index (TreeMap headMap).
   * Queries near the bottom of the score range for a small result set
   * (~20 buckets).
   *
   * @param catalog the bootstrapped catalog
   *
   * @return the average latency in nanoseconds
   */
  private static double benchmarkLessThan(final Catalog<Item> catalog) {
    final Random rng = new Random(42);
    final long start = System.nanoTime();
    for (int i = 0; i < SEARCH_ITERATIONS; i++) {
      catalog.query("by-score").lessThan(20 + rng.nextInt(5));
    }
    return (double) (System.nanoTime() - start) / SEARCH_ITERATIONS;
  }

  /**
   * Benchmarks lessOrEqual on the sorted numeric index (TreeMap headMap).
   * Queries near the bottom of the score range.
   *
   * @param catalog the bootstrapped catalog
   *
   * @return the average latency in nanoseconds
   */
  private static double benchmarkLessOrEqual(final Catalog<Item> catalog) {
    final Random rng = new Random(42);
    final long start = System.nanoTime();
    for (int i = 0; i < SEARCH_ITERATIONS; i++) {
      catalog.query("by-score").lessOrEqual(20 + rng.nextInt(5));
    }
    return (double) (System.nanoTime() - start) / SEARCH_ITERATIONS;
  }

  /**
   * Benchmarks startsWith on the sorted String index (TreeMap subMap).
   * Uses prefixes like "category-X" that match ~1-11 keys.
   *
   * @param catalog the bootstrapped catalog
   *
   * @return the average latency in nanoseconds
   */
  private static double benchmarkStartsWith(final Catalog<Item> catalog) {
    final Random rng = new Random(42);
    final long start = System.nanoTime();
    for (int i = 0; i < SEARCH_ITERATIONS; i++) {
      catalog.query("by-category-sorted")
          .startsWith("category-" + rng.nextInt(10));
    }
    return (double) (System.nanoTime() - start) / SEARCH_ITERATIONS;
  }

  /**
   * Benchmarks endsWith on the sorted String index (reversed TreeMap
   * subMap). Uses suffixes like "-X" that match ~10 keys.
   *
   * @param catalog the bootstrapped catalog
   *
   * @return the average latency in nanoseconds
   */
  private static double benchmarkEndsWith(final Catalog<Item> catalog) {
    final Random rng = new Random(42);
    final long start = System.nanoTime();
    for (int i = 0; i < SEARCH_ITERATIONS; i++) {
      catalog.query("by-category-sorted")
          .endsWith("-" + rng.nextInt(10));
    }
    return (double) (System.nanoTime() - start) / SEARCH_ITERATIONS;
  }

  /**
   * Benchmarks contains on the sorted String index (full key scan).
   * Uses substrings like "gory-X" that match ~1-11 keys.
   *
   * @param catalog the bootstrapped catalog
   *
   * @return the average latency in nanoseconds
   */
  private static double benchmarkContains(final Catalog<Item> catalog) {
    final Random rng = new Random(42);
    final long start = System.nanoTime();
    for (int i = 0; i < SEARCH_ITERATIONS; i++) {
      catalog.query("by-category-sorted")
          .contains("gory-" + rng.nextInt(10));
    }
    return (double) (System.nanoTime() - start) / SEARCH_ITERATIONS;
  }

  /**
   * Benchmarks between on the date index (TreeMap subMap with ISO strings).
   * Uses a 90-day range per query (~3 months).
   *
   * @param catalog the bootstrapped catalog
   *
   * @return the average latency in nanoseconds
   */
  private static double benchmarkDateBetween(final Catalog<Item> catalog) {
    final Random rng = new Random(42);
    final long start = System.nanoTime();
    for (int i = 0; i < SEARCH_ITERATIONS; i++) {
      final int fromIdx = rng.nextInt(DATE_COUNT - 90);
      catalog.query("by-date")
          .between(DATES[fromIdx], DATES[fromIdx + 90]);
    }
    return (double) (System.nanoTime() - start) / SEARCH_ITERATIONS;
  }

  /**
   * Benchmarks startsWith on the date index (TreeMap subMap).
   * Uses year-month prefixes like "2023-06" to find all dates in a month.
   *
   * @param catalog the bootstrapped catalog
   *
   * @return the average latency in nanoseconds
   */
  private static double benchmarkDateStartsWith(
      final Catalog<Item> catalog) {
    final Random rng = new Random(42);
    final long start = System.nanoTime();
    for (int i = 0; i < SEARCH_ITERATIONS; i++) {
      catalog.query("by-date")
          .startsWith(YEAR_MONTHS[rng.nextInt(YEAR_MONTHS.length)]);
    }
    return (double) (System.nanoTime() - start) / SEARCH_ITERATIONS;
  }

  /**
   * Benchmarks contains on the date index (full key scan).
   * Uses day-of-month patterns like "-15" to find all 15th dates.
   *
   * @param catalog the bootstrapped catalog
   *
   * @return the average latency in nanoseconds
   */
  private static double benchmarkDateContains(
      final Catalog<Item> catalog) {
    final Random rng = new Random(42);
    final long start = System.nanoTime();
    for (int i = 0; i < SEARCH_ITERATIONS; i++) {
      final int day = rng.nextInt(28) + 1;
      catalog.query("by-date")
          .contains("-" + String.format("%02d", day));
    }
    return (double) (System.nanoTime() - start) / SEARCH_ITERATIONS;
  }

  /**
   * Generates a list of items with deterministic pseudo-random distribution.
   *
   * @param size the number of items to generate
   *
   * @return the generated list, never null
   */
  private static List<Item> generateData(final int size) {
    final Random rng = new Random(12345);
    final List<Item> items = new ArrayList<>(size);
    for (int i = 0; i < size; i++) {
      items.add(new Item(
          "item-" + i,
          CATEGORIES[rng.nextInt(CATEGORY_COUNT)],
          REGIONS[rng.nextInt(REGION_COUNT)],
          STATUSES[rng.nextInt(STATUS_COUNT)],
          rng.nextInt(SCORE_COUNT),
          DATES[rng.nextInt(DATE_COUNT)]
      ));
    }
    return items;
  }

  /**
   * Builds a Catalog with three regular indices and three sorted indices.
   *
   * <p>Regular indices (HashMap): by-category, by-region, by-status.
   * <p>Sorted indices (HashMap + TreeMap): by-score (Integer keys),
   * by-category-sorted (String keys with reversed TreeMap for endsWith),
   * by-date (String keys in ISO format for date range and pattern queries).
   *
   * @param data the data items
   *
   * @return a new catalog ready to bootstrap
   */
  private static Catalog<Item> buildCatalog(final List<Item> data) {
    return Catalog.of(Item.class)
        .named("benchmark")
        .data(data)
        .index("by-category").by(Item::category, Function.identity())
        .index("by-region").by(Item::region, Function.identity())
        .index("by-status").by(Item::status, Function.identity())
        .indexSorted("by-score")
            .by(item -> item.score(), Function.<Integer>identity())
        .indexSorted("by-category-sorted")
            .by(Item::category, Function.identity())
        .indexSorted("by-date")
            .by(Item::date, Function.identity())
        .build();
  }

  /**
   * Formats a number with comma separators for readability.
   *
   * @param number the number to format
   *
   * @return the formatted string
   */
  private static String formatNumber(final int number) {
    return String.format("%,d", number);
  }
}
