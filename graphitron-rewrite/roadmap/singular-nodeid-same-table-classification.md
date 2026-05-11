---
id: R131
title: "Singular ID! @nodeId classifier emits Reference variants on same-table case"
status: Spec
bucket: validation
priority: 1
theme: nodeid
depends-on: []
---

# Singular ID! @nodeId classifier emits Reference variants on same-table case

Singular `id: ID! @nodeId` on a `@table`-annotated input is unconditionally classified as `ColumnReferenceField` / `CompositeColumnReferenceField`, even when the inferred NodeType target table is the parent's own table (the canonical "filter own rows by primary key" case). The list-of-IDs sibling branch already gets this right by delegating to `NodeIdLeafResolver.resolve(...)`, which distinguishes `SameTable` (→ `ColumnField` / `CompositeColumnField`) from `FkTarget.DirectFk` (→ `ColumnReferenceField` / `CompositeColumnReferenceField`). The singular branch skips that fork and emits the Reference variant unconditionally; the comment at `BuildContext.java:1425-1429` promises lockstep with the list branch and with `FieldBuilder.classifyArgument`, but the singular paths drift.

Canonical reproducer:

```graphql
input SlettRegelverksamlingInput @table(name: "regelverksamling") {
    id: ID! @nodeId
}
extend type Mutation {
    slettRegelverksamling(input: [SlettRegelverksamlingInput!]!): SlettRegelverksamlingPayload! @mutation(typeName: DELETE)
}
```

`regelverksamling` has a composite primary key; the field semantically filters the parent's own rows by PK, so the correct classification is `CompositeColumnField`. Today it lands on `CompositeColumnReferenceField`, which forces the emitter to model the predicate as a join/reference rather than a direct same-table PK filter — wrong shape for delete-by-PK mutations and for any equivalent input shape on read paths.

## Relationship to R130

R130 (`composite-reference-in-mutation-input.md`, Spec, `mutations-errors` theme) addresses the same forcing function (`SlettRegelverksamlingInput`) but from the opposite direction: it accepts the current `CompositeColumnReferenceField` classification as correct and proposes a Phase 1-5 rework of `MutationInputResolver` and DML emitters to support all four currently-deferred carriers (`ColumnField` NodeId-decoded, `ColumnReferenceField`, `CompositeColumnField`, `CompositeColumnReferenceField`).

R131 contends the classification itself is wrong on the same-table axis. If R131 lands, R130's Phase 1 `fieldBindings` widening still applies to the genuinely-joined `FkTarget.DirectFk` carriers (`ColumnReferenceField`, `CompositeColumnReferenceField`), but the same-table half of R130's surface collapses into the existing column-direct emission path — no fieldBindings widening needed for those, no new `RecordBinding` arm in the mutation walker for those, no new `@LoadBearingClassifierCheck` arm for the same-table case.

The two items should be specced together; R130's author should adapt R130's scope to the genuinely-joined-only carriers once R131's premise is accepted.

## Scope

