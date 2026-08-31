---
id: R834
title: "A top-level @service returning a @table type reads columns off the returned record instead of refetching by key"
status: In Progress
bucket: bug
priority: 2
theme: service
depends-on: []
created: 2026-08-25
last-updated: 2026-08-31
---

# A top-level @service returning a @table type reads columns off the returned record instead of refetching by key

A `@service` on a root field hands the operation to a Java method the author wrote. When that method
returns jOOQ table records bound to a `@table` GraphQL type, the generated root fetcher passes the
records straight to graphql-java and every column field reads its value off the record it was handed.
`QueryFetchers.filmsByService` is the whole body: call the service, wrap the result, return it. So a
service that populated only the key columns resolves the key correctly and resolves every other
selected field to `null`, for every row, with no build warning and nothing thrown. A whole column of
nulls is indistinguishable from a column that is genuinely empty, so it reaches production. A Sikt
subgraph has now hit this twice.

The behaviour is not wrong by its own contract. `docs/manual/how-to/handle-services.adoc` states it
twice, that the framework treats the returned records as already-projected rows and that the service
is therefore responsible for selecting every column the schema may ask for. The problem is that the
contract is the odd one out. A root `@service` returning a *polymorphic* `@table` type already lifts
each record's primary key, re-selects by key per participant table, and builds the payload from the
refetched rows; `QueryFetchers.searchManyService` is generated exactly that way, and
`PolymorphicSearchService`'s own javadoc states the contract as "set only the PK and leave the rest
for Graphitron to fetch, matching the legacy 9.3 contract". A root `@service` returning a
`@discriminate` interface does the same, and `ContentSearchService` says so in the same words. Both of
those refetch because they were forced to, one needing the record's Java class as the discriminator
and the other needing a live discriminator column. The plain single-table return is not forced, so it
never got the treatment, and it is the shape an author writes first. Reading the three shapes
together, the passthrough looks like an omission rather than a decision.

The proposal is to make the plain return refetch too, so the contract across every table-returning
root `@service` becomes one sentence: populate the key columns, and Graphitron fetches the rest. The
machinery is already emitted at neighbouring coordinates, including
`FilmCardWrapperFetchers.rowsFilm`, which lifts a producer-handed record's primary key, anchors the
keys in a `VALUES(idx, key...)` table, joins the target table, selects exactly the live selection set
through the type's `$project`, and scatters the rows back to input order by index. At the model level
the passthrough is one named clause: `OperationMembers.mintsReentry` declines to mint the reentry
member for a root service field, so `OutputField.emitsKeyedReQuery()` is false there while
`requiresReFetch()` is true. Its comment says the re-projection is "realized by the downstream child
fetchers", which holds for a child that runs its own keyed query and does not hold for a plain column
read, where there is no downstream query at all.

The reason to state a rule rather than add an opt-in is that the declaration already exists in the
schema. `@table` on a GraphQL type says the type is that table's rows, so its column values come from
that table. A type with no `@table` whose backing class happens to be a jOOQ record keeps the direct
read it has today: `FilmDetails` is exactly that shape, and `FilmDetailsFetchers.title` reads the
column straight off whatever the producer returned. An author who wants to return column values that
differ from the table therefore drops `@table` and names the columns with `@field(name:)`, keeping the
same Java signature. That escape hatch needs saying out loud in the docs, because under the new rule
the same record from the same method behaves differently depending on whether the type it binds to
carries `@table`, and someone who meets that cold will read it as the mirror-image bug.

Four things for the plan to settle. Whether the escape hatch reaches what it needs to: dropping
`@table` also drops the catalog-driven surface, and `@node` requires `@table` explicitly, so an author
who wants to mask one column on an otherwise ordinary entity would be giving up global IDs to do it.
If per-caller column redaction has a home on the condition or tenant-scoping surface then nothing is
lost and the plan can say so; if it does not, that is a follow-up item rather than a blocker. Second,
tables with no primary key have nothing to key a refetch on, so they need a classify-time rejection
naming the table, which is a new build failure on schemas that currently build; there is precedent in
the existing rejection of a keyless `@table` parent hosting a batched child `@service`. Third, a
service that already selects every column, which is what the docs have been telling people to write,
pays one extra batched key lookup until it is rewritten to select keys only. Fourth, the refetch would
run on the request's `DSLContext`, so a service that deliberately read from another schema or tenant
connection would be refetched from the request's instead; the polymorphic path already has this
property, so the check is whether the multi-schema and tenant fixtures agree.

Two gaps to close alongside the fix. No fixture anywhere returns a key-only record from a plain root
`@service`, which is why the tier never caught this: `SampleQueryService.filmsByService` runs
`selectFrom(FILM)` and both execution tests select fully populated rows. And
`handle-services.adoc` states the inbound and the outbound record contracts on one page with nothing
connecting them, the inbound one under an `IMPORTANT` admonition saying a record the framework hands
you carries the key columns and nothing else. The reporter read that and applied it to their return
value, which is the mistake the page currently invites.

Reported externally as GitHub issue 534 against 10.0.0-RC33, with the RC34 generated code inspected
and unchanged; confirmed against the rewrite on 2026-08-25 by reading the generated sources in
`graphitron-sakila-example/target/generated-sources/`.

## Design

The rule in one sentence: a root `@service` field returning a `@table`-bound type re-fetches the
requested fields by primary key, the contract the polymorphic and discriminated service returns
already state, so the author's obligation everywhere becomes "populate the key columns and
Graphitron fetches the rest". The mechanism, in dependency order; every named symbol was verified
against the tree on 2026-08-25 and re-verified after the round-2 revision on 2026-08-26.

