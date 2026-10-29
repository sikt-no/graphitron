---
id: R1100
title: "A node type can narrow its key and still accept ids minted under the old key"
status: Spec
bucket: feature
priority: 3
theme: nodeid
depends-on: []
created: 2026-10-26
last-updated: 2026-10-29
---

# A node type can narrow its key and still accept ids minted under the old key

## Goal

A node type can declare a *legacy key shape*: an older column list its ids were once encoded from. Graphitron then accepts an id in either shape wherever it decodes one. An id in the legacy shape is converted to the current shape before anything else reads it. Every id Graphitron emits uses the current shape. A schema can therefore drop a column that should never have been in the node key without invalidating the ids clients and other subgraphs already store.

A node id is `base64("<typeId>:<v1>,<v2>,...")`: the type id names the node type, and the values are the type's *node key* columns (`@node(keyColumns:)`, else the catalog's `__NODE_KEY_COLUMNS`, else the primary key) in key order. Today the build fixes that list, so dropping a column from it is a hard cut: every id issued before the change stops decoding.

The case from sis is `GodkjentPolitiattest`, over view `POLITIATTESTGODKJENT` (type id `230`). Its primary key is `(INSTITUSJONSNR_EIER, PLNR, STUDIEPROGRAMKODE, LISENSTYPEKODE)`. Its node key has those four columns plus `DATO_FORSTE_GYLDIGE`, the date the approval is valid from. The date entered the key in 2023 with no stated reason. It is a mutable attribute: `endreGodkjentePolitiattester(fraDato:)` changes it, so today every date change also changes the row's id. R1093 (shipped) lets UPDATE and DELETE accept such an id, but `node` and `_entities` still filter on the date and every emitted id still carries it. sis wants to:

* narrow the node key to the four primary-key columns, so the id stops changing when the date changes;
* keep resolving every five-value id already stored, including ids whose date has since changed;
* emit only four-value ids from now on.

```graphql
type GodkjentPolitiattest implements Node @table(name: "POLITIATTESTGODKJENT") @node(
    keyColumns: ["INSTITUSJONSNR_EIER", "PLNR", "STUDIEPROGRAMKODE", "LISENSTYPEKODE"],
    legacyKeyColumns: ["INSTITUSJONSNR_EIER", "PLNR", "STUDIEPROGRAMKODE", "LISENSTYPEKODE", "DATO_FORSTE_GYLDIGE"]
) @key(fields: "id") {
  id: ID!
}
```

sis declares its node keys in jOOQ metadata rather than SDL, so the same fact also has a catalog form: a `__NODE_LEGACY_KEY_COLUMNS` constant beside `__NODE_KEY_COLUMNS` on the generated table class.

After this lands:

* `node`, `nodes`, `_entities`, `@nodeId` arguments, `@nodeId` input fields (including `@reference` ones), UPDATE and DELETE accept both `base64("230:i,p,s,l")` and `base64("230:i,p,s,l,2024-01-05")`. Both identify the row by its four key columns.
* The legacy id's date is checked to be a valid value of its column and then discarded. It is never a filter, so a legacy id whose date has gone stale still resolves.
* Every id in a response is the four-value form, including the id returned for a row that was looked up by a legacy id, and the `missingAsDeleted` echo of a legacy input.
* `NodeIdEncoder.toCurrentId(id)` converts a stored id outside GraphQL, so a migration job can rewrite the ids it holds.

## User documentation (first-client check)

These drafts move into their real homes when the item ships.

**`@node` reference** (`docs/manual/reference/directives/node.adoc`), a new parameter row:

> `legacyKeyColumns` | `[String!]` | none | The column list ids of this type were encoded from before `keyColumns` was narrowed. An id in this shape is still accepted everywhere an id of the type is read, and is read as the id of the row its `keyColumns` values name; its other values are checked to be valid values of their columns and otherwise ignored. Ids Graphitron emits always use `keyColumns`. Must contain every `keyColumns` column plus at least one more, each once. Its order is the order the old ids were written in.

