---
id: R668
title: "Decode @nodeId leaves bound to @routine parameters via argMapping key-column projection"
status: Spec
bucket: feature
priority: 3
theme: routine
depends-on: []
created: 2026-08-14
last-updated: 2026-08-14
---

# Decode @nodeId leaves bound to @routine parameters via argMapping key-column projection

A `@nodeId` field carries a base64-encoded node identity on the wire, not the primary key it
encodes. Graphitron already knows how to turn that wire form back into typed key values: the
decode side ships for lookups and filters, where an argument or input field annotated
`@nodeId(typeName: T)` is decoded into `T`'s key columns and fed to an `IN` / `VALUES` join.
That decode is not wired into the `@routine` parameter binding. `argMapping` hands a routine
IN parameter the *raw* value at the path it names, so a `@nodeId`-carrying input field delivers
the base64 string.

The concrete case, an access-control mutation whose routine takes the organisation's integer
key:

```graphql
input OpprettFeideApplikasjonInput {
  navn: String!
  organisasjonId: ID! @nodeId(typeName: "Organisasjon")   # encodes organisasjon.organisasjonskode (INTEGER)
  serviceId: String!
  beskrivelse: String
}

type Mutation {
  opprettFeideApplikasjon(input: OpprettFeideApplikasjonInput!): OpprettFeideApplikasjonPayload
    @routine(
      name:       "opprett_feide_applikasjon"   # (p_navn TEXT, p_organisasjonskode INTEGER, p_service_id TEXT, p_beskrivelse TEXT)
      argMapping: "pNavn: input.navn, pOrganisasjonskode: input.organisasjonId, pServiceId: input.serviceId, pBeskrivelse: input.beskrivelse"
    )
}
```

This item makes the node type's key columns nameable as a trailing path segment, so the binding
reads:

```
argMapping: "..., pOrganisasjonskode: input.organisasjonId.organisasjonskode, ..."
```

`organisasjonskode` is not a field of any SDL type. It is a *key column of the node type the
`@nodeId` names*, and the segment means "decode this node id and project that column out of the
decoded key tuple".

## What happens today

Three outcomes, measured against the sakila test catalog by classifying a `@nodeId` input field
into `rent_film(p_inventory_id INTEGER, ...)` and into `create_secure_note(p_owner TEXT, ...)`.
None of them is the one the author wants, and the worst of them is silent.

* **`ID` into an `INTEGER` parameter: a rejection that never says `@nodeId`.**
  `pInventoryId: input.inventoryId` resolves the leaf to the `ID` scalar.
  `RoutineDirectiveResolver.leafTypeGate` runs the shared coercion gate
  (`ServiceCatalog.argExtraction` → `WireCoercionResolver.checkScalar`), which compares `ID`'s
  graphql-java coercion output against the parameter's Java type and rejects with
  `Assignability[sdlLeafType=ID!, coercionOutputType=java.lang.String,
  declaredType=java.lang.Integer, site=@routine parameter 'pInventoryId']`. The message reads as
  a type mistake, not as a missing decode.
* **`ID` into a `TEXT` parameter: no rejection at all, and the wrong value ships.** The same
  binding against `create_secure_note`'s `p_owner TEXT` classifies clean, as
  `ArgBinding[routineParamName=pOwner, paramType=java.lang.String, source=Arg[extraction=Direct[],
  path=…owner]]`. The base64 node id goes to the database verbatim. This is the sharper half of
  the problem: silently wrong data rather than a build failure.
* **The proposed segment is rejected by the shared path resolver.**
  `pInventoryId: input.inventoryId.inventory_id` never reaches the routine resolver;
  `ArgBindingMap.of` rejects it first with `@routine argMapping entry 'pInventoryId:
  input.inventoryId.inventory_id' walks through scalar 'ID' at segment 'inventoryId'; only
  input-object types may be traversed`. The traversal rule is right for every path segment that
  exists today; the proposed segment is a new kind of segment it has no concept of.

## Design

### The segment names an SQL key column, and `typeName:` must be explicit

The trailing segment names a key column of the node type **by SQL name**, matching
`columnMapping`'s right-hand side, which already names columns by SQL name at the same
directive. `@node(keyColumns:)` is likewise an SQL-name list, so an author who pinned the key
columns writes the same spelling in both places. Matching is case-insensitive, the way every
other SQL-name comparison in the classifier is.

