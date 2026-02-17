# DevOps & Kubernetes

This page covers everything you need to deploy Andersoni in a Kubernetes cluster.

## Infrastructure Requirements

### Minimum Requirements

| Component | Required? | Purpose |
|-----------|-----------|---------|
| **Java 21+** | Yes | Runtime |
| **Kubernetes 1.19+** | For K8s features | Lease API for leader election |
| **Kafka** | Optional | Cross-node sync (recommended for production) |
| **S3-compatible storage** | Optional | Snapshot persistence (AWS S3, MinIO, etc.) |
| **PostgreSQL / RDBMS** | For DB sync | Alternative to Kafka sync |

### Without Kubernetes

Andersoni works without Kubernetes. If you don't use K8s:
- Use `SingleNodeLeaderElection` (default) for single-instance deployments
- Use HTTP or DB Polling sync for multi-node without K8s
- Use filesystem snapshots for local development

---

## RBAC Configuration

The pod's ServiceAccount needs permissions to manage **Leases** in the Coordination API group.

### ServiceAccount

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: andersoni-app
  namespace: my-namespace
```

### Role

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: andersoni-leader-election
  namespace: my-namespace
rules:
  - apiGroups: ["coordination.k8s.io"]
    resources: ["leases"]
    verbs: ["get", "watch", "list", "create", "patch", "update"]
```

### RoleBinding

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: andersoni-leader-election-binding
  namespace: my-namespace
subjects:
  - kind: ServiceAccount
    name: andersoni-app
    namespace: my-namespace
roleRef:
  kind: Role
  name: andersoni-leader-election
  apiGroup: rbac.authorization.k8s.io
```

> **Important:** Use a `Role` (namespaced), not a `ClusterRole`, to limit permissions to the deployment namespace.

---

## Deployment Configuration

### Deployment Manifest

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: my-app
  namespace: my-namespace
spec:
  replicas: 3
  selector:
    matchLabels:
      app: my-app
  template:
    metadata:
      labels:
        app: my-app
    spec:
      serviceAccountName: andersoni-app
      containers:
        - name: my-app
          image: my-app:latest
          ports:
            - containerPort: 8080
          env:
            # Andersoni node ID (use pod name)
            - name: HOSTNAME
              valueFrom:
                fieldRef:
                  fieldPath: metadata.name

            # K8s namespace (for leader election)
            - name: K8S_NAMESPACE
              valueFrom:
                fieldRef:
                  fieldPath: metadata.namespace

            # Kafka (if using Kafka sync)
            - name: KAFKA_BOOTSTRAP_SERVERS
              value: "kafka.kafka-namespace:9092"

            # S3 / MinIO (if using snapshot persistence)
            - name: S3_ENDPOINT
              value: "http://minio.storage-namespace:9000"
            - name: S3_ACCESS_KEY
              valueFrom:
                secretKeyRef:
                  name: s3-credentials
                  key: access-key
            - name: S3_SECRET_KEY
              valueFrom:
                secretKeyRef:
                  name: s3-credentials
                  key: secret-key

          # Health probes
          readinessProbe:
            httpGet:
              path: /events/info
              port: 8080
            initialDelaySeconds: 15
            periodSeconds: 5
          livenessProbe:
            httpGet:
              path: /events/info
              port: 8080
            initialDelaySeconds: 45
            periodSeconds: 10

          resources:
            requests:
              cpu: 250m
              memory: 512Mi
            limits:
              cpu: "1"
              memory: 1Gi
```

### Key Environment Variables

| Variable | Source | Purpose |
|----------|--------|---------|
| `HOSTNAME` | `metadata.name` | Andersoni node ID and K8s Lease identity |
| `K8S_NAMESPACE` | `metadata.namespace` | Lease namespace |
| `KAFKA_BOOTSTRAP_SERVERS` | ConfigMap/value | Kafka sync |
| `S3_ENDPOINT` | ConfigMap/value | Snapshot storage endpoint |
| `S3_ACCESS_KEY` | Secret | S3 credentials |
| `S3_SECRET_KEY` | Secret | S3 credentials |

---

## Health Probes

Andersoni does not provide built-in health endpoints, but you can expose the catalog info endpoint as a probe target:

```java
@GetMapping("/andersoni/health")
public ResponseEntity<?> health() {
    try {
        final CatalogInfo info = andersoni.info("events");
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "catalog", info.catalogName(),
            "items", info.itemCount(),
            "version", andersoni.catalogs().stream()
                .filter(c -> c.name().equals("events"))
                .findFirst()
                .map(c -> c.currentSnapshot().version())
                .orElse(0L)
        ));
    } catch (final CatalogNotAvailableException e) {
        return ResponseEntity.status(503).body(Map.of(
            "status", "DOWN",
            "reason", e.getMessage()
        ));
    }
}
```

### Probe Timing

