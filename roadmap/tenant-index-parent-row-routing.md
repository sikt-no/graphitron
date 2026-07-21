---
id: R505
title: "Tenant-index tables: per-row tenant routing off an index parent"
status: Backlog
bucket: architecture
priority: 6
theme: runtime-connection
depends-on: []
created: 2026-07-20
last-updated: 2026-07-20
---

# Tenant-index tables: per-row tenant routing off an index parent

Extracted from R45 ([`tenant-routing-and-execution-input.md`](tenant-routing-and-execution-input.md)) on 2026-07-20 to keep its first iteration to a two-way table classification (tenant-scoped or global). This item adds the third scope: the **tenant-index table**, which carries the tenant column but is *not* partitioned; it lives on the default source and its rows point out into tenants. The canonical case is a student-to-organisation index: the query starts in the index on the default source, and each returned row names the tenant its children must be fetched from, on a different connection per row. Until this lands, that shape is served by R46's fan-out (query every tenant and union); the index makes it targeted (one query per tenant that actually holds data).

## Scope sketch (to be developed at Spec)

- **Classification.** The tenant-index scope joins R45's table classification. Carrying the tenant column cannot distinguish partitioned data from an index over tenants, so the index role is declared explicitly.
- **Declaration form.** A 2026-07-20 principles consult on R45's open question (Mojo `<tenantIndexTables>` list vs schema directive) leaned toward a directive on the `@table` type: index-ness is a data-model fact, per-table facts live in the SDL everywhere else (a pom list would be the first per-table enumeration in config, duplicating table names already asserted in `@table(name:)` with silent-drift risk), federation keeps each subgraph self-describing, and the existence/column-carrying rejection gets a real SDL coordinate. The directive is inert-but-valid when `<tenantColumn>` is not configured, the same way a `@table` type is inert until the generator is pointed at a catalog. The pom form's one surviving advantage is retirement safety (directives are costly to retire); weigh that at Spec.
- **`ParentRowBound`.** A new arm of R45's `TenantBinding` axis: a child field reaching tenant-scoped data off a tenant-index parent classifies `ParentRowBound`; each parent row names its own tenant, so the batch spans tenants by construction. Carries a column read off the parent row (the `SourceKey.Reader` family). A child under a tenant-scoped parent stays `Inherited`.
- **Execution.** The tenant read off each parent row joins the loader name, the same partition mechanism R45's per-key arms use: each per-tenant loader is tenant-homogeneous and its captured environment routes that tenant's source.
- **Known limitation to state in the Spec.** The index role is opt-out (column-carrying tables default to tenant-scoped), so a forgotten declaration silently misclassifies an index as tenant-scoped and mis-routes it. No enforcer is possible, that is the premise of the explicit declaration; this is a review-only invariant, and the directive form at least makes the omission visible at the table's definition site.

## Worked example: student results across universities

`STUDENT_ORGANISASJON` (student id against organisations holding the student's data) is a declared tenant-index table on the default source; `RESULTAT` is tenant-scoped.

```graphql
query {
  student(id: "...") {
    organisasjoner {          # index rows, default source; Untenanted
      eierOrganisasjon
      resultater { karakter } # ParentRowBound: routed per row's eierOrganisasjon
    }
  }
}
```

`organisasjoner` runs one query on the default source. `resultater` partitions its loader by each row's `eierOrganisasjon` and acquires per partition: one query and one read-only transaction per university that holds data for this student. Fields below `resultater` classify `Inherited`.

## Siblings

- **Depends on R45** ([`tenant-routing-and-execution-input.md`](tenant-routing-and-execution-input.md)): the tenant-column declaration, the two-way classification this item widens, the `TenantBinding` axis this item's arm joins, and the loader-name partition mechanism.
- **R46** ([`service-multi-tenant-fanout.md`](service-multi-tenant-fanout.md)): the no-index fallback, fan out across every tenant and union. An index narrows the fan-out to the tenants that hold data; schemas without one keep using R46's shape.
