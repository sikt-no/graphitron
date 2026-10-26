---
id: R1100
title: "A node type can narrow its key and still accept ids minted under the old key"
status: Backlog
bucket: feature
priority: 3
theme: nodeid
depends-on: []
created: 2026-10-26
last-updated: 2026-10-26
---

# A node type can narrow its key and still accept ids minted under the old key

## Goal

A node type can declare a *legacy key shape*: an older column list its ids were once encoded from. Graphitron then accepts an id in either shape wherever it decodes one. An id in the legacy shape is converted to the current shape before anything else reads it. Every id Graphitron emits uses the current shape. A schema can therefore drop a column that should never have been in the node key without invalidating the ids clients and other subgraphs already store.

The case from sis is `GodkjentPolitiattest`, over view `POLITIATTESTGODKJENT` (type id `230`). Its primary key is `(INSTITUSJONSNR_EIER, PLNR, STUDIEPROGRAMKODE, LISENSTYPEKODE)`. Its node key has those four columns plus `DATO_FORSTE_GYLDIGE`, the date the approval is valid from. The date entered the key in 2023 with no stated reason. It is a mutable attribute: `endreGodkjentePolitiattester(fraDato:)` changes it, so today every date change also changes the row's id. R1093 (in flight at filing) lets UPDATE and DELETE accept such an id, but reads still filter on the date and every emitted id still carries it. sis wants to:

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

sis declares its node keys in jOOQ metadata rather than SDL, so the same fact also needs a catalog form beside `__NODE_KEY_COLUMNS` (see Implementation).

After this lands:

* `node`, `_entities`, `@nodeId` inputs and filters, and UPDATE and DELETE accept both `base64("230:i,p,s,l")` and `base64("230:i,p,s,l,2024-01-05")`. Both identify the row by its four key columns.
* The legacy id's date is checked to be a valid value of its column and then discarded. It is never a filter, so a legacy id whose date has gone stale still resolves.
* Every id in a response is the four-value form, including the id returned for a row that was looked up by a legacy id.

## The conversion

The core is a per-type conversion from a legacy id to a current one, run at the top of the generated `NodeIdEncoder.decodeColumns`, before it reads its input. Every decode surface reaches a key slot through `decodeColumns`, so every one gets the conversion with no call-site change: the per-type `decode<Type>` helpers, `RecordDecodeFragments`, `HandleMethodBody` (`node` / `_entities`), the `CompositeDecodeHelperRegistry` helpers, `JooqRecordInstantiationEmitter` and `EntityFetcherDispatchClassGenerator`.

1. Peek the type id. If the type has no legacy shape, return the id unchanged.
2. Split the payload. If its arity is the current key's, return the id unchanged.
3. If its arity is the legacy shape's, decode every value against its legacy column with `ColumnValues.exact`, the same check `decodeColumns` applies today. Any failure returns `null`, so a malformed legacy id is rejected like any malformed id.
4. Pick the current key's columns out of the decoded legacy values by column name and re-encode them with `encode(typeId, …)`. Return that id.
5. Any other arity returns the id unchanged, and `decodeColumns`' existing arity check (`values.length != columns.length`) rejects it.

Matching by column name rather than by position means the dropped column need not be last, and a legacy shape may list the key columns in another order. For sis the dropped column happens to be last.

The conversion is also a public static method on the generated `NodeIdEncoder`, for example `NodeIdEncoder.toCurrentId(String base64Id)`. A service, migration script or batch job can then rewrite stored ids without going through GraphQL. It runs one way only, legacy to current. The other direction would need a database read, because the dropped value is not in a current id.

## Build-time rules

`legacyKeyColumns` is refused at build time when:

* **It does not cover the current key.** Some current key column is missing from it, so a legacy id cannot be converted without reading the row.
* **Its arity equals the current key's.** The two shapes could not be told apart by arity, which the conversion relies on.
* **It names a column the table lacks.** The rejection an unresolvable `keyColumns` entry gets today.
* **It names the same column twice.** `keyColumns` has no such rule today; the legacy shape needs one because the conversion matches by column name.
* **It is declared on a type with no current node key.** A legacy shape only makes sense as the previous form of a key.

Today's `@node(keyColumns:)` failures in `TypeBuilder.buildTableType` are untyped `Rejection.structural`, and there is no nodeid lspCode family to file under (the one nodeid lspCode, `graphitron.wire-coercion.nodeid-decoded-type`, is a wire-coercion leaf). The new rules should land as typed `Rejection` leaves carrying their own `lspCode()`; Spec settles their shape, and whether the existing `keyColumns` rules are typed alongside them.

The type id does not change. Converting ids across a type-id change is a type rename and is out of scope.

Under database-per-tenant the tenant column must already be in the current key (`nodeIdKeyMissingTenantColumnOnTenantScopedTableRejects` in `TenantBindingClassificationTest`). Covering the current key therefore means the legacy shape has it too, and routing reads the converted id like any other.

