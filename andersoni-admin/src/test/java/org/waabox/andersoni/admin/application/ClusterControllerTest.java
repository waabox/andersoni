package org.waabox.andersoni.admin.application;

import static org.easymock.EasyMock.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoordinationV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1ContainerStatus;
import io.kubernetes.client.openapi.models.V1Lease;
import io.kubernetes.client.openapi.models.V1LeaseSpec;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.openapi.models.V1PodStatus;

import org.junit.jupiter.api.Test;

import org.springframework.http.ResponseEntity;

class ClusterControllerTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @SuppressWarnings("unchecked")
  void whenFetchingCluster_givenHealthyPods_shouldReturnNodesWithStatus()
      throws Exception {

    CoreV1Api coreApi = createMock(CoreV1Api.class);
    CoordinationV1Api coordApi = createMock(CoordinationV1Api.class);

    // Lease mock
    V1Lease lease = new V1Lease()
        .spec(new V1LeaseSpec().holderIdentity("pod-a"));

    CoordinationV1Api.APIreadNamespacedLeaseRequest leaseRequest =
        createMock(CoordinationV1Api.APIreadNamespacedLeaseRequest.class);
    expect(coordApi.readNamespacedLease("test-leader", "test-ns"))
        .andReturn(leaseRequest);
    expect(leaseRequest.execute()).andReturn(lease);

    // Pod list mock
    V1Pod podA = new V1Pod()
        .metadata(new V1ObjectMeta().name("pod-a"))
        .status(new V1PodStatus()
            .phase("Running")
            .podIP("10.0.0.1")
            .containerStatuses(List.of(
                new V1ContainerStatus().ready(true))));

    V1PodList podList = new V1PodList().items(List.of(podA));

    CoreV1Api.APIlistNamespacedPodRequest podRequest =
        createMock(CoreV1Api.APIlistNamespacedPodRequest.class);
    expect(coreApi.listNamespacedPod("test-ns")).andReturn(podRequest);
    expect(podRequest.labelSelector("app=test")).andReturn(podRequest);
    expect(podRequest.execute()).andReturn(podList);

    // K8s API proxy mock
    String infoJson =
        "{\"nodeId\":\"n1\",\"version\":2,\"hash\":\"abc123\","
        + "\"itemCount\":100,\"createdAt\":\"2026-01-01\"}";

    CoreV1Api.APIconnectGetNamespacedPodProxyWithPathRequest proxyRequest =
        createMock(
            CoreV1Api
                .APIconnectGetNamespacedPodProxyWithPathRequest.class);
    expect(coreApi.connectGetNamespacedPodProxyWithPath(
        "pod-a:8080", "test-ns", "events/info"))
        .andReturn(proxyRequest);
    expect(proxyRequest.execute()).andReturn(infoJson);

    replay(coreApi, coordApi, leaseRequest, podRequest, proxyRequest);

    ClusterController controller = new ClusterController(
        coreApi, coordApi, objectMapper,
        "test-ns", "app=test", "test-leader", "/events/info");

    ResponseEntity<Map<String, Object>> entity =
        controller.getClusterStatus(null, null, null, null);

    assertEquals(200, entity.getStatusCode().value());

    Map<String, Object> body = entity.getBody();
    assertNotNull(body);
    assertEquals("test-ns", body.get("namespace"));
    assertEquals("pod-a", body.get("leaderIdentity"));

    List<Map<String, Object>> nodes =
        (List<Map<String, Object>>) body.get("nodes");
    assertEquals(1, nodes.size());

    Map<String, Object> node = nodes.get(0);
    assertEquals("pod-a", node.get("podName"));
    assertEquals("10.0.0.1", node.get("podIp"));
    assertEquals("Running", node.get("phase"));
    assertEquals(true, node.get("ready"));
    assertEquals(true, node.get("leader"));
    assertEquals("OK", node.get("status"));
    assertNotNull(node.get("info"));

    verify(coreApi, coordApi, leaseRequest, podRequest, proxyRequest);
  }

  @Test
  void whenFetchingCluster_givenPodWithNoIp_shouldReturnStartingStatus()
      throws Exception {

    CoreV1Api coreApi = createMock(CoreV1Api.class);
    CoordinationV1Api coordApi = createMock(CoordinationV1Api.class);

    // Lease mock
    CoordinationV1Api.APIreadNamespacedLeaseRequest leaseRequest =
        createMock(CoordinationV1Api.APIreadNamespacedLeaseRequest.class);
    expect(coordApi.readNamespacedLease("test-leader", "test-ns"))
        .andReturn(leaseRequest);
    expect(leaseRequest.execute())
        .andReturn(new V1Lease().spec(new V1LeaseSpec()));

    // Pod without IP
    V1Pod pod = new V1Pod()
        .metadata(new V1ObjectMeta().name("pod-pending"))
        .status(new V1PodStatus().phase("Pending"));

    V1PodList podList = new V1PodList().items(List.of(pod));

    CoreV1Api.APIlistNamespacedPodRequest podRequest =
        createMock(CoreV1Api.APIlistNamespacedPodRequest.class);
    expect(coreApi.listNamespacedPod("test-ns")).andReturn(podRequest);
    expect(podRequest.labelSelector("app=test")).andReturn(podRequest);
    expect(podRequest.execute()).andReturn(podList);

    replay(coreApi, coordApi, leaseRequest, podRequest);

    ClusterController controller = new ClusterController(
        coreApi, coordApi, objectMapper,
        "test-ns", "app=test", "test-leader", "/events/info");

    ResponseEntity<Map<String, Object>> entity =
        controller.getClusterStatus(null, null, null, null);

    assertEquals(200, entity.getStatusCode().value());

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> nodes =
        (List<Map<String, Object>>) entity.getBody().get("nodes");

    Map<String, Object> node = nodes.get(0);
    assertEquals("STARTING", node.get("status"));
    assertNull(node.get("info"));
    assertFalse((Boolean) node.get("leader"));

    verify(coreApi, coordApi, leaseRequest, podRequest);
  }

  @Test
  @SuppressWarnings("unchecked")
  void whenFetchingCluster_givenUnreachablePod_shouldReturnUnreachableStatus()
      throws Exception {

    CoreV1Api coreApi = createMock(CoreV1Api.class);
    CoordinationV1Api coordApi = createMock(CoordinationV1Api.class);

    // Lease mock
    CoordinationV1Api.APIreadNamespacedLeaseRequest leaseRequest =
        createMock(CoordinationV1Api.APIreadNamespacedLeaseRequest.class);
    expect(coordApi.readNamespacedLease("test-leader", "test-ns"))
        .andReturn(leaseRequest);
    expect(leaseRequest.execute())
        .andReturn(new V1Lease()
            .spec(new V1LeaseSpec().holderIdentity("pod-x")));

    // Pod with IP but proxy fails
    V1Pod pod = new V1Pod()
        .metadata(new V1ObjectMeta().name("pod-x"))
        .status(new V1PodStatus()
            .phase("Running")
            .podIP("10.0.0.5")
            .containerStatuses(List.of(
                new V1ContainerStatus().ready(true))));

    V1PodList podList = new V1PodList().items(List.of(pod));

    CoreV1Api.APIlistNamespacedPodRequest podRequest =
        createMock(CoreV1Api.APIlistNamespacedPodRequest.class);
    expect(coreApi.listNamespacedPod("test-ns")).andReturn(podRequest);
    expect(podRequest.labelSelector("app=test")).andReturn(podRequest);
    expect(podRequest.execute()).andReturn(podList);

    // K8s proxy throws ApiException (pod unreachable)
    CoreV1Api.APIconnectGetNamespacedPodProxyWithPathRequest proxyRequest =
        createMock(
            CoreV1Api
                .APIconnectGetNamespacedPodProxyWithPathRequest.class);
    expect(coreApi.connectGetNamespacedPodProxyWithPath(
        "pod-x:8080", "test-ns", "events/info"))
        .andReturn(proxyRequest);
    expect(proxyRequest.execute())
        .andThrow(new ApiException(502, "Bad Gateway"));

    replay(coreApi, coordApi, leaseRequest, podRequest, proxyRequest);

    ClusterController controller = new ClusterController(
        coreApi, coordApi, objectMapper,
        "test-ns", "app=test", "test-leader", "/events/info");

    ResponseEntity<Map<String, Object>> entity =
        controller.getClusterStatus(null, null, null, null);

    assertEquals(200, entity.getStatusCode().value());

    List<Map<String, Object>> nodes =
        (List<Map<String, Object>>) entity.getBody().get("nodes");

    Map<String, Object> node = nodes.get(0);
    assertEquals("UNREACHABLE", node.get("status"));
    assertEquals("K8s proxy error: HTTP 502", node.get("error"));
    assertTrue((Boolean) node.get("leader"));

    verify(coreApi, coordApi, leaseRequest, podRequest, proxyRequest);
  }

  @Test
  void whenFetchingCluster_givenK8sApiFailure_shouldReturn502()
      throws Exception {

    CoreV1Api coreApi = createMock(CoreV1Api.class);
    CoordinationV1Api coordApi = createMock(CoordinationV1Api.class);

    // Lease succeeds
    CoordinationV1Api.APIreadNamespacedLeaseRequest leaseRequest =
        createMock(CoordinationV1Api.APIreadNamespacedLeaseRequest.class);
    expect(coordApi.readNamespacedLease("test-leader", "test-ns"))
        .andReturn(leaseRequest);
    expect(leaseRequest.execute())
        .andReturn(new V1Lease().spec(new V1LeaseSpec()));

    // Pod list fails
    CoreV1Api.APIlistNamespacedPodRequest podRequest =
        createMock(CoreV1Api.APIlistNamespacedPodRequest.class);
    expect(coreApi.listNamespacedPod("test-ns")).andReturn(podRequest);
    expect(podRequest.labelSelector("app=test")).andReturn(podRequest);
    expect(podRequest.execute())
        .andThrow(new ApiException(403, "Forbidden"));

    replay(coreApi, coordApi, leaseRequest, podRequest);

    ClusterController controller = new ClusterController(
        coreApi, coordApi, objectMapper,
        "test-ns", "app=test", "test-leader", "/events/info");

    ResponseEntity<Map<String, Object>> entity =
        controller.getClusterStatus(null, null, null, null);

    assertEquals(502, entity.getStatusCode().value());

    Map<String, Object> body = entity.getBody();
    assertNotNull(body);
    assertEquals("Kubernetes API error", body.get("error"));
    assertEquals(403, body.get("code"));

    verify(coreApi, coordApi, leaseRequest, podRequest);
  }

  @Test
  void whenFetchingCluster_givenMissingLease_shouldReturnNullLeader()
      throws Exception {

    CoreV1Api coreApi = createMock(CoreV1Api.class);
    CoordinationV1Api coordApi = createMock(CoordinationV1Api.class);

    // Lease not found
    CoordinationV1Api.APIreadNamespacedLeaseRequest leaseRequest =
        createMock(CoordinationV1Api.APIreadNamespacedLeaseRequest.class);
    expect(coordApi.readNamespacedLease("test-leader", "test-ns"))
        .andReturn(leaseRequest);
    expect(leaseRequest.execute())
        .andThrow(new ApiException(404, "Not Found"));

    // Empty pod list
    V1PodList podList = new V1PodList().items(List.of());

    CoreV1Api.APIlistNamespacedPodRequest podRequest =
        createMock(CoreV1Api.APIlistNamespacedPodRequest.class);
    expect(coreApi.listNamespacedPod("test-ns")).andReturn(podRequest);
    expect(podRequest.labelSelector("app=test")).andReturn(podRequest);
    expect(podRequest.execute()).andReturn(podList);

    replay(coreApi, coordApi, leaseRequest, podRequest);

    ClusterController controller = new ClusterController(
        coreApi, coordApi, objectMapper,
        "test-ns", "app=test", "test-leader", "/events/info");

    ResponseEntity<Map<String, Object>> entity =
        controller.getClusterStatus(null, null, null, null);

    assertEquals(200, entity.getStatusCode().value());
    assertNull(entity.getBody().get("leaderIdentity"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> nodes =
        (List<Map<String, Object>>) entity.getBody().get("nodes");
    assertTrue(nodes.isEmpty());

    verify(coreApi, coordApi, leaseRequest, podRequest);
  }

  @Test
  @SuppressWarnings("unchecked")
  void whenFetchingCluster_givenCustomParams_shouldUseOverrides()
      throws Exception {

    CoreV1Api coreApi = createMock(CoreV1Api.class);
    CoordinationV1Api coordApi = createMock(CoordinationV1Api.class);

    // Lease mock with custom namespace/lease
    CoordinationV1Api.APIreadNamespacedLeaseRequest leaseRequest =
        createMock(CoordinationV1Api.APIreadNamespacedLeaseRequest.class);
    expect(coordApi.readNamespacedLease("custom-leader", "custom-ns"))
        .andReturn(leaseRequest);
    expect(leaseRequest.execute())
        .andReturn(new V1Lease().spec(new V1LeaseSpec()));

    // Pod list with custom namespace/selector
    V1PodList podList = new V1PodList().items(List.of());

    CoreV1Api.APIlistNamespacedPodRequest podRequest =
        createMock(CoreV1Api.APIlistNamespacedPodRequest.class);
    expect(coreApi.listNamespacedPod("custom-ns")).andReturn(podRequest);
    expect(podRequest.labelSelector("app=custom")).andReturn(podRequest);
    expect(podRequest.execute()).andReturn(podList);

    replay(coreApi, coordApi, leaseRequest, podRequest);

    ClusterController controller = new ClusterController(
        coreApi, coordApi, objectMapper,
        "test-ns", "app=test", "test-leader", "/events/info");

    ResponseEntity<Map<String, Object>> entity =
        controller.getClusterStatus(
            "custom-ns", "app=custom", "custom-leader", "/custom/info");

    assertEquals(200, entity.getStatusCode().value());
    assertEquals("custom-ns", entity.getBody().get("namespace"));

    verify(coreApi, coordApi, leaseRequest, podRequest);
  }
}
