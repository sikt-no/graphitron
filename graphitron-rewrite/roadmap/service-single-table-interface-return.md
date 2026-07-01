---
id: R405
title: "Support single-table discriminated interface as a @service polymorphic return"
status: In Review
bucket: feature
priority: 5
theme: interface-union
depends-on: []
created: 2026-07-01
last-updated: 2026-07-01
---

# Support single-table discriminated interface as a @service polymorphic return

## Problem

A `@service` field returning a single-table discriminated interface
(`@table @discriminate(on:)`, with implementers pinned by
`@discriminator(value:)`, all sharing one jOOQ table) is rejected at
classify time by `FieldBuilder.deferServiceTableInterface`
(`FieldBuilder.java:3578`). The deferral fires from the
`ServiceDirectiveResolver.Resolved.TableBound` arm of both the query
classifier (`classifyQueryField`, `FieldBuilder.java:3602`) and the
mutation classifier (`classifyMutationField`, `FieldBuilder.java:3781`),
because a `TableInterfaceType` is a `TableBackedType` and so resolves to
the `TableBound` arm (not the `Polymorphic` arm route (a) uses). The
rejection currently carries an **empty backlog-item slot**
(`Rejection.deferred(..., "")`); closing this item fills that slug in and
then removes the deferral entirely.

Multi-table service polymorphic returns already work (R365 "route (a)":
`MutationServicePolymorphicField` / `QueryServicePolymorphicField`,
`MultiTablePolymorphicEmitter.emitServiceMethods`). That route dispatches
each returned record on its **runtime Java class** to pick `__typename`,
then auto-fetches the selected columns by PK. For a single-table
interface every returned record is the *same* jOOQ table record (all
subtypes share one `@table`, e.g. `ContentRecord`), so runtime-class
dispatch cannot tell the subtypes apart and would misreport `__typename`.
That is the precise reason the single-table variant was deferred rather
than folded into route (a); the guard is not an oversight to lift blindly
but a real dispatch-mechanism mismatch, and the fix is to use the
*read-side* discrimination mechanism instead.

## How discrimination works (reused, not reinvented)

`@discriminate(on: "CONTENT_TYPE")` names a real column on the shared
`@table`; each implementer's `@discriminator(value: "FILM")` pins that
implementer to a literal value of that column. The read-side fetcher
(`TypeFetcherGenerator.buildInterfaceFieldsList`,
`TypeFetcherGenerator.java:1008`) projects that column first and
unconditionally, aliased to the synthetic `__discriminator__`
(`MultiTablePolymorphicEmitter.DISCRIMINATOR_COLUMN`), qualified off the
FROM table's own jOOQ instance so the routing read stays unambiguous even
when the interface also exposes the discriminator as a queryable field or
a joined participant table re-declares the column. One `TypeResolver` per
`TableInterfaceType` is generated **unconditionally** for every
`TableInterfaceType` in the schema
(`GraphitronSchemaClassGenerator.java:126-158`): it reads
`record.get(DSL.field(DSL.name("__discriminator__")), String.class)` and
`switch`es the value to `env.getSchema().getObjectType(<implementer>)`.
The `Content` interface used as this item's fixture is already in the
schema (read-side `Query.allContent`), so its `TypeResolver` already
exists; **no new resolver is generated**.

The row that reaches the resolver only has to carry `__discriminator__`;
its Java type is irrelevant. That is exactly why the read side handles
single-table fine and route (a) does not: route (a) routes off the
record's runtime *class*, identical across all single-table subtypes.

## Design

The service already hands back rows of the one shared table,
PK-populated, so class-dispatch is unnecessary; feed the read-side
single-table SELECT from the service's PKs instead. The only genuinely
new emit code is "call the service, collect PKs, feed the SELECT, re-map
to input positions"; the discriminator projection, cross-table joins, and
the `TypeResolver` are all reused.

### Model (new field variants)

Model the new variants on the **read-side single-table**
`QueryField.QueryTableInterfaceField` / `ChildField.TableInterfaceField`
(`ChildField.java:589`), **not** on route (a)'s
`*ServicePolymorphicField`. The distinction matters: route (a)'s variant
carries a `PolymorphicReturnType` + a bare `List<ParticipantRef>` whose
participants are *distinct-table* (each participant's own record class is
the discriminator); the single-table variant carries a
`TableBoundReturnType` over the one shared table plus the discriminator
column and values, and its participants are *same-table* `TableBound`
with non-null `discriminatorValue`. Copying route (a)'s shape would widen
the `returnType` component to a union of two return-type shapes and push a
single-vs-multi-table fork into the emitter body; carrying the
`TableInterfaceType` component set instead keeps the fork on variant
identity and makes the read-side-helper reuse fall out for free (those
helpers already take exactly `(participants, discriminatorColumn, tableLocal)`).

