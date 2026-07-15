---
id: R478
title: "Seal KeyAlternative.KeyShape so each variant carries its requiredFields/columns contract"
status: In Progress
bucket: architecture
priority: 5
theme: nodeid
depends-on: []
created: 2026-07-14
last-updated: 2026-07-15
---

# Seal KeyAlternative.KeyShape so each variant carries its requiredFields/columns contract

`KeyAlternative.KeyShape` is a two-value enum (`DIRECT`, `NODE_ID`) whose variants carry structurally different relationships between `requiredFields` and `columns`, stated only in javadoc prose: `DIRECT` promises `requiredFields.size() == columns.size()` with index-by-index value mapping, while `NODE_ID` promises `requiredFields == ["id"]` with a NodeId decode into the `columns` list. Nothing enforces either promise. R477 (batch node-id wrong-arity crash) is the motivating instance: the decode half of the pair sized its output from runtime data while the select half indexed by the model's column count, and the unenforced prose contract was the gap the bug lived in.

## Current shape (verified against source)

- `KeyAlternative` is a record `(List<String> requiredFields, List<ColumnRef> columns, boolean resolvable, KeyShape shape)`; both contracts live in its javadoc only.
- `HandleMethodBody.emitDecodeAndGroup` is the single genuine identity fork on `shape()`: copy rep values index-by-index (DIRECT) vs decode `rep.id` through `NodeIdEncoder.decodeValues` (NODE_ID). Its NODE_ID arm also re-derives the decode prefix at the emit site as `entity.nodeTypeId() != null ? entity.nodeTypeId() : entity.typeName()`. The fallback arm is dead code: `NodeType.typeId` is defaulted to the type name at classify time (`TypeBuilder` resolves `sdlTypeId != null ? sdlTypeId : name`), and NODE_ID alternatives exist only on `NodeType`s, so `nodeTypeId` is never null where a NODE_ID arm reads it. (The `GraphitronType.NodeType` javadoc still claims typeId is "null when the argument was omitted"; that is stale relative to `TypeBuilder`.)
- `SelectMethodBody` never reads `shape()`; it indexes `alt.columns()` uniformly for both shapes. That implicit reliance on the prose contract is exactly where R477 lived. `matchCondition`/`matchArgs`/`priorityOrder` in `HandleMethodBody` likewise consume `requiredFields()` uniformly.
- `EntityResolutionBuilder` constructs both shapes; `hasNodeIdAlternative` tag-checks `shape() == NODE_ID`.
- `EntityFetcherDispatchClassGenerator.buildTypenameForTypeIdMethod` reads `EntityResolution.nodeTypeId` (populated from the same `nt.typeId()` fact) for the typeId-to-typename reverse map, and the class carries an unused `KeyShape` import.

## Design

Per the "sealed hierarchies over enums for typed information" principle, replace the enum-carrying record with a sealed interface; the `KeyShape` enum disappears.

```java
public sealed interface KeyAlternative {
    List<String> requiredFields();
    List<ColumnRef> columns();
    boolean resolvable();

    /** One (rep field, column) pairing of a Direct alternative. */
    record RepBinding(String repField, ColumnRef column) {}

    /** Consumer-declared @key: rep field values map pairwise to column values. */
    record Direct(List<RepBinding> bindings, boolean resolvable) implements KeyAlternative {
        // requiredFields() and columns() derive from bindings
    }

    /** @node path: the rep's id is a base64 NodeId decoded by NodeIdEncoder. */
    record NodeId(String expectedTypeId, List<ColumnRef> columns, boolean resolvable)
            implements KeyAlternative {
        public List<String> requiredFields() { return List.of("id"); }
    }
}
```

The prose contracts become structure:

- `Direct`'s "sizes equal, index-by-index mapping" becomes unrepresentable: a pair list cannot have mismatched sizes. This is deliberately stronger than the smaller-diff alternative (two parallel lists plus a compact-constructor size check), which only rejects at build time what the pair list makes impossible at compile time; R477 is the standing evidence for buying the stronger form (principles consult concurs, and contrasts this with `SourceKey`'s compact-constructor invariants, which guard cross-axis combinations that cannot be made unrepresentable). The derived accessors allocate per call; that is build-time generator cost and acceptable.
- `NodeId`'s `requiredFields == ["id"]` promise becomes a derived accessor, not stored state that could disagree.
- `NodeId` carries its decode contract: `expectedTypeId` (the resolved wire prefix) plus `columns` (the decode arity). The emit-site prefix re-derivation and its dead fallback in `HandleMethodBody` delete; the decision moves to the builder where it belongs ("a generator branches on a predicate over pre-resolved data" smell).

