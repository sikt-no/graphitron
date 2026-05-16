---
id: R169
title: "Sakila execute-tier fixture for the @service domain-object payload-assembly arm"
status: Backlog
bucket: testing
priority: 4
theme: mutations-errors
depends-on: []
created: 2026-05-16
last-updated: 2026-05-16
---

# Sakila execute-tier fixture for the @service domain-object payload-assembly arm

Split out from R12 (`error-handling-parity`)'s "Test fixture updates for
source-direct dispatch" bullet. R12 introduced the `ResultAssembly.Assembly`
arm: an `@service` field whose method returns a domain object that the
carrier classifier reflects against the payload class's canonical
constructor and binds to a positional slot (sibling shape of `NoAssembly`,
where the method returns the SDL payload type directly). The arm has
pipeline-tier and unit-tier coverage today (`TypeFetcherGeneratorTest`,
`FetcherPipelineTest`); execute-tier coverage in `graphitron-sakila-example`
is missing because every existing `@service` fixture (`FilmReviewService`,
`FilmLookupService`, etc.) returns the SDL payload type, exercising
`NoAssembly` only.

Scope: one new Sakila `@service` fixture whose method returns a domain
object (not the SDL payload), with happy + error-channel paths driven from
`GraphQLQueryTest`. The shape mirrors R12's existing FK-violation
end-to-end test for `MutationDmlRecordField` but exercises the `@service`
payload-construction arm. Not blocked.
