# Kafka Sync Improvements Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix bugs and improve robustness of both Kafka sync modules (`andersoni-sync-kafka` and `andersoni-spring-sync-kafka`).

**Architecture:** Seven focused improvements across both modules: publish guard, listener isolation, configurable acks, stable consumer groups, bean collision prevention, KafkaSyncConfig-to-record migration, and sync metrics integration via `AndersoniMetrics`.

**Tech Stack:** Java 21, Kafka clients 3.9.0, Spring Kafka 3.3.2, Spring Boot 3.4.2, JUnit 5, EasyMock.

---

## File Structure

### andersoni-core (modify)
- `src/main/java/org/waabox/andersoni/metrics/AndersoniMetrics.java` — add sync metrics methods
- `src/main/java/org/waabox/andersoni/metrics/NoopAndersoniMetrics.java` — add noop implementations

### andersoni-sync-kafka (modify)
- `src/main/java/org/waabox/andersoni/sync/kafka/KafkaSyncConfig.java` — convert to record, add acks + nodeId fields
- `src/main/java/org/waabox/andersoni/sync/kafka/KafkaSyncStrategy.java` — publish guard, use configurable acks, stable consumer group
- `src/test/java/org/waabox/andersoni/sync/kafka/KafkaSyncStrategyTest.java` — new tests for guard + config

### andersoni-spring-sync-kafka (modify)
- `src/main/java/org/waabox/andersoni/sync/kafka/spring/SpringKafkaSyncProperties.java` — add acks + nodeId properties
- `src/main/java/org/waabox/andersoni/sync/kafka/spring/SpringKafkaSyncStrategy.java` — listener isolation in onMessage
- `src/main/java/org/waabox/andersoni/sync/kafka/spring/SpringKafkaSyncAutoConfiguration.java` — ConditionalOnMissingBean, use new properties
- `src/test/java/org/waabox/andersoni/sync/kafka/spring/SpringKafkaSyncStrategyTest.java` — test listener isolation
- `src/test/java/org/waabox/andersoni/sync/kafka/spring/SpringKafkaSyncAutoConfigurationTest.java` — test bean collision prevention

### andersoni-metrics-datadog (modify)
- `src/main/java/org/waabox/andersoni/metrics/datadog/DatadogAndersoniMetrics.java` — implement sync metrics

---

## Chunk 1: Raw Kafka Module Fixes

### Task 1: Convert KafkaSyncConfig to record + add acks and nodeId fields

**Files:**
- Modify: `andersoni-sync-kafka/src/main/java/org/waabox/andersoni/sync/kafka/KafkaSyncConfig.java`
- Modify: `andersoni-sync-kafka/src/test/java/org/waabox/andersoni/sync/kafka/KafkaSyncStrategyTest.java`

- [ ] **Step 1: Write tests for new config fields**

Add tests to `KafkaSyncStrategyTest.java`:

```java
@Test
void whenCreatingConfig_givenDefaults_shouldHaveDefaultAcks() {
  final KafkaSyncConfig config = KafkaSyncConfig.create("localhost:9092");
  assertEquals("1", config.acks());
}

@Test
void whenCreatingConfig_givenCustomAcks_shouldUseCustomValue() {
  final KafkaSyncConfig config = KafkaSyncConfig.builder()
      .bootstrapServers("localhost:9092")
      .acks("all")
      .build();
  assertEquals("all", config.acks());
}

@Test
void whenCreatingConfig_givenNodeId_shouldUseItForConsumerGroup() {
  final KafkaSyncConfig config = KafkaSyncConfig.builder()
      .bootstrapServers("localhost:9092")
      .nodeId("node-42")
      .build();
  assertEquals("node-42", config.nodeId());
}

@Test
void whenCreatingConfig_givenNoNodeId_shouldDefaultToNull() {
  final KafkaSyncConfig config = KafkaSyncConfig.create("localhost:9092");
  assertNull(config.nodeId());
}

@Test
void whenCreatingConfig_givenBuilder_shouldHaveCorrectDefaults() {
  final KafkaSyncConfig config = KafkaSyncConfig.builder()
      .bootstrapServers("localhost:9092")
      .build();
  assertEquals("localhost:9092", config.bootstrapServers());
  assertEquals("andersoni-sync", config.topic());
  assertEquals("andersoni-", config.consumerGroupPrefix());
  assertEquals("1", config.acks());
  assertNull(config.nodeId());
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-sync-kafka -Dtest=KafkaSyncStrategyTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation errors (builder doesn't exist, acks/nodeId methods missing).

- [ ] **Step 3: Rewrite KafkaSyncConfig as a final class with Builder**

Replace `KafkaSyncConfig.java` entirely. Keep it as a final class (not a record) because we need a Builder pattern and static factories. Add `acks` (default `"1"`) and `nodeId` (nullable, for stable consumer groups):

```java
package org.waabox.andersoni.sync.kafka;

