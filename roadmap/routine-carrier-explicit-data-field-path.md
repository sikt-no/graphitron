---
id: R622
title: "Routine carrier: admit the explicit data-field reference path, single- and multi-hop"
status: Backlog
bucket: feature
priority: 10
theme: routine
depends-on: [validation-adds-facts]
created: 2026-08-10
last-updated: 2026-08-10
---

# Routine carrier: admit the explicit data-field reference path, single- and multi-hop

`roadmap/routine-mutation-payload-carrier-return.md` ships the routine payload carrier with the
implicit data-field path only: the single name-matched hop from the captured routine result to
the data field's own element table. A data field carrying `@reference` lands a typed `Deferred`
pointing here, so an author cannot yet declare an explicit path, and in particular cannot reach
the target through multi-hop joins off the routine result. That is a real authoring gap: the
implicit hop covers the motivating consumer schema, but any payload whose target is not directly
name-matchable from the routine's result columns has no spelling at all.

The carrier item deferred this for a machinery reason, not a semantic one: its captured pairs
derive at grounding, which runs before field classification, so an explicit path would need
either `@reference` parsing inside the grounding fold (a parse seat with no rejection
coordinate) or independent re-derivation at two classify seats (the two-derivations defect that
item exists to remove). This item is framed fact-base-first because the adopted architecture
dissolves exactly that reason: the path already exists as `graphitron_field_reference_step`
rows from capture (one row per element, phase-independent), so the hop-0 pairs become a
derivation view joining step position 0 (or the defaulted element table, which is the implicit
form as the same view) against the catalog's PK columns, its failure modes become detection
queries minting located violation facts, and "where does the path parse" stops being a design
question. Per the strangler frame in `roadmap/validation-adds-facts.md` (migration keyed by
consumer, derivations built when the first consumer needing them migrates), this item is a
candidate first consumer that pulls the carrier-classification neighbourhood onto the store,
and it should be specced as views over the reference-step relation, not as a new parse seat on
the legacy pipeline.

What remains genuinely open, and is this item's real design work: the read-side shape of a
residual path. `ParentCorrelation.checkCarrierInvariant` pairs a non-empty `joinPath` only with
a hop-anchored correlation, while the carrier data field's correlation is the hop-less
`OnLiftedSlots` over the captured slots, so a residual path needs a correlation arm that anchors
on the captured record and walks onward from it, plus the post-commit query emit that rides it.

Inherited as decided from the carrier item, not reopenable here without arguing down its
two-statements rule: the write transaction contains the routine call and a projection of its own
result, nothing else, at every hop count; residual hops run post-commit under the caller's
identity, so read policies apply to them, and a multi-hop data field legitimately resolving null
with empty errors is the carrier's outcome (b), not a defect. The carrier item's scope boundary
records the honest reasoning (in-transaction capture would not escape RLS either; it buys only
insulation from visibility that changes at commit).
