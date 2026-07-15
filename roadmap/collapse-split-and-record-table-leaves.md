---
id: R432
title: "Collapse SplitTableField and RecordTableField into one source-gated leaf"
status: In Review
bucket: structural
priority: 4
theme: classification-model
depends-on: []
created: 2026-07-04
last-updated: 2026-07-15
---

# Collapse SplitTableField and RecordTableField into one source-gated leaf

The R333 beachhead: collapse `ChildField.SplitTableField` and `ChildField.RecordTableField` (and
their lookup twins, see the decided fork below) into one leaf gated on the source fact. The re-query
unification fork was settled 2026-07-04 (R333 open questions): **full merge, laundered key**. The
keyed re-query is one primitive `f(keys, correlation)`; the source endpoint contributes only how the
key tuple is lifted, never visible to the query unit. R431 (`decompose-sourcekey`, Done) built the
type this item needs: the lift is now the sealed `KeyLift` fact and the key residue is
`SourceKey(columns, wrap)`. This item spends that type: one leaf whose record-sourced arm is the
reentry case, not two leaves sharing machinery. Retires one cross-product axis and produces the
lowering's first executable proof (R333 "First slice"); feeds R314.

## The measured baseline (census 2026-07-15, post-R431)

The full consumer census (every construction and narrowing site in `src/main` across all modules)
was taken this session; the load-bearing findings:

- **Stored difference is exactly one component.** `RecordTableField` = `SplitTableField` +
  `KeyLift lift` (same for the lookup twins, which each add `lookupMapping` + the sealed
  `LookupField` interface). Everything else on the records is shared: `returnType`, `joinPath`,
  `filters`, `orderBy`, `pagination`, `sourceKey`, `loaderRegistration`, `parentCorrelation`,
  and the `BatchKeyField` capability.
- **Derived-method difference collapses.** `sourceShape()`: Split arms answer `Table`, Record arms
  `Record` (the leaf-identity projection at `ChildField.sourceShape()`). `emitsSingleRecordPerKey()`:
  Record adds the `|| dispatch == LOAD_MANY` disjunct; `LoaderRegistration.Dispatch.LOAD_MANY` is
  reachable only from the accessor-many arm (a record-parent shape; `deriveSplitQuerySource` always
  mints `LOAD_ONE`), so the unified formula `!wrapper().isList() || dispatch == LOAD_MANY` is
  behavior-identical for the split arm. That reachability claim graduates from prose to structure
  as ctor invariant 6 below.
- **The rows-method bodies are already unified.** `SplitRowsMethodEmitter`'s four public entry
  points (`buildForSplitTable` / `buildForSplitLookupTable` / `buildForRecordTable` /
  `buildForRecordLookupTable`) are thin forks delegating to shared `buildSingleMethod` /
  `buildListMethod` / `buildConnectionMethod`; the Connection arm is split-only, the lookup arms
  add the `@lookupKey` VALUES join, and the body comment on the record-lookup entry already says
  "identical to SplitLookupTableField's".
- **The real fork is the mint pair and the fetcher pair.** Mints: `deriveSplitQuerySource` (entry
  columns off the step-0 parent correlation, `Wrap.Row`, no lift) vs `resolveRecordParentSource` /
  the `@sourceRow` resolver / `buildPayloadCarrierRecordTableField` (a first-class `KeyLift` +
  agreeing `SourceKey`). Fetchers: `buildSplitQueryDataFetcher` (key via
  `GeneratorUtils.buildKeyExtraction`, jOOQ-row read) vs `buildRecordBasedDataFetcher` (key via
  `buildRecordParentKeyExtraction` consuming the lift, plus the Outcome-narrowing prelude and the
  null-source short-circuit).
- **The key-extraction emits already agree where the arms meet.** `buildKeyExtraction`'s
  `Wrap.Row` arm and `buildFkRowKey` (the `KeyLift.FkColumns` arm) emit the same
  `DSL.row(((Record) env.getSource()).get(Tables.T.COL), ...)` shape for a jOOQ-row holder, so
  giving the split arm an explicit `FkColumns` lift tells no lie: the lift arm names the
  mechanism (project columns off the held jOOQ record), not the provenance of the columns.
