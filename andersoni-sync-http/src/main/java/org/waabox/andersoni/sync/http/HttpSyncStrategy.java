package org.waabox.andersoni.sync.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.waabox.andersoni.sync.RefreshEvent;
import org.waabox.andersoni.sync.RefreshListener;
import org.waabox.andersoni.sync.SyncStrategy;

/**
 * HTTP-based implementation of {@link SyncStrategy}.
 *
 * <p>Uses Java's built-in {@code com.sun.net.httpserver.HttpServer} to receive
 * refresh events and {@code java.net.http.HttpClient} to publish them to peer
 * nodes in a fire-and-forget manner.
 *
 * <p>Typical usage:
 * <pre>{@code
 * HttpSyncConfig config = HttpSyncConfig.create(8081,
 *     List.of("http://node2:8081", "http://node3:8081"));
 * HttpSyncStrategy strategy = new HttpSyncStrategy(config);
 * strategy.subscribe(event -> reload(event.catalogName()));
 * strategy.start();
 * // ... later
 * strategy.publish(new RefreshEvent(...));
 * // ... on shutdown
 * strategy.stop();
 * }</pre>
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public class HttpSyncStrategy implements SyncStrategy {

  /** Logger for this class. */
  private static final Logger LOG =
      Logger.getLogger(HttpSyncStrategy.class.getName());

  /** HTTP 200 OK status code. */
  private static final int HTTP_OK = 200;

  /** HTTP 405 Method Not Allowed status code. */
  private static final int HTTP_METHOD_NOT_ALLOWED = 405;

  /** HTTP 500 Internal Server Error status code. */
  private static final int HTTP_INTERNAL_ERROR = 500;

  /** The delay in seconds before stopping the HTTP server. */
  private static final int SERVER_STOP_DELAY_SECONDS = 1;

  /** The configuration for this strategy. */
  private final HttpSyncConfig config;

  /** The list of registered refresh listeners. */
  private final List<RefreshListener> listeners = new CopyOnWriteArrayList<>();

  /** The HTTP server for receiving incoming events. */
  private HttpServer server;

  /** The HTTP client for publishing events to peers. */
  private HttpClient client;

  /**
   * Creates a new HTTP sync strategy with the given configuration.
   *
   * @param config the HTTP sync configuration, never null
   */
  public HttpSyncStrategy(final HttpSyncConfig config) {
    this.config = Objects.requireNonNull(config, "config cannot be null");
  }

  /** {@inheritDoc} */
  @Override
  public void publish(final RefreshEvent event) {
    Objects.requireNonNull(event, "event cannot be null");

    final String json = RefreshEventCodec.serialize(event);

    for (final String peerUrl : config.peerUrls()) {
      final String url = peerUrl + config.path();
      final HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(url))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(json))
          .build();

      client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
          .thenAccept(response -> {
            if (response.statusCode() != HTTP_OK) {
              LOG.warning("Peer " + url + " responded with status "
                  + response.statusCode());
            }
          })
          .exceptionally(ex -> {
            LOG.log(Level.WARNING,
                "Failed to publish refresh event to " + url, ex);
            return null;
          });
    }
  }

  /** {@inheritDoc} */
  @Override
  public void subscribe(final RefreshListener listener) {
    Objects.requireNonNull(listener, "listener cannot be null");
    listeners.add(listener);
  }

  /** {@inheritDoc} */
  @Override
  public void start() {
    try {
      server = HttpServer.create(
          new InetSocketAddress(config.port()), 0
      );
      server.createContext(config.path(), this::handleRefresh);
      server.start();

      client = HttpClient.newHttpClient();

      LOG.info("HttpSyncStrategy started on port " + config.port()
          + " at path " + config.path());
    } catch (final IOException e) {
      throw new IllegalStateException(
          "Failed to start HTTP server on port " + config.port(), e
      );
    }
  }

  /** {@inheritDoc} */
  @Override
  public void stop() {
    if (server != null) {
      server.stop(SERVER_STOP_DELAY_SECONDS);
      LOG.info("HTTP server stopped");
    }
    if (client != null) {
      client.close();
      LOG.info("HTTP client closed");
    }
  }

  /**
   * Handles an incoming HTTP request on the refresh endpoint.
   *
   * <p>Only POST requests are accepted. The request body must be a valid
   * JSON representation of a {@link RefreshEvent}. On success, all
   * registered listeners are notified.
   *
   * @param exchange the HTTP exchange, never null
   * @throws IOException if reading the request body or sending the
   *     response fails
   */
  private void handleRefresh(final HttpExchange exchange) throws IOException {
    if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
      sendResponse(exchange, HTTP_METHOD_NOT_ALLOWED, "Method Not Allowed");
      return;
    }

    try (InputStream is = exchange.getRequestBody()) {
      final String body = new String(
          is.readAllBytes(), StandardCharsets.UTF_8
      );
      final RefreshEvent event = RefreshEventCodec.deserialize(body);

      for (final RefreshListener listener : listeners) {
        try {
          listener.onRefresh(event);
        } catch (final Exception e) {
          LOG.log(Level.WARNING,
              "Listener threw exception for event: " + event, e);
        }
      }

      sendResponse(exchange, HTTP_OK, "OK");
    } catch (final Exception e) {
      LOG.log(Level.SEVERE, "Failed to handle refresh event", e);
      sendResponse(exchange, HTTP_INTERNAL_ERROR, "Internal Server Error");
    }
  }

  /**
   * Sends an HTTP response with the given status code and body.
   *
   * @param exchange   the HTTP exchange
   * @param statusCode the HTTP status code
   * @param body       the response body text
   * @throws IOException if writing the response fails
   */
  private void sendResponse(final HttpExchange exchange,
      final int statusCode, final String body) throws IOException {
    final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(statusCode, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }
}
