---
id: R300
title: "First-class jOOQ routine support: RoutineCall carrier + Procedure intent"
status: Backlog
bucket: feature
priority: 5
theme: service
depends-on: [dimensional-model-pivot]
created: 2026-06-13
last-updated: 2026-06-13
---

# First-class jOOQ routine support: RoutineCall carrier + Procedure intent

Graphitron has no first-class way to back a field with a database **routine** (stored function or
procedure). A user can wrap a routine in a `@externalField` / `@tableMethod` Java method, but that routes
through the *domain* side (graphitron reflects an opaque `Field<X>`), losing the typing jOOQ already
provides. This item adds routines as a recognised, catalog-side construct.

The shape was worked out against R222's **Field-side dimensional model (refined 2026-06-13)**; the
headline is that **the model absorbs routines with very little change**, precisely because we map against
jOOQ, not the database directly. jOOQ generates *typed* bindings, `Field<T>` for a scalar function,
`Table<R>` for a table-valued function, a `Routine` for a procedure, with UDT/composite returns getting
generated UDT types too. So graphitron **builds** the call with full signature and return-type knowledge,
which lands routines squarely on the **catalog / build** side (not the domain / consume side that
`@service` and `@externalField` occupy).

## What changes, per axis

- **carrier** — unchanged. A function used in a read sits on `Query`/`Source`; a side-effecting
  procedure sits on `Mutation`. The existing carrier gate places read-vs-write correctly.
- **mapping** — already covered by the jOOQ return type: scalar function -> `Column`, table-valued
  function -> `Table` (both catalog). UDT/composite returns ride jOOQ's generated UDT types and tie into
  the future *embedded-records* extension; no new mapping value needed now.
- **intent**:
  - **Functions fold into `Fetch`** plus a routine-binding slot. A function computes a value or rows,
    the same result-contract as a `Fetch` reached via a call (the method-backed pattern). No new read
    intent.
  - **Procedures need a new write intent** (working name `Procedure` / `Call`). A `CALL` with side
    effects and OUT params is not a CRUD verb and cannot be inlined as a `Field`/`Table`, so it does not
    fold into `Insert`/`Update`/`Delete`/`Upsert`. This is the one genuinely new intent.
- **derived** — the re-fetch rule gains a term: `(Service | DML | Procedure) x Table -> re-fetch`
  (a procedure yielding a table-shaped result re-projects, like DML -> `@table`). `FetchRelated` /
  `new-query` unchanged.

## New carrier/slot

A `RoutineCall` carrier (the jOOQ `Routine`/`Field` reference + param bindings) joins R222's `MethodCall`
family alongside `ServiceMethodCall` / `ConditionCall` / `TableMethodCall` / `ExternalFieldCall`, with its
own directive marker (a `@routine` / `@function` / `@procedure` form, TBD) and a Stage-2 walker producer.
So it is additive walker-carrier work, not a new dimension.

## The service parallel, and where jOOQ breaks it in our favour

A routine is to the *catalog* what a service is to the *domain*: an opaque call graphitron invokes without
introspecting the body. But because routines are jOOQ-typed, graphitron knows the kind (function vs
procedure) and the return shape, so reads fold into `Fetch` and the write is one named intent, no coarse
`{QueryService, MutationService}`-style polarity is needed.

Settled forks (from the design conversation):

- **Polarity trust.** Trust the jOOQ kind (function -> read, procedure -> write) for now, exactly as the
  service side currently trusts its declared polarity. A future hardening can mark the connection
  read-only to *guarantee* read polarity. Not blocking.
- **UDT/composite mapping.** jOOQ generates types for UDTs, so composite returns are covered by those
  types; this folds into the future embedded-records extension rather than a new mapping value.

## Scope when picked up

- Add the `Procedure` (write) intent and its carrier gate (`Mutation`).
- Add the `RoutineCall` carrier + directive + Stage-2 walker producer.
- Extend the re-fetch derivation to include `Procedure x Table`.
- Functions ride the existing `Fetch` + the routine-binding slot; no read-intent change.

## Dependencies

Builds on the refined field-side model (R222) and its materialisation: the `Procedure` intent and
`RoutineCall` carrier extend the `carrier x intent x mapping` model that R299 migrates the corpus onto and
R290 materialises on the field. Best sequenced after R290 so it extends a materialised model rather than
the leaf-reconstruction stopgap.

## Out of scope

- The future embedded-records extension (UDT/composite field shaping) ; adjacent, tracked separately.
- Read-only-connection enforcement to guarantee routine read polarity ; a later hardening.
