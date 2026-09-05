package org.waabox.andersoni;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.waabox.andersoni.leader.LeaderElectionStrategy;
import org.waabox.andersoni.metrics.AndersoniMetrics;
import org.waabox.andersoni.snapshot.SnapshotSerializer;
import org.waabox.andersoni.sync.PatchEvent;
import org.waabox.andersoni.sync.PatchOperation;
import org.waabox.andersoni.sync.SyncStrategy;

/**
 * Cross-node coordination of snapshot patches for {@link Andersoni}.
 *
 * <p>Hosts both ends of the patch protocol:
 * <ul>
 *   <li>Leader side: {@link #replaceAndSync} performs the local replace,
 *       saves the snapshot, and publishes a {@link PatchEvent}.</li>
 *   <li>Follower side: {@link #handlePatch} hash-keys an inbound event
 *       (already applied / surgical apply / full-reload fallback) and
 *       dispatches the resulting work through the shared
 *       {@link AsyncRefreshDispatcher}.</li>
 * </ul>
 *
 * <p>This class deliberately does not own the catalog registry, the
 * snapshot store, or the bootstrap state machine. It calls back to
 * {@link Andersoni} for those concerns through {@link Callbacks}.
 */
final class PatchCoordinator {

  /** Callbacks into {@link Andersoni} for cross-cutting concerns. */
  interface Callbacks {
    boolean isStopped();
    Catalog<?> requireCatalog(String catalogName);
    boolean isFailedCatalog(String catalogName);
    void reportIndexSizes(Catalog<?> catalog);
    void saveSnapshotIfPossible(Catalog<?> catalog);
    void refreshFromEvent(String catalogName, Catalog<?> catalog);
  }

  private static final Logger log = LoggerFactory.getLogger(
      PatchCoordinator.class);

  private final String nodeId;
  private final SyncStrategy syncStrategy;
  private final LeaderElectionStrategy leaderElection;
  private final AndersoniMetrics metrics;
  private final Map<String, Catalog<?>> catalogsByName;
  private final Callbacks callbacks;
  private AsyncRefreshDispatcher dispatcher;

  PatchCoordinator(final String nodeId,
      final SyncStrategy syncStrategy,
      final LeaderElectionStrategy leaderElection,
      final AndersoniMetrics metrics,
      final Map<String, Catalog<?>> catalogsByName,
      final Callbacks callbacks) {
    this.nodeId = nodeId;
    this.syncStrategy = syncStrategy;
    this.leaderElection = leaderElection;
    this.metrics = metrics;
    this.catalogsByName = catalogsByName;
    this.callbacks = callbacks;
  }

  void setDispatcher(final AsyncRefreshDispatcher theDispatcher) {
    this.dispatcher = theDispatcher;
  }

  /**
   * Replaces a single item in the named catalog and broadcasts the patch.
   *
   * <p>Steps:
   * <ol>
   *   <li>If a {@link LeaderElectionStrategy} is configured and this node is
   *       not the leader, the call is a no-op and returns {@code false}.</li>
   *   <li>Replaces the item locally. If the lookup finds zero items, returns
   *       {@code false} without publishing.</li>
   *   <li>Saves the resulting snapshot to the configured snapshot store so
   *       followers that fall back to a full reload pick up the latest
   *       state.</li>
   *   <li>Builds a {@link PatchEvent} and publishes it through the sync
   *       strategy. If the strategy doesn't support patches, the local
   *       replace still happens and the snapshot is still saved.</li>
   * </ol>
   *
   * <p>The catalog must have a {@link SnapshotSerializer} configured —
   * cross-node patch events cannot be built otherwise.
   *
   * @return {@code true} on a successful local replace; {@code false} when
   *         no item matched, this node is not the leader, or Andersoni has
   *         been stopped
   *
   * @throws NullPointerException        if any argument is null
   * @throws IllegalStateException       if Andersoni has been stopped or
   *                                     the catalog has no serializer
   * @throws AmbiguousPatchTargetException   if the lookup returned more than
   *                                     one item
   */
  @SuppressWarnings("unchecked")
  boolean replaceAndSync(final String catalogName, final String indexName,
      final Object key, final Object newItem) {
    Objects.requireNonNull(indexName, "indexName must not be null");
    Objects.requireNonNull(key, "key must not be null");
    Objects.requireNonNull(newItem, "newItem must not be null");

    final LeaderContext context = leaderContext(catalogName);
    if (context == null) {
      return false;
    }
    final Optional<CatalogPatcher.ReplaceOutcome<Object>> outcomeOpt =
        context.catalog().replaceAndCapture(indexName, key, newItem);
    if (outcomeOpt.isEmpty()) {
      return false;
    }
    final CatalogPatcher.ReplaceOutcome<Object> outcome = outcomeOpt.get();

    publish(context, catalogName, () -> PatchEvent.replace(catalogName,
        nodeId, outcome.fromVersion(), outcome.toVersion(),
        outcome.fromHash(), outcome.toHash(), indexName,
        context.serializer().serialize(List.of(outcome.oldItem())),
        context.serializer().serialize(List.of(newItem)), Instant.now()));
    return true;
  }

