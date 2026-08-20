---
id: R749
title: "Same-named fields on two participants of one discriminated interface collide on the __rk_ alias, and one join path is silently dropped"
status: Spec
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

---

## Decision: scope the fold's selection per participant, qualify the participant-local alias with the declaring type

The survey the Backlog body asked for is done; its facts are inlined where they bind. The fix is
three coordinated parts: the discriminated fold hands each participant a selection map restricted
to that type (reusing an existing generated helper), participant-local fields in the four
`ResultKeyAliasedField` families carry the declaring type in their `__rk_` alias on both the
write and the read side, and two validator censuses turn the residual silent shapes into build
errors. Vocabulary used below: a field on a `ParticipantRef.TableBound` participant is
*participant-local* when the discriminated interface does not declare it, and *interface-declared*
when it does (SDL forces interface fields onto every implementer, so every participant field name
is exactly one of the two).

Where the mechanism lives, from the survey: the participant fold (`fields.addAll(...)` into a
`LinkedHashSet<Field<?>>`, one `$project` call per branch with the shared grouped map) has two
implementations serving five emitted bodies: `DiscriminatedTableFragments.fieldsList` (render
tree; root launcher, batched discriminated child, the two DML follow-ups) and
`TypeFetcherGenerator.buildTableInterfaceReprojection` (legacy tree; the child twin and, via
`MultiTablePolymorphicEmitter`, the `@service` single-table-interface fetcher). Every `__rk_`
write is in `ProjectionUnitRenderer`'s `$project` arms; every read is in `FetcherEmitter` (the
single-record and pivot unwraps, and `columnByAlias`). The prefix is single-homed in
`ReservedAliases.RESULT_KEY_PREFIX` with a legacy-tree re-read in
`GeneratorUtils.RESERVED_RK_ALIAS_PREFIX`.

### 1. The fold hands each participant a type-scoped selection map

