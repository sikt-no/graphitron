# G5 — Inline `TableField` emission

> **Status:** Done
>
> Emission lives in `TypeClassGenerator.$fields` as a correlated `DSL.multiset` subquery; `TableField` sits in `PROJECTED_LEAVES`. C1 (classifier rejection of `@asConnection` on inline `TableField`), C2 (`ArgCallEmitter` extraction), C3 (emitter + partition migration + four-way meta-test), C4 (schema fixtures + 7 execution tests) all landed. Three post-approval refinements (uniform multiset, runtime alias prefix, cardinality-driven FK direction) documented in history iteration 7. `ConditionJoin` runtime stub + `FkJoin.alias` dead storage + `ArgCallEmitter` first-arg hardcode deferred as classification-vocabulary followups items 5/6/7. Prerequisite for argres Phase 2a (the lookup variant layers VALUES+JOIN onto this shape); unblocked.

`ChildField.TableField` is a table-mapped child field that projects a nested record (or list of records) into the parent's SELECT via a correlated sub-SELECT. It is *not* a DataLoader path — it stays inline, a single round-trip for the parent and its nested reads.

This plan specifies the emission shape, the locus of change (`TypeClassGenerator.$fields`, not `TypeFetcherGenerator`), and the roll-out ordering. G5 is a prerequisite for [argres Phase 2a](argument-resolution.md#phase-2--child-field-lookup-generators-g5g6); the lookup variant layers a VALUES+JOIN keyset onto the inline-subquery shape G5 establishes, so landing G5 first avoids inventing both patterns simultaneously.

## Current State

- **Builder.** Every `@reference(path: […])` on a table-mapped field produces `ChildField.TableField` with resolved components: `joinPath: List<JoinStep>` (either `FkJoin` or `ConditionJoin` — both fully resolved, including FK Java constants and condition method refs), `filters: List<WhereFilter>`, `orderBy: OrderBySpec`, `pagination: PaginationSpec`. Builder coverage is extensive — `GraphitronSchemaBuilderTest` exercises every directive combination.
- **Generator.** `TypeFetcherGenerator.generateTypeSpec` dispatches `ChildField.TableField` to `stub(f)` (which throws at runtime). `TypeClassGenerator.$fields` has no arm — nested fields hit the `default -> { }` no-op and project nothing. In the variant-coverage partition: `TableField` sits in `NOT_IMPLEMENTED_REASONS` today. Its sibling `ChildField.LookupTableField` (inline-subquery emission layered with VALUES+JOIN by argres Phase 2a) also sits in `NOT_IMPLEMENTED_REASONS`; **G5 only migrates `TableField`**. `LookupTableField` stays stubbed until Phase 2a lands and moves it to `PROJECTED_LEAVES` as part of that work. The four-set partition is designed to grow incrementally, one variant at a time.
- **Test coverage.** Zero execution coverage. `graphitron-rewrite-test-spec` has no inline `TableField` in its schema; classification tests cover the builder but no query runs end-to-end.

## Design

### Emission locus

Inline `TableField` emission lives in **`TypeClassGenerator.$fields`**, not `TypeFetcherGenerator`. The parent type's `$fields(sel, table, env)` method returns `List<Field<?>>` — one jOOQ `Field` per projected GraphQL field. A nested `TableField` becomes one entry in that list: a correlated sub-SELECT wrapped to produce a structured value (nested record or list-of-records).

`TypeFetcherGenerator`'s arm moves to a new fourth set `PROJECTED_LEAVES` after G5 — no fetcher method is generated. The meta-test partition grows from three sets to four (landed in C3; see Resolved decision 4).

### Shape

> **Implementation update (C3 / dc07882 / plan history iteration 7).** The two-shape fork
> described below was empirically superseded: jOOQ 3.20's `DSL.row(Collection)` flattens nested
> aliased fields at render time, breaking depth-2+ self-referential projections. Both
> cardinalities now use `DSL.multiset(...)` uniformly; single cardinality adds `.limit(1)` inside
> the subquery and is unwrapped on the read side by a lambda `DataFetcher`. The list-cardinality
> shape below still matches the implementation; the single-cardinality shape is historical.
> See `InlineTableFieldEmitter.buildSwitchArmBody` for the current code.

Two emission shapes, chosen by the GraphQL return cardinality. Both share the correlated-subquery core (select + from + joins + where + orderBy + limit); only the outermost wrapping differs.

**List return** (`[Film!]!`, `[Actor]`, …) — `DSL.multiset`:

```java
case "actors" -> fields.add(
    DSL.multiset(
        DSL.select(Actor.$fields(sf.getSelectionSet(), a0, env))
            .from(a0)
            .where(<joinPath correlation against parent `table`>)
            .orderBy(<orderBy if present>)
            .limit(<pagination.limit if present>)
    ).as("actors")
);
```

`DSL.multiset(Select)` returns `Field<Result<R>>` — a nested result set rendered as a JSON array (PG) or dialect-specific nested value. `sf` is the `SelectedField` from the enclosing `sel.getFieldsGroupedByResultKey()` loop in `$fields` (see `TypeClassGenerator.java:120-122`); `sf.getSelectionSet()` yields the nested `DataFetchingFieldSelectionSet` for the child field. The `<fieldName>Target` alias (`a0` above) is the deepest alias in the `joinPath`; additional hops become chained `.join(...)` calls before the `.where(...)`.

**Single return** (`Film`, `Actor!`) — scalar subselect with `DSL.row`:

```java
case "language" -> fields.add(
    DSL.field(
        DSL.select(DSL.row(Language.$fields(sf.getSelectionSet(), l0, env)))
            .from(l0)
            .where(<joinPath correlation against parent `table`>)
    ).as("language")
);
```

`DSL.row(fields...)` wraps a multi-column projection into a `RowN` value; wrapping the select in `DSL.field(...)` turns it into a scalar subselect returning `Field<RowN>`. This is the idiomatic jOOQ 3.19 single-record shape and supports arbitrary multi-column projection — unlike `DSL.field(select.asField())`, which requires a single-field projection and is therefore *not* the right alternative.

The two shapes share everything except the outer wrap. `InlineTableFieldEmitter.buildFieldExpression` branches on `TableField.returnType().wrapper()` at the top; the shared select/join/filter/where/orderBy/limit composition lives in one place. No spike is needed — both shapes are well-trodden jOOQ 3.19 ground.

**Read-back.** Runtime wiring handles the client-side unwrap — jOOQ's default mapping produces `Result<Record>` for the multiset field and `Record` (or nested jOOQ records) for the `RowN` field, which the registered field DataFetchers consume the same way as any other selection-projected value. No custom converter is needed in emitted code.

### Join path emission

`joinPath` is an ordered list. Each step is one hop navigating towards the target; the chain is emitted inside the correlated subquery starting from the deepest target (FROM clause) and joining back towards the parent.

- **`FkJoin`**: `.join(alias).onKey(Keys.FK_JAVA_CONSTANT)`. If `whereFilter()` (the `MethodRef`) is non-null, AND an extra `<className>.<methodName>(srcAlias, targetAlias)` invocation onto the enclosing WHERE (per `JoinStep` javadoc — ON clause is untouched, WHERE clause is augmented).
- **`ConditionJoin`**: `.join(alias).on(<className>.<methodName>(srcAlias, targetAlias))`, where class/method come from `step.condition()` (a `MethodRef`). The method returns a jOOQ `Condition`.

INNER JOIN is correct inside the subquery because we want rows that participate in the full join chain; the subquery's null/empty return when the chain produces no rows is the desired outer behaviour (correlated subqueries are re-evaluated per outer row and yield null/empty independently of the outer's row existence). G6 (flat batch) requires LEFT JOIN instead — out of scope here.

