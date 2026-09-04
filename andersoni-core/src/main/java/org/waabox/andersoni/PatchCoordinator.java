package org.waabox.andersoni;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.waabox.andersoni.leader.LeaderElectionStrategy;
import org.waabox.andersoni.metrics.AndersoniMetrics;
import org.waabox.andersoni.snapshot.SnapshotSerializer;
import org.waabox.andersoni.sync.PatchEvent;
import org.waabox.andersoni.sync.SyncStrategy;

/**
 * Cross-node coordination of snapshot patches for {@link Andersoni}.
 *
 * <p>Hosts both ends of the patch protocol:
 * <ul>
 *   <li>Leader side: {@link #replaceAndSync} performs the local replace,
 *       saves the snapshot, and publishes a {@link PatchEvent}.</li>
 *   <li>Follower side: {@link #handlePatch} version-keys an inbound event
 *       (stale / surgical apply / full-reload fallback) and dispatches the
 *       resulting work through the shared {@link AsyncRefreshDispatcher}.</li>
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
   * @throws AmbiguousReplaceException   if the lookup returned more than
   *                                     one item
   */
  @SuppressWarnings("unchecked")
  boolean replaceAndSync(final String catalogName, final String indexName,
      final Object key, final Object newItem) {
    Objects.requireNonNull(catalogName, "catalogName must not be null");
    Objects.requireNonNull(indexName, "indexName must not be null");
    Objects.requireNonNull(key, "key must not be null");
    Objects.requireNonNull(newItem, "newItem must not be null");
    if (callbacks.isStopped()) {
      throw new IllegalStateException(
          "Cannot replace after stop() has been called");
    }
    if (!leaderElection.isLeader()) {
      log.debug("Skipping replaceAndSync for catalog '{}': not leader",
          catalogName);
      return false;
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

    final Catalog<Object> typedCatalog = (Catalog<Object>) catalog;
    final Optional<CatalogPatcher.ReplaceOutcome<Object>> outcomeOpt =
        typedCatalog.replaceAndCapture(indexName, key, newItem);
    if (outcomeOpt.isEmpty()) {
      return false;
    }
    final CatalogPatcher.ReplaceOutcome<Object> outcome = outcomeOpt.get();

    callbacks.reportIndexSizes(catalog);
    callbacks.saveSnapshotIfPossible(catalog);

    if (syncStrategy == null || !syncStrategy.supportsPatches()) {
      log.debug("Sync strategy does not support patches; skipping publish "
          + "for catalog '{}'. Followers will converge via the next "
          + "periodic refresh.", catalogName);
      return true;
    }

    final byte[] oldItemBytes = serializer.serialize(
        List.of(outcome.oldItem()));
    final byte[] newItemBytes = serializer.serialize(List.of(newItem));
    final PatchEvent event = new PatchEvent(
        catalogName,
        nodeId,
        outcome.fromVersion(),
        outcome.toVersion(),
        outcome.fromHash(),
        outcome.toHash(),
        indexName,
        oldItemBytes,
        newItemBytes,
        Instant.now());
    try {
      syncStrategy.publishPatch(event);
      metrics.syncPublished(catalogName);
    } catch (final Exception e) {
      log.error("Failed to publish patch event for catalog '{}': {}",
          catalogName, e.getMessage(), e);
      metrics.syncPublishFailed(catalogName, e);
    }
    return true;
  }

  /**
   * Routes an inbound {@link PatchEvent} via version-keyed precedence:
   * stale events are discarded, events whose precondition matches the
   * local snapshot apply surgically, and events with a gap/hash mismatch
   * fall back to a full reload.
   *
   * <p>Dispatched through {@link AsyncRefreshDispatcher} so concurrent
   * patch events on the same catalog are coalesced.
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

    final Snapshot<?> snapshot = catalog.currentSnapshot();
    if (event.toVersion() <= snapshot.version()) {
      log.debug("Discarding stale patch for catalog '{}': toVersion={} "
          + "<= localVersion={}", event.catalogName(),
          event.toVersion(), snapshot.version());
      metrics.patchDiscarded(event.catalogName());
      return;
    }

    metrics.syncReceived(event.catalogName());

    if (event.fromVersion() == snapshot.version()
        && event.fromHash().equals(snapshot.hash())) {
      dispatcher.dispatch(event.catalogName(),
          () -> applyPatchLocally(event, (Catalog<Object>) catalog));
      return;
    }

    log.debug("Patch precondition mismatch for catalog '{}'"
        + " (local v{}/{} vs event from v{}/{}); falling back"
        + " to full reload", event.catalogName(),
        snapshot.version(), snapshot.hash(),
        event.fromVersion(), event.fromHash());
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

      final Object oldItem = serializer.deserialize(
          event.serializedOldItem()).get(0);
      final Object newItem = serializer.deserialize(
          event.serializedNewItem()).get(0);
      final Object key = catalog.extractKeyFor(event.indexName(), oldItem);
      if (key == null) {
        log.warn("Patch oldItem for catalog '{}' has no key under index"
            + " '{}'; falling back to full reload.",
            event.catalogName(), event.indexName());
        callbacks.refreshFromEvent(event.catalogName(), catalog);
        return;
      }

      final Optional<CatalogPatcher.ReplaceOutcome<Object>> outcome =
          catalog.replaceWithPrecondition(event.indexName(), key,
              oldItem, newItem);
      if (outcome.isEmpty()) {
        log.debug("Patch precondition failed at apply time for catalog"
            + " '{}'; falling back to full reload.",
            event.catalogName());
        callbacks.refreshFromEvent(event.catalogName(), catalog);
        return;
      }
      log.info("Applied patch surgically for catalog '{}': v{} -> v{}",
          event.catalogName(), event.fromVersion(), event.toVersion());
      metrics.patchApplied(event.catalogName());
      callbacks.reportIndexSizes(catalog);
    } catch (final Exception e) {
      log.error("Failed to apply patch surgically for catalog '{}': {}."
          + " Falling back to full reload.",
          event.catalogName(), e.getMessage(), e);
      callbacks.refreshFromEvent(event.catalogName(), catalog);
    }
  }
}
