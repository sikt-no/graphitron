---
id: R996
title: "A tenant-scoped row referencing another tenant routes on the referenced tenant"
status: Backlog
bucket: architecture
priority: 6
theme: runtime-connection
depends-on: []
created: 2026-10-07
last-updated: 2026-10-07
---

# A tenant-scoped row referencing another tenant routes on the referenced tenant

## Goal

A tenant-scoped row whose foreign key lands on the tenant column of another tenant-scoped table, from a column that is *not* the parent's own tenant column, fetches the referenced row from the tenant that row names, rather than from the parent's tenant. Today such a child classifies `Inherited` and reads from the parent's database. In a database-per-tenant deployment every tenant-scoped row in tenant T's database carries T, so that read can find the child only when the reference happens to name T: a reference to another tenant silently resolves to `null` or an empty list. R992 routes the same shape per parent row when the parent is global and scoped this case out; its argument (the join's terminal column names which tenant holds the child row) applies here unchanged, so the likely shape is widening R992's rule past its "parent table is global" condition, with the precedence over `Inherited` it already settles. Whether any consumer schema has this shape is the first thing to establish at Spec.

## Provenance

Scoped out of R992 (its condition 3); its principles consult asked that the case be recorded as known-wrong under `Inherited` rather than left as an open question.
