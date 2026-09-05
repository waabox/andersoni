package org.waabox.andersoni;

/**
 * Hook for per-item processing during snapshot build.
 *
 * <p>Hooks are executed after indexation and view computation for each
 * item. They receive the original domain object and return a value
 * (same or transformed) that is passed to the next hook in the chain.
 *
 * <p><strong>Hooks are side-effect observers.</strong> Because they run
 * <em>after</em> indices and views have already been built from the original
 * item, the value returned by {@link #process(Object)} does not replace the
 * indexed, stored, or queryable item — it is only forwarded to the next hook
 * in the chain. Use hooks for per-item side effects (metrics, logging,
 * validation, warming external state), not to transform what the catalog
 * stores or returns. (A future major version may change {@code process} to
 * a {@code void} operation to make this explicit.)
 *
 * <p>Hooks are registered via
 * {@link Catalog.BuildStep#hook(SnapshotBuildHook, int)} in the
 * catalog DSL and ordered by priority (lower executes first).
 *
 * <p><strong>Hooks and patches.</strong> A patch runs hooks only for the
 * item it changes, because that is the only item it builds. A hook that
 * merely observes one item at a time is fine with that; a hook that
 * accumulates something across a whole build — a count, a sum, an index
 * of its own — is not, because it would see a single delta and conclude
 * the catalog contains one item. Since a catalog cannot inspect a lambda
 * to tell which kind it is, {@link #supportsPatch()} defaults to
 * {@code false} and a catalog holding any hook that has not opted in
 * rebuilds fully on every patch. The result is always correct; opting in
 * is what makes it fast.
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

  /**
   * Returns whether this hook can be run for a single patched item rather
   * than for every item of a full build.
   *
   * <p>Return {@code true} only if the hook's effect for one item is
   * independent of every other item — logging it, validating it, warming a
   * cache entry for it. Return {@code false} (the default) if the hook
   * accumulates state across the build, because a patch would feed it one
   * item and leave the accumulated view wrong.
   *
   * <p>A catalog registering any hook that answers {@code false} falls back
   * to a full rebuild on every patch: still correct, and still cheaper than
   * a refresh because the data does not have to be re-loaded, but without
   * the surgical index work.
   *
   * @return true if the hook is safe to run per patched item
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  default boolean supportsPatch() {
    return false;
  }
}