- **Behavioral gates that differ by leaf identity** (the migration walk below): `CatalogBuilder`
  projects `TableTarget(splitBatched=true)` vs `RecordTableTarget`;
  `TypeClassGenerator.collectRequiredProjection` force-includes Split key columns via the
  `BatchKeyField` arm but hard-throws on the Record leaves (the R425 guard);
  `GraphitronSchemaValidator` has a split-only Connection-requires-ORDER-BY guard, a
  `NESTED_WIREABLE_LEAVES` set containing only the Split variants, and a
  `LOCAL_CONTEXT_GUARDED_DATA_CHANNEL_VARIANTS` set containing only `RecordTableField`.

## The merged leaf

Two new leaves replace the four (pairwise): `BatchedTableField` replaces `SplitTableField` +
`RecordTableField`; `BatchedLookupTableField` replaces `SplitLookupTableField` +
`RecordLookupTableField`. Components, in the shared order:

```
parentTypeName, name, location, returnType, joinPath, filters, orderBy, pagination,
sourceShape (SourceShape),          // the source gate: Table | Record
sourceKey (SourceKey),
lift (KeyLift),                     // now total: the split arm carries FkColumns
loaderRegistration, [lookupMapping,] parentCorrelation
```

- **The gate is the stored `SourceShape`.** It cannot derive from the lift: `KeyLift.FkColumns` is
  legitimately carried by both a table-row parent (the split arm) and a jOOQ-record-backed result
  parent (`deriveFkRecordParentSource`), so the parent-backing fact must be stored. `sourceShape()`
  stops being a leaf-identity switch arm for these leaves and reads the component;
  `SourceShapeProjectionTest`'s cross-check against the independently-classified parent backing
  keeps holding and gets stronger (a stored fact checked against a walk, not a tautology of leaf
  identity).
- **The lift becomes total.** `deriveSplitQuerySource` mints `new KeyLift.FkColumns()` alongside
  its `SourceKey(entryColumns, Wrap.Row)`; `KeyLift.checkResidueAgreement` already accepts that
  pair (FkColumns derives `Row`). The record mints are unchanged. `FkColumns`' javadoc generalizes
  from "class-backed parent" to "the key tuple is projected off the held jOOQ record by column".
- **`emitsSingleRecordPerKey()`** is the unified formula (`!isList || LOAD_MANY`), one definition.
- **`operation()` / `target()`** arms were already identical pairwise; they merge mechanically.

Constructor invariants (compact ctor):

1. `requireNonNull(lift)` + `KeyLift.checkResidueAgreement(lift, sourceKey, name)` (both arms; new
   for the split arm, vacuously satisfied by its `FkColumns`/`Row` mint).
2. `ParentCorrelation.checkCarrierInvariant` (unchanged).
3. New: `sourceShape == Table` implies `lift instanceof KeyLift.FkColumns` (a table row is lifted
   only by column projection; the member-read lifts are class-backed-parent mechanisms).
4. New: `sourceShape == Record` implies the wrapper is not `Connection` (no record-parent
   Connection mint exists today; the split-only Connection emit arm and its ORDER-BY validator
   guard stay reachable only from the Table arm, now by construction instead of by leaf identity).
5. The R435 routine-surface pins and the `OnLateralArgs`-implies-nonempty-key guard (today on
   `SplitTableField` only) gate on `sourceShape == Table`, preserving current behavior; whether
   they should hold universally is an in-flight refinement question, not assumed (widening to
   Record would relax nothing that exists but would add unaudited checks).
6. New: `sourceShape == Table` implies `loaderRegistration.dispatch() == LOAD_ONE`. This is what
   makes the unified `emitsSingleRecordPerKey()` formula structurally behavior-identical for the
   split arm rather than an argument resting on unenforced prose ("`deriveSplitQuerySource` always
   mints `LOAD_ONE`" becomes a ctor throw, so a future mint cannot silently flip a split field's
   per-key cardinality).

Two costs of this shape, acknowledged rather than implicit (principles consult 2026-07-15):

- **Invariant 3 is checked, not structural.** In today's `SplitTableField` "a table-sourced field
  with a member-read lift" is unrepresentable (no lift slot exists); on the merged leaf it is a
  ctor throw. The sealed alternative (a gate `{ Table | Record(KeyLift) }` carrying the lift only
  on the arm that varies it) would keep it unrepresentable, but it would mint a second
  representation of the source-shape axis alongside the existing `SourceShape` enum that
  `Source.OnlyChild` / `Source.Child` already consume, which the one-model discipline weighs
  heavier. The sealed gate is the documented escape hatch if the source axis ever needs payload
  beyond the lift.
