---
id: R395
title: "Discriminated-interface discriminator column must qualify to the FROM table, not the @table directive name"
status: In Review
bucket: bug
priority: 3
theme: interface-union
depends-on: []
created: 2026-06-29
last-updated: 2026-06-29
---

## Landed (awaiting In Review → Done sign-off)

All three discriminator emit sites in `TypeFetcherGenerator` now qualify off the FROM table's own
jOOQ instance via `<tableLocal>.getQualifiedName().append(DSL.name(col))` (a `Field<Object>` via the
explicit `Object.class`), so the rendered qualifier matches the FROM clause by construction:

- `buildInterfaceFieldsList` (`__discriminator__` routing projection): dropped `tableSqlName`.
- `buildDiscriminatorFilter` (`... IN (knownValues)`): swapped `tableSqlName` for `tableLocal`.
- `buildCrossTableJoinChain` (LEFT JOIN ON-clause discriminator gate): dropped `tableSqlName`.

Both callers (`buildQueryTableInterfaceFieldFetcher`, `buildTableInterfaceFieldFetcher`) pass
`tableLocal`; `tableRef.tableName()` no longer reaches any discriminator site. Method javadocs and
the two class-level example snippets were refreshed to the table-instance qualifier.

Tests: four regression-lock assertions in `TypeFetcherGeneratorTest` (each site qualifies off
`filmTable.getQualifiedName().append(...)`; the directive-name string never qualifies, pinned with a
case-mismatched `INTERFACE_BASE` fixture). New execution-tier fixture in the `multischema` slice:
`multischema_a.signal` (DDL + seed in `init.sql`, `jooq.codegen.schema.version` bumped 2.1 → 2.2),
a `Signal @discriminate` interface with `AlertSignal` (carrying a cross-table `@reference` to
`widget`) / `NoticeSignal` in `multischema.graphqls`, and `MultiSchemaQueryTest.signalsRouteToDiscriminatedTypesUnderNamedSchema`.
Full graphitron + sakila-example suites green; the default-schema guards (`allContent`/`allSubjects`,
`PolymorphicProjectionQueryTest`) still pass, confirming no over-qualification regression.

### Deviation from spec: execution fixture uses an unqualified `@table` name

The spec called for `@table(name: "multischema_a.SIGNAL")` (schema-qualified, upper-case). Both the
upper-case and the schema-qualified forms are rejected at schema-validation time by a *separate*
`@reference` FK-connection check ("key `signal_widget_id_fkey` does not connect to table ...") that is
case- and schema-qualification-sensitive on the base `@table` name; this is unrelated to R395's
discriminator-qualifier root cause. The fixture instead uses the unqualified `@table(name: "signal")`
(`signal` is unique to `multischema_a`, so it resolves like `Widget`), which still renders the FROM
token schema-qualified as `"multischema_a"."signal"` and so still fires the R395 bug pre-fix; this is
exactly the reported consumer shape (unqualified directive, schema-qualified real table). The
upper-case / schema-qualified-directive dimension is covered at the unit tier (`INTERFACE_BASE`
fixture). The `@reference` FK-connection limitation is filed as a follow-up Backlog item.

On approval: delete this file and add a one-line `changelog.md` entry citing the landing SHA and R395.

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

The read-side fourth site is already handled: R392 routed the `TypeResolver` off the
synthetic `__discriminator__` SELECT alias rather than a qualified column read, so it carries
no table qualifier and does not regress here. Scope is exactly these three SQL-emission sites.

## Implementation

Switch all three sites to qualify off the `tableLocal` jOOQ table-instance variable that the
FROM clause already binds, so the rendered qualifier is produced by jOOQ's own table renderer
and matches FROM by construction. The emit pattern at each site changes from:

```java
DSL.field(DSL.name(tableSqlName, col))                          // "INNBOKS_MELDING"."..."
```

to:

```java
DSL.field(<tableLocal>.getQualifiedName().append(DSL.name(col)), Object.class)
```

`<tableLocal>` is the local-variable name from `GeneratorUtils.ResolvedTableNames.tableLocalName()`
(for example `contentTable`), already in scope at every call site via `declareTableLocal`.
`getQualifiedName()` returns the table's catalog `Name`: for a default-schema table it carries
no schema part (rendering identically to the unqualified FROM token), and for a table in a
named schema it carries the schema part (rendering `"schema"."table"`, identical to FROM).
`.append(DSL.name(col))` adds the discriminator column. The explicit `Object.class` yields a
`Field<Object>` so the downstream `.as(...)`, `.in(String...)`, and `.eq(String)` calls compile;
that typing need is the reason R388 reached for a string-built name in the first place. The
`@table(name:)` string stops participating in qualification entirely.

Column-name handling is unchanged: `col` stays the raw `@discriminate(on:)` value passed through
`DSL.name(col)`. Column-name casing against the catalog is a separate, pre-existing concern and
out of scope here; R395 is strictly the table qualifier.

Concrete edits, all in `TypeFetcherGenerator`:

- `buildInterfaceFieldsList` (the `__discriminator__` routing projection): already receives
  `tableLocal`. Drop the `tableSqlName` parameter; build the qualifier off `tableLocal`.
