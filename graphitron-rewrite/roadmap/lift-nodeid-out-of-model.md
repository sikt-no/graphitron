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

*Input side.* `CallSiteExtraction.NodeIdDecodeKeys` is a first-class sealed sub-taxonomy on the existing `CallSiteExtraction` hierarchy (three arms by failure mode; see "Failure-mode contract"), with full arms in `ArgCallEmitter.buildArgExtraction`. Each arm decodes `String` / `List<String>` to a key tuple / list of key tuples and validates the `typeId` prefix; the arms differ only in how a `typeId` mismatch surfaces. Coverage extends to the top-level-argument case: today `NestedInputField` is the only nested-Map traversal that runs extractions, but a top-level `[ID!] @nodeId` arg needs the same extraction at the call-site root, so `ArgCallEmitter`'s top-level path gains the same dispatch the nested path already has.

*Output side.* Encoding is **not** a `CallSiteExtraction` arm. `CallSiteExtraction` is consumed by `ArgCallEmitter` to shape input *call-site* values; projection emission is a different category, so it gets its own sealed root rather than overloading the input-side one.

R50 introduces `ProjectionTransform` as a sealed sub-taxonomy under `no.sikt.graphitron.rewrite.model` with two initial arms:

- `ProjectionTransform.Direct` — projects the carrier's column as a plain SELECT term. Classifier picks it for every non-NodeId column projection (today's `ChildField.ColumnField` / `ChildField.ColumnReferenceField` shape); the carrier always has `columns.size() == 1` in this case. The validator enforces the carrier↔transform invariant `Direct → columns.size() == 1` so the constraint surfaces at build time rather than as a runtime emission bug.
- `ProjectionTransform.NodeIdEncodeKeys(String typeId)` — wraps the carrier's `columns` in a `NodeIdEncoder.encode(typeId, c1, ..., cN)` call. The columns come from the carrier (`ChildField.ColumnField.columns` or `ChildField.ColumnReferenceField.columns`); the transform contributes only the typeId, classifier-decided once.

`ProjectionTransform` lives on `ChildField.ColumnField` and `ChildField.ColumnReferenceField` (see "Composite-key column carriers"). Dispatch is symmetric with the input side: `CallSiteExtraction` arms route through `ArgCallEmitter` for call-site input shaping, `ProjectionTransform` arms route through the existing scalar-projection emitter for output column shaping. The validator gets a coverage-test arm per `ProjectionTransform` variant just as it does today per `CallSiteExtraction` variant; "Validator + dispatch coverage" below names both.

Failure-mode contract for both sides: see "Failure-mode contract" below.

## Composite-key column carriers

The column-shaped variants today are single-column: `InputField.ColumnField`, `ArgumentRef.ScalarArg.ColumnArg`, and `BodyParam.ColumnEq` each carry one `ColumnRef`; `LookupMapping.ColumnMapping` already carries a `List<LookupColumn>` and `LookupMapping.NodeIdMapping` already carries a `List<ColumnRef> nodeKeyColumns`, so composite is established on the mapping side. The replacements for the wire-shape variants need to carry a composite key with row-IN body emission (`DSL.row(c1, ..., cN).in(rows)`), degenerating to single-column `c.in(...)` / `c.eq(...)` for arity-1.

**Decision: split carriers from predicates.** Column-shaped *carriers* (every `*.ColumnField` / `*.ColumnReferenceField` / `*.ColumnArg`, on both input and output sides) generalise to `List<ColumnRef> columns` because the columns they carry are the same kind of thing whether there are one or many; column arity is an emission detail and does not belong as a model axis on a carrier. Column-shaped *predicates* (`BodyParam.ColumnEq` and its successors) are different: the predicate operator (eq vs in, single-column vs row) *is* a model axis, and folding four operators into one record + two flags pushes a 4-way switch into every emitter that builds a `Condition`.

*Carriers* (regularise on `List<ColumnRef>`). Both input-side and output-side single-column carriers widen so they can host the multi-column key tuples post-collapse:

- Input side: `InputField.ColumnField.column` → `List<ColumnRef> columns`; `InputField.ColumnReferenceField.column` → `List<ColumnRef> columns` (joinPath unchanged); `ArgumentRef.ScalarArg.ColumnArg.column` → `List<ColumnRef> columns`.
- Output side: `ChildField.ColumnField.column` → `List<ColumnRef> columns`; `ChildField.ColumnReferenceField.column` → `List<ColumnRef> columns` (joinPath unchanged).
- Output-side carriers also gain a `ProjectionTransform projection` component — `ProjectionTransform.Direct` for plain column projection, `ProjectionTransform.NodeIdEncodeKeys` for the encode-keys collapse path. The transform reads its columns from the carrier (no redundant column list on the transform itself); the projection emitter switches on `projection`'s sealed arm to decide how to wrap them.
- Callers and switch arms across `BuildContext`, `FieldBuilder`, `TypeBuilder`, `TypeFetcherGenerator`, `GraphitronSchemaValidator`, `TypeConditionsGenerator`, `FetcherEmitter`, and the projection emitters migrate from `.column()` to `.columns()` with arity-1 as the common case. No new sealed arms on the carrier side; the wire-shape variants delete outright once their classifier arms route to the generalised carriers.

*Predicates* (sealed sub-taxonomy):

- Replace `BodyParam.ColumnEq` with a sealed `BodyParam.ColumnPredicate` carrying four record arms:
    - `ColumnPredicate.Eq(ColumnRef column)` — emits `c.eq(val)`. Subsumes today's `ColumnEq` with `list == false`.
    - `ColumnPredicate.In(ColumnRef column)` — emits `c.in(vals)`. Subsumes today's `ColumnEq` with `list == true`.
    - `ColumnPredicate.RowEq(List<ColumnRef> columns)` — emits `DSL.row(c1, ..., cN).eq(DSL.row(v1, ..., vN))`. New shape; lands the composite-key single-tuple comparison.
    - `ColumnPredicate.RowIn(List<ColumnRef> columns)` — emits `DSL.row(c1, ..., cN).in(rows)`. New shape; replaces `BodyParam.NodeIdIn`'s row-IN body.
- The existing `boolean list` slot deletes; the operator chooses the value-arity. Each arm carries exactly the columns the operator needs (single `ColumnRef` for `Eq` / `In`; `List<ColumnRef>` for `RowEq` / `RowIn`); narrow component types per *Narrow component types over broad interfaces*.
- The body emitter switches on `ColumnPredicate`'s four arms with no `columns.size() == 1 ? ... : ...` ladder and no `list ? ... : ...` ladder. Adding a future predicate operator (e.g. `RowGreaterThan` for keyset pagination tuples) is a new sealed arm plus a validator-coverage arm plus an emitter switch arm; the same shape *Generation-thinking* expects.

`InputField.ColumnField` and `ScalarArg.ColumnArg` keep their names; they're column-shaped carriers, not predicates. `BodyParam.ColumnEq`'s name retires with the variant (the rename to `ColumnPredicate` is the new sealed root, not a renamed record arm).

## Variant-by-variant collapse

*Input side* (decode at the call site; body emits a column predicate over decoded keys):

- `InputField.NodeIdField` → multi-column `InputField.ColumnField` with `NodeIdDecodeKeys.NullOnMismatch` extraction; body is `ColumnPredicate.RowEq` (or `Eq` for arity-1).
- `InputField.NodeIdReferenceField` → multi-column `InputField.ColumnReferenceField` (FK joinPath retained) with `NodeIdDecodeKeys.NullOnMismatch` extraction; body is `ColumnPredicate.RowEq` (or `Eq` for arity-1).
- `InputField.NodeIdInFilterField` → multi-column `InputField.ColumnField` with `NodeIdDecodeKeys.SkipMismatchedElement` extraction; body is `ColumnPredicate.RowIn` (or `In` for arity-1).
- `InputField.IdReferenceField` → multi-column `InputField.ColumnReferenceField` over the FK columns with `NodeIdDecodeKeys.SkipMismatchedElement` extraction; body is `ColumnPredicate.Eq` (single FK), `ColumnPredicate.In` (single FK list arg), or `ColumnPredicate.RowIn` (composite FK list). Subsumes R20.
- `BodyParam.NodeIdIn` → `BodyParam.ColumnPredicate.RowIn` (or `In` for arity-1). `NodeIdEncoder.hasIds` deletes.
- `LookupMapping.NodeIdMapping` → regular `LookupMapping` over the PK columns; `LookupValuesJoinEmitter` decodes via `NodeIdDecodeKeys.ThrowOnMismatch` at the call site (lookup-key contract per "Failure-mode contract").
- `ArgumentRef.ScalarArg.NodeIdArg` → multi-column `ColumnArg` with `NodeIdDecodeKeys.NullOnMismatch` extraction for `Query.node(id:)` / `Query.nodes(ids:)` shapes, `ThrowOnMismatch` for lookup/mutation key shapes.

*Output side* (encode at projection; emitted column reads through a transform):

