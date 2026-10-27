---
id: R1105
title: "A multitable ORDER BY slot built from a generator column renders the generator with no table and throws"
status: In Review
bucket: bug
priority: 3
theme: codegen-correctness
depends-on: []
created: 2026-10-27
last-updated: 2026-10-27
---

# A multitable ORDER BY slot built from a generator column renders the generator with no table and throws

## Goal

A root list or connection over a multitable interface or union can be sorted on a client-side computed column without crashing. A *client-side computed column* is a jOOQ column whose value jOOQ builds as a SQL expression from the row's other columns, configured with a `<generator>` on a `forcedType`; it is a commercial jOOQ feature, and consumers on the commercial distribution use it for values that live in another table. A *multitable* field is one whose implementing types sit on different tables, so graphitron answers it with one `UNION ALL` query over every participant table. Today such a field compiles, then throws `NullPointerException` inside jOOQ on every request that sorts on the computed column. Reported against 10.0.0-RC40 on [GitHub issue #523](https://github.com/sikt-no/graphitron/issues/523#issuecomment-6033141282).

The contrast, both connections sorted the same way through the same `@orderBy`:

```graphql
# Works today: last_name is a stored column on customer and staff.
# (graphitron-sakila-example schema.graphqls, AddressOccupant = Customer | Staff)
occupantsOrderedConnection(orderBy: OccupantOrderBy @orderBy,
        first: Int, after: String, last: Int, before: String): [AddressOccupant!]!
    @asConnection(defaultFirstValue: 100)
    @defaultOrder(fields: [{name: "last_name"}])

# Throws today: VISNINGSNAVN is a client-side computed column on feide_bruker and personsubjekt.
# (the reporter's schema)
personbrukere(filter: PersonbrukereFilterInput,
        orderBy: PersonbrukereOrderByInput @orderBy): [Personbruker]
    @asConnection(defaultFirstValue: 100)
```

When this lands the second field returns its rows in `VISNINGSNAVN` order and pages through them like the first. Nothing changes for fields sorted on stored columns, converter-backed ones included.

## Cause

To sort across tables, every `UNION ALL` branch projects each sort column under a shared alias (`__ord0__`, `__ord1__`, ...; these are the *order slots*), and the outer query sorts and seeks on the aliases:

```sql
select * from (
  select ..., feide_bruker.visningsnavn as __ord0__ from feide_bruker
  union all
  select ..., personsubjekt.visningsnavn as __ord0__ from personsubjekt
) as u order by __ord0__
```

The generated ordering helper declares each slot as a free-standing field typed by the participant column's own `DataType`:

```java
Field<?> slot0 = DSL.field(DSL.name("__ord0__"), Tables.FEIDE_BRUKER.VISNINGSNAVN.getDataType());
```

The column's own type is deliberate: a converter on the column rides the cursor decode and the seek binds the database type, which `converterPlacesConnection` depends on (`org_code` is a `bigint` domain exposed as `String` through `OrgCodeStringConverter`). But for a computed column that `DataType` also carries the generator. `DSL.field(Name, DataType)` builds a `TableField` whose `getTable()` is null, so the slot is a table field with no table and a generator on its type. The branch projections are fine, since each one renders the generator against its own table, and the derived table's own fields (`u.__ord0__`) carry no generator. The slot is not: commercial jOOQ resolves it in the outer `ORDER BY`, finds the generator, and runs it with no owning table, so the consumer's lambda dereferences a null `GeneratorContext.table()`. The seek predicate on a later page references the same slot.

Checked against jOOQ 3.20.11, the version the root pom pins. `DataType.computed()` is exactly `generatedAlwaysAsGenerator() != null`, and `computedOnClient()` is `computed()` with a `CLIENT` generation location. `DataType.generatedAlwaysAs(Generator)` returns a copy that keeps every other override, so passing `null` drops the generator and keeps the converter and binding. A scratch program confirmed both on the open-source build: the stripped copy reports `computedOnClient() == false` and still converts `bigint` to `String`, and on a plain column it is an equivalent copy. The open-source build stores the generator but never runs it, which is why nothing in our build reproduces the crash (see Tests).

## Implementation

`MultiTablePolymorphicEmitter.typedFieldDeclaration` is the one method that declares the table-less typed fields over the derived table. It serves both the order slots (`buildOrderingHelper`, via `slotLocal`/`slotAlias`) and the key fields (`declareKeyFields`, via `keyLocal`/`keyAlias`). Make it emit the type through a generated helper that drops the generator:

