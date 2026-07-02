---
id: R415
title: "Connection first/last arguments are not clamped to >= 0, so a negative page size reaches SQL LIMIT and throws a redacted 500"
status: Spec
bucket: bug
priority: 4
theme: pagination
depends-on: []
created: 2026-07-01
last-updated: 2026-07-02
---

# Connection first/last arguments are not clamped to >= 0, so a negative page size reaches SQL LIMIT and throws a redacted 500

> Guard the connection page-size family of client mistakes (negative `first`/`last`, the
> `first`+`last` collision, and the `Integer.MAX_VALUE` overflow) at the single runtime choke
> point `ConnectionHelper.pageRequest`, throwing the R378 client-error marker
> `GraphitronClientException` so the real message reaches the client instead of a redacted
> correlation-id 500. In the same pass, unify the no-channel error disposition across sync and
> async fetcher arms: the async `.exceptionally` arms still route through `ErrorRouter.redact`,
> so without the flip a client error on a nested (DataLoader-based) connection would stay opaque.

## Problem

A negative `first` (or `last`) on a connection field flows unvalidated into the SQL `LIMIT`, so
PostgreSQL throws `ERROR: LIMIT must not be negative`, which the framework redacts into an opaque
correlation-id 500 instead of a client-facing validation error.

Reproduced against the utdanningsregisteret consumer schema:

```graphql
{ utdanningsspesifikasjoner(first: -5) { nodes { kode } } }
# -> { "errors": [{ "message": "An error occurred. Reference: <uuid>.", ... }] }
# server-side Postgres log: ERROR: LIMIT must not be negative
```

`first`/`last` are plain `Int`, so graphql-java accepts any 32-bit value; the only bound today is
the mutual-exclusion guard between the two. A negative value is a client mistake and should
surface as a client error, not become an internal fault.

## Root cause

Two defects compose.

**1. `pageRequest` has no bound on the page size.** `ConnectionHelper.pageRequest` (emitted by
`ConnectionHelperClassGenerator`) derives the page size with no lower bound and hands
`pageSize + 1` straight to the fetcher as the `limit`:

```java
if (first != null && last != null)                                  // only guard present
    throw new IllegalArgumentException("first and last must not both be specified");
boolean backward = last != null;
int pageSize = backward ? last : (first != null ? first : defaultPageSize);   // no >= 0 clamp
...
return new PageRequest(pageSize + 1, pageSize, backward, ...);      // negative limit flows to SQL
```

`first: -5` gives `pageSize = -5`, `limit = -4`, `.limit(-4)`, `LIMIT must not be negative`. Note
that the invariant PostgreSQL enforces is on the *derived* value: `limit = pageSize + 1 >= 0`. A
negative `first` is one way to violate it; `first: 2147483647` is another (`pageSize + 1` wraps to
`Integer.MIN_VALUE`), producing the identical redacted 500. The guard must therefore protect the
derived limit, not just the sign of each input.

**2. The no-channel error disposition drifted in R378.** The disposition is one decision evaluated
at four emit sites across two emitters. R378 flipped the two *sync* catch arms to
`ErrorRouter.surfaceClientErrorOrRedact` (client-marker exceptions surface, everything else still
redacts) but left the two *async* `.exceptionally` arms on plain `ErrorRouter.redact`:

- `TypeFetcherGenerator.noChannelCatchArm` (`:6409`): `surfaceClientErrorOrRedact`. Covers the
  root connection fetcher (its `pageRequest` call at `:4796` sits inside this try/catch).
- `TypeFetcherGenerator.asyncWrapTail`, no-channel branch (`:6507`): `redact`. Covers all
  DataLoader-based child fetchers, including nested `@splitQuery` connections (the
  `SplitRowsMethodEmitter` rows method calling `pageRequest` at `:1033` runs inside the batch
  lambda with no sync catch of its own; `buildSplitQueryDataFetcher` wires
  `asyncWrapTail(..., Optional.empty())`).
- `MultiTablePolymorphicEmitter.noChannelCatchArm` (`:1995`): `surfaceClientErrorOrRedact`.
  Covers the sync multi-table connection fetcher (`pageRequest` at `:918`).
- `MultiTablePolymorphicEmitter.asyncWrapTail` (`:2020`): `redact`. Covers the batched
  multi-table rows path (`pageRequest` at `:1500`).

