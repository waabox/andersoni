# FAQ & Troubleshooting

## Frequently Asked Questions

### Can I use Andersoni without Spring Boot?

Yes. The core module (`andersoni-core`) has zero framework dependencies. See [[Getting Started]] for a standalone example.

### Can I use Andersoni without Kubernetes?

Yes. K8s is only needed for `andersoni-leader-k8s`. For non-K8s deployments:
- Use `SingleNodeLeaderElection` (the default) for single-instance setups
- Use HTTP or DB Polling sync for multi-node without K8s

### Can any node trigger a refresh, or only the leader?

`refreshAndSync()` can be called from **any node**. It is not leader-restricted. This is useful for API-triggered refreshes. Scheduled refreshes (via `refreshEvery`) are leader-only.

### Does Andersoni support partial/incremental updates?

No. Every refresh is a full snapshot rebuild. The DataLoader must return the complete dataset. This is by design — it ensures snapshot-level consistency and simplifies the concurrency model.

### How does Andersoni handle null index keys?

If the key extraction function returns `null` (e.g., the intermediate object in a composed key is null), the item is not indexed under that key. It will not appear in search results for that index, but it is still present in the snapshot's `data()` list.

### Can I have multiple catalogs?

Yes. Register as many catalogs as you need. Each catalog is independent with its own DataLoader, indices, serializer, and refresh schedule.

### Is Andersoni thread-safe?

Yes. The immutable snapshot pattern (`AtomicReference<Snapshot<T>>`) guarantees that readers never see partial state. Concurrent reads during a refresh always see either the old or the new snapshot — never a mix.

### What happens if the DataLoader throws?

- **During bootstrap:** Andersoni retries according to the `RetryPolicy` (default: 3 retries, 2s backoff). After all retries, the catalog is marked FAILED. Other catalogs continue normally.
- **During refresh:** The error is logged, `metrics.refreshFailed()` is called, and the catalog retains its current snapshot. Searches continue to work with the previous data.

### What happens if the SnapshotStore is unavailable?

Andersoni falls back to the DataLoader. The SnapshotStore is an optimization for fast cold-start, not a requirement.

---

## Troubleshooting

### CatalogNotAvailableException on search

**Symptom:** `CatalogNotAvailableException` thrown when calling `andersoni.search()`.

**Cause:** The catalog failed to bootstrap after all retries.

**Solutions:**
1. Check logs for the root cause (DataLoader error, database unreachable, etc.)
2. Increase `RetryPolicy` retries and backoff if the data source is slow to start
3. Ensure the database/external service is reachable before `Andersoni.start()` is called
4. In Spring Boot, verify the datasource is configured and the database is up

### Followers not receiving refresh events

**Symptom:** After `refreshAndSync()` on the leader, other nodes still have stale data.

**Possible causes:**
1. **No SyncStrategy configured** — check that a sync bean exists
2. **Kafka topic doesn't exist** — enable auto-creation or create the topic manually
3. **Shared consumer group** — each node must have a unique consumer group (this is automatic with Kafka sync)
4. **Network connectivity** — verify pods can reach Kafka/peers

### Hash mismatch warnings (unreliable hash without serializer)

**Symptom:** Log messages like `Hash computed using toString() fallback`.

**Cause:** No `SnapshotSerializer` configured on the catalog. The hash is computed using `toString()` which may include object identity (memory addresses), making it unreliable.

**Solution:** Always provide a `SnapshotSerializer` in production:

```java
Catalog.of(Event.class)
    .named("events")
    .loadWith(loader)
    .serializer(new EventSnapshotSerializer())   // Add this
    .build();
```

### Slow bootstrap on followers

**Symptom:** Follower pods take a long time to become ready.

**Cause:** Followers retry the SnapshotStore 10 times waiting for the leader to upload a snapshot. If the leader is slow or the SnapshotStore is not configured, this adds delay.

**Solutions:**
1. Ensure the leader bootstraps quickly (fast DataLoader, fast database)
2. Configure a SnapshotStore so followers can restore from snapshot
3. If no SnapshotStore is needed, followers will fall back to the DataLoader after retries

### Leader election not working in K8s

**Symptom:** No pod acquires the Lease, or all pods think they are the leader.

**Checklist:**
1. ServiceAccount has the correct RBAC permissions (see [[DevOps & Kubernetes]])
2. Lease name and namespace match the configuration
3. Pod identity (`HOSTNAME`) is unique per pod
4. K8s API is reachable from the pods

```bash
# Check the Lease
kubectl get lease andersoni-leader -n my-namespace -o yaml

# Check RBAC
kubectl auth can-i get leases --as=system:serviceaccount:my-namespace:my-app -n my-namespace
```

### Out of memory

**Symptom:** Pod killed with OOMKilled status.

**Cause:** Dataset is larger than the pod's memory limit.

**Solutions:**
1. Check catalog info for memory estimates: `andersoni.info("events").totalEstimatedSizeMB()`
2. Increase pod memory limits (rule of thumb: 2x Andersoni memory + app baseline)
3. Reduce dataset size if possible (filter at the DataLoader level)
4. Monitor with `AndersoniMetrics.indexSizeReported()`

### Refresh events ignored (same hash)

**Symptom:** `refreshAndSync()` is called but other nodes don't refresh. Log shows "same hash" debug messages.

**Cause:** The data hasn't actually changed. Andersoni compares hashes and skips the refresh if the hash matches.

**This is expected behavior.** If you need to force a refresh regardless of the hash, the receiving node would need to call `refresh()` directly on its catalog.

### Spring Boot: multiple SyncStrategy beans

**Symptom:** `IllegalStateException: Found more than one SyncStrategy bean`.

**Cause:** Multiple sync strategy beans in the application context.

**Solution:** Configure exactly one sync strategy. If you have conditional beans (e.g., Kafka in prod, none in dev), use Spring profiles:

```java
@Bean
@Profile("prod")
KafkaSyncStrategy kafkaSyncStrategy(...) { ... }
```
