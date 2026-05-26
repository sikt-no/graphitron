---
id: R242
title: "DML payload positional input/output alignment"
status: Backlog
depends-on: []
created: 2026-05-26
last-updated: 2026-05-26
---

# DML payload positional input/output alignment

Payload-returning bulk DML carriers (DELETE / INSERT / UPDATE / UPSERT) must
emit data-field lists that are positionally aligned with the mutation's input
list: input index `i` maps to output index `i`, and positions where no row
was produced must be representable as `null` (DELETE: the row didn't exist /
wasn't deleted; INSERT/UPDATE/UPSERT: the corresponding "no result for this
input" case, exact taxonomy to be settled in Spec). Today the DELETE `Id`-arm
emitter (`FetcherEmitter.buildSingleRecordIdFromReturningFetcherValue`, lines
561-610) iterates the `DELETE … RETURNING` `Result<Record>` directly and
appends one entry per RETURNING row, so a miss simply shortens the output
list — there is no positional correspondence with the input. The classifier
compounds the mismatch by rejecting the `[ID]` (list-of-nullable) wrapper
that this contract requires and admitting only `[ID!]` / `[ID!]!`
(`BuildContext.java:680-699`); the diagnostic that pins the wrong contract
("every element of a successful DELETE response is the encoded PK of an
actually-deleted row, so the slot cannot be null") is the surface symptom
that originally surfaced this bug. The same gap exists on the DELETE
`Table`-arm synthesized-Record path and across all four DML verbs.

Out of scope for Backlog (to be tightened during Spec):

- Exact identity-matching strategy: PK-keyed `LinkedHashMap` over the input
  (R141 pattern) vs. carrying `idx` through SQL via a `VALUES (idx, …) JOIN`
  derived table (federation `_entities` dispatch pattern). The Spec phase
  decides which arm uses which.
- Wrapper-shape rules: `[ID]` admitted on the `Id`-arm and `[ID!]` rejected
  (since misses must be representable). Whether singleton `ID` / `ID!`
  carriers also change semantics, and what `[Type]` vs `[Type!]` means on
  the `Table` arm.
- Composition with `@service`-backed producers (R158): does positional
  alignment apply to the typed-record producer return, or stay on the DML
  path? Likely both, but the verification mechanism differs.
- Dialect capability gating: positional alignment relies on `RETURNING`
  support that some dialects lack.
- Composition with the `errors:` channel (R12) for per-row failure detail
  beyond "this position is null."

Cross-references: R156 (introduced the DELETE payload-returning carrier and
the PK-echo-of-actually-deleted-rows semantics that this revises), R141
(PK-keyed-map walk for input-order preservation, the closest existing
pattern), R12 (errors channel composition), R158 (`@service`-backed
single-record carrier producer admission).
