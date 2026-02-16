package org.waabox.andersoni;

import java.util.Objects;

/**
 * Holds computed statistics for a single index within a catalog snapshot.
 *
 * <p>Provides the number of unique keys, total entries across all key
 * buckets, and an estimated memory footprint in bytes based on structural
 * JVM object layout heuristics.
 *
 * @param name               the index name, never null
 * @param uniqueKeys         the number of distinct keys in the index
 * @param totalEntries       the total number of items across all key buckets
 * @param estimatedSizeBytes the estimated structural memory overhead in bytes
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public record IndexInfo(String name, int uniqueKeys, int totalEntries,
    long estimatedSizeBytes) {

  /** Creates a new IndexInfo instance.
   *
   * @param name               the index name, never null
   * @param uniqueKeys         the number of distinct keys
   * @param totalEntries       the total items across all key buckets
   * @param estimatedSizeBytes the estimated memory overhead in bytes
   *
   * @throws NullPointerException if name is null
   */
  public IndexInfo {
    Objects.requireNonNull(name, "name must not be null");
  }

  /**
   * Returns the estimated memory size in megabytes.
   *
   * @return the estimated size in MB
   */
  public double estimatedSizeMB() {
    return estimatedSizeBytes / (1024.0 * 1024.0);
  }
}
