package org.waabox.andersoni;

/** Enumerates the types of patch operations that can be applied to a catalog.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public enum PatchOperation {
  /** Adds new items; fails if any identity key already exists. */
  ADD,
  /** Updates existing items; fails if any identity key is not found. */
  UPDATE,
  /** Adds or replaces items by identity key. */
  UPSERT,
  /** Removes items; fails if any identity key is not found. */
  REMOVE
}
