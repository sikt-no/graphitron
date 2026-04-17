# Rewrite Roadmap

This document tracks remaining generator work and design principles for the rewrite pipeline.
For the model taxonomy (types, fields, directives, and what they generate), see [Code Generation Triggers](code-generation-triggers.md).

---

## Design Principles

### Generation-thinking

**Before implementing a generator body, ensure the model carries what the generator needs — pre-resolved, generation-ready.**

The model's job is to be a clean decision boundary. `GraphitronSchemaBuilder` reads directives once and resolves everything: table names, column references, method names, call-site argument extraction strategies, body-generation strategies. Generators receive a model that is already in terms of "what to emit", not "what to interpret".

Signs a model type needs more pre-resolution:
- A generator switches on a raw string (e.g. `"ASC".equalsIgnoreCase(fixed.direction())`)
- A generator contains a multi-arm type switch that recurs across multiple generators (the same switch in 3 places → move the result to the model)
- A generator recomputes a derived name from a field name (e.g. `"load" + capitalize(sf.name())`)
- Generation and calling are conflated in the same model type (e.g. the old `WhereFilter` carrying both column references for body-generation and call expressions for call-site — split them)

**The corollary for tests**: do not assert on generated method bodies. Assert on structural properties (method names, parameter types, return types, which methods exist). Body-content tests are implementation tests that break on every refactor. The correct signal that a body is right is compilation (`graphitron-rewrite-test-spec mvn compile`) and execution against a real database.

### Sealed hierarchies over enums for typed information

When different variants of a concept carry different data, use a sealed interface — not an enum with a shared field set. An enum forces every variant to have the same shape; a sealed record hierarchy gives each variant exactly the fields it needs.

`BatchKey` illustrates the pattern: `RowKeyed` and `RecordKeyed` carry `keyColumns: List<ColumnRef>`, while `ObjectBased` carries `fqClassName: String`. None carry fields they don't use. The compiler enforces exhaustive switches — when a new variant is added, every switch that doesn't handle it becomes a compile error.

### Classification belongs at the parse boundary

`ServiceCatalog.reflectServiceMethod()` and `ServiceCatalog.reflectTableMethod()` are the only places that read the reflection `java.lang.reflect.Type` tree to classify parameters. They convert raw reflection output into `MethodRef.Param` values (each carrying a `ParamSource`). Everything downstream — validator, generator — switches on the pre-classified values and never touches reflection types.

`JooqCatalog`, `TypeBuilder`, `FieldBuilder`, and `ServiceCatalog` are the only classes permitted to hold raw jOOQ types (`Table<?>`, `ForeignKey<?,?>`) or raw graphql-java schema types. If a generator needs information not yet in a taxonomy record, the fix is to add a component and extract the value in the builder — not to reach past the taxonomy boundary.

`CallSiteExtraction` illustrates the principle for argument extraction: the builder decides once (at classify time) which extraction strategy applies to each argument — `Direct`, `EnumValueOf`, `TextMapLookup`, `ContextArg`, or `JooqConvert` — and stores that decision in `CallParam.extraction` or `ParamSource.Arg.extraction`. The generator switches on the pre-classified value and emits code directly.

### Capability interfaces over dispatch chains

When a generation pattern applies across multiple field variants, use an orthogonal capability interface rather than an N-way `instanceof` chain. The interface declares what a field can do; the generator matches on the capability.

Established interfaces:
- `SqlGeneratingField` — `returnType()`, `filters()`, `orderBy()`, `pagination()` (11 variants)
- `MethodBackedField` — `method()` returning `MethodRef` (8 variants)
- `BatchKeyField` — `batchKey()`, `rowsMethodName()` (3 variants, more planned)

### Narrow component types over broad interfaces

Field record components are declared with the narrowest type the classifier can guarantee rather than the broad sealed-interface root. A field whose return type is always table-bound declares `ReturnTypeRef.TableBoundReturnType` directly; a field whose return type is always polymorphic declares `ReturnTypeRef.PolymorphicReturnType` directly.

This pushes classification certainty into the type system: code that receives a `ServiceTableField` knows its `returnType` is `TableBoundReturnType` without a runtime check.

### Sub-taxonomies for resolution outcomes

