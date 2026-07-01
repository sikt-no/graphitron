---
id: R57
title: "FK-target argument @nodeId, JOIN-with-translation emission"
status: Backlog
bucket: architecture
priority: 5
theme: nodeid
depends-on: []
---

# FK-target argument @nodeId, JOIN-with-translation emission

R40 shipped the simple direct-FK case for argument-level FK-target `@nodeId`: when the FK source columns positionally match the target NodeType's keyColumns, projectFilters emits `BodyParam.In` / `Eq` / `RowIn` / `RowEq` against `joinPath[0].sourceColumns()` directly, no JOIN required.

The pathological case (FK target columns differ from the NodeType keyColumns) is rejected at classify time with a deferred-emission hint. The R50 `parent_node` + `child_ref` fixture is the canonical reproducer: `child_ref.parent_alt_key` references `parent_node.alt_key` (a non-PK unique column), but `ParentNode`'s `__NODE_KEY_COLUMNS` pin the encode/decode key to `parent_node.pk_id`. Without translation through a JOIN, the input-side filter cannot turn decoded `pk_id` values into a predicate against `child_ref.parent_alt_key`.

Symmetric to R24 on the output side: R24 absorbed the rooted-at-parent JOIN-with-projection emission for output-side reference fields where FK source columns differ from NodeType keyColumns. R57 is the input-side counterpart, threading a `joinPath`-aware emitter through `GeneratedConditionFilter` (or an EXISTS-subquery shape) so the conditions class can translate `child_ref.parent_alt_key`-side rows to the joined `parent_node.pk_id` predicate.

Scope:

- Single-hop FK-target argument `@nodeId` where `joinPath[0].targetColumns()` does not positionally match the resolved NodeType keyColumns.
- Both scalar and list arity, single-PK and composite-PK target NodeTypes.
- Symmetric extension to input-field `[ID!] @nodeId(typeName: T)` carriers where the same shape arises (R50 ships the classifier; emitter-side JOIN translation is the gap there too).

Out of scope (file separately if/when needed):

- Multi-hop FK-target (already filed as a separate sibling item).
- Condition-join FK-target.

Implementation hint: the resolver returns a `Resolved.FkTarget` whose `joinPath: List<JoinStep>` carries both the FK source columns and the FK target columns; the emitter joins through the FkJoin and projects against the joined-side `keyColumns()` (already on the carrier as `column` / `columns`). The decoder tuple is unchanged; only the SQL shape needs to add the JOIN and switch the predicate's column reference.

Reproduction surface lives in `nodeidfixture` already (`parent_node` + `child_ref`); the existing classification test `ArgumentFkTargetNodeIdCase.FK_TARGET_PATHOLOGICAL_KEY_MISMATCH_DEFERRED` flips from rejection to classified-as-FkTarget when this lands.
