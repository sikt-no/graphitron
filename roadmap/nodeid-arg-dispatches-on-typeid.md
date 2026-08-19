---
id: R673
title: "A @nodeId argument on a polymorphic-returning field binds one node type per branch instead of dispatching on the decoded typeId"
status: Spec
bucket: bug
priority: 3
theme: nodeid
depends-on: []
created: 2026-08-14
last-updated: 2026-08-19
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

## Why it happens

`FieldBuilder.buildNodeIdArgPlan` takes a single `TableRef`. On the multitable arms it is called once per participant from `FieldBuilder.lowerParticipantFilters`, each call handed that participant's own table, and `NodeIdLeafResolver.inferTypeName` then answers with the node type backing that table (`ctx.nodes.forTable(...)`). Each branch therefore carries its own expected typeId as a generation-time constant, and every `@nodeId` argument filter resolves to `CallSiteExtraction.NodeIdDecodeKeys.ThrowOnMismatch` (the seal's only arm), so the first branch's generated decode-or-throw helper throws on any id belonging to a different participant, before any branch can match.

Two facts pin the blast radius:

- Divergent expected types arise only from a bare `@nodeId`. An explicit `@nodeId(typeName:)` short-circuits inference and pins one type for every branch, and bare inference resolves the participant's own table. Bare inference does not by itself imply the same-table arm: `NodeIdLeafResolver.resolve` takes the `Resolved.SameTable` short-circuit only when no `@reference` is present, and deliberately treats a same-table `@nodeId @reference` as a self-FK instead. What closes the FK-target case is that `@reference(path:)` is one shared literal while the inferred target differs per participant, and `resolveFkJoinPath` requires the path to terminate on *that participant's* target: a path naming a `customer` constraint cannot terminate on `staff`. So a bare `@nodeId @reference` over divergent participants fails classification on every branch but one rather than reaching the loop, and divergence that does reach the loop implies same-table filter semantics on every branch. The FK-target resolutions (`DirectFk`/`TranslatedFk`) cannot diverge.
- A participant table with zero node types already rejects at build ("cannot infer node type"), and one with several rejects as ambiguous. The dispatch case therefore always sees exactly one node type per participant, pairwise distinct.

## Scope

In scope: the root multitable arms, `QueryField.QueryInterfaceField` and `QueryField.QueryUnionField`, whose participant loop (`lowerParticipantFilters`) is the only coordinate that builds one `NodeIdArgPlan` per participant. Both cardinalities, both argument shapes (`ID`, `[ID!]`), and both root shapes (plain and `@asConnection`, see D4), top-level arguments only.

Out of scope, each with its reason:

