---
id: R1119
title: "Signed voucher handover: one subgraph vouches, another grants through RLS"
status: Spec
bucket: feature
priority: 3
theme: service
depends-on: []
created: 2026-10-28
last-updated: 2026-11-09
---

# Signed voucher handover: one subgraph vouches, another grants through RLS

## Goal

A subgraph can vouch for an access decision to another subgraph, and the receiving subgraph's database
turns that into data access, at any query depth below the vouched entity, without the grant reaching
beyond the entities vouched for together, and without either side writing a resolver or parsing a token.
The two ends are different acts and get different markers. In subgraph A, which knows the application and
the caseworker, a field marked `@issue` makes Graphitron attach a *voucher* to each reference row it
returns below a parent; reaching those rows under A's own access rules is the decision being vouched for.
The voucher is Graphitron's own artifact: it carries the row's key values for B's entity, signed together
with the scope, the caller and an expiry, and A publishes no field under the name of a key field of B, so
the only way a router can reach B's entity from A is through the voucher. Graphitron later decodes and verifies it
the way it encodes and decodes a node id. In subgraph B, which owns the data, the matching field is marked
`@accept`. B takes a *vouched connection* for each verified vouch: a
database connection on which the consumer's ordinary session mount runs, told what the vouch covers, so
B's row-level security (RLS: Postgres policies deciding per row what a connection may read) admits
exactly those rows. Every field below that entity reads through the same vouched connection and nothing
else does. A voucher that fails verification, has expired, or was issued for another caller, scope or key
fails the request before any SQL on the covered tables runs: a bad voucher means tampering or a
deployment fault, so nothing in that request is answered. The consumer supplies the signing and verifying
keys, the RLS policies, the caller on the per-request `SessionState`, and, in B, a mount that reads the
vouch it is told.

```graphql
# Subgraph A: the issuer. VgsResultat is A's own table of references to results. `key:` is an argMapping
# string, "<B's key field>: <a field of this type>": B's `id` takes the value of `resultatId`. The type keeps
# its ordinary fields, as long as none is named like a field on the left of `key:`. Graphitron computes
# `voucher` per row; no service, no token code, no `@key` written.
type Soknad @table(name: "soknad") @key(fields: "id") {
  id: ID! @nodeId
  vgsResultater: [VgsResultat!]! @reference(path: [{key: "soknad_vgs_resultat_soknad_fkey"}])
}
type VgsResultat @table(name: "soknad_vgs_resultat") {
  resultatId: ID! @field(name: "vgs_resultat_id")
  voucher: String! @issue(scope: "vgs-resultater", ttl: "PT5M", key: "id: resultatId")
}

# Subgraph B: the owner. The real, table-bound entity. `voucher` is the proof field: not a column, not selected.
type VgsResultat @table(name: "vgs_resultat") @key(fields: "id") {
  id: ID!
  voucher: String! @accept(scope: "vgs-resultater")
  fag: String!
  karakter: Int!
  detaljer: [Fagdetalj!]!          # nested table-bound children, read under the same vouched connection
}
```

The voucher carries each value as A's field publishes it, which is what a plain representation would have
carried, and B parses it as its own key field, as it parses any representation. So the values must agree
as for any federation key: here A's `resultatId` reads the result id from `vgs_resultat_id` and B's `id`
reads its own `id` column, both as an `ID`. A client that wants the result's `id` gets it from B, through
the voucher, like `fag`.

What both subgraphs publish is derived, not written. On a type with an `@issue` or `@accept` field
Graphitron mints `@key(fields: "voucher")` and puts `@inaccessible` on the voucher field: in A the key is
`resolvable: false` and is the type's only key, in B it is `resolvable: true` and sits beside the authored
key. Both are minted applications, the same mechanism that gives a node type its `@key(fields: "id")`, so
the `@link` imports `@inaccessible` with no extra step. The author writes neither, and A and B cannot
disagree about the key shape because both derive it from their marker.

