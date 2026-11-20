---
id: R1119
title: "Signed voucher handover: one subgraph vouches, another grants through RLS"
status: In Progress
bucket: feature
priority: 3
theme: service
depends-on: []
created: 2026-10-28
last-updated: 2026-11-18
---

# Signed voucher handover: one subgraph vouches, another grants through RLS

## Goal

One subgraph can vouch for an access decision to another subgraph, and the other subgraph's database
turns that into data access. Neither side writes a resolver or parses a token.

**The problem.** Two federated subgraphs, A and B, serve one user. A knows the application and the
caseworker handling it, so A can decide that this caseworker may see an applicant's school results. B
owns the results, and B's database guards them with row-level security (RLS: Postgres policies that decide,
per row, what a connection may read). B's policies only see the caseworker's own identity, which grants
nothing on the applicant's results. So when a router follows a reference from A into B, B's RLS hides the
rows and the client gets `null`, with no error.

**The outcome.** A marks a type `@issue`. Graphitron then gives the type a voucher field and attaches a
signed *voucher* to each row that A's own rules let the user read. The router carries the voucher to B.
B marks the matching type `@accept`, and Graphitron gives it the same field.
Graphitron checks the voucher there and runs the read, and every read below it at any depth, on a
separate database connection, set up for the value A vouched for (the owner, say). What that connection
may read is what B's policies unlock for that value, and only the vouched entity and the fields below it
read on it. A voucher that fails any check fails the whole request before any SQL on the covered tables
runs: a bad voucher means tampering or a deployment fault, so nothing in that request is answered.

A voucher carries two things that do different jobs. The *vouched-for fields* are the access decision:
they decide which connection the entity reads on, and B's policies test them. The *lookup part* is
navigation: it lets B pick up the row where A left off, and grants nothing.

**The connection contract.** A connection set up for a vouched-for value has to come from the consumer,
who owns the pools and knows how to mount what the policies read, so this item also replaces how every
build gets its connections (it absorbs R1185). The consumer hands each request a `ConnectionSource`, a
function from a `ConnectionKey` to a `Lease`: a mounted connection, the session handle where one is
configured, and how to release it. Graphitron calls it when a field first needs a connection for a key,
once per distinct key per request, keeps the lease, uses it wherever that key applies, and releases every
lease exactly once. Tenancy is not a mode: a tenant is one part of the key and the vouched-for values
another, and what an empty part means is the source's decision. The tenant map, the default source and
the session mount and unmount of today's runtime go.

The consumer supplies the signing and verifying keys, the RLS policies, and the `ConnectionSource`: for a
key carrying vouched-for values, a connection whose session sets them for the policies.

### Terms

| Term | Meaning |
|---|---|
| *Voucher* | A signed token Graphitron computes for one row in A and verifies in B. It is Graphitron's own format, decoded the way a node id is. |
| *Issuing type* | The type in A marked `@issue`. |
| *Accepting type* | The type in B marked `@accept`. |
| *Voucher field* | `internalGraphitronVoucher`, the field Graphitron mints on an issuing or accepting type (slice 8f): `String!` on A, which signs one for every row, and `String` on B, which never computes one. Its name is fixed, so A and B cannot disagree on it; it is `@inaccessible` and the minted key's one field. |
| *Absorbed fields* | Every field the author wrote on the issuing type. Their values are sealed inside the voucher, and A does not publish them. |
| *Vouched-for fields* | The absorbed fields named by `vouchesFor:` on both markers. They say whose rows are vouched for, and they are the access decision. In the first client's case this is the owner's id number, a different person from the user the request is for. |
| *Lookup part* | The other absorbed fields: the key of the vouched row in B. Navigation only. |
| *Scope* | A name both markers agree on, so a voucher issued for one purpose is not accepted for another. |
| *Vouch id* | A random id shared by the vouchers one operation issues under one scope. An audit field only: Graphitron logs it at issue and on every redemption or rejection. |
| *Connection key* | The generated record a connection is acquired for: one `Optional` per part the schema has, the tenant where it has a tenant column and each vouched-for field where it accepts vouchers, and no part at all in a build with neither. Equal keys share a connection within a request. |
| *Connection source* | The consumer's `ConnectionSource`, given with each request: `Lease acquire(ConnectionKey key)`. Built when the request is, it captures what the consumer needs of the request and takes nothing until Graphitron calls it. |
| *Lease* | What `acquire` returns: the mounted `connection()`, the session `handle()` where `<handle>` is configured, and `release()`, which Graphitron calls exactly once. |
| *Vouched connection* | The lease B's connection source returns for a key carrying vouched-for values, one per value per request. What it may read is what B's policies unlock for that value. |

### Example

The first client is an admissions service. In the SDL, `Soknad` is an application, `VgsResultat` an upper
secondary school result, and `eierId` the id of the person who owns the result (the applicant).

```graphql
# Subgraph A: the issuer. VgsResultat is A's own table of references to results, written the way B's key
# looks: `id` is B's key field, read here from A's `vgs_resultat_id`, and `eierId` is the vouched-for field. `@issue`
# absorbs every field written on the type into the voucher, mints `internalGraphitronVoucher`, and A publishes `internalGraphitronVoucher` alone.
# No service, no token code, no voucher field and no `@key` written.
type Soknad @table(name: "soknad") @key(fields: "id") {
  id: ID! @nodeId
  vgsResultater: [VgsResultat!]! @reference(path: [{key: "soknad_vgs_resultat_soknad_fkey"}])
}
type VgsResultat @table(name: "soknad_vgs_resultat")
    @issue(scope: "vgs-resultater", ttl: "PT5M", vouchesFor: "eierId") {
  id: ID! @field(name: "vgs_resultat_id")
  eierId: String! @field(name: "eier_id")
}

# Subgraph B: the owner. The real, table-bound entity. `@accept` mints `internalGraphitronVoucher`, the proof field: not a
# column, not selected. `eierId` is the vouched-for field the voucher's value parses into, kept from clients.
type VgsResultat @table(name: "vgs_resultat") @key(fields: "id")
    @accept(scope: "vgs-resultater", vouchesFor: "eierId") {
  id: ID!
  eierId: String! @inaccessible
  fag: String!
  karakter: Int!
  detaljer: [Fagdetalj!]!          # nested table-bound children, read under the same vouched connection
}
```

The absorbed fields are named and typed like B's key fields and vouched-for fields, because that is what they
are. Each is read as a component of the voucher, not as a field: its `@field` names the column and its
type the scalar, and it never appears in A's served SDL or runtime schema. The voucher carries each value
under the field's name, serialized by the field's scalar, exactly as a plain representation of B's key
would carry it, and B parses it as it parses any representation. So the values must agree as for any
federation key: A's `id` reads the result id from `vgs_resultat_id`, B's `id` reads its own `id` column,
both as an `ID`. A client that wants the result's `id` gets it from B, through the voucher, like `fag`.

### Where it stands

Slices 1 to 8f are on trunk: A issues vouchers end to end, B verifies them and reads the vouched
entity and every field below it on the vouched connection, except a connection-typed field, which
`VOUCHER_ACCEPT_NOT_GENERATED` still refuses below an accepting type; and both markers sit on the type,
with the voucher field minted (*Rulings*, 2026-11-18). What remains is 8e.3, connection-typed fields,
which waits on R876's node for their fetchers. See *Slices*.

The *Implementation* text below the *Rulings* was written for the field markers and describes some
mechanisms in that form (the membership's `issuing_field`, the claim read off a field-level
application, the minted key's field set read off the marked field's name); *Slice 8f* says what each
became.

## First-client docs draft (for `federation-keys.adoc` and the security guide)

"To let another subgraph read data your user's token does not cover, write the row type the way the
other subgraph's key looks, each key field reading its value from your own column, add the field whose
rows these are (the owner, say), and mark the type `@issue(..., vouchesFor: "owner")`. Graphitron gives
the type a voucher field, and the voucher absorbs every field you wrote: they are sealed inside it and
your subgraph publishes the voucher alone, so a router can reach the other subgraph's data only through
it. `vouchesFor` names what you vouch
for, whose rows these are; the other fields are the row's key, which only tells the other subgraph which
row to read. Name and type them exactly as the other subgraph's key fields and vouched-for fields: your
subgraph cannot check that, and a mismatch shows up as every voucher being rejected. Graphitron signs a
voucher for each row your own rules let the user see. In the subgraph that owns the data, add the
vouched-for field (it may be `@inaccessible`) and mark the type `@accept(..., vouchesFor: "owner")`.
Graphitron checks the voucher and asks your connection source for a connection for a key carrying the
vouched-for value: `key.owner()` is present, and your source returns a connection whose session sets it
where your policies read it. Your policies decide what that value unlocks. That entity and everything below it read through that connection,
nothing outside those entities uses it, and it is released when the request ends." If this does not
read simply once the spellings are chosen, the design is wrong.

## How it works

```
 A (issuer)                    router                         B (owner)
 ----------                    ------                         ---------
 read rows under A's rules
 sign one voucher per row  --> fetch `voucher` from A
                               send _entities {voucher} -->   verify every voucher (fail request on any)
                                                              group by vouched-for value
                                                              one vouched connection per group:
                                                                source.acquire(key) reads it
                                                              SELECT the entity, and every field
                                                                below it, on that connection
                                                              release at operation end
```

1. *Issue (A).* A's field is an ordinary read below a parent. The `voucher` field of each returned row is
   computed, not a column, and only when selected, which a router does only when it plans the jump to B.
   For each row Graphitron builds the token (version, vouch id, scope, expiry, key id, and each
   absorbed field's name and wire value) and signs it, together with the entity type name, using the
   runtime's signing key. The vouch id is a random 128-bit value minted once per operation and scope,
   logged by Graphitron as the audit handle; the expiry is the operation's start plus `ttl`. The
   decision is the read itself: a row A's rules hide is never returned and never signed. Under a mutation payload the rows are read and signed inside the
   mutation field's transaction, and a field that rolls back is `null`, so a voucher for rows that are
   gone never reaches the router.
2. *Carry.* The router fetches `voucher` from A, the only field A publishes on the type, and sends
   `_entities` representations `{voucher}` to B. The field is `@inaccessible`, so clients never see it.
3. *Verify (B).* The dispatcher resolves the representations through the voucher key, the way it resolves
   a node-id key: decode the opaque value into the authored key's columns, then the ordinary SELECT.
   Graphitron checks every voucher in the request before it resolves any type, so the connection source
   is asked for nothing until all of them have passed:
   * the signature, over the token and the representation's `__typename`, against the runtime's
     verification keys;
   * the expiry, with a fixed tolerance for clock skew between A and B;
   * the scope, against `@accept(scope:)`;
   * that the signed vouched-for section names exactly B's `@accept(vouchesFor:)` fields;
   * that the lookup section carries exactly the fields of B's authored key.

   Each voucher is verified on its own. The whole token is signed, the lookup part included, but the
   lookup part is inside the token for federation's sake (A withholds the key fields so the router can
   only jump on `voucher`), not as a grant: any row B's policies admit for the vouched-for value is
   readable on that connection anyway. One failure fails the request with a request-scoped
   `VoucherRejectedException`, thrown out of `GraphQL.execute` the way a failed acquisition's rejection
   is. Representations of the plain key, from elsewhere in the graph, are not part of this check and
   resolve on the request's own connection, under the user's own RLS. Graphitron logs the vouch id, scope and
   outcome of every redemption and rejection, never the token or its values.
4. *Route.* The verified vouchers group by their connection key: the vouched-for values, typed as B's
   fields' columns, in the key's parts. Vouchers carrying the same value share one connection, whichever
   operation or scope issued them. For each group the per-operation carrier asks the request's
   `ConnectionSource` for a lease for that `ConnectionKey`, once, and keeps it. The source reads the
   vouched-for values off the key and returns a connection whose session sets them where the policies read
   them. It refuses by throwing, which rejects the request as a whole. What it does on release, clearing
   and returning the connection, is its own.
5. *Read below.* The group's SELECT runs on the vouched connection. A field below the entity is read in
   one of two ways:
   * *In the entity's SELECT.* Fields projected into it, including reference children nested as
     `MULTISET`s, are part of the same statement, so they read on the vouched connection with nothing
     more to do.
   * *By a fetcher of its own.* A batched field, a service or a connection-typed field acquires its own
     connection. Each resolved entity row is returned as a `DataFetcherResult` whose `localContext`
     carries the connection key, graphql-java hands that value to every field below, and such a fetcher
     acquires by it: each loads in one batch per connection key, and a batch never mixes two. A field
     outside the subtree uses the request's own connection, as tenants already do.

   A `$session` binding below reads the handle the vouched connection's lease carries, since `$session`
   is read through the statement's own connection.
6. *Release.* At operation end Graphitron releases every lease the request acquired, the vouched ones
   with the rest, exactly once each.

### When a connection is taken

Connections are taken lazily, one per connection key per operation, and reused until the operation ends.
For one `_entities` request on B:

```
beginExecuteOperation    the carrier (OwnedConnections) is created; nothing acquired yet
fetchEntities            every voucher verified; a failure ends the request here, nothing acquired
resolveByReps            for each type, for each group on its connection key:
  voucher group            the key with the vouched-for values: first use asks the source for a lease
  any other group          the key with no parts: first use asks the source for a lease
  entity SELECT            runs on that group's lease, projected children included
fields with a fetcher    use the lease of the key their entity row handed down (slice 8e)
beginExecution ends      every lease is released, exactly once, on every path
```

An operation that presents only vouchers never takes the request's own connection unless a field outside
the vouched subtree asks for it.

### One connection per vouched-for value

An operation takes one vouched connection, for one vouched-for value. Vouchers carrying it, from a list
of results or several parents' lists, share that connection. An operation whose vouchers carry more than
one vouched-for value fails with an error that names the limit, not `null`, so the limit is not mistaken
for a denial. The limit lives in one place so it can be raised later.

