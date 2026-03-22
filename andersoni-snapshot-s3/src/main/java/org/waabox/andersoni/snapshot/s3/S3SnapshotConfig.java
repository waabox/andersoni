package org.waabox.andersoni.snapshot.s3;

import java.nio.file.Path;
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
 * <p>Optionally supports AWS STS credential acquisition via
 * {@code AssumeRole} or {@code AssumeRoleWithWebIdentity}. When a
 * {@code roleArn} is provided, the store builds an S3 client whose
 * credentials are obtained through STS. If a
 * {@code webIdentityTokenFile} is also provided, the
 * {@code AssumeRoleWithWebIdentity} flow is used instead of the plain
 * {@code AssumeRole} flow.
 *
 * <p>Instances are created via the {@link Builder} returned by
 * {@link #builder()}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class S3SnapshotConfig {

  /** Default key prefix used when none is specified. */
  private static final String DEFAULT_PREFIX = "andersoni/";

  /** Default session name for STS assume-role calls. */
  private static final String DEFAULT_SESSION_NAME = "andersoni-snapshot";

  /** The S3 bucket name, never null. */
  private final String bucket;

  /** The key prefix within the bucket, never null. */
  private final String prefix;

  /** The AWS region for the S3 client, never null. */
  private final Region region;

  /** An optional pre-built S3 client, may be null. */
  private final S3Client s3Client;

  /** The IAM role ARN to assume via STS, may be null. */
  private final String roleArn;

  /** The session name for STS assume-role calls, never null. */
  private final String sessionName;

  /** Path to the web identity token file for OIDC-based assume role,
   * may be null. */
  private final Path webIdentityTokenFile;

  /** An optional external ID for cross-account assume role,
   * may be null. */
  private final String externalId;

  /** Optional session duration in seconds, may be null. */
  private final Integer durationSeconds;

  /** Creates a config from the builder.
   *
   * @param builder the builder to construct from, never null
   */
  private S3SnapshotConfig(final Builder builder) {
    this.bucket = Objects.requireNonNull(builder.bucket, "bucket must not be null");
    this.region = Objects.requireNonNull(builder.region, "region must not be null");
    this.prefix = builder.prefix != null ? builder.prefix : DEFAULT_PREFIX;
    this.s3Client = builder.s3Client;
    this.roleArn = builder.roleArn;
    this.sessionName = builder.sessionName != null ? builder.sessionName : DEFAULT_SESSION_NAME;
    this.webIdentityTokenFile = builder.webIdentityTokenFile;
    this.externalId = builder.externalId;
    this.durationSeconds = builder.durationSeconds;
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
   * Returns the IAM role ARN to assume via STS.
   *
   * <p>When present, the {@link S3SnapshotStore} will use STS to obtain
   * temporary credentials before accessing S3.
   *
   * @return an optional containing the role ARN, or empty
   */
  public Optional<String> roleArn() {
    return Optional.ofNullable(roleArn);
  }

  /**
   * Returns the session name for STS assume-role calls.
   *
   * <p>Defaults to {@code "andersoni-snapshot"} when not explicitly set.
   *
   * @return the session name, never null
   */
  public String sessionName() {
    return sessionName;
  }

  /**
   * Returns the path to the web identity token file.
   *
   * <p>When present along with a {@code roleArn}, the
   * {@code AssumeRoleWithWebIdentity} flow is used instead of the
   * plain {@code AssumeRole} flow.
   *
   * @return an optional containing the token file path, or empty
   */
  public Optional<Path> webIdentityTokenFile() {
    return Optional.ofNullable(webIdentityTokenFile);
  }

  /**
   * Returns the external ID for cross-account assume-role.
   *
   * @return an optional containing the external ID, or empty
   */
  public Optional<String> externalId() {
    return Optional.ofNullable(externalId);
  }

  /**
   * Returns the session duration in seconds.
   *
   * @return an optional containing the duration, or empty
   */
  public Optional<Integer> durationSeconds() {
    return Optional.ofNullable(durationSeconds);
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
   * {@code prefix} (defaults to "andersoni/"), {@code s3Client},
   * {@code roleArn}, {@code sessionName}, {@code webIdentityTokenFile},
   * {@code externalId}, and {@code durationSeconds}.
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

    /** The IAM role ARN to assume via STS. */
    private String roleArn;

    /** The session name for STS calls. */
    private String sessionName;

    /** Path to the web identity token file. */
    private Path webIdentityTokenFile;

    /** External ID for cross-account assume role. */
    private String externalId;

    /** Session duration in seconds. */
    private Integer durationSeconds;

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
     * custom client configuration is needed. STS configuration fields
     * are ignored when a custom S3 client is provided.
     *
     * @param theS3Client the S3 client, may be null
     * @return this builder for chaining, never null
     */
    public Builder s3Client(final S3Client theS3Client) {
      this.s3Client = theS3Client;
      return this;
    }

    /**
     * Sets the IAM role ARN to assume via STS.
     *
     * <p>When set, the store will use STS to obtain temporary
     * credentials for S3 access. If {@link #webIdentityTokenFile}
     * is also set, the {@code AssumeRoleWithWebIdentity} flow is
     * used; otherwise, the plain {@code AssumeRole} flow is used.
     *
     * @param theRoleArn the IAM role ARN, never null
     * @return this builder for chaining, never null
     */
    public Builder roleArn(final String theRoleArn) {
      this.roleArn = theRoleArn;
      return this;
    }

    /**
     * Sets the session name for STS assume-role calls.
     *
     * <p>If not set, defaults to {@code "andersoni-snapshot"}.
     *
     * @param theSessionName the session name, never null
     * @return this builder for chaining, never null
     */
    public Builder sessionName(final String theSessionName) {
      this.sessionName = theSessionName;
      return this;
    }

    /**
     * Sets the path to the web identity token file.
     *
     * <p>When set along with a {@code roleArn}, enables the
     * {@code AssumeRoleWithWebIdentity} flow (used with EKS/IRSA).
     *
     * @param theTokenFile the path to the token file, never null
     * @return this builder for chaining, never null
     */
    public Builder webIdentityTokenFile(final Path theTokenFile) {
      this.webIdentityTokenFile = theTokenFile;
      return this;
    }

    /**
     * Sets the external ID for cross-account assume-role.
     *
     * <p>Only used with the plain {@code AssumeRole} flow.
     *
     * @param theExternalId the external ID, never null
     * @return this builder for chaining, never null
     */
    public Builder externalId(final String theExternalId) {
      this.externalId = theExternalId;
      return this;
    }

    /**
     * Sets the session duration in seconds for the assumed role.
     *
     * <p>When not set, the AWS default duration is used.
     *
     * @param theDurationSeconds the duration in seconds
     * @return this builder for chaining, never null
     */
    public Builder durationSeconds(final int theDurationSeconds) {
      this.durationSeconds = theDurationSeconds;
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
