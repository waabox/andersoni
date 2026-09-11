package org.waabox.andersoni;

/**
 * Whether a catalog on this node matches the cluster's authoritative state
 * (the snapshot store) as of the last reconciliation pass.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public enum SyncState {

  /** The last pass found this node at the store's snapshot. */
  IN_SYNC,

  /** The last pass found a mismatch; a repair was dispatched or is pending. */
  DRIFTED,

  /** Reconciliation is inactive for this catalog (no snapshot store, no
   *  serializer, disabled policy) or no pass has completed yet. */
  UNKNOWN
}
