---
id: R1091
title: "A list field on a payload with errors is null when the call fails, and cannot be declared non-null to answer []"
status: Backlog
bucket: enhancement
priority: 3
theme: error-channel
depends-on: []
created: 2026-10-22
last-updated: 2026-10-22
---

# A list field on a payload with errors is null when the call fails, and cannot be declared non-null to answer []

## Goal

A schema author should be able to say that a list-valued data field on a payload with an errors field answers `[]`, not `null`, when the call fails into the errors list. Graphitron 9 answered `[]` in many of these cases. Today Graphitron 10 has two obstacles. Such a field is `null` on the error arm. Declaring it non-null (`[X!]!`) is a build error (`ErrorChannelWalkerError.NonNullableSuccessProjectionField`), because a non-null field that resolves `null` would bubble up and drop the sibling `errors` field. After this item a non-null list data field answers `[]` on the error arm, so the build accepts it. A nullable one keeps today's `null`. The SDL then states the contract and the client can read it from introspection. Every producer on the outcome transport (root `@service`, DML `@mutation` and `@routine` carriers) behaves the same.

## What Graphitron 9 consumers depend on

Reported by sis, whose consumer-facing developers count `null` in place of `[]` as a breaking change. Graphitron 9 was not uniform, and that shapes the plan.

- **Answered `[]`.** A list data field that was `@splitQuery` in 9 answered `[]` on every error, database constraint errors included. The error-arm payload carried no keys, and 9's split-query fetcher answered `[]` for them without a query. In sis that is 28 fields, all in the experimental contract (`AktiverStudieprogramPayload.studieprogram`, `EndreStudieprogramOkonomiPayload.studieprogram`, …).
- **Declared non-null.** Four sis payload fields were `[X!]!` in 9: `FjernStatsborgerskapPayload.ids` and `.semesterregistreringer` on `RegistrerSemesteravgiftBetaltForStudenterPayload`, `RegistrerFritakFraSemesteravgiftPayload` and `SemesterregistrerStudenterPayload`. Graphitron 10 forced them to `[X!]`, which client code generators see as a type change.
- **Answered `null`.** A list data field that was not `@splitQuery` already answered `null` in 9. That covers every one of sis's stable (32) and beta (19) payload list fields, plus 79 experimental ones (counts from sis `origin/main`).

So "always `[]`" would itself break 9 behaviour for the stable and beta contracts. The choice has to be per field, and nullability is the declaration the schema already has.

## Sketch (fill in at Spec)

- **Relax the rule for lists.** `nonNullableSuccessProjection` in `FieldBuilder` rejects only a non-null field that is not a list. A scalar or object field stays rejected, since there is no empty value to give it.
- **Answer `[]` on the error arm.** Where the outcome's `ErrorList` arm resolves the payload's data fields (the generated payload fetchers reading `Outcome`), a non-null list field answers an empty list. This happens without running the field's query or DataLoader: nothing was produced. A nested non-null list inside a nullable payload field is out of scope.
- **Alternative to weigh.** An explicit opt-in (a directive argument or a plugin setting) that answers `[]` while the SDL stays nullable. It keeps the SDL as it is, but the contract becomes invisible to clients. Nullability is preferred unless the Spec finds a consumer that needs `[]` behind a nullable type.
- **Docs.** `error-channel.adoc` and `mutation.adoc` state the rule: nullable list `null`, non-null list `[]`, other data fields must be nullable.
- **Tests.** Execution tier: a payload with a `[Film!]!` data field returns `[]` beside a populated `errors` on a mapped failure, for a DML carrier and a `@service` carrier. Pipeline tier: the rule accepts `[X!]!` and still rejects `X!`.
