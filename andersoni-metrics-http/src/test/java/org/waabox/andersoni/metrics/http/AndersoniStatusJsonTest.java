package org.waabox.andersoni.metrics.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.waabox.andersoni.Andersoni;
import org.waabox.andersoni.Catalog;
import org.waabox.andersoni.RetryPolicy;

/** Tests for {@link AndersoniStatusJson}. */
class AndersoniStatusJsonTest {

  /** A catalog item. */
  record Event(String id, String venue) {
  }

  /** A view of an event. */
  record EventSummary(String id) {
  }

  @Test
  void whenRendering_givenStartedCatalog_shouldReportNodeAndCatalogStatus() {
    final Andersoni andersoni = Andersoni.builder().nodeId("node-1").build();
    andersoni.register(eventsCatalog());
    andersoni.start();
    try {
      final JSONObject json = new JSONObject(
          AndersoniStatusJson.render(andersoni));

      assertEquals("node-1", json.getString("nodeId"));
      assertTrue(json.getBoolean("leader"));
      assertTrue(json.getBoolean("inSync"));

      final JSONObject catalog = json.getJSONArray("catalogs")
          .getJSONObject(0);
      assertEquals("events", catalog.getString("name"));
      assertTrue(catalog.getBoolean("available"));
      assertEquals(3, catalog.getInt("itemCount"));
      assertFalse(catalog.getString("hash").isEmpty());
      assertEquals("UNKNOWN", catalog.getString("syncState"));
      assertTrue(catalog.isNull("lastReconciledAt"));
      assertTrue(catalog.getLong("dataEstimatedSizeBytes") > 0);
      assertTrue(catalog.getLong("indexEstimatedSizeBytes") > 0);
      assertEquals(catalog.getLong("dataEstimatedSizeBytes")
              + catalog.getLong("indexEstimatedSizeBytes"),
          catalog.getLong("totalEstimatedSizeBytes"));
      assertEquals(List.of("EventSummary"),
          catalog.getJSONArray("views").toList());
    } finally {
      andersoni.stop();
    }
  }

  @Test
  void whenRendering_givenIndexedCatalog_shouldReportEveryIndex() {
    final Andersoni andersoni = Andersoni.builder().build();
    andersoni.register(eventsCatalog());
    andersoni.start();
    try {
      final JSONArray indices = AndersoniStatusJson.toJson(andersoni)
          .getJSONArray("catalogs").getJSONObject(0)
          .getJSONArray("indices");

      assertEquals(2, indices.length());
      final JSONObject byVenue = findIndex(indices, "by-venue");
      assertEquals(2, byVenue.getInt("uniqueKeys"));
      assertEquals(3, byVenue.getInt("totalEntries"));
      assertTrue(byVenue.getLong("estimatedSizeBytes") > 0);
      assertTrue(byVenue.getDouble("estimatedSizeMB") > 0);
      assertEquals(3, findIndex(indices, "by-id").getInt("uniqueKeys"));
    } finally {
      andersoni.stop();
    }
  }

  @Test
  void whenRendering_givenCatalogThatFailedToBootstrap_shouldReportItUnavailable() {
    final Andersoni andersoni = Andersoni.builder()
        .retryPolicy(RetryPolicy.of(1, Duration.ofMillis(1)))
        .build();
    andersoni.register(Catalog.of(Event.class)
        .named("broken")
        .loadWith(() -> {
          throw new IllegalStateException("source down");
        })
        .index("by-id").by(Event::id, id -> id)
        .build());
    andersoni.start();
    try {
      final JSONObject catalog = AndersoniStatusJson.toJson(andersoni)
          .getJSONArray("catalogs").getJSONObject(0);

      assertEquals("broken", catalog.getString("name"));
      assertFalse(catalog.getBoolean("available"));
      assertEquals(0, catalog.getInt("itemCount"));
      assertTrue(catalog.getJSONArray("indices").isEmpty());
    } finally {
      andersoni.stop();
    }
  }

  @Test
  void whenRendering_givenNoCatalogs_shouldReportEmptyCatalogList() {
    final Andersoni andersoni = Andersoni.builder().build();
    andersoni.start();
    try {
      final JSONObject json = AndersoniStatusJson.toJson(andersoni);

      assertTrue(json.getJSONArray("catalogs").isEmpty());
      assertTrue(json.getBoolean("inSync"));
    } finally {
      andersoni.stop();
    }
  }

  @Test
  void whenRendering_givenNullAndersoni_shouldThrow() {
    assertThrows(NullPointerException.class,
        () -> AndersoniStatusJson.render(null));
  }

  /**
   * Builds a catalog of three events over two venues, indexed by id and
   * by venue, with one view.
   *
   * @return the catalog, never null
   */
  private static Catalog<Event> eventsCatalog() {
    return Catalog.of(Event.class)
        .named("events")
        .data(List.of(
            new Event("e1", "river"),
            new Event("e2", "river"),
            new Event("e3", "boca")))
        .index("by-id").by(Event::id, id -> id)
        .index("by-venue").by(Event::venue, venue -> venue)
        .view(EventSummary.class, e -> new EventSummary(e.id()))
        .build();
  }

  /**
   * Finds an index by name in the rendered indices.
   *
   * @param indices the rendered indices, never null
   * @param name    the index name, never null
   *
   * @return the index's JSON, never null
   */
  private static JSONObject findIndex(final JSONArray indices,
      final String name) {
    for (int i = 0; i < indices.length(); i++) {
      final JSONObject index = indices.getJSONObject(i);
      if (name.equals(index.getString("name"))) {
        return index;
      }
    }
    throw new AssertionError("index not found: " + name);
  }
}
