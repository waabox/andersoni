package org.waabox.andersoni;

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
 * <p>Measures snapshot build time, search latency, concurrent read throughput,
 * and approximate memory footprint across different dataset sizes.
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
  }

  /** A simple item record for benchmarking. */
  record Item(String id, String category, String region, String status) {

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
    System.out.println("==========================================================");
    System.out.println("  Andersoni Benchmark");
    System.out.println("==========================================================");
    System.out.println();

    // Warmup JVM with a small dataset
    System.out.println("Warming up JVM...");
    final List<Item> warmupData = generateData(1_000);
    final Catalog<Item> warmupCatalog = buildCatalog(warmupData);
    warmupCatalog.bootstrap();
    for (int i = 0; i < 10_000; i++) {
      warmupCatalog.search("by-category", CATEGORIES[i % CATEGORY_COUNT]);
      warmupCatalog.search("by-region", REGIONS[i % REGION_COUNT]);
      warmupCatalog.search("by-status", STATUSES[i % STATUS_COUNT]);
    }
    System.out.println("Warmup complete.");
    System.out.println();

    // Header
    System.out.printf("%-12s | %-18s | %-22s | %-22s | %-14s%n",
        "Items", "Build Time", "Avg Search Latency",
        "Concurrent Throughput", "Memory (approx)");
    System.out.println(
        "-------------|--------------------"
        + "|------------------------"
        + "|------------------------"
        + "|----------------");

    for (final int size : DATASET_SIZES) {
      runBenchmark(size);
    }

    System.out.println();
    System.out.println("==========================================================");
    System.out.println("  Benchmark complete");
    System.out.println("==========================================================");
  }

  /**
   * Runs the full benchmark suite for a given dataset size.
   *
   * @param size the number of items in the dataset
   *
   * @throws Exception if any step fails
   */
  private static void runBenchmark(final int size) throws Exception {
    // Force GC before measuring
    System.gc();
    Thread.sleep(200);

    final List<Item> data = generateData(size);

    // 1. Measure snapshot build time
    final Catalog<Item> catalog = buildCatalog(data);

    final long buildStart = System.nanoTime();
    catalog.bootstrap();
    final long buildElapsed = System.nanoTime() - buildStart;
    final double buildMs = buildElapsed / 1_000_000.0;

    // 2. Measure search latency (average over SEARCH_ITERATIONS searches)
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

    // 3. Measure concurrent read throughput
    final int readsPerThread = TOTAL_READS / THREAD_COUNT;
    final ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
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

    final double concurrentSeconds = concurrentElapsed / 1_000_000_000.0;
    final double opsPerSec = totalOps.get() / concurrentSeconds;

    // 4. Approximate memory footprint
    System.gc();
    Thread.sleep(200);
    final Runtime runtime = Runtime.getRuntime();
    final long usedMemory = runtime.totalMemory() - runtime.freeMemory();
    final double usedMb = usedMemory / (1024.0 * 1024.0);

    // Print row
    System.out.printf("%-12s | %14.2f ms | %18.0f ns | %18.0f ops/s | %10.1f MB%n",
        formatNumber(size), buildMs, avgSearchNs, opsPerSec, usedMb);
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
          STATUSES[rng.nextInt(STATUS_COUNT)]
      ));
    }
    return items;
  }

  /**
   * Builds a Catalog with three indices for the given data.
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
