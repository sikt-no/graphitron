---
id: R222
title: "Dimensional model pivot: slots over cross-product permits"
status: Spec
bucket: structural
priority: 3
theme: classification-model
depends-on: []
created: 2026-05-21
last-updated: 2026-07-04
---

# Dimensional model pivot: slots over cross-product permits

R222 is the umbrella for the rewrite's dimensional pivot. Three sealed hierarchies pack multi-dimensional information onto single permit sets — input-side classification (`GraphitronType.InputType` + `TableInputType`), field-side classification (`QueryField` / `MutationField` / `ChildField` with 46 cross-product permits), and classification-failure encoding (`UnclassifiedType` / `UnclassifiedField` riding as permits alongside legitimate carriers). The same disease in three organs. R222 absorbs R164 (field-model three-dimension pivot) and R226 (type-level classification failure pivot) and unifies them as one architectural shift, landing the target architecture stage-by-stage through independent spin-out slices.

## Direction, not contract

**Governance (2026-07-04): R333 (`coordinate-lowers-to-datafetcher-queryparts`) is the current
statement of the model.** This umbrella keeps the stage-tracking role and slices keep filing under its
stages, but where its sketches lag R333 — notably the Stage 3 destination sketch and the carrier
table, several of whose planned carriers R333 redistributes onto coordinate facts (generated
conditions become operation rows minted from input coordinates; ordering is `operation: orderBy`
payload; the lookup partition is the `Lookup` operation) — R333 governs. This file is being aligned
incrementally rather than rewritten wholesale.

The model sketched in this umbrella is the *target direction* — where the rewrite is heading. Specific slot names, carrier shapes, the boundary between walker carriers and dimensional slots, and the vocabulary itself are expected to shift as implementation slices land and surface new understanding. Each spin-out slice gets its own spec item where the specifics for that scope get pinned; the slice is free to redraw the diagram so long as it doesn't break the load-bearing claim (cross-product permits dissolve into dimensional slots; producers read graphql-java primitives directly; validity rides on the wrapper). What's stable is the *shape*: slots on a single unified field type, one per consumer concern, populated by thin layers over the SDL substrate. Read the sketches below as illustrative of that shape, not as a frozen contract.

## What is

Three cross-product encodings, three sets of permit-identity-driven discriminations.

**Input-side classification.** `GraphitronType.InputType` permits four backing-class variants (`JavaRecordInputType`, `PojoInputType`, `JooqRecordInputType`, `JooqTableRecordInputType`); `GraphitronType.TableInputType` is a separate sibling root for table-bound inputs. Nine consumer sites discriminate by permit identity: `GraphitronSchemaValidator`, `MutationInputResolver`, `EnumMappingResolver`, `CatalogBuilder` (four sites), `FieldBuilder`, `TypeBuilder`. `TypeBuilder.findReturnTablesForInput` already proves "table-bound" is a property of the consumer, not the input — derived by O(N) back-scan over schema fields. R215's lift admitted `InputField.UnboundField` into `TableInputType.inputFields()`, collapsing the eager-classification axis ahead of this pivot.

**Field-side classification.** 45 permits across `QueryField` (10), `MutationField` (8), `ChildField` (27). `TypeFetcherGenerator` dispatches per-leaf with one arm per permit. A mixin-interface overlay (`BatchKeyField`, `SqlGeneratingField`, `MethodBackedField`, `LookupField`, `TableTargetField`) carries cross-cutting traits. Each permit name packs several decisions: where source comes from (root, parent-keyed, list-parent), what the fetcher does (no I/O, `@service` invocation, generated jOOQ), the field's output shape (single, list, connection), the jOOQ contribution (none, inlined column, own SELECT, UNION ALL, DML), modifiers (lookup mapping, error channel, splitQuery). `RecordLookupTableField` collapses four of these onto one identifier; `QueryServiceRecordField` collapses three. The cross product is the permit set; adding a value to any axis multiplies the permits below it.

**Classification-failure encoding.** `GraphitronType.UnclassifiedType` and `GraphitronField.UnclassifiedField` ride as permits alongside legitimate types and fields, carrying typed `Rejection` payloads. `GraphitronSchemaValidator.validateUnclassifiedType` / `validateUnclassifiedField` translate-then-project — the validator does a half-job (walk Unclassified carriers; project payloads to ValidationError) on top of its real job (cross-type invariants).

`GraphitronField`'s sealed parent (`permits OutputField, InputField, GraphitronField.UnclassifiedField`) carries the input/output split, and `OutputField` carries a further sub-seal (`permits RootField, ChildField`). All three rationales — the cross-product permits, the input/output sibling split, the failure encoding — dissolve in this umbrella.

## What's to be: dimensional slots, walker-driven, failure at the wrapper

Three changes, all instances of the same principle.

**Surface axes as dimensional slots, not as permits.** Each consumer concern lives in its own slot on the field; the field permits flatten to one record per emit-relevant identity (or stay sub-sealed only where authoring scope justifies it). Consumers read the slot they care about. Impossible combinations are excluded at production time, not by permit cross-product. Adding a new axis is additive: new slot, new family, new production logic.

**Producers read graphql-java primitives directly.** The walker abstraction (`Walker<S, C>`) — a pure function over an SDL substrate `S` returning a sealed `WalkerResult<C>` — is *one* implementation shape; slices may pick another. The load-bearing claim is that producers are thin layers over `GraphQLFieldDefinition`, `GraphQLArgument`, `GraphQLInputObjectField` — no graphitron-internal substrate model intermediating between the SDL and the carrier. The unit-testability claim (parse a fragment, run the producer, assert on the sealed result) falls out of this shape; the test of correctness is the slot's shape, not the producer's.

**Validity rides on the wrapper, not the carrier.** Every classification step returns a sealed `Ok(carrier, diagnostics) | Err(errors, diagnostics)`. Carriers have only "happy" arms — valid or the explicit absent arm (`No<Family>`). Structural failure surfaces through `Err`; the orchestrator collects errors across the whole pass and blocks downstream generation. Classification runs to completion regardless; the LSP consumes the partial classification output independent of whether generation ran.

### Destination sketch

