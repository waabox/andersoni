package org.waabox.andersoni;

/**
 * Hook for per-item processing during snapshot build.
 *
 * <p>Hooks are executed after indexation and view computation for each
 * item. They receive the original domain object and return a value
 * (same or transformed) that is passed to the next hook in the chain.
 *
 * <p>Hooks are registered via
 * {@link Catalog.BuildStep#hook(SnapshotBuildHook, int)} in the
 * catalog DSL and ordered by priority (lower executes first).
 *
 * @param <T> the domain object type
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
@FunctionalInterface
public interface SnapshotBuildHook<T> {

  /**
   * Processes a single item during snapshot build.
   *
   * @param item the domain object, never null
   * @return the processed item, never null
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  T process(T item);
}
