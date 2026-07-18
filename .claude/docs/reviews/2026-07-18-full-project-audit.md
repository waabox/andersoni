# Andersoni — Full-Project Audit (2026-07-18, v1.10.1)

End-to-end review of all 13 modules. Findings are grouped by severity.
Items marked **✓ verified** were confirmed by reading the code directly; the
rest come from focused per-domain reviews and are credible but should be
confirmed before fixing.

Method: five parallel domain reviews (core engine; sync strategies; leader
election + snapshot stores; Spring/admin/metrics; architecture/build/release),
then a verification pass on the highest-impact and most actionable findings.

---

## Regression introduced by the recent refresh-propagation fix

**Periodic REFRESH-REQUEST storm on followers** — `andersoni-core/.../Andersoni.java`
`schedulePeriodicRefreshes()` (~line 1053) **✓ verified**

The Javadoc claims "only if this node is the leader", but there is no
`leaderElection.isLeader()` check. The scheduled task calls
`refreshAndSync(name)`, which — after the v1.10.1 fix — publishes a
`RefreshKind.REQUEST` when the node is a follower. Before the fix this path was
a silent no-op (the guard added in commit `5449bf5`). Now every follower with a
`refreshEvery(...)` catalog sends the leader a REQUEST on each interval → a
cross-node request storm, exactly what `5449bf5` set out to prevent.

Fix: gate the scheduled task with `if (leaderElection.isLeader())` (or have the
scheduled path refresh only-if-leader without publishing a request). ~2 lines +
a test.

---

## P0 — Bugs / silent failures / security (fix now)

### P0-1 — Periodic REQUEST storm (core) ✓
See the regression section above.

### P0-2 — DB polling scheduler dies silently — `andersoni-sync-db/.../DbPollingSyncStrategy.java` `poll()` ~210-236 ✓
`poll()` catches only `SQLException`, but `rs.getTimestamp("updated_at").toInstant()`
(line 224) throws `NullPointerException` if the column is null (row written by
external/older tooling, or a manual insert). Because `poll` is scheduled via
`scheduleAtFixedRate`, a single thrown execution **suppresses all future runs**,
with no log. The node then silently stops syncing while appearing healthy.
Fix: wrap the whole body in `catch (Throwable)` (log + continue) and null-guard
`getTimestamp`.

### P0-3 — Unauthenticated SSRF into Kubernetes (admin) — `andersoni-admin/.../ClusterController.java`
No Spring Security is present. `GET /api/cluster` accepts caller-controlled
`namespace`, `labelSelector`, `leaseName`, `infoPath` and proxies them via
`connectGetNamespacedPodProxyWithPath(...)` using the admin ServiceAccount's
RBAC. An unauthenticated caller who can reach the service gets an SSRF /
lateral-movement primitive against pods, plus topology disclosure; raw K8s
error bodies are echoed back.
Fix: add authentication; pin/allowlist the params instead of taking them from
the request; scope SA RBAC to one namespace + a fixed proxy path; stop echoing
K8s error bodies.

### P0-4 — S3 web-identity passes file path instead of JWT — `andersoni-snapshot-s3/.../S3SnapshotStore.java:312` ✓
`.webIdentityToken(config.webIdentityTokenFile().orElseThrow().toString())`
passes the token **file path** where the OIDC **JWT contents** are required, and
bakes a static token into the request (no rotation). On EKS/IRSA the STS call is
rejected (`InvalidIdentityToken`) → S3 save/load fails at runtime.
Fix: use the SDK's `WebIdentityTokenFileCredentialsProvider` /
`StsWebIdentityTokenFileCredentialsProvider` (reads + refreshes the file).

### P0-5 — Leader `stop()` leaves split-brain window — `andersoni-leader-k8s/.../K8sLeaseLeaderElection.java` `stop()` ~194 ✓
`stop()` only interrupts the election thread; it never sets `leader = false` nor
calls `notifyListeners(false)`. After stop, `isLeader()` still returns `true`
and no listener learns of leadership loss. Related: if the election thread dies
(no `UncaughtExceptionHandler`, no supervision) while `leader == true`, the flag
is never reset → `isLeader()` fails **open** → two writers.
Fix: on stop and on any elector exit, force `leader=false` + notify; add
supervision/uncaught-exception handling.

### P0-6 — Non-deterministic content hash — `andersoni-json-serializer/.../JacksonSnapshotSerializer.java` `defaultMapper()` + `andersoni-core/.../Catalog.java` `computeHash` (~655-675)
Two causes defeat cross-node convergence, which is signalled purely by hash:
1. The default `ObjectMapper` does not set `SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS`
   (nor sort POJO properties) → any `Map`/`Set` field serializes in
   JVM/insertion order → identical data, different bytes, different SHA-256.
2. `computeHash` folds items in `data` **list order**; a loader that returns
   rows in nondeterministic order (unordered SQL, `HashSet`, parallel fetch)
   yields different hashes for identical logical content.
