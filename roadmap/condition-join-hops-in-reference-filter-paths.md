---
id: R705
title: "A condition-join hop in a reference filter path is rejected, though the emitter it needs already ships"
status: Spec
bucket: feature
priority: 3
theme: classification-model
depends-on: []
created: 2026-08-18
last-updated: 2026-08-19
---

# A condition-join hop in a reference filter path is rejected, though the emitter it needs already ships

A `@reference(path:)` on an output field may join through a developer-supplied condition method (the
`{condition: {...}}` path element, `On.Predicate` on the resolved hop). The same path element on a
*filter* carrier, an input field or a query argument reaching a column on a joined table, is
rejected at classify time. The author-facing message is:

> argument 'x': @reference filter path traverses a condition-join (non-foreign-key) hop, which is
> not yet supported; reference filters emit a foreign-key correlated subquery and require every
> hop to resolve to a foreign key

Two sites enforce it: the argument arm in `FieldBuilder` (`classifyScalarArg`'s `@reference` branch,
guarded by an `On.ColumnPairs` test, message shared through
`FieldBuilder.referenceFilterConditionJoinRejection`) and the input-field mirror in
`GraphitronSchemaValidator.validateInputColumnBackedReferenceField`. The mechanical cause sits below both:
`ServiceCatalog.terminalTableForReference` returns empty on the first non-`ColumnPairs` hop, so the
terminal column never resolves and the carrier cannot classify.

## Why this is worth reopening

The restriction reads as a cardinality rule but is not one. The justification a reader expects, that
a foreign key targets a unique key so the correlated `EXISTS` matches at most one row where an
authored predicate carries no such guarantee, is already settled the other way in this repo: the
changelog entry for the FK-target `@nodeId` filter work records that `EXISTS` is *"the semantically
right shape rather than a convenient one: no row multiplication when the path is non-unique, and a
NULL FK column fails the correlation instead of duplicating or dropping rows."* The `{key:}` form
also does not constrain FK direction (`synthesizeFkJoin` infers direction from which side of the
key matches the source, and no filter gate checks it), so a reverse-FK hop in a filter path
already classifies and emits `EXISTS`-over-many today by mechanism; no test pins that grain, so
item 10 adds one. Foreign-key-ness is not buying uniqueness in filter position.

What it does buy is two mechanical facts, and both are already available:

* **A resolved terminal table**, so `@field(name:)` finds a real column.
  `ServiceCatalog.terminalTableForReference`'s javadoc states that *"a condition-only step's target
  table is unknown at build time"*. That is stale. `BuildContext.parsePathElement`'s condition arm
  resolves the target through `ConditionJoinTargetResolution` and stores it as a
  `TableExpr.Catalog`, which `JoinStep.Hop.targetTable()` folds back for the uniform read. The
  terminal is known; only this walker refuses to use it.
* **A correlation predicate for hop 0.** The filter emitter (`ConditionGlueRenderer.reachExists`)
  reads `FkHop.pairs()` directly, and `FkHop.narrow` accepts nothing but `On.ColumnPairs`. The
  sibling projection emitter, `PathFragments.correlationWhere`, already dispatches the full seal and
  has the arm this needs: `case On.Predicate pred -> emitTwoArgMethodCall(pred.condition(),
  parentLocal, firstAlias)`. The correlated `EXISTS` for a condition hop is a two-argument call
  against the parent local and the hop alias, which is the shape `appendHopFilters` also emits for
  per-hop `filter()` methods.

So the deferral is a scope boundary drawn when reference filters first shipped, not a semantic
position. The generated SQL for the condition case is the developer's own predicate inside the same
`EXISTS` the FK case builds, which is exactly what the sibling emitter proves.

## Consumer motivation

