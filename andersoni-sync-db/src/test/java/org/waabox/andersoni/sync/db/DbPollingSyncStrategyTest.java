package org.waabox.andersoni.sync.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.waabox.andersoni.sync.RefreshEvent;

/** Unit tests for {@link DbPollingSyncStrategy}.
 *
 * <p>Uses an H2 in-memory database for fast, isolated testing with a short
 * poll interval (100ms) for quick feedback.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class DbPollingSyncStrategyTest {

  /** The H2 in-memory data source used across all tests. */
  private DataSource dataSource;

  /** The strategy under test. */
  private DbPollingSyncStrategy strategy;

  @BeforeEach
  void setUp() {
    final JdbcDataSource ds = new JdbcDataSource();
    ds.setURL("jdbc:h2:mem:testdb_" + System.nanoTime()
        + ";DB_CLOSE_DELAY=-1");
    ds.setUser("sa");
    ds.setPassword("");
    dataSource = ds;
  }

  @AfterEach
  void tearDown() {
    if (strategy != null) {
      strategy.stop();
    }
  }

  @Test
  void whenStarting_shouldCreateTableIfNotExists() throws Exception {
    final DbPollingSyncConfig config = DbPollingSyncConfig.create(
        dataSource, "andersoni_sync_log", Duration.ofMillis(100));

    strategy = new DbPollingSyncStrategy(config);
    strategy.start();

    // Verify table exists by querying metadata.
    try (final Connection conn = dataSource.getConnection()) {
      final ResultSet rs = conn.getMetaData().getTables(
          null, null, "ANDERSONI_SYNC_LOG", null);
      assertTrue(rs.next(), "Table andersoni_sync_log should exist");
    }
  }

  @Test
  void whenPublishing_givenRefreshEvent_shouldInsertIntoTable()
      throws Exception {

    final DbPollingSyncConfig config = DbPollingSyncConfig.create(
        dataSource, "andersoni_sync_log", Duration.ofMillis(100));

    strategy = new DbPollingSyncStrategy(config);
    strategy.start();

    final Instant timestamp = Instant.parse("2024-01-15T10:30:00Z");
    final RefreshEvent event = new RefreshEvent(
        "products", "node-1", 1L, "hash-abc", timestamp);

    strategy.publish(event);

    // Query the table directly to verify the row exists.
    try (final Connection conn = dataSource.getConnection();
         final PreparedStatement ps = conn.prepareStatement(
             "SELECT catalog_name, source_node_id, version, hash, "
                 + "updated_at FROM andersoni_sync_log "
                 + "WHERE catalog_name = ?")) {

      ps.setString(1, "products");

      try (final ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next(), "Row should exist for catalog 'products'");
        assertEquals("products", rs.getString("catalog_name"));
        assertEquals("node-1", rs.getString("source_node_id"));
        assertEquals(1L, rs.getLong("version"));
        assertEquals("hash-abc", rs.getString("hash"));
        assertNotNull(rs.getTimestamp("updated_at"));
      }
    }
  }

  @Test
  void whenPublishing_givenExistingCatalog_shouldUpdateRow()
      throws Exception {

    final DbPollingSyncConfig config = DbPollingSyncConfig.create(
        dataSource, "andersoni_sync_log", Duration.ofMillis(100));

    strategy = new DbPollingSyncStrategy(config);
    strategy.start();

    final Instant timestamp1 = Instant.parse("2024-01-15T10:30:00Z");
    final RefreshEvent event1 = new RefreshEvent(
        "products", "node-1", 1L, "hash-v1", timestamp1);
    strategy.publish(event1);

    final Instant timestamp2 = Instant.parse("2024-01-15T11:00:00Z");
    final RefreshEvent event2 = new RefreshEvent(
        "products", "node-1", 2L, "hash-v2", timestamp2);
    strategy.publish(event2);

    // Verify only one row exists, with updated values.
    try (final Connection conn = dataSource.getConnection();
         final PreparedStatement ps = conn.prepareStatement(
             "SELECT version, hash FROM andersoni_sync_log "
                 + "WHERE catalog_name = ?")) {

      ps.setString(1, "products");

      try (final ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next(), "Row should exist for catalog 'products'");
        assertEquals(2L, rs.getLong("version"));
        assertEquals("hash-v2", rs.getString("hash"));
      }
    }

    // Verify exactly one row in the table for this catalog.
    try (final Connection conn = dataSource.getConnection();
         final PreparedStatement count = conn.prepareStatement(
             "SELECT COUNT(*) FROM andersoni_sync_log "
                 + "WHERE catalog_name = ?")) {

      count.setString(1, "products");

      try (final ResultSet rs = count.executeQuery()) {
        rs.next();
        assertEquals(1, rs.getInt(1),
            "Should have exactly one row for 'products'");
      }
    }
  }

  @Test
  void whenPolling_givenChangedHash_shouldNotifyListeners()
      throws Exception {

    final DbPollingSyncConfig config = DbPollingSyncConfig.create(
        dataSource, "andersoni_sync_log", Duration.ofMillis(100));

    strategy = new DbPollingSyncStrategy(config);

    final CountDownLatch latch = new CountDownLatch(1);
    final List<RefreshEvent> received = new ArrayList<>();

    strategy.subscribe(event -> {
      received.add(event);
      latch.countDown();
    });

    strategy.start();

    // Simulate another node writing directly to the table.
    try (final Connection conn = dataSource.getConnection();
         final PreparedStatement ps = conn.prepareStatement(
             "MERGE INTO andersoni_sync_log "
                 + "(catalog_name, source_node_id, version, hash, updated_at)"
                 + " VALUES (?, ?, ?, ?, ?)")) {

      ps.setString(1, "orders");
      ps.setString(2, "remote-node");
      ps.setLong(3, 5L);
      ps.setString(4, "remote-hash");
      ps.setTimestamp(5, java.sql.Timestamp.from(Instant.now()));
      ps.executeUpdate();
    }

    // Wait for the poll to pick up the change.
    final boolean notified = latch.await(2, TimeUnit.SECONDS);

    assertTrue(notified, "Listener should have been notified");
    assertEquals(1, received.size());
    assertEquals("orders", received.get(0).catalogName());
    assertEquals("remote-node", received.get(0).sourceNodeId());
    assertEquals(5L, received.get(0).version());
    assertEquals("remote-hash", received.get(0).hash());
  }

  @Test
  void whenPolling_givenSameHash_shouldNotNotifyAgain() throws Exception {

    final DbPollingSyncConfig config = DbPollingSyncConfig.create(
        dataSource, "andersoni_sync_log", Duration.ofMillis(100));

    strategy = new DbPollingSyncStrategy(config);

    final List<RefreshEvent> received = new ArrayList<>();
    strategy.subscribe(received::add);

    strategy.start();

    // Insert a row simulating a remote change.
    try (final Connection conn = dataSource.getConnection();
         final PreparedStatement ps = conn.prepareStatement(
             "MERGE INTO andersoni_sync_log "
                 + "(catalog_name, source_node_id, version, hash, updated_at)"
                 + " VALUES (?, ?, ?, ?, ?)")) {

      ps.setString(1, "users");
      ps.setString(2, "remote-node");
      ps.setLong(3, 1L);
      ps.setString(4, "stable-hash");
      ps.setTimestamp(5, java.sql.Timestamp.from(Instant.now()));
      ps.executeUpdate();
    }

    // Wait enough time for multiple poll cycles.
    Thread.sleep(500);

    assertEquals(1, received.size(),
        "Listener should be notified exactly once for the same hash");
  }
}
