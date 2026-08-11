---
id: R589
title: "Classification is a relation; validation adds facts"
status: In Progress
bucket: architecture
priority: 4
theme: classification-model
depends-on: []
created: 2026-08-04
last-updated: 2026-08-11
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
`EdgeKind`'s own javadoc names when it justifies the label-enum shape (the varying endpoint shape
lives in `NodeRef`, so the enum "carries no kind-dependent nullability"). The consumer then
re-derives violation-ness from those components rather than reading a violation.

## Three layers, and the leak is worst at the seam

**Model layer: five failure carriers, five different answers.**

| Carrier | Retains | Reaches a view as |
|---|---|---|
| `InputField.UnboundField` | parent, name, location, typeName, nonNull, list, condition, **`attemptedColumnName`** | nothing live: the `InputUnbound` arm exists for switch coverage, but `projectFieldClassifications` iterates the output-field index only |
| `ArgumentRef.UnclassifiedArg` | name, typeName, nonNull, list, rejection | nothing (arguments have no classification projection) |
| `GraphitronField.UnclassifiedField` | parent, name, location, **`definition`**, rejection | `FieldClassification.Unclassified(reason)` |
| `GraphitronType.UnclassifiedType` | name, location, rejection | `TypeClassification.Unclassified(reason)` |
| `InputFieldResolution.Unresolved` | fieldName, location, **`rejection`** | one located `ValidationError` per failure, minted at the failing field |

**One row has already converged, independently.** `InputFieldResolution.Unresolved` reached the
shape this item argues for through `input-field-resolution-typed-rejections` (R585, Done): a typed
`Rejection` on the carrier, the failing field's own location, and each failure minting its own
located `ValidationError` at the mint boundary (`BuildContext.mintInputFieldFailures`) where three
fan-ins previously joined k failures into one prose sentence at the consuming coordinate. It is
precedent, not a defect: the doctrine held on a real carrier in the pre-store world, which is the
strongest evidence available that the remaining four rows are reachable. The relevant residue for
this item is that the surface slice 5 lands on is already additive and already dedups by value at
the mint, and the tests guarding it are count-asserted on purpose.

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

`coordinate` in that key is grain-generic shorthand, and the grain question is decided here
rather than left to the view definition. Types claim (`@table`, `@error`) and fields claim, and
the two grains have different concrete keys in the shipped DDL (`(graph_name, type_name)`
against `(graph_name, type_name, field_name)`), so the claim layer is one view per grain, two
views on the authored side. A single view spanning both grains would need a nullable
`field_name` column or a serialized coordinate string, which is exactly the kind-dependent
nullability this item exists to remove; the grain split is the relational form of the same rule,
and "the claim view" in the prose below means whichever grain's view covers the coordinate.

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
directives claim is declared where the claim views are defined: each grain's view unions one arm
per claiming semantic relation, so "classification-claiming" is data a query can answer rather
than a hand-enumerated Java list; `@splitQuery`'s relation contributes no arm, and the `Composes`
verdict dissolves rather than migrates. An arm is usually a projection of its relation's key
columns; slice 2 names the two that are not, each an exception of a different shape.

**A claim is position-scoped.** Today's conflict machinery runs one detector site per position
(the code comment's own phrase): the child-field list gates on the parent not being a root type,
the query-root list on `isQueryRoot`, and mutation root fields see only the ad hoc `@service`
with `@mutation` check. So which directives claim is a per-position fact, and each arm's
position mask is part of the axis declaration. Transcribed: `@service` claims at all three
positions, `@externalField` and `@nodeId` on child fields only, `@routine` on child and
query-root fields, `@lookupKey` at the query root only, `@mutation` at the mutation root only.
With the masks in the arms, the conflict detection itself stays position-blind: every coordinate
has one position, so grouping the masked union reproduces today's per-position detected sets
exactly, and `reduceDirectiveConflict`'s Deferred wording ("on a root field") stays reachable
only at the query root, preserving its pinned cause identity. One fidelity note is deliberate:
today's position predicate is name-keyed at every site (`RootType` is minted from the root type
names, and `isQueryRoot` additionally requires the name `Query`), so a renamed root's fields
take the child list today; the masks transcribe that, because the store-side alternative, a
root-keyed join against `graphql_root_operation`, would move the accept/reject line at exactly
the renamed-root hole the demand census names, and closing that hole is the demand follow-up's
line move, not this item's.

The in-memory precursor is already in the tree and should be read before slice 2 designs the arm
list. `no.sikt.graphitron.facts.GatheredFacts` holds one named typed slot per registered visitor,
each visitor owning its own accumulator, with the slot fill switching over `FactVisitor`'s sealed
permits and no default, so a registered visitor without a slot is a compile error rather than a
silently dropped relation. That is the drift protection this item wants for the claim view's arms,
in the shape the pre-store code could express it; the migration is to make the same exhaustiveness
a property of the view definition. `FieldBuilder.hasLookupKeyAnywhere` is the pattern's other half
already applied to a claiming directive: it reads `LookupFacts.triggersFor` instead of re-walking
the argument surface, and its javadoc names the visitor as the directive name's single lexical
home. Slice 2's arm lists should be reconciled against `FactVisitor`'s permits, not derived
independently.

**Resolution is a relational expression, not a tag filter.** The view planning reads is the
authored relation unioned with the inferred rows at coordinates the authored relation does not
cover. Structural classifiers therefore need zero directive knowledge: masking is the join's job,
never a guard's, and the masked structural reading survives as data ("would classify as a table
column; `@service` overrides it") for any hover or diagnostic that wants it.

`NodeDeclaration` (R580, Done) is this shape landed at one coordinate, arrived at independently
and worth copying rather than re-deriving. It answers "is this a node" once, authored (`@node`)
over inferred (`implements Node` plus catalog metadata), for four consumers that previously read
the directive off SDL and stayed consistent with the classifier by coincidence: reachability
seeding, the arrival fold, federation entity synthesis and the LSP node view. Two of its
properties are the ones this item's derivations need. It sits deliberately *above* classification,
because two consumers run before any type is classified, and it takes only a `JooqCatalog`, which
is the derivation layering here (SDL facts joined with catalog facts, no classified registry on
the input side) reached without a store. Its javadoc also records the honest residue: the
classifier's own promotion gate does not call the predicate, because it needs the metadata
*values* to build the type, so the two share helpers rather than a call. Under claim relations
that duplication is what dissolves, since the values and the verdict are the same row.

