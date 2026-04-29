---
id: R50
title: "Lift NodeId out of the model"
status: Spec
bucket: architecture
priority: 1
theme: nodeid
depends-on: []
---

# Lift NodeId out of the model

`@nodeId` is a wire-format encoding (base64 over `typeId` + composite key), but the classifier model and emitted query builders carry the wire shape into layers that only need column refs and key tuples. Encode and decode belong at the DataFetcher boundary; everything below it should see decoded values. The smoking gun is `BodyParam.NodeIdIn`'s body, which emits `NodeIdEncoder.hasIds("typeId", arg, table.col1, ..., table.colN)` from inside a generated condition method, reaching the encoder across the DataFetcher / query-builder boundary; jOOQ should see decoded key tuples and a standard row-IN predicate.

## Wire-shape leaks to retire

- `InputField.NodeIdField`, `NodeIdReferenceField`, `NodeIdInFilterField`, `IdReferenceField`
- `ChildField.NodeIdField`, `ChildField.NodeIdReferenceField`
- `BodyParam.NodeIdIn`
- `LookupMapping.NodeIdMapping`
- `ArgumentRef.ScalarArg.NodeIdArg`

Each is the same wire-shape leak in a different position.

## What R50 must land

1. **Boundary extractions.** `CallSiteExtraction.NodeIdDecodeKeys(typeId)` for inputs (decodes `String` / `List<String>` to a key tuple / list of key tuples; validates `typeId` prefix; short-circuits empty / null / typeid-mismatched input). `NodeIdEncodeKeys(typeId)` (or equivalent projection-side hook) for outputs. Both are first-class members of `CallSiteExtraction`'s sealed hierarchy with full arms in `ArgCallEmitter.buildArgExtraction` and the projection-side counterpart, including the top-level-argument case (today `NestedInputField` is the only nested-Map traversal; the new variant is needed at top-level args too).

2. **Composite-key column carriers.** The column-shaped variants today are single-column: `InputField.ColumnField`, `ArgumentRef.ScalarArg.ColumnArg`, and `BodyParam.ColumnEq` each carry one `ColumnRef`; `LookupMapping.ColumnMapping` already carries a `List<LookupColumn>` and `LookupMapping.NodeIdMapping` already carries a `List<ColumnRef> nodeKeyColumns`, so composite is established on the mapping side. The replacements for the wire-shape variants need to carry a composite key with row-IN body emission (`DSL.row(c1, ..., cN).in(rows)`), degenerating to single-column `c.in(...)` / `c.eq(...)` for arity-1.

   **Decision: generalise the existing single-column carriers to `List<ColumnRef> columns`** rather than introducing `Composite*` sibling variants. Rationale:
   - The whole point of the lift is that arity is an emission detail, not a model axis. Sibling variants would re-encode arity into the sealed hierarchy and force every consumer to handle two arms for the same column-shaped concept.
   - The codebase already mixes single-`ColumnRef` and `List<ColumnRef>` shapes; collapsing the column carriers onto the list shape regularises the model, it doesn't fragment it.
   - The arity branch lives in exactly one place per carrier (the body / projection emitter), where `columns.size() == 1` chooses `c.eq(...)` or `c.in(...)` and `> 1` chooses `DSL.row(...).eq(...)` or `.in(...)`.

   Concrete shape changes: `ColumnField.column` → `List<ColumnRef> columns`; `ColumnArg.column` → `List<ColumnRef> columns`; `ColumnEq.column` → `List<ColumnRef> columns`. Callers and switch arms (~17 sites across `BuildContext`, `FieldBuilder`, `TypeBuilder`, `TypeFetcherGenerator`, `GraphitronSchemaValidator`, `TypeConditionsGenerator`, and the body / projection emitters) migrate from `.column()` to `.columns()` with arity-1 as the common case. No new sealed arms; the wire-shape variants delete outright once their classifier arms route to the generalised carriers.

3. **Variant-by-variant collapse.**
   - `InputField.NodeIdField` (output side, scalar) → column projection with `NodeIdEncodeKeys`.
   - `InputField.NodeIdReferenceField` → `ColumnReferenceField` with FK path; encoding lifts to projection with `NodeIdEncodeKeys`.
   - `InputField.NodeIdInFilterField` → composite `ColumnField` (or `ColumnInField` analog) with `NodeIdDecodeKeys` extraction; body is row-IN.
   - `InputField.IdReferenceField` → `ColumnReferenceField` over the FK columns with `NodeIdDecodeKeys` extraction; body is single FK-eq or row-IN over the FK columns. Subsumes R20.
   - `ChildField.NodeIdField` / `NodeIdReferenceField` → column projection forms with `NodeIdEncodeKeys`.
   - `BodyParam.NodeIdIn` → composite `BodyParam.ColumnEq` (or row-IN body param). `NodeIdEncoder.hasIds` deletes.
   - `LookupMapping.NodeIdMapping` → regular `LookupMapping` over the PK columns; `LookupValuesJoinEmitter` decodes via `NodeIdDecodeKeys` at the call site.
   - `ArgumentRef.ScalarArg.NodeIdArg` → `ColumnArg` (or composite analog) with `NodeIdDecodeKeys` extraction. Stale "Step 5 (the reference variant)" docstring and the matching `FieldBuilder.projectFilters` line 1211 "Step 4 follow-up" comment delete with the variant.

