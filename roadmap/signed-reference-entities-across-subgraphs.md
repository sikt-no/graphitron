---
id: R1119
title: "Signed voucher handover: one subgraph vouches, another grants through RLS"
status: In Progress
bucket: feature
priority: 3
theme: service
depends-on: []
created: 2026-10-28
last-updated: 2026-11-10
---

# Signed voucher handover: one subgraph vouches, another grants through RLS

## Goal

A subgraph can vouch for an access decision to another subgraph, and the receiving subgraph's database
turns that into data access, at any query depth below the vouched entity, without the grant reaching
beyond the entities vouched for together, and without either side writing a resolver or parsing a token.
The two ends are different acts and get different markers. In subgraph A, which knows the application and
the caseworker, a field marked `@issue` makes Graphitron attach a *voucher* to each reference row it
returns below a parent; reaching those rows under A's own access rules is the decision being vouched for.
The voucher is Graphitron's own artifact: it absorbs the row's key fields for B's entity, signed together
with the scope, the caller and an expiry, and A withholds the absorbed fields from what it publishes, so
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
# Subgraph A: the issuer. VgsResultat is A's own table of references to results, written the way B's key
# looks: `id` is B's key field, read here from A's `vgs_resultat_id`. `@issue` absorbs every other field
# of the type into the voucher, and Graphitron withholds them from A's published schema: A publishes
# `voucher` alone. No service, no token code, no `@key` written.
type Soknad @table(name: "soknad") @key(fields: "id") {
  id: ID! @nodeId
  vgsResultater: [VgsResultat!]! @reference(path: [{key: "soknad_vgs_resultat_soknad_fkey"}])
}
type VgsResultat @table(name: "soknad_vgs_resultat") {
  id: ID! @field(name: "vgs_resultat_id")
  voucher: String! @issue(scope: "vgs-resultater", ttl: "PT5M")
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

The *absorbed* fields are the issuing type's fields other than the voucher.
Graphitron reads each as a component of the voucher rather than as a field: its `@field` names the column,
its type the scalar, and its column is read whenever the voucher is computed, but it never becomes a field
of A's schema: not in A's served SDL, not in its runtime schema.
The voucher carries each absorbed value under the field's name, as the field's scalar serializes it, which
is exactly what a plain representation of B's key would have carried, and B parses it as its own key
field, as it parses any representation. So the absorbed fields are named and typed like B's key fields,
and their values must agree as for any federation key: here A's `id` reads the result id from
`vgs_resultat_id` and B's `id` reads its own `id` column, both as an `ID`. A client that wants the
result's `id` gets it from B, through the voucher, like `fag`.

What both subgraphs publish is derived, not written. On a type with an `@issue` or `@accept` field
Graphitron mints `@key(fields: "voucher")` and puts `@inaccessible` on the voucher field: in A the key is
`resolvable: false` and is the type's only key, in B it is `resolvable: true` and sits beside the authored
key. Both are minted applications, the same mechanism that gives a node type its `@key(fields: "id")`, so
the `@link` imports `@inaccessible` with no extra step. The author writes neither, and A and B cannot
disagree about the key shape because both derive it from their marker.

Why A withholds the absorbed fields. A router plans a jump from A to B through any key of B whose fields A
can supply, and any field A publishes on the type counts, whether or not it is in A's own `@key`;
`@inaccessible` hides a field from clients, not from the planner. If A published `id`, B's authored
`@key(fields: "id")` would carry the jump just as well, B would resolve the entity under the caller's own
RLS, the policy would hide the rows, and the client would get `null` with no error: the quiet denial this
item rules out. And any other field A published on the type would exist only in A, on a type no router can
enter A for, so a query reaching the type through B that asked for it could not be planned and the
supergraph would not compose. Withholding answers both: A's published type is `voucher` and its key and
nothing else, which composes with the jump on `voucher` (the round-2 composition table, under Reviewer
findings, row 4). An issuing type is not a node: a node gets a minted `@key(fields: "id")`, and on A
that key would name a field the voucher absorbed. B's authored key is
untouched, so the type still resolves by plain `id` from anywhere else under the caller's own RLS; a
voucher only ever adds access. This is federation only:
`Query.node`, `@node` and the relay id are unaffected and know nothing about vouchers.

## How it works

1. *Issue (A).* A's field is an ordinary read below a parent; the `voucher` field of each returned row is
   not a column but computed, and only when selected, which a router does only when it plans the jump to
   B. For each returned row Graphitron builds the token (version, vouch id, scope, caller, expires, key id,
   and, for each absorbed field, its name and its value as the field's scalar serializes it on the wire)
   and signs it, with the entity type
   name, using the runtime's signing key. The *caller* is the value the consumer put on the request's
   `SessionState`. The *vouch id* is
   a random 128-bit value Graphitron mints once per operation and scope, and every row issued under them
   carries it, signed. `expires` is the operation's start plus `ttl`, fixed per operation, so all rows of a
   vouch share scope, caller, expiry and id. The decision is the read itself: a row A's rules hide is never
   returned and never signed. Under a mutation payload the rows are read and signed inside the mutation
   field's transaction; a field that rolls back is `null`, so a voucher for rows that are gone never
   reaches the router.
2. *Carry.* The router fetches `voucher` from A, the only field A publishes on the type, and sends
   `_entities` representations `{voucher}` to B. The voucher stays out of the client schema because the field is
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
`keys()` is a list with one entry per vouched key, each a map from the key's field name to its value as B's
key field parsed it, so a key of several fields arrives whole.

The policy may test any part of the key, and what it tests is what the vouch grants. A first client
vouches for documents together with their owner: B carries a second authored key `@key(fields: "id
eierId")` beside its plain `@key(fields: "id")`, the accepting field names it (`@accept(scope:
"dokumenter", key: "id eierId")`), and A's issuing type absorbs `id` and `eierId`, both read off A's own
row. The decode is then `WHERE id = ? AND eier_id = ?`, so a voucher pairing a document with another
owner finds no row. A policy testing `eier_id = any(vouched_owners)` admits every document of the vouched
owners on the vouched connection, not only the vouched documents; a policy testing the pairs, or `id`
alone, admits only those. Either is the consumer's choice, and both are covered by the same voucher.

## What exists today, and what is missing

| Need | Today | Gap |
|---|---|---|
| Encode and decode an opaque value in a key | `NodeIdEncoder` encodes and decodes node ids, and the node-id key alternative decodes one into the key's columns before the SELECT. | A voucher codec beside it: encode `(vouch id, scope, caller, expires, key values)`, sign it with the type name, decode, verify, and hand the key values to the authored key's columns. Owned by Graphitron, versioned in the encoding so the format can change. |
| Signing and verification keys | None. | A runtime-level key provider: a signing key on A, verification keys by key id on B, so the public key can sit in B and rotation adds a key id. A default implementation over JDK Ed25519 (in the JDK since 15, so valid in emitted Java 17); an interface so a consumer can sign remotely, for example through Vault Transit. In a build with a marker it is a required parameter of every `Graphitron.runtime(...)` overload, so omitting it is a compile error. |
| The caller the voucher is bound to | `SessionState.of(mount, unmount)` carries the request's callbacks; whatever the request knows is captured in their closures, and Graphitron never sees it. | In a build with a marker, `SessionState.of(caller, mount, unmount)`: the caller as a `String`, read by both subgraphs, so the voucher is useless to another user. Still one `of` form per build, so a lambda never meets an overload. |
| Computed field on a table-bound row | `@nodeId` and other computed projections are emitted into the select list. The scalar occurrence binds by kind (`graphitron_occurrence_binding`: `COLUMN`, `NODE_ID`, `EXPRESSION` and others); `graphitron_occurrence_column_rule` seeks a column for every scalar occurrence it does not exclude, and a field with no column is `COLUMN_UNRESOLVED`. | The voucher field, on A and on B, binds by a kind of its own (`VOUCHER`), so the column rule never seeks a `voucher` column. On A its value comes from the codec, one signature per returned row that selects it, carrying the values of the absorbed fields. |
| Which column a name answers to | The match (SQL name unique on the table, else the jOOQ name unique and no SQL name answers) is spelled inline three times: in `graphitron_occurrence_column_rule` and in the `COLUMN_UNRESOLVED` and ambiguous-column defect arms, each per occurrence and reading element-keyed claims. | One catalog-side relation, one row per table and name a column answers to unambiguously, carrying which name matched (`docs/architecture/explanation/fact-model.adoc`, the column-name ruling). The occurrence rule and the defect arms become joins onto it, and the voucher components read it before any element exists. |
| Per-subtree routing value | `TenantHandDown` is the one writer of the `localContext` slot, which carries the tenant key; `LocalContextOwnershipGuardTest` keeps every other writer out; the carrier's `handDown(row, tenantKey)` stamps entity and node dispatch. A single-tenant build writes nothing there (`TenantHandDown.none()`). | Every build that writes the slot, multi-tenant or accepting, carries a generated `ConnectionKey` in it (the tenant part only in multi-tenant builds, the vouch id part only with `@accept`). The hand-down, the guard's owner list, the loader naming and `handDown` take it, with no "has accept" branch at any of them. A single-tenant build with no `@accept` still writes nothing. |
| One connection per routing key | `TenantConnections` keys its entries by `Optional<tenantKey>`, mints lazily, one mount per entry, unmounts and resets at release; its entry resolution is the one enforcer of the request tenant set. | Key the entries by `ConnectionKey`; the cap of one vouched entry; the tenant part of a vouched key goes through the same enforcer, so a voucher never adds a tenant. |
| Telling the mount what the connection is for | `MountedSession` carries `configuration()` and, in multi-tenant builds only, `tenant()`. | `vouch()`, returning `Optional<Vouch>` (id, scope, expiry, covered keys), declared only in a build with an `@accept` field, the way `tenant()` is declared only in multi-tenant builds. |
| Entity with a non-column key field | Every `@key` field must resolve to a column-backed field, except the node-id alternative, which decodes to the key's columns. `EntityResolutionBuilder` re-derives the alternatives (`KeyAlternative.Direct`, `KeyAlternative.NodeId`) from the classified model on every build. | The entity key alternatives stated as a stored relation, one row per alternative of an entity, read by the dispatcher. An `@accept` field is an alternative there: the whole minted key, excluded from the SELECT and projection, decoded to the columns of B's authored key like a node id. The node-id and direct alternatives move onto the same relation, so `EntityResolutionBuilder`'s re-derivation goes. |
| A field the author writes that the schema does not carry | None: the emitted population, `graphitron_element`, is the authored elements plus the minted ones (`graphitron_element_authored`, `graphitron_element_minted_*`), and nothing is ever taken out of it. The part sets `graphitron_field_authored` and `graphitron_argument_authored` select from the transcription directly. | The absorbed declarations of an issuing type, decoded as voucher components rather than fields: a stored membership relation, decided by structure alone, that `graphitron_element_authored` and both part sets anti-join, the first subtraction from that population, and the component's column and scalar resolved beside it. `EmittedRegistry`, which only adds, gains a removal arm reading the membership, so the served SDL and runtime schema lose them too. |
| Schema alteration from one marker | `graphitron_directive_application_minted` mints `@key(fields: "id")` on a node type of a federated graph, and `graphitron_directive_application_arg_minted` reads that view and hardcodes its arguments, since every minted key is today the node's (`fields: "id"`, `resolvable: true`). `graphitron_federation_link_import_rule` imports every minted directive the link's version allows. | A stored relation of minted keys, one row per key with its coordinate, field set, `resolvable` and role (`NODE`, `ISSUE`, `ACCEPT`), which both the minted application set and the minted argument set read, so no view reads another and no argument is hardcoded. The node rule becomes its `NODE` rows; the voucher key, `@key(fields: "voucher")`, is its `ISSUE` row (`resolvable: false`) and `ACCEPT` row (`resolvable: true`). `@inaccessible` on the marked field is one more arm of the minted application set. The import needs no change. |
| Children of the entity | Table-bound children run through generated fetchers that honour the inherited routing. | Verify that a `@service` child and a connection-typed child under the entity also acquire from the inherited key. |

## Implementation

* *Directives.* `@issue(scope:, ttl:)` on a field of a table-bound type (A); it absorbs every other field
  of the type. `@accept(scope:, key:)` on a field of a table-bound, resolvable type (B): `key:` is the field
  set of the authored `@key` whose columns the voucher decodes to, and may be omitted when the type has
  exactly one authored resolvable key. Graphitron defines both in `directives.graphqls`, as it does `@node`;
  consumers do not write the definitions.
* *Facts.* Capture writes one entry per application, keyed by the directive application as
  `graphitron_ast_node_id_entry` is: `graphitron_ast_issue_entry` (scope, ttl) and
  `graphitron_ast_accept_entry` (scope, the `key:` field set as written, or none). Everything else is
  derived from these and from facts the store already holds: the voucher membership and components
  (below), the voucher binding, the minted keys and `@inaccessible`, the entity key alternatives, the
  rejections below as entry defects, and the build-shape questions the emitters ask (does this graph issue,
  does it accept).
* *Column names.* Which column of a table a written name answers to is stated once in the catalog family,
  per the column-name ruling in `docs/architecture/explanation/fact-model.adoc`: one row per table and name
  a column answers to unambiguously (the SQL name where exactly one column carries it, else the jOOQ name
  where no SQL name answers and exactly one generated one does), carrying which name matched.
  `graphitron_occurrence_column_rule` and the `COLUMN_UNRESOLVED` and ambiguous-column defect arms become
  joins onto it, with their fact documents row-identical; that is the gate for this step. The `graphitron`
  gatherer's `meta_gatherer_dependency` row on the catalog family covers the read.
* *Voucher membership and components, not withheld fields.* An absorbed declaration is not a field that is
  classified and then hidden: the store cannot keep a column binding, a claim or an occurrence for a
  coordinate that is not a `graphitron_element` row (`graphitron_field`, `graphitron_directive_application`,
  `graphitron_occurrence` and `graphitron_claim` all reference it), and a second population axis, modelled
  but not emitted, is something nothing in the model has. So the decode decides what the declaration is,
  in two stored relations of the `graphitron_` family, written by one new stage of the graphitron anchor
  between `nodes` and `emitted` in `meta_stage` (named `vouchers`, class `derive.Vouchers`), after `nodes`
  because its node-type refusal reads `graphitron_node`:
  * the *membership*, one row per field declaration an `@issue` absorbs, keyed on its
    `graphql_field_element` row (`ON DELETE CASCADE`) and carrying the issuing field. It is decided by
    structure alone, from the issue entry and the type's transcribed fields, so a component whose column
    fails to resolve is still a member and still withheld: a resolution failure is a defect, never an `id`
    published by A;
  * the *components*, one row per member whose column and scalar resolved, keyed on the member: the
    component's name, its column (the first `@field(name:)` read off the transcription, else the field
    name, joined to the column-name relation over the issuing type's `graphitron_tabletype` row) and its
    scalar. This is the issue emitter's input: which columns to select, how to serialize each value.

  `graphitron_element_authored`, `graphitron_field_authored` and `graphitron_argument_authored` each
  anti-join the membership, the last two beside their existing `NOT EXISTS` against
  `graphitron_connection_carrier`, and each `meta_relation` description gains the reason. The set states
  its own stand-down rule, as each element set does; it is a decision, not a provenance arm. The
  transcription (`graphql_*`) keeps the declarations as written. Every relation the new stage reads is
  upstream of it (`graphql_*`, `graphitron_ast_*`, `graphitron_tabletype`, `graphitron_node`, the
  column-name relation), so the stage order is sound.
* *The voucher binding.* The voucher field, on A and on B, is a scalar occurrence on a table-bound type,
  and today the column rule would seek a `voucher` column and raise `COLUMN_UNRESOLVED` on a valid schema.
  It binds by a kind of its own, `VOUCHER`, added to the kinds `graphitron_occurrence_binding` and
  `graphitron_binding_claim` allow, per the binding-kind ruling in
  `docs/architecture/explanation/fact-model.adoc`; no directive-name exclusion is added to the column
  rule. The issue emitter and the entity dispatch find the voucher by that binding.
* *Minted keys, stored.* A stored relation of minted keys, one row per key: its coordinate, field set,
  `resolvable` and role (`NODE`, `ISSUE`, `ACCEPT`). The federation entity rule writes its `NODE` rows;
  each `@issue` writes an `ISSUE` row (`fields: "voucher"`, `resolvable: false`) and each `@accept` an
  `ACCEPT` row (`fields: "voucher"`, `resolvable: true`). `graphitron_directive_application_minted` and
  `graphitron_directive_application_arg_minted` both read it, so the argument set stops reading the
  application view and stops hardcoding `fields: "id"` and `resolvable: true`. `@inaccessible` on the
  marked field is one more arm of the minted application set. Existing fact documents for node keys stay
  row-identical. A stage of its own owns it, `minted-keys` (class `derive.MintedKeys`), after
  `federation-link` and `vouchers`, since its `NODE` rows read `graphitron_node` and the federation link
  and its `ISSUE` and `ACCEPT` rows read the voucher stage and the accept entry, and before `emitted`,
  which reads the minted applications.
* *Entity key alternatives, stated.* A stored relation, one row per key alternative of an entity: the
  entity, the key's field set, `resolvable`, and how its representation reaches the SELECT (`DIRECT`
  columns, a `NODE_ID` decode, a `VOUCHER` decode with the authored key it decodes to), with the
  per-field column bindings beside it. The voucher alternative is written from the `ACCEPT` minted key and
  the accept entry; the direct and node-id alternatives move onto the same relation from the facts
  `EntityResolutionBuilder` reads today. The entity dispatch reads the relation, and
  `EntityResolutionBuilder`'s re-derivation of `KeyAlternative` from the classified model goes. This is
  the "Entity resolution is stated" node of R876's graph, taken here. A stage of its own owns it,
  `entity-key-alternatives` (class `derive.EntityKeyAlternatives`), after `binding-claims`, since the
  per-field column bindings read the binding claims; its exact position is fixed by the last relation its
  rows read, and `meta_relation` and `meta_stage_write` name it.
* *The readers.* Everything built on the emitted population drops the declarations by construction: the
  directive applications through `graphitron_directive_application_authored`'s join to
  `graphitron_element`, the fields and arguments through the part sets' anti-joins above. Three readers
  are not built on it. `EmittedRegistry`, which patches the transcribed registry additively and never
  removes, gains a removal arm that reads the membership relation, not a generic "no `graphitron_field`
  row" rule, which would turn a capture defect into a silent deletion; its javadoc's "absence is a
  capture defect" paragraph says so. The served `_service.sdl` follows from it. The runtime wiring and
  the classified walk, which build from graphql-java's view of the authored schema, read the registry
  after that removal rather than skipping absorbed fields themselves, which would add a branch to the
  classified walk being drained. `FactCapture.derive`, through `ClassificationDomainCapture`, reads the
  written assembly, not the emitted one; absorbed scalar fields are harmless there, and a test pins that
  they classify without effect. No reader derives "is this absorbed" from the `@issue` entry on its own;
  each queries the membership. The LSP and MCP keep reading the authored transcription for the author's
  own text, so the declaration stays visible, completed and checked where it is written. The emitters read
  those relations; no new classified leaf carries the markers.
* *Rejections.* Each is a `graphitron_entry_defect` code at the declaration's written position, owned by
  the stage whose relations it reads, so a refusal never reads a later stage's facts
  (`StageRosterGateTest`).
  * Owned by the voucher stage, decided from the transcription and the capture entries: `@issue` on a type
    with an authored `@key` (it would let a router reach B without the voucher); `@issue` on a type
    `graphitron_node` holds, authored or inferred from the table's node metadata, since the minted node
    key would name an absorbed `id`; an `@issue` that absorbs nothing; an absorbed field that is a list or
    an object, or carries `@nodeId`, `@service`, `@routine`, `@reference` or another directive that gives
    it a value of its own (read off `graphql_directive_application`, not off a producer fact); an absorbed
    field whose column does not resolve (a member with no component row); both markers on one type; either
    marker in a graph with no `<sessionState>` (no `store_graph_session_supplied` row), since the caller
    rides on `SessionState`.
  * Owned by `entry-defects`, since what a root field returns is an element-keyed fact the voucher stage
    runs too early to read: `@issue` on a type a root operation field returns (a
    `Query`, `Mutation` or `Subscription` field, directly or as a list), because the vouched decision is
    the path from a parent A's rules admitted. A field of a mutation payload is not a root field and may
    return `@issue` rows.
  * Owned by `entity-key-alternatives`: `@accept` on a type with no authored
    resolvable `@key`, or with several and no `key:`, or a `key:` naming no authored key; `@accept` whose
    `key:` is an authored key with a nested selection (`@key(fields: "org { id }")`), since absorbed fields
    are top-level scalars.
* *Voucher codec.* One generated class owns the format: version byte, vouch id, scope, caller, expiry,
  key id, the key values, signature. The key values travel in GraphQL's own wire form, so A and B never
  have to agree on how a column prints: entries sorted by field name, each value the result of the absorbed
  field's scalar serializing it, exactly what a plain representation would have carried. On B each value
  is parsed by the scalar of B's key field, as a representation's value is, and reaches the authored key's
  columns the way a node id's decoded values do. The signature covers every byte before it plus the entity
  type name, which the token does not carry: B takes it from the representation's `__typename`, so a
  voucher for one type cannot be presented as another's. A decode failure, unknown version, unknown key id,
  a field set other than B's authored key or a value B's scalar does not parse is a verification failure,
  not an exception to the caller.
* *Generated, not shipped.* The codec, `Vouch`, `VoucherRejectedException`, the key provider interface and
  its default Ed25519 implementation are emitted per consumer, as `NodeIdEncoder` is
  (`NodeIdEncoderClassGenerator`), and only in a build whose store holds a marker entry. Generated code has
  no runtime dependency on Graphitron, and `graphitron-jakarta-rest` is an optional serving library a
  consumer verifying a voucher need not take. JDK Ed25519 (`KeyFactory` and `Signature` with "Ed25519")
  compiles at Java 17.
* *Key synthesis.* The minted key and `@inaccessible` show in the served `_service.sdl` on both sides,
  and A's issuing type shows `voucher` alone. B's authored keys, `@node` and the relay id are not touched.
* *Clock skew.* B accepts a voucher up to a fixed 30 seconds past its expiry, so a small difference
  between A's and B's clocks does not reject fresh vouchers. The constant lives beside the vouch cap.
* *Issue.* The fetcher that reads an `@issue` row selects the voucher components' columns whenever
  `voucher` is selected (nothing else can select them, since they are not fields), and computes the token
  after the rows are read, one signature per row, the caller read from the carrier's `SessionState`. A row
  is signed only when `voucher` is selected, which a router does only when it plans the jump to B, so the
  signing cost follows what B is asked for; no ceiling beyond the page size the parent field already sets.
  Expiry is fixed per operation. It works at any position below a parent, including under a mutation
  payload, where the field's transaction rule (a rolled-back field is `null`) keeps a voucher for
  rolled-back rows from the client. It is emitted as an `emit` query and emitter reading the component
  relation, not as a branch of `TypeFetcherGenerator`.
* *Verify and group.* The dispatcher's voucher handler decodes and checks as in step 3, groups by vouch
  id, and acquires one vouched connection per group with every key of the group on the `Vouch`.
* *Connection key.* Every build that writes the `localContext` slot, multi-tenant or accepting, carries a
  generated `ConnectionKey` there in place of `Optional<tenantKey>`, with a tenant part in multi-tenant
  builds and a vouch id part with `@accept`. A single-tenant build with no `@accept` writes nothing there
  today and still writes nothing, so its output is unchanged. The shape is computed once, as one value
  beside `SessionIdentityRows`, from the store's accept facts and the tenancy configuration the generator
  still takes, and handed to every generator that needs it; it is not added to `GraphitronSchema`, and no
  site evaluates "has accept" itself. The routing hand-down remains the one writer of the slot and the
  ownership guard's owner list follows it. `MountedSession.tenant()` keeps its type; the `Optional<K>`
  that `acquire` takes is generated-internal, which the step confirms before it changes it.
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

### Slices

Each slice lands green on trunk with a build that uses neither marker unchanged, because every emitted
unit, parameter and `of` overload is gated on a marker entry in the store.

1. *Column names.* The catalog-side relation, with the occurrence rule and its two defect arms moved onto
   it. A refactor: fact documents row-identical.
2. *Minted keys and entity key alternatives, stated.* Both stored relations with today's rows only
   (`NODE` keys; direct and node-id alternatives), the minted views reading the first and the entity
   dispatch reading the second, `EntityResolutionBuilder`'s re-derivation removed. A refactor: fact
   documents and generated output unchanged.
3. *Connection key.* `ConnectionKey` in every build that writes the slot, tenant part only. A refactor:
   single-tenant output unchanged, multi-tenant output differs only in the key's type.
4. *A's facts and published shape.* `@issue` and its capture entry, the voucher stage (membership,
   components, refusals), the `VOUCHER` binding, the three stand-downs, the `ISSUE` key and
   `@inaccessible`, `EmittedRegistry`'s removal arm, A's served SDL and runtime schema. These land
   together: without the removal arm the walk would be handed fields the store no longer holds. Until
   slice 6, a temporary defect refuses `@issue` at generate time so that a valid schema never reaches an
   emitter that does not exist; slice 6 removes it, and it is listed under Retired vocabulary.
