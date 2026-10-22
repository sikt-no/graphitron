---
id: R1088
title: "A cursor issued by Graphitron 9 is rejected, so a client that stores cursors loses its place at the upgrade"
status: Backlog
bucket: enhancement
priority: 3
theme: pagination
depends-on: []
created: 2026-10-22
last-updated: 2026-10-22
---

# A cursor issued by Graphitron 9 is rejected, so a client that stores cursors loses its place at the upgrade

## Goal

A cursor that Graphitron 9 issued should still work as `after:` or `before:` after the consumer upgrades to Graphitron 10, on a connection whose order columns are unchanged. Today `decodeCursor` rejects it with "cursor is not valid", so a client that stores its last cursor as a checkpoint either replays the feed from the start or loses its place. After this item the generated decoder accepts both formats, and Graphitron 10 keeps emitting its own. The manual's "Cursor encoding is part of the public contract" section (`connections.adoc`) then holds across the major version too. The item also decides whether Graphitron 10 should emit URL-safe Base64, as Graphitron 9 did.

## Why a restart is not acceptable

Reported by sis. Its event feeds (`studenthendelser`, `personProfilhendelser` in the stable contract; `emnehendelser`, `studieprogramhendelser`, `evukurshendelser` in beta) are paged by consumers that poll with `after: <last cursor>` and persist that cursor between runs, often for days. sis had accepted the format change on the assumption that clients restart pagination. That holds for a UI paging through a list, but not for a feed checkpoint. `studenthendelser` orders on `INSTITUSJONSNR_EIER, ROW_SCN, ID`, all numeric, and the column list is the same in both versions.

## The two formats

- **Graphitron 9** (`graphitron-common` `QueryHelper.getOrderByToken` / `getOrderByValues`): `jsonArray(<order columns>)` built in SQL, then URL-safe Base64 without padding. To decode, it parsed the JSON array, loaded it into a record of the order fields (`ctx.newRecord(fields).fromArray(...)`) and bound each value with the field's `DataType`. A multi-table interface variant wrapped the token in a JSON object with `typeName` and `fields`.
- **Graphitron 10** (`ConnectionHelperClassGenerator.encodeCursor` / `decodeCursor`): each value's `toString()`, joined by U+0000 with U+0001 for SQL `NULL`, then standard Base64 with padding. Each token is converted with `DataType.convert`.

The difference is a by-product of the rewrite (the cursor is now built in Java from the fetched record, with no JSON dependency), not a deliberate wire change. R1085 records that the migration guide claims the formats are the same.

## Sketch (fill in at Spec)

- **Recognise a 9 cursor by its content, not by a decode failure.** `java.util.Base64.getDecoder()` accepts unpadded input, so a 9 cursor with no `-` or `_` decodes without error and fails only on arity. Decode with a decoder that accepts both alphabets, then treat the text as a 9 cursor when it parses as a JSON array whose length is the order's column count. A 10 cursor of two or more columns contains U+0000 and is never valid JSON. A one-column 10 cursor whose text value happens to be a JSON array of one element is the only overlap. The Spec decides whether to prefer the 10 reading there.
- **Convert 9 values the way 9 did.** Numbers arrive as JSON numbers. Dates and timestamps arrive in the database's JSON rendering (Oracle gives `2024-01-01T00:00:00`), which `toString()` of a `LocalDateTime` does not reproduce. Test both against `DataType.convert`.
- **The multi-table interface variant**: decide whether it is in scope. sis's event types are single-table interfaces with a discriminator.
- **Emitting URL-safe Base64**: `+`, `/` and `=` force clients to percent-encode a cursor they put in a URL, which 9 cursors never needed. Switching changes every 10 cursor once more, so it is cheapest before Graphitron 10's first consumers ship. The decoder would accept both alphabets either way.
- **Lifetime**: whether legacy decoding is permanent or opt-in behind a plugin setting, and when it can be removed.
