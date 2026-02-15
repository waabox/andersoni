package org.waabox.andersoni.example.application;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.waabox.andersoni.Andersoni;
import org.waabox.andersoni.Snapshot;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** REST controller that exposes the Andersoni events catalog through
 * HTTP endpoints for searching, refreshing, and inspecting cache state.
 *
 * <p>This controller provides three endpoints:
 * <ul>
 *   <li>{@code GET /events/search} - searches the events catalog by
 *       index name and key</li>
 *   <li>{@code POST /events/refresh} - triggers a refresh and sync
 *       of the events catalog</li>
 *   <li>{@code GET /events/info} - returns metadata about the current
 *       snapshot including version, hash, and item count</li>
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
   * </ul>
   *
   * <p>If the events catalog is not found or has no snapshot, only
   * the {@code nodeId} is returned.
   *
   * @return a map containing the catalog metadata, never null
   */
  @GetMapping("/info")
  public Map<String, Object> info() {
    final Map<String, Object> result = new HashMap<>();
    result.put("nodeId", andersoni.nodeId());

    andersoni.catalogs().stream()
        .filter(c -> CATALOG_NAME.equals(c.name()))
        .findFirst()
        .ifPresent(catalog -> {
          final Snapshot<?> snapshot = catalog.currentSnapshot();
          if (snapshot != null) {
            result.put("version", snapshot.version());
            result.put("hash", snapshot.hash());
            result.put("createdAt", snapshot.createdAt());
            result.put("itemCount", snapshot.data().size());
          }
        });

    return result;
  }
}
