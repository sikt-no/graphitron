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

## Boundary extractions

*Input side.* `CallSiteExtraction.NodeIdDecodeKeys(typeId)` is a first-class arm on the existing sealed `CallSiteExtraction` hierarchy with a full arm in `ArgCallEmitter.buildArgExtraction`. It decodes `String` / `List<String>` to a key tuple / list of key tuples and validates the `typeId` prefix. Coverage extends to the top-level-argument case: today `NestedInputField` is the only nested-Map traversal that runs extractions, but a top-level `[ID!] @nodeId` arg needs the same extraction at the call-site root, so `ArgCallEmitter`'s top-level path gains the same dispatch the nested path already has.

*Output side.* Encoding is **not** a `CallSiteExtraction` arm. `CallSiteExtraction` is consumed by `ArgCallEmitter` to shape input *call-site* values; projection emission is a different category. The encode hook lives alongside the projection emitters (concrete type name picked during implementation, e.g. `ProjectionTransform.NodeIdEncodeKeys(typeId)`), wraps the projected key columns in a `NodeIdEncoder.encode(typeId, c1, ..., cN)` call, and dispatches from the same place the existing scalar-projection emitter dispatches from. Failure-mode contract for both sides: see "Failure-mode contract" below.

## Composite-key column carriers

The column-shaped variants today are single-column: `InputField.ColumnField`, `ArgumentRef.ScalarArg.ColumnArg`, and `BodyParam.ColumnEq` each carry one `ColumnRef`; `LookupMapping.ColumnMapping` already carries a `List<LookupColumn>` and `LookupMapping.NodeIdMapping` already carries a `List<ColumnRef> nodeKeyColumns`, so composite is established on the mapping side. The replacements for the wire-shape variants need to carry a composite key with row-IN body emission (`DSL.row(c1, ..., cN).in(rows)`), degenerating to single-column `c.in(...)` / `c.eq(...)` for arity-1.

**Decision: generalise the existing single-column carriers to `List<ColumnRef> columns`** rather than introducing `Composite*` sibling variants. Rationale:

- The whole point of the lift is that arity is an emission detail, not a model axis. Sibling variants would re-encode arity into the sealed hierarchy and force every consumer to handle two arms for the same column-shaped concept.
- The codebase already mixes single-`ColumnRef` and `List<ColumnRef>` shapes; collapsing the column carriers onto the list shape regularises the model, it doesn't fragment it.
- The arity branch lives in exactly one place per carrier (the body / projection emitter), where `columns.size() == 1` chooses `c.eq(...)` or `c.in(...)` and `> 1` chooses `DSL.row(...).eq(...)` or `.in(...)`.

Concrete shape changes: `ColumnField.column` → `List<ColumnRef> columns`; `ColumnArg.column` → `List<ColumnRef> columns`; `ColumnEq.column` → `List<ColumnRef> columns`. Callers and switch arms across `BuildContext`, `FieldBuilder`, `TypeBuilder`, `TypeFetcherGenerator`, `GraphitronSchemaValidator`, `TypeConditionsGenerator`, and the body / projection emitters migrate from `.column()` to `.columns()` with arity-1 as the common case. No new sealed arms; the wire-shape variants delete outright once their classifier arms route to the generalised carriers.

Rename `BodyParam.ColumnEq` → `BodyParam.ColumnPredicate` as part of the same change. The "Eq" name is already a stretch (the existing `boolean list` slot makes it emit `c.in(...)` for list values); generalising to `List<ColumnRef>` adds row-eq and row-in to its emission set, and the name has to stop pretending. `InputField.ColumnField` and `ScalarArg.ColumnArg` keep their names; they're column-shaped *carriers*, and the body/predicate semantics live on the body-param side.

## Variant-by-variant collapse

- `InputField.NodeIdField` (output side, scalar) → column projection with `NodeIdEncodeKeys`.
- `InputField.NodeIdReferenceField` → `ColumnReferenceField` with FK path; encoding lifts to projection with `NodeIdEncodeKeys`.
- `InputField.NodeIdInFilterField` → multi-column `ColumnField` with `NodeIdDecodeKeys` extraction; body is row-IN.
- `InputField.IdReferenceField` → `ColumnReferenceField` over the FK columns with `NodeIdDecodeKeys` extraction; body is single FK-eq or row-IN over the FK columns. Subsumes R20.
- `ChildField.NodeIdField` / `NodeIdReferenceField` → column projection forms with `NodeIdEncodeKeys`.
- `BodyParam.NodeIdIn` → multi-column `BodyParam.ColumnPredicate` (renamed from `ColumnEq`; see "Composite-key column carriers"). `NodeIdEncoder.hasIds` deletes.
- `LookupMapping.NodeIdMapping` → regular `LookupMapping` over the PK columns; `LookupValuesJoinEmitter` decodes via `NodeIdDecodeKeys` at the call site.
- `ArgumentRef.ScalarArg.NodeIdArg` → multi-column `ColumnArg` with `NodeIdDecodeKeys` extraction. Stale "Step 5 (the reference variant)" docstring and the matching `FieldBuilder.projectFilters` line 1211 "Step 4 follow-up" comment delete with the variant.

