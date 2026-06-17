---
id: R322
title: "Runtime value-agreement check for multiple @nodeId decodes onto shared record columns"
status: Backlog
bucket: feature
priority: 4
theme: nodeid
depends-on: []
created: 2026-06-17
last-updated: 2026-06-17
---

# Runtime value-agreement check for multiple @nodeId decodes onto shared record columns

When Graphitron decodes **more than one** `@nodeId` onto the columns of a single
record, two decodes can target the **same** column. Two `@nodeId(typeName:
"Film")` fields on a `FilmRecord` both land on `film_id`; a same-table identity
plus an FK reference whose child column is part of the PK both land on that
column; two composite FKs can overlap on a shared child column. Whether that is
a problem is **data-dependent**: if both decoded ids agree on the shared column
it is harmless, if they disagree one decoded value is silently overwritten
(last-write-wins). A silent overwrite of a caller-supplied id is exactly the
"no silent drops" failure the generator is supposed to avoid; the disagreement
is only observable at runtime (the values come off the wire), so it cannot be a
build-time rejection.

## Origin

Deferred from R315 (FK-reference `@nodeId` on jOOQ-record `@service` params).
R315 removes the legacy "more than one `@nodeId`" gate
(`InputBeanResolver.java:266`) because an FK-reference record legitimately
carries several `@nodeId` references. With that gate gone, the overlapping-column
case is no longer rejected and no agreement check exists yet, so R315 ships
**last-write-wins** for the overlap edge (its motivating consumer has only
disjoint-column references, so the edge does not arise there). This item adds the
guard. See R315's validator-mirror checklist for the deferral note.

## Desired behavior

When build-time analysis finds a column written by **more than one present
projection**, emit a runtime check that compares the decoded values for that
column and throws a `GraphqlErrorException` on disagreement (the same failure
shape the materialization emitter already throws on a decode arity / type
mismatch, `JooqRecordInstantiationEmitter.java:98`). Properties:

- **Fires only on build-time-detected overlap.** Disjoint-column records (every
  R315 motivating mutation, and the common case) emit zero extra code:
  pay-for-what-you-use.
- **Composes with nullability.** The check is over the *present* writers only; an
  omitted nullable key is not a writer and cannot conflict.
- **Pre-coercion string comparison** of the decoded values is sufficient.

## Scope

Applies wherever Graphitron decodes multiple `@nodeId`s onto one row's columns:

- the `@service` jOOQ-record path (R315's `create<Record>` materialization helper);
- the `@mutation` `TableInputType` Graphitron-owned-DML path (decode of
  `@nodeId` references onto the record / SET clause).

In both, the decode-and-write step is generated code that runs **before** control
leaves Graphitron (before the service call, before the INSERT/UPDATE executes),
so the check has a natural home there. **Out of scope:** the R150/R195
InputBean-member path (each record member is a separate Java object, so there are
no shared columns to reconcile).

## Why its own item (not folded into R315)

The two in-scope paths materialize differently today: `create<Record>` uses
`decodeValues` + `fromArray`, the mutation path uses `NodeIdDecodeKeys` /
`ColumnReferenceField`. Doing this once, rather than bolting a check onto each
emitter independently, wants a shared "materialize a record's columns from N
`@nodeId` decodes, asserting shared-column agreement" sub-step that both paths
call (the same "one site owns the fork" move R315's D3 makes for FK pairing).
That is a real refactor with its own design surface.

## Open question for Spec

Emit the agreement check **inline** (per the existing `:98` throw) vs. factor it
into a small **runtime-support method** both paths call. The shared-sub-step
framing above leans toward the latter, but the inline form keeps the generated
code self-contained and debuggable; resolve at Spec.
