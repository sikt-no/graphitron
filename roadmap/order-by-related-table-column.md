---
id: R1083
title: "@order: sort by a column on a related table via path"
status: Spec
bucket: architecture
priority: 5
theme: codegen-correctness
depends-on: []
created: 2026-10-21
last-updated: 2026-10-21
---

# @order: sort by a column on a related table via path

## Goal

An `@order` enum value can sort by a column that lives on a related table, so a connection of `Utdanningsinstans` can be ordered by `Utdanningsinstansnavn.navn_spesifikasjon` without denormalizing the value into the parent table. The author names the related table with a `path:` argument that uses the same `ReferenceElement` shape as `@reference(path:)` (GitHub issue #562).

## The gap

`@order(fields: [{name: ...}])` resolves every `FieldSort.name` against the field's own table (`OrderByResolver.resolveOrderEntries` calls `ctx.catalog.findColumn(tableSqlName, colName)`), so a column on a joined table is a build rejection. The only workaround today is copying the value into the main table.

## Proposed surface

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

## The cardinality rule

An ordering path must never change the cardinality of the row set it orders: every hop must be to-one, so each parent row yields exactly one sort key. This is `JoinStep`'s "no unintended row multiplication" invariant applied without its list-field escape hatch, because an ORDER BY has no meaningful fan-out: joining a to-many hop would duplicate parent rows in the page (and break `first`/`after` counts and cursors), and aggregating to pick a key would invent a semantics the author did not write.

A hop is to-one when it traverses an FK from the table that holds the FK columns to the table they reference (child to parent), or when it traverses the FK the other way and the FK columns are covered by a unique constraint or the primary key on the holding table (a one-to-one extension table). Anything else is to-many and is rejected.

Consequences, stated so the reviewer does not have to derive them:

- A hop that carries only a `condition:` (an `On.Predicate` hop with no `key:`/`table:`) has no FK to prove uniqueness from, so it is rejected; the author must name a `key:`. A `condition:` accompanying a `key:` is a per-hop filter, does not change the key's cardinality, and is allowed.
- The classification is catalog-derived at build time, not author-asserted; there is no opt-out argument.
- A parent with no related row still appears: the join is a LEFT JOIN, so the sort key is `NULL`. Nulls placement follows the database default for the direction unless the spec pins it (see forks), and must be part of the cursor.

The rejection names the field coordinate, the enum value, the offending hop, and the direction in which it fans out (for example "hop 1 `utdanningsinstans` to `utdanningsinstansnavn` via `<key>` is to-many: one parent has many `utdanningsinstansnavn` rows; an ordering path may only follow to-one hops"). If the reporter's real relation is to-many, this item does not serve their case without a later, separately designed way to select one row; that must be confirmed with them before Ready.

## Tiebreaker rule

The ordering is deterministic or it is not valid for keyset pagination, so the resolved order is completed with tiebreaker columns unless the configured columns already cover a key of the field's own table. The rule, as decided:

- If the configured own-table columns (in any position) cover the primary key or a unique constraint of the field's table, add nothing.
- Otherwise append the table's primary key columns after the configured entries, ascending.
- Columns reached through a `path:` never count toward coverage: a to-one hop makes many parent rows share one related row, so a related-table column, even that table's primary key, cannot identify a parent row. An order made only of path columns therefore always gets the primary key appended.

Plan step 4 checks what `OrderBySpec.Fixed` and `resolveDefaultOrderSpec` do today for own-table orders and extends that mechanism to the coverage test above rather than adding a second one; if today's behaviour already is "append the PK unless covered", the work is only teaching the coverage test to ignore path columns.

## Plan

1. SDL and docs: add `path` to `FieldSort` in `directives.graphqls`; update `docs/manual/reference/directives/order.adoc` and `defaultOrder.adoc` (including the to-one rule and its rejection), cross-linking `reference.adoc`.
2. Resolution: in `OrderByResolver.resolveOrderEntries`, when `path` is present, resolve it through the shared `BuildContext.parsePath` (start = the field's table, terminal table taken from the last hop; reuse its error messages rather than a parallel parser), then look the column up on the terminal table.
3. Cardinality check: a catalog query answering "is this hop to-one from its origin table", derived from FK direction plus unique/PK coverage on the holding table. No hop-level cardinality fact exists in the tree today, so this is new; keep it a single function the rejection and any later consumer share, and apply the Adding-a-relation provenance rule if it ends up stored rather than computed (it should be computed from the jOOQ catalog).
4. Tiebreaker: apply the Tiebreaker rule above, reusing the existing completion mechanism if there is one.
5. Model: widen `OrderBySpec.ColumnOrderEntry` with the resolved join steps (empty for own-table columns) so the emitter knows which table alias the column belongs to.
6. Emission: LEFT JOIN the path in the generated select and order by the aliased column, including the keyset-pagination cursor and the backward flip, which today assume columns of the root table.
7. Validation: reject at classification, through the existing `OrderByResolver` rejection route, a non-to-one hop, a `condition:`-only hop, an unknown column on the landing table, and an unresolvable path.

## Design forks to settle during Spec review

- **Nulls placement.** Related row absent means a `NULL` key. Recommendation: do not invent a rule; inherit the database default per direction, and make the cursor encode nullness so forward and backward pages agree. Confirm that the existing keyset emission can express this for own-table nullable columns today; if it cannot, that is a prerequisite this item must name.
- **Multi-hop paths.** Allowed provided every hop is to-one. Confirm the alias scheme in `JoinStep.Hop` stays unique when the order join and a selected-field join traverse the same FK in one statement (reuse versus a second alias).

## Tests

- Pipeline-tier classification: accepted to-one path (child to parent), accepted one-to-one extension hop, rejected to-many hop (message names the hop and direction), rejected `condition:`-only hop, unknown column on the landing table, unresolvable `key:`.
- Pipeline-tier tiebreaker: key already covered (no append), key not covered (PK appended), path-only order (PK appended even when the path column is the related table's PK).
- Execution-tier: a connection ordered by a column on a to-one related table against the sakila fixtures, forward and backward pagination, a parent with no related row, and duplicate sort keys resolved by the appended primary key.

## Reviewer findings

### Round 1: Spec → Ready, withheld (session_01FY5UvuTmpwuywkgQ7ofmBJ, 2026-10-27)

1. **Goal viability: the reporter's reply asks for the case this item rejects.** The spec makes Ready conditional on confirming the reporter's relation is to-one. The reply on issue #562 (2026-10-26) says "to-one", then asks for a `condition` so the order can "hente ut den seneste" (pick the latest) `Utdanningsinstansnavn` row. That is a to-many hop narrowed to one row by a predicate. Under the cardinality rule, a `condition:` beside a `key:` does not change cardinality, and the to-one test is catalog-derived with no opt-out, so that schema is rejected. The SDL example points the same way: by the `<holder>_<column>_fkey` convention, `utdanningsinstans_navn_utdanningsinstans_fkey` is held by `utdanningsinstans_navn`, so the hop runs parent to child and is to-one only if the FK column is unique. Before Ready the author must settle with the reporter whether the relation is really unique. If it is not, the `## Goal` as written does not serve #562: either the goal is restated without #562 as its motivating case, or the item takes on a designed way to pick one row. Which one is the author's call.

2. **Tiebreaker: the general rule belongs to R1106, and today's code has no mechanism.** `OrderByResolver.resolveOrderEntries` and the `@orderBy` helper bodies (`TypeFetcherGenerator.buildSingleArgOrderByBody` / `buildListArgOrderByBody`) emit exactly the configured columns and append nothing, so plan step 4's "if today's behaviour already is ..." resolves to "it is not". R1106 (Backlog, filed 2026-10-27, issue #559) owns appending the primary key to own-table orders and says this item should only teach the coverage test to ignore path columns. As written, this item's Tiebreaker rule changes every non-unique own-table `@order` in the tree, which is R1106's change. This needs `depends-on: [R1106]` (or an explicit decision to absorb it), and the section should shrink to the path-column exclusion. R1106's open questions (append direction, tables with no key) decide what the shrunk section reads.

3. **Nulls placement is a blocker, not a fork.** The cursor already encodes nullness: `ConnectionHelper.encodeCursor` writes SQL `NULL` as `\u0001`. Paging, though, goes through jOOQ's `.seek(page.seekFields())` (`RootLauncherRenderer`, `BatchedRowsFragments`), which renders a row-value or expanded comparison. A `NULL` key compares UNKNOWN, so once the cursor passes them, parents with no related row (`NULL` sort key from the LEFT JOIN) never come back, and a cursor that carries a `NULL` returns nothing after it. The named execution test "a parent with no related row, forward and backward pagination" fails under the emission as planned. The plan has to name a null-safe seek predicate, either in scope or as a prerequisite item. Own-table nullable order columns have the same defect today, which may be the better home for it.

4. **A per-hop `condition:` would drop parents.** `JoinStep.Hop.filter` is documented as "appended to the enclosing SELECT's WHERE, not the JOIN's ON clause". On a LEFT JOIN order path, a WHERE predicate on the joined alias removes every parent without a matching related row. That changes the cardinality of the ordered row set, which the cardinality rule forbids, and it breaks the "a parent with no related row still appears" promise. The plan has to say either that an ordering path's filter goes into the ON clause (a different emit than every other `Hop.filter` consumer) or that `condition:` is rejected on ordering paths. This interacts with finding 1, since the reporter wants a condition.

5. **Projection and alias collisions in the emitter.** `ConnectionHelper.pageRequest` merges `extraFields` into the selection deduplicated by `Field.getName()`, and `encodeCursor` reads `record.get(orderByColumn)`. A landing-table column whose unqualified name matches a selected root column (`navn`, `id`) is dropped from the select, and the cursor reads the wrong column or an ambiguous one. Step 6 must say that a path column is projected under its own alias and that this alias drives `orderBy`, `seek` and cursor encoding. On the alias fork: `JoinStep.Hop.alias` is minted as `fieldName + "_" + stepIndex`, so an order path and the field's own `@reference` path on the same field (a split connection) produce identical aliases by construction. Reuse versus a distinct prefix is the author's choice, but "stays unique" is not true today.

6. **Architecture fit: the to-one test exists as a primitive.** Plan step 3 says this needs a new catalog query. `JooqCatalog.candidateKeys` plus `MatchedKeys.firstCovered` already answer "do these columns cover the PK or a unique key of table T". Because `On.ColumnPairs.slots` are oriented at synthesis (`targetSide` is on the hop's target), "to-one" is just "the target-side columns cover a candidate key of the target table". That one check handles child to parent and one-to-one extension alike, without branching on direction. The same primitive answers the coverage half of the tiebreaker (finding 2). Step 3 should extend it, not stand up a second key-coverage function, and should say what happens to `On.Lateral` (routine) and `Keying.NameMatchedKey` hops.

7. **Unnamed consumers of `FieldSort`: the editor and the fact store.** `LspVocabulary` binds `FieldSort.name` through `CatalogColumnBinding` to the field's element table, via `FieldColumnTable`. With `path:`, the editor would check and complete the name against the wrong table and flag every valid path-ordered column. A `ReferenceElement.key` inside `FieldSort` also needs an origin scope. In the store, `graphitron_ast_order_field_entry`, `graphitron_ast_default_order_field_entry` and `graphitron_default_order_field_entry` capture `FieldSort` elements as written, while path elements live in `graphitron_ast_reference_element_entry`, which is keyed to a `@reference` application (`graphitron_ast_reference_entry`), so a `FieldSort` path has nowhere to go. The plan needs a step that says how the path is captured (under the adding-a-relation discipline) and what the LSP resolves `name` against.

8. **Scope edges the plan does not state.** (a) Multi-table polymorphic roots resolve `FieldSort` once per participant and emit their order through `MultiTablePolymorphicEmitter`. Say whether `path:` there is rejected or supported. (b) Step 6 says "the generated select", but ordering is emitted at several sites: root launchers (`RootLauncherRenderer`), split/batched rows (`BatchedRowsFragments`, `SplitRowsMethodEmitter`), and non-connection lists (`TypeFetcherGenerator.buildOrderByCode`). List which ones the order join must reach, or which shapes are rejected.
