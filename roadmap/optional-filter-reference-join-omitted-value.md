---
id: R888
title: "An optional filter field's @reference join must not constrain the query when the value is absent"
status: In Progress
bucket: bug
priority: 2
theme: codegen-correctness
depends-on: []
created: 2026-08-31
last-updated: 2026-08-31
---

# An optional filter field's @reference join must not constrain the query when the value is absent

A `@reference` path on an optional input-type filter field turns into a correlated `EXISTS`
against the referenced table. An `EXISTS` is a semi-join: it keeps only the rows that have at
least one matching row on the far side of the path. When that `EXISTS` is applied while the
filter carries no value, the query stops meaning "the whole collection" and silently starts
meaning "the part of the collection that has the relation at all". Rows without the relation
disappear from the result and from `totalCount`, with no error and no warning, which is the
hardest failure mode for a consumer to detect. The rule the coordinate owes is the one every
implicit filter conjunct already follows: an absent value contributes no conjunct.

## Field report

Reported as [issue 537](https://github.com/sikt-no/graphitron/issues/537) against `10.0.0-RC35`
(tilgangsstyring subgraph in fs-plattform): an optional connection filter field
`navnerom: [ID!] @nodeId(...) @reference(path: [{key}, {key}])`, queried with the filter
*omitted*, silently dropped the one role that had no membership row. Sibling
[issue 536](https://github.com/sikt-no/graphitron/issues/536), same schema and experiment, is
the decoding half: the same coordinate never decoded the node-id wire values, so any non-empty
filter value threw `ClassCastException`.

## Reproduction on trunk: the reported coordinate no longer reproduces, and nothing pins that

Measured at trunk `7584c75` with a scratch execution test against the sakila fixture
(`actorsByFilmFilter`, the exact reported shape: nullable filter input, `[ID!]` field carrying
`@nodeId` plus a two-hop junction `@reference`, no `@condition`), after seeding an actor with no
`film_actor` rows:

* Filter omitted, `filter: {}`, and `filter: { filmIds: null }` all rendered
  `select ... from actor` with **no `EXISTS` at all**, and the relation-less actor came back.
  The generated glue both decodes and guards:
  `if (filmIds != null && !filmIds.isEmpty()) condition = condition.and(DSL.exists(...))`.
  So the two RC35 defects are fixed on current trunk for this coordinate.
* But no test anywhere executes an input-field `@reference` coordinate with the filter argument
  absent. `TranslatedFkTargetFilterExecutionTest.junctionChain_inputFieldForm_returnsTheSameRows`
  always supplies values; its empty-list case exists only for the *argument* surface
  (`actorsByFilmIds(filmIds: [])`). `ConditionGlueRendererTest` pins the `EXISTS` shape but not
  whether it sits under a guard. The exact regression a consumer already hit in a release is one
  unpinned edit away from coming back, and it would come back silently, which is the failure
  mode itself.

## The half that still reproduces: an authored `@condition` on the same coordinate

The sibling coordinate, an optional filter field whose `@reference`-derived reach carries an
authored `@condition`, still applies the `EXISTS` unconditionally. `ConditionGlueRenderer`'s
authored arm emits `condition = condition.and(...)` with no value guard (the generated-term arm
next to it guards via `appendGuardedAnd`). Reproduced at trunk `7584c75`: with a customer row
whose `address_id` is NULL seeded, `customersByAddressDistrict(filter: {})` renders the bare
`exists (select 1 from address ... where address_id = customer.address_id and <authored>)` and
drops that customer. The convention that an author maps an absent value to `noCondition()`
cannot save this coordinate: the `EXISTS` wrapper is emitted outside the author's method, so
`noCondition()` inside it still leaves a semi-join on the path, and the author has no way to opt
out of the row-dropping. `ConditionSqlBaselineTest.fkTargetCoordinate_correlatedExistsOverTheFkHop`
currently pins this unguarded shape as the baseline.

There is a real design fork here rather than an oversight to patch: an authored condition with
`override: true` that deliberately ignores its value (the Alberta-district fixture) arguably
*wants* to fire without a value, and "every authored `@condition` produces SQL" is stated
doctrine in `FieldBuilder`. What the fork has to reconcile is that doctrine with the reporter's
expectation, which this item adopts for the implicit case and treats as the default to argue
from: omitted filter means unfiltered result, and if conditional application is not feasible for
some authored shape, that combination should be an author-error at generate time rather than a
silent semantics change of the unfiltered query.

## Decision: absent value means no conjunct wherever the wrapper is ours

The fork resolves to guarded application, scoped by who owns the row-dropping structure. Two
different things have been living under the one doctrine sentence "every authored `@condition`
produces SQL" (`FieldBuilder`'s cascade-doctrine comments, `InputField.ConditionOwnedField`
javadoc, and the user manual's cascade pages):

- **What the doctrine actually defends**: override composability. An authored method is never
  silently dropped by an enclosing `override: true` cascade, unlike the legacy generator. That
  stays fully intact; this item changes nothing about which predicates an override suppresses.
- **What the doctrine was never about**: firing a semi-join on behalf of a value that was never
  supplied. The correlated `EXISTS` around an FK-target authored condition is generator-minted
  structure, not the author's SQL, and the author cannot neutralize it from inside: returning
  `noCondition()` still leaves `EXISTS (select 1 ...)`, which drops every row with no far-side
  relation. Generator-minted structure obeys the rule every implicit conjunct already follows:
  an absent value contributes no conjunct.

Concretely, split on the `Predicate.Authored.reach()` axis the command already carries:

- **Empty reach (same-table authored condition)**: unchanged. The method always fires; the
  author's convention of mapping a null value to `noCondition()`
  (`InputFieldConditionFixtures.filmIdCondition` is the canonical shape) fully controls
  semantics because nothing generator-minted sits between the method's return value and the
  WHERE clause.
- **Non-empty reach (the FK-target form, minted from `FkTargetConditionFilter`)**: the whole
  `condition.and(DSL.exists(...))` statement moves under a presence guard taken at the grain
  of the structure it gates. The reach wrapper has exactly one owner: it is minted at one
  place (`FieldBuilder`'s reference-field arm, the `rf.condition()` lift that constructs
  `FkTargetConditionFilter`), from one input field carrying `@nodeId` + `@condition`. So the
  guard is that one field's own wire presence: did the request carry a value for the
  reach-owning filter field. A scalar field is present when its wire value is non-null; a
  list-shaped field additionally when non-empty (an explicit `null` and an empty list both
  contribute no conjunct, exactly the semantics the implicit arm already gives both shapes).
  When the field is absent, no conjunct is contributed and the authored method is not called:
  there is no value for it to map, and the one observable it could otherwise produce is the
  row-dropping wrapper.

The grain matters. Presence must not be computed off the authored method's *parameter list*
(e.g. "fires when at least one bound wire value is non-null"): that splices two independent
axes, whether the value is on the wire and whether the author happened to declare a parameter
for it. `addressDistrictAlberta(Address, Integer addressId)` and a hypothetical
`addressDistrictAlberta(Address)` describe the same query semantics and would get opposite row
sets under a signature-derived guard, and a value-less signature would need a special
generate-time rejection because it leaves nothing to guard on. At the field's grain neither
problem exists: one field, one presence fact, any signature. The renderer already owns this
kind of read (`ConditionGlueRenderer.pruningPresenceRead` reads wire presence where a decoded
local cannot distinguish absent from something else); the nested form is the same traversal
the binding locals use, stopped one step short of decoding.

**Where the guard decision lives: on the command row, as data.** `Predicate.Authored` gains a
presence component minted in `ConditionCommands.predicateOf`: always-apply for the empty-reach
arm, the owning field's wire address (outer argument name, path to the leaf, list-shapedness)
for the FK-target arm. This matches trunk's generated arm, where the presence-gating fact is
*already* producer-computed data (`ColumnTerm.nonNull`, documented as "the presence-gating
fact") and the renderer derives only the mechanical spelling. `Predicate`'s class javadoc
currently states "presence-gating is per-term data on the generated arm, not an arm"; the
change generalises that sentence honestly (per-term data on the generated arm, per-predicate
data on the authored arm, matching where each wrapper sits) instead of silently falsifying
half of it with a renderer-side derivation. Carrying the decision as a command component is
the emit side's own completeness law (the render shell makes no decision the producer could
have made), not an extension of the classified model's leaf zoo, so the strangler rule is not
in play.

**Declined alternatives**, recorded so they are not re-litigated:

- A directive spelling for "fire this condition even without a value" (an opt-out flag on
  `@condition`). Declined: the author who wants an always-on far-side restriction has a fully
  expressive escape hatch today, a field-level `@condition` (which receives the root table, as
  `customersActiveOnly` shows) writing its own `EXISTS` in jOOQ, where the semi-join is the
  author's explicit SQL rather than ours. Directives carry only what the SDL author needs to
  say.
- A generate-time rejection of value-less authored FK-target signatures. Dissolved by the
  field-grain guard: a signature that binds no wire value still guards correctly, because the
  guard never reads the signature.

**Deliberate consequences for the pinned R330 fixtures.** `addressDistrictAlberta`,
`projectNameAtlas` and their query coordinates were authored to prove alias binding (the method
receives the FK-target table inside the `EXISTS`), and their tests exercise that through
`filter: {}` precisely because the methods ignore their value. Under this decision those
queries change meaning: `filter: {}` returns the unfiltered collection. The alias-binding proof
survives untouched by supplying any real node id (the methods ignore the value and `override:
true` suppresses the implicit predicate, so the rendered SQL is byte-identical to today's
pinned strings). What moves is the meaning of the omitted-value query, and that move is pinned
deliberately by new omitted-value cases rather than absorbed silently, which is what
`ConditionSqlBaselineTest`'s charter ("such a re-pin must preserve what the shape
demonstrates") requires.

## Implementation

- `FkTargetConditionFilter` carries the owning field's wire address. The construction site
  (`FieldBuilder`'s reference-field arm, where the `rewrapForNested(c.filter(), outerArgName,
  leafPath)` call already holds both halves in scope) threads the outer argument name and the
  leaf path onto the record, alongside the reach data it already carries. List-shapedness
  comes from the field's own type, the same axis the implicit arm reads.
- `ConditionCommands.predicateOf` mints the presence component onto `Predicate.Authored`:
  always-apply for `ConditionFilter` (empty reach), the carried wire address for
  `FkTargetConditionFilter`. `Predicate.Authored`'s compact constructor holds the invariant
  that a non-empty reach carries a field-presence guard, so an unguarded reach is
  unconstructable rather than a renderer-side assertion.
- `ConditionGlueRenderer.buildGlueMethod`, authored arm: a predicate carrying a field-presence
  guard emits `if (<wire presence read>) condition = condition.and(<reachExists(...)>)`; the
  presence read is the args-map traversal to the owning field's leaf (the top-level form is
  the read `pruningPresenceRead` already emits), with the non-empty check added for a
  list-shaped field. Always-apply predicates keep the current unguarded statement.
- `Predicate`'s class javadoc: generalise the "presence-gating is per-term data on the
  generated arm, not an arm" sentence to cover both arms (per-term data on the generated arm,
  per-predicate data on the authored arm, matching where each wrapper sits).
- Doctrine comments, at the arm the change lands in: the reference-field arm's comment block
  around the `rf.condition()` lift (`FieldBuilder`, "The wrap is arity-uniform" today) states
  that the wrap applies under the owning field's presence guard. The two existing "every
  authored `@condition` produces SQL" comments (`FieldBuilder`'s `ConditionOwnedField` case
  and `InputField.ConditionOwnedField`'s javadoc) sit on the same-table arm this item leaves
  unchanged and need no edit; do not scope them for a change that does not happen there.

## Tests

Pins for the already-fixed implicit coordinate (the reported shape, deliverable regardless of
the authored-arm fix):

- Seed an actor with no `film_actor` rows in `graphitron-sakila-db`'s `init.sql` (INSERT only,
  no DDL change, so the jOOQ catalog is unaffected). Update the assertions the new row widens;
  the verification build enumerates them (at minimum
  `TranslatedFkTargetFilterExecutionTest.junctionChain_emptyList_contributesNoConjunct`).
- Execution tier, `TranslatedFkTargetFilterExecutionTest`: the input-field junction coordinate
  `actorsByFilmFilter` executed with the argument omitted, with `filter: {}`, and with
  `filter: { filmIds: null }`, each asserting the relation-less actor comes back. This is the
  regression the field report measured; without the relation-less row the case cannot bite.
- Connection form with `totalCount`: add `actorsByFilmFilterConnection(filter:
  ActorByFilmFilter): [Actor!]! @asConnection(...)` to the sakila schema and pin `totalCount`
  with the filter omitted (all actors including the uncast one) and with values supplied. The
  report measured `totalCount` drift, so the pin does too.
- Pipeline tier: the command row for the junction filter coordinate carries the
  presence-gating fact (the term's `ColumnTerm.nonNull` is false and the binding is
  list-shaped, which is what `appendGuardedAnd` spells as the guard). Typed assertion on the
  row, next to the existing translated-FK pipeline cases (`NodeIdPipelineTest`,
  `ReferenceFilterRemoteColumnPipelineTest`); code-string assertions on generated method
  bodies are banned at every tier, so the glue body itself is pinned by behaviour, not text.

The authored-arm fix:

- Pipeline tier: the `Predicate.Authored` row for an FK-target coordinate carries the
  field-presence guard with the right wire address; the row for a same-table coordinate
  carries always-apply. The doctrine boundary pinned from both sides, as data. (The compact
  constructor already makes reach-without-guard unconstructable, so no case exists for it.)
- Execution tier, `GraphQLQueryTest`: the Alberta family
  (`customersByAddressDistrict`, `customersByMultiFieldFilter`, `customersByAddressDistrictActive`,
  `store.customersByAddressDistrict` inline, `customersByAddressDistrictSplit`,
  `projectNotesByProject`, `projectNotesByPlainFilter`, `projectNotesByPlainFilterConnection`)
  updated: supply a real node id where the case proves alias binding, and add omitted-value
  siblings asserting the unfiltered collection comes back.
  `customersByAddressDistrictActive(filter: {})` becomes the composition pin: the field-level
  term still fires (active customers only), the FK-target term does not. These fixtures are
  predecessor-fidelity oracles whose normative content is the shape, not the row set a
  `filter: {}` happens to produce; each updated case states in its comment the one sentence of
  what the shape demonstrates (the method receives an aliased FK-target table inside the
  `EXISTS`; two shim terms accumulate; the inline-child and split hosts wrap correctly) and
  the supplied node id preserves exactly that.
- `ConditionSqlBaselineTest`: `fkTargetCoordinate_correlatedExistsOverTheFkHop` and
  `filteredChildCoordinate_batchedStatementCarriesTheInlineFold` supply a node id and keep
  their byte-identical strings (the method ignores the value and `override: true` suppresses
  the implicit predicate, so nothing in the statement moves). A new omitted-value case is an
  *addition* to the baseline, not a re-pin: the same coordinate rendering the bare statement
  with no `EXISTS`, placed beside the FK-target pin so the pair reads as the two arms of one
  rule. This class pins jOOQ-rendered SQL at the execution tier, which the code-string ban
  does not reach.

## Documentation

- `docs/manual/reference/directives/condition.adoc`: the FK-target contract prose (the
  "outer row set is preserved" sentence and the FK-target constraints bullet) states the
  guard: the `EXISTS` applies only when the filter field carries a value; an omitted field, an
  explicit `null`, and an empty list all leave the query unfiltered by this field; this holds
  whatever the method's signature binds, and the method is not called when the field is
  absent.
- `docs/manual/how-to/condition-cascade.adoc` and
  `docs/manual/how-to/migrating-from-legacy.adoc` (both sites state the doctrine in the same
  words; scoping one of two sites is the drift smell): the composability promise ("only the
  rewrite's own implicit predicates are negotiable") gains the second negotiable thing as a
  first-class sentence in the same voice, not a caveat footnote: the wrapper graphitron mints
  around an FK-target `@condition` obeys the absent-value rule every implicit conjunct obeys;
  the method's body is unaffected wherever it runs.
- No retired vocabulary: the doctrine is scoped, not repealed, and no symbol or mechanism is
  removed.

## Field-report follow-up

After the pins and the authored-arm fix land on trunk: reply on
[issue 537](https://github.com/sikt-no/graphitron/issues/537) and
[issue 536](https://github.com/sikt-no/graphitron/issues/536), stating which release first
carries the fixes for the reported coordinate (the implementer determines the RC from release
history; both defects were already fixed on trunk at `7584c75`) and that the authored-sibling
gap is closed by this item. The replies are part of Done, not of In Review.

## Build note

Adding seed rows moves `init.sql`; in the web sandbox a mid-session rebase that moves this file
re-triggers the jOOQ-catalog cascade, and the recovery is a rerun with `-Plocal-db` (see
`.claude/web-environment.md`). No DDL changes, so no jOOQ regeneration is otherwise implied.
