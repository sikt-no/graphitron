---
id: R1041
title: "A mutation called with an empty input list returns an empty payload without routing to a tenant"
status: Spec
bucket: bug
priority: 3
theme: runtime-connection
depends-on: []
created: 2026-10-15
last-updated: 2026-10-15
---

# A mutation called with an empty input list returns an empty payload without routing to a tenant

## Goal

In a database-per-tenant build (one database per tenant, each request routed to the database its arguments name), a field whose tenant is read only from the elements of a list argument, called with an empty list, gets an answer instead of a redacted error. Where the answer to "nothing" is an empty list (every bulk DML mutation, a `@service` whose method returns a list, a `@lookupKey` query) graphitron returns it without opening any tenant connection and without calling the service. Where graphitron cannot build the answer itself (a `@service` returning its own payload class, a filter where an empty list means "no filter"), the client gets a readable error saying the empty list names no tenant, in place of today's "An error occurred. Reference: <uuid>".

Mined from the multi-tenant sakila fixture (`graphitron-sakila-example/src/main/resources/graphql/multitenant.graphqls`), whose tenant column is `film_id`:

```graphql
type Mutation {
    # Bulk DML: the tenant is decoded from each element's node id.
    retitleFilmsChecked(in: [UpdateFilmByNodeIdInput!]!): FilmsRetitledPayload @mutation(typeName: UPDATE, table: "film")
    # @service returning its own payload class (RateFilmsPayload { ranOn: String, films: [Film!]! }).
    rateFilms(in: [FilmRatingInput!]!): RateFilmsPayload @service(service: {
        className: "no.sikt.graphitron.rewrite.test.services.FilmRatingService",
        method: "rateFilms", argMapping: "ratings: in"})
}
```

```graphql
mutation { retitleFilmsChecked(in: []) { films { title } errors { message } } }
# today:  errors: ["An error occurred. Reference: <uuid>"]
# after:  { "retitleFilmsChecked": { "films": [], "errors": null } }, no tenant database opened

mutation { rateFilms(in: []) { films { title } } }
# today:  errors: ["An error occurred. Reference: <uuid>"]
# after:  errors: ["Mutation.rateFilms names no tenant: its tenant is read from the elements of 'in', which is empty."]
```

The sis port hit the first shape on three mutations (below); Graphitron 9 returned an empty payload for all three, and sis's integration tests assert it.

## Evidence

Reported from the sis port on 2026-10-15 against trunk `e4cfef7c1`. Three sis integration tests fail:

- DML UPDATE: `endreStudieprogramOpptakskrav(input: [EndreStudieprogramOpptakskravInput!]!)` with `@mutation(typeName: UPDATE, table: "studieprogram")`, tenant from the `id: ID!` Studieprogram node id. IT `EndreStudieprogramOpptakskravMutationIT.endreStudieprogramOpptakskrav_tomInputliste_returnererIngenStudieprogram`.
- `@service`: `angiStatsborgerskap(input: [AngiStatsborgerskapInput!]!)`, tenant from each element's `personProfilId` `@nodeId`. IT `StatsborgerskapMutationIT.angiStatsborgerskapMedTomInputlisteReturnererIngenRader`.
- `@service`: `registrerVeiledningsforhold(input: [RegistrerVeiledningsforholdInput!]!)`, tenant from the elements' `@nodeId` fields. IT `VeiledningsforholdMutationIT.registrerVeiledningsforhold_medTomListe_girTomPayloadUtenFeil`.

The two sis services return a list of records that the payload projects (the carrier shape), so they take the empty-list arm below; the service is not called, and sis does not need the call.

