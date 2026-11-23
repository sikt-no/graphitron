---
id: R1199
title: "An _entities lookup with vouchers for several values resolves on one connection"
status: Spec
bucket: feature
priority: 3
theme: service
depends-on: [signed-reference-entities-across-subgraphs]
created: 2026-11-18
last-updated: 2026-11-23
---

# An _entities lookup with vouchers for several values resolves on one connection

## Goal

An accepting subgraph answers a router's `_entities` call whose vouchers carry several vouched-for values
(a list of cases with different applicants, say) with every entity it can resolve, still taking at most
one vouched connection from the pool. How a lookup past the limit fails is settled in R1119's slice 8g:
each representation past it fails alone and the rest resolve. This item is about raising the limit
itself, so a list of several owners resolves whole.

The same limit blocks a second shape, reported in
[GitHub issue 584](https://github.com/sikt-no/graphitron/issues/584): a voucher per entity, where the
vouched-for value is the entity's own key (a `Sak`'s id, with B's policies testing `sak_id`). There, a list
of N rows always presents N values. Raising the limit makes that shape useful. The shape also needs one
refusal lifted, `VOUCHER_VOUCHED_FOR_IN_KEY`, so that the vouched-for fields may be B's key (see
*The per-entity voucher*).

## Why

A federation router does not send one `_entities` call per parent: it batches every representation bound
for a subgraph from the whole list into one call. Under R1119's one-vouched-for-value-per-operation rule
(decided 2026-11-12) a list whose rows belong to different owners therefore always crosses the limit,
which makes vouchers unusable for the list case they exist for. Observed in kreg, under FS's `sakerV2`
with three cases, each with a different `soker`:

```
VouchedValueLimitExceededException: The request presents vouchers for more than one vouched-for value;
a request may carry vouchers for one
    at ...OwnedConnections.admitVouched(OwnedConnections.java:241)
    at ...EntityFetcherDispatch.batchKompetanseoppnaelserForSokerAlt0(EntityFetcherDispatch.java:351)
```

The limit throws a request rejection. kreg does not map it in `rejectedRequest`, so
`GraphqlHttpHandler` logs it as uncaught and answers a redacted `500`, and the router reports
`HTTP fetch failed from 'kreg': 500` with every `vgsKompetanseoppnaelser` `null`. Mapping it to `403`
would not help: the limit is documented as not being a denial, and the router would still null all three.

How it fails is the most important part (Alf, 2026-11-18). Keeping an `_entities` lookup to one
connection from the pool is wanted; failing the whole lookup to keep it there is not.

## Options for raising the limit

The floor, failing per representation, moved into R1119 as slice 8g (Alf, 2026-11-22) and is not this
item's. These two build on it and are compatible.

2. *One connection mounted for every value.* Every voucher of one `_entities` call is verified before
   any connection is taken, so the whole set of vouched-for values is known up front. A `ConnectionKey`
   carrying that set lets the consumer's source mount one connection that reads the superset, and one
   SELECT resolves every entity. The limit then becomes a cap on the set's size, a safety bound on the
   policy and array size rather than "one", configurable with a generous default, enforced per representation as in R1119's slice 8g.
3. *A connection per value.* Each value keeps a connection of its own, as the one-value rule's
   reasoning assumed. Since slices 8e.1 and 8e.2 a child `@service`, a split query and a polymorphic
   field below a vouched entity read later, on the key the entity hands down, so a value's connection is
   not done when the entity's SELECT returns: it is held until that value's subtree is read. This keeps
   per-value isolation at the cost of one leased connection per value for the operation, the cost the
   limit exists to bound, and so needs R1192's release when a value's reads finish.

The recommendation so far is 2, with 3 as the fallback for a consumer that needs per-entity isolation.

## Shape of the set key (option 2)

* The key carries a set of vouched-for rows, `Set<VouchedFor>` with `VouchedFor` a generated record of
  the vouched-for fields, never an array per field. With a shape of (org, person), per-field arrays
  `{o1, o2}` and `{p1, p2}` would let a policy match o1 with p2, a pair no voucher granted. One owner is
  a set of one: one shape, no single-value mode beside it.
* The consumer's connection source sets the whole set on the connection (an array, or an unnested array
  of rows for several fields), and its RLS policies test membership
  (`person_id = ANY(current_setting('app.vouched')::int[])`) rather than equality. Both are breaking for
  an accepting consumer; the voucher how-to's source and policies are rewritten to match. Nothing changes
  in the SDL or the generated SQL.
* What it gives up: a row of one entity's subtree is read on a connection that can also see the other
  vouched owners' rows, so a join that strays across owners is no longer caught by the database. It
  grants the caller nothing new, since A vouched every value to the same caller in the same request (the
  2026-11-12 reasoning for sharing a connection across operations), but it is a loss of precision the
  how-to states.
* Vouchers sharing one value no longer share a connection across operations in practice, since a set
  key rarely repeats between requests.

## The per-entity voucher (GitHub issue 584)

A consumer has an event-log subgraph B, whose access should follow A's row-level security on `Sak`. B
stores events keyed by the sak's id. The wanted supergraph shape is a list of saker, each with its
events below it (`hendelser: [Sakshendelse]`, as `@asConnection` eventually). The timeline is testable
by 2026-12-10 and in production by 2027-02-01; otherwise the consumer falls back to replicating the data.

*What it needs from this item.* The vouched-for value is the sak id, so every row in the list is a
distinct value, and the router batches them into one `_entities` call. Under the one-value limit, only
the first sak resolves. Option 2 settles it: one connection whose session holds the set of sak ids, B's
policy `sak_id = ANY(...)`, and one SELECT. Option 3 would lease one connection per row, which grows with
the list. So this case favours option 2, and the cap's default must be sized to a list or page length,
not to "a few owners"; the how-to says so. The precision option 2 gives up is smallest here, since each
value is one entity.

*The refusal it lifts.* `VOUCHER_VOUCHED_FOR_IN_KEY` refuses a vouched-for field that is also a field of
the key the voucher decodes to, "so the two parts never share a value". That rule is R1119's slice
decision, not one of its rulings. The proposed framing, which does not conflict with the 2026-11-16 ruling
(the vouched-for values are the access decision, the lookup part navigation only): when the vouched-for
fields are exactly B's authored key, the lookup section is empty, and the voucher decodes onto that key
from the vouched-for values. `@issue` with no absorbed fields beyond the vouched-for ones is then legal,
and the codec's check "the lookup section names exactly B's key fields" becomes "the lookup section is
empty and the vouched-for fields are the key". A partial overlap stays refused. *Needs a ruling from Alf
or Filip.*

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
  refusals, and Graphitron has no way today for children to bind to an entity's values rather than its
  row. If the `sak` table above is not acceptable to the consumer, it becomes its own item, depending on
  this one.
* `@asConnection` below an accepting type is R1119's slice 8e.3; a plain list is the first target.
* When the `internalGraphitronVoucher` rename reaches a release candidate is a release question.

## Smaller items from the same trace

* A request rejection that a consumer does not map reaches the router as an uncaught `500`. Consider a
  default answer for the limit (if any request-level form of it survives) that names the cause, and say
  in the how-to which rejections a consumer must map.
* Fields below a vouched entity with fetchers of their own read on the handed-down key (slices 8e.1 and
  8e.2, connection-typed fields to follow in 8e.3), so a vouched lease is held to the operation's end.
  Releasing it earlier, under option 3 or not, is R1192's per-worker release and should be decided with
  it.
