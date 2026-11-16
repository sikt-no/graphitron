---
id: R1119
title: "Signed voucher handover: one subgraph vouches, another grants through RLS"
status: In Progress
bucket: feature
priority: 3
theme: service
depends-on: []
created: 2026-10-28
last-updated: 2026-11-16
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

**The outcome.** A marks a field `@issue`. Graphitron then attaches a signed *voucher* to each row that A's
own rules let the caller read. The router carries the voucher to B. B marks the matching field `@accept`.
Graphitron checks the voucher there and runs the read, and every read below it at any depth, on a
separate database connection, set up for the value A vouched for (the owner, say). What that connection
may read is what B's policies unlock for that value, and only the vouched entity and the fields below it
read on it. A voucher that fails any check fails the whole request before any SQL on the covered tables
runs: a bad voucher means tampering or a deployment fault, so nothing in that request is answered.

A voucher carries two things that do different jobs. The *vouched-for fields* are the access decision:
they decide which connection the entity reads on, and B's policies test them. The *lookup part* is
navigation: it lets B pick up the row where A left off, and grants nothing.

The consumer supplies the signing and verifying keys, the RLS policies, the caller on the per-request
`SessionState`, and, in B, a session mount that sets the vouched-for values on the connection.

### Terms

| Term | Meaning |
|---|---|
| *Voucher* | A signed token Graphitron computes for one row in A and verifies in B. It is Graphitron's own format, decoded the way a node id is. |
| *Issuing type* | The type in A that carries the `@issue` field. |
| *Absorbed fields* | Every field of the issuing type other than the voucher. Their values are sealed inside the voucher, and A does not publish them. |
| *Vouched-for fields* | The absorbed fields named by `vouchesFor:` on both markers. They say whose rows are vouched for, and they are the access decision. In the first client's case this is the owner's id number, a different person from the caller. |
| *Lookup part* | The other absorbed fields: the key of the vouched row in B. Navigation only. |
| *Scope* | A name both markers agree on, so a voucher issued for one purpose is not accepted for another. |
| *Caller* | The user the request is for, a string the consumer puts on `SessionState`. A voucher is only valid for the caller it was issued to. |
| *Vouch id* | A random id shared by the vouchers one operation issues under one scope. An audit field only: Graphitron logs it at issue and on every redemption or rejection. |
| *Vouched connection* | A database connection in B, one per vouched-for value, keyed by a `ConnectionKey` carrying that value. The consumer's ordinary mount runs on it, reads the value off `MountedSession.key()`, and sets it for the policies. |

### Example

The first client is an admissions service. In the SDL, `Soknad` is an application, `VgsResultat` an upper
secondary school result, and `eierId` the id of the person who owns the result (the applicant).

