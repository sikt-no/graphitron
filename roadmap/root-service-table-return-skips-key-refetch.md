---
id: R834
title: "A top-level @service returning a @table type reads columns off the returned record instead of refetching by key"
status: Spec
bucket: bug
priority: 2
theme: service
depends-on: []
created: 2026-08-25
last-updated: 2026-08-25
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
against the tree on 2026-08-25.

**1. Model: mint the reentry member.** The passthrough is one clause, `rootServicePassthrough`, in
`OperationMembers.mintsReentry`, with a twin production in `OperationMemberRelation`; the two are
pinned equal by `OperationMemberMintPinTest`, so they flip together. Deleting the clause makes the
mint read as the positive fact it always gated: a bare table target holding produced records mints
reentry, full stop. `OperationMembers.DECLARED_SHAPES` must simultaneously admit `Kind.REENTRY`
into the optional set for `QueryServiceTableField` and `MutationServiceTableField`, or
`validateAgainstDeclaredShape` hard-fails construction. After the flip, `emitsKeyedReQuery()` and
`requiresReFetch()` agree at every coordinate; the javadoc that currently explains their one
disagreement (on `OutputField`, `OperationMember.Reentry`, and the two service-table leaves)
rewrites to the new rule.

**2. Launcher: a root-service verdict.** `LauncherCommands.verdictOf` answers `Launch.NONE` for a
root service today (the `SERVICE` rule is gated on not-root), and `LauncherRelationClosureTest`
pins that absence. Add a `Launch` arm for the root-service reentry, verdict "root, has a
`ServiceCall` member, has a `Reentry` member", minting a `LaunchSource.ProjectedReentry` whose
`ParentCorrelation.OnLiftedSlots` correlation is the return table's own primary key. That is PK
self-identity, the same correlation `FilmCardWrapperFetchers.rowsFilm` runs on. `Launch` is a total
switch at every consumer, so the compiler walks the cascade. `RootLauncherRenderer` already
dispatches `ProjectedReentry` to `ReentryRowsFragments`; the renderer needs no change.

**3. Emitted body: reuse `ReentryRowsFragments`.** `projectedBody` already emits both
cardinalities: `VALUES(idx, pk...)` join plus `$project` from the live selection set plus
`ORDER BY idx` for lists, and a degenerate plain key equality for single. Its three inputs (target
table, key columns, projection unit) all derive from facts the leaf already carries. One real type
decision remains: `LauncherCommands.INVOCATION_BY_SOURCE` has no arm for "keys captured by the
caller from a service return"; `Invocation.Batched` presumes a loader and `ReturningKeyed` presumes
a DML `RETURNING`. Recommendation: a new `Invocation` arm rather than a widened `ReturningKeyed`,
because `ReentryRowsFragments.keysType` derives the keys-parameter type from the invocation and an
honest arm keeps the census readable; implementation may land on widening if the type plumbing
turns out identical, with the constraint that the keys type matches what `projectedBody` consumes.

**4. Caller-side lift.** `buildServiceFetcherCommon` in `TypeFetcherGenerator` (shared by the query
and mutation twins) grows a post-call step: lift each returned record's primary key, call the
minted `rows<Field>` companion, return its result instead of the raw records. The lift loop shape
already exists twice, in `MultiTablePolymorphicEmitter.buildServiceDispatchBlock` and
`ServiceRowsFragments.liftBody`. When the service method declared no `dsl` parameter, synthesize
the local from `TenantDslEmitter.dslExpression`, exactly as `MultiTablePolymorphicEmitter` does for
the polymorphic refetch. The refetch therefore runs on `graphitronContext(env).getDslContext(env)`,
the request's connection, matching the polymorphic path.