Add two leaf variants, siblings of route (a)'s polymorphic variants but
carrying the read-side single-table discrimination data instead of a bare
participant list:

- `QueryField.QueryServiceTableInterfaceField` — implements
  `QueryField, ServiceField, WithErrorChannel`.
- `MutationField.MutationServiceTableInterfaceField` — implements
  `MutationField, ServiceField`.

Each carries the same fields the read-side `QueryTableInterfaceField`
carries, plus the service binding:

```
parentTypeName, name, location,
ReturnTypeRef.TableBoundReturnType returnType,   // the shared @table
String discriminatorColumn,
List<String> knownDiscriminatorValues,
List<ParticipantRef> participants,
ServiceMethodCall serviceMethodCall,
Optional<ErrorChannel> errorChannel
```

`domainReturnType()` returns `DomainReturnType.Plain(OBJECT_CLASS)` (as
route (a)'s variants do — the payload is a raw `Record`/`List<Record>`).
Add both to their sealed `permits` clauses and to the `operation()` /
`target()` switches in `QueryField` / `MutationField`, mapping to
`OutputField.serviceCall(serviceMethodCall())` exactly as the existing
`*ServicePolymorphicField` arms do.

A distinct variant (rather than overloading `*ServicePolymorphicField`)
is right: the emit is one by-PK SELECT with discriminator-value routing,
structurally unlike route (a)'s stage-1/stage-2 dispatch, and the two
carry different data (discriminator column + values vs a bare participant
list). Overloading would force a runtime fork inside the emitter on a
predicate over model shape, which the "lift the fork into the model"
principle rejects.

### Classify

In the `TableBound` arm of both root classifiers, replace the
`deferServiceTableInterface` call with construction of the new variant
when the verdict is a `TableInterfaceType`:

```java
case ServiceDirectiveResolver.Resolved.TableBound tb -> {
    var verdict = typeBuilder.lookAheadVerdict(tb.returnType().returnTypeName());
    if (verdict instanceof TableInterfaceType tit) {
        yield buildServiceField(tb.returnType(), tb.method(), parentTypeName, name, location, fieldDef,
            (ch, smc) -> new QueryField.QueryServiceTableInterfaceField(
                parentTypeName, name, location, tb.returnType(),
                tit.discriminatorColumn(), knownDiscriminatorValues(tit), tit.participants(), smc, ch));
    }
    yield buildServiceField(... QueryServiceTableField ...);   // unchanged
}
```

Notes:

- Participants and discriminator data come straight off the
  `TableInterfaceType` verdict (`tit.participants()`,
  `tit.discriminatorColumn()`, and the existing
  `knownDiscriminatorValues(tit)` helper at `FieldBuilder.java:6222`) —
  the same source the read-side `QueryTableInterfaceField` uses at
  `FieldBuilder.java:3722-3726`. `polymorphicParticipants(...)` is *not*
  reused here: it only understands `InterfaceType`/`UnionType` and
  returns `List.of()` for a `TableInterfaceType`.
- Route through the existing `buildServiceField(...)` helper so the
  `ServiceMethodCall` projection and error-channel resolution match every
  other service field. `checkServiceReturnMatchesPayload` is *currently* a
  no-op for a `TableBoundReturnType` (it only constrains `ResultReturnType`,
  `FieldBuilder.java:3021`), exactly as it is for route (a)'s
  `PolymorphicReturnType`, so no return-type mismatch is raised and no
  relaxation is needed. This is a producer-relaxation the new consumer
  leans on, not a stable invariant: if that check later grows a
  `TableBoundReturnType` arm, the single-table service path inherits it.
  The single-table path's actual requirement of the service return is
  weaker than a strict class match (records need only carry PKs, see
  Contracts below), so this reliance is safe day one; a future strict
  check would only tighten it in a compatible direction.
- `deferServiceTableInterface` (`FieldBuilder.java:3578`) is deleted; it
  has no other callers. This removes the last reference to the empty
  backlog slug, so nothing dangles.
- The same-table-participant `AUTHOR_ERROR` guard that route (a) relies on
  (`validateMultiTableParticipants`) is *not* reached for this path:
  single-table participants resolve through the `TableBound` arm, never
  the `Polymorphic` arm, and single-table is precisely the shape that
  guard steers authors toward. No new guard is needed, and the existing
  one is untouched.

### Emit

One SELECT, not route (a)'s record-class dispatch + per-typename UNION.
The generated fetcher:

1. Calls the service (reuse `ServiceMethodCallEmitter.emit`; declare a
   `dsl` local when the service call did not, as route (a) does at
   `MultiTablePolymorphicEmitter.java:210-212`).
2. Normalises the service return into `List<Record>` in input order
   (the snippet route (a) inlines at
   `MultiTablePolymorphicEmitter.java:214-231`).
3. Collects the shared table's PK values off those records in order.
4. Runs **one** SELECT: `dsl.select(fields).from(<sharedTable>)` with the
   read-side helpers `buildInterfaceFieldsList` + `buildCrossTableAliasDeclarations`
   + `buildCrossTableJoinChain` (projecting `__discriminator__`, the
   unified participant field set, and discriminator-gated cross-table
   `LEFT JOIN`s for subtype-specific fields), `WHERE <pk> IN (:servicePks)`,
   `AND <discriminator> IN (:knownValues)` (reuse `buildDiscriminatorFilter`
   so a shared-table row whose discriminator matches no participant drops
   the same way route (a) drops an unmatched record).
5. Re-maps result rows back to input positions by PK: build a
   `Map<pk, Record>` from the fetch, then walk the service records in
   order, placing each matched row into the result at its input position.

The generated row carries `__discriminator__`; the existing
`TableInterfaceType` `TypeResolver` sets `__typename` per row. No new
resolver, no per-typename helper, no UNION.

**Emit location (seam at the by-PK re-projection, not the whole fetcher).**
The fetcher has two concerns that belong in different homes, and the
cleanest seam splits them rather than moving one wholesale into the
other's class:

- The **read/projection concern** (given a `tableLocal` and a
  `Condition`, project `__discriminator__` + the participant field set +
  discriminator-gated cross-table `LEFT JOIN`s, and fetch) is genuinely
  read-side and already lives in `TypeFetcherGenerator`'s `private static`
  helpers, called by both `buildQueryTableInterfaceFieldFetcher` and
  `buildTableInterfaceFieldFetcher`. Extract the shared body of those two
  (the fields-list + alias + join-chain + `select(...).from(tableLocal).where(condition)`
  assembly) into a **package-private re-projection helper** that takes a
  `tableLocal` and a caller-supplied `Condition` and knows nothing about
  services. The two existing read fetchers keep calling it with their
  FK/discriminator condition; the new service fetcher calls it with a
  PK-IN condition. No service plumbing enters `TypeFetcherGenerator`.
- The **service concern** (emit the developer-method call, normalise the
  return to `List<Record>`, collect PKs, re-map fetched rows to input
  positions) belongs with route (a) in `MultiTablePolymorphicEmitter`,
  which already owns `ServiceMethodCallEmitter` invocation and the
  normalise machinery. The new service fetcher lives here, builds the
  PK-IN `Condition`, and calls the shared re-projection helper.

This keeps the read-side generator free of a developer-method-call prefix
(the rejected Option A) and avoids widening the whole read-side helper
surface to another class (the rejected Option B): the *service* concern
stays in the polymorphic emitter, the *projection* concern stays shared,
and neither class grows a foreign responsibility.

Additionally **extract the service-call-and-normalise snippet** currently
inlined in `MultiTablePolymorphicEmitter.buildServiceMainFetcher`
(lines 214-231, including the `-Werror` redundant-downcast dance at 227)
into a small helper that both route (a)'s main fetcher and this new
fetcher call, so the "call the service, flatten to `List<Record>` in
input order" logic exists in exactly one place rather than being copied.

Wire both new variants into `TypeFetcherGenerator.IMPLEMENTED_LEAVES`
(`TypeFetcherGenerator.java:187`; add — phrased as "R405 adds
`QueryServiceTableInterfaceField` / `MutationServiceTableInterfaceField`
to `IMPLEMENTED_LEAVES`", the partition `GeneratorCoverageTest` checks)
and the emit dispatch `switch` (`TypeFetcherGenerator.java:460`/`499` for
the route (a) arms; add the two new arms next to them, dispatching to the
new service fetcher).

### PK-IN condition (single + composite)

The read-side `buildJoinPathCondition` builds a parent-FK equality, not a
PK-IN. Add a small condition builder that emits
`DSL.row(<pkCols>).in(<rows>)` (jOOQ row-value IN) so single-column and
composite PKs are covered uniformly, matching how route (a) already
collects PKs as `Object[]` per record. For the `content` fixture the PK
is single-column (`content_id`), but row-value IN keeps the emit
composite-safe day one rather than filing a follow-up.

### Contracts (spec-stage questions resolved)

- **Discriminator population on service records.** The service records
  need only carry PKs; the by-PK re-fetch projects `__discriminator__`
  from the live row, so the service is *not* required to populate the
  discriminator column on the records it returns. This matches the read
  side and keeps the service contract identical to route (a)'s
  (PK-populated records, nothing more).
- **Drop / null contract.** Aligned with route (a): a service-returned PK
  that matches no live row (or whose discriminator is not a known
  participant value, filtered out in step 4) drops from a list return
  (the surviving payload is simply shorter) and yields `null` for a
  single return (surfacing as a graphql-java non-null violation if the
  field is non-null). Re-mapping by input position preserves order among
  the survivors. This is the same contract documented on
  `buildServiceMainFetcher`; the two service paths share one drop
  semantics. This deliberately **supersedes** this item's original
  Backlog wording ("a PK matching no live row leaves that position
  null"): a positional-null list would put `null` holes into a non-null
  list element type, surfacing as per-index non-null violations the client
  cannot correlate to input. The by-PK re-map is an internal round-trip
  detail and must not leak into the output cardinality.
- **`@asConnection`.** Out of MVP. Route (a)'s service path also emits
  `isConnection = false` (`buildPerTypenameSelect(..., false, ...)`); a
  `@service` single-table interface return under `@asConnection` is
  rejected/deferred here rather than half-supported. A follow-up can lift
  it once route (a) grows service-path connections.

### Validation (mirror the classifier's new acceptance)

Removing the deferral means the classifier now *accepts* a shape it used
to reject, so the validator must gain a mirroring floor for the new
variants (the "validator mirrors classifier invariants" principle;
`GraphitronSchemaValidator.java:622` is route (a)'s analogue). The mirror
points at the **single-table** checks, not the multi-table ones:

- Add `validateQueryServiceTableInterfaceField` / mutation twin, modelled
  on `validateQueryTableInterfaceField` (`GraphitronSchemaValidator.java:606`),
  which runs `validateCardinality` only. It does **not** call
  `validateMultiTableParticipants` (that check enforces distinct-table PK
  presence + uniform arity + the same-table *rejection* floor route (a)
  needs; single-table is precisely the shape that floor steers authors
  toward, so applying it here would reject the valid case). Register both
  in the validator's per-variant dispatch.
- The single-table invariants the emitter assumes (single-hop FK for each
  cross-table participant field, a PK-bearing shared table, resolvable
  discriminator column) are already enforced **upstream** when the
  `TableInterfaceType` and its `ParticipantRef.TableBound` participants are
  built in `TypeBuilder` (`buildParticipantList` / `extractCrossTableFields`,
  which reject a non-single-hop or unresolvable `@reference`), and the new
  variant reuses `tit.participants()` verbatim. So those invariants are
  shared with the read-side path rather than re-mirrored here; the spec
  names this explicitly so the reviewer sees the floor is inherited, not
  dropped.

## Scope

- Covers the `@service` path only, both query and mutation surfaces (they
  share `deferServiceTableInterface`; the emitter work is identical, so
  splitting query from mutation would be artificial).
- **Root fields only**, as route (a): child (non-root) `@service`
  polymorphic returns stay deferred through their existing separate path.
- DML `@mutation` single-table interface returns are a separate concern
  (input-side subtype selection); tracked by R406, which reuses this
  item's read-side dispatch mechanism for its return half.
- Union returns on the `@service` path stay permanently unsupported
  (`rejectServiceUnionReturn`, `FieldBuilder.java:3564`), unchanged by
  this item.

## Tests

Behaviour is pinned at the pipeline and execution tiers; code-string
assertions on generated bodies are banned (design principles).

### Unit / classification (`GraphitronSchemaBuilderTest`)

- Flip `serviceReturningTableInterface_deferred`
  (`GraphitronSchemaBuilderTest.java:1928`) from asserting `DEFERRED` to
  asserting the new variant: a `@service` query field returning a
  single-table discriminated interface classifies to
  `QueryField.QueryServiceTableInterfaceField` carrying the service method,
  the participant set, the discriminator column, and the known
  discriminator values. Add a mutation twin asserting
  `MutationServiceTableInterfaceField` (list cardinality). Model on the
  existing `@ProjectionFor` `servicePolymorphicProjectionCarriesParticipantsAndMethod`
  test (`GraphitronSchemaBuilderTest.java:1872`).
- Keep `serviceReturningUnion_rejectedAsUnsupported` unchanged (union
  stays `AUTHOR_ERROR`), pinning that this item did not disturb the union
  guard.
- Add the dispatch-partition pin: the two new variants appear in
  `IMPLEMENTED_LEAVES`, so `GeneratorCoverageTest`'s leaf-coverage
  assertion stays green with no un-dispatched leaf.

### Unit / validation

Mirror the `validateQueryTableInterfaceField` test shape for the new
`validateQueryServiceTableInterfaceField` / mutation twin: a well-formed
single-table service interface field passes with no `ValidationError`, and
a list-cardinality violation (if the field variant admits one) surfaces
through `validateCardinality`. This pins that the deferral removal did not
leave the accepted shape without a validate-time floor.

### Pipeline tier

SDL → classified model → generated `TypeSpec`: a `@service` returning a
single-table discriminated interface (interface + at least two
`@discriminator` implementers, one with a cross-table `@reference` field)
produces the new field variant and a fetcher that projects
`__discriminator__` and a discriminator-gated cross-table `LEFT JOIN`,
with the by-PK WHERE source (shape assertions on the model + `TypeSpec`,
not on method-body strings).

### Execution tier (`graphitron-sakila-example`, real PostgreSQL)

Reuse the existing `Content` / `FilmContent` / `ShortContent` fixture
(`schema.graphqls:1751-1845`; shared `content` table, `CONTENT_TYPE`
discriminator, `FilmContent.rating` cross-table `@reference` to `film`,
`ShortContent.description` same-table-but-SHORT-only). Add:

- A new service (e.g. `ContentSearchService` under
  `graphitron-sakila-service`, mirroring `PolymorphicSearchService`)
  returning PK-only `ContentRecord`s: a single-cardinality method (one
  `ContentRecord` with `content_id` set → routes to whichever subtype the
  live row's `CONTENT_TYPE` selects) and a list method returning both a
  FILM-row PK and a SHORT-row PK so a misroute is observable.
- Two `@service` `Query` fields (single + list) plus one `@service`
  `Mutation` field wired to those methods, next to the existing
  `searchOneService` / `searchManyService` fixture
  (`schema.graphqls:399-402`).
- A `ServiceTableInterfaceReturnExecutionTest` modelled on
  `ServicePolymorphicReturnExecutionTest`: assert the list routes each
  row to `FilmContent` / `ShortContent` by `__typename` (off the live
  discriminator, not the record class — a route (a)-style class dispatch
  would misroute both to one type, so this is the observable proof),
  populates `FilmContent.rating` through the discriminator-gated
  cross-table join, leaves `ShortContent.description` on the shared-table
  FILM rows `null`, and honours the drop contract for a PK with no live
  row. The single-cardinality field pins one row routes correctly.

Full reactor green under `mvn -f graphitron-rewrite/pom.xml install -Plocal-db`
(the `-Plocal-db` catalog-jar footgun applies).

## Roadmap entries

On implementation: trim this file to any residual (none expected), flip
`status:` to `In Review`, regenerate the README. On approval: delete the
file and add a one-line `changelog.md` entry citing the landing SHA and
`R405` (this closes the last deferred shape on the `@service` polymorphic
surface and is worth keeping in the changelog). R406 declares the intent
to reuse this item's read-side dispatch for its return half; no
`depends-on` is added here (R405 does not depend on R406), but the
implementer should leave the extracted service-normalise helper and the
by-PK discriminator SELECT in a shape R406 can call into.

## Notes

- The two new variants are the single-table siblings of R365's
  `*ServicePolymorphicField`; they share the participant model
  (`ParticipantRef.TableBound`, here with **non-null** `discriminatorValue`
  and populated `crossTableFields`, whereas route (a)'s multi-table
  participants leave both empty/null) and the drop contract, and differ
  only in dispatch mechanism (discriminator value vs runtime class).
- Reuses `MultiTablePolymorphicEmitter.DISCRIMINATOR_COLUMN`,
  `buildInterfaceFieldsList`, `buildCrossTableAliasDeclarations`,
  `buildCrossTableJoinChain`, `buildDiscriminatorFilter`, and the
  per-`TableInterfaceType` `TypeResolver` verbatim; the joined-table
  (R389) helpers are not exercised (the fixture is single-table, not
  class-table), but the shared field-list builder tolerates their absence.
