---
date: 2026-04-14T11:12:45Z
researcher: claude
git_commit: c1b7eb6
branch: claude/validation-test-coverage-plan-EcOP7
repository: sikt-no/graphitron
topic: "Rewrite Pipeline Generator Implementation"
tags: [implementation, rewrite-pipeline, code-generation, graphql, jooq]
status: complete
last_updated: 2026-04-14
last_updated_by: claude
type: implementation_strategy
---

# Handoff: Rewrite Pipeline — Schema Builder Split + Generator Implementation

## Task(s)

### Completed

1. **Split GraphitronSchemaBuilder** (2154 lines → 5 focused components)
   - `BuildContext.java` — shared state + constants + utilities
   - `ServiceCatalog.java` — reflection + jOOQ catalog lookups
   - `TypeBuilder.java` — two-pass type classification
   - `FieldBuilder.java` — field classification with `resolveServiceField`/`resolveTableFieldComponents` helpers
   - `GraphitronSchemaBuilder.java` — thin ~100-line orchestrator
   - Plan doc: `docs/plan-split-schema-builder.md` (marked COMPLETE)

2. **G3 — Scalar child fields** (`ColumnField` data fetchers + `fields()` SELECT list)
   - `fields()` uses `sel.getFieldsGroupedByResultKey()` for alias-safe column selection
   - `ColumnField` fetcher: `((Record) env.getSource()).get(Tables.TABLE.COL)`

3. **G4 — Root query fields** (`QueryTableField` → `selectMany`/`selectOne`)
   - Condition building with three filter types
   - ORDER BY from `OrderBySpec.Fixed`/`None`/`Argument`

4. **I1 — GraphitronWiring** (aggregates all `wiring()` calls → `RuntimeWiring.Builder`)

5. **Enum support** — build-time validation + runtime conversion
   - `EnumColumnFilter` — jOOQ enum column: `valueOf(String)` conversion
   - `TextEnumColumnFilter` — varchar column: static `Map<String,String>` lookup with `@field(name:)` support
   - Build-time validation: GraphQL enum values must match Java enum constants

6. **Generator architecture restructuring**
   - `TypeClassGenerator` → `<TypeName>.java` (SQL scope: fields, selectMany, selectOne)
   - `TypeFieldsGenerator` → `<TypeName>Fields.java` (fetchers, wiring)
   - `TypeConditionsGenerator` → `<TypeName>Conditions.java` (condition methods, enum maps)
   - `GraphitronWiringClassGenerator` → `GraphitronWiring.java`
   - All in `rewrite.types` package, one class per GraphQL type (not per SQL table)

7. **End-to-end execution tests** against real PostgreSQL via TestContainers
   - 10 tests verifying queries, filters (boolean, jOOQ enum, text enum), ordering, selection set scoping

8. **Test strategy cleanup** — dropped code-string assertions, documented convention in CLAUDE.md

### Remaining from quality review (not started)
- Stub methods in type classes (selectManyByRowKeys etc.) — evaluate if they should be removed
- `maxRentalRate` generates `eq` but semantically should be `le` — needs operator support in taxonomy
- OrderBy as own method (like conditions) — discussed but not implemented

## Critical References

- `docs/plan-record-generation.md` — generation plan with deliverable status table and architecture
- `docs/rewrite-schema-classification.md` — taxonomy design, architecture diagram, design principles
- `CLAUDE.md` — testing conventions, environment setup, development guidelines

## Recent changes

All changes on branch `claude/validation-test-coverage-plan-EcOP7`:

- `graphitron-codegen-parent/graphitron-java-codegen/src/main/java/no/sikt/graphitron/rewrite/generators/TypeClassGenerator.java` — SQL scope generator (fields, selectMany, selectOne)
- `graphitron-codegen-parent/graphitron-java-codegen/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFieldsGenerator.java` — fetcher + wiring generator
- `graphitron-codegen-parent/graphitron-java-codegen/src/main/java/no/sikt/graphitron/rewrite/generators/TypeConditionsGenerator.java` — condition method generator
- `graphitron-codegen-parent/graphitron-java-codegen/src/main/java/no/sikt/graphitron/rewrite/generators/GraphitronWiringClassGenerator.java` — wiring aggregator
- `graphitron-codegen-parent/graphitron-java-codegen/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java` — enum validation in `validateEnumFilter()` and `buildTextEnumMapping()`
- `graphitron-codegen-parent/graphitron-java-codegen/src/main/java/no/sikt/graphitron/rewrite/model/WhereFilter.java` — three filter types: ColumnFilter, EnumColumnFilter, TextEnumColumnFilter
- `graphitron-codegen-parent/graphitron-java-codegen/src/main/java/no/sikt/graphitron/rewrite/GraphQLRewriteGenerator.java` — orchestrator wiring all generators
- `graphitron-rewrite-test/graphitron-rewrite-test-spec/src/test/java/no/sikt/graphitron/rewrite/test/GraphQLQueryTest.java` — 10 end-to-end execution tests

