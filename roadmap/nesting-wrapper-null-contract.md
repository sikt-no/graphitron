---
id: R1026
title: "A nullable nesting wrapper whose columns are all null resolves to an object, not null"
status: In Progress
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

The rule is restored for wrappers whose contents are columns of the parent row, or single references joined from columns of the parent row. That covers both reported sis wrappers: `tidsenhet` above joins from `EMNE.TIDSENHET_VARIGHET_UND`, and `maksimumsvekting.vektingstype` from `EMNEKOMBINASJON.VEKTTYPEKODE_MAX`. A wrapper that also contains a list, an `@externalField` or a `@splitQuery` child always resolves to an object, and the migration guide names that as a divergence from Graphitron 9.

## The contract

Terms used below. The *wrapper* is the nesting type's value on one row. A *column leaf* is a wrapper field whose value is one or more columns of the parent row read as-is: a plain or `@field`-renamed column, an enum-mapped column, or a `@nodeId` field encoding key columns. A *reference leaf* is a wrapper field reached through a reference whose value is a single row or a single value (a `@reference` or implicit-FK object field, or a scalar read through `@reference`), and whose path's first join step pairs columns of the parent row with columns of the next table (an FK or name-matched key, not an authored `condition:` predicate). Its *join columns* are those parent-row columns. The *presence leaves* of a wrapper are its column leaves and reference leaves plus, recursively, those of every nesting field inside it.

1. **Nullable wrapper, columns and parent-keyed references only.** A nullable nesting field whose wrapper (recursively) contains only column leaves, reference leaves and nesting fields resolves to `null` exactly when every presence leaf is null. A column leaf over one column is null when the column is; a `@nodeId` leaf is null when any of its key columns is, because the generated `NodeIdEncoder.encode` returns `null` on any null value. So "every leaf null" is evaluated per leaf, not per column. A reference leaf counts as null when any of its join columns is null: SQL equality never matches a null, so the first join finds no row and the reference resolves to `null` whatever the rest of its path. The check reads the parent row, never the reference's subquery.
2. **Independent of the selection.** Presence is decided over all presence leaves, not the ones the query selects. `{ varighet { antall } }` and `{ varighet { tidsenhet } }` agree on whether `varighet` is null for the same row. A field's nullness that depends on its own sub-selection would corrupt normalized client caches and differ from Graphitron 9, which built the wrapper from all its fields.
3. **Non-null wrapper.** A `T!` nesting field always resolves to an object. Its leaves still count toward an enclosing nullable wrapper's presence (rule 1 recurses through it), which matches Graphitron 9's treatment of an all-null non-null inner object as null-equivalent for the outer one.
4. **Other children block the collapse.** A wrapper containing any field that is not a column leaf, a reference leaf or a nesting field always resolves to an object. That covers a list reference, an interface or union reference, a reference whose first step joins on an authored predicate or laterally to a routine, an `@externalField`, and a `@splitQuery` child. Their values are either never null (a list, a batch key) or can only be known by running a subquery the client did not select, and dropping a non-null value to collapse the wrapper would lose data. A nesting field whose subtree contains such a child is itself blocked, and blocks every enclosing wrapper.

A reference leaf errs in one direction only. When its join columns are all non-null, the leaf counts as present even if the reference still resolves to `null`: a `condition:` on the reference or a later step filtered the row out, an unenforced FK has no target row, or the first step enters from the far end of an FK, so the join columns are the parent's own key. The wrapper then stays an object where Graphitron 9 returned `null`. It is never null where a value exists.

The divergences from Graphitron 9 are rule 4 and the paragraph above. Graphitron 9 counted every single `@reference` object and every correlated subquery toward the collapse, by its actual value.

## Implementation

The first round shipped at `a3cadd6c8` (the subsections below, through **Docs**) and covered column leaves only. Its review was cut short when the sis IT run showed both reported wrappers still pass through, because each holds a reference. The **Amendment** section that follows is the delta still to build on top of the shipped code.

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

### Amendment: reference leaves

**Model, `NestingField.subtreePresence()`.** Two new arms, ahead of the `default -> Blocked`:

- `TableField tf` whose `returnType().wrapper()` is `FieldWrapper.Single` yields a reference leaf.
- `ColumnBackedReferenceField crf` yields a reference leaf. It is single-valued by construction, and arity is a column count, not cardinality.

