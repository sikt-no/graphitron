---
id: R837
title: "A jOOQ record with no table cannot be a @service input parameter"
status: Spec
bucket: feature
priority: 5
theme: service
depends-on: []
created: 2026-08-26
last-updated: 2026-08-26
---

# A jOOQ record with no table cannot be a @service input parameter

A `@service` method can take a jOOQ record as its parameter, and graphitron builds that record from
the SDL input type's fields. Today that works only when the record is bound to a table. A jOOQ record
with no table behind it classifies fine and then fails to generate, and the message it fails with
describes the wrong problem.

## Goal

A consumer writes a service that takes an ad-hoc jOOQ projection, and it works:

```graphql
input FilmProjectionInput @classifiedType(as: JooqRecordInputType) {
    title: String        @field(name: "title")
    releaseYear: Int     @field(name: "release_year")
}

extend type Query {
    describeProjection(in: FilmProjectionInput!): String
        @service(
            service: {
                className: "no.sikt.graphitron.rewrite.test.services.FilmProjectionService",
                method: "describeProjection"
            },
            table: "film"
        )
}
```

```java
public static String describeProjection(Record2<String, Integer> in) {
    return in.value1() + "/" + in.value2();
}
```

```
{ describeProjection(in: {title: "ACADEMY DINOSAUR", releaseYear: 2006}) }
  → "ACADEMY DINOSAUR/2006"
```

The generated helper builds it the way jOOQ builds this shape:

```java
Record2<String, Integer> rec = dsl.newRecord(Tables.FILM.TITLE, Tables.FILM.RELEASE_YEAR);
```

That query is the acceptance test. Today the build fails before it can run, complaining that the
parameter class has no public no-arg constructor.

## The two kinds of jOOQ record

jOOQ's generator emits one record class per table, and those are `TableRecord`s: a `FilmRecord` knows
it is a row of `film`, so it can answer "which columns do I have". The other kind has no table. It is
what an ad-hoc projection produces, `dsl.select(FILM.TITLE, FILM.RELEASE_YEAR).fetchOne()` yielding a
`Record2<String, Integer>`, and it carries only the fields it was built with. Both implement
`org.jooq.Record`; only the first implements `org.jooq.TableRecord`.

Graphitron already distinguishes them. A result or input type backed by the first lands on
`GraphitronType.JooqTableRecordType` / `JooqTableRecordInputType`; one backed by the second lands on
`JooqRecordType` / `JooqRecordInputType`. Classification is correct on both axes.

## The fixture is the first defect

The only place the table-less shape is named today is the `plain-jooq-record-backing` corpus example,
and it names it with a class that could not exist in a real consumer's code. `PlainJooqRecord` is
hand-authored, abstract, and implements `org.jooq.Record` and nothing else; its consumer,
`DummyService.consumePlainJooqRecord`, is a codegen-time return-type stub whose body throws. jOOQ
never produces a class of that shape.

Graphitron exists to map GraphQL onto jOOQ, so a jOOQ fixture that is not a real jOOQ artifact tests
nothing worth testing. This one cost real design time: because the class sits in a consumer package
it passes `InputBeanResolver.looksLikeBeanCandidate` and walks into the JavaBean path, so the
recorded failure is about a missing constructor, which sent the first two readings of this item after
a constructor that was never the answer.

Every fixture this item touches is a real jOOQ artifact: real generated `Field` constants from the
test catalog, a real `Record2` produced by a real `select`, and an execution-tier service that runs.
`PlainJooqRecord` is deleted rather than repaired.

## Fields come from jOOQ, or they do not come at all

The missing piece was never instantiation. `DSLContext#newRecord` builds this shape directly, in 27
overloads: `newRecord(Field<?>...)` returning a bare `Record`, the arity-typed
`newRecord(Field<T1>, Field<T2>)` returning `Record2<T1, T2>` through `Record22`, and
`newRecord(UDT<R>)` for a UDT record. What those calls need is the **field list**, and
`InputBeanResolver`'s one jOOQ arm is gated on `JooqTableRecordInputType` because a table-bound
record produces its list from its table. A table-less record has no table to ask.

The list must still come from jOOQ. Every typed binding this generator emits takes its `DataType`
from something authoritative: a column's `getDataType()`, or a jOOQ-generated routine parameter's.
`SQLDataType` appears exactly once in the generator's main sources, in a javadoc sentence on
`RoutineCallEmitter`. There is no Java-type-to-`DataType` mapping here and this item does not add one.