- **The Table arm's lift is inert at emit.** `buildKeyExtraction` (the Table path) is wrap-driven;
  the split arm's `FkColumns` is consumed only by its own `checkResidueAgreement` ctor call. That
  is deliberate forward-provisioning for R314's unified fetcher (the fact is stored
  consistent-by-construction, its consumption deferred), not dead weight and not something this
  item wires into the emit.

**Rename is load-bearing, not cosmetic.** Keeping the `SplitTableField` name would let existing
narrowing sites keep compiling while silently starting to receive record-sourced instances. Two
census sites make that concretely dangerous: `collectRequiredProjection`'s `BatchKeyField` arm
would force-project record-held key columns into the parent SELECT (the exact R425 bug family the
current hard-throw guards against), and `NESTED_WIREABLE_LEAVES` would silently admit record
leaves to nested wiring. A fresh name forces every switch arm, `instanceof`, and set membership
through the compiler, the same forced-revisit discipline R431 used by deleting components.

## The parent-projection containment check

The merge adds the named integrity invariant from R333 "Query anchors and the two flows": **when a
child's key tuple is lifted off the parent's held object, the parent anchor's projection must
contain the key columns.** R425 is the shipped bug that shows what its absence costs; the level-1
closure oracle (method-name resolution) does not cover it. Naming the invariant without a check is
the false-invariant family the design principles warn about, so this item ships the check:

- **Shape:** a generation-time cross-check at the `$fields` emit site. The requirement side
  enumerates every Table-sourced `BatchKeyField` coordinate's demanded `sourceKey().columns()`;
  the guarantee side is the `RequiredProjection` the emit walk computed. A demanded column absent
  from the projection set throws `IllegalStateException` at generation time, naming field, column,
  and both walks. Divergence means a walk omission (R425's root cause was exactly a pattern-match
  omission in this walk), caught loudly at build time instead of as a silent null at runtime.
- **Independence is the hard requirement, not a preference.** The requirement side enumerates
  coordinates from the classifier's flat field list (every coordinate its own entry, rooted to
  its anchor by parent-chain), and must not call `collectRequiredProjection` or borrow its
  `NestingField` recursion or `fieldsOf` locality: R425's omission was *inside* that walk, and a
  requirement side sharing its recursion would reproduce the omission on both sides and pass
  green over the exact bug family the check exists to catch.
- **Tier:** the check function is unit-tested directly, and the fire-case must be a
  nesting/recursion omission (the R425 shape), not a bare set mismatch, so an implementation that
  shares the audited walk's recursion fails the test. The satisfied case passes across the corpus;
  the R425/R426 pipeline suites keep pinning the satisfied end-to-end behavior. Generation-time
  is the right home because `RequiredProjection` is a generator artifact that validate-time
  cannot see.
- **Keyed on the capability, not leaf identity:** the check reads `BatchKeyField` +
  `sourceShape()`, both of which survive the merge, which is why it can land first (slice 1) and
  guard slices 2-3 without churn.
- **Record-sourced leaves stay out:** their key rides the held object, not the parent SELECT; the
  existing hard-throw tripwire (record demands must never reach the table-parent projection walk)
  is preserved, re-gated on `sourceShape == Record` instead of leaf identity.

## Migration walk (from the census; every site compiler-forced by the rename)

- `ChildField.java`: `TableTargetField` permits (8 to 6), `sourceShape()` / `operation()` /
  `target()` arms merge, the two records delete.
- `FieldBuilder`: 4 Split construction sites gain `sourceShape=Table, lift=FkColumns`; 6 Record
  sites gain `sourceShape=Record`; `deriveSplitQuerySource` returns the lift alongside.
- `SplitRowsMethodEmitter`: 4 entry points to 2 (fork inside on wrapper / `emitsSingleRecordPerKey`
  / `lookupMapping`, arms unchanged); `RowsMethodBody` permits collapse pairwise.
- `TypeFetcherGenerator`: the leaf dispatch merges; the two fetcher builders stay as the two arms
  of one seam gated on `sourceShape` (key extraction: Table via `buildKeyExtraction`, Record via
  `buildRecordParentKeyExtraction`; Outcome prelude and null-source short-circuit stay
  Record-gated). The four-way dispatch partition (`IMPLEMENTED_LEAVES` / `PROJECTED_LEAVES` /
  `NOT_DISPATCHED_LEAVES` / `STUBBED_VARIANTS`, pinned exhaustive-and-disjoint by
  `GeneratorCoverageTest`) updates: the merged leaves land in `IMPLEMENTED_LEAVES`, the four
  retired class literals leave. `hasListSplitField` / `hasConnectionSplitField` /
  `hasSplitLookupField` / `emitsRowKeyedParentInputRowsMethod` / `bkfFieldName` re-gate on the
  merged leaf + facts.
