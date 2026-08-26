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

A consumer writes a service that takes an ad-hoc jOOQ projection and it works:

```graphql
input FilmProjectionInput @classifiedType(as: JooqRecordInputType) {
    title: String
    releaseYear: Int
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
and it names it with a class nobody would write. `PlainJooqRecord` is hand-authored, abstract, and
implements `org.jooq.Record` and nothing else; its consumer, `DummyService.consumePlainJooqRecord`,
is a codegen-time return-type stub whose body throws. jOOQ never produces a class of that shape, and
because this one sits in a consumer package it passes `InputBeanResolver.looksLikeBeanCandidate` and
walks into the JavaBean path, which is why the recorded failure is about a missing constructor.

The fixture therefore hides the feature twice. It cannot be executed, so nothing pins what the
generated code should do; and it provokes a failure that a real consumer would never see, so the
diagnosis it invites is the wrong one.

The honest spelling is the jOOQ interface itself, `org.jooq.Record` or `Record2<String, Integer>`,
which is what a consumer holds after a `select`. That spelling classifies identically:
`RecordBindingResolver.peelReturnElement` returns the raw `org.jooq.Record2` for a parameterized
non-container type, `shouldBind` admits anything outside `java.*`, and `Record2` is assignable to
`Record` but not to `TableRecord`, so `TypeBuilder.buildPlainInputType` reaches the same
`JooqRecordInputType` leaf. Swapping the fixture costs no verdict and buys a test that runs.

It also surfaces a constraint the hand-written class could never provoke: `Record2<String, Integer>`
pins both the arity and the component types of what may be assigned to it, so the SDL input's leaves
have to agree with the declared generics or the generated call will not compile.

## Why a bean constructor is the wrong primitive

A no-arg constructor was never how a jOOQ record is built. `DSLContext#newRecord(Field...)` builds
exactly this shape, and the arity-typed overloads (`newRecord(f1, f2)` returning `Record2<T1, T2>`,
through `Record22`) build the typed forms. Instantiation is not the missing piece.

What is missing is the **field list** those calls take. `InputBeanResolver` has exactly one jOOQ arm,
gated on `JooqTableRecordInputType`, and a table-bound record produces its field list from its table.
A table-less record has no table to ask, so nothing supplies the list and the parameter falls through
to the bean path.

## Where the field list comes from

Not from a table. An earlier reading of this item proposed resolving each SDL input field to a column
the way an unbound `PojoInputType` does, against the consuming field's return table. That precedent
describes a different job.

The rule belongs to a seat where graphitron writes the query. It lives in
`FieldBuilder.resolveTableFieldComponents`, which takes the table as a required argument, classifies
the field's arguments against it, and hands them to `projectForFilter`: the input's fields become
filter predicates on the rows graphitron is about to select. A `@service` field never reaches that
path. `@service` hands the field's entire resolution to consumer code, so graphitron selects no rows
there and there is nothing for a filter to narrow.

The two seats also cannot overlap, and through a single mechanism rather than two aligned facts. On a
`@service` field the arguments correspond to the service method's parameters, and matching a
parameter is exactly the act that binds a backing class. So an input with no backing class is
unbound *because* no parameter takes it, which leaves the graphitron-generated seats as the only
place it can appear, which is where a consuming table exists in the first place.

The arm that already works answers the question directly. `JooqTableRecordInputType` takes its table
from the parameter's own class, matching `Table#getRecordType()` against the backing class
(`ServiceCatalog.resolveTableByRecordClass`, read by `TypeBuilder.buildPlainInputType`). A record's
shape comes from the record, not from the field its parameter sits on. A table-less record names no
table, so the answer is not to borrow one from the enclosing field; it is that there is none to
consult.

Borrowing the field's return table instead would be unreliable even where one exists. Nothing
requires a `@service` parameter to relate to the field's return type. The two often coincide (in
`updateFilm(in: FilmInput): Film` the return table is the table the parameter's record wants), but
that is a convention rather than a constraint, and it is not always available at all: the goal
example's field returns `String`.

That leaves one live alternative, which the item raised at filing: require the author to name the
columns explicitly. It is rejected here, but not as worthless. Naming real columns would make the
field list `newRecord(FILM.TITLE, FILM.RELEASE_YEAR)`, so the constructed record would carry each
column's `DataType` and any registered `Converter`, and would be assignable into a
`dsl.insertInto(FILM).set(rec)`. Synthesized fields carry only the SDL leaf's own type and cannot do
that. The cost is a new authored claim naming the table, since `@table` on an input is deprecated and
inert and no other directive names one for an input. That is more surface than the goal needs, and
nothing asks for the converter fidelity yet. If a consumer ever does, this is the extension point,
and it is additive: an authored table would replace the synthesized field list without changing the
arm around it.

