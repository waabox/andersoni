# Typed Query Results

## Problem

`Andersoni.search()` returns `List<?>` and `Andersoni.query()` returns `QueryStep<?>` because catalogs are stored type-erased in a `Map<String, Catalog<?>>`. Callers must cast manually at every call site.

## Decision

Add convenience overloads that accept `Class<T>` and return typed results. Keep the existing wildcard methods untouched as the safe contract.

## API

Four public methods on `Andersoni`:

| Method | Returns | Contract |
|--------|---------|----------|
| `search(catalogName, indexName, key)` | `List<?>` | Safe, existing |
| `search(catalogName, indexName, key, Class<T>)` | `List<T>` | Convenience, unchecked cast |
| `query(catalogName, indexName)` | `QueryStep<?>` | Safe, existing |
| `query(catalogName, indexName, Class<T>)` | `QueryStep<T>` | Convenience, unchecked cast |

## Rationale

- The user defined the catalog type at construction time (`Catalog.of(Event.class)`), so they know the expected type.
- No structural changes. No type validation. No performance overhead.
- The library moves the `@SuppressWarnings("unchecked")` from the caller into itself.
- Backward compatible: existing code compiles and works unchanged.

## Scope

- Add two overloaded methods to `Andersoni.java`.
- Add tests for the new overloads.
- Update JavaDoc examples in `Andersoni.java`.
