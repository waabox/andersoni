# Andersoni Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build an in-memory indexed cache library for Java 21 with a fluent DSL, pluggable sync/leader/snapshot strategies, and a Spring Boot starter.

**Architecture:** Multi-module Maven project. Core module is pure Java 21 with zero dependencies. Plugin modules bring their own deps (Kafka, K8s, S3). Spring Boot starter auto-wires everything. Concurrency via immutable Snapshots + AtomicReference (lock-free reads).

**Tech Stack:** Java 21, Maven, JUnit 5, EasyMock, Spring Boot (starter only), Kafka, AWS S3, Kubernetes client.

**Root package:** `org.waabox.andersoni`
**GroupId:** `org.waabox`

---

## Phase 1: Project Scaffolding

### Task 1: Create Maven parent POM and module structure

**Files:**
- Create: `pom.xml` (parent)
- Create: `andersoni-core/pom.xml`
- Create: `andersoni-sync-kafka/pom.xml`
- Create: `andersoni-sync-http/pom.xml`
- Create: `andersoni-sync-db/pom.xml`
- Create: `andersoni-leader-k8s/pom.xml`
- Create: `andersoni-snapshot-s3/pom.xml`
- Create: `andersoni-snapshot-fs/pom.xml`
- Create: `andersoni-spring-boot-starter/pom.xml`

**Step 1: Create parent POM**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>org.waabox</groupId>
    <artifactId>andersoni</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <name>Andersoni</name>
    <description>In-memory indexed cache library with fluent DSL</description>

    <properties>
        <java.version>21</java.version>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <junit.version>5.11.4</junit.version>
        <easymock.version>5.5.0</easymock.version>
        <slf4j.version>2.0.16</slf4j.version>
    </properties>

    <modules>
        <module>andersoni-core</module>
        <module>andersoni-sync-kafka</module>
        <module>andersoni-sync-http</module>
        <module>andersoni-sync-db</module>
        <module>andersoni-leader-k8s</module>
        <module>andersoni-snapshot-s3</module>
        <module>andersoni-snapshot-fs</module>
        <module>andersoni-spring-boot-starter</module>
    </modules>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.waabox</groupId>
                <artifactId>andersoni-core</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>org.slf4j</groupId>
                <artifactId>slf4j-api</artifactId>
                <version>${slf4j.version}</version>
            </dependency>
            <dependency>
                <groupId>org.junit.jupiter</groupId>
                <artifactId>junit-jupiter</artifactId>
                <version>${junit.version}</version>
                <scope>test</scope>
            </dependency>
            <dependency>
                <groupId>org.easymock</groupId>
                <artifactId>easymock</artifactId>
                <version>${easymock.version}</version>
                <scope>test</scope>
            </dependency>
            <dependency>
                <groupId>org.slf4j</groupId>
                <artifactId>slf4j-simple</artifactId>
                <version>${slf4j.version}</version>
                <scope>test</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version>
                <configuration>
                    <source>21</source>
                    <target>21</target>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.5.2</version>
            </plugin>
        </plugins>
    </build>
</project>
```

**Step 2: Create andersoni-core/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.waabox</groupId>
        <artifactId>andersoni</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>andersoni-core</artifactId>
    <name>Andersoni Core</name>
    <description>Core engine, DSL, Snapshot, and plugin interfaces (zero external deps)</description>

    <dependencies>
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
        </dependency>
        <dependency>
            <groupId>org.easymock</groupId>
            <artifactId>easymock</artifactId>
        </dependency>
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-simple</artifactId>
        </dependency>
    </dependencies>
</project>
```

**Step 3: Create stub POMs for all other modules**

Each module gets a minimal POM with `andersoni-core` as dependency. Full dependencies will be added when implementing each module. Create skeleton POMs for: `andersoni-sync-kafka`, `andersoni-sync-http`, `andersoni-sync-db`, `andersoni-leader-k8s`, `andersoni-snapshot-s3`, `andersoni-snapshot-fs`, `andersoni-spring-boot-starter`.

**Step 4: Create directory structure for andersoni-core**

```
andersoni-core/src/main/java/org/waabox/andersoni/
andersoni-core/src/test/java/org/waabox/andersoni/
```

**Step 5: Verify the build compiles**

Run: `mvn clean compile -q`
Expected: BUILD SUCCESS

**Step 6: Create .gitignore**

Standard Maven/Java gitignore: `target/`, `*.class`, `.idea/`, `*.iml`, `.settings/`, `.project`, `.classpath`.

**Step 7: Commit**

```
feat: scaffold Maven multi-module project structure
```

---

## Phase 2: Core Interfaces and Value Objects

### Task 2: DataLoader interface

**Files:**
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/DataLoader.java`

**Step 1: Create DataLoader**

```java
package org.waabox.andersoni;

import java.util.List;

/** Loads data for a catalog.
 *
 * <p>The user implements this to define how data is fetched from its source
 * (database, external API, etc.). Andersoni calls it during bootstrap and
 * refresh cycles.</p>
 *
 * @param <T> the type of data to load.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
@FunctionalInterface
public interface DataLoader<T> {

    /** Loads all data for the catalog.
     *
     * @return the list of items, never null.
     */
    List<T> load();
}
```

No test needed — it's a functional interface.

**Step 2: Commit**

```
feat: add DataLoader functional interface
```

### Task 3: RefreshEvent record and RefreshListener

**Files:**
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/sync/RefreshEvent.java`
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/sync/RefreshListener.java`

**Step 1: Create RefreshEvent**

```java
package org.waabox.andersoni.sync;

import java.time.Instant;

