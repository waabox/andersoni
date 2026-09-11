package org.waabox.andersoni.it;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.waabox.andersoni.sync.RefreshEvent;
import org.waabox.andersoni.sync.RefreshListener;
import org.waabox.andersoni.sync.SyncStrategy;

/**
 * A {@link SyncStrategy} decorator that can be made to drop incoming refresh
 * events on demand.
 *
 * <p>Used by the cluster integration test to simulate a follower that missed
 * a leader's refresh event (e.g. a transient consumer outage) deterministically,
 * through the node's HTTP API, while the underlying transport (Kafka) stays
 * healthy. Publishing is never affected: only reception is dropped.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class FaultInjectingSyncStrategy implements SyncStrategy {

  /** The class logger. */
  private static final Logger LOG = LoggerFactory.getLogger(FaultInjectingSyncStrategy.class);

  /** The wrapped sync strategy. */
  private final SyncStrategy delegate;

  /** Whether incoming refresh events should be dropped. */
  private volatile boolean dropIncoming;

  /** The number of incoming refresh events dropped so far. */
  private final AtomicLong droppedEvents = new AtomicLong();

  /**
   * Creates a fault-injecting sync strategy wrapping a delegate.
   *
   * @param theDelegate the sync strategy to delegate to, never null.
   */
  public FaultInjectingSyncStrategy(final SyncStrategy theDelegate) {
    delegate = Objects.requireNonNull(theDelegate, "delegate must not be null");
  }

  /**
   * Sets whether incoming refresh events should be dropped.
   *
   * @param shouldDrop {@code true} to drop every subsequent incoming event.
   */
  public void dropIncoming(final boolean shouldDrop) {
    dropIncoming = shouldDrop;
  }

  /**
   * Returns the number of incoming refresh events dropped so far.
   *
   * @return the dropped event count.
   */
  public long droppedEvents() {
    return droppedEvents.get();
  }

  /** {@inheritDoc} */
  @Override
  public void publish(final RefreshEvent event) {
    delegate.publish(event);
  }

  /** {@inheritDoc} */
  @Override
  public void subscribe(final RefreshListener listener) {
    delegate.subscribe(event -> {
      if (dropIncoming) {
        droppedEvents.incrementAndGet();
        LOG.info("Dropping incoming refresh event for catalog '{}' (fault injection)",
            event.catalogName());
        return;
      }
      listener.onRefresh(event);
    });
  }

  /** {@inheritDoc} */
  @Override
  public void start() {
    delegate.start();
  }

  /** {@inheritDoc} */
  @Override
  public void stop() {
    delegate.stop();
  }
}
