---
id: R75
title: "Plain payload types for DML mutations"
status: Spec
bucket: architecture
priority: 7
theme: mutations-errors
---

# Plain payload types for DML mutations

A `@mutation` whose return type is a plain SDL Object (no `@record(className)`, no
`@table`) wrapping a single `@table`-element data field should compile and serve
correctly without the consumer authoring a Java carrier. Today the rewrite forces an
authored class on every payload that isn't a bare `ID` or `@table`, which inverts legacy
graphitron's default and drags graphql-java's mapping concerns into the consumer's
source tree.

The fix is not to synthesize a Java class — generating transport DTOs is the legacy
mistake the rewrite already disowns under the "no DTOs, no TypeMappers" emitter
convention (`graphitron-rewrite/docs/rewrite-design-principles.adoc`, §"Return types").
Java payload classes earn their place only when the consumer needs to capture state or
behaviour on the carrier. The structural reading of the SDL is: the *mutation* returns a
record-shaped wire type; the data field *inside* that wire type is the table-bound field
that carries the rows. The model classifies them accordingly — record-returning mutation
on top, `RecordTableField` (already in the model) for the data field — and the DML
emitter ships the rows back through graphql-java's standard traversal without a carrier
class on disk.

The reshape also lets the DML's transaction be tight. Today's emit pattern bundles
`INSERT...RETURNING $fields(table)` into a single statement that returns full row data
inside the DML transaction; if the surrounding code holds the transaction across
graphql-java's traversal, read-after-write errors during the response can roll back the
write. R75 shifts to two-statement emit: the DML returns PK columns only and commits in
its own short transaction; the data field's `RecordTableField` fetcher runs the
follow-up `SELECT $fields(table)` outside that transaction. Read errors during traversal
become partial-response errors, never undo writes.

## Concrete friction

```graphql
input OpprettKvotesporsmalPreutfyllingInput @table(name: "kvotesporsmal_preutfylling") {
    kvotesporsmalPreutfyllingKode: String! @field(name: "KVOTESPORSMAL_PREUTFYLLING_KODE")
}

type KvotesporsmalPreutfyllingPayload {
    kvotesporsmalPreutfylling: [KvotesporsmalPreutfylling!]
}

type Mutation {
    opprettKvotesporsmalPreutfylling(
        input: OpprettKvotesporsmalPreutfyllingInput!
    ): KvotesporsmalPreutfyllingPayload @mutation(typeName: INSERT)
}
```

Pre-R75 this fails at `MutationInputResolver.validateReturnType` (the `ScalarReturnType`
arm rejecting non-`ID` returns) with *"return type 'KvotesporsmalPreutfyllingPayload' is
not yet supported; use ID or a @table type"*. The plain Object lands as
`PlainObjectType` in the type classifier (`TypeBuilder.buildTypes`, the no-domain-
directive fall-through), and `GraphitronSchemaBuilder.buildSchema` skips field
classification for `PlainObjectType` parents entirely. There is no machinery downstream
that would route the DML round-trip back to graphql-java for traversal.

R75 Phase 1's first attempt shipped at `3ac4996` under a wire-format-unwrap-at-boundary
design: a new `ChildField.PassthroughDataField` permit, a new `IdentityPassthrough`
capability sealed sub-interface, a `BuildContext.resolveReturnType` short-circuit that
collapsed the payload type into the data field's `TableBoundReturnType`, and the
existing `MutationInsertTableField` etc. permits used for both direct-`@table` returns
and unwrapped payloads. In review, two design tensions surfaced: (a) the unwrap pattern
re-derives passthrough state at four consumer sites instead of carrying the resolution
in the type system, and (b) the one-statement `INSERT...RETURNING $fields(table)` keeps
the DML transaction open across graphql-java's response traversal, so read-after-write
errors can undo writes. The reshape below supersedes the shipped attempt; the SDL
acceptance contract is unchanged from the consumer's view, but the model and the emit
pattern are different. Phase 1's added permits and capability retire as part of the
implementation.

## Phasing

R75 ships in three phases ordered by user-visible value. Each phase is a discrete
implementation track; downstream phases bind structurally to upstream ones but are
not schedule-coupled.

- **Phase 1: the reshape — record-returning DML mutations + tight transactions.**
  Replaces Phase 1's shipped wire-format-unwrap design with the structural model
  the SDL implies: mutations with payload return types classify as record-returning
  carriers; the data field on the payload classifies as the existing
  `ChildField.RecordTableField`; DML emit becomes two-step (PK-only RETURNING
  inside `dsl.transactionResult(...)`, then a follow-up SELECT for the response
  data outside the transaction). Direct-`@table`-return DML mutations get the same
  two-step emit. `DELETE` admits only `: ID` / `: [ID!]` returns (no payload-shaped
  data — the row is gone before the SELECT can read it).
- **Phase 2: multi-field payloads via `localContext` on `@mutation` (DML).** Extends
  the trigger to multi-field payloads where one field is the table-bound data field
  (per Phase 1) and the rest are non-data slot fields. Non-data fields carry state
  via graphql-java's `DataFetcherResult.localContext`. Phase 2 ships the wrap with
  an empty `localContext` map; the populator interface, registration mechanism, and
  per-slot trigger conditions land alongside the first real populator (errors, in
  its own roadmap item).
- **Phase 3: `@service` mutations and `@record`-element data.** Admits payload-
  returning shapes on `@service` mutations (where the consumer constructs
  `DataFetcherResult` directly) and admits `@record`-element data fields (in
  addition to `@table`-element). `RecordTableField` already covers the
  `@record`-parent / `@table`-child case at the data-field site; the work is at
  the mutation-classifier and `@service`-resolver layer.

All three phases are specified in implementation-ready detail below.
Promoting R75 from Spec → Ready means signing off on the entire body;
each phase ships as its own implementation cycle.

## Open design forks (post-architect-review, work-in-progress)

A `principles-architect` review on the pivoted spec body returned 11 findings;
verdict was *needs revision before Spec → Ready handoff*. A self-review found 6 items,
overlapping on the framing items. Of the two forks with a captured discussion frame,
**Fork B is now settled** (the `SourceKey` / `SourceRow` split below — the linchpin
of the design); Fork A remains open. The remaining must-address items carry forward.
The next session must work through Fork A and the must-address list before the Spec
→ Ready transition. The Phase 1 / 2 / 3 bodies below have **not** yet been updated
to reflect the resolutions; they describe the pre-architect-review pivot.

