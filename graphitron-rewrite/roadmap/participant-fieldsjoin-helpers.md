---
id: R76
title: "Emit per-participant fieldsJoin and orderBy; replace SelectJoinStep mutation in interface fetchers"
status: Backlog
bucket: cleanup
priority: 7
theme: structural-refactor
depends-on: []
---

# Emit per-participant fieldsJoin and orderBy; replace SelectJoinStep mutation in interface fetchers

`TypeFetcherGenerator.buildQueryTableInterfaceFieldFetcher` and `buildTableInterfaceFieldFetcher` emit dynamic jOOQ queries by declaring a `SelectJoinStep<Record> step` local and reassigning it inside `if (alias != null)` blocks (`buildCrossTableJoinChain`, see [`TypeFetcherGenerator.java:686-700`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java) and [`TypeFetcherGenerator.java:759-770`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java)). That is not the idiomatic jOOQ pattern for dynamic joins; jOOQ's documented form folds the conditional join into a single fluent expression via `DSL.noTable()` / `DSL.noCondition()`, which jOOQ erases at render time. The step-mutation form also centralises join construction in `QueryFetchers`, breaking symmetry with the existing per-type-class `$fields(...)` helper (which already gates SELECT entries by selection set on the participant type).

Refactor the emitter so each participant type class (e.g. `FilmContent`, `ShortContent`) hosts a sibling helper alongside `$fields` (working name `$fieldsJoin(selectionSet, sourceTable, env)`) that takes the current from-clause, applies its own conditional cross-table joins, and returns the joined `Table<?>`. The interface fetcher then composes participants by nesting the calls and degenerates to a single fluent expression: `dsl.select(new ArrayList<>(fields)).from(ShortContent.$fieldsJoin(FilmContent.$fieldsJoin(content, env), env)).where(condition).orderBy(...).fetch()`. Order-by gets the same treatment via a static `$orderBy(table)` (likely on the interface type or interface-fetcher's own type class, depending on where order-by inputs naturally live).

Plan body pending. Open questions for Spec: (1) where exactly does `$orderBy` live — on the interface type, on the parent type, or as a `QueryFetchers`-local static — given current order-by inputs come from `qtif.orderBy()` and aren't participant-scoped; (2) does the field set stay produced by `$fields` per participant (current shape) or get folded into `$fieldsJoin` so each participant declares its alias and its SELECT contribution in one place; (3) what does `$fieldsJoin` return for a participant with zero cross-table fields — pass-through `sourceTable`, or skip the call entirely at emit time; (4) do we want this same shape for non-interface table-field fetchers (R7's decomposition territory) or scope strictly to the interface-fetcher pair to keep the refactor bounded.
