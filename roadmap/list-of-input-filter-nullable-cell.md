---
id: R1116
title: "A nullable element field in a list-of-input filter does not constrain when absent"
status: In Review
bucket: feature
priority: 4
depends-on: []
created: 2026-10-28
last-updated: 2026-10-28
---

# A nullable element field in a list-of-input filter does not constrain when absent

## Goal

A list-of-input filter (a filter field typed as a list of input objects, where a row matches when it equals any one element) can have a nullable element field. When an element leaves that field absent or null, the field does not constrain that element: `{arstall: 2026}` matches every term in 2026, `{arstall: 2026, betegnelse: VAR}` matches only spring 2026, and a list mixing the two matches the union. Today the build refuses a nullable element field, so a schema cannot say "any value here" per element.

The user documentation draft below states the behaviour as an author reads it; the rest of the body is the plan that reaches it.

## The case from sis

`FagpersonVedLarested.undervisningsenhetsroller(filter: { terminkoder: [FagpersonUndervisningsenhetsrollerFilterTerminkodeInput!] })` filters `UNDERVISNINGSENHETSROLLE` on `(ARSTALL, TERMINKODE)`. The element type is:

```graphql
input FagpersonUndervisningsenhetsrollerFilterTerminkodeInput {
  arstall: Int!
  betegnelse: FagpersonUndervisningsenhetsrollerFilterTerminbetegnelseInput @field(name: "TERMINKODE")
}
```

Clients expect `[{arstall: 2025}, {arstall: 2026, betegnelse: VAR}]` to mean all of 2025 plus spring 2026. Graphitron 9 intended exactly this. Its generated condition used `hasArgumentAtIndex(...) ? DSL.val(betegnelse) : TERMINKODE`, so an absent cell compared the column with itself. The intent was never exercised, because the presence check looked up `filter[i]` instead of `filter.terminkoder[i]` and so never applied `betegnelse`, even when it was sent. Since R1057 refuses a nullable element field, sis has made `betegnelse` non-null for now. It will relax the field back to nullable when this lands, which is non-breaking for clients.

## Semantics

A *cell* is one element field bound to one column: one position of the row an element is compared as. A cell is *required* when its own field and every nested input field on the way down to it from the element are non-null; graphql-java's input coercion then guarantees every element carries it. Any other cell is *optional*.

* **An element is the conjunction of the cells it carries, and the list is the disjunction over its elements.** Per element that reads `(ARSTALL = a AND TERMINKODE = b)`, or `ARSTALL = a` when `b` is left out.
* **A left-out cell matches every row, including rows whose column is NULL.** This is the reading an absent top-level filter argument already has: it adds no conjunct, so it constrains nothing. Graphitron 9's `TERMINKODE = TERMINKODE` would have excluded NULL rows, which no author would read off "leave it out to match any value"; for sis the difference is moot where `TERMINKODE` is non-null.
* **Explicit null and absent mean the same.** This is a filter, not a write; the helper reads the element as a map and `get` answers `null` for both.
* **An element that carries no cell matches every row** on the field's own table: the empty conjunction is true, as `filter: {}` already is. Only an element type with no required cell can produce one. Refusing it (at build time, or as a client error) would add a rule the top-level filter does not have.
* **On a reached list** (every cell reached through one `@reference` path, rendered as a correlated `EXISTS` over that path), the existing reading "some reached row equals the element" carries over unchanged: a reached row must equal the cells the element carries. An element carrying no cell therefore matches the rows that reach at least one row through the path. On a path every row follows (a non-null foreign key) that is every row; on a nullable foreign key or a one-to-many path it is not. This falls out of the shape rather than being special-cased, and is pinned by a test.
* **A nullable nested input inside an element** is lifted too: left out, it leaves every cell below it out, and the reading above applies to each. The cells below it are optional whatever their own nullability.
* **Unchanged:** a `null` element matches nothing (`[null]` matches no row, R1057); an absent, null or empty list applies no filter; an element type whose cells are all required renders exactly as today (`(c1, c2) IN (...)`, or `c1 IN (...)` for one cell).

## User documentation (first-client check)

Replaces the second paragraph and the opening sentence of the third in `docs/manual/reference/directives/field.adoc` § `[[list-of-input-filter]]`:

> `pairs: [{actorId: 1, filmId: 1}, {actorId: 3, filmId: 2}]` produces `(actor_id, film_id) IN ((1, 1), (3, 2))`, not two separate `IN` lists, which would also match `(1, 2)`. An element type with one field produces a plain `IN` on its column. An absent, null or empty list applies no filter; a single object in place of the list filters by that object; a `null` element matches nothing. The list may also be the argument itself (`filmActors(pairs: [FilmActorPairInput])`).
>
> An element field may be nullable. An element that leaves it out, or sets it to null, places no constraint on that column, and the element matches on the fields it does give:
>
> ```graphql
> input FilmLanguageInput {
>     languageId:         Int! @field(name: "language_id")
>     originalLanguageId: Int  @field(name: "original_language_id")
> }
> ```
>
> `[{languageId: 1}, {languageId: 2, originalLanguageId: 3}]` matches every film in language 1, whatever its original language and including films with none, plus the films in language 2 whose original language is 3. A nullable input object inside the element works the same way: left out, none of its fields constrain. An element that gives no field at all matches every row. When every element field is non-null the filter is the single row `IN` shown above.
>
> Every element field must be bound to a column. The columns may sit on the field's own table, or all on one table reached through the same `@reference` path:

and, after the `CustomerAddressInput` example's paragraph, one sentence:

> A reached row must equal the fields an element gives, so an element that gives none matches the rows that reach at least one row through the path.

The diagnostics glossary entry `[#list-of-input-filter]` drops "nullable" from the refused list, and its sample line switches to a refusal that remains (the list-typed element field: "it is itself a list, and one cell of a row holds one value"). Its *Cause* sentence "That reading holds only when every element field is one non-null value bound to a column" becomes "... one value bound to a column", and *Next step* drops "Declare the field non-null".

## Implementation

**Model.** `CallSiteExtraction.ElementRows` becomes a sealed interface with two records, so the shape is decided once, by the classifier, and every consumer forks on identity rather than re-evaluating a predicate over the cells:

* `ElementRows.Whole(List<Cell> cells)`: every cell required. Today's `ElementRows`, unchanged in meaning.
* `ElementRows.Partial(List<Cell> cells)`: at least one cell optional. Its compact constructor refuses a cell list with no optional cell; `Whole`'s refuses one with any.

`CallSiteExtraction.Cell` gains `boolean required`, the per-cell fact the helper reads to skip a null test. `ElementRows.of`, `columns()` and `disagrees` move to the sealed interface. The existing exhaustive switches that name `CallSiteExtraction.ElementRows` (`ArgCallEmitter`, `InputBeanFragments`, `NodeIdDecodeLedger`, three in `FieldBuilder`) keep matching the interface and need no new arm.

`BodyParam.ColumnPredicate` gains a fifth arm, `PartialRowIn(String name, List<ColumnRef> columns, boolean nonNull, CallSiteExtraction extraction)`: membership where each element compares only the cells it carries. Arity 1 and up (it builds no `Row<N>`, so it has no `In` twin); its compact constructor requires the extraction to be an `ElementRows.Partial` (bare or as a `NestedInputField` leaf) whose columns are its columns. `In` and `RowIn` require `Whole` when they read element rows. `RemoteColumnPredicate` wraps it unchanged for the reached shape. `MatchKind` gains `PARTIAL_MEMBERSHIP`, a distinct SQL shape (OR over elements of AND over carried cells), and its javadoc's "covers the four shapes" sentence is updated. The `ColumnPredicate` javadoc's invariant that every arm's binding local is declared at the column's own Java type is restated per arm: for `PartialRowIn` the local is the finished `Condition`, and the bind happens inside the helper, which compares `table.COL.eq(cellN)` with `cellN` declared at the column's Java type, so a column converter still runs.

**Classifier** (`FieldBuilder`). `collectCells` gains a `boolean pathRequired` (true at the element root). `addCell` drops the "it is nullable; a left-out cell would compare as null" refusal and records `required = nonNull && pathRequired`. The nullable-`NestingField` arm drops the "a left-out nested input would leave its cells null" refusal and recurses with `pathRequired = false` (and the enclosing `suppressed`, not `true`). `walkListLevel` mints `Whole` with `In`/`RowIn` exactly as today when every collected cell is required, and `Partial` with `PartialRowIn` otherwise. `MAX_ROW_DEGREE` applies to both, so flipping one field's nullability never changes whether a level fits. `bodyParamCallTypeName` answers `org.jooq.Condition` for `PartialRowIn`; `ColumnBindingLedger.columnsOf` and `ConditionCommands.termOf` gain the arm (`MatchKind.PARTIAL_MEMBERSHIP`). The element-field slots the ledger records are untouched, so the tenant fold reads what it reads today.