import java.util.Objects;

/** Configuration holder for Kafka-based cache synchronization.
 *
 * <p>Encapsulates the connection details and topic configuration needed by
 * {@link KafkaSyncStrategy} to publish and consume refresh events. Uses a
 * broadcast pattern where each node gets its own consumer group (formed by
 * {@code consumerGroupPrefix + nodeId-or-UUID}), so every instance receives
 * every message.
 *
 * <p>If a {@code nodeId} is provided, it is used as the consumer group suffix
 * instead of a random UUID. This produces a stable consumer group that is
 * reused across restarts, avoiding orphaned consumer groups in the broker.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class KafkaSyncConfig {

  /** Default Kafka topic for sync events. */
  private static final String DEFAULT_TOPIC = "andersoni-sync";

  /** Default consumer group prefix. */
  private static final String DEFAULT_CONSUMER_GROUP_PREFIX = "andersoni-";

  /** Default acks configuration. */
  private static final String DEFAULT_ACKS = "1";

  /** The Kafka bootstrap servers connection string, never null. */
  private final String bootstrapServers;

  /** The Kafka topic where sync events are published, never null. */
  private final String topic;

  /** The prefix for generating unique consumer groups, never null. */
  private final String consumerGroupPrefix;

  /** The producer acknowledgment level, never null. */
  private final String acks;

  /** The optional stable node identifier for consumer group naming. */
  private final String nodeId;

  /** Creates a new KafkaSyncConfig.
   *
   * @param theBootstrapServers the Kafka bootstrap servers, never null
   * @param theTopic the topic name for sync events, never null
   * @param theConsumerGroupPrefix the consumer group prefix, never null
   * @param theAcks the producer acks setting, never null
   * @param theNodeId the optional stable node id, may be null
   */
  /** Valid acks values. */
  private static final Set<String> VALID_ACKS = Set.of("0", "1", "all");

  private KafkaSyncConfig(
      final String theBootstrapServers,
      final String theTopic,
      final String theConsumerGroupPrefix,
      final String theAcks,
      final String theNodeId) {
    bootstrapServers = Objects.requireNonNull(theBootstrapServers, "bootstrapServers must not be null");
    topic = Objects.requireNonNull(theTopic, "topic must not be null");
    consumerGroupPrefix = Objects.requireNonNull(theConsumerGroupPrefix, "consumerGroupPrefix must not be null");
    acks = Objects.requireNonNull(theAcks, "acks must not be null");
    if (!VALID_ACKS.contains(acks)) {
      throw new IllegalArgumentException("acks must be one of " + VALID_ACKS + ", got: " + acks);
    }
    nodeId = theNodeId;
  }

  /** Creates a configuration with the given bootstrap servers and default
   * topic, consumer group prefix, and acks.
   *
   * @param bootstrapServers the Kafka bootstrap servers (e.g.
   *        "localhost:9092"), never null
   *
   * @return a new KafkaSyncConfig with default values, never null
   */
  public static KafkaSyncConfig create(final String bootstrapServers) {
    return new KafkaSyncConfig(bootstrapServers, DEFAULT_TOPIC,
        DEFAULT_CONSUMER_GROUP_PREFIX, DEFAULT_ACKS, null);
  }

  /** Creates a configuration with custom topic and consumer group prefix.
   *
   * @param bootstrapServers the Kafka bootstrap servers (e.g.
   *        "broker1:9092,broker2:9092"), never null
   * @param topic the topic name for sync events, never null
   * @param consumerGroupPrefix the prefix for generating unique consumer
   *        groups, never null
   *
   * @return a new KafkaSyncConfig with the specified values, never null
   */
  public static KafkaSyncConfig create(final String bootstrapServers,
      final String topic, final String consumerGroupPrefix) {
    return new KafkaSyncConfig(bootstrapServers, topic,
        consumerGroupPrefix, DEFAULT_ACKS, null);
  }

  /** Creates a new builder for constructing KafkaSyncConfig instances.
   *
   * @return a new builder, never null
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Returns the Kafka bootstrap servers connection string.
   *
   * @return the bootstrap servers, never null
   */
  public String bootstrapServers() {
    return bootstrapServers;
  }

  /** Returns the Kafka topic where sync events are published and consumed.
   *
   * @return the topic name, never null
   */
  public String topic() {
    return topic;
  }

  /** Returns the prefix used to generate unique consumer group IDs.
   *
   * <p>Each node generates its consumer group as
   * {@code consumerGroupPrefix + nodeId-or-UUID} to ensure broadcast
   * semantics (every node receives every message).
   *
   * @return the consumer group prefix, never null
   */
  public String consumerGroupPrefix() {
    return consumerGroupPrefix;
  }

  /** Returns the producer acknowledgment configuration.
   *
   * <p>Valid values are "0", "1", or "all". Default is "1" (leader ack
   * only). Use "all" for stronger delivery guarantees at the cost of
   * latency.
   *
   * @return the acks setting, never null
   */
  public String acks() {
    return acks;
  }

  /** Returns the optional stable node identifier.
   *
   * <p>When set, this is used as the consumer group suffix instead of a
   * random UUID, producing stable consumer groups across restarts.
   *
   * @return the node identifier, or null if not set
   */
  public String nodeId() {
    return nodeId;
  }

  /** A builder for constructing {@link KafkaSyncConfig} instances.
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public static final class Builder {

    /** The bootstrap servers. */
    private String bootstrapServers;

    /** The topic. */
    private String topic = DEFAULT_TOPIC;

    /** The consumer group prefix. */
    private String consumerGroupPrefix = DEFAULT_CONSUMER_GROUP_PREFIX;

    /** The acks setting. */
    private String acks = DEFAULT_ACKS;

    /** The optional node id. */
    private String nodeId;

    /** Private constructor. */
    private Builder() {
    }

    /** Sets the bootstrap servers.
     *
     * @param theBootstrapServers the bootstrap servers, never null
     * @return this builder, never null
     */
    public Builder bootstrapServers(final String theBootstrapServers) {
      bootstrapServers = Objects.requireNonNull(theBootstrapServers,
          "bootstrapServers must not be null");
      return this;
    }

    /** Sets the topic.
     *
     * @param theTopic the topic name, never null
     * @return this builder, never null
     */
    public Builder topic(final String theTopic) {
      topic = Objects.requireNonNull(theTopic, "topic must not be null");
      return this;
    }

    /** Sets the consumer group prefix.
     *
     * @param theConsumerGroupPrefix the prefix, never null
     * @return this builder, never null
     */
    public Builder consumerGroupPrefix(
        final String theConsumerGroupPrefix) {
      consumerGroupPrefix = Objects.requireNonNull(
          theConsumerGroupPrefix,
          "consumerGroupPrefix must not be null");
      return this;
    }

    /** Sets the producer acks configuration.
     *
     * <p>Valid values: "0", "1", or "all".
     *
     * @param theAcks the acks setting, never null
     * @return this builder, never null
     */
    public Builder acks(final String theAcks) {
      acks = Objects.requireNonNull(theAcks, "acks must not be null");
      return this;
    }

    /** Sets the stable node identifier for consumer group naming.
     *
     * <p>When set, the consumer group is
     * {@code consumerGroupPrefix + nodeId} instead of
     * {@code consumerGroupPrefix + UUID}.
     *
     * @param theNodeId the node identifier, never null
     * @return this builder, never null
     */
    public Builder nodeId(final String theNodeId) {
      nodeId = Objects.requireNonNull(theNodeId,
          "nodeId must not be null");
      return this;
    }

    /** Builds the configuration.
     *
     * @return a new KafkaSyncConfig, never null
     * @throws NullPointerException if bootstrapServers was not set
     */
    public KafkaSyncConfig build() {
      return new KafkaSyncConfig(bootstrapServers, topic,
          consumerGroupPrefix, acks, nodeId);
    }
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-sync-kafka -Dtest=KafkaSyncStrategyTest`
Expected: all tests PASS (existing + new).

- [ ] **Step 5: Commit**

```bash
git add andersoni-sync-kafka/src/main/java/org/waabox/andersoni/sync/kafka/KafkaSyncConfig.java
git add andersoni-sync-kafka/src/test/java/org/waabox/andersoni/sync/kafka/KafkaSyncStrategyTest.java
git commit -m "Add Builder, acks and nodeId to KafkaSyncConfig"
```

---

### Task 2: Add publish guard and use configurable acks/nodeId in KafkaSyncStrategy

**Files:**
- Modify: `andersoni-sync-kafka/src/main/java/org/waabox/andersoni/sync/kafka/KafkaSyncStrategy.java`
- Modify: `andersoni-sync-kafka/src/test/java/org/waabox/andersoni/sync/kafka/KafkaSyncStrategyTest.java`

- [ ] **Step 1: Write test for publish guard**

Add to `KafkaSyncStrategyTest.java`:

```java
@Test
void whenPublishing_givenNotStarted_shouldThrowIllegalStateException() {
  final KafkaSyncConfig config = KafkaSyncConfig.create("localhost:9092");
  final KafkaSyncStrategy strategy = new KafkaSyncStrategy(config);

  final RefreshEvent event = new RefreshEvent(
      "events", "node-1", 1L, "hash",
      Instant.parse("2024-01-01T00:00:00Z"));

  assertThrows(IllegalStateException.class, () -> strategy.publish(event));
}
```

Also add a test for publish after stop:

```java
@Test
void whenPublishing_givenAlreadyStopped_shouldThrowIllegalStateException() {
  final KafkaSyncConfig config = KafkaSyncConfig.create("localhost:9092");
  final KafkaSyncStrategy strategy = new KafkaSyncStrategy(config);

  // start() would require a real broker, so we test the guard directly:
  // running is false by default, which covers both "never started" and
  // "started then stopped" (stop sets running=false).

  final RefreshEvent event = new RefreshEvent(
      "events", "node-1", 1L, "hash",
      Instant.parse("2024-01-01T00:00:00Z"));

  assertThrows(IllegalStateException.class, () -> strategy.publish(event));
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-sync-kafka -Dtest=KafkaSyncStrategyTest#whenPublishing_givenNotStarted_shouldThrowIllegalStateException`
Expected: FAIL — currently throws `NullPointerException` instead of `IllegalStateException`.

- [ ] **Step 3: Add publish guard and update createProducer/createConsumer**

In `KafkaSyncStrategy.java`:

Update `publish()`:
```java
@Override
public void publish(final RefreshEvent event) {
  Objects.requireNonNull(event, "event must not be null");

  if (!running.get()) {
    throw new IllegalStateException(
        "Cannot publish: KafkaSyncStrategy is not running. "
        + "Call start() first.");
  }

  final String json = RefreshEventCodec.serialize(event);
  final ProducerRecord<String, String> record = new ProducerRecord<>(
      config.topic(), event.catalogName(), json);

  producer.send(record, (metadata, exception) -> {
    if (exception != null) {
      log.error("Failed to publish refresh event for catalog '{}': {}",
          event.catalogName(), exception.getMessage(), exception);
    } else {
      log.debug("Published refresh event for catalog '{}' to partition {} "
          + "offset {}", event.catalogName(), metadata.partition(),
          metadata.offset());
    }
  });
}
```

Update `createProducer()` to use `config.acks()`:
```java
private KafkaProducer<String, String> createProducer() {
  final Properties props = new Properties();
  props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
      config.bootstrapServers());
  props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
      StringSerializer.class.getName());
  props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
      StringSerializer.class.getName());
  props.put(ProducerConfig.ACKS_CONFIG, config.acks());
  return new KafkaProducer<>(props);
}
```

Update `createConsumer()` to use `config.nodeId()` when available:
```java
private KafkaConsumer<String, String> createConsumer() {
  final String groupSuffix = config.nodeId() != null
      ? config.nodeId()
      : UUID.randomUUID().toString();
  final String groupId = config.consumerGroupPrefix() + groupSuffix;

  final Properties props = new Properties();
  props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
      config.bootstrapServers());
  props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
  props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
      StringDeserializer.class.getName());
  props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
      StringDeserializer.class.getName());
  props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
  props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");

  log.info("Kafka consumer group: {}", groupId);

  return new KafkaConsumer<>(props);
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-sync-kafka -Dtest=KafkaSyncStrategyTest`
Expected: all tests PASS.

- [ ] **Step 5: Commit**

```bash
git add andersoni-sync-kafka/src/main/java/org/waabox/andersoni/sync/kafka/KafkaSyncStrategy.java
git add andersoni-sync-kafka/src/test/java/org/waabox/andersoni/sync/kafka/KafkaSyncStrategyTest.java
git commit -m "Add publish guard and use configurable acks/nodeId in KafkaSyncStrategy"
```

---

## Chunk 2: Spring Kafka Module Fixes

### Task 3: Fix listener isolation in SpringKafkaSyncStrategy.onMessage()

**Files:**
- Modify: `andersoni-spring-sync-kafka/src/main/java/org/waabox/andersoni/sync/kafka/spring/SpringKafkaSyncStrategy.java`
- Modify: `andersoni-spring-sync-kafka/src/test/java/org/waabox/andersoni/sync/kafka/spring/SpringKafkaSyncStrategyTest.java`

- [ ] **Step 1: Write test proving the bug**

Add to `SpringKafkaSyncStrategyTest.java`:

```java
@Test
@SuppressWarnings("unchecked")
void whenReceivingMessage_givenFirstListenerThrows_shouldStillNotifySecond() {
  final KafkaTemplate<String, String> template = createMock(
      KafkaTemplate.class);

  final SpringKafkaSyncStrategy strategy =
      new SpringKafkaSyncStrategy(template, "test-topic");

  final List<RefreshEvent> received = new ArrayList<>();

  // First listener throws
  strategy.subscribe(event -> {
    throw new RuntimeException("boom");
  });
  // Second listener should still be called
  strategy.subscribe(received::add);

  final Instant timestamp = Instant.parse("2024-01-01T00:00:00Z");
  final RefreshEvent event = new RefreshEvent(
      "events", "node-uuid-1", 1L, "abc123", timestamp);
  final String json = RefreshEventCodec.serialize(event);

  final ConsumerRecord<String, String> record =
      new ConsumerRecord<>("test-topic", 0, 0L, "events", json);

  strategy.onMessage(record);

  assertEquals(1, received.size());
  assertEquals(event, received.get(0));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-spring-sync-kafka -Dtest=SpringKafkaSyncStrategyTest#whenReceivingMessage_givenFirstListenerThrows_shouldStillNotifySecond`
Expected: FAIL — second listener not called because exception propagates.

- [ ] **Step 3: Fix onMessage to isolate listener exceptions**

In `SpringKafkaSyncStrategy.java`, replace the `onMessage` method:

```java
@KafkaListener(
    topics = "#{@springKafkaSyncStrategy.getTopic()}")
public void onMessage(final ConsumerRecord<String, String> record) {
  try {
    final RefreshEvent event = RefreshEventCodec.deserialize(record.value());
    for (final RefreshListener listener : listeners) {
      try {
        listener.onRefresh(event);
      } catch (final Exception e) {
        log.error("Listener threw exception while processing event for "
            + "catalog '{}': {}", event.catalogName(), e.getMessage(), e);
      }
    }
  } catch (final Exception e) {
    log.error("Failed to deserialize refresh event from partition {} "
        + "offset {}: {}", record.partition(), record.offset(),
        e.getMessage(), e);
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-spring-sync-kafka -Dtest=SpringKafkaSyncStrategyTest`
Expected: all tests PASS.

- [ ] **Step 5: Commit**

```bash
git add andersoni-spring-sync-kafka/src/main/java/org/waabox/andersoni/sync/kafka/spring/SpringKafkaSyncStrategy.java
git add andersoni-spring-sync-kafka/src/test/java/org/waabox/andersoni/sync/kafka/spring/SpringKafkaSyncStrategyTest.java
git commit -m "Fix listener isolation in SpringKafkaSyncStrategy.onMessage()"
```

---

### Task 4: Add acks and nodeId to SpringKafkaSyncProperties

**Files:**
- Modify: `andersoni-spring-sync-kafka/src/main/java/org/waabox/andersoni/sync/kafka/spring/SpringKafkaSyncProperties.java`
- Modify: `andersoni-spring-sync-kafka/src/test/java/org/waabox/andersoni/sync/kafka/spring/SpringKafkaSyncPropertiesTest.java`

- [ ] **Step 1: Write tests for new properties**

Add to `SpringKafkaSyncPropertiesTest.java`:

```java
@Test
void whenCreatingProperties_givenDefaults_shouldHaveDefaultAcks() {
  final SpringKafkaSyncProperties properties =
      new SpringKafkaSyncProperties();
  assertEquals("1", properties.getAcks());
}

@Test
void whenSettingAcks_shouldReturnConfiguredValue() {
  final SpringKafkaSyncProperties properties =
      new SpringKafkaSyncProperties();
  properties.setAcks("all");
  assertEquals("all", properties.getAcks());
}

@Test
void whenCreatingProperties_givenDefaults_shouldHaveNullNodeId() {
  final SpringKafkaSyncProperties properties =
      new SpringKafkaSyncProperties();
  assertNull(properties.getNodeId());
}

@Test
void whenSettingNodeId_shouldReturnConfiguredValue() {
  final SpringKafkaSyncProperties properties =
      new SpringKafkaSyncProperties();
  properties.setNodeId("node-42");
  assertEquals("node-42", properties.getNodeId());
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-spring-sync-kafka -Dtest=SpringKafkaSyncPropertiesTest`
Expected: compilation errors.

- [ ] **Step 3: Add acks and nodeId to SpringKafkaSyncProperties**

Add to `SpringKafkaSyncProperties.java`:

```java
/** The producer acknowledgment level, defaults to "1". */
private String acks = "1";

/** The optional stable node identifier for consumer group naming. */
private String nodeId;

/** Returns the producer acknowledgment configuration.
 *
 * @return the acks setting, never null
 */
public String getAcks() {
  return acks;
}

/** Sets the producer acknowledgment configuration.
 *
 * <p>Valid values: "0", "1", or "all".
 *
 * @param theAcks the acks setting, never null
 */
public void setAcks(final String theAcks) {
  acks = theAcks;
}

/** Returns the optional stable node identifier.
 *
 * @return the node identifier, or null if not set
 */
public String getNodeId() {
  return nodeId;
}

/** Sets the stable node identifier for consumer group naming.
 *
 * @param theNodeId the node identifier
 */
public void setNodeId(final String theNodeId) {
  nodeId = theNodeId;
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-spring-sync-kafka -Dtest=SpringKafkaSyncPropertiesTest`
Expected: all tests PASS.

- [ ] **Step 5: Commit**

```bash
git add andersoni-spring-sync-kafka/src/main/java/org/waabox/andersoni/sync/kafka/spring/SpringKafkaSyncProperties.java
git add andersoni-spring-sync-kafka/src/test/java/org/waabox/andersoni/sync/kafka/spring/SpringKafkaSyncPropertiesTest.java
git commit -m "Add acks and nodeId properties to SpringKafkaSyncProperties"
```

---

### Task 5: Fix auto-configuration bean collisions and use new properties

**Files:**
- Modify: `andersoni-spring-sync-kafka/src/main/java/org/waabox/andersoni/sync/kafka/spring/SpringKafkaSyncAutoConfiguration.java`
- Modify: `andersoni-spring-sync-kafka/src/test/java/org/waabox/andersoni/sync/kafka/spring/SpringKafkaSyncAutoConfigurationTest.java`

- [ ] **Step 1: Write test for bean collision back-off**

Add to `SpringKafkaSyncAutoConfigurationTest.java`:

```java
@Test
void whenExistingKafkaListenerContainerFactory_shouldNotOverwrite() {
  contextRunner
      .withPropertyValues(
          "andersoni.sync.kafka.bootstrap-servers=localhost:9092")
      .withBean("kafkaListenerContainerFactory",
          ConcurrentKafkaListenerContainerFactory.class,
          () -> new ConcurrentKafkaListenerContainerFactory<>())
      .run(context -> {
        // User's factory should not be overwritten
        assertTrue(context.containsBean("kafkaListenerContainerFactory"));
        // Andersoni should create its own dedicated factory
        assertTrue(context.containsBean(
            "andersoniKafkaListenerContainerFactory"));
        // Strategy should still be created
        assertTrue(context.containsBean("springKafkaSyncStrategy"));
      });
}

@Test
void whenNodeIdSet_shouldCreateConsumerFactoryWithStableGroup() {
  contextRunner
      .withPropertyValues(
          "andersoni.sync.kafka.bootstrap-servers=localhost:9092",
          "andersoni.sync.kafka.node-id=node-42")
      .run(context -> {
        assertNotNull(context.getBean(ConsumerFactory.class));
        assertNotNull(context.getBean(SpringKafkaSyncStrategy.class));
      });
}

@Test
void whenAcksSet_shouldCreateProducerFactoryWithCustomAcks() {
  contextRunner
      .withPropertyValues(
          "andersoni.sync.kafka.bootstrap-servers=localhost:9092",
          "andersoni.sync.kafka.acks=all")
      .run(context -> {
        assertNotNull(context.getBean(ProducerFactory.class));
      });
}
```

Add imports at top:
```java
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
```

Note: The auto-configuration class will also need `import org.springframework.beans.factory.annotation.Qualifier;`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-spring-sync-kafka -Dtest=SpringKafkaSyncAutoConfigurationTest`
Expected: FAIL on bean collision test (Andersoni overwrites user's factory).

- [ ] **Step 3: Update auto-configuration**

In `SpringKafkaSyncAutoConfiguration.java`:

1. Add `@ConditionalOnMissingBean` to factory beans.
2. Rename `kafkaListenerContainerFactory` to `andersoniKafkaListenerContainerFactory` to avoid collision.
3. Add `containerFactory` attribute to `@KafkaListener` in `SpringKafkaSyncStrategy`.
4. Use `properties.getAcks()` and `properties.getNodeId()`.

Updated bean methods:

```java
@Bean
@ConditionalOnMissingBean(name = "andersoniProducerFactory")
public ProducerFactory<String, String> andersoniProducerFactory(
    final SpringKafkaSyncProperties properties) {

  final Map<String, Object> props = new HashMap<>();
  props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
      properties.getBootstrapServers());
  props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
      StringSerializer.class);
  props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
      StringSerializer.class);
  props.put(ProducerConfig.ACKS_CONFIG, properties.getAcks());

  return new DefaultKafkaProducerFactory<>(props);
}

