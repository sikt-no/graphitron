---
id: R1094
title: "@externalField: drop the column-name collision rejection, made moot by result-key aliasing"
status: In Review
bucket: bug
priority: 2
theme: codegen-correctness
depends-on: []
created: 2026-10-23
last-updated: 2026-10-26
---

# @externalField: drop the column-name collision rejection, made moot by result-key aliasing

## Goal

An `@externalField` field (a field whose value comes from a developer's static method returning a jOOQ `Field<T>`, inlined into the parent's `SELECT`) may share its GraphQL name with a column on the parent `@table`, and returns the method's value. Today the build rejects that as an author error, and the only way past is renaming the field, which breaks the consumer's clients. The minimal pair, from the truth-table case that pins the rejection today (`GraphitronSchemaBuilderTest.NAME_COLLIDES_WITH_COLUMN`):

```graphql
type Film @table(name: "film") {
    # today: "@externalField name 'title' collides with column 'title' on table 'film'"
    # after: classifies as a computed field and returns lower(title)
    title:      String @externalField(reference: {className: "...FilmExtensions", method: "lowerTitle"})
    # still reads the column, unaffected by the computed field above
    plainTitle: String @field(name: "TITLE")
}
```

Nothing else changes: a field without the shared name classifies and generates exactly as it does now.

## Consumer

sis needs this to keep the Graphitron 9 `id` on the 215 hendelse types over `API_HENDELSE`. The ids must stay byte-identical to the v9 encoding, `195:<institution>,<ROW_SCN>,<ID>`, because consumers have stored them. All 215 types share one node type id, so `Node` is ruled out, and a computed field is the remaining route. The field is called `id` and the table has a column `ID`, so today every site is rejected. The sis-side method (`HendelseIds.id(ApiHendelse)`, a concat plus `convertFrom` over the v9 encoder) is written and verified against Oracle; only this rejection blocks it. sis treats it as a merge blocker for the Graphitron 10 port.

## Why the guard is stale

The check sits at the top of `ExternalFieldDirectiveResolver.resolve` and matches the GraphQL field name against the parent table's columns through `JooqCatalog.findColumn` (Java name or SQL name, ignoring case). Its comment gives the reason: the fetcher read the value back with `DSL.field("<name>")`, so a same-named column in the result record would shadow it. That read is gone. `ChildField.ComputedField` is a `ResultKeyAliasedField`, meaning a field projected under a reserved alias and read back by that alias:

- Write side: `ProjectionUnitRenderer.rkAlias` aliases the inlined method call `ReservedAliases.resultKeyPrefix(aliasOwner)` + the runtime result key, that is `__rk_<owner>$<resultKey>`.
- Read side: `FetcherEmitter.columnByAlias` (the `ComputedField` arm of the child-field binding) reads `DSL.field(DSL.name(<same prefix> + env.getField().getResultKey()))` off the parent record, with the `AliasScope.Spliced` path segment when the field sits on a nesting type.

The bare field name appears on neither side. `ReservedAliases` owns the disjointness argument: every reserved alias starts with a double underscore, so no catalog column and no client alias can spell one. A column of the field's name is projected, if some other field selects it, under its own name and read through its typed `Field`, so the two terms cannot meet.

The rejection's hint is also wrong. It suggests `@field(name: ...)`, but the check reads `fieldDef.getName()`, so `@field` changes nothing.

No other classification arm contests the coordinate. In `FieldBuilder.classifyChildFieldOnTableType` the `@externalField` arm runs ahead of the object-return arm, the `@nodeId` arm, the `Node.id` arm and the column lookup, so dropping the guard routes the field to `ComputedField` and nowhere else.

The fact store never mirrored the rule: `graphitron_field_target_column_match` and `graphitron_reader_rule` (in `graphitron-model.sql`) exclude `@externalField` by directive, not by name, so a computed `title` already reads as an expression with no target-column row. The walk-side check was the rule's only holder.

### Consumer sites audited

Relaxing a classifier check owes an audit of every emitter site that consumes the shape. Besides the write and read halves above, these address a projected term or a column by name, and none can meet a `ComputedField` by its bare name:

