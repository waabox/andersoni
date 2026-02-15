package org.waabox.andersoni.sync.db;

import java.time.Duration;
import java.util.Objects;

import javax.sql.DataSource;

/**
 * Configuration for the database polling synchronization strategy.
 *
 * <p>Holds the {@link DataSource}, the table name used for the sync log,
 * and the interval at which the table is polled for changes.
 *
 * <p>Instances are created via the static factory methods
 * {@link #create(DataSource)} and
 * {@link #create(DataSource, String, Duration)}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class DbPollingSyncConfig {

  /** Default table name for the sync log. */
  private static final String DEFAULT_TABLE_NAME = "andersoni_sync_log";

  /** Default poll interval (5 seconds). */
  private static final Duration DEFAULT_POLL_INTERVAL =
      Duration.ofSeconds(5);

  /** The JDBC data source, never null. */
  private final DataSource dataSource;

  /** The table name used for the sync log, never null. */
  private final String tableName;

  /** The polling interval, never null. */
  private final Duration pollInterval;

  /** Private constructor; use static factories.
   *
   * @param theDataSource   the JDBC data source
   * @param theTableName    the sync log table name
   * @param thePollInterval the poll interval
   */
  private DbPollingSyncConfig(final DataSource theDataSource,
      final String theTableName, final Duration thePollInterval) {
    dataSource = theDataSource;
    tableName = theTableName;
    pollInterval = thePollInterval;
  }

  /**
   * Creates a configuration with all custom values.
   *
   * @param dataSource   the JDBC data source, never null
   * @param tableName    the table name for the sync log, never null or empty
   * @param pollInterval the polling interval, never null
   *
   * @return a new configuration instance, never null
   */
  public static DbPollingSyncConfig create(final DataSource dataSource,
      final String tableName, final Duration pollInterval) {
    Objects.requireNonNull(dataSource, "dataSource cannot be null");
    Objects.requireNonNull(tableName, "tableName cannot be null");
    Objects.requireNonNull(pollInterval, "pollInterval cannot be null");

    if (tableName.isBlank()) {
      throw new IllegalArgumentException("tableName cannot be blank");
    }

    return new DbPollingSyncConfig(dataSource, tableName, pollInterval);
  }

  /**
   * Creates a configuration with default table name and poll interval.
   *
   * <p>Defaults:
   * <ul>
   *   <li>Table name: {@code andersoni_sync_log}</li>
   *   <li>Poll interval: 5 seconds</li>
   * </ul>
   *
   * @param dataSource the JDBC data source, never null
   *
   * @return a new configuration instance, never null
   */
  public static DbPollingSyncConfig create(final DataSource dataSource) {
    return create(dataSource, DEFAULT_TABLE_NAME, DEFAULT_POLL_INTERVAL);
  }

  /**
   * Returns the JDBC data source.
   *
   * @return the data source, never null
   */
  public DataSource dataSource() {
    return dataSource;
  }

  /**
   * Returns the sync log table name.
   *
   * @return the table name, never null
   */
  public String tableName() {
    return tableName;
  }

  /**
   * Returns the polling interval.
   *
   * @return the poll interval, never null
   */
  public Duration pollInterval() {
    return pollInterval;
  }
}
