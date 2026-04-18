# G5 — Inline `TableField` emission

> **Status:** Draft
>
> Classification complete; emission stub throws `UnsupportedOperationException` for every `ChildField.TableField`. Prerequisite for argres Phase 2a (the lookup variant layers VALUES+JOIN onto this inline-subquery shape).

`ChildField.TableField` is a table-mapped child field that projects a nested record (or list of records) into the parent's SELECT via a correlated sub-SELECT. It is *not* a DataLoader path — it stays inline, a single round-trip for the parent and its nested reads.

This plan specifies the emission shape, the locus of change (`TypeClassGenerator.$fields`, not `TypeFetcherGenerator`), and the roll-out ordering. G5 is a prerequisite for [argres Phase 2a](argument-resolution.md#phase-2--child-field-lookup-generators-g5g6); the lookup variant layers a VALUES+JOIN keyset onto the inline-subquery shape G5 establishes, so landing G5 first avoids inventing both patterns simultaneously.

## Current State

- **Builder.** Every `@reference(path: […])` on a table-mapped field produces `ChildField.TableField` with resolved components: `joinPath: List<JoinStep>` (either `FkJoin` or `ConditionJoin` — both fully resolved, including FK Java constants and condition method refs), `filters: List<WhereFilter>`, `orderBy: OrderBySpec`, `pagination: PaginationSpec`. Builder coverage is extensive — `GraphitronSchemaBuilderTest` exercises every directive combination.
- **Generator.** `TypeFetcherGenerator.generateTypeSpec` dispatches `ChildField.TableField` to `stub(f)` (which throws at runtime). `TypeClassGenerator.$fields` has no arm — nested fields hit the `default -> { }` no-op and project nothing. In the variant-coverage partition: `TableField` sits in `NOT_IMPLEMENTED_REASONS` today.
- **Test coverage.** Zero execution coverage. `graphitron-rewrite-test-spec` has no inline `TableField` in its schema; classification tests cover the builder but no query runs end-to-end.

## Design

### Emission locus

Inline `TableField` emission lives in **`TypeClassGenerator.$fields`**, not `TypeFetcherGenerator`. The parent type's `$fields(sel, table, env)` method returns `List<Field<?>>` — one jOOQ `Field` per projected GraphQL field. A nested `TableField` becomes one entry in that list: a correlated sub-SELECT wrapped to produce a structured value (nested record or list-of-records).

`TypeFetcherGenerator`'s arm stays in `NOT_DISPATCHED_LEAVES` after G5 — no fetcher method is generated. That's a partition migration, documented below (Open decision 4).

### Shape

Two return-type variants. Both use the same correlated-subquery core; they differ in the wrapping that produces a scalar `Field` value.

**List return** (`[Film!]!`, `[Actor]`, …):

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

`sf` is the `SelectedField` pulled from the existing `sel.getFieldsGroupedByResultKey()` loop in `$fields` (see `TypeClassGenerator.java:120-122` for the current shape). `sf.getSelectionSet()` yields the nested `DataFetchingFieldSelectionSet` for the child field — no `getSelectionSetOf(String)` accessor is needed.

`DSL.multiset(Select)` returns `Field<Result<R>>` — a nested result set that jOOQ renders as a JSON array (PG) or equivalent dialect-specific nested value. The `<fieldName>Target` alias (`a0` above) is the deepest alias in the `joinPath`; additional hops become chained `.join(...)` calls before the `.where(...)`.

**Single return** (`Film`, `Actor!`):

The jOOQ 3.19 idiomatic shape for a correlated single record with multi-column projection is **multiset + mapping**: wrap in multiset as above, then `.convertFrom(r -> r.isEmpty() ? null : r.get(0))` on the outer field so the caller sees a single record, not a 1-element result. Alternative: scalar subquery with `DSL.field(select.asField())` — but that requires the inner SELECT to project exactly one field, which breaks recursive `$fields` projection.

**Decision deferred to implementation-time spike.** Two candidate shapes have different dialect-portability trade-offs; confirm against real PG rendering before locking in. Both produce the same end-user semantic.

**Divergence risk.** If the PG-rendering spike rejects `multiset + convertFrom` for single-record wrapping, the alternative (scalar subquery with `DSL.field(select.asField())`) is structurally incompatible with the list emitter — the scalar form requires a single-field projection, which breaks recursive `$fields`. In that case `InlineTableFieldEmitter.buildFieldExpression` forks into two distinct emitters (list vs. single) sharing only `JoinPathEmitter`. Deliverable 2 then becomes two commits instead of one. Evaluate at spike time; stay with single unified emitter unless PG forces the split.

### Join path emission

`joinPath` is an ordered list. Each step is one hop navigating towards the target; the chain is emitted inside the correlated subquery starting from the deepest target (FROM clause) and joining back towards the parent.

- **`FkJoin`**: `.join(alias).onKey(Keys.FK_JAVA_CONSTANT)`. If `whereFilter` is non-null, AND an extra `whereFilter.method(srcAlias, targetAlias)` onto the enclosing WHERE (per `JoinStep` javadoc — ON clause is untouched, WHERE clause is augmented).
- **`ConditionJoin`**: `.join(alias).on(condition.method(srcAlias, targetAlias))`. The method returns a jOOQ `Condition`.

Per `JoinStep` javadoc, correlated subqueries preserve outer rows regardless of inner match, so INNER JOIN is safe and preferred inside the subquery. G6 (flat batch) requires LEFT JOIN instead — out of scope here.

The **last-step correlation to the parent** uses the parent alias (`table` parameter) directly in the WHERE clause: `.where(a_first.parent_fk_col.eq(table.parent_pk_col))`. The exact WHERE shape follows from the first `JoinStep`'s resolved FK or condition — extracted by the emitter from the first step.

### Projection recursion

`$fields` recursively invokes the target type's own `$fields` method to project only the GraphQL-selected columns at each nested level:

```java
Actor.$fields(sel.getSelectionSetOf("actors"), a0, env)
```

The recursion terminates at `ColumnField` / `PlatformIdField` leaves (already emitted). For nested `TableField`, the recursion re-enters this same emission path with a deeper selection set. There is no depth limit at generation time; schema-enforced depth limits are enforced at query time by GraphQL-Java.

### Emitter extraction rationale

The rewrite's existing pattern is dispatch via sealed switches on generators (`TypeFetcherGenerator`, `TypeClassGenerator`), with helpers living as static methods on those generators. G5 introduces two separately-named `*Emitter` classes in `generators/` because:

- **Shared across three plans.** `JoinPathEmitter` is consumed by G5, argres Phase 2a (which layers VALUES+JOIN onto the same join shape), and G6 (flat batch with LEFT JOIN instead of INNER). Inlining the join-chain emission on `TypeClassGenerator` forces the other two consumers to either duplicate or reach across generator boundaries. `InlineTableFieldEmitter` is the single caller of `JoinPathEmitter` in G5 but itself is the hook point where Phase 2a layers keyset joining.
- **Non-trivial internal branching.** List vs. single-record wrapping, FK vs. condition joins, filter composition — enough logic that a static method on `TypeClassGenerator` becomes a grab-bag. A dedicated emitter class keeps the decision tree local.

These two emitters are a deliberate new convention. Future single-consumer emission can stay inline on its generator; shared emission goes into `generators/` as a dedicated class.

### Builder-invariant assumptions

`InlineTableFieldEmitter` assumes the following builder-level invariants. Anything violating these is a classifier bug and must surface as `UnclassifiedField` before reaching the emitter:

- `TableField.joinPath()` is non-empty (a zero-step path is meaningless — the field must navigate to at least its direct target).
- Every `JoinStep.ConditionJoin.method()` is fully resolved (classifier-time reflection succeeded; `MethodRef` is populated).
- `TableField.returnType().wrapper()` is `FieldWrapper.Single` or `FieldWrapper.List` — **not `Connection`** (see Open Decision 3 below; `@asConnection` on inline `TableField` must be rejected at classify time).
- `TableField.pagination()` is either `PaginationSpec.None` or a spec that projects to a `.limit(n)` only (no cursor decode).

The emitter may rely on these and emit without defensive checks. The validator catches violations before generation.

### Component deliverables

Ordered; each is a reviewable commit. Intermediate states may compile but fail execution tests — that's fine, execution tests land with the final commit.

1. **Classifier change: reject `@asConnection` on inline `TableField`.** In `FieldBuilder` — the path that constructs `TableField` at `FieldBuilder.java:260` currently accepts any `FieldWrapper` (including `Connection`). Return `UnclassifiedField` with an explanatory error ("`@asConnection` on inline (non-`@splitQuery`) TableField is not supported; add `@splitQuery` for batched connection semantics") when the resolved wrapper is `FieldWrapper.Connection`. New classification case in `GraphitronSchemaBuilderTest.TableFieldCase` asserting the rejection. This deliverable lands first so the emitter can safely assume the builder invariant.
2. **`JoinPathEmitter` (new class, `generators/`).** Pure function: `emit(List<JoinStep>, String srcAlias, String tgtAlias) -> CodeBlock`. Produces the `.join(..).onKey(..)` or `.join(..).on(..)` chain. Handles `FkJoin` + `ConditionJoin` uniformly. No correlation WHERE — that is the caller's job.
3. **`InlineTableFieldEmitter` (new class, `generators/`).** Top-level emitter: `buildFieldExpression(TableField, String parentAlias) -> CodeBlock`. Returns the full jOOQ `Field` expression (multiset + select + from + joins + where + orderby + limit) to be placed into `$fields`' returned list. Branches on list-vs-single return type. May fork into two classes if the single-record spike forces divergence (see Shape section).
4. **Factor out arg-call helpers.** Promote `buildCallArgs` / `buildArgExtraction` from `TypeFetcherGenerator` to a new `generators/ArgCallEmitter` class (public static methods). `TypeFetcherGenerator` calls migrate to the new home; `InlineTableFieldEmitter` consumes the same helpers for `@condition` filter emission. Resolves Open Decision 2.
5. **`TypeClassGenerator.$fields` switch arm.** Adds `case ChildField.TableField tf -> fields.add(InlineTableFieldEmitter.buildFieldExpression(tf, "table"));` to the existing field-name switch. Preserves the Column/PlatformId arms untouched. First commit after which the compilation test (deliverable 9) can pass.
6. **`TypeFetcherGenerator` dispatch + partition expansion.** Remove `ChildField.TableField` from `NOT_IMPLEMENTED_REASONS` and the dispatch switch. Add a new `PROJECTED_IN_TYPE_CLASS` set on `TypeFetcherGenerator` — `TableField` is its first member. Update `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus` to include `PROJECTED_IN_TYPE_CLASS` in the four-way disjoint/cover assertion. Javadoc cross-link the new set to the existing three. See Open Decision 4.
7. **Test schema additions.** Add to `graphitron-rewrite-test/graphitron-rewrite-test-spec/src/main/resources/graphql/schema.graphqls`:
   - Single-hop FK join: `Film.language: Language`.
   - Multi-hop FK join: `Film.languageOriginalCountry: Country` (via `language` → `country`).
   - List with ordering and `.limit`: `City.customers: [Customer!]!` with `@orderBy` and pagination args producing `.limit`.
   - Condition join: a field using `ConditionJoin` (reusing an existing `@condition` helper).
   - Self-referential recursion: `Film.sequels: [Film!]!` (exercised at depth 2 in execution tests — confirms Open Decision 5's recursion termination invariant in practice, not just in prose).
   - Update `graphitron-rewrite-test/graphitron-rewrite-test-spec/src/main/resources/db/init.sql` to seed reachable nested rows (list the exact INSERT additions in the commit message).
8. **Pipeline test.** New `TableFieldPipelineTest` in `graphitron-rewrite/src/test/java/no/sikt/graphitron/rewrite/` — SDL → classified schema → generated `TypeSpec`. Asserts structural shape (switch arm for each nested field, `$fields` signature unchanged, no fetcher method emitted for `TableField`). Parallel to the existing `StubbedVariantPipelineTest`.
9. **Unit tests (`TypeClassGeneratorTest`).** Structural: `$fields` contains a switch arm per GraphQL field name. No body-substring assertions.
10. **Compilation test.** `mvn compile -pl :graphitron-rewrite-test-spec -Plocal-db` passes — real jOOQ catalog catches type errors in emitted multiset/subquery. Runs after every deliverable from 5 onwards; gating for CI.
11. **Execution tests (`GraphQLQueryTest`).** `mvn test -pl :graphitron-rewrite-test-spec -Plocal-db`. One query per schema variant added in deliverable 7, plus one depth-2 query on `film.sequels.sequels.title` to exercise recursion end-to-end. Assert shape and content against seeded data.

### Resolved decisions

Resolutions committed during the Draft iteration. Carried here (rather than deleted) so implementers can see the reasoning.

2. **`@condition` / filters on the subquery.** Factor `buildCallArgs` / `buildArgExtraction` out of `TypeFetcherGenerator` into a new `generators/ArgCallEmitter` class with public static methods. `InlineTableFieldEmitter` calls the extracted helpers directly. See deliverable 4.
3. **Pagination in correlated subqueries.** `.limit(n)` works; Relay connection pagination (cursor decode + direction switch) is out of scope. `@asConnection` on inline `TableField` becomes an `UnclassifiedField` classifier error (deliverable 1) — the error message points users at `@splitQuery`, which G6 wires up for batched connection semantics. Before G5 the classifier silently built a `TableField` with `FieldWrapper.Connection`; G5 explicitly rejects that shape rather than letting the emitter improvise a broken one.
4. **Meta-test partition expansion.** Committed to option (a): add a fourth disjoint set `PROJECTED_IN_TYPE_CLASS` on `TypeFetcherGenerator`. The meta-test's `everyGraphitronFieldLeafHasAKnownDispatchStatus` absorbs the new set in a one-line extension (add the set to the union; add the two pairwise-disjoint cases against it). This is a compatible extension of the variant-coverage plan's Phase 1 partition — the contract "every leaf in exactly one set" is preserved, only the number of sets grows. No re-approval cycle on the variant-coverage plan; G5's deliverable 6 lands both the new set and the test update in one commit.
5. **Self-referential / recursive types.** `Film → sequels: [Film!]!` is legal GraphQL. The recursive `$fields` call terminates because the selection set at depth-N cannot include the same field at depth-(N+1) unless the client requests it; depth is bounded by the client's query. No generator-time infinite loop; no depth limit imposed by Graphitron. The test schema (deliverable 7) includes a self-referential case and deliverable 11 exercises depth-2 selection to verify the invariant runtime-side.

### Open decisions

One remaining, to be resolved during implementation:

1. **Single-record wrapping (gates deliverable 3).** `multiset + convertFrom` vs a different jOOQ idiom. Spike: render both against PG via `DSL.renderInlined(...)`, compare generated SQL. Pick the one closer to legacy's shape for consistency. If the multiset form is rejected, deliverable 3 forks into list and single-record emitters per the Divergence risk note in Shape.

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