Why the voucher is the whole key. A router plans a jump from A to B through any key of B whose fields A
can supply, and any field A publishes on the type counts, whether or not it is in A's own `@key`
(`@inaccessible` hides a field from clients, not from the planner). If A published a field named `id`, B's
authored `@key(fields: "id")` would carry the jump just as well, B would resolve the entity under the
caller's own RLS, the policy would hide the rows, and the client would get `null` with no error: the quiet
denial this item rules out. What matters is the field's name, not its value: A may publish the result id as
`resultatId`, because no key of B has a field by that name. Graphitron rejects a field of the issuing type
named like a field on the left of `key:`, so no plan from A can use that key of B; the voucher key is the
only way in, by construction rather than by planner preference. The left of `key:` is all A knows of B,
so a field named like a field of some other key of B is the author's to avoid, and the how-to says so. B's authored key is untouched, so the type still resolves by plain `id` from
anywhere else under the caller's own RLS; a voucher only ever adds access. This is federation only:
`Query.node`, `@node` and the relay id are unaffected and know nothing about vouchers.

## How it works

1. *Issue (A).* A's field is an ordinary read below a parent; the `voucher` field of each returned row is
   not a column but computed, and only when selected, which a router does only when it plans the jump to
   B. For each returned row Graphitron builds the token (version, vouch id, scope, caller, expires, key id,
   and, for each `@issue(key:)` entry, B's key field name with the value of the A field it names, serialized
   as that field's scalar serializes it on the wire) and signs it, with the entity type
   name, using the runtime's signing key. The *caller* is the value the consumer put on the request's
   `SessionState`. The *vouch id* is
   a random 128-bit value Graphitron mints once per operation and scope, and every row issued under them
   carries it, signed. `expires` is the operation's start plus `ttl`, fixed per operation, so all rows of a
   vouch share scope, caller, expiry and id. The decision is the read itself: a row A's rules hide is never
   returned and never signed. Under a mutation payload the rows are read and signed inside the mutation
   field's transaction; a field that rolls back is `null`, so a voucher for rows that are gone never
   reaches the router.
2. *Carry.* The router fetches `voucher` from A, the only field there that satisfies a key of B, and
   sends `_entities` representations `{voucher}` to B. The voucher stays out of the client schema because the field is
   `@inaccessible`.
3. *Verify (B).* The dispatcher resolves the representations through the voucher key, the way it resolves
   a node-id key: decode the opaque value into the authored key's columns, then the ordinary SELECT.
   Graphitron decodes each voucher and checks, before any connection is taken: the signature over the token
   and the representation's `__typename`, against the runtime's verification keys; expiry, with a fixed
   tolerance for clock skew between A and B; scope against the field's `@accept(scope:)`; the caller
   against the caller on this request's `SessionState`; and that the token carries exactly the fields of
   B's authored key. The key values are signed inside the token, so a voucher cannot be moved to another
   row or another type. Every voucher-bearing representation is checked and its vouch extracted (vouch id,
   scope, caller, expiry); vouchers with the same vouch id that disagree on scope, caller or expiry are a
   failure too. A single failure fails the request with a request-scoped
   `VoucherRejectedException`, thrown out of `GraphQL.execute` the way a rejected mount's
   `SessionMountException` is, before any connection is taken. Representations of the plain key, from
   elsewhere in the graph, are not part of this check and resolve under the caller's own RLS.
4. *Route.* The extracted vouch ids group the representations. For each group the per-operation carrier
   takes one connection keyed by `(tenant, vouch id)` and runs the consumer's ordinary mount on it, with
   `MountedSession.vouch()` present: the vouch id, scope, expiry and every key the group covers. The mount
   sets the session state the policies read, as it sets identity, and refuses by throwing; a throwing
   mount on a vouched connection is a `SessionMountException` like any other, the connection is evicted and
   the request fails. The unmount receives the same `MountedSession`, so it can clear what the vouch set,
   and on PostgreSQL the reset at release backstops it.
5. *Read below.* The group's SELECT runs on the vouched connection, and each resolved row is returned as a
   `DataFetcherResult` whose `localContext` carries the connection key. graphql-java hands that value to
   every field below, so child acquisitions and their DataLoader batches are keyed by it: each field below
   loads in one batch per vouch on the vouched connection, a batch never mixes two vouches, and a field
   outside the subtree uses the request's own connection, as tenants already do. A `$session` binding below
   reads the handle the vouched connection's mount returned, since `$session` is read through the
   statement's own connection.
6. *Release.* At operation end every vouched connection is unmounted and released with the rest of the
   carrier's entries.

An operation takes at most one vouched connection for now. Rows issued in one operation and scope share a
vouch id, so a list of results, or several parents' lists, is one connection. A second distinct vouch id in
the same operation, which needs a second operation on A or a second scope, fails its entities with an error
that names the limit, not `null`, so the limit is not mistaken for a denial. The constant lives in one place
so it can become configuration later.

One connection per vouch means every entity of the group reads through a connection that covers all the
group's keys, so a subtree is scoped to its entities by the graph it was reached through, not by the
connection. All the keys were vouched by the same issuer for the same caller, so nothing is granted that
the issuer did not already vouch for. Strict isolation, a connection per row, would cost one connection per
result and is not what this item builds.

Because the voucher carries and signs the keys it covers, B needs no notion of application or subject: the
policies test the covered keys, for example `id = any(covered)` on the result table and a join from each
child to its result row, where `covered` is the session setting the mount wrote from `vouch().keys()`.

## What exists today, and what is missing

| Need | Today | Gap |
|---|---|---|
| Encode and decode an opaque value in a key | `NodeIdEncoder` encodes and decodes node ids, and the node-id key alternative decodes one into the key's columns before the SELECT. | A voucher codec beside it: encode `(vouch id, scope, caller, expires, key values)`, sign it with the type name, decode, verify, and hand the key values to the authored key's columns. Owned by Graphitron, versioned in the encoding so the format can change. |
| Signing and verification keys | None. | A runtime-level key provider: a signing key on A, verification keys by key id on B, so the public key can sit in B and rotation adds a key id. A default implementation over JDK Ed25519 (in the JDK since 15, so valid in emitted Java 17); an interface so a consumer can sign remotely, for example through Vault Transit. In a build with a marker it is a required parameter of every `Graphitron.runtime(...)` overload, so omitting it is a compile error. |
| The caller the voucher is bound to | `SessionState.of(mount, unmount)` carries the request's callbacks; whatever the request knows is captured in their closures, and Graphitron never sees it. | In a build with a marker, `SessionState.of(caller, mount, unmount)`: the caller as a `String`, read by both subgraphs, so the voucher is useless to another user. Still one `of` form per build, so a lambda never meets an overload. |
| Computed field on a table-bound row | `@nodeId` and other computed projections are emitted into the select list. | `@issue` is a computed, non-column projection whose value comes from the codec, one signature per returned row that selects it, carrying the values of the fields `key:` names. |
| Per-subtree routing value | `TenantHandDown` is the one writer of the `localContext` slot, which carries the tenant key; `LocalContextOwnershipGuardTest` keeps every other writer out; the carrier's `handDown(row, tenantKey)` stamps entity and node dispatch. | In a build with an `@accept` field the slot carries a generated `ConnectionKey` (the tenant part only in multi-tenant builds, the vouch id part only with `@accept`). The hand-down, the guard's owner list, the loader naming and `handDown` take it. A build with no `@accept` keeps `Optional<K>` exactly. |
| One connection per routing key | `TenantConnections` keys its entries by `Optional<tenantKey>`, mints lazily, one mount per entry, unmounts and resets at release; its entry resolution is the one enforcer of the request tenant set. | Key the entries by `ConnectionKey`; the cap of one vouched entry; the tenant part of a vouched key goes through the same enforcer, so a voucher never adds a tenant. |
| Telling the mount what the connection is for | `MountedSession` carries `configuration()` and, in multi-tenant builds only, `tenant()`. | `vouch()`, returning `Optional<Vouch>` (id, scope, expiry, covered keys), declared only in a build with an `@accept` field, the way `tenant()` is declared only in multi-tenant builds. |
| Entity with a non-column key field | Every `@key` field must resolve to a column-backed field (`EntityResolutionBuilder`), except the node-id alternative, which decodes to the key's columns. | An `@accept` field: the whole minted key, in the SDL, excluded from the SELECT and projection, decoded to the columns of B's authored key like a node id. In A, an `@issue` field whose type publishes no field named like B's key fields. |
| Naming where a value comes from | An `argMapping` string, `"target: path"`, is parsed by one grammar (`ArgMappingSigil`) and captured per site (`graphitron_ast_argmapping_pair_entry`, `graphitron_argmapping_site`, `graphitron_argmapping_pair`); its paths resolve against the field's arguments and input fields, `$session` and `$context`. | `@issue(key:)` as one more argMapping site, its targets B's key field names and its paths resolved against the fields of the issuing type. |
| Schema alteration from one marker | `graphitron_directive_application_minted` mints `@key(fields: "id")` on a node type of a federated graph; `graphitron_federation_link_import_rule` imports every minted directive the link's version allows. | Two more arms of the minted view: `@key(fields: "voucher")`, with `resolvable` from the role (`false` for `@issue`, `true` for `@accept`), and `@inaccessible` on the marked field. The import needs no change. |
| Children of the entity | Table-bound children run through generated fetchers that honour the inherited routing. | Verify that a `@service` child and a connection-typed child under the entity also acquire from the inherited key. |

## Implementation

* *Directives.* `@issue(scope:, ttl:, key:)` on a field of a table-bound type (A). `key:` is a required
  argMapping string, `"<B's key field>: <path>"` entries, comma-separated: the target names a field of B's
  key the voucher carries, the path names a field of the issuing type whose value it carries. There is no
  default, since the obvious one, `"id: id"`, is the form the name rule forbids. `@accept(scope:, key:)` on a field of a table-bound, resolvable type (B): `key:` is the field set
  of the authored `@key` whose columns the voucher decodes to, and may be omitted when the type has exactly
  one authored resolvable key. Graphitron defines both in `directives.graphqls`, as it does `@node`;
  consumers do not write the definitions.
* *Facts.* Capture writes one entry per application, keyed by the directive application as
  `graphitron_ast_node_id_entry` is: `graphitron_ast_issue_entry` (scope, ttl) and
  `graphitron_ast_accept_entry` (scope, the `key:` field set as written). `@issue(key:)` is captured as an
  argMapping site like any other, its pairs in `graphitron_ast_argmapping_pair_entry`, so the grammar, the
  pair relations and the LSP's parsing are shared; what is new is the scope its paths resolve in, the
  fields of the enclosing type rather than the field's arguments, which the path resolution and the LSP's
  completions both take as a new root. Everything else is derived from these and from facts the store
  already holds: the minted key and `@inaccessible` (two arms of `graphitron_directive_application_minted`,
  their arguments in `graphitron_directive_application_arg_minted`), the rejections below as entry
  defects, and the build-shape questions the emitters ask (does this graph issue, does it accept). The
  emitters read those views; no new classified leaf carries the markers. The entity dispatch takes its
  voucher key alternative from the minted application rather than re-deriving it in
  `EntityResolutionBuilder` the way the node-id alternative is derived there today; it shares that
  alternative's decode-then-SELECT path.
* *Rejections.* `@accept` on a type with no authored resolvable `@key`, or with several and no `key:`, or
  a `key:` naming no authored key; `@issue` on a type with an authored `@key`, or with a field named like
  a target of its `key:` (either would let a router reach B without the voucher; `"id: id"` gets its own
  message saying so and naming the rename); an `@issue` `key:` path that names no field of the type, a
  field that is not a scalar read from the row (a `$session` or `$context` path, a list, an object, a
  `@nodeId` field, whose published value is an encoded id rather than the key value), or a target named
  twice; either marker on a field that is also a column; both
  markers on one type; either marker in a graph with no `<sessionState>` (no `store_graph_session_supplied`
  row), since the caller rides on `SessionState`; `@issue` on a type a root operation field returns (a
  `Query`, `Mutation` or `Subscription` field, directly or as a list), because the vouched decision is
  the path from a parent A's rules admitted. A field of a mutation payload is not a root field and may
  return `@issue` rows.
* *Voucher codec.* One class owns the format: version byte, vouch id, scope, caller, expiry, key id, the
  key values, signature. The key values travel in GraphQL's own wire form, so A and B never have to agree
  on how a column prints: entries sorted by B's key field name, each value the result of the A field's
  scalar serializing it, exactly what a plain representation would have carried. On B each value is parsed
  by the scalar of B's key field, as a representation's value is, and reaches the authored key's columns
  the way a node id's decoded values do. The signature covers every byte before it plus the entity type
  name, which the token does not carry: B takes it from the representation's `__typename`, so a voucher
  for one type cannot be presented as another's. A decode failure, unknown version, unknown key id, a
  field set other than B's authored key or a value B's scalar does not parse is a verification failure,
  not an exception to the caller.
* *Key synthesis.* The minted key and `@inaccessible` show in the served `_service.sdl` on both sides. B's
  authored keys, `@node` and the relay id are not touched.
* *Clock skew.* B accepts a voucher up to a fixed 30 seconds past its expiry, so a small difference
  between A's and B's clocks does not reject fresh vouchers. The constant lives beside the vouch cap.
* *Issue.* The fetcher that reads an `@issue` row selects the columns of the fields `key:` names, whether
  or not the client selected those fields, and computes the token after
  the rows are read, one signature per row, the caller read from the carrier's `SessionState`. A row is
  signed only when `voucher` is selected, which a router does only when it plans the jump to B, so the
  signing cost follows what B is asked for; no ceiling beyond the page size the parent field already
  sets. Expiry is fixed per operation. It
  works at any position below a parent, including under a mutation payload, where the field's transaction
  rule (a rolled-back field is `null`) keeps a voucher for rolled-back rows from the client.
* *Verify and group.* The dispatcher's voucher handler decodes and checks as in step 3, groups by vouch
  id, and acquires one vouched connection per group with every key of the group on the `Vouch`.
* *Connection key.* Replace `Optional<tenantKey>` with the generated `ConnectionKey` in builds with an
  `@accept` field. The routing hand-down remains the one writer of the `localContext` slot and the
  ownership guard's owner list follows it. Builds with no `@accept` field emit exactly what they emit
  today; whether a build gets the key is decided from the accept facts and the tenant-column
  configuration, never by hand in two places.
* *Acquisition.* The carrier's entry resolution takes the `ConnectionKey`; a key with a vouch id mints a
  separate entry whose mount sees `vouch()`, and the entry is reused for a repeat presentation of the same
  vouch. A rejected mount evicts the connection and fails the request, as today.
* *Configuration.* No new POM element: the markers switch the feature on, `<sessionState>` is already
  required for the caller, and keys are runtime configuration.
* *Redaction.* The voucher is a credential: confirm generated code and the dispatcher never log
  representations or echo the voucher in errors, and that `VoucherRejectedException`'s message names the
  check that failed and nothing from the token.
* *Out of scope.* Key management and rotation procedure, a reference mount and policies (a worked example
  goes in the how-to), router header propagation, a per-row decision beyond A's own read.

## First-client docs draft (for `federation-keys.adoc` and the security guide)

"To let another subgraph read data your user's token does not cover, give the row type a field
`voucher: String! @issue(..., key: "id: resultatId")`, saying which of your fields holds each field of the
other subgraph's key. Don't publish a field under the other subgraph's key field name: a router could use
it to skip the voucher, so the build refuses it and asks you to rename it. Graphitron
signs a voucher for each row your own rules let the caller see, bound to the caller you pass to
`SessionState.of`; both subgraphs must pass the same caller for the same user (for example the token's
subject), or every voucher is rejected. In the subgraph that owns the data, add the field
`voucher: String! @accept(...)`. Graphitron checks the voucher, takes a separate database connection for it, and
runs your mount there with `session.vouch()` telling it which rows were vouched for, so it can set the
session state your policies read. That entity and everything below it read through that connection.
Nothing outside those entities can use it, and it is released when the request ends." If this does not
read simply once the spellings are chosen, the design is wrong.

## Tests

* Codec: round trip, including an `ID` field over an integer column on A against an integer key column on
  B, a UUID key, an `Int` key and a two-field key whose `key:` entries are written in either order; tampered signature or key value, wrong key id, unknown version, expired beyond the skew
  tolerance (and accepted within it), wrong scope, wrong caller, a voucher presented under another
  `__typename`, a field set other than B's key, a value that does not coerce, and two vouchers sharing a
  vouch id but disagreeing on scope, caller or expiry each fail verification and nothing else does.
* Pipeline tier: capture of both markers and each rejection above, including `@issue` on a type a root
  field returns, on a type with an authored `@key` or a field named like a `key:` target (`"id: id"` with
  its own message), a `key:` path naming no field, a `@nodeId`, list or object field, or `$session`, and
  its acceptance under a mutation payload and beside ordinary fields renamed away from the targets;
  `key:` captured as an argMapping site with its pairs; `@accept` with two authored keys with and without `key:`; the minted key with
  `resolvable: false` and as the only key on A's issuing type, with `resolvable: true` beside the authored
  key on B's entity, and `@inaccessible` on both; the connection key in emitted fetchers; output for
  a build with no marker unchanged; `SessionState.of` gains the caller, `MountedSession` gains `vouch()`
  and `Graphitron.runtime` gains the key parameter only when the matching marker is present.
* Compilation tier: generated issue, dispatcher and acquisition code compiles at Java 17.
* Execution tier (real Postgres, RLS fixture beside the existing session-mount fixture, A and B in one
  fixture): each returned row gets its own token and all share one vouch id; a valid voucher exposes the
  entity and a three-level nested read under a policy that otherwise hides the rows; a list of results,
  or several parents' lists, takes one connection and the mount sees every key once; a second distinct
  vouch id fails the call with the limit error; a field outside the entities' subtrees cannot read the
  covered rows; every verification failure above, and a mount that throws on a vouched connection, fails
  the request and the policy hides the rows; in a multi-tenant fixture a voucher does not reach a tenant
  outside the request tenant set; a mutation payload issues vouchers on commit and none from a field that
  rolled back; vouched connections are unmounted and released at the end.
* Served SDL: `_service { sdl }` shows `@key(fields: "voucher")` and `@inaccessible` on the voucher field
  on both sides, with the `@link` importing `@inaccessible`, and A's issuing type publishing no field named
  like a target of its `key:`. That last assertion is the guard on the router's choice: with no field of
  the type in A under the name of B's key field, no plan from A can use B's plain key. Composing the two fixture subgraphs and planning a query over the
  supergraph would show the same thing end to end, but no composition or planning tool is in the build;
  the how-to names `rover supergraph compose` for consumers who want to see the plan.

## Open questions

* Names: `@issue` and `@accept` name the two acts; both are generic words for directives in an SDL that
  may carry other meanings, so check them against the directive vocabulary before they ship.
* A no-code default for B: Graphitron could set a documented pair of session settings itself (scope,
  covered keys) on a vouched connection before the mount runs, so policies read them and an unchanged
  mount suffices. Additive to the contract above, so it can follow.
* Key provider shape and default: Ed25519 over JDK keys, with the public key distributed to B; whether the
  default also reads PEM or only takes key objects.
* Replay: no per-voucher nonce. A voucher is bound to scope, caller, type, key and a short expiry, and the
  same voucher is legitimately presented more than once (router retries, several fetches in one plan), so
  single use would need shared state across B's instances and would break those. The vouch id is the
  group key and the audit handle, not a replay guard: A logs it when issuing and B logs it on every
  vouched mount and every rejection, so a redemption traces back to an issuance. A consumer that wants
  single use can record the id its mount receives in B's own database, accepting that router retries then
  fail.
* Paths that walk: `@issue(key:)` takes one field of the issuing type per entry. An argMapping path could
  walk further, for example to a value reached through a `@reference`, with no new syntax; left out until a
  consumer needs it, and rejected meanwhile.
* Isolation: one connection per vouch scopes a subtree by graph shape, not by connection. If a consumer
  needs a connection per row, the connection key can include the key fields at the cost of the cap.

## Decisions since the 2026-10-29 draft

The earlier draft predated the per-request session identity (`<sessionState><mount>` is retired; identity
is a `SessionState` of callbacks passed to `newOwnedExecutionInput`) and left the accept contract, the
caller, mutations and the tenant interaction open. Settled here:

* No separate accept method and no `<voucher>` POM block. The vouched connection runs the consumer's own
  mount, told the vouch through `MountedSession.vouch()`; refusing is throwing, as for any mount.
* The caller is a value on `SessionState`, so verification needs no connection and both sides read the
  same thing.
* The routing value and the session value are one generated shape (`ConnectionKey`), derived from the
  tenant-column configuration and the accept facts.
* `@issue` is rejected on a type a root operation field returns, and allowed under mutation payloads,
  which rely on the shipped rule that a rolled-back mutation field is `null`.
* A voucher never widens the request tenant set: the vouched key's tenant part goes through the carrier's
  existing enforcer.
* Verification failure fails the request (request-scoped, like a rejected mount) rather than only the
  `_entities` field; a router's entity fetch is that request.

## Response to review round 1

* Finding 1 (the router may jump on the plain key). The voucher now carries B's key values and A's issuing
  type publishes nothing but `voucher`, so `@key(fields: "voucher")` is the only key of B a plan from A can
  satisfy. The guarantee is structural and pinned by the served-SDL test on A, not by planner preference.
  `@requires` was not taken: A's type is not resolvable, so composition could not satisfy a requirement
  on B's fields from B's other entry points.
* Finding 2 (`resolvable` and multiple keys). The minted key's `resolvable` comes from the role, `false`
  for `@issue` and `true` for `@accept`; in A it is the type's only key, and A takes no authored `@key`.
  In B, `@accept(key:)` picks the authored key the voucher decodes to, required when there are several.
* Finding 3 (the signed form of the key values). The token carries the values in one canonical form and
  B coerces them by its key columns' types, so neither side recomputes the other's serialization; codec
  tests cover integer, UUID and two-field keys.
* Finding 4 (per-row signing). Closed: a row is signed only when `voucher` is selected, which follows the
  router's plan, and the parent's page size bounds it; no further ceiling.
* Notes. The docs draft says both subgraphs must pass the same caller; B tolerates 30 seconds of clock skew.

After round 1, with the user (2026-11-10): the round-1 answer to Finding 1 forbade every field on the
issuing type but `voucher`. A router can use any field A publishes on the type, not only A's own `@key`,
but only by name: what reaches B's key is a field *named* like B's key field. So the rule is now exactly
that, a field named like a target of `key:`, and the issuing type keeps its ordinary fields. `key:` became
an argMapping string, `"id: resultatId"`, naming B's key fields on the left and fields of the issuing type
on the right: no column names in the SDL, the existing grammar and capture, and the values travel as the
fields publish them, so Finding 3's canonical form is GraphQL's own wire form rather than a jOOQ string.

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
* A separate accept callback on `SessionState` beside the mount: a second callback with its own failure
  path and ordering against the mount, for what the mount already does once told the vouch.
* The caller read from a member of the mount's handle (`$session.<member>`, checked against
  `graphitron_session_handle_member`): no new `SessionState` parameter, but B would have to mount the
  request's own connection before it could verify, and a build without `<handle>` would have no caller.
