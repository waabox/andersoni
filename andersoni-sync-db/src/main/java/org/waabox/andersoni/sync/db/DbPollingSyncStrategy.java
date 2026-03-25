package org.waabox.andersoni.sync.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.waabox.andersoni.AndersoniException;
import org.waabox.andersoni.sync.PatchEvent;
import org.waabox.andersoni.sync.RefreshEvent;
import org.waabox.andersoni.sync.SyncEvent;
import org.waabox.andersoni.sync.SyncEventCodec;
import org.waabox.andersoni.sync.SyncEventHandler;
import org.waabox.andersoni.sync.SyncListener;
import org.waabox.andersoni.sync.SyncStrategy;

/**
 * A {@link SyncStrategy} implementation that uses JDBC database polling to
 * synchronize catalog sync events across nodes.
 *
 * <p>This strategy stores sync events in a database table and polls it
 * periodically for changes. When a change in version is detected for any
 * catalog, registered listeners are notified.
 *
 * <p>The table is created automatically on {@link #start()} if it does not
 * already exist. The UPSERT operation uses a portable UPDATE-then-INSERT
 * approach for broad database compatibility.
 *
 * <p>Thread safety: this class is thread-safe. The listener list uses
 * {@link CopyOnWriteArrayList} and the version tracking uses
 * {@link ConcurrentHashMap}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class DbPollingSyncStrategy implements SyncStrategy {

  /** Class logger. */
  private static final Logger log =
      LoggerFactory.getLogger(DbPollingSyncStrategy.class);

  /** The configuration for this strategy, never null. */
  private final DbPollingSyncConfig config;

  /** Registered sync listeners. */
  private final List<SyncListener> listeners =
      new CopyOnWriteArrayList<>();

  /** Last known version per catalog name, used to detect changes. */
  private final ConcurrentHashMap<String, Long> lastKnownVersions =
      new ConcurrentHashMap<>();

  /** The scheduler that runs the polling task. */
  private volatile ScheduledExecutorService scheduler;

  /**
   * Creates a new database polling sync strategy.
   *
   * @param theConfig the configuration, never null
   */
  public DbPollingSyncStrategy(final DbPollingSyncConfig theConfig) {
    Objects.requireNonNull(theConfig, "config cannot be null");
    config = theConfig;
  }

  /** {@inheritDoc} */
  @Override
  public void publish(final SyncEvent event) {
    Objects.requireNonNull(event, "event cannot be null");

    final String[] eventType = new String[1];
    event.accept(new SyncEventHandler() {
      @Override
      public void onRefresh(final RefreshEvent r) {
        eventType[0] = "REFRESH";
      }

      @Override
      public void onPatch(final PatchEvent p) {
        eventType[0] = "PATCH";
      }
    });
    final String eventJson = SyncEventCodec.serialize(event);

    final String updateSql = "UPDATE " + config.tableName()
        + " SET source_node_id = ?, version = ?, event_type = ?,"
        + " event_json = ?, updated_at = ?"
        + " WHERE catalog_name = ?";

    final String insertSql = "INSERT INTO " + config.tableName()
        + " (catalog_name, source_node_id, version, event_type,"
        + " event_json, updated_at)"
        + " VALUES (?, ?, ?, ?, ?, ?)";

    try (final Connection conn = config.dataSource().getConnection()) {

      final int affectedRows;
      try (final PreparedStatement ps = conn.prepareStatement(updateSql)) {
        ps.setString(1, event.sourceNodeId());
        ps.setLong(2, event.version());
        ps.setString(3, eventType[0]);
        ps.setString(4, eventJson);
        ps.setTimestamp(5, Timestamp.from(Instant.now()));
        ps.setString(6, event.catalogName());
        affectedRows = ps.executeUpdate();
      }

      if (affectedRows == 0) {
        try (final PreparedStatement ps =
            conn.prepareStatement(insertSql)) {
          ps.setString(1, event.catalogName());
          ps.setString(2, event.sourceNodeId());
          ps.setLong(3, event.version());
          ps.setString(4, eventType[0]);
          ps.setString(5, eventJson);
          ps.setTimestamp(6, Timestamp.from(Instant.now()));
          ps.executeUpdate();
        }
      }

      log.debug("Published sync event for catalog '{}', version {}",
          event.catalogName(), event.version());

    } catch (final SQLException e) {
      throw new AndersoniException(
          "Failed to publish sync event for catalog '"
              + event.catalogName() + "'", e);
    }
  }

  /** {@inheritDoc} */
  @Override
  public void subscribe(final SyncListener listener) {
    Objects.requireNonNull(listener, "listener cannot be null");
    listeners.add(listener);
  }

  /** {@inheritDoc} */
  @Override
  public void start() {
    createTableIfNotExists();

    scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      final Thread thread = new Thread(r, "andersoni-db-poll");
      thread.setDaemon(true);
      return thread;
    });

    final long intervalMillis = config.pollInterval().toMillis();

    scheduler.scheduleAtFixedRate(this::poll,
        intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);

    log.info("DbPollingSyncStrategy started, polling every {} ms",
        intervalMillis);
  }

  /** {@inheritDoc} */
  @Override
  public void stop() {
    if (scheduler != null) {
      scheduler.shutdown();
      try {
        if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
          scheduler.shutdownNow();
        }
      } catch (final InterruptedException e) {
        scheduler.shutdownNow();
        Thread.currentThread().interrupt();
      }
      log.info("DbPollingSyncStrategy stopped");
    }
  }

  /**
   * Creates the sync log table if it does not already exist.
   *
   * <p>Uses {@code CREATE TABLE IF NOT EXISTS} for idempotent DDL.
   */
  private void createTableIfNotExists() {
    final String ddl = "CREATE TABLE IF NOT EXISTS " + config.tableName()
        + " ("
        + "catalog_name VARCHAR(255) NOT NULL, "
        + "source_node_id VARCHAR(255) NOT NULL, "
        + "version BIGINT NOT NULL, "
        + "event_type VARCHAR(20) NOT NULL, "
        + "event_json TEXT NOT NULL, "
        + "updated_at TIMESTAMP NOT NULL, "
        + "PRIMARY KEY (catalog_name)"
        + ")";

    try (final Connection conn = config.dataSource().getConnection();
         final PreparedStatement ps = conn.prepareStatement(ddl)) {

      ps.execute();
      log.debug("Ensured sync log table '{}' exists", config.tableName());

    } catch (final SQLException e) {
      throw new AndersoniException(
          "Failed to create sync log table '" + config.tableName() + "'",
          e);
    }
  }

  /**
   * Polls the sync log table for changes.
   *
   * <p>Reads all rows and compares each catalog's version against the last
   * known value. If a new or changed version is detected, listeners are
   * notified with the deserialized {@link SyncEvent}.
   */
  private void poll() {
    final String sql = "SELECT catalog_name, event_json, version"
        + " FROM " + config.tableName();

    try (final Connection conn = config.dataSource().getConnection();
         final PreparedStatement ps = conn.prepareStatement(sql);
         final ResultSet rs = ps.executeQuery()) {

      while (rs.next()) {
        final String catalogName = rs.getString("catalog_name");
        final String eventJson = rs.getString("event_json");
        final long version = rs.getLong("version");

        final Long previousVersion =
            lastKnownVersions.put(catalogName, version);

        if (previousVersion == null || version != previousVersion) {
          final SyncEvent event = SyncEventCodec.deserialize(eventJson);
          notifyListeners(event);
        }
      }

    } catch (final SQLException e) {
      log.error("Error polling sync log table '{}'", config.tableName(), e);
    }
  }

  /**
   * Notifies all registered listeners of a sync event.
   *
   * @param event the sync event, never null
   */
  private void notifyListeners(final SyncEvent event) {
    for (final SyncListener listener : listeners) {
      try {
        listener.onEvent(event);
      } catch (final Exception e) {
        log.error("Listener threw exception for catalog '{}'",
            event.catalogName(), e);
      }
    }
  }
}
