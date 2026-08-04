---
id: R589
title: "Validation adds facts; classification failure stops deleting them"
status: Backlog
bucket: architecture
priority: 4
theme: classification-model
depends-on: []
created: 2026-08-04
last-updated: 2026-08-04
---

# Validation adds facts; classification failure stops deleting them

Classification failure is implemented as *replacement*: the coordinate's classification becomes a
tombstone carrying a message, and the facts gathered on the way to the failure are dropped. The
doctrine says the opposite. `development-principles.adoc` under "Classification and validation gather
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

## Three layers, and the leak is worst at the seam

**Model layer: five failure carriers, five different answers.**

| Carrier | Retains | Reaches a view as |
|---|---|---|
| `InputField.UnboundField` | parent, name, location, typeName, nonNull, list, condition, **`attemptedColumnName`** | `FieldClassification.InputUnbound(methodClassName, methodName, override)` |
| `ArgumentRef.UnclassifiedArg` | name, typeName, nonNull, list, rejection | nothing (arguments have no classification projection) |
| `GraphitronField.UnclassifiedField` | parent, name, location, **`definition`**, rejection | `FieldClassification.Unclassified(reason)` |
| `GraphitronType.UnclassifiedType` | name, location, rejection | `TypeClassification.Unclassified(reason)` |
| `InputFieldResolution.Unresolved` | fieldName, lookupColumn, prose | joined into one `Rejection.structural` per input type |

**The exemplar already exists, and it already leaks.** `UnboundField` is the arm that got this right: it
retains eight facts, and its javadoc explains why it keeps `attemptedColumnName` at all, so the later
rejection can render a Levenshtein "did you mean" hint. That is this item's whole thesis, discovered
once and not generalised. Then `CatalogBuilder` projects it and drops the attempted name. So even the
best carrier loses its most diagnostically valuable fact one layer down.

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

Both halves, deliberately. The cheap half alone would leave the doctrine half-applied and the enforcer
unwritten.

1. **Failure carriers retain what was gathered.** Converge the five carriers on one answer. The authored
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