The **first-step correlation to the parent** uses the parent alias (`table` parameter) directly in the WHERE clause. Direction depends on which side holds the FK column — child-owned (`film.language_id → language.language_id`) vs reverse (`rental.customer_id → customer.customer_id`) — so the builder must surface enough metadata for the emitter to branch.

**`FkJoin` enrichment (prep for C3).** Extend the record with resolved source/target column refs:

```java
record FkJoin(
    String fkName,
    String fkJavaConstant,
    TableRef sourceTable,      // NEW — table holding the FK columns
    List<ColumnRef> sourceColumns,  // NEW — FK columns in the source table
    TableRef targetTable,      // existing — table the FK points to
    List<ColumnRef> targetColumns,  // NEW — referenced PK columns in the target
    MethodRef whereFilter,
    String alias
) implements JoinStep {}
```

The builder already holds the jOOQ `ForeignKey` when it constructs `FkJoin`; pulling `fk.getTable()` / `fk.getFields()` / `fk.getKey().getTable()` / `fk.getKey().getFields()` costs one method per side. No classifier rule changes.

**Emitter correlation logic** (`JoinPathEmitter.correlationWhere(FkJoin first, String parentAlias, TableRef parentTable)`):

```java
if (first.sourceTable().equals(parentTable)) {
    // FK in parent (e.g. film.language_id → language.language_id):
    //   correlation is inner.targetCol = parent.sourceCol, matched by position
    return zipAnd(first.alias(), first.targetColumns(), parentAlias, first.sourceColumns());
} else {
    // FK in inner (e.g. rental.customer_id → customer.customer_id):
    //   correlation is inner.sourceCol = parent.targetCol
    return zipAnd(first.alias(), first.sourceColumns(), parentAlias, first.targetColumns());
}
```

