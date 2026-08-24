---
id: R749
title: "Same-named fields on two participants of one discriminated interface collide on the __rk_ alias, and one join path is silently dropped"
status: In Review
bucket: bug
priority: 8
theme: codegen-correctness
depends-on: []
created: 2026-08-20
last-updated: 2026-08-24
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

Three facts composed. The interface fetcher folded every participant's `$project` into one
`LinkedHashSet<Field<?>>`; each participant minted the same `"__rk_" + resultKey` alias, carrying
the result key and nothing about the declaring type; and an aliased jOOQ field
(`org.jooq.impl.FieldAlias`) compares `equals`/`hashCode` on its alias alone. So two terms
rendering different SQL compared equal, the set's second `addAll` was a no-op, and the losing
participant's join path never reached the statement while its fetcher still read
`"__rk_" + resultKey` off the winner's column.

Three properties of the defect that shaped the fix: the winner was not author-controllable
(reordering the SDL does not reorder the fold); a participant projected its arm even when nothing
selected the field on it, the grouped selection map being keyed by result key with no type
qualification; and `@splitQuery` on both fields was already clean, which bounded the defect to the
inline multiset route and gave consumers a workaround.

## Shipped

The design argument that produced the fix is in this item's history (`91e3ad12` through `a2f9629e`);
what follows is what landed, so the Done gate reads the tree rather than the argument. Round 1 of
the review verified all three parts and the four deviations against the tree and found them correct;
its findings are below. The parts are not separable: part 2's shared alias is sound only under
part 1's restriction and part 3's census.

**Part 1, the fold hands each participant a type-scoped selection map** (`bb0e9d47`). The fold's one
per-branch call site, `DiscriminatedTableFragments.fieldsList`, which every emitted body routes
through, passes each branch's `$project` a restriction of the selection rather than the shared
grouped map, reusing the generated `PolymorphicSelectionSet.restrictTo` that the multi-table stage-2
per-typename SELECT already feeds into the same contract. The invariant it protects, stated in the
assembly's javadoc: *a shared alias requires a shared occurrence set*.

The restriction's polarity is inverted from the plan's, and this is a deviation the reviewer
confirmed is the better answer. The plan transmitted the *shared* field names, kept whole in every
arm; the shipped `restrictTo(source, typeName, perTypeFieldNames)` transmits the complement,
restricting only the names whose alias the participant type qualifies and passing everything else
through whole. The plan's polarity missed a third population: a key a *spliced* nesting unit
contributes keeps a bare (shared) alias in every arm, so restricting it would recreate the very
finding the settlement was built on, two arms rendering different SQL under one alias, and would
convert a case that is loud today into silent wrong data, because merging every occurrence is what
lets `requireConsistentArguments` see cross-type argument divergence inside a spliced subtree. The
complement makes the invariant hold by construction over all three populations, and an unavailable
fact degrades to "restrict nothing", the pre-existing behaviour rather than a guess. Cost taken
knowingly: the stage-2 two-arg form is no longer a degenerate call of the selective one, so it stays
its own arity over one private filter whose null sentinel means "restrict every entry".

The restriction rides `SelectionRestriction(helper, perTypeFieldNames)`, a nested record on the
`LaunchSource.DiscriminatedTable` arm rather than the plan's bare name set, because the renderer also
needs the generated helper's class and no render-tier caller of the assembly holds an
`outputPackage`; it follows the `ResultShape.Connection` precedent of carrying a generated helper as
a `UnitRef` on the command.

**Part 2, every `__rk_` alias minted in a participant's own projection unit is keyed by the
coordinate that owns it** (`bb0e9d47`). The alias is `"__rk_" + <owner> + "$" + <resultKey>`, owner
being the declaring interface's name for an interface-declared field and the participant type's name
for a participant-local one, across all four `ResultKeyAliasedField` families, write and read alike.
Non-participants keep the bare form; their select lists never merge with a sibling's. The delimiter
is `$`, which GraphQL names cannot contain, so the namespace is injective by construction and
disjoint from every bare `__rk_<key>`; `$` only ever arrives as a JavaPoet `$S` argument.

