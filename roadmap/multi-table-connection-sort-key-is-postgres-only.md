---
id: R1062
title: "A multi-table interface or union connection pages and orders correctly only on PostgreSQL"
status: Spec
bucket: bug
priority: 2
theme: codegen-correctness
depends-on: []
created: 2026-10-19
last-updated: 2026-10-19
---

# A multi-table interface or union connection pages and orders correctly only on PostgreSQL

## Goal

A root `Query` field that returns a multi-table interface or union (one whose implementing types
live in different tables) pages and orders the same way on every database: a page fetched with
`after` starts after the last row of the previous page, `hasNextPage` turns false at the end, and
rows come out in primary-key order rather than in the text order of a JSON rendering of the key.
Today that holds only on PostgreSQL, and only by accident, for participants whose primary key has
more than one column.

The shape, from the sakila example schema, where both participants have a two-column key
`(k1, k2)`:

```graphql
interface PagedItem { name: String! }
type PagedA implements PagedItem @table(name: "paged_a") { k1: Int! k2: Int! name: String! }
type PagedB implements PagedItem @table(name: "paged_b") { k1: Int! k2: Int! name: String! }

type Query {
    pagedItems: [PagedItem!]! @asConnection(defaultFirstValue: 3)
}
```

```graphql
{ pagedItems(first: 2, after: "<endCursor of page 1>") { nodes { name } pageInfo { hasNextPage } } }
```

After this item, page 2 returns the third and fourth rows in `(k1, k2)` order on Oracle exactly as
on PostgreSQL, and a key `(1, 10)` sorts after `(1, 2)`, not before it. A cursor issued before the
change for a composite-key field is rejected as invalid (the client error every malformed cursor
gets), so a client holding one restarts from page 1; single-column keys keep their cursors.

## Evidence

Surfaced by a v9 against v10 differential test of the sis subgraph on Oracle (2026-10-19).
`Query.personroller` (interface `Personrolle`, nine participant tables with a three-column key) and
`Query.studentvurderinger` both return page 1 for every `after` cursor, and `hasNextPage` stays
true, so a client that pages loops forever. Repro:
`personroller(filter: {eierOrganisasjonskode: "1234"}, first: 2, after: <endCursor of page 1>) { nodes { id } }`
returns the same two ids as page 1. The cursor decodes to `[1234,1000,11]` NUL `Undervisningsaktivitetsrolle`.
The same field orders `...,10` before `...,1`, which is text order.

## Cause


`MultiTablePolymorphicEmitter` reads a multi-table field in two stages. Stage 1 is one `UNION ALL`
over the participants that projects, per row, the participant's type name (`__typename`), its key
columns (`__pk0__..__pkN__`), and a synthetic sort key (`__sort__`); the outer select orders, seeks
and limits on `__sort__` then `__typename`. Stage 2 fetches the full rows per type. At key arity 1
`__sort__` is the key column itself. At arity 2 and above it is `DSL.jsonbArray(pk0..pkN)`, typed
`JSONB`, and the emitter's own comments name the assumption: "PostgreSQL's JSONB lexicographic
ordering reproduces the multi-column ordering". That holds on PostgreSQL, which orders `jsonb`
arrays element by element with numeric comparison.

The rendered Oracle SQL of the page-2 request (captured by the sis session, 2026-10-19) shows two
separate failures:

- `__sort__` renders as `json_array("EMNEROLLE"."INSTITUSJONSNR_EIER", "EMNEROLLE"."PLNR", "EMNEROLLE"."ROLLENR")`,
  which is `VARCHAR2` JSON text. `ORDER BY "__sort__"` therefore sorts text, giving `...,10` before `...,1`.
- The seek predicate is emitted, but the cursor's `JSONB` value binds as `RAW`:
  `where ("__sort__" >= hextoraw('5B313233342C313030302C31315D') and ("__sort__" > hextoraw(...) or ("__sort__" = hextoraw(...) and "__typename" > 'Undervisningsaktivitetsrolle')))`.
  Oracle compares `VARCHAR2` with `RAW` by converting the `RAW` to its hex string, so the comparison
  is `'[1234,1000,10]' > '5B3132...'`; `[` (0x5B) sorts above `5` (0x35), every row passes, and page 2
  returns page 1's rows.

