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
argument-grain spending, and it is the one line that changes meaning.

1. **Carry the bound paths, not their heads.** `routineBoundArgNames` becomes a set of the dotted
   paths `argMapping` binds, with a projected node id's trailing key-column segment stripped so the
   spent leaf is the `@nodeId` field itself (`filter.actorId.actor_id` spends `filter.actorId`; the
   `KeyProjection` command's `trailingSegmentName`, read off `intent_resolved_node_key_projection`,
   already says which segment that is). A flat argument bound directly (`pEnv: env`) is a one-segment path and stays
   skipped whole, which is leaf grain trivially. The parameter name and javadoc on
   `resolveTableFieldComponents` and `classifyArguments` change with the fact they carry.
2. **Skip leaves, not arguments.** `classifyArguments` no longer skips an input-object argument
   because one of its leaves is bound; it classifies the argument and passes the bound paths down the
   nested-input walk, which skips a leaf whose path is bound and classifies every other leaf as it
   does under a table-backed field: column-bound by `@field` or name match, `@nodeId` under the
   node-id filter rules, `@condition` under its own, and `InputField.UnboundField` for a leaf that
   binds to nothing, which reaches the existing rejection at the use site. The result table the leaves
   resolve against is the chain terminus, as it already is for the field's other arguments; a routine
   terminus resolves names over the function's result columns through `intent_field_column_scope`'s
   `ROUTINE_RESULT` basis, and a catalog terminus over that table.
3. **The conflict verdict.** Where a bound leaf carries `@field` or `@condition`, the walk mints a
   structural rejection naming the leaf, the directive and the routine parameter that spends it, in
   the same family as the existing `@routine` + `@reference` directive conflict. A bound leaf carrying
   `@nodeId` is not a conflict; the projection is its consumer.
4. **The mutation arm.** `classifyMutationRoutineChain` gains the leaf accounting the read arm gets
   for free from the walk: every leaf of an argument the mutation routine opens is either a bound
   path or a rejection with the same wording `MutationInputResolver` uses for an unbound DML input
   field, so the two mutation kinds fail the same way for the same fact. Flat arguments the routine
   does not bind on a Mutation field are already outside any read surface and are not this item's
   concern.
5. **Validator mirror.** Whatever the classifier refuses at steps 3 and 4, `GraphitronSchemaValidator`
   refuses with the same message, per the "validator mirrors classifier invariants" rule, so the LSP
   surfaces the verdict at the leaf rather than the build surfacing it at the field.

## Tests

* **Pipeline** (`graphitron` pipeline tier, beside `RoutineMutationWritePipelineTest`): one case per
  verdict over the sakila routines. A leftover `@field` leaf inside a routine argument classifies as
  a column-bound filter and the emitted query carries its predicate; a leftover bare leaf whose name
  matches a result column binds by name; a leftover leaf naming nothing lands the existing no-binding
  rejection with the routine field as the use site; a bound leaf carrying `@field` lands the conflict
  rejection; a bound leaf carrying `@nodeId` does not; a Mutation routine input with an unbound leaf
  lands the DML-worded rejection. The existing routine read fixtures (`tilganger`, `Actor.films`,
  `recentFilmsForActorConnection`) keep their verdicts, which pins that a flat bound argument and a
  fully bound input object are unchanged.
* **Execution** (`graphitron-sakila-example`, beside `RoutineFieldExecutionTest`): one field over
  `films_for_actor` taking a single input object whose `actorId` feeds the routine and whose second
  leaf filters the result by a function result column; the case asserts the narrowed rows against the
  unfiltered call, so the predicate is shown to reach SQL rather than only to classify.
* **Validation** (`GraphitronSchemaValidator` test tier): the two new rejections surface at the leaf
  with the same text the classifier emits.

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
