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
type Actor implements Node @table(name: "actor") @node(keyColumns: ["actor_id"]) { id: ID! }

type ActorFilm @table(name: "films_for_actor") {  # the chain terminus: the function's own result
  filmId: Int   @field(name: "film_id")
  title:  String
}

input ActorFilmFilter {
  actorId:   ID! @nodeId(typeName: "Actor")       # spent: projects actor_id into pActorId
  minLength: Int                                  # spent: feeds pMinLength
  title:     String                               # a filter on the result today? No: silently dropped
}

type Query {
  actorFilms(filter: ActorFilmFilter!): [ActorFilm!]
    @routine(name: "films_for_actor",
             argMapping: "pActorId: filter.actorId.actor_id, pMinLength: filter.minLength")
    @defaultOrder(fields: [{name: "film_id"}])
}
```

Two of that input's leaves are spent, and they are spent two different ways: `actorId` by the key
projection that hands `pActorId` a decoded `actor_id`, `minLength` as an ordinary scalar read
straight into `pMinLength`. Leaf grain is about both, not only about the projected one. The third
leaf is the one the item is for: after this item `title` is `WHERE title = ?` over the function
result, exactly as it would be on a table-backed field, binding by name, with a leaf named
differently from its column carrying `@field(name:)` here as everywhere else. What the surviving
leaves resolve against is the chain terminus, and with no `@reference` hop that is the function
result itself, which is why `ActorFilm` carries the `@table` above and why `title` rather than some
column of `film` is the filter the example can offer. The contrast that makes the point is a fourth
leaf, `foo: String`, naming no result column: on a table-backed field that already fails the build
with "input field 'foo' has no column binding and no @condition", and after this item it fails the
same way inside a routine argument, where today it is silently dead.

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
  `MutationInputResolver`: a build error naming the input type and the leaf. The rule is about the
  seat rather than about nesting, so it covers an argument slot the bindings never name as well as a
  leaf inside one, and it covers every Mutation `@routine` field rather than one classifier's worth
  of them; step 5 states the single seat that makes both true. A deliberately unread input leaf (the
  Relay `clientMutationId` pattern, an idempotency key) has no spelling on either mutation kind today
  and nothing echoes such a value back into a payload; this item states that gap in the manual rather
  than designing the escape hatch.
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

Spending is two questions with two seats, and the shape of this item follows from keeping them
apart. *Which leaves does this field spend* is answered once, where the `argMapping` paths were
resolved against the field's argument types in the first place: `RoutineDirectiveResolver.bindArgs`,
inside the `resolveNode` every `@routine` field reaches. *What follows from a leaf being spent* is
answered at the seats that consume that answer, which is classification on a read and nothing at all
on a write. Today the first question is answered in the wrong place. `FieldBuilder.routineBoundArgNames`
reads the resolved `RoutineRef.ArgBinding` rows and takes each `ParamSource.Arg` binding's
`path().headName()`, and `FieldBuilder.classifyArguments` skips an argument whose name is in that
`Set<String>`. The head-name projection is not a shortcut someone took: `ArgBinding` carries the
written path and nothing else, and telling `filter.actorId` (two input fields) from
`filter.actorId.actor_id` (two input fields and a key-column strip) needs the argument's input types,
which that seat does not have and `bindArgs` does. Leaf grain is unreachable downstream of the
resolver, which is why the derivation moves rather than being refined where it stands. The shared
input classifier's decision tree is not touched.

1. **The spent leaf is a fact the store already resolves, and one walk-side seat answers it.**
   `graphitron_argmapping_match`, at site `ROUTINE`, carries `bound_path` ("the whole written path
   where all of it resolved, and that path less its last name where one name is left over"), together
   with `bound_kind` (`ARGUMENT` or `INPUT_FIELD`), `bound_type_name` and `bound_field_name`: the
   spent leaf's own coordinate, with the projected key-column strip already applied by the store's
   rule. The classifier reads `GatheredFacts` rather than the store, so the walk side answers the same
   question itself, in `bindArgs`, and its answer is stated as the transitional twin of `bound_path`,
   pinned by a shadow-agreement anchor in `rewrite/derive` beside the walk, the way
   `InputOccurrenceShadowTest` pins the occurrence paths. `bindArgs` is where that twin is cheap and
   correct: it already holds `FieldBuilder.argSlotTypes(fieldDef)`, and `leafTypeGate` beside it
   already walks a `PathExpr`'s segments against those slot types through
   `ServiceCatalog.resolvePathLeafType` and `ServiceCatalog.pathLeafDeclaration`. What `bound_path`'s
   second reading adds is one retry: resolve the whole path as input fields, and where that fails,
   resolve the path less its last name, the leftover name being the projected key column. That retry
   is a new sibling of `pathLeafDeclaration`, never a growth inside `ServiceCatalog.pathCoordinate`
   (step 7 says why). No third rule for "which leaf does this path open" appears.
2. **Carry coordinates, not dotted strings, on the binding that resolved them.** What `bindArgs`
   derives it hands forward on the `RoutineRef.ArgBinding` it is building, beside the path rather than
   instead of it, so what the author wrote and what it resolved to stand together and a reader compares
   them the way `bound_path`'s own comment says a reader of the store does. The carrier is
   `NodeIdDecodeCoordinate`, already sealed over an `Argument` arm and an `InputField` arm carrying
   the `(containerTypeName, fieldName)` steps, and already the general use-site coordinate rather than
   a node-id-only type: `ClassifyContext.UseSite.at` and `here` return its `InputField` arm for every
   use site. Its javadoc states why a serialized path is the wrong key ("two spellings of one value
   agree until one changes, and here a disagreement reads as a dropped instruction"). A flat argument
   bound directly (`pEnv: env`) lands the `Argument` arm and keeps being skipped whole in
   `classifyArguments`, which is leaf grain trivially; a `columnMapping`-bound parameter claims no
   argument and carries no coordinate at all. Every consumer then reads a resolved fact instead of
   re-deriving one, and `routineBoundArgNames` is deleted rather than retyped.
3. **Withhold the spent leaves before classification, not after it.** Classification is a gate, not
   a lookup, so a spent leaf must never be offered to it. A leaf the classifier cannot resolve comes
   back `InputFieldResolution.Unresolved`, `InputFieldResolver.resolve` folds that into
   `Resolution.Rejected`, and `FieldBuilder.classifyArgument` turns that into an `UnclassifiedArg`
   for the *whole argument*. Only a column miss is soft: it lifts to `InputField.UnboundField`, which
   is exactly what lets a surviving leaf carry its verdict forward to the walk. A `@nodeId` leaf is
   not soft. `NodeIdLeafResolver` resolves one only where the node type's table is the containing
   table or is reachable from it by a foreign key, and a routine result table stands in neither
   relation to a node type's table: a child of a routine result keys by name-match, which is why
   `@reference` is not needed there and why no foreign key is ever found. `TypeBuilder`'s own note at the sibling call site says
   the same thing from the other side, that column-miss lifts while "NodeId resolution" failures
   remain `Unresolved`. The Goal's spent `actorId` is precisely that shape, so classifying every leaf
   and dropping the spent ones afterwards rejects the argument before anything is dropped.

   Spent-ness therefore rides into the descent. `ClassifyContext` already carries the use-site facts a
   leaf's own view cannot recover (`participant`, `enclosingOverride`, `useSite`) and already carries
   them down through the nesting arm with `UseSite.descending`; that descent is what reaches a spent
   leaf at any depth, and depth is not hypothetical, since `graphitron_argmapping_candidate` expands
   the input tree as deep as it nests and an author may write `filter.inner.key`. A seat that sees
   only the argument's top level is the wrong seat. The spent coordinates join those facts, so what
   is added to `ClassifyContext` is one component of the same kind as the participant it already
   threads.

   Withholding is then done by the two places that enumerate input fields, not by the classifier they
   call. Exactly two exist, and between them they are the descent: `InputFieldResolver.resolve` loops
   `iot.getFieldDefinitions()` for an argument's own input type, and the nesting arm inside
   `classifyInputFieldInternal` loops `nestedInputType.getFieldDefinitions()` for every level below
   it. Each skips a field whose coordinate is in the set before calling `classifyInputField`, so the
   leaf contributes neither a classified `InputField` nor a failure: it is not on this read surface,
   so there is nothing here to classify. Neither skip needs new machinery to say where it stands.
   `resolve` holds the `useSite` it was handed, so the coordinate is `useSite.at(typeName,
   f.getName())`; the nesting arm has already built `nestedCtx`, so it is
   `nestedCtx.coordinateOf(typeName, nested.getName())`. Both are existing methods.

   The alternative was to put the precondition inside `BuildContext.classifyInputField` ahead of its
   decision tree, yielding neither arm of `InputFieldResolution`. It is rejected because
   `InputFieldResolution` is sealed over `Resolved` and `Unresolved` and a third arm reaches four
   consumers to buy one skip. Three are exhaustive switches (`InputFieldResolver.resolve`,
   `TypeBuilder.resolveInputFields`, the nesting arm), and `TypeBuilder`'s serves the definition-grain
   tree built for a `@table` DML input, which no routine read reaches, so it would carry a
   can't-happen arm for a case its own path cannot produce. The fourth, `FieldRegistry.classifyInput`,
   is an `instanceof` chain rather than a switch, so a third arm would pass through it silently and
   the classification trace would stop recording that leaf with nothing failing to compile. Skipping
   at the enumerators leaves all four untouched: the carrier keeps its two arms, and the decision tree
   is untouched and learns no routine vocabulary, which is what step 3 was protecting all along.

   Nothing comes to vary by consumer that does not already. The read surface derives an argument's
   `InputField` tree per call site against that call site's resolving table, every time:
   `classifyArgument` calls `InputFieldResolver.resolve` per argument occurrence, and the one
   definition-grain input tree that does exist is what `TypeBuilder` builds for a `@table` input,
   serving the DML write path a routine read never reaches. The table the survivors resolve
   against is what `intent_input_field_resolving_table` already answers for the use site, the chain
   terminus, whose `graphitron_field_table.target_basis` is `ROUTINE_RESULT` on a routine terminus.

   `FieldBuilder.walkInputFieldConditions` then needs no predicate at all, which is the point: the
   tree it walks *is* the read surface, so an unbound survivor reaches the use-keyed no-binding
   verdict `mintCascadeVerdict` already mints on that seat, with nothing there changed.
4. **The conflict verdict is minted where the leaf is resolved.** Where a spent leaf carries `@field`
   or `@condition` it asks for a WHERE clause it will never get, and the rejection is located and
   names the leaf, the directive, and the routine parameter that spends it, in the family of the
   existing `@routine` + `@reference` directive conflict. It needs no pass of its own, no traversal
   and no classified carrier: step 1's retry returns the leaf's own `GraphQLInputValueDefinition`, so
   the directives are in hand at the moment spending is decided, one `hasAppliedDirective` away. A
   spent leaf carrying `@nodeId` is not a conflict; the projection is its consumer. Minting here
   rather than inside step 3's precondition is what leaves that precondition a pure omission, with no
   routine vocabulary inside the shared classifier.
5. **The mutation rule is the complement of the same set, at the same seat.** A Mutation `@routine`
   field classifies no arguments at all, so there is no classification seat to hang the rule on and
   no read surface to subtract from: the mutation arm has to enumerate the input the field advertises.
   `bindArgs` is where that enumeration is already possible and already scoped right.
   `FieldBuilder.argSlotTypes(fieldDef)` is every argument slot with its input type, the bindings
   under construction carry the spent coordinates of step 2, and the rule is their complement: walk
   each argument slot to its leaves, and every leaf coordinate not spent is a located rejection, one
   per leaf, at the leaf. A slot whose type is not an input object is its own leaf, so an unread flat
   argument is the zero-depth case of that walk rather than a second rule. This is the only new
   traversal the item adds.

   The seat is what makes the rule reach the whole surface, and no smaller seat does. `FieldBuilder`
   classifies a Mutation `@routine` field at two places, reached by two dispatches: `classifyField`'s
   chain interception routes the multi-node write chain to `classifyMutationRoutineChain`, landing
   `MutationField.MutationRoutineWriteField`, and `classifyMutationField` routes the hop-less shape to
   `classifyMutationRoutineCarrier`, whose admitted tail `classifyAdmittedRoutineCarrier` lands
   `MutationField.MutationRoutineWriteRecordField`. Both are shipped, both classify no arguments, and
   they share no seat, so a walk written at either one leaves the other silent with every listed test
   green. What they do share is `RoutineDirectiveResolver.resolveNode`, "the shared node resolution
   behind `resolve` and `resolveCarrierNode`": the chain seat arrives through `walkRoutineChain` and
   `resolve`, the carrier seat through `resolveCarrierNode`, and `bindArgs` runs inside it either way.
   One rule there covers both, and leaves no third seat for the item to have missed.

   `bindArgs` serves reads too, so the rule has to know which seat it stands at. That is one more
   parameter of the kind `resolveNode` already takes beside `isRoot` and `previousNodeTableSqlName`,
   threaded from `resolveCarrierNode` (always the write seat) and from `resolve`'s callers, where the
   Query and Mutation chain classifiers already stand apart. A read-or-write discriminator and nothing
   more; no classification vocabulary enters the resolver with it.

   The wording is a shared typed rejection, not a copy of `MutationInputResolver`'s sentence: that
   sentence's remedy ("or carry an override condition") is false on a routine write seat, where
   `RoutineDirectiveResolver.writeSeatReadSurfaceDeferral` defers `@condition` outright. Ownership is
   stated so two verdicts do not claim one leaf: a spent leaf carrying `@field` or `@condition` is the
   conflict (step 4); an unspent leaf is the unread rejection whatever directive it carries, that
   rejection being the one that says what the leaf failed to do.
6. **Validator and LSP.** No re-authored message. The mirror mechanism already in the tree is the
   located mint drained by `GraphitronSchemaValidator.drainBuildDiagnostics`, which is how
   `mintCascadeVerdict`'s verdicts reach the validator and the LSP at leaf grain today; the new
   verdicts mint the same way, through the `BuildContext.addDiagnostic` the resolver already holds.
   That is what lets a rejection minted at the resolver seat be located at a leaf rather than at the
   field. Nothing in `GraphitronSchemaValidator` restates the rule.
7. **The decode ledger sees the siblings, and an unresolvable sibling fails the build.** Today a
   spent argument is skipped before `disposeRefusedNodeIdArgument` runs, so a `@nodeId` leaf beside
   the bound one never gets a walk-side ledger row; its disposition comes only from the store's
   projected installs (`NodeIdDecodeCoverage`). After this change the spent leaf is withheld and its
   siblings are not, so an unspent `@nodeId` leaf meets the result table like any other filter leaf:
   where it keys, it mints a decode slot at a coordinate the ledger previously saw only as not
   reached; where it does not, it is `Unresolved` and rejects the argument, and the ledger records
   the refusal. The rejection is the honest verdict, a `@nodeId` leaf that cannot key against the
   result table being one that cannot filter it, but it is a behaviour change in a build-failing
   rule and it is the same gate step 3 withholds the spent leaf from, so both directions are pinned
   by pipeline cases rather than left to be noticed. The spent leaf itself still mints no walk-side
   row; the store's projected installs remain its only disposition.

   Step 1's retry stays a sibling of `pathLeafDeclaration` rather than a growth inside
   `ServiceCatalog.pathCoordinate` for exactly this reason. `pathCoordinate` walks every segment as an
   input field and returns null where one is not, and that null is what keeps a projected binding out
   of the decode ledger today. Teach it the second reading and the coordinate a leaf is spent at stops
   being distinguishable from the coordinate the ledger keys on, which would break this step while
   satisfying step 1's "no third rule" on paper. Two questions, two functions, even where one walk
   shape answers both.

## Tests

* **Pipeline** (`graphitron` pipeline tier, beside `RoutineMutationWritePipelineTest`): one case per
  verdict over the sakila routines. First the Goal's own schema verbatim, asserted green: a spent
  `@nodeId(typeName: "Actor")` leaf and a spent plain scalar leaf beside a surviving `title` leaf
  over `films_for_actor` classify to a `QueryTableField` with no rejection on the field and the
  survivor's predicate, and only the survivor's, in the emitted query. That case is what the shape of
  step 3 exists for, and a plan that withholds the spent leaves only after classification fails it,
  so it is written first and the rest hang off it. Then, on the read side: a leftover `@field` leaf
  inside a routine argument classifies as a column-bound filter and the emitted query carries its
  predicate; a leftover bare leaf whose name matches a result column binds by name; a leftover leaf
  naming nothing lands the existing no-binding rejection with the routine field as the use site; a
  spent leaf nested one level below the argument (`argMapping: "p: filter.inner.key"`) is withheld
  too, which pins the nesting enumerator's skip rather than only the argument's top level; a
  spent leaf carrying `@field` lands the conflict rejection; a spent leaf carrying `@nodeId` does not,
  asserted on a field that otherwise classifies so the case cannot pass by the argument having been
  rejected for some other reason; an *unspent* `@nodeId` leaf that keys against nothing on the result
  table rejects the argument (step 7).

  Three cases on the write side, because step 5's seat is what makes the rule reach them and one case
  cannot show that. An unbound leaf inside a Mutation routine *chain* field's input object lands the
  unread rejection; an unbound leaf inside a routine *carrier* mutation's input object lands the same
  rejection, asserted on a field that otherwise classifies to `MutationRoutineWriteRecordField` so it
  cannot pass by the field having been rejected for another reason; and an unread *flat* argument on a
  mutation routine lands it too, which pins the zero-depth arm. `ArgmappingKeyProjectionEmissionPipelineTest`'s
  `SHARED_ID_SDL` points both `rent_film` parameters at `input.inventoryId.inventory_id`, leaving
  `RentFilmInput.customerId` bound to nothing, so that fixture goes red under this rule and is repaired
  in the same change by binding `customerId`; the emission it asserts is unrelated to the leftover leaf.

  The existing routine read fixtures (`tilganger`, `Actor.films`, `recentFilmsForActorConnection`)
  keep their verdicts, which pins that a flat bound argument and a fully bound input object are
  unchanged. Two more cases pin the mechanism: the walk-side spent coordinates agree with
  `graphitron_argmapping_match.bound_path` over the corpus (the shadow anchor of step 1), and a
  `@nodeId` sibling of a bound leaf now appears in the decode ledger with a disposition (step 7).
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
sentence: every input the routine opens must feed a parameter, leaf by leaf and argument by argument,
an unread one is a build error as on a DML mutation, and a deliberately unread leaf has no spelling
yet. The changelog entry carries the upgrade note from the fourth decision.

## Retired vocabulary

* "An argument bound to a routine IN parameter is spent on the call": the argument-grain sentence, in
  the routine manual's read-surface paragraph, the `Query.tilgangerAdmin` fixture comment in the
  sakila example schema, the `classifyRootRoutineChain` javadoc, and the comment at
  `LauncherCommandsPipelineTest` line 246 ("the routine's own IN-parameter arguments are spent on the
  call and contribute neither"). Replaced by the leaf-grain sentence above.
* `routineBoundArgNames`, deleted rather than retyped: spending is no longer derived in
  `FieldBuilder` at all, because the seat that derives it needs the argument input types
  (`## Implementation`, preamble). The `argsBoundElsewhere` parameter survives at `classifyArguments`
  and changes type from a set of argument *names* to the coordinates of step 2; what is retired is the
  reading of spending as an argument-name membership test, and whatever carries the coordinates takes
  a name that says so.

