package org.waabox.andersoni.metrics;

import java.util.Collection;
import org.waabox.andersoni.Catalog;
import org.waabox.andersoni.PatchOperation;

/**
 * An abstraction for recording operational metrics of the Andersoni
 * cache library.
 *
 * <p>Implementations can integrate with monitoring systems such as
 * Micrometer, Prometheus, or Datadog. Use {@link NoopAndersoniMetrics}
 * when metrics collection is not required.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public interface AndersoniMetrics {

  /**
   * Records a snapshot load event.
   *
   * @param catalogName the name of the catalog whose snapshot was loaded,
   *                    never null
   * @param source      a description of the snapshot source (e.g. "s3",
   *                    "filesystem"), never null
   */
  void snapshotLoaded(String catalogName, String source);

  /**
   * Records a failed catalog refresh attempt.
   *
   * @param catalogName the name of the catalog whose refresh failed,
   *                    never null
   * @param cause       the throwable that caused the failure, never null
   */
  void refreshFailed(String catalogName, Throwable cause);

  /**
   * Records the estimated memory size of an index after a refresh or
   * bootstrap.
   *
   * @param catalogName        the name of the catalog, never null
   * @param indexName          the name of the index, never null
   * @param estimatedSizeBytes the estimated structural memory footprint
   *                           in bytes
   */
  void indexSizeReported(String catalogName, String indexName,
      long estimatedSizeBytes);

  /**
   * Records a sync event published to the cluster.
   *
   * @param catalogName the name of the catalog, never null
   */
  default void syncPublished(final String catalogName) {
  }

  /**
   * Records a sync event received from another node.
   *
   * @param catalogName the name of the catalog, never null
   */
  default void syncReceived(final String catalogName) {
  }

  /**
   * Records a sync publish failure.
   *
   * @param catalogName the name of the catalog, never null
   * @param cause       the failure cause, never null
   */
  default void syncPublishFailed(final String catalogName, final Throwable cause) {
  }

  /**
   * Records a sync receive/deserialization failure.
   *
   * @param cause the failure cause, never null
   */
  default void syncReceiveFailed(final Throwable cause) {
  }

  /** Records a successful local patch operation.
   * @param catalogName the name of the catalog, never null
   * @param operation   the type of patch operation, never null
   */
  default void patchApplied(final String catalogName,
      final PatchOperation operation) {
  }

  /** Records a failed local patch operation.
   * @param catalogName the name of the catalog, never null
   * @param operation   the type of patch operation, never null
   * @param cause       the failure cause, never null
   */
  default void patchFailed(final String catalogName,
      final PatchOperation operation, final Throwable cause) {
  }

  /** Records a patch event published to the cluster.
   * @param catalogName the name of the catalog, never null
   * @param operation   the type of patch operation, never null
   */
  default void patchPublished(final String catalogName,
      final PatchOperation operation) {
  }

  /** Records a patch publish failure.
   * @param catalogName the name of the catalog, never null
   * @param operation   the type of patch operation, never null
   * @param cause       the failure cause, never null
   */
  default void patchPublishFailed(final String catalogName,
      final PatchOperation operation, final Throwable cause) {
  }

  /** Records a patch event received from another node.
   * @param catalogName the name of the catalog, never null
   * @param operation   the type of patch operation, never null
   */
  default void patchReceived(final String catalogName,
      final PatchOperation operation) {
  }

  /**
   * Called when the Andersoni engine has fully started.
   *
   * <p>Implementations can use this to begin periodic metric reporting.
   * The provided catalogs are the same instances managed by the engine
   * and their snapshots can be read lock-free at any time.
   *
   * @param catalogs the registered catalogs, never null
   * @param nodeId   the node identifier, never null
   */
  default void start(final Collection<Catalog<?>> catalogs,
      final String nodeId) {
  }

  /**
   * Called when the Andersoni engine is stopping.
   *
   * <p>Implementations should release any resources (schedulers, clients)
   * allocated during {@link #start}.
   */
  default void stop() {
  }
}
