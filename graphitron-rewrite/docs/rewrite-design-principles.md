# Rewrite Design Principles

Technical and architectural principles that govern the rewrite pipeline. For Graphitron's strategic/philosophical principles, see [graphitron-principles.md](../../docs/graphitron-principles.md).

---

## Generation-thinking

**Before implementing a generator body, ensure the model carries what the generator needs — pre-resolved, generation-ready.** `GraphitronSchemaBuilder` reads directives once and resolves everything: table names, column references, method names, extraction strategies. Generators receive a model in terms of "what to emit", not "what to interpret".

Signs a model type needs more pre-resolution:
- A generator switches on a raw string, or recomputes a derived name from a field name.
- The same multi-arm type switch recurs across multiple generators.
- Generation and calling are conflated in the same model type.
- A generator branches on a predicate over pre-resolved data (e.g. which side of a join holds the FK). The decision was not resolved, only its inputs were — lift the fork into the model as a sealed sub-variant. Rule of thumb: if two generators branch on the same predicate over a model field, the branch belongs in the model.

## Sealed hierarchies over enums for typed information

When different variants of a concept carry different data, use a sealed interface — not an enum with a shared field set. An enum forces every variant to have the same shape; a sealed record hierarchy gives each variant exactly the fields it needs.

`BatchKey` illustrates the pattern: `RowKeyed` and `RecordKeyed` carry `keyColumns: List<ColumnRef>`, while `ObjectBased` carries `fqClassName: String`. None carry fields they don't use. The compiler enforces exhaustive switches — when a new variant is added, every switch that doesn't handle it becomes a compile error.

## Classification belongs at the parse boundary

`ServiceCatalog.reflectServiceMethod()` and `ServiceCatalog.reflectTableMethod()` are the only places that read the reflection `java.lang.reflect.Type` tree to classify parameters. They convert raw reflection output into `MethodRef.Param` values (each carrying a `ParamSource`). Everything downstream — validator, generator — switches on the pre-classified values and never touches reflection types.

`JooqCatalog`, `TypeBuilder`, `FieldBuilder`, and `ServiceCatalog` are the only classes permitted to hold raw jOOQ types (`Table<?>`, `ForeignKey<?,?>`) or raw graphql-java schema types. If a generator needs information not yet in a taxonomy record, the fix is to add a component and extract the value in the builder — not to reach past the taxonomy boundary.

`CallSiteExtraction` illustrates the principle for argument extraction: the builder decides once (at classify time) which extraction strategy applies to each argument — `Direct`, `EnumValueOf`, `TextMapLookup`, `ContextArg`, or `JooqConvert` — and stores that decision in `CallParam.extraction` or `ParamSource.Arg.extraction`. The generator switches on the pre-classified value and emits code directly.

## Capability interfaces and sealed switches serve different roles

When a generation pattern applies uniformly across multiple field variants, use an orthogonal capability interface rather than an N-way `instanceof` chain. Established interfaces: `SqlGeneratingField`, `MethodBackedField`, `BatchKeyField`.

Capabilities express what is *uniformly true* across variants; sealed switches express what *varies by identity*. Use a capability when the generator treats variants identically (iterate `SqlGeneratingField.filters()` regardless of leaf type). Use a sealed switch when the generator forks on identity (which `$fields` arm to emit, which rows-method signature to synthesise). Capabilities don't eliminate exhaustiveness bookkeeping — they relocate it.

## Narrow component types over broad interfaces

Field record components are declared with the narrowest type the classifier can guarantee rather than the broad sealed-interface root. A field whose return type is always table-bound declares `ReturnTypeRef.TableBoundReturnType` directly; a field whose return type is always polymorphic declares `ReturnTypeRef.PolymorphicReturnType` directly.

This pushes classification certainty into the type system: code that receives a `ServiceTableField` knows its `returnType` is `TableBoundReturnType` without a runtime check.

## Sub-taxonomies for resolution outcomes

