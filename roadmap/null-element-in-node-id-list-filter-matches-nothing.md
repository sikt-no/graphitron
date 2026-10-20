---
id: R1073
title: "A null element in a node-id list filter matches nothing instead of failing the field"
status: In Progress
bucket: bug
priority: 5
theme: nodeid
depends-on: []
created: 2026-10-19
last-updated: 2026-10-20
---

# A null element in a node-id list filter matches nothing instead of failing the field

## Goal

A filter field typed as a list of node ids with nullable elements (`[ID]` with `@nodeId`) reads a
`null` element as a value that matches no row, the way a nullable-element scalar list filter and a
list-of-input-objects filter already do, instead of failing the whole field with
`Invalid node id "null"`. A list that holds a valid id and a `null` returns the valid id's rows; a
list holding only `null` returns no rows; an id that is present but does not decode still fails the
field with its client error.

The minimal pair, from the sis query that surfaced it (field names as sis spells them; the input
type's name and the scalar list's element type are illustrative):

```graphql
input StudieoppbygningsdelFilter {
    eqfniva: [Int]                                                 # [null] matches nothing today
    studieretninger: [ID] @nodeId(typeName: "Studieretning")       # [null] fails the field today
}
```

After the item lands, `studieretninger: [null]` behaves like `eqfniva: [null]`: no rows, no error.
"Node id" here is graphitron's opaque base64 global id: `@nodeId` on an `ID` slot means the wire
value is decoded to the referenced row's key columns before it becomes a predicate.

## Evidence

Surfaced by the sis v9 against v10 differential test on Oracle (2026-10-19).
`studieoppbygningsdeler(filter: {eierOrganisasjonskode: "1234", studieretninger: [null]})` returns
0 rows with no error on v9; v10 nulls the field with
`Invalid node id "null" for this argument: not a valid Studieretning id`. `[<valid id>, null]` fails
the same way on v10, where v9 returned the valid id's 4 rows. Scalar lists in the same input
(`eqfniva: [null]`, `kullErAktiv: [null]`) already read a null element as matching nothing on v10,
which is the rule the list-of-input-objects filter work (R1057, R1059) also follows.

Nothing in the sakila fixtures caught it because every node-id list filter there declares non-null
elements (`[ID!]`), which graphql-java's own validation keeps `null` out of.

## Cause

Every generated node-id decode of a filter, an authored `@condition` parameter or a `@service`
parameter goes through a decode helper minted by `CompositeDecodeHelperRegistry`, a per-class
collector of small private static methods that turn the wire value into the decoded key. Its
`buildHelper` has two failure modes: `THROW`, the ordinary one, raises the client error on an id that
does not decode; `SKIP`, used only by a polymorphic `@nodeId` branch filter, drops it.

The `THROW` list arm decodes every element: `decode<Type>((String) nodeId)`, and a `null` return
throws through `NodeIdDecodeFailure.throwStatement`. A `null` element casts fine, decodes to `null`,
and so reads as a malformed id, with the message concatenating the literal `null`. The scalar arm of
the same helper does not have this problem: `if (!(wire instanceof String nodeId)) return null`
reads an absent or `null` id as no key.

Dropping the `null` element in the helper is not the fix. `ConditionGlueRenderer.appendGuardedAnd`
reads an empty decoded list as "no filter" (an empty `IN ()` would otherwise zero the query, and an
empty wire list is documented to filter nothing), so dropping would turn `[null]` into the
unfiltered table. `ElementRowsHelperRegistry`, the list-of-input-objects helper, met the same trap
and settled it: a `null` element is never dropped; it contributes a `null` cell at one column and a
row of typed nulls at several, which `IN` compares as SQL `NULL` and so matches no row.

## Implementation

**`CompositeDecodeHelperRegistry.buildHelper`, `THROW` list arm.** A `null` element contributes the
key of a blank record instead of being decoded. The blank record is built over the node table's key
fields exactly as the encoder's own `decode<Type>` builds a decoded one
(`DSL.using(SQLDialect.DEFAULT).newRecord(Tables.X.A, ...)`, the shape
`NodeIdEncoderClassGenerator.buildPerTypeDecode` emits, reached through `HelperRef.Decode.table()`
and `outputColumnShape()`), so the existing projection applies unchanged: `value1()` gives `null` at
arity 1 and `valuesRow()` gives a row whose cells are nulls typed at the key columns' own
`DataType`s at arity N. No arity fork, and the nulls take their types from the same place a decoded
key's values do. A non-null element goes through the decode and the throw exactly as today, so
`["garbage", null]` still fails naming `"garbage"`.

