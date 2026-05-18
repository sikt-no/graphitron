---
id: R176
title: Entity-resolution rejection message claims @table is missing when classification failed for other reasons
status: Spec
bucket: validation
depends-on: []
created: 2026-05-18
last-updated: 2026-05-18
---

# Entity-resolution rejection message claims @table is missing when classification failed for other reasons

## Problem

`EntityResolutionBuilder.build` (`graphitron/src/main/java/no/sikt/graphitron/rewrite/schema/federation/EntityResolutionBuilder.java:108-114`) rejects any type carrying `@key` whose classification is not `TableType` or `NodeType` with this hard-coded message:

```
@key requires a @table-bound type; '<TypeName>' has no @table directive
```

The check is `!(gType instanceof TableType || gType instanceof NodeType)`. It does not inspect the SDL for `@table` — it only looks at the post-classification model. A type can fail that check while still carrying `@table`, for example:

- The jOOQ catalog has no table matching `@table(name: ...)` (TypeBuilder.java:551 → `unknownTableRejection`).
- One of the `keyColumns` on `@node` is not resolvable in the jOOQ table (TypeBuilder.java:589-598).
- `KjerneJooqGenerator` node-id metadata on the backing table is malformed (TypeBuilder.java:562-567).
- `@node` is declared on a type that doesn't `implements Node` (TypeBuilder.java:578-581).

In each case `TypeBuilder` produces an `UnclassifiedType` whose `Rejection` carries the actual cause. Then `EntityResolutionBuilder` runs, sees a non-Table/Node `gType`, and calls `registry.demote(typeName, new UnclassifiedType(..., Rejection.structural("@key requires a @table-bound type; '" + typeName + "' has no @table directive")))`.

`TypeRegistry.demote` (`TypeRegistry.java:64-72`) does an unconditional `types.put(name, type)`. The original `UnclassifiedType` from `TypeBuilder` — the one carrying the real reason — is overwritten in place. `GraphitronSchemaValidator.validateUnclassifiedType` (`GraphitronSchemaValidator.java:919-925`) then reports a single error per `UnclassifiedType`, and that single error is the misleading wording, not the upstream cause.

Result: an author whose SDL is genuinely broken (typo in a `keyColumns` entry, jOOQ class missing from the codegen classpath, …) gets a build log that points at a directive *they have actually written*, with no mention of the real fault. Reported by a user trying to debug an `AdmissioKvalifikasjonsgrunnlag` type whose `@table(name: "sak_kvalifikasjonsgrunnlag")` was clearly present in the SDL.

## Goal

When `EntityResolutionBuilder` encounters a type whose classification disqualifies it from being an entity, the surfaced validator error must identify the actual upstream cause rather than synthesizing a misleading new one.

Non-goals: changing what makes a type ineligible for `@key`, expanding the set of classifications that qualify as entities, or changing `TypeRegistry.demote` semantics for callers that legitimately want to overwrite (e.g. `TableInterfaceType` rejection three lines above the offending block).

## Approach

Two distinct cases inside the offending block. They want different treatments.

### Case A: `gType` is already `UnclassifiedType`

The upstream `TypeBuilder` already recorded the real cause. `EntityResolutionBuilder` has nothing new to add — its check `!(gType instanceof TableType || gType instanceof NodeType)` only fired because classification had already failed.

**Fix:** skip the demote. The existing `UnclassifiedType` will flow through `GraphitronSchemaValidator.validateUnclassifiedType` unmodified and the original rejection is reported. Use `continue` after a debug-log line (or no log at all — `validateUnclassifiedType` will surface the error regardless).

### Case B: `gType` is a different classification (`ScalarType`, etc.)

Author put `@key` on something that isn't even a table-bound candidate. The current message is wrong for this case too — the type may have no `@table` directive because it's a scalar, an interface, an enum. The user needs to know what they actually did.

**Fix:** emit a message that names the actual classification kind, not a guess about a missing directive:

```
@key on type '<TypeName>' requires a table-bound type, but '<TypeName>' is classified as <ClassificationKind> (no @table directive on the SDL declaration).
```

Where `<ClassificationKind>` is the simple class name of `gType` (`ScalarType`, `EnumType`, …) lowered to a friendlier form by the existing model conventions. The "(no @table directive …)" parenthetical is now a hint, not a claim; it stays accurate because Case A is no longer routed here.

### Implementation sketch

