---
id: R705
title: "A condition-join hop in a reference filter path is rejected, though the emitter it needs already ships"
status: Spec
bucket: feature
priority: 3
theme: classification-model
depends-on: []
created: 2026-08-18
last-updated: 2026-08-21
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

## Open review items (Spec review, 2026-08-19)

Two things the Spec → Ready review could not sign off without the author's decision. Everything
else in the plan verified against the tree; the two inline corrections above (item 3's testedness
claim, item 7's `OnParentJoin` premise) are already folded in.

### A. Item 4's lift of `ParsedPath.elements()` is not mechanical

The item calls the lift to `List<JoinStep.Hop>` mechanical on the grounds that `JoinStep` permits
only `Hop`. That is true of the *seal* but not of the *generic*: Java generics are invariant, so a
`List<JoinStep.Hop>` is not assignable to a `List<JoinStep>` parameter. `ParsedPath.elements()` is
not a leaf component; it is the single source feeding every path consumer in the classifier, and
lifting its declared type is a compile error at each one that has not lifted with it:

* 47 `.elements()` reads across `BuildContext`, `FieldBuilder`, `TypeBuilder`,
  `NodeIdLeafResolver`, and `SourceRowDirectiveResolver`.
* the nine `List<JoinStep> joinPath` components of `ChildField` plus its `joinPath()` interface
  accessor, all constructed from `elements()`.
* `BuildContext.buildParentCorrelation(List<JoinStep>, TableRef)` and
  `ServiceCatalog.resolveColumnForReference(String, List<JoinStep>, ...)`.
* the test-side hand-built fixtures typed `List<JoinStep>` (`ColumnReferenceFieldValidationTest`,
  `TableFieldValidationTest`, `BatchedTableFieldValidationTest`, `BatchedLookupValidationTest`,
  `InlineLookupValidationTest`, `ColumnBackedFieldInvariantTest`, `TypeFetcherGeneratorTest`,
  `TestFixtures.pcFor`).

So the lift as written forces exactly the census-wide type lift the same paragraph disclaims
("components this diff does not touch keep the root type"), and which the R485 scope note also
places outside R485. The three carrier components (`BodyParam.RemoteColumnPredicate`,
`ArgumentRef.ScalarArg.ColumnBackedArg`, `InputField.ColumnBackedReferenceField`) *can* be lifted in
isolation, but only if `elements()` stays at the root type and each construction site converts.
Pick one and say so:

. Drop `ParsedPath.elements()` from the lift set; lift only the three carriers, converting at their
  construction sites.
. Add a lifted sibling accessor (`ParsedPath.hops()`) beside `elements()`, so consumers migrate
  incrementally and this diff migrates only the sites it already rewrites.
. Declare the census-wide cascade in scope, with a size estimate, and reconcile it with the R485
  scope note.

### B. Item 2's collapse breaks a pinned diagnostic no item accounts for

`GraphitronSchemaBuilderTest.ColumnReferenceFieldCase.CONDITION_ONLY_NO_RETURN_TYPE_TABLE_REJECTED`
pins the behavior the collapse retires, on exactly the carrier item 7 unlocks. Its SDL is
`actorName: String @reference(path: [{condition: TestConditionStub.join}])` on a `@table`-bound
`Film`, and it asserts the rejection reason contains "cannot resolve target table" and
"no `@table` binding". Post-collapse that site has no declared target, so it reflects on
`TestConditionStub.join`, whose signature is `(Table<?>, Table<?>)`; the case still rejects, but
with the wildcard-parameter message. The test fails as written.

Item 2 states the output-site shift in prose, but this row is not named there, item 10 adds truth
table rows rather than retargeting one, and the retired message text is absent from Retired
vocabulary. Owed: name the row in item 10 with the intended new assertion (retarget to the reworded
reflection message, or point the fixture at `TestConditionStub.intermediate` if the case should keep
pinning a resolvable-vs-unresolvable distinction), and add "cannot resolve target table because the
carrier field's return type has no `@table` binding" to Retired vocabulary.

### Non-blocking, author's call

* **`ReachPath` and the identity map.** Keeping the map identity-keyed preserves today's semantics
  exactly (the current key, `List<FkHop>`, is equally equals-bearing, so `IdentityHashMap` is
  already load-bearing for the same reason). But `ReachPath` is a *new* named carrier, and the
  per-occurrence identity has a natural home inside it: carry the `reachIndex` the loop already
  mints, key a plain `HashMap`, and the subtlety disappears without taking the reach-merging
  decision item 4 defers. Cheaper than the paragraph explaining why the map must stay identity-keyed.
* **`ArgBinding`'s `{@link FkHop}`.** `command/ArgBinding.java`'s class javadoc links `FkHop` as its
  exemplar ("This pairs a borrowed ref with producer data, like ..."). Retiring `FkHop` fails the
  javadoc reference gate there. The retirement sweep catches it, but naming it in Retired vocabulary
  saves a build cycle; that sentence needs a new exemplar, not a `{@code}` downgrade.
* **Item 10's fixture wording.** "new fixtures beside `ReferencePathConditionFixtures`" reads as a
  new class. That file already mixes `Table<?>` and concrete-typed methods
  (`filmActorJunctionToActor`, `splitFilterParentIncluded`), so adding methods to it is the
  established shape; one word settles it.

## Open review items (second Spec review, 2026-08-20)

Independent session, different from the one that last committed this file. A and B above were
re-verified against the tree and both still stand exactly as written, so they are not restated:
the 47 `.elements()` reads across the five named files, the nine `List<JoinStep> joinPath`
components in `ChildField` plus its interface accessor, the eight test-side `List<JoinStep>`
fixtures, and `CONDITION_ONLY_NO_RETURN_TYPE_TABLE_REJECTED`'s SDL and both assertion substrings
all match. `TestConditionStub.join` is `(org.jooq.Table<?>, org.jooq.Table<?>)`, so B's predicted
post-collapse failure is the wildcard-parameter message, as it says. Three further items are owed.

### C. Item 2 must say which hops may see a declared target

Item 2's rationale, "what decides the answer is not the hop's position but the available source",
reads as licence to hand `declaredTarget` to every hop. It is not.
`BuildContext.resolvePathElements` threads one `targetSqlTableName` (the carrier field's return
`@table`) past every element and uses `isTerminal` to decide which element may read it. A
`declaredTarget` supplied uniformly would resolve an *intermediate* condition hop to the field's
return table instead of reflecting on the method's second parameter. That is wrong on its face and
it breaks
`GraphitronSchemaBuilderTest.ColumnReferenceFieldCase.CONDITION_INTERMEDIATE_REFLECTS_METHOD_PARAM`,
whose path is `[{condition: TestConditionStub.intermediate}, {table: "actor"}]` on a `film` carrier
returning `Actor` and which asserts the hop's target resolves to `film_actor` by reflection.

The rule is coherent once stated precisely: `declaredTarget` is *this hop's* declared target, and
only a chain-ending terminal element has one, so the positional test stays at the call site
(`isTerminal ? declaredTargetRef : null`) and only the callee's branch collapses. One sentence in
item 2 settles it. Also worth saying which way the preference points and why: preferring the
declared target over reflection is what keeps `TestConditionStub.terminalWrongTarget` a Check 2
finding rather than a resolution failure, which is the same fact item 2 already asserts when it
says `validateConditionParamTables` keeps firing unchanged.

Verified on the reviewer's side and recorded so the next reader need not re-derive it: the collapse
does *not* undermine `BuildContext.computeTerminalTargetVerdict`'s `On.Predicate` arm, whose
"Match by construction" rests on the terminal target coming from the return `@table`. Every
`parsePath` call site passing a non-null `returnTableRef` passes that same table's name as
`targetSqlTableName`, and the gate returns `NotApplicable` when `returnTableRef` is null, so
wherever it actually compares, the declared target was used and the tautology holds. Only the
arm's prose goes stale (see E).

### D. Item 10's pipeline bullet names an assertion form the tier guide bans

"asserting the classified carrier and the generated `TypeSpec` shape (the `EXISTS` embedding the
two-arg method call)" is method-body content, and the pipeline-tier section of
`docs/architecture/how-to/testing.adoc` says "Banned: code-string body matching". The same guide
names the home this change actually wants: renderer arm tests are "the preferred home for per-arm
structural assertions on command-driven emission", and item 5 is exactly what makes
`ConditionGlueRenderer`'s reach dispatch total over the `On` seal. Item 10 proposes no renderer-arm
case at all, which leaves the one genuinely new dispatch point uncovered at the tier built for it.

