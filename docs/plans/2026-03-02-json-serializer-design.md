# andersoni-json-serializer Design

## Summary

New module providing a generic Jackson-based `SnapshotSerializer<T>` implementation.

## Module

`andersoni-json-serializer` — package `org.waabox.andersoni.snapshot.json`

## Dependencies

- `andersoni-core` (for `SnapshotSerializer<T>`)
- `jackson-databind`
- `jackson-datatype-jsr310`

## API

`JacksonSnapshotSerializer<T>`:

- `JacksonSnapshotSerializer(ObjectMapper, TypeReference<List<T>>)` — full control
- `JacksonSnapshotSerializer(TypeReference<List<T>>)` — default ObjectMapper with JavaTimeModule
- `serialize(List<T>)` → `byte[]`
- `deserialize(byte[])` → `List<T>`
- Wraps `IOException` in `UncheckedIOException`
- `Objects.requireNonNull` on all public API parameters

## Tests

- Roundtrip with a test record containing `LocalDateTime`
- Null checks on constructor, serialize, and deserialize
- Custom ObjectMapper works correctly

## Scope

Only the SnapshotSerializer implementation. RefreshEventCodec stays in core.
