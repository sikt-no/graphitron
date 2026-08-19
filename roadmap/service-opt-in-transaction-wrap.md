---
id: R722
title: "Opt-in @service(transactional:) wraps the generated service invocation in a transaction"
status: Spec
bucket: feature
priority: 6
theme: service
depends-on: []
created: 2026-08-19
last-updated: 2026-08-19
---

# Opt-in @service(transactional:) wraps the generated service invocation in a transaction

A generated `@mutation` write runs inside a transaction graphitron opens; a `@service`
invocation does not, and the service owns its own boundaries. That contract stays. This item
adds one opt-in escape from the boilerplate it implies: `@service(transactional: true)` makes
the generated fetcher open the transaction and hand the service a transactional `DSLContext`,
so a service whose contract is all-or-nothing does not have to write the wrap by hand in every
method. Default is off, and off means exactly today's emit, byte for byte.

The documentation half of the same report is `roadmap/service-transaction-demarcation-undocumented.md`
(R721). It is independent and should ship first if only one ships: an author who knows the
contract can write the wrap themselves, while this item without the docs would advertise a
flag for a footgun we still had not named.

## Evidence

Today's emit, and where the flag would land:

* `TypeFetcherGenerator.buildServiceFetcherCommon` (`TypeFetcherGenerator.java:1647`) is the
  single body shape for all four service-backed root fetchers (query table, query record,
  mutation table, mutation record). It emits `try`, then whatever
  `ServiceMethodCallEmitter.emit` returns, then `catch (Exception e)` routing into the error
  channel. Its javadoc already states the contract this item makes optional: "the
  developer-supplied method owns the transaction scope" (`TypeFetcherGenerator.java:1627`).
* `ServiceMethodCallEmitter.emit` returns an ordered statement list: an optional DSL prelude
  (`DSLContext dsl = graphitronContext(env).getDslContext(env);`), per-entry var-decls, then
  `ReturnType result = ...`. Both the `Instance` arm's constructor args and any
  `MappingEntry.FromDsl` method position read that one `dsl` local.
* The wrap shape to copy is `TypeFetcherGenerator.buildBulkRecordTwoStepFetcher`
  (`TypeFetcherGenerator.java:5329`), whose emitted form is
  `dsl.transactionResult(tx -> { DSLContext txd = DSL.using(tx); ... })`. The `txd` rebinding
  is the part that matters here and is easy to omit.
* The directive surface is `directive @service(service: ExternalCodeReference!, contextArguments: [String!])`
  (`graphitron/src/main/resources/no/sikt/graphitron/rewrite/schema/directives.graphqls:103`).
  `ExternalCodeReference` is shared with `@externalField` and `@enum`, so the new argument
  belongs on the directive beside `contextArguments:`, not inside the reference input.
* The walked fact is presence-grain today: `ServiceFacts.Row` carries `parentTypeName` and
  `fieldName` only, because "the directive's structured `service:` object argument is the
  resolver's payload, not a walkable scalar surface". `transactional:` *is* a walkable scalar
  on the directive itself, so it is the first scalar this relation would carry, and
  `ServiceFactVisitor` is its single lexical home.
* Child `@service` arms are a different body: `ServiceRowsFragments.delegateBody` and
  `liftBody` declare `dsl` only when `sc.method().callShape().needsDsl()`, then delegate a
  whole batch of parent keys.

Fixture base is already rich: `graphitron-sakila-example/src/main/resources/graphql/schema.graphqls`
carries many `@service` mutation coordinates across the record, payload, composite-PK and
error-channel shapes. What it has no instance of is a service that writes twice and throws
between the writes, which is the shape this item needs to witness a rollback.

## Field report

Suggested at https://github.com/sikt-no/graphitron/issues/530, on 10.0.0-RC31, as the optional
second half of a report whose first half is a docs request. The reporter's own service
partially committed a two-element batch, and their fix was `ctx.transactionResult(...)` inside
the service, which they consider the correct permanent home. They ask for the flag as
convenience for "the common case", explicitly opt-in.