@Bean
@ConditionalOnMissingBean(name = "andersoniConsumerFactory")
public ConsumerFactory<String, String> andersoniConsumerFactory(
    final SpringKafkaSyncProperties properties) {

  final String groupSuffix = properties.getNodeId() != null
      ? properties.getNodeId()
      : UUID.randomUUID().toString();
  final String groupId = properties.getConsumerGroupPrefix() + groupSuffix;

  final Map<String, Object> props = new HashMap<>();
  props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
      properties.getBootstrapServers());
  props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
  props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
      StringDeserializer.class);
  props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
      StringDeserializer.class);
  props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
  props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);

  log.info("Andersoni Kafka consumer group: {}", groupId);

  return new DefaultKafkaConsumerFactory<>(props);
}

@Bean
@ConditionalOnMissingBean(name = "andersoniKafkaTemplate")
public KafkaTemplate<String, String> andersoniKafkaTemplate(
    @Qualifier("andersoniProducerFactory")
    final ProducerFactory<String, String> producerFactory) {

  return new KafkaTemplate<>(producerFactory);
}

@Bean
@ConditionalOnMissingBean(name = "andersoniKafkaListenerContainerFactory")
public ConcurrentKafkaListenerContainerFactory<String, String>
    andersoniKafkaListenerContainerFactory(
        @Qualifier("andersoniConsumerFactory")
        final ConsumerFactory<String, String> consumerFactory) {

  final ConcurrentKafkaListenerContainerFactory<String, String> factory =
      new ConcurrentKafkaListenerContainerFactory<>();
  factory.setConsumerFactory(consumerFactory);
  return factory;
}
```

Also update `@KafkaListener` in `SpringKafkaSyncStrategy.java` to reference the renamed factory:

```java
@KafkaListener(
    topics = "#{@springKafkaSyncStrategy.getTopic()}",
    containerFactory = "andersoniKafkaListenerContainerFactory")
