---
id: R705
title: "A condition-join hop in a reference filter path is rejected, though the emitter it needs already ships"
status: In Progress
bucket: feature
priority: 3
theme: classification-model
depends-on: []
created: 2026-08-18
last-updated: 2026-08-24
---

# A condition-join hop in a reference filter path is rejected, though the emitter it needs already ships

A `@reference(path:)` on an output field may join through a developer-supplied condition method (the
`{condition: {...}}` path element, `On.Predicate` on the resolved hop). The same path element on a
*filter* carrier, an input field or a query argument reaching a column on a joined table, is
rejected at classify time. The author-facing message is:

> argument 'x': @reference filter path traverses a condition-join (non-foreign-key) hop, which is
> not yet supported; reference filters emit a foreign-key correlated subquery and require every
> hop to resolve to a foreign key

Two sites enforce it: the argument arm in `FieldBuilder` (`classifyArgument`'s `@reference` branch,
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
draft, and three Spec reviews reshaped items 1, 2, 4, 5, 7, 9 and 10 after that; where a position
was reversed, the reversal and its reason are stated inline, and Review resolutions below carries
the decisions that took more than a sentence.

### 1. Make `ServiceCatalog.terminalTableForReference` total over what can reach it

`JoinStep.Hop.targetTable()` resolves off `target` (a `TableExpr`), never off `on`, so the walk can
advance through an `On.Predicate` hop exactly as through `On.ColumnPairs`. The consultation pass
sharpened the shape beyond a widening: the walker's `Optional` is today an unnamed rejection
channel that folds "the path shape is unsupported" into "no such column", which is exactly how the
scalar-leaf output field (item 7) ended up with its accidental generic rejection. And the only
empty case that would remain after widening, `On.Lateral`, is unconstructable through `parsePath`
(`TableExpr.RoutineCall` is minted only by `FieldBuilder`'s routine arms, never by
`parsePathElement`; re-verify at pickup). So make the walk total:
`TableRef terminalTableForReference(List<JoinStep> path, TableRef start)`, folding
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

The parameter type stays at the sealed root deliberately, decided at the third Spec review. What
buys totality is the sealed switch, not the element type: `JoinStep` permits only `Hop`, so
`switch (step) { case JoinStep.Hop h -> ... }` is exhaustive and the compiler enforces it. Lifting
the parameter to `List<JoinStep.Hop>` would instead force every caller to hold a lifted list, and
all four pass `path.elements()` / `refPath.elements()`, which is where the cascade of the retired
type-lift proposal started (see Review resolutions, A).

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

**Which hops may see a declared target**, settled at the second Spec review and stated here so the
collapse cannot be read as licence to widen it. `declaredTarget` is *this hop's* declared target,
and only a chain-ending terminal element has one. The positional test therefore stays at the call
site in `resolvePathElements` (`isTerminal ? declaredTargetRef : null`) and only the callee's
branch collapses; the parameter that goes away is `isTerminal`, not the position rule. Handing
`declaredTarget` to every element uniformly would resolve an *intermediate* condition hop to the
carrier field's return table instead of reflecting on the method's second parameter, which breaks
`GraphitronSchemaBuilderTest.ColumnReferenceFieldCase.CONDITION_INTERMEDIATE_REFLECTS_METHOD_PARAM`
(path `[{condition: TestConditionStub.intermediate}, {table: "actor"}]` on a `film` carrier,
asserting the hop resolves to `film_actor` by reflection).

The preference direction is declared-target-over-reflection, and it is load-bearing rather than
arbitrary: preferring the declared target is what keeps `TestConditionStub.terminalWrongTarget` a
Check 2 finding (a concrete parameter disagreeing with the table the emitter will hand it) instead
of a resolution failure, which is the same fact this item asserts when it says
`validateConditionParamTables` keeps firing unchanged.

### 3. Retire one rejection, restate the other

`FieldBuilder`'s classify guard and its `referenceFilterConditionJoinRejection` helper are deleted,
and the plain-`@reference` mirror block in
`GraphitronSchemaValidator.validateInputColumnBackedReferenceField` is not merely removed but
replaced by the widened acceptance: the pipeline cases of item 10 become the enforcer of the new
contract. Note for the reviewer, corrected at the Spec review: the two messages differ in how
enforced they are today. The argument-surface message *is* pinned, by substring:
`ReferenceFilterRemoteColumnPipelineTest.surface2_conditionJoinPath_isRejected` asserts the
rejection reason contains "condition-join" and "foreign key", which is
`referenceFilterConditionJoinRejection`'s text. So flipping that case to acceptance replaces a live
pin, not dead weight. The validator's mirror message is the untested one: no test in the tree
asserts it, so on the input-field surface item 10's cases are the first enforcement either
direction gets.

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
`ReachPath(List<JoinStep.Hop> hops)` record whose compact constructor narrows the classified steps
once and rejects a lateral hop, at construction, replacing `FkHop.narrow`'s per-hop throw as the
produce-time enforcer. `ColumnTerm.reach()` and `Predicate.Authored.reach()` carry it, and
`ConditionGlueRenderer.declareReachAliases` keys its alias map on the named carrier instead of a
raw `List`. Because `ReachPath` is a new type it declares the hop-typed component freely, and its
constructor is the single narrowing site for the whole filter rail: `ConditionCommands` hands it
the classified `List<JoinStep>` and reads hops back. That is what lets this item retire `FkHop`
without lifting any existing component's declared type (see Review resolutions, A).

One semantic to preserve deliberately: the current map is an `IdentityHashMap` on
purpose (its javadoc mints locals per reach *occurrence*, `reachIndex` per reach), and a record
carrier is equals-keyed, so a plain `HashMap` on `ReachPath` would merge structurally equal
reaches from different terms. Merging is safe for the generated SQL (each `EXISTS` is a
self-contained subquery) but leaves dead locals and is a separate decision this item does not
take; the map stays identity-keyed on the `ReachPath` instance. The first Spec review floated
carrying the renderer's `reachIndex` inside `ReachPath` so a plain `HashMap` would do; declined,
because that index is minted by `declareReachAliases`'s own declaration loop and is emission-scoped
numbering. Putting it in a plan-layer record would make two structurally identical reaches unequal
for a reason the plan layer holds no opinion about. The per-occurrence semantic is stated on
`ReachPath`'s javadoc instead of the map's, which is where a reader looks for it.

This is deliberately not the rejected sealed per-hop vocabulary (`Keyed`/`Conditioned` arms): that shape carries nothing `JoinStep.Hop` + `On` cannot, and every
shared helper of item 5 would have to translate across it. One carrier, one check, and the hops
stay `JoinStep.Hop` + `On`.

No existing component's declared type changes. An earlier draft also lifted
`ParsedPath.elements()`, `BodyParam.RemoteColumnPredicate.joinPath`, and the `joinPath` components
of `ArgumentRef.ScalarArg.ColumnBackedArg` and `InputField.ColumnBackedReferenceField` to
`List<JoinStep.Hop>`, on the grounds that `JoinStep` permits only `Hop`. That is true of the seal
but not of the generic: Java generics are invariant, so the lift is a compile error at every
consumer that has not lifted with it, and `elements()` alone feeds 47 read sites across five
classifier files. Dropped at the third Spec review; the reasoning is in Review resolutions, A.

What is left of the idea belongs to R485, whose scope is a helper pair (`isFkHop`/`pairsOf`)
consolidating the ~40 inline FK-hop narrowing idioms. This diff shrinks that census incidentally
by retiring `FkHop`, whose narrowing was one of them, and R485's body carries a scope note to that
effect. Every component this diff touches keeps the root type; only `ReachPath`, which is new,
is hop-typed.

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
`PathFragments.hopZeroCorrelation(JoinStep.Hop hop, String firstAlias, String parentLocal)` from
the inner switch of `correlationWhere`'s `OnParentJoin` arm and have that arm delegate to it;
`ConditionGlueRenderer.reachExists` then calls `hopZeroCorrelation` for the hop-0
correlation and the existing `PathFragments.emitBackwardBridging` for interior hops. No
`pathKindLabel` parameter: the label exists on `emitBackwardBridging` and `correlationWhere`
because their `On.Lateral` and `OnLiftedSlots` arms interpolate it, and the inner switch being
extracted throws "ParentCorrelation.OnParentJoin cannot wrap a lateral hop", which carries none. The filter
renderer ends up with zero seal switches of its own, a future `On` arm has exactly one place to
land per dispatch point, and `JoinFragments`' charter stays true. The condition method's call
convention is unchanged: `(source, target)` where source is the previous hop's table (the filtered
table itself at hop 0) and target is the hop's own table, the same convention `appendHopFilters`
and the projection arms already emit.

