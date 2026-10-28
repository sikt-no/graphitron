---
id: R1119
title: "Signed reference handoff: non-table entities across subgraphs"
status: Spec
bucket: feature
priority: 3
theme: service
depends-on: []
created: 2026-10-28
last-updated: 2026-10-28
---

# Signed reference handoff: non-table entities across subgraphs

## Goal

A Graphitron subgraph can take part in the signed-reference handshake described in the design page
"Tilgangsstyrt deling av vgs-resultater i supergrafen": subgraph A decides access and hands subgraph B a
signed, short-lived reference as an `@inaccessible` federation key; B verifies the reference before it
reads anything, and a rejected reference makes the field `null` in the router's answer. Today
Graphitron can only express half of that. A can declare a reference-only stub (`@key(resolvable: false)`
on a type with no `@table`), but B cannot own the type: a resolvable `@key` must resolve to a jOOQ
table row, so there is nowhere to run the verification, and a custom `fetchEntities` may only return
jOOQ `Record`s. This item lets a resolvable entity be resolved by hand-written Java that is handed the
representations and the request context, and returns the entity (or `null`). Graphitron stays out of
the cryptography: signing and verifying (Vault Transit in the design page) remain consumer code.

The target SDL is the design page's, unchanged in B except for the resolver binding:

```graphql
# Subgraph A: producer, reference only
type Soknad @table(name: "soknad") @key(fields: "id") {
  id: ID! @nodeId
  vgsResultater: VgsResultatHandtak
    @service(service: {className: "no.sikt.soknad.VgsHandtak", method: "utsted"},
             contextArguments: ["saksbehandler"])   # null when the caller lacks access
}
type VgsResultatHandtak @key(fields: "fodselsnummer utloper signatur", resolvable: false) {
  fodselsnummer: String! @inaccessible
  utloper: Long! @inaccessible
  signatur: String! @inaccessible
}

# Subgraph B: owner, verifies. No root field exposes the type.
type VgsResultatHandtak
  @key(fields: "fodselsnummer utloper signatur")
  @entityService(service: {className: "no.sikt.vgs.HandtakVerifier", method: "verifiser"},
                 contextArguments: ["saksbehandler"]) {      # directive name and home are open, see below
  fodselsnummer: String! @inaccessible
  utloper: Long! @inaccessible
  signatur: String! @inaccessible
  resultater: [VgsResultat!]! @service(...)
}
```

## What exists today, and what is missing

Grounded in the tree, not the design page:

[cols="2,3,3"]
|===
| Need | Today | Gap
| A: reference-only stub with no table | Supported: `@key(resolvable: false)` on a non-table type is accepted and its `@key` reaches `_service.sdl` (execution-tier fixture `FilmRefStub`). | None for the stub itself.
| A: field that mints the reference per parent, with request context | `@service` on a non-root field batches through a DataLoader (`Sources` parameter, one call per request) and `contextArguments` threads values from `GraphQLContext`. Returning `null` per key is a normal answer. | Verify that a service child may return a plain Java record for a non-table type whose `@key` is `resolvable: false`, and that the scalar fields of that record resolve as properties. Likely works; pin it with a test.
| `@inaccessible` on key fields | `@link` import of `@inaccessible` is applied and validated. | Verify the field-level application survives into the served `_service.sdl`, and that a type whose every field is `@inaccessible` raises no classifier or emitter complaint in the subgraph.
| `Long` scalar for the expiry | Custom scalars bind through `@scalarType`. | None; document `EpochSeconds`-style binding in the first-client docs.
| B: resolvable entity with no table | Rejected at classify time by `EntityResolutionBuilder` ("federation entities need a @table directive"). | *The core gap.* A new resolution source.
| B: resolve an entity from hand-written Java | A custom `fetchEntities` (two-arg `buildSchema`) can replace the dispatcher, but must return jOOQ `Record`s with a `__typename` column; "domain objects without jOOQ backing" are documented as unsupported. | Dispatcher support for a non-`Record` entity, including type resolution.
| B: request identity inside entity resolution | The dispatcher builds a per-representation `DataFetchingEnvironment`, so `GraphQLContext` is reachable; `@service` reads it through `contextArguments`. | Confirm the per-rep environment keeps the `GraphQLContext`; expose it to the new resolver the same way.
| B: unverified reference is a quiet null | An alternative that does not resolve yields `null` in its slot, with no error. | Keep `null` for a resolver's `null`. Interacts with `_entities answers an unknown __typename with an error` (separate Backlog item): that item must not turn a resolver's deliberate `null` into an error.
|===

## Implementation

The design fork is where the hand-written resolution is declared. Recommendation: extend the existing
`@service` vocabulary rather than invent a parallel mechanism, because `@service` already owns method
lookup, `contextArguments`, batching shape and rejection messages.

