package org.waabox.andersoni.sync;

/**
 * A strategy for synchronizing catalog refresh events across nodes.
 *
 * <p>Implementations of this interface define how refresh events are
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
 *   <li>Register listeners via {@link #subscribe(RefreshListener)}</li>
 *   <li>Call {@link #start()} to begin receiving events</li>
 *   <li>Publish events via {@link #publish(RefreshEvent)}</li>
 *   <li>Call {@link #stop()} to shut down the transport</li>
 * </ol>
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
