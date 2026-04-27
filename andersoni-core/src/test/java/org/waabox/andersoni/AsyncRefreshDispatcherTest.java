package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Andersoni.AsyncRefreshDispatcher}.
 *
 * <p>These tests focus on the rerun-flag behavior: events that arrive while
 * a refresh is in flight must trigger exactly one follow-up refresh after
 * the running one finishes (no matter how many events arrived), instead of
 * being silently dropped.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class AsyncRefreshDispatcherTest {

  @Test
  void whenSingleDispatch_givenIdleDispatcher_shouldRunOnce()
      throws Exception {
    final Andersoni.AsyncRefreshDispatcher dispatcher =
        new Andersoni.AsyncRefreshDispatcher(setOf("c"));
    final AtomicInteger runs = new AtomicInteger();
    final CountDownLatch done = new CountDownLatch(1);

    dispatcher.dispatch("c", () -> {
      runs.incrementAndGet();
      done.countDown();
    });

    assertTrue(done.await(2, TimeUnit.SECONDS));
    assertEquals(1, runs.get());
  }

  @Test
  void whenDispatchDuringRunningRefresh_givenManyEvents_shouldRerunOnce()
      throws Exception {
    final Andersoni.AsyncRefreshDispatcher dispatcher =
        new Andersoni.AsyncRefreshDispatcher(setOf("c"));
    final AtomicInteger runs = new AtomicInteger();
    final CountDownLatch firstStarted = new CountDownLatch(1);
    final CountDownLatch releaseFirst = new CountDownLatch(1);

    final Runnable task = () -> {
      final int n = runs.incrementAndGet();
      if (n == 1) {
        firstStarted.countDown();
        try {
          releaseFirst.await(2, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    };

    dispatcher.dispatch("c", task);
    assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

    // Pile on while the first refresh is blocked. All but one should be
    // coalesced into a single rerun.
    for (int i = 0; i < 25; i++) {
      dispatcher.dispatch("c", task);
    }

    releaseFirst.countDown();

    final long deadline = System.currentTimeMillis() + 2000;
    while (runs.get() < 2 && System.currentTimeMillis() < deadline) {
      Thread.sleep(5);
    }
    // Give a moment for any spurious extra runs to appear.
    Thread.sleep(100);

    assertEquals(2, runs.get(), "expected exactly one rerun after the burst");
  }

  @Test
  void whenDispatchAfterFinish_givenIdleDispatcher_shouldRunFresh()
      throws Exception {
    final Andersoni.AsyncRefreshDispatcher dispatcher =
        new Andersoni.AsyncRefreshDispatcher(setOf("c"));
    final AtomicInteger runs = new AtomicInteger();

    dispatcher.dispatch("c", runs::incrementAndGet);
    waitFor(() -> runs.get() == 1, 1000);

    dispatcher.dispatch("c", runs::incrementAndGet);
    waitFor(() -> runs.get() == 2, 1000);

    assertEquals(2, runs.get());
  }

  @Test
  void whenTwoCatalogs_givenIndependentDispatches_shouldNotInterfere()
      throws Exception {
    final Andersoni.AsyncRefreshDispatcher dispatcher =
        new Andersoni.AsyncRefreshDispatcher(setOf("a", "b"));
    final AtomicInteger runsA = new AtomicInteger();
    final AtomicInteger runsB = new AtomicInteger();

    final CountDownLatch aBlocked = new CountDownLatch(1);
    final CountDownLatch releaseA = new CountDownLatch(1);

    dispatcher.dispatch("a", () -> {
      runsA.incrementAndGet();
      aBlocked.countDown();
      try {
        releaseA.await(2, TimeUnit.SECONDS);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    assertTrue(aBlocked.await(2, TimeUnit.SECONDS));

    // Catalog B must not be blocked by catalog A's in-flight refresh.
    dispatcher.dispatch("b", runsB::incrementAndGet);
    waitFor(() -> runsB.get() == 1, 1000);

    releaseA.countDown();
    waitFor(() -> runsA.get() == 1, 1000);

    assertEquals(1, runsA.get());
    assertEquals(1, runsB.get());
  }

  @Test
  void whenDispatchUnknownCatalog_givenMissingEntry_shouldNotRun()
      throws Exception {
    final Andersoni.AsyncRefreshDispatcher dispatcher =
        new Andersoni.AsyncRefreshDispatcher(setOf("c"));
    final AtomicInteger runs = new AtomicInteger();

    dispatcher.dispatch("ghost", runs::incrementAndGet);
    Thread.sleep(50);

    assertEquals(0, runs.get());
  }

  private static Set<String> setOf(final String... names) {
    final Set<String> s = new HashSet<>();
    for (final String n : names) {
      s.add(n);
    }
    return s;
  }

  private static void waitFor(final java.util.function.BooleanSupplier check,
      final long timeoutMillis) throws InterruptedException {
    final long deadline = System.currentTimeMillis() + timeoutMillis;
    while (!check.getAsBoolean()
        && System.currentTimeMillis() < deadline) {
      TimeUnit.MILLISECONDS.sleep(5);
    }
  }
}
