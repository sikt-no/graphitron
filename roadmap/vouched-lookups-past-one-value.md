---
id: R1199
title: "An _entities lookup with vouchers for several values resolves on one connection"
status: Spec
bucket: feature
priority: 3
theme: service
depends-on: []
created: 2026-11-18
last-updated: 2026-11-23
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
  of N rows always presents N values. Usable once the limit is a cap sized to a list and one refusal,
  `VOUCHER_VOUCHED_FOR_IN_KEY`, is narrowed (see *The per-entity voucher*).

What a consumer sees: its connection source reads a set off the `ConnectionKey` instead of one value
per field, and its policies test `= ANY(...)` instead of `=`. Both are breaking for an accepting
consumer. Nothing changes in the SDL either side writes or in the SQL Graphitron generates.

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
3. *The set closes when the first `_entities` field registers.* Admission is per operation. A second,
   aliased `_entities` field in the same operation sees a closed set: its representations whose value is
   in the set resolve on the same connection, and the rest fail per representation as past the limit.
   The reason is that the key is a value (Alf, 2026-11-14): representations already registered under the
   operation's vouched key cannot move to another, so the key cannot grow after they are. A router sends
   one `_entities` field per fetch, so this bounds an unusual operation, not a path the list case takes.
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

The limit becomes a bound on the set's size, a safety bound on the session setting and the policy's
array rather than "one". It is a runtime setting, not a store fact: nothing in the SDL or the catalog
states it and no emitter branches on it. It is application-wide, taken by the accepting runtime
(`Graphitron.runtime(...)`, beside the dialect and jOOQ `Settings`, which are application-wide already),
and enforced per representation as above. Not on `VoucherKeys`: that is the key-material interface the
how-to invites consumers to implement, so a cap given through its `accepting(...)` factory would not exist
for a consumer's own implementation. No written ruling covers where it goes; the runtime is the
proposal, open for Alf and Filip at review. Its default is sized to a list or a page, not to a few owners,
since the per-entity voucher presents one value per row. The proposal is 1000, a router batch of a long
list. A policy's `= ANY` over a session setting is evaluated per row and the planner cannot treat the
array as a constant, so the implementer measures a thousand-value set against the RLS fixture before the
default is fixed, and lowers it if the per-row cost shows. The how-to says
why the bound exists and what a consumer that pages larger raises it to. A cap below 1 is refused at
runtime construction.

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
  isolation puts the entity's key into `vouchesFor:` (the per-entity voucher below), which makes the
  loss nil, since each value is then one entity.
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

*The refusal it narrows.* `VOUCHER_VOUCHED_FOR_IN_KEY` refuses a vouched-for field that is also a field
of the key the voucher decodes to, "so the two parts never share a value". That was R1119's slice
decision, not one of its rulings. The proposed framing, consistent with the 2026-11-16 ruling (the
vouched-for values are the access decision, the lookup part navigation only): when the vouched-for fields
are exactly B's authored key, the lookup section is empty and the voucher decodes onto that key from the
vouched-for values. A changes nothing: an `@issue` absorbing only its vouched-for fields is not refused
today, and it signs an empty lookup section. B's check "the lookup section names exactly B's key fields"
becomes "the lookup section names exactly the key fields the vouched-for section does not supply", which
is empty here. Refused still:

* *A partial overlap*, some key fields vouched for and some looked up: it would split one key between an
  access decision and navigation.
* *A superset*, the vouched-for fields being the whole key and more: the proposal is to accept it, since
  the key is still supplied whole by one section and the extra vouched-for fields only narrow the grant.
  The ruling should say which.

**Needs a ruling from Alf or Filip before Ready.**