## Other solutions we've considered

* **Argument-grain spending plus a diagnostic.** A build error for a filter-bearing leaf inside a
  spent argument, pointing at the two-argument spelling (`actorFilms(actorId: ID! @nodeId, filter:
  TitleFilter)`), which already works. Smaller, and it was this item's first shape. Rejected in the
  first decision: it stands a second rejection beside the existing one for the same fact and keeps the
  bare leaf dead.
* **Accounting only.** Leaf grain for the check but not for the behaviour: a leaf `argMapping` does
  not bind is a build error whatever it names. Simple, but it forbids the reporter's schema for no
  reason the read surface can give, and invents a rejection for a leaf the ordinary rules would have
  bound.
* **Classify every leaf, drop the spent ones at the walk.** This item's second shape, and an
  attractive one: `walkInputFieldConditions` already holds the use-site coordinate and already mints
  the no-binding verdict, so one predicate there would have been the whole change. It does not
  survive the gate standing above that seat. Classification refuses a `@nodeId` leaf it cannot key
  against the resolving table, and the refusal rejects the whole argument before the walk runs, so
  the spent leaf the Goal's schema turns on never reaches the seat that was to drop it. Recorded
  rather than dropped, because the seat is the one a reader reaches for and the failure is invisible
  from the seat itself.