This is the last thing blocking a direct port of a v9 filter shape. Under v9 a `{condition:}` hop
in filter position was declarable: the frozen `legacy-directives.graphqls` snapshot permits
`@reference` with a `condition:` element on arguments and input fields with no positional
restriction. (The legacy generator left this tree, so whether the v9 runtime executed the shape
correctly is not verifiable from here; the port-blocking claim rests on the declared surface.) So
schemas expressed a filter reaching a joined table's columns without the join being a declared
foreign key. Where the underlying relationship *is* conventionally a foreign
key and merely undeclared, the right consumer fix is to declare it (a jOOQ `<syntheticObjects>`
`<foreignKey>` entry, then swap the path to `{key:}`), and that is strictly better than lifting this
restriction: it makes the relationship a catalog fact once instead of restating the predicate at
every filter field, and it unlocks auto-discovery, `{table:}` paths, multi-hop `@nodeId` chains
(FK-only at every position by `NodeIdLeafResolver`), and LSP completions along with it.

But that remedy only exists when the predicate really is a key equality. The shapes the
`condition:` form was reserved for, date-range overlaps, prefix and computed predicates, anything
with no key to declare, have no synthetic-FK equivalent. For those the only path today is to
hand-write the whole `EXISTS` inside a plain `@condition` method on the input field, which receives
the field's own table and the filter value. That works, and is why nobody is hard-blocked, but it
re-implements per filter field the correlation the generator already owns, and it opts the field out
of the generated filter machinery (`@field` resolution, enum mapping, the override cascade).

The sibling item on per-participant `@nodeId` filter paths records the same failure mode from the
other direction: a filter carrier whose join cannot be stated, whose author fallback is to
reimplement generator-owned plumbing inside a condition method.

## Plan

The projection emitter is the design precedent throughout: every step below moves the filter rail
onto the vocabulary the projection rail already dispatches (`JoinStep.Hop` plus the `On` seal),
rather than teaching the FK-shaped filter types new arms. The body names symbols rather than line
numbers; re-locate at pickup. A principles consultation reshaped items 1 through 5 from the first
draft; where a draft position was reversed, the reversal and its reason are stated inline.

### 1. Make `ServiceCatalog.terminalTableForReference` total over what can reach it

