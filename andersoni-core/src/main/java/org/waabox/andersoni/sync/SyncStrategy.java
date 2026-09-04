package org.waabox.andersoni.sync;

/**
 * A strategy for synchronizing catalog refresh and patch events across
 * nodes.
 *
 * <p>Implementations of this interface define how messages are
 * distributed (e.g. via Kafka, HTTP, database polling) and manage the
 * lifecycle of the underlying transport.
 *
 * <p><strong>Contract for implementers:</strong>
 * <ul>
 *   <li><em>Threading:</em> {@link #publish(RefreshEvent)} may be called
 *       concurrently and must be thread-safe. Received events are dispatched
 *       to listeners on the transport's own thread(s); Andersoni offloads the
 *       actual refresh to its own executor, so listeners return quickly.</li>
 *   <li><em>Delivery:</em> delivery is best-effort and at-most-once is not
 *       required — Andersoni tolerates duplicates (it ignores self-originated
 *       events and events whose hash already matches the local snapshot), so
 *       at-least-once or redelivery is safe. Missed events are self-correcting
 *       on the next refresh.</li>
 *   <li><em>Subscription:</em> Andersoni registers exactly one listener
 *       before {@link #start()}; implementations need not support multiple
 *       listeners.</li>
 * </ul>
 *
 * <p>Typical lifecycle:
 * <ol>
 *   <li>Register listeners via {@link #subscribe(RefreshListener)} and
 *       optionally {@link #subscribePatch(PatchListener)}</li>
 *   <li>Call {@link #start()} to begin receiving events</li>
 *   <li>Publish events via {@link #publish(RefreshEvent)} or
 *       {@link #publishPatch(PatchEvent)}</li>
 *   <li>Call {@link #stop()} to shut down the transport</li>
 * </ol>
 *
 * <p>Patch support is opt-in via {@link #supportsPatches()}. Existing
 * implementations that don't override the patch methods will continue to
 * compile and work for refresh-only flows; if a caller publishes a patch
 * through a non-patch-aware strategy, the default {@link #publishPatch}
 * throws {@link UnsupportedOperationException}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public interface SyncStrategy {

  /**
   * Publishes a refresh event to all subscribed nodes.
   *
   * @param event the refresh event to broadcast, never null
   */
  void publish(RefreshEvent event);

  /**
   * Registers a listener that will be notified of incoming refresh events.
   *
   * <p>Listeners must be registered before calling {@link #start()}.
   *
   * @param listener the listener to register, never null
   */
  void subscribe(RefreshListener listener);

  /**
   * Publishes a patch event to all subscribed nodes.
   *
   * <p>The default implementation throws
   * {@link UnsupportedOperationException}. Implementations that support
   * patch propagation must override this method and
   * {@link #supportsPatches()}.
   *
   * @param event the patch event to broadcast, never null
   * @throws UnsupportedOperationException if this strategy does not
   *         support patch events
   */
  default void publishPatch(final PatchEvent event) {
    throw new UnsupportedOperationException(
        "This SyncStrategy does not support patch events");
  }

  /**
   * Registers a listener that will be notified of incoming patch events.
   *
   * <p>The default implementation is a no-op so existing strategies stay
   * source-compatible. Patch-aware strategies should override this to
   * dispatch incoming patch messages to the listener.
   *
   * @param listener the listener to register, never null
   */
  default void subscribePatch(final PatchListener listener) {
    // No-op: refresh-only strategies do not deliver patch events.
  }

  /**
   * Returns whether this strategy supports patch event propagation.
   *
   * <p>The default implementation returns {@code false}. When this returns
   * {@code true}, callers may invoke {@link #publishPatch(PatchEvent)}.
   *
   * @return true if this strategy can publish and deliver patch events,
   *         false otherwise
   */
  default boolean supportsPatches() {
    return false;
  }

  /**
   * Starts the synchronization transport, enabling event reception.
   *
   * <p>After this method returns, the strategy is actively listening for
   * incoming refresh events and dispatching them to registered listeners.
   */
  void start();

  /**
   * Stops the synchronization transport and releases associated resources.
   *
   * <p>After this method returns, no further events will be received or
   * dispatched.
   */
  void stop();
}
