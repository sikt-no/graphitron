---
id: R1188
title: "A key or tenant value with a huge exponent is refused in constant time, and _entities/nodes take a bounded number of keys"
status: Backlog
bucket: bug
priority: 1
theme: nodeid
depends-on: []
created: 2026-11-16
last-updated: 2026-11-16
---

# A key or tenant value with a huge exponent is refused in constant time, and _entities/nodes take a bounded number of keys

## Goal

A client can no longer stall a generated service with a short number. When a federation `@key` value or a tenant value carries an extreme exponent or digit count, the emitted `ColumnValues` (the runtime class that decides whether a client value is a value of its column) refuses it in constant time and memory, before any arithmetic whose cost grows with the exponent. `_entities` and `Query.nodes` also refuse a request carrying more representations or ids than a configurable limit, as a client error and before any key is decoded. Today one `_entities` request can pin a request thread for minutes and push the heap toward `OutOfMemoryError`, which denies service to every user of the subgraph.

## Finding

Reported by a security scan against `2e38c91` (CWE-1284, high). It needs no login, only a schema with an entity whose `@key` field maps to an integer column (`Byte`, `Short`, `Integer`, `Long`, or a converter whose database side is one), as `Film`, `City` and `FilmActor` do in the sakila example. A second way in exists in multi-tenant builds: a `String`- or `ID`-typed tenant binding over an integer tenant column reaches the same method through `TenantConnections.tenantKeyOrNull` and `tenantKeyOf`, before the request's tenant set is consulted. String-typed key columns are not affected.

The path: `representations` is `[_Any!]!`, so a key value is raw client JSON whatever the key field's GraphQL type is. The `_entities` arm of `HandleMethodBody` passes each one to `ColumnValues.coerce` with the key column's type. `readNumber` parses it with `new BigDecimal(s.trim())`, which accepts any exponent the JDK can parse. `trip` converts it through jOOQ's `DataType.convert`, which is cheap: `longValue()` answers 0 at a scale of -64 or lower. Since 0 differs from the input, the wrap check in `coerce` then evaluates `input.setScale(0, RoundingMode.DOWN)`, which builds ten to the power of the exponent in full.

Measured here on JDK 25 and jOOQ 3.20.11 for a `BIGINT` column: `SQLDataType.BIGINT.convert(s)` answers `0` in under a millisecond for every input below, while `setScale` takes 8 ms for `1e100000`, 123 ms for `1e1000000` and 1625 ms for `1e10000000` (ten bytes). The growth is superlinear, and the JDK accepts exponents up to about 5·10^8 here, so one value of about fifteen bytes costs minutes. The computation cannot be interrupted, runs outside any `try`, and is repeated once per representation in a request whose size nothing bounds.

## Fix criteria

Carried over from the report, to be refined into a plan at Spec.

1. Whether a client value wraps is decided at a small constant cost for any input. A number whose magnitude (precision minus scale) or digit count exceeds what the column type can represent returns `null` before any inflating operation (`setScale`, `toBigInteger`, `toPlainString`, and `compareTo` across very different scales). This holds on every numeric column type, converter-backed ones included, and in `exact`, `fromDatabase` and `fitsPrecision` as well as `coerce`. The documented coercions are kept: `"1.5"` binds as 1, a padded number is accepted, and a wrap is still refused.
2. The number of `_entities` representations per request, and of ids per `Query.nodes`, is bounded by a configurable limit. The limit applies on the direct `resolveByReps` path (`EntityFetcherDispatchClassGenerator`) as well as to DataLoader batches, and a request over it is refused as a client error before per-element decoding starts.

Code the report cites: `ColumnValuesClassGenerator` (`coerce`, `trip`, `readNumber`), `HandleMethodBody` (the `_entities` key arm), `EntityFetcherDispatchClassGenerator` (`fetchEntities`), `ConnectionRuntimeClassGenerator` (`tenantKeyOrNull`, `tenantKeyOf`), and `ColumnValuesTest` in `graphitron-sakila-example` for the existing coercion cases. Adjacent: `derived-read-cost-is-a-shape-problem` changes the `exact` and `folded` signatures, so whichever lands second rebases onto the other.

Open for Spec: where the limit is configured (a Maven plugin parameter baked into the generated code, or a runtime setting on the generated wiring) and its default; and whether the magnitude bound comes from the jOOQ `DataType` (precision, and the integer types' fixed ranges) or from a fixed ceiling, since a `NUMERIC` with no declared precision has no column bound to read.
