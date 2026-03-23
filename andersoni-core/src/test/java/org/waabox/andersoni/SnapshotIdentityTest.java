package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

class SnapshotIdentityTest {

  record Item(String id, String name) {}

  @Test
  void whenCreating_givenIdentityFunction_shouldBuildIdentityMap() {
    final Item a = new Item("1", "Alpha");
    final Item b = new Item("2", "Beta");

    final Snapshot<Item> snapshot = Snapshot.of(
        List.of(a, b), Collections.emptyMap(), 1L, "hash",
        (Function<Item, Object>) Item::id);

    assertEquals(a, snapshot.findById("1").orElse(null));
    assertEquals(b, snapshot.findById("2").orElse(null));
    assertTrue(snapshot.findById("3").isEmpty());
  }

  @Test
  void whenCreating_givenNoIdentityFunction_shouldHaveNoIdentityMap() {
    final Item a = new Item("1", "Alpha");
    final Snapshot<Item> snapshot = Snapshot.of(
        List.of(a), Collections.emptyMap(), 1L, "hash");

    assertTrue(snapshot.findById("1").isEmpty());
  }

  @Test
  void whenCreating_givenDuplicateKeys_shouldLastWriteWin() {
    final Item a1 = new Item("1", "First");
    final Item a2 = new Item("1", "Second");

    final Snapshot<Item> snapshot = Snapshot.of(
        List.of(a1, a2), Collections.emptyMap(), 1L, "hash",
        (Function<Item, Object>) Item::id);

    assertEquals("Second", ((Item) snapshot.findById("1").orElse(null)).name());
  }

  @Test
  void whenCreating_givenSixArgOverloadWithIdentity_shouldBuildIdentityMap() {
    final Item a = new Item("1", "Alpha");

    final Snapshot<Item> snapshot = Snapshot.of(
        List.of(a), Collections.emptyMap(),
        Collections.emptyNavigableMap(), Collections.emptyNavigableMap(),
        1L, "hash", (Function<Item, Object>) Item::id);

    assertEquals(a, snapshot.findById("1").orElse(null));
  }
}
