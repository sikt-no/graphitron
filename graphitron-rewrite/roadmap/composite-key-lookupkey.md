---
id: R5
title: "Composite-key `@lookupKey` on list-of-input-object arguments"
status: In Review
bucket: architecture
priority: 4
theme: model-cleanup
depends-on: []
---

# Composite-key `@lookupKey` on list-of-input-object arguments

Shipped at `b55921d0`. Cleanup-and-hardening pass over the already-shipped composite-key path; no behavioural change.

What landed:

- `LookupMapping.MapInput` / `DecodedRecord` canonical constructors reject empty bindings with a named `IllegalArgumentException`. `ColumnMapping.args` stays open by design; the empty-args case is rejected upstream at `FieldBuilder.projectForFilter` before a `LookupTableField` is constructed (documented inline).
- Three new `@LoadBearingClassifierCheck` keys cover the lookup pipeline (previously zero coverage): `lookup-mapping-bindings-table-coherent` and `lookup-key-input-field-non-list` on `EnumMappingResolver.buildLookupBindings`; `lookup-field-non-empty-args` on `FieldBuilder.projectForFilter`. Matching `@DependsOnClassifierCheck` consumers on `LookupValuesJoinEmitter.flattenSlots` and `buildInputRowsMethod`.
- `LookupMappingTest` pins the canonical-constructor invariants and the single-binding-succeeds case.
- `LookupTableFieldPipelineTest.compositeKeyInputType_producesSwitchArmAndInputRowsHelper` extended with assertions on projected `ColumnMapping.MapInput` shape (binding fieldNames + targetColumns + slotColumns order).
- `CompositeKeyLookupQueryTest` (execution tier): SQL-string assertion that the rendered jOOQ JOIN uses `using ("film_id", "actor_id")` — single-column regressions surface as test failure rather than at runtime. Sibling subset-path test pins the "missing slot is null/absent" contract for composite keys.

Naming drift caught during implementation: spec body referred to `FieldBuilder.classifyTableFieldComponents`; the actual method is `projectForFilter`. Annotation landed on the live name; the spec citation was a copy from an earlier internal name.

Behavioural execution-tier coverage of `filmActorsByKey` already lives in `GraphQLQueryTest.compositeKeyLookup_*` (returnsMatchingPairs / preservesInputOrder / mismatchedPairExcluded / emptyInput); the new class focuses on the SQL-shape claim which was previously unverified.

`mvn -f graphitron-rewrite/pom.xml install -Plocal-db -P!docs` green.