```graphql
# Subgraph A: the issuer. VgsResultat is A's own table of references to results, written the way B's key
# looks: `id` is B's key field, read here from A's `vgs_resultat_id`, and `eierId` is the vouched-for field. `@issue`
# absorbs every other field of the type into the voucher, and A publishes `voucher` alone. No service, no
# token code, no `@key` written.
type Soknad @table(name: "soknad") @key(fields: "id") {
  id: ID! @nodeId
  vgsResultater: [VgsResultat!]! @reference(path: [{key: "soknad_vgs_resultat_soknad_fkey"}])
}
type VgsResultat @table(name: "soknad_vgs_resultat") {
  id: ID! @field(name: "vgs_resultat_id")
  eierId: String! @field(name: "eier_id")
  voucher: String! @issue(scope: "vgs-resultater", ttl: "PT5M", vouchesFor: "eierId")
}

# Subgraph B: the owner. The real, table-bound entity. `voucher` is the proof field: not a column, not
# selected. `eierId` is the vouched-for field the voucher's value parses into, kept from clients.
type VgsResultat @table(name: "vgs_resultat") @key(fields: "id") {
  id: ID!
  eierId: String! @inaccessible
  voucher: String! @accept(scope: "vgs-resultater", vouchesFor: "eierId")
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

Slices 1 to 7 are on trunk: A issues vouchers end to end, and B captures `@accept`, states its keys and
publishes the right SDL. A schema using `@accept` is still refused with `VOUCHER_ACCEPT_NOT_GENERATED`,
because B does not yet verify vouchers. Slice 8 is what remains, in two parts: 8a, the dispatch, can be
built now; 8b, the fields below the entity, waits on R876's flip of the child fetchers; see *Slices*. The
example spells the vouched-for argument `vouchesFor:`, the name slice 8a gives it; the shipped spelling is
`claims:`.

## First-client docs draft (for `federation-keys.adoc` and the security guide)

"To let another subgraph read data your user's token does not cover, write the row type the way the
other subgraph's key looks, each key field reading its value from your own column, add the field whose
rows these are (the owner, say), and add a field `voucher: String! @issue(..., vouchesFor: "owner")`.
The voucher absorbs those fields: they are sealed inside it and your subgraph publishes the voucher
alone, so a router can reach the other subgraph's data only through it. `vouchesFor` names what you vouch
for, whose rows these are; the other fields are the row's key, which only tells the other subgraph which
row to read. Name and type them exactly as the other subgraph's key fields and vouched-for fields: your
subgraph cannot check that, and a mismatch shows up as every voucher being rejected. Graphitron signs a
voucher for each row your own rules let the caller see, bound to the caller you pass to
`SessionState.of`; both subgraphs must pass the same caller for the same user (for example the token's
subject), or every voucher is rejected. In the subgraph that owns the data, add the vouched-for field (it
may be `@inaccessible`) and `voucher: String! @accept(..., vouchesFor: "owner")`. Graphitron checks the
voucher, takes a separate database connection for the vouched-for value, and runs your mount there; the
mount reads the value from `session.key()` and sets the session state your policies read. Your policies
decide what that value unlocks. That entity and everything below it read through that connection,
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
                                                              one vouched connection per group,
                                                                consumer's mount reads key()
                                                              SELECT the entity, and every field
                                                                below it, on that connection
                                                              release at operation end
```

1. *Issue (A).* A's field is an ordinary read below a parent. The `voucher` field of each returned row is
   computed, not a column, and only when selected, which a router does only when it plans the jump to B.
   For each row Graphitron builds the token (version, vouch id, scope, caller, expiry, key id, and each
   absorbed field's name and wire value) and signs it, together with the entity type name, using the
   runtime's signing key. The vouch id is a random 128-bit value minted once per operation and scope,
   logged by Graphitron as the audit handle; the expiry is the operation's start plus `ttl`. The decision is the read itself: a row A's rules hide
   is never returned and never signed. Under a mutation payload the rows are read and signed inside the
   mutation field's transaction, and a field that rolls back is `null`, so a voucher for rows that are
   gone never reaches the router.
2. *Carry.* The router fetches `voucher` from A, the only field A publishes on the type, and sends
   `_entities` representations `{voucher}` to B. The field is `@inaccessible`, so clients never see it.
3. *Verify (B).* The dispatcher resolves the representations through the voucher key, the way it resolves
   a node-id key: decode the opaque value into the authored key's columns, then the ordinary SELECT.
   Before any connection is taken, Graphitron checks each voucher:
   * the signature, over the token and the representation's `__typename`, against the runtime's
     verification keys;
   * the expiry, with a fixed tolerance for clock skew between A and B;
   * the scope, against `@accept(scope:)`;
   * the caller, against the caller on this request's `SessionState`;
   * that the signed vouched-for section names exactly B's `@accept(vouchesFor:)` fields;
   * that the lookup section carries exactly the fields of B's authored key.

   Each voucher is verified on its own. The whole token is signed, the lookup part included, but the
   lookup part is inside the token for federation's sake (A withholds the key fields so the router can
   only jump on `voucher`), not as a grant: any row B's policies admit for the vouched-for value is
   readable on that connection anyway. One failure fails the request with a request-scoped
   `VoucherRejectedException`, thrown out of `GraphQL.execute` the way a rejected mount's
   `SessionMountException` is. Representations of the plain key, from elsewhere in the
   graph, are not part of this check and resolve under the caller's own RLS. Graphitron logs the vouch
   id, scope and outcome of every redemption and rejection, never the token or its values.
