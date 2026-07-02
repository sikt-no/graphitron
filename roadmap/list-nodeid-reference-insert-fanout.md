---
id: R420
title: "Support list-valued @nodeId+@reference on INSERT inputs (row fan-out)"
status: Backlog
bucket: feature
theme: nodeid
depends-on: []
created: 2026-07-02
last-updated: 2026-07-02
---

# Support list-valued @nodeId+@reference on INSERT inputs (row fan-out)

On the read side, `[ID!] @nodeId` filter leaves are a designed shape (IN predicate, see `NodeIdReferenceFilterPipelineTest`), but on the write side a list-valued node-id reference on an INSERT input has no semantics: the input maps to one inserted row with one FK slot, so a field like `parentIds: [ID!]! @nodeId(typeName: "T") @reference(path: [...])` would have to mean row fan-out, one inserted row per decoded id, with the sibling scalar fields repeated across the fanned-out rows. Motivating case: FS's `OpprettUtdanningsspesifikasjonshierarkiInput`, where callers today must repeat the whole input element per parent id. Deciding whether fan-out is wanted at all is part of this item; if yes, the INSERT emitter's per-cell binding (`TypeFetcherGenerator.buildInsertDecodeLocals` / `buildPerCellValueList`) needs a list-aware decode-and-expand path, and the R419 build-time rejection gets lifted for the supported shape. Related: R57 covers list arity for the `TranslatedFk` join-translation case on the read side; this item is the `DirectFk` write path.
