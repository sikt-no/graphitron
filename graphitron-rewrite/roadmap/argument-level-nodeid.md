---
id: R40
title: "Argument-level `@nodeId` support"
status: Backlog
bucket: architecture
priority: 2
theme: nodeid
depends-on: []
---

# Argument-level `@nodeId` support

`@nodeId` is declared on `ARGUMENT_DEFINITION` in [`directives.graphqls`](../graphitron/src/main/resources/no/sikt/graphitron/rewrite/schema/directives.graphqls) but `FieldBuilder.classifyArgument` (line 754) never inspects it. The scalar-ID branch at line 815 is gated on `!list` and fires only via `nodeIdMetadata` (synthesized route); an explicit `@nodeId(typeName: T)` on a `[ID!]` arg falls through to the column-binding fallthrough at line 826 and surfaces as `column 'X' could not be resolved in table 'Y'`. Reproducer from opptak: `kompetanseregelverkGittIdV2(ider: [ID!]! @nodeId(typeName: "Kompetanseregelverk")): [Kompetanseregelverk!] @asConnection`.

## Architectural framing

`@nodeId` is a wire-format encoding. The model below the DataFetcher boundary only needs key tuples and FK references; the encoded form should not propagate into the classifier model, the condition body, the projection body, or the lookup mapping. The existing `NodeIdField` / `NodeIdReferenceField` / `NodeIdInFilterField` / `IdReferenceField` / `BodyParam.NodeIdIn` / `LookupMapping.NodeIdMapping` / `ArgumentRef.ScalarArg.NodeIdArg` variants are wire-shape leaks into the model. The smoking gun: `BodyParam.NodeIdIn` emits `NodeIdEncoder.hasIds("typeId", arg, table.col1, ..., table.colN)` from inside a condition method, reaching the encoder across the DataFetcher / query-builder boundary; jOOQ should see decoded key tuples and a standard row-IN predicate.

R40 takes the opportunity to land the right shape for arg-level support without growing that debt:

- New `CallSiteExtraction.NodeIdDecodeKeys(typeId, keyColumns)` (or similarly named) variant: at the DataFetcher call site, decodes a `List<String>` of wire IDs into `List<Object[]>` of key tuples, validates the `typeId` prefix, and short-circuits empty / invalid input.
- Classifier path for `[ID!] @nodeId(typeName: SameTable)` produces a column-shaped argument (single-PK case: ordinary `ColumnArg` over the PK column with the new extraction; composite-PK case: a `CompositeKeyArg` analog if needed, but column-shaped, not NodeId-shaped).
- Body emits standard jOOQ: `table.pkCol.in(decodedKeys)` for single-PK, `DSL.row(table.col1, ..., table.colN).in(decodedKeys)` for composite. No reach into `NodeIdEncoder` from the body.
- `NodeIdEncoder` keeps `decodeKeys` / `encode` (boundary helpers); it loses `hasIds` (a query-builder helper that should never have lived there).

R40 does **not** introduce a `NodeIdInArg` sibling and does **not** extend `NodeIdArg` to cover the list IN cell. Both options propagate the wire shape into the model. The right move is to push encode/decode out to the boundary and let the model see columns.

## Scope

Same-table only: `typeName:` resolves to the field's own backing table. FK-target args (`typeName:` resolves to a different table) are a separate Backlog item; with the boundary-extraction shape in place, the FK-target case becomes "classify as `ColumnReferenceField`-style with an FK path plus the same `NodeIdDecodeKeys` extraction", which is an even smaller follow-up than the input-field analog. R20's `IdReferenceField` predates this framing and would be re-evaluated as part of the model-cleanup item below; R40 does not depend on R20.

## Companion item: lift NodeId out of the model

A separate Backlog entry (to be filed alongside R40 moving to Spec) covers retiring the existing wire-shape leaks: `InputField.NodeIdField` / `NodeIdReferenceField` / `NodeIdInFilterField` / `IdReferenceField`, `ChildField.NodeIdField` / `NodeIdReferenceField`, `BodyParam.NodeIdIn`, `LookupMapping.NodeIdMapping`, `ArgumentRef.ScalarArg.NodeIdArg`, replacing each with the column-shaped variant plus a `NodeIdDecodeKeys` / `NodeIdEncodeKeys` extraction at the appropriate boundary. `GraphitronType.NodeType` stays (it carries identity, not wire format). That item is large; R40 does not block on it, but R40 is shaped so it does not add to the debt.

## Spec-day-one questions

- **Composite-key shape.** Single-PK fits ordinary `ColumnArg` cleanly. Composite-PK needs a column-shaped multi-column carrier. Pin whether to introduce a small `CompositeKeyArg(name, keyColumns: List<ColumnRef>, extraction)` or generalise `ColumnArg` to take a list of columns. Either way, it stays column-shaped, not NodeId-shaped.
- **`@asConnection` composition.** `GraphitronSchemaBuilder.rewriteCarrierField` appends `first` / `after` before classifyArguments runs, so `isPaginationArg` claims those names first. Confirm filter + `PaginationSpec` compose (filter narrows, seek paginates within) with an execution test on the opptak reproducer.
- **`@lookupKey` policy.** Reject `[ID!] @nodeId @lookupKey` at classify time, by symmetry with `buildLookupBindings`'s rejection of `@lookupKey` on list input fields and the `@asConnection` + `@lookupKey` rejection at lines 331/346. (The lookup-key-on-list case is already covered by the existing `NodeIdMapping` path in legacy debt; do not extend that path.)
- **`@condition` policy.** With the model carrying only `ColumnArg` shapes, `@condition` co-existence is the same as on any other column-bound arg; no special case needed.
- **Validator + dispatch coverage.** The new extraction needs an arm in `ArgCallEmitter.buildArgExtraction`; the classifier path is exercised by an existing `ColumnArg` projection arm. No new `NOT_DISPATCHED_LEAVES` entry; nothing for `GraphitronSchemaValidator` beyond what column-shaped args already trigger.
- **Stale-docstring cleanup landing alongside.** `ArgumentRef.ScalarArg.NodeIdArg`'s javadoc references "Step 5 (the reference variant)" and `FieldBuilder.projectFilters` line 1211 references a "Step 4 follow-up"; neither step exists. Strike both as part of R40, or fold into the companion item.
- **Edge cases the classifier must enumerate.** `typeName:` non-existent (UnclassifiedArg with candidate hint), non-`@table` target (UnclassifiedArg), same-table target with no metadata / no `@node` / no PK (UnclassifiedArg); messages mirror the input-field arm's error contract.

## Test surface

`nodeidfixture` catalog (Sakila lacks `__NODE_KEY_COLUMNS`). Pipeline-tier classification cases for composite-PK, single-PK, target-not-`@node` Unresolved, target-non-`@table` Unresolved, missing-`typeName` Unresolved. Execution-tier cases against PostgreSQL using the opptak reproducer shape: filter-by-ids returns exactly those rows; empty list passes through to `noCondition()` (or its column-eq equivalent); combined with `first` / `after` returns the paginated subset of the filtered set; `QUERY_COUNT == 1`. Generated SQL inspection (via `ExecuteListener`) should confirm the predicate is `pkCol IN (...)` (single-PK) or `(col1, col2) IN ((...), ...)` (composite), not a `hasIds` call.
