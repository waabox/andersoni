package org.waabox.andersoni.example.application;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.waabox.andersoni.Andersoni;
import org.waabox.andersoni.CatalogInfo;
import org.waabox.andersoni.IndexInfo;
import org.waabox.andersoni.QueryStep;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** REST controller that exposes the Andersoni events catalog through
 * HTTP endpoints for searching, querying, refreshing, and inspecting
 * cache state.
 *
 * <p>This controller provides endpoints for:
 * <ul>
 *   <li>{@code GET /events/search} - equality search by index and key</li>
 *   <li>{@code GET /events/query/between} - date range queries on the
 *       by-start-time sorted index</li>
 *   <li>{@code GET /events/query/after} - events after a given date</li>
 *   <li>{@code GET /events/query/from} - events from a given date
 *       (inclusive)</li>
 *   <li>{@code GET /events/query/before} - events before a given date</li>
 *   <li>{@code GET /events/query/until} - events until a given date
 *       (inclusive)</li>
 *   <li>{@code GET /events/query/starts-with} - text prefix search on
 *       the by-name sorted index</li>
 *   <li>{@code GET /events/query/ends-with} - text suffix search on
 *       the by-name sorted index</li>
 *   <li>{@code GET /events/query/contains} - text substring search on
 *       the by-name sorted index</li>
 *   <li>{@code POST /events/refresh} - triggers a refresh and sync</li>
 *   <li>{@code GET /events/info} - snapshot metadata</li>
 * </ul>
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
@RestController
@RequestMapping("/events")
public class EventController {

  /** The name of the events catalog registered with Andersoni. */
  private static final String CATALOG_NAME = "events";

  /** The Andersoni instance managing the in-memory cache, never null. */
  private final Andersoni andersoni;

  /** Creates a new EventController.
   *
   * @param theAndersoni the Andersoni instance to delegate operations to,
   *        never null
   */
  public EventController(final Andersoni theAndersoni) {
    andersoni = Objects.requireNonNull(theAndersoni,
        "andersoni cannot be null");
  }

  /** Searches the events catalog by index name and key.
   *
   * <p>Delegates to {@link Andersoni#search(String, String, Object)} using
   * the events catalog name. Valid index names include "by-sport",
   * "by-venue", and "by-status".
   *
   * @param index the name of the index to search (e.g. "by-sport"),
   *        never null
   * @param key the value to look up in the index (e.g. "FOOTBALL"),
   *        never null
   *
   * @return the list of matching events, never null
   */
  @GetMapping("/search")
  public List<?> search(
      @RequestParam("index") final String index,
      @RequestParam("key") final String key) {
    return andersoni.search(CATALOG_NAME, index, key);
  }

  // -- Date range queries on the by-start-time sorted index -----------

  /** Returns events whose start time falls between two dates (inclusive).
   *
   * @param from the start of the date range in ISO format, never null
   * @param to the end of the date range in ISO format, never null
   *
   * @return the list of matching events, never null
   */
  @GetMapping("/query/between")
  public List<?> between(
      @RequestParam("from") final LocalDateTime from,
      @RequestParam("to") final LocalDateTime to) {
    return andersoni.query(CATALOG_NAME, "by-start-time")
        .between(from, to);
  }

  /** Returns events whose start time is strictly after the given date.
   *
   * @param date the reference date in ISO format, never null
   *
   * @return the list of matching events, never null
   */
  @GetMapping("/query/after")
  public List<?> after(@RequestParam("date") final LocalDateTime date) {
    return andersoni.query(CATALOG_NAME, "by-start-time")
        .greaterThan(date);
  }

  /** Returns events whose start time is on or after the given date.
   *
   * @param date the reference date in ISO format, never null
   *
   * @return the list of matching events, never null
   */
  @GetMapping("/query/from")
  public List<?> from(@RequestParam("date") final LocalDateTime date) {
    return andersoni.query(CATALOG_NAME, "by-start-time")
        .greaterOrEqual(date);
  }

  /** Returns events whose start time is strictly before the given date.
   *
   * @param date the reference date in ISO format, never null
   *
   * @return the list of matching events, never null
   */
  @GetMapping("/query/before")
  public List<?> before(@RequestParam("date") final LocalDateTime date) {
    return andersoni.query(CATALOG_NAME, "by-start-time")
        .lessThan(date);
  }

