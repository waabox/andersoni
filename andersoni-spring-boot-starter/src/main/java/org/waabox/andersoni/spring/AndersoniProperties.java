package org.waabox.andersoni.spring;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Andersoni, mapped from the
 * {@code andersoni.*} prefix in application.yml or application.properties.
 *
 * <p>Currently supports:
 * <ul>
 *   <li>{@code andersoni.node-id} - the unique node identifier. If not set,
 *       a random UUID is generated automatically.</li>
 *   <li>{@code andersoni.reconciliation.*} - snapshot reconciliation
 *       (cluster anti-entropy) settings.</li>
 * </ul>
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
@ConfigurationProperties(prefix = "andersoni")
public class AndersoniProperties {

  /** The unique node identifier, null means auto-generate UUID. */
  private String nodeId;

  /** Reconciliation settings. */
  private final Reconciliation reconciliation = new Reconciliation();

  /**
   * Returns the configured node identifier.
   *
   * @return the node identifier, or null if auto-generation should be used
   */
  public String getNodeId() {
    return nodeId;
  }

  /**
   * Sets the unique node identifier.
   *
   * <p>If not set, a random UUID will be generated at startup.
   *
   * @param nodeId the node identifier, may be null
   */
  public void setNodeId(final String nodeId) {
    this.nodeId = nodeId;
  }

  /**
   * Returns the reconciliation settings.
   *
   * @return the reconciliation settings, never null
   */
  public Reconciliation getReconciliation() {
    return reconciliation;
  }

  /**
   * Snapshot reconciliation (cluster anti-entropy) settings, bound from
   * {@code andersoni.reconciliation.*}.
   */
  public static class Reconciliation {

    /** Whether the reconciliation loop runs. Only effective with a snapshot store. */
    private boolean enabled = true;

    /** Base interval between passes. */
    private Duration interval = Duration.ofSeconds(30);

    /**
     * Returns whether the reconciliation loop runs.
     *
     * @return true if reconciliation is enabled
     */
    public boolean isEnabled() {
      return enabled;
    }

    /**
     * Sets whether the reconciliation loop runs.
     *
     * @param enabled true to enable reconciliation, false to disable it
     */
    public void setEnabled(final boolean enabled) {
      this.enabled = enabled;
    }

    /**
     * Returns the base interval between reconciliation passes.
     *
     * @return the interval, never null
     */
    public Duration getInterval() {
      return interval;
    }

    /**
     * Sets the base interval between reconciliation passes.
     *
     * @param interval the interval, never null
     */
    public void setInterval(final Duration interval) {
      this.interval = interval;
    }
  }
}