4. *Route.* The verified vouchers group by their connection key: the tenant part and the vouched-for
   values. Vouchers carrying the same value share one connection, whichever operation or scope issued
   them. For each group the per-operation carrier takes one connection keyed by that `ConnectionKey` and
   runs the consumer's ordinary mount on it. The mount reads the key it is mounting for from
   `MountedSession.key()`, the vouched-for values typed as B's fields parse them, and sets the session
   state the policies read, as it sets identity. It refuses by throwing; a throwing mount is a
   `SessionMountException` like any other, the connection is evicted and the request fails. The unmount
   receives the same `MountedSession`, so it can clear what it set, and on PostgreSQL the reset at
   release backstops it.
5. *Read below.* The group's SELECT runs on the vouched connection. Each resolved row is returned as a
   `DataFetcherResult` whose `localContext` carries the connection key, and graphql-java hands that value
   to every field below. So child acquisitions and their DataLoader batches are keyed by it: each field
   below loads in one batch per connection key, a batch never mixes two, and a field outside the subtree
   uses the request's own connection, as tenants already do. A `$session` binding below reads the handle
   the vouched connection's mount returned, since `$session` is read through the statement's own
   connection.
6. *Release.* At operation end every vouched connection is unmounted and released with the rest of the
   carrier's entries.

### One connection per vouched-for value

An operation takes one vouched connection, for one vouched-for value. Vouchers carrying it, from a list
of results or several parents' lists, share that connection. An operation whose vouchers carry more than
one vouched-for value fails with an error that names the limit, not `null`, so the limit is not mistaken
for a denial. The limit lives in one place so it can be raised later.

Every entity of the group reads through a connection mounted for that value, so a subtree is scoped to
its entities by the graph it was reached through, and its reads to the value by RLS. Sharing the
connection across operations and scopes on A grants nothing new: what it may read is decided by the
value and B's policies on it, and every voucher on it was signed for that value and checked against this
request's caller.

### What B's policies test

The grant is everything B's policies unlock for the vouched-for value; the lookup part is not part of
it. B needs no notion of application or subject: the policies test the value, for example
`eier_id = current_setting('app.vouched_owner')` on the result table and a join from each child to its
result row, where the mount wrote the setting from `key()`.

The first client vouches for documents and their owner: A's issuing type absorbs `id` and `eierId` and
names the owner as vouched for (`@issue(scope: "dokumenter", ttl: "PT5M", vouchesFor: "eierId")`); B
names the same (`@accept(scope: "dokumenter", vouchesFor: "eierId")`), so `id` is the lookup part and
decodes onto B's authored `@key(fields: "id")`. Every voucher for one owner shares the owner's vouched
connection, and a policy on the owner admits that owner's documents on it. If a subtree below a document
leads to the owner's other documents, those are admitted too: that is the grant A made. A consumer that
wants a narrower grant puts more into `vouchesFor:` (a document id, say), at the cost of one connection
per value.

### What each side publishes, and why A withholds the absorbed fields

Both subgraphs' published shape is derived, not written. On a type with an `@issue` or `@accept` field
Graphitron mints `@key(fields: "voucher")` and puts `@inaccessible` on the voucher field. In A the key is
`resolvable: false` and is the type's only key; in B it is `resolvable: true` and sits beside the authored
key. Both are minted applications, the mechanism that gives a node type its `@key(fields: "id")`, so the
`@link` imports `@inaccessible` with no extra step, and A and B cannot disagree about the key shape.

A withholds the absorbed fields for two reasons:

