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

The SDL capture load is restructured into a four-stage pipeline whose last stage reads the
assembled `GraphQLSchema`: the composed census transcribes from assembly instead of the uncomposed
registry (absorbing R714, discarded in this item's favour, see `roadmap/changelog.md`). The
authored-claim conflict detection becomes total over authored claims, its consumers apply their
own population joins, and the `walk_` membership gate leaves the `intent_` stratum with its two
relations deleted. One change, landed as a big bang: no shadow scaffolding, no strangler
increments, and every place the new derivation differs from the walk's behaviour is decided from
requirements rather than transcribed from the legacy code. A principles pass revised the filed
architecture in two places, recorded under Provenance: the domain derivation stays relational
rather than becoming a fifth traversal stage, and the domain gate is deleted rather than
re-pointed.

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
* **The store holds the walk's answer beside its own honest one.** `intent_type_domain` already
  derives the classification domain the corrected way: a SQL closure over captured facts
  (`ReachabilityRows`), seeded from store relations (`intent_node_type`,
  `graphql_root_operation`, `graphitron_federation_key`), a two-corpus rule stated where two
  corpora meet. The `walk_` membership grains transcribe the walk's registries saying nearly the
  same thing, and exist only to feed the gate above, so they retire with it. The Java twin
  (`SchemaReachability`) remains the classifier's own input and dies with the walk under R682.

## The four stages

The gatherer becomes a pipeline of judging stages, each keeping what survived it, per the
fact model's own law that a stage's refusal never cancels the next stage
(`docs/architecture/explanation/fact-model.adoc`):

[cols="1,2,3"]
|===
| Stage | Unit | Owns

| 1. Per-file parse
| one file
| each GraphQL file parsed into its own `TypeDefinitionRegistry` and ingested: source membership,
  `graphql_syntax_error`, and the per-site declaration facts, which carry the file's own cadence
  and therefore survive any later stage's refusal

| 2. Combined registry
| the file set
| all files loaded into a single `TypeDefinitionRegistry`; what the registry refuses emits
  validation-error facts (`graphql_schema_error` at stage `REGISTRY`)

| 3. Assembly
| the whole schema
| the single registry assembled into a `GraphQLSchema`; failure to assemble emits validation-error
  facts (`graphql_schema_error` at stage `ASSEMBLY`)

| 4. Full-schema traversal
| the assembled schema
| a depth-first traversal over the full assembled schema populates the composed census: the
  coordinate-keyed relations, primary key the graph name plus the coordinate, holding what
  composition adds over the per-site declarations
|===

Stages 1 through 3 are the three judging censuses `fact-model.adoc` already documents; stage 4 is
new to the documentation, and writing the staged pipeline into the architecture docs is a
deliverable. Change tempo follows the unit: editing one file invalidates its own stage-1 rows and
the whole of stages 2 through 4. Throughout, the graphql-java objects (`TypeDefinitionRegistry`,
`GraphQLSchema`, the traversal's elements) stay inside the capture collaborators, which are the
only classes permitted to hold them; the staging changes what capture reads, not where the
containment boundary sits.

## The domain derivation is not a fifth stage

The item was filed with a fifth stage: a depth-first traversal rooted at the operation roots plus
the node and entity types (the traversal `SchemaReachability` drives for the classifier today),
populating the reachability facts. It is withdrawn, for two reasons that survive scrutiny better
than the filed shape did.

First, the seeds read two corpora. Nodehood is not an SDL fact: `NodeDeclaration` conjoins the
`@table` binding with catalog metadata, which is why `ReachabilityRows` seeds from
`intent_node_type` rather than asking Java. A traversal seeded on nodehood inside the SDL gatherer
would put a catalog read back into a load whose contract is one corpus
(`FactCapture.capture`'s javadoc: nothing may add a second catalog-shaped input), and it could not
run until after `CatalogFactCapture` anyway, which is to say it was never a stage of the SDL
pipeline; it is a post-flush derivation, which is what it already is today.

Second, after stage 4 the captured edges are the composed ones, so the one argument for reading
the assembled object (that the SQL closure reimplements composition) evaporates in the same change
that would act on it. What would remain is a rule moved out of SQL into an opaque Java assertion,
which is backwards: the fact model wants the rule stated relationally and materialization to be
the implementation detail (`intent_type_domain` is already the sanctioned shape, a table populated
from a stated rule at capture cadence, with a convergence guard).

So the derivation stays where and what it is, and the filed instinct that the gatherer has four
stages was right. What the derivation gains here is one honesty improvement: the
survivor-directive seed arm currently binds `DeclaredDirectives.names()` as a query parameter,
leaving the relation not self-describing from the DDL alone; the store already carries the
discriminator (the bundled directive definitions are rows whose `source_name` names the shipped
`directives.graphqls` resource), so the seed becomes a function of captured rows and the Java
constant leaves the derivation.

## Strategy: big bang, semantics from requirements

The restructure lands as one change. No differential relations, no shadow tests, no residue
records, no transitional double-writes; R740's doctrine (a difference from the legacy behaviour is
either a legacy bug the change fixes or a mistake, and neither earns scaffolding) applies from the
first commit. Where the new derivation's population differs from the walk's, the difference is
decided from what the requirement says and enumerated under "Deliberate behaviour changes" below,
never smuggled in as fidelity to the old code. Legacy vocabulary (the walk's registries, its
tombstones, its hand-assembled domains, its output-only recorded set) does not travel into the new
relations' semantics. The purity claim is scoped to what this item touches: the authored-claim
views' per-arm position masks are also walk transcriptions (hardcoded root-name literals and
per-position gates, as `intent_authored_field_claim`'s own comment records), they shape the
conflict population too, and they are deliberately out of scope here (see below), so the
enumerated corpus diff must attribute each difference to the right cause.

## Design rule: absence means exactly one thing

`fact-model.adoc` already states the rule ("Absence in a derived relation needs a stated meaning",
with the override and partial-relation cases carved out), so this item cites it rather than
restating it, and adds only the delta it relies on: the composed census and the domain derivation
commit inside one capture transaction, so `intent_type_domain` is total over that census by
construction, absence there means "not in the classification domain" and nothing else, and both a
consumer's population join and the "captured but not in the domain" anti-join are sound reads. The
owner's observation that prompted this section stands as a review heuristic for the DDL work:
frequent anti-joins are a model smell, absence being asked to carry a verdict some producer
computed and dropped; when a rule wants an anti-join, first ask whether the missing fact should be
captured.

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
* **Composed versus written is decided per relation, not per stage.** The written form is the
  stratum-one transcription and stays verbatim, per-site, at its source's cadence: the per-site
  declaration facts are stage 1's, so an assembly refusal cannot cost them (the stage-pipeline
  law again: a source that will not parse costs its own declarations and no others). What
  composition adds (the merged effective field set, a resolved default, a directive argument
  filled from its definition) is either a view over the transcription (an argument default is
  deliberately one join away today, and filling it in at capture would store a derivation where a
  view answers) or its own coordinate-keyed relation whose comment owns why it is not a view.
  Under this split `graphql_duplicate_declaration` keeps a producer: the quarantine of what the
  primary path declines belongs to the stage that sees the losing site, decided below.
* **What this deletes.** Capture's own extension merge, and with it the first-wins claim on a
  coordinate two files declare. A duplicate is then either graphql-java's merge or graphql-java's
  refusal, and the refusal is already a fact in `graphql_schema_error`.
* **Decisions to make in the open, not discover.** Introspection types: assembly adds `__Schema`
  and friends, the current census holds the five built-in scalars and no introspection types;
  filter them or admit them, decided deliberately, together with declaration-site attribution for
  composed elements that have no authored site. Ordinal stability: every merge-ordered ordinal
  family the schema gate requires dense (`graphql_type_declaration.merge_ordinal`; the field,
  argument, enum-value and union-member ordinals; the directive ordinals, where
  `graphql_type_directive.ordinal` is part of the key), each pinned by a multi-extension fixture
  rather than assumed of graphql-java's iteration order. The duplicate quarantine's producer under
  the composed/written split. Which relations are composed-only and which stay written, relation
  by relation.
* **The availability cliff shrinks, and what remains is provenance rather than a flag.** Under the
  split above only the composed-only relations sit behind assembly, which is the population that
  genuinely cannot exist without it. For those, a failed assembly leaves the previous composed
  generation in place and the store says so by provenance: one row per graph per composed
  generation, carrying the source-stamp set the last successful assembly stood on, with currency
  derived by comparing those stamps to the present ones (`store_source.stamp` exists for exactly
  this comparison; a stored current/stale flag would be a second spelling of facts the store
  already holds). One generation for everything behind the cliff, since it all commits in one
  transaction over one assembled object. This deliberately re-opens the freshness axis
  `fact-model.adoc` records as retired: the restructure re-creates the withholding that axis
  described, for a new reader (the composed census's consumers), so it is argued here as a
  re-opening with the reader named, not as continuity.

## The domain relation keeps its name and gains a grain answer

`intent_type_domain` is named for its assertion, not for the graph operation, because its seeds
are generator policy rather than neutral schema reachability, and this item keeps that
distinction instead of renaming the concept "reachability" throughout: *in the classification
domain* (policy-seeded, catalog-conjoined, what the emitted surface needs) and *reachable from the
schema's own roots* (SDL-only, what an author means by a dead type) are different questions that
disagree exactly where the policy seeds bite. R319's unreached-type warning asks the second and
must not be handed the first; if it lands, it lands as its own SDL-only relation, out of scope
here.

No field-grain sibling is added: a field coordinate is in the domain exactly when its owning type
is, so the field grain is a join through `graphql_field`, legible as a projection rather than
materialized.

## The gate is deleted, not moved

`intent_authored_claim_conflict` becomes total over the authored claims: no domain join in the
view. Each consumer applies its own population, which is the consumer-split shape the fact model's
provenance section already ships:

* **The build-error surface** (`AuthoredClaimConflicts` into `StoreDetections`) joins
  `intent_type_domain`, type grain directly and field grain through the claim's owning type,
  because only the emitted surface can fail a build.
* **The editor's diagnostic arm** reads the view ungated: an authored contradiction is a
  contradiction wherever it sits, and a type no field reaches is precisely where the author most
  needs the signal.

This follows the item's own design rule to its conclusion: the gate was never a fact of the
conflict, it was one consumer's population filter wearing the view's name, and re-pointing it
would have substituted a defensible population for an indefensible one without asking whether the
filter belongs in the view at all. R740's observation that removing the gate "widens which
coordinates get conflict-checked" becomes a per-consumer statement instead of a global accept-line
move.

The two `walk_` membership grains then have no reader. Their DDL (`walk_claim_domain_type`,
`walk_claim_domain_field`) and their row writer (`ClaimDomainRows`) are deleted in the same
change. Three seams around that deletion are named rather than discovered:

* `ClaimDomain`, the Java projection of the walk's registries, survives this item with exactly one
  consumer left: `DemandShadowTest` diffs the demand relations against `ClaimDomain.of`. Retiring
  that test and this class is R740's scope; this item leaves them a clean pair with no store
  relation behind them.
* `WalkReach` dissolves, and it carries more than the domain rows: `FactCapture.detect` uses its
  absence as the run-mode discriminator (a run with no classified model gets
  `StoreDetections.empty()`), a gate that also governs `ArgmappingProjectionDefects` and
  `ResolvedKeyProjections`. That discriminator is re-expressed as its own typed value rather than
  inherited as a null check.
* `TypeBackingClasses`, `WalkReach`'s other component, keeps a construction path:
  `walk_type_backing_class` stays, is R740's scope, and becomes the family's last resident. The
  family header is rewritten for the one relation that remains, and its proposed flip onto the
  resolved demand relation is superseded by this item.

The demand stratum's relations are untouched. Demand answers "does this coordinate require a
classification verdict", the validator's question; the conflict consumers ask about population,
which is the domain relation's. Under big bang there is no legacy accept line to reproduce.

## Deliberate behaviour changes

Each is the requirement acting as the specification; none ships unnoticed.

1. **The conflict view becomes total, and each consumer's population is its own.** The build-error
   population moves from the walk's registries to the classification domain; the editor's
   population widens to every authored conflict. Coordinates that gain or lose a build error, and
   coordinates the editor newly surfaces, are enumerated on the corpus in the landing commit, with
   each difference argued from the requirement and attributed to its actual cause (the gate, not
   the claim masks that stay in place).
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
* `walk_type_backing_class` and the shadow tests over it, `DemandShadowTest` and `ClaimDomain`
  included: R740, meeting this item at the seams named in the gate section.
* The `graphitron_` decodes moving from AST reads to row reads: R713, downstream of this item's
  stage 4 exactly as it was downstream of R714.
* The unreached-type warning (R319): it asks about SDL-only reachability, which is a different
  question from the classification domain and needs its own relation if it lands; this item only
  keeps the two from being fused.
* The authored-claim views' per-arm position masks: walk transcriptions that also shape the
  conflict population, they re-derive from requirements with the validator half of R682, which is
  also where the demand stratum's walk-transcribed arms and residue holes get the same treatment.

## Retired vocabulary

* `walk_claim_domain_type` and `walk_claim_domain_field` (DDL), `ClaimDomainRows`, and `WalkReach`
  as a pairing (its run-mode discriminator re-expressed as its own typed value;
  `TypeBackingClasses` and `ClaimDomain` continue under R740's clock, per the gate section's
  seams).
* `intent_authored_claim_conflict`'s domain join, replaced by per-consumer population joins.
* `DeclaredDirectives.names()` as the survivor-seed's query-parameter binding in
  `ReachabilityRows`, replaced by the captured `source_name` discriminator.
* `SdlFactCapture`'s extension merge and the first-wins claim path on doubly-declared coordinates,
  and its registry parameter as the composed census's transcription source.
* The `walk_` family header's gate-flip-onto-demand plan, superseded here.

## Coverage

* **The registered agreement anchor** for every touched relation, through
  `FactCaptureAgreementTest`'s mechanical driver, which has no skip list.
* **Pipeline output identity where behaviour is unchanged**, which is everywhere outside the four
  enumerated changes: the emitted sources over the classified corpus are byte-identical, asserted
  by the existing pipeline-tier expectations. The enumerated changes land with their own fixtures:
  a multi-extension fixture pinning every dense ordinal family across base-then-extension merge, a
  two-file duplicate fixture pinning composition-by-assembly and the quarantine's producer, an
  assembly-failure fixture pinning the provenance rows and the stale-generation read, and a
  conflict fixture at a coordinate where the consumers disagree (a type no field reaches carrying
  two claims: the editor surfaces it, the build does not).
* **Error parity per verdict stage**: `graphql_syntax_error` and `graphql_schema_error` keep
  message, location and stage on the fixtures that trip them across the restructure.
* **The naming check per new or reshaped relation** (`fact-model.adoc`): one sentence stating what
  a row asserts, naming no consumer and no producer class.
* **The containment boundary**: the graphql-java objects stay inside the capture collaborators,
  which the principles document's classification-leak rule already names; the restructure's review
  checks it explicitly since the staged design multiplies the places a `GraphQLSchema` is in hand.
* **Documentation**: `fact-model.adoc`'s stage pipeline paragraph grows stage 4 and the
  composed/written split; the `walk_` family header rewrite; R714's census table lands in the
  architecture docs rather than surviving only in a discarded item.

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

A principles-architect pass the same day revised the filed architecture in two places, each by
following the item's own purity rule to its conclusion, and both revisions are the owner's to
overrule at the Spec review. The filed fifth stage (a rooted Java traversal populating
reachability facts) was withdrawn: its seeds read the catalog corpus, so it cannot sit inside the
SDL gatherer without breaching capture's one-catalog rule, and after stage 4 the captured edges
are the composed ones, so the existing SQL closure loses the one disadvantage the traversal was
meant to fix; the gatherer therefore has exactly four stages, and the domain derivation stays a
post-flush relational derivation. And the domain gate was deleted rather than re-pointed: the
detection becomes total over authored claims and each consumer applies its own population join,
per the consumer-split shape the fact model ships. The same pass split composed from written per
relation (so the assembly cliff shrinks to the composed-only relations and the duplicate
quarantine keeps a producer), resolved currency as provenance rows rather than a stored flag,
kept the domain relation named for its assertion instead of renaming it reachability, made the
survivor-directive seed self-describing, named the three seams around `WalkReach`'s dissolution,
and scoped the purity claim so the walk-transcribed claim masks are excluded explicitly rather
than silently.