| Probe | Initial Delay | Period | Rationale |
|-------|--------------|--------|-----------|
| **Readiness** | 15s | 5s | Wait for bootstrap to complete before receiving traffic |
| **Liveness** | 45s | 10s | Longer delay to avoid killing pods during slow bootstrap |

Adjust `initialDelaySeconds` based on your dataset size and DataLoader speed. Large datasets may need longer bootstrap times.

---

## Resource Planning

### Memory

Andersoni holds all data in memory. Plan your pod memory accordingly:

| Items | Indices | Estimated Memory |
|------:|--------:|----------------:|
| 10,000 | 3 | ~2 MB |
| 100,000 | 3 | ~14 MB |
| 500,000 | 3 | ~70 MB |

These estimates cover the index data structures only. Add the size of your actual domain objects.

**Rule of thumb:** set the memory limit to at least **2x** the estimated Andersoni memory usage plus your application's baseline.

### CPU

- Reads are extremely fast (~30 ns) and scale with CPU cores
- Writes (refresh) are single-threaded per catalog
- Leader election and sync have minimal CPU overhead

For most workloads, 250m-500m CPU request is sufficient.

---

## Kafka Setup

### Topic Requirements

| Setting | Recommended Value | Rationale |
|---------|-------------------|-----------|
| Topic name | `andersoni-sync` | Default, configurable |
| Partitions | 1 | Ordering not required, single partition is simplest |
| Replication factor | 3 | Production durability |
| Retention | 1 hour | Events are ephemeral; nodes catch up via snapshot |
| `cleanup.policy` | `delete` | No need for compaction |

### Consumer Group Pattern

Each Andersoni node creates a **unique consumer group** (`andersoni-{UUID}`). This is by design — it ensures broadcast semantics where every node receives every message. Do not configure a shared consumer group.

---

## S3 / MinIO Setup

### Bucket Requirements

- Create the bucket before deploying (Andersoni does not create buckets)
- Bucket policy: read/write access for the Andersoni service credentials
- No special lifecycle rules required — snapshots are overwritten on each refresh

### Key Layout

```
s3://my-bucket/
  └── andersoni/              # prefix (configurable)
      └── events/             # catalog name
          └── snapshot.dat    # serialized data + metadata
```

### MinIO Deployment (Development)

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: minio
spec:
  replicas: 1
  selector:
    matchLabels:
      app: minio
  template:
    metadata:
      labels:
        app: minio
    spec:
      containers:
        - name: minio
          image: minio/minio:latest
          args: ["server", "/data"]
          ports:
            - containerPort: 9000
          env:
            - name: MINIO_ROOT_USER
              value: minioadmin
            - name: MINIO_ROOT_PASSWORD
              value: minioadmin
---
apiVersion: v1
kind: Service
metadata:
  name: minio
spec:
  selector:
    app: minio
  ports:
    - port: 9000
      targetPort: 9000
```

---

## Networking

### Required Connectivity

| From | To | Port | Purpose |
|------|----|------|---------|
| App pods | Kafka | 9092 | Sync events |
| App pods | S3/MinIO | 9000/443 | Snapshot persistence |
| App pods | Database | 5432 | DataLoader (your app) |
| App pods | K8s API | 443 | Leader election (Lease API) |
| Admin console | App pods | 8080 | Proxied monitoring requests |

### Network Policies

If you use Kubernetes NetworkPolicies, ensure the app pods can reach:
- The Kafka service (if using Kafka sync)
- The S3/MinIO service (if using snapshot persistence)
- The Kubernetes API server (for leader election)

---

## Admin Console

The Andersoni Admin Console is a standalone monitoring dashboard for Kubernetes deployments.

### Deployment

```xml
<dependency>
  <groupId>io.github.waabox</groupId>
  <artifactId>andersoni-admin</artifactId>
  <version>1.1.2</version>
</dependency>
```

### Features

- Reads the Kubernetes Lease to identify the current leader
- Lists all pods matching a label selector
- Proxies HTTP requests to individual pods via the K8s API
- Vue.js + Tailwind CSS frontend on port 9090

### Admin RBAC

The admin console needs additional permissions:

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: andersoni-admin
rules:
  - apiGroups: ["coordination.k8s.io"]
    resources: ["leases"]
    verbs: ["get", "watch", "list"]
  - apiGroups: [""]
    resources: ["pods", "pods/proxy"]
    verbs: ["get", "list"]
```

---

## Secrets Management

Store sensitive values in Kubernetes Secrets:

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: s3-credentials
  namespace: my-namespace
type: Opaque
data:
  access-key: <base64-encoded>
  secret-key: <base64-encoded>
```

Reference in the deployment:

```yaml
env:
  - name: S3_ACCESS_KEY
    valueFrom:
      secretKeyRef:
        name: s3-credentials
        key: access-key
```

Never hardcode credentials in ConfigMaps, deployment manifests, or application.yaml.
