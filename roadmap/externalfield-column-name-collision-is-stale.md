---
id: R1094
title: "@externalField: drop the column-name collision rejection, made moot by result-key aliasing"
status: Backlog
bucket: bug
priority: 2
theme: codegen-correctness
depends-on: []
created: 2026-10-23
last-updated: 2026-10-23
---

# @externalField: drop the column-name collision rejection, made moot by result-key aliasing

## Goal

An `@externalField` may share its GraphQL name with a column on the parent `@table`. Today `ExternalFieldDirectiveResolver.resolve` rejects that as an author error, guarding against a read that no longer exists, so a consumer whose computed value has to keep a column's field name has no way to declare it.

## Why the guard is stale

The check matches the GraphQL field name against the parent table's columns, ignoring case (`JooqCatalog.findColumn`). Its comment gives the reason: the fetcher read the value back with `DSL.field("<name>")`, so a same-named column in the result record would shadow it. That stopped being the read with R500. A `ComputedField` is a `ResultKeyAliasedField`: the projection writes it under `ReservedAliases.resultKeyPrefix(aliasOwner) + resultKey` (`ProjectionUnitRenderer.rkAlias`), and the fetcher reads it back by that same composed alias (`FetcherEmitter.columnByAlias`, the `ComputedField` arm). The bare field name appears on neither side, so a column of that name can no longer shadow it.

The rejection's hint is also wrong: it suggests `@field(name: ...)`, but the check reads `fieldDef.getName()`, so `@field` changes nothing. Renaming the field is the only way past it, and that is a breaking change for the consumer.

## Consumer

sis needs it to keep the Graphitron 9 `id` on the 215 hendelse types over `API_HENDELSE`. The ids must stay byte-identical to the v9 encoding, which is `195:<institution>,<ROW_SCN>,<ID>`, because consumers have stored them. All 215 types share one node type id, so `Node` is ruled out, and the computed field is the remaining route. The field is called `id` and the table has a column `ID`, so today every site is rejected. The sis-side method (`HendelseIds.id(ApiHendelse)`, a concat plus `convertFrom` over the v9 encoder) is written and verified against Oracle; only this rejection blocks it. sis treats this as a merge blocker for the Graphitron 10 port.

## Plan

Delete the collision check and its comment in `ExternalFieldDirectiveResolver`. Nothing else reads it (the only other hit for "collides with column" is the test below).

R555 (fold `@externalField` into `@service`) and R54 (rename the directive) touch the same directive; neither depends on this guard, and this item does not wait on them.

## Tests

- `GraphitronSchemaBuilderTest` `NAME_COLLIDES_WITH_COLUMN` flips: the same SDL now classifies to `ChildField.ComputedField`.
- An execution test in the sakila fixtures: an `@externalField` named after a column, with a method that returns something other than that column (for example `title` returning `upper(title)`), selected together with a field that reads the column. Both values have to come back as written.
- A single-table discriminated interface with two participants that each declare the colliding `@externalField`, which is the sis shape. It covers the `aliasOwner` fold with the column in the same projection.
