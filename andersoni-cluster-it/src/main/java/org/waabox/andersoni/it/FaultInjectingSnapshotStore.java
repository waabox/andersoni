package org.waabox.andersoni.it;

import java.util.Objects;
import java.util.Optional;

import org.waabox.andersoni.snapshot.SerializedSnapshot;
import org.waabox.andersoni.snapshot.SnapshotMetadata;
import org.waabox.andersoni.snapshot.SnapshotStore;

/**
 * A {@link SnapshotStore} decorator that can be made to fail saves on demand.
 *
 * <p>Used by the cluster integration test to simulate a snapshot store
 * outage (e.g. an unreachable filesystem or object store) deterministically,
 * through the node's HTTP API, instead of manipulating the host filesystem or
 * network.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class FaultInjectingSnapshotStore implements SnapshotStore {

  /** The wrapped store. */
  private final SnapshotStore delegate;

  /** Whether {@link #save(String, SerializedSnapshot)} should fail. */
  private volatile boolean failSave;

  /**
   * Creates a fault-injecting snapshot store wrapping a delegate.
   *
   * @param theDelegate the store to delegate to, never null.
   */
  public FaultInjectingSnapshotStore(final SnapshotStore theDelegate) {
    delegate = Objects.requireNonNull(theDelegate, "delegate must not be null");
  }

  /**
   * Sets whether {@link #save(String, SerializedSnapshot)} should fail.
   *
   * @param shouldFail {@code true} to make every subsequent save throw.
   */
  public void failSave(final boolean shouldFail) {
    failSave = shouldFail;
  }

  /** {@inheritDoc} */
  @Override
  public void save(final String catalogName, final SerializedSnapshot snapshot) {
    if (failSave) {
      throw new IllegalStateException("simulated snapshot store save failure");
    }
    delegate.save(catalogName, snapshot);
  }

  /** {@inheritDoc} */
  @Override
  public Optional<SerializedSnapshot> load(final String catalogName) {
    return delegate.load(catalogName);
  }

  /** {@inheritDoc} */
  @Override
  public Optional<SnapshotMetadata> describe(final String catalogName) {
    return delegate.describe(catalogName);
  }
}
