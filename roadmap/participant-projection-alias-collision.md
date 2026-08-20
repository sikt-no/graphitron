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
* R752 (`joined-table-reprojection-first-wins-drop`) is the joined-route sibling this item's
  spec split out: the same silent first-wins drop in `JoinedTableReprojection`'s base-slice
  dedupe.

---

## Decision: scope the fold's selection per participant, qualify the participant-local alias with the declaring type

The survey the Backlog body asked for is done; its facts are inlined where they bind. The fix is
three coordinated parts: the discriminated fold hands each participant a selection map restricted
to that type (reusing an existing generated helper), every `__rk_` alias minted inside a
`TableBound` participant's projection unit carries the coordinate that owns the projection
decision, and a validator census turns the residual silent shape into a build error. Vocabulary
used below: a field on a `ParticipantRef.TableBound` participant is *participant-local* when no
discriminated interface the type participates in declares it, and *interface-declared* when one
does (SDL forces interface fields onto every implementer, so every participant field name is
exactly one of the two). This spec revision incorporates a principles consultation; the joined-table
route's sibling defect it surfaced is split out (see Out of scope).

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

Two statements this part owns. First, `restrictTo` becomes *the* type-scoping mechanism for
`$project` calls in the discriminated assembly; the cross-table and joined-detail terms keep
their `containsAnyOf` glob gates (which handle depth via `**/`, a dimension the grouped map does
not carry; merging the two mechanisms is R708's territory), and the assembly's javadoc states
that the two coexist deliberately and which owns what. Second,
`PolymorphicSelectionSetClassGenerator`'s class javadoc names the multi-table stage-2 SELECT as
the consumer; amend it in the same commit to name both consumers.

Restriction alone does not fix the defect: when both types genuinely select the field, both arms
still fire and still mint one alias. Hence part 2.

### 2. Every `__rk_` arm in a participant unit is keyed by the coordinate that owns the projection

The rule, one formula with no unqualified fork inside participant units: an `__rk_` alias minted
in a `TableBound` participant's projection unit is `"__rk_" + <owner> + "$" + <resultKey>`, where
*owner* is the declaring interface's name for an interface-declared field and the participant
type's name for a participant-local field. Applies to the four `ResultKeyAliasedField` families
(`TableField`, `PivotField`, `ComputedField`, Direct-compaction `ColumnBackedReferenceField`),
write and read alike. Non-participant types keep today's bare `"__rk_" + <resultKey>`; their
select lists never merge with a sibling's, so the bare form stays sound there and churns nothing.
This extends the codebase's own precedent into the result-key namespace: the scalar cross-table
participant route already aliases `"<TypeName>_<fieldName>"`
(`ParticipantRef.TableBound.CrossTableField`), and the Backlog body names it as the convention
the `__rk_` path does not follow.

Why the two owner cases compose: same-named participant-local fields on two participants key on
two type names, so the fold's set keeps both terms (the defect's fix). Interface-declared fields
key on the one interface name in every participant's arm, and backstop 3 forces those arms to
agree on projection identity, so the identical terms collapse to one exactly as today. A type
participating in more than one discriminated interface is the case that made a plain
qualify-or-not rule ill-defined (`ParticipantRef.TableBound` participation is not unique, and
nothing rejects double participation): under this rule a field declared by several of the type's
interfaces takes a deterministic representative owner (the lexicographically first declaring
interface), and backstop 3's agreement census spans every declaring interface's participant set,
which transitively forces one projection identity, so any two aliases that coexist in one fold
carry identical SQL and reads stay consistent whichever fold produced the row.

* **The owner is per `(type, field)` and unconditional.** This is forced by the model, not
  convenience: `LaunchSource.DiscriminatedTable.Branch.SingleTable.projection` is the
  participant type's *own* anchor unit, shared with direct queries on that type, so a
  context-dependent alias would make the unit's address a cross-product of type and host. A
  participant queried directly writes and reads the same alias as through the fold.