**Validation rules are the key constraints, refined.** More than one authored claim on a coordinate
is a conflict violation carrying every claim, except where a recognized-combinations rule refines
the kind: `@routine` with `@lookupKey` is a capability gap (keyed batch lookup backed by a routine
is coherent authored intent with no designed emit strategy), so that pair mints a Deferred-kind
violation, exactly the cause identity `reduceDirectiveConflict` produces today. Deferral fails the
build like any rejection, so planning's totality is never exercised and nothing unimplemented
reaches emit; the pairwise table's entire residue is this one rule's data. More than one inferred
claim on an authored-free coordinate is a structural ambiguity. That rule also has a shipped
precedent: the nodeId grammar (R473, Done) made a directive-less node-id reading colliding with a
real column of that name an error at all three coordinates it can occur, explicitly "not a warning
and not a contest either reading wins", through the single method `BuildContext.rejectShadowedNodeId`.
Two structural readings claiming one coordinate, resolved as a violation rather than a precedence
pick, is exactly this rule with the relation still to be built; that method is the migration target
when the detection lands. Zero claims on a coordinate that requires one is unclassifiable. All
three mint into `GraphitronSchema.diagnostics`; the claims stay.

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
intact for the editor view, and fails the build through the validator's drain. Its dedup of the
shared-nesting case (two hosts classifying the same nested coordinate) is no longer a purity
hazard either: R585 moved dedup to the mint boundary, so the branch no longer reads the
accumulator and the arm's own comment now says the case "collapses on `addDiagnostic`'s value
idempotence". What this item changes there is the grain, not the direction: under coordinate-keyed
claims the dedup is structural, a key rather than value equality at a mint, and violations and
registry entries come out of validation and planning, never out of classifiers.

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
2. **The authored claim views ship, and the conflict rule reads them.** One view per grain, per
   the key decision above: the field-grain view and the type-grain view each union one arm per
   claiming `graphitron_` relation at their grain, classifier column a literal per arm, position
   mask per arm per the position-scope paragraph above; the two
   arm lists are the axis declaration. An arm is usually a projection of its relation's key
   columns, and two are not, in two different ways. `@lookupKey` claims on presence anywhere on
   the field's argument surface, so its arm lifts the argument-grain
   `graphitron_argument_lookup_key` rows to field grain and unions the transitive input-object
   closure (a recursive CTE, the vehicle the derivation section settles), which is
   `LookupFacts.triggersFor` restated as a query; the predicate keeps its single home by moving
   there, not by growing a duplicate. The closure half is fidelity over a retired site:
   `@lookupKey` on an input field rejects with a migration message today, so the closure only
   ever produces rows on schemas the build already rejects, and it is kept because those
   rejections' cause identity is pinned, not as a live classification path an implementer
   should price as one. And `@routine` is repeatable, so `graphitron_routine`'s key carries the
   application ordinal; a raw key projection would mint one claim per chain step, and a
   two-step chain would trip the same-classifier key constraint as a phantom purity bug. The
   routine arm collapses the ordinal grain to the field coordinate: the chain is one claim, and
   its steps are the claim's slot facts.
   (The shipped DDL holds the `intent_` prefix in reserve for
   exactly this derived stratum, and R603 gave the call firmer edges by testing the reserve: a
   family is named for whose vocabulary its rows are written in, and a claim is the generator's
   verdict, which fits; but the schema gates govern base relations and this layer is mostly
   views, so whether views constitute a family at all is the open half of the naming call this
   slice makes.) One capture residual is load-bearing here: a decode arm that declines on a missing
   required argument currently writes neither its decoded row nor a
   `graphitron_undecoded_argument` row (R609), so the claim view would silently miss that
   application; either that quarantine lands first, or this slice ships the companion detection
   (a graphitron-namespace `graphql_` application with no decoded row). The conflict detection
   is the same grouping query stated once per grain over its view, and it is position-blind
   because the position knowledge lives in the arm masks, not in the detection; the
   recognized-combinations rule refines `@routine` with `@lookupKey` to the Deferred kind (cause
   identity pinned: it stays a capability-gap rejection); and the four conflict sites dissolve
   into the two grain detections: the two hand-enumerated detector lists behind
   `reduceDirectiveConflict` and the ad hoc `@service` with `@mutation` check into the
   field-grain grouping, the type-level `@table` with `@error` check into the type-grain one.
   Violations mint into `diagnostics`; the claims stay; a
   conflicted coordinate stops tombstoning. The `Composes` row dies with the pairwise table
   (`@splitQuery` is a delivery-axis directive that never claims; the row is dead code today
   regardless). Whichever way the naming call falls, the shadow driver is part of this slice's
   landing: `FactCaptureAgreementTest` enumerates the generated relations and fails on any
   without a registered agreement source, and its `DERIVED` arm's doc already pre-decides where
   derivation strata land ("as registrations here, not as exemptions"), so a claim view that
   enters the DDL registers there on arrival rather than widening any skip list.
3. **One inferred classifier proves the witness model.** The column-match classifier (a field
   name resolving against the parent's table) ships as a derivation view whose row carries its
   join witnesses, masked by the authored-coverage anti-join. Acceptance is agreement with the
   legacy arm over the fixture corpus; this is the vertical slice that validates inferred claims
   before any arm-by-arm migration.
4. **Demand and exemptions become rows, in shadow.** The demand relation (reachable coordinates
   intersected with requiring rules) and the censused exemption populations land as derivations
   with agreement instrumentation against the legacy registry's verdict population; the
   instrumentation's home is `FactCaptureAgreementTest`, the driver the substrate shipped for
   exactly this shadow-period shape, not a parallel harness. They gate
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
   predicate over path prefixes rather than a call-site local. Acceptance is the count, not the
   prose: `InputFieldFanInDiagnosticsTest` is already count-asserted throughout precisely because
   a cause gaining a second producer shows up only as a count, so this slice states the intended
   count per shape and moves those assertions deliberately. A second evaluation site reappearing
   for either predicate is then a failing count rather than a duplicate squiggle nobody notices,
   which is the guard the "exactly one evaluation site" claim otherwise lacks.
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
{"kind": "Unclassified", "reason": "@service, @mutation are mutually exclusive"}
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
the payload shape is wrong. (Settled at slice 6 implementation: the draft's own
projection-permit names failed exactly this constraint, so the shipped wire field is
`classifier` rendering the claim vocabulary; the deviation paragraph in the slice 6
implementation record carries the reasoning.) This is the workflow's first-client rule applied to a changed wire
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
- **Node inference (R580, Done; see `roadmap/changelog.md`):** this item's witness model,
  delivered at one coordinate before the relations exist. `NodeProvenance` is the per-axis
  provenance shape (see the closed question below, which it reopened and settled),
  `NodeDeclaration` is the authored-over-inferred resolution named once and read by four
  consumers, sitting above classification on SDL plus catalog facts alone. Nothing here needs
  changing when the claim relations land; both become derivations, and the classifier gate's
  duplicate re-derivation of the same conjunction dissolves when the values and the verdict are
  one row. Its unverified pre-rollout safety check (the sis typeId-collision census) is R580's to
  carry and does not gate this item.
- **The nodeId grammar (R473, Done; see `roadmap/changelog.md`):** `BuildContext.rejectShadowedNodeId`
  is the structural-ambiguity rule shipped at three coordinates as one method, deciding the
  question this item's validation section decides the same way. It is the migration target for
  that detection, and its "one rule, one lexical home, because divergent wording is the first step
  towards divergent semantics" argument is the same argument the claim view's single grouping
  detection makes against today's four conflict sites.
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
- **`input-field-resolution-typed-rejections` (R585, Done; see `roadmap/changelog.md`):** landed
  first and settled its fork (fan many input-field failures into one prose rejection, or emit
  several) the way this item's doctrine predicts: violations are facts, one per failure. It is this
  item's precedent on the model-layer table above, and the surface slice 5 inherits.
  Load-bearing for that slice: failures mint through `BuildContext.mintInputFieldFailures` at the
  input field's own location, `addDiagnostic` is idempotent by value over a `LinkedHashSet` so
  dedup happens at the mint rather than at any reader's drain, and `InputFieldFanInDiagnosticsTest`
  is count-asserted throughout by design, because a cause gaining a second producer shows up only
  as a count. Slice 5 re-keys those mints (definition-keyed for the malformed shape, use-keyed for
  the cascade), so it moves counts and must say which, rather than treating churn there as
  incidental.
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
  claim views union one arm per claiming `graphitron_` relation, classifier column a literal
  per arm, position mask per arm since the detector sites differ by position, one view per
  grain since types claim too and the two grains' keys differ (both slice 2
  decisions). Today's four conflict sites all dissolve into the
  per-grain grouping detections over those views: the two hand-enumerated detector lists behind
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
  confirming that no verdict is computable during a file's parse. `ClassificationTrace` carries
  the verdict's class name, the source file and the rejection kind, but no trigger data; the
  witness evaporates at each arm's return, which is this item's thesis at the provenance grain.
