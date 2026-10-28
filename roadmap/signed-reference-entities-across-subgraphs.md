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
issued for. Subgraph A, which knows the application and the caseworker, hands subgraph B a *voucher*
(a signed, short-lived token) inside the federation key of a result entity. B uses the voucher to take a
*vouched connection*: a database connection mounted with the caller's identity plus the voucher, so B's
row-level security (RLS: Postgres policies deciding per row what a connection may read) policies admit
exactly what the voucher covers. Every field below that entity reads through the same vouched
connection; fields not below a voucher-carrying entity never touch it. The same voucher presented again
in the operation gets the same connection, a different voucher gets another. A rejected voucher makes
that entity `null` and no SQL on the covered tables runs. Graphitron owns the routing and the seam; the
consumer owns the signing key, the verification, and what the session state means to the policies.

```graphql
# Subgraph A: the stub. `id` is A's own reference to the result; the voucher is minted once per
# parent (so all of one application's results share it) by an ordinary @service child.
type Soknad @table(name: "soknad") @key(fields: "id") {
  id: ID! @nodeId
  vgsResultater: [VgsResultat!]! @service(service: {className: "no.sikt.soknad.VgsHandtak", method: "utsted"},
                                          contextArguments: ["saksbehandler"])
}
type VgsResultat @key(fields: "id voucher", resolvable: false) {
  id: ID!
  voucher: String! @inaccessible
}

# Subgraph B: the real, table-bound entity. `voucher` is a proof field: part of the key, not a column.
type VgsResultat @table(name: "vgs_resultat") @key(fields: "id voucher") @key(fields: "id") {
  id: ID! @nodeId
  voucher: String! @inaccessible @proof
  fag: String!
  karakter: Int!
  detaljer: [Fagdetalj!]!          # nested table-bound children, read under the same vouched connection
}
```

The plain `@key(fields: "id")` alternative stays available: a representation without a voucher resolves
on the ordinary connection under the caller's own RLS, and is denied unless their JWT covers the row. A
voucher only ever adds access.

## How it works

1. `_entities` receives representations `{id, voucher}`. The dispatcher's most-specific-first rule picks
   the voucher alternative and groups the representations by voucher value.
