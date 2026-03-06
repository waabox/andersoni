# andersoni-json-serializer Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Create a new Maven module that provides a generic Jackson-based `SnapshotSerializer<T>` implementation.

**Architecture:** Single class `JacksonSnapshotSerializer<T>` implementing `SnapshotSerializer<T>` from core. Uses Jackson `ObjectMapper` + `TypeReference<List<T>>` for type-safe serialization. Two constructors: one with full control, one convenience with default ObjectMapper + JavaTimeModule.

**Tech Stack:** Java 21, Jackson 2.18.2, JUnit 5, andersoni-core

---

### Task 1: Create module directory and pom.xml

**Files:**
- Create: `andersoni-json-serializer/pom.xml`
- Modify: `pom.xml` (parent — add module)

**Step 1: Create `andersoni-json-serializer/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>io.github.waabox</groupId>
    <artifactId>andersoni</artifactId>
    <version>1.2.1</version>
  </parent>

  <artifactId>andersoni-json-serializer</artifactId>

  <name>Andersoni JSON Serializer</name>
  <description>
    Jackson-based SnapshotSerializer implementation.
  </description>

  <dependencies>

    <dependency>
      <groupId>io.github.waabox</groupId>
      <artifactId>andersoni-core</artifactId>
    </dependency>

    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
    </dependency>

    <dependency>
      <groupId>com.fasterxml.jackson.datatype</groupId>
      <artifactId>jackson-datatype-jsr310</artifactId>
    </dependency>

    <!-- Test dependencies -->
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>

    <dependency>
      <groupId>org.slf4j</groupId>
      <artifactId>slf4j-simple</artifactId>
      <scope>test</scope>
    </dependency>

  </dependencies>

</project>
```

**Step 2: Add module to parent `pom.xml`**

In `pom.xml`, add `<module>andersoni-json-serializer</module>` after the `<module>andersoni-snapshot-fs</module>` line (line 49).

**Step 3: Verify the module compiles**

Run: `mvn -pl andersoni-json-serializer compile -q`
Expected: BUILD SUCCESS (empty module, no sources yet)

**Step 4: Commit**

```
Add andersoni-json-serializer module skeleton
```

---

### Task 2: Write tests for JacksonSnapshotSerializer

**Files:**
- Create: `andersoni-json-serializer/src/test/java/org/waabox/andersoni/snapshot/json/JacksonSnapshotSerializerTest.java`

**Step 1: Write the test class**

The test needs a simple record with a `LocalDateTime` field to exercise JavaTimeModule. Define it as a private inner record.

```java
package org.waabox.andersoni.snapshot.json;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

class JacksonSnapshotSerializerTest {

  record SampleItem(String id, String name, LocalDateTime createdAt) { }

  private static final TypeReference<List<SampleItem>> TYPE_REF =
      new TypeReference<>() { };

  @Test
  void whenSerializingAndDeserializing_givenValidItems_shouldRoundTrip() {
    final JacksonSnapshotSerializer<SampleItem> serializer =
        new JacksonSnapshotSerializer<>(TYPE_REF);

    final List<SampleItem> items = List.of(
        new SampleItem("1", "Alpha",
            LocalDateTime.of(2026, 3, 1, 10, 0)),
        new SampleItem("2", "Beta",
            LocalDateTime.of(2026, 3, 2, 14, 30))
    );

    final byte[] data = serializer.serialize(items);
    assertNotNull(data);
    assertTrue(data.length > 0);

    final List<SampleItem> restored = serializer.deserialize(data);
    assertEquals(2, restored.size());
    assertEquals("1", restored.get(0).id());
    assertEquals("Alpha", restored.get(0).name());
    assertEquals(LocalDateTime.of(2026, 3, 1, 10, 0),
        restored.get(0).createdAt());
    assertEquals("2", restored.get(1).id());
  }

  @Test
  void whenSerializingAndDeserializing_givenEmptyList_shouldRoundTrip() {
    final JacksonSnapshotSerializer<SampleItem> serializer =
        new JacksonSnapshotSerializer<>(TYPE_REF);

    final byte[] data = serializer.serialize(List.of());
    final List<SampleItem> restored = serializer.deserialize(data);
    assertTrue(restored.isEmpty());
  }

  @Test
  void whenSerializing_givenNullItems_shouldThrowNPE() {
    final JacksonSnapshotSerializer<SampleItem> serializer =
        new JacksonSnapshotSerializer<>(TYPE_REF);

    assertThrows(NullPointerException.class,
        () -> serializer.serialize(null));
  }

  @Test
  void whenDeserializing_givenNullData_shouldThrowNPE() {
    final JacksonSnapshotSerializer<SampleItem> serializer =
        new JacksonSnapshotSerializer<>(TYPE_REF);

    assertThrows(NullPointerException.class,
        () -> serializer.deserialize(null));
  }

  @Test
  void whenCreating_givenNullTypeReference_shouldThrowNPE() {
    assertThrows(NullPointerException.class,
        () -> new JacksonSnapshotSerializer<>(null));
  }

  @Test
  void whenCreating_givenNullMapperOrTypeRef_shouldThrowNPE() {
    assertThrows(NullPointerException.class,
        () -> new JacksonSnapshotSerializer<>(null, TYPE_REF));
    assertThrows(NullPointerException.class,
        () -> new JacksonSnapshotSerializer<>(new ObjectMapper(), null));
  }

  @Test
  void whenSerializingAndDeserializing_givenCustomObjectMapper_shouldWork() {
    final ObjectMapper custom = new ObjectMapper();
    custom.registerModule(
        new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    final JacksonSnapshotSerializer<SampleItem> serializer =
        new JacksonSnapshotSerializer<>(custom, TYPE_REF);

    final List<SampleItem> items = List.of(
        new SampleItem("x", "Custom",
            LocalDateTime.of(2026, 1, 1, 0, 0))
    );

    final byte[] data = serializer.serialize(items);
    final List<SampleItem> restored = serializer.deserialize(data);
    assertEquals(1, restored.size());
    assertEquals("Custom", restored.get(0).name());
  }
}
```

