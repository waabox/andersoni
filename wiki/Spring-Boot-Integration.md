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

## Full Spring Boot Example

```java
@Configuration
public class AndersoniConfig {

    // Leader Election
    @Bean
    K8sLeaseLeaderElection leaderElection(
            @Value("${k8s.lease.name}") final String leaseName,
            @Value("${k8s.lease.namespace}") final String namespace,
            @Value("${HOSTNAME:unknown}") final String identity) {

        final K8sLeaseConfig config = K8sLeaseConfig.create(
            leaseName, namespace, identity,
            Duration.ofSeconds(15), Duration.ofSeconds(30));
        return new K8sLeaseLeaderElection(config);
    }

    // S3 Snapshot Store
    @Bean
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

k8s:
  lease:
    name: andersoni-leader
    namespace: ${K8S_NAMESPACE:default}

s3:
  endpoint: http://minio:9000
  access-key: ${S3_ACCESS_KEY}
  secret-key: ${S3_SECRET_KEY}
  bucket: andersoni-snapshots
  region: us-east-1
```

## Lifecycle

The auto-configuration registers a `SmartLifecycle` that:

- **Phase**: `Integer.MAX_VALUE - 1` (starts last, stops first)
- **start()**: calls `andersoni.start()` after all beans are wired
- **stop()**: calls `andersoni.stop()` during shutdown

This ensures Andersoni starts after the database, Kafka, and other infrastructure beans are ready.
