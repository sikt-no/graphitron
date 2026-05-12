---
id: R145
title: "Cardinality safety story for UPSERT under the @multiRow regime"
status: Backlog
bucket: architecture
priority: 13
theme: mutations-errors
depends-on: [mutation-cardinality-safety-default]
---

# Cardinality safety story for UPSERT under the @multiRow regime

R144 inverts the cardinality-safety polarity on DELETE and UPDATE (default
treats every input field as a WHERE filter; PK coverage required; `@multiRow`
is the opt-out). UPSERT is carved out at R144's classify-time rejection
because its semantics differ: `INSERT ... ON CONFLICT (cols) DO UPDATE SET ...`
requires the conflict-target columns to form a unique constraint by
definition, and one input row matches at most one existing row. The
`@multiRow` knob does not apply the same way. This item designs the
UPSERT-specific safety story, lifts R144's classify-time rejection, and
restores UPSERT-generation. Existing UPSERT fixtures in `sakila-example` and
`GraphitronSchemaBuilderTest` migrate as part of this work.

**Headline design fork.** Under R145 the conflict target is a named
unique key (PK by default; alternative unique key via a directive
argument, shape TBD — candidates: `@mutation(typeName: UPSERT,
conflictKey: "alt_unique_index_name")` or a sibling directive
`@onConflict(key: ...)`). Cardinality is structurally enforced by SQL
because the conflict target is a unique constraint by definition; no
`@multiRow` analogue is needed. The `@value` partition extends
naturally from R144 (the SET-side fields of the UPDATE-arm and the
INSERT-arm values). UPSERT with no `@value` fields is structurally
"INSERT-or-no-op" and may admit; UPSERT with no filter fields is
rejected as ill-formed (the conflict target must be input-driven).

R145 also lifts R141's bulk-carrier UPSERT rejection (`MutationBulkDmlRecordField`
rejects `DmlKind.UPSERT` at classify time, with a compact-constructor backstop;
see R141's shipped entry in `changelog.md`) and adds the UPSERT branch to R141's
parameterised emitter dispatch. R141 narrowed its admitted-kinds matrix to
`{INSERT, UPDATE}` to share R144's UPSERT carve-out before it shipped; R145's
landing reopens UPSERT on both the direct-DML arm (`MutationUpsertTableField`)
and R141's bulk-carrier arm in one pass.