/** Event published when a catalog needs to be refreshed across nodes.
 *
 * @param catalogName the name of the catalog that changed.
 * @param sourceNodeId the node ID that originated the refresh.
 * @param version the snapshot version after the refresh.
 * @param hash the SHA-256 hash of the serialized snapshot data.
 * @param timestamp when the refresh occurred.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public record RefreshEvent(
    String catalogName,
    String sourceNodeId,
    long version,
    String hash,
    Instant timestamp
) {}
```

**Step 2: Create RefreshListener**

```java
package org.waabox.andersoni.sync;

/** Listens for refresh events from other nodes.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
@FunctionalInterface
public interface RefreshListener {

    /** Called when a refresh event is received from another node.
     *
     * @param event the refresh event.
     */
    void onRefresh(RefreshEvent event);
}
```

**Step 3: Commit**

```
feat: add RefreshEvent record and RefreshListener interface
```

### Task 4: SyncStrategy interface

**Files:**
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/sync/SyncStrategy.java`

**Step 1: Create SyncStrategy**

```java
package org.waabox.andersoni.sync;

/** Strategy for propagating refresh events between nodes.
 *
 * <p>Implementations define how nodes communicate changes. Out-of-the-box
 * implementations include Kafka, HTTP, and DB polling.</p>
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public interface SyncStrategy {

    /** Publishes a refresh event to other nodes.
     *
     * @param event the refresh event to publish.
     */
    void publish(RefreshEvent event);

    /** Registers a listener that receives refresh events from other nodes.
     *
     * @param listener the listener to register.
     */
    void subscribe(RefreshListener listener);

    /** Starts the sync mechanism (connect to broker, open HTTP server, etc.). */
    void start();

    /** Stops the sync mechanism and releases resources. */
    void stop();
}
```

**Step 2: Commit**

```
feat: add SyncStrategy interface
```

### Task 5: LeaderElectionStrategy interface

**Files:**
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/leader/LeaderElectionStrategy.java`
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/leader/LeaderChangeListener.java`
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/leader/SingleNodeLeaderElection.java`
- Test: `andersoni-core/src/test/java/org/waabox/andersoni/leader/SingleNodeLeaderElectionTest.java`

**Step 1: Create LeaderElectionStrategy**

```java
package org.waabox.andersoni.leader;

/** Strategy for electing a leader node among multiple instances of a service.
 *
 * <p>The leader handles tasks like DB polling, scheduled refreshes, and
 * snapshot publication. Implementations include K8s Lease API and a
 * single-node strategy for development.</p>
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public interface LeaderElectionStrategy {

    /** Starts the election process. */
    void start();

    /** Checks whether this node is the current leader.
     *
     * @return true if this node is the leader.
     */
    boolean isLeader();

    /** Registers a callback for leadership changes.
     *
     * @param listener the listener to register.
     */
    void onLeaderChange(LeaderChangeListener listener);

    /** Stops the election process and releases resources. */
    void stop();
}
```

**Step 2: Create LeaderChangeListener**

```java
package org.waabox.andersoni.leader;

/** Listener for leader election changes.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
@FunctionalInterface
public interface LeaderChangeListener {

    /** Called when the leadership status changes.
     *
     * @param isLeader true if this node became the leader, false if it lost leadership.
     */
    void onLeaderChange(boolean isLeader);
}
```

**Step 3: Write the failing test for SingleNodeLeaderElection**

```java
package org.waabox.andersoni.leader;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SingleNodeLeaderElectionTest {

    @Test
    void whenCheckingIsLeader_givenSingleNode_shouldAlwaysReturnTrue() {
        final SingleNodeLeaderElection election = new SingleNodeLeaderElection();
        election.start();
        assertTrue(election.isLeader());
        election.stop();
    }

    @Test
    void whenStarting_givenListener_shouldNotifyAsLeader() {
        final SingleNodeLeaderElection election = new SingleNodeLeaderElection();
        final boolean[] notified = {false};
        election.onLeaderChange(isLeader -> notified[0] = isLeader);
        election.start();
        assertTrue(notified[0]);
    }
}
```

**Step 4: Run test to verify it fails**

Run: `mvn test -pl andersoni-core -Dtest=SingleNodeLeaderElectionTest -q`
Expected: FAIL — class does not exist yet.

**Step 5: Implement SingleNodeLeaderElection**

```java
package org.waabox.andersoni.leader;

import java.util.ArrayList;
import java.util.List;

