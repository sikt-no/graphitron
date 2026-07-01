---
id: R323
title: "Multi-parent NestingField sharing: BatchKey leaves"
status: Backlog
bucket: architecture
priority: 6
theme: model-cleanup
depends-on: []
created: 2026-06-17
last-updated: 2026-06-17
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

Open re-scoping question carried over from R23: `LookupTableField` moved into
`TypeFetcherGenerator.PROJECTED_LEAVES` alongside `TableField`, so it may be emitter-safe across
parents by the same argument R23 used for `TableField` (each parent's `$fields` emits its own
inline arm; the reified read pulls by field name). It was left out of R23's titled scope. Decide
here (or in its own item) whether to add a parallel `LookupTableField` arm to
`compareNestedFieldsShape` — but only after repeating R23's emitter-safe verification for its
inline-lookup emission and reified read. Do not assume it is safe without that check.
