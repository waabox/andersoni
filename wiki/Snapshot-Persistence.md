# Snapshot Persistence

A `SnapshotStore` persists serialized snapshots so that new or restarting nodes can restore data without hitting the database. This provides fast cold-start recovery and reduces load on the data source.

## How It Works

1. **On refresh (leader):** after building a new snapshot, the leader serializes the data and saves it to the store
2. **On bootstrap (any node):** before calling the DataLoader, the node tries to load from the SnapshotStore
3. **On sync event:** when receiving a RefreshEvent from another node, try loading the snapshot first, fall back to DataLoader

### Requirements

Snapshot persistence requires a `SnapshotSerializer<T>` on the catalog:

```java
Catalog.of(Event.class)
    .named("events")
    .loadWith(loader)
    .serializer(new EventSnapshotSerializer())   // Required for snapshot persistence
    .build();
```

Without a serializer, the SnapshotStore is silently skipped.

---

## S3 Snapshot Store

Stores serialized data and metadata in S3-compatible storage (AWS S3, MinIO, LocalStack).

### Dependency

```xml
<dependency>
  <groupId>io.github.waabox</groupId>
  <artifactId>andersoni-snapshot-s3</artifactId>
  <version>1.1.2</version>
</dependency>
```

### Usage

```java
S3SnapshotConfig config = S3SnapshotConfig.builder()
    .bucket("my-bucket")
    .region(Region.US_EAST_1)
    .prefix("andersoni/")               // default
    .s3Client(s3Client)                 // optional, creates default if omitted
    .build();

S3SnapshotStore store = new S3SnapshotStore(config);
```

### Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `bucket` | *required* | S3 bucket name |
| `region` | *required* | AWS region |
| `prefix` | `andersoni/` | Key prefix for all snapshots |
| `s3Client` | auto-created | Custom S3Client (for MinIO, LocalStack, etc.) |

### Storage Layout

```
s3://my-bucket/andersoni/events/snapshot.dat
```

Metadata stored as S3 user metadata headers:
- `x-amz-meta-hash` — SHA-256 hash of the data
- `x-amz-meta-version` — snapshot version number
- `x-amz-meta-created-at` — ISO-8601 timestamp
- `x-amz-meta-catalog-name` — catalog name

### MinIO / LocalStack

For S3-compatible services, provide a custom `S3Client`:

```java
S3Client s3Client = S3Client.builder()
    .endpointOverride(URI.create("http://minio:9000"))
    .credentialsProvider(StaticCredentialsProvider.create(
        AwsBasicCredentials.create("minioadmin", "minioadmin")))
    .region(Region.US_EAST_1)
    .forcePathStyle(true)       // Required for MinIO
    .build();
```

---

## Filesystem Snapshot Store

Stores snapshots on the local filesystem. Intended for development and testing only.

### Dependency

```xml
<dependency>
  <groupId>io.github.waabox</groupId>
  <artifactId>andersoni-snapshot-fs</artifactId>
  <version>1.1.2</version>
</dependency>
```

### Usage

```java
FileSystemSnapshotStore store = new FileSystemSnapshotStore(
    Path.of("/var/data/andersoni-snapshots")
);
```

### Storage Layout

```
/var/data/andersoni-snapshots/
  └── events/
      ├── snapshot.dat          # Serialized data (byte[])
      └── snapshot.dat.meta     # Metadata (key=value pairs)
```

Metadata file format:
```
hash=abc123...
version=5
createdAt=2026-02-17T10:30:00Z
```

### Atomic Writes

The filesystem store uses atomic writes: data is written to a temporary file first, then renamed to the final path. This prevents corruption if the process crashes mid-write.

### Limitations

- Not suitable for multi-node deployments (each node sees only its own filesystem)
- No automatic cleanup of old snapshots