- `CatalogBuilder`: `projectFieldClassification` gates on `sourceShape` to keep emitting the
  identical `TableTarget(splitBatched=true, ...)` vs `RecordTableTarget(...)` classifications
  (LSP/MCP projections and the `@classified` corpus unchanged); `projectPayloadDataFields`'
  `RecordTableField`-with-`ProducedRecords` probe becomes merged-leaf-with-`ProducedRecords`.
- `TypeClassGenerator.collectRequiredProjection`: the `BatchKeyField` arm keeps contributing
  Table-sourced columns; the record tripwire's predicate becomes
  `BatchKeyField && sourceShape() == Record`, which intentionally consolidates
  `RecordTableMethodField` (already `Record`-shaped) into the same fact-gate as the merged
  leaves, a leaf-list to fact-predicate strengthening, not accidental widening; the containment
  check lands here.
- `GraphitronSchemaValidator`: the two validate methods merge (shared path + cardinality checks;
  Connection-ORDER-BY guard now reaches only Table-sourced by ctor invariant 4);
  `NESTED_WIREABLE_LEAVES` becomes a Table-sourced predicate; `LOCAL_CONTEXT_GUARDED` becomes a
  Record-sourced predicate on the non-lookup leaf (the lookup twin is absent from that set today;
  preserve the asymmetry, do not silently widen).
- `CompileDependencyGraphBuilder` no-op arms, `LookupValuesJoinEmitter.fieldName`,
  `LookupField` sealed permits (4 to 3), `BatchKeyField` javadoc implementer list (7 to 5),
  `FieldClassification` javadoc, `directives.graphqls` comment: mechanical.
- Tests: the four per-leaf validation test classes merge pairwise; `ClassifiedCorpus`,
  `StubbedVariantPipelineTest`, `ReFetchDerivationTest`, `SplitTableFieldPipelineTest`, fetcher /
  pipeline suites and the LSP `DiagnosticsTest` re-name; `SourceShapeProjectionTest` unchanged in
  intent.

## Reviewer notes (Spec → Ready sign-off, 2026-07-15)

Signed off as sound; two refinements to fold into the implementation, neither a design change:

- **Containment-check surface (slice 1).** The check throws `IllegalStateException` at generation
  time. Make explicit that this is a *generator invariant*, not an author-facing rejection: a
  divergence is always a walk omission (a Graphitron bug), never reachable by a valid author schema
  with a correct generator, so `IllegalStateException` is deliberate and the "Rejections: validator
  mirrors classifier" rule does not call for a typed rejection here. State that discriminator at the
  throw site so a future maintainer does not mistake it for an author error and re-home it.
- **`KeyLift` axis coherence (slice 4).** Making `lift` total broadens the whole `KeyLift` axis, not
  just `FkColumns`' javadoc: `dispatch-axes.adoc` today states a table-parent `@splitQuery` field
  has *no* `KeyLift`. The slice-4 doc sweep must reframe the axis itself, from "record-parent lift
  provenance" to "how the key tuple is lifted off the held jOOQ record," so the generalized axis
  reads as one honest axis (`FkColumns`-on-a-table-row a genuine member, not a vacuous value that
  dilutes it). Lead the storage rationale with "total `lift` removes an absence case and tells no
  lie" (durable); R314 forward-provisioning is the bonus, not the load-bearing reason.

## Slices

All four slices shipped (2026-07-15), each full-reactor green and pushed to trunk:

1. **Containment check** — shipped at `3b873c3`. `ParentProjectionContainmentCheck` at the
   `$fields` emit site, keyed on `BatchKeyField` + `sourceShape()` (never leaf identity; slices
   2-3 did not touch it); unit fire-case is the nesting-omission shape. The reviewer note landed:
   the `IllegalStateException` message and class javadoc name it a generator invariant, not an
   author-facing rejection. One implementation-level deviation from the spec text: nested
   plain-object coordinates are *not* in the classifier's flat field index (they resolve through
   the embedding `NestingField`), so the requirement side enumerates the anchor's own flat-index
   coordinates and descends nesting sub-trees with its own worklist — independent code, not the
   audited walk's recursion. The R436 `TableRecord`-wrap fire-case lives on `ServiceTableField`,
   where that wrap is authored (the merged leaf's Table arm pins `FkColumns`/`Row` and can never
   carry it).
