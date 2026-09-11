package org.waabox.andersoni;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Dispatches catalog refresh operations to virtual threads with
 * per-catalog serialization and event coalescing.
 *
 * <p>Each catalog gets its own {@link Semaphore} (1 permit) to ensure
 * that refreshes for the same catalog execute serially. An
 * {@link AtomicBoolean} per catalog tracks whether a refresh is already
 * pending, enabling coalescing: if a refresh is queued or running for a
 * catalog, subsequent events for that catalog are discarded because
 * {@code refreshFromEvent} always loads the latest data.
 *
 * <p>Uses Java 21 virtual threads for lightweight async execution.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
final class AsyncRefreshDispatcher {

  /** The class logger. */
  private static final Logger log = LoggerFactory.getLogger(
      AsyncRefreshDispatcher.class);

  /** Per-catalog semaphores for serial execution. */
  private final Map<String, Semaphore> semaphores;

  /** Per-catalog pending flags for event coalescing. */
  private final Map<String, AtomicBoolean> refreshPending;

  /**
   * Creates a new dispatcher for the given catalog names.
   *
   * @param catalogNames the set of catalog names to manage, never null
   */
  AsyncRefreshDispatcher(final Set<String> catalogNames) {
    final Map<String, Semaphore> sems = new HashMap<>();
    final Map<String, AtomicBoolean> pending = new HashMap<>();
    for (final String name : catalogNames) {
      sems.put(name, new Semaphore(1));
      pending.put(name, new AtomicBoolean(false));
    }
    semaphores = Collections.unmodifiableMap(sems);
    refreshPending = Collections.unmodifiableMap(pending);
  }

  /**
   * Dispatches a refresh task for the given catalog to a virtual thread.
   *
   * <p>If a refresh is already queued for this catalog (dispatched but not
   * yet started), the new event is coalesced: the imminent run loads the
   * latest data anyway. Once a refresh has started running, the pending
   * flag is cleared, so an event arriving mid-run re-arms the dispatcher
   * and triggers a follow-up run rather than being lost.
   *
   * @param catalogName the catalog to refresh, never null
   * @param refreshTask the refresh task to execute, never null
   */
  void dispatch(final String catalogName, final Runnable refreshTask) {
    final AtomicBoolean pending = refreshPending.get(catalogName);
    if (pending == null) {
      log.warn("No dispatcher configured for catalog '{}'", catalogName);
      return;
    }

    if (!pending.compareAndSet(false, true)) {
      log.debug("Refresh already pending for catalog '{}', coalescing",
          catalogName);
      return;
    }

    Thread.startVirtualThread(() -> {
      final Semaphore semaphore = semaphores.get(catalogName);
      try {
        semaphore.acquire();
        try {
          // Clear pending BEFORE running: an event that arrives while this
          // refresh is in flight must re-arm the dispatcher so its newer
          // data is not silently coalesced away and lost. The semaphore
          // still serializes the actual refresh per catalog.
          pending.set(false);
          refreshTask.run();
        } finally {
          semaphore.release();
        }
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        pending.set(false);
        log.warn("Refresh interrupted for catalog '{}'", catalogName);
      }
    });
  }
}
