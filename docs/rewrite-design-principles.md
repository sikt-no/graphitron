# Rewrite Design Principles

Technical and architectural principles that govern the rewrite pipeline. For Graphitron's strategic/philosophical principles, see [graphitron-principles.md](graphitron-principles.md).

---

## Generation-thinking

**Before implementing a generator body, ensure the model carries what the generator needs — pre-resolved, generation-ready.**

The model's job is to be a clean decision boundary. `GraphitronSchemaBuilder` reads directives once and resolves everything: table names, column references, method names, call-site argument extraction strategies, body-generation strategies. Generators receive a model that is already in terms of "what to emit", not "what to interpret".

Signs a model type needs more pre-resolution:
- A generator switches on a raw string (e.g. `"ASC".equalsIgnoreCase(fixed.direction())`)
- A generator contains a multi-arm type switch that recurs across multiple generators (the same switch in 3 places → move the result to the model)
- A generator recomputes a derived name from a field name (e.g. `"load" + capitalize(sf.name())`)
- Generation and calling are conflated in the same model type (e.g. the old `WhereFilter` carrying both column references for body-generation and call expressions for call-site — split them)
- A generator branches on a predicate over pre-resolved data (e.g. `first.sourceTable().equals(parentTable)` to pick FK direction). The decision was not resolved, only its inputs were.

**Corollary — pre-resolve decisions, not just inputs.** Pre-resolving the data a decision reads (e.g. the FK columns on both sides of a join) is not the same as pre-resolving the decision itself (which side holds the FK). When G5's `FkJoin` enriches itself with `sourceColumns` and `targetColumns`, the emitter still branches on direction. Stronger form: lift the fork into the model as a sealed sub-variant — `CorrelationShape.ChildHoldsFk(ours, theirs)` / `ParentHoldsFk(ours, theirs)` — resolved once in the builder. The generator switches, never infers. Rule of thumb: if two generators branch on the same predicate over a model field, the branch belongs in the model.

**The corollary for tests**: do not assert on generated method bodies. Assert on structural properties (method names, parameter types, return types, which methods exist). Body-content tests are implementation tests that break on every refactor. The correct signal that a body is right is compilation (`graphitron-rewrite-test-spec mvn compile`) and execution against a real database.

## Sealed hierarchies over enums for typed information

When different variants of a concept carry different data, use a sealed interface — not an enum with a shared field set. An enum forces every variant to have the same shape; a sealed record hierarchy gives each variant exactly the fields it needs.

`BatchKey` illustrates the pattern: `RowKeyed` and `RecordKeyed` carry `keyColumns: List<ColumnRef>`, while `ObjectBased` carries `fqClassName: String`. None carry fields they don't use. The compiler enforces exhaustive switches — when a new variant is added, every switch that doesn't handle it becomes a compile error.

## Classification belongs at the parse boundary

`ServiceCatalog.reflectServiceMethod()` and `ServiceCatalog.reflectTableMethod()` are the only places that read the reflection `java.lang.reflect.Type` tree to classify parameters. They convert raw reflection output into `MethodRef.Param` values (each carrying a `ParamSource`). Everything downstream — validator, generator — switches on the pre-classified values and never touches reflection types.

`JooqCatalog`, `TypeBuilder`, `FieldBuilder`, and `ServiceCatalog` are the only classes permitted to hold raw jOOQ types (`Table<?>`, `ForeignKey<?,?>`) or raw graphql-java schema types. If a generator needs information not yet in a taxonomy record, the fix is to add a component and extract the value in the builder — not to reach past the taxonomy boundary.

`CallSiteExtraction` illustrates the principle for argument extraction: the builder decides once (at classify time) which extraction strategy applies to each argument — `Direct`, `EnumValueOf`, `TextMapLookup`, `ContextArg`, or `JooqConvert` — and stores that decision in `CallParam.extraction` or `ParamSource.Arg.extraction`. The generator switches on the pre-classified value and emits code directly.

## Capability interfaces and sealed switches serve different roles

