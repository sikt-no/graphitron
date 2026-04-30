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

## Operation taxonomy: lookup vs query

This section is documentation only within R50: it names a distinction the existing variant identities (`LookupMapping` vs everything else) already encode, so no new sealed type lands here. Lifting "lookup vs query" into a first-class model axis (with a sealed `Operation` carrier and a validator-side homogeneity rule) is tracked separately in **R52**; spin it up once a cross-cutting consumer (validator, dispatcher, fixture predicate) actually needs to ask the question.

Two operations underlie every emitted SQL statement, with structurally different shapes:

- **Lookup** (set ⋈ set). Input is a set of typed values; output is one row per input value, in input order. SQL signature: a derived VALUES table carrying the input values plus an `idx` column, JOINed against the data table, ordered by `idx`. The derived table is the *signature of a lookup*: it exists precisely because per-input-row identity has to survive the round-trip.
- **Query** (predicate(set)). Input is a set of predicates over field values; output is whichever rows satisfy them. SQL signature: a WHERE clause over the data table, with optional JOIN or correlated subquery to bring foreign keys into scope. No derived input table, because predicates fold into WHERE; there is no per-input-row identity to preserve.

The "rooted at X" shapes in "Variant-by-variant collapse → Single-hop emission, two shapes" apply cleanly to *queries* — the predicate or projected column has to be rooted at some table, and that's where the rooted-at-child / rooted-at-parent split lives. *Lookups* don't have that split because both tables are pinned by the operation's SQL signature: encode/filter reads from the data table, `idx` rides on the derived values table.

Variant routing across the two operations:

