---
id: R82
title: "FK column pairing: typed slots over parallel ordered lists"
status: In Review
bucket: cleanup
priority: 4
theme: model-cleanup
depends-on: []
---
# FK column pairing: typed slots over parallel ordered lists

Two emitters (`SplitRowsMethodEmitter` and `MultiTablePolymorphicEmitter`) recently shipped fixes for the same bug: a `JOIN parentInput ON ...` predicate that paired one column list at index `i` against another column list at index `i`, on the unstated assumption that the two lists were in the same order. They are not, in general, and a real consumer schema (composite-PK parent, FK declared in a column order different from the parent's PK declaration order) tripped a `Field<String>.eq(Field<Long>)` compile error in the generated source.

The two fixes (`a4b3eabe` and `fdfec353`) replaced positional pairing with nominal lookup. They got the consumer unblocked, but neither fix touches the model shape that admitted the bug in the first place, and the two emitters now solve the same problem with different machinery (Fix 1 iterates FK slots and looks parent-side cols up by `targetColumns[i].sqlName`; Fix 2 iterates parent-PK and runs `matchingParticipantCol` to find the FK slot). That asymmetry is itself the smell ; same predicate, two consumers, two implementations.

Model invariants belong in the type system, not in prose preconditions. This item lifts the FK-pairing parallel-list shape into a typed slot so positional misuse becomes a compile error and the load-bearing "slot `i` on each side is one cell of the FK constraint" invariant becomes structural ; not a prose precondition that two consumers re-derive in subtly different ways.

## What's actually going on

There are at least three independent column orderings in play whenever a rows-method or polymorphic dispatcher emits a parent/child join:

1. **FK declaration order.** `fk.getFields()` (child side) and `fk.getKeyFields()` / `fk.getKey().getFields()` (parent side); both lists are paired *to each other* at index `i` ; that's the one positional pairing jOOQ guarantees from the catalog.
2. **Parent PK declaration order.** `parent.getPrimaryKey().getFields()`, lifted into `TableRef.primaryKeyColumns()`. Independent of FK declaration order; an FK can reference the parent PK columns in any permutation.
3. **`@node(keyColumns)` order.** SDL-author-chosen ordering for the parent type's identity, deliberately decoupled from DB PK and FK ordering so node IDs are stable when DBAs reorder columns. Lives on `GraphitronType.NodeType.nodeKeyColumns`.

Today's emitters mix these orderings without making the relationships explicit:

- `SplitRowsMethodEmitter`'s `pkCols` comes from `batchKey.parentKeyColumns()`, which for `BatchKey.RowKeyed` on a list-cardinality `@splitQuery` is `parent.primaryKeyColumns()` (catalog PK order). The parent VALUES table is aliased in that order; the DataLoader key tuple is constructed in that order; the JOIN predicate then mixes that ordering with FK source columns (FK decl order). Pre-fix, that was a positional `.eq(...)` against `pkCols.get(i)`. Post-fix, it's a nominal `parentInput.field(target[i].sqlName, ...)` lookup against the same parent VALUES.
- `MultiTablePolymorphicEmitter` does the same dance at `batchedBranchJoinPredicate` and additionally runs the same shape at the *non-batched* B3 path (`branchParentFkWhere`).
- `JoinPathEmitter.emitCorrelationWhere` pairs FK source and FK target positionally, which is correct (both are FK decl order) ; but the precondition is hand-written prose rather than a model invariant.

Each consumer makes its own decision about which orderings to mix and how. The model carries `sourceColumns: List<ColumnRef>` and `targetColumns: List<ColumnRef>` as two parallel lists, and the load-bearing fact ; "slot `i` on each side is one cell of the FK constraint" ; lives only in prose javadoc on `JoinStep.FkJoin`.

## Why this matters beyond the two fixes

Both initial fix attempts in the bug session got the wrong analysis on the first pass. The reasoning kept slipping between FK decl order and PK decl order because the model offers no type-level distinction. Reviewer and author both had to re-derive what `getKey().getFields()` returns relative to `getKeyFields()` (they're the same; only `parent.getPrimaryKey().getFields()` is in PK declaration order), and at one point a "fix" went out that didn't actually fix the consumer's compile error because the test fixture was hand-built with target columns aligned the way the *emitter* wanted them, bypassing the model. That class of mistake is a sign the model isn't carrying its invariants into the type system.

Three principles are relevant:

- **Generation-thinking** ("two consumers evaluating the same predicate over a model field is a sign the resolver is under-specified"). FK column pairing is currently re-derived in three sites ; `SplitRowsMethodEmitter`'s three rows-method builders, `MultiTablePolymorphicEmitter`'s batched and non-batched paths, `JoinPathEmitter.emitCorrelationWhere` ; with subtly different machinery each time. The branch belongs in the model.
- **Sub-taxonomies for resolution outcomes** / **narrow component types over broad interfaces**. `sourceColumns` and `targetColumns` are two `List<ColumnRef>` parallel lists with an "expect equal arity" precondition (`JoinPathEmitter.emitCorrelationWhere:97`). A `List<JoinSlot>` ; where `JoinSlot` is a record carrying two `ColumnRef`s paired by equality at slot `i` ; would make positional misuse a compile error and the equal-arity check structural.
- **Classifier guarantees shape emitter assumptions** / **load-bearing classifier checks.** The implicit invariant "FK declaration order matches the parent's PK declaration order" was load-bearing for the pre-fix emitters' correctness but never declared, never tested, never validated. A consumer schema that violated it surfaced the bug as a downstream Java compile error, not at validate time. Either the invariant gets retired (which is what the recent fixes did, by switching to nominal lookup) and the supporting guarantee comes off the books, or it gets declared properly with `@LoadBearingClassifierCheck` and a test fixture that exercises a violating schema.


## Shipped at `1da849b` + `14261ca`

The slot lift landed across two commits. `1da849b` (marked `wip:` because the
emit-side migration was still outstanding) introduced the `JoinSlot` sealed
interface with `FkSlot`/`LifterSlot` permits, refactored `JoinStep.FkJoin` and
`JoinStep.LiftedHop` to carry slot lists, expanded the `WithTarget` capability
with `slots()` (`Iterable<? extends JoinSlot>` to ban positional access at the
type level), `slotCount()`, and default `sourceSideColumns()` /
`targetSideColumns()` materialisers, oriented slots at synthesis time inside
`BuildContext.synthesizeFkJoin`, and migrated the non-emitter readers
(`FieldBuilder.deriveSplitQueryBatchKey`, `deriveBatchKeyForResultType`,
`fkMirrorSourceColumns`, `projectFilters`'s switch arms; the `LiftedHop`
constructor sites in `BatchKeyLifterDirectiveResolver` and `FieldBuilder`).
`14261ca` finished the emit sites: `JoinPathEmitter.emitCorrelationWhere`
dropped the `parentHoldsFk` parameter and the arity-mismatch throw,
`InlineTableFieldEmitter` and `InlineLookupTableFieldEmitter` and
`TypeFetcherGenerator` retired their `parentHoldsFk` derivations,
`MultiTablePolymorphicEmitter`'s `matchingParticipantCol` retired whole and
both arms iterate `slots()` on a single shape, `SplitRowsMethodEmitter`'s
`buildSingleMethod` if/instanceof block at `:653-660` collapsed to a
single-iteration through `WithTarget`, `NodeIdLeafResolver`'s DirectFk arm
reads `targetSideColumns`/`sourceSideColumns`, and `BatchKey`'s
`LifterRowKeyed` / `AccessorKeyedSingle` / `AccessorKeyedMany` arms read
`hop.targetSideColumns()`. The load-bearing pair landed at
`fk-join.slots-oriented-source-and-target` (producer on
`WithTarget.sourceSideColumns()`, consumers on each migrated reader and emit
site that reads slot orientations directly). Test fixtures across nine files
migrated through the new `TestFixtures.fkJoin` / `liftedHop` helpers; the two
body-string regressions added at `fdfec353` retired in favour of the
structural model-tier coverage in `JoinSlotOrientationTest` (5 new tests) plus
the existing compile-tier check at `graphitron-sakila-example`. The
`rewrite-design-principles.adoc:228` DTO-parent batching recipe updated to the
slot-shaped vocabulary in the same commit.

Build at `14261ca`: 10/10 modules SUCCESS under `mvn install -Plocal-db`,
1318 tests pass, including the model-tier orientation test.

### Deviation from spec — surfaced for In Review

The spec promised "no signature change, no caller-supplied hint" on
`synthesizeFkJoin`, deriving orientation from
`sourceSqlName.equalsIgnoreCase(f.getTable().getName())` alone. That test is
ambiguous for self-referential FKs: `category.parent` and `category.children`
navigate the same FK constraint in opposite directions, and the table-name
comparison cannot distinguish them. Two execution-tier tests caught this
(`inlineTableField_selfRef_listCardinality_returnsChildren`,
`inlineTableField_selfRef_nonRootCategory_hasNoChildren`).

The fix threads a `selfRefFkOnSource` boolean through `parsePath` →
`parsePathElement` → `synthesizeFkJoin`, derived from the field's
list-cardinality at the call site. It's consulted only when the FK is
self-referential (`f.getTable() == f.getKey().getTable()`); for non-self-refs
the table-name comparison resolves direction unchanged. Sites that already
had cardinality at hand (the table-bound child-field classifier in
`FieldBuilder`) thread it explicitly; sites that don't (NodeId leafs,
synthesis shim, service paths) pass `true` (parent-holds-FK, the typical case
for those paths). The hint is ignored for non-self-ref FKs, so those defaults
are safe.

This is a one-parameter signature change against the spec's prose. The
underlying structural lift is unchanged. The reviewer should weigh whether
threading cardinality through is the right shape, or whether self-ref
disambiguation should live elsewhere (e.g. a follow-up to add a self-ref
classifier check + reject ambiguous cases at validate time, or a separate
slot-orientation hint on `JoinStep.FkJoin` set by the field classifier
post-synthesis).
