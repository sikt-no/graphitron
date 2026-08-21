---
id: R676
title: "A @nodeId filter input on a multitable query cannot state a per-participant join path"
status: Spec
bucket: bug
priority: 3
theme: nodeid
depends-on: []
created: 2026-08-14
last-updated: 2026-08-19
---

# A @nodeId filter input on a multitable query cannot state a per-participant join path

A filter input field carrying `miljoId: ID @nodeId(typeName: "Miljo")`, on a query returning a multitable interface whose participants each reach `miljo` through a differently-named FK column, cannot be authored at all. The build fails with:

> [author-error] input field 'miljoId': no unique FK from 'feide_applikasjon' to 'miljo'; declare @reference(path: [{key: ...}]) to disambiguate

and the remedy the message names does not exist for this shape: a `@reference(path:)` on an input field is stated once and applies to every participant, so one path cannot describe three differently-keyed tables. Reported against 10.0.0-RC30. The author's fallback is to decode the node id by hand inside a condition method, reimplementing the wire format the generator already owns.

## Why it happens

`NodeIdLeafResolver`'s join-path resolution takes the containing table, and with no `@reference` present falls back to single-hop FK auto-discovery via `ctx.catalog.findUniqueFkToTable(containingTable, targetTable)`. On a multitable query that resolution runs once per participant, each with its own table, so any participant lacking a unique single-hop FK to the target rejects and fails the build for the whole field.

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
- The path grammar inherits the decode rail's constraints, the same ones explicit `@reference` obeys on a `@nodeId` leaf today: hops on column pairs, the identity-carrying lift validation, no `{condition:}` steps (the `NodeIdLeafResolver` arms behind `LIFT_FAILURE_MARKER` and `CONDITION_STEP_MARKER`).
- `@reference` and `@referenceFor` together on one leaf reject as ambiguous: a leaf states one uniform path or per-participant paths, not both.
- Plain `@reference` on a decode leaf under a multitable consumer stays legal. The output-coordinate doctrine ("a single stated path is terminal-correct for at most one participant") does not transfer: here the terminus is fixed, the stated hops resolve once per participant against that participant's own table, and a uniform path survives exactly where the per-participant resolutions coincide. The docs state that the check is per-participant rather than "a uniform path is allowed".

### `type:` validation follows the fact's grain

The authored fact is "from participant X's table, the path to the target is P". It is keyed by the definition coordinate; which participants exist is a fact of each consumer. So validity is two-layered:

- **Per use site:** applications whose `type:` names a table-bound participant of that consumer's return type select that participant's route; applications matching no participant there are inert at that consumer, exactly as unnamed participants keep auto-discovery.
- **Whole-schema:** an application whose `type:` matches no participant at *any* consumer of the input type rejects, listing each consuming coordinate and its participant names. This closes the typo hole inertness alone would open, without forcing an author to fork an input type shared by two queries whose participant sets differ. The detection is a join over facts already captured: `graphitron_reference_for` rows at the definition coordinate, `intent_input_occurrence_path` (which already materialises every occurrence of an input surface under a use site), and each root consumer's participant set.

Strict per-use-site rejection was considered and dropped: it keys validity two grains finer than the fact, and its remedy (split the input type) hand-maintains duplicate path expressions across SDL sites. Duplicate `type:` on one leaf rejects, carried over from the output coordinate unchanged. An input field consumed only by single-table queries has no participant set anywhere, so its applications reject via the whole-schema layer with a message pointing at `@reference` for that shape.

### Participant identity threading and route selection

`FieldBuilder.lowerParticipantFilters` already re-runs classification once per participant with only the table varying. The participant's identity travels as the existing model pair `ParticipantRef.TableBound` (type name and table together; a bare type-name String slot could disagree with the table it rides beside, and the two are one fact) on `ClassifyContext`, the builder-internal traversal scaffolding discarded before the model. It threads from `resolveTableFieldComponents` down through `InputFieldResolver.resolve` and `classifyInputField` to `NodeIdLeafResolver.resolve`.

Route selection in `NodeIdLeafResolver.resolveFkJoinPath` becomes a small sealed outcome rather than an if-chain: explicit `@reference` chain, explicit `@referenceFor` route for the participant in scope, single-hop auto-discovery. The validator mirrors those arms.

### The override escape and its uniformity invariant

