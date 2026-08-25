---
id: R673
title: "A @nodeId argument on a polymorphic-returning field binds one node type per branch instead of dispatching on the decoded typeId"
status: In Progress
bucket: bug
priority: 3
theme: nodeid
depends-on: []
created: 2026-08-14
last-updated: 2026-08-25
---

# A @nodeId argument on a polymorphic-returning field binds one node type per branch instead of dispatching on the decoded typeId

A by-id lookup whose return type is a multitable interface classifies clean, generates without a diagnostic, and then accepts ids of only one implementation at runtime. Reported against 10.0.0-RC30 on

```graphql
type Query {
  applikasjon(id: ID! @nodeId): Applikasjon
}
```

where `Applikasjon` is implemented by three `@table` types, each carrying its own `@node(typeId: ...)`. An id belonging to any implementation but one fails the whole field with `Invalid node id 'MjAwMTE6LTE2' for this argument: decodes to type '20011', expected a FeideApplikasjon id`. Reported at https://github.com/sikt-no/graphitron/issues/526.

## Decision: dispatch

The generated fetcher lets each participant branch match only its own ids, and an id no participant can decode stays a client error. This is what the schema shape means and what a Relay client expects from a polymorphic node lookup. The generate-time rejection (the reporter's fallback ask) ships only for the one adjacent shape dispatch does not cover day one, nested-input `@nodeId` leaves, so no silently-misbinding shape survives.

The mismatch policy resolves per the Backlog analysis: `ThrowOnMismatch`'s justification (a mismatch means the caller made a mistake) holds at field granularity, not branch granularity, once expected types diverge across branches. Dispatch keeps the throw at the granularity where the justification is true: exactly-one-branch-matches is the success path, no-branch-matches throws. The silent-null alternative (what the `Query.node` dispatcher's `NullOnMismatch` does) is rejected: every other argument-typed `@nodeId` on this surface treats a wrong id as a client error, and this field should too, just with a set of candidate types instead of one.

The reporter confirmed this reading on 2026-08-21: dispatch with each branch matching its own typeIds, and a clear error naming the candidate types on a miss, is what they expected. So the fallback ask (reject at generate time instead) is settled rather than merely chosen, and D5 is the only place it still ships.

## Why it happens

`FieldBuilder.buildNodeIdArgPlan` takes a single `TableRef`. On the multitable arms it is called once per participant from `FieldBuilder.lowerParticipantFilters`, each call handed that participant's own table, and `NodeIdLeafResolver.inferTypeName` then answers with the node type backing that table (`ctx.nodes.forTable(...)`). Each branch therefore carries its own expected typeId as a generation-time constant, and every `@nodeId` argument filter resolves to `CallSiteExtraction.NodeIdDecodeKeys.ThrowOnMismatch` (the seal's only arm), so the first branch's generated decode-or-throw helper throws on any id belonging to a different participant, before any branch can match.

Two facts pin the blast radius:

- Divergent expected types arise only from a bare `@nodeId`. An explicit `@nodeId(typeName:)` short-circuits inference and pins one type for every branch, and bare inference resolves the participant's own table. Bare inference does not by itself imply the same-table arm: `NodeIdLeafResolver.resolve` takes the `Resolved.SameTable` short-circuit only when no `@reference` is present, and deliberately treats a same-table `@nodeId @reference` as a self-FK instead. What closes the FK-target case is that `@reference(path:)` is one shared literal while both ends of its resolution are per-participant: `resolveFkJoinPath` receives that participant's `containingTable` and that participant's inferred target, and its explicit-path arm hands both to `BuildContext.parsePath`, whose terminal-target verdict compares jOOQ table-class identity rather than the `@table` echo. A path naming a `customer` constraint neither parses from `staff` nor terminates on it. So a bare `@nodeId @reference` over divergent participants fails classification on every branch but one rather than reaching the loop, and divergence that does reach the loop implies same-table filter semantics on every branch. The FK-target resolutions (`DirectFk`/`TranslatedFk`) cannot diverge.

  This cut rests on the terminal-target verdict and not on the lift, which is worth stating because R728 stage 3 stops `validateLift` rejecting: an absent lift becomes absent local columns and the chain binds remotely instead of failing. Checked against that, the conclusion survives. Relaxing the lift admits more multi-hop paths per participant, but it cannot admit two participants on one literal: a path that parses from two containing tables still terminates on one table, and one table is one shared target rather than a divergence. Stage 3 widens what classifies and leaves this scope cut where it is.
