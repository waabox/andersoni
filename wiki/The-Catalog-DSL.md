# The Catalog DSL

Andersoni uses a fluent, type-safe builder DSL to define catalogs. The builder guides you through each step with compile-time safety.

## Builder Steps

```
Catalog.of(Type.class)             // 1. Type inference
    .named("catalog-name")         // 2. Name
    .loadWith(dataLoader)          // 3. Data source (DataLoader or static)
    .serializer(serializer)        // 4. Optional: serializer
    .refreshEvery(duration)        // 5. Optional: scheduled refresh
    .index("name").by(fn)          // 6. One or more indices
    .build()                       // 7. Build
```

## Step 1: Type Inference

```java
Catalog.of(Event.class)
```

Sets the generic type `<T>` for the catalog. All subsequent builder methods are type-safe against this type.

## Step 2: Name

```java
.named("events")
```

The catalog name is used as the key for `search()`, `refreshAndSync()`, snapshot storage, and sync events. It must be unique across all registered catalogs.

## Step 3: Data Source

Choose one:

### DataLoader (dynamic data)

```java
.loadWith(() -> repository.findAll())
```

The `DataLoader<T>` is a functional interface called during bootstrap and on every refresh. It must return a complete, non-null list.

### Static Data

```java
.data(List.of(item1, item2, item3))
```

For datasets that never change. You can still call `catalog.refresh(newList)` to replace the data explicitly.

## Step 4: Serializer (Optional)

```java
.serializer(new EventSnapshotSerializer())
```

A `SnapshotSerializer<T>` converts the dataset to/from `byte[]`. It is required for:
- **Snapshot persistence** (S3, filesystem)
- **Deterministic hash computation** (SHA-256 of serialized bytes)

Without a serializer, the hash falls back to `toString()` which may be unreliable.

### Example Serializer (Jackson)

```java
public class EventSnapshotSerializer implements SnapshotSerializer<Event> {

    private final ObjectMapper mapper;

    public EventSnapshotSerializer() {
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
    }

    @Override
    public byte[] serialize(final List<Event> items) {
        try {
            return mapper.writeValueAsBytes(items);
        } catch (final JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize events", e);
        }
    }

    @Override
    public List<Event> deserialize(final byte[] data) {
        try {
            return mapper.readValue(data,
                mapper.getTypeFactory()
                    .constructCollectionType(List.class, Event.class));
        } catch (final IOException e) {
            throw new RuntimeException("Failed to deserialize events", e);
        }
    }
}
```

## Step 5: Scheduled Refresh (Optional)

```java
.refreshEvery(Duration.ofMinutes(5))
```

Enables automatic periodic refresh. **Leader-only** — only the leader node executes the DataLoader and broadcasts the change. Non-leader nodes receive updates via sync.

Requires a `DataLoader` (does not work with `.data()`).

## Step 6: Indices

At least one index is required. You can define as many as needed.

### Simple Key

```java
.index("by-sport").by(Event::sport)
```

Extracts the key directly from the item.

### Composed Key

```java
.index("by-venue").by(Event::venue, Venue::name)
```

Applies two functions in sequence: `Event → Venue → String`. If the intermediate value is `null`, the key is `null` and the item is not indexed under that key.

### Multiple Indices

```java
.index("by-sport").by(Event::sport)
.index("by-venue").by(Event::venue, Venue::name)
.index("by-status").by(Event::status)
```

Each index creates an independent `Map<String, List<T>>`. Searches on different indices are completely independent.

## Step 7: Build

```java
.build()
```

Returns an immutable `Catalog<T>` ready to be registered with `Andersoni`.

## Complete Example

```java
Catalog<Event> events = Catalog.of(Event.class)
    .named("events")
    .loadWith(() -> eventRepository.findAll())
    .serializer(new EventSnapshotSerializer())
    .refreshEvery(Duration.ofMinutes(5))
    .index("by-sport").by(Event::sport)
    .index("by-venue").by(Event::venue, Venue::name)
    .index("by-status").by(Event::status)
    .build();

Andersoni andersoni = Andersoni.builder()
    .nodeId("node-1")
    .build();

andersoni.register(events);
andersoni.start();
```
