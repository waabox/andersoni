package org.waabox.andersoni.sync;

/**
 * A strategy for synchronizing catalog events across nodes.
 *
 * <p>Implementations of this interface define how sync events are
 * distributed (e.g. via Kafka, HTTP, database polling) and manage the
 * lifecycle of the underlying transport.
 *
 * <p>Typical lifecycle:
 * <ol>
 *   <li>Register listeners via {@link #subscribe(SyncEventListener)}</li>
 *   <li>Call {@link #start()} to begin receiving events</li>
 *   <li>Publish events via {@link #publish(SyncEvent)}</li>
 *   <li>Call {@link #stop()} to shut down the transport</li>
 * </ol>
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public interface SyncStrategy {

  /**
   * Publishes a sync event to all subscribed nodes.
   *
   * @param event the sync event to broadcast, never null
   */
  void publish(SyncEvent event);

  /**
   * Registers a listener that will be notified of incoming sync events.
   *
   * <p>Listeners must be registered before calling {@link #start()}.
   *
   * @param listener the listener to register, never null
   */
  void subscribe(SyncEventListener listener);

  /**
   * Starts the synchronization transport, enabling event reception.
   *
   * <p>After this method returns, the strategy is actively listening for
   * incoming sync events and dispatching them to registered listeners.
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