Composite FKs AND all column pairs. Single-column FKs are the common case; the zip supports both uniformly. `JoinPathEmitter` asserts `sourceColumns.size() == targetColumns.size()` as a precondition — jOOQ's `ForeignKey` guarantees equal arity, but a mismatched builder-side resolution should fail loudly rather than emit silently broken SQL.

### Alias generation

`JoinPathEmitter` generates per-hop aliases deterministically from each hop's target-table `javaName()`: lowercase the initial letter + hop index starting at 0 (`Language` → `l0`, `Actor` → `a0`, `FilmActor` → `f0`). When two hops in the same chain share an initial, the second uses the first two lowercased characters (`fi0` for a `Film`/`FilmActor` collision). The parent alias is always `"table"` (the existing `$fields(sel, table, env)` parameter name).

Each correlated subquery is its own jOOQ scope, so aliases do not need to be unique across sibling `TableField` subqueries on the same parent — only within a single joinPath. Self-referential joins (`Category.parent: Category`) work because the inner alias (`c0`) is distinct from the outer `table` parameter. The emitter writes aliases into `FkJoin.alias()` before C3 lands (builder presently leaves it empty in some paths); alternatively, `JoinPathEmitter` assigns them on the fly from the `javaName()` + index and ignores the field's stored alias. Decide at C3 implementation time based on whether other consumers of `FkJoin.alias()` exist.

### Projection recursion

`$fields` recursively invokes the target type's own `$fields` method to project only the GraphQL-selected columns at each nested level:

```java
Actor.$fields(sf.getSelectionSet(), a0, env)
```

The recursion terminates at `ColumnField` / `PlatformIdField` leaves (already emitted). For nested `TableField`, the recursion re-enters this same emission path with a deeper selection set. There is no depth limit at generation time; schema-enforced depth limits are enforced at query time by GraphQL-Java.

**Alias threading.** The correlation predicate inside each nested subquery references the *immediately enclosing* `$fields` call's `table` parameter, not the outermost one. The emitter must read the parent alias from the `InlineTableFieldEmitter.buildFieldExpression(..., String parentAlias)` argument threaded through each level — never hardcode `"table"` in the correlation WHERE. A depth-2 subquery's correlation targets the depth-1 alias (`a0`), not the root `table` parameter. This falls out naturally from passing `parentAlias` explicitly, but the invariant is worth stating so a future refactor does not lose the thread.

### Emitter extraction rationale

The rewrite's existing pattern is dispatch via sealed switches on generators (`TypeFetcherGenerator`, `TypeClassGenerator`), with helpers living as static methods on those generators. G5 introduces two separately-named `*Emitter` classes in `generators/` because:

- **Shared across three plans.** `JoinPathEmitter` is consumed by G5, argres Phase 2a (which layers VALUES+JOIN onto the same join shape), and G6 (flat batch with LEFT JOIN instead of INNER). Inlining the join-chain emission on `TypeClassGenerator` forces the other two consumers to either duplicate or reach across generator boundaries. `InlineTableFieldEmitter` is the single caller of `JoinPathEmitter` in G5 but itself is the hook point where Phase 2a layers keyset joining.
- **Non-trivial internal branching.** List vs. single-record wrapping, FK vs. condition joins, filter composition — enough logic that a static method on `TypeClassGenerator` becomes a grab-bag. A dedicated emitter class keeps the decision tree local.

