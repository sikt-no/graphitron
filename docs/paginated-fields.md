# Paginated Fields

## What's in place

**Validation:** Paginated fields with `OrderBySpec.None` produce a clear error directing users to add `@defaultOrder` or `@orderBy`.

**Native `@asConnection`:** `FieldBuilder` reads the directive directly, produces `FieldWrapper.Connection` with `defaultPageSize` and `connectionName`. `PaginationSpec` is synthesized (forward: `first` + `after`) when no explicit pagination args exist. Pre-expanded Connection types (from the schema transform or hand-written) continue to work via structural detection.

**Keyset pagination SQL:** `TypeClassGenerator.selectMany` has a paginated overload with `seekValues` + `limit` using jOOQ `.seek()` + `.limit()`. `fields(sel, extraFields)` force-includes ORDER BY columns for cursor construction.

**Generated utilities:** `ConnectionResult` (carrier: `Result<Record>` + pageSize + cursor + ORDER BY columns) and `ConnectionHelper` (edges, nodes, pageInfo, edgeNode, edgeCursor, encodeCursor, decodeCursor). Both emitted as source files — no runtime dependency.

**Connection fetcher:** `TypeFetcherGenerator.buildQueryConnectionFetcher` dispatches for `QueryTableField` with `FieldWrapper.Connection`. Extracts pagination args, decodes cursor, calls paginated `selectMany`, wraps in `ConnectionResult`.

**Connection wiring:** `GraphitronWiringClassGenerator` emits `TypeRuntimeWiring` entries for Connection types (edges, nodes, pageInfo) and Edge types (node, cursor) using `ConnectionHelper` method references.

## Known bugs

### Connection fetcher ignores `<fieldName>OrderBy` helper

`buildQueryConnectionFetcher` calls `buildOrderByCode(qtf.orderBy())` (the no-fieldName overload), which for `OrderBySpec.Argument` falls back to the base (primary key) ordering. The regular list fetcher correctly calls `buildOrderByCode(qtf.orderBy(), qtf.name())`, which dispatches to the generated `<fieldName>OrderBy` helper.

A connection field with `@orderBy` silently sorts by primary key regardless of what the client requests.

**Fix:** `buildQueryConnectionFetcher` line 258 should pass `qtf.name()`:
```java
builder.addCode(buildOrderByCode(qtf.orderBy(), qtf.name()));
```

### `decodeCursor` breaks on string values containing commas

`ConnectionHelperClassGenerator` emits `json.split(",")` (line 180) to parse the cursor JSON array. String column values containing commas (e.g., `"Smith, Jr."`) produce extra segments and corrupt seek values.

**Fix:** split on `,` only outside quoted tokens, or use a proper JSON array parser.

### Fetcher hardcodes `"first"` / `"after"` instead of reading `PaginationSpec`

`buildQueryConnectionFetcher` emits `env.getArgument("first")` and `env.getArgument("after")` as literal strings. The model already carries `qtf.pagination()` with the actual argument names. This violates the generation-thinking principle — generators should read pre-resolved data from the model.

**Fix:** read from `qtf.pagination().first().name()` and `qtf.pagination().after().name()`.

### `ConnectionResult.cursor` is confusingly named

The `cursor` field stores the incoming `after` argument value, used only for the `hasPreviousPage()` shortcut (`return cursor != null`). It is not an outgoing cursor. The name will confuse anyone implementing backward pagination.

**Fix:** rename to `afterCursor`.

## What's next

### 1. Fix known bugs above

The orderBy helper bug is the highest priority — it must be fixed before dynamic cursor threading makes sense. The cursor parsing bug is latent until string column values hit production. The PaginationSpec and naming issues are design hygiene.

### 2. Dynamic ordering cursor support

For `OrderBySpec.Argument`, the cursor columns depend on which `NamedOrder` the client selected. After bug 1 is fixed, the fetcher correctly calls the `<fieldName>OrderBy` helper for SQL ordering. What remains is threading the resolved columns into the `ConnectionResult` carrier so `ConnectionHelper.edges()` and `pageInfo()` encode cursors from the correct columns (not just the base ordering columns).

### 3. Backward pagination (`last`/`before`)

Reverse the ORDER BY direction, seek from the `before` cursor, limit to `last + 1`, then reverse the result to restore natural order. `hasPreviousPage` and `hasNextPage` swap roles. `ConnectionHelper` methods handle both directions based on which arguments are present in the `ConnectionResult`.

`@asConnection` currently synthesizes only `first`/`after`. Extend it to optionally add `last`/`before` — either always (bidirectional by default) or via a directive argument.

### 4. Structural tests for connection fetcher

`buildQueryConnectionFetcher` has zero structural test coverage. Add to `TypeFetcherGeneratorTest`:
- `QueryTableField` with `FieldWrapper.Connection` → dispatches to connection fetcher (not the regular fetcher)
- Return type is `ConnectionResult`, not `Result<Record>`
- Method body calls paginated `selectMany`
- Method body reads pagination arg names from `PaginationSpec`

### 5. Execution test

No end-to-end test exercises pagination against a real database yet. Add to `graphitron-rewrite-test-spec`:
- A connection field in the schema (e.g., `films: [Film] @asConnection @defaultOrder(primaryKey: true)`)
- Seed data with enough rows to paginate
- Test: first page returns `pageSize` items + correct `hasNextPage`/cursors
- Test: second page with `after` cursor returns next items
- Test: empty result when cursor is past the end

### 6. Document transform coexistence

When the schema goes through both the transform AND the builder, `@asConnection` is stripped by the transform before the builder sees it. The builder falls back to structural detection, which works but loses `defaultPageSize` (defaults to 100). Document this: users who need custom `defaultFirstValue` and use the schema transform should set it on the directive before transformation, or configure the transform's default.
