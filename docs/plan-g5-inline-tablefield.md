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
        DSL.select(Actor.$fields(sel.getSelectionSetOf("actors"), a0, env))
            .from(a0)
            .where(<joinPath correlation against parent `table`>)
            .orderBy(<orderBy if present>)
            .limit(<pagination.limit if present>)
    ).as("actors")
);
```

`DSL.multiset(Select)` returns `Field<Result<R>>` — a nested result set that jOOQ renders as a JSON array (PG) or equivalent dialect-specific nested value. The `<fieldName>Target` alias (`a0` above) is the deepest alias in the `joinPath`; additional hops become chained `.join(...)` calls before the `.where(...)`.

**Single return** (`Film`, `Actor!`):

The jOOQ 3.19 idiomatic shape for a correlated single record with multi-column projection is **multiset + mapping**: wrap in multiset as above, then `.convertFrom(r -> r.isEmpty() ? null : r.get(0))` on the outer field so the caller sees a single record, not a 1-element result. Alternative: scalar subquery with `DSL.field(select.asField())` — but that requires the inner SELECT to project exactly one field, which breaks recursive `$fields` projection.

**Decision deferred to implementation-time spike.** Two candidate shapes have different dialect-portability trade-offs; confirm against real PG rendering before locking in. Both produce the same end-user semantic.

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

### Component deliverables

Ordered; each is a reviewable commit. Intermediate states may compile but fail execution tests — that's fine, execution tests land with the final commit.

1. **`JoinPathEmitter` (new class, `generators/`).** Pure function: `emit(List<JoinStep>, String srcAlias, String tgtAlias) -> CodeBlock`. Produces the `.join(..).onKey(..)` or `.join(..).on(..)` chain. Handles `FkJoin` + `ConditionJoin` uniformly. No correlation WHERE — that is the caller's job.
2. **`InlineTableFieldEmitter` (new class, `generators/`).** Top-level emitter: `buildFieldExpression(TableField, String parentAlias) -> CodeBlock`. Returns the full jOOQ `Field` expression (multiset + select + from + joins + where + orderby + limit) to be placed into `$fields`' returned list. Branches on list-vs-single return type.
3. **`TypeClassGenerator.$fields` switch arm.** Adds `case ChildField.TableField tf -> fields.add(InlineTableFieldEmitter.buildFieldExpression(tf, "table"));` to the existing field-name switch. Preserves the Column/PlatformId arms untouched.
4. **`TypeFetcherGenerator` dispatch.** Remove `ChildField.TableField` from `NOT_IMPLEMENTED_REASONS` and the dispatch switch; add it to `NOT_DISPATCHED_LEAVES`. Update the meta-test partition assertion. (See Open decision 4.)
5. **Test schema additions.** Add single + list inline-TableField fields to `graphitron-rewrite-test-spec/.../schema.graphqls`, exercising FK joins (single hop, multi-hop), condition joins, nullable vs non-null returns. Extend `init.sql` if needed to populate reachable nested rows.
6. **Execution tests (`GraphQLQueryTest`).** One query per variant: `film.language.name`, `film.actors.firstName`, `country.cities.name` etc. Assert shape and content against seeded data.
7. **Unit tests (`TypeClassGeneratorTest`).** Structural: `$fields` contains a switch arm per GraphQL field name. No body-substring assertions.
8. **Compilation test.** `mvn compile -pl :graphitron-rewrite-test-spec -Plocal-db` passes — real jOOQ catalog catches type errors in emitted multiset/subquery.

### Open decisions

To be resolved during implementation, ideally before the component they gate:

1. **Single-record wrapping (gates deliverable 2).** `multiset + convertFrom` vs a different jOOQ idiom. Spike: render both against PG via `DSL.renderInlined(...)`, compare generated SQL. Pick the one closer to legacy's shape for consistency.
2. **`@condition` / filters on the subquery (gates deliverable 2).** A non-empty `field.filters()` means the WHERE clause inside the multiset gets AND-ed filters from `@condition` methods. Use the existing `buildCallArgs` / `buildArgExtraction` helpers from `TypeFetcherGenerator` — likely factor them out to `GeneratorUtils` if shared.
3. **Pagination in correlated subqueries (gates deliverable 2).** `.limit(n)` works but Relay connection pagination (cursor decode + direction switch) is a much larger emission. G5 supports `.limit` only; `@asConnection` on inline fields is deferred — classify as `UnclassifiedField` if it shows up on an inline TableField until G6 handles the split-connection path.
4. **Meta-test partition expansion (gates deliverable 4).** Today's partition (`IMPLEMENTED_LEAVES` / `NOT_DISPATCHED_LEAVES` / `NOT_IMPLEMENTED_REASONS.keySet()`) doesn't have a slot for "emitted in `TypeClassGenerator.$fields` instead of `TypeFetcherGenerator`". Two options:
   - (a) Add a fourth set `PROJECTED_IN_TYPE_CLASS` on `TypeFetcherGenerator` (with its own meta-test entry). Keeps all four sets disjoint and exhaustive.
   - (b) Move `TableField` into `NOT_DISPATCHED_LEAVES` with a javadoc note ("emitted in `TypeClassGenerator.$fields`, not this class"). Smaller change but conflates two distinct "not here" reasons.

   Prefer (a) for precision. The meta-test plan (`plan-variant-coverage-meta-test.md`) can absorb the fourth set in its next iteration; G5 adds it as a focused delta.
5. **Self-referential / recursive types.** `Film → film (sequel)` pointing back to Film is legal GraphQL. The recursive `$fields` call terminates because the selection set at depth-N cannot include the same field at depth-(N+1) without the client requesting it. No infinite loop at generation time. Document the invariant; no code change needed.

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
