---
id: R1059
title: "A list-of-input filter whose element fields share a @reference path renders as one correlated EXISTS"
status: In Progress
bucket: feature
depends-on: []
created: 2026-10-17
last-updated: 2026-10-17
---

# A list-of-input filter whose element fields share a @reference path renders as one correlated EXISTS

## Goal

A filter field typed as a list of input objects, whose element fields all reach their columns through the same `@reference` path, builds and narrows the query instead of failing the build. The list becomes one correlated `EXISTS` over that path. Inside it, the columns of the row the path reaches are compared as a row against the elements, so a parent row matches when some row reached through the path equals some element. An absent, null or empty list applies no filter, and a null element matches nothing, exactly as for a list whose element fields name the parent table's own columns. Element fields on different paths, a mix of own columns and a path, and `@nodeId` element fields stay build errors.

From sis, `Query.vurderingsmeldinger` over `VURDERINGSMELDING`:

```graphql
input QueryVurderingsmeldingerUndervisningsterminFilterInput {
    arstall: Int! @reference(path: [{table: "VURDERINGSENHET"}, {key: "VURDERINGSENHET__HAR__VURDERINGSTID__FK"}]) @field(name: "ARSTALL_GJELDER_I")
    betegnelse: Undervisningsterminbetegnelse! @reference(path: [{table: "VURDERINGSENHET"}, {key: "VURDERINGSENHET__HAR__VURDERINGSTID__FK"}]) @field(name: "TERMINKODE_GJELDER_I")
}
# filter field: undervisningsterminkoder: [QueryVurderingsmeldingerUndervisningsterminFilterInput]
```

`undervisningsterminkoder: [{arstall: 2024, betegnelse: HOST}, {arstall: 2025, betegnelse: VAR}]` reads

```sql
EXISTS (SELECT 1 FROM vurderingstid vt JOIN vurderingsenhet ve ON <the FK>
        WHERE <ve correlated to the outer vurderingsmelding row>
          AND (vt.arstall_gjelder_i, vt.terminkode_gjelder_i) IN ((2024, <HOST's code>), (2025, <VAR's code>)))
```

## Context

Follow-up from the sis port (trunk `145ef1ae5`). R1057, now Done, made a list-of-input filter field a row-`IN` and rejected element fields carrying `@nodeId` or `@reference`. Of the five sis filters that rejection hit, three were fixed in the schema: two had a pointless `@reference` to a column on the table itself, and one had a nullable element field. The other two need the join, and sis cannot build until they are supported. The first is the Goal's example. The second is a three-hop path whose element type omits `@field` on one field, so its column defaults to the field name:

```graphql
# experimental, Query.resultater -> [Resultat] on STUDENTVURDKOMBPROTOKOLL
input ResultaterFilterTerminInput {
  arstall: Int! @reference(path: [{table: "VURDERINGSENHET"}, {key: "VURDERINGSENHET__HAR__VURDERINGSTID__FK"}, {table: "TERMIN"}])
  betegnelse: String! @field(name: "TERMINKODE") @reference(path: [{table: "VURDERINGSENHET"}, {key: "VURDERINGSENHET__HAR__VURDERINGSTID__FK"}, {table: "TERMIN"}])
}
# filter field: terminer: [ResultaterFilterTerminInput!]
```

Today's diagnostic for both is "input field 'arstall' ... is an element field of list-of-input filter field 'undervisningsterminkoder', whose elements are compared as rows: it carries @nodeId or @reference, whose column is reached through a key or a join a row of plain values cannot express".

Almost everything this item needs is already in the tree. A single-valued `@reference` filter field with a non-empty path lowers to `BodyParam.RemoteColumnPredicate`, a column predicate wrapped with the join path that reaches its column. `ConditionGlueRenderer.reachExists` renders that as a correlated `EXISTS`, hop by hop, including condition-join hops and a hop's own `{key:, condition:}` filter. R1057's list-of-input filter is a `BodyParam.RowIn` (or `In` at one cell) over a `CallSiteExtraction.ElementRows` read. `RemoteColumnPredicate` already wraps any `ColumnPredicate`, `ConditionCommands.termOf` already lowers it with the inner predicate's columns, and `ConditionGlueRenderer.columnCompare` already renders a row compare against a hop alias. The two have never been combined because `FieldBuilder.collectCells` refuses the reference cell first.

