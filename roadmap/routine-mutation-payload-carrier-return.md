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

The function inherits the Connection peel the resolver does today (`isConnectionType` then
`connectionElementTypeName`, ahead of `resolveReturnType`), so "identity" means identity over the
*carrier* axis, not a shorter computation. Dropping the peel would not fail loudly in the obvious
place: a Connection-returning routine field would stop resolving table-bound and take the
not-table-bound rejection instead of reaching `routineChainVerdict`'s Connection fork, silently
replacing a pinned diagnostic (`a routine-terminus chain does not support Connection return types`)
with a misleading one. That the pin exists is why this is a note rather than a risk.

### D3: the producer observation carries the key, and the third arm is where the axis wants reifying

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
D5's outcome (a) failing at the transport seat rather than at the channel, and it is not
compiler-checked: a boolean is silently false where a sealed switch would have demanded an arm.
Whatever shape the capability takes, it must expose "is an emitted-carrier binding bound to this
SDL type" as one question, so this gate stops being a hand-maintained disjunction over the arms.

The third arm is therefore the point at which the axis wants reifying rather than extending,
but only half of that reification belongs here. The two halves separate cleanly:

**Taken: the capability and its total accessors.** Introduce `EmittedCarrierBinding` over the three
emitted-carrier arms, exposing `tableRef()`, `arrival()`, the captured key columns from the
convergence section, and the one presence probe the `activeChannel` gate needs. This is not tidying,
it is what makes this item's own invariant enforceable: without a *total* `correlationColumns()`
accessor, `buildPayloadCarrierBatchedTableField` would have to fork on "does this binding carry key
columns, else compute `primaryKeyColumns()`", which is a fork on absence and reintroduces the
two-derivations problem one level up. With it, the DML and
`@service` arms answer `tableRef.primaryKeyColumns()` (the value they compute today), the routine arm
answers its captured tuple, and the call site reads one accessor. The key columns are also the honest
one-line answer to what the new arm carries that its siblings cannot, which is the justification the
model asks of a new sub-taxonomy; "it has no `DmlKind`" is not one.

**Declined: the consumer-side merge.** Folding the three memo maps on `RecordBindingResolver`, the
three `xEmittedBinding` accessors, and the three near-duplicate blocks in
`classifyChildFieldOnResultType` into one seam is a real consolidation, and this item makes it
tempting rather than necessary. The blocks differ in their table-agreement diagnostics, whose
wording existing fixtures pin, so unifying them is the actual work and it is diagnostic work, not
structural. Taking it here would also put the DML and `@service` emit paths, which this item
otherwise does not touch at all, inside its acceptance surface. Filed as
`roadmap/emitted-carrier-binding-consumer-consolidation.md`.

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
Mutation field carrying `@routine` whose return is a non-`@table` Object, with the table read
straight off the carrier scan's `DmlElementKind.Table`.

Reading the table off the scan means the grounding walk calls into `TypeBuilder` while the binding
fixed point is still forming, which looks like a layering violation and is not one. `TypeBuilder`'s
own `lookAheadVerdict` javadoc records the rule: during `prepareForWalk` the inputs are still
forming (it names the DML grounding probing the payload scan mid-fold as the existing instance), so
`prepareForWalk` clears the memo at its end and only post-fixed-point verdicts stick. The routine
arm is the second instance of a pattern already reasoned about, not a new hazard. Noted here so the
implementer does not spend the cycle re-deriving it, or "fix" it by threading the table in from the
field instead, which is possible but buys nothing: the data field's element type is where the table
actually lives.

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

This raises `LeafRatchetTest.MUTATION_FIELD_LEAVES` from 8 to 9, against a constant whose javadoc
says the pins move only downward. That javadoc is worth reading precisely: what it names as the
illegitimate rise is "a new *operation-encoding* leaf, which the dissolution programme exists to
make unnecessary: add a fact or a member row instead", and it names the surviving distinctions as
"source, delivery and target grain". This rise is the second kind. The operation is unchanged (the
same routine write, the same `OperationMember`), and what the new leaf encodes is target grain,
which the same sentence protects.