**Step 2: Run tests to verify they fail**

Run: `mvn -pl andersoni-json-serializer test -q`
Expected: COMPILATION FAILURE — `JacksonSnapshotSerializer` does not exist yet.

---

### Task 3: Implement JacksonSnapshotSerializer

**Files:**
- Create: `andersoni-json-serializer/src/main/java/org/waabox/andersoni/snapshot/json/JacksonSnapshotSerializer.java`

**Step 1: Write the implementation**

```java
package org.waabox.andersoni.snapshot.json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.waabox.andersoni.snapshot.SnapshotSerializer;

/** Jackson-based {@link SnapshotSerializer} implementation.
 *
 * <p>Serializes and deserializes lists of items to and from JSON byte
 * arrays using Jackson's {@link ObjectMapper}. Registers
 * {@link JavaTimeModule} by default to support {@code java.time} types.
 *
 * <p>Usage:
 * <pre>{@code
 * SnapshotSerializer<Event> serializer = new JacksonSnapshotSerializer<>(
 *     new TypeReference<List<Event>>() {}
 * );
 * }</pre>
 *
 * @param <T> the type of items this serializer handles
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public class JacksonSnapshotSerializer<T>
    implements SnapshotSerializer<T> {

  /** The Jackson object mapper, never null. */
  private final ObjectMapper mapper;

  /** The type reference for deserializing, never null. */
  private final TypeReference<List<T>> typeReference;

  /** Creates a new serializer with the given {@link ObjectMapper} and
   * type reference.
   *
   * @param theMapper the ObjectMapper to use, never null.
   * @param theTypeReference the type reference for deserialization,
   *     never null.
   */
  public JacksonSnapshotSerializer(final ObjectMapper theMapper,
      final TypeReference<List<T>> theTypeReference) {
    Objects.requireNonNull(theMapper, "mapper must not be null");
    Objects.requireNonNull(theTypeReference, "typeReference must not be null");
    mapper = theMapper;
    typeReference = theTypeReference;
  }

  /** Creates a new serializer with a default {@link ObjectMapper}
   * configured with {@link JavaTimeModule}.
   *
   * @param theTypeReference the type reference for deserialization,
   *     never null.
   */
  public JacksonSnapshotSerializer(
      final TypeReference<List<T>> theTypeReference) {
    this(defaultMapper(), theTypeReference);
  }

  /** {@inheritDoc} */
  @Override
  public byte[] serialize(final List<T> items) {
    Objects.requireNonNull(items, "items must not be null");
    try {
      return mapper.writeValueAsBytes(items);
    } catch (final IOException e) {
      throw new UncheckedIOException("Failed to serialize items", e);
    }
  }

  /** {@inheritDoc} */
  @Override
  public List<T> deserialize(final byte[] data) {
    Objects.requireNonNull(data, "data must not be null");
    try {
      return mapper.readValue(data, typeReference);
    } catch (final IOException e) {
      throw new UncheckedIOException("Failed to deserialize items", e);
    }
  }

  /** Creates a default ObjectMapper with JavaTimeModule registered.
   *
   * @return a new ObjectMapper, never null.
   */
  private static ObjectMapper defaultMapper() {
    final ObjectMapper mapper = new ObjectMapper();
    mapper.registerModule(new JavaTimeModule());
    return mapper;
  }
}
```

**Step 2: Run tests to verify they pass**

Run: `mvn -pl andersoni-json-serializer test -q`
Expected: BUILD SUCCESS, all 7 tests pass.

**Step 3: Commit**

```
Add JacksonSnapshotSerializer with tests
```

---

### Task 4: Full build verification

**Step 1: Run the full build**

Run: `mvn clean verify -q`
Expected: BUILD SUCCESS across all modules.

**Step 2: Commit (if any adjustments were needed)**

Only if changes were made during verification.