**The reach also starts emitting its hops' own `filter()` predicates**, which it does not do today.
This was found at the third Spec review and folded in here rather than spun out; the reasoning for
folding it in is in Review resolutions, F. `reachExists` emits the walk-back joins, the hop-0
correlation and the inner term, and stops. It never calls `PathFragments.appendHopFilters`, and no
site in the filter rail reads `JoinStep.Hop#filter()` at all: not `ConditionCommands`, not `FkHop`,
not `ColumnTerm`, not the glue renderer. The projection sibling this item takes as its precedent
does call it, at `PathFragments.scalarInnerSelect` and at both `ProjectionUnitRenderer` sites.

The consequence is reachable today, with none of this item's changes. A `{key: …, condition: …}` or
`{table: …, condition: …}` path element folds its condition into `Hop.filter()` and leaves the hop
foreign-key-derived, pinned by `GraphitronSchemaBuilderTest.KEY_WITH_CONDITION_PRESERVES_WHERE_FILTER`
and its `{table:}` twin. So `on()` is `On.ColumnPairs`, both rejection guards pass (each tests only
`on()`), `FieldBuilder.wrapIfRemote` carries the path into `BodyParam.RemoteColumnPredicate`
verbatim, `narrowPath` narrows it, and the emitted `EXISTS` omits the author's predicate. The
generated filter is silently *wider* than the schema declares: rows the predicate should exclude
are matched.

