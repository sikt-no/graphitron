---
id: R1207
title: "An accepting type guards its vouched fields with @requires, so an issuer can be an ordinary entity"
status: Backlog
bucket: feature
priority: 3
theme: service
depends-on: [vouched-lookups-past-one-value]
created: 2026-11-23
last-updated: 2026-11-23
---

# An accepting type guards its vouched fields with @requires, so an issuer can be an ordinary entity

## Goal

An issuing subgraph (A) can put `@issue` on an ordinary entity, a node keyed by its id and returned by root
fields, instead of on a dedicated type that absorbs and withholds every field it carries. The accepting
subgraph (B) keeps a field that needs the voucher from being reached without it through federation's
`@requires`, which Graphitron mints. A client then reads `sak { hendelser }` with no hop through an
issuing type. Alf ruled the direction on 2026-11-23, recorded in
`docs/architecture/explanation/fact-model.adoc`: correctness comes from `@requires` in B, not from the
shape of A. What an author writes on both sides changes, and so does what Graphitron mints; the exact
shape is still open (below).

Split out of R1199 at its Spec review (2026-11-23). R1199 keeps the set key, which lets the vouchers in
one `_entities` call carry many vouched-for values and still resolve on one connection; this item builds
on it, since N entities in one call present N vouched-for values whichever form issues them.

## A consumer of the first form

The first form is in use. In fs-plattform, opptak issues from a dedicated type
(`VgsKompetanseoppnaelserForSokerGittNasjonalId`, every field absorbed and withheld), and kreg receives
representations carrying only `{__typename, internalGraphitronVoucher}`. Those match B's minted resolvable
voucher key (`graphitron_minted_key`, role `ACCEPT`). Dropping that key, making the voucher no key, or
retiring withholding each leaves such a representation matching no alternative. Whatever this item
retires, it plans that consumer's migration: what A and B write instead, and whether both forms are
accepted for a while.

The migration reaches the consumer's Java too. A `VouchedFor` component is named for its vouched-for
field (`ConnectionKeyShape.VouchedForPart.name`), so kreg's source reads `VouchedFor::nasjonalIdForVoucher`
after R1199, and narrowing the refusal so the key field itself is vouched for renames it: a second source
break, after R1199's. The how-to's per-entity variant is written here, once the narrowing's ruling says
whether the second-field form is a valid schema; R1199 pins the form as accepted but does not teach it.

## Guarding with `@requires`

**The ruling (Alf, 2026-11-23).** R1119's refusals of `@issue` on a keyed type, a node and a type a root
field returns, and its absorbing and withholding every authored field, were scoping for the first
iteration. This item changes those rules: correctness comes from federation's `@requires` in B rather
than from the shape of A. Recorded in `docs/architecture/explanation/fact-model.adoc`.

```graphql
# Subgraph A: Sak stays an ordinary node, keyed by id and returned by root fields.
type Sak @table(name: "sak") @key(fields: "id")
    @issue(scope: "sakshendelser", ttl: "PT5M", vouchesFor: "id") {
  id: ID! @nodeId
  tittel: String!
  # minted: internalGraphitronVoucher: String! @inaccessible
}

# Subgraph B: the vouched-for field is B's key, and every guarded field requires the voucher.
type Sak @table(name: "sak") @key(fields: "id")
    @accept(scope: "sakshendelser", vouchesFor: "id") {
  id: ID!
  hendelser: [Sakshendelse!]! @reference(path: [{key: "sakshendelse_sak_fkey"}])
  # minted: internalGraphitronVoucher: String @external @inaccessible,
  #         and @requires(fields: "internalGraphitronVoucher") on hendelser
}
```

**Why it is correct.** Verified in Apollo Router's source (`apollo-federation`, branch `dev`, read
2026-11-23):

* For a field carrying `@requires`, the planner builds the `_entities` input from the required fields plus
  a key of the type that selects no `@external` field (`inputs_for_require` and `locally_satisfiable_key`,
  the first such key in declaration order, `resolvable:` not consulted). So B's representation for
  `hendelser` is `{__typename, id, internalGraphitronVoucher}`, whichever key the router jumped by.
* Graphitron's `_entities` dispatch already tries the voucher alternative before any authored key
  (`HandleMethodBody.emitAlternativeCascade`, matching on `containsKey`), so that representation is
  verified and read on the vouched connection with its subtree.
* A jump by another key reaches only fields without `@requires`, under the caller's own row-level
  security: a quiet `null` at worst, never more access. So `@accept` mints the `@requires` rather than
  leaving it to the author.
* A key field that is `null` in A's response is copied into the representation as `null`
  (`execute_selection_set` in `selection.rs`); a non-null field that fails propagates in A and the row is
  never sent. A's voucher field is `String!`, so B never sees a `null` voucher from it.

**What changes.**

