# An emitter owns its query

Evidence behind the R876 decision "The emitter architecture is examined against the store", taken
2026-10-17 on trunk `2bcb259ff`. The question was whether R549 and R682's plan, command, emit split
(Java planners read facts into command values, an emitter reads only its command) still earns its
place now that the store is the model, or whether an emitter should read the store with jOOQ and emit
directly. Decided with Alf: the emitter owns its query.

## What the split was for

R549 and R682 gave four reasons. Quoted from the deleted item bodies
(`git show 8cd26b7b4^:roadmap/facts-and-commands.md`,
`git show 74f20922c^:roadmap/planners-read-facts-emitters-read-commands.md`).

1. **Testability, by reachability.** Emitters were the worst-covered generator code
   (`JooqRecordInstantiationEmitter` 40.7%, `FetcherEmitter` 50.2%) because reaching a branch meant
   driving the whole pipeline. A command built by hand reaches it directly.
2. **One way to reach a fact** (R682): "joined onto a command row or read off the leaf the emitter
   happens to be holding, and only one is right."
3. **The recompile graph derived once**, which retired `CompileDependencyGraphBuilder`.
4. **A checkable boundary**: "the shell decides nothing", made falsifiable by ratchets.

## What the record shows

**The testability claim was never measured.** R549 promised to re-run the coverage baseline and
expected emitter coverage to climb. No re-run appears in its Done record.

**The command was a Java value, never a store row.** No command, plan or emit relation exists in the
DDL. The docs call them "command relations" with "rows"; the code holds them in `List` and
`CoordinateIndex` records.

**The one store-native planner shows the cost.** `RoutineWriteFacts.read` returns fact rows from the
store; `RoutineWriteCommands.rowOf` copies each into a near-identical `RoutineWriteCommand`, hops one
to one (`tailOf`). Seven of the ten planner entry points read only the walk.

**The boundary leaks both ways.** No emitter queries the store, but `TypeFetcherGenerator` calls
`LauncherCommands.produceWithoutSchema` mid-emit at two sites, and `CommandSeamRatchetTest`'s count of
leaf references inside `plan` has grown from 1 to 140: dispatch moved into planners, it did not go.

**The arc that extended it moved nothing.** R682's conditions arc: 48 relations, 4,031 lines of DDL,
14,846 lines of test source over 106 commits, and in its own words "moved neither of this item's own
dials."

| area | lines |
|---|---|
| `command` + `plan` | about 8,700 |
| emitters (`render` + `rewrite/generators`) | about 35,400 |
| classification core feeding `GraphitronSchema` | about 56,000 |

The split itself is small. The weight is the walk behind the planners, which R876 retires anyway.

## Why behaviour holds what the seam held

1,032 execution tests in `graphitron-sakila-example` run the generated code against PostgreSQL 18,
across every directive the fixture schemas use (`@field` 438 applications, `@nodeId` 225, `@service`
188, `@reference` 178, `@splitQuery` 84, `@condition` 84, `@mutation` 79, tenant routing, federation,
multischema). Nine classes in `graphitron` compile emitted code in process through
`EmittedCodeHarness` and invoke it without a database. A rare state is reached by a fact document,
which replaces a hand-built command as the cheap way in.

What the decision loses: about 91 tests asserting a planner's output as data, and about 56 renderer
tests built on hand-made commands. What it does not lose: the execution and compile tiers.

## The gate is behaviour

Generated output is not held byte-identical across a change, and no test asserts generated Java or
SQL as text. Two findings bear on that.

- About 495 assertion lines in 30 `graphitron` test files read generated method bodies as text,
  against the written ban. They break on any emit change with or without commands in between, so the
  seam never bought text independence.
- Five `*SqlBaselineTest` classes, 66 tests, pin whole rendered SQL statements as exact strings,
  frozen as a pin during the launcher cutover. The written ban in `development-principles.adoc`
  names only "code-string assertions on generated method bodies", so it does not reach them.

Counting round trips is behaviour, not text: it is what catches an emitter querying per field.

## Open

- **The recompile graph.** `PlanCompileGraph` is the only reader of `FetcherEdgeRelation` and is how
  the dev loop knows which file to rebuild. It needs the same answer from the store.
- **Query grain.** An emitter querying per field is the N+1 R682 warned of. The grains are the ones
  "The datafetchers and the rest are generated from the store" catalogues: type, field, keyed
  selection.
