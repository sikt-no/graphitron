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

### 1. Type-safe cursors driven by ORDER BY column metadata

The current cursor format carries its own type system (`i:`, `l:`, `s:`, `b:` tags per value) and decodes into `Object[]` passed to jOOQ's untyped `seek(Object...)`. This is redundant and fragile — the `OrderByResult` already carries the `List<Field<?>>` with exact column data types.

**Replace the type-tag system with column-driven encode/decode:**

- **Encode:** `field.getDataType().convert(val).toString()` → Base64. No type tags — the ORDER BY columns *are* the type information.
- **Decode:** Base64 → string → `field.getDataType().convert(stringVal)` reconstructs the typed value. Return `Field<?>[]` not `Object[]`, using `DSL.val(converted, field.getDataType())`.
- **No cursor:** Return `DSL.noField(field)` per ORDER BY column — makes `.seek()` a no-op ([jOOQ #14395](https://github.com/jOOQ/jOOQ/issues/14395), fixed in 3.18; project is on 3.20.11).

This enables a **single-expression jOOQ query** (no if-branch):

```java
dsl.select(...)
   .from(table)
   .where(condition)
   .orderBy(orderBy)
   .seek(toSeekFields(cursor, orderByColumns))  // noField() when no cursor
   .limit(limit)
   .fetch()
```

**What this eliminates:**
1. The entire type-tag switch in `encodeCursor` and `decodeCursor`
2. The `Object[]` → `seek(Object...)` untyped path
3. The if-branch for conditional seek in `selectMany`

**What this improves:**
- Type safety: each seek value is bound with its column's `DataType`
- Cursor integrity: if the ordering changes between requests (user switches sort), `convert()` fails fast rather than silently misinterpreting a value against a different column
- jOOQ idiom: single-expression query composition using `noField()` / `noCondition()` as Lukas Eder recommends ([jOOQ #2333](https://github.com/jOOQ/jOOQ/issues/2333), [#16464](https://github.com/jOOQ/jOOQ/issues/16464))

`encodeCursor` and `decodeCursor` signatures change to accept `List<Field<?>> orderByColumns`. `ConnectionHelper` already receives the column list — the threading is in place.

### 2. Document transform coexistence

When the schema goes through both the transform AND the builder, `@asConnection` is stripped by the transform before the builder sees it. The builder falls back to structural detection, which works but loses `defaultPageSize` (defaults to 100). Document this.

---

## Review: `claude/review-pagination-plan-PUT5L`

Reviewed 2026-04-15 against commits `418adea6..8edd0651` (4 commits: cursor encoding fix, outer Base64 removal, backward pagination, execution tests).

### What's done well

- **Cursor encoding** (418adea6, da411f96): Per-value Base64 cleanly replaces the broken quote-wrapped format. Removing the outer Base64 layer simplifies without losing safety. The known limitation is resolved.
- **Backward pagination** (549b1e7c): Clean architecture — `backward` flag drives `reverseOrderBy()`, `ConnectionResult.trimmedResult()` re-reverses, `hasNextPage`/`hasPreviousPage` swap roles. All correct per Relay spec.
- **`@asConnection` now synthesizes all 4 args** by default — no opt-in needed for bidirectional pagination.
- **Execution tests** (8edd0651): 12 end-to-end tests against a real database. Forward pagination (first page, cursor nav, last page, past-end, no-args default), backward (basic, with cursor), dynamic ordering (default, by title, cursor nav). This was the top-priority gap.
- **Three jOOQ fixes** in the execution commit: `@asConnection` assertion removal (schema transform strips it), structural detection `connectionName` pass-through, `orderBy.toArray()` type incompatibility, conditional `seek()`.

### Issues to address

**1. Step-by-step query building in `selectMany`** — The paginated `selectMany` in `TypeClassGenerator` assigns to `var step`, then branches on `seekValues` to conditionally call `.seek()`. This breaks jOOQ's single-expression composition idiom. Fix as part of the type-safe cursor work above (item 1 in "What's next") — `noField()` eliminates the branch.

**2. `last` + `first` both supplied** — `backward = last != null` means `last` silently wins. The Relay spec says clients should not supply both. Consider rejecting this at runtime (throw) or documenting the precedence.

**3. `reverseOrderBy` uses jOOQ `$`-prefixed model API** — `sf.$sortOrder()` and `sf.$field()` are stable since 3.17 on the project's jOOQ version. Acceptable, but a comment in the generated code noting these are jOOQ model-API accessors (not experimental) would help future readers.

**4. Plan not updated to reflect done work** — The review branch's `paginated-fields.md` still lists "Execution test" as item 1 in "What's next" even though the branch includes execution tests. Backward pagination is described in "What's in place" but the "Known limitation" section is gone without noting the fix. The plan should be updated to reflect the current state accurately.

**5. Cursor format is no longer opaque** — Dropping the outer Base64 means cursors are human-readable (`[i:MQ==,s:aGVsbG8=]`). The Relay spec recommends but doesn't require opacity. Not a blocker — if opacity is desired later, a single outer Base64 wrap can be re-added. The type-safe cursor redesign (item 1) should decide whether to include an outer wrapper.