4. **`NodeIdEncoder` API.** `hasIds` deletes (it is a query-builder helper that does not belong in the encoder). `encode` / `decodeKeys` / `peekTypeId` / `canonicalize` stay (boundary helpers consumed by DataFetcher emitters and by the new extractions).

5. **Validator + dispatch coverage.** Every retired variant comes off `TypeFetcherGenerator.NOT_DISPATCHED_LEAVES` and `GraphitronSchemaValidator`'s no-op arms. The new extractions get arms in the validator's coverage tests.

## What stays

`GraphitronType.NodeType` stays. It carries `(typeId, keyColumns)`, which is type-level identity: the schema author declared this type as a Node and these are its key columns. Identity is a model concern; the wire format derived from that identity is not.

## Coupling

- **R40 (argument-level `@nodeId` support)** depends on R50. R40 is shaped as a small classifier-only follow-on once R50's foundation is in place: extend `FieldBuilder.classifyArgument` to read `@nodeId(typeName: T)` on an argument, validate same-table, build a column-shaped argument with `NodeIdDecodeKeys`. R40 does not land any new model variants and adds nothing to the wire-shape debt.
- **R20 (`IdReferenceField` code generation)** had a Spec on the legacy variant; that Spec is invalidated by R50's framing because R20's emission shape dissolves into R50 (standard FK-equality or row-IN through a column-shaped variant, not a `has<Qualifier>` method call). R20 has been flipped back to Backlog as part of moving R50 to Spec; its execution-tier coverage is folded into R50's test surface above.
- **R24 (`NodeIdReferenceField` JOIN-projection form)** also dissolves: the multi-hop / non-mirroring FK case becomes a column projection with `NodeIdEncodeKeys` once `NodeIdReferenceField` retires.

## Test surface

- **Pipeline-tier.** Every retired variant's existing classification cases re-pointed at the column-shaped successor: `@nodeId` scalar output, `@nodeId` reference (FK-mirror and non-mirror), `@nodeId` filter on `[ID!]` arg, `@nodeId` argument on a top-level `[ID!]` arg, federated `_entities` resolver, `Query.node(id:)` and `Query.nodes(ids:)`. A coverage meta-test asserts that no `NodeIdField` / `NodeIdReferenceField` / `NodeIdInFilterField` / `IdReferenceField` / `ChildField.NodeIdField` / `ChildField.NodeIdReferenceField` / `BodyParam.NodeIdIn` / `LookupMapping.NodeIdMapping` / `ScalarArg.NodeIdArg` instance survives in any classified model across the rewrite's fixture catalogs (mirrors the existing variant-coverage meta-tests).
- **Validator-tier.** `GraphitronSchemaValidator`'s coverage tests gain arms for the new `CallSiteExtraction.NodeIdDecodeKeys` / `NodeIdEncodeKeys` variants and lose arms for the retired model variants. `TypeFetcherGenerator.NOT_DISPATCHED_LEAVES` shrinks; the dispatch-coverage test asserts the retired leaves are gone.
- **Compilation-tier.** `mvn compile -pl :graphitron-test -Plocal-db` against the `nodeidfixture` and `idreffixture` catalogs after the collapse, plus any new fixture columns the composite-key cases require (composite PK, multi-hop FK, non-mirroring FK).
- **Execution-tier.** Every existing `@nodeId` execution test continues to round-trip end-to-end: `Query.node(id:)`, `Query.nodes(ids:)`, federated `_entities`, same-table `[ID!] @nodeId` filter, FK-mirror reference, and at least one composite-key fixture. SQL inspection via `ExecuteListener` confirms `NodeIdEncoder.hasIds` is gone from emitted bodies and is replaced with `c.in(...)` (arity-1) or `DSL.row(c1, ..., cN).in(...)` (arity-N), and that decoded key tuples flow as bind values rather than encoded `String` ids.
- **Negative coverage.** A pipeline test asserts `NodeIdEncoder.hasIds` is unreferenced from generated query-builder code (string scan over the emitted source set), guarding against regressions that re-introduce the encoder helper from the query layer.