```

- [ ] **Step 4: Update existing test to reference new bean name**

In `SpringKafkaSyncAutoConfigurationTest.java`, update `whenBootstrapServersSet_shouldCreateAllBeans`:

```java
assertTrue(context.containsBean("andersoniKafkaListenerContainerFactory"));
```

Replace `kafkaListenerContainerFactory` references.

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-spring-sync-kafka`
Expected: all tests PASS.

- [ ] **Step 6: Commit**

```bash
git add andersoni-spring-sync-kafka/src/main/java/org/waabox/andersoni/sync/kafka/spring/SpringKafkaSyncAutoConfiguration.java
git add andersoni-spring-sync-kafka/src/main/java/org/waabox/andersoni/sync/kafka/spring/SpringKafkaSyncStrategy.java
git add andersoni-spring-sync-kafka/src/test/java/org/waabox/andersoni/sync/kafka/spring/SpringKafkaSyncAutoConfigurationTest.java
git commit -m "Fix bean collisions in auto-configuration and use configurable acks/nodeId"
```

---

## Chunk 3: Sync Metrics Integration

### Task 6: Add sync metrics to AndersoniMetrics interface

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/metrics/AndersoniMetrics.java`
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/metrics/NoopAndersoniMetrics.java`

- [ ] **Step 1: Add sync metrics methods to AndersoniMetrics**

