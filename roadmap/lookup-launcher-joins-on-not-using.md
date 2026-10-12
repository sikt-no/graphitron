---
id: R1015
title: "The lookup launcher joins its input rows with ON, not USING"
status: Backlog
bucket: bug
priority: 6
theme: codegen-correctness
depends-on: []
created: 2026-10-12
last-updated: 2026-10-12
---

# The lookup launcher joins its input rows with ON, not USING

## Goal

A root lookup field (one whose arguments carry `@lookupKey`, so the launcher turns the argument list into a `VALUES` table of input rows and joins it against the target table) produces SQL that every supported dialect accepts, whatever its key columns are. Today the launcher joins with `USING (<key columns>)`, which is broken in two ways. On every dialect, a key column whose jOOQ field renders as an expression (a computed column) yields `using ((to_char("FODSELSDATO", :4) || to_char("PERSONNR", :5)))` or `using (case when ... end)`, which is not valid SQL. On Oracle, even a plain key column fails, because the `$project` projection selects the key qualified (`"STUDIEPROGRAM"."STUDIEPROGRAMKODE"`) and Oracle forbids qualifying a `USING` column anywhere in the query (ORA-25154). When this lands the launcher joins with an explicit `ON` against `input.field("<col>", T)`, the computed expression staying on the table side, exactly as the entity dispatcher already does.

## Provenance

sis IT run against Oracle 18c on trunk `dd940c703` (sis branch `sis/upgrade-graphitron`, write-up in `sis/GRAPHITRON10_STATUS.md`, section "Second IT run"). The single largest failure cause there, about 45 of 218 ITs. Failing SQL, from `studieprogramGittStudieprogramkoder`:

```sql
from "STUDIEPROGRAM"
join (select 0 "idx", :1 "STUDIEPROGRAMKODE" from DUAL) "studieprogramGittStudieprogramkoderInput"
  using ("STUDIEPROGRAMKODE")
where "STUDIEPROGRAM"."INSTITUSJONSNR_EIER" = :2
```

Computed-key failures: `studenterGittFodselsnumre` (ORA-03077) and `studenterGittFeideBrukere` (ORA-00907).

## Where it lives

- `RootLauncherRenderer.java:493` emits `.join(input).using(LookupRows.usingArgs(...))`.
- `ValuesJoinRowBuilder.usingArgs` builds the `USING` list; its only consumer is the launcher.
- The precedent is `SelectMethodBody`, whose class Javadoc explains why the dispatcher path uses `.on(...)` rather than `.using(...)` (the `$project` interaction). The launcher should take the same shape, and `usingArgs` retires with it.
- `GraphQLQueryTest.nodes_perTypeIdBatch_emitsValuesJoinOrderByIdxShape` gates the `values`/`join`/`order by` SQL shape on the dispatcher side; the fix extends or mirrors that gate for the launcher, and a computed-key lookup belongs in the execution tier, since PostgreSQL already rejects that half.
