---
id: R81
title: "Sealed resolution outcomes for catalog table/FK lookups"
status: Spec
bucket: architecture
priority: 3
theme: model-cleanup
depends-on: []
---

# Sealed resolution outcomes for catalog table/FK lookups

R78 lifted the catalog-resolution boundary onto typed values (`TableRef`, `ForeignKeyRef`) but stopped short of giving the *failure* side of those resolutions a typed shape. Three R78-adjacent smells share one root cause and want one fix:

1. **`JoinStep.FkJoin.fk` is a documented-nullable model field.** The javadoc admits two regimes ("null when the jOOQ catalog is not available (unit tests)") and 26 test fixtures construct `new JoinStep.FkJoin(name, null, …)`. Four emit sites — `InlineTableFieldEmitter:122`, `InlineLookupTableFieldEmitter:190`, `SplitRowsMethodEmitter:508` and `:839` — dereference `bridging.fk().keysClass()` with no null-check, relying on a classifier guarantee that is not type-encoded. The redundant `String fkName` component exists *because* `fk` can be null; in the typed-fk case `fk.sqlName()` carries the same data. This is the "tri-state with implicit-only-this-state-reaches-here" anti-pattern that *Builder-step results are sealed, not strings or out-params* names directly.

2. **`Optional<TableEntry>` collapses three meaningfully different outcomes into one.** `JooqCatalog.findTable(String)` returns `Optional.empty()` for "name not in catalog", "ambiguous across schemas" (the multi-schema-bug R78 fixed), and "schema package has no `Tables` class". R78 added `findCandidateSchemasFor()` to recover the ambiguity reason; one production caller consumes it (`BuildContext.unknownTableRejection:451` for `@table` directive misses) but the six other `findTable`-empty sites listed below do not. The user-facing diagnostic for `@table(name: "event")` ambiguous between `multischema_a` and `multischema_b` ends up being "table 'event' is not in the jOOQ catalog" rather than "qualify with multischema_a.event or multischema_b.event" at every site that isn't `unknownTableRejection`. The fixture's whole point — exposing the ambiguity branch — only reaches the schema author through one path of seven.

3. **The same "not in catalog" string is fabricated at seven call sites with subtle wording drift.** R78 introduced this as a side-effect of #2: `BuildContext.parsePath:541`, `parsePathElement:681` and `:708`, `synthesizeFkJoin`'s callers, `buildInputNodeIdReference:1006`, the `IdReference` synthesis-shim arm at `:1161`, `NodeIdLeafResolver.resolve:264` and `:409`, and the DML-payload assembly at `FieldBuilder.resolveDmlPayloadAssembly:1642` all do `if (Optional<TableRef>.isEmpty()) { return Rejected("…some variation of 'X is not in the jOOQ catalog'…"); }`. Wording drifts: "touches a table whose schema package is not generated" / "is not in the jOOQ catalog" / "is not a fully resolved table in the jOOQ catalog". *Generation-thinking*: the same predicate evaluated by multiple consumers means the resolver is under-specified.

The unifying fix is one sealed result type at the catalog boundary plus a non-null `ForeignKeyRef` on `FkJoin`:

- `TableResolution.{Resolved(TableEntry) | NotInCatalog | Ambiguous(List<String> schemas) | NoConstantsClass}` returned by `findTable(String)`. Classifiers switch exhaustively; the `Ambiguous` arm carries the candidates straight into a single well-written diagnostic; the `NoConstantsClass` arm captures R78's degenerate-codegen case explicitly instead of folding it into "not in catalog". Drops `findCandidateSchemasFor` as redundant.
- `JoinStep.FkJoin.fk` becomes non-null `ForeignKeyRef`. `BuildContext.synthesizeFkJoin` is already `Optional<FkJoin>` and routes catalog-miss to `UnclassifiedField`; tighten the resolver path to also return empty when `findForeignKeyByName` returns empty (instead of `.orElse(null)` at `BuildContext.java:575`). Drop the redundant `String fkName` component — `fk.sqlName()` carries it. Test fixtures construct synthetic `ForeignKeyRef`s through a new `TestFixtures.foreignKeyRef(...)` factory analogous to R78's `tableRef(...)` factory; the 26 `null`-fk fixture literals across `TypeFetcherGeneratorTest`, `NestingFieldValidationTest`, and the `*FieldValidationTest` family migrate uniformly.
- The seven-site error-string fabrication collapses into one diagnostic builder keyed on the `TableResolution` variant; per-site control flow shrinks to `case Resolved r -> …; case NotInCatalog n -> reject(…); case Ambiguous a -> reject(…); case NoConstantsClass n -> reject(…);`. The existing `BuildContext.unknownTableRejection` builder is the seed: it already does the ambiguous/missing dispatch by re-querying `findCandidateSchemasFor`; the sealed type lets it read straight from the variant.

Drive-by while editing this surface: `ServiceDirectiveResolver.computeExpectedServiceReturnType`'s javadoc at `:246` still describes the `TableBoundReturnType + Single` arm as `<jooqPackage>.tables.records.<TableName>Record`. R78 retired the `jooqPackage` threading; the post-R78 phrasing is `<schemaPackage>.tables.records.<TableName>Record`. One-line fix in the same commit that tightens this surface.

## Implementation