These two emitters are a deliberate new convention. Future single-consumer emission can stay inline on its generator; shared emission goes into `generators/` as a dedicated class.

### Builder-invariant assumptions

`InlineTableFieldEmitter` assumes the following builder-level invariants. Anything violating these is a classifier bug and must surface as `UnclassifiedField` before reaching the emitter:

- `TableField.joinPath()` is non-empty (a zero-step path is meaningless — the field must navigate to at least its direct target).
- Every `JoinStep.ConditionJoin.condition()` is fully resolved (classifier-time reflection succeeded; `MethodRef` is populated).
- `TableField.returnType().wrapper()` is `FieldWrapper.Single` or `FieldWrapper.List` — **not `Connection`** (see Open Decision 3 below; `@asConnection` on inline `TableField` must be rejected at classify time).
- `TableField.pagination()` is either `PaginationSpec.None` or a spec that projects to a `.limit(n)` only (no cursor decode).

The emitter may rely on these and emit without defensive checks. The validator catches violations before generation.

### Commit structure

Four commits, ordered. Each has a distinct failure mode if regressed; merging or splitting further would conflate or duplicate bisect points.

#### C1 — Classifier rejects `@asConnection` on inline `TableField`

In `FieldBuilder` — the path that constructs `TableField` at `FieldBuilder.java:260` currently accepts any `FieldWrapper` (including `Connection`). Return `UnclassifiedField` with the explanatory error "`@asConnection` on inline (non-`@splitQuery`) TableField is not supported; add `@splitQuery` for batched connection semantics" when the resolved wrapper is `FieldWrapper.Connection`. Add a classification case in `GraphitronSchemaBuilderTest.TableFieldCase` asserting the rejection.

Lands first so the emitter in C3 can safely assume the builder invariant `TableField.returnType().wrapper() != Connection`.

**Blast radius.** Across `graphitron-example-spec/schema.graphqls` and internal test schemas, every nested `@asConnection` on a table-to-table reference path also carries `@splitQuery` (classified as `SplitTableField`, not `TableField`) — the new validator error fires on zero known call-sites.

#### C2 — Extract `ArgCallEmitter` from `TypeFetcherGenerator`

Promote `buildCallArgs` / `buildArgExtraction` from `TypeFetcherGenerator` to a new `generators/ArgCallEmitter` class (public static methods). All `TypeFetcherGenerator` callers migrate to the new home; signatures unchanged. The inline-subquery emitter in C3 consumes the same helpers for `@condition` filter emission.

Pure refactor. Full test suite green with no other diffs. Isolated bisect point if fetcher emission regresses after C3 or later.

#### C3 — Implement inline `TableField` emission

One atomic change: two emitter classes, the `$fields` switch arm, partition migration, and structural tests. These parts are inseparable — the partition assertion and the meta-test's disjoint/cover property fail unless all land together.

**New classes in `generators/`.**

- **`JoinPathEmitter`.** Pure function: `emit(List<JoinStep>, String srcAlias, String tgtAlias) -> CodeBlock`. Produces the `.join(..).onKey(..)` or `.join(..).on(..)` chain, uniform over `FkJoin` + `ConditionJoin`. No correlation WHERE — the caller composes that from `joinPath.get(0)`'s resolved FK / condition against the parent alias.
- **`InlineTableFieldEmitter`.** Top-level: `buildFieldExpression(TableField, String parentAlias) -> CodeBlock`. Returns the full jOOQ `Field` expression (outer wrap + select + from + joins + where + orderBy + limit) for placement into `$fields`' returned list. Branches on `TableField.returnType().wrapper()`: `DSL.multiset(...)` for list return, `DSL.field(DSL.select(DSL.row(...))...)` for single return — shared composition beneath (see Shape).

**`TypeClassGenerator.$fields`.** Add `case ChildField.TableField tf -> fields.add(InlineTableFieldEmitter.buildFieldExpression(tf, "table"));` to the field-name switch. Column / PlatformId arms unchanged.

**`TypeFetcherGenerator`.** Remove `ChildField.TableField` from `NOT_IMPLEMENTED_REASONS` and from the dispatch switch. Introduce `PROJECTED_LEAVES` — `TableField` is its first member. Javadoc cross-links the new set to the existing three.

