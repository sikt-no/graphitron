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
wrong, not to erase the claims. Two defects follow from the mismatch, and the second is the one that
gives the item its name: failure is implemented as *replacement*, so the facts gathered on the way to
the failure are dropped. The doctrine says the opposite. `development-principles.adoc` under "Classification and validation gather
facts": validation "gathers facts too, **reifying each missing or conflicting fact into a located
violation** the build acts on later". Adding a violation, not deleting a classification.

The umbrella data-model item (`coordinate-lowers-to-datafetcher-queryparts`) already designs the
target: "Diagnostics are data: one located-violation relation, two views. `validate` does not *do*
something, it *produces* a relation." In a relational model this is free. A violation lands in the
violation relation; the coordinate's `source` / `target` / `operation` rows are untouched, because
nothing shares a slot. The leaf model cannot express it: one coordinate holds one classification, so a
failure has nowhere to go except *into* the slot it displaces. The tombstone is forced by the
denormalised shape, which is why this belongs to that programme; it is the executable carve, since the
umbrella ships no code beyond its closure oracle.

## Cardinality: "could not classify" reports two different things

The one-classification slot forces two unrelated situations into the same tombstone, and neither is
what the tombstone says.

**More than one classification is reported as none.** `FieldBuilder.reduceDirectiveConflict` names the
shape in its own javadoc: it "reduces the **classification-claiming** directives present at a position
to **a single verdict**" through a pairwise table. Several directives each claim the classification;
reducing them to one is a policy, and when the policy finds a conflicting pair the coordinate lands as
an `UnclassifiedField`, asserting *zero* classifications where the truth is two or more. What the two
claims *were* is never retained, and in most cases never computed, so the strongest available
description of the defect ("this field claims both a table target and a service backing, pick one") is
unavailable to every view. The participating directive names survive on the rejection; the
classifications they would have produced do not.

**A failed bind is laundered as a successful one.** `InputField.UnboundField` should be a positive
fact: this field binds no SQL column. Instead it doubles as the demotion target for column-miss, and
the demotion is wrapped in `InputFieldResolution.Resolved` at both construction sites in
`BuildContext`, so the classifier reports success while meaning "no column bound, rejected later
somewhere else". Its javadoc lists three cases under one record, two of which it calls a schema-author
bug, and the discriminator between them is component values: whether `condition` is present, whether it
overrides, and whether the nullable `attemptedColumnName` was filled in. That is kind-dependent
nullability standing in for a fact the model should carry outright, the same smell the principles doc
names when it justifies `EdgeKind` as a label enum ("carries no kind-dependent nullability"). The
consumer then re-derives violation-ness from those components rather than reading a violation.

Both dissolve the same way, and it is the umbrella's shape rather than a new invention: let the
coordinate carry its claims (`Coordinate -> Classification*`) and let violations be their own facts.
Then zero claims is genuinely unclassifiable, one is the ordinary case, several is a conflict violation
with all claims retained, and "unbound" goes back to meaning what it says. Every claim is good
information even when the set of them is invalid.

## Three layers, and the leak is worst at the seam

**Model layer: five failure carriers, five different answers.**

| Carrier | Retains | Reaches a view as |
|---|---|---|
| `InputField.UnboundField` | parent, name, location, typeName, nonNull, list, condition, **`attemptedColumnName`** | `FieldClassification.InputUnbound(methodClassName, methodName, override)` |
| `ArgumentRef.UnclassifiedArg` | name, typeName, nonNull, list, rejection | nothing (arguments have no classification projection) |
| `GraphitronField.UnclassifiedField` | parent, name, location, **`definition`**, rejection | `FieldClassification.Unclassified(reason)` |
| `GraphitronType.UnclassifiedType` | name, location, rejection | `TypeClassification.Unclassified(reason)` |
| `InputFieldResolution.Unresolved` | fieldName, lookupColumn, prose | joined into one `Rejection.structural` per input type |

**`UnboundField` is right on one axis and wrong on the other, which is why it is worth reading closely.**
On retention it is the best carrier in the model: eight facts, and its javadoc explains why it keeps
`attemptedColumnName` at all, so the later rejection can render a Levenshtein "did you mean" hint. That
is half this item's thesis, discovered once and never generalised. On identity it is the worst, for the
reasons above: it doubles as a demotion target, so the retention it does well is retention *inside a
mislabelled arm*. And the retention leaks anyway one layer down, where `CatalogBuilder` projects it and
drops the attempted name. Fixing identity without fixing the seam would move the facts into an honest
arm and still lose them at the projection.

