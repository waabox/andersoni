package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.waabox.andersoni.sync.RefreshEvent;
import org.waabox.andersoni.sync.RefreshListener;
import org.waabox.andersoni.sync.SyncStrategy;

/**
 * End-to-end integration tests for the refresh-event debouncer and the
 * dispatcher's rerun flag.
 *
 * <p>Each test wires a real {@link Andersoni} instance with a hand-rolled
 * in-process {@link SyncStrategy} that lets the test drive
 * {@link RefreshEvent} arrivals directly. Catalog data loaders are
 * instrumented to count invocations and (in some tests) block on a latch
 * so we can simulate a slow refresh and observe events that arrive while
 * one is in flight.
 */
class RefreshDebounceIntegrationTest {

  /** A tiny domain record used for the catalog under test. */
  record Item(String id) {}

  @Test
  void whenBurstOfEvents_givenDebounceWindow_shouldLoadOnce()
      throws Exception {
    final TestSyncStrategy sync = new TestSyncStrategy();
    final AtomicInteger loads = new AtomicInteger();

    final Catalog<Item> catalog = Catalog.of(Item.class)
        .named("items")
        .loadWith(() -> {
          loads.incrementAndGet();
          return List.of(new Item("a"));
        })
        .index("by-id").by(Item::id, java.util.function.Function.identity())
        .build();

    final Andersoni andersoni = Andersoni.builder()
        .nodeId("local")
        .syncStrategy(sync)
        .debouncePolicy(DebouncePolicy.of(Duration.ofMillis(150)))
        .build();
    andersoni.register(catalog);
    andersoni.start();

    final int loadsAfterBootstrap = loads.get();

    // Fire 20 events back-to-back; debouncer should coalesce them all into
    // a single dispatch after the 150ms window.
    for (int i = 0; i < 20; i++) {
      sync.fire(event("items", "leader", i + 1, "h-" + i));
    }

    waitFor(() -> loads.get() > loadsAfterBootstrap, 1500);
    Thread.sleep(200);

    assertEquals(loadsAfterBootstrap + 1, loads.get(),
        "expected a single coalesced refresh for the burst");

    andersoni.stop();
  }