Emit the arm as a `for` loop over the wire list into an `ArrayList`, the body shape
`ElementRowsHelperRegistry.buildHelper` uses, rather than adding a second branch inside the current
statement lambda: the two helpers now carry one rule and read the same way, and each branch is a
statement a developer can breakpoint. The class javadoc gains the rule beside its existing paragraph
on the skip arm's empty-list contract: in throw mode a `null` element is a key of nulls, never
dropped, and why (the glue's empty-list guard).

Consequences for each consumer of the throw-mode list helper, all intended:

- *Generated filter* (`ConditionGlueRenderer`, `ColumnTerm` with `MatchKind` IN): `col.in(list)` /
  `DSL.row(...).in(list)` now carries a `NULL` member, which matches nothing; the presence guard is
  untouched, since the list is non-empty. This covers own-table, FK-target and `@reference`-reached
  node-id filters alike, since they share the helper.
- *Authored `@condition` parameter* bound to a list-shaped `@nodeId` slot: receives the list with a
  `null` (arity 1) or a row of typed nulls (arity N) in the element's position, which its own
  `.in(keys)` binds directly with the same meaning. Today it never runs for such input.
- *`@service` parameter* receiving decoded keys (`ServiceMethodCallEmitter`, `ArgCallEmitter`): the
  same list. This matches the record-decode list helpers (`InputBeanFragments.recordDecodeListHelper`,
  `RecordDecodeFragments.polymorphicDecodeHelperList`), which already hand a `null` element through
  as a `null` record.

**`MultiTablePolymorphicEmitter.nodeIdDispatchGuard`, list arm.** The guard that fails a bare
`@nodeId` id no participant can decode (on a field returning a multitable interface or union) walks
the list element by element and throws when no branch decodes the element, which today includes a
`null` element. Skip a `null` element, as the scalar arm's `wire != null` test already does. The
per-branch filter then needs no change: the `SKIP` list helper already drops a `null` element, so
`[null]` reaches `appendPruningAnd` as a non-null empty list and renders `DSL.falseCondition()`
(matches nothing), and `[<customer id>, null]` matches the customer only.

**The `SKIP` arm is unchanged.** It already gives the rule its observable meaning by its own
route (drop, then the pruning guard's all-mismatched-is-false), and its empty-return contract is
what that guard reads.

**Out of scope.** `@lookupKey` lists (`LookupRows`) are positional lookups, not filters; a `null`
key there keeps today's behaviour. Tenant fan-out narrowing (`registerTenantSlot`) already reads a
`null` element as narrowing nothing, which stays correct (it only queries more tenants than needed).
R1067 (key values that do not fit their column refused at decode) changes what the encoder's
`decode<Type>` accepts for a present id; the `null` branch here never calls the decode, so the two
compose without ordering.

## Manual

- `docs/manual/reference/directives/nodeId.adoc` § Constraints: the bullet listing admissible slot
  types names `ID!`, `ID`, `[ID!]`, `[ID!]!`, while `[ID]` and `[ID]!` build and run today (sis uses
  them). Add them, and state the null-element rule for filter, `@condition` and `@service` slots: a
  `null` element is a key of nulls, which as a filter matches nothing; an id that is present but
  malformed or of another type still fails the request. Do not extend the claim to `@lookupKey`.
- `docs/manual/how-to/batch-lookups.adoc`, the "Same-table `@nodeId` arg path, without
  `@lookupKey`" paragraph lists the filter's three consequences (order, empty list, bad id): add that
  a `null` element in an `[ID]` list matches nothing, mirroring the sentence `field.adoc`
  § list-of-input filter already carries for its shape.

## Tests

New sakila fixtures with nullable-element node-id list filters (siblings, so the `[ID!]` fixtures
keep pinning their own shape):

- arity 1, own table: a `Film` query filtered by `filmIds: [ID] @nodeId(typeName: "Film")`;
- arity 2, own table: a `FilmActor` query filtered by `ids: [ID] @nodeId(typeName: "FilmActor")`
  (composite key, so the typed-null row reaches `DSL.row(...).in(...)`);
- arity 1, FK target (the sis shape): a `Customer` query filtered by
  `addressIds: [ID] @nodeId(typeName: "Address")`, decoded keys compared against
  `customer.address_id`;
- polymorphic: an `[ID] @nodeId` (bare) twin of `occupantsByIds` on the `AddressOccupant` union.

Execution tier (`GraphQLQueryTest`, `MultiTableFilterExecutionTest`), per fixture: `[null]` returns no
rows and no error; `[<valid>, null]` returns exactly the valid id's rows; `["garbage", null]` nulls
the field with the client error naming `"garbage"`; `[]` returns the unfiltered baseline (unchanged,
pinned against the new helper body). On the FilmActor fixture, `[<valid composite id>, null]`
proves the arity-N row binds on PostgreSQL.

`CompositeDecodeHelperRegistryTest` (or the existing `ConditionGlueRendererTest` helper coverage)
stays structural: helper signature and return type, never the null branch's body text. The emitted
helper compiles at `<release>17</release>` through `graphitron-sakila-example` as usual.

## Other solutions we've considered

- *Drop the `null` element in the throw-mode helper* (the original sketch). Turns `[null]` into an
  empty list, which the glue's presence guard reads as no filter, so the field would return every
  row. Rescuing it needs the skip arm's sentinel contract (absent and empty both return `null`) plus
  a changed guard for every throw-mode list binding, and it would change what an authored
  `@condition` receives for an empty wire list. More change for a worse fit with `ElementRows`.
- *A plain `null` element at every arity, substituted at the generated row-IN site.* Keeps arity N
  uniform with arity 1 for authors, but leaves every authored `@condition` that binds a composite key
  list to handle the `null` itself (`row(a, b).in(keys)` with a `null` member is not a safe bind), and
  spreads the decision across the helper and the glue. Deciding it once in the helper, where every
  consumer reads it, wins.
- *Type the nulls with `DSL.val(null, Tables.X.COL.getDataType())`* as `ElementRowsHelperRegistry`
  does. Equally correct; the blank record removes the arity fork and reuses the encoder's own field
  list.
