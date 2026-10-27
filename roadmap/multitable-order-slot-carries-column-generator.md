---
id: R1105
title: "A multitable ORDER BY slot built from a generator column renders the generator with no table and throws"
status: Backlog
bucket: bug
priority: 3
theme: codegen-correctness
depends-on: []
created: 2026-10-27
last-updated: 2026-10-27
---

# A multitable ORDER BY slot built from a generator column renders the generator with no table and throws

## Goal

A root list or connection over a multitable interface or union can be ordered by a jOOQ column that carries a `<generator>` (a client-side computed column, registered under `syntheticObjects`) without crashing at runtime. Today the generated fetcher declares each ordering slot over the derived table as `DSL.field(DSL.name("__ord0__"), <Table>.<COLUMN>.getDataType())`, and for a generator column that `DataType` is virtual and carries the generator; when jOOQ renders `ORDER BY` over the derived table, `SelectQueryImpl.getResolveProjection` runs the generator for the unqualified slot with no owning table, and the consumer's lambda throws `NullPointerException` on `GeneratorContext.table()`. The same column sorts fine on a single-table connection, and a plain column with the same declaration works. Reported against 10.0.0-RC40 on [GitHub issue #523](https://github.com/sikt-no/graphitron/issues/523#issuecomment-6033141282) (`Personbruker` over `feide_bruker` and `personsubjekt`, sorted on `VISNINGSNAVN`).

## Where it lives

`MultiTablePolymorphicEmitter.typedFieldDeclaration` emits the declaration; it serves both the ordering slots (the `slotLocal`/`slotAlias` call) and the parent-key fields (`keyLocal`/`keyAlias`). The reporter suggests typing the slot from the column's Java type or a non-virtual base `SQLDataType` instead. Whatever the fix, it must keep the slot's converter-free database type so the seek and the cursor still bind correctly, and the parent-key call site should be checked for the same hazard. A fixture needs a generator column on two participant tables, ordered at execution tier.
