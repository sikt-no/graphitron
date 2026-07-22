---
id: R513
title: "Fetchers helper names collide when two same-named classes come from different schema packages"
status: Backlog
bucket: bug
priority: 3
theme: codegen-correctness
depends-on: []
created: 2026-07-22
last-updated: 2026-07-22
---

# Fetchers helper names collide when two same-named classes come from different schema packages

When a database exposes two schemas that both contain a table of the same
name (e.g. `opptak.OPPTAK` and `opptak_v2.OPPTAK`), jOOQ generates two record
classes with an identical simple name but distinct packages
(`...jooq.opptak.tables.records.OpptakRecord` vs
`...jooq.opptak_v2.tables.records.OpptakRecord`). If a schema declares
mutations against both, the generated `*Fetchers` class (e.g.
`MutationFetchers`) emits two helpers with the same name and signature and
fails to compile: the author cannot express cross-schema mutations at all.

The helper-name resolvers key the method-name *stem* on `ClassName.simpleName()`
and dedup only *within* a full-`ClassName` group, never *across* two distinct
classes that share a simple name. `JooqRecordHelperNames.of()` groups carriers
by full `ClassName` (so the two `OpptakRecord` classes correctly land in
separate groups), then derives each group's stem from `simpleName()` alone, so
both groups emit `createOpptakRecord(...)` / `createOpptakRecordList(...)`. The
existing contention machinery covers only the opposite case (one class reached
by several binding shapes, disambiguated with ordinal suffixes); there is no
cross-group check for two classes with the same simple name.

The same simple-name-keyed pattern recurs in the sibling helper families on the
same `*Fetchers` classes: the `@record`-POJO / bean input helpers
(`ServiceMethodCallEmitter.singularHelperName`/`pluralHelperName`, keyed on
`beanClass.simpleName()`) and the `@nodeId` `decode*Record` node-ID helpers.
The fix should make helper stems unique across all classes contributing to one
`*Fetchers` class, disambiguating same-simple-name classes by their
distinguishing schema package segment while keeping the single-class common
case byte-for-byte unchanged. Distinct from R512 (`@reference(key:)` FK-name
resolution across schemas), which is a catalog-lookup concern, not a
generated-helper-naming one. The reactor's multi-schema fixture already carries
colliding table names, so this is reproducible in-repo.
