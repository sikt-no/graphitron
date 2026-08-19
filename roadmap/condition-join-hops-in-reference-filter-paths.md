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
`GraphitronSchemaValidator.validateInputColumnField`. The mechanical cause sits below both:
`ServiceCatalog.terminalTableForReference` returns empty on the first non-`ColumnPairs` hop, so the
terminal column never resolves and the carrier cannot classify.

## Why this is worth reopening

The restriction reads as a cardinality rule but is not one. The justification a reader expects, that
a foreign key targets a unique key so the correlated `EXISTS` matches at most one row where an
authored predicate carries no such guarantee, is already settled the other way in this repo: the
changelog entry for the FK-target `@nodeId` filter work records that `EXISTS` is *"the semantically
right shape rather than a convenient one: no row multiplication when the path is non-unique, and a
NULL FK column fails the correlation instead of duplicating or dropping rows."* The `{key:}` form
also does not constrain FK direction, so a reverse-FK hop in a filter path already produces
`EXISTS`-over-many today. Foreign-key-ness is not buying uniqueness in filter position.

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

This is the last thing blocking a direct port of a v9 filter shape. Under v9 a `{condition:}` hop in
filter position worked, so schemas expressed a filter reaching a joined table's columns without the
join being a declared foreign key. Where the underlying relationship *is* conventionally a foreign
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
rather than teaching the FK-shaped filter types new arms. Line numbers are orientation for the
implementer, not contracts; re-locate at pickup.

### 1. Walk condition hops in `ServiceCatalog.terminalTableForReference`

`JoinStep.Hop.targetTable()` resolves off `target` (a `TableExpr`), never off `on`, so the walk can
advance through an `On.Predicate` hop exactly as through `On.ColumnPairs`. Change the walker to
advance on both arms and keep resolving empty for `On.Lateral` (the filter emitter has no lateral
`EXISTS` shape; see non-goals). Correct the stale javadoc parenthetical, "a condition-only step's
target table is unknown at build time", which item 4's target resolution disproved. All three
callers (`FieldBuilder`'s scalar-arg `@reference` branch at ~1772, `BuildContext.classifyInputField`'s
plain-`@reference` branch at ~2720, and the output scalar-leaf branch at `FieldBuilder` ~7771)
consume only the terminal table or column, never FK pairs, so no FK-only variant of the walk needs
to survive.

### 2. Resolve a terminal condition hop where no return-type `@table` exists

Both filter sites pass `targetSqlTableName = null` into `parsePath` (`FieldBuilder` ~1762,
`BuildContext` ~2717), so `resolveConditionJoinTarget`'s terminal arm AuthorErrors on a terminal
`{condition:}` element today, with a message about the return type that reads as nonsense at a
filter site. Since a single-element path is both position 0 and terminal, this arm blocks the
primary use case outright. Change: when `isTerminal` and `terminalTargetSqlName == null`, fall
through to the existing intermediate-hop reflection arm (the condition method's second parameter
type, mapped through `JooqCatalog.findTableByClass`). Stated consequence, documented in the manual:
a terminal condition hop at a filter site requires the condition method's second parameter to be a
concrete generated jOOQ table class, where an output field's terminal hop tolerates `Table<?>`
because the return type's `@table` binding answers instead. The existing wildcard rejection message
already names the fix. `validateConditionParamTables` keeps firing unchanged; hop 0's origin table
is the filter's own start table, which is always resolved at both sites.

### 3. Retire the two directed rejections

Delete `FieldBuilder`'s classify guard (~1767) together with `referenceFilterConditionJoinRejection`
(~1904), and the plain-`@reference` mirror block in
`GraphitronSchemaValidator.validateInputColumnBackedReferenceField` (~1802). The sibling block in
the same method (~1780), requiring an FK path for `@condition` on an FK-target `@nodeId` field,
stays; that shape is a non-goal below. At implementation time, verify whether a filter-site path can
produce an `On.Lateral` hop at all: if it can, reject it with a directed structural message at the
same two sites (preserving the invariant that the validator rejects what the emitter cannot render);
if it cannot, the walker's empty bail is defense enough and no guard is needed.

### 4. Reach becomes `List<JoinStep.Hop>`; `FkHop` retires

The load-bearing constraint. Today `ConditionCommands.narrowPath` narrows the classified
`List<JoinStep>` to `List<FkHop>` at produce time (`FkHop.narrow` accepts only `On.ColumnPairs`),
and `ConditionGlueRenderer.reachExists` reads `.pairs()` unconditionally. Decision: retire `FkHop`.
`ColumnTerm.reach()` and `Predicate.Authored.reach()` change component type to `List<JoinStep.Hop>`
(`JoinStep` is sealed permitting only `Hop`, so this is not a widening to arbitrary steps), and the
renderer dispatches `hop.on()` per hop the way the projection sibling already does, with an
`On.Lateral` arm that throws, mirroring `PathFragments.correlationWhere`'s posture on shapes its
carrier cannot legally hold.

