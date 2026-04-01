# Async Refresh Dispatcher Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Decouple Kafka consumer thread from catalog refresh execution using virtual threads, with per-catalog serialization and event coalescing.

**Architecture:** Private `AsyncRefreshDispatcher` inner class in `Andersoni` that dispatches refresh work to virtual threads with `Semaphore(1)` per catalog for serialization and `AtomicBoolean` per catalog for coalescing duplicate events.

**Tech Stack:** Java 21 virtual threads, JUnit 5, EasyMock.

---

## File Structure

### andersoni-core (modify)
- `src/main/java/org/waabox/andersoni/Andersoni.java` — add `AsyncRefreshDispatcher` inner class, wire into `start()` and `wireSyncListener()`
- `src/test/java/org/waabox/andersoni/AndersoniTest.java` — add tests for async dispatch behavior

---

## Task 1: Add AsyncRefreshDispatcher inner class and wire it

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/Andersoni.java`
- Modify: `andersoni-core/src/test/java/org/waabox/andersoni/AndersoniTest.java`

- [ ] **Step 1: Read current Andersoni.java and AndersoniTest.java**

Understand the current `wireSyncListener()`, `refreshFromEvent()`, and test patterns.

- [ ] **Step 2: Add AsyncRefreshDispatcher inner class**

Add as a private static inner class at the bottom of `Andersoni.java`, before the `Builder` class:

```java
/** Dispatches catalog refresh operations to virtual threads with
 * per-catalog serialization and event coalescing.
 *
 * <p>Each catalog gets its own {@link Semaphore} (1 permit) to ensure
 * that refreshes for the same catalog execute serially. An
 * {@link AtomicBoolean} per catalog tracks whether a refresh is already
 * pending, enabling coalescing: if a refresh is queued or running for a
 * catalog, subsequent events for that catalog are discarded because
 * {@code refreshFromEvent} always loads the latest data.
 *
 * <p>Uses Java 21 virtual threads for lightweight async execution.
 */
private static final class AsyncRefreshDispatcher {

  private static final Logger log = LoggerFactory.getLogger(
      AsyncRefreshDispatcher.class);

  private final Map<String, Semaphore> semaphores;
  private final Map<String, AtomicBoolean> refreshPending;

  AsyncRefreshDispatcher(final Set<String> catalogNames) {
    final Map<String, Semaphore> sems = new HashMap<>();
    final Map<String, AtomicBoolean> pending = new HashMap<>();
    for (final String name : catalogNames) {
      sems.put(name, new Semaphore(1));
      pending.put(name, new AtomicBoolean(false));
    }
    semaphores = Collections.unmodifiableMap(sems);
    refreshPending = Collections.unmodifiableMap(pending);
  }

  void dispatch(final String catalogName, final Runnable refreshTask) {
    final AtomicBoolean pending = refreshPending.get(catalogName);
    if (pending == null) {
      log.warn("No dispatcher configured for catalog '{}'", catalogName);
      return;
    }

    if (!pending.compareAndSet(false, true)) {
      log.debug("Refresh already pending for catalog '{}', coalescing",
          catalogName);
      return;
    }

    Thread.startVirtualThread(() -> {
      final Semaphore semaphore = semaphores.get(catalogName);
      try {
        semaphore.acquire();
        try {
          refreshTask.run();
        } finally {
          pending.set(false);
          semaphore.release();
        }
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        pending.set(false);
        log.warn("Refresh interrupted for catalog '{}'", catalogName);
      }
    });
  }
}
```

- [ ] **Step 3: Add dispatcher field and initialize in start()**

Add field to `Andersoni`:
```java
/** The async refresh dispatcher for sync events. */
private AsyncRefreshDispatcher asyncRefreshDispatcher;
```

In `start()`, after `bootstrapAllCatalogs()` and before `wireSyncListener()`, add:
```java
asyncRefreshDispatcher = new AsyncRefreshDispatcher(catalogsByName.keySet());
```

- [ ] **Step 4: Update wireSyncListener() to use dispatcher**

Replace the direct call:
```java
metrics.syncReceived(event.catalogName());
refreshFromEvent(event.catalogName(), catalog);
```

With:
```java
metrics.syncReceived(event.catalogName());
asyncRefreshDispatcher.dispatch(event.catalogName(),
    () -> refreshFromEvent(event.catalogName(), catalog));
```

- [ ] **Step 5: Run tests**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-core`
Expected: all existing tests PASS. Some tests may need adjustment if they verify synchronous refresh behavior after sync events.

- [ ] **Step 6: Add test for coalescing behavior**

Add a test to `AndersoniTest.java` that verifies coalescing: when two rapid sync events arrive for the same catalog, only one refresh is executed (or at most the second one is coalesced). This may require using a `CountDownLatch` or similar mechanism to control timing.

- [ ] **Step 7: Run full build**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn clean verify`
Expected: BUILD SUCCESS across all modules.

- [ ] **Step 8: Commit**

```bash
git add andersoni-core/src/main/java/org/waabox/andersoni/Andersoni.java
git add andersoni-core/src/test/java/org/waabox/andersoni/AndersoniTest.java
git commit -m "Add AsyncRefreshDispatcher with virtual threads and coalescing"
```
