---
id: R1106
title: "A single-table connection whose order is not unique gets the table's row key as tiebreaker, so keyset pages stop dropping rows"
status: In Progress
bucket: bug
priority: 2
theme: codegen-correctness
depends-on: []
created: 2026-10-27
last-updated: 2026-10-27
---

# A single-table connection whose order is not unique gets the table's row key as tiebreaker, so keyset pages stop dropping rows

## Goal

Paging through a single-table connection returns every row exactly once, whatever the connection is ordered by. Today graphitron orders, builds the cursor and seeks on exactly the columns the author lists in `@defaultOrder` or in an `@order` enum value. When rows tie on those columns across a page boundary, the next page starts strictly after the last row's values, so the remaining tied rows are never returned. `totalCount` stays right and `hasNextPage` turns false too early. After this item, when the listed columns do not already identify a row, graphitron appends the table's row key to the order, the cursor and the seek, in the direction of the last listed column. The row key is the primary key, or, if the table has none, the type's `@node` key or a non-null unique key. Reported against 10.0.0-RC41 as [GitHub issue #559](https://github.com/sikt-no/graphitron/issues/559): `totalCount` 52 against pages that summed to 49, and in production 83 of 107 roles shown with no way to load more.

A *connection* is a paginated list field (`@asConnection` on a list, or a hand-written `...Connection` type). Each page hands out a *cursor*, an opaque encoding of the sort-column values of the page's last row. The next page *seeks* from it: it keeps only the rows that sort strictly after those values. This is keyset pagination, and it is only correct when the sort columns identify a row. A *single-table* connection reads one `@table`, as opposed to a multitable interface or union, which already gets a tiebreaker (`docs/manual/how-to/sort-results.adoc`, section "Sort across polymorphism").

From the sakila example schema:

```graphql
type Query {
    filmsByRateDescTitleAsc(first: Int, last: Int, after: String, before: String): FilmsConnection!
        @defaultOrder(fields: [
            {name: "rental_rate", direction: DESC},
            {name: "title", direction: ASC}
        ])
}
```

Today this orders by `rental_rate DESC, title ASC` and its cursor carries two values, so two films with the same rate and title on either side of a page boundary lose one of them. After this item it orders by `rental_rate DESC, title ASC, film_id ASC` and the cursor carries three values. The contrast is the form the manual recommends today:

```graphql
customers: [Customer!]! @asConnection
    @defaultOrder(fields: [{name: "LAST_NAME"}, {name: "FIRST_NAME"}, {name: "CUSTOMER_ID"}])
```

It already lists the primary key, so it does not change: its SQL is the same and its outstanding cursors stay valid.

This reverses a documented position. `sort-results.adoc` says "The rewrite does *not* auto-append a PK tie-breaker; it is a schema-author responsibility". That stance has three costs. The failure is silent. The tiebreaker has to be repeated on every `@order` value. And the multitable path already does the opposite. The issue reporter and the triage comment both asked for the append.

## The rule

**Where it applies.** It applies to every field whose wrapper is `FieldWrapper.Connection` (as `BuildContext.buildWrapper` classifies it) and that resolves at a single-table coordinate. That covers root connections, inline child connections and `@splitQuery` child connections, which all get their ordering from the one call `FieldBuilder.projectForFilter` makes to `OrderByResolver.resolve`. Two shapes are left out:

- **Plain lists** (`[Film]` without `@asConnection`) have no cursor. A tie only reorders rows between runs and never drops one, so their SQL and query plans stay as they are.
- **Multitable participants.** These are coordinates where `FieldBuilder.NodeIdArgPlan.participant()` is non-null. `FieldBuilder.foldParticipantOrderings` already appends each participant's primary-key slots and `__typename`, and it folds the per-participant `OrderBySpec`s positionally. A per-participant append would add redundant key slots, and it would fail arity agreement wherever participants' keys differ in width.

