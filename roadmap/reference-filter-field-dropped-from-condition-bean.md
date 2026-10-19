---
id: R1071
title: "A filter input field with @field and @reference is dropped from the bean a whole-input @condition receives"
status: Backlog
bucket: bug
priority: 1
theme: codegen-correctness
depends-on: []
created: 2026-10-19
last-updated: 2026-10-19
---

# A filter input field with @field and @reference is dropped from the bean a whole-input @condition receives

## Goal

A filter input field declared with `@field(name:)` and `@reference(path:)` reaches the bean a
whole-input `@condition` receives, or the build rejects the declaration naming the field. Today the
field is silently left out, so the condition sees `null` and the filter matches every row.

## Evidence

Surfaced by the sis v9 against v10 differential test on Oracle (2026-10-19).
`extend input QueryStudenterFilterInput { privatEpost: String @field(name: "EMAILADRESSE_PRIVAT") @reference(path: [{table: "PERSON"}]) }`
is read by a whole-input condition, `StudentFilterConditions.privatEpostCondition(student, filter.getPrivatEpost())`,
through the generated bean builder `QueryConditions.createQueryStudenterFilterRecord`. The builder
never reads `privatEpost` into `QueryStudenterFilterRecord`, so the condition always sees `null`.
`studenter(filter: {eierOrganisasjonskode: "1234", privatEpost: "<an address>"}) { totalCount }`
returns 2044 (every student) where v9 returned 1, and a non-existent address also returns 2044
(v9: 0). No warning at build time. Graphitron 9's declaration was the `@reference` without
`@field`. A scan of every generated bean builder in sis against introspection found this to be the
only dropped input field.

## Questions for the plan

Why the bean classification leaves a `@reference` field out (presumably it is classified as an
implicit filter predicate, which the condition's override then suppresses, leaving the value with
no consumer at all). Then: either the bean carries every input field whatever its other directives,
or a field whose value no emitted code reads is a build error. The second rule is worth having
regardless, since it turns any future silent drop into a rejection.