- `buildDiscriminatorFilter` (the `... IN (knownValues)` restriction): currently receives only
  `tableSqlName`. Add a `tableLocal` parameter and drop `tableSqlName`. Both callers
  (`buildQueryTableInterfaceFieldFetcher`, `buildTableInterfaceFieldFetcher`) already have
  `tableLocal` in scope and today pass `tableRef.tableName()`; pass `tableLocal` instead.
- `buildCrossTableJoinChain` (the discriminator equality in each participant LEFT JOIN ON-clause):
  already receives `tableLocal`. Drop the `tableSqlName` parameter; build the qualifier off
  `tableLocal`.

After this, `tableRef.tableName()` no longer flows into any of the three discriminator emit
sites. Refresh the method javadocs that currently justify "the two-part `DSL.name(base, col)`"
qualification so they describe the table-instance qualifier instead.

## Verification: the default-schema path must not over-qualify

The one risk in this approach is over-qualification on the default-schema path: if
`getQualifiedName()` carried a `public` schema part that FROM suppresses, the fix would
introduce a *new* `missing FROM-clause entry` regression on the existing sakila `content`
fixture.

Evidence it does not: the rewrite sakila tables render with no schema prefix (the R108
execution test `PolymorphicProjectionQueryTest` asserts captured SQL of the form
`"customer"."first_name"`, never `"public"."customer"...`), while the `multischema_a` /
`multischema_b` tables do render their schema (`MultiSchemaQueryTest`, R83). That asymmetry
means the sakila tables sit in jOOQ's unnamed default schema, so `getQualifiedName()`
contributes no schema part for them and the qualifier matches the unqualified FROM token. The
fix is therefore correct for both shapes, but this is a property to pin with tests, not assume:
the existing `allContent` / `allSubjects` execution tests in `GraphQLQueryTest` are the
default-schema regression guard (they must keep passing), and the fixture below is the
non-default-schema guard.

## Tests

### Unit (`TypeFetcherGeneratorTest`)

The existing interface tests assert on the discriminator column literal (`"FILM_TYPE"`), the IN
values (`"FILM"`, `"SHORT"`), `.in(`, and the LEFT JOIN ON shape; those survive unchanged
because the column and values are untouched. Add assertions that pin the new qualifier shape and
lock out the regression:

- At all three sites, the emitted body qualifies off the table local
  (`<tableLocal>.getQualifiedName().append(`), not a directive-name string literal.
- The fixture's `@table(name:)` echo does not appear as a SQL-name qualifier in the discriminator
  projection, filter, or join ON-clause. To make that assertion meaningful, give the
  discriminated-interface unit fixture a `tableName` whose case differs from the local-variable
  derivation (for example `@table(name:)` of `"FILM"` while the column stays `FILM_TYPE`), so a
  regression back to the directive-string qualifier surfaces as a distinct literal the assertion
  can forbid.

### Execution tier (the coverage gap R388 regressed on)

Add a single-table discriminated interface in a non-default schema with a case-mismatched
directive name to the `multischema` fixture, which already has its own graphitron-maven-plugin
execution (`rewrite-generate-multischema`) and the `MultiSchemaQueryTest` execution-tier home:

- DDL (`graphitron-sakila-db/src/main/resources/init.sql`): a new table in `multischema_a`
  (lowercase real name, for example `multischema_a.signal`) with a discriminator column and a
  few seed rows of differing discriminator values. To also pin the cross-table LEFT JOIN
  ON-clause site under a non-default schema, give one participant a cross-table `@reference` to
  another `multischema` table reachable by FK; if that proves heavy, sites 1 and 2 are still
  pinned here and site 3 keeps its existing default-schema coverage plus the new unit assertion.
- SDL (`multischema.graphqls`): an `interface` with `@table(name: "multischema_a.SIGNAL")`
  (schema-qualified and upper-case, so neither the case nor a bare name matches the rendered
  table) plus `@discriminate(on: ...)`, two implementing types each with `@discriminator(value: ...)`,
  and a `Query` root field returning the list.
- Test (`MultiSchemaQueryTest`): query the interface field and assert the rows route to the
  correct concrete types. Before the fix this query throws `missing FROM-clause entry` and the
  field resolves null; after the fix it returns the seeded rows. Optionally capture SQL (as
  `PolymorphicProjectionQueryTest` does) and assert the discriminator references resolve against
  the same `"multischema_a"."signal"` token FROM uses.

This fixture is the durable guard for the dimension R388 regressed on; without it, a later change
to the qualifier could regress the schema-qualified shape again unnoticed.

## Roadmap entries

On implementation: trim this file to its residual follow-up (if any), flip `status:` to
`In Review`, regenerate the README. On approval: delete the file and, because this closes a
released-RC regression with a named root cause worth keeping, add a one-line `changelog.md` entry
citing the landing SHA and `R395`.

## Notes

- Reported against the released **10.0.0-RC21**; consumer wants this fix and the RC21
  transitively-nested `@reference` fix in the same version.
- RC20 worked because the pre-R388 bare-name projection resolved against the single FROM table.
