---
id: R589
title: "Classification is a relation; validation adds facts"
status: Backlog
bucket: architecture
priority: 4
theme: classification-model
depends-on: []
created: 2026-08-04
last-updated: 2026-08-04
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
them or decline, and the claims land in a relation, `Coordinate -> Claim*`. **Validation adds
derived facts**: a wrong claim count on a coordinate becomes a located violation; the claims stay.
**Planning returns to the single-classification worldview**: commands are created from the facts,
and on a valid schema the claims relation *is* functional, so the view planning reads
(`Coordinate -> Classification`, total) keeps today's contract and no downstream consumer grows a
cardinality branch. Execute/render stays the simplest stage. The count check is not a mechanism; it
is one validation rule over the relation.

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
one, the claims relation. The amendment is deliberate, is the first slice of the scope below, and is
reviewed through this item's gates; R333 stays in Ready.

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
rejection; the classifications they would have produced do not.

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
no entries to the projection's index. The input half of any projection fix is therefore blocked on
input-member coordinates (umbrella work), not on this item.

**Consumer layer: views re-derive from text what the model had.** Measured on a live session against
a consumer schema mid-migration, an agent asked to repair `@mutation` usage on the delete mutations
ran `grep -rn 'typeName: DELETE'` across a whole monorepo. `schema(type: "Mutation")` answers that
for mutations that *classified*, projecting `dmlKind` and the resolved `tableName`. For the broken
ones, which are exactly the population needing repair, it answers `Unclassified` plus prose. So the
tool is useless precisely where the author needs it, the agent falls back to SDL text, and SDL text
is worse data: it reads intent rather than the verdict and carries no table binding.

## Target model: scatter-gather classification over a claims relation

A claim row is `(coordinate, classification payload, provenance, tier)`. Provenance names the
classifier that claimed and the trigger it claimed on (a directive application, or the structural
trigger), which is what turns a conflict message from "@service, @routine are mutually exclusive"
into "the service classifier claimed service-backed from `@service` at line 12; the routine
classifier claimed routine-backed from `@routine` at line 12".

**Guards are part of a classifier's contract.** Classifiers that might step on each other make the
interaction explicit by knowing each other's directives: the condition classifier and the lookup-key
classifier both know `@lookupKey`, one claims only when it is present, the other only when it is
absent. A claim is therefore a gathered fact about the schema as authored, never a counterfactual
("what `@service` would have produced had `@routine` not been there"), which is what disqualified
running today's arms speculatively: the arms are not pure, and their output under co-occurrence is a
derivation under a false premise.

**Guard drift is self-reporting.** Too-loose guards produce two claims and surface as a conflict
violation carrying both; too-tight guards produce zero claims and surface as unclassifiable. Drift
can never silently produce wrong code; it lands as a cardinality violation on the first schema that
exercises the overlap. The invariant carries its own enforcer, where today a wrong `pairVerdict`
entry or a name missing from the two hand-enumerated detector lists misclassifies silently, because
arm order in `FieldBuilder` is the de facto precedence table and nothing renders it.

