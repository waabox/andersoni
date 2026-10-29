package org.waabox.andersoni;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dispatches catalog refresh and patch work to virtual threads with
 * per-catalog serialization.
 *
 * <p>Each catalog gets its own {@link Semaphore} (1 permit) so all work for
 * that catalog executes serially, whichever mode submitted it. Two
 * submission modes are offered, because refreshes and patches have
 * different loss semantics:
 *
 * <ul>
 *   <li>{@link #dispatch} — <strong>coalescing</strong>, for full refreshes.
 *       An {@link AtomicBoolean} per catalog tracks whether a refresh is
 *       already pending; if one is, the new event is discarded because the
 *       in-flight refresh will load the latest data anyway. Dropping a
 *       refresh is safe: a later full reload subsumes an earlier one.</li>
 *   <li>{@link #dispatchOrdered} — <strong>FIFO, never coalescing</strong>,
 *       for patches. A patch is a delta against a specific base state, so
 *       one patch never subsumes another: dropping one would leave the node
 *       silently missing that change. Tasks are queued per catalog and run
 *       in submission order.</li>
 * </ul>
 *
 * <p>Uses Java 21 virtual threads for lightweight async execution.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
final class AsyncRefreshDispatcher {

  private static final Logger log = LoggerFactory.getLogger(
      AsyncRefreshDispatcher.class);

  /**
   * Maximum patches queued per catalog before {@link #dispatchOrdered}
   * starts refusing work. A caller that is refused falls back to a full
   * reload, which is both cheaper than replaying a deep backlog and
   * self-correcting, so the bound cannot cost correctness.
   */
  private static final int PATCH_QUEUE_CAPACITY = 256;

  private final Map<String, Semaphore> semaphores;
  private final Map<String, AtomicBoolean> refreshPending;
  private final Map<String, ThreadPoolExecutor> patchQueues;

  AsyncRefreshDispatcher(final Set<String> catalogNames) {
    final Map<String, Semaphore> sems = new HashMap<>();
    final Map<String, AtomicBoolean> pending = new HashMap<>();
    final Map<String, ThreadPoolExecutor> queues = new HashMap<>();
    for (final String name : catalogNames) {
      sems.put(name, new Semaphore(1));
      pending.put(name, new AtomicBoolean(false));
      queues.put(name, newPatchQueue(name));
    }
    semaphores = Collections.unmodifiableMap(sems);
    refreshPending = Collections.unmodifiableMap(pending);
    patchQueues = Collections.unmodifiableMap(queues);
  }

  /**
   * Creates the single-threaded, bounded executor backing one catalog's
   * patch queue. One thread is what makes submission order the execution
   * order.
   *
   * @param catalogName the catalog this queue serves, never null
   *
   * @return the executor, never null
   */
  private static ThreadPoolExecutor newPatchQueue(final String catalogName) {
    return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<>(PATCH_QUEUE_CAPACITY),
        Thread.ofVirtual().name("andersoni-patch-" + catalogName).factory());
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
        // Clear pending BEFORE running: an event that arrives while this
        // refresh is in flight must re-arm the dispatcher so its newer
        // data is not silently coalesced away and lost. The semaphore
        // still serializes the actual refresh per catalog.
        pending.set(false);
        try {
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

  /**
   * Queues a task for the given catalog, preserving submission order and
   * never coalescing. Intended for patches, where each task carries a
   * distinct delta that no other task subsumes.
   *
   * <p>The task still acquires the catalog's semaphore before running, so
   * it cannot interleave with a refresh dispatched through
   * {@link #dispatch}.
   *
   * <p>Returns {@code false} rather than dropping work silently: the queue
   * is full, the dispatcher has been stopped, or the catalog is unknown.
   * The caller is expected to fall back to a full reload, which restores
   * the same end state.
   *
   * @param catalogName the catalog the task belongs to, never null
   * @param task        the task to queue, never null
   *
   * @return true if the task was queued, false if the caller must fall back
   */
  boolean dispatchOrdered(final String catalogName, final Runnable task) {
    final ThreadPoolExecutor queue = patchQueues.get(catalogName);
    if (queue == null) {
      log.warn("No patch queue configured for catalog '{}'", catalogName);
      return false;
    }

    try {
      queue.execute(() -> {
        final Semaphore semaphore = semaphores.get(catalogName);
        try {
          semaphore.acquire();
          try {
            task.run();
          } finally {
            semaphore.release();
          }
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
          log.warn("Patch interrupted for catalog '{}'", catalogName);
        }
      });
      return true;
    } catch (final RejectedExecutionException e) {
      log.warn("Patch queue for catalog '{}' rejected a task (capacity {} "
          + "or shut down)", catalogName, PATCH_QUEUE_CAPACITY);
      return false;
    }
  }

  /**
   * Shuts down the per-catalog patch queues. Queued-but-unstarted patches
   * are abandoned; nodes converge through the normal refresh path on the
   * next event, and this only runs on the way to {@code stop()} anyway.
   */
  void stop() {
    for (final ThreadPoolExecutor queue : patchQueues.values()) {
      queue.shutdownNow();
    }
  }
}
