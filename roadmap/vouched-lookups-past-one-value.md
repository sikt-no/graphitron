---
id: R1199
title: "An _entities lookup with vouchers for several values resolves on one connection, and a limit fails only the entities past it"
status: Backlog
bucket: feature
priority: 3
theme: service
depends-on: [signed-reference-entities-across-subgraphs]
created: 2026-11-18
last-updated: 2026-11-18
---

# An _entities lookup with vouchers for several values resolves on one connection, and a limit fails only the entities past it

## Goal

An accepting subgraph answers a router's `_entities` call whose vouchers carry several vouched-for values
(a list of cases with different applicants, say) with every entity it can resolve, still taking at most
one vouched connection from the pool. Where a limit does stop a representation, that representation gets
its own error at its own path and the rest resolve, so the limit never reads as a failed fetch of the
whole lookup.

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

## Options for how it fails

These are compatible; the first is the floor whatever else lands.

1. *Fail per representation.* The limit stops admitting values but does not throw a request rejection.
   Representations whose value is admitted resolve as today; each one past the limit answers `null`
   with an error at its own `_entities` index naming the limit, which the router places at the parent's
   path. Which values are admitted must not depend on loader dispatch order: admit in representation
   order. A failed voucher check stays a request rejection, since that is a forged or expired token, not
   a capacity limit.
2. *One connection mounted for every value.* Every voucher of one `_entities` call is verified before
   any connection is taken, so the whole set of vouched-for values is known up front. A `ConnectionKey`
   carrying that set lets the consumer's source mount one connection that reads the superset, and one
   SELECT resolves every entity. The limit then becomes a cap on the set's size, a safety bound on the
   policy and array size rather than "one", configurable with a generous default, enforced as in 1.
3. *Several values in turn on one lease.* Since `VOUCHER_ACCEPT_NOT_GENERATED` refuses a field below an
   accepting type that has a fetcher of its own, everything vouched is read in the entity's own SELECT,
   and a value's connection is done when that SELECT returns. Values could be read one after another,
   releasing (or re-mounting) between them, keeping per-value isolation at the cost of a round trip each.
   This stops holding once nested fetchers read on the vouched connection.

The recommendation so far is 1 now, then 2, with 3 as the fallback for a consumer that needs per-entity
isolation.

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

## Smaller items from the same trace

* A request rejection that a consumer does not map reaches the router as an uncaught `500`. Consider a
  default answer for the limit (if any request-level form of it survives) that names the cause, and say
  in the how-to which rejections a consumer must map.
* When nested fetchers are allowed on the vouched connection, the release point moves from the entity's
  SELECT back to the operation's end; that change and R1192's per-worker release should be decided
  together.