  @Test
  void whenSustainedTraffic_givenMaxWaitCap_shouldFireWithinCap()
      throws Exception {
    final TestSyncStrategy sync = new TestSyncStrategy();
    final AtomicInteger loads = new AtomicInteger();

    final Catalog<Item> catalog = Catalog.of(Item.class)
        .named("items")
        .loadWith(() -> {
          loads.incrementAndGet();
          return List.of(new Item("a"));
        })
        .index("by-id").by(Item::id, java.util.function.Function.identity())
        .build();

    final Andersoni andersoni = Andersoni.builder()
        .nodeId("local")
        .syncStrategy(sync)
        .debouncePolicy(DebouncePolicy.of(
            Duration.ofMillis(80), Duration.ofMillis(250)))
        .build();
    andersoni.register(catalog);
    andersoni.start();

    final int loadsAfterBootstrap = loads.get();
    final long start = System.currentTimeMillis();

    // Feed events faster than the window for ~600ms. The window keeps
    // getting pushed out, but maxWait must force a fire within ~250ms of
    // the first event.
    final Thread feeder = new Thread(() -> {
      int i = 0;
      while (!Thread.currentThread().isInterrupted()
          && System.currentTimeMillis() - start < 600) {
        sync.fire(event("items", "leader", ++i, "h-" + i));
        try {
          Thread.sleep(20);
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    });
    feeder.setDaemon(true);
    feeder.start();

    waitFor(() -> loads.get() > loadsAfterBootstrap, 1000);
    final long firedAfter = System.currentTimeMillis() - start;
    feeder.interrupt();
    feeder.join(500);

    assertTrue(firedAfter <= 450,
        "expected a refresh within ~maxWait, got " + firedAfter + "ms");
    assertTrue(loads.get() > loadsAfterBootstrap,
        "expected at least one extra refresh under sustained traffic");

    andersoni.stop();
  }

  @Test
  void whenZeroWindow_givenSeparatedEvents_shouldRunPerEvent()
      throws Exception {
    final TestSyncStrategy sync = new TestSyncStrategy();
    final AtomicInteger loads = new AtomicInteger();

    final Catalog<Item> catalog = Catalog.of(Item.class)
        .named("items")
        .loadWith(() -> {
          loads.incrementAndGet();
          return List.of(new Item("a"));
        })
        .index("by-id").by(Item::id, java.util.function.Function.identity())
        .build();

    final Andersoni andersoni = Andersoni.builder()
        .nodeId("local")
        .syncStrategy(sync)
        .build();
    andersoni.register(catalog);
    andersoni.start();

    final int loadsAfterBootstrap = loads.get();

    // With a zero window the debouncer is pass-through and the dispatcher
    // serialises one refresh at a time per catalog. Spacing events out so
    // each sees pending=false guarantees one refresh per event.
    sync.fire(event("items", "leader", 1, "h-1"));
    waitFor(() -> loads.get() == loadsAfterBootstrap + 1, 1000);

    sync.fire(event("items", "leader", 2, "h-2"));
    waitFor(() -> loads.get() == loadsAfterBootstrap + 2, 1000);

    sync.fire(event("items", "leader", 3, "h-3"));
    waitFor(() -> loads.get() == loadsAfterBootstrap + 3, 1000);

    assertEquals(loadsAfterBootstrap + 3, loads.get());

    andersoni.stop();
  }

  @Test
  void whenEventArrivesDuringSlowRefresh_givenRerunFlag_shouldRunAgain()
      throws Exception {
    final TestSyncStrategy sync = new TestSyncStrategy();
    final AtomicInteger loads = new AtomicInteger();
    final AtomicReference<CountDownLatch> blockOn =
        new AtomicReference<>(null);
    final CountDownLatch firstLoadStarted = new CountDownLatch(1);

    final Catalog<Item> catalog = Catalog.of(Item.class)
        .named("items")
        .loadWith(() -> {
          final int n = loads.incrementAndGet();
          if (n == 2) {
            firstLoadStarted.countDown();
            try {
              final CountDownLatch latch = blockOn.get();
              if (latch != null) {
                latch.await(2, TimeUnit.SECONDS);
              }
            } catch (final InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
          return List.of(new Item("a"));
        })
        .index("by-id").by(Item::id, java.util.function.Function.identity())
        .build();

    final Andersoni andersoni = Andersoni.builder()
        .nodeId("local")
        .syncStrategy(sync)
        .build();
    andersoni.register(catalog);
    andersoni.start();
    // Bootstrap counts as load #1.
    assertEquals(1, loads.get());

    // Arm the latch so the next load call blocks.
    final CountDownLatch release = new CountDownLatch(1);
    blockOn.set(release);

    // Trigger load #2 (will block).
    sync.fire(event("items", "leader", 1, "h-1"));
    assertTrue(firstLoadStarted.await(2, TimeUnit.SECONDS));

    // Pile on while load #2 is held. Dispatcher should set rerun=true so
    // exactly one extra load runs after #2 completes.
    blockOn.set(null);
    for (int i = 0; i < 25; i++) {
      sync.fire(event("items", "leader", 2 + i, "h-burst-" + i));
    }

    release.countDown();

    waitFor(() -> loads.get() >= 3, 2000);
    Thread.sleep(200);

    assertEquals(3, loads.get(),
        "expected exactly one rerun after the in-flight refresh");

    andersoni.stop();
  }

  @Test
  void whenTwoCatalogs_givenIndependentBursts_shouldDebouncePerCatalog()
      throws Exception {
    final TestSyncStrategy sync = new TestSyncStrategy();
    final AtomicInteger loadsA = new AtomicInteger();
    final AtomicInteger loadsB = new AtomicInteger();

    final Catalog<Item> catA = Catalog.of(Item.class)
        .named("a")
        .loadWith(() -> {
          loadsA.incrementAndGet();
          return List.of(new Item("a"));
        })
        .index("by-id").by(Item::id, java.util.function.Function.identity())
        .build();
    final Catalog<Item> catB = Catalog.of(Item.class)
        .named("b")
        .loadWith(() -> {
          loadsB.incrementAndGet();
          return List.of(new Item("b"));
        })
        .index("by-id").by(Item::id, java.util.function.Function.identity())
        .build();

    final Andersoni andersoni = Andersoni.builder()
        .nodeId("local")
        .syncStrategy(sync)
        .debouncePolicy(DebouncePolicy.of(Duration.ofMillis(120)))
        .build();
    andersoni.register(catA);
    andersoni.register(catB);
    andersoni.start();

    final int aBootstrap = loadsA.get();
    final int bBootstrap = loadsB.get();

    for (int i = 0; i < 10; i++) {
      sync.fire(event("a", "leader", i + 1, "ah-" + i));
      sync.fire(event("b", "leader", i + 1, "bh-" + i));
    }

    waitFor(() -> loadsA.get() > aBootstrap && loadsB.get() > bBootstrap,
        1500);
    Thread.sleep(200);

    assertEquals(aBootstrap + 1, loadsA.get(), "catalog a coalesces to one");
    assertEquals(bBootstrap + 1, loadsB.get(), "catalog b coalesces to one");

    andersoni.stop();
  }

  private static RefreshEvent event(final String catalogName,
      final String sourceNodeId, final long version, final String hash) {
    return new RefreshEvent(catalogName, sourceNodeId, version, hash,
        Instant.now());
  }

  private static void waitFor(
      final java.util.function.BooleanSupplier check,
      final long timeoutMillis) throws InterruptedException {
    final long deadline = System.currentTimeMillis() + timeoutMillis;
    while (!check.getAsBoolean()
        && System.currentTimeMillis() < deadline) {
      TimeUnit.MILLISECONDS.sleep(5);
    }
  }

  /**
   * In-process sync strategy that lets a test fire RefreshEvents directly
   * to subscribed listeners. Publish is a no-op since there is only one
   * node in the test.
   */
  private static final class TestSyncStrategy implements SyncStrategy {

    private final List<RefreshListener> listeners = new ArrayList<>();

    @Override
    public void publish(final RefreshEvent event) {
      // No-op: tests drive listeners directly via fire().
    }

    @Override
    public void subscribe(final RefreshListener listener) {
      listeners.add(listener);
    }

    @Override
    public void start() {
      // No-op.
    }

    @Override
    public void stop() {
      listeners.clear();
    }

    void fire(final RefreshEvent event) {
      for (final RefreshListener l : listeners) {
        l.onRefresh(event);
      }
    }
  }
}
