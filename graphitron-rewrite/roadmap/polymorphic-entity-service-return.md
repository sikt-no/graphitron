---
id: R365
title: "Support returning a polymorphic entity (interface/union) from a @service mutation"
status: Spec
bucket: bug
priority: 5
theme: interface-union
depends-on: []
created: 2026-06-24
last-updated: 2026-06-24
---

# Support returning a polymorphic entity (interface/union) from a @service mutation

## Problem

A `@service` mutation (or query) that operates on a multitable interface cannot return the affected
polymorphic entity (interface or union), with or without a typed-error envelope. Every candidate
shape is rejected at codegen. This shape worked in graphitron 9.3, so under the rewrite's
feature-equivalence goal this is a regression, not merely an unbuilt feature: the generator's "not yet
supported" message understates it. Confirm the exact 9.3 surface that worked (which of the shapes
below, or another) so the restored API matches rather than inventing a new one.

## Rejected shapes today (exact messages, confirmed)

1. `@service` returning the interface directly (single or list):
   `@service returning a polymorphic type is not yet supported` (`ServiceDirectiveResolver.java:224`,
   in `projectReturnType`). Only an all-`@error` nullable-list "errors channel" lifts via
   `liftToErrorsField`; anything else falls to `Resolved.Rejected`.
2. A union of a `@record` success carrier plus `Error`-implementing types:
   `implementing type '<X>' is not table-bound (missing @table directive)` (`TypeBuilder.java:767`;
   the union is classified via `buildParticipantList(..., allowNonTableMembers=false)` at line 918).
   Nuance for the Spec: `@error` members are admitted (as `ParticipantRef.Unbound`); it is the
   non-error `@record` success carrier that trips the rule. "All union members must be @table-bound" is
   the wrong gloss.
3. A hand-declared payload object `{ field: ..., errors: [Error] }` returned by `@service`:
   `... returns SDL Object type '<XPayload>', which did not classify into the model (no @table or
   record-backed binding, no producer-backed carrier promotion, not embedded as a nesting projection
   of a table-backed parent) ...` (`GraphitronSchemaBuilder.java:584-593`, in
   `rejectDanglingTypeReferences`). The original report quoted "no @table/@record binding"; the actual
   text is "no @table or record-backed binding".

## Plan

This is the largest item in the cluster and is design-led, not a localized patch. The route is
**pinned to (a) — auto-fetch extended to polymorphic returns** (rationale below); a single
investigation gate (the 9.3 baseline) remains before implementation, and it constrains *fidelity of
the restored surface*, not the mechanism.

### Pinned route: (a) auto-fetch of `@service`-returned records, extended to polymorphic returns

The service returns the concrete PK-populated `TableRecord` per branch, and Graphitron resolves it to
the right participant and fetches the rest — no `@splitQuery` payload field, no `errors`-envelope
wiring required for the base shape. This is pinned over the alternative for three reasons:

