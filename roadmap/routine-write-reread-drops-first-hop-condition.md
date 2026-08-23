---
id: R816
title: "Refuse a routine write's first-hop condition instead of deferring it"
status: Spec
bucket: generator
priority: 3
theme: routine
depends-on: []
created: 2026-08-23
last-updated: 2026-08-23
---

# Refuse a routine write's first-hop condition instead of deferring it

An author who puts a `condition:` on the first `@reference` path element of a `@routine`-writing
mutation field gets a build error today. What they do not get is an error they can act on. The
message offers them two shapes at once ("joins by condition or carries a filter"), explains itself
in generator vocabulary ("no derivable post-commit re-read anchor"), names no fix, and closes with
"does not emit yet", which promises a release that will accept what they wrote. The user manual
repeats that promise in its *Deferred write shapes* list, and the store's own seat verdict files the
refusal under "shapes the generator owes an emitter".

The promise is wrong. There is no emitter owed here: the predicate's departure side is the
routine's own result, which cannot appear in the statement that runs after the commit, and the
author has to move the predicate or drop it. When this item lands, they are told exactly that, in
the shape they wrote, with the two places the predicate can go instead, and no surface anywhere
implies a future release will take it.

## What the establishing read found

The Backlog body asked three questions and answered none of them. All three are answerable by
reading the tree, and the answers move the item.

**Can the schema be written?** Yes. A first path element `{table: "rental", condition: {...}}`
resolves: `BuildContext.parsePathElement`'s `table:` branch resolves the `condition:` to a
`JoinConditionRef`, sees the departure node is a table-valued function, and hands the filter to
`synthesizeNameMatchedJoin`, which builds an `On.ColumnPairs` hop carrying it. So hop 0 is a
key-paired hop with a non-null `filter()`, which is precisely the shape the Backlog body suspected
nothing looked at.

**Does anything reject it?** Yes, and it is the verdict the body assumed was blind to it.
`FieldBuilder.classifyMutationRoutineChain`'s re-read-anchor verdict tests
`!(hop0.on() instanceof On.ColumnPairs) || hop0.filter() != null`, so the filtered arm and the
condition-joined arm both land an `UnclassifiedField`. `GraphitronSchemaValidator`'s
`validateUnclassifiedField` turns that into a `ValidationError` through `ValidationError.forField`,
which prefixes `Field '<Type>.<field>': ` and carries the field's `SourceLocation`, and
`GraphQLRewriteGenerator` throws `ValidationFailedException` on a non-empty error list. The
build fails, at the coordinate, before anything is emitted. Nothing is silently dropped.

**Is the fix a located rejection?** The location and the coordinate are already there. What is
missing is everything else about the diagnostic, and one taxonomy claim behind it.

So the item is no longer an investigation. It is a diagnostic-quality and kind-correctness fix
whose scope the read has pinned exactly.

## Why the shape is refused permanently, not deferred

`RejectionKind`'s own rule states the line: `DEFERRED` is for a shape that is "legitimate; support
is absent, not structurally impossible". The general mechanism that makes a hop-0 filter emittable
is to join the departure table into the query as an alias, so the filter method has something to
receive as its first argument. An ordinary single-table child field does exactly that:
`ParentCorrelation.OnParentJoin` joins the parent's own aliased table in and keys the batch by the
parent's primary key, so a hop-0 filter there is supported, not refused.

That mechanism is unavailable to a routine write, and the reason is the family's defining rule
rather than a missing feature: the routine appears in no statement after the one that ran it,
because re-invoking it would re-execute the write. The departure table cannot be joined into the
re-read at all, so the filter method's first argument has nothing to bind against, at any level of
generator effort. `RoutineWriteCommand.RereadAnchor`'s javadoc already states this, and states it
as structural.

The tree already refuses the same shape, for the same reason, at the two other sites where the
departure side is not a joined alias, and both use `Rejection.structural`:

* `FieldBuilder.classifyParticipantRoute`'s hop-0 filter reject on a multi-table interface or union
  child, where the parent side correlates by value. Its message is the model to copy: it names what
  the author wrote, says why it cannot bind, says it would otherwise be silently dropped, and gives
  the two fixes. `docs/manual/reference/directives/referenceFor.adoc` documents the rule.
* `BuildContext.buildParentCorrelation` called with a null parent table (the class-backed-parent
  carrier route), which returns its `AuthorError` arm and is wrapped in `Rejection.structural` at
  the `@sourceRow` call site in `FieldBuilder`.

