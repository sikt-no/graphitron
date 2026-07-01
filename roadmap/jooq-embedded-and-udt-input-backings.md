---
id: R234
title: "Support jOOQ embedded and UDT records as non-table input backings"
status: Backlog
bucket: architecture
priority: 1
theme: model-cleanup
depends-on: []
created: 2026-05-23
last-updated: 2026-05-23
---

# Support jOOQ embedded and UDT records as non-table input backings

R222 collapsed the legacy `JooqRecordInputType` arm by rejecting any non-`TableRecord` jOOQ `Record` subclass at classification with `Rejection.AuthorError("backing class %s is a jOOQ Record but not a TableRecord; supported non-table backings are Java record or POJO")`. That stance is correct for today's graphitron-fixtures-codegen and Sakila surfaces (no real schema currently binds a non-table jOOQ Record as an input), but it bakes in a rejection that will trip the moment a consumer wants to use a jOOQ embeddable record (jOOQ 3.20+ feature for grouping related columns into a typed structure) or a UDT record (PostgreSQL composite type) as an input. Both are legitimate jOOQ-side carriers; both have stable Java accessors but no `TableRef` of their own. This item reintroduces the backing-class arm(s) those cases need — likely `EmbeddableRecord(fqClassName, embeddable: EmbeddableRef)` and `UDTRecord(fqClassName, udt: UDTRef)` rather than a generic `JooqRecord` catch-all, so each arm carries the structural metadata its downstream consumers actually want. Scope: extend `BackingClass` with the new arm(s), wire the visitor's runtime-shape classification to detect them, decide whether they participate in `classifiedFields` (UDT-typed inputs may project onto a single column whose value is the UDT instance, which is a different code path than table-bound inputs), and add fixtures. Out of scope for R222 because no consumer needs it yet; lands once a real fixture or user case surfaces.
