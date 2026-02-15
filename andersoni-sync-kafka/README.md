# andersoni-sync-kafka

Kafka-based synchronization strategy for [Andersoni](../README.md). Broadcasts `RefreshEvent`s across all nodes using Kafka's consumer group model so every node in the cluster receives every message.

## How It Works

Each node runs its own Kafka consumer with a **unique consumer group** (prefix + UUID). This ensures broadcast semantics: every node receives every refresh event, unlike traditional consumer groups that load-balance across members.

```
Node A (leader):
  refreshAndSync("events")
    -> KafkaSyncStrategy.publish(RefreshEvent)
    -> Kafka topic "andersoni-sync"

Node B, Node C (followers):
  KafkaSyncStrategy receives RefreshEvent
    -> compare hash with local snapshot
    -> if different, refresh from S3 or DataLoader
```

## Usage

### Standalone

```java
KafkaSyncConfig config = KafkaSyncConfig.create("kafka:9092");

Andersoni andersoni = Andersoni.builder()
    .syncStrategy(new KafkaSyncStrategy(config))
    .build();
```

### Spring Boot

```java
@Bean
SyncStrategy syncStrategy() {
    return new KafkaSyncStrategy(
        KafkaSyncConfig.create("kafka:9092"));
}
```

The starter auto-detects the `SyncStrategy` bean and wires it into Andersoni.

## Configuration

| Property | Default | Description |
|---|---|---|
| `bootstrapServers` | *(required)* | Kafka broker connection string |
| `topic` | `andersoni-sync` | Kafka topic for refresh events |
| `consumerGroupPrefix` | `andersoni-` | Prefix for unique consumer groups |

### Custom configuration

```java
KafkaSyncConfig config = KafkaSyncConfig.create(
    "kafka:9092",           // bootstrapServers
    "my-custom-topic",      // topic
    "my-prefix-"            // consumerGroupPrefix
);
```

## Dependencies

| Dependency | Version |
|---|---|
| `andersoni-core` | — |
| `kafka-clients` | 3.9.0 |

## Maven

```xml
<dependency>
    <groupId>io.github.waabox</groupId>
    <artifactId>andersoni-sync-kafka</artifactId>
    <version>1.0.0</version>
</dependency>
```