Every entity of the group reads through a connection mounted for that value, so a subtree is scoped to
its entities by the graph it was reached through, and its reads to the value by RLS. Sharing the
connection across operations and scopes on A grants nothing new: what it may read is decided by the
value and B's policies on it, and every voucher on it was signed for that value.

### What B's policies test

The grant is everything B's policies unlock for the vouched-for value; the lookup part is not part of
it. B needs no notion of application or subject: the policies test the value, for example
`eier_id = current_setting('app.vouched_owner')` on the result table and a join from each child to its
result row, where the connection source's session wrote the setting from the key.

The first client vouches for documents and their owner: A's issuing type absorbs `id` and `eierId` and
names the owner as vouched for (`@issue(scope: "dokumenter", ttl: "PT5M", vouchesFor: "eierId")`); B
names the same (`@accept(scope: "dokumenter", vouchesFor: "eierId")`), so `id` is the lookup part and
decodes onto B's authored `@key(fields: "id")`. Every voucher for one owner shares the owner's vouched
connection, and a policy on the owner admits that owner's documents on it. If a subtree below a document
leads to the owner's other documents, those are admitted too: that is the grant A made. A consumer that
wants a narrower grant puts more into `vouchesFor:` (a document id, say), at the cost of one connection
per value.

### What each side publishes, and why A withholds the absorbed fields

Both subgraphs' published shape is derived, not written. On a type marked `@issue` or `@accept`
Graphitron mints the field `internalGraphitronVoucher @inaccessible`, `String!` in A and `String` in B, and `@key(fields: "internalGraphitronVoucher")` (slice 8f;
until then the author writes the field, marks it, and the key names it). In A the key is
`resolvable: false` and is the type's only key; in B it is `resolvable: true` and sits beside the authored
key. Both are minted applications, the mechanism that gives a node type its `@key(fields: "id")`, so the
`@link` imports `@inaccessible` with no extra step, and A and B cannot disagree about the key shape.

A withholds the absorbed fields for two reasons:

* *A published key field would bypass the voucher.* A router plans a jump from A to B through any key of
  B whose fields A can supply, and any field A publishes on the type counts, whether or not it is in A's
  own `@key`; `@inaccessible` hides a field from clients, not from the planner. If A published `id`, B's
  authored `@key(fields: "id")` would carry the jump, B would resolve the entity under the user's own
  RLS, and the client would get the quiet `null` this item exists to remove.
* *Any other A-only field would break composition.* It would exist only in A, on a type no router can
  enter A for, so a query reaching the type through B that asked for it could not be planned.

With only `voucher` and its key published, the supergraph composes with the jump on `voucher` (row 4 of
the round-2 composition table under *Reviewer findings*). An issuing type is not a node: a node gets a
minted `@key(fields: "id")`, which on A would name an absorbed field. B's authored key is untouched, so
the type still resolves by plain `id` from anywhere else under the user's own RLS; a voucher only ever
adds access. This is federation only: `Query.node`, `@node` and the relay id know nothing about vouchers.

### Rulings this plan rests on

Decisions the user made on this item; each is stated where it applies below.

* The vouched-for fields are required on both markers, and both name them, B refusing a voucher whose
  signed vouched-for section differs (2026-11-12). A voucher without them would have no connection of its
  own to read on.
* One vouched-for value per operation, and vouchers sharing the value share a connection across
  operations and scopes on A (2026-11-12).
* The vouched-for fields are the access decision and the lookup part is navigation only (2026-11-16).
  The connection source needs only the vouched-for values, to set them on the connection; it reads them
  off the key it is asked for. No per-voucher detail reaches it: the covered lookup keys and a `Vouch`
  type are dropped.
* The connection contract (2026-11-16, in `docs/architecture/reference/runtime-extension-points.adoc`):
  the consumer hands each request a `ConnectionSource`, `Lease acquire(ConnectionKey key)`, built when the
  request is and capturing what it needs of it; the lease holds the mounted connection, the session
  handle and its release. Graphitron owns the lifecycle: it acquires when a field first needs a key's
  connection, once per distinct key per request, and releases every lease exactly once on every path.
  A throwing `acquire` rejects the request and nothing more is acquired; a throwing `release` is logged,
  the rest are released, and the first failure surfaces. Mount, unmount and which pool are the source's,
  inside `acquire` and `release`. This item absorbs R1185, which is discarded.
* Tenancy is not a mode (2026-11-16): a tenant is one part of the connection key, the vouched-for values
  another. `ConnectionKey` is a record with one `Optional` per part the schema has, generated in every
  build, with no part in a build that has neither; what an empty part means is the source's decision.
* Which tenants are valid, and whether a request may reach one, is the source's to decide (2026-11-16):
  it refuses a key by throwing the generated `KeyNotPermitted` from `acquire`, which answers as a refused
  tenant does today: an error located at the field, and `null` with no error in the entity and node
  dispatch, so a refusal reads like a row that does not exist and cannot be used to probe. Any other
  throw from `acquire` rejects the request. Graphitron's request tenant set, the factories' leading
  parameter in a `<tenantColumn>` build, goes, so the factories are the same in every build. A fan-out's
  domain is the source's too: in a build with a tenant column `ConnectionSource` declares `tenants()`,
  the tenants this request may span, and it is built with `ConnectionSource.of(acquire, tenants)`, so a
  source without a domain does not compile; in a build without one it stays a functional interface whose
  `tenants()` is a default that throws, never called since nothing fans out there. Graphitron fans out
  over the domain, one key and one lease per tenant, and a tenant the request may not see is simply not
  named (2026-11-16).
* One request rejection (2026-11-17): a generated sealed `RequestRejectedException` with arms for a
  failed acquisition (`AcquisitionFailedException`), a failed voucher check (`VoucherRejectedException`)
  and the one-vouched-value limit; the carrier's one slot, the error router's decline, the
  instrumentation's throw and the facade read that one type. The facade's `acquisitionFailure` becomes
  `requestRejection`, answering the source's own exception for a failed acquisition and the rejection
  itself otherwise; graphitron-jakarta-rest's `failedAcquisition` becomes `rejectedRequest`.
* No default tenant and no `@defaultTenant` (2026-11-17): `RequestTenants.withDefault` and the entity
  default tenant go with the request tenant set. Every read and write of a global table runs on the key
  whose tenant part is empty, whose pool the source picks, so `dslGlobal` folds into `dslDefault`.
  `@defaultTenant` is dropped, since its lookup binds a tenant value the request no longer names; a schema
  that still applies it is refused with a message naming the change.
* The dialect and jOOQ `Settings` a lease's connection is used with are the runtime's, application-wide,
  with an optional overload per lease (2026-11-16): today's per-source dialect and settings stay
  expressible without every lease naming them.
* Entities and nodes are read one way in every build without a tenant column (2026-11-16): DataLoaders
  per (type, alternative, connection key), every representation's key built from its alternative and
  empty unless a voucher fills it; the codec only decodes, the dispatcher coerces, builds the key and
  logs, and the one-vouched-value limit lives where the carrier takes connections. First round kept
  tight: a build that accepts vouchers has owned connections and no tenant column, and tenanted builds
  keep their dispatch until R876.
* Vouchers require Graphitron's owned connections (2026-11-16): a build that accepts vouchers has no
  escape hatch, as a tenant-routed build has none, since `_entities` must acquire the vouched
  connection from the request's source.
* The caller binding is dropped (2026-11-16): within one supergraph operation the router calls A and B
  for the same user, and the voucher never leaves the router, being `@inaccessible`. A voucher carries no
  caller and verification needs nothing of the request. What the binding guarded, a voucher captured from
  internal traffic and presented in another operation within its expiry, rests on the router's
  isolation, the scope, the type, the signature and the short expiry.
* The vouch id is an audit field that Graphitron logs; handing it to the consumer can be added later
  (2026-11-16). The verifier's check that vouchers sharing a vouch id agree is dropped.
* `_entities` does not change: it executes on the connection acquired for the vouched-for value. It is
  not a new producer in the store's connection facts (2026-11-16).
* Version 1 has no tenant pairing: `@accept` in a graph with a `<tenantColumn>` is refused (2026-11-16).
  A vouched key's tenant part is always empty, so the dispatch needs no tenant source for the voucher
  alternative. Pairing it with the authored key's tenant source can follow.
* `claims:` is renamed `vouchesFor:`, since "claims" already means the request's identity claims
  (2026-11-16).
* Either marker in a graph with no federation link is refused (2026-11-12).
* Federation knows keys, not `Node.id`: whether a key is the id contract is decided by how its field
  binds, and an authored key spelling `id` stands the minted node key down (2026-11-10, 2026-11-11).
* `EntityResolutionBuilder` dissolves with its readers rather than behind an adapter; its deletion moved
  to R876 (2026-11-11).
* The `@issue` field is the store's whole: the walk has no leaf for it (2026-11-13, in
  `docs/architecture/explanation/pipeline-overview.adoc`).
* The column-name match and the `VOUCHER` binding kind (2026-11-10, in
  `docs/architecture/explanation/fact-model.adoc`).
* The minted field is named `internalGraphitronVoucher` (2026-11-20): camelCase, so Apollo GraphOS
  schema linting (`FIELD_NAMES_SHOULD_BE_CAMEL_CASE`) passes the subgraph SDL it is published in, and
  prefixed so an author's own `voucher` field is not refused for colliding with it.
* The markers go on the type and Graphitron mints the voucher field (2026-11-18): `@issue` and `@accept`
  are applied to an object type, not to a field the author writes, and Graphitron mints the voucher
  field on both sides, with a fixed name and type, so A and B cannot disagree on them. Slice 8f.

## Implementation

* *Directives.* `@issue(scope:, ttl:, vouchesFor:)` on a table-bound type (A); it absorbs every field
  written on the type, and `vouchesFor:` names the absorbed fields that are vouched for, the rest being
  its lookup part. `@accept(scope:, key:, vouchesFor:)` on a table-bound, resolvable type (B), beside the
  `@key` it decodes onto: `key:` is the field set of the authored `@key` whose columns the voucher's lookup
  part decodes to, and may be omitted when the type has exactly one authored resolvable key;
  `vouchesFor:` is the field set the voucher's vouched-for values parse into, fields of B's type parsed by
  their scalars as key fields are (each must be a field of the type, and may be `@inaccessible` so the
  owner is not published to clients). `vouchesFor:` is required on both: a voucher without it would have
  no connection of its own to read on, and the request's own connection is the user's. The vouched-for
  values are the rows' owner, not the user the request is for. Both ends name them, so the issuer states what it vouches for and B refuses a voucher
  whose signed vouched-for section differs from its own `vouchesFor:`; B's policies decide what the
  values unlock. Graphitron defines both in `directives.graphqls`, as it does `@node`; consumers do not
  write the definitions.
* *Facts.* Capture writes one entry per application, keyed by the directive application as
  `graphitron_ast_node_id_entry` is: `graphitron_ast_issue_entry` (scope, ttl, the `vouchesFor:` field set
  as written) and `graphitron_ast_accept_entry` (scope, the `key:` field set as written or none, the
  `vouchesFor:` field set as written). The vouched-for fields are decided into rows, not read back off
  the strings: one relation with a row per vouched-for field of an application, keyed by the application
  and the field's position, referencing the field's element, which the codec, the `ConnectionKey`'s
  typing and the refusals
  read. Everything else is
  derived from these and from facts the store already holds: the voucher membership and components
  (below), the voucher binding, the minted keys and `@inaccessible`, the entity key alternatives, the
  rejections below as entry defects, and the build-shape questions the emitters ask (does this graph issue,
  does it accept).
* *Column names.* Which column of a table a written name answers to is stated once in the catalog family,
  per the column-name ruling in `docs/architecture/explanation/fact-model.adoc`, as `sql_column_spelling`:
  one row per column a folded name reaches on its table (by SQL name where any column carries it, else by
  jOOQ name), carrying which name matched and the reach, so a resolution is `reach = 1`, an ambiguity
  `reach > 1`, and an unresolved name no row.
  `graphitron_occurrence_column_rule` and the `COLUMN_UNRESOLVED` and ambiguous-column defect arms become
  joins onto it, with their fact documents row-identical; that is the gate for this step. The `graphitron`
  gatherer's `meta_gatherer_dependency` row on the catalog family covers the read.
