---
id: R978
title: "Refuse a connection-binding root service that names no tenant under database-per-tenant"
status: Ready
bucket: bug
priority: 2
theme: runtime-connection
depends-on: []
created: 2026-09-25
last-updated: 2026-10-05
---

# Refuse a connection-binding root service that names no tenant under database-per-tenant

## Goal

Under database-per-tenant routing (a `<tenantColumn>` build, where each tenant's data lives in its own database), a root `@service` field that receives a connection (a `DSLContext` or the `$session` handle) and whose arguments name no tenant is refused at build time with a message naming the fix, instead of silently running on the default database. A service that really works only on global data says so with a new field directive, `@globalData`, and keeps building. The build checks that declaration against everything it can see, so `@globalData` cannot quiet the refusal on a service whose arguments or return type show that it works on tenant data.

A root service that names its tenant builds and routes today. This one is from the multitenant example schema. The `FilmRating` bean's `film` member is a `FilmRecord`, so the id is decoded and the call runs on that film's tenant database:

```graphql
input FilmRatingInput { film: ID! @nodeId(typeName: "Film")  rating: String }
type Mutation {
    rateFilms(in: [FilmRatingInput!]!): RateFilmsPayload @service(service: {
        className: "...FilmRatingService", method: "rateFilms", argMapping: "ratings: in"})
}
```

The same field over a bean whose `film` member is a `String` holding the encoded id names no tenant that the build can read. Today it builds, and `rateFilms` writes its ratings into the default database. After this item it is a build error:

```
'Mutation.rateFilms' is a @service that is handed a connection, but nothing in its arguments
names a tenant for tenant column 'film_id', so it would run on the default database. Take the
node table's jOOQ record with @nodeId(typeName:) (a bean member, a parameter, or a top-level
argument), or bind a jOOQ record field to 'film_id'. If the service works only on global data,
mark the field @globalData.
```

A service over reference data, or one that only reads the mounted session handle, is marked and keeps running on the default database:

```graphql
type Query {
    sessionPrincipal: String @globalData @service(service: {
        className: "...SessionIdentityService", method: "sessionPrincipal", argMapping: "identity: $session"})
}
```

## Background: where the gap is

`TenantBindingIndex.Fold.armOf` gives every classified output field its `TenantBinding` arm, which records how the field picks the connection it runs on. Three arms matter here: `ArgumentBound` (the field's arguments name the tenant), `Inherited` (the field runs on a tenant its parent fixed), and `Untenanted` (the field runs on the default source, the database that holds the global tables). The decision rests on the field's *reach*: `reachedTables`, the tables graphitron's own SQL for the field touches.

R976 (shipped; see `roadmap/changelog.md`) taught the fold to divine a root service's tenant from its arguments. A service's own SQL is opaque, so a root service that divines nothing is decided by its reach alone:

- **Tenant-scoped `@table` return**: already rejected, with the generic `NoTenantBinding` text ("no argument or input field maps to tenant column"), which says nothing about services.
- **Global `@table` return**: `Untenanted`, and correctly so. Graphitron re-reads the returned rows on the service's connection, and global rows live on the default source.
- **Empty reach** (a scalar, a payload wrapper, any non-`@table` return): `Untenanted`. **This is the gap.** If the service is handed a connection, it runs its SQL on the default database whatever that SQL touches. A payload wrapper with tenant-scoped children is caught one level down ("no ancestor established a tenant context"), but a scalar or `Boolean` return, a payload without tenant-scoped children, and every write the service makes before returning are not caught.

A root service that is handed no connection is not part of the gap. It cannot reach graphitron's connection at all, and it stays `Untenanted`.

## Consumer-side resolution (sis, decided 2026-09-25)

sis has 40 service methods that take an encoded node id as a plain `String`. 34 of them are bean members such as `AktiverFagpersonerRecord.fagpersonVedLarestedID`, and 6 are bare `List<String> ids` parameters such as `UndervisningsaktivitetService.godkjennForPublisering`. They migrate to the node table's jOOQ record, with `@nodeId(typeName:)` on the SDL field: a `FagpersonRecord` member, a `List<UndervisningsaktivitetRecord>` parameter. That is the `CallSiteExtraction.NodeIdDecodeRecord` path R976 reads. A bean member is read through `InputBeanResolver` and a parameter through `ServiceCatalog.nodeIdSlotExtraction`, whose `takesTheNodeTablesRecord` admits a `List<XRecord>` slot. No generator change is needed for them. The migration also removes the wire-format leak the principles name, since the services stop decoding Relay ids themselves. The refusal below would fire on each of the 40 unmigrated methods, which shows it is aimed at the right population.

Runtime inference from the embedded type id, as v9 did, was weighed during R976's spec and not chosen, because the classification verdict would then rest on per-request evidence.

## Implementation

### One "needs a tenant" fact in `armOf`

Today `anyTenant` (some reached table is tenant-scoped) silently stands for "this statement needs a tenant": the decline gate reads it, and so do the `Untenanted` fallthrough and the generic rejection. This item widens what needs a tenant, so the widening is computed once as a local and every one of those sites reads it:

```java
boolean connectionService = roots.contains(coord.getTypeName())
    && out instanceof ServiceField && bindsConnection(out) && !anyGlobal;
boolean needsTenant = anyTenant || connectionService;
```

`!anyGlobal` keeps the global-`@table`-return case structurally decided, as it is today. The new `armOf` order:

1. Marker dispatch, ahead of everything else, where `fanMarked` sits today. A field carrying both `@globalData` and `@tenantFanOut` gets the `directiveConflict` rejection. A field carrying `@tenantFanOut` goes to the fan-out ladder, unchanged. A field carrying `@globalData` goes to the marker ladder (below) and returns its verdict. Like the fan-out ladder, the marker ladder computes reach and the direct binding itself, so a marked field never reaches the cross-scope rejection below. Every marked `OutputField` therefore gets a ladder verdict, which is what the sweep's predicate counts. A cross-scope reach always holds a tenant-scoped table, so a marked cross-scope field is answered by rung 2 or rung 4 with a marker-specific text.
2. The cross-scope rejection, unchanged.
3. The decline gate reads `needsTenant` instead of `anyTenant`. Without this, an empty-reach root service whose only tenant-bearing argument is a declined shape, such as a polymorphic `@nodeId`, would get the generic service refusal rather than the decline's own text, which names what is wrong with the binding the author did write. Reach decides which arm the gate emits. With a tenant-scoped table in reach, it emits one `NoTenantBinding` per decline, as today, because that arm's "reaches tenant-scoped table" opening is true there. With empty reach, where `needsTenant` holds only because `connectionService` does, it emits one `UnroutedServiceCall` carrying the declines, so no message names a table the field does not reach.
4. `divines` → `ArgumentBound`; `NODE_RESOLVE` → as today; the child-service `Inherited` rule → as today.
5. `!needsTenant` → `Untenanted` (was `!anyTenant`).
6. Tenant context → `Inherited`. A root coordinate never has one.
7. Reject. If `connectionService` holds, the rejection is the new `UnroutedServiceCall` below. Otherwise it is the existing generic `NoTenantBinding`. Tenant-reach root services that bind a connection move to the new text, because the fix they need is the same one.

### Tenant evidence from the existing argument walk

`SlotCollector` gains a third outcome beside slots and declines: *tenant evidence*, meaning an argument value typed by a tenant-scoped table that bound no tenant slot. `collectFromServiceCall` already walks the argument `ValueShape` tree with the same `tenantScoped` test, so the evidence is recorded where that walk already looks, and there is no second walk:

- `collectFromJooqRecord`: the record's `carrier().table()` is tenant-scoped, but neither a column binding nor a key decode added a slot. One example is a `FilmRecord` bound only to `title`.
- `collectFromServiceLeaf`: a `NodeIdDecodeRecord` or `NodeIdDecodeKeys` of a tenant-scoped node type whose key does not hold the tenant column (`tenantIndex < 0`).

`DirectBinding` carries the evidence as a list of human-readable names (`"FilmRecord at in.film"`). Two readers use it: the refusal's message names it, and the marker ladder refuses to accept the marker over it. The evidence is not a new routing rule. `collectFromServiceCall`'s early return for a global-reach service stays as it is, since such a service is decided structurally (R976; the manual already tells authors not to hand a tenant's record to a service that works on global tables).

### The new rejection arm

`Rejection.AuthorError.UnroutedServiceCall(String coordinate, String tenantColumn, List<String> declines, List<String> evidence)`. It is a new arm because `NoTenantBinding.message()` opens with "reaches tenant-scoped table 'X'", which is false for an empty-reach service. One field gets at most one `UnroutedServiceCall`, and `declines` picks between its two message forms:

- **No declines** (from the reject step): the message shown in the Goal. When `evidence` is non-empty, it appends "Its arguments carry tenant-scoped values that bind no tenant: <evidence>.".
- **Declines** (from the decline gate at empty reach): the arguments name the tenant column, but only through shapes that cannot route the call. Each decline's own detail follows, and it carries its own fix. This form does not suggest `@globalData`, because rung 6 of the marker ladder rejects the marker over a decline:

```
'Mutation.pickForThing' is a @service that is handed a connection, and its arguments name tenant
column 'film_id' only through shapes that cannot route the call, so it would run on the default
database: the tenant is reached through a @nodeId slot naming 'FilmThing', whose members decode
the same id as different node types, each with its own key columns: [...]
```

The arm is added to `AuthorError`'s permits list and to `RejectionFacts.typedColumns` (`NONE`, like `NoTenantBinding`).

### The `@globalData` marker

Declared in `directives.graphqls` beside `@tenantFanOut` as `directive @globalData on FIELD_DEFINITION`, with no arguments. `BuildContext.DIR_GLOBAL_DATA` names it. The fold reads it off the SDL field definition the way `fanMarked` reads `@tenantFanOut`. No fact-store relation is added: an argument-less marker is already recorded by the applied-directive transcription (`graphql_ast_field_directive_entry`), and the comment at the `@splitQuery` / `@tenantFanOut` site in `graphitron-model.sql` already states this rule. `GraphitronFactCapture`'s per-directive switch gets no case either: its `default` arm already decodes nothing. The same count is stated in two places. In both, it goes up by one and `@globalData` joins the argument-less directives listed beside `@splitQuery` and `@tenantFanOut`. One is the `graphitron-model.sql` comment, where "Four of the site's sixteen directive names" becomes five of seventeen. The other is `GraphitronFieldEntries`'s class javadoc, where "Sixteen directive names reach this site and twelve of them get a relation" becomes seventeen, still with twelve relations.

The marker is accepted exactly where the refusal would otherwise fire, and the arm is then `Untenanted`. It is a closed ladder like the `@tenantFanOut` one: validate-time, each rung with its own text, and the first rung that applies wins.

1. The field is not on a root operation type: "`@globalData` is supported on root fields only; a child service runs on its parent's tenant."
2. The field is not a `@service`: "only a `@service` field's SQL is opaque to the build; graphitron decides this field's source from the tables it reads."
3. `!bindsConnection(out)`: "the service is handed no connection, so there is nothing to route; remove the directive."
4. `anyTenant`: "the field returns tenant-scoped `@table` type X; its rows cannot be re-read on the default source."
5. `anyGlobal`: "the field returns global `@table` type X, which already runs on the default source; the structure decides this field, remove the directive."
6. `direct.divines()` or non-empty declines: "the arguments name a tenant (<slot>), which contradicts `@globalData`."
7. Non-empty tenant evidence: "the arguments carry tenant-scoped values (<evidence>), so the service works on tenant data."

Rungs 4 to 7 are what stop the marker from becoming a silencer. Everything the build can see about the service's data either agrees with the marker or rejects it. What is left unchecked is the service's SQL, which is the one thing the build cannot see, and the marker is the author's signed statement about it at the field.

A field carrying both `@globalData` and `@tenantFanOut` is a `directiveConflict` rejection. It is dispatched first in `armOf` (step 1 above), before either ladder, and counts as a verdict for both markers.

### One completeness sweep for both tenancy markers

Rungs 1 and 2 fire only for coordinates that reach `armOf` as an `OutputField`. A marker on an interface field, on a nesting type's member, or on a field that failed classification would otherwise be ignored without a word. `sweepUnreachedFanOutMarkers` and `rejectMarkersWithoutTenancy` already close this gap for `@tenantFanOut` with two hand-written loops. Rather than writing two more, both become one sweep over a small table of tenancy markers. Each entry pairs the directive name with its single-tenant rejection text and its "reached a verdict" predicate: for `@tenantFanOut`, a `FanOut` arm or a fan-out rejection, as today; for `@globalData`, a ladder verdict (accepted or rejected). The two existing rejection texts are kept as they are.

### Docs

- `docs/manual/how-to/tenant-scoping.adoc` § 2: the paragraph that begins "A service's own SQL is opaque" is replaced by the draft below.
- New reference page `docs/manual/reference/directives/globalData.adoc`, linked from both lists in `directives/index.adoc`. The list in `docs/manual/_generated/supported-directives.adoc` is regenerated by the `directive-support` tool, not edited by hand.
- `SchemaDirectiveRegistryTest`'s expected directive list gains `globalData`.

## User documentation (first-client check)

The `tenant-scoping.adoc` paragraph:

> A service's own SQL is opaque to graphitron, so a root service that is handed a `DSLContext` or `$session` must name its tenant in its arguments, or the build refuses it rather than run it on the default source. Take the node table's jOOQ record with `@nodeId(typeName:)` instead of the encoded id as a `String`, and the call routes on the id. A service that works only on global tables, or only reads the mounted session handle, says so with `@globalData`, and runs on the default source. The build rejects `@globalData` wherever it can see tenant data: a tenant-scoped `@table` return, an argument that names a tenant, or an argument typed by a tenant-scoped table's record. The reverse holds too: a service that takes a tenant's record runs on that tenant's database, so it should not take one if it only works on global tables.

The `globalData.adoc` reference page:

> `@globalData` marks a root `@service` field whose service works only on global data, in a build with database-per-tenant routing (`<tenantColumn>`). The service runs on the default source. Without the marker, a root service that is handed a `DSLContext` or `$session` and whose arguments name no tenant is a build error, because graphitron cannot see which tables the service's SQL touches.
>
> ```graphql
> type Mutation {
>     refreshLanguages: Boolean @globalData @service(service: {className: "...LanguageService"})
> }
> ```
>
> The build rejects the marker where it decides nothing or contradicts what the build can see: in a build without `<tenantColumn>`, on a non-root field, on a field that is not a `@service`, on a service that is handed no connection, on a field returning a `@table` type (tenant-scoped rows cannot be read from the default source, and global ones already are), on a field whose arguments name a tenant or carry a tenant-scoped table's record, and beside `@tenantFanOut`.

## Tests

Pipeline tier, `TenantBindingClassificationTest`:

- **Refusal**: two cases each reject with `UnroutedServiceCall` in its no-declines form, and each message names `film_id` and both fixes.
  - An empty-reach root query service binding `$session`. This replaces `sessionBoundServiceAtAnUntenantedRoot_staysUntenanted`.
  - A root mutation service binding a `DSLContext` over an undecoded `String` id. This uses a new stub, `TenantServiceStub.rateByRawIdOnConnection(DSLContext dsl, String film)`, in a new test that also asserts the payload child's own rejection still fires.
- **No connection**: `anUndecodedIdNamesNoTenantSoTheWrappersChildStillRejects` stays as it is. Its stub, `TenantServiceStub.rateByRawId(String film)`, takes no connection, so it is the no-connection case: the root stays `Untenanted` with no root rejection, and the child still rejects.
- A tenant-reach root service binding a `DSLContext` gets the new arm rather than the generic text.
- **Evidence**: a root service taking a `FilmRecord` bound only to a non-tenant column rejects, naming the record in the evidence list.
- **Decline under the widened gate**: an empty-reach root service whose only tenant-bearing argument is a polymorphic `@nodeId` rejects with exactly one `UnroutedServiceCall`, in its declines form. The message contains the decline's own detail. It does not contain "reaches tenant-scoped table", the no-declines text, or a `@globalData` suggestion. `aPolymorphicRecordIdAtATableReturningRootRejectsWithItsOwnText` stays green unchanged, which pins that a tenant-reach decline keeps `NoTenantBinding`.
- **Marker accepted**: a root `$session` service and a root `DSLContext` service marked `@globalData` classify `Untenanted` with no rejections.
- **Marker ladder**: one case per rung (1 to 7), plus the `@tenantFanOut` conflict. Each asserts the rung's own text and that no other rung's text appears. One more case covers a marked field whose reach is cross-scope, for example a non-service polymorphic root over `Film` and `Language`. It gets its ladder rung's text alone (rung 2 there), and neither the cross-scope text nor the sweep's.
- **Sweep**: `@globalData` on an interface field, and on a field of a nesting type, reject. `@globalData` in a single-tenant build rejects through the shared sweep. The existing `@tenantFanOut` sweep cases in `TenantFanOutClassificationTest` stay green unchanged, which pins the refactor.

Execution tier, `TenantDivinedRoutingExecutionTest`, over `multitenant.graphqls`:

- A new root `Query.globalServedBy: String @globalData @service(...)` on a `FilmRatingService` method taking only a `DSLContext` and returning `current_database()`. The test asserts it answers the default database's name and acquires no tenant connection.
- The rest of `multitenant.graphqls` building unchanged is itself evidence: every root service in it divines, so the refusal must not fire there.

Before In Review, the implementer greps every `<tenantColumn>`-configured fixture (classification tests via `withTenantColumn`, the multitenant example, the corpus) for root connection-binding services that divine nothing. Each one found either marks itself or is rewritten, and each change is named in the In Review commit.

## Scope and the gap left open

Child services are out of scope, for a structural reason. A child service gets its tenant from its parent (R976's `Inherited` rule). Once the root refusal ships, a parent with no tenant context is global, explicitly `@globalData`, or already rejected. The remaining gap is a parent type reached by both a tenant-bound path and an unbound one. `tenantContextOf` folds over every path, so such a parent has no context, and a connection-binding child service under it runs on the default source without any error. This item names that gap and does not close it. Closing it means either a per-path verdict or refusing such a child, and both are a different design.

Consumer impact: this is a breaking build change. A schema with an unmarked, non-divining, connection-binding root service stops building, and the error names both fixes. The Done-gate changelog entry says so.

## Other solutions we've considered

- **Reading it off the return type.** A global `@table` return is already decided structurally. A payload wrapper over global tables says nothing about what the service writes before returning, because the wrapper's children fetch on their own connections.
- **Refusing mutations only.** A read on the wrong database returns another tenant's rows, or no rows, just as silently as a write lands in the wrong one. Staging the refusal would leave half the gap open with no point at which it closes.
- **An argument on `@service`.** Tenancy routing is otherwise declared by field-level directives (`@tenantFanOut`). The argument would also mean nothing in single-tenant builds, where `@service` is used just as much, so it would need its own rejection there anyway.
- **A Java annotation on the service method.** That puts the claim next to the SQL that justifies it, but it needs an annotation type on the consumer's classpath, and graphitron's declarations live in the SDL.
- **A mojo-configuration list of global services.** That moves a per-field fact out of the schema, where neither the LSP nor a schema reviewer sees it.
- **Naming the marker for the routing (`@defaultSource`).** The manual's vocabulary would support it, but the author is asserting something about the data ("this service touches only global data"), and the entry discipline records what the author meant. The routing follows from that.

## Reviewer findings

### Round 1 (Spec → Ready): request revisions

Question 1 passes. When this lands, a database-per-tenant build refuses a root `@service` that is handed a `DSLContext` or `$session` and whose arguments name no tenant, because today that call quietly runs on the default database. An author either takes the node table's record so the call routes, or marks the field `@globalData`, and the build rejects that marker wherever it can see tenant data. The claims about code all check out against the tree (the `armOf` sites, `bindsConnection`, `collectFromServiceCall`'s global-reach early return, `SlotCollector`, `DirectBinding`, the `NodeIdDecodeRecord` / `NodeIdDecodeKeys` leaves, `JooqRecord.table()`, the two loops being replaced, the `graphitron-model.sql` comment and `GraphitronFactCapture`'s `default` arm, the test names, the docs paths). The shape fits the tree: one widened predicate, evidence recorded by the walk that already runs, a closed marker ladder modelled on `@tenantFanOut`'s, and one sweep where there were two loops.

Question 2 fails on two points. In both, the plan leaves an author-facing rejection that is either false or reported twice, and fixing either one means choosing between rejection arms, which is the author's call.

1. **The widened decline gate sends empty-reach declines through the arm the spec rules out for empty reach.** Step 2 makes the gate `needsTenant && !declines.isEmpty()`, and the gate's body emits `Rejection.noTenantBinding(coordinate, tenantTable, detail)`, where `tenantTable` falls back to `scopes.columnName()` when nothing in reach is tenant-scoped. Today that fallback never runs, since the gate needs `anyTenant`. Once the gate is widened it does run, and the "Decline under the widened gate" test case would print `'Mutation.x' reaches tenant-scoped table 'film_id' with no tenant binding in scope: …`. That names a column as a table and says the field reaches it when it reaches nothing. The section "The new rejection arm" gives exactly this falsehood as its reason for adding `UnroutedServiceCall`. *To satisfy:* say which arm carries a decline at an empty-reach connection service (for example `UnroutedServiceCall` with the decline detail, or `NoTenantBinding` given a text that is true here), and make the decline test assert that the message contains no "reaches tenant-scoped table".

2. **`@globalData` is dispatched after the cross-scope rejection, but the sweep only counts ladder verdicts.** The new order puts the marker check after the cross-scope rejection (`anyTenant && anyGlobal`), and that rejection returns `null` before the ladder is reached. The sweep's "reached a verdict" predicate for `@globalData` is "a ladder verdict (accepted or rejected)". So a marked field that is cross-scope gets the cross-scope rejection and then the sweep's "never reached" rejection as well, and the second one is false. `@tenantFanOut` avoids this because `fanMarked` is checked before the cross-scope rejection. *To satisfy:* either dispatch `@globalData` (and the conflict check) ahead of the cross-scope rejection, as `fanMarked` is, or widen the sweep predicate to count any rejection at the coordinate. Then add the cross-scope case to the ladder tests.

Non-blocking:

- `TenantServiceStub.rateByRawId(String film)` takes no `DSLContext`, so under this plan `anUndecodedIdNamesNoTenantSoTheWrappersChildStillRejects` keeps its root `Untenanted` assertion (no connection, nothing to route). The refusal case described in Tests needs the stub to take a `DSLContext`, or a new stub. Say which, so the "replaces the root assertion" wording holds.
- The directive count in the `graphitron-model.sql` comment is repeated in `GraphitronFieldEntries`'s class javadoc ("Sixteen directive names reach this site and twelve of them get a relation…"). "Goes up by one in each place" should name that javadoc too.
- The Goal's `sessionPrincipal` example named `SessionIdentityService.principalOf`, which does not exist. It was corrected in this commit to the real method, `sessionPrincipal`. (`principalOf` is on `TestServiceStub`.)