Complex resolution outcomes get their own sealed type rather than being stored as raw strings. `BatchKey` is a sub-taxonomy of `ParamSource.Sources`, just as `TableRef` is a sub-taxonomy of `GraphitronType.TableBackedType` and `ColumnRef` is a sub-taxonomy of `InputField.ColumnField`. This pattern keeps each concept's complexity local and makes the taxonomy self-documenting: the type of a field tells you exactly what states it can be in.

### Builder-internal sealed hierarchies for multi-target classification

When a builder step classifies inputs into many variants that project into *different* generation-ready outputs, introduce a builder-internal sealed hierarchy. It captures the full classification, enables exhaustive projection into each target, and is discarded before reaching the model.

`ArgumentRef` (see [argument-resolution.md](argument-resolution.md)) classifies every GraphQL argument once into a variant (`ColumnArg`, `OrderByArg`, `PaginationArgRef`, `TableInputArg`, etc.). Separate projection steps then switch on the classified values to produce `GeneratedConditionFilter`, `LookupMapping`, `OrderBySpec`, and `PaginationSpec` — each projection is exhaustive and independent. The alternative — multiple independent passes that implicitly coordinate by skipping each other's arguments (e.g., `buildFilters()` skipping pagination args using the same hardcoded names as `buildPaginationSpec()`) — is fragile and makes adding new argument types error-prone.

The key distinction from model-level sealed hierarchies: builder-internal hierarchies are ephemeral. They exist to structure a complex builder decision, not to carry information to generators. Generators never see `ArgumentRef` — they see the projected results.

### Model metadata over parallel type systems

When the model already carries typed information, runtime data formats should derive from that metadata rather than inventing a parallel type system.

`OrderByResult` pairs `List<SortField<?>>` with `List<Field<?>>` — each cursor column's `DataType` is already known. Cursor encode/decode should use `field.getDataType().convert()` for type-safe round-tripping, and `DSL.noField(field)` for the no-cursor seek case. This eliminates the need for a hand-rolled type-tag system (`i:`, `s:`, `l:`) in the cursor format — the column metadata *is* the type information.

The general principle: when the model has already classified and resolved type information at build time, that same information should drive any runtime format that needs types. A parallel type system in the runtime format is redundant and will diverge.

### Generator Java version vs. generated output Java version

Graphitron is a code generator. The Java version used to build the generator is independent of the Java version of the source it emits.

- **Generator implementation** (everything in `graphitron-rewrite`, `graphitron-java-codegen`, etc.) may freely use Java 21 features — sealed classes, pattern matching, records, switch expressions, text blocks, and so on.
- **Generated source files** must target Java 17. Consumers compile Graphitron's output with their own toolchain, which may be Java 17. Generator authors are responsible for ensuring that any syntax emitted into generated files is valid Java 17 — no switch patterns, no sequenced collections API, nothing that requires 21.

The practical implication: when adding code to a generator, distinguish between code *in* the generator (unrestricted) and code *emitted by* the generator (Java 17).

---

## Remaining Work

### Architecture review priorities (2026-04-17)

From a cross-cutting review comparing the module against its own design
principles. Work below is ordered by expected leverage: higher items change
how every subsequent plan lands.

