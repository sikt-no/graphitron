---
id: R1115
title: "A child returning a @table type on its parent's own table, with no @reference, is rejected instead of reading the parent's own row"
status: Spec
bucket: bug
priority: 2
theme: classification-model
depends-on: []
created: 2026-10-28
last-updated: 2026-10-28
---

# A child returning a @table type on its parent's own table, with no @reference, is rejected instead of reading the parent's own row

## Goal

A single-valued, argument-free field whose return type is a `@table` type bound to the same table as its parent, with no `@reference`, is a build error that asks for a `@reference` stating how the two rows of that table are related. Binding the return type to a table with `@table` means "this is a row of that table, reached by a join", so two `@table` types on one table describe a self-join, and a self-join has no default. Only a return type without `@table`, a plain nested object, reads the parent's own row. An author who wants the parent's own row and needs the return type to stay `@table` (because a root field or a field on another table also returns it) states the join with a new built-in remedy: `@reference` naming the table's own primary key, which joins each row to itself without hand-written code. Today, after R1045 and R1069, these two schemas behave identically, both reading the parent's film row with the same null rule:

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

## Decisions taken in Backlog (with the user, 2026-10-28)

- **Remedy: a built-in primary-key self-join.** A same-table `@reference` hop names the table's own primary key, and the generator joins the child alias to the parent alias on every primary-key column. Recommended surface: let `ReferenceElement.key` accept the table's primary-key constraint name (`film_pkey`, as jOOQ's `Keys` names it) when the hop's source and target are the same table; today `key:` resolves foreign keys only. Spec settles the exact surface (that, or a dedicated element field), the rejection for a table with no primary key, and whether the hop reuses an existing primary-key slot builder in `BuildContext` (there are primary-key `JoinSlot.FkSlot` builders already). The four sis fields migrate to this, so it ships with the rejection, never after it.
- **Narrowed same-table fields are out of scope.** A same-table field narrowed by arguments, `@condition` or `@lookupKey` (`Film.sameLength(length: Int @field(name: "length")): [Film!]`, which returns the films of that length under every film) joins nothing: it is a filtered read of the table, independent of the parent, so it needs no `@reference` and keeps R1045's documented reading. The rejection applies only to the shape that today reads the parent's row: single-valued, argument-free, unnarrowed. R1045's existing rejection of the unnarrowed list and argument-that-filters-nothing forms stays; its message stops offering "make the field single-valued with no arguments" as the way to read the parent's own row.

## Open questions for Spec

- **Rejection message.** Names the coordinate, both types and the table, and gives two remedies: drop `@table` from the return type when nothing else returns it as a table type (the type becomes a nested object over the parent's row), or state the primary-key self-join with `@reference`. Whether the shared rejection helper is a revision of `unnarrowedSameTableMessage` or a sibling is the implementer's call.
- **What the primary-key hop yields.** A single-valued field over the primary-key self-join is the parent's own row; check whether the existing `@reference` single child path (correlated multiset over a join path) already carries the null contract R1069 gave `SameRowTableField` (the row exists exactly when the parent does, so a nullable field collapses on R1069's presence rule over the type's fields), so the user-visible result for the four sis fields is unchanged.
- **Retirement.** If nothing else mints it, `ChildField.SameRowTableField` and its projection arm, fetcher arm, validator arms and ratchet pins go, along with the `map-types-to-tables.adoc` section "Returning the parent's own row as a table type", the `migrating-from-legacy.adoc` bullet saying the reading did not change from Graphitron 9 (it now does: a migration note instead), and the remedy text in `uncorrelatedSameTableSplitMessage` / `splitQuery.adoc` that sends authors to the same-row reading. Declare these under `## Retired vocabulary`.
- **Tests that pin the current reading** and flip to rejections or move to the primary-key `@reference` (the narrowed cases stay as they are): `SameRowTableChildExecutionTest`, `FederationEntitiesDispatchTest.entities_sameRowChild_readsEachEntitysOwnRow`, `SameTableInlineChildPipelineTest`, `FetcherRegistrationsEmitterTest.nestedSameRowChild_registersUnderTheNestedType`, the `same-row-table.graphqls` corpus fixture and the R1069 presence cases over same-table children (`TableChildNullContractExecutionTest`, `SameRowTableChildExecutionTest`'s list case).