- The discriminated-fold selection restriction (`LauncherCommands.selectionRestriction`, `DiscriminatedTableFragments.typeConditionedGate`) keys on GraphQL field names, and one type cannot declare two fields of one name.
- The joined-table base slice and detail terms (`JoinedTableReprojection.of`, `DiscriminatedTableFragments.fieldsList`) mint bare column-name aliases only from `ColumnBackedField`; a `ComputedField` never enters them.
- The plain `DSL.field("<sqlName>")` reads in `FetcherEmitter` serve `RecordReadField` on result types, from which `ComputedField` is excluded (`ParentSourceBinding.WRAPPER_INADMISSIBLE_LEAVES`).
- `@service` and DML table returns re-project through the type's `$project`, so the computed term is written under its alias there too.
- Federation keys: `EntityResolutionBuilder` finds no column for a `ComputedField` and rejects it as not column-backed, rather than binding the same-named column.

### The single-table interface shape

sis declares the field on participants of a single-table discriminated interface (an interface whose implementers all live in one table and are told apart by a discriminator column, queried in one statement that folds every participant's select terms together). The fold dedupes aliased terms by alias, and the alias owner (`AliasOwner`, stamped by `FieldBuilder.aliasOwnerOf` from `TypeBuilder.aliasOwnerIndex`) keeps them apart: a name the interface declares is owned by the interface, so participants that agree collapse to one term; a name only a participant declares is owned by that participant. Participants that resolve an interface-declared name differently are already a deferred rejection (`ParticipantAliasOwnerPipelineTest` pins it). None of this reads the column namespace, so the guard was never what kept the interface case sound. The tests below pin both ownership arms with the column present.

### Node types

This item widens one shape, and says so. On a node type (a `@table` type implementing `Node`) whose table has a literal `id` column, `id: ID! @externalField(...)` now classifies as `ComputedField` rather than being rejected. That is the verdict the same field already gets on a node type without an `id` column: the directive is an explicit reading, like `@nodeId` and `@field`, which also exit ahead of the `Node.id` arm and its `rejectShadowedIdColumn` refusal, so a rejection keyed on whether a column exists would key on the wrong thing.

The guard was, by accident, the only thing keeping that shape off the common node table. A computed `Node.id` is emitted from the author's method, while `node(id:)` and a federation `@key(fields: "id")` decode with the node type's decoder over `nodeKeyColumns` (`EntityResolutionBuilder.build` mints `KeyAlternative.NodeId` whatever `id` classified to), and nothing checks that the two agree. That is filed as R1095 rather than folded in here: the choice between a narrow rejection and a stated contract is its own design question, and sis does not reach it (its hendelse types do not implement `Node`). This item pins the widened verdict as a truth-table row, so R1095's enforcer flips a pinned row instead of changing behaviour silently.

## Implementation

- `ExternalFieldDirectiveResolver.resolve`: delete the collision check and its comment. Update `resolve`'s javadoc, whose `parentTable` sentence says its SQL name "gates the alias-collision check"; after the change the table ref serves only the `ServiceCatalog.reflectExternalField` parent-table-class invariant.
- `ChildField.ComputedField` javadoc: it says runtime wiring reads "the aliased column, keyed on the GraphQL field name", which is the stale premise. Restate it as the result-key alias read, linking `ResultKeyAliasedField` (the `FetcherEmitter` member is private, so the link goes to the marker interface).
- `FetcherEmitter`, the `ComputedField` arm's comment opens "Wired by name"; it is wired by result-key alias, and the comment says so.
- User manual, `docs/manual/reference/directives/externalField.adoc`: drop the Constraints bullet "The GraphQL field name must not collide with a real SQL column on the parent table…" and the See also bullet saying `@field(name: ...)` disambiguates a collision; add one Constraints-adjacent sentence that the field may share its name with a column on the parent table, and that other fields still read that column.
- How-to, `docs/manual/how-to/computed-fields.adoc`: drop the same collision bullet (it carries the same wrong `@field` hint).
- Nothing else reads the check: the only other hit for "collides with column" is the test below, and neither `graphitron-lsp` nor `diagnostics-glossary.adoc` carries the rejection.

R555 (fold `@externalField` into `@service`) and R54 (rename the directive) touch the same directive. Neither depends on this guard, and this item does not wait on them; whichever lands later carries the docs edit above into its own rewrite.

## Retired vocabulary

- "collides with column" (the rejection message)
- "alias-collision check" (the resolver's comment and javadoc)
- the "must not collide with a real SQL column" constraint and the `@field(name: ...)` disambiguation advice, in the manual and the how-to

## Tests

- **Classification**, `GraphitronSchemaBuilderTest`: `NAME_COLLIDES_WITH_COLUMN` flips to a positive case (renamed to say what it now shows, for example `NAME_MATCHES_COLUMN`). The same SDL classifies to `ChildField.ComputedField` with `AliasOwner.shared()`.
- **Classification, the widened node shape**, `GraphitronSchemaBuilderTest`: a new row for `id: ID! @externalField(...)` on a node type over a table with an `id` column, classifying to `ComputedField`. It pins the verdict R1095 decides about; its description names that it is pinned on purpose.
- **Interface ownership**, a pipeline-tier case beside `ParticipantAliasOwnerPipelineTest`, over the `fan_base` fixture, whose `label` column is the colliding name, in the sis shape: `label` declared on the interface and on both participants with the same method, beside a field reading the `label` column. Asserted as model data, not generated code: both participants' `ComputedField` carry `AliasOwner.qualifiedBy("<Interface>")`; `ProjectionCommands.siblingProjectionConflicts` is empty; the anchor unit's contributions hold a `SelectTerm.Column` for the column field and a `SelectTerm.HelperCall` for the computed one, its `Contribution.aliasOwner()` the interface; and `selectionRestriction(...).sharedAliasFieldNames()` contains `label`. The participant-owned arm needs no case of its own here: `FieldBuilder.aliasOwnerOf` never reads the catalog, so the column cannot change ownership.
- **Execution**, in `graphitron-sakila-example`, the strongest evidence and the one sis needs:
  - The table-type shape: a type over `film` with `title` as an `@externalField` returning `lower(title)` (a new `FilmExtensions.lowerTitle(Film)`; sakila titles are seeded uppercase, so `lower` tells the two values apart where `upper` would not) and `plainTitle: String @field(name: "TITLE")`, reached from a root field by primary key. One query selecting both returns `title = "academy dinosaur"` and `plainTitle = "ACADEMY DINOSAUR"` for film 1. Two cases, each field selected alone, pin that neither read depends on the other being in the select list.
  - The interface shape (the sis shape): a single-table interface over `fan_base` whose participants both declare `label` as an `@externalField` returning a value distinct from the column (for example `fan_kind || ': ' || label`), beside a field reading the `label` column. One query over both participant types returns the computed and the column value per row, for an `ALPHA` and a `BETA` row. Do not repurpose `FanItem.label`: existing fold assertions read it as the column.
  - Placement is the implementer's call between a new test class and the existing `GraphQLQueryTest` / `ResultKeyScopeExecutionTest`; the fixture SDL carries a comment saying the name is shared with a column on purpose. A fact scenario in `graphitron` (the "tested where it is generated" home in `docs/architecture/how-to/testing.adoc`) is an acceptable alternative home for the table-type shape if it also pins the store facts above (the computed `title` reads as an expression, with no target-column row).

## Implementation notes

All of the plan above shipped in one change. Where the tests landed:

- Classification: `GraphitronSchemaBuilderTest.ComputedFieldCase.NAME_MATCHES_COLUMN` (with `plainTitle` asserted as the `title` column) and `NODE_ID_MATCHES_ID_COLUMN` (over the `baz` fixture, whose key column is `id`).
- Interface ownership: `ParticipantAliasOwnerPipelineTest.computedFieldSharingAColumnName_isOwnedByTheInterface_andProjectsBesideTheColumn`, using the new `TestExternalFieldStub.fanLabel(FanBase)`.
- Execution: a new `ExternalFieldSharedColumnNameExecutionTest` in `graphitron-sakila-example`, over the new `ComputedTitleFilm` type (`FilmExtensions.lowerTitle`) and the new `FanLabelled` interface (`FanExtensions.kindLabel`, returning `fan_kind || ': ' || label`). The interface case is asserted twice, once selecting the fields on the interface and once through both participants' type conditions.