## Position

**Opt-in, never default.** The reporter's three arguments against a default wrap are correct
and this item adopts them rather than re-deriving them:

* A service may have effects a rollback cannot reach, a write against a second datasource
  being the obvious one. A default wrap would manufacture a new silent failure mode: external
  effect applied, local state rolled back, error payload looking consistent. Whether wrapping
  is safe is irreducibly the service author's call, and the generator cannot see far enough to
  make it.
* A service that already manages its own `ctx.transaction()` would have it demoted to a
  savepoint under an outer wrap, moving the commit point without anything in the schema or the
  service changing.
* `@service` is the documented escape hatch. "You own the implementation" should keep
  including the commit point.

So the flag's default is `false`, and `false` must be the byte-identical emit we ship today.
That is an acceptance criterion, not a hope: the compilation-tier fixtures that exist for the
four service arms should be unchanged by this item's diff.

**Root fields only. A set flag on any other coordinate is a build error.** The four arms that
share `buildServiceFetcherCommon` are one field, one call, one commit point, which is the only
shape where "wrap the invocation" has an unambiguous meaning. A child `@service` arm
(`ServiceRowsFragments`) hands the method a batch of parent keys gathered across the
selection, so wrapping it would put an entire batch of unrelated parents in one transaction:
a different contract, which nobody asked for and which we should not infer. Reject with a
diagnostic that says so, rather than silently ignoring the flag; silently-ignored directives
are the class of defect two other items on this board exist to fix.

**A service that binds no `DSLContext` cannot be wrapped, and asking for it is an author
error.** `callShape().needsDsl()` is already the emitter's own signal for whether a `dsl` local
is declared at all. With no `DSLContext` reaching the service, a transaction around the call
has nothing to demarcate: it would open, do nothing observable, and commit, which is worse
than a rejection because it reads as a guarantee. Reject at build time with the reason stated.

**Query fields are allowed.** The four arms are one emitter and one flag, and a writing
query-path `@service` exists in the wild (`docs/manual/how-to/mcp-agent-context.adoc:105`
acknowledges it as the reason the MCP rollback guarantee does not cover it). Forbidding the
flag on queries would be an asymmetry with no argument behind it. Note the interaction with
`roadmap/query-read-only-enforcement.md`: that item reasons about read-only enforcement for
paths graphitron does not control, and a `transactional: true` query service is a coordinate
where the author has told us the call writes. Whichever lands second should say so on the
other's terms; neither blocks the other.

## The one correctness risk

Wrapping changes what the catch arm sees, and the error channel is sensitive to exactly that.

`TransactionalCallable.run(Configuration)` declares `throws Throwable` (verified against jOOQ
3.20.11), so a service method's checked exception still compiles inside the lambda. What is
not settled by that signature is the identity of the exception that leaves
`transactionResult` after the rollback. If a non-`RuntimeException` is rethrown wrapped, the
`catch (Exception e)` arm stops seeing the service's own exception and starts seeing a jOOQ
wrapper with the original as cause. That matters because the channel's dispatch rule is
"the *wrapping* exception wins for any mapping it matches first"
(`docs/manual/how-to/error-channel.adoc:140`), so a schema with a broad handler could silently
change which error type a client receives, on a field whose only edit was adding
`transactional: true`.

The requirement is therefore stated as an invariant rather than as an assumption about jOOQ:
**the exception the catch arm observes must be the one the service threw, wrapped or not,
identically to the unwrapped emit.** The implementation determines jOOQ's actual rethrow
behaviour by test, not by reading, and if it does wrap, the emitted lambda unwraps before
leaving the transaction so the channel sees what it sees today. Pin it for both an unchecked
and a checked service exception; the checked case is the one that can regress.

## Implementation

Four layers, small at each.

* **Directive.** Add `transactional: Boolean = false` to `@service` in `directives.graphqls`,
  with a description that states the contract and the two rejections rather than just the
  behaviour.
