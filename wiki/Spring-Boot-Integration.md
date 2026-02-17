# Spring Boot Integration

Andersoni provides a Spring Boot starter that auto-discovers infrastructure beans and manages the `Andersoni` lifecycle.

## Dependency

```xml
<dependency>
  <groupId>io.github.waabox</groupId>
  <artifactId>andersoni-spring-boot-starter</artifactId>
  <version>1.1.2</version>
</dependency>
```

## How It Works

The `AndersoniAutoConfiguration` class:

1. Creates an `Andersoni` bean using the builder pattern
2. Auto-discovers optional infrastructure beans from the application context
3. Invokes all `CatalogRegistrar` beans to register catalogs
4. Manages `start()` / `stop()` via Spring's `SmartLifecycle`

## Auto-Discovered Beans

| Bean Type | Max | Default | Purpose |
|-----------|-----|---------|---------|
| `SyncStrategy` | 1 | none (single-node) | Cross-node synchronization |
| `LeaderElectionStrategy` | 1 | `SingleNodeLeaderElection` | Leader coordination |
| `SnapshotStore` | 1 | none | Snapshot persistence |
| `AndersoniMetrics` | 1 | `NoopAndersoniMetrics` | Observability |
| `RetryPolicy` | 1 | 3 retries, 2s backoff | Bootstrap retry |
| `CatalogRegistrar` | many | — | Catalog registration |

If more than one `SyncStrategy` or `SnapshotStore` bean is found, the auto-configuration throws an `IllegalStateException` at startup.

## Configuration Properties

```yaml
andersoni:
  node-id: ${HOSTNAME:}    # Optional, auto-generates UUID if not set
```

## Registering Catalogs

Implement `CatalogRegistrar` as a Spring bean:

```java
@Configuration
public class AndersoniConfig {

    @Bean
    CatalogRegistrar eventsCatalogRegistrar(
            final EventRepository eventRepository) {

        return andersoni -> {
            final Catalog<Event> events = Catalog.of(Event.class)
                .named("events")
                .loadWith(eventRepository::findAll)
                .serializer(new EventSnapshotSerializer())
                .refreshEvery(Duration.ofMinutes(5))
                .index("by-sport").by(Event::sport)
                .index("by-venue").by(Event::venue, Venue::name)
                .index("by-status").by(Event::status)
                .build();

            andersoni.register(events);
        };
    }
}
```

You can define multiple `CatalogRegistrar` beans — they are all invoked before `Andersoni.start()`.

---

## Kafka Sync (Spring Kafka Auto-Configuration)

The `andersoni-spring-sync-kafka` module provides its own auto-configuration. Just add the dependency and set the properties — no manual bean wiring needed.

### Dependency

```xml
<dependency>
  <groupId>io.github.waabox</groupId>
  <artifactId>andersoni-spring-sync-kafka</artifactId>
  <version>1.1.2</version>
</dependency>
```

### Configuration Properties

```yaml
andersoni:
  sync:
    kafka:
      bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
      topic: ${KAFKA_SYNC_TOPIC:andersoni-sync}                      # default
      consumer-group-prefix: ${KAFKA_CONSUMER_GROUP_PREFIX:andersoni-} # default
```

| Property | Default | Required | Description |
|----------|---------|----------|-------------|
| `andersoni.sync.kafka.bootstrap-servers` | — | **Yes** | Kafka broker connection string |
| `andersoni.sync.kafka.topic` | `andersoni-sync` | No | Topic for refresh events |
| `andersoni.sync.kafka.consumer-group-prefix` | `andersoni-` | No | Prefix for unique consumer groups |

### Auto-Configuration Details

`SpringKafkaSyncAutoConfiguration` activates when:
- `KafkaTemplate` is on the classpath (`@ConditionalOnClass`)
- `andersoni.sync.kafka.bootstrap-servers` property is set (`@ConditionalOnProperty`)
- No other `SyncStrategy` bean exists (`@ConditionalOnMissingBean`)

It automatically creates:
- `ProducerFactory<String, String>` — Kafka producer factory
- `ConsumerFactory<String, String>` — Kafka consumer factory with unique group ID
- `KafkaTemplate<String, String>` — for publishing refresh events
- `ConcurrentKafkaListenerContainerFactory` — for consuming refresh events
- `SpringKafkaSyncStrategy` — the `SyncStrategy` bean wired into Andersoni

> **Note:** If you use the plain `andersoni-sync-kafka` module (without Spring), you must wire `KafkaSyncStrategy` as a bean manually. The `andersoni-spring-sync-kafka` module handles everything through auto-configuration.