**5. Domain return type.** `QueryServiceTableField.domainReturnType()` is
`DomainReturnType.Record(table)` today because children walked the service's typed record. After
the change children walk the re-fetched projected row, like every catalog read, so the domain
return type moves to whatever the catalog root read answers and downstream child wiring agrees.
This is the widest-reaching edit in the item; see Risks.

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
member. Third, the existing rejection of an error channel on a reentry `@service` field (the
reentry fetcher inlines its channel arms on a single-channel premise) must be checked against root
services carrying error channels, which become reentry under this item; if the combination is
live in fixtures, the reentry emit learns the channel arms rather than the rejection widening, and
either outcome gets a pipeline pin.

## The backlog's four questions, settled

**Escape hatch reach.** The no-`@table` shape works today and is pinned: `FilmDetails` has no
`@table`, is backed by jOOQ's `FilmRecord` (`JooqTableRecordType`, asserted by
`SharedDomainTypeProducerPipelineTest`), and `FilmDetailsFetchers.title` reads the column straight
off whatever the producer handed over. So the escape hatch exists and costs nothing new. What it
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

**Phase 1, additive.** The keyless-return-table rejection at the root arm: classifier diagnostic
plus validator mirror plus pipeline pins, cloned from the child arm's wording and from the
reject-plus-control shape of `PkLessParentServiceSourcesRejectionTest`. Lands alone and green
before any behaviour changes.

**Phase 2, cutover.** One commit, because the pieces are coupled by the implementedness guard:
the model flip (both mint homes plus `DECLARED_SHAPES`), the `Launch` and `Invocation` arms with
the `ProjectedReentry` row producer, the caller-side lift in `buildServiceFetcherCommon`, the
domain-return-type move, and the guard admission. Pin updates ride along:
`OperationMemberMintPinTest`, `ReFetchDerivationTest`, `LauncherRelationClosureTest` (the pinned
absence of a root-service launcher row becomes a pinned presence),
`GraphitronSchemaBuilderTest`'s service rows, the corpus examples whose outcome tables show the
passthrough, and `TypeFetcherGeneratorTest`'s body-shape comment.

**Phase 3, proof and prose.** Rewrite `SampleQueryService.filmsByService` to populate only
`FILM_ID`, which is the fixture the tier never had; its siblings (`filmsByServiceRenamed`, the
path-mapping family) stay full-select to prove already-full records keep working and the extra
lookup is harmless. Execution pins for the key-only fixture, the missing-row drop, and the
existing `titleTitlecase` child resolving off the projected parent. Update the service javadoc
contracts (`SampleQueryService` states the passthrough today; `PolymorphicSearchService` and
`ContentSearchService` become statements of the now-universal rule). Docs per the section below.

## Test surface

- Pipeline: the pin updates named in Phase 2; new reject-plus-control tests for the keyless root
  return; a pin for the error-channel outcome of Design point 7.
- Unit: `TypeFetcherGeneratorTest.queryServiceTableField_emittedFetcher_declaresTypedResult`
  re-asserts the new declared return type; body shape stays delegated to execution tier per its
  own comment.
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

- The domain-return-type move (Design point 5) is the widest unknown: every child shape hanging
  off a service-returned `@table` parent (nesting fields, batched children, reference paths, the
  `titleTitlecase` wrap) must agree on the projected-row source. Each child shape is already
  execution-covered off catalog reads; the residual risk is an arm keyed specifically on the
  service parent's typed record, and the verification build surfaces it.
- The error-channel interaction (Design point 7) may force the reentry emit to learn channel arms,
  which grows Phase 2.
- A consumer whose service deliberately returned column values differing from the table gets
  table values after upgrading. That is the intended fix of the reported bug, but it is a silent
  semantic change for anyone relying on it; the changelog and the escape-hatch paragraph are the
  mitigation.
- `ValuesJoinRowBuilder` caps the VALUES row at `Row22`, so composite primary keys up to 21
  columns; the same cap every existing keyed path has, no new constraint.

## Done criteria

- Generated `QueryFetchers.filmsByService` lifts keys and returns the `rows<Field>` companion's
  result; no direct passthrough of service records bound to a `@table` type remains.
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
