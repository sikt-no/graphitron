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

1. **Lift the FK pairing into the model.** Replace `sourceColumns: List<ColumnRef>` / `targetColumns: List<ColumnRef>` on `JoinStep.FkJoin` with `slots: List<FkSlot>` (or similar), where each slot carries both sides. Decide whether `LiftedHop` ; whose "DataLoader key tuple IS the target-column tuple" relationship is the same kind of implicit invariant ; gets parallel slots whose source equals target (uniform shape, capability-friendly), keeps a separate accessor (genuine identity fork stays a switch), or surfaces the equality as a structural fact some other way. Audit the read sites ; `JoinPathEmitter.emitCorrelationWhere`, `SplitRowsMethodEmitter`'s three rows builders, `MultiTablePolymorphicEmitter`'s `batchedBranchJoinPredicate` and `branchParentFkWhere`, plus any reader in `BuildContext` ; and confirm each one wants slot pairs, not "all source cols then all target cols". The arity precondition disappears as a structural fact.

   Downstream consequence (worth scoping in, not a separate item): with slots on both arms, the new `joinOnParentCols` switch in `SplitRowsMethodEmitter.derivePreludeBindings` collapses on the list-cardinality path (both arms project to the same accessor), and the `if/instanceof` over `firstHop` in `buildSingleMethod:660` reduces to a single `slots()` read. The "same predicate evaluated by multiple consumers" smell that triggered this item gets resolved at the same time as the parallel-list smell ; one structural change, both fixes.
2. **Decide the relationship between `pkCols` and `nodeKeyColumns`.** Today `BatchKey.RowKeyed` on the split-query list path uses `parent.primaryKeyColumns()` (catalog PK order), so the DataLoader key tuple and parent VALUES aliasing are bound to DB ordering. Per the user-stated intent of `@node(keyColumns)`, the SDL author should be able to reorder DB PK columns for performance without rewriting consumer code. If the rows method's parent VALUES aliasing should follow `nodeKeyColumns` order when present, that's a separate-but-related model change ; the `BatchKey` carrier's column list needs an authority story (catalog PK? `@node(keyColumns)`? one or the other depending on context?). Document the decision either way.
3. **Audit every site that iterates two column lists positionally ; and every site that hardcodes `.get(0)` past a documented arity gate.** Grep for `\.get\(i\)` over `sourceColumns`, `targetColumns`, `parentKeyColumns`, `primaryKeyColumns`, `nodeKeyColumns`, `slotColumns`, `lookupCols`. For each, classify: same-list iteration (fine, e.g. `InlineLookupTableFieldEmitter`), one list paired against another by FK construction (record the invariant), or one list paired against another whose ordering is *separately* derived (the bug shape). Each occurrence of the third category needs a fix or a load-bearing check.

   Companion sweep: every `\.get\(0\)` on `sourceColumns` / `targetColumns` ; today's example is `MultiTablePolymorphicEmitter.branchParentFkWhere:602-607`, which pairs `fkJoin.targetColumns().get(0)` against `fkJoin.sourceColumns().get(0)` with no arity loop. Either composite FK on the non-batched B3 path is a classifier guarantee (in which case it wants `@LoadBearingClassifierCheck`, not silent column-dropping) or it's an unstated single-column-FK limitation, and a multi-column FK schema would emit a predicate that ignores all but the first slot. The slot type makes both shapes structural: `slots().get(0)` documents the single-slot intent, and a composite FK either compiles or is rejected explicitly.
4. **Compilation-tier fixture, and retire the body-string greps.** The unit-tier regression tests added in `fdfec353` (`splitTableField_listRowsMethod_reorderedHeteroFk_pairsBySqlNameAndType`, `childInterfaceField_connection_reorderedCompositeFk_pairsBySqlNameAndType`) assert against emitted method bodies via `assertThat(rows).contains("p0.PROJECT_ID.eq(parentInput.field(\"project_id\", java.lang.Integer.class))")`-style string greps. Per **Pipeline tests are the primary behavioural tier** ("Code-string assertions on generated method bodies are banned at every tier; they test implementation, not behaviour, and break on every refactor"), these are the exact pattern the principles forbid ; doubly so when the bug they prevent is `Field<Integer>.eq(Field<String>)` ; a compile error in the emitted Java, which `mvn compile -pl :graphitron-sakila-example` is designed to catch without any string match. The same commit also re-engineered the existing `childInterfaceField_emitsParentFkConditionPerBranch` fixture (both FKs now source from `last_update`) so the assertions still match ; an integration-style test bent around the body-content shape instead of testing behaviour. The slot-shape work should: (a) add a Sakila-DB fixture (composite-PK parent + child FK declared in different column order than the parent PK) so the bug shape is exercised end-to-end through `BuildContext.synthesizeFkJoin` with javac as the assertion, and (b) delete the body-string regressions in favour of that compile-tier coverage, or recast them against the public emitter contract (`MethodSpec` shape, parameter types) rather than method-body strings.
5. **Residual validate-time check (not a fork in the road).** The typed-slot shape removes the FK-vs-PK ordering invariant by construction: positional misuse is a compile error, ordering is irrelevant. What's left to decide is whether any *other* invariant survives the refactor and wants a `@LoadBearingClassifierCheck` ; e.g. "the FK's parent-side columns are a permutation of the parent PK" (a precondition for `MultiTablePolymorphicEmitter.matchingParticipantCol`, currently a runtime `IllegalStateException` at emit time). If yes, declare it; if no, retire the throw. The "graphitron-side convention that FKs must declare in PK order" framing is the *wrong* shape ; the slot fix moots it ; and should not survive the Spec phase as an option.