* **A transitional warning.** Rejected in the fourth decision: no warning tier exists on this surface,
  and a warning about a filter that silently does nothing is still a release of the silent behaviour.
* **Reads only, mutations unchanged.** Leaves an unread mutation input leaf silent while the DML
  mutation beside it rejects the same shape; the asymmetry has no reason behind it.
* **The mutation walk inside the classifier, one seat at a time.** This item's third shape: the
  unread-leaf walk written at `classifyMutationRoutineChain`, where the mutation verdicts already
  live. Rejected because that is one of two live Mutation `@routine` classification seats and the
  other, `classifyMutationRoutineCarrier`, is reached by a different dispatch and shares no code with
  it, so the shape ships the item's own failure class on the carrier seat with every test green. It
  also cannot see an unread flat argument, there being no input tree to walk at that grain. Both
  fall out of moving the rule to the seat the two dispatches share (step 5).

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

### Round 2 (2026-09-14, Spec -> Ready, reviewer session 01WAhchXVefbFT3rEJ7VN5qy)

Verdict: revisions requested. One blocking finding on question one, plus four non-blocking notes.

Round 1's blocking finding is settled. I walked the gate myself rather than taking the revision's
word for it: `FieldBuilder.classifyArgument`'s plain-input branch calls
`InputFieldResolver.resolve`, which folds any `InputFieldResolution.Unresolved` into
`Resolution.Rejected` and then into `ArgumentRef.UnclassifiedArg` for the whole argument, and
`NodeIdLeafResolver.resolve` returns `Resolved.Rejected` on anything that is neither
`Resolved.SameTable` nor `Resolved.FkTarget`, which a routine result table can never be. So
withholding before classification is the shape the item needs, and the seat step 3 picks is a real
one: `BuildContext.classifyInputField` already receives the `ClassifyContext` whose `useSite` names
the coordinate, `ClassifyContext.UseSite.descending` already advances it through the nesting arm at
`classifyInputFieldInternal`, and `coordinateOf` already composes the same
`(containerTypeName, fieldName)` steps a spent coordinate would carry. Step 3 fits.

