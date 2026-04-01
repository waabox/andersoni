package org.waabox.andersoni;

import java.util.List;
import java.util.Objects;

/**
 * Holds aggregated statistics for a catalog, including per-index info,
 * the total estimated memory footprint, and registered view metadata.
 *
 * @param catalogName             the catalog name, never null
 * @param itemCount               the total number of data items
 * @param indices                 the per-index statistics, never null,
 *                                unmodifiable
 * @param totalEstimatedSizeBytes the sum of all index estimated sizes
 * @param viewCount               the number of registered view types
 * @param viewTypeNames           the simple class names of registered view
 *                                types, never null, unmodifiable
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public record CatalogInfo(
    String catalogName,
    int itemCount,
    List<IndexInfo> indices,
    long totalEstimatedSizeBytes,
    int viewCount,
    List<String> viewTypeNames) {

  /**
   * Creates a new CatalogInfo instance.
   *
   * @param catalogName             the catalog name, never null
   * @param itemCount               the total number of data items
   * @param indices                 the per-index statistics, never null
   * @param totalEstimatedSizeBytes the sum of all index estimated sizes
   * @param viewCount               the number of registered view types
   * @param viewTypeNames           the simple class names of registered view
   *                                types, never null
   *
   * @throws NullPointerException if catalogName, indices, or viewTypeNames
   *                              is null
   */
  public CatalogInfo {
    Objects.requireNonNull(catalogName, "catalogName must not be null");
    Objects.requireNonNull(indices, "indices must not be null");
    Objects.requireNonNull(viewTypeNames, "viewTypeNames must not be null");
    indices = List.copyOf(indices);
    viewTypeNames = List.copyOf(viewTypeNames);
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
