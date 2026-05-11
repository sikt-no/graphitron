---
id: R131
title: "Singular ID! @nodeId classifier emits Reference variants on same-table case"
status: Backlog
bucket: validation
priority: 1
theme: nodeid
depends-on: []
---

# Singular ID! @nodeId classifier emits Reference variants on same-table case

Singular `id: ID! @nodeId` on a `@table`-annotated input is unconditionally classified as `ColumnReferenceField` / `CompositeColumnReferenceField`, even when the inferred NodeType target table is the parent's own table (the canonical "filter own rows by primary key" case). The list-of-IDs sibling branch already gets this right by delegating to `NodeIdLeafResolver.resolve(...)`, which distinguishes `SameTable` (→ `ColumnField` / `CompositeColumnField`) from `FkTarget.DirectFk` (→ `ColumnReferenceField` / `CompositeColumnReferenceField`). The singular branch skips that fork and emits the Reference variant unconditionally; the comment at `BuildContext.java:1428` promises lockstep with the list branch and with `FieldBuilder.classifyArgument`, but the singular paths drift.

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

- `BuildContext.classifyInputField`, singular `ID! @nodeId` branch at `BuildContext.java:1330`: mirror the list-branch SameTable/FK fork by routing through `NodeIdLeafResolver.resolve(...)` (or an equivalent shared helper) so the two paths share one decision site. Emit `ColumnField` / `CompositeColumnField` on `SameTable` (arity 1 / ≥2) and keep `ColumnReferenceField` / `CompositeColumnReferenceField` on `FkTarget.DirectFk`.
- `FieldBuilder.classifyArgument`, singular `ID! @nodeId` path: audit for the same defect and land a consistent fix in the same item — the comment at `BuildContext.java:1428-1429` already names this as a lockstep partner.
- Reachability: this defect is currently only reachable on the singular shape; the list shape already takes the correct branch. Tests must cover singular + same-table + composite-PK, singular + same-table + single-PK, singular + FK-target (both PK arities) to lock in symmetry across all four corners.

## Out of scope (file separately if needed)

- Multi-hop and condition-join singular `@nodeId` shapes — they should be rejected at this layer the same way the list branch rejects them, but any emitter changes for those shapes belong elsewhere.
- `TranslatedFk` (FK source/target columns differ from NodeType keyColumns) — the singular branch should match the list branch's rejection-with-hint, mirroring R57 on the deferral track.

## Implementation hint

`buildInputNodeIdReference` at `BuildContext.java:1677-1707` is the singular sink today and only knows the Reference variants. Either widen it to a four-way switch on resolver outcome × arity, or split it: the list branch's switch at `BuildContext.java:1430-1467` is the template. Sharing one helper across both branches (and across `FieldBuilder.classifyArgument`) is the right shape so future variants ripple uniformly.
