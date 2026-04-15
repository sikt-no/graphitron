# Paginated Fields

Plan for implementing Relay Connection pagination in the rewrite pipeline.

## Design decisions

**Keyset pagination only.** jOOQ `.seek()` + `.limit(pageSize + 1)` for deterministic, stable cursors. No OFFSET/LIMIT.

**Bidirectional.** Both forward (`first`/`after`) and backward (`last`/`before`) pagination. `@asConnection` gains a way to control which arguments are added; hand-written Connection types declare whichever arguments they need.

**Generated wiring, not runtime framework.** The connection envelope (edges, pageInfo, cursors, nodes) is wired through generated DataFetcher registrations in the `*Fetchers` wiring — not via a runtime helper library. Graphitron generates code; it is not a runtime dependency.

**`@asConnection` native to the builder.** The builder reads `@asConnection` directly and synthesizes Connection/Edge/PageInfo types internally. Pre-expanded Connection structures (from the schema transform or hand-written) continue to work via structural detection. The schema transform becomes optional tooling, not a prerequisite.

**Cursors from ORDER BY column values.** No extra cursor column in the SELECT. The ORDER BY columns are force-included in the selected column list for connection fields. Cursor encoding/decoding works directly on the Record's ORDER BY column values.

**Order must be stable across pages.** For dynamic ordering (`OrderBySpec.Argument`), the client must pass the same `orderBy` argument when paginating with `after`/`before`. The cursor encodes column values positionally; the active ordering determines which columns those values correspond to. Changing ordering mid-pagination invalidates the cursor.

**No new type variants.** Connection and Edge types are not added to `GraphitronType`. The generator derives everything from the paginated field's `FieldWrapper.Connection` + `OrderBySpec`. The connection structure is fixed (edges, node, cursor, pageInfo) — no independent classification needed.

## Taxonomy work

### 1. `@asConnection` in the builder

