---
title: "Stub #3: Interface / union fetchers"
status: In Progress
bucket: stubs
priority: 1
---

# Stub #3: Interface / union fetchers

Track A (`TableInterfaceType` variants) is **done**, pending the cleanup items listed below. Track B (multi-table polymorphic variants) remains and requires a design decision before any code can be written.

The companion cleanup item (`typeresolver-wiring-interface-union.md`) is absorbed here. Track A closes it for `TableInterfaceType`; Track B closes it for `InterfaceType` / `UnionType`.

Priority number `#3` must stay stable: it is embedded in emitted reason strings consumed by existing schema authors.

---

## Track A cleanup

Review of the Track A implementation identified the following gaps to address before Track A is fully closed.

### 1. Missing `orderBy` in `buildTableInterfaceFieldFetcher` list branch (bug)

`ChildField.TableInterfaceField` carries an `OrderBySpec orderBy` field but `buildTableInterfaceFieldFetcher` never calls `buildOrderByCode` and emits no `.orderBy(...)` clause in the list branch. The sibling `buildQueryTableInterfaceFieldFetcher` handles this correctly (line 596). Any list-cardinality `TableInterfaceField` with an ordering directive will silently return unordered results until this is fixed.

Fix: call `buildOrderByCode(tif.orderBy(), tif.name(), tableLocal)` before the DSL chain in the `isList` branch, and add `.orderBy(orderBy)\n` to the emitted query.

### 2. Multi-hop / ConditionJoin path should be a classification error, not a runtime throw

`buildJoinPathCondition` emits an `UnsupportedOperationException` in the generated code for multi-hop and `ConditionJoin` paths. The classifier has full joinPath information at build time, so this should be caught as an `UnclassifiedField` with `RejectionKind.AUTHOR_ERROR` — consistent with how every other unsupported pattern is handled. A user should not have to deploy and execute a query to discover their schema is unsupported.

Also: the current error message text says "See stub-interface-union-fetchers.md Track A" — Track A is done. Update the reference once the validation is moved to the classifier.

### 3. Execution tests in `GraphQLQueryTest` (highest-value gap)

The `content` table, seed data, `allContent` root query, and `Film.filmContent` child field are all in place. There are no execution-tier tests asserting that the TypeResolver routing actually works. Add at minimum:

- `allContent_returnsFilmContentAndShortContentWithCorrectTypename` — queries `{ allContent { __typename title } }` and asserts that rows with `CONTENT_TYPE='FILM'` resolve to `FilmContent` and `CONTENT_TYPE='SHORT'` resolve to `ShortContent`.
- `filmContent_childField_returnsDiscriminatedType` — queries `{ films { title filmContent { __typename title } } }` and asserts that films with a linked content row return the correct concrete type, and films without one return null.

### 4. Unit tests for the two new generator methods

`TypeFetcherGeneratorTest` tests structural properties (return type, parameter signature) for every other fetcher variant. Add:

- `queryTableInterfaceField_list_returnsResultRecord`
- `queryTableInterfaceField_single_returnsRecord`
- `queryTableInterfaceField_hasEnvParameter`
- `tableInterfaceField_list_returnsResultRecord`
- `tableInterfaceField_single_returnsRecord`

### 5. Unit tests for TypeResolver emission in `GraphitronSchemaClassGeneratorTest`

The `GraphitronSchemaClassGenerator` conditionally emits `codeRegistry.typeResolver(...)` for each `TableInterfaceType`. No tests cover this new branch. Add at minimum:

- A test asserting that a schema with a `@table @discriminate` interface produces a `typeResolver(...)` block that references the interface name and discriminator column.
- A test asserting that a schema without any `@discriminate` interface emits no `typeResolver(...)` beyond the Node one.

### 6. Duplicate Javadoc on `buildJoinPathCondition`

`TypeFetcherGenerator.buildJoinPathCondition` (around line 698) has two consecutive `/** ... */` blocks. The first is a stale draft with an incomplete description; the second is correct. Remove the first block.

---

## Track B: Multi-table polymorphic

**Variants:** `QueryField.QueryInterfaceField`, `QueryField.QueryUnionField`, `ChildField.InterfaceField`, `ChildField.UnionField`

All four carry only `PolymorphicReturnType` — no table, no `MethodRef`. The classifier produces them when the return type is a multi-table `InterfaceType` or `UnionType` and no `@service` / `@tableMethod` is present.

### Design decision (resolve before coding)

Without a table binding or a method reference, there is no SQL for Graphitron to generate. Two options:

**Option 1 — Reject at classification.** These variants become `UnclassifiedField` when the field has no `@service`. The error message should say: "Multi-table interface/union fields require `@service` for developer-supplied dispatch, or `@table @discriminate` on the interface type for single-table polymorphism." The four variants disappear from the model; their `NOT_IMPLEMENTED_REASONS` entries and `stub(f)` arms are removed.

**Option 2 — Keep variants, improve the stub message.** The fetcher body emits a better `UnsupportedOperationException` message pointing to the two options above. Structurally the same as today but more actionable.

Recommendation: **Option 1.** Consistent with how the classifier already rejects other unsupported patterns. The model variants (`QueryInterfaceField`, etc.) may survive as separate classified paths if `@service` + interface-return becomes an explicit classification in the future.

### TypeResolver wiring for `InterfaceType` / `UnionType`

Even under Option 1, `InterfaceType` and `UnionType` exist in the schema (e.g. the `Node` interface, or user types whose fields are all `@service`-backed). Their TypeResolvers still need wiring.

Pattern: emit `codeRegistry.typeResolver("<Name>", env -> { ... })` for each non-`Node` `InterfaceType` and `UnionType` in `schema.types()`. Use the `__typename` convention: the resolver reads `record.get("__typename")` (same contract as `QueryNodeFetcher.registerTypeResolver`) and calls `env.getSchema().getObjectType(value)`. Document this as a required contract for `@service` methods returning multi-table interface / union types (see `graphitroncontext-extension-point-docs.md`).

---

## Order and gating

Track A is done. Track B's design decision (Option 1 vs. Option 2 above) is a prerequisite for any Track B code.

---

## Non-goals

- Per-participant sub-queries for multi-table interface fetchers without `@service` (requires a new directive or classification path).
- `NodeIdReferenceField` JOIN-projection form (tracked separately under Cleanup).
- TypeResolver for the built-in `Node` interface (already wired via `QueryNodeFetcher.registerTypeResolver`).

---

## Changelog

- **2026-04-27** — Track A complete. `QueryTableInterfaceField` and `ChildField.TableInterfaceField` lifted from `NOT_IMPLEMENTED_REASONS` to `IMPLEMENTED_LEAVES`. `discriminatorColumn` added to both records; classifier, `QueryConditionsGenerator`, `TypeFetcherGenerator` (new `buildQueryTableInterfaceFieldFetcher`, `buildTableInterfaceFieldFetcher`, `buildJoinPathCondition`), and `GraphitronSchemaClassGenerator` (TypeResolver wiring) updated. Fixtures: `content` table, `allContent` root query, `Film.filmContent` child field, `Content` / `FilmContent` / `ShortContent` SDL. Review identified six cleanup items (see Track A cleanup section above).