Question two is otherwise answered. The plan extends shapes in the tree rather than standing a
parallel mechanism beside them: one precondition threaded on a record that already threads three,
one pass over a set the SDL can be read from directly, one new walk on the Mutation seat that is
declared as new and justified by `classifyMutationRoutineChain` resolving no input type at all. I
would hand the implementation shape to an implementer as it stands.

**Finding 1 (question one): the Goal's worked example does not build, and `## Tests` makes it the
case everything else hangs off.**

`argMapping`'s right-hand side is a path, never a literal, and the rejection comes earlier than the
path resolver: `ArgBindingMap.parseArgMapping` refuses the token outright. Classified and printed,
the Goal's schema as written lands an `UnclassifiedField` reading `@routine argMapping syntax error
- expected a value name after ':' for entry 'pMinLength' but got INT(60) (expected comma-separated
'javaParam: graphqlArg' or 'javaParam: input.field' pairs)`.

Deleting the entry does not rescue it. `films_for_actor(p_actor_id INTEGER, p_min_length INTEGER)`
declares two IN parameters, and `RoutineDirectiveResolver` identity-binds an unmentioned parameter to
an argument of the same name or else rejects, which on this field is `@routine parameter 'pMinLength'
has no binding: it is not a GraphQL argument of this field and no argMapping entry names it;
available arguments are ['filter']; did you mean: filter`. Nothing in the tree binds a routine
parameter to a constant, and no fixture anywhere spells a literal right-hand side.

This blocks rather than reading as a typo because the repair changes what the implementer builds.
Binding `pMinLength` to a second leaf of `ActorFilmFilter` gives the example two spent leaves where
the prose describes one; giving the field a second flat argument named `pMinLength` concedes the
two-argument spelling the first decision rejected, in the item's only illustration of the
single-input-object schema it exists to make work. `## Tests` then names this schema as the case
written first, asserted green, with "the rest hang off it", so whichever repair is chosen is the
shape of the item's primary acceptance case, and that is the author's to settle rather than the
implementer's to improvise at the keyboard.

