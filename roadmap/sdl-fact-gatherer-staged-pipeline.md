---
id: R743
title: "The SDL fact gatherer becomes a staged pipeline, and the walk_ gate dissolves"
status: Backlog
bucket: architecture
priority: 3
theme: classification-model
depends-on: []
created: 2026-08-20
last-updated: 2026-08-20
---

# The SDL fact gatherer becomes a staged pipeline, and the walk_ gate dissolves

The SDL capture load is restructured into a staged pipeline whose last two stages read the
assembled `GraphQLSchema`: the composed census transcribes from assembly instead of the uncomposed
registry (absorbing R714, discarded in this item's favour, see `roadmap/changelog.md`), and a
rooted traversal writes reachability facts that replace the `walk_` membership gate in the
`intent_` stratum. One change, landed as a big bang: no shadow scaffolding, no strangler
increments, and every place the new derivation differs from the walk's behaviour is decided from
requirements rather than transcribed from the legacy code.

## Problem

Three defects, one restructure:

* **The `intent_` stratum reads the retiring walk.** `intent_authored_claim_conflict` joins
  `walk_claim_domain_type` and `walk_claim_domain_field` so conflict minting stays on the
  population the legacy detection reached. The walk is going away (R682,
  `roadmap/planners-read-facts-emitters-read-commands.md`, carries the deletion as its terminal
  deliverable), and when it goes those tables lose their writer and the view's population silently
  empties. R740 (`roadmap/retire-oracle-diff-shadow-tests.md`) declined to schedule the flip for
  want of a reason; the walk's retirement is the reason.
* **The composed census is a reimplementation.** The `graphql_` payload is written from
  `attributed.preSynthesisRegistry()`, a `TypeDefinitionRegistry`, which is the uncomposed census:
  base declarations and extensions are separate entries, and `SdlFactCapture` merges them itself,
  deciding collisions through `FactSink.claim`'s first-wins. That merge is graphql-java's job and
  graphql-java already does it during assembly. This was R714's whole content and it lands here as
  stage 4.
* **Reachability is derived twice, and neither derivation is the architecture's.** The classifier
  computes it in Java (`SchemaReachability` over the assembled schema), and the store re-derives it
  as a semi-naive SQL closure over captured edges (`ReachabilityRows` materializing
  `intent_type_domain`), transcribing the former's seeds. Two derivations of one answer, with the
  store-side one reimplementing edges (interface implementations, union membership) that the
  assembled schema object states directly.

## The five stages

The gatherer becomes a pipeline of judging stages, each keeping what survived it, per the
fact model's own law that a stage's refusal never cancels the next stage
(`docs/architecture/explanation/fact-model.adoc`):

[cols="1,2,3"]
|===
| Stage | Unit | Owns

| 1. Per-file parse
| one file
| each GraphQL file parsed into its own `TypeDefinitionRegistry` and ingested: source membership,
  per-file declarations for the editor's fragment path, `graphql_syntax_error`

| 2. Combined registry
| the file set
| all files loaded into a single `TypeDefinitionRegistry`; a file the registry refuses emits
  validation-error facts (`graphql_schema_error` at stage `REGISTRY`)

| 3. Assembly
| the whole schema
| the single registry assembled into a `GraphQLSchema`; failure to assemble emits validation-error
  facts (`graphql_schema_error` at stage `ASSEMBLY`)

| 4. Full-schema traversal
| the assembled schema
| a depth-first traversal over the full assembled schema populates the fact tables whose primary
  key is the graph name plus the coordinate: the whole `graphql_` payload, composed

| 5. Rooted traversal
| the assembled schema
| a depth-first traversal rooted at the operation roots plus the node and entity types (the
  traversal `SchemaReachability` drives today) populates the reachability facts
|===

Stages 1 through 3 are the three judging censuses `fact-model.adoc` already documents; stages 4
and 5 are new to the documentation, and writing them into the architecture docs is a deliverable.
Change tempo follows the unit: editing one file invalidates its own stage-1 rows and the whole of
stages 2 through 5.

## Strategy: big bang, semantics from requirements

The restructure lands as one change. No differential relations, no shadow tests, no residue
records, no transitional double-writes; R740's doctrine (a difference from the legacy behaviour is
either a legacy bug the change fixes or a mistake, and neither earns scaffolding) applies from the
first commit. Where the new derivation's population differs from the walk's, the difference is
decided from what the requirement says and enumerated under "Deliberate behaviour changes" below,
never smuggled in as fidelity to the old code. Legacy vocabulary (the walk's registries, its
tombstones, its hand-assembled domains, its output-only recorded set) does not travel into the new
relations' semantics.

## Design rule: absence means exactly one thing