The sibling test settles the reading better than parsing the ratchet's own wording does.
`LeafReconstructionKeyTest`'s class javadoc contemplates exactly this move: a new leaf "fails here
until it declares its triple, which is the moment to ask whether the distinction it encodes is
source, delivery or target grain, or an operation term that belongs on a member row." That is the
programme describing a legitimate new leaf and naming the question to answer before adding one, in a
test whose stated purpose is enforcing the reconstruction key. The two tests are one programme, so a
rise that answers that question with "target grain" is the case the pair was written to admit, not a
loophole in one of them.

The alternative was weighed and is worse, for a reason that is structural rather than aesthetic. A
sealed return-shape fact on the existing leaf keeps the count at 8, but `LeafReconstructionKeyTest`
declares triples as a `Map<Class<?>, String>`, one per leaf class, and `MutationField.target()` is a
total switch with one arm per leaf class. Under the fold, `MutationRoutineWriteField` would have two
targets ("table (post-commit terminus)" and "payload record") and its `target()` arm would have to
switch on an inner fact to say which. The leaf would stop determining its own target, so
`leaf = f(source, delivery, target)` would stop holding as a function, which is the single-valued-
slot-for-a-multi-valued-relation fault that the dissolution programme spent eight slices removing.
Keeping a count low by making the reconstruction key untrue is the wrong trade against a test whose
stated purpose is to enforce that key.

So: take the rise, and the constant's history line records it in the same commit as a target-grain
addition with this reasoning, in the format its existing downward moves use. A ratchet that can
never rise for any reason is a count, not an invariant.

The class javadoc has to move with the constant, not just gain a history line. Its standing sentence
is the flat "**These pins move only downward**", and after this item that sentence is false as
written, whatever the qualifying sentences after it say. Reword it to the rule the rest of the
paragraph already implies: the pins move downward as dissolution slices land, and rise only for a
distinction the reconstruction key names as surviving grain, never for an operation-encoding leaf.
Leaving a false flat claim above a constant that just contradicted it is the version of this change
that rots, and it teaches the next reader that the ratchet is decorative.

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

Both cardinalities, and no constraint tying the data field's wrapper to the routine's own result
shape, because there is no fact to constrain against. jOOQ generates every table-valued function as
a `Table<R>`, so "set-returning" is the kind, not a cardinality statement about any particular call;
a `RETURNS TABLE` function yielding one row is indistinguishable in the catalog from one yielding
many. The SDL wrapper is therefore the only cardinality claim in the system, exactly as it is on the
direct-return path, which already admits both. A single-cardinality data field means step 1 emits
`fetchOne()`, and the no-row case is already handled by the existing null-keys guard. The question
of whether an author *should* declare a single wrapper over a many-row routine is a schema-review
question, not one the model can answer.

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
  diagnostic afterwards, and it keeps the Connection element peel the resolver does today.
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
* `FieldBuilder.transportForParent`: the `activeChannel` gate admits the routine carrier, so its
  `errors` field reaches `selectErrorsTransport` and binds `Transport.LocalContext` instead of
  falling through to `PayloadAccessor` (D3). Silent if missed, and it is what makes outcome (a)
  reach the client at all, so it carries its own classification test rather than riding the
  data field's.
* `TypeFetcherGenerator`: a step-1-only fetcher for the new leaf. It is
  `buildMutationRoutineWriteFetcher` truncated at the transaction boundary, returning the captured
  keys, with `catchArm` given the `singleRecordSentinelFor` sentinel the DML carrier passes. The
  existing two-step fetcher stays for the direct-return leaf. Step 1 projects the captured tuple
  under the *terminus table's* key fields, not the routine result's same-named columns: the data
  field reads its correlation off that record by column, and the DML path it mirrors projects
  `Tables.<TARGET>.<PK>` directly, so matching that keeps the carried-key component and its reader
  agreeing by field identity rather than by jOOQ's name-lookup fallback.
