# Rewrite Roadmap

This document tracks remaining generator work and design principles for the rewrite pipeline.
For the model taxonomy (types, fields, directives, and what they generate), see [Code Generation Triggers](../code-generation-triggers.md).

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

## Active

Items with a plan in Draft, Approved, In Progress, or Pending Review. First line: state marker + plan link. Description below.

### Variant-coverage meta-test **[Approved]** — [plan-variant-coverage-meta-test.md](plan-variant-coverage-meta-test.md)

Iterate every sealed root in `model/` and assert every permit has at least one classification test case and one generator branch (or a documented allowlist entry). Phase 1 (generator-branch partition) shipped; Phase 2 (classification-case coverage) pending; Phase 3 (narrow-component coverage) deferred. Structural, cheap, compounding.

### Argument-resolution unification **[Approved]** — [argument-resolution.md](argument-resolution.md)

Unified `ArgumentRef` classification + projection in the builder; gates `@condition`-on-fields, `InputColumnBinding`, and every future argument category. `FieldBuilder` is ~1350 LOC around the three-pass argument model. Phase 1 (classification + projection) shipped; Phase 2 (generator-side migration) is next and gates six downstream features including the `FieldBuilder` decomposition.

### Stubbed-variant validator **[Pending Review]** — [plan-stubbed-variant-validator.md](plan-stubbed-variant-validator.md)

`GraphitronSchemaValidator` rejects schemas whose classification lands on a `NOT_IMPLEMENTED_REASONS` variant; `ValidateMojo` fails the build on rewrite validation errors by default. Implemented; awaiting independent review.

### G5 — Inline `TableField` emission **[Approved]** — [plan-g5-inline-tablefield.md](plan-g5-inline-tablefield.md)

Emission stub throws `UnsupportedOperationException` for every `ChildField.TableField`. Classification complete; emission lives in `TypeClassGenerator.$fields` as a correlated subquery, not in `TypeFetcherGenerator`. Gating prerequisite for argres Phase 2a (the lookup variant layers VALUES+JOIN onto this shape). Execution-test prerequisite: document the lookup-condition method signature (classification-vocabulary item 5).

### `IdReferenceField` input filter variant **[Draft]** — [plan-id-reference-input-field.md](plan-id-reference-input-field.md)

`[ID!] @reference(path: ...)` filter inputs currently mis-classify as `UnclassifiedType` because the `@field(name:)` value is a method-accessor suffix (`hasTerminIds`), not a column. Add a new `InputField.IdReferenceField` permit classified via directive detection.

### Classification vocabulary follow-ups **[Draft]** — [plan-classification-vocabulary-followups.md](plan-classification-vocabulary-followups.md)

Five independent doc/generator-behaviour cleanups around the "source context vs. target type" split. None is a release blocker; they can land in any order. Item 2 (build warning channel) is reusable by the `@table`+`@record` bug fix and by the stubbed-variant validator's "warn instead of fail" configurations. Item 5 (lookup-condition method signature docs + execution test) gates G5/G6 execution tests.

### `@table` + `@record` input type validation bug **[Draft]** — [bug-record-input-table-validation.md](bug-record-input-table-validation.md)

Classifier fails on `@table` + `@record` combined on an input type — legacy tolerates. Fix treats `@record` as authoritative, logs a warning naming `@table` as shadowed. Introduces the shared build-warnings channel also consumed by classification-vocabulary item 2.

### Docs-as-index stabilization **[Approved — deferred]** — [plan-docs-as-index-into-tests.md](plan-docs-as-index-into-tests.md)

Steps 1-2 shipped on `claude/review-docs-plan-adYJW`. Step 5 superseded by `plan-variant-coverage-meta-test.md`. Steps 3-4 (re-section renames + doc rewire) deferred until the sealed hierarchy stabilises; picking them up mid-churn means constant rework.

### Legacy platformId **[Draft]** — [legacy-platform-id.md](legacy-platform-id.md)

Classify `id: ID!` fields that bind to composite platform keys emitted by `KjerneJooqGenerator` (`get*Id()` / `set*Id()` accessors, no real SQL column). Items 1-2 shipped: output and input classification, dispatch registration, and end-to-end pipeline tests backed by a synthetic `platformidfixture` jOOQ catalog that adds platform-id accessors to a hand-written table/record pair. Item 3 (mutation generator binding via `InputColumnBinding`) is blocked on argres Phase 3 (`TableInputArg.fieldBindings` population). Item 4 (argument/filter condition emission via `table.hasId`/`hasIds`) was added 2026-04-18 after a review surfaced the gap; it is independently schedulable and drives a `LookupMapping` sum-type refactor.

