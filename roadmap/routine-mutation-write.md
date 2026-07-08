---
id: R451
title: "Routine writes: @routine on Mutation commits before the follow-up query"
status: Backlog
bucket: feature
priority: 2
theme: service
depends-on: []
created: 2026-07-08
last-updated: 2026-07-08
---

# Routine writes: @routine on Mutation commits before the follow-up query

`@routine` is read-only today: R300 shipped the day-one table-valued read slice on Query and
R435 extended it to order-significant chains, but the procedure-write fork was deferred at
R300's retirement and survives only as prose in its `changelog.md` entry — no live item
carries it. An SDL author cannot back a Mutation field with a database routine, which is a
core capability: the legacy generator supported the shape via `@experimental_procedureCall`
(whose 26 `procedureCall*` rejection fixtures R300 also left untranslated).

The contract this item exists to pin: **the routine call is the write, and it commits before
any follow-up query runs.** `type Mutation { x: [Film!] @routine(...) @reference(...) }`
means: execute the routine, commit its transaction, then run the chain's follow-up query so
the re-read observes committed state. That is a structurally different emission from R435's
read chain, which renders one SQL statement (the routine as the `FROM` source, hops joined
laterally); write-then-requery is two statements with a transaction boundary between them,
so the emitter shape is new and the commit placement interacts with the connection /
transaction lifecycle hooks (R429).

Classification state at filing: a single-node `@routine` on Mutation lands
`classifyMutationField`'s generic "needs `@service` / `@mutation`" fallback, and a multi-node
chain misclassifies as a Query read until R449 lands. R449's D1 mints the typed `Deferred`
with this item's planSlug (`routine-mutation-write`) for both shapes, so the SDL author is
pointed here rather than at a dead end.

Questions the Spec pass must answer (not design decisions yet): what the field's return
shape binds to (the routine's own result, OUT parameters, or only the follow-up re-query);
whether void/scalar procedures are in the first slice or only table-returning ones; the jOOQ
call surface for procedures vs the `Routines` table-valued convenience methods; how routine
errors map to the mutation error channel; and whether the commit is graphitron-managed or
delegated to the R429 lifecycle contract.
