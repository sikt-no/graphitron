---
id: R603
title: "A pipeline-output facts family in the model store"
status: Backlog
bucket: architecture
priority: 5
theme: classification-model
depends-on: [graphitron-model-captures-facts]
created: 2026-08-06
last-updated: 2026-08-07
---

# A pipeline-output facts family in the model store

`graphitron-model-captures-facts` (R595) scopes the store to input facts: what the author
wrote (`graphql_`, `applied_`, `intent_`), what the catalog contains (`catalog_`), what the
extension classpath offers (`extension_`). `validation-adds-facts` (R589) adds the derived
stratum: what the generator concluded, as detections over those inputs. Neither family can
hold the third kind of fact the pipeline observes: what the run *produced*, reported by
oracles that run after capture. The first concrete case is generated-code compile
diagnostics: `Workspace.compileDiagnostics()` carries javac output over emitted sources,
written per dev-loop compile round, and today it lives only in memory beside the store
instead of in it. Candidate siblings in the same family: the emitted-file inventory (which
files a run wrote, from which coordinates), which today exists only as filesystem state.

Why it matters, and the boundary with R569: `mcp-aggregated-diagnostics` (R569) is now the
store's first reader and lands an interim compile *bridge* relation (javac output loaded per
compile round, registered under the agreement driver's bridged arm) as one arm of its
`diagnostic` union view. That bridge is a copy of legacy output with a read-side key; this
item owns the real thing: the output family's prefix, its writer lifecycle, and the promotion
of run-output facts to first-class citizens (compile results keyed to the emitted files that
produced them, the emitted-file inventory itself). When this family lands it replaces the
compile arm of R569's view (a dropped table and a one-line view edit, by that item's design),
and it may adopt or supersede the bridge table's key; the bridge's shape is deliberately not
binding here.

Why this is not an R595 amendment: the write cadence is different in kind, not in detail.
R595's two capture loads run at startup, and the store is "populated by capture"; compile
results do not exist at capture time (in the batch pipeline javac runs in the consumer's
build, after the generator exits; only the dev-loop cadence sees them at all, and only after
generation). So the family needs a third writer with its own lifecycle (per-round upsert or
delete-and-reload of the partition, interaction with R595's warm-start stamp), which is a
design of its own, and the relation would sit empty in exactly the batch runs R595's
agreement tests exercise. Per the architecture's own rule that a relation's DDL lands with
its first consumer, this family lands as its own item. R569's bridge covers the diagnostics
read surface in the meantime, so the consumer that fixes this family's shape is whichever
first needs output facts as *facts* rather than as display rows: compile results joined to the
emitted files that produced them, or the inventory itself.

Spec-time questions: the family prefix and its boundary (output facts an oracle reports
versus derived facts a detection computes; the two must not blur, or the detection doctrine
leaks); the compile relation's natural key (file, position, and an ordinal for repeated
identical messages); how a per-round writer coexists with "one database per generator run,
created at startup, populated by capture" without turning the store into shared mutable
state; and whether the emitted-file inventory belongs here or stays with the umbrella's
emit-side command records.