Both read the join columns through one shared helper over `joinPath()`. When `joinPath().getFirst()` is a `JoinStep.Hop` whose `on()` is `On.ColumnPairs cp`, the leaf is `new PresenceLeaf(cp.sourceSideColumns())`. Otherwise the field answers `Blocked` with that child: an `On.Predicate` or `On.Lateral` first step, or an empty path. `PresenceLeaf` already means "present when every column is non-null", which is the reference-leaf rule, so the record does not change. `On.ColumnPairs.slots` are oriented at synthesis so that `sourceSide()` is on the hop's origin table, and hop 0's origin is the parent table, so the columns are parent-row columns whichever end of the FK the parent holds. Neither `Keying` arm needs a case: the null argument holds for any equality pairing. A list `TableField`, `TableInterfaceField`, the batched variants and `ParticipantColumnReferenceField` keep falling through to `Blocked`. Interface and participant references are left out because no reported shape needs them, not because they would be unsound.

Two things follow without further code. `parentRowColumns()` already flattens the leaves, so the join columns ride `CallWrap.Splice.callerDemand` into the anchor's projection as base-name parent-table columns. `FetcherEmitter.nestingBody` already emits one `row.get(T.COL) != null` conjunction per leaf against the parent's table constants. Pin both in the pipeline tier (below) rather than assuming them, because these are the first leaves whose columns come from a `JoinStep` rather than from a column leaf's own `columns()`.

**Shared nested types, `GraphitronSchemaValidator.compareNestedFieldsShape`.** An inner nesting field's fetcher is emitted once, on the shared nested type's `Fetchers` class, from the representative site's contract and table constants. Column leaves are already safe there because the comparison requires equal `sqlName`s. A reference child's `joinPath` is deliberately not compared (the `TableField` arm says why), so two anchors could reach the same inner coordinate with join columns of different names. In the `NestingField`-recursion arm, when either side's `nullContract()` is `CollapsesWhenAbsent`, require both sides to answer `CollapsesWhenAbsent` with equal leaf column `sqlName` lists, and reject structurally otherwise, naming both parents and both column lists. Outer nesting fields are per-parent coordinates with their own fetchers, so only the recursion arm needs the check.

*Fork for the reviewer:* this is a new rejection for a shape that passes today (as an always-object wrapper): a nested type reused across `@table` parents whose *inner* nullable nesting type holds a reference joined from differently named columns. The alternative, emitting `return source;` for such a coordinate, needs the emitter to see every reuse site, and today it sees only the representative. I recommend the rejection, with a message that says to split the nested type. It is the same constraint the column-leaf comparison already imposes, and sis does not have the shape. Only 3 of its nesting types are reused across `@table` parents (`SpesialpensumTittel`, `EmnenavnAlleSprak`, `EmnenavnForkortetAlleSprak`), and all three are column-only.

**Javadoc.** `subtreePresence()` and `PresenceLeaf` name the reference leaf and the one-directional error. Keep the record's first javadoc line unchanged, as round 1 did (it feeds `supported-schema-shapes.adoc`).

**Docs.** Rewrite round 1's shipped text in `map-types-to-tables.adoc` and `migrating-from-legacy.adoc` to match the draft below. The shipped text says any `@reference` keeps the wrapper an object, which is no longer true. The migration divergence entry now names the blocked kinds (list, `@splitQuery`, `@externalField`, predicate-joined reference) plus the stays-an-object case for a conditioned or reverse-FK reference.

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
> A field of the nested type that follows a reference to a single row of another table also counts, through the parent's columns it joins from: when any of those columns is null the reference is null too. A reference with a `condition:`, or one that enters from the far end of a foreign key, can still be null when those columns are not, and then the nested object stays an object.
>
> A nested type that also holds a list, an `@externalField`, a `@splitQuery` field, or a reference joined by a `condition:` predicate rather than a key always resolves to an object.

## Tests

