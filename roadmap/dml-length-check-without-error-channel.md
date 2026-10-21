---
id: R1084
title: "A DML mutation without an error channel sends an over-long value to the database"
status: Backlog
bucket: architecture
priority: 4
theme: mutation-write
depends-on: []
created: 2026-10-21
last-updated: 2026-10-21
---

# A DML mutation without an error channel sends an over-long value to the database

## Goal

A `@mutation(typeName: INSERT | UPDATE | UPSERT)` field whose payload has no errors field, or that returns its table type directly, should reject a string too long for the column it writes before the statement runs. It should answer with a top-level GraphQL error that has classification `ValidationError`, a path to the offending input field and the limit in the message, the way Graphitron 9 did. Today the value reaches the database, the statement fails (ORA-12899 on Oracle, `22001` on PostgreSQL), nothing is written, and the client gets the redacted "An error occurred. Reference: ...". There is no build warning either. The user manual's column-length section also reads as if the build always warns.

An *error channel* is a payload's errors field: a union of `@error` types whose `handlers` say which failures land in it. R1025 added the length check for DML mutations that have one, gated on a `{handler: VALIDATION}` member. It kept mutations without one out of scope. This item extends the check to them.

## Why this does not reopen R1025's gating decision

R1025 considered "always check, and fall back to a top-level error without `VALIDATION`" and rejected it. The reason was specific to fields with a union. A consumer may already catch the overflow there with `{handler: DATABASE, code: "12899"}` or `{handler: DATABASE, sqlState: "22001"}`, and an always-on check would move that error out of the union into a top-level error. A field without an errors field has no union to move anything out of. Today's outcome is already a top-level error, just a redacted one. After this item it is a readable top-level error in the same place, with nothing written in either case. No consumer's existing routing changes.

The seam is `LengthCheck.of`, whose `errorChannel.isEmpty()` arm returns `None` today. Fields that have an errors field but no `VALIDATION` member (`Unrouted`) keep R1025's behaviour and its `dml-write-length-unrouted` warning.

## Why the consumer cannot add an errors field instead

From sis. A union member only reaches a client that selects `errors`. sis's production caller of `endreLanetakerIdForStudenter` (`uit_adgangskort`) selects only `studenter`. With an errors field added, an over-long value would come back as `studenter: null` and nothing else. Today that caller at least sees an error, and in Graphitron 9 it saw the validation message.

Repro (sis difftest finding M4), with `STUDENT.BIBSYSLANETAKERID VARCHAR2(20)`:

```graphql
mutation {
  endreLanetakerIdForStudenter(input: [{id: "<an existing student id>", lanetakerId: "123456789012345678901"}]) {
    studenter { id }
  }
}
```

Graphitron 9 answers "size must be between 0 and 20". Graphitron 10 answers with the redacted error and logs ORA-12899. Other affected sis mutations, all experimental: `registrerSoknaderOmEkstraVurderingsforsok`, `registrerVedtakOmEkstraVurderingsforsok`, `behandleEksamenstilpasningssoknader`, `endreUndervisningstilpasninger`, `endreStudieretningForProgramStudieretter`, `endreBetalingsfrister`, `endreStudietilpasningForStudentVedLarested`. sis has accepted the difference for now and listed it in its breaking-changes table, so this does not block their upgrade.

## Scope to settle at Spec

- **Direct-return DML** (`MutationField.DmlTableField`). R1025 left it out only because it had no union to route into, which is the gap this item fills. It is in scope unless Spec finds a reason to exclude it. Confirm with sis which of the eight mutations take that shape.
- **How the error leaves the fetcher.** `ChannelEarlyReturnEmitter.emit` returns `Outcome.ErrorList` into a union, so it does not apply here. Two candidates: return a `DataFetcherResult` that carries the violations as errors, which bypasses the redacting exception handler, or throw something that handler passes through unredacted, as it does for `GraphitronClientException`. Pick one at Spec.
- **Classification.** The emitted `ConstraintViolations.validationError` builder calls `GraphqlErrorBuilder.newError(env)` with no `errorType`, so the classification defaults to `DataFetchingException`. That is invisible inside a union but visible at top level, and sis asked for `ValidationError`. The wire shape should stay in one builder (R1025's rule), so this is probably a parameter on that builder rather than a second call site. Keep the path shape: the argument path appended to the execution path is the shape Graphitron 9 produced.
- **The build warning sis asked for as a stopgap.** R1025 judged that a warning on every field without an errors field would fire on nearly every payload over a `varchar` column. Once the check always runs on these fields, no warning is needed. If this item stalls, the stopgap is the documentation fix below, not a new warning.
- **Documentation.** `docs/manual/how-to/error-channel.adoc#column-length-check` says "Without a `VALIDATION` member the value reaches the database unchecked, and the build warns", which holds only when the payload has an errors field. `docs/manual/reference/directives/mutation.adoc` points at the same section. Both should describe the behaviour for fields without an errors field.

## Not in this item

- Fields that have an errors field but no `VALIDATION` member. R1025's gating and warning stand.
- Numeric precision, byte-length semantics and `@service` writes, for the reasons R1025 gave.
