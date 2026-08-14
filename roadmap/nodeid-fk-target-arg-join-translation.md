---
id: R57
title: "FK-target @nodeId JOIN-with-translation filter emission (argument + input field)"
status: Spec
bucket: architecture
priority: 5
theme: nodeid
depends-on: []
last-updated: 2026-08-14
---

# FK-target @nodeId JOIN-with-translation filter emission

## Problem

`@nodeId(typeName: X)` on an argument or filter input field whose containing table reaches `X.table()` through a foreign key classifies into one of two `NodeIdLeafResolver.Resolved.FkTarget` arms. `DirectFk` (the FK's terminal target-side columns equal `X`'s keyColumns as a multiset) shipped with R40: the resolver lifts the FK source columns into `liftedSourceColumns` and `projectFilters` emits a plain `Eq` / `In` / `RowEq` / `RowIn` against the field's own table, no join. `TranslatedFk` (target-side columns genuinely differ from the keyColumns) is rejected at classify time by `FieldBuilder.translatedFkRejection`, routing to `UnclassifiedArg` on the argument side and `InputFieldResolution.Unresolved` on the input-field side.

The canonical reproducer is the `nodeidfixture` pair `parent_node` + `child_ref`: `child_ref.parent_alt_key` references `parent_node.alt_key` (a non-PK unique column) while `ParentNode`'s node key is `parent_node.pk_id`. Decoding the incoming id yields a `pk_id` value; `child_ref` has no column holding that value, so the predicate cannot be written without visiting `parent_node`. That is the translation: SQL must convert `pk_id` into `alt_key` before it can filter `child_ref`.

## Stale premise to retire

The rejection message and this item's previous body both defer to output-side JOIN-with-projection emission (R24). That coupling is wrong. R24 is the encode direction: project the parent's key columns into a result column. This item is the decode direction: turn already-decoded key values into a predicate. The decode direction does not need R24's emitter; it needs a correlated EXISTS, and that machinery already ships twice over:

- `BodyParam.RemoteColumnPredicate(joinPath, inner)` wraps an ordinary `ColumnPredicate` whose columns bind to the terminal table; `ConditionCommands.termOf` narrows it into a `ColumnTerm` with a non-empty reach, and `ConditionGlueRenderer.reachExists` renders `DSL.exists(selectOne().from(terminal)<walk-back joins>.where(<correlation>.and(<inner>)))`. Plain `@reference` filters over a joined terminal column use this today.
- `FkTargetConditionFilter` (authored `@condition` on an FK-target `@nodeId` field) already emits the same correlated EXISTS, handing the developer method an alias for the target table.

So the emission mechanism for this item is reuse, not new SQL shaping. The spec drops the R24 dependency entirely; R24's output side is unaffected and stays filed on its own.

The false dependency is authored into several places, all retired by this item:

- `FieldBuilder.translatedFkRejection`'s message text ("deferred until output-side JOIN-with-projection emission ships") and its javadoc.
- The comment in `FieldBuilder.classifyArgument`'s `TranslatedFk` case arm, carrying the same sentence.
- The comment in `FieldBuilder.projectFilters`' `ColumnBackedReferenceArg` arm, carrying it a third time.
- The `TranslatedFk` arm javadoc ("which no emitter supports") and the `FkTarget` doc bullet ("the only shape the projection arms emit") in `NodeIdLeafResolver`.
- The `ColumnBackedReferenceArg` javadoc's "rejected at classify time with a deferred-emission hint", and the `ColumnBackedArg` javadoc's contrast clause ("whose `@nodeId` join path lifts to local FK columns (no join)"), which stops being true once the reference carrier can bind `Remote`.
- The `parent_node` metadata comment in `NodeIdFixtureGenerator` ("that mismatch is the test surface for the rooted-at-parent JOIN-with-projection emission path"), which describes the decode-side fixture in encode-side terms.
- The arg-side FK-target commentary in `NodeIdPipelineTest` ("parallel to the still-deferred output-side JOIN-with-projection") and the `FK_TARGET_PATHOLOGICAL_KEY_MISMATCH_DEFERRED` case description.

