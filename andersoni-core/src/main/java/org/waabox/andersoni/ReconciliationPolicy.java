package org.waabox.andersoni;

import java.time.Duration;
import java.util.Objects;

/**
 * Configures the snapshot reconciliation loop: whether it runs and how often.
 *
 * <p>Reconciliation is the cluster's anti-entropy mechanism. On every
 * interval each node compares the snapshot store's content hash with the
 * hash it last applied; followers reload on drift and the leader re-saves
 * and re-publishes. It only runs when a
 * {@link org.waabox.andersoni.snapshot.SnapshotStore} is configured.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class ReconciliationPolicy {

  /** The default interval between reconciliation passes. */
  private static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(30);

  /** Whether reconciliation runs at all. */
  private final boolean enabled;

  /** The base interval between passes; zero when disabled. */
  private final Duration interval;

  private ReconciliationPolicy(final boolean enabled, final Duration interval) {
    this.enabled = enabled;
    this.interval = interval;
  }

  /**
   * Creates an enabled policy with the given interval between passes.
   *
   * @param interval the base interval between passes, never null, positive
   *
   * @return the policy, never null
   *
   * @throws IllegalArgumentException if the interval is zero or negative
   */
  public static ReconciliationPolicy of(final Duration interval) {
    Objects.requireNonNull(interval, "interval must not be null");
    if (interval.isNegative() || interval.isZero()) {
      throw new IllegalArgumentException(
          "interval must be a positive duration, got: " + interval);
    }
    return new ReconciliationPolicy(true, interval);
  }

  /**
   * Creates a policy that turns reconciliation off.
   *
   * @return the disabled policy, never null
   */
  public static ReconciliationPolicy disabled() {
    return new ReconciliationPolicy(false, Duration.ZERO);
  }

  /**
   * Creates the default policy: enabled, one pass every 30 seconds.
   *
   * @return the default policy, never null
   */
  public static ReconciliationPolicy defaultPolicy() {
    return new ReconciliationPolicy(true, DEFAULT_INTERVAL);
  }

  /**
   * Returns whether reconciliation is enabled.
   *
   * @return true if passes should run
   */
  public boolean enabled() {
    return enabled;
  }

  /**
   * Returns the base interval between passes.
   *
   * @return the interval, {@link Duration#ZERO} when disabled, never null
   */
  public Duration interval() {
    return interval;
  }
}