- `ChildField.NodeIdField` → multi-column `ChildField.ColumnField` with `ProjectionTransform.NodeIdEncodeKeys`.
- `ChildField.NodeIdReferenceField` → multi-column `ChildField.ColumnReferenceField` (FK joinPath retained) with `ProjectionTransform.NodeIdEncodeKeys`.

## `NodeIdEncoder` API

`hasIds` deletes (it is a query-builder helper that does not belong in the encoder). `canonicalize` deletes too: it has no caller in the generator codebase or in emitted code today. `QueryNodeFetcherClassGenerator`'s rowsNodes flow already documents in a comment that canonicalize was removed in favour of the dispatcher's idx-driven scatter (`Base64.getUrlDecoder` accepts both padded and unpadded forms, so canonicalisation at the encoder is dead weight). `NodeIdEncoderClassGenerator` stops emitting the `canonicalize` method; the regression test `GraphQLQueryTest.nodes_paddedBase64Id_canonicalizesAndResolves` keeps its assertion (the *behaviour* is preserved by the decoder's leniency, not by `canonicalize`). The stale comment block in `graphitron-test/src/main/resources/graphql/schema.graphqls` referencing `NodeIdEncoder.canonicalize` is rewritten in the same change.

`encode` / `decodeKeys` / `peekTypeId` stay (boundary helpers consumed by DataFetcher emitters and by the new extractions).

## Validator + dispatch coverage

Every retired variant comes off `TypeFetcherGenerator.NOT_DISPATCHED_LEAVES` and `GraphitronSchemaValidator`'s no-op arms. The new sealed arms get matching coverage in the validator's exhaustiveness tests:

- `CallSiteExtraction.NodeIdDecodeKeys.NullOnMismatch`, `.SkipMismatchedElement`, `.ThrowOnMismatch` — one validator-coverage arm each, mirroring the per-variant pattern the existing `CallSiteExtraction` arms (`Direct`, `EnumValueOf`, `TextMapLookup`, `ContextArg`, `JooqConvert`) already follow.
- `ProjectionTransform.Direct`, `ProjectionTransform.NodeIdEncodeKeys` — one validator-coverage arm each on the new sealed root.
- `BodyParam.ColumnPredicate.Eq`, `.In`, `.RowEq`, `.RowIn` — one validator-coverage arm each on the new predicate sub-taxonomy. The retired `ColumnEq` arm and its `boolean list` invariant come off.
- Carrier↔transform invariant: `ProjectionTransform.Direct → columns.size() == 1` on `ChildField.ColumnField` / `ChildField.ColumnReferenceField`. Validator-tier rule fails the build if a classifier path produces a `Direct` transform on a multi-column carrier (no such path exists today; the rule guards against future regressions).

Per *Validator mirrors classifier invariants*, every classifier decision that picks a sealed arm here implies a generator branch; the validator's coverage tests enforce that each arm has a dispatch-side implementation, so adding a future arm without an emitter fails at validate time rather than runtime.

Load-bearing classifier guarantees the new emitter shapes rely on (per *Classifier guarantees shape emitter assumptions*):

- `@LoadBearingClassifierCheck(key = "nodeid.decode.failure-mode")` on the classifier site that picks the `NodeIdDecodeKeys` arm; `@DependsOnClassifierCheck(key = "nodeid.decode.failure-mode", reliesOn = "...")` on each emitter site that switches on it. Relaxing the classifier's per-call-site selection breaks emitted failure handling.
- `@LoadBearingClassifierCheck(key = "columnpredicate.column-arity")` on the classifier site that picks `Eq` / `In` vs `RowEq` / `RowIn`; `@DependsOnClassifierCheck` on the body emitter. The arm's column shape (single `ColumnRef` vs `List<ColumnRef>`) is the carrier the emitter relies on with no defensive size check.

## Failure-mode contract

Three call-site categories want three different responses to a typeId mismatch. Folding the mode into a single `NodeIdDecodeKeys(typeId)` record with a flag would put the same predicate (`extraction.mode() == ...`) into every emitter that consumes the arm, exactly the multi-arm switch *Generation-thinking* says belongs in the model. R50 instead splits `NodeIdDecodeKeys` into three sealed arms under the existing `CallSiteExtraction` hierarchy; the classifier picks the arm at build time, the emitter switches exhaustively, and the validator gets a coverage arm per failure mode for free.

- `CallSiteExtraction.NodeIdDecodeKeys.NullOnMismatch(String typeId)` — `Query.node(id:)`, `Query.nodes(ids:)`, federated `_entities`. typeId mismatch on a single `id` resolves to `null` for the whole field; on one element of `ids`, to `null` for that element only. Parity with the existing Relay-spec behaviour `_entities` already implements.
- `CallSiteExtraction.NodeIdDecodeKeys.SkipMismatchedElement(String typeId)` — `[ID!] @nodeId(typeName: T)` filter argument. typeId mismatch on any element short-circuits the bad element to "no row matches"; never throws. Empty list and null arg follow existing column-equality behaviour (predicate omitted when the arg is absent, `falseCondition()` when the list is present-but-empty, matching today's `NodeIdInFilterField` body).
- `CallSiteExtraction.NodeIdDecodeKeys.ThrowOnMismatch(String typeId)` — `@nodeId` on a top-level scalar/list argument used as a lookup or mutation key. typeId mismatch is an authored-input error and surfaces as a `GraphqlErrorException`-shaped error, not silent null; a wrong-type id at a lookup key is a contract violation rather than "no match."

The shared decode primitive lives in `NodeIdEncoder.decodeKeys` (or a thin adapter next to it); each arm composes it with the appropriate failure response. Adding a future failure mode is a new sealed arm plus a validator-coverage arm plus an emitter switch arm; no shared flag to misset, no per-emitter mode-predicate to keep in sync.

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
- **R20 (`IdReferenceField` code generation)** had a Spec on the legacy variant; that Spec is invalidated by R50's framing because R20's emission shape dissolves into R50 (standard FK-equality or row-IN through a column-shaped variant, not a `has<Qualifier>` method call). R20 is now a tombstone in Backlog; its execution-tier coverage is folded into R50's "Test surface" and the R20 file deletes when R50 reaches Done.
- **R24 (`NodeIdReferenceField` JOIN-projection form)** also dissolves: the multi-hop / non-mirroring FK case becomes a column projection with `ProjectionTransform.NodeIdEncodeKeys` once `NodeIdReferenceField` retires.

## Test surface

- **Pipeline-tier.** Every retired variant's existing classification cases re-pointed at the column-shaped successor: `@nodeId` scalar output, `@nodeId` reference (FK-mirror and non-mirror), `@nodeId` filter on `[ID!]` arg, `@nodeId` argument on a top-level `[ID!]` arg, federated `_entities` resolver, `Query.node(id:)` and `Query.nodes(ids:)`. The retired variant classes are deleted in the same change, so "no instance survives" is enforced by the compiler rather than asserted. `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus` and `VariantCoverageTest.everySealedLeafHasAClassificationCase` keep their existing semantics (the four-set partition shrinks; the new column-shaped successors fall under existing IMPLEMENTED / PROJECTED status). Exhaustiveness for the new emitter-side sealed roots (`CallSiteExtraction.NodeIdDecodeKeys.*`, `ProjectionTransform.*`, `BodyParam.ColumnPredicate.*`) lives in the validator-tier coverage; see the next bullet.
- **Validator-tier.** `GraphitronSchemaValidator`'s coverage tests gain one arm per new sealed variant (per "Validator + dispatch coverage": three `NodeIdDecodeKeys` arms, two `ProjectionTransform` arms, four `ColumnPredicate` arms) and lose arms for the retired model variants. `TypeFetcherGenerator.NOT_DISPATCHED_LEAVES` shrinks; the dispatch-coverage test asserts the retired leaves are gone.
- **Compilation-tier.** `mvn compile -pl :graphitron-test -Plocal-db` against the `nodeidfixture` and `idreffixture` catalogs after the collapse, including the composite-PK Node and non-mirroring single-hop FK fixtures spelled out in "Fixture growth". `NodeIdEncoder.hasIds` is deleted from the encoder, so any emitted reference fails compilation here, no string scan needed.
- **Execution-tier.** Every existing `@nodeId` execution test continues to round-trip end-to-end: `Query.node(id:)`, `Query.nodes(ids:)`, federated `_entities`, same-table `[ID!] @nodeId` filter, FK-mirror reference, and the new composite-PK / non-mirroring FK cases. SQL inspection via `ExecuteListener` confirms emitted bodies use `c.eq(...)` / `c.in(...)` for `ColumnPredicate.Eq` / `In` and `DSL.row(c1, ..., cN).eq(...)` / `.in(...)` for `RowEq` / `RowIn`, with decoded key tuples flowing as bind values rather than encoded `String` ids. Failure-mode parity with "Failure-mode contract": one typeId-mismatch test per sealed `NodeIdDecodeKeys` arm — `NullOnMismatch` against `Query.node(id:)`, `SkipMismatchedElement` against `[ID!] @nodeId` filter, `ThrowOnMismatch` against a lookup-key argument.