Effect: perpetual "hash differs → refresh" churn across nodes.
Fix: enable `ORDER_MAP_ENTRIES_BY_KEYS` (+ stable property order); sort
per-item hashes before folding, or contractually require a deterministic loader
order and document it. Add a determinism test.
(Works in the cluster IT only because `Item` is a map-free record and the loader
orders by id.)

### P0-7 — AsyncRefreshDispatcher lost-update window — `andersoni-core/.../Andersoni.java` `dispatch()` ~1189 ✓
`pending.set(false)` runs in the `finally` **after** `refreshTask.run()`. An
EVENT arriving while a run is in progress fails the CAS and is coalesced
(discarded); the in-flight run finishes with older data and clears the flag, and
no later event re-triggers it → a follower can stay stale until an unrelated
refresh.
Fix: clear `pending` **before** invoking `run()` (still holding the semaphore),
or add a dirty/re-check loop that re-runs if another event arrived during
execution.

### P0-8 — Kafka poll loop dies on non-Wakeup exception — `andersoni-sync-kafka/.../KafkaSyncStrategy.java` `pollLoop()` :193
The loop only handles `WakeupException`. Any other exception (broker outage,
rebalance error, deserialization) propagates out, the daemon consumer thread
dies, `running` stays `true`, `publish()` keeps working, but the node never
consumes again until a full process restart. No backoff, no health signal.
Fix: wrap `poll()` inside the loop with catch + short backoff + continue;
rethrow `WakeupException` only when stopping.

### P0-9 — Admin app is published to Maven Central — `andersoni-admin/pom.xml` ✓
It runs `spring-boot-maven-plugin:repackage` (executable fat jar) and has **no**
`maven.deploy.skip`. During `mvn deploy -Prelease` the whole reactor publishes,
so the admin console ships to Central as if it were a library (unusable as a
dependency, namespace pollution). Almost certainly already happened in the
1.10.1 release and earlier. `andersoni-example` is correctly excluded; `admin`
was not.
Fix: add `<maven.deploy.skip>true</maven.deploy.skip>` to `andersoni-admin`
(and confirm the Central bundle contents with a `-Prelease` dry run).

---

## P1 — Risks / robustness

- **HTTP sync** (`HttpSyncStrategy`): unauthenticated endpoint + unbounded
  request body (`readAllBytes`, OOM) + single-thread default executor;
  fire-and-forget publish with no retry (permanent divergence if a peer is
  briefly down); no lifecycle/idempotency guard (`publish` before `start` NPEs,
  double `start` fails to bind, no publish-after-stop guard).
- **DB publish** (`DbPollingSyncStrategy`): `UPDATE ... WHERE catalog_name=?`
  with no `AND version < ?` → last-writer-wins (a stale event overwrites newer
  content, and `poll` compares only by hash); UPDATE-then-INSERT is not atomic →
  two nodes creating a new catalog concurrently both INSERT → PK violation.
- **DB table name** interpolated into SQL with only `isBlank()` validation →
  SQL injection surface via externalized config. Validate against
  `^[A-Za-z_][A-Za-z0-9_]*$`.
- **Kafka `start()`**: sets `running=true` before building resources; if the
  consumer fails, the already-created producer leaks and the instance is wedged
  in "running". Build resources before flipping the flag / clean up on failure.
- **S3 store**: `readAllBytes()` / `RequestBody.fromBytes(...)` buffer the whole
  snapshot in heap (OOM for large catalogs) and the stored `hash` metadata is
  never verified against the downloaded bytes. Use streaming/multipart; verify
  hash on load.
- **FS store**: `snapshot.dat` and `snapshot.meta` are moved with two separate
  `ATOMIC_MOVE`s → a crash or a concurrent reader can observe new data + old
  meta. Make the unit atomic (single file / completion marker / verify hash on
  read).
- **Catalog-name path/key injection** (FS `resolve` can escape `baseDir`; S3 key
  built from raw name) in both stores. Validate/normalize the catalog name.
- **K8s renewal math**: no validation that `retryPeriod < renewDeadline <
  leaseDuration` → leadership flapping with plausible config.
- **Starter lifecycle** (`AndersoniAutoConfiguration`): `start()` blocks Spring
  context startup for the whole follower bootstrap-retry window; and `running`
  is set only after a successful `start()`, so a partial-start failure means
  Spring never calls `stop()` → leaked election/sync/metrics threads and
  sockets.
- **Datadog cardinality** (`DatadogAndersoniMetrics`): the default `nodeId` is a
  random UUID per process, emitted as a `node:` tag on every metric → unbounded
  tag cardinality across pod restarts. Default to a stable id (HOSTNAME/pod
  name) or make the tag opt-in.
- **Build hooks** (`SnapshotBuildHook`): the value returned by `process(...)` is
  discarded — indices/views are built from the original item before hooks run,
  so a transforming hook has no effect. Either drop the return type (pure
  side-effect hooks) or feed the transformed item into indexation.
