package org.waabox.andersoni;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.waabox.andersoni.snapshot.SerializedSnapshot;
import org.waabox.andersoni.snapshot.SnapshotMetadata;
import org.waabox.andersoni.snapshot.SnapshotStore;

/**
 * An in-memory {@link SnapshotStore} for tests, with failure switches and
 * call counters.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
final class InMemorySnapshotStore implements SnapshotStore {

  private final Map<String, SerializedSnapshot> snapshots = new ConcurrentHashMap<>();
  private final AtomicInteger describeCalls = new AtomicInteger();
  private final AtomicInteger loadCalls = new AtomicInteger();
  private final AtomicInteger saveCalls = new AtomicInteger();

  /** When true, the next save throws and the flag resets. */
  volatile boolean failNextSave;

  /** When true, every describe throws. */
  volatile boolean failDescribe;

  /** When true, every load throws. */
  volatile boolean failLoad;

  @Override
  public void save(final String catalogName, final SerializedSnapshot snapshot) {
    saveCalls.incrementAndGet();
    if (failNextSave) {
      failNextSave = false;
      throw new IllegalStateException("simulated save failure");
    }
    snapshots.put(catalogName, snapshot);
  }

  @Override
  public Optional<SerializedSnapshot> load(final String catalogName) {
    loadCalls.incrementAndGet();
    if (failLoad) {
      throw new IllegalStateException("simulated load failure");
    }
    return Optional.ofNullable(snapshots.get(catalogName));
  }

  @Override
  public Optional<SnapshotMetadata> describe(final String catalogName) {
    describeCalls.incrementAndGet();
    if (failDescribe) {
      throw new IllegalStateException("simulated describe failure");
    }
    return Optional.ofNullable(snapshots.get(catalogName)).map(SnapshotMetadata::of);
  }

  void put(final String catalogName, final SerializedSnapshot snapshot) {
    snapshots.put(catalogName, snapshot);
  }

  Optional<SerializedSnapshot> get(final String catalogName) {
    return Optional.ofNullable(snapshots.get(catalogName));
  }

  void clear() {
    snapshots.clear();
  }

  int describeCalls() {
    return describeCalls.get();
  }

  int loadCalls() {
    return loadCalls.get();
  }

  int saveCalls() {
    return saveCalls.get();
  }
}