## Pre-step cleanup ; removable independently, structurally subsumed by the slot lift

The two fix commits left structural sediment behind: locals and a record component that were load-bearing pre-fix and are now dead at the consumer side. Removing them lands on its own merits (less surface to migrate, less stale prose to mislead the next reader) and acts as the first signal that the implementation has internalised the slot-shape direction ; the same reads that go away here are the ones the slot lift would have removed anyway.

- `SplitRowsMethodEmitter.buildListMethod:443`, `buildSingleMethod:637`, `buildConnectionMethod:778`: each declares `List<ColumnRef> pkCols = p.pkCols();` and never reads it. The only remaining textual references are the explanatory comments at `:519` ("positional `.field(i+1, pkCols.get(i).columnClass)` would mis-pair types when those orderings disagree") and `:656` (same shape) ; both describe the *old* shape and have served their purpose now that the consumers do not consume `pkCols`.
- `SplitRowsMethodEmitter.PreludeBindings.pkCols`: dead at the boundary. The component is computed from `batchKey.preludeKeyColumns()` at `derivePreludeBindings:163` and used heavily *inside that method* (parent-VALUES sizing at `:166`, typed `parentRowTypeArgs` at `:174`, the row-builder loop at `:243`, the parent-input alias at `:256`), then propagated through the record into builders that don't read it. The component drops; the local stays.
- The two `pkCols.get(i).columnClass` comments stop carrying weight once the locals are gone. Either delete them or fold a single sentence into the prelude-bindings javadoc explaining why the JOIN predicate uses `joinOnParentCols` instead.

This phase is mechanical, ships under one commit, and a green `mvn install -Plocal-db` is the only check needed.

## Touchpoints

- `JoinStep.FkJoin` (record shape: `sourceColumns` / `targetColumns` ; the lift target).
- `JoinStep.LiftedHop` (target-equals-source today via prose; needs the slot-shape decision per Q1).
- `BuildContext.synthesizeFkJoin:548-549` (how the parallel lists are populated; the structural construction site).
- `JoinPathEmitter.emitCorrelationWhere` (the only correctly-paired consumer today). The arity-mismatch throw at `:97-101` becomes structurally impossible once slots replace parallel lists and is deletable. The `noCondition()` empty-catalog fallback at `:104-110` stays (an empty `slots()` list still wants the same DSL-runtime stub).
- `SplitRowsMethodEmitter` ; `buildListMethod`, `buildSingleMethod`, `buildConnectionMethod`, plus the `joinOnParentCols` field on `PreludeBindings` and the dead `pkCols` plumbing per the pre-step cleanup section.
- `SplitRowsMethodEmitter.PreludeBindings.pkCols` (record component; removable at the boundary independent of the slot lift).
- `MultiTablePolymorphicEmitter` ; `batchedBranchJoinPredicate` (composite-FK loop) and `matchingParticipantCol` (sqlName lookup that disappears), `branchParentFkWhere:602-607` (the `.get(0)` hardcode that needs an explicit arity decision).
- `BatchKey.ParentKeyed.parentKeyColumns()` (the authority for "what order is the DataLoader key tuple in").
- `GraphitronType.NodeType.nodeKeyColumns` (the SDL-author-chosen identity ordering).
- `LoadBearingClassifierCheck` / `DependsOnClassifierCheck` (for whichever invariants we keep ; per Q5 likely just the "FK's parent-side columns are a permutation of parent PK" check that today throws inside `matchingParticipantCol`).

## Out of scope

- Changing how node IDs are encoded on the wire. `@node(keyColumns)` order remains the encoding source of truth. This item only addresses how the *internal* column orderings flow through the model and the rows-method emitters.
- The cardinality-direction *check itself* (`parent-holds-FK` vs `child-holds-FK`) ; already a sealed identity decision (`fk.targetTable().equalsIgnoreCase(participant.table().tableName())` etc.) and not changed by this item. But how slots project under each direction *is* in scope: list-cardinality reads parent-side from slots and terminal-side from slots; single-cardinality inverts which side is which (FK source sits on the parent in that case). The slot accessor needs to be coherent across both directions, which is what makes the typed shape pay off.

