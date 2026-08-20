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
`TableBound` participant's own projection unit carries the coordinate that owns the projection
decision, and a validator census turns the residual silent shape into a build error. Vocabulary
used below: a *participant* is a type that appears as `ParticipantRef.TableBound` in some
single-table discriminated interface's `GraphitronType.TableInterfaceType.participants()`; the
scope is that membership, not the `TableBound` variant alone. A `TableBound` implementer of a
directiveless multi-table `InterfaceType` or `UnionType` (`Film` in `Searchable`) is *not* a
participant here and keeps today's bare alias for the same reason non-participants do: its
stage-2 per-typename SELECT never merges with a sibling's select list, so qualification would be
pure churn. A field on a participant is *participant-local* when no discriminated interface the
type participates in declares it, and *interface-declared* when one does (SDL forces interface
fields onto every implementer, so every participant field name is exactly one of the two). The
scope is per declaring type, which makes it exactly the participant's own anchor projection unit;
fields that reach that unit from a *spliced* nesting unit are declared on a non-participant type
and stay bare, for the read-side reason part 2's spliced-units bullet gives. This spec revision
incorporates a principles consultation; the joined-table route's sibling defect it surfaced is
split out (see Out of scope).

Where the mechanism lives, from the survey: the participant fold (`fields.addAll(...)` into a
`LinkedHashSet<Field<?>>`, one `$project` call per branch with the shared grouped map) is one
assembly serving five emitted bodies: `DiscriminatedTableFragments.fieldsList` renders it (root
launcher, batched discriminated child, the two DML follow-ups through the launcher's reentry
arm), and `TypeFetcherGenerator.buildTableInterfaceReprojection` is a thin legacy-tree delegate
onto `DiscriminatedTableFragments.assembly` (the child twin and, via
`MultiTablePolymorphicEmitter`, the `@service` single-table-interface fetcher), so the fold edit
lands once and cannot drift between trees. Every `__rk_` write inside a projection unit is in
`ProjectionUnitRenderer`'s `$project` arms; the one write outside them is
`DiscriminatedTableFragments`' `InheritedRef` base-slice arm, which serves only
`JoinedTableBound` participants (`JoinedTableReprojection.of` skips every other variant) and so
stays bare under this item (the joined route is out of scope, R752's territory). Every read is
in `FetcherEmitter` (the single-record and pivot unwraps, and `columnByAlias`). The prefix is
single-homed in `ReservedAliases.RESULT_KEY_PREFIX` with a legacy-tree re-read in
`GeneratorUtils.RESERVED_RK_ALIAS_PREFIX`.

### 1. The fold hands each participant a type-scoped selection map

Both fold implementations pass each branch's `$project` a per-participant restriction of the
selection instead of the shared grouped map, reusing the generated
`PolymorphicSelectionSet.restrictTo(source, concreteTypeName)` that the multi-table stage-2
per-typename SELECT already feeds into the same `$project` contract (the wrapper exists precisely
because `$project` recurses through `SelectedField.getSelectionSet()`, so a bare filtered map
would not survive the contract; see `PolymorphicSelectionSetClassGenerator`'s design note).
Check the helper's `EmitPlan` gating: it must be emitted whenever a discriminated interface
exists, not only when the multi-table route does (as of this revision `EmitPlan` emits it
unconditionally, so the check should resolve trivially).

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
in a `TableBound` participant's own projection unit is `"__rk_" + <owner> + "$" + <resultKey>`,
where *owner* is the declaring interface's name for an interface-declared field and the
participant type's name for a participant-local field. Spell the concept `aliasOwner` in code:
`ProjectionCommands.collectContributions` and `contributionFor` already carry a `UnitRef owner`
parameter meaning the enclosing unit, and two `owner`s in one signature is a reading hazard at
the exact site this item edits. Applies to the four `ResultKeyAliasedField` families
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
carry identical SQL and reads stay consistent whichever fold produced the row. The representative
rule's cost, stated so it is chosen rather than discovered: a field two of a type's interfaces
both declare projects once per distinct representative, so a sibling participant of only the
second interface contributes a second term over identical SQL under a different alias. Correct
(backstop 3 forces the identity) and bounded at one term per declaring interface rather than the
per-participant blow-up the rejected uniform rule carries, but it is a real, if exotic,
redundancy on the same axis.

* **The owner is per `(type, field)` and unconditional.** This is forced by the model, not
  convenience: `LaunchSource.DiscriminatedTable.Branch.SingleTable.projection` is the
  participant type's *own* anchor unit, shared with direct queries on that type, so a
  context-dependent alias would make the unit's address a cross-product of type and host. A
  participant queried directly writes and reads the same alias as through the fold.
* **Spliced nesting units stay bare, and part 3 is what makes them safe.** A `CallWrap.Splice`
  arm renders as `fields.addAll(<nestingUnit>.$project(...))`, so a nesting unit's terms land in
  the participant's own field set and from there in the fold's `LinkedHashSet`: the collision
  this item fixes recurs one level down. It is reachable whenever two participants embed
  *different* nesting types that declare a same-named `__rk_`-minting field over divergent paths.
  Embedding the *same* nesting type is already safe, because
  `GeneratedUnits.nestingUnit(anchorTypeName, nestedTypeName)` mints one unit per anchor with
  identical contributions and the set dedupes them correctly. Qualification cannot reach these
  arms: the fields are declared on a non-participant type, graphql-java registers one data
  fetcher per coordinate, and one nesting type may be embedded under several anchors, so an
  anchor-dependent alias has no single read site to agree with. That is the read-divergence shape
  R556 tracks, and buying into it here would be taking on that item's problem to fix this one.
  So these arms keep the bare alias, and part 3's census is extended to cover the keys they
  contribute, which turns the residual case into a build error rather than a silent drop. The
  correct-or-build-error promise holds across the split: qualification for what a participant
  declares, census for what is spliced into it.
* **The decision is minted once, at capture, on the model.** The owner is a pure function of
  `(declaring type, field name)`, and both halves of the alias need it, so by "decide once, at
  capture" and its rule of thumb ("if two consumers evaluate the same predicate over a model
  field, the branch belongs in the model") it is a model fact, not a plan derivation.
  Declaration site: the `ResultKeyAliasedField` marker gains an `AliasOwner aliasOwner()`
  accessor, `AliasOwner` being a small sealed type (`Shared`, `QualifiedBy(String owner)`)
  beside it in `rewrite/model`. Homing it on that marker is what makes the invariant enforced
  rather than reviewed: the marker is exactly the membership of the four `__rk_`-minting
  families, so a new family fails compilation until it declares its namespace verdict, the same
  discipline the marker's existing write/read fall-through throws already enforce for membership.
  The marker's javadoc currently asserts the opposite ("The marker carries no method: the alias
  basis is entirely runtime-keyed ... with no per-variant model value to expose"); retiring that
  sentence is part of this edit. Capture stamps the value where the sibling precedent already
  stamps a type-qualified alias: `TypeBuilder`'s participant walk mints
  `ParticipantRef.TableBound.CrossTableField.aliasName` as
  `participantTypeName + "_" + fieldName`, and both the writer (`LauncherCommands`) and the
  reader (`FetcherEmitter`) consume the stamped string rather than recomputing it.
* **Downstream carriers copy, never re-derive.** `TermAlias` stays the two-value addressing enum
  (its own javadoc says the subselect-shaped terms carry no alias slot, so it cannot host a
  value); the plan copies the stamped `AliasOwner` onto `Contribution`, which every arm already
  carries, so `ProjectionUnitRenderer` emits what it is handed and `ProjectionCommands` computes
  no predicate of its own. This is what closes the drift a plan-homed fact would have opened:
  `FetcherEmitter.bind` has two call sites, `TypeFetcherGenerator` and
  `FetcherRegistrationsEmitter`, and the latter's `emit` entry point builds its own
  `GeneratedUnits` and carries no `ProjectionRelation`, so a plan-homed owner would have to be
  either plumbed through a public signature or re-derived there. Reading it off the field needs
  neither.
* **The string is composed in one place.** A single mint function beside
  `ReservedAliases.RESULT_KEY_PREFIX` (with the `GeneratorUtils` re-read for the legacy tree)
  takes the owner and returns the emitted prefix; neither side spells the delimiter or the
  concatenation. The namespace-disjointness argument extends the existing `ReservedAliases`
  class javadoc rather than being restated at minting sites. JavaPoet rider: `$` is JavaPoet's
  format placeholder, so the delimiter only ever arrives as an `$S` argument, never inside a
  format string; the mint function's javadoc states this.
* **Read side.** `FetcherEmitter.bind` has no schema handle and needs none: it receives the
  `ChildField`, so all three read sites (the single-record unwrap, the pivot unwrap,
  `columnByAlias`) read `aliasOwner()` off the field they are already binding, and both `bind`
  call sites get the fact for free. The existing membership guards (`requireAliasedWriteArm`,
  `FetcherEmitter`'s `ResultKeyAliasedField` fall-through throw) keep covering *whether* both
  halves handle a family; the owner *value* is single-sourced by construction rather than by two
  agreeing derivations, so its enforcer is a pipeline-tier test that both emitted halves spell
  the stamped owner (write alias equals read alias, per coordinate), not a test that two
  computations match. The qualified reads use by-name lookups (`record.get(String, ...)` or a
  `DSL.name`-based field); `columnByAlias` currently builds a plain-SQL `DSL.field(String)` and
  moves to the name-based form in the same change.
* **Delimiter `$`, not `_`.** GraphQL names cannot contain `$`, so the alias is injective by
  construction, up to PostgreSQL's 63-byte identifier limit which this item does not address,
  and disjoint from every bare `__rk_<key>` (a client alias spelling `Owner$x` is
  unrepresentable). With `_`, participants named `Fan` and `Fan_X` plus crafted client aliases
  re-admit exactly the silent name-equality dedupe this item exists to kill, and no build-time
  census can see runtime result keys, so that invariant would have no enforcer. That asymmetry is
  also why the `<TypeName>_<fieldName>` precedent keeps its `_` and is not migrated: it composes
  two SDL names, both visible at build time, so a collision there is censusable, while this
  namespace composes a client-minted result key that no build-time check can enumerate. `$` is
  legal in a PostgreSQL identifier and in the emitted Java string literal.

**Why not uniform per-type qualification** (keying every arm on the participant type,
interface-declared included): it needs no agreement census and makes divergent re-declarations
of interface-declared fields correct rather than deferred, but every inherited reference would
project once per participant, N-1 redundant correlated subqueries executed per row on interfaces
with many implementers, purely to serve a shape (participants contradicting their interface's
resolved path) that should stay a loud deferral until someone needs it. The owner-keyed rule
keeps the shared single term for the agreeing case; backstop 3 makes the disagreeing case loud.

### 3. Validator backstop: sibling agreement on every key two participants share

The interface-keyed shared term is sound only while every participant's arm for an
interface-declared field name mints an identical term. A participant re-declaring such a field
with a divergent `@reference` path breaks that and silently collides again, so this check is not
optional and not splittable: it is the validate-time mirror of the naming decision part 2
introduces and ships with it. For each single-table discriminated interface and each field name
it declares, every `TableBound` participant's classified projection for that name must agree
with its siblings' (projection identity: resolved path, condition, arguments); when a field is
declared by several discriminated interfaces of one type, the census spans every declaring
interface's participant set (part 2's transitivity argument rests on this).

The census has a second, wider arm, for the keys part 2 deliberately leaves bare. Every `__rk_`
key that two `TableBound` participants of one interface can contribute to the same fold must
resolve to one projection identity. For a key a participant declares itself, that is the arm
above and the alias already satisfies it. For a key reaching the fold through a spliced nesting
unit, the census is the only guard there is, so it walks each participant's spliced units and
censuses the keys they contribute the same way. Two participants embedding *different* nesting
types that declare the same key over divergent paths is the shape this arm rejects; embedding the
same nesting type agrees trivially and stays silent.

The rejection kind is `Rejection.Deferred` (`RejectionKind.DEFERRED`), not an author error, and
not the unrelated `Severity` enum, whose values are `Error` / `Warning` / `Information` / `Hint`.
Two participants resolving one interface field over divergent paths is a legal, meaningful schema
that uniform qualification would support, so the
rejection means "the generator does not emit this yet" and retires cleanly if that route is ever
built, rather than pinning the schema as illegal and later retracting. The rejection text states
the fact standalone (which declarations disagree and on what), no roadmap citation. Shape: follow
the in-tree write/validate pair precedent, one derivation read by a producer-side backstop and
the validator mirror (`ProjectionCommands.AddressCensus` plus
`GraphitronSchemaValidator.validateProjectionUnitAddresses`), not a standalone pass with its own
formula.

## Implementation

File-by-file, from the survey:

* `rewrite/model/AliasOwner.java` (new), `rewrite/model/ResultKeyAliasedField.java` and
  `rewrite/model/ChildField.java`: the sealed owner fact (`Shared` / `QualifiedBy(owner)`), the
  marker's new `aliasOwner()` accessor, and the component on the four families; retire the
  marker javadoc's "carries no method" sentence, which this change falsifies.
* `rewrite/TypeBuilder.java` and `rewrite/FieldBuilder.java`: stamp the owner at capture, beside
  the `CrossTableField.aliasName` precedent that already composes a type-qualified alias there.
* `command/Contribution.java` (and the rows `plan/ProjectionCommands.java` mints in
  `contributionFor`): carry the stamped fact through to the renderer, copied, never re-derived;
  the agreement census behind backstop 3 lives beside it with a producer-side backstop throw.
* `render/ProjectionUnitRenderer.java`: emit the carried owner in the `BY_RESULT_KEY` column
  arm and the multiset, lookup-multiset, pivot-multiset, helper-call, and scalar-subselect arms.
* `render/DiscriminatedTableFragments.java` and
  `rewrite/generators/TypeFetcherGenerator.java` (plus the `MultiTablePolymorphicEmitter` call
  site): `restrictTo` at the fold, per branch; assembly javadoc stating the two type-scoping
  mechanisms' ownership.
* `rewrite/generators/FetcherEmitter.java`: owner-keyed reads in the single-record unwrap, the
  pivot unwrap, and `columnByAlias` (moved to a by-name lookup), reading `aliasOwner()` off the
  field. `bind`'s other call site, `rewrite/generators/schema/FetcherRegistrationsEmitter.java`,
  needs no edit for the same reason: the fact arrives on the field, not through the caller.
* `command/ReservedAliases.java` plus the `GeneratorUtils` re-read: the one mint function for
  the composed prefix, the extended namespace-disjointness javadoc, the JavaPoet `$` rider.
* `rewrite/GraphitronSchemaValidator.java`: the validator mirror of backstop 3, draining the
  plan-side census.
* `rewrite/generators/util/PolymorphicSelectionSetClassGenerator.java`: consumer list in the
  class javadoc; verify its `EmitPlan` gating covers discriminated-interface-only schemas.
* `PolymorphicProjectionFilterPinTest` (test source): its javadoc and assertion message state
  that the one stage-2 site is every `restrictTo` emit site. Part 1 makes that false without
  making the test fail, because the fold's emit site lands in `render/`, outside the pin's
  non-recursive `rewrite/generators/` scan. Amend both strings in the same commit as the
  generator's class javadoc; the assertion itself still holds at 1.
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
  one fragment carries only that participant's term. Implementer heads-up on that second
  baseline: after part 1, a participant whose restricted selection comes back empty returns
  `ProjectionUnitRenderer`'s `DSL.inline(1).as("__row_present__")` sentinel, so a query that
  selects no interface-declared field will show that column. The sentinels dedupe across
  participants in the fold's set, so this is a baseline expectation, not a defect.
* **Render unit** (`RootLauncherRendererTest`): a two-branch discriminated row (every existing
  case builds exactly one branch, which is why the fold's drop was never pinned), asserting the
  restricted-map call per branch and the distinct aliases; plus a spliced arm asserting its
  alias stays bare, so the unit-scope split is pinned at the renderer and not only argued.
* **Pipeline**: the owner fact's verdicts as model data (participant-local, interface-declared,
  non-participant, a type participating while also queried directly, the multi-interface
  representative, a `TableBound` implementer of a multi-table interface or union staying
  `Shared`, and a field declared on a nesting type spliced under a participant anchor staying
  `Shared`, the two verdicts that pin the vocabulary's scope on each axis); the write/read
  enforcer (both emitted halves spell the stamped owner, per coordinate); backstop 3's two
  deferrals (a divergent interface-declared redeclaration, and two participants embedding
  different nesting types that collide on one key) each with an agreeing control (an agreeing
  redeclaration, and two participants embedding the same nesting type). The
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
* Two participants that collide on one `__rk_` key through *spliced* nesting units fail the build
  the same way, so no shape covered by this item's promise resolves to a silent drop.
* The owner fact is asserted as model data at the pipeline tier, and the write/read enforcer
  holds: both emitted halves spell the one stamped owner.
* Full `mvn install -Plocal-db` green.
