---
id: R181
title: "Validate @order/@defaultOrder require exactly one of index/fields/primaryKey"
status: Backlog
bucket: validation
priority: 5
theme: model-cleanup
depends-on: []
created: 2026-05-19
last-updated: 2026-05-19
---

# Validate @order/@defaultOrder require exactly one of index/fields/primaryKey

A bare `@order` directive on an enum value (or `@defaultOrder` on a field) — i.e. with none of `index`, `fields`, or `primaryKey` set — crashes the schema build with `NullPointerException` at `OrderByResolver.resolveOrderEntries` line 240 (`List.of(fieldsArg.getValue())` when the `fields` arg value is `null`). graphql-java's `getAppliedDirective` returns a wrapper object for every declared argument regardless of whether the user supplied it, so the `fieldsArg != null` check guards the *declaration*, not the *value*. The directive's SDL doc says "Exactly one of index, fields, or primaryKey must be set", but nothing enforces it; instead, the malformed input crashes deep in the resolver. Real-world repro (paraphrased from a user report):

```graphql
enum OrgOrder { NAVN @order @index(name: "organisasjon_navn_original_ix") @field(name: "NAVN_ORIGINAL") }
```

The user intended `@order(index: "organisasjon_navn_original_ix")` (the `@index` enum-value directive is the deprecated alias). The bare `@order` should have surfaced as a clear validation error pointing at the malformed directive, not an NPE.

Scope: surface this as a `ValidationError` (or `Rejection.invalidSchema`) on the enum value carrying `@order` (and analogously on fields carrying `@defaultOrder`) when zero or more than one of `index` / `fields` / `primaryKey: true` is set. Harden `resolveOrderEntries` against the null-value case so a future regression of the validator can't reintroduce the NPE.
