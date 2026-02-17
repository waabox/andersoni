# Andersoni Example - Full Stack Demo

This is a complete Spring Boot application demonstrating all Andersoni features in a production-like environment. The application deploys as 3 pods in Kubernetes, showcasing distributed caching, leader election, Kafka-based synchronization, and S3 snapshot persistence.

## What This Demonstrates

- **Multi-index search**: Search events by sport, venue, or status using `by-sport`, `by-venue`, and `by-status` indexes
- **Kafka sync across nodes**: Real-time cache synchronization using `andersoni-spring-sync-kafka` (auto-configured)
- **K8s Lease-based leader election**: Automatic leader election using `andersoni-leader-k8s`
- **S3 snapshot persistence**: Cache snapshots stored in MinIO via `andersoni-snapshot-s3`
- **Spring Boot auto-configuration**: Zero-boilerplate setup using `andersoni-spring-boot-starter`

## Architecture

```
                    ┌──────────────┐
                    │  PostgreSQL  │  (Source of truth)
                    └──────┬───────┘
                           │ DataLoader.load()
              ┌────────────┼─────────┐
              ▼            ▼         ▼
         ┌────────┐  ┌────────┐  ┌────────┐
         │ Pod 1  │  │ Pod 2  │  │ Pod 3  │
         │(Leader)│  │        │  │        │
         └───┬────┘  └───┬────┘  └───┬────┘
             │           │           │
             └────┬──────┴──────┬────┘
                  │             │
             ┌────▼─────┐ ┌─────▼────┐
             │  Kafka   │ │  MinIO   │
             │ (Sync)   │ │(Snapshot)│
             └──────────┘ └──────────┘
```

## Prerequisites

- Docker Desktop (or Docker + Docker Compose)
- VS Code with Dev Containers extension (recommended)
- Or: Docker Compose standalone

## Quick Start

```bash
# 1. Open in DevContainer (VS Code)
# Open the project in VS Code, then "Reopen in Container"

# 2. Or start infrastructure manually
cd andersoni-example/devcontainer
docker compose up -d

# 3. Build the project
mvn clean package -pl andersoni-example -am -DskipTests

# 4. Build Docker image
docker build -t andersoni-example andersoni-example/

# 5. Import image into K3s
docker save andersoni-example | k3s ctr images import -

# 6. Deploy to K8s
kubectl apply -f andersoni-example/k8s/namespace.yaml
kubectl apply -f andersoni-example/k8s/rbac.yaml
kubectl apply -f andersoni-example/k8s/deployment.yaml
kubectl apply -f andersoni-example/k8s/service.yaml

# 7. Verify pods
kubectl -n andersoni-example get pods
```

## Demo Walkthrough

### Search Events

```bash
# Search by sport
curl http://localhost:30080/events/search?index=by-sport&key=FOOTBALL

# Search by venue
curl http://localhost:30080/events/search?index=by-venue&key=Wembley

# Search by status
curl http://localhost:30080/events/search?index=by-status&key=LIVE
```

### Check Node Info

```bash
# See which pod is leader, snapshot version, etc.
for pod in $(kubectl -n andersoni-example get pods -o name); do
  echo "=== $pod ==="
  kubectl -n andersoni-example exec $pod -- curl -s localhost:8080/events/info
  echo
done
```

### Trigger Refresh & Observe Sync

```bash
# 1. Insert new event in PostgreSQL
docker compose -f andersoni-example/devcontainer/docker-compose.yml exec postgres \
  psql -U andersoni -d events -c \
  "INSERT INTO events VALUES ('new-1', 'New Match', 'FOOTBALL', 'Wembley', 'Arsenal', 'Chelsea', 'SCHEDULED', '2026-06-01 20:00:00')"

# 2. Trigger refresh on the leader
curl -X POST http://localhost:30080/events/refresh

# 3. Verify all pods have the new data (via Kafka sync)
curl http://localhost:30080/events/search?index=by-sport&key=FOOTBALL | jq length
```

### Leader Failover

```bash
# 1. Find the leader
for pod in $(kubectl -n andersoni-example get pods -o name); do
  echo "$pod: $(kubectl -n andersoni-example exec $pod -- curl -s localhost:8080/events/info)"
done

# 2. Kill the leader pod
kubectl -n andersoni-example delete pod <leader-pod-name>

# 3. Watch new leader elected (~30 seconds)
kubectl -n andersoni-example get pods -w

# 4. Verify new leader
for pod in $(kubectl -n andersoni-example get pods -o name); do
  echo "$pod: $(kubectl -n andersoni-example exec $pod -- curl -s localhost:8080/events/info)"
done
```

## REST API

