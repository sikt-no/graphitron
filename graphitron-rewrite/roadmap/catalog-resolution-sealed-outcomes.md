---
id: R81
title: "Sealed resolution outcomes for catalog table/FK lookups"
status: Backlog
bucket: architecture
priority: 3
theme: model-cleanup
depends-on: []
---

# Sealed resolution outcomes for catalog table/FK lookups

R78 lifted the catalog-resolution boundary onto typed values (`TableRef`, `ForeignKeyRef`) but stopped short of giving the *failure* side of those resolutions a typed shape. Three R78-adjacent smells share one root cause and want one fix:

1. **`JoinStep.FkJoin.fk` is a documented-nullable model field.** The javadoc admits two regimes ("null when the jOOQ catalog is not available (unit tests)") and 24 test fixtures construct `new JoinStep.FkJoin(name, null, …)`. Four emit sites — `InlineTableFieldEmitter:122`, `InlineLookupTableFieldEmitter:190`, `SplitRowsMethodEmitter:486` and `:802` — dereference `bridging.fk().keysClass()` with no null-check, relying on a classifier guarantee that is not type-encoded. The redundant `String fkName` component exists *because* `fk` can be null; in the typed-fk case `fk.sqlName()` carries the same data. This is the "tri-state with implicit-only-this-state-reaches-here" anti-pattern that *Builder-step results are sealed, not strings or out-params* names directly.

2. **`Optional<TableEntry>` collapses three meaningfully different outcomes into one.** `JooqCatalog.findTable(String)` returns `Optional.empty()` for "name not in catalog", "ambiguous across schemas" (the multi-schema-bug R78 fixed), and "schema package has no `Tables` class". R78 added `findCandidateSchemasFor()` to recover the ambiguity reason, but no production code consumes it; only `JooqCatalogMultiSchemaTest` does. The user-facing diagnostic for `@table(name: "event")` ambiguous between `multischema_a` and `multischema_b` ends up being "table 'event' is not in the jOOQ catalog" rather than "qualify with multischema_a.event or multischema_b.event". The fixture's whole point — exposing the ambiguity branch — has no consumer wired through to the schema author.

3. **The same "not in catalog" string is fabricated at six call sites with subtle wording drift.** R78 introduced this as a side-effect of #2: `BuildContext.synthesizeFkJoin` (×3 sites), `parsePathInferred`, `parsePathElement`, `buildInputNodeIdReference`, and `NodeIdLeafResolver.resolveJoinPath` all do `if (Optional<TableRef>.isEmpty()) { return Rejected("…some variation of 'X is not in the jOOQ catalog'…"); }`. Wording drifts: "touches a table whose schema package is not generated" / "is not in the jOOQ catalog" / "is not a fully resolved table in the jOOQ catalog". *Generation-thinking*: the same predicate evaluated by multiple consumers means the resolver is under-specified.

The unifying fix is one sealed result type at the catalog boundary plus a non-null `ForeignKeyRef` on `FkJoin`:

- `TableResolution.{Resolved(TableEntry) | NotInCatalog | Ambiguous(List<String> schemas) | NoConstantsClass}` returned by `findTable(String)`. Classifiers switch exhaustively; the `Ambiguous` arm carries the candidates straight into a single well-written diagnostic; the `NoConstantsClass` arm captures R78's degenerate-codegen case explicitly instead of folding it into "not in catalog". Drops `findCandidateSchemasFor` as redundant.
- `JoinStep.FkJoin.fk` becomes non-null `ForeignKeyRef`. `BuildContext.synthesizeFkJoin` is already `Optional<FkJoin>` and routes catalog-miss to `UnclassifiedField`; tighten the resolver path to also return empty when `findForeignKeyByName` returns empty (instead of `.orElse(null)` at `BuildContext.java:542`). Drop the redundant `String fkName` component — `fk.sqlName()` carries it. Test fixtures construct synthetic `ForeignKeyRef`s through a new `TestFixtures.foreignKeyRef(...)` factory analogous to R78's `tableRef(...)` factory; the 24 `null` literals across `TypeFetcherGeneratorTest`, `NestingFieldValidationTest`, and the `*FieldValidationTest` family migrate uniformly.
- The six-site error-string fabrication collapses into one diagnostic builder keyed on the `TableResolution` variant; per-site control flow shrinks to `case Resolved r -> …; case NotInCatalog n -> reject(…); case Ambiguous a -> reject(…); case NoConstantsClass n -> reject(…);`.

Drive-by while editing this surface: `ServiceDirectiveResolver.computeExpectedServiceReturnType`'s javadoc still describes the `TableBoundReturnType + Single` arm as `<jooqPackage>.tables.records.<TableName>Record`. R78 retired the `jooqPackage` threading; the post-R78 phrasing is `<schemaPackage>.tables.records.<TableName>Record`. One-line fix in the same commit that tightens this surface.

Out of scope: relaxing `synthesizeFkJoin`'s endpoint resolution further (already `Optional<FkJoin>`); the multi-schema fixture itself (R78 ships it). This item rides the fixture R78 lands.

Principles cited: *Sub-taxonomies for resolution outcomes*; *Builder-step results are sealed, not strings or out-params*; *Generation-thinking* (rule of thumb: same predicate evaluated by multiple consumers belongs in the model).