Single-source the typeId fact (principles consult, point 1): drop `EntityResolution.nodeTypeId`. It and the new `expectedTypeId` would be the same value sourced from `nt.typeId()` in two slots with nothing binding them. `buildTypenameForTypeIdMethod` instead derives the reverse map from each entity's `NodeId` alternative: the presence sets coincide exactly (every `NodeType` entity gets a `NodeId` alternative, synthesised when no explicit `@key(fields: "id")` exists; non-`NodeType` entities never get one, and `required == ["id"]` yields at most one per entity), the map key is its `expectedTypeId`, and inclusion ignores `resolvable` to match today's `nodeTypeId != null` test.

Uniform facts stay interface accessors (`columns()` for `SelectMethodBody`, `requiredFields()` for match/priority logic, `resolvable()`); the one identity fork, `emitDecodeAndGroup`, becomes a sealed switch with **no default arm**, so a future third key shape is a compile error at the fork site rather than a silent runtime gap.

## Implementation

1. `KeyAlternative`: sealed interface as above; the variant javadoc states each contract as a fact of the structure, not a promise.
2. `EntityResolutionBuilder`: `buildAlternative` constructs `NodeId(nodeType.typeId(), nodeType.nodeKeyColumns(), resolvable)` on the node path and builds `Direct` from the (name, ColumnRef) pair loop it already runs; the synthetic alternative in `build()` likewise; `hasNodeIdAlternative` becomes an `instanceof` check; the `EntityResolution` constructor call loses `nodeTypeId`.
3. `HandleMethodBody.emitDecodeAndGroup`: exhaustive sealed switch; the `NodeId` arm reads `alt.expectedTypeId()` directly; the `Direct` arm iterates `bindings` (`cols[i] = rep.get(binding.repField())`).
4. `SelectMethodBody`: no change beyond consuming `columns()` through the interface.
5. `EntityFetcherDispatchClassGenerator`: `buildTypenameForTypeIdMethod` reads the `NodeId` alternative per the design above; sweep the unused `KeyShape` import.
6. `EntityResolution`: drop the `nodeTypeId` component and rewrite the javadoc paragraph that documents it to point at `KeyAlternative.NodeId.expectedTypeId`.
7. `GraphitronType.NodeType`: javadoc-only correction of the stale "null when the argument was omitted" claim on `typeId` (it is the resolved wire prefix, defaulted to the type name at classify time).
8. `RejectNonIdNodeIdPipelineTest` carries a comment naming `KeyShape.NODE_ID`; update the wording alongside.

## Sequencing

Depended on R477 (`batch-node-id-wrong-arity-crash`), now Done: R477 shipped the behavioural arity guard on its signed-off minimal patch, so R478 is a pure model refactor over the guarded emission. Both edit `emitDecodeAndGroup`'s NODE_ID arm; landing the bug fix first avoided rebasing it over a restructure, and that ordering has already played out.

## Out of scope

- Any behavioural or emitted-code change. The generated dispatcher must be identical before and after; the execution tier is the backstop, not the subject.
- Routing the batch decode through the per-type `decode<TypeName>` helpers (`HelperRef.Decode`). R477 explicitly kept the call-site guard convention; helper-vs-raw-decode is orthogonal to the model shape and stays out.
- New key shapes; this item only prices their arrival correctly (a compile error at every fork site).

## Tests

- `EntityResolutionBuilderTest`: rewrite the five `alt.shape()` tag assertions to `instanceof KeyAlternative.NodeId` / `Direct` pattern checks, asserting `expectedTypeId` on the variant for both the default and the explicit `@node(typeId:)` cases (these replace the `resolution.nodeTypeId()` assertions, which lose their subject) and the pairwise `bindings` on a `Direct` case.
- Same test class: assert the derived accessors on constructed alternatives (`NodeId.requiredFields()` is exactly `["id"]`; `Direct.requiredFields()`/`columns()` unzip `bindings` in order).
- No pipeline or execution tier changes. The existing federation dispatch, node/nodes, and R477 wrong-arity tests staying green unchanged is the acceptance evidence for the zero-diff claim.

## Acceptance

- The `KeyShape` enum is gone; `KeyAlternative` is a sealed interface with `Direct`/`NodeId` variants; `emitDecodeAndGroup` switches exhaustively with no default arm.
- `EntityResolution.nodeTypeId` is gone; the `typenameForTypeId` map derives from the `NodeId` alternatives.
- Generated output is unchanged: no pipeline snapshot or execution test edits anywhere in the change, and the two named unit-test files are the only test churn.
- Full `mvn install -Plocal-db` passes.