One rule, three sites, and the routine-write site is the only one filed as a deferral. Matching the
other two is the whole design decision, and it extends a shape already in the tree rather than
inventing a taxonomy.

A caveat worth stating so a reviewer can disagree with it deliberately. A filter *could* be
evaluated inside the transaction, in the capture statement, by joining the arrival table there and
filtering the captured keys before the commit. That is expressible. It is also a different feature
wearing the same spelling: everywhere else `condition:` filters the rows of the enclosing query,
and at this one position it would filter the keys of a pre-commit statement instead. Admitting the
spelling later would mean it silently means something else here than it does everywhere else, so
the deferral promise should not be kept open for it. If a reviewer wants that feature, it is a new
Backlog item with its own spelling, not this one's emission.

## Implementation

**`FieldBuilder.classifyMutationRoutineChain`.** Split the one conflated verdict into the two
shapes an author can actually write, each `Rejection.structural`, keeping the check itself where it
is (immediately after the Connection verdict, reading `walk.steps().get(0)`). Both messages state
the mechanism in the author's own vocabulary and give the fixes; neither repeats the coordinate,
which `ValidationError.forField` prefixes.

For a first element that joins by `condition:` alone (`On.Predicate`): say the element joins by
`condition:`, that the method's first argument would be the routine's own result, that the result
cannot appear in the query that runs after the commit because re-invoking the routine would run the
write a second time, and that a first element must name a key or a table so the write has key
columns to capture and re-read by. Steer to moving the predicate onto a later element.

For a first element carrying a `condition:` alongside `key:` or `table:` (`On.ColumnPairs` with a
non-null `filter()`): say the element carries a `condition:` filter, give the same reason, and add
what it costs, which is that the filter would be dropped and the field would return rows the
predicate should exclude. Steer to dropping the `condition:` or moving it onto a later element,
where both of the method's tables are joined into the re-read.

Keep the verdict total over `On`. The single condition it replaces is total by construction
(`!(hop0.on() instanceof On.ColumnPairs)` catches everything that is not column pairs), and two
hand-written arms are not, so write it as a switch over `hop0.on()` and give `On.Lateral` an arm of
its own: a first element resolving to a second routine call, which the re-read cannot depart from
for the same reason. That arm fires for nobody today, the one-routine-per-chain rule refusing a
second `@routine` earlier and `walkRoutineChain` adding a lateral hop only where a running source
exists, so it is there to keep every reachable shape on a located rejection rather than on the
leaf's `IllegalArgumentException` if either of those rules moves.

Per the javadoc conventions, neither message nor either comment cites a roadmap item; the prose
names the live symbols and states the fact.

**`MutationField.MutationRoutineWriteField`'s compact constructor.** This is where the guard goes,
and the seat is a decision rather than a detail, so here is the argument. The hazard is real and
worth guarding: `RoutineWriteCommands.anchorOf` reads hop 0's `targetTable()`, `alias()` and pairs
and never looks at `filter()`, so a regression in the classifier verdict would emit an unfiltered
re-read and return rows the author's predicate should have excluded, silently. But the leaf is
the producer-side pin that the classifier's guarantee is already restated at: it pins `hops`
non-empty and hop 0 joining by `On.ColumnPairs`, and its own comment says the emitter narrows
"on this pin's authority". Add the third pin there, a non-null `filter()` on hop 0, so the leaf's
account of itself becomes complete and a filtered hop-0 leaf stops being constructible at all.
`docs/architecture/principles/development-principles.adoc`'s acceptance rule anchors this class of
contract on narrowing at the producer, which points here.

