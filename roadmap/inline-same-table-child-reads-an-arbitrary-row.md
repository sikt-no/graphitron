---
id: R1045
title: "An inline child bound to its parent's own table with no @reference reads the whole table, so a single-cardinality one returns an arbitrary row"
status: Ready
bucket: bug
priority: 1
theme: classification-model
depends-on: []
created: 2026-10-15
last-updated: 2026-10-20
---

# An inline child bound to its parent's own table with no @reference reads the whole table, so a single-cardinality one returns an arbitrary row

## Goal

A child field whose return type is bound to the same table as its parent, with no `@reference` and no `@splitQuery`, answers a question the author can state. A single-cardinality field with no arguments reads the parent's own row, as it did in Graphitron 9. A field narrowed by its own arguments or `@condition` keeps today's documented read of the table, independent of the parent. A field that is neither, which today reads the whole table under every parent, is rejected at build time. Today every one of these shapes reads the table without any correlation to the parent row, so `FeideBruker.personProfil` below returns the same arbitrary person for every `FeideBruker`, through `_entities` too, with no build error.

```graphql
# sis, production: both types are bound to PERSON, and PersonProfil stays @table
# because other fields use it as a table type.
# (key and column fields elided; only the shape is quoted)
type FeideBruker @key(...) @table(name: "PERSON") {
  personProfil: PersonProfil      # today: limit 1 over all of PERSON, same row for every parent
}                                 # after: this FeideBruker's own PERSON row

type PersonProfil @table(name: "PERSON") {
  # the person's name columns
}
```

The minimal pair is the narrowed form, which keeps its meaning and becomes documented:

```graphql
type Bar @table(name: "bar") {
  related(filter: BarFilter): [Bar!]   # rows of bar matching the filter, the same for every Bar
  everyBar: [Bar!]                     # rejected: reads all of bar under every Bar
}
```

## Evidence

- The shape classifies as an inline `ChildField.TableField` with an empty join path. `BuildContext.parsePath` infers a foreign key only between two different tables, so a same-table field with no `@reference` gets no path, and `BuildContext.buildParentCorrelation` returns no correlation (the parent-to-child key equation a child read is filtered on). `ProjectionUnitRenderer.multisetInnerSelect` renders that as `multiset(select ... from t0 where DSL.noCondition() [.and(glue)] [.limit(1)])`, where the glue is the field's argument-driven filter. Its comment in `multisetArmBody` calls the arm "a conditions-only correlated subquery"; it is uncorrelated.
- The `@splitQuery` twin of this shape is already rejected (`FieldBuilder.uncorrelatedSameTableSplitMessage`, `SplitQuerySameTableRejectionPipelineTest`), and its third remedy, "drop `@splitQuery`", sends authors straight onto the inline shape. Graphitron 9 authors porting a same-row field land here.
- The one fixture exercising the uncorrelated arm, `NodeIdReferenceFilterPipelineTest.inlineReferenceField_conditionOnlyFilter_emptyJoinPath_standaloneSubquery`, asserts only that rendering does not throw. `SplitQuerySameTableRejectionPipelineTest.inlineTwin_keepsStandaloneShape` pins the classification (`Film.details: FilmDetails`, both on `film`) and nothing about rows.
- Production, sis v9 against v10 differential test (2026-10-19). `Resultat.kandidatinformasjon` (`ResultatKandidatinformasjon @table(name: "STUDENTVURDKOMBPROTOKOLL")` under a `Resultat` on the same table) returns `"100195"` three times where v9 and the SQL give `"3"`, `null`, `null`; a production operation selects it. `Karakter.navnAlleSprak` (`KarakternavnAlleSprak @table(name: "KARAKTER")`) returns null names for every grade where v9 returns the real ones. `StudieprogramUtvekslingsavtaleKobling.gyldighetsperiode` returns `{fraDato: null, tilDato: null}` for all six rows where v9 and the database give real dates; eleven production documents select it. A schema scan finds exactly these two same-table nested types in sis besides `FeideBruker.personProfil`; all four are single cardinality with no arguments.
- Personal-data leak, confirmed by the sis session. `_entities` with three `FeideBruker` representations for three users returns the same person's name for all three on v10; v9 returns each user's own. sis's own entity test passed only because its single test user is the row `limit 1` happens to pick. No production `_entities(FeideBruker)` calls were seen in the last 30 days, so the exposure is latent, but releasing v10 as it stands ships it.
- Graphitron 9 mapped such a child from the parent row's own columns (`DSL.row(parent.KANDIDATLOPENR).mapping(nullOnAllNull ...)`): the parent's own row, null when every mapped column is null. That is the reading these consumers depend on, so a rejection alone would break four production fields at build time.