Why it fails today. A tenant-routed field declares `K _divinedTenant = TenantConnections.divinedTenant(<slot reads>)` (a *slot* is one argument or input field that carries the tenant value; *divining* folds every slot's runtime value into the one tenant the call runs on), then `DSLContext dsl = TenantConnections.dslFor(env, _divinedTenant)`. For a slot inside list elements the read is `TenantConnections.tenantSlot(env.getArgument("in"), ...)`, which yields an empty list for `[]`; the fold finds nothing and `divinedTenant` throws `NoSuchElementException("The tenant binding value is absent; ...")`. No payload error type maps it, so it is redacted.

After R1019 every routed binding has at least one slot the client cannot leave out, so a list that arrives empty is now the only way a client reaches that throw. A null list cannot occur on these `[X!]!` arguments, and a non-empty list always yields a value because every element's slot is non-null.

## What the client sees

The rule, per field, when the request's lists name no tenant:

[cols="2,2,3"]
|===
| Field shape | Answer | Why

| DML mutation with a list input (INSERT, UPDATE, UPSERT, DELETE; direct return or payload)
| Empty list (payload with empty data list and null `errors`)
| Nothing to write, so nothing to route. Holds in single-tenant builds too.

| `@service` whose answer is a list (a `@table` list return, a `List` / `Set` / `Collection` / jOOQ `Result` carrier, a polymorphic or table-interface list return)
| Empty list, wrapped as a success when the payload has `errors`; the service is not called
| Graphitron can build the answer, and calling the service needs a tenant the request never named.

| Root `@lookupKey` query
| Empty list
| A lookup answers one slot per key; no keys, no slots, already its single-tenant answer.

| Everything else: a `@service` returning its own payload class, a scalar or a single record; a list filter (where an empty list means "unfiltered"); a single-valued return; a tenant read through a list nested inside the input's elements
| Client error naming the field and the empty argument
| Graphitron cannot answer without calling the service, the answer would be every row of an unnamed tenant, or the outer list still has rows to act on.
|===

A field that also names its tenant through a slot outside any list (a non-null top-level argument, a context argument) is unaffected: it routes as today, and a `@service` is called with the empty list.

## User documentation (first-client check)

Draft for `docs/manual/how-to/tenant-scoping.adoc`, a new paragraph after the optional-arguments paragraph in §2 (the one ending "since `[ID]!` can arrive as `[null]`"):

> A non-null list can still arrive empty, and an empty list names no tenant. When a field's tenant comes only from list elements, graphitron answers an empty list without opening any tenant connection where the answer is plainly empty: a bulk mutation writes nothing and returns an empty list (an empty data list and no errors, under a payload), a `@lookupKey` query returns an empty list, and a `@service` whose answer is a list returns an empty list without calling the service. A service that must run even for an empty batch, to write an audit row say, needs a tenant from outside the list: a non-null argument or a context argument. Where graphitron cannot build the answer itself (a service returning its own payload class, a filter where an empty list means "no filter", or a tenant read from a list nested inside each element, where the outer list may still hold rows), the request fails with an error naming the field and the empty argument.

And one sentence in the `@service` paragraph of §2, after "a batch naming two tenants is refused before the service is called": "An empty batch that names no tenant does not call the service at all; see above."

## Implementation

The change refines a decision the tenant fold already makes (`TenantBinding` lives on the transitional model the classification walk produces, with no store relation), so it adds components to existing records rather than a new leaf. Everything the emitters need is decided in the model; the emitters render arms.

**Model: a slot says which lists it reads through.** `TenantBinding.BoundSlot` replaces `boolean required` with a sealed `SlotPresence`:

- `Optional`: the client may leave it out (today's `required == false`).
- `Guaranteed`: present on every request; a non-null path with no list on it, or a context argument.
- `PerElement(List<List<String>> crossedLists)`: non-null all the way down, but the path crosses one or more lists, so it yields one value per element and none for an empty list. Each entry is a crossed list's path from the argument: `[["in"]]` for `in: [X!]!` with the tenant on `X`, `[["ids"]]` for a top-level `ids: [ID!]!`, `[["in"], ["in", "b"]]` when `X.b: [B!]!` carries it.

`required()` stays as the derived accessor `!(presence instanceof Optional)`, which still means "non-null all the way" and nothing more, so R1019's consumers read unchanged. `TenantBindingIndex.slotRequired` becomes `slotPresence`, the same SDL walk recording each `GraphQLList` it unwraps on a non-null path; `SlotCollector.add` mints it. The presence is read off the consuming field's own definition, as `slotRequired` is today: one input type can be list-wrapped at one field and not at another. This recovers the list fact the fold drops at mint time (`collectFromValueShape` recurses through `ValueShape.ListOf` and forgets it) from the definition the walk already reads, rather than threading it through every collector.

**Model: the binding says what an empty request gets.** `TenantBinding.ArgumentBound` gains a component `WhenNoTenant whenNoTenant`, sealed:

- `Unreachable`: some slot is `Guaranteed`, so every request names a tenant.
- `AnswerEmpty(EmptyValue value)`: no slot is `Guaranteed`, every `PerElement` slot crosses only its own argument's list (`crossedLists` is exactly `[[arg]]`), and the field's answer to no input is an empty value graphitron can build. With that shape, the fold comes up empty exactly when those top-level lists are all empty (and any optional slot is omitted), so the empty answer is the field's own answer to an empty batch.
- `Refuse(List<String> arguments)`: no slot is `Guaranteed`, and either the field has no buildable empty answer or a slot crosses a nested list (a non-empty `in` whose every `b` is empty names no tenant, yet has rows to act on, so an empty answer would be wrong). `arguments` are the top-level arguments the `PerElement` slots read through, for the message.

`EmptyValue` is sealed `EmptyList | EmptySet | EmptyResult(TableRef table)`. The constructor checks `Unreachable` holds exactly when some slot is `Guaranteed`. The `ArgumentBound` javadoc and `Reading.Guaranteed`'s ("every request names a tenant") are corrected in the same commit: a required slot read through a list names a tenant only when the list is non-empty, and `whenNoTenant` says what happens otherwise.

The fold mints `whenNoTenant` where it mints `ArgumentBound` (the `Reading.Guaranteed` arm of the verdict), from the slots and an exhaustive switch over the field's classified leaf:

[cols="3,2"]
|===
| Leaf | `AnswerEmpty` value, when the slot shape allows it

| `MutationField.DmlTableField` with a list input
| `EmptyList`

| `MutationField.MutationBulkDmlRecordField`
| `EmptyResult(table)` (the keys the payload's data field re-reads)

| `MutationServiceTableField` / `QueryServiceTableField`, `MutationServicePolymorphicField` / `QueryServicePolymorphicField`, `MutationServiceTableInterfaceField` / `QueryServiceTableInterfaceField`, with a list return
| `EmptyList` (the fetcher value is the re-selected `List<Record>`, whatever the service's own container)

| `MutationServiceRecordField` / `QueryServiceRecordField`
| from the service's return container (below): `EmptyList`, `EmptySet` or `EmptyResult(table)`; `Refuse` for a payload class, a scalar, a single record, `Optional` or `CompletableFuture`

| `QueryTableField` launched as a keyed lookup (`@lookupKey`) with a list return
| `EmptyList`

| everything else (filters, connections, single returns, the child batched lookup)
| none: `Refuse`
|===

The service's return container is a fact of its own, minted where the return type is reflected: `ServiceMethodCallWalker` records a sealed `ReturnContainer` on `ServiceMethodCall` (`ListLike` for `List` / `Collection`, `SetLike`, `JooqResult(TableRef)`, `Other`), so no emitter inspects a type name. The child batched lookup under an untenanted parent could answer `emptyScatter(keys.size())`, but that needs its thin fetcher's hand-down and its rows method's declaration changed together for a shape no reported schema has; it gets `Refuse` now.

**Runtime: an absent-tolerant fold.** `ConnectionRuntimeClassGenerator` emits `TenantConnections.namedTenant(Object... candidates)`, the current fold returning `null` when no slot names a tenant; `divinedTenant` becomes `namedTenant` plus its existing throw, so every `Unreachable` site keeps its message.

**Emission: every site renders the arm.** `TenantDslEmitter.resolve`, `resolveByName` and `handDownOnly` switch on `whenNoTenant`:

- `Unreachable`: today's `divinedTenant` declaration, byte for byte.
- `Refuse`: `K _divinedTenant = TenantConnections.namedTenant(<slot reads>);` then, when null, throw the generated `GraphitronClientException` (the type the disagreeing-tenants refusal already raises) with `<Type.field> names no tenant: its tenant is read from the elements of 'in', which is empty.` (for several arguments: `... of 'a' and 'b', which are empty.`).
- `AnswerEmpty`: the same `namedTenant` declaration; when null, return the site's rendering of the `EmptyValue` (`List.of()`, `Set.of()`, `DSL.using(SQLDialect.DEFAULT).newResult(<fields>)`), wrapped through `returnSyncSuccessWrapped` when the field has an error channel, with no hand-down. A site handed an `AnswerEmpty` it has no rendering for is a generator bug and throws at generation, so the leaf table above and the sites cannot drift apart silently; the pipeline tier compiles every leaf in it.

On the service sites (`TypeFetcherGenerator.buildServiceFetcherCommon`, `MultiTablePolymorphicEmitter.buildServiceMainFetcher` and `buildServiceTableInterfaceFetcher`) the declaration sits after the `validatorPreStep` (ahead of the `try` today, and staying there) and ahead of `ServiceMethodCallEmitter.emit`, so neither argument extraction nor the service call runs on the empty path. On `buildQueryTableFetcher` it sits ahead of the `rows<Field>(dsl, env)` call, whose own `rows.length == 0` gate in `RootLauncherRenderer.lookupBody` gives the same answer one step later in single-tenant builds.

No hand-down on the empty path is safe because nothing below an empty answer acquires a connection: the payload's record-sourced data field (`buildBatchedDataFetcher`) calls `loadMany` over empty keys, which dispatches no batch, and `tenantLoaderName` tolerates a null `localContext`; the `errors` field reads the `Outcome` source only.

**DML: the hoisted guard is the rendering.** The two DML builders already guard `in.isEmpty()`, after routing: `TypeFetcherGenerator.buildDmlFetcher` (list arm, every `DmlTableField` verb) after `TenantDslEmitter.resolve` and `emitDialectGuard`, and `buildBulkRecordTwoStepFetcher` (`MutationBulkDmlRecordField`) after `resolve`, building its empty `Result` with the routed `dsl.newResult`. Both move the `in` cast and the guard above `resolve` and the dialect guard, and build the empty value from the `EmptyValue` rendering (`List.of()`, or `DSL.using(SQLDialect.DEFAULT).newResult(<pk fields>)`), with no hand-down.

In a multi-tenant build that hoisted guard is the site's rendering of `AnswerEmpty`: the arm's slots cross exactly `[[inputArg]]`, so the fold is absent exactly when `in` is empty, and the guard has already returned. The DML builders check that the arm names exactly their input argument (anything else is a generator bug) and then emit the plain `divinedTenant` declaration. A DML binding the fold marked `Refuse` (a nested-list slot) renders the `namedTenant` refusal after the hoisted guard instead.

The hoist is unconditional, single-tenant builds included: an empty batch takes no connection there either. The one visible change is that an empty list on a dialect the verb rejects (UPSERT on Oracle, bulk UPDATE off Postgres) returns the empty answer instead of the dialect error, which is right for a statement that never runs.

**Command path.** `TenantAcquisition.ArgumentBound`, the plan-row restatement `TenantAcquisitionFragments.declare` renders, gains the same `whenNoTenant` arm, restated by its producer (`RoutineWriteCommands`) from the binding, and the renderer switches on it as `TenantDslEmitter` does. No row reaches that arm today (`@routine` writes mint no slot), so this keeps the two paths agreeing by construction rather than changing behaviour.

## Tests

Execution tier, in `TenantDivinedRoutingExecutionTest` against the multi-tenant fixture, each asserting the answer and, through the `TENANT_1_OPENED` / `TENANT_2_OPENED` counters, that no tenant database was opened:

- `deleteFilmActorsByNodeId(in: [])` returns `[]` (direct-return DML DELETE).
- `updateFilmSceneParents(in: [])` returns `[]` (bulk UPDATE, which also carries the Postgres dialect guard the hoist passes).
- `retitleFilmsChecked(in: [])` returns `{ films: [], errors: null }` (payload DML on the error channel, the sis `endreStudieprogramOpptakskrav` shape).
- A new fixture mutation in the sis service shape: a list-input `@service` with an `errors` field whose method returns `List<FilmRecord>` that the payload projects. With `in: []` it returns an empty data list and null `errors`, and the stub method throws on an empty list, so a passing test proves the call was skipped.
- A new fixture mutation over a `@table` list return (`[Film!]!`, `MutationServiceTableField`), stub throwing on empty: returns `[]`.
- `rateFilms(in: [])` (class-backed `RateFilmsPayload`): one error whose message names `Mutation.rateFilms` and `in`, not a redacted reference.
- `filmActorsByNodeId(ids: [])` (top-level list filter, decoded slot): the same readable refusal.
- A new `@lookupKey` root over the tenant column (`filmsByKey(filmId: [Int!]! @field(name: "film_id") @lookupKey): [Film]!`): `[]`.
- Non-regression: a non-empty call on each new fixture field routes as before, and the existing single-tenant `DmlBulkMutationsExecutionTest` empty-list cases (`createFilms`, `updateFilms`, `upsertFilms`, `deleteFilms` with `in: []`) stay green across the hoist.

Classification tier, in `TenantBindingClassificationTest`:

- `SlotPresence` per slot shape: `in: [X!]!` with the tenant on `X` is `PerElement([["in"]])`, a top-level `ids: [ID!]!` is `PerElement([["ids"]])`, a nested `X.b: [B!]!` is `PerElement([["in"], ["in", "b"]])`; a scalar `filmId: Int!` and a context argument are `Guaranteed`; `[ID]!` and nullable paths are `Optional`.
- `whenNoTenant` per leaf row of the table under Implementation, each asserted as the typed arm and value: `AnswerEmpty(EmptyList)` for a list DML and a list-returning service, `AnswerEmpty(EmptyResult(film))` for the payload DML, `Refuse(["in"])` for a class-backed service payload and a filter, `Refuse` for a nested-list slot, and `Unreachable` for a service mixing an `in: [X!]!` slot with a non-null top-level `@tenant` argument.
- `ServiceMethodCall`'s `ReturnContainer` for `List`, `Set`, `Result<FilmRecord>` and a payload class.

No test asserts on generated code text; the execution tier pins the behaviour, and the multi-tenant fixture's compile at `<release>17</release>` pins that every `AnswerEmpty` rendering compiles.

The sis ITs above are the external acceptance; they are not in this repo.

## Other solutions we've considered

- **Call the service on the default source for an empty list** (Graphitron 9 parity: it called the service). Rejected: the default source is not the tenant's database, so a service that writes anything for an empty batch would write tenant data where it does not belong, and the request never named a tenant to justify the choice.
- **Call the service only when it binds no connection** (no `DSLContext`, no `$session`). It would rescue the class-backed payload shape for connection-free services, but the call still hands no tenant down, so a payload with a tenant-scoped non-list child would fail one level lower. A second branch for a shape nobody reported; the readable refusal tells the author to return a list.
- **Let each emit site opt into an empty answer**, with refusal as the default. It reaches the same output, but the semantic fact (does an empty list call the developer's service?) would live only in which builder methods opted in: invisible to the docs and the LSP, and a future list-returning service leaf would fall to refusal with nothing failing. The `whenNoTenant` arm puts the decision in the model and makes every site's switch exhaustive.
- **DML sites render the `namedTenant` guard instead of hoisting.** It would leave the existing `in.isEmpty()` guard after routing, dead in multi-tenant builds and live in single-tenant ones. The hoisted guard renders `AnswerEmpty` before acquisition, and the arm's `[[inputArg]]` list shape is what justifies the plain declaration after it.
- **Reject list-only tenant bindings at build time**, as R1019 rejects optional-only ones. Rejected: an empty list is rare and legitimate, and the shape is the normal one for bulk writes; the build would refuse every bulk mutation in the sis schema to prevent a case that now has a defined answer.
- **Make `divinedTenant`'s absent throw a client error everywhere.** An absent fold on a binding with a `Guaranteed` slot means a null context argument, a server wiring fault the client cannot fix, so it should not read as the client's mistake.
