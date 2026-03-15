package org.waabox.andersoni.metrics.datadog;

import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.waabox.andersoni.Catalog;
import org.waabox.andersoni.CatalogInfo;
import org.waabox.andersoni.IndexInfo;
import org.waabox.andersoni.metrics.AndersoniMetrics;

import com.timgroup.statsd.NonBlockingStatsDClientBuilder;
import com.timgroup.statsd.StatsDClient;

/**
 * {@link AndersoniMetrics} implementation that reports metrics to Datadog
 * via the DogStatsD protocol.
 *
 * <p>Reports counters on events (snapshot loads, refresh failures) and
 * gauges on a configurable polling interval (item counts, memory usage,
 * index sizes).
 *
 * <p>Three factory methods are available:
 * <ul>
 *   <li>{@link #create()} -- zero-config K8s autodiscovery</li>
 *   <li>{@link #create(DatadogMetricsConfig)} -- custom configuration</li>
 *   <li>{@link #create(StatsDClient)} -- user-provided client</li>
 * </ul>
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class DatadogAndersoniMetrics implements AndersoniMetrics {

  /** The class logger. */
  private static final Logger log = LoggerFactory.getLogger(
      DatadogAndersoniMetrics.class);

  /** Graceful shutdown timeout in seconds. */
  private static final int SHUTDOWN_TIMEOUT_SECONDS = 5;

  /** The DogStatsD client. */
  private final StatsDClient client;

  /** Whether this instance owns the client (and should close it). */
  private final boolean ownsClient;

  /** The gauge polling interval. */
  private final long pollingIntervalMs;

  /** The registered catalogs, set on start. */
  private volatile Collection<Catalog<?>> catalogs;

  /** The node identifier, set on start. */
  private volatile String nodeId;

  /** The scheduler for gauge polling. */
  private volatile ScheduledExecutorService scheduler;

  private DatadogAndersoniMetrics(final StatsDClient theClient,
      final boolean clientOwned, final long thePollingIntervalMs) {
    this.client = Objects.requireNonNull(theClient,
        "client must not be null");
    this.ownsClient = clientOwned;
    this.pollingIntervalMs = thePollingIntervalMs;
  }

  /**
   * Creates a metrics reporter with K8s autodiscovery (zero config).
   *
   * <p>The DogStatsD client resolves the agent address from
   * {@code DD_DOGSTATSD_URL} or {@code DD_AGENT_HOST} environment
   * variables, falling back to {@code localhost:8125}.
   *
   * @return a new metrics reporter, never null
   */
  public static DatadogAndersoniMetrics create() {
    return create(DatadogMetricsConfig.builder().build());
  }

  /**
   * Creates a metrics reporter with custom configuration.
   *
   * @param config the configuration, never null
   * @return a new metrics reporter, never null
   */
  public static DatadogAndersoniMetrics create(
      final DatadogMetricsConfig config) {
    Objects.requireNonNull(config, "config must not be null");

    final NonBlockingStatsDClientBuilder builder =
        new NonBlockingStatsDClientBuilder()
            .port(config.port())
            .prefix(config.prefix());

    if (config.host() != null) {
      builder.hostname(config.host());
    }

    final String[] tags = config.constantTags();
    if (tags.length > 0) {
      builder.constantTags(tags);
    }

    final StatsDClient statsDClient = builder.build();
    final long intervalMs = config.pollingInterval().toMillis();

    return new DatadogAndersoniMetrics(statsDClient, true, intervalMs);
  }

  /**
   * Creates a metrics reporter using a user-provided StatsDClient.
   *
   * <p>The module will <b>not</b> close the client on {@link #stop()}.
   * The caller retains ownership.
   *
   * @param statsDClient the DogStatsD client, never null
   * @return a new metrics reporter, never null
   */
  public static DatadogAndersoniMetrics create(
      final StatsDClient statsDClient) {
    Objects.requireNonNull(statsDClient,
        "statsDClient must not be null");
    return new DatadogAndersoniMetrics(statsDClient, false,
        DatadogMetricsConfig.DEFAULT_POLLING_INTERVAL.toMillis());
  }

  /** {@inheritDoc} */
  @Override
  public void snapshotLoaded(final String catalogName,
      final String source) {
    final String node = this.nodeId;
    if (node != null) {
      client.count("catalog.snapshot.loaded", 1,
          "catalog:" + catalogName, "source:" + source,
          "node:" + node);
    } else {
      client.count("catalog.snapshot.loaded", 1,
          "catalog:" + catalogName, "source:" + source);
    }
  }

  /** {@inheritDoc} */
  @Override
  public void refreshFailed(final String catalogName,
      final Throwable cause) {
    final String node = this.nodeId;
    if (node != null) {
      client.count("catalog.refresh.failed", 1,
          "catalog:" + catalogName, "node:" + node);
    } else {
      client.count("catalog.refresh.failed", 1,
          "catalog:" + catalogName);
    }
  }

  /** {@inheritDoc} */
  @Override
  public void indexSizeReported(final String catalogName,
      final String indexName, final long estimatedSizeBytes) {
    final String node = this.nodeId;
    if (node != null) {
      client.gauge("index.memory.bytes", estimatedSizeBytes,
          "catalog:" + catalogName, "index:" + indexName,
          "node:" + node);
    } else {
      client.gauge("index.memory.bytes", estimatedSizeBytes,
          "catalog:" + catalogName, "index:" + indexName);
    }
  }

  /** {@inheritDoc} */
  @Override
  public void start(final Collection<Catalog<?>> theCatalogs,
      final String theNodeId) {
    Objects.requireNonNull(theCatalogs, "catalogs must not be null");
    Objects.requireNonNull(theNodeId, "nodeId must not be null");

    this.catalogs = theCatalogs;
    this.nodeId = theNodeId;

    this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      final Thread t = new Thread(r,
          "andersoni-metrics-datadog-" + theNodeId);
      t.setDaemon(true);
      return t;
    });

    scheduler.scheduleAtFixedRate(
        this::reportGauges,
        pollingIntervalMs,
        pollingIntervalMs,
        TimeUnit.MILLISECONDS);

    log.info("Datadog metrics started for node '{}', polling every {}ms",
        theNodeId, pollingIntervalMs);
  }

  /** {@inheritDoc} */
  @Override
  public void stop() {
    final ScheduledExecutorService sched = this.scheduler;
    if (sched != null) {
      sched.shutdown();
      try {
        if (!sched.awaitTermination(
            SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
          sched.shutdownNow();
        }
      } catch (final InterruptedException e) {
        sched.shutdownNow();
        Thread.currentThread().interrupt();
      }
      this.scheduler = null;
    }

    if (ownsClient) {
      client.close();
    }

    log.info("Datadog metrics stopped");
  }

  /**
   * Reports gauge metrics for all registered catalogs.
   *
   * <p>Called periodically by the internal scheduler. Exceptions per
   * catalog are caught and logged so one failing catalog does not
   * prevent others from reporting.
   */
  private void reportGauges() {
    final String node = this.nodeId;
    final Collection<Catalog<?>> cats = this.catalogs;
    if (cats == null || node == null) {
      return;
    }

    for (final Catalog<?> catalog : cats) {
      try {
        reportCatalogGauges(catalog, node);
      } catch (final Exception e) {
        log.warn("Failed to report gauges for catalog '{}'",
            catalog.name(), e);
      }
    }
  }

  /**
   * Reports gauge metrics for a single catalog.
   *
   * @param catalog the catalog to report, never null
   * @param node    the node tag value, never null
   */
  private void reportCatalogGauges(final Catalog<?> catalog,
      final String node) {
    final String catalogTag = "catalog:" + catalog.name();
    final String nodeTag = "node:" + node;

    final CatalogInfo info = catalog.info();

    client.gauge("catalog.items", info.itemCount(),
        catalogTag, nodeTag);
    client.gauge("catalog.memory.bytes", info.totalEstimatedSizeBytes(),
        catalogTag, nodeTag);
    client.gauge("catalog.version",
        catalog.currentSnapshot().version(),
        catalogTag, nodeTag);

    for (final IndexInfo indexInfo : info.indices()) {
      final String indexTag = "index:" + indexInfo.name();
      client.gauge("index.memory.bytes", indexInfo.estimatedSizeBytes(),
          catalogTag, indexTag, nodeTag);
      client.gauge("index.keys", indexInfo.uniqueKeys(),
          catalogTag, indexTag, nodeTag);
    }
  }
}