| Method | Path | Description |
|--------|------|-------------|
| GET | `/events/search?index={name}&key={value}` | Search events by index |
| POST | `/events/refresh` | Trigger cache refresh + Kafka sync |
| GET | `/events/info` | Node ID, snapshot version, hash, item count |

## Configuration Reference

All configuration lives in `application.yaml` and can be overridden via environment variables. The infrastructure beans are wired in `AndersoniConfig.java` using Spring's `@Value` injection.

### Andersoni Core

| Property | Env Variable | Default | Description |
|----------|-------------|---------|-------------|
| `andersoni.node-id` | `HOSTNAME` | `local-dev` | Unique identifier for this node in the cluster. In Kubernetes, use the pod hostname so each replica is uniquely identified. This value is also used as the identity for leader election (K8s Lease holder identity). If not set, a random UUID is generated at startup. |

### Kubernetes Leader Election (`andersoni-leader-k8s`)

Only the leader node performs scheduled catalog refreshes, avoiding redundant database queries across replicas. Non-leader nodes receive refresh events via the sync strategy (Kafka, HTTP, or DB polling).

**Requirements:**
- A `ServiceAccount` with RBAC permissions to `get`, `create`, `update`, `patch`, `watch`, and `list` Lease objects in the `coordination.k8s.io` API group.
- The K8s Java client auto-detects in-cluster credentials, or falls back to `KUBECONFIG` env var / `~/.kube/config` for local development.

| Property | Env Variable | Default | Description |
|----------|-------------|---------|-------------|
| `k8s.lease.name` | - | `andersoni-example-leader` | Name of the Kubernetes Lease resource created in the target namespace. Each application should use a unique lease name to avoid conflicts with other services using Lease-based leader election. |
| `k8s.lease.namespace` | `K8S_NAMESPACE` | `andersoni-example` | Kubernetes namespace where the Lease resource is managed. Must match the namespace where the pods are deployed. The ServiceAccount must have RBAC permissions in this namespace. |
| `k8s.lease.renewal-interval-seconds` | `K8S_LEASE_RENEWAL_INTERVAL` | `15` | How often (in seconds) the current leader renews the lease. A shorter interval means faster failover detection but increases API server load. Must be strictly less than `lease-duration-seconds`. |
| `k8s.lease.lease-duration-seconds` | `K8S_LEASE_DURATION` | `30` | How long (in seconds) the lease is valid before it expires. If the leader fails to renew within this window, another node can acquire leadership. Recommended: at least 2x the renewal interval. |

**How leader election works:**

1. On startup, every node tries to acquire the Lease resource.
2. The first node to create (or claim) the Lease becomes the leader.
3. The leader renews the lease every `renewal-interval-seconds`.
4. If the leader pod crashes or is evicted, it stops renewing.
5. After `lease-duration-seconds` without renewal, another node acquires the lease.
6. Failover time = `lease-duration-seconds` (worst case).

**RBAC example** (already included in `k8s/rbac.yaml`):

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: andersoni-example
  namespace: andersoni-example
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: andersoni-leader-election
  namespace: andersoni-example
