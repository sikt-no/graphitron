---
id: R1100
title: "A node type can narrow its key and still accept ids minted under the old key"
status: In Progress
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

The pick in step 4 is a position map the store derives by column (`current_position`, below), so the dropped columns need not be last and the legacy shape may list the key columns in another order.

On a successful conversion it logs the type id, once per type id per process at `INFO` and at `DEBUG` after that, the pattern `reportFold` already uses (a concurrent set of seen type ids). It logs no client-supplied value. This is what tells a schema owner the legacy shape can go: there is no metrics dependency in the generated runtime, and a consumer that wants a count can count the lines.

The conversion runs one way only, legacy to current. The other direction would need a database read, because the dropped value is not in a current id.

### Where the raw id string is identity

A legacy id and its current form are different strings for one row, so anything keyed on the wire string would see two ids. An audit of the emitters found every dedup, grouping, ordering, tenant-routing and echo decision works on values `decodeColumns` returned, and so on converted values: the bulk UPDATE duplicate guard (`seenKeys` over decoded cells), the bulk DELETE `IN` list, UPSERT, `@lookupKey` VALUES rows, the `_entities` grouping by decoded tenant, `requireColumnAgreement`, and the `missingAsDeleted` echo. No change is needed there. Three sites read the raw string and stay as they are:

* **Type dispatch** (`QueryNodeFetcher` `peekTypeId` into `EntityFetcherDispatch.typenameForTypeId`, the polymorphic `@nodeId` dispatch in `RecordDecodeFragments` and `MultiTablePolymorphicEmitter`) routes on the type id before decoding. That is correct because a legacy shape belongs to its type id, which does not change. A type-id change is a type rename and out of scope.
* **`nodes(ids:)`**' DataLoader is keyed on the raw string, so a legacy id and its current form in one call are two loads of one row. Each position still gets its row; the cost is one extra VALUES row, as for two spellings of one id today.
* **`SelectionOccurrences.requireConsistentArguments`** compares raw argument maps when one field is selected under both `edges.node` and `nodes`. A client passing the legacy form on one path and the current form on the other gets the existing "conflicting arguments" error. It fails loud, never with wrong data, and needs a client mixing two forms of one id in one query.

### The `missingAsDeleted` echo

A DELETE with `missingAsDeleted: true` answers a missing row with an id built from the key values its WHERE compared (`TypeFetcherGenerator.missingRowEcho`), never from the input string. For a legacy input those are the converted values, so the echo is the current-form id. That is the only form Graphitron can mint itself, it is what `node(id:){ id }` and a deleted row's `RETURNING` already answer, and it keeps "every id in a response is the current form" true without an exception. The javadoc on `DeleteRows.IdempotentByPrimaryKey` and `missingRowEcho` that says the echo is "the id the client sent" is reworded to "the id the client sent, in the current shape".

## A store fact keyed by type id

The legacy shape is a fact of the wire type id, not of a GraphQL type or a decode helper: `decodeColumns` knows the type id and nothing else about its target, and an id's type id is what names its id space. It lands in the fact store (the SQL model `graphitron-model.sql` that capture fills from the SDL, the jOOQ catalog and the classpath), and nowhere in the walked `GraphitronSchema` model. That follows the strangler rule in `docs/architecture/explanation/pipeline-overview.adoc`: while the walk and the store are both live, a new capability is a new fact relation, never a new leaf component or walk-side registry. Nothing in `NodeType`, `HelperRef.Decode`, `NodeProvenance`, `TypeBuilder` or `BuildContext` changes.

Keying by type id also closes a gap a per-helper shape would leave. `BuildContext.resolveOneRecordDecode` serves the `@service` jOOQ-record path from catalog metadata without minting a `HelperRef.Decode`, and `JooqRecordInstantiationEmitter` calls `decodeColumns` directly; a shape carried on decode helpers would never reach it. A shape keyed by type id reaches every caller.

**Capture**, following the modeling discipline (`docs/architecture/explanation/modeling-discipline.adoc`): base facts first, shaped like the `keyColumns` facts they sit beside.