```java
Field<?> slot0 = DSL.field(DSL.name("__ord0__"), withoutGenerator(Tables.FEIDE_BRUKER.VISNINGSNAVN.getDataType()));

/** The column's type without a client-side generator, which jOOQ would run with no owning table. */
private static <T> DataType<T> withoutGenerator(DataType<T> type) {
    return type.generatedAlwaysAs((Generator<?, ?, T>) null);
}
```

- **The helper.** A private static method on the generated fetcher class, built in `MultiTablePolymorphicEmitter` and added once per class, emitted iff some field on the class reaches `typedFieldDeclaration`. Three emitters reach it: `buildOrderingHelper` (from `emitMethods` and `emitRootConnectionMethods`), and `buildRootConnectionFetcher` and `buildBatchedConnectionRowsMethod` through `declareKeyFields`. As built, the gate is not a predicate mirroring that routing (the `parentKeyCellValue` shape the plan named): a field-shape predicate in `TypeFetcherGenerator` names model leaves in `case` patterns, which `CommandSeamRatchetTest.leafDispatchSitesInGenerators` forbids from rising. Instead `typedFieldDeclaration` takes the call expression from `TypeFetcherEmissionContext.withoutGeneratorCall`, which records a `WITHOUT_GENERATOR` helper kind the way `graphitronContextCall` records `GRAPHITRON_CONTEXT`, and class assembly drains it. The helper is then emitted exactly when a declaration calls it, with no routing to keep in step. The cast inside it picks the `Generator` overload out of the three `generatedAlwaysAs` overloads (value, `Field`, `Generator`) without naming a type. An inline cast at each call site would have to spell the column's Java type, and `ColumnRef.columnClass` holds the raw class name, so a converter to a parameterized user type (`java.util.List<String>`) would emit a cast that does not compile; a raw cast would add an unchecked warning to consumer code. The helper's name also tells a consumer reading the generated source why the call is there.
- **Every column, not only computed ones.** On a plain column the call returns an equivalent copy. Stripping only computed columns would mean capturing each column's generator status from the jOOQ catalog into the model, a new fact for one call site, to avoid a no-op.
- **Javadoc.** Update `typedFieldDeclaration` and `declareKeyFields` to say the declared type is the column's `DataType` without its generator, and why.

The key fields get the helper because they share the method, not because they are exposed: their typing columns are participant primary-key columns, which name stored columns.

**Not in scope.** The other `getDataType()` sites in the generators are a different shape or out of reach here:

- The `DSL.val(<value>, <DataType>)` sites are bind values. No report implicates them, and how the commercial runtime treats a bind typed with a generator cannot be checked from this build.
- The `parentInput.field(<name>, <DataType>)` lookups in `MultiTablePolymorphicEmitter.parentInputSlotPredicate`, `parentInputKeyPredicate`, `BatchedRowsFragments`, `ValuesJoinRowBuilder` and `ServiceRowsFragments` read foreign-key and primary-key columns, which are stored.
- `RoutineCallEmitter.argExpression`, the `ParamSource.SourceColumn` over a `PreviousNodeRef.ParentInputField` arm, is the exception. It looks up any `$source.<column>` a routine argument maps, so a computed column can reach it, typed with its generator on a field of the `parentInput` table. That is close to this bug's shape but not the same: the field belongs to a table, not to none, and nobody has reported it. It stays out of this item because it needs its own routine fixture to test. Once this item's fixture shows the helper works, file a Backlog item to route that lookup through `withoutGenerator` too, with a routine argument mapped from the fixture's computed column.

## Tests

No test in our build can reproduce the crash itself: computed columns are a commercial jOOQ feature, open-source jOOQ codegen does not emit `<generator>` (the codegen XSD says "available in the commercial distribution only"), and the open-source runtime does not run a generator it is handed. The open-source runtime does keep the generator on the `DataType` and report `computedOnClient()`, and that flag is what commercial jOOQ acts on. So the execution test asserts the condition the crash needs, not the crash.