What would satisfy it: a worked example that builds. Every IN parameter of `films_for_actor` bound,
the spent `@nodeId` leaf, the surviving `title` leaf naming a real result column (the round 1 repair,
which is correct: the function returns `film_id` and `title`), and the `foo` contrast leaf the
paragraph below the snippet already describes. Then `## Tests` naming that exact schema for its
first case.

*Applied in this same commit, at the user's explicit direction rather than by the author.* Both
halves of the finding, and the repair, were confirmed by classifying all three schemas through
`TestSchemaHelper.buildSchema` in a throwaway test: the old snippet lands the parse rejection quoted
above, dropping the entry lands the unbound-parameter rejection quoted above, and the repaired
snippet lands `QueryField.QueryTableField`. The repair binds `pMinLength` to a second input leaf (`minLength: Int`),
declares the `Actor` node type the `@nodeId` needs, and leaves the field single-argument, so the
single-input-object narrative is intact and the example now carries both ways a leaf is spent: a key
projection and a plain scalar read. The paragraph below the snippet and the first pipeline case in
`## Tests` were updated to match. Nothing else in the plan body was touched, and the four notes
below are unaddressed.

Non-blocking, offered rather than required:

* **Where the walk-side strip lives.** Step 1 says the spent set is derived from the same
  `ArgBinding` rows as today and is the transitional twin of `bound_path`, which means it has to
  implement `bound_path`'s *second* reading, the written path less its last name. The walk side has
  a function that answers almost this question, `ServiceCatalog.pathCoordinate`, and it deliberately
  answers only the first: it walks every segment as an input field and returns null when one is not,
  which is exactly why a projected `@nodeId` binding gets no walk-side ledger row today. That null is
  load-bearing for step 7's closing sentence. If the twin is grown inside `pathCoordinate`, the
  coordinate a leaf is spent at and the coordinate the decode ledger keys on stop being
  distinguishable by the same null. Worth one sentence saying they are two questions even if one
  walk answers both, since "no third rule for which leaf this path opens" is a constraint the item
  states and an implementer could satisfy in a way that quietly breaks step 7.
