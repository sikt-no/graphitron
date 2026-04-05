# Review: `docs/plan-phase-out-dto-layer.md`

## Context

This review assesses the plan to phase out the DTO/TypeMapper layer in graphitron's code generation pipeline, replacing it with a record-based output that resolves GraphQL fields directly from jOOQ `Record` objects. The review covers architecture viability, implementation quality of what's been built so far, and testing gaps to address before starting the next implementation phase (the generating stream and integration deliverables).

---

## 1. Architecture Viability — Strong

The overall architecture is sound and well-suited to the problem.

### What works well

- **Sealed hierarchy as the core abstraction.** Using Java sealed interfaces for `GraphitronField` (28+ leaf types) and `GraphitronType` (8 variants) gives exhaustive pattern matching enforced by the compiler. This eliminates entire categories of bugs where a new field/type variant is added but not handled in validators or generators.

- **Two-stream independence.** The parsing stream (schema → `GraphitronField`) and generating stream (`GraphitronField` → JavaPoet) are fully decoupled. Each can be tested in isolation with hand-crafted inputs. This is the right decomposition.

- **Directive boundary.** Only `GraphitronSchemaBuilder` reads directives from the schema. All downstream code operates on classified records. This prevents directive-reading logic from leaking into generators and validators.

- **Resolved/Unresolved pattern.** `TableRef`, `ColumnRef`, `ReferencePathElementRef`, `NodeTypeRef`, `FieldConditionRef` all use two-variant sealed hierarchies to represent resolution outcomes. The validator reports errors for unresolved variants; generators only consume resolved ones. This makes "missing FK" or "unknown column" into type-safe states rather than nullable fields.

- **Feature flag coexistence.** Running new generators alongside existing ones behind `recordBasedOutput` is the right transition strategy — zero risk to existing users.

- **`LightDataFetcher` in `GraphitronFetcherFactory`.** Using graphql-java's `LightDataFetcher` for scalar fields avoids creating a full `DataFetchingEnvironment` per field resolution. This is a meaningful performance win for types with many scalar columns.

### Concerns (minor, none blocking)

1. **`CompletableFuture.supplyAsync()` without an executor (Deliverable 3, line ~340).** The generated root field code uses `CompletableFuture.supplyAsync(() -> ctx.getDslContext(env).select(...).fetchOne())` which defaults to `ForkJoinPool.commonPool()`. This pool uses daemon threads sized to CPU cores and is inappropriate for blocking JDBC calls. The plan should specify that an executor parameter (from `GraphitronContext` or `GraphQlContext`) is used, or document this as a known limitation to address in a follow-up.

2. **`@defer` terminology in Deliverable 4 (line ~412).** The section title says "@defer check-then-fetch" but the pattern described is actually an inline-prefetch-with-fallback pattern (check if the parent query already embedded the result, fall back to a separate query if not). This is not related to GraphQL `@defer`. The naming may confuse readers — suggest renaming to "prefetch-with-fallback" or "inline-or-fetch".

3. **No executor/thread-pool strategy documented.** The plan covers SQL generation and wiring in detail but doesn't discuss the threading model for DataLoaders and root field fetchers. This matters for production correctness and should be addressed at least as a note in Deliverable 5 (DataLoaders).

4. **Mutation scope deferral is fine** but should be called out more prominently as a phase boundary. Currently buried at the bottom (line 621).

---

## 2. Implementation Quality — Excellent

What's been built (Deliverables 1 + parsing stream P1–P5) is high quality.

### Strengths

- **`GraphitronSchemaBuilder`** (~800+ lines) handles the full classification matrix: type directives × field directives × return type resolution × FK inference. The classification priority for query fields (lines 234–243 of the plan) correctly handles ambiguous cases (e.g., `@service` takes priority over `@lookupKey`).

- **`GraphitronSchemaValidator`** is exhaustive — every sealed leaf has a dedicated validation branch. Error messages include field/type names and source locations. The accumulating error list (rather than fail-fast) is the right choice for a build tool.

- **`JooqCatalog`** correctly uses reflection to find Java field names rather than uppercasing SQL names. This is critical for projects with custom jOOQ `GeneratorStrategy` implementations — a detail many tools get wrong.

- **`GraphitronFetcherFactory`** is minimal and correct: 3 methods, each a one-liner returning a `LightDataFetcher`. The `nestedRecord`/`nestedResult` methods correctly use typed `Record.get(alias, Class)` calls.

- **Record design.** All leaf types are Java records with immutable fields. No mutable state anywhere in the classified schema representation.

### Gaps in infrastructure (Deliverable 2 incomplete)

These items are described in the plan but not yet implemented:

| Item | File | Status |
|---|---|---|
| `recordBasedOutput` flag | `GeneratorConfig.java` | **Missing** |
| `recordBasedOutput` Maven parameter | `GenerateMojo.java` | **Missing** |
| `recordBasedOutput()` on `Generator` interface | `Generator.java` | **Missing** |
| `getTenantId()` interface method | `GraphitronContext.java` | **Missing** |
| `getTenantId()` implementation | `DefaultGraphitronContext.java` | **Missing** |
| Conditional generator registration | `GraphQLGenerator.getGenerators()` | **Missing** (existing record mappers run unconditionally) |

