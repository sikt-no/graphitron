---
id: R540
title: "mutation.adoc documents UPSERT the generator refuses at dispatch"
status: Backlog
bucket: docs
priority: 7
theme: legacy-migration
depends-on: []
created: 2026-07-26
last-updated: 2026-07-26
---

# `mutation.adoc` documents UPSERT the generator refuses at dispatch

`docs/manual/reference/directives/mutation.adoc` still teaches
`@mutation(typeName: UPSERT)` as a working verb: it appears as a documented enum
value with `ON CONFLICT` semantics and row-identification prose (about nine
mentions on the page). The generator refuses the verb at classifier dispatch
with "not yet supported; the conflict-target's uniqueness and the bulk-UPSERT
cardinality story are not designed" (`FieldBuilder`'s DML-kind dispatch), the
example schema carries no upsert field, and the `directives.graphqls`
doc-comment already states the refusal. A reader following the manual authors a
schema the build rejects.

Surfaced during the R519 docs sweep, which removed only UPSERT's
`@table`-on-input dependencies and left the broader staleness in place as out
of scope for that item.

Fix shape: cut the page's UPSERT sections down to the same one-line
refused-for-now statement the SDL doc-comment makes (keeping the enum value
listed, since the declaration still declares it), or, if UPSERT support is
genuinely near, leave the prose and say so explicitly with a "not yet
implemented" banner. Either way the page must stop describing unimplemented
`ON CONFLICT` behavior as current. Check `deprecations.adoc` and the tutorial
for echoes while in there.