* *A published key field would bypass the voucher.* A router plans a jump from A to B through any key of
  B whose fields A can supply, and any field A publishes on the type counts, whether or not it is in A's
  own `@key`; `@inaccessible` hides a field from clients, not from the planner. If A published `id`, B's
  authored `@key(fields: "id")` would carry the jump, B would resolve the entity under the caller's own
  RLS, and the client would get the quiet `null` this item exists to remove.
* *Any other A-only field would break composition.* It would exist only in A, on a type no router can
  enter A for, so a query reaching the type through B that asked for it could not be planned.

With only `voucher` and its key published, the supergraph composes with the jump on `voucher` (row 4 of
the round-2 composition table under *Reviewer findings*). An issuing type is not a node: a node gets a
minted `@key(fields: "id")`, which on A would name an absorbed field. B's authored key is untouched, so
the type still resolves by plain `id` from anywhere else under the caller's own RLS; a voucher only ever
adds access. This is federation only: `Query.node`, `@node` and the relay id know nothing about vouchers.

### Rulings this plan rests on

Decisions the user made on this item; each is stated where it applies below.

* The vouched-for fields are required on both markers, and both name them, B refusing a voucher whose
  signed vouched-for section differs (2026-11-12). A voucher without them would have no connection of its
  own to read on.
* One vouched-for value per operation, and vouchers sharing the value share a connection across
  operations and scopes on A (2026-11-12).
* The vouched-for fields are the access decision and the lookup part is navigation only (2026-11-16).
  The mount needs only the vouched-for values, to set them on the connection; it reads them from the
  connection key it is mounting for, `MountedSession.key()`, as R1185's connection hook takes the whole
  key. No per-voucher detail reaches the mount: the covered lookup keys and a `Vouch` type are dropped.
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

## Implementation

* *Directives.* `@issue(scope:, ttl:, vouchesFor:)` on a field of a table-bound type (A); it absorbs
  every other field of the type, and `vouchesFor:` names the absorbed fields that are vouched for, the
  rest being its lookup part. `@accept(scope:, key:, vouchesFor:)` on a field of a table-bound,
  resolvable type (B): `key:` is the field set of the authored `@key` whose columns the voucher's lookup
  part decodes to, and may be omitted when the type has exactly one authored resolvable key;
  `vouchesFor:` is the field set the voucher's vouched-for values parse into, fields of B's type parsed by
  their scalars as key fields are (each must be a field of the type, and may be `@inaccessible` so the
  owner is not published to clients). `vouchesFor:` is required on both: a voucher without it would have
  no connection of its own to read on, and the request's connection is the caller's. The vouched-for
  values are the rows' owner, not the requesting caller, which is checked separately against
  `SessionState`. Both ends name them, so the issuer states what it vouches for and B refuses a voucher
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
  * Owned by the voucher stage, decided from the transcription and the capture entries: more than one
    voucher field on one type, whichever markers they carry (one code, `VOUCHER_FIELD_REPEATED`, at each
    marker after the first); `@issue` on a type with an authored `@key` (it would let a router reach B
    without the voucher); `@issue` on a type
    `graphitron_node` holds, authored or inferred from the table's node metadata, since the minted node
    key would name an absorbed `id`; an `@issue` that absorbs nothing; an absorbed field that is a list or
    an object, or carries `@nodeId`, `@service`, `@routine`, `@reference` or another directive that gives
    it a value of its own (read off `graphql_directive_application`, not off a producer fact); an absorbed
    field whose column does not resolve (a member with no component row); either
    marker in a graph with no `<sessionState>` (no `store_graph_session_supplied` row), since the caller
    rides on `SessionState`; `@accept` in a graph with a `<tenantColumn>`, since version 1 has no tenant
    pairing (read off the graph's tenant-column configuration; the implementer confirms that relation is
    upstream of the stage, or moves the refusal to the first stage it is). And the vouched-for fields, on either marker: a `vouchesFor:` naming no field, a field named
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
    either marker is one code for both: the session-state, federation-link, empty-vouched-for and
    repeated-vouched-for refusals say "a voucher marker".
* *Voucher codec.* One generated class owns the format: version byte, vouch id, scope, caller, expiry,
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
  caller.