**Keys.** The rule reads three kinds of key from the field's table and element type:

- The *row key* is the first of these that exists:
  1. the primary key, as `TableRef.primaryKeyColumns()` reports it, the same source the multitable fold's key reads (this includes a synthetic primary key configured in jOOQ codegen for a view);
  2. the element type's `GraphitronType.NodeType.nodeKeyColumns`, when non-empty;
  3. the first unique key in `MatchedKeys.candidates` order whose columns are all `NOT NULL`.
- The *identifying keys* are every one of those that exists: the primary key, the node key, and each all-`NOT NULL` unique key. A unique key with a nullable column does not count, because PostgreSQL lets several rows hold `NULL` in it. A node key is taken without that check. It is the identity the author declared for the type, and the type's node ids encode the same columns, so a `NULL` in it is already a broken id. It also has to be exempt to cover the reported case: jOOQ reports a view's columns as nullable whatever the data holds.
- An order *identifies a row* when its column set contains every column of some identifying key. Position, direction and collation do not matter.

**Completion.** If the order identifies a row, it is left unchanged. Otherwise the row-key columns the order does not already list are appended in row-key order, with no collation. Each one takes the direction of the order's last entry. An empty order is never completed. The existing "paginated fields must have ordering" rejection in `GraphitronSchemaValidator.validatePaginationRequiresOrdering` covers that case.

**Direction.** The appended key takes the last entry's direction for two reasons:

- It keeps a uniform-direction order uniform. jOOQ renders such a seek as one row-value comparison, and PostgreSQL can serve it from a composite index scanned in either direction. An always-ascending key would turn every `DESC` order into a mixed-direction one. jOOQ handles mixed directions (`filmsByRateDescTitleAsc` already is one), but a single-direction index cannot serve them.
- It leaves `OrderBySpec.Fixed.uniformAsc` as it was. So an `@order` value that the runtime `direction:` argument flips also flips the appended key. That is what the multitable path does: the manual says its tiebreakers "flip with a runtime `direction:` where the order itself does".

**The four ordering sources:**

1. **`@defaultOrder(fields:)` and `@defaultOrder(index:)`** are completed in the resolver. `primaryKey: true` and the implicit primary-key fallback already identify a row, so they are untouched.
2. **A single-valued `@orderBy` argument** (`order: FilmOrderBy`): each `@order` enum value is completed on its own, in the resolver, so every consumer of the model sees the completed order. The argument's `base`, which is the `@defaultOrder` fallback, is completed as in 1.
3. **A list-valued `@orderBy` argument** (`order: [FilmOrderBy]`): the client composes a sequence of values at request time. Completion therefore happens once, in the generated helper after its loop. If the accumulated columns contain an identifying key, nothing is appended. Otherwise the missing row-key columns are appended in the direction of the last accumulated sort part. Completing each value separately would be wrong: `[TITLE, RATE]` would order by `title, film_id, rental_rate, film_id`.
4. **No row key at all** covers a keyless view, or a table-valued function result such as the sakila `tilganger*` routine fields. Nothing can be appended, and the build cannot tell whether the listed columns are unique. The classifier emits a lint finding under a new rule, `connection-order-not-unique`, on each such connection whose order is non-empty. The message names the field and says that rows tying on the listed columns can be skipped between pages. It also gives the remedies: declare a primary key, or a synthetic one in jOOQ codegen for a view; add `@node(keyColumns:)` on the type; or, if the listed columns are unique in fact, disable the rule in the `<lint>` block. A warning rather than an error, because an error would break builds whose orders are unique in fact and that have no way to say so.

**Cursor compatibility.** On a completed field, the cursor grows by the appended columns. A cursor issued before the upgrade then decodes to the wrong number of values. `ConnectionHelper.decodeCursor` already turns that into the client error `cursor is not valid`. This is a one-time break, limited to fields that were not row-identifying and were therefore already dropping rows. The release note says so.

