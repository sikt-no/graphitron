---
id: R288
title: "Inline TableInterfaceField and TableMethodField children (currently N+1)"
status: Backlog
theme: codegen-correctness
bucket: bug
priority: 4
depends-on: []
created: 2026-06-09
last-updated: 2026-07-15
---

# Inline TableInterfaceField and TableMethodField children (currently N+1)

> **Conflicts with R277 (flagged 2026-07-15).** R277 (`tablemethod-under-nested-type`) wires the
> synchronous `buildChildTableMethodFetcher` for the `@tableMethod`-under-`NestingField` case,
> leaving that method unchanged, which is the exact per-parent sync fetcher this item declares an
> N+1 defect and inlines away. Reconcile at Spec: R277's nested-depth support is a candidate to fold
> into this item's inlining rather than build the sync path first. See R277's mirrored note.

A child `@table` field backed by a polymorphic interface target (`ChildField.TableInterfaceField`) or
by a `@tableMethod` (`ChildField.TableMethodField`) is reachable by FK correlation from a query-scope
parent, so it should inline into the parent query as a correlated subquery / `DSL.multiset(...)`,
exactly like the ordinary `ChildField.TableField` (`InlineTableFieldEmitter`). It does not. Both leaves
get a generated *synchronous* fetcher method (`TypeFetcherGenerator.buildTableInterfaceFieldFetcher`,
`buildChildTableMethodFetcher`) that runs its own per-parent
`dsl.select(...).from(...).where(parent-correlation).fetch()` against `env.getSource()`. There is no
`SplitRowsMethodEmitter` and no DataLoader registration for these, so the fetcher fires once per parent
row: an **N+1 query pattern**. N+1 is never correct.

The correct target is to inline these the way `TableField` / `LookupTableField` are inlined (a
correlated multiset folded into the parent SELECT, with the polymorphic discriminator / participant
joins expressed inline for the interface case, and the developer-supplied jOOQ table spliced into the
multiset for the `@tableMethod` case). Inlining is possible for both; support simply has not been built
out (`@tableMethod` in particular has had little work). At minimum, if a separate execution turns out
to be genuinely required for some shape, it must be a **keyed batch** (DataLoader, as `SplitTableField`
/ `RecordTableField` do via `SplitRowsMethodEmitter`), never a per-parent sync query.

Surfaced during R281 dimensional-model design (the `producer x mapping` cut). It is the textbook
*current implementation != what's correct* divergence the R281 corpus exists to enforce: these leaves
are *classified* `producer = ∅` (inline), and this defect is the gap between that correct verdict and
the current generator. R281 asserts the correct classification; this item fixes the generator to match.
See `roadmap/audits/classification-test-dsl-inventory.md` (the historical R281 corpus inventory,
now marked superseded), the current-vs-correct note under `producer`.
