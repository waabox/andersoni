package org.waabox.andersoni;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
 * <p>When a snapshot store is configured, a background reconciliation loop
 * treats it as the cluster's authority: on each pass a follower whose
 * applied hash drifted from the store reloads from it, and the leader
 * whose applied hash drifted re-saves and re-publishes, so a save that
 * failed mid-refresh is retried automatically. Reconciliation is on by
 * default (see {@link ReconciliationPolicy#defaultPolicy()}); trigger an
 * on-demand pass with {@link #reconcile()}.
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
 * List<Event> results = andersoni.search("events", "by-sport", "Football", Event.class);
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

  /** The bridge to the optional snapshot store for persistent snapshots. */
  private final SnapshotStoreBridge storeBridge;

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

  /** The scheduled executor for periodic refresh tasks. Written in start()
   *  and read from stop(); volatile for cross-thread visibility. */
  private volatile ScheduledExecutorService scheduler;

  /** The scheduled refresh futures, keyed by catalog name. */
  private final Map<String, ScheduledFuture<?>> scheduledFutures;

  /** The async refresh dispatcher for sync events. Written in start() and
   *  read from transport/dispatch threads; volatile for visibility. */
  private volatile AsyncRefreshDispatcher asyncRefreshDispatcher;

  /** The reconciliation policy. */
  private final ReconciliationPolicy reconciliationPolicy;

  /** The reconciler, or null when reconciliation is inactive. Written in
   *  start() and read by status()/reconcile(). */
  private volatile SnapshotReconciler reconciler;

  /**
   * Creates a new Andersoni instance.
   *
   * @param nodeId               the unique node identifier, never null
   * @param syncStrategy         the optional sync strategy, may be null
   * @param leaderElection       the leader election strategy, never null
   * @param snapshotStore        the optional snapshot store, may be null
   * @param retryPolicy          the retry policy, never null
   * @param metrics              the metrics reporter, never null
   * @param reconciliationPolicy the reconciliation policy, never null
   */
  private Andersoni(final String nodeId,
      final SyncStrategy syncStrategy,
      final LeaderElectionStrategy leaderElection,
      final SnapshotStore snapshotStore,
      final RetryPolicy retryPolicy,
      final AndersoniMetrics metrics,
      final ReconciliationPolicy reconciliationPolicy) {
    this.nodeId = nodeId;
    this.syncStrategy = syncStrategy;
    this.leaderElection = leaderElection;
    this.storeBridge = new SnapshotStoreBridge(snapshotStore);
    this.retryPolicy = retryPolicy;
    this.metrics = metrics;
    this.catalogsByName = new ConcurrentHashMap<>();
    this.failedCatalogs = ConcurrentHashMap.newKeySet();
    this.scheduledFutures = new ConcurrentHashMap<>();
    this.reconciliationPolicy = reconciliationPolicy;
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
    asyncRefreshDispatcher = new AsyncRefreshDispatcher(
        catalogsByName.keySet());
    wireSyncListener();
    schedulePeriodicRefreshes();
    startReconciler();
    metrics.start(
        Collections.unmodifiableCollection(catalogsByName.values()),
        nodeId);
  }

  /**
   * Starts the reconciliation loop when the policy is enabled and a
   * snapshot store is configured; otherwise logs why it is inactive.
   */
  private void startReconciler() {
    if (!reconciliationPolicy.enabled() || !storeBridge.isConfigured()) {
      log.info("Snapshot reconciliation inactive (enabled={}, snapshotStore={})",
          reconciliationPolicy.enabled(), storeBridge.isConfigured());
      return;
    }
    final SnapshotReconciler created = new SnapshotReconciler(
        catalogsByName,
        storeBridge,
        leaderElection,
        asyncRefreshDispatcher::dispatch,
        metrics,
        reconciliationPolicy,
        failedCatalogs,
        this::repairFollower,
        this::repairLeader);
    // Assigned before start() so a failure inside start() (e.g. a listener
    // registered on leaderElection.onLeaderChange throwing) still leaves
    // reconciler visible to stop(), which would otherwise never see it and
    // leak whatever start() managed to spin up before failing.
    reconciler = created;
    created.start();
  }

  /**
   * Follower repair: reload the catalog from the store, which is the
   * authority. Never falls back to the DataLoader.
   *
   * @param catalog the drifted catalog, never null
   * @throws IllegalStateException if the store has no snapshot any more
   */
  private void repairFollower(final Catalog<?> catalog) {
    if (!storeBridge.load(catalog)) {
      throw new IllegalStateException("Snapshot for catalog '" + catalog.name()
          + "' disappeared from the store before it could be loaded");
    }
    failedCatalogs.remove(catalog.name());
    reportIndexSizes(catalog);
  }

  /**
   * Leader repair: re-save the current snapshot and re-publish it.
   *
   * @param catalog the drifted catalog, never null
   */
  private void repairLeader(final Catalog<?> catalog) {
    storeBridge.save(catalog);
    publishRefreshEvent(catalog);
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
   * Searches a catalog by name and delegates to the specified index,
   * returning a typed list for caller convenience.
   *
   * <p>If {@code type} is a registered view on the catalog, this method
   * returns view instances produced by the view mapping function. Otherwise,
   * it returns the catalog's item type cast to {@code T}.
   *
   * @param catalogName  the name of the catalog to search, never null
   * @param indexName    the name of the index within the catalog, never null
   * @param key          the key to look up, never null
   * @param type         the expected element type or a registered view type, never null
   * @param <T>          the expected element type
   *
   * @return an unmodifiable list of matching items or views, never null
   *
   * @throws IllegalArgumentException      if no catalog with the given name
   *                                       is registered
   * @throws CatalogNotAvailableException  if the catalog failed to bootstrap
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public <T> List<T> search(final String catalogName, final String indexName,
      final Object key, final Class<T> type) {
    Objects.requireNonNull(catalogName, "catalogName must not be null");
    Objects.requireNonNull(indexName, "indexName must not be null");
    Objects.requireNonNull(key, "key must not be null");
    Objects.requireNonNull(type, "type must not be null");

    final Catalog<?> catalog = requireCatalog(catalogName);

    if (failedCatalogs.contains(catalogName)) {
      throw new CatalogNotAvailableException(catalogName);
    }

    return catalog.searchWithType(indexName, key, type);
  }

  /**
   * Returns a {@link QueryStep} for fluent querying of a catalog's index.
   *
   * <p>Usage:
   * <pre>{@code
   * andersoni.query("events", "by-date").between(from, to);
   * andersoni.query("events", "by-venue").equalTo("Maracana");
   * }</pre>
   *
   * @param catalogName the name of the catalog, never null
   * @param indexName   the name of the index, never null
   *
   * @return a QueryStep for the specified catalog and index, never null
   *
   * @throws IllegalArgumentException     if no catalog with the given name
   *                                      is registered
   * @throws CatalogNotAvailableException if the catalog failed to bootstrap
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public QueryStep<?> query(final String catalogName,
      final String indexName) {
    Objects.requireNonNull(catalogName, "catalogName must not be null");
    Objects.requireNonNull(indexName, "indexName must not be null");

    final Catalog<?> catalog = requireCatalog(catalogName);

    if (failedCatalogs.contains(catalogName)) {
      throw new CatalogNotAvailableException(catalogName);
    }

    return catalog.query(indexName);
  }

  /**
   * Returns a typed {@link QueryStep} for fluent querying of a catalog's
   * index.
   *
   * <p>This is a convenience overload that performs an unchecked cast
   * on the result of {@link #query(String, String)}. The caller is
   * responsible for providing the correct type — the type that was used
   * when creating the catalog via {@link Catalog#of(Class)}.
   *
   * <p>Usage:
   * <pre>{@code
   * andersoni.query("events", "by-date", Event.class).between(from, to);
   * andersoni.query("events", "by-venue", Event.class).equalTo("Maracana");
   * }</pre>
   *
   * @param catalogName the name of the catalog, never null
   * @param indexName   the name of the index, never null
   * @param type        the expected element type, never null
   * @param <T>         the expected element type
   *
   * @return a QueryStep for the specified catalog and index, never null
   *
   * @throws IllegalArgumentException     if no catalog with the given name
   *                                      is registered
   * @throws CatalogNotAvailableException if the catalog failed to bootstrap
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  @SuppressWarnings("unchecked")
  public <T> QueryStep<T> query(final String catalogName,
      final String indexName, final Class<T> type) {
    Objects.requireNonNull(type, "type must not be null");
    return (QueryStep<T>) query(catalogName, indexName);
  }

  /**
   * Creates a compound query for the specified catalog.
   *
   * @param catalogName the catalog name, never null
   * @return a CompoundQuery for the catalog, never null
   * @throws IllegalArgumentException if no catalog with the given name is
   *                                  registered
   * @throws CatalogNotAvailableException if the catalog failed to bootstrap
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public CompoundQuery<?> compound(final String catalogName) {
    Objects.requireNonNull(catalogName, "catalogName must not be null");
    final Catalog<?> catalog = requireCatalog(catalogName);
    if (failedCatalogs.contains(catalogName)) {
      throw new CatalogNotAvailableException(catalogName);
    }
    return catalog.compound();
  }

  /**
   * Creates a typed compound query for the specified catalog.
   *
   * @param catalogName the catalog name, never null
   * @param type the expected element type, never null
   * @param <T> the expected element type
   * @return a typed CompoundQuery for the catalog, never null
   * @throws IllegalArgumentException if no catalog with the given name is
   *                                  registered
   * @throws CatalogNotAvailableException if the catalog failed to bootstrap
   * @author waabox(waabox[at]gmail[dot]com)
   */
  @SuppressWarnings("unchecked")
  public <T> CompoundQuery<T> compound(final String catalogName,
      final Class<T> type) {
    Objects.requireNonNull(type, "type must not be null");
    return (CompoundQuery<T>) compound(catalogName);
  }

  /**
   * Creates a graph query builder for the named catalog, bound to the
   * current snapshot.
   *
   * <p>The returned {@link GraphQueryBuilder} uses the query planner to
   * select the best graph index and hotpath for the given conditions.
   *
   * @param catalogName the name of the catalog to query, never null
   *
   * @return a GraphQueryBuilder bound to the current snapshot, never null
   *
   * @throws NullPointerException     if catalogName is null
   * @throws IllegalArgumentException if no catalog with the given name
   *                                  is registered
   * @throws CatalogNotAvailableException if the catalog failed to bootstrap
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public GraphQueryBuilder<?> graphQuery(final String catalogName) {
    Objects.requireNonNull(catalogName, "catalogName must not be null");
    final Catalog<?> catalog = requireCatalog(catalogName);
    if (failedCatalogs.contains(catalogName)) {
      throw new CatalogNotAvailableException(catalogName);
    }
    return catalog.graphQuery();
  }

  /**
   * Creates a typed graph query builder for the named catalog, bound to
   * the current snapshot.
   *
   * @param catalogName the name of the catalog to query, never null
   * @param type        the expected item type, never null
   * @param <T>         the expected element type
   *
   * @return a typed GraphQueryBuilder for the catalog, never null
   *
   * @throws IllegalArgumentException if no catalog with the given name
   *                                  is registered
   * @throws CatalogNotAvailableException if the catalog failed to bootstrap
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  @SuppressWarnings("unchecked")
  public <T> GraphQueryBuilder<T> graphQuery(final String catalogName,
      final Class<T> type) {
    Objects.requireNonNull(type, "type must not be null");
    return (GraphQueryBuilder<T>) graphQuery(catalogName);
  }

  /**
   * Refreshes a catalog locally and synchronizes the refresh event across
   * nodes.
   *
   * <p>The authoritative refresh always runs on the leader. If this node is
   * the leader, it refreshes locally and broadcasts the result. If this node
   * is not the leader and a sync strategy is present, it instead publishes a
   * {@link RefreshEvent} of kind {@link org.waabox.andersoni.sync.RefreshKind#REQUEST}
   * so the leader performs the refresh; the requesting node then converges
   * through the normal result event. Followers ignore requests, so no
   * propagation loop can form. If this node is not the leader and no sync
   * strategy is configured, the call is a no-op.
   *
   * <p>When acting as the leader, a {@link RefreshEvent} is published
   * after the local refresh. If a snapshot store is present and the catalog
   * has a serializer, the refreshed data is serialized and saved to the
   * snapshot store before publishing the event.
   *
   * @param catalogName the name of the catalog to refresh, never null
   *
   * @throws IllegalArgumentException if no catalog with the given name
   *                                  is registered
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public void refreshAndSync(final String catalogName) {
    Objects.requireNonNull(catalogName, "catalogName must not be null");
    if (stopped.get()) {
      throw new IllegalStateException(
          "Cannot refresh after stop() has been called");
    }
    final Catalog<?> catalog = requireCatalog(catalogName);

    if (!leaderElection.isLeader()) {
      publishRefreshRequest(catalogName);
      return;
    }

    catalog.refresh();
    storeBridge.markUnknown(catalogName);
    reportIndexSizes(catalog);
    storeBridge.save(catalog);
    publishRefreshEvent(catalog);
  }

  /**
   * Broadcasts the catalog's current snapshot as a result
   * {@link org.waabox.andersoni.sync.RefreshKind#EVENT}, if a sync
   * strategy is configured. Publish failures are logged and reported, never
   * thrown: the reconciliation loop is the retry path.
   *
   * @param catalog the catalog whose snapshot was just refreshed, never null
   */
  private void publishRefreshEvent(final Catalog<?> catalog) {
    if (syncStrategy == null) {
      return;
    }
    final Snapshot<?> snapshot = catalog.currentSnapshot();
    final RefreshEvent event = new RefreshEvent(
        catalog.name(), nodeId, snapshot.version(), snapshot.hash(), Instant.now());
    try {
      syncStrategy.publish(event);
      metrics.syncPublished(catalog.name());
    } catch (final Exception e) {
      log.error("Failed to publish sync event for catalog '{}': {}",
          catalog.name(), e.getMessage(), e);
      metrics.syncPublishFailed(catalog.name(), e);
    }
  }

  /**
   * Publishes a {@link org.waabox.andersoni.sync.RefreshKind#REQUEST}
   * message asking the leader to refresh the given catalog.
   *
   * <p>Invoked when a non-leader node receives a {@code refreshAndSync}
   * call. The request is a broadcast command; only the leader acts upon it
   * (see {@link #wireSyncListener()}), so it is never re-emitted and cannot
   * form a loop. If no sync strategy is configured the request cannot be
   * delivered and the call becomes a no-op.
   *
   * @param catalogName the catalog to request a refresh for, never null
   */
  private void publishRefreshRequest(final String catalogName) {
    if (syncStrategy == null) {
      log.debug("Skipping refreshAndSync for catalog '{}': not leader "
          + "and no sync strategy to reach the leader", catalogName);
      return;
    }
    final RefreshEvent request = RefreshEvent.request(
        catalogName, nodeId, Instant.now());
    try {
      syncStrategy.publish(request);
      metrics.syncRequested(catalogName);
      log.debug("Not leader; published refresh request for catalog '{}'",
          catalogName);
    } catch (final Exception e) {
      log.error("Failed to publish refresh request for catalog '{}': {}",
          catalogName, e.getMessage(), e);
      metrics.syncPublishFailed(catalogName, e);
    }
  }

  /**
   * Refreshes a catalog locally without synchronizing across nodes.
   *
   * <p>This method is intended for internal use when receiving sync events
   * from other nodes. It re-queries the catalog's DataLoader to get fresh
   * data.
   *
   * <p>With reconciliation active, a follower's local refresh is overwritten
   * by the store's snapshot on the next pass; on the leader, the next pass
   * re-saves and re-publishes the refreshed data.
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
    storeBridge.markUnknown(catalogName);
    reportIndexSizes(catalog);
  }

  /**
   * Runs a reconciliation pass as soon as possible on the reconciler thread
   * and returns without waiting.
   *
   * <p>This is the operational replacement for a manual refresh: it never
   * queries the DataLoader. Followers reload from the snapshot store if it
   * moved; the leader re-saves and re-publishes if the store is behind. It
   * is a no-op when reconciliation is inactive (disabled policy or no
   * snapshot store).
   *
   * @throws IllegalStateException if {@link #stop()} has been called
   */
  public void reconcile() {
    if (stopped.get()) {
      throw new IllegalStateException("Cannot reconcile after stop() has been called");
    }
    final SnapshotReconciler current = reconciler;
    if (current == null) {
      log.debug("reconcile() ignored: reconciliation is inactive");
      return;
    }
    current.requestPass();
  }

  /**
   * Runs a reconciliation pass on the calling thread. Repairs are still
   * dispatched asynchronously. Intended for tests.
   */
  void reconcileNow() {
    final SnapshotReconciler current = reconciler;
    if (current != null) {
      current.runPass();
    }
  }

  /**
   * Stops the Andersoni lifecycle.
   *
   * <p>This method cancels all scheduled refresh tasks, stops the
   * reconciler, the sync strategy and leader election, and clears internal
   * state. Metrics are stopped last, after every component that might still
   * report through them (in particular an in-flight reconciliation repair)
   * has been shut down.
   */
  public void stop() {
    if (!stopped.compareAndSet(false, true)) {
      return;
    }

    cancelScheduledRefreshes();

    final SnapshotReconciler current = reconciler;
    if (current != null) {
      current.stop();
    }

    if (syncStrategy != null) {
      syncStrategy.stop();
    }

    leaderElection.stop();

    metrics.stop();
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
   * Returns a read-only snapshot of this node's operational state: whether it
   * is the leader and, per catalog, the current version, hash, item count and
   * estimated memory size.
   *
   * <p>Intended for health endpoints, dashboards and cross-node convergence
   * checks (compare each catalog's {@code hash} across nodes). A catalog that
   * failed to bootstrap or is not yet ready is reported with
   * {@code available = false} rather than throwing.
   *
   * @return this node's status, never null
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public AndersoniStatus status() {
    final boolean leader = leaderElection.isLeader();
    final List<AndersoniStatus.CatalogStatus> catalogStatuses =
        new ArrayList<>();
    for (final Catalog<?> catalog : catalogsByName.values()) {
      catalogStatuses.add(buildCatalogStatus(catalog));
    }
    return new AndersoniStatus(nodeId, leader, catalogStatuses);
  }

  /**
   * Builds the status for a single catalog, degrading gracefully if the
   * catalog is not ready.
   *
   * @param catalog the catalog to inspect, never null
   * @return the catalog status, never null
   */
  private AndersoniStatus.CatalogStatus buildCatalogStatus(
      final Catalog<?> catalog) {
    final SnapshotReconciler current = reconciler;
    final SyncState syncState = current == null
        ? SyncState.UNKNOWN : current.syncState(catalog.name());
    final Optional<Instant> lastReconciledAt = current == null
        ? Optional.empty() : current.lastReconciledAt(catalog.name());
    try {
      final Snapshot<?> snapshot = catalog.currentSnapshot();
      final CatalogInfo info = catalog.info();
      return new AndersoniStatus.CatalogStatus(
          catalog.name(), !failedCatalogs.contains(catalog.name()),
          snapshot.version(), snapshot.hash(),
          catalog.serializer().isPresent(),
          info.itemCount(), info.totalEstimatedSizeMB(),
          syncState, lastReconciledAt);
    } catch (final RuntimeException e) {
      return new AndersoniStatus.CatalogStatus(
          catalog.name(), false, 0L, "", false, 0, 0.0,
          syncState, lastReconciledAt);
    }
  }

  /**
   * Returns a registered catalog by name, cast to the expected type.
   *
   * @param catalogName the name of the catalog, never null
   * @param type        the expected item type, never null
   * @param <T>         the item type
   *
   * @return the catalog, never null
   *
   * @throws NullPointerException     if any argument is null
   * @throws IllegalArgumentException if no catalog with the given name
   *                                  is registered
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  @SuppressWarnings("unchecked")
  public <T> Catalog<T> catalog(final String catalogName,
      final Class<T> type) {
    Objects.requireNonNull(catalogName, "catalogName must not be null");
    Objects.requireNonNull(type, "type must not be null");
    return (Catalog<T>) requireCatalog(catalogName);
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
      if (storeBridge.load(catalog)) {
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
        storeBridge.markUnknown(name);
        metrics.snapshotLoaded(name, "dataLoader");
        storeBridge.save(catalog);
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
   * <p>If all snapshot store attempts are exhausted, falls back to the
   * DataLoader as a last resort. This prevents permanent catalog
   * unavailability when the snapshot store contains a corrupt or
   * incompatible snapshot and the leader has not recovered in time. On
   * success, the follower saves the snapshot to the store so subsequent
   * restarts recover without hitting the database again.
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
        if (storeBridge.load(catalog)) {
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
        log.warn("Catalog '{}': all {} follower snapshot store attempts "
            + "exhausted. Falling back to DataLoader as last resort.",
            name, maxAttempts);
        tryDataLoaderAsFallback(name, catalog);
      }
    }
  }

  /**
   * Last-resort fallback for followers that exhausted all snapshot store
   * retries.
   *
   * <p>Attempts a single DataLoader call. On success, saves the snapshot
   * to the store so subsequent restarts (and other followers) can recover
   * without hitting the database again.
   *
   * @param name    the catalog name, never null
   * @param catalog the catalog to bootstrap, never null
   */
  private void tryDataLoaderAsFallback(final String name,
      final Catalog<?> catalog) {
    try {
      catalog.bootstrap();
      storeBridge.markUnknown(name);
      storeBridge.save(catalog);
      metrics.snapshotLoaded(name, "followerDataLoaderFallback");
      reportIndexSizes(catalog);
      log.info("Catalog '{}': follower DataLoader fallback succeeded,"
          + " snapshot saved for future restarts.", name);
    } catch (final Exception e) {
      log.error("Catalog '{}': follower DataLoader fallback also failed."
          + " Marking as FAILED.", name, e);
      failedCatalogs.add(name);
      metrics.refreshFailed(name, e);
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
   * Wires the sync listener if a sync strategy is configured.
   *
   * <p>Incoming messages are handled by kind. A
   * {@link org.waabox.andersoni.sync.RefreshKind#REQUEST} is a command that
   * only the leader acts upon (by running the authoritative refresh);
   * followers ignore it, so it is never re-emitted. A
   * {@link org.waabox.andersoni.sync.RefreshKind#EVENT} is a result: the
   * listener ignores events from this node (to prevent infinite loops) and
   * events where the local catalog already has the same hash, otherwise it
   * reloads from the snapshot store first, falling back to the catalog's
   * DataLoader.
   */
  private void wireSyncListener() {
    if (syncStrategy == null) {
      return;
    }

    syncStrategy.subscribe(event -> {
      final Catalog<?> catalog = catalogsByName.get(event.catalogName());
      if (catalog == null) {
        log.warn("Received refresh event for unknown catalog '{}'",
            event.catalogName());
        return;
      }

      if (event.isRequest()) {
        // A request is a command to the leader. Only the leader acts on it,
        // by running the authoritative refresh (which broadcasts a result
        // event). Followers ignore it, so the request is never re-emitted
        // and no propagation loop can form.
        if (leaderElection.isLeader()) {
          log.debug("Leader received refresh request for catalog '{}'",
              event.catalogName());
          asyncRefreshDispatcher.dispatch(event.catalogName(),
              () -> refreshAndSync(event.catalogName()));
        } else {
          log.debug("Ignoring refresh request for catalog '{}': not leader",
              event.catalogName());
        }
        return;
      }

      if (nodeId.equals(event.sourceNodeId())) {
        log.debug("Ignoring refresh event from self for catalog '{}'",
            event.catalogName());
        return;
      }

      final String localHash = catalog.currentSnapshot().hash();
      if (localHash.equals(event.hash())) {
        log.debug("Catalog '{}' already at hash {}, ignoring event",
            event.catalogName(), event.hash());
        return;
      }

      metrics.syncReceived(event.catalogName());
      asyncRefreshDispatcher.dispatch(event.catalogName(),
          () -> refreshFromEvent(event.catalogName(), catalog));
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
  private void refreshFromEvent(final String catalogName,
      final Catalog<?> catalog) {
    try {
      if (storeBridge.load(catalog)) {
        log.info("Refreshed catalog '{}' from snapshot store", catalogName);
        reportIndexSizes(catalog);
        return;
      }
      catalog.refresh();
      storeBridge.markUnknown(catalogName);
      log.info("Refreshed catalog '{}' from DataLoader", catalogName);
      reportIndexSizes(catalog);
    } catch (final Exception e) {
      log.error("Failed to refresh catalog '{}' from sync event: {}",
          catalogName, e.getMessage(), e);
      metrics.refreshFailed(catalogName, e);
    }
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
              try {
                // Periodic refresh is leader-only. A follower must not run
                // it, and in particular must not publish a refresh request
                // (refreshAndSync would otherwise do so), which would storm
                // the leader once per interval per follower.
                if (leaderElection.isLeader()) {
                  refreshAndSync(name);
                }
              } catch (final Exception e) {
                log.error("Scheduled refresh failed for catalog '{}': {}",
                    name, e.getMessage(), e);
                metrics.refreshFailed(name, e);
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
   *   <li>reconciliation: {@link ReconciliationPolicy#defaultPolicy()}</li>
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

    /** The optional reconciliation policy. */
    private ReconciliationPolicy reconciliation;

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
     * Sets the reconciliation policy. Defaults to
     * {@link ReconciliationPolicy#defaultPolicy()}: enabled, every 30
     * seconds, active only when a snapshot store is configured.
     *
     * @param thePolicy the policy, never null
     * @return this builder, never null
     */
    public Builder reconciliation(final ReconciliationPolicy thePolicy) {
      Objects.requireNonNull(thePolicy, "reconciliation policy must not be null");
      this.reconciliation = thePolicy;
      return this;
    }

    /**
     * Builds the Andersoni instance with the configured settings.
     *
     * <p>Any unset optional fields are replaced with their defaults.
     *
     * @return a new Andersoni instance, never null
     */
    /**
     * Resolves the default node id when none was configured.
     *
     * <p>Prefers a stable identifier from the {@code HOSTNAME} environment
     * variable (in Kubernetes this is the pod name), falling back to a random
     * UUID. A stable id keeps per-node metric tags from growing unbounded
     * across process restarts.
     *
     * @return the default node id, never null.
     */
    private static String defaultNodeId() {
      final String hostname = System.getenv("HOSTNAME");
      if (hostname != null && !hostname.isBlank()) {
        return hostname;
      }
      return UUID.randomUUID().toString();
    }

    public Andersoni build() {
      final String resolvedNodeId = nodeId != null
          ? nodeId : defaultNodeId();
      final LeaderElectionStrategy resolvedLeader = leaderElection != null
          ? leaderElection : new SingleNodeLeaderElection();
      final RetryPolicy resolvedRetry = retryPolicy != null
          ? retryPolicy : RetryPolicy.defaultPolicy();
      final AndersoniMetrics resolvedMetrics = metrics != null
          ? metrics : new NoopAndersoniMetrics();
      final ReconciliationPolicy resolvedReconciliation = reconciliation != null
          ? reconciliation : ReconciliationPolicy.defaultPolicy();

      return new Andersoni(
          resolvedNodeId,
          syncStrategy,
          resolvedLeader,
          snapshotStore,
          resolvedRetry,
          resolvedMetrics,
          resolvedReconciliation);
    }
  }
}