Add to `AndersoniMetrics.java` as default methods (backward compatible):

```java
/**
 * Records a sync event published to the cluster.
 *
 * @param catalogName the name of the catalog, never null
 */
default void syncPublished(final String catalogName) {
}

/**
 * Records a sync event received from another node.
 *
 * @param catalogName the name of the catalog, never null
 */
default void syncReceived(final String catalogName) {
}

/**
 * Records a sync publish failure.
 *
 * @param catalogName the name of the catalog, never null
 * @param cause       the failure cause, never null
 */
default void syncPublishFailed(final String catalogName,
    final Throwable cause) {
}

/**
 * Records a sync receive/deserialization failure.
 *
 * @param cause the failure cause, never null
 */
default void syncReceiveFailed(final Throwable cause) {
}
```

- [ ] **Step 2: Verify NoopAndersoniMetrics still compiles**

Since these are default methods, `NoopAndersoniMetrics` doesn't need changes. Verify:

Run: `cd /Users/waabox/code/waabox/andersoni && mvn compile -pl andersoni-core`
Expected: compilation succeeds.

- [ ] **Step 3: Commit**

```bash
git add andersoni-core/src/main/java/org/waabox/andersoni/metrics/AndersoniMetrics.java
git commit -m "Add sync metrics methods to AndersoniMetrics interface"
```

