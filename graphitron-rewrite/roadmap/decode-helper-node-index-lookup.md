---
id: R377
title: "decode<typeId> mismatch: resolve decode helper via NodeIndex when multiple @table types share a table"
status: Backlog
bucket: correctness
priority: 2
theme: nodeid
depends-on: []
created: 2026-06-25
last-updated: 2026-06-25
---

# decode<typeId> mismatch: resolve decode helper via NodeIndex when multiple @table types share a table

Two generators can disagree on the name of a node-id decode helper, emitting a call to a `decode<typeId>` method the encoder never generates. The encoder (`NodeIdEncoderClassGenerator` via `TypeBuilder.buildNodeType`) names decode helpers by GraphQL **type name** (`decodeUtdanningsmulighet`). The call site (`QueryConditions` via `BuildContext.resolveDecodeHelperForTable`) agrees on its primary branch but, on its fallback branch, names by `@node` **typeId** (`decode10154`). When a customized numeric typeId is used (`@node(typeId: "10154")`), the two names diverge and the generated Java fails to compile (`The method decode10154(String) is undefined for the type NodeIdEncoder`).

The fallback branch is reached because `findGraphQLTypeForTable` counts **every** `@table`-annotated object type over a table, so it returns empty (ambiguous) whenever a table is backed by more than one object type, even though only the `@node` type owns a decode helper. Example: `UTDANNINGSMULIGHET` is carried by `Utdanningsmulighet` (the node) plus two nesting-projection types (`UtdanningsmulighetDagerPeriode`, `UtdanningsmulighetTiderPeriode`), so the unique-match lookup fails and the typeId fallback fires.

This is latent rather than a regression: it surfaces only after schema validation passes and codegen runs to completion (a javac-stage error, not a validation error), and only when typeId ≠ type name. It was found porting `utdanningsregisteret` to Graphitron 10.

**Fix (already drafted in the working tree, `BuildContext.resolveDecodeHelperForTable`):** between the `findGraphQLTypeForTable` unique-match block and the typeId fallback, consult `NodeIndex.forTable(sqlTableName)` — which sees only `@node` types. When exactly one NodeType backs the table, return its `decodeMethod()` (type-name-keyed, matching the encoder). The typeId fallback remains only for the orphan-input / genuinely-multi-node-without-`@nodeId(typeName:)` case. No signature change; all four callers benefit.

**Verification done:** rebuilt plugin+core, regenerated ureg; zero `decode[0-9]{3,}` calls remain and `QueryConditions` compiles. A regression fixture in the pipeline tests (a `@table` shared by a node type plus a nesting projection, with a customized numeric `@node(typeId:)`) should be added so this stays closed.

Note: the working tree already contains the code change; this item exists to bring that change under the roadmap gate. A separate, unrelated bug family surfaced downstream (`@reference(path:)` + `@field(name:)` columns resolved against the parent table instead of the path target) and is **not** covered here.

## The change

In `BuildContext.resolveDecodeHelperForTable`, between the `findGraphQLTypeForTable` unique-match block and the typeId fallback:

```java
// findGraphQLTypeForTable counts every @table object type (the NodeType plus any
// nesting-projection types over the same rows), so it returns empty for a table backed by
// more than one object type even though only the @node NodeType owns a decode helper. The
// NodeIndex's table view sees only @node types: when exactly one covers the table it is the
// authoritative decode source. Its decodeMethod is keyed on the GraphQL type name, matching
// NodeIdEncoderClassGenerator's emitted helper — unlike the typeId-suffixed fallback below,
// which agrees with the encoder only when typeId equals the type name (the customized-typeId
// case would otherwise emit a call to a decode<typeId> method the encoder never generates).
var nodeTypesForTable = nodes.forTable(sqlTableName);
if (nodeTypesForTable.size() == 1) {
    return nodeTypesForTable.get(0).decodeMethod();
}
// No unique @node NodeType backs this table (e.g. orphan-input schemas where only an
// `input Foo @table(name: "bar")` exists, or a genuinely multi-node table reached without
// a disambiguating @nodeId(typeName:)). Fall back to the metadata's typeId (the wire-format
// identifier) as the helper-method suffix; this matches NodeType.encodeMethod /
// decodeMethod resolution in the default case where typeId equals the GraphQL type name.
// The customized-typeId / no-NodeType combination is only reachable through the synthesis
// shim, which is on a retirement track (see graphitron-rewrite/roadmap/retire-synthesis-shims.md).
if (fallbackTypeNameOrTypeId == null || fallbackTypeNameOrTypeId.isBlank()) return null;
return new no.sikt.graphitron.rewrite.model.HelperRef.Decode(
    encoderClass, "decode" + fallbackTypeNameOrTypeId, keyColumns);
```

Reviewer check: the new branch returns `nodeTypesForTable.get(0).decodeMethod()` directly, whereas neighbouring branches construct `new HelperRef.Decode(encoderClass, ..., keyColumns)`. Confirm `NodeType.decodeMethod()` returns a fully-formed `HelperRef.Decode` carrying the correct `keyColumns`.
