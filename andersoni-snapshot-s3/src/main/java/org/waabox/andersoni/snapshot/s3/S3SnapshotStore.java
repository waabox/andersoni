package org.waabox.andersoni.snapshot.s3;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.waabox.andersoni.snapshot.SerializedSnapshot;
import org.waabox.andersoni.snapshot.SnapshotStore;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * A {@link SnapshotStore} implementation that persists snapshots to
 * Amazon S3.
 *
 * <p>Snapshots are stored as single S3 objects with the following key
 * layout:
 * <pre>
 * {prefix}{catalogName}/snapshot.dat
 * </pre>
 *
 * <p>Snapshot metadata (hash, version, creation timestamp, catalog name)
 * is stored as S3 user metadata headers on the object:
 * <ul>
 *   <li>{@code x-amz-meta-hash} - content hash for integrity</li>
 *   <li>{@code x-amz-meta-version} - monotonically increasing version</li>
 *   <li>{@code x-amz-meta-created-at} - ISO-8601 creation timestamp</li>
 *   <li>{@code x-amz-meta-catalog-name} - the catalog name</li>
 * </ul>
 *
 * <p>When an {@link S3Client} is not provided via the config, this store
 * creates a default client from the configured region. In that case,
 * the client is closed when {@link #close()} is called.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class S3SnapshotStore implements SnapshotStore, AutoCloseable {

  /** Logger for this class. */
  private static final Logger log =
      LoggerFactory.getLogger(S3SnapshotStore.class);

  /** The name of the snapshot data file within each catalog prefix. */
  private static final String DATA_FILE = "snapshot.dat";

  /** S3 user metadata key for the content hash. */
  private static final String META_HASH = "hash";

  /** S3 user metadata key for the version number. */
  private static final String META_VERSION = "version";

  /** S3 user metadata key for the creation timestamp (ISO-8601). */
  private static final String META_CREATED_AT = "created-at";

  /** S3 user metadata key for the catalog name. */
  private static final String META_CATALOG_NAME = "catalog-name";

  /** The S3 bucket name. */
  private final String bucket;

  /** The key prefix within the bucket. */
  private final String prefix;

  /** The S3 client used for all operations. */
  private final S3Client s3Client;

  /** Whether this store owns the S3 client and should close it. */
  private final boolean ownsClient;

  /**
   * Creates a new S3SnapshotStore from the given configuration.
   *
   * <p>If the configuration does not provide an S3Client, a default
   * client is built using the configured region. The store will close
   * this client when {@link #close()} is called.
   *
   * @param config the S3 snapshot configuration, never null
   *
   * @throws NullPointerException if config is null
   */
  public S3SnapshotStore(final S3SnapshotConfig config) {
    Objects.requireNonNull(config, "config must not be null");

    this.bucket = config.bucket();
    this.prefix = config.prefix();

    if (config.s3Client().isPresent()) {
      this.s3Client = config.s3Client().get();
      this.ownsClient = false;
    } else {
      this.s3Client = S3Client.builder()
          .region(config.region())
          .build();
      this.ownsClient = true;
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>Uploads the snapshot data as a single S3 object at
   * {@code {prefix}{catalogName}/snapshot.dat}. Snapshot metadata is
   * stored as S3 user metadata headers on the object.
   *
   * <p>If a snapshot already exists for the catalog, it is overwritten.
   *
   * @throws UncheckedIOException if the S3 put operation fails
   */
  @Override
  public void save(final String catalogName,
      final SerializedSnapshot snapshot) {

    Objects.requireNonNull(catalogName, "catalogName must not be null");
    Objects.requireNonNull(snapshot, "snapshot must not be null");

    final String key = buildKey(catalogName);

    log.debug("Saving snapshot for catalog '{}' to s3://{}/{}",
        catalogName, bucket, key);

    final Map<String, String> metadata = Map.of(
        META_HASH, snapshot.hash(),
        META_VERSION, String.valueOf(snapshot.version()),
        META_CREATED_AT, snapshot.createdAt().toString(),
        META_CATALOG_NAME, snapshot.catalogName()
    );

    final PutObjectRequest request = PutObjectRequest.builder()
        .bucket(bucket)
        .key(key)
        .metadata(metadata)
        .build();

    s3Client.putObject(request, RequestBody.fromBytes(snapshot.data()));

    log.info("Saved snapshot for catalog '{}' (version={}, hash={}) "
        + "to s3://{}/{}", catalogName, snapshot.version(),
        snapshot.hash(), bucket, key);
  }

  /**
   * {@inheritDoc}
   *
   * <p>Downloads the snapshot object from
   * {@code {prefix}{catalogName}/snapshot.dat} and reconstructs the
   * {@link SerializedSnapshot} from the object data and its user
   * metadata headers.
   *
   * <p>Returns {@link Optional#empty()} if no snapshot exists for the
   * given catalog (i.e., the S3 key does not exist).
   *
   * @throws UncheckedIOException if the S3 get operation fails for
   *                              reasons other than a missing key
   */
  @Override
  public Optional<SerializedSnapshot> load(final String catalogName) {
    Objects.requireNonNull(catalogName, "catalogName must not be null");

    final String key = buildKey(catalogName);

    log.debug("Loading snapshot for catalog '{}' from s3://{}/{}",
        catalogName, bucket, key);

    final GetObjectRequest request = GetObjectRequest.builder()
        .bucket(bucket)
        .key(key)
        .build();

    try (final ResponseInputStream<GetObjectResponse> response =
             s3Client.getObject(request)) {

      final byte[] data = response.readAllBytes();
      final Map<String, String> metadata = response.response().metadata();

      final String hash = metadata.get(META_HASH);
      final long version = Long.parseLong(metadata.get(META_VERSION));
      final Instant createdAt = Instant.parse(metadata.get(META_CREATED_AT));

      final SerializedSnapshot snapshot = new SerializedSnapshot(
          catalogName, hash, version, createdAt, data);

      log.info("Loaded snapshot for catalog '{}' (version={}, hash={}) "
          + "from s3://{}/{}", catalogName, version, hash, bucket, key);

      return Optional.of(snapshot);

    } catch (final NoSuchKeyException e) {
      log.debug("No snapshot found for catalog '{}' at s3://{}/{}",
          catalogName, bucket, key);
      return Optional.empty();

    } catch (final IOException e) {
      throw new UncheckedIOException(
          "Failed to read snapshot for catalog: " + catalogName, e);
    }
  }

  /**
   * Closes the S3 client if this store created it.
   *
   * <p>If the client was provided via configuration, it is not closed
   * since the caller retains ownership.
   */
  @Override
  public void close() {
    if (ownsClient) {
      log.debug("Closing S3 client owned by this store");
      s3Client.close();
    }
  }

  /**
   * Builds the S3 object key for a given catalog name.
   *
   * @param catalogName the catalog name, never null
   * @return the full S3 object key, never null
   */
  private String buildKey(final String catalogName) {
    return prefix + catalogName + "/" + DATA_FILE;
  }
}