When a generation pattern applies uniformly across multiple field variants, use an orthogonal capability interface rather than an N-way `instanceof` chain. The interface declares what a field can do; the generator matches on the capability.

Established interfaces:
- `SqlGeneratingField` — `returnType()`, `filters()`, `orderBy()`, `pagination()` (11 variants)
- `MethodBackedField` — `method()` returning `MethodRef` (8 variants)
- `BatchKeyField` — `batchKey()`, `rowsMethodName()` (3 variants, more planned)

**Capabilities do not eliminate exhaustiveness bookkeeping — they relocate it.** A capability expresses what is *uniformly true* across variants; a sealed switch expresses what *varies by variant identity*. Both patterns belong, neither replaces the other. Heuristic: use a capability when the generator treats the variants identically (iterate `SqlGeneratingField.filters()` the same way regardless of leaf type). Use a sealed switch when the generator forks on identity (which `$fields` arm to emit for this leaf, which rows-method signature to synthesise). A new leaf added to a sealed hierarchy costs one sealed-switch arm *and* a capability implementation if it opts in — the switch doesn't go away, only the `instanceof` chain that tried to re-derive the capability at each call site.

## Narrow component types over broad interfaces

Field record components are declared with the narrowest type the classifier can guarantee rather than the broad sealed-interface root. A field whose return type is always table-bound declares `ReturnTypeRef.TableBoundReturnType` directly; a field whose return type is always polymorphic declares `ReturnTypeRef.PolymorphicReturnType` directly.

This pushes classification certainty into the type system: code that receives a `ServiceTableField` knows its `returnType` is `TableBoundReturnType` without a runtime check.

## Sub-taxonomies for resolution outcomes

Complex resolution outcomes get their own sealed type rather than being stored as raw strings. `BatchKey` is a sub-taxonomy of `ParamSource.Sources`, just as `TableRef` is a sub-taxonomy of `GraphitronType.TableBackedType` and `ColumnRef` is a sub-taxonomy of `InputField.ColumnField`. This pattern keeps each concept's complexity local and makes the taxonomy self-documenting: the type of a field tells you exactly what states it can be in.

**Corollary — audit sub-taxonomy pressure at stable points.** Each sub-taxonomy (`TableRef`, `ColumnRef`, `BatchKey`, `CallSiteExtraction`, `ArgumentRef`, `ReturnTypeRef.*BoundReturnType`, …) pays for itself individually; the aggregate cognitive cost of N parallel narrow hierarchies compounds and is not tracked per-addition. At stable points in the rewrite (milestone boundaries), audit: which sub-taxonomies could collapse into a sibling or a single parent now that their forcing functions are visible? Prefer collapse once compile-time guarantees are no longer the binding constraint. Each new sub-taxonomy proposal comes with a one-line note on what distinct information it carries that a sibling cannot — a sub-taxonomy without that note is probably a field on an existing record.

## Builder-internal sealed hierarchies for multi-target classification

When a builder step classifies inputs into many variants that project into *different* generation-ready outputs, introduce a builder-internal sealed hierarchy. It captures the full classification, enables exhaustive projection into each target, and is discarded before reaching the model.

`ArgumentRef` (see [argument-resolution.md](planning/argument-resolution.md)) classifies every GraphQL argument once into a variant (`ColumnArg`, `OrderByArg`, `PaginationArgRef`, `TableInputArg`, etc.). Separate projection steps then switch on the classified values to produce `GeneratedConditionFilter`, `LookupMapping`, `OrderBySpec`, and `PaginationSpec` — each projection is exhaustive and independent. The alternative — multiple independent passes that implicitly coordinate by skipping each other's arguments (e.g., `buildFilters()` skipping pagination args using the same hardcoded names as `buildPaginationSpec()`) — is fragile and makes adding new argument types error-prone.

The key distinction from model-level sealed hierarchies: builder-internal hierarchies are ephemeral. They exist to structure a complex builder decision, not to carry information to generators. Generators never see `ArgumentRef` — they see the projected results.

## Model metadata over parallel type systems

When the model already carries typed information, runtime data formats should derive from that metadata rather than inventing a parallel type system.

