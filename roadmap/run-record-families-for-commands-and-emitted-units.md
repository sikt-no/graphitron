---
id: R727
title: "Run-record families: committed command rows and the emitted-unit census land in the store"
status: Backlog
bucket: architecture
priority: 4
theme: classification-model
depends-on: []
created: 2026-08-19
last-updated: 2026-08-19
---

# Run-record families: committed command rows and the emitted-unit census land in the store

The store answers what the schema means, and after
`roadmap/planners-read-facts-emitters-read-commands.md` it will be what the plan derives commands
from, but nothing records what a run *concluded*: which command rows the plan committed, and which
Java units the render fold emitted for them. Both conclusions exist only in memory during the run,
so every consumer that wants them has to reproduce the tier that produced them. The language server
and the MCP cannot answer "what code did this coordinate produce" without re-deriving planner
logic, and a cross-tier invariant cannot be asked at all: the enforcement gap in
`roadmap/list-ordering-invariant-enforcement.md` is precisely a question of the form "every
coordinate the facts classify as list-shaped has a launcher row carrying an ordering", and its
hardest case is a site that takes *no* launcher row, an absence no in-memory walk over existing
rows can see but a relational anti-join between the fact stratum and a command record answers
directly.

## Shape

A run-record stratum, written *downward* by the tier that owns the rows, at the tier's own cadence:

* **The plan's committed command rows**, written after `EmitPlan.produce` commits them. Keyed by
  coordinate plus the glue keys the relations already declare; javapoet types stay out, exactly as
  the plan itself refuses them.
* **The render's emitted-unit census**, written by the render fold: coordinate to emitted class,
  method, file. The fold's closure invariant already holds this mapping in hand (every emitted
  method is the render output of exactly one committed command), so the writer is a fold over data
  the shell has, not a parse of the generated tree.

The cadence precedent is `javac_`: a post-capture family with its own writer, whose graph partition
capture clears "because its rows describe an emitted tree the run is about to replace"
(`graphitron-model/src/main/resources/no/sikt/graphitron/model/graphitron-model.sql`, header). The
emitted-unit census is the second family whose corpus the run itself produced; the fact model
(`docs/architecture/explanation/fact-model.adoc`) names `javac_` as the only one today.

## The rule that keeps this compatible with the tier doctrine

The seam item bans the store serving plan-shaped views as planner inputs, and that ban is about
*read direction*, not about a record. The charter rule for these families: **no tier reads a
run-record family upward.** Planners read facts, emitters read the in-memory command rows the fold
hands them, and neither ever reads the record back; the record exists for gates, the MCP, the
language server, and cross-tier joins. A planner or emitter importing the record's generated tables
is the violation the family comment states and a boundary test forbids, on the
`StoreClientBoundaryTest` model.

Freshness is inherent and honest: rows describe the last run, so an editor workspace that never ran
the plan has none, the same as `javac_` today. Author-facing rules that must be fresh at edit
cadence (the declared-but-unlowerable rejection in the ordering item) stay fact-derived and do not
move here.

## Sequencing

After or alongside the seam item's per-family conversions, which are what make this cheap: once a
producer derives its command rows from the store by SQL, writing them back beside the facts is one
more statement at a grain the producer already owns. The ordering-invariant item does not block on
this (its honesty half is fact-derived, its enforcement half can start as an in-memory fold gate),
but its enforcement becomes non-regressable and its absent-row blind spot becomes queryable only
once the command record exists.

## Provenance

The emitted-code half was first listed as the "generated code catalog" dimension in
`roadmap/knowledge-base-programme.md` (R117), whose own fact-base note reconciles that the model
store, not the DuckDB projection, is where such a dimension lands. The command half was surfaced by
the ordering-invariant enforcement question (R677) during the spec discussion of the seam item
(R682, `roadmap/planners-read-facts-emitters-read-commands.md`), which deliberately keeps both
records out of its own scope.
