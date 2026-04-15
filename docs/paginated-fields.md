# Paginated Fields

Plan for implementing Relay Connection pagination in the rewrite pipeline.

## Design decisions

**Keyset pagination only.** jOOQ `.seek()` + `.limit(pageSize + 1)` for deterministic, stable cursors. No OFFSET/LIMIT.

**Bidirectional.** Both forward (`first`/`after`) and backward (`last`/`before`) pagination. `@asConnection` gains a way to control which arguments are added; hand-written Connection types declare whichever arguments they need.

**Generated wiring, not runtime framework.** The connection envelope (edges, pageInfo, cursors, nodes) is wired through generated DataFetcher registrations in the `*Fetchers` wiring — not via a runtime helper library. Graphitron generates code; it is not a runtime dependency.

**`@asConnection` native to the builder.** The builder reads `@asConnection` directly and synthesizes Connection/Edge/PageInfo types internally. Pre-expanded Connection structures (from the schema transform or hand-written) continue to work via structural detection. The schema transform becomes optional tooling, not a prerequisite.

**Cursors from ORDER BY column values.** No extra cursor column in the SELECT. The ORDER BY columns are force-included in the selected column list for connection fields. Cursor encoding/decoding works directly on the Record's ORDER BY column values. The cursor DataFetcher is only called when the client requests it.

**Order must be stable across pages.** For dynamic ordering (`OrderBySpec.Argument`), the client must pass the same `orderBy` argument when paginating with `after`/`before`. The cursor encodes column values positionally; the active ordering determines which columns those values correspond to. Changing ordering mid-pagination invalidates the cursor.

## Taxonomy work

### 1. `@asConnection` in the builder

