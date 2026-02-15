package org.waabox.andersoni.admin.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoordinationV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1ContainerStatus;
import io.kubernetes.client.openapi.models.V1Lease;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST controller that aggregates cluster status for the admin dashboard.
 *
 * <p>Discovers pods via the Kubernetes API, reads the leader Lease, and
 * proxies requests to each pod through the K8s API server (since pod IPs
 * are not directly reachable from outside the cluster).
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
@RestController
@RequestMapping("/api/cluster")
public class ClusterController {

  /** The class logger. */
  private static final Logger log =
      LoggerFactory.getLogger(ClusterController.class);

  /** The Kubernetes core API for listing pods and proxying requests. */
  private final CoreV1Api coreApi;

  /** The Kubernetes coordination API for reading leases. */
  private final CoordinationV1Api coordinationApi;

  /** Jackson mapper for parsing pod info responses. */
  private final ObjectMapper objectMapper;

  /** Default namespace from configuration. */
  private final String defaultNamespace;

  /** Default label selector from configuration. */
  private final String defaultLabelSelector;

  /** Default lease name from configuration. */
  private final String defaultLeaseName;

  /** Default info path from configuration. */
  private final String defaultInfoPath;

  /** Creates a new ClusterController.
   *
   * @param apiClient the Kubernetes API client, never null.
   * @param objectMapper the Jackson object mapper, never null.
   * @param defaultNamespace the default namespace, never null.
   * @param defaultLabelSelector the default label selector, never null.
   * @param defaultLeaseName the default lease name, never null.
   * @param defaultInfoPath the default info path, never null.
   */
  @Autowired
  public ClusterController(
      final ApiClient apiClient,
      final ObjectMapper objectMapper,
      @Value("${admin.default-namespace}") final String defaultNamespace,
      @Value("${admin.default-label-selector}")
          final String defaultLabelSelector,
      @Value("${admin.default-lease-name}") final String defaultLeaseName,
      @Value("${admin.default-info-path}") final String defaultInfoPath) {
    this.coreApi = new CoreV1Api(apiClient);
    this.coordinationApi = new CoordinationV1Api(apiClient);
    this.objectMapper = objectMapper;
    this.defaultNamespace = defaultNamespace;
    this.defaultLabelSelector = defaultLabelSelector;
    this.defaultLeaseName = defaultLeaseName;
    this.defaultInfoPath = defaultInfoPath;
  }

  // Visible for testing.
  ClusterController(
      final CoreV1Api coreApi,
      final CoordinationV1Api coordinationApi,
      final ObjectMapper objectMapper,
      final String defaultNamespace,
      final String defaultLabelSelector,
      final String defaultLeaseName,
      final String defaultInfoPath) {
    this.coreApi = coreApi;
    this.coordinationApi = coordinationApi;
    this.objectMapper = objectMapper;
    this.defaultNamespace = defaultNamespace;
    this.defaultLabelSelector = defaultLabelSelector;
    this.defaultLeaseName = defaultLeaseName;
    this.defaultInfoPath = defaultInfoPath;
  }

  /** Returns the aggregated cluster status.
   *
   * <p>Discovers pods matching the label selector, reads the leader Lease,
   * and proxies a request to each pod's info endpoint through the K8s API
   * server in parallel.
   *
   * @param namespace the Kubernetes namespace, defaults to config value.
   * @param labelSelector the pod label selector, defaults to config value.
   * @param leaseName the lease name for leader detection, defaults to config
   *     value.
   * @param infoPath the HTTP path to call on each pod, defaults to config
   *     value.
   *
   * @return the cluster status as a JSON response, never null.
   */
  @GetMapping
  public ResponseEntity<Map<String, Object>> getClusterStatus(
      @RequestParam(required = false) final String namespace,
      @RequestParam(required = false) final String labelSelector,
      @RequestParam(required = false) final String leaseName,
      @RequestParam(required = false) final String infoPath) {

    final String ns = namespace != null ? namespace : defaultNamespace;
    final String selector = labelSelector != null
        ? labelSelector : defaultLabelSelector;
    final String lease = leaseName != null ? leaseName : defaultLeaseName;
    final String path = infoPath != null ? infoPath : defaultInfoPath;

    try {
      String leaderIdentity = readLeaderIdentity(ns, lease);

      V1PodList podList = coreApi.listNamespacedPod(ns)
          .labelSelector(selector)
          .execute();

      List<CompletableFuture<Map<String, Object>>> futures =
          new ArrayList<>();

      for (final V1Pod pod : podList.getItems()) {
        futures.add(CompletableFuture.supplyAsync(
            () -> buildNodeInfo(pod, leaderIdentity, ns, path)));
      }

      List<Map<String, Object>> nodes = futures.stream()
          .map(CompletableFuture::join)
          .toList();

      Map<String, Object> response = new LinkedHashMap<>();
      response.put("namespace", ns);
      response.put("leaderIdentity", leaderIdentity);
      response.put("nodes", nodes);

      return ResponseEntity.ok(response);

    } catch (ApiException e) {
      log.error("Kubernetes API error: {} - {}", e.getCode(),
          e.getResponseBody(), e);
      Map<String, Object> error = new LinkedHashMap<>();
      error.put("error", "Kubernetes API error");
      error.put("message", e.getMessage());
      error.put("code", e.getCode());
      return ResponseEntity.status(502).body(error);

    } catch (Exception e) {
      log.error("Unexpected error fetching cluster status", e);
      Map<String, Object> error = new LinkedHashMap<>();
      error.put("error", "Internal error");
      error.put("message", e.getMessage());
      return ResponseEntity.status(500).body(error);
    }
  }

