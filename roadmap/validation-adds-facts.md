---
id: R589
title: "Classification is a relation; validation adds facts"
status: Spec
bucket: architecture
priority: 4
theme: classification-model
depends-on: []
created: 2026-08-04
last-updated: 2026-08-09
---

# Classification is a relation; validation adds facts

A coordinate's classification is modelled as a partial *function*: exactly one classification, or a
tombstone carrying a message. The domain is not a function. Classification produces zero, one, or
several claims on a coordinate, and validation's job is to add a violation fact when the count is
wrong, not to erase the claims. Failure is implemented as *replacement* today, so the facts gathered
on the way to the failure are dropped. The doctrine says the opposite:
`development-principles.adoc` under "Classification and validation gather facts" has validation
"reifying each missing or conflicting fact into a located violation the build acts on later". Adding
a violation, not deleting a classification.

The pivot purifies the pipeline's stages, and each stage comes out simpler than it is today.
**Classification gathers facts from the schema**: classifiers examine coordinates and either claim
them or decline, and the claims land in relations keyed `(coordinate, classifier)`. **Validation
adds derived facts**: a violated key constraint on the claim relations becomes a located violation;
the claims stay. **Planning returns to the single-classification worldview**: the reduced claim
view is single-valued per coordinate on a valid schema, and planning joins it with the gathered
slot facts directly into command records, so no downstream consumer grows a cardinality branch.
Execute/render stays the simplest stage. The count check is not a mechanism; it is a key
constraint, and its violations are validation's derived facts.

One premise of the first draft was wrong, and correcting it makes the item smaller. The additive
violation channel already ships: `GraphitronSchema.diagnostics` carries "build-time validation
findings accumulated instead of demoting a classified verdict to `UnclassifiedType` /
`UnclassifiedField`" (its own javadoc), the validator drains it, and several producers already use
it. The leaf model *can* express "add a violation, keep the facts"; the tombstoning sites are simply
not routed through the channel built for them. The tombstone is a second, redundant violation
channel, one that overwrites facts instead of adding one. `UnclassifiedField` survives only as the
slot value at a coordinate with no resolvable verdict; it stops being the record of what happened.

This *extends* the umbrella data-model item (`coordinate-lowers-to-datafetcher-queryparts`, R333)
rather than merely implementing it: the umbrella's normalised model today has no classification base
relation at all (classification is described as a denormalised view over facts), and this item adds
two, the authored and inferred claim relations. It also sharpens the umbrella's end state: the
commands are the parse targets, planning joins facts directly into them, and today's classification
record hierarchy is the transitional producer surface the umbrella's arm-by-arm migration dissolves.
The amendment is deliberate, is the first slice of the scope below, and is reviewed through this
item's gates; R333 stays in Ready.

## Cardinality: "could not classify" reports two different things

The one-classification slot forces two unrelated situations into the same tombstone, and neither is
what the tombstone says.