* *Voucher membership and components, not withheld fields.* An absorbed declaration is not a field that is
  classified and then hidden: the store cannot keep a column binding, a claim or an occurrence for a
  coordinate that is not a `graphitron_element` row (`graphitron_field`, `graphitron_directive_application`,
  `graphitron_occurrence` and `graphitron_claim` all reference it), and a second population axis, modelled
  but not emitted, is something nothing in the model has. So the decode decides what the declaration is,
  in two stored relations of the `graphitron_` family, written by two new stages of the graphitron anchor,
  since the emitted population is written by `macro-expansion` right after the anchor, ahead of the table
  types the components need: `voucher-members` (class `derive.VoucherMembers`), between `anchor` and
  `macro-expansion`, writes the membership from the transcription and the issue entry alone; `vouchers`
  (class `derive.Vouchers`), after `nodes` and before `federation-link`, writes the components, the vouched-for
  rows and the voucher stage's refusals, after `nodes` because its node-type refusal reads
  `graphitron_node`:
  * the *membership*, one row per field declaration an `@issue` absorbs, keyed on its
    `graphql_field_element` row (`ON DELETE CASCADE`) and carrying the issuing field. It is decided by
    structure alone, from the issue entry and the type's transcribed fields, and independently of every
    refusal: a component whose column fails to resolve, or an `@issue` refused for any reason, still
    absorbs, so a resolution failure or a refusal is a defect, never an `id` published by A. A field
    marked `@issue` or `@accept` is never a member, so two `@issue` fields on one type (refused below)
    cannot absorb each other;
  * the *components*, one row per member whose column and scalar resolved, keyed on the member: the
    component's name, its column (the first `@field(name:)` read off the transcription, else the field
    name, joined to the column-name relation over the issuing type's `graphitron_tabletype` row) and its
    scalar. This is the issue emitter's input: which columns to select, how to serialize each value.

  `graphitron_element_authored`, `graphitron_field_authored` and `graphitron_argument_authored` each
  anti-join the membership (the field set beside its existing `NOT EXISTS` against
  `graphitron_connection_carrier`, the argument set on its field's membership), and each `meta_relation`
  description gains the reason. The set states
  its own stand-down rule, as each element set does; it is a decision, not a provenance arm. The
  transcription (`graphql_*`) keeps the declarations as written. Every relation each stage reads is
  upstream of it: `voucher-members` reads only `graphql_*` and `graphitron_ast_*`; `vouchers` also reads
  `graphitron_tabletype`, `graphitron_node` and the column-name relation.
* *The voucher binding.* The voucher field, on A and on B, is a scalar occurrence on a table-bound type,
  and today the column rule would seek a `voucher` column and raise `COLUMN_UNRESOLVED` on a valid schema.
  It binds by a kind of its own, `VOUCHER`, added to the kinds `graphitron_occurrence_binding` and
  `graphitron_binding_claim` allow, per the binding-kind ruling in
  `docs/architecture/explanation/fact-model.adoc`; no directive-name exclusion is added to the column
  rule. The claim is written by `binding-claims` and the occurrence binding, `bound` the type's qualified
  table as for `COLUMN`, by the stage writing the other scalar bindings, so the voucher occurrence has its
  binding and the column rule never sees it. The issue emitter and the entity dispatch
  find the voucher by that binding.
* *Minted keys, stored.* A stored relation of minted keys, one row per key: its coordinate, field set,
  `resolvable` and role (`NODE`, `ISSUE`, `ACCEPT`). The federation entity rule writes its `NODE` rows;
  each `@issue` writes an `ISSUE` row (`resolvable: false`) and each `@accept` an `ACCEPT` row
  (`resolvable: true`), each with the marked field's name, read off its capture entry, as its field set.
  Like `NODE`, both are minted only in a graph with a federation link. `graphitron_directive_application_minted` and
  `graphitron_directive_application_arg_minted` both read it, so the argument set stops reading the
  application view and stops hardcoding `fields: "id"` and `resolvable: true`. `@inaccessible` on the
  marked field is one more arm of the minted application set. Existing fact documents for node keys stay
  row-identical. A stage of its own owns it, `minted-keys` (class `derive.MintedKeys`), after
  `federation-link` and `vouchers`, since its `NODE` rows read `graphitron_node` and the federation link
  and its `ISSUE` and `ACCEPT` rows read the issue and accept entries, and before `directive-applications`,
  which writes the minted applications.
* *Entity key alternatives, stated.* The one fact the store lacks is how each field of an authored `@key`
  binds at its entity's table; everything else an alternative carries is already stated. So one stored
  relation, `graphitron_key_field_binding`: one row per field of an authored `@key` on a table-bound
  type, keyed `(graph, type, 'key', ordinal, position)` (the key's directive application, since a key on
  an extension can name a field declared in another file, and the field's index in the parse), with a
  foreign key into the key's `graphitron_directive_application` row, one into the field's
  `graphitron_occurrence_binding` row (which carries the binding kind), and one into
  `graphitron_tabletype` so the bound is the type's own table. A key field with no row binds nothing at
  that table, which is the refusal; the parse is reached through the authored application's written
  position, an attribute of the row and not its key. The alternatives are composed by their one reader,
  an `EntityKeyQuery` in `emit` beside `NodeIdDecodeQuery`: an authored key whose fields bind `COLUMN`
  is `DIRECT`, its columns joined from `graphitron_occurrence_column`; a key whose one field binds
  `NODE_ID`, authored or minted (`graphitron_minted_key` `NODE`), is the node-id alternative, its type id
  and columns from `graphitron_node` and `graphitron_node_keycolumn`; a minted `ACCEPT` key is the
  voucher alternative, decoding onto the authored key `graphitron_accept_key` records for each `@accept`
  (composed in slice 8). `EntityResolutionBuilder`'s refusals moved here; the builder itself stays until
  its last reader moves onto the store, under R876 (see *Slices*). A stage of its own owns the relation and the refusals,
  `entity-key-alternatives` (class `derive.EntityKeyAlternatives`), after `occurrence-depths` and
  `occurrence-values`, the two stages that write the bindings it reads, and before `outbox`;
  `meta_relation` and `meta_stage_write` name it. Whether a key is the id contract is decided by the binding (the ruling in
  `docs/architecture/explanation/fact-model.adoc`): an authored `@key(fields: "id")` over an `id` that
  carries `@field(name:)` is a key on its column, and, federation knowing keys rather than `Node.id`,
  any authored key spelling `id` stands the minted node key down.
* *The readers.* Everything built on the emitted population drops the declarations by construction: the
  directive applications through `graphitron_directive_application_authored`'s join to
  `graphitron_element`, the fields and arguments through the part sets' anti-joins above. Three readers
  are not built on it. `EmittedRegistry`, which patches the transcribed registry additively and never
  removes, gains a removal arm that reads the membership relation, not a generic "no `graphitron_field`
  row" rule, which would turn a capture defect into a silent deletion; its javadoc's "absence is a
  capture defect" paragraph says so. The served `_service.sdl` follows from it. The runtime wiring is
  handed the emitted schema, so it sees the removal. The classified walk is not: it builds from the
  written composition (`AttributedRegistry`), and so would still be handed the absorbed fields. So the
  walk and the runtime wiring go through the one membership-reading removal, applied at the single point
  both are handed their registry (`AttributedRegistry.of`), rather than a filter in each, which would be a
  second removal site for one fact and a branch in the walk being drained. The `@issue` field itself is
  the store's whole: the walk leaves it unclassified, and the store-rendered `Vouchers` unit
  (`VoucherIssueEmitter` over `VoucherIssueQuery`) holds its fetcher and registration. `FactCapture.derive`, through `ClassificationDomainCapture`, reads the
  written assembly, not the emitted one; absorbed scalar fields are harmless there, and a test pins that
  they classify without effect. No reader derives "is this absorbed" from the `@issue` entry on its own;
  each queries the membership. The LSP and MCP keep reading the authored transcription for the author's
  own text, so the declaration stays visible, completed and checked where it is written. The emitters read
  those relations; no new classified leaf carries the markers.
* *Rejections.* Each is a `graphitron_entry_defect` code at the declaration's written position, owned by
  the stage whose relations it reads, so a refusal never reads a later stage's facts
  (`StageRosterGateTest`).
  * Owned by `minted-keys`, which already reads the federation link: either marker in a graph with no
    federation link, since a voucher only makes sense where a router plans across subgraphs, and the
    minted key it publishes needs the link.
  * Owned by the voucher stage, decided from the transcription and the capture entries: a type marked
    both `@issue` and `@accept` (`VOUCHER_MARKERS_BOTH`, at each), and an authored field at the minted
    name (`VOUCHER_FIELD_NAME_TAKEN`), both since slice 8f; `@issue` on a type with an authored `@key` (it would let a router reach B
    without the voucher); `@issue` on a type
    `graphitron_node` holds, authored or inferred from the table's node metadata, since the minted node
    key would name an absorbed `id`; an absorbed field that is a list or
    an object, or carries `@nodeId`, `@service`, `@routine`, `@reference` or another directive that gives
    it a value of its own (read off `graphql_directive_application`, not off a producer fact); an absorbed
    field whose column does not resolve (a member with no component row); `@accept` in a graph with a
    `<tenantColumn>`, since version 1 has no tenant pairing (read off `store_graph_tenant_column`, captured
    configuration, so upstream of the stage; shipped in `6521093`). And
    the vouched-for fields, on either marker: a `vouchesFor:` naming no field, a field named
    twice, a list, object or nested selection, or a field carrying `@nodeId`, `@service`, `@routine`,
    `@reference` or another value-giving directive; on `@issue`, a vouched-for field that is not an absorbed
    field; on `@accept`, a vouched-for field that is not a field of the type; and two
    `@accept` fields in one graph whose vouched-for fields differ in names or scalars, since the generated
    `ConnectionKey` has one shape.
  * Owned by `entry-defects`, since what a root field returns is an element-keyed fact the voucher stage
    runs too early to read: `@issue` on a type a root operation field returns (a
    `Query`, `Mutation` or `Subscription` field, directly or as a list), because the vouched decision is
    the path from a parent A's rules admitted. A field of a mutation payload is not a root field and may
    return `@issue` rows.
  * Owned by `entity-key-alternatives`, which also owns the federation `@key` refusals: `@accept` on a
    type with no authored
    resolvable `@key`, or with several and no `key:`, or a `key:` naming no authored key; `@accept` whose
    `key:` is an authored key with a nested selection (`@key(fields: "org { id }")`), since absorbed fields
    are top-level scalars. And two refusals of an `@accept`'s vouched-for fields: a vouched-for
    field that does not bind a column at the type's table (the test key fields pass, read off the
    occurrence binding, so B's vouched-for fields need no copy of A's value-giving directive list, which A
    needed only because its members have no bindings); and a vouched-for field that is also a field of the
    key the voucher decodes to (refused rather than allowed, so the two parts never share a value), which
    reads `graphitron_accept_key` and so cannot be the voucher stage's. A rule broken the same way by
    either marker is one code for both: the federation-link, empty-vouched-for and repeated-vouched-for
    refusals say "a voucher marker" (and the session-state refusal did, until slice 8b).
* *Voucher codec.* One generated class owns the format: version byte, vouch id, scope, expiry,
  key id, the vouched-for section, the lookup section, signature. Each section is the values of its fields,
  so the token itself says which values are vouched for and which the row's key. The key values travel in GraphQL's own wire form, so A and B never
  have to agree on how a column prints: entries sorted by field name, each value the result of the absorbed
  field's scalar serializing it, exactly what a plain representation would have carried. On B each value
  is parsed by the scalar of B's key field, as a representation's value is, and reaches the authored key's
  columns the way a node id's decoded values do. The signature covers every byte before it plus the entity
  type name, which the token does not carry: B takes it from the representation's `__typename`, so a
  voucher for one type cannot be presented as another's. A decode failure, unknown version, unknown key id,
  a vouched-for section naming fields other than B's `vouchesFor:`, a lookup section naming fields other than B's
  authored key, or a value B's scalar does not parse is a verification failure, not an exception to the
  consumer. Dropping the caller (*Rulings*) changes the format, so the version byte goes up.
  Hardened 2026-11-18 after a security finding that an unsigned token could cost B unbounded CPU, and a
  far-off expiry could escape as a `DateTimeException` past the audit log. The codec now caps a token at
  `MAX_TOKEN` characters and a number's text at `MAX_NUMBER_TEXT` (the same bound as `ColumnValues`)
  before decoding anything. It takes the token apart without reading a value, verifies the signature,
  and only then reads values and the expiry, so every failure is a `VoucherRejectedException`. The issuer
  refuses to sign what the verifier would refuse for its length.
* *Generated, not shipped.* The codec, `VoucherRejectedException`, the key provider interface and its
  default Ed25519 implementation are emitted per consumer, as `NodeIdEncoder` is
  (`NodeIdEncoderClassGenerator`), and only in a build whose store holds a marker entry. Generated code has
  no runtime dependency on Graphitron, and `graphitron-jakarta-rest` is an optional serving library a
  consumer verifying a voucher need not take. JDK Ed25519 (`KeyFactory` and `Signature` with "Ed25519")
  compiles at Java 17.
* *Runtime surface.* The key provider appears only in a build with a marker; the connection contract
  (below) is every build's:
  * a key provider on the runtime: a signing key on A, verification keys by key id on B, so the public
    key can sit in B and rotation adds a key id. The default is over JDK Ed25519; the interface lets a
    consumer sign remotely, for example through Vault Transit. It is a required parameter of the runtime
    factory in a build with a marker, so omitting it is a compile error;
  * nothing per request beyond the connection source: the caller is gone (*Rulings*), and the
    vouched-for values reach the consumer as parts of the key its source is asked for.
* *Key synthesis.* The minted key and `@inaccessible` show in the served `_service.sdl` on both sides,
  and A's issuing type shows `voucher` alone. B's authored keys, `@node` and the relay id are not touched.
* *Clock skew.* B accepts a voucher up to a fixed 30 seconds past its expiry, so a small difference
  between A's and B's clocks does not reject fresh vouchers. The constant lives on the codec, which is
  the one place that reads it.
* *Issue.* The fetcher that reads an `@issue` row selects the voucher components' columns whenever
  `voucher` is selected (nothing else can select them, since they are not fields), and computes the token
  after the rows are read, one signature per row. A row
  is signed only when `voucher` is selected, which a router does only when it plans the jump to B, so the
  signing cost follows what B is asked for; no ceiling beyond the page size the parent field already sets.
  Expiry is fixed per operation. It works at any position below a parent, including under a mutation
  payload, where the field's transaction rule (a rolled-back field is `null`) keeps a voucher for
  rolled-back rows from the client. It is emitted as an `emit` query and emitter reading the component
  relation, not as a branch of `TypeFetcherGenerator`.
* *Verify and group.* Entities and nodes resolve through DataLoaders keyed by connection key, in every
  build without a tenant column (slice 8d.1), and vouchers are one more source of a key's parts (slice
  8d.2). `fetchEntities` takes each representation in turn: it picks the alternative, decodes the
  representation into the columns of that alternative's key and a `ConnectionKey`, and registers the
  columns with the loader for (type, alternative, key). A plain key and a node id give the key with no
  parts. A voucher is decoded and checked by the codec, which only decodes (format, signature over the
  token and the `__typename`, expiry, scope, and that the sections name exactly the expected fields);
  the dispatcher then coerces each lookup and vouched-for value to its column as a representation's
  values are coerced, a value no column takes failing the check, and builds the key from the vouched-for
  values. The per-type data (scope, voucher field, vouched-for and lookup fields, their columns) is
  emitted beside that step from the voucher alternative's one key-field list, so the lookup columns and
  the SELECT's columns cannot drift. When graphql-java dispatches the loaders, each batch is one SELECT on
  `dslFor(env, key)`, its key's lease, and `_entities` answers in the representations' order from their
  futures. `Query.node` and `nodes` load through the same loaders, so an entity is read one way. The
  connection for a key is reached through the one point every fetcher reaches it by, which on the
  escape-hatch path answers the key with no parts with the consumer's `DSLContext`; a build that accepts
  vouchers has no escape hatch and no tenant column (*Rulings*).

  No connection is taken before every voucher of the request is checked, by the loaders' own timing:
  checking happens while representations are registered, acquisition only when the loaders dispatch. A
  failed check rejects the request the way a failed `acquire` does: the carrier remembers the first
  rejection in its one slot, `instrumentExecutionResult` throws it in place of the result, and once one is
  remembered the carrier acquires nothing more, so a representation registered before the failing one
  never gets a connection either. Throwing from the fetcher alone would make a field error. The
  one-value limit lives where connections are taken: the carrier acquires at most one key with
  vouched-for values per request, and a second fails with an error naming the limit. The dispatcher logs
  the vouch id, scope and outcome of every redemption and rejection; the codec logs nothing. The voucher
  alternative is a sealed arm of `EntityKeyQuery.Alternative` with its own identity, the minted `ACCEPT`
  key, carrying the scope, the vouched-for fields, and the authored key it decodes onto by identity with
  that key's fields; the vouched-for values filter nothing. Builds with a tenant column keep today's
  dispatch until R876 states their tenant routing in the store; tenant pairing, after version 1, gives the
  voucher alternative the tenant source of the authored key it decodes onto (`graphitron_accept_key`).