- **Visibility**: `asyncRefreshDispatcher` and `scheduler` are non-`volatile`,
  written in `start()` and read from transport threads / `stop()`. Make them
  `volatile` or assign in the constructor.
- **Mixed-version rolling upgrade**: a pre-1.10 node decodes a follower's
  REQUEST as a plain event (`hash=""`) and does a spurious DataLoader refresh.
  Document the upgrade ordering; consider a marker old nodes safely ignore.

---

## P2 — Design / quality / tech-debt / docs

- **`RefreshEvent` + `RefreshKind` models invalid states** (REQUEST carries
  `version=0`, `hash=""`). For a future 2.0: `sealed interface RefreshMessage
  permits RefreshResult, RefreshRequest`, each a record with only meaningful
  fields, dispatched via `switch` pattern matching. The `kind` field is an
  acceptable minimal fix for 1.x; the wire codec's "unknown kind → EVENT"
  default is a good compat tactic to keep.
- **core is not zero-dependency** ✓: it pulls `org.json` (used only by
  `RefreshEventCodec`), so every consumer inherits it and the project ships two
  JSON stacks (org.json + Jackson). Move the codec into a `sync-commons`/
  transport module; correct the "zero dependencies" claim in `CLAUDE.md`.
- **`SyncStrategy` SPI under-specified**: threading model, delivery semantics
  (at-least/at-most-once), and `subscribe` cardinality are undocumented.
- **Thin observability**: `syncPublished` is used for both EVENT and REQUEST
  (can't distinguish broadcast vs request); no gauges for leader status,
  per-catalog version/hash, staleness/lag, or coalesced/dropped events. Expose a
  read-only `AndersoniStatus` in core so ops don't reinvent a `/state` endpoint.
- **Auto-config**: missing `@ConditionalOnMissingBean` on `andersoni` /
  `andersoniLifecycle`; multiplicity validation applied only to Sync/Snapshot
  (Leader/Metrics/Retry throw opaque `NoUniqueBeanDefinitionException`).
- **Metrics event methods** lack a try/catch guard (rely on the caller and a
  non-throwing client to keep "metrics never break the cache").
- **Spring Kafka `acks`** is not validated (the raw module enforces
  `{0,1,all}`).
- **Kafka `auto.offset.reset=latest`** drops events during the consumer join
  window; stable-group (`nodeId` set) vs fresh-group semantics differ. Document.
- **Dead code**: unused `buildIndex(...)` methods across the index definitions
  (a second code path to keep in sync — already inconsistent on null-key
  handling vs `accumulate`).
- **Query IN_LIST** cartesian product has no query-time size cap.
- **Serializer**: `FAIL_ON_UNKNOWN_PROPERTIES` is on (brittle schema evolution
  when loading older snapshots) and `readValue` → `null` NPEs.
- **Docs drift**: `CLAUDE.md` module table omits `andersoni-json-serializer`
  and `andersoni-cluster-it`; `README.md` pins `1.9.0`; the "zero-dependency"
  claim is inaccurate.
- **Test gaps**: dispatcher concurrency/coalescing; follower bootstrap +
  promotion-mid-bootstrap; leader transitions + `stop()`; store crash-
  consistency + path traversal; serializer determinism; transport failure paths
  (scheduler death, poll-loop recovery, malformed/oversized/unauthenticated
  HTTP); auto-config `stop()` propagation + survival of a failed catalog
  bootstrap.

---

## Cross-cutting themes

1. **Silent background-thread death**: schedulers / poll loops / the K8s elector
   die with no recovery and no log (DB, Kafka, leader). A shared
   "supervise, log, backoff, continue" pattern is missing.
2. **Hash determinism** is the cornerstone of convergence and is currently
   fragile (map order, row order, `toString`).
3. **Release hygiene**: the default reactor requires Docker (cluster-it); admin
   (and possibly example) get published; sign/javadoc run over non-published
   modules.
4. **Security**: both the admin app and HTTP sync are exposed without auth.

---

## Suggested next 48h

1. **P0-1** (the periodic-request storm — introduced by the recent fix): trivial
   leader gate + test.
2. **P0-2 / P0-8** (DB scheduler death, Kafka poll loop): `catch (Throwable)` +
   backoff so one bad row/exception can't kill sync forever.
3. **P0-9** (admin `maven.deploy.skip`) + verify what the 1.10.1 Central bundle
   actually contained.
4. **P0-4 / P0-5** (S3 IRSA, K8s split-brain): the ones that break real
   production deployments.
5. **P0-6** (deterministic hash): `ORDER_MAP_ENTRIES_BY_KEYS` + sort per-item
   hashes.

Longer term: normalize `RefreshEvent` → sealed `RefreshMessage`; purge
`org.json` from core; harden observability + read-only status API; backfill
deterministic concurrency/bootstrap unit tests and demote the Docker IT to a
smoke test.
