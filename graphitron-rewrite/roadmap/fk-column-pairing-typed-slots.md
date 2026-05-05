---
id: R82
title: "FK column pairing: typed slots over parallel ordered lists"
status: Backlog
bucket: cleanup
priority: 4
theme: model-cleanup
depends-on: []
---
# FK column pairing: typed slots over parallel ordered lists

Two emitters (`SplitRowsMethodEmitter` and `MultiTablePolymorphicEmitter`) recently shipped fixes for the same bug: a `JOIN parentInput ON ...` predicate that paired one column list at index `i` against another column list at index `i`, on the unstated assumption that the two lists were in the same order. They are not, in general, and a real consumer schema (composite-PK parent, FK declared in a column order different from the parent's PK declaration order) tripped a `Field<String>.eq(Field<Long>)` compile error in the generated source.

The two fixes (`a4b3eabe` and `fdfec353`) replaced positional pairing with nominal lookup. They got the consumer unblocked, but neither fix touches the model shape that admitted the bug in the first place, and the two emitters now solve the same problem with different machinery (Fix 1 iterates FK slots and looks parent-side cols up by `targetColumns[i].sqlName`; Fix 2 iterates parent-PK and runs `matchingParticipantCol` to find the FK slot). That asymmetry is itself the smell ; same predicate, two consumers, two implementations.

Treat this item as a vehicle for the bigger lesson, not just a refactor of the two consumers.

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
- **Sub-taxonomies for resolution outcomes** / **narrow component types over broad interfaces**. `sourceColumns` and `targetColumns` are two `List<ColumnRef>` parallel lists with an "expect equal arity" precondition (`JoinPathEmitter.emitCorrelationWhere:97`). A `List<FkSlot>` ; where `FkSlot` is a record carrying the source `ColumnRef` and target `ColumnRef` together ; would make positional misuse a compile error and the equal-arity check structural.
- **Classifier guarantees shape emitter assumptions** / **load-bearing classifier checks.** The implicit invariant "FK declaration order matches the parent's PK declaration order" was load-bearing for the pre-fix emitters' correctness but never declared, never tested, never validated. A consumer schema that violated it surfaced the bug as a downstream Java compile error, not at validate time. Either the invariant gets retired (which is what the recent fixes did, by switching to nominal lookup) and the supporting guarantee comes off the books, or it gets declared properly with `@LoadBearingClassifierCheck` and a test fixture that exercises a violating schema.

## Research questions for the Spec phase

1. **Lift the FK pairing into the model.** Replace `sourceColumns: List<ColumnRef>` / `targetColumns: List<ColumnRef>` on `JoinStep.FkJoin` with `slots: List<FkSlot>` (or similar), where each slot carries both sides. Audit the read sites ; `JoinPathEmitter.emitCorrelationWhere`, `SplitRowsMethodEmitter`'s three rows builders, `MultiTablePolymorphicEmitter`'s `batchedBranchJoinPredicate` and `branchParentFkWhere`, plus any reader in `BuildContext` ; and confirm each one wants slot pairs, not "all source cols then all target cols". The arity precondition disappears as a structural fact.
2. **Decide the relationship between `pkCols` and `nodeKeyColumns`.** Today `BatchKey.RowKeyed` on the split-query list path uses `parent.primaryKeyColumns()` (catalog PK order), so the DataLoader key tuple and parent VALUES aliasing are bound to DB ordering. Per the user-stated intent of `@node(keyColumns)`, the SDL author should be able to reorder DB PK columns for performance without rewriting consumer code. If the rows method's parent VALUES aliasing should follow `nodeKeyColumns` order when present, that's a separate-but-related model change ; the `BatchKey` carrier's column list needs an authority story (catalog PK? `@node(keyColumns)`? one or the other depending on context?). Document the decision either way.
3. **Audit every site that iterates two column lists positionally.** Grep for `\.get\(i\)` over `sourceColumns`, `targetColumns`, `parentKeyColumns`, `primaryKeyColumns`, `nodeKeyColumns`, `slotColumns`, `lookupCols`. For each, classify: same-list iteration (fine, e.g. `InlineLookupTableFieldEmitter`), one list paired against another by FK construction (record the invariant), or one list paired against another whose ordering is *separately* derived (the bug shape). Each occurrence of the third category needs a fix or a load-bearing check.
4. **Compilation-tier fixture.** The unit-tier regression tests added in `fdfec353` exercise the bug shape with hand-built `FkJoin` records. They caught the symptom because the fixture was carefully constructed; they didn't catch the deeper "two parallel lists with implicit pairing" smell. Add a Sakila-DB fixture (composite-PK parent + child FK declared in different column order than the parent PK) so the bug shape is exercised end-to-end through `BuildContext.synthesizeFkJoin` and the consumer's javac is the assertion. That's the real safety net per the test-tier principles.
5. **Validate vs. normalise.** If we decide FK columns may be in any permutation of parent PK columns (which the database allows and which a real consumer schema does in practice), the model just needs the typed-slot fix. If we decide consumer schemas should declare FKs in PK order (a graphitron-side convention), the validator needs to reject mismatches. Current code silently accepts the violation and emits broken Java; that's the wrong default either way.

## Touchpoints

- `JoinStep.FkJoin` (record shape: `sourceColumns` / `targetColumns`)
- `BuildContext.synthesizeFkJoin` (line 548-549; how the lists are populated)
- `JoinPathEmitter.emitCorrelationWhere` (the only correctly-paired consumer today)
- `SplitRowsMethodEmitter` ; `buildListMethod`, `buildSingleMethod`, `buildConnectionMethod`, plus the `joinOnParentCols` field on `PreludeBindings`
- `MultiTablePolymorphicEmitter` ; `batchedBranchJoinPredicate`, `branchParentFkWhere`, `matchingParticipantCol`
- `BatchKey.ParentKeyed.parentKeyColumns()` (the authority for "what order is the DataLoader key tuple in")
- `GraphitronType.NodeType.nodeKeyColumns` (the SDL-author-chosen identity ordering)
- `LoadBearingClassifierCheck` / `DependsOnClassifierCheck` (for whichever invariants we keep)

## Out of scope

- Changing how node IDs are encoded on the wire. `@node(keyColumns)` order remains the encoding source of truth. This item only addresses how the *internal* column orderings flow through the model and the rows-method emitters.
- Cardinality direction (`parent-holds-FK` vs `child-holds-FK`) ; that's already a sealed identity check (`fk.targetTable().equalsIgnoreCase(participant.table().tableName())` etc.). The pairing fix is orthogonal.

