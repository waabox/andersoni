package org.waabox.andersoni;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.waabox.andersoni.leader.LeaderElectionStrategy;
import org.waabox.andersoni.metrics.AndersoniMetrics;
import org.waabox.andersoni.snapshot.SnapshotMetadata;

/**
 * The cluster's anti-entropy loop.
 *
 * <p>On every pass, for each catalog with a serializer, the store's snapshot
 * is described (metadata only) and its hash compared with the hash this node
 * last applied (see {@link SnapshotStoreBridge}):
 * <ul>
 *   <li>a <b>follower</b> whose applied hash differs from the store's reloads
 *       from the store (never from the DataLoader: the store is the
 *       authority);</li>
 *   <li>the <b>leader</b> whose applied hash is missing or differs re-saves
 *       and re-publishes, which also covers a save that failed during
 *       {@code refreshAndSync};</li>
 *   <li>a node promoted to leader runs a pass immediately.</li>
 * </ul>
 *
 * <p>Repairs run through the shared dispatcher so they serialize with
 * sync-event reloads per catalog. A failed step is reported and simply
 * retried on the next pass; the loop is the retry mechanism.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
final class SnapshotReconciler {

  /** The class logger. */
  private static final Logger log = LoggerFactory.getLogger(SnapshotReconciler.class);

  /** Maximum random jitter added to each interval, as a ratio of it. */
  private static final double MAX_JITTER_RATIO = 0.2;

  private final Map<String, Catalog<?>> catalogsByName;
  private final SnapshotStoreBridge bridge;
  private final LeaderElectionStrategy leaderElection;
  private final BiConsumer<String, Runnable> dispatcher;
  private final AndersoniMetrics metrics;
  private final ReconciliationPolicy policy;
  private final Set<String> failedCatalogs;
  private final Consumer<Catalog<?>> followerRepair;
  private final Consumer<Catalog<?>> leaderRepair;

  private final Map<String, SyncState> syncStates = new ConcurrentHashMap<>();
  private final Map<String, Instant> lastReconciledAt = new ConcurrentHashMap<>();
  private final AtomicBoolean running = new AtomicBoolean(false);
  private volatile ScheduledExecutorService scheduler;

  /**
   * Creates a reconciler. Nothing runs until {@link #start()}.
   *
   * @param catalogsByName the registered catalogs, never null
   * @param bridge         the store bridge, never null
   * @param leaderElection the leader election, never null
   * @param dispatcher     runs a repair for a catalog name, never null
   * @param metrics        the metrics sink, never null
   * @param policy         the pass interval, never null, must be enabled
   * @param failedCatalogs the catalogs whose bootstrap failed, never null
   * @param followerRepair reloads a catalog from the store, never null
   * @param leaderRepair   re-saves and re-publishes a catalog, never null
   */
  SnapshotReconciler(final Map<String, Catalog<?>> catalogsByName,
      final SnapshotStoreBridge bridge,
      final LeaderElectionStrategy leaderElection,
      final BiConsumer<String, Runnable> dispatcher,
      final AndersoniMetrics metrics,
      final ReconciliationPolicy policy,
      final Set<String> failedCatalogs,
      final Consumer<Catalog<?>> followerRepair,
      final Consumer<Catalog<?>> leaderRepair) {
    this.catalogsByName = catalogsByName;
    this.bridge = bridge;
    this.leaderElection = leaderElection;
    this.dispatcher = dispatcher;
    this.metrics = metrics;
    this.policy = policy;
    this.failedCatalogs = failedCatalogs;
    this.followerRepair = followerRepair;
    this.leaderRepair = leaderRepair;
  }

  /** Starts the scheduler, registers for leader changes, schedules the first pass. */
  void start() {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      final Thread thread = new Thread(r, "andersoni-reconciler");
      thread.setDaemon(true);
      return thread;
    });
    leaderElection.onLeaderChange(isLeader -> {
      if (isLeader) {
        log.info("Promoted to leader; running an immediate reconciliation pass");
        requestPass();
      }
    });
    scheduleNext();
    log.info("Snapshot reconciliation started, interval {} (+ up to {}% jitter)",
        policy.interval(), (int) (MAX_JITTER_RATIO * 100));
  }

  /** Stops the scheduler. Idempotent; safe when never started. */
  void stop() {
    if (!running.compareAndSet(true, false)) {
      return;
    }
    final ScheduledExecutorService current = scheduler;
    scheduler = null;
    if (current != null) {
      current.shutdownNow();
    }
    log.info("Snapshot reconciliation stopped");
  }

  /** Runs a pass on the reconciler thread as soon as possible. No-op when stopped. */
  void requestPass() {
    final ScheduledExecutorService current = scheduler;
    if (current == null) {
      return;
    }
    current.execute(this::runPassSafely);
  }

  /**
   * Runs one pass on the calling thread. Repairs are handed to the
   * dispatcher, so they may complete after this method returns.
   */
  void runPass() {
    final boolean leader = leaderElection.isLeader();
    for (final Map.Entry<String, Catalog<?>> entry : catalogsByName.entrySet()) {
      reconcileCatalog(entry.getKey(), entry.getValue(), leader);
    }
  }

  /**
   * @param catalogName the catalog name, never null
   * @return the state recorded by the last pass, {@link SyncState#UNKNOWN} if none
   */
  SyncState syncState(final String catalogName) {
    return syncStates.getOrDefault(catalogName, SyncState.UNKNOWN);
  }

  /**
   * @param catalogName the catalog name, never null
   * @return when the last pass checked this catalog, or empty
   */
  Optional<Instant> lastReconciledAt(final String catalogName) {
    return Optional.ofNullable(lastReconciledAt.get(catalogName));
  }

  private void scheduleNext() {
    final ScheduledExecutorService current = scheduler;
    if (current == null || !running.get()) {
      return;
    }
    current.schedule(() -> {
      runPassSafely();
      scheduleNext();
    }, nextDelayMillis(), TimeUnit.MILLISECONDS);
  }

  private long nextDelayMillis() {
    final long base = policy.interval().toMillis();
    final long jitter = (long) (base * MAX_JITTER_RATIO * ThreadLocalRandom.current().nextDouble());
    return base + jitter;
  }

  private void runPassSafely() {
    try {
      runPass();
    } catch (final RuntimeException e) {
      log.error("Reconciliation pass failed: {}", e.getMessage(), e);
    }
  }

  private void reconcileCatalog(final String name, final Catalog<?> catalog,
      final boolean leader) {
    if (!bridge.supports(catalog)) {
      syncStates.put(name, SyncState.UNKNOWN);
      return;
    }

    final Optional<SnapshotMetadata> metadata;
    try {
      metadata = bridge.describe(name);
    } catch (final RuntimeException e) {
      log.warn("Reconciliation could not describe catalog '{}' in the snapshot store: {}",
          name, e.getMessage());
      metrics.reconcileFailed(name, e);
      return;
    }

    final Optional<String> storeHash = metadata.map(SnapshotMetadata::hash);
    final Optional<String> applied = bridge.appliedStoreHash(name);
    lastReconciledAt.put(name, Instant.now());

    final boolean inSync = leader
        ? applied.isPresent() && applied.equals(storeHash)
        : storeHash.isEmpty() || storeHash.equals(applied);

    if (inSync) {
      syncStates.put(name, SyncState.IN_SYNC);
      return;
    }

    syncStates.put(name, SyncState.DRIFTED);
    metrics.driftDetected(name);

    if (leader && failedCatalogs.contains(name)) {
      log.warn("Catalog '{}' drifted from the snapshot store but this leader has no"
          + " snapshot to publish (bootstrap failed); waiting for bootstrap", name);
      return;
    }

    log.info("Catalog '{}' drifted from the snapshot store (role={}, store={}, applied={});"
        + " repairing", name, leader ? "leader" : "follower",
        storeHash.orElse("<none>"), applied.orElse("<none>"));

    final Consumer<Catalog<?>> repair = leader ? leaderRepair : followerRepair;
    dispatcher.accept(name, () -> {
      try {
        repair.accept(catalog);
        syncStates.put(name, SyncState.IN_SYNC);
        metrics.driftRepaired(name);
        log.info("Catalog '{}' reconciled with the snapshot store", name);
      } catch (final RuntimeException e) {
        log.warn("Reconciliation repair failed for catalog '{}': {}", name, e.getMessage(), e);
        metrics.reconcileFailed(name, e);
      }
    });
  }
}
