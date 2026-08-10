---
id: R618
title: "Routine mutation: admit a payload carrier return with a typed errors channel"
status: Spec
bucket: feature
priority: 3
theme: routine
depends-on: []
created: 2026-08-10
last-updated: 2026-08-10
---

# Routine mutation: admit a payload carrier return with a typed errors channel

A `@routine` write on `Mutation` can only return the terminus `@table` type directly, and only
through a `@routine` + `@reference` chain. Every other return shape on the chain path is rejected
by `RoutineDirectiveResolver.resolve` with `@routine requires a @table-annotated return type`,
and a `@routine` with no `@reference` hop at all lands the single-node typed `Deferred`
(`FieldBuilder.MUTATION_SINGLE_NODE_ROUTINE_DEFERRAL`: "no post-commit table to re-read the
response from"). Between them the two rules block the return shape most authors reach for on a
fallible write: the payload carrier.

Both other write families already admit that carrier. `@mutation` DML resolves it through
`BuildContext.scanStructuralDmlPayload` (a payload Object with exactly one non-errors data field
whose element is `@table`-bound, plus an optional errors-shaped field), landing
`MutationDmlRecordField` with an `ErrorChannel.RouterDispatched`; the `@service` family resolves
the sibling `scanStructuralServiceCarrierPayload`. `@routine` resolves neither, and
`MutationRoutineWriteField.errorChannel()` is pinned `Optional.empty()` with the reason stated as
the terminus rule: the return "is the direct terminus `@table` type, never a payload carrying a
typed `errors` field". So the two halves are one gap, not two. Admitting the carrier is what makes
a typed channel representable on a routine write at all; without it a routine that raises for a
business reason reaches the client through the redacting catch arm as `An error occurred.
Reference: <uuid>`, and the author's only route to a typed `errors` list is to abandon `@routine`
and hand-write the call behind `@service`.

The authoring shape that motivates this (from a consumer schema) is an access-controlled create:

```graphql
type OpprettFeideBrukerPayload {
  feideBruker: FeideBruker      # @table-bound element, the carrier's data field
  errors: [OpprettFeideBrukerError]
}

type Mutation {
  opprettFeideBruker(input: OpprettFeideBrukerInput!): OpprettFeideBrukerPayload
    @routine(name: "opprett_feide_bruker", argMapping: "...")
}
```

No `@reference` anywhere. `@routine` alone means: run the routine inside the write transaction,
capture its result, commit. Everything the payload renders is a post-commit follow-up query off
the captured result; the write transaction never contains a join (D1). The data field's path is
implicit: the single name-matched hop to its own element's table (D2).

Note what the RLS setting does to the value of each half: the caller cannot read the row it just
created (the read policy requires a role assignment the new row does not yet have), so the
post-commit re-read legitimately returns nothing and the data field is always null. The entire
informational content of the response is the errors list. A shape where the errors channel is the
point, and the data field is a structural placeholder, is exactly the one the current pinning
refuses.

## The two statements, and who owns them

The routine write and the DML carrier already emit the same two statements; they differ only in
where the split falls. `buildSingleRecordTwoStepFetcher` (the DML carrier) emits step 1 alone: a
PK-only `RETURNING` inside `dsl.transactionResult(...)`, returned as the fetcher's source. The
response SELECT is step 2, and it lives in the carrier's data field, classified by
`buildPayloadCarrierBatchedTableField` as a record-sourced `ChildField.BatchedTableField` whose
`ParentCorrelation.OnLiftedSlots` correlates the source record's PK back to the catalog rows.
`buildMutationRoutineWriteFetcher` (the shipped direct-return routine write) emits both steps in
one method body: step 1 captures hop 0's key columns off the routine result inside the
transaction boundary, step 2 anchors on hop 0's table post-commit and projects the terminus.

So admitting the carrier is not a new emit story. It is giving the routine write the DML
carrier's split exactly: the mutation fetcher owns step 1 alone, and step 2 belongs to the data
field the DML family already routes it through. This item raises that split to a stated rule
rather than an emergent property: **the write transaction contains the routine call and a
projection of the routine's own result columns, nothing else, ever.** No join runs inside it, at
any hop count, in any future extension of this shape; anything beyond the capture is a
post-commit follow-up query owned by a payload field. The scope boundary's residual-hop deferral
inherits this rule as decided, not open.

The capture itself is two facts at two grains, and the model should say so rather than blur them
under one name. A hop out of a routine result has no FK metadata to ride, so
`BuildContext.synthesizeNameMatchedJoin` keys it by matching the *target table's primary key*
columns by SQL name against the routine's result columns, producing pairs (routine result
column, target key column). The pairs' **target side** is the target table's PK by construction,
which is what the read-side correlation (`OnLiftedSlots`) wants, and it is the same value the DML
and `@service` carriers compute; the pairs' **source side** (which routine result columns to
project) is the routine-only fact, consumed by step 1 alone. So the correlation keeps reading a
uniform, total `correlationColumns()` (the target table's PK columns, on every carrier family),
the name-matched pairs travel separately on the routine producer observation for step 1 (D4),
and the leaf's compact constructor pins the two together: the pairs' keying is name-matched and
their target side equals the target table's PK columns. That pin is the no-join-in-the-
transaction rule expressed in the type system, because a name-matched hop 0 is precisely the
case where step 1 needs no join. Under the old draft the tuple had two independent derivations
(the mutation field's chain and the data field's correlation) that happened to coincide; here it
is derived once, at grounding, from two catalog facts (D2), and every reader reads the carried
result.

## Design

### D1: the return shape classifies once; the path's seat derives from it

The classified fact is the mutation field's return shape, not its directive set. A return that
resolves `@table`-bound is the direct shape: the shipped `@routine` + `@reference` chain path,
untouched end to end. `classifyField`'s chain interception, `walkRoutineChain`,
`routineChainVerdict`, the hop-0 re-read-anchor verdict and the two-step fetcher all keep their
current contracts, and no seat on that path learns anything about carriers. A return that scans
as a routine carrier is the new shape, and on it the reference path's one legal seat is the
payload's data field (D2), because that is the field whose rows the path fetches.

The author-facing consequence is still a one-sentence rule, and it is this item's first-client
check: with `@reference` on the mutation field, the field returns the table the chain reaches;
without it, the field returns a payload whose data field declares its own path. But the model
fact underneath is the return shape, and the routing already agrees: a hop-less `@routine` never
enters the chain path today (`isMutationWriteChain` requires more than one chain directive), so
it falls through to `classifyMutationField`'s `@routine` branch, which is exactly where the
carrier fork belongs. That branch today lands every hop-less routine on the single-node typed
`Deferred`; after this item it runs the carrier scan first, `Admit` with a `Table`-element data
field classifies the new shape, and everything else keeps the deferral. The deferral's text
(`MUTATION_SINGLE_NODE_ROUTINE_DEFERRAL`) must be reworded around the anchor's *seat*, not hop
presence: what still defers is a routine write with no hop on the field *and* no payload data
field to carry one (void, scalar, OUT-parameter binding, non-carrier Objects). Its current "no
post-commit table to re-read the response from" turns false the moment a carrier return provides
one.

The fourth cell, `@routine` + `@reference` on the mutation field *with* a carrier return, is the
right fact asserted at the wrong grain: the path belongs to the data field whose rows it fetches,
not to the field that runs the routine. It rejects as a typed directive conflict (the
`Rejection.directiveConflict` shape the `@routine` + `@splitQuery` pair already uses, so the
diagnostic carries a stable directive pair rather than prose alone), with the message naming the
data field as the path's seat. Rejecting rather than admitting a second spelling is deliberate:
one fact stated at two grains needs an agreement check to keep the statements from drifting,
which is exactly the check the data-field placement exists to make unrepresentable, and it hands
schema reviewers two spellings they must know are equivalent. Rejection is also the
cheap-to-reverse choice: admitting the spelling later is additive, retiring an admitted spelling
breaks published schemas.

The old draft threaded a carrier-unwrap function through the three seats that read the mutation
field's return shape (`RoutineDirectiveResolver.resolve`, `walkRoutineChain`'s cardinality,
`routineChainVerdict`'s Connection fork), with its own quiet cardinality trap and
Connection-peel hazard. This decision dissolves all of it: the carrier path never walks a chain
off the mutation field, so those three seats keep their current contracts untouched, the
carrier's cardinality is the data field's own wrapper read at the data field's own seat, and the
one thing the carrier path still needs from the resolver is the routine node itself (name,
argument binding, result table), split out as a node-only resolution with the return-shape
demand left on the chain path where it belongs.

### D2: the data field's path is implicit, and derived once at grounding

The payload's data field is where the target's rows are rendered, so the path from the captured
routine result to the target table belongs to it conceptually: the path from the parent context
to this field's data, which is what a reference path means on every other field in the system.
On the old draft's mutation-field placement it meant "the path to a table my own return type is
not", which is the anomaly that forced every return-shape seat to unwrap the carrier first.

In scope, the path is implicit only: the directiveless data field means the single name-matched
hop from the FK-less routine result to the field's own element table, mirroring the DML
carrier's structural correlation (whose data field is also directiveless, correlating on the
input table's PK). The derivation is a pure catalog computation over two facts the grounding
walk already has, the routine's result table and the element table's primary key, so it runs
where the producer observation is built (`RecordBindingResolver`, inside the binding fold) and
has exactly one producer. The hop it computes is consumed at classify time into the hop-less
`OnLiftedSlots` correlation and the leaf's captured pairs; no `JoinStep` ever lands in the
model, so `ParentCorrelation.checkCarrierInvariant` stays satisfied with an empty `joinPath` and
the model acquires no hop-count axis.

The name-matching can fail, unlike the DML correlation it mirrors, and that failure needs its
own rejection at this seat rather than the shared one. `synthesizeNameMatchedJoin`'s existing
message ends "or join on an explicit predicate via a `condition:` element", a fix clause that is
false here: a condition join has no key tuple to capture, so following it lands the author in a
second rejection. The carrier classification states its own typed rejection, landed on the
mutation field, with the one fix that works (expose the target's key column from the routine,
with the candidate hint naming the routine's actual columns), mirrored in
`GraphitronSchemaValidator`. The unmatchable-PK failure is the one
the implicit form makes reachable from a directiveless field, so the classification tests pin
its wording on this seat.

The explicit form, `@reference` on the data field, is deliberately *not* in scope, at any hop
count, and the reason is the derivation seat just named: the captured pairs must exist at
grounding, which runs before field classification, and an explicit path would either need
`@reference` parsing inside the grounding fold (a second parse seat outside the chain walker,
with no `Rejection` seat to land in) or a re-derivation at the mutation leaf, which is the
two-derivations problem this placement exists to remove. The explicit declaration, single- and
multi-hop together, is one follow-up question (the read-side path declaration, scope boundary),
and a data field carrying `@reference` lands a typed `Deferred` naming it, produced by the
would-admit-but-for-the-directive probe pattern (`diagnoseForbiddenCarrierDirective` is the
house shape), so the author gets a pointed answer instead of the generic non-carrier
fallthrough.

What this placement dissolves, rather than solves: the old draft needed an agreement check
between the chain terminus and the data-field element table, with its own rejection and test,
because the same fact was declared in two places. Here the target is declared once, where the
data lives, and the implicit form has no second declaration to check against anything; the
standard terminus-backs-element coherence rule arrives with the explicit form, in the follow-up
that admits it.

### D3: a `CarrierFamily.ROUTINE` arm that earns its policy

The enum's contract, in its own javadoc, is that families differ on their two coupled policies:
the forbidden-directive set on the data field and the ID-element wrapper admission. With the
explicit path form out of scope (D2), the routine family keeps the strict DML forbidden set
(`@reference` on the data field routes to D2's pointed `Deferred` via the probe pattern rather
than admitting, and `@splitQuery` stays forbidden because the data field already is a
record-sourced re-fetch), so the arm's justification rests on the ID axis: the ID-element permit
exists for the DELETE PK echo, a routine write has no PK-echo shape at all, so `ROUTINE` rejects
`DmlElementKind.IdElement` outright rather than admitting it under wrapper sub-rules worded for
DELETE. That is a third value on the ID axis, not provenance wearing a policy's clothes. The
coupling worth recording: the day the follow-up admits the explicit form, the forbidden set
diverges from DML's too, and the family arm is already the seat that divergence lands in.

The alternative considered and rejected: reuse `CarrierFamily.DML` and put the ID refusal at the
classifier seat, the way `classifyUpdatePayloadField` words its own per-verb refusals. It works,
but it leaves a routine seat calling a method named `scanStructuralDmlPayload`, which is a lie
about the axis rather than a wart on it, and it leaves the follow-up's forbidden-set divergence
with no seat to land in short of minting the family arm then anyway.

### D4: the producer observation carries the key, and the third arm is where the axis wants reifying

The carrier's data field classifies through `FieldBuilder.classifyChildFieldOnResultType`, which
dispatches on a producer binding observed for the payload SDL type: `ProducerBinding.DmlEmitted`
(grounded by `RecordBindingResolver.groundDmlMutationField`) or `ProducerBinding.ServiceEmitted`.
A routine carrier needs its own observation, and `DmlEmitted` cannot carry it: every component fits
except `DmlKind`, and a routine write has no DML verb. Widening `DmlKind` with a routine pseudo-verb
would put a non-verb in the enum that `OperationMember.Write.Dml` and the per-verb emit switches
read.

But "it has no `DmlKind`" is a weak reason to mint an arm, and taken alone it multiplies five
parallel structures: a third memo map on `RecordBindingResolver`, a third `xEmittedBinding`
accessor, a third near-duplicate block in `classifyChildFieldOnResultType`, a third probe in
`TypeBuilder.carrierBinding`, and a third disjunct in `FieldBuilder.transportForParent`'s
`activeChannel` gate. No consumer forks on the arm's identity; every one reads the same two
or three accessors. Strip `DmlKind` and `DmlEmitted` and `ServiceEmitted` are the same
consumer-facing shape, differing only in provenance, which `describe()` and the multi-producer
rejection consume.

The fifth is not more of the same, and it is the reason the capability below is load-bearing rather
than tidy. The other four duplicate a shape; `activeChannel` is
`dmlEmittedBinding(...).isPresent() || serviceEmittedBinding(...).isPresent()`, and an unrecognized
parent does not fail there, it falls through to `ChildField.Transport.PayloadAccessor`. A
directiveless structural carrier has no developer payload class to read an accessor off, so the
routine carrier's `errors` field would bind the one transport that cannot work, quietly, while
`selectErrorsTransport` (which would have answered `Transport.LocalContext`) never runs. That is
D6's outcome (a) failing at the transport seat rather than at the channel, and it is not
compiler-checked: a boolean is silently false where a sealed switch would have demanded an arm.
Whatever shape the capability takes, it must expose "is an emitted-carrier binding bound to this
SDL type" as one question, so this gate stops being a hand-maintained disjunction over the arms.

The third arm is therefore the point at which the axis wants reifying rather than extending,
but only half of that reification belongs here. The two halves separate cleanly:

**Taken: the capability and its total accessors.** Introduce `EmittedCarrierBinding` over the three
emitted-carrier arms, exposing `tableRef()`, `arrival()`, a *total* `correlationColumns()`, and the
one presence probe the `activeChannel` gate needs. `correlationColumns()` answers the target
table's PK columns uniformly on every arm (a default method over `tableRef()`), because that is
the one meaning the read-side correlation consumes; without it,
`buildPayloadCarrierBatchedTableField` would have to fork on "does this binding carry key
columns, else compute `primaryKeyColumns()`", a fork on absence. The routine arm's distinct
fact, the name-matched pairs whose source side step 1 projects, travels as its own accessor on
that arm alone rather than overloading the shared one: the two-statements section's point that
the capture is two facts at two grains, and a shared accessor whose meaning depends on the
variant is the exact smell the axis guidance names. The pairs are also the honest one-line
answer to what the new arm carries that its siblings cannot, which is the justification the
model asks of a new sub-taxonomy; "it has no `DmlKind`" is not one.

**Declined: the consumer-side merge.** Folding the three memo maps on `RecordBindingResolver`, the
three `xEmittedBinding` accessors, and the three near-duplicate blocks in
`classifyChildFieldOnResultType` into one seam is a real consolidation, and this item makes it
tempting rather than necessary. The redesign was a reason to re-take this decision rather than
inherit it, because the cost side moved: the routine classify block carries no table-agreement
diagnostic of its own (its binding's table was read off the same data field the check would
compare it to, so the agreement is tautological at this seat), which makes the third block a
deliberate thin duplicate and the merge correspondingly cheaper. The decision stands anyway: the
two real diagnostics (DML's and `@service`'s) have fixture-pinned wording, unifying them is the
actual work, and taking it here would put the DML and `@service` emit paths, which this item
otherwise does not touch at all, inside its acceptance surface. Filed as
`roadmap/emitted-carrier-binding-consumer-consolidation.md`, which this item's shape makes
smaller, not larger.

Two of the five sites are not on that declined list, because this item cannot work without them:
`TypeBuilder.carrierBinding`'s probe (without it the payload never registers as a carrier) and the
`activeChannel` gate (without it the errors field binds the wrong transport). Both arrive
three-armed here; what defers is the diagnostic unification of the three classify blocks. The
follow-up item's inventory is worded to match, so it does not inherit a site this one already had to
touch.

So this item adds a plain `ProducerBinding.RoutineEmitted` arm implementing the new capability,
carrying the same compact-constructor class-identity invariant its siblings do
(`reflectedClass.getName().equals(tableRef.recordClass().reflectionName())`), so the per-SDL-type
binding fold still agrees with `RootTable` for the same table. Grounding needs no chain walk: a
Mutation field carrying `@routine` and no chain (the shape D1 routes to the carrier fork) whose
return scans as a carrier, with the table read straight off the scan's `DmlElementKind.Table`
element and the name-matched pairs computed right there, from the routine's result table and
that element table's PK (D2's single derivation site).

Reading the table off the scan means the grounding walk calls into `TypeBuilder` while the binding
fixed point is still forming, which looks like a layering violation and is not one. `TypeBuilder`'s
own `lookAheadVerdict` javadoc records the rule: during `prepareForWalk` the inputs are still
forming (it names the DML grounding probing the payload scan mid-fold as the existing instance), so
`prepareForWalk` clears the memo at its end and only post-fixed-point verdicts stick. The routine
arm is the second instance of a pattern already reasoned about, not a new hazard. Noted here so the
implementer does not spend the cycle re-deriving it, or "fix" it by threading the table in from the
field instead, which is possible but buys nothing: the data field's element type is where the table
actually lives.

### D5: a sibling leaf, and the ratchet

`MutationRoutineWriteField` cannot carry the carrier: its `returnType` is a
`ReturnTypeRef.TableBoundReturnType`, its `domainReturnType()` is `Record(table)`, its
`errorChannel()` is pinned empty, and its whole shape is a chain (hops non-empty by compact
constructor). The carrier return is a `ResultReturnType`, has no hops at all, delivers captured
key slots rather than projected terminus rows, and carries a channel.

Three facts co-vary, not one: the return-type component, the channel (never vs sometimes), and who
owns step 2 (the fetcher projecting the terminus, vs the data field doing a source=target
re-fetch). `MutationField.target()` already forks on the third, since the direct arm answers
`TargetShape.Table` and a carrier must answer `TargetShape.Record`. Folding all three into one leaf
behind an `Optional` channel would make `TypeFetcherGenerator` read `errorChannel().isPresent()` to
choose its emit topology, which is the generator branching on a predicate over pre-resolved data.

Land `MutationRoutineWriteRecordField` beside it, exactly as `MutationDmlRecordField` sits beside
`DmlTableField`. `LeafReconstructionKeyTest` makes this the principled answer rather than the
convenient one: it already separates those two DML leaves on the *target* term of
`leaf = f(source, delivery, target)` ("DML return expression" vs "payload record"), and the new
leaf's triple is ("routine call", "root", "payload record") against the sibling's
("routine chain", "root", "table (post-commit terminus)"). The two leaves differ on source grain
(a bare call vs a chain; "service call" is the existing precedent for the source term) and on
target grain (payload record vs post-commit terminus table), both axes the reconstruction key
names as surviving. Under the old draft the leaves shared their source term and the case rested
on target grain alone; the chain moving off the mutation field makes the separation
two-dimensional.

One observation belongs on the record here rather than discovered later: after this item the
mutation hierarchy carries the complete cross-product of write source (DML, routine) and return
shape (direct, carrier) as four leaf identifiers, while the `QueryField` side folded the same
source axis into a component (`QueryTableField`'s declared source term reads "tableExpr
component (catalog table | routine chain)"). The reconstruction key's formulation, keyed on the
leaf class with a per-leaf `target()`, is what forces the split here, so if a future slice
re-keys it, the fold starts from a complete square. That is an observation for whichever item
takes that pivot, not licence for this one to pre-fold.

This raises `LeafRatchetTest.MUTATION_FIELD_LEAVES` from 8 to 9, against a constant whose javadoc
says the pins move only downward. That javadoc is worth reading precisely: what it names as the
illegitimate rise is "a new *operation-encoding* leaf, which the dissolution programme exists to
make unnecessary: add a fact or a member row instead", and it names the surviving distinctions as
"source, delivery and target grain". This rise is the second kind. The operation is unchanged (the
same routine write, the same `OperationMember`), and what the new leaf encodes is source and
target grain, which the same sentence protects.

The sibling test settles the reading better than parsing the ratchet's own wording does.
`LeafReconstructionKeyTest`'s class javadoc contemplates exactly this move: a new leaf "fails here
until it declares its triple, which is the moment to ask whether the distinction it encodes is
source, delivery or target grain, or an operation term that belongs on a member row." That is the
programme describing a legitimate new leaf and naming the question to answer before adding one, in a
test whose stated purpose is enforcing the reconstruction key. The two tests are one programme, so a
rise that answers that question with surviving grain is the case the pair was written to admit, not
a loophole in one of them.

The alternative was weighed and is worse, for a reason that is structural rather than aesthetic. A
sealed return-shape fact on the existing leaf keeps the count at 8, but `LeafReconstructionKeyTest`
declares triples as a `Map<Class<?>, String>`, one per leaf class, and `MutationField.target()` is a
total switch with one arm per leaf class. Under the fold, `MutationRoutineWriteField` would have two
targets ("table (post-commit terminus)" and "payload record") and its `target()` arm would have to
switch on an inner fact to say which, and its hops-non-empty compact-constructor pin would have
to weaken to admit the hop-less carrier. The leaf would stop determining its own target, so
`leaf = f(source, delivery, target)` would stop holding as a function, which is the single-valued-
slot-for-a-multi-valued-relation fault that the dissolution programme spent eight slices removing.
Keeping a count low by making the reconstruction key untrue is the wrong trade against a test whose
stated purpose is to enforce that key.

So: take the rise, and the constant's history line records it in the same commit as a grain
addition (source and target both) with this reasoning, in the format its existing downward moves
use. A ratchet that can never rise for any reason is a count, not an invariant.

The class javadoc has to move with the constant, not just gain a history line. Its standing sentence
is the flat "**These pins move only downward**", and after this item that sentence is false as
written, whatever the qualifying sentences after it say. Reword it to the rule the rest of the
paragraph already implies: the pins move downward as dissolution slices land, and rise only for a
distinction the reconstruction key names as surviving grain, never for an operation-encoding leaf.
Leaving a false flat claim above a constant that just contradicted it is the version of this change
that rots, and it teaches the next reader that the ratchet is decorative.

### D6: the error channel, and what the pin's retirement means

`MutationRoutineWriteField.errorChannel()` stays pinned empty. The pin is not being converted into
a conditional; it stays true of the leaf it is written on, because that leaf keeps its direct
`@table` return. What must be rewritten is the *reason text*: it currently reads as though no
routine write can ever carry a channel, when the true statement is narrower, and after this item
the honest wording names the sibling ("this leaf is the direct-terminus shape, which has no payload
field to put errors in; the carrier shape is `MutationRoutineWriteRecordField`"). The terminus rule
it cites did not stop applying; it stopped being the only shape.

The new leaf's channel slot is `Optional<ErrorChannel.LocalContext>`, not the wider
`RouterDispatched`. A directiveless structural carrier has no developer payload class, so
`detectStructuralDmlErrorChannel` can only ever produce `LocalContext`; declaring the narrow type
puts that contract in the signature instead of in the producer's body. (`StructuralDmlErrorChannel.
Present` declares the wide type for the same reason and could be narrowed in passing.)

The two null-data-field outcomes are distinct and only one goes through the channel. **(a) The
routine raised.** The catch arm returns the non-null all-null-column sentinel so graphql-java
traverses into `errors` instead of short-circuiting on a null parent; the data field's null-key
SELECT returns no row and renders null; `errors` is populated. **(b) The routine succeeded and the
row is invisible.** This is the motivating RLS path and the consumer's *happy* path: the write
commits, step 1 captures a real key inside the transaction, and the post-commit SELECT returns no
row because the read policy hides it. Data field null, `errors` **empty**, no sentinel involved, no
field error. Pinning these as one claim would leave (b) unspecified, and (b) is the one the
consumer schema exercises every time.

### D7: the carrier's data field must be nullable

Outcome (b) promotes the zero-row re-read from an edge case to a first-class success path, and that
makes the data field's nullability load-bearing. An author who writes `feideBruker: FeideBruker!`
gets graphql-java's non-null propagation nulling the whole payload on a legitimate zero-row read,
destroying the errors list that the response's entire informational content lives in. That is the
exact failure the `LocalContext` sentinel exists to prevent, arriving through the success path where
no sentinel is in play.

So a non-null data field on a routine carrier is an `AuthorError` at classify time, with the message
naming the zero-row reason, mirrored in `GraphitronSchemaValidator` per the validator-mirrors-
classifier rule. Whether the DML family should acquire the same rule is a separate question and not
this item's to answer: its `RETURNING` always yields a row, so the failure has never been live there.

## Scope boundary

**In scope:** a hop-less `@routine` Mutation field returning a carrier whose data field is
directiveless (the implicit single name-matched hop, D2), with and without an errors-shaped
field, at both data-field cardinalities.

Both cardinalities, and no constraint tying the data field's wrapper to the routine's own result
shape, because there is no fact to constrain against. jOOQ generates every table-valued function as
a `Table<R>`, so "set-returning" is the kind, not a cardinality statement about any particular call;
a `RETURNS TABLE` function yielding one row is indistinguishable in the catalog from one yielding
many. The data field's SDL wrapper is therefore the only cardinality claim in the system, read at
the data field's own seat, exactly as the mutation field's wrapper is on the direct-return path,
which already admits both. A single-cardinality data field means step 1 emits `fetchOne()`, and the
no-row case is already handled by the existing null-keys guard. The question of whether an author
*should* declare a single wrapper over a many-row routine is a schema-review question, not one the
model can answer.

**Out of scope, landing as typed `Deferred`s pointing at the follow-up item
`roadmap/routine-carrier-explicit-data-field-path.md`:** the explicit data-field path
declaration, single- and multi-hop alike (D2). Of the two questions this item leaves closed
together, one is already answered by the adopted fact-base architecture rather than open: the
path exists as `graphitron_field_reference_step` rows from capture, phase-independent, so
"where does an explicit path parse" dissolves into derivation views over that relation, and the
follow-up is framed fact-base-first and sequenced with the strangler slice that migrates this
classification neighbourhood (`roadmap/validation-adds-facts.md`). The genuinely open question
is the read-side correlation arm: `ParentCorrelation.checkCarrierInvariant` pairs a non-empty
`joinPath` only with a hop-anchored correlation while the carrier data field's correlation is
the hop-less `OnLiftedSlots` over the captured slots, so a residual path needs an arm that
anchors on the captured record and walks onward from it, post-commit, as an ordinary read.

The semantics of that walk are recorded here as decided, not open, because they follow from the
two-statements rule. Residual hops run at read time under the caller's identity, so read
policies apply to them, and under RLS a multi-hop data field can legitimately resolve null with
empty errors, which is outcome (b) at every hop count. The in-transaction alternative (capturing
the terminus key across the residual hops before commit) was weighed and rejected on honest
grounds: it does not escape RLS either, because in-transaction joins also run under the caller's
identity. All it buys is insulation from visibility that changes *at* commit (a trigger granting
the reading role mid-write, a deferred constraint), and its price is joins inside the write
transaction, which the two-statements rule forbids, plus a second transaction topology on a
carrier emit surface that DML and `@service` carriers keep at one. If the follow-up needs to
reopen this, the two-statements rule is what has to be argued down, not the deferral's wording.

The direct-`@table` chain shape, single- and multi-hop, keeps working exactly as it does today
(D1); only the carrier's explicit path declaration defers.

Also out of scope: `@routine` carriers on `Query` (no write, no channel motivation), and the
record-element and ID-element data-field shapes (the first rejected at the seat, the second by
the `ROUTINE` carrier family per D3).

## Implementation

* `BuildContext`: the `CarrierFamily.ROUTINE` arm plus its two policy-site cases (the strict DML
  forbidden set, the outright ID-element refusal, per D3), and the
  `scanStructuralRoutineCarrierPayload` entry point beside its two siblings.
* `FieldBuilder.classifyMutationField`'s `@routine` branch (D1): run the carrier scan before
  landing the single-node deferral; the routing gate (`isMutationWriteChain`'s more-than-one-
  directive test) already sends the hop-less shape here, so it is the branch body that changes,
  not the routing. `Admit` with a `Table`-element data field classifies the new shape; an
  `Admit` whose name-match derivation failed lands D2's typed rejection at the field; everything
  else keeps the deferral. `MUTATION_SINGLE_NODE_ROUTINE_DEFERRAL` is reworded around the
  anchor's seat (no hop on the field and no payload data field to carry one), naming the shapes
  still in it (void, scalar, OUT-parameter binding, non-carrier Objects); its current "no
  post-commit table to re-read the response from" is false once a carrier return provides one.
* The fourth-cell rejection (D1): on the chain path (`@routine` + `@reference`), where the
  return-shape derivation lands `@routine requires a @table-annotated return type` today, probe
  the carrier scan and reject an `Admit` as a typed `Rejection.directiveConflict` over the pair
  (the `@routine` + `@splitQuery` precedent), with the message naming the data field as the
  path's seat.
* `RoutineDirectiveResolver`: split the node-only resolution (name, argument binding, result
  table) from the return-shape derivation so the carrier seat can resolve the call without a
  table-bound return. The chain path keeps `resolve` as is; behaviour-identical there.
* The pair derivation (D2): one pure function over the routine's result table and the data-field
  element table's PK, producing the name-matched pairs or its typed failure.
  `RecordBindingResolver`'s grounding consumes it to fill the `RoutineEmitted` binding;
  `classifyMutationField` surfaces the typed failure as the field's rejection. No other caller.
* The data-field classification (D2), in `classifyChildFieldOnResultType`'s routine block: reads
  the binding (`correlationColumns()` for the hop-less `OnLiftedSlots` via
  `buildPayloadCarrierBatchedTableField`), never re-derives the keying. A data field carrying
  `@reference` lands the pointed typed `Deferred` via the would-admit probe pattern (scope
  boundary).
* `MutationField`: the new `MutationRoutineWriteRecordField` leaf, carrying the
  `ResultReturnType`, the routine call (routine ref plus result table), the captured pairs
  (routine result column paired with target key column), the target table, and
  `Optional<ErrorChannel.LocalContext>`. Compact-constructor pins: pairs non-empty, keying
  name-matched, and the pairs' target side equals the target table's PK columns (the
  two-statements rule as a type invariant). No chain, no hops, no hop-count component (scope
  boundary).
* The emitted-carrier producer capability (D4) or the `RoutineEmitted` fallback arm, plus its
  `RecordBindingResolver` grounding, its `TypeBuilder.carrierBinding` recognition, and the
  `classifyChildFieldOnResultType` branch. `buildPayloadCarrierBatchedTableField` reads the
  capability's total `correlationColumns()` instead of recomputing `primaryKeyColumns()` per
  family.
* `FieldBuilder.transportForParent`: the `activeChannel` gate admits the routine carrier, so its
  `errors` field reaches `selectErrorsTransport` and binds `Transport.LocalContext` instead of
  falling through to `PayloadAccessor` (D4). Silent if missed, and it is what makes outcome (a)
  reach the client at all, so it carries its own classification test rather than riding the
  data field's.
* `TypeFetcherGenerator`: a step-1-only fetcher for the new leaf. It is
  `buildMutationRoutineWriteFetcher` truncated at the transaction boundary, returning the captured
  keys, with `catchArm` given the `singleRecordSentinelFor` sentinel the DML carrier passes. The
  existing two-step fetcher stays for the direct-return leaf. Step 1 projects the captured tuple
  under the *target table's* key fields, not the routine result's same-named columns: the data
  field reads its correlation off that record by column, and the DML path it mirrors projects
  `Tables.<TARGET>.<PK>` directly, so matching that keeps the carried-key component and its reader
  agreeing by field identity rather than by jOOQ's name-lookup fallback.
* `GraphitronSchemaValidator`: an arm for the new leaf mirroring the classifier's pins, including
  D7.
* The operation-member declarations, all landing `Write.RoutineWrite()` exactly as the sibling leaf
  does (D5's "the same `OperationMember`"): an `OperationMembers.DECLARED_SHAPES` entry, a
  `membersOf` arm, and an `OperationMemberRelation.writePayloadOf` arm. The first two are
  compiler- or test-enforced; `writePayloadOf` ends in `default -> throw`, so a missing arm is a
  generation-time throw rather than a build failure at the edit site.
* `CatalogBuilder`'s `FieldClassification` arm for the new leaf. The sibling projects
  `RoutineBacked` (hover and jump-to-source route to the routine's call surface); the carrier leaf
  wants the same, since the routine is still what backs it.
* `FetcherEdgeCommands`, `LeafReconstructionKeyTest`'s triple map, `LeafRatchetTest`'s constant plus
  its history line *and* the rewording of its class javadoc's downward-only sentence (D5), and the
  generated `docs/manual/_generated/supported-schema-shapes.adoc` (regenerated by the roadmap tool,
  not hand-edited).

## Tests

* **Classification** (`GraphitronSchemaBuilderTest` routine block): the directiveless carrier
  admitted with and without an errors field; the fourth cell (`@routine` + `@reference` on the
  mutation field with a carrier return) rejected as the typed directive conflict naming the data
  field as the path's seat, not the generic not-table-bound rejection; a routine result that
  does not expose the target's PK column by name rejected with the carrier seat's own wording
  (candidate hint present, no `condition:` fix clause, per D2); a data field carrying
  `@reference` landing the pointed typed `Deferred` that names the follow-up's question;
  record-element and ID-element data fields rejected with routine wording; a non-null data
  field rejected per D7; a `[Film!]` data field driving list cardinality on the leaf (read at
  the data field's seat); hop-less non-carrier shapes still landing the narrowed single-node
  deferral; direct-return chain shapes unchanged. Plus the transport pin: the carrier's `errors`
  field classifies as `ChildField.ErrorsField` with `Transport.LocalContext`, which is the
  assertion that fails if the `activeChannel` gate was not widened, and fails at classify time
  rather than three tiers later in execution.
* **Pipeline** (`RoutineMutationWritePipelineTest`): the carrier fetcher emits exactly one
  `transactionResult(...)` and *no* follow-up `.select(` after it, the mirror of the existing
  two-step pin. Same fingerprint style, no source-text matching. Plus the outcome-(a) shape claim:
  the catch arm carries a sentinel.
* **Execution, outcome (a)** (`graphitron-sakila-example`): the DB already has what this needs.
  `public.rent_film` is a `VOLATILE` `RETURNS TABLE` function whose bad-`inventory_id` path trips
  the same PostgreSQL FK violation `createFilmWithErrors` uses, and the example schema already
  carries an `@error` type and union plus `FilmCreateLocalContextPayload` as the `LocalContext`
  precedent. Add a `RentFilmPayload` carrier over `Rental` and round-trip the happy path (data field
  populated, errors empty) and the error path (data field null, typed error in `errors`).
* **Execution, outcome (b)**: a real RLS policy, not a predicate-filter stand-in. The infrastructure
  this looked like it needed mostly exists: `SessionHookExecutionTest` already stands up a
  non-superuser role, a table with `enable`/`force row level security`, and a fail-closed policy
  keyed on `current_setting('app.user_id', true)`, and it already asserts the neighbouring claim
  that a mutation's post-commit read-back sees only permitted rows. Its comment header also records
  the trap worth inheriting: the pooled test DataSource connects as `postgres`, and superusers
  bypass RLS outright, so the probe connection must be opened as the dedicated role.

  Two things are genuinely new. The probe table there is created in `@BeforeAll`, which is after
  jOOQ codegen, so it is invisible to the catalog; this item's table and its `VOLATILE` routine must
  live in `graphitron-sakila-db`'s `init.sql` instead, so `@routine` and `@table` can resolve them.
  And that test drives the emitted hook over raw JDBC, whereas this one must run the *generated
  fetcher* over a probe-role connection (`TenantDivinedRoutingExecutionTest` is the precedent for
  per-test connection config).

  The fixture that reproduces the motivating schema rather than approximating it: a `SECURITY
  DEFINER` routine, so the write runs as owner and succeeds, inserting a row whose owner column does
  not match the caller's mounted identity, under a policy that hides it. The caller then commits a
  real row it cannot read, which is the consumer's situation exactly (read access requires a role
  assignment the fresh row has not got yet). A predicate-filter substitute would exercise the same
  code path while pinning a different claim, and outcome (b) is the claim this item exists to make.
* The `@classified` corpus entry for the new coordinate, per the classified-corpus loop.

## User documentation (first-client check)

`docs/manual/reference/directives/routine.adoc`, the "Writes on Mutation" section, gains the
carrier as a second admitted return shape beside the direct `@table` return, headlined by D1's
one-sentence rule: with `@reference`, the field returns the table the chain reaches; without it,
the field returns a payload whose data field declares its own path. The worked payload example
follows, with one sentence on what the two statements become (the routine call and the key
capture commit; the data field's SELECT is the post-commit re-read). Its "Constraints" list
narrows the flat "the field's return type must be `@table`-bound" claim to the `@reference`-
present shape, and gains the explicit data-field path deferral.

`docs/manual/how-to/error-channel.adoc` needs a correction this item makes unavoidable: it says
`@error` "only takes effect when the type appears as (or in a union behind) an `errors:` field on
a payload reachable from a `@service` field", and its See-also repeats that `@service` is "the
upstream of the errors channel: only service-returned payloads carry one". That has been untrue
since DML carriers started binding `ErrorChannel.LocalContext`; the routine carrier makes it a
third counterexample. Reword to name the three producer families.

If the reference page cannot state the carrier rule in D1's single sentence, without reaching
for the word "terminus" at all, the design is wrong and the shape should change before
implementation. (The old draft's placement could not pass this check; the current one states the
rule with no chain vocabulary because the carrier shape has no chain.)

## Where this plan is most likely to be wrong

Not questions to answer before implementation; the decisions above are taken. These are the load
points a fresh reader should test the reasoning at, because if the plan fails it fails here.

* **D1's fourth-cell rejection.** The return shape classifies once and the path's seat derives
  from it, which hard-rejects the mutation-field spelling on a carrier. If a real schema turns
  up a `@reference` + carrier combination the data-field seat cannot express, the rejection was
  the wrong call; the design's own defense is that admitting the spelling later is additive
  while retiring it is not.
* **The implicit-only scope.** D2 ships no explicit path spelling at all, on the derivation-seat
  argument. The motivating consumer schema needs none, but if real schemas need explicit paths
  soon, authors sit on a typed `Deferred` until the follow-up lands, and the scope was cut too
  tight. The deferral is pointed at the follow-up precisely so that gap is visible rather than
  mysterious.
* **D5's ratchet rise (8 to 9).** The argument is that the rise is source and target grain, which
  the constant's own javadoc protects, rather than operation encoding, which it forbids, and that
  the count-preserving alternative would make `leaf = f(source, delivery, target)` untrue as a
  function. If that reading of the javadoc is wrong, the rest of D5 falls with it and the fold
  becomes the answer despite its cost.
* **The captured pairs.** The old draft's version of this risk (two independent derivations
  silently disagreeing) is gone, and the leaf's compact constructor now pins the target side to
  the target table's PK. What no pin can check is the source side: the derivation asserts that a
  routine result column with the PK's SQL name identifies the written row, which is a naming
  convention about the routine, not a catalog fact. A routine that returns a same-named column
  with different semantics captures a wrong key silently. That risk is inherent to name-matched
  keying and shipped already on the direct path; it is recorded here because the carrier makes
  the implicit form the default authoring shape.
* **D7's nullability rule.** It is a new authoring restriction justified by a runtime consequence
  (non-null propagation destroying the errors list). If a reviewer can show a shape where a non-null
  data field is both safe and useful, the rule is over-broad and should become a warning.
* **The residual-hop trade.** The scope boundary records post-commit residual hops as the
  decided semantics, on the argument that in-transaction capture buys only insulation from
  visibility that changes at commit. If a real consumer depends on exactly that (a trigger
  granting the reading role as part of the write), the post-commit walk resolves null where the
  in-transaction capture would not, the trade was wrong, and it is the two-statements rule
  itself that has to be argued down, which this spec has made deliberately hard.

Adjacent, deliberately not folded in: the routine-kind axis (procedures, scalar and void
routines: the shapes left in the narrowed single-node deferral) is
`roadmap/routine-write-result-shapes.md`. That item asks which routine kinds can back a write;
this one asks what the field may return once one does, and this one rewords the deferral text
both items share (D1), which that item's author inherits. They meet if a consumer's routine
turns out to be void or scalar, since a void routine plus a payload carrier has no data field to
fill, but the return-shape work stands alone over the table-valued kind already shipped.