**More than one classification is reported as none.** `FieldBuilder.reduceDirectiveConflict` names
the shape in its own javadoc: it "reduces the **classification-claiming** directives present at a
position to **a single verdict**" through a pairwise table. Several directives each claim the
classification; reducing them to one is a policy, and when the policy finds a conflicting pair the
coordinate lands as an `UnclassifiedField`, asserting *zero* classifications where the truth is two
or more. What the two claims *were* is never retained, and in most cases never computed, so the
strongest available description of the defect ("this field claims both a table target and a service
backing, pick one") is unavailable to every view. The participating directive names survive on the
rejection; the classifications they would have produced do not. The machinery is invisible enough
that one of its own rows is dead: the `Composes` verdict (`@routine` with `@splitQuery`) can never
fire, because neither detector list ever passes `@splitQuery`, and the live routine-and-splitQuery
interaction is a conditional conflict minted mid-arm against a resolution result (an empty derived
batch key), not against co-occurrence. The registry already contains this item's key constraint
in embryonic form: `FieldRegistry.classify` detects the same coordinate classified twice and
replaces the second write with a synthetic `UnclassifiedField` whose rejection is a
generator-internal-conflict string, indistinguishable at the view from an author error. Under
keyed claims the two cases separate mechanically: a second claim from the same classifier
violates the base relation's key (a purity bug), claims from different classifiers are the
ordinary conflict rule.

**A failed bind is laundered as a successful one.** `InputField.UnboundField` should be a positive
fact: this field binds no SQL column. Instead it doubles as the demotion target for column-miss, and
the demotion is wrapped in `InputFieldResolution.Resolved` at both construction sites in
`BuildContext`, so the classifier reports success while meaning "no column bound, rejected later
somewhere else". Its javadoc lists three cases under one record, two of which it calls a
schema-author bug, and the discriminator between them is component values: whether `condition` is
present, whether it overrides, and whether the nullable `attemptedColumnName` was filled in. That is
kind-dependent nullability standing in for a fact the model should carry outright, the same smell
the principles doc names when it justifies `EdgeKind` as a label enum ("carries no kind-dependent
nullability"). The consumer then re-derives violation-ness from those components rather than reading
a violation.

## Three layers, and the leak is worst at the seam

**Model layer: five failure carriers, five different answers.**

| Carrier | Retains | Reaches a view as |
|---|---|---|
| `InputField.UnboundField` | parent, name, location, typeName, nonNull, list, condition, **`attemptedColumnName`** | nothing live: the `InputUnbound` arm exists for switch coverage, but `projectFieldClassifications` iterates the output-field index only |
| `ArgumentRef.UnclassifiedArg` | name, typeName, nonNull, list, rejection | nothing (arguments have no classification projection) |
| `GraphitronField.UnclassifiedField` | parent, name, location, **`definition`**, rejection | `FieldClassification.Unclassified(reason)` |
| `GraphitronType.UnclassifiedType` | name, location, rejection | `TypeClassification.Unclassified(reason)` |
| `InputFieldResolution.Unresolved` | fieldName, lookupColumn, prose | joined into one `Rejection.structural` per input type |

**`UnboundField` is right on one axis and wrong on the other.** On retention it is the best carrier
in the model: eight facts, and its javadoc explains why it keeps `attemptedColumnName` at all, so
the later rejection can render a Levenshtein "did you mean" hint. That is half this item's thesis,
discovered once and never generalised. On identity it is the worst, for the reasons above: it
doubles as a demotion target, so the retention it does well is retention *inside a mislabelled arm*.

**Projection layer: coverage is enforced, fidelity is not.** The principles doc names
`CatalogBuilder.projectFieldClassification` as the exemplar of "One model, many views": an
exhaustive switch, so a new permit fails compilation until the view covers it. That pins *coverage*.
Nothing pins *fidelity*: a view may narrow a permit to a message and no test notices. The clearest
case: `UnclassifiedField.definition()` (the full authored `GraphQLFieldDefinition`) is in hand and
discarded in favour of `f.reason()`. And coverage without a producer is worth nothing: the
`FieldClassification.InputUnbound` arm compiles but is unreachable, because input fields contribute
no entries to the projection's index. The arm is even declared edge-bearing in `EdgeProducer`, a
second consumer-side declaration pinning an unreachable arm. The input half of any projection fix
is therefore blocked on input-member coordinates (umbrella work), not on this item.

**Consumer layer: views re-derive from text what the model had.** Measured on a live session against
a consumer schema mid-migration, an agent asked to repair `@mutation` usage on the delete mutations
ran `grep -rn 'typeName: DELETE'` across a whole monorepo. `schema(type: "Mutation")` answers that
for mutations that *classified*, projecting `dmlKind` and the resolved `tableName`. For the broken
ones, which are exactly the population needing repair, it answers `Unclassified` plus prose. So the
tool is useless precisely where the author needs it, the agent falls back to SDL text, and SDL text
is worse data: it reads intent rather than the verdict and carries no table binding.

## Target model: scatter-gather classification over claim relations

Two base relations, both keyed `(coordinate, classifier)`:

- `authoredClaims`: claims triggered by a directive application. Provenance is the application and
  its source location.
- `inferredClaims`: claims triggered by structure (a name resolving against the parent's table, a
  grouping type nesting). Provenance is the structural trigger.

Since the graph partition dimension shipped (R610, Done), `graph_name` leads every
`graphitron_` base-relation key, so the claim views inherit the column by construction and the
full key is `(graph_name, coordinate, classifier)`. A generator run works one graph; the prose
here keeps writing the run-scoped key. Key structure is the default home for this kind of
behavior: R610's own refresh scoping landed derivationally, a relation carrying the graph
column ownership-cleared by construction rather than taught per relation, and these slices
default to the same move.

Separate relations rather than one relation with a tier tag keep each row type honest: the two
provenance shapes never share a record, so no component goes nullable by kind, which is the
`UnboundField` smell above. The key is the purity statement: a classifier is a deterministic
function from gathered facts to at most one claim per coordinate. Two hosts classifying the same
nested coordinate land on the same key, so deduplication is structural, not procedural. Provenance
is what turns a conflict message from "@service, @routine are mutually exclusive" into "the service
classifier claimed service-backed from `@service` at line 12; the routine classifier claimed
routine-backed from `@routine` at line 12".

**The claim payload is decoded slot facts.** A claim carries the classification kind it asserts and
the facts the classifier resolved on the way there (a resolved table, a parent correlation, an
order spec), all decoded. No graphql-java node crosses into the relations, preserving the
containment line `FieldClassification`'s javadoc pins for the views. A conflicted DELETE mutation
reports its intended table because the table slot fact was resolved before the conflict was
derived, not because anything specially preserved it.

**Claims cover the classification axis only.** Delivery (`@splitQuery`), reachable source shapes,
and the other gathered axes stay their own coordinate-keyed relations; `GraphitronSchema` already
carries eight, and the claim relations join that family rather than swallowing it. Which
directives claim is declared where the claim view is defined: the view unions one arm per
claiming semantic relation, so "classification-claiming" is data a query can answer rather than
a hand-enumerated Java list; `@splitQuery`'s relation contributes no arm, and the `Composes`
verdict dissolves rather than migrates.

**Resolution is a relational expression, not a tag filter.** The view planning reads is the
authored relation unioned with the inferred rows at coordinates the authored relation does not
cover. Structural classifiers therefore need zero directive knowledge: masking is the join's job,
never a guard's, and the masked structural reading survives as data ("would classify as a table
column; `@service` overrides it") for any hover or diagnostic that wants it.

**Validation rules are the key constraints, refined.** More than one authored claim on a coordinate
is a conflict violation carrying every claim, except where a recognized-combinations rule refines
the kind: `@routine` with `@lookupKey` is a capability gap (keyed batch lookup backed by a routine
is coherent authored intent with no designed emit strategy), so that pair mints a Deferred-kind
violation, exactly the cause identity `reduceDirectiveConflict` produces today. Deferral fails the
build like any rejection, so planning's totality is never exercised and nothing unimplemented
reaches emit; the pairwise table's entire residue is this one rule's data. More than one inferred
claim on an authored-free coordinate is a structural ambiguity. Zero claims on a coordinate that
requires one is unclassifiable. All three mint into `GraphitronSchema.diagnostics`; the claims
stay.

**Guards are part of a classifier's contract, within the authored side.** Authored classifiers that
might step on each other make the interaction explicit by knowing each other's directives: the
condition classifier and the lookup-key classifier both know `@lookupKey`, one claims only when it
is present, the other only when it is absent. A claim is therefore a gathered fact about the schema
as authored, never a counterfactual ("what `@service` would have produced had `@routine` not been
there"), which is what disqualified running today's arms speculatively: the arms are not pure, and
their output under co-occurrence is a derivation under a false premise. Where a co-occurrence is
recognised and deliberate, the guards let both claims stand and the recognized-combinations rule
names the pair; that is the `@routine` with `@lookupKey` case above.

**Guard drift is self-reporting.** Too-loose guards produce two claims and surface as a conflict
violation carrying both; too-tight guards produce zero claims and surface as unclassifiable. Drift
can never silently produce wrong code; it lands as a key-constraint violation on the first schema
that exercises the overlap. The invariant carries its own enforcer, where today a wrong
`pairVerdict` entry or a name missing from the two hand-enumerated detector lists misclassifies
silently, because arm order in `FieldBuilder` is the de facto precedence table and nothing renders
it.

**Parse, don't validate, at the right grain.** Each classification arm today is a parser whose
target is the classification record, and the records rightly cannot be instantiated half-filled.
The defect is that the parse's intermediate progress lives in locals: by the time the
uncorrelated-routine `@splitQuery` check fails (the `Rejection.directiveConflict` naming
`@splitQuery` and `@routine` in `FieldBuilder`'s routine-chain arm), the arm has resolved the
table-backed target, built the `ParentCorrelation`, and derived the split source, and all of it
evaporates into one `UnclassifiedField`. All-or-nothing is a property of the parse target; today
it leaks into the knowledge. Under this model classifiers record the slot facts they resolve, a
coordinate whose facts cannot fill a target's slots yields a violation naming exactly the missing
slot, and every filled slot survives to every view. The parse targets are the commands: planning
joins the reduced claim view and the slot facts directly into command records (a coordinate may
lower to several), with no intermediate classification record between facts and commands. Today's
`GraphitronField` hierarchy is the transitional producer surface; dissolving it is umbrella work.

**Classifiers are pure, and the additive pattern already ships.** The
`@splitQuery`-on-nesting-field branch in `FieldBuilder` already does what this item asks for: it
mints a Deferred diagnostic into `ctx.diagnostics()`, keeps the classification and its subtree
intact for the editor view, and fails the build through the validator's drain. It also carries the
purity hazard to retire: it reads the accumulator to dedupe the shared-nesting case. Under
coordinate-keyed claims that dedup is structural, per the key above; violations and registry
entries come out of validation and planning, never out of classifiers.

## Capture and derivation: two loads, then a stack of views

Simulating the target pipeline collapses it further than the stage vocabulary above suggests. Two
capture steps, neither of which can fail, load the base relations. The SDL walk reads the
type-definition registry, not the assembled schema: parse-to-registry is graphql-java's
linear half, assembly is the superlinear half capture never pays, and the semantic validity
assembly used to enforce becomes detections, this item's thesis applied at the parse boundary
(a dangling author-spelled reference or malformed argument mints a located diagnostic instead
of dying in graphql-java's throw). The walk records existence facts and directive
applications: the type exists, the field exists at this coordinate, the argument uses this
input type. Every application transcribes into the generic application relations regardless of
namespace, graphitron's own included: the shipped `graphql_` family is a total transcription,
and whether an application survives into the emitted schema is a namespace query at emission,
not a table choice at capture. A graphitron or federation application additionally lands
decoded in its per-directive `graphitron_` relation (the decode never throws; while assembly
still runs upstream, invalid input never reaches capture), and the macros (`@asConnection`,
`@asFacet`, federation key synthesis) expand during the same walk, their synthesized rows
marked by provenance relations so the authored picture stays the anti-join.
Source locations ride the raw facts so every later diagnostic inherits its location without
re-walking SDL. The second load fills the `sql_` and `jvm_` families; the DDL header and the
relation comments own their exact scope and filters. Capture is total: everything in
the SDL is recorded, with no reachability pruning at capture time.

Everything after capture is derivation, and classification stops being a phase. An authored
claim is captured, not assembled: the visitor decodes each graphitron application into its
semantic relation, and the claim relation is a thin union view over the claiming relations,
the classifier column a literal per arm; the conflict rule stays a detection query grouping by
coordinate, per the constraint split below. An inferred claim is a join with catalog facts on
the right side (a field fact whose parent binds a table, a catalog column matching the name). Reachability
is a derived relation too (root seeds plus transitive closure over captured edges), and with it
the zero-claims domain becomes explicit: a demand relation, reachable coordinates intersected with
requiring rules, each row carrying *why* a claim is required. Today's implicit skips (connection
and pageInfo machinery never enters the registry, a DELETE-carrier's data field is silently
filtered, every interface field is skipped wholesale, `Subscription` fields are demanded and
then unconditionally deferred) become visible exemption and rule rows, censused in
`roadmap/audits/2026-08-06-demand-exemption-census.md`, and unclassifiable is the anti-join of
demand against the reduced claim view.

Diagnostics enter wherever a constraint's inputs are complete, which stratifies into three groups
without anyone scheduling it. SDL-only constraints (authored conflict, recognized combinations)
fire after the first load, computable with no catalog on the classpath, which is exactly the
latency class the LSP wants. Resolution constraints (a directive naming no catalog row, key
columns absent) fire after the second load, each an inclusion constraint whose violation names the
missing fact. Assembly constraints fire at planning, where the uncorrelated-routine `@splitQuery`
check above is the type specimen, expressible only once the split-source slot fact exists.
Planning itself never re-checks: a coordinate that tripped an earlier constraint has no row to
join, so absence manifests as no command. Each stratum only ever adds rows, which is this item's
title read as an evaluation order.

The input side falls out of the same picture. Use-site facts live on the argument edge, so the
occurrence path (this argument, then this nesting field, then this one) is derivable data: the
transitive closure of argument-use over input-object field edges, keyed by the path value itself.
The cascade fact slice 5 flags (`enclosingOverride`) is then a predicate over path prefixes, not a
fact waiting for input carriers to get minted coordinates; the open question below narrows
accordingly.

**Materialization is decided: the store is adopted.** The relations and views above live in an
embedded relational store, H2 queried through jOOQ codegen over the fact DDL. The
store makes the constraint split mechanical (the base-relation key is a literal `PRIMARY KEY`,
and throwing on a duplicate is right because that is a generator bug; the author-error rules are
detection queries whose result sets mint diagnostics), dogfoods the stack the generator emits
for consumers, and opens a read-only SQL surface for agents. Its three empirical costs were
measured by the spike (`roadmap/audits/2026-08-05-fact-base-h2-spike.md`): encoding clean with
no blobs, latency comfortable for build and on-save loops, and an ordering discipline whose
violation the fixture corpus catches as an output diff. The spike's verdict leaned to a hybrid
with a typed facade owning `ORDER BY`; the round after it rejected the facade. Mediating all
access would reconstruct the fixed method vocabulary the store was chosen to escape and
duplicate every relation into a second hand-written surface, while the jOOQ generated classes
already are the containment (typed access, no strings, no JDBC), and once the module ordering
below lands, relations-as-Java is a rewrite with or without a seam, so the facade buys a
retreat nobody would take. The residue is one rule: determinism is owned at the emission
boundary, "whatever crosses into emission or diagnostics output is sorted at the crossing", so
planning orders command records by coordinate, the diagnostics drain sorts by location, interior
query order stays free, and an engine upgrade's corpus diff has a suspect list of a few
crossings rather than every query.

What the store materializes deserves naming precisely: the fact schema DDL is the umbrella's
normalised data model reified as a SQL schema. The store's persistence and refresh model has
reshaped under each item that shipped against it (in-process at first, a build-directory
cache at R595 slice 5, a per-workspace multi-graph file at R610), so this body stops
mirroring it: the DDL header and the changelog entries are the authoritative statement of
where the file lives and how it refreshes. What this item relies on is narrower and has held
through every reshape: the file is a cache, never a state of record; no migration ever exists
(a stamp mismatch opens a different file rather than converting one); and a run that cannot
use the file falls back to in-memory. The DDL is source, and its home is the `graphitron-model` reactor module,
which holds the DDL, runs jOOQ codegen over it (live H2 metadata over a store the build driver
boots from the DDL, no external database process; `DDLDatabase` was tried and dropped, per the
functions spike below), and builds before core: the `graphitron-sakila-db` shape made
hermetic. The module name is the reification read literally, the module holding the DDL is the
model. The ordering turns schema
evolution into a compiler conversation: drop a column and every derivation, detection, and
consumer that touched it fails javac in core before anything runs, and since no persisted state
of record exists, compile-time is the only compatibility surface the schema has. Changing the
model is editing the DDL and following the compiler.

The derivation vehicle is settled too, by a second spike
(`roadmap/audits/2026-08-05-h2-functions-jooq-spike.md`). Table-valued functions are not it: H2
pre-evaluates table functions, so their arguments cannot reference columns of the surrounding
query, there is no `LATERAL` to rescue the correlated join a TVF-based derivation layer would be
built from, and the generated binding is untyped besides. Derivations stay SQL statements, views
and `INSERT..SELECT` strata with recursive CTEs where closure is needed, exactly the shape the
stratification above assumes. A later round then moved structured-argument decoding into
capture itself (the shipped `graphitron_` family: the visitor holds the AST, so nothing
re-parses at derivation time), which empties the known class of derivations needing a SQL-side
parse. The
scalar-alias bridge the spike proved (`CREATE ALIAS` functions in the model's DDL, row
explosion via a `SYSTEM_RANGE` join with `CARDINALITY` and `ARRAY_GET`) is thereby a
contingency, not a planned mechanism: adopted only if a derivation ever genuinely needs a
parse SQL cannot express, with the spike's wiring as the recipe.

Materialized derivations inherit a lifecycle question views do not have, and the doctrine
answering it shipped with the `javac_` family (R603): in a store shared across graphs and
never discarded, any row a post-capture writer mints (a violation a detection materializes,
slice 4's shadow demand rows) outlives its inputs unless something owns clearing it. The DDL
header states cadence as its own axis, and the recipe transfers verbatim: the writer owns its
cadence and clears the run's own graph partition of its family before rewriting it, and every
statement carries the graph predicate, because in a shared store an unscoped delete is one
run erasing a sibling graph's rows. A stratum that stays a view computes on read and the
question dissolves; view versus materialized rows is a per-stratum choice, made where each
slice lands.

## Scope

The slices are cut on the strangler frame: R595's substrate has shipped, and this item is the
store's first reader, migrating the classification stage. Generated output is identical
throughout; what moves is where verdicts come from and what survives a failure.

1. **Amend the umbrella.** The single-pass R333 refresh: the materialization section re-argued
   to the adopted store, the claim base relations and stage vocabulary (classification gathers,
   validation derives, planning joins facts into commands) and commands-as-parse-targets end
   state added, the three-consumer re-sourcing mechanism replaced by the strangler frame, the
   location rule split by namespace, the input side re-keyed onto the derived occurrence path,
   with the staleness audit's symbol refresh folded into the same pass (region list in
   `roadmap/audits/2026-08-06-fact-base-impact-sweep.md`). Reviewed through this item's gates;
   R333 does not leave Ready.
2. **The authored claim view ships, and the conflict rule reads it.** The view unions one arm
   per claiming `graphitron_` relation at both grains, classifier column a literal per arm; the
   arm list is the axis declaration. (The shipped DDL holds the `intent_` prefix in reserve for
   exactly this derived stratum, and R603 gave the call firmer edges by testing the reserve: a
   family is named for whose vocabulary its rows are written in, and a claim is the generator's
   verdict, which fits; but the schema gates govern base relations and this layer is mostly
   views, so whether views constitute a family at all is the open half of the naming call this
   slice makes.) One capture residual is load-bearing here: a decode arm that declines on a missing
   required argument currently writes neither its decoded row nor a
   `graphitron_undecoded_argument` row (R609), so the claim view would silently miss that
   application; either that quarantine lands first, or this slice ships the companion detection
   (a graphitron-namespace `graphql_` application with no decoded row). The conflict detection
   groups the view by coordinate; the
   recognized-combinations rule refines `@routine` with `@lookupKey` to the Deferred kind (cause
   identity pinned: it stays a capability-gap rejection); and the four conflict sites dissolve
   into the one detection: the two hand-enumerated detector lists behind
   `reduceDirectiveConflict`, the ad hoc `@service` with `@mutation` check, and the type-level
   `@table` with `@error` check. Violations mint into `diagnostics`; the claims stay; a
   conflicted coordinate stops tombstoning. The `Composes` row dies with the pairwise table
   (`@splitQuery` is a delivery-axis directive that never claims; the row is dead code today
   regardless).
3. **One inferred classifier proves the witness model.** The column-match classifier (a field
   name resolving against the parent's table) ships as a derivation view whose row carries its
   join witnesses, masked by the authored-coverage anti-join. Acceptance is agreement with the
   legacy arm over the fixture corpus; this is the vertical slice that validates inferred claims
   before any arm-by-arm migration.
4. **Demand and exemptions become rows, in shadow.** The demand relation (reachable coordinates
   intersected with requiring rules) and the censused exemption populations land as derivations
   with agreement instrumentation against the legacy registry's verdict population. They gate
   nothing here: flipping the demand anti-join to a build-failing rule changes what the build
   accepts (the DELETE-carrier hole, the renamed subscription root) and is follow-up work with
   its own item.
5. **"Unbound" stops being a demotion target.** The definition-keyed fact ("no column bound,
   attempted name X") stays on the carrier as a positive fact. The malformed-shape verdict
   (`@condition(override: false)` with no column) mints into `diagnostics` as a definition-keyed
   detection, with no later retraction. The cascade verdict is use-keyed and mints once per
   use-site join; each of the two predicates gets exactly one evaluation site (today
   `FieldBuilder.rejectAtConsumer` and `GraphitronSchemaValidator.validateInputUnboundField`
   overlap). This subsumes the validator-mirror gap R221
   (`validator-walks-plain-input-unbound-fields`) owns in full: the definition-keyed disjunct is
   exactly the reachability R221 asks for, and the cascade disjunct gets its evaluation site
   from the occurrence-path derivation (closed below), so R221 closes as subsumed when this
   slice lands. The cascade fact `rejectAtConsumer` reads (`enclosingOverride`) becomes a
   predicate over path prefixes rather than a call-site local.
6. **The output-side projection preserves the claims.** A broken DELETE mutation still reads as a
   DELETE mutation with its intended table on the LSP and MCP surfaces, sourced from the claim
   relations, never from `UnclassifiedField.definition()` (a graphql-java node; reading applied
   directives off it downstream would widen a parse-boundary containment exception into two more
   consumers). The projection arm splits instead of reusing `Unclassified(reason)`: unresolvable
   (carrying the reason) and conflicted (carrying the claims), so fidelity has a type to land on.
   The conflicted arm is edge-bearing (closed below): one edge per claim whose slot facts
   resolved a table, `EdgeProducer` declares it and `EdgeCoverageTest` pins it, so the motivating
   query ("which delete mutations target table X") includes exactly the broken population.
   Input-side projection is descoped: input fields have no coordinates in the projection until
   the umbrella's input-member-coordinate work lands, and the dead `InputUnbound` arm stays dead
   until then.
Enforcement across the slices is behavioural plus type-lift, not a census. The acceptance fixture
is pipeline-tier: an SDL schema with a conflicting-directive DELETE mutation, asserted through the
projection to still report the DML kind and the intended table. Fidelity lifts into types where
the projected arm's components are a view record over the claim payload. No reflection census of
carrier components against projection components; it cannot observe whether the projection read a
component, so it degenerates into two hand lists agreeing by convention. Once slice 2 lands, the
existing fixture corpus doubles as a guard-drift census for free: unchanged accept/reject over the
corpus asserts that the reduced view is single-valued everywhere the corpus reaches.

## User documentation (first-client check, draft)

The MCP `schema(type:)` entry for a conflicted DELETE mutation. Today:

```json
{"kind": "Unclassified", "reason": "@mutation, @service are mutually exclusive"}
```

After slice 6 (shape illustrative; field names settle at implementation):

```json
{
  "kind": "Conflicted",
  "claims": [
    {"classification": "DmlMutation", "dmlKind": "DELETE", "tableName": "film",
     "trigger": "@mutation(typeName: \"DELETE\")", "line": 12},
    {"classification": "MutationService", "methodClassName": "no.sikt.films.DeleteFilmService",
     "trigger": "@service", "line": 12}
  ],
  "violation": "two directives claim the classification; pick one"
}
```

The LSP hover header gains the same split: `Conflicted: DmlMutation (@mutation), MutationService
(@service)` where today it renders `Unclassified`. This draft is the first client of the claim
payload: everything in it must be renderable from decoded slot facts alone, and if it cannot be,
the payload shape is wrong. This is the workflow's first-client rule applied to a changed wire
shape rather than a new one; the MCP JSON and the hover header are agent- and author-facing
surfaces, so the item does not qualify for the internal-refactor exemption.

## Relationships

- **`graphitron-model-captures-facts` (R595, Done; see `roadmap/changelog.md`):** the substrate
  this architecture runs on, shipped with real divergences from the plan this body's earlier
  rounds cite. The load-bearing ones here: the fidelity family dissolved into the total
  `graphql_` transcription (re-emission is a namespace query at emission), the semantic
  stratum is `graphitron_`, and the DDL header reserves `intent_` for the derived stratum
  this item builds. For everything else the DDL header and the changelog entry are the
  record; this body no longer mirrors the substrate. This item, the classification-stage
  migration, is the store's first reader.
- **Graph partition dimension (R610, Done; see `roadmap/changelog.md`):** `graph_name` leads
  every `graphql_` / `graphitron_` base-relation key, anchored by `store_graph`, so one store
  holds several graphs and the claim views this item builds are graph-scoped by construction:
  a view unioning graph-keyed relations carries the column, and `FactSchemaGateTest`'s
  partition-dimension and FK-closure gates cover new relations in exemption polarity, so slice
  2 inherits the dimension with no case to make. R610 also reshaped persistence and refresh;
  its changelog entry is the record.
- **`javac_` oracle family (R603, Done; see `roadmap/changelog.md`):** the sixth family and
  the cadence doctrine: a post-capture writer gets its own vocabulary-named family on that
  writer's cadence, and capture clears the run's own graph partition of it before
  regenerating. It confirms rather than disturbs this item's layer: the DDL header still
  reserves `intent_` for the derived stratum, and the claim views are capture-cadence
  derivations rather than oracle-written base relations, so the agreement driver's new
  `ORACLE` arm does not apply to them.
- **`capture-load-residuals` (R609):** the Done-gate residuals. The declined-decode gap is the
  one this item's slice 2 must land against (see there); the others (second catalog walk,
  retained-partition scan skip, nested-class filter, shadowed-duplicate quarantine) are
  shadow-period sharpenings this item can take or leave.
- **Umbrella (`coordinate-lowers-to-datafetcher-queryparts`, R333):** amended by slice 1. The claim
  relations are base relations the umbrella's current text lacks; the single-classification
  worldview relocates to the planning stage instead of being abolished, the commands become the
  parse targets, and today's classification record hierarchy is named as the transitional producer
  surface. The arm-by-arm migration of `FieldBuilder` into independent classifiers is follow-up
  work under the umbrella, not this item. With the store adopted, the fact schema DDL (the
  `graphitron-model` module) is the umbrella's normalised data model reified as SQL: created at
  startup, populated during a run, never migrated.
- **`input-field-resolution-typed-rejections` (R585):** overlaps on one carrier, and its one open
  design fork (fan many input-field failures into one prose rejection, or emit several) is decided
  by this item's doctrine: violations are facts, one per failure. Whichever lands first settles the
  fork once; let that item land first if both reach In Progress together (it is smaller and already
  scoped).
- **`validator-walks-plain-input-unbound-fields` (R221):** subsumed or narrowed by slice 5; see
  there.
- **`mcp-aggregated-diagnostics` (R569):** a consumer, and the reason this surfaced. Every fact that
  survives a rejection becomes a candidate pivot dimension; that item should not grow to anticipate
  this, its dimension set widens on its own when this lands.

## Out of scope

- **Changing what the build accepts or rejects.** A schema that fails today still fails, with the
  same causes at the same locations. Message *text* may improve (a conflict can now name both
  claims); cause identity and location are pinned, and message-asserting tests are expected to
  churn.
- **The full `FieldBuilder` decomposition.** This item ships the mechanism and one pilot pair; the
  migration series is umbrella follow-up work.
- **The emit side.** A coordinate whose claim count is wrong generates nothing today and still
  generates nothing; retained claims feed the read-side views, not partial code generation.
- **New rejection causes**, and any move of the accept / reject line.
- **Flipping demand to a gate.** The demand and exemption derivations land in shadow; making
  the anti-join fail the build changes what the build accepts and is its own follow-up item.
- **Input-side projection**, per the projection slice.

## Where the design rounds landed: the strangler frame

Four design rounds took this item from a projection-fidelity fix to a pipeline architecture:
capture as two infallible loads, classification and reachability and demand as derivations,
diagnostics as strata, the occurrence path as a derived key, and an adopted materialization
that reifies the umbrella's normalised model as a startup-created SQL schema in a new upstream
module. The scope above is cut on that picture.

The frame is a strangler migration keyed by consumer, not by derivation layer. The substrate
shipped first (`graphitron-model-captures-facts`, R595, Done): the module, the two capture
loads running beside the working pipeline and changing no behavior, agreement tests as the
shadow period's honesty check. Downstream code now migrates off `GraphitronSchema` onto the
store piece by piece, in whatever order pays best rather than pipeline order, each piece gated
on generated-output identity; a derivation is built when the first consumer needing it
migrates, never speculatively. While both models are live, new facts land only in the store,
so the two-model window shrinks monotonically. `GraphitronSchema` is the surface being
strangled, not extended, which is the umbrella's arm-by-arm migration language given a
substrate. This item is the classification-stage migration piece (the claim derivations, the
conflict rule, the Conflicted projection), which is what its title, fixture, and motivating
example describe; the remaining migration pieces get their own items as they are cut. The
design questions below are all closed; each closure stays recorded because its answer must
land in one of the scope's pieces.

## Design questions, all closed

- **The demand relation's rules and exemption census: closed by census**
  (`roadmap/audits/2026-08-06-demand-exemption-census.md`). Today there is no demand predicate
  at all: "requires a classification" is the negative space of `classifyFieldsOfObject`'s early
  returns, the validator sees only produced verdicts, and the reachable-implies-classified
  invariant is asserted in tests only, at type grain. The census enumerates thirteen exemption
  populations that become explicit rows, the largest undocumented one being every field of
  every interface type (no interface ever gets a `classifyFieldsOfObject` call). `Subscription`
  is answered: its fields are demanded and receive a Deferred verdict, so it is a requiring
  rule satisfied by a recognized capability gap, not an exemption; the dispatch being
  name-keyed rather than root-keyed is a hole the demand rule closes (a renamed subscription
  root's fields silently take the nesting-target exemption today). Two demand-shaped defects
  the relation makes visible: the DELETE carrier's data field gets no verdict at all whenever
  `classifyDeletePayloadField` bails before its `IdElement` arm, and mixed-source nesting
  targets are classified twice through two paths, which the coordinate-keyed claim relation
  collapses structurally. Both suspected dead finds are confirmed, plus a second unreachable
  `TableInterfaceType` exclusion in `classifyChildRoutineChain`; they belong to whichever slice
  touches the walk.
- **Slice 5's cascade half: closed, the path-valued key is adopted.** The predicate splits
  cleanly: the definition-keyed disjunct (`@condition(override: false)` with no column) is
  exactly `GraphitronSchemaValidator.validateInputUnboundField`'s existing predicate, and the
  cascade disjunct (no condition, no ancestor override) is irreducibly a fact about an
  occurrence path. The path relation is derived and value-keyed like everything else in the
  store: the parent row's key is the serialized path, ordinal-keyed step rows carry the same
  data relationally so no consumer parses the key. A derived relation is re-derived each run
  and never migrated, so the value key costs nothing and keeps re-derivation deterministic; no
  surrogate ids enter the schema. It lands with this item's classification-migration piece,
  not behind the umbrella's input-member coordinate work, because the derivation needs no
  minted coordinates; the path is its own identity. `rejectAtConsumer`'s consumer-side
  re-derivation retires when the cascade predicate reads the derived relation.
- **Edge placement of the conflicted arm: closed, edge-bearing.** Discoverability was never at
  stake (the MCP `schema` tool lists broken fields inline with their reason regardless of
  edges), so `NO_EDGE_FIELDS` membership governs traversal reach only, and the item's own
  thesis decides it: `Unclassified` is rightly no-edge because nothing resolved, and a
  conflicted coordinate differs precisely because its claims' slot facts survived. The
  conflicted arm derives one edge per claim whose slot facts resolved a table, so the reverse
  index answers "which delete mutations target table X" with the broken population included,
  which is the motivating query. Multi-edge-per-field is established pattern (composite columns
  and polymorphic participants loop the same builders); `EdgeProducer` declares the arm
  edge-bearing and `EdgeCoverageTest` pins it.
- **Slot-fact granularity: closed.** The semantic-capture pivot recorded in R595 answers it:
  the graphitron directive inventory decodes at capture into per-directive semantic relations
  that ship with the substrate, so no increment mints decoded shapes later; what an increment
  adds is its detections and resolution derivations. The bullet stays only to record the
  closure.
- **The axis declaration's home: closed by the store.** A directive's axis is which derivation
  views read its semantic relation, and the classification axis's declaration is literal: the
  claim view unions one arm per claiming `graphitron_` relation, classifier column a literal
  per arm, at both grains, since types claim too. Today's four conflict sites all dissolve into the
  same grouping detection over that view: the two hand-enumerated detector lists behind
  `FieldBuilder.reduceDirectiveConflict` (child fields: `@service`, `@externalField`, `@nodeId`,
  `@routine`; root query fields: `@service`, `@lookupKey` anywhere on the argument surface,
  `@routine`), the ad hoc `@service` with `@mutation` check in the mutation-field arm, and the
  type-level `@table` with `@error` check in `TypeBuilder`. Drift is loud in both directions: a
  claiming relation left out of the view produces zero claims and lands as unclassifiable
  through the demand anti-join, and a non-claiming relation wired in must invent a
  classification kind for its arm, one planning has no parse target for, and additionally
  surfaces as conflict rows wherever it co-occurs with the real claimant. The generated
  directives reference renders the axis from the store rather than from a Java-side list; the
  render mechanism is implementation detail.
- **Interim claim payload in the original slice 2: mooted by the strangler frame.** That
  slice's interim (the monolithic classifier minting claim rows) belonged to the pre-store
  shape and is gone from the scope above. Under the store the
  authored claim relation is a view over the captured semantic relations; nothing mints claim
  rows, so there is no interim payload to choose. The residue is the view's column shape, and
  the first-client draft above already constrains it: whatever the MCP JSON and hover header
  render must be selectable from the claiming relation's decoded columns.
- **Provenance shape for inferred claims: closed by census**
  (`roadmap/audits/2026-08-06-structural-classifier-census.md`). The enumeration exists:
  roughly twenty structural arms whose trigger vocabulary collapses into six families (name
  resolution against the catalog, type-shape recognition, catalog key facts, catalog metadata
  sentinels, reflection facts, cross-site agreement). One provenance record does not cover
  them, and does not need to; the answer mirrors the authored side. Each structural classifier
  is its own derivation view, and its claiming row's columns are that classifier's join
  witnesses: the catalog column that matched the name, the unique FK between the table pair,
  the membership set that proved the errors shape. Provenance is the witnesses, so no
  component goes nullable by kind, and the claim view unions per-classifier relations with the
  classifier column a literal per arm on both sides; the only universal part is the
  `(coordinate, classifier)` key. Two census findings reinforce the model: every structural
  arm already has an explicit directive mask upstream, so moving masking to the
  authored-coverage anti-join invents no semantics, and cross-file triggers are the norm,
  confirming that no verdict is computable during a file's parse. `ClassificationTrace`, the
  only provenance-adjacent record today, carries the verdict's class name and no trigger data;
  the witness evaporates at each arm's return, which is this item's thesis at the provenance
  grain.

## Retired vocabulary (expected; finalise at the Done gate)

- `FieldBuilder.PairVerdict` / `pairVerdict` / `reduceDirectiveConflict`: the pairwise reduction,
  replaced by the authored-relation key constraint plus the recognized-combinations rule.
- The three-cases-in-one-record reading of `InputField.UnboundField` and its `attemptedColumnName`
  null-as-discriminator semantics (the component itself may survive as an honest fact).
- `UnclassifiedField.definition()` (candidate): the claim relations subsume the rich-error-context
  role its javadoc claims; deleting it closes the parse-boundary containment exception the
  projection slice protects.
