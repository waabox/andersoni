package org.waabox.andersoni;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-catalog time-window coalescer for refresh signals.
 *
 * <p>Sits in front of the {@code AsyncRefreshDispatcher} to compress bursts
 * of incoming refresh events into a single dispatch.
 *
 * <p><b>Per-catalog isolation.</b> Each catalog name has its own fully
 * independent timer state (the burst start time and the scheduled
 * future) held in a {@link ConcurrentHashMap} and locked independently.
 * Consequences:
 * <ul>
 *   <li>A burst on catalog {@code A} does not push out, delay, or coalesce
 *       events for catalog {@code B}.</li>
 *   <li>The {@code window} and {@code maxWait} bounds are computed and
 *       enforced per catalog, never globally.</li>
 *   <li>The {@code submit} fast path synchronizes only on the per-catalog
 *       state, so concurrent submissions for different catalogs do not
 *       contend on a single lock.</li>
 * </ul>
 * The scheduler <em>thread</em> is shared across all catalogs (one daemon
 * thread total), but the work it performs when a timer fires is just
 * resetting state and handing off to the downstream dispatcher, which in
 * turn dispatches onto a virtual thread. The shared scheduler thread is
 * therefore not on the refresh hot path and cannot become a bottleneck
 * even if many catalogs fire at the same instant.
 *
 * <p>Semantics (applied per catalog): the first event in a quiet period
 * schedules the action to run after {@code window}. Each subsequent event
 * for the same catalog within the window pushes <em>that catalog's</em>
 * timer out, but never beyond {@code firstEventTime + maxWait}, so
 * sustained traffic still fires at a bounded cadence and cannot starve.
 *
 * <p>When {@code window} is {@link Duration#ZERO} the debouncer is a
 * pass-through: the action runs synchronously on the calling thread and no
 * scheduler resources are used. This preserves the pre-debouncer behavior.
 *
 * <p>This class is thread-safe.
 */
final class RefreshDebouncer {

  /** The class logger. */
  private static final Logger log = LoggerFactory.getLogger(
      RefreshDebouncer.class);

  /** The minimum delay between the first event in a burst and firing. */
  private final Duration window;

  /** The maximum delay between the first event in a burst and firing. */
  private final Duration maxWait;

  /** The scheduler used to fire pending tasks; {@code null} when disabled. */
  private final ScheduledExecutorService scheduler;

  /** Per-catalog timer state. */
  private final Map<String, CatalogState> states;

  /**
   * Creates a new debouncer governed by the given policy.
   *
   * <p>The policy is the single source of truth for the window/max-wait
   * bounds and is already validated by {@link DebouncePolicy}'s factories,
   * so this constructor performs no further range checking. A
   * {@link DebouncePolicy#passThrough()} policy yields a no-op debouncer
   * that runs actions synchronously on the calling thread.
   *
   * @param policy the debounce policy, never null
   *
   * @throws NullPointerException if policy is null
   */
  RefreshDebouncer(final DebouncePolicy policy) {
    Objects.requireNonNull(policy, "policy must not be null");
    this.window = policy.window();
    this.maxWait = policy.maxWait();
    this.states = new ConcurrentHashMap<>();
    if (policy.isPassThrough()) {
      this.scheduler = null;
    } else {
      this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        final Thread t = new Thread(r, "andersoni-refresh-debouncer");
        t.setDaemon(true);
        return t;
      });
    }
  }

  /**
   * Returns {@code true} if this debouncer is a no-op pass-through.
   *
   * @return whether the window is zero
   */
  boolean isPassThrough() {
    return scheduler == null;
  }

  /**
   * Submits a refresh signal for the given catalog.
   *
   * <p>If the debouncer is in pass-through mode, the action runs immediately
   * on the calling thread. Otherwise the action is scheduled to run after
   * the configured window, coalescing any further submissions for the
   * <em>same</em> catalog within that window into a single firing.
   * Submissions for <em>different</em> catalogs are coalesced independently:
   * each catalog has its own timer and its own lock, so the call path for
   * one catalog never blocks or delays another.
   *
   * @param catalogName the catalog identifier, never null
   * @param action      the action to run when the timer fires, never null
   *
   * @throws NullPointerException if any argument is null
   */
  void submit(final String catalogName, final Runnable action) {
    Objects.requireNonNull(catalogName, "catalogName must not be null");
    Objects.requireNonNull(action, "action must not be null");

    if (scheduler == null) {
      action.run();
      return;
    }

    final CatalogState state = states.computeIfAbsent(catalogName,
        k -> new CatalogState());
    synchronized (state) {
      final long now = System.nanoTime();
      if (!state.inBurst) {
        state.inBurst = true;
        state.firstEventNanos = now;
      }
      final long maxFireAt = state.firstEventNanos + maxWait.toNanos();
      final long candidateFireAt = now + window.toNanos();
      final long actualFireAt = Math.min(candidateFireAt, maxFireAt);
      final long delayNanos = Math.max(0L, actualFireAt - now);

      if (state.scheduled != null) {
        state.scheduled.cancel(false);
      }
      state.scheduled = scheduler.schedule(() -> fire(state, action),
          delayNanos, TimeUnit.NANOSECONDS);
    }
  }

  /**
   * Resets the per-catalog state and runs the action.
   */
  private void fire(final CatalogState state, final Runnable action) {
    synchronized (state) {
      state.inBurst = false;
      state.scheduled = null;
    }
    try {
      action.run();
    } catch (final RuntimeException e) {
      log.error("Debounced refresh action failed: {}", e.getMessage(), e);
    }
  }

  /**
   * Cancels all pending tasks and shuts down the scheduler.
   *
   * <p>Safe to call multiple times. Subsequent submissions after shutdown
   * will fail with {@link java.util.concurrent.RejectedExecutionException}
   * unless the debouncer is in pass-through mode.
   */
  void shutdown() {
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
  }

  /** Holds the timer state for a single catalog. */
  private static final class CatalogState {

    /** Whether a burst is currently in progress for this catalog. */
    private boolean inBurst = false;

    /**
     * {@link System#nanoTime()} reading of the first event in the current
     * burst. Only meaningful while {@link #inBurst} is {@code true}.
     */
    private long firstEventNanos = 0L;

    /** The currently scheduled fire task, or {@code null} if none. */
    private ScheduledFuture<?> scheduled;
  }
}
