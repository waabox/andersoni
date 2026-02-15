package org.waabox.andersoni.snapshot.s3;

import java.util.Objects;
import java.util.Optional;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Immutable configuration holder for the S3-backed snapshot store.
 *
 * <p>Holds the S3 bucket name, an optional key prefix, the AWS region,
 * and an optional pre-built {@link S3Client}. When no S3Client is provided,
 * the {@link S3SnapshotStore} will create a default client using the
 * configured region.
 *
 * <p>Instances are created via the {@link Builder} returned by
 * {@link #builder()}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class S3SnapshotConfig {

  /** Default key prefix used when none is specified. */
  private static final String DEFAULT_PREFIX = "andersoni/";

  /** The S3 bucket name, never null. */
  private final String bucket;

  /** The key prefix within the bucket, never null. */
  private final String prefix;

  /** The AWS region for the S3 client, never null. */
  private final Region region;

  /** An optional pre-built S3 client, may be null. */
  private final S3Client s3Client;

  /** Creates a config from the builder.
   *
   * @param builder the builder to construct from, never null
   */
  private S3SnapshotConfig(final Builder builder) {
    this.bucket = Objects.requireNonNull(builder.bucket,
        "bucket must not be null");
    this.region = Objects.requireNonNull(builder.region,
        "region must not be null");
    this.prefix = builder.prefix != null ? builder.prefix : DEFAULT_PREFIX;
    this.s3Client = builder.s3Client;
  }

  /**
   * Returns the S3 bucket name.
   *
   * @return the bucket name, never null
   */
  public String bucket() {
    return bucket;
  }

  /**
   * Returns the key prefix used within the S3 bucket.
   *
   * <p>Defaults to {@code "andersoni/"} when not explicitly set.
   *
   * @return the key prefix, never null
   */
  public String prefix() {
    return prefix;
  }

  /**
   * Returns the AWS region.
   *
   * @return the AWS region, never null
   */
  public Region region() {
    return region;
  }

  /**
   * Returns the optional pre-built S3 client.
   *
   * <p>When empty, the {@link S3SnapshotStore} will build a default
   * client from the configured region.
   *
   * @return an optional containing the S3 client if provided, or empty
   */
  public Optional<S3Client> s3Client() {
    return Optional.ofNullable(s3Client);
  }

  /**
   * Creates a new builder for constructing an {@link S3SnapshotConfig}.
   *
   * @return a new builder instance, never null
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * A builder for constructing {@link S3SnapshotConfig} instances.
   *
   * <p>Required fields: {@code bucket} and {@code region}. Optional:
   * {@code prefix} (defaults to "andersoni/") and {@code s3Client}.
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public static final class Builder {

    /** The S3 bucket name. */
    private String bucket;

    /** The key prefix within the bucket. */
    private String prefix;

    /** The AWS region. */
    private Region region;

    /** The optional pre-built S3 client. */
    private S3Client s3Client;

    /** Private constructor to enforce usage via
     * {@link S3SnapshotConfig#builder()}.
     */
    private Builder() {
    }

    /**
     * Sets the S3 bucket name.
     *
     * @param theBucket the bucket name, never null
     * @return this builder for chaining, never null
     */
    public Builder bucket(final String theBucket) {
      this.bucket = theBucket;
      return this;
    }

    /**
     * Sets the key prefix within the S3 bucket.
     *
     * <p>If not set, defaults to {@code "andersoni/"}.
     *
     * @param thePrefix the key prefix, may be null for default
     * @return this builder for chaining, never null
     */
    public Builder prefix(final String thePrefix) {
      this.prefix = thePrefix;
      return this;
    }

    /**
     * Sets the AWS region for the S3 client.
     *
     * @param theRegion the AWS region, never null
     * @return this builder for chaining, never null
     */
    public Builder region(final Region theRegion) {
      this.region = theRegion;
      return this;
    }

    /**
     * Sets an optional pre-built S3 client.
     *
     * <p>When provided, the {@link S3SnapshotStore} will use this client
     * instead of creating its own. This is useful for testing or when
     * custom client configuration is needed.
     *
     * @param theS3Client the S3 client, may be null
     * @return this builder for chaining, never null
     */
    public Builder s3Client(final S3Client theS3Client) {
      this.s3Client = theS3Client;
      return this;
    }

    /**
     * Builds the {@link S3SnapshotConfig} instance.
     *
     * @return a new immutable config instance, never null
     *
     * @throws NullPointerException if bucket or region is null
     */
    public S3SnapshotConfig build() {
      return new S3SnapshotConfig(this);
    }
  }
}
