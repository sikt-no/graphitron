---
id: R396
title: "@reference FK-connection validation rejects schema-qualified or case-mismatched @table base names"
status: Backlog
bucket: bug
priority: 3
theme: interface-union
depends-on: []
created: 2026-06-29
last-updated: 2026-06-29
---

# @reference FK-connection validation rejects schema-qualified or case-mismatched @table base names

When a type carries `@table(name:)` with a schema-qualified value (for example
`@table(name: "multischema_a.signal")`) or a case-mismatched value (for example
`@table(name: "multischema_a.SIGNAL")` against a real lowercase `signal`), an
`@reference(path: [{key: "<fk>"}])` field on that type fails schema validation with
`Author error: Field '<Type>.<field>': key '<fk>' does not connect to table '<name>'`. The same
`@reference` with an unqualified, exact-case `@table(name: "signal")` validates and resolves fine.
The FK-connection check appears to compare the FK's source table against the verbatim `@table(name:)`
directive string rather than the resolved jOOQ table identity, so any directive spelling that differs
from the catalog's rendered name (schema prefix, or letter case) is treated as "not connected" even
though it resolves to that very table. This is the same class of directive-string-vs-rendered-name
divergence that R395 fixed for the discriminator qualifier, but on the `@reference` FK-connection path
and surfacing at schema-validation time (author error) rather than at runtime.

Discovered while implementing R395: the planned `multischema` execution fixture wanted a
schema-qualified, upper-case `@table(name: "multischema_a.SIGNAL")` discriminated interface with a
cross-table `@reference`; the FK-connection check rejected both the qualified and the upper-case
forms, so R395 worked around it with an unqualified `@table(name: "signal")` (the schema-qualified /
case dimension is instead pinned at R395's unit tier). Fixing this would let consumers author
schema-qualified or case-divergent base tables and still attach `@reference` fields, and would let the
R395 execution fixture be tightened to the originally-specified schema-qualified form.