Both fold implementations pass each branch's `$project` a per-participant restriction of the
selection instead of the shared grouped map, reusing the generated
`PolymorphicSelectionSet.restrictTo(source, concreteTypeName)` that the multi-table stage-2
per-typename SELECT already feeds into the same `$project` contract (the wrapper exists precisely
because `$project` recurses through `SelectedField.getSelectionSet()`, so a bare filtered map
would not survive the contract; see `PolymorphicSelectionSetClassGenerator`'s design note).
Check the helper's `EmitPlan` gating: it must be emitted whenever a discriminated interface
exists, not only when the multi-table route does.

What this buys beyond hygiene: a participant no longer projects a field nothing selected on it
(the second observed property in the Backlog body); diverging per-type sub-selections
(`... on DokumentMelding { soknad { soknadId } } ... on MangelMelding { soknad { tittel } }`)
reach each arm already scoped to its own occurrences; and the `requireConsistentArguments` guard
becomes per-type, so `... on A { s(x: 1) } ... on B { s(x: 2) }` stops being a spurious
`GraphitronClientException`.

Restriction alone does not fix the defect: when both types genuinely select the field, both arms
still fire and still mint one alias. Hence part 2.

### 2. Participant-local `__rk_` arms carry the declaring type in the alias

For participant-local fields in the four `ResultKeyAliasedField` families (`TableField`,
`PivotField`, `ComputedField`, Direct-compaction `ColumnBackedReferenceField`), the write side
aliases `"__rk_" + <TypeName> + "$" + <resultKey>` and the read side reads the same, replacing
the shared `"__rk_" + <resultKey>` on both sides. This extends the codebase's own precedent into
the result-key namespace: the scalar cross-table participant route already aliases
`"<TypeName>_<fieldName>"` (`ParticipantRef.TableBound.CrossTableField`) with a type-conditioned
runtime gate, and the Backlog body names it as the convention the `__rk_` path does not follow.

* **The qualification is per `(type, field)` and unconditional.** A participant type queried
  directly (outside the fold) writes and reads the same qualified alias, so no context threads
  through `$project` or the fetchers; both sides derive the alias from the same static model
  fact.
* **Interface-declared fields stay unqualified and shared.** Every participant's arm mints
  identical SQL there (same base table, same resolved path), the fold's `LinkedHashSet`
  collapses them to one term, and the shared read keeps working: today's behaviour, now backed
  by backstop 3a instead of by luck.
* **Write side.** `ProjectionCommands.contributionFor` (which has `schema` and `anchorTypeName`
  in hand) mints the reader-addressing prefix onto the contribution row for a participant-local
  field; `ProjectionUnitRenderer` emits the carried prefix instead of composing `"__rk_"`
  inline. `TermAlias` stays a two-value enum; the prefix is orthogonal to *whether* a term
  aliases.
* **Read side.** `FetcherEmitter.bind` has no schema handle, so the qualifier arrives as data
  from a caller that has one, or stamped on the `ChildField`; implementer's choice. The alias
  *composition* is single-homed beside `ReservedAliases.RESULT_KEY_PREFIX` (and the
  `GeneratorUtils` re-read), so write and read cannot drift, and the existing membership guards
  (`requireAliasedWriteArm`, `FetcherEmitter`'s `ResultKeyAliasedField` fall-through throw) keep
  covering both halves. The qualified reads must use by-name lookups (`record.get(String, ...)`
  or a `DSL.name`-based field); `columnByAlias` currently builds a plain-SQL
  `DSL.field(String)`, which should move to the name-based form in the same change.
* **Delimiter `$`, not `_`.** GraphQL names cannot contain `$`, so `__rk_<Type>$<key>` is
  injective by construction and disjoint from every unqualified `__rk_<key>` (a client alias
  spelling `Type$x` is unrepresentable). With `_`, participants named `Fan` and `Fan_X` plus
  crafted client aliases re-admit exactly the silent name-equality dedupe this item exists to
  kill, and no build-time census can see runtime result keys. `$` is legal in a Postgres
  identifier and in the emitted Java string literal.

**Why not uniform qualification** (qualifying every `__rk_` arm on every participant,
interface-declared included): it would make divergent re-declarations of interface-declared
fields correct rather than rejected and would need no interface-declaredness fact, but every
inherited reference would project once per participant, N-1 redundant correlated subqueries
executed per row on interfaces with many implementers, purely to serve a shape (participants
contradicting their interface's resolved path) that authorship guidance should reject anyway.
The selective rule keeps the shared single term for the agreeing case, and backstop 3a makes the
disagreeing case loud.

### 3. Validator backstops: the residue is a build error, never silent

1. **Sibling agreement on interface-declared names.** The unqualified shared term is sound only
   while every participant's arm for an interface-declared field name mints an identical term.
   A participant re-declaring such a field with a divergent `@reference` path breaks that and
   silently collides again. New `GraphitronSchemaValidator` pass: for each single-table
   discriminated interface and each field name it declares, every `TableBound` participant's
   classified projection for that name must agree with its siblings' (projection identity:
   resolved path, condition, arguments); disagreement is an author error naming the disagreeing
   declarations. Precedent for the shape: `validateProjectionUnitAddresses` and
   `validateLauncherMethodNames` (census, collision, author-facing error located at the
   colliding declarations).
2. **Joined-table first-wins census.** `JoinedTableReprojection.of` dedupes same-named
   participant reference fields first-wins by bare field name (`seenAliases`); that is the same
   silent-drop shape on the joined route, currently masked because the in-tree fixtures agree on
   the base column. Extend the fold: when `seenAliases` blocks a term whose projection identity
   differs from the surviving term's, emit a `Deferral` (the fold's existing validator-drained
   channel) instead of dropping silently; true duplicates keep deduping. Qualifying the joined
   route is deliberately not built here.

## Implementation

File-by-file, from the survey:

* `rewrite/GraphitronSchema.java`, or a small post-walk fold beside `JoinedTableReprojection`:
  the participant-local lookup (`(typeName, fieldName)` to optional qualifier), single home for
  the model fact.
* `plan/ProjectionCommands.java`: mint the prefix onto contributions in `contributionFor`.
* `render/ProjectionUnitRenderer.java`: emit the carried prefix in the `BY_RESULT_KEY` column
  arm and the multiset, lookup-multiset, pivot-multiset, helper-call, and scalar-subselect arms.
* `render/DiscriminatedTableFragments.java` and
  `rewrite/generators/TypeFetcherGenerator.java` (plus the `MultiTablePolymorphicEmitter` call
  site): `restrictTo` at the fold, per branch.
* `rewrite/generators/FetcherEmitter.java`: qualified reads in the single-record unwrap, the
  pivot unwrap, and `columnByAlias` (moved to a by-name lookup).
* `command/ReservedAliases.java` plus the `GeneratorUtils` re-read: the single-homed alias
  composition and the namespace-disjointness javadoc.
* `rewrite/GraphitronSchemaValidator.java`: pass 3.1.
* `rewrite/JoinedTableReprojection.java`: census 3.2.

## Tests

* **Execution fixture** (`graphitron-sakila-db/src/main/resources/init.sql` +
  `graphitron-sakila-example/src/main/resources/graphql/schema.graphqls`): extend the fan
  family with the repro shape. New table `fan_target(fan_target_id, label)`; two FK columns on
  `fan_base` (`alpha_target_id`, `beta_target_id`) referencing it; `FanAlpha.target: FanTarget`
  and `FanBeta.target: FanTarget` over the two FKs, seeded so each participant resolves a
  different row. This keeps FanBeta's existing "no cross-table scalar" property (`note` stays
  FanAlpha-only), and existing fan baselines never select the new columns. No in-tree
  single-table participant carries a `__rk_`-family field today, so alias churn in existing
  baselines is nil.