The verdict is a model fact, stamped once at capture as `AliasOwner` (`Shared` /
`QualifiedBy(owner)`) on the `ResultKeyAliasedField` marker, so a new alias-minting family fails
compilation until it declares its namespace verdict. Deviation from the plan, and an improvement on
it: the stamping happens in `FieldBuilder.aliasOwnerOf`, the sole construction site of the four
families, off a `BuildContext.aliasOwnerByParticipant` index that `TypeBuilder` builds in the same
discriminated-interface scan that already builds `crossTableFieldsByParticipant`. That reuses the
real participant classification instead of re-deriving the `TableBound` / `JoinedTableBound` fork
from the SDL. Downstream carriers copy and never re-derive: the stamped value rides `Contribution`,
`ProjectionUnitRenderer` emits what it is handed, and `FetcherEmitter`'s three read sites
(single-record unwrap, pivot unwrap, `columnByAlias`, the last moved to a name-based lookup) read
`aliasOwner()` off the field they are already binding, so `bind`'s second call site needs no edit.
The prefix is composed by one mint function, `ReservedAliases.resultKeyPrefix`, with the
`GeneratorUtils` re-read for the legacy tree.

Spliced nesting units keep the bare alias deliberately: their fields are declared on a
non-participant type, graphql-java registers one data fetcher per coordinate, and one nesting type
may be embedded under several anchors, so an anchor-dependent alias would have no single read site to
agree with. Part 3's census is what makes that safe.

**Part 3, the validator census** (`0cf1b65b`). Three arms, each shipped where its terms are minted
and drained by the validator, following the in-tree write/validate pair precedent rather than a
standalone pass. Sibling agreement on every interface-declared field name across a participant set;
the same agreement on every `__rk_` key two participants can contribute through *spliced* nesting
units, which is the only guard those keys have; and the mixed-participation arm. Rejections are
`Rejection.Deferred`, not author errors: two participants resolving one interface field over
divergent paths is a legal schema that uniform qualification would support, so the rejection means
"the generator does not emit this yet" and retires cleanly.

The third arm has no reachable population today, and ships as a backstop. For a type that is
`TableBound` in one discriminated interface, `TypeBuilder.extractCrossTableFields` claims every
single-hop `@reference` terminating off that interface's base, so the coordinate classifies as
`ParticipantColumnReferenceField`, which is not a result-key-aliased family member at all and mints
no base-slice inherited reference. It stays because it is cheap and keyed on the disagreement rather
than on the participation topology, so it costs nothing while empty; the pipeline test pins where the
mixed shape is actually routed instead, so a change that moves the coordinate back into the
result-key family surfaces there rather than as a read of an alias nothing wrote.

**Fixture, tests, and the author-facing promise** (`0cf1b65b`). The fan family gained `fan_target`
plus two FK columns on `fan_base`, so each participant declares its own `target`; execution pins in
`GraphQLQueryTest`, SQL baselines in `RootLauncherSqlBaselineTest`, two-branch render cases in
`RootLauncherRendererTest`, and the owner fact as model data plus the write/read enforcer in
`ParticipantAliasOwnerPipelineTest`. `code-generation-triggers.adoc` states the per-participant alias
namespace and the promise split part 1 creates: *declaration*-time divergence on an
interface-declared name is a build error, *query*-time divergence on one stays a runtime client
error, and where the field is declared decides which half applies.

## Round 2: the argument pin now stands on the relaxation

Round 1 found `divergingArgumentsDoNotRaiseTheConsistencyError` asserting nothing about the property
it names, and the fan fixture unable to express it: the only argument-carrying field on `FanItem` was
the interface-declared `details`, whose arm keeps every occurrence by construction, while the
participant-local `target` and `targetLoaded` carry no arguments, so the guard was never emitted into
their arm at all. Closed by giving the fixture a same-named participant-local field that carries one.

