# Paginated Fields

## What's in place

**Validation:** Paginated fields with `OrderBySpec.None` produce a clear error.

**Native `@asConnection`:** `FieldBuilder` reads the directive directly, produces `FieldWrapper.Connection` with `defaultPageSize` and `connectionName`. `PaginationSpec` synthesized. Pre-expanded Connection types continue to work via structural detection, with `typeName` passed as `connectionName` so wiring uses the SDL type name directly.

**Keyset pagination SQL:** `buildQueryConnectionFetcher` inlines a paginated DSL chain using jOOQ `.seek()` + `.limit()`. `Type.$fields(sel, table, env)` is called for projection; extra ordering columns are merged by name into the select list.

**Type-safe cursors:** Cursor encode/decode is column-driven — no hand-rolled type-tag system. `encodeCursor(Record, List<Field<?>>)` serialises each value via `val.toString()`, encoding nulls as JSON `null`. `decodeCursor(String, List<Field<?>>)` returns `Field<?>[]`: `DSL.val(field.getDataType().convert(token), field.getDataType())` per column, or `DSL.noField(col)` when cursor is `null`. `DSL.noField()` makes `.seek()` a no-op (jOOQ 3.18+), enabling a single-expression query — no if-branch.

**Backward pagination:** `ConnectionResult` carries `beforeCursor`, `backward` flag. `trimmedResult()` re-reverses the result for backward pagination. `hasNextPage`/`hasPreviousPage` swap roles when `backward = true`. `reverseOrderBy(List<SortField<?>>)` is emitted as a private helper in each Fetchers class that has a connection field; it uses jOOQ's `$field()` and `$sortOrder()` model-API accessors (stable since 3.17) to flip sort directions.

**Relay first+last validation:** `buildQueryConnectionFetcher` emits a runtime check that throws `IllegalArgumentException` when both `first` and `last` are supplied.

**Generated utilities:** `ConnectionResult` (carrier: `Result<Record>` + pageSize + afterCursor + beforeCursor + backward + ORDER BY columns), `ConnectionHelper` (edges, nodes, pageInfo, edgeNode, edgeCursor, encodeCursor, decodeCursor), `OrderByResult` (pairs `List<SortField<?>>` with `List<Field<?>>` for cursor columns). All emitted as source files.

**Connection fetcher:** `buildQueryConnectionFetcher` reads all four Relay pagination arg names from `PaginationSpec`, decodes cursor with column metadata, calls `$fields` for projection (merging extra ordering columns), reverses ordering for backward pagination, and executes the inline paginated query as a single expression.

**Dynamic ordering cursors:** `<fieldName>OrderBy` helper returns `OrderByResult`, so sort fields and cursor columns are derived together from a single dispatch. `buildConnectionOrderingBlock` emits both `orderBy` and `extraFields` for Fixed/Argument/None.

**Connection wiring:** `GraphitronWiringClassGenerator` emits `TypeRuntimeWiring` entries for Connection types (edges, nodes, pageInfo) and Edge types (node, cursor).

**Structural tests:** 19 tests covering connection field return type, pagination arg names, helper method presence, OrderByResult return type, custom arg names, backward pagination (reverseOrderBy helper, Relay validation, seek expression).

**Execution tests:** 12 end-to-end tests against a real PostgreSQL database covering: forward pagination (first page, cursor navigation, last page), backward pagination (last N films, with before cursor), dynamic ordering (default, by title, with cursor navigation), and default page size.

## What's next

### 1. Document transform coexistence

When the schema goes through both the transform AND the builder, `@asConnection` is stripped by the transform before the builder sees it. The builder falls back to structural detection, which works but loses `defaultPageSize` (defaults to 100). Document this.

---

## Architecture notes

### Connection field naming

For `@asConnection` fields: `connectionName` comes from the directive arg, or defaults to `{ParentType}{capitalize(fieldName)}Connection`.

For structural detection (pre-expanded Connection types in SDL): `FieldBuilder.buildWrapper` now passes `typeName` as `connectionName`, so the wiring generator uses the SDL type name directly rather than deriving it from the parent type and field name.

### Cursor format

Each cursor is a Base64-encoded JSON array: `["val1", "val2", null, ...]`. Values are serialised using the column's `toString()` representation; `null` values use JSON `null`. Decoded using `org.jooq.tools.json.JSONValue` (bundled in jOOQ; internal API, no stability guarantee, but acceptable for self-contained generated code).

No type tags — the ORDER BY columns in `ConnectionResult.orderByColumns` carry the type information. `DataType.convert(stringVal)` reconstructs the correct Java type during decode.

### Backward pagination invariants

- `backward = last != null`
- `effectiveOrderBy = backward ? reverseOrderBy(orderBy) : orderBy`
- `cursor = backward ? before : after`
- `trimmedResult()` re-reverses the raw result when `backward = true` (the reversed query returns rows in the opposite order)
- `hasNextPage` → true when backward AND afterCursor was supplied (there is a "next" page in forward direction)
- `hasPreviousPage` → true when backward AND result was over-fetched (there is a previous page in backward direction)
