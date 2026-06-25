---
id: R377
title: "decode<typeId> mismatch: resolve decode helper via NodeIndex when multiple @table types share a table"
status: Ready
bucket: correctness
priority: 2
theme: nodeid
depends-on: []
created: 2026-06-25
last-updated: 2026-06-25
---

# decode<typeId> mismatch: resolve decode helper via NodeIndex when multiple @table types share a table

Two generators can disagree on the name of a node-id decode helper, emitting a call to a `decode<typeId>` method the encoder never generates. The encoder (`NodeIdEncoderClassGenerator`, which emits one `decode<TypeName>` per `@node` type) names decode helpers by GraphQL **type name** (`decodeUtdanningsmulighet`). The call site (`BuildContext.resolveDecodeHelperForTable`) agrees on its primary branch but, on its fallback branch, names by `@node` **typeId** (`decode10154`). When a customized numeric typeId is used (`@node(typeId: "10154")`), the two names diverge and the generated Java fails to compile (`The method decode10154(String) is undefined for the type NodeIdEncoder`).

The fallback branch is reached because `findGraphQLTypeForTable` counts **every** `@table`-annotated object type over a table, so it returns empty (ambiguous) whenever a table is backed by more than one object type, even though only the `@node` type owns a decode helper. Example: `UTDANNINGSMULIGHET` is carried by `Utdanningsmulighet` (the node) plus two nesting-projection types (`UtdanningsmulighetDagerPeriode`, `UtdanningsmulighetTiderPeriode`), so the unique-match lookup fails and the typeId fallback fires.