  /** Reads the leader identity from the K8s Lease.
   *
   * @param namespace the namespace containing the lease, never null.
   * @param leaseName the name of the lease, never null.
   *
   * @return the leader identity string, or null if the lease does not exist
   *     or has no holder.
   */
  String readLeaderIdentity(final String namespace, final String leaseName) {
    try {
      V1Lease lease = coordinationApi
          .readNamespacedLease(leaseName, namespace)
          .execute();

      if (lease.getSpec() != null
          && lease.getSpec().getHolderIdentity() != null) {
        return lease.getSpec().getHolderIdentity();
      }
      return null;
    } catch (ApiException e) {
      log.warn("Could not read lease {}/{}: {} - {}",
          namespace, leaseName, e.getCode(), e.getMessage());
      return null;
    }
  }

  /** Builds the info map for a single pod.
   *
   * <p>Determines the pod status and, if the pod is running, proxies a
   * request through the K8s API server to fetch runtime details. This
   * avoids the need to reach pod IPs directly from outside the cluster.
   *
   * @param pod the Kubernetes pod, never null.
   * @param leaderIdentity the current leader identity, may be null.
   * @param namespace the namespace for the API proxy call, never null.
   * @param infoPath the HTTP path to call on the pod, never null.
   *
   * @return a map with pod name, IP, phase, readiness, leader flag, info
   *     payload and status. Never null.
   */
  Map<String, Object> buildNodeInfo(final V1Pod pod,
      final String leaderIdentity, final String namespace,
      final String infoPath) {

    final String podName = pod.getMetadata() != null
        ? pod.getMetadata().getName() : "unknown";
    final String podIp = pod.getStatus() != null
        ? pod.getStatus().getPodIP() : null;
    final String phase = pod.getStatus() != null
        ? pod.getStatus().getPhase() : "Unknown";

    boolean ready = isReady(pod);
    boolean leader = podName.equals(leaderIdentity);

    Map<String, Object> node = new LinkedHashMap<>();
    node.put("podName", podName);
    node.put("podIp", podIp);
    node.put("phase", phase);
    node.put("ready", ready);
    node.put("leader", leader);

    if (podIp == null) {
      node.put("info", null);
      node.put("status", "STARTING");
      return node;
    }

    try {
      // Strip leading slash for the proxy path parameter.
      String proxyPath = infoPath.startsWith("/")
          ? infoPath.substring(1) : infoPath;

      // Proxy through K8s API server: GET /api/v1/namespaces/{ns}/
      //   pods/{podName}:8080/proxy/{path}
      String responseBody = coreApi.connectGetNamespacedPodProxyWithPath(
          podName + ":8080", namespace, proxyPath)
          .execute();

      Object info = objectMapper.readValue(responseBody, Object.class);
      node.put("info", info);
      node.put("status", "OK");

    } catch (ApiException e) {
      log.warn("K8s proxy to pod {} failed: {} - {}",
          podName, e.getCode(), e.getMessage());
      node.put("info", null);
      node.put("status", "UNREACHABLE");
      node.put("error", "K8s proxy error: HTTP " + e.getCode());

    } catch (Exception e) {
      log.warn("Failed to reach pod {}: {}", podName, e.getMessage());
      node.put("info", null);
      node.put("status", "UNREACHABLE");
      node.put("error", e.getMessage());
    }

    return node;
  }

  /** Checks if a pod has at least one ready container.
   *
   * @param pod the Kubernetes pod, never null.
   *
   * @return true if the pod has a ready container status.
   */
  private boolean isReady(final V1Pod pod) {
    if (pod.getStatus() == null
        || pod.getStatus().getContainerStatuses() == null) {
      return false;
    }
    for (V1ContainerStatus cs : pod.getStatus().getContainerStatuses()) {
      if (Boolean.TRUE.equals(cs.getReady())) {
        return true;
      }
    }
    return false;
  }
}
