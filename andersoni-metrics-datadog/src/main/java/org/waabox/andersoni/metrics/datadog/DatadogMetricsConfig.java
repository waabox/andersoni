package org.waabox.andersoni.metrics.datadog;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration for the Datadog DogStatsD metrics reporter.
 *
 * <p>Use the {@link #builder()} to create instances. All fields are
 * immutable and validated at build time.
 *
 * <p>When {@code host} is null, the DogStatsD client resolves the agent
 * address from the {@code DD_DOGSTATSD_URL} or {@code DD_AGENT_HOST}
 * environment variables, falling back to {@code localhost}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class DatadogMetricsConfig {

  /** Default DogStatsD port. */
  public static final int DEFAULT_PORT = 8125;

  /** Default metric name prefix. */
  public static final String DEFAULT_PREFIX = "andersoni";

  /** Default gauge polling interval (30 seconds). */
  public static final Duration DEFAULT_POLLING_INTERVAL =
      Duration.ofSeconds(30);

  /** The DogStatsD agent host, null for autodiscovery. */
  private final String host;

  /** The DogStatsD agent port. */
  private final int port;

  /** The metric name prefix. */
  private final String prefix;

  /** Constant tags added to every metric. */
  private final String[] constantTags;

  /** The gauge polling interval. */
  private final Duration pollingInterval;

  private DatadogMetricsConfig(final Builder builder) {
    this.host = builder.host;
    this.port = builder.port;
    this.prefix = Objects.requireNonNull(builder.prefix,
        "prefix must not be null");
    this.constantTags = builder.constantTags.clone();
    this.pollingInterval = Objects.requireNonNull(builder.pollingInterval,
        "pollingInterval must not be null");

    if (port < 0 || port > 65535) {
      throw new IllegalArgumentException(
          "port must be between 0 and 65535, got: " + port);
    }
    if (pollingInterval.isZero() || pollingInterval.isNegative()) {
      throw new IllegalArgumentException(
          "pollingInterval must be positive, got: " + pollingInterval);
    }
  }

  /**
   * Returns the DogStatsD agent host, or null for autodiscovery.
   *
   * @return the host, or null
   */
  public String host() {
    return host;
  }

  /**
   * Returns the DogStatsD agent port.
   *
   * @return the port
   */
  public int port() {
    return port;
  }

  /**
   * Returns the metric name prefix.
   *
   * @return the prefix, never null
   */
  public String prefix() {
    return prefix;
  }

  /**
   * Returns a copy of the constant tags array.
   *
   * @return the constant tags, never null
   */
  public String[] constantTags() {
    return constantTags.clone();
  }

  /**
   * Returns the gauge polling interval.
   *
   * @return the polling interval, never null
   */
  public Duration pollingInterval() {
    return pollingInterval;
  }

  /**
   * Creates a new builder with default values.
   *
   * @return a new builder, never null
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Builder for {@link DatadogMetricsConfig}.
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public static final class Builder {

    /** The host, null by default (autodiscovery). */
    private String host = null;

    /** The port, default 8125. */
    private int port = DEFAULT_PORT;

    /** The prefix, default "andersoni". */
    private String prefix = DEFAULT_PREFIX;

    /** The constant tags, empty by default. */
    private String[] constantTags = new String[0];

    /** The polling interval, default 30s. */
    private Duration pollingInterval = DEFAULT_POLLING_INTERVAL;

    private Builder() {
    }

    /**
     * Sets the DogStatsD agent host.
     *
     * @param theHost the host, or null for autodiscovery
     * @return this builder
     */
    public Builder host(final String theHost) {
      this.host = theHost;
      return this;
    }

    /**
     * Sets the DogStatsD agent port.
     *
     * @param thePort the port number
     * @return this builder
     */
    public Builder port(final int thePort) {
      this.port = thePort;
      return this;
    }

    /**
     * Sets the metric name prefix.
     *
     * @param thePrefix the prefix, never null
     * @return this builder
     */
    public Builder prefix(final String thePrefix) {
      Objects.requireNonNull(thePrefix, "prefix must not be null");
      this.prefix = thePrefix;
      return this;
    }

    /**
     * Sets the constant tags added to every metric.
     *
     * @param theTags the tags (e.g. "service:my-app", "env:prod")
     * @return this builder
     */
    public Builder constantTags(final String... theTags) {
      Objects.requireNonNull(theTags, "constantTags must not be null");
      this.constantTags = theTags.clone();
      return this;
    }

    /**
     * Sets the gauge polling interval.
     *
     * @param thePollingInterval the interval, never null, must be positive
     * @return this builder
     */
    public Builder pollingInterval(final Duration thePollingInterval) {
      Objects.requireNonNull(thePollingInterval,
          "pollingInterval must not be null");
      this.pollingInterval = thePollingInterval;
      return this;
    }

    /**
     * Builds an immutable {@link DatadogMetricsConfig} instance.
     *
     * @return the config, never null
     *
     * @throws NullPointerException     if prefix or pollingInterval is null
     * @throws IllegalArgumentException if port is negative or
     *                                  pollingInterval is zero/negative
     */
    public DatadogMetricsConfig build() {
      return new DatadogMetricsConfig(this);
    }
  }
}