* **The decision is minted once, in the plan.** The owner (or its absence, for non-participant
  units) is a fact the plan computes and carries; nothing re-evaluates the predicate on the read
  side. Carrier: `TermAlias` stays the two-value addressing enum (its own javadoc says the
  subselect-shaped terms carry no alias slot, so it cannot host a value), and the fact rides
  `Contribution` instead, which every arm already carries, as a small sealed type with
  `Shared` and `QualifiedBy(owner)` arms. `ProjectionCommands.contributionFor` (which has
  `schema` and `anchorTypeName` in hand) mints it; `ProjectionUnitRenderer` emits what is
  carried. No new walk-side registry or `GraphitronSchema` accessor: the naming decision is
  derived in the plan from facts the walk already carries
  (`GraphitronType.TableInterfaceType.participants()`, `schema.fieldsOf`).
* **The string is composed in one place.** A single mint function beside
  `ReservedAliases.RESULT_KEY_PREFIX` (with the `GeneratorUtils` re-read for the legacy tree)
  takes the owner and returns the emitted prefix; neither side spells the delimiter or the
  concatenation. The namespace-disjointness argument extends the existing `ReservedAliases`
  class javadoc rather than being restated at minting sites. JavaPoet rider: `$` is JavaPoet's
  format placeholder, so the delimiter only ever arrives as an `$S` argument, never inside a
  format string; the mint function's javadoc states this.
* **Read side.** `FetcherEmitter.bind` has no schema handle, so the fetcher side consumes the
  minted owner as data (threaded from a caller that has the plan fact, or stamped on the
  `ChildField`); it must not re-run the predicate. The existing membership guards
  (`requireAliasedWriteArm`, `FetcherEmitter`'s `ResultKeyAliasedField` fall-through throw) keep
  covering *whether* both halves handle a family; they do not guard the owner *value*, so the
  write/read agreement on the value needs its own enforcer: a pipeline-tier test asserting the
  plan's carried owner equals what the fetcher binding derives, per coordinate. The qualified
  reads use by-name lookups (`record.get(String, ...)` or a `DSL.name`-based field);
  `columnByAlias` currently builds a plain-SQL `DSL.field(String)` and moves to the name-based
  form in the same change.
* **Delimiter `$`, not `_`.** GraphQL names cannot contain `$`, so the alias is injective by
  construction, up to PostgreSQL's 63-byte identifier limit which this item does not address,
  and disjoint from every bare `__rk_<key>` (a client alias spelling `Owner$x` is
  unrepresentable). With `_`, participants named `Fan` and `Fan_X` plus crafted client aliases
  re-admit exactly the silent name-equality dedupe this item exists to kill, and no build-time
  census can see runtime result keys, so that invariant would have no enforcer. `$` is legal in
  a PostgreSQL identifier and in the emitted Java string literal.

**Why not uniform per-type qualification** (keying every arm on the participant type,
interface-declared included): it needs no agreement census and makes divergent re-declarations
of interface-declared fields correct rather than deferred, but every inherited reference would
project once per participant, N-1 redundant correlated subqueries executed per row on interfaces
with many implementers, purely to serve a shape (participants contradicting their interface's
resolved path) that should stay a loud deferral until someone needs it. The owner-keyed rule
keeps the shared single term for the agreeing case; backstop 3 makes the disagreeing case loud.

### 3. Validator backstop: sibling agreement on interface-declared names

The interface-keyed shared term is sound only while every participant's arm for an
interface-declared field name mints an identical term. A participant re-declaring such a field
with a divergent `@reference` path breaks that and silently collides again, so this check is not
optional and not splittable: it is the validate-time mirror of the naming decision part 2
introduces and ships with it. For each single-table discriminated interface and each field name
it declares, every `TableBound` participant's classified projection for that name must agree
with its siblings' (projection identity: resolved path, condition, arguments); when a field is
declared by several discriminated interfaces of one type, the census spans every declaring
interface's participant set (part 2's transitivity argument rests on this).

