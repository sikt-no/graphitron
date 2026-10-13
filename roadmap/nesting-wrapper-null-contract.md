---
id: R1026
title: "A nullable nesting wrapper whose columns are all null resolves to an object, not null"
status: In Review
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

**Model, `ChildField.NestingField`.** Two derived methods, computed from the components the record already carries (`returnType().wrapper()` and `nestedFields()`), so classification and the fact store are untouched. Wrapper nullability and child classification are not in the store, and `ParentRowDemand.parentRowColumns()` is the precedent for a derived read on the leaf.

```java
/** The subtree's presence leaves, or the child that blocks the collapse; ignores this field's own nullability. */
SubtreePresence subtreePresence()
    // sealed: Leaves(List<PresenceLeaf> leaves) | Blocked(ChildField blocker)

/** The null contract of this field. */
NullContract nullContract()
    // sealed: CollapsesWhenAbsent(List<PresenceLeaf> leaves)
    //       | AlwaysObject(Reason reason)   // NonNull | BlockedBy(ChildField) | NotSingle

record PresenceLeaf(List<ColumnRef> columns)   // present when every column is non-null
```

`subtreePresence()` is the recursion: a Direct `ColumnBackedField` yields a one-column leaf, a `NodeIdEncodeKeys` one yields a leaf over its key columns, an inner `NestingField` contributes its own `subtreePresence()` whatever its nullability (rule 3), and any other child answers `Blocked` with that child (rule 4), which propagates up. `nullContract()` combines it with the wrapper: switch on `returnType().wrapper()` (a list-wrapped nesting field reaches the model and is rejected only by the validator, so the switch answers `AlwaysObject(NotSingle)` rather than casting), then `Single(false)` answers `AlwaysObject(NonNull)`, and `Single(true)` answers `CollapsesWhenAbsent` or `AlwaysObject(BlockedBy)` from the subtree. Carrying the blocking child keeps the Graphitron 9 divergence on the model rather than leaving it to be re-derived. The synthetic pivot nesting field `NestingReach.pivotWiring` mints holds `PivotSlotField` children, so it answers `AlwaysObject(BlockedBy)` and nothing reads it there. The type names are the implementer's; the shape (one nullability-independent recursion, one sealed contract with a reason) is the plan.

**Parent-row demand.** `NestingField` implements `ParentRowDemand`: its fetcher now reads columns off the parent's row, which is exactly that capability. `parentRowColumns()` is the flattened, distinct columns of a `CollapsesWhenAbsent` contract and empty otherwise. Add `NestingField` to the implementer list in `ParentRowDemand`'s javadoc.

**Projection, `ProjectionCommands.contributionFor`.** The `NestingField` arm emits `Contribution.Call(nf.name(), nested, new CallWrap.Splice(), ...)`, and the nested unit projects only the selected subfields, with unselected columns absent from the record (`Record.get(Field)` on an absent field throws). Rule 2 needs the demanded columns in the row whenever the nesting field is selected. A second `Contribution` for the same field would emit a duplicate `case` label, so the demand rides the splice: give `CallWrap.Splice` a component carrying the calling field's parent-row demand (named for that role, since `CallWrap`'s axis is how the callee's fields arrive), filled from `nf.parentRowColumns()`, so supply and demand are one read as the `correlationKeyArm` comment in `contributionFor` asks. `ProjectionUnitRenderer.emitArm`'s `Splice` arm adds those columns as base-name column terms ahead of the callee's fields. Base-name identity is what the nested column leaves already project under, so a demanded column the query also selects lands once (the unit's field set is a `LinkedHashSet`). `PlanCompileGraph`'s `Splice` arm and `requireAliasedWriteArm` stay as they are; no result-key alias is minted. Adding the columns inside the nested unit instead does not work: the unit is keyed on (anchor, nesting type), shared by a nullable and a non-null site of the same type, and the demand belongs to the field.

**Fetcher, `FetcherEmitter.bindRaw`.** The `NestingField` arm stays `sourceOnly` (a `LightFetcher`, no `env`): with the demanded columns always projected, the check needs only the record. On `CollapsesWhenAbsent` it emits one statement per leaf:

```java
Record row = (Record) source;
if (row.get(T.A) != null) return source;
if (row.get(T.B) != null && row.get(T.C) != null) return source;
return null;
```

with `T` the nesting field's table constants (`CatalogRefs.constantsClass(nf.returnType().table())`, the read the column leaves use). On `AlwaysObject` the body stays `return source;`. An inner nesting field's fetcher lives on the shared nested type's `Fetchers` class and reads the representative site's table constants; that is sound because `GraphitronSchemaValidator.compareNestedFieldsShape` requires the same leaf class and column names at every reuse site, which the emitter's javadoc cites with `{@link}`. The `inlineSuccessReturn` `NestingField` arm is unreachable (`NestingField` is classified only on table parents, for which `ParentSourceBinding.of` answers `TableRow`); make it throw rather than carry a second copy of the check.

