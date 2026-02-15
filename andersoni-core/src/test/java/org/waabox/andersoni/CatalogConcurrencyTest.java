package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Concurrency tests for {@link Catalog}.
 *
 * <p>Verifies that multiple reader threads can search while a writer thread
 * refreshes, and no reader ever observes a partial or inconsistent state.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class CatalogConcurrencyTest {

  /** A category domain object used for testing. */
  record Category(String name) {
    @Override
    public String toString() {
      return name;
    }
  }

  /** An item domain object used for testing. */
  record Item(String id, Category category) {
    @Override
    public String toString() {
      return id + ":" + category.name();
    }
  }

  /** The number of reader threads to run concurrently. */
  private static final int READER_COUNT = 8;

  /** The number of read iterations each reader performs. */
  private static final int READS_PER_READER = 1000;

  /** The number of refresh iterations the writer performs. */
  private static final int REFRESH_COUNT = 100;

  @Test
  void whenSearchingDuringRefresh_givenConcurrentReaders_shouldNeverSeePartialState()
      throws Exception {

    final Category electronics = new Category("Electronics");
    final Category books = new Category("Books");

    // The data loader alternates between two completely different datasets.
    // Dataset 0 has items E1, E2 in "Electronics".
    // Dataset 1 has items B1, B2 in "Books".
    // A partial/inconsistent state would mix items from both datasets.
    final AtomicInteger loadCount = new AtomicInteger(0);

    final DataLoader<Item> loader = () -> {
      final int count = loadCount.getAndIncrement();
      if (count % 2 == 0) {
        return List.of(
            new Item("E1", electronics),
            new Item("E2", electronics)
        );
      } else {
        return List.of(
            new Item("B1", books),
            new Item("B2", books)
        );
      }
    };

    final Catalog<Item> catalog = Catalog.of(Item.class)
        .named("concurrent-test")
        .loadWith(loader)
        .index("by-category").by(Item::category, Category::name)
        .build();

    // Bootstrap with the first dataset (Electronics).
    catalog.bootstrap();

    final CountDownLatch startLatch = new CountDownLatch(1);
    final AtomicBoolean inconsistencyDetected = new AtomicBoolean(false);
    final List<String> inconsistencyDetails = new ArrayList<>();

    final ExecutorService executor = Executors.newFixedThreadPool(
        READER_COUNT + 1);

    final List<Future<?>> futures = new ArrayList<>();

    // Launch reader threads.
    for (int r = 0; r < READER_COUNT; r++) {
      final int readerId = r;
      futures.add(executor.submit(() -> {
        try {
          startLatch.await();
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }

        for (int i = 0; i < READS_PER_READER; i++) {
          // Take a consistent view via currentSnapshot.
          final Snapshot<Item> snapshot = catalog.currentSnapshot();
          final List<Item> data = snapshot.data();

          if (data.isEmpty()) {
            continue;
          }

          // All items in a single snapshot must belong to the same category.
          final String firstCategory = data.get(0).category().name();
          for (int j = 1; j < data.size(); j++) {
            final String itemCategory = data.get(j).category().name();
            if (!firstCategory.equals(itemCategory)) {
              inconsistencyDetected.set(true);
              synchronized (inconsistencyDetails) {
                inconsistencyDetails.add(
                    "Reader " + readerId + " iteration " + i
                        + ": mixed categories [" + firstCategory
                        + ", " + itemCategory + "] in snapshot v"
                        + snapshot.version()
                );
              }
              return;
            }
          }

          // Also verify search results are consistent with the snapshot data.
          final List<Item> searchResult = catalog.search(
              "by-category", firstCategory);

          // The search result might come from a different snapshot if a
          // refresh happened between currentSnapshot() and search(). That is
          // acceptable. What matters is that the search result itself is
          // internally consistent: all returned items share the same category.
          for (final Item item : searchResult) {
            if (!item.category().name().equals(firstCategory)) {
              inconsistencyDetected.set(true);
              synchronized (inconsistencyDetails) {
                inconsistencyDetails.add(
                    "Reader " + readerId + " iteration " + i
                        + ": search returned item " + item.id()
                        + " with category " + item.category().name()
                        + " but searched for " + firstCategory
                );
              }
              return;
            }
          }
        }
      }));
    }

    // Launch writer thread.
    futures.add(executor.submit(() -> {
      try {
        startLatch.await();
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }

      for (int i = 0; i < REFRESH_COUNT; i++) {
        catalog.refresh();
      }
    }));

    // Release all threads simultaneously.
    startLatch.countDown();

    // Wait for all futures to complete.
    for (final Future<?> future : futures) {
      future.get(30, TimeUnit.SECONDS);
    }

    executor.shutdown();
    final boolean terminated = executor.awaitTermination(10, TimeUnit.SECONDS);
    assertTrue(terminated, "Executor did not terminate in time");

    assertFalse(inconsistencyDetected.get(),
        "Detected inconsistent snapshot state: " + inconsistencyDetails);
  }
}