Complex resolution outcomes get their own sealed type rather than being stored as raw strings. `BatchKey` is a sub-taxonomy of `ParamSource.Sources`, `TableRef` of `GraphitronType.TableBackedType`, `ColumnRef` of `InputField.ColumnField`. The type of a field tells you exactly what states it can be in.

Each new sub-taxonomy proposal comes with a one-line note on what distinct information it carries that a sibling cannot — otherwise it's probably a field on an existing record. At milestone boundaries, audit which sub-taxonomies could collapse now that their forcing functions are visible.

## Builder-internal sealed hierarchies for multi-target classification

When a builder step classifies inputs into many variants that project into *different* generation-ready outputs, introduce a builder-internal sealed hierarchy. It captures the full classification, enables exhaustive projection into each target, and is discarded before reaching the model.

`ArgumentRef` (see [argument-resolution.md](argument-resolution.md)) classifies every GraphQL argument once into a variant (`ColumnArg`, `OrderByArg`, `PaginationArgRef`, `TableInputArg`, etc.). Separate projection steps then switch on the classified values to produce `GeneratedConditionFilter`, `LookupMapping`, `OrderBySpec`, and `PaginationSpec` — each projection is exhaustive and independent. The alternative — multiple independent passes that implicitly coordinate by skipping each other's arguments (e.g., `buildFilters()` skipping pagination args using the same hardcoded names as `buildPaginationSpec()`) — is fragile and makes adding new argument types error-prone.

The key distinction from model-level sealed hierarchies: builder-internal hierarchies are ephemeral. They exist to structure a complex builder decision, not to carry information to generators. Generators never see `ArgumentRef` — they see the projected results.

## Model metadata over parallel type systems

When the model already carries typed information, runtime data formats should derive from that metadata rather than inventing a parallel type system.

`OrderByResult` pairs `List<SortField<?>>` with `List<Field<?>>` — each cursor column's `DataType` is already known. Cursor encode/decode should use `field.getDataType().convert()` for type-safe round-tripping, and `DSL.noField(field)` for the no-cursor seek case. This eliminates the need for a hand-rolled type-tag system (`i:`, `s:`, `l:`) in the cursor format — the column metadata *is* the type information.

The general principle: when the model has already classified and resolved type information at build time, that same information should drive any runtime format that needs types. A parallel type system in the runtime format is redundant and will diverge.

## Validator mirrors classifier invariants

Every classifier decision that implies a generator branch must fail at validate time if that branch is unimplemented. The validator reads the same sets the dispatcher does (`NOT_IMPLEMENTED_REASONS.keySet()` today; the successor status-map when the four-set partition collapses) so an unsupported classification surfaces as a build-time error rather than a runtime `UnsupportedOperationException`. This closes the gap between "the schema classifies cleanly" and "the emitter has an arm for this leaf". `ValidateMojo` consumes the stubbed-variant set and fails the build by default.

The rule extends beyond stubbed variants: when a classifier introduces a new invariant (e.g. "`@asConnection` not allowed on inline `TableField`"), the validator should reject it by the same mechanism the generator relies on — no generator-side invariant goes unchecked at validate time. This keeps "problems caught at build time" honest and the generator's builder-invariant assumptions emitter-side safe.

## Classifier guarantees shape emitter assumptions

The rule above flows in one direction: a classifier rejection becomes a build-time error via the validator. The reverse direction also matters. A classifier acceptance can let an emitter assume narrower shapes, so the emitted code reads as tight as if it were hand-written: no defensive casts, no wildcard locals, no `instanceof` guards. When that pattern lands, the classifier check becomes load-bearing for the emitter's correctness; relaxing the check breaks the generated source.

This is a feature, not a fragility. Compile failure of the emitted source at `mvn compile -pl :graphitron-rewrite-test` is the safety net: any classifier/emitter mismatch surfaces during the build, before any code reaches a consumer. Compared with defensive runtime casts (which can throw `ClassCastException` on a real request, days after the build passed) or `var`-typed locals fed into parameterised entry points (which abandon the strict-shape guarantee entirely), the load-bearing-guarantee shape is the safest expression of the contract.