## Plan

The change is decided in one place: the `TableBoundReturnType` arm of `FieldBuilder.classifyObjectReturnChildField`, at the inline `TableField` mint, which runs after the arm's existing guards (the `@splitQuery` same-table guard, the `@lookupKey` gate, the split branch and the inline `@asConnection` rejection). The predicate is decided there once, from facts the arm already holds:

- *same-row candidate*: `referencePath.elements()` is empty and the parent correlation is null (the carrier shape `ParentCorrelation.checkCarrierInvariant` admits for an empty path, as `TableField`'s constructor applies it). An empty path with no `@reference` only arises when the two tables are the same, because `parsePath` errors on a missing or ambiguous foreign key between different tables.
- *narrowed*: `TableFieldComponents.Ok` carries a non-empty `filters()` (argument filters, argument or field-level `@condition`, `@nodeId` decodes) or a `lookup()` other than `LookupResolution.None`.
- *argument-free*: the field declares no arguments, and the components carry `OrderBySpec.None` and no pagination.

A narrowed candidate mints `TableField` exactly as today, at every cardinality. Connection cardinality is rejected by the inline `@asConnection` guard, and single-cardinality `@lookupKey` by `LookupKeyDirectiveResolver.resolveAtChild`, so neither reaches the predicate.

An unnarrowed candidate splits two ways: a single-cardinality, argument-free one reads the parent's own row (below), and every other one is rejected.

### Reject the unnarrowed shapes with no parent-row reading

An unnarrowed candidate that is a list, or single cardinality carrying `@orderBy`, `first` or another argument that filters nothing, returns an `UnclassifiedField` with `Rejection.structural(...)`. The message names the field coordinate, both types and the table, says the field would read every row of the table under every parent, and gives the remedies: single cardinality with no arguments to read the parent's own row; `@reference` with a self-referencing `{key:}` for rows related to the parent; an argument, `@condition` or `@lookupKey` to narrow the read. The message is built by a helper beside `uncorrelatedSameTableSplitMessage`, so the two same-table guards read as siblings. `ProjectionUnitRenderer`'s comment on the empty-path arm is corrected to say the read is uncorrelated and narrowed only by the field's own filters.

### Read the parent's own row

A single-cardinality, argument-free candidate mints a new leaf, `ChildField.SameRowTableField` (working name; the implementer may pick a better one). The leaf's fact is "this coordinate's value is the parent's own row, read through the return type's own classification". That is a different fact from `ChildField.NestingField`, whose nested fields are classified per embedding edge against the host's table, so reusing `NestingField` would put an "is the target a table type?" branch into every walker of `nestedFields()` (`NestingReach`, `ProjectionCommands.mintNestedUnit`, `collectSplicedClaims`, `subtreePresence`, the validator's nested-leaf walk). A new leaf strains the rule that the classified leaf model is drained rather than extended; it is admissible here because it restores Graphitron 9's reading as a bug fix, adds no capability, and has no fact-store reader to migrate.

The leaf:

- Carries the `TableBoundReturnType` and admits only `FieldWrapper.Single` (compact-constructor check); list cardinality is never minted. Its `domainReturnType` is the return type's table record, not the generic `org.jooq.Record` `NestingField` uses.
- Holds no copy of the return type's fields. The return type is a `@table` type, so its fields are classified once, per type, as always; the leaf points at it.
- Has a null contract with the same rule as `NestingField.nullContract`: a nullable field resolves to `null` when every presence leaf of the return type's fields is absent, decided over every field of the type whatever the query selects; a non-null field always resolves to an object. This is Graphitron 9's `nullOnAllNull` parity. The rule moves out of `NestingField.subtreePresence` into a static over `List<ChildField>` that both leaves call, so there is one rule and not two. The contract is a model fact computed once per return type after the type walk and exposed through a `GraphitronSchema` accessor (or carried on the leaf, implementer's choice), never re-derived by the projection and the fetcher separately. A `SameRowTableField` met inside the walked fields falls into the existing `default -> Blocked` arm, so a same-table cycle (`Person.profil: PersonProfil`, `PersonProfil.person: Person`) terminates and keeps the wrapper an object.

Projection. A new `CallWrap` arm in `ProjectionCommands.contributionFor` and `ProjectionUnitRenderer.emitArm` renders the field as a single-row multiset with no `FROM` of its own, reading the host's alias directly:

```java
fields.add(DSL.multiset(DSL.select(<presence columns on table>, <ReturnType>.$project(entry.getValue(), table, env)))
    .as("__rk_" + entry.getKey()));
```

The callee is the return type's existing anchor unit (`units.typeClass`), the same unit the root fetcher and the inline multiset arm already share, and it type-checks because the host alias and the return type are bound to the same table. Foreign-key children inside the return type correlate on `table`, which is the parent row. The reader contract is the one `CallWrap.Multiset` with `Arity.SINGLE` already has: the field's fetcher unwraps the one-row result, applies the null contract over the presence columns, and hands the return type's own fetchers a record holding exactly the return type's projection, as root and multiset reads do. Two consequences follow from giving the child its own record scope:

- The child's result-key aliases (`__rk_<result key>`) stay inside the multiset and cannot collide with the host's. A splice into the host's select list, as `NestingField` does, would merge the two namespaces, and with a same-table cycle (`{ friends { id } profil { person { friends { name } } } }`) two different `__rk_friends` terms would meet in one select set and one would be deduped away. No build-time census can catch that, because the result keys are chosen by the client.
- The leaf is an ordinary `ResultKeyAliasedField` and not a `ParentRowDemand`; its presence columns are projected inside the multiset.

The cost is one correlated subquery per parent row that touches no table, which PostgreSQL evaluates from the outer row. The implementer confirms with a first spike that jOOQ 3.20 renders the `FROM`-less inner select inside `multiset` on PostgreSQL and that nested multisets inside it (a list child of the return type) resolve; if it does not, the fallback is the nested `DSL.row(...)` shape below under "Other solutions", with the same reader contract.

Memberships the leaf owes, each a build-enforced partition: `ProjectionCommands.CONTRIBUTION_MINTING_LEAVES` (it contributes to its parent's projection, which puts it in the projected bucket `GeneratorCoverageTest` derives, beside `TableField`, `NestingField` and `PivotField`, whose fetchers `FetcherEmitter` also emits; it is not one of the dual-arm kinds, which are the leaves in `TypeFetcherGenerator.IMPLEMENTED_LEAVES` as well), and an arm in `GraphitronSchemaValidator`'s exhaustive child-field switches. `FetcherEmitter` gets the fetcher arm; `TypeFetcherGenerator` and `FetcherRegistrationsEmitter` need nothing new, because the return type's fetchers are its own.

`uncorrelatedSameTableSplitMessage` and the `splitQuery.adoc` bullet change their first two remedies to "drop `@splitQuery` from a single-cardinality field with no arguments to read the parent's own row in the same statement" and keep the `@reference(path: [{condition: ...}])` remedy for a field that must stay split.

## User documentation (first-client check)

`docs/manual/how-to/map-types-to-tables.adoc`, after "Grouping columns into a nested object", a new section:

> == Returning the parent's own row as a table type
>
> A field whose return type is bound to the same table as its parent, with no `@reference`, reads the parent's own row. Use it when the return type must stay `@table` because other fields return it on their own:
>
> ```graphql
> type Film @table {
>     summary: FilmSummary
> }
> type FilmSummary @table(name: "film") {
>     title: String @field(name: "TITLE")
>     length: Int @field(name: "LENGTH")
> }
> ```
>
> `film { summary { title } }` returns each film's own title, in the same statement as the film. The field must be single-valued and take no arguments. The null rule is the nested-object one above: a nullable field resolves to `null` when every field of `FilmSummary` would be null.
>
> With arguments, a `@condition` or a `@lookupKey`, the same field means something else: a read of the whole table narrowed by them, the same for every parent. `related(filter: FilmFilter): [Film!]` on `Film` returns the films matching the filter, whichever film it is selected under. A same-table field that is neither, a list or a single field with arguments that filter nothing, would read every row of the table under every parent and is rejected at build time. To reach other rows of the table related to the parent, state the self-referencing key with `@reference(path: [{key: ...}])`.

`docs/manual/how-to/migrating-from-legacy.adoc`, "Things that didn't change": one bullet saying a field returning a `@table` type on the parent's own table, with no `@reference`, still reads the parent's own row with Graphitron 9's null rule, and needs no `@splitQuery`. `docs/manual/reference/directives/splitQuery.adoc`: the remedy revision above.

## Tests

Pipeline tier (a new `SameTableInlineChildPipelineTest`, or a nested class of `SplitQuerySameTableRejectionPipelineTest` if the fixtures share enough):

- single, argument-free, unnarrowed: classifies as `SameRowTableField` over the return type's table, including under a `@key` entity parent;
- list unnarrowed, list with only `first`, single with only an order argument: structural rejections whose message names the coordinate, both types, the table and the remedies, surfacing through `GraphitronSchemaValidator` output with the field coordinate;
- field-level `@condition` (single), argument filter (list, the `Bar.related` shape), list `@lookupKey`: still `TableField` with empty path and null correlation;
- the null contract over a type of column leaves is `CollapsesWhenAbsent`, over a type holding a list child is `AlwaysObject`, and over a same-table cycle terminates as `AlwaysObject`; the shared presence rule yields the same verdicts for every existing `NestingFieldNullContractTest` case.

`SplitQuerySameTableRejectionPipelineTest.inlineTwin_keepsStandaloneShape` is rewritten to pin the new leaf. Any sakila or corpus fixture the full build finds on the rejected shapes is fixed in the same commit; the survey found none.

Execution tier (`graphitron-sakila-example`, `GraphQLQueryTest` and `FederationEntitiesDispatchTest`), on a new fixture pair `Film.sameRow: FilmSameRow` with `FilmSameRow @table(name: "film")` also returned by a root field, so the type is a genuine table type:

- several films each get their own `title` under `sameRow` (the regression: today they all get one film's title);
- a nullable `sameRow` over only nullable columns (`release_year`, `original_language_id`) resolves to `null` for a film where both are null and to an object otherwise;
- a foreign-key child inside `FilmSameRow` (`language`) resolves per film, and `film { language { name } sameRow { language { name } } }` returns the right language at both levels (result keys shared between host and child);
- a same-table cycle query two levels deep returns the right rows;
- `_entities` with three `Film` representations selecting `sameRow { title }` returns each film's own title (the leak shape); this needs the pair in `federated-schema.graphqls` as well, whose `Film` is the `@key` entity;
- the narrowed form: a same-table list field with a `@condition` argument returns the filtered rows, identical under every parent, pinning the documented reading.

No assertions on generated method bodies.

## Implementation notes

Landed in one commit on the plan above. Facts the Done reviewer needs that the plan does not state:

- The jOOQ spike passed: jOOQ 3.20.11 renders a `FROM`-less select inside `multiset` on PostgreSQL as a correlated subquery over the outer alias (`(select coalesce(jsonb_agg(...)) from (select "film"."title" as "v0", ...) as t)`), and a nested multiset inside it resolves. The fallback was not needed.
- The null contract is exposed as `GraphitronSchema.sameRowNullContract(SameRowTableField)`, a pure per-type fold beside `nestingReach`. The shared rule is `NestingField.SubtreePresence.over(List<? extends ChildField>)` and `NestingField.NullContract.of(FieldWrapper, List<? extends ChildField>)`; `NestingField.subtreePresence()` / `nullContract()` delegate to them, and `NullContract.presenceColumns()` is the one projection of the contract's columns, read by `NestingField.parentRowColumns()`, the projection producer and (through the contract) the fetcher.
- `FetcherEmitter.bind` takes the schema as its first parameter, since the fetcher reads the contract from it.
- The leaf is admitted at nested depth (`GraphitronSchemaValidator.isNestedWireableLeaf`), on the same reasoning as `TableField`: its projection takes the anchor's `table` local and its fetcher reads by result key.
- Execution tests live in a new `SameRowTableChildExecutionTest` (beside `NestingNullContractExecutionTest`) rather than in `GraphQLQueryTest`, plus `FederationEntitiesDispatchTest.entities_sameRowChild_readsEachEntitysOwnRow`. Two fixture adjustments against the Tests section: the null-rule cases run on `PivotNestingHost.labelsRow: HostLabelsRow` (both on `pivot_nesting_host`), because no sakila film has `release_year` and `original_language_id` both null; host and child share a result key through `languageName`, declared on both `Film` and `FilmSameRow`, since the example's `Film` has no `language` field; and the narrowed form is `Film.sameTableById(filmId: Int @field(name: "FILM_ID"))`, a column-bound argument filter, because a scalar argument `@condition` still binds the argument to a column. An aliased case (`x: languageName sameRow { x: language { name } }`) is not testable today: the projection gate groups by result key over the whole sub-selection (`getFieldsGroupedByResultKey()` is recursive), so two different fields under one result key at different depths are refused by `SelectionOccurrences.canonical` before any SQL runs. That is the separately filed selection-gate defect the `OccupantLocation` fixture describes, not this item's.
- Corpus: `same-row-table.graphqls` demonstrates the leaf for the coverage obligations (no projection, so no page fragment).
- Pins moved with history lines: `LeafRatchetTest` child leaves 23 to 24 (a source-grain triple in `LeafReconstructionKeyTest`), `CommandSeamRatchetTest` generator instanceof 66 to 67, case 57 to 58, plan references 140 to 143.

## Other solutions we've considered

- *Reuse `ChildField.NestingField` for a `@table` return type.* Rejected for the reason under "Read the parent's own row": two disciplines behind one leaf, a per-edge copy of a classification the type already has, and a "circular type reference" rejection on exactly the same-table cycles real schemas can hold.
- *Splice the return type's anchor unit into the host select (`CallWrap.Splice`).* No subquery, but it merges the child's result-key aliases into the host's namespace, and same-table cycles make the collision reachable by any client.
- *Nested `DSL.row(...)` under the field's alias.* Graphitron 9's shape, no subquery, same reader contract as the chosen arm. It is the fallback if the spike shows the `FROM`-less multiset does not render; it was not chosen first because jOOQ's nested-record emulation with multisets inside is less exercised in this codebase than `multiset`.
- *Synthesize a primary-key self-correlation so the existing multiset arm reads the parent's row.* Reuses the most, but a synthesized correlation is indistinguishable from an authored `@reference` or an inferred foreign key in `ParentCorrelation`, it fails on tables with no primary key, it scans the table by key once per parent row, and a nullable child never collapses to `null`, breaking Graphitron 9 parity.
- *Reject every unnarrowed shape, permanently.* Rejected by the production evidence: four sis fields depend on the parent-row reading.
- *Ship a build-time rejection of the single argument-free shape first, as an interim safety net (requested by the sis session).* Not chosen. It passes the same Spec → Ready gate as the fix, so it would land only marginally sooner; v10 is unreleased and no production `_entities(FeideBruker)` calls were seen, so the exposure stays latent in the meantime; and it would make sis rewrite four fields to clear a rejection that the fix then removes. The fix reuses the return type's anchor unit, the single-row multiset reader and the nesting null rule, and its one open question (the jOOQ spike) has a fallback, so it does not need a holding position. If implementation stalls, the interim rejection is cheap to add then.

## Reviewer findings

### In Review → Done, 2026-10-20: rework

The top-level delivery matches the plan and its evidence holds. Classification, the leaf, the single-row multiset over the host alias, the shared null rule, the rejections, the diagnostics and the manual all read as approved, and the documented substitutions (fixtures, the dropped alias case that R708 owns, the presence columns riding in the child's row) are sound. The two-level same-table cycle test also exercises the result-key collision the plan chose the multiset to avoid. One addition fails both gate questions.

**Question 1, correctness of added scope.** The implementation notes say the leaf is admitted at nested depth (`GraphitronSchemaValidator.isNestedWireableLeaf`). That admission was not in the plan, and the shape it admits crashes the generator. A same-row child inside a plain nested object reaches `FetcherRegistrationsEmitter.nestedBody`, which calls `registrationEntry(null, ...)`. The `null` is the schema, and `FetcherEmitter.bindRaw` dereferences it for `sameRowNullContract`. Reproduced on the sakila example by adding

```graphql
type FilmProbeNest { rating: String @field(name: "RATING")  row: FilmSameRow }
# and on Film:  probeNest: FilmProbeNest
```

`generate` then fails with `Cannot invoke "GraphitronSchema.sameRowNullContract(...)" because "schema" is null`: a build crash, not a diagnostic. Before this item the same SDL classified as an inline `TableField` and built.

**Question 2, evidence.** No test covers the leaf at nested depth, which is how the crash got through.

**What satisfies it.** Either:

- *Keep the admission.* Thread the schema through `nestedBody` into `registrationEntry`, and correct the comment above that call, which says the path never reads the schema. Then add an execution test that reads a same-row child under a nested object, each film getting its own row with a foreign-key child resolving. The reviewer checked this remedy with a scratch patch (three lines in `FetcherRegistrationsEmitter`) and the probe above: it generates, compiles, and returns each film's own `filmId`, `title` and `language`.
- *Or withdraw it.* Drop the leaf from `isNestedWireableLeaf`, so the shape gets the nested-depth deferral diagnostic, and pin that with a pipeline test.

Either way the spec body should name the nested-depth behaviour as shipped.

**Build note, not this item's.** `mvn install -Plocal-db` is red in `graphitron-sakila-example` on `GraphQLQueryTest`'s whole-table film reads. In three runs it failed on `filmsByTitleBean_absentInput_buildsNoBeanAndLeavesTheFieldUnfiltered` and `inputFieldCondition_customScalarDefaults_bindAsBigDecimalBounds`, where rows written concurrently by `ServiceListGapExecutionTest` and the tenant tests show up. R863 already records exactly this. With class parallelism off (`-Djunit.jupiter.execution.parallel.enabled=false`) the module passes, 1152 tests and 0 failures, and every R1045 test passes in both modes.
