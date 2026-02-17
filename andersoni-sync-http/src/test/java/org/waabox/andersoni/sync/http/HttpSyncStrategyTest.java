package org.waabox.andersoni.sync.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import org.waabox.andersoni.sync.RefreshEvent;
import org.waabox.andersoni.sync.RefreshEventCodec;

/**
 * Integration tests for {@link HttpSyncStrategy}.
 *
 * <p>These tests start real HTTP servers on localhost using ephemeral-like
 * ports to verify end-to-end synchronization behavior.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class HttpSyncStrategyTest {

  @Test
  void whenPublishing_givenPeerUrl_shouldSendHttpPost() throws Exception {
    // Arrange: two nodes, node1 publishes to node2
    final int port1 = 19081;
    final int port2 = 19082;

    final HttpSyncConfig config1 = HttpSyncConfig.create(
        port1, List.of("http://localhost:" + port2)
    );
    final HttpSyncConfig config2 = HttpSyncConfig.create(
        port2, List.of("http://localhost:" + port1)
    );

    final HttpSyncStrategy node1 = new HttpSyncStrategy(config1);
    final HttpSyncStrategy node2 = new HttpSyncStrategy(config2);

    final CountDownLatch latch = new CountDownLatch(1);
    final AtomicReference<RefreshEvent> received = new AtomicReference<>();

    node2.subscribe(event -> {
      received.set(event);
      latch.countDown();
    });

    node1.start();
    node2.start();

    try {
      final Instant now = Instant.now();
      final RefreshEvent event = new RefreshEvent(
          "products", "node-1", 1L, "hash1", now
      );

      // Act
      node1.publish(event);

      // Assert
      final boolean completed = latch.await(5, TimeUnit.SECONDS);
      assertTrue(completed, "Listener on node2 should have been notified");

      final RefreshEvent receivedEvent = received.get();
      assertNotNull(receivedEvent);
      assertEquals("products", receivedEvent.catalogName());
      assertEquals("node-1", receivedEvent.sourceNodeId());
      assertEquals(1L, receivedEvent.version());
      assertEquals("hash1", receivedEvent.hash());
      assertEquals(now, receivedEvent.timestamp());
    } finally {
      node1.stop();
      node2.stop();
    }
  }

  @Test
  void whenReceivingPost_givenValidJson_shouldNotifyListeners()
      throws Exception {
    // Arrange
    final int port = 19083;
    final HttpSyncConfig config = HttpSyncConfig.create(
        port, List.of()
    );

    final HttpSyncStrategy strategy = new HttpSyncStrategy(config);

    final CountDownLatch latch = new CountDownLatch(1);
    final AtomicReference<RefreshEvent> received = new AtomicReference<>();

    strategy.subscribe(event -> {
      received.set(event);
      latch.countDown();
    });

    strategy.start();

    try {
      final Instant now = Instant.parse("2026-01-20T15:00:00Z");
      final RefreshEvent event = new RefreshEvent(
          "orders", "node-x", 5L, "hashX", now
      );
      final String json = RefreshEventCodec.serialize(event);

      // Act: POST directly to the server
      final HttpClient client = HttpClient.newHttpClient();
      final HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create("http://localhost:" + port
              + "/andersoni/refresh"))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(json))
          .build();

      final HttpResponse<String> response = client.send(
          request, HttpResponse.BodyHandlers.ofString()
      );

      // Assert
      assertEquals(200, response.statusCode());

      final boolean completed = latch.await(5, TimeUnit.SECONDS);
      assertTrue(completed,
          "Listener should have been notified via direct POST");

      final RefreshEvent receivedEvent = received.get();
      assertNotNull(receivedEvent);
      assertEquals("orders", receivedEvent.catalogName());
      assertEquals("node-x", receivedEvent.sourceNodeId());
      assertEquals(5L, receivedEvent.version());
      assertEquals("hashX", receivedEvent.hash());
      assertEquals(now, receivedEvent.timestamp());
    } finally {
      strategy.stop();
    }
  }
}
