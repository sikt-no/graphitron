---
id: R749
title: "Same-named fields on two participants of one discriminated interface collide on the __rk_ alias, and one join path is silently dropped"
status: Ready
bucket: bug
priority: 8
theme: codegen-correctness
depends-on: []
created: 2026-08-20
last-updated: 2026-08-21
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

3. **An aliased jOOQ field compares equal on its alias alone, so the set drops the second one.**
   `field.as(alias)` yields an `org.jooq.impl.FieldAlias`, whose `equals` and `hashCode` read the
   alias and nothing else; unaliased query parts fall back to rendered-SQL equality, which is why
   the drop is specific to the aliased families. The two
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
to that type (reusing an existing generated helper; interface-declared names stay exempt so a
shared alias always covers a shared occurrence set), every `__rk_` alias minted inside a
`TableBound` participant's own projection unit carries the coordinate that owns the projection
decision, and a validator census turns the residual silent shapes into build errors. Vocabulary
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
and stay bare, for the read-side reason part 2's spliced-units bullet gives. The spec incorporates
two principles consultations: the first surfaced the joined-table route's sibling defect, split
out (see Out of scope); the second shaped the gate settlements below (the shared set as a
projection over the stamped owner, and the third census arm keyed on the disagreement rather than
the participation topology).

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

Second gate finding, on that one out-of-unit write: it stays bare while the *field's* stamped
owner decides the read, and the `TableBound` / `JoinedTableBound` fork is per interface
(`TypeBuilder.buildParticipantList` forks on whether the type's own table denotes the interface's
base). So a type that is `TableBound` in one discriminated interface and `JoinedTableBound` in
another carries qualified fields and a bare base-slice write for the same coordinate, and its one
registered fetcher then reads an alias that route never mints. Nothing rejects that double
participation, the same fact part 2's representative rule rests on. Settled: the shape defers,
and the arm is keyed on the disagreement, not the participation topology. Part 3 gains a third
census arm that is the validate-time mirror of part 2's own enforcer (write alias equals read
alias, per coordinate): a coordinate whose stamped owner is `QualifiedBy` and that the joined
route serves through the bare `BaseSliceTerm.InheritedRef` write is a write the registered
fetcher's qualified read can never find, and defers. The population is read off
`JoinedTableReprojection.of`, which mints those terms with the field in hand. Keying on the
coordinate keeps the rejection true: a doubly-participating type carrying no `__rk_`-family
coordinate on the joined route emits correctly today and after this item, and stays accepted,
where a type-grain rejection would defer it on a claim ("the generator does not emit this yet")
that is false for that schema. The other two options are rejected: not shown unreachable, because
nothing rejects double participation and a proof would pin `buildParticipantList` internals the
model does not promise; not fixed by carrying the stamped owner onto `BaseSliceTerm.InheritedRef`,
because that extends qualification into the joined route this item scopes out to R752, whose fold
carries its own first-wins defect and whose read side would need the same census that item owes.
The deferral retires by itself if that item's fix mints owners on the joined route.

### 1. The fold hands each participant a type-scoped selection map

