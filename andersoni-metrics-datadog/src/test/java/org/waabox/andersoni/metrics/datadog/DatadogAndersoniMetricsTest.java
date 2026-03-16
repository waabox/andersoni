package org.waabox.andersoni.metrics.datadog;

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.waabox.andersoni.Catalog;

import com.timgroup.statsd.StatsDClient;

/** Unit tests for {@link DatadogAndersoniMetrics}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class DatadogAndersoniMetricsTest {

  @Test
  void whenCreatingWithDefaults_shouldNotThrow() {
    final DatadogMetricsConfig config = DatadogMetricsConfig.builder()
        .host("localhost")
        .build();

    final DatadogAndersoniMetrics metrics =
        DatadogAndersoniMetrics.create(config);
    assertNotNull(metrics);
    metrics.stop();
  }

  @Test
  void whenCreatingWithConfig_shouldNotThrow() {
    final DatadogMetricsConfig config = DatadogMetricsConfig.builder()
        .host("localhost")
        .port(8125)
        .build();

    final DatadogAndersoniMetrics metrics =
        DatadogAndersoniMetrics.create(config);
    assertNotNull(metrics);
    metrics.stop();
  }

  @Test
  void whenCreatingWithClient_shouldNotThrow() {
    final StatsDClient client = createMock(StatsDClient.class);
    replay(client);

    assertDoesNotThrow(() -> {
      final DatadogAndersoniMetrics metrics =
          DatadogAndersoniMetrics.create(client);
      assertNotNull(metrics);
    });

    verify(client);
  }

  @Test
  void whenSnapshotLoaded_givenBeforeStart_shouldIncrementCounterWithoutNodeTag() {
    final StatsDClient client = createMock(StatsDClient.class);

    client.count(eq("catalog.snapshot.loaded"), eq(1L),
        eq("catalog:products"), eq("source:dataLoader"));
    expectLastCall().once();

    replay(client);

    final DatadogAndersoniMetrics metrics =
        DatadogAndersoniMetrics.create(client);
    metrics.snapshotLoaded("products", "dataLoader");

    verify(client);
  }

  @Test
  void whenSnapshotLoaded_givenAfterStart_shouldIncrementCounterWithNodeTag() {
    final StatsDClient client = createMock(StatsDClient.class);

    client.count(eq("catalog.snapshot.loaded"), eq(1L),
        eq("catalog:products"), eq("source:s3"),
        eq("node:node-1"));
    expectLastCall().once();

    replay(client);

    final DatadogAndersoniMetrics metrics =
        DatadogAndersoniMetrics.create(client);
    metrics.start(List.of(), "node-1");
    metrics.snapshotLoaded("products", "s3");
    metrics.stop();

    verify(client);
  }

  @Test
  void whenRefreshFailed_givenBeforeStart_shouldIncrementCounterWithoutNodeTag() {
    final StatsDClient client = createMock(StatsDClient.class);

    client.count(eq("catalog.refresh.failed"), eq(1L),
        eq("catalog:products"));
    expectLastCall().once();

    replay(client);

    final DatadogAndersoniMetrics metrics =
        DatadogAndersoniMetrics.create(client);
    metrics.refreshFailed("products", new RuntimeException("fail"));

    verify(client);
  }

  @Test
  void whenRefreshFailed_givenAfterStart_shouldIncrementCounterWithNodeTag() {
    final StatsDClient client = createMock(StatsDClient.class);

    client.count(eq("catalog.refresh.failed"), eq(1L),
        eq("catalog:products"), eq("node:node-1"));
    expectLastCall().once();

    replay(client);

    final DatadogAndersoniMetrics metrics =
        DatadogAndersoniMetrics.create(client);
    metrics.start(List.of(), "node-1");
    metrics.refreshFailed("products", new RuntimeException("fail"));
    metrics.stop();

    verify(client);
  }

  @Test
  void whenIndexSizeReported_shouldRecordGauge() {
    final StatsDClient client = createMock(StatsDClient.class);

    client.gauge(eq("index.memory.bytes"), eq(1024L),
        eq("catalog:products"), eq("index:by-name"));
    expectLastCall().once();

    replay(client);

    final DatadogAndersoniMetrics metrics =
        DatadogAndersoniMetrics.create(client);
    metrics.indexSizeReported("products", "by-name", 1024L);

    verify(client);
  }

  @Test
  void whenStoppingWithUserProvidedClient_shouldNotCloseClient() {
    final StatsDClient client = createMock(StatsDClient.class);
    // No close() expectation — if close is called, verify will fail
    replay(client);

    final DatadogAndersoniMetrics metrics =
        DatadogAndersoniMetrics.create(client);
    metrics.start(List.of(), "node-1");
    metrics.stop();

    verify(client);
  }

  @Test
  void whenSyncPublished_givenBeforeStart_shouldIncrementCounterWithoutNodeTag() {
    final StatsDClient client = createMock(StatsDClient.class);

    client.count(eq("sync.published"), eq(1L),
        eq("catalog:products"));
    expectLastCall().once();

    replay(client);

    final DatadogAndersoniMetrics metrics =
        DatadogAndersoniMetrics.create(client);
    metrics.syncPublished("products");

    verify(client);
  }

  @Test
  void whenSyncPublished_givenAfterStart_shouldIncrementCounterWithNodeTag() {
    final StatsDClient client = createMock(StatsDClient.class);

    client.count(eq("sync.published"), eq(1L),
        eq("catalog:products"), eq("node:node-1"));
    expectLastCall().once();

    replay(client);

    final DatadogAndersoniMetrics metrics =
        DatadogAndersoniMetrics.create(client);
    metrics.start(List.of(), "node-1");
    metrics.syncPublished("products");
    metrics.stop();

    verify(client);
  }

  @Test
  void whenSyncReceived_givenBeforeStart_shouldIncrementCounterWithoutNodeTag() {
    final StatsDClient client = createMock(StatsDClient.class);

    client.count(eq("sync.received"), eq(1L),
        eq("catalog:products"));
    expectLastCall().once();

    replay(client);

    final DatadogAndersoniMetrics metrics =
        DatadogAndersoniMetrics.create(client);
    metrics.syncReceived("products");

    verify(client);
  }

  @Test
  void whenSyncReceived_givenAfterStart_shouldIncrementCounterWithNodeTag() {
    final StatsDClient client = createMock(StatsDClient.class);

    client.count(eq("sync.received"), eq(1L),
        eq("catalog:products"), eq("node:node-1"));
    expectLastCall().once();

    replay(client);

    final DatadogAndersoniMetrics metrics =
        DatadogAndersoniMetrics.create(client);
    metrics.start(List.of(), "node-1");
    metrics.syncReceived("products");
    metrics.stop();

    verify(client);
  }

  @Test
  void whenSyncPublishFailed_givenBeforeStart_shouldIncrementCounterWithoutNodeTag() {
    final StatsDClient client = createMock(StatsDClient.class);

    client.count(eq("sync.publish.failed"), eq(1L),
        eq("catalog:products"));
    expectLastCall().once();

    replay(client);

    final DatadogAndersoniMetrics metrics =
        DatadogAndersoniMetrics.create(client);
    metrics.syncPublishFailed("products", new RuntimeException("fail"));

    verify(client);
  }

  @Test
  void whenSyncPublishFailed_givenAfterStart_shouldIncrementCounterWithNodeTag() {
    final StatsDClient client = createMock(StatsDClient.class);

    client.count(eq("sync.publish.failed"), eq(1L),
        eq("catalog:products"), eq("node:node-1"));
    expectLastCall().once();

    replay(client);

    final DatadogAndersoniMetrics metrics =
        DatadogAndersoniMetrics.create(client);
    metrics.start(List.of(), "node-1");
    metrics.syncPublishFailed("products", new RuntimeException("fail"));
    metrics.stop();

    verify(client);
  }

  @Test
  void whenSyncReceiveFailed_givenBeforeStart_shouldIncrementCounterWithoutNodeTag() {
    final StatsDClient client = createMock(StatsDClient.class);

    client.count(eq("sync.receive.failed"), eq(1L));
    expectLastCall().once();

    replay(client);

    final DatadogAndersoniMetrics metrics =
        DatadogAndersoniMetrics.create(client);
    metrics.syncReceiveFailed(new RuntimeException("fail"));

    verify(client);
  }

  @Test
  void whenSyncReceiveFailed_givenAfterStart_shouldIncrementCounterWithNodeTag() {
    final StatsDClient client = createMock(StatsDClient.class);

    client.count(eq("sync.receive.failed"), eq(1L),
        eq("node:node-1"));
    expectLastCall().once();

    replay(client);

    final DatadogAndersoniMetrics metrics =
        DatadogAndersoniMetrics.create(client);
    metrics.start(List.of(), "node-1");
    metrics.syncReceiveFailed(new RuntimeException("fail"));
    metrics.stop();

    verify(client);
  }

  @Test
  void whenReportingGauges_givenCatalogWithIndex_shouldReportAllGauges() {
    final StatsDClient client = createMock(StatsDClient.class);

    // Build a real catalog with static data and one index
    final Catalog<String> catalog = Catalog.of(String.class)
        .named("cities")
        .data(List.of("Buenos Aires", "Madrid", "Tokyo"))
        .index("by-length").by(s -> s, String::length)
        .build();
    catalog.bootstrap();

    // Expect catalog-level gauges
    client.gauge(eq("catalog.items"), eq(3L),
        eq("catalog:cities"), eq("node:node-1"));
    expectLastCall().once();

    client.gauge(eq("catalog.memory.bytes"),
        org.easymock.EasyMock.anyLong(),
        eq("catalog:cities"), eq("node:node-1"));
    expectLastCall().once();

    client.gauge(eq("catalog.version"), eq(1L),
        eq("catalog:cities"), eq("node:node-1"));
    expectLastCall().once();

    // Expect index-level gauges
    client.gauge(eq("index.memory.bytes"),
        org.easymock.EasyMock.anyLong(),
        eq("catalog:cities"), eq("index:by-length"),
        eq("node:node-1"));
    expectLastCall().once();

    client.gauge(eq("index.keys"),
        org.easymock.EasyMock.anyLong(),
        eq("catalog:cities"), eq("index:by-length"),
        eq("node:node-1"));
    expectLastCall().once();

    replay(client);

    final DatadogAndersoniMetrics metrics =
        DatadogAndersoniMetrics.create(client);
    metrics.start(List.of(catalog), "node-1");

    // Invoke reportGauges directly (package-private) for synchronous testing
    metrics.reportGauges();

    metrics.stop();

    verify(client);
  }
}