- **Model unit test** on `NestingField.subtreePresence()` and `nullContract()`: columns only (`CollapsesWhenAbsent`, one leaf per column); a `@nodeId` leaf (one leaf over several columns); a non-null inner nesting field and a nullable inner one (both contribute their leaves); a child `TableField`, `ComputedField` and `BatchedTableField` (each `AlwaysObject(BlockedBy)` naming that child, and the same for every enclosing wrapper); a non-null outer (`AlwaysObject(NonNull)`); a list-wrapped nesting field (`AlwaysObject(NotSingle)`, no cast failure). `parentRowColumns()` is the flattened leaves for the collapsing case and empty for the others.
- **Pipeline tier, `NestingFieldPipelineTest`**: the nesting fetcher body carries the per-leaf check for a nullable wrapper and is `return source;` for a non-null one; the parent's projection arm for the nesting field adds the demanded columns. `outerFetcherRegistration_wiresNestingFieldAsLightFetcher` keeps passing unchanged (the fetcher stays source-only).
- **Execution tier**, a dedicated fixture rather than `TranslatedTexts`, which is already a pivot projection type, a nesting type and a `@service` result at once, so a failure there would be ambiguous. Add a plain nesting type over `pivot_nesting_host`'s nullable columns to the sakila example schema, reached twice from `PivotNestingHost`, once nullable and once non-null (say `HostLabels { nn: String  nb: String }` as `labels: HostLabels` and `labelsRequired: HostLabels!`). Seed a second `pivot_nesting_host` row with all four columns null in `graphitron-sakila-db/src/main/resources/init.sql` (the existing row 1 is `('nn-vert','nb-vert',NULL,'en-host')`; `PivotExecutionTest` reads host 1 by position, so check it stays first). Then:
  - host 2 resolves `labels` to `null`;
  - host 2 resolves `labelsRequired` to `{nn: null, nb: null}`, the non-null control;
  - host 1 resolves `labels` to an object;
  - a host whose `nb` is non-null and `nn` null (seed a third row, or reuse one if the seed already has the shape) resolves `labels { nn }` to `{nn: null}`, the selection-independence case (rule 2).

The cases above shipped in round 1. The amendment adds:

- **Model unit test** (`NestingFieldNullContractTest`):
  - A single `TableField` with an FK first step yields one leaf over `sourceSideColumns()`, and so does a composite FK, as one leaf over all its columns.
  - A list `TableField` is `Blocked`, and so is a `TableField` whose first step is `On.Predicate`.
  - A multi-hop `ColumnBackedReferenceField` yields a leaf over hop 0's source columns only.
  - A reference leaf nested in an inner nesting field reaches the outer contract.
  - `parentRowColumns()` includes the join columns.
- **Pipeline tier** (`NestingFieldPipelineTest`): the round-1 test that pins a `@reference` (`TableField`) blocker uses a single FK reference, which now collapses, so repoint it at a list reference. New SDL cases:
  - A nullable wrapper over a column and a single FK reference: the fetcher checks the FK column on the parent's table constants, and the parent's splice arm projects that FK column.
  - A scalar through a two-hop `@reference`: the check reads only the first hop's parent column.
  - A reference entering from the far end of an FK: the leaf is the parent's key column, and the wrapper never collapses.
  - The shared-nested-type rejection, with differently named join columns at two anchors, plus its negative: same-named join columns at both anchors pass.
- **Execution tier.** Add a nullable `language_id int REFERENCES language(language_id)` column to `pivot_nesting_host`, set on host 1 and null on hosts 2 and 3. Add a nesting type reached as `origin: HostOrigin` on `PivotNestingHost`, holding `language: Language` (implicit FK) and `languageName: String @field(name: "NAME") @reference(...)` (scalar through a reference). Then:
  - host 2 resolves `origin` to `null`;
  - host 1 resolves `origin` to an object with both fields set;
  - `origin { languageName }` and `origin { language { name } }` agree on host 1 and host 2 (rule 2 through a reference leaf).

  Keep `HostLabels` as it is so the round-1 cases stay column-only. Check that nothing reads `pivot_nesting_host` with `SELECT *` or by column position.

## Implementation notes

### Round 1 (`a3cadd6c8`)

Landed as specified. The contract types nest in `ChildField.NestingField` (`PresenceLeaf`, `SubtreePresence.{Leaves, Blocked}`, `NullContract.{CollapsesWhenAbsent, AlwaysObject}` with `Reason.{NonNull, BlockedBy, NotSingle}`). The splice component is `CallWrap.Splice.callerDemand`. Points for the reviewer:

- *The fetcher switches over the contract rather than testing for one arm.* An `instanceof ChildField.NestingField.NullContract.CollapsesWhenAbsent` in `FetcherEmitter` tripped `CommandSeamRatchetTest.leafDispatchSitesInGenerators`, whose regex counts any `instanceof ChildField…` as leaf dispatch. That was a false positive: the line reads the contract and doesn't dispatch on which leaf the field is. `nestingBody` imports `NullContract` and switches exhaustively over it, so the pin is unchanged.
- *The record's first javadoc line is unchanged*, ending in a period the leaf-coverage extractor strips, so `docs/manual/_generated/supported-schema-shapes.adoc` needs no regeneration.
- *The blocking children are tested in two places.* The model unit test (`NestingFieldNullContractTest`) builds the `@externalField` blocker directly. The `@reference` (`TableField`) and `@splitQuery` (`BatchedTableField`) blockers are classified from SDL in `NestingFieldPipelineTest`, which also pins that a blocked wrapper's fetcher stays `return source;`.
- *Existing nesting sites change behaviour.* `PivotNestingHost.texts` (nullable, four column leaves) now collapses too. Host 1 carries values and `PivotExecutionTest` reads only host 1, so nothing there moved. The new hosts 2 and 3 are seeded with explicit ids after it.
- *Execution coverage* is `NestingNullContractExecutionTest` over `PivotNestingHost.labels` / `labelsRequired: HostLabels`, covering all four cases in the Tests section. In the web sandbox the local `rewrite_test` database has to be reseeded after this change (see `.claude/web-environment.md`). Without the reseed these tests fail on the missing host rows, and a stale DML row (film 85) also fails `GraphQLQueryTest.filmsByTitleBean_absentInput_buildsNoBeanAndLeavesTheFieldUnfiltered`.

