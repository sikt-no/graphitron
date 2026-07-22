---
id: R511
title: "Split-query TableRecord key extraction breaks on @service-returned @table parents"
status: Backlog
bucket: bug
priority: 6
theme: service
depends-on: []
created: 2026-07-22
last-updated: 2026-07-22
---

# Split-query TableRecord key extraction breaks on @service-returned @table parents

A field carrying both `@splitQuery` and `@service` generates a DataLoader-backed child fetcher that reconstructs the parent's batch key from `env.getSource()`. When the service method's key signature is a full jOOQ `TableRecord` (e.g. `Set<BestillingRecord>`), the key wrap is `SourceKey.Wrap.TableRecord` and `GeneratorUtils.buildKeyExtraction`'s `TableRecord` arm rebuilds the record by reading reserved projection aliases off the source row, `source.get("__src_<col>__", ...)` for every `parentTable.allColumns()`. Those `__src_<col>__` aliases exist only because a generated, SQL-projected parent query (`<Type>.$fields`) adds them. The extraction silently assumes the parent row was produced by such a query.

That assumption is false whenever the same `@table` type is handed back raw by a `@service`. A `@service`-backed query (the incident: `Query.minNyesteBestillingNy` returning a bare `BestillingRecord` from `selectFrom(BESTILLING)`) never goes through `$fields`, so its row type carries only the real columns and no `__src_*` aliases. Every `TableRecord`-keyed split-query child then throws at runtime, `IllegalArgumentException: Field "__src_BESTILLINGID__" is not contained in row type ("VIB"."BESTILLING".BESTILLINGID, ...)`. In the incident all five `@splitQuery @service` fields on `Bestilling` (`hoyereUtdannelse`, `fagskole`, `videregaende`, `grunnskole`, `ukjentFagniva`) failed identically whenever the parent `Bestilling` had been returned by a service. The combination worked pre-v10; the reserved-alias key-reconstruction scheme is a v10 generator addition (introduced fixing the `into(Tables.X)` multiset-alias collision) that narrowed the "what a parent source row looks like" contract without accounting for service-returned `@table` parents.

## Why the build-time guard misses it

`ParentProjectionContainmentCheck` (wired at `TypeClassGenerator.generateForType`) enforces the producer/consumer contract only for the SQL parent path: it verifies `<Type>.$fields` flips `reservedFullRow` so the projected query carries the aliases the children read. It has no model of a `@table` type being returned raw by a `@service`, so the service parent path is entirely un-guarded. The two parent kinds coexist in one schema and the generator never reconciles them.

## Subgraph workaround already in the field

Changing the service key signatures from `Set<BestillingRecord>` to `Set<Record1<String>>` flips the child onto the `Wrap.Record`/`Wrap.Row` extraction path (`((Record) env.getSource()).into(Tables.X.COL)` / `.get(Tables.X.COL)`), a base-name read that succeeds on both a raw service record and a generated projected row. This is a correct stopgap but is subgraph-side and per-signature fragile: the natural, documented thing to write is `Set<XRecord>`, so the next author (or the next `@table` type reachable via a service) re-trips it. The upstream fix belongs here.

## Direction (design fork for Spec)

The extraction cannot know statically which parent path feeds a given child (the same `@table` type is reachable via an SQL query with `__src_*` aliases, via a `@service`/DML producer as a raw typed record, or as a nested child). A runtime-adaptive read is the natural shape, discriminating on the source's own type: the SQL `$fields` path fetches into a generic `org.jooq.Record`, never the typed subclass, so `env.getSource() instanceof <XRecord>` distinguishes a service/DML-produced typed record (use it directly) from an SQL-projected generic row (rebuild from the reserved aliases). Both then work with the ergonomic `Set<XRecord>` signature. The fallback, a build-time validator that rejects the combination with author guidance, makes it loud but leaves the natural signature unusable, so it is the lesser option. Coverage should pin both parent kinds end to end (one execution test per parent kind, mirroring the two the workaround verification already exercised in the subgraph).
