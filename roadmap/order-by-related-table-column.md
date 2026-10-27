---
id: R1083
title: "@order: sort by a column on a to-one related table via path, or by a method-supplied expression"
status: Spec
bucket: architecture
priority: 5
theme: codegen-correctness
depends-on: []
created: 2026-10-21
last-updated: 2026-10-27
---

# @order: sort by a column on a to-one related table via path, or by a method-supplied expression

## Goal

An `@order` or `@defaultOrder` entry can sort by a value that does not live in a column of the field's own table, without denormalizing it into that table. Two forms cover the two shapes such a value takes. When the value is a column on a table reached through to-one hops, the author names the column and a `path:` in the same `ReferenceElement` shape as `@reference(path:)`. When the value has to be derived from several related rows, the motivating case of GitHub issue #562 (order `Utdanningsinstans` by the `navn_spesifikasjon` of its latest `Utdanningsinstansnavn`, of which each instance has many), the author supplies the sort key as an expression through `method:`, a static Java method with `@externalField`'s calling convention: it takes the field's table and returns a `Field<?>`. Either way each parent row has exactly one sort key, and graphitron keeps owning direction, keyset pagination and the cursor.

## The gap

`@order(fields: [{name: ...}])` resolves every `FieldSort.name` against the field's own table (`OrderByResolver.resolveOrderEntries` calls `ctx.catalog.findColumn(tableSqlName, colName)`), so a column on a joined table is a build rejection. The only workaround today is copying the value into the main table.

## Proposed surface

`FieldSort` gains two optional arguments, `path:` and `method:`. An entry sets exactly one of `name:` and `method:`; `path:` is only valid beside `name:`.

### `path:`: a column on a to-one related table

Add an optional `path: [ReferenceElement!]` to `FieldSort`, reusing the existing input type and its `table:` / `key:` / `condition:` semantics, so there is one way to say "walk from here to that table" across the schema:

```graphql
enum QueryUtdanningsinstanserOrderByFieldInput {
    FRA_ARSTALL @order(fields: [{name: "ARSTALL_FRA"}])
    NAVN        @order(fields: [{
        name: "NAVN_SPESIFIKASJON",
        path: [{key: "utdanningsinstans_navn_utdanningsinstans_fkey"}]
    }])
}
```

`name` is resolved against the table the path lands on; with no `path:` nothing changes. The same argument belongs on `@defaultOrder` since both directives share `FieldSort`. `index:` and `primaryKey:` are table-scoped and stay own-table only.

The path is always written out: there is no inferred or empty form, so a column name never silently changes table.

### `method:`: a sort key the author derives

Add an optional `method: ExternalCodeReference` to `FieldSort`. It names a static method with exactly `@externalField`'s convention: its sole parameter is the jOOQ table class of the field's table, it receives the alias the generated query uses, and it returns a `Field<?>` that is that row's sort key. `argMapping:` is rejected, as it is on `@externalField`; a parameterised sort key (for example "the name valid on a date argument") is a later extension, not part of this item.

```graphql
enum QueryUtdanningsinstanserOrderByFieldInput {
    NAVN @order(fields: [{method: {className: "no.sikt.fs.Ordering", method: "gjeldendeNavn"}}])
}
```

```java
public static Field<String> gjeldendeNavn(Utdanningsinstans t) {
    var n = UTDANNINGSINSTANSNAVN.as("n");
    return DSL.field(DSL.select(n.NAVN_SPESIFIKASJON).from(n)
        .where(n.UTDANNINGSINSTANS_ID.eq(t.ID))
        .orderBy(n.ARSTALL_FRA.desc()).limit(1));
}
```

