package org.waabox.andersoni.metrics;

/**
 * A no-operation implementation of {@link AndersoniMetrics}.
 *
 * <p>All methods in this class are intentionally empty. Use this
 * implementation when metrics collection is not required or during
 * testing.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public class NoopAndersoniMetrics implements AndersoniMetrics {

  /** {@inheritDoc} */
  @Override
  public void catalogRefreshed(final String catalogName,
      final long durationMs, final long itemCount) {
  }

  /** {@inheritDoc} */
  @Override
  public void snapshotLoaded(final String catalogName, final String source) {
  }

  /** {@inheritDoc} */
  @Override
  public void searchExecuted(final String catalogName,
      final String indexName, final long durationNs) {
  }

  /** {@inheritDoc} */
  @Override
  public void refreshFailed(final String catalogName, final Throwable cause) {
  }

  /** {@inheritDoc} */
  @Override
  public void indexSizeReported(final String catalogName,
      final String indexName, final long estimatedSizeBytes) {
  }
}