## Learnings

1. **Large file writes crash the session.** Copy-then-trim (subtraction) works; writing from scratch (addition) doesn't. Use surgical edits, not full rewrites.

2. **graphql-java selection set API:** `env.getSelectionSet()` is scoped per data fetcher. `getFieldsGroupedByResultKey()` is the right entry point — handles aliases and gives `SelectedField` handles for drill-down.

3. **jOOQ `eq()` overload ambiguity:** `env.getArgument()` returns `Object`, causing ambiguous overloads. Fix: `DSL.val(value, TABLE.COL)` for all conditions — the `Field<T>` parameter resolves ambiguity.

4. **Enum three-layer problem:** GraphQL value names (`PG_13`), Java enum constants (`PG_13`), PostgreSQL literals (`"PG-13"`) can all differ. Build-time validation ensures GraphQL↔Java match; text enum maps handle GraphQL→DB string conversion.

5. **Condition methods as pure functions:** Taking `(Table table, TypedArg1 arg1, ...)` makes them testable without graphql-java, and gives the same signature as developer `@condition` methods — unified model.

6. **One class per GraphQL type, not per SQL table.** Multiple GraphQL types can map to the same table; each gets its own generated classes. Consistent with Fields class naming.

7. **Don't test generated code bodies with string assertions.** Test structure (method names, types, signatures) in unit tests. Test correctness via compilation against real jOOQ + execution against real PostgreSQL.

## Artifacts

- `docs/plan-record-generation.md` — updated generation plan with status table
- `docs/rewrite-schema-classification.md` — updated architecture diagram
- `docs/plan-split-schema-builder.md` — marked COMPLETE
- `CLAUDE.md` — testing conventions
- `graphitron-codegen-parent/graphitron-java-codegen/src/main/java/no/sikt/graphitron/rewrite/generators/TypeClassGenerator.java`
- `graphitron-codegen-parent/graphitron-java-codegen/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFieldsGenerator.java`
- `graphitron-codegen-parent/graphitron-java-codegen/src/main/java/no/sikt/graphitron/rewrite/generators/TypeConditionsGenerator.java`
- `graphitron-codegen-parent/graphitron-java-codegen/src/main/java/no/sikt/graphitron/rewrite/generators/GraphitronWiringClassGenerator.java`
- `graphitron-codegen-parent/graphitron-java-codegen/src/main/java/no/sikt/graphitron/rewrite/model/WhereFilter.java`
- `graphitron-codegen-parent/graphitron-java-codegen/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java`
- `graphitron-codegen-parent/graphitron-java-codegen/src/test/java/no/sikt/graphitron/rewrite/generators/TypeClassGeneratorTest.java`
- `graphitron-codegen-parent/graphitron-java-codegen/src/test/java/no/sikt/graphitron/rewrite/generators/TypeFieldsGeneratorTest.java`
- `graphitron-codegen-parent/graphitron-java-codegen/src/test/java/no/sikt/graphitron/rewrite/generators/FieldsPipelineTest.java`
- `graphitron-codegen-parent/graphitron-java-codegen/src/test/java/no/sikt/graphitron/rewrite/generators/TablePipelineTest.java`
- `graphitron-rewrite-test/graphitron-rewrite-test-spec/src/test/java/no/sikt/graphitron/rewrite/test/GraphQLQueryTest.java`

## Action Items & Next Steps

1. **G5 — Inline TableField** (next deliverable per plan): nested table fields without `@splitQuery`. Requires recursive `fields()` generation using `SelectedField.getSelectionSet()` drill-down and `subselectMany`/`subselectOne` implementation.

2. **OrderBy as own generator** — same pattern as conditions: pure function with named parameters, own class. Discussed but not implemented.

3. **Operator support in taxonomy** — `maxRentalRate` generates `eq` but should be `le`. WhereFilter needs an operator field or variant.

4. **Remove stub methods** — 6 stub methods on type class (selectManyByRowKeys etc.) throw UnsupportedOperationException. Evaluate removal or gating behind feature flag.

5. **ColumnReferenceField support** — G3 handles ColumnField but not ColumnReferenceField (FK-traversed column access). Needs join path resolution.

## Other Notes

- Docker daemon dies frequently in this environment. Restart with: `dockerd --host=unix:///var/run/docker.sock > /tmp/dockerd.log 2>&1 &`
- Maven proxy credentials expire. Recreate `~/.m2/settings.xml` from `$http_proxy` env var per CLAUDE.md instructions.
- The rewrite-test-spec module needs `mvn install` of graphitron-java-codegen first, then `mvn compile -pl :graphitron-rewrite-test-spec` to regenerate code before `mvn test`.
- All 1576 codegen tests + 10 execution tests pass as of commit c1b7eb6.
