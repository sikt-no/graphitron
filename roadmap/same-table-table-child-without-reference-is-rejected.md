---
id: R1115
title: "A child returning a @table type on its parent's own table, with no @reference, is rejected instead of reading the parent's own row"
status: Backlog
bucket: bug
priority: 2
theme: classification-model
depends-on: []
created: 2026-10-28
last-updated: 2026-10-28
---

# A child returning a @table type on its parent's own table, with no @reference, is rejected instead of reading the parent's own row

## Goal

A field whose return type is a `@table` type bound to the same table as its parent, with no `@reference`, is a build error that asks for a `@reference` stating how the two rows of that table are related. Binding the return type to a table with `@table` means "this is a row of that table, reached by a join", so two `@table` types on one table describe a self-join, and a self-join has no default. Only a return type without `@table`, a plain nested object, reads the parent's own row. Today, after R1045 and R1069, these two schemas behave identically, both reading the parent's film row with the same null rule:

```graphql
# A: correct today, and stays as is. FilmNames is a plain nested object,
# so its fields are columns of the parent's own film row.
type Film @table(name: "film") { names: FilmNames }
type FilmNames { nb: String @field(name: "title") }

# B: today reads the parent's own row too. After this item: rejected,
# naming the field and asking for @reference.
type Film @table(name: "film") { names: FilmNames }
type FilmNames @table(name: "film") { nb: String @field(name: "title") }
```

## Background

R1045 (Done, `706f151`; deleted item `inline-same-table-child-reads-an-arbitrary-row.md`, restorable from `f6b153a44^`) found shape B reading one arbitrary row for every parent, a personal-data leak through `_entities` in sis. It chose Graphitron 9's reading for the single, argument-free form (a new leaf, `ChildField.SameRowTableField`, projected as a `FROM`-less single-row multiset over the host alias calling the return type's own `$project`), kept the uncorrelated read for a field narrowed by arguments, `@condition` or `@lookupKey`, and rejected the rest (`FieldBuilder.unnarrowedSameTableMessage`). Its evidence section records why it did not simply reject: four sis production fields use shape B (`FeideBruker.personProfil`, `Resultat.kandidatinformasjon`, `Karakter.navnAlleSprak`, `StudieprogramUtvekslingsavtaleKobling.gyldighetsperiode`), and their return types stay `@table` because other fields return them as table types. R1069 then lifted the null contract into `rewrite.model.Presence`, so the leaf now shares the nested object's presence rule.

This item reverses R1045's single-row reading: shape B is ambiguous (the parent's own row, or a related row of the same table?) and the author must say which.

## Open questions for Spec

- **The remedy for "I mean the parent's own row" when the return type must stay `@table`.** There is usually no self-referencing foreign key for the identity join, so `@reference(path: [{key: ...}])` does not express it. Candidates: a `condition:` hop joining the two aliases on the primary key (hand-written Java per field; check that a condition-only same-table hop correlates to the parent today and is not the uncorrelated arm), a first-class way to name the primary key as the join, or splitting the type into a nested object for the same-row use and a `@table` type for the root use. The rejection message names whichever remedies exist when this lands; the four sis fields need one before the item ships, so this is the item's migration story.
- **Narrowed same-table children** (arguments, `@condition`, `@lookupKey`; `Film.related(filter:)`), which R1045 documented as a parent-independent read of the table. The same "two `@table` types on one table is a self-join" argument covers them. Recommendation: settle explicitly in Spec whether they keep that reading or also require a `@reference`.
- **Retirement.** If nothing else mints it, `ChildField.SameRowTableField` and its projection arm, fetcher arm, validator arms and ratchet pins go, along with the `map-types-to-tables.adoc` section "Returning the parent's own row as a table type", the `migrating-from-legacy.adoc` bullet saying the reading did not change from Graphitron 9 (it now does: a migration note instead), and the remedy text in `uncorrelatedSameTableSplitMessage` / `splitQuery.adoc` that sends authors to the same-row reading. Declare these under `## Retired vocabulary`.
- **Tests that pin the current reading** and flip to rejections: `SameRowTableChildExecutionTest`, `FederationEntitiesDispatchTest.entities_sameRowChild_readsEachEntitysOwnRow`, `SameTableInlineChildPipelineTest`, `FetcherRegistrationsEmitterTest.nestedSameRowChild_registersUnderTheNestedType`, the `same-row-table.graphqls` corpus fixture and the R1069 presence cases over same-table children (`TableChildNullContractExecutionTest`, `SameRowTableChildExecutionTest`'s list case).
