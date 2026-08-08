---
id: R603
title: "A pipeline-output facts family in the model store"
status: Backlog
bucket: architecture
priority: 5
theme: classification-model
depends-on: []
created: 2026-08-06
last-updated: 2026-08-08
---

# A pipeline-output facts family in the model store

`graphitron-model-captures-facts` (R595, shipped; see `roadmap/changelog.md`) landed the
store scoped to what a run reads: five families, each named for whose vocabulary a row is
written in, never for its reader or its role. What the SDL declares (`graphql_`), what
graphitron makes of that document (`graphitron_`: decoded directives and macro provenance),
what the consumer's database declares (`sql_`), what the compile classpath declares (`jvm_`),
and the store's own record of what it read and was built from (`store_`).
`validation-adds-facts` (R589) adds the derived stratum: what the generator concluded, as
detections over those inputs; the landed DDL header holds the `intent_` prefix in reserve for
exactly that layer. Neither stratum can hold the third kind of fact the pipeline observes:
what the run *produced*, reported by
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
R595's capture loads run at startup, and the store now persists between runs on capture's
terms: an H2 file under `<build>/graphitron-model`, a `store_stamp` (DDL hash plus generator
version) that discards and rebuilds on any mismatch so no migration ever exists,
`StoreRefresh` retaining partitions whose source still hashes to what `store_source`
recorded, stamps written only after the capture flush so a killed run leaves nothing claiming
completeness, and readers getting a copy-on-open snapshot. Compile results do not exist at
capture time (in the batch pipeline javac runs in the consumer's build, after the generator
exits; only the dev-loop cadence sees them at all, and only after generation). So the family
needs a third writer with its own lifecycle, and each piece of the landed machinery asks its
own question of that writer: what a compile round's `store_source` entry hashes when the
source is the emitted file set, what vouches for a round's completeness when the stamp that
vouches for capture is already written, and how rows written after open reach readers that
snapshot on open. That is a design of its own, and the relation would sit empty in exactly
the batch runs R595's agreement tests exercise. Per the architecture's own rule that a
relation's DDL lands with its first consumer (cheap by construction now: new DDL is a stamp
mismatch and a rebuild, never a migration), this family lands as its own item. R569's bridge covers the diagnostics
read surface in the meantime, so the consumer that fixes this family's shape is whichever
first needs output facts as *facts* rather than as display rows: compile results joined to the
emitted files that produced them, or the inventory itself.

Spec-time questions: the family prefix, now bounded by the landed naming rule (whose
vocabulary is the row written in; a reader's name or a role like `output_` is exactly what
the rule exists to reject) and its boundary against the reserved `intent_` stratum (output
facts an oracle reports versus derived facts a detection computes; the two must not blur, or
the detection doctrine leaks); the compile relation's natural key (file, position, and an
ordinal for repeated identical messages); the per-round writer's contract with the landed
lifecycle (`store_stamp`, `store_source`, `StoreRefresh` retention, copy-on-open reads)
without turning the store into shared mutable state; and whether the emitted-file inventory
belongs here or stays with the umbrella's emit-side command records.
