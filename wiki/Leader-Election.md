# Leader Election

Leader election determines which node is responsible for:
- Executing DataLoaders during bootstrap
- Running scheduled refreshes (`refreshEvery`)
- Uploading snapshots to the SnapshotStore

## Kubernetes Lease

Production-grade leader election using the Kubernetes Lease API with automatic failover.

### Dependency

```xml
<dependency>
  <groupId>io.github.waabox</groupId>
  <artifactId>andersoni-leader-k8s</artifactId>
  <version>1.1.2</version>
</dependency>
```

### Usage

```java
K8sLeaseConfig config = K8sLeaseConfig.create(
    "andersoni-leader",             // lease name
    "default",                      // namespace
    System.getenv("HOSTNAME"),      // identity (pod name)
    Duration.ofSeconds(15),         // renewal interval
    Duration.ofSeconds(30)          // lease duration
);

K8sLeaseLeaderElection leaderElection = new K8sLeaseLeaderElection(config);
```

With defaults (15s renewal, 30s lease duration):

```java
K8sLeaseConfig config = K8sLeaseConfig.create(
    "andersoni-leader",
    System.getenv("HOSTNAME")
);
```

### Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `leaseName` | *required* | Name of the Kubernetes Lease resource |
| `leaseNamespace` | `default` | Kubernetes namespace |
| `identity` | *required* | Unique identity for this node (usually pod name) |
| `renewalInterval` | 15 seconds | How often the leader renews its lease |
| `leaseDuration` | 30 seconds | How long the lease is valid |

### How It Works

1. On `start()`, a daemon thread begins competing for the Lease
2. The node that acquires the Lease becomes the leader
3. The leader renews the Lease every `renewalInterval`
4. If the leader fails to renew within `leaseDuration`, another node acquires the Lease
5. `LeaderChangeListener` callbacks fire on leadership transitions

### RBAC Requirements

The pod's ServiceAccount must have permissions to manage Leases. See [[DevOps & Kubernetes]] for the full RBAC setup.

### Failover Behavior

- **Renewal deadline**: 2/3 of lease duration (e.g., 20s for a 30s lease)
- **Retry period**: same as renewal interval
- If the leader pod is killed, another pod acquires the Lease within ~`leaseDuration`

---

## Single Node (Default)

The default strategy when no `LeaderElectionStrategy` is configured. The node is always the leader.

```java
// This is what Andersoni uses by default — no configuration needed
SingleNodeLeaderElection.INSTANCE
```

Use this for:
- Local development
- Single-instance deployments
- Testing

---

## Leader-Aware Behavior

### Bootstrap

During `Andersoni.start()`, leader status affects the bootstrap strategy:

| Scenario | Behavior |
|----------|----------|
| **Leader** | Load from SnapshotStore → fall back to DataLoader → save snapshot |
| **Follower** | Load from SnapshotStore → retry (waiting for leader) → if promoted, switch to DataLoader |

Followers retry the SnapshotStore 10 times to account for the leader's upload latency. If a follower is promoted to leader mid-bootstrap, it switches to the DataLoader path.

### Scheduled Refresh

Only the leader executes scheduled refreshes (`refreshEvery`). Non-leader nodes skip the scheduled task silently.

### On-Demand Refresh

`refreshAndSync()` can be called from **any node** — it is not leader-restricted. This is useful for API-triggered refreshes (e.g., a REST endpoint that triggers a data reload).
