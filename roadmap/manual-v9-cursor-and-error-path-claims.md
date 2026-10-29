---
id: R1085
title: "The user manual misstates a matched error's path"
status: Backlog
bucket: cleanup
priority: 5
theme: docs
depends-on: []
created: 2026-10-22
last-updated: 2026-10-29
---

# The user manual misstates a matched error's path

## Goal

A statement in the user manual contradicts what the generated code does: a consumer who trusts it writes clients that expect the wrong `path` on a matched error. After this item the page describes the shipped behaviour. This is a docs-only change: no generator or runtime code changes.

## The error

Reported by sis during its Graphitron 9 to 10 port. A second claim the report named, the `migrating-from-legacy.adoc` cursor-format sentence, belongs to R1088, which changes the cursor format and rewrites that sentence.

**Path of a matched error.** `docs/manual/how-to/error-channel.adoc`, in the `path:` section, says an exception thrown by `createFilm` and matched into `errors[0]` "produces `path: ["createFilm"]` (or `["createFilm", "<some-subfield>"]` if the throw happened during nested resolution)". The generated `path` fetcher returns `env.getExecutionStepInfo().getPath()` of the `path` field's own resolution. That is `["createFilm", "errors", "0", "path"]` whatever threw, which is what `GraphQLQueryTest` pins (`rentFilmPayload`, `filmLookup` and `submitFilmReview`, each `[field, "errors", "0", "path"]`). The fix corrects the example, drops the nested-resolution parenthetical, and keeps the existing advice to carry the input field in the exception, `message:` or a custom field. The neighbouring sentence on `VALIDATION` sources, whose `path` is read from the `GraphQLError` and does name the input field, is correct and stays.

## Verification

A docs-only diff owes the full verification build under the project rules, since it is outside `roadmap/`. The `docs` module's `check-adoc-xrefs` is the step that can fail on it.
