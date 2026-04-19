# argres Phase 2b — Split(Lookup)TableField DataLoader rows-method emission

> **Status:** Draft
>
> Skeleton. Fills in the DataLoader rows-method bodies for `ChildField.SplitTableField` and `ChildField.SplitLookupTableField`, which today compile but throw `UnsupportedOperationException` at runtime. Completes the split-query story for inline DataLoader batching; `RecordLookupTableField` (Phase 2c) remains out of scope pending the `BatchKey.ObjectBased` model decision.

Phase 2a made `LookupTableField` render inline via `DSL.multiset` + VALUES/JOIN keyset. Phase 2b covers the DataLoader-batched complement: flat SELECT per batch instead of a per-parent multiset projection. Together, 2a and 2b close the `LookupField` generation story and make the two most common split-query shapes work end-to-end.

## Current state

- **Model.** `SplitTableField` (`ChildField.java:128`) and `SplitLookupTableField` (`ChildField.java:157`) both implement `TableTargetField` + `BatchKeyField`; `SplitLookupTableField` additionally implements `LookupField` (carries `LookupMapping`). `BatchKeyField` capability is the join point — three implementers (Split*, `ServiceTableField`); each overrides `rowsMethodName()` to `"rows" + Capitalized(name)`.
- **Generator.** Both variants sit in `IMPLEMENTED_LEAVES` today — fetcher + rows-method skeletons are emitted. The rows-method body at `TypeFetcherGenerator.buildSplitRowsMethod` (line 1026) is a stub: `public static Object rowsXxx(List<KeyElementType> sources)` whose body is `throw new UnsupportedOperationException()`. The method signature's `Object` return is a placeholder — the real DataLoader-consumable shape is one of the open decisions below.
- **Phase 2a reuse surface.** `LookupValuesJoinEmitter.buildChildInputRowsMethod` (line 132) already reads `@lookupKey` args from a `SelectedField` and builds the typed `Row[]`. `JoinPathEmitter` from G5 handles the join chain. `ArgCallEmitter` handles `@condition` filter args. Phase 2b adds one new emitter on top of these.
- **Validator.** Stubbed-variant validator (Pending Review) does not flag Split* today because they compile cleanly and sit in `IMPLEMENTED_LEAVES`. Phase 2b closes this honesty gap — after 2b, `IMPLEMENTED_LEAVES` means "emits working code", not "emits a method whose body throws".

## Design

### Emission locus

The rows-method stays where it already lives: a `public static` method on the fetcher class, one per split field, named `rowsXxx`. Phase 2b replaces the body only. Fetcher method signature and DataLoader wiring are unchanged.

### Shape

Flat batched SELECT. Both variants share the same outer shape:

```java
public static <ReturnShape> rowsXxx(List<KeyElementType> sources) {
    return dsl.select(<projected fields>)
        .from(<target table alias>)
        <joinPath chain via JoinPathEmitter>
        .join(<VALUES-derived input table>).using(<key columns>)
        .where(<filters + correlation>)
        .orderBy(<orderBy, including input.idx for stable parent-keyed order>)
        .fetch();
}
```

They differ only in **how the input keyset is built**:

- **`SplitLookupTableField`** — reuse `LookupValuesJoinEmitter.buildChildInputRowsMethod` unchanged. Keys come from `@lookupKey` arg extraction (exactly like Phase 2a, but the consumer is the rows-method flat SELECT instead of Phase 2a's multiset wrap).
- **`SplitTableField`** — no `@lookupKey`; keys are parent PK/FK values carried in `sources`. Needs a sibling helper on `LookupValuesJoinEmitter` (or a new mini-emitter) that builds the typed `Row[]` from the `sources: List<KeyElementType>` parameter directly. Smaller than `buildChildInputRowsMethod` — no argument extraction, just typed row construction.

The shared composition (from/joinPath/where/filters/orderBy) lives in one new emitter (`SplitRowsMethodEmitter`), which branches on the input-keyset source at the top and then delegates to the shared core. Mirrors how G5's `InlineTableFieldEmitter` branches on return cardinality.

### Components (skeleton)

Ordered; each a reviewable commit. Bodies are intentionally coarse at this stage — flesh out during next Draft iteration.

1. **Rows-method emitter.** New `SplitRowsMethodEmitter` in `generators/`. Public static `buildBody(BatchKeyField) -> CodeBlock`, branches on `SplitTableField` vs `SplitLookupTableField` to pick the input-row helper, then composes the shared flat SELECT.
2. **Parent-key rows helper.** Sibling to `buildChildInputRowsMethod` on `LookupValuesJoinEmitter` (or new class — decide during Draft) that builds `Row[]` from the `sources` parameter for the non-`@lookupKey` case.
3. **Update `buildSplitRowsMethod`.** Replace the `UnsupportedOperationException` body with `SplitRowsMethodEmitter.buildBody(...)`. Fix the return type (see Open Decision 1).
4. **Schema fixtures + execution tests.** Add `@splitQuery` and `@splitQuery + @lookupKey` cases to `graphitron-rewrite-test-spec/schema.graphqls`, plus seeded data. Execution tests cover single-hop / multi-hop / filter / orderBy; `@asConnection` coverage contingent on Open Decision 3.

### Open decisions

First iteration — not yet resolved.

1. **Rows-method return type.** Current signature returns `Object` (placeholder). The real shape is driven by how the fetcher method consumes the rows and how DataLoader re-scatters by key. Candidates: `Result<Record>` (flat, scatter at fetcher-body layer), `Map<KeyElementType, List<Record>>` (pre-scattered in the rows method), or a jOOQ-native DataLoader shape. Trace the generated fetcher body from Phase 2a (or the legacy codegen's DataLoader rows method) to decide. Gates deliverable 3.
2. **`SplitTableField` input-row shape.** Two options: (a) VALUES + USING derived table keyed by parent PK/FK (matches 2a's pattern exactly — shared mental model, shared aliasing conventions); (b) `WHERE parent_fk IN (values)` (simpler SQL, no derived table). Both render fine on PG. Pick (a) unless a concrete advantage for (b) surfaces — consistency with 2a keeps the emitter small. Gates deliverable 2.
3. **Pagination on split fields (`@asConnection`).** Per-batch pagination in a DataLoader rows method is hard — each parent wants its own window over its own child result set, but the rows method fetches one flat result for N parents. Options: (i) defer — `@asConnection` on `Split*` classifies as `UnclassifiedField` until a later phase; (ii) support via window functions (`ROW_NUMBER() PARTITION BY parent_key`); (iii) support via per-parent subquery emission (closer to legacy). Choose (i) for 2b unless (ii) is small and well-understood — pagination is a large design space we should not open here. Gates scope boundary.
4. **Empty-input short-circuit.** Phase 2a emits `where(falseCondition())` when the input rows are empty. DataLoader rarely dispatches empty batches (its framework collapses them), but the rows method is a `public static` utility reachable independently. Decide: emit the Phase-2a-style guard, or trust the DataLoader contract. Cheap to include; probably include. Gates deliverable 1.
5. **`ServiceTableField` scope.** Third `BatchKeyField` implementer. Roadmap puts it in `IMPLEMENTED_LEAVES` — verify whether its rows method is real or also a stub. If stub, decide whether to pull it into 2b or leave for a separate plan. Gates scope boundary.

### Non-goals

- **`RecordLookupTableField` (Phase 2c).** Blocked on the `BatchKey.ObjectBased` decision (roadmap Backlog). Out of scope.
- **Union / interface split fields.** Different classification path; separate plan.
- **Pagination** if Open Decision 3 resolves to defer.
- **Mutation bodies** (roadmap stub priority #4). Separate track.

### Cross-plan dependencies

- **No partition migration.** Both variants already sit in `IMPLEMENTED_LEAVES`; Phase 2b fills in bodies, meta-test contract is unchanged.
- **Stubbed-variant validator (Pending Review).** 2b closes a known "IMPLEMENTED but throws at runtime" gap — after 2b lands, `IMPLEMENTED_LEAVES` semantics tightens to "emits working code". Update the validator's invariant doc once 2b is Done; no code change in the validator itself.
- **G5 / Phase 2a reuse.** `JoinPathEmitter` (G5), `ArgCallEmitter` (G5), `LookupValuesJoinEmitter.buildChildInputRowsMethod` (2a). All consumed, none modified.
- **argres Phase 2c.** Depends on 2b for the emission pattern plus the `BatchKey.ObjectBased` decision (Backlog).
- **argres Phase 3.** Independent — mutation / `InputColumnBinding` path.

## Test strategy

Per CLAUDE.md — structural at unit tier, behaviour at execution tier:

| Surface | What is verified |
|---|---|
| Unit | `buildSplitRowsMethod` returns a `MethodSpec` with the expected return type and parameter; no body-substring assertions. |
| Pipeline | SDL with `@splitQuery` and `@splitQuery + @lookupKey` produces a fetcher `TypeSpec` with `rowsXxx` methods; structural shape only. |
| Compilation | `mvn compile -pl :graphitron-rewrite-test-spec -Plocal-db` — real jOOQ catches type errors in flat SELECT emission. |
| Execution | `mvn test -pl :graphitron-rewrite-test-spec -Plocal-db` — batched queries against seeded PG data. Assert shape, content, and DataLoader batching semantics (N parents → 1 SQL round-trip). |

## History

- 2026-04-19 — skeleton draft after Phase 2a landed (Pending Review). Five open decisions pinned; bodies intentionally coarse. Next iteration: resolve return-type shape (#1) and pagination stance (#3), then tighten deliverable 1's body spec.