*Recommended: an entity-resolver binding on the type.* A type with a resolvable `@key` and no `@table`
must name a service method that takes the batch of representations and returns one entity per
representation, in order, `null` for a rejected one. Signature, in the existing style: a
`List<Rep>` (or `Set`/`List` of a typed key record whose components are the `@key` fields) plus
`contextArguments` parameters, returning `List<T>` where `T` is the type's backing class. Batching
means the consumer can verify all references of a request in one Vault call.

Work, file by file in spirit:

* *Directive and classification.* Decide the spelling (fork below). `EntityResolutionBuilder` stops
  rejecting a resolvable `@key` on a non-table type when the binding is present, and still rejects it
  (with the message naming the missing `@table` or binding) when it is absent. `EntityResolution`
  carries a `table` today; make it optional or give the service-resolved shape its own `KeyAlternative`
  arm so the sealed hierarchy stays exhaustive and no consumer branches on a null table.
* *Key fields that are not columns.* Today every `@key` field must resolve to a column. For the
  service arm the key fields resolve to the representation's raw values (coerced by the field's
  scalar), so a `String`, a `Long` and a `String` reach the method as typed components.
* *Dispatcher.* `EntityFetcherDispatchClassGenerator` gains a handler per service-resolved type:
  group the representations by `__typename` as now, call the method once per group, scatter results
  back to original positions. Non-`Record` results need a typename for `resolveType`; the dispatcher
  wraps them (or records the participant type) so the default `resolveEntityType` finds the type
  without the consumer supplying one. The document in `apollo-federation.adoc` that says non-jOOQ
  entities are unsupported is rewritten to say what is supported and what is not.
* *Child fields of the verified entity.* `resultater` on B resolves from the entity's `fodselsnummer`.
  Use the existing service child with a record-backed parent (`_example-service-child-class-backed-parent`)
  and verify that it composes with a service-resolved parent; this is the same DataLoader path as any
  other non-table parent.
* *Failure semantics.* A `null` from the resolver nulls the slot; an exception from the resolver is
  an ordinary error on that representation (Vault down means an error, not a silent null, so an
  outage is not mistaken for a denial). Document both.
* *Redaction.* The key fields carry a national identity number in clear text through router logs and
  tracing. Confirm generated code never logs representations; if error paths echo a representation,
  fix that in this item. Router-side masking stays a deployment concern, called out in the docs.
* *Not in scope.* A Vault client, the signing-input helper, key policies, rotation, the router header
  propagation. These are consumer code and deployment config. Optional follow-up: a small published
  helper for the shared signing input, only if two consumers ask.

## First-client docs draft (to land in `federation-keys.adoc`)

"A subgraph can own an entity that has no table. Declare the `@key`, bind a service method that turns
representations into entities, and return `null` for a representation you refuse. This is how a
subgraph accepts a signed reference from another subgraph: the method checks the signature and the
request's user (from `contextArguments`) and returns the entity only when both hold. The producing
subgraph declares the same type with `resolvable: false` and fills the key fields from a `@service`
child field." If this does not read simply once the directive is chosen, the design is wrong.

## Tests

* Pipeline tier: classification accepts a resolvable non-table `@key` with a binding, rejects it
  without; key-field type coercion for `String` and `Long`; `@inaccessible` fields classify cleanly.
* Compilation tier: the generated dispatcher handler compiles at Java 17.
* Execution tier (sakila example, `FederationEntitiesDispatchTest` pattern): one fixture holding both
  ends. The producer service mints a reference, a representation built from it resolves, a tampered
  representation and an expired one resolve to `null`, a missing context value resolves to `null`, a
  resolver exception surfaces as an error. A mixed `_entities` batch (table-backed entity, service
  entity, rejected entity) returns slots in request order.
* Served SDL: `_service { sdl }` shows `@inaccessible` on the key fields and `@key(resolvable: false)`
  on the producer's stub; the `_Entity` union lists the service-resolved type.

## Open questions for the design owner

* Directive spelling and home: a service binding on the type (as sketched), or a type-level
  `@service`, or a Java registration through the two-arg `buildSchema` customizer. The customizer is
  the smallest change but hides the binding from the SDL, the LSP and the classifier; the directive is
  recommended.
* Resolver granularity: per type (recommended) or per `@key` alternative.
* Resolver exceptions: error (recommended) or `null`.
* Alternative to a new resolution source: a guard hook on a table-backed entity, where the `@key`
  includes non-column "proof" fields and a hook accepts or rejects each representation before the
  SELECT. It reuses batching and tenant routing but forces a table onto B's type and stretches
  "key field" to mean "not a column". Not recommended, listed so the fork is visible.
* The design page's own open questions (lifetime, per-application scope, Vault verify versus local
  public key, log masking) are decisions for the consumers and do not block this item.

## Other solutions we've considered

* `@requires` pull and `@context`/`@fromContext`: rejected on the design page for A and B ownership
  reasons; nothing here changes that.
* A fully generated sign/verify pair: puts a key-management dependency and a signing-format contract
  into the generator for one consumer's need.
