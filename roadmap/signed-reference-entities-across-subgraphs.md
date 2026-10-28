---
id: R1119
title: "Signed voucher handover: one subgraph vouches, another grants through RLS"
status: Spec
bucket: feature
priority: 3
theme: service
depends-on: []
created: 2026-10-28
last-updated: 2026-10-28
---

# Signed voucher handover: one subgraph vouches, another grants through RLS

## Goal

A subgraph can vouch for an access decision to another subgraph, and the receiving subgraph's database
turns that into data access, at any query depth, without the grant leaking beyond the entities it was
issued for, and without either side writing a resolver. The two ends are different acts and get different
markers. In subgraph A, which knows the application and the caseworker, a field marked `@issue` makes
Graphitron attach a *voucher* (a signed, short-lived token) to each reference it returns; reaching those
rows under A's own access rules is the decision being vouched for. In subgraph B, which owns the data, the
matching key field is marked `@redeem`. B uses the voucher to take a *vouched connection*: a database
connection mounted with the caller's identity plus the voucher, so B's row-level security (RLS: Postgres
policies deciding per row what a connection may read) admits exactly what the voucher covers. Every field
below that entity reads through the same vouched connection and nothing else does. The same voucher
presented again in the operation gets the same connection; a different voucher gets another. A rejected
voucher makes that entity `null` and no SQL on the covered tables runs. Graphitron owns the minting call
site, the key shape, the routing and the seam; the consumer owns the signing key, the verification, and
what the session state means to the policies.

```graphql
# Subgraph A: the issuer. VgsResultat is A's own table of references to results; `id` is the result's id.
# Graphitron computes `voucher` per row, the way it computes a @nodeId; no service.
type Soknad @table(name: "soknad") @key(fields: "id") {
  id: ID! @nodeId
  vgsResultater: [VgsResultat!]! @reference(path: [{key: "soknad_vgs_resultat_soknad_fkey"}])
}
type VgsResultat @table(name: "soknad_vgs_resultat") @key(fields: "id", resolvable: false) {
  id: ID! @field(name: "vgs_resultat_id")
  voucher: String! @issue(scope: "vgs-resultater", subject: "fodselsnummer", ttl: "PT5M")
}

# Subgraph B: the owner. The real, table-bound entity. `voucher` is the proof field: not a column, not selected.
type VgsResultat @table(name: "vgs_resultat") @key(fields: "id") {
  id: ID! @nodeId
  voucher: String! @redeem(scope: "vgs-resultater")
  fag: String!
  karakter: Int!
  detaljer: [Fagdetalj!]!          # nested table-bound children, read under the same vouched connection
}
```

What both subgraphs publish is derived, not written. Graphitron rewrites every `@key` on a type that has a
`@issue` or `@redeem` field so it gains that field (`@key(fields: "id voucher")`) and puts `@inaccessible`
on the field, importing `@inaccessible` into the `@link` as it does for the other directives it applies.
The author writes neither, and A and B cannot disagree about the key shape because both derive it from
their marker. Because every authored key gains the voucher, the type is reachable from other subgraphs
only with one; a type that must also resolve by plain `id` under the caller's own RLS needs the escape in
the open questions.

## How it works