  /**
   * Adds an item on the leader and broadcasts the insert.
   *
   * @param catalogName the catalog to add to, never null
   * @param newItem     the item to add, never null
   *
   * @return true when this node is the leader and applied the insert
   */
  @SuppressWarnings("unchecked")
  boolean insertAndSync(final String catalogName, final Object newItem) {
    Objects.requireNonNull(newItem, "newItem must not be null");

    final LeaderContext context = leaderContext(catalogName);
    if (context == null) {
      return false;
    }
    final CatalogPatcher.ReplaceOutcome<Object> outcome =
        context.catalog().insertAndCapture(newItem).orElseThrow();

    publish(context, catalogName, () -> PatchEvent.insert(catalogName,
        nodeId, outcome.fromVersion(), outcome.toVersion(),
        outcome.fromHash(), outcome.toHash(),
        context.serializer().serialize(List.of(newItem)), Instant.now()));
    return true;
  }

  /**
   * Removes an item on the leader and broadcasts the removal.
   *
   * @param catalogName the catalog to remove from, never null
   * @param indexName   the index to look the item up in, never null
   * @param key         the lookup key, never null
   *
   * @return true when this node is the leader and an item was removed
   */
  @SuppressWarnings("unchecked")
  boolean removeAndSync(final String catalogName, final String indexName,
      final Object key) {
    Objects.requireNonNull(indexName, "indexName must not be null");
    Objects.requireNonNull(key, "key must not be null");

    final LeaderContext context = leaderContext(catalogName);
    if (context == null) {
      return false;
    }
    final Optional<CatalogPatcher.ReplaceOutcome<Object>> outcomeOpt =
        context.catalog().removeAndCapture(indexName, key);
    if (outcomeOpt.isEmpty()) {
      return false;
    }
    final CatalogPatcher.ReplaceOutcome<Object> outcome = outcomeOpt.get();

    publish(context, catalogName, () -> PatchEvent.remove(catalogName,
        nodeId, outcome.fromVersion(), outcome.toVersion(),
        outcome.fromHash(), outcome.toHash(), indexName,
        context.serializer().serialize(List.of(outcome.oldItem())),
        Instant.now()));
    return true;
  }

  /**
   * Replaces an item on the leader if the lookup finds one, inserts it
   * otherwise, and broadcasts whichever change actually happened.
   *
   * <p>Followers are told the concrete operation rather than the upsert,
   * so they never repeat a decision that depends on state they may not
   * share.
   *
   * @param catalogName the catalog to write to, never null
   * @param indexName   the index to look the item up in, never null
   * @param key         the lookup key, never null
   * @param newItem     the item to store, never null
   *
   * @return true when this node is the leader and applied the upsert
   */
  @SuppressWarnings("unchecked")
  boolean upsertAndSync(final String catalogName, final String indexName,
      final Object key, final Object newItem) {
    Objects.requireNonNull(indexName, "indexName must not be null");
    Objects.requireNonNull(key, "key must not be null");
    Objects.requireNonNull(newItem, "newItem must not be null");

    final LeaderContext context = leaderContext(catalogName);
    if (context == null) {
      return false;
    }
    final Catalog.UpsertCapture<Object> capture =
        context.catalog().upsertAndCapture(indexName, key, newItem);
    final CatalogPatcher.ReplaceOutcome<Object> outcome = capture.outcome();

    publish(context, catalogName, () -> capture.replaced()
        ? PatchEvent.replace(catalogName, nodeId, outcome.fromVersion(),
            outcome.toVersion(), outcome.fromHash(), outcome.toHash(),
            indexName,
            context.serializer().serialize(List.of(outcome.oldItem())),
            context.serializer().serialize(List.of(newItem)), Instant.now())
        : PatchEvent.insert(catalogName, nodeId, outcome.fromVersion(),
            outcome.toVersion(), outcome.fromHash(), outcome.toHash(),
            context.serializer().serialize(List.of(newItem)),
            Instant.now()));
    return true;
  }