5. *Codec.* The generated codec, key provider, `Vouch` and `VoucherRejectedException`, emitted only when a
   marker entry exists; compilation-tier tests and execution tests of the emitted codec. May run beside
   slice 4.
6. *Issuing.* The issue fetcher, `SessionState.of(caller, ...)` and the key parameter on
   `Graphitron.runtime(...)`, gated on the issue entry; A-alone execution tests (tokens decode, one vouch
   id per operation and scope); the temporary refusal goes.
7. *B's facts.* `@accept` and its capture entry, its refusals, the `ACCEPT` key and `@inaccessible`, the
   voucher alternative in the entity key alternatives. The same temporary-refusal device as slice 4,
   removed in slice 8.
8. *Dispatch.* The dispatcher's verify and group, vouched acquisition, `MountedSession.vouch()`, the A and
   B RLS execution fixture, and the recorded composition check.

## First-client docs draft (for `federation-keys.adoc` and the security guide)

"To let another subgraph read data your user's token does not cover, write the row type the way the
other subgraph's key looks, each key field reading its value from your own column, and add a field
`voucher: String! @issue(...)`. The voucher absorbs the key fields: they are sealed inside it and your
subgraph publishes the voucher alone, so a router can reach the other subgraph's data only through it.
Clients read the key fields from the other subgraph, like its other fields. Name and type them exactly
as the other subgraph's key fields: your subgraph cannot check that, and a mismatch shows up as every
voucher being rejected. Graphitron
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
  B, a UUID key, an `Int` key and a two-field key whose fields are declared in either order; tampered
  signature or key value, wrong key id, unknown version, expired beyond the skew
  tolerance (and accepted within it), wrong scope, wrong caller, a voucher presented under another
  `__typename`, a field set other than B's key, a value that does not coerce, and two vouchers sharing a
  vouch id but disagreeing on scope, caller or expiry each fail verification and nothing else does.
