package org.waabox.andersoni;

import java.util.List;
import java.util.Objects;

/**
 * A read-only snapshot of this node's operational state, suitable for health
 * endpoints, dashboards, and cross-node convergence checks.
 *
 * <p>Obtained via {@link Andersoni#status()}. It answers the questions that
 * matter operationally in a multi-node cache: is this node the leader, and for
 * each catalog, what version/hash is it at, how many items does it hold, and
 * how much memory do they use — so tooling can verify all nodes converged
 * without each reinventing an inspection endpoint.
 *
 * @param nodeId   this node's identifier, never null
 * @param leader   whether this node is currently the leader
 * @param catalogs per-catalog status, one entry per registered catalog,
 *                 never null
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public record AndersoniStatus(
    String nodeId,
    boolean leader,
    List<CatalogStatus> catalogs) {

  /**
   * Canonical constructor validating and defensively copying inputs.
   *
   * @param nodeId   this node's identifier, never null.
   * @param leader   whether this node is currently the leader.
   * @param catalogs per-catalog status, never null.
   */
  public AndersoniStatus {
    Objects.requireNonNull(nodeId, "nodeId must not be null");
    Objects.requireNonNull(catalogs, "catalogs must not be null");
    catalogs = List.copyOf(catalogs);
  }

  /**
   * The status of a single catalog on this node.
   *
   * @param catalogName     the catalog name, never null
   * @param available       {@code true} if the catalog is bootstrapped and
   *                        queryable; {@code false} if it failed to load or is
   *                        not yet ready
   * @param version         the current snapshot version, or {@code 0} if
   *                        unavailable
   * @param hash            the current snapshot content hash (the cross-node
   *                        convergence signal), or empty if unavailable,
   *                        never null
   * @param itemCount       the number of items in the current snapshot
   * @param estimatedSizeMB the estimated in-memory size of the catalog's
   *                        indices, in megabytes
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public record CatalogStatus(
      String catalogName,
      boolean available,
      long version,
      String hash,
      int itemCount,
      double estimatedSizeMB) {

    /**
     * Canonical constructor validating inputs.
     *
     * @param catalogName     the catalog name, never null.
     * @param available       whether the catalog is bootstrapped.
     * @param version         the snapshot version.
     * @param hash            the snapshot hash, never null.
     * @param itemCount       the item count.
     * @param estimatedSizeMB the estimated size in megabytes.
     */
    public CatalogStatus {
      Objects.requireNonNull(catalogName, "catalogName must not be null");
      Objects.requireNonNull(hash, "hash must not be null");
    }
  }
}
