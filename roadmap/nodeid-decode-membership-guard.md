---
id: R893
title: "A decoding @nodeId instruction with no installed decode fails the build"
status: In Progress
bucket: validation
theme: nodeid
depends-on: []
created: 2026-08-31
last-updated: 2026-09-10
---

# A decoding @nodeId instruction with no installed decode fails the build

## Goal

An author who writes a `@nodeId` the generator cannot carry out learns so from the build, not from
production. Today such an instruction can be dropped silently: the build is green, the generated code
compiles, and the failure arrives per request, as a `ClassCastException` at the cast the emitter falls
back to or as base64 strings compared against integer key columns. That is what
[issue 536](https://github.com/sikt-no/graphitron/issues/536) reports. When this lands, an instruction
no decode reaches fails the build instead, naming the coordinate and the node type, and it holds for
every lowering path the generator has rather than for one shape at a time.

The rest of this section is the mechanism behind that promise, and the plan follows it.

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
dropped on the SQL rail would compile clean and fail per request, which is the failure above. So the
promise this item makes is the one the Java rail already keeps, extended to the SQL rail and stated
over every lowering path instead of one shape at a time.

## What the measurement showed, and how it changes the plan

The Backlog text proposed a membership rule over the store: `intent_node_id_instruction` states every
authored decode instruction, `intent_node_id_decode` states the installed decodes, and an instruction
with no decode row at any destination is one the generator dropped. It also guessed that the dotted-descent
shape (a field-level `@condition` whose `argMapping` descends to a `@nodeId` input field) would reject
for free at such a rule, and that the rule would live as one more component on `StoreDetections`.

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

The third row, a dotted `argMapping` descending to a `@nodeId` input field, **has a decode row**, and
it has one for a reason unrelated to whether a decode is installed there. The reason is stated on
`intent_node_id_decode_slot` itself: a dotted `argMapping` path that descends into an argument and
binds one input field below it draws no slot row, its arms asking about the root argument. With no
slot row the coordinate falls into the decode's table arm and reads as a table predicate, which is a
decode. So the store answers this coordinate by accident, and a membership rule over the store would
be right here for the wrong reason. Closing that limit belongs to the slot relation and not to this
item; what this item takes from the row is that the store's answer and the generator's behaviour are
independent at this coordinate, in both directions.

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
read off: not the store's model of the decode, but the coordinates the run **actually installed one
at**. Three precisions, because getting any of them wrong makes the check unbuildable or wrong.

*The obligation mirrors a classifier decision; it is not a statement about which program computes it.*
Every classifier decision that implies a generator branch has to fail at `validate()` when that branch
is unimplemented, and a dropped decode is that corollary's `@nodeId` instance. Stating it that way
settles the anchor without an inventory of surfaces to keep current. The emit plan is still excluded,
because `EmitPlan.produce` runs after validation and only on an empty error list and
`Projection.VALIDATE` emits nothing, so a check reading it could never fail this item's own gate; but
that is a consequence of where the plan sits rather than the rule itself. It also gives the drainage
clause for free: the ledger's producer moves with the walk, the obligation does not.

*The decode is installed by two rails, and one of them is not in the walk at all.*

**Rail one, the classification walk's carriers**, held by a filter carrier, an `InputColumnBinding`, a
`LookupMapping` and a `CallParam`. Four arms of the sealed `CallSiteExtraction`: `NodeIdDecodeKeys`
(both `ThrowOnMismatch` and `PruneOnMismatch`), `NodeIdDecodeRecord` for a slot typed as the node
table's generated record, `NodeIdDecodePolymorphicRecord` for a slot whose `@nodeId(typeName:)` names
an interface, and `JooqRecord`, which holds a `RecordKeyDecode` per `@nodeId` field of an input type
bound to a record parameter. `RecordKeyDecode` is the one decode leaf that is not itself an arm of
that seal. The seal is therefore a handle on the *arms* and no handle at all on the *installs*: an
install is a construction site rather than a variant, and nothing in the type system pairs a
construction with the coordinate it happened at. That, and not any absence of a supertype, is what
slice 1 exists for.

**Rail two, the projected-key rail**, which is neither a `CallSiteExtraction` arm nor a walk product.
`ProjectedKeyReads` is, in its own words, "the node-id decodes one emitted method performs";
`ConditionGlueRenderer` and `RoutineWriteFetcherRenderer` drain it, and
`ProjectedKeyReads.installRailOwns` exists precisely to say when rail one owns a binding and this rail
stands aside. Where it stands aside, this rail is the one that installs. Its fact is the
`KeyProjection` row, which reaches the generator as `StoreDetections.keyProjections()`, in hand at the
fold point one statement before `EmitPlan.produce` is reached. A rule reading rail one alone reports
every coordinate rail two serves, and those are shapes the user manual documents as working.

Rail two states its own disposition rather than having one read off `keyProjections()`, and both
reasons bite. *The grain is wrong.* `ResolvedKeyProjections` is "deliberately coarser than the view's
own grain" and drops `(site, use_site, position)` because an emitter needs none of it, while the census
at the `INPUT_FIELD` site is per use site, "one input field carrying one directive is as many rows as
there are coordinates consuming it". A definition-keyed install therefore anti-joins away every use
site where nothing was installed, which is a silent miss of exactly the class this item exists to
close, arriving through the keying axis. *And presence there is not an install.* The relation resolves
a projection at every site; what leaves only the emitting ones is that `ArgmappingProjectionDefects`
has already failed the build for the rest, `EMITTING_SITES` being that closed set. Reading presence as
an install rests this rule's soundness on a sibling family's verdict with nothing binding the two, so
the day a site joins the view's population without joining `EMITTING_SITES`, this check goes quiet
exactly where it should speak. That is probe B's over-claim again, one relation over.

*Both rails are transitional surfaces, so the plan owes a drainage clause.* When the walk drains, or
when the projection relation is re-grained, this check is re-sourced from whatever states the install
then. The clause belongs to the check rather than to either rail, which is why the obligation is
worded over rails and not over types.

So the obligation is stated as a **total** one, which is what keeps an absence meaning a single thing:
**every decoding `@nodeId` coordinate the walk stands on gets a ledger row carrying its disposition**,
and the residual is the census rows with **no ledger row at all**. The disposition is a sealed
`Disposition`: `Installed` naming the rail, `Refused` carrying the `Rejection`, `NotReached` where the
owning field's classification aborted before the coordinate. A census row with no ledger row is then a
coordinate the generator neither carried out, nor refused, nor failed to reach, which is a generator
gap by construction and nothing else, and `Rejection.deferred` is the honest arm for it.

This is the difference between the plan and a rail survey, and it is worth being plain about why. A
plan-level list of producers is an inventory that reads as authoritative and rots silently, and this
plan's own list went stale in the week it sat in Spec, which is what round one's first finding
measured. Under totality, "which rails exist" stops being a sentence a reader has to keep true and becomes a
compiler-and-meta-test question, and the residual stops meaning three things at once (a dropped decode,
a rail the plan forgot, a coordinate the walk never reached).

Two operands, then two rules about how they meet. The refusals are a disposition on the ledger rather
than a set beside it, which is what collapses the third operand and makes the two share a key by
construction.

* *The census of instructions.* `intent_node_id_instruction` where `site` is `ARGUMENT` or
  `INPUT_FIELD`, joined to `intent_type_domain` the way `NodeIdDecodeDefects.inDomain` scopes its
  verdicts, only a coordinate the generator intends to classify being able to fail a build. That
  domain join is the census's scope and not the residual's, for the reason the last bullet states. A materialized table, so the read is cheap. Notably not `intent_node_id_decode`, the deepest
  derived read in the schema, which stays off the build path entirely.
* *The disposition ledger.* One row per coordinate the walk stands on, minted at the site that holds
  the coordinate and the verdict together. That site already exists and already decides: the sealed
  `NodeIdLeafResolver.Resolved` has four install arms (`SameTable`, `FkTarget.DirectFk`,
  `FkTarget.TranslatedFk`, `AuthorOwnedPredicate`) and `Rejected(Rejection)`, and it carries no
  coordinate only because its callers hold one, `BuildContext.classifyInputField` and
  `FieldBuilder.classifyArgument` being the same sites that hold the install. So the ledger is a
  transcription of a decision made once rather than a second decision, which is the discipline the
  fact model asks for. `NotReached` is likewise a fact the walk holds structurally, not a message it
  currently emits.

  It is deliberately **not** derived from the run's `ValidationError` stream, nor from whether some
  other family currently emits a message at the coordinate. Sourcing the coverage set from where a
  message lives makes this rule's population change whenever an unrelated family re-words or re-grains
  its errors, with nothing failing to say so, and it goes quiet the day that family re-sources onto
  the store. At `Type.field` granularity the stream is also too coarse to be safe: a filter input type
  carrying several `@nodeId` fields on one owning field would have every instruction under it covered
  by one unrelated error.
* *The keying axis, which is use, not definition.* Both operands key on the coordinate's
  **components**, never on a composed coordinate string, and on the **use site** rather than the
  definition. The census is already use-keyed at the `INPUT_FIELD` site; a ledger keyed at the
  definition would anti-join away every use site where nothing was installed. The components are
  relational on both sides and need no path surgery: `graphitron_argmapping_match` carries
  `bound_kind`, `bound_type_name`, `bound_field_name` and `bound_argument_name`, which at the
  `INPUT_FIELD` site are the census's own `(type_name, field_name)`, and the use site decomposes
  through `intent_input_occurrence_path` (`root_type_name`, `root_field_name`, `root_argument_name`)
  with the descent one ordinal-keyed row per step in `intent_input_occurrence_path_step`. Splitting
  the written `argumentPath` in Java to recover the same components, as `ProjectedKeyReads.leafOf`
  case-folds one today, would be a second spelling of a resolution the store already states. The rule
  matters because `use_site` in the store is a rendering of columns beside it, and a Java-side string
  composed to match it is two spellings of one value that agree until one changes. A join miss here
  does not read as a join miss, it reads as a dropped instruction and fails a build that should pass.
* *Not the classification domain, for the residual.* `intent_type_domain` scopes the census and only
  the census. It is the generator's *intended* traversal seeded from SDL facts and over-approximates
  on purpose, in its own words admitting a type "declaring the Relay contract over no table or over
  defective metadata" as "a member that gains diagnostics instead of vanishing", and it is type-grain.
  A `@nodeId` under a field the walk refused for an unrelated reason is therefore a census member, and
  what keeps the check quiet about it is the `NotReached` disposition rather than the domain join.

## Shipped

Both slices landed. What follows is the shape as built, and the places it diverged from the plan.

**The vocabulary and the ledger.** `NodeIdDecodeCoordinate` lives in `graphitron-model`'s
diagnostics package, so both operands of the rule are one type rather than two spellings translated
at the seam. It is sealed over the two decoding sites and carries components, never a rendered
use-site string. `NodeIdDecodeDisposition` is the sealed `Installed(Rail)` / `Refused(Rejection)` /
`NotReached` the plan asked for, and `NodeIdDecodeLedger` is the one write path, held on
`BuildContext` and surfaced on `GraphitronSchemaBuilder.Bundle`.

**The mint sites, as they turned out.** The plan named two and there are five, all of them sites
that already held coordinate and verdict together: `FieldBuilder.classifyArgument` and
`BuildContext.classifyInputField` on the directive arm (each transcribing
`NodeIdLeafResolver.Resolved`), the same two on the *implicit* arm, and `InputBeanResolver` plus
`ServiceCatalog.bindServiceMethod` for a producer slot. The implicit arm is the one the plan did not
see, and it is not a rail: `intent_node_id_instruction` states five bases, and `TARGET_ID_NAME` (an
`id: ID` slot of a node-returning field, no directive at all) is a decoding instruction the census
holds with nothing written on it. The corpus ratchet found it immediately, as
`Query.filmActorByNodeId` in the sakila example.

**The use site had to be threaded, and that was the slice's real work.** `ClassifyContext` gained a
`UseSite` (the three root components plus the descent), which is what makes an input-field
coordinate nameable at use grain; `InputFieldResolver.resolve`, `TypeBuilder.resolveInputFields`,
`InputBeanResolver.enrich` and the bean and record descents inside it all take one.
`ServiceCatalog.pathCoordinate` recovers the same components for an `argMapping` path through the
SDL walk `pathLeafDeclaration` already makes, rather than by splitting the written path.

**Totality is a post-walk sweep, not one mint per abort path.** `NodeIdDecodeNotReached` mints the
`NotReached` rows off the walk's own structural facts: the field registry holds no entry for the
coordinate's owning field, or it holds an `UnclassifiedField`. One site rather than one per abort
path, driven by SDL plus the registry rather than by the census, so it cannot empty the residual it
exists to keep meaningful. A field that classified is deliberately not swept.

**The check.** `NodeIdDecodeCoverage.violations` folds the projected-key rail's installs into the
ledger and anti-joins the census against it, at the fold point beside `StoreDetections.violations()`
in `GraphQLRewriteGenerator`. `Rejection.deferred`, as the plan requires, and no sub-population is
attributed to the author. `NodeIdDecodeCoverageFacts` is the store operand: the census (materialized
tables only, so it expands no rule at read cadence) and the rail-two installs, read at
`intent_resolved_node_key_projection`'s own `(site, use_site, position)` grain and narrowed on
`ArgmappingProjectionDefects.EMITTING_SITES`, now package-visible for that one reader.

**The residual on the tree as shipped is empty**, which is the outcome the plan predicted. The
corpus ratchet passes over every document carrying a node id, and so does the sakila example's full
generate-compile-execute pipeline. The item is the ratchet it said it was. Probe B's open candidate
(a `@nodeId(typeName: "Staff")` filter over `store`, two foreign keys between the pair) is not a
member: the walk refuses an ambiguous route by name, which is a `Refused` disposition.

**Three divergences worth the reviewer's attention.**

* *The mint pin is over arms, not over construction sites.* `NodeIdDecodeArmCoverageTest` computes
  by reflection over the sealed hierarchy which `CallSiteExtraction` arms reach a handle on the wire
  format, and compares that with `NodeIdDecodeLedger.DECODE_ARMS`; `installedBy`'s switch is
  exhaustive, so a fifth arm is a compile error and an arm answered wrongly is a test failure. The
  plan asked for a lexical scan in the mould of
  `PackageImportDirectionTest.unitRefsAreMintedOnlyByThePlansNamingVocabulary`. The reason for the
  change is that the pin's stated purpose is catching the *fifth arm*, which reflection over the
  seal catches directly, while two of the nine construction sites are emit-time and hold no
  coordinate to mint with.
* *The granularity enforcer states the grain with two drops rather than a drop beside a refusal.*
  The plan's pairing is not constructible: a field whose arguments the walk classifies disposes of
  every instruction under it, and one whose arguments it does not classify refuses none of them.
  The test says so in its own javadoc.
* *One coarsening, in the safe direction, stated on the code.* Where one field reaches one input
  field through two arguments, the rail-two install operand takes both occurrence paths as
  installed. `graphitron_argmapping_match` leaves `bound_argument_name` NULL on an `INPUT_FIELD`
  binding, and narrowing further would mean splitting the written path, which is the second spelling
  the keying rule refuses. It can lose a report and can never fail a passing build.

## Tests

Landed as follows.

* `NodeIdDecodeCoveragePipelineTest`, the gate and its clean half. A `@nodeId` argument on a plain
  column field fails the build naming the coordinate, the node type and both rails; the same
  instruction on a filter argument builds clean. Beside them: the rail-two regression (a dotted
  `argMapping` descent to a `@nodeId` input field, both spellings, clean), the use-site enforcer
  (one input field installed at one consumer and not at another, only the second reported), the
  granularity enforcer, and the `NotReached` case.
* `NodeIdDecodeCoverageRatchetTest`, the totality claim over the corpus: every document carrying a
  node id validates with no coverage report, with a floor on the swept set.
* `NodeIdDecodeArmCoverageTest`, the mint pin, plus the delegating arm's own case.
* `DetectionReadReachGateTest` gains the new component's read-cadence row, which is the projection
  relation's body and nothing else.
* `HierarchyKindRegistryTest` gains the coordinate's kind label.
* No new model-tier cases: the rule reads no new relation.

## Docs

Landed. The user-visible change is a build message where a request-time failure used to be, on a rail
the manual already promises decoded values for, and `docs/manual/reference/directives/nodeId.adoc`
now says so in the inference section, beside the two shapes that already leave nothing to inherit: a
coordinate no lowering path reaches fails the build naming the slot, the node type and the consuming
field, and the message points at the `argMapping` key projection where that is the remedy. No new
directive, no new argument, no SDL change.

`intent_node_id_decode`'s comment has converged on `intent_node_id_encode`'s wording for its own
half. It keeps its absence clause, which was never in doubt, and gains the presence clause it lacked:
a row says where the decoded tuple would land, and whether the generator carries the decode out there
is the walk's fact. Probe B's cross-table filter is named on the comment as the measurement that
settles the reading, and the comment says outright that the build-path coverage rule is deliberately
not a reader of that relation.

## Open forks for the reviewer

* *Where should the disposition be stated?* Slice 1 states it in Java, in the walk's own vocabulary.
  The store-first alternative is to write it as a relation transcribing what the walk decided, which
  makes the rule an anti-join rather than code, drains with the walk, and can be diffed against
  `intent_node_id_decode` so the over-claim becomes a measured shadow instead of a comment. The
  counterweight has grown since this was written: the `walk_` family that would have housed such a
  relation is gone, its membership grains deleted once their only reader went away and its last
  resident with them, so the store-first option now means reviving a retired family rather than
  adding to a live one. Recommendation, unchanged and now better supported: single-mint in Java
  first, because slice 2 needs the fact whichever surface holds it, and revisit only if a second
  reader appears.
* *Is `NotReached` a disposition or a scope?* This plan makes it a disposition, written where the
  walk's classification of the owning field aborts, so the residual's absence keeps meaning one thing.
  The alternative is to leave it out of the vocabulary and scope the residual to fields that
  classified, which is one less arm and one less mint site. It is rejected here because that scope
  reads the population off whether another family currently emits a message at the coordinate, which
  this plan refuses everywhere else, but a reviewer who reads the mint cost as the larger risk would
  take it, and the choice is visible in slice 1's size.
* *Which `Rejection` arm does the residual class get?* This plan says deferral by default. A reviewer
  who thinks a dropped decode is always the author's mistake would say structural, and that choice
  decides whether a consumer's build stops or reports.
* *Encode side.* The symmetric question on `intent_node_id_encode` (an output field whose encode was
  dropped) is deliberately out of scope. Named so nobody reads the omission as an oversight.

## Reviewer findings

### Round 1 (2026-09-09, Spec -> Ready, reviewer session 01DdweB3L6tSQ4C489RwT1zH)

Verdict: withhold. Two blocking findings on question two, one on question one.

The goal comes through without reconstruction, and it is a good one: today a `@nodeId` on a filter
argument or filter input field whose decode the generator cannot install compiles clean and fails per
request, and after this lands it fails the build instead. The diagnosis is unusually well evidenced,
the two probes are the right measurements, and the reasoning that moves the install fact off
`intent_node_id_decode` and onto the walk is sound: probe B's over-claim is real, and I confirmed the
comment on `intent_node_id_decode` does assert what the plan says it asserts. Nearly every claim about
the tree checks out, including the scoping of `intent_node_id_decode_defect` (`site = 'ARGUMENT'`,
`carrier = 'NAMED_PARAMETER'`, two parameter-typing verdicts), the fold point beside
`StoreDetections.violations()` in `GraphQLRewriteGenerator`, the ordering that puts `EmitPlan.produce`
after the error list is empty, and `intent_node_id_instruction` being the materialized table.

What withholds sign-off is that the plan's picture of where a decode gets installed is one rail and one
arm out of date, and that the third of its three operands has no home.

**Finding 1 (question two: architecture fit). "The decode lives in three arms" misses a fourth arm and
a whole non-walk rail, and under the rule as written both read as dropped instructions.**

The rule reads "a decode was installed" off the classification walk and enumerates three carriers. The
tree has more than three, and two of the misses are live:

* `CallSiteExtraction.NodeIdDecodePolymorphicRecord` is a fourth decode arm, minted by
  `InputBeanResolver` for a `@nodeId(typeName:)` naming an interface at a `@service` slot. It landed
  under R933 on 2026-09-09, after this plan's `last-updated`. Its coordinate is an input-field site, so
  the census covers it.
* The projected-key-column rail is not a `CallSiteExtraction` decode arm at all.
  `ProjectedKeyReads` is, in its own words, "the node-id decodes one emitted method performs":
  `ConditionGlueRenderer` and `RoutineWriteFetcherRenderer` drain it, and it reaches a decode helper per
  `KeyProjection` row. `ProjectedKeyReads.installRailOwns` exists precisely to say when the whole-slot
  install rail owns a binding and this rail therefore stands aside; where it returns false, this rail is
  the one that installs the decode.

Both are silent under the rule as specified, and silence here is not a missed report. A coordinate the
census holds and the ledger does not anti-joins to a row, and the check fails a build on a schema that
works. That is the same hazard the plan names for a composed join key ("it reads as a dropped
instruction and fails a build that should pass"), arriving through the operand rather than through the
key.

The projection rail also pushes on the plan's stated anchor. "It is the classified model, not the emit
plan" is argued from `EmitPlan.produce` running after validation, and that argument is correct as far as
it goes, but this rail's positive fact is neither a walk product nor unavailable at `validate()` time:
it is `StoreDetections.keyProjections()`, which the capture callback already holds at the fold point
where slice 2 wants to live, one statement before `EmitPlan.produce` is reached. So the operand exists
and is in hand; the plan just does not admit it. An anchor that is "walk products only" cannot state
this install, and slice 1's single mint cannot reach it either, since nothing mints it in the walk.

What would satisfy: an enumeration of install rails checked against the tree as it stands, and an anchor
that admits an install stated outside the classification walk, or an argument for why the projection
rail's coordinates fall outside the census.

*Author response.* Both rails are now named, with the fourth arm and `RecordKeyDecode`'s position in the
seal stated correctly, and the anchor is restated as the validator-mirrors-classifier corollary so it no
longer names a program. But the finding's remedy is not the one taken: an enumeration checked against
today's tree is still an inventory, and this plan's went stale inside a week. The rule is now **total**,
a `Disposition` per coordinate the walk stands on, with the residual defined as census rows carrying no
ledger row at all; the rail list moves into the compiler and the mint pin. Rail two also does not read
`keyProjections()` as an install, which the finding implicitly proposed: that read drops
`(site, use_site, position)` while the census is use-keyed, so it would have anti-joined away every use
site where nothing was installed, and its soundness rests on `ArgmappingProjectionDefects` having already
failed the build for non-emitting sites. The keying axis is now stated as use rather than definition,
with the components read off `graphitron_argmapping_match` and the occurrence-path relations instead of
being recovered by splitting the written path.

Incidentally load-bearing and not true as written: "The three share no supertype, which is why the first
slice below is a type-system change rather than a survey." `NodeIdDecodeKeys`, `NodeIdDecodeRecord`,
`NodeIdDecodePolymorphicRecord` and `JooqRecord` are all arms of the sealed `CallSiteExtraction`; only
`RecordKeyDecode` sits outside it, as a leaf inside `JooqRecord`. Slice 1's recommendation may well
survive the correction, but its justification has to be restated on what is actually there.

**Finding 2 (question two: architecture fit). The third operand has no home in the plan, and no surface
in the tree states it at the grain the plan requires.**

Slice 1 mints the install fact at coordinate-component grain and gives a whole slice to it. The
refusals operand is described as something to read: "drop what the classifier refused by name", "the
classifier's own typed `Rejection` at the same coordinate". No slice mints it, and nothing in the tree
states it at that grain:

* `Rejection` carries no coordinate at all. The plan's own exemplar,
  `InputBeanResolver.singleValuedMemberDeferral`, bakes the field path, the parameter name, the method
  and the class into the `Rejection.deferred` summary prose.
* `ValidationError.coordinate` is documented as a type name or a `Type.field` qualified name, which is
  the grain the plan rejects as too coarse.
* `GraphitronField.UnclassifiedField` carries `(parentTypeName, name, location, rejection)`, the same
  grain.
* The stored form is no finer: `RejectionFacts` transcribes the walk's stream into
  `rejection_validation_error` as a `(type_name, field_name)` pair, and its own javadoc says the sealed
  coordinate component has not landed.

So the plan rules out the one existing surface, then requires strictly finer grain than any surface
provides (the granularity enforcer's fixture, two `@nodeId` instructions on one owning field, is exactly
a demand for argument or occurrence-path grain), and allocates no work for it. This is the same
threading job slice 1 exists to do, and an implementer handed this plan would design it mid-flight,
which is what the second gate question asks about.

What would satisfy: say where the refusal fact is minted and at what grain, as a slice of its own or as
stated scope on slice 1. If the answer is that the refusal ledger rides the same mint as the install
ledger, that is a good answer and worth one sentence.

*Author response.* It rides the same mint, as a `Refused` arm of the sealed `Disposition` rather than as
a second ledger, so the two cannot disagree about a coordinate. The mint sites are not new decisions:
`NodeIdLeafResolver.Resolved` is already one sealed verdict with four install arms and a `Rejected`, and
its callers already hold the coordinate it declines to carry. The finding's measurement stands and is
what rules out the `ValidationError` stream; slice 1 now names the vocabulary, the arms and the pin.

**Finding 3 (question one: is the outcome reachable, and is it the outcome claimed). The item's only
enumerated build failure has shipped.**

"What lands as a build failure the day this ships" is the right section to owe, and its one member is
R884's shape. R884 is Done: `roadmap/changelog.md` carries its entry, its Done gate landed 2026-09-08,
a week after this plan's `last-updated`, and `ProjectedKeyReads.installRailOwns` plus
`ResolvedKeyProjections` are its fix. `docs/manual/reference/directives/condition.adoc` now documents
that descent as working: "Either way the parameter receives a decoded column's own value and never the
encoded id."

That makes five statements in this plan describe a state that no longer holds: the `CallParam` installs
no decode ("that is what R884 *is*"), the check reports the shape by construction, the row drains when
R884 lands, the `depends-on` rationale about a drain rather than a prerequisite, and the tests bullet
placing that descent in the ratchet's expected-deferral set rather than its clean set. Probe A's third
row and the paragraph that reads it also predate the fix.

This is not only staleness, which is why it blocks rather than sitting below. With that member gone the
section enumerates nothing, and a reviewer cannot tell from the plan whether this check converts any
live silent drop into a reported one on the day it ships, or whether it is now purely a ratchet against
future ones. Both are defensible goals and the second is a real one, but they are different goals with
different value, and the plan's own standard is to owe the list rather than discover it in a consumer's
build.

What would satisfy: re-run the enumeration against the current tree and state what this reports on the
day it ships. If the answer is nothing, say so and state the goal as the ratchet.

*Author response.* Re-run, and the section now says the list is empty and the item is a ratchet. The
shipped shape is reclassified from first member to the reason rail two has to be an operand at all, the
two probe-A coordinates and probe B's no-foreign-key filter are shown covered, and the one shape the
probes did not measure either way (probe B's two-foreign-key filter) is named as the open candidate
rather than left for slice 2 to discover. The `Goal` heading and the `StoreDetections` count in the
non-blocking notes are fixed in this revision.

**Non-blocking.**

* The body opens with `## What this is about` rather than the `## Goal` section
  `roadmap/workflow.adoc` § Item file conventions now prescribes. The goal is stated plainly in that
  section's third paragraph, so nothing is lost; the heading is the only miss.
* "the rule would live as a fifth component on `StoreDetections`" is a stale count (the record now
  carries eight components), but it describes the discarded Backlog approach, so nothing turns on it.

### Round 2 (2026-09-10, Spec -> Ready, reviewer session 017ccpBvcWZh8FjuNmpLfNo2)

Verdict: sign off. Both gate questions pass, and all three round-one findings are answered by the
revision rather than argued away.

*Question one.* The goal reads without reconstruction. Today a `@nodeId` on a filter argument or a
filter input field whose decode the generator cannot install compiles clean and fails per request,
as a cast that throws or as a base64 string compared against an integer key column; after this
lands, `validate()` refuses it naming the coordinate and the node type. The revision's own answer to
round one's third finding is the honest one and is what makes the goal judgeable: on today's tree
the day-one failure list is empty, so nothing changes for an existing consumer schema and the item
is a ratchet against a class whose defining property is that nobody knows when it acquires a member.
That is a smaller claim than the plan once made and it is the one the probes support. The single
open candidate (probe B's two-foreign-key filter) is named here rather than left for slice 2 to
discover, which is what the section is for.

*Question two.* The shape extends what is in the tree rather than standing something beside it: a
sealed `Disposition` in the mould of the sealed verdict `NodeIdLeafResolver.Resolved` already is,
minted at the two callers that already hold coordinate and verdict together, folded in at the point
where `StoreDetections.violations()` already meets the walk's error stream, keyed on relational
components rather than on a composed string. Making the obligation total is the right answer to the
finding that produced it: it converts "which rails install a decode" from a sentence this document
has to keep true into a compiler-and-meta-test question, and it collapses the third operand round
one found homeless into a `Refused` arm on the same mint, so the two cannot disagree about a
coordinate. The slicing is right too: the mint is the part whose size is unknown, and the plan says
so and names the one known instance (rail two's root argument name, NULL on an `INPUT_FIELD` binding
in `graphitron_argmapping_match`, recoverable from the occurrence path) instead of discovering it
mid-implementation. I would hand this to an implementer as written.

*What I verified against the tree*, since a spec's claims about code are checkable and this one makes
many. Every symbol, relation and quotation named exists as named, by FQN-aware grep: the four
`CallSiteExtraction` decode arms with `RecordKeyDecode` a leaf inside `JooqRecord` rather than an arm;
`NodeIdLeafResolver.Resolved`'s four install arms and `Rejected(Rejection)`, and its two callers;
`ProjectedKeyReads`' self-description, `installRailOwns`, `leafOf`, and its two draining renderers;
`ResolvedKeyProjections`' stated coarsening off `(site, use_site, position)` and the projection record
that carries none of the three; `ArgmappingProjectionDefects.EMITTING_SITES` as the three-site set;
`StoreDetections`' eight components including `keyProjections()`; the fold point in
`GraphQLRewriteGenerator` with `EmitPlan.produce` gated on an empty error list and `Projection.VALIDATE`
emitting nothing; `intent_node_id_instruction` as a materialized table whose `INPUT_FIELD` grain is the
use site; `intent_node_id_decode_defect`'s `site = 'ARGUMENT'` / `carrier = 'NAMED_PARAMETER'` scope and
its two verdicts; `NodeIdDecodeDefects.inDomain`'s `intent_type_domain` join; the decode relation's
absence-is-never-a-message assertion and the encode relation's "Presence is not an emitter" wording that
the Docs section asks it to converge on; the slot relation's dotted-`argMapping` limit, verbatim as the
plan reads it; `graphitron_argmapping_match`'s `bound_*` columns and its `CASE` leaving
`bound_argument_name` NULL off the `ARGUMENT` kind; the occurrence-path pair and their column names;
`Rejection.deferred` / `Rejection.structural`; the mint-pin exemplar in `PackageImportDirectionTest`; the
two probe harnesses; and both manual quotations, which are exact.

**Non-blocking.**

* `EMITTING_SITES` and its `Site` enum are `private` to `ArgmappingProjectionDefects` in
  `graphitron-model`. Slice 1 wants the ledger to narrow on that same declaration and to
  `{@link}` it, so the implementer will have to widen it. A one-line visibility change with no
  design content, noted only so it is not read as a surprise.
* Slice 1's mint pin catches an install carrier constructed outside the vocabulary, which is the
  `Installed` arm. A `Refused` or `NotReached` site that forgets to mint is caught by the corpus
  ratchet rather than by the compiler, and its failure mode is a build that fails when it should
  pass, which is loud. That asymmetry looks right and needs no change; it is worth knowing when
  slice 2's enumeration runs.
