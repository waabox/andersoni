package org.waabox.andersoni;

import java.util.Objects;
import java.util.function.Function;

/**
 * Defines a view (projection) of a catalog's domain object.
 *
 * <p>Associates a target type {@code V} with a stateless mapping function
 * that transforms the source object {@code T} into its projected form.
 * Views are pre-computed at snapshot build time and stored alongside the
 * original item in {@link AndersoniCatalogItem}.
 *
 * @param <T> the source domain type
 * @param <V> the view (projected) type
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class ViewDefinition<T, V> {

  /** The view type class, never null. */
  private final Class<V> viewType;

  /** The mapping function from T to V, never null. */
  private final Function<T, V> mapper;

  private ViewDefinition(final Class<V> theViewType,
      final Function<T, V> theMapper) {
    viewType = theViewType;
    mapper = theMapper;
  }

  /**
   * Creates a new view definition associating the given type with a
   * mapping function.
   *
   * @param viewType the view type class, never null
   * @param mapper   the mapping function from T to V, never null
   * @param <T>      the source domain type
   * @param <V>      the view type
   *
   * @return a new view definition, never null
   *
   * @throws NullPointerException if viewType or mapper is null
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public static <T, V> ViewDefinition<T, V> of(final Class<V> viewType,
      final Function<T, V> mapper) {
    Objects.requireNonNull(viewType, "viewType must not be null");
    Objects.requireNonNull(mapper, "mapper must not be null");
    return new ViewDefinition<>(viewType, mapper);
  }

  /**
   * Returns the view type class.
   *
   * @return the view type, never null
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public Class<V> viewType() {
    return viewType;
  }

  /**
   * Returns the mapping function.
   *
   * @return the mapper from T to V, never null
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public Function<T, V> mapper() {
    return mapper;
  }
}