Stated once here because the DDL work applies it repeatedly. A relation's absent row is ambiguous
between "the fact is negative" and "nobody gathered the fact", so an anti-join against a relation
is sound only when that relation is total over the question's domain at a single cadence. When a
rule wants an anti-join and that totality does not hold, the model is missing a fact: some producer
computed the verdict and dropped it, and the fix is capturing the verdict rather than inferring it
from silence. The demand stratum already practices this (the exemption views exist so that
"intentionally not demanded" is a positive row, and absence is reserved for model incompleteness).
Reachability under this item is the clean case: stage 5 runs in the same transaction over the same
assembled schema as stage 4, so the reachability relation is total over the captured census by
construction. The conflict gate is then a positive join, and "captured but unreached" is a sound
anti-join for whoever needs it (the unreached-type warning R319 wants is exactly that read).

## Stage 4: assembly owns the composed census

Absorbed from R714; its analysis holds and lands here.

* **The object is already in hand.** `SchemaAssembly.of` runs on every pass and returns a sealed
  `Assembled(GraphQLSchema)` / `Rejected(errors, cause)`; an always-produced, read-only,
  availability-sealed composed schema is precisely a transcription source. Assembly keeps the AST
  back-pointers (`getDefinition()`, `getExtensionDefinitions()`), so `graphql_type_declaration`
  and the site foreign keys remain writable from the composed object.
* **Which registry gets assembled.** The pre-synthesis snapshot, cut after the schema-level
  rewrites that inject declarations (`FederationLinkApplier` among them) and before
  `KeyNodeSynthesiser` applies federation keys. Verify rather than assume that this registry
  assembles standalone; if the cut sits on the wrong side of an injected declaration, assembly
  fails on schemas whose authors wrote nothing wrong. A finding that falls out for free: today's
  `ASSEMBLY` verdicts judge the post-synthesis registry, so a verdict can be caused by graphitron's
  own rewrite rather than by the author; taking the transcription and the verdict from one
  pre-synthesis assembly removes that.
* **What this deletes.** Capture's own extension merge, and with it the first-wins claim on a
  coordinate two files declare. A duplicate is then either graphql-java's merge or graphql-java's
  refusal, and the refusal is already a fact in `graphql_schema_error`.
* **Two decisions to make in the open, not discover.** Introspection types: assembly adds
  `__Schema` and friends, the current census holds the five built-in scalars and no introspection
  types; filter them or admit them, decided deliberately. Applied-directive ordinals:
  `graphql_type_directive.ordinal` is part of the key, so base-then-extension merge order must
  survive the move, pinned by a multi-extension fixture rather than assumed of graphql-java's
  iteration order.
* **The availability cliff becomes a per-census currency fact.** An assembly-owned payload has no
  fresh rows when assembly fails, and under this item stages 4 and 5 both sit behind that cliff. A
  failed assembly must leave the previous composed census in place and say so: a stale valid answer
  instead of a fresh invalid one. `fact-model.adoc` records that freshness stopped being a property
  a consumer handle carries; this deliverable keeps that true by landing currency as rows in the
  store (which census generation is current for the graph, per stage), not as a consumer-side axis.
  The DDL shape of that status relation is this item's to design.

## Stage 5: reachability facts

The rooted traversal writes the reachable-type set as positive rows at capture cadence, in the
same transaction as stage 4.

* **Seeds and edges are `SchemaReachability`'s, by requirement rather than by transcription:** the
  operation roots, the node types, the entity types (authored `@key` carriers), and the argument
  types of directive definitions that survive into the emitted schema; descent over field targets,
  argument types, input-object fields, union members, and `implements` in both directions with the
  interface-to-implementor edge from `GraphQLSchema.getImplementations`. The walk-era output-only
  filter on the recorded set does not travel: the relation holds every reached named type, input
  types, enums and scalars included, which is the population `intent_type_domain` already holds.
* **One relation, type grain.** `intent_type_domain` is the destination; stage 5 becomes its
  writer and `ReachabilityRows`' SQL closure retires. The argument mirrors stage 4's: the closure
  reimplements composition edges the assembled schema states, and it was already a materialized
  table at capture cadence (H2 has no safe recursive view over a cyclic graph), so a traversal
  writing the same rows at the same cadence deletes a reimplementation without moving any
  boundary. No field-grain sibling: a field coordinate is reachable exactly when its owning type
  is reached, so the field grain is a join through `graphql_field`, legible as a projection.
* **The traversal stays in Java and that is not a violation.** The fact model's recompute test
  cares that the rows are a function of captured inputs and that the store holds those inputs;
  both hold. What changes is only which producer computes the closure.

## The gate flip