This is latent rather than a regression: it surfaces only after schema validation passes and codegen runs to completion (a javac-stage error in the consumer's build, not a validation error), and only when typeId ≠ type name. It was found porting `utdanningsregisteret` to Graphitron 10.

**Status correction.** The original Backlog note claimed "the working tree already contains the code change." It does not: the working tree is clean and `resolveDecodeHelperForTable` still has only the two branches (`graphitron-rewrite/.../BuildContext.java:2208-2236`). The drafted change was never committed and is gone. This Spec is the design of record; treat the change as unimplemented.

## Root cause, precisely

`resolveDecodeHelperForTable` (BuildContext.java:2208) resolves a table name to a `decode<...>` helper through two branches:

1. `findGraphQLTypeForTable(sqlTableName)` returns a **unique** `@table` object type for the table, or empty when more than one `@table` object backs it (it counts nesting-projection types, not just `@node` types). On a unique match it returns the `NodeType.decodeMethod()` (keyed by GraphQL type name) via `nodes.forName(typeName)`.
2. Fallback: `new HelperRef.Decode(encoderClass, "decode" + fallbackTypeNameOrTypeId, keyColumns)`, where `fallbackTypeNameOrTypeId` is the wire-format **typeId**.

A table backed by a `@node` plus one or more nesting-projection `@table` types makes branch 1 return empty, so branch 2 fires and names by typeId. The encoder only ever emits `decode<TypeName>`, so when `typeId != typeName` the call references a method that does not exist.

There is a deeper shape problem the architect review surfaced: this method holds **three** table→node lookups doing overlapping work. Branch 1 is a two-hop detour (`findGraphQLTypeForTable`, an all-`@table` index, then `nodes.forName`) to recover a `NodeType` from a table name; the `NodeIndex` already offers `nodes.forTable(sqlTableName)`, a direct `@node`-only view that is exactly the right domain. The bug is precisely the gap between branch 1's "every `@table` object" domain and the correct "only `@node` objects" domain. Rather than insert a third branch above the wrong-domain index and rely on branch ordering to mask it, the fix replaces the detour with the node index.

## Design

Rewrite `resolveDecodeHelperForTable` to resolve through the `@node`-only index, with a three-way outcome that matches what callers already do with the result:

```java
no.sikt.graphitron.rewrite.model.HelperRef.Decode resolveDecodeHelperForTable(
        String sqlTableName,
        String fallbackTypeId,
        java.util.List<no.sikt.graphitron.rewrite.model.ColumnRef> keyColumns) {
    // The NodeIndex's by-table view sees only @node types (not the nesting-projection @table
    // types that share the rows), so it is the authoritative decode source. decodeMethod() is
    // keyed on the GraphQL type name, matching NodeIdEncoderClassGenerator's emitted helper —
    // unlike the typeId-suffixed fallback below, which agrees with the encoder only when typeId
    // equals the type name.
    var nodesForTable = nodes.forTable(sqlTableName);
    if (nodesForTable.size() == 1) {
        return nodesForTable.get(0).decodeMethod();
    }
    if (!nodesForTable.isEmpty()) {
        // Two or more @node types back this table and the call site did not disambiguate with
        // @nodeId(typeName:). There is no implicit decode helper; return null so the caller emits
        // a validate-time rejection rather than a decode<typeId> call the encoder never generates.
        return null;
    }
    // No @node backs this table (orphan-input / synthesis-shim case: an `input Foo @table(...)`
    // with catalog NodeId metadata but no @node SDL type). Fall back to the metadata's typeId as
    // the helper suffix; only reachable through the synthesis shim, on a retirement track (see
    // graphitron-rewrite/roadmap/retire-synthesis-shims.md).
    if (fallbackTypeId == null || fallbackTypeId.isBlank()) return null;
    return new no.sikt.graphitron.rewrite.model.HelperRef.Decode(
        encoderClass, "decode" + fallbackTypeId, keyColumns);
}
```

Three points the implementer must settle, each load-bearing:

**1. Case-folding `NodeIndex.forTable` (do this as part of R377, not later).** `NodeIndex.byTable` keys on `nt.table().tableName()` (the verbatim `@table(name:)` echo, `TypeBuilder.java:518`) and `forTable` looks up with a raw `getOrDefault` (`NodeIndex.java:55`). The branch this Spec removes went through `findGraphQLTypesForTable`, which **lowercases** its key (`BuildContext.java:2432`), matching the `TableRef.sameTable` case-insensitive contract. Routing decode resolution through the raw-keyed `forTable` makes correctness depend on a casing coincidence (today every caller's `sqlTableName` and every `byTable` key both reduce to the lowercased `@table(name:)` echo, but those are two independent derivations). A caller that passes a catalog-cased or mixed-case table name would silently fall through to the typeId fallback and reintroduce the exact `decode10154` bug. Close it structurally: key `byTable` on `tableName().toLowerCase()` at construction (`TypeBuilder.buildClassificationIndices`) and lowercase the lookup arg in `NodeIndex.forTable`. This also brings `forTable` in line with the `TableRef` "consumers never re-establish the case-folding contract" rule. The four existing `nodes.forTable` callers in `FieldBuilder` are unaffected (they already pass the same lowercased echo).

**2. Multi-node returns `null`, which is already a clean rejection.** All four callers of `resolveDecodeHelperForTable` map a `null` return to a structural rejection with a "zero or multiple GraphQL types map to it" message (`BuildContext.java:2042` and `:2177`, `NodeIdLeafResolver.java:277`, `FieldBuilder.java:1140`). So the genuinely-multi-`@node`-without-`@nodeId(typeName:)` case becomes a **validate-time rejection** instead of a `decode<typeId>` javac error downstream, satisfying the "validator mirrors classifier invariants" principle (a classifier decision implying an unimplementable generator branch must fail at build time, not in the consumer's compile). No caller changes are required; confirm the existing messages read sensibly for this path and tighten wording if not.

**3. Dropping branch 1 has one behavioral nuance to confirm.** Current branch 1 has a sub-case (`BuildContext.java:2224`): a **unique** `@table` object type that `nodes.forName` does *not* find as a `@node` returns `new HelperRef.Decode(encoderClass, "decode" + typeName, keyColumns)` — keyed by type **name**. The collapse routes that input to the empty-`forTable` fallback, which keys by **typeId**. For a non-`@node` `@table` type the encoder generates no decode helper at all, so both spellings reference a non-existent method; this is only "reachable" through the synthesis shim, where it is already best-effort. The recommendation is to drop branch 1 and let this input take the typeId fallback (simpler, one node-resolution path), and to pin the orphan-input behavior with the test below so the change is observable. If the orphan-input test shows a real consumer depends on the type-name spelling, keep a narrowed branch 1 and document, per the sub-taxonomy rule, what distinct input it resolves that `forTable` cannot. `findGraphQLTypeForTable` itself stays — its other caller (the id-reference synthesis shim, `BuildContext.java:1949`) is unchanged; only its use inside `resolveDecodeHelperForTable` is removed.

**`keyColumns` parameter and the reviewer note.** The original Backlog reviewer note asked to "confirm `NodeType.decodeMethod()` carries the correct `keyColumns`." That conflates two things: `HelperRef.Decode`'s third component is `outputColumnShape` (the decoded `RecordN` arity, `HelperRef.java:69`), **not** the call-site key list. Returning `decodeMethod()` and discarding the passed-in `keyColumns` is sound because the single `@node` on a table has the same key columns the caller resolved for it (both trace to the catalog / `@node(keyColumns:)`), and it is exactly what current branch 1 already does. Restate the justification in terms of `outputColumnShape`, not "keyColumns." Note that after the collapse the `keyColumns` parameter is consumed only by the typeId fallback; if that asymmetry bothers a reviewer it is a follow-up, not part of R377.

## Implementation

- `BuildContext.resolveDecodeHelperForTable` (BuildContext.java:2208): rewrite to the three-outcome shape above. Rename the `fallbackTypeNameOrTypeId` parameter to `fallbackTypeId` (it is only ever the typeId now). Update the method javadoc (lines 2196-2207) to describe node-index-first resolution rather than the `findGraphQLTypeForTable` detour.
- `NodeIndex.forTable` (NodeIndex.java:54) and `TypeBuilder.buildClassificationIndices` (TypeBuilder.java:518): lowercase the `byTable` key on construction and the `forTable` lookup arg, closing the casing divergence. Update the `forTable` javadoc to state the key is case-folded.
- No caller changes: the four callers already handle the `null` outcome.

## Tests

- **Pipeline (primary tier, `NodeIdPipelineTest` or a focused regression class next to it):** add a fixture table to `NodeIdFixtureGenerator.METADATA` whose `typeId` is a customized value distinct from the GraphQL type name (e.g. `"shared_node" -> Metadata("10154", List.of("ID"))`); no existing fixture has a numeric/custom typeId, so this is new. Schema: a `@node` type with `@node(typeId: "10154")` plus one nesting-projection `@table` type over the same table. Assert the resolved decode `HelperRef.Decode.methodName()` carried on the field's extraction is `decode<TypeName>`, **not** `decode10154`. Pin the structural helper name, not an emitted code string (code-string assertions are banned at every tier).
- **Pipeline (multi-node rejection):** two `@node` types on one table, a call site that needs the implicit decode helper without `@nodeId(typeName:)`. Assert it rejects at validate time (the `Unresolved` / `Rejected` / `UnclassifiedArg` carrier with the "zero or multiple" message), not a resolved carrier. Sibling to the existing `MULTIPLE_NODE_TYPES_PER_TABLE_ALLOWED` case (NodeIdPipelineTest.java:222), which pins that both types still *classify* as `NodeType`.
- **Pipeline (orphan-input fallback):** a `@table` (non-`@node`) type over a metadata-carrying table reached through the synthesis shim still resolves to the typeId fallback (or rejects), pinning whatever branch-1 collapse decision is made in Design point 3 so it is observable rather than silent.
- **Compilation backstop (`graphitron-sakila-example`):** the pipeline tier produces a structurally well-formed `TypeSpec` even when it names `decode10154`, so the *javac* failure that is the actual bug is only caught by a cross-module compile. Add a node type with a customized numeric `@node(typeId:)` over a table that is also backed by a projection type to the example schema, so `graphitron-sakila-example`'s `<release>17</release>` compile is the end-to-end guard. If the existing Sakila schema already has a multi-`@table`-over-one-table shape, extend it; otherwise add the minimal pair.

## Out of scope

- Retiring the synthesis shim and the typeId fallback entirely (tracked by `retire-synthesis-shims.md`). R377 narrows when the fallback fires and makes the multi-node case a clean rejection; it does not remove the orphan-input path.
- The unrelated `@reference(path:)` + `@field(name:)` parent-table-resolution bug family noted when this item was filed; not covered here.
- Dropping the now-fallback-only `keyColumns` parameter from `resolveDecodeHelperForTable` (a cleanup follow-up, not a correctness change).
