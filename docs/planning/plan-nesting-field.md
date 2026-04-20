# `ChildField.NestingField` emission

> **Status:** Draft
>
> Lift `ChildField.NestingField` out of `TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS` by (a) wiring a source-passthrough data fetcher and (b) recursing into the nested selection set from the parent's `$fields` projection via graphql-java's `DataFetchingFieldSelectionSet` API. First arm of roadmap item #8 ("Non-table / scalar / reference child leaves").

## Current state

- `ChildField.NestingField` (`model/ChildField.java:220`) carries the parent's `TableRef` verbatim in its `ReturnTypeRef.TableBoundReturnType`. It is deliberately excluded from `TableTargetField` (`ChildField.java:99`) because it does not navigate.
- Classification is complete: `FieldBuilder.classifyObjectReturnChildField:310` fires the `NestingField` arm when the child's return type is a `GraphQLObjectType` with no `GraphitronType` entry (no `@table`, no `@record`). Covered by `GraphitronSchemaBuilderTest.NestingFieldCase` — `PLAIN_OBJECT_TYPE` (`Film.details: FilmDetails`) and `LIST_OF_PLAIN_OBJECT_TYPE` (`Film.tags: [Tag!]!`).
- `TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS:225` routes `NestingField` to `stub(f)`; the sealed-switch arm in `generateTypeSpec` is at `:354`.
- `GraphitronSchemaValidator.validateNestingField:417` is empty; users see the generic stubbed-variant error from `validateVariantIsImplemented`.
- The nested child type (`FilmDetails`) has no Graphitron classification and no emitted fetchers class. Under the plan below it never gets one — its fields resolve via graphql-java's default `PropertyDataFetcher` against the parent's jOOQ record.

## Plan

Two commits.

### C1 — Emission

**Wiring.** Add a `ChildField.NestingField` arm to `TypeFetcherGenerator.buildWiringEntry` emitting `.dataFetcher($S, env -> env.getSource())`. Identical to the existing `ConstructorField` arm at `TypeFetcherGenerator.java:1280–1281` — the parent's record passes straight through; graphql-java resolves nested scalar fields from it via default property fetching against the generated jOOQ getters (`getTitle()`, `getDescription()`, …).

**`$fields` projection.** Extend `TypeClassGenerator.$fields` emission so that when a parent classifies a `NestingField` child, the emitted method inspects the nested sub-selection at runtime and contributes the matching columns to the parent's SELECT. Use graphql-java's `DataFetchingFieldSelectionSet.getImmediateFields()` (or `getFields(childName + "/*")`) scoped under the nesting field's name to enumerate nested scalar selections; map each selected field name to a column on the parent's table via the standard camelCase → `UPPER_SNAKE_CASE` convention already used elsewhere in the generator. Columns not present on the parent table are skipped (validator's problem, not runtime's).

Multi-level nesting (nesting inside nesting) falls out of the same recursion because the inner `NestingField` is, again, just a projection on the same parent table.

**Partition.** Remove `NestingField` from `NOT_IMPLEMENTED_REASONS`. The implementer picks the correct `*_LEAVES` partition — `NestingField` both participates in `$fields` (projection) and needs a wiring entry (source passthrough), so confirm the current partitions (`IMPLEMENTED_LEAVES`, `NOT_DISPATCHED_LEAVES`, `PROJECTED_LEAVES`) admit this combination and extend the partition vocabulary if not. `GeneratorCoverageTest` enforces the invariant and will catch drift.

Pipeline test: SDL with a `@table` parent and a plain-object nesting child classifies as `NestingField`; the generated fetchers class contains neither a method-body for the nesting field nor a stub throwing `UnsupportedOperationException`; the parent's `$fields` method references the nesting-field's selection subset.

### C2 — Validator + execution tests

**Validator.** Fill in `GraphitronSchemaValidator.validateNestingField`:

- Walk the nested object type's fields. For each field, require either (a) a matching column on the parent table by convention, or (b) another nesting-eligible object type as its return type (recursion). Anything else — a directive-bearing field (`@reference`, `@computed`, `@service`, `@table`, `@record`), or a scalar field with no matching column — produces a classification error at build time. Keeps the runtime surface narrow in v1 and surfaces typos early.
- Reject `FieldWrapper.List` cardinality on `NestingField` with a clear message. "Inherits the parent's table context unchanged" has no sensible list semantics under option C (no navigation → no way to produce multiple rows). Legacy may have supported this; if a real schema needs it we lift the rejection in a follow-up with a defined semantic.

Pipeline test coverage: directive-bearing nested field → classification error; list-cardinality nesting → classification error.

**Execution tests.** Add to `graphitron-rewrite-test-spec`:

- Scalar nesting. `Film @table { details: FilmDetails } ; FilmDetails { title, description, releaseYear }`. Query `film { details { title releaseYear } }` → single SQL round-trip projecting `FILM.TITLE, FILM.RELEASE_YEAR` only (not `DESCRIPTION`).
- Multi-level nesting. `Film { details: FilmDetails } ; FilmDetails { meta: FilmMeta } ; FilmMeta { releaseYear }`. Confirm the projection and resolution paths still close.
- Null-parent short-circuit. `film(id: <nonexistent>) { details { title } }` → `details: null`, no NPE on the passthrough data fetcher.

Compile gate: `mvn compile -pl :graphitron-rewrite-test,:graphitron-rewrite-test-fixtures,:graphitron-rewrite-test-spec -Plocal-db`.

## Non-goals

- **Other arms of roadmap #8** — `ColumnReferenceField`, `NodeIdReferenceField`, `ComputedField`, `TableMethodField`, `ServiceRecordField`, `MultitableReferenceField` each get their own plan. `NodeIdReferenceField` is additionally blocked on Platform-id (Active).
- **Directive-bearing fields on nested types** — rejected at validate time in v1. When a schema needs `FilmDetails.externalRef: Something @reference(path: …)` we extend the validator + $fields recursion in a follow-up.
- **List-cardinality nesting** — rejected at validate time. Lift when a concrete use case arrives with a defined semantic.
- **Explicit classification of nested child types** — intentionally skipped. `FilmDetails` never enters the `GraphitronType` map; its fields resolve via graphql-java's default property fetcher. The validator walks the nested GraphQL type directly.
- **Schema-field-name to column-name overrides** — v1 relies on the standard naming convention. If a nested field needs a custom mapping we either reject it (forcing the author to rename) or add a narrow override mechanism in a follow-up.