`GraphitronSchemaBuilder` reads `@asConnection` on list fields and synthesizes:
- A `FieldWrapper.Connection` on the field (already detected structurally today — this makes it directive-driven too)
- A `PaginationSpec` from the directive arguments (currently built from the field's `first`/`after`/`last`/`before` args — this adds the ability to configure them via the directive)
- Synthetic type entries in the `GraphitronSchema` for the Connection, Edge, and PageInfo types

The field's `returnType` resolves to the element type (e.g., `Film`), not the Connection wrapper. `FieldWrapper.Connection` captures the wrapping. This is already how the structural detection works.

Pre-expanded Connection types continue to work as before — the builder detects them structurally via the edges→node pattern when `@asConnection` is absent.

### 2. `PaginationSpec` cleanup

`PaginationSpec` currently allows `null` on the field (no pagination) and `null` on individual args (partial pagination). Consider making this a sealed interface with `Paginated`/`None` variants to eliminate null checks. Optional — aligns with how `OrderBySpec` handles absence.

### 3. Validation: paginated fields must have ordering

Add to `GraphitronSchemaValidator`: if a field has a non-null `PaginationSpec` and `OrderBySpec.None`, emit a validation error. Keyset pagination without ordering is broken by definition. The legacy `PaginationValidator` has this check; the rewrite validator doesn't.

## Generator work

### 4. Force-include ORDER BY columns in `fields(sel)`

For connection fields, the `fields()` method in `TypeClassGenerator` must include the ORDER BY columns even when the client doesn't select the corresponding GraphQL fields. The ordering column values must be in the `Result<Record>` for cursor construction downstream.

### 5. `selectMany` with seek + limit

`TypeClassGenerator.selectMany` gains an overload (or additional parameters) for seek-based pagination:
- `seekValues: Object[]` (decoded from cursor) — passed to jOOQ `.seek(seekValues)`
- `limit: int` — passed to `.limit(limit)`

For non-paginated calls, the existing signature (no seek/limit) remains.

### 6. Fetcher: connection field method

For a connection field (`FieldWrapper.Connection`), the generated fetcher:
- Extracts `first`/`after` (and `last`/`before` for bidirectional) from `env.getArgument(...)` using the names from `PaginationSpec`
- Decodes the cursor token into seek values (column values matching the active ORDER BY)
- Calls the type class `selectMany` with condition, orderBy, seek values, and `limit = pageSize + 1`

The return type stays `Result<Record>` — the connection envelope is handled by wiring, not by the fetcher. The result contains up to `pageSize + 1` rows; the over-fetch determines `hasNextPage`.

### 7. Wiring: Connection/Edge/PageInfo types

A `ConnectionHelper` class is generated once (like `ColumnFetcher`). The generated `*Fetchers` wiring registers the connection types using it:

```java
// Connection type — one per connection field
TypeRuntimeWiring.newTypeWiring("LanguageFilmsConnection")
    .dataFetcher("edges", ConnectionHelper::edges)
    .dataFetcher("nodes", ConnectionHelper::nodes)
    .dataFetcher("pageInfo", ConnectionHelper::pageInfo)

// Edge type — one per connection field
TypeRuntimeWiring.newTypeWiring("LanguageFilmsEdge")
    .dataFetcher("node", env -> env.getSource().record())
    .dataFetcher("cursor", env -> env.getSource().cursor())
```

The wiring is identical for every connection — `ConnectionHelper` is generic.

**`nodes`** — trims `Result<Record>` to pageSize, returns Records directly. Each Record is the node, resolved by the existing `ColumnFetcher` wiring on the element type.

**`edges`** — trims `Result<Record>` to pageSize, wraps each Record into an Edge carrying the Record (node) and a pre-computed cursor string. The cursor is encoded from the Record's ORDER BY column values, resolved from the parent field's arguments.

**`pageInfo`** — computes from the untrimmed `Result<Record>`:
- `hasNextPage` → `result.size() > pageSize`
- `hasPreviousPage` → `after` argument was non-null
- `startCursor` / `endCursor` → cursor encoding on first/last Record in the trimmed result

Both `edges` and `pageInfo` resolve the active ORDER BY columns from the parent field's arguments — this handles both `OrderBySpec.Fixed` (static columns) and `OrderBySpec.Argument` (dynamic, client-selected ordering).

### 8. Dynamic ordering + cursors

For `OrderBySpec.Argument`, both the fetcher (step 6) and `ConnectionHelper` (step 7) resolve which `NamedOrder` the client selected from the `orderBy` argument. The resolution logic is shared — both need `(orderBy argument value) → (column list)`.

The cursor encodes column values positionally. The decoder in the fetcher uses the same `NamedOrder` resolution to know which columns those positions correspond to.

### 9. Backward pagination (`last`/`before`)

Backward pagination reverses the ORDER BY direction, seeks from the `before` cursor, limits to `last + 1`, then reverses the result to restore natural order. `hasNextPage` and `hasPreviousPage` swap roles. The `ConnectionHelper` methods handle both directions based on which arguments are present.

## Implementation order

| Step | What | Depends on |
|---|---|---|
| 1 | Validation: paginated fields must have ordering | Nothing |
| 2 | `@asConnection` native in builder | Nothing |
| 3 | Force-include ORDER BY columns in `fields(sel)` | Nothing |
| 4 | `selectMany` with seek + limit | Step 3 |
| 5 | `ConnectionHelper` generated utility class | Nothing |
| 6 | Fetcher: connection field method | Steps 2, 4 |
| 7 | Wiring: Connection/Edge/PageInfo entries | Steps 5, 6 |
| 8 | Dynamic ordering cursor support | Steps 6, 7 |
| 9 | Backward pagination | Steps 6, 7 |

Steps 1-3 and 5 are independent. Steps 4-7 are the core sequence. Steps 8-9 build on the core.

## Test strategy

- **Validation test:** paginated field + `OrderBySpec.None` → error
- **Builder test:** `@asConnection` on a list field → `FieldWrapper.Connection` + `PaginationSpec` + synthetic types in schema
- **Builder test:** pre-expanded Connection type (no directive) → same classification as today
- **Generator unit test:** connection field produces fetcher with seek/limit call, wiring includes Connection/Edge entries
- **Pipeline test:** SDL with `@asConnection` → full generated class set including connection wiring
- **Test-spec schema:** add a connection field, execution test for paginated query with cursors
