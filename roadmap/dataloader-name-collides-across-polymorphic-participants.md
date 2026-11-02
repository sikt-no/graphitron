---
id: R1132
title: "Same-named split child fields on different polymorphic participants share one DataLoader and resolve to null"
status: In Review
bucket: bug
priority: 1
theme: codegen-correctness
depends-on: []
created: 2026-10-30
last-updated: 2026-11-01
---

# Same-named split child fields on different polymorphic participants share one DataLoader and resolve to null

## Goal

A query that selects the same `@splitQuery` child field on two participants of one polymorphic
field, through two inline fragments, resolves each participant's children from its own table, as a
query selecting either fragment alone already does. Today every participant after the first gets
null, and on a non-null child the null bubbles until the response loses `data`.

A *participant* is one of the object types implementing an interface (or belonging to a union); a
*multitable interface* is one whose participants each sit on their own table; a `@splitQuery` field
is a child field resolved by a separate batched query keyed on the parent rows instead of a join
(the batching goes through a graphql-java `DataLoader`, which collects keys and issues one query).
The shape, mined from kreg (the field that fails is `utdanningsspesifikasjon`, declared on every
participant over its own condition-based reference):

```graphql
{ kompetanseoppnaelseByNasjonalId(nasjonalId: "50920275521") {
    __typename
    ... on Programomraade { utdanningsspesifikasjon { kode } }
    ... on KregFag        { utdanningsspesifikasjon { kode } } } }
```

All 79 `KregFag.utdanningsspesifikasjon` come back null, in either fragment order. Either fragment
alone is correct, and aliasing one side (`u: utdanningsspesifikasjon`) makes both correct, which
places the cause in the loader name. The same happens under a `@referenceFor` multitable child
(`barn { ... on Programomraade {...} ... on KregFag {...} }`). It blocks kreg's move to
Graphitron 10: opptak's production query to kreg selects these fields on both types under `barn`.
A wrong answer on a valid schema, so a regression ahead of planned work.

## Cause

Every batched fetcher names its `DataLoader` from the response path alone and registers it with
`env.getDataLoaderRegistry().computeIfAbsent(name, ...)`. Two participants selected at one path
(`/kompetanseoppnaelseByNasjonalId/utdanningsspesifikasjon`) compute the same name, so the second
participant's fetcher is handed the loader the first created, whose batch function runs the first
participant's rows method against the first participant's table. The second participant's keys match
nothing, and the loader answers null per key.

Two parent types can meet at one response path whenever the field above them is polymorphic
(interface or union) or a `@referenceFor` multitable child, because the same path then carries
rows of several concrete types. Each emission site, verified against the tree:

[cols="3,2,4"]
|===
|Site |Collides? |Reason

|`TenantDslEmitter.loaderNameDeclaration` single-tenant inline form (`String.join("/", ...getKeysOnly())`)
|Yes
|The recipe every `@table` split/child fetcher uses in a single-tenant build. Called by `TypeFetcherGenerator` (four sites) and `MultiTablePolymorphicEmitter` (the polymorphic connection and list registration sites, which are themselves per-participant-parent children).

|`DataLoaderFetcherEmitter` convenience overload (`buildDataLoaderName`)
|Yes, same recipe
|Carries the same inline recipe for callers that pass no resolved declaration. It has no `ctx`, so it cannot take the parent type; if no production caller remains, delete it and make the declaration a required parameter (see Implementation).

|`ConnectionRuntimeClassGenerator.loaderName` / `tenantLoaderName` (multi-tenant carrier, `TenantConnections`)
|Yes
|Every multi-tenant registration site reads the name through this seam, and its recipe is the same path join. `tenantLoaderName` appends the tenant to `loaderName`, so fixing `loaderName` fixes both.

|`QueryNodeFetcherClassGenerator` (`Query.nodes` dispatch, inline join and `loaderName(env)` form)
|No
|The parent is always `Query`, a single object type, so no second parent type can share the path. Left as it is; it keeps calling `loaderName` only if that stays parent-free (see the fork below), otherwise it passes the literal `Query`.
|===