* *Generated, not shipped.* The codec, `VoucherRejectedException`, the key provider interface and its
  default Ed25519 implementation are emitted per consumer, as `NodeIdEncoder` is
  (`NodeIdEncoderClassGenerator`), and only in a build whose store holds a marker entry. Generated code has
  no runtime dependency on Graphitron, and `graphitron-jakarta-rest` is an optional serving library a
  consumer verifying a voucher need not take. JDK Ed25519 (`KeyFactory` and `Signature` with "Ed25519")
  compiles at Java 17.
* *Runtime surface.* All of it appears only in a build with a marker:
  * a key provider on the runtime: a signing key on A, verification keys by key id on B, so the public
    key can sit in B and rotation adds a key id. The default is over JDK Ed25519; the interface lets a
    consumer sign remotely, for example through Vault Transit. It is a required parameter of every
    `Graphitron.runtime(...)` overload, so omitting it is a compile error;
  * `SessionState.of(caller, mount, unmount)`, the caller a `String` both subgraphs read, so a voucher is
    useless to another user. Still one `of` form per build, so a lambda never meets an overload. The gate
    is "the graph issues or accepts", read as one stored build-shape fact; today it reads
    `VoucherRuntime.vouches`, which sees issuing only, so an accept-only build gets no caller;
  * `MountedSession.key()`, the `ConnectionKey` the connection is mounted for, declared wherever
    `ConnectionKey` is generated (multi-tenant or accepting builds). The mount reads the vouched-for values
    from it. In an untenanted accepting build the request's own mount sees a key with both parts empty, a
    key like any other; the docs say so. Whether `MountedSession.tenant()` retires in slice 8a or with
    R1185 is open.
* *Key synthesis.* The minted key and `@inaccessible` show in the served `_service.sdl` on both sides,
  and A's issuing type shows `voucher` alone. B's authored keys, `@node` and the relay id are not touched.
* *Clock skew.* B accepts a voucher up to a fixed 30 seconds past its expiry, so a small difference
  between A's and B's clocks does not reject fresh vouchers. The constant lives on the codec, which is
  the one place that reads it.
* *Issue.* The fetcher that reads an `@issue` row selects the voucher components' columns whenever
  `voucher` is selected (nothing else can select them, since they are not fields), and computes the token
  after the rows are read, one signature per row, the caller read from the carrier's `SessionState`. A row
  is signed only when `voucher` is selected, which a router does only when it plans the jump to B, so the
  signing cost follows what B is asked for; no ceiling beyond the page size the parent field already sets.
  Expiry is fixed per operation. It works at any position below a parent, including under a mutation
  payload, where the field's transaction rule (a rolled-back field is `null`) keeps a voucher for
  rolled-back rows from the client. It is emitted as an `emit` query and emitter reading the component
  relation, not as a branch of `TypeFetcherGenerator`.
