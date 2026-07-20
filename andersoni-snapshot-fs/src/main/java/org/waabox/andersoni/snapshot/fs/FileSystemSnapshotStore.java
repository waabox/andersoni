package org.waabox.andersoni.snapshot.fs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import org.waabox.andersoni.snapshot.SerializedSnapshot;
import org.waabox.andersoni.snapshot.SnapshotStore;

/**
 * A {@link SnapshotStore} implementation that persists snapshots to the local
 * filesystem.
 *
 * <p>Snapshots are stored under a configurable base directory. Each catalog
 * gets its own subdirectory holding a single {@code snapshot.bin} file: a
 * key=value metadata header (hash, version, createdAt in ISO-8601), a blank
 * line, then the raw serialized data bytes.
 *
 * <p><strong>Metadata and data are one atomic unit.</strong> They live in the
 * same file precisely so a snapshot can never be committed halfway: the file
 * is written to a temporary path and then moved into place with a single
 * atomic rename. A crash mid-write leaves the previous snapshot intact, and a
 * concurrent reader observes either the old snapshot or the new one — never
 * one snapshot's data paired with another's metadata, which is what an
 * earlier two-file layout allowed.
 *
 * <p>Storage layout:
 * <pre>
 * {baseDir}/
 *   {catalogName}/
 *     snapshot.bin
 * </pre>
 *
 * <p>Snapshots written by earlier versions, which used a separate
 * {@code snapshot.dat} and {@code snapshot.meta}, are still readable; the
 * next save migrates the catalog to the single-file layout.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class FileSystemSnapshotStore implements SnapshotStore {

  /** Allowed catalog-name characters. Anything else (path separators, {@code
   *  ..}) is rejected to prevent writing/reading outside the base directory. */
  private static final Pattern SAFE_CATALOG_NAME =
      Pattern.compile("^[A-Za-z0-9._-]+$");

  /** The single snapshot file within each catalog directory, holding the
   *  metadata header and the data bytes as one atomically committed unit. */
  private static final String SNAPSHOT_FILE = "snapshot.bin";

  /** The name of the legacy data file, still readable on load. */
  private static final String DATA_FILE = "snapshot.dat";

  /** The name of the legacy metadata file, still readable on load. */
  private static final String META_FILE = "snapshot.meta";

  /** Separates the metadata header from the data bytes: a blank line. Header
   *  values never contain a newline, so the first occurrence of this sequence
   *  always terminates the header. */
  private static final byte[] HEADER_SEPARATOR =
      "\n\n".getBytes(StandardCharsets.UTF_8);

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
   * <p>Writes the metadata header and the data bytes to a temporary file and
   * then commits them with a single atomic rename, so the snapshot is never
   * visible in a partially written state.
   *
   * <p>If a snapshot already exists for the catalog, it is overwritten. A
   * catalog still stored in the legacy two-file layout is migrated here.
   *
   * @throws UncheckedIOException if writing to the filesystem fails
   */
  @Override
  public void save(final String catalogName,
      final SerializedSnapshot snapshot) {

    Objects.requireNonNull(catalogName, "catalogName must not be null");
    Objects.requireNonNull(snapshot, "snapshot must not be null");
    validateCatalogName(catalogName);

    final Path catalogDir = baseDir.resolve(catalogName);

    try {
      Files.createDirectories(catalogDir);

      // The header's own trailing newline is dropped so the separator below
      // is the only blank line in the file.
      final byte[] header = buildMetaContent(snapshot).stripTrailing()
          .getBytes(StandardCharsets.UTF_8);
      final byte[] data = snapshot.data();

      final byte[] content =
          new byte[header.length + HEADER_SEPARATOR.length + data.length];
      System.arraycopy(header, 0, content, 0, header.length);
      System.arraycopy(HEADER_SEPARATOR, 0, content, header.length,
          HEADER_SEPARATOR.length);
      System.arraycopy(data, 0, content,
          header.length + HEADER_SEPARATOR.length, data.length);

      // A unique temp name keeps concurrent savers from overwriting each
      // other's half-written file before either commits.
      final Path tempFile = Files.createTempFile(catalogDir, SNAPSHOT_FILE,
          ".tmp");
      try {
        Files.write(tempFile, content);
        Files.move(tempFile, catalogDir.resolve(SNAPSHOT_FILE),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
      } catch (final IOException e) {
        Files.deleteIfExists(tempFile);
        throw e;
      }

      // The snapshot is committed; drop any legacy files it supersedes.
      Files.deleteIfExists(catalogDir.resolve(DATA_FILE));
      Files.deleteIfExists(catalogDir.resolve(META_FILE));

    } catch (final IOException e) {
      throw new UncheckedIOException(
          "Failed to save snapshot for catalog: " + catalogName, e);
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>Reads the snapshot from the single {@code snapshot.bin} file, falling
   * back to the legacy {@code snapshot.dat} / {@code snapshot.meta} pair for
   * catalogs written by an earlier version. Returns {@link Optional#empty()}
   * if no snapshot exists for the catalog.
   *
   * @throws UncheckedIOException if reading from the filesystem fails
   *                              (other than missing files)
   */
  @Override
  public Optional<SerializedSnapshot> load(final String catalogName) {
    Objects.requireNonNull(catalogName, "catalogName must not be null");
    validateCatalogName(catalogName);

    final Path catalogDir = baseDir.resolve(catalogName);
    final Path snapshotFile = catalogDir.resolve(SNAPSHOT_FILE);

    try {
      if (Files.exists(snapshotFile)) {
        return Optional.of(
            parseSnapshotFile(catalogName, Files.readAllBytes(snapshotFile)));
      }
      return loadLegacy(catalogName, catalogDir);
    } catch (final IOException e) {
      throw new UncheckedIOException(
          "Failed to load snapshot for catalog: " + catalogName, e);
    }
  }

  /**
   * Reads a snapshot stored in the legacy two-file layout.
   *
   * @param catalogName the catalog name, never null
   * @param catalogDir  the catalog directory, never null
   *
   * @return the snapshot, or empty if the legacy files are not both present
   *
   * @throws IOException if reading fails
   */
  private Optional<SerializedSnapshot> loadLegacy(final String catalogName,
      final Path catalogDir) throws IOException {

    final Path dataFile = catalogDir.resolve(DATA_FILE);
    final Path metaFile = catalogDir.resolve(META_FILE);

    if (!Files.exists(dataFile) || !Files.exists(metaFile)) {
      return Optional.empty();
    }

    return Optional.of(parseSnapshot(catalogName,
        Files.readAllBytes(dataFile), Files.readString(metaFile)));
  }

  /**
   * Splits a single-file snapshot into its metadata header and data bytes.
   *
   * @param catalogName the catalog name, never null
   * @param content     the full file content, never null
   *
   * @return the reconstructed snapshot, never null
   *
   * @throws IllegalStateException if the header separator is missing
   */
  private SerializedSnapshot parseSnapshotFile(final String catalogName,
      final byte[] content) {

    final int separator = indexOfHeaderSeparator(content);
    if (separator < 0) {
      throw new IllegalStateException(
          "Malformed snapshot file for catalog: " + catalogName
              + ". Missing the blank line separating header from data.");
    }

    final String metaContent =
        new String(content, 0, separator, StandardCharsets.UTF_8);
    final byte[] data = Arrays.copyOfRange(content,
        separator + HEADER_SEPARATOR.length, content.length);

    return parseSnapshot(catalogName, data, metaContent);
  }

  /**
   * Finds the blank line separating the metadata header from the data.
   *
   * @param content the full file content, never null
   *
   * @return the index at which the separator starts, or {@code -1}
   */
  private static int indexOfHeaderSeparator(final byte[] content) {
    for (int i = 0; i + HEADER_SEPARATOR.length <= content.length; i++) {
      boolean matches = true;
      for (int j = 0; j < HEADER_SEPARATOR.length; j++) {
        if (content[i + j] != HEADER_SEPARATOR[j]) {
          matches = false;
          break;
        }
      }
      if (matches) {
        return i;
      }
    }
    return -1;
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

  /** Rejects catalog names that could escape the base directory.
   *
   * @param catalogName the name to validate, never null.
   * @throws IllegalArgumentException if the name contains path separators,
   *     is {@code .}/{@code ..}, or uses characters outside the safe set.
   */
  private static void validateCatalogName(final String catalogName) {
    if (catalogName.equals(".") || catalogName.equals("..")
        || !SAFE_CATALOG_NAME.matcher(catalogName).matches()) {
      throw new IllegalArgumentException(
          "Invalid catalog name: '" + catalogName + "'. Allowed characters:"
              + " letters, digits, '.', '_', '-' (no path separators).");
    }
  }
}