**Synthesizing fields from the SDL input is therefore rejected, and it is worth recording why, since
it is the obvious-looking answer.** The SDL input type carries no jOOQ type information, and the
structure that does carry Java types, `InputRecordShape`, is built for the validator walk and answers
a different question. `TypeBuilder.resolveInputElementJavaType` resolves an SDL enum to
`String` (graphql-java delivers the value name), an unresolved custom scalar to `Object` as a
deliberate fallback, and a nested input object to the generated `<outputPackage>.inputs.<Name>`
class. Those are the right answers for validation and the wrong ones for a jOOQ field list: an enum
column would arrive as `Field<String>` against a consumer's `Record1<MpaaRating>`, and a nested input
has no `DataType` at all. It would also make flattening incoherent, because `InputRecordShape` holds
one component per *declared* field rather than one per flattened leaf.

## The design: the field names the table, the leaves name columns

On the column axis `@field(name:)` on a jOOQ-record input leaf already means "the column this field
writes", resolved by `InputBeanResolver.collectJooqBindings` through
`ctx.catalog.findColumn(table.tableName(), key)`. Only one thing is missing when the record has no
table: the `TableRef` to resolve against.

Graphitron already has an idiom for exactly this, and the `@table`-on-`INPUT_OBJECT` deprecation text
names it while explaining why that location went away: *"a bare `ID` / `Boolean` / count return
carries no table, so `@mutation(table: "film")` names it directly"*, described there as the field-level
analogue of `@service(argMapping:)` and as the replacement for the input-side `@table` it deprecates.
A `@service` parameter whose record has no table is the same problem at the sibling seat, so it takes
the same answer: a `table:` argument on `@service`, naming the table its record parameter's columns
resolve against.

```graphql
describeProjection(in: FilmProjectionInput!): String
    @service(service: {...}, table: "film")
```

Nothing else about the column axis changes. The leaves keep plain `@field(name: "release_year")`
references resolved against that table, which is what they already mean, and
`collectJooqBindings` is reused as-is with a `TableRef` from a different source. Nested directiveless
grouping inputs flatten exactly as they do today.

