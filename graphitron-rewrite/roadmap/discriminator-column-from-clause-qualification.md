---
id: R395
title: "Discriminated-interface discriminator column must qualify to the FROM table, not the @table directive name"
status: Backlog
bucket: bug
priority: 3
theme: interface-union
depends-on: []
created: 2026-06-29
last-updated: 2026-06-29
---

# Discriminated-interface discriminator column must qualify to the FROM table, not the @table directive name

A single-table discriminated interface (`@table @discriminate`) generates SQL that
references the discriminator column qualified by the **`@table(name:)` directive string**
rather than the table that actually appears in the FROM clause. When the directive name does
not render identically to the jOOQ table (different schema, or different case), Postgres
rejects the query with `missing FROM-clause entry`. This is a regression: the discriminator
field was projected with a bare column name before R388 and resolved fine against the single
FROM table.

## Reproduction (consumer: opptak)

Schema (simplified):

```graphql
interface Melding @table(name: "INNBOKS_MELDING") @discriminate(on: "INNBOKS_MELDINGSTYPE_KODE") { ... }
type GenerellMelding implements Melding @table(name: "INNBOKS_MELDING") @discriminator(value: "GENERELL") { ... }
# ...DokumentMelding, KvitteringMelding, all on the same table
```

The real Postgres table is `kommunikasjon.innboks_melding` (lowercase, in schema `kommunikasjon`).
The directive value `INNBOKS_MELDING` is uppercase and unqualified. Generated SQL (abbreviated):

```sql
select
  "INNBOKS_MELDING"."innboks_meldingstype_kode" as "__discriminator__",
  ...
from "kommunikasjon"."innboks_melding"
where ...
  and "INNBOKS_MELDING"."innboks_meldingstype_kode" in ('GENERELL', 'DOKUMENT', 'KVITTERING')
```

The `__discriminator__` projection and the IN filter reference `"INNBOKS_MELDING"`, an alias
absent from FROM. Runtime: `org.postgresql.util.PSQLException: ERROR: missing FROM-clause entry
for table "INNBOKS_MELDING"`, surfaced as a `DataFetchingException` so the field returns null.

## Root cause

`b6b629dac` (R388, "qualify discriminator column") changed the discriminator projection in
`TypeFetcherGenerator` from a bare `DSL.field(DSL.name(col))` to a two-part
`DSL.field(DSL.name(tableSqlName, col))`, intending `"base"."col"` to match the FROM clause.
But `tableSqlName` is `tableRef.tableName()` — the verbatim, case-preserved `@table(name:)`
value (see `TableRef` javadoc), which is **not** how jOOQ renders the FROM table. jOOQ emits
the generated table's real schema-qualified, case-folded name. The two strings coincide only
when the directive name equals the unqualified SQL name *and* jOOQ renders no schema prefix
(the sakila fixtures: `@table(name:"content")` == `content`, default schema unrendered), which
is why the regression slipped past the existing fixtures.

Three emit sites carry the same flaw, all in `TypeFetcherGenerator`:
`buildInterfaceFieldsList` (the `__discriminator__` routing projection), `buildDiscriminatorFilter`
(the `... IN (knownValues)` restriction), and `buildCrossTableJoinChain` (the discriminator
equality in each participant LEFT JOIN ON-clause). Each qualifies via `tableSqlName`.

## Proposed direction

Qualify the discriminator off the **same `tableLocal` jOOQ table instance** that the FROM clause
uses, so the rendered qualifier is identical to FROM by construction, regardless of schema or
directive-name case (and regardless of whether the base table is later aliased). Concretely,
emit `DSL.field(<tableLocal>.getQualifiedName().append(DSL.name("<col>")), Object.class)` — a
`Field<Object>` so the downstream `.eq(String)` / `.in(String...)` / `.as(...)` calls still
compile (the original typing concern R388 cited against a bare table-instance `Field<?>`). The
`@table(name:)` string stops participating in qualification entirely.

## Coverage gap to close

No fixture exercises a discriminated interface whose table is in a non-default schema or whose
`@table(name:)` casing differs from the SQL name; that blind spot is exactly what let R388
regress. Add an execution-tier fixture in that shape (e.g. a schema-qualified, case-mismatched
discriminated interface) so the FROM-clause qualification is pinned against a real Postgres run.

## Notes

- Reported against the released **10.0.0-RC21**; consumer wants this fix and the RC21
  transitively-nested `@reference` fix in the same version.
- RC20 worked because the pre-R388 bare-name projection resolved against the single FROM table.
