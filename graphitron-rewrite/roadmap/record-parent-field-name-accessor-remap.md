---
id: R191
title: Honor @field(name:) for accessor lookup on free-form @record parents
status: Spec
bucket: bug
priority: 5
theme: service
depends-on: []
created: 2026-05-20
last-updated: 2026-05-20
---

# Honor @field(name:) for accessor lookup on free-form @record parents

## Problem

On a free-form `@record` parent (`PojoResultType` or `JavaRecordType` backing class), the table-bound child path derives candidate accessor method names exclusively from the GraphQL field name. `FieldBuilder.collectAccessorMatches` tries `<fieldName>`, `get<FieldName>`, `is<FieldName>` and nothing else (`FieldBuilder.java:4236-4239`). The `@field(name: ...)` directive value is computed into `columnName` at the call site (`FieldBuilder.java:3668-3670`) but is dropped before `resolveRecordParentSource(name, ...)` is invoked with the raw GraphQL field name (`FieldBuilder.java:3700`); `deriveAccessorRecordParentSource` and `collectAccessorMatches` then see only `name`.

The polymorphic-hub call site has the same shape: `derivePolymorphicHubSource` invokes `collectAccessorMatches(parentClass, fieldName, fieldIsList, null)` at `FieldBuilder.java:4477` from the polymorphic-child branch dispatched at `FieldBuilder.java:4432`. The same divergence on a polymorphic child on a free-form `@record` parent triggers the same fall-through.

When an author writes a Pojo-backed `@record` whose accessor name diverges from the GraphQL field name and tries to bridge with `@field(name: "<accessorTail>")`, the matcher returns `AccessorDerivation.None` and the field falls through to the three-option AUTHOR_ERROR (`FieldBuilder.java:4057-4058`). The rejection text advertises "expose a typed accessor on the parent returning List<...Record>, Set<...Record>, or ...Record" without mentioning the name-matching requirement, so authors read the suggestion as describing exactly what they already wrote and conclude the validator is broken.

Concrete repro (from a user report): a Pojo with `getUtdanningsspesifikasjonRecord()` / `getUtdanningsmulighetRecords()` returning typed `TableRecord` shapes, exposed through SDL fields `utdanningsspesifikasjon` / `utdanningsmulighet` with `@field(name: "utdanningsspesifikasjonRecord")` / `@field(name: "utdanningsmulighetRecords")`. Both fields land as `UnclassifiedField` despite the typed accessors being present.

## Resolution

Adopt option (a): teach the table-bound and polymorphic-hub accessor-lookup paths to honor `@field(name:)` on free-form `@record` parents. When the directive is present on a child field whose parent is a `PojoResultType` or `JavaRecordType`, the directive value is used as the sole accessor-name target (with the same `<value>` / `get<Value>` / `is<Value>` triple); when absent, the GraphQL field name is used as today.

This restores symmetry with the scalar/result branch on the same parent shape, which already threads `@field(name:)` into `resolveRecordAccessor` as `accessorBaseName` (`FieldBuilder.java:3651,3797`). Option (b) (rewrite the rejection text and leave the validator alone) was rejected because it would entrench an internal asymmetry: `@field(name:)` would remap accessor names on scalar/result children but not on table-bound children of the same parent, which is the harder semantics to explain in docs and the easier surprise to walk into.