So even with a client-typed throw from `pageRequest`, a negative `first` on a nested connection
(sakila's `Film.actorsConnection`) would still redact. The `asyncWrapTail` javadoc at `:6498`
("The `.exceptionally` arm forks on `errorChannel` the same way `catchArm` does") already asserts
a uniformity that R378 silently broke; this item restores it.

(The fifth `pageRequest` call site, `MultiTablePolymorphicEmitter:866`, passes literal nulls for
all four pagination args and cannot trip the guards; no change there.)

## Design

### Guard the client-mistake family in `pageRequest`

In the emitted `pageRequest` body, next to the existing mutual-exclusion guard:

- `if (first != null && first < 0)` throw `GraphitronClientException` with a message naming the
  argument and the offending value (e.g. `"first must not be negative (was: -5)"`); same for
  `last`. `first: 0` stays valid (empty page, `hasNextPage` still computable).
- Migrate the existing mutual-exclusion `IllegalArgumentException("first and last must not both
  be specified")` to `GraphitronClientException` with the same message: same client-mistake
  family, same choke point, same redaction defect.
- After the `pageSize` derivation, guard the derived limit: reject `pageSize == Integer.MAX_VALUE`
  with a client message, so `limit = pageSize + 1` can never wrap negative. Guarding the derived
  value (rather than each input) also covers a pathological `defaultPageSize` and closes the whole
  "negative limit reaches SQL" family at one check.

`GraphitronClientException` is the R378-established generated marker at
`<outputPackage>.schema.GraphitronClientException`; `ConnectionHelperClassGenerator.generate`
already receives `outputPackage`, so referencing it is a `ClassName.get` away.

**Why a runtime guard, not validate-time:** `first`/`last` are per-request values; graphitron's
validation tier runs at schema build time, and plain GraphQL `Int` cannot carry a lower bound.
There is no classifier decision or generator branch for the validator to mirror, so the absence of
a validate-time rule is correct, not a gap. `pageRequest` is the single choke point every
connection flavour funnels through; per-fetcher emission would duplicate the guard four times.

**Purity note reword:** `pageRequest` is documented as having "no graphql-java dependency".
`GraphitronClientException` subclasses `graphql.GraphqlErrorException`, so reword the comment to
the property that is actually load-bearing: the method takes no `DataFetchingEnvironment` (the
four env-arg extractions stay on the fetcher side). The enclosing class already imports
graphql-java heavily.

### One no-channel disposition, four sites

Lift the no-channel router call into one shared emit-side helper consulted by all four sites
(both `noChannelCatchArm`s and both `asyncWrapTail` no-channel branches), emitting
`surfaceClientErrorOrRedact`. Suggested home: a static on `ErrorRouterClassGenerator`, which both
emitters already reference and which owns knowledge of the router's API; exact home is the
implementer's judgment. The point is that the next change to the disposition is one edit, not a
four-site hand-coordination of the kind that produced this drift.

The flip is safe on the async paths: `surfaceClientErrorOrRedact` walks the cause chain, so the
`CompletionException` wrapping that DataLoader applies to a batch-function throw unwraps to the
marker type; everything that is not a `GraphitronClientException` falls through to `redact`
unchanged. Blast radius is exactly the marker type.

Fix the prose that names the old behaviour in the same pass: the block comment at
`TypeFetcherGenerator:6303` ("empty -> ErrorRouter.redact"), the `asyncWrapTail` javadoc at
`:6498` (true again after the flip), and the `MultiTablePolymorphicEmitter.asyncWrapTail` javadoc
at `:2006` which hardcodes `redact` in prose.

### Observable behaviour changes

1. Negative `first`/`last` on any connection: redacted 500 becomes a client error with a real
   message.
2. `first`+`last` collision: redacted 500 becomes a client error. The execution test
   `filmsConnection_rejectsFirstAndLastTogether` (GraphQLQueryTest `:1253`) currently pins the
   redaction and must be updated to pin the surfaced message. Its comment notes consumers could
   recover the raw message via an `@error GENERIC` handler on `java.lang.IllegalArgumentException`;
   that match would stop firing, but query-field connections have no `@error` channel today (R397
   pending), so no consumer-facing contract actually breaks.
3. Async no-channel fetchers now surface `GraphitronClientException` instead of redacting it,
   uniform with the sync arms since R378. All other exception types keep redacting.

## Implementation

- `graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/util/ConnectionHelperClassGenerator.java`:
  the three guards in the emitted `pageRequest`; purity-note reword; javadoc update.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java`:
  shared no-channel router-call helper; `asyncWrapTail` no-channel branch flips to it; comment
  fixes at `:6303` and `:6498`.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/MultiTablePolymorphicEmitter.java`:
  `asyncWrapTail` flips to the shared helper; javadoc fix at `:2006`.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/schema/ErrorRouterClassGenerator.java`
  (or wherever the shared helper lands): the one definition of the no-channel router call.
- Generator-tier assertions that pin the emitted text will need updating where they name the old
  arm (`.exceptionally(t -> ErrorRouter.redact...)`): `TypeFetcherGeneratorTest`,
  `ChannelCatchArmEmitterTest`, `FetcherPipelineTest` as applicable.

## Regression coverage

Execution tier (`graphitron-sakila-example` `GraphQLQueryTest`):

- `filmsConnection(first: -1)`: error message names `first` and the negativity, does not contain
  `"An error occurred. Reference:"`, field nullified. Same for `last: -1`.
- `filmsConnection(first: 2147483647)`: surfaced client error, not a redacted 500 (pins the
  derived-limit overflow guard).
- `filmsConnection(first: 0)`: empty `nodes`, no error (pins that zero stays valid).
- Update `filmsConnection_rejectsFirstAndLastTogether` to pin the surfaced collision message.
- The load-bearing test for the async-arm flip: `filmById(film_id: ["1"]) { actorsConnection(first: -1) { nodes { lastName } } }`
  surfaces the client message. This proves the throw propagates from the batch rows method through
  the DataLoader (as a `CompletionException`) to the flipped `.exceptionally` arm and unwraps via
  the cause-chain walk. A root-connection test alone would pass without the flip, because the sync
  catch arm has surfaced since R378.
- Redact narrowness on the async arm needs no new synthetic-fault fixture: the
  fall-through-to-redact contract of `surfaceClientErrorOrRedact` is pinned at the unit tier
  (`ErrorRouterClassGeneratorTest`) and existing execution tests keep pinning redaction of genuine
  internal faults.

## Out of scope

- Malformed `after`/`before` cursors (Base64 / `DataType.convert` throws in `decodeCursor`) redact
  the same way. Same family, different surface; candidate follow-up backlog item.
- A configurable maximum page size (a DoS-shaped policy cap). The `Integer.MAX_VALUE` guard here
  is correctness only (keeps SQL `LIMIT` non-negative); choosing and enforcing a policy bound is a
  separate feature.
