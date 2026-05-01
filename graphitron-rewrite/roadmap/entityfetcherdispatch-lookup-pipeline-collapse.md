---
id: R55
title: "Collapse EntityFetcherDispatch per-typeId VALUES emission onto the shared row-builder"
status: In Review
bucket: architecture
priority: 5
theme: nodeid
depends-on: []
---

# Collapse EntityFetcherDispatch per-typeId VALUES emission onto the shared row-builder

## Shipped (pending review)

Implementation landed 2026-05-01. Sub-sections below preserved as the design record; the shipped diff matches the design with one minor adjustment noted in *Implementation deviations*.

- New `ValuesJoinRowBuilder` helper at `graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/util/ValuesJoinRowBuilder.java` carries the typed `Row<N+1>` array declaration, per-cell typed value construction, alias-args list, and USING-args list. 14 unit tests at `graphitron/src/test/java/no/sikt/graphitron/rewrite/generators/util/ValuesJoinRowBuilderTest.java` pin arity behaviour, the arity-22 cap, alias args, USING args, and accept both lookup-shape (`DSL.inline(i)`) and dispatcher-shape (`DSL.val(idx, Integer.class)`) idx cells.
- `LookupValuesJoinEmitter` retains its lookup-specific `Slot` / `RootSource` / `DecodeBinding` records and `slotValueExpr` / decode-args block. Row-array declaration, cells construction, alias args, USING args, and the array/table types all delegate to the helper.
- `SelectMethodBody` (federated `_entities` + `Query.nodes`) collapsed onto the helper for the same five pieces. Switched `.join(input).on(t.COL.eq(input.field("COL", T.class))…)` to `.join(input).using(<usingArgs>)`. Added `Condition condition = DSL.noCondition()` declared before the join body so the SELECT chain is symmetric with the lookup site (`.where(condition)` is now present in dispatcher SQL; jOOQ folds `noCondition()` away at render time).
- f-E SQL-shape regression test (`GraphQLQueryTest.nodes_perTypeIdBatch_emitsValuesJoinOrderByIdxShape`) continues to pass; matches `join ` plus `values (` plus `order by` substrings, both `.on(...)` and `.using(...)` render to `join ... using (…)` shape that satisfies the pin.

### Implementation deviations

- **`cellsCode` accepts an `idxCellExpr` parameter**: the spec's helper API listed `cellsCode(slots, valueExpr, tableLocal)` but the two call sites need different idx cells (`DSL.inline(i)` vs `DSL.val(idx, Integer.class)`). Both render to typed `Field<Integer>`; the helper takes the idx-cell expression as an extra `CodeBlock` parameter.
- **`emitRowArrayDecl` takes `sizeExpr` as a string**: the spec hinted at `int arity` plus `String sizeExpr`. Arity is computed from `slots.size()` inside the helper; only `sizeExpr` ("n", "bindings.size()") needs to vary per caller.

## Motivation

After R50, two generators emit the same SQL shape for batched lookups by key tuple:

- `LookupValuesJoinEmitter` (`graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/LookupValuesJoinEmitter.java`) drives `@lookupKey` lookups (`Query.foo(keys: [...])`, `[FilmActorKey!]! @lookupKey`, etc.) for both the root path (`buildInputRowsMethod`) and the inline-child path (`buildChildInputRowsMethod`).
- `EntityFetcherDispatchClassGenerator` (`graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/util/EntityFetcherDispatchClassGenerator.java`) emits `select<TypeName>Alt<N>` per `@key` alternative for federated `_entities` and, via `QueryNodeFetcher.rowsNodes`, for `Query.node` / `Query.nodes`.

Both produce `VALUES (idx, c1, …) JOIN <table> ORDER BY idx`. R50 phase (f-E) pinned the dispatcher's emitted shape with a regression test (`GraphQLQueryTest.nodes_perTypeIdBatch_emitsValuesJoinOrderByIdxShape`, with a negative pin against legacy `WHERE row-IN`). The two pipelines remain parallel implementations, with duplicated typed-`Row<N+1>` construction, duplicated arity-22 caps, and duplicated `DSL.val(value, table.COL.getDataType())` cell building. Per *Generation-thinking* ("the same multi-arm type switch recurs across multiple generators"), they want to converge.

## Design