## Implementation

Shipped: the emission sites, the carrier seam, the removal of the convenience overload and its path-only recipe, the unit-tier pins, the `FilmCastMember` execution fixture (`ActorMember.films` and `FilmInventory.films`, both fragment orders plus the aliased control, in `MultiTablePolymorphicJoinedCorrelationExecutionTest`) and the manual section. The `@referenceFor` multitable child shape and the pin for a `@splitQuery` directive on an interface-declared field are not covered by a test yet; the reviewer decides whether that blocks Done. Verification note: `GraphCountGrowthGateTest` in `graphitron-model` (a wall-clock gate unrelated to this change) failed under load in this sandbox and passes alone, so `graphitron-model` was installed without its tests and every downstream module ran in full.

*Fork: where the parent type enters the name.* Two options. (a) Bake the declaring parent type into
the name at generation time, from `ctx.parentTypeName()`, which `loaderNameDeclaration` already
holds. (b) Read it at request time with `env.getExecutionStepInfo().getObjectType().getName()`.
A fetcher is only emitted in the class of a concrete object type (`TableType`, `NodeType`,
`RootType`, `ResultType`); an interface or union hosts no fetcher class, so `ctx.parentTypeName()` at
every site that reaches `loaderNameDeclaration` is already a participant or the declaring object
type. The rule is therefore: loader identity is the declaring object type plus the response path,
one loader per emitted batch function. That also splits a single-table interface's participants
(`FanAlpha.targetLoaded` and `FanBeta.targetLoaded` get separate loaders and batches). This stays
correct, since each participant's rows method keys its own foreign key, and the batch-count change on
such fields is intended. Recommendation: (a), on the one ground that remains: the declaring type is a
constant at the emission site, so the name needs no per-request lookup. (b) would give the same names.
A null `ctx.parentTypeName()` occurs only in out-of-band contexts (`TypeFetcherEmissionContext()`
builds one, and `TenantDslEmitter.routes` already guards on it); `loaderNameDeclaration` requires a
non-null parent and fails loudly, so no path-only recipe survives.

Steps:

* Name shape: the existing path join, then `"@"` and the declaring parent type, e.g.
  `kompetanseoppnaelseByNasjonalId/utdanningsspesifikasjon@KregFag`. `@` cannot occur in a GraphQL
  name or an alias, so the suffix cannot collide with a path segment, the same reasoning
  `tenantLoaderName` already gives for its `" tenant:"` separator. The tenant-partitioned name keeps
  appending its tenant segment after the parent-qualified name.
* `TenantDslEmitter.loaderNameDeclaration`: single-tenant inline form appends the literal
  `ctx.parentTypeName()`; the multi-tenant arms pass it to the carrier.
* `ConnectionRuntimeClassGenerator`: `loaderName(env)` becomes `loaderName(env, parentType)`, and the
  two `tenantLoaderName` overloads take the parent type ahead of the tenant. Keep the recipe's one
  home in `loaderName`; the javadoc says the name is path plus declaring type.
* `QueryNodeFetcherClassGenerator`: pass the literal `Query` to the carrier call in the
  multi-tenant branch; leave the single-tenant inline join (parent cannot vary).
* `DataLoaderFetcherEmitter`: if the convenience `build` overload that calls `buildDataLoaderName`
  has no production caller, delete it and `buildDataLoaderName`, so no path-only recipe survives;
  otherwise give it the parent type. Check with a search before choosing.
* Update the javadoc on `DataLoaderFetcherEmitter` (the class summary and the
  `buildDataLoaderName` comment) to say the name is path plus declaring type.
* User manual `docs/manual/how-to/split-vs-inline.adoc`, section "DataLoader cache key and
  lifecycle": the snippet, the stale `TypeFetcherGenerator.buildDataLoaderName:2537` pointer (it is a
  line number into a method that moved; drop it) and the bullet "Cache key is path-scoped, not
  name-scoped" all describe the path-only name. State the new name, and say that the same field name
  on two participants of one polymorphic field gets one loader per declaring type.

