package org.waabox.andersoni;

import java.time.Duration;
import java.util.Objects;

/**
 * Defines the time-window coalescing behavior for cross-node refresh
 * events.
 *
 * <p>A policy bundles the two parameters that govern the
 * {@code RefreshDebouncer}:
 * <ul>
 *   <li>{@code window} — the minimum delay between the first event in a
 *       burst and the coalesced refresh firing. Subsequent events for the
 *       same catalog within this window push the timer out.</li>
 *   <li>{@code maxWait} — the maximum delay between the first event in a
 *       burst and firing, so sustained traffic cannot starve the refresh.
 *       Must be greater than or equal to {@code window} (or zero when
 *       {@code window} is zero).</li>
 * </ul>
 *
 * <p>Use {@link #passThrough()} to disable debouncing entirely (refresh
 * events are dispatched immediately on the calling thread, preserving
 * pre-debouncer behavior).
 *
 * <p>Instances are created through static factory methods. The class is
 * immutable and thread-safe.
 */
public final class DebouncePolicy {

  /** Shared pass-through (no debouncing) instance. */
  private static final DebouncePolicy PASS_THROUGH =
      new DebouncePolicy(Duration.ZERO, Duration.ZERO);

  /** The minimum delay before firing. */
  private final Duration window;

  /** The maximum delay before firing. */
  private final Duration maxWait;

  /**
   * Creates a new debounce policy.
   *
   * @param theWindow  the minimum delay before firing, never null
   * @param theMaxWait the maximum delay before firing, never null
   */
  private DebouncePolicy(final Duration theWindow,
      final Duration theMaxWait) {
    this.window = theWindow;
    this.maxWait = theMaxWait;
  }

  /**
   * Creates a fixed-window-throttle policy: at most one refresh per
   * {@code window}, regardless of how many events arrive within it. This
   * is shorthand for {@code of(window, window)}.
   *
   * @param theWindow the debounce window, never null and not negative
   *
   * @return a new policy, never null
   *
   * @throws NullPointerException     if theWindow is null
   * @throws IllegalArgumentException if theWindow is negative
   */
  public static DebouncePolicy of(final Duration theWindow) {
    Objects.requireNonNull(theWindow, "window must not be null");
    return of(theWindow, theWindow);
  }

  /**
   * Creates a policy with explicit window and max-wait.
   *
   * @param theWindow  the minimum delay before firing, never null and not
   *                   negative
   * @param theMaxWait the maximum delay before firing, never null, not
   *                   negative, and {@code >= theWindow} when {@code
   *                   theWindow} is non-zero
   *
   * @return a new policy, never null
   *
   * @throws NullPointerException     if any argument is null
   * @throws IllegalArgumentException if any duration is negative, or if
   *                                  {@code theMaxWait < theWindow} when
   *                                  {@code theWindow} is non-zero
   */
  public static DebouncePolicy of(final Duration theWindow,
      final Duration theMaxWait) {
    Objects.requireNonNull(theWindow, "window must not be null");
    Objects.requireNonNull(theMaxWait, "maxWait must not be null");
    if (theWindow.isNegative()) {
      throw new IllegalArgumentException(
          "window must not be negative, got: " + theWindow);
    }
    if (theMaxWait.isNegative()) {
      throw new IllegalArgumentException(
          "maxWait must not be negative, got: " + theMaxWait);
    }
    if (!theWindow.isZero() && theMaxWait.compareTo(theWindow) < 0) {
      throw new IllegalArgumentException(
          "maxWait must be >= window (window=" + theWindow
              + ", maxWait=" + theMaxWait + ")");
    }
    return new DebouncePolicy(theWindow, theMaxWait);
  }

  /**
   * Returns the pass-through policy that disables debouncing entirely.
   * Refresh events are dispatched immediately on the calling thread.
   *
   * @return the pass-through policy, never null
   */
  public static DebouncePolicy passThrough() {
    return PASS_THROUGH;
  }

  /**
   * Returns the minimum delay between the first event in a burst and the
   * coalesced refresh firing.
   *
   * @return the window duration, never null
   */
  public Duration window() {
    return window;
  }

  /**
   * Returns the maximum delay between the first event in a burst and the
   * coalesced refresh firing.
   *
   * @return the max-wait duration, never null
   */
  public Duration maxWait() {
    return maxWait;
  }

  /**
   * Returns {@code true} when this policy disables debouncing (zero
   * window). Refresh events submitted under a pass-through policy run
   * synchronously on the calling thread.
   *
   * @return whether the window is zero
   */
  public boolean isPassThrough() {
    return window.isZero();
  }

  /** {@inheritDoc} */
  @Override
  public boolean equals(final Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof DebouncePolicy)) {
      return false;
    }
    final DebouncePolicy that = (DebouncePolicy) other;
    return window.equals(that.window) && maxWait.equals(that.maxWait);
  }

  /** {@inheritDoc} */
  @Override
  public int hashCode() {
    return Objects.hash(window, maxWait);
  }

  /** {@inheritDoc} */
  @Override
  public String toString() {
    return "DebouncePolicy[window=" + window + ", maxWait=" + maxWait + "]";
  }
}