`GraphitronField` becomes a single field namespace (the renamed `OutputField` after the input/output split dissolves and `UnclassifiedField` retires). Each carrier slot lives on the narrowest existing interface that names its property, not as a universal accessor on `GraphitronField`. The walker is universal across that interface's implementers; slot presence is interface-gated, and consumers reading the slot through the interface always get a populated value. R238 (the foundation slice) pins this for the service `MethodCall` family: `ServiceMethodCall` (sealed `Static` / `Instance`) lands on a fresh `ServiceField` interface that sits sibling to `MethodBackedField`, not as a sub-interface of it. The earlier umbrella draft anticipated one unified `MethodCall` carrier on `MethodBackedField`, with per-directive markers as pure sub-interfaces; R238 surfaced that the call shapes across `@service` / `@condition` / `@tableMethod` / `@externalField` differ enough (ctor vs static, multiple-DSLContext rules, return-type relationships) that one unified carrier would carry a kitchen-sink of optional fields. Per-directive sibling interfaces let each slice ship a tight carrier scoped to its call shape; `MethodBackedField` retires only once every per-directive sibling has landed.

Subsequent slices for `Pagination`, `Ordering`, `PredicateCarrier`, `ValidationShape`, `InsertRows`, `UpdateRows` follow the same pattern: find (or introduce) the narrow interface that names the property, put the slot there, and add a marker sub-interface if a consumer subset needs polymorphic dispatch. Interface names land per slice:

| Carrier | Slot home | Status |
|---|---|---|
| `ServiceMethodCall` | `ServiceField` (new sibling of `MethodBackedField`) | R238 (Spec) |
| `ConditionCall` / `TableMethodCall` / `ExternalFieldCall` | per-directive siblings | future slices; collectively retire `MethodBackedField` |
| `ValidationShape` | TBD (narrow interface or existing marker) | future slice |
| `Pagination` | TBD | future slice |
| `Ordering` | TBD | future slice |
| `PredicateCarrier` | TBD | future slice |
| `InsertRows` | TBD | future slice (R122 partner) |
| `UpdateRows` | TBD | future slice |

A two-layer composition still holds where dimensional slots add real composition over multiple walker carriers: a `QueryBuilder` for an UPDATE field composes `predicate()` (WHERE), `updateRows()` (SET), and the field's return-type table; a `DataFetcherBuilder.Service` composes `serviceMethodCall()` and a class accessor; a `ValidationBuilder` composes `validation()`. Where a consumer's needs are simpler than full composition, a *shared emitter* parameterised on the carrier itself is the lighter tool: R238 introduces `ServiceMethodCallEmitter(ServiceMethodCall) -> List<CodeBlock>`. Not every carrier needs a dimensional slot; choose per consumer's need. Consumers attach at whichever layer (carrier, shared emitter, or dimensional slot) matches their concern; the layers compose without re-walking SDL behind them.

Within each sub-seal, R164's permit consolidation collapses the cross-product permits to one record per emit-relevant identity. `RootField` as the intermediate between `OutputField` and `QueryField` / `MutationField` retires alongside the parent collapse.

### The unified diagnostic surface

`Diagnostic` is an LSP-aligned sealed family — `severity` (`Error` / `Warning` / `Information` / `Hint`, mirroring LSP `DiagnosticSeverity`), `code` (stable string id), `source` (`"graphitron"`), `message`, `tags` (`Unnecessary`, `Deprecated`), `relatedInformation`. Arms keep type-safe pattern matching on the producer side; the LSP wire-format adapter reads the LSP fields and projects mechanically. `AuthorError` (the existing `Rejection.AuthorError` sealed family) carries on `WalkerResult.Err.errors`; the wire-format adapter projects each leaf to severity=Error LSP `Diagnostic` records with a code derived per leaf type.

`ValidationReport` carries `errors: List<ValidationError>` and `warnings: List<BuildWarning>` today; a later slice adds a `walkerDiagnostics: List<Diagnostic>` slot alongside them (the foundation slice, R238, shipped `WalkerResult` and the `Diagnostic` record but not this slot — as of 2026-07-04 it is still to come, and the shipped `Diagnostic` does not yet carry `tags`), and once every producer migrates the three slots collapse into one diagnostic stream. From that slice forward, walker output reaches the editor through the same channel today's validator output does — `Workspace.setBuildOutput(BuildArtifacts, ValidationReport)` is the seam, the rest of the wire (`recalculateListener` → `Diagnostics.compute` → `LanguageClient.publishDiagnostics`) is already live, and `Diagnostics.validatorDiagnostics` gains an arm projecting the walker `Diagnostic` family.

### Single field namespace, no failure permit

After the pivot, `GraphitronField`'s sealed parent and the `UnclassifiedField` permit are both gone:

- `InputField` (and its sub-permits) retires as input-side carriers move to slots on the unified field.
- `UnclassifiedField` retires as classification failure moves to `WalkerResult.Err`.
- `OutputField` and `RootField` retire as redundant intermediate sub-seals — there is no "Input" half left to contrast with, and the `RootField` between `OutputField` and `QueryField` / `MutationField` carries nothing distinctive.
- The surviving permits live directly under `GraphitronField`: `QueryField`, `MutationField`, `ChildField` (the latter renamed `SourceField`; see the refined field-side model below).

## Field-side dimensional model (pivoted 2026-06-18 to source/operation/target)