**Catalog boundary (`graphitron/src/main/java/no/sikt/graphitron/rewrite/JooqCatalog.java`).**
- Introduce `sealed interface TableResolution permits Resolved, NotInCatalog, Ambiguous, NoConstantsClass` (record arms; `Resolved` carries `TableEntry`, `Ambiguous` carries `List<String> schemas`).
- `findTable(String)` returns `TableResolution`. The two-arg `findTable(schema, table)` keeps `Optional<TableEntry>` for now (qualified miss is one-armed; only the unqualified resolver fans out into the sub-taxonomy).
- Replace `findUnqualifiedTable`'s `.limit(2).toList() → Optional.of/empty` with the four-way fork: zero matches → `NotInCatalog`; one match → `Resolved`; two-or-more matches → `Ambiguous`; zero schemas with a `Tables` class for the table at all → `NoConstantsClass`.
- Delete `findCandidateSchemasFor(String)`; the `Ambiguous` variant carries the same data inline.
- `findRecordClass` is a one-liner over `findTable`; rewrite as a switch over `TableResolution` so `FieldBuilder.resolveDmlPayloadAssembly` gets the same variant-keyed diagnostic.

**Diagnostic builder.**
- Promote `BuildContext.unknownTableRejection` to a switch over `TableResolution`: `Ambiguous → structural rejection naming the schemas`; `NotInCatalog → unknownTable rejection with Levenshtein candidates`; `NoConstantsClass → distinct rejection naming the schema package`. The current re-query of `findCandidateSchemasFor` at `BuildContext:451` collapses into a direct field read.
- The seven rejection sites listed in smell #3 all go through this builder. Their per-site control flow becomes `switch (catalog.findTable(name)) { case Resolved r -> r.tableEntry(); case ... -> return Unresolved(unknownTableRejection(variant)); }`.

**Model (`graphitron/src/main/java/no/sikt/graphitron/rewrite/model/JoinStep.java`).**
- Drop the `String fkName` component of `FkJoin`; tighten `ForeignKeyRef fk` to non-null. Update the record header javadoc at `JoinStep.java:88-92` (the "{@code null} when the jOOQ catalog is not available" sentence goes away). Four emit-site dereferences (`InlineTableFieldEmitter:122`, `InlineLookupTableFieldEmitter:190`, `SplitRowsMethodEmitter:508`, `:839`) keep their existing call shape; what changes is that the type system now guarantees what they already assume.
- `BuildContext.synthesizeFkJoin:575` replaces `.orElse(null)` with `Optional<FkJoin>`-empty propagation when `findForeignKeyByName` returns empty. The two callers at `BuildContext:537` and `:706` are already `Optional<FkJoin>` consumers; no signature ripple.

**Test fixture migration.**
- New `TestFixtures.foreignKeyRef(String name, String constantName, ClassName keysClass)` factory, mirroring the existing `tableRef(...)` factory at `TestFixtures.java:37`. A short SDL-style helper for the common case (FK name only, synthesise constantName + keysClass from `TEST_JOOQ_ROOT`) keeps the 26 fixture call sites concise.
- Migrate the 26 `new FkJoin(name, null, …)` literals across `TypeFetcherGeneratorTest`, `NestingFieldValidationTest`, `TableFieldValidationTest`, `TableMethodFieldValidationTest`, `SplitTableFieldValidationTest`, `RecordTableFieldValidationTest`, `RecordLookupTableFieldValidationTest`, `ColumnReferenceFieldValidationTest`. Mechanical sweep; no behaviour changes.

**Drive-by.**
- `ServiceDirectiveResolver.java:246`: `<jooqPackage>` → `<schemaPackage>` in the javadoc bullet.

## Tests

- **Unit tier on the sealed type.** Extend `JooqCatalogMultiSchemaTest` to assert the four arms by name (today the test asserts via `findCandidateSchemasFor`; that helper is going away). Drive each arm through the multi-schema fixture: `Resolved` for `widget`, `Ambiguous(["multischema_a", "multischema_b"])` for `event`, `NotInCatalog` for `made_up_name`, `NoConstantsClass` against a synthetic catalog with a schema whose package has no `Tables` class.
- **Diagnostic builder.** A focused unit test on `unknownTableRejection` (or its renamed successor) asserts each variant produces the expected `Rejection` shape — `structural` with the schema list for `Ambiguous`, `unknownTable` with Levenshtein candidates for `NotInCatalog`, a distinct rejection for `NoConstantsClass`.
- **Pipeline tier.** No new pipeline test in this item: the existing pipeline tests that go through the seven rejection sites already cover the wiring. Once R83 lands its multi-schema pipeline coverage, the `Ambiguous` diagnostic gets end-to-end validation for free.
- **Fixture migration.** The 26-fixture sweep is verified by the suites continuing to pass; no new assertions land alongside the migration.

## Out of scope

- Relaxing `synthesizeFkJoin`'s endpoint resolution further (already `Optional<FkJoin>`).
- The multi-schema fixture itself (R78 shipped it; R83 promotes it to higher tiers).
- The two-arg `findTable(schema, table)` overload — qualified miss is one-armed, no sub-taxonomy needed.

Principles cited: *Sub-taxonomies for resolution outcomes*; *Builder-step results are sealed, not strings or out-params*; *Generation-thinking* (rule of thumb: same predicate evaluated by multiple consumers belongs in the model).

