---
id: R531
title: "Meta-test: no registry reads in the classify-time set"
status: Backlog
bucket: architecture
priority: 5
theme: classification-model
depends-on: []
created: 2026-07-24
last-updated: 2026-08-06
---

# Meta-test: no registry reads in the classify-time set

The read-free classification invariant (no read of the type registry under construction during
the single classify-and-emit walk) now spans several classes: `FieldBuilder`, `ServiceCatalog`,
`InputBeanResolver`, `EnumMappingResolver`, and `TypeBuilder`'s classify path all resolve
referenced-type verdicts through `TypeBuilder.lookAheadVerdict` / `BuildContext.lookAheadVerdict`
or the fixed-point indices (`BuildContext.scalarVerdicts` and siblings), never `ctx.types`. The
invariant's history is the argument for pinning it: it was believed to hold after R317/R325, held
lexically for `FieldBuilder` only, and was falsified by transitive helper reads while implementing
the walk's input-surface extension. Today it is enforced by one marker comment in `FieldBuilder`
and review; the next helper that reaches for `ctx.types.get(...)` mid-walk reopens the hole with
an order-dependent misclassification no existing test is guaranteed to notice (the failure mode is
often permissive: a lost rejection, not a crash). File shape: a meta-test in the same lexical-scan
style as `RoadmapReferenceGuardTest`, scanning the classify-time set for `ctx.types` /
`typeRegistry.entries()` / `typeRegistry.get` reads, with an explicit allow-list for the
deliberate post-walk readers (validator, index folds, `GraphitronSchemaBuilder`'s post-walk
reductions and the visitor's own sibling-independent `parentType` read).

## Fact-base note (2026-08-06)

The read-free invariant is structural for consumers migrated onto the store: capture-then-derive has no classify-time registry to read. Scope the meta-test to the shrinking un-migrated set with an explicit retirement gate, or fold it into the shadow window's agreement tests.
Context and the whole-board picture: `roadmap/audits/2026-08-06-fact-base-impact-sweep.md`.