The fix is the one call `scalarInnerSelect` already makes; `reachExists` holds the hop aliases and
the `"table"` parent local it needs. Two facts make it cheap to land here rather than separately.
Nothing pins the current behaviour: no filter carrier anywhere uses the shape, and the two folded
`{key:, condition:}` elements in `schema.graphqls` both sit on the `SplitFilterParent` output
fields, so no baseline SQL and no pipeline case has to be renegotiated. Re-check at pickup with a
scan for path elements carrying a `key:` or `table:` alongside a `condition:`. And the semantics the filter rail is losing are already
proven on the output rail by the `SplitFilterParent.targetSplit` / `targetInline` execution
fixtures, whose whole point is that a hop-0 `filter()` gives each parent its own verdict; the
filter-rail fixture in item 10 mirrors that shape. It carries its own acceptance row so the Done
gate checks it as a correctness fix rather than inside the feature.

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

Which blocker fires depends on where the condition hop sits, and the item 10 rows follow that split.
The generic unknown-column rejection is the *interior* shape (`[{condition:}, {table:}]`), where the
walker is the only blocker and item 1 alone unlocks it. The condition-only *terminal* shape fails
earlier, at `resolveConditionJoinTarget`'s terminal arm, so that carrier needs item 2 as well; it is
the row item 10 retargets.

