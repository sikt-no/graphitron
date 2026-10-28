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
turns that vouching into data access. In Graphitron terms: a field in subgraph A marked `@voucher`
mints a short-lived, signed *voucher* (a token naming who may read what, issued by A); the type that
voucher refers to, in subgraph B, redeems it. Redeeming verifies the token and then calls a
consumer-written database routine on B's pinned connection, which sets session state that B's row-level
security (RLS: Postgres policies that decide per row what a connection may read) policies consult. A
caseworker whose own JWT does not cover an applicant's upper-secondary results can then read exactly the
rows the voucher names, for as long as the voucher lives, and nothing else. A rejected voucher makes the
field `null`; no SQL on the covered tables runs. Graphitron owns the handover (token shape, key
synthesis, dispatch, the hook seam); the consumer owns the signing key, and what a voucher grants is
decided by the routine and the policies they write.

```graphql
# Subgraph A: decides, mints. Reaching the parent row under A's own RLS already is the decision;
# an optional `decision` method can add more.
type Soknad @table(name: "soknad") @key(fields: "id") {
  id: ID! @nodeId
  vgsResultater: VgsTilgang
    @voucher(scope: "vgs-resultater", subject: "$source.fodselsnummer", ttl: "PT5M")
}
type VgsTilgang @voucher(scope: "vgs-resultater")   # stub; Graphitron synthesises the resolvable:false key

# Subgraph B: owns the data, redeems. No root field exposes VgsResultat.
type VgsTilgang @voucher(scope: "vgs-resultater") {
  subject: String!                                   # read from the verified claims
  resultater: [VgsResultat!]! @reference(...)        # ordinary table read, filtered by RLS
}
type VgsResultat @table(name: "vgs_resultat") { fag: String! karakter: Int! }
```

```sql
-- B: the consumer's redeem routine. Adds to the connection's grants; the policy reads them.
create function vgs.redeem(p_scope text, p_subject text, p_user text, p_expires bigint) returns void ...
  -- checks p_user against the identity the mount set, then appends p_subject to app.vgs_grants
create policy vgs_read on vgs_resultat for select using (
  fnr = current_setting('app.user_fnr', true)
  or fnr = any (string_to_array(nullif(current_setting('app.vgs_grants', true), ''), ',')));
```

## The mechanism: how a voucher becomes data access

A voucher arrives inside an `_entities` representation, which is field-level data inside an operation
that is already running. The existing `<sessionState>` mount cannot take it: mount runs once, at
connection acquisition, from request-level payload (headers). So the handover needs a second hook on the
same pinned connection, run per redeemed voucher, after the mount and before any SQL on the covered
data. The open question is what that hook does to make RLS see the grant. Options, from weakest to
strongest enforcement:

[cols="2,3,3"]
|===
| Mechanism | How RLS sees the grant | Cost and weakness
| Session setting appended by a routine (recommended first step) | The routine calls `set_config('app.vgs_grants', ..., false)`, accumulating subjects so several vouchers in one batch coexist; policies read `current_setting`. Same shape and same session-scoped contract as the mount. | A session setting is a convention: any SQL on the connection, including a consumer `@service`, can overwrite it. Acceptable where B's own code is trusted; this is the documented posture of the mount.
| Grant ledger table written only by a definer-rights routine | The routine inserts `(pg_backend_pid(), scope, subject)` into a table the application role cannot write; policies join on `pg_backend_pid()`. Unmount deletes the rows. | Unforgeable by consumer SQL, one extra write per voucher, and a cleanup that must not be skipped (crash recovery needs a pid-reuse guard).
| `SET ROLE` to a grant role | Policies are written per role. | Role switching is connection-wide and cannot express a per-subject grant; only fits coarse scopes.
| Verify in the database | The routine verifies the token itself (HMAC with pgcrypto) instead of trusting Java. | Removes the trust in B's Java for the signature; Ed25519 has no native Postgres verifier. Optional layer on top of either option above, because the hook receives the raw token as well as the claims.
|===

Graphitron's contract is the seam, not the mechanism: it calls a consumer-named routine or static
method with the verified claims, on the pinned connection, session-scoped. Which of the options above the
routine implements is the consumer's choice, and it can move from the first to the second without a
schema change. What the voucher covers is then controlled in three places, each with one owner:

* *Scope and subject in the SDL.* `@voucher(scope:)` names the grant; `subject:` says what it is about
  (a column of the parent row, a `@service` result, or a context value). A scope is a string both
  subgraphs agree on; the supergraph composes it as a directive argument so a mismatch is visible.
* *The redeem routine.* Maps `(scope, subject, user, expiry)` to session state and may refuse
  (wrong user, unknown scope, expired). This is the "custom auth logic" seam.
* *The RLS policies.* Decide which tables honour which grants. Coverage of the data needs is therefore a
  policy question, answered table by table, and testable in the database without Graphitron.

## What exists today, and what is missing