- **Fixture.** Two new tables in `graphitron-sakila-db/src/main/resources/init.sql`, each with a serial key and a stored `varchar` sort column under the same name, seeded so the sort order interleaves the two tables and differs from key order; a union over two `@table` types for them in `graphitron-sakila-example`'s `schema.graphqls`, with an `@asConnection` root field ordered by the sort column through `@defaultOrder` and an `@orderBy` argument. In the fixture catalog the sort column's `DataType` carries a client-side generator (`generationLocation(CLIENT)`, `generationOption(VIRTUAL)`). That takes three pieces, because open-source jOOQ neither keeps a configured generator nor writes one:
  - *Designation*, in `NodeIdFixtureGenerator`. A `CLIENT_GENERATORS` map beside its `METADATA` names the two sort columns and their generator expression. The planned route, a generator-only `<forcedType>` in `graphitron-sakila-db/pom.xml`, does not survive configuration on the open-source build: `AbstractDatabase` removes any forced type carrying a commercial flag (`<generator>` among them) when the edition is not commercial, logging the "commercial only" warning, so no column ever matches it. An override of `generateTable` therefore sets the generator on each designated column's resolved `DataTypeDefinition` (`DefaultDataTypeDefinition.generator`, the call a matching forced type makes). The resolved type is cached per output file, so it is the instance the table class's `createField(...)` call reads.
  - *The generator itself*, `DisplayNameGenerator` in `graphitron-fixtures-codegen` beside `OrgCodeStringConverter`, implementing `Generator<Record, Table<Record>, String>` with the reporter's shape (`family_name || ', ' || given_name` read off `ctx.table()`), so it would dereference the missing table exactly as the reported one does if it ran. A named class, because a lambda passed to `generatedAlwaysAs` does not compile. The module is already a compile dependency of `graphitron-sakila-db`.
  - *Emission*, in `NodeIdFixtureGenerator`'s override of `JavaGenerator.getJavaTypeReference(Database, DataTypeDefinition, JavaWriter)`: when `getGenerator()` is non-null, append `.generatedAlwaysAs(<generator>).generationLocation(QOM.GenerationLocation.CLIENT).generationOption(QOM.GenerationOption.VIRTUAL)` to the super result. The location is spelled out because a `DataType` given only a generator reports `computedOnClient() == false`.

  Check the result against the generated `TableImpl`, not just the override: `Tables.<T>.<COL>.getDataType().computedOnClient()` must be true for the two sort columns, since that is the `DataType` the emitted slot declaration reads. `fixture_displayNameColumnsCarryAClientSideGenerator` asserts it, so the listener tests cannot pass because the fixture stopped carrying the generator. Because the open-source runtime ignores the generator, the column reads and sorts as the stored value, so the rows are checkable. Use new tables, not `customer`/`staff`, so no existing test reads a column whose `DataType` changed; nothing in the tree reads `computed()` or `computedOnClient()`, so marking the new columns has no other effect.
- **Execution tier, in `MultiTableOrderingExecutionTest`.** Register a `VisitListener` on the test's own `DSLContext` (through `DefaultVisitListenerProvider`) whose `visitStart` records every rendered `TableField` with a null `getTable()` whose `DataType` reports `computedOnClient()`. That predicate is the reported failure's precondition stated in jOOQ's terms: a generator reached from a field with no owning table. A scratch program against jOOQ 3.20.11 confirmed it is exact on this shape. Over a `UNION ALL` derived table ordered and seeked on a slot typed with a client generator, it records the slot twice (the seek and the `ORDER BY`), and nothing else: the branch columns have a table, the branch aliases and the derived table's fields carry no generator. With the generator stripped it records nothing. Use the render-time listener, not a walk of the query model: the model traversal API (`Traversers`) is not in the open-source jar. The tests: the first page in sort order, a descending `orderBy`, and a second page after the first page's end cursor (so the seek runs too), each asserting the rows and that the listener recorded nothing. Run once against an emitter whose helper returned the type unchanged, the listener recorded `["__ord0__"]` on the first-page and descending requests (the `ORDER BY`) and `["__ord0__", "__ord0__"]` on the second page (the seek and the `ORDER BY`), with the rows correct in all three.
- **Regression.** `converterPlacesConnection`'s existing paging tests stay green, which shows the stripped type still carries the converter.
- **Pipeline tier.** Nothing new: asserting the emitted `generatedAlwaysAs` text would be a code-string body match, which the test-tier guide rules out, and the execution test covers the behaviour.

Acceptance outside our build: the reporter confirms on the next RC that `personbrukere` sorts on `VISNINGSNAVN`, since only the commercial runtime can show the crash gone. Ask on issue #523 when the fix ships.

## Other solutions we've considered