### Settled this session

**Fork B — `SourceKey` / `SourceRow` split (resolves architect #2). Linchpin.**

Today's `BatchKey` resolves into two cleaner concepts plus a small companion
record, with the 11-permit hierarchy orthogonalized into independent axes.

**`SourceKey`** is the singular per-field metadata — the shape descriptor the
classifier produces and consumers (fetcher emitter, rows-method emitter,
validator) read. Carries: target table identity (derivable from path/columns;
path empty → table the columns belong to, path non-empty →
`path.getLast().targetTable()`); column tuple (`List<ColumnRef>`, entry-point
columns — match target's columns when path empty, first-hop source-side when
path non-empty); path (`List<JoinStep.FkJoin>`, empty = target-aligned, non-empty
= FK chain to target — `LiftedHop` no longer mixes here, the `@sourceRows`
contract pins lifter output to entry-point columns and the chain is FK-only);
wrap shape (`Row` | `Record` | `TableRecord<X>`); reader strategy (sealed
sub-level, see below); per-source cardinality (`ONE` | `MANY`).

**`SourceRow`** is the plural runtime instance — a row of data flowing at
execution time. Java type dictated by SourceKey's `wrap × columns`
(e.g. `Row1<Integer>`, `Record2<Integer, String>`, `FilmRecord`). Not a
generated type; the runtime form of what SourceKey describes. **One SourceKey
per field; potentially many SourceRows per source per fetch.** Multiple
SourceRows share the same SourceKey: same target, same column tuple, same wrap.

**`LoaderRegistration`** is a small field-level record (not sealed): loader
name, value-type per key (`Record` vs `List<Record>`), container
(`POSITIONAL_LIST` vs `MAPPED_SET` — drives `newDataLoader` vs
`newMappedDataLoader`). The same SourceKey shape can be loaded into either
container kind, so container doesn't belong on SourceKey. The R75 rooted case
has no `LoaderRegistration` — the DataFetcher reads `env.getSource()` and uses
SourceKey directly to extract SourceRow instances.

**Per-fetch dispatch** (`load(sourceRow)` vs `loadMany(sourceRows)`) reads
`sourceKey.cardinality()` at the call site. Today's `LoaderDispatch` enum on
`RecordParentBatchKey` becomes redundant.

**Reader strategies** (sealed sub-level on SourceKey):
- `ColumnRead` — catalog FK on parent record.
- `AccessorCall` — typed instance accessor on `@record` parent backing class.
- `SourceRowsCall` — `@sourceRows` static lifter (directive renamed from
  "lifter"; plural reflects the per-source cardinality vocabulary).
- `ResultRowWalk` — walk a `Result<RecordN>` from upstream DML (the R75 rooted
  case).
- `ServiceTableRecord` — service returns `TableRecord<X>`; reflection over X
  extracts entry-point columns. FK-on-X optimization (target-align without
  walking the chain when X carries an FK to target) deferred to a sibling
  Backlog item.
- `ServiceUntypedRecord` — service returns `Record<>`; reflection verifies field
  names + types match target's key columns. Target-aligned only.

**Cross-axis invariants** enforced in compact constructors per Reader:
`SourceRowsCall` → `wrap == Row`; `AccessorCall` returning `List<X>` →
`cardinality == MANY` and `wrap == Record`; `ResultRowWalk` → cardinality
matches the upstream Result's row count (singular `Record1<...>` → ONE,
`Result<Record1<...>>` → MANY); `ServiceTableRecord` with X = target table →
`path` empty.

**Args stay separate.** Verified `LookupMapping.ColumnMapping.LookupArg` already
exists as the args-side sealed type (three arms: `ScalarLookupArg`,
`DecodedRecord`, `MapInput`), driven by `@lookupKey` and projected by
`LookupMappingResolver`. SourceKey is the source-side sibling; no unification.

**Mapping today's 11 BatchKey permits to the new shape:**

| BatchKey permit | Reader | wrap | cardinality | path |
|---|---|---|---|---|
| `RowKeyed` (`ParentKeyed`) | `ColumnRead` | Row | ONE | empty |
| `RecordKeyed` | `ColumnRead` | Record | ONE | empty |
| `MappedRowKeyed` | `ColumnRead` | Row | ONE | empty |
| `MappedRecordKeyed` | `ColumnRead` | Record | ONE | empty |
| `TableRecordKeyed` | `ColumnRead` | TableRecord | ONE | empty |
| `MappedTableRecordKeyed` | `ColumnRead` | TableRecord | ONE | empty |
| `RowKeyed` (`RecordParentBatchKey`) | `ColumnRead` | Row | ONE | catalog FK chain |
| `LifterLeafKeyed` | `SourceRowsCall` | Row | ONE | empty |
| `LifterPathKeyed` | `SourceRowsCall` | Row | ONE | FK chain |
| `AccessorKeyedSingle` | `AccessorCall` | Record | ONE | empty |
| `AccessorKeyedMany` | `AccessorCall` | Record | MANY | empty |

The container axis (positional vs `Mapped*` variants) moves to
`LoaderRegistration`. Net type-identity count: 11 → 1 SourceKey + 6 Reader
sub-permits = 7. Adding R75's `ResultRowWalk` is a one-Reader-permit addition,
not a 12th BatchKey permit.

**Resolver pattern.** A new `SourceKeyResolver` projects classification context
(catalog FK / `@record` parent's accessor / `@sourceRows` lifter / service-return
reflection / upstream DML Result) into a SourceKey. Sibling to `OrderByResolver`,
`PaginationResolver`, `LookupMappingResolver` — the existing single-concern
projection pattern.

**Knock-on impacts on the must-address list (re-grounded under the new
vocabulary):**
- **Architect #4** gets a cleaner home: SourceKey with `reader == ResultRowWalk`
  structurally pins target to the upstream DML's RETURNING table. The
  load-bearing classifier check declares the constraint by SourceKey shape; the
  validator threads rejection messages.
