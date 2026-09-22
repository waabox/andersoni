# Andersoni + Spring Boot (1.12.1)

1. [Dependencies](#dependencies)
2. [What the starter auto-configures](#what-the-starter-auto-configures)
3. [`andersoni.*` properties](#andersoni-properties)
4. [Registering catalogs (`CatalogRegistrar`)](#registering-catalogs)
5. [Spring Kafka sync (`andersoni-spring-sync-kafka`)](#spring-kafka-sync)
6. [Wiring the other modules as beans](#wiring-the-other-modules)
7. [Using `Andersoni` in services and controllers](#using-andersoni)
8. [Testing](#testing)
9. [Common mistakes](#common-mistakes)

## Dependencies

```xml
<dependency>
  <groupId>io.github.waabox</groupId>
  <artifactId>andersoni-spring-boot-starter</artifactId>
  <version>1.12.1</version>
</dependency>
<!-- optional: Spring Kafka broadcast sync, auto-configured -->
<dependency>
  <groupId>io.github.waabox</groupId>
  <artifactId>andersoni-spring-sync-kafka</artifactId>
  <version>1.12.1</version>
</dependency>
```

| Artifact | Pulls in (compile) | Notes |
|---|---|---|
| `andersoni-spring-boot-starter` | `andersoni-core` (slf4j-api, org.json), `spring-boot-autoconfigure` 3.4.2 | Built and tested against Spring Boot 3.4.2; Java 21+. Your Boot BOM/parent overrides the autoconfigure version. Does NOT bring `spring-boot-starter`, web, actuator, Kafka, Jackson, or any sync/leader/snapshot module. |
| `andersoni-spring-sync-kafka` | `andersoni-core`, `spring-kafka` 3.3.2 (brings `kafka-clients`) | `spring-boot-autoconfigure` is `optional` here; it comes from the starter / your app. |
| `andersoni-leader-k8s`, `andersoni-snapshot-s3`, `andersoni-snapshot-fs`, `andersoni-json-serializer`, `andersoni-metrics-datadog`, `andersoni-sync-kafka`, `andersoni-sync-http`, `andersoni-sync-db` | their own clients | **No Spring auto-configuration.** Declare them as `@Bean`s yourself (see below). |

Only two auto-configurations exist (registered in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`):
`org.waabox.andersoni.spring.AndersoniAutoConfiguration` and `org.waabox.andersoni.sync.kafka.spring.SpringKafkaSyncAutoConfiguration`.

## What the starter auto-configures

`AndersoniAutoConfiguration` (`@AutoConfiguration`, `@EnableConfigurationProperties(AndersoniProperties.class)`):

| Bean | Name | Condition | What it does |
|---|---|---|---|
| `Andersoni` | `andersoni` | `@ConditionalOnMissingBean(Andersoni.class)` | `Andersoni.builder()` + properties + optional beans below, then calls every `CatalogRegistrar.register(andersoni)`. Does NOT start it. |
| `SmartLifecycle` | `andersoniLifecycle` | `@ConditionalOnMissingBean(name = "andersoniLifecycle")` | `start()` -> `andersoni.start()`; `stop()` -> `andersoni.stop()`. Phase `Integer.MAX_VALUE - 1` (starts late, stops early). |

Optional beans picked up by type through `ObjectProvider.ifAvailable(...)`:

| Bean type | Builder call | If absent | Multiplicity |
|---|---|---|---|
| `org.waabox.andersoni.sync.SyncStrategy` | `syncStrategy(...)` | no sync (single node) | at most one; >1 -> `IllegalStateException("Andersoni requires at most one SyncStrategy bean, but found N: ...")`, even with `@Primary` |
| `org.waabox.andersoni.snapshot.SnapshotStore` | `snapshotStore(...)` | no snapshot store, reconciliation inactive | at most one, same check as above |
| `org.waabox.andersoni.leader.LeaderElectionStrategy` | `leaderElection(...)` | `SingleNodeLeaderElection` (always leader) | must be unique or `@Primary` (Spring `ObjectProvider` rule) |
| `org.waabox.andersoni.metrics.AndersoniMetrics` | `metrics(...)` | no-op metrics | unique or `@Primary` |
| `org.waabox.andersoni.RetryPolicy` | `retryPolicy(...)` | `RetryPolicy.defaultPolicy()` = 3 total attempts (leader; followers 10x), 2s backoff | unique or `@Primary` |
| `org.waabox.andersoni.spring.CatalogRegistrar` | invoked after `build()` | zero catalogs (context still starts) | any number (injected as `List`) |

Lifecycle facts:
- `andersoni.start()` runs synchronously inside Spring's lifecycle start, i.e. during `context.refresh()`, before `ApplicationReadyEvent`. It bootstraps every catalog (snapshot store first, then `DataLoader` with retries on the leader), wires sync, schedules `refreshEvery` tasks, starts the reconciler and metrics. Slow loaders delay startup.
- A catalog that exhausts its retries is marked failed and does NOT abort startup; queries on it throw `CatalogNotAvailableException` and `status()` reports `available=false`.
- If `start()` throws, the lifecycle calls `andersoni.stop()` (best effort) and rethrows -> context fails.
- `Andersoni.register(...)` after `start()` throws `IllegalStateException`. Register only through `CatalogRegistrar`.
- Andersoni calls `start()/stop()` on the `SyncStrategy`, `LeaderElectionStrategy` and `AndersoniMetrics` itself; do not start them yourself.
- If you define your own `Andersoni` bean, the auto-configured one backs off, registrars are NOT invoked, and `andersoniLifecycle` still starts/stops your bean (override it by defining a bean named `andersoniLifecycle`).

## `andersoni.*` properties

Bound by `org.waabox.andersoni.spring.AndersoniProperties` (prefix `andersoni`). This is the complete list.

| Property | Type | Default | Meaning |
|---|---|---|---|
| `andersoni.node-id` | `String` | unset -> env `HOSTNAME` if non-blank (pod name in K8s), else random UUID | Node identity (`andersoni.nodeId()`). Blank is treated as unset. |
| `andersoni.reconciliation.enabled` | `boolean` | `true` | `true` -> `ReconciliationPolicy.of(interval)`; `false` -> `ReconciliationPolicy.disabled()`. Only effective when a `SnapshotStore` bean exists. |
| `andersoni.reconciliation.interval` | `Duration` | `30s` | Base interval between anti-entropy passes. Must be positive (else `IllegalArgumentException` at startup). |

Anything else (retry, catalog refresh interval, leader election, S3, K8s) is NOT a property: it is a bean or catalog DSL option.

```yaml
andersoni:
  node-id: ${HOSTNAME:local-dev}   # use the pod name in Kubernetes
  reconciliation:
    enabled: true
    interval: 30s
```

## Registering catalogs

The starter does **not** discover `Catalog<T>` beans. The only hook is `CatalogRegistrar` (`@FunctionalInterface void register(Andersoni andersoni)`). Declare one or more as beans; each calls `andersoni.register(catalog)`. Catalog names must be unique (duplicate -> `IllegalArgumentException`).

```java
import java.time.Duration;
import java.util.function.Function;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.waabox.andersoni.Catalog;
import org.waabox.andersoni.spring.CatalogRegistrar;

@Configuration
public class CatalogConfig {

  @Bean
  public CatalogRegistrar productsCatalogRegistrar(final ProductRepository repository) {
    return andersoni -> {
      final Catalog<Product> catalog = Catalog.of(Product.class)
          .named("products")
          .loadWith(repository::findAll)                 // DataLoader<T>: List<T> load()
          .index("by-category").by(Product::getCategory, Function.identity())
          .indexSorted("by-price").by(Product::getPrice, Function.identity())
          .serializer(new ProductSnapshotSerializer())   // needed for SnapshotStore + cross-node hash
          .refreshEvery(Duration.ofMinutes(5))           // leader-only periodic refreshAndSync
          .build();
      andersoni.register(catalog);
    };
  }
}
```

- The loader runs later, at `andersoni.start()`, not when the registrar runs; it may use JPA/JDBC repositories normally.
- The registrar bean method may inject any Spring bean (repositories, `ObjectMapper`, config values).
- Several registrars (one per bounded context) are fine.

## Spring Kafka sync

`SpringKafkaSyncAutoConfiguration` activates only when ALL hold:
- `@ConditionalOnClass(KafkaTemplate.class)` (spring-kafka on classpath; the module brings it),
- `@ConditionalOnProperty(prefix = "andersoni.sync.kafka", name = "bootstrap-servers")` (property present and not `false`),
- `@ConditionalOnMissingBean(SyncStrategy.class)` (your own `SyncStrategy` bean wins).

Beans created:

| Bean name | Type | Condition | Config |
|---|---|---|---|
| `andersoniProducerFactory` | `ProducerFactory<String, String>` | missing bean by name | bootstrap servers, `StringSerializer` key/value, `acks` |
| `andersoniConsumerFactory` | `ConsumerFactory<String, String>` | missing bean by name | group id = `consumer-group-prefix` + (`node-id` or random UUID), `StringDeserializer`, `auto.offset.reset=latest`, `enable.auto.commit=true` |
| `andersoniKafkaTemplate` | `KafkaTemplate<String, String>` | missing bean by name | uses `andersoniProducerFactory` |
| `andersoniKafkaListenerContainerFactory` | `ConcurrentKafkaListenerContainerFactory<String, String>` | missing bean by name | uses `andersoniConsumerFactory` |
| `springKafkaSyncStrategy` | `SpringKafkaSyncStrategy` (a `SyncStrategy`) | none beyond class conditions | `new SpringKafkaSyncStrategy(kafkaTemplate, topic)` |

These are separate from Boot's own `spring.kafka.*` beans (`kafkaTemplate`, `kafkaListenerContainerFactory`), which keep working for your application topics.

`SpringKafkaSyncProperties` (prefix `andersoni.sync.kafka`), complete list:

| Property | Type | Default | Meaning |
|---|---|---|---|
| `bootstrap-servers` | `String` | none (required; its presence activates the module) | Kafka brokers, `host:port[,host:port]` |
| `topic` | `String` | `andersoni-sync` | Topic for refresh events/requests. Also read by `@KafkaListener` via `${andersoni.sync.kafka.topic:andersoni-sync}`. All nodes of one app must share it. |
| `consumer-group-prefix` | `String` | `andersoni-` | Prefix of the per-node consumer group (broadcast: every node gets every message) |
| `acks` | `String` | `1` | Producer acks; only `0`, `1`, `all` (else `IllegalArgumentException` at binding) |
| `node-id` | `String` | unset -> random UUID per start | Stable group suffix; avoids orphaned groups after restarts. Independent of `andersoni.node-id` (set both). |

```yaml
andersoni:
  node-id: ${HOSTNAME:local-dev}
  sync:
    kafka:
      bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
      topic: my-app-andersoni-sync
      consumer-group-prefix: my-app-
      node-id: ${HOSTNAME:local-dev}
      acks: all
```

Behavior: messages are JSON (`RefreshEventCodec`), keyed by catalog name; publish is async (`KafkaTemplate.send(...).whenComplete`, failures logged only). `start()/stop()` are no-ops; Spring owns the listener container. Consuming requires Spring Kafka's `@KafkaListener` processing, which Boot's `KafkaAutoConfiguration` enables (`@EnableKafka`) when spring-kafka is on the classpath; add `@EnableKafka` yourself if you excluded it.

## Wiring the other modules

None of these auto-configure. Declare exactly one `SyncStrategy` and at most one `SnapshotStore`.

```java
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.waabox.andersoni.leader.k8s.K8sLeaseConfig;
import org.waabox.andersoni.leader.k8s.K8sLeaseLeaderElection;
import org.waabox.andersoni.metrics.datadog.DatadogAndersoniMetrics;
import org.waabox.andersoni.metrics.datadog.DatadogMetricsConfig;
import org.waabox.andersoni.snapshot.s3.S3SnapshotConfig;
import org.waabox.andersoni.snapshot.s3.S3SnapshotStore;
import software.amazon.awssdk.regions.Region;

@Configuration
public class AndersoniInfraConfig {

  @Bean
  public K8sLeaseLeaderElection leaderElection(@Value("${andersoni.node-id}") final String nodeId) {
    // create(leaseName, namespace, identity, renewalInterval, leaseDuration)
    // or create(leaseName, identity): namespace "default", 15s renewal, 30s lease
    return new K8sLeaseLeaderElection(K8sLeaseConfig.create(
        "my-app-andersoni-leader", "my-namespace", nodeId, Duration.ofSeconds(15), Duration.ofSeconds(30)));
  }

  @Bean // S3SnapshotStore is AutoCloseable; Spring infers close() on shutdown
  public S3SnapshotStore snapshotStore(@Value("${s3.bucket}") final String bucket) {
    return new S3SnapshotStore(S3SnapshotConfig.builder()
        .bucket(bucket)                 // required
        .region(Region.US_EAST_1)       // required
        .prefix("andersoni/")           // default "andersoni/"
        .build());                      // optional: .s3Client(client), .roleArn(...)
  }

  @Bean
  public DatadogAndersoniMetrics andersoniMetrics() {
    // create() resolves the agent from DD_DOGSTATSD_URL / DD_AGENT_HOST, else localhost:8125
    return DatadogAndersoniMetrics.create(DatadogMetricsConfig.builder().prefix("myapp").build());
  }
}
```

| Module | Bean to declare | Constructor / factory |
|---|---|---|
| `andersoni-snapshot-fs` (dev/test) | `SnapshotStore` | `new FileSystemSnapshotStore(Path baseDir)` |
| `andersoni-json-serializer` | not a bean: pass to `.serializer(...)` | `new JacksonSnapshotSerializer<>(new TypeReference<List<Product>>() {})` or `(ObjectMapper, TypeReference)` (inject Boot's `ObjectMapper`) |
| `andersoni-sync-kafka` (raw, no Spring) | `SyncStrategy` | `new KafkaSyncStrategy(KafkaSyncConfig.create("broker:9092"))` - do not combine with `andersoni.sync.kafka.bootstrap-servers` |
| `andersoni-sync-http` | `SyncStrategy` | `new HttpSyncStrategy(HttpSyncConfig.create(port, peerUrls))` |
| `andersoni-sync-db` | `SyncStrategy` | `new DbPollingSyncStrategy(DbPollingSyncConfig.create(dataSource))` (table `andersoni_sync_log`, 5s poll). Cannot carry refresh REQUESTs: a follower's `refreshAndSync` is a no-op. |

## Using Andersoni

Inject the single `Andersoni` bean by constructor.

```java
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.waabox.andersoni.Andersoni;
import org.waabox.andersoni.AndersoniStatus;

@RestController
public class ProductController {

  private final Andersoni andersoni;

  public ProductController(final Andersoni theAndersoni) {
    andersoni = theAndersoni;
  }

  @GetMapping("/products")
  public List<Product> byCategory(@RequestParam("category") final String category) {
    return andersoni.search("products", "by-category", category, Product.class);
  }

  @PostMapping("/admin/andersoni/products/refresh")
  public void refresh() {
    andersoni.refreshAndSync("products");
  }

  @GetMapping("/admin/andersoni/status")
  public AndersoniStatus status() {
    return andersoni.status(); // record: nodeId, leader, catalogs[...]; inSync()
  }
}
```

After a write (service method, `@KafkaListener` on your domain topic, `@Scheduled` job), call `andersoni.refreshAndSync(catalogName)`:
- Leader: reloads via the `DataLoader` **synchronously on the calling thread**, saves to the `SnapshotStore` (if catalog has a serializer), publishes an `EVENT`; other nodes reload.
- Follower with sync: publishes a `REQUEST`; only the leader acts on it; returns immediately.
- Follower without sync: no-op.
- Throws `IllegalStateException` after `stop()`, and on the leader for a catalog built with `.data(...)` (no loader). Unknown catalog -> `IllegalArgumentException`.
- Run it after the transaction commits (e.g. `@TransactionalEventListener(phase = AFTER_COMMIT)`), otherwise the loader may read pre-commit data.

Other ops: `andersoni.refresh(name)` = local only, no sync (a follower's result is overwritten by the store on the next reconciliation pass); `andersoni.reconcile()` = asynchronous anti-entropy pass, never calls the loader, no-op when reconciliation is inactive. `status().catalogs()` entries expose `catalogName, available, version, hash, hashComparable, itemCount, estimatedSizeMB, syncState (IN_SYNC|DRIFTED|UNKNOWN), lastReconciledAt (Optional<Instant>)`. The starter ships no actuator integration; if you use actuator, wrap `status()` in your own `HealthIndicator`.

## Testing

- Unit tests need no Spring: `Andersoni.builder().build()`, `register(...)`, `start()`, query, `stop()` in `finally`. Use `Catalog.of(T.class).named("x").data(list)` for fixed data (and `catalog.refresh(List<T>)` to swap data).
- `@SpringBootTest` without Kafka: do not set `andersoni.sync.kafka.bootstrap-servers` in the test profile, or exclude it: `spring.autoconfigure.exclude=org.waabox.andersoni.sync.kafka.spring.SpringKafkaSyncAutoConfiguration`. Alternatively declare a no-op `SyncStrategy` `@Bean` (4 methods: `publish`, `subscribe`, `start`, `stop`); the Kafka auto-config then backs off.
- Disable anti-entropy: `andersoni.reconciliation.enabled=false`. Fix the node: `andersoni.node-id=test-node`.
- Without leader/store beans the test node is always leader with no snapshot store, so `refreshAndSync` reloads locally.
- Slice-test auto-config with `ApplicationContextRunner().withConfiguration(AutoConfigurations.of(AndersoniAutoConfiguration.class))`.
- Real loaders run during context startup: make the test datasource (H2/Testcontainers) ready and seeded before the context starts, or catalogs end up failed.

## Common mistakes

| Mistake | Effect / fix |
|---|---|
| Declaring `Catalog<T>` as a `@Bean` | Ignored. Register inside a `CatalogRegistrar`. |
| Calling `andersoni.start()` / `stop()` manually in a Spring app | `start()` twice -> `IllegalStateException("Andersoni has already been started")`. The lifecycle bean does it. |
| Registering catalogs from `@PostConstruct` / `ApplicationReadyEvent` | Too late after start -> `IllegalStateException`. |
| Two `SyncStrategy` or two `SnapshotStore` beans (e.g. `andersoni.sync.kafka.bootstrap-servers` set AND a raw `KafkaSyncStrategy` bean) | Startup fails. Keep one. Note a user `SyncStrategy` bean makes the Spring Kafka module back off entirely. |
| Two `LeaderElectionStrategy`/`AndersoniMetrics`/`RetryPolicy` beans without `@Primary` | `NoUniqueBeanDefinitionException`. |
| Setting only `andersoni.node-id` and expecting a stable Kafka group | Group uses `andersoni.sync.kafka.node-id`; set it too. |
| Expecting `spring.kafka.*` to configure Andersoni sync | It does not; only `andersoni.sync.kafka.*` is used. |
| Snapshot store configured but catalog has no `.serializer(...)` | Store not used for that catalog; `syncState` stays `UNKNOWN`, `hashComparable=false`. |
| K8s leader identity different per restart | Pass the pod name (`HOSTNAME`) as both `andersoni.node-id` and the lease identity. |
| `refreshAndSync` inside the write transaction | Loader may miss uncommitted data; trigger after commit. |
| `.data(list)` catalog + `refreshAndSync`/`refreshEvery` | Leader throws `IllegalStateException`; use `loadWith(...)`. |
