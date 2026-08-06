---
id: R603
title: "A pipeline-output facts family in the model store"
status: Backlog
bucket: architecture
priority: 5
theme: classification-model
depends-on: [graphitron-model-captures-facts]
created: 2026-08-06
last-updated: 2026-08-06
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

Why it matters: as long as run-output facts live outside the store, every consumer that spans
input and output channels has to build a union seam in Java. The concrete cost is recorded in
`mcp-aggregated-diagnostics` (R569): its `DiagnosticRow` union over three channels stays
permanent precisely because the compile channel has no store home, and its aggregate cannot
become a single relational read even after R589's violation relation lands. With an output
family, the cross-channel union becomes a store view and the seam dissolves.

Why this is not an R595 amendment: the write cadence is different in kind, not in detail.
R595's two capture loads run at startup, and the store is "populated by capture"; compile
results do not exist at capture time (in the batch pipeline javac runs in the consumer's
build, after the generator exits; only the dev-loop cadence sees them at all, and only after
generation). So the family needs a third writer with its own lifecycle (per-round upsert or
delete-and-reload of the partition, interaction with the R597 warm-start stamp), which is a
design of its own, and the relation would sit empty in exactly the batch runs R595's
agreement tests exercise. Per the architecture's own rule that a relation's DDL lands with
its first consumer, this family lands as its own item, with R569's aggregate (or the LSP
diagnostics publisher) as the consumer that fixes its shape.

Spec-time questions: the family prefix and its boundary (output facts an oracle reports
versus derived facts a detection computes; the two must not blur, or the detection doctrine
leaks); the compile relation's natural key (file, position, and an ordinal for repeated
identical messages); how a per-round writer coexists with "one database per generator run,
created at startup, populated by capture" without turning the store into shared mutable
state; and whether the emitted-file inventory belongs here or stays with the umbrella's
emit-side command records.