---

## S3 Snapshot Store (Manual Bean Wiring)

The S3 module does not have auto-configuration. You wire the beans yourself in a `@Configuration` class.

### Dependency

```xml
<dependency>
  <groupId>io.github.waabox</groupId>
  <artifactId>andersoni-snapshot-s3</artifactId>
  <version>1.1.2</version>
</dependency>
```

### Configuration

```java
@Configuration
public class S3Config {

    @Bean(destroyMethod = "close")
    S3Client s3Client(
            @Value("${s3.endpoint}") final String endpoint,
            @Value("${s3.access-key}") final String accessKey,
            @Value("${s3.secret-key}") final String secretKey,
            @Value("${s3.region}") final String region) {

        return S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey, secretKey)))
            .region(Region.of(region))
            .forcePathStyle(true)       // Required for MinIO / LocalStack
            .build();
    }

    @Bean
    S3SnapshotStore snapshotStore(
            final S3Client s3Client,
            @Value("${s3.bucket}") final String bucket,
            @Value("${s3.region}") final String region) {

        final S3SnapshotConfig config = S3SnapshotConfig.builder()
            .bucket(bucket)
            .region(Region.of(region))
            .s3Client(s3Client)
            .prefix("andersoni/")
            .build();
        return new S3SnapshotStore(config);
    }
}
```

```yaml
s3:
  endpoint: ${S3_ENDPOINT:http://localhost:9000}
  access-key: ${S3_ACCESS_KEY:minioadmin}
  secret-key: ${S3_SECRET_KEY:minioadmin}
  bucket: ${S3_BUCKET:andersoni-snapshots}
  region: ${S3_REGION:us-east-1}
```

| Property | Description |
|----------|-------------|
| `s3.endpoint` | S3-compatible endpoint (AWS S3, MinIO, LocalStack) |
| `s3.access-key` | AWS access key or MinIO user |
| `s3.secret-key` | AWS secret key or MinIO password |
| `s3.bucket` | Bucket name (must exist beforehand) |
| `s3.region` | AWS region |

> **Tip:** Use `destroyMethod = "close"` on the `S3Client` bean to ensure the HTTP client is properly closed on shutdown.

### For AWS S3 (no endpoint override)

If using real AWS S3 with default credentials (IAM role, environment variables, etc.):

```java
@Bean(destroyMethod = "close")
S3Client s3Client(@Value("${s3.region}") final String region) {
    return S3Client.builder()
        .region(Region.of(region))
        .build();
}
```

---

## K8s Leader Election (Manual Bean Wiring)

The K8s module does not have auto-configuration. You wire the bean yourself in a `@Configuration` class.

### Dependency

```xml
<dependency>
  <groupId>io.github.waabox</groupId>
  <artifactId>andersoni-leader-k8s</artifactId>
  <version>1.1.2</version>
</dependency>
```

### Configuration

```java
@Configuration
public class K8sConfig {

    @Bean
    K8sLeaseLeaderElection leaderElection(
            @Value("${k8s.lease.name}") final String leaseName,
            @Value("${k8s.lease.namespace}") final String namespace,
            @Value("${HOSTNAME:unknown}") final String identity,
            @Value("${k8s.lease.renewal-interval-seconds:15}") final int renewalSeconds,
            @Value("${k8s.lease.lease-duration-seconds:30}") final int durationSeconds) {

        final K8sLeaseConfig config = K8sLeaseConfig.create(
            leaseName, namespace, identity,
            Duration.ofSeconds(renewalSeconds),
            Duration.ofSeconds(durationSeconds));
        return new K8sLeaseLeaderElection(config);
    }
}
```

```yaml
k8s:
  lease:
    name: andersoni-leader
    namespace: ${K8S_NAMESPACE:default}
    renewal-interval-seconds: 15
    lease-duration-seconds: 30
```

| Property | Default | Description |
|----------|---------|-------------|
| `k8s.lease.name` | *required* | Kubernetes Lease resource name |
| `k8s.lease.namespace` | `default` | Kubernetes namespace |
| `k8s.lease.renewal-interval-seconds` | `15` | How often the leader renews |
| `k8s.lease.lease-duration-seconds` | `30` | Lease validity period |

The `HOSTNAME` environment variable is automatically set by Kubernetes to the pod name. Use it as the node identity:

```yaml
# In your Deployment manifest
env:
  - name: HOSTNAME
    valueFrom:
      fieldRef:
        fieldPath: metadata.name
```