| R50 variant | Operation |
|---|---|
| `[ID!] @nodeId` filter (`InputField.NodeIdInFilterField`) | Query — WHERE row-IN over decoded tuples |
| Input-side `@nodeId` references (`InputField.NodeIdReferenceField`, `InputField.IdReferenceField`; both rooted-at shapes) | Query — WHERE on child columns or parent alias |
| Output-side `@nodeId` projections (`ChildField.NodeIdField`, `ChildField.NodeIdReferenceField`) | Query — encode column in the SELECT clause (rooted at child or parent) |
| `@lookupKey` lookup field | Lookup — VALUES + JOIN, idx-ordered |
| `@nodeId` lookup arg (today's `LookupMapping.NodeIdMapping`) | Lookup — same after R50 collapse onto `LookupArg` |
| `Query.nodes(ids:)` per-typeId batch | Lookup — see *Query.nodes folds onto the lookup pipeline* below |
| `Query.node(id:)` | Lookup of arity-1; degenerates to query shape (single WHERE) since ordering is moot |

## Wire-shape leaks to retire

- `InputField.NodeIdField`, `NodeIdReferenceField`, `NodeIdInFilterField`, `IdReferenceField`
- `ChildField.NodeIdField`, `ChildField.NodeIdReferenceField`
- `BodyParam.NodeIdIn`
- `LookupMapping.NodeIdMapping`
- `ArgumentRef.ScalarArg.NodeIdArg`

Each is the same wire-shape leak in a different position.

## Boundary extractions

*Input side.* `CallSiteExtraction.NodeIdDecodeKeys` is a first-class sealed sub-taxonomy on the existing `CallSiteExtraction` hierarchy (three arms by failure mode; see "Failure-mode contract"), with full arms in `ArgCallEmitter.buildArgExtraction`. Each arm calls the per-NodeType `decode<TypeName>` helper (see "`NodeIdEncoder` API") to turn a `String` / `List<String>` into a typed value (a single column's Java type for arity-1 carriers, a `Record<N>` of typed key values for arity > 1), returning `null` on malformed input or typeId mismatch; the arms differ only in how that null surfaces. Coverage extends to the top-level-argument case: today `NestedInputField` is the only nested-Map traversal that runs extractions, but a top-level `[ID!] @nodeId` arg needs the same extraction at the call-site root, so `ArgCallEmitter`'s top-level path gains the same dispatch the nested path already has.

*Output side.* Encoding is the symmetric counterpart of input-side extraction, so it gets a parallel sealed taxonomy: `CallSiteCompaction`, lives at `no.sikt.graphitron.rewrite.model`, with two initial arms — `Direct()` for plain SELECT-term projection (today's behaviour for non-NodeId columns) and `NodeIdEncodeKeys(HelperRef encodeMethod)` for the per-type `NodeIdEncoder.encode<TypeName>(c1, ..., cN)` wrap. Single-column output carriers (`ChildField.ColumnField` / `ColumnReferenceField`) carry `compaction: CallSiteCompaction` non-Optional; composite output carriers narrow the slot to `CallSiteCompaction.NodeIdEncodeKeys` (see "Composite-key column carriers"). The projection emitter switches on the slot's sealed arm to decide how to wrap the carrier's column(s).

The two sides line up: every input-side carrier carries `extraction: CallSiteExtraction`; every output-side carrier carries `compaction: CallSiteCompaction`. Extraction and compaction are inverse boundary translations — extraction reads a wire value into a typed Java value, compaction writes a typed Java value back into a wire value — and both classify exhaustively at the parse boundary.

Failure-mode contract: see "Failure-mode contract" below.

## Composite-key column carriers

The column-shaped variants today are single-column on the field/arg side: `InputField.ColumnField`, `ArgumentRef.ScalarArg.ColumnArg`, and `BodyParam.ColumnEq` each carry one `ColumnRef`. Composite is already established on the mapping side: `LookupMapping.ColumnMapping` carries a `List<LookupColumn>` and `LookupMapping.NodeIdMapping` carries a `List<ColumnRef> nodeKeyColumns`. For R50's collapse, composite has to be expressible on the field/arg side too — composite-PK NodeIds want a `DSL.row(c1, ..., cN).in(rows)` body for `[ID!] @nodeId` filters, an N-column encode call on output, and so on. Arity-1 cases stay on the existing single-column shape (`c.in(...)` / `c.eq(...)`).

**Decision: split carriers from predicates, and split single-column carriers from composite-key carriers.** Column-shaped *carriers* keep their narrow types: single-column carriers (every `*.ColumnField` / `*.ColumnReferenceField` / `*.ColumnArg`) stay typed at one `ColumnRef`; new sibling `Composite*` variants carry `List<ColumnRef>` for the genuine multi-column cases. Per *Narrow component types over broad interfaces*, a field whose classifier guarantees a single column should not be retyped as a list "in case." Column-shaped *predicates* (`BodyParam.ColumnEq` and its successors) get a sealed sub-taxonomy because the predicate operator (eq vs in, single-column vs row) *is* a model axis: folding four operators into one record + two flags pushes a 4-way switch into every emitter that builds a `Condition`.

*Carriers* (single-column stays single; composite-key gets new variants):

- Single-column input carriers gain an `extraction: CallSiteExtraction` slot so they can host arity-1 NodeId cases (extraction = `NodeIdDecodeKeys.*`) alongside today's plain / `JooqConvert` / `EnumValueOf` shapes: `InputField.ColumnField` and `InputField.ColumnReferenceField` both add the slot. `ArgumentRef.ScalarArg.ColumnArg` already carries `extraction`; nothing to add there.
- Single-column output carriers gain a `compaction: CallSiteCompaction` slot, symmetric to the input-side `extraction` slot: `Direct()` for plain SELECT-term projection (today's behaviour), `NodeIdEncodeKeys(MethodRef encodeMethod)` for arity-1 NodeId-encoded projections. `ChildField.ColumnField` and `ChildField.ColumnReferenceField` both add the slot.
- New variants for arity > 1: `InputField.CompositeColumnField`, `InputField.CompositeColumnReferenceField`, `ChildField.CompositeColumnField`, `ChildField.CompositeColumnReferenceField`, `ArgumentRef.ScalarArg.CompositeColumnArg`. Each carries `columns: List<ColumnRef>` (size ≥ 2; arity-1 routes to the single-column siblings) plus the side-appropriate boundary slot — `extraction: CallSiteExtraction` for the input-side and arg variants. The output-side composite carriers narrow their slot to `compaction: CallSiteCompaction.NodeIdEncodeKeys` directly (per *Narrow component types over broad interfaces*) — there is no plain composite-column projection, and typing the slot at the sealed root would push that classification certainty out of the type system and into a validator coverage rule. The single-column output carriers keep the broad `compaction: CallSiteCompaction` because both arms genuinely occur there. `joinPath` is preserved on the `CompositeColumnReferenceField` arms.
- Helper-method references on the boundary-translation arms are typed: `CallSiteCompaction.NodeIdEncodeKeys` carries `HelperRef encodeMethod`, and `CallSiteExtraction.NodeIdDecodeKeys.*` carries `HelperRef decodeMethod`. `HelperRef` is a new sibling of `MethodRef` (also under `no.sikt.graphitron.rewrite.model`) shaped for stateless generated helpers: it carries the encoder's binary class name, the per-type method name (`encode<TypeName>` / `decode<TypeName>`), the helper's return type, and a `params: List<ColumnRef>` typed directly off the target Node's `keyColumns` — no `MethodRef.Param` / `ParamSource` indirection, because there is no developer-authored ParamSource story for these helpers. The narrower type also keeps the door open for the future "pluggable ID encode/decode" surface without retrofitting `MethodRef`'s `ParamSource` axis. Carrying the full reference rather than just a method-name string means consumers (projection emitter, classifier, validator) reach the helper by structurally typed reference rather than re-resolving a string against the encoder class.
- `ChildField.ColumnField.columnName: String` and `ChildField.ColumnReferenceField.columnName: String` retire in the same change. The slot is redundant with `column.javaName()` / the resolved `ColumnRef`'s SQL name accessors, and "two strings naming the same column" has been a long-standing footgun. `ChildField.PropertyField` and `ChildField.RecordField` keep their `columnName` slots — their `column: ColumnRef` is nullable for non-table-backed parents, so `columnName` is the only carrier of the SDL string in those cases. The nullable-`column` shape on `PropertyField` / `RecordField` is itself a design smell (a single record straddling two parent kinds via an Optional component) and gets its own Backlog item, separate from R50's targeted retirement.
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

- `InputField.NodeIdField` → arity-1: `InputField.ColumnField` with `extraction = NodeIdDecodeKeys.NullOnMismatch(decodeMethod)`, body is `ColumnPredicate.Eq`. Arity > 1: `InputField.CompositeColumnField` with the same extraction, body is `ColumnPredicate.RowEq`.
- `InputField.NodeIdReferenceField` → arity-1: `InputField.ColumnReferenceField` (FK joinPath retained) with `NodeIdDecodeKeys.NullOnMismatch`, body `ColumnPredicate.Eq`. Arity > 1: `InputField.CompositeColumnReferenceField`, body `ColumnPredicate.RowEq`.
- `InputField.NodeIdInFilterField` → arity-1: `InputField.ColumnField` with `NodeIdDecodeKeys.SkipMismatchedElement`, body `ColumnPredicate.In`. Arity > 1: `InputField.CompositeColumnField`, body `ColumnPredicate.RowIn`.
- `InputField.IdReferenceField` → arity-1: `InputField.ColumnReferenceField` over the FK column with `NodeIdDecodeKeys.SkipMismatchedElement`, body `ColumnPredicate.Eq` (single FK) or `In` (single FK list arg). Arity > 1: `InputField.CompositeColumnReferenceField`, body `ColumnPredicate.RowIn`. Subsumes R20.
- `BodyParam.NodeIdIn` → arity-1: `BodyParam.ColumnPredicate.In`. Arity > 1: `BodyParam.ColumnPredicate.RowIn`. `NodeIdEncoder.hasIds` deletes.
- `LookupMapping.NodeIdMapping` → retires outright. `LookupMapping.ColumnMapping` restructures around an arg layer with sealed `LookupArg` arms (see "Lookup arg restructure" below); the NodeId lookup case folds onto `ScalarLookupArg` (single-key target) or `CompositeLookupArg` (composite-key target). `buildNodeIdFetcherBody` deletes; the lookup case shares the same VALUES + JOIN body the rest of the lookup paths use.
- `ArgumentRef.ScalarArg.NodeIdArg` → arity-1: `ScalarArg.ColumnArg` with `NodeIdDecodeKeys.NullOnMismatch` for `Query.node(id:)` / `Query.nodes(ids:)` shapes, `ThrowOnMismatch` for lookup/mutation key shapes. Arity > 1: `ScalarArg.CompositeColumnArg` with the corresponding failure-mode arm.

*Output side* (encode at projection; arity-1 wraps via the carrier's `compaction` slot, arity > 1 routes to `Composite*`):

- `ChildField.NodeIdField` → arity-1: `ChildField.ColumnField` with `compaction = NodeIdEncodeKeys(encodeMethod)`. Arity > 1: `ChildField.CompositeColumnField` with the same compaction arm.
- `ChildField.NodeIdReferenceField` → arity-1: `ChildField.ColumnReferenceField` (FK joinPath retained) with `compaction = NodeIdEncodeKeys(encodeMethod)`. Arity > 1: `ChildField.CompositeColumnReferenceField` with the same compaction arm.

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
            SlotSource source,
            ColumnRef targetColumn,
            CallSiteExtraction extraction
        ) {}

        sealed interface SlotSource permits MapField, RecordSlot {}
        record MapField(String fieldName) implements SlotSource {}    // Map case: ((Map) raw).get(fieldName)
        record RecordSlot(int index)      implements SlotSource {}    // Record<N> case: record.get(index)
    }
}
```

Folds the four shapes:

- Non-NodeId single-arg lookup → `ScalarLookupArg(name, col, Direct, list)`.
- NodeId single-key lookup → `ScalarLookupArg(name, col, NodeIdDecodeKeys.ThrowOnMismatch(decodeMethod), list)`.
- Composite-key input type → `CompositeLookupArg(name, list, Direct, [Binding(MapField("fieldName"), col, Direct), …])`.
- Composite-PK NodeId → `CompositeLookupArg(name, list, NodeIdDecodeKeys.ThrowOnMismatch(decodeMethod), [Binding(RecordSlot(0), col1, Direct), …])`.

`SourcePath` and the existing `LookupColumn` record retire. `LookupValuesJoinEmitter`'s `rootSources()` grouping logic and the per-column `columnValueExpr` ladder both retire — the emitter switches at the arg layer on `LookupArg`'s sealed arms, then per binding on `Binding.source`'s `SlotSource` arm to emit Map-keyed vs Record-keyed slot access. `argExtraction`'s job stays narrowly the wire→typed translation applied once to the raw arg value (e.g., decode a `String` NodeId into a `Record<N>`); it is no longer a discriminator the emitter reads to pick slot-access shape. Multiple args still combine into one VALUES row by sitting as distinct entries in `args`; the existing `Row<N+1>` arity cap covers the sum of slots across args.

Validator rule: bindings within one `CompositeLookupArg` are homogeneous on `SlotSource` arm (all `MapField` or all `RecordSlot`), and that arm correlates with `argExtraction`'s arm (Map source ↔ `Direct`; Record source ↔ `NodeIdDecodeKeys.ThrowOnMismatch`). The classifier produces both consistently; the validator catches drift if a future classifier change relaxes one without the other.

*Query.nodes folds onto the lookup pipeline.* `Query.nodes(ids:)` is a lookup wearing query-field syntax: input is a set of decoded NodeId tuples, output is one row per input id, ordered by input position. The dispatcher's existing idx-driven scatter (which the encoder-API section notes replaced `canonicalize`) is the lookup pattern manifest in Java; after R50's `LookupArg` restructure, per-typeId batches emit through `LookupValuesJoinEmitter` rather than `QueryNodeFetcherClassGenerator`'s bespoke `WHERE row-IN` flow.

- Cross-typeId fanout dispatcher stays in `QueryNodeFetcherClassGenerator`: it peeks each id's typeId via `peekTypeId`, decodes via `decode<TypeName>`, and groups decoded tuples by typeId.
- Per-typeId batch routes through `LookupValuesJoinEmitter` with a `LookupMapping.ColumnMapping` carrying one `ScalarLookupArg` (single-key target) or `CompositeLookupArg` (composite-key target). The batch has already decoded; the emitter receives typed key tuples directly, with a pass-through `argExtraction`. VALUES rows carry `idx` + decoded keys; JOIN to the target table; ORDER BY `idx`.
- Cross-typeId merge scatters per-typeId results back to original input positions by global `idx`.

The rowsNodes lambda's bespoke `WHERE row-IN` body deletes; the dispatcher's idx-driven scatter retains its role for cross-typeId merging, but the per-typeId emission is the same VALUES + JOIN pipeline `@lookupKey` and post-R50 `@nodeId` lookups use. `Query.node(id:)` (arity-1, single result) skips the VALUES form and degenerates to a single WHERE on decoded keys; ordering is moot for one row.

*Single-hop emission, two shapes.* Encode/filter always reads off a root table; the shapes differ in *which* table serves as that root. In canonical FK terminology the *child* table holds the FK; the *parent* table is the referenced table whose `keyColumns` we need to encode (or filter on). For a `NodeIdReferenceField`, the schema-side parent type's backing table is the FK's *child*, and the referenced NodeType's table is the FK's *parent*.

- *Rooted at child (no JOIN).* The child's FK source columns positionally equal the parent's `keyColumns`, so the child table can serve as root for the encode (its FK columns *are* the keys). Encode reads `child.fkCol1, ..., child.fkColN` directly; the input-side predicate filters on the same child columns. This is the no-JOIN shortcut today's emitters already use; it stays.
- *Rooted at parent (single-hop JOIN).* The child's FK source columns differ from the parent's `keyColumns` (e.g. the FK references the parent through a non-PK unique constraint, or the parent's NodeId uses columns the FK doesn't reach), so the parent table is brought into scope via JOIN and serves as root. Threaded through the carrier's `joinPath`. On the output side, the projection emitter resolves the parent alias from `joinPath` and calls `encode<TypeName>(parent_alias.k1, ..., parent_alias.kN)` against the joined parent's columns. On the input side, the row predicate emits against `parent_alias.k1, ..., parent_alias.kN` (matching the decoded key tuples) rather than against the child's FK columns.

The same-table `@nodeId` case (e.g. a Node type's own `id` field) collapses naturally into the rooted-at-parent frame: parent and the SELECT's existing FROM coincide, so no JOIN is needed to make the parent the root. Multi-hop and condition-join cases stay in R24 as correlated-subquery emission — same principle, third scope mechanism (rooted at parent via correlated subquery); R50's single-hop coverage stops at the FK boundary.

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

Consumer call-site rewrites that drop out: `FetcherEmitter`'s `ChildField.NodeIdField` / `NodeIdReferenceField` projection lambdas (today `NodeIdEncoder.encode("Film", r.get(...), ...)`) become `NodeIdEncoder.encodeFilm(r.get(...), ...)` via the carrier's `compaction.encodeMethod` `HelperRef`; `TypeFetcherGenerator`'s rowsNodes lambda rewrites the same way; `LookupValuesJoinEmitter.buildNodeIdFetcherBody` deletes wholesale; `Query.node(id:)` decodes via `decode<TypeName>` and emits a single WHERE on the decoded keys; `Query.nodes(ids:)` decodes per typeId, fans out, and routes per-typeId batches through `LookupValuesJoinEmitter` rather than the bespoke `WHERE row-IN` flow (see *Variant-by-variant collapse → Query.nodes folds onto the lookup pipeline*). The mutation DML emitter's encode call (today `NodeIdEncoder.encode("typeId", v1, ..., vN)` from `MutationField.DmlTableField.nodeIdMeta`) rewrites to `NodeIdEncoder.encode<TypeName>(v1, ..., vN)` via the `NodeType.encodeMethod` `HelperRef` in the same change — required because the generic `encode` becomes private; what stays out of scope is unifying the *dispatch* with the child-field side (see "What we're NOT doing"). The string typeId only appears at the schema-classifier boundary (where it is read once from the SDL `@node` directive) and inside `NodeIdEncoderClassGenerator` itself; runtime call sites resolve the helper through structurally typed `HelperRef` references.

## Validator + dispatch coverage

Every retired variant comes off `TypeFetcherGenerator.NOT_DISPATCHED_LEAVES` and `GraphitronSchemaValidator`'s no-op arms. The new sealed arms get matching coverage in the validator's exhaustiveness tests:

- `CallSiteExtraction.NodeIdDecodeKeys.NullOnMismatch`, `.SkipMismatchedElement`, `.ThrowOnMismatch` — one validator-coverage arm each, mirroring the per-variant pattern the existing `CallSiteExtraction` arms (`Direct`, `EnumValueOf`, `TextMapLookup`, `ContextArg`, `JooqConvert`) already follow.
- `CallSiteCompaction.Direct`, `CallSiteCompaction.NodeIdEncodeKeys` — one validator-coverage arm each on the new sealed root.
- `InputField.CompositeColumnField`, `InputField.CompositeColumnReferenceField`, `ChildField.CompositeColumnField`, `ChildField.CompositeColumnReferenceField`, `ArgumentRef.ScalarArg.CompositeColumnArg` — one validator-coverage arm each on the corresponding sealed roots.
- `LookupMapping.ColumnMapping.LookupArg`'s two arms (`ScalarLookupArg`, `CompositeLookupArg`) — one validator-coverage arm each. The retired `LookupMapping.NodeIdMapping` arm comes off.
- `CompositeLookupArg.SlotSource`'s two arms (`MapField`, `RecordSlot`) — one validator-coverage arm each, mirroring how the existing `CallSiteExtraction` arms are covered.
- `BodyParam.ColumnPredicate.Eq`, `.In`, `.RowEq`, `.RowIn` — one validator-coverage arm each on the new predicate sub-taxonomy. The retired `ColumnEq` arm and its `boolean list` invariant come off.
- Carrier-arity invariant: `Composite*` variants must carry `columns.size() ≥ 2` (arity-1 routes to the single-column siblings); upper bound is the `Record<N>` / `Row<N+1>` 22-slot ceiling that already governs lookup VALUES rows. Validator-tier rule fails the build if a classifier path produces a `Composite*` carrier with `columns.size() < 2` (the routing rule would have picked the single-column sibling), or any `columns.size() > 22` (architectural cap). The lower-bound rule guards against future regressions; arity-zero is structurally meaningless.
- Compaction-carrier invariant: composite output carriers narrow `compaction` to `CallSiteCompaction.NodeIdEncodeKeys` at the type system level, so no validator rule is required for that side. For single-column output carriers, `compaction = NodeIdEncodeKeys` is permitted only when the GraphQL type is `ID` and the parent table participates in a `NodeType` whose `keyColumns.size() == 1`; the classifier picks the arm and the validator enforces this single-column condition.
- `CompositeLookupArg` homogeneity invariant (see *Lookup arg restructure*): all bindings within one `CompositeLookupArg` share a `SlotSource` arm, and that arm correlates with `argExtraction`'s arm.

Per *Validator mirrors classifier invariants*, every classifier decision that picks a sealed arm here implies a generator branch; the validator's coverage tests enforce that each arm has a dispatch-side implementation, so adding a future arm without an emitter fails at validate time rather than runtime.

Load-bearing classifier guarantees the new emitter shapes rely on (per *Classifier guarantees shape emitter assumptions*):

- `@LoadBearingClassifierCheck(key = "nodeid.decode.failure-mode")` on the classifier site that picks the `NodeIdDecodeKeys` arm; `@DependsOnClassifierCheck(key = "nodeid.decode.failure-mode", reliesOn = "...")` on each emitter site that switches on it. Relaxing the classifier's per-call-site selection breaks emitted failure handling.
- `@LoadBearingClassifierCheck(key = "columnpredicate.column-arity")` on the classifier site that picks `Eq` / `In` vs `RowEq` / `RowIn` (and the parallel single-column-carrier vs `Composite*`-carrier routing decision); `@DependsOnClassifierCheck` on the body emitter and on every projection / call-site emitter that consumes the carriers. The arm's column shape (single `ColumnRef` vs `List<ColumnRef>` with size ≥ 2) is the carrier the emitter relies on with no defensive size check.

## Failure-mode contract

Three call-site categories want three different responses to a `decode<TypeName>` returning `null` (malformed input or typeId mismatch). Folding the mode into a single `NodeIdDecodeKeys(decodeMethod, mode)` record would put the same predicate (`extraction.mode() == ...`) into every emitter that consumes the arm, exactly the multi-arm switch *Generation-thinking* says belongs in the model. R50 instead splits `NodeIdDecodeKeys` into three sealed arms under the existing `CallSiteExtraction` hierarchy; the classifier picks the arm at build time, the emitter switches exhaustively, and the validator gets a coverage arm per failure mode for free.

- `CallSiteExtraction.NodeIdDecodeKeys.NullOnMismatch(HelperRef decodeMethod)` — every Query field classified as a node-by-id fetcher (any Query field whose element type is the `Node` interface, per the signature-based classifier; not just literal `node` / `nodes`), plus the federated `_entities` resolver. A `null` return from `decode<TypeName>` on a single `id` resolves to `null` for the whole field; on one element of `ids`, to `null` for that element only. Parity with the existing Relay-spec behaviour `_entities` already implements.
- `CallSiteExtraction.NodeIdDecodeKeys.SkipMismatchedElement(HelperRef decodeMethod)` — `[ID!] @nodeId(typeName: T)` filter argument. A `null` return on any element short-circuits the bad element to "no row matches"; never throws. Empty list and null arg follow existing column-equality behaviour (predicate omitted when the arg is absent, `falseCondition()` when the list is present-but-empty, matching today's `NodeIdInFilterField` body).
- `CallSiteExtraction.NodeIdDecodeKeys.ThrowOnMismatch(HelperRef decodeMethod)` — `@nodeId` on a top-level scalar/list argument used as a lookup or mutation key. A `null` return is an authored-input error and surfaces as a `GraphqlErrorException`-shaped error, not silent null; a wrong-type id at a lookup key is a contract violation rather than "no match."

The shared decode primitive is the per-Node-type `decode<TypeName>` helper (see "`NodeIdEncoder` API"); each arm composes it with the appropriate response to a `null` return. Adding a future failure mode is a new sealed arm plus a validator-coverage arm plus an emitter switch arm; no shared flag to misset, no per-emitter mode-predicate to keep in sync.

## What stays

`GraphitronType.NodeType` stays. It carries `(typeId, keyColumns)`, which is type-level identity: the schema author declared this type as a Node and these are its key columns. Identity is a model concern; the wire format derived from that identity is not.

`NodeType` gains two pre-resolved `HelperRef` fields, `encodeMethod` and `decodeMethod`, constructed once by the schema builder from the encoder's binary class name (output package + `NodeIdEncoder`) and the per-type helper method names (`encode<TypeName>` / `decode<TypeName>`). They are the single source of truth for the helper references: `NodeIdEncoderClassGenerator` reads them when emitting the helper definitions; `CallSiteCompaction.NodeIdEncodeKeys` and `CallSiteExtraction.NodeIdDecodeKeys.*` carry copies via the classifier; the mutation DML emitter resolves through `NodeType.encodeMethod` (reached via `MutationField.DmlTableField.nodeIdMeta`'s target type). No emitter computes a helper class or name on its own, so no two emitters can disagree.

`MutationField.DmlTableField.nodeIdMeta: Optional<JooqCatalog.NodeIdMetadata>` (introduced upstream in mutations Phase 1, R22) stays for the same reason. `NodeIdMetadata(String typeId, List<ColumnRef> keyColumns)` is structurally the same identity data as `NodeType`, carried per-mutation so the DML emitter knows which key columns to encode for an `ID`-returning mutation. The mutation-return encode call resolves to the same per-type helper (`NodeIdEncoder.encode<TypeName>(k1, ..., kN)`), so mutation returns and child-field projections share one boundary even though they reach it via different model carriers (see "What we're NOT doing" on dispatch unification).

## What we're NOT doing

- **The wire format itself.** `@nodeId` ids stay as base64-encoded `typeId` + composite key. R50 is a model-side lift, not a protocol change; consumer schemas and clients see the same id strings.
- **Wire-format primitives in `NodeIdEncoder` beyond what "`NodeIdEncoder` API" lists.** The generic `encode` and `decodeValues` bodies become private but keep their current behaviour; `peekTypeId` keeps its current shape; the per-type `encode<TypeName>` / `decode<TypeName>` helpers are thin generated wrappers over them. If a downstream change wants to refactor the wire format itself, it is a separate item.
- **Multi-hop FK and condition-join correlated-subquery emission.** R50's column-shaped successors cover three FK shapes: rooted at child (no JOIN), rooted at parent via single-hop JOIN (JOIN-with-projection on output, JOIN + row-IN on input), and composite-PK keyed Node types. Anything past one hop, or where the FK is replaced by a condition-join, still wants a correlated-subquery emission projecting the parent's key columns under aliases — same principle, rooted at parent via correlated subquery; that machinery stays in R24's scope.
- **Consumer schema migration.** No changes required in consuming schemas; `[ID!] @nodeId(typeName: T)` and `@nodeId` on scalar / list outputs are the same surface they are today.
- **Mutation return-encode dispatch unification.** R50 rewrites the DML emitter's existing `NodeIdEncoder.encode("typeId", v1, ..., vN)` call to the per-type helper `NodeIdEncoder.encode<TypeName>(v1, ..., vN)` (required because the generic `encode` becomes private in the same change; see "`NodeIdEncoder` API → Consumer call-site rewrites"). What stays out of scope is unifying the *model dispatch*: the DML emitter still reaches the encode helper via `MutationField.DmlTableField.nodeIdMeta` rather than through a child-field-style carrier slot, so child-field projections and mutation returns share the same generated boundary call but reach it through different model dispatches. Aligning those dispatches is principled but a separate change; a future item can lift mutation returns onto a shared hook once the `ChildField` side has settled.

## Fixture growth

R50 adds two fixture shapes to the existing `nodeidfixture` to exercise the composite-key paths the lift unlocks:

- **Composite-PK Node.** A table with a two-column PK plus `__NODE_TYPE_ID`, exposed as a NodeType. Drives the row-IN body emission for `[ID!] @nodeId` filters and the row-projection encode path on output.
- **Rooted-at-parent single-hop reference.** A reference whose FK columns do not positionally match the parent NodeType's `keyColumns`, but where the parent table is reachable in one hop. Drives `NodeIdReferenceField` collapse for the rooted-at-parent case without pulling in correlated-subquery emission.

Multi-hop FK fixture growth is explicitly deferred to R24 (see "What we're NOT doing").

## Coupling

- **R40 (argument-level `@nodeId` support)** depends on R50. R40 is shaped as a small classifier-only follow-on once R50's foundation is in place: extend `FieldBuilder.classifyArgument` to read `@nodeId(typeName: T)` on an argument, validate same-table, build a column-shaped argument with `NodeIdDecodeKeys`. R40 does not land any new model variants and adds nothing to the wire-shape debt.
- **R20 (`IdReferenceField` code generation)** had a Spec on the legacy variant; that Spec is invalidated by R50's framing because R20's emission shape dissolves into R50 (standard FK-equality or row-IN through a column-shaped variant, not a `has<Qualifier>` method call). R20 is now a tombstone in Backlog; its execution-tier coverage is folded into R50's "Test surface" and the R20 file deletes when R50 reaches Done.
- **R24 (`NodeIdReferenceField` JOIN-projection form)** shrinks rather than dissolving. R50 absorbs the rooted-at-parent single-hop case as a JOIN-with-projection through the column-shaped successor (see "Variant-by-variant collapse → Single-hop emission, two shapes"), so R24's scope contracts to multi-hop and condition-join correlated-subquery emission. R24 retains its file and stays in Backlog; re-spec it once a real schema needs the multi-hop shape.
- **`columnName` redundancy cleanup (in scope).** `ChildField.ColumnField.columnName` and `ChildField.ColumnReferenceField.columnName` retire in the same change as the carrier-slot additions (`compaction`); see "Composite-key column carriers". `ChildField.PropertyField` and `ChildField.RecordField` keep their `columnName` slots within R50 — their `column: ColumnRef` is nullable for non-table-backed parents, so `columnName` is the only carrier of the SDL string in those cases. The nullable-`column` shape is itself a design smell tracked in **R51** (split `PropertyField` / `RecordField` on parent-kind instead of carrying a nullable `ColumnRef`).

## Test surface

- **Pipeline-tier.** Every retired variant's existing classification cases re-pointed at the appropriate successor — single-column carriers for arity-1, `Composite*` for arity > 1: `@nodeId` scalar output, `@nodeId` reference (rooted at child and rooted at parent), `@nodeId` filter on `[ID!]` arg, `@nodeId` argument on a top-level `[ID!]` arg, federated `_entities` resolver, `Query.node(id:)` and `Query.nodes(ids:)`. The retired variant classes are deleted in the same change, so "no instance survives" is enforced by the compiler rather than asserted. Any unit-tier structural tests scoped to a retired variant (e.g. tests asserting on `NodeIdInFilterField`'s record shape) delete with the variant. `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus` and `VariantCoverageTest.everySealedLeafHasAClassificationCase` keep their existing semantics (the four-set partition shrinks; the new column-shaped successors fall under existing IMPLEMENTED / PROJECTED status). Exhaustiveness for the new emitter-side sealed roots (`CallSiteExtraction.NodeIdDecodeKeys.*`, `CallSiteCompaction.*`, `BodyParam.ColumnPredicate.*`, `LookupArg.*`, `CompositeLookupArg.SlotSource.*`) and the new `Composite*` carrier variants lives in the validator-tier coverage; see the next bullet.
- **Validator-tier.** `GraphitronSchemaValidator`'s coverage tests gain one arm per new sealed variant (per "Validator + dispatch coverage": three `NodeIdDecodeKeys` arms, two `CallSiteCompaction` arms, four `ColumnPredicate` arms, two `LookupArg` arms, two `SlotSource` arms, five `Composite*` carrier arms) and lose arms for the retired model variants. `TypeFetcherGenerator.NOT_DISPATCHED_LEAVES` shrinks; the dispatch-coverage test asserts the retired leaves are gone.
- **Compilation-tier.** `mvn compile -pl :graphitron-test -Plocal-db` against the `nodeidfixture` and `idreffixture` catalogs after the collapse, including the composite-PK Node and rooted-at-parent single-hop fixtures spelled out in "Fixture growth". `NodeIdEncoder.hasIds` is deleted from the encoder, so any emitted reference fails compilation here, no string scan needed.
- **Execution-tier.** Every existing `@nodeId` execution test continues to round-trip end-to-end: `Query.node(id:)`, `Query.nodes(ids:)`, federated `_entities`, same-table `[ID!] @nodeId` filter, rooted-at-child reference, and the new composite-PK / rooted-at-parent cases. SQL inspection via `ExecuteListener` confirms emitted bodies use `c.eq(...)` / `c.in(...)` for `ColumnPredicate.Eq` / `In` and `DSL.row(c1, ..., cN).eq(...)` / `.in(...)` for `RowEq` / `RowIn`, with decoded key tuples flowing as bind values rather than encoded `String` ids. `Query.nodes(ids:)` SQL is asserted to be the lookup shape (`VALUES + JOIN + ORDER BY idx`) rather than the legacy `WHERE row-IN`, catching regressions where the dispatcher falls back to the old flow. Failure-mode parity with "Failure-mode contract": one typeId-mismatch test per sealed `NodeIdDecodeKeys` arm — `NullOnMismatch` against `Query.node(id:)`, `SkipMismatchedElement` against `[ID!] @nodeId` filter, `ThrowOnMismatch` against a lookup-key argument.
