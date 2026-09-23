# Andersoni cluster modules (1.12.2)

Pluggable modules for multi-node sync, leader election, snapshot persistence, serialization and metrics.
All artifacts: groupId `io.github.waabox`, version `1.12.2`. Every module depends on `andersoni-core` transitively.

## Contents

1. [How the pieces plug in](#how-the-pieces-plug-in)
2. [Choosing a sync strategy](#choosing-a-sync-strategy)
3. [andersoni-sync-kafka](#andersoni-sync-kafka)
4. [andersoni-spring-sync-kafka](#andersoni-spring-sync-kafka)
5. [andersoni-sync-http](#andersoni-sync-http)
6. [andersoni-sync-db](#andersoni-sync-db)
7. [andersoni-leader-k8s](#andersoni-leader-k8s)
8. [andersoni-snapshot-s3](#andersoni-snapshot-s3)
9. [andersoni-snapshot-fs](#andersoni-snapshot-fs)
10. [andersoni-json-serializer](#andersoni-json-serializer)
11. [andersoni-metrics-datadog](#andersoni-metrics-datadog)
12. [andersoni-admin](#andersoni-admin)
13. [Recommended production topologies](#recommended-production-topologies)

## How the pieces plug in

| `Andersoni.Builder` method | SPI (package) | Default when omitted |
|---|---|---|
| `nodeId(String)` | - | env `HOSTNAME`, else random UUID |
| `syncStrategy(SyncStrategy)` | `org.waabox.andersoni.sync` | none (no cross-node sync) |
| `leaderElection(LeaderElectionStrategy)` | `org.waabox.andersoni.leader` | `SingleNodeLeaderElection` (always leader) |
| `snapshotStore(SnapshotStore)` | `org.waabox.andersoni.snapshot` | none |
| `metrics(AndersoniMetrics)` | `org.waabox.andersoni.metrics` | `NoopAndersoniMetrics` |
| `retryPolicy(RetryPolicy)` | `org.waabox.andersoni` | `RetryPolicy.defaultPolicy()` |
| `reconciliation(ReconciliationPolicy)` | `org.waabox.andersoni` | `ReconciliationPolicy.defaultPolicy()` (30s) |
| `maxPatchPayloadBytes(int)` (1.13.0) | `org.waabox.andersoni` | 256 KB; larger patches are published as a full-refresh `EVENT` |

Lifecycle facts (verified in `Andersoni`):
- `start()` order: `leaderElection.start()` -> bootstrap catalogs -> `syncStrategy.subscribe(...)` + `syncStrategy.start()` -> periodic refreshes -> reconciler -> `metrics.start(catalogs, nodeId)`.
- `stop()` stops the reconciler, `syncStrategy.stop()`, `leaderElection.stop()`, `metrics.stop()`. It does **not** close the `SnapshotStore`: close `S3SnapshotStore` yourself (it is `AutoCloseable`).
- The snapshot store is used **only for catalogs that declare `.serializer(...)`**. A catalog without a serializer never touches the store.
- The reconciler runs only when a store is configured and the policy is enabled (`ReconciliationPolicy.disabled()` turns it off).
- Incoming sync events are ignored when they come from this node (`sourceNodeId == nodeId`) or when their hash equals the local snapshot hash, so duplicates and self-delivery are harmless. `nodeId` must be unique per node.
- `REQUEST` events (published by `refreshAndSync` on a follower) are acted on only by the leader; followers ignore them.
- `PatchEvent`s (1.13.0, published by the leader's `replaceAndSync`/`insertAndSync`/`removeAndSync`/`upsertAndSync`/`applyAndSync`) are delivered only by strategies whose `supportsPatches()` is `true` (Kafka, Spring Kafka, HTTP). A follower applies one surgically when the event's `fromHash` equals its local hash, discards it when `toHash` already matches, and reloads fully otherwise. Details: `engine-and-operations.md`, section 4.

## Choosing a sync strategy

| Strategy | Propagation latency | Infra needed | Carries `REQUEST` (follower `refreshAndSync`) | Carries patches (1.13.0) | Ordering / delivery | Missed events |
|---|---|---|---|---|---|---|
| none | - | none | no (follower `refreshAndSync` is a logged no-op) | no (leader patches apply locally and are saved to the store; nothing is broadcast) | - | - |
| `KafkaSyncStrategy` | ms (async send) | Kafka topic | yes | yes | per catalog ordered (record key = catalog name); broadcast, one consumer group per node | `auto.offset.reset=latest`; stable `nodeId` group resumes from committed offset, random group loses them; reconciliation repairs |
| `SpringKafkaSyncStrategy` | ms | Kafka + Spring Kafka | yes | yes | same as Kafka | same as Kafka |
| `HttpSyncStrategy` | ms (async POST per peer) | open port on every node, static peer list | yes, only if the leader is in the follower's `peerUrls` | yes, bodies up to 64 KiB (set `maxPatchPayloadBytes(64 * 1024)`) | fire-and-forget, no retry, no ordering | lost; reconciliation repairs |
| `DbPollingSyncStrategy` | up to `pollInterval` (default 5s) | JDBC `DataSource`, one table | **no** (requests are dropped; follower `refreshAndSync` is a no-op) | **no** (`supportsPatches()` false; a leader patch writes no row, followers converge via reconciliation or the next full refresh) | last-write-wins per catalog row; intermediate states skipped | self-correcting: next poll sees current row |

Rules of thumb: Kafka already in the stack -> Kafka. Few static nodes, no broker -> HTTP (on a private network). Only a shared DB, tolerate seconds of lag and no follower-triggered refresh -> DB polling. Single node -> none.

## andersoni-sync-kafka

```xml
<dependency>
  <groupId>io.github.waabox</groupId>
  <artifactId>andersoni-sync-kafka</artifactId>
  <version>1.12.2</version>
</dependency>
```
Runtime: brings `org.apache.kafka:kafka-clients:3.9.0` (compile scope). No Spring.

`org.waabox.andersoni.sync.kafka.KafkaSyncConfig` (immutable):

| Option | Builder method | Default | Notes |
|---|---|---|---|
| bootstrap servers | `bootstrapServers(String)` | none, required | NPE on `build()` if missing |
| topic | `topic(String)` | `"andersoni-sync"` | shared by all nodes of the app |
| consumer group prefix | `consumerGroupPrefix(String)` | `"andersoni-"` | group id = prefix + nodeId (or random UUID) |
| producer acks | `acks(String)` | `"1"` | must be `"0"`, `"1"` or `"all"`, else `IllegalArgumentException` |
| node id | `nodeId(String)` | null -> random UUID group per start | set it (same as Andersoni `nodeId`) for a stable group |

Factories: `KafkaSyncConfig.create(bootstrapServers)`, `KafkaSyncConfig.create(bootstrapServers, topic, consumerGroupPrefix)`, `KafkaSyncConfig.builder()`.

Behavior: producer key = catalog name, value = JSON from `SyncMessageCodec` (1.13.0): a `RefreshEvent` (`catalogName`, `sourceNodeId`, `version`, `hash`, `timestamp`, `kind`) or a `PatchEvent` (`fromHash`, `toHash`, `operations`, ...), discriminated by a `type` field; messages without `type` decode as refresh events. Nodes on 1.12.2 or earlier cannot decode a `PatchEvent` (error logged per record, polling continues, node stays stale). Consumer: `auto.offset.reset=latest`, auto commit on, daemon thread `andersoni-kafka-sync-poll`, poll errors back off 1s and keep polling. `publish()` before `start()` throws `IllegalStateException`; send failures are logged asynchronously (not thrown).

```java
import org.waabox.andersoni.Andersoni;
import org.waabox.andersoni.sync.kafka.KafkaSyncConfig;
import org.waabox.andersoni.sync.kafka.KafkaSyncStrategy;

final String nodeId = System.getenv("HOSTNAME");
final KafkaSyncConfig kafkaConfig = KafkaSyncConfig.builder()
    .bootstrapServers("broker1:9092,broker2:9092")
    .topic("orders-andersoni-sync")
    .consumerGroupPrefix("orders-andersoni-")
    .acks("all")
    .nodeId(nodeId)
    .build();
final Andersoni andersoni = Andersoni.builder()
    .nodeId(nodeId)
    .syncStrategy(new KafkaSyncStrategy(kafkaConfig))
    .build();
```

Infra: create the topic (or rely on broker auto-create). Any partition count works (every node reads all partitions). Low retention is fine (events are tiny, only the latest matters). Each node creates its own consumer group: with random UUID groups (no `nodeId`) orphaned groups accumulate in the broker.

## andersoni-spring-sync-kafka

```xml
<dependency>
  <groupId>io.github.waabox</groupId>
  <artifactId>andersoni-spring-sync-kafka</artifactId>
  <version>1.12.2</version>
</dependency>
```
Runtime: `org.springframework.kafka:spring-kafka:3.3.2`; `spring-boot-autoconfigure` is optional (the Boot app provides it).
Summary only (details in `spring-boot.md`): `SpringKafkaSyncAutoConfiguration` activates when `andersoni.sync.kafka.bootstrap-servers` is set and no other `SyncStrategy` bean exists, and registers `SpringKafkaSyncStrategy` (uses `KafkaTemplate` + `@KafkaListener`). Properties under `andersoni.sync.kafka`: `bootstrap-servers`, `topic` (`andersoni-sync`), `consumer-group-prefix` (`andersoni-`), `acks` (`1`), `node-id`. Same wire format, group naming and offset policy as `andersoni-sync-kafka`, so both can share a topic. Use one Kafka module, not both.

## andersoni-sync-http

```xml
<dependency>
  <groupId>io.github.waabox</groupId>
  <artifactId>andersoni-sync-http</artifactId>
  <version>1.12.2</version>
</dependency>
```
Runtime: none beyond the JDK (`com.sun.net.httpserver.HttpServer` + `java.net.http.HttpClient`).

`org.waabox.andersoni.sync.http.HttpSyncConfig` (no builder):

| Factory | Meaning |
|---|---|
| `HttpSyncConfig.create(int port, List<String> peerUrls)` | path defaults to `"/andersoni/refresh"` |
| `HttpSyncConfig.create(int port, List<String> peerUrls, String path)` | custom path |

Behavior:
- Peer discovery: **none**. `peerUrls` is a static list fixed at construction. Target URL = `peerUrl + path`, so give base URLs without trailing slash, e.g. `"http://node-b:8081"`.
- List the other nodes (including self is harmless: self events are ignored). For `REQUEST` propagation, the leader must be reachable in every follower's list, so use a full mesh.
- Exposes `POST <path>` on `0.0.0.0:<port>` (all interfaces). Responses: 200 ok, 405 non-POST, 413 body > 64 KiB, 500 on parse/handler failure. Patch events (1.13.0) POST to the same path, so a patch whose JSON exceeds 64 KiB is refused by every peer with 413 and only logged on the leader; the default `maxPatchPayloadBytes` (256 KB) does not protect against this, set it to `64 * 1024` or less with HTTP sync. Peers on 1.12.2 or earlier answer 500 to a patch and stay stale.
- `publish()` sends async to every peer; non-200 or errors are only logged (no retry). `publish()` before `start()` throws `IllegalStateException`; a bind failure in `start()` throws `IllegalStateException`.

Security: the endpoint is **unauthenticated and plain HTTP**. Anyone reaching it can make the leader run refreshes (`REQUEST`) or make nodes reload. Keep the port off public ingress (private network / K8s `NetworkPolicy`), and do not reuse the app's public port (it is a separate JDK server).

```java
import java.util.List;
import org.waabox.andersoni.Andersoni;
import org.waabox.andersoni.sync.http.HttpSyncConfig;
import org.waabox.andersoni.sync.http.HttpSyncStrategy;

final HttpSyncConfig httpConfig = HttpSyncConfig.create(8081,
    List.of("http://node-b:8081", "http://node-c:8081"));
final Andersoni andersoni = Andersoni.builder()
    .nodeId("node-a")
    .syncStrategy(new HttpSyncStrategy(httpConfig))
    .build();
```

## andersoni-sync-db

```xml
<dependency>
  <groupId>io.github.waabox</groupId>
  <artifactId>andersoni-sync-db</artifactId>
  <version>1.12.2</version>
</dependency>
```
Runtime: you provide a `javax.sql.DataSource` and the JDBC driver (no driver is shipped; H2 is test scope only).

`org.waabox.andersoni.sync.db.DbPollingSyncConfig` (no builder):

| Factory | Defaults |
|---|---|
| `DbPollingSyncConfig.create(DataSource dataSource)` | table `andersoni_sync_log`, poll every 5s |
| `DbPollingSyncConfig.create(DataSource, String tableName, Duration pollInterval)` | nulls -> NPE; blank or unsafe name -> `IllegalArgumentException` |

`tableName` must match `^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)?$` (optionally schema-qualified).

Schema: `start()` executes this (needs DDL privilege; otherwise pre-create the table). Failure -> `AndersoniException`.
```sql
CREATE TABLE IF NOT EXISTS andersoni_sync_log (
  catalog_name VARCHAR(255) NOT NULL,
  source_node_id VARCHAR(255) NOT NULL,
  version BIGINT NOT NULL,
  hash VARCHAR(255) NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  PRIMARY KEY (catalog_name)
)
```
Behavior:
- One row per catalog. `publish(EVENT)` = `UPDATE ... WHERE catalog_name = ?`, `INSERT` if 0 rows (retries the UPDATE on a concurrent insert clash). SQL failure -> `AndersoniException`.
- `publish(REQUEST)` is **ignored** (logged at debug): it is a state channel, not a command channel. With DB polling a follower's `refreshAndSync` does nothing; trigger refreshes on the leader (or rely on `refreshEvery` / reconciliation).
- Patches (1.13.0) are not carried (`supportsPatches()` is `false`): a leader `replaceAndSync` applies locally and saves to the snapshot store, but publishes nothing, not even a refresh row. Followers converge on the next reconciliation pass (store configured) or the next full `refreshAndSync` / `refreshEvery` refresh; without a store they stay stale until then.
- Daemon thread `andersoni-db-poll` runs `SELECT` over the whole table every `pollInterval` (first poll after one interval) and emits an event when a row's hash changed since the last poll. The first poll after start emits every row once (filtered by hash in core).
- Poll errors are logged, polling continues.

```java
import javax.sql.DataSource;
import java.time.Duration;
import org.waabox.andersoni.Andersoni;
import org.waabox.andersoni.sync.db.DbPollingSyncConfig;
import org.waabox.andersoni.sync.db.DbPollingSyncStrategy;

final DataSource dataSource = createDataSource(); // e.g. HikariCP
final DbPollingSyncConfig dbConfig = DbPollingSyncConfig.create(
    dataSource, "cache.andersoni_sync_log", Duration.ofSeconds(2));
final Andersoni andersoni = Andersoni.builder()
    .syncStrategy(new DbPollingSyncStrategy(dbConfig))
    .build();
```

## andersoni-leader-k8s

```xml
<dependency>
  <groupId>io.github.waabox</groupId>
  <artifactId>andersoni-leader-k8s</artifactId>
  <version>1.12.2</version>
</dependency>
```
Runtime: brings `io.kubernetes:client-java:21.0.2` and `io.kubernetes:client-java-extended:21.0.2` (official Kubernetes Java client, not fabric8). Credentials via `Config.defaultClient()`: in-cluster service account, else `KUBECONFIG` / `~/.kube/config`.

`org.waabox.andersoni.leader.k8s.K8sLeaseConfig` (no builder):

| Factory | Values |
|---|---|
| `K8sLeaseConfig.create(String leaseName, String identity)` | namespace `"default"`, renewal 15s, lease 30s |
| `K8sLeaseConfig.create(String leaseName, String leaseNamespace, String identity, Duration renewalInterval, Duration leaseDuration)` | all explicit |

Validation (`IllegalArgumentException`): both durations positive, and `renewalInterval < leaseDuration * 2/3` (renew deadline). Nulls -> NPE.

Behavior: `K8sLeaseLeaderElection.start()` runs the elector on daemon thread `k8s-leader-election` and blocks up to `renewalInterval` for the first result; if unresolved it proceeds as follower. Setup failure -> `AndersoniException`. If the elector thread dies, leadership is relinquished (no split brain). On promotion Andersoni runs an authoritative refresh of every catalog.

Use the same value for `identity` and Andersoni `nodeId` (the pod name, `HOSTNAME`); `andersoni-admin` marks the leader by `podName == lease holderIdentity`.

```java
import java.time.Duration;
import org.waabox.andersoni.Andersoni;
import org.waabox.andersoni.leader.k8s.K8sLeaseConfig;
import org.waabox.andersoni.leader.k8s.K8sLeaseLeaderElection;

final String podName = System.getenv("HOSTNAME");
final K8sLeaseConfig leaseConfig = K8sLeaseConfig.create(
    "orders-andersoni-leader", "orders", podName, Duration.ofSeconds(10), Duration.ofSeconds(30));
final Andersoni andersoni = Andersoni.builder()
    .nodeId(podName)
    .leaderElection(new K8sLeaseLeaderElection(leaseConfig))
    .build();
```

RBAC (the Lease is created by the client if absent; one unique lease name per app):
```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata: { name: andersoni-leader-election, namespace: orders }
rules:
  - apiGroups: ["coordination.k8s.io"]
    resources: ["leases"]
    verbs: ["get", "watch", "list", "create", "patch", "update"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata: { name: andersoni-leader-election, namespace: orders }
roleRef: { apiGroup: rbac.authorization.k8s.io, kind: Role, name: andersoni-leader-election }
subjects:
  - { kind: ServiceAccount, name: orders, namespace: orders }
```

## andersoni-snapshot-s3

```xml
<dependency>
  <groupId>io.github.waabox</groupId>
  <artifactId>andersoni-snapshot-s3</artifactId>
  <version>1.12.2</version>
</dependency>
```
Runtime: brings `software.amazon.awssdk:s3:2.29.51` and `software.amazon.awssdk:sts:2.29.51`. Region type: `software.amazon.awssdk.regions.Region`.

`org.waabox.andersoni.snapshot.s3.S3SnapshotConfig.builder()`:

| Method | Default | Notes |
|---|---|---|
| `bucket(String)` | required | NPE on `build()` if null; bucket must already exist |
| `region(Region)` | required | NPE on `build()` if null |
| `prefix(String)` | `"andersoni/"` | key = `prefix + catalogName + "/snapshot.dat"` |
| `s3Client(S3Client)` | none | if set, used as-is (endpoint override, MinIO...) and **not** closed by the store; all STS options ignored |
| `roleArn(String)` | none | enables STS; without it the SDK default credentials chain is used |
| `sessionName(String)` | `"andersoni-snapshot"` | STS role session name |
| `webIdentityTokenFile(Path)` | none | with `roleArn`: `AssumeRoleWithWebIdentity` (EKS IRSA), token file re-read on refresh |
| `externalId(String)` | none | AssumeRole only |
| `durationSeconds(int)` | STS default | AssumeRole only (ignored in web-identity mode) |

Credential precedence: `s3Client` > `roleArn` (+ token file -> web identity, else AssumeRole) > SDK default chain.

Behavior: `save` = `PutObject` with user metadata `hash`, `version`, `created-at`, `catalog-name`. `load` verifies SHA-256 of the bytes against the `hash` metadata; mismatch -> logged warning and `Optional.empty()` (caller falls back to the `DataLoader`). `describe` = `HeadObject` (cheap, used by reconciliation). Missing key -> `Optional.empty()`. Catalog names must match `^[A-Za-z0-9._-]+$` (else `IllegalArgumentException`). Read I/O errors -> `UncheckedIOException`.

IAM for the prefix: `s3:PutObject`, `s3:GetObject` on `arn:aws:s3:::<bucket>/<prefix>*`. Grant `s3:ListBucket` on the bucket too, otherwise S3 answers 403 instead of 404 for a missing snapshot (not treated as "absent" by `describe`). With `roleArn`: `sts:AssumeRole` or `sts:AssumeRoleWithWebIdentity` on that role.

```java
import software.amazon.awssdk.regions.Region;
import org.waabox.andersoni.Andersoni;
import org.waabox.andersoni.snapshot.s3.S3SnapshotConfig;
import org.waabox.andersoni.snapshot.s3.S3SnapshotStore;

final S3SnapshotConfig s3Config = S3SnapshotConfig.builder()
    .bucket("orders-snapshots")
    .region(Region.US_EAST_1)
    .prefix("prod/")
    .build();
final S3SnapshotStore store = new S3SnapshotStore(s3Config); // close() on shutdown
final Andersoni andersoni = Andersoni.builder()
    .snapshotStore(store)
    .build();
```
In Spring, a `S3SnapshotStore` bean is closed automatically (inferred `close()`).

## andersoni-snapshot-fs

```xml
<dependency>
  <groupId>io.github.waabox</groupId>
  <artifactId>andersoni-snapshot-fs</artifactId>
  <version>1.12.2</version>
</dependency>
```
Runtime: none. Dev/test, or nodes sharing one volume.
`new FileSystemSnapshotStore(Path baseDir)` creates `baseDir` (failure -> `UncheckedIOException`). File per catalog: `<baseDir>/<catalogName>/snapshot.bin` (text header `hash=`, `version=`, `createdAt=` + blank line + data), written to a temp file and atomically moved. Still reads the legacy `snapshot.dat` + `snapshot.meta` pair. Same catalog-name rule as S3. Not `AutoCloseable`.

```java
import java.nio.file.Paths;
import org.waabox.andersoni.Andersoni;
import org.waabox.andersoni.snapshot.fs.FileSystemSnapshotStore;

final Andersoni andersoni = Andersoni.builder()
    .snapshotStore(new FileSystemSnapshotStore(Paths.get("/var/lib/andersoni")))
    .build();
```

## andersoni-json-serializer

```xml
<dependency>
  <groupId>io.github.waabox</groupId>
  <artifactId>andersoni-json-serializer</artifactId>
  <version>1.12.2</version>
</dependency>
```
Runtime: brings `jackson-databind:2.18.2` and `jackson-datatype-jsr310:2.18.2`.

`org.waabox.andersoni.snapshot.json.JacksonSnapshotSerializer<T>` (public, non-final class, implements `SnapshotSerializer<T>`):

| Constructor | Mapper |
|---|---|
| `new JacksonSnapshotSerializer<>(TypeReference<List<T>>)` | default: `JavaTimeModule`, `ORDER_MAP_ENTRIES_BY_KEYS=true`, `SORT_PROPERTIES_ALPHABETICALLY=true` |
| `new JacksonSnapshotSerializer<>(ObjectMapper, TypeReference<List<T>>)` | yours; you must keep it deterministic |

Wire it per catalog (the store is only used for catalogs with a serializer):
```java
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;
import org.waabox.andersoni.Catalog;
import org.waabox.andersoni.snapshot.json.JacksonSnapshotSerializer;

public record Product(String sku, String category, java.time.Instant updatedAt) { }

final Catalog<Product> catalog = Catalog.of(Product.class)
    .named("products")
    .loadWith(productRepository::findAllOrderedBySku)
    .serializer(new JacksonSnapshotSerializer<>(new TypeReference<List<Product>>() { }))
    .index("by-category").by(Product::category, java.util.function.Function.identity())
    .build();
```
Requirements on `T`:
- Jackson must round-trip it: records (supported natively) or classes with a Jackson-usable constructor/setters. `java.time` types are covered by the default mapper; anything else (e.g. `Jdk8Module`, custom modules) needs the two-arg constructor with your mapper.
- Determinism (the serialized bytes are the catalog hash): the default mapper sorts properties and map keys, but **not `Set` contents** and not list order. Use sorted collections, and a `DataLoader` that returns items in a stable order (SQL `ORDER BY`). A nondeterministic serializer causes redundant reloads, reconciliation drift and S3 hash warnings.
- `deserialize` returns an unmodifiable list; JSON `null` or parse errors -> `UncheckedIOException`.

## andersoni-metrics-datadog

```xml
<dependency>
  <groupId>io.github.waabox</groupId>
  <artifactId>andersoni-metrics-datadog</artifactId>
  <version>1.12.2</version>
</dependency>
```
Runtime: brings `com.datadoghq:java-dogstatsd-client:4.4.5`. Needs a DogStatsD agent (UDP).

`org.waabox.andersoni.metrics.datadog.DatadogMetricsConfig.builder()`:

| Method | Default | Notes |
|---|---|---|
| `host(String)` | null | null -> client resolves `DD_DOGSTATSD_URL` / `DD_AGENT_HOST`, else `localhost` |
| `port(int)` | `8125` (`DEFAULT_PORT`) | 0..65535 |
| `prefix(String)` | `"andersoni"` (`DEFAULT_PREFIX`) | metric names become `andersoni.<name>` |
| `constantTags(String...)` | none | e.g. `"env:prod"`, `"service:orders"` |
| `pollingInterval(Duration)` | 30s (`DEFAULT_POLLING_INTERVAL`) | gauge reporting period, must be positive |

Factories on `DatadogAndersoniMetrics`: `create()` (all defaults), `create(DatadogMetricsConfig)` (owns and closes the client on `stop()`), `create(StatsDClient)` (your client, not closed, 30s polling, no prefix applied by Andersoni).

```java
import java.time.Duration;
import org.waabox.andersoni.Andersoni;
import org.waabox.andersoni.metrics.datadog.DatadogAndersoniMetrics;
import org.waabox.andersoni.metrics.datadog.DatadogMetricsConfig;

final DatadogMetricsConfig ddConfig = DatadogMetricsConfig.builder()
    .constantTags("env:prod", "service:orders")
    .pollingInterval(Duration.ofSeconds(15))
    .build();
final Andersoni andersoni = Andersoni.builder()
    .metrics(DatadogAndersoniMetrics.create(ddConfig))
    .build();
```

Metrics (names before prefix; every metric also carries `node:<nodeId>` once started):

| Metric | Type | Tags | Emitted when |
|---|---|---|---|
| `catalog.snapshot.loaded` | count | `catalog`, `source` (`snapshotStore`, `dataLoader`, `followerDataLoaderFallback`) | a snapshot is loaded |
| `catalog.refresh.failed` | count | `catalog` | refresh failed |
| `sync.published` | count | `catalog` | leader published an `EVENT` |
| `sync.requested` | count | `catalog` | follower published a `REQUEST` |
| `sync.received` | count | `catalog` | accepted an incoming `EVENT` |
| `sync.publish.failed` | count | `catalog` | publish threw |
| `sync.receive.failed` | count | - | declared; not emitted by core 1.12.2 |
| `reconcile.drift_detected` | count | `catalog` | reconciler found drift |
| `reconcile.drift_repaired` | count | `catalog` | drift repaired |
| `reconcile.failed` | count | `catalog` | reconciliation pass failed |
| (none) | - | - | the 1.13.0 patch callbacks (`patchApplied`, `patchDiscarded`, `patchFellBack`, `patchOversized`) are not overridden by this module, so no patch metric is emitted; `sync.published`/`sync.received`/`sync.publish.failed` still count patch traffic |
| `index.memory.bytes` | gauge | `catalog`, `index` | after refresh/bootstrap and every poll |
| `catalog.items` | gauge | `catalog` | every poll |
| `catalog.memory.bytes` | gauge | `catalog` | every poll |
| `catalog.version` | gauge | `catalog` | every poll |
| `catalog.in_sync` | gauge (1/0) | `catalog` | every poll; 0 after drift detected until repaired |
| `index.keys` | gauge | `catalog`, `index` | every poll (unique keys) |

Emission failures are logged and swallowed. Poll thread: `andersoni-metrics-datadog-<nodeId>`.

## andersoni-admin

Standalone Spring Boot web app (Vue + Tailwind dashboard) that monitors Andersoni pods in a Kubernetes namespace. **Not published to Maven Central** (`maven.deploy.skip=true`): build from the repo (`mvn clean package` in `andersoni-admin`, `Dockerfile` provided) and deploy as its own pod, or run locally with a kubeconfig.
- Listens on port 9090. API: `GET /api/cluster` -> namespace, `leaderIdentity`, and per pod `podName`, `podIp`, `phase`, `ready`, `leader`, `info`, `status`.
- Reads the Lease holder, lists pods by label, and fetches each pod's info path through the API-server pod proxy on port **8080** (hard-coded). Your app must expose a JSON info endpoint on 8080 (e.g. returning `andersoni.status()` / `info(...)`).
- Env: `ADMIN_NAMESPACE` (`andersoni-example`), `ADMIN_LABEL_SELECTOR` (`app=andersoni-example`), `ADMIN_LEASE_NAME` (`andersoni-example-leader`), `ADMIN_INFO_PATH` (`/events/info`).
- RBAC: `pods` list, `pods/proxy` get (core), `leases` get (`coordination.k8s.io`).

## Recommended production topologies

| Deployment | Sync | Leader | Store | Serializer | Metrics |
|---|---|---|---|---|---|
| Single node / local dev | none | default `SingleNodeLeaderElection` | none, or `FileSystemSnapshotStore` for fast restarts | JSON if a store is used | optional |
| Kubernetes, Kafka available | `KafkaSyncStrategy` (or Spring Kafka) with `nodeId` = pod name | `K8sLeaseLeaderElection`, identity = pod name | `S3SnapshotStore` (IRSA via `roleArn` + `webIdentityTokenFile`, or default chain) | `JacksonSnapshotSerializer` on every catalog | `DatadogAndersoniMetrics` |
| Kubernetes, no broker | `DbPollingSyncStrategy` on the app DB (accept poll lag, no follower `refreshAndSync`) | `K8sLeaseLeaderElection` | `S3SnapshotStore` | JSON | Datadog |
| Fixed VMs, few nodes | `HttpSyncStrategy`, full mesh on a private port | custom `LeaderElectionStrategy` (no non-K8s module ships) | S3, or FS on a shared volume | JSON | Datadog |

Full K8s wiring (imports as in the module sections above):
```java
final String podName = System.getenv("HOSTNAME");
final S3SnapshotStore store = new S3SnapshotStore(S3SnapshotConfig.builder()
    .bucket("orders-snapshots").region(Region.US_EAST_1).prefix("prod/").build());
final Andersoni andersoni = Andersoni.builder()
    .nodeId(podName)
    .syncStrategy(new KafkaSyncStrategy(KafkaSyncConfig.builder()
        .bootstrapServers(System.getenv("KAFKA_BOOTSTRAP_SERVERS")).topic("orders-andersoni-sync").nodeId(podName).build()))
    .leaderElection(new K8sLeaseLeaderElection(K8sLeaseConfig.create("orders-andersoni-leader", "orders", podName,
        Duration.ofSeconds(10), Duration.ofSeconds(30))))
    .snapshotStore(store)
    .metrics(DatadogAndersoniMetrics.create(DatadogMetricsConfig.builder().constantTags("service:orders").build()))
    .build();
andersoni.register(catalog); // catalog built with .serializer(new JacksonSnapshotSerializer<>(...))
andersoni.start();
// shutdown: andersoni.stop(); store.close();
```
Checklist: unique `nodeId` per pod; every catalog that should use S3 has a deterministic serializer and an ordered `DataLoader`; topic + bucket exist; Lease RBAC bound to the pod's ServiceAccount; the leader is the only node that refreshes from the source, followers load from S3 on each `EVENT` and during reconciliation.
