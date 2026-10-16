---
id: R1057
title: "A filter field typed as a list of input objects is read as one Map, so the filter is silently dropped"
status: Spec
bucket: bug
depends-on: []
created: 2026-10-16
last-updated: 2026-10-16
---

# A filter field typed as a list of input objects is read as one Map, so the filter is silently dropped

## Goal

A filter input field typed as a list of input objects (`terminer: [TermInput!]`, whose element fields carry `@field(name:)` onto the target table's columns) narrows the query to rows matching any element. Element fields are compared together as a row, so it reads `row(ARSTALL, TERMINKODE).in(...)`, and a null or empty list applies no filter. That is how a `[String!] @field` list already becomes `.in(...)`, and what v9 did. Where graphitron cannot apply such a shape, it rejects it at build time instead. Today the filter is silently dropped and the query returns too many rows, with no diagnostic and no runtime error. That is the one wrong answer, because it leaks data the caller did not ask for.

## Context

Reported from the sis port (trunk `c28a6cd4d`). Shape, from the stable `Query.publiseringsklareEmner` over `INFOTERMIN_EMNE`:

```graphql
input PubliseringsklareEmnerFilter {
    eierInstitusjonsnummer: String! @field(name: "INSTITUSJONSNR_EIER")
    terminer: [PubliseringsklartEmneTerminInput!]
    emnekoder: [String!] @field(name: "EMNEKODE")
}
input PubliseringsklartEmneTerminInput {
    arstall: Int! @field(name:"ARSTALL")
    terminbetegnelse: String! @field(name: "TERMINKODE")
}
```

Generated in `QueryConditions.publiseringsklareEmnerCondition`:

```java
Integer arstall = (filterMap != null ? (filterMap.get("terminer") instanceof Map<?, ?> map2 ? (Integer) map2.get("arstall") : null) : null);
String terminbetegnelse = (filterMap != null ? (filterMap.get("terminer") instanceof Map<?, ?> map2 ? (String) map2.get("terminbetegnelse") : null) : null);
...
if (arstall != null) condition = condition.and(table.ARSTALL.eq(arstall));
if (terminbetegnelse != null) condition = condition.and(table.TERMINKODE.eq(terminbetegnelse));
```

graphql-java delivers `terminer` as a `List`; a single object in variables is coerced to a one-element list. So the `instanceof Map` test is always false, both locals are null, and the term filter never applies. With `terminer: {arstall: 2017, terminbetegnelse: "VÅR"}` the query returns rows from 2005, 2006, 2007 and so on.

The extraction is `ConditionGlueRenderer.nestedExtraction`, which walks `instanceof Map<?, ?>` ternaries from the outer argument down to the leaf (`WireMapChain`) with no step for a list level in between. The classifier also treats each element field as an independent scalar `.eq`, which cannot express "any element matches" even if the read were fixed. The fix therefore spans classification (a list-of-input filter field becomes a row-`IN` over its element fields) and emission. Element nullability needs a rule as well, because some sis element types are nullable (`[X]`). The rule is under Semantics below.

Scope in sis: 26 generated conditions read a list-of-input field this way. The fields are `terminer`, `terminkoder`, `gjelderFraTerminer`, `gjelderForTerminer`, `kullterminkoder`, `slutterminkoder` and `undervisningsterminkoder`, across Query, Emne, Studieprogram, PubliseringsklartEmne/StudieprogramForTermin, Campus, FagpersonVedLarested and StudentVedLarested conditions. Found via sis `ReadOnlyApprovalIT`, where 6 approvals differ.

Neighbours: R1053 (the `@service` validation pre-check ignores list wrapping, a different site), and R334 (readability of the same extraction code; it does not change behaviour).

Two more sites share the defect, and the plan covers them:

- An argument that is itself a list of plain input objects (`filter: [FilmFilter!]`) passes through `FieldBuilder.projectFilters`, which reads only `PlainInputArg.nonNull()`. Every leaf under it reads `args.get("filter") instanceof Map`, which is always false, so it is dropped the same way. This is the same shape with the list level at depth 0.
- An authored `@condition` on any field inside the element, or a condition parameter whose `argMapping` descends past the list field (`terminer.arstall`), is rewrapped by `ConditionResolver.rewrapForNested` onto a `NestedInputField` path that runs through the list level. Its parameters therefore receive `null` at runtime. A `@condition` on the list field itself is sound: its default binding is the field's own value (`ConditionResolver.nestedPath` keeps a one-segment binding at `leafPath`), so the method receives the whole `List`.

The tenant fold is not affected. Its runtime reader (`tenantSlot` and `namedTenants`, emitted by `ConnectionRuntimeClassGenerator`) already flattens through list values, and the `ColumnBindingLedger` slots that feed it keep their current shape.

## Terms

- *List level*: a position on an argument path where the GraphQL type is a list of input objects. That is either a `NestingField` with `list() == true`, or a `PlainInputArg` with `list() == true`.
- *Element*: one input object inside that list. Its *cells* are the scalar leaves reached from it, through any non-list nested input objects inside it. Each cell is bound to one column of the table the filter resolves against.
- *Rows binding*: the `List<RowN<...>>` local (or `List<T>` for a one-cell element) that the condition glue builds from the wire list, one row per element.

## Semantics

These are the rules a list level follows. Each one matches a rule `[String!] @field` already has, or the plan says where it differs.

- **Any element matches.** A list level with cells `c1..cN` contributes `DSL.row(table.C1, ..., table.CN).in(rows)`. With one cell it contributes `table.C1.in(values)`, the same term as `[String!] @field`.
- **Absent, null or empty list: no filter.** This is the existing guard in `ConditionGlueRenderer.appendGuardedAnd` (`!= null && !isEmpty()`), unchanged. One object passed where the list is declared is coerced by graphql-java to a one-element list, so it filters by that object.
- **A null element matches no row.** The element still contributes a row, and every cell in that row is `null`. A null element is the only null the helper sees: graphql-java enforces the declared non-null element fields on the wire. SQL row comparison against a null row is never true, so the element adds nothing to the match. This is what a `null` inside `[String]` already does (`col IN (..., null)`). The element is not dropped from the list: dropping it would turn `[null]` into an empty list, which the guard reads as no filter, and the query would return every row.
- **Every element field must be non-null in the SDL.** A nullable element field (`arstall: Int`) is a build error. A left-out cell would compare as `null` and the element would match nothing, which looks like a filter and quietly returns too little. The other reading, that a left-out cell constrains nothing, needs an OR of per-element ANDs rather than a row-`IN`. The first schema that needs it can ask for it; see Other solutions.
- **Each cell reads its value the way a scalar field at that position does.** That is the leaf `FieldBuilder.implicitLeaf` already picks: `Direct` cast to the column's class, `JooqConvert` for an `ID`, `EnumValueOf` for a GraphQL enum. The value is then lifted with `DSL.val(value, table.COL.getDataType())`, so a converter on the column runs. `TypeFetcherGenerator.buildBulkLookupRowIn` builds row values from a list of maps the same way.

### What is rejected at build time

A list level whose element does not fit the rules above is a build error. Each case below is something the row-`IN` cannot express, or something today's emission would silently read as `null`:

- an element field that is nullable;
- an element field that is itself a list (`[Int!]`), or a list level inside an element (a list of lists of inputs);
- an element field carrying `@nodeId` or `@reference`;
- a `@condition` (either `override` value) on any field or nested input inside the element, or a condition parameter on the list field whose `argMapping` descends past it;
- an element field with no column binding (`UnboundField`), or one owned by a `@condition(override: true)`.

The verdict depends on the use site, not on the element type's definition. The same input type is fine as a non-list nested input, or as a `@service` bean. The cause is therefore minted the way `FieldBuilder.mintCascadeVerdict` mints the cascade verdict in the same walk: a use-keyed `ValidationError` through `ctx.addDiagnostic`, at the offending element field's own coordinate and location, with the occurrence path (consuming field, argument, list field) in the message. The consuming field keeps one consequence rejection per list level in `walkRejections`. An `UnboundField` inside an element calls `mintCascadeVerdict` itself rather than writing a second message for the same fact.

The list level's implicit predicate is suppressed, with none of the cases above applying, in two situations:

- an enclosing `@condition(override: true)` at argument or field level, as for every other implicit predicate (the `enclosingOverride` flag in `walkInputFieldConditions`);
- a `@condition(override: true)` on the list field itself, which receives the whole list, the way `nestOverride` already suppresses below a non-list `NestingField`.

A `@condition` with `override: false` on the list field runs alongside the row-`IN`, as an explicit condition does on any other field. This holds at depth 0 too, where the argument's own `@condition` is the list field's.

## Implementation

**Model.**
- `CallSiteExtraction` gets a new arm, `ElementRows(List<Cell> cells)`.
  - `Cell(List<String> path, ColumnRef column, CallSiteExtraction leaf)`: `path` is the descent from the element to the cell, and is longer than one segment when the cell sits inside a non-list nested input.
  - The compact constructor requires at least one cell, non-empty cell paths, and leaves drawn from `Direct`, `JooqConvert` and `EnumValueOf`.
- The arm rides `BodyParam.RowIn` at two or more cells and `BodyParam.In` at one, wrapped in `NestedInputField(outer, pathToList, ElementRows)` when the list level is nested. When the list level is the argument itself, it is the extraction directly, the way `NodeIdDecodeKeys` sits both bare and under `NestedInputField`.
- `BodyParam.RowIn` already admits any extraction. Only the `InputField` carriers restrict multi-column extraction to `NodeIdDecodeKeys`, and they are not touched.
- The columns are stated twice, once on the predicate (`In.column`, `RowIn.columns`) and once on the cells. `In` and `RowIn` pin the agreement in their compact constructors: when the extraction is `ElementRows`, bare or as the leaf of a `NestedInputField`, the cell columns equal the predicate's columns in order. That also pins one cell to `In` and two or more to `RowIn`.
- This is the right seam rather than a new `BodyParam` arm or per-segment list flags on `NestedInputField.path` (the `ArgPath.Segment.liftsList` shape on the service rail). The operator stays on `BodyParam` and the way the value is read stays on `CallSiteExtraction`. Per-segment flags would read N cells as N independent lists, paired only by index, whereas the row grouping is the fact.
- Every exhaustive switch over `CallSiteExtraction` gains an arm: `ConditionGlueRenderer.extractionExpr`, `InputBeanFragments`, `ArgCallEmitter`, `NodeIdDecodeLedger` and `FieldBuilder`. Outside the condition glue, the arm throws "never a … binding", as `JooqRecord` and `NodeIdDecodeRecord` already do.

**Classification** (`FieldBuilder`).
- `walkInputFieldConditions`, `NestingField` arm: when `nf.list()` is set, stop recursing as if the field held one object. Instead, one private walk over the element's `InputField`s:
  - collects the element's cells;
  - emits each element field's `ColumnBindingLedger` slot through `columnBindingOf`, exactly as the loop head does today. The tenant fold keeps its slots by construction, rather than through a second walk that has to agree with this one;
  - mints the rejections above, as located diagnostics plus one consequence;
  - adds one `RowIn` or `In` body param named after the list field, unless the list level is suppressed.
  - The list field's `nonNull` slot is `effectiveNonNull && nf.nonNull()`. It covers only the list's own presence, since an element's nullability does not change the guard.
  - Columns stay on the resolving table, because a `NestingField` inherits its parent's table.
- `projectFilters`, `PlainInputArg` arm: when `pia.list()` is set, take the same path with the argument as the list level and an empty `pathToList`.
- A `@condition` inside the element, or a parameter descending past the list: reject it before calling `rewrapForNested`, so no rewrapped filter is built with a path through the list. The list field's own `@condition` is rewrapped as today.
- `columnBindingOf` and the ledger slots stay exactly as they are (see Context).

**Emission** (`ConditionGlueRenderer`, plus a new `ElementRowsHelperRegistry` in `no.sikt.graphitron.render`).
- The binding local is built by a private static helper on the conditions class, registered the way `CompositeDecodeHelperRegistry` and `ArgPathHelperRegistry` register theirs (bracketed by `collectInto`), and drained through `Hosts`. It is a registry of its own because none of the three existing ones has this key: `CompositeDecodeHelperRegistry` keys on a `HelperRef.Decode` and a mode, `ArgPathHelperRegistry` serves routines and takes no `Table`, and `InputBeanHelperRegistry` builds beans.
- The helper is in statement form, following the development principles' "statement form over expression tricks":
  - a non-`List` wire value returns `null`;
  - otherwise it builds one `DSL.row(DSL.val(cell, table.COL.getDataType()), ...)` per element, reading cells off the element `Map` with the per-leaf rule;
  - a `null` element yields a row of typed nulls. This is one branch, keyed on the element not being a `Map`, rather than a separate semantic for non-map elements, which graphql-java's coercion already excludes.
- The helper takes the aliased `Table` as a parameter, per helper locality in `docs/architecture/reference/emitter-conventions.adoc`.
- At the call site, `nestedExtraction` gets an arm for an `ElementRows` leaf. It hands the uncast `WireMapChain` descent to the list (or `args.get(name)` at depth 0) to the helper, as the decode arm hands its descent to the decode helper. `extractionExpr` gets the bare arm.
- `localType` must derive the local's type from the cells' columns: `List<Row<N><T1, ..., TN>>`, or `List<T>` at one cell. It must never derive it from `param.typeName()`. `FieldBuilder.bodyParamCallTypeName` gives `RowIn` the raw string `"org.jooq.RowN"`, which today goes unused only because the decode path bypasses `localType`. Falling through to the `rawComponent` branch would emit a raw `List<Row2>`, which fails consumer compiles under `-Xlint:rawtypes -Werror`.
- The typed row is computed by `CompositeDecodeHelperRegistry.typedRow(List<ColumnRef>)`, which is private today. Lift it beside `CatalogRefs.columnType`, where `localType` and both registries reach it, rather than having one render-side registry call into another.
- `ConditionCommands.termOf` and `columnCompare` need no change: `RowIn` already lowers to `DSL.row(...).in(local)`, and `In` to `.in(local)`.

Every consumer of `GeneratedConditionFilter` gets the fix through the same body params: the root conditions, split and lookup fetchers, and the per-participant lowering of multi-table roots (`MultiTableFilterLoweringTest`). The implementer confirms that the multi-table lowering carries the new extraction through, and adds a pipeline case there if it does not come for free.

## Tests

**Pipeline tier**: a new `ListOfInputFilterPipelineTest` in `graphitron/src/test/java/no/sikt/graphitron/rewrite/`, modelled on `NestedInputFieldEffectiveNonNullPipelineTest`, asserting on `GeneratedConditionFilter.bodyParams()`:
- Positive cases:
  - a two-cell element classifies as one `RowIn` with columns `[actor_id, film_id]` and an `ElementRows` leaf under `NestedInputField(filter, [pairs], ...)`. No `Eq` body param whose path runs through `pairs` is left;
  - a one-cell element classifies as `In`;
  - a cell inside a non-list nested input inside the element carries a two-segment cell path;
  - a list-typed argument (`pairs: [FilmActorPairInput!]` directly on the field) classifies with a bare `ElementRows`;
  - an `ID`-typed cell carries `JooqConvert`.
- Negative cases, one per rejection bullet above. Each asserts a located diagnostic at the offending element field's coordinate, carrying the occurrence path, plus the one consequence rejection on the consuming field. Assertions are on the located diagnostic, not on the consumer's message text.
- Suppression: an enclosing `@condition(override: true)` and a `@condition(override: true)` on the list field each suppress the list level without a rejection. A `@condition` with `override: false` on the list field yields both the authored filter and the row-`IN`.
- The element fields' `ColumnBindingLedger` slots are present after the change, in the same shape as before.
- The invariant, stated over every classified filter in the test schemas rather than over one fixture: no `NestedInputField` path in any body param or rewrapped condition parameter runs through a list level, except as the descent to an `ElementRows` leaf. Nothing else enforces it. The dropped `nf.list()` check is how this bug got in, and `UpdateRowsWalker` and `DeleteRowsWalker` each re-check `list()` on their own.

**Execution tier**: `graphitron-sakila-example`, over `film_actor`.
- The fixture is two-column, all `int`, and its seed rows separate a row-`IN` from two independent `IN`s. The seed pairs as `(actor_id, film_id)` are (1,1), (2,1), (1,2), (3,2), (1,3), (2,4), (3,5).
- Add to `schema.graphqls`:

```graphql
input FilmActorPairInput {
    actorId: Int! @field(name: "actor_id")
    filmId: Int! @field(name: "film_id")
}
input FilmActorPairFilter { pairs: [FilmActorPairInput] }

type Query {
    filmActorsByPairs(filter: FilmActorPairFilter): [FilmActor!]!
    filmActorsByPairList(pairs: [FilmActorPairInput]): [FilmActor!]!
}
```

- Tests in `GraphQLQueryTest`, beside `inputFieldCondition_nestedTwoLevel_pathWalksThroughNestingField`:
  - `pairs: [{actorId: 1, filmId: 1}, {actorId: 3, filmId: 2}]` returns exactly those 2 rows. Two independent `IN`s would also return (1,2).
  - a single object in place of the list returns 1 row;
  - `pairs: []`, `pairs: null` and an absent filter each return all 7 rows;
  - `[{actorId: 1, filmId: 1}, null]` returns 1 row, and `[null]` returns 0 rows;
  - the same pair query through `filmActorsByPairList` returns the same 2 rows.
- Compiling the emitted sources at release 17 covers the helper's shape. `GeneratedSourcesLintTest` covers its `var`-freedom.

The sis approvals that motivated the item (`ReadOnlyApprovalIT`, 6 differing) are the consumer-side confirmation. They are not part of this reactor's build.

## User documentation (first-client check)

The draft below goes into `docs/manual/reference/directives/field.adoc`, after the input-field paragraph of the canonical example. A `Named structural errors` entry for the rejection goes into `docs/manual/reference/diagnostics-glossary.adoc`.

> On a filter input, a field typed as a *list of input objects* matches rows equal to any one element, comparing the element's fields together:
>
> ```graphql
> input FilmActorPairInput {
>     actorId: Int! @field(name: "actor_id")
>     filmId: Int! @field(name: "film_id")
> }
> input FilmActorPairFilter { pairs: [FilmActorPairInput] }
> ```
>
> `pairs: [{actorId: 1, filmId: 1}, {actorId: 3, filmId: 2}]` produces `(actor_id, film_id) IN ((1, 1), (3, 2))`, not two separate `IN`s. An absent, null or empty list applies no filter; a `null` element matches nothing. Every field of the element type must be non-null and bound to a column; `@nodeId`, `@reference`, list-typed fields and `@condition` are not accepted inside a list-of-input filter, and the build says which field is at fault. To filter on anything else, put `@condition(override: true)` on the list field: your method receives the whole list and owns the predicate.

## Other solutions we've considered

- **A left-out cell constrains nothing.** Admit nullable element fields and emit `OR` over the elements, each an `AND` of its present cells. This is the closest match to the top-level rule that an absent filter field filters nothing. It was not chosen: it gives up the row-`IN` and the shape the bulk and composite-key rails already emit, and an element with every cell absent matches every row. The non-null rule rejects this case today, so admitting it later is purely additive.
- **Skip null elements.** Rejected under Semantics: it turns `[null]` into no filter.
- **Lift the list in `WireMapChain` and keep per-field `.eq`s.** A list read cannot feed an `.eq`, and independent `IN`s per field match pairs no element names. That is the wrong answer the Goal rules out.
- **Reject every list-of-input filter at build time.** This is honest, but it would block 26 sis conditions that v9 served, and the row-`IN` machinery is already in the tree.