One defect sits on the path this item takes. The condition glue reads an `ID` value through `DSL.val(wire, table.<COL>.getDataType())`. It does that in both scalar `JooqConvert` arms of `ConditionGlueRenderer` (`extractionExpr` and `nestedExtraction`) and in the row helper's cell read. `table` is always the filter's own table. For a single-valued `ID`-typed `@reference` filter with a non-empty path, `<COL>` is a terminal-table column, so the emitted source names a column the own table's class does not have. No fixture has that shape, so nothing has caught it, and it is unconfirmed until the fixture below lands. A reached list level hits the same read for every cell, so the plan settles which table a value's columns live on once and has all three reads use it.

## Terms

- *List level*: a filter field (or argument) typed as a list of input objects. Its *element* is one input object in the list.
- *Cell*: one scalar element field, reached from the element through any non-list nested inputs, bound to one column. One element contributes one row of cell values.
- *Reach*: the resolved join path a `@reference` cell takes from the table the filter resolves against to the table holding its column. A cell with no `@reference`, or with an empty path, has an empty reach: its column is on the filter's own table.
- *Own-table level* and *reached level*: a list level whose cells all have an empty reach (R1057's shape, unchanged), and one whose cells all share one non-empty reach (this item).

## Semantics

- **A list level is own-table or reached, never both.** Every cell's reach must equal the first cell's, in declaration order.
- **Same reach means the same joins, whatever the spelling.** Two reaches are the same when they are hop by hop the same join: the same target, joined the same way (`JoinStep.Hop.on`), with the same hop filter. The SDL spelling does not matter, so `{table: "city"}` and `{key: "address_city_id_fkey"}` agree when the table form infers that key. The hop alias, which is minted per field (`<fieldName>_<stepIndex>`), is not part of the comparison.
- **A reached level is one `EXISTS` for the whole list.** It has the same `FROM`, joins, correlation and hop filters a single-valued reference filter on that path renders. Its last conjunct is `DSL.row(t.C1, ..., t.CN).in(rows)` against the terminal alias `t`, or `t.C1.in(values)` at one cell.
- **One reached row must match the whole element.** On a to-one path this is what writing the same fields as a non-list nested input means. On a path that fans out it is stricter: there each `@reference` field is its own `EXISTS`, so two fields can be satisfied by different reached rows. The row reading is the one a list compared as rows promises. On `film -> film_actor -> actor`, the element `(PENELOPE, WAHLBERG)` must not match film 1, whose cast holds a PENELOPE GUINESS and a NICK WAHLBERG. A parent row matching several elements, or matching through several reached rows, comes back once, because `EXISTS` does not multiply rows.
- **Presence and nulls follow R1057 unchanged.** An absent, null or empty list skips the whole `EXISTS` (the existing `!= null && !isEmpty()` guard in `appendGuardedAnd`). A null element contributes a row of typed nulls, which no reached row equals.
- **Cells follow R1057's rules.** A reached cell must be non-null, not a list, and carry no `@condition`. It reads its value the way a scalar at that position does: `Direct`, or `JooqConvert` for an `ID`. Its value is lifted at the data type of the terminal column.
- **An empty-path `@reference` is an own-table cell.** It binds the filter's own column (`FilterBinding.Local`), exactly as the non-list walk treats it, so it joins an own-table level rather than being refused.

### What stays rejected

- A list level mixing own-table and reached cells, or reached cells with different reaches. Every cell whose reach differs from the first cell's is a located diagnostic at its own coordinate, naming the occurrence and the first cell, plus R1057's one consequence rejection on the consuming field. Serving these would mean joining several paths in one `EXISTS` and deciding which reached rows must coincide. No consumer has asked for it, and admitting it later is additive (see Other solutions).
- An element field carrying `@nodeId`. That is a `ColumnBackedField` with a node-id decode, or an FK-target `@nodeId @reference` (a `ColumnBackedReferenceField` whose extraction is a `NodeIdDecodeKeys`). Decoding a key per element is not something the row helper does, so this does not come for free. The refusal text narrows to `@nodeId`.
- Every other R1057 rejection, unchanged: nullable, list-typed, list of inputs inside an element, `@condition` inside an element, unbound, and more than 22 cells.

Suppression is unchanged. Under an enclosing `@condition(override: true)`, or one on the list field, no predicate is emitted, so no reach agreement is owed and none is checked. Only a `@condition` inside the element is still refused there.

## Implementation

**Model.** No new arm. A reached level is `new BodyParam.RemoteColumnPredicate(reach, inner)`, where `inner` is the `RowIn`, or `In` at one cell, that R1057 builds. Its columns are the terminal-table columns. Its extraction is the `ElementRows` read, bare at depth 0 and under `NestedInputField` otherwise. The cells carry the terminal columns, so the `In`/`RowIn` column-agreement check in their compact constructors holds as it stands. `CallSiteExtraction.Cell` is unchanged.

Reach equality lives on the hop, beside the components it compares: `JoinStep.Hop.sameJoinAs(Hop other)` holds when the two agree on `target`, `on`, `originTable` and `filter`. Its javadoc `{@link}`s each of them and says why `alias` is left out: it is an emit-side name minted per field. Every component is a record, so this is value equality. Two reaches are the same when they have equal length and agree hop by hop. That loop is a private helper in `FieldBuilder`, its one caller. A unit test enforces the covered set: it reads `Hop`'s record components reflectively and fails when one other than `alias` exists that `sameJoinAs` does not compare, so a sixth component cannot drift in silently. The level's reach is its first cell's `joinPath`. The condition glue mints its own hop locals (`table_fkt<p>_<h>` in `declareReachAliases`), and `Hop.alias()` is read only by the projection and routine renderers, so the alias reaches no condition code. The implementer confirms the same for the multi-table lowering.

**Classification** (`FieldBuilder`).
- `collectCells`, `ColumnBackedReferenceField` arm. The arm stops refusing on `@reference` alone.
  - Plain `@reference` (extraction `Direct`), on either binding: take the same per-field checks as the `ColumnBackedField` arm (list, nullable, leaf kind). Then add the cell with column `predicateColumns(rf.binding(), rf.columns()).get(0)`, the expression `columnBindingOf` records the slot under, and leaf `implicitLeaf(rf.extraction(), rf.typeName(), column)`. The cell's reach is `rf.joinPath()` for a `Remote` binding and empty for a `Local` one.
  - `NodeIdDecodeKeys` extraction: refused with the `@nodeId` text.
  - The checks the two arms share move into one private method, so the cell rules are stated once.
- Cells are collected with their reach and their field. A private record pairing the `Cell` with its `joinPath` and the `InputField` is enough; the field is what the agreement diagnostic needs for its coordinate and location.
- `walkListLevel` runs the agreement check right after `collectCells` returns, ahead of the `offending > 0` consequence and its early return, and skips it on a suppressed level. Each cell whose reach is not the same as the first cell's mints a located `ValidationError` and counts toward `offending`. The located message `collectCells` builds inline today moves into a private method both sites call. A non-empty level reach wraps the predicate through `wrapIfRemote(inner, new FilterBinding.Remote(), reach)`, the one place that already decides local versus remote. An empty reach leaves the bare predicate, so R1057's output is byte-for-byte unchanged.
- `columnBindingOf` is unchanged. It already records a reference field's slot with `predicateColumns(binding, columns)`, which is the terminal column for a `Remote` binding, and the same `implicitLeaf`. `ColumnBindingLedger.requireCovers` already unwraps `RemoteColumnPredicate` and expects one slot per cell. So the cross-check holds with no change, and the tenant fold reads exactly the slots the same fields would record as a non-list nested input.

**Emission** (`ConditionCommands`, `ConditionGlueRenderer`).
- `termExpr`, `reachExists` and `columnCompare` need no change. `ConditionCommands.termOf` already lowers a `RemoteColumnPredicate` to a term with the inner predicate's columns, `MEMBERSHIP` and the narrowed `ReachPath`. `columnCompare` against the terminal alias then renders `DSL.row(t.C1, t.C2).in(local)`.
- The one change is which table a value's columns are read off when the local is built. That is the row helper's `table.<COL>.getDataType()` for each cell's lift, its typed nulls and its `JooqConvert` read, and the two scalar `JooqConvert` arms (see Context). It is decided once, where the term is made, and read by all three.
  - `ArgBinding` gains a component naming the table the binding's columns live on. For example `Optional<TableRef> reachedTable`: empty means the glue's own `table`, present means a reach terminal.
  - `ConditionCommands.termOf` sets it to the last hop's `targetTable()` when it narrows a non-empty reach, and leaves it empty otherwise. Authored bindings (`ConditionCommands` builds them beside the generated terms) leave it empty.
  - The renderer reads it off the binding. When it is present, `rowsCall` registers the helper against that table's class (`CatalogRefs.tableClass`) and passes its catalog constant (`CatalogRefs.constantsClass(t)` plus `t.javaFieldName()`, the expression `declareReachAliases` aliases). The two `JooqConvert` arms read `getDataType()` off the same constant. When it is empty, every read keeps `table`, so own-table output is unchanged.
  - An unaliased constant is sound here: these reads take only a `DataType`, which no alias changes. Helper locality (`docs/architecture/reference/emitter-conventions.adoc` § Helper-locality) still holds, because the helper still takes its table as a parameter.
  - `distinctBindings` shares a local between terms by `localName`, which `LocalNames` keys on the `CallParam`. A reached level's `CallParam` belongs to its one list field, so two terms sharing a local cannot disagree on the table. The `ArgBinding` compact constructor needs no check for it, and the implementer confirms the same holds for the scalar reference arms.
- `ElementRowsHelperRegistry` needs no logic change. Its key already includes the table class, so a reached helper and an own-table helper over the same cells cannot collide. Its javadoc, which says the helper takes "the aliased table", is updated to say the table the cells' columns belong to.

**Multi-table roots.** Single-valued reference filters already lower per participant. The implementer confirms that `MultiTableFilterLoweringTest`'s lowering carries a reached level through, and adds a case beside `listOfInputFilter_lowersPerParticipantAsRowIn`.

**Docs.** The `field.adoc` list-of-input paragraph and the `diagnostics-glossary.adoc` `#list-of-input-filter` entry take the draft below.

## Tests

**Pipeline tier**, in `ListOfInputFilterPipelineTest`:
- Positive cases:
  - a two-cell element on one single-hop reach classifies as one `RemoteColumnPredicate` whose inner `RowIn` holds the terminal columns, with an `ElementRows` leaf under `NestedInputField`, and no per-field predicate;
  - a one-cell reached element classifies as `RemoteColumnPredicate` over `In`. The fixture of today's `referenceElementField_isRejected` (actor `first_name` via `film_actor_actor_id_fkey`) becomes this positive case;
  - a multi-hop reach carries both hops;
  - the same foreign key spelled `{table:}` in one cell and `{key:}` in the other is admitted, which pins reach equality against the alias and the spelling;
  - a list-typed argument classifies with a bare `ElementRows` inside the `RemoteColumnPredicate`;
  - an `ID`-typed reached cell carries `JooqConvert`;
  - an empty-path `@reference` cell beside a plain cell classifies as an own-table `RowIn`.
- Negative cases, each asserting the located diagnostic at the offending field plus the consequence: two different reaches (located at the second cell); an own-table cell followed by a reached cell; a nullable reached cell. `nodeIdElementField_isRejected` stays as it is.
- Suppression: reached cells with different reaches under an enclosing `@condition(override: true)` yield no rejection and no predicate.
- `elementFieldSlots_keepTheirShapeInTheColumnBindingLedger` gains a reached cell.
- The `@AfterAll` invariant needs no change.

**Unit tier**: the `Hop.sameJoinAs` coverage test described under Model. A `ConditionCommandsPipelineTest` case asserts that a reached level's term binding carries the terminal table and an own-table level's carries none.

**Render tier**: a `ConditionGlueRendererTest` case asserting the reached level's helper method is declared with the terminal table class as its second parameter type. That is a structural assertion on the method spec, not on the body text. What the call passes is pinned by compilation: the sakila example's emitted sources compile at release 17, and the `ID`-typed cell below forces the helper's `JooqConvert` read onto a column the own table does not have.

**Execution tier**, in `graphitron-sakila-example` over seed data already in `init.sql`. Add to `schema.graphqls`:

```graphql
input CustomerAddressInput {
    district: String! @field(name: "district") @reference(path: [{key: "customer_address_id_fkey"}])
    cityId: ID! @field(name: "city_id") @reference(path: [{key: "customer_address_id_fkey"}])
}
input CustomerAddressFilter { addresses: [CustomerAddressInput] }
input CustomerCityFilter {
    cityId: ID @field(name: "city_id") @reference(path: [{key: "customer_address_id_fkey"}])
}
input FilmCastNameInput {
    firstName: String! @field(name: "first_name") @reference(path: [{key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])
    lastName: String! @field(name: "last_name") @reference(path: [{key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])
}

type Query {
    customersByAddresses(filter: CustomerAddressFilter): [Customer!]!
    filmsByCastNames(names: [FilmCastNameInput!]): [Film!]!
    customersByCityId(cityId: ID @field(name: "city_id") @reference(path: [{key: "customer_address_id_fkey"}])): [Customer!]!
    customersByCityFilter(filter: CustomerCityFilter): [Customer!]!
}
```

`cityId` is deliberately an `ID` over an `int` column `customer` does not have, so all three `JooqConvert` reads must resolve on `address`. `customersByCityId` and `customersByCityFilter` are the single-valued twins that pin the two scalar arms. If the defect in Context is real, they fail to compile before the change.

The seed has five customers on four addresses: Mary and Barbara at address 1 (Alberta, city 1), Patricia and Elizabeth at address 2 (Lazio, city 2), and Linda at address 3 (Alberta, city 1). Address 4 (Tasmania, city 1) has no customer. Cast: film 1 is PENELOPE GUINESS and NICK WAHLBERG, film 2 is PENELOPE GUINESS and ED CHASE, film 3 is PENELOPE GUINESS, film 4 is NICK WAHLBERG, and film 5 is ED CHASE. New `GraphQLQueryTest` cases go beside `listOfInputFilter_matchesRowsEqualToAnElement`:
- `addresses: [{district: "Alberta", cityId: "1"}, {district: "Tasmania", cityId: "2"}, {district: "Lazio", cityId: "1"}]` returns Mary, Linda and Barbara. Comparing the fields independently would return all five.
- a single object in place of the list filters by that object;
- `addresses: []`, `addresses: null` and an absent filter each return all five;
- `[{district: "Lazio", cityId: "2"}, null]` returns Patricia and Elizabeth, and `[null]` returns none;
- `customersByCityId(cityId: "2")` and `customersByCityFilter(filter: {cityId: "2"})` each return Patricia and Elizabeth;
- `filmsByCastNames(names: [{firstName: "PENELOPE", lastName: "WAHLBERG"}])` returns no film. One `EXISTS` per field would return film 1, so this is the one-reached-row proof;
- `names: [{firstName: "PENELOPE", lastName: "GUINESS"}, {firstName: "NICK", lastName: "WAHLBERG"}]` returns films 1, 2, 3 and 4, with film 1 once. This is the no-row-multiplication proof, and it also covers the list as the argument itself and a two-hop reach.

The sis build is the consumer-side confirmation. It is not part of this reactor's build.

## User documentation (first-client check)

In `docs/manual/reference/directives/field.adoc`, the second list-of-input paragraph becomes:

> Every field of the element type must be non-null and bound to a column. The columns may sit on the field's own table, or all on one table reached through the same `@reference` path:
>
> ```graphql
> input CustomerAddressInput {
>     district: String! @field(name: "district") @reference(path: [{key: "customer_address_id_fkey"}])
>     cityId:   Int!    @field(name: "city_id")  @reference(path: [{key: "customer_address_id_fkey"}])
> }
> input CustomerAddressFilter { addresses: [CustomerAddressInput] }
> ```
>
> The list then becomes one `EXISTS` over the path, matching a customer when some address it reaches equals some element: `(district, city_id) IN (...)` on the joined `address` row. One reached row must match the whole element, so on a path that reaches many rows (a film's cast, say) an element's fields cannot be satisfied by two different rows, as separate `@reference` fields on a non-list input can. Element fields on different paths, or mixing the field's own columns with a path, are not accepted. Neither are `@nodeId`, list-typed fields and `@condition` inside a list-of-input filter. The build names the field at fault (see the diagnostics glossary). To filter on anything else, put `@condition(override: true)` on the list field: your method receives the whole list (as a `List<Map<String, Object>>`) and owns the predicate.

In `diagnostics-glossary.adoc` § An element field a list-of-input filter cannot compare, the cause names the new refusal ("its column is reached through a different @reference path than element field '<first>'"), and the `@nodeId`/`@reference` clause becomes `@nodeId` alone.

## Other solutions we've considered

- **Join every path in one `EXISTS`.** This would serve mixed and multi-path elements by joining each distinct path into one subquery, with own-table cells compared on the outer row. It needs a rule for which reached rows must coincide across paths, and a fan-out on one path multiplies the reach of the others. No consumer needs it, and the rejection leaves room to add it later.
- **One `EXISTS` per cell, combined per element.** That is an `OR` over elements of an `AND` of per-cell `EXISTS`. It matches what the same fields mean on a non-list input, but it gives up the row-`IN`, and on a fan-out path it lets two reached rows satisfy one element, which the `(PENELOPE, WAHLBERG)` case shows is the wrong answer for a list compared as rows.
- **Carry the cells' table on `ElementRows`.** The model would then state the terminal table twice, once on the reach and once on the read, with an agreement check to keep them in step. It would also leave the two scalar `JooqConvert` reads to decide the same question again for themselves.
- **Derive the table in the renderer.** `buildGlueMethod` could map each row-reading binding back to its term's reach. That makes a second decision beside the one `termExpr` already makes per term, through a reverse lookup, and still leaves the scalar reads uncovered. Deciding it in `ConditionCommands`, where the reach is narrowed, puts it on the binding every read consumes.
- **Make the join a record of its own inside `Hop`.** Reach equality would then be plain record equality, with the alias outside it by construction. It is the cleaner end state, but it reshapes a record every path consumer reads, for one comparison. `sameJoinAs` plus its coverage test gets the same guarantee at this item's size.
- **Pass the terminal hop alias to the helper.** The alias locals are declared after the binding locals, so this would move every reach-bearing glue method's alias declarations above its bindings. That changes their emitted order for no semantic gain, since the helper reads only data types.
- **Admit `@nodeId` cells.** This needs a per-element node-id decode inside the row helper, with its own mismatch policy. No consumer asks for it.

## Reviewer findings

### Spec → Ready, signed off (session_01HyaqwVLfx3ZEXsVknznmCR, 2026-10-17)

Both questions answered; no blocking findings. Two non-blocking notes for the implementer:

- `referenceElementField_isRejected` builds through `assertRejected`, which adds an own-table `filmId` cell after the authored field. Its fixture is therefore a reached cell followed by an own-table cell, which under this plan stays rejected (located at `PairInput.filmId`, the second cell), not the one-cell positive case the Tests section says it becomes. The one-cell reached case needs its own fixture; the existing one can serve as the mixed-level negative.
- After the change a reached `ColumnTerm` states its terminal table twice: as the last hop of `reach()` and as `binding().reachedTable()`. Both are set in the same `termOf` arm, so drift is unlikely, but a compact-constructor agreement check on `ColumnTerm` would make it unrepresentable at little cost.