The column-remap reading of `@field(name:)` on `JooqTableRecord` parents (where the directive picks a SQL column on the parent's jOOQ table) is unaffected; that branch never reaches `deriveAccessorRecordParentSource` or `derivePolymorphicHubSource` because `JooqTableRecordType` / `JooqRecordType` both map to `parentFqClassName == null` in the parent-class switch at `FieldBuilder.java:4123-4127` and `FieldBuilder.java:4458-4462`. The two readings stay disjoint by parent shape.

## Implementation

* `FieldBuilder.collectAccessorMatches`: take an additional `accessorBaseName` parameter and replace the use of `fieldName` for name matching with that value. The `ucFirst` and the three `nameMatches` checks shift from `fieldName` / `ucName` to `accessorBaseName` / `ucFirst(accessorBaseName)`. The `fieldName` parameter remains for error messages (cardinality-mismatch text quotes the SDL field name, not the accessor base) and `fieldIsList`/`expectedSqlName` are unchanged.
* `FieldBuilder.deriveAccessorRecordParentSource`: take `accessorBaseName` from the caller and pass it through to `collectAccessorMatches`. The signature gains one parameter; the `Ok` / `Ambiguous` / `CardinalityMismatch` / `None` outcomes and the `AccessorRef.methodName()` value are unchanged (the matched method's actual name is still what gets called at runtime, not the directive value).
* `FieldBuilder.resolveRecordParentSource`: take `accessorBaseName` from the caller and pass it through to `deriveAccessorRecordParentSource`. The FK-derivation arm is unaffected (FK derivation runs off catalog metadata, not parent-class reflection).
* `FieldBuilder` `TableBoundReturnType` case at line 3700: compute the accessor base name from the directive (mirroring the `columnName` computation already done at 3668-3670 for the column-resolution path) and pass it into `resolveRecordParentSource`. Reuse the already-computed `columnName` rather than recomputing.
* `FieldBuilder.derivePolymorphicHubSource`: take `accessorBaseName` from the polymorphic-child dispatch at line 4432 and pass it through to `collectAccessorMatches`. The directive is read at the dispatch site (parent of `classifyRecordParentPolymorphicChild` / `derivePolymorphicHubSource`) the same way the table-bound site reads it.
* The `accessor-rowkey-shape-resolved` and `accessor-rowkey-shape-resolved-against-hub` `LoadBearingClassifierCheck` description blocks gain one sentence each clarifying that the matched accessor's name is `@field(name:)` when the directive is present on a free-form `@record` parent, else the GraphQL field name. The invariants (single match, container/element classification, table identity) are unchanged.

## Tests

* `GraphitronSchemaBuilderTest.AccessorDerivedSourceCase`: add a new enum case `ACCESSOR_ROWKEYED_FIELD_NAME_REMAPS_ACCESSOR` exercising the divergent-accessor-name + `@field(name:)` admit. SDL has a Pojo parent whose accessor is `filmRecord` (or `getFilmRecord`) while the SDL child field is named `film` with `@field(name: "filmRecord")`. Assertions mirror `ACCESSOR_ROWKEYED_SINGLE_SINGLE_FIELD_SINGLE_ACCESSOR`: the field classifies as `RecordTableField`, `SourceKey.Reader.AccessorCall.accessor().methodName()` equals the actual accessor method name (`filmRecord` or `getFilmRecord`, not the SDL name `film`), and cardinality matches the field shape.
* Add a second enum case `ACCESSOR_ROWKEYED_FIELD_NAME_REJECTS_WITHOUT_DIRECTIVE` pinning the existing behavior on the other side of the fork: divergent accessor name with no `@field(name:)` still falls through to the three-option AUTHOR_ERROR. The repro from the bug report is exactly this shape minus the directive; pinning it prevents a future "be helpful, scan all accessors" drift that would reintroduce ambiguity.
* Co-locate the new test classes in `AccessorPayloads`: add `record RemappedPayload(FilmRecord filmRecord)` (or a Pojo `RemappedListPayload` exposing `List<FilmRecord> filmRecords()`). One single-cardinality fixture is sufficient; the cardinality, ambiguity, and heterogeneous-element arms are already covered by the existing cases and the remap rule is orthogonal to them.
* Polymorphic-hub coverage: add a single classifier case under whichever enum currently covers `derivePolymorphicHubSource` admits (typically a `*PolymorphicHub*` or `*PolymorphicChild*` enum in `GraphitronSchemaBuilderTest`; locate during implementation). One admit-with-`@field(name:)` is enough; the existing hub-discovery cases cover the rest of the space.
* No pipeline-tier or execution-tier additions: the runtime path beyond classification is unchanged. The accessor method name carried on `AccessorRef` is the actual reflected name in every case; `buildAccessorKeySingle` / `buildAccessorKeyMany` and `TypeFetcherGenerator.buildRecordBasedDataFetcher` already invoke by that name without caring how it was selected. Existing pipeline coverage of the auto-derive arm continues to assert the runtime contract.

## Out of scope

* Renaming or restructuring `@field(name:)`. The directive keeps its current two readings (column on `JooqTableRecord` parents, accessor base on free-form `@record` parents); this Spec only extends the second reading to the table-bound and polymorphic-hub branches that were skipping it.
* Touching the FK-derivation path. FK derivation runs off jOOQ catalog metadata, not parent-class reflection, so `@field(name:)` is structurally irrelevant there.
* Touching the rejection text. The three-option AUTHOR_ERROR keeps its current wording; with the directive honored, an author who wrote the bug-report shape now gets the admit they expected, and an author who omitted the directive sees the same three options as before.