Severity is `Deferred`, not author error: two participants resolving one interface field over
divergent paths is a legal, meaningful schema that uniform qualification would support, so the
rejection means "the generator does not emit this yet" and retires cleanly if that route is ever
built, rather than pinning the schema as illegal and later retracting. The rejection text states
the fact standalone (which declarations disagree and on what), no roadmap citation. Shape: follow
the in-tree write/validate pair precedent, one derivation read by a producer-side backstop and
the validator mirror (`ProjectionCommands.AddressCensus` plus
`GraphitronSchemaValidator.validateProjectionUnitAddresses`), not a standalone pass with its own
formula.

## Implementation

File-by-file, from the survey:

* `command/Contribution.java` (and the rows `plan/ProjectionCommands.java` mints in
  `contributionFor`): the sealed owner fact (`Shared` / `QualifiedBy(owner)`), computed once in
  the plan from facts the walk already carries; the agreement census behind backstop 3 lives
  beside it with a producer-side backstop throw.
* `render/ProjectionUnitRenderer.java`: emit the carried owner in the `BY_RESULT_KEY` column
  arm and the multiset, lookup-multiset, pivot-multiset, helper-call, and scalar-subselect arms.
* `render/DiscriminatedTableFragments.java` and
  `rewrite/generators/TypeFetcherGenerator.java` (plus the `MultiTablePolymorphicEmitter` call
  site): `restrictTo` at the fold, per branch; assembly javadoc stating the two type-scoping
  mechanisms' ownership.
* `rewrite/generators/FetcherEmitter.java`: owner-keyed reads in the single-record unwrap, the
  pivot unwrap, and `columnByAlias` (moved to a by-name lookup), consuming the owner as data.
* `command/ReservedAliases.java` plus the `GeneratorUtils` re-read: the one mint function for
  the composed prefix, the extended namespace-disjointness javadoc, the JavaPoet `$` rider.
* `rewrite/GraphitronSchemaValidator.java`: the validator mirror of backstop 3, draining the
  plan-side census.
* `rewrite/generators/util/PolymorphicSelectionSetClassGenerator.java`: consumer list in the
  class javadoc; verify its `EmitPlan` gating covers discriminated-interface-only schemas.
* `docs/architecture/reference/code-generation-triggers.adoc`: the discriminated-interface
  section gains a statement of the per-participant alias namespace (it currently describes the
  fold's projection with none).

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
* **Pipeline**: the owner fact's verdicts as plan data (participant-local, interface-declared,
  non-participant, a type participating while also queried directly, the multi-interface
  representative); the write/read value enforcer (the plan's carried owner equals the fetcher
  binding's, per coordinate); backstop 3's deferral plus an agreeing-redeclaration control. The
  per-type `requireConsistentArguments` behaviour change is its own pin (execution tier), not a
  rider on the correctness cases.

## Out of scope

* R708's depth leak: the grouped map still flattens every depth below the unit; this item
  scopes the *fold* by type and nothing else. R708 keeps its own survey and fix.
* The original report's `@splitQuery` + per-type `@condition` variant (unreproduced; needs its
  own trace, as the Backlog body already states).
* PostgreSQL's 63-byte identifier truncation: a pre-existing exposure of the `__rk_` scheme;
  the qualifier consumes more of the budget but changes nothing in kind, and part 2's
  injectivity claim is stated conditionally on it.
* The joined-table route: `JoinedTableReprojection.of` dedupes same-named participant reference
  fields first-wins by bare field name (`seenAliases`), the same silent-drop shape on a
  different producer, currently masked because the in-tree fixtures agree on the base column.
  Its oracle does not exist in-tree and its fix is that fold's own, so it is filed as its own
  item (R752) rather than riding this one's Done gate. Until that item ships, the joined route
  is explicitly *not* covered by this item's correct-or-build-error promise.

## Acceptance

* The Backlog body's repro schema returns both types' `soknad` populated from their own FK
  paths, and the emitted statement carries both correlations under distinct aliases.
* A one-fragment query projects only that participant's term.
* Sibling disagreement on an interface-declared name fails the build as a deferred rejection
  naming the disagreeing declarations.
* The owner fact is asserted as plan data at the pipeline tier, and the write/read value
  enforcer holds.
* Full `mvn install -Plocal-db` green.