`@nodeId` without `typeName:` is rejected at this position. `NodeIdLeafResolver.inferTypeName`
infers a bare `@nodeId`'s target from the *containing table*, and a routine parameter has no
containing table; there is nothing to infer from. The rejection says so and tells the author to
add `typeName:`.

### It is not a `NodeIdLeafResolver` reuse

`NodeIdLeafResolver.resolve` answers a table-anchored question: given a containing table, is this
`@nodeId` the table's own identity (same-table) or a foreign key into another table (FK-target),
and which columns does the predicate bind against. A routine IN parameter has no containing table
and no predicate. It wants only the wire half, "decode into typed key values", with no projection
against a table at all.

The seam is narrower than either that resolver or the `resolveDecodeHelperForType` /
`resolveTargetKeys` pair the Backlog draft named: `NodeIndex.forName(typeName)` returns a
`NodeType` that already carries both `nodeKeyColumns` and `decodeMethod`, fully reconciled by
`TypeBuilder`. Everything the segment needs is one index lookup. Routing through the
table-anchored resolver would force a fake containing table into the call and re-derive an FK
verdict nobody asked for.

### The path stays SDL-only; the decode rides the extraction slot

The projection splits cleanly in two, and the split is what keeps both halves honest:

* **`PathExpr` keeps meaning "walk SDL input fields".** The resolved path stops at the `@nodeId`
  field. `RoutineCallEmitter.nestedSlotRead` and `ArgPathHelperRegistry` are untouched: the
  descent helper still walks a `Map` chain to a leaf and casts, and for a node binding that leaf
  is the `String` id.
* **The decode-and-project becomes a `CallSiteExtraction` arm** on `ParamSource.Arg.extraction()`.
  That slot is exactly "how to extract one argument value at the call site", it already carries
  `NodeIdDecodeKeys.ThrowOnMismatch(decodeMethod)`, and the only fact missing for a routine
  parameter is *which* key column to project. So the new arm is the existing decode plus a key
  index.

This is preferable to a terminal `PathExpr` arm, which was the Backlog draft's guess. A terminal
arm would put a decode concern inside a type whose whole contract is SDL traversal, and would
leave `extraction()` still unread on this binding.

That last point is the item's other payoff. `RoutineDirectiveResolver` hardcodes
`new CallSiteExtraction.Direct()` on every argument-sourced binding and `RoutineCallEmitter`
never reads `arg.extraction()` at all: a model fact with no consumer, which
`roadmap/routine-coercing-arg-extractions.md` (R625) is chartered to fix. This item makes the
emitter read the slot for the first time. See "Relationship to other items" for the ordering.

### The bare form becomes a rejection

Once the segment exists, binding a `@nodeId` leaf with *no* key-column segment is rejected,
naming the node type and listing its key columns. This is the change that closes the silent
`TEXT`-parameter hole, and it is worth landing even if everything else here slipped: today that
spelling writes a base64 string into a database column and nothing in the build says a word.

The counter-proposal, implicit decode for single-key node types, is rejected. It would make the
same spelling mean two different things depending on a fact (the node's key arity) that is not
visible at the `argMapping` site, and it would leave composite-key node types needing the
explicit segment anyway. One spelling, always explicit.

This is a breaking change for any schema relying on the silent pass-through. It is a rejection
of a spelling that produces wrong data, so it is a bug fix rather than a capability removal, and
the rejection message names the fix. Call it out in the changelog entry at Done.

### Type gate

`RoutineDirectiveResolver.leafTypeGate` feeds `ServiceCatalog.resolvePathLeafType`'s answer (the
`ID` scalar) into `ServiceCatalog.argExtraction`. For a projected binding the comparison is
against the **projected column's** Java type (`ColumnRef.columnClass()`) and the routine
parameter's Java type, boxed on both sides. A mismatch is a structural rejection naming both
types, the column, and the node type, so an author who projects the wrong column of a composite
key is told exactly that.

The `ID`-scalar coercion check does not run on a projected binding: the wire type is no longer
what reaches the parameter. Keep the two paths visibly separate in `leafTypeGate` rather than
threading a substituted leaf type through `argExtraction`, so a reader can see which check
governs which binding.

### Emission

`CompositeDecodeHelperRegistry` already emits precisely the required helper.
`register(HelperRef.Decode, Mode.THROW, list)` produces a private static method returning the
single key column's type at arity 1 and a typed `RowN` above, raising the generated
`GraphitronClientException` on a null decode with the two-branch malformed-versus-wrong-type
message. So:

* arity 1: `decode<Type>(<raw read>)`
* arity N: `decode<Type>(<raw read>).value<i>()`, `i` being the projected column's 1-based
  position in `HelperRef.Decode.outputColumnShape`

The `list` axis is `false` here: a routine IN parameter takes one value, which the existing
`leafTypeGate` cardinality check already enforces.

Two bindings projecting different columns of the same node id share one registered helper (the
registry keys on encoder class, method, mode and list-ness), so a composite key is decoded once
per call site and projected twice, not decoded twice.

Four `RoutineCallEmitter.emitCall` sites need a decode registry in scope: `RootLauncherRenderer`,
`PathFragments.emitTableExpression`, and two in `TypeFetcherGenerator`. Three of the four already
thread `ctx.argPathHelpers()`, a generation-context-level `ArgPathHelperRegistry` drained onto the
class builder in `TypeFetcherGenerator`; adding a sibling `ctx.compositeDecodeHelpers()` accessor
drained the same way is the symmetric move and keeps the threading identical at every site.

Both call surfaces carry it: the uncorrelated value overload takes the projected value directly,
and the correlated `Field` overload wraps it in the existing `DSL.val(...)`.

## Implementation

* `NodeKeyProjection` (new, `rewrite/model`): the resolved segment. Carries the node type name,
  the `HelperRef.Decode`, the projected `ColumnRef`, and its index in the decode tuple. Small
  enough to be a record; every consumer reads it and nothing re-derives.
* `CallSiteExtraction`: new arm `NodeIdKeyProjection(HelperRef.Decode decodeMethod, int keyIndex,
  ColumnRef column)` in the sealed permits list. Every exhaustive switch over
  `CallSiteExtraction` becomes a compile error until it names the arm, which is the point;
  arms that cannot reach it document that rather than falling through.
