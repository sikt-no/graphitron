---
id: R676
title: "A @nodeId filter input on a multitable query cannot state a per-participant join path"
status: In Progress
bucket: bug
priority: 3
theme: nodeid
depends-on: []
created: 2026-08-14
last-updated: 2026-08-26
---

# A @nodeId filter input on a multitable query cannot state a per-participant join path

A filter input field carrying `miljoId: ID @nodeId(typeName: "Miljo")`, on a query returning a multitable interface whose participants each reach `miljo` through a differently-named FK column, cannot be authored at all. The build fails with:

> [author-error] input field 'miljoId': no unique FK from 'feide_applikasjon' to 'miljo'; declare @reference(path: [{key: ...}]) to disambiguate

and the remedy the message names does not exist for this shape: a `@reference(path:)` on an input field is stated once and applies to every participant, so one path cannot describe three differently-keyed tables. Reported against 10.0.0-RC30. The author's fallback is to decode the node id by hand inside a condition method, reimplementing the wire format the generator already owns.

## Why it happens

`NodeIdLeafResolver`'s join-path resolution takes the containing table, and with no `@reference` present falls back to single-hop FK auto-discovery via `JooqCatalog.findOutgoingFkToTable`, whose three-armed `OutgoingFkLookup` result (`Unique`, `Ambiguous`, `NoneInDirection`) `autoDiscoveryRefusal` turns into one of three refusals. On a multitable query that resolution runs once per participant, each with its own table, so any participant lacking a unique single-hop FK to the target rejects and fails the build for the whole field. The refusals have been reworded since the reported RC30 build: all three name `@reference`, but none is the message quoted above verbatim, so work from `autoDiscoveryRefusal`'s arms rather than grepping for the reporter's string.

The per-participant escape hatch exists but is out of reach at this coordinate. `@referenceFor(type:, path:)` is exactly the surface for "this participant's complete path from the parent's table", and its own documentation says participants not named keep automatic discovery, which is the semantics this case wants. It is declared `repeatable on FIELD_DEFINITION`. Meanwhile `@reference` is declared on `FIELD_DEFINITION | ARGUMENT_DEFINITION | INPUT_FIELD_DEFINITION` and `@condition` on all three. So the general per-participant path surface stops one location short of the coordinate that needs it, and the surfaces that do reach input fields cannot express per-participant variation.

## The rider: `@condition(override: true)` does not silence the gate

The reporter also observes that the FK-path demand fires even under `@condition(override: true)`, where the authored method owns the predicate and the generator's default column mapping is never used. That is the same shape already settled once for the column-miss arm, where `override: true` suppresses the miss because the author has taken responsibility for the predicate. Extending it to the `@nodeId` FK-path resolution is a separable and much cheaper change than per-participant paths, and may be the whole of what an author in this position needs.

Whether the two ship together is the Spec's call. They are independent: the override relaxation unblocks the reported schema without making per-participant paths expressible, and per-participant paths fix the general case without touching the override interaction.

## Already available, worth telling the reporter

The hand-rolled base64 in the workaround is unnecessary. `NodeIdEncoder` is emitted into `<outputPackage>.schema` whenever any type carries `@node` and exposes `peekTypeId(String)` plus a per-type `decode<TypeName>(String)` returning a typed jOOQ `RecordN` (null on malformed input or a typeId mismatch). `peekTypeId` in particular reads the discriminator without committing to a type, which is what a condition method serving several participants needs.

## Notes carried from Backlog

- Whether an input field can name participants of the field that consumes it, and what happens when the same input type is reused across two fields with different participant sets, was the open design question; the `type:` validation section below answers both.
- The failing message should not name a remedy that cannot work at the coordinate it fires on; the minting section below moves the remedy choice to where the participant is known.

Reported at https://github.com/sikt-no/graphitron/issues/525 (second half; the `@condition` overload half is its own item, R675, whose decision keeps `@condition` *method* resolution coordinate-invariant; nothing here touches method resolution).

---

## Decision: the per-participant surface reaches the decode coordinate, and override becomes an escape that works

Two tracks, one item. `@referenceFor` widens to `INPUT_FIELD_DEFINITION` and `ARGUMENT_DEFINITION` so a per-participant path is expressible at the `@nodeId` decode coordinate, and `NodeIdLeafResolver` gains an author-owned-predicate outcome so `@condition(override: true)` genuinely takes responsibility where the build's own guidance already claims it can. They ship together because both thread the same participant identity into the same resolver, and the uniformity invariant below only makes sense with both halves in view.