- **Type the slot by its Java class** (`DSL.field(name, String.class)`), the reporter's first suggestion. It drops the converter and binding, so `converterPlacesConnection` would decode its cursor as `String` and bind the seek as `varchar` against a `bigint` domain, which is the operator error the converter fixtures exist to catch.
- **Rebuild from the base type** (`getDataType().getSQLDataType().asConvertedDataType(getDataType().getBinding())`), the reporter's second. It also works in the scratch program, but it re-assembles converter and binding by hand to remove one property that `generatedAlwaysAs(null)` removes directly, and it drops the other overrides along the way.
- **Sort the derived table by untyped name** (`DSL.field(DSL.name("__ord0__"))`). The `ORDER BY` would render, but the seek and the cursor need the slot typed.

## Reviewer findings

### Round 1 (2026-10-27, Spec -> Ready, reviewer session 01FY5UvtzU2Aa8xNAsnypLnT)

Verdict: withhold. One blocking finding on question two, in the Tests fixture. The goal reads on
its own (a multitable root sorted on a client-side computed column stops throwing and sorts like a
stored column, with converter-backed sorts unchanged), and the fix is sound and fits the tree:
`typedFieldDeclaration` really is the single declaration point for both slots and key fields, and
the jOOQ facts the Cause section rests on all reproduce against 3.20.11. The problem is the fixture
step, which names a codegen hook that table columns never go through.

**Finding 1 (question two: would I hand this to an implementer as-is). `JavaGenerator.getTypeReference`
is not on the table-column path, so overriding it cannot mark the fixture columns.**

In jooq-codegen 3.20.11, table columns get their type through
`getJavaTypeReference(Database, DataTypeDefinition, JavaWriter)`, which calls
`getJavaTypeReference0`, which calls the *private* `getTypeReference0`. The protected
`getTypeReference(...)` that the spec names is a thin wrapper over that same private method, and
nothing inside `JavaGenerator` calls it. An override would never run. Its parameter list also has
no column identity, so it could not say which columns are "the designated columns" anyway.

A working route exists, and it changes what the implementer builds, so it is the author's choice to
make:

- `getJavaTypeReference(Database, DataTypeDefinition, JavaWriter)` is protected and on the path,
  and the `DataTypeDefinition` it receives exposes `getGenerator()`, `getGenerationOption()` and
  `getGenerationLocation()`.
- Open-source jooq-meta keeps a `<generator>` from a `<forcedType>`:
  `DefaultDataTypeDefinition.generator(String)` logs the "commercial only" notice and stores the
  value anyway. So the columns can be designated in `graphitron-sakila-db/pom.xml` by a forcedType
  (that pom already carries `<forcedTypes>`), and the override can append the
  `generatedAlwaysAs(...)`/`generationLocation(CLIENT)` text when `getGenerator()` is non-null. No
  per-column list in `NodeIdFixtureGenerator` is needed.

What would satisfy it: name the hook that table columns actually reach, say how the columns are
designated, and fix the claims that "nothing in the tree uses it yet" and "the hook to try first" so
they match the chosen hook. If the author prefers another mechanism, check that the type text it
writes ends up in the `createField(...)` call of the generated `TableImpl`, because that is the
`DataType` that `Tables.<T>.<COL>.getDataType()` returns.

> Author response: took the route the finding sketched. The Tests fixture bullet now marks the
> columns with a generator-only `<forcedType>` and has `NodeIdFixtureGenerator` override
> `getJavaTypeReference`, and says why `getTypeReference` is not the hook. Two more facts came up
> while checking that route against 3.20.11, and the bullet now covers both. First, the generator
> has to be a named class in `graphitron-fixtures-codegen`, because a lambda passed to
> `generatedAlwaysAs` does not compile. Second, the emitted text sets
> `generationLocation(CLIENT)` explicitly, because a `DataType` given only a generator reports
> `computedOnClient() == false`. The bullet closes with a check on the generated `TableImpl`.

Non-blocking: the helper gate in `TypeFetcherGenerator` has to cover all three ways a field reaches
`typedFieldDeclaration`: `buildOrderingHelper` (from `emitMethods` and
`emitRootConnectionMethods`), `buildRootConnectionFetcher` and `buildBatchedConnectionRowsMethod`
(both through `declareKeyFields`). Otherwise the generated class references a helper it does not
declare, which fails compilation in `graphitron-sakila-example` rather than going unnoticed, so the
existing build catches a gap.

> Author response: the Implementation "The helper" bullet now names the three emitters the gate
> covers and says that a gap fails compilation.
