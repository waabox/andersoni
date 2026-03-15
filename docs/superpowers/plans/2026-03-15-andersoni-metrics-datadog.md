# andersoni-metrics-datadog Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create the `andersoni-metrics-datadog` module that implements `AndersoniMetrics` using DogStatsD to report catalog and index metrics to Datadog.

**Architecture:** Add lifecycle methods (`start`/`stop`) to the core `AndersoniMetrics` interface with default empty implementations, then build a new module that implements those methods plus the existing callbacks using `java-dogstatsd-client`. The module runs its own daemon scheduler for periodic gauge reporting.

**Tech Stack:** Java 21, java-dogstatsd-client 4.4.5, JUnit 5, EasyMock

**Spec:** `docs/superpowers/specs/2026-03-15-andersoni-metrics-datadog-design.md`

---

## Chunk 1: Core interface changes and module scaffolding

### Task 1: Add lifecycle methods to AndersoniMetrics

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/metrics/AndersoniMetrics.java`

- [ ] **Step 1: Add start and stop default methods to AndersoniMetrics**

Add imports and two default methods at the end of the interface, before the closing brace:

```java
import java.util.Collection;
import org.waabox.andersoni.Catalog;
```

Add after `indexSizeReported`:

```java
  /**
   * Called when the Andersoni engine has fully started.
   *
   * <p>Implementations can use this to begin periodic metric reporting.
   * The provided catalogs are the same instances managed by the engine
   * and their snapshots can be read lock-free at any time.
   *
   * @param catalogs the registered catalogs, never null
   * @param nodeId   the node identifier, never null
   */
  default void start(final Collection<Catalog<?>> catalogs,
      final String nodeId) {
  }

  /**
   * Called when the Andersoni engine is stopping.
   *
   * <p>Implementations should release any resources (schedulers, clients)
   * allocated during {@link #start}.
   */
  default void stop() {
  }
```

- [ ] **Step 2: Run core tests to verify nothing breaks**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-core -q`
Expected: BUILD SUCCESS (NoopAndersoniMetrics inherits defaults, no changes needed)

- [ ] **Step 3: Commit**

```
Add lifecycle methods (start/stop) to AndersoniMetrics interface
```

---

### Task 2: Wire lifecycle calls in Andersoni engine

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/Andersoni.java`

- [ ] **Step 1: Add metrics.start() call at the end of Andersoni.start()**

In `Andersoni.java`, the `start()` method (line 208) currently ends at line 217. Add the metrics start call after `schedulePeriodicRefreshes()` (line 216), before the closing brace:

```java
  public void start() {
    if (!started.compareAndSet(false, true)) {
      throw new IllegalStateException(
          "Andersoni has already been started");
    }
    leaderElection.start();
    bootstrapAllCatalogs();
    wireSyncListener();
    schedulePeriodicRefreshes();
    metrics.start(
        Collections.unmodifiableCollection(catalogsByName.values()),
        nodeId);
  }
```

- [ ] **Step 2: Add metrics.stop() call as first operation in Andersoni.stop()**

In `Andersoni.java`, the `stop()` method (line 507). Add `metrics.stop()` right after the `compareAndSet` guard (line 509), before `cancelScheduledRefreshes()`:

```java
  public void stop() {
    if (!stopped.compareAndSet(false, true)) {
      return;
    }

    metrics.stop();

    cancelScheduledRefreshes();

    if (syncStrategy != null) {
      syncStrategy.stop();
    }

    leaderElection.stop();
  }
```

- [ ] **Step 3: Run core tests**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-core -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```
Wire AndersoniMetrics lifecycle calls in Andersoni start/stop
```

---

### Task 3: Scaffold andersoni-metrics-datadog module

**Files:**
- Create: `andersoni-metrics-datadog/pom.xml`
- Modify: `pom.xml` (parent — add module)

- [ ] **Step 1: Create module pom.xml**

Create `andersoni-metrics-datadog/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>io.github.waabox</groupId>
    <artifactId>andersoni</artifactId>
    <version>1.5.1</version>
  </parent>

  <artifactId>andersoni-metrics-datadog</artifactId>

  <name>Andersoni Metrics Datadog</name>
  <description>
    Datadog DogStatsD metrics for Andersoni catalogs.
  </description>

  <dependencies>

    <dependency>
      <groupId>io.github.waabox</groupId>
      <artifactId>andersoni-core</artifactId>
    </dependency>

    <dependency>
      <groupId>com.datadoghq</groupId>
      <artifactId>java-dogstatsd-client</artifactId>
      <version>4.4.5</version>
    </dependency>

    <dependency>
      <groupId>org.slf4j</groupId>
      <artifactId>slf4j-api</artifactId>
    </dependency>

    <!-- Test dependencies -->
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>

    <dependency>
      <groupId>org.easymock</groupId>
      <artifactId>easymock</artifactId>
      <scope>test</scope>
    </dependency>

    <dependency>
      <groupId>org.slf4j</groupId>
      <artifactId>slf4j-simple</artifactId>
      <scope>test</scope>
    </dependency>

  </dependencies>

