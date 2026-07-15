---
id: R288
title: "Inline TableInterfaceField children (currently N+1)"
status: Backlog
theme: codegen-correctness
bucket: bug
priority: 4
depends-on: []
created: 2026-06-09
last-updated: 2026-07-15
---

# Inline TableInterfaceField children (currently N+1)

> **Scoped to the interface case 2026-07-15.** This item originally also covered
> `ChildField.TableMethodField` (the `@tableMethod` N+1). `@tableMethod` is withheld from the v1
> surface (R400) and its support work was set aside (R277 discarded 2026-07-15; `@routine` covers
> the pressing need, and `@tableMethod` will be redesigned on a real feature request), so the
> `@tableMethod` half is dropped from here. `buildChildTableMethodFetcher` has the same per-parent
> sync N+1 shape and should be inlined as part of that future `@tableMethod` redesign, not here.
> This item is now the interface case only.

A child `@table` field backed by a polymorphic interface target (`ChildField.TableInterfaceField`) is
reachable by FK correlation from a query-scope parent, so it should inline into the parent query as a
correlated subquery / `DSL.multiset(...)`, exactly like the ordinary `ChildField.TableField`
(`InlineTableFieldEmitter`). It does not. It gets a generated *synchronous* fetcher method
(`TypeFetcherGenerator.buildTableInterfaceFieldFetcher`) that runs its own per-parent
`dsl.select(...).from(...).where(parent-correlation).fetch()` against `env.getSource()`. There is no
`SplitRowsMethodEmitter` and no DataLoader registration, so the fetcher fires once per parent
row: an **N+1 query pattern**. N+1 is never correct.

The correct target is to inline it the way `TableField` / `LookupTableField` are inlined (a
correlated multiset folded into the parent SELECT, with the polymorphic discriminator / participant
joins expressed inline). At minimum, if a separate execution turns out to be genuinely required for
some shape, it must be a **keyed batch** (DataLoader, as `SplitTableField` / `RecordTableField` do via
`SplitRowsMethodEmitter`), never a per-parent sync query.

Surfaced during R281 dimensional-model design (the `producer x mapping` cut). It is the textbook
*current implementation != what's correct* divergence the R281 corpus exists to enforce: these leaves
are *classified* `producer = ∅` (inline), and this defect is the gap between that correct verdict and
the current generator. R281 asserts the correct classification; this item fixes the generator to match.
See `roadmap/audits/classification-test-dsl-inventory.md` (the historical R281 corpus inventory,
now marked superseded), the current-vs-correct note under `producer`.