These are all straightforward additions. Recommend completing Deliverable 2 fully before starting the generating stream.

---

## 3. Testing Gaps — Actionable Items Before Next Phase

### Level 1 (Validator unit tests) — 2 gaps

| Gap | Severity | Recommendation |
|---|---|---|
| **No `ErrorTypeValidationTest.java`** | Low | The validator has an explicit no-op for `ErrorType` (line 60 of `GraphitronSchemaValidator.java`). Add a test that constructs an `ErrorType` and asserts zero validation errors. This documents the intentional no-op and prevents accidental regression if validation rules are added later. |
| **`NodeTypeValidationTest.java` scope** | Low | Node validation is partly covered via `TableTypeValidationTest` but `@node` with key columns, unresolved key columns, and typeId deserves its own dedicated test class for clarity. Currently `NodeTypeValidationTest.java` exists with 5 cases — verify it covers `UnresolvedKeyColumn` and empty-keyColumns-fallback-to-PK scenarios. |

### Level 2 (Classification tests) — 2 gaps

| Gap | Severity | Recommendation |
|---|---|---|
| **`ConstructorField` has no classification test** | Medium | The plan says the builder classifies it as `UnclassifiedField` (since the directive isn't defined yet). Add a test in `GraphitronSchemaBuilderTest` that confirms a field which *would* be a `ConstructorField` is classified as `UnclassifiedField` with the expected validator error. This documents the intentional gap. |
| **`UnclassifiedField` has no classification test** | Medium | Add a test confirming that a field on a `@table` type with no recognized directive pattern falls through to `UnclassifiedField`. Currently only tested via direct construction in `UnclassifiedFieldValidationTest`. |

### Level 3 (Generating stream) — entirely missing

The plan's testing strategy requires approval tests for every leaf type in the generating stream. Since `FieldsCodeGenerator` doesn't exist yet, this is expected — but the plan should be explicit that **no code generation work should begin until the Level 2 gaps above are closed**. The classification tests are the specification; generating code against an incompletely-specified classification is risky.

### Cross-cutting gaps

| Gap | Severity | Recommendation |
|---|---|---|
| **No negative classification tests for directive conflicts** | Medium | Add tests for invalid directive combinations that should produce errors: e.g., `@table` + `@record` on the same type, `@nodeId` + `@service` on the same field, `@splitQuery` on a non-`TableField`. These are boundary conditions the builder must handle. |
| **No test for `hasLookupKeyAnywhere()` depth guard** | Low | The plan mentions a depth guard at 10 levels for recursive `@lookupKey` detection. Add a test confirming the guard prevents infinite recursion on circular input type references. |
| **`FieldWrapper.Connection` detection not tested** | Medium | The plan describes structural detection (edges→node pattern) for connection fields. Verify `GraphitronSchemaBuilderTest` has a case that classifies a connection-wrapped `TableField` with the correct `FieldWrapper.Connection` wrapper. The `TableFieldCase` enum has 11 cases — check if any test connection detection specifically. |
| **No round-trip test for `JooqCatalog` with Sakila** | Low | `JooqCatalog` is tested indirectly via `GraphitronSchemaBuilderTest` (which uses `@table(name: "film")` etc.), but a direct unit test against the Sakila jOOQ classes would catch reflection edge cases (e.g., tables with unusual naming, composite FKs). |

---

## 4. Recommended Sequencing Before Next Phase

1. **Complete Deliverable 2** — Add `recordBasedOutput` flag to `GeneratorConfig`, `GenerateMojo`, and `Generator` interface. Add `getTenantId()` to `GraphitronContext`/`DefaultGraphitronContext`. Gate new generators in `GraphQLGenerator.getGenerators()`.

2. **Close Level 2 classification gaps** — Add `ConstructorField` and `UnclassifiedField` classification tests. Add directive-conflict negative tests. Verify `FieldWrapper.Connection` detection test exists.

3. **Clarify `CompletableFuture` executor strategy** — Document in the plan whether generated code will accept an executor parameter or use a fixed pool. This affects the API surface of every generated root field and DataLoader method.

4. **Rename "@defer" section** — Deliverable 4's "check-then-fetch" pattern is not related to GraphQL `@defer`. Rename to avoid confusion.

5. **Then proceed to generating stream (G1)** — With classification fully tested, hand-craft `GraphitronField` instances for `ColumnField` and `TableQueryField`, implement `FieldsCodeGenerator`, and write approval tests.

---

## 5. Summary

| Area | Rating | Notes |
|---|---|---|
| Architecture | **Strong** | Sealed hierarchies, two-stream design, directive boundary — all excellent |
| Implementation (D1 + parsing) | **Excellent** | Complete, well-structured, uses records and sealed types correctly |
| Infrastructure (D2) | **Incomplete** | Feature flag and context methods not yet wired |
| Testing (L1 validators) | **Very good** | 40+ test files, 1 minor gap (ErrorType) |
| Testing (L2 classification) | **Very good** | 26/28 leaf types, 2 gaps (ConstructorField, UnclassifiedField) |
| Testing (L3 generation) | **Not started** | Expected — generating stream not built yet |
| Plan clarity | **Good** | Minor naming issues (@defer), executor strategy undocumented |
