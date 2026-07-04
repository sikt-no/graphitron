---
id: R314
title: "Dissolve the re-fetch (reentry) leaf fields: emit reentry by switching on the model"
status: Spec
bucket: architecture
priority: 4
theme: structural-refactor
depends-on: [coordinate-lowers-to-datafetcher-queryparts, decompose-sourcekey, collapse-split-and-record-table-leaves]
created: 2026-06-15
last-updated: 2026-07-04
---

# Dissolve the re-fetch (reentry) leaf fields: emit reentry by switching on the model

A vertical slice of the emit re-platforming: the **reentry** (re-fetch) family becomes the first emit
family driven by the R333 model (the coordinate's facts and the named-seam method graph) instead of
leaf identity. Re-specced 2026-07-04 onto R333's vocabulary; the original 2026-06-15 body targeted the
`carrier` / `intent` / `mapping` types R316 deleted and is superseded in full.

## What reentry is, in the model's terms

Today the generator emits reentry by switching on **leaf identity** — `RecordTableField` (the merged
source-gated leaf's record arm after R432), `RecordLookupTableField`, `RecordTableMethodField`,
`ServiceTableField`, the root `QueryServiceTableField` / `MutationServiceTableField`, and projected
`DmlTableField`. The reentry SQL is **uniform** across the family: the keyed re-query
`f(keys, correlation)` — `VALUES(idx, key...) JOIN target ON correlation ORDER BY idx`, scatter —
where `correlation` is PK self-identity (the degenerate case of the FK join; R333 *Two levels of
natural key*). The per-member variation is exactly what the facts own:

- **The source endpoint** owns the key lift: N reads through the ordinary field-level locator facts,
  gated on the held object's shape (jOOQ record → column projection; Java object → member read, with
  `@sourceRow` as authored provenance on that arm). Post-R431 this lives on the decomposed
  source-object / locator facts, not on `SourceKey.Reader`. There is no liveness axis; "same keys,
  same rows" is the whole contract.
- **The operation** owns the per-member payload: `Fetch` a plain re-projection, `Lookup` the
  positional correspondence (`LookupMapping`), `ServiceCall` the service lift, the DML arms their
  projected payload.
- **The target** owns the projection: the re-projected `@table` (`$fields`), pagination on
  `Paginate`.

So `emit(reentry) = f(source facts, operation, target)` with no `instanceof` on leaf classes.

## Goals

1. **Emit the keyed re-query off the model.** Re-platform the reentry emit in `TypeFetcherGenerator`
   / `SplitRowsMethodEmitter` to compose the one primitive from the facts above instead of dispatching
   per re-fetch leaf.
2. **Dissolve the re-fetch leaves.** Reduce `RecordLookupTableField`, `RecordTableMethodField`,
   `ServiceTableField`, and the reentry arms of the root service and projected DML fields to thin
   records or remove them, once their distinguishing data lives on the facts.
3. **Retire `dispatchPerformsReFetch` and its mirror test.** The generator consults
   `OutputField.requiresReFetch()` (already axis-derived since R305/R316) directly, so the derivation
   and the emit cannot drift by construction — "if two consumers evaluate the same predicate over a
   model field, the branch belongs in the model."
4. **First seam-named family.** The reentry query unit gets regime-1 naming (model-carried method
   names, thread J), making it the first family thread I's **bidirectional** closure check (every
   emitted method is one command's output; every callee resolves to a committed command) can assert.
   The level-1 characterization oracle over the whole emit ships earlier under R333's Ready
   deliverable and is the harness this slice runs against.

## Scope

The reentry emit family only — not all of generation. Other emit families (inline projection arms,
plain `@splitQuery` beyond the R432-merged leaf, polymorphic, connections, DML internals) stay
leaf-dispatched until their own slices. No input-side work; no `TypeFetcherGenerator` decomposition
beyond what the reentry path forces (R7 stays separate). The seam-worklist verdict this slice must
state: row 15 (channel catch / early-return arms), since the service reentry path crosses the error
channel; other open promote-or-inline verdicts (rows 12–14, 16) stay per-slice calls for later
families.

## Acceptance

**Execution-tier equivalence, not byte-for-byte output equality** (settled 2026-07-04): the goal is
gradual improvement toward R333/R222, so the slice may normalize generated-code shape as it goes. The
gates are the R305 reentry execution tier (`SingleRecordPayloadDmlTest`, the service-producer
execution test, the LocalContext error path) — same rows, same order, error paths intact — plus the
`@classified` corpus classifying unchanged, plus the level-1 closure oracle staying green across the
re-platforming.

## Lineage

Follows **R305** (dissolved `SingleRecordTableField`, made `requiresReFetch` axis-derived, kept the
emit leaf-dispatched), **R316** (the `(source, operation, target)` pivot; its changelog entry pins
"Collapses to one carrier under R314" on `Operation.Call`), and **R333** (the model this slice
consumes; its Relationships section records the decided sequence). Run-up: R431
(`decompose-sourcekey`) then R432 (`collapse-split-and-record-table-leaves`, the beachhead), then this
item. The original body's insight — reentry SQL is uniform, variation belongs to the axes — survives
verbatim; only the vocabulary and the slot destinations moved.
