package org.waabox.andersoni;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.waabox.andersoni.snapshot.SnapshotSerializer;

/**
 * Measures what a patch costs against what it replaces: a full snapshot
 * rebuild from data already in memory.
 *
 * <p>The comparison deliberately excludes the DataLoader. A real refresh also
 * re-reads the whole dataset from its source, which is usually the dominant
 * cost and which a patch avoids entirely; including it would flatter the
 * patch without telling us anything we did not already know. What is left is
 * the part worth measuring — the index work — plus the costs a patch does not
 * avoid.
 *
 * <p>Two of those are unavoidable today and are reported separately so the
 * ceiling is visible: the content hash serializes the whole dataset on every
 * change, and the item list is copied by reference. A patch cannot be faster
 * than the hash line no matter how little index work it does.
 *
 * <p>This is a hand-rolled harness, not JMH: no dead-code elimination
 * guards, no fork isolation, no statistical rigour beyond a median. Treat the
 * numbers as ratios and orders of magnitude, not as precise figures.
 *
 * @author borafael
 */
public final class PatchBenchmark {

  /** Dataset sizes to measure. */
  private static final int[] SIZES = {10_000, 100_000, 500_000};

  /** Distinct category values, so buckets hold many items. */
  private static final int CATEGORIES = 100;

  /** Distinct region values. */
  private static final int REGIONS = 50;

  /** Operations timed per measurement, for the cheap operations. */
  private static final int PATCH_ITERATIONS = 200;

  /** Operations timed per measurement, for the expensive ones. */
  private static final int REBUILD_ITERATIONS = 20;

  /** Operations in a measured batch. */
  private static final int BATCH_SIZE = 10;

  /** Private constructor: this is an entry point. */
  private PatchBenchmark() {
  }

  /** An item with enough shape to exercise every index type. */
  record Item(String id, String category, String region, long score) {
  }

  /** A projection, so view materialization is part of the measurement. */
  record ItemView(String id, String category) {
    static ItemView from(final Item item) {
      return new ItemView(item.id(), item.category());
    }
  }

  /** Line-oriented serializer: deterministic, and cheap enough not to
   *  dominate the hash measurement by itself. */
  static final class ItemSerializer implements SnapshotSerializer<Item> {
    @Override
    public byte[] serialize(final List<Item> items) {
      final StringBuilder builder = new StringBuilder(items.size() * 32);
      for (final Item item : items) {
        builder.append(item.id()).append('|').append(item.category())
            .append('|').append(item.region()).append('|')
            .append(item.score()).append('\n');
      }
      return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public List<Item> deserialize(final byte[] data) {
      final List<Item> items = new ArrayList<>();
      for (final String line : new String(data, StandardCharsets.UTF_8)
          .split("\n")) {
        if (line.isBlank()) {
          continue;
        }
        final String[] parts = line.split("\\|");
        items.add(new Item(parts[0], parts[1], parts[2],
            Long.parseLong(parts[3])));
      }
      return items;
    }
  }

  /**
   * Runs the benchmark.
   *
   * @param args ignored.
   */
  public static void main(final String[] args) {
    System.out.println("Patch vs rebuild — cost of one change");
    System.out.println("(rebuild excludes the DataLoader; a real refresh "
        + "also re-reads the dataset)");
    System.out.println();

    warmup();

    System.out.printf("%-10s %12s %12s %12s %12s %12s%n",
        "items", "rebuild", "patch", "batch/10", "patch(hook)", "hash only");
    System.out.printf("%-10s %12s %12s %12s %12s %12s%n",
        "", "ms", "ms", "ms", "ms", "ms");

    for (final int size : SIZES) {
      final List<Item> data = generate(size);

      final double rebuild = median(measure(REBUILD_ITERATIONS, () -> {
        final Catalog<Item> catalog = catalog(data, true);
        return () -> catalog.refresh(data);
      }));
      final double patch = median(measure(PATCH_ITERATIONS, () -> {
        final Catalog<Item> catalog = catalog(data, true);
        final int[] n = {0};
        return () -> catalog.replace("by-id", "id-0",
            new Item("id-0", "cat-1", "reg-1", n[0]++));
      }));
      final double batch = median(measure(REBUILD_ITERATIONS, () -> {
        final Catalog<Item> catalog = catalog(data, true);
        final int[] n = {0};
        return () -> catalog.apply(batchOps(n[0]++));
      }));
      final double hooked = median(measure(REBUILD_ITERATIONS, () -> {
        final Catalog<Item> catalog = catalog(data, false);
        final int[] n = {0};
        return () -> catalog.replace("by-id", "id-0",
            new Item("id-0", "cat-1", "reg-1", n[0]++));
      }));
      final double hash = median(measure(REBUILD_ITERATIONS, () -> {
        final ItemSerializer serializer = new ItemSerializer();
        return () -> sha256(serializer.serialize(data));
      }));

      System.out.printf("%-10d %12.2f %12.2f %12.2f %12.2f %12.2f%n",
          size, rebuild, patch, batch, hooked, hash);
    }

    System.out.println();
    System.out.println("patch(hook) = a catalog with a hook that has not "
        + "opted into per-item execution, so patches rebuild.");
    System.out.println("hash only   = serialize the dataset and SHA-256 it: "
        + "the floor every patch pays.");
  }