* *A.* `@issue` may sit on any table-bound type, a node, a keyed type and a root-returned type included.
  The voucher carries the `vouchesFor` fields; the type's other fields publish as usual.
* *B.* `@accept` mints its voucher field `@external` and mints `@requires(fields: "internalGraphitronVoucher")`
  on every field below the accepting type that it reads on the vouched connection. Both directives are
  published in the subgraph SDL and imported through the federation `@link`.
* *The key part.* `VOUCHER_VOUCHED_FOR_IN_KEY` is narrowed as in *Narrowing `VOUCHER_VOUCHED_FOR_IN_KEY`*
  below, so the vouched-for fields may be B's authored key with an empty lookup section; if the lookup
  part goes altogether (see *Open*), the refusal goes with it.
* *The set key* (R1199) is what makes the list resolve: N saker present N vouched-for values in one
  `_entities` call.
* *A `null` voucher* in a representation fails that representation alone, not the request
  (`MALFORMED` rejects the whole request today). It cannot come from a Graphitron issuer, but a
  hand-written stub may publish the field nullable.

**Principles architect's read (2026-11-23), for Alf and Filip to weigh.**

* *One form, not two.* `@requires` works only where A and B share a key the router can jump by, which the
  first iteration's dedicated issuing type withholds. Two forms would need two shapes in B, and B is built
  without A's schema, so every reader would fork on a form it cannot see. Recommended: retire absorbing,
  withholding, A's minted `resolvable: false` key and the three `VOUCHER_ISSUE_ON_*` refusals; the old
  usage becomes an ordinary type carrying `@issue` that publishes B's key. Deletes
  `graphitron_voucher_member` and the anti-join on it, and the ISSUE role of `graphitron_minted_key`. The
  issuing relations that reference members (`graphitron_voucher_vouched_for`,
  `graphitron_voucher_component`) point at `graphitron_field` instead, as `graphitron_accept_vouched_for`
  does, reading the column from the field's existing binding.
* *Drop B's resolvable voucher key.* Under `@requires` the voucher travels with whichever key the router
  used, so it no longer selects one; keeping it in the key cascade mixes two axes. `graphitron_minted_key`
  goes back to the node key only, `@inaccessible` is minted off `graphitron_voucher_field`, and the
  dispatch checks for a voucher before choosing a key alternative. It also removes the composition
  question of `@external` on a key field.
* *Store the guarded fields.* `@requires` and `@external` are minted off a stored relation at
  field-declaration grain (`graphitron_voucher_guard`, say), not worked out inside the minting view.
* *The lookup part probably dissolves.* The representation now carries B's key, so the voucher's lookup
  section repeats navigation. Dropping it deletes `graphitron_accept_key`, `@accept(key:)`, its three
  key refusals, `VOUCHER_VOUCHED_FOR_IN_KEY` and the codec's lookup section. The cost: a voucher for a value
  is no longer bound to one row, and unlocks any row B's policies admit for that value under whatever key
  is presented.
* No reason found that the old form must stay, short of a consumer whose A can publish no key it shares
  with B.

**Open, for Alf and Filip.**

* *The voucher is no longer a key* (Alf's direction, 2026-11-23, to be confirmed against a running router
  before it is ruled). Neither side keys its type by `internalGraphitronVoucher`: A's minted
  `resolvable: false` key and B's resolvable one both go, and the voucher travels only as a field
  `@requires` names, beside a key A and B share. It settles the two questions the architect raised: B
  keeps no voucher key, and one form remains, since a dedicated issuing type that withholds every key is
  unreachable without one. It also removes the composition question of `@external` on a key field. The
  2026-11-18 ruling in `fact-model.adoc` that the voucher field is a minted key field changes with it:
  the nullability stands (`String!` on A, `String` on B), B's field becomes `@external`, and it keys
  nothing. B's dispatch looks for a voucher in the representation before choosing a key alternative,
  rather than treating it as one.
* *Prove it first.* Before implementation, two small subgraphs composed and served by a real router: for
  `sak { hendelser }`, one sak and a list, B receives `{__typename, id, internalGraphitronVoucher}`; a field
  without `@requires`, reached by `id`, receives no voucher and reads under the caller's own row-level
  security. Rover and the router cannot be downloaded in the agent sandbox, so this runs in CI or on a
  developer machine.
