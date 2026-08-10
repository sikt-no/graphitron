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

A `@routine` write on `Mutation` can only return the terminus `@table` type directly. Every
other return shape is rejected by `RoutineDirectiveResolver.resolve` with `@routine requires a
@table-annotated return type`, which fires before catalog resolution and therefore before any
chain-level verdict. The rejection is correct for the shape the resolver models (the routine
node's result table must resolve against a table-bound element type), but it also blocks the
return shape most authors reach for on a fallible write: the payload carrier.

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
    @reference(path: [{table: "feide_bruker"}])
}
```

Note what the RLS setting does to the value of each half: the caller cannot read the row it just
created (the read policy requires a role assignment the new row does not yet have), so the
post-commit re-read legitimately returns nothing and the data field is always null. The entire
informational content of the response is the errors list. A shape where the errors channel is the
point, and the data field is a structural placeholder, is exactly the one the current pinning
refuses.

## The convergence this rests on

The routine write and the DML carrier already emit the same two statements; they differ only in
where the split falls. `buildSingleRecordTwoStepFetcher` (the DML carrier) emits step 1 alone: a
PK-only `RETURNING` inside `dsl.transactionResult(...)`, returned as the fetcher's source. The
response SELECT is step 2, and it lives in the carrier's data field, classified by
`buildPayloadCarrierBatchedTableField` as a record-sourced `ChildField.BatchedTableField` whose
`ParentCorrelation.OnLiftedSlots` correlates the source record's PK back to the catalog rows.
`buildMutationRoutineWriteFetcher` emits both steps in one method body: step 1 captures hop 0's
key columns off the routine result inside the same transaction boundary, step 2 anchors on hop 0's
table and projects the terminus.

For a single-hop chain the two key tuples are the same tuple. A `{table:}` hop out of a routine
result has no FK metadata to ride, so `BuildContext.synthesizeNameMatchedJoin` keys it by matching
the *target table's primary key* columns by SQL name against the routine's result columns. The
target side of hop 0 is therefore the terminus table's PK by construction, which is exactly what
`OnLiftedSlots` wants. A `condition:` hop 0 is already a typed `Deferred` on this path (no
derivable re-read anchor), and a `{key:}` element out of an FK-less routine result does not
resolve, so every hop-0 shape that reaches `MutationRoutineWriteField` today is name-matched PK.

So admitting the carrier is not a new emit story. It is moving the existing step 2 out of the
mutation fetcher and into the data field that the DML family already routes it through.

**That agreement must become a component, not stay an argument.** As sketched above it is two
derivations that happen to coincide: `buildPayloadCarrierBatchedTableField` computes
`targetTable.primaryKeyColumns()`, the fetcher's step 1 computes `hop0Pairs.slots().sourceSide()`,
and nothing in the model binds them. `MutationRoutineWriteField`'s compact constructor pins
`On.ColumnPairs`, not `On.Keying.NameMatchedKey`, so the paragraph above is a fact with no
enforcer, and it is the load-bearing fact of the whole design. Carry the captured key tuple once,
on the producer observation the data-field seat already reads (D3), and have both the step-1
emitter and the `ParentCorrelation.OnLiftedSlots` construction read that slot. The name-matched
keying then becomes an implementation detail of how the classifier filled the slot rather than a
premise two emit sites independently rely on.

## Design

### D1: a `CarrierFamily.ROUTINE` arm that earns its policy

The enum's contract, in its own javadoc, is that families differ on their two coupled policies:
the forbidden-directive set on the data field and the ID-element wrapper admission. A routine
carrier shares the first (the strict DML set: `@reference` on the data field must stay forbidden
because the chain lives on the mutation field, and `@splitQuery` must stay forbidden because the
data field is already a record-sourced re-fetch). It differs on the second, and that difference is
the arm's justification: the ID-element permit exists for the DELETE PK echo, a routine write has
no PK-echo shape at all, so `ROUTINE` rejects `DmlElementKind.IdElement` outright rather than
admitting it under wrapper sub-rules worded for DELETE. That is a third value on the ID axis, not
provenance wearing a policy's clothes.

The alternative considered and rejected: reuse `CarrierFamily.DML` and put the ID refusal at the
classifier seat, the way `classifyUpdatePayloadField` words its own per-verb refusals. It works,
but it leaves a routine seat calling a method named `scanStructuralDmlPayload`, which is a lie
about the axis rather than a wart on it. If the reviewer prefers that route, the honest version of
it renames the family and its entry points to something policy-shaped, which is the larger diff of
the two.

### D2: the seat computes the terminus shape once, wrapper included

Three sites read the mutation field's own return shape today, and all three are wrong under a
carrier: `RoutineDirectiveResolver.resolve` (which derives `returnType` from
`baseTypeName(fieldDef)` plus `buildWrapper(fieldDef)` and produces the `TableBoundReturnType` the
chain hangs off), `walkRoutineChain`'s `isList` (which feeds cardinality into `parseChainSegment`),
and `routineChainVerdict`'s Connection fork (which reads `returnType.wrapper()`).

Unwrapping only the *type name* at the seat is the trap: the carrier's own wrapper is always
`Single`, so a `[Film!]` data field would silently flip `parseChainSegment`'s self-referential-FK
direction and give the Connection fork the wrong field to look at. Both failures are quiet.

So the seat computes one `ReturnTypeRef.TableBoundReturnType` (element type and wrapper together)
through a single carrier-unwrap-or-identity function and threads it into `walkRoutineChain` and
`resolve` as a parameter. The shape invariant moves with it: the unwrap function is what returns
either a table-bound shape or the `@routine requires a @table-annotated return type` rejection, so
the diagnostic stays single-sourced while the resolver's own check becomes a precondition on an
argument. The resolver then knows nothing about position *or* return shape, which is the narrower
contract, and the read seats (query root, child) call the same function and get the identity
result, behaviour-identical.

### D3: the producer observation carries the key, and the third arm is where the axis wants reifying

The carrier's data field classifies through `FieldBuilder.classifyChildFieldOnResultType`, which
dispatches on a producer binding observed for the payload SDL type: `ProducerBinding.DmlEmitted`
(grounded by `RecordBindingResolver.groundDmlMutationField`) or `ProducerBinding.ServiceEmitted`.
A routine carrier needs its own observation, and `DmlEmitted` cannot carry it: every component fits
except `DmlKind`, and a routine write has no DML verb. Widening `DmlKind` with a routine pseudo-verb
would put a non-verb in the enum that `OperationMember.Write.Dml` and the per-verb emit switches
read.

But "it has no `DmlKind`" is a weak reason to mint an arm, and taken alone it multiplies four
parallel structures: a third memo map on `RecordBindingResolver`, a third `xEmittedBinding`
accessor, a third near-duplicate block in `classifyChildFieldOnResultType`, and a third probe in
`TypeBuilder.carrierBinding`. No consumer forks on the arm's identity; every one reads the same two
or three accessors. Strip `DmlKind` and `DmlEmitted` and `ServiceEmitted` are the same
consumer-facing shape, differing only in provenance, which `describe()` and the multi-producer
rejection consume.

The third instance is therefore the point at which the axis wants reifying rather than extending.
Introduce a capability over the emitted-carrier arms exposing `tableRef()`, `arrival()`, and the
captured key columns from the convergence section, with one `emittedCarrierBinding(sdlTypeName)`
accessor and one classify-time block; provenance stays per-arm for diagnostics. The key columns are
what makes this more than tidying: they are the slot that turns the captured-key agreement into an
enforced component, and they are also the honest one-line answer to "what does this arm carry that
its siblings cannot".

The cost is that the capability widens the diff into the DML and `@service` paths, which this item
otherwise does not touch. The fallback, if the reviewer wants the narrower blast radius, is a plain
`ProducerBinding.RoutineEmitted` sibling arm justified by the key columns rather than by the absent
`DmlKind`, with the capability filed as a follow-up. Either way the arm carries the same components
and the same compact-constructor class-identity invariant
(`reflectedClass.getName().equals(tableRef.recordClass().reflectionName())`), so the per-SDL-type
binding fold still agrees with `RootTable` for the same table. Grounding needs no chain walk: a
Mutation field carrying `@routine` whose return is a non-`@table` Object, with the table read
straight off the carrier scan's `DmlElementKind.Table`.

### D4: a sibling leaf, and the ratchet

`MutationRoutineWriteField` cannot carry the carrier: its `returnType` is a
`ReturnTypeRef.TableBoundReturnType`, its `domainReturnType()` is `Record(table)`, and its
`errorChannel()` is pinned empty. The carrier return is a `ResultReturnType`, delivers captured
keys rather than projected terminus rows, and carries a channel.

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
leaf's triple is ("routine chain", "root", "payload record"). That is a target-grain distinction,
which the reconstruction key names as a surviving axis.

This raises `LeafRatchetTest.MUTATION_FIELD_LEAVES` from 8 to 9, and that constant's javadoc says
the pins move only downward, with a rise being "a new operation-encoding leaf". This one is not:
the operation is unchanged (the same routine write), and the new leaf encodes target grain. The
rise is legitimate under the ratchet's own stated rule, and the constant's history line must say so
in the same commit rather than just bumping the number. Flagged for the reviewer as the single most
contestable decision in this plan; if the reviewer reads the ratchet as a harder floor, the
fallback is a sealed return-shape fact on the existing leaf, at the cost of a fork inside it.

### D5: the error channel, and what the pin's retirement means

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

### D6: the carrier's data field must be nullable

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

**In scope:** a single-`@reference`-hop routine write chain returning a carrier, with and without
an errors-shaped field, at both data-field cardinalities.

**Out of scope, landing as a typed `Deferred` pointing at a follow-up item:** the multi-hop carrier.
The deferral is stated over the *emitter*, not over the leaf's shape, and the leaf carries no hop
count. What is missing is not a model shape but a capture: step 1 would have to capture the
*terminus* key across the residual hops inside the write transaction, and that emit does not exist
yet. Frame it that way and the model does not acquire a hop-count axis it would later have to shed:
the day step 1 captures the terminus key pre-commit, the data field's correlation is unchanged, still
the hop-less `OnLiftedSlots` over the carried key tuple, and multi-hop lands with no unpicking.

Framing it the other way (deferring because `ParentCorrelation.checkCarrierInvariant` pairs a
non-empty `joinPath` only with a hop-anchored correlation) would encode hop count into the carrier
and describe a residual-`joinPath` design that this item's own key-capture story argues against.
Note too that the in-transaction capture is what makes the RLS case work at *every* hop count, so it
is an argument for the general shape rather than a concession in the single-hop one.

The direct-`@table` multi-hop shape keeps working exactly as it does today; only the carrier
combination defers.

Also out of scope: `@routine` carriers on `Query` (no write, no channel motivation), and the
record-element and ID-element data-field shapes (the first rejected at the seat, the second by the
`ROUTINE` carrier family per D1).

## Implementation

* `BuildContext`: the `CarrierFamily.ROUTINE` arm plus its two policy-site cases, and the
  `scanStructuralRoutineCarrierPayload` entry point beside its two siblings.
* The carrier-unwrap-or-identity function (D2): takes the mutation field, returns either a
  `ReturnTypeRef.TableBoundReturnType` or the not-table-bound rejection. Sole home of that
  diagnostic afterwards.
* `RoutineDirectiveResolver.resolve` and `FieldBuilder.walkRoutineChain`: take the resolved
  terminus shape as a parameter instead of deriving it from the field. Read seats pass the identity
  result; behaviour-identical.
* `FieldBuilder.classifyMutationRoutineChain`: run the carrier scan first. On `NotApplicable`, the
  existing direct-return path, unchanged. On `Admit` with a `Table` element, walk the chain against
  the data field's shape, apply `routineChainVerdict` and the hop-0 re-read-anchor verdict as today,
  check D6's nullability rule, then land the new leaf with the channel from
  `detectStructuralDmlErrorChannel`. On `Admit` with a record element, and on `Reject`, a
  routine-worded rejection.
* `MutationField`: the new `MutationRoutineWriteRecordField` leaf, carrying the `ResultReturnType`,
  the `RoutineChain`, the captured key tuple, and `Optional<ErrorChannel.LocalContext>`, with the
  hops-non-empty and `On.ColumnPairs` compact-constructor pins the sibling leaf already has. No
  hop-count component (scope boundary).
* The emitted-carrier producer capability (D3) or the `RoutineEmitted` fallback arm, plus its
  `RecordBindingResolver` grounding, its `TypeBuilder.carrierBinding` recognition, and the
  `classifyChildFieldOnResultType` branch. `buildPayloadCarrierBatchedTableField` gains the carried
  key tuple as its correlation source instead of recomputing `primaryKeyColumns()`.
* `TypeFetcherGenerator`: a step-1-only fetcher for the new leaf. It is
  `buildMutationRoutineWriteFetcher` truncated at the transaction boundary, returning the captured
  keys, with `catchArm` given the `singleRecordSentinelFor` sentinel the DML carrier passes. The
  existing two-step fetcher stays for the direct-return leaf.
* `GraphitronSchemaValidator`: an arm for the new leaf mirroring the classifier's pins, including
  D6.
* `FetcherEdgeCommands`, `LeafReconstructionKeyTest`'s triple map, `LeafRatchetTest`'s constant plus
  its history line, and the generated `docs/manual/_generated/supported-schema-shapes.adoc`
  (regenerated by the roadmap tool, not hand-edited).

## Tests

* **Classification** (`GraphitronSchemaBuilderTest` routine block): carrier admitted with and
  without an errors field; record-element and ID-element data fields rejected with routine wording;
  a non-null data field rejected per D6; multi-hop carrier lands the typed `Deferred`; a carrier
  whose data-field element table disagrees with the chain terminus rejects on the terminus rule; a
  `[Film!]` data field drives list cardinality (the D2 hazard, which would pass silently as
  `Single` under a type-name-only unwrap); direct-return shapes unchanged.
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
* **Execution, outcome (b)**: only assertable against a real policy, so it needs fixture surface
  that does not exist yet: a table with an RLS policy that hides freshly-written rows from their
  writer, plus a `VOLATILE` routine writing into it, in `graphitron-sakila-db`'s `init.sql`. Named
  here rather than discovered during implementation, because it is the one piece of this item with
  genuinely new infrastructure cost. If the reviewer judges it disproportionate, the honest
  fallback is a routine that commits a row the follow-up SELECT filters out by an ordinary
  predicate, which exercises the same code path with a weaker story about why the row is invisible.
* The `@classified` corpus entry for the new coordinate, per the classified-corpus loop.

## User documentation (first-client check)

`docs/manual/reference/directives/routine.adoc`, the "Writes on Mutation" section, gains the
carrier as a second admitted return shape beside the direct `@table` return, with the worked
payload example and one sentence on what the two statements become (the routine call and the
key capture commit; the data field's SELECT is the post-commit re-read). Its "Constraints" list
loses the flat "the field's return type must be `@table`-bound" claim, which is what the current
rejection message asserts, and gains the multi-hop carrier deferral.

`docs/manual/how-to/error-channel.adoc` needs a correction this item makes unavoidable: it says
`@error` "only takes effect when the type appears as (or in a union behind) an `errors:` field on
a payload reachable from a `@service` field", and its See-also repeats that `@service` is "the
upstream of the errors channel: only service-returned payloads carry one". That has been untrue
since DML carriers started binding `ErrorChannel.LocalContext`; the routine carrier makes it a
third counterexample. Reword to name the three producer families.

If the reference page cannot state the carrier rule in a sentence without reaching for the word
"terminus", the design is wrong and the shape should change before implementation.

## Open questions for the reviewer

* D4's ratchet rise is the one to push on. Is a target-grain leaf a legitimate rise under a
  constant whose javadoc says the pins move only downward, or does the fallback (a sealed
  return-shape fact on the existing leaf) better serve the dissolution programme? The
  reconstruction key and `target()` both say leaf; the ratchet's prose says think twice.
* D3's blast radius: reify the emitted-carrier capability now, touching the DML and `@service`
  paths this item otherwise leaves alone, or land the narrower `RoutineEmitted` arm and file the
  capability as a follow-up? The third instance is the natural moment, but "natural moment" is not
  the same as "this item's job".
* The outcome-(b) execution fixture's infrastructure cost (new RLS surface in `init.sql`) against
  the weaker predicate-filter substitute.
* Should the data field's cardinality be constrained against the routine's own result shape? A
  set-returning function backing a single-cardinality data field is representable (step 1 would
  `fetchOne`), and the direct-return path already admits both, so the plan admits both here too.
  If that is wrong, it is wrong on both paths and the constraint belongs upstream of this item.

Adjacent, deliberately not folded in: the routine-kind axis (procedures, scalar and void
routines, and the single-node Mutation `@routine` with no `@reference` hop) is
`roadmap/routine-write-result-shapes.md`. That item asks which routine kinds can back a write;
this one asks what the field may return once one does. They meet if a consumer's routine turns
out to be void or scalar, since a void routine plus a payload carrier has no data field to fill,
but the return-shape work stands alone over the table-valued kind already shipped.