`intent_authored_claim_conflict`'s domain gate becomes a join against `intent_type_domain`: the
type-grain arm joins it directly, the field-grain arm joins it through the claim's owning type.
The two `walk_` membership grains then have no reader; their DDL
(`walk_claim_domain_type`, `walk_claim_domain_field`), their writers (`ClaimDomain`,
`ClaimDomainRows`) and the `WalkReach` pairing that exists to carry them are deleted in the same
change. `walk_type_backing_class` stays, is R740's scope, and becomes the family's last resident;
the family header's text (including its proposed flip onto the resolved demand relation, which
this item supersedes) is rewritten for the one relation that remains.

The demand stratum is deliberately untouched. Demand answers "does this coordinate require a
classification verdict", the validator's question; the conflict gate asks "is this coordinate part
of the schema's reached surface at all", which is reachability. Under big bang there is no legacy
accept line to reproduce, so the gate lands on the semantically honest population.

## Deliberate behaviour changes

Each is the requirement acting as the specification; none ships unnoticed.

1. **The conflict detection's population moves from the walk's registries to reachability.** The
   walk's registries include tombstones and hand-assembled domain entries and exclude nothing the
   walk visited; reachability includes every reached coordinate whether or not the walk would have
   classified it. Coordinates that gain or lose conflict checking are enumerated on the corpus in
   the landing commit, with each difference argued from the requirement (a conflict on a reached
   coordinate is author-facing wherever it sits).
2. **Duplicate composition becomes graphql-java's.** A coordinate two files declare is merged or
   refused by the registry and assembly rather than by capture's first-wins;
   `graphql_duplicate_declaration` keeps quarantining what the primary path declines, per its own
   charter.
3. **`ASSEMBLY` verdicts judge the pre-synthesis registry**, so an author is no longer blamed for
   a failure graphitron's own rewrite caused.
4. **The census's population question (introspection types) is settled openly**, whichever way it
   goes.

## Out of scope

* Deleting the walk, the leaf hierarchies, or any read the classifier itself performs on
  `SchemaReachability`: R682's terminal deliverable. This item removes the capture-side coupling
  so that deletion finds nothing in the store still standing on the walk.
* `walk_type_backing_class` and the shadow tests over it: R740.
* The `graphitron_` decodes moving from AST reads to row reads: R713, downstream of this item's
  stage 4 exactly as it was downstream of R714.
* The unreached-type warning (R319): enabled by stage 5's facts plus the census (one sound
  anti-join), but its own item.
* The demand stratum's walk-transcribed arms and residue holes: they re-derive from requirements
  when the validator half of R682 migrates each check.

## Retired vocabulary

* `walk_claim_domain_type` and `walk_claim_domain_field` (DDL), `ClaimDomain`, `ClaimDomainRows`,
  and `WalkReach` (`TypeBackingClasses` continues alone under R740's clock).
* `ReachabilityRows` and its semi-naive closure.
* `SdlFactCapture`'s extension merge and the first-wins claim path on doubly-declared coordinates,
  and its registry parameter as the payload's transcription source.
* The `walk_` family header's gate-flip-onto-demand plan, superseded here.

## Coverage

* **The registered agreement anchor** for every touched relation, through
  `FactCaptureAgreementTest`'s mechanical driver, which has no skip list.
* **Pipeline output identity where behaviour is unchanged**, which is everywhere outside the four
  enumerated changes: the emitted sources over the classified corpus are byte-identical, asserted
  by the existing pipeline-tier expectations. The enumerated changes land with their own fixtures:
  a multi-extension fixture pinning applied-directive ordinals, a two-file duplicate fixture
  pinning composition-by-assembly, an assembly-failure fixture pinning the currency status rows
  and the stale-census read, and a gate fixture pinning the conflict population at a coordinate
  where the old and new gates disagree.
* **Error parity per verdict stage**: `graphql_syntax_error` and `graphql_schema_error` keep
  message, location and stage on the fixtures that trip them across the restructure.
* **The naming check per new or reshaped relation** (`fact-model.adoc`): one sentence stating what
  a row asserts, naming no consumer and no producer class.
* **Documentation**: `fact-model.adoc`'s stage pipeline paragraph grows the two traversal stages;
  the `walk_` family header rewrite; R714's census table lands in the architecture docs rather
  than surviving only in a discarded item.

## Provenance

Filed 2026-08-20 from the owner's direction: the `walk_` family is going away as it stands, the
`intent_` views reading it must be fixed, and the gatherer's target architecture is the five-stage
pipeline stated above. Rewritten to Spec the same day after two owner decisions: the change is a
big bang rather than a slow migration (so no shadow or residue scaffolding, and the strangler-era
framing of the "gate-flip follow-up" is dropped), and R714 is absorbed (under a big bang its
deliverable is exactly stage 4, leaving it no independent success criterion; the R678 discard in
`roadmap/changelog.md` states the test this applies). The absence-means-one-thing rule is the
owner's observation, sharpened: frequent anti-joins are a model smell indicating an uncaptured
fact, so absence must be reserved for a single meaning and verdicts a producer already computed
must land as rows.