and a section after "Inferring the identity parameters":

> === Narrowing the key
>
> A key column that should never have been in the id (a mutable date, a status) can be dropped without invalidating ids already issued. Narrow `keyColumns` and move the old list to `legacyKeyColumns`:
>
> ```graphql
> type InventoryItem implements Node @table(name: "inventory")
>     @node(keyColumns: ["inventory_id"], legacyKeyColumns: ["inventory_id", "last_update"]) {
>   id: ID!
> }
> ```
>
> Old ids keep resolving, including ids whose `last_update` has changed since they were issued, and every id in a response is the narrowed form. The type id must stay the same; a renamed type id is a different id space. A generated table class can state the same thing with `__NODE_LEGACY_KEY_COLUMNS`; when both are present they must be the same list in the same order.
>
> The generated `NodeIdEncoder` logs the first legacy id of each type it converts at `INFO`, and later ones at `DEBUG`. Once a release passes with no such line, the stored legacy ids are gone and `legacyKeyColumns` can be deleted. `NodeIdEncoder.toCurrentId(id)` rewrites a stored id without going through GraphQL.

**How-to** (`docs/manual/how-to/global-id.adoc`, section "`keyColumns` migrations"): the closing paragraph that calls every `keyColumns` change a hard cut becomes: narrowing the key has a rolling window (link the section above); widening it, reordering it, or swapping in an unrelated key is still a hard cut, for which a second `@node` type pinned to the old shape remains the tool.

**Runtime API** (`docs/manual/reference/runtime-api.adoc`): a `toCurrentId(String base64Id)` entry (returns the id unchanged unless it is a legacy-shape id of a type that declares one, the current-shape id when it is, `null` when it is a legacy-shape id carrying a value that is not one of its column), and a sentence on `decodeValues`: it is the raw unpack, so a legacy id comes back with its legacy values; call `toCurrentId` first for the current shape.

## The conversion

The conversion runs inside the generated `NodeIdEncoder.decodeColumns`, before its arity check (`values.length != columns.length`), which rejects a legacy id today. Every decode surface reaches a key slot through `decodeColumns`: the per-type `decode<Type>` helpers, `RecordDecodeFragments`, `HandleMethodBody` (`node` / `_entities`), the `CompositeDecodeHelperRegistry` helpers, `JooqRecordInstantiationEmitter`, `LookupRows`, the tenant-slot decodes `TenantDslEmitter` installs and `EntityFetcherDispatchClassGenerator`. So every surface gets the conversion with no call-site change.

`decodeColumns` gains one statement at its top: when the expected type id has a legacy shape, replace the input with `toCurrentId(base64Id)`. The gate is on `expectedTypeId`, a map lookup, so ids of types without a legacy shape pay nothing, not even a second base64 decode. A `null` from `toCurrentId` flows into `decodeValues`, which returns `null`, so a malformed legacy id is rejected exactly like any malformed id.

`toCurrentId(String base64Id)` is a public static method, emitted on every run so the runtime API does not depend on the schema:

1. `null` in, `null` out. Peek the type id. If it has no legacy shape, return the id unchanged.
2. Split the payload with `decodeValues`. If its arity is the current key's, return the id unchanged.
3. If its arity is the legacy shape's, check every value against its legacy column with `ColumnValues.exact` and the column's parse class: the same per-slot check `decodeColumns` makes, fold warning included, factored into one private helper both call. Any failure returns `null`.
4. Pick the current key's values out of the checked legacy values and re-encode them with `encode(typeId, ...)`. Return that id. `encode` writes `toString()` of each value, and the exact rule guarantees a checked value's `toString()` is the text `encode<Type>` writes for it, so the result is the id the row's current encode produces.
5. Any other arity returns the id unchanged; `decodeColumns`' own arity check then rejects it.