Not on that list, deliberately: `docs/architecture/reference/code-generation-triggers.adoc` carries no row for this deferral. Its one deferral row is the output-side `@nodeId(typeName:)`, non-FK-mirror target row pending `nodeidreferencefield-join-projection-form`, which is the encode direction and stays as-is; the input-side `@nodeId(typeName:) on input scalar` row describes the DirectFk decode and never mentions the rejection. That row is worth extending with the translated variant once this ships, but nothing there is being retired.

EXISTS is also the semantically correct shape over a real JOIN here: no row multiplication when the target is reached through a non-unique path, and a NULL FK column simply fails the correlation instead of duplicating or dropping rows.

## Design

### Carrier fork: a sealed binding component

Both carriers, `ArgumentRef.ScalarArg.ColumnBackedReferenceArg` and `InputField.ColumnBackedReferenceField`, carry `columns` (the target NodeType's keyColumns), `joinPath`, and `liftedSourceColumns`. The `liftedSourceColumns` slot is already overloaded with two meanings, recorded in `FieldBuilder.remoteIfReferenceJoin`'s javadoc: for `@nodeId` DirectFk it is FK-child columns on the field's own table (bind locally), while for a plain joined `@reference` it is the terminal column on the joined table (wrap in `RemoteColumnPredicate`). Today the two are told apart by extraction type (`Direct` vs `NodeIdDecodeKeys`), which encodes "nodeId implies local"; the TranslatedFk case falsifies exactly that implication.

Replace the slot with a small sealed component that names the axis outright:

- `Local(List<ColumnRef> ownTableColumns)`: the predicate binds to the field's own table; bare `Eq` / `In` / `RowEq` / `RowIn`. DirectFk's lifted tuple and the local plain-`@reference` column land here. The arm name carries the referent so the record never again admits a slot whose table is implicit.
- `Remote`: payload-free. It means "the predicate binds the carrier's `columns()` against the terminal table of `joinPath`"; the emitter wraps in `RemoteColumnPredicate(joinPath, inner)`. The joined plain-`@reference` case and TranslatedFk both land here, structurally identical. Payload-free on purpose: in both cases the terminal tuple is already the carrier's `columns()` (TranslatedFk's predicate columns are the keyColumns; the joined plain-`@reference` today stores the same single column in both slots, `BuildContext` input-field construction), and a second slot holding a copy of `columns()` is a drift risk with no enforcer. Only `Local` carries a tuple that `columns()` cannot supply.

The compact constructor enforces what the sentinel could not: `Remote` requires a non-empty `joinPath`. `remoteIfReferenceJoin` switches on the binding instead of the extraction and its two-meanings javadoc retires.

`joinPath` stays orthogonal to the binding and does not fold into the `Remote` arm: a `Local`-bound FK-target `@nodeId` with an authored `@condition` still needs the path for the `FkTargetConditionFilter` correlation, so "Local + non-empty joinPath" is a legitimate state and `Remote` means specifically "the value predicate reaches through the path". The arm javadoc states this so the two readers of `joinPath` stay un-conflated.