* *A field directive in B instead of `@accept` on the type.* Since what is guarded is a field, and
  `@requires` and the router's planning are per field, B could mark each guarded field:

  ```graphql
  type Sak @table(name: "sak") @key(fields: "id") {
    id: ID!
    status: String!                     # public, read under the caller's own row-level security
    hendelser: [Sakshendelse!]!
      @reference(path: [{key: "sakshendelse_sak_fkey"}])
      @requiresVoucher(scope: "sakshendelser", vouchesFor: "id")
    # minted: internalGraphitronVoucher: String @external @inaccessible,
    #         and @requires(fields: "internalGraphitronVoucher") on hendelser
  }
  ```

  It answers which fields are guarded (the author says, in the SDL), lets one type mix public and vouched
  fields, reads as the specialised `@requires` it is, and turns the guarded-field relation into a capture
  of an authored directive rather than a derivation. With the voucher no longer a key, type-level
  `@accept`, `@accept(key:)` and its refusals, and `graphitron_accept_key` dissolve. Open within it:
  * Where `scope` and `vouchesFor` live: on the field directive, self-contained like `@requires`, with
    two guarded fields of one type that disagree refused (recommended); or once on the type, with an
    argument-less field directive, which brings a smaller `@accept` back. One scope per type to start;
    several would need a voucher field per scope.
  * What reads on the vouched connection: the guarded field and its subtree, and the entity's own row in
    the fetch that carries the voucher, since B's lookup of `Sak` may itself sit behind row-level
    security. Proposed: a representation carrying a voucher reads its entity and every field of that
    fetch on the vouched connection; the router already puts the guarded fields in a fetch of their own.
  * A's side: `@issue` stays on the type, without absorbing or withholding and without a key; whether it
    is renamed to pair with `@requiresVoucher`.
  * A field that should carry the directive and does not answers a quiet `null`, the same risk
    federation's own `@requires` carries, visible in the SDL.
  * The principles architect is consulted on this shape before it is ruled.
* Which fields are guarded, if type-level `@accept` stays: every non-key field of the accepting type, or
  only those that read on the vouched connection. Key fields cannot carry `@requires`.
* Whether the lookup part goes, giving up binding a voucher to one row. The 2026-11-16 ruling that the
  lookup part is navigation only is in R1119's *Rulings* but in no `docs/architecture` page; it is
  recorded there with whatever is decided now.
* Scope: retiring the first form is larger than adding the guard. Whether both stay in this item is
  settled when it is specced.
* Composition: if B keeps its voucher key, that Federation 2 composition accepts `@external` on a field B
  also keys by, checked with a real composition before implementation.
* Whether Graphitron's classification passes an authored `@requires` or `@external` through untouched
  today; it names neither.

## Narrowing `VOUCHER_VOUCHED_FOR_IN_KEY`

Moved here from R1199. It is a question of the first form, so it is decided together with "whether the
lookup part goes" above: if the representation carries B's key and the lookup section dissolves, the
refusal goes with it and the narrowing is never built; if the first form stays, the narrowing removes a
duplicate field.

The per-entity voucher works today without it. The rule in `graphitron-model.sql` matches vouched-for
fields to key fields by field name, so a second field reading the key's column passes it: kreg vouches for
`nasjonalIdForVoucher` beside its key `nasjonalId`, both reading `NASJONAL_ID`, and opptak absorbs both.
That form defeats the refusal's stated reason, "the two parts never share a value", since the two fields
share a column. Either the reason does not hold, and the refusal narrows; or the form is a loophole, and
the rule closes it by comparing columns, which needs the narrowing first or it breaks that consumer. The
ruling decides which.

*The refusal it narrows.* `VOUCHER_VOUCHED_FOR_IN_KEY` refuses a vouched-for field that is also a field
of the key the voucher decodes to, "so the two parts never share a value". That was R1119's slice
decision, not one of its rulings. The proposed framing, consistent with the 2026-11-16 ruling (the
vouched-for values are the access decision, the lookup part navigation only): when the vouched-for fields
are exactly B's authored key, the lookup section is empty and the voucher decodes onto that key from the
vouched-for values. A changes nothing: an `@issue` absorbing only its vouched-for fields is not refused
today, and it signs an empty lookup section. B's check "the lookup section names exactly B's key fields"
becomes "the lookup section names exactly the key fields the vouched-for section does not supply", which
is empty here. Refused still:

* *A partial overlap*, some key fields vouched for and some looked up: it would split one key between an
  access decision and navigation.
* *A superset*, the vouched-for fields being the whole key and more: the proposal is to accept it, since
  the key is still supplied whole by one section and the extra vouched-for fields only narrow the grant.
  The ruling should say which.

**Needs a ruling from Alf or Filip before Ready.**

*Where the split lives.* Narrowed, the defect is a set comparison, and the voucher arm needs the same
split in Java (which key fields the codec expects in the lookup section, which columns are filled from
vouched-for values). Derived once in the store instead (`fact-model.adoc`, "a derivation gets a relation
as soon as a second reader asks it"): a relation whose grain is *one field of the key an `@accept`
decodes to, with the voucher section that supplies it*, written by the stage that writes
`graphitron_accept_key` in its anchoring step, hanging off `graphitron_accept_key` by a cascading
foreign key so its sweep follows. The defect rule reads it ("the key is supplied from both sections"),
and `EntityKeyQuery` reads the section per field for the dispatch.