Alternative considered and rejected: a sealed reach-hop type (`Keyed(hop, pairs)` /
`Conditioned(hop, condition)`) preserving produce-time unrepresentability of lateral hops. It buys
one static exclusion at the cost of a second vocabulary parallel to `JoinStep.Hop` + `On` that every
shared emitter helper (item 5) would have to translate across, and the projection rail already
demonstrates the raw-seal dispatch living safely without it. The `FkTargetConditionFilter` arm of
`ConditionCommands` (the `@nodeId` + `@condition` reach) keeps its FK-only guarantee upstream at the
validator (item 3's surviving block), not through the type system; its reach simply never contains a
condition hop.

Coordination note: R485 (model-level `isFkHop`/`pairsOf` helpers) touches the same narrowing idioms.
No dependency either way, but whichever lands second re-counts the idiom sites.

### 5. One per-hop dispatch, shared with the projection emitter

`reachExists` needs two dispatch points: the backward bridging join for interior hops (today
`JoinFragments.emitBridgingJoin`, pairs-only) and the hop-0 correlation (today
`JoinFragments.emitCorrelationWhere`, pairs-only). The projection rail already owns both arms:
`PathFragments.emitBackwardBridging` emits `.join(prev).on(twoArgCall(cond, prevAlias, hopAlias))`
for a predicate hop, and `correlationWhere`'s `OnParentJoin` arm emits
`twoArgCall(cond, parentLocal, firstAlias)`. Hoist the per-hop `On` dispatch into one shared home
(likely `JoinFragments`: a backward-bridging-join helper and a hop-0-correlation helper taking the
raw `On`), and have both `reachExists` and the `PathFragments` arms delegate to it, rather than
growing a second copy of the seal switch. If implementation finds the two emitters' shapes diverge
more than expected (alias schemes, format targets), the fallback is a shared arm for the
`On.Predicate` case only; what is not acceptable is a full second switch that a future `On` arm can
miss. The condition method's call convention is unchanged: `(source, target)` where source is the
previous hop's table (the filtered table itself at hop 0) and target is the hop's own table, the
same convention `appendHopFilters` and the projection arms already emit.

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
execution coverage rather than adding a new guard to preserve an accidental rejection. If execution
coverage surfaces a real emitter gap here, that gap gets its own item and this arm gets a directed
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

### 10. Tests

Per the testing rubric, pipeline beats unit beats execution where the assertion fits the tier:

* **Pipeline** (`ReferenceFilterRemoteColumnPipelineTest`): `surface2_conditionJoinPath_isRejected`
  flips to acceptance cases on both surfaces (scalar argument, input field), asserting the
  classified carrier and the generated `TypeSpec` shape (the `EXISTS` embedding the two-arg method
  call). Add mixed-path cases in both orders (condition-then-key, key-then-condition) and truth-table
  entries in `GraphitronSchemaBuilderTest` beside the existing output-side condition-hop rows.
* **Unit**: a validator case proving `validateInputColumnBackedReferenceField` accepts a
  condition-hop filter carrier (and, if item 3's verification finds lateral representable, the
  directed lateral rejection).
* **Execution** (`GraphQLQueryTest`, new fixtures beside `ReferencePathConditionFixtures` with
  concrete parameter types per item 2): a filter through a single terminal condition hop; a filter
  through an FK-then-condition bridge; assertions on `EXISTS` grain (no row multiplication when the
  path matches many rows, no dropped parents on non-match). One case each for the scalar-leaf unlock
  of item 7. Compilation-tier coverage rides the fixtures for free.

## Non-goals

* `@condition` on an FK-target `@nodeId` field keeps requiring an FK path (the surviving validator
  block); that shape binds decoded id columns and is a different mechanism.
* `On.Lateral` hops in filter paths stay unsupported (directed rejection or walker bail per item 3).
* `@lookupKey` and the write rails stay closed to every `Remote` binding; no wording changes.
* R676 (per-participant `@nodeId` filter paths) is untouched; it needs a path *surface*, not this
  item's path *semantics*.

## Retired vocabulary

* `FkHop` (the type; item 4 retires it).
* `referenceFilterConditionJoinRejection` and its message text, "traverses a condition-join
  (non-foreign-key) hop, which is not yet supported", in both its `FieldBuilder` and validator
  copies.
* The walker javadoc claim "a condition-only step's target table is unknown at build time".
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
