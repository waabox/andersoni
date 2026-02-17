# Sync Strategies

Andersoni uses a pluggable `SyncStrategy` to broadcast refresh events across nodes. Three implementations are provided.

## Choosing a Strategy

| Strategy | Latency | Infrastructure | Best For |
|----------|---------|----------------|----------|
| **Kafka** | ~ms | Kafka cluster | Production multi-node setups |
| **HTTP** | ~ms | None (peer-to-peer) | Small clusters, no Kafka |
| **DB Polling** | seconds | Shared database | Simplest setup, tolerates lag |

If you don't configure a `SyncStrategy`, Andersoni runs in single-node mode — refreshes are local only.

---

## Kafka Sync

Broadcast pattern: each node creates a unique consumer group (prefix + UUID), ensuring every node receives every refresh event.

### Dependency

```xml
<dependency>
  <groupId>io.github.waabox</groupId>
  <artifactId>andersoni-sync-kafka</artifactId>
  <version>1.1.2</version>
</dependency>
```

### Usage

```java
KafkaSyncConfig config = KafkaSyncConfig.create(
    "kafka-broker-1:9092,kafka-broker-2:9092",   // bootstrap servers
    "andersoni-sync",                              // topic (default)
    "andersoni-"                                   // consumer group prefix (default)
);

KafkaSyncStrategy kafkaSync = new KafkaSyncStrategy(config);
```

With defaults:

```java
KafkaSyncConfig config = KafkaSyncConfig.create("kafka:9092");
```

### Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `bootstrapServers` | *required* | Kafka bootstrap servers |
| `topic` | `andersoni-sync` | Topic for refresh events |
| `consumerGroupPrefix` | `andersoni-` | Prefix for unique consumer groups |

### Kafka Requirements

- The topic must exist or auto-creation must be enabled
- Each node gets a unique consumer group: `{prefix}{UUID}` — this ensures broadcast semantics (every node gets every message)
- Producer config: `acks=1`, serializer = String
- Consumer config: `auto.offset.reset=latest`

### Spring Boot (with Spring Kafka)

For Spring Kafka integration, use the dedicated starter:

```xml
<dependency>
  <groupId>io.github.waabox</groupId>
  <artifactId>andersoni-spring-sync-kafka</artifactId>
  <version>1.1.2</version>
</dependency>
```

---

## HTTP Sync

Direct peer-to-peer HTTP POST. Each node runs a lightweight HTTP server and posts refresh events to all known peers.

### Dependency

```xml
<dependency>
  <groupId>io.github.waabox</groupId>
  <artifactId>andersoni-sync-http</artifactId>
  <version>1.1.2</version>
</dependency>
```

### Usage

```java
HttpSyncConfig config = HttpSyncConfig.create(
    8081,                                          // local port
    List.of(                                       // peer URLs
        "http://node-2:8081",
        "http://node-3:8081"
    )
);

HttpSyncStrategy httpSync = new HttpSyncStrategy(config);
```

With custom path:

```java
HttpSyncConfig config = HttpSyncConfig.create(
    8081,
    List.of("http://node-2:8081", "http://node-3:8081"),
    "/andersoni/refresh"                           // default path
);
```

### Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `port` | *required* | Local HTTP server port |
| `peerUrls` | *required* | List of peer base URLs |
| `path` | `/andersoni/refresh` | Endpoint path |

### How It Works

- Uses Java's built-in `com.sun.net.httpserver.HttpServer` (no external dependencies)
- Publishing is fire-and-forget: POST to all peers asynchronously
- If a peer is unreachable, the error is logged but does not block other peers

### Limitations

- Requires a static list of peer URLs (no dynamic discovery)
- Not suitable for large clusters or frequently changing topologies

---

## DB Polling Sync

Shared database table polling. Nodes write refresh events to a table and poll for changes on a schedule.

### Dependency

```xml
<dependency>
  <groupId>io.github.waabox</groupId>
  <artifactId>andersoni-sync-db</artifactId>
  <version>1.1.2</version>
</dependency>
```

### Usage

```java
DbPollingSyncConfig config = DbPollingSyncConfig.create(
    dataSource,                                    // javax.sql.DataSource
    "andersoni_sync_log",                          // table name (default)
    Duration.ofSeconds(5)                          // poll interval (default)
);

DbPollingSyncStrategy dbSync = new DbPollingSyncStrategy(config);
```

With defaults:

```java
DbPollingSyncConfig config = DbPollingSyncConfig.create(dataSource);
```

### Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `dataSource` | *required* | JDBC DataSource |
| `tableName` | `andersoni_sync_log` | Table for refresh events |
| `pollInterval` | 5 seconds | How often to poll for changes |

### Table Schema

The table is created automatically on `start()`:

```sql
CREATE TABLE IF NOT EXISTS andersoni_sync_log (
    catalog_name    VARCHAR(255) PRIMARY KEY,
    source_node_id  VARCHAR(255) NOT NULL,
    version         BIGINT NOT NULL,
    hash            VARCHAR(255) NOT NULL,
    updated_at      TIMESTAMP NOT NULL
);
```

### How It Works

- On `publish()`: UPSERT (UPDATE then INSERT if not found) into the table
- On poll interval: SELECT all rows, compare hashes with local state
- If a hash differs from the last known hash → fire refresh event to listener
- Tracks `lastKnownHashes` in memory to avoid redundant refreshes

### Limitations

- Highest latency of the three strategies (depends on poll interval)
- Requires a shared database accessible by all nodes
