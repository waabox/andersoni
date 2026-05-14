package org.waabox.andersoni;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dispatches catalog refresh operations to virtual threads with per-catalog
 * serialization and event coalescing.
 *
 * <p>Each catalog gets its own {@link Semaphore} (1 permit) so refreshes for
 * the same catalog execute serially. An {@link AtomicBoolean} per catalog
 * tracks whether a refresh is already pending, enabling coalescing: if a
 * refresh is queued or running for a catalog, subsequent events are discarded
 * because the in-flight refresh will load the latest data.
 *
 * <p>Uses Java 21 virtual threads for lightweight async execution.
 */
final class AsyncRefreshDispatcher {

  private static final Logger log = LoggerFactory.getLogger(
      AsyncRefreshDispatcher.class);

  private final Map<String, Semaphore> semaphores;
  private final Map<String, AtomicBoolean> refreshPending;

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
   * If a refresh is already pending or running, the event is coalesced.
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
          refreshTask.run();
        } finally {
          pending.set(false);
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