Nothing is added at `anchorOf`, and that is the point of the seat choice rather than an omission.
Its javadoc disclaims being an assertion site ("the translation of that guarantee into the shape the
row declares, not a second assertion of it: what the check below adds is the Java narrowing the
wider carrier's type cannot express"), and a `filter()` throw narrows nothing, so seating one there
would falsify that paragraph to buy a weaker guarantee than the leaf gives. The producer's own
`joinBasisOf` refuses a lateral hop by name, and that is the same category rather than a
counter-example: it is a decode with no arm to put a lateral in, which is the narrowing that javadoc
endorses. Two prose surfaces move with the pin instead:

* `anchorOf`'s javadoc says the leaf guarantees "both halves"; with the third pin it guarantees
  three, and the added clause names the filter one.
* `GraphitronSchemaValidator`'s `MutationRoutineWriteField` switch-arm comment enumerates the leaf's
  pins ("hops non-empty, terminus rule, ColumnPairs hop 0 via the classifier's re-read-anchor
  verdict") and needs the filter pin added, on the validator-mirrors-classifier rule that comment
  exists to state.

`RoutineWriteCommand.RereadAnchor`'s javadoc already calls the filter's absence structural rather
than an omission. It needs no edit; what changes is that the claim becomes enforced upstream instead
of asserted, and the pin's own message should read as the enforcement of it.

**`intent_mutation_routine_seat`'s verdict comment** (`graphitron-model/src/main/resources/no/sikt/graphitron/model/graphitron-model.sql`).
Its closing sentence partitions the fourteen verdicts into the ones an author fixes and the ones the
generator owes an emitter, and it lists `UNANCHORED_FIRST_HOP` among the latter. Move it to the
author-fixable list, so the store's own account and the rejection kind agree. The verdict name and
the view's `CASE` logic do not change: the view keys off the first chain node's step carrying a
`class_name`, which covers both authored shapes and is correct as it stands.

**`docs/manual/reference/directives/routine.adoc`.** Delete the first-hop sentence from the
*Deferred write shapes* section and state the rule as a constraint instead, in the `== Constraints`
list where the other hard "no"s live, with the two fixes. The other three deferrals in that section
(procedures, scalar and void routines, the hop-less Mutation `@routine`) are genuinely awaiting an
emitter and stay.

## Tests

**`GraphitronSchemaBuilderTest`.** The existing
`mutationRoutineChainWithConditionJoinedHopZeroDefers` pins only the condition-joined arm and
asserts `Rejection.Deferred`. Rename it to say it is refused rather than deferred, flip the
assertion to `Rejection.AuthorError.Structural`, and anchor it on a fragment of the new prose.
Add its sibling for the arm this item exists for: a first element `{table: "rental", condition:
{...}}` out of `rent_film`, asserting the structural arm and a fragment naming the filter. That
sibling is the fixture the Backlog body said this item should own; today the shape is covered
nowhere at the classifier tier, only through the derived view.

**`MutationRoutineSeatTest.anAuthoredConditionOnTheFirstHopLeavesTheReReadUnanchored`** already
uses exactly this SDL and stays green untouched: the view's verdict name and logic do not change.
Worth naming here so a reviewer sees the two tiers are deliberately asserting different things,
the classifier's rejection and the store's verdict.

**A new unit-tier invariant test for the leaf's pin**, in the `model/` package's established
`*InvariantTest` idiom. The siblings to copy are the direct-construction ones,
`ColumnBackedFieldInvariantTest` and `SingleRecordIdFieldKeyShapeInvariantTest`: `@UnitTier`, a leaf
built by hand, `assertThatThrownBy` on the compact constructor. (Not
`ParentCorrelationFirstHopInvariantTest`, whose name is close but whose shape is not: it is
pipeline-tier and classifies SDL.) Construct a `MutationRoutineWriteField` whose hop 0 is an
`On.ColumnPairs` hop carrying a filter and assert the throw, in the one-liner
`assertThatThrownBy(() -> new ...)` shape. Worth knowing before writing it: none of the leaf's three
pins is asserted anywhere today, so this adds the first, and covering the two it joins in the same
class is cheap and in scope.

**`RejectionKindProjectionTest`** needs no change (the projection is per-arm, not per-site), and
neither does the diagnostics glossary, whose `deferred` entry describes the kind and enumerates no
shapes.

The verification build is the full `mvn install -Plocal-db`: the SQL comment change touches the
model module every downstream module reads, and the `.adoc` change renders in the docs module.

## Out of scope

* **A capture-time filter emission.** Named above and deliberately not this item's; a reviewer who
  wants it should ask for a separate Backlog item so the spelling question gets its own decision.
* **`validateWhereFilterParamTables` on the routine-result hop.** The `table:` branch of
  `parsePathElement` returns straight after `synthesizeNameMatchedJoin` without running the
  condition method's parameter-table check, which the two foreign-key branches beside it do run. It
  is unobservable once this shape is refused outright, so noting it here is enough; if it is worth
  closing it is worth closing for its own reasons, on the read side.
* **The stale promise about "the four `deferred` emit-block reasons"**, a section neither page still
  has, in `diagnostics-glossary.adoc`'s opening paragraph and again in
  `docs/manual/reference/index.adoc`. Unrelated drift, not this item's, and both spellings belong to
  whichever item closes it.
* **`R682`'s disclosed silence** on the bare-`{condition:}` first hop, which reads as
  `CHAIN_UNRESOLVED` on the seat view because such an element resolves to no hop row at all. That
  item owns the view's verdict logic; this one changes only the comment's author-fixable partition,
  which is true of the verdict under either reading. The comment is one long line in a file that
  item is actively editing, so sync trunk immediately before making that edit.

## Retired vocabulary

Nothing symbol-level is retired. What is retired is the claim that this shape awaits an emitter, and
it survives as prose in five places rather than as code, so the sweep needs a query that reaches all
five. Two of them carry no phrase from the author-facing surfaces and would be missed by a sweep
built from those alone:

* `FieldBuilder.classifyMutationRoutineChain`'s own javadoc, whose "One write-only verdict on top"
  paragraph says a condition-joined or filtered hop 0 "lands a typed `Deferred` rather than reaching
  the leaf";
* `MutationField.MutationRoutineWriteField`'s javadoc ("this leaf adds two pins of its own ... the
  classifier's re-read-anchor verdict routes every other shape to a typed `Deferred`") and the
  comment above its hop-0 pin, which repeats it. Both are edited by this item's own Implementation
  section, the leaf being where the new pin lands, so the sweep is confirming rather than
  discovering; naming them here is what makes that checkable at the gate.

Alongside the three that the author-facing phrasing does reach:

* the deferral framing of this shape: "does not emit yet" and "is likewise deferred" applied to a
  routine chain's first hop, in the classifier message and in `routine.adoc`;
* `UNANCHORED_FIRST_HOP` appearing in a list of shapes the generator owes an emitter;
* `GraphitronSchemaValidator`'s switch-arm comment enumerating the leaf's pins without the filter
  one.

The sweep query that reaches all five is the verdict's own name rather than the deferral prose:
grep `re-read-anchor verdict` and `re-read anchor` across main and test sources plus the `.adoc`
tree, and read every hit for whether it still claims an emitter is owed. Grepping "Deferred" near
"routine" also works and is noisier, the sibling deferrals in that family (procedures, scalar and
void routines, the hop-less Mutation `@routine`, the multi-routine chain) being genuine and staying.

## Reviewer findings

### Round 1 (Spec → Ready gate, session_01UjdWPdE2PhrX1russQuxoU, 2026-08-23)

Independent reviewer session, status stays `Spec`. Two findings, one per gate question.

Question 1 is answered as it stands, and the establishing read holds up: every symbol, code site,
test and message fragment the body names exists as named, and the three claims the read turns on
were re-derived from the tree rather than taken on trust. What changes for a consumer is plain
without the phase list: an author who writes a `condition:` on the first `@reference` element of a
`@routine`-writing mutation still gets a build failure at their coordinate, but the message now
names the shape they actually wrote, says the predicate's departure side is the routine's own
result and so can never appear in the post-commit query, names the two places the predicate can go
instead, and stops promising a release that will accept it; the manual moves the rule out of
*Deferred write shapes* into `== Constraints`, and the store's own verdict partition stops filing
it as a shape the generator owes.

1. **Question 2. The new plan-time pin is seated at `RoutineWriteCommands.anchorOf`, whose own
   javadoc says that site is not an assertion site, and the seat the tree already uses for the
   sibling half of the same invariant goes unmentioned.** The Implementation section calls the
   filter throw "the missing symmetric half of the invariant that already sits there" and asks for
   it "in the same shape and voice as the sibling check". The sibling check's voice is the problem:
   `anchorOf`'s javadoc reads "The leaf guarantees both halves in its own constructor (at least one
   hop, and hop 0 joining by column pairs), so this is the translation of that guarantee into the
   shape the row declares, *not a second assertion of it*: what the check below adds is the Java
   narrowing the wider carrier's type cannot express." Both halves are pinned in
   `MutationField.MutationRoutineWriteField`'s compact constructor, whose own comment says the
   emitter narrows to `On.ColumnPairs` "on this pin's authority", and that leaf accepts a filtered
   `On.ColumnPairs` hop 0 today. So the plan as written leaves the producer's authority incomplete
   (a filtered leaf stays constructible and legal by the leaf's own account), adds a pure assertion
   at a site that exists for type narrowing, and falsifies a javadoc paragraph the plan does not
   mention rewriting. `docs/architecture/principles/development-principles.adoc`, "Acceptances:
   classifier guarantees shape emitter assumptions", anchors this class of contract on
   "type-system narrowing at the producer", which points at the leaf.

   The fork is visible in the Tests section too, and is what makes it a plan question rather than a
   detail. At the leaf's compact constructor the test is a one-liner in exactly the
   `anAnchorCapturingNoKeyIsRefused` shape the plan wants to copy
   (`assertThatThrownBy(() -> new MutationRoutineWriteField(...))`). At `anchorOf`, which is
   `private static` and reachable only by driving `EmitPlan.produceWithoutStore` over a
   hand-assembled model whose classifier would never produce that leaf, it is not, which is why the
   plan has to pre-authorize a fallback ("assert the throw at the narrowest reachable seam"). The
   fallback reads as a hedge against an unknown; it is actually the predictable consequence of the
   seat choice.

   What would satisfy: pick the seat deliberately in the body and say why. The leaf's compact
   constructor beside its two siblings, `anchorOf`, or both are all defensible, and the argument
   for `anchorOf` (it is where `filter()` is dropped, since it reads `targetTable()`, `alias()` and
   the pairs and nothing else) is real and worth stating if that is the choice. Where the chosen
   seat contradicts prose already in the tree, say what happens to that prose. Then let the test
   paragraph follow from the choice rather than hedge against it.

   *Author response.* Agreed, and the seat moves to the leaf's compact constructor. The
   Implementation section's `anchorOf` paragraph is replaced by one that names the leaf, argues the
   choice (the leaf is the pin's existing authority and the acceptance rule points at narrowing at
   the producer; a `filter()` throw narrows nothing, so seating one at `anchorOf` would falsify that
   javadoc to buy a weaker guarantee), and states explicitly that nothing is added at `anchorOf`.
   The hazard the old paragraph was reaching for is kept in the argument rather than dropped, since
   it is why the pin exists at all. Two prose surfaces are now named as moving with the pin:
   `anchorOf`'s "both halves" clause, and `GraphitronSchemaValidator`'s switch-arm comment
   enumerating the leaf's pins, which the finding's own reading of the validator-mirrors-classifier
   rule implies. The Tests paragraph follows the choice: a model-tier `*InvariantTest` in the
   `ParentCorrelationFirstHopInvariantTest` idiom, no fallback, plus the fact that none of the
   leaf's three pins is asserted today so this adds the first.

2. **Question 1. The retirement sweep list, which is the Done gate's only grep query, misses the two
   javadoc paragraphs that carry the retired claim.** The item's stated outcome is that "no surface
   anywhere implies a future release will take it", and two contributor-facing surfaces would say
   otherwise the moment this lands: `FieldBuilder.classifyMutationRoutineChain`'s own javadoc ("so a
   condition-joined or filtered hop 0 ... has no derivable re-read anchor; it lands a typed
   `Deferred` rather than reaching the leaf"), and `MutationField.MutationRoutineWriteField`'s
   javadoc plus the comment above its hop-0 pin, both of which say the re-read-anchor verdict
   "routes every other shape to a typed `Deferred`". Neither contains "does not emit yet" or "is
   likewise deferred", so the sweep as specified would not catch them, and the second one lives in a
   file the Implementation section does not touch. What would satisfy: name both in the sweep list
   (or in Implementation), so the Done-gate grep has a query that reaches them.

   *Author response.* Both are named in the rewritten `## Retired vocabulary`, which now lists five
   surfaces and separates the two the author-facing phrasing cannot reach from the three it can. Two
   things the finding surfaced beyond what it asked for are folded in: the sweep now leads with a
   query that actually finds all five (`re-read-anchor verdict` / `re-read anchor`, the verdict's own
   name, rather than the deferral prose), and the `MutationField` file is no longer untouched by
   Implementation, since finding 1 moved the pin into it, so the sweep confirms an edit instead of
   discovering an omission. `GraphitronSchemaValidator`'s pin enumeration is listed too.

Non-blocking, noted only, neither bearing on the two questions:

* The current verdict's `!(hop0.on() instanceof On.ColumnPairs)` is total over `On`'s three
  variants; a literal split "into the two shapes an author can actually write" is not, and an
  `On.Lateral` hop 0 would fall through to the leaf's `IllegalArgumentException` instead of a
  located rejection. That arm is unreachable here (`walkRoutineChain` adds a lateral hop only when
  `runningSource != null`, and the root-head rule puts `@routine` first on a Mutation root), so
  this is robustness rather than a hole; a sealed switch or a retained fallthrough keeps it total.
* `docs/manual/reference/index.adoc` carries the same "four deferred emit-block reasons" drift the
  out-of-scope section attributes to `diagnostics-glossary.adoc`'s opener. Worth folding into
  whatever item eventually closes that drift.

*Author response to both notes.* Taken. The Implementation section's classifier paragraph now
requires the verdict to stay total over `On`, as a switch with an `On.Lateral` arm of its own, and
says why that arm exists given it fires for nobody today. The out-of-scope bullet now names both
pages carrying the glossary drift rather than one.