*Where the split lives.* Narrowed, the defect is a set comparison, and the voucher arm needs the same
split in Java (which key fields the codec expects in the lookup section, which columns are filled from
vouched-for values). Derived once in the store instead (`fact-model.adoc`, "a derivation gets a relation
as soon as a second reader asks it"): a relation whose grain is *one field of the key an `@accept`
decodes to, with the voucher section that supplies it*, written by the stage that writes
`graphitron_accept_key` in its anchoring step, hanging off `graphitron_accept_key` by a cascading
foreign key so its sweep follows. The defect rule reads it ("the key is supplied from both sections"),
and `EntityKeyQuery` reads the section per field for the dispatch.

With that, the consumer's shape uses existing vocabulary only, provided B owns a `sak(sak_id)` table
that its events reference:

```graphql
# Subgraph A: the issuer. Sak itself cannot issue (it is a node, keyed, and a root field returns it),
# so the voucher sits on a type reached through a field on Sak.
type Sak @table(name: "sak") @key(fields: "id") {
  id: ID! @nodeId
  hendelseLogg: SakHendelseLogg      # same row, read as the issuing type
}
type SakHendelseLogg @table(name: "sak") @issue(scope: "sakshendelser", ttl: "PT5M", vouchesFor: "sakId") {
  sakId: ID! @field(name: "sak_id")
}

# Subgraph B: the vouched-for field is the key; the lookup part is empty.
type SakHendelseLogg @table(name: "sak") @key(fields: "sakId")
    @accept(scope: "sakshendelser", vouchesFor: "sakId") {
  sakId: ID! @inaccessible
  hendelser: [Sakshendelse!]! @reference(path: [{key: "sakshendelse_sak_fkey"}])
}
```

*Outside this item.*

* The extra hop (`Sak.hendelseLogg.hendelser` rather than `Sak.hendelser`) is the voucher itself: the
  router hands B a voucher only by jumping on a type keyed by it, and `Sak` cannot be that type
  without publishing a key that bypasses the voucher (`VOUCHER_ISSUE_ON_KEYED_TYPE`,
  `VOUCHER_ISSUE_ON_NODE`, `VOUCHER_ISSUE_ON_ROOT_TYPE`). Removing the hop means reopening those
  refusals, a question for Alf and Filip that this item does not take.
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
  and its cap; `admitVouched(ConnectionKey)` becomes `admitVouched(VouchedFor)`, answering whether the
  value is in the set or was added to it, still the one site that decides. Closing the set answers the
  operation's vouched key. `entryFor`'s invariant becomes "a key with a vouched-for part is the
  operation's vouched key", still an `IllegalStateException`.
* *The dispatch* (`EntityFetcherDispatchClassGenerator`, `HandleMethodBody`): the voucher arm in the
  alternative cascade verifies, coerces and admits as now, and answers a pending load or the past-cap
  failure instead of registering; `fetchEntities` closes the set after its loop and registers the pending
  loads. Verification, coercion, the rejection path and the audit lines move, they are not rewritten. The
  multi-tenant synchronous dispatch is untouched: `@accept` is refused in a tenant-column build.
* *The runtime* (`VoucherRuntime`, the generated runtime factory): the cap on `Graphitron.runtime(...)`
  in an accepting build, its default, its refusal below 1, and the reworded `LIMIT_MESSAGE`.
* *The refusal*, if ruled as proposed: the key-field-section relation above, `graphitron-model.sql`'s
  `VOUCHER_VOUCHED_FOR_IN_KEY` rule rewritten over it, its `graphitron_defect_type` row and guidance
  fragment `defects/VOUCHER_VOUCHED_FOR_IN_KEY.adoc` restated (`defects-as-facts.adoc`, "Adding a
  defect"), and B's codec check reading the lookup fields the relation states.
* *Docs*: `voucher-handover.adoc` (step 4's source and policy, step 5's limit bullet, the per-entity
  voucher as a variant, the precision note), `accept.adoc` (the limit paragraph, the audit outcome
  name, the narrowed refusal's row), and `docs/architecture/reference/runtime-extension-points.adoc`
  (the key's parts restated as above, the limit sentence). The same page's one-request-rejection
  paragraph still lists "a second vouched-for value" among the request rejections, stale since slice 8g;
  that is fixed on its own as a `docs` commit, not carried here.
* *Commit*: `feat(R1199)!` with a `BREAKING-CHANGE:` footer naming the key's accessor and the policies'
  membership test.

## Tests

* *Unit tier* (emitter harness, `testing.adoc` "Every emitted unit runs at the unit tier"): the generated
  `ConnectionKey` for one and for two vouched-for fields; admission in representation order, a repeated
  value admitted again, the value past the cap refused, a cap of one behaving as slice 8g does; a forged
  voucher after admitted ones still rejecting the request; the second-field case seeing a closed set;
  `VouchedFor`'s and the key's `toString` naming no value.
  `VoucherDispatchTest` and `RequestRejectionCarrierTest` move from the one-value key to the set.
* *Execution tier* (sakila's federated fixture, `VoucherAcceptExecutionTest`, with the fixture's
  source and policies moved to the set):
  * `vouchersForTwoOwnersInOneLookupResolveTheFirstOwnersAndFailEachOfTheSecondsAtItsIndex` becomes:
    representations for two owners in one call resolve every entity of both, on one lease, acquired
    once.
  * A cap set to 2 with three owners: the third owner's representations each fail at their own index,
    naming no value, and take no connection.
  * A split query and a `@service` below entities of two owners read on the one lease, in one batch.
  * A plain-key representation beside vouchers still reads on the request's own connection.
* *Pipeline tier*, if the refusal narrows: a fact document under `facts/scenarios/` for the exact-key
  shape accepted (A changes nothing, so B's side is what it pins), with the key-field-section rows it
  holds; the partial overlap still refused, beside `facts/defects/voucher-accept-keys.graphqls`; the
  superset as the ruling decides.
  An execution test of the per-entity shape: a list of sak-shaped rows, one voucher each, resolving on
  one lease.

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
* *Failing the whole lookup past the limit.* What shipped before slice 8g; rejected by Alf (2026-11-18).

## Smaller items from the same trace

* A request rejection a consumer does not map reaches the router as an uncaught `500`. The limit no
  longer is one, but a failed voucher check and a failed acquisition still are; the how-to should say
  which rejections a consumer must map in `rejectedRequest`, and whether a default answer that names the
  cause belongs in graphitron-jakarta-rest. Its own item if wanted.
* Fields below a vouched entity with fetchers of their own hold the vouched lease to the operation's end.
  Releasing it earlier is R1192's per-worker release, unchanged by this item.