- **Provenance grain within a claim: closed by `NodeProvenance`, which shipped after the census
  above.** Node inference (R580, Done) delivered the witness model at one coordinate and turned
  up the wrinkle the census missed. `NodeProvenance(Origin typeId, Origin keyColumns)` records
  per *axis* where each identity parameter came from (`DECLARED` from `@node`, `METADATA` from
  the jOOQ constants, `DEFAULTED` from the classifier), and its javadoc states the reason it is
  a pair: `@node(typeId: "195")` over a table publishing `__NODE_KEY_COLUMNS` is declared on one
  axis and inferred on the other, so a single declared-versus-inferred flag cannot express it.
  A claim can therefore mix provenance across its slot facts, which the two-relation split does
  not by itself capture. The resolution keeps the split and refines the payload: **relation
  membership is keyed by the claim's trigger, and slot facts carry their own origin where both
  sources are live.** Nodehood is triggered by `implements Node`, a structural fact, so the claim
  is inferred; that its `typeId` slot may be decoded from an authored `@node` argument is a
  property of the slot, not of the trigger. This costs the "the two provenance shapes never share
  a record" rationale some of its reach (it holds at the trigger grain, not the slot grain) and
  gains a shipped, reviewed shape to copy: a per-axis origin enum beats a nullable
  authored-value column, and it is still no kind-dependent nullability. `NodeProvenance` is the
  precedent a slot-fact origin column should follow.

## Slice 2 implementation record (settled at implementation start, 2026-08-10)

The design below was fixed before the first code change, checked against the live code (the
wiring facts), probed empirically (the H2 facts), and reviewed through a principles-architect
consult whose findings are folded in. It binds the slice's implementation; deviations get
recorded here.

**Wiring facts that shaped it.** Capture runs after classification today, and after validation
on the generate path (`GraphQLRewriteGenerator.runPipeline`: build, validate, capture); the
LSP path (`buildOutput`) captures before validating; the `validate()` goal never captures at
all. `BuildContext` holds no store handle. The legacy detectors are AST-presence-based
(`hasAppliedDirective`), while the semantic relations are decode-based, and the R609
declined-decode gap is still open (`@routine` missing its name and `@mutation` missing
`typeName` decline silently), so a decode-only claim view under-counts exactly where the
presence-based detectors fire. The conflict checks' domain is the walk: interface fields,
nesting-target fields, carrier data fields and embedded nested children never reach them (the
demand census's negative space), and embedded nested children are classified through
`classifyChildFieldOnTableType`, which bypasses the detector sites entirely.

**H2 facts (probed on 2.4.240).** A recursive CTE with `UNION` does not terminate on a cyclic
graph; it accumulates until the JVM dies, and cyclic input objects are legal GraphQL. The
path-guarded form (`UNION ALL` with a visited-path column and a `POSITION` guard) terminates on
cycles, short-circuits to zero rows on an empty seed, works inside a `CREATE VIEW`, and
composes with plain arms under an outer `UNION ALL`. `ROW_NUMBER() OVER (PARTITION BY ...)`
works inside a view. The lookup closure uses the path-guarded form; its seed is the retired
input-field site, so on every accepted schema the recursion never expands.

**The views.** The `intent_` reserve is taken: `intent_authored_field_claim` and
`intent_authored_type_claim` are the derived stratum's first residents, and the DDL header's
reserve paragraph updates to record that the stratum arrived as views (a family is named for
whose vocabulary its rows are written in; materialization is not the discriminator). Columns:
`graph_name`, the grain's coordinate columns, `classifier` (a classification-kind literal per
arm, decoded Java-side into a sealed vocabulary whose enforcer is a test asserting the view's
distinct values fall inside it), `trigger` (the claiming directive's name, for messages),
`decoded` (boolean), and the application's own `source_name` / `source_line` / `source_column`.
Classifier and trigger are separate columns because slice 3's inferred arms have classifiers
with no directive and the first-client JSON already renders them as different fields. Each
claiming relation contributes a decoded arm, and each claiming directive additionally
contributes an undecoded presence arm (the site-family application rows anti-joined against the
decoded relation, `decoded` false) so the view is presence-faithful where a decode declined;
this is the R609 companion detection landing inside the view rather than beside it, keeping the
axis declaration and the position masks in one place. `@lookupKey` gets no presence arm: it is
an argument-less marker whose decode is total, stated in the view comment. The routine arms
collapse the ordinal grain by picking the minimum-ordinal application's row via `ROW_NUMBER`,
depending on no density invariant. The type-grain arms carry the root-name mask (transcribing
`TypeBuilder`'s root short-circuit); their OBJECT-only co-occurrence is guaranteed upstream by
assembly today (`@error` is declared `on OBJECT`), recorded in the view comment the way
`graphitron_undecoded_argument` records its assembly dependency.

**The detection.** `no.sikt.graphitron.rewrite.derive.AuthoredClaimConflicts`, the store's
first reader: one grouping query per grain over its view, every statement graph-scoped. The
reduction is typed over the classifier vocabulary: more than one claim mints
`Rejection.directiveConflict` naming every claim in the fixed order service, externalField,
nodeId, lookupKey, routine, mutation (which reproduces all three legacy per-site list orders),
except claims equal to exactly the routine and lookup pair, which mints the pinned Deferred
message verbatim. Violations are `ValidationError.forField` / `forType` values identical to
what the validator mints from today's tombstones; mint locations join from the store
(`graphql_field`'s own position, the type's base declaration site), not from the walked model.

**The domain gate, a named scaffold.** The detection mints only at coordinates present in the
walked model's registries, a membership test and nothing more (no `RootType` subtraction; the
masks already keep roots claim-free). Its home is one typed value (`ClaimDomain`) built from
the bundle, whose javadoc names the demand census and states that it is the unreified demand
relation; slice 4's shadow demand rows diff against exactly this value, which is the scaffold's
removal criterion. An ungated detection would move the accept line at the census's E0/F1/F3
populations, which the out-of-scope pins forbid.

**Wiring.** `FactCapture.runWithDetections(...)` returns the detection's violations through a
typed seam (no raw `DSLContext` escapes; the class javadoc stops claiming nothing reads the
store). `runPipeline` reorders capture ahead of validation and merges the violations into the
error stream; `validate()` gains the same capture-and-detect step (today it would silently
lose conflicts); `buildOutput` detects after its existing capture call and merges into the
`ValidationReport`; all three share one private helper. Hosting capture inside `buildBundle`
(the single-drain end state, where violations mint into `GraphitronSchema.diagnostics` before
the model seals) was considered and deliberately deferred: `buildBundle` is the unit tier's
entry point and an H2 boot per test multiplies suite cost with no verdict change. That end
state arrives when capture moves ahead of classification under the umbrella.

**Deletions.** The four conflict sites and both detector lists go
(`detectChildFieldConflict`, `detectQueryFieldConflict`, the ad hoc `@service` with
`@mutation` check, `TypeBuilder.detectTypeDirectiveConflict`), and `PairVerdict` /
`pairVerdict` / `reduceDirectiveConflict` with them. `hasLookupKeyAnywhere` and
`LookupFacts.triggersFor` stay live: the classifier arms still consult them, so the trigger
predicate ends this slice with two homes, bound by an agreement anchor (the lookup arm's
field set equals `triggersFor` over the anchor fixtures, a cyclic one included); the single
home arrives with the arm-by-arm migration under the umbrella. The retirement rejections for
`@notGenerated` and `@multitableReference` stay where they are; a schema combining one of
them with two claiming directives now reports the retirement and the conflict.

**Tests.** The five conflict rows in `GraphitronSchemaBuilderTest`'s three conflict enums
migrate to a store-backed detection test (capture the fixture, build the bundle for the gate,
assert the minted violations), which also carries the agreement anchors, a sibling-graph
scoping guard (a second graph's conflict must not leak into this run's violations), and the
undecoded-fallback cases. The claim views register in `FactCaptureAgreementTest`'s `DERIVED`
arm, whose doc is softened to say a semantic derivation registers with its own anchor rather
than claiming all view agreement vacuous.

**Recorded corners, all verified against the corpus at landing.** Embedded nested-child
conflicts surface today as an enriched rejection at the referencing parent; after
de-tombstoning they surface as whatever the nested arm rejects at the parent, plus the
detection at the child when the child coordinate classifies, so accept/reject is preserved
while message and location churn at that corner. A DELETE-carrier data field that reaches the
registry through `reclassify` is newly inside the gate. Until slice 6 lands the Conflicted
projection arm, a conflicted coordinate renders its arm-order classification on the MCP and
LSP surfaces with the violation on the diagnostics channel only; slice 6 restores the
conflict signal to those surfaces, and this intermediate is deliberate rather than
discovered.

**Deviations discovered at implementation (recorded per the rule above).**
`OperationMemberRelation`'s mint carried an invariant the design had not surfaced: its
membership half fires member kinds from the raw trigger facts and its payload half throws when
the classified leaf carries no matching payload capability, a pairing that was consistent only
because conflicted coordinates tombstoned out of the mint's domain (`UnclassifiedField` is not
an `OutputField`). With conflicts de-tombstoned the losing directive's trigger fact survives
beside an arm-order winner that cannot carry its payload, and the mint crashed the build
(`buildBundle`, so the LSP path too) instead of letting the detection report. The fix gates the
service and write triggers on the leaf's carrier capability, reading the capability the same
way the membership half already reads `SqlGeneratingField` and `LookupResolution`; on every
conflict-free schema the gate is a tautology (the walk either followed the trigger or
tombstoned the coordinate), so only formerly-crashing shapes change behaviour. Second, the test
migration was larger than the five enum rows the record counted: five named
`GraphitronSchemaBuilderTest` methods (the `@service` with `@routine` conflicts at child, root
single-node and root chain positions, the root `@routine` with `@lookupKey` deferral, and the
three-directive dominance rule) and the typed directives-list assertion in
`R58TypedRejectionPipelineTest` asserted the same tombstones and migrated to the detection test
alongside them. Third, a Mutation field carrying `@routine` beside `@service` and `@mutation`
now reports the walk's single-node routine deferral and the detection's `@service`,
`@mutation` conflict, where the legacy order reported the deferral alone; both sides reject,
the second message is additive.