* `graphitron_node_legacykeycolumn_entry`: one `legacyKeyColumns` element of an `@node`, as written, keyed `(graph_name, type_name, position)` with a `column_ref`, beside `graphitron_node_keycolumn_entry` and written by the same capture. Wherever `keyColumns` has an AST-level decode relation as well (`graphitron_ast_node_keycolumn_entry`), `legacyKeyColumns` gets its twin, so the two arguments are captured the same way.
* `sql_node_legacy_key_column`: one position of a table class's `__NODE_LEGACY_KEY_COLUMNS` array, naming the column it resolves to, beside `sql_node_key_column`. Its foreign key is into `sql_node_metadata`, so a legacy array on a class that publishes no usable identity pair cannot be stored; that is the "legacy shape without a node key" rule, held by the schema rather than by code.
* `sql_node_legacy_key_unreadable`: a table whose class declares `__NODE_LEGACY_KEY_COLUMNS` that cannot be used (not a non-empty `Field<?>[]`, a null entry, an entry naming no column of the table, or declared on a class without a usable identity pair). Separate from `sql_node_metadata_unreadable` on purpose: the legacy constant is an independent axis, and a malformed one must not flip the table's identity to the fallback tiers. `JooqCatalog` gains a reading of the constant with its own three-way outcome (absent, present, malformed) through the same `statedConstant` and `columnOf`, and leaves `nodeIdMetadata`, `NodeIdMetadata` and `stateNodeMetadata` as they are.

**Derive**, in the anchoring phase, as a stage after `NodeKeyColumns`:

* `graphitron_node_legacy_keycolumn`: one position of the legacy key shape a wire type id answers to in a graph. Columns: `graph_name`, `type_id`, `position`, `column_name`, the table's `table_source_name` / `table_schema` / `table_name`, `current_position` (the column's position in the current key, `NULL` for a column the current key dropped; this is the pick the conversion makes, computed once here rather than by name-matching in the emitter) and `column_origin` (`SDL_PINNED` or `JOOQ_METADATA`, carried because the fix for a catalog-origin defect is in the generator configuration, not the SDL). Primary key `(graph_name, type_id, position)`, which is "one legacy shape per type id" held by the database; `UNIQUE (graph_name, type_id, column_name)` holds "no repeats"; a foreign key into `sql_column`.
* The contributors are every node type (`graphitron_node`, its current key from `graphitron_node_keycolumn`, its legacy list from the SDL entry or else its bound table's `sql_node_legacy_key_column`) and every table-bound non-node type over a table with usable node metadata (its type id from `sql_node_metadata`, its current key from `sql_node_key_column`): the population a `@nodeId(typeName:)` catalog decode or the record path can decode for.
* Like `NodeKeyColumns`, the stage anchors only shapes that pass every rule below, all positions or none. A shape that fails is absent here and present as a defect.

**Read.** A store read beside `StoreNodeTables`, assembling each type id's legacy columns as `ColumnRef`s on a `TableRef` (the constants class from `sql_schema`, as `StoreNodeTables` does) with the current positions. It rides `StoreDetections` as an emission fact, the way `keyProjections` does, and `GraphQLRewriteGenerator` hands it to `NodeIdEncoderClassGenerator.generate`.

The store's other node relations, and the LSP hover and MCP schema view reading them, are unchanged; a hover line for the legacy shape is a later nicety, not part of this item.

## Build-time rules

A legacy shape is refused when:

* **It does not cover the current key.** Some current key column is missing from it, so a legacy id cannot be converted without reading the row.
* **It is the current key reordered.** Given the rule above, a legacy shape of the current key's arity holds exactly the current key's columns; the two shapes could not be told apart by arity, which the conversion relies on.
* **It names a column the table lacks.**
* **It names the same column twice.** The conversion picks by column.
* **SDL and the catalog both state one and the lists differ**, in content or order. See below.
* **Its type id is shared with another contributor over a different table or current key.** Node type ids are already unique (`NODE_TYPE_ID_SHARED`), but a table-bound non-node type over a metadata table answers to its table's `__NODE_TYPE_ID`, and two tables can publish one (the `collide_a` / `collide_b` fixtures do). If one of them declares a legacy shape, the conversion would rewrite ids the other owns. Two contributors over one table with one current key share the shape, which is right: they read one id space.
* **The catalog constant is unreadable.** Unlike `NODE_METADATA_UNREADABLE`, which is a warning because a fallback tier answers, there is no fallback here: ignoring the constant would leave every stored legacy id failing at runtime.

### SDL against the catalog

The current key keeps its rule (SDL `keyColumns` wins, differing sets reject, differing order warns). The legacy shape is resolved independently and checked against whichever current key won:

* only SDL states it, or only the catalog does: that list is the legacy shape;
* both state it: they must be the same columns in the same order, compared case-insensitively by SQL name.

There is no order-warning tier for the legacy shape. Its order is the wire format of ids already issued, so SDL cannot pin a different order of them, and a list in the wrong order would read every legacy id's values into the wrong columns.

### How the rules are enforced

Each rule is an arm of the `graphitron_entry_defect_rule` view with a roster row, the mechanism that already carries `NODE_KEY_COLUMN_UNRESOLVED`, `NODE_WITHOUT_KEY`, `NODE_TYPE_ID_SHARED` and `NODE_METADATA_UNREADABLE`, so the editor shows every one through the `diagnostic` view with no LSP code:

* `NODE_LEGACY_KEY_COLUMN_UNRESOLVED`, at the element;
* `NODE_LEGACY_KEY_MISSES_KEY_COLUMN`, `NODE_LEGACY_KEY_REORDERS_KEY`, `NODE_LEGACY_KEY_REPEATS_COLUMN`, `NODE_LEGACY_KEY_DISAGREES_WITH_METADATA`, at the `@node` application, or for a catalog-origin shape at each bound type's `@table` as `NODE_METADATA_UNREADABLE` is;
* `NODE_LEGACY_TYPE_ID_SHARED`, at each contributing type's `@table`, naming the other on `NODE_TYPE_ID_SHARED`'s terms;
* `NODE_LEGACY_METADATA_UNREADABLE`, at each bound type's `@table`.

All are `error`. The build fails on them through a new `StoreDetections` family, `NodeLegacyKeyDefects`, on `DefaultPageSizeDefects`' pattern: it reads the stored defect rows over the classification domain and mints one `ValidationError` each, `Rejection.unknownNodeIdKeyColumn` for the unresolved element (the typed leaf `ArgmappingProjectionDefects` already mints for that lookup) and `Rejection.structural` with a message naming the code's remedy for the rest. No new `Rejection` leaf: the roster code is the stable identifier, as it is for every store-native defect.

The existing `keyColumns` rules in `TypeBuilder.buildTableType` stay as they are. They are walk-side prose with store twins already, and moving their build gate onto the store is that rule family's own migration, not this item's.

## Implementation

* **Directive.** `graphitron-model`'s `directives.graphqls`: `@node` gains `legacyKeyColumns: [String!]` with a description. The walk reads nothing from it.
* **Catalog reading.** `JooqCatalog`: the `__NODE_LEGACY_KEY_COLUMNS` reading described above, and `JooqFactCapture` writing `sql_node_legacy_key_column` or `sql_node_legacy_key_unreadable` beside its node-metadata rows, swept with them.
* **SDL capture.** The `graphitron_node_legacykeycolumn_entry` writer (and its AST twin if `keyColumns` has one) beside the `keyColumns` writers in `GraphitronTypeEntries` / `GraphitronAnchor`.
* **Store DDL.** The new relations with their comments, registered as `RelationRegistrationGateTest` and `meta_relation` require (owner, grain, mark and sweep), and `meta_gatherer_dependency` updated for the derivation's upstream reads.
* **Derivation.** A `NodeLegacyKeyShapes` stage after `NodeKeyColumns` writing `graphitron_node_legacy_keycolumn`; the defect arms in `graphitron_entry_defect_rule` and their roster rows.
* **Detection and read.** `NodeLegacyKeyDefects` as a `StoreDetections` family with its `READS`; the legacy-shape read as an emission-fact member of `StoreDetections`, assembled the way `StoreNodeTables` assembles a node's `TableRef` and `ColumnRef`s.
* **Emitter.** `NodeIdEncoderClassGenerator.generate` takes the read. It emits a private static nested record per legacy shape holding the legacy columns (table constants, as `decodeColumns` callers pass them), their parse classes (`SlotParse.parseClasses`) and the `int[]` of current positions, plus a static map from type id to it, filled in a static initializer (generated sources target Java 17 and avoid `var`; `Map.of` caps at ten entries). It emits `toCurrentId`, the shared slot-check helper, the hit-logging set and the one-line gate at the top of `decodeColumns`. No call site changes.
* **Javadoc.** `DeleteRows.IdempotentByPrimaryKey` and `TypeFetcherGenerator.missingRowEcho`, per the echo decision above; the `NodeIdEncoderClassGenerator` class javadoc gains a paragraph on the conversion.
* **LSP.** `LspVocabulary` binds `DirectiveArg("node", "legacyKeyColumns")` to `CatalogColumnBinding`, as `keyColumns` is bound, so completion and per-element typo flags come for free.
* **Docs.** The drafts above; the `nodeid-key-column` entry of `diagnostics-glossary.adoc` names `legacyKeyColumns` beside `keyColumns`.
* **Fixtures.** `NodeIdFixtureGenerator.Metadata` gains an optional legacy list, emitted as `__NODE_LEGACY_KEY_COLUMNS`, on one fixture table with a spare column, plus one malformed case for the unreadable arm.

## Tests

* **Generated encoder, unit** (`graphitron-sakila-example`, `test/internal`, beside `NodeIdFoldWarningTest`, over the execution fixture's type): `toCurrentId` returns the id unchanged for a current id, a type with no legacy shape, a foreign type id and an unknown arity; converts a legacy id to exactly what `encode` produces from the key values; returns `null` for a legacy id whose dropped value does not parse; logs the type id once at `INFO` and then at `DEBUG`, and no client value. `decodeColumns` over a legacy id returns the current key values.
* **Catalog reading, unit** (`JooqCatalogNodeIdMetadataTest`): each malformed `__NODE_LEGACY_KEY_COLUMNS` case, the constant on a class without the identity pair, a well-formed one, and a malformed legacy constant leaving `nodeIdMetadata` and `nodeMetadataOf` exactly as they were.
* **Store, seeded** (`graphitron-model`, on the seeded-store harness the node relations' own tests use): `graphitron_node_legacy_keycolumn` from an SDL shape, from a catalog shape, from both when equal, with `current_position` for a dropped column first, last and in the middle; for a table-bound non-node contributor; absent, with the right defect row, for each rule above.
* **Pipeline** (on `StoreNodeTablesTest`'s terms, real SDL over the real catalog): the legacy-shape read assembles the columns and current positions an emitted conversion spells, from SDL and from the fixture catalog's constant; each defect code becomes a build error through `NodeLegacyKeyDefects`, with the type-id sharing case over `collide_a` / `collide_b`.
* **Execution** (`graphitron-sakila-example`). A new node type over `inventory`, `InventoryItem`, with current key `(inventory_id)` and legacy shape `(store_id, inventory_id, last_update)`: dropped columns on both sides of the kept one, so the pick is pinned end to end. `inventory` is chosen because `rental` references it, which a `@reference` case needs; `InventoryUnit` already covers the table as a superkey node with its own type id, so every leaf names its type with `typeName:`. Legacy ids are minted in the test with `encode`:
  * `node` and `nodes` resolve a legacy id, including one whose `last_update` has since changed in SQL, and return the current-form id; `InventoryUnit`'s stale-id `node` case stays null beside it, the contrast between keeping a superkey and narrowing one.
  * A `@nodeId` filter argument and a `@nodeId @reference` input field over `rental` accept a legacy id.
  * Single and bulk UPDATE and DELETE by a legacy id hit the row; the payload ids, and the `missingAsDeleted` echo of a legacy id for a missing row, are current-form.
  * A bulk UPDATE carrying one row's legacy and current ids is refused by the duplicate guard, as two current ids are.
  * A legacy id with an unparseable `last_update` is rejected the way a malformed id is today.
  * `_entities` in the federated schema resolves a legacy id, over a node type there given a legacy shape.
  * A `@service` jOOQ-record input decoding a legacy id through the record path, the caller no decode helper reaches.

## Relationship to R1093

The two items are independent, so `depends-on` stays empty. R1093 (shipped) keeps a superkey node key and stops it from blocking DML; it stands on its own for superkeys that are meant to stay, such as a tenant column outside the primary key. This item is for keys that should shrink. Once it lands, sis moves `GodkjentPolitiattest` off the superkey: the node key becomes the four primary-key columns and the five-column list becomes the legacy shape. From then on sis no longer relies on R1093 for this type.

Under database-per-tenant the tenant column must already be in the current key (`nodeIdKeyMissingTenantColumnOnTenantScopedTableRejects` in `TenantBindingClassificationTest`). Covering the current key therefore means the legacy shape has it too, and routing reads the converted id like any other.

## Out of scope

* **More than one legacy shape.** sis needs one. A list of shapes is a small extension if a second narrowing ever happens, with one more build rule: no two shapes of one arity.
* **Type-id changes.** Converting ids across a type-id change is a type rename.
* **The sis catalog side.** kjerneapi-codegen emitting `__NODE_LEGACY_KEY_COLUMNS` from a new `configuration.xml` element next to `<plattformId>` lives in fs-plattform.

## Provenance

Requested 2026-10-26 by the sis Graphitron 10 port session. The sis side keeps R1093 as its interim, while `GRAPHITRON10_STATUS.md` D21 still holds the five-column key. Narrowing the key is a deliberate change to the id format. sis must still record that decision with its id consumers: UiA's RPA flow from OPPF-2253 is the only known external holder of these ids.