  /**
   * Builds the operations of a measured batch: five replaces and five
   * inserts, so both sides of the machinery are exercised.
   *
   * @param seed distinguishes one batch from the next.
   *
   * @return the operations, never null.
   */
  private static List<PatchOp<Item>> batchOps(final int seed) {
    final List<PatchOp<Item>> ops = new ArrayList<>(BATCH_SIZE);
    for (int i = 0; i < BATCH_SIZE / 2; i++) {
      ops.add(new PatchOp.Replace<>("by-id", "id-" + i,
          new Item("id-" + i, "cat-1", "reg-1", seed)));
      ops.add(new PatchOp.Insert<>(
          new Item("new-" + seed + "-" + i, "cat-2", "reg-2", seed)));
    }
    return ops;
  }

  /**
   * Times an operation, returning the per-operation milliseconds of each
   * iteration.
   *
   * @param iterations how many times to run it.
   * @param setup      produces a fresh operation bound to fresh state.
   *
   * @return the per-iteration timings in milliseconds, never null.
   */
  private static double[] measure(final int iterations,
      final java.util.function.Supplier<Runnable> setup) {
    final Runnable operation = setup.get();
    final double[] timings = new double[iterations];
    for (int i = 0; i < iterations; i++) {
      final long start = System.nanoTime();
      operation.run();
      timings[i] = (System.nanoTime() - start) / 1_000_000.0;
    }
    return timings;
  }

  /**
   * Returns the median of the given timings, which discards the warm-up
   * outliers a mean would carry.
   *
   * @param timings the timings, never null.
   *
   * @return the median.
   */
  private static double median(final double[] timings) {
    final double[] sorted = timings.clone();
    Arrays.sort(sorted);
    return sorted[sorted.length / 2];
  }

  /**
   * Builds a catalog over the given data.
   *
   * @param data      the items, never null.
   * @param patchable whether the registered hook opts into per-item
   *                  execution.
   *
   * @return a bootstrapped catalog, never null.
   */
  private static Catalog<Item> catalog(final List<Item> data,
      final boolean patchable) {
    final Catalog<Item> catalog = Catalog.of(Item.class)
        .named("items")
        .data(data)
        .serializer(new ItemSerializer())
        .index("by-id").by(Item::id, id -> id)
        .index("by-category").by(Item::category, c -> c)
        .indexSorted("by-score").by(Item::score, score -> score)
        .view(ItemView.class, ItemView::from)
        .hook(new SnapshotBuildHook<Item>() {
          @Override
          public Item process(final Item item) {
            return item;
          }

          @Override
          public boolean supportsPatch() {
            return patchable;
          }
        })
        .build();
    catalog.bootstrap();
    return catalog;
  }

  /**
   * Generates a dataset.
   *
   * @param size how many items.
   *
   * @return the items, never null.
   */
  private static List<Item> generate(final int size) {
    final Random random = new Random(42);
    final List<Item> items = new ArrayList<>(size);
    for (int i = 0; i < size; i++) {
      items.add(new Item("id-" + i,
          "cat-" + random.nextInt(CATEGORIES),
          "reg-" + random.nextInt(REGIONS),
          random.nextInt(1_000)));
    }
    return items;
  }

  /**
   * Digests the given bytes, mirroring what the catalog's content hash does
   * on every change.
   *
   * @param bytes the bytes, never null.
   */
  private static void sha256(final byte[] bytes) {
    try {
      MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (final Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** Runs enough work to get the JIT past the interpreter. */
  private static void warmup() {
    System.out.println("warming up...");
    final List<Item> data = generate(5_000);
    for (int round = 0; round < 5; round++) {
      final Catalog<Item> catalog = catalog(data, true);
      for (int i = 0; i < 200; i++) {
        catalog.replace("by-id", "id-0", new Item("id-0", "cat-1", "reg-1", i));
      }
      catalog.refresh(data);
      catalog.apply(batchOps(round));
      sha256(new ItemSerializer().serialize(data));
    }
    System.out.println();
  }
}