The field-side pivot (Stage 3, R164's content) first materialised as `carrier × intent × mapping`
(R290 / R299 / R305). R316 (`source-operation-target-pivot`) corrects that model to
**`(source, operation, target)`**: `mapping` was not a dimension but a per-endpoint *polarity*, and the
first two axes were under-named. A field is an **edge**: it **arrives into** a `source`, **performs** an
`operation`, and **projects** a `target`. `source` and `target` are each a *wrapper around a shape* (the
wrapper a multiplicity layer, the shape the named thing inside); `operation` is a sealed interface of
payload-carrying verbs. The producer dimension still dissolves entirely (see below); what changed is the
naming of the three surviving axes and the recognition that the two endpoints share one form. The full
argument is R316 and its backing audit; this section records the model the umbrella now speaks. R333
subsequently normalized the triple into the coordinate-and-its-facts schema (the triple is a
per-coordinate summary row over independent facts; `operation` is a 0..N set) — per the governance
note above, read this section through R333 where they differ.

**`source`** — the arrival endpoint: a wrapper around a `SourceShape`, the wrapper being the field's
**arrival cardinality** (how many source objects reach its fetcher).

```
source      = Root | OnlyChild(SourceShape) | Child(SourceShape)
Root        = Query | Mutation
SourceShape = Table | Record | Interface(Table | Record)
```

- `Root` (permits `Query` / `Mutation`): an operation root, no source object arrives (arrival `Zero`).
  The `Query` / `Mutation` split *is* the legality gate the old `carrier` implied (writes only on
  `Mutation`, `NodeResolve` only on `Query`), folded into the arm identity.
- `OnlyChild(SourceShape)`: exactly one source object arrives (arrival `One`), direct SQL.
- `Child(SourceShape)`: many source objects arrive (arrival `Many`), DataLoader-batched.

**The arrival wrapper is the emit-strategy dispatch:** `Child` needs a DataLoader or it is an N+1;
`Root` / `OnlyChild` run SQL directly, single invocation. Naming the arms for the arrival (not a bare
`One` / `Many`) keeps the count from being misread as the field's *output* arity, since the same
`{One, Many}` values sit on the target wrapper (see [the wrapper algebra](#the-wrapper-algebra)).
`SourceShape` carries the catalog-vs-Java polarity of the parent and is a **subset of `TargetShape`** (a
source object is always a row, never a scalar): `Table` (a catalog row parent), `Record` (a
producer-handed domain record: an `@service` / DML payload, a DTO parent, or a jOOQ embeddable column read
as a record), and `Interface(Table | Record)` (a polymorphic parent; the `Record` case is a `@service`
returning an interface). This replaces the old `Carrier.Source`'s flat `source-shape` +
`source-object cardinality` payload: the cardinality became the wrapper arm, the shape stayed inside it,
and R305's "sealed makes the payload unrepresentable on `Query` / `Mutation`" survives as `Root` carrying
no `SourceShape`.

**`operation`** — the verb: a **sealed interface `Operation` with `record` arms** (replacing the flat
`enum Intent`, which could not hold per-arm payloads). Each arm carries the slots its kind needs:

- read: `Fetch` (catalog read returning rows; carries `List<WhereFilter>` + ordering), `Paginate`
  (windowed read producing a connection; carries the filter surface + ordering + the pagination window +
  `pageInfo` synthesis), `Lookup` (`@lookupKey` correspondence; carries `LookupMapping`), `Count` /
  `Facet` (the connection-operation siblings of `Paginate`), `Nest` (a zero-component regroup).
- service: `ServiceCall` (a `@service` invocation; carries the `MethodRef` + params, arguments binding to
  method **parameters**). This **collapses** the old `QueryService` / `MutationService`, which differed
  only by the read/write legality gate now carried by `Root`'s `Query` / `Mutation` split.
- write: `Insert` / `Update` / `Upsert` / `Delete`, each carrying its DML payload.

`Paginate` is the windowed-read arm the old fused `Mapping.TableConnection` mis-filed on the target axis;
pagination lives on the operation, joining `Count` / `Facet` (the previously modeled-but-unpopulated
connection roles). The framework resolvers `NodeResolve` / `EntityResolve` stay protocol-specific
operation arms (Relay `node` / `nodes`, Federation `_entities`); the old `UpdateMatching` /
`DeleteMatching` condition-matched writes remain modeled gaps. Arms carry payload concretely (the payload
*is* the model); R314 owns the emit's *consumption* of it.

**`target`** — the projection endpoint: a wrapper around a `TargetShape`, the wrapper being the field's
**own output cardinality** (read off `field.getType()`).

```
target      = Single(TargetShape) | List(TargetShape)
TargetShape = Table | Record | Column | Field          // base shapes
            | Connection(TargetShape)                   // container shape
            | Interface(Table | Record) | Union(Table)
```

- The **base shapes** carry the catalog-vs-Java polarity: `Table` / `Column` (catalog, graphitron
  *builds* the SQL) and `Record` / `Field` (Java, graphitron *consumes* a value it did not build).
  `Table:Column :: Record:Field` (mirror : reflect). This is exactly what the old `mapping` axis
  encoded; it is **not a dimension** but the shape's polarity, and it reappears on `source`. The polarity
  classifies graphitron's epistemic role, not the runtime SQL location: `@externalField` emits a jOOQ
  `Field<X>` that runs in the query, but graphitron only reflects its user-authored result, so it is
  domain (`Field` / `Record`), not catalog. "In the SQL ≠ catalog." `SourceShape ⊆ TargetShape`: source
  has only the row shapes; target adds the scalar shapes (`Column` / `Field`) a source can never be.
- `Connection(TargetShape)` is a `Single`-wrapped shape with its own fields, its many-ness living on
  those fields (`edges` / `nodes`), classified normally. This retires the fused `Mapping.TableConnection`;
  the "paginated" fact moves to `operation`'s `Paginate` arm.
- `Interface(Table | Record)` and `Union(Table)` are the polymorphic shapes. `Interface` straddles both
  seals (an interface declares its own fields, so its value can be a source); `Union` is target-only (a
  union declares no fields, so it is never a source) and wraps `Table` only (`Union(Record)` degrades to
  `Object` in the Java type system, which we cannot reflect on, so it is unsupported).
- A target shape's **definition can be developer-supplied** instead of catalog-derived, and that
  provenance (not a new operation) is the home for `@tableMethod` (supplies the `Table<?>` that `@table`
  would resolve, replacing the target table) and `@externalField` (a `Column<T>` whose expression is
  authored). The provenance `MethodRef` rides the target shape; the operation stays `Fetch` / projection.
- A `Column` is not always a scalar leaf: a jOOQ embeddable column carries an embedded record, so a target
  `Column` can be the **source `Record` for further child fields**. This is the shape-level reading of the
  wrapper algebra: a field's target shape becomes its children's source shape (projected to row
  granularity), which is *why* `SourceShape ⊆ TargetShape` holds.

### the wrapper algebra

The two wrappers are **one algebra at two positions**. The target wrapper is *local* (this field's own
output); the source wrapper is *accumulated* (the fold of the ancestor fields' target wrappers, so one
`List` ancestor makes every descendant `Many`). A field's target wrapper thus becomes part of its
descendants' source wrapper. The monoid is trivial and closed over the arms (`Root` the empty product,
`OnlyChild` the `One` identity, `Child` the `Many` absorber):

```
Root · x          = x          OnlyChild · x   = x
Child · OnlyChild = Child       Child · Child   = Child
```

This is *why* a bare `Cardinality.MANY` is unreadable: the same `{One, Many}` values appear at both
positions, so detached from its endpoint a cardinality value could be either. The fix is structural:
cardinality only ever exists as a wrapper bound to an endpoint, never as a standalone
`SourceCardinality` / `TargetCardinality` type. This subsumes the earlier draft's two source
cardinalities: the *source-object* arity (arrival) is the source wrapper arm, and the *source-field*
arity (today's `SourceKey.Cardinality`, rows of this field per object) is the target output wrapper. The
named invariant `sourceWrapperIsTheFoldOfAncestorTargetWrappers` keeps the two positions honest. Where a
record-backed parent exposes the field as a typed accessor, `AccessorMatch.CardinalityMismatch` still
rejects a declared arity that disagrees with the accessor's return arity.

**derived layer** (computed from the endpoints; never asserted):

- `FetchRelated` ← a non-empty **join-path** slot (a `Fetch` reaching a related entity via FK / `@reference`).
- **re-fetch** ← a record-producing endpoint (a source `Record`, or a record-producing `operation`:
  `ServiceCall` / a write) crossing into a catalog `Target.Table`. A field holding a domain record while
  projecting a catalog table forces re-projecting the table from the record's keys. Read off the two
  endpoints' polarities rather than decoded from a conflated `mapping`; this replaces the
  `mapping() != Mapping.Table` gate and still catches `SingleRecordTableField` (R305) through its received
  `Record` source.
- **new-query** ← a source-side slot, forced by `@splitQuery` / polymorphic UNION / record-handoff.
- **polarity** (mutating?) ← `Root.Mutation` plus the write operations.

### Why the producer dimension dissolves

Its information redistributes with no residual: position → `source` (the `Root` / `OnlyChild` / `Child`
arm); build-vs-consume → the shape polarity on `source` / `target`; operation → `operation`; new-query → a
derived slot. The governing principle is **assert what nothing else carries; derive what another axis or
slot already forces.** So `FetchRelated` (forced by the join-path slot), re-fetch (forced by a
record-producing endpoint meeting a catalog `Target.Table`), new-query (forced by `@splitQuery` /
limitations), and polarity (forced by `Root.Mutation` + the write operations) are all derived, not
asserted. `Query` / `Mutation` are *not* a separate provider axis because they double-encode: read-vs-write
is the operation legality gate, and root-ness is the `Root` arm.

`Nest` is an asserted operation (not derived from "empty join-path"), avoiding the absence-as-domain-state
shape this umbrella rejects elsewhere: it is a distinct structural verb (produces nothing, inherits the
parent's scope, regroups children) outside read / write / service. `ServiceCall` stays the coarse
mutate-or-not polarity only (graphitron can't infer more from opaque user code; a `Lookup`-like service
would need method-signature inference, deferred), with the read/write split now on `Root` rather than two
`*Service` intents. The writes enumerate the legal verbs; **bulk is the target wrapper** (`List`), not an
operation. The two source-side cardinalities the earlier draft separated (source-object arrival,
source-field arity) are the two wrapper positions of [the wrapper algebra](#the-wrapper-algebra) above,
not two free enums.

### What `SourceKey` decomposes into (researched 2026-06-16)

`SourceKey` is `(target, columns, path, wrap, cardinality, reader)`, but under the source-object /
source-field vocabulary it bundles three separable concerns, only one of which is a source *key*. The
mechanical simplification is R316 (`decompose-sourcekey`); the model claim is here.

- **Target reach (already slotted elsewhere).** `target` is the target table, the table the
  rows-method reads `FROM`, the element type an accessor returns, the leaf of the join path, not the
  source; the name is correct (it is `null` only for the parent-IS-source polymorphic case). `path` is
  the join route to it. Both already live as first-class slots on `TableTargetField`: `returnType`
  (`returnType.table()`) and `joinPath`, carried even by the non-source table-bound variants
  (`TableField`, `LookupTableField`, `TableMethodField`) that hold no `SourceKey`. The `SourceKey`
  copies are denormalized: `SourceKey.path()` has *zero* readers in the generator (every emitter reads
  `field.joinPath()`), and the four `SourceKey.target()` readers all sit on carriers that also expose
  `returnType.table()` for the same table. So `target`/`path` leave `SourceKey` by deletion, not by
  introducing a new slot.

- **Source-object facts (migrate to the source endpoint).** The source object's shape (`Table | Record`,
  now the `SourceShape` inside the `OnlyChild` / `Child` arm), its backing class (today in
  `AccessorRef.parentBackingClass` / the lifter cast target), and its `env.getSource()` envelope
  (`SourceEnvelope`, carried per-field on `Reader.ResultRowWalk` but deliberately *not* on
  `Reader.ProducedRecordRead`, which already hoists it to the type level as `sourceIsOutcome`) are
  properties of the parent type, identical across every field on it. They belong on a richer source-object
  descriptor under the source arm's `SourceShape.Record`, not smeared per field. The `Reader` permit
  conflates source-object shape with the field's extraction mechanism; only the latter is a field fact.

- **The source key (what stays).** `columns` (the key tuple lifted off the source object), `wrap` (its
  Java row shape), `cardinality` (the source-**field** arity, see *bulk is a slot* above), and the
  extraction-mechanism half of `reader` (`ColumnRead` / `AccessorCall` / `SourceRowsCall` /
  PK-off-record). This residue earns the name: the key extracted from the source field, nothing about
  where it points or what shape its parent arrived in.

The `parentSourceKey` on `InterfaceField` / `UnionField` is the one place `SourceKey` is bent to
describe the source *object* (parent-identity extraction, `cardinality` hardcoded `ONE` = "one
parent"); it belongs with the source-object descriptor, not a field key.

### Leaf dissolution and collapse

- **`ConstructorField` dissolves.** Dead since the `@record`-on-types ban; its only path was an
  edge case not in use. **Done in R290:** the leaf, its leaf-to-tuple adapter arm, and its generator
  dispatch were deleted, and the table-and-service clash that used to classify it is now a build-time
  rejection.
- **`SingleRecordTableField` collapses into `RecordTableField`.** SRTF is `RecordTableField` (RTF) at
  arrival `OnlyChild`: same operation (re-project a `@table` from a held domain record), differing only in
  how many source records arrive. Its operation stays what the target dictates (`Fetch`); re-fetch is the
  orthogonal derived axis, not an operation change. **Split to R305**
  (`collapse-singlerecordtablefield-into-recordtablefield`): the `SingleRecordTableField` leaf is deleted, its
  construction sites produce `RecordTableField` at arrival `OnlyChild`, the re-fetch derivation above catches
  it through its received `Record` source, and `OrderingOwnedByProducer` dissolves (the source/target key
  correspondence owns the visible order). The whole re-fetch family (SRTF→RTF, `RecordLookupTableField` as
  RTF's `@lookupKey` sibling, `RecordTableMethodField` as RTF with the `@tableMethod` target instance, and the
  `@service`-batched `ServiceTableField`) re-fetches; `OnlyChild` re-projects inline (no DataLoader), `Child`
  batches. Because the source record and the target table are the same entity, **the source key is the target
  key**: the re-fetch key carries the target table plus its identifying columns once, not a source/target
  column duality. No distinct leaf survives once R305 lands.

### Leaf reconstruction: where each slot lands

The inverse of the dissolution above: dissolution says *which leaves* collapse, this says *where each
legacy slot* lands on the triple. The completeness test for the model (full walk and worked examples in
R316): given an `OutputField`'s `(source, operation, target)` coordinate plus its bridge and cross-cut
slots, the legacy leaf record must be reconstructible. A slot the triple cannot hold is a model gap.

| Legacy slot (representative) | Lands on | As |
|---|---|---|
| `returnType` + `FieldWrapper` | target | `wrapper(shape)`: `wrapper()` → `Single` / `List`; arms → `TargetShape` |
| `column` / `columns` / `columnName` (projection) | target | `Column` shape (arity ≥ 2 is the composite sub-detail) |
| `compaction` / `encode` / `aliasName` | target | projection function / alias on `Column` / `Field` |
| `returnExpression: DmlReturnExpression` | target | `Single` / `List` × `Column` (encoded id) / `Table` (projected) |
| `filters` / `orderBy` | operation | `Fetch` / `Paginate` payload |
| `pagination` | operation | the `Fetch` ↔ `Paginate` discriminant; `Paginate` payload |
| `method` / `serviceMethodCall` | operation **or** target provenance | `ServiceCall`; `@tableMethod` / `@externalField` ride the target shape |
| `lookupMapping` | operation | `Lookup` payload |
| `tableInputArg` / `inputArg` + `updateRows` / `deleteRows` / `kind` | operation | the write-arm input payload |
| `nestedFields` | operation | `Nest` payload |
| `participants` / `participantJoinPaths` / `discriminatorColumn` | target + bridge | `Interface` / `Union` shape payload + per-participant join paths |
| `SourceKey` (target, columns, path, wrap, cardinality, reader) | splits | `path` / `target` → bridge + target; reader / wrap / backing → source `Record`; `cardinality` → target wrapper |
| `loaderRegistration` | source | the `Child`-arm batch payload (its presence = `Child`) |
| `parentSourceKey` / `parentResultType` / `accessor` | source | source-object key / shape / `Record` extraction |
| `joinPath` / `fkJoin` / `parentCorrelation` | bridge | the FK route and its step-0 correlation |
| `errorChannel` / `errorTypes` / `transport` | cross-cut | the error channel, not an axis |
| `parentTypeName` / `name` / `location` | field identity | the `OutputField` envelope, not a dimension |

The leaf set reads larger than the model because one concept wears different vocabulary across leaves; each
is carried once by the unified axes:

1. **Service call**: `method` (child) and `serviceMethodCall` (root) collapse onto `Operation.ServiceCall`.
2. **Write input**: `tableInputArg` (INSERT / UPSERT) and `inputArg` + a walker carrier (UPDATE / DELETE)
   collapse onto one write-arm input payload.
3. **FK route**: `joinPath`, `fkJoin`, `participantJoinPaths`, and `SourceKey.path` are one join bridge.
4. **NodeId encode**: `compaction: CallSiteCompaction` and the bare `encode: NodeIdEncodeKeys` are one
   target projection.
5. **Return shape**: `returnExpression` re-expresses `returnType` + `FieldWrapper` for DML; it dissolves
   into the target `wrapper(shape)`.
6. **`column`**: the projection on `ColumnField` and the source read-location on `PropertyField` are the
   same name at opposite endpoints, split by endpoint.
7. **Cardinality**: `SourceKey.cardinality`, `LoaderRegistration`, `wrapper().isList()`, and the arrival
   count are one-vs-many at four positions, each a wrapper bound to its endpoint (the wrapper algebra).

The reconstruction key, invertible, is the completeness proof and predicts the leaf collapse R314 harvests:

```
leaf = f(source shape, source arrival, operation, target shape, target wrapper)
       + { new-query, re-fetch }   // derived slots
```

`Split*` vs non-`Split` is the `@splitQuery` new-query derived slot; `Record*` is a source `Record` vs
`Table`; `Lookup*` is operation `Lookup`; `Bulk*` is target `List`; `*Payload` vs `*Table` is target shape
`Record` vs `Table` / `Column`; `Composite*` is target `Column` arity ≥ 2. A `leafReconstructsFromCoordinate`
test over the R281 corpus (R316) makes the key executable.

### Model complete, classifier coverage partial

The `operation` set is the full model; the classifier populates what the current leaf set permits. The
modeled-but-unpopulated operations (declared gaps, never silently absent): `EntityResolve` (Federation
`_entities`), `Count`, `Facet` (connection roles, behind the ConnectionType quarantine), and the
condition-matched writes (unimplemented). Model leads classifier.

### Where this lands across the stages

This refines **Stage 3**. The dimensional-slot consumers (`DataFetcherBuilder`, `QueryBuilder`,
`ValidationBuilder`) compose these three axes plus the Stage-2 walker carriers into emit. **R290** is the
field-side spin-out that first materialised the field axes (as `carrier × intent × mapping`) and deletes
`LeafTupleAdapter`. **R299** first asserted them in the R281 corpus, migrating it off the producer/mapping
reconstruction; **R305** built the sealed source arm. **R316** is the pivot that renames the three axes to
`(source, operation, target)` and lifts R290 / R299 / R305's output onto them; its slices own the code
changes, this section the vocabulary.

## Architectural principle this codifies

The rewrite-internal disease is encoding multiple independent axes through one permit set; the cure is dimensional slots populated by independent producers, each producer a thin layer over graphql-java primitives, validity riding on the wrapper.

- **Cross-product encodings hide axes.** Per-axis encodings surface them. Adding an axis becomes adding a slot; adding a value to an axis becomes adding an arm to that slot's sealed family. No multiplication. Impossible combinations are excluded at production time, not by permit cross-product.
- **The walker abstraction is one implementation shape, not the load-bearing claim.** The load-bearing claim is that producers read graphql-java primitives directly and return typed sealed results. Slices may share an abstraction or roll their own; the test of correctness is the slot shape, not the producer shape.
- **Absence encoding follows the slot's home.** When a slot is field-universal (lives on `GraphitronField` or a sub-seal), absence is encoded by a `No<Family>` arm: the producer runs unconditionally, the carrier has a no-signal arm, consumers pattern-match exhaustively. When a slot is directive-gated and lives on a narrow interface (R238's pattern: `ServiceMethodCall` on `ServiceField`, sibling of `MethodBackedField`), absence is encoded by interface non-membership: the producer runs only for implementers, consumers reading the slot through the interface always get a populated value. Both forms make absence first-class; neither uses `Optional`.
- **Validity lives at the wrapper, not inside the carrier.** Encoding failure inside the carrier family would force every downstream consumer to either filter or handle the failure arm. Encoding it at the wrapper plus a classification/generation phase split lets downstream consumers assume `Ok`-only inputs while classification runs to completion for the LSP's benefit.
- **LSP-aligned diagnostics from day one.** Every diagnostic carries the LSP-shape fields (severity, code, message, tags, relatedInformation) so the wire-format adapter is a mechanical projection rather than a translation layer. R226's reframing of validator output as walker diagnostics, and R222's walker output, share one wire format.
- **Each axis is independently testable.** A producer is a pure function: SDL fragment in, sealed result out. Tests don't need a graphitron classification context.

## Transition techniques

Catalog of techniques surfaced by early slices. Not prescriptions; later slices may discard, refine, or invent alternatives based on what their own scope requires. Recorded so the next slice can borrow without re-deriving.

- **Additive cutover, then destructive retirement.** R238's actual landing (commits `f90a2f3` → `c1e7d2b` → `e6b6c1c`) added the new carrier slot alongside the legacy `MethodRef` slot on each record, cut consumers over while both shapes were reachable, then retired the legacy slot. Bounds the dual-implementation window to a short commit sequence rather than a feature-branch lifetime. Each step is reviewable on its own; the legacy stays runnable until the cutover commit lands. R244 adopts the same sequence for the `ErrorChannel.PayloadClass` retirement.
- **Temporary sibling interface for incompatible-shape implementers.** When a slice's new carrier shape can't accommodate every implementer of the original carrier-bearing interface in one slice's scope, the implementers that can't ride the new shape may split onto a sibling interface for the transition window, with the named follow-on slice absorbing them back. R244 introduces `WithDmlErrorTransport` next to `WithErrorChannel` because the DML carriers' sentinel-based transport doesn't fit the new `Mapped | NoChannel` shape; the DML follow-on slice re-unifies them. Technique trade-off: keeps each slice's scope bounded at the cost of one acknowledged transitional surface that must retire on a named follow-on; the alternative ("scope each slice so every implementer can ride the new shape in one slice") may produce a tighter result where it fits, and is fine to prefer.
- **Walker substrate concession on blast-radius grounds.** The principled substrate is SDL primitives + classloader directly. R238 took a translator concession (`ServiceMethodCallWalker` reads an upstream-resolved `MethodRef.Service` rather than reflecting from scratch) because today's `ServiceCatalog.reflectServiceMethod` is 1258 LOC of battle-tested reflection that a translator-walker avoids duplicating; a planned follow-up retires the intermediate. R244 has no comparably large intermediate and stays on the principled substrate. Each slice's call.

## Stages

Each stage is a work-stream; spin-out roadmap items file as slices get picked up. The order below names dependency edges, not the schedule. Stages 1–4 are mostly parallelizable across slices; Stages 5–7 are sync points.

### Stage 1 — Foundation slice

One vertical slice, end-to-end: one slot on a carrier-bearing interface, one producer that fills it, one consumer migration, one LSP wire arm. The slot's home (existing narrow interface vs. introduced one), the producer's implementation shape (sealed `Walker<S, C>` vs another), and the first consumer to migrate are the foundation slice's call. The point is that the slot pattern lands once, in tree, demonstrating the pivot's structural shift end-to-end. The foundation slice fixes the wire-format conventions (source attribution, code namespace, per-walker `AuthorError` sub-seal) and the slot-home convention (narrow interface, not universal parent; interface-gated absence rather than `No<Family>` when directive-gated) that subsequent slices inherit. R238 ships the foundation slice as `ServiceMethodCall` on a new `ServiceField` sibling of `MethodBackedField`.

### Stage 2 — Walker carrier slots

Each remaining walker-output carrier ships as an independent slice: its sealed family or record, its slot on a carrier-bearing interface (existing or introduced), the consumer migrations that read it, the `Diagnostic` arms it surfaces. Whether the carrier needs a `No<Family>` arm depends on the slot's home: field-universal slots do, directive-gated slots on narrow interfaces don't. Candidates (minus whichever Stage 1 ships): `ValidationShape`, `Pagination`, `Ordering`, `PredicateCarrier`, `MethodCall`, `InsertRows`, `UpdateRows`. Slices are parallelizable; no ordering between them.

### Stage 3 — Field dimensional slots

R164's content. `DataFetcherBuilder`, `QueryBuilder`, `ValidationBuilder` dimensional slots land per sub-seal, composing walker carriers and reflection-driven information into emit-ready form. Each dimension's sealed family lands once; the sub-seal's cross-product permits flatten under it. Each dimension is a spin-out slice; runs in parallel with Stage 2 once the foundation lands. See **Field-side dimensional model (pivoted 2026-06-18 to source/operation/target)** above for the sharpened target this stage implements: the field axes are `(source, operation, target)` with the producer dimension dissolved, and the builders here are the consumers that compose them.

### Stage 4 — Failure at the wrapper everywhere

R226's content. `UnclassifiedType` and `UnclassifiedField` retire. `GraphitronSchemaValidator.validateUnclassifiedType` / `validateUnclassifiedField` retire. Type-level classification (`GraphitronSchemaBuilder`'s type-classification step) lifts into `WalkerResult<C>`. The validator's surface narrows to cross-type invariant checks. `ValidationReport`'s `errors` / `warnings` slots collapse into the unified `Diagnostic` stream. R279 (`field-first-classification-driver`, supersedes R166) restructures that classification driver ahead of this lift, into a single reachability-driven field-first walk; it preserves the `Unclassified*` carriers and the classify → validate split, so this Stage 4 lift rides on its walk rather than the eager type pass.

### Stage 5 — Legacy permit deletion

Sync point on Stages 2 + 3 (every consumer reads via slots; every cross-product permit's dimensional slots have ingressed). Retirements:

- `GraphitronType.InputType` 4-arm permit + `TableInputType` sibling root
- `ArgumentRef.InputTypeArg.TableInputArg`, `PlainInputArg`
- `InputField` sealed family (`ColumnField`, `ColumnReferenceField`, `CompositeColumnField`, `CompositeColumnReferenceField`, `NestingField`, `UnboundField`)
- `HasInputRecordShape` capability marker
- `RootField` intermediate sub-seal between `OutputField` and `QueryField` / `MutationField`
- Cross-product field permits per R164's consolidation (`RecordLookupTableField`, `QueryServiceRecordField`, etc.)
- `TypeBuilder.findReturnTablesForInput` back-scan

### Stage 6 — Namespace collapse

Sync point on Stages 4 + 5. After Stages 4 + 5, `GraphitronField`'s `permits OutputField, InputField, UnclassifiedField` reduces to `permits OutputField`. Delete the sealed parent; rename `OutputField` → `GraphitronField`. Re-flatten field signatures across consumers; the `RootField` intermediate retires here if not already.

### Stage 7 — Directive narrowing

`@table`, `@record(class:)`, `@value` drop from `INPUT_OBJECT` scope; the SDL directive declarations narrow; fixture sweep. Closes R97. Lands anywhere after Stage 5.

## What this absorbs

| Item | Absorption mode |
|---|---|
| **R164** (field-model three-dimension pivot) | Stage 3 + Stage 5 (permit consolidation). File discarded |
| **R226** (classification dimensional pivot: diagnostics off the model) | Stage 4 + unified `Diagnostic` family. File discarded |
| R171 (sealed `InputLikeType` parent) | Dissolves; no per-input model record survives |
| R97 (deprecate `@table` on input types) | Stage 7 directive narrowing closes the item. `argMapping` grouping (R97 Phase 1) remains separable |
| R213 (rejections at consumer field) | Walker-time `SourceLocation` is the consumer field's own SDL location |
| R209 (FieldRegistry classify-input trace) | Typed `Rejection.AuthorError` at walker time; surfaces through the orchestrator's `WalkerResult.Err.errors` collection |
| R221 (validator walks `PlainInputArg.fields()` for `UnboundField` rejection) | Dissolves; per-permit dispatch retires |
| R144 (lookup-key / set-field partition stored on `TableInputArg`; `@value` directive marker) | Two reversals. (1) Partition lives in `PredicateCarrier`'s `Condition` / `LookupRows` arm choice. (2) `@value` retires (catalog-derived from PK membership). R144's cardinality-safety surface (`multiRow: true` opt-in, PK-coverage check) survives unchanged |
| R215 (column-binding at classification, not usage) | Subsumed; column binding happens inside each SQL-emitting producer at its leaf-resolution step. R215's `UnboundField` admit set translates per-walker into the producer's own "unresolved" arm |
| R98 (multi-source input validation) | Dissolves structurally. Per-output-field validation makes "different consumers, different POJOs" the default behaviour, not a structural extension |

Adjacent but not absorbed:

- **R220 / R193** (`ServiceCatalog` predicate consolidation, sealed `UnresolvedParam`): same disease in a different file. R222 primes the pattern; those items apply it on the consumer-side surface independently.
- **R122** (compound-entity-mutations): contract partner. R122 owns `InsertRowsWalker`'s tree shape and FK threading; R222 names the slot the producer fills.
- **R200 / R195** (honor `@field(name:)` in `InputBeanResolver`): naming binding between SDL fields and Java members, orthogonal to the pivot.
- **R279** (`field-first-classification-driver`): a slice under this umbrella that restructures `GraphitronSchemaBuilder`'s classification *driver* into a single reachability-driven, field-first walk, and supersedes R166. Orthogonal to the slot/carrier work here, the dimensional-slot producers run on reachable fields regardless, but it is where the umbrella's reachability prune (the old R166 Phase 1 reachability slot) actually lands, and the Stage 4 failure-at-the-wrapper lift rides on its walk.

## Dependencies and sequencing

- Stage 1 enables Stages 2–4. Slices within those stages are parallel; no inter-slice ordering.
- Stage 5 syncs on Stage 2 + the parts of Stage 3 whose dimensional slots compose Stage 2 carriers.
- Stage 6 syncs on Stages 4 + 5.
- Stage 7 lands anywhere after Stage 5.
- **R215** (Done): unbound-field admit set generalises to producers' "unresolved" arms; no further build-order concern.
- **R94** (shipped): the existing `HasInputRecordShape` marker + `InputRecordShape` record become the `ValidationShape` carrier; the slice that ships this is per-output-field POJO emit in `InputRecordGenerator`. R94's per-input POJO becomes per-(output-field × input-type-typed-arg) POJO; R98's multi-source case becomes default behaviour.

## Vocabulary

The names below are the working vocabulary for the umbrella; slices may rename, narrow, or restructure as their implementation details surface. Treat this as anchor terminology, not a frozen API.

- **`Walker<S, C>`** — a pure function over an SDL substrate `S` returning `WalkerResult<C>`. One implementation shape for producers; slices may pick another. Substrate-parametric for forward-compat with type-level producers.
- **`WalkerResult<C>`** — sealed `Ok<C>(C carrier, List<Diagnostic> diagnostics)` / `Err<C>(List<AuthorError> errors, List<Diagnostic> diagnostics)`. `Ok` rejects Error-severity diagnostics by compact-ctor; `Err.errors` is non-empty by compact-ctor invariant. Classification runs to completion regardless of how many `Err`s; downstream generation is blocked when any `Err` is present.
- **Carriers**: `ValidationShape`, `Pagination`, `Ordering`, `PredicateCarrier`, the `MethodCall` family (per-directive: `ServiceMethodCall`, `ConditionCall`, `TableMethodCall`, `ExternalFieldCall`), `InsertRows`, `UpdateRows`. Each a sealed family or record carrying the reduced output. `No<Family>` arms apply when the slot is field-universal; directive-gated slots on narrow interfaces (R238's pattern) skip the `No<>` arm and use interface non-membership instead. No `Invalid` arm in either case; structural failure rides on `WalkerResult.Err`.
- **Dimensional slots** — `DataFetcherBuilder`, `QueryBuilder`, `ValidationBuilder`. Compose walker carriers + reflection-driven information into emit-ready form.
- **`No<Family>`**: the domain arm naming "the substrate carries no actionable signal for this family." Producer ran, no error, nothing to encode. Applies when the slot is field-universal; directive-gated slots on narrow interfaces use interface non-membership instead. Concrete shapes vary per family (`NoPredicates`, `NoValidationShape`, ...); framing is uniform across the cases that need it.
- **`PredicateCarrier`'s two valid arms** — `Condition` for SQL-emitting *read* fields, `LookupRows` for *mutation* fields. The producer's bailout-restart pattern handles role discovery: sentinel directives (`@lookupKey` on a read field, `@multirows` on a mutation field) trigger an arm flip. Consumers pattern-match the arm at use time.
- **`MethodCall` family**: per-directive records carrying `(target, methodName, bindings, returnShape)`. R238 pins the first instance — `ServiceMethodCall` (sealed `Static` / `Instance`) — and lands its bindings as `MappingEntry` arms (`FromArg`, `FromContext`, `FromDsl`) plus a recursive `ValueShape` family for input-object bindings. Subsequent slices add `ConditionCall`, `TableMethodCall`, `ExternalFieldCall` with their own binding shapes. The earlier umbrella draft named one unified `MethodCall` with `ParamBinding` arms covering every directive (`FromEnvArg`, `FromContextKey`, `FromDslContext`, `FromBatchKeys`, `FromSourceRow`); R238 split it per-directive because call shapes differ enough that one unified carrier would carry many always-absent slots per callsite.
- **Shared emitter**: a static utility parameterised on a carrier-bearing interface that produces emit-ready code fragments (var-decls, expression blocks). R238 introduces `ServiceMethodCallEmitter(ServiceMethodCall) -> List<CodeBlock>`. Lighter than a dimensional slot when the consumer's only need is the carrier's emission, not multi-carrier composition. Slices choose between shared emitter and dimensional slot per consumer's need.
- **Per-directive sibling interface**: a sibling of `MethodBackedField` carrying one directive's call slot. R238 introduces `ServiceField` (carrying `ServiceMethodCall`) as the first instance. The earlier umbrella draft framed this as a pure marker sub-interface of `MethodBackedField`; the sibling shape lets each slice ship narrow without forcing the other six `MethodBackedField` implementers to grow no-op slot accessors. `MethodBackedField` retires once every per-directive sibling has landed.
- **`BackingClass`** — three-arm sealed family (`Pojo`, `JavaRecord`, `JooqTableRecord`); attaches per binding kind where method-call semantics need it.
- **`Diagnostic`** — LSP-aligned record with a graphitron-internal `Severity` enum (arms `Error` / `Warning` / `Information` / `Hint`, paralleling LSP `DiagnosticSeverity`). Carries non-error events on both `Ok` and `Err`. The graphitron-side shape does not import `org.eclipse.lsp4j`; the LSP module's `Diagnostics` projector maps to `lsp4j.Diagnostic` at the wire boundary so no code below the LSP module sees the lsp4j types. `BuildWarning` migration retires; the channel is one unified stream at the LSP boundary.
- **`AuthorError`** — the existing `Rejection.AuthorError` sealed family. The wire-format adapter projects each leaf to severity=Error LSP `Diagnostic` with a code derived per leaf type (e.g. `AuthorError.UnknownName` → `"graphitron.unknown-name"`).
- **`@table` / `@record(class:)` on input types** — drop entirely. Table-binding collapses to the consumer's `@table` return at production time. `@record(class:)`'s deserialization-target function collapses to the user's declared service-method param type, read by reflection at the `MethodCall` producer's site.
- **`@value` on input fields** — drops as redundant scaffolding. The WHERE-vs-SET partition derives from catalog PK membership inside the SQL-emitting producers.

## Out of scope

- The R164 sub-dimension internals (`DataFetcherBuilder` source-cardinality + action + field-cardinality axes, etc.) — Stage 3 spin-outs own those.
- The R122 `InsertRowsWalker` tree shape and FK threading — R122 owns.
- `ServiceCatalog` predicate consolidation (R220 / R193) — adjacent disease in a different file.
- `argMapping` grouping syntax (R97 Phase 1) — adjacent.
- Reachability pruning across all type kinds — owned by R279 (`field-first-classification-driver`, supersedes R166 Phase 1), the driver-restructure slice; orthogonal to the slot/carrier work here.
- Producer-side unification of method invocation paths (uniform reflection-mapping rules across `@service` / `@externalField` / `@tableMethod` / `@condition`) — separate work that R164's `DataFetcherBuilder` dimension may absorb piecewise; not load-bearing on the umbrella.

## Previous design attempts

Rejected alternatives from earlier R222 drafts, recorded so reviewers don't re-derive the dead ends.

- **Horizontal Phase 1 (all carriers, all slots, all `Diagnostic` arms up front).** Earlier draft committed seven carrier families, seven slot getters, four `Diagnostic` arms in Phase 1 with only `ValidationShape` populated. Rejected: vocabulary that doesn't ride a live producer is a contract committed before any consumer pulls on it. Vertical slices each ship their own vocabulary.
- **R164 + R226 as separate items.** Treated as adjacent contract partners. Rejected because the work overlaps structurally: R164's dimensional slots compose R222's walker carriers; R226's `Unclassified*` retirement uses the same `WalkerResult.Err` wrapper R222 produces. Unifying as one umbrella with parallel spin-outs replaces the coordination tax with explicit stage dependencies.
- **Recursive `InputUsage` carrier scoped to SQL emission.** First pivot folded WHERE construction, DML row-shaping, lookup-key identification, method-param binding, pagination, and ordering into one classified output that consumers re-discriminated by role. Rejected as wrong granularity.
- **`Invalid<Family>` arms inside the carrier families.** Encoded structural failure inside two of the seven families. Rejected because no downstream generator inspects an `Invalid` arm — generation is blocked before any generator runs in either failure mode, so the asymmetry encoded an invariant the wrapper + phase split already enforce.
- **`Input` / `InputFieldDecl` per-input wrapper records.** Pass-through wrappers over `GraphQLInputObjectType` / `GraphQLInputObjectField`. Rejected because per-input identity has no carrier-independent state worth a record, and graphql-java already provides every accessor.
- **`SchemaCoordinate(String)` identity wrapper.** Stringly-typed wrapper conflating `"FilmInput"` and `"FilmInput.title"`. Rejected because plain `String` covers the type-name case and graphql-java's `FieldCoordinates` covers the type+field case.
- **Optional carrier slots on `OutputField` (`Optional<Pagination>`, ...).** Presence-vs-absence at the storage layer. Rejected for field-universal slots because the `No<Family>` arm makes absence a first-class domain state consumers pattern-match exhaustively; Optional re-introduces a present/missing flag the sealed family already encodes. For directive-gated slots, R238 surfaced a third option: interface-gated presence (slot lives on a narrow interface, absent for non-implementers). Optional remains rejected in both cases.
- **`ValidationShape` as a per-input carrier (`Map<String, ValidationShape>` on the classification artifact).** Two-substrate variant: per-output-field carriers on `OutputField`, per-input carriers in a name-keyed map. Rejected because validation fires at the resolver method-arg boundary, which is the output field's seat; the "global common shape across consumers" framing tried to reuse a per-type POJO, but the consumer is the unit of validation.
- **`MethodArguments` as the carrier name.** Earlier vocabulary draft. Replaced by `MethodCall` to align with the rewrite's existing `CallParam` / `CallSiteExtraction` / `MethodRef.Call` naming family.
