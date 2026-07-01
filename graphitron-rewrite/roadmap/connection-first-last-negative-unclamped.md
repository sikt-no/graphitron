---
id: R415
title: "Connection first/last arguments are not clamped to >= 0, so a negative page size reaches SQL LIMIT and throws a redacted 500"
status: Backlog
bucket: bug
priority: 4
theme: pagination
depends-on: []
created: 2026-07-01
last-updated: 2026-07-01
---

# Connection first/last arguments are not clamped to >= 0, so a negative page size reaches SQL LIMIT and throws a redacted 500

## Problem

A negative `first` (or `last`) on a connection field flows unvalidated into the SQL `LIMIT`, so PostgreSQL throws `ERROR: LIMIT must not be negative`, which the framework redacts into an opaque correlation-id 500 instead of a client-facing validation error.

Reproduced against the utdanningsregisteret consumer schema:

```graphql
{ utdanningsspesifikasjoner(first: -5) { nodes { kode } } }
# -> { "errors": [{ "message": "An error occurred. Reference: <uuid>.", ... }] }
# server-side Postgres log: ERROR: LIMIT must not be negative
```

`first`/`last` are plain `Int`, so graphql-java accepts any 32-bit value; the only bound today is the mutual-exclusion guard between the two. A negative value is a client mistake and should surface as a client error (or be rejected at validation), not become an internal fault.

## Root cause

`ConnectionHelper.pageRequest` (emitted by `ConnectionHelperClassGenerator`) derives the page size with no lower bound (`:148`) and hands `pageSize + 1` straight to the fetcher as the `limit` (`:163`):

```java
if (first != null && last != null)                                  // :144 — only guard present
    throw new IllegalArgumentException("first and last must not both be specified");
boolean backward = last != null;
int pageSize = backward ? last : (first != null ? first : defaultPageSize);   // :148 — no >= 0 clamp
...
return new PageRequest(pageSize + 1, pageSize, backward, ...);      // :163 — negative limit flows to SQL
```

`first: -5` → `pageSize = -5` → `limit = -4` → `.limit(-4)` → `LIMIT must not be negative`. There is a mutual-exclusion guard (`:144`) but no non-negative guard, so the emitted runtime has no defence against the negative case.

## Fix sketch

Validate `first`/`last >= 0` in `pageRequest` and raise a client-facing error rather than letting the value reach the query. The cheapest forward-compatible shape mirrors the existing mutual-exclusion guard, but should route through the client-error channel R378 established (`GraphitronClientException` surfaced by `ErrorRouter.surfaceClientErrorOrRedact`) so the message reaches the client instead of being redacted:

```java
if (first != null && first < 0) throw new GraphitronClientException("first must not be negative");
if (last  != null && last  < 0) throw new GraphitronClientException("last must not be negative");
```

(The existing `IllegalArgumentException` for the first+last collision has the same redaction problem and could move to the same channel in the same pass.) Decide whether the bound belongs in `pageRequest` (runtime) or as a validate-time rejection; the runtime guard is the minimal fix since `first`/`last` values are only known per-request.

## Regression coverage

Add an execution-tier assertion that `first: -1` (and `last: -1`) on a connection field yields a client error with a real message, and that a genuine internal fault on the same field still redacts to a correlation id (pinning the surface arm to the client-error type, as R378's tests do).