**Meta-test.** Add `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus` asserting full four-way disjoint/cover against `IMPLEMENTED_LEAVES`, `NOT_DISPATCHED_LEAVES`, `NOT_IMPLEMENTED_REASONS.keySet()`, and the new `PROJECTED_LEAVES`. Today `TypeFetcherGenerator`'s javadoc (lines 102, 145) references this test by name, but it doesn't exist — only `notImplementedReasonsContainsOnlyConcreteSealedLeaves` does, which spot-checks one of the three sets. C3 lands the method for the first time, already four-way; the javadoc reference becomes accurate.

**Structural tests (same commit).**

- **Pipeline:** new `TableFieldPipelineTest` in `graphitron-rewrite/src/test/java/no/sikt/graphitron/rewrite/` — SDL → classified schema → generated `TypeSpec`. Asserts switch-arm presence per nested field, `$fields` signature unchanged, no fetcher method emitted for `TableField`. Parallel to `StubbedVariantPipelineTest`.
- **Unit (`TypeClassGeneratorTest`):** `$fields` contains a switch arm per GraphQL field name. No body-substring assertions.

**Compilation gate.** `mvn compile -pl :graphitron-rewrite-test-spec -Plocal-db` must pass — the real jOOQ catalog catches type errors in the emitted multiset / row subquery.

#### C4 — Test schema additions + execution tests

Schema fixtures and end-to-end tests land together because either half alone is dead code.

**`graphitron-rewrite-test/graphitron-rewrite-test-spec/src/main/resources/graphql/schema.graphqls`.**

- Single-hop FK: `Film.language: Language`.
- Multi-hop FK: `Film.languageOriginalCountry: Country` (via `language` → `country`).
- List with ordering + `.limit`: `City.customers: [Customer!]!` with `@orderBy` and pagination args producing `.limit`.
- Condition join: a field using `ConditionJoin` (reusing an existing `@condition` helper).
- Self-referential recursion: `Category.parent: Category` and `Category.children: [Category!]!`, both via the new FK.

**`graphitron-rewrite-test/graphitron-rewrite-test-fixtures/src/main/resources/init.sql`.** The fixture module owns the shared schema + seed data consumed by test-spec. Stock sakila has no self-referential FK, so the self-referential case requires an `init.sql` delta:

- `ALTER TABLE category ADD COLUMN parent_category_id int REFERENCES category(category_id);`
- Seed parent/child rows sufficient to exercise depth-2 recursion, plus INSERTs for any other nested rows the new fields require. List the exact additions in the commit message.

**Execution tests (`GraphQLQueryTest`).** `mvn test -pl :graphitron-rewrite-test-spec -Plocal-db`.

- One query per schema case above, **with one exception**.
- Depth-2 recursion: `{ categories { name parent { name parent { name } } } }` — verifies Resolved Decision 5's "recursion terminates on client selection depth" invariant holds end-to-end, not just in prose.

**Condition-join execution test deferred.** The `ConditionJoin` test case is specified in the schema fixtures (so the classifier path is exercised) but its execution test is deferred to classification-vocabulary item 5. Item 5 documents the `@condition` method signature for both inline and lookup paths and adds a shared execution test; until that lands, G5's `ConditionJoin` execution assertion would duplicate work item 5 owns. C4 emits the schema fixture, lets the classifier build the `ConditionJoin` step, and verifies compilation — but skips runtime query-result assertion for that specific case. This keeps G5 off item 5's critical path.

### Resolved decisions

Resolutions committed during the Draft iteration. Carried here (rather than deleted) so implementers can see the reasoning.