Two instances on trunk today:

- **`@tableMethod` root fetcher.** `ServiceCatalog.reflectTableMethod` rejects developer methods whose return type is wider than the generated jOOQ table class via a strict `ClassName.equals` comparison. `TypeFetcherGenerator.buildQueryTableMethodFetcher` then declares `<SpecificTable> table = Method.x(...)` with no cast, and feeds the local directly into `<SpecificTable>Type.$fields(...)` which expects exactly that type. The companion strict-`@service` return-type check in `reflectServiceMethod` (against `FieldBuilder.computeExpectedServiceReturnType`) follows the same pattern: typed `Result<FilmRecord>` fetcher returns instead of `Object`, structural `TypeName.equals` comparison.
- **`ColumnField` parent table.** The classifier produces a `ColumnField` only on a table-backed parent. `TypeFetcherGenerator`'s switch arm throws `IllegalStateException` when it sees a `ColumnField` with `parentTable == null`, treating that reachability as a classifier-invariant violation rather than emitting a defensive null-check.

Rule: if you relax a classifier check that an emitter relies on, audit every emitter site that consumes the corresponding shape, in the same commit. The compile-time failure of the generated `*Fetchers` source is the safety net; the audit is the cheap upstream version of the same signal.

## Pipeline tests are the primary behavioural tier

Behaviour is asserted at the SDL → classified model → generated `TypeSpec` pipeline layer — not at the per-variant unit tier. Per-variant structural tests (method names, return types, which methods exist) are bookkeeping; the primary signal that a feature works is that a realistic SDL produces a realistic `TypeSpec` end-to-end through the classifier. New features earn a pipeline test first; unit tests cover structural invariants that pipeline coverage would make repetitive.

Complementary tiers layered above: compilation of `graphitron-rewrite-test` against real jOOQ classes (type correctness); execution of the generated code against real PostgreSQL (behaviour correctness). Code-string assertions on generated method bodies are banned at every tier — they test implementation, not behaviour, and break on every refactor.

## Documentation names only live tests/code

Javadoc, plan prose, and README references that name a test, method, or class must name one that exists today. A javadoc comment saying "enforced by `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus`" when that method does not exist is worse than no comment — it's a false invariant that readers trust. Reviewers check this explicitly during Draft → Approved and Pending Review → Done transitions. When a plan's wording anticipates a method, class, or test that the same plan will create, phrase it as "C3 adds `X`" rather than "as asserted by `X`".

## Compilation against real jOOQ is a test tier

`mvn compile -pl :graphitron-rewrite-test -Plocal-db` against a real jOOQ catalog is the primary check that generated emission is type-correct. Unit tests assert structure; pipeline tests assert SDL → TypeSpec shape; compilation catches "the `Field<Record4<Int,Str,Int,Str>>` parameter doesn't line up with the emitted DSL call" without a hand-written assertion. Every generator change must pass `-Plocal-db` compile before merging.

The complementary tier above it — execution against a real PostgreSQL via the same fixture database — is the behaviour check. Together, compile + execute replace the body-content assertions that the "generation-thinking" principle bans.

## Generator Java version vs. generated output Java version

Graphitron is a code generator. The Java version used to build the generator is independent of the Java version of the source it emits.

- **Generator implementation** (everything in `graphitron-rewrite`, `graphitron-java-codegen`, etc.) may freely use Java 21 features — sealed classes, pattern matching, records, switch expressions, text blocks, and so on.
- **Generated source files** must target Java 17. Consumers compile Graphitron's output with their own toolchain, which may be Java 17. Generator authors are responsible for ensuring that any syntax emitted into generated files is valid Java 17 — no switch patterns, no sequenced collections API, nothing that requires 21.

The practical implication: when adding code to a generator, distinguish between code *in* the generator (unrestricted) and code *emitted by* the generator (Java 17).

## Rewrite builds independently of legacy Graphitron modules

