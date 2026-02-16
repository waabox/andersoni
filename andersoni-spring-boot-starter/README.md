# andersoni-spring-boot-starter

Spring Boot auto-configuration for [Andersoni](../README.md). Provides zero-config wiring of the Andersoni instance, lifecycle management, and bean discovery for infrastructure strategies and catalog registrars.

## How It Works

The starter auto-discovers optional beans from the application context and wires them into a singleton `Andersoni` instance:

| Bean Type | Purpose | Required |
|---|---|---|
| `SyncStrategy` | Cross-node refresh sync (Kafka, HTTP, DB) | No |
| `LeaderElectionStrategy` | Leader election (K8s Lease) | No (default: single-node) |
| `SnapshotStore` | Snapshot persistence (S3, filesystem) | No |
| `AndersoniMetrics` | Observability metrics | No (default: no-op) |
| `RetryPolicy` | Bootstrap retry behavior | No (default: 3 retries, 2s backoff) |
| `CatalogRegistrar` | Catalog registration (multiple allowed) | At least one |

The Andersoni lifecycle (start/stop) is managed through Spring's `SmartLifecycle`, starting late to ensure all beans are initialized first.

## Usage

### 1. Add the dependency

```xml
<dependency>
    <groupId>io.github.waabox</groupId>
    <artifactId>andersoni-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 2. Configure properties

```yaml
andersoni:
  node-id: ${HOSTNAME:}    # empty = auto-generated UUID
```

### 3. Register catalogs

Define one or more `CatalogRegistrar` beans. Each registrar is invoked before the Andersoni lifecycle starts.

```java
@Bean
CatalogRegistrar eventsCatalog(EventRepository repository) {
    return andersoni -> andersoni.register(
        Catalog.of(Event.class)
            .named("events")
            .loadWith(repository::findAll)
            .serializer(new EventSnapshotSerializer())
            .refreshEvery(Duration.ofMinutes(5))
            .index("by-sport").by(Event::getSport, Function.identity())
            .index("by-venue").by(Event::getVenue, Function.identity())
            .build()
    );
}
```

Multiple `CatalogRegistrar` beans are supported — each one registers its own catalogs independently.

### 4. Define infrastructure beans (optional)

```java
@Bean
SyncStrategy syncStrategy() {
    return new KafkaSyncStrategy(
        KafkaSyncConfig.create("kafka:9092"));
}

@Bean
SnapshotStore snapshotStore() {
    return new S3SnapshotStore(S3SnapshotConfig.builder()
        .bucket("my-snapshots")
        .region(Region.US_EAST_1)
        .build());
}

@Bean
LeaderElectionStrategy leaderElection(
    @Value("${HOSTNAME:unknown}") String podName) {
    return new K8sLeaseLeaderElection(
        K8sLeaseConfig.create("andersoni-leader", podName));
}
```

If no infrastructure beans are defined, Andersoni runs in single-node mode with no sync, no snapshots, and always-leader election.

## Bean Constraints

`SyncStrategy` and `SnapshotStore` must have **at most one bean** each. If multiple beans of either type are found, the application fails at startup with a clear error:

```
IllegalStateException: Andersoni requires at most one SyncStrategy bean,
but found 2: KafkaSyncStrategy, DbPollingSyncStrategy
```

`LeaderElectionStrategy`, `AndersoniMetrics`, and `RetryPolicy` do not have this constraint.

## Properties

| Property | Default | Description |
|---|---|---|
| `andersoni.node-id` | auto-generated UUID | Unique identifier for this node |

## Dependencies

| Dependency | Version |
|---|---|
| `andersoni-core` | — |
| `spring-boot-autoconfigure` | 3.x |

## Maven

```xml
<dependency>
    <groupId>io.github.waabox</groupId>
    <artifactId>andersoni-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```