`NodeIdLeafResolver.resolve` gains the predicate-ownership bit (both call sites already read the leaf's `@condition` directive and can pass `override()`; this threads a boolean the callers hold, not the condition build, so the class's "condition resolution is intentionally not owned by this resolver" note stands). `Resolved` gains a fourth arm, the author-owned-predicate outcome, produced when path resolution rejects and the predicate is author-owned. Both consumers (`BuildContext.inputFieldFromNodeIdResolved` and `FieldBuilder.classifyArgument`'s `@nodeId` arm) switch on it and mint the author-owned carrier (`InputField.ConditionOwnedField` and the argument analogue). Putting the decision in the resolver keeps two consumers from evaluating the same predicate over the same pre-resolved value; a future caller cannot silently omit it.

The behaviour ladder for a decode leaf under `@condition(override: true)`:

- **Path resolves:** unchanged pinned behaviour; the developer condition lifts to `FkTargetConditionFilter` carrying the correlation and the method receives the FK-target table alias (`NodeIdOverrideConditionFkTargetPipelineTest` stays green).
- **Path rejects:** the new arm; the method owns the whole `WHERE` contribution and receives the resolving table (each branch's stage-1 alias on a multitable consumer) plus the raw ID value. The author decodes with the generated `NodeIdEncoder` helpers (`peekTypeId`, `decode<TypeName>`); the docs show the pattern.
- **`override: false` plus rejection keeps failing**, mirroring the existing boundary pair on the column-miss arm. The column-miss guidance sentence ("set override: true so the condition method owns the WHERE predicate entirely") becomes true at this coordinate, where today it is false advice.

Mixed contracts are rejected, not documented around. On a multitable consumer, one leaf under override must not lift to the target-table contract on some branches and the branch-table contract on others: the method's first parameter is `Table<?>`-shaped, nothing fails at build or compile, and the defect surfaces as a wrong `WHERE` on a real request. The invariant gets an enforcer at `lowerParticipantFilters`, which holds every branch's lift: a split rejects at the consuming field, naming the split participants and the remedies that work (state `@referenceFor` paths for the unresolved participants to get the uniform target-table contract, or drop `@nodeId` for a plain ID condition leaf if the method should own every branch against its own table). Monotone against today's builds: all-resolve is unchanged, all-reject is new capability, mixed failed before and still fails, now with remedies that exist.

### Rejections mint at the detection site

With the participant in scope, `NodeIdLeafResolver` states the remedy that exists at the coordinate it fires on: under a participant, the no-unique-FK rejection names `@referenceFor(type: "<participant>", path:)` and the override escape; the single-table wording keeps naming `@reference`. No rewording wrap at the consuming site.

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
2. **`NodeIdLeafResolver`**: sealed route-selection outcome in `resolveFkJoinPath`; participant-aware rejection wording; predicate-ownership parameter; fourth `Resolved` arm (author-owned predicate).
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

## The relation move touches three parts of this plan (2026-08-20)

Read before the next Spec pass. The `@nodeId` instruction population and its encode/decode
resolution became store relations on 2026-08-20. The relation-move item already flags one of these
three (the lift constraint moving rather than vanishing); the other two it does not name.

**The decode-rail grammar keeps one of its two named constraints.** The path-grammar bullet under
*Semantics at the decode coordinate* inherits "hops on column pairs, the identity-carrying lift
validation, no `{condition:}` steps (the `NodeIdLeafResolver` arms behind `LIFT_FAILURE_MARKER` and
`CONDITION_STEP_MARKER`)". The lift conjunct goes: an absent lift becomes absent local columns and
the chain binds remotely rather than rejecting. `CONDITION_STEP_MARKER` stays, deliberately, the
`EXISTS` emitter being hop-general over foreign-key hops and nothing else. So the bullet should
name one constraint, not two, and should point at the decode relation rather than at the markers.

**Deliverable 2 restructures a method the relation move dissolves.** It asks for a "sealed
route-selection outcome in `resolveFkJoinPath`" plus a fourth `Resolved` arm. The relation move
retires `JoinPathResult`, which is what `resolveFkJoinPath` returns, and makes the class a reader
of relation rows rather than the resolver of the facts. Two plans restructure one method from
opposite directions: one replacing its return shape, the other moving the resolution out of Java.
The author-owned-predicate outcome is the part worth keeping either way, since it is a decision
about the leaf rather than a step in path resolution; where it lives is the open question.

**The relations have no participant dimension.** Deliverable 3 threads `ParticipantRef.TableBound`
down to the resolver so a route can be selected per participant. `intent_node_id_instruction` is
keyed by use site with no participant column, and its bare-inference arms reach the slot's table
through `intent_argument_scope_table`, which demands an unambiguous binding and therefore answers
nothing at a multitable coordinate. Explicit `@nodeId(typeName:)` leaves, which is this item's
reported shape, resolve on the `EXPLICIT_TYPE_NAME` arm and are unaffected, so this is not a
blocker here; but it is a blocker for the bare-inference divergence item this plan names in its
own Out of scope, and the participant-keyed arm those two need is the same one.

Detail: `roadmap/audits/2026-08-20-nodeid-relation-impact-sweep.md`, Findings 1 and 6.

## Acceptance

- The reported schema shape is authorable two ways, per-participant `@referenceFor` paths and the `@condition(override: true)` escape, and both build and filter correctly per branch.
- The no-unique-FK rejection under a participant names `@referenceFor` and the override escape; single-table wording is unchanged.
- All failing participants surface together with typed, participant-coordinate rejections.
- Full `mvn install -Plocal-db` green.

## A third item now asks for the participant-keyed arm (2026-08-21)

R673 (bare `@nodeId` argument dispatch on a polymorphic-returning field) landed its implementation
computing the cross-participant question on the Java side, in `FieldBuilder`, over the classified
participant set, because `intent_node_id_instruction` produces no row at a multitable coordinate on
either bare-inference arm. Its producer takes the field definition plus the table-bound participant
set and returns a sealed per-argument verdict, so a participant-keyed relation arm becomes the thing
that producer reads instead of the thing it computes: one call site to repoint. So the arm this item
names in "The relations have no participant dimension" now has three consumers waiting on it
(this item, R673, and R726), which is worth knowing when deciding who owns widening the population.
