---
id: R342
title: "Structural dedup for bulk UPDATE SET columns written by overlapping carriers"
status: Backlog
bucket: feature
priority: 5
theme: nodeid
depends-on: []
created: 2026-06-19
last-updated: 2026-06-19
---

# Structural dedup for bulk UPDATE SET columns written by overlapping carriers

R322 closed the value-agreement gap on three of the four mutation write surfaces: the `@service`
jOOQ-record path, the `@mutation` INSERT path (single-row and bulk: structural dedup + agreement),
and the single-row `@mutation` UPDATE SET path (agreement preamble + the plain-field-vs-plain-field
reject). It deliberately left the **bulk** UPDATE SET path's decode-involving overlap to this
follow-up, on the grounds that — unlike the single-row SET `Map.put` silent last-write-wins R322
fixed — the bulk path fails *loud*, so it is self-announcing rather than a silent drop.

## Current behavior (as found, post-R322)

The bulk UPDATE renders `UPDATE t SET c = v.c FROM (VALUES …) AS v(col1, col2, …) WHERE …`. The
column-name list `v(…)` is built by `TypeFetcherGenerator.emitSetVColNameAdds`, the per-row cells by
`emitSetBulkCellAdds`, and the SET assignment by `emitSetVFieldPuts` — all walking `List<SetGroup>`
per-group with no cross-group column dedup (the same shape the INSERT path had before R322's
`insertColumnPlan`). When two SET carriers write one column (e.g. a plain `@field` plus a
`@nodeId` FK reference whose lifted child column is the same, on a `multiRow: true` list input), the
column name is added to `v(…)` twice, producing a duplicate column in the derived table — a loud
Postgres / jOOQ error, not a silent drop.

R322's all-plain reject (`UpdateRowsError.PlainColumnCollision` in `UpdateRowsWalker`) already covers
the bulk path's plain-field-vs-plain-field case at validate time (the walker feeds both single-row and
bulk). So what remains is only the **decode-involving** bulk SET overlap: today a loud crash, where it
should dedup to one `v(…)` column + one coalesced cell with the value-agreement check, the bulk-SET
analogue of R322's D5 INSERT dedup.

## Design sketch

Drive `emitSetVColNameAdds` / `emitSetBulkCellAdds` / `emitSetVFieldPuts` off a per-column SET plan so
a shared column contributes exactly one `v(…)` entry, one per-row cell (coalescing over the present
writers), and one `sets.put`. The agreement check fires per row (inside the row lambda), mirroring
R322's `emitInsertAgreementPrep` but on the bulk-SET cell shape rather than the INSERT VALUES cell.

When picking this up, weigh the `principles-architect` note recorded against R322: the overlap analysis
now exists in four per-path instantiations (`JooqRecordInstantiationEmitter.analyzeOverlap`,
`TypeFetcherGenerator.insertColumnPlan`, `MutationInputResolver.collectSetColumns`, and R322's
single-row `emitSetAgreementPreamble`). Adding a fifth bulk-SET walk is the "same predicate, multiple
consumers, drift risk" smell the Generation-thinking principle and R322's D1 both warn against; this
item is the natural place to consider lifting the column→ordered-writers analysis into one shared
abstraction over a carrier-agnostic writer (slot + decode-flag + access-path + value-expr) that all of
detect / dedup / agree / reject read off, rather than instantiating a sixth.

## Coverage

Execution-tier: a bulk (`multiRow: true`, list input) UPDATE whose SET column is written by both a
plain `@field` and a `@nodeId` FK reference — agreeing rows update the agreed value (proving the dedup:
no duplicate-`v`-column crash), a disagreeing row throws. Reuse the R322 `film_endorsement`
`endorsed_film` overlap shape, listed.

## Relationship to other items

- **R322** (Done/In Review): shipped `@service` + INSERT + single-row UPDATE SET agreement and the
  cross-path plain-field reject; left this bulk-SET dedup as the one remaining loud-failure surface.
