---
id: R731
title: "The resolved key-column list hands out a spelling, so every consumer folds at the crossing"
status: Backlog
bucket: architecture
priority: 4
theme: classification-model
depends-on: []
created: 2026-08-19
last-updated: 2026-08-19
---

# The resolved key-column list hands out a spelling, so every consumer folds at the crossing

`intent_resolved_node_key_column` answers "which columns does this type encode a node id from" with a
*name*: the winning tier's own spelling, as written on `SDL_PINNED`, as the generated class stated it
on `JOOQ_METADATA`, and the catalog's own on `CATALOG_PRIMARY_KEY`. A name is not a resolved column,
and the view's comment says so outright: whether the name is a column the table actually has "is
deliberately not asked here". Every consumer that has to match against that name therefore folds case
at the crossing, because the answer it was handed is a spelling rather than a reference.

That is why `intent_resolved_node_key_projection` carries the schema's only surviving per-row case
fold. The key-column side has nowhere to reach, because the value came out of a three-tier pick and
no single base relation owns it. The comparison is correct and this item is not a bug report about
it.

**Dated 2026-09-03, after the argMapping coordinate remodelling.** The fold used to read
`UPPER(k.column_name) = sg.segment_name_upper`, with the authored side folded properly on
`graphitron_argument_path_segment.segment_name_upper`. That relation is gone: the authored name is
`graphitron_argmapping_match.trailing_name` now, carried up from the entry's generated split rather
than off a stored decomposition, and the comparison is `UPPER(k.column_name) = UPPER(l.trailing_name)`
with neither side stored folded. That makes the asymmetry this item is about worse rather than
better, and it does not change what the item asks: a spelling handed across a crossing is still a
spelling. Re-read the fold before acting on the prose above.

The question is whether the view is handing out the wrong thing. The schema has a worked example of
the other shape: `intent_spelled_table` is a union across five-plus arms with no single owning
relation either, and it consumes each arm's own stored `_upper` internally, matches, and exposes the
*resolved table*. The fold is an implementation detail of a resolution there, and no reader ever sees
one. That is why no view in this schema exposes an `_upper` column, all fifty-five of them, and why
minting one on this reduction is the wrong fix: it would treat the symptom and break the invariant.

So the fix worth costing is the resolution itself. Three sub-questions a Spec has to answer, and they
may not have one answer between them:

* Can the `JOOQ_METADATA` tier project a real column rather than the stated entry name? R724's
  `intent_stated_key_column_match` is exactly the machinery, carrying the matched `sql_column`'s own
  spelling alongside the arity that says whether the match was unambiguous. That tier is the one where
  a stated name most obviously is not a resolved column, the entry being free to name a column the
  table does not have.
* Can the `SDL_PINNED` tier resolve at all? It answers without a table, `graphitron_node_key_column`
  being keyed by graph and type, which is what lets a type with an ambiguous binding still pin its key
  columns. A tier that has no table cannot resolve a column against one, so this tier may be
  irreducibly a spelling, and if it is, the view keeps handing out names and the whole item collapses
  to a smaller one.
* If the tiers cannot be made uniform, is the right answer a second relation beside this one that
  resolves where resolution is possible, rather than changing this one?

The prize is real but modest and should be sized honestly before anyone starts: the schema's last
per-row fold retires, the fold rule ends with no exception, and a consumer needing a real column stops
receiving a name it has to re-resolve. The cost may be that one tier cannot participate.

Provenance, since the reasoning is easy to lose: R668 hit this while landing the projection, shipped a
`column_name_upper` on the reduction, and reverted it on the no-forwarding rule. That revert was
right. The note it left behind gives the multi-arm shape as the reason no fold is reachable, which
`intent_spelled_table` refutes, and this item exists because the correct reason is one level down.

