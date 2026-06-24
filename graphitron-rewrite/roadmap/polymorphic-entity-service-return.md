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

This is the largest item in the cluster and is design-led, not a localized patch. Pin the restored
surface against the confirmed 9.3 behaviour, then decompose.

1. **Establish the 9.3 baseline (gate before Spec sign-off).** Capture exactly which shape 9.3
   accepted (interface return directly? union with a record carrier? a payload with `errors`?) and the
   resolver it generated. The restored 10.x surface must match that contract, not a new invention.
2. **Choose the mechanism.** Two routes:
   - (a) **Auto-fetch of `@service`-returned records, extended to polymorphic returns.** Aligns with
     the maintainer stance (a `@service` returns PK-populated `TableRecord`s and Graphitron fetches the
     rest): the service returns the concrete record per branch and Graphitron resolves it to the right
     participant via the `__typename` / PK machinery the multitable query path already uses, with no
     `@splitQuery` payload field. `ServiceDirectiveResolver.projectReturnType`
     (`ServiceDirectiveResolver.java:220-225`) currently rejects `PolymorphicReturnType` outright; this
     route replaces the reject with a resolved polymorphic-return arm.
   - (b) **Payload field carrying `[Applikasjon] @splitQuery` + `errors: [Error]`.** Depends on R366
     (list-cardinality emit) and R367 (single-cardinality record-parent arm) landing first; the
     `errors` envelope rides the existing `ErrorChannel` wiring.
   Recommend (a) as primary, with (b) falling out once R366/R367 land. Pin the route at Spec with
   principles-architect.
3. **Relax the adjacent guards only if route (b)/union shapes are in scope.** The union-member rule
   (`TypeBuilder.java:767`, the non-error `@record` carrier is what trips it) and the dangling-payload
   classifier (`GraphitronSchemaBuilder.java:584-593`) need touching only for the union/payload shapes;
   route (a) leaves both alone.

Front-matter `depends-on` is left empty because the recommended route (a) has no hard dependency;
route (b) would add R366 + R367. Resolve the route at Spec before wiring `depends-on`.

## Cross-links

- R366 (list-cardinality polymorphic `@splitQuery` emits non-compiling code) and R367
  (single-cardinality polymorphic child on a record-backed parent is rejected) are the concrete
  emitter blockers on the payload route; both must land for shape (b).
- Related to the `mutations-errors` theme items on payload/error-envelope construction.
