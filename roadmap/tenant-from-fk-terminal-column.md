---
id: R992
title: "A reference whose FK lands on the tenant column routes on the parent row value"
status: Backlog
bucket: architecture
priority: 4
theme: runtime-connection
depends-on: []
created: 2026-10-07
last-updated: 2026-10-07
---

# A reference whose FK lands on the tenant column routes on the parent row value

## Goal

A field on a global parent (a table without the configured `<tenantColumn>`) that reaches a tenant-scoped child (a table with it) through a foreign key whose child-side column *is* the tenant column binds its tenant from the parent row, instead of drawing `NoTenantBinding`. The join itself says which tenant holds the child row: the parent's paired column carries the value the child's tenant column must equal. So the tenant is known per parent row even when the parent arrived through `_entities` or `node` with no tenant in scope, and the "global registry row to its per-tenant settings row" shape becomes servable.

## The case that surfaced it

The sis Graphitron 10 port (`<tenantColumn>INSTITUSJONSNR_EIER</tenantColumn>`), validated against trunk at R989 and R988 Done. Of sis's 8 remaining errors, 6 are R990 and 1 is a field on hold; this is the last one:

```
'Organisasjon.eierInstitusjon' reaches tenant-scoped table 'eierinstitusjon' with no tenant binding
in scope: no argument or input field maps to tenant column 'INSTITUSJONSNR_EIER', and no ancestor
established a tenant context. ...
```

```graphql
type Organisasjon implements Node @table(name: "INSTITUSJON") @key(fields: "organisasjonskode") {
  id: ID! @shareable
  organisasjonskode: String! @field(name: "INSTITUSJONSNR") @shareable
}

extend type Organisasjon { eierInstitusjon: Eierinstitusjon @splitQuery }

type Eierinstitusjon implements Node @table @shareable {
  id: ID!
  schac: String @field(name: "SCHAC")
  pic: String @field(name: "PIC")
}
```

The jOOQ key `INSTITUSJON__HAR__EIERINSTITTUSJON__FK` pairs `INSTITUSJON.INSTITUSJONSNR` with `EIERINSTITUSJON.INSTITUSJONSNR_EIER` (`EIERINSTITUSJON__PK`), and the node key of `EIERINSTITUSJON` is `(INSTITUSJONSNR_EIER)`.

Why it is rejected today: `INSTITUSJON` has no `INSTITUSJONSNR_EIER`, so it is global (default source); `EIERINSTITUSJON` carries the tenant column. `Organisasjon` is reached from tenant-bound parents (`Student.larested`, `Emne.larested`), and those paths are fine. But it is also an entity with a resolvable `@key` and a `Node`, so `_entities` and `node` enter it with no tenant, and the every-path rule (each path into a field must establish a tenant before tenant-scoped data) rejects the child.

## Proposed rule (for Spec to settle)

When a field's reference path is an FK hop, or a chain of them, whose terminal columns include the tenant column, and the parent-side column paired with it is selectable on the parent, the field binds its tenant from the parent row's value at that column. This is a per-parent-row slot, like the per-row `localContext` stamping node dispatch already does. A batched (`@splitQuery`) child then partitions its keys per tenant, as `nodes` and `_entities` do. If the parent is also tenant-bound, the two must agree or the field is refused.

## Open points

1. Only FK hops (column pairs), or also condition hops? sis needs only the FK case.
2. Multi-hop paths where the tenant column sits on an intermediate table rather than the terminal one.
3. The batching shape: one statement per tenant across a DataLoader batch.
4. R975's authorization applies to the derived tenant as to any routed tenant: a parent row naming an institution outside the request's tenant set fails that element.

## Minimal pair over sakila

A global parent table with an FK whose child-side column is the configured tenant column, reachable through a resolvable `@key`. Today the child field draws `NoTenantBinding`; after the rule, it binds per parent row.

## Siblings

- **R505** (Backlog): the same per-row routing outcome (its proposed `ParentRowBound` arm of the `TenantBinding` axis), with different provenance. R505's parent is a *declared* tenant-index table that carries the tenant column; here the parent lacks it and the tenant is *derived* from the FK's terminal column, with no declaration. Neither depends on the other; Spec should decide whether they share the arm and whether one lands as the other's first slice.
- **R975** (Done): the request tenant set and its membership check, which the derived tenant must pass (open point 4).
- **R986** (Done): the cycle fold judged by the edges entering a cycle; the every-path rule this item relaxes for one edge shape.
- **R988** (Done): non-resolvable `@key` entries, which narrow the `_entities` entry points; `Organisasjon`'s key is resolvable, so it does not help here.