**Two tiers, one explicit precedence rule.** Claims are authored (directive-triggered) or inferred
(structural: a name resolving against the parent's table, a grouping type nesting). Resolution
prefers authored over inferred; conflict is meaningful within the winning tier. This is the one
global rule that replaces the invisible arm ordering implementing exactly this today, and it keeps
structural classifiers from enumerating the full directive set, which would re-centralise the very
list the guards distribute.

**Classifiers are pure.** A classifier is a function from gathered facts to claim rows. Today's arms
mint into `ctx.diagnostics()` and the field registry mid-flight; under this model, violations and
registry entries come out of validation and planning, never out of classifiers.

## Scope

1. **Amend the umbrella.** Add the claims base relation and the stage vocabulary (classification
   gathers, validation derives, planning returns to the functional view) to R333's model text. First
   slice, reviewed through this item's gates; R333 does not leave Ready.
2. **The claims relation ships.** `Coordinate -> Claim*` with payload, provenance, and tier. The
   existing monolithic classifier participates as a single claim producer in the interim. Validation
   derives cardinality violations into `GraphitronSchema.diagnostics` with every claim retained;
   planning reads the functional view, total on a valid schema.
3. **Conflicts record instead of reduce.** `reduceDirectiveConflict` stops reducing to a verdict and
   starts recording claim rows (floor payload: the directive application plus the classification
   kind it claims) and one violation. The pairwise table and the two hand-enumerated detector lists
   become derivations over one claiming-directive relation rather than a fourth list.
4. **"Unbound" stops being a demotion target.** The definition-keyed fact ("no column bound,
   attempted name X") stays on the carrier as a positive fact. The malformed-shape verdict
   (`@condition(override: false)` with no column) mints into `diagnostics` at classify time, with no
   later retraction. The cascade verdict is use-keyed and mints once per use-site join; each of the
   two predicates gets exactly one evaluation site (today `FieldBuilder.rejectAtConsumer` and
   `GraphitronSchemaValidator.validateInputUnboundField` overlap). This subsumes the validator-mirror
   gap R221 (`validator-walks-plain-input-unbound-fields`) owns, or narrows it to a residue; settle
   which at implementation and close or re-scope R221 accordingly.
5. **The output-side projection preserves the claims.** A broken DELETE mutation still reads as a
   DELETE mutation with its intended table on the LSP and MCP surfaces, sourced from the claims
   relation, never from `UnclassifiedField.definition()` (a graphql-java node; reading applied
   directives off it downstream would widen a parse-boundary containment exception into two more
   consumers). Input-side projection is descoped: input fields have no coordinates in the projection
   until the umbrella's input-member-coordinate work lands, and the dead `InputUnbound` arm stays
   dead until then.
6. **A pilot classifier pair proves the guard discipline.** Extract the condition / lookup-key pair
   as pure classifiers with explicit mutual guards, end to end: claims gathered, violation on
   overlap, claims surviving to the projection. This is the vertical slice that validates the model
   before any arm-by-arm migration.
7. **Enforcement is behavioural plus type-lift, not a census.** The acceptance fixture is
   pipeline-tier: an SDL schema with a conflicting-directive DELETE mutation, asserted through the
   projection to still report the DML kind and the intended table. Fidelity lifts into types where
   the projected arm's components are the claim payload record. No reflection census of carrier
   components against projection components; it cannot observe whether the projection read a
   component, so it degenerates into two hand lists agreeing by convention.

## Relationships

- **Umbrella (`coordinate-lowers-to-datafetcher-queryparts`, R333):** amended by slice 1. The claims
  relation is a base relation the umbrella's current text lacks; the single-classification worldview
  relocates to the planning stage instead of being abolished. The arm-by-arm migration of
  `FieldBuilder` into independent classifiers is follow-up work under the umbrella, not this item.
- **`input-field-resolution-typed-rejections` (R585):** overlaps on one carrier, and its one open
  design fork (fan many input-field failures into one prose rejection, or emit several) is decided
  by this item's doctrine: violations are facts, one per failure. Whichever lands first settles the
  fork once; let that item land first if both reach In Progress together (it is smaller and already
  scoped).
- **`validator-walks-plain-input-unbound-fields` (R221):** subsumed or narrowed by slice 4; see
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
- **Input-side projection**, per scope item 5.

## Retired vocabulary (expected; finalise at the Done gate)

- `FieldBuilder.PairVerdict` / `pairVerdict` / `reduceDirectiveConflict`: the pairwise reduction,
  replaced by derivations over the claiming-directive relation.
- The three-cases-in-one-record reading of `InputField.UnboundField` and its `attemptedColumnName`
  null-as-discriminator semantics (the component itself may survive as an honest fact).
