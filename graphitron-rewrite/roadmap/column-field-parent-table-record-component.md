---
id: R239
title: "Lift ColumnField.parentTable from emitter parameter to record component"
status: Backlog
bucket: architecture
priority: 6
theme: structural-refactor
depends-on: []
created: 2026-05-25
last-updated: 2026-05-25
---

# Lift ColumnField.parentTable from emitter parameter to record component

Surfaced by R237 Phase 2 as the (b-cheap) candidate keyed `column-field-requires-table-backed-parent`. The classifier produces a `ChildField.ColumnField` only on a table-backed parent, but the parent table itself is currently threaded into `TypeFetcherGenerator.generateTypeSpec` as a parameter rather than carried on the `ColumnField` record. The switch arm at `TypeFetcherGenerator.java:319` reads `parentTable` from the parameter and throws `IllegalStateException` if null, treating a structurally-precluded reachability as a defensive guard.

Lift: add a non-null `parentTable` record component to `ColumnField`, populated at construction in `BuildContext.classifyOutputField` (the producer site). The compact constructor takes care of non-null enforcement; the `generateTypeSpec` parameter and the `IllegalStateException` arm disappear. The annotation `column-field-requires-table-backed-parent` retires with R237 regardless of this lift — the lift adds structural type-system enforcement where R237 already removes the documentation duplication.

Pre-conditions: R237 Phase 1 has shipped (rubrics no longer push the annotation pattern). The lift can proceed independently of R237's Phase 4 timing.

Out of scope: the multi-record threading on `MethodRef.StaticOnly` × `ReturnTypeRef.TableBoundReturnType` (R240's territory).
