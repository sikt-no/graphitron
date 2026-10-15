---
id: R1038
title: "A client-supplied tenant key routes by the tenant column database value, not its converted Java form"
status: Backlog
bucket: bug
priority: 3
theme: runtime-connection
depends-on: []
created: 2026-10-15
last-updated: 2026-10-15
---

# A client-supplied tenant key routes by the tenant column database value, not its converted Java form

## Goal

In a database-per-tenant build, two tenant values that bind to the same database value in the tenant column name the same tenant. Today graphitron types the tenant key as the tenant column's Java type, read off the jOOQ catalog (`GraphQLRewriteGenerator` passes it to `ConnectionRuntimeClassGenerator`), and compares keys with that type's `equals`. When a jOOQ converter maps the column to a Java type with more spellings than the database type, a value from a client argument can bind correctly in the SQL and still be refused by the router. sis's `INSTITUSJONSNR_EIER` is `INTEGER` in Oracle and `String` in Java through `IntegerToStringConverter`. A client sending `eierOrganisasjonskode: "0194"` gets "Tenant '0194' is not permitted for this request" from the generated `entryFor` check (`tenants.contains(key.get())`), though the converter binds `"0194"` as `194` and the request's tenant set holds `"194"`. After this lands, a key that comes from a client value is canonicalised before routing, so it matches the keys read off rows and the ones the consumer registers.

## Evidence

Reported from sis on 2026-10-15 (fs-plattform branch `sis/upgrade-graphitron`). The values come from arguments and input fields bound to the tenant column, from R989's `@tenant` scalar inputs, and from filter slots. Keys read off rows never have leading zeros, because the database returns `194`. sis already normalises its own side: `GraphqlEngines` builds the runtime source map and `RequestTenants` with `replaceFirst("^0+", "")`. In Graphitron 9, sis's own `QueryInspector.identifiserInstitusjonsnrEier` stripped leading zeros from every tenant it derived before routing, so `"0194"` and `"194"` were the same institution. Whether clients rely on this is not yet verified from traffic.

## Shapes considered at Backlog

- **(a) Canonicalise through the column's converter (leaning).** A key that comes from a client is put through the database type and back, `converter.from(converter.to(value))`, so it equals what a read of that column yields. The column type already defines equality, so this needs no new configuration, and it is a no-op for a column with no converter. The cost is that the runtime needs the converter or the column's `DataType`, at each place a client-sourced key enters routing, or at the single gate where keys are compared, if one exists. The Spec finds out which.
- **(b) A consumer-supplied key-normalisation hook** on the runtime or configuration. More general, but it duplicates a rule the converter already states, and a consumer who forgets it gets today's behaviour.
- **(c) Out of scope,** with sis documenting a breaking change. This leaves a build where the SQL and the router disagree about the same value, which is the defect in itself.

## Questions for the Spec

- **Where to canonicalise.** At each divination site, or once at the gate where keys meet `tenants.contains` and the source map. Keys read off rows are already canonical, so canonicalising every key at the gate costs a round-trip per key, but it is one site, and keys the consumer supplies would get the same treatment.
- **A value the converter cannot read** (for example `"abc"` on an integer column). Today the router refuses it as not permitted; the SQL would fail to bind. Pick one answer, and keep it a client error rather than a server error.
- **The consumer's side.** If the gate canonicalises, decide whether `RequestTenants` and the source map are canonicalised too, so sis can drop its own `replaceFirst`, or whether the consumer must register canonical keys and graphitron says so.
