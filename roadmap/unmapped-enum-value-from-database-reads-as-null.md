---
id: R1068
title: "A database value outside an enum's mapping reads as null instead of a per-row serialization error"
status: Spec
bucket: bug
priority: 3
theme: codegen-correctness
depends-on: []
created: 2026-10-19
last-updated: 2026-10-20
---

# A database value outside an enum's mapping reads as null instead of a per-row serialization error

## Goal

A GraphQL enum read from a text column (one whose values are mapped to database strings with
`@field(name:)`, or default to their own names) resolves a row whose column holds a value outside the
enum to `null`, as Graphitron 9 did. Today each such row adds a serialization error to the response
that echoes the raw database value to the client, outside Graphitron's error redaction. A value that
the enum does name reads exactly as it does today.

The case that surfaced it is a production field in a consumer schema:

```graphql
enum Maalform {
  NB @field(name: "BOKMÅL")
  NN @field(name: "NYNORSK")
  EN @field(name: "ENGELSK")
}
```

`maalform: Maalform` reads the column `SPRAKKODE_MALFORM`, which also holds `ARABISK`, `DANSK`,
`NOB`, `NORSK` and `SAMISK`. Take a row holding `ARABISK`. The query
`personProfilerGittPersonlopenumre(eierOrganisasjonskode: "1234", personlopenumre: [...]) { personlopenummer maalform }`
answers today with

```json
{ "data": { "personProfilerGittPersonlopenumre": [{ "personlopenummer": …, "maalform": null }] },
  "errors": [{ "message": "Can't serialize value (/personProfilerGittPersonlopenumre[0]/maalform) : Invalid input for enum 'Maalform'. Unknown value 'ARABISK'" }] }
```

and after this lands with the same `data` and no `errors` entry. A row holding `BOKMÅL` answers
`"maalform": "NB"` before and after. On a non-null field (`maalform: Maalform!`) the `null` is a
non-null violation that nulls the parent, with graphql-java's message, which carries the path but
not the database value.

A second production field shows the same thing: `Emne.emnetype`, whose column also holds
`OBLIGKURS`, `EKS+ØV` and `ØVING`.

## Why it happens

`EnumTypeGenerator.buildValueDefinitionMethod` gives each `GraphQLEnumValueDefinition` its runtime
value: the `@field(name:)` string, else the SDL name. The output read hands graphql-java whatever
the column holds, unchanged (`FetcherEmitter.bindRaw`, the `ColumnBackedField` arm, emits
`return ((Record) source).get(Tables.FILM.TEXT_RATING);` for a sakila field over `film.text_rating`). graphql-java's
`GraphQLEnumType.serialize` matches a `String` only by equality with a runtime value and throws
`CoercingSerializeException` otherwise. `ExecutionStrategy` turns that into a `SerializationError`
itself. The error never passes through a data fetcher, so neither the generated per-fetcher
`ErrorRouter.redact` nor any `DataFetcherExceptionHandler` sees it.

Three ways to stop the error at graphql-java's end are closed:

* `GraphQLEnumType` has a private constructor, so a tolerant enum coercing cannot be subclassed.
* A custom `ExecutionStrategy` belongs to the consumer.
* Wrapping data fetchers in an instrumentation only works where the consumer builds `GraphQL`
  through Graphitron's wiring.

So the value has to be right before graphql-java sees it, and the place for that is the read.

## Design