* **Execution tests** (`GraphQLQueryTest`): both types return their own row (the Backlog
  payload, corrected); per-type sub-selection divergence; aliased duplicates inside one
  fragment (`a: target b: target`); per-type argument divergence not raising the
  consistent-arguments client error; the `@splitQuery` variant staying green; an adversarial
  client alias against the qualified namespace.
* **SQL baseline** (`RootLauncherSqlBaselineTest`): both qualified terms present, each
  correlated on its own FK; and the over-projection pin: a query selecting the field on only
  one fragment carries only that participant's term.
* **Render unit** (`RootLauncherRendererTest`): a two-branch discriminated row (every existing
  case builds exactly one branch, which is why the fold's drop was never pinned), asserting the
  restricted-map call per branch and the distinct aliases.
* **Pipeline**: the participant-local lookup's verdicts (local vs interface-declared, including
  a type participating while also queried directly); validator 3.1 rejection plus an
  agreeing-redeclaration control; census 3.2 deferral plus a true-duplicate control.

## Out of scope

* R708's depth leak: the grouped map still flattens every depth below the unit; this item
  scopes the *fold* by type and nothing else. R708 keeps its own survey and fix.
* The original report's `@splitQuery` + per-type `@condition` variant (unreproduced; needs its
  own trace, as the Backlog body already states).
* PostgreSQL's 63-byte identifier truncation: a pre-existing exposure of the `__rk_` scheme;
  the qualifier consumes more of the budget but changes nothing in kind.
* Qualifying the joined-table route (census 3.2 makes its residue loud instead).

## Acceptance

* The Backlog body's repro schema returns both types' `soknad` populated from their own FK
  paths, and the emitted statement carries both correlations under distinct aliases.
* A one-fragment query projects only that participant's term.
* Sibling disagreement on an interface-declared name and joined-route projection disagreement
  both fail the build with author-facing errors.
* Full `mvn install -Plocal-db` green.