1. *Issue (A).* A's field is an ordinary read; the `voucher` column of each returned row is not selected
   but computed. For each distinct `subject` value among the rows the operation returns (a column of the
   row, here the applicant's id number), Graphitron calls the configured issue method once with
   `(scope, subject, expires)` plus whatever identity payload the method declares, and writes the token
   into every row with that subject. `expires` is the operation's start plus `ttl`, fixed per operation,
   so a deterministic signature gives every row of one subject the same token. The decision is the read
   itself: a row A's rules hide never gets a voucher.
2. *Carry.* The router fetches the key fields from A and sends `_entities` representations `{id, voucher}`
   to B. The voucher stays out of the client schema because the field is `@inaccessible`.
3. *Route (B).* The dispatcher resolves the representations through the voucher-bearing key and groups them
   by voucher value. For each distinct voucher the runtime takes a connection keyed by
   `(tenant, voucher)`, runs the ordinary mount (identity from the caller's JWT), then the consumer's
   redeem method with the voucher and the field's `scope`. The redeem method verifies the token (in the
   database with an HMAC check, or in Java before calling a routine), checks scope, expiry and that the
   user matches the mounted identity, and sets session state for that connection.
4. *Reject.* If the redeem method reports the voucher invalid, the connection is unmounted and released and every
   entity carrying that voucher is `null`. A rejected identity still fails the whole request.
5. *Read below.* The group's SELECT runs on the vouched connection, and each resolved row is returned as a
   `DataFetcherResult` whose `localContext` carries the routing key. graphql-java hands that value to
   every field below, so child acquisitions and their DataLoader batches are keyed by it; a batch never
   mixes two vouchers and a field outside the subtree uses the base connection, as tenants already do.
6. *Release.* At operation end every vouched connection is unmounted and released.

A cap on vouched connections per operation fails the excess entities closed instead of exhausting the
pool. How many connections a query takes follows from the issue side: one per distinct subject.

## What exists today, and what is missing

| Need | Today | Gap |
|---|---|---|
| Computed field on a table-bound row | `@nodeId` and other computed projections are emitted into the select list. | `@issue` is a computed, non-column projection whose value comes from the issue method; rows are grouped by subject so the method is called once per distinct subject per operation. |
| Signing | None, and Graphitron should not hold keys. | A top-level `<voucher>` block naming an `<issue>` method, read at build time like the mount: extra parameters become contextArguments on the factory, and it may be a jOOQ-generated routine method so A has no Java at all. |
| Per-subtree routing value | The tenant rides down an entity's subtree in `localContext`; loaders and acquisitions read it. | The slot is reserved for the tenant and a guard test keeps every other writer out. It becomes a small routing record (tenant, voucher), and the guard, the loader naming and the acquisition path read both parts. |
| One connection per routing key | Per-tenant acquisition, lazily, one mount per connection, unmount at release. | Key the acquisition by `(tenant, voucher)`; a redeem step after the ordinary mount; the per-operation cap. |
| Entity with a non-column key field | Every `@key` field must resolve to a column. | A `@redeem` field: in the key, in the SDL, excluded from the SELECT and projection; a key with it must also name at least one column field. |
| Schema alteration from one marker | `@node` types get a synthesised `@key`; applied federation directives are imported into the `@link`. | A synthesiser that adds the marked field to every `@key` on the type and applies `@inaccessible`, same pass, same import handling. A marker on a type with no `@key` is rejected. |
| Verification | None. | `<voucher><redeem>` method: receives the connection configuration, the voucher and the scope, and, by type, the mount's handle; reports accepted or rejected. Same session-scoped contract as the mount. |
| Reference-only stub on a table | Accepted. | An `@issue` row type keeps `@key(resolvable: false)`; reject `@issue` on a resolvable key. |
| Children of the entity | Table-bound children run through generated fetchers that honour the inherited routing. | Verify that a `@service` child and a connection-typed child under the entity also acquire from the inherited key. |

## Implementation

* *Directives.* `@issue(scope:, subject:, ttl:)` on a field of a table-bound, reference-only type (A);
  `@redeem(scope:)` on a field of a table-bound, resolvable type (B). Graphitron defines both, as it
  does `@node`; consumers do not write the definitions. `subject` names a column of the same row. Reject:
  either marker on a type with no `@key`, on a field that is also a column, both markers on one type, and
  `@redeem` in a build with no `<redeem>`, `@issue` in a build with no `<issue>`.
* *Key synthesis.* Beside the node-key synthesiser: each `@key` on a type with a marked field is extended
  with it, the field gains `@inaccessible`, and both show in the served `_service.sdl`. A `@node` type's
  synthesised id key is extended the same way, which needs the node-id alternative to accept a proof field
  beside `id`.
* *Issue.* The select list for an `@issue` row computes the token after the rows are read, grouped by
  subject, calling the configured method once per distinct subject. Expiry is fixed per operation.
* *Routing record.* Replace the bare tenant in the `localContext` slot with a record carrying the optional
  tenant and the optional voucher. `TenantHandDown` becomes the one writer of the record; the ownership
  guard test is updated. Single-tenant, voucher-free builds emit exactly what they emit today.
* *Acquisition.* The runtime's acquire path takes the routing record; a record with a voucher takes a
  separate connection, runs mount then redeem, and remembers the outcome per key so a repeat
  presentation reuses the connection or the rejection.
* *Entity dispatch.* `EntityResolution` gains a key alternative carrying a proof field. The generated
  handler groups by voucher, acquires, runs the batched SELECT on the vouched connection, stamps the
  routing record on each result, and answers `null` for rejected vouchers.
* *Configuration.* `<voucher><issue>fqcn#method</issue><redeem>fqcn#method</redeem></voucher>`, each
  optional, each read at build time. `<redeem>` also requires a `<sessionState><mount>`.
* *Redaction.* The voucher is a credential: confirm generated code and the dispatcher never log
  representations or echo the voucher in errors.
* *Out of scope.* Key management and rotation, the reference redeem routine and policies (a worked example
  goes in the how-to), router header propagation, a per-row decision beyond A's own read.

## First-client docs draft (for `federation-keys.adoc` and the security guide)

"To let another subgraph read data your user's token does not cover, mark the field that returns the
reference `voucher: String! @issue(...)`. Graphitron attaches a signed token to every row your own rules
let the caller see. In the subgraph that owns the data, mark the same key field `@redeem(...)`. Graphitron
takes a separate database connection for each distinct token, runs your redeem method on it, and reads
that entity and everything below it through that connection. Nothing outside those entities can use it,
and it is released when the request ends." If this does not read simply once the spellings are chosen,
the design is wrong.

## Tests

* Pipeline tier: classification of both markers and each rejection above; the synthesised key and
  `@inaccessible` on a table-bound entity and on a reference-only row type; the routing record in emitted
  fetchers; single-tenant output unchanged.
* Compilation tier: generated issue, dispatcher and acquisition code compiles at Java 17.
* Execution tier (real Postgres, RLS fixture beside the existing session-mount fixture, A and B in one
  fixture): rows of one subject get one token and the issue method runs once for them; a valid voucher
  exposes the entity and a three-level nested read under a policy that otherwise hides the rows; the same
  voucher twice takes one connection and two vouchers take two; a sibling entity or a field outside the
  subtree cannot read the first voucher's rows; tampered, expired, wrong-scope and wrong-user vouchers
  give `null`; the cap fails the excess closed; connections are unmounted and released at the end.
* Served SDL: `_service { sdl }` shows `@key(fields: "id voucher")` and `@inaccessible` on the voucher
  field on both sides, with the `@link` importing `@inaccessible`.

## Open questions

* Names: `@issue` and `@redeem` name the two acts; `@issue` is a generic word for a directive in an SDL
  that may carry other meanings, so check it against the directive vocabulary before it ships.
* Strict or additive: every authored key gains the voucher (recommended, nothing is reachable without one),
  or the type keeps its plain keys. If a type needs both, `@issue`/`@redeem(keepPlainKeys: true)` would
  add the voucher key beside the authored ones.
* A `@node` type: does the voucher join the relay id key, and does `Query.node` still resolve the id
  without one?
* The issue method's identity payload: the caseworker comes from a contextArgument (as mount payloads do)
  or from the mount's handle; recommendation is the same by-type recognition the mount uses.
* The redeem method's result: boolean (recommended), with exceptions evicting the connection and erroring
  the entities.
* Cap default and whether it is per operation or per runtime.
* Multi-tenant: confirm a voucher cannot widen the tenant set the request may touch.
* Is `subject` always a column of the row, or does the stub sometimes need a value from the parent?

## Other solutions we've considered

* A `@service` child in A that returns carriers with the voucher: works with no generator change, but puts
  a hand-written resolver on every issuing field, which is what the marker removes.
* Accumulating grants on the request's single connection: visible to every field and to consumer SQL, and
  needs a ledger table to be unforgeable. Routing removes the sharing instead.
* A routine per covered read shape (voucher as a routine argument): stateless, but each nested shape needs
  its own routine, which does not scale to deep table-bound reads.
* A transaction-local setting per DataLoader batch: scopes exactly and needs no extra connections, at the
  price of wrapping reads in transactions and extra round trips.
* One marker at both ends: the two acts differ (computing a token against redeeming one), take different
  arguments, and fail at different build-time checks.