> **Done since this review was written:** *Sealed-switch the generator
> dispatch* (original P1 #1) landed in commit `3357928`.
> `TypeFetcherGenerator.generateTypeSpec` is now an exhaustive sealed
> `switch` over all 39+ leaf variants, with stubbed leaves routed through
> `stub(f)` backed by the `NOT_IMPLEMENTED_REASONS` map. `ColumnField` on
> a non-table-backed parent is rejected by `GraphitronSchemaValidator`.
> `GeneratorCoverageTest` guards the map against stale entries. The
> remaining priorities below are renumbered accordingly.

**P1 — do first (structural, cheap, compounding):**

1. **Variant-coverage meta-test.** Iterate
   `ChildField.class.getPermittedSubclasses()` (and every sealed root in
   `model/`) and assert every permit has at least one classification test
   case and at least one generator branch (or a documented exception).
   This subsumes *docs-as-index step 5* below and generalises it to the
   whole taxonomy. Cheap to write; catches the `ObjectBased`-shaped gap
   automatically next time. The narrow `GeneratorCoverageTest` sanity
   check shipped with the sealed-switch refactor is a starting point;
   extend it from "map entries are sealed leaves" to "every leaf has a
   classification test case".
2. **Argument-resolution unification.** The `ArgumentRef` classification
   + projection design in [argument-resolution.md](argument-resolution.md)
   is blocking `@condition`-on-fields, `InputColumnBinding`, and every
   future argument category. `FieldBuilder` has grown to ~1350 LOC around
   the three-pass argument model; landing the unified classification is a
   prerequisite for the next six planned features and a prerequisite for
   decomposing `FieldBuilder`.

**P2 — high value, depends on P1 or unblocks adoption:**

3. **Validator asks "can this generate?"** The sealed-switch dispatch is
   in place; `TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS` exposes the
   exact set of stubbed leaves. Extend `GraphitronSchemaValidator` to
   consume that map and reject schemas whose fields would land in a
   stubbed branch, with a targeted error naming the variant. Today a
   validated schema can still crash at request time with
   `UnsupportedOperationException` — which violates the "problems caught
   at build time are far cheaper" principle.
4. **Legacy-vs-rewrite parity matrix.** Document which features each
   generator supports (mutations, split queries, federation, connections,
   interfaces, unions, platformId, …). Without this, users cannot plan
   migration and contributors cannot see where the rewrite is behind.
   Lives naturally in a new `docs/parity-matrix.md` and should be linked
   from the top of this file once it exists.
5. **Cross-plan ownership.** Two types are referenced by multiple plans
   but owned by none:
   - `InputColumnBinding` — required by platformId step 6, owned by
     argument-resolution. Argument-resolution plan must commit a
     definition that accommodates `PlatformIdField`.
   - `BatchKey.ObjectBased` — exists in the sealed hierarchy but has no
     generator path. Decision (collapse into `RecordKeyed` vs implement a
     distinct `selectManyByObjectKeys`) should land before the next
     `ObjectBased`-emitting classifier path is added.

**P3 — medium, quality-of-life:**

6. **Rebalance the test pyramid toward SDL→classified-model→emitted-code
   pipeline tests.** Per-variant structural validator tests (51 classes,
   mostly asserting "this variant produces no errors") dominate the
   current test surface; end-to-end pipeline coverage lives in a single
   `GraphitronSchemaBuilderTest`. Review plans repeatedly defer "pipeline
   tests" to "step N when the fixture lands". New test investment should
   go into the classification→emission chain keyed off the existing
   `graphitron-rewrite-test-fixtures` jOOQ catalog — that's the chain
   users experience.
7. **Decompose `FieldBuilder`.** With argument-resolution unified (P1 #2),
   `FieldBuilder` can be split along the field taxonomy — one builder
   per `RootField` / `ChildField` family — rather than a monolith. Do
   not attempt before P1 #2 lands; the coupling runs through the argument
   passes.

**Backlog — worth fixing but not urgent:**

- **Java-17 output ratchet.** Generator implementation may use Java 21
  (per principle above); emitted source must target Java 17. No
  automated check enforces this — a contributor emitting a Java 21 text
  block would only fail in a consumer build. Add `-source 17 -target 17
  -Werror` on `graphitron-rewrite-test-spec`'s compile step as a ratchet.
- **Cursor format stability.** `paginated-fields.md` uses jOOQ's
  internal `org.jooq.tools.json.JSONValue` (no stability guarantee). Fine
  as a pragmatic choice; not fine as an undocumented production risk. At
  minimum add a note in the paginated-fields doc pointing at a
  replacement plan if jOOQ removes it.
- **Selection parser audit.** `selection/` hand-rolls a Lexer + parser
  (~500 LOC) for GraphQL selection-set syntax that graphql-java already
  parses. Audit whether the runtime path from `DataFetchingEnvironment`
  really requires re-parsing, or whether the AST can be taken from
  graphql-java directly.
- **`GraphitronContext` extension-point documentation.** The interface
  has three methods; all auth / tenancy / audit concerns flow through
  `getDslContext` or live outside the generator's sight. Document the
  boundary: what belongs in a `GraphitronContext` method vs a jOOQ
  `ExecuteListener` vs a schema directive. Relevant to the 30-year
  maintainability goal from `graphitron-principles.md`.

---

### Active

- **Paginated fields** — document transform coexistence (builder fallback loses `defaultPageSize` when `@asConnection` is stripped). See [paginated-fields.md](paginated-fields.md).
- **Argument resolution** — unified classification, `@condition` support, lookup VALUES generation. See [argument-resolution.md](argument-resolution.md).
- **Legacy platformId** (low priority) — classify `id: ID!` input fields that bind to composite platform keys via record-level `getId`/`setId`. See [legacy-platform-id.md](legacy-platform-id.md).

### Stubs to complete

Generator bodies that currently throw `UnsupportedOperationException`, approximate priority:

1. `TypeFetcherGenerator` — `TableField` / `LookupTableField` inline-subquery field methods (G5: inline correlated subquery using `Type.$fields(sel, table, env)` for projection)
2. `TypeFetcherGenerator` — `SplitTableField` / `SplitLookupTableField` rows method bodies (DataLoader batch SQL)
3. `TypeFetcherGenerator` — `QueryTableInterfaceField`, `QueryInterfaceField`, `QueryUnionField` fetchers
4. `TypeFetcherGenerator` — Mutation field bodies (all four DML variants: INSERT/UPDATE/DELETE/UPSERT)

### Deferred: docs-as-index stabilization

Once the sealed type hierarchy is stable (Active work and Stubs completed), finish wiring `code-generation-triggers.md` as a live index into the classification tests. Steps 1 and 2 of the plan are done — every doc table row has a test case, and case descriptions follow a uniform `trigger → VariantName` style. Remaining:

- **Step 3** (re-section) — rename `TypeClassificationCase` → `TableTypeCase`, rename `NonTableParentCase` → `ChildFieldOnRecordParentCase`, and decide Option A (inline rename + section markers) vs Option B (split into one file per doc section). Favour B if this pattern is permanent; A if still experimental.
- **Step 4** (rewire doc) — add spec pointers after each classification table, shrink the *Generator Output* column to 3–5 words, drop SDL snippets that duplicate test cases, add a reading-guide header.
- **Step 5** (meta-test) — add a `ClassificationCase` interface with a `Class<?> variant` field on each enum case, and a `everyClassificationVariantHasAtLeastOneCase` meta-test that collects those fields and asserts every non-allowlisted sealed-type permit is represented. This is strictly more reliable than description-string matching. **Folded under priority P1 #1** above — the meta-test should cover generator branches as well as test cases, and iterate every sealed root in `model/`, not just the two docs-as-index enums.

Full plan: `docs/plan-docs-as-index-into-tests.md`.

### G5 — Inline `TableField`

`TableField` in table-mapped source context (no `@splitQuery`). Extends the SQL scope with an inline subselect — does not start a new scope or use a DataLoader. Introduces the static field method pattern (called from the parent type class during SELECT assembly).

Join paths (`TableField.joinPath()`) may contain both `FkJoin` and `ConditionJoin` steps. The builder fully resolves both (condition-join `MethodRef` reflection is implemented). The generator must handle both: `FkJoin` emits a standard FK-driven JOIN; `ConditionJoin` emits a join whose ON clause is the result of calling `condition.method(srcAlias, tgtAlias)`.

### G6 — Split/Lookup field categories

G6 covers four categories of DataLoader-backed field. Before implementing any category, verify the model is generation-ready.

| Category | DataLoader | Derived tables | `@condition` / non-`@lookupKey` args | Pagination |
|---|---|---|---|---|
| **`LookupQueryField`** (root lookup) | No — synchronous | Derived target only | Blocked (lookup invariant) | Never — result count = M exactly |
| **Table-mapped `LookupTableField`** (`@splitQuery` + `@lookupKey`, table-mapped parent) | No — correlated subquery | Derived target + correlated parent join | Blocked | Never |
| **Result-mapped `TableField`** (`@splitQuery`, no `@lookupKey`) | Yes | Derived source only | Allowed | Allowed |
| **Result-mapped `LookupTableField`** (`@splitQuery` + `@lookupKey`, result-mapped parent) | Yes | Both | Blocked | Never — result count = N × M |

---

## Known Gaps

### `ConditionFilter` has no builder path

`FieldBuilder` currently produces `GeneratedConditionFilter` entries for filterable arguments, but never produces `ConditionFilter` entries for `@condition` directives on fields. Field-level `@condition` annotations — WHERE predicates applied to the field's own target table — are not yet classified into the rewrite pipeline's filter list.

**Note:** This gap is about the `@condition` directive on *fields* (WHERE predicate). It is distinct from condition joins in `@reference` paths — `{condition: {className, method}}` in a `path:` element — which are fully resolved by the builder into `ConditionJoin` steps.

**Fix**: add `@condition` directive reading to `FieldBuilder.resolveFilters()`. `ConditionFilter` now implements `MethodRef` directly, so the builder constructs it with `(className, methodName, params)` and `callParams()` is derived automatically.

### `ObjectBased` batch loading is unimplemented

`BatchKey.ObjectBased` exists in the sealed hierarchy but has no rows-method implementation in `TypeFetcherGenerator` — the service rows method currently throws `UnsupportedOperationException` for all `BatchKey` variants.

Two options:
- **Option A** — collapse `ObjectBased` into `RecordKeyed` if it always implies a jOOQ `TableRecord` parent in practice.
- **Option B** — implement a distinct `selectManyByObjectKeys` / `selectOneByObjectKeys` path in `TypeFetcherGenerator`.

Decision needed before implementing any `ObjectBased`-keyed service field. Tracked as a cross-plan ownership item under priority P2 #5 above: this roadmap owns the decision; plans that want to emit `ObjectBased` cannot land until it is made.

### Validator cannot detect generator stubs

`GraphitronSchemaValidator` reports classification errors (`UnclassifiedType`, `UnclassifiedField`, structural-invariant failures) but has no visibility into which sealed-variant branches of `TypeFetcherGenerator` are implemented. A schema that validates successfully can still emit a Fetchers class whose methods throw `UnsupportedOperationException` at request time.

Tied to priority P2 #3: sealed-switch dispatch and `NOT_IMPLEMENTED_REASONS` now exist in `TypeFetcherGenerator` (completed under the original P1 #1); the validator needs a single new arm consuming `NOT_IMPLEMENTED_REASONS.keySet()` to reject schemas that would land in a stubbed branch, turning a runtime failure into a build-time error. This closes the loop on the "problems caught at build time" principle from `graphitron-principles.md`.

### Legacy-vs-rewrite parity is undocumented

The rewrite is a clean reimplementation with no shared code paths, but no doc states which schema features work in each generator. Mutations are entirely stubbed in the rewrite; legacy supports them. Users cannot plan migration and contributors cannot see where the rewrite is behind.

Tracked under priority P2 #4 above. Should live in a new `docs/parity-matrix.md` enumerating every directive and every field-variant family, per-generator, with a one-line status (✅ supported / ⏳ stubbed / ❌ no classifier). Linked from the top of this file once it exists.

---

## Implementation Guidance

No DTOs, no TypeMappers. DataFetchers return `Result<Record>`; GraphQL-Java traverses the records using the registered field DataFetchers.

**Exception:** Connection fields return `ConnectionResult` — a generated carrier wrapping `Result<Record>` + pagination context. See [paginated-fields.md](paginated-fields.md).

### Selection-aware queries and multiset

`DataFetchingFieldSelectionSet` and `SelectedField` are already threaded through all table method signatures, structurally committing to selection-aware queries. When the table method bodies are implemented:

- **Top-level**: call `Type.$fields(sel, table, env)` for the column list, then `dsl.select(fields).from(table)...`
- **Inline nesting**: use jOOQ `multiset(select(columns).from(CHILD).where(...)).as("alias")`, returned as `Field<?>` (type-erased). Use type erasure at every helper method boundary — jOOQ's generic types compound badly with nesting depth, causing slow compile times.
- **`@splitQuery`**: separate DataLoader; parent fetches the FK/PK columns, child batches by those keys.

### Query plan caching trade-off

Selection-driven queries produce different SQL per request (different column lists). The database cannot reuse cached query plans across requests. This is an acceptable cost for wide tables with large optional columns, but for narrow tables (≤10 columns) where most fields are always requested, selecting `TABLE.*` is simpler and the overhead of dynamic column selection exceeds the benefit.

### Error quality

`BuildContext` implements `candidateHint(attempt, candidates)` using Levenshtein distance to sort candidates by similarity. Used in 14 places (5 in `FieldBuilder`, 5 in `TypeBuilder`, 2 in `BuildContext`, 2 in `ServiceCatalog`). When adding new jOOQ existence checks in the validator or builder, follow the same pattern — pass the relevant candidate list from `JooqCatalog` to `candidateHint`.
