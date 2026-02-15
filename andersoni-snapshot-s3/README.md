# andersoni-snapshot-s3

S3-based snapshot persistence for [Andersoni](../README.md). Stores serialized catalog snapshots in Amazon S3 (or S3-compatible services like MinIO) for fast cold-start. New nodes download the snapshot instead of querying the database.

## How It Works

The leader serializes the catalog data (`List<T>` only, not indices) and uploads it to S3 as a single object. Metadata (hash, version, timestamp) is stored as S3 user metadata headers. On startup, nodes download the snapshot, deserialize the data, and rebuild indices in memory.

```
S3 bucket:
  andersoni/events/snapshot.dat
    ├── Body: serialized List<T> (byte[])
    └── Metadata:
        ├── x-amz-meta-hash: "a3f8c2..."
        ├── x-amz-meta-version: "42"
        ├── x-amz-meta-created-at: "2026-02-15T10:30:00Z"
        └── x-amz-meta-catalog-name: "events"
```

## Usage

### Standalone

```java
S3SnapshotConfig config = S3SnapshotConfig.builder()
    .bucket("my-snapshots")
    .region(Region.US_EAST_1)
    .build();

Andersoni andersoni = Andersoni.builder()
    .snapshotStore(new S3SnapshotStore(config))
    .build();
```

### Spring Boot

```java
@Bean
SnapshotStore snapshotStore() {
    return new S3SnapshotStore(S3SnapshotConfig.builder()
        .bucket("my-snapshots")
        .region(Region.US_EAST_1)
        .build());
}
```

The starter auto-detects the `SnapshotStore` bean and wires it into Andersoni.

### With a custom S3Client (MinIO, LocalStack, etc.)

```java
S3Client s3Client = S3Client.builder()
    .endpointOverride(URI.create("http://minio:9000"))
    .region(Region.US_EAST_1)
    .build();

S3SnapshotConfig config = S3SnapshotConfig.builder()
    .bucket("my-snapshots")
    .region(Region.US_EAST_1)
    .s3Client(s3Client)
    .build();
```

When a custom `S3Client` is provided, the store uses it as-is and does not close it on shutdown. When no client is provided, the store creates one internally and manages its lifecycle.

## Configuration

| Property | Default | Description |
|---|---|---|
| `bucket` | *(required)* | S3 bucket name |
| `region` | *(required)* | AWS region |
| `prefix` | `andersoni/` | Key prefix within the bucket |
| `s3Client` | *(auto-created)* | Optional pre-built S3Client |

## Dependencies

| Dependency | Version |
|---|---|
| `andersoni-core` | — |
| `software.amazon.awssdk:s3` | 2.29.51 |

## Maven

```xml
<dependency>
    <groupId>io.github.waabox</groupId>
    <artifactId>andersoni-snapshot-s3</artifactId>
    <version>1.0.0</version>
</dependency>
```