## Backlog

Unplanned items. Pick one, draft a plan, then move to Active.

**Priority (architecture review, 2026-04-17):**

- **Legacy-vs-rewrite parity matrix** **[Unplanned]** — document which features each generator supports (mutations, split queries, federation, connections, interfaces, unions, platformId). Lives in a new `docs/parity-matrix.md`; link from the top of this file when it exists.
- **`BatchKey.ObjectBased` generator path decision** **[Unplanned]** — `ObjectBased` exists in the sealed hierarchy but no generator emits for it. Decide: collapse into `RecordKeyed` (A) vs implement a distinct `selectManyByObjectKeys` (B). Blocks any future `ObjectBased`-emitting classifier.
- **Rebalance test pyramid toward SDL→classified-model→emitted-code pipeline tests** **[Unplanned]** — per-variant structural validator tests (53 classes) dominate the surface; pipeline coverage lives in a single `GraphitronSchemaBuilderTest`. New test investment should go into the classification→emission chain keyed off `graphitron-rewrite-test-fixtures`.
- **Decompose `FieldBuilder`** **[Unplanned]** — split along the field taxonomy after argument-resolution Phase 2 lands. Blocked on Argument-resolution unification (Active).

**Generator stubs to complete:**

Enumerated by `TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS`; the stubbed-variant validator (Active, Pending Review) fails the build when a schema lands on one. Approximate priority:

1. `TypeFetcherGenerator` — `TableField` / `LookupTableField` inline-subquery methods (tracked by G5 in Active + argres Phase 2a).
2. `TypeFetcherGenerator` — `SplitTableField` / `SplitLookupTableField` rows-method bodies (DataLoader batch SQL; argres Phase 2b).
3. `TypeFetcherGenerator` — `QueryTableInterfaceField`, `QueryInterfaceField`, `QueryUnionField` fetchers.
4. `TypeFetcherGenerator` — Mutation bodies (INSERT / UPDATE / DELETE / UPSERT).

**Miscellaneous:**

- **Paginated-fields transform coexistence** **[Unplanned]** — builder fallback loses `defaultPageSize` when `@asConnection` is stripped. See [../paginated-fields.md](../paginated-fields.md).
- **Java-17 output ratchet** **[Unplanned]** — `-source 17 -target 17 -Werror` on `graphitron-rewrite-test-spec` so Java 21 syntax emitted into generated files fails here, not in consumers' builds.
- **Cursor format stability** **[Unplanned]** — `paginated-fields.md` uses jOOQ's internal `org.jooq.tools.json.JSONValue`. Document the risk and a replacement path if jOOQ removes it.
- **Selection parser audit** **[Unplanned]** — `selection/` hand-rolls ~500 LOC that graphql-java already parses. Audit whether the runtime path really needs re-parsing.
- **`GraphitronContext` extension-point docs** **[Unplanned]** — document what belongs in a `GraphitronContext` method vs a jOOQ `ExecuteListener` vs a schema directive.
- **Drop `graphitron-common` build dependency from `graphitron-rewrite`** **[Unplanned]** — one production import + one test-classpath resource. Inline `MultiSourceReader` + auto-inject `directives.graphqls` from the module's own classpath. Emitted code's *runtime* dependency on `graphitron-common` is unchanged.

## Done milestones

Short history — landings worth remembering when picking up related work.

- `3357928` — Sealed-switch generator dispatch. `TypeFetcherGenerator.generateTypeSpec` became an exhaustive sealed `switch` over all `GraphitronField` leaves; stubbed leaves route through `stub(f)` backed by `NOT_IMPLEMENTED_REASONS`.
- `15f9f61e` — Variant-coverage Phase 1. `IMPLEMENTED_LEAVES` + `NOT_DISPATCHED_LEAVES` sibling sets; partition invariant enforced by `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus`.
- `1e48c4ee` — Argument-resolution Phase 1. VALUES + JOIN lookup emission for `QueryLookupTableField`.

---

## Reference: G6 split/lookup field categories

G6 covers four categories of DataLoader-backed field. Before implementing any category, verify the model is generation-ready. Execution-test prerequisite same as G5 above.

