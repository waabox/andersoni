package org.waabox.andersoni;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.waabox.andersoni.snapshot.SerializedSnapshot;
import org.waabox.andersoni.snapshot.SnapshotMetadata;
import org.waabox.andersoni.snapshot.SnapshotSerializer;
import org.waabox.andersoni.snapshot.SnapshotStore;

/**
 * The single place where the engine reads and writes the
 * {@link SnapshotStore}, and the owner of the per-catalog
 * <em>applied store hash</em>.
 *
 * <p>The applied store hash is the hash of the store object this node last
 * loaded or wrote for a catalog. Reconciliation compares the store's current
 * hash against it, never against the in-memory snapshot hash, because the
 * in-memory hash is recomputed after deserializing and may differ from the
 * stored one when a serializer's round trip is not byte-stable. An absent
 * applied hash always means "this node must reconcile".
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
final class SnapshotStoreBridge {

  /** The class logger. */
  private static final Logger log = LoggerFactory.getLogger(SnapshotStoreBridge.class);

  /** The configured store, or null when none. */
  private final SnapshotStore store;

  /** The store hash last applied or written per catalog name. */
  private final Map<String, String> appliedStoreHash = new ConcurrentHashMap<>();

  /** Per-catalog monitors serializing {@link #save(Catalog)}. */
  private final Map<String, Object> saveLocks = new ConcurrentHashMap<>();

  /**
   * Creates a bridge over the given store.
   *
   * @param store the store, may be null when snapshots are not persisted
   */
  SnapshotStoreBridge(final SnapshotStore store) {
    this.store = store;
  }

  /** @return true if a store is configured. */
  boolean isConfigured() {
    return store != null;
  }

  /** @return the configured store's implementation class name, or "none" when absent. */
  String storeDescription() {
    return store == null ? "none" : store.getClass().getName();
  }

  /**
   * @param catalog the catalog, never null
   * @return true if the catalog can be persisted: a store is configured and
   *         the catalog has a serializer
   */
  boolean supports(final Catalog<?> catalog) {
    return store != null && catalog.serializer().isPresent();
  }

  /**
   * Describes the stored snapshot of a catalog without loading it.
   *
   * @param catalogName the catalog name, never null
   * @return the metadata, or empty if no store or no snapshot
   */
  Optional<SnapshotMetadata> describe(final String catalogName) {
    if (store == null) {
      return Optional.empty();
    }
    return store.describe(catalogName);
  }

  /**
   * @param catalogName the catalog name, never null
   * @return the store hash this node last applied or wrote, or empty
   */
  Optional<String> appliedStoreHash(final String catalogName) {
    return Optional.ofNullable(appliedStoreHash.get(catalogName));
  }

  /**
   * Forgets the applied hash: the local state changed from a source other
   * than the store (a local refresh, a DataLoader fallback), so the next
   * reconciliation pass must act.
   *
   * @param catalogName the catalog name, never null
   */
  void markUnknown(final String catalogName) {
    appliedStoreHash.remove(catalogName);
  }

  /**
   * Loads the stored snapshot into the catalog.
   *
   * @param catalog the catalog to load, never null
   * @return true if a snapshot was loaded; false if no store, no serializer
   *         or no snapshot exists
   */
  @SuppressWarnings("unchecked")
  boolean load(final Catalog<?> catalog) {
    if (!supports(catalog)) {
      return false;
    }
    final Optional<SerializedSnapshot> snapshotOpt = store.load(catalog.name());
    if (snapshotOpt.isEmpty()) {
      return false;
    }
    final SerializedSnapshot serialized = snapshotOpt.get();
    final SnapshotSerializer<Object> serializer =
        (SnapshotSerializer<Object>) catalog.serializer().get();
    final List<Object> data = serializer.deserialize(serialized.data());
    final Catalog<Object> typedCatalog = (Catalog<Object>) catalog;
    typedCatalog.refresh(data);
    appliedStoreHash.put(catalog.name(), serialized.hash());
    log.debug("Applied store snapshot for catalog '{}' (hash={})",
        catalog.name(), serialized.hash());
    return true;
  }

  /**
   * Serializes and saves the catalog's current snapshot.
   *
   * <p>The stored hash is the SHA-256 of the bytes actually written, so a
   * store can verify integrity on load without depending on two separate
   * {@code serialize()} calls agreeing.
   *
   * <p>The read-snapshot/serialize/store/record-hash sequence is serialized
   * per catalog so that concurrent callers (a leader repair and an
   * application-thread {@code refreshAndSync} can both call this) never
   * interleave and let an older snapshot's write land after a newer one's.
   *
   * @param catalog the catalog to save, never null
   */
  @SuppressWarnings("unchecked")
  void save(final Catalog<?> catalog) {
    if (!supports(catalog)) {
      return;
    }
    synchronized (saveLocks.computeIfAbsent(catalog.name(), k -> new Object())) {
      final SnapshotSerializer<Object> serializer =
          (SnapshotSerializer<Object>) catalog.serializer().get();
      final Snapshot<?> snapshot = catalog.currentSnapshot();
      final List<Object> data = (List<Object>) snapshot.data();
      final byte[] bytes = serializer.serialize(data);
      final String hash = sha256Hex(bytes);
      final SerializedSnapshot serialized = new SerializedSnapshot(
          catalog.name(), hash, snapshot.version(), snapshot.createdAt(), bytes);
      store.save(catalog.name(), serialized);
      appliedStoreHash.put(catalog.name(), hash);
      log.debug("Saved store snapshot for catalog '{}' (hash={})", catalog.name(), hash);
    }
  }

  /**
   * Returns the lowercase hex SHA-256 digest of the given bytes.
   *
   * @param bytes the bytes to digest, never null
   * @return the hex-encoded digest, never null
   */
  static String sha256Hex(final byte[] bytes) {
    try {
      final byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
      final StringBuilder builder = new StringBuilder(digest.length * 2);
      for (final byte b : digest) {
        builder.append(Character.forDigit((b >> 4) & 0xF, 16));
        builder.append(Character.forDigit(b & 0xF, 16));
      }
      return builder.toString();
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 algorithm not available", e);
    }
  }
}
