---
id: R380
title: "Implement @reference join-subquery filter conditions on input fields"
status: Backlog
bucket: feature
priority: 5
theme: structural-refactor
depends-on: []
created: 2026-06-25
last-updated: 2026-06-25
---

# Implement @reference join-subquery filter conditions on input fields

`@reference` is accepted on `INPUT_FIELD_DEFINITION` / `ARGUMENT_DEFINITION` (the directive
grammar permits it, and the how-to advertises an input-field variant), but the filter-
condition emitter ignores the join path entirely. `TypeConditionsGenerator.buildConditionMethod`
(`TypeConditionsGenerator.java:85`) takes a single `Table table` parameter bound to the
field's own table (`gcf.tableRef()`) and emits every predicate as `table.<COLUMN>`
(`:104`, `:118`, `:130`, `:145`). When a filter-argument field carries
`@reference(path:[...])` aiming at a column on a *joined* table, the generator binds that
column by name against the local table and the generated source fails to compile with
`cannot find symbol`.

Observed in the wild (utdanningsregisteret): filter fields referencing
`STATUS_SELVAKKREDITERENDE`, `NKRKODE`, `UTDANNINGSSPESIFIKASJONSTYPE_KODE`,
`STATUS_GYLDIG_AKKREDITERING` resolved against `Organisasjon` / `Utdanningsmulighet` /
`Utdanningsspesifikasjon`, none of which carry those columns; the author expected the path
to produce a join-subquery condition.

Decision (with the user): implement the join now rather than reject-loudly. A filter field
with `@reference` should emit a correlated subquery / `EXISTS` that joins through the path
and applies the predicate against the *terminal* alias, not the local table. The machinery
to model on already exists:

* `InlineTableFieldEmitter.buildInnerSelect` (`:125`) builds correlated join-subqueries
  walking the same `List<JoinStep>` path with hop aliases.
* `FkTargetConditionEmitter.declareAliases` (`InlineTableFieldEmitter.java:105`) already
  emits FK-target `EXISTS` for `@nodeId` override `@condition` filters; the
  correlated-EXISTS-against-a-joined-alias shape is precedent.

Work for Spec: thread the parsed join path into `GeneratedConditionFilter` (today it
carries only `tableRef` + body params, no path), teach the condition model/emitter to
declare hop aliases and emit the predicate against the terminal alias inside an `EXISTS`
correlated back to the parent, and cover multi-hop, composite keys, null/empty-list
semantics (mirroring the existing `In`/`RowIn` empty guards), and interaction with
`@splitQuery`. Reconcile the public how-to (`join-with-references.adoc:172`), which
currently advertises input-field `@reference` as single-hop column projection only; this
extends it to remote-column filtering.

Depends conceptually on R379 (terminal-hop resolution) since both hinge on the path's
terminal table being correctly resolved.
