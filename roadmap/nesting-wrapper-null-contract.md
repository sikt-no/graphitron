---
id: R1026
title: "A nullable nesting wrapper whose columns are all null resolves to an object, not null"
status: Spec
bucket: bug
priority: 3
theme: codegen-correctness
depends-on: []
created: 2026-10-13
last-updated: 2026-10-13
---

# A nullable nesting wrapper whose columns are all null resolves to an object, not null

## Goal

A nullable field that returns a *nesting type* (an object type without `@table` whose fields read columns of the enclosing `@table` type's row) resolves to `null` when every value the wrapper would carry is null, as Graphitron 9 did, and the manual says so. Today it always resolves to an object, so a wrapper with nothing in it reaches the client as `{antall: null, tidsenhet: null}`. A client that tests `varighet != null` breaks on migration, and nothing in the manual says the behaviour changed.

From sis:

```graphql
type EmneUndervisningsoversikt @table(name: "EMNE") {
    varighet: Emneundervisningsvarighet
}
type Emneundervisningsvarighet {
    antall: Int @field(name: "TALL_VARIGHET_UND")
    tidsenhet: Tidsenhet
}
```

For an emne with no duration, Graphitron 9 returns `"varighet": null`; Graphitron 10 returns `"varighet": {"antall": null, "tidsenhet": null}`. After this item both return `null`. A non-null `varighet: Emneundervisningsvarighet!` keeps resolving to an object, as it did in Graphitron 9. sis's `EmnerQueryIT.fetchEmnerQuery` (asserting every present `varighet.antall` is an `Integer`) and the `studieoppbygningQuery` approval (`maksimumsvekting: null` became `{verdi: null}`, `beskrivelseAlleSprak: null` became `{en: null, nb: null, nn: null}`) are the consumer-side reproductions.

The rule is restored for the wrappers whose contents are columns of the parent row, which covers every shape sis reported. A wrapper that also contains a field fetched some other way (a `@reference` to another table, an `@externalField`, a `@splitQuery` child) always resolves to an object, and the migration guide names that as a divergence from Graphitron 9.

## The contract

Terms used below. The *wrapper* is the nesting type's value on one row. A *column leaf* is a wrapper field whose value is one or more columns of the parent row read as-is: a plain or `@field`-renamed column, an enum-mapped column, or a `@nodeId` field encoding key columns. The *presence leaves* of a wrapper are its column leaves plus, recursively, the column leaves of every nesting field inside it.

1. **Nullable wrapper, columns only.** A nullable nesting field whose wrapper (recursively) contains only column leaves and nesting fields resolves to `null` exactly when every presence leaf is null. A column leaf over one column is null when the column is; a `@nodeId` leaf is null when any of its key columns is, because the generated `NodeIdEncoder.encode` returns `null` on any null value. So "every leaf null" is evaluated per leaf, not per column.
2. **Independent of the selection.** Presence is decided over all presence leaves, not the ones the query selects. `{ varighet { antall } }` and `{ varighet { tidsenhet } }` agree on whether `varighet` is null for the same row. A field's nullness that depends on its own sub-selection would corrupt normalized client caches and differ from Graphitron 9, which built the wrapper from all its fields.
3. **Non-null wrapper.** A `T!` nesting field always resolves to an object. Its leaves still count toward an enclosing nullable wrapper's presence (rule 1 recurses through it), which matches Graphitron 9's treatment of an all-null non-null inner object as null-equivalent for the outer one.
4. **Other children block the collapse.** A wrapper containing any field that is not a column leaf or a nesting field always resolves to an object: a `@reference` object or list (an inline correlated multiset), a correlated scalar `@reference` column, an `@externalField`, a `@splitQuery` child. Their values are either never null (a list, a batch key) or cost a subquery the client did not select, and dropping a non-null value to collapse the wrapper would lose data. A nesting field whose subtree contains such a child is itself blocked, and blocks every enclosing wrapper.

Rule 4 is the divergence from Graphitron 9, which counted a single `@reference` object and a correlated subquery toward the collapse. sis's reported wrappers contain no such child.

## Implementation

**Model, `ChildField.NestingField`.** Add a derived method, computed from the components the record already carries (`returnType().wrapper()` and `nestedFields()`), so classification and the fact store are untouched:

```java
/** Null when every presence leaf is null; empty when the wrapper always resolves to an object. */
Optional<List<List<ColumnRef>>> presenceLeaves()
```

Each inner list is one leaf's columns (a Direct `ColumnBackedField` contributes its one column, a `NodeIdEncodeKeys` one contributes its key columns); a leaf is present when all its columns are non-null. The method answers empty when `((FieldWrapper.Single) returnType().wrapper()).nullable()` is false, or when any child in the subtree is neither a `ColumnBackedField` nor a `NestingField` (rule 4). A non-null inner `NestingField` contributes its subtree's leaves (rule 3), and so does a nullable inner one that is not blocked. The method is the one home of the rule: the projection and the fetcher both read it. The synthetic pivot nesting field `NestingReach.pivotWiring` mints holds `PivotSlotField` children, so it answers empty and nothing reads it there.

Update the `NestingField` javadoc while there: it still says the fetcher is `env -> env.getSource()`, and its first sentence feeds the generated `docs/manual/_generated/supported-schema-shapes.adoc`.

**Projection, `ProjectionCommands.contributionFor`.** The `NestingField` arm emits `Contribution.Call(nf.name(), nested, new CallWrap.Splice(), ...)`, and the nested unit projects only the selected subfields, with unselected columns absent from the record (`Record.get(Field)` on an absent field throws). Rule 2 needs the presence columns in the row whenever the nesting field is selected. Give `CallWrap.Splice` a `List<ColumnRef> presenceColumns` component (the flattened, distinct columns of `presenceLeaves()`, empty when it is empty), and have `ProjectionUnitRenderer.emitArm`'s `Splice` arm add them as `SelectTerm.Column(col, TermAlias.BY_COLUMN_IDENTITY)` ahead of the callee's fields. Base-name identity is what the nested column leaves already project under, so a presence column the query also selects lands once (the unit's field set de-duplicates). `PlanCompileGraph`'s `Splice` arm and `requireAliasedWriteArm` stay as they are; no result-key alias is minted.

The alternative of adding the presence columns inside the nested unit's `$project` unconditionally works too, but the nested unit is shared across the reuse sites of the same nesting type under one anchor and the decision belongs to the field (a nullable and a non-null site can share the type), so the call site carries it.

**Fetcher, `FetcherEmitter.bindRaw`.** The `NestingField` arm stays `sourceOnly` (a `LightFetcher`, no `env`): with the presence columns always projected, the check needs only the record. When `presenceLeaves()` is present it emits

```java
Record r = (Record) source;
return (r.get(T.A) != null) || (r.get(T.B) != null && r.get(T.C) != null) ? source : null;
```

with `T` the nesting field's table constants (`CatalogRefs.constantsClass(nf.returnType().table())`, the same read the column leaves use), one disjunct per leaf. When it is empty the body stays `return source;`. The `inlineSuccessReturn` `NestingField` arm is unreachable for nesting (`ParentSourceBinding.of` answers `TableRow` for a table parent); leave it, or make it throw if the implementer confirms that, but do not add a second copy of the check.

**Docs.**

- `docs/manual/how-to/map-types-to-tables.adoc` gains a section, drafted below.
- `docs/manual/how-to/migrating-from-legacy.adoc`: one line under "Things that didn't change" for the restored rule, and a short "Behavior divergence" entry for rule 4, modelled on the `@condition` cascade section.

## User documentation (first-client check)

Draft section for `map-types-to-tables.adoc`, after "Re-binding and the table-context cascade":

> == Grouping columns into a nested object
>
> An object type without `@table`, returned by a field of a `@table` type, groups columns of the parent's row under one field. It is not a join: its fields read the parent's columns.
>
> ```graphql
> type Film @table {
>     release: FilmRelease
> }
> type FilmRelease {
>     year: Int @field(name: "RELEASE_YEAR")
>     minutes: Int @field(name: "LENGTH")
> }
> ```
>
> When the field is nullable and every field of the nested type would be null, the field resolves to `null` rather than to an object of nulls. This is decided over all the nested type's fields, whatever the query selects, so `release { year }` and `release { minutes }` agree on whether `release` is null. Declare the field non-null (`release: FilmRelease!`) to always get an object.
>
> A nested type that also holds a field fetched another way, such as an `@reference` to another table, an `@externalField`, or a `@splitQuery` field, always resolves to an object.

## Tests

- **Model unit test** on `NestingField.presenceLeaves()`: columns only (present), a `@nodeId` leaf (one leaf of several columns), a non-null inner nesting field (contributes leaves), a nullable inner nesting field (contributes leaves), a child `TableField` / `ComputedField` / `BatchedTableField` (empty, and empty for every enclosing wrapper), non-null outer (empty).
- **Pipeline tier, `NestingFieldPipelineTest`**: the nesting fetcher body carries the presence check for a nullable wrapper and is `return source;` for a non-null one; the parent's projection arm for the nesting field adds the presence columns. `outerFetcherRegistration_wiresNestingFieldAsLightFetcher` keeps passing unchanged (the fetcher stays source-only).
- **Execution tier, `PivotExecutionTest` or a sibling** over `PivotNestingHost.texts: TranslatedTexts` (four nullable columns `nn nb se en`). Seed a second `pivot_nesting_host` row with all four columns null in `graphitron-sakila-db/src/main/resources/init.sql` (the existing row 1 is `('nn-vert','nb-vert',NULL,'en-host')`; the existing test reads host 1 by position, so check it stays first). Then:
  - host 2 resolves `texts` to `null`;
  - host 1 resolves `texts { se }` to `{se: null}`, the selection-independence case (rule 2);
  - a non-null control: add `textsRequired: TranslatedTexts!` (or a similarly named field) to `PivotNestingHost`; host 2 resolves it to an object of nulls.

## Provenance

Reported by the sis Oracle IT run (fs-plattform, branch `sis/upgrade-graphitron`, write-up in `sis/GRAPHITRON10_STATUS.md` § "Third IT run"), on a pre-R1019 snapshot not re-checked on trunk. No manual page states the null contract of a nesting field either way (checked at `1cb2e1b04`). Graphitron 9 (`FetchDBMethodGenerator.createMappingFunction` at tag `v9.3.0`) mapped every nested object with jOOQ's `Functions.nullOnAllNull`, over all the type's fields, and used a plain constructor for a non-null nested field.