* *Verify and group.* The dispatcher's voucher handler decodes and checks each voucher as in step 3,
  builds a `ConnectionKey` per representation from its optional parts (the tenant part from the
  alternative's routing source, the vouched-for part from the verified voucher), and groups on that key
  once. There is no voucher branch beside the tenant branch: every alternative groups by its key, which is
  what R1185 puts behind the consumer's hook. In version 1 an accepting graph has no tenant column
  (*Rejections*), so the voucher alternative's tenant part is empty and `EntityTenantRouting.fromWalk`,
  which runs only in multi-tenant builds, never meets it. No exemption is added there and no new
  reference to the classified model's `TenantBinding`. Tenant pairing, later, gives the voucher
  alternative the tenant source of the authored key it decodes onto (`graphitron_accept_key`), paired in
  `EntityKeyQuery` and carried through `EntityTenantRouting.Source`.
* *Connection key.* Every build that writes the `localContext` slot, multi-tenant or accepting, carries a
  generated `ConnectionKey` there, with a tenant part in multi-tenant builds (shipped in slice 3) and the
  vouched-for part with `@accept` (slice 8a), its components the accepting fields' vouched-for fields.
  The graph's `@accept` fields share one vouched-for shape (names and scalars); differing shapes are
  refused, so the generated type is one. A single-tenant build with no `@accept` writes nothing there, so
  its output is unchanged. The shape is computed once, as one value beside `SessionIdentityRows`, at the
  point in `GraphQLRewriteGenerator` that computes `ConnectionKeyShape` today, reading the store's
  "does it accept" fact and the tenancy configuration the generator still takes, and is handed to every
  generator that needs it. Today it reaches only `ConnectionRuntimeClassGenerator`; the dispatch, the
  instrumentation, the node fetcher and the connection helper take the tenant key type alone, and slice
  8a hands them the shape. It is not added to `GraphitronSchema`, and no site evaluates "has accept"
  itself. `ConnectionKeyShape.writesSlot()` stops meaning "multi-tenant": the tenant machinery gates on
  the tenant part, the slot on either part. The routing hand-down remains the one writer of the slot and
  the ownership guard's owner list follows it.
* *Acquisition.* The carrier's entry resolution takes the `ConnectionKey`; a key with a vouched-for part
  mints a separate entry, whose mount sees it through `key()`, and the entry is reused for every voucher
  carrying the same value, across operations and scopes on A. A rejected mount evicts the connection and
  fails the request, as today.
* *Whether the dispatch is emitted.* Today the plan and the schema class decide it from the walk's
  entity map, which never sees the minted voucher key. That decision is one fact R876's node "The facade,
  dev executor, registrations and schema class read the store" states once; slice 8a either states it
  (the dispatch is emitted when the store holds an entity with a resolvable alternative) and marks that
  part of R876's node taken, or leaves it to R876. It adds no voucher-specific predicate beside the
  walk's.
* *The walk's stand-down for B's voucher field.* It reads the `VOUCHER` binding alone, applied at the one
  point the walk is handed its registry, as the membership removal is (`AttributedRegistry.of`), not as a
  check in each walk consumer. It goes with the walk.
* *Configuration.* No new POM element: the markers switch the feature on, `<sessionState>` is already
  required for the caller, and keys are runtime configuration.
* *Audit logging.* Graphitron logs the vouch id, scope and outcome at issue and on every redemption and
  rejection, at a named level and logger the docs state. Nothing reads the vouch id otherwise.
* *Redaction.* The voucher is a credential: confirm generated code and the dispatcher never log
  representations, the token or its values, or echo the voucher in errors, and that
  `VoucherRejectedException`'s message names the check that failed and nothing from the token.
* *Out of scope.* Key management and rotation procedure, a reference mount and policies (a worked example
  goes in the how-to), router header propagation, a per-row decision beyond A's own read.

### Slices

Each slice lands green on trunk, and a build that uses neither marker is unchanged, because every emitted
unit, parameter and `of` overload is gated on a marker entry in the store.

| Slice | What it delivered | Shipped at |
|---|---|---|
| 1. Column names | `sql_column_spelling`; the occurrence rule and its defect arms join onto it | `b4b9c96b` |
| 2. Minted keys and entity key alternatives | `graphitron_minted_key` (`8c719f9b`); the `@key` refusals as entry defects of `entity-key-alternatives` (`5c649178`); `graphitron_key_field_binding` (`8d6c63a2`); an authored key spelling `id` stands the node key down (`3b2ac078`) | see cell |
| 3. Connection key | A generated `ConnectionKey`, tenant part only, in the `localContext` slot | `f1c4b26a` |
| 4. A's facts and published shape | `@issue`, its membership, components, vouched-for rows and refusals, the `VOUCHER` binding, the `ISSUE` key and `@inaccessible`, `EmittedRegistry`'s removal arm | `08ebe10d`, `2ca51f42`, `62b43cd8`, `b0bbbd25` |
| 5. Codec | The generated codec, key provider and `VoucherRejectedException` | `7b656753` |
| 6. Issuing | `VoucherKeys` on the runtime, the caller on `SessionState.of`, the store-rendered `Vouchers` unit signing per row; the temporary `@issue` refusal removed | `a46e60a8`, `c2f16ab1`, `d24dc49c` |
| 7. B's facts | `@accept`, its `VOUCHER` binding, vouched-for rows and refusals, the `ACCEPT` key and `@inaccessible`, `graphitron_accept_key`, the voucher alternative stated in the store; refused until generated | `f8ef399f`, `24b24899`, `6dadbfcd` |
| 8a. Dispatch | Pending; below | |
| 8b. The subtree | Pending; below | |

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
  `ConnectionKeyHandDown`, and store-emitted fetchers acquire by `dslFor(env, ConnectionKey)`. Slice 8
  extends that key with the vouched-for part rather than introducing its own.

Slice 8 splits in two because of R876's flip. The `_entities` dispatch already renders from the store
(`EntityKeyQuery`, `EntityFetcherDispatchClassGenerator`), so the dispatch can be built now. The fields
below the entity cannot: walk-side child fetchers (`TenantDslEmitter`) rebuild the connection key from its
tenant part and, in an untenanted build, read the default connection; store-side children inherit only
where `graphitron_occurrence_connection` states a row, and its rule joins `store_graph_tenant_column`.
Patching the walk's emitters would extend code R876 is deleting.

**Slice 8a, the dispatch.** Buildable now, with the refusal kept, since fields below the entity do not
yet read on the vouched connection.

* The rename: `claims:` to `vouchesFor:` on both markers, through the store and the docs together, since
  the store already uses "claim" for binding claims. `graphitron_ast_issue_claim_entry`,
  `graphitron_ast_accept_claim_entry`, `graphitron_voucher_claim`, `graphitron_accept_claim` and their
  rules, the `VOUCHER_CLAIM*` codes, `directives.graphqls`, `issue.adoc`, `accept.adoc` and the
  diagnostics glossary. A `feat!` with a `BREAKING-CHANGE:` footer, though no consumer uses it yet.
* The verifier's vouch-id agreement check and its codec tests go; Graphitron logs the vouch id
  (*Audit logging*).
* The vouched-for part of `ConnectionKey`, the shape handed to every generator that needs it, and
  `MountedSession.key()` (*Connection key*, *Runtime surface*). The caller gate on `SessionState.of`
  reads "issues or accepts".
* `@accept` in a `<tenantColumn>` graph refused (*Rejections*).
* `EntityKeyQuery` composes the voucher alternative, its tenant part empty; the dispatcher's verify and group on the whole key; vouched acquisition (*Verify and
  group*, *Acquisition*).