`JoinStep.Hop.targetTable()` resolves off `target` (a `TableExpr`), never off `on`, so the walk can
advance through an `On.Predicate` hop exactly as through `On.ColumnPairs`. The consultation pass
sharpened the shape beyond a widening: the walker's `Optional` is today an unnamed rejection
channel that folds "the path shape is unsupported" into "no such column", which is exactly how the
scalar-leaf output field (item 7) ended up with its accidental generic rejection. And the only
empty case that would remain after widening, `On.Lateral`, is unconstructable through `parsePath`
(`TableExpr.RoutineCall` is minted only by `FieldBuilder`'s routine arms, never by
`parsePathElement`; re-verify at pickup). So make the walk total:
`TableRef terminalTableForReference(List<JoinStep.Hop> path, TableRef start)`, folding
`hop.targetTable()` with a throwing `On.Lateral` arm that mirrors
`PathFragments.emitBackwardBridging`'s posture on shapes its callers cannot legally hold.
`resolveColumnForReference`'s `Optional` then means exactly one thing, a missing column, and the
two candidate-hint call sites drop their empty-list fallbacks (`.orElseGet(List::of)` in
`BuildContext.classifyInputField`'s branch, `.orElse(List.of())` in the scalar-leaf branch), so a
mistyped column on a condition-hop terminal gets real candidate names in the rejection. Correct
the stale javadoc sentence, "a condition-only step's target table is unknown at build time". All three callers
(`FieldBuilder`'s scalar-arg `@reference` branch, `BuildContext.classifyInputField`'s
plain-`@reference` branch, and `FieldBuilder`'s output scalar-leaf branch) consume only the
terminal table or column, never FK pairs, so no FK-only variant of the walk needs to survive.

### 2. One resolution rule for a condition hop's target, keyed on the available source

Both filter sites pass `targetSqlTableName = null` into `parsePath` (`FieldBuilder`'s scalar-arg
branch, `BuildContext.classifyInputField`), so `resolveConditionJoinTarget`'s terminal arm
AuthorErrors on a terminal `{condition:}` element today, with a message about the return type that
reads as nonsense at a filter site. Since a single-element path is both position 0 and terminal,
this arm blocks the primary use case outright.

The first draft patched this positionally (fall through to the reflection arm when terminal with no
target name) and documented the result as an output-vs-filter asymmetry. The consultation pass
showed the asymmetry is an artifact of the spliced `(boolean isTerminal, String
terminalTargetSqlName)` parameter pair: what decides the answer is not the hop's position but the
available source. Collapse to one rule,
`resolveConditionJoinTarget(methodRef, TableRef declaredTarget)` with `declaredTarget` nullable
(the return type's `@table` binding when the site has one): prefer the declared target when
present, otherwise reflect the method's second parameter through `JooqCatalog.findTableByClass`,
and drop `isTerminal`. The same authored `{condition:}` element then resolves the same way
wherever it is read from. Rejection messages state the fact rather than a position ("no
return-type `@table` binding is available here, so the target is read from the method's second
parameter, which must be a concrete generated jOOQ table class"); the current "intermediate-hop"
wording would misname a terminal filter-site wildcard. Documented consequence in the manual:
filter sites always resolve through the method signature, so a condition method used at a filter
site needs concrete table parameter types where an output field's terminal hop tolerates
`Table<?>`. The collapse also shifts output-site behavior, stated here so the reviewer sees both
sides: today a terminal condition hop whose return type lacks a `@table` binding AuthorErrors on
the missing binding; under the collapsed rule it falls through to reflection and errors only when
the method's second parameter is not a concrete generated table class. That is the intended
improvement (the reflection answer is just as authoritative at an output terminal as at a filter
site), not an accident of the collapse. `validateConditionParamTables` keeps firing unchanged;
hop 0's origin table is the filter's own start table, which is always resolved at both sites.

### 3. Retire one rejection, restate the other

`FieldBuilder`'s classify guard and its `referenceFilterConditionJoinRejection` helper are deleted,
and the plain-`@reference` mirror block in
`GraphitronSchemaValidator.validateInputColumnBackedReferenceField` is not merely removed but
replaced by the widened acceptance: the pipeline cases of item 10 become the enforcer of the new
contract. Note for the reviewer: neither rejection message is exercised by any test today (no test
in the tree asserts either message text), so the deletion removes untested code and item 10's cases
are the first enforcement either surface gets, on either side of the flip.

The sibling block in the same method, requiring an FK path for `@condition` on an FK-target
`@nodeId` field, stays, but its footing changes and the plan says so explicitly: its rationale
comment currently justifies it as a mirror of the glue renderer's FK-only reach precondition, and
item 4 deletes that precondition. What remains is a deliberate policy deferral (that shape binds
decoded id columns and is a different mechanism), so the block's rejection becomes
`Rejection.deferred` rather than `structural`, its comment states the deferral instead of the
retired emitter fact, and a pinned test stands on its own rather than riding the old invariant.

No lateral guard is needed at the filter sites: per item 1, a `parsePath`-built path cannot contain
an `On.Lateral` hop, and the walker's throwing arm is the backstop enforcer if that fact ever
changes.

### 4. Reach becomes a hop-typed path carrier; `FkHop` retires

The load-bearing constraint. Today `ConditionCommands.narrowPath` narrows the classified
`List<JoinStep>` to `List<FkHop>` at produce time (`FkHop.narrow` accepts only `On.ColumnPairs`),
and `ConditionGlueRenderer.reachExists` reads `.pairs()` unconditionally. Decision: retire `FkHop`;
the renderer dispatches `hop.on()` per hop the way the projection sibling already does. The model's
own voice has settled this question: `ParentCorrelation.OnParentJoin` deliberately exposes no
`condition()` accessor and instructs consumers to dispatch the hop-0 attach on `firstHop().on()`
per `JoinStep`'s two-axis model.

The reach slot becomes a small path-grain carrier rather than a bare list: a
`ReachPath(List<JoinStep.Hop> hops)` record whose compact constructor rejects a lateral hop once,
at construction, replacing `FkHop.narrow`'s per-hop throw as the produce-time enforcer.
`ColumnTerm.reach()` and `Predicate.Authored.reach()` carry it, and
`ConditionGlueRenderer.declareReachAliases` keys its alias map on the named carrier instead of a
raw `List`. One semantic to preserve deliberately: the current map is an `IdentityHashMap` on
purpose (its javadoc mints locals per reach *occurrence*, `reachIndex` per reach), and a record
carrier is equals-keyed, so a plain `HashMap` on `ReachPath` would merge structurally equal
reaches from different terms. Merging is safe for the generated SQL (each `EXISTS` is a
self-contained subquery) but leaves dead locals and is a separate decision this item does not
take; the map stays identity-keyed on the `ReachPath` instance.

This is deliberately not the rejected sealed per-hop vocabulary (`Keyed`/`Conditioned` arms): that shape carries nothing `JoinStep.Hop` + `On` cannot, and every
shared helper of item 5 would have to translate across it. One carrier, one check, and the hops
stay `JoinStep.Hop` + `On`.

The same type lift extends to the model components this item's diff already rewrites:
`ParsedPath.elements()`, `BodyParam.RemoteColumnPredicate.joinPath`, and the `joinPath` components
of `ArgumentRef.ScalarArg.ColumnBackedArg` and `InputField.ColumnBackedReferenceField` declare
`List<JoinStep.Hop>` instead of the sealed root (`JoinStep` permits only `Hop`, so the lift is
mechanical), retiring the `instanceof JoinStep.Hop` narrowings on those paths.

This shrinks the census R485 works over rather than depending on it, but note what R485 actually
is: its own scope is a helper pair (`isFkHop`/`pairsOf`) consolidating the ~40 inline FK-hop
narrowing idioms, not a type lift, and this diff both deletes a slice of those idioms and retires
`FkHop`, whose narrowing was one of them. R485's body carries a scope note to that effect (added
alongside this revision); components this diff does not touch keep the root type.

The `FkTargetConditionFilter` arm of `ConditionCommands` (the `@nodeId` + `@condition` reach) keeps
its FK-only guarantee upstream at item 3's restated validator deferral, not through the type
system; its reach simply never contains a condition hop.

### 5. The shared dispatch lives in `PathFragments`; `JoinFragments` stays untouched

`reachExists` needs two dispatch points: the backward bridging join for interior hops (today
`JoinFragments.emitBridgingJoin`, pairs-only) and the hop-0 correlation (today
`JoinFragments.emitCorrelationWhere`, pairs-only). The projection rail already owns both arms:
`PathFragments.emitBackwardBridging` emits `.join(prev).on(twoArgCall(cond, prevAlias, hopAlias))`
for a predicate hop, and `correlationWhere`'s `OnParentJoin` arm emits
`twoArgCall(cond, parentLocal, firstAlias)`.

The first draft floated hoisting the dispatch into `JoinFragments`; that inverts two stated class
charters (`JoinFragments` is the below-narrowing layer whose every entry point takes a proven
`On.ColumnPairs`, never a raw `On`; `PathFragments` holds the arms above that narrowing). So the
dispatch stays where the charters put it: extract
`PathFragments.hopZeroCorrelation(JoinStep.Hop hop, String firstAlias, String parentLocal, String
pathKindLabel)` from the inner switch of `correlationWhere`'s `OnParentJoin` arm and have that arm
delegate to it; `ConditionGlueRenderer.reachExists` then calls `hopZeroCorrelation` for the hop-0
correlation and the existing `PathFragments.emitBackwardBridging` for interior hops. The filter
renderer ends up with zero seal switches of its own, a future `On` arm has exactly one place to
land per dispatch point, and `JoinFragments`' charter stays true. The condition method's call
convention is unchanged: `(source, target)` where source is the previous hop's table (the filtered
table itself at hop 0) and target is the hop's own table, the same convention `appendHopFilters`
and the projection arms already emit.

### 6. All positions supported

Condition hops are admitted at hop 0, interior, and terminal positions, and mix freely with `{key:}`
and `{table:}` hops. The item floated a position-0-only first cut; it is rejected because (i) the
emitter dispatch of item 5 is uniform per hop, so restricting positions saves nothing, (ii) interior
condition hops already resolve via the reflection arm today, and (iii) a position-0-only rule would
still have to solve item 2, since a one-element path is position 0 and terminal at once.

### 7. The scalar-leaf output unlock rides along

The output-side scalar `@reference` leaf field (a scalar field reaching a joined table's column,
`ChildField.ColumnBackedReferenceField`) has no directed guard; it fails today with a generic
unknown-column rejection because the same walker returns empty. Item 1 unlocks it mechanically, and
its emitter (`SelectTerm.ScalarSubselect` through `PathFragments`) already dispatches the full seal;
the unit-tier validation case (`ColumnReferenceFieldValidationTest.CONDITION_METHOD`) already pins
the shape as legal on a hand-built carrier. Include the unlock deliberately with pipeline and
execution coverage rather than adding a new guard to preserve an accidental rejection; relaxing a
producer's check obliges auditing every consumer of the relaxed shape in the same change, and the
walker has exactly three consumers.

The unlock is more than a new emitter arm becoming reachable: it lets this carrier hold
`ParentCorrelation.OnParentJoin` where only `OnFkSlots` was reachable before, and the arm choice
fixes both correlation topology and batch grain (the batch keys on the parent PK and the query
anchors the parent table, per `parentKeyColumns()`). So the coverage owed is every consumer of
`ParentCorrelation` on this carrier handling `OnParentJoin`, not just the inline `ScalarSubselect`
render. `ParentCorrelationArmTest` is not that seam: it pins the model type's own per-arm behavior
(construction invariants, `parentKeyColumns()`), not consumer dispatch. The audit therefore walks
the consumers directly and extends whichever emitter tests exercise them; the arm test grows a
case only where a model-level fact (the batch grain of a predicate-hop `OnParentJoin`, say) is
what needs pinning.
If that audit surfaces a real emitter gap, the gap gets its own item and this arm gets a directed
rejection instead; silent breakage is the only unacceptable outcome.

### 8. Rails posture

Read rail only, and no new code makes it so: the `@lookupKey` gate and the three write-rail gates
dimension on `FilterBinding.Remote` vs `Local`, not on hop kind, so a condition-hop reference filter
is already rejected there by the existing `remoteBindingUnsupported` machinery. State it in the
manual alongside the FK case. The gates' message text speaks of FK-target `@nodeId`; generalizing
that wording is cosmetic and out of scope.

### 9. Docs

`docs/manual/how-to/join-with-references.adoc` changes fold in the stale-claims correction the item
flagged as adjacent (same file, same sections, and lifting the filter restriction makes them doubly
wrong):

* Retire the "Foreign-key hops only" section's rule; replace with the lifted contract, including
  the concrete-parameter-type requirement from item 2.
* Rewrite "The `condition:` form (classify-only today)": condition-join references execute today
  (inline, batched split-rows, FK-then-condition bridging, backed by
  `ReferencePathConditionFixtures`), so the runtime-throwing-stub claim, the
  `UnsupportedOperationException` example, the combining table's "runtime arm has not shipped"
  sentence, and the pitfalls-list repeat all go.
* Keep the recommendation that a conventionally-foreign-key relationship should be declared as a
  jOOQ synthetic FK and filtered through `{key:}`; the condition form is for predicates with no key
  to declare.

For the reviewer's benefit the two doc corrections carry different evidence: the FK-only filter
rule is retired on the strength of this item's own new tests, while the "classify-only /
runtime-throwing stub" claims are truth-fixes for behaviour already shipped and already covered by
the existing execution fixtures.

### 10. Tests

Per the testing rubric, pipeline beats unit beats execution where the assertion fits the tier:

* **Pipeline** (`ReferenceFilterRemoteColumnPipelineTest`): `surface2_conditionJoinPath_isRejected`
  is the only rejection pin either surface has today (the input-field surface has none). It flips
  to acceptance, and the input-field surface gains its first cases alongside it, asserting the
  classified carrier and the generated `TypeSpec` shape (the `EXISTS` embedding the two-arg method
  call). These cases carry the contract rather than flipping existing pins; per item 3, the deleted
  guards were never test-enforced. Add mixed-path cases in both orders (condition-then-key,
  key-then-condition) and truth-table entries in `GraphitronSchemaBuilderTest` beside the existing
  output-side condition-hop rows.
* **Unit**: a validator case proving `validateInputColumnBackedReferenceField` accepts a
  condition-hop filter carrier, and a pinned case for item 3's restated FK-target `@nodeId`
  deferral standing on its own now that its old emitter rationale is gone. Item 7's consumer audit
  lands its coverage in the consumers' own tests per that item; `ParentCorrelationArmTest` grows a
  case only for model-level facts.
* **Execution** (`GraphQLQueryTest`, new fixtures beside `ReferencePathConditionFixtures` with
  concrete parameter types per item 2): a filter through a single terminal condition hop; a filter
  through an FK-then-condition bridge; assertions on `EXISTS` grain (no row multiplication when the
  path matches many rows, no dropped parents on non-match). Also a filter through a
  reverse-direction `{key:}` hop (parent reaching into its children's columns), pinning the
  `EXISTS`-over-many grain the motivation section leans on, which no test proves today. One case
  each for the scalar-leaf unlock of item 7. Compilation-tier coverage rides the fixtures for free.

## Non-goals

* `@condition` on an FK-target `@nodeId` field keeps requiring an FK path (the surviving validator
  block); that shape binds decoded id columns and is a different mechanism.
* `On.Lateral` hops in filter paths stay unsupported; they are unconstructable through `parsePath`
  today, and the walker's throwing arm plus `ReachPath`'s constructor are the backstops if that
  changes.
* `@lookupKey` and the write rails stay closed to every `Remote` binding; no wording changes.
* R676 (per-participant `@nodeId` filter paths) is untouched; it needs a path *surface*, not this
  item's path *semantics*.

## Retired vocabulary

* `FkHop`, `FkHop.narrow`, and `ConditionCommands.narrowPath` (item 4 retires all three).
* `referenceFilterConditionJoinRejection` and its message text, "traverses a condition-join
  (non-foreign-key) hop, which is not yet supported", in both its `FieldBuilder` and validator
  copies.
* The walker javadoc claim "a condition-only step's target table is unknown at build time".
* The "proven FK" reach framing wherever prose states it: `ColumnTerm.reach`'s "proven FK hop
  path", `ConditionGlueRenderer`'s "the row's proven FK hops", and the validator's "defensive
  mirror against future path-resolution changes" rationale comment (the change it guarded against
  is this item).
* The manual's "Foreign-key hops only" rule and the "classify-only today" / runtime-throwing-stub
  framing of the `condition:` form.

## Acceptance

Full reactor green (`mvn install -Plocal-db`), including the new execution fixtures against
PostgreSQL. The pipeline truth table carries acceptance rows for filter-position condition hops on
both surfaces. The manual page states the lifted contract with no stale claims. Retirement sweep run
at the Done gate per `roadmap/workflow.adoc`.

## Adjacent stale documentation

`docs/manual/how-to/join-with-references.adoc`'s "The `condition:` form (classify-only today)"
section states that the condition-join emitter is a runtime-throwing stub and that selecting such a
field *"throws `UnsupportedOperationException` until the emitter ships a real body"*, and the
pitfalls list repeats it. That is no longer true: condition-join references execute against
PostgreSQL in the execution tier for the inline shape, the batched split-rows shape, and the
FK-then-condition bridging shape, backed by the fixtures in `ReferencePathConditionFixtures`. The
advice that production schemas should avoid `condition:`-only references now rests on a false
premise. The correction is folded into this item as part of plan item 9, since it rewrites the same
sections of the same file.