* *Connection key.* Every build emits a generated `ConnectionKey`, a record with one `Optional` per part
  its schema has: the tenant, typed by the tenant column, where it has a `<tenantColumn>`, and one per
  vouched-for field, typed by the column the field reads, where it accepts vouchers. A build with neither
  emits a record with no component, and the carrier keys by it all the same, so the
  `Optional<tenantKey>` keying of today's single-tenant carrier goes. Factories name the keys a site
  means (the request's own, a tenant's, a vouched value's); a compact constructor refuses some
  vouched-for parts present and others not. The graph's `@accept` fields share one vouched-for shape,
  names and column types, differing shapes refused (`VOUCHER_ACCEPT_VOUCHED_FOR_DIFFERS`), so the record
  has one set of components. The shape is computed once from the store and the tenancy configuration and
  handed to every generator that needs it; no site evaluates "has accept" or "is multi-tenant" to decide
  how connections are keyed. The `localContext` slot carries a row's key wherever its subtree reads it:
  until slice 8e, wherever the key has a tenant part, as today; an accepting row's key, whose tenant part
  is empty in version 1, is not written there before slice 8e, since nothing below a vouched entity reads
  it yet. The shape question replacing `writesSlot()`'s "is multi-tenant" is "which parts does the key
  have", asked of the shape, not of the build.
* *Connection source and leases.* The contract of *Rulings*, generated in every owned-connection build:
  * `ConnectionSource`, a functional interface `Lease acquire(ConnectionKey key) throws Exception`,
    given with each request to `Graphitron.newOwnedExecutionInput(source, ...)`; built when the request
    is, it captures what the consumer needs of it and acquires nothing until called. In a build with a
    tenant column it also declares `Set<T> tenants()`, typed by the tenant column, the fan-out domain,
    and is built with `ConnectionSource.of(acquire, tenants)`; in a build without one it is a functional
    interface, its `tenants()` a default that throws;
  * `KeyNotPermitted`, the one refusal `acquire` may throw: an error located at the field that asked, and
    `null` with no error in `_entities`, `node` and `nodes`, as a refused tenant answers today; any other
    throw rejects the request;
  * `Lease`, with `connection()`, `handle()` typed by `<handle>` where one is configured (absent
    otherwise), and `release()`; a static factory builds one from a connection, a handle and a release
    action, so a consumer writes no class. The jOOQ dialect and `Settings` Graphitron builds a lease's
    `DSLContext` with are the runtime's, application-wide, given to the runtime factory; an overload of
    the lease factory takes a dialect and settings of the lease's own, for a pool of another database
    or another configuration (2026-11-16);
  * the carrier (`OwnedConnections`) calls `acquire` the first time a field needs a key's connection,
    never twice for one key, though it may call it for distinct keys from several threads at once (a
    fan-out's workers), so a source must be safe to call concurrently; it keeps one lease per distinct key, gives `$session` bindings the lease's handle, demarcates each
    mutation field's transaction on the lease's connection after asserting autocommit, and releases
    every lease exactly once on every path. A fan-out worker past its deadline is Graphitron's to stop
    (cancel the statement, abort the connection through JDBC) before its lease is released; nothing of
    it reaches the consumer;
  * a throwing `acquire`, other than `KeyNotPermitted`, rejects the request through the carrier's one
    rejection slot, the slot a failed voucher check uses; a throwing `release` is logged, the rest are released, and the first failure
    surfaces after all are attempted;
  * what goes: the runtime's default `DataSource` and `Map<TenantId, DataSource>`, `SessionState` with
    its mount and unmount, `MountedSession`, the generated `GraphitronSessionHook`, `PinnedConnection`,
    and the PostgreSQL `RESET ALL` backstop as Graphitron's duty, and the request tenant set
    (*Rulings*);
  * a ready-made source, shipped with the contract: built from a key-to-`DataSource` function, a mount
    and an unmount (the vouched-for values and identity as session settings), with the PostgreSQL reset on
    release, so a consumer keeps today's behaviour without writing a lease, and the migration is one
    call. The manual's worked example uses it. The escape-hatch path
    (`newExecutionInput(dsl, ...)`) is unchanged where the key has no part; a build whose key has parts,
    a tenant or vouched-for values, is owned-connections only.
* *Whether the dispatch is emitted.* Today the plan and the schema class decide it from the walk's
  entity map, which never sees the minted voucher key. That decision is one fact R876's node "The facade,
  dev executor, registrations and schema class read the store" states once; slice 8d either states it
  (the dispatch is emitted when the store holds an entity with a resolvable alternative) and marks that
  part of R876's node taken, or leaves it to R876. It adds no voucher-specific predicate beside the
  walk's.
* *The walk's stand-down for B's voucher field.* It reads the `VOUCHER` binding alone, applied at the one
  point the walk is handed its registry, as the membership removal is (`AttributedRegistry.of`), not as a
  check in each walk consumer. It goes with the walk.
* *Configuration.* No new POM element: the markers switch the feature on, and keys are runtime
  configuration. `<sessionState>` keeps only what it names, the handle class; a voucher marker no longer
  needs it (*Retired vocabulary*).
* *Audit logging.* Graphitron logs the vouch id, scope and outcome at issue and on every redemption and
  rejection, at a named level and logger the docs state. Nothing reads the vouch id otherwise.
* *Redaction.* The voucher is a credential: confirm generated code and the dispatcher never log
  representations, the token or its values, or echo the voucher in errors, and that
  `VoucherRejectedException`'s message names the check that failed and nothing from the token.
* *Out of scope.* Key management and rotation procedure, a reference connection source and policies (a worked example
  goes in the how-to), router header propagation, a per-row decision beyond A's own read.

### Slices

Each slice lands green on trunk. Through slice 8b a build that uses neither marker is unchanged, because
every emitted unit, parameter and `of` overload is gated on a marker entry in the store; slice 8c changes
every owned-connection build, as the connection contract is every build's.

| Slice | What it delivered | Shipped at |
|---|---|---|
| 1. Column names | `sql_column_spelling`; the occurrence rule and its defect arms join onto it | `b4b9c96b` |
| 2. Minted keys and entity key alternatives | `graphitron_minted_key` (`8c719f9b`); the `@key` refusals as entry defects of `entity-key-alternatives` (`5c649178`); `graphitron_key_field_binding` (`8d6c63a2`); an authored key spelling `id` stands the node key down (`3b2ac078`) | see cell |
| 3. Connection key | A generated `ConnectionKey`, tenant part only, in the `localContext` slot | `f1c4b26a` |
| 4. A's facts and published shape | `@issue`, its membership, components, vouched-for rows and refusals, the `VOUCHER` binding, the `ISSUE` key and `@inaccessible`, `EmittedRegistry`'s removal arm | `08ebe10d`, `2ca51f42`, `62b43cd8`, `b0bbbd25` |
| 5. Codec | The generated codec, key provider and `VoucherRejectedException` | `7b656753` |
| 6. Issuing | `VoucherKeys` on the runtime, the caller on `SessionState.of`, the store-rendered `Vouchers` unit signing per row; the temporary `@issue` refusal removed | `a46e60a8`, `c2f16ab1`, `d24dc49c` |
| 7. B's facts | `@accept`, its `VOUCHER` binding, vouched-for rows and refusals, the `ACCEPT` key and `@inaccessible`, `graphitron_accept_key`, the voucher alternative stated in the store; refused until generated | `f8ef399f`, `24b24899`, `6dadbfcd` |
| 8a. Rename and refusals | `claims:` renamed `vouchesFor:` through the store, codes and codec; the vouch-id agreement check gone; `@accept` in a `<tenantColumn>` build refused | `18c1a92`, `6521093` |
| 8b. No caller | The voucher carries no caller; format version 2; `VOUCHER_WITHOUT_SESSION_STATE` gone | `71c0917` |
| 8c.1. Keyed by `ConnectionKey` | Every build keys its connections by the generated `ConnectionKey`, its tenant part typed `Object` without a tenant column | `674c051` |
| 8c.2. The contract | `ConnectionSource`, `Lease`, `KeyNotPermitted`, `AcquisitionFailedException` and `ConnectionSources`; the runtime on dialect and settings; the session units gone | `e3a3ecf` |
| 8c.3. Tenants to the source | `ConnectionSource.of(acquire, tenants)` names the fan-out domain; the request tenant set, the default tenant and `@defaultTenant` gone | `abe204f` |
| 8d.1. One way to read an entity | `_entities`, `node` and `nodes` load through DataLoaders per (type, alternative, connection key) in every build without a tenant column; `OwnedConnections.dslFor(env, key)` the one point, in every build | `a871d07` |
| 8d.2. Vouchers on it | The voucher alternative, the codec's check and the dispatcher's key, the limit, the sealed request rejection; the how-to and both halves as fact documents | `26b15d3`, `dc0c7a7`, `d5d1e83`, `ccd0b98` |
| 8e.1. The grain and a `@service` below | A field below a vouched entity is handed the entity's key; a child `@service` reads on it | `8bb6d38`, `3f44811` |
| 8e.2. The walk reads the handed-down key | A split query and a polymorphic field below a vouched entity read on the key it hands down | `c0f511d`, `420ba54` |
| 8e.3. Connection-typed fields | Pending on R876; below | |
| 8f. Markers on the type | `@issue` and `@accept` on the type, `internalGraphitronVoucher` minted on both sides | see *Slice 8f* |

Facts from the shipped slices that slice 8 builds on:

* `EntityKeyQuery` passes over a voucher key today. Slice 8 composes the voucher alternative beside the
  dispatch arm that renders it; an arm with nothing to render, unreachable behind the refusal, would be
  code no test can run.
* `EntityResolutionBuilder` and the walk are untouched by slice 7, because the tenant fold still reads the
  builder. The builder goes with its last reader, under R876's node "The entity dispatch's tenant routing
  is read from the store".
* A `key:` is matched against the authored key's own `graphitron_key_field_binding` rows, which are also
  where the voucher alternative's columns come from.
* `VOUCHER_MEMBER_NOT_SCALAR` refuses enum members too, since an enum member has no scalar constant to
  serialize by. Carrying an enum's wire value can follow if a consumer needs it.
* Since slice 3, R876 renamed the connection carrier to `OwnedConnections` and the slot's writer to
  `ConnectionKeyHandDown`, and store-emitted fetchers acquire by `dslFor(env, ConnectionKey)`. Slice 8c
  puts the consumer's connection source behind that one point rather than introducing another.
* A first build of the dispatch (kept off trunk) emitted the verification inline into the dispatcher and
  let Graphitron mount on a connection it took itself. It ran end to end over an RLS fixture, but the
  algorithm could only be reached through a query, and the consumer's side did not match the contract
  above; slices 8c and 8d replace it. What carries over: the store's voucher alternative, the narrowed
  refusal, the shape refusal, the RLS fixture and its execution tests.

The dispatch and the fields below an entity split along R876's flip. The `_entities` dispatch already renders from the store
(`EntityKeyQuery`, `EntityFetcherDispatchClassGenerator`), and a field projected into the entity's SELECT
reads on whatever connection that SELECT runs on, so both can be built now. A field below the entity with
a fetcher of its own cannot: walk-side fetchers (`TenantDslEmitter`) rebuild the connection key from its
tenant part and, in an untenanted build, read the request's connection; store-side fetchers inherit only
where `graphitron_occurrence_connection` states a row, and its rule joins `store_graph_tenant_column`.
Patching the walk's emitters would extend code R876 is deleting.

**Slice 8b, no caller.** The caller binding goes (*Rulings*): the token carries no caller and the format
version goes up, `Reason.CALLER` goes, the issuers sign without one, `SessionState.of` loses its caller
parameter, and `VOUCHER_WITHOUT_SESSION_STATE` goes, a marker no longer needing `<sessionState>`;
`issue.adoc` and `accept.adoc` lose both. The codec and issuing tests lose their caller cases. Small, and first, so slice 8c does not carry the caller
into the new contract.

**Slice 8c, the connection contract.** Every owned-connection build gets the `ConnectionKey` of
*Connection key* and the `ConnectionSource` and `Lease` of *Connection source and leases*; the carrier
acquires through the request's source by the whole key. In three steps, each green on trunk:

* 8c.1, internal: every build keys its connections by the generated `ConnectionKey`, its tenant part
  typed `Object` where there is no tenant column, the same value the `Optional` key was. No consumer
  change.
* 8c.2, the contract: `ConnectionSource`, `Lease`, `KeyNotPermitted` and the ready-made source; the
  carrier acquires through the source; `MountedSession`, `SessionState` and `GraphitronSessionHook` go,
  and `PinnedConnection` becomes the carrier's package-private wrapper of a lease (2026-11-17). A `feat!`
  whose `BREAKING-CHANGE:` footer names what a consumer changes: the runtime factory loses its
  `DataSource` and tenant map, and `newOwnedExecutionInput` takes a source in place of a `SessionState`.
  As built (2026-11-17, agreed): `KeyNotPermitted` is a `RuntimeException` the carrier turns into the
  field's client error, since the generated client exception is final; every throw of the source but
  `KeyNotPermitted` is a failed acquisition, a pool fault included, so a consumer tells a rejected
  identity from an outage by the cause; jakarta-rest's seam takes the new names; and the record keeps its
  `Object`-typed tenant part in an untenanted build until slice 8d.2 adds the vouched-for parts, where it
  narrows to the parts the schema has.
* 8c.3, tenants to the source: the request tenant set goes, a tenanted build's source names its fan-out
  domain through `ConnectionSource.of(acquire, tenants)`. Breaking for tenanted consumers. With it go
  the default tenant and `@defaultTenant` (*Rulings*, 2026-11-17). The entity and node dispatch's
  pre-check against the set becomes catching `KeyNotPermitted` per key, which leaves that group `null`;
  the carrier remembers a refusal per key, so the source is never asked twice for one key. The carrier
  reads `tenants()` once per request, in the tenant column's form and in the order given; a tenant the
  source names there and then refuses answers at the fanned field as an error. The dev executor in a
  tenanted build fails naming `<devDatabase><session>` rather than fanning out over nothing.

* The generated runtime: `ConnectionKey` in every build, `ConnectionSource`, `Lease` and its factory, the
  carrier keyed by `ConnectionKey` in every build, `PinnedConnection`, `MountedSession`, `SessionState`
  and `GraphitronSessionHook` gone, the request-rejection slot shared by a failed `acquire` and (from
  slice 8d) a failed voucher check, the dev executor and the facade's factories on the new signatures.
* The tenant machinery stays where it is a schema feature (divination, fan-out) and stops being a
  runtime mode: a tenant reaches the source as the key's tenant part, a fan-out's domain comes from the
  source's `tenants()` (`ConnectionSource.of(acquire, tenants)` in a tenanted build), and the request
  tenant set and its check go, a refusal being the source's `KeyNotPermitted`.
* The ready-made source and `KeyNotPermitted` ship with the contract, not after it.
* Unit tests through `EmittedCodeHarness` for the carrier: lazy acquisition once per distinct key, the
  lease's handle to `$session`, release exactly once on success, failure and a timed-out fan-out worker,
  `KeyNotPermitted` answering as a field error and as `null` in the entity dispatch,
  a throwing `acquire` rejecting the request and acquiring nothing more, a throwing `release` not
  orphaning the rest.
* The dev loop: `<devDatabase><session>` (and `GRAPHITRON_DEV_SESSION`) names a method that returns a
  `ConnectionSource` in place of a `SessionState`, and the dev executor and the MCP `execute` tool take
  it.
* Every execution fixture moves to a source. Rewritten for it: the manual's tenant-scoping how-to,
  `runtime-api.adoc`, `security.adoc`, `mojo-configuration`'s session-state and dev-session sections,
  and `runtime-extension-points.adoc`, its `<sessionState>` section (with the warning when none is
  configured) and § Per-tenant routing alike.

**Slice 8d, the dispatch.** In two steps, after slice 8c.2, whose source and leases the batches acquire
through. It ends with consumers able to test an accepting type whose fields below it are all projected,
which is enough to try the first client's shape if its documents read that way.

* 8d.1, one way to read an entity: in every build without a tenant column, `_entities`, `Query.node` and
  `nodes` resolve through DataLoaders per (type, alternative, connection key), the key empty in every
  representation of such a build, in place of the synchronous `resolveByReps`; the one point a fetcher
  reaches a connection by takes the key. No behaviour changes; the federation and node tests pin it.
  Tenanted builds keep today's dispatch.
* 8d.2, vouchers on it: `EntityKeyQuery` composes the voucher alternative; per representation the codec
  decodes and checks the voucher and the dispatcher coerces and builds the key, with the per-type data
  emitted beside it; the carrier's one-vouched-value limit; the dispatcher's audit log (*Verify and
  group*). Unit tests through `EmittedCodeHarness`: the carrier refusing every acquisition once a
  rejection is remembered, the limit, and a voucher's decode, check and coercion on plain
  representations with real keys.
* Whether the dispatch is emitted, left to R876 (*Whether the dispatch is emitted*): an accepting type
  carries an authored `@key`, so the walk's entity map holds it.
* The walk's stand-down reads the `VOUCHER` binding alone. `VoucherBindingQuery` reaches a voucher field
  through A's members, so B's accepting field, which has none, would drop out of it and be asked of the
  walk.
* The refusal narrows. `VOUCHER_ACCEPT_NOT_GENERATED` refuses only an accepting type with a field below
  it, at any depth through the object types its SELECT reads, that has a producer at any of its
  occurrences: the producer relation is the store's positive statement that a field runs something of
  its own, and a field reached elsewhere with one counts here too, erring toward refusal. Walked type by
  type by indexed lookups, in `entity-key-alternatives`' defect rule. The fact documents pin it both ways.
* `VOUCHER_ACCEPT_VOUCHED_FOR_DIFFERS`: two `@accept` fields vouching for differently shaped values,
  names or column types, refused at each differing from the first accepting type's (*Rejections*).
* The redaction check (*Redaction*).
* Execution tests over an RLS fixture and an `accepting` graph of its own beside the `issuing` one: the
  entity and a nested read projected into its SELECT on the vouched connection, under a policy that
  otherwise hides the rows; one lease for an owner's vouchers, released at the end; a read outside the
  subtree admitted nothing; a plain-key representation adding no access; the limit error; every
  verification failure with nothing acquired, also among plain-key and valid representations.

**Slice 8e, fields with a fetcher of their own.** In three parts. None waits on a tenant-routing node:
version 1 refuses `@accept` in a tenant-column graph.

*8e.1, the connection relation's grain and a `@service` below a vouched entity.*

* `graphitron_occurrence_connection`'s grain widens from "one producer of a database-per-tenant graph"
  to "a producer of a graph whose `ConnectionKey` has a part": a tenant column, or an accepting type's
  vouched-for fields. Every build has emitted a `ConnectionKey` since slice 8c.1, so "carries one" is
  every graph; a part is what the generated code can hand down. The fields below an accepting entity
  get `INHERIT` rows in an untenanted graph too. A field inherits the whole key its producer hands
  down, which is already what `INHERIT` means; no vouched-for part is restated on its rows, and the
  part `CHECK` stays `'TENANT'`. `_entities` itself does not change and gets no row: it runs as today,
  on the connection the dispatcher acquired for the vouched-for value, and hands that key down as it
  hands down a tenant's (2026-11-16). Occurrences are kept per field rather than per path, so a child
  producer of an accepting graph is `INHERIT` wherever it is reached; a row reached with no key handed
  down runs on the request's own connection, the rule for a field outside the subtree. R876 carries
  this as its node "The connection relation's grain covers accepting graphs", so a rewrite of its rule
  does not narrow it back to tenancy.
* The `localContext` slot follows either part of the key (*Connection key*), so each resolved entity row
  is a `DataFetcherResult` carrying its group's key in an accepting build.
* A `@service` field below the entity acquires from the inherited key, through the store-fed frame.
* `VOUCHER_ACCEPT_NOT_GENERATED` narrows to an accepting type with a field below it, through the types
  its SELECT and its `@service` fields read, that runs a query of its own other than a `@service`: a
  split query, a connection-typed field, a routine read on its own. `accept.adoc` and the directive
  definition say so.
* Execution tests: the service under a voucher reads on the vouched lease, released once; the same
  service reached from outside the subtree reads on the request's own connection.

*8e.2, the walk reads the handed-down key.* Alf, 2026-11-17: the walk's untenanted arm reads the
`ConnectionKey`, so the fields the walk still fetches need not wait for their store-fed fetchers. An
exception R876 records in its flips in flight, gone with the walk's read path.

* The walk's emission context carries the build's `ConnectionKeyShape`, read once, and
  `TenantDslEmitter.readsHandedDownKey` is its one reader: a build whose key has parts and no tenant
  column. Where the shape is decided, the walk's tenant scopes must agree with the store's tenant column.
* Its three places that do not route read the key the parent was read on: the fetcher's declaration and
  its expression form `dslHandedDown(env)`, a loader's name `handedDownLoaderName(env, parent)`, so a
  split query, a routine and a polymorphic field below the entity acquire from the inherited key. A
  root, handed no key, reads on the key with no parts, as before.
* `VOUCHER_ACCEPT_NOT_GENERATED` narrows to a connection below the accepting type, a field
  `@asConnection` expands or one returning a connection type, whatever fetches it; the reach follows
  every field and steps from a union or interface to its members. `accept.adoc` and the directive
  definition say so.
* Execution tests: a split query and a polymorphic child below the entity read on the owner's one lease;
  a root handed no key reads on the key with no parts.

*8e.3, connection-typed fields.* Waits on R876's node "A connection-typed field's fetcher is emitted
from the store, on its stated connection": a connection's count and facets resolve their own
`DSLContext`, which the walk's handed-down read does not reach.

* A connection-typed field under the entity acquires from the inherited key.
* `VOUCHER_ACCEPT_NOT_GENERATED` goes, with the "refused for now" sentence in `accept.adoc` and the
  directive definition.
* The remaining execution tests under *Tests*: a nested read through fields with their own fetchers, a
  field outside the subtree, release.
* Before Done: the composition check against the fixture's two served SDLs, recorded in the item (see
  *Tests*, Served SDL), and the first-client docs draft moved into `federation-keys.adoc` and the security
  guide (the how-to with a worked connection source and policies shipped beside slice 8d.2, as
  `voucher-handover.adoc`).
* Before Done, the rest of what the rulings ask that slice 8d left open (folded back from R1194 and R1195,
  2026-11-17):
  * the issue-side audit line: an issuing subgraph logs one line per operation and scope it signs, with
    the vouch id, scope and expiry, on the `no.sikt.graphitron.voucher.audit` logger the accepting side
    already uses, so the two halves of a handover trace against each other by vouch id; never the token
    or its values (*Audit logging*);
  * a build whose `ConnectionKey` has a tenant part emits no `Graphitron.newExecutionInput(dsl, ...)`, as
    an accepting build already does not, so every keyed build is owned connections only (*Rulings*,
    2026-11-16). Taken with R876's tenant routing if that rewrites the tenanted dispatch first.

**Slice 8f, markers on the type.** `@issue` and `@accept` move from a field the author writes onto
the type, and Graphitron mints the voucher field on both sides (*Rulings*, 2026-11-18). What it buys: the
field's name is fixed, and its type is Graphitron's, so A and B cannot disagree on them, a disagreement neither build can
check; on A, "absorb every other field" becomes "absorb every field written", so the membership loses its
marked-field exception, and the minted key's field set is a constant rather than a read of the marked
field's name. A `feat!` whose `BREAKING-CHANGE:` footer says what an author changes: move the marker from
the voucher field onto its type and delete the field. Nothing outside the repo applied either marker.
Independent of 8e.3. As built:

* *Directives.* Both definitions' location is `OBJECT`, and their descriptions say Graphitron adds
  `internalGraphitronVoucher`, `String!` on an issuing type and `String` on an accepting one, since B never computes a
  voucher for a row it reads by another route (Alf, 2026-11-18). Capture admits an application only at
  its declared location, so the decode moved
  from the field site to the type site, in a class of its own (`GraphitronVoucherEntries`). The entries
  are keyed by the application's written position and did not change; every join that went from the
  entry through the marked field's element to its type now reaches the type, the application's
  coordinate.
* *The mint.* `graphitron_voucher_field`, one row per marked type (role `ISSUE` or `ACCEPT`, ordinal
  after the authored fields, the marker's position), written by the `voucher-members` stage beside the
  membership, from the transcription alone, and unioned into `graphitron_field_minted` as an arm of its
  own. It is not an arm of `graphitron_field_minted_candidate`, whose `position` and
  `carrier_coordinate` are the connection's and whose rows stand down to an authored field. Minted
  fields already get occurrences, so the voucher field needed nothing new to have one.
* *Collision* (Alf, 2026-11-18, in `defects-as-facts.adoc`). An authored `internalGraphitronVoucher` on a marked type is
  `VOUCHER_FIELD_NAME_TAKEN` at the marker; it keeps the coordinate, is never absorbed, and nothing is
  minted beside it.
* *The double marker* (Alf, 2026-11-18, in `defects-as-facts.adoc`: never both). A type marked both is
  `VOUCHER_MARKERS_BOTH` at each marker, and is given the issuing field, which absorbs what it declares,
  so the refused schema still assembles for capture to report it (see *Open questions*). Both shapes leave the type a
  field, so the refused schema still assembles and its defects are reported. They replace
  `VOUCHER_FIELD_REPEATED`.
* *Membership.* Every field written on the issuing type but an authored `internalGraphitronVoucher`.
  `graphitron_voucher_member` lost `issuing_field` and its `CHECK`, and gained a foreign key into
  `graphql_element` on its coordinate, which it is.
* *The voucher binding.* The `VOUCHER` kind stays; its claim arm reads the mint, its source position the
  marker's. `graphitron_occurrence_voucher_rule` is unchanged.
* *Minted keys.* The `ISSUE` and `ACCEPT` rows read the mint, their field set its name; the minted
  `@inaccessible` lands on the minted field.
* *The emitters.* `VoucherIssueQuery` and `VoucherBindingQuery` find the field by the mint rather than
  rebuilding `TYPE_NAME || '.' || ISSUING_FIELD`.
* *The walk.* Not as planned: the walk's schema must be valid GraphQL, and an issuing type whose written
  fields are all absorbed would have none. So `AttributedRegistry.of` adds the minted field
  (`EmittedRegistry.mintVoucherFields`), and the walk's stand-down stays, read off the mint rather than
  the binding. Both go with the walk.
* *`VOUCHER_ISSUE_ABSORBS_NOTHING` retired.* A type whose only field is the minted one is no type
  graphql-java assembles, so the refusal can no longer fire.
* *Refusals restated.* The rest keep their codes, raised at the type-level application; a detail that
  was the marked field's coordinate is the type's name.
* *Prose and fixtures.* `issue.adoc`, `accept.adoc`, the directive definitions, the how-to and its
  rendered example, the sakila `issuing.graphqls` and `accepting.graphqls`, the fact documents, and
  `pipeline-overview.adoc`'s account of the stand-down.
* *Tests.* The fact documents changed by the rows above; the refusal documents claim the collision on
  both sides and the double marker, and that neither collision mints. The execution tests pass unchanged
  in substance, the representation now `{internalGraphitronVoucher}`.

## Tests

**Codec.**

* Round trip, including an `ID` field over an integer column on A against an integer key column on B, a
  UUID key, an `Int` key, and a two-field key whose fields are declared in either order.
* Each of these fails verification, and nothing else does: a tampered signature or key value; a wrong
  key id; an unknown version; expired beyond the skew tolerance (and accepted within it); a wrong scope;
  a voucher presented under another `__typename`; a vouched-for section naming other
  fields than B's `vouchesFor:`; a lookup section naming other fields than B's key; a missing or extra
  vouched-for field; a value that does not coerce.
* Bounded and total before the signature: a multi-megabyte token or one over `MAX_TOKEN` is malformed;
  a number over `MAX_NUMBER_TEXT` is malformed whether or not it is signed; text no tag reads is refused
  for the signature when unsigned, so nothing parsed it first; an expiry no `Instant` holds is a
  rejection, never another exception; the issuer refuses what the verifier would refuse for its length.

**Refactor gates (slices 1 to 3).** Every existing fact document row-identical after the column-name
relation, the minted-key relation and the entity key alternatives land, and generated output unchanged
for every existing fixture; single-tenant output unchanged after `ConnectionKey`.

**Pipeline tier.**

* Capture of both markers, and each rejection under *Rejections* as a defect at the declaration's
  position, owned by the stage named there. These include `@issue` on a type a root field returns, on a
  type with an authored `@key`, and on a node type (authored and inferred from node metadata); absorbing
  nothing; absorbing a `@nodeId`, list, object or `@service` field; absorbing a field whose column does
  not resolve. And `@issue` accepted under a mutation payload.
* The membership for every other field of the type, and the components with column and scalar resolved,
  the column read through the column-name relation both by SQL name and by jOOQ name.
* A member whose column does not resolve is still withheld: no `graphitron_element`, `graphitron_field`
  or argument row, absent from the served SDL.
* No `graphitron_element` row, no field or argument binding and no occurrence for an absorbed
  declaration; absorbed fields classify without effect in `ClassificationDomainCapture`.
* The voucher field bound `VOUCHER` and no `COLUMN_UNRESOLVED` on it, on A and on B.
* `@accept` with two authored keys, with and without `key:`, and on a key with a nested selection.
* The minted keys as rows (`ISSUE` with `resolvable: false` as the only key on A's issuing type, `ACCEPT`
  with `resolvable: true` beside the authored key on B's entity), and as the applications and arguments
  the minted sets render; `@inaccessible` on the voucher field on both sides.
* The voucher alternative beside the authored one in the entity key alternatives.
* The connection key in emitted fetchers.
* Through slice 8b, output for a build with no marker unchanged, and `Graphitron.runtime` gains the key
  parameter only when a marker is present. From slice 8c, every build's `ConnectionKey` has exactly the
  parts its schema has: none, the tenant, the vouched-for fields.

**Compilation tier.** Generated issue, dispatcher and acquisition code compiles at Java 17.

**Execution tier.** Real Postgres, an RLS fixture beside the existing session fixture, A and B as two
graphs of one fixture module.

* Each returned row gets its own token, all share one vouch id, and Graphitron logs it at issue and at
  redemption, never the token or its values.
* A valid voucher exposes the entity and a three-level nested read under a policy that otherwise hides
  the rows, both through fields projected into the entity's SELECT (8d) and through fields with a
  fetcher of their own (8e).
* A list of results, or several parents' lists, for one owner takes one lease, and the source is asked
  once, for a key carrying the owner.
* Vouchers from two scopes, or two issuing operations on A, for the same owner, presented in
  one operation on B, share the connection.
* An operation mixing two owners' vouchers fails with the limit error.
* A field outside the entities' subtrees cannot read the rows the vouched-for value unlocks.
* Every verification failure above, and a source that throws for a vouched key, fails the request,
  and the policy hides the rows. A verification failure takes no connection, even when the bad voucher
  belongs to a type resolved after another voucher-bearing type.
* A mutation payload issues vouchers on commit, and none from a field that rolled back.
* Every lease is released at the end, exactly once.
* With the owner as vouched for (`vouchesFor: "eierId"` on both markers): the source sees the owner in
  the key; a policy on the owner admits the owner's other documents on the vouched connection; and an
  owner who is not the user the request is for is admitted.

**Served SDL.** `_service { sdl }` shows `@key(fields: "voucher")` and `@inaccessible` on the voucher
field on both sides, with the `@link` importing `@inaccessible`, and A's issuing type publishes `voucher`
and nothing else, in the served SDL and in the runtime schema (an introspection of A's own endpoint).
That assertion is the build-time guard on both federation questions: with only `voucher` on the type, no
plan from A can use B's plain key, and no A-only field can make the supergraph fail to compose.

No composition or planning tool is in the build, so the end-to-end evidence is row 4 of the round-2
composition table under *Reviewer findings* (Apollo's `@apollo/composition` and `@apollo/query-planner`
2.14.4): A publishing `voucher` alone composes, with the jump on `voucher`, while B also reaches the type
from its own root field. Before Done, the implementer repeats that check against the fixture's two served
SDLs and records the result in the item; the how-to names `rover supergraph compose` for consumers who
want to see the plan.

## Open questions

* Names: `@issue` and `@accept` name the two acts; both are generic words for directives in an SDL that
  may carry other meanings, so check them against the directive vocabulary before they ship.
* Whether the 2026-11-15 ruling that a name Graphitron would mint is never silently withheld, written for
  types, covers fields too. Slice 8f's collision refusal rests on it if so, and needs a ruling of its own
  if not; for Alf and Filip, and the answer belongs in `defects-as-facts.adoc`.
* The refused voucher shapes still mint (slice 8f). A type marked both `@issue` and `@accept` is refused
  (Alf, 2026-11-18) but given the issuing voucher field, and an authored `internalGraphitronVoucher` is kept out of the
  membership, both so the refused schema still assembles for capture to report it.
  `defects-as-facts.adoc` § The supertype says a refused macro shape is refused before the mint. For Alf
  and Filip: whether capture should instead judge a field-less type itself, which would let both refused
  shapes mint nothing and drop the member rule's `internalGraphitronVoucher` exception.
* The voucher refusals restate the mint rule's predicates (slice 8f): `VOUCHER_FIELD_NAME_TAKEN` and
  `VOUCHER_MARKERS_BOTH` are worked out again from the transcription rather than read as the absence of
  a `graphitron_voucher_field` row, against the ruling that a stage decides once in its relation's rule
  (`defects-as-facts.adoc` § Ownership). A refactor that changes no behaviour; folded in with the
  question above, since the double marker's mint is what keeps it from being a pure absence.
* `<sessionState>`: once it only names the handle class, whether it keeps its name.
* Key provider shape and default: Ed25519 over JDK keys, with the public key distributed to B; whether the
  default also reads PEM or only takes key objects.
* Replay: no per-voucher nonce. A voucher is bound to scope, type, key and a short expiry, no longer to a
  caller (*Rulings*), and the
  same voucher is legitimately presented more than once (router retries, several fetches in one plan), so
  single use would need shared state across B's instances and would break those. The vouch id is the
  audit handle, not a replay guard: Graphitron logs it at issue on A and on every redemption and
  rejection on B, so a redemption traces back to an issuance. Handing the id to the consumer, for one
  that wants single use recorded in B's own database, can be added later; router retries would then
  fail.
* A component read through a `@reference`: an absorbed field is a scalar read off the issuing row. A key
  value that lives one join away is left out until a consumer needs it, and refused meanwhile.
* Narrower grants: one connection per vouched-for value scopes a subtree's reads to the value by RLS and
  to its entities by graph shape, not by connection. A consumer that needs a narrower grant puts more into
  `vouchesFor:` (a document id, say), at the cost of the one-value-per-operation limit.
* Tenant pairing, after version 1: lifting the `<tenantColumn>` refusal by giving the voucher alternative
  the tenant source of the authored key it decodes onto (*Verify and group*), with an execution test
  that a voucher does not reach a tenant outside the request tenant set. It brings one more question
  back with it: does `UNSCOPED` empty only the tenant part? A child below a vouched entity that reads a
  table no tenant column scopes is `UNSCOPED`. If that means "empty tenant part, vouched-for part
  inherited", it stays on the vouched connection; if it means "the empty key", it reads on the request's
  connection and B's policies hide it. No page states which; it is for Alf and Filip, and the answer
  belongs in `docs/architecture/reference/code-generation-triggers.adoc`. In version 1 there is no tenant
  column in an accepting graph, so no child is `UNSCOPED` there.

## Retired vocabulary

* `@issue(key:)`, its capture column and its refusals: retired before they were built.
* The inline name match in `graphitron_occurrence_column_rule` and the `COLUMN_UNRESOLVED` and
  ambiguous-column defect arms, replaced by joins onto the column-name relation.
* The hardcoded `fields: "id"` and `resolvable: true` arms of `graphitron_directive_application_arg_minted`,
  and its read of `graphitron_directive_application_minted`.
* `EntityResolutionBuilder`, its derivation of `KeyAlternative` from the classified model and its
  `@key` refusals, and the spelling-only id-contract test in `graphitron_minted_key_rule`.
* `Optional<tenantKey>` as the routing value in the `localContext` slot and in `OwnedConnections`.
* `TenantHandDown`, renamed `ConnectionKeyHandDown` when the slot came to carry a `ConnectionKey`.
* The vouch id as the vouched connection's group key, and the first client's two-field accepting key
  (`id eierId`): replaced by the vouched-for fields.
* The vouch cap: replaced by the one-value-per-operation limit.
* `claims:` on `@issue` and `@accept`, renamed `vouchesFor:`, with `graphitron_ast_issue_claim_entry`,
  `graphitron_ast_accept_claim_entry`, `graphitron_voucher_claim`, `graphitron_accept_claim`, their rules
  and the `VOUCHER_CLAIM*` codes; "claims" as the name of the voucher's access part.
* `MountedSession.vouch()`, `Vouch`, its per-vouch entries and `vouch().keys()` (the covered lookup keys):
  dropped before they were built.
* The verifier's check that vouchers sharing a vouch id agree on scope, caller and expiry, and its codec
  tests.
* The walk's stand-down for B's voucher field, which goes with the walk.
* The temporary refusals of `@issue` and `@accept` that slices 4 and 7 add and slices 6 and 8e remove,
  `VOUCHER_ISSUE_NOT_GENERATED` and `VOUCHER_ACCEPT_NOT_GENERATED`, and the "refused for now" sentences
  of their reference pages and directive definitions.
* `VOUCHER_ISSUE_TWICE`, folded into `VOUCHER_FIELD_REPEATED` in slice 7.
* The hardcoded `id` field and `NODE` role of `graphitron_key_field_binding_rule`'s minted arm, which
  reads the minted key's own field set from slice 7.
* The caller: the voucher's caller field, `Reason.CALLER`, the caller parameter of `SessionState.of` and
  of the issuers, the *Caller* term, and `VOUCHER_WITHOUT_SESSION_STATE` (slice 8b).
* The runtime's connection setup (slice 8c): the default `DataSource` and the `Map<TenantId, DataSource>`
  of `Graphitron.runtime(...)`, `SessionState` and `SessionState.of(mount, unmount)`, `MountedSession`
  and its `tenant()`, the generated `GraphitronSessionHook`, `PinnedConnection` as a public type (it stays
  as the carrier's package-private wrapper of a lease), `SessionMountException` (now
  `AcquisitionFailedException`), the runtime's `Source`, `tenantKeys()` and `acquireForTenant`, the
  facade's `sessionMountFailure` (now `acquisitionFailure`), the carrier's `mountFailure()`,
  `mountCause` and `isMountFailure` (now `acquisitionFailure()`, `acquisitionCause`,
  `isAcquisitionFailure`), jakarta-rest's `GraphitronApplication.mountCause` and `rejectedIdentity` and
  `AbstractGraphitronApplication`'s `mountCauseFinder` (now `acquisitionCause`, `failedAcquisition` and
  `acquisitionCauseFinder`), and the PostgreSQL `RESET ALL` backstop as Graphitron's duty (it lives in
  `ConnectionSources.mounting`); "mount" and "unmount" as Graphitron vocabulary, the source's own business
  from then on.
* "Multi-tenant build" and "single-tenant build" as runtime modes, and `Optional<tenantKey>` keying of a
  single-tenant carrier: every build keys by a `ConnectionKey` whose parts its schema decides.
* The request tenant set (`RequestTenants`, the factories' leading tenant-set parameter, the carrier's
  check against it, the fan-out domain as tenant map intersected with it), and the default tenant and
  entity default tenant the request names (slice 8c): the source decides tenants. With them
  `dslGlobal` and the global-read route, and the `@defaultTenant` directive, its classification marker,
  its dispatch arm and its reference page (2026-11-17).
* R1185, "The consumer chooses a query's connection in a hook that takes the whole connection key":
  absorbed here and discarded.
* Slice 8f: `@issue` and `@accept` on `FIELD_DEFINITION`; the author-written voucher field; the
  "marked field" and "issuing field" as terms; `graphitron_voucher_member.issuing_field` and its `CHECK`;
  the membership rule's stand-down for fields carrying a marker; the minted key's field set read off the
  marked field's name; the binding claim read off a field-level marker; `VOUCHER_FIELD_REPEATED`;
  `VOUCHER_ISSUE_ABSORBS_NOTHING`; the walk's stand-down read off the `VOUCHER` binding (it reads the
  mint now).

## Other solutions we've considered

* A consumer-written issue method and an opaque token the consumer parses: needs a hand-written resolver
  or method at each end and gives Graphitron nothing to check, so every consumer re-implements expiry,
  scope and coverage checks.
* A `@service` child in A that returns carriers with the voucher: works with no generator change, but puts
  a hand-written resolver on every issuing field, which is what the marker removes.
* Accumulating grants on the request's single connection: visible to every field and to consumer SQL, and
  needs a ledger table to be unforgeable. Routing removes the sharing instead.
* A routine per covered read shape (voucher as a routine argument): stateless, but each nested shape needs
  its own routine, which does not scale to deep table-bound reads.
* A transaction-local setting per DataLoader batch: scopes exactly and needs no extra connections, at the
  price of wrapping reads in transactions and extra round trips.
* One marker at both ends: the two acts differ (computing a token against accepting one), take different
  arguments, and fail at different build-time checks.
* A separate accept callback on `SessionState` beside the mount: a second callback with its own failure
  path and ordering against the mount, for what the mount already does once it reads the key.
* The caller read from a member of the mount's handle (`$session.<member>`, checked against
  `graphitron_session_handle_member`): no new `SessionState` parameter, but B would have to mount the
  request's own connection before it could verify, and a build without `<handle>` would have no caller.
