---
id: R535
title: "Migrate the inline @tableMethod leaf onto TableExpr.MethodCall"
status: Backlog
bucket: architecture
priority: 5
theme: model-cleanup
depends-on: []
created: 2026-07-25
last-updated: 2026-07-25
---

# Migrate the inline @tableMethod leaf onto TableExpr.MethodCall

R314 dissolved `RecordTableMethodField` by building `TableExpr.MethodCall`: the
record-sourced `BatchedTableField` now carries the developer's `@tableMethod` as the
terminal hop's table expression (`FieldBuilder.java:6252`), so alias generation, terminus
checks, and `$fields` projection read the method node uniformly with `Catalog` and
`RoutineCall` nodes. The inline `@tableMethod` leaf did not migrate: `ChildField.TableMethodField`
(`ChildField.java:687`) predates the two-axis step model and still carries its `MethodRef`
inline beside the `joinPath`, and `TypeFetcherGenerator.buildChildTableMethodFetcher`
(`TypeFetcherGenerator.java:1992`) emits a bespoke per-row fetcher from that inline field
rather than through the join-path machinery. The split shows in `JoinPathEmitter`, which
treats a `MethodCall` node reaching a general materialization site as a wiring bug
(`JoinPathEmitter.java:111`) because only the batched rows-method path can legitimately
produce one today. R333 names this migration as the outstanding residue of the join-path
model (`roadmap/coordinate-lowers-to-datafetcher-queryparts.md`, "The join path" and the
resolved `@routine`/`@tableMethod` open question).

The work: represent the inline leaf's developer method as its join path's terminal
`TableExpr.MethodCall` node, delete the inline `MethodRef` carriage, and route the emit
through the shared join-path machinery, lifting the `JoinPathEmitter` wiring-bug guard for
the now-legitimate site. Behavior is preserved; this is a representation change. Design
questions for Spec: whether a distinct `TableMethodField` leaf survives at all (mirroring
how R432/R314 dissolved the re-fetch family into source-gated `Batched*` leaves), and
whether the current runtime `UnsupportedOperationException` on multi-hop and
condition-join paths (`ChildField.java:699` javadoc) stays a runtime surface or becomes a
classification-time rejection once the path is model-expressed.