| Category | DataLoader | Derived tables | `@condition` / non-`@lookupKey` args | Pagination |
|---|---|---|---|---|
| **`LookupQueryField`** (root lookup) — now live via argres Phase 1 | No — synchronous | Derived target only | Blocked (lookup invariant) | Never — result count = M exactly |
| **Table-mapped `LookupTableField`** (`@splitQuery` + `@lookupKey`, table-mapped parent) | No — correlated subquery | Derived target + correlated parent join | Blocked | Never |
| **Result-mapped `TableField`** (`@splitQuery`, no `@lookupKey`) | Yes | Derived source only | Allowed | Allowed |
| **Result-mapped `LookupTableField`** (`@splitQuery` + `@lookupKey`, result-mapped parent) — aspirational row | Yes | Both | Blocked | Never — result count = N × M |

**Gap in the last row.** The sealed record `ChildField.RecordLookupTableField` today has no `BatchKey` field (unlike `SplitLookupTableField`), so the table's "DataLoader: Yes" entry is aspirational. Before implementing this category, resolve: (a) add `BatchKey` to `RecordLookupTableField`, (b) batch via a different mechanism derived from the parent result, or (c) declare `RecordLookupTableField` synchronous and move this row out of the DataLoader table. See [argres Phase 2c](argument-resolution.md#phase-2--child-field-lookup-generators-g5g6).

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

Decision needed before implementing any `ObjectBased`-keyed service field. Tracked as a Backlog item (`BatchKey.ObjectBased` generator path decision); this roadmap owns the decision, plans that want to emit `ObjectBased` cannot land until it is made.

### Validator cannot detect generator stubs

`GraphitronSchemaValidator` reports classification errors (`UnclassifiedType`, `UnclassifiedField`, structural-invariant failures) but has no visibility into which sealed-variant branches of `TypeFetcherGenerator` are implemented. A schema that validates successfully can still emit a Fetchers class whose methods throw `UnsupportedOperationException` at request time.

Addressed by the Stubbed-variant validator plan (Active, Pending Review). The validator consumes `NOT_IMPLEMENTED_REASONS.keySet()` to reject stubbed-branch schemas at build time; `ValidateMojo` fails the build by default. This closes the loop on the "problems caught at build time" principle from `graphitron-principles.md`.

### Legacy-vs-rewrite parity is undocumented

The rewrite is a clean reimplementation with no shared code paths, but no doc states which schema features work in each generator. Mutations are entirely stubbed in the rewrite; legacy supports them. Users cannot plan migration and contributors cannot see where the rewrite is behind.

Tracked as the Legacy-vs-rewrite parity matrix Backlog item. Should live in a new `docs/parity-matrix.md` enumerating every directive and every field-variant family, per-generator, with a one-line status (✅ supported / ⏳ stubbed / ❌ no classifier). Linked from the top of this file once it exists.

---

## Implementation Guidance

No DTOs, no TypeMappers. DataFetchers return `Result<Record>`; GraphQL-Java traverses the records using the registered field DataFetchers.

**Exception:** Connection fields return `ConnectionResult` — a generated carrier wrapping `Result<Record>` + pagination context. See [paginated-fields.md](../paginated-fields.md).

### Selection-aware queries and multiset

`DataFetchingFieldSelectionSet` and `SelectedField` are already threaded through all table method signatures, structurally committing to selection-aware queries. When the table method bodies are implemented:

- **Top-level**: call `Type.$fields(sel, table, env)` for the column list, then `dsl.select(fields).from(table)...`
- **Inline nesting**: use jOOQ `multiset(select(columns).from(CHILD).where(...)).as("alias")`, returned as `Field<?>` (type-erased). Use type erasure at every helper method boundary — jOOQ's generic types compound badly with nesting depth, causing slow compile times.
- **`@splitQuery`**: separate DataLoader; parent fetches the FK/PK columns, child batches by those keys.

### Query plan caching trade-off

Selection-driven queries produce different SQL per request (different column lists). The database cannot reuse cached query plans across requests. This is an acceptable cost for wide tables with large optional columns, but for narrow tables (≤10 columns) where most fields are always requested, selecting `TABLE.*` is simpler and the overhead of dynamic column selection exceeds the benefit.

### Error quality

`BuildContext` implements `candidateHint(attempt, candidates)` using Levenshtein distance to sort candidates by similarity. Used in 14 places (5 in `FieldBuilder`, 5 in `TypeBuilder`, 2 in `BuildContext`, 2 in `ServiceCatalog`). When adding new jOOQ existence checks in the validator or builder, follow the same pattern — pass the relevant candidate list from `JooqCatalog` to `candidateHint`.