## `NodeIdEncoder` API

`hasIds` deletes (it is a query-builder helper that does not belong in the encoder). `canonicalize` deletes too: it has no caller in the generator codebase or in emitted code today. `QueryNodeFetcherClassGenerator`'s rowsNodes flow already documents in a comment that canonicalize was removed in favour of the dispatcher's idx-driven scatter (`Base64.getUrlDecoder` accepts both padded and unpadded forms, so canonicalisation at the encoder is dead weight). `NodeIdEncoderClassGenerator` line 141 stops emitting the method; the regression test `GraphQLQueryTest.nodes_paddedBase64Id_canonicalizesAndResolves` keeps its assertion (the *behaviour* is preserved by the decoder's leniency, not by `canonicalize`). The stale `schema.graphqls` line 17 docstring referencing `NodeIdEncoder.canonicalize` is rewritten in the same change.

`encode` / `decodeKeys` / `peekTypeId` stay (boundary helpers consumed by DataFetcher emitters and by the new extractions).

## Validator + dispatch coverage

Every retired variant comes off `TypeFetcherGenerator.NOT_DISPATCHED_LEAVES` and `GraphitronSchemaValidator`'s no-op arms. The new extractions get arms in the validator's coverage tests.

## Failure-mode contract

`NodeIdDecodeKeys` is silent today and the spec needs to pin it explicitly:

- **`Query.node(id:)` / `Query.nodes(ids:)`.** typeId mismatch on a single `id` resolves to `null` for the whole field; typeId mismatch on one element of `ids` resolves to `null` for that element only. Parity with the existing Relay-spec behaviour the federated `_entities` resolver already implements.
- **Filter argument `[ID!] @nodeId(typeName: T)`.** typeId mismatch on any element does not throw; the decode short-circuits the bad element to "no row matches." Empty list and null arg follow existing column-equality behaviour (predicate omitted when the arg is absent, `falseCondition()` when the list is present-but-empty, matching today's `NodeIdInFilterField` body).
- **`@nodeId` on a top-level scalar/list argument used as a key.** typeId mismatch is an authored-input error and throws (a `GraphqlErrorException`-shaped error surface, not silent null). This applies to lookup keys and mutation arguments, where a wrong-type id is a contract violation rather than "no match."

The contract lives in `NodeIdEncoder.decodeKeys` (or a thin adapter next to it); the new `CallSiteExtraction.NodeIdDecodeKeys` arm picks the strict-vs-lenient mode per call site rather than scattering the choice through emitters.

## What stays

`GraphitronType.NodeType` stays. It carries `(typeId, keyColumns)`, which is type-level identity: the schema author declared this type as a Node and these are its key columns. Identity is a model concern; the wire format derived from that identity is not.

## What we're NOT doing

- **The wire format itself.** `@nodeId` ids stay as base64-encoded `typeId` + composite key. R50 is a model-side lift, not a protocol change; consumer schemas and clients see the same id strings.
- **`NodeIdEncoder` rewrite beyond `hasIds` and `canonicalize` deletion.** `encode` / `decodeKeys` / `peekTypeId` keep their current shape. If a downstream change wants to refactor the surviving boundary helpers, it's a separate item; R50 only removes the two helpers that have no surviving callers (`hasIds` is the wire-shape leak, `canonicalize` is dead emitted code; see "`NodeIdEncoder` API").
- **Multi-hop FK / non-mirroring correlated-subquery emission.** R24's framing of multi-hop / non-mirroring FK as needing a "real correlated-subquery emission projecting the target's `nodeKeyColumns` under aliases" is a separate piece of mechanism, orthogonal to the wire-shape lift. R50 lands the column-shaped form for the cases the FK-mirror collapse already handles, plus the composite-PK and non-mirroring single-hop FK cases (see Test surface). Multi-hop stays in R24's scope.
- **Consumer schema migration.** No changes required in consuming schemas; `[ID!] @nodeId(typeName: T)` and `@nodeId` on scalar / list outputs are the same surface they are today.

## Fixture growth

R50 adds two fixture shapes to the existing `nodeidfixture` to exercise the composite-key paths the lift unlocks:

- **Composite-PK Node.** A table with a two-column PK plus `__NODE_TYPE_ID`, exposed as a NodeType. Drives the row-IN body emission for `[ID!] @nodeId` filters and the row-projection encode path on output.
- **Non-mirroring single-hop FK.** A reference whose FK columns do not positionally match the target NodeType's `keyColumns`, but where the FK target is reachable in one hop. Drives `NodeIdReferenceField` collapse for the non-mirror case without pulling in correlated-subquery emission.

Multi-hop FK fixture growth is explicitly deferred to R24 (see "What we're NOT doing").

## Coupling

- **R40 (argument-level `@nodeId` support)** depends on R50. R40 is shaped as a small classifier-only follow-on once R50's foundation is in place: extend `FieldBuilder.classifyArgument` to read `@nodeId(typeName: T)` on an argument, validate same-table, build a column-shaped argument with `NodeIdDecodeKeys`. R40 does not land any new model variants and adds nothing to the wire-shape debt.
- **R20 (`IdReferenceField` code generation)** had a Spec on the legacy variant; that Spec is invalidated by R50's framing because R20's emission shape dissolves into R50 (standard FK-equality or row-IN through a column-shaped variant, not a `has<Qualifier>` method call). R20 has been flipped back to Backlog as part of moving R50 to Spec; its execution-tier coverage is folded into R50's test surface above.
- **R24 (`NodeIdReferenceField` JOIN-projection form)** also dissolves: the multi-hop / non-mirroring FK case becomes a column projection with `NodeIdEncodeKeys` once `NodeIdReferenceField` retires.

## Test surface

- **Pipeline-tier.** Every retired variant's existing classification cases re-pointed at the column-shaped successor: `@nodeId` scalar output, `@nodeId` reference (FK-mirror and non-mirror), `@nodeId` filter on `[ID!]` arg, `@nodeId` argument on a top-level `[ID!]` arg, federated `_entities` resolver, `Query.node(id:)` and `Query.nodes(ids:)`. A coverage meta-test asserts that no `NodeIdField` / `NodeIdReferenceField` / `NodeIdInFilterField` / `IdReferenceField` / `ChildField.NodeIdField` / `ChildField.NodeIdReferenceField` / `BodyParam.NodeIdIn` / `LookupMapping.NodeIdMapping` / `ScalarArg.NodeIdArg` instance survives in any classified model across the rewrite's fixture catalogs (mirrors the existing variant-coverage meta-tests).
- **Validator-tier.** `GraphitronSchemaValidator`'s coverage tests gain an arm for the new `CallSiteExtraction.NodeIdDecodeKeys` variant and an arm for the projection-side encode hook (per the input/output split in "Boundary extractions"), and lose arms for the retired model variants. `TypeFetcherGenerator.NOT_DISPATCHED_LEAVES` shrinks; the dispatch-coverage test asserts the retired leaves are gone.
- **Compilation-tier.** `mvn compile -pl :graphitron-test -Plocal-db` against the `nodeidfixture` and `idreffixture` catalogs after the collapse, including the composite-PK Node and non-mirroring single-hop FK fixtures spelled out in "Fixture growth".
- **Execution-tier.** Every existing `@nodeId` execution test continues to round-trip end-to-end: `Query.node(id:)`, `Query.nodes(ids:)`, federated `_entities`, same-table `[ID!] @nodeId` filter, FK-mirror reference, and the new composite-PK / non-mirroring FK cases. SQL inspection via `ExecuteListener` confirms `NodeIdEncoder.hasIds` is gone from emitted bodies and is replaced with `c.in(...)` (arity-1) or `DSL.row(c1, ..., cN).in(...)` (arity-N), and that decoded key tuples flow as bind values rather than encoded `String` ids. Failure-mode parity with "Failure-mode contract": typeId-mismatch tests for the `Query.node(id:)` null-on-mismatch, `[ID!] @nodeId` filter element-skip, and lookup-key throw paths.
- **Negative coverage.** A pipeline test asserts `NodeIdEncoder.hasIds` is unreferenced from generated query-builder code (string scan over the emitted source set), guarding against regressions that re-introduce the encoder helper from the query layer.
