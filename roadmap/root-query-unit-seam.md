---
id: R541
title: "Root Query unit: one query unit shared by root and child fetchers"
status: Backlog
bucket: architecture
priority: 4
theme: classification-model
depends-on: []
created: 2026-07-26
last-updated: 2026-07-26
---

# Root Query unit: one query unit shared by root and child fetchers

Owns seam-worklist row 10 of R333's decided target topology: the **Root Query unit**, the root
`rows<X>`-equivalent. The resolve side is asymmetric today: the child path factors its query into a
named unit (child fetcher → DataLoader → `rows<X>`, the `select` / `from` / `where` / `orderBy` /
`$fields` assembly as a named method with a model-carried name), while the root path inlines that
same assembly into the fetcher body with no named unit to call. Root and child build the same query
two ways; only child names it. The decided target (2026-06-19, reaffirmed against the
generate-inline alternative 2026-07-26): both fetcher kinds become thin entry points delegating to
one shared query unit, differing only in invocation strategy (root calls it directly; child calls it
batched through a loader plus scatter). The root path gains a level of indirection not required by
runtime (a static, monomorphic, JIT-inlined call; no batching justifies it); paying it buys
uniformity, independent assertability of the query unit, and reuse where one type is reachable both
at root and as child. This is the deliberate, accepted trade recorded in R333.