### Round 2: reference leaves

Landed as the Amendment specifies. Points for the reviewer:

- *One helper, two arms.* `NestingField.subtreePresence()` gains a `TableField` arm guarded on `FieldWrapper.Single` and a `ColumnBackedReferenceField` arm, both through the private `referenceLeaf(joinPath)`. An empty path, `On.Predicate` and `On.Lateral` first hops answer `Blocked` with that child. `Hop.filter()` is deliberately not read: a hop-0 `condition:` is the stays-an-object case, not a blocker.
- *No emitter or projection change.* `parentRowColumns()` and `FetcherEmitter.nestingBody` picked the join columns up as they were. The pipeline tier pins both for an FK reference (`singleFkReference_fetcherChecksTheJoinColumnAndTheSpliceProjectsIt`) and pins the exact fetcher body for a two-hop scalar `@reference`, which reads only `ADDRESS.CITY_ID`.
- *The validator fork took the recommended branch.* `compareNestedFieldsShape`'s `NestingField` arm now calls `comparePresenceColumns`, which rejects structurally when the two sites' per-leaf `sqlName` lists differ, and says to split the nested type. It runs only when the recursion added no error, so a divergent inner column is not reported twice. The fixture is `content.film_id` against `film_endorsement.endorsed_film` through an implicit FK; `content` against `inventory` (both `film_id`) is the passing negative.
- *Round-1 test repointed.* `referenceChild_blocksTheCollapse` became `listReferenceChild_blocksTheCollapse`, over `[Actor!]!` through `film_actor`.
- *Reverse FK.* `reverseFkReference_leafIsTheParentsOwnKey` asserts only that the leaf is `language.language_id`; the wrapper therefore never collapses on a fetched row, which the contract allows.
- *Execution coverage.* `pivot_nesting_host.language_id` (host 1 = 2, Italian; hosts 2 and 3 null) and `PivotNestingHost.origin: HostOrigin` holding `language: Language` and `languageName` through `pivot_nesting_host_language_id_fkey`. Three new cases in `NestingNullContractExecutionTest`. Nothing reads `pivot_nesting_host` by position or with `SELECT *`. In the web sandbox, reseed `rewrite_test` as for round 1.

## Provenance

Reported by the sis Oracle IT run (fs-plattform, branch `sis/upgrade-graphitron`, write-up in `sis/GRAPHITRON10_STATUS.md` § "Third IT run"), on a pre-R1019 snapshot not re-checked on trunk. No manual page states the null contract of a nesting field either way (checked at `1cb2e1b04`). Graphitron 9 (`FetchDBMethodGenerator.createMappingFunction` at tag `v9.3.0`) mapped every nested object with jOOQ's `Functions.nullOnAllNull`, over all the type's fields, and used a plain constructor for a non-null nested field.

Round 1's spec claimed sis's reported wrappers held no reference. They both do (`Emneundervisningsvarighet.tidsenhet`, `MaksimumVektingForStudieoppbygningsdel.vektingstype`). The sis IT run against `a3cadd6c8` caught this: 207 sis wrappers got the presence check, but both reported ones still emitted `return source;`. A script over sis's generated fetchers, its SDL and its jOOQ `Keys.java` sized the amendment against the 113 wrappers still passing through:

- 69 become collapsible. Every non-column field in them is a single reference whose first step is a forward FK from the parent row, with no hop-0 condition. 62 hold only object references, including both reported cases; 7 hold a scalar read through `@reference`, 5 of them multi-hop.
- 30 are non-null, so nothing changes for them.
- 13 stay blocked: 10 by `@splitQuery` and 3 by `@externalField`.
- 1 (`Termin.studiesemesterPeriode`) has a reverse FK at hop 0 and stays an object.

None of them holds a list reference.