## Slice 3 implementation record (settled at implementation start, 2026-08-10)

Fixed before the first code change, same discipline as slice 2: wiring facts checked against the
live code, H2 facts probed on 2.4.240, principles-architect consult folded in; deviations get
recorded here.

**Wiring facts that shaped it.** The legacy column-match arm is the fall-through of
`FieldBuilder.classifyChildFieldOnTableType`: a scalar-or-enum-returning field on a table-backed
parent, undiverted by any directive branch, resolves `@field(name:)`-or-the-SDL-name through
`ServiceCatalog.resolveColumn` into `JooqCatalog.findColumn`, whose match is two-tiered (generated
Java name first, SQL name second, both case-insensitive) over the parent's resolved table. Table
resolution (`findTable`) accepts qualified refs split on the first dot and unqualified refs matched
case-insensitively across every schema of the run's catalog, with a multi-schema collision minting
`TableResolution.Ambiguous`, which un-backs the type. The arm's product is uniquely identifiable
from outside: it is the only mint of `ColumnBackedField` carrying `CallSiteCompaction.Direct` (the
other two mints are node-id carriers with `NodeIdEncodeKeys`), which is what the agreement reads.
The arm's domain matches the walked registries: interface fields, input fields, and embedded
nested-type children never register coordinates, so the walked-domain gate (slice 2's
`ClaimDomain`) is the agreement's comparison population, not a per-case exclusion list.

**The membership relation, a recorded deferral landing.** `store_graph` carries a recorded
exemption: an SDL-to-catalog join is underdetermined in a shared store "until a membership relation
says which sources are the joining graph's; such joins are deferred with their consumers, and the
membership relation lands with them." The inferred claim view is the first such consumer, so
`store_graph_source (graph_name, source_name)` lands in this slice: total over every source kind
the run touches (schema files, jOOQ schema packages, classpath entries), because kind is an axis
on `store_source`, not on membership: a kind-filtered relation's completeness would be a function
of which consumers had shipped, so a reader could not tell "not this graph's" from "kind not
captured yet". Written through the sink (graph-stamped, FK-ordered); cleared by the derived
graph-scoped clear with no `StoreRefresh` edit, since the clear enumerates relations by their
`graph_name` column. The comment distinguishes it from the recipe rows beside it: the recipe is
configuration the run held in hand (patterns, including files that do not exist yet), membership
is what the run actually read. The `store_graph` comment updates from deferral to pointer.