Precedent cuts both ways, so this is a decision to record rather than a flat error:
`ConditionGluePipelineTest` is a `@PipelineTier` class and already asserts
`primaryBody).contains("decodeBarRowsOrThrow(")`. But the last Done-gate review in this
neighbourhood checked "no code-string assertions on generated method bodies" explicitly, so an
implementer following item 10 literally walks into that check. Splitting the bullet costs nothing:
the pipeline tier asserts the classified carrier plus the command rows (SDL to reach rows, the
`ConditionCommandsPipelineTest` shape, no javapoet), a `ConditionGlueRenderer` arm test asserts
`hopZeroCorrelation`'s predicate arm from record literals, and the emitted `EXISTS` is pinned where
the guide puts it, at `ConditionSqlBaselineTest` or the execution tier.

### E. Retired vocabulary under-declares item 2's footprint

Item 2 retires the terminal-versus-intermediate framing, not just one message, and the sweep at the
Done gate reads this section. Add:

* the "cannot resolve target table because the carrier field's return type has no `@table`
  binding" text (already owed by B),
* the `isTerminal` parameter and the "intermediate-hop `@condition` method" message prefix, which
  opens all three of `resolveConditionJoinTarget`'s reflection-arm rejections, and
* the "terminal branch" / "terminal-hop arm" / "intermediate-hop arm" framing in prose. It sits in
  roughly ten places, four in `BuildContext` (`resolveConditionJoinTarget`'s own javadoc, the
  `computeTerminalTargetVerdict` javadoc, that method's inline `On.Predicate` comment, and the
  Check 2 comment in `parsePathElement`'s condition arm) and the rest in
  `MultiSchemaConditionParamTest`, `TestConditionStub` (both the `intermediate` and
  `terminalWrongTarget` fixtures) and `GraphitronSchemaBuilderTest`. Re-count at pickup.

### Non-blocking, second pass

* **A third stale claim in the same manual file.** `join-with-references.adoc`'s Pitfalls list
  says "*`path:` must be non-empty.* `@reference(path: [])` fails graphql-java parsing. Every
  reference declares at least one hop." It does not fail: the empty list is legal SDL, and both
  filter surfaces classify it and bind `Local`, pinned by
  `ReferenceFilterRemoteColumnPipelineTest.surface2_scalarArg_elementLessPath_bindsLocal` and its
  `surface1` twin. R692 owns the decision about whether the inert form *should* be rejected; it
  does not own the false claim. Item 9 already rewrites the neighbouring sections of this file for
  exactly this class of staleness, so folding in the one-line correction is cheaper than a separate
  pass. Author's call whether it rides here or goes into R692's body.
* **Item 10's "first cases" wording.** "the input-field surface gains its first cases alongside it"
  undersells what is there: `ReferenceFilterRemoteColumnPipelineTest` carries five `surface1` cases
  today. What that surface lacks is a condition-hop case, and a rejection pin of any kind.
* **The first pass's three non-blocking notes all check out.** `command/ArgBinding.java`'s class
  javadoc does use `{@link FkHop}` rather than `{@code}`, so the reference gate does fire on the
  retirement; `declareReachAliases`'s `IdentityHashMap` is keyed and documented as the note says;
  and `ReferencePathConditionFixtures` already mixes wildcard and concrete parameter types.

Everything else verified against the tree: both rejection sites and their message text; the walker
and its stale javadoc sentence; its three consumers and the two candidate-hint fallbacks item 1
deletes; `resolveConditionJoinTarget`'s two arms; `On.Lateral` minted only by `FieldBuilder`'s
routine arm at one site and never by `parsePathElement`; `FkHop`, `narrowPath`, `reachExists`'s
unconditional `.pairs()` reads and the `FkHop` blast radius (six main-source files, the test-side
hits being the unrelated `isFkHop` / `filteredFkHop` helper names);
`PathFragments.correlationWhere`'s `On.Predicate` arm verbatim, `emitBackwardBridging`'s signature,
and both class charters as item 5 quotes them; `OnParentJoin`'s no-`condition()`-accessor javadoc
and its dispatch instruction verbatim; item 7's corrected premise in
`buildParentCorrelation`'s `On.ColumnPairs` arm; the `FkTargetConditionFilter` reach reaching only
from the carrier the surviving validator block gates, and `NodeIdLeafResolver`'s FK-only-at-every-
position loop; the four `remoteBindingUnsupported` rail sites; all three manual claims item 9
retires plus the shipped execution fixtures that falsify them; the legacy directive snapshot's
locations and `ReferenceElement.condition`; the changelog quote verbatim; and R485's scope note and
R676's framing.

## Open review items (third Spec review, 2026-08-21)

Independent session, different from both prior reviewers and from the authoring sessions. Status
stays Spec: A through E are all still unanswered, no commit has touched this file since the second
review, and two further items are owed. A through E were re-verified rather than taken on trust and
all five hold; the re-verification is recorded compactly so the next reader need not repeat it.

* **A** holds. 47 `.elements()` reads, distributed exactly as stated (`FieldBuilder` 31,
  `TypeBuilder` 4, `BuildContext` 4, `NodeIdLeafResolver` 5, `SourceRowDirectiveResolver` 3); nine
  `List<JoinStep> joinPath` components in `ChildField`; eight test-side files declaring
  `List<JoinStep>`, the ones named.
* **B** holds. `CONDITION_ONLY_NO_RETURN_TYPE_TABLE_REJECTED` asserts both substrings, and the
  terminal arm's message is verbatim the text E asks to declare retired.
* **C** holds. `resolvePathElements` computes `isTerminal = endsChain && (localIndex ==
  totalElements - 1)` and threads one `targetSqlTableName`;
  `CONDITION_INTERMEDIATE_REFLECTS_METHOD_PARAM` asserts `film_actor` by reflection on the
  `intermediate` stub.
* **D** holds. `docs/architecture/how-to/testing.adoc` bans code-string body matching at the
  pipeline tier in as many words, and blesses renderer arm tests as "the preferred home for per-arm
  structural assertions on command-driven emission".
* **E** holds and is if anything understated: all three of `resolveConditionJoinTarget`'s
  reflection-arm rejections open with "intermediate-hop `@condition` method", and its javadoc frames
  the whole method as "the terminal-hop arm ... the intermediate-hop arm".

### F. The filter rail drops per-hop `filter()` predicates, and item 5 walks into it

`ConditionGlueRenderer.reachExists` emits the walk-back joins, the hop-0 correlation and the inner
term, and nothing else. It never calls `PathFragments.appendHopFilters`, and no other site in the
filter rail reads `JoinStep.Hop#filter()`: `ConditionCommands`, `FkHop`, `ColumnTerm` and the glue
renderer contain no `filter()` read at all. The projection sibling this item takes as its precedent
does call it (`PathFragments.scalarInnerSelect`, and both `ProjectionUnitRenderer` sites).

That gap is reachable on a filter carrier today, without any of this item's changes. A
`{key: …, condition: …}` or `{table: …, condition: …}` path element folds its condition into
`Hop.filter()` and leaves the hop FK-derived, pinned by
`GraphitronSchemaBuilderTest.KEY_WITH_CONDITION_PRESERVES_WHERE_FILTER` and its `{table:}` twin. So
`on()` is `On.ColumnPairs`, both rejection guards pass (each tests only `on()`), `wrapIfRemote`
carries the path into `BodyParam.RemoteColumnPredicate` verbatim, `narrowPath` narrows it happily,
and the generated `EXISTS` omits the author's predicate. The filter is silently *wider* than
declared: rows the predicate should exclude are matched.

This is not R705's bug, but it is R705's problem in three ways. Item 5's whole case is that the
filter rail moves onto the vocabulary the projection rail already dispatches, and this is the one
place the two rails still disagree after item 5 lands. Items 4 and 5 both cite `appendHopFilters` as
the shared calling convention, which reads as a claim that the filter rail already emits those
calls. And item 10's new mixed-path cases (condition-then-key, key-then-condition) are precisely
where an implementer authoring fixtures would hit it, most likely as a wrong-rows execution
failure with no guard pointing at the cause.

Owed: one decision, stated. Either fold the call into item 5 (`reachExists` already holds the hop
aliases and the `"table"` parent local, so it is the same one-line addition `scalarInnerSelect`
makes, plus a case pinning it), or declare it out of scope with its own Backlog item and say how
item 10's fixtures stay clear of the shape. Silently inheriting it is the outcome to avoid, because
after item 5 the divergence no longer has a mechanical excuse.

### G. The `FkHop` retirement's prose footprint is under-declared

Retired vocabulary names `FkHop`, `FkHop.narrow` and `ConditionCommands.narrowPath` as symbols, and
the first pass's non-blocking note caught `command/ArgBinding.java`'s `{@link FkHop}`. One more
prose site: `JoinFragments`'s class javadoc closes with "Every entry point takes a proven
`{@link On.ColumnPairs}` (the command's `{@code FkHop}` narrowing), never a raw `{@code On}`."

It matters more than a stray mention. Item 5 quotes this charter as its reason for keeping the
dispatch in `PathFragments`, so the sentence is load-bearing for the item's own argument, and after
item 4 the parenthetical names a retired type while the narrowing it describes has moved into
`PathFragments`' per-hop dispatch and `ReachPath`'s constructor. Because the reference is
`{@code}` and not `{@link}`, the javadoc reference gate will not catch it; only the Done-gate sweep
will, and only if it is declared. Add it beside the `ArgBinding` note, with the replacement wording
naming the new narrowing site rather than deleting the provenance.

### Non-blocking, third pass

* **Item 1's walker signature presupposes A's resolution.** The proposed
  `TableRef terminalTableForReference(List<JoinStep.Hop> path, TableRef start)` lifts the parameter
  type, and all four call sites pass `path.elements()` / `refPath.elements()`, typed
  `List<JoinStep>`. Under A's option 1 (leave `elements()` at the root type) that signature does not
  compile without a conversion at each site, which A's options do not mention. Worth noting that the
  lift is not what buys totality: `JoinStep` permits only `Hop`, so a `List<JoinStep>` walk is total
  with a sealed switch, and keeping the parameter at the root type decouples item 1 from A entirely.
  That shrinks what A has to decide.
* **`hopZeroCorrelation`'s `pathKindLabel` parameter has nothing to say.** The label exists on
  `emitBackwardBridging` and `correlationWhere` because their `On.Lateral` and `OnLiftedSlots` arms
  interpolate it. The inner switch item 5 extracts throws "ParentCorrelation.OnParentJoin cannot
  wrap a lateral hop", which carries no label. Drop the parameter from the proposed signature.
* **Item 7's stated failure mode is right for some shapes, not the primary one.** "It fails today
  with a generic unknown-column rejection because the same walker returns empty" holds for a path
  whose condition hop is interior (`[{condition:}, {table:}]` on a scalar leaf). For the
  condition-only terminal shape, the failure comes earlier, at `resolveConditionJoinTarget`'s
  terminal arm, which is finding B's case. Item 2 already carries that fact; one clause in item 7
  pointing at it would stop a reader concluding the walker is the only blocker on that carrier.
* **One consumer audited on the reviewer's side, and it is clean.**
  `TenantBindingIndex`'s `BodyParam.RemoteColumnPredicate` arm recurses on `remote.inner()` and
  never reads the join path, so it is hop-kind-agnostic and needs nothing from item 7's audit.
  Recorded so the audit does not spend a pass on it.

Everything else spot-checked against the tree and matching the plan: both rejection sites,
`referenceFilterConditionJoinRejection`'s text and the `On.ColumnPairs`-only guard at each;
`terminalTableForReference`'s body and its stale javadoc sentence verbatim; both filter sites
passing `targetSqlTableName = null` into `parsePath`; `Rejection.deferred` existing as a factory
with live users; neither validator message being asserted anywhere in the test tree, so item 3's
testedness split is right on both halves and the surviving FK-target `@nodeId` block can change
kind without breaking a pin; `PathFragments.correlationWhere`'s `OnParentJoin` inner switch and
`emitBackwardBridging`'s signature, both as item 5 needs them; `declareReachAliases`'s
`IdentityHashMap` and `reachExists`'s two unconditional `.pairs()` reads; the `FkHop` blast radius
(six main-source files); `surface2_conditionJoinPath_isRejected` asserting "condition-join" and
"foreign key" on an intermediate-hop path with an FK terminal; the five `surface1` cases; and
`ColumnReferenceFieldValidationTest.CONDITION_METHOD` expecting no errors on a hand-built
condition-path carrier.

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