2. For each distinct voucher, the dispatcher asks the runtime for a vouched connection, keyed by
   `(tenant, voucher)` where the tenant part is the existing routing key. The runtime takes the
   connection, runs the ordinary mount (identity from the caller's JWT), then the consumer's voucher
   mount with the voucher string. The voucher mount verifies the token (in the database, with an HMAC
   check, or in Java before calling a routine) and sets session state wholesale for that connection.
3. If the voucher mount reports the voucher invalid, the connection is unmounted and released, and every
   entity carrying that voucher is `null`. This is a different failure from a rejected identity, which
   still fails the whole request.
4. The group's SELECT runs on the vouched connection, and each resolved row is returned as a
   `DataFetcherResult` whose `localContext` carries the routing key. graphql-java hands that value to
   every field below, so child acquisitions and their DataLoader batches are keyed by it. A batch never
   mixes two vouchers, and a field outside the subtree uses the base connection, exactly as tenants
   already behave.
5. At operation end every vouched connection is unmounted through the ordinary unmount and released.
   Unmount clearing is belt and braces here, since a vouched connection is never reused by another
   voucher or by the base path within the operation.

Granularity is the consumer's choice by how A mints: one voucher per application gives one connection
per application in the query; one per result gives one per result. A configurable cap on vouched
connections per operation fails the excess entities closed instead of exhausting the pool.

## What exists today, and what is missing

| Need | Today | Gap |
|---|---|---|
| Per-subtree routing value | The tenant rides down an entity's subtree in `localContext`; loaders and acquisitions read it. | The slot is reserved for the tenant and a guard test keeps every other writer out. It becomes a small routing record (tenant, voucher), and the guard, the loader naming and the acquisition path read both parts. |
| One connection per routing key | Per-tenant acquisition, lazily, one mount per connection, unmount at release. | Key the acquisition by `(tenant, voucher)`; a voucher mount step after the ordinary mount; the per-operation cap. |
| Entity with a non-column key field | Every `@key` field must resolve to a column. | A `@proof` field: in the key, in the SDL, excluded from the SELECT and projection. A key alternative with a proof field must also name at least one column field, which is what the SELECT filters on. |
| Verification | None; the mount contract is session-scoped state. | An optional `<voucher>` block inside `<sessionState>` naming a mount method that receives the connection configuration, the voucher, and, by type, the base mount's handle; it reports accepted or rejected. Build-time signature reading as for the mount. |
| Mint at A | `@service` children batch and read `contextArguments`; a reference-only stub on a non-table type is accepted. | None for the minimum. A `@voucher` sugar directive is a later convenience, not part of this item. |
| Children of the entity | Table-bound children run through generated fetchers that already honour the inherited routing. | Verify that a `@service` child and a connection-typed child under the entity also acquire from the inherited key. |

## Implementation

* *Routing record.* Replace the bare tenant in the `localContext` slot with a record carrying the optional
  tenant and the optional voucher. `TenantHandDown` becomes the one writer of the record; the ownership
  guard test is updated to the new shape. Single-tenant, voucher-free builds emit exactly what they emit
  today.
* *Acquisition.* The runtime's acquire path takes the routing record; a record with a voucher takes a
  separate connection, runs mount then voucher mount, and remembers the outcome per key so a repeat
  presentation reuses the connection (or the rejection).
* *Entity dispatch.* `EntityResolution` gains a key alternative carrying a proof field. The generated
  handler groups by voucher, acquires, runs the batched SELECT on the vouched connection, and stamps the
  routing record on each result. Rejected vouchers produce `null` slots.
* *Configuration.* `<sessionState><voucher>fqcn#method</voucher></sessionState>`. A `<voucher>` without a
  `<mount>` fails the build, and a `@proof` field in a build without a voucher mount fails the build.
* *Redaction.* The voucher is a credential: confirm generated code and the dispatcher never log
  representations or echo the voucher in errors.
* *Out of scope.* Signing, key rotation, the reference mount routine and policies (a worked example goes
  in the how-to), router header propagation.

## First-client docs draft (for `federation-keys.adoc` and the security guide)

"To let another subgraph read data your user's token does not cover, the subgraph that made the access
decision returns a voucher inside the entity's key. The subgraph that owns the data marks that key field
`@proof` and names a voucher mount. Graphitron takes a separate database connection for each distinct
voucher, runs your voucher mount on it, and reads that entity and everything below it through that
connection. Nothing outside those entities can use it, and it is released when the request ends." If this
does not read simply once the spellings are chosen, the design is wrong.

## Tests

* Pipeline tier: classification of `@proof` (accepted with a voucher mount, rejected without, rejected on
  a field that is also a column); the routing record in emitted fetchers; single-tenant output unchanged.
* Compilation tier: generated dispatcher and acquisition code compiles at Java 17.
* Execution tier (real Postgres, RLS fixture beside the existing session-mount fixture): a valid voucher
  exposes the entity and a three-level nested read under a policy that otherwise hides the rows; the same
  voucher twice in one operation takes one connection; two vouchers take two; a sibling entity or a field
  outside the subtree cannot read the first voucher's rows; tampered, expired and wrong-user vouchers give
  `null`; the cap fails the excess closed; connections are unmounted and released at the end.
* Served SDL: `_service { sdl }` shows `@inaccessible` on the voucher field and both `@key` alternatives.

## Open questions

* Spelling of `@proof` (a field directive, or an argument on `@key` listing proof fields).
* The voucher mount's result: boolean accepted, or an exception type that means "rejected" as opposed to
  "failed"; recommendation is boolean, with exceptions evicting the connection and erroring the entities.
* Cap default and whether it is per operation or per runtime.
* A voucher mounted in a multi-tenant build: the tenant part of the key comes from the entity's own
  tenant routing; confirm a voucher cannot widen the tenant set the request may touch.
* Whether a later `@voucher` sugar on A's field is worth adding once two consumers hand-write the same
  minting service.

## Other solutions we've considered

* Accumulating grants on the request's single connection: the grant is visible to every field and to any
  consumer SQL, and needs a ledger table to be unforgeable. Routing removes the sharing instead.
* A routine per covered read shape (voucher as a routine argument): stateless, but each nested shape needs
  its own routine, which does not scale to deep table-bound reads.
* A transaction-local setting per DataLoader batch: scopes exactly and needs no extra connections, at the
  price of wrapping reads in transactions and extra round trips.
* A carrier type with a hand-written entity resolver, or a Graphitron-owned voucher directive with a
  signing interface: more surface in the generator for what the mount seam already expresses.
