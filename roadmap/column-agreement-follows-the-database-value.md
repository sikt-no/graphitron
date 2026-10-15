---
id: R1044
title: "Two-writer column agreement compares converter-backed values by their database value"
status: Backlog
bucket: bug
priority: 4
theme: nodeid
depends-on: [tenant-key-equality-follows-the-column]
created: 2026-10-15
last-updated: 2026-10-15
---

# Two-writer column agreement compares converter-backed values by their database value

## Goal

When two input fields write one column (a `@nodeId` decode and a plain `@field` landing on the same column, or two `SET` writers in an update), graphitron accepts them when they name the same database value, including on a column a jOOQ converter maps to a Java type with more spellings than the database type. Today the generated `NodeIdEncoder.requireColumnAgreement` compares `type.convert(a)` with `type.convert(b)`, and for a column converted to `String` that compares the Java spellings: `"0186"` and `"186"` on a `bigint` column mapped to `String` are reported as "Conflicting values supplied", though both write `186`. R1038 makes tenant routing compare by the round trip through the column's converter (`converter.from(converter.to(type.convert(v)))`); after this item the two-writer check uses the same rule, so the emitted runtime holds one definition of "same value of this column".

## Notes for the Spec

- Needs an execution fixture with two writers on a converter-backed column. `NodeIdValueAgreementExecutionTest` has none today; `converter_campus.org_code` and the `ConverterCampusTerm` `@nodeId` roots in the example schema are the nearest candidates.
- Decide whether the round trip becomes one emitted primitive that `NodeIdEncoder` and `TenantConnections` both call, or one generator-side helper both emitters render through.