`fan_mark` is one child table reached over two FKs into `fan_base` (`alpha_base_id`, `beta_base_id`),
so each participant declares its own `marks` path exactly as each declares its own `target`.
`marks(first: Int): [FanMark!]!` is participant-local, so its alias and therefore its occurrence
bucket are per type, and a paginated list, so `readsSelectedFieldArguments` returns true and the arm
both carries `requireConsistentArguments` and serves the runtime `first` off the canonical
occurrence. Per-participant row counts are three and three, so a `first: 1` against a `first: 2`
resolves two page sizes that neither the sibling's argument nor a dropped limit reproduces.

What the pin now asserts, and why each half is load-bearing. That the divergence raises no client
error is the relaxation itself. That the ALPHA row returns exactly its own one row and the BETA row
exactly its own two is the stronger half: per-type page sizes are reachable only if each arm read its
own occurrence's arguments, so the assertion pins the property rather than the absence of an error.
Merged, as before this item, the canonical occurrence's `first` would be served to both types and the
counts would agree with each other instead.

Verified in the emitted tree rather than assumed, which is what round 1's finding was about: the fold
emits `Set.of("marks", "target")` as its per-type restriction, so `marks` is stamped participant-local
alongside `target`; each participant's `marks` arm carries `requireConsistentArguments`, reads
`sf.getArguments().get("first")`, correlates on its own FK column, and aliases
`"__rk_Fan<Alpha|Beta>$"`; and each participant's fetcher reads back the alias its own arm wrote. The
matched control is the unchanged `interfaceDeclaredKey_divergingArgumentsStillRaiseTheConsistencyError`:
the same guard on the same shape, differing only in that an interface-declared key is not restricted,
and it still raises.

Also settled in this round, both from round 1's bookkeeping: this collapse of the plan body, with the
four deviations folded into the parts they belong to rather than left as an appendix to reconcile; and
the `code-generation-triggers.adoc` paragraph on the mixed-participation deferral, which now states
that no schema reaches it today so a reader stops looking for one.

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
* A *participant-local* key that carries an argument, selected with divergent per-type arguments,
  raises no client error *and* resolves each type's own page size. The second half is what makes
  the pin stand on the property rather than on the absence of an error: per-type page sizes are
  reachable only if each arm read its own occurrence's arguments. Its fixture field is
  participant-local and paginated, so the arm it exercises does carry the argument guard, and the
  interface-declared control raises on the same guard.
* A coordinate the joined route serves bare while its stamped owner is qualified (a type
  `TableBound` in one discriminated interface, `JoinedTableBound` in another, with a
  `__rk_`-family reference the joined route re-projects) fails the build as a deferred rejection;
  the same mixed shape without such a coordinate builds and executes as today.
* The owner fact is asserted as model data at the pipeline tier, and the write/read enforcer
  holds: both emitted halves spell the one stamped owner.
* Full `mvn install -Plocal-db` green.

## Reviewer findings

### Round 1 (In Review -> Ready)

Question 1 (is the implementation correct, and is it the change the spec approved) passes. The
alias-owner fact is stamped once in `FieldBuilder.aliasOwnerOf` off the
`BuildContext.aliasOwnerByParticipant` index that `TypeBuilder`'s existing discriminated-interface
scan builds, so the `TableBound` / `JoinedTableBound` fork is the real participant classification
rather than a second SDL derivation; `ReservedAliases.resultKeyPrefix` is the single mint both the
write side (`ProjectionUnitRenderer`) and the read side (`FetcherEmitter`) compose from; all four
`DiscriminatedTable` mint sites carry the restriction, including the
`TypeFetcherGenerator.buildTableInterfaceReprojection` delegate; and the three census arms land where
their terms are minted. The four deviations in `## Implementation notes` are all defensible, and the
inverted restriction polarity in particular is a better answer than the spec's: transmitting the
per-type names rather than the shared ones keeps "a shared alias requires a shared occurrence set"
true over the spliced-unit population too, which the spec's polarity would have broken.