**Helper** (`ElementRowsHelperRegistry`). The registry key gains the extraction's identity, and a `Partial` registers a match helper beside the existing rows helper, taking the uncast wire value and the table the cells compare against, and returning the finished `Condition`, or `null` when there is nothing to filter. Statement form, per element:

```java
private static Condition terminkoderMatch(Object wire, Undervisningsenhetsrolle table) {
    if (!(wire instanceof List<?> elements) || elements.isEmpty()) {
        return null;
    }
    List<Condition> anyOf = new ArrayList<>(elements.size());
    for (Object element : elements) {
        if (!(element instanceof Map<?, ?> fields)) {
            anyOf.add(DSL.falseCondition());
            continue;
        }
        Integer cell1 = (Integer) fields.get("arstall");
        String cell2 = (String) fields.get("betegnelse");
        List<Condition> cells = new ArrayList<>(2);
        cells.add(table.ARSTALL.eq(cell1));
        if (cell2 != null) {
            cells.add(table.TERMINKODE.eq(cell2));
        }
        anyOf.add(cells.isEmpty() ? DSL.trueCondition() : DSL.and(cells));
    }
    return DSL.or(anyOf);
}
```

Cell reads reuse `readCell` (nested descent, `JooqConvert`, `EnumValueOf`) as they are; a required cell is compared unguarded. The empty-element arm must be `DSL.trueCondition()`, never `DSL.and(cells)` over an empty list: jOOQ's `noCondition()` is an identity for `or` as well as `and`, so it would silently drop the element from the disjunction and turn `[{a: 1}, {}]` into `a = 1`. The null-element arm is `DSL.falseCondition()` for the same reason a `Whole` read contributes a row of typed nulls: it keeps `[null]` a filter that matches nothing.

**Glue** (`ConditionGlueRenderer`). The comparison has to run against the hop alias inside the `EXISTS` for the reached shape, while binding locals are declared today ahead of the aliases and read off the static table (`ValueTable`). Two changes:

* `declareReachAliases` runs before the binding locals are declared. Aliases depend only on `table`, so this is a reorder; it changes the statement order of every glue method with a reach, which pipeline-tier assertions on that order absorb.
* A `Partial` binding's local is `Condition <name> = <stem>Match(<wire>, <tableExpr>)`, where `<tableExpr>` is `table` for an own-table term and the terminal hop alias of the term's own reach for a reached one. The binding knows its reached table but not which reach occurrence its term owns, so the renderer resolves the alias from the generated term that mints the binding; a `Partial` local shared by two occurrences is an `IllegalStateException`, since an authored `@condition` on the list field binds the raw list under its own extraction. `localType` answers `Condition`; `emitsUncheckedLocalCast` answers false. `columnCompare` for `PARTIAL_MEMBERSHIP` renders the local itself, so the reached shape becomes `EXISTS(... AND <local>)`, and `appendGuardedAnd` guards it as a scalar, `if (<local> != null)`, whatever `nonNull` says, since the helper returns `null` for an empty list. The guard stays outside the `EXISTS`.

## Tests

**Pipeline tier** (`ListOfInputFilterPipelineTest`):

* `nullableElementField_isRejectedAtItsOwnCoordinate` becomes `nullableElementField_classifiesAsPartialRowIn`: one required and one optional cell, `ElementRows.Partial`, `required` flags in cell order.
* `nullableReachedCell_isRejected` becomes `nullableReachedCell_classifiesAsRemotePartialRowIn`.
* New: `oneNullableCell_classifiesAsPartialRowInAtArityOne`; `nullableNestedInput_makesItsCellsOptional` (a non-null field inside a nullable nested input is optional); every existing all-non-null case still asserts `Whole` with `In`/`RowIn`.
* `assertRejected` cases for list-typed, `@nodeId`, `@condition` and unbound element fields stay as they are.

**Render** (`ConditionGlueRendererTest`): an own-table `Partial` level declares `Condition` from the match helper over `table` and guards it as `!= null`; a reached one declares the aliases first, passes the terminal hop alias to the helper, and wraps the local in the `EXISTS`. The helper body asserts `trueCondition()` for the empty element and `falseCondition()` for a non-`Map` element.

**Execution tier** (`GraphQLQueryTest`, sakila; `film.original_language_id` is nullable and seeded film 1 → 3, films 2 and 3 → 2, film 5 → 1, film 4 none; every film is in language 1). Three new fixtures in `graphitron-sakila-example`'s schema:

* `filmsByLanguagePairs(filter: {pairs: [FilmLanguageInput]})`, the sis shape (`languageId: Int!`, `originalLanguageId: Int`): `[{languageId: 1}]` returns films 1 to 5, film 4 included (pins NULL-column inclusion); `[{languageId: 1, originalLanguageId: 2}]` returns 2 and 3; `originalLanguageId: null` reads as absent; `[{languageId: 2}, {languageId: 1, originalLanguageId: 3}]` returns 1 (the union, with the partial element widening nothing); `[{languageId: 1, originalLanguageId: 3}, null]` returns 1 and `[null]` returns nothing.
* `filmsByOptionalLanguages(filter: {languages: [FilmOptionalLanguageInput]})`, every cell optional, one through a nullable nested input (`languageId: Int`, `original: FilmOriginalLanguageIdInput` with `id: Int! @field(name: "original_language_id")`): `[{}]` returns every film; `[{original: {id: 2}}]` returns 2 and 3; `[{languageId: 2, original: null}]` returns nothing; `[{original: {id: 1}}, {original: {id: 3}}]` returns 1 and 5.
* `filmsByOriginalLanguages(filter: {languages: [FilmOriginalLanguageInput]})`, reached through `film_original_language_id_fkey` (`name: String`, `languageId: Int`, both `@reference`d): `[{name: "Italian"}]` returns 2 and 3; `[{languageId: 3}, {name: "English"}]` returns 1 and 5; `[{}]` returns 1, 2, 3 and 5 but not film 4, which reaches no language (pins the reached empty-element reading).

## Implementation notes

Shipped as planned, in one commit. Deviations a reviewer should know about:

* The match helper emits `anyOf.add(DSL.and(cells))` without the empty-list ternary when the element has a required cell, since `cells` can then never be empty; the `trueCondition()` arm is emitted only for an element type with no required cell.
* `ElementRowsHelperRegistry.rowsType` became `localType(ElementRows)`, answering `Condition` for a partial read; the glue renderer's `localType` delegates to it.
* `FixtureWarningsGateTest` pins a schema line number; the three new fixtures sit above it, so the pin moved from 767 to 777.

Where the goal is demonstrated: `ListOfInputFilterPipelineTest` (classification, including the nullable nested input), `ConditionGlueRendererTest` (own-table and reached glue, the helper's `trueCondition` / `falseCondition` arms), and the eight `partialListOfInputFilter_*`, `allOptionalListOfInputFilter_*` and `reachedPartialListOfInputFilter_*` cases in `GraphQLQueryTest`.

## Retired vocabulary

The two refusal messages this item deletes, which must not survive in prose (javadoc, the manual, the glossary, test names):

* "it is nullable; a left-out cell would compare as null and the element would match no row, so declare it non-null"
* "it is nullable; a left-out nested input would leave its cells null and the element would match no row, so declare it non-null"

Also the claim, in `field.adoc` and the glossary, that every element field "must be non-null".

## Other solutions we've considered

* **Graphitron 9's self-comparison**, an absent cell comparing its column with itself inside the row (`(ARSTALL, TERMINKODE) IN ((2025, TERMINKODE), ...)`). It keeps the row-`IN` shape, but it excludes rows whose column is NULL, which contradicts "does not constrain"; it hides the constant from the planner, so the index on the column goes unused; and in the reached shape the row is built where the binding is read, against the static table rather than the hop alias inside the `EXISTS`.
* **Grouping elements by which cells they carry, one row-`IN` per group**, OR'ed. Equivalent, and cheaper SQL for long lists with few patterns, but the groups have different arities, so one binding local would hold rows of several `Row<N>` types, and the helper and glue would carry a runtime grouping step. A filter list is short in practice and PostgreSQL plans an OR of conjunctions and a row-`IN` over a short list alike; nothing measured asks for it. It can replace the match helper's body later without touching the model.
* **A fused helper called at the comparison site** over an uncast `List<?>` binding local. Fewer moving parts (no alias reorder, no binding-to-reach resolution, the list guard unchanged), but the interior carries an undecoded wire value and the decode and compare are one step at the term. The plan keeps the decode at the binding, with a typed local, at the cost of the reorder.
* **Refusing an element with no carried cell**, either at build time (requiring a required cell) or as a client error at runtime. sis was fine with either, but each adds a rule a top-level input filter does not have, and the runtime form makes a filter value an error path.

## Provenance

Requested 2026-10-28 by the sis Graphitron 10 port session. It is not a port blocker: sis has made the field non-null in the meantime.
