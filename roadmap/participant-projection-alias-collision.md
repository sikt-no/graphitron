---
id: R749
title: "Same-named fields on two participants of one discriminated interface collide on the __rk_ alias, and one join path is silently dropped"
status: Backlog
bucket: bug
priority: 8
theme: codegen-correctness
depends-on: []
created: 2026-08-20
last-updated: 2026-08-20
---

# Same-named fields on two participants of one discriminated interface collide on the __rk_ alias, and one join path is silently dropped

When two participants of the same single-table discriminated interface each declare a field with
the same name over a *different* join path, only one of the two projections reaches the SQL. The
other participant's field reads the surviving participant's column, so it returns null (when the
surviving path's key is null on those rows) or another participant's data (when it is not). No
build-time diagnostic, no runtime error, no warning: the schema classifies, generates, compiles,
and executes clean.

Reported from a consumer subgraph: an interface `Melding` with four implementers, two of which
populate their own `soknad` field over different reference paths. Only one type's `soknad` came
back populated; the other was null.

## Reproduction

Confirmed on `e60c176` at the execution tier. Fixture tables (not in
`graphitron-sakila-db/src/main/resources/init.sql`; created ad hoc for the trace):

```sql
CREATE TABLE soknad (
    soknad_id serial PRIMARY KEY,
    tittel    varchar(64) NOT NULL
);
CREATE TABLE melding (
    melding_id         serial PRIMARY KEY,
    melding_type       varchar(16) NOT NULL,
    dokument_soknad_id int REFERENCES soknad(soknad_id),
    mangel_soknad_id   int REFERENCES soknad(soknad_id)
);
INSERT INTO soknad (tittel) VALUES ('Dokumentsoknad'), ('Mangelsoknad');
INSERT INTO melding (melding_type, dokument_soknad_id, mangel_soknad_id) VALUES
  ('DOKUMENT', 1, NULL), ('MANGEL', NULL, 2);
```

Schema:

```graphql
interface Melding @table(name: "melding") @discriminate(on: "melding_type") {
    meldingId: Int! @field(name: "melding_id")
}

type DokumentMelding implements Melding @table(name: "melding") @discriminator(value: "DOKUMENT") {
    meldingId: Int! @field(name: "melding_id")
    soknad: Soknad @reference(path: [{key: "melding_dokument_soknad_id_fkey"}])
}

type MangelMelding implements Melding @table(name: "melding") @discriminator(value: "MANGEL") {
    meldingId: Int! @field(name: "melding_id")
    soknad: Soknad @reference(path: [{key: "melding_mangel_soknad_id_fkey"}])
}

type Soknad @table(name: "soknad") {
    soknadId: Int!    @field(name: "soknad_id")
    tittel:   String! @field(name: "tittel")
}
```

Querying `soknad` on both types yields no errors and this payload:

```
{__typename=DokumentMelding, meldingId=1, soknad={soknadId=1, tittel=Dokumentsoknad}}
{__typename=MangelMelding,   meldingId=2, soknad=null}
```

The emitted statement carries exactly one `soknad` term, correlated on `dokument_soknad_id`.
`mangel_soknad_id` appears nowhere in it:

```sql
select "melding"."melding_type" as "__discriminator__", "public"."melding"."melding_id",
       (select coalesce(jsonb_agg(jsonb_build_array(t."v0", t."v1")), jsonb_build_array())
        from (select "melding_s0"."soknad_id" as "v0", "melding_s0"."tittel" as "v1"
              from "public"."soknad" as "melding_s0"
              where "melding_s0"."soknad_id" = "public"."melding"."dokument_soknad_id"
              fetch next ? rows only) as t) as "__rk_soknad"
from "public"."melding" where "melding"."melding_type" in (?, ?)
order by "public"."melding"."melding_id" asc
```

## Mechanism

The defect is a lost projection, not a duplicated column. Three facts compose:

1. **The interface fetcher folds every participant's projection into one set.** The generated root
   body calls each participant's `$project` with the *same* grouped selection map and accumulates
   into a `LinkedHashSet<Field<?>>`:

   ```java
   fields.addAll(DokumentMelding.$project(env.getSelectionSet().getFieldsGroupedByResultKey(), meldingTable, env));
   fields.addAll(MangelMelding.$project(env.getSelectionSet().getFieldsGroupedByResultKey(), meldingTable, env));
   ```

2. **Both participants mint the same alias.** Each `$project` aliases its multiset
   `"__rk_" + entry.getKey()`, the result-key aliasing introduced for aliased duplicate selections
   (R500, Done). The alias carries the result key and nothing about the declaring type, so both
   arms produce `__rk_soknad`.

3. **jOOQ `Field` equality is name-and-type based, so the set drops the second one.** The two
   multiset expressions render different SQL but compare equal, so the second `addAll` is a no-op
   and the losing participant's join path never reaches the statement. The read side is
   `record.get("__rk_" + env.getField().getResultKey())` on both fetchers, so the losing type reads
   the winning type's column.

Three properties of the current behaviour worth carrying into the fix:

* **The winner is not author-controllable.** Swapping the two type declarations in the SDL does not
  change the fold order; `DokumentMelding.$project` is still emitted first. Reordering the schema is
  not a workaround.
* **A participant projects even when nothing selected its field.** With only
  `... on MangelMelding { soknad { ... } }` in the query, the statement still projects the
  `DokumentMelding` correlation: the grouped map is keyed by result key with no type qualification,
  so `case "soknad"` fires in every participant's `$project`. This is the cross-type arm collision
  R708 predicted from the `OccupantLocation` fixture ("two same-named arms whose SQL differs would
  emit two terms under one `__rk_` alias"), observed here as a live consumer defect. R708's own
  observation was over-projection under a shared nesting type, and its fix direction (gate on
  immediate children) does not by itself disambiguate two participants that both legitimately
  declare the name.
* **`@splitQuery` on both fields is clean.** With `@splitQuery` on each `soknad`, both types return
  their own row: the DataLoader path keys off the parent's FK column projected by base name rather
  than a shared `__rk_` alias. That makes it a usable consumer workaround for the inline shape, and
  it bounds the defect to the inline multiset route. The original report also claimed a split-query
  failure; that variant differs by using per-type `@condition` methods rather than distinct FK
  paths and is not reproduced here, so it needs its own trace before being treated as the same bug.

## What a fix has to decide

The alias needs per-participant disambiguation, and the surviving read has to agree with it. The
open question is where the type qualifier lives. Note the scalar cross-table participant field
already solves this problem in a neighbouring emitter: the discriminated route aliases those
`"<TypeName>_<fieldName>"` (`FanAlpha_note` in the fan fixture), so the codebase carries a
precedent that the `__rk_` path does not follow. Whether the fix is to extend that convention into
the result-key namespace, to have the fold key on `(declaringType, resultKey)`, or to make the
per-participant `$project` receive a type-scoped selection map, wants the survey R708 also asks
for: several call sites build the grouped map, the pivot and discriminated-table bodies loop it too,
and `SelectionOccurrences` is part of the generated contract.

Whatever the mechanism, a same-named participant field pair should be either correct or a build
error, never a silent wrong-column read. The classifier knows both participants and both resolved
paths at build time, so the collision is detectable before emission.

## Related

* R708 (`projection-selection-gate-depth-leak`) predicted the collision as a consequence of the
  selection gate keying on bare field names; this item is the reported instance with divergent SQL.
* R500 (Done) introduced the `__rk_` result-key alias namespace, which disambiguates result keys
  within one type and is the substrate this defect sits on.
* R556 (`pivot-nesting-representative-read-divergence`) is the neighbouring case of a shared
  projection type whose read name diverges from what was emitted.
