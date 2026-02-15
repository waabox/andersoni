package org.waabox.andersoni.snapshot;

import java.util.Optional;

/**
 * A persistent store for catalog snapshots.
 *
 * <p>Implementations define where snapshots are stored (e.g. S3,
 * local filesystem, database) and how they are retrieved. The store
 * enables fast catalog warm-up by loading a pre-serialized snapshot
 * instead of querying the original data source.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public interface SnapshotStore {

  /**
   * Saves a serialized snapshot for the given catalog.
   *
   * <p>If a snapshot already exists for the catalog, it is replaced.
   *
   * @param catalogName the name of the catalog, never null
   * @param snapshot    the serialized snapshot to persist, never null
   */
  void save(String catalogName, SerializedSnapshot snapshot);

  /**
   * Loads the most recent serialized snapshot for the given catalog.
   *
   * @param catalogName the name of the catalog, never null
   * @return an optional containing the snapshot if one exists, or empty
   *         if no snapshot has been stored for this catalog
   */
  Optional<SerializedSnapshot> load(String catalogName);
}
