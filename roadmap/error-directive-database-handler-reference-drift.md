---
id: R690
title: "Correct the @error reference page's DATABASE handler rows: SQLException, not DataAccessException"
status: Backlog
bucket: docs
priority: 5
theme: error-channel
depends-on: []
created: 2026-08-17
last-updated: 2026-08-17
---

# Correct the @error reference page's DATABASE handler rows: SQLException, not DataAccessException

`docs/manual/reference/directives/error.adoc`'s `ErrorHandler` field table describes a `DATABASE`
handler that the rewrite no longer implements, on three counts. It says `DATABASE` "matches
`org.jooq.exception.DataAccessException` (or a configured subclass)", but `TypeBuilder`'s DATABASE
arm lifts a no-discriminator entry to `ExceptionHandler("java.sql.SQLException")` and both
`SqlStateHandler` and `VendorCodeHandler` match any `java.sql.SQLException` in the cause chain; no
`@error` code path mentions `DataAccessException`, and nothing makes the base class configurable.
It says `className` "defaults to `org.jooq.exception.DataAccessException` for `DATABASE`", but the
DATABASE arm rejects `className` outright ("DATABASE matches any SQLException; use `{handler:
GENERIC, className: "..."}` for class-narrowed matching"), so there is no default to document. And
it says `className` is "ignored for `VALIDATION`", where the VALIDATION arm's `disallowed` list also
rejects it. Two of the three wrongly promise an author that a build will succeed.

In practice a jOOQ `DataAccessException` still routes, because jOOQ wraps the driver's
`SQLException` as its cause and the matcher walks the cause chain, so the drift reads as harmless
until an author writes the documented `className:` and the build fails. Surfaced while reviewing
R686, which edits the `description` row of this same table and deliberately does not widen into the
rest of it.