`EntityResolutionBuilder.java:108-114`, replace:

```java
if (!(gType instanceof TableType || gType instanceof NodeType)) {
    registry.demote(typeName, new UnclassifiedType(typeName, gType.location(), Rejection.structural(
        "@key requires a @table-bound type; '" + typeName
        + "' has no @table directive")));
    continue;
}
```

with:

```java
if (gType instanceof UnclassifiedType) {
    // Already rejected upstream (TypeBuilder); the existing rejection carries the
    // real cause (unresolved @table, unresolvable @node keyColumns, malformed
    // KjerneJooqGenerator metadata, …). Don't overwrite it with a guess.
    continue;
}
if (!(gType instanceof TableType || gType instanceof NodeType)) {
    registry.demote(typeName, new UnclassifiedType(typeName, gType.location(), Rejection.structural(
        "@key on type '" + typeName + "' requires a table-bound type, but '" + typeName
        + "' is classified as " + kindLabel(gType) + " (no @table directive on the SDL declaration).")));
    continue;
}
```

`kindLabel(GraphitronType)` is a tiny private helper that maps a `GraphitronType` to a human-friendly string (`ScalarType` → `"scalar"`, `EnumType` → `"enum"`, etc.). If a small helper like this already lives on `GraphitronType` or in a sibling diagnostics util, reuse it; otherwise add it locally to `EntityResolutionBuilder`.

The `TableInterfaceType` branch immediately above stays as-is: it is itself a "demote a legitimately-classified type because @key isn't supported on this shape" path, which is materially different from the Case A overwrite.

### Why not change `TypeRegistry.demote` to refuse re-demotion?

Tempting but wrong. `demote` is also used to *enrich* a rejection (the `TableInterfaceType` branch one block up does exactly this: it takes a classified `TableInterfaceType` and demotes it to `UnclassifiedType` because `@key` isn't supported on that shape). Forbidding `demote` on a non-classified entry would have to be `forbid demote on already-Unclassified entry`, which is a narrow guard that adds no value once the local `continue` above is in place — the only caller that hit the case is the one being fixed.

A repo-wide audit of `demote` callers is part of this work to confirm no other site silently overwrites an `UnclassifiedType`. If others exist, they get the same `if (gType instanceof UnclassifiedType) continue;` guard at their call sites; the registry stays permissive.

## Tests

Pipeline tier (`graphitron/src/test/java/.../federation/` neighbourhood — pick whichever existing test class covers EntityResolutionBuilder; if no test class covers it, the same fixture pattern as the `KeyNodeSynthesiser` tests applies):

1. **`@key` with unresolvable `@table` name surfaces the unknown-table rejection, not the misleading message.** Build a fixture with `type T @key(fields: "id") @node @table(name: "no_such_table") implements Node { id: ID! @nodeId }`; assert the validator error message contains the unknown-table phrasing produced by `unknownTableRejection`, and does **not** contain `has no @table directive`.

2. **`@key` with `@node(keyColumns: [...])` referencing a column not in the jOOQ table surfaces the unresolved-column rejection.** Use an existing fixture table with at least one column, set `keyColumns: ["definitely_not_a_column"]`. Assert the error message contains `key column 'definitely_not_a_column' in @node could not be resolved`, and does not contain `has no @table directive`.

3. **`@key` on a scalar / non-table-bound type still produces a clear classification-mismatch message.** Build a fixture where `@key` is attached to something that classifies as `ScalarType`. Assert the error message contains `is classified as scalar` and does **not** claim a missing `@table` outside the parenthetical hint.

4. **Existing happy path stays green.** A `TableType` and a `NodeType` each with a valid `@key` still produce an `EntityResolution`. (Likely already covered; verify the existing test still passes.)

The first two are the regression-prevention tests for the bug. The third pins the new wording. The fourth confirms no behaviour change for valid SDL.

## Out of scope

- Surfacing *all* rejections per type rather than the first. The validator's one-error-per-UnclassifiedType policy is unchanged.
- LSP fix-it hints for the new wording. The structural `Rejection` carries enough payload for an LSP layer to consume later; no fix-it is being authored here.
- Auditing whether any other `Builder` pass silently overwrites prior `UnclassifiedType` rejections beyond the call-site audit described above.

## Effort

Small. One file edit (~15 LOC including the helper) plus three pipeline tests. Plausible single-session change after spec sign-off.
