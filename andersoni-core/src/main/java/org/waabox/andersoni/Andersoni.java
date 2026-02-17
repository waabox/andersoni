package org.waabox.andersoni;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.waabox.andersoni.leader.LeaderElectionStrategy;
import org.waabox.andersoni.leader.SingleNodeLeaderElection;
import org.waabox.andersoni.metrics.AndersoniMetrics;
import org.waabox.andersoni.metrics.NoopAndersoniMetrics;
import org.waabox.andersoni.snapshot.SerializedSnapshot;
import org.waabox.andersoni.snapshot.SnapshotSerializer;
import org.waabox.andersoni.snapshot.SnapshotStore;
import org.waabox.andersoni.sync.RefreshEvent;
import org.waabox.andersoni.sync.SyncStrategy;

/**
 * The main entry point for the Andersoni in-memory cache library.
 *
 * <p>Andersoni orchestrates the lifecycle of multiple {@link Catalog catalogs},
 * including bootstrapping, refresh synchronization across nodes, leader-based
 * scheduled refreshes, and snapshot persistence.
 *
 * <p>Instances are created through the fluent {@link Builder} starting with
 * {@link #builder()}.
 *
 * <p>Usage example:
 * <pre>{@code
 * Andersoni andersoni = Andersoni.builder()
 *     .nodeId("node-1")
 *     .syncStrategy(kafkaSyncStrategy)
 *     .leaderElection(k8sLeaseStrategy)
 *     .snapshotStore(s3SnapshotStore)
 *     .retryPolicy(RetryPolicy.of(3, Duration.ofSeconds(2)))
 *     .metrics(micrometerMetrics)
 *     .build();
 *
 * andersoni.register(eventsCatalog);
 * andersoni.register(sportsCatalog);
 * andersoni.start();
 *
 * List<?> results = andersoni.search("events", "by-sport", "Football");
 * }</pre>
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class Andersoni {

  /** The class logger. */
  private static final Logger log = LoggerFactory.getLogger(Andersoni.class);

  /** The unique identifier for this node. */
  private final String nodeId;

  /** The optional sync strategy for cross-node refresh events. */
  private final SyncStrategy syncStrategy;

  /** The leader election strategy. */
  private final LeaderElectionStrategy leaderElection;

  /** The optional snapshot store for persistent snapshots. */
  private final SnapshotStore snapshotStore;

  /** The retry policy for catalog bootstrap operations. */
  private final RetryPolicy retryPolicy;

  /** The metrics reporter. */
  private final AndersoniMetrics metrics;

  /** The registered catalogs, keyed by catalog name. */
  private final Map<String, Catalog<?>> catalogsByName;

  /** The set of catalog names that failed to bootstrap. */
  private final Set<String> failedCatalogs;

  /** Whether this instance has been started. */
  private final AtomicBoolean started = new AtomicBoolean(false);

  /** Whether this instance has been stopped. */
  private final AtomicBoolean stopped = new AtomicBoolean(false);

  /** The scheduled executor for periodic refresh tasks. */
  private ScheduledExecutorService scheduler;

  /** The scheduled refresh futures, keyed by catalog name. */
  private final Map<String, ScheduledFuture<?>> scheduledFutures;

  /**
   * Creates a new Andersoni instance.
   *
   * @param nodeId          the unique node identifier, never null
   * @param syncStrategy    the optional sync strategy, may be null
   * @param leaderElection  the leader election strategy, never null
   * @param snapshotStore   the optional snapshot store, may be null
   * @param retryPolicy     the retry policy, never null
   * @param metrics         the metrics reporter, never null
   */
  private Andersoni(final String nodeId,
      final SyncStrategy syncStrategy,
      final LeaderElectionStrategy leaderElection,
      final SnapshotStore snapshotStore,
      final RetryPolicy retryPolicy,
      final AndersoniMetrics metrics) {
    this.nodeId = nodeId;
    this.syncStrategy = syncStrategy;
    this.leaderElection = leaderElection;
    this.snapshotStore = snapshotStore;
    this.retryPolicy = retryPolicy;
    this.metrics = metrics;
    this.catalogsByName = new ConcurrentHashMap<>();
    this.failedCatalogs = ConcurrentHashMap.newKeySet();
    this.scheduledFutures = new ConcurrentHashMap<>();
  }

  /**
   * Creates a new builder for constructing an Andersoni instance.
   *
   * @return a new builder, never null
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Returns the unique identifier for this node.
   *
   * <p>If no custom node ID was provided during construction, this returns
   * an auto-generated UUID string.
   *
   * @return the node identifier, never null
   */
  public String nodeId() {
    return nodeId;
  }

  /**
   * Registers a catalog with this Andersoni instance.
   *
   * <p>The catalog is stored in an internal map keyed by its name. The
   * catalog must have a unique name among all registered catalogs.
   *
   * @param catalog the catalog to register, never null
   *
   * @throws NullPointerException     if catalog is null
   * @throws IllegalArgumentException if a catalog with the same name is
   *                                  already registered
   */
  public void register(final Catalog<?> catalog) {
    Objects.requireNonNull(catalog, "catalog must not be null");

    if (started.get()) {
      throw new IllegalStateException(
          "Cannot register catalogs after start() has been called");
    }

    final String name = catalog.name();
    final Catalog<?> existing = catalogsByName.putIfAbsent(name, catalog);
    if (existing != null) {
      throw new IllegalArgumentException(
          "A catalog with name '" + name + "' is already registered");
    }
  }

  /**
   * Starts the Andersoni lifecycle.
   *
   * <p>This method performs the following steps in order:
   * <ol>
   *   <li>Starts the leader election so nodes know their role.</li>
   *   <li>Bootstraps all registered catalogs with leader-aware retry
   *       support.</li>
   *   <li>If a sync strategy is configured, subscribes a refresh listener
   *       and starts the sync transport.</li>
   *   <li>For catalogs with a refresh interval, if this node is the leader,
   *       schedules periodic refresh tasks.</li>
   * </ol>
   *
   * <p>For each catalog bootstrap:
   * <ul>
   *   <li>First tries to load from the SnapshotStore (if configured and
   *       the catalog has a serializer).</li>
   *   <li>If that fails and this node is the leader, falls back to the
   *       catalog's DataLoader with retry support and saves the result
   *       to the SnapshotStore.</li>
   *   <li>If that fails and this node is a follower, retries loading
   *       from the SnapshotStore (waiting for the leader to upload),
   *       with failover to the DataLoader path if this node becomes
   *       leader mid-bootstrap.</li>
   *   <li>After exhausting retries, the catalog is marked as FAILED and
   *       the error is logged. Other catalogs continue bootstrapping.</li>
   * </ul>
   */
  public void start() {
    if (!started.compareAndSet(false, true)) {
      throw new IllegalStateException(
          "Andersoni has already been started");
    }
    leaderElection.start();
    bootstrapAllCatalogs();
    wireSyncListener();
    schedulePeriodicRefreshes();
  }

  /**
   * Searches a catalog by name and delegates to the specified index.
   *
   * @param catalogName the name of the catalog to search, never null
   * @param indexName   the name of the index within the catalog, never null
   * @param key         the key to look up, never null
   *
   * @return an unmodifiable list of matching items, never null
   *
   * @throws IllegalArgumentException      if no catalog with the given name
   *                                       is registered
   * @throws CatalogNotAvailableException  if the catalog failed to bootstrap
   */
  public List<?> search(final String catalogName, final String indexName,
      final Object key) {
    Objects.requireNonNull(catalogName, "catalogName must not be null");
    Objects.requireNonNull(indexName, "indexName must not be null");
    Objects.requireNonNull(key, "key must not be null");

    final Catalog<?> catalog = requireCatalog(catalogName);

    if (failedCatalogs.contains(catalogName)) {
      throw new CatalogNotAvailableException(catalogName);
    }

    return catalog.search(indexName, key);
  }

  /**
   * Refreshes a catalog locally and synchronizes the refresh event across
   * nodes.
   *
   * <p>If a sync strategy is present, a {@link RefreshEvent} is published
   * after the local refresh. If a snapshot store is present and the catalog
   * has a serializer, the refreshed data is serialized and saved to the
   * snapshot store before publishing the event.
   *
   * <p><strong>Note:</strong> This method does NOT require leader status.
   * Any node can call it at any time, which means concurrent refreshes
   * across the cluster are possible. Callers are responsible for
   * coordinating access if exclusive refresh semantics are required.
   *
   * @param catalogName the name of the catalog to refresh, never null
   *
   * @throws IllegalArgumentException if no catalog with the given name
   *                                  is registered
   */
  public void refreshAndSync(final String catalogName) {
    Objects.requireNonNull(catalogName, "catalogName must not be null");
    if (stopped.get()) {
      throw new IllegalStateException(
          "Cannot refresh after stop() has been called");
    }
    final Catalog<?> catalog = requireCatalog(catalogName);

    catalog.refresh();
    reportIndexSizes(catalog);

    saveSnapshotIfPossible(catalog);

    if (syncStrategy != null) {
      final Snapshot<?> snapshot = catalog.currentSnapshot();
      final RefreshEvent event = new RefreshEvent(
          catalogName,
          nodeId,
          snapshot.version(),
          snapshot.hash(),
          Instant.now());
      syncStrategy.publish(event);
    }
  }

  /**
   * Refreshes a catalog locally without synchronizing across nodes.
   *
   * <p>This method is intended for internal use when receiving sync events
   * from other nodes. It re-queries the catalog's DataLoader to get fresh
   * data.
   *
   * @param catalogName the name of the catalog to refresh, never null
   *
   * @throws IllegalArgumentException if no catalog with the given name
   *                                  is registered
   */
  public void refresh(final String catalogName) {
    Objects.requireNonNull(catalogName, "catalogName must not be null");
    if (stopped.get()) {
      throw new IllegalStateException(
          "Cannot refresh after stop() has been called");
    }
    final Catalog<?> catalog = requireCatalog(catalogName);
    catalog.refresh();
    reportIndexSizes(catalog);
  }

  /**
   * Stops the Andersoni lifecycle.
   *
   * <p>This method cancels all scheduled refresh tasks, stops the sync
   * strategy and leader election, and clears internal state.
   */
  public void stop() {
    if (!stopped.compareAndSet(false, true)) {
      return;
    }

    cancelScheduledRefreshes();

    if (syncStrategy != null) {
      syncStrategy.stop();
    }

    leaderElection.stop();
  }

  /**
   * Returns an unmodifiable collection of all registered catalogs.
   *
   * <p>This is primarily intended for the Spring Boot starter to inspect
   * registered catalogs.
   *
   * @return an unmodifiable collection of catalogs, never null
   */
  public Collection<Catalog<?>> catalogs() {
    return Collections.unmodifiableCollection(catalogsByName.values());
  }

  /**
   * Returns statistics about a registered catalog, including per-index
   * memory estimation.
   *
   * @param catalogName the name of the catalog, never null
   *
   * @return the catalog info, never null
   *
   * @throws NullPointerException     if catalogName is null
   * @throws IllegalArgumentException if no catalog with the given name
   *                                  is registered
   */
  public CatalogInfo info(final String catalogName) {
    Objects.requireNonNull(catalogName, "catalogName must not be null");
    return requireCatalog(catalogName).info();
  }

  /**
   * Bootstraps all registered catalogs with retry support.
   *
   * <p>For each catalog, first attempts to load from the snapshot store
   * (if configured and the catalog has a serializer). Falls back to the
   * catalog's own bootstrap method. Retries failures per the retry policy.
   */
  private void bootstrapAllCatalogs() {
    for (final Map.Entry<String, Catalog<?>> entry
        : catalogsByName.entrySet()) {
      final String name = entry.getKey();
      final Catalog<?> catalog = entry.getValue();
      bootstrapWithRetry(name, catalog);
    }
  }

  /**
   * Bootstraps a single catalog with leader-aware retry support.
   *
   * <p>First attempts to load from the SnapshotStore. If that fails:
   * <ul>
   *   <li>Leaders fall back to the DataLoader with retry support and
   *       save the result to the SnapshotStore for followers.</li>
   *   <li>Followers retry loading from the SnapshotStore, waiting for
   *       the leader to upload a new snapshot. If a follower becomes
   *       leader mid-bootstrap, it switches to the DataLoader path.</li>
   * </ul>
   *
   * @param name    the catalog name, never null
   * @param catalog the catalog to bootstrap, never null
   */
  private void bootstrapWithRetry(final String name,
      final Catalog<?> catalog) {

    // Step 1: try S3 once.
    try {
      if (tryLoadFromSnapshotStore(name, catalog)) {
        metrics.snapshotLoaded(name, "snapshotStore");
        reportIndexSizes(catalog);
        return;
      }
    } catch (final Exception e) {
      log.warn("Catalog '{}': snapshot store load failed: {}", name,
          e.getMessage());
    }

    // Step 2: role-aware fallback.
    if (leaderElection.isLeader()) {
      bootstrapAsLeader(name, catalog);
    } else {
      bootstrapAsFollower(name, catalog);
    }
  }

  /**
   * Bootstraps a catalog as leader using the DataLoader with retry support.
   *
   * <p>On success, saves the result to the SnapshotStore so followers can
   * pick it up immediately.
   *
   * @param name    the catalog name, never null
   * @param catalog the catalog to bootstrap, never null
   */
  private void bootstrapAsLeader(final String name,
      final Catalog<?> catalog) {
    final int maxAttempts = retryPolicy.maxRetries();
    final Duration backoff = retryPolicy.backoff();

    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        catalog.bootstrap();
        metrics.snapshotLoaded(name, "dataLoader");
        saveSnapshotIfPossible(catalog);
        reportIndexSizes(catalog);
        return;
      } catch (final Exception e) {
        log.warn("Catalog '{}': leader DataLoader attempt {}/{} failed: {}",
            name, attempt, maxAttempts, e.getMessage());

        if (attempt < maxAttempts) {
          sleepOrAbort(name, backoff);
        } else {
          log.error("Catalog '{}': all {} leader DataLoader attempts "
              + "exhausted. Marking as FAILED.", name, maxAttempts);
          failedCatalogs.add(name);
          metrics.refreshFailed(name, e);
        }
      }
    }
  }

  /**
   * Bootstraps a catalog as follower by retrying the SnapshotStore.
   *
   * <p>Waits for the leader to upload a new snapshot. On each attempt,
   * re-checks leadership status so a follower that gets promoted to leader
   * mid-bootstrap switches to the DataLoader path. Logs a warning every
   * 10 failed attempts.
   *
   * <p>The maximum number of attempts is the configured
   * {@link RetryPolicy#maxRetries()} multiplied by 10. This 10x multiplier
   * exists because followers depend on the leader uploading a snapshot
   * first, which introduces additional latency. The extra attempts give
   * the leader enough time to complete its own bootstrap and upload the
   * snapshot before the follower gives up.
   *
   * @param name    the catalog name, never null
   * @param catalog the catalog to bootstrap, never null
   */
  private void bootstrapAsFollower(final String name,
      final Catalog<?> catalog) {
    final int maxAttempts = Math.min(retryPolicy.maxRetries(),
        Integer.MAX_VALUE / 10) * 10;
    final Duration backoff = retryPolicy.backoff();

    for (int attempt = 1; attempt <= maxAttempts; attempt++) {

      // Re-check leadership: if promoted, switch to leader path.
      if (leaderElection.isLeader()) {
        log.info("Catalog '{}': follower promoted to leader at attempt {},"
            + " switching to DataLoader path", name, attempt);
        bootstrapAsLeader(name, catalog);
        return;
      }

      try {
        if (tryLoadFromSnapshotStore(name, catalog)) {
          metrics.snapshotLoaded(name, "snapshotStore");
          reportIndexSizes(catalog);
          return;
        }
      } catch (final Exception e) {
        log.debug("Catalog '{}': follower snapshot store attempt {} failed:"
            + " {}", name, attempt, e.getMessage());
      }

      if (attempt % 10 == 0) {
        log.warn("Catalog '{}': follower waiting for leader to upload "
            + "snapshot, attempt {}/{}", name, attempt, maxAttempts);
      }

      if (attempt < maxAttempts) {
        sleepOrAbort(name, backoff);
      } else {
        log.error("Catalog '{}': all {} follower snapshot store attempts "
            + "exhausted. Marking as FAILED.", name, maxAttempts);
        failedCatalogs.add(name);
        metrics.refreshFailed(name,
            new RuntimeException("Follower bootstrap exhausted for " + name));
      }
    }
  }

  /**
   * Sleeps for the given duration. If interrupted, marks the catalog as
   * failed and restores the interrupt flag.
   *
   * @param catalogName the catalog name for logging, never null
   * @param backoff     the duration to sleep, never null
   */
  private void sleepOrAbort(final String catalogName,
      final Duration backoff) {
    try {
      Thread.sleep(backoff.toMillis());
    } catch (final InterruptedException ie) {
      Thread.currentThread().interrupt();
      log.error("Bootstrap interrupted for catalog '{}'", catalogName);
      failedCatalogs.add(catalogName);
      metrics.refreshFailed(catalogName, ie);
    }
  }

  /**
   * Attempts to load catalog data from the snapshot store.
   *
   * @param name    the catalog name, never null
   * @param catalog the catalog to load, never null
   *
   * @return true if data was successfully loaded from the snapshot store,
   *         false if the snapshot store is not configured, the catalog has
   *         no serializer, or no snapshot was found
   */
  @SuppressWarnings("unchecked")
  private boolean tryLoadFromSnapshotStore(final String name,
      final Catalog<?> catalog) {
    if (snapshotStore == null) {
      return false;
    }

    final Optional<? extends SnapshotSerializer<?>> serializerOpt =
        catalog.serializer();
    if (serializerOpt.isEmpty()) {
      return false;
    }

    final Optional<SerializedSnapshot> snapshotOpt = snapshotStore.load(name);
    if (snapshotOpt.isEmpty()) {
      return false;
    }

    final SerializedSnapshot serialized = snapshotOpt.get();
    final SnapshotSerializer<Object> serializer =
        (SnapshotSerializer<Object>) serializerOpt.get();
    final List<Object> data = serializer.deserialize(serialized.data());

    final Catalog<Object> typedCatalog = (Catalog<Object>) catalog;
    typedCatalog.refresh(data);

    return true;
  }

  /**
   * Wires the sync listener if a sync strategy is configured.
   *
   * <p>The listener ignores events from this node (to prevent infinite
   * loops) and events where the local catalog already has the same hash.
   * When a new event is received, it attempts to load from the snapshot
   * store first, falling back to the catalog's DataLoader.
   */
  private void wireSyncListener() {
    if (syncStrategy == null) {
      return;
    }

    syncStrategy.subscribe(event -> {
      if (nodeId.equals(event.sourceNodeId())) {
        log.debug("Ignoring refresh event from self for catalog '{}'",
            event.catalogName());
        return;
      }

      final Catalog<?> catalog = catalogsByName.get(event.catalogName());
      if (catalog == null) {
        log.warn("Received refresh event for unknown catalog '{}'",
            event.catalogName());
        return;
      }

      final String localHash = catalog.currentSnapshot().hash();
      if (localHash.equals(event.hash())) {
        log.debug("Catalog '{}' already at hash {}, ignoring event",
            event.catalogName(), event.hash());
        return;
      }

      refreshFromEvent(event.catalogName(), catalog);
    });

    syncStrategy.start();
  }

  /**
   * Refreshes a catalog in response to a sync event.
   *
   * <p>First attempts to load from the snapshot store (if configured and
   * the catalog has a serializer). Falls back to the catalog's DataLoader.
   *
   * @param catalogName the catalog name, never null
   * @param catalog     the catalog to refresh, never null
   */
  @SuppressWarnings("unchecked")
  private void refreshFromEvent(final String catalogName,
      final Catalog<?> catalog) {
    try {
      if (snapshotStore != null && catalog.serializer().isPresent()) {
        final Optional<SerializedSnapshot> snapshotOpt =
            snapshotStore.load(catalogName);
        if (snapshotOpt.isPresent()) {
          final SnapshotSerializer<Object> serializer =
              (SnapshotSerializer<Object>) catalog.serializer().get();
          final List<Object> data =
              serializer.deserialize(snapshotOpt.get().data());
          final Catalog<Object> typedCatalog = (Catalog<Object>) catalog;
          typedCatalog.refresh(data);
          log.info("Refreshed catalog '{}' from snapshot store",
              catalogName);
          reportIndexSizes(catalog);
          return;
        }
      }

      catalog.refresh();
      log.info("Refreshed catalog '{}' from DataLoader", catalogName);
      reportIndexSizes(catalog);
    } catch (final Exception e) {
      log.error("Failed to refresh catalog '{}' from sync event: {}",
          catalogName, e.getMessage(), e);
      metrics.refreshFailed(catalogName, e);
    }
  }

  /**
   * Serializes and saves the current catalog snapshot to the snapshot store
   * if both the snapshot store is configured and the catalog has a
   * serializer.
   *
   * @param catalog the catalog whose snapshot should be saved, never null
   */
  @SuppressWarnings("unchecked")
  private void saveSnapshotIfPossible(final Catalog<?> catalog) {
    if (snapshotStore == null) {
      return;
    }

    final Optional<? extends SnapshotSerializer<?>> serializerOpt =
        catalog.serializer();
    if (serializerOpt.isEmpty()) {
      return;
    }

    final SnapshotSerializer<Object> serializer =
        (SnapshotSerializer<Object>) serializerOpt.get();
    final Snapshot<?> snapshot = catalog.currentSnapshot();
    final List<Object> data = (List<Object>) snapshot.data();
    final byte[] bytes = serializer.serialize(data);

    final SerializedSnapshot serialized = new SerializedSnapshot(
        catalog.name(),
        snapshot.hash(),
        snapshot.version(),
        snapshot.createdAt(),
        bytes);

    snapshotStore.save(catalog.name(), serialized);
  }

  /**
   * Schedules periodic refreshes for catalogs that have a refresh interval
   * configured, but only if this node is the leader.
   */
  private void schedulePeriodicRefreshes() {
    final boolean hasSchedulable = catalogsByName.values().stream()
        .anyMatch(c -> c.refreshInterval().isPresent());

    if (!hasSchedulable) {
      return;
    }

    scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      final Thread thread = new Thread(r, "andersoni-refresh-scheduler");
      thread.setDaemon(true);
      return thread;
    });

    for (final Map.Entry<String, Catalog<?>> entry
        : catalogsByName.entrySet()) {
      final String name = entry.getKey();
      final Catalog<?> catalog = entry.getValue();
      final Optional<Duration> intervalOpt = catalog.refreshInterval();

      if (intervalOpt.isPresent()) {
        final Duration interval = intervalOpt.get();
        final ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
            () -> {
              if (leaderElection.isLeader()) {
                try {
                  refreshAndSync(name);
                } catch (final Exception e) {
                  log.error("Scheduled refresh failed for catalog '{}': {}",
                      name, e.getMessage(), e);
                  metrics.refreshFailed(name, e);
                }
              }
            },
            interval.toMillis(),
            interval.toMillis(),
            TimeUnit.MILLISECONDS);

        scheduledFutures.put(name, future);
      }
    }
  }

  /**
   * Cancels all scheduled refresh futures and shuts down the scheduler.
   */
  private void cancelScheduledRefreshes() {
    for (final ScheduledFuture<?> future : scheduledFutures.values()) {
      future.cancel(false);
    }
    scheduledFutures.clear();

    if (scheduler != null) {
      scheduler.shutdownNow();
      scheduler = null;
    }
  }

  /**
   * Reports index sizes to the metrics interface for the given catalog.
   *
   * @param catalog the catalog whose index sizes should be reported,
   *                never null
   */
  private void reportIndexSizes(final Catalog<?> catalog) {
    final CatalogInfo info = catalog.info();
    for (final IndexInfo indexInfo : info.indices()) {
      metrics.indexSizeReported(catalog.name(), indexInfo.name(),
          indexInfo.estimatedSizeBytes());
    }
  }

  /**
   * Looks up a catalog by name, throwing if not found.
   *
   * @param catalogName the catalog name to look up, never null
   *
   * @return the catalog, never null
   *
   * @throws IllegalArgumentException if no catalog with the given name
   *                                  is registered
   */
  private Catalog<?> requireCatalog(final String catalogName) {
    final Catalog<?> catalog = catalogsByName.get(catalogName);
    if (catalog == null) {
      throw new IllegalArgumentException(
          "No catalog registered with name '" + catalogName + "'");
    }
    return catalog;
  }

  /**
   * A fluent builder for constructing {@link Andersoni} instances.
   *
   * <p>All configuration is optional. Defaults:
   * <ul>
   *   <li>nodeId: auto-generated UUID</li>
   *   <li>syncStrategy: none (single-node mode)</li>
   *   <li>leaderElection: {@link SingleNodeLeaderElection}</li>
   *   <li>snapshotStore: none</li>
   *   <li>retryPolicy: {@link RetryPolicy#defaultPolicy()}</li>
   *   <li>metrics: {@link NoopAndersoniMetrics}</li>
   * </ul>
   */
  public static final class Builder {

    /** The optional custom node identifier. */
    private String nodeId;

    /** The optional sync strategy. */
    private SyncStrategy syncStrategy;

    /** The optional leader election strategy. */
    private LeaderElectionStrategy leaderElection;

    /** The optional snapshot store. */
    private SnapshotStore snapshotStore;

    /** The optional retry policy. */
    private RetryPolicy retryPolicy;

    /** The optional metrics reporter. */
    private AndersoniMetrics metrics;

    /** Creates a new builder with default settings. */
    private Builder() {
    }

    /**
     * Sets the unique node identifier for this Andersoni instance.
     *
     * <p>If not set, an auto-generated UUID will be used.
     *
     * @param theNodeId the node identifier, never null or empty
     *
     * @return this builder for chaining, never null
     *
     * @throws NullPointerException     if theNodeId is null
     * @throws IllegalArgumentException if theNodeId is empty
     */
    public Builder nodeId(final String theNodeId) {
      Objects.requireNonNull(theNodeId, "nodeId must not be null");
      if (theNodeId.isEmpty()) {
        throw new IllegalArgumentException("nodeId must not be empty");
      }
      this.nodeId = theNodeId;
      return this;
    }

    /**
     * Sets the sync strategy for cross-node refresh event distribution.
     *
     * <p>If not set, Andersoni operates in single-node mode without
     * event synchronization.
     *
     * @param theSyncStrategy the sync strategy, never null
     *
     * @return this builder for chaining, never null
     *
     * @throws NullPointerException if theSyncStrategy is null
     */
    public Builder syncStrategy(final SyncStrategy theSyncStrategy) {
      Objects.requireNonNull(theSyncStrategy,
          "syncStrategy must not be null");
      this.syncStrategy = theSyncStrategy;
      return this;
    }

    /**
     * Sets the leader election strategy.
     *
     * <p>If not set, {@link SingleNodeLeaderElection} is used as default,
     * which always considers this node as the leader.
     *
     * @param theLeaderElection the leader election strategy, never null
     *
     * @return this builder for chaining, never null
     *
     * @throws NullPointerException if theLeaderElection is null
     */
    public Builder leaderElection(
        final LeaderElectionStrategy theLeaderElection) {
      Objects.requireNonNull(theLeaderElection,
          "leaderElection must not be null");
      this.leaderElection = theLeaderElection;
      return this;
    }

    /**
     * Sets the snapshot store for persistent snapshot storage.
     *
     * <p>If not set, no snapshot persistence is used.
     *
     * @param theSnapshotStore the snapshot store, never null
     *
     * @return this builder for chaining, never null
     *
     * @throws NullPointerException if theSnapshotStore is null
     */
    public Builder snapshotStore(final SnapshotStore theSnapshotStore) {
      Objects.requireNonNull(theSnapshotStore,
          "snapshotStore must not be null");
      this.snapshotStore = theSnapshotStore;
      return this;
    }

    /**
     * Sets the retry policy for catalog bootstrap operations.
     *
     * <p>If not set, {@link RetryPolicy#defaultPolicy()} is used.
     *
     * @param theRetryPolicy the retry policy, never null
     *
     * @return this builder for chaining, never null
     *
     * @throws NullPointerException if theRetryPolicy is null
     */
    public Builder retryPolicy(final RetryPolicy theRetryPolicy) {
      Objects.requireNonNull(theRetryPolicy,
          "retryPolicy must not be null");
      this.retryPolicy = theRetryPolicy;
      return this;
    }

    /**
     * Sets the metrics reporter for operational metrics collection.
     *
     * <p>If not set, {@link NoopAndersoniMetrics} is used.
     *
     * @param theMetrics the metrics reporter, never null
     *
     * @return this builder for chaining, never null
     *
     * @throws NullPointerException if theMetrics is null
     */
    public Builder metrics(final AndersoniMetrics theMetrics) {
      Objects.requireNonNull(theMetrics, "metrics must not be null");
      this.metrics = theMetrics;
      return this;
    }

    /**
     * Builds the Andersoni instance with the configured settings.
     *
     * <p>Any unset optional fields are replaced with their defaults.
     *
     * @return a new Andersoni instance, never null
     */
    public Andersoni build() {
      final String resolvedNodeId = nodeId != null
          ? nodeId : UUID.randomUUID().toString();
      final LeaderElectionStrategy resolvedLeader = leaderElection != null
          ? leaderElection : new SingleNodeLeaderElection();
      final RetryPolicy resolvedRetry = retryPolicy != null
          ? retryPolicy : RetryPolicy.defaultPolicy();
      final AndersoniMetrics resolvedMetrics = metrics != null
          ? metrics : new NoopAndersoniMetrics();

      return new Andersoni(
          resolvedNodeId,
          syncStrategy,
          resolvedLeader,
          snapshotStore,
          resolvedRetry,
          resolvedMetrics);
    }
  }
}