* **The precondition needs an outcome the carrier does not have.** `InputFieldResolution` is sealed
  over `Resolved` and `Unresolved`, and three switches are exhaustive over exactly those two:
  `InputFieldResolver.resolve`, `TypeBuilder.resolveInputFields`, and the nesting arm inside
  `classifyInputFieldInternal`. "Yields no `InputField`" is neither arm. Mechanical to add, and the
  claim it does not disturb is the decision tree rather than the carrier, so nothing here is wrong;
  it is just the one thing an implementer invents rather than reads.
* **A fourth home for the retired sentence.** `## Retired vocabulary` names the manual paragraph, the
  `Query.tilgangerAdmin` fixture comment, and the `classifyRootRoutineChain` javadoc. All three are
  there as described. There is a fourth, a comment in `LauncherCommandsPipelineTest` reading "the
  routine's own IN-parameter arguments are spent on the call and contribute neither". The retirement
  sweep would find it; listing it costs nothing.
* **One existing Mutation fixture changes verdict under step 5.** `## Tests` pins that the existing
  routine *read* fixtures keep their verdicts, and they do. On the write side,
  `ArgmappingKeyProjectionEmissionPipelineTest`'s `SHARED_ID_SDL` points both routine parameters at
  `input.inventoryId.inventory_id`, which leaves `RentFilmInput.customerId` bound to nothing, and
  step 5 turns that into a build error on a fixture whose test asserts a successful emission. The
  plan is right about it; the test list does not account for it.

### Round 3 (2026-09-14, Spec -> Ready, reviewer session 01FeaMG3A55bBy31tXiVQc1R)

Verdict: revisions requested. One blocking finding on question two, which carries the second
decision's reach on the write side with it.

Round 2's blocking finding is settled, and verified rather than taken on trust: I classified the
repaired Goal schema verbatim through `TestSchemaHelper.buildSchema` in a throwaway pipeline test.
It lands `QueryField.QueryTableField`, no diagnostics, `filters=[]`. That is both halves at once,
that the example builds and that the silence the item exists to remove is real on exactly the schema
the item leads with: `title` is advertised in the SDL and reaches no WHERE clause. The gate analysis
step 3 rests on holds as written (`InputFieldResolver.resolve` folds any
`InputFieldResolution.Unresolved` into `Resolution.Rejected` and `FieldBuilder.classifyArgument`'s
plain-input branch into `ArgumentRef.UnclassifiedArg`), and `ClassifyContext` is a four-component
record with `UseSite.descending` already composing the `(containerTypeName, fieldName)` steps step 2
wants, so threading a fifth component is the same shape as the `participant` beside it. Question one
is well communicated: a consumer who today puts a routine key and a filter in one input object gets
the filter dropped without a word and an over-return; afterwards the key is spent, the filter is a
predicate on the function result, and a leaf naming nothing fails the build the way it would on a
table-backed field. Every symbol, relation, fixture and manual sentence the plan names exists as
named; the verification narrative is in this review commit's message.

**Finding 1 (question two): `classifyMutationRoutineChain` is one of two live Mutation `@routine`
classification seats, and step 5 names only that one, so the item ships its own failure class on the
other with a green build.**

The second decision states the mutation rule without qualification: on a Mutation `@routine` field a
leaf `argMapping` does not bind reaches nothing, so it is a build error. Step 5 then locates the walk
at `classifyMutationRoutineChain`, justified by that method resolving no input type at all. The
justification is correct and it is equally true of a seat the plan never mentions.