[cols="2,3,3"]
|===
| Need | Today | Gap
| Per-request identity on the pinned connection | `<sessionState>` mount and unmount on the owned-connection engine, signature-read at build time, session-scoped contract, rejected identity fails the request. | None; the voucher hook reuses its machinery and contract.
| A reference-only stub type with a non-column key | `@key(resolvable: false)` on a non-table type is accepted (fixture `FilmRefStub`); `@node` synthesises its own `@key`. | `@voucher` synthesises the key (one opaque `voucher: String!`, `@inaccessible`) the way the node synthesiser does, resolvable on B and not on A.
| Resolve an entity that is not a jOOQ row | Resolvable `@key` requires `@table`; custom `fetchEntities` may only return `Record`s. | A new key alternative arm: decode the voucher, verify, redeem, return a carrier of the claims. Type resolution must handle a non-`Record` result.
| Mint at A | `@service` children batch and read `contextArguments`; `$source.<column>` binds a parent column. | The `@voucher` field fetcher: build the claims, sign through the authority, return the carrier, `null` when the optional decision refuses. Batched, one signing call per operation.
| Signing key and verification | None, and Graphitron should not hold keys. | A small authority interface the consumer implements (`sign`, `verify`, both batch-shaped); Vault Transit is one implementation from the design page, JWT another.
| Run a hook mid-operation on the pinned connection | Mount and unmount only, at acquisition and release. | A `<voucher>` configuration block with a `<redeem>` method, same signature reading, same autocommit and session-scoped rules. Unmount must clear grants; documented.
| A table read under a non-table parent | Record-sourced child fields exist. | Verify `resultater` filters by the carrier's `subject` without a table parent.
|===

## Implementation

* *Directive.* `@voucher(scope:, subject:, ttl:, decision:)` on a field (mint) and `@voucher(scope:)` on
  an object type (redeem on B, reference-only stub on A, told apart by whether the subgraph configures
  a redeem hook). Declared by the generator like `@node`; consumers do not write the definition.
* *Classification.* A field with `@voucher` must return a `@voucher` type. The type gets a synthesised
  `@key(fields: "voucher")` with an `@inaccessible` `voucher: String!`; resolvable only where a redeem
  hook is configured. Reject a `@voucher` type that also has `@table`, a scope mismatch between a field
  and its return type, and a redeem hook without an authority.
* *Mint.* Generated fetcher builds the claims (issuer, scope, subject, user from the session identity,
  expiry from `ttl`), calls `VoucherAuthority.sign` once per operation through a DataLoader, and returns
  the carrier. The optional `decision` is a `@condition`-style method over the parent row; `null` skips
  signing.
* *Redeem.* A new `KeyAlternative` arm in `EntityResolution` for voucher keys. The generated dispatcher
  handler verifies the batch through the authority, rejects expired or wrong-scope vouchers to `null`,
  calls the `<redeem>` hook once per accepted voucher on the pinned connection, and returns the claims
  carrier. A verification or redeem exception is an error on that representation, not a silent null,
  so an outage is not read as a denial. The dispatcher records the participant type so the default
  `resolveEntityType` finds it without a custom resolver.
* *Configuration and build.* A `<voucher>` block in the Maven plugin beside `<sessionState>`:
  `<authority>` (a class implementing the SPI, wired on the runtime) and `<redeem>`. Build-time
  signature reading as for the mount; the redeem may take the mount's `$session` handle.
* *Redaction.* The subject (a national identity number) travels in the signed token only if the
  consumer puts it there; the key field is the opaque token. Confirm generated code never logs
  representations and that the token is not echoed in errors.
* *Out of scope.* Vault client, key rotation, the policy and routine templates, router header
  propagation. A documented reference routine and policy pair lives in the how-to, not the generator.

## First-client docs draft (for `federation-keys.adoc`, with a security-guide section)

"To let another subgraph read data your user's token does not cover, the subgraph that made the access
decision marks the field `@voucher`. The subgraph that owns the data declares the matching type and names
a `<redeem>` routine. The routine receives the verified voucher and sets whatever session state your RLS
policies read. Nothing is granted unless the routine grants it, and everything it grants is dropped when
the request's connection is released." If the docs do not read this simply once the directive is chosen,
the design is wrong.

## Tests

* Pipeline tier: classification of both sides of `@voucher`, the synthesised key, and each rejection.
* Compilation tier: generated mint and redeem code compiles at Java 17.
* Execution tier (sakila example, real Postgres with RLS, the existing session-mount fixture as base):
  mint at one end, redeem at the other in one fixture; a valid voucher exposes exactly the named
  subject's rows under a policy that otherwise hides them; a second voucher in the same batch accumulates;
  tampered, expired, wrong-scope and wrong-user vouchers give `null` and the policy hides the rows; the
  grant is gone after the connection is released; an authority outage is an error.
* Served SDL: `_service { sdl }` shows the synthesised `@key` and `@inaccessible`; composed scope
  argument is visible.

## Open questions

* Is the token Graphitron-shaped (fixed claims, consumer-supplied crypto, recommended) or fully opaque
  with the consumer building and parsing claims?
* One redeem call per voucher or one per batch (the routine takes an array)? Per batch saves round trips.
* Must a grant end before the operation does, for example after the `_entities` field resolves? Child
  fields resolve later through DataLoaders, so ending it early breaks `resultater`; recommendation is
  operation scope, relying on B being reachable only through the router.
* Mechanism for the first consumer: session setting or grant ledger table.
* Should `scope` be a composed directive so the supergraph can check both sides agree?

## Other solutions we've considered

* A hand-written entity resolver bound by a service directive on B's type: more general, but it hands the
  consumer the verification and the connection work, and offers nothing for the RLS handover, which is the
  point.
* A guard hook on a table-backed entity: reuses table batching but forces a table onto the voucher type.
* Passing the voucher as a header: the router propagates headers, not per-entity data, so one request
  could carry only one voucher for all its entities.
* `@requires`, `@context` and `@fromContext`: rejected on the design page for ownership reasons.
