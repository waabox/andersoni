package org.waabox.andersoni;

/**
 * Base exception for all Andersoni-related errors.
 *
 * <p>This is an unchecked exception intended to wrap infrastructure and
 * configuration failures that cannot be meaningfully recovered from at
 * the call site.</p>
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class AndersoniException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates a new exception with the given message.
   *
   * @param message the detail message, cannot be null.
   */
  public AndersoniException(final String message) {
    super(message);
  }

  /** Creates a new exception with the given message and cause.
   *
   * @param message the detail message, cannot be null.
   * @param cause the underlying cause, cannot be null.
   */
  public AndersoniException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