- **Aligns with the documented maintainer stance** ("a `@service` returns PK-populated `TableRecord`s
  and Graphitron fetches the rest"). Participant resolution is *direct*: the returned jOOQ
  `TableRecord` carries its own type identity, so the concrete participant is read off the record's
  runtime class (matched against each `ParticipantRef.TableBound.table().recordClass()`). No
  synthesized `__typename` discriminator column or PK round-trip is needed to *identify* the type —
  unlike the multitable *query* path, whose discriminator exists only to recover the Java type that
  UNION-ALL row projection erases.
- **No hard dependency.** Route (b) (payload field carrying `[Applikasjon] @splitQuery` +
  `errors: [Error]`) depends on **R366** (list-cardinality emit) and **R367** (single-cardinality
  record-parent arm) landing first; route (a) depends on neither, so it can ship independently and
  unblocks the reported regression sooner.
- **Smallest guard surface.** Route (a) touches only the `PolymorphicReturnType` arm of
  `projectReturnType`. It leaves the union-member rule (`TypeBuilder.java:767`) and the
  dangling-payload classifier (`GraphitronSchemaBuilder.java:584-593`) untouched — those only need
  relaxing for the union/payload shapes route (b) introduces.

Route (b) is retained as a **follow-up**, not an alternative: once R366 + R367 land, the payload +
`errors`-envelope shape falls out on top of route (a) and can be added then. It is not on this item's
critical path.

### Remaining gate before implementation: confirm the 9.3 baseline (fidelity, not mechanism)

The mechanism is settled; what must still be captured is the *exact restored surface* so 10.x matches
9.3 rather than inventing a new contract. This is an investigation gate, not a design fork — capture,
before writing code:

1. Which return shape 9.3 actually accepted: the interface/union directly (single? list?), a union
   with a `@record` success carrier, or a `{ field, errors }` payload object.
2. The resolver 9.3 generated for it (how it dispatched to the concrete type, whether it auto-fetched
   or required the service to return a fully-populated record).
3. Whether 9.3 supported the typed-error envelope on this shape, or only the bare polymorphic return.

If 9.3's accepted shape was the bare interface/union return, route (a) restores it directly. If 9.3's
shape was the `{ field, errors }` payload, that is route (b) territory and this item gains
`depends-on: R366, R367` — so the baseline finding is also what decides whether `depends-on` stays
empty.

### Implementation (route (a))

`ServiceDirectiveResolver.projectReturnType` (`ServiceDirectiveResolver.java:214-225`) currently
yields `Resolved.Rejected(Rejection.deferred("@service returning a polymorphic type is not yet
supported", ""))` for the `PolymorphicReturnType` arm whenever `liftToErrorsField` returns null.
Replace that reject with a resolved polymorphic-return arm that carries the participant set and the
service method, and emit a fetcher that, for each returned `TableRecord`, **dispatches on its runtime
record class** against the participant set (`ParticipantRef.TableBound` already pairs
`table().recordClass()` with `typeName()`) to pick the participant arm, sets the GraphQL `__typename`
to that participant's type, and auto-fetches the selected columns by PK against that participant's
table. The Java type of the returned record *is* the discriminator, so this needs neither the query
path's synthesized `__typename` column nor its PK-based type reconstruction — those exist only because
UNION-ALL projection erases the Java type, which a returned typed record does not. The participant's
`table()` is still used, but only for the by-PK auto-fetch, not for type identification. The
all-`@error` nullable-list "errors channel" (`liftToErrorsField`) continues to lift as today; this
widens the *non*-errors arm.

### Discriminability: same-table participants need a `@discriminator`

Record-class dispatch tells participants apart by **table identity**, so it only works when each
table-bound participant maps to a *distinct* table. Two participant types backed by the same table
share a `recordClass`, so a returned record matches both arms and cannot be discriminated from its
Java type alone. (The multitable *query* path R363 lowers onto has the same blind spot: two UNION
branches over one table would tag every row of that table with both `__typename`s. So this rule is a
shared invariant of multitable polymorphism, not route-(a)-specific.)

Add a build-time rule in `validateQueryInterfaceField` / `validateQueryUnionField` (and the
`@service`-return arm), with three outcomes:

- **Distinct tables** → discriminate by record class (the base case above). No directive needed.
- **Same-table participants WITH a discriminator** → discriminate by the discriminator column value.
  The directives already exist: `@discriminator(value:)` is read into
  `ParticipantRef.TableBound.discriminatorValue` for *all* participants by `buildParticipantList`
  (`TypeBuilder.java:753-757`), so the per-type values are available. The discriminator *column*
  `@discriminate(on:)` is currently parsed only on the single-table `TableInterfaceType` path
  (`TypeBuilder.java:1337`); the multitable path would need to read it for the colliding subset too.
  The fetcher then reads that column off the returned record and matches the value to each
  participant's `@discriminator(value:)`.
- **Same-table participants WITHOUT a discriminator** → **validation error**, e.g. "interface/union
  '`<X>`' maps types '`<A>`' and '`<B>`' to the same table '`<T>`' with no `@discriminator` to tell
  them apart; add `@discriminate(on:)` + `@discriminator(value:)`, or split the types." This fails the
  build instead of silently mis-dispatching a returned record to the wrong arm.

The validation error is the **floor** and is in scope day one (it closes a real misdispatch hole and
guards R363's query path over the same interfaces). Whether the `@discriminator`-based same-table
*dispatch* lands here or as a follow-up is an implementer call to pin at Spec; the directive plumbing
already exists, so leaning on it is preferred over inventing a new discriminator signal.

Front-matter `depends-on` stays empty under route (a). Wire `depends-on: R366, R367` only if the 9.3
baseline forces the payload shape (route (b)).

## Cross-links

- R366 (list-cardinality polymorphic `@splitQuery` emits non-compiling code) and R367
  (single-cardinality polymorphic child on a record-backed parent is rejected) are the concrete
  emitter blockers on the payload route; both must land for shape (b).
- Related to the `mutations-errors` theme items on payload/error-envelope construction.

## Spec-review revisions (2026-06-24)

Reviewer (Spec gate, session ≠ author) pinned route (a) (auto-fetch extended to polymorphic returns)
as the mechanism — it has no hard dependency, reuses the multitable query path's participant
resolution, and leaves the union-member and dangling-payload guards untouched — and recast route (b)
as a post-R366/R367 follow-up rather than a live alternative. The remaining 9.3-baseline gate was
narrowed from "choose the mechanism" to "confirm the restored surface's fidelity (and whether it
forces `depends-on: R366, R367`)". The baseline still requires a real 9.3 artifact to confirm, so this
stays in Spec; a fresh session signs it off to Ready once the baseline is captured.

Also scoped in: a discriminability rule for route (a) — participants must be distinguishable, by
distinct table (record-class dispatch) or by `@discriminator` when they share a table; same-table
participants with no `@discriminator` are a build-time validation error rather than a silent
misdispatch. This also guards R363's query path over the same interfaces.
