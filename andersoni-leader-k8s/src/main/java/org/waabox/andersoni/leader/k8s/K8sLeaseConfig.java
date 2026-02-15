package org.waabox.andersoni.leader.k8s;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration for Kubernetes Lease-based leader election.
 *
 * <p>Holds all parameters required to create and manage a Kubernetes Lease
 * resource for leader election. Instances are created via static factory
 * methods and are immutable.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class K8sLeaseConfig {

  /** The default Kubernetes namespace. */
  private static final String DEFAULT_NAMESPACE = "default";

  /** The default renewal interval (15 seconds). */
  private static final Duration DEFAULT_RENEWAL_INTERVAL =
      Duration.ofSeconds(15);

  /** The default lease duration (30 seconds). */
  private static final Duration DEFAULT_LEASE_DURATION =
      Duration.ofSeconds(30);

  /** The name of the Lease resource in Kubernetes. */
  private final String leaseName;

  /** The Kubernetes namespace where the Lease resource lives. */
  private final String leaseNamespace;

  /** The identity of this node, typically the pod name. */
  private final String identity;

  /** How often the leader renews the lease. */
  private final Duration renewalInterval;

  /** How long the lease is valid before it expires. */
  private final Duration leaseDuration;

  /** Private constructor; use static factory methods. */
  private K8sLeaseConfig(final String theLeaseName,
      final String theLeaseNamespace,
      final String theIdentity,
      final Duration theRenewalInterval,
      final Duration theLeaseDuration) {
    leaseName = Objects.requireNonNull(theLeaseName,
        "leaseName must not be null");
    leaseNamespace = Objects.requireNonNull(theLeaseNamespace,
        "leaseNamespace must not be null");
    identity = Objects.requireNonNull(theIdentity,
        "identity must not be null");
    renewalInterval = Objects.requireNonNull(theRenewalInterval,
        "renewalInterval must not be null");
    leaseDuration = Objects.requireNonNull(theLeaseDuration,
        "leaseDuration must not be null");
  }

  /**
   * Creates a new configuration with all parameters specified.
   *
   * @param theLeaseName the name of the Lease resource, never null
   * @param theLeaseNamespace the Kubernetes namespace, never null
   * @param theIdentity the identity of this node (e.g. pod name),
   *                    never null
   * @param theRenewalInterval how often the leader renews the lease,
   *                           never null
   * @param theLeaseDuration how long the lease is valid, never null
   *
   * @return a new {@link K8sLeaseConfig}, never null
   */
  public static K8sLeaseConfig create(final String theLeaseName,
      final String theLeaseNamespace,
      final String theIdentity,
      final Duration theRenewalInterval,
      final Duration theLeaseDuration) {
    return new K8sLeaseConfig(theLeaseName, theLeaseNamespace,
        theIdentity, theRenewalInterval, theLeaseDuration);
  }

  /**
   * Creates a new configuration with default namespace, renewal interval,
   * and lease duration.
   *
   * <p>Defaults:
   * <ul>
   *   <li>namespace: "default"</li>
   *   <li>renewalInterval: 15 seconds</li>
   *   <li>leaseDuration: 30 seconds</li>
   * </ul>
   *
   * @param theLeaseName the name of the Lease resource, never null
   * @param theIdentity the identity of this node (e.g. pod name),
   *                    never null
   *
   * @return a new {@link K8sLeaseConfig} with default values, never null
   */
  public static K8sLeaseConfig create(final String theLeaseName,
      final String theIdentity) {
    return new K8sLeaseConfig(theLeaseName, DEFAULT_NAMESPACE,
        theIdentity, DEFAULT_RENEWAL_INTERVAL, DEFAULT_LEASE_DURATION);
  }

  /**
   * Returns the name of the Lease resource.
   *
   * @return the lease name, never null
   */
  public String leaseName() {
    return leaseName;
  }

  /**
   * Returns the Kubernetes namespace.
   *
   * @return the namespace, never null
   */
  public String leaseNamespace() {
    return leaseNamespace;
  }

  /**
   * Returns the identity of this node.
   *
   * @return the identity string, never null
   */
  public String identity() {
    return identity;
  }

  /**
   * Returns the renewal interval.
   *
   * @return how often the leader renews the lease, never null
   */
  public Duration renewalInterval() {
    return renewalInterval;
  }

  /**
   * Returns the lease duration.
   *
   * @return how long the lease is valid, never null
   */
  public Duration leaseDuration() {
    return leaseDuration;
  }
}