### The direction invariant that lets one directive serve both coordinates

On an output field, `path:` runs from the parent's table to the participant's table. At a `@nodeId` decode leaf, the participant's table is where the query is standing and the terminus is fixed by `typeName:`. One sentence covers both, and the documentation leads with it:

> The path runs in the direction the generated join runs, starting from the table the query is standing on at that coordinate.

At an output field the query stands on the parent row and joins outward to the participant; at a `@nodeId` filter leaf the predicate stands on each participant's row and reaches toward the decoded target's table. `type:` names the participant in both cases; what varies is whether the participant's table is the terminus (output) or the start (decode). This is one rule read at two coordinates, not two directives sharing a name. If the docs draft below stops reading simply during implementation, that is the signal to reopen: the fallback is a distinct directive name for the decode coordinate, not a longer explanation of the flip.

### Semantics at the decode coordinate

- `directives.graphqls` (the single location authority) adds the two locations; there is no second Java-side location table.
- `type:` names a table-bound participant of the *consuming field's* return type. `path:` is that participant's complete path from its own table to the `@nodeId` target's table.
- Participants not named keep single-hop FK auto-discovery, the same override-merge as the output coordinate.
- The path grammar inherits the decode rail's one remaining constraint, the same one explicit `@reference` obeys on a `@nodeId` leaf today: every step joins on column pairs, no `{condition:}` steps (the `NodeIdLeafResolver` arm behind `CONDITION_STEP_MARKER`, which survives because the `EXISTS` emitter is hop-general over foreign-key hops and over nothing else). The identity-carrying lift validation is gone: a chain that stops carrying its departing columns forward lands no key position and binds remotely rather than refusing.
- `@reference` and `@referenceFor` together on one leaf reject as ambiguous: a leaf states one uniform path or per-participant paths, not both.
- Plain `@reference` on a decode leaf under a multitable consumer stays legal. The output-coordinate doctrine ("a single stated path is terminal-correct for at most one participant") does not transfer: here the terminus is fixed, the stated hops resolve once per participant against that participant's own table, and a uniform path survives exactly where the per-participant resolutions coincide. The docs state that the check is per-participant rather than "a uniform path is allowed".

### `type:` validation follows the fact's grain

The authored fact is "from participant X's table, the path to the target is P". It is keyed by the definition coordinate; which participants exist is a fact of each consumer. So validity is two-layered:

- **Per use site:** applications whose `type:` names a table-bound participant of that consumer's return type select that participant's route; applications matching no participant there are inert at that consumer, exactly as unnamed participants keep auto-discovery.
- **Whole-schema:** an application whose `type:` matches no participant at *any* consumer of the input type rejects, listing each consuming coordinate and its participant names. This closes the typo hole inertness alone would open, without forcing an author to fork an input type shared by two queries whose participant sets differ. The detection is a join over facts already captured: `graphitron_reference_for` rows at the definition coordinate, `intent_input_occurrence_path` (which already materialises every occurrence of an input surface under a use site), and each root consumer's participant set.

Strict per-use-site rejection was considered and dropped: it keys validity two grains finer than the fact, and its remedy (split the input type) hand-maintains duplicate path expressions across SDL sites. Duplicate `type:` on one leaf rejects, carried over from the output coordinate unchanged. An input field consumed only by single-table queries has no participant set anywhere, so its applications reject via the whole-schema layer with a message pointing at `@reference` for that shape.

### Participant identity threading and route selection

`FieldBuilder.lowerParticipantFilters` already re-runs classification once per participant with only the table varying. The participant's identity travels as the existing model pair `ParticipantRef.TableBound` (type name and table together; a bare type-name String slot could disagree with the table it rides beside, and the two are one fact) on `ClassifyContext`, the builder-internal traversal scaffolding discarded before the model. It threads from `resolveTableFieldComponents` down through `InputFieldResolver.resolve` and `classifyInputField` to `NodeIdLeafResolver.resolve`. `ClassifyContext` is already the vehicle for exactly this kind of fact: a two-slot record today (`expandingTypes`, `enclosingOverride`) whose own javadoc says the second slot is carried ahead of its consumers so that a new classifier arm does not have to touch every call site. The participant slot is a third of the same kind, and `enclosingOverride` is half of the override half's threading already in place.

