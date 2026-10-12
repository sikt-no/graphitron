---
id: R1018
title: "A child field routes on its parent row tenant, not on an optional argument slot"
status: Backlog
bucket: bug
priority: 6
theme: runtime-connection
depends-on: []
created: 2026-10-12
last-updated: 2026-10-12
---

# A child field routes on its parent row tenant, not on an optional argument slot

## Goal

In a database-per-tenant build, a child field below a tenant-scoped parent routes on the tenant its parent row names, even when the field also takes an argument that can carry a tenant. Today the argument wins: a nested `@splitQuery` whose filter argument has a tenant slot (a value that names the tenant column, such as an encoded id) classifies as `ArgumentBound`, the tenant binding arm that reads the tenant from the request arguments. When the client omits that optional argument, the fetcher throws `NoSuchElementException: The tenant binding value is absent`, although every parent key in the batch already carries the tenant column. When this lands the parent-row binding (or the inherited tenant) decides, and an argument slot is at most checked for agreement with it.

## Provenance

sis IT run against Oracle 18c on trunk `dd940c703` (`sis/GRAPHITRON10_STATUS.md`, section "Second IT run"). In the generated `StudieprogramFetchers.rowsKull`, every parent key is a `Row2<String, String>` whose `field1` is `INSTITUSJONSNR_EIER` (the tenant column), yet the tenant is computed as

```java
TenantConnections.divinedTenant(decodeTerminTenantSlot0OrThrow(
    TenantConnections.tenantSlot(env.getArgument("filter"), "terminer")))
```

so omitting `filter` or `filter.terminer` throws. Same in `OrganisasjonsenhetFetchers.rowsInstitutt` and `StudentVedLarestedFetchers.rowsEmneStudieretter`.

## Mechanism

`TenantBindingIndex.Fold.armOf` returns `ArgumentBound` as soon as `directBinding(site).divines()`, before `parentRowBinding(site)` and both `Inherited` checks are consulted. The comment above the parent-row check already states the intended order ("the parent row names the tenant that holds the child row, so it decides even when an ancestor handed a tenant down"); the argument rung simply sits ahead of it. The fix reorders for non-root sites, and the Spec decides what an agreeing argument slot does (ignored, or checked and rejected at runtime on disagreement).

## Related

- R1009 (In Review) changes the same fold for `@splitQuery` fields on nesting types; this item should land after it or rebase onto it.
- R996 (a tenant-scoped row referencing another tenant) is about which parent-row tenant wins, a neighbouring question in the same ladder.
- R1019 covers the root-level variant, where there is no parent row to fall back on.
