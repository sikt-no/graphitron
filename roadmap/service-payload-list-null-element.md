---
id: R1064
title: "A service payload list with a null element throws while extracting keys"
status: Backlog
bucket: bug
priority: 2
theme: service
depends-on: []
created: 2026-10-19
last-updated: 2026-10-19
---

# A service payload list with a null element throws while extracting keys

## Goal

A `@service` mutation whose service returns a list containing a `null` element resolves the
payload's list field with a `null` in that position (or rejects the element with a clear error),
instead of throwing a `NullPointerException` that hides rows the mutation already committed.

## Evidence

Surfaced by the sis v9 against v10 differential test (2026-10-19), on `endreTimeplaner`, a stable
mutation used in production. The service returns `null` for an id it does not know. The generated
`EndreTimeplanerPayloadFetchers.timeplaner` builds the batch keys with

```java
for (Object element : (Iterable<?>) success.value()) {
    Row9<...> key = DSL.row(((Record) element).get(Tables.UNDERVISNINGSTIMEPLAN.INSTITUSJONSNR_EIER), ...);
    keys.add(key);
}
```

and dereferences the `null` element. The client gets `timeplaner: null` plus a redacted error,
although the known rows were written. Graphitron 9 returned the known rows.

## Direction

The positional contract root lookups already follow (one slot per input key, `null` where the key
matched nothing) suggests the same reading here: a `null` element maps to a `null` key and a `null`
result element, keeping positions aligned for the loader. The alternative, rejecting the element
with a message naming the field, is defensible but loses the committed rows from the response. The
plan picks one and states which list nullability (`[T]` against `[T!]`) each case needs.

Related: `list-valued-bean-member-unchecked-cast.md` notes null elements surviving on the input
side; this is the output side.
