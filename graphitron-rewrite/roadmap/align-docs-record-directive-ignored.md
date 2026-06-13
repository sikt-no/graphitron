---
id: R301
title: "Align docs and javadoc with @record removal"
status: Backlog
bucket: cleanup
depends-on: []
created: 2026-06-13
last-updated: 2026-06-13
---

# Align docs and javadoc with @record removal

R276 made `@record` binding reflection-only: the directive is still parsed and registered (so existing schemas keep loading), but it no longer drives any binding. The backing class for an SDL type is now derived from the producing field's reflected return type (an `@service` method return, a `@table` resolution, a `@tableMethod` return, or a parent-accessor chain). When a reachable type still carries `@record`, `TypeBuilder.emitDirectiveIgnoredWarnings` warns the author to remove it (redundant, shadowed-by-`@table`, or disagreeing-with-reflection variants).

The documentation and javadoc still present `@record(record: {className: ...})` as the canonical, load-bearing way to bind a payload/result/input type to a Java class. The 2026-06-12 roadmap staleness audit names R276 as the highest-leverage source of doc/javadoc drift. Surfaces to align:

* `docs/manual/reference/directives/record.adoc` — currently teaches `@record` as a working binding directive; reframe as deprecated/ignored (remove from schema), explaining the reflection-derived binding.
* `docs/manual/reference/deprecations.adoc` and `docs/manual/reference/directives/index.adoc` — add `@record` to the deprecated/ignored listing (precedent: `@index`, `@notGenerated`).
* `graphitron/src/main/resources/.../directives.graphqls` — the `@record` description still says "Wrap type in the given Java record"; mark it ignored.
* Explanation pages (`classifier-mental-model.adoc`, `how-it-works.adoc`) that use "`@record`-bound type" as a category label.
* How-to guides (`result-types.adoc`, `source-row.adoc`, `handle-services.adoc`, `error-channel.adoc`, `computed-fields.adoc`, `condition-cascade.adoc`, `batch-lookups.adoc`, `external-code.adoc`, `split-vs-inline.adoc`, `migrating-from-legacy.adoc`) and several reference pages whose worked examples declare `@record(record: {className: ...})`.
* `graphitron-rewrite/docs/` internal adocs (`code-generation-triggers.adoc`, `dispatch-axes.adoc`, `typed-rejection.adoc`, `rewrite-design-principles.adoc`).
* Javadoc across `model/`, `catalog/`, and resolver classes that describe classifications as "`@record`-declared" rather than reflection-bound.

Note `changelog.md` and other roadmap files are a historical ledger and must not be rewritten. The generated `docs/manual/_generated/supported-directives.adoc` is produced from `directives.graphqls`; regenerate rather than hand-edit.