Fixing the bind alone would not be enough: a correct text bind still pages wrongly on numeric keys,
because the comparison is on text. The sort key has to stop being a JSON document.

`__pk0__..__pkN__` are already projected beside `__sort__` in every branch and aligned
positionally (the validator enforces uniform key arity), so ordering and seeking on them asks
nothing of the participants' columns that stage 1 does not already project. What it does ask is
that each key position binds one way for every participant, which nothing checks today; see the key
slots below.

## Implementation

The sort key becomes the participants' key columns themselves, at every arity: stage 1 orders and
seeks on `__pk0__ .. __pkN__`, then `__typename`. `__sort__` is retired, including at arity 1, where
it was a second copy of `__pk0__`; one shape for every arity replaces a branch whose composite arm
is the defect. A multi-column seek over plain columns is what every single-table connection with a
composite `@defaultOrder` already sends, and jOOQ renders it on each dialect (a row-value comparison
where the dialect has one, the expanded `OR` chain where it does not), so the order is
dialect-independent by construction: no computed value is left whose comparison a dialect could
get wrong.

### Model: the key is a slot list

`PolymorphicOrdering` (the field-level ordering a root multi-table list or connection carries)
already has *slots*: an order column projected as one alias in every `UNION ALL` branch, typed once,
with one column per participant, minted by `FieldBuilder.foldParticipantOrderings` through an
agreement check that rejects a position whose (`sqlType`, `bindingType`) differs across
participants. The key positions have exactly that shape, so they become slots too, rather than a
second, unchecked copy of it in the emitter:

- `PolymorphicOrdering` gains `key`, a `List<Slot>` with one slot per primary-key position, each
  mapping every participant to its key column at that position. The emitter aliases key slots
  `__pkI__` (the alias stage 1 already projects and stage 2 dispatches on), not by
  `slotAlias`, so they are never projected twice.
- `foldParticipantOrderings` mints `key` from each participant's primary key through the same
  per-position (`sqlType`, `bindingType`) agreement check `foldOrder` applies to declared slots,
  factored out of `foldOrder` into one helper both callers use. A disagreement rejects into
  `participantFailures` with the position and both columns named, as a declared order does today.
  This is a new rejection: such a schema builds today and binds every participant's cursor value
  through the first participant's key type, which is the latent version of the defect this item
  fixes.
- `SlotOrder.OnSyntheticKey` is renamed `SlotOrder.OnPrimaryKey`, with javadoc saying it orders by
  the key slots. `syntheticKeyDirection` is renamed to match. `foldOrder`'s detection of a
  primary-key-shaped order is otherwise unchanged.

### Emitter

All in `MultiTablePolymorphicEmitter`:

- `branchProjection`: drop the `__sort__` projection (both arms); the `__pkI__` projections stay.
- `sortKeyClass` goes. One helper declares the key fields from `ordering.key()`:
  `Field<?> keyI = DSL.field(DSL.name("__pkI__"), <typing column>.getDataType())`, the same
  `DataType` typing `buildOrderingHelper` uses for declared slots, so a converter on a key column
  rides the cursor decode and the seek binds the database type. `buildStage1ConnectionBlock`'s
  outer select reads these fields instead of redeclaring `__pkI__` by Java class.
- `buildOrderingHelper` / `orderedParts`: the `OnPrimaryKey` arm emits one term per key field under
  the order's direction (today one `sortField` term), and the tiebreaker appended after declared
  slots becomes every key field then `tieField`, each flipping with a runtime `direction:` exactly
  where `sortField` flips today. Every primary-key-shaped order lowers to `OnPrimaryKey`
  (`@order(primaryKey: true)`, `@defaultOrder(primaryKey: true)` and the implicit fallback), so
  this arm is the path `pagedItems` and sis's `personroller` take.
- `buildRootConnectionFetcher`: its no-ordering arm (`ordering` empty) is unreachable for a valid
  root connection. `foldParticipantOrderings` returns empty only when every participant resolved
  no order, which a connection never does unless a participant has no primary key, and the
  validator rejects that. Delete the arm and take the ordering as present on that path (narrow
  the parameter, or unwrap once with an invariant message), so the ordering helper is the only
  producer of the seek and cursor columns.