  /**
   * The leader-side state a patch operation needs: the catalog it writes
   * to and the serializer that puts items on the wire.
   *
   * @param catalog    the target catalog, never null
   * @param serializer the catalog's serializer, never null
   */
  private record LeaderContext(Catalog<Object> catalog,
      SnapshotSerializer<Object> serializer) {
  }

  /**
   * Validates that this node may run an authoritative patch on the named
   * catalog, and gathers what the operation needs.
   *
   * @param catalogName the catalog name, never null
   *
   * @return the context, or null when this node is not the leader
   *
   * @throws IllegalStateException        if stopped, or the catalog has no
   *                                      serializer
   * @throws CatalogNotAvailableException if the catalog failed to bootstrap
   */
  @SuppressWarnings("unchecked")
  private LeaderContext leaderContext(final String catalogName) {
    Objects.requireNonNull(catalogName, "catalogName must not be null");
    if (callbacks.isStopped()) {
      throw new IllegalStateException(
          "Cannot patch after stop() has been called");
    }
    if (!leaderElection.isLeader()) {
      log.debug("Skipping patch for catalog '{}': not leader", catalogName);
      return null;
    }
    final Catalog<?> catalog = callbacks.requireCatalog(catalogName);
    if (callbacks.isFailedCatalog(catalogName)) {
      throw new CatalogNotAvailableException(catalogName);
    }
    final SnapshotSerializer<Object> serializer = catalog.serializer()
        .map(s -> (SnapshotSerializer<Object>) s)
        .orElseThrow(() -> new IllegalStateException(
            "Catalog '" + catalogName + "' has no SnapshotSerializer; "
                + "cross-node patches require one"));
    return new LeaderContext((Catalog<Object>) catalog, serializer);
  }

  /**
   * Persists the patched snapshot and broadcasts the event describing it.
   *
   * <p>The snapshot is saved before publishing so a follower that falls
   * back to a full reload finds the state the event refers to, rather than
   * the one before it.
   *
   * @param context     the leader context, never null
   * @param catalogName the catalog name, never null
   * @param event       supplies the event to publish, never null
   */
  private void publish(final LeaderContext context, final String catalogName,
      final Supplier<PatchEvent> event) {
    callbacks.reportIndexSizes(context.catalog());
    callbacks.saveSnapshotIfPossible(context.catalog());

    if (syncStrategy == null || !syncStrategy.supportsPatches()) {
      log.debug("Sync strategy does not support patches; skipping publish "
          + "for catalog '{}'. Followers will converge via the next "
          + "periodic refresh.", catalogName);
      return;
    }
    try {
      syncStrategy.publishPatch(event.get());
      metrics.syncPublished(catalogName);
    } catch (final Exception e) {
      log.error("Failed to publish patch event for catalog '{}': {}",
          catalogName, e.getMessage(), e);
      metrics.syncPublishFailed(catalogName, e);
    }
  }

  /**
   * Routes an inbound {@link PatchEvent} by comparing the event's hashes
   * against the local snapshot: an event whose {@code toHash} the local
   * snapshot already carries has been applied and is discarded, an event
   * whose {@code fromHash} matches applies surgically, and anything else
   * falls back to a full reload.
   *
   * <p>Preconditions are deliberately hash-only. A snapshot version is a
   * per-node counter — {@code Catalog.versionCounter} starts at zero in
   * every process and no node ever adopts another's — so the versions of
   * two nodes holding identical data are only coincidentally equal. A
   * follower whose counter has run ahead of the leader's (after a leader
   * restart, or simply having refreshed more often) would then discard
   * live patches as stale and silently stop converging. Content hashes are
   * comparable across nodes by construction, and are already the
   * convergence signal for refresh events.
   *
   * <p>A surgical apply is queued through
   * {@link AsyncRefreshDispatcher#dispatchOrdered}, never the coalescing
   * path: patches are deltas against a specific base state, so dropping one
   * loses that change instead of merely delaying it.
   */
  @SuppressWarnings("unchecked")
  void handlePatch(final PatchEvent event) {
    if (nodeId.equals(event.sourceNodeId())) {
      log.debug("Ignoring patch event from self for catalog '{}'",
          event.catalogName());
      return;
    }

    final Catalog<?> catalog = catalogsByName.get(event.catalogName());
    if (catalog == null) {
      log.warn("Received patch event for unknown catalog '{}'",
          event.catalogName());
      return;
    }

    final String localHash = catalog.currentSnapshot().hash();

    if (event.toHash().equals(localHash)) {
      log.debug("Discarding already-applied patch for catalog '{}': local "
          + "hash is already {}", event.catalogName(), event.toHash());
      metrics.patchDiscarded(event.catalogName());
      return;
    }

    metrics.syncReceived(event.catalogName());

    if (event.fromHash().equals(localHash)) {
      if (dispatcher.dispatchOrdered(event.catalogName(),
          () -> applyPatchLocally(event, (Catalog<Object>) catalog))) {
        return;
      }
      log.warn("Patch queue for catalog '{}' could not accept the event; "
          + "falling back to a full reload", event.catalogName());
    } else {
      log.debug("Patch precondition mismatch for catalog '{}' (local hash "
          + "{} vs event fromHash {}); falling back to a full reload",
          event.catalogName(), localHash, event.fromHash());
    }

    metrics.patchFellBack(event.catalogName());
    dispatcher.dispatch(event.catalogName(),
        () -> callbacks.refreshFromEvent(event.catalogName(), catalog));
  }

