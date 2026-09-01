---
id: R893
title: "A decoding @nodeId instruction with no installed decode fails the build"
status: Spec
bucket: validation
theme: nodeid
depends-on: []
created: 2026-08-31
last-updated: 2026-09-01
---

# A decoding @nodeId instruction with no installed decode fails the build

## What this is about

A `@nodeId` directive is an instruction with two halves that live apart. The client sends opaque
base64 node-id strings on the wire, and the generated code must decode them into key-column values
before anything consumes them. Two rails carry a decoded value. On the **Java rail** the value ends
up in a method parameter of a `@service` or `@condition` signature. On the **SQL rail** it ends up
inside a predicate: a filter input field or a filter argument lowering to an `IN` inside a correlated
`EXISTS`.

The Java rail is guarded. `intent_node_id_decode_defect` compares the node key against the declared
parameter type and fails the build on a mismatch, and `NodeIdDecodeDefects` turns those rows into
located build errors. The SQL rail has no such guard: that view is scoped to `site = 'ARGUMENT'` with
`carrier = 'NAMED_PARAMETER'`, and both its verdicts are parameter-typing comparisons. A decode
dropped on the SQL rail would compile clean and fail per request, either a `ClassCastException` at
the cast the emitter falls back to, or base64 strings compared against key columns. That is the
failure mode [issue 536](https://github.com/sikt-no/graphitron/issues/536) reports.

The goal of this item is the promise that an instruction the generator cannot carry out fails the
build instead of being dropped silently, on the SQL rail as it already holds on the Java rail, and
for every present and future lowering path rather than one shape at a time.

## What the measurement showed, and how it changes the plan

The Backlog text proposed a membership rule over the store: `intent_node_id_instruction` states every
authored decode instruction, `intent_node_id_decode` states the installed decodes, and an instruction
with no decode row at any destination is one the generator dropped. It also guessed that R884's shape
(a field-level `@condition` whose `argMapping` descends to a `@nodeId` input field) would reject for
free at such a rule, and that the rule would live as a fifth component on `StoreDetections`.

Both guesses were checked against captured stores before writing this plan, with throwaway pipeline
probes built on `CapturedStore.ofCatalog` (the harness `NodeIdDecodeSlotCaptureTest` uses) and on the
`validate()` harness `ArgmappingProjectionRejectionPipelineTest` uses. The probes are not in the tree;
their fixtures are reproduced below so any reader can rebuild them. Both guesses were wrong, in ways
that decide the design.

*Probe A, seven decoding coordinates against the sakila test catalog with a hand-built census:*

[cols="4,2,3"]
|===
| coordinate | `intent_node_id_decode` | refused anywhere else

| `films(id)`, argument, same table | `OWN_TABLE_COLUMNS` | no, and none needed
| `filmsBy(filter)/filmId`, input field, same table | `OWN_TABLE_COLUMNS` | no, and none needed
| `filmsCond(filter)/filmId`, field-level `@condition`, `argMapping: "p: filter.filmId"` | `OWN_TABLE_COLUMNS` | no
| `findFilm(id)`, argument, `@service` parameter typed `Integer` | `SINGLE_KEY_COLUMN` | no, and none needed
| `badFind(id)`, argument, `@service` parameter typed `String` | no row | yes, `intent_node_id_decode_defect` verdict `KEY_COLUMN_TYPE_DISAGREEMENT`
| `modifyFilm(in)/filmId`, input field inside a bean parameter typed as a hand-written class | no row | yes, `InputBeanResolver.singleValuedMemberDeferral`
| `modifyFilmRec(in)/filmId`, same, parameter typed as the node table's generated record | `JOOQ_RECORD` | no, and none needed
|===

The third row is R884's shape and it **has a decode row**, so a membership rule is structurally blind
to it. The reason is stated on `intent_node_id_decode_slot` itself: a dotted `argMapping` path that
descends into an argument and binds one input field below it draws no slot row, its arms asking about
the root argument. With no slot row the coordinate falls into the decode's table arm and reads as a
table predicate, which is a decode. Closing R884 wants the slot relation's stated limit closed, which
is R884's own work; nothing this item can do reaches it.

The last two "no row" cases are the whole of the membership class in this fixture, and both are
already refused. One is refused by the sibling view, the other by the walk. So the naive rule's first
act would be two duplicate messages, and one of them would escalate a deferral (recognised, emitter
pending) into a structural error.

*Probe B, cross-table filters, no census:* a `@nodeId(typeName: "Actor")` filter over `film`, where no
foreign key connects the pair, gets `TARGET_TABLE_COLUMNS` from the store while the walk refuses the
coordinate outright ("no foreign key found between tables 'film' and 'actor'"). A
`@nodeId(typeName: "Staff")` filter over `store`, where two foreign keys connect the pair, gets
`OWN_TABLE_COLUMNS` and the walk says nothing. The membership query returned no rows at all.

What the two probes settle, stated no wider than the evidence:

* **The store's decode relation over-claims.** Probe B's cross-table filter gets
  `TARGET_TABLE_COLUMNS` at a coordinate the walk refuses outright. So presence of a row is not
  presence of an emitted decode. `intent_node_id_encode` already carries the correct wording for its
  own half ("Presence is not an emitter... whether the generator carries the encode out at that
  coordinate is the schema walk's fact rather than this relation's"); the decode relation's comment
  claims the opposite and owes that convergence.
* **The store's under-claim is not a disagreement.** At the bean member the store holds no row and
  `InputBeanResolver.singleValuedMemberDeferral` reports a deferral: both say "not carried out". What
  that case raises is message duplication and severity, which any arm of this rule has to answer
  anyway.
* **The measurement that would make the store-side arm unsound is not constructible today.** That
  would be a coordinate carrying a decode row where the generator drops the decode silently, which is
  issue 536's own shape, and the issue's minimized SDL does not reproduce on the RC35 commit. So the
  store-side arm is *redundant* on the available evidence rather than proven wrong. The case against it
  is the over-claim above plus its cost, not a demonstrated silent miss.

## The rule

Keep the membership shape the Backlog text asked for, and change what "a decode was installed" is
read off: not the store's model of the decode, but the coordinates the **classification walk** actually
installed one at. Two precisions, because getting either wrong makes the check unbuildable or wrong.

*It is the classified model, not the emit plan.* `FilterBinding`, `InputColumnBinding`,
`LookupMapping` and `CallParam` are classification-walk products. `EmitPlan.produce` runs after
validation and only when the error list is empty, and `Projection.VALIDATE` emits nothing, so a check
reading the emit plan could never fail `validate()`, which is this item's own gate. Anchoring on the
walk also means the invariant is anchored on a transitional surface, so the plan owes a drainage
clause: when the walk drains, this check is re-sourced from whatever states the install then.

*The decode lives in three arms, not one.* `CallSiteExtraction.NodeIdDecodeKeys` (both
`ThrowOnMismatch` and `PruneOnMismatch`) is what a filter carrier, an `InputColumnBinding`, a
`LookupMapping` and a `CallParam` hold. `NodeIdDecodeRecord` is what a slot typed as the node table's
generated record holds. `JooqRecord` holds a `RecordKeyDecode` per `@nodeId` field of an input type
bound to a record parameter. The three share no supertype, which is why the first slice below is a
type-system change rather than a survey.

So the obligation is: **for every decoding `@nodeId` instruction in the classification domain, the run
either installed a decode at that coordinate or refused the coordinate by name.** An instruction in
neither set is one the generator dropped, and that is what fails the build.

Three operands:

* *The census of instructions.* `intent_node_id_instruction` where `site` is `ARGUMENT` or
  `INPUT_FIELD`, joined to `intent_type_domain` the way `NodeIdDecodeDefects.inDomain` scopes its
  verdicts, only a coordinate the generator intends to classify being able to fail a build. A
  materialized table, so the read is cheap. Notably not `intent_node_id_decode`, the deepest derived
  read in the schema, which stays off the build path entirely.
* *The install ledger.* The coordinates the walk installed a decode at, recorded by the carriers
  themselves as they are minted. Keyed on the coordinate's **components** (type, field, argument or
  the occurrence-path key), never on a composed coordinate string: `use_site` in the store is a
  rendering of columns beside it, and a Java-side string composed to match it is two spellings of one
  value that agree until one changes. A join miss here does not read as a join miss, it reads as a
  dropped instruction and fails a build that should pass.
* *The refusals.* The classifier's own typed `Rejection` at the same coordinate, of which
  `InputBeanResolver.singleValuedMemberDeferral` is one. Deliberately **not** the run's whole
  `ValidationError` stream: sourcing the coverage set from wherever a message currently lives makes
  this rule's population change whenever an unrelated family re-words or re-grains its errors, with
  nothing failing to say so. At `Type.field` granularity the stream is also too coarse to be safe: a
  filter input type carrying several `@nodeId` fields on one owning field would have every instruction
  under it covered by one unrelated error.

## Slice 1: the install fact gets a single mint

No new build failures, bounded by the compiler, and it is the slice that makes slice 2 a projection
rather than a research task.

* Route every construction of the three decode arms through one vocabulary that records the coordinate
  components as it mints. Pin it with a meta-test in the mould of
  `PackageImportDirectionTest.unitRefsAreMintedOnlyByThePlansNamingVocabulary`, so a fourth decode
  carrier added later cannot reach the plan without joining the ledger.
* The alternative is a capability over the three arms, read off the carriers. Cheaper, and membership
  completeness stays review-only, which is the weakness: a carrier missing from the set reads as a
  dropped instruction downstream. Recommendation: single mint, and fall back to the capability only if
  the mint sites turn out not to be a small set.
* Where a carrier cannot state its coordinate components (an occurrence path it does not hold, say),
  that is this slice's finding and its threading is this slice's work. It is the one task here whose
  size is not yet known, which is why it is a slice of its own rather than a bullet in the check.

## Slice 2: the check

* One pass after classification, beside where `StoreDetections`' violations are folded into the error
  stream in `GraphQLRewriteGenerator`. Anti-join the census against the install ledger on coordinate
  components, drop what the classifier refused by name, report the rest.
* **`Rejection.deferred` is the default arm, not `Rejection.structural`.** The residual class is
  defined by the generator not having carried something out, which is exactly "recognised but not yet
  generator-supported". Structural would tell an author to fix a schema that is correct, which is the
  failure the deferral arm exists to prevent, and it is the fault this plan finds in the naive store
  rule. Reserve `structural` for a sub-population the check can actually attribute to the author; if it
  can attribute none, say so rather than reaching for the arm.
* The message names the coordinate, the node type, and the rail, and states the remedy that exists
  today. Where the remedy is what the walk's own foreign-key message already says, converge on that
  wording rather than renegotiating it.

## What lands as a build failure the day this ships

The check converts today's silent drops into reported ones, so the item owes the list rather than
discovering it in a consumer's build.

* **R884's shape is the first known member, and it is a member, not an exception.** A field-level
  `@condition` whose `argMapping` descends to a `@nodeId` input field installs no decode on the
  `CallParam` (that is what R884 *is*), and nothing refuses the coordinate, so this check reports it by
  construction. That is the item's promise firing on a live instance of the exact failure class, and
  the deferral arm is what makes it safe: the message names the pending emitter, and the row drains by
  itself when R884 lands and the install appears. An earlier draft of this plan said the shape "must
  stay green", which the obligation cannot support.
* `depends-on` stays empty on purpose. R884 is a drain, not a prerequisite: this item reports the shape
  as deferred whether or not R884 has shipped, and shipping R884 removes the report without touching
  this rule.
* Slice 2's first task is to run the ratchet over the whole fixture corpus and enumerate every other
  member it finds, with a decision per member: a deferral naming the pending emitter, or a structural
  error where the coordinate really is the author's mistake.

## Tests

* *The gate, pipeline tier.* In the mould of `ArgmappingProjectionRejectionPipelineTest`: an SDL whose
  decoding instruction has no install fails `validate()` with the new message, and the same shape with
  the install builds clean. Both halves, or the gate passes because everything fails.
* *The ratchet, pipeline tier.* The partition over the fixture corpus: every decoding instruction is
  either installed or refused by name. This is what keeps the class from acquiring a silent member as
  new lowering paths land, and it is what would have caught the bean-member shape before an author did.
* *The granularity enforcer.* A fixture with two `@nodeId` instructions on one owning field, one
  refused for an unrelated reason and one dropped, asserting the check reports the dropped one. Without
  it, the coverage set's grain is a claim with no enforcer, and the ratchet can go green over a corpus
  while a drop hides behind a neighbour's error.
* *The mint pin.* Slice 1's meta-test, which is the only thing standing between the install ledger and
  the emit-side allow-list this design is trying not to be.
* *No new model-tier cases.* The rule reads no new relation, so `graphitron-model`'s suite has nothing
  to state about it. Worth naming, because the Backlog text's shape (a new view) would have wanted a
  seeded case per verdict and that work is not in this plan.
* The Probe A and Probe B shapes are the fixtures to reach for. R884's `@condition` descent belongs in
  the ratchet's expected-deferral set rather than in its clean set.

## Docs

The user-visible change is a build message where a request-time failure used to be, on a rail the
manual already promises decoded values for. `docs/manual/reference/directives/nodeId.adoc` states that
promise for the Java rail ("Two ways to get this wrong, and the build names both"); it owes a sentence
saying the build now also speaks up for a filter slot whose id no decode reaches, and the `@condition`
page's existing remedy for R884's shape ("put the `@condition` on the `@nodeId` input field itself")
becomes the remedy the new message points at. No new directive, no new argument, no SDL change.

Separately, `intent_node_id_decode`'s comment asserts that "an instruction with no row here was not
carried out, and absence is therefore never a message". Probe B shows presence over-claims, so the
comment converges on `intent_node_id_encode`'s wording for its own half rather than acquiring an
appended note about the surprise.

## Open forks for the reviewer

* *Where should the install fact be stated?* Slice 1 states it in Java, in the walk's own vocabulary.
  The store-first alternative is to write it as a `walk_` relation, which makes the rule an anti-join
  rather than code, drains with the walk, and can be diffed against `intent_node_id_decode` so the
  over-claim becomes a measured shadow instead of a comment. The counterweight is that the `walk_`
  family's membership grains were deliberately deleted once their only reader went away, so re-adding
  one is an argument to make out loud rather than a default. Recommendation: single-mint in Java first,
  because slice 2 needs the fact whichever surface holds it, and lift it to a `walk_` relation if a
  second reader appears.
* *Which `Rejection` arm does the residual class get?* This plan says deferral by default. A reviewer
  who thinks a dropped decode is always the author's mistake would say structural, and that choice
  decides whether a consumer's build stops or reports.
* *Encode side.* The symmetric question on `intent_node_id_encode` (an output field whose encode was
  dropped) is deliberately out of scope. Named so nobody reads the omission as an oversight.