`FieldBuilder` classifies a Mutation `@routine` field at two places, reached by two different
dispatches. `classifyField`'s chain interception routes the multi-node write chain to
`classifyMutationRoutineChain`, landing `MutationField.MutationRoutineWriteField`. Separately,
`classifyMutationField` routes every hop-less `@routine` Mutation field to
`classifyMutationRoutineCarrier`, which admits a payload-carrier return through
`classifyAdmittedRoutineCarrier` and lands `MutationField.MutationRoutineWriteRecordField`. That
second seat is shipped, not deferred: `GraphitronSchemaBuilderTest`'s `RENT_FILM_CARRIER` fixture
classifies through it, and the manual documents the carrier return on the routine page's "Writes on
Mutation" section. It classifies no arguments either, for the same reason the plan gives for the
first seat.

I confirmed the silence is there and identical. `RENT_FILM_CARRIER` with its two flat arguments
replaced by one input object (`input: RentFilmInput!` holding `inventoryId`, `customerId` and a
`neverRead: String`, with `argMapping: "pInventoryId: input.inventoryId, pCustomerId:
input.customerId"`) classifies to `MutationRoutineWriteRecordField` with an empty diagnostics list.
`neverRead` is advertised, a client can send it, and nothing consumes it, which is the write-side
spelling of the over-return in the Goal.

This blocks rather than reading as a detail because of what the plan does and does not make fail. An
implementer who builds step 5 as written puts the walk on one seat, `## Tests`'s single mutation case
("a Mutation routine input with an unbound leaf lands the DML-worded rejection") passes against that
seat, and the carrier seat keeps the silence with every test green. Nothing in the item would notice,
and the retirement sweep would not either, the vocabulary there being about arguments rather than
about this method. An item whose Goal is that no fourth outcome exists cannot leave a fourth outcome
standing on a surface it did not look at.

What would satisfy it: say in `## Implementation` where the mutation walk runs, across both seats
rather than one, and how it is reached from two dispatches that share no seat (the two entry points
are `FieldBuilder` line 3347 and line 5768 on the current head). Whether that is one helper called
twice or something else is the author's call, and it is the author's rather than the implementer's
because it decides whether the item has one mutation acceptance case or two. Then `## Tests` needs
the carrier case beside the chain case: an unbound leaf inside a routine carrier mutation's input
object lands the same rejection, asserted on a field that otherwise classifies to
`MutationRoutineWriteRecordField` so the case cannot pass by the field having been rejected for some
other reason.

Scope note, offered rather than required: if the carrier seat is meant to be out of scope, that is a
decision and belongs in `## Decisions` with its reason, not an omission, because the second decision
as written covers it.

Non-blocking, and unchanged from round 2. All four of round 2's notes are still open and all four
are real; I checked each rather than carrying them forward on the previous reviewer's word.
`ServiceCatalog.pathCoordinate` is the walk-side function whose null is load-bearing for step 7.
`InputFieldResolution` is sealed over `Resolved` and `Unresolved` with three exhaustive switches
over exactly those two, so step 3's precondition needs a carrier arm that does not exist yet; that
is mechanical, and the three sites are `InputFieldResolver.resolve`, `TypeBuilder.resolveInputFields`
and the nesting arm in `classifyInputFieldInternal`. The fourth home for the retired sentence is
`LauncherCommandsPipelineTest` line 246. And `ArgmappingKeyProjectionEmissionPipelineTest`'s
`SHARED_ID_SDL` does point both `rent_film` parameters at `input.inventoryId.inventory_id`, leaving
`RentFilmInput.customerId` bound to nothing under a test that asserts a successful emission, so step
5 turns that fixture red and `## Tests` does not say so.

### Round 4 (2026-09-14, Spec -> Ready, reviewer session 014juQQon3ZcfyDHjTzX7iuL)

Verdict: revisions requested. Round 3's blocking finding on question two is still open, because the
plan body has not changed since it was written: the most recent commit touching this file is the
round 3 review itself. I re-derived the finding from the code rather than carrying it forward on the
previous reviewer's word, and it holds as stated. One new non-blocking note, on the same seat.

Question one is well communicated and I have nothing to add to rounds 2 and 3 on it. A consumer who
today puts a routine key and a filter in one input object gets the filter dropped in silence and an
over-return; afterwards the bound leaves are spent, the rest are ordinary filters against the chain
terminus, and a leaf naming nothing fails the build as it would on a table-backed field. The Goal's
repaired example is consistent with the catalog: `films_for_actor(p_actor_id INTEGER, p_min_length
INTEGER) RETURNS TABLE(film_id INTEGER, title TEXT)` in `init.sql`, so both IN parameters are bound
and `title` is a real result column. Every symbol, relation, fixture and manual sentence the plan
names exists as named; the verification narrative is in this review commit's message.

**Finding 1 (question two): unchanged from round 3. The Mutation `@routine` write surface has two
classification seats and step 5 names one, so the item ships its own failure class on the other with
a green build.**

Re-confirmed from the current head. `FieldBuilder` reaches a Mutation `@routine` field at two
classifiers: `classifyField`'s chain interception routes the multi-node chain to
`classifyMutationRoutineChain`, landing `MutationField.MutationRoutineWriteField`, and
`classifyMutationField` routes the hop-less shape to `classifyMutationRoutineCarrier`, whose
admitted tail `classifyAdmittedRoutineCarrier` lands `MutationField.MutationRoutineWriteRecordField`.
Neither body calls `classifyArguments`, `resolveTableFieldComponents` or `InputFieldResolver`
anywhere: both run their verdicts off the walk and the carrier scan and construct their leaf
directly. So step 5's justification, that the seat resolves no input type at all, is exactly as true
of the carrier seat as of the chain seat, and step 5 puts the walk on one of them.

