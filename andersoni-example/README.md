# Andersoni Example - Full Stack Demo

This is a complete Spring Boot application demonstrating all Andersoni features in a production-like environment. The application deploys as 3 pods in Kubernetes, showcasing distributed caching, leader election, Kafka-based synchronization, and S3 snapshot persistence.

## What This Demonstrates

- **Multi-index search**: Search events by sport, venue, or status using `by-sport`, `by-venue`, and `by-status` indexes
- **Kafka sync across nodes**: Real-time cache synchronization using `andersoni-sync-kafka`
- **K8s Lease-based leader election**: Automatic leader election using `andersoni-leader-k8s`
- **S3 snapshot persistence**: Cache snapshots stored in MinIO via `andersoni-snapshot-s3`
- **Spring Boot auto-configuration**: Zero-boilerplate setup using `andersoni-spring-boot-starter`

## Architecture

```
                    ┌──────────────┐
                    │  PostgreSQL  │  (Source of truth)
                    └──────┬───────┘
                           │ DataLoader.load()
              ┌────────────┼────────────┐
              ▼            ▼            ▼
         ┌────────┐  ┌────────┐  ┌────────┐
         │ Pod 1  │  │ Pod 2  │  │ Pod 3  │
         │(Leader)│  │        │  │        │
         └───┬────┘  └───┬────┘  └───┬────┘
             │            │            │
             └─────┬──────┴──────┬─────┘
                   │             │
             ┌─────▼─────┐ ┌────▼─────┐
             │   Kafka   │ │  MinIO   │
             │  (Sync)   │ │(Snapshot)│
             └───────────┘ └──────────┘
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

## Configuration

All configuration is provided via environment variables with sensible defaults. The application uses Spring Boot's external configuration mechanism.

### Key Configuration Properties

```yaml
# Cache configuration
andersoni:
  cache-name: events-cache
  snapshot:
    enabled: true
    storage: s3
    s3:
      bucket: andersoni-snapshots
      endpoint: http://minio:9000
      access-key: minioadmin
      secret-key: minioadmin
  sync:
    enabled: true
    type: kafka
    kafka:
      bootstrap-servers: kafka:9092
      topic: andersoni-events
  leader:
    enabled: true
    type: k8s
    k8s:
      namespace: andersoni-example
      lease-name: andersoni-leader

# Database
spring:
  datasource:
    url: jdbc:postgresql://postgres:5432/events
    username: andersoni
    password: andersoni

# Kafka
spring:
  kafka:
    bootstrap-servers: kafka:9092
```

### Environment Variables

Override any property using environment variables with the `ANDERSONI_` prefix:

```bash
ANDERSONI_CACHE_NAME=events-cache
ANDERSONI_SNAPSHOT_ENABLED=true
ANDERSONI_SNAPSHOT_STORAGE=s3
ANDERSONI_SNAPSHOT_S3_BUCKET=andersoni-snapshots
ANDERSONI_SNAPSHOT_S3_ENDPOINT=http://minio:9000
ANDERSONI_SNAPSHOT_S3_ACCESSKEY=minioadmin
ANDERSONI_SNAPSHOT_S3_SECRETKEY=minioadmin
ANDERSONI_SYNC_ENABLED=true
ANDERSONI_SYNC_TYPE=kafka
ANDERSONI_SYNC_KAFKA_BOOTSTRAPSERVERS=kafka:9092
ANDERSONI_SYNC_KAFKA_TOPIC=andersoni-events
ANDERSONI_LEADER_ENABLED=true
ANDERSONI_LEADER_TYPE=k8s
ANDERSONI_LEADER_K8S_NAMESPACE=andersoni-example
ANDERSONI_LEADER_K8S_LEASENAME=andersoni-leader
```

## Project Structure

```
andersoni-example/
├── src/main/java/org/waabox/andersoni/example/
│   ├── AndersoniExampleApplication.java    # Main entry point
│   ├── Event.java                           # Domain model
│   ├── EventDataLoader.java                 # Loads data from PostgreSQL
│   ├── EventService.java                    # Cache operations
│   └── EventController.java                 # REST endpoints
├── src/main/resources/
│   ├── application.yaml                     # Configuration
│   └── schema.sql                           # Database schema
├── devcontainer/
│   ├── docker-compose.yml                   # Local infrastructure
│   └── init-db.sql                         # Sample data
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
kubectl -n andersoni-example describe rolebinding andersoni-leader-binding
```

### Kafka sync not working

Check Kafka connectivity:
```bash
kubectl -n andersoni-example exec <pod-name> -- curl -v kafka:9092
```

### S3 snapshot failures

Verify MinIO is accessible:
```bash
kubectl -n andersoni-example exec <pod-name> -- curl -v http://minio:9000
```

## Next Steps

- Explore the source code to understand how Andersoni is configured
- Modify the `EventDataLoader` to load data from your own source
- Add custom indexes by implementing `IndexStrategy`
- Configure different snapshot storage (filesystem, custom S3)
- Integrate with your existing Spring Boot application

## License

This example is part of the Andersoni project and is licensed under the MIT License.
