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

Per the javadoc conventions, neither message nor either comment cites a roadmap item; the prose
names the live symbols and states the fact.

**`RoutineWriteCommands.anchorOf`.** Add the missing symmetric half of the invariant that already
sits there. The method narrows hop 0 and throws when `on()` is not `On.ColumnPairs`, restating the
classifier's guarantee as a plan-time invariant; it reads `targetTable()`, `alias()` and the pairs
and never looks at `filter()`. So the one thing that would make the re-read silently unfiltered, a
regression in the classifier verdict, is the one thing the plan does not refuse. Throw on a non-null
`filter()` too, in the same shape and voice as the sibling check, naming the coordinate. This is the
item's guard against the hazard the Backlog body was actually worried about: with it, dropping the
verdict's filter clause fails loudly at plan time instead of emitting a re-read that returns rows
the author's predicate should have excluded.

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

**`RoutineWriteRelationTest`.** Add the plan-time refusal beside
`anAnchorCapturingNoKeyIsRefused`, in the same construction-check shape: build the anchor's hop with
a filter attached and assert `anchorOf` refuses it. If reaching `anchorOf` with a filtered hop is
awkward from that test's fixture (it produces the relation from a classified model, and a filtered
hop 0 never classifies), assert the throw at the narrowest reachable seam rather than weakening the
check, and say in the test's javadoc why the shape has to be constructed rather than classified.

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
* **The stale promise in `diagnostics-glossary.adoc`'s opening paragraph** ("the four `deferred`
  emit-block reasons", a section the page no longer has). Unrelated drift, not this item's.
* **`R682`'s disclosed silence** on the bare-`{condition:}` first hop, which reads as
  `CHAIN_UNRESOLVED` on the seat view because such an element resolves to no hop row at all. That
  item owns the view's verdict logic; this one changes only the comment's author-fixable partition,
  which is true of the verdict under either reading. The comment is one long line in a file that
  item is actively editing, so sync trunk immediately before making that edit.

## Retired vocabulary

Nothing symbol-level is retired. Two prose claims are, and they are what the Done-gate sweep should
grep for, because both survive as text rather than as code:

* the deferral framing of this shape: "does not emit yet" and "is likewise deferred" applied to a
  routine chain's first hop, in the classifier message and in `routine.adoc`;
* `UNANCHORED_FIRST_HOP` appearing in a list of shapes the generator owes an emitter.