* `@issue(key:)` as a list of `{field, column}` pairs: names A's columns in the SDL, which no other
  directive beside `@field`, `@table` and its kin does, needs a canonical string form per column type, and
  pushed the issuing type to publish nothing but `voucher`.
* `@issue(key:)` as an argMapping, `"id: resultatId"`, with a field of the issuing type named like B's key
  field refused and `@inaccessible` minted on the type's other fields: composes, but adds a new argMapping path root, a name rule that only covers the key `key:`
  names, and fields that exist on A's type yet no client can read.
* Absorbed fields classified as fields and hidden from the emitted schema: needs a population of modelled
  but not emitted elements, since every field binding hangs off `graphitron_element`.
* Covered lookup keys on the mount (`vouch().keys()`), so a policy could admit only the vouched rows:
  mixes navigation into the access decision, and a mount runs once while its entry is reused for later
  vouchers. A narrower grant is more in `vouchesFor:`.
* Allowing only fields B also defines on the issuing type: the build cannot check that from A.
* A second key `@key(fields: "id voucher")` beside the plain one, with A publishing `id`: a router can
  satisfy B's plain key from A as well, and a plan that takes it denies quietly under RLS.
* B's protected fields declaring `@requires(fields: "voucher")` with the voucher `@external`: makes the
  requirement explicit, but composition must satisfy it from every path to those fields, and A, the only
  subgraph with the voucher, cannot be entered to fetch it.