- The single-base-table arms (`QueryField.QueryTableInterfaceField`, `ChildField.TableInterfaceField`/`BatchedTableInterfaceField`): no silent runtime misbinding is reachable there. Bare `@nodeId` over a table carrying more than one node type rejects at build as ambiguous in `NodeIdLeafResolver.inferTypeName`; with exactly one node type there is a single id space and the existing throw semantics are correct. This cut currently rests on the ambiguity arm alone, so it gets an enforcer: a pipeline-tier assertion that the single-base-table arm rejects the ambiguous bare `@nodeId`, and a javadoc `{@link}` from the scope-cut site to `inferTypeName` so the reference gate pins the dependency.
- The child multitable arm (`ChildField.InterfaceField`): takes no `@nodeId` arguments at all (it correlates by FK, not by author-supplied argument).
- SQL-level pruning of non-matching union arms (emitting one branch's query instead of N-1 `falseCondition` arms): an optimization, not a correctness fix; a `falseCondition` arm is constant-folded at plan time.
- Dispatch for nested-input bare-`@nodeId` leaves: replaced day one by a build-time rejection (D5), lifted to dispatch later only if a consumer needs it.

## Implementation

**D1: second `NodeIdDecodeKeys` arm, named for pruning.** Add `record PruneOnMismatch(HelperRef.Decode decodeMethod) implements NodeIdDecodeKeys` beside `ThrowOnMismatch` in `CallSiteExtraction`. The arm carries the same single component as its sibling; what earns it a seat is that the failure mode must ride the carrier the condition glue renderer sees, because the extraction rides into the condition command row and the renderer cannot reach a field-level dispatch fact. It does not "skip" (the retired silent-drop sibling's semantics, which hid client mistakes); it prunes a branch that structurally cannot match, while D4 keeps the client error alive at field granularity. `ConditionGlueRenderer.decodeCall`'s mode-selection ternary (`instanceof ThrowOnMismatch ? THROW : SKIP`) becomes an exhaustive switch on the sealed arm in the same change, so a third arm cannot silently map to a mode. `CompositeDecodeHelperRegistry` already builds the SKIP-mode helper bodies, and `decodeCall`'s else-branch is their only main-source caller, so under a one-arm seal nothing reaches them today. That is what lets D3 restate the prune-mode list helper's contract without touching a shipped consumer surface.

**D2: one divergence producer, sealed outcome.** The cross-participant question gets a producer on the axis it lives on: one call taking the field definition plus the table-bound participant set, returning per `@nodeId` argument a sealed verdict, `SharedTarget(HelperRef.Decode)` (every participant decodes the same node type; the shipped `occupantsByAddress` shape) or `PerParticipant(Map<String, HelperRef.Decode>)` keyed by participant type name (divergent node types). The loop's per-participant plans, the branch extractions, and the field-level guard all read this one verdict instead of diffing each other's outputs; `SharedTarget` keeps `ThrowOnMismatch` exactly as today, `PerParticipant` lowers each branch with `PruneOnMismatch` and hands the field its dispatch fact. The nested-leaf rejection (D5) falls out of the same computation, not a separate predicate over the same inputs.

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

## Tests

Fixtures (sakila example): give `Staff` a bare `@node`, which also means `implements Node` and the `id: ID!` field the interface requires (`TypeBuilder` rejects `@node` without the Relay interface), making `AddressOccupant = Customer | Staff` a fully node-backed union. The bare-inference question this raises is already answered: `StoreManager` shares the `staff` table but carries no `@node`, so `NodeIdLeafResolver.inferTypeName` sees the staff table go from zero node types to one and its ambiguity arm stays quiet. Confirm with a full `mvn install -Plocal-db`. Add to `Query`:

- `occupantById(id: ID! @nodeId): AddressOccupant` (the reported single-cardinality lookup shape),
- a list-shaped sibling, e.g. `occupantsByIds(ids: [ID!] @nodeId): [AddressOccupant!]!`,
- a nullable-argument sibling for the D3 nullable cell,
- an `@asConnection` sibling for D4's second root fetcher, mirroring `occupantsByNameConnection`,
- a nested-input divergent leaf fixture for the D5 rejection (pipeline tier only).

Unit tier (`CompositeDecodeHelperRegistryTest`): the prune-mode list helper's new empty-wire contract (D3) gets its own assertion beside the existing SKIP-body pins. Those pins are `contains` assertions and survive the added fold silently, so without a new one the contract D3's list cell rests on is untested.

Pipeline tier (`MultiTableFilterLoweringTest`): divergent targets lower to `PruneOnMismatch` per participant plus the dispatch fact on the model; shared-target (`occupantsByAddress`) stays `ThrowOnMismatch` with no dispatch fact; the nested divergent leaf rejects with the D5 author error; the single-base-table arm rejects the ambiguous bare `@nodeId` (the scope-cut enforcer).

Execution tier (`MultiTableFilterExecutionTest`): the primary D3 pin is the list-shaped exact-set assertion (mixed Customer and Staff ids return exactly the named rows with correct `__typename`, nothing from unpruned branches) plus the nullable-argument absent case (unfiltered) and present-mismatched case, since the single-cardinality arm can mask an unpruned branch as an order-dependent `__typename`. Also: a Customer id returns the Customer row, a Staff id the Staff row (the reported repro), a Film id fails with the client error naming Customer and Staff, a malformed id fails with the malformed-branch message, the `@asConnection` sibling gives the same client error rather than an empty page (D4's second fetcher), and the existing `nodeIdFilter_wrongTypeId_surfacesClientError` on `occupantsByAddress` stays untouched and green (all branches share one expected type there, so the branch-level throw remains correct).

## Roadmap entries

None beyond this item. Nested-leaf dispatch and union-arm pruning are named out of scope above; file follow-ups only if a consumer asks.
