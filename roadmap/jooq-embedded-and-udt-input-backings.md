---
id: R234
title: "Support jOOQ embedded and UDT records as non-table input backings"
status: Backlog
bucket: architecture
priority: 1
theme: classification-model
depends-on: []
created: 2026-05-23
last-updated: 2026-07-13
---

# Support jOOQ embedded and UDT records as non-table input backings

A non-table jOOQ `Record` input backing is currently *admitted*, not rejected: `TypeBuilder.buildNonTableInputType` (`TypeBuilder.java:1688`, dispatch at `:1702`-`:1709`) routes `TableRecord` subclasses to `GraphitronType.JooqTableRecordInputType` and any other `org.jooq.Record` subclass to the generic `GraphitronType.JooqRecordInputType`; both are live sibling input arms in `GraphitronType.java` (`:342` and `:357`). (An earlier version of this item claimed R222 had collapsed the generic arm into a rejection; that is not the shipped state, and the `BackingClass` family that framing referenced is unbuilt. R222 remains a prose gate on classification-model direction, not a hard dependency edge, so `depends-on: []` stands.) What the generic arm lacks is structure: a jOOQ embeddable record (jOOQ 3.20+ feature for grouping related columns into a typed structure) or a UDT record (PostgreSQL composite type) classifies as the same undifferentiated `JooqRecordInputType`, and no real schema in graphitron-fixtures-codegen or Sakila currently exercises either as an input. Both are legitimate jOOQ-side carriers; both have stable Java accessors but no `TableRef` of their own. This item introduces the dedicated arm(s) those cases need, likely `EmbeddableRecord(fqClassName, embeddable: EmbeddableRef)` and `UDTRecord(fqClassName, udt: UDTRef)` rather than leaning on the generic `JooqRecordInputType` catch-all, so each arm carries the structural metadata its downstream consumers actually want. Scope: add the new arm(s) as siblings of `JooqRecordInputType` / `JooqTableRecordInputType`, wire `TypeBuilder.buildNonTableInputType` to detect them, decide whether they participate in `classifiedFields` (UDT-typed inputs may project onto a single column whose value is the UDT instance, which is a different code path than table-bound inputs), and add fixtures. No consumer needs it yet; lands once a real fixture or user case surfaces.