> **RBAC:** The pod's ServiceAccount must have Lease permissions. See [[DevOps & Kubernetes]] for the full RBAC setup.

---

## Full Spring Boot Example

Putting it all together — Kafka sync (auto-configured) + S3 snapshots + K8s leader election + catalog registration:

```java
@Configuration
public class AndersoniConfig {

    // K8s Leader Election (manual)
    @Bean
    K8sLeaseLeaderElection leaderElection(
            @Value("${k8s.lease.name}") final String leaseName,
            @Value("${k8s.lease.namespace}") final String namespace,
            @Value("${HOSTNAME:unknown}") final String identity,
            @Value("${k8s.lease.renewal-interval-seconds:15}") final int renewalSeconds,
            @Value("${k8s.lease.lease-duration-seconds:30}") final int durationSeconds) {

        final K8sLeaseConfig config = K8sLeaseConfig.create(
            leaseName, namespace, identity,
            Duration.ofSeconds(renewalSeconds),
            Duration.ofSeconds(durationSeconds));
        return new K8sLeaseLeaderElection(config);
    }

    // S3 Snapshot Store (manual)
    @Bean(destroyMethod = "close")
    S3Client s3Client(
            @Value("${s3.endpoint}") final String endpoint,
            @Value("${s3.access-key}") final String accessKey,
            @Value("${s3.secret-key}") final String secretKey,
            @Value("${s3.region}") final String region) {

        return S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey, secretKey)))
            .region(Region.of(region))
            .forcePathStyle(true)
            .build();
    }

    @Bean
    S3SnapshotStore snapshotStore(
            final S3Client s3Client,
            @Value("${s3.bucket}") final String bucket,
            @Value("${s3.region}") final String region) {

        final S3SnapshotConfig config = S3SnapshotConfig.builder()
            .bucket(bucket)
            .region(Region.of(region))
            .s3Client(s3Client)
            .prefix("andersoni/")
            .build();
        return new S3SnapshotStore(config);
    }

    // Catalog Registration
    @Bean
    CatalogRegistrar eventsCatalogRegistrar(
            final EventRepository eventRepository) {

        return andersoni -> {
            final Catalog<Event> events = Catalog.of(Event.class)
                .named("events")
                .loadWith(eventRepository::findAll)
                .serializer(new EventSnapshotSerializer())
                .refreshEvery(Duration.ofMinutes(5))
                .index("by-sport").by(Event::sport)
                .index("by-venue").by(Event::venue, Venue::name)
                .index("by-status").by(Event::status)
                .build();

            andersoni.register(events);
        };
    }
}
```

```yaml
# application.yaml
andersoni:
  node-id: ${HOSTNAME:}
  sync:
    kafka:
      bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
      topic: ${KAFKA_SYNC_TOPIC:andersoni-sync}
      consumer-group-prefix: ${KAFKA_CONSUMER_GROUP_PREFIX:andersoni-}

k8s:
  lease:
    name: andersoni-leader
    namespace: ${K8S_NAMESPACE:default}
    renewal-interval-seconds: 15
    lease-duration-seconds: 30

s3:
  endpoint: ${S3_ENDPOINT:http://localhost:9000}
  access-key: ${S3_ACCESS_KEY:minioadmin}
  secret-key: ${S3_SECRET_KEY:minioadmin}
  bucket: ${S3_BUCKET:andersoni-snapshots}
  region: ${S3_REGION:us-east-1}
```

### What Gets Auto-Configured vs Manual

| Component | Module | Auto-Configured? |
|-----------|--------|-----------------|
| **Andersoni** | `andersoni-spring-boot-starter` | Yes — always |
| **Kafka Sync** | `andersoni-spring-sync-kafka` | Yes — when `bootstrap-servers` is set |
| **S3 Snapshots** | `andersoni-snapshot-s3` | No — manual `@Bean` wiring |
| **K8s Leader** | `andersoni-leader-k8s` | No — manual `@Bean` wiring |
| **Filesystem Snapshots** | `andersoni-snapshot-fs` | No — manual `@Bean` wiring |

---

## Lifecycle

The auto-configuration registers a `SmartLifecycle` that:

- **Phase**: `Integer.MAX_VALUE - 1` (starts last, stops first)
- **start()**: calls `andersoni.start()` after all beans are wired
- **stop()**: calls `andersoni.stop()` during shutdown

This ensures Andersoni starts after the database, Kafka, and other infrastructure beans are ready.