---

### Task 7: Implement sync metrics in DatadogAndersoniMetrics

**Files:**
- Modify: `andersoni-metrics-datadog/src/main/java/org/waabox/andersoni/metrics/datadog/DatadogAndersoniMetrics.java`

Note: First read the current implementation to understand the DogStatsD client usage pattern.

- [ ] **Step 1: Read the current DatadogAndersoniMetrics implementation**

Read the file and understand how it uses the DogStatsD client, tag naming conventions, and metric naming patterns.

- [ ] **Step 2: Add sync metric implementations**

Add overrides for the 4 new sync metrics methods, following the existing naming and tagging pattern. Use counter metrics:

- `andersoni.sync.published` — counter, tag: `catalog:<name>`
- `andersoni.sync.received` — counter, tag: `catalog:<name>`
- `andersoni.sync.publish.failed` — counter, tag: `catalog:<name>`
- `andersoni.sync.receive.failed` — counter (no catalog tag, since deserialization may fail before extracting catalog name)

- [ ] **Step 3: Run full build to verify**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-metrics-datadog`
Expected: all tests PASS.

- [ ] **Step 4: Commit**

```bash
git add andersoni-metrics-datadog/src/main/java/org/waabox/andersoni/metrics/datadog/DatadogAndersoniMetrics.java
git commit -m "Implement sync metrics in DatadogAndersoniMetrics"
```

---

### Task 8: Wire sync metrics callers in Andersoni engine

The sync metrics methods added in Task 6 need to be called from the engine. The `Andersoni` class already handles sync publishing (in `refreshAndSync()`) and receiving (in `wireSyncListener()`). These are the right places to add the metrics calls.

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/Andersoni.java`
- Modify: `andersoni-core/src/test/java/org/waabox/andersoni/AndersoniTest.java` (if it exists, add metrics verification)