The pick in step 4 is a position map computed at build time by column name, so the dropped columns need not be last and the legacy shape may list the key columns in another order.

On a successful conversion it logs the type id, once per type id per process at `INFO` and at `DEBUG` after that, the pattern `reportFold` already uses (a concurrent set of seen type ids). It logs no client-supplied value. This is what tells a schema owner the legacy shape can go: there is no metrics dependency in the generated runtime, and a consumer that wants a count can count the lines.

The conversion runs one way only, legacy to current. The other direction would need a database read, because the dropped value is not in a current id.

### Where the raw id string is identity

A legacy id and its current form are different strings for one row, so anything keyed on the wire string would see two ids. An audit of the emitters found every dedup, grouping, ordering, tenant-routing and echo decision works on values `decodeColumns` returned, and so on converted values: the bulk UPDATE duplicate guard (`seenKeys` over decoded cells), the bulk DELETE `IN` list, UPSERT, `@lookupKey` VALUES rows, the `_entities` grouping by decoded tenant, `requireColumnAgreement`, and the `missingAsDeleted` echo. No change is needed there. Three sites read the raw string and stay as they are:

* **Type dispatch** (`QueryNodeFetcher` `peekTypeId` into `EntityFetcherDispatch.typenameForTypeId`, the polymorphic `@nodeId` dispatch in `RecordDecodeFragments` and `MultiTablePolymorphicEmitter`) routes on the type id before decoding. That is correct because a legacy shape belongs to its type id, which does not change. A type-id change is a type rename and out of scope.
* **`nodes(ids:)`**' DataLoader is keyed on the raw string, so a legacy id and its current form in one call are two loads of one row. Each position still gets its row; the cost is one extra VALUES row, as for two spellings of one id today.
* **`SelectionOccurrences.requireConsistentArguments`** compares raw argument maps when one field is selected under both `edges.node` and `nodes`. A client passing the legacy form on one path and the current form on the other gets the existing "conflicting arguments" error. It fails loud, never with wrong data, and needs a client mixing two forms of one id in one query.

### The `missingAsDeleted` echo

A DELETE with `missingAsDeleted: true` answers a missing row with an id built from the key values its WHERE compared (`TypeFetcherGenerator.missingRowEcho`), never from the input string. For a legacy input those are the converted values, so the echo is the current-form id. That is the only form Graphitron can mint itself, it is what `node(id:){ id }` and a deleted row's `RETURNING` already answer, and it keeps "every id in a response is the current form" true without an exception. The javadoc on `DeleteRows.IdempotentByPrimaryKey` and `missingRowEcho` that says the echo is "the id the client sent" is reworded to "the id the client sent, in the current shape".

## Build-time rules

`legacyKeyColumns` (or `__NODE_LEGACY_KEY_COLUMNS`) is refused at build time when:

* **It does not cover the current key.** Some current key column is missing from it, so a legacy id cannot be converted without reading the row.
* **It is the current key reordered.** Given the rule above, a legacy shape of the current key's arity holds exactly the current key's columns; the two shapes could not be told apart by arity, which the conversion relies on.
* **It names a column the table lacks.**
* **It names the same column twice.** The conversion matches by column name.
* **SDL and the catalog both state one and the lists differ**, in content or order. See below.
* **Its type id is shared with another decode target over a different table or key.** The legacy table in `NodeIdEncoder` is keyed by type id, because `decodeColumns` knows the type id and nothing else about its target. Node type ids are already unique (`TypeBuilder`'s type-id uniqueness check); a `@nodeId(typeName:)` leaf naming a `@table` type decodes through a catalog decode minted from that table's metadata, and two tables can publish one `__NODE_TYPE_ID` (the `collide_a` / `collide_b` fixtures do). When one of two decode targets sharing a type id declares a legacy shape and they differ in table or current key, the conversion would rewrite ids the other target owns. The mint (`BuildContext.catalogNodeDecode`) refuses it on the leaf. Two targets over one table with one current key share the shape, which is right: they read one id space.

There is no separate "declared on a type with no node key" rule. Every `NodeType` has a node key (declared, catalog, or primary key; a missing primary key is already refused), and a catalog class stating `__NODE_LEGACY_KEY_COLUMNS` without `__NODE_KEY_COLUMNS` is malformed metadata, below.

### Typed rejections

The rules land as typed `Rejection` leaves with their own `lspCode()`. A typed leaf is a sealed record a diagnostic consumer switches on instead of parsing prose; the `@node` failures in `TypeBuilder.buildTableType` are untyped `Rejection.structural` today.

* A new sub-seal `NodeKeyError` of `Rejection.AuthorError`, under the `graphitron.node-key.` namespace, alongside `PivotError` and `WireCoercionError`:
  * `LegacyShapeMissesKeyColumns(typeName, missing)`, `graphitron.node-key.legacy-misses-key-columns`;
  * `LegacyShapeReordersKey(typeName)`, `graphitron.node-key.legacy-reorders-key`;
  * `LegacyShapeRepeatsColumn(typeName, column)`, `graphitron.node-key.legacy-repeats-column`;
  * `CatalogDisagreement(typeName, argument, sdlColumns, catalogColumns)`, `graphitron.node-key.catalog-disagreement`, where `argument` is `keyColumns` or `legacyKeyColumns`;
  * `LegacyShapeTypeIdShared(typeId, typeName, otherTypeName)`, `graphitron.node-key.legacy-type-id-shared`.
* An unresolvable column in either list is the existing `Rejection.unknownNodeIdKeyColumn` (`AuthorError.UnknownName`, attempt kind `NODEID_KEY_COLUMN`, with candidates), which the diagnostics glossary already documents for `keyColumns` and `ArgmappingProjectionDefects` already mints. `TypeBuilder`'s joined `keyColumns` prose moves onto it, reporting the first unresolved column; the LSP already flags every misspelled element separately.

The existing `keyColumns` rules that the new argument shares (unresolved column, SDL disagreeing with the catalog) are typed alongside, because leaving them prose would give one fault two shapes depending on which argument it is in. The rules the new argument does not share stay `Rejection.structural`: `@node` without `implements Node`, a defaulted key over a table with no primary key, and malformed catalog metadata.

### SDL against the catalog

The current key keeps its rule: SDL `keyColumns` wins, differing column sets reject (now `CatalogDisagreement` with `argument = keyColumns`), differing order warns. The legacy shape is resolved independently, then checked against whichever current key won:

* only SDL states it, or only the catalog does: that list is the legacy shape;
* both state it: they must be the same columns in the same order, compared case-insensitively by SQL name, else `CatalogDisagreement` with `argument = legacyKeyColumns`.

There is no order-warning tier for the legacy shape. Its order is the wire format of ids already issued; SDL cannot pin a different order of them, and a list in the wrong order would read every legacy id's values into the wrong columns.

## Implementation

* **Directive.** `graphitron-model`'s `directives.graphqls`: `@node` gains `legacyKeyColumns: [String!]` with a description. `BuildContext` gains `ARG_LEGACY_KEY_COLUMNS`.
* **Catalog.** `JooqCatalog` reads `__NODE_LEGACY_KEY_COLUMNS` beside the other two constants, through the same `statedConstant`, and `validateFields` checks it with the `__NODE_KEY_COLUMNS` shape rules (non-empty `Field<?>[]`, no null entry, every entry a column of the table via `columnOf`) plus "declared without `__NODE_KEY_COLUMNS`". A failure is `NodeIdMetadataLookup.Malformed`, so `nodeIdMetadataDiagnostic` and the capture's `nodeMetadataOf` both see it. `NodeIdMetadata` gains `legacyKeyColumns` (empty when the constant is absent). The relational rules (coverage, reordering, repeats) are not checked here, because the current key they are checked against may come from SDL.
* **Model.** `NodeType` gains `List<ColumnRef> legacyKeyColumns` after `nodeKeyColumns`, empty when none is declared. `HelperRef.Decode` gains `legacyColumnShape` beside `outputColumnShape`, empty when none, so the emitter reads one shape off every decode it emits, node type or catalog decode alike; `buildNodeType` fills both from one list, as it already does for the current key. `BuildContext.TargetKeys.OfCatalog` gains the legacy list so `catalogNodeDecode` can fill it. `NodeProvenance` does not gain an axis: no reader asks where a legacy shape came from, and when both sources state one they are equal by the rule above.
* **Classifier.** `TypeBuilder.buildTableType` reads the argument where it reads `keyColumns` (`argStringList(objType, DIR_NODE, ...)`), resolves it with `svc.resolveKeyColumn`, applies the SDL-against-catalog rule, and runs one shared check, a static method on a small `LegacyKeyShape` helper beside `NodeIndex`, returning the first `NodeKeyError` or empty. `BuildContext.catalogNodeDecode` runs the same check on catalog metadata and the type-id sharing check against `NodeIndex` and earlier mints; it returns a rejection the leaf reports instead of a decode.
* **Emitter.** `NodeIdEncoderClassGenerator` collects every `HelperRef.Decode` with a non-empty legacy shape, one entry per type id, and emits a private static nested record holding the legacy columns (table constants, as `decodeColumns` callers pass them), their parse classes (`SlotParse.parseClasses`) and the `int[]` position map, plus a static map from type id to it, filled in a static initializer (generated sources target Java 17 and avoid `var`; `Map.of` caps at ten entries). It emits `toCurrentId`, the shared slot-check helper, the hit-logging set, and the one-line gate at the top of `decodeColumns`. No call site changes.
* **Javadoc.** `DeleteRows.IdempotentByPrimaryKey` and `TypeFetcherGenerator.missingRowEcho`, per the echo decision above; the `NodeIdEncoderClassGenerator` class javadoc gains a paragraph on the conversion.
* **LSP.** `LspVocabulary` binds `DirectiveArg("node", "legacyKeyColumns")` to `CatalogColumnBinding`, as `keyColumns` is bound, so completion, per-element typo flags and hover come for free. `RejectionSeverityCoverageTest` gains a sample per new arm.
* **Fact store.** No new relation. The store's node relations (`graphitron_ast_node_keycolumn_entry`, `sql_node_key_column`, `graphitron_node_keycolumn`) feed the LSP hover, the MCP schema view and `StoreNodeTables`, none of which has a question about the legacy shape; the emitter reads the classified model. Capturing the argument would add a relation no consumer reads. A store reader that wants it later adds it under the modeling discipline then.
* **Docs.** The drafts above; the new arms in `docs/architecture/explanation/typed-rejection.adoc`; the `nodeid-key-column` entry of `diagnostics-glossary.adoc` names `legacyKeyColumns` beside `keyColumns`. `RejectionResidueDrainageTest.RESIDUE_LEAVES` gains the new leaves.
* **Fixtures.** `NodeIdFixtureGenerator.Metadata` gains an optional legacy list, emitted as `__NODE_LEGACY_KEY_COLUMNS`, on one fixture table with a spare column for the catalog-form pipeline tests.

## Tests

* **Generated encoder, unit** (`graphitron-sakila-example`, `test/internal`, beside `NodeIdFoldWarningTest`, over the execution fixture's type): `toCurrentId` returns the id unchanged for a current id, a type with no legacy shape, a foreign type id and an unknown arity; converts a legacy id to exactly what `encode` produces from the key values; returns `null` for a legacy id whose dropped value does not parse; logs the type id once at `INFO` and then at `DEBUG`, and no client value. `decodeColumns` over a legacy id returns the current key values.
* **Catalog, unit** (`JooqCatalogNodeIdMetadataTest`): each malformed `__NODE_LEGACY_KEY_COLUMNS` case, the legacy constant without the key constant, and a well-formed one surfacing on `NodeIdMetadata`.
* **Build rejections** (a classification test beside `MutationDmlNodeIdClassificationTest`): one case per `NodeKeyError` arm and per argument for `CatalogDisagreement`; an unresolved `legacyKeyColumns` and an unresolved `keyColumns` entry both as `UnknownName` with `NODEID_KEY_COLUMN`; the type-id sharing rule over the `collide_a` / `collide_b` fixtures; and the accepting cases (dropped column first, legacy shape over a defaulted primary-key current key, SDL and catalog equal).
* **Pipeline:** `NodeType.legacyKeyColumns()` and `decodeMethod().legacyColumnShape()` filled from SDL and from catalog metadata; a catalog decode for a `@table`-only `@nodeId(typeName:)` target carrying the table's legacy shape.
* **Execution** (`graphitron-sakila-example`). A new node type over `inventory`, `InventoryItem`, with current key `(inventory_id)` and legacy shape `(store_id, inventory_id, last_update)`: dropped columns on both sides of the kept one, so the by-name pick is pinned end to end. `inventory` is chosen because `rental` references it, which a `@reference` case needs; `InventoryUnit` already covers the table as a superkey node with its own type id, so every leaf names its type with `typeName:`. Legacy ids are minted in the test with `encode`:
  * `node` and `nodes` resolve a legacy id, including one whose `last_update` has since changed in SQL, and return the current-form id; `InventoryUnit`'s stale-id `node` case stays null beside it, the contrast between keeping a superkey and narrowing one.
  * A `@nodeId` filter argument and a `@nodeId @reference` input field over `rental` accept a legacy id.
  * Single and bulk UPDATE and DELETE by a legacy id hit the row; the payload ids, and the `missingAsDeleted` echo of a legacy id for a missing row, are current-form.
  * A bulk UPDATE carrying one row's legacy and current ids is refused by the duplicate guard, as two current ids are.
  * A legacy id with an unparseable `last_update` is rejected the way a malformed id is today.
  * `_entities` in the federated schema resolves a legacy id, over a node type there given a legacy shape.

## Relationship to R1093

The two items are independent, so `depends-on` stays empty. R1093 (shipped) keeps a superkey node key and stops it from blocking DML; it stands on its own for superkeys that are meant to stay, such as a tenant column outside the primary key. This item is for keys that should shrink. Once it lands, sis moves `GodkjentPolitiattest` off the superkey: the node key becomes the four primary-key columns and the five-column list becomes the legacy shape. From then on sis no longer relies on R1093 for this type.

Under database-per-tenant the tenant column must already be in the current key (`nodeIdKeyMissingTenantColumnOnTenantScopedTableRejects` in `TenantBindingClassificationTest`). Covering the current key therefore means the legacy shape has it too, and routing reads the converted id like any other.

## Out of scope

* **More than one legacy shape.** sis needs one. A list of shapes is a small extension if a second narrowing ever happens, with one more build rule: no two shapes of one arity.
* **Type-id changes.** Converting ids across a type-id change is a type rename.
* **The sis catalog side.** kjerneapi-codegen emitting `__NODE_LEGACY_KEY_COLUMNS` from a new `configuration.xml` element next to `<plattformId>` lives in fs-plattform.

## Provenance

Requested 2026-10-26 by the sis Graphitron 10 port session. The sis side keeps R1093 as its interim, while `GRAPHITRON10_STATUS.md` D21 still holds the five-column key. Narrowing the key is a deliberate change to the id format. sis must still record that decision with its id consumers: UiA's RPA flow from OPPF-2253 is the only known external holder of these ids.
