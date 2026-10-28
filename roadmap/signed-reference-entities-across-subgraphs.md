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
issued for, and without either side writing a resolver or parsing a token. The two ends are different acts
and get different markers. In subgraph A, which knows the application and the caseworker, a field marked
`@issue` makes Graphitron attach a *voucher* to each reference it returns; reaching those rows under A's own
access rules is the decision being vouched for. The voucher is Graphitron's own artifact: a signature over
the scope, the caller, an expiry and the row's own `@key` fields, which Graphitron later decodes and
verifies against the key fields of the representation it receives, the way it encodes and decodes a node id. In subgraph B, which owns the data, the matching key
field is marked `@accept`. B takes a *vouched connection* for each verified voucher: a database connection
mounted with the caller's identity, then handed what the voucher covers so B's row-level security (RLS:
Postgres policies deciding per row what a connection may read) admits exactly those rows. Every field
below that entity reads through the same vouched connection and nothing else does. A voucher that fails
verification, has expired, or was issued for another caller, scope or key fails the whole `_entities`
call with an error, and no SQL on the covered tables runs: a bad voucher means tampering or a deployment
fault, so nothing in that call is answered. The consumer supplies the signing and verifying
keys, the RLS policies, and one small method that turns a verified voucher into session state.

```graphql
# Subgraph A: the issuer. VgsResultat is A's own table of references to results; `id` is the result's id.
# Graphitron computes `voucher` per row, the way it computes a @nodeId; no service, no token code.
type Soknad @table(name: "soknad") @key(fields: "id") {
  id: ID! @nodeId
  vgsResultater: [VgsResultat!]! @reference(path: [{key: "soknad_vgs_resultat_soknad_fkey"}])
}
type VgsResultat @table(name: "soknad_vgs_resultat") @key(fields: "id", resolvable: false) {
  id: ID! @field(name: "vgs_resultat_id")
  voucher: String! @issue(scope: "vgs-resultater", ttl: "PT5M")
}

# Subgraph B: the owner. The real, table-bound entity. `voucher` is the proof field: not a column, not selected.
type VgsResultat @table(name: "vgs_resultat") @key(fields: "id") {
  id: ID! @nodeId
  voucher: String! @accept(scope: "vgs-resultater")
  fag: String!
  karakter: Int!
  detaljer: [Fagdetalj!]!          # nested table-bound children, read under the same vouched connection
}
```

What both subgraphs publish is derived, not written. Graphitron adds a second key beside the authored one on
a type that has an `@issue` or `@accept` field, `@key(fields: "id voucher")`, and puts `@inaccessible` on the
voucher field, importing `@inaccessible` into the `@link` as it does for the other directives it applies.
The author writes neither, and A and B cannot disagree about the key shape because both derive it from
their marker. The authored `@key(fields: "id")` is untouched, so the type still resolves by plain `id`
under the caller's own RLS; a voucher only ever adds access. This is federation only: `Query.node`,
`@node` and the relay id are unaffected and know nothing about vouchers.

## How it works

1. *Issue (A).* A's field is an ordinary read; the `voucher` column of each returned row is not selected
   but computed. For each returned row Graphitron signs `(scope, caller, expires, the entity type name, the row's key fields)`
   with the runtime's signing key and writes the token (version, scope, caller, expires, key id, signature)
   into that row. `expires` is the operation's start plus `ttl`, fixed per operation, so all rows of an
   operation share the same scope, caller and expiry. The decision is the read itself: a row A's rules hide
   is never returned and never signed.
2. *Carry.* The router fetches the key fields from A and sends `_entities` representations `{id, voucher}`
   to B. The voucher stays out of the client schema because the field is `@inaccessible`.
3. *Verify (B).* The dispatcher resolves the representations through the voucher-bearing key. Graphitron
   decodes each voucher and checks, before any connection is taken: the signature over the token's scope,
   caller and expiry plus the type name and key fields of *this representation*, against the runtime's verification keys;
   expiry; scope against the field's `@accept(scope:)`; and the caller against the caller of this request.
   Because the signature covers the representation's own key, a voucher cannot be paired with another row.
   Every voucher-bearing representation is checked and its context extracted (scope, caller, expiry); a
   single failure fails the whole `_entities` call with an error, before any connection is taken.
   Representations that carry no voucher use the plain key and are not part of this check.