`GraphitronSchemaBuilder` reads `@asConnection` on list fields and synthesizes:
- A `FieldWrapper.Connection` on the field (already detected structurally today — this makes it directive-driven too)
- A `PaginationSpec` from the directive arguments (currently built from the field's `first`/`after`/`last`/`before` args — this adds the ability to configure them via the directive)
- Synthetic type entries in the `GraphitronSchema` for the Connection, Edge, and PageInfo types

The field's `returnType` resolves to the element type (e.g., `Film`), not the Connection wrapper. `FieldWrapper.Connection` captures the wrapping. This is already how the structural detection works.

Pre-expanded Connection types continue to work as before — the builder detects them structurally via the edges→node pattern when `@asConnection` is absent.

### 2. `PaginationSpec` cleanup

`PaginationSpec` currently allows `null` on the field (no pagination) and `null` on individual args (partial pagination). Following the taxonomy principle from our earlier analysis, consider making this a sealed interface with `Paginated`/`None` variants to eliminate null checks. This is optional — the generator can check for null — but aligns with how `OrderBySpec` handles absence.

### 3. Validation: paginated fields must have ordering

Add to `GraphitronSchemaValidator`: if a field has a non-null `PaginationSpec` and `OrderBySpec.None`, emit a validation error. Keyset pagination without ordering is broken by definition. The legacy `PaginationValidator` has this check; the rewrite validator doesn't.

### 4. Connection type model

The synthetic Connection/Edge types need representation in `GraphitronType`. Options:

- **Lightweight:** Don't add new type variants. The Connection and Edge types are wiring-only — the generator knows their shape from the field's `FieldWrapper.Connection` + `OrderBySpec`. No classification needed because the structure is fixed (edges, node, cursor, pageInfo).
- **Explicit:** Add `ConnectionType` and `EdgeType` variants to `GraphitronType`. These carry the element type reference and the ORDER BY columns needed for cursor construction.

The lightweight approach is simpler and avoids polluting the type taxonomy with synthetic types that have no user-visible directives. The generator can derive everything it needs from the paginated field itself. Lean toward lightweight unless we find a reason the types need independent classification.

## Generator work

### 5. Fetcher: connection field method

For a connection field (`FieldWrapper.Connection`), the generated fetcher:
- Extracts `first`/`after` (and `last`/`before` for bidirectional) from `env.getArgument(...)` using the names from `PaginationSpec`
- Decodes the cursor token into seek values (column values matching the active ORDER BY)
- Calls the type class `selectMany` with condition, orderBy, seek values, and `limit = pageSize + 1`
- The result (`Result<Record>`) contains up to `pageSize + 1` rows — the over-fetch determines `hasNextPage`

The return type of the fetcher method stays `Result<Record>` — the connection envelope is handled by wiring, not by the fetcher.

### 6. Type class: seek + limit on selectMany

`TypeClassGenerator.selectMany` gains an overload (or the existing method gains parameters) for seek-based pagination:
- `seekValues: Object[]` (decoded from cursor) — passed to jOOQ `.seek(seekValues)`
- `limit: int` — passed to `.limit(limit)`

For non-paginated calls, the existing signature (no seek/limit) remains.

The `fields(sel)` method must force-include ORDER BY columns even when the client doesn't select the corresponding GraphQL fields. The ordering columns must be in the `Result<Record>` for cursor construction.

### 7. Wiring: Connection/Edge/PageInfo types

The generated `*Fetchers.wiring()` adds entries for the synthetic types. For a `films: [Film] @asConnection` field on `Language`:

```java
// LanguageFetchers.wiring() — existing
.dataFetcher("films", LanguageFetchers::films)

// Plus: the generated wiring registers entries for the connection types
```

And separately (generated alongside LanguageFetchers, or in a dedicated method):

```java
// LanguageFilmsConnection type wiring
TypeRuntimeWiring.newTypeWiring("LanguageFilmsConnection")
    .dataFetcher("edges", env -> trimToPageSize(env.getSource()))
    .dataFetcher("nodes", env -> trimToPageSize(env.getSource()))  // nodes without cursor
    .dataFetcher("pageInfo", env -> buildPageInfo(env.getSource(), pageSize))

// LanguageFilmsEdge type wiring
TypeRuntimeWiring.newTypeWiring("LanguageFilmsEdge")
    .dataFetcher("node", env -> env.getSource())  // Record is the node
    .dataFetcher("cursor", env -> encodeCursor(env.getSource(), orderByColumns))

// PageInfo wiring — shared or per-connection
```

The `orderByColumns` are compile-time constants from the field's `OrderBySpec`. For `OrderBySpec.Fixed`, this is a static list. For `OrderBySpec.Argument`, the columns depend on the runtime `orderBy` argument — the cursor encoder needs access to the active `NamedOrder`'s column list, which must be threaded through from the fetcher (e.g., stashed in the GraphQL local context).

### 8. Dynamic ordering + cursors

For `OrderBySpec.Argument`, the fetcher resolves which `NamedOrder` the client selected, extracts the corresponding `Fixed` column list, and uses that for both:
- The jOOQ `.orderBy(...)` + `.seek(...)` call
- The cursor encoding (stashed in context so the Edge cursor resolver can access it)

The cursor encodes column values positionally. The decoder in the fetcher uses the same `NamedOrder` resolution (from the current query's `orderBy` argument) to know which columns those positions correspond to. If the client changes ordering between pages, the cursor is invalid — this is the documented contract.

## Implementation order

| Step | What | Depends on |
|---|---|---|
| 1 | Validation: paginated fields must have ordering | Nothing |
| 2 | `@asConnection` native in builder (synthesize types, set pagination) | Nothing |
| 3 | Force-include ORDER BY columns in `fields(sel)` for connection fields | Nothing |
| 4 | `selectMany` with seek + limit parameters | Step 3 |
| 5 | Fetcher: connection field method (extract args, decode cursor, call selectMany) | Steps 2, 4 |
| 6 | Wiring: Connection/Edge/PageInfo type entries | Step 5 |
| 7 | Dynamic ordering cursor support (`OrderBySpec.Argument` + context threading) | Steps 5, 6 |
| 8 | Backward pagination (`last`/`before`) | Steps 5, 6 |

Steps 1-3 are independent and can be done in parallel. Steps 4-6 are the core implementation sequence. Steps 7-8 are enhancements that build on the core.

## Test strategy

- **Validation test:** paginated field + `OrderBySpec.None` → error
- **Builder test:** `@asConnection` on a list field → `FieldWrapper.Connection` + `PaginationSpec` + synthetic types in schema
- **Builder test:** pre-expanded Connection type (no directive) → same classification as today
- **Generator unit test:** connection field produces fetcher with seek/limit call, wiring includes Connection/Edge entries
- **Pipeline test:** SDL with `@asConnection` → full generated class set including connection wiring
- **Test-spec schema:** add a connection field to the existing schema, add execution test for paginated query with cursors