**1. Model: mint the reentry member.** The passthrough is one clause, `rootServicePassthrough`, in
`OperationMembers.mintsReentry`, with a twin production in `OperationMemberRelation`; the two are
pinned equal by `OperationMemberMintPinTest`, so they flip together. Deleting the clause makes the
mint read as the positive fact it always gated: a bare table target holding produced records mints
reentry, full stop. `OperationMembers.DECLARED_SHAPES` must simultaneously admit `Kind.REENTRY`
for `QueryServiceTableField` and `MutationServiceTableField`, or `validateAgainstDeclaredShape`
hard-fails construction; into the *required* set, not the optional one, because every instance
of these leaves has a bare table target and a record-producing service call, so every instance
mints (unlike `DmlTableField`, whose encoded return arms exempt it and make optional the honest
slot there). The one wrapper that would break the "every instance" claim cannot be constructed:
`ServiceDirectiveResolver` rejects Connection returns on root services outright ("@service at
the root does not support Connection return types"). After the flip, `emitsKeyedReQuery()` and
`requiresReFetch()` agree at every coordinate; the javadoc that currently explains their one
disagreement (on `OutputField`, `OperationMember.Reentry`, and the two service-table leaves)
rewrites to the new rule.

**2. Launcher: a root-service verdict, sourced by the existing reentry arm.**
`LauncherCommands.verdictOf` answers `Launch.NONE` for a root service today (the `SERVICE` rule
is gated on not-root), and `LauncherRelationClosureTest` pins that absence. Add a `Launch` enum
arm (`SERVICE_REENTRY`), verdict "root, has a `ServiceCall` member, has a `Reentry` member",
whose payload dispatch mints the *existing* `LaunchSource.ProjectedReentry` with a
`ParentCorrelation.OnLiftedSlots` over the return table's own primary key: PK self-identity,
the same correlation `FilmCardWrapperFetchers.rowsFilm` runs on. `Launch` is a total switch at
every consumer (`mintedMethodOf`, the `produce` dispatch, the schema-free walk), so the
compiler walks the cascade, and the membership census's per-arm non-vacuity floor makes the new
arm demand fixture coverage. Unlike `DML_REENTRY`, the schema-free walk serves this arm: the
skip reason there ("a schema-free assembly builds no mutation writes") does not apply, the
unit-tier fetcher assemblies do build `QueryServiceTableField` leaves, and the payload needs
only leaf facts (the return table's PK, the projection unit). The row mirrors `reentryRow`'s
shape otherwise: the `reentryRows<Field>` unit name, a null WHERE slot (the root service leaves
declare no filter surface), single tenancy, single/list result off the wrapper.

Reusing `ProjectedReentry` is a decision between three shapes, and the other two are rejected
with their costs named. `LauncherCommands.INVOCATION_BY_SOURCE` admits exactly one `Invocation`
arm per concrete `LaunchSource` leaf
(`LauncherMembershipTest.invocationDeterminationIsTotalOverTheSourceArms` pins the key set
against the sealed leaves), and `ProjectedReentry`'s arm is `Invocation.ReturningKeyed`, so
reuse entails `ReturningKeyed`, taken deliberately in point 3. The alternatives:

- *Mint a new `Reentry` leaf plus a new `Invocation` arm.* Buys nothing but a name: the emitted
  SQL, the keys-parameter type and the payload type would be byte-identical to what
  `ReentryRowsFragments` already renders for `ProjectedReentry` (its `keysType` reads the
  *source* correlation, not the invocation), while costing a `permits` edit on
  `LaunchSource.Reentry`, an `INVOCATION_BY_SOURCE` entry, and three new switch arms in
  `RootLauncherRenderer` (the body dispatch plus its two invocation switches).
- *The `ServiceTableLift` shape: move the service call inside the rows method*, the way the
  child `@service` table arm and every root catalog read work (`QueryFetchers.filmsConnection`
  is a thin call to `rowsFilmsConnection(dsl, env)`). The fork is real, and the child arm's
  javadoc is this item's mechanism at the child coordinate, but it cannot be taken by reuse:
  `INVOCATION_BY_SOURCE` pins `ServiceTableLift` to `Invocation.Batched`, whose two payload
  facts (`SourceKey`, `LoaderRegistration`) a root coordinate does not have, so the shape
  forces a new `LaunchSource` arm mapped to `Invocation.Direct` anyway, plus a new SQL
  fragment: `ServiceRowsFragments.liftBody` is loader-container-shaped end to end (per-parent
  bucket normalisation, the `seq` cell, the four-tail container re-wrap), so the root variant
  would duplicate what `ReentryRowsFragments.projectedBody` already is. It would also pull the
  service call inside the SQL-composing unit while the Jakarta validation pre-step and the
  try/catch envelope stay in the fetcher, splitting one call's envelope across two units. The
  DML precedent decides against it: `TypeFetcherGenerator.emitReentry`'s javadoc states the
  mutation entry point is "deliberately not thin" (it owns the write, the guards and the
  channel envelope; only the re-select is the launcher's), and the root service fetcher stands
  in exactly that position, with the service call as its write-analog.

With `ProjectedReentry` reused, `RootLauncherRenderer` needs no change at any of its three
switches: the body dispatch already routes `ProjectedReentry` to
`ReentryRowsFragments.projectedBody`, and the keys-parameter and `valueTypeOf` switches already
route `ReturningKeyed` to the same fragments.

**3. Delivery: `Invocation.ReturningKeyed`, widened in prose only.** `ReturningKeyed` is
payload-free by design; its own javadoc says every fact the arm would carry already rides
another axis (the keys type from the source correlation, the list lift from the result shape,
the `dsl` from the shell's declaration fragment). The mechanism it names, the entry point
produces keys itself and calls the launcher once with them, is exactly what the root service
fetcher does after the lift, so nothing structural widens: no new `Invocation` arm, no
`INVOCATION_BY_SOURCE` edit, and `LauncherAxisPins`' declared-arm agreement holds on the minted
rows unchanged. What rewrites is the DML-specific wording: `Invocation.ReturningKeyed`'s
javadoc ("the mutation entry point runs the write itself"), `LaunchSource.Reentry`'s ("the
mutation's captured `RETURNING` keys"), `ProjectedReentry`'s ("a projected mutation
companion"), the `ReentryRowsFragments` class javadoc, and its `ValuesJoinRowBuilder`
diagnostic context string ("@mutation @table-return reentry key") all restate as "keys captured
at the call site", with the DML `RETURNING` capture and the root-service key lift as the two
callers. `projectedBody` itself already emits both cardinalities the item needs:
`VALUES(idx, pk...)` join plus `$project` from the live selection set plus `ORDER BY idx` for
lists, and plain key equality for single; its inputs (target table, key columns, projection
unit) all derive from facts the leaf carries. The companion's deliberate absence of an
empty-input gate ("the companion is only ever called with captured keys") becomes a stated
obligation on both callers; the root service fetcher's half is in point 4.

**4. Caller-side lift in the fetcher.** `buildServiceFetcherCommon` in `TypeFetcherGenerator`
(shared by the query and mutation twins) grows a post-call step on its success arm for the two
table-bound leaves: lift each returned record's primary key into the companion's keys
container, call the reentry companion, return its result instead of the raw records. The
primary precedent is `TypeFetcherGenerator.emitReentry`, the DML caller of the same companion:
it registers the rendered companion through `ctx.addCompanionMethod` with
`RootLauncherRenderer.render` handed the `TenantDslEmitter`-resolved `dsl` declaration as the
shell fragment, guards the single-cardinality no-keys case before calling, and calls the
companion once with `(keys, env)`; the service emit does the same, with the lift loop standing
where the DML has its `RETURNING` capture (the loop shape itself already exists in
`MultiTablePolymorphicEmitter.buildServiceDispatchBlock` and `ServiceRowsFragments.liftBody`).
Two concrete obligations:

- *The keys container.* `ReentryRowsFragments.keysType` pins the companion's parameter to the
  typed key row (`RecordN<...>`), lifted to `Result<RecordN<...>>` for the list shape. The DML
  caller gets that container for free from `returningResult(...).fetch()`; the service caller
  constructs it with jOOQ's typed `DSLContext.newResult(fields...)` and
  `newRecord(fields...).values(...)` overloads (typed through degree 22, the same ceiling as
  the `Row22` cap every keyed path already has). This makes the fetcher's `dsl` local
  arm-entailed for the table-bound leaves regardless of the service method's own `needsDsl`
  answer; the record leaves keep the conditional declaration.
- *The empty gate is the caller's.* Single cardinality: a null service return skips the
  companion and resolves null, mirroring `emitReentry`'s `keys == null` guard. List
  cardinality: an empty service return skips the companion and resolves the empty list; this
  gate is load-bearing, not cosmetic, because the companion's list arm builds
  `DSL.values(keyRows)` and jOOQ rejects an empty row array.

The companion's own `dsl` binds inside its body from the shell fragment, i.e.
`graphitronContext(env).getDslContext(env)`, so the refetch runs on the request's connection,
matching the polymorphic path. The validation pre-step and the try/catch envelope stay in the
fetcher, unchanged, wrapped around both the call and the lift.

**5. The emitted fetcher's payload type moves; the model's `domainReturnType()` stays put.**
`QueryServiceTableField.domainReturnType()` already answers `DomainReturnType.Record(table)`,
verbatim what the catalog root table reads answer, and it does not change:
`ChildField.ServiceTableField`, the arm that already performs exactly this lift, keeps the same
value, its javadoc naming the reason (agreeing with the SQL-emit table-bound producers so a
`@table`-bound SDL type reached by both a service and an SQL-emit producer does not surface as
a spurious conflict). Editing it would reintroduce that conflict; no model edit happens here.

What does move is the emitted fetcher's declared Java payload type, which is what determines
the `env.getSource()` every child hanging off the parent receives. Today
`buildQueryServiceTableFetcher` declares the service's own container
(`DataFetcherResult<Result<FilmRecord>>` for `filmsByService`, pinned by literal FQN in
`TypeFetcherGeneratorTest.queryServiceTableField_emittedFetcher_declaresTypedResult`); after
the change the payload is the companion's `ReentryRowsFragments.valueTypeOf`: `List<Record>`
for the list shape, `Record` for single. Children then walk a projected row, which is what
children already walk under every catalog read and under the child `@service` arm; the pin
re-asserts the new type. See Risks for the residual exposure.

**6. Missing-row contract.** A lifted key with no matching table row drops from a list result and
resolves a single result to null, matching the drop contract `ContentSearchService` already pins
(its record 999). Pin the plain-shape version at execution tier.

**7. Rejections, validator-mirrored per the house pattern.** Three validator touchpoints. First,
the keyless return table: a root `@service` returning a `@table` type whose table has no primary
key has nothing to key the refetch on, so it is rejected at classify time with the table named,
mirrored in the validator. The child `@service` arm already carries the exact sentence
("@service on a table-bound return type requires the returned table 'X' to have a primary key for
identity re-projection", `GraphitronSchemaValidator.validateServiceTableField`); the root arm's
`validateQueryServiceTableField` is an empty method today. This is a new build failure on schemas
that currently build; changelog and docs say so. Second, the reentry implementedness guard
currently rejects any `emitsKeyedReQuery()` leaf that is neither a `BatchKeyField` nor a
`MutationField.DmlTableField`; it must admit the new leaves in the same commit that mints the
member. Third, the error channel: structurally, no table-bound service return can carry one
today, root or child, because `FieldBuilder.resolveErrorChannel` answers no-channel for anything
but a class-backed `ResultReturnType` and a `@table`-bound return is not one, so the reentry
emit needs no channel arms. The child arm's validator guard (`validateServiceTableField`'s
reentry-plus-channel rejection) exists to pin that premise at the build boundary; the root arm
gains the mirror guard in the same currently-empty `validateQueryServiceTableField` (and its
mutation twin), so a future widening of channel resolution to table-bound payloads fails loudly
at the new coordinate too, with a pipeline pin.

## The backlog's four questions, settled

**Escape hatch reach.** The no-`@table` shape works today at exactly the coordinate this item
changes, and is pinned: `FilmDetailsCarrier` carries no `@table`, is produced by the root
`@service` `Query.filmDetailsBatch` returning `List<FilmRecord>`, and its fetchers read columns
straight off the record the service handed over; the schema keeps it distinct from `FilmDetails`
precisely because a single SDL type cannot have two producers that disagree on the
`env.getSource()` Java type. (`FilmDetails` is the same no-`@table` direct-read shape, backed by
jOOQ's `FilmRecord` per `SharedDomainTypeProducerPipelineTest`, but it is reached as a
same-table nesting child, not from a root `@service`; `FilmDetailsCarrier` is the witness for
the shape the hatch is offered for.) So the escape hatch exists at the right coordinate and
costs nothing new. What it
cannot do is mask one column on an otherwise ordinary `@node` entity, because `@node` requires
`@table`; no current surface (condition, tenant scoping) offers per-caller column redaction. Per
the backlog's own framing that is a follow-up item to file if demand appears, not a blocker; the
docs name the hatch and what dropping `@table` gives up.

**Keyless tables.** Rejected, per Design point 7. Breaking for schemas that currently build; the
changelog entry says so and names the fix (add a primary key, or drop `@table` for the direct-read
shape).

**The extra lookup.** A service that already selects every column pays one additional batched
keyed SELECT per field until rewritten to select keys only. Accepted: it is one query per field
per request, the same price every polymorphic service return already pays, and correctness beats
the saved round trip. The docs state the cost and the key-only rewrite that removes it.

**Connection identity.** The refetch runs on the request's `DSLContext`. No fixture disagrees: the
multi-schema and tenant `@service` fixtures return scalars, not `@table` types (the cross-schema
fixture's concern is inbound record naming; the tenant fixture is a child scalar), and the tenant
fan-out on a root `@service` is already rejected by `TenantFanOutClassificationTest`. One comment
in that test states its basis as "a plain service return's reach is structurally empty", which
stops being true under this item; the rejection stays, the comment rewrites. A new execution
fixture pins that key-only records refetch on the request connection.

## Phases

**Phase 1, additive.** Shipped at `d1abd12d`: the keyless-return-table rejection at the root arm,
classifier diagnostic plus validator mirror plus the reject-plus-control pipeline pins in
`RootServiceReturnTablePkRejectionTest`. Landed alone and green before any behaviour changed.

**Phase 2, cutover.** Shipped at `4a2d3e85`, one commit as planned: the model flip, the `Launch`
arm and its `ProjectedReentry` row producer in both walks, the caller-side lift, the
emitted-payload-type move, the guard admission, and every pin update named here.

**Phase 3, proof and prose.** Shipped at `4a2d3e85` too, folded into the cutover commit rather
than following it: the key-only `SampleQueryService.filmsByService`, the execution pins, the
service javadoc contracts, and all four documentation surfaces.

**Rework round 1.** Shipped at `<this pass>`: the three evidence gaps round 4 named. The
validator's three root guards get their unit-tier reject pins beside the control, the three
code-string assertions on generated bodies are gone, and the single-cardinality arm gets the
example-schema coordinate it never had plus its compile and execution coverage. Round 4's
finding 1 is partly answered by Phase 1 rather than by this pass; see the round 5 note below.

Nothing outstanding. The changelog entry is still the Done gate's, per `roadmap/workflow.adoc`.

## Test surface

- Pipeline: the pin updates named in Phase 2; new reject-plus-control tests for the keyless root
  return; a pin for the root mirror of the reentry-plus-channel guard from Design point 7.
  `LauncherMembershipTest`'s invocation-determination pin needs no edit and proves the point:
  neither `LaunchSource`'s sealed leaves nor `INVOCATION_BY_SOURCE` change; the membership
  census gains fixture coverage for the new `Launch` arm's non-vacuity floor.
- Unit: `TypeFetcherGeneratorTest.queryServiceTableField_emittedFetcher_declaresTypedResult`
  re-asserts the new declared payload type (`DataFetcherResult<List<Record>>` for the list
  shape); body shape stays delegated to execution tier per its own comment.
- Execution (`graphitron-sakila-example`): key-only service records resolve full column data;
  absent key drops from a list and nulls a single; full-select siblings return unchanged results;
  `filmsByService_titleTitlecase_resolvesOnServiceReturnedTypedParent` stays green with its
  name and comment updated to the projected-parent reality.
- Compilation: the emitted companion compiles at Java 17 under the existing gate; nothing new.

## User documentation (first-client check)

The unified contract, replacing the passthrough statement in
`docs/manual/how-to/handle-services.adoc` (currently at the `Result<TableRecord>` heading and
restated in Pitfalls):

> When the return type is bound to a `@table` GraphQL type, the framework treats the returned
> records as key carriers: it lifts each record's primary key and re-selects the fields the query
> asked for from the table, in one batched query on the request's connection. Populate the key
> columns; everything else comes from the table. This is the same contract for every
> table-returning service shape (plain, interface, union), and it is the outbound mirror of the
> inbound rule above: records crossing the service boundary carry the key columns and nothing
> else, in both directions.

That last clause is the connection the page currently lacks between its inbound IMPORTANT
admonition and the outbound contract; the admonition gains the same cross-reference. The Pitfalls
entry rewrites from "returns skip framework projection" to the keyless-table rejection and the
missing-row drop. A short escape-hatch paragraph follows the contract: a type without `@table`
whose backing class is a jOOQ record keeps the direct read (the `FilmDetails` shape), the author
names columns with `@field(name:)`, and dropping `@table` gives up the catalog-driven surface
including `@node`. The fourth surface, `docs/manual/reference/directives/service.adoc`, currently
states "the framework treats jOOQ records as already-populated rows and skips its own projection"
and rewrites to one sentence of the new contract. Changelog entry states the behaviour change and
the new keyless rejection loudly.

## Retired vocabulary

- "root `@service` passthrough" and the `rootServicePassthrough` clause name
- "universal passthrough" (the `buildServiceFetcherCommon` javadoc's phrase)
- "treats the records as already-projected rows" / "already-populated rows"
- "the service is responsible for selecting every column"

## Risks

- The emitted-payload-type move (Design point 5) is the widest unknown: every child shape
  hanging off a service-returned `@table` parent (nesting fields, batched children, reference
  paths, the `titleTitlecase` wrap) must resolve off the projected `Record` where it resolved
  off the typed record before. Each child shape is already execution-covered off catalog reads
  and off the child `@service` arm's projected rows; the residual risk is an arm keyed
  specifically on the service parent's typed record, and the verification build surfaces it.
- A consumer whose service deliberately returned column values differing from the table gets
  table values after upgrading. That is the intended fix of the reported bug, but it is a silent
  semantic change for anyone relying on it; the changelog and the escape-hatch paragraph are the
  mitigation.
- `ValuesJoinRowBuilder` caps the VALUES row at `Row22`, so composite primary keys up to 21
  columns; the same cap every existing keyed path has, no new constraint.
- The delete-and-return mutation service (round 3's non-blocking note 2). A root
  `@mutation @service` that deletes rows and hands the deleted records back bound to a `@table`
  type renders those records today and, under the new rule, refetches nothing: it resolves an
  empty list or a null. Unlike the differing-column-values case above, no key-only rewrite
  recovers it; only dropping `@table` does. The mutation twin is the coordinate where an author
  is most likely to hold rows the table no longer has, so the changelog and the docs both say so.

## Plan departures (recorded during implementation)

Seven, none changing the design. The first six were recorded during the original implementation;
the seventh during rework round 1:

1. **The unit-tier `film` fixture had to gain a primary key.** `TestFixtures.filmTable()` carries
   no PK columns, and `ParentCorrelation.OnLiftedSlots` refuses an empty column tuple, so the
   hand-built table-bound service leaves in `TypeFetcherGeneratorTest` and the two
   `*ServiceTableFieldValidationTest` cases now use a new `TestFixtures.tableBoundFilmWithPk` /
   the existing `filmTableWithPk`. This is the honest fixture update the rule forces: a key-less
   return table is no longer a constructible shape at this coordinate.
2. **The three root validator guards are one method.** Design point 7's keyless and channel
   touchpoints and round 3's key-arity note fold into `validateRootServiceTableReturn`, shared by
   the query leaf and its mutation twin, rather than three separate clauses per leaf.
3. **The missing-row fixture is a new coordinate, not the rewritten one.**
   `filmsByService(ids:)` runs its own `SELECT` and so can never return a key the table has no
   row for. `SampleQueryService.filmsByServiceUnchecked` (new, plus `Query.filmsByServiceUnchecked`)
   builds key-only records in memory with no query at all, which pins the drop contract and, as a
   bonus, the strongest form of the rule: a service need not touch the database to answer a
   `@table`-bound field. `filmsByService` still became key-only, as the plan specified.
4. **One SQL baseline beyond the stated test surface.**
   `RootLauncherSqlBaselineTest.rootServiceTableReturn_oneCompanionSelectOverTheLiftedKeys` pins
   the caller-side claim the test surface named in prose but had no home for: that the coordinate
   costs exactly one keyed re-select, input-ordered. The companion's body shape stays baselined at
   the DML caller.
5. **"Universal passthrough" retires only where it stopped being true**, per round 3's imprecision
   note: at `buildServiceFetcherCommon`. At the record- and scalar-returning leaves the arm is
   still a passthrough, so those three sites keep the word "passthrough" and drop "universal".
6. **The changelog entry is left to the Done gate**, per `roadmap/workflow.adoc`, which places it
   at the approve step with the landing commits cited.
7. **The validator's three guards are pinned at the unit tier, not as pipeline
   reject-plus-control.** Round 4's finding 1 offered either. Only the unit tier can reach them: a
   schema cannot produce a field that arrives at the validator carrying any of the three defects,
   because the classifier rejects the key-less return first (which is what the Phase 1 pipeline
   test asserts, from SDL) and channel resolution answers no-channel for a table-bound return. The
   hand-built enum row is the house pattern for exactly this, already used by
   `ServiceFieldValidationTest.reentryServiceFieldWithPresentChannel_rejected` at the child
   coordinate. The two tiers divide the work the criterion asks for: the pipeline test pins the
   diagnostic an author actually meets, the unit rows pin that the mirror behind it is enforced.

## Done criteria

- Generated `QueryFetchers.filmsByService` lifts keys and returns the `reentryRows<Field>`
  companion's result; no direct passthrough of service records bound to a `@table` type remains.
- Named execution test proves key-only service records resolve full column data, and another
  proves the missing-row drop/null contract.
- The keyless root return is rejected naming the table, classifier and validator both, with
  reject-plus-control pipeline pins.
- `emitsKeyedReQuery()` agrees with `requiresReFetch()` at every coordinate and the javadoc
  explaining their old disagreement is gone.
- All four documentation surfaces state the one-sentence contract, the inbound and outbound rules
  cross-reference each other, and the escape hatch is documented with what it gives up.
- The retirement sweep at the Done gate finds none of the retired vocabulary.

## Reviewer findings

### Round 1 (Spec → Ready gate, session_2f64b812-3033-42e0-951e-bbd5a6fb0508, 2026-08-26)

Independent reviewer session, status stays `Spec`. Two blocking findings and one non-blocking
note.

Question 1's establishing read holds. Every symbol, code site, test, message fragment and doc
sentence the body names exists as named, checked by FQN-aware grep, including the generated
bodies in `graphitron-sakila-example/target/generated-sources/` that are not in the tree. What
changes for a consumer is plain without the phase list: an author who writes a root `@service`
whose GraphQL return type carries `@table` stops being responsible for populating the rows they
return. Today graphitron hands their jOOQ records to graphql-java and every column field reads
off the record, so a method that selected only the key resolves every other field to `null`
silently; after this lands graphitron lifts each returned record's primary key and re-SELECTs the
requested fields from the table, one batched query on the request's connection. Two consequences
the author will feel: a schema whose returned `@table` type has no primary key stops building,
and a service that deliberately returned values differing from the table gets table values, with
"drop `@table` and name columns with `@field(name:)`" as the escape hatch. The outcome is
reachable: the machinery is emitted at neighbouring coordinates and was read there, not taken on
trust.

Question 2 is where the plan does not survive contact. Two of its named mechanisms are
contradicted by the tree.

1. **Question 2. Design points 2 and 3 cannot both hold: `INVOCATION_BY_SOURCE` admits exactly
   one `Invocation` arm per concrete `LaunchSource` leaf, and `ProjectedReentry`'s is already
   spoken for.** Point 2 mints a `LaunchSource.ProjectedReentry` and states that
   `RootLauncherRenderer` "needs no change"; point 3 recommends "a new `Invocation` arm rather
   than a widened `ReturningKeyed`". `LauncherCommands.INVOCATION_BY_SOURCE` is a
   `Map<Class<? extends LaunchSource>, Class<? extends Invocation>>` carrying
   `ProjectedReentry.class -> ReturningKeyed.class`, and the one-arm-per-leaf property is
   enforced twice: `LauncherMembershipTest.invocationDeterminationIsTotalOverTheSourceArms`
   pins the key set against `LaunchSource`'s sealed leaves, and
   `LauncherAxisPins.assertInvocationMatchesDeclaredDetermination` pins every produced row's
   invocation against the declared arm at every relation the test tree builds. So minting
   `ProjectedReentry` forces `ReturningKeyed`, which is the widening point 3 argues against;
   and a new `Invocation` arm forces a new `LaunchSource` arm, which falsifies point 2's
   renderer claim, `RootLauncherRenderer` carrying a total `switch (row.source())` for the body
   and two total `switch (row.invocation())` for the keys parameter and for `valueTypeOf`.
   The stated reason for the recommendation does not hold either:
   `ReentryRowsFragments.keysType(row)` reads `((LaunchSource.Reentry) row.source()).correlation()`,
   deriving the keys type from the *source*, not from the invocation; the renderer merely
   selects it on the invocation arm.

   What would satisfy this: pick one arm and state its consequence. Either reuse
   `ProjectedReentry` and accept that `Invocation.ReturningKeyed` and the `LaunchSource.Reentry`
   javadoc widen from "the write's captured `RETURNING` keys" to "keys captured at the call
   site" (renderer untouched), or mint a new `Reentry` arm plus a new `Invocation` arm and say
   the renderer gains three.

   The choice should also weigh `LaunchSource.ServiceTableLift`, which the plan never mentions.
   Its javadoc is this item's mechanism at the child coordinate: "A `@service` table child: the
   developer's method produces real table records; the rows method lifts them back by
   re-projecting each returned record's primary key by identity through `projection`'s
   `$project` over an `(idx, seq, pk...)` VALUES join against `table`". It differs from the
   plan's shape in where the service call sits: inside the `rows<Field>` method rather than in
   the caller. That is a third design, and it is the one the root catalog shape already uses
   (`QueryFetchers.filmsConnection` is a thin call to `rowsFilmsConnection(dsl, env)`); taking
   it would delete Design point 4's caller-side lift outright. The plan cites
   `ServiceRowsFragments.liftBody` only as a loop-shape precedent for the caller-side lift, so
   the fork was passed without being named. Say why the caller-side lift wins, or take the
   other arm.

2. **Question 1. Design point 5 names an edit that is a no-op, and Risks makes it the item's
   widest unknown.** The point says `QueryServiceTableField.domainReturnType()` "moves to
   whatever the catalog root read answers". It already answers
   `new DomainReturnType.Record(returnType.table())`, which is verbatim what the catalog root
   table reads answer (`QueryField.QueryTableField` and `QueryField.QueryTableFilterField`).
   Further, `ChildField.ServiceTableField`, the child `@service` arm that already performs
   exactly this lift, keeps `Record(table)`, and its javadoc gives the reason: the value
   "agree[s] with the SQL-emit table-bound producers ... so a `@table`-bound SDL type reached by
   both a service and an SQL-emit producer does not surface as a spurious conflict". So the
   named edit changes nothing, and making it would reintroduce the conflict the sibling arm
   guards against. The precedent the plan needs is sitting in the arm it already cites for the
   rejection wording.

   There is a real change the point conflates with this one, and it deserves the Risks entry
   instead: the *emitted fetcher's* declared Java return type. `filmsByService` is emitted as
   `DataFetcherResult<Result<FilmRecord>>` today, pinned by literal FQN string in
   `TypeFetcherGeneratorTest.queryServiceTableField_emittedFetcher_declaresTypedResult`, and it
   is that type, not `domainReturnType()`, that determines what `env.getSource()` hands every
   child hanging off the parent. Restating point 5 as the emitted-type and `env.getSource()`
   move, with the child arm cited as the precedent that the model-level `DomainReturnType`
   stays put, would satisfy this and shrink the declared risk to its true size.

**Non-blocking.** The escape-hatch argument cites the wrong witness. `FilmDetails` is reached via
`Film.filmDetails` as a same-table `NestingField` passthrough, not from a root `@service`; the
schema comment beside it says it "lost" the producer coverage when it was reclassified. The type
the argument needs is `FilmDetailsCarrier`, produced by the root `@service` `Query.filmDetailsBatch`
returning `List<FilmRecord>`, carrying no `@table`, with the schema stating outright that it is
kept distinct "because a single SDL type cannot have two producers that disagree on the
`env.getSource()` Java type". Both types exist and both are pinned; naming the second makes the
escape hatch load-bearing for the shape it is offered for.

### Round 2 (Spec → Ready gate, session_b5d1f000-b26e-43bd-ad31-1759b68c6a89, 2026-08-26)

Independent reviewer session, different from round 1's. Status stays `Spec`. No new blocking
finding; round 1's two are re-confirmed first-hand, and the plan body is byte-identical to the
copy round 1 reviewed (`git diff` over the file since the Backlog → Spec commit is the findings
section and nothing else). So the bounce stands on the same two grounds, and this round exists to
close off the one thing that could have lifted it: that round 1 had misread the tree. It had not.

Re-verified independently, not by reading round 1:

- `LauncherCommands.INVOCATION_BY_SOURCE` is a `Map<Class<? extends LaunchSource>, Class<?
  extends Invocation>>` with `ProjectedReentry.class -> ReturningKeyed.class`, and
  `LauncherMembershipTest.invocationDeterminationIsTotalOverTheSourceArms` asserts its key set
  equals `LaunchSource`'s sealed leaves exactly, so one arm per leaf is pinned, not merely
  conventional. `LaunchSource.Reentry permits ProjectedReentry, DiscriminatedReentry` and both
  hold a `ParentCorrelation.OnLiftedSlots`, so a third reentry arm also edits the `permits`
  clause. `ReentryRowsFragments.keysType` reads
  `((LaunchSource.Reentry) row.source()).correlation()`: source, not invocation, as round 1 said.
- `QueryServiceTableField.domainReturnType()` returns `new DomainReturnType.Record(returnType
  .table())`, and `ChildField.ServiceTableField.domainReturnType()` returns the same expression
  with the javadoc naming the reason ("agreeing with the SQL-emit table-bound producers ... so a
  `@table`-bound SDL type reached by both a service and an SQL-emit producer does not surface as
  a spurious conflict"). Design point 5's edit is a no-op, and performing it reintroduces that
  conflict.
- The plan's other named facts hold where I sampled them: `rootServicePassthrough` lives at both
  mint homes, `DECLARED_SHAPES` gives `QueryServiceTableField` `shape(Set.of(Kind.SERVICE_CALL),
  Set.of())`, `verdictOf`'s `SERVICE` rule is `!root && hasKind(SERVICE_CALL)`,
  `validateQueryServiceTableField` is a body of one comment, the child arm's PK rejection
  sentence is verbatim, `SampleQueryService.filmsByService` is `selectFrom(Tables.FILM)`, and the
  four doc sentences slated for rewrite exist at the quoted wording.

One sharpening on round 1's "weigh `ServiceTableLift`", because it makes the unnamed fork cheaper
than the arm the plan chose rather than merely different: `RootLauncherRenderer.valueTypeOf`
short-circuits on `LaunchSource.ServiceCall` and `LaunchSource.ServiceTableLift` *ahead* of its
invocation switch and delegates to `ServiceRowsFragments`, and `INVOCATION_BY_SOURCE` already maps
`ServiceTableLift.class -> Invocation.Batched.class`. So the service-shaped arm is wired through
the renderer at the value-type view already, and taking it would delete Design point 4's
caller-side lift instead of adding to it. What blocks it is narrower than a renderer cascade:
`Launch.SERVICE` is gated on `!root`, and `LauncherCommands.serviceRow` switches over
`ChildField.ServiceTableField` / `ChildField.ServiceRecordField` with a throwing default, so the
root leaves would have to be admitted there. Naming that cost and rejecting it is a fine answer;
what the plan cannot do is leave the fork unstated while citing `ServiceRowsFragments.liftBody`
only for its loop shape.

The non-blocking note also holds: `FilmDetails` carries no `@table` but is reached as a same-table
nesting child, while `FilmDetailsCarrier` is produced by the root `@service` `Query
.filmDetailsBatch` returning `List<FilmRecord>`. The second is the witness the escape-hatch
argument needs.

Nothing else to add. Settling findings 1 and 2 is the whole of what stands between this spec and
Ready; the establishing read, the phase decomposition, the test surface and the doc plan are in
good shape and I would not ask for changes to them.

### Revision after round 2 (2026-08-26)

Design points 2 through 5 rewritten, with the ripples (point 7's channel touchpoint, the
escape-hatch witness, Phases, Test surface, Risks, Done criteria). What changed, against the two
findings:

**Finding 1 settled by choosing an arm and naming the fork.** The design reuses
`LaunchSource.ProjectedReentry`, accepting `Invocation.ReturningKeyed` as the pinned consequence
and taking the javadoc widening ("keys captured at the call site") deliberately; the renderer is
untouched at all three switches, for the reason round 1 itself supplied (`keysType` derives from
the source correlation). The `ServiceTableLift` fork round 1 and round 2 flagged is now stated
in the body and rejected on three verified grounds: the pinned
`ServiceTableLift -> Invocation.Batched` determination forces a new source arm anyway (a root
has no `SourceKey` or `LoaderRegistration`), the root variant of the loader-container-shaped
`liftBody` would duplicate `projectedBody`, and moving the call inside the rows method splits
the validation/channel envelope across two units against `emitReentry`'s "deliberately not thin"
entry-point precedent, which is the structural analogy the whole design now leans on (service
call at root : reentry companion :: DML write : reentry companion). Two caller obligations the
old text lacked are stated: constructing the `Result<RecordN>` keys container (typed
`DSLContext.newResult`/`newRecord`, degree 22 ceiling) and owning the empty-input gate the
companion deliberately lacks.

**Finding 2 settled as the reviewer proposed.** Design point 5 no longer edits
`domainReturnType()` (it stays `Record(table)`, with `ChildField.ServiceTableField`'s
conflict-avoidance javadoc cited as the reason); the point now names the real move, the emitted
fetcher's declared payload type and the `env.getSource()` consequence, and the Risks entry
shrinks to that.

**Non-blocking note taken.** The escape-hatch witness is `FilmDetailsCarrier`, with
`FilmDetails` demoted to the same-shape-different-producer aside.

Facts re-verified first-hand against the tree on 2026-08-26 during this revision, beyond
re-reading the rounds: `INVOCATION_BY_SOURCE` and both membership pins, `ReturningKeyed`'s
payload-free javadoc, `projectedBody`/`liftBody`/`keysType`/`valueTypeOf`, `emitReentry` and
`emitKeysTransaction`, the mint and `DECLARED_SHAPES` at both homes, and
`resolveErrorChannel`'s class-backed-`ResultReturnType` guard, which settles the channel
touchpoint structurally (a table-bound return can never carry a channel today, so the reentry
emit needs no channel arms and the root validator arm gains the mirror guard).

### Round 3 (Spec -> Ready gate, session_4aeb0540-b3c1-475f-875f-582b4f6b80af, 2026-08-26)

Independent reviewer session, different from rounds 1 and 2 and from the session that landed the
revision. **Sign off.** Status `Spec` -> `Ready`.

Both gate questions pass, and the revision settles rounds 1 and 2 on grounds I checked against the
tree rather than against the rounds.

Question 1. What changes for a consumer: an author who writes a root `@service` whose GraphQL
return type carries `@table` stops owning the rows they hand back. Today the generated fetcher
passes the jOOQ records to graphql-java and every column field reads off the record, so a method
that selected only the key resolves every other selected field to `null` with nothing thrown;
after this lands the fetcher lifts each returned record's primary key and re-selects the requested
fields from the table, one batched query on the request's connection, ordered back to the service's
own order. Three consequences the author feels: a schema whose returned `@table` type has no
primary key stops building; a lifted key with no live row drops from a list and nulls a single; and
a service that deliberately returned values differing from the table now gets table values, with
"drop `@table` and name the columns with `@field(name:)`" as the escape hatch. Reachable: the
machinery is emitted at neighbouring coordinates and I read it there, `searchManyService` lifting
per-participant keys in the fetcher and `rowsFilm` running the `VALUES(idx, pk)` join through
`$project`.

Question 2. The design extends one shape rather than standing a second beside it. Reusing
`LaunchSource.ProjectedReentry` is forced to `Invocation.ReturningKeyed` by
`INVOCATION_BY_SOURCE`, which is what point 3 now takes deliberately, and the "renderer untouched"
claim holds at all three of `RootLauncherRenderer`'s switches. The `ServiceTableLift` fork rounds 1
and 2 asked for is stated and rejected on grounds that check out. The `DECLARED_SHAPES`
required-versus-optional argument holds: the only root leaves that gain a reentry member when the
passthrough clause goes are the two service-table leaves, because every other root leaf with a
`ServiceCall` member carries an `Interface` or `Record` target, and the Connection wrapper that
would break the "every instance" claim is rejected at classify time. Point 5 leaves
`domainReturnType()` alone with the right citation and names the emitted-payload move instead. I
would hand this to an implementer as it stands.

**Non-blocking, three notes.**

1. *The reentry key-arity mirror is a fourth validator touchpoint.* Point 7 enumerates three, and
   Risks says the `Row22` cap is "no new constraint", which is true of the cap but not of the
   diagnostic. `ReentryRowsFragments`' own javadoc on `ROW_CONTEXT`, the string point 3 rewrites,
   asserts that `GraphitronSchemaValidator` rejects an over-arity reentry key at validate time so
   the row builder's throw is only a backstop for objects built outside the pipeline. That
   assertion is currently true because `validateDmlReentryKeyArity` is the only in-pipeline caller;
   a root-service caller without the mirror makes the row builder's `IllegalStateException` the
   primary diagnostic at a new coordinate. Two lines beside the keyless rejection Phase 1 already
   specifies, off the same `returnType().table()` fact, or a weakened javadoc if the author would
   rather match the child `@service` lift, which carries no arity guard either.

2. *The delete-and-return mutation service.* `MutationServiceTableField` is in scope, and the DML
   family's `Delete`-pairs-only-with-`Encoded` invariant exists because a row that is gone cannot
   be re-selected. A root `@mutation @service` that deletes rows and hands back the deleted records
   bound to a `@table` type renders those records today and, under the new rule, refetches nothing
   and resolves an empty list or a null. Unlike the differing-column-values case Risks already
   names, no key-only rewrite recovers it; only dropping `@table` does. Worth its own Risks bullet
   and a changelog sentence, since the mutation twin is the coordinate where an author is most
   likely to hold rows the table no longer has.

3. *Context for Phase 2's corpus work, from a commit that landed after the revision.* `cff68d271`
   moved the two mutation `@service` returns out of `mutation-roots` into a new `mutation-service`
   corpus example whose prose already states this item's destination ("the coordinate re-queries
   the catalog keyed on what the service handed back"). So the corpus edit is the `@classified`
   `operations:` list at `Mutation.importFilm` and `Query.externalFilm`, with no prose to rewrite
   at the mutation coordinate. Separately, the "root `@service` passthrough" line in
   `docs/architecture/reference/code-generation-triggers.adoc` is a fifth doc surface; the Retired
   vocabulary section already covers it through the Done-gate sweep.

One imprecision in Retired vocabulary, mentioned only because a Done criterion turns on the sweep
finding none of it: "universal passthrough" also names the success arm of the record-returning and
scalar-returning service leaves, where it stays accurate. The phrase retires at
`buildServiceFetcherCommon`, whose success arm stops being universal, not everywhere it appears.

### Round 4 (In Review -> Done gate, session_01KdaUAayLBKKV6deApWv7Z5, 2026-08-31)

Independent reviewer session, different from every session that authored an implementation commit
(the four carry no session trailer; the git-author fallback is a human, and this is a Claude Code
session with its own id). **Request rework.** Question 2 fails. Status `In Review` -> `Ready`.

`mvn install -Plocal-db` passes on the delivered tree, all 14 modules, 08:39 wall clock, including
`GraphQLQueryTest` (388 tests) and `RootLauncherSqlBaselineTest` (22). So the findings below are
about evidence that is absent, not about anything that is broken.

Question 1 passes, with one substitution noted in finding 2. The mechanism the design approved is
what arrived, point by point: the `rootServicePassthrough` clause is gone from both mint homes and
`mintsReentry` reads as the positive fact; `DECLARED_SHAPES` carries `Kind.REENTRY` in the
*required* set for both service-table leaves with the "every instance mints" reasoning stated
beside it; `Launch.SERVICE_REENTRY` exists with the verdict "root, has ServiceCall, has Reentry",
mints `LaunchSource.ProjectedReentry` over `ParentCorrelation.OnLiftedSlots(table,
table.primaryKeyColumns())` with `Invocation.ReturningKeyed`, a null where slot and single tenancy,
and is served by the schema-free walk with the reasoning read the other way; `RootLauncherRenderer`
is untouched at all three switches; `emitServiceReentryLift` owns the empty gate and builds the
keys container through typed `newResult` / `newRecord`; `domainReturnType()` stays
`Record(table)` with the conflict-avoidance citation. The generated proof is there and reads
cleanly: `QueryFetchers.filmsByService` declares `DataFetcherResult<List<Record>>`, gates on
`result == null || result.isEmpty()`, lifts `Result<Record1<Integer>> keys`, and returns
`rowsFilmsByService(keys, env)`. Done criterion 1 is met. So is criterion 5: all four user-facing
surfaces plus the architecture triggers page state the one-sentence contract, the inbound
`IMPORTANT` and the outbound rule cross-reference each other, and the escape hatch names what
dropping `@table` gives up (`@reference`, `@splitQuery`, `@orderBy`, pagination, `@node`). The
retirement sweep finds none of the retired vocabulary in live code or docs; the surviving hits are
`roadmap/changelog.md` (permanent record) and R674's body, which quotes the manual sentences this
item rewrote and is now stale for its own next pass rather than for this one.

Three findings, all on question 2's evidence.

1. **Done criterion 3 is not delivered: the keyless root return has no reject pin at all, and
   neither do the other two new root guards.** The criterion says "rejected naming the table,
   classifier and validator both, with reject-plus-control pipeline pins", and Phase 1's whole
   content was that rejection landing "alone and green before any behaviour changes". Both halves
   of the rejection exist in main sources: `ServiceDirectiveResolver` lines 321-330 (the
   `STRICT_ROOT` classify arm) and `GraphitronSchemaValidator.validateRootServiceTableReturn`
   (982-1022, all three guards). Not one of the four error branches is exercised by any test.
   `QueryServiceTableFieldValidationTest` and `MutationServiceTableFieldValidationTest` each hold
   a single `VALID` case, so the control half shipped without the reject half; the only
   "returned table ... to have a primary key" pin in the tree is the pre-existing child-arm one at
   `Film.externalChild` in `ServiceFieldValidationTest.RETURN_TABLE_NO_PK`, which is a different
   coordinate with different wording. This is load-bearing rather than bookkeeping: the keyless
   rejection is the precondition the emitter runs on, because `serviceReentryRow` hands
   `table.primaryKeyColumns()` to `ParentCorrelation.OnLiftedSlots`, which refuses an empty tuple.
   If the classify arm ever stops firing, the failure mode is a generator-side throw or a wrong
   correlation, not the build error the item promises consumers. The arity guard is round 3's
   non-blocking note 1, taken and then left unpinned, which leaves `ReentryRowsFragments`'
   `ROW_CONTEXT` javadoc claiming a validate-time rejection that nothing demonstrates at the new
   coordinate. What satisfies this: a reject case per guard beside the existing control, in the
   two validation tests or as a pipeline reject-plus-control pair on the
   `PkLessParentServiceSourcesRejectionTest` shape the plan named, asserting the message text at
   both the classifier and the validator coordinate.

2. **Three code-string assertions on generated method bodies, which the approval preconditions ban
   and which the plan's own test surface said would not be here.** Test surface, Unit bullet:
   `queryServiceTableField_emittedFetcher_declaresTypedResult` "re-asserts the new declared payload
   type ...; body shape stays delegated to execution tier per its own comment". The delivered test
   keeps that comment ("Body-shape properties (the dsl local, the service call, the key lift) are
   asserted at execution tier") and then contradicts it two lines later:
   `TypeFetcherGeneratorTest.java:651-653` asserts the body contains
   `"org.jooq.Result<...FilmRecord> result"` and `"rowsFilmsByService(keys, env)"`, and
   `:839` adds `"rowsCreateFilm(keys, env)"` to the mutation twin. `development-principles.adoc`
   bans the form at every tier ("they test implementation, not behaviour, and break on every
   refactor"), review-enforced, which is this gate. The `connectionField_withOrderByArg_*`
   carve-out does not extend here: it claims its exemption in prose and states why no structural
   equivalent exists, and both facts these three assert do have one, the launcher row's unit name
   (already pinned by `LauncherRelationClosureTest`) and the compile tier resolving the companion
   call. Local precedent exists at the DML caller (`FetcherPipelineTest:667`/`699`,
   `SingleRecordPayloadPipelineTest:445`), so this reads as following the family rather than
   inventing anything; it is pre-existing debt, not a licence. What satisfies this: drop the three
   `contains` on `.code().toString()`, keep the `returnType()` assertions, which are structural and
   are what the plan asked for.

3. **The single-cardinality arm has no compile or execution coverage, and half of criterion 4 is
   unproven.** Criterion 4 wants a test proving "the missing-row drop/null contract"; the delivered
   `queryServiceTable_keyWithNoLiveRow_dropsFromTheListResult` proves the drop, and nothing proves
   the null. The reason is structural rather than an oversight in the test file: every plain root
   `@service` `@table` coordinate in `graphitron-sakila-example` is list cardinality
   (`contentSearchOne` is the discriminated interface leaf, not this one), so
   `emitServiceReentryLift`'s single arm, the `result == null` gate and the bare `RecordN` keys
   container, reaches neither the compile tier nor the execution tier. "Compilation: ... nothing
   new" in the test surface is true only of shapes the example schema declares, and it declares
   none. This is an untested arm and not a broken one: I probe-compiled the emitted shape
   (`Record1<Integer> keys = dsl.newRecord(FILM.FILM_ID).values(result.get(FILM.FILM_ID))` into a
   `Record rowsX(Record1<Integer>, ...)` call) against the sakila catalog at `--release 17` and it
   compiles. Cheapest of the three to close: one single-cardinality field on the example schema
   plus a service method, which buys the compile coverage, and one execution test for the null.

Nothing else. The plan departures section is accurate about what shipped and is worth keeping as
written. One bookkeeping item for the next pass, not a finding: the Phases section is still
forward-looking prose rather than one-line "shipped at `<sha>`" notes, which the Done gate's
preconditions ask for; the departures section already carries most of what the collapse would say.

### Rework round 1 (response to round 4, session_01XWRcm5Tn4A2E4PxcbnePQi, 2026-08-31)

All three findings addressed. No behaviour changed: the diff is tests, two fixtures, and one
example-schema coordinate.

**Finding 1, the new root-arm rejections.** Partly already delivered, and the correction matters
for what the next gate should check. `RootServiceReturnTablePkRejectionTest` landed in Phase 1 at
`d1abd12d` and is the reject-plus-control pipeline pin the criterion asks for at the classifier
coordinate: the rejection from SDL at both cardinalities and both root kinds, the classify verdict
and its arrival at the build boundary as a validation error, a keyed control, and a second control
for the escape hatch the rejection's own wording offers. Round 4 did not find it (its search was
for the child arm's message wording, which this test does not use; it asserts the fragments
`film_list`, `primary key` and `drop @table`), and concluded no pin existed at all. That half of
the finding is stale.

What was genuinely missing is the validator's own three guards, none of which the pipeline test
reaches. `QueryServiceTableFieldValidationTest` and `MutationServiceTableFieldValidationTest` now
carry four rows each beside the existing control, asserting the full message text at the validator
coordinate: the key-less return table, the 22-column key on the list arm, the same key at single
cardinality staying silent (the guard is list-only, because only the list arm's `VALUES (idx,
key...)` row spends a slot on the index), and a present error channel. Departure 7 above states why
these are unit-tier rows rather than a second pipeline test. The arity row is what makes
`ReentryRowsFragments`' `ROW_CONTEXT` javadoc true at this coordinate: the row builder's own throw
really is a backstop behind a validate-time rejection, and there is now a test that fails if the
rejection goes away.

**Finding 2, the code-string assertions.** All three are gone:
`TypeFetcherGeneratorTest.queryServiceTableField_emittedFetcher_declaresTypedResult` keeps only its
`returnType()` assertion, and the mutation twin keeps its stub-regression assertions and drops the
`rowsCreateFilm(keys, env)` one. Both facts the deleted assertions covered keep structural homes,
named in the surviving comment: `LauncherRelationClosureTest` pins the launcher row's unit name
(`rowsExternalFilm`, its source arm and its invocation) and the compile tier pins that the emitted
call resolves. The pre-existing precedent at the DML caller is left alone, as the finding said.

**Finding 3, the single-cardinality arm.** `Query.filmByServiceUnchecked(id: Int): Film` is the
coordinate the example schema lacked, backed by `SampleQueryService.filmByServiceUnchecked`, which
returns one key carrier with no query run, or nothing at all when `id` is omitted. That buys the
compile coverage the arm never had (the bare `RecordN` keys container and the single-record
companion call now come out of the generator and through the Java 17 gate), and three execution
pins: a key-only record resolving full column data at single cardinality, a key with no live row
resolving null, and a service returning no record resolving null through the emitter's own
`result == null` gate. The two null routes are separate tests because only the second reaches that
gate; the first goes through the companion and comes back empty.

Verification: `mvn install -Plocal-db` on the rebased tree, all modules green.