rules:
  - apiGroups: ["coordination.k8s.io"]
    resources: ["leases"]
    verbs: ["get", "watch", "list", "create", "patch", "update"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: andersoni-leader-election
  namespace: andersoni-example
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: andersoni-leader-election
subjects:
  - kind: ServiceAccount
    name: andersoni-example
    namespace: andersoni-example
```

**Tuning tips:**

| Scenario | `renewal-interval-seconds` | `lease-duration-seconds` | Tradeoff |
|----------|---------------------------|-------------------------|----------|
| Fast failover | 5 | 10 | More API server requests, ~10s failover |
| Balanced (default) | 15 | 30 | Moderate API load, ~30s failover |
| Low API load | 30 | 60 | Minimal API requests, ~60s failover |

### Kafka Sync Strategy (`andersoni-spring-sync-kafka`)

Auto-configured Spring Kafka-based synchronization. Uses `KafkaTemplate` for publishing and `@KafkaListener` for consuming. Spring manages the full Kafka listener lifecycle (no manual `start()`/`stop()` required). When the leader refreshes a catalog, it publishes an event to the configured topic. All other nodes consume the event and reload their local cache.

Uses a **broadcast pattern**: each node gets its own consumer group (`consumer-group-prefix` + random UUID), so every instance receives every message.

| Property | Env Variable | Default | Description |
|----------|-------------|---------|-------------|
| `andersoni.sync.kafka.bootstrap-servers` | `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka broker connection string. Comma-separated list of host:port pairs. Example: `broker1:9092,broker2:9092,broker3:9092`. |
| `andersoni.sync.kafka.topic` | `KAFKA_SYNC_TOPIC` | `andersoni-events` | Kafka topic where refresh events are published and consumed. All Andersoni nodes for this application must share the same topic. Use a unique topic per application to isolate sync traffic. |
| `andersoni.sync.kafka.consumer-group-prefix` | `KAFKA_CONSUMER_GROUP_PREFIX` | `andersoni-example-` | Prefix for generating unique consumer group IDs per node. Each node creates a consumer group as `<prefix><random-UUID>`. This ensures broadcast semantics (every node receives every message). |

### S3 Snapshot Store (`andersoni-snapshot-s3`)

Persists catalog snapshots to S3-compatible storage (AWS S3, MinIO, etc.). On startup, nodes restore their cache from the latest snapshot instead of hitting the database, enabling faster cold starts.

| Property | Env Variable | Default | Description |
|----------|-------------|---------|-------------|
| `s3.endpoint` | `S3_ENDPOINT` | `http://localhost:9000` | S3-compatible endpoint URL. For AWS S3, omit or leave empty (the SDK uses the default endpoint). For MinIO or other S3-compatible services, set the full URL. Example: `http://minio.storage.svc.cluster.local:9000`. |
| `s3.access-key` | `S3_ACCESS_KEY` | `minioadmin` | AWS access key for S3 authentication. In production K8s, prefer using IAM Roles for Service Accounts (IRSA) or Pod Identity instead of static credentials. |
| `s3.secret-key` | `S3_SECRET_KEY` | `minioadmin` | AWS secret key for S3 authentication. In production K8s, prefer using IAM Roles for Service Accounts (IRSA) or Pod Identity instead of static credentials. |
| `s3.bucket` | `S3_BUCKET` | `andersoni-snapshots` | S3 bucket where catalog snapshots are stored. The bucket must exist before the application starts. Each catalog stores its snapshot under: `<prefix><catalog-name>/snapshot`. |
| `s3.region` | `S3_REGION` | `us-east-1` | AWS region for the S3 client. Must match the region where the bucket is located. |
| `s3.prefix` | `S3_PREFIX` | `andersoni/` | Key prefix within the S3 bucket for snapshot files. Useful for organizing snapshots per environment or application. |

### Spring / JPA

| Property | Env Variable | Default | Description |
|----------|-------------|---------|-------------|
| `spring.datasource.url` | `POSTGRES_HOST` | `localhost:5432/events` | JDBC connection URL to PostgreSQL. |
| `spring.datasource.username` | `POSTGRES_USER` | `andersoni` | Database username. |
| `spring.datasource.password` | `POSTGRES_PASSWORD` | `andersoni` | Database password. |

### Full `application.yaml`

```yaml
server:
  port: 8080

spring:
  application:
    name: andersoni-example
  datasource:
    url: jdbc:postgresql://${POSTGRES_HOST:localhost}:5432/events
    username: ${POSTGRES_USER:andersoni}
    password: ${POSTGRES_PASSWORD:andersoni}
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect

# Andersoni core
andersoni:
  node-id: ${HOSTNAME:local-dev}                              # Unique node identity (pod hostname in K8s)

# Kubernetes Leader Election
k8s:
  lease:
    name: andersoni-example-leader                            # Lease resource name (unique per app)
    namespace: ${K8S_NAMESPACE:andersoni-example}             # K8s namespace for the Lease
    renewal-interval-seconds: ${K8S_LEASE_RENEWAL_INTERVAL:15} # Leader renewal frequency
    lease-duration-seconds: ${K8S_LEASE_DURATION:30}          # Lease TTL before expiry

# Kafka Sync Strategy (auto-configured by andersoni-spring-sync-kafka)
  sync:
    kafka:
      bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092} # Kafka broker(s)
      topic: ${KAFKA_SYNC_TOPIC:andersoni-events}                 # Sync events topic
      consumer-group-prefix: ${KAFKA_CONSUMER_GROUP_PREFIX:andersoni-example-} # Broadcast consumer group prefix

# S3 Snapshot Store
s3:
  endpoint: ${S3_ENDPOINT:http://localhost:9000}              # S3-compatible endpoint (MinIO for dev)
  access-key: ${S3_ACCESS_KEY:minioadmin}                     # S3 access key (use IRSA in prod)
  secret-key: ${S3_SECRET_KEY:minioadmin}                     # S3 secret key (use IRSA in prod)
  bucket: ${S3_BUCKET:andersoni-snapshots}                    # Snapshot bucket name
  region: ${S3_REGION:us-east-1}                              # AWS region
  prefix: ${S3_PREFIX:andersoni/}                             # Key prefix within bucket
```

### Environment Variables Summary

| Variable | Required | Default | Component |
|----------|----------|---------|-----------|
| `HOSTNAME` | No | `local-dev` | Core (auto-set by K8s) |
| `K8S_NAMESPACE` | Yes (in K8s) | `andersoni-example` | Leader Election |
| `K8S_LEASE_RENEWAL_INTERVAL` | No | `15` | Leader Election |
| `K8S_LEASE_DURATION` | No | `30` | Leader Election |
| `KAFKA_BOOTSTRAP_SERVERS` | Yes | `localhost:9092` | Kafka Sync |
| `KAFKA_SYNC_TOPIC` | No | `andersoni-events` | Kafka Sync |
| `KAFKA_CONSUMER_GROUP_PREFIX` | No | `andersoni-example-` | Kafka Sync |
| `S3_ENDPOINT` | Yes | `http://localhost:9000` | S3 Snapshots |
| `S3_ACCESS_KEY` | Yes | `minioadmin` | S3 Snapshots |
| `S3_SECRET_KEY` | Yes | `minioadmin` | S3 Snapshots |
| `S3_BUCKET` | No | `andersoni-snapshots` | S3 Snapshots |
| `S3_REGION` | No | `us-east-1` | S3 Snapshots |
| `S3_PREFIX` | No | `andersoni/` | S3 Snapshots |
| `POSTGRES_HOST` | Yes | `localhost` | Database |
| `POSTGRES_USER` | No | `andersoni` | Database |
| `POSTGRES_PASSWORD` | No | `andersoni` | Database |

### Alternative Sync Strategies

This example uses Kafka, but Andersoni supports two additional sync strategies. Replace `KafkaSyncStrategy` in `AndersoniConfig.java` with one of the following:

**HTTP Sync (`andersoni-sync-http`)** - Peer-to-peer push via HTTP:

| Config Field | Default | Description |
|-------------|---------|-------------|
| `port` | *(required)* | Port to listen on for incoming refresh events |
| `peerUrls` | *(required)* | URLs of all peer nodes to push events to |
| `path` | `/andersoni/refresh` | HTTP endpoint path for refresh events |

**DB Polling Sync (`andersoni-sync-db`)** - JDBC table polling:

| Config Field | Default | Description |
|-------------|---------|-------------|
| `dataSource` | *(required)* | JDBC DataSource to poll |
| `tableName` | `andersoni_sync_log` | Table used as the sync log |
| `pollInterval` | `5 seconds` | How often the table is polled for new events |

### Alternative Snapshot Store

This example uses S3, but Andersoni also provides:

**Filesystem Snapshot (`andersoni-snapshot-fs`)** - For local development and testing:

| Constructor Arg | Description |
|----------------|-------------|
| `baseDir` | Root directory where per-catalog subdirectories are created |

## Project Structure

```
andersoni-example/
├── src/main/java/org/waabox/andersoni/example/
│   ├── ExampleApplication.java              # Main entry point
│   ├── config/
│   │   └── AndersoniConfig.java             # Andersoni infrastructure beans
│   ├── domain/
│   │   ├── Event.java                       # Domain model (JPA entity)
│   │   ├── EventRepository.java             # Loads events from PostgreSQL
│   │   └── EventSnapshotSerializer.java     # Jackson snapshot serializer
│   └── application/
│       └── EventController.java             # REST endpoints
├── src/main/resources/
│   └── application.yaml                     # Configuration
├── devcontainer/
│   ├── docker-compose.yml                   # Local infrastructure
│   └── init-db.sql                          # Sample data
├── k8s/
│   ├── namespace.yaml                       # Kubernetes namespace
│   ├── rbac.yaml                            # Service account & roles
│   ├── deployment.yaml                      # 3-pod deployment
│   └── service.yaml                         # NodePort service
├── Dockerfile                               # Container image
└── pom.xml                                  # Maven dependencies
```

## Troubleshooting

### Pods not starting

Check pod logs:
```bash
kubectl -n andersoni-example logs -f <pod-name>
```

### Leader not elected

Verify RBAC permissions:
```bash
kubectl -n andersoni-example get rolebinding
kubectl -n andersoni-example describe rolebinding andersoni-leader-election
```

Check the Lease resource directly:
```bash
kubectl -n andersoni-example get lease andersoni-example-leader -o yaml
```

### Kafka sync not working

Check Kafka connectivity:
```bash
kubectl -n andersoni-example exec <pod-name> -- curl -v kafka:9092
```

Verify the topic exists:
```bash
kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic andersoni-events
```

### S3 snapshot failures

Verify MinIO is accessible:
```bash
kubectl -n andersoni-example exec <pod-name> -- curl -v http://minio:9000
```

Check the bucket exists:
```bash
aws --endpoint-url http://localhost:9000 s3 ls s3://andersoni-snapshots/
```

## License

This example is part of the Andersoni project and is licensed under the MIT License.