/** Leader election strategy for single-node deployments.
 *
 * <p>Always considers the node as the leader. Intended for development,
 * testing, and single-instance production deployments.</p>
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class SingleNodeLeaderElection implements LeaderElectionStrategy {

    /** The list of listeners, never null. */
    private final List<LeaderChangeListener> listeners = new ArrayList<>();

    /** {@inheritDoc} */
    @Override
    public void start() {
        for (final LeaderChangeListener listener : listeners) {
            listener.onLeaderChange(true);
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean isLeader() {
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public void onLeaderChange(final LeaderChangeListener listener) {
        listeners.add(listener);
    }

    /** {@inheritDoc} */
    @Override
    public void stop() {
        // Nothing to clean up in single-node mode.
    }
}
```

**Step 6: Run test to verify it passes**

Run: `mvn test -pl andersoni-core -Dtest=SingleNodeLeaderElectionTest -q`
Expected: PASS

**Step 7: Commit**

```
feat: add LeaderElectionStrategy interface and SingleNodeLeaderElection
```

### Task 6: SnapshotStore interface and SerializedSnapshot record

**Files:**
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/snapshot/SnapshotStore.java`
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/snapshot/SerializedSnapshot.java`
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/snapshot/SnapshotSerializer.java`

**Step 1: Create SerializedSnapshot**

```java
package org.waabox.andersoni.snapshot;

import java.time.Instant;

/** Serialized representation of a catalog snapshot for persistence.
 *
 * @param catalogName the catalog this snapshot belongs to.
 * @param hash the SHA-256 hash of the serialized data.
 * @param version the snapshot version number.
 * @param createdAt when the snapshot was created.
 * @param data the serialized byte array.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public record SerializedSnapshot(
    String catalogName,
    String hash,
    long version,
    Instant createdAt,
    byte[] data
) {}
```

**Step 2: Create SnapshotSerializer**

```java
package org.waabox.andersoni.snapshot;

import java.util.List;

/** Serializes and deserializes catalog data for snapshot persistence.
 *
 * @param <T> the type of data in the catalog.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public interface SnapshotSerializer<T> {

    /** Serializes a list of items to bytes.
     *
     * @param items the items to serialize.
     * @return the serialized byte array.
     */
    byte[] serialize(List<T> items);

    /** Deserializes bytes back to a list of items.
     *
     * @param data the serialized byte array.
     * @return the deserialized items.
     */
    List<T> deserialize(byte[] data);
}
```

**Step 3: Create SnapshotStore**

```java
package org.waabox.andersoni.snapshot;

import java.util.Optional;

/** Strategy for persisting and retrieving serialized catalog snapshots.
 *
 * <p>Implementations define where snapshots are stored (S3, filesystem, etc.).
 * Used for fast cold starts — new nodes download the latest snapshot instead
 * of querying the database.</p>
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public interface SnapshotStore {

    /** Persists a serialized snapshot.
     *
     * @param catalogName the catalog name.
     * @param snapshot the serialized snapshot to persist.
     */
    void save(String catalogName, SerializedSnapshot snapshot);

    /** Loads the latest serialized snapshot for a catalog.
     *
     * @param catalogName the catalog name.
     * @return the snapshot if available, empty if not found.
     */
    Optional<SerializedSnapshot> load(String catalogName);
}
```

**Step 4: Commit**

```
feat: add SnapshotStore, SerializedSnapshot, and SnapshotSerializer
```

### Task 7: AndersoniMetrics interface and NoopMetrics

**Files:**
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/metrics/AndersoniMetrics.java`
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/metrics/NoopAndersoniMetrics.java`

**Step 1: Create AndersoniMetrics**

```java
package org.waabox.andersoni.metrics;

/** Observability hooks for Andersoni operations.
 *
 * <p>Default implementation is no-op. The Spring Boot starter wires this to
 * Micrometer automatically if available on the classpath.</p>
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public interface AndersoniMetrics {

    /** Called after a catalog is refreshed.
     *
     * @param catalogName the catalog name.
     * @param durationMs how long the refresh took in milliseconds.
     * @param itemCount how many items are in the new snapshot.
     */
    void catalogRefreshed(String catalogName, long durationMs, long itemCount);

    /** Called after a snapshot is loaded.
     *
     * @param catalogName the catalog name.
     * @param source where the snapshot came from ("snapshotStore" or "dataLoader").
     */
    void snapshotLoaded(String catalogName, String source);

    /** Called after a search is executed.
     *
     * @param catalogName the catalog name.
     * @param indexName the index that was searched.
     * @param durationNs how long the search took in nanoseconds.
     */
    void searchExecuted(String catalogName, String indexName, long durationNs);

    /** Called when a refresh fails.
     *
     * @param catalogName the catalog name.
     * @param cause the exception that caused the failure.
     */
    void refreshFailed(String catalogName, Throwable cause);
}
```

**Step 2: Create NoopAndersoniMetrics**

```java
package org.waabox.andersoni.metrics;

/** No-op implementation of AndersoniMetrics. Used as default when no metrics
 * framework is configured.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class NoopAndersoniMetrics implements AndersoniMetrics {

    @Override
    public void catalogRefreshed(final String catalogName,
        final long durationMs, final long itemCount) {}

    @Override
    public void snapshotLoaded(final String catalogName,
        final String source) {}

    @Override
    public void searchExecuted(final String catalogName,
        final String indexName, final long durationNs) {}

    @Override
    public void refreshFailed(final String catalogName,
        final Throwable cause) {}
}
```

**Step 3: Commit**

```
feat: add AndersoniMetrics interface and NoopAndersoniMetrics
```

### Task 8: RetryPolicy and CatalogNotAvailableException

**Files:**
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/RetryPolicy.java`
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/CatalogNotAvailableException.java`
- Test: `andersoni-core/src/test/java/org/waabox/andersoni/RetryPolicyTest.java`

**Step 1: Write the failing test**

```java
package org.waabox.andersoni;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class RetryPolicyTest {

    @Test
    void whenCreating_givenValidParams_shouldRetainValues() {
        final RetryPolicy policy = RetryPolicy.of(3, Duration.ofSeconds(2));
        assertEquals(3, policy.maxRetries());
        assertEquals(Duration.ofSeconds(2), policy.backoff());
    }

    @Test
    void whenCreating_givenZeroRetries_shouldThrow() {
        assertThrows(IllegalArgumentException.class,
            () -> RetryPolicy.of(0, Duration.ofSeconds(1)));
    }

    @Test
    void whenCreating_givenNullBackoff_shouldThrow() {
        assertThrows(NullPointerException.class,
            () -> RetryPolicy.of(1, null));
    }

    @Test
    void whenUsingDefault_shouldHaveReasonableValues() {
        final RetryPolicy policy = RetryPolicy.defaultPolicy();
        assertTrue(policy.maxRetries() > 0);
        assertNotNull(policy.backoff());
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -pl andersoni-core -Dtest=RetryPolicyTest -q`
Expected: FAIL

**Step 3: Implement RetryPolicy**

```java
package org.waabox.andersoni;

import java.time.Duration;
import java.util.Objects;

/** Defines retry behavior for data loading and sync operations.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class RetryPolicy {

    /** Default: 3 retries with 2 second backoff. */
    private static final RetryPolicy DEFAULT =
        new RetryPolicy(3, Duration.ofSeconds(2));

    /** The maximum number of retries, always > 0. */
    private final int maxRetries;

    /** The backoff duration between retries, never null. */
    private final Duration backoff;

    /** Creates a RetryPolicy.
     *
     * @param maxRetries the maximum number of retries, must be > 0.
     * @param backoff the backoff duration, never null.
     */
    private RetryPolicy(final int maxRetries, final Duration backoff) {
        this.maxRetries = maxRetries;
        this.backoff = backoff;
    }

    /** Creates a new RetryPolicy.
     *
     * @param maxRetries the maximum number of retries, must be > 0.
     * @param backoff the backoff duration, never null.
     * @return a new RetryPolicy, never null.
     */
    public static RetryPolicy of(final int maxRetries, final Duration backoff) {
        if (maxRetries <= 0) {
            throw new IllegalArgumentException(
                "maxRetries must be > 0, got: " + maxRetries);
        }
        Objects.requireNonNull(backoff, "backoff cannot be null");
        return new RetryPolicy(maxRetries, backoff);
    }

    /** Returns the default retry policy (3 retries, 2s backoff).
     *
     * @return the default policy, never null.
     */
    public static RetryPolicy defaultPolicy() {
        return DEFAULT;
    }

    /** Returns the maximum number of retries.
     *
     * @return the max retries, always > 0.
     */
    public int maxRetries() {
        return maxRetries;
    }

    /** Returns the backoff duration between retries.
     *
     * @return the backoff duration, never null.
     */
    public Duration backoff() {
        return backoff;
    }
}
```

**Step 4: Create CatalogNotAvailableException**

```java
package org.waabox.andersoni;

/** Thrown when a search is performed on a catalog that failed to load.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class CatalogNotAvailableException extends RuntimeException {

    /** Creates a new CatalogNotAvailableException.
     *
     * @param catalogName the catalog that is not available.
     */
    public CatalogNotAvailableException(final String catalogName) {
        super("Catalog '" + catalogName + "' is not available. "
            + "It may have failed to load during bootstrap.");
    }

    /** Creates a new CatalogNotAvailableException with a cause.
     *
     * @param catalogName the catalog that is not available.
     * @param cause the underlying cause.
     */
    public CatalogNotAvailableException(final String catalogName,
        final Throwable cause) {
        super("Catalog '" + catalogName + "' is not available: "
            + cause.getMessage(), cause);
    }
}
```

**Step 5: Run tests to verify they pass**

Run: `mvn test -pl andersoni-core -Dtest=RetryPolicyTest -q`
Expected: PASS

**Step 6: Commit**

```
feat: add RetryPolicy and CatalogNotAvailableException
```

---

## Phase 3: Snapshot and Index Engine

### Task 9: Snapshot — immutable point-in-time view

**Files:**
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/Snapshot.java`
- Test: `andersoni-core/src/test/java/org/waabox/andersoni/SnapshotTest.java`

**Step 1: Write the failing test**

```java
package org.waabox.andersoni;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotTest {

    @Test
    void whenCreating_givenDataAndIndices_shouldRetainImmutableState() {
        final Map<String, Map<Object, List<String>>> indices = Map.of(
            "by-length", Map.of(
                3, List.of("foo", "bar"),
                5, List.of("hello")
            )
        );
        final Snapshot<String> snapshot = Snapshot.of(
            List.of("foo", "bar", "hello"), indices, 1L, "abc123"
        );

        assertEquals(3, snapshot.data().size());
        assertEquals(1L, snapshot.version());
        assertEquals("abc123", snapshot.hash());
        assertNotNull(snapshot.createdAt());
    }

    @Test
    void whenSearchingIndex_givenExistingKey_shouldReturnMatches() {
        final Map<String, Map<Object, List<String>>> indices = Map.of(
            "by-length", Map.of(
                3, List.of("foo", "bar"),
                5, List.of("hello")
            )
        );
        final Snapshot<String> snapshot = Snapshot.of(
            List.of("foo", "bar", "hello"), indices, 1L, "abc123"
        );

        assertEquals(List.of("foo", "bar"), snapshot.search("by-length", 3));
        assertEquals(List.of("hello"), snapshot.search("by-length", 5));
    }

    @Test
    void whenSearchingIndex_givenMissingKey_shouldReturnEmptyList() {
        final Map<String, Map<Object, List<String>>> indices = Map.of(
            "by-length", Map.of(3, List.of("foo"))
        );
        final Snapshot<String> snapshot = Snapshot.of(
            List.of("foo"), indices, 1L, "abc123"
        );

        assertEquals(List.of(), snapshot.search("by-length", 99));
    }

    @Test
    void whenSearchingIndex_givenMissingIndex_shouldReturnEmptyList() {
        final Snapshot<String> snapshot = Snapshot.of(
            List.of("foo"), Map.of(), 1L, "abc123"
        );

        assertEquals(List.of(), snapshot.search("nonexistent", "key"));
    }

    @Test
    void whenAccessingData_shouldBeUnmodifiable() {
        final Snapshot<String> snapshot = Snapshot.of(
            List.of("foo"), Map.of(), 1L, "abc123"
        );

        assertThrows(UnsupportedOperationException.class,
            () -> snapshot.data().add("bar"));
    }

    @Test
    void whenCreatingEmpty_shouldWorkCorrectly() {
        final Snapshot<String> empty = Snapshot.empty();

        assertEquals(0, empty.data().size());
        assertEquals(0L, empty.version());
        assertEquals("", empty.hash());
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -pl andersoni-core -Dtest=SnapshotTest -q`
Expected: FAIL

**Step 3: Implement Snapshot**

```java
package org.waabox.andersoni;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable point-in-time view of all data and indices for a catalog.
 *
 * <p>Once created, a Snapshot is never modified. Reads are lock-free.
 * The concurrency model relies on AtomicReference swaps of entire Snapshot
 * instances.</p>
 *
 * @param <T> the type of data in the catalog.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class Snapshot<T> {

    /** Empty snapshot singleton data. */
    private static final Snapshot<?> EMPTY = new Snapshot<>(
        List.of(), Map.of(), 0L, "", Instant.now()
    );

    /** The data items, never null, unmodifiable. */
    private final List<T> data;

    /** The indices keyed by index name, never null, unmodifiable. */
    private final Map<String, Map<Object, List<T>>> indices;

    /** The version number. */
    private final long version;

    /** The SHA-256 hash of the serialized data. */
    private final String hash;

    /** When this snapshot was created. */
    private final Instant createdAt;

    /** Creates a new Snapshot.
     *
     * @param data the data items.
     * @param indices the indices.
     * @param version the version.
     * @param hash the hash.
     * @param createdAt when the snapshot was created.
     */
    private Snapshot(final List<T> data,
        final Map<String, Map<Object, List<T>>> indices,
        final long version, final String hash, final Instant createdAt) {
        this.data = data;
        this.indices = indices;
        this.version = version;
        this.hash = hash;
        this.createdAt = createdAt;
    }

    /** Creates a new Snapshot from data and pre-built indices.
     *
     * @param data the data items, never null.
     * @param indices the indices, never null.
     * @param version the version number.
     * @param hash the SHA-256 hash of the serialized data.
     * @param <T> the type of data.
     * @return a new Snapshot, never null.
     */
    public static <T> Snapshot<T> of(final List<T> data,
        final Map<String, Map<Object, List<T>>> indices,
        final long version, final String hash) {
        Objects.requireNonNull(data, "data cannot be null");
        Objects.requireNonNull(indices, "indices cannot be null");
        Objects.requireNonNull(hash, "hash cannot be null");
        return new Snapshot<>(
            Collections.unmodifiableList(List.copyOf(data)),
            Collections.unmodifiableMap(indices),
            version, hash, Instant.now()
        );
    }

    /** Returns an empty Snapshot.
     *
     * @param <T> the type of data.
     * @return an empty Snapshot, never null.
     */
    @SuppressWarnings("unchecked")
    public static <T> Snapshot<T> empty() {
        return (Snapshot<T>) EMPTY;
    }

    /** Searches an index by key.
     *
     * @param indexName the index name.
     * @param key the key to search for.
     * @return the matching items, or empty list if no match.
     */
    public List<T> search(final String indexName, final Object key) {
        final Map<Object, List<T>> index = indices.getOrDefault(
            indexName, Map.of());
        return index.getOrDefault(key, List.of());
    }

    /** Returns the data items (unmodifiable).
     *
     * @return the data items, never null.
     */
    public List<T> data() {
        return data;
    }

    /** Returns the version number.
     *
     * @return the version.
     */
    public long version() {
        return version;
    }

    /** Returns the hash of the serialized data.
     *
     * @return the hash, never null.
     */
    public String hash() {
        return hash;
    }

    /** Returns when this snapshot was created.
     *
     * @return the creation timestamp, never null.
     */
    public Instant createdAt() {
        return createdAt;
    }
}
```

**Step 4: Run tests to verify they pass**

Run: `mvn test -pl andersoni-core -Dtest=SnapshotTest -q`
Expected: PASS

**Step 5: Commit**

```
feat: add Snapshot immutable point-in-time view
```

### Task 10: IndexDefinition and fluent builder

**Files:**
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/IndexDefinition.java`
- Test: `andersoni-core/src/test/java/org/waabox/andersoni/IndexDefinitionTest.java`

**Step 1: Write the failing test**

Use a simple test domain class (inner class in test) like:

```java
record Sport(String name) {}
record Venue(String name) {}
record Team(String name) {}
record Event(String id, Sport sport, Venue venue, Team homeTeam) {}
```

Test that `IndexDefinition` can:
- Extract a key from a composed path (`Event::getVenue` then `Venue::getName`)
- Build an index map from a list of data

```java
package org.waabox.andersoni;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class IndexDefinitionTest {

    record Sport(String name) {}
    record Venue(String name) {}
    record Event(String id, Sport sport, Venue venue) {}

    @Test
    void whenBuildingIndex_givenData_shouldGroupByKey() {
        final IndexDefinition<Event> def = IndexDefinition.<Event>named("by-venue")
            .by(Event::venue, Venue::name);

        final Venue wembley = new Venue("Wembley");
        final Venue bernabeu = new Venue("Bernabeu");

        final Event e1 = new Event("1", new Sport("Football"), wembley);
        final Event e2 = new Event("2", new Sport("Rugby"), wembley);
        final Event e3 = new Event("3", new Sport("Football"), bernabeu);

        final Map<Object, List<Event>> index = def.buildIndex(
            List.of(e1, e2, e3));

        assertEquals(List.of(e1, e2), index.get("Wembley"));
        assertEquals(List.of(e3), index.get("Bernabeu"));
        assertEquals(2, index.size());
    }

    @Test
    void whenBuildingIndex_givenEmptyData_shouldReturnEmptyMap() {
        final IndexDefinition<Event> def = IndexDefinition.<Event>named("by-sport")
            .by(Event::sport, Sport::name);

        final Map<Object, List<Event>> index = def.buildIndex(List.of());
        assertTrue(index.isEmpty());
    }

    @Test
    void whenGettingName_shouldReturnConfiguredName() {
        final IndexDefinition<Event> def = IndexDefinition.<Event>named("by-venue")
            .by(Event::venue, Venue::name);
        assertEquals("by-venue", def.name());
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -pl andersoni-core -Dtest=IndexDefinitionTest -q`
Expected: FAIL

**Step 3: Implement IndexDefinition**

```java
package org.waabox.andersoni;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Defines how to extract an index key from a domain object.
 *
 * <p>Created via the fluent builder and used by Catalog to build index maps
 * from loaded data.</p>
 *
 * @param <T> the type of domain object being indexed.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class IndexDefinition<T> {

    /** The index name, never null or empty. */
    private final String name;

    /** The key extractor function, never null. */
    private final Function<T, Object> keyExtractor;

    /** Creates an IndexDefinition.
     *
     * @param name the index name.
     * @param keyExtractor the function to extract the key.
     */
    private IndexDefinition(final String name,
        final Function<T, Object> keyExtractor) {
        this.name = name;
        this.keyExtractor = keyExtractor;
    }

    /** Starts building an IndexDefinition with the given name.
     *
     * @param name the index name, never null or empty.
     * @param <T> the type of domain object.
     * @return a builder step for defining the key extractor.
     */
    public static <T> KeyStep<T> named(final String name) {
        Objects.requireNonNull(name, "name cannot be null");
        return new KeyStep<>(name);
    }

    /** Builds an index map from the given data.
     *
     * @param data the data to index.
     * @return an unmodifiable map of key to list of items.
     */
    public Map<Object, List<T>> buildIndex(final List<T> data) {
        final Map<Object, List<T>> index = new HashMap<>();
        for (final T item : data) {
            final Object key = keyExtractor.apply(item);
            index.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
        }
        index.replaceAll((k, v) -> Collections.unmodifiableList(v));
        return Collections.unmodifiableMap(index);
    }

    /** Returns the index name.
     *
     * @return the name, never null.
     */
    public String name() {
        return name;
    }

    /** Builder step for defining the key extractor.
     *
     * @param <T> the type of domain object.
     */
    public static final class KeyStep<T> {

        /** The index name. */
        private final String name;

        private KeyStep(final String name) {
            this.name = name;
        }

        /** Defines the key extractor as a composed function.
         *
         * @param first extracts an intermediate object from T.
         * @param second extracts the key from the intermediate object.
         * @param <I> the intermediate type.
         * @return the IndexDefinition.
         */
        public <I> IndexDefinition<T> by(final Function<T, I> first,
            final Function<I, ?> second) {
            Objects.requireNonNull(first, "first extractor cannot be null");
            Objects.requireNonNull(second, "second extractor cannot be null");
            final Function<T, Object> composed =
                item -> second.apply(first.apply(item));
            return new IndexDefinition<>(name, composed);
        }
    }
}
```

**Step 4: Run tests to verify they pass**

Run: `mvn test -pl andersoni-core -Dtest=IndexDefinitionTest -q`
Expected: PASS

**Step 5: Commit**

```
feat: add IndexDefinition with fluent builder and composed key extractors
```

---

## Phase 4: Catalog — The Core Abstraction

### Task 11: Catalog with fluent builder, DataLoader, .data(), and search

**Files:**
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/Catalog.java`
- Test: `andersoni-core/src/test/java/org/waabox/andersoni/CatalogTest.java`

**Step 1: Write the failing test**

```java
package org.waabox.andersoni;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CatalogTest {

    record Sport(String name) {}
    record Venue(String name) {}
    record Event(String id, Sport sport, Venue venue) {}

    @Test
    void whenBuilding_givenDataLoaderAndIndices_shouldBuildCorrectly() {
        final Venue wembley = new Venue("Wembley");
        final Event e1 = new Event("1", new Sport("Football"), wembley);
        final Event e2 = new Event("2", new Sport("Rugby"), wembley);
        final Event e3 = new Event("3", new Sport("Football"),
            new Venue("Bernabeu"));

        final Catalog<Event> catalog = Catalog.of(Event.class)
            .named("events")
            .loadWith(() -> List.of(e1, e2, e3))
            .index("by-venue").by(Event::venue, Venue::name)
            .index("by-sport").by(Event::sport, Sport::name)
            .build();

        assertEquals("events", catalog.name());
    }

    @Test
    void whenBootstrapping_givenDataLoader_shouldPopulateIndices() {
        final Venue wembley = new Venue("Wembley");
        final Event e1 = new Event("1", new Sport("Football"), wembley);
        final Event e2 = new Event("2", new Sport("Rugby"), wembley);

        final Catalog<Event> catalog = Catalog.of(Event.class)
            .named("events")
            .loadWith(() -> List.of(e1, e2))
            .index("by-venue").by(Event::venue, Venue::name)
            .build();

        catalog.bootstrap();

        final List<Event> result = catalog.search("by-venue", "Wembley");
        assertEquals(2, result.size());
        assertTrue(result.contains(e1));
        assertTrue(result.contains(e2));
    }

    @Test
    void whenBootstrapping_givenStaticData_shouldPopulateIndices() {
        final Event e1 = new Event("1", new Sport("Football"),
            new Venue("Wembley"));

        final Catalog<Event> catalog = Catalog.of(Event.class)
            .named("events")
            .data(List.of(e1))
            .index("by-venue").by(Event::venue, Venue::name)
            .build();

        catalog.bootstrap();

        assertEquals(List.of(e1), catalog.search("by-venue", "Wembley"));
    }

    @Test
    void whenRefreshing_givenNewData_shouldAtomicallySwapSnapshot() {
        final Event e1 = new Event("1", new Sport("Football"),
            new Venue("Wembley"));
        final Event e2 = new Event("2", new Sport("Rugby"),
            new Venue("Bernabeu"));

        final List<List<Event>> dataSets = List.of(
            List.of(e1),
            List.of(e1, e2)
        );
        final int[] callCount = {0};

        final Catalog<Event> catalog = Catalog.of(Event.class)
            .named("events")
            .loadWith(() -> dataSets.get(callCount[0]++))
            .index("by-venue").by(Event::venue, Venue::name)
            .build();

        catalog.bootstrap();
        assertEquals(1, catalog.search("by-venue", "Wembley").size());
        assertEquals(0, catalog.search("by-venue", "Bernabeu").size());

        catalog.refresh();
        assertEquals(1, catalog.search("by-venue", "Wembley").size());
        assertEquals(1, catalog.search("by-venue", "Bernabeu").size());
    }

    @Test
    void whenRefreshing_givenStaticData_shouldAcceptNewData() {
        final Catalog<Event> catalog = Catalog.of(Event.class)
            .named("events")
            .data(List.of())
            .index("by-venue").by(Event::venue, Venue::name)
            .build();

        catalog.bootstrap();
        assertEquals(0, catalog.search("by-venue", "Wembley").size());

        final Event e1 = new Event("1", new Sport("Football"),
            new Venue("Wembley"));
        catalog.refresh(List.of(e1));

        assertEquals(1, catalog.search("by-venue", "Wembley").size());
    }

    @Test
    void whenSearching_givenMissingKey_shouldReturnEmptyList() {
        final Catalog<Event> catalog = Catalog.of(Event.class)
            .named("events")
            .data(List.of())
            .index("by-venue").by(Event::venue, Venue::name)
            .build();

        catalog.bootstrap();

        assertEquals(List.of(), catalog.search("by-venue", "Nonexistent"));
    }

    @Test
    void whenSearching_givenNotBootstrapped_shouldReturnEmptyList() {
        final Catalog<Event> catalog = Catalog.of(Event.class)
            .named("events")
            .data(List.of())
            .index("by-venue").by(Event::venue, Venue::name)
            .build();

        assertEquals(List.of(), catalog.search("by-venue", "Wembley"));
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -pl andersoni-core -Dtest=CatalogTest -q`
Expected: FAIL

**Step 3: Implement Catalog**

The Catalog class needs:
- Builder that collects index definitions, DataLoader or static data, optional serializer, optional refreshEvery
- `bootstrap()`: loads data via DataLoader (or uses static data), builds snapshot, sets AtomicReference
- `refresh()`: re-executes DataLoader, rebuilds snapshot, atomic swap
- `refresh(List<T>)`: for static data mode, accepts new data directly
- `search(indexName, key)`: delegates to current Snapshot

Key implementation details:
- `AtomicReference<Snapshot<T>>` initialized to `Snapshot.empty()`
- `ReentrantLock` on write path (bootstrap/refresh)
- `AtomicLong` version counter
- Hash computed via SHA-256 on serialized data (using SnapshotSerializer if available, otherwise Object.hashCode as fallback)

**Step 4: Run tests to verify they pass**

Run: `mvn test -pl andersoni-core -Dtest=CatalogTest -q`
Expected: PASS

**Step 5: Commit**

```
feat: add Catalog with fluent builder, DataLoader, static data, and atomic refresh
```

---

## Phase 5: Andersoni — The Entry Point

### Task 12: Andersoni builder and lifecycle orchestration

**Files:**
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/Andersoni.java`
- Test: `andersoni-core/src/test/java/org/waabox/andersoni/AndersoniTest.java`

**Step 1: Write the failing test**

Test covers:
- Builder with defaults (auto UUID nodeId, no sync, single-node leader, no snapshot store)
- Register a catalog
- `start()` bootstraps all catalogs
- `search(catalogName, indexName, key)` delegates to the right catalog
- `refreshAndSync(catalogName)` refreshes and publishes via SyncStrategy
- `stop()` shuts down everything

Use EasyMock for SyncStrategy and SnapshotStore mocks.

**Step 2: Run test to verify it fails**

Run: `mvn test -pl andersoni-core -Dtest=AndersoniTest -q`
Expected: FAIL

**Step 3: Implement Andersoni**

Key details:
- `nodeId()`: returns the node ID (auto UUID or custom)
- `register(Catalog)`: adds to internal map
- `start()`: bootstraps all catalogs, starts sync, starts leader election
- `search(catalogName, indexName, key)`: looks up catalog, delegates search
- `refreshAndSync(catalogName)`: refresh catalog, then if syncStrategy present: serialize, save to snapshotStore, publish RefreshEvent
- `refresh(catalogName)`: refresh without sync (internal use, for when receiving sync events)
- `stop()`: stop sync, stop leader, cleanup

Wire the SyncStrategy listener: when receiving a RefreshEvent, compare hash, if different: load from snapshotStore (or fallback to DataLoader), rebuild, swap.

**Step 4: Run tests to verify they pass**

Run: `mvn test -pl andersoni-core -Dtest=AndersoniTest -q`
Expected: PASS

**Step 5: Commit**

```
feat: add Andersoni entry point with builder, lifecycle, and sync orchestration
```

---

## Phase 6: Concurrency and Hash Tests

### Task 13: Concurrent read/write test

**Files:**
- Test: `andersoni-core/src/test/java/org/waabox/andersoni/CatalogConcurrencyTest.java`

**Step 1: Write the concurrency test**

Test that:
- Multiple reader threads can search while a writer thread refreshes
- No reader ever sees a partial/inconsistent state
- After refresh, new readers see the new data

Use `CountDownLatch` and `ExecutorService` with multiple threads. Each reader captures its result and asserts it's either the old snapshot or the new one — never a mix.

**Step 2: Run test to verify it passes**

Run: `mvn test -pl andersoni-core -Dtest=CatalogConcurrencyTest -q`
Expected: PASS (this tests existing code, no new implementation)

**Step 3: Commit**

```
test: add concurrency test for Catalog atomic snapshot swap
```

### Task 14: SHA-256 hash computation

**Files:**
- Test: `andersoni-core/src/test/java/org/waabox/andersoni/SnapshotHashTest.java`

Verify that:
- Same data produces same hash
- Different data produces different hash
- Hash is deterministic

This test validates the hash logic built into Catalog/Snapshot during Task 11.

**Step 1: Write and run the test**

**Step 2: Commit**

```
test: add hash computation verification for Snapshot
```

---

## Phase 7: Snapshot Filesystem Store

### Task 15: FileSystemSnapshotStore

**Files:**
- Create: `andersoni-snapshot-fs/src/main/java/org/waabox/andersoni/snapshot/fs/FileSystemSnapshotStore.java`
- Test: `andersoni-snapshot-fs/src/test/java/org/waabox/andersoni/snapshot/fs/FileSystemSnapshotStoreTest.java`

**Step 1: Write the failing test**

Test with temp directory:
- Save a SerializedSnapshot, then load it back and assert equality
- Load from non-existent catalog returns Optional.empty()
- Save overwrites previous snapshot

**Step 2: Run test to verify it fails**

Run: `mvn test -pl andersoni-snapshot-fs -Dtest=FileSystemSnapshotStoreTest -q`
Expected: FAIL

**Step 3: Implement FileSystemSnapshotStore**

Writes files to `{baseDir}/{catalogName}/snapshot.dat` with metadata in `snapshot.meta` (JSON with hash, version, createdAt).

**Step 4: Run tests to verify they pass**

Run: `mvn test -pl andersoni-snapshot-fs -Dtest=FileSystemSnapshotStoreTest -q`
Expected: PASS

**Step 5: Commit**

```
feat: add FileSystemSnapshotStore for local/dev snapshot persistence
```

---

## Phase 8: Integration Test — Full Lifecycle

### Task 16: End-to-end integration test with core + filesystem store

**Files:**
- Test: `andersoni-core/src/test/java/org/waabox/andersoni/AndersoniIntegrationTest.java`

**Step 1: Write integration test**

Full lifecycle:
1. Create Andersoni with SingleNodeLeaderElection (no sync, no snapshot store)
2. Register a catalog with DataLoader
3. Start → verify bootstrap loaded data
4. Search → verify results
5. Refresh → verify new data
6. Stop

**Step 2: Run test**

Run: `mvn test -pl andersoni-core -Dtest=AndersoniIntegrationTest -q`
Expected: PASS

**Step 3: Commit**

```
test: add end-to-end integration test for Andersoni lifecycle
```

---

## Phase 9: Plugin Modules (can be done in parallel)

### Task 17: andersoni-sync-kafka — KafkaSyncStrategy

**Files:**
- Create: `andersoni-sync-kafka/src/main/java/org/waabox/andersoni/sync/kafka/KafkaSyncStrategy.java`
- Create: `andersoni-sync-kafka/src/main/java/org/waabox/andersoni/sync/kafka/KafkaSyncConfig.java`
- Test: `andersoni-sync-kafka/src/test/java/org/waabox/andersoni/sync/kafka/KafkaSyncStrategyTest.java`

Uses Kafka Producer/Consumer. Publishes RefreshEvent as JSON to configurable topic. Each instance uses a unique consumer group (broadcast pattern).

### Task 18: andersoni-sync-http — HttpSyncStrategy

**Files:**
- Create: `andersoni-sync-http/src/main/java/org/waabox/andersoni/sync/http/HttpSyncStrategy.java`
- Create: `andersoni-sync-http/src/main/java/org/waabox/andersoni/sync/http/HttpSyncConfig.java`
- Test: `andersoni-sync-http/src/test/java/org/waabox/andersoni/sync/http/HttpSyncStrategyTest.java`

Uses `java.net.http.HttpClient` (built-in Java 21). Calls `/andersoni/refresh` on peer nodes. Exposes a lightweight HTTP server on a configurable port to receive events.

### Task 19: andersoni-sync-db — DbPollingSyncStrategy

**Files:**
- Create: `andersoni-sync-db/src/main/java/org/waabox/andersoni/sync/db/DbPollingSyncStrategy.java`
- Create: `andersoni-sync-db/src/main/java/org/waabox/andersoni/sync/db/DbPollingSyncConfig.java`
- Test: `andersoni-sync-db/src/test/java/org/waabox/andersoni/sync/db/DbPollingSyncStrategyTest.java`

Uses JDBC. Table `andersoni_sync_log`. Leader polls periodically. On change detection, triggers refresh on local node which then propagates.

### Task 20: andersoni-leader-k8s — K8sLeaseLeaderElection

**Files:**
- Create: `andersoni-leader-k8s/src/main/java/org/waabox/andersoni/leader/k8s/K8sLeaseLeaderElection.java`
- Create: `andersoni-leader-k8s/src/main/java/org/waabox/andersoni/leader/k8s/K8sLeaseConfig.java`
- Test: `andersoni-leader-k8s/src/test/java/org/waabox/andersoni/leader/k8s/K8sLeaseLeaderElectionTest.java`

Uses Kubernetes Java client Lease API. Configurable lease name, namespace, renewal interval.

### Task 21: andersoni-snapshot-s3 — S3SnapshotStore

**Files:**
- Create: `andersoni-snapshot-s3/src/main/java/org/waabox/andersoni/snapshot/s3/S3SnapshotStore.java`
- Create: `andersoni-snapshot-s3/src/main/java/org/waabox/andersoni/snapshot/s3/S3SnapshotConfig.java`
- Test: `andersoni-snapshot-s3/src/test/java/org/waabox/andersoni/snapshot/s3/S3SnapshotStoreTest.java`

Uses AWS SDK v2. Stores to `{bucket}/{prefix}/{catalogName}/snapshot.dat`. Metadata in S3 object metadata headers.

---

## Phase 10: Spring Boot Starter

### Task 22: Auto-configuration and CatalogRegistrar

**Files:**
- Create: `andersoni-spring-boot-starter/src/main/java/org/waabox/andersoni/spring/AndersoniAutoConfiguration.java`
- Create: `andersoni-spring-boot-starter/src/main/java/org/waabox/andersoni/spring/AndersoniProperties.java`
- Create: `andersoni-spring-boot-starter/src/main/java/org/waabox/andersoni/spring/CatalogRegistrar.java`
- Create: `andersoni-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `andersoni-spring-boot-starter/src/test/java/org/waabox/andersoni/spring/AndersoniAutoConfigurationTest.java`

**Step 1: Create CatalogRegistrar**

```java
@FunctionalInterface
public interface CatalogRegistrar {
    void register(Andersoni andersoni);
}
```

**Step 2: Create AndersoniProperties** — maps `andersoni.*` from application.yml

**Step 3: Create AndersoniAutoConfiguration**

- Creates `Andersoni` bean with properties
- Auto-detects `SyncStrategy`, `LeaderElectionStrategy`, `SnapshotStore`, `AndersoniMetrics` beans
- Collects all `CatalogRegistrar` beans and registers catalogs
- Calls `andersoni.start()` on `@PostConstruct`
- Calls `andersoni.stop()` on `@PreDestroy`

**Step 4: Test with Spring Boot Test**

Verify that auto-configuration creates the Andersoni bean and registers catalogs.

**Step 5: Commit**

```
feat: add Spring Boot starter with auto-configuration
```

---

## Phase 11: Final Verification

### Task 23: Full build and test suite

**Step 1: Run full build**

Run: `mvn clean verify -q`
Expected: BUILD SUCCESS, all tests pass.

**Step 2: Review test coverage**

Ensure all core classes have tests: Snapshot, IndexDefinition, Catalog, Andersoni, RetryPolicy, SingleNodeLeaderElection, FileSystemSnapshotStore.

**Step 3: Final commit if any cleanup needed**

```
chore: final cleanup and verification
```