* Refactor gates (slices 1 to 3): every existing fact document row-identical after the column-name
  relation, the minted-key relation and the entity key alternatives land, and generated output unchanged
  for every existing fixture; single-tenant output unchanged after `ConnectionKey`.
* Pipeline tier: capture of both markers and each rejection above, each a defect at the declaration's
  position and owned by the stage named there, including `@issue` on a type a root field returns, on a type
  with an authored `@key`, on a node type (authored and inferred from node metadata), absorbing nothing,
  absorbing a `@nodeId`, list, object or `@service` field, or absorbing a field whose column does not
  resolve, and its acceptance under a mutation payload; the membership for every other field of the type
  and the components with column and scalar resolved, the column read through the column-name relation
  both by SQL name and by jOOQ name; a member whose column does not resolve still withheld (no
  `graphitron_element`, `graphitron_field` or argument row, absent from the served SDL); no
  `graphitron_element` row, no field or argument binding and no occurrence for an absorbed declaration;
  the voucher field bound `VOUCHER` and no `COLUMN_UNRESOLVED` on it, on A and on B; absorbed fields
  classifying without effect in `ClassificationDomainCapture`; `@accept` with two authored keys with and
  without `key:`, and on a key with a nested selection; the minted keys as rows (`ISSUE` with
  `resolvable: false` as the only key on A's issuing type, `ACCEPT` with `resolvable: true` beside the
  authored key on B's entity) and as the applications and arguments the minted sets render, and
  `@inaccessible` on the voucher field on both; the voucher alternative beside the authored one in the
  entity key alternatives; the connection key in emitted fetchers; output for a build with no marker
  unchanged; `SessionState.of` gains the caller, `MountedSession` gains `vouch()` and
  `Graphitron.runtime` gains the key parameter only when the matching marker is present.
* Compilation tier: generated issue, dispatcher and acquisition code compiles at Java 17.
* Execution tier (real Postgres, RLS fixture beside the existing session-mount fixture, A and B in one
  fixture): each returned row gets its own token and all share one vouch id; a valid voucher exposes the
  entity and a three-level nested read under a policy that otherwise hides the rows; a list of results,
  or several parents' lists, takes one connection and the mount sees every key once; a second distinct
  vouch id fails the call with the limit error; a field outside the entities' subtrees cannot read the
  covered rows; every verification failure above, and a mount that throws on a vouched connection, fails
  the request and the policy hides the rows; in a multi-tenant fixture a voucher does not reach a tenant
  outside the request tenant set; a mutation payload issues vouchers on commit and none from a field that
  rolled back; vouched connections are unmounted and released at the end; with a two-field accepting key
  beside the plain one (`id eierId`), the mount sees each key whole in `vouch().keys()`, a policy on the
  owner admits the owner's other documents on the vouched connection, and a voucher whose owner does not
  match its document finds no row.
* Served SDL: `_service { sdl }` shows `@key(fields: "voucher")` and `@inaccessible` on the voucher field
  on both sides, with the `@link` importing `@inaccessible`, and A's issuing type publishing `voucher` and
  nothing else, in the served SDL and in the runtime schema (an introspection of A's own endpoint). That
  assertion is the build-time guard on both federation questions: with only `voucher` on the type, no plan
  from A can use B's plain key, and no A-only field can make the supergraph fail to compose. No
  composition or planning tool is in the build, so the end-to-end evidence is row 4 of the round-2
  composition table under Reviewer findings (Apollo's `@apollo/composition` and `@apollo/query-planner`
  2.14.4): A publishing `voucher` alone composes, with the jump on `voucher`, while B also reaches the type
  from its own root field. Before Done, the implementer repeats that check against the fixture's two served
  SDLs and records the result in the item; the how-to names `rover supergraph compose` for consumers who
  want to see the plan.

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
* A component read through a `@reference`: an absorbed field is a scalar read off the issuing row. A key
  value that lives one join away is left out until a consumer needs it, and refused meanwhile.
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
* Finding 3 (the signed form of the key values). Settled after round 1, below: the token carries each
  value in GraphQL's wire form, as A's field serializes it, and B parses it with its key field's scalar;
  codec tests cover integer, UUID and two-field keys.
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

## Response to review round 2

* Finding 5 (an ordinary field on the issuing type breaks composition). Answered with a redesign rather than
  a patch, with the user (2026-11-10): the voucher absorbs the issuing type's other fields, and Graphitron
  decodes them as voucher components rather than fields, so A publishes `voucher` alone, which is row 4 of
  the round-2 table (composes, jump on `voucher`, B reaching the type from its own root field). Any field
  that is neither the voucher nor absorbed is refused. This retires, before they were built, the
  `@issue(key:)` argMapping (and its new path root), the name rule and the `@inaccessible` minted on
  ordinary fields. The absorbed fields are named and typed like B's key fields, which is what they are, and
  `key:` is an optional `@key`-style field set that defaults to every other field.
* The principles architect was consulted on the redesign. What it raised and what the spec does with it:
  a field classified but not emitted would need a second population axis, since bindings, claims and
  occurrences reference `graphitron_element`, so the declarations are decoded as a stored component
  relation that `graphitron_element_authored` anti-joins (*Voucher components*); that relation is written
  before the element and its column resolution must not depend on the coordinate (*Stage order*, and an
  open question); `EmittedRegistry` only adds and gains a removal arm, and the walk reads the registry
  after it rather than skipping fields (*The readers*); the refusals are defect codes owned by the stage
  that writes the component relation (*Rejections*).
* Notes. The round-1 entry for Finding 3 now points at the wire-form answer. `@accept` on a key with a
  nested selection is refused, since absorbed fields are top-level scalars.

## Response to round 3 and the take-up consultation

The item reached Ready on round 3 with two implementer notes. On taking it up the principles architect was
consulted, and the user ruled on what it raised (2026-11-10); the plan changed beyond a plan edit, so the
item returns to Spec for a fresh sign-off.

* Round 3, note 1 (the part sets). `graphitron_field_authored` and `graphitron_argument_authored` anti-join
  the membership beside their `graphitron_connection_carrier` stand-down (*Voucher membership and
  components*).
* Round 3, note 2 (`@issue(key:)` can only restate its default). Dropped, with its capture column and the
  refusals it needed (a `key:` naming no field or a field twice), and the refusal of a field neither
  voucher nor absorbed, which cannot occur once every other field is absorbed.
* The voucher field had no binding, so the column rule would refuse a valid schema with
  `COLUMN_UNRESOLVED`. Ruled: a binding kind of its own, `VOUCHER`, not a directive-name exclusion in the
  column rule (*The voucher binding*; the ruling is in `fact-model.adoc`).
* A node issuing type would get a minted `@key(fields: "id")` over an absorbed `id`, and a bare absorbed
  `id` would mean an encoded node id as a field but a raw column as a component. Ruled: `@issue` on a node
  type is refused (*Rejections*).
* The component's column resolution had no coordinate-free rule, and writing one would have been a fourth
  inline copy of the name match. Ruled: the match is stated once in the catalog family and the occurrence
  rule moves onto it (*Column names*; the ruling is in `fact-model.adoc`). This closes the open question.
* A component relation whose resolved column is required would drop a field that fails to resolve and
  hand it back to the element set, so A would publish `id`. Membership and resolution are now two
  relations, the anti-joins read membership (*Voucher membership and components*).
* Voucher arms in `graphitron_directive_application_arg_minted` would extend a view reading a view and
  break its hardcoded premise. Ruled in scope: minted keys are a stored relation (*Minted keys, stored*).
* A voucher arm in `EntityResolutionBuilder` would extend the classified walk. Ruled in scope: the entity
  key alternatives are stated, and the re-derivation goes (*Entity key alternatives, stated*).
* `ConnectionKey` only with `@accept` would put a "has accept" branch at five generator sites. Now it is
  emitted wherever the slot is written, computed once (*Connection key*); R1129's planned
  `MutationScope.tenant()` reads the tenant part.
* The codec and its types are generated per consumer, not shipped in `graphitron-jakarta-rest`
  (*Generated, not shipped*).
* Refusals are split by the stage whose facts they read, and `ClassificationDomainCapture` is named among
  the readers.
* Slicing, and the temporary refusal that keeps a valid `@issue` schema from reaching a missing emitter
  (*Slices*).

Round 4's two notes, taken at In Progress: the voucher stage runs after `nodes`, and the minted keys and
the entity key alternatives each have a named owning stage with a stated position (*Voucher membership and
components*, *Minted keys, stored*, *Entity key alternatives, stated*, *Rejections*). At the same point the
user confirmed the first client's case, a voucher carrying a document and its owner, which the
two-field accepting key covers as written; *How it works* now states it and the shape of `vouch().keys()`.

## Retired vocabulary

* `@issue(key:)`, its capture column and its refusals: retired before they were built.
* The inline name match in `graphitron_occurrence_column_rule` and the `COLUMN_UNRESOLVED` and
  ambiguous-column defect arms, replaced by joins onto the column-name relation.
* The hardcoded `fields: "id"` and `resolvable: true` arms of `graphitron_directive_application_arg_minted`,
  and its read of `graphitron_directive_application_minted`.
* `EntityResolutionBuilder`'s derivation of `KeyAlternative` from the classified model.
* `Optional<tenantKey>` as the routing value in the `localContext` slot and in `TenantConnections`.
* The temporary refusals of `@issue` and `@accept` that slices 4 and 7 add and slices 6 and 8 remove.

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
* `@issue(key:)` as an argMapping, `"id: resultatId"`, with a field of the issuing type named like B's key
  field refused and `@inaccessible` minted on the type's other fields (the 2026-11-10 draft and its round-2
  answer): composes, but adds a new argMapping path root, a name rule that only covers the key `key:`
  names, and fields that exist on A's type yet no client can read.
* Absorbed fields classified as fields and hidden from the emitted schema: needs a population of modelled
  but not emitted elements, since every field binding hangs off `graphitron_element`.
* Allowing only fields B also defines on the issuing type: the build cannot check that from A.
* A second key `@key(fields: "id voucher")` beside the plain one, with A publishing `id`: a router can
  satisfy B's plain key from A as well, and a plan that takes it denies quietly under RLS.
* B's protected fields declaring `@requires(fields: "voucher")` with the voucher `@external`: makes the
  requirement explicit, but composition must satisfy it from every path to those fields, and A, the only
  subgraph with the voucher, cannot be entered to fetch it.

* `@issue(key:)` as an optional `@key`-style field set defaulting to every other field (the round-3 draft):
  every non-voucher field must be absorbed, so an explicit `key:` could only restate the default.
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

Notes, not blocking: the "Response to review round 1" entry for Finding 3 still says B coerces "by its
key columns' types" in "one canonical form". The body and the later paragraph now say B parses with its
key field's scalar from the wire form, and the stale sentence could mislead an implementer. `@issue(key:)`
targets are field names, so a B key with a nested selection (`@key(fields: "org { id }")`) is neither
supported nor listed among the rejections; one line saying which would close it.

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