</project>
```

- [ ] **Step 2: Add module to parent pom.xml**

In the parent `pom.xml`, add `andersoni-metrics-datadog` to the `<modules>` section (after `andersoni-admin`, before the closing `</modules>` tag):

```xml
    <module>andersoni-metrics-datadog</module>
```

- [ ] **Step 3: Create source directories**

```bash
mkdir -p andersoni-metrics-datadog/src/main/java/org/waabox/andersoni/metrics/datadog
mkdir -p andersoni-metrics-datadog/src/test/java/org/waabox/andersoni/metrics/datadog
```

- [ ] **Step 4: Verify module compiles**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn compile -pl andersoni-metrics-datadog -am -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```
Scaffold andersoni-metrics-datadog module
```

---

## Chunk 2: DatadogMetricsConfig

### Task 4: Write DatadogMetricsConfig tests

**Files:**
- Create: `andersoni-metrics-datadog/src/test/java/org/waabox/andersoni/metrics/datadog/DatadogMetricsConfigTest.java`

- [ ] **Step 1: Write failing tests for DatadogMetricsConfig**

```java
package org.waabox.andersoni.metrics.datadog;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link DatadogMetricsConfig}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class DatadogMetricsConfigTest {

  @Test
  void whenCreatingConfig_givenDefaults_shouldHaveCorrectValues() {
    final DatadogMetricsConfig config = DatadogMetricsConfig.builder()
        .build();

    assertNull(config.host());
    assertEquals(DatadogMetricsConfig.DEFAULT_PORT, config.port());
    assertEquals(DatadogMetricsConfig.DEFAULT_PREFIX, config.prefix());
    assertArrayEquals(new String[0], config.constantTags());
    assertEquals(DatadogMetricsConfig.DEFAULT_POLLING_INTERVAL,
        config.pollingInterval());
  }

  @Test
  void whenCreatingConfig_givenCustomValues_shouldHaveCustomValues() {
    final DatadogMetricsConfig config = DatadogMetricsConfig.builder()
        .host("custom-host")
        .port(9125)
        .prefix("myapp.andersoni")
        .constantTags("service:my-app", "env:staging")
        .pollingInterval(Duration.ofSeconds(60))
        .build();

    assertEquals("custom-host", config.host());
    assertEquals(9125, config.port());
    assertEquals("myapp.andersoni", config.prefix());
    assertArrayEquals(
        new String[]{"service:my-app", "env:staging"},
        config.constantTags());
    assertEquals(Duration.ofSeconds(60), config.pollingInterval());
  }

  @Test
  void whenCreatingConfig_givenNullPrefix_shouldThrow() {
    assertThrows(NullPointerException.class, () ->
        DatadogMetricsConfig.builder().prefix(null).build());
  }

  @Test
  void whenCreatingConfig_givenNullPollingInterval_shouldThrow() {
    assertThrows(NullPointerException.class, () ->
        DatadogMetricsConfig.builder().pollingInterval(null).build());
  }

  @Test
  void whenCreatingConfig_givenZeroPollingInterval_shouldThrow() {
    assertThrows(IllegalArgumentException.class, () ->
        DatadogMetricsConfig.builder()
            .pollingInterval(Duration.ZERO).build());
  }

  @Test
  void whenCreatingConfig_givenNegativePort_shouldThrow() {
    assertThrows(IllegalArgumentException.class, () ->
        DatadogMetricsConfig.builder().port(-1).build());
  }

  @Test
  void whenCreatingConfig_givenPortAboveMax_shouldThrow() {
    assertThrows(IllegalArgumentException.class, () ->
        DatadogMetricsConfig.builder().port(70000).build());
  }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-metrics-datadog -q`
Expected: COMPILATION ERROR (DatadogMetricsConfig does not exist)

---

### Task 5: Implement DatadogMetricsConfig

**Files:**
- Create: `andersoni-metrics-datadog/src/main/java/org/waabox/andersoni/metrics/datadog/DatadogMetricsConfig.java`

- [ ] **Step 1: Implement DatadogMetricsConfig**

```java
package org.waabox.andersoni.metrics.datadog;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration for the Datadog DogStatsD metrics reporter.
 *
 * <p>Use the {@link #builder()} to create instances. All fields are
 * immutable and validated at build time.
 *
 * <p>When {@code host} is null, the DogStatsD client resolves the agent
 * address from the {@code DD_DOGSTATSD_URL} or {@code DD_AGENT_HOST}
 * environment variables, falling back to {@code localhost}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class DatadogMetricsConfig {

  /** Default DogStatsD port. */
  public static final int DEFAULT_PORT = 8125;

  /** Default metric name prefix. */
  public static final String DEFAULT_PREFIX = "andersoni";

  /** Default gauge polling interval (30 seconds). */
  public static final Duration DEFAULT_POLLING_INTERVAL =
      Duration.ofSeconds(30);

  /** The DogStatsD agent host, null for autodiscovery. */
  private final String host;

  /** The DogStatsD agent port. */
  private final int port;

  /** The metric name prefix. */
  private final String prefix;

  /** Constant tags added to every metric. */
  private final String[] constantTags;

  /** The gauge polling interval. */
  private final Duration pollingInterval;

  private DatadogMetricsConfig(final Builder builder) {
    this.host = builder.host;
    this.port = builder.port;
    this.prefix = Objects.requireNonNull(builder.prefix,
        "prefix must not be null");
    this.constantTags = builder.constantTags.clone();
    this.pollingInterval = Objects.requireNonNull(builder.pollingInterval,
        "pollingInterval must not be null");

    if (port < 0 || port > 65535) {
      throw new IllegalArgumentException(
          "port must be between 0 and 65535, got: " + port);
    }
    if (pollingInterval.isZero() || pollingInterval.isNegative()) {
      throw new IllegalArgumentException(
          "pollingInterval must be positive, got: " + pollingInterval);
    }
  }

  /**
   * Returns the DogStatsD agent host, or null for autodiscovery.
   *
   * @return the host, or null
   */
  public String host() {
    return host;
  }

  /**
   * Returns the DogStatsD agent port.
   *
   * @return the port
   */
  public int port() {
    return port;
  }

  /**
   * Returns the metric name prefix.
   *
   * @return the prefix, never null
   */
  public String prefix() {
    return prefix;
  }

  /**
   * Returns a copy of the constant tags array.
   *
   * @return the constant tags, never null
   */
  public String[] constantTags() {
    return constantTags.clone();
  }

  /**
   * Returns the gauge polling interval.
   *
   * @return the polling interval, never null
   */
  public Duration pollingInterval() {
    return pollingInterval;
  }

  /**
   * Creates a new builder with default values.
   *
   * @return a new builder, never null
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Builder for {@link DatadogMetricsConfig}.
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public static final class Builder {

    /** The host, null by default (autodiscovery). */
    private String host = null;

    /** The port, default 8125. */
    private int port = DEFAULT_PORT;

    /** The prefix, default "andersoni". */
    private String prefix = DEFAULT_PREFIX;

    /** The constant tags, empty by default. */
    private String[] constantTags = new String[0];

    /** The polling interval, default 30s. */
    private Duration pollingInterval = DEFAULT_POLLING_INTERVAL;

    private Builder() {
    }

    /**
     * Sets the DogStatsD agent host.
     *
     * @param theHost the host, or null for autodiscovery
     * @return this builder
     */
    public Builder host(final String theHost) {
      this.host = theHost;
      return this;
    }

    /**
     * Sets the DogStatsD agent port.
     *
     * @param thePort the port number
     * @return this builder
     */
    public Builder port(final int thePort) {
      this.port = thePort;
      return this;
    }

    /**
     * Sets the metric name prefix.
     *
     * @param thePrefix the prefix, never null
     * @return this builder
     */
    public Builder prefix(final String thePrefix) {
      this.prefix = thePrefix;
      return this;
    }

    /**
     * Sets the constant tags added to every metric.
     *
     * @param theTags the tags (e.g. "service:my-app", "env:prod")
     * @return this builder
     */
    public Builder constantTags(final String... theTags) {
      Objects.requireNonNull(theTags, "constantTags must not be null");
      this.constantTags = theTags.clone();
      return this;
    }

    /**
     * Sets the gauge polling interval.
     *
     * @param thePollingInterval the interval, never null, must be positive
     * @return this builder
     */
    public Builder pollingInterval(final Duration thePollingInterval) {
      this.pollingInterval = thePollingInterval;
      return this;
    }

    /**
     * Builds an immutable {@link DatadogMetricsConfig} instance.
     *
     * @return the config, never null
     *
     * @throws NullPointerException     if prefix or pollingInterval is null
     * @throws IllegalArgumentException if port is negative or
     *                                  pollingInterval is zero/negative
     */
    public DatadogMetricsConfig build() {
      return new DatadogMetricsConfig(this);
    }
  }
}
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-metrics-datadog -q`
Expected: BUILD SUCCESS, all 7 tests pass

- [ ] **Step 3: Commit**

```
Add DatadogMetricsConfig with builder pattern
```

---

## Chunk 3: DatadogAndersoniMetrics implementation

### Task 6: Write DatadogAndersoniMetrics tests

**Files:**
- Create: `andersoni-metrics-datadog/src/test/java/org/waabox/andersoni/metrics/datadog/DatadogAndersoniMetricsTest.java`

- [ ] **Step 1: Write failing tests**

```java
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
import org.waabox.andersoni.Snapshot;

import com.timgroup.statsd.StatsDClient;

/** Unit tests for {@link DatadogAndersoniMetrics}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class DatadogAndersoniMetricsTest {

  @Test
  void whenCreatingWithDefaults_shouldNotThrow() {
    final DatadogAndersoniMetrics metrics =
        DatadogAndersoniMetrics.create();
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
  void whenReportingGauges_givenCatalogWithIndex_shouldReportAllGauges() {
    final StatsDClient client = createMock(StatsDClient.class);

    // Build a real catalog with static data and one index
    final Catalog<String> catalog = Catalog.of(String.class)
        .named("cities")
        .data(List.of("Buenos Aires", "Madrid", "Tokyo"))
        .index("by-length").by(String::length)
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

    // Invoke reportGauges via reflection to test synchronously
    // without waiting for the scheduler
    try {
      final var method =
          DatadogAndersoniMetrics.class.getDeclaredMethod("reportGauges");
      method.setAccessible(true);
      method.invoke(metrics);
    } catch (final Exception e) {
      throw new RuntimeException(e);
    }

    metrics.stop();

    verify(client);
  }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-metrics-datadog -q`
Expected: COMPILATION ERROR (DatadogAndersoniMetrics does not exist)

---

### Task 7: Implement DatadogAndersoniMetrics

**Files:**
- Create: `andersoni-metrics-datadog/src/main/java/org/waabox/andersoni/metrics/datadog/DatadogAndersoniMetrics.java`

- [ ] **Step 1: Implement DatadogAndersoniMetrics**

```java
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
 *   <li>{@link #create()} — zero-config K8s autodiscovery</li>
 *   <li>{@link #create(DatadogMetricsConfig)} — custom configuration</li>
 *   <li>{@link #create(StatsDClient)} — user-provided client</li>
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
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-metrics-datadog -q`
Expected: BUILD SUCCESS, all 11 tests pass

- [ ] **Step 3: Commit**

```
Add DatadogAndersoniMetrics implementation
```

---

## Chunk 4: Full build verification

### Task 8: Full build and verify

- [ ] **Step 1: Run full project build**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn clean verify -q`
Expected: BUILD SUCCESS across all modules

- [ ] **Step 2: Commit any fixes if needed**

- [ ] **Step 3: Update CLAUDE.md**

Add `andersoni-metrics-datadog` to the Module table and Package Convention sections in `CLAUDE.md`:

Module table — add row:
```
| `andersoni-metrics-datadog` | Datadog DogStatsD metrics |
```

Package convention — add line:
```
org.waabox.andersoni.metrics.datadog    # Datadog DogStatsD metrics
```

- [ ] **Step 4: Commit CLAUDE.md update**

```
Update CLAUDE.md with andersoni-metrics-datadog module
```