- [ ] **Step 1: Add syncPublished call to refreshAndSync()**

In `Andersoni.java`, inside `refreshAndSync()`, after `syncStrategy.publish(event)` (around line 477), add:

```java
syncStrategy.publish(event);
metrics.syncPublished(catalogName);
```

- [ ] **Step 2: Add syncReceived call to wireSyncListener()**

In `Andersoni.java`, inside the `wireSyncListener()` lambda, after the hash-check guard and before calling `refreshFromEvent()` (around line 823), add:

```java
metrics.syncReceived(event.catalogName());
refreshFromEvent(event.catalogName(), catalog);
```

- [ ] **Step 3: Add syncPublishFailed in publish error callback**

In `refreshAndSync()`, wrap the `syncStrategy.publish(event)` call to catch failures:

```java
if (syncStrategy != null) {
  final Snapshot<?> snapshot = catalog.currentSnapshot();
  final RefreshEvent event = new RefreshEvent(
      catalogName,
      nodeId,
      snapshot.version(),
      snapshot.hash(),
      Instant.now());
  try {
    syncStrategy.publish(event);
    metrics.syncPublished(catalogName);
  } catch (final Exception e) {
    log.error("Failed to publish sync event for catalog '{}': {}",
        catalogName, e.getMessage(), e);
    metrics.syncPublishFailed(catalogName, e);
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-core`
Expected: all tests PASS.

- [ ] **Step 5: Commit**

```bash
git add andersoni-core/src/main/java/org/waabox/andersoni/Andersoni.java
git commit -m "Wire sync metrics callers in Andersoni engine"
```

---

### Task 9: Full build verification

- [ ] **Step 1: Run full project build**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn clean verify`
Expected: BUILD SUCCESS, all tests pass across all modules.

- [ ] **Step 2: Review all changes**

Run: `git diff main --stat` to verify only the expected files were modified.

---

## Summary of Improvements

| # | Fix | Module | Type |
|---|-----|--------|------|
| 1 | `KafkaSyncConfig` Builder + acks + nodeId | raw | Enhancement |
| 2 | Publish guard (prevents NPE) | raw | Bug fix |
| 3 | Configurable acks in producer | both | Enhancement |
| 4 | Stable consumer groups via nodeId | both | Bug fix |
| 5 | Listener exception isolation in `onMessage()` | spring | Bug fix |
| 6 | `@ConditionalOnMissingBean` + `@Qualifier` + renamed factory | spring | Bug fix |
| 7 | Sync metrics in `AndersoniMetrics` + Datadog impl | core + datadog | Enhancement |
| 8 | Wire sync metrics callers in `Andersoni` engine | core | Enhancement |
