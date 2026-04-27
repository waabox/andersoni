package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link RefreshDebouncer}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class RefreshDebouncerTest {

  @Test
  void whenWindowIsZero_givenAnySubmit_shouldRunSynchronously() {
    final RefreshDebouncer debouncer =
        new RefreshDebouncer(Duration.ZERO, Duration.ZERO);
    final AtomicInteger count = new AtomicInteger();

    assertTrue(debouncer.isPassThrough());
    debouncer.submit("c", count::incrementAndGet);
    debouncer.submit("c", count::incrementAndGet);
    debouncer.submit("c", count::incrementAndGet);

    assertEquals(3, count.get());
    debouncer.shutdown();
  }

  @Test
  void whenBurst_givenSmallWindow_shouldFireOnce() throws Exception {
    final RefreshDebouncer debouncer = new RefreshDebouncer(
        Duration.ofMillis(100), Duration.ofMillis(100));
    final AtomicInteger count = new AtomicInteger();

    assertFalse(debouncer.isPassThrough());
    for (int i = 0; i < 10; i++) {
      debouncer.submit("c", count::incrementAndGet);
    }
    Thread.sleep(300);

    assertEquals(1, count.get());
    debouncer.shutdown();
  }

  @Test
  void whenSustainedTraffic_givenMaxWaitCap_shouldFireWithinCap()
      throws Exception {
    final RefreshDebouncer debouncer = new RefreshDebouncer(
        Duration.ofMillis(80), Duration.ofMillis(200));
    final AtomicInteger count = new AtomicInteger();

    final long start = System.currentTimeMillis();
    final Thread feeder = new Thread(() -> {
      while (!Thread.currentThread().isInterrupted()) {
        debouncer.submit("c", count::incrementAndGet);
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

    while (count.get() == 0
        && System.currentTimeMillis() - start < 1000) {
      Thread.sleep(10);
    }
    final long firedAfter = System.currentTimeMillis() - start;
    feeder.interrupt();
    feeder.join(500);

    assertTrue(count.get() >= 1, "expected at least one fire");
    assertTrue(firedAfter <= 350,
        "expected fire within ~maxWait, fired after " + firedAfter + "ms");
    debouncer.shutdown();
  }

  @Test
  void whenTwoCatalogs_givenIndependentBursts_shouldFirePerCatalog()
      throws Exception {
    final RefreshDebouncer debouncer = new RefreshDebouncer(
        Duration.ofMillis(80), Duration.ofMillis(80));
    final AtomicInteger countA = new AtomicInteger();
    final AtomicInteger countB = new AtomicInteger();

    debouncer.submit("a", countA::incrementAndGet);
    debouncer.submit("b", countB::incrementAndGet);
    debouncer.submit("a", countA::incrementAndGet);
    debouncer.submit("b", countB::incrementAndGet);

    Thread.sleep(250);

    assertEquals(1, countA.get());
    assertEquals(1, countB.get());
    debouncer.shutdown();
  }

  @Test
  void whenSecondBurstAfterFirst_givenQuietPeriod_shouldFireBoth()
      throws Exception {
    final RefreshDebouncer debouncer = new RefreshDebouncer(
        Duration.ofMillis(60), Duration.ofMillis(60));
    final AtomicInteger count = new AtomicInteger();

    debouncer.submit("c", count::incrementAndGet);
    debouncer.submit("c", count::incrementAndGet);
    Thread.sleep(200);

    debouncer.submit("c", count::incrementAndGet);
    debouncer.submit("c", count::incrementAndGet);
    Thread.sleep(200);

    assertEquals(2, count.get());
    debouncer.shutdown();
  }

  @Test
  void whenConstructing_givenNegativeWindow_shouldThrow() {
    assertThrows(IllegalArgumentException.class, () ->
        new RefreshDebouncer(Duration.ofMillis(-1), Duration.ZERO));
  }

  @Test
  void whenConstructing_givenNegativeMaxWait_shouldThrow() {
    assertThrows(IllegalArgumentException.class, () ->
        new RefreshDebouncer(Duration.ofMillis(10), Duration.ofMillis(-1)));
  }

  @Test
  void whenConstructing_givenMaxWaitLessThanWindow_shouldThrow() {
    assertThrows(IllegalArgumentException.class, () ->
        new RefreshDebouncer(Duration.ofMillis(100), Duration.ofMillis(50)));
  }

  @Test
  void whenSubmit_givenNullCatalog_shouldThrow() {
    final RefreshDebouncer debouncer = new RefreshDebouncer(
        Duration.ofMillis(10), Duration.ofMillis(10));
    try {
      assertThrows(NullPointerException.class,
          () -> debouncer.submit(null, () -> {}));
    } finally {
      debouncer.shutdown();
    }
  }

  @Test
  void whenShutdown_givenNoSubmits_shouldNotThrow() {
    final RefreshDebouncer debouncer = new RefreshDebouncer(
        Duration.ofMillis(10), Duration.ofMillis(10));
    debouncer.shutdown();
    debouncer.shutdown();
  }

  @Test
  void whenSubmitAfterFire_givenSchedulerStillRunning_shouldFireAgain()
      throws Exception {
    final RefreshDebouncer debouncer = new RefreshDebouncer(
        Duration.ofMillis(50), Duration.ofMillis(50));
    final AtomicInteger count = new AtomicInteger();

    debouncer.submit("c", count::incrementAndGet);
    waitFor(() -> count.get() >= 1, 500);
    debouncer.submit("c", count::incrementAndGet);
    waitFor(() -> count.get() >= 2, 500);

    assertEquals(2, count.get());
    debouncer.shutdown();
  }

  /** Polls until the predicate is true or the timeout elapses. */
  private static void waitFor(final java.util.function.BooleanSupplier check,
      final long timeoutMillis) throws InterruptedException {
    final long deadline = System.currentTimeMillis() + timeoutMillis;
    while (!check.getAsBoolean()
        && System.currentTimeMillis() < deadline) {
      TimeUnit.MILLISECONDS.sleep(5);
    }
  }
}