1. **Single-record wrapping.** The natural SQL-level fork is the right answer: `DSL.row(...)` scalar subselect for single return, `DSL.multiset` for list return. Both share join/filter/where/orderBy/limit emission (via `JoinPathEmitter` and `ArgCallEmitter`); only the outermost wrap differs. No spike needed — both shapes are idiomatic jOOQ 3.19 and support arbitrary multi-column projection. Earlier drafts posited a `multiset + convertFrom` vs. `DSL.field(select.asField())` fork; the scalar-subselect alternative was the wrong one (`select.asField()` requires a single-column projection, so it can't wrap a multi-column record), and `DSL.row` is the correct idiom for the single-record case. See Shape section for the concrete emitter shapes.

2. **`@condition` / filters on the subquery.** Factor `buildCallArgs` / `buildArgExtraction` out of `TypeFetcherGenerator` into a new `generators/ArgCallEmitter` class with public static methods. `InlineTableFieldEmitter` calls the extracted helpers directly. See C2.
3. **Pagination in correlated subqueries.** `.limit(n)` works; Relay connection pagination (cursor decode + direction switch) is out of scope. `@asConnection` on inline `TableField` becomes an `UnclassifiedField` classifier error (C1) — the error message points users at `@splitQuery`, which G6 wires up for batched connection semantics. Before G5 the classifier silently built a `TableField` with `FieldWrapper.Connection`; G5 explicitly rejects that shape rather than letting the emitter improvise a broken one.
4. **Meta-test partition expansion.** Committed to option (a): add a fourth disjoint set `PROJECTED_LEAVES` on `TypeFetcherGenerator`. `everyGraphitronFieldLeafHasAKnownDispatchStatus` is *added* in C3 (not extended — it's referenced by javadoc today but not implemented; only `notImplementedReasonsContainsOnlyConcreteSealedLeaves` exists, spot-checking one of three sets). The new test asserts full four-way disjoint/cover from day one. This is a compatible extension of the variant-coverage plan's Phase 1 partition — the contract "every leaf in exactly one set" is preserved, only the number of sets grows. No re-approval cycle on the variant-coverage plan; G5's C3 lands both the new set and the test in one commit.
5. **Self-referential / recursive types.** `Category → parent: Category` / `Category → children: [Category!]!` is legal GraphQL. The recursive `$fields` call terminates because the selection set at depth-N cannot include the same field at depth-(N+1) unless the client requests it; depth is bounded by the client's query. No generator-time infinite loop; no depth limit imposed by Graphitron. C4 adds the self-referential fixture and the depth-2 execution query that verifies the invariant runtime-side.

### Open decisions

None remaining. All five original open decisions are resolved — implementation can proceed without further design spikes.

### Known runtime pitfalls

- **Non-null return with optional FK.** An SDL `originalLanguage: Language!` over the nullable `film.original_language_id` FK classifies and generates cleanly, then fails at response-construction time when the correlated subquery returns no row (the inner `Field<RowN>` is null). The validator does not forbid this combination; the mismatch is detected by GraphQL-Java at runtime. Schema authors are responsible for marking FK-optional fields nullable. (The mandatory `film.language_id` FK does not exhibit this pitfall — only `original_language_id` does.)

### Non-goals

- **DataLoader (G6).** This plan covers inline-only; `@splitQuery` variants stay stubs for G6.
- **Interface / union child fields.** `ChildField.InterfaceField` / `UnionField` remain stubs — different emission story.
- **`@condition` that references user-method maps (`TextMapLookup`).** Existing filter-emission helpers handle these; no new work.
- **Legacy cursor decoding for `@asConnection` on inline fields.** Deferred (Open decision 3).

## Cross-plan Dependencies

- **argres Phase 2a (`LookupTableField`)** depends on G5. Phase 2a inserts a VALUES+JOIN keyset (from `LookupValuesJoinEmitter`) into the same correlated-subquery shape this plan establishes. Once G5 is complete, 2a re-opens with a narrow scope: "add a `.join(values)` derived table to the same subquery shape and AND the keyset's USING into the WHERE."
- **G6 table-mapped `LookupTableField`** extends the correlated-subquery shape into a DataLoader-batched form (LEFT JOIN mandatory per `JoinStep` javadoc). Same emitter base plus DataLoader rows-method wrapper.

## Test Strategy

Per CLAUDE.md: structural assertions for unit tests, behaviour-level assertions for execution tests. No body-substring assertions on emitted `CodeBlock`s.

| Surface | What is verified | Example |
|---|---|---|
| Unit (`TypeClassGeneratorTest`) | Switch arms exist per GraphQL field name; `$fields` signature unchanged | `assertThat(arms).containsExactly("title", "releaseYear", "language", "actors")` |
| Pipeline | SDL → structural properties of generated TypeSpec | exhaustive dispatch; no stray `default` cases for a mapped field |
| Compilation | `mvn compile -pl :graphitron-rewrite-test-spec` | real jOOQ types, catches nested projection type errors |
| Execution | `mvn test -pl :graphitron-rewrite-test-spec` queries against real PG | `{ films { title language { name } actors { firstName } } }` returns correct nested shape |

## History

- 2026-04-18 — plan drafted after argres Phase 1 reconnaissance flagged G5 as a prerequisite. Status: not started. Five open decisions pinned.
- 2026-04-18 — reviewer iteration: resolved Decision 4 (partition expansion) and Decision 3 (@asConnection classifier behavior); corrected SelectionSet API in the proposed emission shape; committed the emitter-extraction rationale; added builder-invariant assumptions, recursive-type test coverage, pipeline test, and execution profile/seed details to deliverables.
- 2026-04-18 — second reviewer iteration: corrected the single-record wrapping idiom (now `DSL.row(...)` scalar subselect, not `multiset + convertFrom`; the previously-posited `DSL.field(select.asField())` alternative was wrong — it requires single-column projection) and resolved Decision 1; fixed the partition-migration statement in Emission locus (was "stays in `NOT_DISPATCHED_LEAVES`", now correctly "moves to new `PROJECTED_LEAVES` set"); unified the Projection recursion example to `sf.getSelectionSet()`; pinned the `ArgCallEmitter` extraction as a behavior-preserving refactor; annotated the classifier-rejection blast radius (zero known call-sites in example + test schemas); called out the `init.sql` schema delta required for the self-referential test case. All five open decisions now resolved.
- 2026-04-18 — third reviewer iteration: restructured 11 deliverables into 4 commits with distinct failure modes (classifier rejection / `ArgCallEmitter` refactor / emission + structural tests / schema + execution tests); merged the dead-intermediate states (`JoinPathEmitter` alone, `$fields` switch arm alone, partition migration alone) into C3; aligned the execution-test recursion example with the `Category.parent` / `Category.children` self-FK fixture (was previously `film.sequels.sequels`, mismatching the init.sql delta in the same deliverable).
- 2026-04-18 — fourth reviewer iteration: resolved three implementation hazards the third draft left for C3. (a) Read-back path clarified — jOOQ's default mapping handles `Result<Record>` / `RowN` unwrap; no custom converter needed (runtime wiring consumes them like any other selection-projected value). (b) FK-direction pinned — `FkJoin` gains `sourceTable`, `sourceColumns`, `targetColumns` fields (builder resolves from the jOOQ `ForeignKey`); `JoinPathEmitter.correlationWhere` branches on whether source table equals the parent, flipping the equality shape. Composite FKs AND the paired columns. (c) Alias generation convention nailed — deterministic from `TableRef.javaName()` + hop index, with a two-char fallback on collision; parent alias stays `"table"`. Nits folded: `PROJECTED_IN_TYPE_CLASS` → `PROJECTED_LEAVES` (uniform with siblings); INNER-JOIN justification reworded (outer-row preservation is about correlated-subquery evaluation, not inner-join semantics); added a "Known runtime pitfalls" section noting the `Language!` + optional-FK response-construction failure path.
- 2026-04-18 — sixth reviewer iteration (approval): Draft → Approved after verifying factual claims against trunk. Two corrections: (a) the "Known runtime pitfall" example swapped from the NOT NULL `film.language_id` to the nullable `film.original_language_id` (only the latter exhibits the pitfall in the fixture schema); (b) the C3 meta-test work reframed from "extend `everyGraphitronFieldLeafHasAKnownDispatchStatus`" to "add" — the test is referenced by `TypeFetcherGenerator`'s javadoc (lines 102, 145) but has never been implemented; today only `notImplementedReasonsContainsOnlyConcreteSealedLeaves` exists, spot-checking one of three sets. C3 lands the four-way assertion for the first time, making the javadoc reference honest. All other claims verified: `$fields` signature (`TypeClassGenerator.java:112-137`), three-set partition present (`TypeFetcherGenerator.java:106, 123, 154`), `ConditionJoin.condition()` accessor, `FieldWrapper.Connection` currently unrejected by `buildWrapper`, `buildCallArgs`/`buildArgExtraction` ready to move, `StubbedVariantPipelineTest` exists as claimed, `init.sql` path correct, no `parent_category_id` column today.
- 2026-04-18 — fifth reviewer iteration (factual corrections + partition scope): (a) corrected `ConditionJoin.method()` → `.condition()` in the builder-invariant section and rewrote lines 67-68 to disambiguate `MethodRef` access vs. invocation (class/method come from `step.condition()`; emission writes `<className>.<methodName>(srcAlias, targetAlias)`). (b) corrected the C4 `init.sql` path from `graphitron-rewrite-test-spec/src/main/resources/db/init.sql` to `graphitron-rewrite-test-fixtures/src/main/resources/init.sql` (fixtures module owns the shared schema + seed consumed by test-spec). (c) pinned the partition scope: G5 migrates *only* `TableField` to `PROJECTED_LEAVES`; `LookupTableField` stays in `NOT_IMPLEMENTED_REASONS` until argres Phase 2a, which ships its own one-variant migration. The four-set partition grows one variant at a time. (d) resolved the item-5 gating ambiguity for the `ConditionJoin` execution test: C4 ships the `ConditionJoin` schema fixture (exercises the classifier) but defers the runtime query-result assertion to classification-vocabulary item 5, which owns the shared `@condition` exec-test pattern. G5 stays off item 5's critical path. (e) added a `JoinPathEmitter` precondition `sourceColumns.size() == targetColumns.size()` (jOOQ guarantees this; fail loudly on builder-side violation). (f) made the parent-alias threading invariant explicit in Projection recursion — the correlation predicate reads the immediately enclosing `$fields`' `table` parameter, never hardcodes `"table"` in nested subqueries.
- 2026-04-18 — implementation complete; In Progress → Pending Review. C1–C4 landed. Three post-approval implementation refinements surfaced while exercising the generated code against real jOOQ 3.20 and PostgreSQL 16:
  1. **Uniform `DSL.multiset` wrap.** Plan specified `DSL.row(...)` scalar subselect for single cardinality. In jOOQ 3.20, `DSL.row(Collection)` containing an aliased nested `Field<?>` flattens the inner alias into a bare column reference at render time — breaking depth-2+ self-ref queries. Implementation switched to `DSL.multiset(...)` for both cardinalities with `.limit(1)` on the single path; wiring unwraps the `Result<Record>` to its first row for single-cardinality fields via an inline lambda `DataFetcher`. Plan's single-record "scalar subselect with DSL.row" guidance is now empirically superseded for jOOQ 3.20.
  2. **Runtime alias prefix for recursive depth.** Plan specified deterministic per-hop aliases (`l0`, `c0`, with two-char fallback on same-chain collision). Works for same-chain collisions; does NOT handle cross-depth collisions in self-referential recursion (e.g. `category { parent { parent { … } } }` emits nested subselects all using `"c0"`, which PostgreSQL scoping resolves to the innermost, yielding a self-reference `c0.category_id = c0.parent_category_id`). Implementation derives the per-hop alias at runtime from the enclosing table's `getName()` — each hop is `<parentAlias>_<staticSuffix>`, making aliases globally unique across arbitrary nesting depth. The static `JoinPathEmitter.generateAliases` suffix remains deterministic as specified; the runtime prefix is purely additive uniqueness.
  3. **Cardinality-driven FK direction (self-ref).** Plan specified `first.sourceTable().equals(parentTable)` as the parent-holds-FK discriminator. Works for non-self-ref; fails for self-referential FKs where both sides are the same table (e.g. `Category.parent` and `Category.children` traverse the same FK but in opposite directions). Implementation uses cardinality as the reliable signal — `Single` → parent holds FK, `List` → parent is the PK side. Subsumes the source-table check and works for all cases.
  Findings from attempting `ConditionJoin` inline emission captured in `plan-classification-vocabulary-followups.md` item 5: proposed `ConditionJoin` enrichment (add `TableRef targetTable`), emitter shape once item 5 resolves target tables, and the `Category.similar` fixture ready for item 5 to build on.
- 2026-04-19 — reviewer pass on Pending Review: stale javadoc in `InlineTableFieldEmitter` (class-level and `buildInnerSelect`-level comments still described the two-shape fork) updated to match the uniform `DSL.multiset` implementation; plan's `Shape` section annotated with an implementation-update callout pointing at history iteration 7. Two latent issues captured as classification-vocabulary followups: item 6 (`FkJoin.alias` is dead storage — builder writes it, nothing reads it) and item 7 (`ArgCallEmitter.buildCallArgs` hardcodes `"table"` as the first argument — wrong alias on the inline-subquery path; untested because no G5 fixture uses `@condition(filter:)` on inline `TableField`). Pending Review → Done.