**The views.** `intent_column_match_claim` is the classifier's own derivation view, named for the
classifier rather than for the inferred tier (the consult's strongest push, adopted: the census
names some twenty structural arms whose witness sets differ, so a tier-named view is a gravity
well toward nullable-by-kind witness columns, the `UnboundField` smell itself; each structural
classifier lands as its own view, mirroring how each claiming relation contributes its own
authored arm). Classifier literal `TABLE_COLUMN`: a `graphql_field` whose named type has kind
SCALAR or ENUM, whose parent carries a `graphitron_table` row resolved uniquely against
`sql_table` through the graph's membership (qualified split, case-insensitive halves, a
non-unique candidate set yields no claim, transcribing `Ambiguous`; the comment records that
distinguishing `Ambiguous` from `NotInCatalog` is a future resolution-stratum detection over
`graphitron_table`, not something this view's absence encodes), and whose effective name
(`COALESCE` of the `@field` binding and the field name) matches a `sql_column`: jOOQ-name tier
before SQL-name tier, collapsed to one row per coordinate by `ROW_NUMBER` ordered
tier-then-ordinal, all probed on H2 inside a view. The `@field` arm needs no presence fallback and
the comment says so in the `@lookupKey` note's shape: a declined `@field` decode writes no binding
row and the `COALESCE` lands on the field name, which is the walk's own `.orElse(name)`. The row
carries its join witnesses (`table_source_name`, `table_schema`, `table_name`, `column_name`, together the
`sql_column` key with its other columns one join away per the `sql_referential_constraint`
discipline, plus `matched_name` and `matched_by`, the classifier's own products) and the field's own source
position. Masks: the three root names only (transcribing that roots classify before any table
binding is read). No parent-kind gate and no directive knowledge: an INTERFACE or INPUT_OBJECT
`@table` parent yields honest structural rows the domain gate excludes, exactly as slice 2's type
view treats a lone INPUT_OBJECT `@table` claim. `intent_resolved_field_claim` is the reduction the
design body names ("the authored relation unioned with the inferred rows at coordinates the
authored relation does not cover"): the projection `(graph_name, type_name, field_name,
classifier, tier)`, the anti-join at the coordinate grain, `tier` a literal per arm (`AUTHORED` |
`INFERRED`) so which relation carries a claim's provenance is a column read rather than a
hand-maintained classifier-to-relation mapping in every reader; no trigger/decoded/witness
component crosses, so nothing goes nullable by kind. No type-grain twin: no inferred type arm
exists yet, and a pure re-projection would register vacuous. The alternative, baking the authored
anti-join into the classifier view, was rejected because the masked structural reading must
survive as data ("would classify as a table column; `@service` overrides it"), and an anti-join
inside the classifier relation destroys exactly those rows. The DDL header's `intent_` paragraph
updates to record the stratum's two-layer shape (per-classifier derivations, then the reduction
over them), so the next resident picks a layer deliberately. Mechanically, the derived-stratum
section moves to the DDL's tail: the file executes sequentially, and the classifier view is the
stratum's first reader of the `sql_` family, which the old section position preceded.

**Transitional residue, recorded as two cases.** First, the diverting directives: `@reference`,
`@pivot` and `@sourceRow` divert the legacy walk ahead of the column arm but are not authored
claims, so the anti-join does not mask them; a diverted coordinate whose name happens to match a
parent column carries a column-match claim the walk contradicts until those arms migrate under the
umbrella, at which point the same anti-join masks them with no view edit. The sweep expresses this
exception relationally, an anti-join against the diverting applications in
`graphql_field_directive` rather than a Java skip-list, so a new diverting directive
announces itself as a sweep failure. Second, and not the same shape: a `Node.id` field on a node
type whose table also has a literal `id` column carries no directive at all, so nothing authored
can mask it; the walk rejects it (`rejectShadowedIdColumn`) while the view claims `TABLE_COLUMN`.
That coordinate is the first live instance of two structural readings on one coordinate (the
node-id reading is a future inferred arm, and `BuildContext.rejectShadowedNodeId` is the
structural-ambiguity rule's named migration target), so the pin frames it as one claim showing
where the target model shows two claims and a violation, not as a masking story.

**Agreement.** A new derive-package test is the shadow reader and the registered anchor: the two
views register in `FactCaptureAgreementTest`'s `DERIVED` arm, while `store_graph_source` is a
capture-written base relation and registers `EQUALITY`, with its anchor beside the other store
anchors (the run's resolved input set reduced two ways, under two graphs so ownership is
distinguishable). The corpus sweep opens one store and captures every `ClassifiedCorpus` example
as its own graph (the example id as `graph_name`: one H2 boot, not one per example, declining
the cost shape slice 2's record already declined once; the partition dimension exists for exactly
this, and it makes sibling-graph scoping a property of the sweep itself), walks each example
through the legacy classifier, and asserts two-way agreement over the walked domain: every
`ColumnBackedField`+`Direct` coordinate has exactly one masked column-match claim whose witnesses
name the walk's resolved table and column, and every masked claim at a domain coordinate is such
a classification, less the relationally-expressed residue above. Targeted fixtures pin the
witness content, the `@field` rename, the jOOQ-name-tier precedence, the ambiguity decline, the
authored-coverage mask with the raw row surviving, sibling-graph scoping through the membership
relation, the two residue cases, and the closed value vocabularies (`classifier`, `matched_by`,
`tier`). No production wiring changes: slice 3 is a shadow slice, and the full-build corpus
signal stays unchanged by construction.

## Slice 4 implementation record (settled at implementation start, 2026-08-10)

**Wiring facts, re-verified at the code before design.** Demand is negative space exactly as the
census states: `GraphitronSchemaBuilder.classifyFieldsOfObject` is called once per visited object
and its early returns are the skips (the structural nesting-target verdict, the five connection
machinery arms), the interface visitor arm classifies only the type, and the DELETE-carrier
special case classifies errors-shaped fields only, with the data field repaid solely by the
`IdElement` arm of `classifyDeletePayloadField` after write-target resolution, return-type
validation, the DeleteRows walker and ID-encoder resolution have all succeeded; every earlier
bail leaves the data-field coordinate verdict-free (the census's silent-loss window).
`FieldBuilder.classifyRootField` dispatches on the literal names Query and Mutation and mints
the Deferred verdict for everything else, so a renamed root's fields silently take the
nesting-target skip (the census's renamed-root hole). Two predicates the skips leans on are
reflection-derived and out of transcription reach this slice: `TypeBuilder.isDirectivelessNestingTarget`
conjoins on the `RecordBindingResolver` fixed point (accessor probes over backing classes,
container peeling, propagation to convergence), and the DELETE scan's record-element arm reads
the same fixed point. Two predicates are purely structural and transcribe directly:
`BuildContext.isConnectionType` (an object with an `edges` field whose element object has a
`node` field; the pageInfo resident is the literal `PageInfo` name when SDL-declared), and
`SchemaReachability`'s seed and descent-edge set. `graphql_root_operation` is total over
effective roots (name-convention defaults are rows with null positions), which the DDL wrote
down in advance as this derivation's seed. `NodeDeclaration.isNodeType`'s inference conjunct
reads jOOQ node-metadata constants that capture does not hold, so the seed transcription is
`@node` union (`@table` and `implements Node`), an over-seed treated as a named residue with a
removal criterion below; its only exposure is a metadata-less such type that no field reaches.

**The domain relation is materialized, written at capture cadence in production; everything
above it stays views.** This item's own H2 facts paragraph rules out the recursive-CTE `UNION`
form (non-termination on cyclic graphs, and type graphs are routinely cyclic) and the
path-guarded form enumerates simple paths, which explodes on dense schemas; the semi-naive loop
is monotone and bounded by the type count, and it is exactly the `INSERT..SELECT` stratum shape
the derivation-vehicle decision names. A derive-package writer (`ReachabilityRows`) clears the
run's own graph partition, inserts the seeds (root operations, `@node` rows, the inference
approximation above, `@key` carriers, and survivor-directive argument types with the
generator-only exclusion bound from `DeclaredDirectives.names()` as a query parameter, so the
live Java vocabulary is not duplicated into DDL literals; the writer's javadoc `{@link}`s the
vocabulary so the reference gate holds the linkage, and the table comment states that the seed
set is parameterized, since a relation on the agent-facing SQL surface whose content depends on
a Java constant is not self-describing from DDL alone), then iterates one frontier statement
(field targets, argument types, union members, implements in both directions with the reverse
edge narrowed to object implementors, input-object field types) under a counted bound that
throws past the graph's type count, so a non-monotone edit fails loudly instead of hanging a
build. `intent_type_domain` records every reached named type of every kind, named for the
assertion (the classification domain's type members) rather than the graph operation, so a
reader does not join an intended-traversal surface as neutral schema reachability. The
architect consult rejected the test-only-writer shape this record first held: a materialized
relation no production path writes is cleared by `StoreRefresh.graphScoped` on every capture
and never repopulated, leaving a declared relation whose zero rows are plausible, and it makes
the partition-dimension gates pass vacuously (the failure mode `FactSchemaGateTest`'s own
javadoc names). The writer therefore runs inside capture after the flush, at capture cadence
per the lifecycle doctrine; nothing reads the rows in production, so output identity is
untouched, the warm/cold census anchors the lifecycle with no new machinery, and the clear
rule is real. `FactCaptureAgreementTest`'s `DERIVED` arm doc widens to say materialized
capture-cadence derivations register there too, anchored like views, so the closed-four-arm
doc stays true.

**Rules are stated at their authored grain, arms stay unmasked, and a reduction per grain owns
the one-why answer.** Every demand and exemption rule this slice transcribes is a property of
the parent type, so the rules land type-keyed and the field grain is a mechanical join, made
legible as such rather than materialized into rule literals (the consult's grain finding; a
future genuinely field-grain rule unions into the reduction marked as what it is).
`intent_field_demand_rule` holds the demanding parents, a rule literal per arm: root-operation
types (keyed by `graphql_root_operation`, so the relation states the intended root rule and the
renamed-root hole becomes a visible diff rather than a transcribed defect), `@table` types,
`@error` types, and producer payloads (return types of an `@service`, `@externalField` or DML
`@mutation` field, the grounding capture can see), every arm narrowed to OBJECT kind. The
DELETE carrier's data field is deliberately demanded through the producer-payload arm, not
exempted: the census records its verdict loss as a defect, and the acceptance text names it as
what the future gate flip changes. `intent_field_exemption_rule` holds the intended skips with
a `reason` per arm (`classifier` stays reserved family-wide for classification kinds):
interface parents, input-object parents (the census's trace-only population, made explicit as
rows), underscore-prefixed parents, connection machinery (the structural recognition above),
and the directiveless nesting target stated by its own absence-shaped predicate (no classifying
directive, no root binding, no store-visible producer), which is the walk's own rule
transcribed, not an anti-join against the demand view; arms overlap where two readings are
true (a connection type is also directiveless) and both rows survive, per slice 3's
masked-reading argument. `intent_resolved_field_demand` is the field-grain reduction over the
domain, the rules and `graphql_field`: demand beats exemption, machinery beats the catch-all,
one row per reachable output coordinate carrying its winning rule or reason; the future gate
flip reads this view. The type-verdict grain mirrors the shape: `intent_type_demand` (arms as
above plus an explicit root-operation arm, since `RootType` is a registered verdict today, plus
every reachable interface and union), `intent_type_exemption` (underscore prefixes, and a
named leaf-kind deferral arm for scalar, enum and input verdicts whose comment states it
retires when those classifiers migrate, so the bound is a row-level fact rather than a
test-side filter), and `intent_resolved_type_demand`. Support types need no exemption arm at
either grain, and the reason is load-bearing rather than observational: they are reachable
only through generator-only directive argument edges, which are not walk edges, so the
retained-published case classifies as an ordinary reachable leaf and the rest never enter the
domain; the coverage gate below is the enforcer, because a drifted seed parameter would make
support types reachable and fail it loudly.

**The residues are named, store-derivable where pinned, and carry removal criteria.** Types
bound only through the reflection fixed point (accessor-chain propagation, the two-level
record-composite carrier) are in neither rule relation; they are the slice's transcription
residue. The population gets the shape `ClaimDomain` already has: a named value in the derive
package built from the walked model (class-backed `ResultType` parents), javadoc stating it is
a scaffold whose removal criterion is the binding-walk classifiers' migration to captured
facts, so the scaffold is discoverable from the production package rather than one test's
helper. The two demanded-but-unregistered populations are derived from the store, not from
Java-side coordinate lists (slice 3's residue discipline): DELETE-carrier data fields from
`graphitron_mutation`, non-conventional root bindings from `graphql_root_operation`, so a
third instance of the same hole class fails the diff instead of hiding. The node-inference
over-seed is likewise a named residue, not a fidelity note: its removal criterion is capturing
the jOOQ node-metadata constants into the classpath family (the gap also blocks the
Relationships section's commitment that `NodeDeclaration` becomes a derivation), and the sweep
asserts the over-seeded population is empty over the corpus so the exposure claim has an
enforcer. The type-grain residue splits into the same named populations rather than one
directiveless-object bucket, so a renamed root type and the over-seed excess cannot hide
inside a broader structural property.

**Agreement.** The seven relations register in `FactCaptureAgreementTest`'s `DERIVED` arm per
its own (widened) doc: registration in the driver, anchor with the reader's test, which is a
new derive-package corpus sweep in the slice-3 shape (one store, each `ClassifiedCorpus`
example captured as its own graph, walked through the legacy classifier). Per graph it
asserts: the materialized domain restricted to composite kinds equals
`SchemaReachability.reachableTypeNames`; resolved field demand equals
`ClaimDomain.fieldCoordinates` outside the named residues, with the two disagreement
directions pinned against store-derived populations (registered-but-undemanded rows only under
reflection-bound parents, demanded-but-unregistered rows only at DELETE-carrier data fields
and non-conventional root bindings) and each pinned population asserted non-empty on the
fixtures that create it, so the pins cannot go vacuous; every reachable output coordinate is
demanded, exempted or residue (the coverage gate, so no population is silently unaccounted);
and resolved type demand agrees with `ClaimDomain.typeNames` under the same discipline, the
leaf-kind deferral rows carrying the bound as data. Targeted fixtures pin the renamed
subscription root (demand strictly exceeds the registry there, the hole made visible), a
failing and a succeeding DELETE carrier (the data field demanded in both, registered only
where the IdElement repayment fires), the interface and machinery exemption rows with an
overlap surviving unmasked in the rule relation, and the closed rule and reason vocabularies.
`ClaimDomain`'s javadoc updates to name the diff that now exists; the gate itself dissolves
only when the follow-up item flips the detection to read demand, so the scaffold stays. The
only production wiring is the write-only capture-cadence writer above; nothing reads the rows
in production, the full-build corpus signal stays unchanged, and slice 4 gates nothing.

**Landed with two substrate fixes and three named fidelity notes.** First fix:
`graphql_root_operation`'s comments promised name-convention default rows (null positions), but
capture wrote rows only from explicit schema definitions; the relation gained its first
consumer here and the promise is now kept (`SdlFactCapture.captureConventionRoots`, firing only
when no schema definition exists, after the explicit bindings so a spelled binding wins the
claim). Second fix: the DDL had grown past what H2 executes as one script; a multi-statement
command recurses once per remaining statement, so booting the store overflowed the thread
stack at a depth that varied with the caller's own stack (surfacing as an order-dependent test
failure, with `openAt`'s in-memory fallback masking the cause). `GraphitronModelStore.create`
now executes the DDL one statement at a time through a quote- and comment-aware splitter, flat
at any schema size, and a boot failure names the exact statement. The fidelity notes, each a
named population in the sweep: the facet shapes are subtracted from the domain comparison
(capture's connection expansion records the `@asFacet` marker but does not synthesize the facet
types; closes when it does), the five spec built-in scalars may be walk-registered without a
demand reading (one of their reaching edges, a built-in directive's argument, exists only in
the assembled schema, never in the registry capture transcribes), and the corpus sweep appends
the Relay `Node` interface to the captured document when absent so both sides parse the same
text (the walk harness injects it).

## Slice 5 implementation record (settled at implementation start, 2026-08-11)

Fixed before the first code change, same discipline as the prior slices: wiring facts checked
against the live code, the expansion probed on H2 2.4.240, principles-architect consult folded
in; deviations get recorded here.

**Wiring facts that shaped it.** `BuildContext.classifyInputField` is the single classification
funnel for every input-field surface: `TypeBuilder` classifies `@table` input types against the
input's own table, `InputFieldResolver.resolve` classifies plain inputs at consumer time once
per consuming field per resolving table, and the funnel's own nesting recursion covers embedded
groupings. The malformed-shape predicate is evaluated at two sites today
(`GraphitronSchemaValidator.validateInputUnboundField`, which reaches only registry-walked
fields, and `FieldBuilder.rejectAtConsumer`, which evaluates it only outside a cascade, the two
halves of the R221 hole), plus a third habitat the consult surfaced: the validator method is
also the DML write-target paths' mirror (`collectInputFieldRejections` feeding
`mintMirroredInputFieldRejections`). The `@condition(override: true)` eager collapse demotes a
resolved column into `UnboundField` on the plain-column path only; the same authored shape on
the `@reference` and same-table `@nodeId` paths keeps its column carrier, so the write-path
sites that discriminate `UnboundField` with an override condition see only some condition-owned
fields, a live asymmetry. The polymorphic path walks input conditions once per participant
table, which fixes the reading of "mints once per use-site join": the verdict's identity is the
use site joined with the resolving table, and the diagnostics channel's value dedup delivers
exactly that. `BuildContext.diagnostics` already states the doctrine ("accumulated instead of
demoting a classified verdict"), so the mint surface exists; this slice adds producers.

**The carrier split, the consult's strongest push, adopted.** The collapse was right that an
override-owned field's column is dead storage and wrong about the arm it reached for. A new
permit `InputField.ConditionOwnedField` carries the fields the explicit condition method owns
entirely (compact constructor pins the condition present with `override: true`); both
override-owned mints (column resolved and column missing) produce it, so the carrier means one
thing. `UnboundField` becomes the genuine-miss carrier: `attemptedColumnName` is always the
looked-up name (non-null, enforced), the null-as-discriminator semantics retire per this item's
retired-vocabulary entry, and its condition axis narrows to empty (cascade-dependent) or
`override: false` (the malformed shape, whose fact is minted at the funnel). The four
discrimination sites (`MutationInputResolver`, both DML walkers, `rejectAtConsumer`) switch on
carrier identity instead of re-evaluating `condition().isPresent() && override()` longhand;
exhaustive switches (validator, projection, generators) gain the arm mechanically. Deleting the
collapse instead was considered and rejected: a `ColumnBackedField` carrying an override
condition would be admitted as a writable column by the DML walkers, silently dropping the
author's filter, which is exactly what `OverrideConditionNotSupported` exists to prevent.

**The two mints, at their honest grains.** The malformed-shape verdict mints at the funnel's
column-miss fall-through, keyed by definition joined with the resolving table: the consult's
correction, adopted, because a plain input consumed by two fields on two tables can be
malformed at one and fine at the other, and the channel's own doctrine (facts naming different
tables are different facts, `sameInputTypeAgainstTwoTables_keepsBothFacts`) already refuses
engineered collapses. The item body's "definition-keyed" holds exactly where the resolving
table is the definition's own (`@table` inputs); the honest grain names the table. The mint
reads the authored directive (`readConditionDirective`), not the built `ArgConditionRef`,
because a failed condition build empties the carrier's condition by design and the fact
asserted is about the authored shape. It fires unconditionally on cascade context, closing
R221. The cascade verdict keeps its single evaluation site in the walk (capture runs after
classification, so production cannot read the store mid-walk; the ordering flips under the
umbrella) but its mint moves: a use-keyed `ValidationError` into diagnostics at the leaf
field's own location, keeping the typed `UnknownName` arm with the Levenshtein candidates,
message carrying the resolving table and the serialized occurrence path. The consuming field
keeps one consequence rejection, a deliberate intermediate recorded in slice 2's shape: full
de-tombstoning needs the downstream-assumption audit the `OperationMemberRelation` deviation
proved necessary, and it arrives with the store-side detection. The path riding in prose is a
recorded deferral, not the design's answer: the occurrence identity becomes a typed component
when the diagnostics surface grows one, and until then a named fixture asserts the Java
serialization equals the store key so the two homes cannot drift silently.

**The validator arm retires, and the mirror corner is named.** `validateInputUnboundField`
deletes; its registry-walk arms become no-op comments in the established pattern. On the DML
write-target paths the mirror stops short-circuiting the malformed shape, so the cause identity
moves: the located, typed fact now mints at the funnel (the directive's own line, a better
location), and the walkers' own rejections carry the consumer consequence.
`MutationTableArgClassificationTest`'s mirror pin migrates with it; message and location churn
at that corner, deliberate rather than discovered.

**The relations.** `intent_input_occurrence_path` (key: `graph_name`, `path`, the serialized
occurrence; columns: the use-site coordinate triple, the root input type, the leaf's named
type, depth) and `intent_input_occurrence_path_step` (key: path plus 1-based ordinal; columns:
the step's container input type, field name, named type), homogeneous over input-field steps
only, the consult's correction to a step-kind design whose columns went nullable by kind: the
use-site field and argument are fixed by construction and already live on the parent row, so
step rows carry only the recursion. Every prefix of a path is itself a row. The writer
(`derive.InputOccurrencePaths`, invoked in `FactCapture.capture` beside `ReachabilityRows`)
seeds depth-0 rows from `graphql_argument` rows whose named type has kind `INPUT_OBJECT` and
expands semi-naively; the cycle guard is relational (a path stops expanding when its leaf type
equals the root input type or any earlier step's named type, the walk's own
`ClassifyContext.expandingTypes` first-visit rule restated), so the row population equals the
recursion tree the build already walks and simple-path enumeration introduces no new
asymptotic class, which is why the explosion argument that rejected the path-guarded CTE for
the type domain does not apply here; the loop bound is the graph's input-object type count,
exceeded throws. `intent_input_occurrence_override` is the cascade fact as a predicate over
path prefixes with its witness kept: one row per path with an enclosing `override: true`,
carrying the nearest enclosing overriding site's coordinate (nullable `argument_name`
distinguishes the two condition relations the witness joins, their own key shapes); absence is
the no-override reading. Production reads none of them this slice; `rejectAtConsumer`'s
threaded boolean retires when capture precedes classification.

**Agreement.** A derive-package pipeline-tier shadow test sweeps `ClassifiedCorpus`, one store,
one graph per example: the derived path population equals a structural reference enumeration
recomputed from the assembled schema (deliberately classification-independent, so tombstoned
consumers cost no exclusion list); the override rows equal the condition-directive expectation;
and every cascade verdict minted into diagnostics names a derived path with no override row.
That last binding is one-directional and the test's javadoc says so: the store predicate being
too narrow is invisible until the store-side unbound predicate lands with its own slice; the
fixture populations are asserted non-empty so the binding cannot go vacuous, and the
serialization equality is pinned on a named fixture. Targeted fixtures pin the admitted
cascade, the rejected cascade at two use sites (two facts), the malformed shape inside an
override cascade (R221's exact hole, now failing), and cyclic input nesting (derivation
terminates, the walk's circularity rejection unchanged). All three relations register
`Arm.DERIVED` in `FactCaptureAgreementTest` with the shadow test named as anchor.

**Counts move deliberately.** `InputFieldFanInDiagnosticsTest` gains the two new producers in
its honest-record partition (the cascade verdict is a typed `UnknownName` row, the malformed
shape a structural row) and the count assertions state the intended count per shape. The
named `GraphitronSchemaBuilderTest` cases move with the design: the override-collapse
projection test follows the carrier split, the malformed-shape boundary test asserts the funnel
diagnostic where it asserted a consumer tombstone, and the bare-miss consumer test asserts the
cause in diagnostics and the consequence on the consumer.

**Deviations discovered at implementation (recorded per the rule above).** One: the
malformed-shape mint is typed `Rejection.AuthorError.UnknownName` rather than structural. The
retired consumer arm carried the attempted column and the Levenshtein candidates for exactly
this shape, and dropping to prose would have moved a producer row backwards in the fan-in
test's honest-record partition; the malformed remedy lives in the summary, so the fact reads
as both the shape violation and the name miss it is. Both new producer rows are therefore
`UnknownName` rows. Two: the leaf ratchet moved 4 to 5 for the `InputField` pin (the carrier
split adds a permit), with the history line in `LeafRatchetTest` naming the split; the
projection-coverage and enum-truth-table obligations gained their `ConditionOwnedField`
instruments in the same commit (the projection test retargets to the new carrier, the
truth table gains a `CONDITION_OWNED_FIELD` case, and the admitted-cascade acceptance test
becomes `UnboundField`'s instrument).

## Slice 6 implementation record (settled at implementation start, 2026-08-11)

Fixed before the first code change, same discipline as the earlier slices: wiring facts checked
against the live code, principles-architect consult folded in; deviations get recorded here.

**Wiring facts that shaped it.** `GraphQLRewriteGenerator.buildOutput()` is the only production
snapshot producer (the generate path throws on validation errors before emission, so only the
LSP/MCP path ever renders a broken schema), and it currently builds the snapshot *before* the
capture-and-detect step; the reorder is part of this slice. `CatalogBuilder.buildSnapshot`
projects purely from the walked model. The claim views carry coordinate, classifier, trigger,
decoded and position but no slot facts; those live on the per-directive semantic relations
(`graphitron_mutation.operation` and `.table_ref`, `graphitron_service.class_name` and
`.method`, `graphitron_external_field`, `graphitron_routine.routine_ref`,
`graphitron_field_node_id.node_type_ref`). Since slice 2 a conflicted coordinate classifies as
its arm-order winner, so the projection map always has an entry for the overlay to replace. The
DML write-target precedence has exactly one producer
(`MutationInputResolver.resolveDmlWriteTableRef`: the return-derived rung for INSERT/UPDATE,
then `@mutation(table:)`), and the walk deliberately refuses an input-argument table bridge.

**The projection split.** `FieldClassification.Unclassified` splits into `Unresolvable(reason)`
(the genuine nothing-resolved arm, renamed so the narrowed field-grain meaning is visible on
the surface the LSP teaches with; `TypeClassification.Unclassified` keeps the wide meaning and
the name divergence dates the type-grain follow-up) and `Conflicted(claims, violation)`.
`Claim` is a sealed hierarchy in the catalog package, one arm per claiming classifier
(`Service`, `ExternalField`, `NodeId`, `LookupKey`, `Routine`, `Mutation`), each carrying
exactly its own slot facts plus the shared provenance (trigger, decoded, the claim's own
position decoded to the catalog module's `CompletionData.SourceLocation`; no graphql-java type
crosses the projection boundary). A `TableClaiming` capability interface reifies the table
axis, so the edge producer reads a capability instead of branching on a nullable component.
The wire field is `classifier`, the store column's own name; `classification` would re-import
the classifier-versus-trigger ambiguity the DDL comment resolves. Deviation from the
first-client draft, recorded: the draft's `"classification": "DmlMutation"` is not renderable
from decoded slot facts alone (`DmlMutation` versus `DmlRecord` needs return-shape walk
knowledge, and `Service`'s projection name is position-dependent), so by the item's own
first-client constraint the payload renders the claim vocabulary through the arm's simple
name, never a projection permit name.

**Slot facts.** The `Mutation` claim carries the operation as written (a string, so a broken
verb literal renders faithfully) and its table slot is `graphitron_mutation.table_ref` alone:
a claim's slots are the directive's own decoded columns, and inventing a resolution rung (the
input argument's `@table`, or the return-derived rung) would fork the single-producer
write-target precedence and let the projection assert a table the classifier refuses.
`Service` and `ExternalField` carry the class and method pair, `Routine` the chain's
`routine_ref`s in application-ordinal order (the claim view collapses the repeatable ordinal
grain to one claim row per coordinate, so a chain never reads as
routine-conflicting-with-routine, and per slice 2's own words the chain is one claim whose
steps are the claim's slot facts; the first landing carried only the minimum-ordinal ref,
caught in review), `NodeId` its `node_type_ref`, `LookupKey` provenance only. Enrichment joins
each claim's semantic relation Java-side in the derive reader, scoped to conflicted
coordinates; per-classifier slot views were considered and declined because each would
re-project a single base relation 1:1 with no derivation content. The arm-list agreement has a
named enforcer: arm selection is an exhaustive switch over `AuthoredClaim`, whose two-way
vocabulary binding to the view arms is already test-enforced, so a claiming relation added to
the view without an arm fails the vocabulary round trip and the switch fails to compile
without a new case.

**The seam.** `AuthoredClaimConflicts` returns a typed reduction outcome per field coordinate
(a sealed verdict: `Conflict` carrying the claims and its rejection, `Deferred` likewise for
the recognized routine-plus-lookup pair), and the error stream derives its `ValidationError`s
from the verdicts at one site, so "which coordinates project `Conflicted`" is a type and never
a re-test of the reduction predicate. `FactCapture.runWithDetections` returns the widened
product; `buildOutput` reorders capture-and-detect ahead of `buildSnapshot` and passes the
`Conflict` verdicts; the overlay's stated contract is that it writes only over coordinates the
walked projection map already carries (the domain gate makes that true, the contract makes it
deliberate). `validate()` and the generate pipeline read only the violations.

**The surfaces.** `SchemaView` renders kind `Conflicted` with the claims array and the
violation. `EdgeProducer`'s `Conflicted` arm emits one TARGETS edge per `TableClaiming` claim
whose table slot is present (per the closed design question: table edges only; the `Service`
arm visibly carries a method pair the edge producer ignores, noted at the arm so a later
reader does not "fix" it), joins `EDGE_BEARING_FIELDS`, and `EdgeCoverageTest` pins the
partition. The LSP label arm renders the simple name; the hover body lists each claim as
classifier and trigger with its slot facts, then the violation. `lspColumnDispatch` maps
`Conflicted` to `Silent`. The MCP instructions prose gains the `Unresolvable` and `Conflicted`
vocabulary where it names `Unclassified` for fields today.

**Containment made structural.** `GraphitronField.UnclassifiedField.definition()` is deleted
in this slice (186 mint sites across `FieldBuilder` and `FieldRegistry` drop the component; no
main-source reader exists), so "the projection sources from claims, never from the graphql-java
node" stops being reviewer prose and becomes unrepresentable. The one test that read it
re-anchors its directive-application assertion on the assembled schema.

**Tests.** Acceptance drives `GraphQLRewriteGenerator.buildOutput()` on a pipeline-tier fixture
whose mutation carries both `@mutation(typeName: DELETE, table: ...)` and `@service`, asserting
the snapshot's `Conflicted` arm reports the `Mutation` claim's DELETE verb and table and the
`Service` claim's method pair while the report still carries the conflict violation; the
deferred routine-plus-lookup pair is asserted *not* to overlay. The motivating query is
asserted rather than narrated: a reverse-edge test in graphitron-mcp pins that the broken
DELETE appears under its target table, so `Conflicted` joining `EDGE_BEARING_FIELDS` has a
live instrument instead of repeating the pinned-but-unreachable mistake this item criticizes.
The rename sweeps the projection, hover and MCP tests that assert `Unclassified` at field
grain. A self-review pass before the In Review handoff added the one instrument the first
round missed: a server round-trip in `GraphitronMcpServerTest` pinning the claim JSON itself
(the `classifier` / `dmlKind` / `tableName` / `violation` keys and the omitted-when-absent
location), since the catalog records, hover text and edges were pinned but the first-client
wire keys were not.

## Retired vocabulary (expected; finalise at the Done gate)

- `FieldBuilder.PairVerdict` / `pairVerdict` / `reduceDirectiveConflict`: the pairwise reduction,
  replaced by the authored-relation key constraint plus the recognized-combinations rule.
- `FieldBuilder.detectChildFieldConflict` / `detectQueryFieldConflict` /
  `TypeBuilder.detectTypeDirectiveConflict`: the per-position detector sites, dissolved into the
  two grain detections (slice 2).
- The three-cases-in-one-record reading of `InputField.UnboundField` and its `attemptedColumnName`
  null-as-discriminator semantics (the component itself may survive as an honest fact).
- `UnclassifiedField.definition()` (retired in slice 6): the claim relations subsume the
  rich-error-context role its javadoc claimed; deleting it closed the parse-boundary containment
  exception the projection slice protects.
