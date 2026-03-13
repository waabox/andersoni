package org.waabox.andersoni.sync;

/**
 * The type of patch operation carried by a {@link PatchEvent}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public enum PatchType {

  /** An item (or items) should be added to the snapshot. */
  ADD,

  /** An item (or items) should be removed from the snapshot. */
  REMOVE
}
