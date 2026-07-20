package org.waabox.andersoni.sync.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DbPollingSyncConfig}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class DbPollingSyncConfigTest {

  /** A data source used to build configurations. */
  private DataSource dataSource;

  @BeforeEach
  void setUp() {
    final JdbcDataSource ds = new JdbcDataSource();
    ds.setURL("jdbc:h2:mem:configdb_" + System.nanoTime()
        + ";DB_CLOSE_DELAY=-1");
    ds.setUser("sa");
    ds.setPassword("");
    dataSource = ds;
  }

  @Test
  void whenCreating_givenTableNameWithSqlInjection_shouldThrow() {
    assertThrows(IllegalArgumentException.class,
        () -> DbPollingSyncConfig.create(dataSource,
            "sync_log; DROP TABLE users", Duration.ofSeconds(1)),
        "The table name is interpolated into SQL and must reject anything "
            + "that is not a plain identifier");
  }

  @Test
  void whenCreating_givenTableNameWithSpace_shouldThrow() {
    assertThrows(IllegalArgumentException.class,
        () -> DbPollingSyncConfig.create(dataSource, "sync log",
            Duration.ofSeconds(1)));
  }

  @Test
  void whenCreating_givenTableNameStartingWithDigit_shouldThrow() {
    assertThrows(IllegalArgumentException.class,
        () -> DbPollingSyncConfig.create(dataSource, "1sync_log",
            Duration.ofSeconds(1)));
  }

  @Test
  void whenCreating_givenPlainTableName_shouldAccept() {
    final DbPollingSyncConfig config = DbPollingSyncConfig.create(
        dataSource, "andersoni_sync_log", Duration.ofSeconds(1));

    assertEquals("andersoni_sync_log", config.tableName());
  }

  @Test
  void whenCreating_givenSchemaQualifiedTableName_shouldAccept() {
    final DbPollingSyncConfig config = DbPollingSyncConfig.create(
        dataSource, "andersoni.sync_log", Duration.ofSeconds(1));

    assertEquals("andersoni.sync_log", config.tableName());
  }
}