## Implementation

* **Model.** `NodeType` gains `legacyKeyColumns()` beside `nodeKeyColumns()`, empty when none is declared. `NodeProvenance` gains an axis recording whether it came from SDL or the catalog, as its `keyColumns` axis does (`DEFAULTED` has no meaning for it).
* **SDL.** `@node` gains `legacyKeyColumns: [String!]`, read in `TypeBuilder.buildTableType` where `keyColumns` is read (`argStringList(objType, DIR_NODE, ARG_KEY_COLUMNS)`) and passed through `buildNodeType`.
* **Catalog.** A jOOQ table may carry `__NODE_LEGACY_KEY_COLUMNS` beside `__NODE_KEY_COLUMNS`, read by the same catalog reader (`JooqCatalog.nodeIdMetadata`, with `nodeIdMetadataDiagnostic` for malformed values). In sis, kjerneapi-codegen emits it from a new `configuration.xml` element next to `<plattformId>`. That change lives in fs-plattform, not here. The catalog-only decodes a `@nodeId` leaf makes for a `@table` type that is not itself a node (`GraphitronSchema.catalogNodeDecodes()`) read the same metadata, so they get the legacy shape too.
* **Emitter.** `NodeIdEncoderClassGenerator` emits a static table of legacy shapes keyed by type id: the column list and its parse classes, in the form `decodeColumns` takes them today. It also emits the conversion method, which `decodeColumns` calls first. No call site changes.
* **Docs.** Add a paragraph to the `@node` reference, a "Narrowing a node key" how-to, and the new rejections in `docs/architecture/explanation/typed-rejection.adoc` (plus `RejectionSeverityCoverageTest` in `graphitron-lsp` and `RejectionResidueDrainageTest.RESIDUE_LEAVES` in `graphitron`).

## Things to settle in Spec

* **Uses of the raw wire string as identity.** Anything that compares, groups or caches by the id string sees a legacy id and its current form as different ids. R1093's plan moves the bulk UPDATE duplicate guard onto decoded values. Audit the rest, for example DataLoader keys and dedup sets, and make sure each one reads the converted id.
* **`missingAsDeleted` echo.** The echo for a missing row can be the client's id as sent, or its converted form. R1093's plan echoes the exact input string for a superkey node, because the extra column has no other source. For a primary-key node the echo is re-encoded from the decoded key values, so a legacy input would echo in current form by default, and the converted form is the only one Graphitron could mint itself. Pick one and document it.
* **SDL against catalog.** When both `@node(legacyKeyColumns:)` and `__NODE_LEGACY_KEY_COLUMNS` are present, decide whether to mirror the `keyColumns` rule (SDL wins, differing column sets reject, differing order warns).
* **Knowing when the legacy shape can go.** The conversion should log or count legacy hits per type at debug level, so a schema owner can see when stored legacy ids have stopped arriving and delete the declaration.
* **More than one legacy shape.** sis needs one. A list of shapes is a small extension if a second narrowing ever happens, but leave it out until someone needs it.

## Tests

* **Unit.** The conversion: unchanged for a current id, a type with no legacy shape, a foreign type id and an unknown arity; converted for a legacy id; `null` for a legacy id whose dropped value does not parse.
* **Build rejections.** One test per rule above.
* **Pipeline.** `NodeType.legacyKeyColumns()` is filled from SDL and from catalog metadata.
* **Execution** (`graphitron-sakila-example`). Use a node over a table no other node claims, with current key `(film_id, category_id)` and legacy shape `(film_id, category_id, last_update)` on `film_category`. Mint legacy ids in the test with `encode`:
  * `node` and `_entities` resolve a legacy id, including one whose `last_update` has since changed in SQL, and return the current-form id.
  * A `@nodeId` filter argument and a `@nodeId @reference` input accept a legacy id.
  * UPDATE and DELETE by a legacy id hit the row, and the payload ids are current-form.
  * A legacy id with an unparseable `last_update` is rejected the way a malformed id is today.
  * `NodeIdEncoder.toCurrentId` returns the id `encode` produces from the key values.

## Relationship to R1093

The two items are independent, so `depends-on` stays empty: the narrowed key is the primary key, and UPDATE and DELETE by a converted id need nothing from R1093. R1093 keeps a superkey node key and stops it from blocking DML. It stands on its own for superkeys that are meant to stay, such as a tenant column outside the primary key. This item is for keys that should shrink. Once it lands, sis moves `GodkjentPolitiattest` off the superkey: the node key becomes the four primary-key columns and the five-column list becomes the legacy shape. From then on sis no longer relies on R1093 for this type.

## Provenance

Requested 2026-10-26 by the sis Graphitron 10 port session. The sis side keeps R1093 as its interim, while `GRAPHITRON10_STATUS.md` D21 still holds the five-column key. Narrowing the key is a deliberate change to the id format. sis must still record that decision with its id consumers: UiA's RPA flow from OPPF-2253 is the only known external holder of these ids.