* `ArgBindingMap`: `of` gains a `NodeIndex` parameter and, when a path segment would otherwise be
  rejected for walking through a scalar, checks whether that scalar leaf carries
  `@nodeId(typeName:)` and whether the segment names one of the node's key columns. The value
  type `Map<String, PathExpr> byJavaName` becomes `Map<String, BoundPath>` where `BoundPath`
  pairs the `PathExpr` with an optional `NodeKeyProjection`. Add a two-argument `of` overload
  delegating with `NodeIndex.EMPTY` for the schema-free call site
  (`BuildContext`'s segment-chain resolution, which passes an empty slot map) and the unit tests.
  Three near-miss rejections, each listing candidates: segment on a non-`@nodeId` scalar (today's
  message, unchanged), `@nodeId` without `typeName:`, and a segment that is not a key column of
  the resolved node type.
* `RoutineDirectiveResolver`: read the projection off the binding, run the projected-column type
  gate, and mint `ParamSource.Arg(new CallSiteExtraction.NodeIdKeyProjection(...), path)` instead
  of `Direct`. Add the bare-`@nodeId`-leaf rejection.
* `ServiceDirectiveResolver` / `ConditionResolver` (both sites): a projected binding is a typed
  deferral naming the follow-up. They resolve the segment through the same shared `of` but cannot
  emit it; the deferral is what keeps the shared resolver honest rather than making it
  routine-only.
* `RoutineCallEmitter`: `argExpression` switches on `b.source()`'s extraction for the first time.
  `emitCall` gains the decode-registry parameter; the four call sites thread it.
* `GenerationContext`: `compositeDecodeHelpers()` accessor plus the drain in
  `TypeFetcherGenerator`, mirroring `argPathHelpers()`.

## Tests

* `ArgBindingMapTest` (unit): segment resolution and the three near-miss rejections, driven
  through a stub `NodeIndex`. This is where the grammar is pinned; the tier is cheap and the
  rules are pure.
* `RoutineMutationWritePipelineTest` (pipeline): a sakila-shaped variant of the existing nested
  `rent_film` fixture binding `pInventoryId` from `ID! @nodeId(typeName: "Inventory")` through
  the projected key column. Assert the classified `ArgBinding` carries
  `NodeIdKeyProjection`, and assert the emitted call site registers and invokes the decode helper
  exactly once. Plus the two rejection cases: the bare `@nodeId` leaf, and a segment naming a
  non-key column.
* Composite-key coverage belongs on the `nodeidfixture` catalog (`bar` is the composite-key node
  type `NodeIdPipelineTest` already uses), pinning the `.value<i>()` index and the
  decode-once-project-twice sharing. A transposed projection is exactly the failure a
  single-column fixture cannot catch.
* Execution tier (`graphitron-sakila-example`): one round trip proving the decoded key reaches
  the database, alongside the existing `NodeIdValueAgreementExecutionTest`. A pipeline assertion
  on emitted source cannot prove the projection is not transposed; only a real row can.

## User documentation (first-client check)

The user surface is a new spelling on an existing directive argument, so the docs change is
small and lands in three places:

* `docs/manual/reference/directives/service.adoc#arg-mapping` is the shared home of the
  right-hand-side path form, cross-referenced by `@service`, `@condition`, `@routine` and
  `@tableMethod`. The rule list there gains one bullet: a segment following a `@nodeId` leaf
  names one of the node type's key columns by SQL name, and decodes the id rather than walking
  an input field. Note in the same bullet that only `@routine` emits it today.
* `docs/manual/reference/directives/routine.adoc`: a short subsection after the existing
  wrapper-input example, showing the `@nodeId` input field and the projected binding. The
  Constraints list gains the bare-form rejection and the explicit-`typeName:` requirement.
* `docs/manual/reference/directives/nodeId.adoc`: a cross-reference from the decode side, so an
  author reading about `@nodeId` finds the routine binding without going through `@routine`.

Draft of the `routine.adoc` subsection:

> **Binding a routine parameter from a node id**
>
> When the input field carries `@nodeId`, its wire value is an opaque base64 id, not the key it
> encodes. Name the key column after the field to bind the decoded key instead:
>
> ```graphql
> input RentFilmInput {
>     inventoryId: ID! @nodeId(typeName: "Inventory")
>     customerId:  Int!
> }
>
> type Mutation {
>     rentFilm(input: RentFilmInput!): RentFilmPayload
>         @routine(
>             name:       "rent_film"
>             argMapping: "pInventoryId: input.inventoryId.inventory_id, pCustomerId: input.customerId"
>         )
> }
> ```
>
> `inventory_id` is a key column of the `Inventory` node type, named by its SQL name the same way
> `columnMapping` names columns. A node type with a composite key exposes each column, so two
> parameters can be bound from one id. The id is decoded once per call.
>
> A malformed id, or a well-formed id of the wrong type, fails the field with a client error;
> it is never passed through. Binding a `@nodeId` field without naming a key column is a build
> error listing the columns available, and `@nodeId` at this position requires an explicit
> `typeName:` because there is no containing table to infer the node type from.

## Relationship to other items

* `roadmap/routine-coercing-arg-extractions.md` (R625) makes the routine emitter honour
  non-`Direct` extraction arms (`EnumValueOf`, `JooqConvert`). This item builds the same seam for
  a third arm. Whichever lands first pays for `RoutineCallEmitter.argExpression` switching on
  `extraction()` and the other plugs into it. Neither blocks the other; the implementer should
  check which is true at pickup and size accordingly. If R625 is already in flight, coordinate on
  the switch rather than both writing it.
* `roadmap/lsp-argmapping-routine-coordinate.md` (R626) gives `@routine(argMapping:)` completions
  and diagnostics at all. Completing key columns after a `@nodeId` leaf is a *further* step: R626
  explicitly leaves dot-path expansion unmodelled ("offer nothing rather than a misleading flat
  list") because the LSP snapshot carries no nested-input-field projection. Key columns are a
  smaller and better-defined completion source than general nested input fields, so they may be
  reachable ahead of the general case, but that is its own item and must not ride this one.
* `roadmap/nested-argmapping-syntax.md` (R249) extends the right-hand side with a nested object
  form. It varies the same grammar from the other end. Neither item's grammar change should land
  without the other's author confirming the two compose; the shared owner is
  `ArgBindingMap.parseArgMapping` plus `ArgBindingMap.of`.

## Open questions

* Whether the `@service` and `@condition` deferrals should instead be rejections. A deferral
  promises the shape is coming; nobody has asked for it on those directives, and `@service`
  already has a richer answer available (an input bean can carry a decoded record through
  `CallSiteExtraction.NodeIdDecodeRecord`). Settle before implementing the two arms.
* Whether a `@nodeId` input field that nothing consumes should warn. Today it is silently
  ignored wherever no consumer reads it, which is how the `TEXT` case above stays invisible;
  the bare-form rejection closes it at the `@routine` site only. A general "declared and
  unconsumed" warning is a larger question and belongs in its own item if anyone wants it.