* `@issue(key:)` as a list of `{field, column}` pairs: names A's columns in the SDL, which no other
  directive beside `@field`, `@table` and its kin does, needs a canonical string form per column type, and
  pushed the issuing type to publish nothing but `voucher`.
* A second key `@key(fields: "id voucher")` beside the plain one, with A publishing `id`: a router can
  satisfy B's plain key from A as well, and a plan that takes it denies quietly under RLS.
* B's protected fields declaring `@requires(fields: "voucher")` with the voucher `@external`: makes the
  requirement explicit, but composition must satisfy it from every path to those fields, and A, the only
  subgraph with the voucher, cannot be entered to fetch it.

## Reviewer findings

### Round 1: Spec → Ready (session_01WkW2JFv9qFDgNA4RommHSq, 2026-11-09)

Verdict: withhold. The goal reads without the step list and the design extends shapes already in the
tree rather than standing beside them: checked against trunk, `NodeIdEncoder`, `TenantHandDown`,
`LocalContextOwnershipGuardTest`, `TenantConnections`, `MountedSession` (`tenant()` only in a
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

**Finding 3 (question one: viability). The signed form of the key fields is undefined across the two
sides.** A signs "the row's key fields in their own scalar form", read from its column
(`vgs_resultat_id`, whatever its jOOQ type). B "recomputes them from the representation", which is JSON
off the wire, so for an `ID` it is a string. If A signs the column's Java value and B the decoded
representation value, verification fails for every row whose column type does not print the same way as
its wire form (an integer key under `ID`, a UUID, a composite key's field order). Specify one canonical
input, presumably each key field's serialized GraphQL value in key-selection order with the type name, and
add a codec or execution test with a non-`String` key column.

**Finding 4 (question one, non-blocking). Close the per-row signing question rather than leaving it open.**
The voucher is a field: it is computed only when selected, and the router selects it only when it plans
the voucher key. So "sign lazily only for rows the router actually asks the key of" is already what the
fetcher does, once Finding 1 is settled. Whether a ceiling is still wanted is then a small decision, and
better made here than at implementation time.

Notes, not blocking: the docs draft should say that both subgraphs must put the same string on
`SessionState.of` as the caller (for example the token subject), since router header propagation is out
of scope and a mismatch shows up as every voucher being rejected. A sentence on clock skew between A's
expiry and B's check (a tolerance, or none on purpose) would answer a question the implementer will
otherwise answer alone.
