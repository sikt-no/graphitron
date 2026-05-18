---
id: R176
title: Entity-resolution rejection message claims @table is missing when classification failed for other reasons
status: Ready
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

## Design principle in play

This bug sits on the line between two principles already in the rewrite design docs: *classification belongs at the parse boundary* and *validator mirrors classifier invariants*. Together they imply a third, which the fix codifies: **once `TypeBuilder` has produced an `UnclassifiedType`, downstream stages enrich the rejection or pass it through; they do not relitigate the reason.** `EntityResolutionBuilder` is downstream of classification; when it sees an `UnclassifiedType`, its job is to leave it alone, not to substitute a fresh guess.

That principle drives the call-site fix below over a registry-level `demote`-refuses-overwrite invariant. See "Why call-site, not registry" further down.

## Approach

Two distinct cases inside the offending block. They want different treatments.

### Case A: `gType` is already `UnclassifiedType`

The upstream `TypeBuilder` already recorded the real cause. `EntityResolutionBuilder` has nothing new to add — its check `!(gType instanceof TableType || gType instanceof NodeType)` only fired because classification had already failed.

**Fix:** skip the demote. The existing `UnclassifiedType` flows through `GraphitronSchemaValidator.validateUnclassifiedType` unmodified and the original rejection is reported. Plain `continue`; no log line needed — `validateUnclassifiedType` already surfaces the rejection.

### Case B: `gType` is a non-table-bound classification

After the line-93 filter (`assembledType instanceof GraphQLObjectType`) and Case A's removal, the surviving Case-B set is: any `GraphQLObjectType` classified as something other than `TableType`, `NodeType`, `TableInterfaceType`, or `UnclassifiedType`. In practice today that is `PlainObjectType` (an SDL object with no `@table`) and the `ResultType` sub-hierarchy (`@record`-annotated types). Existing test `plainObjectTypeWithKey_demotesToUnclassifiedType` at `EntityResolutionBuilderTest.java:224` already exercises the `PlainObjectType` arm — Case B is live, not vestigial.

The current message ("`<T>` has no @table directive") is accurate for the `PlainObjectType` sub-case but wrong-by-coincidence for `ResultType`: a `@record` type also has no `@table`, but the actionable feedback is "you put `@key` on a `@record` type", not "you forgot `@table`". The new wording should name the classification kind so both sub-cases point at the actual misuse.

**Fix:** emit a message that names the classification:

```
@key on type '<TypeName>' requires a table-bound type, but '<TypeName>' is classified as <kind> — federation entities need a @table directive.
```