`OrderByResult` pairs `List<SortField<?>>` with `List<Field<?>>` — each cursor column's `DataType` is already known. Cursor encode/decode should use `field.getDataType().convert()` for type-safe round-tripping, and `DSL.noField(field)` for the no-cursor seek case. This eliminates the need for a hand-rolled type-tag system (`i:`, `s:`, `l:`) in the cursor format — the column metadata *is* the type information.

The general principle: when the model has already classified and resolved type information at build time, that same information should drive any runtime format that needs types. A parallel type system in the runtime format is redundant and will diverge.

## Validator mirrors classifier invariants

Every classifier decision that implies a generator branch must fail at validate time if that branch is unimplemented. The validator reads the same sets the dispatcher does (`NOT_IMPLEMENTED_REASONS.keySet()` today; the successor status-map when the four-set partition collapses) so an unsupported classification surfaces as a build-time error rather than a runtime `UnsupportedOperationException`. This closes the gap between "the schema classifies cleanly" and "the emitter has an arm for this leaf". `ValidateMojo` consumes the stubbed-variant set and fails the build by default.

The rule extends beyond stubbed variants: when a classifier introduces a new invariant (e.g. "`@asConnection` not allowed on inline `TableField`"), the validator should reject it by the same mechanism the generator relies on — no generator-side invariant goes unchecked at validate time. This keeps "problems caught at build time" honest and the generator's builder-invariant assumptions emitter-side safe.

## Pipeline tests are the primary behavioural tier

Behaviour is asserted at the SDL → classified model → generated `TypeSpec` pipeline layer — not at the per-variant unit tier. Per-variant structural tests (method names, return types, which methods exist) are bookkeeping; the primary signal that a feature works is that a realistic SDL produces a realistic `TypeSpec` end-to-end through the classifier. New features earn a pipeline test first; unit tests cover structural invariants that pipeline coverage would make repetitive.

Complementary tiers layered above: compilation of `graphitron-rewrite-test-spec` against real jOOQ classes (type correctness); execution of the generated code against real PostgreSQL (behaviour correctness). Code-string assertions on generated method bodies are banned at every tier — they test implementation, not behaviour, and break on every refactor.

## Documentation names only live tests/code

Javadoc, plan prose, and README references that name a test, method, or class must name one that exists today. A javadoc comment saying "enforced by `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus`" when that method does not exist is worse than no comment — it's a false invariant that readers trust. Reviewers check this explicitly during Draft → Approved and Pending Review → Done transitions. When a plan's wording anticipates a method, class, or test that the same plan will create, phrase it as "C3 adds `X`" rather than "as asserted by `X`".

## Compilation against real jOOQ is a test tier

`mvn compile -pl :graphitron-rewrite-test-spec -Plocal-db` against a real jOOQ catalog is the primary check that generated emission is type-correct. Unit tests assert structure; pipeline tests assert SDL → TypeSpec shape; compilation catches "the `Field<Record4<Int,Str,Int,Str>>` parameter doesn't line up with the emitted DSL call" without a hand-written assertion. Every generator change must pass `-Plocal-db` compile before merging.

The complementary tier above it — execution against a real PostgreSQL via the same fixture database — is the behaviour check. Together, compile + execute replace the body-content assertions that the "generation-thinking" principle bans.

## Generator Java version vs. generated output Java version

Graphitron is a code generator. The Java version used to build the generator is independent of the Java version of the source it emits.

- **Generator implementation** (everything in `graphitron-rewrite`, `graphitron-java-codegen`, etc.) may freely use Java 21 features — sealed classes, pattern matching, records, switch expressions, text blocks, and so on.
- **Generated source files** must target Java 17. Consumers compile Graphitron's output with their own toolchain, which may be Java 17. Generator authors are responsible for ensuring that any syntax emitted into generated files is valid Java 17 — no switch patterns, no sequenced collections API, nothing that requires 21.

The practical implication: when adding code to a generator, distinguish between code *in* the generator (unrestricted) and code *emitted by* the generator (Java 17).