* `GraphitronSchemaValidator`: an arm for the new leaf mirroring the classifier's pins, including
  D6.
* The operation-member declarations, all landing `Write.RoutineWrite()` exactly as the sibling leaf
  does (D4's "the same `OperationMember`"): an `OperationMembers.DECLARED_SHAPES` entry, a
  `membersOf` arm, and an `OperationMemberRelation.writePayloadOf` arm. The first two are
  compiler- or test-enforced; `writePayloadOf` ends in `default -> throw`, so a missing arm is a
  generation-time throw rather than a build failure at the edit site.
* `CatalogBuilder`'s `FieldClassification` arm for the new leaf. The sibling projects
  `RoutineBacked` (hover and jump-to-source route to the routine's call surface); the carrier leaf
  wants the same, since the routine is still what backs it.
* `FetcherEdgeCommands`, `LeafReconstructionKeyTest`'s triple map, `LeafRatchetTest`'s constant plus
  its history line *and* the rewording of its class javadoc's downward-only sentence (D4), and the
  generated `docs/manual/_generated/supported-schema-shapes.adoc` (regenerated by the roadmap tool,
  not hand-edited).

## Tests

* **Classification** (`GraphitronSchemaBuilderTest` routine block): carrier admitted with and
  without an errors field; record-element and ID-element data fields rejected with routine wording;
  a non-null data field rejected per D6; multi-hop carrier lands the typed `Deferred`; a carrier
  whose data-field element table disagrees with the chain terminus rejects on the terminus rule; a
  `[Film!]` data field drives list cardinality (the D2 hazard, which would pass silently as
  `Single` under a type-name-only unwrap); direct-return shapes unchanged. Plus the transport pin:
  the carrier's `errors` field classifies as `ChildField.ErrorsField` with
  `Transport.LocalContext`, which is the assertion that fails if the `activeChannel` gate was not
  widened, and fails at classify time rather than three tiers later in execution.
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

## Where this plan is most likely to be wrong

Not questions to answer before implementation; the decisions above are taken. These are the load
points a fresh reader should test the reasoning at, because if the plan fails it fails here.

* **D4's ratchet rise (8 to 9).** The argument is that the rise is target grain, which the
  constant's own javadoc protects, rather than operation encoding, which it forbids, and that the
  count-preserving alternative would make `leaf = f(source, delivery, target)` untrue as a function.
  If that reading of the javadoc is wrong, the rest of D4 falls with it and the fold becomes the
  answer despite its cost.
* **The captured-key component.** The whole design rests on the claim that hop 0's target side is
  the terminus PK for every single-hop shape that reaches this leaf, which rests in turn on
  `synthesizeNameMatchedJoin` being the only keying available out of an FK-less routine result. If
  some hop-0 shape reaches the leaf without going through it, the carried key is silently wrong
  rather than loudly absent, and the classifier needs the `On.Keying.NameMatchedKey` pin the current
  leaf does not carry.
* **D6's nullability rule.** It is a new authoring restriction justified by a runtime consequence
  (non-null propagation destroying the errors list). If a reviewer can show a shape where a non-null
  data field is both safe and useful, the rule is over-broad and should become a warning.
* **The multi-hop deferral's framing.** Stated over the emitter on the argument that the model then
  acquires no hop-count axis. If the terminus-key-capture emit turns out to be infeasible in-
  transaction for a reason not visible from here, the deferral is really about shape after all and
  the wording will have misled whoever picks the follow-up up.

Adjacent, deliberately not folded in: the routine-kind axis (procedures, scalar and void
routines, and the single-node Mutation `@routine` with no `@reference` hop) is
`roadmap/routine-write-result-shapes.md`. That item asks which routine kinds can back a write;
this one asks what the field may return once one does. They meet if a consumer's routine turns
out to be void or scalar, since a void routine plus a payload carrier has no data field to fill,
but the return-shape work stands alone over the table-valued kind already shipped.