- The root list fetcher's no-ordering arm stays, since a single-valued root lowers no ordering: it
  orders by `__pk0__ .. __pkN__` then `__typename` as untyped name references (an `ORDER BY` with
  no seek binds nothing, so it needs no `DataType`). The inline child fetcher's `buildStage1Block`
  call (the one with `parentSourceKey`, which today passes `__sort__` alone) orders by the same
  key references with no type-name tiebreaker, so
  `inlineSingleCardinalityChild_deliversTheRowItAlwaysHas` keeps its row. Both take the alias list
  from the helper `branchProjection` uses, so the projected aliases and the ordered ones cannot
  drift apart.
- `buildPerTypenameSelect` with `includeSortKey`: project each key column as `__pkI__` on the typed
  stage-2 record instead of `__sort__`, so `ConnectionHelper.encodeCursor` reads the cursor values
  by the names the seek uses. The `VALUES` input is aliased by the key columns' SQL names, not
  `__pkI__`, so nothing collides.
- The child connection arm (`buildBatchedConnectionRowsMethod`, `batchedBranchProjection`) is
  single-key and has no `PolymorphicOrdering`. It orders and seeks on `__pk0__` instead of
  `__sort__`, typed by `DataType` through the same field-declaring helper at arity 1 (fed the first
  participant's key column) rather than by Java class, so one emitter does not keep two typings for
  the same field. Behaviour is otherwise unchanged; see Out of scope.
- Delete `SORT_COLUMN` and the `JSONB` reference, and rewrite the javadoc and comments that state
  the JSONB assumption or the `(typename, pk, sort)` shape: the class javadoc, the alias comment
  above the constants, the stale "validator rejects composite-PK participants" scope note on
  `emitRootConnectionMethods`, and `GraphitronSchemaValidator.validateMultiTableParticipants`'s
  javadoc.

Fixture prose in `graphitron-sakila-example/src/main/resources/graphql/schema.graphqls` (the
`pagedItems`, `pagedItemsByName` and `PagedItem` comments, and the `search` / `searchConnection`
comments naming `__sort__`) is rewritten to the new shape.

## User documentation

The how-to pages describe the shape and change in the same commit:

- `docs/manual/how-to/sort-results.adoc`, the multi-table paragraph: the combined result is
  ordered on the participants' primary-key columns, then the type name, on every database; an
  order that names the primary key sorts by those columns directly. The participants' key columns
  must agree on SQL type and Java type at each position, as declared order columns already must.
- `docs/manual/how-to/polymorphic-types.adoc`: the stage-1 projection is
  `(__typename, __pk0__ .. __pkN__)`; the composite-key `jsonbArray` sentence goes.
- `docs/manual/how-to/connections.adoc`: the cross-reference stops naming `__sort__`.
- `docs/architecture/principles/development-principles.adoc` cites `__sort__` as its example of a
  synthetic alias; the example becomes `__typename`.

## Tests

The defect does not reproduce in the reactor. Its only execution database is PostgreSQL, where the
JSON key orders correctly, and the open-source jOOQ edition it builds against has no Oracle dialect
to render. Asserting on the SQL text instead is banned (`docs/architecture/how-to/testing.adoc`,
execution tier). So the tests pin the property that makes the order dialect-independent, that
the key is a list of columns in the model with no computed value anywhere, and cover the new shape
end to end on PostgreSQL. Oracle behaviour itself is confirmed by the consumer, below.

- **Pipeline, model** (`MultiTableOrderingLoweringTest`): rename
  `everyPrimaryKeyShapedOrder_isOnSyntheticKeyAndMintsNoSlot` to the `OnPrimaryKey` name and assert
  each case's `key()` as well: for a composite-key union (`PagedItem`), two key slots mapping
  `PagedA` and `PagedB` to their `k1` and `k2` columns, and no declared slot minted. This is the
  structural pin: an ordering whose key is a column list has no place to carry a computed sort
  value.
- **Pipeline, rejection**: a union whose participants' key columns disagree on SQL type at one
  position is rejected with the position and both columns named, mirroring
  `MultiTableOrderingLoweringTest.intAgainstBigint_rejectsOnTheTypePair` for declared order
  columns. Use a pair of tables that exists in the sakila catalog
  if one fits; otherwise add two small tables to the init SQL.
- **Retired vocabulary guard** (`RetiredVocabularyGuardTest`): register `SORT_COLUMN`,
  `OnSyntheticKey` and `sortKeyClass` as retired tokens, so the reverse-enforcer fails the build
  if the synthetic key comes back under its old names.
- **Seed** (`graphitron-sakila-db` init SQL): add `paged_b (1, 10, 'B-1-10')`, and update the
  assertions that count or list `paged_a` / `paged_b` rows (`GraphQLQueryTest`'s
  `pagedItemsConnection_*`, `MultiTableOrderingExecutionTest`'s composite-key case).
- **Execution** (`MultiTableOrderingExecutionTest`): `pagedItems` paged forward and backward with
  `first` / `last: 2` visits every row exactly once in `(k1, k2, __typename)` order with `B-1-10`
  after `B-1-3`, and the last page reports `hasNextPage: false`. This passes before the change too,
  because PostgreSQL's `jsonb` comparison is numeric: it is coverage of the new shape, not a
  reproduction of the defect. Rename
  `connection_overCompositeKeyParticipants_composesWithTheJsonbSyntheticKey` to drop the retired
  term; it keeps passing with the key fields as its tiebreaker.
- **Unit** (`TypeFetcherGeneratorTest`): delete the assertions on emitted method-body text that pin
  `__sort__`, `jsonbArray` and the `Field<JSONB>` sort key (the composite-key cases and the
  single-key `FILM_ID.as("__sort__")` cases). They are code-string assertions on generated bodies,
  banned at every tier; the execution paging tests carry the behaviour they stood in for.

Consumer confirmation: rerun the sis differential `personroller` page-2 request on Oracle after
the change lands. That is acceptance evidence the reactor cannot produce, so it is named here
rather than left implicit; the Done gate rests on the tests above.

## Out of scope

- **Composite participant keys on a child connection.** `buildBatchedConnectionRowsMethod` reads
  only the first key column throughout (projection, binding, sort), so a composite-key participant
  under a child connection pages and dispatches on a truncated key. The comment closing
  `GraphitronSchemaValidator.validateMultiTableParticipants` declines to reject that because a
  rejection "would break `Query.pagedItems`", which is the root path this item fixes, so the
  reason stops holding when this lands. The fix (full-key dispatch on the batched arm), or a
  validate-time rejection of composite keys on child connections in the meantime, belongs to its
  own Backlog item. This item rewrites that comment to name the child-only constraint and the
  follow-up as the owner, so the stale rationale does not survive.
- **Grouping by type.** v9 returned all rows of one participant type before the next; v10 orders by
  key across types with the type name as tiebreaker, which `MultiTableOrderingExecutionTest`'s
  `BY_KEY` and the sort-results how-to document. This item keeps that contract and only makes the
  key comparison right.
- The `__`-alias naming question in `generated-output-hygiene-sweep.md`, whose reserved-alias list
  loses `__sort__` when this lands.

## Retired vocabulary

`SORT_COLUMN`, the `__sort__` alias, `sortKeyClass`, `SlotOrder.OnSyntheticKey`,
`syntheticKeyDirection`, "synthetic key", "JSONB synthetic key", `DSL.jsonbArray` in
`MultiTablePolymorphicEmitter`, "PostgreSQL's (lexicographic) JSONB ordering",
"(typename, pk0..pkN, sort)". Prose habitats to sweep beyond the emitter: `PolymorphicOrdering`
and `FieldBuilder` javadoc, `DunderFreeEmissionPipelineTest`'s javadoc alias list,
`GraphQLQueryTest` comments on `search` / `searchConnection` / `pagedItems`, and
`MultiTableOrderingExecutionTest`'s class javadoc.

## Other solutions we've considered

- **Keep `__sort__` and render it per dialect** (a JSON array on PostgreSQL, a zero-padded string
  concatenation elsewhere). Padding needs a width per column type, breaks on negative numbers,
  strings and dates, and leaves two shapes where the plain columns give one that every dialect
  already compares correctly.
- **Fix the cursor bind only** (bind the decoded value as text on Oracle). Ordering and seeking
  would still compare JSON text, so `...,10` keeps sorting before `...,2`.
- **Keep `__sort__` at arity 1 and use the key columns only for composite keys.** Smaller diff, but
  it keeps the arity branch whose composite arm is the bug and a second alias carrying the same
  value.
- **Type the key fields in the emitter from the first participant, without key slots.** No new
  rejection, but it repeats the slot shape without the agreement check that makes one typing right
  for every branch, which is exactly the check declared order columns already get.
- **An SQL-capture test asserting the stage-1 statement orders by plain columns.** It would pin
  the dialect-relevant property directly, but it asserts on SQL text, which the testing guide bans.
  The model-level pin covers the same property one layer up.

## Roadmap entries

File a Backlog item for the child-connection composite-key truncation (Out of scope, first
bullet), alongside the move to Ready.

## Reviewer findings

### Round 1 (2026-10-19, Spec -> Ready, reviewer session 017intVzVV9E1eMdBXjS2A4g)

Verdict: request revisions. One blocking finding on question one, one on question two; both are
small. The goal reads cleanly on its own: a root multi-table connection over composite-key
participants pages and orders by key on every dialect, and old composite cursors are refused. The
diagnosis holds against the tree, every symbol, test and doc path the plan names exists as named,
and the shape (key positions as `PolymorphicOrdering` slots through the existing agreement check,
plain-column seek) extends what is there rather than standing beside it. I would hand the emitter
and test plan to an implementer as-is.

**Finding 1 (question one: viability for the consumer the goal names). The new key-type rejection
is not checked against `personroller` / `studentvurderinger`.**

The Goal's acceptance is sis's `personroller` page 2 on Oracle. The plan adds a rejection when
participants' key columns disagree on (`sqlType`, `bindingType`) at a position, and `sqlType` is
`DataType.getTypeName()`, so on Oracle every `NUMBER` column agrees on it and the check turns on
`bindingType` alone. jOOQ binds an Oracle `NUMBER(p, 0)` by its precision (`Short`, `Integer`,
`Long`, `BigInteger`), so nine legacy tables that declare `INSTITUSJONSNR_EIER`, `PLNR` or `ROLLENR`
with different precisions build today and would be rejected after this lands: the item would stop
the consumer that motivates it from building, rather than fix its paging. The spec says "such a
schema builds today" without saying whether any known one does. What would satisfy it: state
whether the participants of both sis fields agree per key position (the generated jOOQ table
classes answer it), and if they do not, say what the plan does about it (for instance, whether a
width-only disagreement within one SQL type should widen rather than reject, which is a different
rule from the one declared slots get and would need saying as such).

**Finding 2 (question two: fit with an existing mechanism). Registering the retired names in
`RetiredVocabularyGuardTest` at landing contradicts the registry's admission rule.**

The registry's javadoc sets the entry bar as demonstrated recurrence ("a term enters the registry
when an audit finds it surviving a cleanup, not at every rename"), and `roadmap/workflow.adoc`'s
Retirement sweep says recurrently-surviving terms graduate into it. The Tests bullet registers
`SORT_COLUMN`, `OnSyntheticKey` and `sortKeyClass` before any survival is shown. What would
satisfy it: drop the bullet and let the `## Retired vocabulary` section and the Done-gate sweep do
the work, or give the recurrence evidence that admits them.

**Non-blocking.**

- Key minting in `foldParticipantOrderings` runs before the validator's primary-key and arity
  checks, so it will meet a participant with no key or a different arity. The plan names only the
  type rejection; following `foldOrder`'s existing precedent (absorb, the validator owns the
  message) keeps those diagnostics where they are, and `PolymorphicOrdering`'s totality check
  will need to tolerate the absorbed case.
- Sweep habitats the Retired vocabulary list does not name, which the Done-gate grep for
  `__sort__` will still find: the `paged_a` comment in `graphitron-sakila-db`'s `init.sql` (states
  the JSONB assumption), `GeneratedSourcesLintTest`'s javadoc alias list, and
  `docs/manual/how-to/polymorphic-types.adoc` line 164 ("a single typed `__sort__` column").