### What collapses

The shared *row-construction core* is the merge target, not the carrier model. Specifically:

1. The typed `Row<N+1>` array creation with the unchecked/rawtypes `@SuppressWarnings` cast.
2. The arity-22 cap and its error message.
3. The per-cell `DSL.val(value, table.COL.getDataType())` construction (so jOOQ's Converter binds without a SQL `CAST`).
4. The `DSL.values(rows).as(alias, "idx", "<sqlName>", ...)` aliasing.
5. The join-and-project tail: `.select(fields).from(table).join(input).using(<keyCols>).where(condition).orderBy(input.field("idx"))`.

A new helper holds these pieces. Concrete shape:

```java
final class ValuesJoinRowBuilder {
    record Slot(ColumnRef targetColumn) {}

    static TypeName[] rowTypeArgs(List<Slot> slots);          // Integer + per-slot column types; rejects arity > 22
    static void emitRowArrayDecl(CodeBlock.Builder b, List<Slot> slots, int arity, String rowsLocal, String sizeExpr);
    static CodeBlock cellsCode(List<Slot> slots, BiFunction<Slot, Integer, CodeBlock> valueExpr, String tableLocal);
    static CodeBlock aliasArgs(List<Slot> slots, String alias);   // "<alias>", "idx", "<sqlName1>", ...
    static CodeBlock usingArgs(List<Slot> slots, String tableLocal); // table.COL1, table.COL2, ...
}
```

The two emission sites supply their own per-row value source via the `valueExpr` callback (env / SelectedField / `bindings.get(i)`-derived), declare `Condition condition` before the join body, and construct any extra projection cells locally.

### What stays in `EntityFetcherDispatchClassGenerator`

The dispatcher's outer plumbing is *not* on the collapse path:

- `fetchEntities` / `resolveByReps` rep iteration, `__typename` lookup, per-type handler dispatch.
- `HandleMethodBody`'s alternative matching (`requiredFields`-most-specific-first cascade), per-rep DFE construction (`DataFetchingEnvironmentImpl.newDataFetchingEnvironment(env).arguments(rep).build()`), DIRECT/NODE_ID decode into `Object[] cols`, and `(altIdx, tenantId)` grouping.
- The `select<TypeName>Alt<N>` method's outer signature (`bindings, env, dsl, result`) and the result-scatter loop (`result[outIdx] = r`).

The dispatcher *keeps owning the decode*. The R50 changelog explicitly classifies the dispatcher's NodeId failure mode (`NullOnMismatch`: bad rep id silently leaves `result[i] = null`, federation surfaces its own resolution-failure error) as *dispatcher-driven, not carrier-driven*. Trying to re-route the decode through `LookupArg.ScalarLookupArg.ThrowOnMismatch` would either change federation/`Query.nodes` semantics or force a fourth `LookupArg`/extraction arm for `NullOnMismatch` (carrier complexity for no callers). DIRECT alternatives also don't map to any existing `LookupArg` arm: `MapInput`'s `InputColumnBinding.MapBinding` list is produced by the classifier from input-object schemas, not federation `@key`. Fabricating bindings just to share the slot type is incremental complexity with no payoff.

### The three emission deltas, resolved

| Axis | Lookup site | Dispatcher site | Resolution |
|---|---|---|---|
| Source of values | `env.getArgument` / `sf.getArguments()` | `bindings.get(i)` → `(idx, cols, repEnv)` | Caller-supplied `valueExpr` callback. |
| `__typename` literal | not projected | `DSL.inline("<TypeName>").as("__typename")` | Caller appends to the `fields` list before calling the join helper. Helper stays type-agnostic. |
| Join syntax | `.using(table.C1, …)` | `.on(t.C1.eq(input.field("C1", T.class)).and(…))` | **Switch dispatcher to `.using(...)`**. Dispatcher's FROM side is the entity's own jOOQ table only — no FK chain, so no quoted-name collision risk. Implementer: verify by running the `_entities` and `Query.nodes` execution tests after the flip; the f-E SQL-shape pin will also catch a regression. |
| `where(condition)` | declared by caller, default `DSL.noCondition()` | absent today | Dispatcher's `select<TypeName>Alt<N>` declares `Condition condition = DSL.noCondition();` before the join body. jOOQ folds this away at render time. |

The `idx` cell, the typed-row arity, and the order-by-idx scatter are already identical and move into the helper unchanged.

### Tenant plumbing

The dispatcher partitions reps by `(altIdx, tenantId)` *before* the SELECT call (`HandleMethodBody.emitDecodeAndGroup` / `emitGroupDispatch`). Each invocation of `select<TypeName>Alt<N>` already receives a single `groupEnv` (the first rep's per-rep DFE for that group), and the body extracts `dsl` from it. From the join helper's POV "a single env is in scope" — same as the lookup-site path. No tenant-aware extension point in the helper.

## Implementation

Single PR, one commit. No phase split: the carrier is small (~300 lines dispatcher + ~500 lines lookup) and there is no consumer-visible API change.

File-by-file:

- **New** `graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/util/ValuesJoinRowBuilder.java` — holds the five shared pieces above plus the arity-22 cap.
- `LookupValuesJoinEmitter.java` — drop `rowTypeArgs`, the local `Slot` record's row-array building, and the alias/using arg builders; route through `ValuesJoinRowBuilder`. `flattenSlots` and the `RootSource` / `DecodeBinding` records stay (lookup-specific). `addRowBuildingCore`'s decode-args block (DecodedRecord per-row decode + `GraphqlErrorException` on null) stays — it's specific to the lookup-site decode path.
- `SelectMethodBody.java` — drop `emitRowArray`, `emitJoinSelect`, and `columnNamesList`. Call into `ValuesJoinRowBuilder` for row-array, alias args, and using args. Append `DSL.inline("<TypeName>").as("__typename")` and `idxCol` locally to the field list. Declare `Condition condition = DSL.noCondition();` before the join.
- `EntityFetcherDispatchClassGenerator.java` — unchanged class-level structure; only `SelectMethodEmitter` updates if its constructor signature changes (it shouldn't).

The dispatcher's switch from `.on(...)` to `.using(...)` is part of this refactor, justified once in the helper's class Javadoc with the "no FK chain on the FROM side" rationale (mirroring `LookupValuesJoinEmitter`'s root-path Javadoc).

## Tests

The f-E regression test (`GraphQLQueryTest.nodes_perTypeIdBatch_emitsValuesJoinOrderByIdxShape`) and its negative pin against legacy `WHERE row-IN` already gate SQL shape — both must continue to pass. Federation `_entities` execution tests already exercise the dispatcher path end-to-end.

New unit coverage on `ValuesJoinRowBuilder` directly (no pipeline test expansion needed):

- Typed `Row<N+1>` arity matches `slots.size() + 1` for arities 1, 5, 21.
- Arity > 22 throws `IllegalStateException` with a clear message.
- Alias args contain `idx` followed by SQL names in slot order.
- Using args reference the correct table-alias-qualified column constants.

If `SelectMethodBody`'s sibling unit tests duplicate any of the above, shrink them to assert only dispatcher-specific behavior (the `__typename` literal, the `result[outIdx] = r` scatter call).

## Open questions

None — design is settled. Risks are bounded: the helper's contract is the existing emitted SQL shape, the f-E regression test guards it, and the carrier model is untouched.

## Roadmap entries (siblings / dependencies)

- **Originating context:** R50 (`lift-nodeid-out-of-model`) phase (f-E) pinned the dispatcher's SQL shape but left the two pipelines parallel; this item is the deferred follow-on.
- **Downstream consumer:** R36 (`stub-interface-union-fetchers`) Track B stage 2 is the planned third caller of `ValuesJoinRowBuilder`. R36 declares this as a hard `depends-on`; without R55 collapsing the two existing duplicates first, Track B's stage-2 emission would fork a third copy. The helper shape as designed accommodates Track B without modification: the `valueExpr` callback reads from stage 1's result rows (analogous to the lookup site's `env.getArgument` and the dispatcher's `bindings.get(i)`); the type-agnostic helper covers Track B's "no `__typename` projection in stage 2" case (stage 1 has already projected it, the merge step attaches it Java-side); `.using(...)` is correct because the participant table is the only FROM side, no FK chain; the optional WHERE-condition slot covers any per-typename filters Track B may append.
- **No dependencies on** R24, R40, R5, R27, those touch carrier-model or non-dispatcher emission paths and are independent.
