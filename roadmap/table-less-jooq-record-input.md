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
    title: String        @field(name: "film.title")
    releaseYear: Int     @field(name: "film.release_year")
}

extend type Query {
    describeProjection(in: FilmProjectionInput!): String
        @service(service: {
            className: "no.sikt.graphitron.rewrite.test.services.FilmProjectionService",
            method: "describeProjection"
        })
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

## The design: each leaf names a real column

On the column axis `@field(name:)` on a jOOQ-record input leaf already means "the column this field
writes", resolved by `InputBeanResolver.collectJooqBindings` through
`ctx.catalog.findColumn(table.tableName(), key)`. The table comes from the record. When the record
has none, the leaf supplies it by qualifying the name:

```graphql
title: String @field(name: "film.title")
```

Resolution is a column reference with an optional qualifier, and both halves already exist:
`JooqCatalog.findTable` accepts unqualified and schema-qualified names, and `findColumn` resolves a
column within a table. So `"film.title"` is table plus column, `"public.film.title"` is schema, table
and column, and a bare `"title"` rejects: with no record table there is nothing to resolve it
against, and guessing across the catalog would bind silently to whichever table happened to match.

This is the shape an ad-hoc projection actually has. The columns need not share a table, which is the
one thing this arm can express that no `TableRecord` can:

```graphql
input FilmLanguageInput @classifiedType(as: JooqRecordInputType) {
    title: String    @field(name: "film.title")
    language: String @field(name: "language.name")
}
```

Each resolved leaf yields a `(TableRef, ColumnRef)` pair. `TableRef` carries `constantsClass` and
`javaFieldName`, `ColumnRef` carries `javaName` and `columnType`, so the emitter has both the
`Tables.FILM.TITLE` reference to pass to `newRecord` and the Java type to check the declared
parameter against. Nested directiveless grouping inputs flatten as they do on the column axis: the
walk descends and keeps appending pairs in declaration order, and the record's field order is that
order.

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
`plain-jooq-record-backing` example in `ClassifiedCorpus` so its leaves carry qualified `@field`
references matching those generics. `NodeIdReadEncodePipelineTest` also names `makePlainJooqRecord`
and moves with it. The `@classifiedType` verdicts on both halves are unchanged: `Record2` is
assignable to `Record` and not to `TableRecord`, `RecordBindingResolver.peelReturnElement` returns
the raw `org.jooq.Record2` for a parameterized non-container type, and `shouldBind` admits it, so
`TypeBuilder.buildPlainInputType` reaches the same leaf.

**Qualified column resolution.** Extend the binding-key read used by `collectJooqBindings` to parse a
one-, two-, or three-part column reference and resolve it through `JooqCatalog.findTable` plus
`findColumn`. Unqualified rejects when there is no record table; ambiguity and not-in-catalog reuse
the existing `TableResolution` arms and the `BuildContext.candidateHint` suggestion shape.

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

- **Unit**: qualified-reference resolution (two-part, three-part, unqualified rejects, unknown table,
  unknown column, ambiguous unqualified schema) and the three declared-type gate outcomes, including
  that a UDT-shaped declared type rejects with the not-served-here message rather than an
  impossibility claim.
- **Pipeline**: the generated fetcher passes the helper's result to the service, and the helper's
  `newRecord` argument list is the resolved column constants in SDL declaration order. A second case
  covers the cross-table projection, whose field list spans two `Tables` constants.
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
`SQLDataType` mapping; and any way to *declare* an ad-hoc projection in SDL beyond naming the columns
its fields bind to.

## Retired vocabulary

- `PlainJooqRecord`, the hand-written abstract `Record` fixture class, and the prose describing the
  input half as ungeneratable.
