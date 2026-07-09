---
id: R220
title: "Consolidate looksLikeSourcesShape, couldBeSourcesShape, and classifySourcesType into one predicate"
status: Backlog
theme: service
bucket: architecture
depends-on: []
created: 2026-05-21
last-updated: 2026-05-21
---

# Consolidate looksLikeSourcesShape, couldBeSourcesShape, and classifySourcesType into one predicate

`ServiceCatalog` now has three closely-related predicates over the same Java parameter shapes, each subtly different: `looksLikeSourcesShape` (`Row<N>` / `Record<N>` lists only, used by the root-coordinate diagnostic), `couldBeSourcesShape` (R214 addition; adds `TableRecord` to the above, used by the inference gate to exclude SOURCES-shape params from candidate binding), and `classifySourcesType` (gated by `parentPkColumns.isEmpty()` and produces a typed `SourcesShape` result). The principles-architect review (round 1, finding 5) flagged this as the "same predicate evaluated by multiple consumers" smell — the resolver is under-specified, and the three predicates have already drifted apart in subtle ways.

Concretely, the `TableRecord` arm of `classifySourcesType` only fires when `parentPkColumns.isEmpty()` is false; `couldBeSourcesShape` doesn't model the coordinate axis. So a root-coordinate `List<XRecord>` parameter (the canonical InputBeanResolver shape, called out explicitly in `looksLikeSourcesShape`'s own doc as the case excluded after R185) is currently excluded from R214's inference even though SOURCES classification would never accept it at root anyway. The author has to write `argMapping` for the canonical case R214 was meant to handle. This isn't a correctness bug (the existing diagnostic still surfaces), but it's a missed-inference case that the under-specification produces.

Direction: split `classifySourcesType` into two predicates:

1. `Optional<SourcesShape> classifySourcesShape(Type paramType)` — pure type-shape predicate, no coordinate gate. Returns the typed shape for `List<RowN>` / `List<RecordN>` / `List<TableRecord>` / `Set<>` variants.
2. `boolean accepts(SourcesShape, List<ColumnRef> parentPkColumns)` — coordinate-aware acceptance gate. Returns false for root.

Then `looksLikeSourcesShape` (used by the root-coordinate diagnostic) becomes `classifySourcesShape(...).isPresent() && !accepts(...)`. `couldBeSourcesShape` (used by R214's inference gate) becomes `classifySourcesShape(...).isPresent() && accepts(...)` — the inference correctly skips only those parameters the coordinate actually accepts. The R214 missed-inference case at root falls naturally into the inferrable set; the per-param SOURCES loop at child coordinates still wins.

Scope notes for Spec:

- **Caller migration.** `looksLikeSourcesShape` has two call sites in `ServiceCatalog`'s diagnostic builder (root-batch-diagnostic and the diagnostic-arm fork). `couldBeSourcesShape` has one (R214 inference). `classifySourcesType` has one. All four migrate; the compiler exhaustiveness check on `Optional<SourcesShape>` is the safety net.
- **Naming.** `couldBeSourcesShape` is the wrong name post-split; rename to something that signals "this param is SOURCES-classifiable at this coordinate" (`isAcceptedSourcesShape`?). Bikeshed at Spec time.

Files in play: `ServiceCatalog.java` (`classifySourcesType`, `looksLikeSourcesShape`, `couldBeSourcesShape`, the diagnostic builder around line 280-345, the inference gate in `inferBindingsByType`). Tests: existing SOURCES-shape tests stay green; add a new test pinning the previously-excluded `List<XRecord>` at root case now inferring positionally.

Related: R214 (introduced `couldBeSourcesShape` as a partial fix; this item is the principled consolidation), R185 (narrowed `looksLikeSourcesShape` to exclude `TableRecord` for the InputBeanResolver shape — that narrowing is exactly what this consolidation makes coordinate-aware rather than type-aware).
