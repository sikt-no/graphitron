---
id: R366
title: "Emit batch (loadMany) dispatch for list-cardinality polymorphic @splitQuery on record parents"
status: In Review
bucket: bug
priority: 4
theme: interface-union
depends-on: []
created: 2026-06-24
last-updated: 2026-06-24
---

# Emit batch (loadMany) dispatch for list-cardinality polymorphic @splitQuery on record parents

## Problem

A payload or wrapper field that is a list of a multitable interface via `@splitQuery` on a
record-backed (Pojo / JavaRecord) parent is accepted by codegen and a fetcher is emitted, but the
generated Java does not compile: the loader call references a loop-local `key` that is out of scope at
the call site (`cannot find symbol: variable key`). It passes `graphitron:validate` and fails javac.
New on 10.x. This is the polymorphic / multitable variant of the non-polymorphic
`wrapper { x: T @splitQuery }` compile bug that was fixed earlier for the table-backed case; the fix
did not cover the polymorphic emitter.

## Mechanism (confirmed by source trace)

- The list path is `MultiTablePolymorphicEmitter.buildBatchedListFetcher` (around lines 830-875) plus
  `buildBatchedListRowsMethod`, routed at the child overload (around lines 169-172,
  `if (isList && !tableBound.isEmpty())`). Correct the original report's attribution: the broken
  builder is `buildBatchedListFetcher`, not `buildScalarPerParentFetcher` (that is the
  single-cardinality fetcher, see R367).
- `buildBatchedListFetcher` unconditionally emits `return loader.load(key, env)` (around line 870)
  after key extraction via `GeneratorUtils.buildRecordParentKeyExtraction` (`GeneratorUtils.java:205`),
  which dispatches on the reader:
  - `ColumnRead` to `buildFkRowKey` (lines 244-247): a single method-scoped `key`, in scope at the
    call site, compiles.
  - `AccessorCall` + `MANY` to `buildAccessorKeyMany` (lines 338-365): emits
    `List<RowN<..>> keys = new ArrayList<>(); for (...) { RowN<..> key = element.into(...); keys.add(key); }`,
    where `key` is loop-local, so `loader.load(key, env)` at the call site references the out-of-scope
    local. (`ProducedRecordRead` + `MANY` at lines 291-306 has the identical hazard.)
- The emitter never consults the LOAD_MANY dispatch / `loadMany` (no `loadMany` reference exists in
  it), so it cannot distinguish ONE from MANY at the load site. The non-polymorphic split-query path
  (`TypeFetcherGenerator.buildRecordBasedDataFetcher`) branches on `field.emitsSingleRecordPerKey()`
  and gets this right.

Trigger nuance: the broken path is the `AccessorCall` / `MANY` reader, which arises when the parent is
a Pojo / `@record` carrier exposing a list accessor (for example a `@service`-returned payload
carrier). The original report's `@reference`-on-`@table`-parent example resolves to `ColumnRead` /
`ONE` and compiles; the reporter's actual repro (parent is a `@service`-returned `@record`) plausibly
hits the `AccessorCall` path. Confirm the resolved reader for that exact shape when building the
fixture.

## Plan

`buildBatchedListFetcher` (`MultiTablePolymorphicEmitter.java:830-875`) hardcodes
`return loader.load(key, env)` (line 870). That is correct only for single-key readers: the
record-parent key extraction (`GeneratorUtils.buildRecordParentKeyExtraction`,
`GeneratorUtils.java:205-239`) declares a single `key` for the single readers (`buildFkRowKey`,
`buildAccessorKeySingle`, ...) but a `List<key> keys` with a loop-local `key` for the MANY readers
(`buildAccessorKeyMany`, 338-365; `buildProducedRecordsKeyMany`, 291-306). So for `AccessorCall` /
`MANY` (and `ProducedRecordRead` / `MANY`), `loader.load(key, env)` references the out-of-scope loop
local.

1. **Branch the dispatch on reader cardinality at the load site.** When
   `parentSourceKey.cardinality()` is `ONE`, keep `loader.load(key, env)`. When `MANY`, dispatch
   `loader.loadMany(keys, env)` over the collected `keys` list the MANY extraction already declares.
   The cardinality is on the `SourceKey` the fetcher already holds, so no model change is needed; this
   mirrors how `TypeFetcherGenerator.buildRecordBasedDataFetcher` branches on
   `emitsSingleRecordPerKey()` for the non-polymorphic path.
2. **Reconcile the value shape for MANY.** `load(key)` yields `List<Record>` (one parent's bucket);
   `loadMany(keys)` yields `List<List<Record>>` (one bucket per element key). The field's declared
   value is a single `List<Record>`, so the MANY arm must flatten the buckets (stream `flatMap` /
   concat) before the async tail. **Design fork to flag to principles-architect:** confirm
   flatten-and-concat is the intended semantics for a list-polymorphic field assembled from multiple
   element keys (vs. preserving per-element grouping). Recommend flatten, since the field surface is a
   flat `[Applikasjon!]`. This is the substantive work; it is not a `key` -> `keys` rename.
3. **Floor guarantee.** If a reader/cardinality combination is deliberately out of scope, reject it
   cleanly in `FieldBuilder` (as R367's single-cardinality guard does) rather than emit non-compiling
   code. No path reachable by `buildBatchedListFetcher` should produce a dangling `key`.

## Tests

- Pipeline tier already classifies the `AccessorCall` / `MANY` shape
  (`RecordParentMultiTablePolymorphicPipelineTest.childInterfaceField_recordParent_accessorKeyedMany`
  and the union sibling), but those assertions do not inspect the method body.
- Add a compilation-tier fixture with a Pojo / record-backed parent exposing a list polymorphic
  `@splitQuery` child, so javac exercises the dispatch and catches the `key` / `keys` mismatch.

## Cross-links

Sibling of R367 (single-cardinality of the same field, an explicit deferred-rejection guard); shares
`MultiTablePolymorphicEmitter` with R363; on the critical path for R365 shape (b).
