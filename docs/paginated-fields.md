# Paginated Fields

Plan for implementing Relay Connection pagination in the rewrite pipeline.

## Design decisions

**Keyset pagination only.** jOOQ `.seek()` + `.limit(pageSize + 1)` for deterministic, stable cursors. No OFFSET/LIMIT.

**Bidirectional.** Both forward (`first`/`after`) and backward (`last`/`before`) pagination. `@asConnection` gains a way to control which arguments are added; hand-written Connection types declare whichever arguments they need.

**Generated wiring, not runtime framework.** The connection envelope (edges, pageInfo, cursors, nodes) is wired through generated DataFetcher registrations in the `*Fetchers` wiring — not via a runtime helper library. Graphitron generates code; it is not a runtime dependency.

**`@asConnection` native to the builder.** The builder reads `@asConnection` directly and synthesizes Connection/Edge/PageInfo types internally. Pre-expanded Connection structures (from the schema transform or hand-written) continue to work via structural detection. The schema transform becomes optional tooling, not a prerequisite.

**Cursors from ORDER BY column values.** No extra cursor column in the SELECT. The ORDER BY columns are force-included in the selected column list for connection fields. Cursor format: Base64-encoded JSON array of column values with type tags (e.g., `["i:42","s:Alien"]`). NULLs are encoded explicitly. Column types are known at generation time from `ColumnRef.columnClass()`.

**Order must be stable across pages.** For dynamic ordering (`OrderBySpec.Argument`), the client must pass the same `orderBy` argument when paginating with `after`/`before`. The cursor encodes column values positionally; the active ordering determines which columns those positions correspond to. Cursor decoding should detect a mismatch between cursor column count and the active ordering's column count, and return a clear error rather than silently producing wrong results.

**`ConnectionResult` carrier.** The connection field fetcher returns a `ConnectionResult` (generated class) wrapping the `Result<Record>` + pagination args (`pageSize`, cursor) + the resolved ORDER BY column list. This is the `env.getSource()` for all Connection-level resolvers (`edges`, `nodes`, `pageInfo`). Without a carrier, sibling resolvers have no access to the parent field's arguments — `env.getSource()` would be a bare `Result<Record>` with no context.

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
- Resolves the active ORDER BY columns (trivial for `Fixed`; for `Argument`, resolves the selected `NamedOrder`)
- Decodes the cursor token into seek values (column values matching the active ORDER BY), validating column count matches
- Calls the type class `selectMany` with condition, orderBy, seek values, and `limit = pageSize + 1`
- Wraps the result in a `ConnectionResult(result, pageSize, cursor, orderByColumns)` and returns it

The return type is `ConnectionResult` — a generated carrier class that holds the `Result<Record>` plus the context that Connection-level resolvers need. The connection envelope is handled by wiring, not by the fetcher.

### 7. Wiring: Connection/Edge/PageInfo types

A `ConnectionHelper` class is generated once (like `ColumnFetcher` — both are emitted as Java source by a `*ClassGenerator`, not shipped as a library). The generated `*Fetchers` wiring registers the connection types using it:

```java
// Connection type — one per connection field
TypeRuntimeWiring.newTypeWiring("LanguageFilmsConnection")
    .dataFetcher("edges", ConnectionHelper::edges)
    .dataFetcher("nodes", ConnectionHelper::nodes)
    .dataFetcher("pageInfo", ConnectionHelper::pageInfo)

// Edge type — one per connection field
TypeRuntimeWiring.newTypeWiring("LanguageFilmsEdge")
    .dataFetcher("node", ConnectionHelper::edgeNode)
    .dataFetcher("cursor", ConnectionHelper::edgeCursor)
```

The wiring is identical for every connection — `ConnectionHelper` is generic. All Connection-level resolvers receive the `ConnectionResult` carrier as `env.getSource()`, which provides the `Result<Record>`, `pageSize`, cursor, and resolved ORDER BY columns.

**`nodes`** — trims to `pageSize`, returns Records directly. Each Record is the node.

**`edges`** — trims to `pageSize`, wraps each Record into an Edge object carrying the Record (node) and a pre-computed cursor string. Cursor encoding uses the ORDER BY columns from the `ConnectionResult`.

**`pageInfo`** — computes from the `ConnectionResult`:
- `hasNextPage` → `result.size() > pageSize`
- `hasPreviousPage` → cursor (after/before) was non-null (pragmatic shortcut — see note below)
- `startCursor` / `endCursor` → cursor encoding on first/last Record in the trimmed result

**Note on `hasPreviousPage`:** Using `after != null` as the signal for `hasPreviousPage` in forward pagination is a common pragmatic shortcut. The Relay spec technically says forward pagination shouldn't claim to know about prior pages. Acceptable for now; can be refined later if needed.

### 8. Dynamic ordering + cursors

For `OrderBySpec.Argument`, the fetcher (step 6) resolves which `NamedOrder` the client selected and stores the resulting column list in the `ConnectionResult`. `ConnectionHelper` reads it from there — no separate resolution needed.

The cursor encodes column values positionally. The decoder in the fetcher uses the same `NamedOrder` resolution (from the current query's `orderBy` argument) to know which columns those positions correspond to. Column count mismatch between cursor and active ordering is detected and reported as an error.

### 9. Backward pagination (`last`/`before`)

Backward pagination reverses the ORDER BY direction, seeks from the `before` cursor, limits to `last + 1`, then reverses the result to restore natural order. `hasNextPage` and `hasPreviousPage` swap roles. The `ConnectionHelper` methods handle both directions based on which arguments are present.

## Implementation order

| Step | What | Depends on |
|---|---|---|
| 1 | Validation: paginated fields must have ordering | Nothing |
| 2 | `@asConnection` native in builder | Nothing |
| 3 | Force-include ORDER BY columns in `fields(sel)` | Nothing |
| 4 | `selectMany` with seek + limit | Step 3 |
| 5 | `ConnectionResult` carrier + `ConnectionHelper` generated classes | Nothing |
| 6 | Fetcher: connection field method (returns `ConnectionResult`) | Steps 2, 4, 5 |
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