- **Share the sealed `Resolved` outcome, not just a helper.** The list branch at `BuildContext.java:1430-1467` switches over `NodeIdLeafResolver.Resolved` (`SameTable | FkTarget.DirectFk | FkTarget.TranslatedFk | Rejected`). The singular branch open-codes its own multi-hop path parse, `validateLift` call, and `liftSourceColumns` invocation at `:1393-1418`, then funnels every outcome into the Reference-only sink `buildInputNodeIdReference` at `:1677-1707`. R131 extends `NodeIdLeafResolver.resolve` (or adds a singular-shape sibling) to return the same `Resolved` sealed for the singular case, and refactors both branches to share *one switch shape over one sealed result*. The Reference-only sink becomes the body of the `FkTarget.DirectFk` arm; the same-table arm produces `ColumnField` (arity 1) / `CompositeColumnField` (arity ≥ 2). Avoid landing a parallel four-way switch in `buildInputNodeIdReference` — that duplicates the decision site instead of collapsing it.
- **Bare-`@nodeId` typeName inference collapses into the resolver.** `BuildContext.inferNodeIdTypeName` at `BuildContext.java:1246-1266` is a duplicate of `NodeIdLeafResolver.inferTypeName` at `NodeIdLeafResolver.java:355-374` (called from `resolve` at `:263`): same explicit-arg read, same `findGraphQLTypesForTable` lookup, same zero/multi-candidate diagnostics. The only difference is the error wrapper (`InputFieldResolution.Unresolved` vs `TypeNameResult`), which the singular call site can apply after the routing collapse. R131 deletes `BuildContext.inferNodeIdTypeName` along with the routing-collapse — keeping two copies of the same predicate is the exact "same predicate evaluated by multiple consumers" smell the generation-thinking principle calls out.
- **`@LoadBearingClassifierCheck` audit, in the same commit.** The producer-side annotation `@LoadBearingClassifierCheck(key = "nodeid-fk.direct-fk-keys-match")` lives on `NodeIdLeafResolver.resolve` at `NodeIdLeafResolver.java:231-241`; its consumer mirror `@DependsOnClassifierCheck(key = "nodeid-fk.direct-fk-keys-match")` on `BuildContext.classifyInputFieldInternal` at `BuildContext.java:1299-1305` reads "NodeId FK-target arms construct `ColumnReferenceField` / `CompositeColumnReferenceField` only on `NodeIdLeafResolver.Resolved.FkTarget.DirectFk`." Today the singular branch violates this contract on the same-table case, so the consumer's declaration is aspirational. R131 makes it truthful. Re-read the producer description on `NodeIdLeafResolver` and either tighten its wording or confirm it already covers the singular consumer once routed. Confirm no downstream emitter relies on the now-stale "this `CompositeColumnReferenceField` carrier may represent a same-table PK filter" reading — the audit is the cheap check the principle asks for in the same commit; do not split it into a follow-on.
- **`FieldBuilder.classifyArgument`, singular `ID! @nodeId` argument shape.** Canonical shape: `field(id: ID! @nodeId): T` on a lookup field where the inferred target table equals the lookup's own table. Implementer is expected to either (a) confirm `classifyArgument` already routes through `NodeIdLeafResolver.resolve` and produces the correct sealed leaf, and pin that with a pipeline-tier test; or (b) find the same drift here and apply the same routing-collapse fix. The Ready-gate reviewer should see one of those two outcomes resolved in the implementation, not deferred.
- **Test tier: pipeline.** The four corners (singular + same-table + composite-PK, singular + same-table + single-PK, singular + FK-target + single-PK, singular + FK-target + composite-PK) are SDL → classified-model facts: SDL fragment → `GraphitronSchema` → assert on the classified `InputField` leaf identity. Pipeline tier is the primary, per the project's test-tier rubric; per-variant unit tests are explicitly *not* the primary tier. One corner additionally rides through the compilation tier via the sakila example (or an analogue) to close the SDL → emitted-source loop — the natural carrier is a composite-PK same-table delete-by-PK mutation modelled on the `slettRegelverksamling` shape.

## Reachability claims (pipeline tests must pin these)

- Multi-hop singular `@nodeId` rejects today at `BuildContext.java:1400-1418` via the FkJoin loop and `validateLift`. After the routing collapse, the rejection comes from `NodeIdLeafResolver.resolve` (same code, different call site) and produces the same error text the list branch produces. No new rejection paths.
- Condition-join singular `@nodeId` rejects today at `:1400-1408` ("step N is a condition step …"). After the collapse, this comes from the resolver. Same text as the list branch.
- `TranslatedFk` singular `@nodeId` reaches `Resolved.FkTarget.TranslatedFk` and rejects via `FieldBuilder.translatedFkRejectionReason` — the *same* message the list branch emits at `BuildContext.java:1466`. Not a parallel message.

Pipeline tests anchor on the shared marker constants on `NodeIdLeafResolver` (`LIFT_FAILURE_MARKER`, `CONDITION_STEP_MARKER`), not on copied substrings of the resolver's prose — same precedent R114 set for the multi-hop / condition-step rejections, so the two branches' tests will assert against the same symbol and drift surfaces as a constant-rename across both call sites at once.

If the implementer finds any of these does *not* hold no-op-by-construction (e.g. the singular branch's `validateLift` text diverges from `NodeIdLeafResolver`'s), the alignment is in-scope for R131; do not split it.

## Out of scope (file separately if needed)

- Emitter changes for multi-hop / condition-join `@nodeId` carriers. Those remain rejected at the classifier; supporting them is its own item.
- Same-table semantics on `NestingField`-shaped input carriers (R130's fifth deferred carrier). R131 covers leaf `@nodeId` only.
- The genuinely-joined `FkTarget.DirectFk` half of R130's emitter widening. R130 retains scope for those carriers.

## Implementation hint

The list branch's switch at `BuildContext.java:1430-1467` is the template for the unified switch. After R131 lands, the singular branch is structurally a copy of that switch with `list = false` baked in, and the duplicate `BuildContext.inferNodeIdTypeName` is gone (the resolver does the inference). The Reference-only sink `buildInputNodeIdReference` at `:1677-1707` either becomes the body of the `FkTarget.DirectFk` arm (preferred — one helper, called from both arities) or is inlined and deleted (acceptable if the inlining is short). Sharing the switch shape across both arity branches *and* `FieldBuilder.classifyArgument` is the load-bearing outcome; if the implementer finds they need three call sites of the switch, the helper extraction failed and the design needs revisiting before merge.
