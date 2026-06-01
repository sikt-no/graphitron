---
id: R269
title: "Null-guard split-query key extraction for nullable to-one records"
status: Backlog
bucket: structural
depends-on: []
created: 2026-06-01
last-updated: 2026-06-01
---

# Null-guard split-query key extraction for nullable to-one records

The split-query key extraction `GeneratorUtils.buildAccessorKeySingle` / `buildAccessorKeyMany` (lines 249-296) reads a nested jOOQ record off the parent backing and calls `.into(<PK columns>)` on it with no null guard: `ElementRecord elt = ((Backing) source).accessor(); RecordN<...> key = elt.into(T.PK1, T.PK2)`. When the accessor returns null (a nullable to-one `@table` relation that resolves to no row on an otherwise successful parent), this NPEs with `Cannot invoke "...Record.into(...)" because "__elt" is null` instead of resolving the field to null. The sibling table-parent path `buildKeyExtractionWithNullCheck` (lines 324-356) already short-circuits with `CompletableFuture.completedFuture(null)` when a key component is null; the accessor path never got the equivalent guard. This is independent of the error channel (it fires on the success arm, with no `@error` payload involved) and was split out of R268, which fixes only the error-arm case via the `Outcome` arm-switch (on the `ErrorList` arm the loader is never dispatched, so the key extraction is not reached). Scope: add the null short-circuit to the accessor key-extraction helpers; pin with an execution-tier fixture where a nullable to-one `@table` field resolves null on a non-error parent and the field renders null rather than throwing.
