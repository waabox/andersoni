package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

class AndersoniCompoundTest {

  record Item(String country, String category) {}

  private Andersoni buildAndersoni(final List<Item> data) {
    final Catalog<Item> catalog = Catalog.of(Item.class)
        .named("items")
        .data(data)
        .index("by-country").by(Item::country, Function.identity())
        .index("by-category").by(Item::category, Function.identity())
        .build();
    final Andersoni andersoni = Andersoni.builder().nodeId("test-node").build();
    andersoni.register(catalog);
    andersoni.start();
    return andersoni;
  }

  @Test
  void whenCompound_givenTyped_shouldReturnResults() {
    final Item i1 = new Item("AR", "SPORTS");
    final Item i2 = new Item("AR", "MUSIC");
    final Item i3 = new Item("BR", "SPORTS");
    final Andersoni andersoni = buildAndersoni(List.of(i1, i2, i3));
    final List<Item> results = andersoni.compound("items", Item.class)
        .where("by-country").equalTo("AR")
        .and("by-category").equalTo("SPORTS")
        .execute();
    assertEquals(1, results.size());
    assertTrue(results.contains(i1));
    andersoni.stop();
  }

  @Test
  void whenCompound_givenWildcard_shouldReturnResults() {
    final Item i1 = new Item("AR", "SPORTS");
    final Andersoni andersoni = buildAndersoni(List.of(i1));
    final List<?> results = andersoni.compound("items")
        .where("by-country").equalTo("AR")
        .execute();
    assertEquals(1, results.size());
    andersoni.stop();
  }

  @Test
  void whenCompound_givenUnknownCatalog_shouldThrow() {
    final Andersoni andersoni = buildAndersoni(List.of());
    assertThrows(IllegalArgumentException.class, () -> andersoni.compound("unknown", Item.class));
    andersoni.stop();
  }

  @Test
  void whenCompound_givenNullCatalogName_shouldThrowNpe() {
    final Andersoni andersoni = buildAndersoni(List.of());
    assertThrows(NullPointerException.class, () -> andersoni.compound(null, Item.class));
    andersoni.stop();
  }
}
