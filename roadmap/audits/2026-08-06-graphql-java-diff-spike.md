# graphql-java schema diffing: no budget for the fact base to save

A working document, not a roadmap item; it lives in `audits/` so the roadmap-tool ignores it.
It records a spike commissioned during R589/R595 design dialogue, answering whether
graphql-java's built-in diffing tools could speed up incremental work (the on-save loop
updating the fact store from a schema delta instead of recapturing). The spike code is
disposable and is not committed anywhere. Sibling records:
`2026-08-05-fact-base-h2-spike.md` (the store's own latency numbers this one compares
against) and `2026-08-05-h2-functions-jooq-spike.md`.

## The question

If an editor session knows what changed between saves, could capture apply a delta instead of
rebuilding? graphql-java ships two diff tools: `graphql.schema.diffing.SchemaDiffing`, a
graph-edit-distance diff producing minimal edit operations (plus `diffAndAnalyze` for
structured differences), and the older `graphql.schema.diff.SchemaDiff`, a category reporter
(breaking / dangerous) for API evolution. Both take **assembled `GraphQLSchema` pairs**, which
is the first constraint: no diff happens before the new schema is parsed and assembled.

## Setup

graphql-java 25.0 (the pinned version), JDK 25, synthetic schemas at the fact-base spike's two
scales (100 types x 13 fields "sakila-sized", 1,000 types x 13 fields stress), warm-JVM
medians. Deltas exercised: identical pair, one field added, one type added.

## Numbers

| operation | 100 types | 1,000 types |
|---|---|---|
| parse + assemble (the diff's admission price) | 31.7 ms | 2,734.8 ms |
| GED diff, identical schemas | 51.7 ms | 5,201.0 ms |
| GED diff, one field added | 22.4 ms | 2,164.5 ms |
| GED diff, one type added | 22.3 ms | 1,973.5 ms |
| GED `diffAndAnalyze`, one field | 24.3 ms | 2,119.8 ms |
| old `SchemaDiff`, one field | 30.9 ms | `StackOverflowError` |

Fact-base spike reference: full capture through planning is ~60 ms at scale 1 and ~578 ms at
scale 2; SDL capture alone is 8.9 ms at scale 1.

## Verdict

- **No speed boost exists to collect.** The delta path must assemble the new schema first
  (~32 ms at scale 1), then diff (~22 ms), and has then spent more than the full rebuild it
  was trying to avoid; the diff alone costs more than the entire SDL capture it would
  replace. At stress scale the diff costs 3.5x the whole capture-through-planning rebuild.
  And the saving ceiling was always the capture slice only: H2 has no incremental view
  maintenance, so the derivation strata re-run either way.
- **The real incremental bottleneck is upstream of the store.** At stress scale,
  parse+assembly (2.7 s) dwarfs the store rebuild (578 ms) and is superlinear (~86x cost for
  10x size); any future incremental investment belongs at the graphql-java boundary, and no
  diff tool avoids that cost, because diffing consumes assembled schemas.
- **Where the tools do fit is diagnostics, not speed.** `diffAndAnalyze` produces structured
  differences at negligible cost at real scale (~25 ms), which suits the round-trip gate
  (when store-reconstructed SDL diverges from the emitted schema, report *what* differs
  instead of a byte diff) and consumer-facing breaking-change reporting. The older
  `SchemaDiff` recursion overflows on large schemas; prefer `SchemaDiffing` if either role
  is picked up.