* `@issue(key:)` as an optional `@key`-style field set defaulting to every other field: every non-voucher field must be absorbed, so an explicit `key:` could only restate the default.
* Excluding `issue` and `accept` from the column rule by directive name, as `service`, `routine` and
  `pivot` are: another exception inside the rule rather than a fact a reader can query.
* Standing the node key down for an issuing type instead of refusing a node issuing type: keeps a type
  whose bare `id` means an encoded node id as a field and a raw column as a component.
* A component relation carrying the resolved column as required, read by the anti-joins: a field whose
  column fails to resolve would return to the element set and be published.
* An `EmittedRegistry` removal arm for anything with no `graphitron_field` row: turns a capture defect
  into a silent deletion.
* `ConnectionKey` only in builds with `@accept`: every site that writes or reads the slot evaluates the
  same predicate.
* The codec and its types in `graphitron-jakarta-rest`: a consumer that verifies a voucher would take on
  an HTTP serving library, and generated code would depend on Graphitron at runtime.

* Graphitron taking the connection and the consumer mounting on it, the key read off `MountedSession`
  (the first build of slice 8): the consumer cannot choose the pool for a key, so tenancy needs a
  Graphitron-owned tenant map beside a default source, and vouchers a second mode beside it.
* `ConnectionKey` as a sealed hierarchy, one arm per combination of parts: makes a half-present key
  unrepresentable, but the arms are the cross product of the parts and every tenant read becomes a
  pattern match. A record with one `Optional` per part grows by one component per part.
