---
id: R1199
title: "An _entities lookup with vouchers for several values resolves on one connection"
status: Spec
bucket: feature
priority: 3
theme: service
depends-on: []
created: 2026-11-18
last-updated: 2026-11-24
---

# An _entities lookup with vouchers for several values resolves on one connection

## Goal

An accepting subgraph answers a router's `_entities` call whose vouchers carry several vouched-for values
(a list of cases with different applicants, say) with every entity it can resolve, still taking one
vouched connection from the pool. That connection is mounted for the whole set of values the call
presents, and B's row-level security (RLS: Postgres policies that decide, per row, what a connection may
read) tests membership in the set rather than equality with one value.

A *vouched-for value* is the part of a voucher that names whose data A vouched for (an owner, an
applicant): B's policies read it off the connection, and it is the access decision. The *lookup part* is
navigation only, the key B finds the entity row by. Both terms are R1119's; its how-to,
`docs/manual/how-to/voucher-handover.adoc`, is the author-facing account.

Two shapes become usable:

* *Several owners in one list.* Today a list whose rows belong to different owners resolves only the
  first owner's rows; each of the others fails alone at its own index (R1119's slice 8g). After this
  item they all resolve.
* *A voucher per entity* ([GitHub issue 584](https://github.com/sikt-no/graphitron/issues/584)). The
  vouched-for value is the entity's own key (a sak's id, with B's policies testing `sak_id`), so a list
  of N rows always presents N values. The voucher can be written today, vouching for a second field that
  reads the key's column (see *The per-entity voucher*); what it lacks is a limit sized to a list, which
  the cap gives it.

What a consumer sees: its connection source reads a set off the `ConnectionKey` instead of one value
per field, and its policies test `= ANY(...)` instead of `=`. Both are breaking for an accepting
consumer. Nothing changes in the SDL either side writes or in the SQL Graphitron generates.

Two things are not in this item and do not block it. Letting `Sak` itself carry the voucher, guarded by
`@requires` in B (Alf's ruling of 2026-11-23), changes what both sides write and is R1207, which depends
on this item. Narrowing `VOUCHER_VOUCHED_FOR_IN_KEY`, so the per-entity voucher needs no second field,
moved there with it, since whether the voucher keeps a lookup part decides whether there is a refusal
left to narrow.

## Why

A federation router does not send one `_entities` call per parent: it batches every representation bound
for a subgraph from the whole list into one call. Under the one-vouched-for-value-per-operation rule
(2026-11-12) a list whose rows belong to different owners therefore always crosses the limit. Observed
in kreg, under FS's `sakerV2` with three cases, each with a different `soker`: before slice 8g the whole
call failed with a redacted `500`, and since slice 8g two of the three answer `null` with an error. Either
way vouchers do not work for the list case they exist for.

Keeping an `_entities` lookup to one connection from the pool is wanted; failing entities to keep it
there is not (Alf, 2026-11-18). One connection mounted for every value keeps the first and drops the
second. Filip chose this over a connection per value on 2026-11-23 (see *Other solutions we've
considered*).

## How it works

```
 B, one _entities call
 ---------------------
 the existing loop, every representation in order (one alternative cascade, as today):
   plain key          -> load under the key with no parts, as today
   voucher            -> verify (a failure rejects the request, nothing acquired)
                         coerce its vouched-for values into one VouchedFor
                         admit it into the operation's set, unless the set is full
                         admitted: a pending load; past the cap: null + error at its own index,
                         no loader, no connection, no SQL
 after the loop       -> close the set; register every pending load under the operation's vouched
                         key: one key, one loader per type
 loaders dispatch     -> the vouched key's first use asks the source for one lease;
                         one SELECT per batch reads every admitted owner's entities
```

1. *Verify before admitting, admit before registering.* Every voucher in the call is verified before it
   is admitted, as slice 8g already requires, and every one is admitted before any registers with a
   loader, so the set is complete when the first representation is keyed by it. Today the voucher arm
   verifies, keys and registers one representation at a time inside `loadSlot`. The arm splits rather
   than a second pass appearing beside it: its verify, coerce and admit half runs where it runs now, in
   the one alternative cascade (`HandleMethodBody.emitAlternativeCascade`), and answers a pending load
   instead of registering; `fetchEntities` closes the set after its loop and registers the pending loads
   under the operation's vouched key. Choosing a representation's type and alternative stays decided in
   one place. Nothing acquires before the loaders dispatch, so registering later changes no acquisition.
2. *Admission is a set with a cap.* The carrier (`OwnedConnections`, the per-operation object that holds
   the request's leases) holds the operation's vouched-for set instead of one vouched key. A value is
   admitted if it is already in the set or the set has room; the first values in representation order
   fill it, so which values are admitted never depends on loader dispatch order. A representation whose
   value is past the cap fails as slice 8g fails one today: `null`, an error at its own `_entities`
   index naming the limit and no value, no loader, no connection, an audit line by vouch id and scope.
   One owner is a set of one, so a list of one owner's rows runs exactly as today.
3. *The set closes when the first `_entities` field that admitted a voucher registers.* Admission is per
   operation. A second, aliased `_entities` field in the same operation sees a closed set: its
   representations whose value is in the set resolve on the same connection, and the rest fail per
   representation as past the limit. The reason is that the key is a value (Alf, 2026-11-14):
   representations already registered under the operation's vouched key cannot move to another, so the
   key cannot grow after they are. A field that admitted no voucher registers nothing under a vouched key,
   so it leaves the set open. A router sends one `_entities` field per fetch, so this bounds an unusual
   operation, not a path the list case takes.
4. *One key, one lease.* The vouched key is `ConnectionKey` with the set as its vouched-for part. Every
   admitted representation loads under that one key, so each accepting type gets one loader and one
   SELECT per batch for all admitted owners, and the source is asked for one lease, once.
5. *Below the entity.* Each resolved entity row hands the same key down in `localContext`, as it does
   today, so a split query, a `@service`, a routine or a polymorphic field below the entity acquires the
   same lease and batches across every owner's entities at once. Fewer statements than today's one
   owner per operation, not more.

### The key's shape

Today `ConnectionKey` has one `Optional` component per vouched-for field (`key.eierId()`), all present or
all empty. It becomes:

```java
public record ConnectionKey(Set<VouchedFor> vouchedFor) {
    public record VouchedFor(String eierId) {}            // one component per vouched-for field
    public static ConnectionKey defaultSource() { ... }   // the empty set: the request's own connection
    public static ConnectionKey forVouchedFor(Set<VouchedFor> values) { ... }   // never empty
    public boolean hasVouchedFor() { return !vouchedFor.isEmpty(); }
}
```

* A set of rows, never an array per field. With vouched-for fields (org, person), per-field arrays
  `{o1, o2}` and `{p1, p2}` would let a policy pair o1 with p2, which no voucher granted. A row of the
  vouched-for fields keeps each voucher's grant whole.
* `VouchedFor`'s components are `ConnectionKeyShape.vouchedFor()`'s parts, unchanged: one per vouched-for
  field, typed by the column it reads, in name order. Every `@accept` in a graph has the one shape
  (`VOUCHER_ACCEPT_VOUCHED_FOR_DIFFERS`), so there is one `VouchedFor` per build.
* The set is type-blind: a `VouchedFor` row names values, not the accepting type that admitted them,
  as today's one-value key does. Types share values only by sharing vouched-for field names, which owners
  do by design (`eierId` on every accepting type). A per-entity voucher names its type's own key, so it
  is in practice the build's one accepting shape: a second type with a key of its own is refused by
  `VOUCHER_ACCEPT_VOUCHED_FOR_DIFFERS`, and one made to share the name shares values, so a policy on
  either table admits the other's ids. Proposed: keep the set type-blind and state that limit in the
  how-to, for Alf and Filip at review. The alternative is under *Other solutions*; choosing it later
  breaks consumers' source a second time.
* One owner is a set of one, so there is one shape and no single-value mode beside it.
* The empty set is the key's "no part", not `Optional<Set<VouchedFor>>`, which would have two empty
  states, one of them unreachable through `forVouchedFor`. That keeps what "tenancy is not a mode" (Alf,
  2026-11-16) means, a part may be empty and the source decides what empty means, but not its words,
  "one `Optional` per part". `runtime-extension-points.adoc` restates it as "one component per part,
  each possibly empty: an `Optional` tenant, a set of vouched-for rows".
* The set is immutable, and both records' `toString` name no value, as today's key names none:
  `VouchedFor` overrides its own, or a set printed through the key would leak each value.
* What it deletes: the key's "every vouched-for value or none" constructor check (the type now
  guarantees it), the per-field `forVouchedFor` parameters and accessors, the carrier's single
  `vouchedKey`, and the multi-owner failures slice 8g ships.
* In a tenant-column build the key has no vouched-for part, as today (`@accept` is refused there).

### The cap

The limit becomes a bound on the set's size, the most distinct vouched-for values one operation admits,
rather than "one". It bounds what one request costs B's database: the whole set is one session setting,
and every row read under B's policies tests membership in it. It grants nothing: every admitted value
was vouched for by A to the same caller. It is a runtime setting, not a store fact: nothing in the SDL
or the catalog states it and no emitter branches on it.

*A runtime hook, shaped like `BatchSizeLimit` (Filip, 2026-11-24).* The accepting runtime takes it as
`Graphitron.runtime(...).withVouchedValueLimit(limit)`, beside `withBatchSizeLimit`, and the generated
`VouchedValueLimit` sits next to `BatchSizeLimit` in the schema package:

```java
var runtime = Graphitron.runtime(SQLDialect.POSTGRES, voucherKeys)
    .withVouchedValueLimit(env -> 1000);
```

* *Asked once per operation*, by the carrier at the first voucher it admits, with that `_entities` field's
  environment (`maxVouchedValues(env)`), and held for the operation. The set is per operation, so the
  limit cannot change partway through it; the environment lets it differ by request, a larger one for a
  batch integration, say.
* *Default 1000* (Filip, 2026-11-24) when no hook is given: sized to a list or a page, not to a few owners,
  since the per-entity voucher presents one value per row. A policy's `= ANY` over a session setting is
  evaluated per row and the planner cannot treat the array as a constant, so the implementer still
  measures a thousand-value set against the RLS fixture and reports it; a cost that argues for a lower
  default goes back to Filip rather than changing it silently.
* *It fails closed, unlike `BatchSizeLimit`.* There, no hook or an answer below 1 means no limit, which is
  safe for a bound that only splits a statement. Here no limit would drop the protection: an answer below
  1 admits no value, so every vouched representation fails at its own index as past the limit, and a
  consumer that wants no practical bound answers `Integer.MAX_VALUE`. A hook that throws leaves the default
  standing and logs one ERROR line naming its class, as the runtime's other hooks do.
* *No escape-hatch path.* `Graphitron.newGraphQL()` has no runtime, but a build that accepts vouchers is
  owned connections only, so the hook is always reachable.

Why there and not elsewhere:

* Not on `VoucherKeys`: that is the key-material interface the how-to invites consumers to implement, so a
  cap given through its `accepting(...)` factory would not exist for a consumer's own implementation.
* Not in the configuration, though `<maxAllowedPageSize>` is a size bound there: the store checks it at
  build time against what the schema states (`DEFAULT_PAGE_SIZE_ABOVE_MAXIMUM`). The cap has nothing in
  B's schema to be checked against, since the list whose rows present the values is paged by A, in
  another build, and what it protects is a deployment's fact.
* Not on `@accept`: the set is per operation across every accepting type (one key, one lease), so a cap
  per type would need a rule for combining them.
* Not per request on the execution input, nor on the connection source: the hook's environment already
  gives a per-request answer from one place, and the source is consumer-implemented as `VoucherKeys` is.

The how-to says why the bound exists and what a consumer that pages larger raises it to.

`VoucherRuntime.LIMIT_MESSAGE` keeps naming the limit and no value, reworded from "one vouched-for value"
to the cap.

### The connection source and the policies

The source sets the whole set on the connection, and the policies test membership. For one vouched-for
field, the how-to's source and policy become:

```java
var source = ConnectionSources.mounting(
    key -> dataSource,
    (connection, key) -> DSL.using(connection, SQLDialect.POSTGRES)
        .execute("select set_config('app.vouched_owners', ?::text, false)",
            (Object) key.vouchedFor().stream().map(VouchedFor::eierId).toArray(String[]::new)),
    (connection, key) -> DSL.using(connection, SQLDialect.POSTGRES)
        .execute("select set_config('app.vouched_owners', '{}', false)"));
```

```sql
CREATE POLICY vouched_result_owner ON vouched_result
    USING (owner_id = ANY (coalesce(nullif(current_setting('app.vouched_owners', true), ''), '{}')::text[]));
```

For several vouched-for fields the source writes a JSON array of objects and the policy tests membership
of the row's pair (`EXISTS (SELECT 1 FROM jsonb_to_recordset(...) v(org int, person int) WHERE v.org =
org_id AND v.person = person_id)`), which keeps each pair whole. The exact SQL is the how-to's; the
implementer runs both in the RLS fixture before the how-to states them.

### What it gives up

* *Precision inside one operation.* A row of one entity's subtree is read on a connection that can also
  see the other admitted owners' rows, so a join that strays across owners is no longer caught by the
  database. It grants the caller nothing new: A vouched every admitted value to the same caller in the
  same request, the reasoning by which vouchers sharing a value already share a connection across A's
  operations and scopes (2026-11-12). The how-to states the loss, and that a consumer needing per-entity
  isolation vouches for the entity's own key (the per-entity voucher below), which makes the loss nil,
  since each value is then one entity, for the build's one accepting shape (see *The key's shape*).
* *Connection reuse across requests in practice.* A set key rarely repeats between requests. The lease
  is per operation anyway, so nothing that exists today is lost; it only rules out a pool keyed by owner,
  which no source ships.

## The per-entity voucher (GitHub issue 584)

A consumer has an event-log subgraph B whose access should follow A's RLS on `Sak`. B stores events keyed
by the sak's id. The wanted supergraph shape is a list of saker, each with its events below it
(`hendelser: [Sakshendelse]`, as `@asConnection` eventually). The consumer's timeline: testable by
2026-12-10, in production by 2027-02-01; otherwise it falls back to replicating the data.

The vouched-for value is the sak id, so every row in the list is a distinct value and the router batches
them into one `_entities` call. Under the set key that is one connection whose session holds the sak ids,
B's policy `sak_id = ANY(...)`, and one SELECT. That is why the cap's default is sized to a list.

The consumer's shape uses today's vocabulary only, provided B owns a `sak(sak_id)` table that its events
reference. `VOUCHER_VOUCHED_FOR_IN_KEY` refuses a vouched-for field that is also a field of the key the
voucher decodes to, and it compares fields by name, so the voucher vouches for a second field reading the
key's column. kreg is written this way today (`nasjonalIdForVoucher` beside its key `nasjonalId`, both
reading `NASJONAL_ID`). Whether that second field goes is R1207's, as is letting `Sak` itself issue.
Until R1207 rules whether the form is a valid schema or a loophole, this item pins it as accepted (see
*Tests*), since a consumer depends on it, but does not teach it in the how-to, since publishing it would
give that ruling.

```graphql
# Subgraph A: the issuer. Sak itself cannot issue (it is a node, keyed, and a root field returns it),
# so the voucher sits on a type reached through a field on Sak.
type Sak @table(name: "sak") @key(fields: "id") {
  id: ID! @nodeId
  hendelseLogg: SakHendelseLogg      # same row, read as the issuing type
}
type SakHendelseLogg @table(name: "sak")
    @issue(scope: "sakshendelser", ttl: "PT5M", vouchesFor: "sakIdForVoucher") {
  sakId: ID! @field(name: "sak_id")            # the lookup part, named as B's key
  sakIdForVoucher: ID! @field(name: "sak_id")  # the vouched-for value, the same column
}

# Subgraph B: the lookup part is the key; the vouched-for field reads the same column.
type SakHendelseLogg @table(name: "sak") @key(fields: "sakId")
    @accept(scope: "sakshendelser", vouchesFor: "sakIdForVoucher") {
  sakId: ID! @field(name: "sak_id") @inaccessible
  sakIdForVoucher: ID! @field(name: "sak_id") @inaccessible
  hendelser: [Sakshendelse!]! @reference(path: [{key: "sakshendelse_sak_fkey"}])
}
```

*Outside this item.*

* An accepted entity with no table of its own in B, whose "row" is just its vouched-for values (the
  issue's point 2 taken literally). It contradicts how an entity is resolved
  (`docs/manual/reference/federation.adoc`, "selecting its row from its table") and three `@accept`
  refusals. If the `sak` table above is not acceptable to the consumer, it becomes its own item,
  depending on this one.
* `@asConnection` below an accepting type is R1119's slice 8e.3; a plain list is the first target.

## Relation to R1119

R1119 (the voucher handover) is In Progress with slice 8e.3 and its before-Done list remaining. None of
it touches what this item changes: 8e.3 hands the connection key down to connection-typed fields, and a
set-valued key rides that hand-down unchanged. So this item does not wait on it.

The one shared surface is prose. This item rewrites the source and policies in `voucher-handover.adoc`
and its rendered example, and R1119's before-Done list moves the first-client docs draft into
`federation-keys.adoc` and the security guide. Whichever lands second carries the other's text: if this
item lands first, the move carries the set-shaped source and policies; if R1119 lands first, this item
edits the moved copy too. When this item ships it also updates R1119's own plan where it states the
one-value rule (*One connection per vouched-for value*, the 2026-11-12 ruling's line, the request-
rejection ruling's "and the one-vouched-value limit"), if R1119 is still on the board.

## Implementation

* *`ConnectionKeyShape` and the key* (`graphitron/.../emit/ConnectionKeyShape.java`,
  `ConnectionRuntimeClassGenerator`): the accepting arm of the `ConnectionKey` record emits the
  `Set<VouchedFor>` component and the nested `VouchedFor` record from the shape's parts; `forVouchedFor`
  takes a non-empty set; `hasVouchedFor` reads the set. The shape's own type does not change.
* *The carrier* (`ConnectionRuntimeClassGenerator`): `vouchedKey` becomes the operation's admitted set
  and its cap, asked of the runtime's `VouchedValueLimit` at the first admission and held for the
  operation; `admitVouched(ConnectionKey)` becomes `admitVouched(VouchedFor, env)`, answering whether the
  value is in the set or was added to it, still the one site that decides. Closing the set answers the
  operation's vouched key. `entryFor`'s invariant becomes "a key with a vouched-for part is the
  operation's vouched key", still an `IllegalStateException`.
* *The dispatch* (`EntityFetcherDispatchClassGenerator`, `HandleMethodBody`): the voucher arm in the
  alternative cascade verifies, coerces and admits as now, and answers a pending load or the past-cap
  failure instead of registering; `fetchEntities` closes the set after its loop and registers the pending
  loads. Verification, coercion, the rejection path and the audit lines move, they are not rewritten. The
  multi-tenant synchronous dispatch is untouched: `@accept` is refused in a tenant-column build.
  `load<Type>` is also reached from `Query.node` and `Query.nodes` (through `load` and `loadKeyed`), where
  no `fetchEntities` closes a set; that path never meets the voucher arm, since a representation built
  from a node id carries no voucher field, so it gains no close of its own.
* *The runtime* (`ConnectionRuntimeClassGenerator`, beside `withBatchSizeLimit`; `VoucherRuntime`): in an
  accepting build, the generated `VouchedValueLimit` interface (`maxVouchedValues(env)`), the runtime's
  `withVouchedValueLimit` copy method and its accessor, the default of 1000 without one, an answer below 1
  admitting none, a throwing hook leaving the default, and the reworded `LIMIT_MESSAGE`.
* *Docs*: `voucher-handover.adoc` (step 4's source and policy, step 5's limit bullet, the precision
  note, and what a per-entity voucher means for a consumer: one value per row, a cap sized to a list, one
  accepting shape per build; not the second-field form, see *The per-entity voucher*), `accept.adoc`
  (the limit paragraph, the audit outcome name), `docs/manual/reference/runtime-api.adoc` (a
  `VouchedValueLimit` row in the consumer hooks table, beside `BatchSizeLimit`, its default and why it
  fails closed), and `docs/architecture/reference/runtime-extension-points.adoc`
  (the key's parts restated as above, the limit sentence). The same page's one-request-rejection
  paragraph still lists "a second vouched-for value" among the request rejections, stale since slice 8g;
  that is fixed on its own as a `docs` commit, not carried here.
* *Commit*: `feat(R1199)!` with a `BREAKING-CHANGE:` footer naming the key's accessor and the policies'
  membership test.

## Tests

* *Unit tier* (emitter harness, `testing.adoc` "Every emitted unit runs at the unit tier"): the generated
  `ConnectionKey` for one and for two vouched-for fields; admission in representation order, a repeated
  value admitted again, the value past the cap refused, a cap of one behaving as slice 8g does; the
  `VouchedValueLimit` asked once per operation, 1000 without one, an answer below 1 admitting none, a
  throwing hook leaving 1000; a forged
  voucher after admitted ones still rejecting the request; a second `_entities` field seeing a closed set;
  `VouchedFor`'s and the key's `toString` naming no value.
  `VoucherDispatchTest` and `RequestRejectionCarrierTest` move from the one-value key to the set.
* *Execution tier* (sakila's federated fixture, `VoucherAcceptExecutionTest`, with the fixture's
  source and policies moved to the set):
  * `vouchersForTwoOwnersInOneLookupResolveTheFirstOwnersAndFailEachOfTheSecondsAtItsIndex` becomes:
    representations for two owners in one call resolve every entity of both, on one lease, acquired
    once.
  * `withVouchedValueLimit(env -> 2)` with three owners: the third owner's representations each fail at
    their own index, naming no value, and take no connection.
  * A split query and a `@service` below entities of two owners read on the one lease, in one batch.
  * A plain-key representation beside vouchers still reads on the request's own connection.
  * The per-entity shape: representations carrying only a voucher, one per row, each vouching for its
    own row's key through a second field on the key's column, resolve every row on one lease, acquired
    once, each answering its own row.
* *Pipeline tier*: a near miss in `facts/defects/voucher-accept-keys.graphqls`, beside `ClaimInKey`: an
  `@accept` whose vouched-for field reads its key's column under another name draws no
  `VOUCHER_VOUCHED_FOR_IN_KEY`, and a `graphitron_occurrence_column` claim states that both fields read
  the one column. A change to the rule that compares columns then has to face the shared column in rows
  rather than surface at a consumer.

## Retired vocabulary

* "One vouched-for value per operation" / "the one-value limit" / "one-vouched-value limit": becomes
  the cap on the vouched-for set.
* `ConnectionKey`'s per-field vouched-for accessors (`key.eierId()` and the like): become
  `key.vouchedFor()`.
* `app.vouched_owner` in the how-to and the fixture: becomes `app.vouched_owners`.
* "Group by vouched-for value" and "one vouched connection per group" (R1119's *How it works*, step 4):
  every admitted voucher of an operation is in one group, on one connection.

## Other solutions we've considered

* *A connection per value* (option 3 in earlier drafts). Each value keeps a connection of its own, as
  the one-value rule's reasoning assumed. Since slices 8e.1 and 8e.2 a child `@service`, a split query
  and a polymorphic field below a vouched entity read later, on the key the entity hands down, so a
  value's connection is held until its subtree is read: one leased connection per value for the
  operation, which grows with the list and is the cost the limit exists to bound. It would also need
  R1192's per-worker release to be tolerable. The per-entity voucher makes it worst, one connection per
  row. Filip chose the set key (2026-11-23); a consumer needing per-entity isolation gets it from the
  per-entity voucher instead.
* *Per-field arrays on the key.* Simpler for a one-field shape, wrong for two: see *The key's shape*.
* *A `VouchedFor` that names its accepting type.* Each row would carry the type it was admitted for, so
  two types' equal values stay apart and per-entity vouchers could span several accepting types. It
  costs a component no policy reads today and lifts a limit no consumer has met;
  `VOUCHER_ACCEPT_VOUCHED_FOR_DIFFERS` already holds a build to one shape. Not proposed, but if Alf or
  Filip want it, it belongs in this item, since `VouchedFor` is the source consumers code against here.
* *Failing the whole lookup past the limit.* What shipped before slice 8g; rejected by Alf (2026-11-18).
* *The `@requires` guard and the narrowed refusal in this item.* Folded in for one revision, split out
  to R1207 at the Spec review (2026-11-23): the set key needs neither, the guard's shape is still open,
  and its leading directions retire the voucher key the first form's representations match on, which a
  consumer of the first form needs a migration for.

## Smaller items from the same trace

* A request rejection a consumer does not map reaches the router as an uncaught `500`. The limit no
  longer is one, but a failed voucher check and a failed acquisition still are; the how-to should say
  which rejections a consumer must map in `rejectedRequest`, and whether a default answer that names the
  cause belongs in graphitron-jakarta-rest. Its own item if wanted.
* Fields below a vouched entity with fetchers of their own hold the vouched lease to the operation's end.
  Releasing it earlier is R1192's per-worker release, unchanged by this item.

## Reviewer findings

### Round 1: Spec → Ready, revisions requested (session_a75e11ee-4af6-4577-afbe-0ef4ebf3fdd5, 2026-11-23)

The set key passes both questions on its own. Its goal reads without the phase list: an accepting
subgraph answers a batched `_entities` call whose vouchers carry several vouched-for values with every
entity, on one vouched connection, and the consumer's source reads a set off `ConnectionKey` while its
policies test membership. It extends the shapes already in the tree (the one admission site
`admitVouched`, the one alternative cascade, the generated key record, the carrier's single vouched
key) rather than standing a mechanism beside them, and I would hand that half to an implementer as it
is. Every symbol, relation, doc section, test and fixture the spec names exists as named. The item as a
whole does not pass, because the second half is a design still open.

1. **The spec says it is not Ready, and no ruling has arrived (question 2).** *The per-entity voucher*
   ends "Needs a ruling from Alf or Filip before Ready" on narrowing `VOUCHER_VOUCHED_FOR_IN_KEY`,
   the superset case included, and no ruling is recorded in the body or under `docs/architecture`.
   *The cap* leaves its home "open for Alf and Filip at review"; the runtime placement is well argued
   and needs only their nod, not a revision. Satisfied when the narrowing's ruling is recorded where
   CLAUDE.md puts rulings and the body states it, or when the narrowing leaves this item (finding 3).

   *Author (2026-11-23):* the narrowing left this item for R1207, with its pending ruling. *The cap*
   still proposes the runtime and awaits Alf's or Filip's nod.

   *Author (2026-11-24):* Filip ruled the cap: a runtime hook taking the field's environment, shaped like
   `BatchSizeLimit` (`withVouchedValueLimit`), default 1000. *The cap* states it.

2. **The goal promises a change the plan does not build (question 1).** The Goal's second bullet and
   *What a consumer sees* ("the `@requires` guard does change what both sides write and what Graphitron
   mints") promise the second iteration, but *Implementation*, *Tests* and *Retired vocabulary* hold
   nothing that mints `@external` or `@requires`, stores the guarded fields, lifts the three
   `VOUCHER_ISSUE_ON_*` refusals or retires absorbing and withholding. *Guarding with `@requires`*
   leaves its shape open across the voucher as a key or not, type-level `@accept` against a field-level
   `@requiresVoucher`, which fields are guarded, whether the lookup part goes, composition of
   `@external` on a key field and the pass-through of an authored `@requires`, behind a router proof
   that cannot run in the sandbox. A reader cannot say what an author writes in A and B once this item
   lands. Satisfied when the Goal and the plan describe the same change.

   *Author (2026-11-23):* the Goal now promises the set key alone and names what is not in the item.
   *Guarding with `@requires`* moved whole to R1207.

3. **Scope, which the spec hands to this review: the second iteration leaves this item (question 2).**
   * *The set key does not need it.* The per-entity voucher is usable today without the narrowing.
     `VOUCHER_VOUCHED_FOR_IN_KEY`'s rule in `graphitron-model.sql` matches vouched-for fields to key
     fields by field name, so a second field reading the key's column passes it. That is how the FS
     consumer behind *Why* is written: kreg's `VgsKompetanseoppnaelserForSokerGittNasjonalId` keys by
     `nasjonalId` and vouches for `nasjonalIdForVoucher`, both reading `NASJONAL_ID`. The Goal's "Usable
     once ... one refusal, `VOUCHER_VOUCHED_FOR_IN_KEY`, is narrowed" overstates: the narrowing drops
     the duplicate field, it does not enable the shape. Issue 584's consumer can take the same form on
     the set key alone, within its timeline.
   * *The open directions break the shape the set key is for.* That consumer's failing test
     (fs-plattform MR 6936, `vouchereForToNasjonaleIderGirKompetanseoppnaelserForBegge`) posts
     `{__typename, internalGraphitronVoucher}` and nothing else, issued by opptak's dedicated type that
     withholds `nasjonalId`, and it matches B's minted resolvable voucher key (`graphitron_minted_key`,
     role `ACCEPT`). Dropping B's voucher key, the voucher no longer being a key and retiring
     withholding together leave that representation matching no alternative, and the spec plans no
     migration for a first-form consumer.
   * *The narrowing is entangled with the lookup part.* If the lookup part goes, the refusal it
     narrows goes with it, so ruling one before the other risks building the narrowing twice.

   Satisfied when this item is the set key and the cap, with the narrowing only if it is ruled and
   stands independent of the lookup-part question, and the `@requires` iteration (with the narrowing if
   it goes there, the retirement of the first form and the first-form consumer's migration) is filed as
   its own Backlog item depending on this one, carrying its open questions and the router proof. If Alf
   or Filip want it kept here instead, the open forks are ruled and the plan gains *Implementation*,
   *Tests* and *Retired vocabulary* entries for it.

   *Author (2026-11-23):* split as asked. R1207 (Backlog, depends on R1199) carries the guard, the
   narrowing with its open ruling, the router proof, and a section on the first form's consumer and its
   migration. *The per-entity voucher* here shows the second-field form, valid today, and the Goal's
   "usable once ... narrowed" is gone.

4. **The per-entity execution test is conditional on the narrowing (question 2).** "An execution test
   of the per-entity shape" sits inside the "*Pipeline tier*, if the refusal narrows" bullet, so it
   leaves the plan if the narrowing does. It is the shape the item exists for, and per finding 3 it
   works without the narrowing. Satisfied when it is an unconditional execution-tier test:
   representations carrying only a voucher, for distinct vouched-for values, each answering its own
   rows on one lease.

   *Author (2026-11-23):* it is an unconditional execution-tier bullet now, and a near miss in the
   defect's fact document pins the second-field form as accepted.

Not blocking:

* `load<Type>` is also reached from `Query.node` and `Query.nodes` through `load` and `loadKeyed`, and
  the pending load the voucher arm answers relies on `fetchEntities` closing the set. A node id's
  representation never carries the voucher field, so the path is unreachable; the dispatch bullet could
  say so, so the implementer adds no close there.

  *Author (2026-11-23):* the dispatch bullet says so.
* *How it works* point 3: when the first `_entities` field presents no voucher, say whether the set
  closes empty and fails every voucher in a second aliased field.

  *Author (2026-11-23):* it stays open; point 3 now closes the set at the first field that admitted a
  voucher.
