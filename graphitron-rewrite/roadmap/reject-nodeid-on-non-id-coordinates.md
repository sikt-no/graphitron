---
id: R262
title: "Reject @nodeId on non-ID coordinates and federation encoded @key fields at validate time"
status: Backlog
bucket: validation
priority: 2
theme: mutations-errors
depends-on: []
created: 2026-05-29
last-updated: 2026-05-29
---

# Reject @nodeId on non-ID coordinates and federation encoded @key fields at validate time

## Problem

`@nodeId` decode is silently conditional. The SDL directive
(`directives.graphqls:370-379`) permits `@nodeId` on
`FIELD_DEFINITION | INPUT_FIELD_DEFINITION | ARGUMENT_DEFINITION` with **no
restriction to `ID`**, but every decode arm in the generator is gated on
`"ID".equals(typeName)`, and `GraphitronSchemaValidator` has **zero** `@nodeId`
checks. So `@nodeId` on a non-`ID` coordinate is accepted, the decode is dropped,
and the base64 wire String is bound raw, producing a runtime SQL bind/type error
or a silent never-matches predicate. The build is green; the failure is in
production. This is the same "compiles but breaks at runtime" family as the
input-bean `ClassCastException` (see `wire-coercion-cast-guard`), but on the
wire-decode axis rather than the cast axis.

## Findings (from the wire-format decode audit; all HIGH, none caught pre-runtime)

| # | Coordinate | Gate that drops the decode | Result |
|---|------------|----------------------------|--------|
| F | `@nodeId` on a **non-ID input-object field** | `BuildContext.java:1738` (`"ID".equals(typeName)`) | falls to column lookup `BuildContext.java:1892`; base64 String bound raw with `Direct` |
| G | `@nodeId` on a **non-ID top-level argument** | `FieldBuilder.java:1019` / `:1105` | falls to `ScalarArg.ColumnArg` `FieldBuilder.java:1143-1168`; base64 bound raw (when a column matches the arg name, the common case; otherwise degrades to `UnboundArg`, which does surface) |
| H | federation `@key` whose field is itself `@nodeId`-encoded | `HandleMethodBody.java:122` (DIRECT arm) copies `rep.get(field)` verbatim | base64 rep value bound undecoded into the `_entities` VALUES table |

Finding H contradicts the design doc's own claim
(`rewrite-design-principles.adoc:87`) that the federation rep flow "walks
alternatives over decoded values, never as opaque blobs." The compound-`id`
sub-case at `EntityResolutionBuilder.java:238-243` emits a build *warning*; the
encoded-reference-field sub-case is fully silent. No fixture exercises `@nodeId`
on a non-ID field or an encoded `@key`, so nothing catches any of F/G/H.

## Direction

These are pure validator rules; no new generation machinery, and independent of
the `wire-coercion-cast-guard` and `dimensional-model-pivot` work (shippable on
its own):

1. Reject `@nodeId` applied to a coordinate whose unwrapped SDL type is not `ID`,
   at validate time, with a typed `Rejection` naming the coordinate and the type.
   This is the single rule covering F and G (the decode arms already assume `ID`;
   the validator makes that assumption explicit instead of silently dropping
   off-`ID` uses).
2. Promote the federation encoded-`@key` case (H) from silent/warning to a typed
   rejection: a `@key` field that resolves to a `ChildField.ColumnReferenceField`
   (the `@nodeId` FK-reference shape whose output carrier is
   `CallSiteCompaction.NodeIdEncodeKeys`) cannot be consumed raw on the DIRECT
   rep path. Either reject, or (follow-on) decode the rep value via the same
   `NodeIdEncoder.decode...` the NODE_ID shape already uses.

Mirrors "Validator mirrors classifier invariants": the generator assumes
`@nodeId` ⇒ `ID`, so the validator must reject the cases that violate it rather
than letting them reach a runtime bind error.

## Tests

Pipeline/validator-tier: `@nodeId` on a `String`/`Int` input field, on a non-`ID`
argument, and on an encoded `@key` field each fail the build with the new typed
rejection. Add the missing fixtures (none exists today). Confirm the legitimate
`ID` cases and the federation NODE_ID happy path still pass unchanged.

## Out of scope

- The cast-axis defects (A-E) in `wire-coercion-cast-guard`.
- Actually *implementing* decode-into-record for encoded `@key` rep values
  (finding H remediation beyond rejection); rejection first, decode as a possible
  follow-on once a real fixture needs it.

