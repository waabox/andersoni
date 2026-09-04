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
import org.waabox.andersoni.sync.RefreshEvent;
import org.waabox.andersoni.sync.RefreshListener;
import org.waabox.andersoni.sync.SyncStrategy;

/**
 * A {@link SyncStrategy} implementation that uses JDBC database polling to
 * synchronize catalog refresh events across nodes.
 *
 * <p>This strategy stores refresh events in a database table and polls it
 * periodically for changes. When a change in hash is detected for any catalog,
 * registered listeners are notified.
 *
 * <p>The table is created automatically on {@link #start()} if it does not
 * already exist. The UPSERT operation uses a portable UPDATE-then-INSERT
 * approach for broad database compatibility.
 *
 * <p>Thread safety: this class is thread-safe. The listener list uses
 * {@link CopyOnWriteArrayList} and the hash tracking uses
 * {@link ConcurrentHashMap}.
 *
 * <p><strong>Patch propagation is not supported by this strategy.</strong>
 * The table is keyed by {@code catalog_name} with one row per catalog
 * tracking the latest hash — there is no per-event log, so individual
 * patches cannot be delivered. Followers on this transport learn about
 * leader-side patches only on the next periodic refresh that picks up
 * the updated hash. Cross-node surgical patch propagation requires a
 * patch-aware transport such as Kafka or HTTP.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class DbPollingSyncStrategy implements SyncStrategy {

  /** Class logger. */
  private static final Logger log =
      LoggerFactory.getLogger(DbPollingSyncStrategy.class);

  /** The configuration for this strategy, never null. */
  private final DbPollingSyncConfig config;

  /** Registered refresh listeners. */
  private final List<RefreshListener> listeners =
      new CopyOnWriteArrayList<>();

  /** Last known hash per catalog name, used to detect changes. */
  private final ConcurrentHashMap<String, String> lastKnownHashes =
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
  public void publish(final RefreshEvent event) {
    Objects.requireNonNull(event, "event cannot be null");

    if (event.isRequest()) {
      // The DB polling channel tracks the latest hash per catalog to detect
      // changes; it is a state channel, not a command channel. A refresh
      // request carries no hash and has no leader back-channel here, so
      // writing it would corrupt change detection. Requests are ignored:
      // with DB polling, a follower's refreshAndSync is a safe no-op.
      log.debug("DB polling sync does not support refresh requests; "
          + "ignoring request for catalog '{}'", event.catalogName());
      return;
    }

    final String updateSql = "UPDATE " + config.tableName()
        + " SET source_node_id = ?, version = ?, hash = ?, updated_at = ?"
        + " WHERE catalog_name = ?";

    final String insertSql = "INSERT INTO " + config.tableName()
        + " (catalog_name, source_node_id, version, hash, updated_at)"
        + " VALUES (?, ?, ?, ?, ?)";

    try (final Connection conn = config.dataSource().getConnection()) {

      if (updateRow(conn, updateSql, event) == 0) {
        try (final PreparedStatement ps =
            conn.prepareStatement(insertSql)) {
          ps.setString(1, event.catalogName());
          ps.setString(2, event.sourceNodeId());
          ps.setLong(3, event.version());
          ps.setString(4, event.hash());
          ps.setTimestamp(5, Timestamp.from(event.timestamp()));
          ps.executeUpdate();
        } catch (final SQLException insertError) {
          // Another node inserted the row for this catalog between our UPDATE
          // (0 rows) and this INSERT, causing a primary-key clash. Recover by
          // retrying the UPDATE instead of failing the publish.
          if (updateRow(conn, updateSql, event) == 0) {
            throw insertError;
          }
        }
      }

      log.debug("Published refresh event for catalog '{}', version {}",
          event.catalogName(), event.version());

    } catch (final SQLException e) {
      throw new AndersoniException(
          "Failed to publish refresh event for catalog '"
              + event.catalogName() + "'", e);
    }
  }

  /** Runs the update statement for a refresh event.
   *
   * @param conn      the database connection, never null.
   * @param updateSql the UPDATE statement, never null.
   * @param event     the event to persist, never null.
   * @return the number of rows affected.
   * @throws SQLException if the update fails.
   */
  private static int updateRow(final Connection conn, final String updateSql,
      final RefreshEvent event) throws SQLException {
    try (final PreparedStatement ps = conn.prepareStatement(updateSql)) {
      ps.setString(1, event.sourceNodeId());
      ps.setLong(2, event.version());
      ps.setString(3, event.hash());
      ps.setTimestamp(4, Timestamp.from(event.timestamp()));
      ps.setString(5, event.catalogName());
      return ps.executeUpdate();
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
        + "hash VARCHAR(255) NOT NULL, "
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
   * <p>Reads all rows and compares each catalog's hash against the last
   * known value. If a new or changed hash is detected, listeners are
   * notified with a {@link RefreshEvent}.
   */
  private void poll() {
    final String sql = "SELECT catalog_name, source_node_id, version, hash,"
        + " updated_at FROM " + config.tableName();

    try (final Connection conn = config.dataSource().getConnection();
         final PreparedStatement ps = conn.prepareStatement(sql);
         final ResultSet rs = ps.executeQuery()) {

      while (rs.next()) {
        final String catalogName = rs.getString("catalog_name");
        final String sourceNodeId = rs.getString("source_node_id");
        final long version = rs.getLong("version");
        final String hash = rs.getString("hash");
        final Timestamp updatedTs = rs.getTimestamp("updated_at");
        final Instant updatedAt =
            updatedTs != null ? updatedTs.toInstant() : Instant.now();

        final String previousHash = lastKnownHashes.put(catalogName, hash);

        if (!hash.equals(previousHash)) {
          final RefreshEvent event = new RefreshEvent(
              catalogName, sourceNodeId, version, hash, updatedAt);

          notifyListeners(event);
        }
      }

    } catch (final Exception e) {
      // Catch broadly on purpose: this runs under scheduleAtFixedRate, where
      // any thrown exception (e.g. a null column producing an NPE, not just a
      // SQLException) permanently suppresses all future executions. Swallow
      // and log so a single bad row or transient error never stops polling.
      log.error("Error polling sync log table '{}'", config.tableName(), e);
    }
  }

  /**
   * Notifies all registered listeners of a refresh event.
   *
   * @param event the refresh event, never null
   */
  private void notifyListeners(final RefreshEvent event) {
    for (final RefreshListener listener : listeners) {
      try {
        listener.onRefresh(event);
      } catch (final Exception e) {
        log.error("Listener threw exception for catalog '{}'",
            event.catalogName(), e);
      }
    }
  }
}
