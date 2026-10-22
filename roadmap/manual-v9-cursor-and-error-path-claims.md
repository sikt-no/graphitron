---
id: R1085
title: "The user manual misstates the v9 cursor format and a matched error's path"
status: Backlog
bucket: cleanup
priority: 5
theme: docs
depends-on: []
created: 2026-10-22
last-updated: 2026-10-22
---

# The user manual misstates the v9 cursor format and a matched error's path

## Goal

Two statements in the user manual contradict what the generated code does. A consumer migrating from Graphitron 9 who trusts them keeps cursors that will be rejected, and writes clients that expect the wrong `path` on a matched error. After this item both pages describe the shipped behaviour. This is a docs-only change: no generator or runtime code changes.

## The two errors

Reported by sis during its Graphitron 9 to 10 port.

**1. Cursor format.** `docs/manual/how-to/migrating-from-legacy.adoc`, in the `@orderBy` / `@order` / `@defaultOrder` bullet, says "The cursor wire format is the same base64-encoded NUL-separated tuple." It is not the same. Graphitron 9 (`QueryHelper.getOrderByToken` and `encodeToken` at `v9.3.0`) encodes a cursor as URL-safe Base64, without padding, of a JSON array of the key values. Its multi-table interface variant wraps that in a JSON object with `typeName` and `fields`. Graphitron 10 (`ConnectionHelperClassGenerator`'s `encodeCursor` / `decodeCursor`) encodes standard Base64, with padding, of the values joined by NUL, with SQL `NULL` as SOH. The key columns and their order are the same, but a cursor issued by 9 fails to decode in 10. sis re-approved seven approval tests for this and lists it as a breaking change. The fix states the difference and its consequence: clients must drop stored cursors and restart pagination across the upgrade.

**2. Path of a matched error.** `docs/manual/how-to/error-channel.adoc`, in the `path:` section, says an exception thrown by `createFilm` and matched into `errors[0]` "produces `path: ["createFilm"]` (or `["createFilm", "<some-subfield>"]` if the throw happened during nested resolution)". The generated `path` fetcher returns `env.getExecutionStepInfo().getPath()` of the `path` field's own resolution. That is `["createFilm", "errors", "0", "path"]` whatever threw, which is what `GraphQLQueryTest` pins (`rentFilmPayload`, `filmLookup` and `submitFilmReview`, each `[field, "errors", "0", "path"]`). The fix corrects the example, drops the nested-resolution parenthetical, and keeps the existing advice to carry the input field in the exception, `message:` or a custom field. The neighbouring sentence on `VALIDATION` sources, whose `path` is read from the `GraphQLError` and does name the input field, is correct and stays.

## Verification

A docs-only diff owes the full verification build under the project rules, since it is outside `roadmap/`. The `docs` module's `check-adoc-xrefs` is the step that can fail on it.