  /** Returns events whose start time is on or before the given date.
   *
   * @param date the reference date in ISO format, never null
   *
   * @return the list of matching events, never null
   */
  @GetMapping("/query/until")
  public List<?> until(@RequestParam("date") final LocalDateTime date) {
    return andersoni.query(CATALOG_NAME, "by-start-time")
        .lessOrEqual(date);
  }

  // -- Text search queries on the by-name sorted index ----------------

  /** Returns events whose name starts with the given prefix.
   *
   * @param prefix the prefix to match against event names, never null
   *
   * @return the list of matching events, never null
   */
  @GetMapping("/query/starts-with")
  public List<?> startsWith(
      @RequestParam("prefix") final String prefix) {
    return andersoni.query(CATALOG_NAME, "by-name")
        .startsWith(prefix);
  }

  /** Returns events whose name ends with the given suffix.
   *
   * @param suffix the suffix to match against event names, never null
   *
   * @return the list of matching events, never null
   */
  @GetMapping("/query/ends-with")
  public List<?> endsWith(
      @RequestParam("suffix") final String suffix) {
    return andersoni.query(CATALOG_NAME, "by-name")
        .endsWith(suffix);
  }

  /** Returns events whose name contains the given substring.
   *
   * @param substring the substring to match against event names,
   *        never null
   *
   * @return the list of matching events, never null
   */
  @GetMapping("/query/contains")
  public List<?> contains(
      @RequestParam("substring") final String substring) {
    return andersoni.query(CATALOG_NAME, "by-name")
        .contains(substring);
  }

  /** Triggers a refresh and sync of the events catalog.
   *
   * <p>Reloads the catalog data from the database, updates the local
   * snapshot, and publishes a sync event to notify other nodes in the
   * cluster.
   *
   * @return a map containing the status "refreshed", never null
   */
  @PostMapping("/refresh")
  public Map<String, Object> refresh() {
    andersoni.refreshAndSync(CATALOG_NAME);
    return Map.of("status", "refreshed");
  }

  /** Returns metadata about the current state of the events catalog.
   *
   * <p>The response includes:
   * <ul>
   *   <li>{@code nodeId} - the identifier of this Andersoni node</li>
   *   <li>{@code version} - the current snapshot version number</li>
   *   <li>{@code hash} - the content hash of the current snapshot</li>
   *   <li>{@code createdAt} - when the current snapshot was created</li>
   *   <li>{@code itemCount} - the number of events in the snapshot</li>
   *   <li>{@code indices} - per-index statistics including estimated
   *       memory size in MB</li>
   *   <li>{@code totalEstimatedSizeMB} - total estimated memory for all
   *       indices</li>
   * </ul>
   *
   * <p>If the events catalog is not found, only the {@code nodeId} is
   * returned.
   *
   * @return a map containing the catalog metadata, never null
   */
  @GetMapping("/info")
  public Map<String, Object> info() {
    final Map<String, Object> result = new LinkedHashMap<>();
    result.put("nodeId", andersoni.nodeId());

    andersoni.catalogs().stream()
        .filter(c -> CATALOG_NAME.equals(c.name()))
        .findFirst()
        .ifPresent(catalog -> {
          final CatalogInfo catalogInfo = catalog.info();
          result.put("version", catalog.currentSnapshot().version());
          result.put("hash", catalog.currentSnapshot().hash());
          result.put("createdAt", catalog.currentSnapshot().createdAt());
          result.put("itemCount", catalogInfo.itemCount());
          result.put("totalEstimatedSizeMB",
              catalogInfo.totalEstimatedSizeMB());

          final List<Map<String, Object>> indexList = new ArrayList<>();
          for (final IndexInfo idx : catalogInfo.indices()) {
            final Map<String, Object> indexMap = new LinkedHashMap<>();
            indexMap.put("name", idx.name());
            indexMap.put("uniqueKeys", idx.uniqueKeys());
            indexMap.put("totalEntries", idx.totalEntries());
            indexMap.put("estimatedSizeMB", idx.estimatedSizeMB());
            indexList.add(indexMap);
          }
          result.put("indices", indexList);
        });

    return result;
  }
}