This is deliberately not a spelling inside the `@field` value. A qualified `@field(name: "film.title")`
would parse, since `name` is a `String!`, but it would resolve to nothing (`JooqCatalog.findColumn`
matches only a column's `javaName` or `sqlName`) and it would be the only place in the directive set
where one string carries two levels of the catalog. Every other reference in graphitron names the
table on an enclosing element and the columns relative to it, and this follows that.

The cost is that one `table:` names one table, so a projection whose columns span several tables is
not expressible. That is the right trade for now: it keeps the shape inside the model graphitron
already has, and no fixture or consumer asks for the cross-table case.

Each resolved leaf yields a `ColumnRef` on that one `TableRef`. `TableRef` carries `constantsClass`
and `javaFieldName`, `ColumnRef` carries `javaName` and `columnType`, so the emitter has both the
`Tables.FILM.TITLE` reference to pass to `newRecord` and the Java type to check the declared
parameter against.

## The declared-type gate

What `newRecord` returns must be assignable to the declared parameter type, and the resolved columns
make that decidable at classify time:

- `org.jooq.Record`: the `newRecord(Field<?>...)` overload returns exactly this. Admit, no further
  check.
- `Record1` through `Record22`: the arity must equal the resolved leaf count, and each type argument
  must equal the corresponding `ColumnRef.columnType()`. A mismatch rejects naming both sides, rather
  than emitting a call that fails in the consumer's javac.
- Anything else assignable to `Record`: reject **for this arm**, naming the two spellings that work.

That last rejection must not overclaim, because two shapes it would catch are real and are somebody
else's work. A jOOQ **UDT record** (a PostgreSQL composite type) is a generated public concrete class,
is constructible through `newRecord(UDT<R>)`, and implements `Record` without `TableRecord`, so it
classifies onto this very leaf; an **embeddable record** is in the same position. Both are already
filed as R234, which exists because they collapse onto the undifferentiated `JooqRecordInputType` and
need arms carrying their own structure. This item must not preempt that: the message says the shape
is not served by the column-list arm, never that it cannot be built.

## Implementation

**Fixtures.** Delete `PlainJooqRecord`. Retype `DummyService.consumePlainJooqRecord` to take
`org.jooq.Record2<String, Integer>` and `makePlainJooqRecord` to return the same, and update the
`plain-jooq-record-backing` corpus document so its consuming field carries `table:` and
its leaves carry `@field` column references matching those generics. `NodeIdReadEncodePipelineTest` also names `makePlainJooqRecord`
and moves with it. The `@classifiedType` verdicts on both halves are unchanged: `Record2` is
assignable to `Record` and not to `TableRecord`, `RecordBindingResolver.peelReturnElement` returns
the raw `org.jooq.Record2` for a parameterized non-container type, and `shouldBind` admits it, so
`TypeBuilder.buildPlainInputType` reaches the same leaf.

**`@service(table:)`.** Add the argument to the `@service` directive declaration in
`directives.graphqls` with documentation mirroring `@mutation(table:)`'s, and resolve it through
`ServiceCatalog`'s existing table-by-SQL-name lookup (`ctx.catalog.findTable(...).asEntry()`), so a
name that is not in the catalog or is ambiguous across schemas reuses the `TableResolution` arms and
the `BuildContext.candidateHint` suggestion shape rather than inventing diagnostics. Supplying
`table:` on a `@service` whose parameters include no table-less jOOQ record is rejected, matching how
`@mutation(table:)` is rejected on verbs that derive their target by other means.

**The arm.** Add a `JooqRecordInputType` branch to `InputBeanResolver.enrich`, sibling to the
`JooqTableRecordInputType` one and placed *ahead* of the `looksLikeBeanCandidate` gate, which
excludes everything under `org.jooq.*` and is why a parameter declared as a jOOQ interface reaches no
arm today. It produces a new `CallSiteExtraction` arm carrying the ordered
`(TableRef, ColumnRef, access path)` triples, plus the declared-type verdict above.

**The emitter.** A sibling to `JooqRecordInstantiationEmitter` emitting
`create<...>(DSLContext dsl, Map<String, Object> raw)`, which calls `dsl.newRecord(<field constants>)`
and loads each present wire value. Per-field presence semantics follow the table-bound helper: an
absent key leaves the field unset, an explicit null sets null, so the jOOQ `changed`-flag contract
reads the same on both. The `dsl` local exists at these call sites but is declared only when
`ServiceMethodCallEmitter.needsDslLocal` sees a `MappingEntry.FromDsl` or `FromSessionHandle`, so the
new extraction has to force it; `ArgCallEmitter` needs the same wiring for the child-coordinate route.

## Tests

- **Unit**: `@service(table:)` resolution (resolved, unknown table, ambiguous across schemas, present
  on a service with no table-less record parameter, absent while a table-less record parameter needs
  it) and the three declared-type gate outcomes, including that a UDT-shaped declared type rejects
  with the not-served-here message rather than an impossibility claim.
- **Pipeline**: the generated fetcher passes the helper's result to the service, and the helper's
  `newRecord` argument list is the resolved column constants in SDL declaration order. A second case
  covers a leaf flattened out of a nested grouping input, whose column resolves against the same
  table as its peers.
- **Compilation** (`graphitron-sakila-example`): the emitted `newRecord` call compiles against the
  declared `Record2<String, Integer>`. This is the tier that catches a wrong generic, and the reason
  the fixture must be a real typed assignment rather than a bare `Record`.
- **Execution**: the goal query, driven from `GraphQLQueryTest` against a new `FilmProjectionService`
  in `graphitron-sakila-service`. A second case pins presence semantics: an omitted nullable leaf
  leaves its field unset and an explicitly-null one sets null, observed from the service body the way
  `AddressRecordService` observes `touched`.
- **Corpus**: `plain-jooq-record-backing` keeps its two `@classifiedType` verdicts and gains a
  generating input half, so the `code-generation-triggers.adoc` block that currently says the pattern
  generates nothing renders emitted names instead.

## Scope

Input path only. The result axis works and is not touched beyond retyping the fixture's producer to a
real projection type.

Out of scope, deliberately: UDT and embeddable record inputs, which are R234's; any Java-type to
`SQLDataType` mapping; a projection whose columns span more than one table; and any way to *declare*
an ad-hoc projection in SDL beyond naming its table and its columns.

`@service(table:)` is new directive surface, so it is the part of this plan a reviewer should weigh
hardest. It is proposed because the alternatives are worse, not because it is free: the input type
cannot carry the table (`@table` on `INPUT_OBJECT` is deprecated, ignored, and slated for rejection),
the record class has none by definition, and the consuming field's return table answers a different
question. Naming it on the field is the shape `@mutation(table:)` already established for the same
gap.

## Retired vocabulary

- `PlainJooqRecord`, the hand-written abstract `Record` fixture class, and the prose describing the
  input half as ungeneratable.