The fold's one per-branch call site (`DiscriminatedTableFragments.fieldsList`, which every
emitted body routes through; `TypeFetcherGenerator` holds no `$project` call of its own) passes
each branch's `$project` a per-participant restriction of the
selection instead of the shared grouped map, reusing the generated
`PolymorphicSelectionSet.restrictTo(source, concreteTypeName)` that the multi-table stage-2
per-typename SELECT already feeds into the same `$project` contract (the wrapper exists precisely
because `$project` recurses through `SelectedField.getSelectionSet()`, so a bare filtered map
would not survive the contract; see `PolymorphicSelectionSetClassGenerator`'s design note). The
seam is already in place: swap `ProjectionCall.fromEnvSelection` for `ProjectionCall.fromSelectionSet`
with the `restrictTo` expression, the pair the stage-2 emitter already uses. The helper keeps a
field whose `getObjectTypeNames()` contains the concrete type, and graphql-java's normalisation
puts every implementer on an interface-level selection, so interface-declared fields survive the
restriction in every arm.
Check the helper's `EmitPlan` gating: it must be emitted whenever a discriminated interface
exists, not only when the multi-table route does (as of this revision `EmitPlan` emits it
unconditionally, so the check should resolve trivially).

What this buys beyond hygiene, scoped honestly by the settlement below: for participant-local
keys, a participant no longer projects a field nothing selected on it (the second observed
property in the Backlog body). Interface-declared keys stay exempt from the restriction, so
over-projection survives on exactly those keys; that residue is R708's territory, not this
item's.

**Settled at the Spec to Ready gate: restriction granularity matches alias granularity.** The
finding that forced the settlement: two further wins this part claims, diverging per-type
sub-selections
(`... on DokumentMelding { soknad { soknadId } } ... on MangelMelding { soknad { tittel } }`)
reaching each arm already scoped to its own occurrences, and a per-type
`requireConsistentArguments` that stops raising a `GraphitronClientException` on
`... on A { s(x: 1) } ... on B { s(x: 2) }`, hold only for keys whose alias part 2 makes
per-type. On an *interface-declared* key, where part 2 keeps one shared alias in every arm, they
are this item's own defect recreated. The fold hands each arm its restricted occurrence list, the
arm builds its multiset inner select from
`SelectionOccurrences.mergeByResultKey(<that arm's occurrences>)` (`ProjectionCall.fromOccurrences`),
so the two arms render different SQL under one alias and the `FieldAlias` alias-only equality
drops the second. Today both arms merge *every* occurrence of the key, so the one surviving term
selects the union and both types read it correctly; with restriction plus a shared alias the
losing type's sub-selection is gone (a per-row jOOQ "not contained in row type", or null) and its
divergent argument silently reads the winner's rows, trading a loud client error for wrong data.
Part 3's census cannot catch it: the declarations agree, and the divergence is a client fact no
build-time check can enumerate.

The invariant, stated in the assembly's javadoc and made structural: a shared alias requires a
shared occurrence set. The settlement restricts only the keys whose alias is per-type.

The generated helper gains an arity, `restrictTo(source, concreteTypeName, sharedFieldNames)`: an
occurrence is kept in every arm when its *field name* is in the set, and filtered to the concrete
type otherwise. The filter runs on `sf.getName()`, never on the map key, because the map key is a
client-minted result key and `x: interfaceField` must stay exempt; the parameter name and the
generated javadoc both say field names for exactly this reason. The existing two-arg form becomes
`return restrictTo(source, typeName, Set.of())`, so the emitted artifact carries one filter rule
with a degenerate call and the stage-2 consumer's no-exemption behaviour is spelled rather than
reviewed.

The set itself is not recomputed from the interface declaration: "is this name interface-declared"
is the very verdict part 2 stamps as `AliasOwner`, and a second derivation would agree today and
drift silently the day a new `__rk_`-minting family misses it, with no enforcer. Instead the set
is a projection over the stamped fact, the field names of the fold's participants whose
`aliasOwner()` is `QualifiedBy` with this interface as owner, carried onto
`LaunchSource.DiscriminatedTable` at its mint sites (each already holds the participants), and
rendered by `fieldsList` into the `restrictTo` call. In the emitted body the set is hoisted to one
`private static final Set<String>` per interface rather than a per-arm literal: it is the
interface's fact, not the participant's, and the hoist drops a per-request allocation per arm.

Consequences, per ownership rather than claimed uniformly. Participant-local keys get both wins:
each arm sees only its own occurrences, so diverging per-type sub-selections resolve per type, and
per-type argument divergence stops raising the consistent-arguments client error, because nothing
merges across types any more. Interface-declared keys keep today's behaviour exactly: every arm
merges every occurrence, the one surviving shared-alias term selects the union, both types read it
correctly, and cross-type argument divergence still raises `requireConsistentArguments`'s
`GraphitronClientException`. That split is the correct-or-build-error promise's own split, and
author-visible: *declaration*-time divergence on an interface-declared name is a build error (part
3), *query*-time divergence on one stays a runtime client error, and where the field is declared
decides which half applies. The `code-generation-triggers.adoc` edit in the Implementation section
states it in those terms. The rejected routes: uniform per-participant qualification of
interface-declared keys is rejected in part 2 (its redundancy cost stands under restriction, since
graphql-java puts interface-level selections on every implementer, so every arm still projects the
key); exempting shared keys anywhere outside the helper would make the invariant reviewed rather
than structural.

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
agree on projection identity, so the identical terms collapse to one exactly as today. That
collapse also assumes every arm sees the same occurrences of the key, which part 1's settlement
guarantees structurally: interface-declared names are in the restriction's shared set, so every
arm keeps every occurrence. A type
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
  reader (`FetcherEmitter`) consume the stamped string rather than recomputing it. Naming the
  boundary, since the docs use *capture* for `FactCapture`'s transcription: this fact is minted
  on the transitional walk's model in `TypeBuilder`, not as a store relation, because
  `FetcherRegistrationsEmitter`'s `bind` path carries no `ProjectionRelation` and the emit window
  does not read the store (the next bullet's drift argument); when the projection consumers
  migrate to store-sourced facts, the owner becomes a relation derived from the implements edges
  and the field census, which is what this derivation already is.
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

The census's third arm is the settled mixed-participation finding, stated in part 2's enforcer
terms (write alias equals read alias, per coordinate): a coordinate whose stamped owner is
`QualifiedBy` and that the joined route serves through the bare `BaseSliceTerm.InheritedRef`
write defers, because the registered fetcher's qualified read can never find that write. The
population is read off `JoinedTableReprojection.of`, which mints the terms; the arm fires exactly
where a disagreement exists, so a type `TableBound` in one discriminated interface and
`JoinedTableBound` in another with no `__rk_`-family coordinate on the joined route stays
accepted and keeps emitting correctly.

The rejection kind is `Rejection.Deferred` (`RejectionKind.DEFERRED`), not an author error, and
not the unrelated `Severity` enum, whose values are `Error` / `Warning` / `Information` / `Hint`.
Two participants resolving one interface field over divergent paths is a legal, meaningful schema
that uniform qualification would support, so the
rejection means "the generator does not emit this yet" and retires cleanly if that route is ever
built, rather than pinning the schema as illegal and later retracting. The third arm's deferral is
true on the same terms: the coordinate is legal, and emitting it needs the joined route to mint
owners, which is R752's fix to make. The rejection text states
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
* `command/LaunchSource.java` and the four `DiscriminatedTable` mint sites (three in
  `plan/LauncherCommands.java`, one in `TypeFetcherGenerator.buildTableInterfaceReprojection`):
  the record gains the shared field-name set, populated at mint as a projection over the
  participants' stamped `aliasOwner()` facts, copied, never re-derived.
* `render/DiscriminatedTableFragments.java`: the three-arg `restrictTo` at the fold's one
  per-branch call site in `fieldsList`, which every emitted body routes through, including the
  `TypeFetcherGenerator.buildTableInterfaceReprojection` delegate and its
  `MultiTablePolymorphicEmitter` caller (their only edit is the mint-site component above); the
  hoisted per-interface shared-set constant; assembly javadoc stating the two type-scoping
  mechanisms' ownership and the shared-alias-implies-shared-occurrences invariant part 1's
  settlement states.
* `rewrite/generators/FetcherEmitter.java`: owner-keyed reads in the single-record unwrap, the
  pivot unwrap, and `columnByAlias` (moved to a by-name lookup), reading `aliasOwner()` off the
  field. `bind`'s other call site, `rewrite/generators/schema/FetcherRegistrationsEmitter.java`,
  needs no edit for the same reason: the fact arrives on the field, not through the caller.
* `command/ReservedAliases.java` plus the `GeneratorUtils` re-read: the one mint function for
  the composed prefix, the extended namespace-disjointness javadoc, the JavaPoet `$` rider.
* `rewrite/GraphitronSchemaValidator.java`: the validator mirror of backstop 3, draining the
  plan-side census, all three arms; the third arm's population comes from
  `JoinedTableReprojection.of`.
* `rewrite/generators/util/PolymorphicSelectionSetClassGenerator.java`: the
  `restrictTo(source, typeName, sharedFieldNames)` arity, filtering on `sf.getName()`, with the
  two-arg form delegating through `Set.of()` and the generated javadoc saying field names, not
  result keys; consumer list in the class javadoc; verify its `EmitPlan` gating covers
  discriminated-interface-only schemas.
* `PolymorphicProjectionFilterPinTest` (test source): its javadoc and assertion message state
  that the one stage-2 site is every `restrictTo` emit site. Part 1 makes that false without
  making the test fail, because the fold's emit site lands in `render/`, outside the pin's
  non-recursive `rewrite/generators/` scan. Amend both strings in the same commit as the
  generator's class javadoc; the assertion itself still holds at 1.
* `docs/architecture/reference/code-generation-triggers.adoc`: the discriminated-interface
  section gains a statement of the per-participant alias namespace (it currently describes the
  fold's projection with none), and of the promise split part 1's settlement creates:
  declaration-time divergence on an interface-declared name is a build error, query-time
  divergence on one is a runtime client error, and where the field is declared decides which.

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
  client alias against the qualified namespace. Add the settlement's oracle, which the fan
  fixture as drafted cannot see because `target` is participant-local: an *interface-declared*
  `__rk_` field selected with divergent per-type sub-selections returns the merged union on every
  type, and with divergent per-type arguments still raises the consistent-arguments client error
  (the argument relaxation earlier in this list is participant-local-only). Both pins hold
  because the shared set keeps interface-declared occurrences whole in every arm.
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
  `Shared`, the two verdicts that pin the vocabulary's scope on each axis; plus the mixed
  participation verdicts: a type `TableBound` in one discriminated interface and
  `JoinedTableBound` in another whose joined route re-projects a `__rk_`-family reference,
  asserting the third census arm's deferral, and the same mixed shape carrying no such
  coordinate, asserting it stays accepted); the write/read
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
* An interface-declared key selected with divergent per-type sub-selections returns the merged
  union on every type, and divergent per-type arguments on one raise the existing client error;
  the shared set makes a shared alias over per-type occurrence sets unrepresentable by
  construction.
* A coordinate the joined route serves bare while its stamped owner is qualified (a type
  `TableBound` in one discriminated interface, `JoinedTableBound` in another, with a
  `__rk_`-family reference the joined route re-projects) fails the build as a deferred rejection;
  the same mixed shape without such a coordinate builds and executes as today.
* The owner fact is asserted as model data at the pipeline tier, and the write/read enforcer
  holds: both emitted halves spell the one stamped owner.
* Full `mvn install -Plocal-db` green.