The rule for which related row counts (the latest, an approved one, any filter the reporter's `condition` would have expressed) lives once, in jOOQ, inside the method; it is not restated per enum value, and the same method can back an `@externalField` when the value should also be selectable. `direction:` and `collate:` apply to the returned expression as they do to a column. The method is not a hop and introduces no join, so the cardinality rule below does not apply to it: a `Field<?>` is one value per row by type. A method returning several rows per parent fails at runtime in the database, as a scalar subquery does; the build cannot see inside the method and does not try.

`method:` with `path:` is rejected (the method already receives the field's table and can reach anything from it). `index:` and `primaryKey:` stay as they are.

For a consumer that would rather keep the rule in the database, a view with a unique key on the parent's id (for example `utdanningsinstans_gjeldende_navn`) plus a jOOQ synthetic foreign key to it makes the hop to-one, and the `path:` form then serves the same case with no code. `order.adoc` documents this as the no-code alternative.

## The cardinality rule

An ordering path must never change the cardinality of the row set it orders: every hop must be to-one, so each parent row yields exactly one sort key. This is `JoinStep`'s "no unintended row multiplication" invariant applied without its list-field escape hatch, because an ORDER BY has no meaningful fan-out: joining a to-many hop would duplicate parent rows in the page (and break `first`/`after` counts and cursors), and aggregating to pick a key would invent a semantics the author did not write.

A hop is to-one when it traverses an FK from the table that holds the FK columns to the table they reference (child to parent), or when it traverses the FK the other way and the FK columns are covered by a unique constraint or the primary key on the holding table (a one-to-one extension table). Anything else is to-many and is rejected.

Consequences, stated so the reviewer does not have to derive them:

- A hop that carries only a `condition:` (an `On.Predicate` hop with no `key:`/`table:`) has no FK to prove uniqueness from, so it is rejected; the author must name a `key:`. A `condition:` accompanying a `key:` is a per-hop filter, does not change the key's cardinality, and is allowed.
- The classification is catalog-derived at build time, not author-asserted; there is no opt-out argument.
- A parent with no related row still appears: the join is a LEFT JOIN, so the sort key is `NULL`. Nulls placement follows the database default for the direction unless the spec pins it (see forks), and must be part of the cursor.

The rejection names the field coordinate, the enum value, the offending hop, and the direction in which it fans out (for example "hop 1 `utdanningsinstans` to `utdanningsinstansnavn` via `<key>` is to-many: one parent has many `utdanningsinstansnavn` rows; an ordering path may only follow to-one hops"). A to-many relation is the `method:` form's case, and the rejection message says so: it points at `method:` and at the view-plus-synthetic-key alternative.

## Tiebreaker rule

The ordering is deterministic or it is not valid for keyset pagination, so the resolved order is completed with tiebreaker columns unless the configured columns already cover a key of the field's own table. The rule, as decided:

- If the configured own-table columns (in any position) cover the primary key or a unique constraint of the field's table, add nothing.
- Otherwise append the table's primary key columns after the configured entries, ascending.
- Columns reached through a `path:` never count toward coverage: a to-one hop makes many parent rows share one related row, so a related-table column, even that table's primary key, cannot identify a parent row. An order made only of path columns therefore always gets the primary key appended.
- A `method:` entry never counts toward coverage either: the build cannot see what the expression returns, so an order containing one is completed with the primary key unless its own-table columns already cover a key.

Plan step 4 checks what `OrderBySpec.Fixed` and `resolveDefaultOrderSpec` do today for own-table orders and extends that mechanism to the coverage test above rather than adding a second one; if today's behaviour already is "append the PK unless covered", the work is only teaching the coverage test to ignore path columns.

## Plan

1. SDL and docs: add `path` to `FieldSort` in `directives.graphqls`; update `docs/manual/reference/directives/order.adoc` and `defaultOrder.adoc` (including the to-one rule and its rejection), cross-linking `reference.adoc`.
2. Resolution: in `OrderByResolver.resolveOrderEntries`, when `path` is present, resolve it through the shared `BuildContext.parsePath` (start = the field's table, terminal table taken from the last hop; reuse its error messages rather than a parallel parser), then look the column up on the terminal table.
3. Cardinality check: a catalog query answering "is this hop to-one from its origin table", derived from FK direction plus unique/PK coverage on the holding table. No hop-level cardinality fact exists in the tree today, so this is new; keep it a single function the rejection and any later consumer share, and apply the Adding-a-relation provenance rule if it ends up stored rather than computed (it should be computed from the jOOQ catalog).
4. Tiebreaker: apply the Tiebreaker rule above, reusing the existing completion mechanism if there is one.
5. Model: widen `OrderBySpec.ColumnOrderEntry` with the resolved join steps (empty for own-table columns) so the emitter knows which table alias the column belongs to.
6. Emission: LEFT JOIN the path in the generated select and order by the aliased column, including the keyset-pagination cursor and the backward flip, which today assume columns of the root table.
7. Validation: reject at classification, through the existing `OrderByResolver` rejection route, a non-to-one hop, a `condition:`-only hop, an unknown column on the landing table, and an unresolvable path.
8. `method:` resolution: resolve the reference with the machinery `@externalField` already uses (class and method lookup, the sole parameter typed as the field's table class, a `Field<?>` return); reject a missing method, a wrong parameter or return type, `argMapping:`, `method:` together with `name:` or `path:`, and neither of `name:`/`method:` set. Widen the model with a sealed sibling of `OrderBySpec.ColumnOrderEntry`, an expression entry carrying the method reference, collation and direction, rather than a nullable method field on the column entry.
9. `method:` emission: call the method once per query with the field's alias, project the returned expression under its own minted alias (finding 5's projection and alias rule applies to it as to a path column), and drive `orderBy`, `seek` and the cursor from that projection, so the cursor encodes the sort key's value and not the method. A `NULL` sort key (a parent with no related row) meets the same null-safe seek requirement as a nullable path column (finding 3).

## Design forks to settle during Spec review

- **Nulls placement.** Related row absent means a `NULL` key. Recommendation: do not invent a rule; inherit the database default per direction, and make the cursor encode nullness so forward and backward pages agree. Confirm that the existing keyset emission can express this for own-table nullable columns today; if it cannot, that is a prerequisite this item must name.
- **Multi-hop paths.** Allowed provided every hop is to-one. Confirm the alias scheme in `JoinStep.Hop` stays unique when the order join and a selected-field join traverse the same FK in one statement (reuse versus a second alias).

## Tests

- Pipeline-tier classification: accepted to-one path (child to parent), accepted one-to-one extension hop, rejected to-many hop (message names the hop and direction), rejected `condition:`-only hop, unknown column on the landing table, unresolvable `key:`.
- Pipeline-tier tiebreaker: key already covered (no append), key not covered (PK appended), path-only order (PK appended even when the path column is the related table's PK), `method:`-only order (PK appended).
- Pipeline-tier `method:` classification: accepted method, missing method, wrong parameter type, non-`Field` return, `argMapping:` rejected, `method:` with `name:`, `method:` with `path:`, neither set.
- Execution-tier: a connection ordered by a column on a to-one related table against the sakila fixtures, forward and backward pagination, a parent with no related row, and duplicate sort keys resolved by the appended primary key.
- Execution-tier `method:`: a connection ordered by a correlated "latest related row" expression over a to-many relation in the sakila fixtures (for example customers by the date of their latest rental), forward and backward pagination, a parent with no related row, and two parents with equal sort keys resolved by the appended primary key.

## Other solutions we've considered

- **Pick one row per hop** (`ReferenceElement` gains an ordering, and a to-many hop that carries one counts as to-one). Rejected: the `LIMIT 1` a single-row read emits caps the whole correlated subquery, not one hop, so on a multi-hop path "first row at hop 1, then continue" renders as "order the final rows by hop-1 columns, keep one"; the rule for which row counts would be restated at every enum value and field path that uses it; and the same element would mean "choose a row" on a single field, "make this hop to-one" in an ordering path, and nothing on a list field.
- **Let the author assert that a hop's `condition:` selects one row.** Rejected: it trades the catalog-derived cardinality rule for an unchecked claim whose failure is silent (duplicated parents, broken page counts and cursors).
- **Order by a GraphQL field** (`{field: "gjeldendeNavn.navnSpesifikasjon"}`, reusing a single-valued field's own row choice). Rejected: it introduces a second naming system into `@order`, which names database columns, and ties ordering to how the field is delivered (`@splitQuery`, services).

## Reviewer findings

### Round 1: Spec → Ready, withheld (session_01FY5UvuTmpwuywkgQ7ofmBJ, 2026-10-27)

1. **Goal viability: the reporter's reply asks for the case this item rejects.** The spec makes Ready conditional on confirming the reporter's relation is to-one. The reply on issue #562 (2026-10-26) says "to-one", then asks for a `condition` so the order can "hente ut den seneste" (pick the latest) `Utdanningsinstansnavn` row. That is a to-many hop narrowed to one row by a predicate. Under the cardinality rule, a `condition:` beside a `key:` does not change cardinality, and the to-one test is catalog-derived with no opt-out, so that schema is rejected. The SDL example points the same way: by the `<holder>_<column>_fkey` convention, `utdanningsinstans_navn_utdanningsinstans_fkey` is held by `utdanningsinstans_navn`, so the hop runs parent to child and is to-one only if the FK column is unique. Before Ready the author must settle with the reporter whether the relation is really unique. If it is not, the `## Goal` as written does not serve #562: either the goal is restated without #562 as its motivating case, or the item takes on a designed way to pick one row. Which one is the author's call.

   > *Author response (session 01WkW2JHiX9Y4NenjwpaKZhb, 2026-10-27).* Settled as "the item takes on a designed way", but not by picking a row: the reporter needs one sort key per parent derived from a to-many relation, which is an expression, not a path. The goal and `## Proposed surface` now add `FieldSort.method:` with `@externalField`'s calling convention (the field's table in, `Field<?>` out), plan steps 8 and 9 and the tests cover it, and the to-one rule for `path:` is unchanged. The per-hop pick, author-asserted cardinality and ordering by a GraphQL field are under `## Other solutions we've considered`. The view-plus-synthetic-key route is documented as the no-code alternative. Findings 2 to 8 are not addressed in this revision; findings 3 and 5 now also bind the `method:` emission, as step 9 says.

2. **Tiebreaker: the general rule belongs to R1106, and today's code has no mechanism.** `OrderByResolver.resolveOrderEntries` and the `@orderBy` helper bodies (`TypeFetcherGenerator.buildSingleArgOrderByBody` / `buildListArgOrderByBody`) emit exactly the configured columns and append nothing, so plan step 4's "if today's behaviour already is ..." resolves to "it is not". R1106 (Backlog, filed 2026-10-27, issue #559) owns appending the primary key to own-table orders and says this item should only teach the coverage test to ignore path columns. As written, this item's Tiebreaker rule changes every non-unique own-table `@order` in the tree, which is R1106's change. This needs `depends-on: [R1106]` (or an explicit decision to absorb it), and the section should shrink to the path-column exclusion. R1106's open questions (append direction, tables with no key) decide what the shrunk section reads.

3. **Nulls placement is a blocker, not a fork.** The cursor already encodes nullness: `ConnectionHelper.encodeCursor` writes SQL `NULL` as `\u0001`. Paging, though, goes through jOOQ's `.seek(page.seekFields())` (`RootLauncherRenderer`, `BatchedRowsFragments`), which renders a row-value or expanded comparison. A `NULL` key compares UNKNOWN, so once the cursor passes them, parents with no related row (`NULL` sort key from the LEFT JOIN) never come back, and a cursor that carries a `NULL` returns nothing after it. The named execution test "a parent with no related row, forward and backward pagination" fails under the emission as planned. The plan has to name a null-safe seek predicate, either in scope or as a prerequisite item. Own-table nullable order columns have the same defect today, which may be the better home for it.

4. **A per-hop `condition:` would drop parents.** `JoinStep.Hop.filter` is documented as "appended to the enclosing SELECT's WHERE, not the JOIN's ON clause". On a LEFT JOIN order path, a WHERE predicate on the joined alias removes every parent without a matching related row. That changes the cardinality of the ordered row set, which the cardinality rule forbids, and it breaks the "a parent with no related row still appears" promise. The plan has to say either that an ordering path's filter goes into the ON clause (a different emit than every other `Hop.filter` consumer) or that `condition:` is rejected on ordering paths. This interacts with finding 1, since the reporter wants a condition.

5. **Projection and alias collisions in the emitter.** `ConnectionHelper.pageRequest` merges `extraFields` into the selection deduplicated by `Field.getName()`, and `encodeCursor` reads `record.get(orderByColumn)`. A landing-table column whose unqualified name matches a selected root column (`navn`, `id`) is dropped from the select, and the cursor reads the wrong column or an ambiguous one. Step 6 must say that a path column is projected under its own alias and that this alias drives `orderBy`, `seek` and cursor encoding. On the alias fork: `JoinStep.Hop.alias` is minted as `fieldName + "_" + stepIndex`, so an order path and the field's own `@reference` path on the same field (a split connection) produce identical aliases by construction. Reuse versus a distinct prefix is the author's choice, but "stays unique" is not true today.

6. **Architecture fit: the to-one test exists as a primitive.** Plan step 3 says this needs a new catalog query. `JooqCatalog.candidateKeys` plus `MatchedKeys.firstCovered` already answer "do these columns cover the PK or a unique key of table T". Because `On.ColumnPairs.slots` are oriented at synthesis (`targetSide` is on the hop's target), "to-one" is just "the target-side columns cover a candidate key of the target table". That one check handles child to parent and one-to-one extension alike, without branching on direction. The same primitive answers the coverage half of the tiebreaker (finding 2). Step 3 should extend it, not stand up a second key-coverage function, and should say what happens to `On.Lateral` (routine) and `Keying.NameMatchedKey` hops.

7. **Unnamed consumers of `FieldSort`: the editor and the fact store.** `LspVocabulary` binds `FieldSort.name` through `CatalogColumnBinding` to the field's element table, via `FieldColumnTable`. With `path:`, the editor would check and complete the name against the wrong table and flag every valid path-ordered column. A `ReferenceElement.key` inside `FieldSort` also needs an origin scope. In the store, `graphitron_ast_order_field_entry`, `graphitron_ast_default_order_field_entry` and `graphitron_default_order_field_entry` capture `FieldSort` elements as written, while path elements live in `graphitron_ast_reference_element_entry`, which is keyed to a `@reference` application (`graphitron_ast_reference_entry`), so a `FieldSort` path has nowhere to go. The plan needs a step that says how the path is captured (under the adding-a-relation discipline) and what the LSP resolves `name` against.

8. **Scope edges the plan does not state.** (a) Multi-table polymorphic roots resolve `FieldSort` once per participant and emit their order through `MultiTablePolymorphicEmitter`. Say whether `path:` there is rejected or supported. (b) Step 6 says "the generated select", but ordering is emitted at several sites: root launchers (`RootLauncherRenderer`), split/batched rows (`BatchedRowsFragments`, `SplitRowsMethodEmitter`), and non-connection lists (`TypeFetcherGenerator.buildOrderByCode`). List which ones the order join must reach, or which shapes are rejected.

### Round 2: Spec → Ready, withheld (session_01FY5UvuAKLm3cAVHbkmm6Pf, 2026-10-27)

Question 1 now passes. A consumer can sort a field by a value outside its own table in one of two ways: a column one or more to-one hops away, named with `path:`, or a key that a static method computes from the field's table, named with `method:`. Graphitron still owns direction, paging and the cursor in both cases, and the motivating case from #562 is served by `method:`. The outcome is reachable. `ServiceCatalog.reflectExternalField` already checks the parameter-is-the-parent-table and returns-`Field<?>` convention, `FieldBuilder.parseExternalRef` already rejects `argMapping` for `@externalField`, and `ResultKeyAliasedField` is the existing shape for projecting a computed term under a reserved alias, which is what step 9 needs. Settling finding 1 with an expression rather than a per-hop row pick fits the tree.

Question 2 still fails. The author's note says findings 2 to 8 are not addressed in this revision.

1. **Round-1 findings 2 to 8 are still open, and each one still holds against trunk `d1a49a9`.** I re-checked the code each one cites: `.seek(page.seekFields())` in `RootLauncherRenderer` and `BatchedRowsFragments`; the `JoinStep.Hop` javadoc that puts `filter` in the WHERE and mints the alias as `fieldName + "_" + stepIndex`; the `FieldSort.name` binding in `LspVocabulary`; and the three `*_order_field_entry` store tables. Each finding names what would satisfy it. They are the plan, not polish: as written, an implementer would redesign nulls, filters, aliases, the to-one primitive, the store capture and the emission sites as they went.

2. **Finding 2 has hardened: R1106 has fixed the rule this item contradicts.** R1106 is now In Progress, and its `## The rule` and `## Implementation` settle the tiebreaker. It applies to connections only, not plain lists or multitable participants. The row key is the primary key, then the node key, then a unique key whose columns are all `NOT NULL`. Appended columns take the direction of the last entry. A list-valued `@orderBy` is completed in the generated helper by comparing `Field.getName()`. This item's `## Tiebreaker rule` disagrees on three points: it covers every order, it appends ascending, and it appends the primary key only. To satisfy this finding, add `depends-on: [R1106]` and reduce the section to what this item adds to R1106's identifying-key test. Path and method entries never count toward coverage, in both the resolver arm and the list-valued helper's name check. A name check needs care: a path column named like an own-table key column, such as a related table's `id`, would count as coverage. Also say how the new expression sibling of `ColumnOrderEntry` passes through R1106's completion and its `Single`/`Sequence` composition.

3. **The `method:` surface is missing from the plan's SDL, editor and store steps.** "An entry sets exactly one of `name:` and `method:`" means `FieldSort.name` has to change from `String!` to nullable. Step 1 adds only `path`. It needs to say `method` is added too and `name` is relaxed, and that change has consumers the plan does not name. `graphitron_ast_default_order_field_entry.name_ref` and `graphitron_default_order_field_entry.name_ref` are `NOT NULL` (`graphitron_ast_order_field_entry.name_ref` is already nullable). The `ExternalCodeReference` in a `FieldSort` has nowhere to be captured. The LSP needs to know that a `FieldSort` can have no `name`. This is the same missing step as round-1 finding 7, now with a second argument. The step that answers 7 should cover both `path:` and `method:` under the adding-a-relation discipline. Step 8 should also say how it gets to `reflectExternalField`. `parseExternalRef` reads a directive argument from a `GraphQLFieldDefinition`, but this reference is an element nested in a list inside a directive on an enum value, so the argMapping rejection has to be applied at the new parse site.

Verdict: stays in Spec. Settle round-1 findings 2 to 8 and findings 2 and 3 above, and the next pass can sign off. Nothing in question 1 needs to be reopened.