* A nested `VouchedFor` record inside the key: a third shape beside the two above, with nothing either
  lacks.
* The lease's `abort()`, for a connection something may still run on: that race is Graphitron's own (a
  timed-out fan-out worker, a pin completing after release), so Graphitron stops the worker itself
  before releasing and the consumer never sees it.
* A `DataSource` per key as the source: the right semantics, too wide an interface for a lambda.
* The session handle reached through JDBC's `Connection.unwrap`: makes the consumer wrap every pooled
  connection to answer it, bends `unwrap`'s purpose, and fails at the first `$session` read rather than
  at compile time.
* A typed request context Graphitron carries to the source (`acquire(key, request)`, its type named in
  the POM): the closure the consumer builds per request captures the same, with no configuration.
* The voucher verification emitted inline into the dispatcher, per accepting type: reachable only
  through a query, and the verified token had to travel inside the representation map.
* Keeping the caller binding: within one supergraph operation the caller cannot change, so the binding
  guarded only reuse of a captured voucher across operations within its expiry.

* Graphitron keeping the request tenant set and checking a key's tenant part against it: an
  authorization the consumer already holds, kept as a tenancy-shaped factory parameter in one kind of
  build. The source decides.
* The fan-out domain given on the request, or read off a fanned field's argument alone: the first puts
  a tenancy-shaped parameter back on the request, the second covers only domains the client names.

* Every throw from `acquire` rejecting the request, a refused tenant included: one disallowed tenant
  would fail a whole request that today answers it with a field error, and would tell a prober the key
  exists.
* A fan-out over an empty default domain, or a default `tenants()` that throws only when a fan-out
  runs: the first answers a misconfiguration with a quiet empty list, the second finds it at run time.
  A tenanted build requires the domain at compile time instead.
* The ready-made source after the breaking change: a consumer's migration would have nothing to move to.

* A per-request verification loop over every representation before any is resolved, in the codec or a
  fixed unit beside it: the DataLoaders' own timing already keeps acquisition after every check, and the
  limit belongs where connections are taken. The loop would have been a second batching beside the
  loaders 8e needs anyway.
* Keeping the synchronous `resolveByReps` for builds without vouchers and loaders only where vouchers
  are: two ways to read an entity, one of them used by a minority of builds.

* The markers on a voucher field the author writes (slices 4 to 8e.2 as shipped): A and B each choose the
  field's name and type and neither build can check the other's, the membership needs an exception for
  the marked field, and two markers on one type need a refusal of their own.
* The voucher field as an arm of the connection field mint (`graphitron_field_minted_candidate`): its
  `position` and `carrier_coordinate` are the connection's, and its rows stand down to an authored field.
* An authored field at the minted name standing the mint down, as a declared connection type does: on A
  it would be absorbed, bringing back the membership exception, and the mint would be silently withheld.
* A `field:` argument naming the minted field: an override A and B must again agree on, unchecked.
* `_voucher` as the minted name, which first shipped: it reads as federation machinery beside `_entities`
  and `_service`, but those come from the federation spec and Apollo GraphOS linting exempts them, while
  `_voucher` is ours and fails `FIELD_NAMES_SHOULD_BE_CAMEL_CASE` in a consumer's subgraph checks.
* `voucher` as the minted name: camelCase, but a domain with vouchers of its own would have its field
  refused as `VOUCHER_FIELD_NAME_TAKEN` on an accepting type.

## Reviewer findings

### Round 1: Spec → Ready (session_01WkW2JFv9qFDgNA4RommHSq, 2026-11-09)

Verdict: withhold. The goal reads without the step list and the design extends shapes already in the
tree rather than standing beside them: checked against trunk, `NodeIdEncoder`, `TenantHandDown`,
`LocalContextOwnershipGuardTest`, `OwnedConnections`, `MountedSession` (`tenant()` only in a
`<tenantColumn>` build), `SessionMountException` (thrown out of `GraphQL.execute`, first rejection
remembered for the operation), `graphitron_directive_application_minted` and its arg relation,
`graphitron_federation_link_import_rule` (imports every minted directive the link's version allows, so
`@inaccessible` needs no change), `graphitron_ast_node_id_entry`, `store_graph_session_supplied` and
the "a rolled-back mutation field is `null`" rule all exist as described. The dispatcher already picks the
most-specific resolvable key alternative (`federation-keys.adoc`), so a `{id, voucher}` representation
does reach the voucher alternative once it arrives. Two blocking findings, both on question one, and two
smaller ones.

**Finding 1 (question one: viability). Nothing makes the router use the voucher key, and if it does not,
the feature fails silently.** Step 2 says the router sends `{id, voucher}` to B. But B publishes two
resolvable keys, the authored `@key(fields: "id")` and the minted `@key(fields: "id voucher")`, and A can
supply the fields of both. The query planner may take either key for the jump from A to B, and the spec
keeps the plain key on purpose ("the type still resolves by plain `id`"). If the planner takes `id`, B
resolves the entity under the caller's own RLS, the policy hides the rows, and the client gets `null` or
missing entities with no error. That is the quiet denial the spec rules out for a bad voucher ("fails the
request ... nothing in that request is answered"). The execution test runs A and B in one fixture with no
router, so it feeds B the representation the spec assumes and cannot catch this. The spec needs to do one
of two things. Either show how the planner chooses between the two keys (documented composition or
query-planner behaviour, plus a test that plans a query over the composed supergraph of the two fixture
subgraphs and asserts the voucher key is used). Or change the design so the plain key cannot carry the
jump for vouched data, for example by having B's protected fields require the voucher
(`@requires(fields: "voucher")` with the field `@external`) rather than relying on key choice. Which one
it is changes what gets built.

> Response: the voucher carries B's key values and A's issuing type publishes nothing but `voucher`, so
> `@key(fields: "voucher")` is the only key of B a plan from A can satisfy. The guarantee is structural,
> pinned by the served-SDL test on A. `@requires` was not taken: A's type is not resolvable, so
> composition could not satisfy a requirement on B's fields from B's other entry points.

**Finding 2 (question one: viability). The minted key's `resolvable` is unspecified, and in A it must be
`false`.** In the example, A's authored key is `@key(fields: "id", resolvable: false)`. The spec says
the minted key is "the authored key's fields plus the marked field" and says nothing about `resolvable`.
If it defaults to `true`, A claims to the supergraph that it can resolve `VgsResultat` by `id voucher`.
`EntityResolutionBuilder` then wants a SELECT path for that key on A's side, and A has nothing to resolve
`voucher` from: on A the field is computed by signing, not decoded from a representation. State that the
minted key copies the authored key's `resolvable` (so `false` in A, `true` in B, through
`graphitron_directive_application_arg_minted`). Also say which authored key it extends when a type has
more than one. "Either marker on a type with no authored `@key`" covers zero keys, not two. Add the
reference-only case to the pipeline test that already names "a reference-only row type".

> Response: the minted key's `resolvable` comes from the role, `false` for `@issue` and `true` for
> `@accept`. In A it is the type's only key, and A takes no authored `@key`. In B, `@accept(key:)` picks
> the authored key the voucher decodes to, required when there are several.

