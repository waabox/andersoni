package org.waabox.andersoni;

import java.time.Duration;
import java.util.Objects;

/**
 * Defines the retry behavior for catalog operations such as data loading
 * and snapshot retrieval.
 *
 * <p>Instances are created through static factory methods. The default
 * policy uses 3 retries with a 2-second backoff between attempts.
 *
 * <p>This class is immutable and thread-safe.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class RetryPolicy {

  /** The default number of retries. */
  private static final int DEFAULT_MAX_RETRIES = 3;

  /** The default backoff duration. */
  private static final Duration DEFAULT_BACKOFF = Duration.ofSeconds(2);

  /** The maximum number of retry attempts. */
  private final int maxRetries;

  /** The duration to wait between retry attempts. */
  private final Duration backoff;

  /**
   * Creates a new retry policy.
   *
   * @param maxRetries the maximum number of retries, must be greater
   *                   than zero
   * @param backoff    the duration to wait between retries, never null
   */
  private RetryPolicy(final int maxRetries, final Duration backoff) {
    this.maxRetries = maxRetries;
    this.backoff = backoff;
  }

  /**
   * Creates a retry policy with the given parameters.
   *
   * @param maxRetries the maximum number of retry attempts, must be
   *                   greater than zero
   * @param backoff    the duration to wait between retry attempts,
   *                   never null
   * @return a new retry policy, never null
   *
   * @throws IllegalArgumentException if maxRetries is less than or equal
   *                                  to zero
   * @throws NullPointerException if backoff is null
   */
  public static RetryPolicy of(final int maxRetries, final Duration backoff) {
    if (maxRetries <= 0) {
      throw new IllegalArgumentException(
          "maxRetries must be greater than 0, got: " + maxRetries);
    }
    Objects.requireNonNull(backoff, "backoff must not be null");
    return new RetryPolicy(maxRetries, backoff);
  }

  /**
   * Creates a retry policy with sensible defaults: 3 retries with a
   * 2-second backoff.
   *
   * @return the default retry policy, never null
   */
  public static RetryPolicy defaultPolicy() {
    return new RetryPolicy(DEFAULT_MAX_RETRIES, DEFAULT_BACKOFF);
  }

  /**
   * Returns the maximum number of retry attempts.
   *
   * @return the maximum number of retries, always greater than zero
   */
  public int maxRetries() {
    return maxRetries;
  }

  /**
   * Returns the duration to wait between retry attempts.
   *
   * @return the backoff duration, never null
   */
  public Duration backoff() {
    return backoff;
  }
}