`graphitron-rewrite/pom.xml` is a self-contained Maven aggregator. `mvn install -f graphitron-rewrite/pom.xml` on a clean local repo builds the five rewrite modules (`graphitron-rewrite-javapoet`, `graphitron-rewrite`, `graphitron-rewrite-fixtures`, `graphitron-rewrite-maven`, `graphitron-rewrite-test`) without resolving any legacy artifact (`graphitron-common`, `graphitron-java-codegen`, `graphitron-maven-plugin`, `graphitron-schema-transform`, or the legacy `graphitron-javapoet` coord).

The invariant is enforced by `graphitron-rewrite/scripts/verify-standalone-build.sh`, which runs the aggregator against a fresh empty local repo and greps the resulting repo for forbidden legacy coords. Any future change that pulls a legacy dep back into the rewrite tree fails this check.

Rationale: rewrite is the successor; consumers migrating off the legacy generator should be able to depend on the rewrite aggregator alone. The rewrite-owned Maven plugin (`graphitron-rewrite-maven`) is the entry point; the legacy plugin and schema-transform module remain available for consumers who haven't migrated yet, but rewrite code does not import from them.

---

## Emitter Conventions

### Return types

DataFetchers return `Result<Record>` — no DTOs, no TypeMappers. GraphQL-Java traverses records using the registered field DataFetchers. Exception: Connection fields return `ConnectionResult`, a generated carrier wrapping `Result<Record>` + pagination context.

### Selection-aware queries

`DataFetchingFieldSelectionSet` and `SelectedField` are threaded through all table method signatures, structurally committing to selection-aware queries:

- **Top-level queries**: call `Type.$fields(sel, table, env)` for the column list, then `dsl.select(fields).from(table)...`
- **Inline nesting**: use jOOQ `multiset(select(columns).from(CHILD).where(...)).as("alias")` returning `Field<?>` (type-erased). Use type erasure at every helper method boundary — jOOQ generic types compound badly with nesting depth, causing slow compile times.
- **`@splitQuery`**: separate DataLoader; parent fetches FK/PK columns, child batches by those keys.

Selection-driven queries produce different SQL per request, preventing cached query-plan reuse. This is an acceptable trade-off for wide tables with large optional columns; for narrow tables (≤ 10 columns) where most fields are always requested, `TABLE.*` is simpler and the dynamic-column overhead exceeds the benefit.

### Error quality

`BuildContext.candidateHint(attempt, candidates)` sorts candidates by Levenshtein distance. Used in 14 places (5 in `FieldBuilder`, 5 in `TypeBuilder`, 2 in `BuildContext`, 2 in `ServiceCatalog`). When adding new jOOQ existence checks in the validator or builder, follow the same pattern — pass the relevant candidate list from `JooqCatalog` to `candidateHint`.

### Helper-locality

Emitted helper methods that bind column references to a specific aliased jOOQ `Table` instance always take the `Table` as a parameter — never declare it locally. Callers from different paths (root fetcher, inline subquery, Split-rows method) need to pass distinct aliases for the same target table; a locally-declared `Table` forces the wrong alias on every caller but the one the helper was first written for.

Pattern (canonical example, `<fieldName>OrderBy` emitted by `TypeFetcherGenerator.buildOrderByHelperMethod`):

```java
private static OrderByResult <fieldName>OrderBy(DataFetchingEnvironment env, <Table> table) { ... }
```

Each call site supplies the alias appropriate to its scope: the root fetcher passes its declared `<entity>Table`; a Split-rows method passes its FK-chain terminal alias; an inline subquery passes its correlated alias. The helper's column references resolve through the parameter.

Compliant emitters (audit 2026-04-25): `TypeFetcherGenerator.buildOrderByHelperMethod`, `QueryConditionsGenerator`, `LookupValuesJoinEmitter` (root + child forms), `InlineLookupTableFieldEmitter`. `ConnectionHelperClassGenerator.pageRequest` / `encodeCursor` / `decodeCursor` are not Table-bound (operate on `Field<?>` / `SortField<?>`) and the rule does not apply.
