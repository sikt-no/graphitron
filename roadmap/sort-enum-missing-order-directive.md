---
id: R453
title: "Reject sort-enum values missing @order/@index instead of silently skipping (empty ORDER BY breaks keyset pagination)"
status: Backlog
bucket: bug
priority: 2
theme: model-cleanup
depends-on: []
created: 2026-07-09
last-updated: 2026-07-09
---

# Reject sort-enum values missing @order/@index instead of silently skipping (empty ORDER BY breaks keyset pagination)

## Symptom

A sort enum with a value that carries **neither `@order` nor `@index`** builds cleanly, even though the documentation promises a per-value build failure (`docs/manual/how-to/sort-results.adoc:135`, `docs/manual/reference/directives/order.adoc:83`, `orderBy.adoc:48-49` all state that a missing value "fails the build with a per-value diagnostic"). At runtime the unannotated value is silently skipped. On a plain field the rows come back in default/PK order; **on a paginated connection the result is nondeterministic** — see below.

## Mechanics

- `OrderByResolver.java:312` `continue`s past any sort-enum value lacking `@order`/`@index`: no error, no `NamedOrder` produced.
- `FieldBuilder.java:1437-1438` classifies an enum as a sort enum via `anyMatch`, so an enum where only *some* values are annotated passes classification — the unannotated values then fall into the skip above.
- The two generated dispatch arms diverge. Single-arg dispatch (`TypeFetcherGenerator.java:5087`) has `default -> baseExpr` (silent default-order fallback). The list-arg dispatch switch (`:5127-5150`) has **no default arm**; when every supplied entry is an unannotated value the generated helper returns an `OrderByResult` with empty sort-field and column lists.
- `buildConnectionOrderingBlock` (`:4914-4924`) feeds those possibly-empty lists straight into the connection as both the ORDER BY and the keyset cursor columns. Empty means no ORDER BY and no cursor columns, so keyset pagination slices a nondeterministic set: rows duplicate or vanish across pages.

`GraphitronSchemaValidator`'s ordering checks (`:247-287`) only fire on `OrderBySpec.None`; an `Argument`-shaped spec passes untouched. No lint rule covers it; no test exercises a partially annotated sort enum.

## Fix direction (for Spec)

Add a build-time rejection for a sort-enum value that resolves to no ordering directive (the diagnostic the docs already promise), rather than silently skipping it. Decide at Spec whether the classifier (`FieldBuilder` `anyMatch` → all-match, or per-value verdict) or the validator is the right home, and whether the generated list-arg dispatch should additionally carry a non-empty guard so an empty ORDER BY can never reach a keyset connection. Align the emitted diagnostic wording with the three doc pages above.

## Relationship to R181

R181 (`validate-order-directive-args`) covers a *different* gap: an **empty** `@order` directive and `@order`+`@index` **coexistence** on one value. This item covers a value with **no ordering directive at all**, plus the divergent generated dispatch arms and the empty-ORDER-BY keyset-pagination consequence R181 does not touch. The two are siblings and a combined fix may be natural, but the shapes and the pagination-correctness blast radius are distinct.

Confirmed high severity by the architecture-trap audit (adversarially verified against code and docs; R181 checked and found not to cover this case).
