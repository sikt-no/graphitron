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

*Input side.* `CallSiteExtraction.NodeIdDecodeKeys` is a first-class sealed sub-taxonomy on the existing `CallSiteExtraction` hierarchy (three arms by failure mode; see "Failure-mode contract"), with full arms in `ArgCallEmitter.buildArgExtraction`. Each arm calls the per-NodeType `decode<TypeName>` helper (see "`NodeIdEncoder` API") to turn a `String` / `List<String>` into a typed value (a single column's Java type for arity-1 carriers, a `Record<N>` of typed key values for arity > 1), returning `null` on malformed input or typeId mismatch; the arms differ only in how that null surfaces. Coverage extends to the top-level-argument case: today `NestedInputField` is the only nested-Map traversal that runs extractions, but a top-level `[ID!] @nodeId` arg needs the same extraction at the call-site root, so `ArgCallEmitter`'s top-level path gains the same dispatch the nested path already has.

*Output side.* Encoding does not need a sealed sub-taxonomy. Both arity-1 and arity > 1 wrap the carrier's column(s) in a per-type `NodeIdEncoder.<encodeMethodName>(c1, ..., cN)` call; the only thing that varies is the carrier shape and how the helper name reaches the projection emitter. The arity-1 case projects through the existing single-column `ChildField.ColumnField` / `ChildField.ColumnReferenceField` carrying a new `encodeMethodName: Optional<String>` slot (empty → plain SELECT term as today; present → encode wrap). The arity > 1 case routes through `ChildField.CompositeColumnField` / `ChildField.CompositeColumnReferenceField`, which carry the helper name as a mandatory string slot. One projection shape doesn't earn a sealed root; if a future transform lands (e.g. `JsonAggregate`), introduce `ProjectionTransform` then.

The asymmetry between sides is structural, not a missed simplification: `CallSiteExtraction` already plays the role of "how to translate the wire value at the boundary" on the input side, so a parallel `InputTransform` would duplicate it. The output side has no equivalent existing root; with one shape today there is nothing yet to factor.

Failure-mode contract: see "Failure-mode contract" below.

## Composite-key column carriers

The column-shaped variants today are single-column: `InputField.ColumnField`, `ArgumentRef.ScalarArg.ColumnArg`, and `BodyParam.ColumnEq` each carry one `ColumnRef`; `LookupMapping.ColumnMapping` already carries a `List<LookupColumn>` and `LookupMapping.NodeIdMapping` already carries a `List<ColumnRef> nodeKeyColumns`, so composite is established on the mapping side. The replacements for the wire-shape variants need to carry a composite key with row-IN body emission (`DSL.row(c1, ..., cN).in(rows)`), degenerating to single-column `c.in(...)` / `c.eq(...)` for arity-1.

**Decision: split carriers from predicates, and split single-column carriers from composite-key carriers.** Column-shaped *carriers* keep their narrow types: single-column carriers (every `*.ColumnField` / `*.ColumnReferenceField` / `*.ColumnArg`) stay typed at one `ColumnRef`; new sibling `Composite*` variants carry `List<ColumnRef>` for the genuine multi-column cases. Per *Narrow component types over broad interfaces*, a field whose classifier guarantees a single column should not be retyped as a list "in case." Column-shaped *predicates* (`BodyParam.ColumnEq` and its successors) get a sealed sub-taxonomy because the predicate operator (eq vs in, single-column vs row) *is* a model axis: folding four operators into one record + two flags pushes a 4-way switch into every emitter that builds a `Condition`.

*Carriers* (single-column stays single; composite-key gets new variants):

- Single-column input carriers gain an `extraction: CallSiteExtraction` slot so they can host arity-1 NodeId cases (extraction = `NodeIdDecodeKeys.*`) alongside today's plain / `JooqConvert` / `EnumValueOf` shapes: `InputField.ColumnField` and `InputField.ColumnReferenceField` both add the slot. `ArgumentRef.ScalarArg.ColumnArg` already carries `extraction`; nothing to add there.
- Single-column output carriers gain `encodeMethodName: Optional<String>` to host arity-1 NodeId-encoded projections: `ChildField.ColumnField` and `ChildField.ColumnReferenceField` both add the slot. Empty → plain SELECT term (today's behaviour); present → `NodeIdEncoder.<encodeMethodName>(c)` wrap. The slot is `Optional` rather than two carrier variants because the only axis varying is "encode or don't" — not a sub-taxonomy worth a sealed root.
- New variants for arity > 1: `InputField.CompositeColumnField`, `InputField.CompositeColumnReferenceField`, `ChildField.CompositeColumnField`, `ChildField.CompositeColumnReferenceField`, `ArgumentRef.ScalarArg.CompositeColumnArg`. Each carries `columns: List<ColumnRef>` (size ≥ 2; arity-1 routes to the single-column siblings) plus the side-appropriate boundary slot — `extraction: CallSiteExtraction` for the input-side and arg variants, `encodeMethodName: String` (mandatory, no Optional) for the output-side variants. `joinPath` is preserved on the `CompositeColumnReferenceField` arms.
- `ChildField.ColumnField.columnName: String` and `ChildField.ColumnReferenceField.columnName: String` retire in the same change. The slot is redundant with `column.javaName()` / the resolved `ColumnRef`'s SQL name accessors, and "two strings naming the same column" has been a long-standing footgun. `ChildField.PropertyField` and `ChildField.RecordField` keep their `columnName` slots — those carriers tolerate a null `column` (non-table-backed parents), so `columnName` is the only carrier of the SDL string in those cases. <todo>this is a design smell, create a backlog item for cleaning this up</todo>
- Callers and switch arms across `BuildContext`, `FieldBuilder`, `TypeBuilder`, `TypeFetcherGenerator`, `GraphitronSchemaValidator`, `TypeConditionsGenerator`, `FetcherEmitter`, and the projection emitters gain switch arms for the new `Composite*` variants. The wire-shape variants delete outright once their classifier arms route to either the existing single-column carriers (arity-1) or the new `Composite*` siblings (arity > 1).

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

Each retiring variant routes to either the existing single-column carrier (arity-1) or the new `Composite*` sibling (arity > 1). The classifier picks the arm by the target NodeType's `keyColumns.size()`.

*Input side* (decode at the call site; body emits a column predicate over decoded keys):

- `InputField.NodeIdField` → arity-1: `InputField.ColumnField` with `extraction = NodeIdDecodeKeys.NullOnMismatch(decodeMethodName)`, body is `ColumnPredicate.Eq`. Arity > 1: `InputField.CompositeColumnField` with the same extraction, body is `ColumnPredicate.RowEq`.
- `InputField.NodeIdReferenceField` → arity-1: `InputField.ColumnReferenceField` (FK joinPath retained) with `NodeIdDecodeKeys.NullOnMismatch`, body `ColumnPredicate.Eq`. Arity > 1: `InputField.CompositeColumnReferenceField`, body `ColumnPredicate.RowEq`.
- `InputField.NodeIdInFilterField` → arity-1: `InputField.ColumnField` with `NodeIdDecodeKeys.SkipMismatchedElement`, body `ColumnPredicate.In`. Arity > 1: `InputField.CompositeColumnField`, body `ColumnPredicate.RowIn`.
- `InputField.IdReferenceField` → arity-1: `InputField.ColumnReferenceField` over the FK column with `NodeIdDecodeKeys.SkipMismatchedElement`, body `ColumnPredicate.Eq` (single FK) or `In` (single FK list arg). Arity > 1: `InputField.CompositeColumnReferenceField`, body `ColumnPredicate.RowIn`. Subsumes R20.
- `BodyParam.NodeIdIn` → arity-1: `BodyParam.ColumnPredicate.In`. Arity > 1: `BodyParam.ColumnPredicate.RowIn`. `NodeIdEncoder.hasIds` deletes.
- `LookupMapping.NodeIdMapping` → retires outright. `LookupMapping.ColumnMapping` restructures around an arg layer with sealed `LookupArg` arms (see "Lookup arg restructure" below); the NodeId lookup case folds onto `ScalarLookupArg` (single-key target) or `CompositeLookupArg` (composite-key target). `buildNodeIdFetcherBody` deletes; the lookup case shares the same VALUES + JOIN body the rest of the lookup paths use.
- `ArgumentRef.ScalarArg.NodeIdArg` → arity-1: `ScalarArg.ColumnArg` with `NodeIdDecodeKeys.NullOnMismatch` for `Query.node(id:)` / `Query.nodes(ids:)` shapes, `ThrowOnMismatch` for lookup/mutation key shapes. Arity > 1: `ScalarArg.CompositeColumnArg` with the corresponding failure-mode arm.

*Output side* (encode at projection; arity-1 wraps via the `encodeMethodName` slot, arity > 1 routes to `Composite*`):

- `ChildField.NodeIdField` → arity-1: `ChildField.ColumnField` with `encodeMethodName = Optional.of(encodeMethodName)`. Arity > 1: `ChildField.CompositeColumnField` with mandatory `encodeMethodName: String`.
- `ChildField.NodeIdReferenceField` → arity-1: `ChildField.ColumnReferenceField` (FK joinPath retained) with `encodeMethodName = Optional.of(encodeMethodName)`. Arity > 1: `ChildField.CompositeColumnReferenceField`.

*Lookup arg restructure.* `LookupMapping.ColumnMapping` retypes its column list as a per-arg list, so the boundary translation lives once per arg rather than copied across N columns:

```java
record ColumnMapping(List<LookupArg> args, TableRef targetTable) implements LookupMapping {
    sealed interface LookupArg permits ScalarLookupArg, CompositeLookupArg {
        String argName();
        boolean list();
    }

    record ScalarLookupArg(
        String argName,
        ColumnRef targetColumn,
        CallSiteExtraction extraction,   // Direct, JooqConvert, NodeIdDecodeKeys.*
        boolean list
    ) implements LookupArg {}

    record CompositeLookupArg(
        String argName,
        boolean list,
        CallSiteExtraction argExtraction,    // applied once per row to env.getArgument(argName)
        List<Binding> bindings               // one per target column
    ) implements LookupArg {
        record Binding(
            String slotKey,                  // input field name (Map case) or slot index (Record<N> case)
            ColumnRef targetColumn,
            CallSiteExtraction extraction
        ) {}
    }
}
```

Folds the four shapes:

- Non-NodeId single-arg lookup → `ScalarLookupArg(name, col, Direct, list)`.
- NodeId single-key lookup → `ScalarLookupArg(name, col, NodeIdDecodeKeys.ThrowOnMismatch(decode), list)`.
- Composite-key input type → `CompositeLookupArg(name, list, Direct, [Binding(fieldName, col, Direct), …])`.
- Composite-PK NodeId → `CompositeLookupArg(name, list, NodeIdDecodeKeys.ThrowOnMismatch(decode), [Binding("0", col1, Direct), …])`.

`SourcePath` and the existing `LookupColumn` record retire. `LookupValuesJoinEmitter`'s `rootSources()` grouping logic and the per-column `columnValueExpr` ladder both retire — the emitter switches at the arg layer on `LookupArg`'s sealed arms, then on `argExtraction`'s arm to choose Map-keyed vs Record-keyed slot access. Multiple args still combine into one VALUES row by sitting as distinct entries in `args`; the existing `Row<N+1>` arity cap covers the sum of slots across args.

*Non-mirror single-hop emission.* When the FK columns do not positionally match the target NodeType's `keyColumns` but the target is one hop away, R50's column-shaped successors emit via JOIN-with-projection (output) and JOIN + row-IN (input), threaded through the carrier's existing `joinPath`. On the output side, the projection emitter resolves the target alias from `joinPath` and calls `encode<TypeName>(target_alias.k1, ..., target_alias.kN)` against the joined target's columns rather than the parent's FK source columns. On the input side, the row predicate emits against `target_alias.k1, ..., target_alias.kN` (matching the decoded key tuples) rather than against the parent's FK columns. FK-mirror retains today's no-JOIN shortcut (parent's FK source columns positionally equal the target keys; encode / filter reads them directly). Multi-hop and condition-join cases stay in R24 as correlated-subquery emission; R50's JOIN-with-projection covers single-hop only.

## `NodeIdEncoder` API

The encoder's public surface becomes per-Node-type. For each `@node` GraphQL type `T` with `keyColumns = [tab.k1, ..., tab.kN]` of types `T1..TN`, `NodeIdEncoderClassGenerator` emits two static helpers alongside `peekTypeId`:

```java
static String encode<TypeName>(T1 v1, ..., TN vN) { return encode("<typeId>", v1, ..., vN); }

static Record<N><T1, ..., TN> decode<TypeName>(String base64Id) {
    String[] values = decodeValues("<typeId>", base64Id);
    if (values == null) return null;
    var rec = DSL.using(SQLDialect.DEFAULT).newRecord(tab.k1, ..., tab.kN);
    rec.set(tab.k1, tab.k1.getDataType().convert(values[0]));
    ...
    rec.set(tab.kN, tab.kN.getDataType().convert(values[N-1]));
    return rec;
}
```

Helper-method names are pre-resolved on `GraphitronType.NodeType` (see "What stays") so every emitter that calls them, and the encoder generator that defines them, read from one source of truth and cannot drift. Arity cap is `RecordN`'s 22-slot ceiling, which matches the existing `LookupValuesJoinEmitter.rowTypeArgs` cap.

**Public**:

- `peekTypeId(String) → String` — needed by typeId-fanout sites (`Query.nodes(ids:)` batch dispatch, federated `_entities`) that route to the correct `decode<TypeName>` before committing to a type. The only generic public method.
- `encode<TypeName>(...) → String` per `@node` type. Bakes the typeId into the helper name; consumers do not pass the typeId string.
- `decode<TypeName>(String) → Record<N><...>` per `@node` type. Returns `null` uniformly for malformed input or typeId mismatch; the `NodeIdDecodeKeys.*` arms wrap that null per failure mode (see "Failure-mode contract").

**Private**:

- `encode(String typeId, Object... values)` — generic body the per-type `encode<TypeName>` helpers delegate to.
- `decodeValues(String typeId, String base64Id) → String[]` — generic body the per-type `decode<TypeName>` helpers delegate to.

**Deleted**:

- `hasIds` / `hasId` — query-builder helpers that do not belong in the encoder. The body emitter shapes (`ColumnPredicate.RowIn` / `In` / `Eq` / `RowEq`) replace them in every consumer.
- `coerceValue` — the per-type `decode<TypeName>` helpers know their column types statically and inline `getDataType().convert(...)` per slot, so the runtime branch over `OffsetDateTime` / `LocalDate` / etc. is no longer needed.
- `canonicalize` — has no caller in the generator codebase or in emitted code today. `QueryNodeFetcherClassGenerator`'s rowsNodes flow already documents in a comment that canonicalize was removed in favour of the dispatcher's idx-driven scatter (`Base64.getUrlDecoder` accepts both padded and unpadded forms, so canonicalisation at the encoder is dead weight). The regression test `GraphQLQueryTest.nodes_paddedBase64Id_canonicalizesAndResolves` keeps its assertion (the *behaviour* is preserved by the decoder's leniency, not by `canonicalize`). The stale comment block in `graphitron-test/src/main/resources/graphql/schema.graphqls` referencing `NodeIdEncoder.canonicalize` is rewritten in the same change.

Consumer call-site rewrites that drop out: `FetcherEmitter`'s `ChildField.NodeIdField` / `NodeIdReferenceField` projection lambdas (today `NodeIdEncoder.encode("Film", r.get(...), ...)`) become `NodeIdEncoder.encodeFilm(r.get(...), ...)` via the carrier's `encodeMethodName` slot (arity-1 single-column carriers) or its mandatory `encodeMethodName` field (arity > 1 `Composite*` carriers); `TypeFetcherGenerator`'s rowsNodes lambda rewrites the same way; `LookupValuesJoinEmitter.buildNodeIdFetcherBody` deletes wholesale; `Query.node(id:)` and `Query.nodes(ids:)` dispatchers route through `decode<TypeName>` per typeId. The mutation DML emitter's encode call (today `NodeIdEncoder.encode("typeId", v1, ..., vN)` from `MutationField.DmlTableField.nodeIdMeta`) rewrites to `NodeIdEncoder.encode<TypeName>(v1, ..., vN)` in the same change — required because the generic `encode` becomes private; what stays out of scope is unifying the *dispatch* with the child-field side (see "What we're NOT doing"). The string typeId only appears at the schema-classifier boundary (where it is read once from the SDL `@node` directive) and inside `NodeIdEncoderClassGenerator` itself; runtime call sites name typed methods.

## Validator + dispatch coverage

Every retired variant comes off `TypeFetcherGenerator.NOT_DISPATCHED_LEAVES` and `GraphitronSchemaValidator`'s no-op arms. The new sealed arms get matching coverage in the validator's exhaustiveness tests:

- `CallSiteExtraction.NodeIdDecodeKeys.NullOnMismatch`, `.SkipMismatchedElement`, `.ThrowOnMismatch` — one validator-coverage arm each, mirroring the per-variant pattern the existing `CallSiteExtraction` arms (`Direct`, `EnumValueOf`, `TextMapLookup`, `ContextArg`, `JooqConvert`) already follow.
- `InputField.CompositeColumnField`, `InputField.CompositeColumnReferenceField`, `ChildField.CompositeColumnField`, `ChildField.CompositeColumnReferenceField`, `ArgumentRef.ScalarArg.CompositeColumnArg` — one validator-coverage arm each on the corresponding sealed roots.
- `LookupMapping.ColumnMapping.LookupArg`'s two arms (`ScalarLookupArg`, `CompositeLookupArg`) — one validator-coverage arm each. The retired `LookupMapping.NodeIdMapping` arm comes off.
- `BodyParam.ColumnPredicate.Eq`, `.In`, `.RowEq`, `.RowIn` — one validator-coverage arm each on the new predicate sub-taxonomy. The retired `ColumnEq` arm and its `boolean list` invariant come off.
- Carrier-arity invariant: `Composite*` variants must carry `columns.size() ≥ 2` (arity-1 routes to the single-column siblings); upper bound is the `Record<N>` / `Row<N+1>` 22-slot ceiling that already governs lookup VALUES rows. Validator-tier rule fails the build if a classifier path produces a `Composite*` carrier with `columns.size() < 2` (the routing rule would have picked the single-column sibling), or any `columns.size() > 22` (architectural cap). The lower-bound rule guards against future regressions; arity-zero is structurally meaningless.
- Single-column-carrier encode invariant: `ChildField.ColumnField.encodeMethodName.isPresent()` and `ChildField.ColumnReferenceField.encodeMethodName.isPresent()` only when the GraphQL type is `ID` and the parent table participates in a `NodeType` whose `keyColumns.size() == 1`. The classifier sets the slot in those cases and only those; the validator enforces.

Per *Validator mirrors classifier invariants*, every classifier decision that picks a sealed arm here implies a generator branch; the validator's coverage tests enforce that each arm has a dispatch-side implementation, so adding a future arm without an emitter fails at validate time rather than runtime.

Load-bearing classifier guarantees the new emitter shapes rely on (per *Classifier guarantees shape emitter assumptions*):

- `@LoadBearingClassifierCheck(key = "nodeid.decode.failure-mode")` on the classifier site that picks the `NodeIdDecodeKeys` arm; `@DependsOnClassifierCheck(key = "nodeid.decode.failure-mode", reliesOn = "...")` on each emitter site that switches on it. Relaxing the classifier's per-call-site selection breaks emitted failure handling.
- `@LoadBearingClassifierCheck(key = "columnpredicate.column-arity")` on the classifier site that picks `Eq` / `In` vs `RowEq` / `RowIn` (and the parallel single-column-carrier vs `Composite*`-carrier routing decision); `@DependsOnClassifierCheck` on the body emitter and on every projection / call-site emitter that consumes the carriers. The arm's column shape (single `ColumnRef` vs `List<ColumnRef>` with size ≥ 2) is the carrier the emitter relies on with no defensive size check.

## Failure-mode contract

Three call-site categories want three different responses to a `decode<TypeName>` returning `null` (malformed input or typeId mismatch). Folding the mode into a single `NodeIdDecodeKeys(decodeMethodName, mode)` record would put the same predicate (`extraction.mode() == ...`) into every emitter that consumes the arm, exactly the multi-arm switch *Generation-thinking* says belongs in the model. R50 instead splits `NodeIdDecodeKeys` into three sealed arms under the existing `CallSiteExtraction` hierarchy; the classifier picks the arm at build time, the emitter switches exhaustively, and the validator gets a coverage arm per failure mode for free.

- `CallSiteExtraction.NodeIdDecodeKeys.NullOnMismatch(String decodeMethodName)` — every Query field classified as a node-by-id fetcher (any Query field whose element type is the `Node` interface, per the signature-based classifier; not just literal `node` / `nodes`), plus the federated `_entities` resolver. A `null` return from `decode<TypeName>` on a single `id` resolves to `null` for the whole field; on one element of `ids`, to `null` for that element only. Parity with the existing Relay-spec behaviour `_entities` already implements.
- `CallSiteExtraction.NodeIdDecodeKeys.SkipMismatchedElement(String decodeMethodName)` — `[ID!] @nodeId(typeName: T)` filter argument. A `null` return on any element short-circuits the bad element to "no row matches"; never throws. Empty list and null arg follow existing column-equality behaviour (predicate omitted when the arg is absent, `falseCondition()` when the list is present-but-empty, matching today's `NodeIdInFilterField` body).
- `CallSiteExtraction.NodeIdDecodeKeys.ThrowOnMismatch(String decodeMethodName)` — `@nodeId` on a top-level scalar/list argument used as a lookup or mutation key. A `null` return is an authored-input error and surfaces as a `GraphqlErrorException`-shaped error, not silent null; a wrong-type id at a lookup key is a contract violation rather than "no match."

The shared decode primitive is the per-Node-type `decode<TypeName>` helper (see "`NodeIdEncoder` API"); each arm composes it with the appropriate response to a `null` return. Adding a future failure mode is a new sealed arm plus a validator-coverage arm plus an emitter switch arm; no shared flag to misset, no per-emitter mode-predicate to keep in sync.

## What stays

`GraphitronType.NodeType` stays. It carries `(typeId, keyColumns)`, which is type-level identity: the schema author declared this type as a Node and these are its key columns. Identity is a model concern; the wire format derived from that identity is not.

`NodeType` gains two pre-resolved string fields, `encodeMethodName` and `decodeMethodName`, derived once by the schema builder from the GraphQL type name (rule colocated with `NodeType`, e.g. `"encode" + capitalised(typeName)`). They are the single source of truth for the helper names: `NodeIdEncoderClassGenerator` reads them when emitting the helper definitions; the `encodeMethodName` slot on `ChildField.ColumnField` / `ChildField.ColumnReferenceField` / `ChildField.CompositeColumnField` / `ChildField.CompositeColumnReferenceField` and the `CallSiteExtraction.NodeIdDecodeKeys.*` arms each carry a copy via the classifier. No emitter computes a helper name on its own, so no two emitters can disagree.

`MutationField.DmlTableField.nodeIdMeta: Optional<JooqCatalog.NodeIdMetadata>` (introduced upstream in mutations Phase 1, R22) stays for the same reason. `NodeIdMetadata(String typeId, List<ColumnRef> keyColumns)` is structurally the same identity data as `NodeType`, carried per-mutation so the DML emitter knows which key columns to encode for an `ID`-returning mutation. The mutation-return encode call resolves to the same per-type helper (`NodeIdEncoder.encode<TypeName>(k1, ..., kN)`), so mutation returns and child-field projections share one boundary even though they reach it via different model carriers (see "What we're NOT doing" on dispatch unification).

## What we're NOT doing

- **The wire format itself.** `@nodeId` ids stay as base64-encoded `typeId` + composite key. R50 is a model-side lift, not a protocol change; consumer schemas and clients see the same id strings.
- **Wire-format primitives in `NodeIdEncoder` beyond what "`NodeIdEncoder` API" lists.** The generic `encode` and `decodeValues` bodies become private but keep their current behaviour; `peekTypeId` keeps its current shape; the per-type `encode<TypeName>` / `decode<TypeName>` helpers are thin generated wrappers over them. If a downstream change wants to refactor the wire format itself, it is a separate item.
- **Multi-hop FK and condition-join correlated-subquery emission.** R50's column-shaped successors cover three FK shapes: FK-mirror (no JOIN), non-mirroring single-hop (JOIN-with-projection on output, JOIN + row-IN on input), and composite-PK keyed Node types. Anything past one hop, or where the FK is replaced by a condition-join, still wants a correlated-subquery emission projecting the target's key columns under aliases; that machinery stays in R24's scope. <todo>I don't get the mirror concept, can we find better terms? Should we talk about derived tables here?</todo>
- **Consumer schema migration.** No changes required in consuming schemas; `[ID!] @nodeId(typeName: T)` and `@nodeId` on scalar / list outputs are the same surface they are today.
- **Mutation return-encode dispatch unification.** R50 rewrites the DML emitter's existing `NodeIdEncoder.encode("typeId", v1, ..., vN)` call to the per-type helper `NodeIdEncoder.encode<TypeName>(v1, ..., vN)` (required because the generic `encode` becomes private in the same change; see "`NodeIdEncoder` API → Consumer call-site rewrites"). What stays out of scope is unifying the *model dispatch*: the DML emitter still reaches the encode helper via `MutationField.DmlTableField.nodeIdMeta` rather than through a child-field-style carrier slot, so child-field projections and mutation returns share the same generated boundary call but reach it through different model dispatches. Aligning those dispatches is principled but a separate change; a future item can lift mutation returns onto a shared hook once the `ChildField` side has settled.

## Fixture growth

R50 adds two fixture shapes to the existing `nodeidfixture` to exercise the composite-key paths the lift unlocks:

- **Composite-PK Node.** A table with a two-column PK plus `__NODE_TYPE_ID`, exposed as a NodeType. Drives the row-IN body emission for `[ID!] @nodeId` filters and the row-projection encode path on output.
- **Non-mirroring single-hop FK.** A reference whose FK columns do not positionally match the target NodeType's `keyColumns`, but where the FK target is reachable in one hop. Drives `NodeIdReferenceField` collapse for the non-mirror case without pulling in correlated-subquery emission.

Multi-hop FK fixture growth is explicitly deferred to R24 (see "What we're NOT doing").

## Coupling

- **R40 (argument-level `@nodeId` support)** depends on R50. R40 is shaped as a small classifier-only follow-on once R50's foundation is in place: extend `FieldBuilder.classifyArgument` to read `@nodeId(typeName: T)` on an argument, validate same-table, build a column-shaped argument with `NodeIdDecodeKeys`. R40 does not land any new model variants and adds nothing to the wire-shape debt.
- **R20 (`IdReferenceField` code generation)** had a Spec on the legacy variant; that Spec is invalidated by R50's framing because R20's emission shape dissolves into R50 (standard FK-equality or row-IN through a column-shaped variant, not a `has<Qualifier>` method call). R20 is now a tombstone in Backlog; its execution-tier coverage is folded into R50's "Test surface" and the R20 file deletes when R50 reaches Done.
- **R24 (`NodeIdReferenceField` JOIN-projection form)** shrinks rather than dissolving. R50 absorbs the non-mirroring single-hop FK case as a JOIN-with-projection through the column-shaped successor (see "Variant-by-variant collapse → Non-mirror single-hop emission"), so R24's scope contracts to multi-hop and condition-join correlated-subquery emission. R24 retains its file and stays in Backlog; re-spec it once a real schema needs the multi-hop shape.
- **`columnName` redundancy cleanup (in scope).** `ChildField.ColumnField.columnName` and `ChildField.ColumnReferenceField.columnName` retire in the same change as the carrier-slot additions (`encodeMethodName`); see "Composite-key column carriers". `ChildField.PropertyField` and `ChildField.RecordField` keep their `columnName` slots — their `column: ColumnRef` is nullable for non-table-backed parents, so `columnName` is the only carrier of the SDL string in those cases.

## Test surface

- **Pipeline-tier.** Every retired variant's existing classification cases re-pointed at the appropriate successor — single-column carriers for arity-1, `Composite*` for arity > 1: `@nodeId` scalar output, `@nodeId` reference (FK-mirror and non-mirror), `@nodeId` filter on `[ID!]` arg, `@nodeId` argument on a top-level `[ID!]` arg, federated `_entities` resolver, `Query.node(id:)` and `Query.nodes(ids:)`. The retired variant classes are deleted in the same change, so "no instance survives" is enforced by the compiler rather than asserted. Any unit-tier structural tests scoped to a retired variant (e.g. tests asserting on `NodeIdInFilterField`'s record shape) delete with the variant. `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus` and `VariantCoverageTest.everySealedLeafHasAClassificationCase` keep their existing semantics (the four-set partition shrinks; the new column-shaped successors fall under existing IMPLEMENTED / PROJECTED status). Exhaustiveness for the new emitter-side sealed roots (`CallSiteExtraction.NodeIdDecodeKeys.*`, `BodyParam.ColumnPredicate.*`, `LookupArg.*`) and the new `Composite*` carrier variants lives in the validator-tier coverage; see the next bullet.
- **Validator-tier.** `GraphitronSchemaValidator`'s coverage tests gain one arm per new sealed variant (per "Validator + dispatch coverage": three `NodeIdDecodeKeys` arms, four `ColumnPredicate` arms, two `LookupArg` arms, five `Composite*` carrier arms) and lose arms for the retired model variants. `TypeFetcherGenerator.NOT_DISPATCHED_LEAVES` shrinks; the dispatch-coverage test asserts the retired leaves are gone.
- **Compilation-tier.** `mvn compile -pl :graphitron-test -Plocal-db` against the `nodeidfixture` and `idreffixture` catalogs after the collapse, including the composite-PK Node and non-mirroring single-hop FK fixtures spelled out in "Fixture growth". `NodeIdEncoder.hasIds` is deleted from the encoder, so any emitted reference fails compilation here, no string scan needed.
- **Execution-tier.** Every existing `@nodeId` execution test continues to round-trip end-to-end: `Query.node(id:)`, `Query.nodes(ids:)`, federated `_entities`, same-table `[ID!] @nodeId` filter, FK-mirror reference, and the new composite-PK / non-mirroring FK cases. SQL inspection via `ExecuteListener` confirms emitted bodies use `c.eq(...)` / `c.in(...)` for `ColumnPredicate.Eq` / `In` and `DSL.row(c1, ..., cN).eq(...)` / `.in(...)` for `RowEq` / `RowIn`, with decoded key tuples flowing as bind values rather than encoded `String` ids. Failure-mode parity with "Failure-mode contract": one typeId-mismatch test per sealed `NodeIdDecodeKeys` arm — `NullOnMismatch` against `Query.node(id:)`, `SkipMismatchedElement` against `[ID!] @nodeId` filter, `ThrowOnMismatch` against a lookup-key argument.
