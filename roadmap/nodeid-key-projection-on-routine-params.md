---
id: R668
title: "Decode @nodeId leaves bound to @routine parameters via argMapping key-column projection"
status: Backlog
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

The author's proposal is to make the key columns nameable as a path segment, so the binding
reads:

```
argMapping: "..., pOrganisasjonskode: input.organisasjonId.organisasjonskode, ..."
```

`organisasjonskode` is not a field of any SDL type. It is a *key column of the node type the
`@nodeId` names*, and the segment means "decode this node id and project that column out of the
decoded key tuple". Graphitron already holds everything the segment needs: `@nodeId(typeName:)`
settles the node type, `BuildContext.resolveTargetKeys` settles its key columns, and
`BuildContext.resolveDecodeHelperForType` settles the generated `decode<TypeName>` helper.

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

## Why this is not a `NodeIdLeafResolver` reuse

`NodeIdLeafResolver.resolve` answers a table-anchored question: given a containing table, is
this `@nodeId` the table's own identity (same-table) or a foreign key into another table
(FK-target), and which columns does the predicate bind against. A routine IN parameter has no
containing table and no predicate; it wants only the wire half of that resolver, "decode into
typed key values", with no projection against a table at all. So the reusable seam is the pair
`resolveDecodeHelperForType` + `resolveTargetKeys`, not `NodeIdLeafResolver`. Naming that up
front matters: routing this through the table-anchored resolver would force a fake containing
table into the call and re-derive an FK verdict nobody asked for.

## Design forks to settle at Spec

**Where the segment resolves.** `ArgBindingMap.of` is the shared path resolver for `@service`,
`@condition` and `@routine`; it is the site that currently rejects the segment, so it has to
learn about it, and whatever it learns applies to all three directives at once. Two shapes:
teach `of` to admit and resolve the segment for every site (widest blast radius, but the fact
"this path ends in a decoded node key" is directive-independent and belongs at the parse
boundary), or teach it to *carry* the segment unresolved and let each consumer decide. The
first fits "decide once, at the parse boundary" better; the second keeps `@service`'s behaviour
untouched. Recommendation is the first, with `@service` and `@condition` consuming a typed
deferral until someone implements their emit arms.

**How `PathExpr` represents it.** Today `PathExpr.Segment` is `(name, liftsList)` and every
segment means "read this map key". A node-key projection is a different operation on the same
chain, so it wants to be visible in the type rather than inferred by re-asking the schema at
emit time. A terminal `PathExpr` arm (or a `Segment` kind) carrying the resolved
`HelperRef.Decode` and the projected `ColumnRef` plus its index in the decode tuple is the
shape that lets `RoutineCallEmitter` emit without re-deriving anything.

**Whether the bare form stays legal.** The `TEXT`-parameter case above is the one that decides
this. Binding a `@nodeId` leaf with no key-column segment is almost certainly an author mistake,
and today it is an invisible one. The proposal here is to reject it and name the available key
columns in the message, converting the silent-wrong-value into a directed rejection. The
counter-argument is convenience for single-key node types, where the projection is unambiguous.
Settle explicitly; do not let it fall out of the implementation. Note that closing this is worth
doing even if the projection syntax itself is deferred: the rejection alone removes a class of
silently-wrong writes.

**Type gate.** The coercion check must compare the *projected column's* Java type against the
routine parameter's Java type, not the `ID` scalar's coercion output. That is a different input
to `ServiceCatalog.argExtraction` (or a bypass of it), and it is what turns the concrete case
above from a rejection into a binding.

**Emission.** `RoutineCallEmitter.argExpression` reads `ParamSource.Arg` bindings through
`typedSlotRead` / `nestedSlotRead`. The decode rides on top of whichever read reaches the raw
id: `decode<TypeName>(<raw>)` returns a `RecordN`, and the projection is `.valueN()` at the
column's index in `HelperRef.Decode.outputColumnShape`. A `null` return is
`CallSiteExtraction.NodeIdDecodeKeys.ThrowOnMismatch` semantics: the generated
`GraphitronClientException`, the same failure surface every other authored `@nodeId` decode
already has. Both call surfaces need it, the uncorrelated value overload and the correlated
`DSL.val(...)` `Field` overload.

**Composite keys.** A node type with a multi-column key makes the segment mandatory and makes
two parameters bindable from one node id (`p_a: input.x.col_a, p_b: input.x.col_b`). Whether
the decode helper is called once and shared or once per binding is an emit-side question; the
generated code should not decode the same id twice.

## Relationship to other items

* `roadmap/lsp-argmapping-routine-coordinate.md` gives `@routine(argMapping:)` completions and
  diagnostics at all. The author's "we should even get completion on this" is a *further* step:
  that item explicitly leaves dot-path expansion unmodelled ("offer nothing rather than a
  misleading flat list") because the LSP snapshot carries no nested-input-field projection. Key
  columns of a `@nodeId` leaf are a smaller and better-defined completion source than general
  nested input fields, so they may be reachable ahead of the general case, but it is separate
  work and belongs in its own item rather than riding this one.
* `roadmap/routine-coercing-arg-extractions.md` (R625) makes the routine emitter honour
  non-`Direct` `CallSiteExtraction` arms (`EnumValueOf`, `JooqConvert`). A node-key projection
  is a third such arm on the same emitter seam. If R625 lands first, this item plugs into a
  seam that already exists; if not, this item builds the first one. Neither ordering blocks the
  other, but sizing should account for which is true at pickup.
* `roadmap/nested-argmapping-syntax.md` (R249) extends `argMapping`'s right-hand side with a
  nested object form. It varies the same grammar from the other end, so the two should agree on
  where `argMapping` parsing lives before either lands a grammar change.

## Tests

`RoutineMutationWritePipelineTest` carries the existing `rent_film` fixtures, including the
nested `input.inventoryId` / `input.customerId` form, and is the natural home: a sakila-shaped
variant binds `pInventoryId` from an `ID! @nodeId(typeName: "Inventory")` input field through
the projected key column. The execution tier (`graphitron-sakila-example`) is where the decoded
value is proved to reach the database as the key rather than the base64 string; a pipeline
assertion on emitted source alone would not catch a transposed composite-key projection.