4. *Route.* The extracted contexts group the representations by tenant, scope, caller and expiry. For each group the runtime takes one connection keyed by `(tenant, context)`, runs the ordinary
   mount (identity from the caller's JWT), then the consumer's accept method once with the context and
   every key the group covers. The accept method sets session state for the connection and returns a
   boolean; `false` unmounts and releases the connection and fails the whole call, like a verification failure.
5. *Read below.* The group's SELECT runs on the vouched connection, and each resolved row is returned as a
   `DataFetcherResult` whose `localContext` carries the routing key. graphql-java hands that value to
   every field below, so child acquisitions and their DataLoader batches are keyed by the context, so
   each field below loads in one batch per context on the vouched connection; a batch never mixes two contexts and a field outside the subtree uses the base connection, as tenants already do.
6. *Release.* At operation end every vouched connection is unmounted and released.

An operation takes at most one vouched connection for now. Rows issued in one operation share a context, so
a list of results, or several parents' lists, is one connection. A second distinct context in the same
operation, which needs a second operation on A, fails its entities with an error that names the limit, not
`null`, so the limit is not mistaken for a denial. The constant lives in one place so it can become
configuration later.

One connection per context means every entity of the group reads through a connection that covers all the
group's keys, so a subtree is scoped to its entities by the graph it was reached through, not by the
connection. All the keys were vouched by the same issuer for the same caller, so nothing is granted that
the issuer did not already vouch for. Strict isolation, a connection per row, would cost one connection per
result and is not what this item builds.

Because the voucher signs the keys it is presented with, B needs no notion of application or subject: the
policies test the covered keys, for example `id = any(covered)` on the result table and a join from each
child to its result row.

## What exists today, and what is missing

| Need | Today | Gap |
|---|---|---|
| Encode and decode an opaque value in a key | `NodeIdEncoder` encodes and decodes node ids. | A voucher codec beside it: encode `(scope, caller, expires)`, sign over those plus the row's key fields, decode, verify against the representation's key. Owned by Graphitron, versioned in the encoding so the format can change. |
| Signing and verification keys | None. | A runtime-level key provider supplied when the runtime is built, like the tenant `DataSource` map: a signing key on A, verification keys by key id on B, so the public key can sit in B and rotation adds a key id. A default implementation over JDK Ed25519 (available at Java 17); an interface so a consumer can sign remotely, for example through Vault Transit. Presence of a marker adds the parameter to the generated runtime constructor, so omitting it is a compile error. |
| The caller the voucher is bound to | The mount's payload becomes contextArguments on the factory. | One named context value both subgraphs read as the caller, so the voucher is useless to another user. Named once, in plugin configuration. |
| Computed field on a table-bound row | `@nodeId` and other computed projections are emitted into the select list. | `@issue` is a computed, non-column projection whose value comes from the codec, one signature per returned row, computed from the row's own key fields. |
| Per-subtree routing value | The tenant rides down an entity's subtree in `localContext`; loaders and acquisitions read it. | The slot is reserved for the tenant and a guard test keeps every other writer out. It becomes a small routing record (tenant, voucher), and the guard, the loader naming and the acquisition path read both parts. |
| One connection per routing key | Per-tenant acquisition, lazily, one mount per connection, unmount at release. | Key the acquisition by `(tenant, voucher context)`; an accept step after the ordinary mount; the cap of one. |
| Entity with a non-column key field | Every `@key` field must resolve to a column. | An `@accept` field: in the key, in the SDL, excluded from the SELECT and projection; a key with it must also name at least one column field. |
| Schema alteration from one marker | `@node` types get a synthesised `@key`; applied federation directives are imported into the `@link`. | A synthesiser that adds a second key (the authored key's fields plus the marked field) beside the authored one and applies `@inaccessible` to the marked field, same pass, same import handling. A marker on a type with no `@key` is rejected. |
| Session state from a verified voucher | `<sessionState>` mount sets identity. | `<voucher><accept>` method: receives the connection configuration and the group's voucher context (scope, expiry) with every covered key, sets session state, returns a boolean; an exception evicts the connection and errors the entities. Same session-scoped contract as the mount. |
| Children of the entity | Table-bound children run through generated fetchers that honour the inherited routing. | Verify that a `@service` child and a connection-typed child under the entity also acquire from the inherited key. |

## Implementation

* *Directives.* `@issue(scope:, ttl:)` on a field of a table-bound, reference-only type (A);
  `@accept(scope:)` on a field of a table-bound, resolvable type (B). Graphitron defines both, as it
  does `@node`; consumers do not write the definitions. Reject: either marker on a type with no `@key`, on
  a field that is also a column, both markers on one type, and `@accept` in a build with no `<accept>`.
* *Voucher codec.* One class owns the format: version byte, scope, caller, expiry, key id, signature. The
  signature input adds the entity type name and the row's key fields in their own scalar form, which the
  token does not carry: B recomputes them from the representation, so a voucher for one type's key cannot
  be presented as another type's with the same key value. A decode failure, unknown version or unknown key id is a
  verification failure, not an exception to the caller.
* *Key synthesis.* Beside the node-key synthesiser: a type with a marked field gains a second `@key`, the
  authored key's fields plus the marked field, and the marked field gains `@inaccessible`; both show in
  the served `_service.sdl`. Authored keys, `@node` and the relay id are not touched. The voucher key's
  `id` part decodes the way the plain key's does.
* *Issue.* The fetcher that reads an `@issue` row computes the token after the rows are read, one signature
  per row. Expiry is fixed per operation. It works at any position, root or child.
* *Verify and group.* The dispatcher's voucher handler decodes and checks as in step 3, groups by voucher
  context, and hands the accept method every key of a group in one call.
* *Routing record.* Replace the bare tenant in the `localContext` slot with a record carrying the optional
  tenant and the optional voucher context. `TenantHandDown` becomes the one writer of the record; the ownership
  guard test is updated. Single-tenant, voucher-free builds emit exactly what they emit today.
* *Acquisition.* The runtime's acquire path takes the routing record; a record with a voucher takes a
  separate connection, runs mount then accept, and remembers the outcome per key so a repeat presentation
  reuses the connection or the rejection.
* *Configuration.* `<voucher><caller>contextArgumentName</caller><accept>fqcn#method</accept></voucher>`;
  the accept method is read at build time like the mount, and `<accept>` requires a `<sessionState><mount>`.
  Keys are runtime, not plugin, configuration.
* *Redaction.* The voucher is a credential: confirm generated code and the dispatcher never log
  representations or echo the voucher in errors.
* *Out of scope.* Key management and rotation procedure, the reference accept method and policies (a worked
  example goes in the how-to), router header propagation, a per-row decision beyond A's own read.

## First-client docs draft (for `federation-keys.adoc` and the security guide)

"To let another subgraph read data your user's token does not cover, mark the field that returns the
reference `voucher: String! @issue(...)`. Graphitron signs a voucher for each row your own rules
let the caller see. In the subgraph that owns the data, mark the same key field `@accept(...)`. Graphitron
checks the voucher, takes a separate database connection for it, runs your accept method to set the session
state your policies read, and reads that entity and everything below it through that connection. Nothing
outside those entities can use it, and it is released when the request ends." If this does not read simply
once the spellings are chosen, the design is wrong.

## Tests

* Codec: round trip; tampered signature, wrong key id, unknown version, expired, wrong scope, wrong caller
  and a voucher presented with another row's key each fail verification and nothing else does.
* Pipeline tier: classification of both markers and each rejection above; the synthesised key and
  `@inaccessible` on a table-bound entity and on a reference-only row type; the routing record in emitted
  fetchers; single-tenant output unchanged; the generated runtime constructor gains the key parameter only
  when a marker is present.
* Compilation tier: generated issue, dispatcher and acquisition code compiles at Java 17.
* Execution tier (real Postgres, RLS fixture beside the existing session-mount fixture, A and B in one
  fixture): each returned row gets its own token and all share one context; a valid voucher exposes the
  entity and a three-level nested read under a policy that otherwise hides the rows; a list of results, or
  several parents' lists, takes one connection and the accept method sees every key once; a second
  distinct context fails its entities with the limit error; a field outside the entities' subtrees cannot
  read the covered rows; every verification failure above, and an accept method returning `false`, fails the whole call with an
  error and the policy hides the rows;
  connections are unmounted and released at the end.
* Served SDL: `_service { sdl }` shows `@key(fields: "id voucher")` and `@inaccessible` on the voucher
  field on both sides, with the `@link` importing `@inaccessible`.

## Open questions

* Names: `@issue` and `@accept` name the two acts; both are generic words for directives in an SDL that may
  carry other meanings, so check them against the directive vocabulary before they ship.
* Should B need an accept method at all? Graphitron could set a fixed pair of session settings itself
  (scope, covered keys) when none is configured, so policies read a documented setting and B writes no
  Java. The method stays for consumers who need other state or an extra refusal.
* The caller: one context value read by name on both sides (as written), or taken from the mount's handle.
* Key provider shape and default: Ed25519 over JDK keys, with the public key distributed to B; whether the
  default also reads PEM from configuration or only takes key objects.
* A nonce: none is proposed. A voucher is bound to scope, caller, type, key and a short expiry, and the
  same voucher is legitimately presented more than once (router retries, several fetches in one plan), so
  single use would need shared state across B's instances and would break those. Open: an unsigned-secret
  token id in the signed payload, logged at A and B, for audit correlation; and, for a consumer that wants
  single use, handing the id to the accept method so it can record it in B's own database.
* One signature per returned row: negligible for Ed25519 at page sizes, but a connection over thousands of
  rows signs thousands; set a ceiling or sign lazily only for rows the router actually asks the key of.
* Isolation: one connection per context scopes a subtree by graph shape, not by connection. If a consumer
  needs a connection per row, the routing key can include the key fields at the cost of the cap.
* Expiry fixed per operation means two operations on A inside one router query plan produce two contexts;
  rounding expiry to the `ttl` boundary would merge them. Decide when a consumer shows it.
* Multi-tenant: confirm a voucher cannot widen the tenant set the request may touch.

## Other solutions we've considered

* A consumer-written issue method and an opaque token the consumer parses: needs a hand-written resolver
  or method at each end and gives Graphitron nothing to check, so every consumer re-implements expiry,
  scope, caller and coverage checks.
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