The same axis has a third spelling today: the arg-side plain-`@reference` carrier (`ColumnBackedArg`) discriminates local-vs-remote by an empty-`joinPath` sentinel in `projectFilters`. That is the same implicit fork this item rejects for the new slot, so `FilterBinding` goes onto `ColumnBackedArg` in the same change and the three discrimination sites (`ColumnBackedArg` sentinel, `remoteIfReferenceJoin`'s extraction test, the new component) collapse to one switch. If this leg grows during implementation it may split into a sibling item, but the spec's default is unification.

One asymmetry to decide at implementation, because it is what would make that leg grow. `ColumnBackedArg` has a single column slot, so on that carrier *both* arms are payload-free: local means "`columns()` bind to the own table", remote means "`columns()` bind to the terminal table". On the reference carriers only `Remote` is payload-free; `Local` has to carry the lifted tuple, which `columns()` cannot supply. So a `Local(List<ColumnRef> ownTableColumns)` shared by both families makes `ColumnBackedArg` copy its own `columns()` into the arm, which is the same unenforced-duplicate risk this section rejects for `Remote`. Three ways out, in preference order: give `Local` an accessor that reads through to `columns()` when the arm's list is empty (rejected, that is the sentinel again); keep `Local`'s tuple and have `ColumnBackedArg`'s construction sites pass `columns()` explicitly, accepting a derivable-but-restated slot on one family; or split the leg out and leave `ColumnBackedArg` on its sentinel for now. The middle option is the spec's default: the duplication is confined to construction sites the compact constructor can check (`Local`'s tuple must be non-empty, and on `ColumnBackedArg` it is the same arity as `columns()`), which is strictly better than a sentinel no switch can see.

Strangler position: `FilterBinding` is not a new leaf type and not a walk-side registry; it de-overloads an existing slot on existing leaves and deletes two ad-hoc discriminations, net-subtractive.

Alternatives rejected: an empty-`liftedSourceColumns` sentinel (an implicit fork where an empty list means "read the other slot", invisible to the constructor and to every switch); splitting each carrier into two records (doubles every switch in `TypeFetcherGenerator`, `CatalogBuilder`, `UpdateRowsWalker`, and the validator for what is a single emission fork). The wrapping-over-flag posture follows `RemoteColumnPredicate`'s own javadoc, which keeps the local-vs-remote axis off the operator/value-arity taxonomy; that javadoc currently names only `@reference(path:)` and broadens to name the FK-target `@nodeId` path.

### Classifier unchanged, consumers stop rejecting on the read path

`NodeIdLeafResolver` keeps producing `TranslatedFk`; the classification truth is unchanged. The two consumer sites stop converting it into a rejection:

- Argument site (`FieldBuilder`, the `TranslatedFk` case arm beside DirectFk's): build `ColumnBackedReferenceArg` with the `Remote` binding (`columns` already carry the keyColumns). The existing `@lookupKey` guard applies unchanged (FK-target is a filter, not a lookup).
- Input-field site (`BuildContext`, the parallel case arm): build `InputField.ColumnBackedReferenceField` with the same binding. `TranslatedFk` carries no `selfReference` flag and the read path does not need one, so the construction site passes `false`, matching the carrier's own javadoc ("every non-self-FK construction site sets `false`"). The slot's one reader is the UPDATE SET partition, which rejects a `Remote` binding before it gets there, so the value is unreachable rather than merely unused; the write path is out of scope below.

Extraction stays `CallSiteExtraction.ThrowOnMismatch(decodeMethod)` at both sites: a malformed or wrong-type id on an authored filter throws rather than narrowing the result set.

### Emission is composition of shipped parts

`projectFilters` (arguments) and the implicit-predicate half of `walkInputFieldConditions` (input fields) build the inner predicate against the binding's columns and wrap when the binding is `Remote`. Everything downstream already handles the wrapped shape: `bodyParamCallTypeName` delegates to the inner predicate, `TenantBindingIndex` has a `RemoteColumnPredicate` arm, `ConditionCommands.termOf` recurses into the inner predicate so composite keys ride the existing `RowEq` / `RowIn` handling, `appendGuardedAnd` keeps the null-scalar and empty-list guards, and `reachExists` renders the EXISTS.

An authored `@condition` on a translated field wraps in `FkTargetConditionFilter` exactly as it does for DirectFk, and the emitter path is unchanged since it already correlates through `joinPath`. Its `liftedSourceColumns` slot is carried "for symmetry and validation" and has no own-table tuple to hold in the Remote case; the implementation threads the binding through (or narrows the slot) rather than inventing a placeholder tuple. The validator arm in `GraphitronSchemaValidator.validateInputColumnBackedReferenceField` requiring every hop to be FK-derived applies as-is.

### Write and lookup rails keep a deferral, gated uniformly and compile-checked

Every consumer that reads the lifted tuple as own-table columns must refuse a `Remote` binding, and the set is wider than the two DML walkers. The full list, from a sweep of `liftedSourceColumns()` readers in main sources:

- `UpdateRowsWalker.classifyInto` and `DeleteRowsWalker.classifyInto`. These are *parallel* methods, not one shared helper: the DELETE one's javadoc calls itself "the DELETE analogue of `UpdateRowsWalker.classifyInto`". Two gates, or make the sharing part of this item.
- `MutationInputResolver.collectSetColumns`, its own SET-side recursion over `InputField`, reached by neither of the above.
- `EnumMappingResolver`'s `ColumnBackedReferenceField` arm (`buildLookupBindings`), which derives `TableInputArg.fieldBindings` and pairs lifted columns slot-for-slot with the decoded record's accessors.
- The INSERT / UPSERT column readers in `TypeFetcherGenerator` (`setFieldColumns` / `setFieldNodeIdExtraction`, plus the SET-statement arms that walk `crf.liftedSourceColumns()` directly).
- The lookup rail: `ArgumentRef.TableInputArg.of` filters by `instanceof LookupKeyField` and `ColumnBackedReferenceField` is in that permits set; `FieldBuilder.classifyPlainLookupKeyArg` rejects only non-`LookupKeyField` carriers; the `LookupKeyField` javadoc's "no JOIN context at the emit site" contract is true by type today and becomes per-instance once the reference carrier can be `Remote`-bound.

Honest write support would mean scalar-subquery SET / INSERT values with new failure modes (an id naming no target row); that is a separate feature.

Two structural rules keep this from becoming scattered `instanceof Remote` tests:

- Gate at each seam that derives a capability list, with an exhaustive `switch` over `FilterBinding`, so a future third arm breaks every gate at compile time. There are four such seams, not one: `TableInputArg.of`, the two walkers' `classifyInto`, and `EnumMappingResolver.buildLookupBindings`. "Gate once" is the shape of each gate, not their count; the count is the price of four independent walks over the same field list, and collapsing them is its own item, not this one. The rejection is a shared directed `Rejection.deferred` that no longer cites output-side projection emission; it states that a translated FK-target `@nodeId` cannot be written (or used as a lookup key) without a key-to-FK-column subquery, which is unimplemented. The `LookupKeyField` javadoc's admissibility sentence is updated to name the gate.
- Every write-side accessor destructures `case FilterBinding.Local(var ownTableColumns)` rather than calling a shared getter, so no consumer can read a Remote tuple as own-table columns without a compile error. This is also what makes the enumeration above safe rather than load-bearing: retiring the `liftedSourceColumns()` accessor breaks every reader of it, so a site missed by the list surfaces as a compile error at implementation time rather than as a wrong predicate at runtime. Sites that turn out to be unreachable behind an earlier gate say so in a comment and throw; they do not silently `continue`.

This moves the admissibility decision from classification (context-free) to the consumers that actually cannot emit, the established pattern for context-dependent admissibility (`ConditionOwnedField` and `UnboundField` are fired by the filter walk and rejected by the DML walkers at their own arms). The precedent forks on leaf type where exhaustive switches do the enforcing; this fork is on a component value, which is exactly what the destructuring rule restores. `translatedFkRejection` itself retires from the read path; if no caller remains it is deleted.

## Scope

- Single-hop `TranslatedFk` paths (all the classifier produces for this arm today), argument and input-field sites, scalar and list arity, single-column and composite node keys.
- `FilterBinding` unification across all three current spellings of the local-vs-remote axis, including the `ColumnBackedArg` empty-`joinPath` sentinel (see the carrier-fork section).
- New composite pathological fixture in `nodeidfixture`: a parent with a composite node key and a child FK targeting a *different* composite unique constraint. `parent_node` + `child_ref` covers single-key; `reordered_pk_parent` + `reordered_fk_child` is the permuted DirectFk case, not a translation. Two files move together: the `CREATE TABLE` pair in `graphitron-sakila-db/src/main/resources/init.sql` and the matching `NodeIdFixtureGenerator.METADATA` entry that publishes `__NODE_TYPE_ID` / `__NODE_KEY_COLUMNS`. The `too_wide` comment in `init.sql` already records that coupling.
- Execution-tier fixture wiring, whose cost is set out under Test surface below.
- Retire the stale message: the read path stops rejecting; the write-path deferral gets its own wording as above.

## Out of scope (file separately if a real schema reaches them)

- Write-target translation (scalar-subquery SET / INSERT emission).
- Multi-hop translated paths and condition-join hops. The rejection surface for condition-join `@nodeId` paths is `NodeIdLeafResolver.resolveFkJoinPath`'s condition-step gate, upstream at classify time; `FkHop.narrow` is only the plan-time backstop (its own message says the validator must reject first) and the validator mirrors in `GraphitronSchemaValidator.validateInputColumnBackedReferenceField` stay as-is.
- R24's output-side JOIN-with-projection emitter.

## Test surface

- `NodeIdLeafResolverTest`: TranslatedFk classification assertions unchanged.
- Pipeline tier: `NodeIdPipelineTest.ArgumentFkTargetNodeIdCase.FK_TARGET_PATHOLOGICAL_KEY_MISMATCH_DEFERRED` and the parallel input-field case flip from asserting the rejection substrings to asserting classification (carrier shape, `Remote` binding). No assertions on generated method bodies; that tier rule is absolute.
- Execution tier: what this tier uniquely pins is semantics, that the translation returns the right rows. The emitted SQL *shape* is reachable more cheaply: `ReferenceFilterRemoteColumnPipelineTest` already pins the `RemoteColumnPredicate` lowering for this exact machinery at the pipeline tier, and `ConditionRenderTestSupport` (produce then render) reaches the rendered glue method. Assert shape there and semantics here, using the `ExecuteListener` structural-token approach documented in `docs/architecture/reference/argument-resolution.adoc`. Cases: filter `child_ref` rows by `ParentNode` ids, scalar and list, empty list contributes no conjunct, malformed / wrong-type id throws `GraphitronClientException`. Composite twin over the new fixture lands in the same item.

  **This bullet is not four assertions, and sizing the item on it is the main way to get the estimate wrong.** `nodeidfixture` has never been wired into the execution module. `parent_node` and `child_ref` exist in `init.sql` but carry no seed rows, and the schema is referenced only by `graphitron`-module tests: no `.graphqls`, no generator execution, and no test in `graphitron-sakila-example` names it. Two ways to close that, and the choice belongs in this spec before implementation starts:

  - *Put the translated-FK pair in the `public` schema instead.* `graphitron-sakila-example`'s main generator execution already runs over `public` via `${jooqPackage}` (`no.sikt.graphitron.rewrite.test.jooq`), so this costs two `CREATE TABLE`s plus seed rows in `init.sql` and a few types in the existing `schema.graphqls`, with no build wiring at all. Recommended. The cost is one more fixture pair in the schema the execution tests share.
  - *Wire `nodeidfixture` into the execution module.* Follow the `multischemafixture` precedent already in `graphitron-sakila-example/pom.xml`: a new `<execution>` with its own `jooqPackage`, a dedicated `.graphqls`, seed rows, and a test class. Keeps fixture schemas cleanly separated, but is comparable in size to the rest of this item, so it belongs in Scope explicitly rather than inside a test bullet.

  If neither is taken up here, drop the execution tier from this item and file it separately rather than leaving the bullet to be quietly skipped.
- Write and lookup rails: one case per gate, so all four seams are covered rather than the two that happen to be easiest to reach. A translated carrier on an UPDATE input (`UpdateRowsWalker`), on a DELETE input (`DeleteRowsWalker`), on an INSERT input (`MutationInputResolver` / the `TypeFetcherGenerator` SET readers), and on a `@lookupKey` coordinate (`TableInputArg.of` and `EnumMappingResolver.buildLookupBindings`) each surfaces the new directed deferral at validate time.
