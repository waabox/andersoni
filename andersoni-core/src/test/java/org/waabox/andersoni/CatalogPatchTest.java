package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class CatalogPatchTest {

  record Item(String id, String value) {}

  private Catalog<Item> buildCatalog(final List<Item> data) {
    final Catalog<Item> catalog = Catalog.of(Item.class)
        .named("items")
        .data(data)
        .identifiedBy(Item::id)
        .index("by-value").by(Item::value)
        .build();
    catalog.bootstrap();
    return catalog;
  }

  private Catalog<Item> buildCatalogWithoutIdentity(final List<Item> data) {
    final Catalog<Item> catalog = Catalog.of(Item.class)
        .named("items")
        .data(data)
        .index("by-value").by(Item::value)
        .build();
    catalog.bootstrap();
    return catalog;
  }

  // --- add ---

  @Test
  void whenAdding_givenNewItem_shouldAddToSnapshot() {
    final Catalog<Item> catalog = buildCatalog(
        List.of(new Item("1", "a")));
    catalog.add(new Item("2", "b"));
    assertEquals(2, catalog.currentSnapshot().data().size());
    assertTrue(catalog.findById("2").isPresent());
  }

  @Test
  void whenAdding_givenDuplicateKey_shouldThrow() {
    final Catalog<Item> catalog = buildCatalog(
        List.of(new Item("1", "a")));
    assertThrows(IllegalArgumentException.class,
        () -> catalog.add(new Item("1", "b")));
  }

  @Test
  void whenAdding_givenNoIdentityFunction_shouldThrow() {
    final Catalog<Item> catalog = buildCatalogWithoutIdentity(
        List.of(new Item("1", "a")));
    assertThrows(IllegalStateException.class,
        () -> catalog.add(new Item("2", "b")));
  }

  @Test
  void whenAddingCollection_givenAllNew_shouldAddAll() {
    final Catalog<Item> catalog = buildCatalog(List.of(new Item("1", "a")));
    catalog.add(List.of(new Item("2", "b"), new Item("3", "c")));
    assertEquals(3, catalog.currentSnapshot().data().size());
  }

  @Test
  void whenAddingCollection_givenOneDuplicate_shouldThrowAndNotApply() {
    final Catalog<Item> catalog = buildCatalog(
        List.of(new Item("1", "a")));
    assertThrows(IllegalArgumentException.class,
        () -> catalog.add(List.of(new Item("2", "b"), new Item("1", "dup"))));
    assertEquals(1, catalog.currentSnapshot().data().size());
  }

  // --- update ---

  @Test
  void whenUpdating_givenExistingKey_shouldReplace() {
    final Catalog<Item> catalog = buildCatalog(
        List.of(new Item("1", "old")));
    catalog.update(new Item("1", "new"));
    assertEquals(1, catalog.currentSnapshot().data().size());
    assertEquals("new", catalog.findById("1").map(Item::value).orElse(null));
  }

  @Test
  void whenUpdating_givenMissingKey_shouldThrow() {
    final Catalog<Item> catalog = buildCatalog(
        List.of(new Item("1", "a")));
    assertThrows(IllegalArgumentException.class,
        () -> catalog.update(new Item("2", "b")));
  }

  @Test
  void whenUpdatingCollection_givenAllExist_shouldUpdateAll() {
    final Catalog<Item> catalog = buildCatalog(
        List.of(new Item("1", "a"), new Item("2", "b")));
    catalog.update(List.of(new Item("1", "x"), new Item("2", "y")));
    assertEquals("x", catalog.findById("1").map(Item::value).orElse(null));
    assertEquals("y", catalog.findById("2").map(Item::value).orElse(null));
  }

  @Test
  void whenUpdatingCollection_givenOneMissing_shouldThrowAndNotApply() {
    final Catalog<Item> catalog = buildCatalog(
        List.of(new Item("1", "a")));
    assertThrows(IllegalArgumentException.class,
        () -> catalog.update(List.of(new Item("1", "x"), new Item("99", "z"))));
    assertEquals("a", catalog.findById("1").map(Item::value).orElse(null));
  }

  // --- upsert ---

  @Test
  void whenUpserting_givenNewItem_shouldAdd() {
    final Catalog<Item> catalog = buildCatalog(
        List.of(new Item("1", "a")));
    catalog.upsert(new Item("2", "b"));
    assertEquals(2, catalog.currentSnapshot().data().size());
  }

  @Test
  void whenUpserting_givenExistingKey_shouldReplace() {
    final Catalog<Item> catalog = buildCatalog(
        List.of(new Item("1", "old")));
    catalog.upsert(new Item("1", "new"));
    assertEquals(1, catalog.currentSnapshot().data().size());
    assertEquals("new", catalog.findById("1").map(Item::value).orElse(null));
  }

  @Test
  void whenUpsertingCollection_givenMixOfNewAndExisting_shouldAddAndReplace() {
    final Catalog<Item> catalog = buildCatalog(
        List.of(new Item("1", "old")));
    catalog.upsert(List.of(new Item("1", "updated"), new Item("2", "new")));
    assertEquals(2, catalog.currentSnapshot().data().size());
    assertEquals("updated", catalog.findById("1").map(Item::value).orElse(null));
    assertEquals("new", catalog.findById("2").map(Item::value).orElse(null));
  }

  // --- remove ---

  @Test
  void whenRemoving_givenExistingKey_shouldRemove() {
    final Catalog<Item> catalog = buildCatalog(
        List.of(new Item("1", "a"), new Item("2", "b")));
    catalog.remove(new Item("1", "a"));
    assertEquals(1, catalog.currentSnapshot().data().size());
    assertTrue(catalog.findById("1").isEmpty());
  }

  @Test
  void whenRemoving_givenMissingKey_shouldThrow() {
    final Catalog<Item> catalog = buildCatalog(
        List.of(new Item("1", "a")));
    assertThrows(IllegalArgumentException.class,
        () -> catalog.remove(new Item("99", "x")));
  }

  @Test
  void whenRemovingCollection_givenAllExist_shouldRemoveAll() {
    final Catalog<Item> catalog = buildCatalog(
        List.of(new Item("1", "a"), new Item("2", "b"), new Item("3", "c")));
    catalog.remove(List.of(new Item("1", "a"), new Item("2", "b")));
    assertEquals(1, catalog.currentSnapshot().data().size());
    assertTrue(catalog.findById("3").isPresent());
  }

  @Test
  void whenRemovingCollection_givenOneMissing_shouldThrowAndNotApply() {
    final Catalog<Item> catalog = buildCatalog(
        List.of(new Item("1", "a"), new Item("2", "b")));
    assertThrows(IllegalArgumentException.class,
        () -> catalog.remove(List.of(new Item("1", "a"), new Item("99", "x"))));
    assertEquals(2, catalog.currentSnapshot().data().size());
  }

  // --- findById ---

  @Test
  void whenFindingById_givenExistingKey_shouldReturn() {
    final Catalog<Item> catalog = buildCatalog(
        List.of(new Item("1", "a")));
    assertEquals("a", catalog.findById("1").map(Item::value).orElse(null));
  }

  @Test
  void whenFindingById_givenNoIdentity_shouldReturnEmpty() {
    final Catalog<Item> catalog = buildCatalogWithoutIdentity(
        List.of(new Item("1", "a")));
    assertTrue(catalog.findById("1").isEmpty());
  }

  // --- intra-batch duplicate detection ---

  @Test
  void whenAddingCollection_givenDuplicateKeysInBatch_shouldThrow() {
    final Catalog<Item> catalog = buildCatalog(List.of());
    assertThrows(IllegalArgumentException.class,
        () -> catalog.add(List.of(new Item("1", "a"), new Item("1", "b"))));
    assertEquals(0, catalog.currentSnapshot().data().size());
  }

  @Test
  void whenUpdatingCollection_givenDuplicateKeysInBatch_shouldThrow() {
    final Catalog<Item> catalog = buildCatalog(
        List.of(new Item("1", "a")));
    assertThrows(IllegalArgumentException.class,
        () -> catalog.update(
            List.of(new Item("1", "x"), new Item("1", "y"))));
    assertEquals("a", catalog.findById("1").map(Item::value).orElse(null));
  }

  @Test
  void whenRemovingCollection_givenDuplicateKeysInBatch_shouldThrow() {
    final Catalog<Item> catalog = buildCatalog(
        List.of(new Item("1", "a"), new Item("2", "b")));
    assertThrows(IllegalArgumentException.class,
        () -> catalog.remove(
            List.of(new Item("1", "a"), new Item("1", "a"))));
    assertEquals(2, catalog.currentSnapshot().data().size());
  }

  // --- index integrity after patch ---

  @Test
  void whenAdding_givenIndex_shouldUpdateIndex() {
    final Catalog<Item> catalog = buildCatalog(
        List.of(new Item("1", "a")));
    catalog.add(new Item("2", "b"));
    final List<Item> results = catalog.search("by-value", "b");
    assertEquals(1, results.size());
    assertEquals("2", results.get(0).id());
  }
}
