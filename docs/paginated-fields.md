# Paginated Fields

## What's in place

**Validation:** Paginated fields with `OrderBySpec.None` produce a clear error directing users to add `@defaultOrder` or `@orderBy`.

**Native `@asConnection`:** `FieldBuilder` reads the directive directly, produces `FieldWrapper.Connection` with `defaultPageSize` and `connectionName`. `PaginationSpec` is synthesized (forward: `first` + `after`) when no explicit pagination args exist. Pre-expanded Connection types (from the schema transform or hand-written) continue to work via structural detection.

**Keyset pagination SQL:** `TypeClassGenerator.selectMany` has a paginated overload with `seekValues` + `limit` using jOOQ `.seek()` + `.limit()`. `fields(sel, extraFields)` force-includes ORDER BY columns for cursor construction.

**Generated utilities:** `ConnectionResult` (carrier: `Result<Record>` + pageSize + cursor + ORDER BY columns) and `ConnectionHelper` (edges, nodes, pageInfo, edgeNode, edgeCursor, encodeCursor, decodeCursor). Both emitted as source files — no runtime dependency.

**Connection fetcher:** `TypeFetcherGenerator.buildQueryConnectionFetcher` dispatches for `QueryTableField` with `FieldWrapper.Connection`. Extracts pagination args, decodes cursor, calls paginated `selectMany`, wraps in `ConnectionResult`.

**Connection wiring:** `GraphitronWiringClassGenerator` emits `TypeRuntimeWiring` entries for Connection types (edges, nodes, pageInfo) and Edge types (node, cursor) using `ConnectionHelper` method references.

## What's next

### 1. Dynamic ordering cursor support

For `OrderBySpec.Argument`, the cursor columns depend on which `NamedOrder` the client selected. The fetcher must resolve the active ordering, pass it into the `ConnectionResult`, and `ConnectionHelper` must use it for cursor encoding.

Currently the connection fetcher only handles `OrderBySpec.Fixed`. This step depends on the [orderBy implementation](orderby-implementation.md) completing `namedOrders` population and the `<fieldName>OrderBy` helper method.

### 2. Backward pagination (`last`/`before`)

Reverse the ORDER BY direction, seek from the `before` cursor, limit to `last + 1`, then reverse the result to restore natural order. `hasPreviousPage` and `hasNextPage` swap roles. `ConnectionHelper` methods handle both directions based on which arguments are present in the `ConnectionResult`.

`@asConnection` currently synthesizes only `first`/`after`. Extend it to optionally add `last`/`before` — either always (bidirectional by default) or via a directive argument.

### 3. Execution test

No end-to-end test exercises pagination against a real database yet. Add to `graphitron-rewrite-test-spec`:
- A connection field in the schema (e.g., `films: [Film] @asConnection @defaultOrder(primaryKey: true)`)
- Seed data with enough rows to paginate
- Test: first page returns `pageSize` items + correct `hasNextPage`/cursors
- Test: second page with `after` cursor returns next items
- Test: empty result when cursor is past the end

### 4. Document transform coexistence

When the schema goes through both the transform AND the builder, `@asConnection` is stripped by the transform before the builder sees it. The builder falls back to structural detection, which works but loses `defaultPageSize` (defaults to 100). Document this: users who need custom `defaultFirstValue` and use the schema transform should set it on the directive before transformation, or configure the transform's default.