- A participant table with zero node types already rejects at build ("cannot infer node type"), and one with several rejects as ambiguous. The dispatch case therefore always sees exactly one node type per participant, pairwise distinct.

## Scope

In scope: the root multitable arms, `QueryField.QueryInterfaceField` and `QueryField.QueryUnionField`, whose participant loop (`lowerParticipantFilters`) is the only coordinate that builds one `NodeIdArgPlan` per participant. Both cardinalities, both argument shapes (`ID`, `[ID!]`), and both root shapes (plain and `@asConnection`, see D4), top-level arguments only.

Out of scope, each with its reason:

- The single-base-table arms (`QueryField.QueryTableInterfaceField`, `ChildField.TableInterfaceField`/`BatchedTableInterfaceField`): no silent runtime misbinding is reachable there. Bare `@nodeId` over a table carrying more than one node type rejects at build as ambiguous in `NodeIdLeafResolver.inferTypeName`; with exactly one node type there is a single id space and the existing throw semantics are correct. This cut currently rests on the ambiguity arm alone, so it gets an enforcer: a pipeline-tier assertion that the single-base-table arm rejects the ambiguous bare `@nodeId`, and a javadoc `{@link}` from the scope-cut site to `inferTypeName` so the reference gate pins the dependency.
- The child multitable arm (`ChildField.InterfaceField`): takes no `@nodeId` arguments at all (it correlates by FK, not by author-supplied argument).
- A polymorphic-returning field on a class-backed parent, which is what a mutation payload's field is: same reason, no `@nodeId` argument. Its producer is `FieldBuilder.classifyRecordParentPolymorphicChild`, and it is a separate axis rather than a later phase here.
- SQL-level pruning of non-matching union arms (emitting one branch's query instead of N-1 `falseCondition` arms): an optimization, not a correctness fix; a `falseCondition` arm is constant-folded at plan time.
- Dispatch for nested-input bare-`@nodeId` leaves: replaced day one by a build-time rejection (D5), lifted to dispatch later only if a consumer needs it.

The reporter asked on 2026-08-21 whether the child multitable arm and the class-backed-parent bullet above are later phases of this effort or separate filings, naming two downstream cases in their own priority order. Separate filings, and the reason is one line: this item's defect is that a `@nodeId` **argument**'s expected node type is baked per branch as a generation-time constant, and neither case carries a `@nodeId` argument. Branch selection in both is driven by the correlation, not by a decoded id, so nothing in the implementation below changes either one whichever way this item lands. (Their reading that the plan already listed payload fields out of scope was of a plan that did not mention them; the bullet above now does.)

Their first case is a mutation payload field returning the interface, worked around today by three typed lists that every consumer stitches back together. Nothing on the roadmap covers it, and it is not obviously an absent capability either: a polymorphic-returning field on a class-backed parent is a classified shape, and a payload type is class-backed, so this may be reaching `classifyRecordParentPolymorphicChild` and misbehaving rather than falling through to a single-implementation binding. On the mutation side proper, `MutationField`'s only interface arm is `MutationServiceTableInterfaceField`, a root service-returning discriminated table interface, so no mutation-rooted variant dispatches multitable by itself. Bug or capability is what a repro decides, and that decides which kind of item to file.

Their second case is a single-valued child field for a polymorphic audit reference, the parent holding an FK toward the application leg. That capability ships: single-cardinality multi-table polymorphic child fields classify on both the table-backed parent (`classifyObjectReturnChildField`) and the record-backed parent, a per-participant join path that auto-discovery cannot derive is authorable with `@referenceFor(type:, path:)`, which is what a hop from a supertype table out to each subtype table wants, and the parent-holds-FK parent-projection crash is fixed at single cardinality. The one known gap is the batched form of that correlation, which rejects at build time and is R487. So a single-valued field of this shape should already dispatch per participant; if it does not, that is a child-field classifier bug with its own issue, not a phase here.

## Where the facts live while this ships

The `@nodeId` instruction population and its encode/decode resolution became store relations on 2026-08-20, and R728 is mid-flight on the rest of that move (stage 2c on trunk as of 2026-08-21). Three consequences for this item, all checked against the tree rather than read off that plan.

**This coordinate has no instruction row, and this item does not need one.** `intent_node_id_instruction`'s two bare-inference arms reach the slot's table through `intent_argument_scope_table`, which demands an unambiguous binding; a multitable return type has none, and the name-carried arm joins the return type to `intent_node_type`, which a multitable interface is not. So the reported repro produces no row on any arm. Widening the population with a participant-keyed arm is the remedy the sweep names, wanted by R676 and R726 as well, and it is nobody's committed work today: R728's plan carries no participant dimension at any grain. This item therefore does not read the store, and says so on purpose rather than by omission. D2 states which side computes the divergence, and it is this side.

**Nothing in `graphitron` has moved yet.** `NodeIdLeafResolver` still owns resolution, `CallSiteExtraction.NodeIdDecodeKeys` still reads `permits ThrowOnMismatch` and nothing else, `PruneOnMismatch` is zero hits, and `buildNodeIdArgPlan`, `lowerParticipantFilters` and `inferTypeName` are all where the sections above put them. Verified on trunk at the time of this pass. So "Why it happens" describes the live mechanism, not a remembered one.

**The order this lands in relative to R728 stage 2 does not change the design.** D2's producer takes the field definition and the classified table-bound participant set and returns a sealed verdict. If stage 2's reader conversion lands first, the *inputs* to that producer change habitat while its signature and its verdict do not, and the participant-keyed arm becomes the thing the producer reads instead of the thing it computes: one call site to repoint, which is what a single producer buys. If this item lands first, R728 inherits a consumer that already names the grain its population lacks.

Detail: `roadmap/audits/2026-08-20-nodeid-relation-impact-sweep.md`, Finding 1 for the population gap and Finding 5 for the javadoc overlap D7 settles.

## Implementation

**D1: second `NodeIdDecodeKeys` arm, named for pruning.** Add `record PruneOnMismatch(HelperRef.Decode decodeMethod) implements NodeIdDecodeKeys` beside `ThrowOnMismatch` in `CallSiteExtraction`. The arm carries the same single component as its sibling; what earns it a seat is that the failure mode must ride the carrier the condition glue renderer sees, because the extraction rides into the condition command row and the renderer cannot reach a field-level dispatch fact. It does not "skip" (the retired silent-drop sibling's semantics, which hid client mistakes); it prunes a branch that structurally cannot match, while D4 keeps the client error alive at field granularity. `ConditionGlueRenderer.decodeCall`'s mode-selection ternary (`instanceof ThrowOnMismatch ? THROW : SKIP`) becomes an exhaustive switch on the sealed arm in the same change, so a third arm cannot silently map to a mode. `CompositeDecodeHelperRegistry` already builds the SKIP-mode helper bodies, and `decodeCall`'s else-branch is their only main-source caller, so under a one-arm seal nothing reaches them today. That is what lets D3 restate the prune-mode list helper's contract without touching a shipped consumer surface.

**D2: one divergence producer, sealed outcome.** The cross-participant question gets a producer on the axis it lives on: one call taking the field definition plus the table-bound participant set, returning per `@nodeId` argument a sealed verdict, `SharedTarget(HelperRef.Decode)` (every participant decodes the same node type; the shipped `occupantsByAddress` shape) or `PerParticipant(Map<String, HelperRef.Decode>)` keyed by participant type name (divergent node types). The loop's per-participant plans, the branch extractions, and the field-level guard all read this one verdict instead of diffing each other's outputs; `SharedTarget` keeps `ThrowOnMismatch` exactly as today, `PerParticipant` lowers each branch with `PruneOnMismatch` and hands the field its dispatch fact. The nested-leaf rejection (D5) falls out of the same computation, not a separate predicate over the same inputs.

**Which side computes it.** This side, in `FieldBuilder`, over the classified participant set, for as long as the resolver resolves. The alternative is to read a participant-keyed row out of the instruction population, and that row does not exist: the coordinate is silent, and the widening that would make it speak is unowned work wanted by two other items. Blocking a runtime-wrong bug behind it is the wrong trade, and the producer is itself the seam that makes the choice cheap to revisit. What this item owes the store side is a notification and a fixture rather than a read, both named under Tests.

Shared-ness is a property of the decode target, not of the whole `Resolved`, so neither arm carries a `Resolved`. `occupantsByAddress` (`@nodeId(typeName: "Address")` over `Customer | Staff`) resolves one node type on every participant while each participant's `Resolved.FkTarget.DirectFk` carries its own `joinPath` and `liftedSourceColumns` (`customer_address_id_fkey` vs `staff_address_id_fkey`): no single `Resolved` describes that field, and picking one participant's would be a copy the branches contradict. The per-participant `Resolved`s stay where `lowerParticipantFilters` already produces them; the verdict answers only the divergence question, and `PerParticipant`'s map *is* the dispatch fact rather than a second shape derived from it.

The dispatch fact carries the per-participant `HelperRef.Decode` references themselves, never a restated (typeName, typeId) pair; type names and typeIds are already reachable through the participant and the decode ref, and an echoed copy could drift with nothing failing at build time. Expose it through the existing `ParticipantFilterField` capability interface as one accessor rather than duplicate components on the two leaf records. This extends the transitional walk model, which is the pragmatic call while the fact store has no planning reader; the strangler conversion inherits one seam, not two.

**Participant-arm gate.** `lowerParticipantFilters` skips any participant that is not `ParticipantRef.TableBound`, and so does the emitter: `MultiTablePolymorphicEmitter.emitMethods` and `emitRootConnectionMethods` both narrow the participant list to `TableBound` before building stage 1, so a skipped participant contributes no branch rather than an unpruned one. The two sites already agree on the branch set, and dispatch inherits that agreement rather than introducing a third view: the divergence producer, the per-branch lowering, and the guard's candidate list all range over the table-bound participants, which is exactly the set stage 1 emits.

Neither other arm needs a gate. `ParticipantRef.JoinedTableBound` only ever appears in a `TableInterfaceType` participant list (stated on the record itself), which is the out-of-scope single-base-table arm, so it is unreachable at these leaves. `ParticipantRef.Unbound` *is* reachable and must stay admitted: an `@error` or nesting member of a directiveless interface / union lands there (stated on `GraphitronType.InterfaceType`), and such a participant has no table, no node type, and no branch, so leaving it out of the candidate list is correct rather than a lie. Requiring every participant to be `TableBound` for the `PerParticipant` verdict would reject unions that generate correctly today.

**D3: mismatch-vs-absent soundness in the condition glue.** Today `appendGuardedAnd` guards scalar terms on `local != null` and list terms on non-empty, which under a naive prune-mode swap would make present-but-mismatched indistinguishable from absent and leave the branch unfiltered. The trichotomy (absent: no conjunct; present-but-mismatched: `DSL.falseCondition()`; present-and-matched: the normal conjunct) is resolved per cell:

- Non-null scalar (the reported `id: ID!` shape): absent is unreachable, so no presence guard exists at all; the emit is `condition.and(local != null ? <compare> : DSL.falseCondition())`.
- List: move the fold into the prune-mode list helper's contract: it returns null for an absent (or empty) wire list and a list otherwise, so a non-null empty return can only mean all elements mismatched, and the glue emits `falseCondition` for it without re-reading the wire. An empty wire list keeps the shipped list-filter semantics (no conjunct, unfiltered), matching single-table `@nodeId` lists.
- Nullable scalar: null from the helper conflates absent with mismatch, and no sentinel exists in an arbitrary key type, so this one cell guards on wire presence, the same args-map read the extraction expression already performs (a presence test, never a second decode).

**D4: matches-none guard in `MultiTablePolymorphicEmitter`.** In each of the two root fetchers, ahead of the stage-1 union, for each dispatch fact: verify every present wire id decodes for at least one participant, using helpers minted through the same `CompositeDecodeHelperRegistry` mechanism from the same `HelperRef.Decode` facts the branches consume (all participants' decodes null for an id: throw the generated `GraphitronClientException`, with `NodeIdEncoder.peekTypeId` read only for the message). The list shape checks each element and names the offending element. The message follows the existing two-branch form and names every candidate type in participant order, e.g. `Invalid node id "X" for this argument: decodes to type "Y", expected an id of one of: Customer, Staff` (wrong type) and `Invalid node id "X" for this argument: not a valid id, expected an id of one of: Customer, Staff` (malformed). This also covers the right-prefix-wrong-arity id, which the matching branch's prune helper would otherwise silently turn into an empty result.

Two root fetchers, not one: `emitRootConnectionMethods` is reachable for the same shape, because `@asConnection` over a same-table `@nodeId` is admitted with a lint advisory (`FieldBuilder.warnAsConnectionSameTable`, emitted once across participants) rather than rejected, and its per-branch WHERE is the shipped path `occupantsByNameConnection` already pins. The branch prune rides the extraction, so the connection stage 1 gets it for free; the guard is the part that does not travel, and without it the no-branch-matches case degrades to a silent empty page instead of the client error this decision exists to keep.

**D5: nested-leaf backstop.** A nested-input bare-`@nodeId` leaf whose per-participant resolved targets diverge (same defect, different plumbing) rejects at classification time, produced by the D2 verdict computation, with an author error naming the input field, the participants, and their differing node types, and pointing at an explicit `@nodeId(typeName:)` as the way to pin one type. Nested leaves with a shared target keep working unchanged.

**D6: documentation edits.** `NodeIdLeafResolver`'s class javadoc asserts "Failure mode is fixed at `ThrowOnMismatch`" and `CallSiteExtraction`'s `NodeIdDecodeKeys` javadoc documents the sealed-to-one-arm state; both become false and are rewritten with the arm's pruning semantics. The user manual's global-id chapter states the throw-vs-`NullOnMismatch` asymmetry in terms of a single asserted type; it gains the multitable arm, where the assertion is a set of candidate types.

**D7: sequencing D6 against R728.** Both items rewrite `NodeIdLeafResolver`'s class javadoc and neither named the other. R728's retirement list rewrites the one-conjunct discriminator statement, the `FkTarget` seal's arm list, `TranslatedFk`'s record javadoc and its `@param joinPath`, plus `resolveFkJoinPath`'s identity-carrying-lift paragraph, all of them sentences D6 does not touch. So this is a rebase rather than a conflict of substance: whichever lands second rewrites its own sentences as they then read. R728 is In Progress while this item is in Spec, so the second one is this one and the obligation sits here.

## Tests

Fixtures (sakila example): give `Staff` a bare `@node`, which also means `implements Node` and the `id: ID!` field the interface requires (`TypeBuilder` rejects `@node` without the Relay interface), making `AddressOccupant = Customer | Staff` a fully node-backed union. The bare-inference question this raises is already answered: `StoreManager` shares the `staff` table but carries no `@node`, so `NodeIdLeafResolver.inferTypeName` sees the staff table go from zero node types to one and its ambiguity arm stays quiet. Confirm with a full `mvn install -Plocal-db`. Add to `Query`:

- `occupantById(id: ID! @nodeId): AddressOccupant` (the reported single-cardinality lookup shape),
- a list-shaped sibling, e.g. `occupantsByIds(ids: [ID!] @nodeId): [AddressOccupant!]!`,
- a nullable-argument sibling for the D3 nullable cell,
- an `@asConnection` sibling for D4's second root fetcher, mirroring `occupantsByNameConnection`,
- a nested-input divergent leaf fixture for the D5 rejection (pipeline tier only).

No unit-tier case. The prune-mode list helper's empty-wire contract (D3) is a behaviour, so it is pinned where the behaviour is observable: the execution-tier empty-list case below. Asserting it against the emitted method body would be a code-string assertion, which `docs/architecture/principles/development-principles.adoc` bans at every tier.

Pipeline tier (`MultiTableFilterLoweringTest`): divergent targets lower to `PruneOnMismatch` per participant plus the dispatch fact on the model; shared-target (`occupantsByAddress`) stays `ThrowOnMismatch` with no dispatch fact; the nested divergent leaf rejects with the D5 author error; the single-base-table arm rejects the ambiguous bare `@nodeId` (the scope-cut enforcer).

Execution tier (`MultiTableFilterExecutionTest`): the primary D3 pin is the list-shaped exact-set assertion (mixed Customer and Staff ids return exactly the named rows with correct `__typename`, nothing from unpruned branches) plus the nullable-argument absent case (unfiltered) and present-mismatched case, since the single-cardinality arm can mask an unpruned branch as an order-dependent `__typename`. The list cell's other half is the empty-wire case, `occupantsByIds(ids: [])`, which returns all seven occupants unfiltered: that is the one behaviour the prune-mode list helper's absent-or-empty fold exists to produce, and an empty return there would mean the fold read empty as all-mismatched. Also: a Customer id returns the Customer row, a Staff id the Staff row (the reported repro), a Film id fails with the client error naming Customer and Staff, a malformed id fails with the malformed-branch message, the `@asConnection` sibling gives the same client error rather than an empty page (D4's second fetcher), and the existing `nodeIdFilter_wrongTypeId_surfacesClientError` on `occupantsByAddress` stays untouched and green (all branches share one expected type there, so the branch-level throw remains correct).

The fixtures also leave the store side something it lacks, without this item testing it. `NodeIdInstructionTest` carries no interface or union case at all, so the silence at this coordinate is pinned in neither direction. Adding that pin here would freeze a consequence of `intent_argument_scope_table`'s certainty demand as though it were a decision somebody made, and R728's own rule is that no test asserts a relation agrees with the classified model. So the fixtures above are the shape that store-tier case wants once R728's implementer settles the population question, and telling that author is the obligation, not a test written here.

## Roadmap entries

None beyond this item. Nested-leaf dispatch and union-arm pruning are named out of scope above; file follow-ups only if a consumer asks.

Two filings do come out of the 2026-08-21 comment, and neither is a phase of this item: the payload shape, once its repro says whether it is a bug or an absent capability, and the child field only if its repro still misbinds on a current build. Ask the reporter for both repros before filing either.

Notifications, and a dependency in neither direction. R728's implementer gains a named consumer for the participant-keyed instruction arm and the first interface or union fixture its store-tier suite lacks. R676 and R726 want that same arm and should know a third item is asking for it. The javadoc sequencing in D7 is this item's to carry, being the one still in Spec.

## Implementation shipped at `2a446ec9d` (2026-08-21)

The implementation was written while this item was still in `Spec`, at the user's explicit request,
and the independent `Spec -> Ready` sign-off landed at `e9d1349ea` from a session distinct from both
the spec author and the implementer. So the plan the code implements is the approved one, and the
ordering cost the item paid is that the sign-off could not have caught a design problem before the
code existed. It found none; its own verification pass is in that commit's message.

The diff follows D1 to D7 in order, which is what makes the Done gate's first question cheap to
check. What shipped, against the plan:

* **D1** `CallSiteExtraction.PruneOnMismatch` beside `ThrowOnMismatch`, and `ConditionGlueRenderer.decodeCall`'s mode selection is now an exhaustive switch over the seal.
* **D2** `FieldBuilder.resolveNodeIdArgTargets` is the single producer, returning a sealed `NodeIdArgTarget` per `@nodeId` argument (`SharedTarget` / `PerParticipant`) plus the per-participant plans, so the participant loop does not resolve the same leaves twice. The dispatch fact is `NodeIdArgDispatch`, reached through one new `ParticipantFilterField.nodeIdArgDispatches()` accessor. Deviation from the plan's literal shape: the map is a `SequencedMap` rather than a `Map`, because `Map.copyOf` does not preserve iteration order and the generated guard's candidate list and helper registration order are both order-sensitive.
* **D3** the three trichotomy cells render as `appendPruningAnd` in the glue renderer, and the prune-mode list helper returns null for an absent *or empty* wire list.
* **D4** `MultiTablePolymorphicEmitter.nodeIdDispatchGuard`, emitted ahead of stage 1 in both root fetchers, minting its decoders through the fetcher class's own `CompositeDecodeHelperRegistry`.
* **D5** the divergent nested-input leaf rejects out of the same producer, naming the leaf's dotted path, each participant, and the node type it resolved.
* **D6/D7** `NodeIdLeafResolver` and `CallSiteExtraction` javadoc rewritten, the user manual's global-id chapter gains the polymorphic-argument section, and the `@nodeId` reference chapter gains the matching constraint bullet. Two stale sentences in `LookupRows` that the second arm falsified are corrected too.
* Tests: five new pipeline-tier cases in `MultiTableFilterLoweringTest` (including the scope-cut enforcer for the single-base-table arm), one unit-tier case in `CompositeDecodeHelperRegistryTest`, ten execution-tier cases in `MultiTableFilterExecutionTest`, and the sakila fixtures the Tests section names.

Full `mvn install -Plocal-db` green.

## Reviewer findings

**In Review -> Done, 2026-08-24: rework, one finding.** The implementation is the change this spec
approved and the goal is demonstrably delivered; the finding is a test-tier one, and the fix is one
test plus one line of this spec.

*What was checked.* Every decision D1 to D7 is present in the delivered tree and traceable:
`CallSiteExtraction.PruneOnMismatch` beside `ThrowOnMismatch` with `decodeCall` an exhaustive switch
(D1); `FieldBuilder.resolveNodeIdArgTargets` as the single producer returning the sealed
`SharedTarget` / `PerParticipant` verdict, reaching the field through one
`ParticipantFilterField.nodeIdArgDispatches()` accessor (D2); the three trichotomy cells in
`ConditionGlueRenderer.appendPruningAnd` (D3); `MultiTablePolymorphicEmitter.nodeIdDispatchGuard`
emitted in both root fetchers (D4); the nested-leaf rejection falling out of the same producer (D5);
the `NodeIdLeafResolver` / `CallSiteExtraction` / `LookupRows` javadoc and the two user-manual edits
(D6/D7). Both in-scope arms are wired identically: `QueryInterfaceField` and `QueryUnionField` call
the same `lowerParticipantFilters` and carry the fact into the same shared emitter, so the reported
interface shape inherits the union fixtures' behaviour by construction. The one deviation from the
plan's literal shape (`SequencedMap` rather than `Map`) is stated in the body with its reason, which
is the right handling. The user-facing-doc check passes (no roadmap-internal markers in either
`.adoc`); the retirement sweep does not apply (no `Retired vocabulary` section). Full
`mvn install -Plocal-db` green on the delivered tree.

*The finding, against question 2.* The unit-tier case this spec's Tests section asked for,
`CompositeDecodeHelperRegistryTest.emit_listSkipHelper_answersNullForAnEmptyWireList`, asserts
`.contains("nodeIds.isEmpty()")` against `MethodSpec.code().toString()`. That is a code-string
assertion on a generated method body, which
`docs/architecture/principles/development-principles.adoc` bans at every tier: it tests
implementation rather than behaviour, and the compile and execution tiers are meant to replace it.
The surrounding file is built on the same pattern, which explains the choice but does not make the
new assertion compliant, and the spec asked for it, so the Tests section is part of what needs
correcting.

It bears on question 2 rather than on style. Every other D3 cell has an execution pin
(`dispatch_nullableArgumentAbsent_leavesTheFieldUnfiltered`,
`dispatch_nullableArgumentPresent_prunesTheNonMatchingBranch`,
`dispatch_idsOfOneParticipantOnly_prunesTheOtherBranch`). The empty-wire-list cell has none: its only
test is the string match, so the one behaviour the prune-mode list helper's new contract exists to
produce is unpinned at the tier that would catch a regression in it.

*What would satisfy it.* Replace the unit-tier assertion with an execution-tier case in
`MultiTableFilterExecutionTest`: `occupantsByIds(ids: [])` returns all seven occupants, unfiltered.
Amend this spec's Tests section to name that case instead of the unit-tier one. The reviewer
confirmed the behaviour is already correct, so this is a tier move and not a bug fix: the delivered
`QueryConditions.decodeCustomerKeys` returns `null` for an absent *or* empty wire list and the glue
renders `if (ids != null) condition = condition.and(ids.isEmpty() ? DSL.falseCondition() : ...)`, so
the new case should pass as written.

**Author response, 2026-08-25: done as asked.** The unit-tier case is deleted, and
`MultiTableFilterExecutionTest.dispatch_emptyIdList_leavesBothBranchesUnfiltered` pins the behaviour
instead: `occupantsByIds(ids: [])` returns all seven occupants. The Tests section above no longer
asks for a unit-tier case and says why, and names the execution case in the list cell's sentence.
The behaviour needed no code change, as the reviewer predicted; the delivered helper and glue pass
the new case unmodified.

*Not blocking, no action asked.* The dispatch fixtures are all unions
(`AddressOccupant = Customer | Staff`), while the reported repro is an interface. The two arms
provably share the producer and the emitter, and the spec named union fixtures, so this is noted
rather than raised.
