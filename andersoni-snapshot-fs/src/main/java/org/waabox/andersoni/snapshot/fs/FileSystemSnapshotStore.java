package org.waabox.andersoni.snapshot.fs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import org.waabox.andersoni.snapshot.SerializedSnapshot;
import org.waabox.andersoni.snapshot.SnapshotStore;

/**
 * A {@link SnapshotStore} implementation that persists snapshots to the local
 * filesystem.
 *
 * <p>Snapshots are stored under a configurable base directory. Each catalog
 * gets its own subdirectory containing two files:
 * <ul>
 *   <li>{@code snapshot.dat} - the raw serialized data bytes</li>
 *   <li>{@code snapshot.meta} - metadata in a simple key=value format
 *       (hash, version, createdAt in ISO-8601)</li>
 * </ul>
 *
 * <p>Writes use an atomic pattern: data is written to a temporary file and
 * then renamed, providing crash safety. If the JVM crashes mid-write, the
 * previous snapshot remains intact.
 *
 * <p>Storage layout:
 * <pre>
 * {baseDir}/
 *   {catalogName}/
 *     snapshot.dat
 *     snapshot.meta
 * </pre>
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class FileSystemSnapshotStore implements SnapshotStore {

  /** The name of the data file within each catalog directory. */
  private static final String DATA_FILE = "snapshot.dat";

  /** The name of the metadata file within each catalog directory. */
  private static final String META_FILE = "snapshot.meta";

  /** The metadata key for the content hash. */
  private static final String META_KEY_HASH = "hash";

  /** The metadata key for the version number. */
  private static final String META_KEY_VERSION = "version";

  /** The metadata key for the creation timestamp. */
  private static final String META_KEY_CREATED_AT = "createdAt";

  /** The base directory where all catalog snapshots are stored. */
  private final Path baseDir;

  /**
   * Creates a new FileSystemSnapshotStore with the given base directory.
   *
   * <p>If the base directory does not exist, it is created along with any
   * necessary parent directories.
   *
   * @param baseDir the base directory for snapshot storage, never null
   *
   * @throws NullPointerException if baseDir is null
   * @throws UncheckedIOException if the directory cannot be created
   */
  public FileSystemSnapshotStore(final Path baseDir) {
    Objects.requireNonNull(baseDir, "baseDir must not be null");
    this.baseDir = baseDir;

    try {
      Files.createDirectories(baseDir);
    } catch (final IOException e) {
      throw new UncheckedIOException(
          "Failed to create base directory: " + baseDir, e);
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>Saves the snapshot data and metadata to the filesystem using an
   * atomic write pattern. Data is first written to a temporary file, then
   * atomically moved to the final location to ensure crash safety.
   *
   * <p>If a snapshot already exists for the catalog, it is overwritten.
   *
   * @throws UncheckedIOException if writing to the filesystem fails
   */
  @Override
  public void save(final String catalogName,
      final SerializedSnapshot snapshot) {

    Objects.requireNonNull(catalogName, "catalogName must not be null");
    Objects.requireNonNull(snapshot, "snapshot must not be null");

    final Path catalogDir = baseDir.resolve(catalogName);

    try {
      Files.createDirectories(catalogDir);

      final Path dataFile = catalogDir.resolve(DATA_FILE);
      final Path metaFile = catalogDir.resolve(META_FILE);

      // Write data atomically: write to temp file, then rename.
      final Path tempDataFile = catalogDir.resolve(DATA_FILE + ".tmp");
      Files.write(tempDataFile, snapshot.data());
      Files.move(tempDataFile, dataFile,
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE);

      // Write metadata atomically.
      final String metaContent = buildMetaContent(snapshot);
      final Path tempMetaFile = catalogDir.resolve(META_FILE + ".tmp");
      Files.writeString(tempMetaFile, metaContent);
      Files.move(tempMetaFile, metaFile,
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE);

    } catch (final IOException e) {
      throw new UncheckedIOException(
          "Failed to save snapshot for catalog: " + catalogName, e);
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>Reads the snapshot data and metadata from the filesystem. Returns
   * {@link Optional#empty()} if the catalog directory or snapshot files
   * do not exist.
   *
   * @throws UncheckedIOException if reading from the filesystem fails
   *                              (other than missing files)
   */
  @Override
  public Optional<SerializedSnapshot> load(final String catalogName) {
    Objects.requireNonNull(catalogName, "catalogName must not be null");

    final Path catalogDir = baseDir.resolve(catalogName);
    final Path dataFile = catalogDir.resolve(DATA_FILE);
    final Path metaFile = catalogDir.resolve(META_FILE);

    if (!Files.exists(dataFile) || !Files.exists(metaFile)) {
      return Optional.empty();
    }

    try {
      final byte[] data = Files.readAllBytes(dataFile);
      final String metaContent = Files.readString(metaFile);

      final SerializedSnapshot snapshot = parseSnapshot(
          catalogName, data, metaContent);

      return Optional.of(snapshot);
    } catch (final IOException e) {
      throw new UncheckedIOException(
          "Failed to load snapshot for catalog: " + catalogName, e);
    }
  }

  /**
   * Builds the metadata file content from a serialized snapshot.
   *
   * <p>The format is one key=value pair per line:
   * <pre>
   * hash=abc123
   * version=42
   * createdAt=2026-01-15T10:30:00Z
   * </pre>
   *
   * @param snapshot the snapshot to extract metadata from, never null
   *
   * @return the metadata file content, never null
   */
  private String buildMetaContent(final SerializedSnapshot snapshot) {
    return META_KEY_HASH + "=" + snapshot.hash() + "\n"
        + META_KEY_VERSION + "=" + snapshot.version() + "\n"
        + META_KEY_CREATED_AT + "=" + snapshot.createdAt().toString() + "\n";
  }

  /**
   * Parses a serialized snapshot from data bytes and metadata content.
   *
   * @param catalogName the catalog name, never null
   * @param data        the raw data bytes, never null
   * @param metaContent the metadata file content, never null
   *
   * @return the reconstructed serialized snapshot, never null
   *
   * @throws IllegalStateException if required metadata keys are missing
   */
  private SerializedSnapshot parseSnapshot(final String catalogName,
      final byte[] data, final String metaContent) {

    String hash = null;
    Long version = null;
    Instant createdAt = null;

    final String[] lines = metaContent.split("\n");
    for (final String line : lines) {
      final String trimmed = line.trim();
      if (trimmed.isEmpty()) {
        continue;
      }

      final int equalsIndex = trimmed.indexOf('=');
      if (equalsIndex < 0) {
        continue;
      }

      final String key = trimmed.substring(0, equalsIndex);
      final String value = trimmed.substring(equalsIndex + 1);

      switch (key) {
        case META_KEY_HASH -> hash = value;
        case META_KEY_VERSION -> version = Long.parseLong(value);
        case META_KEY_CREATED_AT -> createdAt = Instant.parse(value);
        default -> { /* ignore unknown keys for forward compatibility */ }
      }
    }

    if (hash == null || version == null || createdAt == null) {
      throw new IllegalStateException(
          "Incomplete metadata for catalog: " + catalogName
              + ". Missing one of: hash, version, createdAt");
    }

    return new SerializedSnapshot(catalogName, hash, version, createdAt, data);
  }
}
