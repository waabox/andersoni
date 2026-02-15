package org.waabox.andersoni.snapshot.s3;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.newCapture;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.easymock.Capture;
import org.junit.jupiter.api.Test;
import org.waabox.andersoni.snapshot.SerializedSnapshot;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

/**
 * Tests for {@link S3SnapshotStore}.
 *
 * <p>Uses EasyMock to mock the S3Client since unit tests cannot depend
 * on a real AWS S3 bucket.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class S3SnapshotStoreTest {

  @Test
  void whenSaving_givenSnapshot_shouldPutObjectWithCorrectKeyAndMetadata() {

    final S3Client s3Client = createMock(S3Client.class);

    final S3SnapshotConfig config = S3SnapshotConfig.builder()
        .bucket("my-bucket")
        .region(Region.US_EAST_1)
        .s3Client(s3Client)
        .build();

    final byte[] data = "snapshot-data-bytes".getBytes();
    final Instant createdAt = Instant.parse("2026-01-15T10:30:00Z");
    final SerializedSnapshot snapshot = new SerializedSnapshot(
        "events", "sha256hash", 42L, createdAt, data);

    final Capture<PutObjectRequest> requestCapture = newCapture();
    final Capture<RequestBody> bodyCapture = newCapture();

    expect(s3Client.putObject(capture(requestCapture), capture(bodyCapture)))
        .andReturn(PutObjectResponse.builder().build());

    replay(s3Client);

    final S3SnapshotStore store = new S3SnapshotStore(config);
    store.save("events", snapshot);

    verify(s3Client);

    final PutObjectRequest captured = requestCapture.getValue();
    assertEquals("my-bucket", captured.bucket());
    assertEquals("andersoni/events/snapshot.dat", captured.key());

    final Map<String, String> metadata = captured.metadata();
    assertEquals("sha256hash", metadata.get("hash"));
    assertEquals("42", metadata.get("version"));
    assertEquals("2026-01-15T10:30:00Z", metadata.get("created-at"));
    assertEquals("events", metadata.get("catalog-name"));
  }

  @Test
  void whenLoading_givenExistingSnapshot_shouldReturnCorrectData()
      throws Exception {

    final S3Client s3Client = createMock(S3Client.class);

    final S3SnapshotConfig config = S3SnapshotConfig.builder()
        .bucket("my-bucket")
        .region(Region.US_EAST_1)
        .s3Client(s3Client)
        .build();

    final byte[] data = "snapshot-data-bytes".getBytes();
    final Instant createdAt = Instant.parse("2026-01-15T10:30:00Z");

    final GetObjectResponse response = GetObjectResponse.builder()
        .metadata(Map.of(
            "hash", "sha256hash",
            "version", "42",
            "created-at", "2026-01-15T10:30:00Z",
            "catalog-name", "events"
        ))
        .build();

    final AbortableInputStream abortableStream =
        AbortableInputStream.create(new ByteArrayInputStream(data));
    final ResponseInputStream<GetObjectResponse> responseStream =
        new ResponseInputStream<>(response, abortableStream);

    final Capture<GetObjectRequest> requestCapture = newCapture();

    expect(s3Client.getObject(capture(requestCapture)))
        .andReturn(responseStream);

    replay(s3Client);

    final S3SnapshotStore store = new S3SnapshotStore(config);
    final Optional<SerializedSnapshot> loaded = store.load("events");

    verify(s3Client);

    final GetObjectRequest capturedRequest = requestCapture.getValue();
    assertEquals("my-bucket", capturedRequest.bucket());
    assertEquals("andersoni/events/snapshot.dat", capturedRequest.key());

    assertTrue(loaded.isPresent(), "Loaded snapshot should be present");

    final SerializedSnapshot result = loaded.get();
    assertEquals("events", result.catalogName());
    assertEquals("sha256hash", result.hash());
    assertEquals(42L, result.version());
    assertEquals(createdAt, result.createdAt());
    assertArrayEquals(data, result.data());
  }

  @Test
  void whenLoading_givenNonExistentKey_shouldReturnEmpty() {

    final S3Client s3Client = createMock(S3Client.class);

    final S3SnapshotConfig config = S3SnapshotConfig.builder()
        .bucket("my-bucket")
        .region(Region.US_EAST_1)
        .s3Client(s3Client)
        .build();

    expect(s3Client.getObject(anyObject(GetObjectRequest.class)))
        .andThrow(NoSuchKeyException.builder()
            .message("The specified key does not exist.")
            .build());

    replay(s3Client);

    final S3SnapshotStore store = new S3SnapshotStore(config);
    final Optional<SerializedSnapshot> loaded = store.load("non-existent");

    verify(s3Client);

    assertTrue(loaded.isEmpty(),
        "Loading a non-existent key should return empty");
  }

  @Test
  void whenCreatingConfig_givenRequiredParams_shouldRetainValues() {

    final S3SnapshotConfig config = S3SnapshotConfig.builder()
        .bucket("test-bucket")
        .region(Region.EU_WEST_1)
        .prefix("custom/prefix/")
        .build();

    assertEquals("test-bucket", config.bucket());
    assertEquals(Region.EU_WEST_1, config.region());
    assertEquals("custom/prefix/", config.prefix());
    assertTrue(config.s3Client().isEmpty(),
        "S3Client should be empty when not provided");
  }

  @Test
  void whenCreatingConfig_givenDefaultPrefix_shouldUseAndersoniPrefix() {

    final S3SnapshotConfig config = S3SnapshotConfig.builder()
        .bucket("test-bucket")
        .region(Region.US_EAST_1)
        .build();

    assertEquals("andersoni/", config.prefix());
  }

  @Test
  void whenSaving_givenCustomPrefix_shouldUseCustomPrefixInKey() {

    final S3Client s3Client = createMock(S3Client.class);

    final S3SnapshotConfig config = S3SnapshotConfig.builder()
        .bucket("my-bucket")
        .region(Region.US_EAST_1)
        .prefix("custom/")
        .s3Client(s3Client)
        .build();

    final byte[] data = "data".getBytes();
    final Instant createdAt = Instant.parse("2026-01-15T10:30:00Z");
    final SerializedSnapshot snapshot = new SerializedSnapshot(
        "products", "hash1", 1L, createdAt, data);

    final Capture<PutObjectRequest> requestCapture = newCapture();

    expect(s3Client.putObject(capture(requestCapture),
        anyObject(RequestBody.class)))
        .andReturn(PutObjectResponse.builder().build());

    replay(s3Client);

    final S3SnapshotStore store = new S3SnapshotStore(config);
    store.save("products", snapshot);

    verify(s3Client);

    assertEquals("custom/products/snapshot.dat",
        requestCapture.getValue().key());
  }
}
