---
id: R1082
title: "Manual says child @service needs @splitQuery; the classifier requires a Sources parameter instead"
status: Backlog
bucket: docs
depends-on: []
created: 2026-10-21
last-updated: 2026-10-21
---

# Manual says child @service needs @splitQuery; the classifier requires a Sources parameter instead

## Goal

The user manual tells authors that a `@service` on a non-root field (a child field, one below Query or Mutation) must carry `@splitQuery` (the directive that moves a field into its own batched DataLoader query) and that the build rejects it otherwise. The classifier does not work that way: every child `@service` is delivered through a DataLoader whatever its directives say, and what it actually requires is a `Sources` parameter (the `Set<Key>` or `List<Key>` of parent keys) paired with a `Map` or `List` return. When this lands, the manual states that contract, drops the `@splitQuery` requirement, and stops recommending a directive that on some parents earns a redundancy warning.

## Evidence

The manual passages that state the requirement:

* `docs/manual/reference/directives/service.adoc`, a bullet under "Constraints": "On non-root fields, `@service` requires `@splitQuery`".
* `docs/manual/how-to/handle-services.adoc`: the page description, the section heading "`@service` on a child field: the `@splitQuery` contract", the sentence "allowed *only* under `@splitQuery` ... the classifier rejects the schema at build time", the "Non-root `@service` requires `@splitQuery`" bullet in the summary list, and the closing see-also line.
* `docs/manual/reference/directives/splitQuery.adoc`: "Required when `@service` is used on a non-root field", plus the `filmsFromCity` example that pairs the two.
* `docs/manual/how-to/migrating-from-legacy.adoc`, the `@splitQuery @notGenerated` row of the mapping table: "with `@splitQuery` for non-root batching".

What the code does:

* The child arms (`FieldBuilder`, the `DIR_SERVICE` branches for record-backed and table-backed parents) call `ServiceDirectiveResolver.resolve` without reading `@splitQuery`. The resolver rejects a child service that has no `Sources` parameter ("... declares no Sources parameter, but a child @service field resolves through a DataLoader and needs one ...").
* On a record-backed parent with `@sourceRow`, `@splitQuery` beside `@service` raises the `SPLITQUERY_REDUNDANT_ON_RECORD_PARENT` lint ("@splitQuery is redundant on a record-backed parent field ... The directive will be ignored."), so an author who follows the manual gets a warning.
* The Sakila example's `Film.titleUppercase`, `titleLowercase`, `titleTitlecase` and `languageByService` are child `@service` fields with no `@splitQuery`, and they build and run.

## Notes for Spec

* Graphitron 9 did gate child services on `@splitQuery` (or on the field having arguments), which is probably where the text came from. The migration guide may want one line saying the directive is no longer needed there and is harmless on a table-backed parent.
* The same section of `handle-services.adoc` says "Argument binding is by name". The resolver also pairs leftover parameters by arity and by unique type (`ServiceCatalog.inferBindingsByType`). Fixing it in the same pass is cheap but optional.
* Decide whether `@splitQuery` beside a child `@service` on a table-backed parent should get the same redundancy lint as the record-backed case. If it should, that is a code change and makes this more than a docs item.
