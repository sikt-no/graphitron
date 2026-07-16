---
id: R323
title: "Multi-parent NestingField sharing: BatchKey leaves"
status: Backlog
bucket: architecture
priority: 6
theme: classification-model
depends-on: []
created: 2026-06-17
last-updated: 2026-07-15
---

# Multi-parent NestingField sharing: BatchKey leaves

Follow-up to R23 (which shipped the `TableField` arm of multi-parent `NestingField` sharing).
`GraphitronSchemaValidator.compareNestedFieldsShape` still rejects a plain-object nested type
shared across multiple `@table` parents when any shared leaf is a BatchKey carrier —
`SplitTableField`, `SplitLookupTableField`, `RecordTableField`, `RecordLookupTableField`, or a
`Record*MethodField` — with the "not yet supported across multiple parents" deferral. Unlike the
projected `TableField` leaf R23 admitted, these variants carry per-field DataLoader registration
and per-parent rows-method generation keyed off the outer parent context, so reconciling them
across a shared nested type is real work, not just a validator gate: each variant's
`LoaderRegistration` / `SourceKey` resolution and rows-method signature would need to agree (or be
made per-parent) across the sharing parents. Each variant has its own considerations; the spec
should decide whether to land them together or per-variant.

Re-anchor at pickup (added 2026-07-15): the leaf enumeration above and the `SourceKey` carrier
this item leans on are being restructured underneath it. R431 (`decompose-sourcekey`, In Progress)
decomposes `SourceKey` onto facts, and R432 (`collapse-split-and-record-table-leaves`) folds
`SplitTableField` + `RecordTableField` (and the lookup twins) into one source-gated leaf, which is
why this item was gated on both (both now shipped: R431 and R432 are Done). Re-derive the variant
list and the per-parent reconciliation
target against the decomposed facts and the collapsed leaf set when this reaches Spec, rather than
against the pre-R431/R432 shapes named here.

Open re-scoping question carried over from R23: `LookupTableField` moved into
`TypeFetcherGenerator.PROJECTED_LEAVES` alongside `TableField`, so it may be emitter-safe across
parents by the same argument R23 used for `TableField` (each parent's `$fields` emits its own
inline arm; the reified read pulls by field name). It was left out of R23's titled scope. Decide
here (or in its own item) whether to add a parallel `LookupTableField` arm to
`compareNestedFieldsShape` — but only after repeating R23's emitter-safe verification for its
inline-lookup emission and reified read. Do not assume it is safe without that check.
