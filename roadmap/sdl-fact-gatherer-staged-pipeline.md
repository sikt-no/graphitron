---
id: R743
title: "The SDL fact gatherer becomes a staged pipeline, and the walk_ gate dissolves"
status: In Progress
bucket: architecture
priority: 3
theme: classification-model
depends-on: []
created: 2026-08-20
last-updated: 2026-08-20
---

# The SDL fact gatherer becomes a staged pipeline, and the walk_ gate dissolves

The SDL capture load is restructured into a five-stage pipeline, with assembly becoming the
gatherer's own stage rather than a verdict a caller hands it. The `graphql_` family's
coordinate-keyed account of what the schema declares with extensions merged gets its own anchor
relations, pinned against graphql-java's composition instead of reimplementing it, which is R714's
question answered (that item is discarded in this item's favour, see `roadmap/changelog.md`). A
rooted traversal writes the classification domain, the set of types the generator intends to
classify, from SDL-only seeds. And the authored-claim conflict detection, the rule that two
classifying directives may not claim one coordinate, becomes total over authored claims with each
consumer applying its own population join, so the `walk_` membership gate leaves the `intent_`
stratum and its two relations are deleted. One change, landed as a big bang: no shadow
scaffolding, no strangler increments, and every place the new derivation differs from the walk's
behaviour is decided from requirements rather than transcribed from the legacy code.

Stage 4 landed differently from how it was filed, and the difference is load-bearing for reading
the rest of this item. It was filed as a full-schema traversal transcribing a composed census from
the assembled schema; it landed as an ownership change to the family's keys, which answers the same
question at an earlier cadence and at no availability cost. "Implementation finding" below carries
the argument, and the sections between here and there are marked where the finding supersedes them.

## Problem

Three defects, one restructure:

* **The `intent_` stratum reads the retiring walk.** The classification walk is the legacy Java
  pass that turns the schema into the sealed leaf model, and it is going away: R682
  (`roadmap/planners-read-facts-emitters-read-commands.md`) carries its deletion as the terminal
  deliverable. `intent_authored_claim_conflict` joins `walk_claim_domain_type` and
  `walk_claim_domain_field`, transcriptions of that walk's registries, so conflict minting stays
  on the population the legacy detection reached; when the walk goes, those tables lose their
  writer and the view's population silently empties. R740
  (`roadmap/retire-oracle-diff-shadow-tests.md`) declined to schedule the flip for want of a
  reason; the walk's retirement is the reason.
* **The composed census is a reimplementation.** The `graphql_` payload is written from the
  loader's pre-synthesis registry snapshot, a `TypeDefinitionRegistry`, which is the uncomposed
  census: base declarations and extensions are separate entries, and `SdlFactCapture` merges them
  itself, deciding collisions through `FactSink.claim`'s first-wins. That merge is graphql-java's
  job and graphql-java already does it during assembly. This was R714's whole content and it lands
  here as stage 4.
* **The domain relation's rule is contested and restated.** `intent_type_domain` holds the
  classification domain's type members, and its writer disagrees with its own DDL comment about
  the seed rule: the comment records SDL-only seeds (calling the node arm an over-approximation of
  node inference), while the writer (`ReachabilityRows`) narrowed nodehood to `intent_node_type`'s
  cross-corpus conjunction, the walk's accept line. The writer also re-enumerates graphql-java's
  descent semantics as SQL `UNION` arms that must track every SDL feature by hand. Beside it, the
  `walk_` membership grains transcribe the walk's registries saying nearly the same thing,
  existing only to feed the gate above, so they retire with it. The classifier's own Java twin
  (`SchemaReachability`) remains the walk's input and dies with the walk under R682.

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

| 4. Coordinate-keyed census
| the file set
| the merged effective element set: one row per coordinate in the `graphql_*_coordinate` anchors,
  the losing occurrence of a duplicated one quarantined in `graphql_duplicate_declaration`. Filed
  as a full-schema traversal transcribing this from the assembled schema; landed as the anchor
  family, per "Implementation finding" below

| 5. Rooted traversal
| the assembled schema
| a depth-first traversal from the SDL-stated seeds (below) writes the classification domain,
  `intent_type_domain`, in the same transaction as stage 4
|===

Stages 1 through 3 are the three judging censuses `fact-model.adoc` already documents; stages 4
and 5 are new to the documentation, and writing the staged pipeline into the architecture docs is
a deliverable. Change tempo follows the unit: editing one file invalidates its own stage-1 rows
and the whole of stages 2 through 5. Throughout, the graphql-java objects
(`TypeDefinitionRegistry`, `GraphQLSchema`, the traversal's elements) stay inside the capture
collaborators, which are the only classes permitted to hold them; the staging changes what capture
reads, not where the containment boundary sits.

## What landed, and where to check it

Three implementation commits plus the gate's rework pass, in order:

[cols="1,2,3"]
|===
| Commit | Carries | Reviewable against

| `227a199`
| the gate deletion: `intent_authored_claim_conflict` total, per-consumer population joins, the two
  `walk_` membership grains and `ClaimDomainRows` deleted, `WalkReach` dissolved into
  `ClassifiedRun`
| behaviour change 1; the gate section and its three named seams

| `f8b7dab`
| stage 3 as the gatherer's own stage with the `ASSEMBLY` verdict moved to the pre-synthesis
  registry, and stage 5 as a rooted traversal (`ClassificationDomainCapture`) replacing
  `ReachabilityRows`
| behaviour changes 3, 5 and 6; the stage 5 section

| `bfa41ab`
| the four `graphql_*_coordinate` anchors, the whole foreign-key web re-pointed onto them,
  `SdlCoordinates`, and the census pin against graphql-java
| the implementation finding below

| the rework pass
| the retirement sweep closed, the census pin extended to the fourth grain and every merge-ordered
  ordinal family, the pre-synthesis split no longer dropping the author's facts on graphitron's own
  defect, and the seed scan widened to interface carriers
| "The gate's rework pass" at the end of this item
|===

Stages 1 and 2 are unchanged: they were already the gatherer's, and the item touches them only in
the documentation. Two of the filed deliverables are deliberately not in the tree, each marked at
its own section: the composed census as a transcription from assembly, and the availability-cliff
provenance rows. Behaviour changes 2 and 4 fall with them.

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
conflict population too, and they are deliberately out of scope here (see "Out of scope"), so the
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

Absorbed from R714. Filed as below; two of these bullets are superseded by the implementation
finding and say so where they sit. What survived unchanged is the registry question (which registry
gets assembled, and the verdict that follows from it) and the composed-versus-written split, which
is the rule the anchor family then let stage 4 apply without an availability cost.

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
  primary path declines belongs to the stage that sees the losing site, enumerated below as a
  decision to make.
* **What this deletes.** *(Superseded: the merge stays, and is pinned instead. See "What stage 4
  becomes under the anchor family".)* Capture's own extension merge, and with it the first-wins
  claim on a coordinate two files declare. A duplicate is then either graphql-java's merge or
  graphql-java's refusal, and the refusal is already a fact in `graphql_schema_error`.
* **Decisions to make in the open, not discover.** *(All four are settled; the finding below says
  how. Two dropped out with the composed census, the ordinal question landed pinned by value rather
  than by density, and the composed-versus-written line is drawn at the anchors.)* Introspection
  types: assembly adds `__Schema`
  and friends, the current census holds the five built-in scalars and no introspection types;
  filter them or admit them, decided deliberately, together with declaration-site attribution for
  composed elements that have no authored site. Ordinal stability: every merge-ordered ordinal
  family the schema gate requires dense (`graphql_type_declaration.merge_ordinal`; the field,
  argument, enum-value and union-member ordinals; the directive ordinals, where
  `graphql_type_directive.ordinal` is part of the key), each pinned by a multi-extension fixture
  rather than assumed of graphql-java's iteration order. The duplicate quarantine's producer under
  the composed/written split. Which relations are composed-only and which stay written, relation
  by relation.
* **The availability cliff shrinks, and what remains is provenance rather than a flag.**
  *(Superseded: no relation sits behind the cliff, so there is no generation to retain and no
  provenance to stamp. The finding below carries why retention was structurally unavailable and
  what replaced the need for it. The retired freshness axis stays retired.)* Under the
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

## Stage 5: the classification domain

The rooted traversal writes `intent_type_domain`, the classification domain's type members, in the
same transaction as stage 4.

* **Every seed is an SDL fact, readable off the assembled schema:** the operation roots, the
  `@node` carriers, the `implements Node` types, the `@key` carriers, and the argument types of
  directive definitions that survive into the emitted schema, where the survivor discriminator is
  the definition's own source (the bundled `directives.graphqls` resource name on its AST pointer)
  rather than `DeclaredDirectives.names()` bound as a constant. The node seed is `implements Node`
  alone, not `@table` conjoined with `implements Node`, settled at the owner's direction: inferred
  nodehood requires the declaration to take effect at all, so the declaration alone yields the
  relevant superset, and both conjuncts it drops (`@table` presence, well-formed node metadata on
  the bound table) decide what nodehood means, never whether the author declared it. Seeding is
  monotone, so the superset answers the membership question correctly, and the superset is
  deliberate: later work digs into what each member's nodehood amounts to, on captured facts. This
  is what makes stage 5 a one-corpus producer and genuinely a stage of the SDL gatherer, running
  before and independently of the catalog capture.
* **The traversal, not the SQL closure, is the producer**, on the same ground as stage 4: the
  descent rule (field targets, argument types, input-object fields, union members, `implements` in
  both directions with the interface-to-implementor edge from
  `GraphQLSchema.getImplementations`) is graphql-java's own child semantics, and the closure's
  `UNION` arms restate it edge kind by edge kind and must track every SDL feature by hand. The
  fact model's stratum test is about what a relation's rows are a function of, never what program
  computes them, and the rows stay a function of the captured sources. `ReachabilityRows` retires;
  `intent_type_domain` remains the destination, its DDL comment rewritten for its new writer and
  the now-decided seed rule, which ends the comment-versus-writer disagreement the problem
  statement names.

## The domain relation keeps its name, and the field grain is a join

`intent_type_domain` is named for its assertion, not for the graph operation, because its seeds
are generator policy rather than neutral schema reachability, and this item keeps that
distinction instead of renaming the concept "reachability" throughout: *in the classification
domain* (policy-seeded: a node or entity type no field returns is a member on purpose) and
*reachable from the schema's own roots* (roots only, what an author means by a dead type) are
different questions that disagree exactly where the policy seeds bite, and both are SDL-only after
stage 5's seed correction. R319's unreached-type warning asks the second and must not be handed
the first; if it lands, it lands as its own roots-only relation, out of scope here.

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
change. Three seams around that deletion are named rather than discovered. The seam inventory
describes end states, not the present tree: today `ClaimDomain` or `WalkReach` is also constructed
by `AuthoredClaimConflictsTest`, `DiagnosticFactsTest`, `FactCaptureAgreementTest` (the `walk_`
partition-lifecycle and oracle-content tests) and `TypeBackingClassesTest`, each of which this
item re-points or updates as its subject moves, so the implementer's grep does not finish at
`DemandShadowTest`.

* `ClaimDomain`, the Java projection of the walk's registries, survives this item with exactly one
  consumer left: `DemandShadowTest`'s demand arms diff the demand relations against
  `ClaimDomain.of`. That test's *domain* arm does not survive: it asserts `intent_type_domain`
  equals `SchemaReachability`'s reachable set exactly, and this item deliberately changes that
  relation's writer and population, so the walk stops being its specification. The arm is deleted
  here, and `intent_type_domain`'s registered agreement anchor re-points to a specification test
  in the seeded style (hand-written expectations over captured rows, the seed fixtures riding it).
  A named-residue subtraction instead of deletion was considered and refused: the excess is not a
  closed population (any author can mint a member by declaring `implements Node`), and a residue
  record is the shape the Strategy section forbids. Retiring the demand arms and `ClaimDomain`
  itself stays R740's scope; this item leaves them a pure walk-versus-demand diff with no `walk_`
  relation behind it.
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
2. **Withdrawn: duplicate composition becomes graphql-java's.** No such change shipped. Capture's
   first-wins still merges and still quarantines the losing site in
   `graphql_duplicate_declaration`, and the equivalence with graphql-java's composition is asserted
   rather than assumed. Numbering kept so the citations elsewhere in this item stay valid.
3. **`ASSEMBLY` verdicts judge the pre-synthesis registry**, so an author is no longer blamed for
   a failure graphitron's own rewrite caused.
4. **Withdrawn: the census's population question (introspection types) is settled openly.** The
   question dissolved with the composed census: nothing transcribes from the assembled schema, so
   no introspection type is ever offered to the census and none needs filtering. Numbering kept for
   the same reason as 2.
5. **The domain widens at the SDL-only node seeds.** A type that declares `implements Node` and is
   reached by no field is silently pruned today unless the full inference conjunction holds; under
   stage 5's seed rule it is a domain member whether it binds no `@table` at all or a table whose
   node metadata is absent or defective, so it gains diagnostics instead of vanishing, which is
   what the author's declared contract is owed. The demand reductions
   (`intent_resolved_field_demand`, `intent_resolved_type_demand`) join the domain, so their
   populations widen with it; both diffs are enumerated on the corpus in the landing commit.
6. **The classification domain is empty when the registry does not assemble.** Not filed, and found
   while landing stage 5: the SQL closure answered from captured rows and so had a population on a
   refused read, while a traversal over the assembled schema has no schema to walk. This is the
   composed-versus-written split doing what it says (only what genuinely needs assembly sits behind
   it), and the emptiness is read together with the `ASSEMBLY` verdict rather than alone. Its
   readers are the demand reductions and the conflict detection's build-error population, and the
   latter runs only on a classified pass, which an assembly refusal has already ruled out.
   `ClassificationDomainTest` carries the cliff as its own case.

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
* `ReachabilityRows` and its semi-naive closure, replaced by stage 5's traversal; with it,
  `DeclaredDirectives.names()` as the survivor-seed's query-parameter binding, replaced by the
  definition-source discriminator.
* Nothing from `SdlFactCapture`'s merge. Filed as a retirement of its extension merge, the
  first-wins claim path on doubly-declared coordinates, and its registry parameter; all three stay,
  and the sweep must not go looking for them. What did move is where the claim is taken:
  `SdlCoordinates` owns every coordinate's first-wins claim, and taking one directly off `FactSink`
  at an SDL coordinate is the retired spelling.
* The `walk_` family header's gate-flip-onto-demand plan, superseded here.
* `intent_node_type`'s role as the domain's node seed: `ReachabilityRows`' javadoc conjoins it
  deliberately, and after stage 5 the domain seeds on the SDL declarations instead. The relation
  itself stays, and every other reader of nodehood is unaffected.

## Coverage

* **The registered agreement anchor** for every touched relation, through
  `FactCaptureAgreementTest`'s mechanical driver, which has no skip list.
* **Pipeline output identity where behaviour is unchanged**, which is everywhere outside the
  enumerated changes: the emitted sources over the classified corpus are byte-identical, asserted
  by the existing pipeline-tier expectations. The enumerated changes land with their own fixtures:
  a multi-extension fixture pinning every merge-ordered ordinal family by value across
  base-then-extension merge and the coordinate set at all four grains against graphql-java's
  composed one (`SdlCoordinateCensusTest`, which subsumes the filed two-file duplicate fixture: the
  merge it pins is the same one the duplicate case would have exercised). All five counters
  `ElementOrdinals` holds are merge-ordered, being per type and carried across the declaration
  sites, so all five are pinned, each on a shape written out of document order: the field, argument,
  enum-value and union-member ordinals, and the per-name type-directive ordinals. Beside it, an
  assembly-failure case pinning the domain's emptiness and the declaration facts' survival, a
  conflict fixture at a coordinate where the consumers disagree (a type no field reaches carrying
  two claims: the editor surfaces it, the build does not), and seed fixtures pinning the widened
  domain (an `implements Node` type with no `@table`, and one over defective node metadata, each
  reached by no field: domain members with diagnostics rather than silent prunes, plus an interface
  that is the sole carrier of its own declaration).
* **Error parity per verdict stage**: `graphql_syntax_error` and `graphql_schema_error` keep
  message, location and stage on the fixtures that trip them across the restructure.
* **The naming check per new or reshaped relation** (`fact-model.adoc`): one sentence stating what
  a row asserts, naming no consumer and no producer class.
* **The containment boundary**: at capture the graphql-java objects are read down to rows and do not
  cross into the store. That rule was written only in the `principles-architect` agent brief (whose
  classification-leaks check names graphql-java schema types beside `Table<?>` and
  `java.lang.reflect.Type`); `development-principles.adoc`'s capture-boundary section named the
  catalog and reflection types and not the graphql-java ones, so writing them into that section is
  a deliverable here rather than a citation. The restructure's review checks the boundary
  explicitly, since the staged design multiplies the places a `GraphQLSchema` is in hand. Both texts
  state the rule at altitude rather than as a roster of permitted holders: the review pass found the
  roster both wrong (forty-five files in the generator hold one of these types) and forbidden by the
  same document's rule against unguarded inventories, so the rule is structural at capture and a
  question of direction off it, with grep recipes instead of a list.
* **Documentation**: `fact-model.adoc`'s stage pipeline paragraph is revised, not merely grown:
  its claim that only the first stage produces declarations and the later stages contribute
  nothing but verdicts stops being the whole truth once stage 4 transcribes the composed census,
  and the paragraph gains stages 4 and 5 and the composed/written split. Also the `walk_` family
  header rewrite, and R714's census table landing in the architecture docs rather than surviving
  only in a discarded item.

## Implementation finding: the anchor tables become ours

Landed so far: the gate deletion (the conflict relation total, per-consumer populations, the two
`walk_` membership grains and `ClaimDomainRows` deleted, `WalkReach` dissolved into
`ClassifiedRun`), stage 3 as the gatherer's own stage with the `ASSEMBLY` verdict moved to the
pre-synthesis registry, and stage 5 as a rooted traversal replacing `ReachabilityRows`. Stage 4's
mitigation for the availability cliff turned out to be structurally unavailable, and the way out
reshapes the `graphql_` family's keys.

The problem is the FK web inside the `graphql_` family. `graphql_type_declaration` references
`graphql_type`, and `graphql_field` references both `graphql_type` and the declaration site it hangs
off; the element relations are the same shape. So the composed half and the written half cannot sit
on two clocks:

* Retaining the previous composed generation while the sites are rewritten makes the *delete* of a
  moved site row violate a retained `graphql_field` row's foreign key.
* Retaining `graphql_type` while `graphql_type_declaration` is rewritten leaves a newly declared
  type's site row with no parent.

Retention therefore needs those foreign keys dropped, which is the guarantee the fact model prizes
most in its largest family. Without retention, the composed relations are empty whenever assembly
refuses, and that is the one thing the model's own law forbids: a single dangling type reference
mid-edit would blank `graphql_field` and the whole `graphitron_` decode family, which is exactly the
"one freshly broken file blanks every fact about every file beside it" failure `SdlVerdicts` names.
This is not hypothetical for the editor path: the dev loop's catalog refresh already captures on a
refused read *specifically* so the author keeps answers while the buffer is broken.

The owner settled it with an option none of the three above named: **take ownership of the anchor
tables.** A `graphql_*_coordinate` family, written by the gatherer, holds each SDL coordinate's
existence and nothing else, and every foreign key naming an SDL coordinate names one of those
relations instead of an attribute relation. The attribute relations beside them (`graphql_type`,
`graphql_field`, `graphql_argument`, `graphql_enum_value`) are then referenced by nothing, so any of
them can change hands, be withheld or drain without the reference web following. The cliff does not
need retention because nothing hangs off what falls.

This is a better answer than the first option rather than a variant of it. Option 1 bought
availability by dropping foreign keys, trading a structural guarantee for a cadence; the anchor
family keeps every foreign key and satisfies each of them at the earliest cadence any SDL fact has,
so a coordinate's existence is settled by the per-file parse and can never be conditional on a stage
it owes nothing to. The reason the original design could not do this is that it conflated two
assertions in one relation: `graphql_type` asserted both that a name exists and what its kind and
description are, and the reference web had no choice but to anchor on the pair.

Landed with it: the four coordinate relations, the whole FK web re-pointed onto them (52 foreign
keys), `SdlCoordinates` as the single producer of an anchor row and the owner of the first-wins claim
on every coordinate, and a both-directions agreement anchor in `FactCaptureAgreementTest` for the
invariant no constraint can see (a foreign key stops an attribute row with no anchor; nothing stops
an anchor with no attributes).

### What stage 4 becomes under the anchor family

The anchor question being settled structurally changes what is left to decide, and shrinks it. The
element attribute relations stay written, at the per-site cadence, because their readers cannot
afford the cliff: an author mid-edit needs a field's type expression to get a completion, and the
coordinate alone does not carry one. What composition genuinely adds beyond those relations is then
added per relation when a consumer needs it, at no foreign-key cost, which is the freedom the anchor
family buys.

That leaves capture's extension merge in place, and it should be: the merge is what produces the
coordinate set and the ordinals in the first place. The correctness worry stage 4 raised against it
(our merge versus graphql-java's) is answered by pinning rather than by deletion, which is the
stronger form anyway: on any schema that assembles, the claimed coordinate set must equal the
assembled schema's effective set, as an `EQUALITY` agreement anchor. A schema that does not assemble
has no effective set to compare against, and its refusal is already a row in `graphql_schema_error`.

Landed with it: `SdlCoordinateCensusTest`, which pins each grain of capture's merge against
graphql-java's composition on a fixture whose base definitions and extensions are deliberately out
of document order, and pins the merge order by ordinal value rather than by density (density passes
on a walk that numbers sites in the order it meets them; only the values catch a base definition
numbered 1 because an extension was written above it). It also records the one place the two
censuses genuinely differ: the registry hands over all five specified scalars whether or not the
document names them, and assembly keeps only the referenced ones, so the equality arms compare the
declared types and a separate case states the scalar difference. R714's census table lands in
`fact-model.adoc` as the four anchors and what hangs off each.

Two owed items drop out rather than being deferred: the introspection-population decision (nothing
composes the census, so no introspection type is ever offered to it) and the duplicate quarantine's
producer (the walk still sees the losing site, so the quarantine keeps the producer it has).

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
following the item's own purity rule to its conclusion. The domain gate was deleted rather than
re-pointed: the detection becomes total over authored claims and each consumer applies its own
population join, per the consumer-split shape the fact model ships; that revision stands. The
same pass split composed from written per relation (so the assembly cliff shrinks to the
composed-only relations and the duplicate quarantine keeps a producer), resolved currency as
provenance rows rather than a stored flag, kept the domain relation named for its assertion
instead of renaming it reachability, named the three seams around `WalkReach`'s dissolution, and
scoped the purity claim so the walk-transcribed claim masks are excluded explicitly rather than
silently.

The pass's other revision withdrew the filed fifth stage, on the argument that its node seeds read
the catalog corpus (`NodeDeclaration`'s metadata conjunction) and so the traversal could neither
sit inside the SDL gatherer nor beat the SQL closure once stage 4 composes the captured edges. The
owner overruled it by correcting the premise rather than rejecting the principle: `implements
Node` is required for inferred nodehood to take effect, so the SDL-only seed rule
over-approximates the node seed set, over-approximation is monotone-safe for domain membership,
and the catalog conjunct was never load-bearing for the question; the traversal is thereby a
one-corpus producer and the stage is reinstated with SDL-only seeds, the closure's remaining role
(restating graphql-java's descent semantics as hand-tracked `UNION` arms) being the same
reimplementation stage 4 deletes. `intent_type_domain`'s own comment had recorded the SDL-only
seed shape as an over-approximation whose excess the shadow agreement asserted empty; under big
bang the excess becomes deliberate membership, enumerated as behaviour change 5.

An independent Spec review the same day surfaced two blocking problems, resolved at the owner's
direction, plus five corrections applied as given (the containment rule as a doc deliverable, the
fact-model revision named, the seam inventory's present-tense constructors, `intent_node_type`'s
seed role retired, a forward-reference nit). The first problem: `DemandShadowTest`'s domain arm
asserts exact walk agreement on `intent_type_domain`, which behaviour change 5 deliberately
breaks, and the spec assigned the whole test to R740 without saying what the implementer does
about the arm. Resolved by deleting the arm here and re-pointing the relation's anchor (the
review's option b); a named-residue subtraction was refused as an unclosed population and the
forbidden residue shape, and pulling the whole retirement in was refused as demand-stratum scope.
The second: the seed rule read two ways on the `@table` conjunct. Settled as `implements Node`
alone, the owner's original formulation taken at its word: the declaration yields the relevant
superset, later work digs into what each member's nodehood amounts to, and behaviour change 5 now
enumerates both widenings (no `@table` at all, and `@table` over absent or defective metadata).


## The gate's rework pass

An independent In Review to Done review passed the delivery's architecture, the build and the
code-string and user-facing-doc checks, and held it on two things: the retirement sweep did not
pass, and the census pin covered less than the Coverage section and `fact-model.adoc` claimed of it.
Both are closed here, along with three of the review's four non-blocking observations. Nothing that
had landed was reversed.

**The sweep now passes.** `WalkReach` left one test name behind and three item bodies were still
reasoning over the deleted membership relations, two of them saying things that had become false
rather than merely dated:

* `FactCaptureAgreementTest`'s lifecycle anchor for the `walk_` family is named for the binding
  grain it actually asserts over, the family having one relation left.
* R682 (`roadmap/planners-read-facts-emitters-read-commands.md`) had the domain gate as pending work
  and told its implementer that what remained for the terminal deletion was "`WalkReach` and its
  components". The first bullet is rewritten as the discharged case it is; the family-boundary
  bullet now names what is actually left, which is nothing `walk_`-shaped, R740 owning the rest.
* R740 (`roadmap/retire-oracle-diff-shadow-tests.md`) carved the two relations out of its scope as a
  live gate that would move only on an author-facing reason. The carve-out is closed in place, and
  the drainage paragraph gains the family's new shape and the `ClaimDomain` value R743 left it.
* The two `roadmap/nodeid-effective-at-every-coordinate.md` mentions already read as deleted-past and
  are correct as they stand. The `roadmap/changelog.md` hits are frozen records of what shipped when
  it shipped and are deliberately untouched.

**The census pin now covers what it claims.** The pin is what this item substituted for deleting
capture's merge, so a partial pin was a partial substitution:

* `SdlCoordinateCensusTest` gains the argument grain, making all four anchors compared against
  graphql-java's composition. It is a real arm rather than a corollary of the field one: capture
  numbers a type's arguments with one type-wide counter running across the declaration sites, so the
  grain has its own way to disagree.
* Every merge-ordered ordinal family is now pinned by value. `ElementOrdinals` holds five counters
  per type and carries each across the sites, so all five are merge-ordered; the fixture pinned three
  of them and carried no union at all. `MERGED` gains a union extended from above its own base, a
  base field with arguments beside an extension field with one, and a repeatable type directive
  applied on the base and on two extensions, one written above the base. The type-directive family
  was already pinned by value by `MacroCaptureTest`; what was missing there and is added here is the
  out-of-order case, which is the whole point of pinning by value rather than by density.
* `fact-model.adoc`'s `*Enforced by:*` line said "at each grain" while one grain was uncompared. It
  now says four grains and every ordinal family, and states why the families are merge-ordered.

**Three observations acted on.**

* The pre-synthesis assembly split dropped the whole fact capture on one narrow path: where the
  pre-synthesis registry assembles and the post-synthesis one does not, neither refusal branch fired
  and the pipeline assembly threw before anything was written. That is graphitron's own rewrite
  breaking a document the author wrote correctly, and withholding their facts for it is the failure
  this item argues against throughout. `assembleForPipeline` becomes `assemblyForPipeline`, returning
  the outcome rather than assembling-or-throwing, and the caller captures from the pre-synthesis
  assembly before failing. The build still fails as loudly, and still records no `ASSEMBLY` verdict
  row against the author, which was the deliberate half.
* The seed scan was narrowed to object types, where two of the three declaration arms are legal on an
  interface: federation's `@key` is defined `on OBJECT | INTERFACE`, and an interface may sit in
  another interface's `implements` clause. It scans implementing types now, keyed off the two
  capabilities the arms need rather than off a concrete kind, with a `ClassificationDomainTest` case
  where the interface is the sole carrier and has no reached implementor. This restores seeds the
  retired closure had (it read `@key` carriers at the type grain) rather than widening past it, so
  behaviour change 5 covers it and it earns no number of its own.
* `development-principles.adoc`'s containment section listed the classes permitted to hold a
  graphql-java schema object. Forty-five files in the generator reference one, so the roster was
  wrong, and it was an unguarded inventory in the document that forbids them. The rule is restated at
  altitude: structural at capture, and off capture a question of direction rather than of a permitted
  holder list, with the grep recipes the earlier text had carried. The `principles-architect` brief's
  classification-leaks check is aligned to the same wording. `DocSizeBudgetTest` caught the first
  attempt at 3613 words against a 3500 budget, which is the gate working: the restatement is now
  shorter than the roster it replaces, at one word under where trunk stood. Naming the graphql-java
  types is still the deliverable it was, and they are named, in the containment sentence as a
  category and in the `*Enforced by:*` grep list concretely.

**One observation withdrawn.** The review called the conflict view's `SELECT DISTINCT ... FROM
intent_authored_type_claim a) c` subqueries leftovers of the dropped join. They are not: the
`DISTINCT` deduplicates repeated classifier-and-trigger pairs so `COUNT(*)` counts distinct claims,
and flattening it would change the predicate. The subqueries stay as they are.