**Projection layer: coverage is enforced, fidelity is not.** The principles doc names
`CatalogBuilder.projectFieldClassification` as the exemplar of "One model, many views": an exhaustive
switch, so a new permit fails compilation until the view covers it. That pins *coverage*. Nothing pins
*fidelity*: a view may narrow a permit to a message and no test notices. The two clearest cases sit two
lines apart, where `UnclassifiedField.definition()` (the full authored `GraphQLFieldDefinition`, with
every applied directive on it) is in hand and discarded in favour of `f.reason()`.

**Consumer layer: views re-derive from text what the model had.** Measured on a live session against a
consumer schema mid-migration, an agent asked to repair `@mutation` usage on the delete mutations ran
`grep -rn 'typeName: DELETE'` across a whole monorepo. `schema(type: "Mutation")` answers that for
mutations that *classified*, projecting `dmlKind` and the resolved `tableName`. For the broken ones,
which are exactly the population needing repair, it answers `Unclassified` plus prose. So the tool is
useless precisely where the author needs it, the agent falls back to SDL text, and SDL text is worse
data: it reads intent rather than the verdict and carries no table binding.

## Scope

All of it, deliberately. The projection half alone would leave the doctrine half-applied and the
enforcer unwritten, and the cardinality half is what makes the retained facts truthful rather than a
best-effort salvage.

1. **Classification becomes a relation.** A coordinate carries its claims rather than one verdict, so a
   conflict is "several claims plus a violation" instead of "no classification". `reduceDirectiveConflict`
   stops reducing and starts recording. What a claim consists of at conflict time is the open design
   question for the Spec pass: the cheap floor is the claiming directive plus the classification it would
   have produced, and it is worth checking whether producing all claims costs more than producing the
   first, since the pairwise verdict table runs before the arms do.
2. **"Unbound" stops being a demotion target.** The three cases now sharing `UnboundField` separate: the
   genuine no-column-binding carrier keeps the name and the nullable discriminator goes away; column-miss
   becomes a classification plus a violation; the cascade-deferred case becomes a classification plus a
   violation that consumption resolves. The `Resolved` wrapper stops carrying failures.
3. **Failure carriers retain what was gathered.** Converge the five carriers on one answer. The authored
   definition is already retained on the field carrier, so the floor is free; the ceiling is whatever the
   classifier had established when it stopped.
2. **The projection seam preserves it.** `Unclassified` / `InputUnbound` and their type-level siblings
   grow to carry the retained facts, so a broken DELETE mutation still reads as a DELETE mutation with
   its intended table on the LSP and MCP surfaces.
3. **A fidelity enforcer.** Coverage is the compiler's; fidelity needs a test. Shape: for every failure
   carrier, each retained component is either projected or declared deliberately dropped, in the
   partition mould the module already uses (`EdgeCoverageTest`, and the coverage pin that
   `projectFieldClassification` is the exemplar for). This is what stops the next arm re-inventing a
   tombstone.

## Relationships

- **Umbrella:** `coordinate-lowers-to-datafetcher-queryparts` owns the target model (violation as its own
  relation, facts as independent relations keyed by coordinate). Not a dependency in the scheduling
  sense, since that item is a design umbrella worked through carves like this one rather than directly.
  The normalised end state is where "facts survive a violation" becomes structural rather than
  maintained.
- **`input-field-resolution-typed-rejections`:** overlaps on one carrier. That item gives
  `InputFieldResolution.Unresolved` a typed `Rejection` and settles its fan-in; this item gives the same
  record its facts. Both reshape it, so let that one land first (it is smaller and already scoped) and
  extend the carrier it leaves. Left off `depends-on` because the field-side work, which is the bulk, is
  independent of it; worth hardening if both reach In Progress together.
- **`mcp-aggregated-diagnostics`:** a consumer, and the reason this surfaced. Its pivot dimensions are
  extractors over diagnostic rows, so every fact that survives a rejection becomes a candidate dimension
  and the currently inexpressible "diagnostics on my DELETE mutations" pivot becomes expressible. That
  item should not grow to anticipate this; its dimension set widens on its own when this lands.

## Out of scope

- **Changing what the build accepts or rejects.** A schema that fails today still fails, with the same
  causes at the same locations. This changes what a *failed* coordinate can still tell a reader.
- **New rejection causes**, and any move of the accept / reject line.
- **The emit side.** A tombstoned coordinate generates nothing today and still generates nothing;
  retained facts are for the read-side views, not for partial code generation.