2. **Non-lookup merge** into `BatchedTableField` — shipped at `9253bca`. All six ctor
   invariants, full migration walk, tests merged/renamed. Generated output across the sakila
   corpus was **byte-identical** to the pre-merge baseline (`diff -r` empty), so execution-tier
   equivalence and fact-level classification stability hold trivially.
3. **Lookup merge** into `BatchedLookupTableField` — shipped at `f6ebfaf`. Invariants 1/2/3/6;
   invariant 4 (Record ⟹ non-Connection) deliberately absent on the lookup leaf — a
   Connection-shaped lookup is an author-reachable schema the validator rejects on both arms,
   not an unrepresentable generator state. Byte-identical output again.
4. **Docs + spec-notes sweep** — this commit. `dispatch-axes.adoc` reframes the whole `KeyLift`
   axis (per the reviewer note: "how the key tuple is lifted off the held jOOQ record", total
   lift leading with "removes an absence case and tells no lie", R314 provisioning as the
   bonus); `code-generation-triggers.adoc` tables; `KeyLift` / `FkColumns` / `BatchKeyField`
   javadoc; `supported-schema-shapes.adoc` + `inference-axis-coverage.adoc` regenerated from
   fresh traces; R333's leaf mentions get a shipped-note.

## Decided forks

- **The lookup twin rides along** (slice 3) rather than filing separately: the census shows its
  divergence is a strict subset of the non-lookup pair's (same mint/fetcher fork, no Connection
  arm on either side), and leaving it split would keep the `LookupField` seal naming both halves
  of a dissolved axis.
- **Fresh names** (`BatchedTableField` / `BatchedLookupTableField`), for the forced-revisit reason
  above. "Batched" names what distinguishes the pair from inline `TableField` / `LookupTableField`:
  the field launches its own keyed, DataLoader-batched re-query anchor (the `BatchKeyField`
  capability sense of "batched"; distinct from the arrival-cardinality sense in which
  `Source.Child` fetchers are "DataLoader-batched", which applies to arrival, not to this leaf
  axis). Naming is reviewable at Spec review; the rename-not-reuse decision itself is
  load-bearing.
- **The split arm's lift is `FkColumns`**, not a fifth `KeyLift` arm: the R333 resolution names
  exactly two lift mechanisms (project columns off a jOOQ record / read members off a Java
  object), and `FkColumns` is the former; its javadoc generalizes rather than a new arm
  duplicating it.
- **The fetcher fork survives inside one seam.** This item re-gates the existing emit blocks on
  stored facts; it does not unify `buildSplitQueryDataFetcher` / `buildRecordBasedDataFetcher`
  into one rendering. That is R314's re-platforming; doing it here would turn the beachhead into
  the invasion.

## Acceptance

Execution-tier equivalence (same rows, same order, error paths intact) plus **fact-level
classification stability**: the rename necessarily changes every corpus assertion that names a
leaf class, so "classifying unchanged" is precise only at the fact level. The crisp statement:
every coordinate's `(sourceShape, operation, target)` verdict and its projected
`FieldClassification` (`TableTarget(splitBatched=true, ...)` vs `RecordTableTarget(...)`) are
identical before and after; the `@classified` corpus diff is renames-only, and any verdict delta
found during the corpus rename is a red flag surfaced in review, not silently absorbed.
Byte-for-byte generated-output equality is explicitly not required (R333 run-up rule), though
slices should note where output did stay byte-identical as evidence. Full reactor green per
slice. The containment check is load-bearing acceptance: it must be able to fail (unit-proven,
recursion-omission fire-case) and must pass across the corpus.

## Relationships

- **R333** (the model): this is its "First slice" beachhead; the merged leaf is the executable
  proof of the re-query unification resolution (2026-07-04, "full merge, laundered key").
- **R431** (decompose-sourcekey, Done): the dependency; `KeyLift` / `SourceKey(columns, wrap)` /
  `checkResidueAgreement` are the types this merge is expressed in.
- **R314** (reentry re-platforming): downstream consumer; retires `dispatchPerformsReFetch` and
  unifies the fetcher rendering this item deliberately leaves forked-behind-one-seam.
- **R425 / R426 / R436** (the parent-projection bug family): the containment check is the
  facts-level invariant whose absence produced them; their pipeline/execution suites are the
  regression net this item must keep green.
