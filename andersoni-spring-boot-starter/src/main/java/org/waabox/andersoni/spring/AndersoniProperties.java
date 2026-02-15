package org.waabox.andersoni.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Andersoni, mapped from the
 * {@code andersoni.*} prefix in application.yml or application.properties.
 *
 * <p>Currently supports:
 * <ul>
 *   <li>{@code andersoni.node-id} - the unique node identifier. If not set,
 *       a random UUID is generated automatically.</li>
 * </ul>
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
@ConfigurationProperties(prefix = "andersoni")
public class AndersoniProperties {

  /** The unique node identifier, null means auto-generate UUID. */
  private String nodeId;

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
}
