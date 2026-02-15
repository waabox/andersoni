# andersoni-leader-k8s

Kubernetes Lease-based leader election for [Andersoni](../README.md). In a multi-node cluster, one pod becomes the leader responsible for scheduled catalog refreshes, snapshot publication, and sync event broadcasting. Automatic failover if the leader pod dies.

## How It Works

Uses the Kubernetes [Lease API](https://kubernetes.io/docs/concepts/architecture/leases/) to coordinate leadership. The leader periodically renews the lease. If it fails to renew (pod crash, network partition), another pod acquires the lease automatically.

```
Pod A: acquires Lease "andersoni-leader" in namespace "default"
  -> isLeader() = true
  -> runs scheduled refreshes, uploads snapshots, publishes events

Pod B, Pod C: attempt to acquire, lease held by Pod A
  -> isLeader() = false
  -> wait for sync events from leader

Pod A dies:
  -> lease expires after leaseDuration (30s)
  -> Pod B acquires lease
  -> Pod B becomes new leader
```

## Usage

### Standalone

```java
K8sLeaseConfig config = K8sLeaseConfig.create(
    "andersoni-leader",   // leaseName
    "my-pod-name"         // identity (typically HOSTNAME env var)
);

Andersoni andersoni = Andersoni.builder()
    .leaderElection(new K8sLeaseLeaderElection(config))
    .build();
```

### Spring Boot

```java
@Bean
LeaderElectionStrategy leaderElection(
    @Value("${HOSTNAME:unknown}") String podName) {
    return new K8sLeaseLeaderElection(
        K8sLeaseConfig.create("andersoni-leader", podName));
}
```

The starter auto-detects the `LeaderElectionStrategy` bean and wires it into Andersoni.

### Custom configuration

```java
K8sLeaseConfig config = K8sLeaseConfig.create(
    "andersoni-leader",         // leaseName
    "my-namespace",             // leaseNamespace
    "my-pod-name",              // identity
    Duration.ofSeconds(10),     // renewalInterval
    Duration.ofSeconds(20)      // leaseDuration
);
```

## Configuration

| Property | Default | Description |
|---|---|---|
| `leaseName` | *(required)* | Name of the Kubernetes Lease resource |
| `leaseNamespace` | `default` | Kubernetes namespace |
| `identity` | *(required)* | Unique identity for this node (typically pod name) |
| `renewalInterval` | `15s` | How often the leader renews the lease |
| `leaseDuration` | `30s` | How long the lease is valid before expiration |

## RBAC

The service account running the pods needs permissions to manage Lease resources:

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: andersoni-leader
rules:
  - apiGroups: ["coordination.k8s.io"]
    resources: ["leases"]
    verbs: ["get", "create", "update"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: andersoni-leader
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: andersoni-leader
subjects:
  - kind: ServiceAccount
    name: default
```

## Dependencies

| Dependency | Version |
|---|---|
| `andersoni-core` | — |
| `client-java` | 21.0.2 |
| `client-java-extended` | 21.0.2 |

## Maven

```xml
<dependency>
    <groupId>io.github.waabox</groupId>
    <artifactId>andersoni-leader-k8s</artifactId>
    <version>1.0.0</version>
</dependency>
```