Update the stale `NestingField` javadoc while there: the record's says the fetcher is `env -> env.getSource()` (its first sentence feeds the generated `docs/manual/_generated/supported-schema-shapes.adoc`), and `domainReturnType()`'s says the children read by column name where they read typed constants.

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

- **Model unit test** on `NestingField.subtreePresence()` and `nullContract()`: columns only (`CollapsesWhenAbsent`, one leaf per column); a `@nodeId` leaf (one leaf over several columns); a non-null inner nesting field and a nullable inner one (both contribute their leaves); a child `TableField`, `ComputedField` and `BatchedTableField` (each `AlwaysObject(BlockedBy)` naming that child, and the same for every enclosing wrapper); a non-null outer (`AlwaysObject(NonNull)`); a list-wrapped nesting field (`AlwaysObject(NotSingle)`, no cast failure). `parentRowColumns()` is the flattened leaves for the collapsing case and empty for the others.
- **Pipeline tier, `NestingFieldPipelineTest`**: the nesting fetcher body carries the per-leaf check for a nullable wrapper and is `return source;` for a non-null one; the parent's projection arm for the nesting field adds the demanded columns. `outerFetcherRegistration_wiresNestingFieldAsLightFetcher` keeps passing unchanged (the fetcher stays source-only).
- **Execution tier**, a dedicated fixture rather than `TranslatedTexts`, which is already a pivot projection type, a nesting type and a `@service` result at once, so a failure there would be ambiguous. Add a plain nesting type over `pivot_nesting_host`'s nullable columns to the sakila example schema, reached twice from `PivotNestingHost`, once nullable and once non-null (say `HostLabels { nn: String  nb: String }` as `labels: HostLabels` and `labelsRequired: HostLabels!`). Seed a second `pivot_nesting_host` row with all four columns null in `graphitron-sakila-db/src/main/resources/init.sql` (the existing row 1 is `('nn-vert','nb-vert',NULL,'en-host')`; `PivotExecutionTest` reads host 1 by position, so check it stays first). Then:
  - host 2 resolves `labels` to `null`;
  - host 2 resolves `labelsRequired` to `{nn: null, nb: null}`, the non-null control;
  - host 1 resolves `labels` to an object;
  - a host whose `nb` is non-null and `nn` null (seed a third row, or reuse one if the seed already has the shape) resolves `labels { nn }` to `{nn: null}`, the selection-independence case (rule 2).

## Implementation notes

Landed as specified. The contract types nest in `ChildField.NestingField` (`PresenceLeaf`, `SubtreePresence.{Leaves, Blocked}`, `NullContract.{CollapsesWhenAbsent, AlwaysObject}` with `Reason.{NonNull, BlockedBy, NotSingle}`). The splice component is `CallWrap.Splice.callerDemand`. Points for the reviewer:

- *The fetcher switches over the contract rather than testing for one arm.* An `instanceof ChildField.NestingField.NullContract.CollapsesWhenAbsent` in `FetcherEmitter` tripped `CommandSeamRatchetTest.leafDispatchSitesInGenerators`, whose regex counts any `instanceof ChildField…` as leaf dispatch. That was a false positive: the line reads the contract and doesn't dispatch on which leaf the field is. `nestingBody` imports `NullContract` and switches exhaustively over it, so the pin is unchanged.
- *The record's first javadoc line is unchanged*, ending in a period the leaf-coverage extractor strips, so `docs/manual/_generated/supported-schema-shapes.adoc` needs no regeneration.
- *The blocking children are tested in two places.* The model unit test (`NestingFieldNullContractTest`) builds the `@externalField` blocker directly. The `@reference` (`TableField`) and `@splitQuery` (`BatchedTableField`) blockers are classified from SDL in `NestingFieldPipelineTest`, which also pins that a blocked wrapper's fetcher stays `return source;`.
- *Existing nesting sites change behaviour.* `PivotNestingHost.texts` (nullable, four column leaves) now collapses too. Host 1 carries values and `PivotExecutionTest` reads only host 1, so nothing there moved. The new hosts 2 and 3 are seeded with explicit ids after it.
- *Execution coverage* is `NestingNullContractExecutionTest` over `PivotNestingHost.labels` / `labelsRequired: HostLabels`, covering all four cases in the Tests section. In the web sandbox the local `rewrite_test` database has to be reseeded after this change (see `.claude/web-environment.md`). Without the reseed these tests fail on the missing host rows, and a stale DML row (film 85) also fails `GraphQLQueryTest.filmsByTitleBean_absentInput_buildsNoBeanAndLeavesTheFieldUnfiltered`.

## Provenance

Reported by the sis Oracle IT run (fs-plattform, branch `sis/upgrade-graphitron`, write-up in `sis/GRAPHITRON10_STATUS.md` § "Third IT run"), on a pre-R1019 snapshot not re-checked on trunk. No manual page states the null contract of a nesting field either way (checked at `1cb2e1b04`). Graphitron 9 (`FetchDBMethodGenerator.createMappingFunction` at tag `v9.3.0`) mapped every nested object with jOOQ's `Functions.nullOnAllNull`, over all the type's fields, and used a plain constructor for a non-null nested field.
