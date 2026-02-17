package org.waabox.andersoni.metrics;

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
}
