---
id: R692
title: "Decide whether an element-less @reference on an argument or input field is an author error"
status: Backlog
bucket: architecture
priority: 7
theme: diagnostics
depends-on: []
created: 2026-08-17
last-updated: 2026-08-17
---

# Decide whether an element-less @reference on an argument or input field is an author error

`@reference(path: [])` is legal SDL, and on a field definition the empty list is the documented "infer
the foreign key" spelling: `BuildContext.parsePath` resolves the single FK between the field's start and
target tables. On an *argument* or an *input field* there is no target table to infer against (both
sites call `parsePath` with a null `targetSqlTableName`), so the empty path stays empty, the column
resolves against the field's own table, and the carrier behaves exactly as if the author had written no
directive at all. The directive is inert in those two positions.

Today both sites accept it silently. That is deliberate as of the FK-target `@nodeId` translated-filter
work, which fixed a crash on this shape by binding it `Local` on the argument side to match the
input-field side, and explicitly left the question of whether it should be *accepted* alone. An inert
directive is a small authoring trap: it looks like it constrains the filter and does nothing, and the
neighbouring repeated-`@reference` case is already a structural rejection, so silence here is not
obviously the house answer.

The decision is one decision over both positions, not two: rejecting on the argument side alone would
re-create the asymmetry that produced the original crash. If it goes to a rejection it needs the
classifier arm, the mirror in `GraphitronSchemaValidator`, and a sentence in the `@reference` manual
page saying where the empty-path inference spelling does and does not apply. Weigh that against the
possibility that some consumer schema writes the empty path deliberately (for instance generated SDL
that always emits the `path:` argument), for which a rejection is a breaking change.
