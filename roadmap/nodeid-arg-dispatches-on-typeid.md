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

- Divergent expected types arise only from a bare `@nodeId`. An explicit `@nodeId(typeName:)` short-circuits inference and pins one type for every branch, and bare inference resolves the participant's own table, which classifies `Resolved.SameTable`. So divergence implies same-table filter semantics on every branch; the FK-target resolutions (`DirectFk`/`TranslatedFk`) cannot diverge.
- A participant table with zero node types already rejects at build ("cannot infer node type"), and one with several rejects as ambiguous. The dispatch case therefore always sees exactly one node type per participant, pairwise distinct.

## Scope

In scope: the root multitable arms, `QueryField.QueryInterfaceField` and `QueryField.QueryUnionField`, whose participant loop (`lowerParticipantFilters`) is the only coordinate that builds one `NodeIdArgPlan` per participant. Both cardinalities and both argument shapes (`ID`, `[ID!]`), top-level arguments only.

Out of scope, each with its reason:

- The single-base-table arms (`QueryField.QueryTableInterfaceField`, `ChildField.TableInterfaceField`/`BatchedTableInterfaceField`): no silent runtime misbinding is reachable there. Bare `@nodeId` over a table carrying more than one node type rejects at build as ambiguous in `NodeIdLeafResolver.inferTypeName`; with exactly one node type there is a single id space and the existing throw semantics are correct. This cut currently rests on the ambiguity arm alone, so it gets an enforcer: a pipeline-tier assertion that the single-base-table arm rejects the ambiguous bare `@nodeId`, and a javadoc `{@link}` from the scope-cut site to `inferTypeName` so the reference gate pins the dependency.
- The child multitable arm (`ChildField.InterfaceField`): takes no `@nodeId` arguments at all (it correlates by FK, not by author-supplied argument).
- SQL-level pruning of non-matching union arms (emitting one branch's query instead of N-1 `falseCondition` arms): an optimization, not a correctness fix; a `falseCondition` arm is constant-folded at plan time.
- Dispatch for nested-input bare-`@nodeId` leaves: replaced day one by a build-time rejection (D5), lifted to dispatch later only if a consumer needs it.

## Implementation

**D1: second `NodeIdDecodeKeys` arm, named for pruning.** Add `record PruneOnMismatch(HelperRef.Decode decodeMethod) implements NodeIdDecodeKeys` beside `ThrowOnMismatch` in `CallSiteExtraction`. The arm carries the same single component as its sibling; what earns it a seat is that the failure mode must ride the carrier the condition glue renderer sees, because the extraction rides into the condition command row and the renderer cannot reach a field-level dispatch fact. It does not "skip" (the retired silent-drop sibling's semantics, which hid client mistakes); it prunes a branch that structurally cannot match, while D4 keeps the client error alive at field granularity. `ConditionGlueRenderer.decodeCall`'s mode-selection ternary (`instanceof ThrowOnMismatch ? THROW : SKIP`) becomes an exhaustive switch on the sealed arm in the same change, so a third arm cannot silently map to a mode. `CompositeDecodeHelperRegistry` already builds the SKIP-mode helper bodies.

**D2: one divergence producer, sealed outcome.** The cross-participant question gets a producer on the axis it lives on: one call taking the field definition plus the full participant set, returning per `@nodeId` argument a sealed verdict, `SharedTarget(Resolved)` (all participants resolve one target; the shipped `occupantsByAddress` shape) or `PerParticipant(Map<participant, Resolved>)` (divergent targets). The loop's per-participant plans, the branch extractions, and the field-level guard all read this one verdict instead of diffing each other's outputs; `SharedTarget` keeps `ThrowOnMismatch` exactly as today, `PerParticipant` lowers each branch with `PruneOnMismatch` and hands the field its dispatch fact. The nested-leaf rejection (D5) falls out of the same computation, not a separate predicate over the same inputs.

The dispatch fact carries the per-participant `HelperRef.Decode` references themselves, never a restated (typeName, typeId) pair; type names and typeIds are already reachable through the participant and the decode ref, and an echoed copy could drift with nothing failing at build time. Expose it through the existing `ParticipantFilterField` capability interface as one accessor rather than duplicate components on the two leaf records. This extends the transitional walk model, which is the pragmatic call while the fact store has no planning reader; the strangler conversion inherits one seam, not two.

**Participant-arm gate.** `lowerParticipantFilters` today silently skips any participant that is not `ParticipantRef.TableBound`, but the seal also permits `JoinedTableBound` (and `Unbound`), and the stage-1 union is built over all participants. A skipped participant under dispatch would be an unpruned branch, exactly the data-correctness leak D3 exists to prevent, and would make the guard's candidate list a lie. The divergence producer therefore computes over the full `participants()` list and the `PerParticipant` verdict requires every participant to be `TableBound`; establish whether the other arms are reachable at these leaves, and either pin unreachability (assert on the residue, with the classifier guarantee stated) or reject the divergent-`@nodeId`-with-non-TableBound-participant field with an author error.

**D3: mismatch-vs-absent soundness in the condition glue.** Today `appendGuardedAnd` guards scalar terms on `local != null` and list terms on non-empty, which under a naive prune-mode swap would make present-but-mismatched indistinguishable from absent and leave the branch unfiltered. The trichotomy (absent: no conjunct; present-but-mismatched: `DSL.falseCondition()`; present-and-matched: the normal conjunct) is resolved per cell:

- Non-null scalar (the reported `id: ID!` shape): absent is unreachable, so no presence guard exists at all; the emit is `condition.and(local != null ? <compare> : DSL.falseCondition())`.
- List: move the fold into the prune-mode list helper's contract: it returns null for an absent (or empty) wire list and a list otherwise, so a non-null empty return can only mean all elements mismatched, and the glue emits `falseCondition` for it without re-reading the wire. An empty wire list keeps the shipped list-filter semantics (no conjunct, unfiltered), matching single-table `@nodeId` lists.
- Nullable scalar: null from the helper conflates absent with mismatch, and no sentinel exists in an arbitrary key type, so this one cell guards on wire presence, the same args-map read the extraction expression already performs (a presence test, never a second decode).

**D4: matches-none guard in `MultiTablePolymorphicEmitter`.** In the root fetcher, ahead of the stage-1 union, for each dispatch fact: verify every present wire id decodes for at least one participant, using helpers minted through the same `CompositeDecodeHelperRegistry` mechanism from the same `HelperRef.Decode` facts the branches consume (all participants' decodes null for an id: throw the generated `GraphitronClientException`, with `NodeIdEncoder.peekTypeId` read only for the message). The list shape checks each element and names the offending element. The message follows the existing two-branch form and names every candidate type in participant order, e.g. `Invalid node id "X" for this argument: decodes to type "Y", expected an id of one of: Customer, Staff` (wrong type) and `Invalid node id "X" for this argument: not a valid id, expected an id of one of: Customer, Staff` (malformed). This also covers the right-prefix-wrong-arity id, which the matching branch's prune helper would otherwise silently turn into an empty result.

**D5: nested-leaf backstop.** A nested-input bare-`@nodeId` leaf whose per-participant resolved targets diverge (same defect, different plumbing) rejects at classification time, produced by the D2 verdict computation, with an author error naming the input field, the participants, and their differing node types, and pointing at an explicit `@nodeId(typeName:)` as the way to pin one type. Nested leaves with a shared target keep working unchanged.

**D6: documentation edits.** `NodeIdLeafResolver`'s class javadoc asserts "Failure mode is fixed at `ThrowOnMismatch`" and `CallSiteExtraction`'s `NodeIdDecodeKeys` javadoc documents the sealed-to-one-arm state; both become false and are rewritten with the arm's pruning semantics. The user manual's global-id chapter states the throw-vs-`NullOnMismatch` asymmetry in terms of a single asserted type; it gains the multitable arm, where the assertion is a set of candidate types.

## Tests

Fixtures (sakila example): give `Staff` a bare `@node`, making `AddressOccupant = Customer | Staff` a fully node-backed union (verify with a full `mvn install -Plocal-db` that no existing bare-`@nodeId` inference over the staff table changes meaning). Add to `Query`:

- `occupantById(id: ID! @nodeId): AddressOccupant` (the reported single-cardinality lookup shape),
- a list-shaped sibling, e.g. `occupantsByIds(ids: [ID!] @nodeId): [AddressOccupant!]!`,
- a nullable-argument sibling for the D3 nullable cell,
- a nested-input divergent leaf fixture for the D5 rejection (pipeline tier only).

Pipeline tier (`MultiTableFilterLoweringTest`): divergent targets lower to `PruneOnMismatch` per participant plus the dispatch fact on the model; shared-target (`occupantsByAddress`) stays `ThrowOnMismatch` with no dispatch fact; the nested divergent leaf rejects with the D5 author error; the single-base-table arm rejects the ambiguous bare `@nodeId` (the scope-cut enforcer).

Execution tier (`MultiTableFilterExecutionTest`): the primary D3 pin is the list-shaped exact-set assertion (mixed Customer and Staff ids return exactly the named rows with correct `__typename`, nothing from unpruned branches) plus the nullable-argument absent case (unfiltered) and present-mismatched case, since the single-cardinality arm can mask an unpruned branch as an order-dependent `__typename`. Also: a Customer id returns the Customer row, a Staff id the Staff row (the reported repro), a Film id fails with the client error naming Customer and Staff, a malformed id fails with the malformed-branch message, and the existing `nodeIdFilter_wrongTypeId_surfacesClientError` on `occupantsByAddress` stays untouched and green (all branches share one expected type there, so the branch-level throw remains correct).

## Roadmap entries

None beyond this item. Nested-leaf dispatch and union-arm pruning are named out of scope above; file follow-ups only if a consumer asks.
