package org.waabox.andersoni;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Internal wrapper that holds a domain object together with its
 * pre-computed views (projections).
 *
 * <p>This class is package-private and never exposed in the public API.
 * All public-facing methods return either {@code T} (the item) or
 * {@code V} (a view type) — never this wrapper.
 *
 * <p>Instances are immutable. Views are materialized at snapshot build
 * time and shared across all index lookups.
 *
 * @param <T> the domain object type
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
final class AndersoniCatalogItem<T> {

  /** The domain object, never null. */
  private final T item;

  /** Pre-computed views keyed by view type, never null. */
  private final Map<Class<?>, Object> views;

  private AndersoniCatalogItem(final T theItem,
      final Map<Class<?>, Object> theViews) {
    item = theItem;
    views = theViews;
  }

  /**
   * Creates a new catalog item with the given domain object and views.
   *
   * @param item  the domain object, never null
   * @param views the pre-computed views keyed by type, never null
   * @param <T>   the domain object type
   *
   * @return a new immutable catalog item, never null
   *
   * @throws NullPointerException if item or views is null
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  static <T> AndersoniCatalogItem<T> of(final T item,
      final Map<Class<?>, Object> views) {
    Objects.requireNonNull(item, "item must not be null");
    Objects.requireNonNull(views, "views must not be null");
    return new AndersoniCatalogItem<>(item,
        Collections.unmodifiableMap(Map.copyOf(views)));
  }

  /**
   * Returns the domain object.
   *
   * @return the item, never null
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  T item() {
    return item;
  }

  /**
   * Returns the pre-computed view for the given type.
   *
   * @param viewType the view type class, never null
   * @param <V>      the view type
   *
   * @return the view instance, never null
   *
   * @throws IllegalArgumentException if the view type is not registered
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  @SuppressWarnings("unchecked")
  <V> V view(final Class<V> viewType) {
    final Object view = views.get(viewType);
    if (view == null) {
      throw new IllegalArgumentException(
          "View " + viewType.getSimpleName() + " is not registered");
    }
    return (V) view;
  }

  /**
   * Returns the unmodifiable map of all views.
   *
   * @return the views map, never null
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  Map<Class<?>, Object> views() {
    return views;
  }
}