* Whether the dispatch is emitted, read from the store or left to R876 (*Whether the dispatch is
  emitted*).
* The walk's stand-down reads the `VOUCHER` binding alone. `VoucherBindingQuery` reaches a voucher field
  through A's members, so B's accepting field, which has none, would drop out of it and be asked of the
  walk; the stand-down takes the binding and the projection takes the components.
* The redaction check (*Redaction*).
* Execution tests for the entity's own row on the vouched connection, every verification failure, the
  limit error.

**Slice 8b, the subtree.** Taken up with or after R876's nodes "A child whose followed key lands on the
tenant column is routed by its row's tenant" and "`TypeFetcherGenerator` reads the store, one family of
field at a time", for the children it covers.

* `graphitron_occurrence_connection`'s grain widens from "one producer of a database-per-tenant graph"
  to "a producer of a graph whose generated code carries a `ConnectionKey`", so the children below an
  accepting entity get `INHERIT` rows in an untenanted graph too. A child inherits the whole key its
  producer hands down, which is already what `INHERIT` means; no vouched-for part is restated on child
  rows, and the part `CHECK` stays `'TENANT'`. `_entities` itself does not change and gets no row: it
  runs as today, on the connection the dispatcher acquired for the vouched-for value, and hands that key
  down as it hands down a tenant's (2026-11-16). R876 should carry a note that the relation's grain covers
  accepting graphs, so a rewrite of its rule does not narrow it back to tenancy.
