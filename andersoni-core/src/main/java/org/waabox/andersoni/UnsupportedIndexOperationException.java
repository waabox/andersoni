package org.waabox.andersoni;

import java.util.Objects;

/**
 * Thrown when a query operation is not supported by the target index type.
 *
 * <p>Two common scenarios produce this exception:</p>
 * <ul>
 *   <li>A range or pattern operation is requested on an index that was
 *       created with {@code index()} instead of {@code indexSorted()}.
 *       Range queries require a sorted (tree-map backed) index.</li>
 *   <li>A text pattern operation (e.g. starts-with, contains) is requested
 *       on an index whose key type is not {@link String}.</li>
 * </ul>
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class UnsupportedIndexOperationException
    extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates a new exception with the given detail message.
   *
   * @param message the detail message explaining why the operation is
   *                unsupported, cannot be null.
   */
  public UnsupportedIndexOperationException(final String message) {
    super(Objects.requireNonNull(message, "message"));
  }
}
