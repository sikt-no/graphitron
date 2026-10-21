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
factories, so the set and the value definitions cannot drift apart. The generator collects the
runtime values into a `LinkedHashSet` before it writes `Set.of(...)`, so each value is emitted
once. Two enum values may map to the same database string (`A @field(name: "X")` beside
`B @field(name: "X")`, or a deprecated alias of a current value). Nothing in the classifier
rejects that, and graphql-java accepts such an enum, serializing the first match. `Set.of`
throws on a duplicate element, which would fail the class at initialization, and with it the
whole schema, for an enum no field even reads from a column. The method is generic, so a
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

* the `ColumnBackedField` site at the end of the plain-column arm (`FieldBuilder.java:9753` at the
  time of writing);
* the `ColumnBackedReferenceField` site in the `@reference` arm (`:9705`);
* the `RecordReadField` site on record parents (`:8590`), only for the `ValueLocator.TypedColumn`
  and `ValueLocator.ByName` locators on a jOOQ-record parent.

`JavaAccessor` and `DefaultRead` reads on class-backed parents, and the `@error`-type
`RecordReadField` site (`:5019`), keep `Direct`. Their values come from developer code, not a
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

**Emission, and the hazard it closes.** Each read site decides today whether to encode with a
test that has no arm for anything else:

* `var encode = … instanceof NodeIdEncodeKeys e ? e : null` in `FetcherEmitter.recordReadBinding`
  and `inlineSuccessReturn`;
* `if (… instanceof NodeIdEncodeKeys enc)` in `bindRaw`'s `ColumnBackedField` arm and in
  `dualShapeRecordArmRead`;
* `instanceof CallSiteCompaction.Direct` on `bindRaw`'s `ColumnBackedReferenceField` arm.

A new arm that one of those sites misses would keep today's behaviour with no error. The sites
do not share one emission shape, though. The record reads bind one value to a local of the key
column's type and null-guard it (`encodedReadReturn`). The column reads encode N key columns
positionally with no guard. The dual-shape read is an expression its caller returns.
`columnByAlias` is shared with `TableField` and `ComputedField`. So the plan does not funnel the
sites through one wrapper. It makes each site's decision an exhaustive switch and shares only
the new arm's expression:

* **A capability for "plain value".** `sealed interface PlainValue extends CallSiteCompaction
  permits Direct, EnumKnownOrNull`. These are the arms whose projection is the plain column and
  whose read yields one value. `CallSiteCompaction` then permits `PlainValue` and
  `NodeIdEncodeKeys`.
* **One expression for the plain arms.** `FetcherEmitter.plainValueRead(CodeBlock read,
  PlainValue value, GraphitronField field)` switches over `PlainValue` with no `default`.
  `Direct` returns `read`. `EnumKnownOrNull` returns
  `<Name>Type.knownOrNull(<read>, "<Type>.<field>")`, with the coordinate from
  `field.qualifiedName()`. It is an expression, so the statement-shaped and the expression-shaped
  sites both use it.
* **An exhaustive switch at each site,** with `case NodeIdEncodeKeys`, `case PlainValue` and no
  `default`:
  * `bindRaw`'s `ColumnBackedField` arm and `dualShapeRecordArmRead`. `NodeIdEncodeKeys` keeps
    its N-column emission unchanged. `PlainValue` passes the single typed-column read through
    `plainValueRead`.
  * `encodedReadReturn`, called by `recordReadBinding` and `inlineSuccessReturn`. It takes the
    `CallSiteCompaction` instead of a nullable `NodeIdEncodeKeys`. `NodeIdEncodeKeys` keeps its
    typed-local, null-guarded form unchanged. `PlainValue` returns `plainValueRead(read, …)`.
    Two more decisions in those callers become the same two-case switch: the `ByName` read
    shape (typed `get` for an encode, untyped otherwise) and `recordReadBinding`'s `DefaultRead`
    arm.
  * `bindRaw`'s `ColumnBackedReferenceField` arm. `PlainValue` reads through `columnByAlias`,
    which gains a `PlainValue` parameter that it applies through `plainValueRead`; `TableField`
    and `ComputedField` pass `Direct`. `NodeIdEncodeKeys` falls to the `ResultKeyAliasedField`
    guard and throws, as it does today.

  After that, an arm added beside `NodeIdEncodeKeys` is a compile error at each of these sites,
  and an arm added under `PlainValue` is a compile error in `plainValueRead`. Node-id output is
  byte-for-byte unchanged on every site: its code moves under a `case` without being rewritten.
  The existing tests that cover node-id reads are the evidence, and no test changes for it.
