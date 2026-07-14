---
id: R477
title: "Batch node-id decode crashes on wrong-arity ids (Query.node/nodes/_entities)"
status: Backlog
bucket: bug
priority: 4
theme: nodeid
depends-on: []
created: 2026-07-14
last-updated: 2026-07-14
---

# Batch node-id decode crashes on wrong-arity ids (Query.node/nodes/_entities)

The batch entity-resolution path (`EntityFetcherDispatch`, used by `Query.node(id:)`, `Query.nodes(ids:)`, and federation `_entities`) throws `ArrayIndexOutOfBoundsException` when a client supplies a well-formed node id whose decoded key has the wrong number of parts for a composite-key type. Per the Relay spec, and per `QueryNodeFetcher.getNode`'s own docstring ("Returns null for null/garbage/unknown IDs"), a non-resolvable id must yield null, never an error. Garbage ids (unparseable base64, unknown type prefix, missing colon) are already handled correctly and return null; only the recognised-type / wrong-arity case crashes.

## Reproduction (opptak subgraph, live)

Opptak has a 2-column key (`OPPTAKSTYPE_KODE`, `OPPTAK_KODE`):

- `opptakNode(id: "T3BwdGFrOkZTVSw5OTk=")` (`Opptak:FSU,999`, correct arity, nonexistent) → null ✓
- `opptakNode(id: "T3BwdGFrOjk5OQ==")` (`Opptak:999`, 1 key part) → 500, redacted `DataFetchingException` ✗
- `_entities(representations: [{__typename:"Opptak", id:"T3BwdGFrOjk5OQ=="}])` → 500 with the raw, unredacted message `Index 1 out of bounds for length 1` ✗

The `_entities` variant is the more serious of the two: the Apollo router / federation gateway drives it machine-to-machine, and the raw AIOOBE message leaks internal implementation detail through the error surface.

## Root cause

`HandleMethodBody.emitDecodeAndGroup` (`graphitron/.../generators/util/HandleMethodBody.java`, ~L111-121) emits, for a `KeyShape.NODE_ID` alternative:

```java
String[] decoded = NodeIdEncoder.decodeValues("Opptak", idStr);
if (decoded == null) continue;                          // null-check only
Object[] cols = new Object[decoded.length];             // sized by decoded length
for (int j = 0; j < decoded.length; j++) cols[j] = decoded[j];
```

`cols` is sized to `decoded.length`, but the paired `SelectMethodBody` (~L85, L100-102) emits `DSL.val(cols[0] ..)`, `DSL.val(cols[1] ..)` for every column in `alt.columns()` (the fixed composite-key column count). When `decoded.length < alt.columns().size()`, `cols[1]` (etc.) throws AIOOBE inside the generated `selectXxxAlt0`.

The single-record decode path is already correct and inconsistent with this one: the per-type `toRecord`-style helpers emit `if (values == null || values.length != N) return null;`, a full arity guard. Only the batch `resolveByReps` path omits the `!= N` check.

## Proposed fix

In `HandleMethodBody.emitDecodeAndGroup`, tighten the `NODE_ID` guard to also reject arity mismatches, mirroring the single-record path:

```java
b.addStatement("if (decoded == null || decoded.length != $L) continue", alt.columns().size());
```

(Confirm `alt.columns().size()` is the right count; it is what `SelectMethodBody` indexes for a `NODE_ID` alt.) With the guard, a wrong-arity id is treated as garbage/unknown → the rep is skipped → null result, matching Relay and the existing single-record behaviour. Add fixture coverage for the wrong-arity case on a composite-key `@node` type across all three entry points (`node`, `nodes`, `_entities`), since a single-column-key type cannot exercise the bug.