The unlock is more than a new emitter arm becoming reachable, but state the delta precisely (the
first draft's version of this paragraph overstated it; corrected at the Spec review).
`ParentCorrelation.OnParentJoin` is *already* reachable on this carrier today: the walker admits a
`{key:, condition:}` hop because its `on()` is `On.ColumnPairs`, and
`BuildContext.buildParentCorrelation`'s `On.ColumnPairs` arm yields `OnParentJoin` whenever
`hop.filter() != null`. So the arm, and with it the parent-PK batch grain and parent-table anchoring
per `parentKeyColumns()`, is load-bearing on this carrier before this item touches anything. What
item 1 newly admits is the `On.Predicate` *occupant* inside that arm. The audit is correspondingly
narrower than "a new arm becomes reachable" would imply: consumers already dispatch `OnParentJoin`,
and the hop-0 predicate dispatch it now has to survive is the one
`PathFragments.correlationWhere` already emits. What the audit must confirm is that each consumer
reaching `OnParentJoin` on this carrier reads the correlation through that dispatch rather than
assuming column pairs behind the arm. `ParentCorrelationArmTest` is not that seam: it pins the model type's own per-arm behavior
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
* Correct the third stale claim in the same Pitfalls list, folded in at the second Spec review:
  "*`path:` must be non-empty.* `@reference(path: [])` fails graphql-java parsing." It does not.
  The empty list is legal SDL and both filter surfaces classify it and bind `Local`, pinned by
  `ReferenceFilterRemoteColumnPipelineTest.surface2_scalarArg_elementLessPath_bindsLocal` and its
  `surface1` twin. State what actually happens (an element-less path binds the carrier's own table)
  and leave whether the inert form *should* be rejected to R692, which owns that decision but not
  this false claim.
* State that a hop's own `condition:` predicate (the `{key:, condition:}` form) is emitted inside
  the filter's `EXISTS`, per item 5. The page currently says nothing either way, and until this
  item it was not true.

For the reviewer's benefit the two doc corrections carry different evidence: the FK-only filter
rule is retired on the strength of this item's own new tests, while the "classify-only /
runtime-throwing stub" claims are truth-fixes for behaviour already shipped and already covered by
the existing execution fixtures.

### 10. Tests

Per the testing rubric, pipeline beats unit beats execution where the assertion fits the tier:

The split across tiers below is the second Spec review's, replacing a single pipeline bullet that
asked for a generated-body string match. `docs/architecture/how-to/testing.adoc` bans code-string
body matching at the pipeline tier in as many words, and names the home this change actually wants:
renderer arm tests, "the preferred home for per-arm structural assertions on command-driven
emission". Item 5 is what makes `ConditionGlueRenderer`'s reach dispatch total over the `On` seal,
so the new dispatch point belongs there and the emitted SQL belongs at the execution tier.

* **Pipeline** (`ReferenceFilterRemoteColumnPipelineTest`): `surface2_conditionJoinPath_isRejected`
  is the only rejection pin either surface has today. It flips to acceptance. The input-field
  surface has five `surface1` cases already; what it lacks is a condition-hop case and a rejection
  pin of any kind, and it gains the former. Assertions stay at this tier's grain: the classified
  carrier, the `FilterBinding.Remote` verdict, and the lowered command rows (the
  `ConditionCommandsPipelineTest` shape, SDL to reach rows, no javapoet). Per item 3 the deleted
  guards were never test-enforced on the input-field surface, so these cases are the first
  enforcement either direction gets there. Add mixed-path cases in both orders (condition-then-key,
  key-then-condition).
* **Pipeline truth table** (`GraphitronSchemaBuilderTest`, beside the existing output-side
  condition-hop rows): filter-position condition hops on both surfaces. Two existing rows in the
  `ColumnReferenceFieldCase` family move, and item 2's collapse is why:
  - `CONDITION_ONLY_NO_RETURN_TYPE_TABLE_REJECTED` pins the message the collapse retires, on the
    carrier item 7 unlocks (`actorName: String @reference(path: [{condition: TestConditionStub.join}])`
    on a `@table`-bound `Film`, asserting "cannot resolve target table" and "no `@table` binding").
    Post-collapse the site has no declared target, so it reflects on `join`, whose signature is
    `(Table<?>, Table<?>)`; the case still rejects, with the wildcard-parameter message. Retarget
    it and rename it for what it now pins, a wildcard second parameter at a site with no declared
    target. The retired text is declared in Retired vocabulary.
  - Add the acceptance sibling that row leaves uncovered: the same scalar-leaf carrier with a
    concrete-typed condition method, resolving its terminal by reflection and finding the column.
    That is item 7's unlock at the truth table, and the pair makes the resolvable-versus-
    unresolvable distinction explicit rather than implied.
  - `CONDITION_INTERMEDIATE_REFLECTS_METHOD_PARAM` must stay green unchanged. It is the guard on
    item 2's positional rule (see item 2); if the collapse is implemented as a uniform
    `declaredTarget`, this row fails, which is the intended signal.
* **Renderer arm** (new `ConditionGlueRendererTest`, following `ProjectionUnitRendererTest`'s
  shape: record literals at the point of assertion, no schema, no catalog): `hopZeroCorrelation`'s
  `On.Predicate` arm, its `On.ColumnPairs` arm for the unchanged case, and the interior-hop
  bridging delegation. This is the one genuinely new dispatch point item 5 creates and the tier the
  guide builds for it; the class does not exist yet.
* **Unit**: a validator case proving `validateInputColumnBackedReferenceField` accepts a
  condition-hop filter carrier, and a pinned case for item 3's restated FK-target `@nodeId`
  deferral standing on its own now that its old emitter rationale is gone. Nothing pins either
  validator message today, so the kind change from `structural` to `deferred` breaks no assertion.
  Item 7's consumer audit lands its coverage in the consumers' own tests per that item;
  `ParentCorrelationArmTest` grows a case only for model-level facts.
* **Execution** (`GraphQLQueryTest`, with new methods on `ReferencePathConditionFixtures`, which
  already mixes wildcard and concrete parameter types, so concrete-typed additions per item 2 are
  the established shape rather than a new class): a filter through a single terminal condition hop;
  a filter through an FK-then-condition bridge; assertions on `EXISTS` grain (no row multiplication
  when the path matches many rows, no dropped parents on non-match). Also a filter through a
  reverse-direction `{key:}` hop (parent reaching into its children's columns), pinning the
  `EXISTS`-over-many grain the motivation section leans on, which no test proves today. One case
  each for the scalar-leaf unlock of item 7.
* **Execution, item 5's hop-filter fix, its own acceptance row**: a filter whose path is a single
  `{key:, condition:}` hop, over seeded rows where the hop's predicate excludes some rows the FK
  slot alone would match. Mirror `SplitFilterParent`'s shape, which proves the same semantic on the
  output rail: rows sharing an FK-slot value but differing in the column the predicate reads. The
  assertion is that the excluded rows do not come back, which fails on today's emission.
* **SQL baseline** (`ConditionSqlBaselineTest` in `graphitron-sakila-example`): the rendered
  statement for one condition-hop filter, so the `EXISTS` embedding the two-argument call is pinned
  as SQL where the guide puts it rather than as a generated-code substring at the pipeline tier.
  Compilation-tier coverage rides the fixtures for free.

## Review resolutions

Three Spec reviews (2026-08-19, 2026-08-20, 2026-08-21) raised seven items, A through G, and a
handful of non-blocking notes. All are addressed in the plan above; this section records what was
decided where the decision was not obvious from the plan text, so the next reviewer reads decisions
rather than re-deriving them. Nothing here is outstanding.

### A. The type lift is dropped, not resized

The reviews established that lifting `ParsedPath.elements()` and the three carrier components to
`List<JoinStep.Hop>` is not mechanical. `JoinStep` permits only `Hop`, so the *seal* is a
single-arm one, but Java generics are invariant, so a `List<JoinStep.Hop>` is not assignable where
a `List<JoinStep>` is expected. `elements()` is the single source feeding every path consumer in
the classifier: 47 read sites (`FieldBuilder` 31, `TypeBuilder` 4, `BuildContext` 4,
`NodeIdLeafResolver` 5, `SourceRowDirectiveResolver` 3), nine `List<JoinStep> joinPath` components
on `ChildField` plus its interface accessor, `buildParentCorrelation` and
`resolveColumnForReference`, and eight test-source files holding hand-built `List<JoinStep>`
fixtures. The lift is a compile error at each site that has not lifted with it.

The reviews offered three resolutions: lift only the three carriers and convert at their
construction sites, add a lifted `ParsedPath.hops()` sibling accessor, or declare the cascade in
scope. All three are declined in favour of a fourth: **drop the type lift from this item entirely.**

* `ReachPath` is a *new* type (item 4), so it declares `List<JoinStep.Hop>` freely, and its compact
  constructor becomes the single narrowing site for the whole filter rail. That is what `FkHop`'s
  retirement actually needed; the lift was never load-bearing for it.
* Item 1's walker does not need it either. Totality comes from the sealed switch, not the element
  type, so the walker keeps `List<JoinStep>` and all four call sites keep passing `elements()`
  unchanged. This is the third review's non-blocking note, promoted: it removes item 1's dependence
  on A's outcome, which was most of A's stakes.
* Lifting the three existing carriers would trade N read-site narrowings for one construction-site
  narrowing per carrier. That is the exact consolidation R485 exists for, and it belongs there:
  R485's scope is the `isFkHop`/`pairsOf` helper pair over the ~40 inline narrowing idioms, of
  which this diff removes a slice for free by retiring `FkHop`.
* The `hops()` sibling accessor was the tempting option and is the worst of the three. It stands two
  accessors over the same data with different static types, and consumers then migrate on no
  schedule, which is a parallel mechanism beside an existing one rather than an extension of it.

Net effect: no existing component's declared type changes, the cascade does not happen, and this
item stays a feature item.

### B. The retired diagnostic's pinned row is retargeted, and gains a sibling

`GraphitronSchemaBuilderTest.ColumnReferenceFieldCase.CONDITION_ONLY_NO_RETURN_TYPE_TABLE_REJECTED`
pins the behaviour item 2's collapse retires, on exactly the carrier item 7 unlocks. Item 10 now
names it, with the retarget spelled out: post-collapse the site has no declared target, reflects on
`TestConditionStub.join`'s `(Table<?>, Table<?>)` signature, and rejects with the wildcard-parameter
message instead, so the row is renamed for what it now pins. The retired message text is declared
in Retired vocabulary.

The row also gains an acceptance sibling rather than merely moving: the same carrier with a
concrete-typed condition method, which resolves and classifies. Pointing the existing fixture at
`TestConditionStub.intermediate` was the other option the review offered; the pair is better,
because the distinction being pinned is resolvable-versus-unresolvable and one row can only show
one side of it.

### C. Item 2 states which hops may read a declared target

Folded into item 2 as its own paragraph. The rule is that `declaredTarget` is *this hop's* declared
target and only a chain-ending terminal element has one, so the positional test stays at the call
site (`isTerminal ? declaredTargetRef : null`) and only the callee's branch collapses; the parameter
that disappears is `isTerminal`. Item 10 names
`CONDITION_INTERMEDIATE_REFLECTS_METHOD_PARAM` as the guard that fails if the collapse is
implemented as a uniform `declaredTarget`, and the acceptance list requires it green unchanged.

The second review also verified, and this is recorded so it is not re-derived, that the collapse
does not undermine `BuildContext.computeTerminalTargetVerdict`'s `On.Predicate` arm: every
`parsePath` call site passing a non-null `returnTableRef` passes that same table's name as
`targetSqlTableName`, and the gate returns `NotApplicable` when `returnTableRef` is null, so
wherever it compares, the declared target was used. Only that arm's prose goes stale, and Retired
vocabulary carries it.

### D. The tests are re-tiered

Item 10's single pipeline bullet asked for a generated-body string match, which the pipeline tier
bans in as many words, and proposed no case at the tier the guide names for exactly this change.
Item 10 is now five bullets: pipeline asserts the classified carrier and lowered command rows with
no javapoet; the truth table carries the classification verdicts; a new `ConditionGlueRendererTest`
covers `hopZeroCorrelation`'s arms from record literals, which is where per-arm structural
assertions on command-driven emission belong; execution covers grain and rows; and
`ConditionSqlBaselineTest` pins the rendered `EXISTS` as SQL.

`ConditionGlueRendererTest` does not exist yet and is new work in this item.
`ConditionSqlBaselineTest` does exist, in `graphitron-sakila-example`, and already pins condition
family `EXISTS` statements including their runtime-prefixed aliases, so the new pin is one more
string in an established harness.

### E. Retired vocabulary declares all three layers of item 2's footprint

Rewritten to name the message text, the `isTerminal` parameter plus the "intermediate-hop
`@condition` method" prefix shared by all three reflection-arm rejections, and the terminal-versus-
intermediate prose framing with its roughly ten sites enumerated by file.

### F. The dropped hop predicate is fixed here, with its own acceptance row

Raised at the third review: `ConditionGlueRenderer.reachExists` never calls
`PathFragments.appendHopFilters`, and nothing in the filter rail reads `JoinStep.Hop#filter()`, so a
`{key:, condition:}` filter path emits an `EXISTS` without the author's predicate and the filter
comes out wider than declared. The projection rail this item takes as precedent does emit it.

Folded into item 5 rather than spun out, on four grounds. The fix is the one call the projection
sibling already makes, and `reachExists` holds the aliases and parent local it needs. Nothing pins
the current behaviour: no filter carrier uses the shape and the two folded `{key:, condition:}`
elements in `schema.graphqls` both sit on the `SplitFilterParent` output fields, so no baseline SQL
or pipeline case has to be renegotiated, which makes this the cheapest moment it will ever be. This
item makes the shape more likely to be authored, since once condition hops are legal in filter
paths, `{key:, condition:}` becomes the natural spelling for "foreign key plus one more
predicate". And the semantics being
restored are already proven on the output rail by the `SplitFilterParent` execution fixtures, so
the filter-rail fixture mirrors a shape that exists rather than inventing one.

Against folding it in: it is a correctness fix inside a feature item, and a green feature suite
could hide it. That is what the separate acceptance row is for. If the fix turns out to want its own
item once someone is in the code, splitting it out is a normal In Progress plan edit, not a reopen.

### G. `JoinFragments`' charter is declared retired vocabulary

The charter names "the command's `{@code FkHop}` narrowing" as the reason its entry points may
assume `On.ColumnPairs`. Item 5 quotes that charter as its justification for keeping the new
dispatch in `PathFragments`, so the sentence is load-bearing for this item's own argument, and the
`{@code}` reference means the javadoc gate will not catch it. Declared alongside `ArgBinding`'s
`{@link FkHop}`, both with the instruction to re-point rather than delete: the mechanism is real, it
just moves to `ReachPath`'s constructor and `PathFragments`' per-hop dispatch.

### Non-blocking notes, all resolved

* **The `ReachPath` identity map.** The first review suggested carrying the renderer's `reachIndex`
  inside `ReachPath` so a plain `HashMap` would key correctly. Declined in item 4: that index is
  minted by `declareReachAliases`'s own loop and is emission-scoped numbering, so putting it in a
  plan-layer record would make two structurally identical reaches unequal for a reason the plan
  layer holds no opinion about. The map stays identity-keyed and the per-occurrence semantic moves
  onto `ReachPath`'s javadoc, where a reader looks for it.
* **`hopZeroCorrelation`'s `pathKindLabel`.** Dropped from the proposed signature in item 5; the
  extracted switch has no arm that interpolates a label.
* **Item 7's stated failure mode.** Item 7 now distinguishes the interior-hop shape, where the
  walker is the only blocker, from the condition-only terminal shape, which needs item 2 as well.
* **Item 10's fixture and "first cases" wording.** Both corrected in item 10: methods on
  `ReferencePathConditionFixtures` rather than a new class, and the input-field surface has five
  cases already, lacking a condition-hop case and a rejection pin rather than lacking cases.
* **The third stale manual claim.** Folded into item 9: the Pitfalls list says
  `@reference(path: [])` fails graphql-java parsing, and two pipeline tests pin it classifying and
  binding `Local`. R692 owns whether the inert form should be rejected, not the false claim.
* **`TenantBindingIndex` cleared for item 7's audit.** Its `BodyParam.RemoteColumnPredicate` arm
  recurses on `remote.inner()` and never reads the join path, so it is hop-kind-agnostic. Recorded
  so the audit does not spend a pass on it.

## Implementation notes

Four places the landed diff departs from the plan text, none of them a design change.

* **Where the filter-position truth-table rows landed.** Item 10 asked for them "beside the
  existing output-side condition-hop rows". Neither `ArgumentParsingCase` nor
  `InputFieldResolutionCase` had any `@reference`-filter row to sit beside on the argument
  surface, so the argument-surface verdict is a new row at the end of `ArgumentParsingCase` and
  the input-field one sits directly after `InputFieldResolutionCase.COLUMN_REFERENCE_FIELD`, its
  FK sibling. The two output-side rows are in `ColumnReferenceFieldCase` as written.
* **`PathFragments.appendHopFilters` takes `List<? extends JoinStep>`.** Item 5 needs the filter
  rail to call it with `ReachPath`'s hop-typed list, and Java generics are invariant. This is a
  parameter widening that accepts everything it accepted before, not one of the component type
  lifts Review resolution A dropped: no declared component changed, and every existing caller
  passes the same `List<JoinStep>` unchanged.
* **`declaredTargetRef` is a named helper.** Item 2 puts the positional test at the call site;
  the call site needs a `TableRef` and held only the SQL name, so the name-to-ref resolution is a
  one-line private helper in `BuildContext` rather than inlined into the condition arm. The
  positional rule itself is at the call site as specified.
* **Two new fixtures rather than one.** Item 10 asked for methods on
  `ReferencePathConditionFixtures`; the filter rows needed two, `customerToAddressConcrete` and
  `filmToFilmActor`, because a filter site reflects and the existing wildcard-typed fixtures
  cannot be reused there. `TestConditionStub` also gained `junctionToActor` for the pipeline
  tier's key-then-condition ordering case.

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

* `FkHop`, `FkHop.narrow`, and `ConditionCommands.narrowPath` (item 4 retires all three). Two
  prose sites name `FkHop` and both need a replacement rather than a deletion, because both are
  explaining a real mechanism that moves to `ReachPath`'s constructor and `PathFragments`'
  per-hop dispatch:
  - `command/ArgBinding.java`'s class javadoc links it as its exemplar ("This pairs a borrowed ref
    with producer data, like `{@link FkHop}`"). This one is `{@link}`, so the javadoc reference
    gate fires on the retirement; it needs a new exemplar, not a `{@code}` downgrade.
  - `JoinFragments`'s class charter closes with "Every entry point takes a proven
    `{@link On.ColumnPairs}` (the command's `{@code FkHop}` narrowing), never a raw `{@code On}`."
    Item 5 quotes this charter as its reason for keeping the dispatch in `PathFragments`, so the
    sentence is load-bearing for this item's own argument. The reference is `{@code}`, so the
    javadoc gate will *not* catch it and only this declaration will.
* `referenceFilterConditionJoinRejection` and its message text, "traverses a condition-join
  (non-foreign-key) hop, which is not yet supported", in both its `FieldBuilder` and validator
  copies.
* The walker javadoc claim "a condition-only step's target table is unknown at build time".
* Item 2's whole terminal-versus-intermediate framing, not just one message. The sweep reads this
  list, so all three layers are named:
  - the message text "cannot resolve target table because the carrier field's return type has no
    `@table` binding" (`resolveConditionJoinTarget`'s terminal arm);
  - the `isTerminal` parameter, and the "intermediate-hop `@condition` method" prefix, which opens
    all three of the reflection arm's rejections (fewer-than-two-parameters, wildcard target
    parameter, unresolvable parameter class);
  - the "terminal branch" / "terminal-hop arm" / "intermediate-hop arm" framing in prose, roughly
    ten sites. Four are in `BuildContext`: `resolveConditionJoinTarget`'s own javadoc, the
    `computeTerminalTargetVerdict` javadoc, that method's inline `On.Predicate` comment, and the
    Check 2 comment in `parsePathElement`'s condition arm. The rest are in
    `MultiSchemaConditionParamTest`, `TestConditionStub` (both the `intermediate` and
    `terminalWrongTarget` fixtures) and `GraphitronSchemaBuilderTest`. Re-count at pickup.
* The "proven FK" reach framing wherever prose states it: `ColumnTerm.reach`'s "proven FK hop
  path", `ConditionGlueRenderer`'s "the row's proven FK hops", and the validator's "defensive
  mirror against future path-resolution changes" rationale comment (the change it guarded against
  is this item).
* The manual's "Foreign-key hops only" rule and the "classify-only today" / runtime-throwing-stub
  framing of the `condition:` form.

## Acceptance

Full reactor green (`mvn install -Plocal-db`), including the new execution fixtures against
PostgreSQL. Then, one row per thing this item claims to deliver:

* The pipeline truth table carries acceptance rows for filter-position condition hops on both
  surfaces, and `CONDITION_INTERMEDIATE_REFLECTS_METHOD_PARAM` is still green unchanged.
* `ConditionGlueRendererTest` exists and covers both arms of `hopZeroCorrelation`.
* The scalar-leaf output carrier classifies with a concrete-typed condition method and rejects with
  a wildcard one, both pinned at the truth table.
* Item 5's hop-filter fix has its own execution row: a `{key:, condition:}` filter whose predicate
  excludes rows the FK slot alone would match, and the excluded rows do not come back. This is a
  correctness fix riding along, so it is checked on its own and not inferred from the feature's
  cases being green.
* The manual page states the lifted contract, the concrete-parameter-type requirement, and the
  hop-predicate emission, with all three stale claims gone.
* Retirement sweep run at the Done gate per `roadmap/workflow.adoc`, against the three-layer
  declaration above rather than the symbol names alone.

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
