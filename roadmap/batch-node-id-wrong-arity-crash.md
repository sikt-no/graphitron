---
id: R477
title: "Batch node-id decode crashes on wrong-arity ids (Query.node/nodes/_entities)"
status: Ready
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

## Root cause (verified against current source)

`HandleMethodBody.emitDecodeAndGroup` emits, for a `KeyShape.NODE_ID` alternative:

```java
String[] decoded = NodeIdEncoder.decodeValues("Opptak", idStr);
if (decoded == null) continue;                          // null-check only
Object[] cols = new Object[decoded.length];             // sized by decoded length
for (int j = 0; j < decoded.length; j++) cols[j] = decoded[j];
```

`cols` is sized to `decoded.length`, but the paired `SelectMethodBody.body` builds each `VALUES` row through `ValuesJoinRowBuilder.cellsCode` with a `cols[<i>]` accessor for every column in `alt.columns()` (the fixed composite-key column count). Two failure modes follow:

- **Under-arity** (`decoded.length < alt.columns().size()`): `cols[1]` (etc.) throws AIOOBE inside the generated `select<TypeName>Alt<N>`. This is the observed crash.
- **Over-arity** (`decoded.length > alt.columns().size()`): the extra decoded parts are silently ignored, so an id like `FilmActor:1,1,999` resolves to the row keyed `(1,1)` if one exists. A wrong-row return, arguably worse than the crash; the same guard fixes it.

The single-record decode path is already correct and inconsistent with this one: the per-type decode helpers emitted by `NodeIdEncoderClassGenerator` guard `if (values == null || values.length != N) return null;`, and `InputBeanInstantiationEmitter` guards `values.length != arity` the same way. Only the batch `resolveByReps` path omits the `!= N` check.

`KeyAlternative.KeyShape.NODE_ID`'s contract ("the rep's id is decoded by NodeIdEncoder into the columns list") makes `alt.columns().size()` the arity source. `DIRECT` alternatives are unaffected: they size `cols` by `requiredFields`, which the record's contract fixes equal to `columns` for that shape.

## Implementation

In `HandleMethodBody.emitDecodeAndGroup`, `NODE_ID` branch only, tighten the emitted guard to reject arity mismatches, mirroring the single-record path, and size `cols` from the model rather than from the runtime array:

```java
b.addStatement("if (decoded == null || decoded.length != $L) continue", alt.columns().size());
b.addStatement("Object[] cols = new Object[$L]", alt.columns().size());
```

With the guard, a wrong-arity id is treated exactly like a garbage/unknown id: the rep is skipped, its result slot stays `null`, matching Relay ("if no such object exists, the field returns null"), the opacity stance (decoding detail never leaks into the error surface), and the existing single-record behaviour.

Sizing `cols` by `alt.columns().size()` (equal to `decoded.length` once past the guard) makes both halves of the decode/select pair read the arity from the same model fact: `SelectMethodBody` already indexes `cols[0..alt.columns().size()-1]`, so after this change `decoded.length` is inspected exactly once, at the boundary check where a wire quantity belongs (principles consult, point 1).

Deliberately **not** in scope:

- Making `NodeIdEncoder.decodeValues` arity-aware. It is a generic `String[]` decoder shared by call sites that each know their own arity from the model; every other caller already guards at the call site, so the call-site guard keeps one consistent convention rather than splitting arity knowledge across two layers (principles consult concurs).
- Surfacing a GraphQL error for wrong-arity ids. Null-not-error is the documented contract, and the `_entities` variant currently leaks a raw `Index 1 out of bounds for length 1` message machine-to-machine; null is strictly better on both axes.
- Extracting a shared emit-helper for the guarded-decode pattern (this becomes the third hand-written copy alongside `NodeIdEncoderClassGenerator` and `InputBeanInstantiationEmitter`). All three read the arity number from the same model fact, so they cannot disagree on the count; a shared helper is backlog material only if a fourth batch-decode consumer ever appears.
- Restructuring `KeyAlternative.KeyShape` (enum whose variants carry different `requiredFields`/`columns` invariants in prose only, the structural reason this bug had a place to live). Filed separately as R478 (`keyshape-sealed-variants`).

## Tests

Execution tier in `graphitron-sakila-example`, on `FilmActor` (composite PK `actor_id, film_id`; it is the fixture schema's composite-key `@node` type, and a single-column-key type cannot exercise the bug). Wrong-arity ids are crafted with the generated `NodeIdEncoder.encode(typeId, Object...)` varargs, which happily encodes any part count.

1. `GraphQLQueryTest` (next to `node_garbageInput_returnsNull`): `node(id:)` with a 1-part `FilmActor` id returns `null`, no GraphQL errors.
2. `GraphQLQueryTest` (next to the existing `nodes(ids:)` garbage-slot test): `nodes(ids: [valid, under-arity, over-arity])` returns the resolved row and `null` in the two wrong-arity slots, positions preserved.
3. `FederationEntitiesDispatchTest` (next to `entities_garbageNodeId_yieldsNullSlot`): an `_entities` rep `{__typename: "FilmActor", id: <under-arity>}` yields a `null` slot with an empty errors list (the test class's `execute` helper already asserts errors are empty).
4. `FederationEntitiesDispatchTest`: an over-arity rep whose 3-part id's 2-part prefix **is** an existing row (e.g. extra part appended to a known-valid `(1,1)` id) yields `null`, guarding the wrong-row-return hazard, not just the crash. Match the part order to the generated `encodeFilmActor` helper (round-trip a valid id first if in doubt).

No pipeline-tier snapshot updates are needed: nothing currently pins the emitted guard text, and the change is behavioural, which is exactly what the execution tier exists to observe.

## Acceptance

- All three entry points return `null` (empty errors) for well-formed ids with wrong key arity on a composite-key `@node` type; the opptak reproduction above no longer 500s.
- Correct-arity behaviour is unchanged: the existing dispatch, node, and nodes tests stay green.
- Full `mvn install -Plocal-db` passes.