## Implementation

- **Row identity is decided once, in `OrderByResolver`,** as a resolver-internal sealed outcome: `RowIdentity { Keyed(rowKey, identifyingKeys); Keyless }`. It is derived only for a single-table connection.
  - Sources: `TableRef.primaryKeyColumns()`, `MatchedKeys.candidates` filtered on the NOT-NULL rule (nullability from the catalog's `ColumnEntry.nullable`), and the element type's node key via `ctx.nodes.forName(returnTypeName)`. `MatchedKeys` already answers "which catalog key does this column set cover" for the UPDATE and DELETE walkers, so this reuses it rather than writing a second key enumeration.
  - `Keyed` drives completion. `Keyless` drives the `connection-order-not-unique` finding. The resolver's `Resolved.Ok` reports a `Keyless` connection with a non-empty order, and `FieldBuilder` emits the finding from that report, so the lint and the completion come out of one decision.
  - This is a derived fact that would naturally live as a store view over `sql_constraint`, `sql_constraint_column`, `sql_column.nullable` and the node keys. The classifier does not read the store today, so the item computes it in the resolver and leaves that migration to the store work.
- **`resolve` takes the `TableRef` and the coordinate's single-table-ness.** It takes the `TableRef` that `projectForFilter` already holds, instead of only its SQL name. `resolveTableFieldComponents` already holds the `NodeIdArgPlan`, so it threads `plan.participant() == null` into `projectForFilter`. The `emitAsConnectionAdvisory` boolean on `resolveTableFieldComponents` is false exactly when a participant is set; the implementer may derive it from `plan.participant()` in the same change, so the axis has one slot. The connection test reads `ctx.buildWrapper(fieldDef) instanceof FieldWrapper.Connection` rather than a second copy of that predicate.
- **`OrderBySpec.Argument`'s `boolean list` becomes a sealed composition component.**
  - `Single(List<NamedOrder> namedOrders)` carries the named orders already completed by the resolver.
  - `Sequence(List<NamedOrder> namedOrders, SequenceCompletion completion)` carries them as declared, because completion has to apply to the composed sequence.
  - `SequenceCompletion` is `None` (a plain list, a participant, or a `Keyless` table) or `AppendRowKey(rowKey, identifyingKeys)`.
  - The resolver also completes `base` in place. Derived `namedOrders()` and `list()` accessors keep `FieldBuilder.foldParticipantOrderings` and the other readers compiling unchanged.
  - The javadoc on `Fixed` and `Argument` says that, for connections, the completed form is what consumers see. A pure `complete(Fixed, Keyed)` helper is shared by the static arms and the tests.
- **`TypeFetcherGenerator.buildOrderByHelperMethod`** switches exhaustively on the composition instead of on `arg.list()`. `buildSingleArgOrderByBody` is unchanged: it renders the already-completed named orders, and because `uniformAsc` is preserved, the existing flip-or-lock rendering covers the appended columns.
- **`TypeFetcherGenerator.buildListArgOrderByBody`** emits a completion block after the loop under `AppendRowKey`. The block collects the names of `colParts`. If they contain no identifying key's column names, it reads `$sortOrder()` from the last element of `sortParts` and appends each missing row-key column to both `sortParts` and `colParts`, using `sort(last)`. Names are compared by `Field.getName()`, the same way `ConnectionHelper.pageRequest` merges `extraFields`. If no value contributed a column, `sortParts` is empty; in that case the helper returns `base`, as it does for an empty argument.
- **`LintRule`** gains `CONNECTION_ORDER_NOT_UNIQUE("connection-order-not-unique", Source.CLASSIFIER)`, with its row in the `lint_rule` catalogue insert in `graphitron-model.sql` (`LintRuleCatalogueTest` gates the pair). The classifier emits it beside the other `Source.CLASSIFIER` advisories in `FieldBuilder`.
- **No emitter outside `TypeFetcherGenerator` changes.** `OrderByFragments.fixedSortParts` / `fixedColumnParts`, `LauncherCommands` (`Ordering.Columns`), `BatchedRowsFragments` (the `@splitQuery` `ROW_NUMBER()` and seek) and `ProjectionUnitRenderer` all read `Fixed.columns()`. They pick up the completed list as ordinary entries, for both the ORDER BY and the cursor columns.
- **Docs.** The user-doc draft below replaces these statements:
  - `docs/manual/how-to/sort-results.adoc`: the closing sentence of the tie-breaker paragraph under "Pick the column source", the second numbered consequence under `[#stable-cursors-and-tie-breakers]`, the "Unlike every other field" paragraph in `[#sort-across-polymorphism]`, and the tie-breaker bullet under "Constraints and pitfalls";
  - `reference/directives/defaultOrder.adoc` ("field-based orders need a tie-breaker");
  - `reference/directives/orderBy.adoc` ("`FILM_ID` after `TITLE` ties the keyset cursor");
  - `reference/directives/order.adoc`;
  - `how-to/connections.adoc` (the empty-cursor tiebreaker note);
  - `reference/directives/asConnection.adoc`.

  The new lint rule joins the `[#silencing-lint-warnings]` guidance in `reference/mojo-configuration.adoc` only if that page enumerates rules. Today it does not.

## User documentation (first-client check)

Replaces the second consequence in `sort-results.adoc` § Stable cursors and tie-breakers:

> *Determinism gates "the next page is always the next page".* If two rows compare equal on every order column, a seek from the first steps over the second. Graphitron therefore makes every connection's order identify a row. When the columns you list do not include the table's primary key (or another key that identifies a row: the type's `@node` key, or a unique key whose columns are all `NOT NULL`), graphitron appends the primary key after them, in the direction of your last column. The appended columns are part of the cursor. `@defaultOrder(fields: [{name: "rental_rate", direction: DESC}, {name: "title"}])` pages on `rental_rate DESC, title ASC, film_id ASC`. Listing the key yourself changes nothing, and is still the way to put it somewhere other than last.
>
> Plain lists are not completed: without a cursor, a tie only changes the order between runs and never drops a row. A table with no key at all (a view without a synthetic primary key, a table-valued function result) has nothing to append. The build warns under the lint rule `connection-order-not-unique`, and the listed columns have to be unique on their own.

The "Constraints and pitfalls" bullet becomes: "Connection orders that do not identify a row get the table's key appended, which is part of the cursor. Changing a table's primary key invalidates outstanding cursors on its connections." The polymorphism section loses its "Unlike every other field" framing. It keeps the part that is specific to multitable: the participant typename follows the key.

## Tests

- **Pipeline tier** (`GraphitronSchemaBuilderTest`):
  - Update the cases that pin today's column counts: `DEFAULT_ORDER_INDEX`, `DEFAULT_ORDER_INDEX_DESC`, `DEFAULT_ORDER_FIELDS`, `PER_FIELD_DIRECTION_DEFAULT_ORDER`, `DIRECTIVE_LEVEL_DIRECTION_PUSHES_DOWN`, `PER_FIELD_DIRECTION_ORDER_ENUM_VALUE`, `CONNECTION_WITH_DEFAULT_ORDER_INDEX_SPLIT_CLASSIFIED`. Each asserts the completed list where the field is a connection. `DEFAULT_ORDER_PRIMARY_KEY` and `NO_DEFAULT_ORDER_PK_FALLBACK` stay unchanged.
  - New cases, one per branch:
    - a non-identifying order gets the PK appended, ascending;
    - a `direction: DESC` order gets it descending;
    - a mixed order gets it in the last entry's direction;
    - a composite PK partly listed gets only the missing columns appended;
    - an order that lists the PK in a non-final position is unchanged;
    - an order covering an all-`NOT NULL` unique key on a keyless table (`stint`) is unchanged;
    - a keyless table with a non-identifying order gets its unique key appended (`stint_single`);
    - a keyless table under a type declaring `@node(keyColumns:)` gets the node key appended. The test SDL declares such a type over `role_assignment`, the keyless stand-in for the reported view;
    - a keyless table with no unique key and no node key (`role_assignment` under a plain `@table` type) keeps its order and carries the `connection-order-not-unique` finding;
    - a plain list with the same `@defaultOrder` is not completed;
    - a single-valued `@orderBy` completes each named order and the base;
    - a list-valued `@orderBy` is a `Sequence` with its named orders as declared and an `AppendRowKey` completion;
    - the same list-valued argument on a plain list is a `Sequence` with `None`.
  - Multitable: `MultiTableOrderingLoweringTest`, `UnlowerableOrderingRejectionPipelineTest` and `LauncherOrderingClosureTest` stay green unchanged. Their passing is the evidence that participants are not completed.
- **Unit tier** (`TypeFetcherGeneratorTest`, beside `orderByArg_*` and `connectionField_withOrderByArg_extraFieldsComeFromOrderingResult`): the single-valued helper renders the appended column inside the flip expression of a uniform-ASC value, and with a fixed direction on a locked value. The list-valued helper renders the post-loop completion block under `AppendRowKey`, and omits it under `SequenceCompletion.None`.
- **Execution tier** (`graphitron-sakila-example`, new `ConnectionTiebreakerExecutionTest` beside `GraphQLQueryTest`; this is the acceptance evidence for the goal). The seed already has the tie this needs: three of the five films share `rental_rate` 2.99, all in language 1. So the fixtures are new schema fields, not new seed rows. Every execution test reads the seed, and its counts are pinned all over `GraphQLQueryTest`. Add three fixture fields:
  - a root connection `@defaultOrder(fields: [{name: "rental_rate"}])`;
  - a `RENTAL_RATE @order(fields: [{name: "rental_rate"}])` value on `FilmSort`, or a sibling enum if that value disturbs existing `filmsOrderedConnection` assertions;
  - a `@splitQuery` connection from `Language` to its films with the same `@defaultOrder`. This is the reporter's shape, and the language-1 partition holds the tie.

  Walk every page with `first: 2` and assert that the concatenated nodes equal `totalCount` with no duplicates, forward and backward (`last:`/`before:`). Do this for the root `@defaultOrder` path, for the list-valued `@orderBy` path in both `direction: ASC` and `DESC` (the appended key flips), and for the split path. Each walk must fail on today's trunk: page one ends inside the 2.99 tie and page two seeks past the rest. Run each one before the fix to confirm it reproduces the issue. Also refresh the "orders on two columns" comment on `filmsByRateDescTitleAsc_underSplitAfterCursor_surfacesClientError`, whose cursor gains `film_id`.
- **Fixture warnings.** The sakila `tilganger*` connections over a routine terminus will carry the new finding. Accept it in whatever baseline the build's warning assertions keep, or give the fixture a unique order. Either way, the finding's appearance there is itself evidence for branch 4.

## Implementation notes

Shipped in one commit, `R1106: a single-table connection's order gets the table's row key as tiebreaker`. Where it departs from the plan above, or settles something the plan left open:

- **Unique keys come from `JooqCatalog.candidateKeys`, the enumeration `MatchedKeys.candidates` wraps,** not from `MatchedKeys` itself. `MatchedKeys` lifts each key into `ColumnRef`s, which carry no nullability, so filtering on the NOT-NULL rule needs the catalog's `ColumnEntry.nullable` one level down. It is still the one key enumeration, not a second.
- **`resolve` takes the return type name** beside the `TableRef` and the single-table flag, as the Spec -> Ready review noted, for the node-key lookup. The node key counts only when the node type's table `denotesSameTableAs` the field's.
- **`Resolved.Ok` carries a `keylessConnection` flag** rather than the `RowIdentity` itself: the caller needs only "warn or not", and the flag is computed where the order's emptiness is known.
- **The generated list-valued completion reads `SortField.getOrder()`,** the stable accessor, rather than the model API's `$sortOrder()`. Same value.
- **The `emitAsConnectionAdvisory` boolean is gone,** derived from `plan.participant()` as the plan allowed; the three-overload chain on `resolveTableFieldComponents` is down to two.
- **The `@splitQuery` execution fixture is a structural `FilmsConnection` with all four pagination arguments** (`Language.filmsByRentalRate`), not `@asConnection`: `@asConnection` synthesizes only `first`/`after`, and the backward walk needs `last`/`before`.
- **The execution walks judge the seeded rows, not `totalCount`.** Execution-tier classes share one database and insert films concurrently, so a walked count and a `totalCount` are two reads another class can write between. Each walk asserts that no row repeats and that the five seeded films arrive exactly once each in rate-then-id order. With completion disabled all five tests failed (the forward `@defaultOrder` walk returned `[1, 3, 2]`, dropping films 4 and 5 from the 2.99 tie); with it they pass.
- **`RENTAL_RATE` went on `FilmSort` itself;** no existing `filmsOrderedConnection` assertion enumerates the enum.
- **The `tilgangerConnection` finding is accepted into `FixtureWarningsGateTest`'s baseline,** which now pins three advisories: the gate is the evidence for branch 4 on a real routine terminus. The same test's pinned line for `filmsConnectionByRequiredIds` moves from 702 to 708, the fixture fields added above it.
- **Of the `GraphitronSchemaBuilderTest` cases the plan listed as pinning today's counts, only `CONNECTION_WITH_DEFAULT_ORDER_INDEX_SPLIT_CLASSIFIED` is a connection;** the rest are plain lists and stay unchanged, as the rule says they should. The split case now asserts `last_name, actor_id`. The new cases live in their own `ConnectionOrderCompletionCase` enum, one per branch.

## Relation to the related-table ordering item

R1083 (`order-by-related-table-column.md`, in Spec) states its own version of this rule: append the primary key ascending unless own-table columns cover the PK or a unique constraint. This item owns the own-table rule, with the direction and the nullable-unique-key refinement above. R1083 then only needs one addition: columns reached through a `path:` never count toward identifying a row. Its tiebreaker section should defer to this item at its next revision, with a `depends-on: [R1106]` edge, which is what R1083's first review round (finding 2) asks for. That round's finding 6 also points R1083 at `MatchedKeys` for key coverage, the same primitive this item builds row identity on.

## Other solutions we've considered

- **Reject non-unique orders at build time** (the reporter's alternative). This is loud, but it pushes the same chore onto every author and every `@order` value. It also cannot judge keyless tables any better than the warning does. Completion fixes the bug without asking anything of the author.
- **Opt-in `@defaultOrder(fields: ..., primaryKey: true)`** (the reporter's smaller step). It still fails silently when forgotten, which is the failure being fixed.
- **Append always-ascending**, as R1083 currently states. Rejected for the reasons under Direction: it turns uniform `DESC` orders into mixed ones that a plain index cannot serve, and it would need special-casing to stay ascending under the runtime flip.
- **Append the primary key to every order that does not list it, ignoring unique-key coverage.** This is one predicate, but it changes the SQL and invalidates cursors of fields that are already correct (an order on a unique `email`) and gains nothing.
- **Record which entries were appended** (an authored count beside `Fixed.columns()`). No consumer in this item needs the distinction: emitters read one list, and the tests assert the completed list. Add it when a consumer appears, such as an LSP hover that shows the appended key.
- **Complete plain lists too.** That would give them deterministic order between runs, but it changes the SQL and plans of every ordered list for a property no one asked for and no page boundary needs. It can be a separate item if wanted.
