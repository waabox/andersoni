package org.waabox.andersoni;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Holds aggregated statistics for a catalog, including per-index info
 * and the total estimated memory footprint.
 *
 * @param catalogName             the catalog name, never null
 * @param itemCount               the total number of data items
 * @param indices                 the per-index statistics, never null,
 *                                unmodifiable
 * @param totalEstimatedSizeBytes the sum of all index estimated sizes
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public record CatalogInfo(String catalogName, int itemCount,
    List<IndexInfo> indices, long totalEstimatedSizeBytes) {

  /**
   * Creates a new CatalogInfo instance.
   *
   * @param catalogName             the catalog name, never null
   * @param itemCount               the total number of data items
   * @param indices                 the per-index statistics, never null
   * @param totalEstimatedSizeBytes the sum of all index estimated sizes
   *
   * @throws NullPointerException if catalogName or indices is null
   */
  public CatalogInfo {
    Objects.requireNonNull(catalogName, "catalogName must not be null");
    Objects.requireNonNull(indices, "indices must not be null");
    indices = Collections.unmodifiableList(List.copyOf(indices));
  }

  /**
   * Returns the total estimated memory size in megabytes.
   *
   * @return the total estimated size in MB
   */
  public double totalEstimatedSizeMB() {
    return totalEstimatedSizeBytes / (1024.0 * 1024.0);
  }
}