**Finding 3 (question one: viability). The signed form of the key fields is undefined across the two
sides.** A signs "the row's key fields in their own scalar form", read from its column
(`vgs_resultat_id`, whatever its jOOQ type). B "recomputes them from the representation", which is JSON
off the wire, so for an `ID` it is a string. If A signs the column's Java value and B the decoded
representation value, verification fails for every row whose column type does not print the same way as
its wire form (an integer key under `ID`, a UUID, a composite key's field order). Specify one canonical
input, presumably each key field's serialized GraphQL value in key-selection order with the type name, and
add a codec or execution test with a non-`String` key column.

> Response: the token carries each value in GraphQL's wire form, as A's field serializes it, and B
> parses it with its key field's scalar (*Voucher codec*); codec tests cover integer, UUID and two-field
> keys.

**Finding 4 (question one, non-blocking). Close the per-row signing question rather than leaving it open.**
The voucher is a field: it is computed only when selected, and the router selects it only when it plans
the voucher key. So "sign lazily only for rows the router actually asks the key of" is already what the
fetcher does, once Finding 1 is settled. Whether a ceiling is still wanted is then a small decision, and
better made here than at implementation time.

> Response: closed. A row is signed only when `voucher` is selected, which follows the router's plan, and
> the parent's page size bounds it; no further ceiling.

Notes, not blocking: the docs draft should say that both subgraphs must put the same string on
`SessionState.of` as the caller (for example the token subject), since router header propagation is out
of scope and a mismatch shows up as every voucher being rejected. A sentence on clock skew between A's
expiry and B's check (a tolerance, or none on purpose) would answer a question the implementer will
otherwise answer alone.

> Response: the docs draft says both subgraphs must pass the same caller; B tolerates 30 seconds of clock
> skew (*Clock skew*).

### Round 2: Spec → Ready (session_01WkW2JFv9qFDgNA4RommHSq, 2026-11-10)

Verdict: withhold, on one finding introduced by the post-round-1 change. The four round-1 findings are
settled. Finding 1: B's plain key is now unreachable from A by construction. The voucher carries B's key
values, and the build refuses a field on the issuing type named like a `key:` target. Finding 2: the
minted key's `resolvable` comes from the role, A takes no authored key, and `@accept(key:)` picks among
several. Finding 3: the values travel in GraphQL's wire form, with codec tests over integer, UUID and
two-field keys. Finding 4: closed. The new argMapping reuse checks out against the tree: `ArgMappingSigil`,
`graphitron_ast_argmapping_pair_entry`, `graphitron_argmapping_site` and `graphitron_argmapping_pair`
exist. The new path root (fields of the enclosing type) is named as a new root for both path resolution
and LSP completion.

**Finding 5 (question one: viability). An ordinary field on the issuing type may make the supergraph fail
to compose.** After the 2026-11-10 change, A's `VgsResultat` keeps fields like `resultatId`, which exist
only in A, on a type whose only key in A is `resolvable: false`. The spec expects B's entity to be reached
from elsewhere too ("still resolves by plain `id` from anywhere else"). Federation 2 composition checks
that every field of the supergraph can be resolved on every path that reaches its type. A query that
enters `VgsResultat` through B (a B root field, or a third subgraph's jump on B's `id` key) and asks for
`resultatId` cannot be planned: B has no such field, and no key lets the planner enter A. As I understand
federation, composition rejects that as a satisfiability error. I could not confirm this against the
Apollo docs from this session, because the host is outside its network allowance. Whether marking such a
field `@inaccessible` exempts it is a second point I could not settle. The round-1 answer, where the
issuing type published only `voucher`, did not have this problem, since `voucher` exists in both
subgraphs. The served-SDL test cannot catch it, and the spec states that no composition tool is in the
build. The spec needs to settle the federation behaviour and then pick a rule. One option: the issuing
type publishes only `voucher` and fields B also defines, which the build cannot check from A but the
how-to can state. Another: ordinary fields on the issuing type are refused, or minted `@inaccessible` if
that is shown to satisfy composition. A third: a documented one-off composition of the fixture's two
subgraphs, recorded in the item, as the evidence in place of a build-time test. The example in the Goal
should match whichever rule is chosen.

> Response: answered with a redesign, with the user (2026-11-10). The voucher absorbs the issuing type's
> other fields, decoded as voucher components rather than fields, so A publishes `voucher` alone: row 4
> of the table below. This retired `@issue(key:)` as an argMapping, the name rule and `@inaccessible`
> minted on ordinary fields before they were built. The principles architect was consulted on the
> redesign: a field classified but not emitted would need a second population axis, so the declarations
> are a stored membership the element set anti-joins (*Voucher membership and components*);
> `EmittedRegistry` gains a removal arm rather than the walk skipping fields (*The readers*); the
> refusals are defect codes owned by the stage whose facts they read (*Rejections*).

Notes, not blocking: the "Response to review round 1" entry for Finding 3 still says B coerces "by its
key columns' types" in "one canonical form". The body and the later paragraph now say B parses with its
key field's scalar from the wire form, and the stale sentence could mislead an implementer. `@issue(key:)`
targets are field names, so a B key with a nested selection (`@key(fields: "org { id }")`) is neither
supported nor listed among the rejections; one line saying which would close it.

> Response: the stale round-1 sentence is gone with the response section. `@accept` on a key with a
> nested selection is refused, since absorbed fields are top-level scalars (*Rejections*).

Addendum to round 2 (same session, 2026-11-10): the two federation points above, checked against Apollo's
own composition and query planner (`@apollo/composition` and `@apollo/query-planner` 2.14.4, federation
v2.5 link). Each case composes two subgraphs, A with `Query.soknad` and `Soknad.vgsResultater: [T!]!`,
and plans `{ soknad { vgsResultater { fag } } }`:

| A's `T` / B's `T` | Composes | Jump A → B uses |
|---|---|---|
| No `@key`, field `id @shareable` / `@key(fields: "id")` | yes | `id` |
| `@key(fields: "voucher", resolvable: false)` and a field `id @shareable` / keys `id` and `voucher` | yes | `id` |
| The round-0 design: keys `id` and `id voucher`, both `resolvable: false` / keys `id` and `id voucher` | yes | `id` |
| `@key(fields: "voucher", resolvable: false)`, only `voucher` / keys `id` and `voucher`, B has `Query.t: T` | yes | `voucher` |
| As above plus `resultatId: ID!` (the current Goal example) / same B | no: `{ t { resultatId } }` is unsatisfiable | (does not compose) |
| As above with `resultatId: ID! @inaccessible` / same B | yes | `voucher` |
| `voucher` and `resultatId` / B with no root path to `T` | yes | `voucher` |

So: a field of A's type that is not in A's own `@key` does carry a jump on B's key (rows 1 and 2), which
is why the name rule is needed. Without `@shareable` the same `id` fails composition as a non-shareable
field resolved from both subgraphs, but that check catches it only by accident. Round 1's Finding 1 held
(row 3: the planner took the plain key). Finding 5 holds as soon as B can reach `T` any other way (row 5),
and `@inaccessible` on A's ordinary fields is enough to make the supergraph compose (row 6). Minting
`@inaccessible` on every ordinary field of an issuing type is therefore a working rule for Finding 5. It
costs those fields' visibility to clients, which then read the values from B through the voucher.

### Round 3: Spec → Ready (session_01WkW2JFv9qFDgNA4RommHSq, 2026-11-10)

Verdict: sign off. Finding 5 is answered by construction. A's issuing type publishes `voucher` alone, the
case the round-2 table shows composing with the jump on `voucher` while B also reaches the type from its
own root field. That one guarantee settles both federation questions, and the Tests section pins it in the
served SDL and the runtime schema. It also requires the implementer to repeat the composition check
against the fixture's two served SDLs before Done, which is the right evidence when no composition tool is
in the build. The goal still reads without the step list.

On fit, the absorbed declaration is a real addition: an authored field that never becomes an element. The
spec owns that openly. It decides at the decode (a stored component relation, keyed on
`graphql_field_element`), not with a hidden-field axis. It orders the stage before `graphitron_element`,
gives `EmittedRegistry` a removal arm instead of a branch in the walk, and sends the one possibly-new
coordinate-free column rule to Alf and Filip before it is written. Checked against the tree:
`graphitron_element` is a table and `graphitron_element_authored` a view over `graphql_element`.
`graphitron_directive_application_authored` joins `graphitron_element`, so absorbed fields' `@field`
applications drop by construction. `EmittedRegistry`'s javadoc says "Nothing is removed", so the removal
arm is new, as the spec says.

Two notes for the implementer. Both are plan edits in In Progress, not a reopen:

* *The part sets read the transcription, not the element.* `graphitron_field_authored` (and
  `graphitron_argument_authored`) select from `graphql_field` directly, and `EmittedAnchor.fields`
  inserts them into `graphitron_field`, whose foreign key references `graphitron_element`. An absorbed
  declaration anti-joined out of the element set alone would fail that key. Both part sets need the same
  stand-down. `graphitron_field_authored` already has the shape, its `NOT EXISTS` against
  `graphitron_connection_carrier`, and its `meta_relation` description should gain the new reason. "Everything built
  on the emitted population drops the declarations by construction" is true of the directive applications,
  not of these two.
* *`@issue(key:)` can only restate its default.* Every field of the issuing type must be the voucher or
  absorbed, and a field that is neither is refused, so an explicit `key:` either names every other field
  (the default) or is refused. Drop the argument, its capture column and its two refusals and tests, or
  give it a case where it selects something the default does not.

> Response: both taken. The part sets anti-join the membership beside their
> `graphitron_connection_carrier` stand-down. `@issue(key:)` is dropped, with its capture column, its
> refusals, and the refusal of a field neither voucher nor absorbed, which cannot occur. At take-up the
> principles architect raised further points and the user ruled on them (2026-11-10), which returned
> the item to Spec for round 4: the `VOUCHER` binding kind, refusing `@issue` on a node type, the
> column-name relation, membership split from resolution, minted keys stored, the entity key
> alternatives stated, `ConnectionKey` wherever the slot is written, and the codec generated rather than
> shipped.

Not blocking: the docs draft could add that the absorbed fields must be named like the other subgraph's key
fields, since A cannot check that and a mismatch shows up as every voucher being rejected.

### Round 4: Spec → Ready (session_01WkW2JFv9qFDgNA4RommHSq, 2026-11-10)

Verdict: sign off. The goal, the runtime flow and A's published shape are as signed off in round 3. What
changed is the plan beneath them, and each change replaces a branch or an inline copy with a stated
relation, which is the direction the architecture docs ask for. Both round-3 notes are taken: the part
sets anti-join the membership, and `@issue(key:)` is gone. Splitting membership from component resolution
closes a hole round 3 missed: a field whose column failed to resolve would have fallen back into the
element set, and A would have published `id`. The slices each land a refactor or a gated unit, so trunk
stays green with no marker in use.

Checked against the tree:

* `fact-model.adoc` carries both 2026-11-10 rulings the spec leans on: the column name stated once in the
  catalog family, and a binding kind of its own for a directive-computed field.
* `graphitron_directive_application_arg_minted` reads `graphitron_directive_application_minted` and
  hardcodes `fields: "id"` and `resolvable: true`, as the spec says.
* `graphitron_occurrence_column_rule`, `graphitron_occurrence_binding`, `graphitron_binding_claim`,
  `COLUMN_UNRESOLVED`, `TenantHandDown.none()`, `SessionIdentityRows`, `NodeIdEncoderClassGenerator`,
  `ClassificationDomainCapture` and `StageRosterGateTest` exist.
* R876's graph marks "Entity resolution is stated" as taken by this item, and R1129 already names
  `ConnectionKey`, so the scope moved here is recorded on both sides.

Two notes for the implementer, both plan edits in In Progress:

* *The voucher stage follows `nodes`, not just `table-types`.* The node-type refusal reads
  `graphitron_node`, which the `nodes` stage writes (position 6 in `meta_stage`, after `table-types` at 5),
  and the refusal is owned by the voucher stage. So the stage sits between `nodes` and `emitted`, and
  `graphitron_node` belongs in the list of relations it reads.
* *Name the owning stage of the two new stored relations.* The minted-key relation needs `graphitron_node`
  and the federation link for its `NODE` rows and the voucher stage's facts for its `ISSUE` row. The entity
  key alternatives carry per-field column bindings, so they follow `binding-claims`. Each owes an owner
  in `meta_relation`, and the `@accept` refusals the spec gives to "the stage that writes the entity key
  alternatives" need that stage to have a name and a position `StageRosterGateTest` can check.

> Response: both taken at In Progress. The voucher stage runs after `nodes`, and the minted keys
> (`minted-keys`) and the entity key alternatives (`entity-key-alternatives`) each have a named owning
> stage with a stated position.

### Round 5: slice 8a take-up (session_01WkW2JHwmdHjyuEcZChjpE6, 2026-11-16)

> Note: the slices were renumbered by the redesign of 2026-11-16. This round's "slice 8a" is today's
> 8a to 8d, and its "slice 8b" is today's 8e.

A second-party read of the four plan commits of 2026-11-16 against trunk, before slice 8a is built. The
design changes rest on Alf's rulings of that day; the text is checked here. Verified: `resolveByReps`
resolves type after type, each acquiring as its groups decode, so verification in `fetchEntities` is
needed; `VoucherBindingQuery` reaches a voucher field through A's members, so B's field drops out of it;
`ConnectionKeyShape.writesSlot()` is what `ConnectionRuntimeClassGenerator` calls multi-tenant; and
`graphitron_producer` already states which fields are taken out of the parent's statement. Corrected
in the text: slice 8b's gate named an R876 node that does not exist, and needs no tenant-routing node
in version 1; a failed check fails the request only through the carrier and the instrumentation, as a
rejected mount does, which slice 8a had not named; the tenant-column refusal's relation is captured
configuration, so the hedge is settled; and the entity rows' `localContext` stamping moves to 8b
(*Connection key*). The principles architect's points at take-up are folded in: the rejection is the
carrier's invariant through one supertype, the shape answers three questions apart, the voucher
alternative pairs with its decode-onto key by identity, and the narrowed refusal reads occurrences and
allows what is stated projected.

### Round 6: the connection contract (2026-11-16)

The redesign of 2026-11-16 (the connection contract, no caller, tenants to the source) was read by an
author of R876, the item whose carrier, fan-out and connection relation it reshapes, and by the
principles architect, whose take-up read is folded into the plan. The R876 author's recommendations
were ruled on and taken: the typed refusal `KeyNotPermitted`, a tenanted source's required fan-out
domain through `ConnectionSource.of(acquire, tenants)`, and the ready-made source shipping with the
contract; and they filed R1192, a fan-out worker releasing its lease when it finishes, on this
contract. Alf judged the two reads sufficient to proceed with slices 8b to 8d (2026-11-16).