Route selection in `NodeIdLeafResolver.resolveFkJoinPath` becomes a small sealed outcome rather than an if-chain: explicit `@reference` chain, explicit `@referenceFor` route for the participant in scope, single-hop auto-discovery. The method's *return* is already sealed (`PathResolution`, `Walked` or `Refused`); what remains an if-chain is the choice of route inside it, and that is what gains the third arm. The validator mirrors those arms.

### The override escape and its uniformity invariant

`NodeIdLeafResolver.resolve` gains the predicate-ownership bit (both call sites already read the leaf's `@condition` directive and can pass `override()`; this threads a boolean the callers hold, not the condition build, so the class's "condition resolution is intentionally not owned by this resolver" note stands). `Resolved` gains a fourth arm, the author-owned-predicate outcome, produced when path resolution rejects and the predicate is author-owned. Both consumers (`BuildContext.inputFieldFromNodeIdResolved` and `FieldBuilder.classifyArgument`'s `@nodeId` arm) switch on it and mint the author-owned carrier (`InputField.ConditionOwnedField` and the argument analogue). Putting the decision in the resolver keeps two consumers from evaluating the same predicate over the same pre-resolved value; a future caller cannot silently omit it.

The behaviour ladder for a decode leaf under `@condition(override: true)`:

- **Path resolves:** unchanged pinned behaviour; the developer condition lifts to `FkTargetConditionFilter` carrying the correlation and the method receives the FK-target table alias (`NodeIdOverrideConditionFkTargetPipelineTest` stays green).
- **Path rejects:** the new arm; the method owns the whole `WHERE` contribution and receives the resolving table (each branch's stage-1 alias on a multitable consumer) plus the raw ID value. The author decodes with the generated `NodeIdEncoder` helpers (`peekTypeId`, `decode<TypeName>`); the docs show the pattern.
- **`override: false` plus rejection keeps failing**, mirroring the existing boundary pair on the column-miss arm. The column-miss guidance sentence ("set override: true so the condition method owns the WHERE predicate entirely") becomes true at this coordinate, where today it is false advice.

Mixed contracts are rejected, not documented around. On a multitable consumer, one leaf under override must not lift to the target-table contract on some branches and the branch-table contract on others: the method's first parameter is `Table<?>`-shaped, nothing fails at build or compile, and the defect surfaces as a wrong `WHERE` on a real request. The invariant gets an enforcer at `lowerParticipantFilters`, which holds every branch's lift: a split rejects at the consuming field, naming the split participants and the remedies that work (state `@referenceFor` paths for the unresolved participants to get the uniform target-table contract, or drop `@nodeId` for a plain ID condition leaf if the method should own every branch against its own table). Monotone against today's builds: all-resolve is unchanged, all-reject is new capability, mixed failed before and still fails, now with remedies that exist.

### Rejections mint at the detection site

With the participant in scope, `NodeIdLeafResolver` states the remedy that exists at the coordinate it fires on: under a participant, the no-unique-FK rejection names `@referenceFor(type: "<participant>", path:)` and the override escape; the single-table wording keeps naming `@reference`. No rewording wrap at the consuming site. Three wordings carry this, not one: `autoDiscoveryRefusal`'s `Ambiguous` arm, its reverse-FK `NoneInDirection` arm, and its no-FK-either-way `NoneInDirection` arm each name `@reference` today, and each needs the participant-scoped variant.

`lowerParticipantFilters` stops short-circuiting on the first failing participant: each participant's rejections mint at their own coordinates (the `BuildContext.mintInputFieldFailures` pattern) and the consuming field carries one consequence rejection. `aggregateChildPolymorphicErrors` is not the tool here; it string-joins messages and flattens typed arms into prose.

### Fact capture

- Input-field applications land on the existing field-keyed relations unchanged: `graphql_field` already spans input fields and `SdlFactCapture.captureInputFields` already routes input-field directives through the same field-directive walk, so `graphitron_reference_for` / `_step` / `_step_arg_mapping_pair` carry the new population as-is.
- The argument coordinate gets a parallel family (`graphitron_argument_reference_for` and step/pair siblings) mirroring `graphitron_argument_reference`.
- Capture stays total across every SDL-legal location; the decode-rail scoping lives in the validator, so a later widening is a validator change only, never a capture change.
- Stale comments updated: `graphitron_reference_for`'s table comment and `participant_type_ref`'s comment describe the output-field population only, and `FactSchemaGateTest` passes on stale-but-present comments, so nothing else catches this.

### Scope wording for the non-decode coordinates

`@referenceFor` on an input field or argument that does not carry `@nodeId` rejects, and the rejection is worded on the axis, not the directive: the decode rail is the only per-participant path consumer at this coordinate so far. The plain-`@reference` input rail (`resolveColumnForReference` also runs once per participant inside the same loop) has the same expressibility gap and is the natural follow-up; it is out of scope here, and the wording must not teach "only `@nodeId` fields may carry `@referenceFor`" as a law.

## User documentation (first-client check)

Draft for `referenceFor.adoc`, a new section after the SDL signature (whose location line gains the two coordinates); the direction-invariant sentence also moves into the intro as the rule both coordinates read:

> === On `@nodeId` filter inputs and arguments
>
> `@referenceFor` is also legal on an input field or argument that carries `@nodeId`, when the consuming query returns a multi-table interface or union. The invariant is the same at every coordinate: the path runs in the direction the generated join runs, starting from the table the query is standing on. Here the query stands on each participant's row, so `type:` names a participant of the consuming query's return type and `path:` runs from that participant's table to the `@nodeId` target's table.
>
> ```graphql
> input ApplicationFilter {
>     environmentId: ID @nodeId(typeName: "Environment")
>         @referenceFor(type: "FeideApplication", path: [{key: "feide_app_environment_fkey"}])
>         @referenceFor(type: "IdmApplication",  path: [{key: "idm_app_env_fkey"}])
> }
>
> type Query {
>     applications(filter: ApplicationFilter): [Application!]!   # multi-table interface
> }
> ```
>
> Participants you do not name keep automatic discovery. The path grammar at this coordinate is the decode rail's: foreign-key hops only, no `{condition:}` steps. An application whose `type:` is not a participant of one consuming query is inert at that consumer; a `type:` that matches no participant at any consumer is an error. On a single-table consumer there is no participant set; use `@reference`.

Draft for the global-id how-to, a new "Multitable filter inputs" subsection under the decode-side material:

> A `@nodeId` filter on a query returning a multi-table interface decodes once and filters each participant branch. When every participant reaches the target through a unique single-hop FK, discovery is automatic. When a participant's route is ambiguous or longer, state it per participant with `@referenceFor` (example as above). When no generated route fits, `@condition(override: true)` on the leaf hands the whole predicate to your method: it receives each branch's table and the raw ID, and the generated `NodeIdEncoder` decodes it (`peekTypeId(id)` reads the type discriminator without committing; `decodeEnvironment(id)` returns the typed key record, null on malformed input or a type mismatch). Mixing the two contracts on one leaf, some branches generator-routed and some method-owned, is rejected at build time.

`nodeId.adoc` gains one constraint line for the same ladder, and `condition.adoc`'s override row gains the decode-leaf case. If these drafts do not read simply, the design is wrong; the direction-invariant sentence is the load-bearing test.

## Deliverables

1. **`directives.graphqls`**: `@referenceFor` gains `INPUT_FIELD_DEFINITION | ARGUMENT_DEFINITION`; the directive-doc block gains the decode-coordinate sentence.
2. **`NodeIdLeafResolver`**: a third route arm inside `resolveFkJoinPath` (whose `PathResolution` return is already sealed); participant-aware wording on all three `autoDiscoveryRefusal` arms; predicate-ownership parameter; fourth `Resolved` arm (author-owned predicate) beside `SameTable`, `FkTarget`, and `Rejected`.
3. **Threading**: `ParticipantRef.TableBound` on `ClassifyContext`, from `resolveTableFieldComponents` through `InputFieldResolver.resolve` / `classifyInputField` to the resolver.
4. **Consumers**: fourth-arm minting at `inputFieldFromNodeIdResolved` and `classifyArgument`; the `@reference`+`@referenceFor` combo rejection; duplicate-`type:` rejection; the uniformity enforcer and participant aggregation in `lowerParticipantFilters`; the axis-worded non-decode rejection.
5. **Whole-schema unmatched-`type:` detection** over `graphitron_reference_for` × `intent_input_occurrence_path` × participant sets.
6. **Capture**: argument-coordinate `graphitron_argument_reference_for` family; stale table comments refreshed.
7. **Docs**: the drafts above landed in `referenceFor.adoc`, the global-id how-to, `nodeId.adoc`, `condition.adoc`; the generated directive-support table follows from the location change.

## Tests

Pipeline tier, asserting typed arms and classified carriers rather than message substrings:

- Per-participant routes classify: a multitable filter leaf with `@referenceFor` for two differently-keyed participants; a mixed set (one explicit, one auto-discovered) exercising the override-merge; the argument-coordinate variant.
- Reuse: the same input type consumed by a second multitable query whose participant set lacks the named `type:` classifies clean there (inert application).
- Whole-schema typo: a `type:` matching no participant at any consumer rejects, listing consumers and participant sets; the same shape with only single-table consumers rejects pointing at `@reference`.
- One-leaf rejections: duplicate `type:`; `@reference` alongside `@referenceFor`; `@referenceFor` on a non-`@nodeId` input field (axis wording).
- Override ladder: all-participants-rejected plus `override: true` lifts to the author-owned carrier at both coordinates; `override: false` still fails (boundary pair mirroring the column-miss arm's `plainInput_override*` pair); a resolved/rejected split under override rejects naming the split participants. `NodeIdOverrideConditionFkTargetPipelineTest` stays green unmodified.
- Aggregation: three unresolvable participants surface three minted participant-coordinate failures plus one consequence rejection, not the first failure alone.
- The single-table rejection rows in `GraphitronSchemaBuilderTest` (`ID_REFERENCE_AMBIGUOUS_FK`, `ID_REFERENCE_NO_FK_TO_TARGET`) stay as-is; the reworded remedy applies only under a participant.

Execution tier (`graphitron-sakila-example`): a multitable interface where one participant needs multi-FK disambiguation and another a stated multi-hop route to the same target (sakila sketch: `film` reaches `language` only ambiguously, `inventory` only through `film`; final fixture at the implementer's discretion within the lift constraints), with a `@nodeId` filter decoding and filtering correctly per branch and a wrong-typeId ID surfacing the client error; plus one override-escape case whose condition method decodes via `NodeIdEncoder` against the branch alias. The compilation tier comes free with the example build.

## Out of scope

- Widening the plain-`@reference` input rail to per-participant paths (same axis, no reported schema; follow-up candidate, wording must not foreclose it).
- The bare-`@nodeId` inference divergence on multitable consumers (`inferTypeName` re-runs per participant and can answer differently per branch with no diagnostic); filed as its own Backlog item.
- R675's overload admission; no interaction, method resolution stays coordinate-invariant.
- Replying on issue 525 happens when this ships; the reply is not a gate for Done. The `NodeIdEncoder` helpers can be pointed out to the reporter now, independent of this item.

## Reconciliation with the current tree (2026-08-25)

The plan was written on 2026-08-19; the `@nodeId` instruction population and its encode/decode
resolution became store relations on 2026-08-20, and a sweep
(`roadmap/audits/2026-08-20-nodeid-relation-impact-sweep.md`, Findings 1 and 6) flagged three places
where that move might invalidate this plan. Re-checked against the tree today: two of the three
resolved themselves and one stands. The body above has been edited to match; this section records
what moved so a reviewer does not have to re-derive it.

**Resolved: the decode rail lost one of its two grammar constraints.** The identity-carrying lift
validation is gone (`LIFT_FAILURE_MARKER` no longer exists anywhere in the tree); an absent lift now
binds remotely instead of refusing. `CONDITION_STEP_MARKER` stays, and the resolver's own javadoc
states why. The bullet under *Semantics at the decode coordinate* names one constraint now.

**Resolved: `resolveFkJoinPath` survived the relation move.** The sweep expected the method to
dissolve and take Deliverable 2 with it. `JoinPathResult` is indeed gone, but the method remains,
returns a private sealed `PathResolution`, and `NodeIdLeafResolver` still resolves routes off the
catalog rather than reading relation rows. Deliverable 2 is implementable as written and is restated
above in the current vocabulary. The author-owned-predicate outcome stays on the leaf, for the reason
the plan already gave.

**Stands: the relations have no participant dimension.** `intent_node_id_instruction` is keyed by use
site with no participant column, and its bare-inference arms reach the slot's table through
`intent_argument_scope_table`, which demands an unambiguous binding and therefore answers nothing at a
multitable coordinate. This item's reported shape is an explicit `@nodeId(typeName:)` leaf, which
resolves on the `EXPLICIT_TYPE_NAME` basis, so this is not a blocker here. It is a blocker for R726,
the bare-inference divergence this plan lists under Out of scope.

**Three items want the participant-keyed arm and none owns it.** R673 (In Review) shipped computing
its cross-participant verdict on the Java side, in `FieldBuilder`, over the classified participant
set, because the relation produces no row at a multitable coordinate on either bare-inference arm.
R726 needs the arm outright. This item needs it only if the per-participant route fact is ever moved
into the store, which it is not here. Whoever widens the population repoints one call site in R673
and unblocks R726; that ownership is worth settling before this item reaches Ready, but it is not a
dependency of this plan as scoped, which is why `depends-on` stays empty.

**What R673 left behind that this item should reuse.** `lowerParticipantFilters` now runs a
cross-participant pre-pass (`resolveNodeIdArgTargets`, returning `NodeIdParticipantTargets`) ahead of
the per-participant loop, and mints one cross-participant rejection off it (`firstNestedDivergence`,
for a nested bare-`@nodeId` leaf whose participants disagree on the node type). That is the shape and
the site this plan's uniformity enforcer wants, and its message is the model for the enforcer's
wording. The per-participant loop still returns `ParticipantFiltersResult.Rejected` on the first
failing participant, so Deliverable 4's "stop short-circuiting" requirement stands unchanged.

## What implementation settled differently

Three places where the shipped code answers a question the plan left open or answers it differently,
recorded so a reviewer reads the deviation rather than deriving it.

**The participant rides `NodeIdArgPlan` as well as `ClassifyContext`.** The plan named
`ClassifyContext` as the vehicle "from `resolveTableFieldComponents` through `InputFieldResolver.resolve`
/ `classifyInputField` to the resolver". A plan is already built once per participant, in
`resolveNodeIdArgTargets`, and is already the carrier for the other fact computed over the participant
*set* (`dispatchedArgNames`), so it carries the identity to `classifyArgument` without a fourth
parameter on three `resolveTableFieldComponents` overloads and two `classifyArguments` ones.
`ClassifyContext` still carries it for the input-field descent exactly as the plan specifies; the plan
seeds it from `plan.participant()`.

**The whole-schema detection is a `derive/` join, not a new stored view.** The plan called it "a join
over facts already captured" and named the three relations. `ReferenceForParticipantDefects` expresses
that join in jOOQ inside the detection pass, the way `AuthoredClaimConflicts` and
`ArgmappingProjectionDefects` express theirs, rather than adding a fourth
`intent_*` view. The participant set comes from `intent_field_participant_scope_table`, which already
carries the participant dimension the reconciliation section found missing from
`intent_node_id_instruction`. Output-field applications are excluded structurally rather than by a
type-kind test: an occurrence path's leaf is always an input object type, so an application on an
object field matches no occurrence and never enters the population.

**Two refusals stay refusals under `override: true`, where the plan's ladder said the escape applies.**
The ladder reads "path resolves / path rejects / `override: false` plus rejection", and the middle rung
is narrower in the code:

- A leaf-local contradiction (`@reference` alongside `@referenceFor`, a repeated `type:`) rejects
  before any route is walked. It is a malformed leaf rather than a missing route, and no authored
  method makes a contradictory pair of directives mean something.
- A route the author *stated* and got wrong (a `@referenceFor` naming a foreign key that does not
  resolve) also rejects. Naming a key asks the build to check it, a stale route left behind by a
  migration to `override: true` would otherwise pass silently, and that shape fails today, so
  refusing it is what keeps the change monotone. Only an *undiscovered* route escapes.

**One test the plan asks for is not implementable as written.** The execution-tier override case was
specified with "one override-escape case whose condition method decodes via `NodeIdEncoder` against
the branch alias". A `@condition` class is reflected *during* generation, so it compiles upstream of
the code the generator emits and cannot reference `NodeIdEncoder` at all; the class would have to live
in a module that does not exist. The fixture instead filters on the `film_id` column both participant
tables carry, which still pins everything the escape promises: the method fires once per branch,
against that branch's own table, with no implicit predicate of the generator's beside it. The
`NodeIdEncoder` decode a production author writes is documented in the global-id how-to and in the
fixture method's own javadoc, with the reason it cannot be exercised here.

## Acceptance

- The reported schema shape is authorable two ways, per-participant `@referenceFor` paths and the `@condition(override: true)` escape, and both build and filter correctly per branch.
- The no-unique-FK rejection under a participant names `@referenceFor` and the override escape; single-table wording is unchanged.
- All failing participants surface together with typed, participant-coordinate rejections.
- Full `mvn install -Plocal-db` green.
