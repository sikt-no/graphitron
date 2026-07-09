---
id: R277
title: "Support @tableMethod under a table-bound NestingField"
status: Backlog
bucket: feature
priority: 6
theme: service
depends-on: []
created: 2026-06-03
last-updated: 2026-06-03
---

# Support @tableMethod under a table-bound NestingField

A plain object child of a `@table` parent, with no boundary directive (`@reference`/`@splitQuery`/FK), classifies as a `ChildField.NestingField` whose nested fields are bound to the parent's table (`FieldBuilder.classifyObjectReturnChildField`, the `TableBoundReturnType(elementTypeName, parentTableType.table())` arm). A `@tableMethod` field declared inside such a nesting is conceptually identical to a `@tableMethod` directly on the `@table` parent: it correlates the developer-returned table against the same parent row via the resolved FK's source-side columns, and the nesting carries the same bound table. The generator's projection-column collection already anticipates this (`TypeClassGenerator.collectRequiredProjectionColumns` has a `TableMethodField` arm and recurses into `NestingField`).

It is currently rejected, and not wired, for one structural reason: `ChildField.TableMethodField implements ChildField, MethodBackedField, WithErrorChannel` but **not** `BatchKeyField`, and the nested-fetcher machinery keys on `BatchKeyField`, not on "method-backed":

* `GraphitronSchemaValidator.NESTED_WIREABLE_LEAVES` does not list `TableMethodField`, so `validateVariantIsSupportedAtNestedDepth` defers it ("TableMethodField is not yet supported under NestingField").
* `TypeFetcherGenerator.collectNestedFetcherClasses` generates the nested `<Type>Fetchers` class only from `BatchKeyField` nested fields (and only when at least one exists), so a `NestingField` whose only method-backed leaf is a `TableMethodField` gets no fetcher class and no emitted fetcher method.
* `FetcherRegistrationsEmitter.nestedBody` computes `nestedFetchersClass` only when a `BatchKeyField` is present and otherwise hardcodes `fetchersClass = null` into `registrationEntry`; a `TableMethodField` then reaches `FetcherEmitter.dataFetcherValueRaw`'s method-reference arm (`"$T::$L"`) with a null type and the build throws `IllegalArgumentException: expected type but was null`.

## Target

Generalise the two nested-fetcher sites from `BatchKeyField` to "needs a generated fetcher method" (method-backed): emit the nested `<Type>Fetchers` class with its method-backed leaves (not just batch-keyed ones), and route those leaves to that class in the registration emit. At the top level this already works (`buildBody` always passes a non-null fetchers class), so this is bringing the nested path to parity, not new emit logic for the fetcher body itself (`buildChildTableMethodFetcher` is unchanged).

## Likely seams

* `GraphitronSchemaValidator.NESTED_WIREABLE_LEAVES`: add `ChildField.TableMethodField`.
* `TypeFetcherGenerator.collectNestedFetcherClasses`: include method-backed (non-batch-key) leaves when generating the nested type's `TypeSpec`, so their fetcher methods are emitted.
* `FetcherRegistrationsEmitter.nestedBody`: compute `nestedFetchersClass` when any leaf needs a fetcher method, and pass it (not `null`) to `registrationEntry` for method-backed leaves.

## Tests

* **Compilation (`@CompilationTier`).** A `@table` parent with a plain nested-object child carrying a `@tableMethod` field generates and compiles.
* **Execution (`@ExecutionTier`).** The nested `@tableMethod` resolves the developer-returned table correlated on the parent row, verifying `env.getSource()` is the parent record at the nested depth and the registration method reference points at the nested fetcher.

## Context

Surfaced by R276 (record binding is reflection-only): removing `@record` binding reclassified `FilmDetailsForMethod` in `graphitron-sakila-example`'s `schema.graphqls` from a `JooqTableRecordType` (DTO-parent `@tableMethod` emit) to a table-bound `NestingField`, exposing that `@tableMethod` under a `NestingField` was never wired (the old `@record` path masked it via `RecordTableMethodField`). As an interim, the `languageViaTableMethod` `@tableMethod` child was removed from that fixture so the example build stays green; re-add it as the execution-tier fixture when this item lands.

## Out of scope

* `RecordTableMethodField` (the DTO-parent `@record` sibling) is retired with `@record` binding; this item is the table-bound nesting path only.
