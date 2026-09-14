---
id: R950
title: "A @routine spends the leaves its argMapping binds, not the arguments they sit in"
status: Spec
bucket: bug
priority: 3
theme: routine
depends-on: []
created: 2026-09-14
last-updated: 2026-09-14
---

# A @routine spends the leaves its argMapping binds, not the arguments they sit in

## Goal

An input field inside a `@routine`-backed field's argument does exactly one of three things, and the
author can tell which from the schema: it feeds a routine parameter, it filters the routine's result,
or it fails the build. Today a fourth outcome exists and is the default: a `@routine` *spends* (binds
to a routine IN parameter, so the read surface never sees it) the whole of any argument its
`argMapping` opens, at argument grain. Once one dotted path into `filter` feeds the call, every other
field of `filter` is invisible to the WHERE clause, whether it carries `@field`, `@nodeId`,
`@condition`, or nothing. No verdict says so. The build is green, the emitted SDL advertises the
filter, and a client that supplies it gets the unfiltered result: a silent over-return, the failure
class the project otherwise refuses to ship. When this lands, spending moves to leaf grain: only the
leaves `argMapping` binds are spent, and every other leaf of the argument is an ordinary input leaf
with the ordinary verdicts against the routine's result table. The author's single-input-object
schema below works, and no new diagnostic is needed for the leaf that binds to nothing, because the
rejection that already exists for every other filter in the tree now reaches it. Surfaced in the
follow-up comment on [issue 547](https://github.com/sikt-no/graphitron/issues/547), where it was the
third of three declarative routes to an optional filter on a routine-backed field, the other two
being the NPE the sibling item on omitted node ids in key projections fixes.

```graphql
input ActorFilmFilter {
  actorId:    ID!    @nodeId(typeName: "Actor")   # spent: feeds pActorId
  ratingCode: String @field(name: "rating")       # a filter on the result today? No: silently dropped
}

type Query {
  actorFilms(filter: ActorFilmFilter!): [ActorFilm!]
    @routine(name: "films_for_actor", argMapping: "pActorId: filter.actorId.actor_id, pMinLength: 60")
    @defaultOrder(fields: [{name: "film_id"}])
}
```

After this item `ratingCode` is `WHERE rating = ?` over the function result, exactly as it would be
on a table-backed field. The contrast that makes the point is a fourth leaf, `foo: String`, naming no
result column: on a table-backed field that already fails the build with "input field 'foo' has no
column binding and no @condition", and after this item it fails the same way inside a routine
argument, where today it is silently dead.

## Decisions

Settled at filing, so the plan below does not reopen them:

* **Leaf grain, not a diagnostic.** The alternative was to keep argument-grain spending and add a
  build error for a filter-bearing leaf inside a spent argument. That stands a second rejection beside
  the existing no-binding one for the same fact, forbids the single-input-object schema outright, and
  leaves a bare leaf (no directive, name matching a result column) dead. Moving the grain removes the
  exemption instead of decorating it: the rule "every input leaf has exactly one consumer" already
  governs every filter in the tree, and `argMapping` becomes one kind of consumer beside `@field`,
  `@nodeId` and `@condition`. `@field` is not required for a leaf to bind: a leaf whose name matches a
  result column case-insensitively binds by name, as it does everywhere else, and the manual's
  existing warning about name-match binding the wrong column applies unchanged.
* **Mutation routine inputs get the rule too, as a rejection.** A Mutation `@routine` field resolves
  no filter surface: the function is the write, its parameter list is the only thing that consumes
  input, and the post-commit re-read is keyed, not filtered. A leaf `argMapping` does not bind there
  reaches nothing, so the only honest verdict is the one a DML `@mutation` input already gets from
  `MutationInputResolver`: a build error naming the input type and the leaf. A deliberately unread
  input leaf (the Relay `clientMutationId` pattern, an idempotency key) has no spelling on either
  mutation kind today and nothing echoes such a value back into a payload; this item states that gap
  in the manual rather than designing the escape hatch.
* **A spent leaf that also carries a filter directive is a directive conflict.** `@field` or
  `@condition` on a leaf `argMapping` binds asks for a WHERE clause the spent leaf will never get; it
  is the same silent class this item removes, one level down, and it fails the build naming the leaf
  and the routine parameter that spends it. `@nodeId` on a spent leaf is exempt: the key projection
  consumes it, so there it is a decode instruction and not a filter directive.
* **Hard switch, stated in the changelog and the manual.** A consumer whose schema carries a leftover
  leaf inside a routine argument sees one of two changes at upgrade: the leaf becomes a filter if it
  names a result column, or the build fails if it names nothing or sits on a mutation routine input.
  Both are the honest outcome of input the schema already advertised, the failing case fails at build
  time with a message naming the leaf and the two fixes (bind it, or remove it), and graphitron has no
  warning tier on this surface to stage the change through.

## Implementation

The seam is `FieldBuilder.classifyArguments`, which takes `argsBoundElsewhere`, a `Set<String>` of
argument *names*, and skips an argument outright when its name is in the set. The set is computed by
`FieldBuilder.routineBoundArgNames` from the routine's `RoutineRef.ArgBinding` rows by taking each
`ParamSource.Arg` binding's `path().headName()`. That head-name projection is the whole of
argument-grain spending. The change is in where the fact comes from, what carries it, and where it
is applied; the shared input classifier is not touched.

1. **The spent leaf is a fact the store already resolves.** `graphitron_argmapping_match`, at site
   `ROUTINE`, carries `bound_path` ("the whole written path where all of it resolved, and that path
   less its last name where one name is left over"), together with `bound_kind` (`ARGUMENT` or
   `INPUT_FIELD`), `bound_type_name` and `bound_field_name`: the spent leaf's own coordinate, with the
   projected key-column strip already applied by the store's rule. The classifier reads
   `GatheredFacts` rather than the store, so the walk-side set is derived from the same
   `ArgBinding` rows as today and is stated as the transitional twin of `bound_path`, pinned by a
   shadow-agreement anchor in `rewrite/derive` beside the walk, the way `InputOccurrenceShadowTest`
   pins the occurrence paths. No third rule for "which leaf does this path open" appears.
2. **Carry coordinates, not dotted strings.** The set is not a `Set<String>` of paths. It carries the
   spent leaves as typed coordinates, the `(containerTypeName, fieldName)` steps `ClassifyContext.UseSite`
   already descends by, compared component-wise; that record's javadoc states why a serialized path
   is the wrong key ("two spellings of one value agree until one changes, and here a disagreement
   reads as a dropped instruction"). A flat argument bound directly (`pEnv: env`) is a one-step
   coordinate and keeps being skipped whole in `classifyArguments`, which is leaf grain trivially.
3. **Spend at the use-site projection, not in the classifier.** Spent-ness is a fact about this
   consuming field's `@routine`, not about the input field, so the classified `InputField` tree for an
   input type must not vary by consumer, and the one classifier every DML, `@table` and plain-input
   path shares stays untouched. Every leaf is classified exactly as today. The spent leaves are
   dropped at `FieldBuilder.walkInputFieldConditions`, the seat that already carries `outerArgName`
   and `leafPath`, composes the occurrence path, and mints the use-keyed no-binding verdict through
   `mintCascadeVerdict`: one predicate there, and an unbound leaf that is not spent reaches the
   rejection that already lives on that seat. The result table the surviving leaves resolve against is
   what `intent_input_field_resolving_table` already answers for the use site, the chain terminus,
   whose `graphitron_field_table.target_basis` is `ROUTINE_RESULT` on a routine terminus.
4. **The conflict verdict.** Where a spent leaf carries `@field` or `@condition`, the same seat mints a
   located structural rejection naming the leaf, the directive and the routine parameter that spends
   it, in the family of the existing `@routine` + `@reference` directive conflict. A spent leaf
   carrying `@nodeId` is not a conflict; the projection is its consumer.
5. **The mutation arm is a new walk, stated as such.** `classifyMutationRoutineChain` never resolves
   an input type, and `InputFieldResolver` cannot be borrowed for it: it returns nothing when the
   resolving table is null, which on a routine write seat is the premise. So the accounting is one
   walk over the argument's input tree yielding leaf coordinates, with the verdict a parameter of the
   seat: on a Query field the surviving leaves are filters (step 3), on a Mutation field every
   surviving leaf is a located rejection, one per leaf, at the leaf. The wording is a shared typed
   rejection, not a copy of `MutationInputResolver`'s sentence: that sentence's remedy ("or carry an
   override condition") is false on a routine write seat, where
   `RoutineDirectiveResolver.writeSeatReadSurfaceDeferral` defers `@condition` outright. Ownership on
   the mutation seat is stated so three verdicts do not claim one leaf: a spent leaf carrying `@field`
   or `@condition` is the conflict (step 4); an unspent leaf is the unread rejection whatever directive
   it carries, that rejection being the one that says what the leaf failed to do.
6. **Validator and LSP.** No re-authored message. The mirror mechanism already in the tree is the
   located mint drained by `GraphitronSchemaValidator.drainBuildDiagnostics`, which is how
   `mintCascadeVerdict`'s verdicts reach the validator and the LSP at leaf grain today; the two new
   verdicts mint the same way. Nothing in `GraphitronSchemaValidator` restates the rule.
7. **The decode ledger sees the siblings.** Today a spent argument is skipped before
   `disposeRefusedNodeIdArgument` runs, so a `@nodeId` leaf beside the bound one never gets a
   walk-side ledger row; its disposition comes only from the store's projected installs
   (`NodeIdDecodeCoverage`). After this change the sibling leaves are classified, so they mint decode
   slots or refusals at coordinates the ledger previously saw only as not reached. That is the right
   direction and a behaviour change in a build-failing rule, so it is stated here and pinned by a
   pipeline case rather than left to be noticed.

## Tests

* **Pipeline** (`graphitron` pipeline tier, beside `RoutineMutationWritePipelineTest`): one case per
  verdict over the sakila routines. A leftover `@field` leaf inside a routine argument classifies as
  a column-bound filter and the emitted query carries its predicate; a leftover bare leaf whose name
  matches a result column binds by name; a leftover leaf naming nothing lands the existing no-binding
  rejection with the routine field as the use site; a bound leaf carrying `@field` lands the conflict
  rejection; a bound leaf carrying `@nodeId` does not; a Mutation routine input with an unbound leaf
  lands the DML-worded rejection. The existing routine read fixtures (`tilganger`, `Actor.films`,
  `recentFilmsForActorConnection`) keep their verdicts, which pins that a flat bound argument and a
  fully bound input object are unchanged. Two more cases pin the mechanism: the walk-side spent set
  agrees with `graphitron_argmapping_match.bound_path` over the corpus (the shadow anchor of step 1),
  and a `@nodeId` sibling of a bound leaf now appears in the decode ledger with a disposition (step 7).
* **Execution** (`graphitron-sakila-example`, beside `RoutineFieldExecutionTest`): one field over
  `films_for_actor` taking a single input object whose `actorId` feeds the routine and whose second
  leaf filters the result by a function result column; the case asserts the narrowed rows against the
  unfiltered call, so the predicate is shown to reach SQL rather than only to classify.
* **Validator reach** (pipeline tier, over the classified model): the two new rejections arrive
  through `drainBuildDiagnostics` located at the leaf, in the build and in the LSP, with no second
  spelling of the rule in `GraphitronSchemaValidator`.

## User-facing docs

The routine page's read-surface paragraph (`docs/manual/reference/directives/routine.adoc`, "The
read surface") currently ends: "The field's remaining arguments are unaffected: an argument bound to a
routine IN parameter is spent on the call and is never read as a filter." It is rewritten to leaf
grain: a leaf `argMapping` binds is spent on the call; every other input field of the same argument
filters the result like any input field on a table-backed field, binding by `@field` or by name; a
leaf that binds to nothing is the same build error it is everywhere; and a spent leaf carrying
`@field` or `@condition` is a directive conflict. The "Writes on Mutation" section gets the mutation
sentence: every leaf of an input the routine opens must feed a parameter, an unread leaf is a build
error as on a DML mutation, and a deliberately unread leaf has no spelling yet. The changelog entry
carries the upgrade note from the fourth decision.

## Retired vocabulary

* "An argument bound to a routine IN parameter is spent on the call": the argument-grain sentence, in
  the routine manual's read-surface paragraph, the `Query.tilgangerAdmin` fixture comment in the
  sakila example schema, and the `classifyRootRoutineChain` javadoc. Replaced by the leaf-grain
  sentence above.
* `routineBoundArgNames` and the `argsBoundElsewhere` parameter as sets of argument names; whatever
  carries the bound paths takes a name that says so.

## Other solutions we've considered

* **Argument-grain spending plus a diagnostic.** A build error for a filter-bearing leaf inside a
  spent argument, pointing at the two-argument spelling (`actorFilms(actorId: ID! @nodeId, filter:
  RatingFilter)`), which already works. Smaller, and it was this item's first shape. Rejected in the
  first decision: it stands a second rejection beside the existing one for the same fact and keeps the
  bare leaf dead.
* **Accounting only.** Leaf grain for the check but not for the behaviour: a leaf `argMapping` does
  not bind is a build error whatever it names. Simple, but it forbids the reporter's schema for no
  reason the read surface can give, and invents a rejection for a leaf the ordinary rules would have
  bound.
* **A transitional warning.** Rejected in the fourth decision: no warning tier exists on this surface,
  and a warning about a filter that silently does nothing is still a release of the silent behaviour.
* **Reads only, mutations unchanged.** Leaves an unread mutation input leaf silent while the DML
  mutation beside it rejects the same shape; the asymmetry has no reason behind it.

## Reviewer findings

### Round 1 (2026-09-14, Spec -> Ready, reviewer session 016uo6utLw18534uY1a6rVqD)

Verdict: revisions requested. One blocking finding on question two, which takes question one's
viability with it, plus one non-blocking note on the worked example. Question one is otherwise well
communicated: a consumer who today writes one input object holding both the routine's key and a
filter gets the filter silently dropped and an over-return; afterwards the key is spent, the filter
is a WHERE clause on the function result, and a leaf naming nothing fails the build. Every symbol,
relation and fixture the plan names exists as named; the verification narrative is in the review
commit's message.

**Finding 1 (question two, and with it question one's viability): the drop seat in step 3 sits
downstream of the gate that refuses the spent leaf, so the Goal's own schema still fails the
build.**

The read surface reaches an input tree through `FieldBuilder.classifyArgument`'s plain-input branch,
which calls `InputFieldResolver.resolve(typeName, rt, ...)` with `rt` the chain terminus
(`routineChainComponents` hands it `walk.tb().returnType().table()`). Any field that comes back
`InputFieldResolution.Unresolved` there folds the whole argument into `ArgumentRef.UnclassifiedArg`
at the `Resolution.Rejected` arm, before `walkInputFieldConditions` runs at all. So "classify every
leaf exactly as today, drop the spent ones at the walk" reaches only leaves whose miss is soft.

A column miss is soft: it lifts to `InputField.UnboundField` and the verdict is minted at the walk,
which is exactly what makes this item's bare-leaf and `@field` arms work. A `@nodeId` leaf is not
soft. `BuildContext.classifyInputFieldInternal` routes it to `NodeIdLeafResolver.resolve` against
that same `rt`, whose result must be `Resolved.SameTable` (the node type's table *is* the containing
table) or a `Resolved.FkTarget` reached by a foreign key walk from the containing table; every other
outcome is `Resolved.Rejected`, which the caller turns into `unresolved(field, name, ...)`.

On a routine terminus the containing table is the function result table, which carries no foreign-key
metadata. The tree states this positively:
`GraphitronSchemaBuilderTest.tableChildOfARoutineResultParentNeedsNoReference` pins that a child of a
`films_for_actor` parent keys by name-match, and its sibling
`tableChildOfARoutineResultParentWithNoNameMatchPointsAtTheConditionElement` asserts the refusal
there does not even reach "no foreign key" vocabulary.

Put together on the Goal's own schema: `ActorFilmFilter.actorId: ID! @nodeId(typeName: "Actor")` is
classified against `films_for_actor` (`film_id`, `title` per `init.sql`), `actor` is neither that
table nor FK-reachable from it, the leaf is `Rejected`, the `filter` argument lands
`UnclassifiedArg`, and the walk that was to drop that leaf never runs. The schema the Goal says
"works" fails the build, with a message about a missing route to `actor` rather than anything about
spending.

This is not a corner of the design. `pActorId: filter.actorId.actor_id` is the canonical projection
spelling (`ArgmappingProjectionRejectionPipelineTest` spells it `input.inventoryId.inventory_id`),
and the third decision blesses precisely this leaf: "`@nodeId` on a spent leaf is exempt: the key
projection consumes it". Under step 3 that exemption has no seat to be applied at.

Step 7 inherits the same gap from the other side. A `@nodeId` sibling of a bound leaf that does not
resolve against the result table is not merely "a refusal row in the decode ledger": `classifyInputField`
records the refusal *because* the field came back `Unresolved`, so the same event rejects the whole
argument. Step 7 reads as a ledger-only consequence ("that is the right direction"), and a pipeline
case pinned to the ledger row would pass without noticing the build failure standing beside it.

What would satisfy it: a settled statement in `## Implementation` of where spent-ness is applied
relative to the classification gate. Two shapes that would read as settled, and the item has to pick
one because they differ in what the implementer builds:

* Withhold the spent leaves before the gate, by threading the spent coordinates into the plain-input
  call site (`InputFieldResolver.resolve`) so a spent leaf is never classified against the result
  table at all, leaving `BuildContext.classifyInputField`'s decision tree untouched, which is what
  step 3's "the shared input classifier is not touched" actually protects. Step 3's other premise
  would then need restating: the plain-input tree is already computed per call site rather than once
  per input type, so it already varies by consumer in the sense that matters here.
* Or keep the drop at the walk and say what makes a spent `@nodeId` leaf resolve cleanly against a
  routine terminus in the first place.

Either way `## Tests` needs a case that is the Goal's own schema asserted green: a spent `@nodeId`
leaf beside a surviving filter leaf on a Query routine. The listed pipeline case "a bound leaf
carrying `@nodeId` does not [land the conflict rejection]" passes whenever the argument is rejected
for some other reason, so it does not pin this.

**Finding 2 (question one, not blocking on its own): the worked example does not work as narrated
even after the change.**

With no `@reference` hop the terminus is the function result table, so `ActorFilm` is
`@table(name: "films_for_actor")`, whose columns are `film_id` and `title`. `ratingCode: String
@field(name: "rating")` therefore names no result column, and after this item it lands the
no-binding rejection rather than the `WHERE rating = ?` the paragraph below the snippet promises;
`rating` is a `film` column, reachable only through a hop the example does not carry. `## Tests`
has this right ("filters the result by a function result column"), so this is the illustration
drifting from the plan, not the plan being wrong. It is worth fixing in the same round because the
snippet is the Goal's only worked example and the natural source for the pipeline and execution
cases the item asks for.
