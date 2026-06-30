---
id: R262
title: "Reject @nodeId on non-ID coordinates and federation encoded @key fields at validate time"
status: In Progress
bucket: validation
priority: 2
theme: mutations-errors
depends-on: []
created: 2026-05-29
last-updated: 2026-06-30
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
sub-case at `EntityResolutionBuilder.buildAlternative` emits a build *warning*;
the encoded-reference-field sub-case is fully silent. No fixture exercises
`@nodeId` on a non-ID field or an encoded `@key`, so nothing catches any of
F/G/H.

> Audit-line provenance: the `#`-keyed table above is from the original
> wire-format decode audit; the line numbers have since drifted. The current
> confirmed sites are listed under [Implementation](#implementation) below. Treat
> the table as the *what*, the Implementation section as the *where*.

## Design decision: the invariant, and where it is enforced

The clean invariant is one sentence: **every coordinate carrying `@nodeId` must
have an unwrapped SDL type of `ID`.** `@nodeId` is declared `on FIELD_DEFINITION
| INPUT_FIELD_DEFINITION | ARGUMENT_DEFINITION` (`directives.graphqls:378-387`),
so the rule has to hold across all three SDL locations. Those three locations map
to **two mechanisms**:

- *Decode (input side)* — `INPUT_FIELD_DEFINITION` and `ARGUMENT_DEFINITION`. Each
  decode arm is gated on `"ID".equals(...)`, so a non-`ID` `@nodeId` falls through
  the gate to a plain column/scalar binding and the base64 wire String is bound
  raw (findings F and G).
- *Encode (output side)* — `FIELD_DEFINITION`. This is a **distinct mechanism**
  the decode gates never touch: a `@nodeId` output field becomes a
  `CallSiteCompaction.NodeIdEncodeKeys` projection. Worse than the input side, the
  scalar/enum output classifier (`FieldBuilder.java:5902`) reads
  `hasAppliedDirective(DIR_NODE_ID)` with **no** `"ID".equals` gate at all and
  builds an encoder regardless of the field's declared type, so a base64 String is
  projected into, say, a `String`- or `Int`-typed field. Finding H (federation
  encoded `@key`) is the acute, currently-silent sub-case of this output mechanism.

This matters for *where* the check lives. The check cannot be a post-classification
pass over the model: a dropped `@nodeId` leaves **no trace** in the classified
field (that absence is the bug). The detection has to read the SDL directive at a
point where the raw schema is in scope.

**Resolution (the chosen route).** Add a single centralized build-time soundness
reduction in the build walk, registered through the existing diagnostic channel
(`BuildContext.addDiagnostic(ValidationError)` → `GraphitronSchema.diagnostics()`
→ `GraphitronSchemaValidator.drainBuildDiagnostics`). This is **not a new
mechanism**: it is another instance of the R317 slice-5 "global soundness
reduction" pattern that R204 (multi-producer domain-type disagreement), R194
(case-fold collisions), node-typeId uniqueness, and the federation `@key`
reductions already populate. The pass walks the directive's three `on` locations
in `ctx.schema` and registers a typed rejection for any `@nodeId` site whose
unwrapped type is not `ID`.

Two routes were rejected:

- *Raw schema threaded into the validator.* Adding a `GraphQLSchema` field to the
  immutable `GraphitronSchema` record (so the validator could walk it directly)
  introduces a parallel access path to data the build walk already holds at the
  parse boundary, for the sake of one pass. `GraphitronSchema` deliberately does
  not hold the raw schema; `BuildContext` does. Rejected.
- *Scattered `else`-arms at each decode gate.* Hanging the rejection off `else if
  (hasAppliedDirective(DIR_NODE_ID))` at each `"ID".equals` gate **structurally
  cannot catch the output `FIELD_DEFINITION` case**, which has no decode gate of
  that shape, so it would leave finding H's whole mechanism silent exactly as
  today. It also compounds the existing smell (the `@nodeId`-site predicate is
  already replicated across four sites). Rejected.

One honest divergence from the sibling reductions to flag for the reviewer: those
iterate the classified registries (`ctx.fieldRegistry` / `ctx.typeRegistry`); this
one reads `ctx.schema` applied directives, precisely because the dropped directive
leaves no registry trace. `BuildContext` is a permitted raw-schema holder, so this
is sound, but it means the pass cannot reuse the registry-iteration loop shape.

The underlying smell (out of scope to fix here, noted for context): R262 is needed
*because* `hasAppliedDirective(DIR_NODE_ID) && "ID".equals(...)` is re-evaluated at
four+ scattered sites with no shared "this is a `@nodeId` site" classification. The
centralized pass is the single-source-of-truth answer to that predicate; R262
rejects rather than re-architects.

## Implementation

Confirmed current sites (the audit line numbers have drifted):

- Decode gates that silently drop non-`ID` `@nodeId` (findings F, G):
  `BuildContext.java:2069` (input-object field), `FieldBuilder.java:373` and
  `:418` (nested / same-table input fields), `FieldBuilder.java:1211`
  (top-level argument in `classifyArgument`).
- Encode path with no `ID` gate (the output `FIELD_DEFINITION` mechanism):
  `FieldBuilder.java:5902` (scalar/enum return classifier), feeding
  `CallSiteCompaction.NodeIdEncodeKeys`.
- Federation rep path (finding H): `EntityResolutionBuilder.buildAlternative`
  (the `KeyShape.DIRECT` arm, ~`:265-293`); the existing compound-`id` warning at
  ~`:276-287` is a non-fatal `BuildWarning`.

Work:

1. **`rejectNonIdNodeId(ctx)` reduction.** Add a build-walk pass (sibling to
   `rejectCaseInsensitiveTypeCollisions`, called from the same orchestration region
   in `GraphitronSchemaBuilder`) that walks `ctx.schema` for every `@nodeId`
   application across the three `on` locations (object/interface field
   definitions, input-object fields, field arguments) and, for any whose
   `GraphQLTypeUtil.unwrapAll(...)` is not the `ID` scalar, registers
   `ctx.addDiagnostic(ValidationError.forField(<coord>, Rejection.invalidSchema(
   "@nodeId is only valid on a field/argument of type ID (got '<type>')"),
   <sourceLocation>))`. Use the field/argument source location so the build error
   carries `file:line`. Detection idiom is the existing `DIR_NODE_ID` constant and
   `hasAppliedDirective` / `argString` helpers in `BuildContext`. For the argument
   coordinate, mirror whatever qualified-name convention the existing
   `UnclassifiedArg` rejections use so the coordinate reads consistently.
2. **Finding H rejection.** In `EntityResolutionBuilder`'s `KeyShape.DIRECT` arm,
   detect a `@key` field that resolves to the `@nodeId`-encoded reference shape
   (a `ChildField.ColumnReferenceField` whose output carrier is
   `CallSiteCompaction.NodeIdEncodeKeys`) and register it through
   `ctx.addDiagnostic(...)` as a **fatal** `ValidationError` carrying a typed
   `Rejection` — *not* a sibling `BuildWarning` to the existing non-fatal
   compound-`id` warning. Co-locating with the warning would silently inherit its
   non-fatal disposition and leave the build green.

**Rejection kind.** Use `Rejection.invalidSchema(...)` (`InvalidSchema.Structural`,
projecting to `RejectionKind.INVALID_SCHEMA`). Precedent: "`@column` is not valid
on a non-table-backed type" and "`@orderBy` is not valid on a lookup field" both
use `invalidSchema` for the same "directive not valid on this coordinate; drop it
or change the type" shape. For finding H, mirror whichever variant the sibling
`@key` reductions at `GraphitronSchemaBuilder` use; `InvalidSchema.Structural`
reads accurately (an encoded `@nodeId` reference on a federation rep is a shape no
small edit repairs).

## Tests

Pipeline-tier (`@PipelineTier`), following `DiscriminatorReferenceContradictionPipelineTest`:
build a schema from SDL via `TestSchemaHelper.buildSchema(...)` and assert
`schema.diagnostics()` contains a `ValidationError` with the expected coordinate
and `RejectionKind`. Assert on the **typed rejection (coordinate + kind/message)**,
never on "the field is absent from the emitted fetcher" — under the diagnostics
design the field keeps its verdict and the validator throws before the emitter
runs. One fixture per coordinate location plus one for H:

- `@nodeId` on a non-`ID` **input-object field** (`String`/`Int`) → rejected.
- `@nodeId` on a non-`ID` **argument** → rejected.
- `@nodeId` on a non-`ID` **output field** (the encode mechanism) → rejected.
- federation `@key` field that is itself `@nodeId`-encoded → rejected (fatal, via
  `diagnostics()`).

Counter-cases that must keep passing unchanged: legitimate `@nodeId` on `ID`
coordinates (input, argument, output) and the federation `NODE_ID` happy path
(single `id` `@key` on a `@node` type). No `@nodeId` fixture exercising the non-ID
or encoded-`@key` cases exists today; these are net-new.

## Out of scope

- The cast-axis defects (A-E) in `wire-coercion-cast-guard`.
- Re-architecting the replicated `@nodeId`-site predicate into a single shared
  classification (the underlying smell noted above); R262 rejects, it does not
  consolidate.
- Actually *implementing* decode-into-record for encoded `@key` rep values
  (finding H remediation beyond rejection); rejection first, decode as a possible
  follow-on once a real fixture needs it.

## Relationships

Independent of `wire-coercion-cast-guard` and `dimensional-model-pivot` (shippable
on its own). Sibling to R397 (`@error` on bare-entity query fields) and R273
(NodeId mismatch semantics) in the nodeid/errors space; R397's body names R262 as
the `@nodeId`-on-non-ID validate-time rejection.