The field list comes from the SDL input type itself. Each leaf yields
`DSL.field(DSL.name(<leaf name>), <SQLDataType for the leaf's Java type>)`, where the Java type is
the one `TypeBuilder.buildInputRecordShape` already resolved onto the input's `InputRecordShape`, and
the field name is the leaf's own name unless `@field(name:)` overrides it (the same `bindingKey` read
the column axis makes). No table is consulted, and none is needed: an ad-hoc record's fields are
whatever it was built with.

## Implementation

**Honest fixtures, first.** Retype `DummyService.consumePlainJooqRecord` to take `org.jooq.Record2<String, Integer>`
and `makePlainJooqRecord` to return the same, delete `PlainJooqRecord`, and update the
`plain-jooq-record-backing` example in `ClassifiedCorpus` so its two SDL leaves match the declared
generics. The `@classifiedType` verdicts on both halves are unchanged; the corpus example's prose
block, which currently explains why the input half cannot generate, gets rewritten once the arm
lands. `NodeIdReadEncodePipelineTest` also names `makePlainJooqRecord` and moves with it.

**The arm.** Add a `JooqRecordInputType` branch to `InputBeanResolver.enrich`, sibling to the
existing `JooqTableRecordInputType` one and placed *ahead* of the `looksLikeBeanCandidate` gate: that
gate excludes everything under `org.jooq.*`, so a parameter declared as a jOOQ interface reaches no
arm at all today. The branch walks the SDL input's leaves into a new `CallSiteExtraction` arm
(`JooqAdHocRecord`) carrying, per leaf, the wire access path, the synthesized field name, and the
resolved Java type. Nested directiveless grouping inputs flatten as they do on the column axis, since
an ad-hoc record is as flat as a table-bound one.

**The declared-type gate.** What `newRecord` returns must be assignable to the declared parameter
type, and that is decidable at classify time:

- `org.jooq.Record`: always assignable, no further check.
- `Record1` through `Record22`: the arity must equal the leaf count and each type argument must equal
  the corresponding leaf's resolved Java type. A mismatch rejects naming both sides, rather than
  emitting a `newRecord` call that fails in the consumer's javac.
- Anything else assignable to `Record`: reject. `newRecord` returns jOOQ's own implementation, and
  nothing makes that assignable to a consumer-authored subclass. This is the floor the retiring
  `PlainJooqRecord` would have landed on.

The rejection is `Rejection.structural`, not `Rejection.deferred`: once the arm exists, a declared
type it cannot serve is an author error with a stated remedy, not a feature awaiting a generator.

**The emitter.** `JooqAdHocRecordInstantiationEmitter`, sibling to `JooqRecordInstantiationEmitter`,
emitting a `create<...>(DSLContext dsl, Map<String, Object> raw)` helper that builds the field list,
calls `dsl.newRecord(fields)`, and loads each present wire value. The `dsl` local already exists at
these call sites (`TenantDslEmitter` resolves it, `ServiceMethodCallEmitter` declares it), but only
when `needsDslLocal` sees a `MappingEntry.FromDsl` or `FromSessionHandle`; the new extraction has to
force the declaration too. `ArgCallEmitter` needs the same wiring for the child-coordinate route.

Per-field presence semantics follow the table-bound helper: an absent wire key leaves the field
unset, an explicit null sets null. The jOOQ `changed`-flag contract is per field on an ad-hoc record
exactly as it is on a table record, so the two helpers should read the same way.

## Tests

- **Unit** (`InputBeanResolver`-level): the three declared-type gate outcomes. `org.jooq.Record`
  admits; a `Record2` whose generics match admits; arity and component-type mismatches reject with
  both sides named; a consumer-authored `Record` subclass rejects.
- **Pipeline**: the generated fetcher for the goal example calls the helper and passes its result to
  the service, and the helper's field list matches the SDL leaves in declaration order.
- **Compilation** (`graphitron-sakila-example`): the emitted `newRecord` call compiles against the
  declared `Record2<String, Integer>`. This is the tier that would have caught a wrong generic, and
  the reason the fixture must be a real assignment rather than a raw `Record`.
- **Execution**: the goal query, driven from `GraphQLQueryTest` against a new
  `FilmProjectionService` in `graphitron-sakila-service` and SDL in the sakila-example schema. Add a
  second case pinning the presence semantics: an omitted nullable leaf leaves its field unset, and an
  explicitly-null one sets null, observed from the service body the way `AddressRecordService`
  observes `touched`.
- **Corpus**: `plain-jooq-record-backing` keeps its two `@classifiedType` verdicts and gains a
  generating input half, so the `code-generation-triggers.adoc` block that currently says the pattern
  generates nothing renders emitted names instead.

## Scope

Input path only. The result axis works and is not touched beyond retyping the fixture's producer.
This does not add a way to *declare* an ad-hoc projection in SDL; it makes an already-classified
input shape generate.

## Retired vocabulary

- `PlainJooqRecord` (the hand-written abstract `Record` fixture class)
- `consumePlainJooqRecord` / `makePlainJooqRecord` keep their names but change their declared types;
  prose describing the input half as ungeneratable retires with them.
