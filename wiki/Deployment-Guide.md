# Deployment Guide

This guide walks through deploying a multi-node Andersoni application with Kafka sync, S3 snapshots, and K8s leader election.

## Deployment Topology

```
┌─────────────────────────────────────────────────────────┐
│                    Kubernetes Cluster                     │
│                                                          │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐                  │
│  │  Pod 1   │  │  Pod 2   │  │  Pod 3   │                │
│  │ (Leader) │  │(Follower)│  │(Follower)│                │
│  │          │  │          │  │          │                  │
│  │ Andersoni│  │ Andersoni│  │ Andersoni│                │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘                │
│       │              │              │                      │
│  ┌────▼──────────────▼──────────────▼────┐                │
│  │              Kafka Topic              │                │
│  │          (andersoni-sync)             │                │
│  └───────────────────────────────────────┘                │
│                                                          │
│  ┌───────────────────┐  ┌───────────────────┐            │
│  │    PostgreSQL      │  │   MinIO / S3       │           │
│  │   (DataLoader)     │  │  (Snapshots)       │           │
│  └───────────────────┘  └───────────────────┘            │
│                                                          │
│  ┌───────────────────┐                                    │
│  │  K8s Lease API     │                                   │
│  │ (Leader Election)  │                                   │
│  └───────────────────┘                                    │
└─────────────────────────────────────────────────────────┘
```

## Step 1: Dependencies

```xml
<!-- Core -->
<dependency>
  <groupId>io.github.waabox</groupId>
  <artifactId>andersoni-spring-boot-starter</artifactId>
  <version>1.1.2</version>
</dependency>

<!-- Kafka Sync -->
<dependency>
  <groupId>io.github.waabox</groupId>
  <artifactId>andersoni-sync-kafka</artifactId>
  <version>1.1.2</version>
</dependency>

<!-- S3 Snapshots -->
<dependency>
  <groupId>io.github.waabox</groupId>
  <artifactId>andersoni-snapshot-s3</artifactId>
  <version>1.1.2</version>
</dependency>

<!-- K8s Leader Election -->
<dependency>
  <groupId>io.github.waabox</groupId>
  <artifactId>andersoni-leader-k8s</artifactId>
  <version>1.1.2</version>
</dependency>
```

## Step 2: Application Configuration

```yaml
# application.yaml
server:
  port: 8080

andersoni:
  node-id: ${HOSTNAME:}

k8s:
  lease:
    name: andersoni-leader
    namespace: ${K8S_NAMESPACE:default}

kafka:
  bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:kafka:9092}
  sync:
    topic: andersoni-sync
    consumer-group-prefix: andersoni-

s3:
  endpoint: ${S3_ENDPOINT:http://minio:9000}
  access-key: ${S3_ACCESS_KEY}
  secret-key: ${S3_SECRET_KEY}
  bucket: ${S3_BUCKET:andersoni-snapshots}
  region: ${S3_REGION:us-east-1}

spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST:postgres}:5432/${DB_NAME:mydb}
    username: ${DB_USER:postgres}
    password: ${DB_PASSWORD:postgres}
```

## Step 3: Java Configuration

```java
@Configuration
public class AndersoniConfig {

    // --- Leader Election ---

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

    // --- Kafka Sync ---

    @Bean
    KafkaSyncStrategy kafkaSyncStrategy(
            @Value("${kafka.bootstrap-servers}") final String servers,
            @Value("${kafka.sync.topic}") final String topic,
            @Value("${kafka.sync.consumer-group-prefix}") final String prefix) {

        final KafkaSyncConfig config = KafkaSyncConfig.create(
            servers, topic, prefix);
        return new KafkaSyncStrategy(config);
    }

    // --- S3 Snapshot Store ---

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

    // --- Catalog Registration ---

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

## Step 4: Kubernetes Manifests

### Namespace

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: my-app
```

### RBAC

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: my-app
  namespace: my-app
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: andersoni-leader-election
  namespace: my-app
rules:
  - apiGroups: ["coordination.k8s.io"]
    resources: ["leases"]
    verbs: ["get", "watch", "list", "create", "patch", "update"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: andersoni-leader-election-binding
  namespace: my-app
subjects:
  - kind: ServiceAccount
    name: my-app
    namespace: my-app
roleRef:
  kind: Role
  name: andersoni-leader-election
  apiGroup: rbac.authorization.k8s.io
```

### Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: my-app
  namespace: my-app
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
      serviceAccountName: my-app
      containers:
        - name: my-app
          image: my-app:latest
          ports:
            - containerPort: 8080
          env:
            - name: HOSTNAME
              valueFrom:
                fieldRef:
                  fieldPath: metadata.name
            - name: K8S_NAMESPACE
              valueFrom:
                fieldRef:
                  fieldPath: metadata.namespace
            - name: KAFKA_BOOTSTRAP_SERVERS
              value: "kafka.kafka:9092"
            - name: S3_ENDPOINT
              value: "http://minio.storage:9000"
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
            - name: S3_BUCKET
              value: "andersoni-snapshots"
            - name: DB_HOST
              value: "postgres.database"
            - name: DB_NAME
              value: "mydb"
            - name: DB_USER
              valueFrom:
                secretKeyRef:
                  name: db-credentials
                  key: username
            - name: DB_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: db-credentials
                  key: password
          readinessProbe:
            httpGet:
              path: /andersoni/health
              port: 8080
            initialDelaySeconds: 15
            periodSeconds: 5
          livenessProbe:
            httpGet:
              path: /andersoni/health
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

### Service

```yaml
apiVersion: v1
kind: Service
metadata:
  name: my-app
  namespace: my-app
spec:
  selector:
    app: my-app
  ports:
    - port: 8080
      targetPort: 8080
```

## Step 5: Deploy

```bash
# Create namespace and resources
kubectl apply -f namespace.yaml
kubectl apply -f rbac.yaml
kubectl apply -f service.yaml
kubectl apply -f deployment.yaml

# Verify
kubectl get pods -n my-app
kubectl logs -n my-app -l app=my-app --tail=50

# Check leader lease
kubectl get lease andersoni-leader -n my-app -o yaml
```

## Verification Checklist

After deployment, verify:

- [ ] All pods are Running and Ready
- [ ] One pod has acquired the Lease (check `kubectl get lease`)
- [ ] Catalog info endpoint returns data: `curl http://<service>:8080/events/info`
- [ ] Search works: `curl http://<service>:8080/events/search?index=by-sport&key=Soccer`
- [ ] Refresh propagates: trigger refresh on one pod, verify other pods have new data
- [ ] Leader failover: kill the leader pod, verify another pod acquires the Lease