* **Fact.** `ServiceFactVisitor` reads the scalar into a new `ServiceFacts.Row` component. This
  is the relation's first scalar, so the visitor's presence-grain note goes with the change:
  the comment currently explains *why* the row is presence-grain, and that reason no longer
  covers the whole directive.
* **Model and validation.** The flag rides to emit on the classified field's service carrier.
  The two rejections belong with the other service-shape rejections (the
  `ServiceCarrierShapeError` / `ServiceDirectiveResolver` family) so they surface as author
  errors in the usual voice, not as generator invariants.
* **Emit.** In `buildServiceFetcherCommon`, when the flag is set, wrap the statements
  `ServiceMethodCallEmitter.emit` returns in `dsl.transactionResult(tx -> { ... })` with
  `DSLContext txd = DSL.using(tx);` and every `FromDsl` position plus the `Instance` arm's
  constructor arg rebound to `txd`. The rebinding is the whole point: a wrap that leaves the
  service on the outer `dsl` opens a transaction the service never joins, which is the failure
  mode this item exists to remove, in a form that looks like it works. Prefer threading the
  local's name into `ServiceMethodCallEmitter.emit` over post-processing its `CodeBlock`s.

The wrap goes *inside* the existing `try` and outside nothing else: the validator pre-step
stays ahead of the try, and the catch arm stays outside the transaction, so rollback happens
before the channel maps.

## Tests

* **Unit / pipeline.** The flag reaches the carrier; both rejections fire with their stated
  reasons (set on a child `@service` coordinate, and set on a service binding no
  `DSLContext`).
* **Emission pin.** The wrapped body declares `txd` and binds the service call to it. The pin
  is a seam pin in the sense `roadmap/emitted-seam-pin-assertion-convention.md` is still
  litigating; keep it to the boundary call and the rebinding, not to incidental body text.
* **Default-off equality.** The existing service-arm compilation fixtures emit byte-identical
  output. This is the criterion that makes the feature safe to add.
* **Execution tier, the one that would have caught the reported bug.** A new service method
  that writes two rows and throws between them, on one coordinate with the flag and one
  without. Without: row 1 survives, which is today's behaviour and the reporter's incident.
  With: neither row survives. Add the fixture beside the existing `@service` mutation
  coordinates in the example schema.
* **Exception identity.** Both an unchecked and a checked service exception, asserted to reach
  the same error type through the channel with the flag on as with it off.

## User documentation

The flag goes on `docs/manual/reference/directives/service.adoc` as a parameter row plus a
paragraph, and the paragraph must say what the flag does *not* do: it does not make the
service safe to roll back, it does not reach a second datasource, and it demotes a
service-managed `ctx.transaction()` to a savepoint. Those are the reporter's three arguments,
and they are exactly what an author needs before turning the flag on.

If R721 has landed, this amends the statement it wrote rather than replacing it: the contract
is still that a `@service` owns its boundaries, with one declarative way to delegate that
back. If R721 has not landed, this item writes that statement too, since a flag documented
without the contract it modifies is not documentation.

## Out of scope

* Auto-wrapping, under any heuristic.
* Wrapping child `@service` arms, and batch-scoped transaction semantics generally.
* Any change to the `@mutation` or `@routine` boundaries.
* The read-only enforcement question for delegated paths
  (`roadmap/query-read-only-enforcement.md`).
* Isolation level, propagation, and timeout controls. If they are ever wanted they are their
  own item; a boolean is the whole surface here.

## Related

* `roadmap/service-transaction-demarcation-undocumented.md` (R721), the docs half of the same
  report. Independent, and the one to ship first.
* `roadmap/query-read-only-enforcement.md`, the framing neighbour for what graphitron may
  assume about a path it does not control.
* `roadmap/compound-entity-mutations.md`, which specifies a single jOOQ transaction wrapping a
  compound write. Same mechanism, generated side, and worth reading for the boundary
  vocabulary before writing this one.
* `roadmap/emitted-seam-pin-assertion-convention.md`, which decides what the emission pin
  above is allowed to assert.
