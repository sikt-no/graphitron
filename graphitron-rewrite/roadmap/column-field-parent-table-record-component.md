---
id: R239
title: "Lift ColumnField.parentTable from emitter parameter to record component"
status: Backlog
bucket: architecture
priority: 6
theme: structural-refactor
depends-on: []
created: 2026-05-25
last-updated: 2026-06-26
---

# Lift ColumnField.parentTable from emitter parameter to record component

Surfaced by R237 Phase 2 as a (b-cheap) structural-lift candidate. The classifier produces a `ChildField.ColumnField` only on a table-backed parent, but the parent table itself is currently threaded into `TypeFetcherGenerator.generateTypeSpec` as a parameter rather than carried on the `ColumnField` record. The switch arm at `TypeFetcherGenerator.java:319` reads `parentTable` from the parameter and throws `IllegalStateException` if null, treating a structurally-precluded reachability as a defensive guard.

Lift: add a non-null `parentTable` record component to `ColumnField`, populated at construction in `BuildContext.classifyOutputField` (the producer site). The compact constructor takes care of non-null enforcement; the `generateTypeSpec` parameter and the `IllegalStateException` arm disappear. This lift adds structural type-system enforcement of the table-backed-parent contract, replacing the documentation duplication R237 already removed.

Pre-conditions: none outstanding — R237 has shipped. The lift stands on its own structural merit.

Out of scope: the multi-record threading on `MethodRef.StaticOnly` × `ReturnTypeRef.TableBoundReturnType` (R240's territory).
