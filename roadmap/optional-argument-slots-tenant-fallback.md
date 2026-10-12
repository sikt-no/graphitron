---
id: R1019
title: "A root field whose tenant slots are all optional does not fail when every slot is absent"
status: Backlog
bucket: bug
priority: 4
theme: runtime-connection
depends-on: []
created: 2026-10-12
last-updated: 2026-10-12
---

# A root field whose tenant slots are all optional does not fail when every slot is absent

## Goal

In a database-per-tenant build, a root field whose tenant comes only from optional argument slots (values inside nullable arguments that name the tenant column) has a defined outcome when the client supplies none of them. Today such a field classifies as `ArgumentBound` and the generated fetcher throws `NoSuchElementException: The tenant binding value is absent` at runtime, with no fallback to the request's default tenant and no build-time warning. When this lands the build either requires at least one slot to be non-null in the schema, or the field falls back to a defined source (the request default tenant, or a fan-out) when every slot is absent; which one is a Spec decision.

## Provenance

Latent in the sis IT run against Oracle 18c on trunk `dd940c703` (`sis/GRAPHITRON10_STATUS.md`, section "Second IT run"); it did not fire in the ITs. `StudieprogramFetchers` around line 502 in the generated sources: a root field with two optional slots, `filter.terminer` and `filter.campuser`, and nothing behind them.

## Related

R1018 is the child-field variant, where the parent row supplies the tenant and the fix is ordering. Here there is no parent row, so the fix is a fallback or a build-time requirement. R517 (a tenant-column list argument narrows the fan-out domain) is the neighbouring design for arguments that select tenants deliberately.