* Store-side emitters below the entity read it; a `@service` child and a connection-typed child under the
  entity acquire from the inherited key.
* `VOUCHER_ACCEPT_NOT_GENERATED` goes, with the "refused for now" sentence in `accept.adoc` and the
  directive definition.
* The remaining execution tests under *Tests*: the nested read, a field outside the subtree, release.
* Before Done: the composition check against the fixture's two served SDLs, recorded in the item (see
  *Tests*, Served SDL), and the first-client docs draft moved into `federation-keys.adoc` and the security
  guide, with a how-to carrying a worked mount and policies.

## Tests

**Codec.**

* Round trip, including an `ID` field over an integer column on A against an integer key column on B, a
  UUID key, an `Int` key, and a two-field key whose fields are declared in either order.
* Each of these fails verification, and nothing else does: a tampered signature or key value; a wrong
  key id; an unknown version; expired beyond the skew tolerance (and accepted within it); a wrong scope;
  a wrong caller; a voucher presented under another `__typename`; a vouched-for section naming other
  fields than B's `vouchesFor:`; a lookup section naming other fields than B's key; a missing or extra
  vouched-for field; a value that does not coerce.

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
* Output for a build with no marker unchanged; `SessionState.of` gains the caller, `MountedSession`
  gains `key()` and `Graphitron.runtime` gains the key parameter only when the matching marker is
  present.

**Compilation tier.** Generated issue, dispatcher and acquisition code compiles at Java 17.

**Execution tier.** Real Postgres, an RLS fixture beside the existing session-mount fixture, A and B in
one fixture.

* Each returned row gets its own token, all share one vouch id, and Graphitron logs it at issue and at
  redemption, never the token or its values.
* A valid voucher exposes the entity and a three-level nested read under a policy that otherwise hides
  the rows.
* A list of results, or several parents' lists, for one owner takes one connection, and the mount sees
  the owner once, in `key()`.
* Vouchers from two scopes, or two issuing operations on A, for the same owner, presented in
  one operation on B, share the connection.
* An operation mixing two owners' vouchers fails with the limit error.
* A field outside the entities' subtrees cannot read the rows the vouched-for value unlocks.
* Every verification failure above, and a mount that throws on a vouched connection, fails the request,
  and the policy hides the rows.
* A mutation payload issues vouchers on commit, and none from a field that rolled back.
* Vouched connections are unmounted and released at the end.
* With the owner as vouched for (`vouchesFor: "eierId"` on both markers): the mount sees the owner in
  `key()`; a policy on the owner admits the owner's other documents on the vouched connection; and an
  owner who is not the caller is admitted, the caller check being separate.

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
* A no-code default for B: Graphitron could set a documented session setting itself (the vouched-for
  values) on a vouched connection before the mount runs, so policies read it and an unchanged mount
  suffices. Additive to the contract above, so it can follow.
* Key provider shape and default: Ed25519 over JDK keys, with the public key distributed to B; whether the
  default also reads PEM or only takes key objects.
* Replay: no per-voucher nonce. A voucher is bound to scope, caller, type, key and a short expiry, and the
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
* Whether `MountedSession.tenant()` retires in slice 8a or with R1185, which rewrites the manual's
  tenant pages. Either keeps the key the one source; retiring it is a breaking consumer change.

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
* The temporary refusals of `@issue` and `@accept` that slices 4 and 7 add and slices 6 and 8b remove,
  `VOUCHER_ISSUE_NOT_GENERATED` and `VOUCHER_ACCEPT_NOT_GENERATED`, and the "refused for now" sentences
  of their reference pages and directive definitions.
* `VOUCHER_ISSUE_TWICE`, folded into `VOUCHER_FIELD_REPEATED` in slice 7.
* The hardcoded `id` field and `NODE` role of `graphitron_key_field_binding_rule`'s minted arm, which
  reads the minted key's own field set from slice 7.

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
