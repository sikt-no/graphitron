---
id: R436
title: "Unsafe into() key extraction collides with multiset aliases and escapes error redaction"
status: Backlog
bucket: bug
priority: 5
theme: service
depends-on: []
created: 2026-07-06
last-updated: 2026-07-06
---

# Unsafe into() key extraction collides with multiset aliases and escapes error redaction

A `@service` split field on a `@table` parent derives its DataLoader key by
blind-converting the whole parent source record: `GeneratorUtils.buildKeyExtraction`'s
`SourceKey.Wrap.TableRecord` arm (GeneratorUtils.java:519-522) emits
`XRecord key = ((Record) env.getSource()).into(Tables.X)`. When a *sibling* object
field on the same parent is multiset-backed, its projection is aliased to the GraphQL
field name (`TypeFetcherGenerator`, `.as(fieldName)`), and if that alias case-insensitively
collides with a physical column on the parent table, jOOQ's `into(...)` maps the nested
`Result` value into the physical column's type and throws a `MappingException`. This
surfaced in a downstream consumer whose `Utdanningsmulighet` `@table` type selected
multiset-backed object fields `dager`/`tider` (aliases colliding with the physical
`UTDANNINGSMULIGHET.DAGER` `daterange` / `TIDER` `tstzrange` range columns) alongside a
`@service` split field `statushistorikk`; every parent node crashed resolving the split
field. The collision is aggravated by R426: `TypeClassGenerator.RequiredProjection.FullParentRow`
already widens the parent SELECT to `table.fields()` for the `TableRecord` wrap, so both the
physical range columns and the colliding multiset aliases coexist in the record `into()` walks.

Two distinct defects, both worth fixing here:

1. **Unsafe key derivation.** `into(Tables.X)` reifies *every* column on the parent record by
   name, not just the key columns the DataLoader actually needs. Any sibling projection whose
   alias shadows a physical column name (multiset object fields are the concrete trigger, but the
   hazard is general) poisons the conversion. Contrast the `Wrap.Record` arm (GeneratorUtils.java:509-517),
   which projects only `sourceKey.columns()` via `into(col, ...)` and is immune. The `TableRecord`
   arm should likewise read only the key/PK columns (or otherwise avoid a whole-record `into`),
   reconciled with R426's documented contract that this wrap hands the service body a
   fully-populated parent record.

2. **Escapes error redaction.** `keyExtraction` is emitted synchronously in the DataFetcher body
   *before* the DataLoader dispatch and its guard: `DataLoaderFetcherEmitter.build` places the
   extraction at DataLoaderFetcherEmitter.java:141, ahead of `loader.load(key, env)` (:142) and the
   `.thenApply(...).exceptionally(ErrorRouter...)` tail (:143). A throw out of `into()` therefore
   propagates straight out of `DataFetcher.get()` and the `.exceptionally` router that would
   `redact` it never runs. The raw jOOQ message, which includes a dump of the record's data, leaks
   into the API response, a privacy hole, and repeated across every parent node it bloats responses
   (the reported symptom: OTel gRPC export exceeding its 4 MiB limit).

Minimal repro to encode as a fixture: a `@table` type that selects a multiset-backed object field
whose alias collides with a physical column name, alongside a `@service` split field on the same
type. Any API consumer hitting that combination crashes today.

Fix directions to weigh at Spec time: (a) narrow the `TableRecord` key read to the key columns
rather than a whole-record `into`, and/or reconstruct the typed record from only the projected
key/PK columns; (b) ensure synchronous key-extraction failures are routed through `ErrorRouter`
redaction rather than escaping the fetcher (either wrap the extraction in the async chain or guard
it explicitly), so a codegen/selection defect can never leak raw jOOQ diagnostics to clients.