Question 2 (how do we know the item is complete) is where this goes back.

**One of the spec's six named execution pins does not test what it is named for, and the fixture
cannot express it.** The `## Tests` section lists "per-type argument divergence not raising the
consistent-arguments client error" as an execution pin, and adds, specifically: "The per-type
`requireConsistentArguments` behaviour change is its own pin (execution tier), not a rider on the
correctness cases." The delivered
`GraphQLQueryTest.fanItems_sameNamedParticipantFields_divergingArgumentsDoNotRaiseTheConsistencyError`
selects `details(first: 1)` on `FanAlpha` and `target` on `FanBeta`: two different field names, one
occurrence each, and its only assertion is `hasSize(4)`. Nothing in it can raise
`requireConsistentArguments` before or after this item, so it would pass just as well on the
pre-change tree.

The fixture cannot express the case as drafted. On `FanItem` the only argument-carrying field is
`details`, which the interface declares, so its owner is `QualifiedBy("FanItem")` and every arm still
merges every occurrence: that is the *other* half of the split, and it is pinned correctly by
`fanItems_interfaceDeclaredKey_divergingArgumentsStillRaiseTheConsistencyError`. The participant-local
fields (`target`, `targetLoaded`) carry no arguments, so `readsSelectedFieldArguments` never emits the
guard into their arm and the relaxation is unobservable on them.

This is not a naming quibble, for two reasons. First, the relaxation is now an author-facing promise:
the `code-generation-triggers.adoc` table this item added states that a query selecting "a
participant-declared field with different arguments or sub-selections per type" resolves per type. The
sub-selections half is pinned (`...divergingSubSelectionsResolvePerType`); the arguments half is
documented and unguarded. Second, a test carrying the property's name while asserting nothing about it
is worse than no test: it reads as coverage to the next person who touches the restriction.

To fix: give both participants a same-named participant-local field that carries an argument (a
paginated `@reference` off `fan_base` not declared on `FanItem`), then assert that
`... on FanAlpha { <field>(first: 1) { ... } } ... on FanBeta { <field>(first: 2) { ... } }` returns
both types' rows rather than raising `GraphitronClientException`. If the relaxation turns out not to
hold, that is the more valuable finding and belongs in the body.

### Bookkeeping, to settle in the same round

The plan body is still written in the future tense throughout parts 1-3 and the Implementation and
Tests sections, with `## Implementation notes` appended rather than the shipped prose collapsed.
`roadmap/workflow.adoc` ("Publishing", step 3) has the implementer "update the plan (remove shipped,
keep pending)". Reading forward through a full design argument to find out what actually landed, then
reconciling it against a four-item deviation appendix, is most of the cost of this gate. Collapse the
shipped parts to `shipped at <sha>` notes and leave standing only the remaining work (the pin above)
before the next handoff.

### Non-blocking notes, no action required

* `CommandSeamRatchetTest.PLAN_LEAF_REFERENCES` goes 138 -> 147. The javadoc justifies all nine and
  four of them are the projection-identity switch, which is genuinely the point. Worth naming only
  because the ratchet is meant to trend down.
* The new `RootLauncherRendererTest` cases assert on emitted body strings
  (`java.util.Set<java.lang.String> perTypeFields = java.util.Set.of("target")`), which
  `development-principles.adoc` bans "at every tier". The file already does this in fourteen places,
  the spec named these pins explicitly and they were signed off at Spec -> Ready, and the behaviour is
  independently pinned at the SQL-baseline and execution tiers, so this is the file's pre-existing
  norm rather than a regression introduced here. Not a reason to hold the gate.
* The `code-generation-triggers.adoc` closing paragraph presents the mixed-participation deferral as a
  shape an author can hit, while `## Implementation notes` item 2 establishes that the arm has no
  reachable population. True as written (the backstop does defer if reached), but a reader will look
  for a schema that triggers it and not find one.