- **Architect #5** reframes cleanly: mutation fetcher produces SourceRow
  instances of typed shape; data-field fetcher consumes via SourceKey with
  `reader == ResultRowWalk`. Both halves carry typed shapes; the prior
  `(Result<Record1<Integer>>) env.getSource()` cast becomes a SourceKey-driven
  typed read with the wrap pinned by SourceKey.
- **Architect #11** (load-bearing key inventory) — candidates surfaced by the
  new shape:
  - `source-key.target-table-derives-from-structure` (path-empty vs
    path-non-empty cases).
  - `source-key.column-tuple-aligns-with-entry-point` (columns match target
    when path empty, first-hop source-side when path non-empty).
  - `source-key.cardinality-matches-reader` (per-reader compact-constructor
    invariants).
  - `source-key.result-row-walk-target-equals-dml-input-table` (R75-specific;
    architect #4 overlap).
  - `loader-registration.dispatch-derives-from-cardinality` (load vs loadMany
    at call site reads cardinality, not a stored discriminator).
- **Fork A / Architect #1** intersects: the bulk-vs-single axis Fork A is
  exploring is largely a SourceKey `cardinality` property rather than a
  carrier-permit property. May simplify Fork A's resolution.

This is the linchpin: Phase 1 / 2 / 3 bodies need to be re-grounded against the
SourceKey / SourceRow vocabulary before Spec → Ready. The session that picks up
this spec next should start here.

### Forks with discussion frame (still open)

**Fork A — `RecordTableField` reuse vs sibling permit (architect #1).**

Architect's claim: today's `RecordTableField` always routes through
`buildRecordBasedDataFetcher` (DataLoader path with `SplitRowsMethodEmitter
.buildForRecordTable`). The reshape's rooted case is a plain `DataFetcher` reading
`env.getSource()` — different topology, no rows-method, no DataLoader registration.
Forcing both shapes through one permit puts the dispatch fork at every consumer site
(validator, fetcher emitter, rows-method emitter, leaf-coverage check). The empty
`joinPath` / `filters` / `pagination` / bespoke `BatchKey` on the rooted case is the
type system telling us the variants don't share a meaningful component set.
Suggested: sibling permit (`RootedRecordTableField`) or sealed sub-split.

Counter-frame (user): bulk DML mutations require the rows-method + `VALUES`-table-with-
strict-ordering machinery — a non-trivial generation-time concern. Only the *single-
input* mutation is "trivial". The trivial case can be solved using the general case,
so the rooted/nested split isn't the cleanest axis — bulk vs single is. Whether
`RootedRecordTableField` is the right cleanup, or whether a different sealed sub-split
captures the variation, is unresolved. Discuss further next session.

(Note from settled Fork B: bulk-vs-single is largely a SourceKey `cardinality`
property; that may simplify the carrier-permit decision here.)

### Must address (no discussion frame yet)

**Architect #3 — "trigger consulted at exactly two sites" undercount.** The pivot
retires `BuildContext.resolveReturnType`'s call (good) but the validator arms in
`MutationInputResolver.validateReturnType` (`ScalarReturnType` arm + `ResultReturnType`
arm) still need to thread `Rejected` reasons. The "two sites" claim erases the
validator wing of the validator-mirrors-classifier discipline. Either consolidate
rejection-message responsibility into the classifier (truly fewer sites) or describe
the call topology honestly. Self-review #2 also flagged this.

**Architect #4 — load-bearing key `passthrough-payload.data-table-equals-dml-target`
is *not* structurally enforced by the pivot.** Trigger condition #3 admits any
`TableBackedType` element. SDL author writing
`Mutation.x(in: FilmInput!): ActorPayload` admits — Actor is `@table` — but the emit
generates `INSERT INTO film ... RETURNING film.film_id` then
`SELECT $fields(actor) FROM actor WHERE actor.actor_id IN (source.getValues(film.film_id))`.
That compiles, runs, and returns nonsense. Either add trigger condition #5
(`dataElement.table() == tableInputArg.inputTable()` for DML mutations) or restore
the load-bearing classifier-check pair. Self-review #1 also flagged this.

**Architect #5 — the wire-format boundary still exists, just moved up.** The pivot
returns `Result<Record1<Integer>>` (PK-only rows) to graphql-java; the data-field
fetcher casts `(Result<Record1<Integer>>) env.getSource()` and runs the response
SELECT. That cast IS the boundary, one layer up. The shape "PK rows packed in jOOQ
Result" is exactly the wire-format-boundary smell. The honest description: adapter /
composer pair (per the principle). Mutation's fetcher = adapter (PK-only out); data-
field's fetcher = composer (PK in, full rows out). Both halves carry typed shapes;
the cast in the spec sketch is the asymmetry the principle warns against. Reframe
the spec body to acknowledge the boundary and design the typed shape on both sides.

**Architect #6 — Open Q1 (PlainObjectType vs PojoResultType promotion) isn't 50/50.**
Option B literally re-introduces the `resolveReturnType` short-circuit the pivot
exists to retire. Option A (promote no-`@record` plain Objects to `PojoResultType`
with null `fqClassName` at type-classification time) is the principled answer.
Commit; the wart it widens is real but proper to defer (see #8 below).

**Architect #7 — Open Q3 (carrier wrap convention) should commit to `DataFetcherResult`
from day 1.** Phase 2 needs the wrap; deferring forces a Phase-2 migration of every
Phase-1 consumer. Cleaner to ship Phase 1 with
`DataFetcherResult.<Result<RecordN<...>>>newResult().data(rows).build()` and have
Phase 2 be purely additive (`localContext` attaches to the existing wrap). Drop bare-
`Result` return from the spec.

**Architect #9 — three identical `Mutation*RecordField` permits should be one** with
a `DmlKind` discriminator. The "sealed hierarchies" principle says variants split
when they carry distinct data; these three carry identical components. Either one
`MutationDmlRecordField` carrying `DmlKind kind`, or — out of R75's scope, but
worth a Backlog item — collapse the existing four `DmlTableField` permits the same
way.

**Architect #10 — direct-`@table`-return two-step emit is a perf trade.** Adds one
round-trip per mutation versus today's single-statement `INSERT...RETURNING $fields`.
The transaction-durability concern that motivates two-step is the *payload* case;
direct-`@table`-return doesn't have the same exposure (graphql-java's traversal of a
`Record` doesn't issue further DB reads). Pick consciously: (a) two-step only on
payload returns, single-statement on direct-`@table`; (b) two-step uniformly, accept
the perf hit. Spec implicitly chose (b) without justification. Self-review #5 also
flagged this.

**Architect #11 — "no new load-bearing keys" undersells the design's reliance.** The
rooted `getSource()` cast, the from-clause anchored on the input table, the IN-
predicate over the input table's PK columns — each is a classifier guarantee the
emitter relies on. Each needs `@LoadBearingClassifierCheck` /
`@DependsOnClassifierCheck` declared up front, or the audit will flag orphans when
implementation lands. Re-audit the emitter sites the pivot adds and tag pre-conditions
with load-bearing keys.

### Defer to a sibling Backlog item

**Architect #8 — `PojoResultType` model wart.** `fqClassName == null` on
`PojoResultType` carries two distinct meanings (no `@record(className:)` ever
authored vs payload-shaped no-`@record` Object promoted by the trigger). Smell of
"enum forces every variant to have the same shape". Lift to a sealed sub-taxonomy
(`(Backed | NoBacking)` or similar) so the trigger's condition #1 reads off one
permit. R75 doesn't fix this; it inherits the wart. Open as Backlog if the next
reviewer agrees.

### Self-review items not in the architect's list

- **Trigger function name.** `unwrapPassthroughPayload` is residue from the retired
  wire-format-unwrap design. Rename to something like `tryResolvePayloadShape` as a
  Phase 1 implementation step; spec should commit rather than say "consider
  renaming". (Self-review #4.)
- **Phase 1 carrier should pre-include `slots: List<SlotDescriptor>`.** Phase 2
  widens the permits with the slot list; pre-including (empty in Phase 1) avoids
  retroactive permit-widening. Folds into Architect #7's "commit to
  `DataFetcherResult` wrap from day 1". (Self-review #3.)
- **Component-type narrowness inconsistency vs `MutationServiceRecordField`.** New
  R75 permits use narrow `ResultReturnType`; existing `MutationServiceRecordField`
  uses broad `ReturnTypeRef`. Pick a side: (a) follow the existing broad shape
  (consistency, less type-safety), (b) flag a follow-up item to narrow the existing
  carrier. Spec doesn't pick. (Self-review #6.)

---

## Phase 1: record-returning DML mutations + tight transactions

Phase 1 reshapes R75 onto the structural reading of the SDL: a `@mutation` returning a
payload type classifies as a *record-returning* mutation; the data field on the payload
classifies as the existing `ChildField.RecordTableField`; DML emit becomes two-step
(PK-only RETURNING in a tight transaction, then a follow-up SELECT outside it). The
shipped attempt's `PassthroughDataField` permit, `IdentityPassthrough` capability, and
`resolveReturnType` short-circuit all retire as part of the implementation.

### Model changes

Three new permits parallel today's `MutationServiceRecordField` in shape:

```java
record MutationInsertRecordField(
    String parentTypeName,
    String name,
    SourceLocation location,
    ReturnTypeRef.ResultReturnType returnType,
    ArgumentRef.InputTypeArg.TableInputArg tableInputArg,
    Optional<ErrorChannel> errorChannel
) implements MutationField {}

record MutationUpdateRecordField(...) implements MutationField {}
record MutationUpsertRecordField(...) implements MutationField {}
```

`returnType` is the payload's `ResultReturnType` (no unwrap — the SDL's structural
truth). `tableInputArg` carries the input `@table` exactly like the existing
`MutationInsertTableField` etc. arms. No `MutationDeleteRecordField` permit: DELETE-
with-payload-return is rejected at classify time (the row is gone before any follow-up
SELECT can read it; pre-delete state in the response is "the idea of being able to
return the film after it has been deleted is wrong").

Retired from R75 Phase 1's shipped attempt:

- `ChildField.PassthroughDataField` permit.
- `ChildField.IdentityPassthrough` capability sealed sub-interface.
  `FetcherEmitter.dataFetcherValue` reverts to the two pre-R75 `instanceof` arms
  (`ConstructorField`, `NestingField`).
- `BuildContext.resolveReturnType` short-circuit. Payload types resolve to
  `ResultReturnType` like any other record return.
- `FetcherRegistrationsEmitter.emit()` filter widening to `PlainObjectType`.
- `GraphitronSchemaValidator` switch-arm, `IMPLEMENTED_LEAVES` entry, and
  `TypeFetcherGenerator` no-op arm for `PassthroughDataField`.
- `NonTableParentCase.PASSTHROUGH_PAYLOAD_DATA_FIELD` fixture.
- `BuildContext.PASSTHROUGH_FORBIDDEN_DATA_FIELD_DIRECTIVES` set survives but moves
  inside the trigger function alongside the other classify-time conditions.
- The load-bearing classifier check `passthrough-payload.data-table-equals-dml-target`.
  The reshape doesn't carry separate "data table" vs "input table" state; the data
  field's `RecordTableField` filters by the input table's PK directly via
  `tableInputArg`, so there's no cross-pair invariant to enforce.

### Trigger function (survives as a classify-time helper)

`BuildContext.unwrapPassthroughPayload` (consider renaming for clarity,
e.g. `tryResolvePayloadShape`) is the same pure structural test as in the shipped
attempt, returning a sealed `PassthroughResolution` (`Ok` / `NotCandidate` /
`Rejected`). The four conditions match the implementation that shipped:

1. The type is registered as `PlainObjectType`, or `PojoResultType` with
   `fqClassName == null`.
2. The SDL Object declares exactly one field — the data field.
3. That single field's element type is registered as `TableBackedType`.
4. The data field carries no graphitron-domain directive (`@service` / `@sourceRow` /
   `@reference` / `@nodeId` / `@field` / `@asConnection` / `@splitQuery` /
   `@externalField` / `@condition` / `@lookupKey` / `@notGenerated` /
   `@tableMethod` / `@defaultOrder` / `@orderBy` / `@multitableReference`).
   Pure-metadata directives (`@deprecated`, user-defined non-graphitron directives) are
   off the list.

The trigger is consulted at exactly two sites: the mutation classifier (to admit the
payload return as a `Mutation<Kind>RecordField`) and the schema-builder's per-type pass
(to register the data field as `RecordTableField`). No multi-site re-derivation: the
resolution is consumed where it's produced, and the carrier types hold the resolved
structure downstream.

### Mutation-field classification

`FieldBuilder.classifyMutationField` admits payload returns as a sibling branch to the
existing direct-`@table` admission:

- The mutation has `@mutation(typeName: <KIND>)` and a registered `TableInputArg`.
- `ctx.resolveReturnType(rawReturn, wrapper)` returns `ResultReturnType` (the payload
  type, classified as `PojoResultType` with null `fqClassName`, or `PlainObjectType`
  promoted to one — see Open question 1 below).
- The classifier consults the trigger. On `Ok`, classify as `Mutation<Kind>RecordField`.
  For `kind == DELETE`, reject with `"@mutation(typeName: DELETE) returning a payload
  type is not supported; use ID or [ID!]"`. On `Rejected`, surface the reason.
  On `NotCandidate`, fall through to the existing `ResultReturnType`-arm rejection
  (the `@record`-with-`className` non-payload case is unchanged).

`MutationInputResolver.validateReturnType` keeps its existing dispatch shape; the
`ResultReturnType` arm tightens to substitute the trigger's `Rejected` reason when the
return type is a payload candidate that failed a per-condition check (mirrors the
shipped attempt's message threading).

### Data-field classification: `ChildField.RecordTableField`

The schema-builder's per-type pass classifies the data field as
`ChildField.RecordTableField` when the trigger returns `Ok`:

- `parentTypeName`: the payload type name.
- `name`: the data field's name.
- `returnType`: `TableBoundReturnType` carrying the data field's element type,
  resolved `TableRef`, and SDL `FieldWrapper` (single or list).
- `joinPath`: empty list — the data field has no FK chain to traverse; rows are
  filtered by PK directly.
- `filters` / `orderBy` / `pagination`: empty / null. Phase 1's trigger forbids
  arguments and directives on the data field.
- `batchKey`: a new `BatchKey.RecordParentBatchKey` arm marking "rooted, no
  DataLoader; the parent's source IS the key carrier". Carries the PK columns of the
  data field's table (from `TableRef.primaryKeyColumns()`). See Open question 2 below
  for the exact arm name and structure.

Because the mutation is invoked exactly once per request and its result is the singleton
parent of the payload's traversal, films is fetched once. The fetcher is a plain
`DataFetcher`, not a DataLoader-batched `BatchLoader`. The `BatchKey` arm signals this
to the emitter; existing `RecordTableField` emit logic for non-rooted parents stays on
the DataLoader path.

### Generator emit

The mutation's fetcher does the DML inside `dsl.transactionResult(tx -> ...)`, returning
`Result<RecordN<...>>` directly to graphql-java (wrapped in `DataFetcherResult` only if
needed for error-channel routing — see existing `MutationServiceRecordField` shape for
the wrap/unwrap convention):

```java
public static Result<Record1<Integer>> createFilmsPassthrough(DataFetchingEnvironment env) {
    DSLContext dsl = graphitronContext(env).getDslContext(env);
    List<Map<?, ?>> in = (List<Map<?, ?>>) env.getArgument("in");
    if (in.isEmpty()) return DSL.using(dsl.configuration()).newResult(Tables.FILM.FILM_ID);
    return dsl.transactionResult(tx -> DSL.using(tx)
        .insertInto(Tables.FILM, Tables.FILM.TITLE, Tables.FILM.LANGUAGE_ID)
        .valuesOfRows(in.stream().map(row -> DSL.row(...)).toList())
        .returningResult(Tables.FILM.FILM_ID)
        .fetch());
}
```

The transaction commits when `transactionResult` returns; the materialised result
outlives it. graphql-java treats it as the source for the payload's traversal.

The data field's fetcher is the rooted-parent `RecordTableField` emit:

```java
($T env) -> {
    Result<Record1<Integer>> source = (Result<Record1<Integer>>) env.getSource();
    if (source.isEmpty()) return source;
    DSLContext dsl = graphitronContext(env).getDslContext(env);
    return dsl.select(Film.$fields(env.getSelectionSet(), Tables.FILM, env))
        .from(Tables.FILM)
        .where(Tables.FILM.FILM_ID.in(source.getValues(Tables.FILM.FILM_ID)))
        .fetch();
}
```

Composite-PK tables use `RecordN` and a row-tuple `IN` predicate; the emit pattern
mirrors today's bulk-DELETE / lookup-VALUES emitters. Read errors during this SELECT
(or during nested `@table` fetchers traversing each Film) propagate as graphql-java
field errors — they cannot undo the DML, which committed when the mutation's fetcher
returned.

### Direct-`@table` mutation return: same two-step emit

`Mutation.createFilm: Film` (direct `@table` return) keeps its existing
`MutationInsertTableField` etc. classification but its emit changes to the two-step
shape: PK-only RETURNING inside `transactionResult`, then a follow-up SELECT inside the
same fetcher (returning a `Record` for graphql-java's traversal of Film's children).
DELETE-direct-return (`Mutation.deleteFilm: ID`) and `: ID` returns generally keep
single-statement emit — RETURNING the PK column directly satisfies the encoder, no
follow-up needed.

### What about R12's authored payload (`@record(record: {className: ...})`)?

Out of R75's scope. R12's `DmlReturnExpression.Payload` arm continues today's behaviour
(authored carrier, single-statement emit). The reshape's pattern (PK-only RETURNING +
SELECT + payload-class constructor walks the SELECT) is implementable on R12's emit too
and would land any future deprecation cleanly, but R12 is tracked elsewhere.

### Open questions (for the implementer to settle, with reviewer sign-off)

1. **Plain-Object payload classification.** Today, no-`@record` plain Objects classify
   as `PlainObjectType`. The reshape needs the payload type to flow through
   `ResultReturnType` so the existing dispatch admits it. Two options:
   - Promote no-`@record` plain Objects (when they pass the trigger) to
     `PojoResultType` with null `fqClassName` at type-classification time. Cleaner
     downstream — every payload candidate uses the same `ResultType` machinery — but
     widens the meaning of `PojoResultType`.
   - Keep `PlainObjectType` and admit it through `resolveReturnType` as
     `ResultReturnType` directly. Localised change but adds a special case at the
     `resolveReturnType` site (which the reshape was meant to remove).

2. **`BatchKey` arm for the rooted data field.** The data field's `RecordTableField`
   needs a `BatchKey.RecordParentBatchKey` arm that signals "no DataLoader; PK columns
   come from `env.getSource()` cast to `Result<Record_N_<...>>`". Likely a new arm —
   `BatchKey.RootRowKeyed` or similar — carrying the PK column list. The exact shape
   intersects with the `BatchKey` design conventions; flag for the implementer to
   propose when the field is wired.

3. **Carrier wrap.** The mutation's fetcher returns `Result<RecordN<...>>` directly (or
   wrapped in `DataFetcherResult` if the error channel needs it). Existing
   `MutationServiceRecordField` emit shape is the precedent. Phase 2's slot work may
   want a wrap; for Phase 1, settle for the simpler return shape and let Phase 2
   widen if needed.

### Tests

Pipeline-tier (`graphitron/src/test/`): rewrite `PassthroughPayloadPipelineTest`:

- Per-`DmlKind ∈ {INSERT, UPDATE, UPSERT}`:
  - Mutation field classifies as `Mutation<Kind>RecordField` for payload returns.
  - Data field classifies as `ChildField.RecordTableField` with the rooted `BatchKey`
    arm.
- DELETE-with-payload return rejects with the per-mismatch message.
- Trigger rejections (multi-field, scalar element, interface element, graphitron-
  domain directives on the data field, `@table`-typed payload,
  `@record`-with-`className`).
- `@deprecated` on the data field admits.
- Direct-`@table`-return tests (`MutationInsertTableField` etc.): emit shape now
  two-step. Pin via a structural assertion on the generated method body if pipeline-
  tier can reach it; otherwise rely on compilation + execution tier.
- `fetcherEmitter_revertedTwoArms`: structural pin that
  `FetcherEmitter.dataFetcherValue` has the two pre-R75 `instanceof` arms
  (`ConstructorField`, `NestingField`) — replaces the shipped attempt's
  `_dispatchArmCount` pin.

Compilation-tier (`graphitron-sakila-example`): SDL fixture from the shipped attempt
survives — `FilmPassthroughPayload` + the `*Passthrough` mutations — minus
`deleteFilmsPassthrough` which retires alongside the DELETE-with-payload rejection.
`mvn compile -pl :graphitron-sakila-example -Plocal-db` verifies the two-step emit
produces compile-correct DSL.

Execution-tier (`graphitron-sakila-example/src/test/java/no/sikt/graphitron/rewrite/test/querydb/`):
rewrite `PassthroughPayloadDmlTest`:

- INSERT / UPDATE / UPSERT round-trip: row written, PK returned by mutation's fetcher,
  follow-up SELECT projects the requested columns, response shape matches.
- *Headline correctness pin* — `dml_persists_when_followupSelect_throws`: a mutation
  whose DML succeeds but whose data-field traversal throws (synthetic error from a
  `@service`-wired nested `@table` field, or an injected exception in the SELECT
  path). Asserts the row exists in the DB after the response, *and* the response
  carries the GraphQL field error. This is the durability invariant the reshape
  exists to make true.
- Direct-`@table`-return mutations (`createFilm: Film` etc.): same round-trip check
  — confirms the existing carrier types still work under the two-step emit.

Audit-tier: no new load-bearing keys.

### Out of scope for Phase 1

- **Multi-field passthrough payloads.** Phase 2 territory.
- **`@service` mutations returning payload types.** Phase 3 territory.
- **`@record`-element data fields.** Phase 3 territory.
- **Restructuring R12's authored-carrier emit.** Tracked separately.
- **DELETE returning a payload of pre-deletion data.** Rejected at classify time; not
  coming back.

## Phase 2: multi-field payloads via localContext

Phase 1 admits single-data-field payloads. Phase 2 extends the trigger to multi-field
payloads where one field is the table-bound data field (per Phase 1) and the rest are
non-data slot fields. Slot fields render null until a per-slot populator wires up;
that work is its own roadmap item per slot family (errors, affected-row counts, etc.).

### Trigger extension

The trigger function's condition #2 changes from "exactly one field" to "exactly one
`@table`-element field (the data field) and zero or more non-`@table`-element fields
(slot fields)". Conditions #1, #3, and #4 (the directive guard, applied to the data
field only) carry over. Multi-`@table`-element payloads remain rejected — they form
the compound-mutation pattern tracked under R122.

The trigger result extends with a slot-descriptor list; Phase 1 callers continue to
see an empty list and behave identically.

```java
record SlotDescriptor(String name, String elementName, FieldWrapper wrapper) {}
```

Slot fields with `@record`-element types reject at trigger time (Phase 3 territory).

### Slot-field classification

Slot fields land on the same payload-type parent as the data field. A new
`ChildField.PassthroughSlotField` permit handles them; its fetcher reads from
`env.getLocalContext().get(name)`:

```java
if (field instanceof ChildField.PassthroughSlotField slot) {
    return CodeBlock.of(
        "($T env) -> env.getLocalContext() == null ? null "
            + ": (($T<$T,$T>) env.getLocalContext()).get($S)",
        DATA_FETCHING_ENV, MAP_CLASS, STRING_CLASS, OBJECT_CLASS, slot.name());
}
```

The null-guard accounts for the consumer-driven-population case where the localContext
map may not have been populated yet.

### Mutation carrier extension

The Phase 1 `Mutation<Kind>RecordField` permits gain a `slots` component:

```java
record MutationInsertRecordField(
    String parentTypeName,
    String name,
    SourceLocation location,
    ReturnTypeRef.ResultReturnType returnType,
    ArgumentRef.InputTypeArg.TableInputArg tableInputArg,
    Optional<ErrorChannel> errorChannel,
    List<SlotDescriptor> slots
) implements MutationField {}
```

When `slots` is non-empty, the mutation's fetcher wraps in
`DataFetcherResult.<...>newResult().data(rows).localContext(slotsMap).build()`. Phase 2
ships with `slotsMap = emptyMap()`; the populator framework lands per-slot-family in
its own roadmap item. When `slots` is empty (Phase 1 case), the fetcher returns
`Result<RecordN<...>>` directly. No `MutationFieldWithSlots` wrapper — the slots ride
on the carrier itself.

### Slot population

Phase 2 ships the wrap mechanism and the slot fetcher emit; it does *not* ship a
populator framework. Designing the populator API against zero consumers would be the
"premature framework" anti-pattern. The first real populator (errors is the canonical
candidate) lands in its own roadmap item; its emit needs inform the contract surface.

A SDL author who writes a payload with `errors: [Error!]` or any other non-data slot
sees the data field render correctly and the slot fields render as null until the
populator's roadmap item ships. Usable intermediate state.

### Out of scope for Phase 2

- Slot populators. Tracked separately per slot family.
- `@service` multi-field payloads (Phase 3 — the consumer constructs `DataFetcherResult`
  directly, so the `localContext` carriage is the consumer's responsibility).
- Slot fields with `@table` element type (R122 / compound mutations).
- Slot fields with `@record` element type (Phase 3).

### Tests

Pipeline-tier additions to `PassthroughPayloadPipelineTest` (parameterised over
`DmlKind ∈ {INSERT, UPDATE, UPSERT}`):

- Multi-field admission with scalar slots: data field classifies as `RecordTableField`
  (per Phase 1), slot fields classify as `PassthroughSlotField`, mutation carrier
  carries the slot list with declaration order preserved.
- Multi-`@table`-element rejection (compound-mutation territory).
- Slot field with `@record`-element rejection (Phase 3 territory).
- Mutation field's fetcher emits the `DataFetcherResult.localContext(emptyMap())` wrap
  when slots are present, returns `Result<RecordN<...>>` directly when not.

Execution-tier (sakila): a new fixture verifying multi-field admission compiles and
runs with slots rendering null. Per-`DmlKind` drivers assert the data field renders the
inserted/updated/upserted row and the slot fields render null. The execution tier
verifies the `DataFetcherResult.localContext(emptyMap())` wrap composes through
graphql-java's traversal correctly.

Audit-tier: no new load-bearing keys.

## Phase 3: @service mutations and @record-element data

Phases 1 and 2 cover `@mutation` (DML) payload returns with `@table`-element data
fields. Phase 3 extends along two orthogonal axes:

- **Consumer.** The trigger admits `@service` mutations whose return type is a payload.
  The `@service` consumer constructs the `DataFetcherResult` (or a record-shaped
  return) directly; graphitron registers the data-field fetcher and any slot fetchers
  on the payload, and the existing `@service` wrapper unwrap (`Optional<T>`,
  `CompletableFuture<T>`, `Mono<T>`) handles wrapper composition.
- **Element kind.** The data field's element type may be `@record`-mapped in addition
  to `@table`-mapped. Service methods may return a domain record (Java record or POJO
  bound via `@record(record: {className: ...})`); the data field's classification
  carries the `@record` shape and graphql-java's per-field fetchers on the `@record`
  element handle accessor reads.

### Trigger extension

Condition #3 changes from "the data field's element type is registered as
`TableBackedType`" to "registered as `TableBackedType` or `ResultType`". The other
conditions stay; multi-field admission and slot rejection from Phase 2 carry over.

The trigger result's data-element descriptor becomes a sealed sub-taxonomy:

```java
sealed interface DataElement {
    String name();
    FieldWrapper wrapper();
    record Table(String name, TableRef table, FieldWrapper wrapper) implements DataElement {}
    record Record(String name, String fqClassName, FieldWrapper wrapper) implements DataElement {}
}
```

Phase 1 / 2 callers see `DataElement.Table`. Phase 3 introduces the `Record` arm.

### Mutation classification

`@mutation` (DML) admits only `@table`-element data — Phase 1 / 2 unchanged.
`@record`-element data on a DML mutation is out of scope (would require a "DML row →
domain record" conversion step at the emitter; tracked separately).

`@service` mutations classify through the existing `MutationServiceRecordField` permit
when the SDL return is a payload type. The service method's declared return type is
matched against the data field's element kind:

- `DataElement.Table(name, table, wrapper)`: the inner type must be `Result<<TableRecord>>`
  (or a single `<TableRecord>` for non-list, or a `DataFetcherResult` wrapping either).
- `DataElement.Record(name, fqClassName, wrapper)`: the inner type must be the class
  named `fqClassName` (or a list / optional thereof matching `wrapper`, or a
  `DataFetcherResult` wrapping it).

Mismatches reject at classify time with a per-mismatch message naming the SDL data
element, the method's return type, and the gap. Validator-mirrors-classifier coverage:
every classifier rejection surfaces as a build-time error via the existing typed
`Resolved` shapes and the validator's `@service` arm.

### Data-field classification

For `@table`-element data on a `@service` mutation: same `ChildField.RecordTableField`
classification as Phase 1, with the rooted-`BatchKey` arm. The data field's fetcher
reads `env.getSource()` (the service method's `Result<RecordN<...>>` after wrapper
unwrap) and runs the response SELECT.

For `@record`-element data: the data field classifies under whichever existing model
shape covers "record parent → record child" cleanly. The mechanism is graphql-java's
per-field accessor traversal of the service method's returned domain record;
graphitron's emit is identity passthrough (the existing `ConstructorField` shape, which
remains in the model with its pre-R75 documentation). Phase 3 may extend that permit's
docstring to cover record-on-record use, or introduce a sibling permit if structural
clarity demands. Implementer call.

### Wrapper composition

Service methods may return `T`, `Optional<T>`, `CompletableFuture<T>`, `Mono<T>`. The
existing `@service` unwrap strips these wrappers before classifying the inner `T`.
`DataFetcherResult<T>` is treated as another wrapper layer: a method returning
`DataFetcherResult<T>` admits with the same trigger as one returning `T`. A method
returning bare `T` paired with a payload that has slot fields admits — the slots
render as null until the consumer wraps in `DataFetcherResult` and populates the
localContext map.

The matrix of wrapper × element kind (4 wrappers × 2 element kinds = 8 combinations)
is verified at the execution tier.

### Out of scope for Phase 3

- `@record`-element data on `@mutation` (DML). Tracked separately.
- Service-side slot populators (per-slot roadmap items per Phase 2).
- `@service` queries returning payload types. The trigger is consumer-agnostic so the
  classification works, but the per-query fetcher emit hasn't been audited end-to-end.
  Treat as a follow-up.

### Tests

Pipeline-tier additions to `PassthroughPayloadPipelineTest`:

- `@record`-element data field admits and registers correctly.
- `@service` mutation returning a payload admits at the service classifier; no
  `UnclassifiedField` at the mutation site. Parameterised over `{Table, Record}`.
- Service-method-return-type mismatch rejects with the per-mismatch message.
  Parameterised over `{Table, Record}`.
- Wrapper composition: service method's `Optional<T>` / `CompletableFuture<T>` /
  `Mono<T>` / `DataFetcherResult<T>` unwraps to the inner `T` for trigger matching.
  Parameterised over `{T, Optional, CompletableFuture, Mono, DataFetcherResult} ×
  {Table, Record}`.
- `@service` payload with both data and slot fields: method returning
  `DataFetcherResult<T>` admits; slot fetchers read from the consumer-populated
  localContext.

Execution-tier (`graphitron-sakila-example/src/test/java/no/sikt/graphitron/rewrite/test/querydb/`):
a new `PassthroughPayloadServiceTest` parameterising over `(elementKind, wrapper)`:

- SDL: two payload variants — `@table`-element via the existing sakila `Actor`,
  `@record`-element via an `ActorRecord` Java record bound through
  `@record(record: {className: "...sakila.ActorRecord"})`.
- Per-wrapper drivers (`T`, `Optional<T>`, `CompletableFuture<T>`, `Mono<T>`) for each
  element kind: invoke the service mutation against the testcontainer, assert response
  shape and follow-up reads. 8 cases.
- Additional driver per element kind exercising a multi-field payload over
  `DataFetcherResult<T>` to verify consumer-side slot population. 2 more cases.

Audit-tier: no new load-bearing keys.

Audit-tier: no new load-bearing keys in Phase 3.

## Non-goals

- **Synthesizing Java payload classes.** Explicitly rejected. No
  `<outputPackage>.synthesized` package, no `SynthesizedPayloadClassGenerator`, no
  per-payload-type Java emission.
- **Setter-based errors injection.** Violates immutability and would have graphitron
  mutate consumer-produced objects. Legacy's `payload.setErrors(...)` shape is not
  coming back.
- **DELETE returning a payload of pre-deletion data.** Rejected at classify time —
  the row is gone before the response SELECT can read it. DELETE returns `: ID` /
  `: [ID!]` only.
- **Multi-mutation atomicity inside a single GraphQL request.** Mutations are
  sequential per the GraphQL spec, and federation breaks the illusion anyway.
  Generated DML mutations commit in their own tight transactions; consumers don't
  get a "transactionally atomic multi-mutation" knob.
- **Restructuring R12's authored-carrier emit.** Tracked separately. R12's eventual
  deprecation is informed by R75's emit pattern (PK-only RETURNING + follow-up
  SELECT), but R75 doesn't touch R12's classification or carrier shape.

## Success criteria

**Phase 1:**

- The reproduction case at the top compiles and serves correctly through
  `mvn -f graphitron-rewrite/pom.xml install -Plocal-db`, with no
  `@record(className)` declaration on the SDL payload type and no Java class on disk
  for the payload.
- `PassthroughPayloadPipelineTest`'s admission cases pass for INSERT / UPDATE /
  UPSERT; DELETE-with-payload rejects with the per-mismatch message.
- `PassthroughPayloadDmlTest`'s execution-tier driver passes against the sakila
  testcontainer for INSERT / UPDATE / UPSERT, verifying the two-step emit (DML in
  transaction + follow-up SELECT) compiles and runs end-to-end.
- The headline correctness pin (`dml_persists_when_followupSelect_throws`) passes:
  the DML row persists in the DB even when the response-side SELECT or a nested
  `@table` fetcher throws.
- Phase 1's shipped attempt retires cleanly: `PassthroughDataField`,
  `IdentityPassthrough`, the `resolveReturnType` short-circuit, the
  `FetcherRegistrationsEmitter` filter widening, the
  `passthrough-payload.data-table-equals-dml-target` load-bearing key, and the
  `PASSTHROUGH_PAYLOAD_DATA_FIELD` fixture are all gone.
- Direct-`@table`-return DML mutations (`createFilm: Film` etc.) continue to work
  under the new two-step emit pattern; existing fixtures regress without changes.
- Authored payloads with `@record(record: {className: ...})` are unaffected (R12
  scope unchanged).

**Phase 2:**

- `PassthroughPayloadPipelineTest`'s Phase 2 admission cases pass for INSERT /
  UPDATE / UPSERT.
- The classifier produces the per-kind `Mutation*RecordField` with non-empty
  `slots` when the trigger admits multi-field; Phase 1 single-field payloads
  continue to classify with empty `slots` (regression pin).
- The execution-tier multi-field fixture passes for the three DML kinds with slot
  fields rendering null (no populator wired in Phase 2; the
  `DataFetcherResult.localContext(emptyMap())` wrap composes through graphql-java).
- No populator framework or catalog ships; the populator contract surface lands per
  slot family in its own roadmap item.

**Phase 3:**

- `PassthroughPayloadPipelineTest`'s Phase 3 admission cases pass for both element
  kinds (`{Table, Record}`) and all wrapper kinds (`{T, Optional, CompletableFuture,
  Mono, DataFetcherResult}`).
- `PassthroughPayloadServiceTest`'s execution-tier matrix passes (8 driver cases
  over 2 element kinds × 4 wrappers + 2 multi-field variants over
  `DataFetcherResult`).
- `ServiceCatalog`'s `@service` mutation classifier admits payload-typed returns;
  the trigger's `DataElement.{Table | Record}` sealed sub-taxonomy distinguishes
  the two element kinds.
- Authored payloads with `@record(record: {className: ...})` remain unaffected
  across all three phases (regression pin via the existing authored-carrier
  fixtures).
