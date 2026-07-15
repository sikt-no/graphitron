---
id: R431
title: "Decompose SourceKey onto the model's facts"
status: In Progress
bucket: structural
priority: 4
theme: classification-model
depends-on: []
created: 2026-07-04
last-updated: 2026-07-15
---

# Decompose SourceKey onto the model's facts

`SourceKey` is `(target, columns, path, wrap, cardinality, reader)` and bundles three separable
concerns, only one of which is a source key (R222 "What SourceKey decomposes into"; R333 sharpens the
destinations). This item is the eager, mechanical decomposition, sequenced ahead of the reentry emit
re-platforming (R314) so the emit slices land on decomposed facts instead of extending the conflated
record. R432 (`collapse-split-and-record-table-leaves`, the R333 beachhead) depends on this item;
R314's spec states its post-condition plainly: "Post-R431 this lives on the decomposed
source-object / locator facts, not on `SourceKey.Reader`."

Since the Backlog body was filed, the surface kept growing in its conflated form, as predicted:
R425 (force-include key columns) landed the `BatchKeyField` capability arm returning
`sourceKey().columns()`, R426 gated full-row projection on `SourceKey.Wrap.TableRecord`, and R436
rebuilt the `TableRecord` key extraction column-by-column. All three shipped; their emit sites are
now consumers this item migrates. R461 (Done) unified accessor resolution behind
`ClassAccessorResolver`, which is the locator machinery the decomposed read side reuses, and R463
(Done) populated the `Source.OnlyChild` / `Source.Child` arrival arms the source-object fact
composes with.

## Consumer census (surveyed 2026-07-14)

The decomposition is grounded in who actually reads each component. Symbols, not line numbers;
re-grep at pickup.

- **`target`** — four readers: `GeneratorUtils.buildAccessorKeySingle` / `buildAccessorKeyMany`
  (the table whose PK columns the accessor's returned record projects into),
  `GeneratorUtils.buildRecordParentKeyExtraction`'s `ProducedRecordRead`-ONE arm (via
  `buildKeyExtractionWithNullCheck`), `FetcherEmitter.buildSingleRecordIdFetcherValue` (node-key
  column reads), and `CatalogBuilder`'s null-tolerant LSP projection. Every one sits on a carrier
  that also exposes `returnType.table()` for the same table.
- **`path`** — **zero readers**, confirmed by symbol and destructuring search. Emitters traverse the
  `joinPath` component carried first-class on the `ChildField` records; `SourceKey.path()` exists
  only to feed the compact-constructor invariants and to smuggle the lifter/accessor arms' single
  `JoinStep.LiftedHop` into the model (consumed via `ParentCorrelation.OnFkSlots`, not via
  `SourceKey.path()`).
- **`columns`** — load-bearing, read widely: the `DSL.row(...)` / `into(...)` key cells
  (`GeneratorUtils`), the parent-input VALUES cells (`SplitRowsMethodEmitter.parentKeyCells`), the
  forced parent projection (`TypeClassGenerator.collectRequiredProjection`,
  `GraphitronSchemaValidator.collectBaseNamedKeyColumns`), the node-key encode
  (`FetcherEmitter.buildSingleRecordIdFetcherValue`), and `keyElementType` derivation.