`<kind>` is a friendly label derived from the `GraphitronType` subtype: `PlainObjectType` → `"a plain object type"`, `JavaRecordType` → `"a @record type"`, etc. A private `static String kindLabel(GraphitronType)` switch in `EntityResolutionBuilder` covers the surviving classifications; no existing helper in the codebase produces this label, and the closest neighbour (`GraphitronType.getClass().getSimpleName()`) leaks internal record names that aren't author-facing.

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
    // real cause. Pass it through unchanged — see "Design principle in play".
    continue;
}
if (!(gType instanceof TableType || gType instanceof NodeType)) {
    registry.demote(typeName, new UnclassifiedType(typeName, gType.location(), Rejection.structural(
        "@key on type '" + typeName + "' requires a table-bound type, but '" + typeName
        + "' is classified as " + kindLabel(gType)
        + " — federation entities need a @table directive.")));
    continue;
}
```

`kindLabel(GraphitronType)` is a small private switch local to `EntityResolutionBuilder` mapping the surviving Case-B classifications to author-facing strings (`PlainObjectType` → `"a plain object type"`, `JavaRecordType`/`PojoResultType`/`JooqRecordType`/`JooqTableRecordType` → `"a @record type"`, default branch → `"a non-table-bound type"` for any classification a future contributor adds without updating the switch). No existing helper in the codebase produces this label.

The `TableInterfaceType` branch immediately above stays as-is: it demotes a *classified* `TableInterfaceType`, which is the legitimate enrich-with-rejection pattern. Materially different from the Case A overwrite.

### Why call-site, not registry

A `demote`-refuses-overwrite invariant on `TypeRegistry` is the obvious alternative. The reason to reject it: `demote` is the registry's *enrich-with-rejection* primitive, not just a "mark Unclassified" verb. The two legitimate demote callers in the rewrite tree today (`TypeBuilder.java:226`, `EntityResolutionBuilder.java:104`) both demote from a *classified* entry to `UnclassifiedType` because a downstream stage discovered a structural problem the classifier couldn't see — a typeId collision across types, or `@key` on `TableInterfaceType`. A blanket overwrite refusal would block those, while a narrower "refuse overwrite of `UnclassifiedType`" rule duplicates what `if (gType instanceof UnclassifiedType) continue;` already says at one specific call site.

The deeper reason is the principle stated above: rejection durability is the *caller's* responsibility, because only the caller knows whether it's enriching a rejection (legitimate) or relitigating one (the bug). The registry can't tell those apart from the type signatures alone. Keeping the discipline at the call site keeps the registry honest about what it is — a mutable map with classification tracing — and puts the obligation where the knowledge lives.

### `demote` call-site audit

Inlined here rather than left as a follow-up. Four `demote(...)` callers in `graphitron-rewrite/graphitron/src/main/java`:

| Site | Demoting from | Verdict |
|---|---|---|
| `TypeBuilder.java:226` | `NodeType` (typeId collision across types) | Legitimate enrich — classified type, the downstream check is what surfaced the collision. |
| `EntityResolutionBuilder.java:104` | `TableInterfaceType` (`@key` on an interface) | Legitimate enrich — classified type, downstream check surfaces a structural rejection the classifier couldn't see. |
| `EntityResolutionBuilder.java:110` | Whatever (incl. `UnclassifiedType`) | **The bug.** |
| `EntityResolutionBuilder.java:128` | `TableType` or `NodeType` (line 109 already gated) | Legitimate enrich — gType is guaranteed table-bound by the preceding check. |

Only the `:110` site is the overwrite. The other three sites all demote from classified entries; none overwrites a pre-existing `UnclassifiedType`. No additional call-site guards are needed.

## Tests

All in `graphitron/src/test/java/no/sikt/graphitron/rewrite/schema/federation/EntityResolutionBuilderTest.java` (existing `@UnitTier` class, uses `TestSchemaHelper.buildSchema` with real `customer`/`film`/`language` tables from the test jOOQ catalog). Every assertion checks both inclusion of the expected upstream phrase **and** absence of the misleading wording, uniformly across all three regression tests.

1. **(new) `@key` with unresolvable `@table` name preserves the unknown-table rejection.** Fixture:
   ```graphql
   type Query { x: T }
   type T implements Node @key(fields: "id") @node @table(name: "no_such_table") { id: ID! @nodeId }
   ```
   Assert `unclassified.reason()` contains the unknown-table phrase produced by `TypeBuilder.unknownTableRejection` (the existing wording in that helper, whatever it is — test should match the helper's literal so it doesn't drift), and does **not** contain `has no @table directive`.

2. **(new) `@key` with `@node(keyColumns: [...])` referencing a non-column preserves the unresolved-column rejection.** Fixture uses `customer` (which exists), with `@node(keyColumns: ["definitely_not_a_column"])`. Assert `unclassified.reason()` contains `key column 'definitely_not_a_column' in @node could not be resolved`, and does **not** contain `has no @table directive`.

3. **(update existing) `plainObjectTypeWithKey_demotesToUnclassifiedType` (line 224) pins the new Case-B wording.** The current assertion is `assertThat(unclassified.reason()).contains("@table")`, which is too loose — it would still pass the bug. Tighten to: contains `is classified as a plain object type` AND contains `federation entities need a @table directive`. Both halves matter: the first is what makes the message actionable, the second preserves the "missing @table" hint for the case where it really is the cause.

4. **(new, optional) `@key` on a `@record`-annotated type names the `@record` kind.** Fixture: a `@record` type with `@key`. Assert `unclassified.reason()` contains `is classified as a @record type`. Optional because Case B's `ResultType` arm is rare; include if a `@record` fixture is cheap to spin up in `TestSchemaHelper`, otherwise punt to a follow-up. Mark it `@Disabled` with a TODO rather than silently dropping if it turns out to need new fixture machinery.

5. **(verify, no change) Existing happy paths stay green.** `nodeType_alwaysGetsNodeIdAlternative_evenWithoutFederation` (line 32) and `tableType_withExplicitKey_getsDirectAlternative` (line 67) confirm the non-error paths are unaffected.

Tests 1, 2, 3 are the minimum sufficient regression-prevention set. Test 4 is nice-to-have; the implementer may drop it without spec revision.

## Out of scope

- Surfacing *all* rejections per type rather than the first. The validator's one-error-per-`UnclassifiedType` policy is unchanged.
- LSP fix-it hints for the new wording. The structural `Rejection` carries enough payload for an LSP layer to consume later; no fix-it is being authored here.
- Changing `TypeRegistry.demote` semantics. See "Why call-site, not registry".

## Effort

Small. One file edit (~15 LOC including the helper) plus three pipeline tests. Plausible single-session change after spec sign-off.
