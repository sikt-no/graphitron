---
id: R52
title: "Lift lookup-vs-query operation taxonomy into the model"
status: Backlog
bucket: architecture
priority: 5
depends-on: [lift-nodeid-out-of-model]
---

# Lift lookup-vs-query operation taxonomy into the model

R50 names the lookup-vs-query split as documentation only (see *Operation taxonomy: lookup vs query* in [`lift-nodeid-out-of-model.md`](lift-nodeid-out-of-model.md)). The distinction is real and structurally consequential: lookups carry a derived VALUES table with an `idx` column to preserve per-input-row identity, queries fold predicates into a WHERE with no input-row identity to track. Today the split is encoded only by variant identity (`LookupMapping` vs everything else) and routing decisions taken in individual generators.

Lift the axis to a first-class sealed `Operation` carrier (or a comparable model-level handle) once a cross-cutting consumer needs to ask "is this a lookup?" without dispatching through variant-shape inspection. Likely forcing functions:

- A validator rule that wants to enforce "every lookup-shaped field has an idx-preserving emission path" or "every query-shaped field has a non-VALUES SQL signature."
- A dispatcher that picks between `LookupValuesJoinEmitter` and the WHERE-builder paths uniformly across variant kinds.
- A fixture-side predicate (or test harness) that wants to assert on the operation kind directly.

Until one of these lands, the distinction is documented but not modeled — *Sub-taxonomies for resolution outcomes* says each new sub-taxonomy proposal comes with a one-line note on what distinct information it carries that a sibling cannot, and today the answer is "nothing a switch on variant identity doesn't already produce." Re-spec when that changes.