  /**
   * Applies a patch event surgically against the local snapshot, falling
   * back to a full reload if local state has drifted between dispatch and
   * apply.
   */
  private void applyPatchLocally(final PatchEvent event,
      final Catalog<Object> catalog) {
    try {
      final SnapshotSerializer<Object> serializer =
          catalog.serializer()
              .map(s -> {
                @SuppressWarnings("unchecked")
                final SnapshotSerializer<Object> cast =
                    (SnapshotSerializer<Object>) s;
                return cast;
              })
              .orElse(null);
      if (serializer == null) {
        log.warn("Catalog '{}' has no serializer; cannot apply patch."
            + " Falling back to full reload.", event.catalogName());
        callbacks.refreshFromEvent(event.catalogName(), catalog);
        return;
      }

      final Optional<CatalogPatcher.ReplaceOutcome<Object>> outcome =
          switch (event.operation()) {
            case INSERT -> catalog.insertWithPrecondition(event.fromHash(),
                serializer.deserialize(event.serializedNewItem()).get(0));
            case REPLACE, REMOVE -> applyLookupBased(event, catalog,
                serializer);
          };
      if (outcome == null) {
        callbacks.refreshFromEvent(event.catalogName(), catalog);
        return;
      }
      if (outcome.isEmpty()) {
        log.debug("Patch precondition failed at apply time for catalog"
            + " '{}'; falling back to full reload.",
            event.catalogName());
        callbacks.refreshFromEvent(event.catalogName(), catalog);
        return;
      }
      log.info("Applied {} surgically for catalog '{}' (leader v{} -> v{})",
          event.operation(), event.catalogName(), event.fromVersion(),
          event.toVersion());
      metrics.patchApplied(event.catalogName());
      callbacks.reportIndexSizes(catalog);
    } catch (final Exception e) {
      log.error("Failed to apply patch surgically for catalog '{}': {}."
          + " Falling back to full reload.",
          event.catalogName(), e.getMessage(), e);
      callbacks.refreshFromEvent(event.catalogName(), catalog);
    }
  }

  /**
   * Applies the operations that locate their target through an index: a
   * replace and a removal both re-derive the lookup key locally from the
   * deserialized old item, so no key ever crosses the wire.
   *
   * @param catalog    the catalog to patch, never null
   * @param event      the inbound event, never null
   * @param serializer the catalog's serializer, never null
   *
   * @return the outcome, empty when the precondition failed, or null when
   *         the key could not be derived and the caller must reload
   */
  private Optional<CatalogPatcher.ReplaceOutcome<Object>> applyLookupBased(
      final PatchEvent event, final Catalog<Object> catalog,
      final SnapshotSerializer<Object> serializer) {

    final Object oldItem =
        serializer.deserialize(event.serializedOldItem()).get(0);
    final Object key = catalog.extractKeyFor(event.indexName(), oldItem);
    if (key == null) {
      log.warn("Patch oldItem for catalog '{}' has no key under index"
          + " '{}'; falling back to full reload.",
          event.catalogName(), event.indexName());
      return null;
    }

    if (event.operation() == PatchOperation.REMOVE) {
      return catalog.removeWithPrecondition(event.indexName(), key,
          event.fromHash());
    }
    return catalog.replaceWithPrecondition(event.indexName(), key,
        event.fromHash(),
        serializer.deserialize(event.serializedNewItem()).get(0));
  }
}