The mapping happens at the generated read, through the output-side value axis that already exists.
`CallSiteCompaction` is that axis: "how to write a typed Java value back into a wire-format value".
It is carried by `ChildField.ColumnBackedField`, `ChildField.ColumnBackedReferenceField` and
`ChildField.RecordReadField`. Today it has two arms: `Direct` (the value is the field's value) and
`NodeIdEncodeKeys` (the read passes through a generated `encode<Type>` helper). The interface's
first line still says "at a SELECT-side projection". Its carriers already use it for the read's
wire direction (see `RecordReadField`'s javadoc), and that line is restated as "how the read's value
becomes the wire value" when the third arm lands. Enum and `@nodeId` fields exclude each other by
return type, so the arms never need to combine. The third arm,
`CallSiteCompaction.EnumKnownOrNull`, passes the read through a generated helper on the enum's own
`<Name>Type` class. The helper returns the value when the enum names it and `null` otherwise.
`NodeIdEncodeKeys` already wraps a read in a generated helper, and this arm mirrors that shape.

**The helper.** `EnumTypeGenerator` emits, on every `<Name>Type` class, the enum's runtime-value set
and one method:

```java
private static final Set<String> RUNTIME_VALUES = Set.of("BOKMÅL", "NYNORSK", "ENGELSK");

public static <T> T knownOrNull(T value, String coordinate) {
    if (value instanceof String s && !RUNTIME_VALUES.contains(s)) {
        UNMAPPED_LOG.debug("{}: database value '{}' is not a value of enum Maalform; resolving null", coordinate, s);
        return null;
    }
    return value;
}
```

`RUNTIME_VALUES` is filled from the same `et.values()` loop that writes the `enumValueDef_*`
factories, so the set and the value definitions cannot drift apart. The method is generic, so a
wrapped read keeps the static type it has today. As a result the carriers' `domainReturnType()`
overrides need no new branch. `UNMAPPED_LOG` is one fixed logger category shared by every enum
class, for example `<outputPackage>.schema.UnmappedEnumValue`, so an operator enables DEBUG once,
not per enum. The method name is one constant on `EnumTypeGenerator`, which both the generator and
the classifier read.

The method is type-tolerant on purpose. Only a `String` outside the set is nulled. Any other value
(`null`, a Java enum constant, anything else) passes through to graphql-java unchanged, so the arm
cannot change the outcome for a value graphql-java would have accepted. A `String` in the set is
exactly a `String` graphql-java's `serialize` accepts, because every runtime value the generator
writes is a `String` literal. The emitted code must compile at Java 17: `instanceof` with a binding
and `Set.of` both do. Logging uses SLF4J, which the generated `ErrorRouter` already depends on.

**Logging.** DEBUG, per occurrence, naming the field coordinate, the value and the enum. Not WARN:
the reporting consumer's unmapped values are intended (the schema deliberately exposes three of
eight languages), so WARN would log every read of a working field. DEBUG keeps the case
diagnosable without noise. The values go to the server log, not the response.

**Unconditional, not opt-in.** No consumer is served by today's behaviour: one unredacted error
per row, a value the response does not otherwise carry. Graphitron 9 nulled unconditionally
(`QueryHelper.makeEnumMap(...)`). An author who wants an out-of-set value to be an error has the
database for that (a `CHECK` constraint or a Postgres enum type), not the GraphQL boundary. No
directive is added.

**Where the arm is chosen.** At classification, in `FieldBuilder`. The arm is for database reads
only, so it is admitted on three of the carriers' construction sites:

* the `ColumnBackedField` site at the end of the plain-column arm (`FieldBuilder.java:9752` at the
  time of writing);
* the `ColumnBackedReferenceField` site in the `@reference` arm (`:9704`);
* the `RecordReadField` site on record parents (`:8588`), only for the `ValueLocator.TypedColumn`
  and `ValueLocator.ByName` locators on a jOOQ-record parent.

`JavaAccessor` and `DefaultRead` reads on class-backed parents, and the `@error`-type
`RecordReadField` site (`:5014`), keep `Direct`. Their values come from developer code, not a
column, which is the same reason `ComputedField` is out of scope. The `@error` reads also go through
`ErrorFieldRead.SourceAccessor`, which reifies a method only for `NodeIdEncodeKeys`.

One predicate decides the arm at those three sites. It returns `EnumKnownOrNull` when all three of
these hold, and `Direct` otherwise:

* the field's unwrapped GraphQL type classifies as a `GraphitronType.EnumType`;
* the field is single-valued (not a list);
* the column is a text column. For a typed read (`ColumnBackedField`, `ColumnBackedReferenceField`,
  `TypedColumn`), `EnumMappingResolver.validateEnumFilter` decides this. That method already answers
  "is this column a jOOQ enum" as `NotEnum`, `Valid` or `Mismatch`, and the predicate switches on
  its result instead of loading the column class a second time. `NotEnum` over a column whose Java
  type is `String` takes the arm. `Valid` and `Mismatch` (a jOOQ-enum column) keep `Direct`, and so
  does `NotEnum` over a non-`String` column, where the wrap would provably do nothing. A `ByName`
  read has no static type, so it takes the arm, and the type-tolerant helper makes that safe.

A jOOQ-enum column's value set is known at build time, so it belongs to a build check, which R1078
owns from the same `validateEnumFilter` call.

**The arm's reference.** `EnumKnownOrNull` carries its own record: the host `ClassName` and the
method name. It is not a `HelperRef` arm. `HelperRef`'s arms exist to say what their `List<ColumnRef>`
means, and this reference has no column list, no meaningful `returnType()`, and no encoder class.
The host class comes from `GeneratedUnits.schemaShape(enumName)`. `EnumTypeGenerator` mints its own
class name through the same call, replacing today's `et.name() + "Type"`, so the classifier and the
generator cannot disagree on the name.

**Emission, and the hazard it closes.** The read sites decide today whether to wrap with a nullable
`NodeIdEncodeKeys` binding: `var encode = … instanceof NodeIdEncodeKeys e ? e : null` in
`FetcherEmitter.recordReadBinding` and `inlineSuccessReturn`, plus the same shape in `bindRaw` and
`dualShapeRecordArmRead`. A new arm that one of those sites misses would keep today's behaviour with
no error. So the plan replaces the pattern rather than extending it:

* **One exhaustive wrapper.** `encodedReadReturn`'s nullable `encode` parameter becomes a `switch`
  over `CallSiteCompaction` with no `default` (`Direct` returns the read, `NodeIdEncodeKeys` encodes
  it, `EnumKnownOrNull` wraps it in `<Name>Type.knownOrNull(<read>, "<Type>.<field>")`). Every read
  site that handles a compaction calls it: `bindRaw`'s `ColumnBackedField` arm,
  `dualShapeRecordArmRead`, `columnByAlias` for `ColumnBackedReferenceField`, `recordReadBinding`
  and `inlineSuccessReturn`. After that, adding an arm is a compile error at the one place that has
  to handle it.
* **A capability for "plain value".** `sealed interface PlainValue extends CallSiteCompaction
  permits Direct, EnumKnownOrNull`. The projection is unchanged under the new arm: the SELECT term is
  still the plain column. So the sites that use `instanceof Direct` to mean "a plain single-column
  value, not node-id encoded" test `PlainValue` instead, and no bare `instanceof
  CallSiteCompaction.Direct` remains in `graphitron/src/main`. Known sites at the time of writing:
  * `JoinedTableReprojection`, the `ColumnBackedField` and `ColumnBackedReferenceField` deferral
    checks. If one is missed, the participant is silently deferred.
  * `FetcherEmitter`, the `ColumnBackedReferenceField` arm. If it is missed, the read falls to the
    `ResultKeyAliasedField` guard, which throws.

  The `instanceof NodeIdEncodeKeys` sites keep their meaning and need no change:
  `PlanCompileGraph`, `EntityResolutionBuilder`, `ProjectionCommands`, `GraphitronSchemaValidator`,
  the arity invariants in `ChildField`'s carrier constructors, `domainReturnType()`,
  `GraphitronSchemaClassGenerator` and `ErrorTypeFetcherClassGenerator`. The implementer still
  greps `CallSiteCompaction` across `graphitron/src/main`, to confirm the list is complete.

The projection itself is unchanged, so `ProjectionCommands` and the alias machinery need no new
branch.

## Out of scope

* **Carriers without a `CallSiteCompaction` slot.** These keep today's behaviour over a text column:
  * `ComputedField` (`@externalField`): the developer supplies the value.
  * `ParticipantColumnReferenceField` (cross-table field on a table interface).
  * `PivotSlotField`.
  * The connection facet `value` field, filled by `ConnectionHelperClassGenerator.facets` and read by
    the default fetcher.

  Each would need the arm on its own carrier. If pickup finds one of them reachable with an
  enum-typed field over a text column in a consumer schema, file it as a follow-up. Do not widen
  this item.
* **List-valued enum fields** (`[Maalform]`): graphql-java serializes each element, and nulling an
  element changes the list's shape. A separate question.
* **The input direction.** An unmapped value cannot be written through an enum argument, since
  graphql-java rejects it at parse time with the SDL name, which is not a database value.
* **jOOQ-enum columns:** R1078.

## Implementation

* `graphitron/.../rewrite/model/CallSiteCompaction.java`: the `EnumKnownOrNull` arm with its
  reference record, the `PlainValue` sub-interface, and the restated first line.
* `graphitron/.../rewrite/FieldBuilder.java`: the predicate, called from the three sites above.
  It switches on `EnumMappingResolver.validateEnumFilter`.
* `graphitron/.../rewrite/generators/schema/EnumTypeGenerator.java`: `RUNTIME_VALUES`, the shared
  `UNMAPPED_LOG` category and `knownOrNull` on every `<Name>Type` class. The class name comes from
  `GeneratedUnits.schemaShape`.
* `graphitron/.../rewrite/generators/FetcherEmitter.java`: the exhaustive compaction wrapper, called
  from the read sites above.
* The `PlainValue` sites above (`JoinedTableReprojection`, `FetcherEmitter`).
* `docs/manual/how-to/map-types-to-tables.adoc`, section "Enum values: text-column and jOOQ-enum".
  Rewrite the text-column paragraph:
  * It still says the generator "emits a static `*_MAP` lookup table". That retired with the move of
    the translation into graphql-java's enum value definitions; the translation now happens at that
    boundary.
  * Add the behaviour this item delivers: a database value outside the enum reads as `null`, logged
    at DEBUG, and on a non-null field nulls the parent.
  * The section's jOOQ-enum paragraphs are R1078's.
* `graphitron-sakila-example/src/main/resources/graphql/schema.graphqls`: retype `Film.textRating`
  from `String` to `TextRating`. The manual already presents it as the text-column enum form, and no
  output test reads it today.
* `graphitron-sakila-db/src/main/resources/init.sql`: give one seeded `film` row a `text_rating`
  outside `TextRating`, for example `'UNRATED'` (the column is `varchar(10)`). Pick a row that
  `films(textRating:)`'s two filter tests (`G`, `NC_17`) do not count. Before choosing, grep the
  other readers of `TEXT_RATING`: `FilmService` in `graphitron-sakila-service`, and the `graphitron`
  pipeline tests that name the column.

## Tests

* **Pipeline tier, classification.** A `GraphitronSchemaBuilderTest` family, one row each. Each row
  pins the carrier's compaction:
  * `EnumKnownOrNull` for an enum-typed `ColumnBackedField` over a text column;
  * `EnumKnownOrNull` for an enum-typed `ColumnBackedReferenceField` over a text column;
  * `EnumKnownOrNull` for an enum-typed `RecordReadField` with a `TypedColumn` locator on a
    jOOQ-record parent;
  * `EnumKnownOrNull` for one with a `ByName` locator (the one read with no static type);
  * `EnumKnownOrNull` for one on an outcome parent, which is the `inlineSuccessReturn` path;
  * `Direct` for the same field typed `String`;
  * `Direct` for an enum over a jOOQ-enum column (`Film.rating: MpaaRating`);
  * `Direct` for an enum over a non-`String` column;
  * `Direct` for a list-valued enum field;
  * `Direct` for each excluded read: a `JavaAccessor` and a `DefaultRead` locator on a class-backed
    parent, and an `@error`-type field.
* **Pipeline tier, the audit.** A table-interface participant carrying an `EnumKnownOrNull`
  `ColumnBackedField` is not deferred by `JoinedTableReprojection`. This is the silent arm of the
  hazard.
* **Execution tier, the goal** (`graphitron-sakila-example`, `GraphQLQueryTest`):
  * `films { title textRating }` answers `null` for the seeded unmapped row, with
    `result.getErrors()` empty. This test is the acceptance evidence for the goal.
  * `NC_17` for the `'NC-17'` row and `PG` for a `'PG'` row: a mapped value and a self-named value
    are unchanged.
  * The same field through a `@reference` path, if the sakila schema has or gains an enum-typed
    scalar `@reference` over a text column.

  No assertion on the log line. The level and wording are the design's and not worth pinning.

## Roadmap entries

* R1078 (filed with this spec): the build check for enum outputs over jOOQ-enum columns.
* R231 assumes a text-mapped enum field is lowered to `String` at emit time. It looks stale: the
  sakila field is `String` because its SDL says so. The retyped `Film.textRating` above settles it.
  At Done, if the execution test sees the enum on the wire, R231 is `Discarded` as stale in the same
  commit.