- **`wrap`** — load-bearing: three full `Row | Record | TableRecord` switches
  (`GeneratorUtils.buildKeyExtraction`, `SplitRowsMethodEmitter.parentKeyCells`,
  `MultiTablePolymorphicEmitter`'s parent-key cells) plus narrow gates
  (`TypeClassGenerator.collectRequiredProjection` on `TableRecord`,
  `TypeFetcherGenerator`'s `instanceof Wrap.Row` fetcher-shape tests,
  `ServiceDirectiveResolver`'s `TableRecord` set handling).
- **`cardinality`** — two readers fork single-vs-many: `buildRecordParentKeyExtraction`'s
  `AccessorCall` and `ProducedRecordRead` arms, and `buildSingleRecordIdFetcherValue`'s list-vs-single
  ID encode. Constructed from `wrapper().isList()` at every service/produced site and from the
  accessor's return arity at accessor sites; `LoaderRegistration` records the same arity again as
  container/dispatch.
- **`reader`** — exactly **one exhaustive seven-arm switch**:
  `GeneratorUtils.buildRecordParentKeyExtraction`, in which the three producer-side arms
  (`ServiceTableRecord`, `ServiceUntypedRecord`, `ResultRowWalk`) throw as unreachable. All other
  reads are single-arm `instanceof` (`MultiTablePolymorphicEmitter` on `AccessorCall`,
  `CatalogBuilder` on `ProducedRecordRead`, `ChildField.SingleRecordIdField`'s ctor plus
  `FetcherEmitter`'s cast on `ResultRowWalk`).
- **`Reader.SourceEnvelope`** — read at `FetcherEmitter.buildSingleRecordIdFetcherValue` (off the
  `ResultRowWalk` cast) and `buildRecordCompositeFetcherValue`, the latter off
  `ChildField.RecordCompositeField.envelope()`, an already-denormalized copy on a leaf that holds no
  `SourceKey` at all.
- **Construction** — eleven sites, all classifier-side (`FieldBuilder`,
  `SourceRowDirectiveResolver`); no generator constructs one.
- **Partial carriers** — `MethodRef.Param.Sourced`, `ParamSource.Sources`, and
  `ServiceCatalog.SourcesShape` hold only the `(wrap, columns[, container])` shape pair and use the
  static `SourceKey.keyElementType(wrap, columns)` overload; they never materialize a full key.
  `InterfaceField` / `UnionField` carry `parentSourceKey`, `SourceKey` bent to describe the source
  *object* (R222 names this misfit; `cardinality` hardcoded `ONE`).

## Destinations, already settled in R333 (2026-07-04 design session)

- **`target` / `path` leave by deletion.** They are denormalized copies of `returnType.table()` /
  `joinPath` already carried first-class on the leaves; the census above confirms `path()` has zero
  generator readers and every `target()` reader sits next to the same table fact.
- **`wrap` / `reader` / backing dissolve into the read-side facts.** The source object's shape is a
  type-level fact (jOOQ record vs Java object); the key lift is N reads through the same field-level
  locator family the ordinary read side uses (post-R461, `ClassAccessorResolver`'s one candidate
  model); `@sourceRow`'s lifter is *provenance* on the member-read arm (authored where the catalog
  cannot infer the mapping), not a third mechanism. Nothing key-specific survives in `Reader`'s
  seven arms, which conflate shape, provenance, and envelope. The two service arms
  (`ServiceTableRecord` / `ServiceUntypedRecord`) additionally duplicate the producer's declared
  return shape, a `MethodRef` signature fact; the census shows they are dispatch-dead in the one
  exhaustive switch.
- **The envelope names its destination too.** `SourceEnvelope` (`DIRECT` vs `OUTCOME_SUCCESS`,
  carried on `Reader.ResultRowWalk`) is neither shape nor provenance: it is a read-site fact about
  whether `env.getSource()` is the row directly or an `Outcome.Success` wrapper. It coalesces onto
  the type-level source-object / error-channel fact (`Reader.ProducedRecordRead`'s javadoc already
  hoists it as `sourceIsOutcome`, and `RecordCompositeField.envelope()` is a second denormalized
  copy this fold retires; R333's `errorGuard.channel` is the natural home), never onto the key
  residue. Leaving it unrouted would push an implementer to keep a `Reader` remnant alive for it,
  reintroducing the conflation this item removes.
- **The residue that stays**: `columns` (the key tuple) and the source-field arity, which is a
  wrapper position of the wrapper algebra, not a free `Cardinality` enum. The residue earns the
  name: the key extracted from the source field, nothing about where it points or what shape its
  parent arrived in. The `(wrap, columns)` pair the partial carriers hold is the natural shape seam;
  whether the emitted DataLoader key-row shape (`RowN` / `RecordN` / typed record) stays a stored
  component of the residue or becomes a derivation from lift arm plus producer signature is the
  implementer's call, made under the byte-identical acceptance below (the service-declared shape is
  already a `Param.Sourced` signature fact; the non-service arms pin their wrap by construction).
- **`parentSourceKey` routes to the source-object side.** The `InterfaceField` / `UnionField`
  parent-identity extraction is source-object description, not a field key. Re-typing those two
  components onto the decomposed facts is **in scope and required**: the item's endpoint is the
  `SourceKey` record deleted, and a surviving `parentSourceKey` would keep it alive bent (R432 and
  R314 both pin on the post-condition "this lives on the decomposed facts, not on
  `SourceKey.Reader`"). What is separable, if the polymorphic work balloons, is emit-side
  refinement of `MultiTablePolymorphicEmitter` beyond the mechanical re-typing, not the record
  migration itself.

**Transition technique: additive-then-cutover** (R222's technique, restated by
`workflow.adoc` "Structural pivots land additive-then-cutover" because four items pin on this
type). Introduce the decomposed facts alongside `SourceKey`, dual-source, migrate consumers arm by
arm behind the compiler, then delete the record; the acceptance holds at every intermediate commit,
not just the endpoint. Not a single atomic edit — `SourceKey` is too widely pinned for big-bang in
a trunk-based, concurrently-edited repo.

## Implementation

Flat slices; each lands green on the full reactor and ships to trunk independently. Ordering
within the technique is the implementer's judgment, but the LiftedHop re-typing gates the
R438-cleanup placement, and deleting `target` / `path` first shrinks the record every later slice
touches.

- **Delete the denormalized copies.** *`target` shipped at `bab6f35` (slice 1): the four readers
  migrated (`buildRecordParentKeyExtraction` gained a caller-supplied `keyOwnerTable`,
  `SingleRecordIdField` gained a first-class `table`, CatalogBuilder reads the leaf), all twelve
  construction sites dropped the argument (the census said eleven; a fully-qualified ctor call
  evaded the grep, re-counted at pickup as instructed), and the two target-alignment invariant
  sub-checks left with the component, dispositions in `SourceKeyTest`'s javadoc. `path` shipped at
  `9c8261b` (slice 2) together with the LiftedHop retirement below. Byte-identical output verified
  both times against a clean baseline build.* Original scope: migrate the four `target()` readers to the table fact their
  carrier already holds (`returnType.table()` / `ParentCorrelation.parentKeyOwnerTable()`; verify
  per site which one is the same table, especially the accessor arms where the element table is the
  accessor's return table). The null-`target` case (scalar-returning `@service`, the polymorphic
  record parent) must keep its absence semantics: the one null-tolerant reader
  (`CatalogBuilder`'s LSP projection) migrates to the carrier's return-type fact, where "no
  table-bound target" is an arm of `ReturnTypeRef`, not a nullable slot — absence lands as a typed
  arm, not a re-invented null. Delete `path` after the LiftedHop re-typing below; its compact-ctor
  invariants (`ResultRowWalk` / target-aligned `ServiceTableRecord` require empty path) re-home onto
  whatever carries the join path, or dissolve where the decomposed facts make the illegal state
  unrepresentable.
- **Retire `JoinStep.LiftedHop`.** *Shipped at `9c8261b` (slice 2): the pre-keyed correlation is
  the new hop-less `ParentCorrelation.OnLiftedSlots(targetTable, columns)` arm; lifted carriers
  classify with an empty `joinPath`; `OnFkSlots` narrowed to a `Hop`-with-`ColumnPairs` first hop;
  the four defensive unreachable arms deleted; `HasSlots` died into `On.ColumnPairs`;
  `JoinSlot.LifterSlot` left with the hop; `SourceKey.path` deleted in the same motion (its two
  empty-path invariants lost their carrier — hop-lessness is now pinned structurally by
  `checkCarrierInvariant`). One LSP-metadata-only delta: lifted carriers' `fkSteps` drop the
  `FkStep(table, null)` pseudo-step. `Hop.originTable` deletability noted, not taken.* Original
  scope: its lifted slots are the `Lift` source-side provenance (R333),
  and its live consumers reach it through `ParentCorrelation.OnFkSlots`, not through the join path:
  re-express the pre-keyed correlation as a `ParentCorrelation` arm carrying the column tuple
  directly (R333's "correlation is the FK column pairs for split and PK self-identity for re-fetch,
  the degenerate case"), so `@reference`-parsed paths remain the only `JoinStep` population. Then:
  the four defensive LiftedHop-unreachable arms in the `@reference`-path emitters
  (`BuildContext`'s path-resolution switch, `InlineLookupTableFieldEmitter`,
  `InlineColumnReferenceFieldEmitter`, `InlineTableFieldEmitter`, plus
  `SplitRowsMethodEmitter`'s bridging-position guard) become type-level impossibilities; the
  transitional `HasSlots` capability dies (`On.ColumnPairs` becomes its only implementor, and
  `JoinPathEmitter.emitCorrelationWhere`, the one API typed on it, re-types accordingly); and the
  denormalized `Hop.originTable` component becomes deletable in favor of a path-position
  derivation once the path carrier owns its start.
- **Decompose `reader` / `wrap` / `cardinality` / envelope.** *Shipped as slice 3, two commits.
  Slice 3.1 (`3229cbe`): the arrival-vocabulary half of `Cardinality` moved to the top-level
  `Arity` enum on the producer/carrier endpoint facts (`ProducerBinding`, `ServiceCarrierShape`,
  `ServiceCarrierShapeError`, the producer-arrival memo). Deliberately not R463's `Arrival`
  (accumulated tensor monoid, typename-keyed) per principles-architect consult 2026-07-15: same
  values, different grain. Slice 3.2 (`2ae8529`): `SourceKey` shrank to the `(columns, wrap)`
  residue (keeping the name the destinations section says it earns); the four live reader arms
  relocated to the new sealed `KeyLift` fact (`FkColumns` / `Lifter` / `Accessor(ref, Arity)` /
  `ProducedRecords(Arity)`) carried by the three record-parent leaves and, as `parentKeyLift`,
  `InterfaceField` / `UnionField` (the parentSourceKey re-typing this spec requires); the service
  arms died into the `MethodRef.Param.Sourced` signature; `ResultRowWalk` dissolved into
  `SingleRecordIdField` + the top-level `SourceEnvelope` enum (also re-typing
  `RecordCompositeField.envelope()`, so the Reader-side original copy is gone). Wrap stayed a
  stored residue component where authored (split/service) and became `KeyLift.wrap()`'s
  derivation where inferred (record-parent leaves construct through it;
  `KeyLift.checkResidueAgreement` is the constructor tripwire). The envelope stayed
  classifier-minted (`carrierPayloadHasErrorsField`) on the two leaves rather than joining the
  emit-time `hasWrapperArmErrors` type-level fact: the two predicates are structurally different
  and equating them is an unwitnessed equivalence (consult, same date); hoisting both onto one
  classifier-minted flip fact is noted as a candidate follow-up, not done here. Byte-identical
  output verified against a clean baseline both commits; full reactor green (8176 tests).*
  Original scope: introduce the decomposed facts
  (type-level source-object shape composing with the R463 `Source` arms; field-level lift with its
  two shape-gated arms and `@sourceRow` as authored provenance on the member-read arm; producer
  return shape read off the `MethodRef` signature where the service arms duplicated it; envelope on
  the type-level error-channel fact, retiring the `RecordCompositeField.envelope()` copy), then cut
  over the one exhaustive switch (`GeneratorUtils.buildRecordParentKeyExtraction`) and the narrow
  `instanceof` sites arm by arm, then delete the `Reader` seal. `cardinality`'s two forks read the
  arity off the endpoint that owns it (the accessor's return arity, the produced wrapper) instead
  of a free enum.
- **The two R438 self-review cleanups.** *Slice 4 (`ef3f4d8`) landed cleanup (2): the repeated
  bridging-join switch collapsed onto `JoinPathEmitter.emitForwardBridging` /
  `emitBackwardBridging` (forward and terminal-first chain families; the two root-chain switches
  in `TypeFetcherGenerator` stay as dispatch with site-specific unreachable-throw arms).
  Cleanup (1) is spun out to R485 (`fk-hop-narrowing-helpers`) per the option below.
  Byte-identical output verified.* Original scope: placed once LiftedHop is out of the seal (both
  pre-existing patterns R438 mechanically widened, not regressions). They differ in coupling:
  (2) the seven-line `switch (hop.on())` bridging-join emit (`onKey` vs `.on(condition(...))`)
  repeated in the four inline/split emitters consolidates into `JoinPathEmitter`, which already
  hosts the shared join-path emit helpers — genuinely coupled, since those emitters lose their
  LiftedHop arms in the same motion. (1) a model-level `isFkHop(JoinStep)` / `pairsOf(JoinStep)`
  pair replacing the ~forty inline `instanceof JoinStep.Hop h && h.on() instanceof On.ColumnPairs`
  narrowings and blind `(On.ColumnPairs) hop.on()` casts across ~nineteen files (the exhaustive
  sealed-switch sites are proper dispatch and stay) — only thinly coupled, so it lands as its
  **own trailing slice** whose byte-identity diff is audited in isolation, and splitting it to a
  follow-on item is an acceptable outcome if this item runs long.

## Tests and acceptance

- **Generated output byte-identical per slice.** This item moves facts between carriers without
  changing emit decisions, the same standard R438 held. If a slice genuinely cannot hold
  byte-identity (a normalization falls out of a dual-source collapse), the floor is execution-tier
  equivalence, and the deviation must **cite the specific execution-tier test that witnesses the
  equivalence** in the commit message (the R425/R426/R436 suites already pin the migrated
  behaviors), not merely describe the normalization; a deviation no existing test witnesses gets a
  new one first.
- **`@classified` corpus classifying unchanged**; the level-1 closure oracle
  (`MethodClosureOracleTest`) staying green; full reactor `mvn install -Plocal-db` green at every
  slice.
- **Model invariants keep their teeth, per-invariant.** The compact constructor carries six
  rejections in five invariant families (re-count at pickup; `SourceKeyTest` is the pin) with
  different fates once the axes separate, and each must be dispositioned explicitly, with the
  existing `SourceKeyTest` coverage migrating alongside as the mechanical pin; no invariant
  silently dropped:
  - `SourceRowsCall` ⇒ `Wrap.Row` and `AccessorCall` ⇒ `Wrap.Record`: expected to become
    **unrepresentable by construction** (the non-service lift arms pin their key shape; a stored
    wrap that could disagree no longer exists). *Disposition (slice 3.2): unrepresentable via the
    `KeyLift.wrap()` derivation every lift-carrying construction goes through, with
    `KeyLift.checkResidueAgreement` in the five carrying leaf constructors as the tripwire;
    pinned by `KeyLiftTest`.*
  - `ServiceTableRecord`(target-aligned) ⇒ empty path and `ResultRowWalk` ⇒ empty path: dissolve
    with `path`'s deletion (the illegal state loses its carrier; the delete-the-copies slice owns
    the re-home-or-dissolve call, as stated there).
  - `ResultRowWalk` ⇒ `Wrap.Record` | target-aligned `Wrap.TableRecord` (the DML carrier's bare
    rows vs the `@service` error-channel carrier's typed record; pinned by
    `SourceKeyTest.resultRowWalkRejectsWrapRow` /
    `resultRowWalkRejectsWrapTableRecordMismatchedTarget`): the reader-to-wrap half becomes
    unrepresentable if the key-row shape lands as a derivation from the lift arm plus producer
    signature (the choice the destinations section leaves to the implementer); if the shape stays
    a stored component, this family re-asserts at the same named join site as the envelope
    invariant below. Either way the target-alignment sub-check dissolves with `target`'s
    deletion — it asserts a denormalized copy agrees with its source, the drift class this item
    removes — so this family is dispositioned no later than the `target`-deleting slice, not left
    for the `Reader` decomposition.
  - `ResultRowWalk(OUTCOME_SUCCESS)` ⇒ `Wrap.TableRecord`: the hard one — it couples the envelope
    axis (routed to the type-level error-channel fact) to the key-shape axis (routed to the
    source-object / producer facts), two facts that share no landing site after the split. Per the
    orthogonal-axes principle this drops from compiler-enforced to review-only unless it is
    re-asserted at a named join site; the implementer names that site (the classifier point that
    mints both facts for a carrier field is the natural candidate) and pins it with a migrated
    test. Leaving it enforced nowhere is rework, not a judgment call. *Disposition (slice 3.2):
    the named join site is `ChildField.SingleRecordIdField`'s compact constructor — the only
    envelope-bearing typed-record read once the DML `Wrap.Record` walk died into the R305
    re-fetch — which requires `Wrap.TableRecord` unconditionally, strictly stronger than the
    retired conditional (its sibling envelope carrier `RecordCompositeField` is a composite
    passthrough holding no key). Compiler-enforced at the leaf, pinned by
    `SingleRecordIdFieldKeyShapeInvariantTest`. The `ResultRowWalk` ⇒ `Record`/`TableRecord`
    family is subsumed by the same unconditional requirement; the `Record` (DML) arm was dead
    vocabulary — the only `ResultRowWalk` construction was the `SingleRecordIdField` mint.*

  `SealedHierarchyDocCoverageTest` and the architecture docs sweep in the deleting slice. The docs
  half is a chapter, not stray mentions: `docs/architecture/explanation/dispatch-axes.adoc` narrates
  the `SourceKey` / `LoaderRegistration` contract and is xref'd from `docs/architecture/index.adoc`,
  `explanation/index.adoc`, and `development-principles.adoc` (the axis-split exemplar at its
  "Dispatch axes" xref); the deleting slice rewrites or retires that chapter onto the decomposed
  facts and retargets the inbound xrefs. (R433's forward note pointing `SourceKey` readers at this
  item lived in the old principles doc and did not survive the R434 restructure, so no live doc
  currently flags the record as scheduled for decomposition; verified 2026-07-14.) *Done in the
  slice-3 docs commit: the chapter is rewritten onto the residue/lift/envelope model (its old
  five-axis narration is in git history), the `explanation/index.adoc` blurb and the
  `code-generation-triggers.adoc` model-catalog rows (which also still named the slice-2-retired
  `LiftedHop`) are updated, and the `index.adoc` / `development-principles.adoc` xrefs were
  checked and hold as-is. `SealedHierarchyDocCoverageTest` walks `Rejection` only and is
  untouched by the new seals.*
- No new execution-tier fixtures required: R425/R426/R436's pipeline and execution tests already
  pin the behaviors whose emit sites this item migrates, and they must stay green throughout.

## Relationships

- **Feeds R432** (`collapse-split-and-record-table-leaves`, depends-on this item) and **R314**
  (reentry re-platforming; run-up recorded in both specs as R431 → R432 → R314). The
  parent-projection containment check is R432's deliverable, not this item's.
- **R180** (`record-parent-column-read-helper`, Spec) resolves per-column accessors for
  record-parent key reads and its own spec says "sequence this after R431 or land it as part of
  R431's reshaping": the implementer may fold the resolved-accessor carry into the lift fact's
  member-read arm if it falls out naturally; otherwise R180 follows.
- **R471** (`direct-sql-onlychild-reentry-emit`) owns the arrival-strategy question; nothing here
  consumes the `OnlyChild` arm as a strategy fork.
- **When this lands, re-check R71, R234, R323** (staleness-audit observation 3; R425/R426 shipped
  since the observation was recorded): all pin on `SourceKey` shapes (`Wrap` parity, embedded/UDT
  backings, multi-parent `BatchKey` leaves) and their bodies re-anchor onto the decomposed facts.
- **R333 stays the governing model document** (status Ready, kept live for exactly this
  consumption); the seam-worklist and "What SourceKey decomposes into" (R222) sections are the
  authority if this spec and they ever disagree.