## Tests

Tier choice follows `docs/architecture/how-to/testing.adoc`: the wrong answer is a runtime result,
so the regression case belongs in the execution tier, with unit-tier pins for the emitted name.

* Execution (`graphitron-sakila-example`, `GraphQLQueryTest` or a sibling, against `schema.graphqls`):
  needs a multitable interface whose participants declare the same-named `@splitQuery` field over
  different tables. None exists today: `FanItem` is single-table, and its existing
  `fanItems_splitQueryOnSameNamedParticipantFields_staysGreen` passes only because both
  participants' `targetLoaded` batches key on the foreign-key value into the same `fan_target`
  table, so the shared loader happens to answer correctly. Add the fixture on the existing
  `FilmCastMember` interface (`ActorMember` over `actor`, `FilmInventory` over `inventory`, reached
  from `Film.membersViaJunction`): declare `films: [Film!]! @splitQuery` on both, each with its own
  `@reference` path (`ActorMember` through `film_actor`, `FilmInventory` through
  `inventory_film_id_fkey`). Their parent keys come from different tables, so a shared loader
  returns wrong or empty children. If the classifier rejects the list-cardinality parent-holds-FK
  path on `FilmInventory`, fall back to a new pair of small participant tables in `init.sql`
  rather than bending the shape. Assert both orders of the two fragments and an aliased control.
  `Film.membersViaJunction` is itself a `@referenceFor` multitable child (its participants are reached
  through a `@referenceFor(type: ...)` route), so these cases are the `@referenceFor` shape the report
  names; kreg's `barn` differs only in that its parent is also a participant, which does not enter the
  name (it is the field's own declaring type plus its path).
* Pipeline: `InterfaceDeclaredSplitQueryPinTest` pins what the build does with `@splitQuery` on a field
  declared on the interface itself: no diagnostic, no field classified for the interface (it hosts no
  fetcher class), and each participant's own declaration classifies as a batched field.
* Unit/pipeline: update the assertions that pin the path-only name: `DataLoaderFetcherEmitterTest`
  (line 57 region, the exact `String.join` statement), `TenantRoutedFetcherPipelineTest` (the
  `loaderName(env)` / `tenantLoaderName(env)` / inline `getKeysOnly` strings), `TenantFanOutFetcherPipelineTest`
  (`TenantConnections.loaderName(env)`), and `TenantRuntimeKeyTypeTest` (the `loaderName(env) + " tenant:"`
  pin). `TypeFetcherGeneratorTest` only asserts `getKeysOnly()` is present and stays green.
  No approval-file or generated-output snapshot pins the name beyond these.
* Run the full verification build; the Javadoc reference gate covers the edited `{@link}`s.

## Retired vocabulary

* "path-scoped" / "path-only" DataLoader name, "path-derived form": the name is the response path plus
  the declaring parent type.
* `DataLoaderFetcherEmitter.buildDataLoaderName` and the convenience `build` overload that called it.
* `ConnectionRuntimeClassGenerator` one-argument `loaderName(env)` and the `tenantLoaderName(env)` /
  `tenantLoaderName(env, tenant)` forms: parent type is now a parameter ahead of the tenant.

## Other solutions we've considered

* Request-time `getObjectType()` (fork (b) above): rejected as the lesser constant: it yields the same names as (a) at the cost of a per-request lookup.
* Fix only the single-tenant inline form: rejected, the multi-tenant carrier has the same defect and
  one recipe should have one home.
* Tell consumers to alias: rejected, it is a workaround that changes the response shape and the
  production query cannot adopt it.

Provenance: reported by the kreg Graphitron 10 port session (fs-plattform's kreg module on 10.0.0-RC46, branch `worktree-kreg-g10` at `7858137af4`, not pushed), 2026-10-30; the reporter found it present on graphitron `origin/main` the same day.

## Reviewer findings

### Round 1: Spec → Ready (session_01WkW2JEzgQYyoRDRQke2QpU, 2026-10-31)

Verdict: withhold. The goal reads cleanly on its own and the cause holds against the tree: every
batched fetcher builds its loader name from `getKeysOnly()` alone (`TenantDslEmitter.loaderNameDeclaration`,
`DataLoaderFetcherEmitter.buildDataLoaderName`, the carrier seam), so two participants at one response
path share a loader. The emission-site table is accurate. The fork the author asked about turns out to
rest on a premise the tree does not support, and that changes both the rationale and a test.

**Finding 1 (question two: fit). The open fork is mis-posed, and with it the stated advantage of (a) over
(b).** A fetcher is only ever emitted in the class of a concrete object type. `GraphitronSchemaBuilder`
calls `classifyField` only with a `GraphQLObjectType` parent, and `TypeFetcherGenerator.generate` renders a
class only for `TableType`, `NodeType`, `RootType` and `ResultType` rows. `InterfaceType`, `UnionType` and
`TableInterfaceType` host no fetcher class, and `FieldBuilder.classifyFieldInner` returns "parent type is
unclassified" for an interface or union parent. So `ctx.parentTypeName()` is always a participant (or the
declaring object type) at every site that reaches `loaderNameDeclaration`; the case "field declared on the
multitable interface itself" never reaches it, and the spec need not leave that to pickup. The same fact
removes the spec's reason for preferring (a): because each participant's fetcher is emitted in its own
class with its own `parentTypeName`, (a) also yields one loader per participant on a *single-table*
interface (`FanAlpha.targetLoaded` and `FanBeta.targetLoaded` get distinct names), which is exactly the
batch-count change the spec attributes only to (b) and says (a) avoids ("keeps today's single batched
query", "stays green"). The result stays correct, since each participant's rows method keys its own
foreign key, but the batch count on `FanItem` does change. The spec should state the rule as it actually
is (loader identity is the declaring object type plus the path, one per emitted batch function), say that
single-table participants split too and that this is intended, and settle (a) versus (b) on the ground
that remains, which is that (a) is a constant at the emission site, rather than on the batch-count claim.

> Response (author): Implementation now states the rule as loader identity = declaring object type plus path, says single-table participants split too and that this is intended, and settles (a) on the constant-at-emission-site ground.

**Finding 2 (question one: the goal is deliverable, but one test bullet cannot test anything).** "A multitable
interface whose `@splitQuery` field is declared on the interface itself as well as on the participants"
exercises no emission path: the interface declaration produces no fetcher and no loader name. Either drop
the bullet, or restate it as pinning that behaviour (what the build does with the directive on an
interface-declared field, which the author should check rather than assume). The two other execution
cases, both fragment orders over the new `FilmCastMember` fixture plus the aliased control and the
`@referenceFor` child shape, are sound and cover the report.

> Response (author): the interface-declared test bullet is restated as pinning what the build does with the directive on such a field, with a note to check the classifier.

**Finding 3 (question one: a gap in the emitted name).** `TypeFetcherEmissionContext()` builds a context
with a `null` parent type, and `TenantDslEmitter.routes` already guards on that. The step "single-tenant
inline form appends the literal `ctx.parentTypeName()`" does not say what a `null` parent emits. Production
paths always carry the type, but the spec should say which of the two it wants: require a non-null parent at
this site (and fail loudly), or fall back to the path-only name for out-of-band contexts.

> Response (author): `loaderNameDeclaration` now requires a non-null parent and fails loudly; no path-only fallback survives.

The spec's other claims were checked and hold: the four `TypeFetcherGenerator` sites and the two
`MultiTablePolymorphicEmitter` sites call `loaderNameDeclaration`, `QueryNodeFetcherClassGenerator`'s parent is
always `Query`, and the existing `FanItem` test is correct today for the reason the spec gives.

### Round 2: In Review → Done (session_01WkW2JEzgQYyoRDRQke2QpU, 2026-11-01)

Verdict: withhold. The change is correct and is the change the spec approved; the evidence for the
reported shape is sound; three things the spec named are not delivered. Question three (correct and the
approved change): the name is the response path, `"@"` and the declaring object type at every registration
site. All four `getDataLoaderRegistry()` sites read it through `loaderNameDeclaration` or the
`loaderName` / `tenantLoaderName` seam, `Query.nodes` passes the literal `Query`, a null parent fails
loudly, and the convenience overload and its path-only recipe are gone. The manual section is accurate and
states the type-scoped rule. Question four (how do we know it is complete) is where the gaps are.

**Finding 1 (question four). The `@referenceFor` multitable-child shape has no test.** The spec's Goal names
it ("The same happens under a `@referenceFor` multitable child") and its Tests section lists it; the kreg
production query that blocks the port selects the fields under `barn`, which is that shape. The new tests
reach the participants through `Film.membersViaJunction` only. The mechanism is shared, since the
participants' split fields are emitted by the same `TypeFetcherGenerator` class per participant, so this is
a coverage gap and not a suspected defect, but nothing named shows the second shape the goal promises. Add
the execution case, or have the spec drop the claim.

> Response (author): `Film.membersViaJunction` is a `@referenceFor` multitable child, so the new execution cases are that shape; the spec's Tests wording now says so and names the one difference from kreg's `barn`. Not adding a separate polymorphic-parent fixture, since the parent's type does not enter the name. Say so if you want it anyway.

**Finding 2 (question four). The pin for `@splitQuery` on an interface-declared field is not written.**
Round 1 asked for it to be restated rather than dropped, and the spec carries it as a test. The author's
note says the reviewer decides whether it blocks; it does not need to be a long test, but it is the one
place the build's behaviour for that directive is stated, so either write it or remove it from the spec.

> Response (author): written as `InterfaceDeclaredSplitQueryPinTest`: the build accepts the directive with no diagnostic, classifies no field for the interface, and classifies each participant's declaration as a batched field.

**Finding 3 (question three: the approved change, prose half). Prose still describes the path-only name.**
The spec's step "update the javadoc on `DataLoaderFetcherEmitter` (the class summary ...)" is only half done:
the `build` javadoc was updated but the class summary (line 19, "resolve the path-scoped DataLoader name via")
was not. The same stale wording remains in `LoaderRegistration` (line 31, "The path-scoped DataLoader name"),
in `MultiTablePolymorphicEmitter` (line 1658, "the path-derived form") and in `DataLoaderFetcherEmitterTest`
(line 16). `TypeFetcherGeneratorTest` (line 1313) still points at the deleted `buildDataLoaderName`. The item
declared no `Retired vocabulary`, which is what the sweep would otherwise have caught; the path-only name is
the retired mechanism here.

> Response (author): the class summary, `LoaderRegistration`, `MultiTablePolymorphicEmitter`, `DataLoaderFetcherEmitterTest` and `TypeFetcherGeneratorTest` wording is fixed, the two stale `R190` assertion descriptions and the sakila test comment are reworded, and the manual's `handle-services.adoc` and `split-vs-inline.adoc` sentences that still said path-scoped now say path and declaring type. A `## Retired vocabulary` section is declared. A grep for path-scoped, path-only, path-derived and `buildDataLoaderName` over java, adoc, graphqls and md now finds only unrelated uses.

Evidence the reviewer produced, so the author does not redo it: with the four generator sources reverted to
their pre-fix state and the same schema and tests, `splitFieldOnBothParticipants_eachResolvesFromItsOwnTable_inEitherFragmentOrder`
and `splitFieldOnBothParticipants_matchesAliasedControl` both fail and the five existing tests in the class
pass; with the fix all seven pass. That answers the red-run gap the author raised. The `graphitron-model`
`-DskipTests` install is accepted: the diff is not upstream of that module.
