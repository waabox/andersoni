package org.waabox.andersoni.metrics.http;

import java.util.Objects;

import org.json.JSONArray;
import org.json.JSONObject;
import org.waabox.andersoni.Andersoni;
import org.waabox.andersoni.AndersoniStatus;
import org.waabox.andersoni.CatalogInfo;
import org.waabox.andersoni.IndexInfo;

/**
 * Renders an Andersoni node's status as JSON, for HTTP status endpoints.
 *
 * <p>The document carries everything {@link Andersoni#status()} reports,
 * plus, per catalog, the registered view types and the statistics of every
 * index taken from {@link Andersoni#info(String)}:
 *
 * <pre>{@code
 * {
 *   "nodeId": "node-1",
 *   "leader": true,
 *   "inSync": true,
 *   "catalogs": [
 *     {
 *       "name": "events",
 *       "available": true,
 *       "version": 3,
 *       "hash": "9f2c...",
 *       "hashComparable": true,
 *       "itemCount": 1200,
 *       "estimatedSizeMB": 1.4,
 *       "dataEstimatedSizeBytes": 1376256,
 *       "indexEstimatedSizeBytes": 98304,
 *       "totalEstimatedSizeBytes": 1474560,
 *       "syncState": "IN_SYNC",
 *       "lastReconciledAt": "2026-11-17T15:00:00Z",
 *       "views": ["EventSummary"],
 *       "indices": [
 *         {
 *           "name": "by-venue",
 *           "uniqueKeys": 40,
 *           "totalEntries": 1200,
 *           "estimatedSizeBytes": 98304,
 *           "estimatedSizeMB": 0.09
 *         }
 *       ]
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <p>{@code estimatedSizeMB} is the catalog's whole footprint, items plus
 * indices; the three {@code *EstimatedSizeBytes} fields split it into the
 * items and the index structures. {@code lastReconciledAt} is {@code null}
 * when reconciliation has not run for the catalog. A catalog whose
 * statistics cannot be read, for example one that never bootstrapped, is
 * reported with empty {@code views} and {@code indices} and {@code null}
 * byte sizes instead of failing the whole document.
 *
 * <p>Status and index statistics are read one after the other, so a
 * refresh in between can make them describe consecutive snapshots.
 *
 * <p>Sizes are estimated once per snapshot and cached, so the first render
 * after a refresh walks the new snapshot's object graph and later renders
 * are cheap.
 */
public final class AndersoniStatusJson {

  /** Utility class, not instantiable. */
  private AndersoniStatusJson() {
  }

  /**
   * Renders the status of the given node as a JSON string.
   *
   * @param andersoni the node to report on, never null
   *
   * @return the status document, never null
   *
   * @throws NullPointerException if andersoni is null
   */
  public static String render(final Andersoni andersoni) {
    return toJson(andersoni).toString();
  }

  /**
   * Builds the status document of the given node.
   *
   * @param andersoni the node to report on, never null
   *
   * @return a new JSON object with the node's status, never null
   *
   * @throws NullPointerException if andersoni is null
   */
  public static JSONObject toJson(final Andersoni andersoni) {
    Objects.requireNonNull(andersoni, "andersoni must not be null");
    final AndersoniStatus status = andersoni.status();
    final JSONArray catalogs = new JSONArray();
    for (final AndersoniStatus.CatalogStatus catalog : status.catalogs()) {
      catalogs.put(catalogJson(andersoni, catalog));
    }
    return new JSONObject()
        .put("nodeId", status.nodeId())
        .put("leader", status.leader())
        .put("inSync", status.inSync())
        .put("catalogs", catalogs);
  }

  /**
   * Builds the JSON of one catalog: its status plus its views and indices.
   *
   * @param andersoni the node owning the catalog, never null
   * @param status    the catalog's status, never null
   *
   * @return the catalog's JSON, never null
   */
  private static JSONObject catalogJson(final Andersoni andersoni,
      final AndersoniStatus.CatalogStatus status) {
    final JSONArray views = new JSONArray();
    final JSONArray indices = new JSONArray();
    Object dataBytes = JSONObject.NULL;
    Object indexBytes = JSONObject.NULL;
    Object totalBytes = JSONObject.NULL;
    final CatalogInfo info = readInfo(andersoni, status.catalogName());
    if (info != null) {
      info.viewTypeNames().forEach(views::put);
      for (final IndexInfo index : info.indices()) {
        indices.put(indexJson(index));
      }
      dataBytes = info.dataEstimatedSizeBytes();
      indexBytes = info.indexEstimatedSizeBytes();
      totalBytes = info.totalEstimatedSizeBytes();
    }
    return new JSONObject()
        .put("name", status.catalogName())
        .put("available", status.available())
        .put("version", status.version())
        .put("hash", status.hash())
        .put("hashComparable", status.hashComparable())
        .put("itemCount", status.itemCount())
        .put("estimatedSizeMB", status.estimatedSizeMB())
        .put("dataEstimatedSizeBytes", dataBytes)
        .put("indexEstimatedSizeBytes", indexBytes)
        .put("totalEstimatedSizeBytes", totalBytes)
        .put("syncState", status.syncState().name())
        .put("lastReconciledAt", status.lastReconciledAt()
            .<Object>map(Object::toString).orElse(JSONObject.NULL))
        .put("views", views)
        .put("indices", indices);
  }

  /**
   * Reads a catalog's statistics, degrading to null when the catalog has
   * no readable snapshot.
   *
   * @param andersoni   the node owning the catalog, never null
   * @param catalogName the catalog's name, never null
   *
   * @return the catalog's statistics, or null if they cannot be read
   */
  private static CatalogInfo readInfo(final Andersoni andersoni,
      final String catalogName) {
    try {
      return andersoni.info(catalogName);
    } catch (final RuntimeException e) {
      return null;
    }
  }

  /**
   * Builds the JSON of one index.
   *
   * @param index the index statistics, never null
   *
   * @return the index's JSON, never null
   */
  private static JSONObject indexJson(final IndexInfo index) {
    return new JSONObject()
        .put("name", index.name())
        .put("uniqueKeys", index.uniqueKeys())
        .put("totalEntries", index.totalEntries())
        .put("estimatedSizeBytes", index.estimatedSizeBytes())
        .put("estimatedSizeMB", index.estimatedSizeMB());
  }
}