One thing I can add that rounds 1 to 3 did not check, and that makes the omission easier to make
rather than less real: the *read* side is genuinely single-seated, so the plan's one-seat framing is
right there and only there. `routineBoundArgNames` has exactly one call site,
`FieldBuilder.routineChainComponents`, and that helper is called from both `classifyRootRoutineChain`
and `classifyChildRoutineChain`. Two dispatches, one seat, so step 3's precondition covers the root
and child read positions without naming either. The write side is the mirror image: two dispatches,
two seats, sharing no helper to hang a precondition on. That asymmetry is what the plan has to state,
and it is the author's to state because it decides whether the item has one mutation acceptance case
or two.

What would satisfy it is unchanged from round 3: `## Implementation` says where the mutation walk
runs across both seats and how it is reached from two dispatches that share none, and `## Tests`
carries the carrier case beside the chain case, asserted on a field that otherwise classifies to
`MutationRoutineWriteRecordField` so it cannot pass by the field having been rejected for another
reason. Or, if the carrier seat is deliberately out of scope, that is a decision with a reason in
`## Decisions`, since the second decision as written covers it.

**Note (non-blocking, same seat, one grain up): an unread *flat* argument on a Mutation `@routine`
field is silent today and stays silent under step 5.**

The second decision's reason is about the seat, not about nesting: "the function is the write, its
parameter list is the only thing that consumes input, and the post-commit re-read is keyed, not
filtered". That reason reaches a flat argument the `argMapping` never names just as it reaches a leaf
inside an input object. Nothing refuses one today: `RoutineDirectiveResolver` refuses an unbound
*parameter* ("no argMapping entry names it", line 342) and there is no diagnostic in the other
direction, and since neither mutation seat classifies arguments, an extra argument is accepted,
advertised in the emitted SDL, and read by nothing. Step 5's mechanism is "one walk over the
argument's input tree yielding leaf coordinates", which a flat scalar argument does not have, so it
does not reach this.

Non-blocking because the Goal frames the item at input fields inside an argument and a flat argument
is outside that frame, so treating it as scope the item chose not to take is defensible. Worth a
sentence either way while the mutation arm is being restated for finding 1, since a reader of the
second decision will expect the rule to cover it; if it is out of scope, a fresh Backlog item is the
place for it rather than this one.

Round 2's four non-blocking notes are all still open and all still real; I spot-checked each rather
than carrying them forward. `ServiceCatalog.pathCoordinate` is the single walk-side function whose
null step 7 leans on. `InputFieldResolution` is still sealed over `Resolved` and `Unresolved` only.
The fourth home for the retired sentence is still `LauncherCommandsPipelineTest` line 246, and
`## Retired vocabulary` still lists three. And `ArgmappingKeyProjectionEmissionPipelineTest`'s
`SHARED_ID_SDL` still leaves `RentFilmInput.customerId` bound to nothing under a test asserting a
successful emission, which step 5 turns red without `## Tests` saying so.

*Revision applied in a later commit by this same reviewer session, at the user's explicit direction
rather than by the author, following the precedent of the round 2 repair.* The blocking finding is
settled by moving the derivation of spent-ness to `RoutineDirectiveResolver.bindArgs`, the seat
inside `resolveNode` that both Mutation dispatches and both read dispatches already share, and
carrying the resolved coordinate on the `ArgBinding` beside the path. `## Implementation`'s preamble
and steps 1, 2, 4, 5, 6 and 7 were rewritten for it; step 3 stands as it was, the precondition it
states being unchanged by where the set comes from. The same move settles three of the open notes:
the walk-side strip is a new sibling of `ServiceCatalog.pathLeafDeclaration` and `pathCoordinate` is
explicitly left alone (step 7), the unread flat argument is the zero-depth arm of step 5's walk, and
the fourth home for the retired sentence is now listed. `## Tests` gained the carrier and flat-argument
write cases and states the `SHARED_ID_SDL` repair; `## Other solutions we've considered` records the
one-seat walk as rejected, with its reason.

The last open note is settled in the same way, at the user's direction: step 3 now withholds at the
two places that enumerate input fields (`InputFieldResolver.resolve` and the nesting arm inside
`classifyInputFieldInternal`) rather than inside `BuildContext.classifyInputField`, so
`InputFieldResolution` keeps its two arms. The rejected alternative is recorded in step 3 with its
reason: a third arm reaches four consumers to buy one skip, and the fourth of them
(`FieldRegistry.classifyInput`) is an `instanceof` chain that would pass it through silently. Both
skips read a coordinate from a method that already exists (`UseSite.at`, `ClassifyContext.coordinateOf`).
All five non-blocking notes rounds 2 and 4 raised (round 3 re-checked round 2's
four and added none) are now closed.

Because this session wrote plan prose, it is disqualified from the Spec -> Ready sign-off on this
item. The next gate needs a reviewer session that has committed neither the plan nor this revision.