* **Two carrier invariants,** so that combinations the classifier never produces cannot reach
  emission:
  * `RecordReadField`'s canonical constructor rejects `EnumKnownOrNull` unless the locator is
    `TypedColumn` or `ByName`, beside its existing arity check. `recordReadBinding`'s
    `DefaultRead` arm therefore throws `IllegalStateException` on `EnumKnownOrNull` as
    unreachable, the way that file already marks unreachable arms, instead of building a read
    for it.
  * `ErrorFieldRead.SourceAccessor`'s canonical constructor rejects `EnumKnownOrNull` as its
    `wire`. That keeps the `instanceof NodeIdEncodeKeys` tests in
    `GraphitronSchemaClassGenerator` and `ErrorTypeFetcherClassGenerator` sound without touching
    them.
* **The `PlainValue` sites.** The sites that use `instanceof Direct` to mean "a plain
  single-column value, not node-id encoded" test `PlainValue` instead, and no bare `instanceof
  CallSiteCompaction.Direct` remains in `graphitron/src/main`. Known sites at the time of writing:
  * `JoinedTableReprojection`, the `ColumnBackedField` and `ColumnBackedReferenceField` deferral
    checks. If one is missed, the participant is silently deferred.
  * `FetcherEmitter`, the `ColumnBackedReferenceField` arm, which the switch above already
    covers.

  The remaining `instanceof NodeIdEncodeKeys` sites keep their meaning and need no change:
  `PlanCompileGraph`, `EntityResolutionBuilder`, `ProjectionCommands`, `GraphitronSchemaValidator`,
  the arity invariants in `ChildField`'s carrier constructors, `domainReturnType()`,
  `GraphitronSchemaClassGenerator` and `ErrorTypeFetcherClassGenerator`. The implementer still
  greps `CallSiteCompaction` across `graphitron/src/main` to confirm the list is complete.

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
* `graphitron/.../rewrite/model/ChildField.java` (`RecordReadField`) and `ErrorFieldRead.java`
  (`SourceAccessor`): the two constructor invariants.
* `graphitron/.../rewrite/FieldBuilder.java`: the predicate, called from the three sites above.
  It switches on `EnumMappingResolver.validateEnumFilter`.
* `graphitron/.../rewrite/generators/schema/EnumTypeGenerator.java`: `RUNTIME_VALUES` (deduplicated
  at generation), the shared `UNMAPPED_LOG` category and `knownOrNull` on every `<Name>Type`
  class. The class name comes from `GeneratedUnits.schemaShape`, which returns a `UnitRef`; the
  classifier builds a `GeneratedUnits` from the output package, as the planners do.
* `graphitron/.../rewrite/generators/FetcherEmitter.java`: `plainValueRead`, the compaction-typed
  `encodedReadReturn`, `columnByAlias`'s `PlainValue` parameter, and the exhaustive switch at each
  read site above.
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
  output test reads it today. Add to `TextRating` a deprecated alias that maps to a value another
  constant already maps to, `NC17 @field(name: "NC-17") @deprecated(reason: "Use NC_17.")`, after
  `NC_17`. That is the duplicate-mapping case in the shape a consumer would write it.
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
* **Unit tier, the invariants.** `RecordReadField` rejects `EnumKnownOrNull` with a
  `JavaAccessor` and with a `DefaultRead` locator, and `SourceAccessor` rejects it as its `wire`.
* **Pipeline tier, the audit.** A table-interface participant carrying an `EnumKnownOrNull`
  `ColumnBackedField` is not deferred by `JoinedTableReprojection`. This is the silent arm of the
  hazard.
* **Execution tier, the goal** (`graphitron-sakila-example`, `GraphQLQueryTest`):
  * `films { title textRating }` answers `null` for the seeded unmapped row, with
    `result.getErrors()` empty. This test is the acceptance evidence for the goal.
  * `NC_17` for the `'NC-17'` row and `PG` for a `'PG'` row: a mapped value and a self-named value
    are unchanged. With the `NC17` alias in place, the `NC_17` assertion also pins that a
    duplicate mapping serializes to its first constant, as graphql-java does today.
  * The duplicate mapping itself needs no assertion of its own. Every execution test builds the
    sakila schema, which loads `TextRatingType`, so a `RUNTIME_VALUES` that throws on the
    duplicate fails the whole suite. Only the execution tier loads the generated class, which is
    why the case lives there and not at the pipeline tier.
  * The same field through a `@reference` path, if the sakila schema has or gains an enum-typed
    scalar `@reference` over a text column.

  No assertion on the log line. The level and wording are the design's and not worth pinning.

## Roadmap entries

* R1078 (filed with this spec): the build check for enum outputs over jOOQ-enum columns.
* R231 assumes a text-mapped enum field is lowered to `String` at emit time. It looks stale: the
  sakila field is `String` because its SDL says so. The retyped `Film.textRating` above settles it.
  At Done, if the execution test sees the enum on the wire, R231 is `Discarded` as stale in the same
  commit.

## Reviewer findings

### Round 1 (2026-10-20, Spec -> Ready, reviewer session 01J1ffEvZ1P5aKUV6yTkxuoA)

Verdict: request revisions. One finding on question two and one on question one. The goal reads
cleanly on its own: a consumer whose text column holds values outside the enum gets `null` for
those rows instead of an `errors` entry that leaks the raw database value, and every mapped value
reads as before. The diagnosis checks out against graphql-java, and putting the fix at the read is
the right call. Nearly every symbol and site the spec names exists as named. The plan extends an
axis already in the tree (`CallSiteCompaction`) rather than standing up a parallel one. Both
findings are narrow, but each one changes what the implementer builds.

1. **Question two: the "one exhaustive wrapper" does not fit the sites the spec routes through
   it.** The Emission section turns `encodedReadReturn`'s nullable parameter into a switch and
   says every read site that handles a compaction calls it, naming `bindRaw`'s
   `ColumnBackedField` arm, `dualShapeRecordArmRead`, `columnByAlias`, `recordReadBinding` and
   `inlineSuccessReturn`. Today only the last two call it, because its shape is single-key.
   `encodedReadReturn` returns a *statement*. It binds one read to a local typed by
   `encodeKeyType` (`paramSignature().get(0)`) and null-guards it:
   `return key == null ? null : encode(key);`. `bindRaw`'s `ColumnBackedField` arm and
   `dualShapeRecordArmRead` encode N columns positionally with no null guard. The dual-shape
   site also needs an *expression*, because its caller writes `return $L;`. `columnByAlias` is
   shared with `TableField` and `ComputedField` and takes no compaction. Routing the first two
   sites through the wrapper as written means either changing NodeId emission on the
   multi-column path (adding a null guard and a typed local), which is outside this item and
   would churn its code-string pins, or inventing a wrapper shape the spec does not name. To
   satisfy this finding, the spec names the mechanism that closes the hazard. One option is an
   expression-level wrapper for the single-value arms, with the N-column `NodeIdEncodeKeys`
   emission kept at its site under an exhaustive `switch`. Another is an exhaustive `switch`
   with no `default` at each site, dropping the "one place" claim. Either way, the spec says
   which, and states that NodeId output on every site is byte-for-byte unchanged.

   *Response:* Emission no longer funnels every site through one wrapper. Each site makes an
   exhaustive `NodeIdEncodeKeys` / `PlainValue` switch with no `default`. Only the new arm's
   expression is shared, as `plainValueRead`, an expression helper exhaustive over `PlainValue`.
   Node-id emission moves under its `case` without being rewritten, and the spec now says its
   output is byte-for-byte unchanged. `encodedReadReturn` takes the compaction, and
   `columnByAlias` takes a `PlainValue`. Two constructor invariants keep the classifier's
   exclusions out of emission: `RecordReadField` admits the arm only with `TypedColumn` or
   `ByName`, and `SourceAccessor` never carries it. The plan says so under Design, Emission;
   the Implementation list and a unit-tier test bullet are updated to match.

2. **Question one: `Set.of` in `RUNTIME_VALUES` turns a duplicate mapping into a class-init
   crash.** `TypeBuilder` builds each `EnumValueSpec` from `@field(name:)`, else the SDL name.
   Nothing rejects two values with the same runtime value (`A @field(name: "X")` beside
   `B @field(name: "X")`, or `A` beside `B @field(name: "A")`), and graphql-java accepts such an
   enum: serialize returns the first match. `Set.of` throws `IllegalArgumentException` on a
   duplicate element. The spec emits `RUNTIME_VALUES` on *every* `<Name>Type` class,
   unconditionally, and fills it from the `et.values()` loop. So a schema that builds today
   would fail with `ExceptionInInitializerError` the first time `type()` loads that class,
   whether or not any field reads the enum from a column. That contradicts "a value that the
   enum does name reads exactly as it does today". To satisfy this finding, the spec makes the
   emitted set duplicate-safe (dedupe at generation time, or build it in a form that tolerates
   repeats) and adds a pipeline- or compilation-tier case with a duplicate mapping. The other
   option is to reject duplicate mappings at classification, but that is a new rejection with
   its own consumer impact, and the spec would have to argue it.

   *Response:* The generator deduplicates the runtime values (`LinkedHashSet`) before it writes
   `Set.of(...)`. The Design section's helper paragraph states why. The sakila `TextRating` gains
   a deprecated alias, `NC17 @field(name: "NC-17")`, so the execution suite fails as a whole if
   the set ever throws on a duplicate. The existing `NC_17` assertion also pins first-match
   serialization. The alternative of rejecting duplicates at classification is not taken: it
   would be a new build failure for schemas that work today.

Corrected in passing: the four `FieldBuilder` line hints (`:9752`, `:9704`, `:8588`, `:5014`) had
drifted by one to five lines. They now read `:9753`, `:9705`, `:8590` and `:5019`.

### Round 2 (2026-10-21, Spec -> Ready, reviewer session 019ovaQ5mAS9dU2e825nvaip)

Verdict: request revisions, on question two. Question one passes: when this lands, a consumer
whose text column holds a value the enum does not name gets `null` for that row (and a DEBUG log
line) instead of an `errors` entry that leaks the database value, and every mapped value reads as
before. Both round 1 responses hold up. The per-site exhaustive switches fit the sites as they
are in the tree, and the `LinkedHashSet` dedup closes the `Set.of` crash. Every symbol, line hint
and file the spec names exists as named. graphql-java 25's `GraphQLEnumType.getNameByValue`
matches a `String` only by `equals` against the runtime value, so a `String` inside
`RUNTIME_VALUES` is exactly what serializes today, as the spec says. One gap remains, in a
component the spec explicitly sets aside.

1. **Question two: the new arm adds a cross-unit reference that `PlanCompileGraph` does not
   record, and the spec says that class needs no change.** Every site the arm reaches emits
   `<Name>Type.knownOrNull(...)` inside a `<Parent>Fetchers` unit, which is a reference from a
   fetchers unit to an enum's schema-shape unit. `PlanCompileGraph` is the dev loop's compile
   dependency graph, and its contract is that precise edges "mirror references the emitted
   source actually makes". `IncrementalCompileHarnessTest` leg 2 enforces that: the graph must
   be a superset of what `TypeSpecReferenceWalk` finds in the emitted units. Nothing in the
   graph today runs from a fetchers unit to a schema-shape unit. The blanket runs the other
   way, schema shape to its own fetchers. The only leaf-derived edges are the `NodeIdEncoder`
   ones in `addEncoderLeafEdges`, which test `NodeIdEncodeKeys` only. The spec lists
   `PlanCompileGraph` among the `instanceof NodeIdEncodeKeys` sites that "keep their meaning and
   need no change". The test there does keep its meaning, but the class owes a new edge. The
   build will not catch the omission: the harness corpus has no enum-typed output over a text
   column, so leg 2 never sees the reference, and the graph would silently fall out of step
   with its own invariant.

   To satisfy this finding, the spec names the edge and its class, adds `PlanCompileGraph` to
   the Implementation list, and adds a test that exercises it. There are two shapes, and the
   choice is the author's:
   * A precise leaf edge from the carrier's fetchers unit to `GeneratedUnits.schemaShape(<enum>)`
     for each `EnumKnownOrNull` leaf, beside `addEncoderLeafEdges`. This extends the class's one
     documented exception to "never re-derived from the classified model".
   * A declared-superset edge. This fits the class's stated policy, since `knownOrNull`'s
     signature is the same on every enum class and cannot move on a schema edit. The spec would
     have to say how wide the edge is, for example every fetchers unit to every enum schema shape.

   Either way, the test is an `IncrementalCompileHarnessTest` corpus extension, or a
   `PlanCompileGraphTest` case, with an enum-typed field over a text column, so that leg 2
   covers the reference.
