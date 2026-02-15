package org.waabox.andersoni.sync.http;

import java.util.List;

/**
 * Configuration holder for the HTTP-based synchronization strategy.
 *
 * <p>Holds the port to listen on, the list of peer node URLs, and the
 * HTTP path where refresh events are exchanged.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class HttpSyncConfig {

  /** The default HTTP path for refresh events. */
  private static final String DEFAULT_PATH = "/andersoni/refresh";

  /** The port to listen on for incoming refresh events. */
  private final int port;

  /** The URLs of peer nodes to publish refresh events to. */
  private final List<String> peerUrls;

  /** The HTTP path for the refresh endpoint. */
  private final String path;

  /** Private constructor; use the static factory methods instead. */
  private HttpSyncConfig(final int port, final List<String> peerUrls,
      final String path) {
    this.port = port;
    this.peerUrls = List.copyOf(peerUrls);
    this.path = path;
  }

  /**
   * Creates a new configuration with the given port and peer URLs, using
   * the default path ({@value #DEFAULT_PATH}).
   *
   * @param port     the port to listen on, must be positive
   * @param peerUrls the list of peer node URLs, never null
   * @return a new {@link HttpSyncConfig} instance, never null
   */
  public static HttpSyncConfig create(final int port,
      final List<String> peerUrls) {
    return new HttpSyncConfig(port, peerUrls, DEFAULT_PATH);
  }

  /**
   * Creates a new configuration with the given port, peer URLs, and path.
   *
   * @param port     the port to listen on, must be positive
   * @param peerUrls the list of peer node URLs, never null
   * @param path     the HTTP path for the refresh endpoint, never null
   * @return a new {@link HttpSyncConfig} instance, never null
   */
  public static HttpSyncConfig create(final int port,
      final List<String> peerUrls, final String path) {
    return new HttpSyncConfig(port, peerUrls, path);
  }

  /**
   * Returns the port to listen on.
   *
   * @return the listening port
   */
  public int port() {
    return port;
  }

  /**
   * Returns an unmodifiable list of peer node URLs.
   *
   * @return the peer URLs, never null
   */
  public List<String> peerUrls() {
    return peerUrls;
  }

  /**
   * Returns the HTTP path for the refresh endpoint.
   *
   * @return the path, never null
   */
  public String path() {
    return path;
  }
}
