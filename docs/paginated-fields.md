# Paginated Fields

## What's in place

**Validation:** Paginated fields with `OrderBySpec.None` produce a clear error.

**Native `@asConnection`:** `FieldBuilder` reads the directive directly, produces `FieldWrapper.Connection` with `defaultPageSize` and `connectionName`. `PaginationSpec` synthesized. Pre-expanded Connection types continue to work via structural detection.

**Keyset pagination SQL:** `TypeClassGenerator.selectMany` paginated overload with `seekValues` + `limit` using jOOQ `.seek()` + `.limit()`. `fields(sel, extraFields)` force-includes ORDER BY columns.

**Generated utilities:** `ConnectionResult` (carrier: `Result<Record>` + pageSize + afterCursor + ORDER BY columns), `ConnectionHelper` (edges, nodes, pageInfo, edgeNode, edgeCursor, encodeCursor, decodeCursor with quote-aware parsing), `OrderByResult` (pairs `List<SortField<?>>` with `List<Field<?>>` for cursor columns). All emitted as source files.

**Connection fetcher:** `buildQueryConnectionFetcher` reads pagination arg names from `PaginationSpec`, decodes cursor, calls paginated `selectMany`, wraps in `ConnectionResult`.

**Dynamic ordering cursors:** `<fieldName>OrderBy` helper returns `OrderByResult`, so sort fields and cursor columns are derived together from a single dispatch. `buildConnectionOrderingBlock` emits both `orderBy` and `extraFields` for Fixed/Argument/None.

**Connection wiring:** `GraphitronWiringClassGenerator` emits `TypeRuntimeWiring` entries for Connection types (edges, nodes, pageInfo) and Edge types (node, cursor).

**Structural tests:** 8 tests covering connection field return type, pagination arg names, helper method presence, OrderByResult return type, custom arg names.

## What's next

### 1. Backward pagination (`last`/`before`)

Reverse the ORDER BY direction, seek from the `before` cursor, limit to `last + 1`, then reverse the result to restore natural order. `hasPreviousPage` and `hasNextPage` swap roles. `ConnectionHelper` methods handle both directions based on which arguments are present in the `ConnectionResult`.

`@asConnection` currently synthesizes only `first`/`after`. Extend it to optionally add `last`/`before` — either always (bidirectional by default) or via a directive argument.

### 2. Execution test

No end-to-end test exercises pagination against a real database yet. Add to `graphitron-rewrite-test-spec`:
- A connection field in the schema (e.g., `films: [Film] @asConnection @defaultOrder(primaryKey: true)`)
- Seed data with enough rows to paginate
- Test: first page returns `pageSize` items + correct `hasNextPage`/cursors
- Test: second page with `after` cursor returns next items
- Test: empty result when cursor is past the end
- Test: dynamic ordering (`@orderBy`) produces correct cursors for the selected order

### 3. Document transform coexistence

When the schema goes through both the transform AND the builder, `@asConnection` is stripped by the transform before the builder sees it. The builder falls back to structural detection, which works but loses `defaultPageSize` (defaults to 100). Document this.
