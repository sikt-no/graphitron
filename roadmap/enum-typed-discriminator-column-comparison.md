---
id: R750
title: "@discriminate over an enum-typed discriminator column emits an uncast varchar comparison Postgres rejects"
status: Ready
bucket: bug
priority: 6
theme: codegen-correctness
depends-on: []
created: 2026-08-20
last-updated: 2026-08-20
---

# @discriminate over an enum-typed discriminator column emits an uncast varchar comparison Postgres rejects

> Support Postgres-enum discriminator columns by typing the bind values, not the reference:
> every `@discriminator(value:)` literal in a discriminator comparison binds as
> `DSL.val("<value>", <tableLocal>.<COL>.getDataType())`, the generator's established
> typed-bind idiom, so the value reaches Postgres through the column's registered
> converter as an enum-typed bind. jOOQ renders that bind as
> `cast(? as "<schema>"."<enum_type>")` on an enum column, which is exactly the operand
> Postgres accepts, and as a plain `?` on a varchar column, so varchar families are
> untouched. The qualified-name field reference, the routing projection and the
> `TypeResolver` read-back all stay as they are. Two build-time guards make the mechanism
> total: an unresolvable
> `@discriminate(on:)` column becomes a classify-time rejection instead of today's silent
> raw-string fallback, and when the column's value domain is closed (a jOOQ-generated
> enum), each `@discriminator(value:)` is validated against the enum's literal set. The
> model carries the resolved `ColumnRef` end to end instead of re-collapsing it to a bare
> SQL name.

## Reproduction

Found while building a fixture for R749, on `e60c176`. Declaring an interface over the
existing `film` table with its `rating` column (Postgres type `mpaa_rating`) as the
discriminator:

```graphql
interface FilmKind @table(name: "film") @discriminate(on: "rating") {
    filmId: Int!    @field(name: "film_id")
    title:  String! @field(name: "title")
}

type GFilm implements FilmKind @table(name: "film") @discriminator(value: "G") {
    filmId: Int!    @field(name: "film_id")
    title:  String! @field(name: "title")
}
```

generates and compiles clean, and then fails on every query:

```
org.jooq.exception.DataAccessException: SQL [select "film"."rating" as "__discriminator__", ...
  from "public"."film" where "film"."rating" in (?, ?) order by "public"."film"."film_id" asc];
ERROR: operator does not exist: mpaa_rating = character varying
  Hint: No operator matches the given name and argument types. You might need to add explicit type casts.
```

The client sees only `An error occurred. Reference: <uuid>` (the error router redacts it,
correctly, since it is an unmatched exception).

## Where it comes from

The discriminator is referenced through an untyped field over the qualified column name,
`DSL.field(<tableLocal>.getQualifiedName().append(DSL.name("<col>")), Object.class)`, so
the `@discriminator(value:)` strings bind with no type information and Postgres finds no
`<enum> = character varying` operator. There are exactly four emission sites, all in the
`render` package, and one read-back site:

* `DiscriminatedTableFragments.discriminatorFilter`: the `IN (?, ?)` known-values
  restriction. **Binds; fails.**
* `DiscriminatedTableFragments.joinedDetailJoinChain`: the joined-detail `LEFT JOIN`'s
  ON-clause discriminator equality. **Binds; fails.**
* `PathFragments.parentColumnEquals`: the cross-table participant subselect's
  `<discriminator> = ?` gate, minted as `SelectTerm.ScalarSubselect.ParentColumnEquals`
  at `LauncherCommands.crossTableTerms`. **Binds; fails.** The filed reproduction (a bare
  two-field interface) never reached this site; an interface with a cross-table
  `@reference` participant field fails here too, independently.
* `DiscriminatedTableFragments.fieldsList`: the `__discriminator__` routing projection.
  Projection only, no bind, **not broken**.
* `GraphitronSchemaClassGenerator`'s emitted `TypeResolver` reads the routing alias back
  with `record.get(DSL.field(DSL.name("__discriminator__")), String.class)` and switches
  on the literal strings. Pure read, **not broken**.

Every consumer path reuses this one fragment family (root launcher and its connection
twin, batched child and batched connection via `BatchedRowsFragments`, the DML follow-up
read via `ReentryRowsFragments`, the `@service` single-table arm via
`MultiTablePolymorphicEmitter.buildServiceTableInterfaceFetcher`, the unbatched child
twin via `TypeFetcherGenerator.buildTableInterfaceReprojection`). There is no duplicate
idiom; fixing the four sites fixes every path.

The classifier already resolves the column: `TypeBuilder.buildTableInterfaceType` calls
`ctx.catalog.findColumn(...)`, which yields a `JooqCatalog.ColumnEntry` carrying
`javaName`, `columnClass`, and `columnType`, and then keeps only `sqlName()`. The type is
known at classify time and discarded there. On a *failed* lookup the code silently falls
back to the raw directive string; the comment beside it promises "the validator will
report the bad column name" but no such validator check exists (the mirror comment in
`GraphitronSchemaValidator` claims the invariant is "enforced upstream in `TypeBuilder`";
neither end enforces it).

Every discriminated fixture family in the tree uses a `varchar` discriminator column
(`content_type`, `fan_kind`, `subject_kind`, `party_kind`, `signal_kind`), so no test
exercises an enum-typed one and the build is green.

## Design

### Decision: support, with typed binds

An enum-typed discriminator is supported, not rejected. It is a natural modelling choice,
and consumers who generate jOOQ from an existing schema do not control the column's type
("the database is your ally").

The mechanism types the *bind values* and leaves the *reference* alone. At the three
comparison sites, each `@discriminator(value:)` literal is emitted as

```java
DSL.val("<value>", <tableLocal>.<javaName>.getDataType())
```

which is the generator's standing typed-bind idiom (`ValuesJoinRowBuilder.cellsCode` routes
every VALUES cell through `DSL.val(value, col.getDataType())` so the value passes the
column's registered Converter; `LookupRows` builds its `DSL.row(...)` cells the same way,
and `ReentryRowsFragments` reaches the same typing through
`keysInput.field("<sqlName>", <target>.<COL>.getDataType())`). For a jOOQ-generated enum
column, `getDataType()` carries `asEnumDataType(<EnumClass>)`, so the literal converts via
the enum's `lookupLiteral` and binds enum-typed. For a plain varchar column the conversion
is the identity and the bind is byte-identical to today.

One precedent needs its shape stated precisely, because this item depends on the part it
does not cover: `ConditionGlueRenderer`'s `JooqConvert` arm spells
`DSL.val(args.get("x"), table.<COL>.getDataType()).getValue()`, unwrapping the `Param` and
passing the converted *value* into a condition method. It is precedent for the conversion,
not for a `Param` surviving as a rendered comparison operand. This item keeps the `Param`,
which is what makes the rendered-SQL question below live rather than settled.

### What the rendered SQL becomes: a cast on the enum arm

jOOQ renders a SQL `CAST` around a Postgres-enum bind. The operand is a `Param` whose
`DataType` carries the enum, so `DefaultBinding.DefaultEnumTypeBinding.sqlBind0` routes
through `Cast.renderCastIf` and emits the enum's qualified type name. Measured against this
repo's own catalog on jOOQ 3.20.11, over `film.rating` (`mpaa_rating`):

[cols="1,3"]
|===
| Operand | Rendered

| today, `.in("G", "PG")`
| `"film"."rating" in (?, ?)`

| typed, `.in(DSL.val("G", RATING.getDataType()), ...)`
| `"film"."rating" in (cast(? as "public"."mpaa_rating"), cast(? as "public"."mpaa_rating"))`

| typed, `.eq(DSL.val("G", RATING.getDataType()))`
| `"film"."rating" = cast(? as "public"."mpaa_rating")`

| typed, over a varchar column
| `"film"."title" in (?)`, byte-identical to today
|===

Two API details, measured on the same probe, that an implementer will otherwise re-derive the
hard way. The reference at the comparison sites is a `Field<Object>`, and `Field<T>.eq` offers
`eq(T)` and `eq(Field<T>)`; with `T = Object` a `Param<MpaaRating>` matches only `eq(T)`. That
overload is the one that fires, and jOOQ passes the `Param` through unchanged rather than
re-binding it, so the cast renders anyway. Do not read the apparent mismatch as a reason to
type the reference. The `IN` site takes `in(Field<?>...)` and has no such subtlety.

The cast is not incidental; it is the mechanism. `mpaa_rating = cast(? as mpaa_rating)` is
what Postgres accepts where `mpaa_rating = character varying` is the error this item kills.
The tree already pins the shape from the enum-filter path
(`ConditionSqlBaselineTest`'s `where "public"."film"."rating" in (cast(? as
"public"."mpaa_rating"))`, and the `=` form in `RootLauncherSqlBaselineTest`), so this is an
established rendering the baselines already carry, not a new one this item invents.

Two consequences the implementer must not misread. **Enum arm:** converting a fixture
family's discriminator to a Postgres enum *does* move that family's frozen baseline strings,
at every comparison site, and those edits are expected test maintenance rather than a defect
in the approach. **Varchar arm:** the control-group families' baselines must come back
byte-identical, and a diff there *is* a defect. Note also that `ValuesJoinRowBuilder`'s class
javadoc currently promises "a plain JDBC bind, no SQL `CAST`". That is true of the columns it
serves today (no VALUES cell targets an enum column) but is not a general property of
`DSL.val(v, dataType)`; if the retype makes an enum column reachable through it, narrow that
sentence in the same commit rather than leaving a promise the binding does not keep.

The qualified-name reference itself
(`DSL.field(<tableLocal>.getQualifiedName().append(DSL.name("<sqlName>")), Object.class)`)
stays: its qualification-by-construction argument (the class javadoc of
`DiscriminatedTableFragments`) is untouched by this item, and typing the reference is not
needed once the operand is typed.

### The projection stays untyped: an axis split, stated

The discriminator plays two roles on two axes. In WHERE/ON it is a SQL comparison
operand, and the operand's type is the column's; in SELECT it is a routing token whose
canonical vocabulary is the authored `@discriminator(value:)` literal set that the
generated `TypeResolver` switches on as `String`. Typing the binds while leaving the
`__discriminator__` projection and its `String.class` read-back untouched is therefore
the correct seam, not a half-measure. The implementation states this axis split in
`DiscriminatedTableFragments`'s class javadoc (which already owns the qualified-name
idiom's rationale), so a future reader does not "fix" the asymmetry.

The untyped projection read of an enum column must round-trip to the literal string for
routing; the converted execution fixtures below pin that.

### Namespace: `@discriminator(value:)` is the database literal

The directive value names what the database stores, which for an enum column is the enum
*literal* (`'PG-13'`, not the Java constant name `PG_13`). The jOOQ-generated enum's
literal is the database literal, so with typed binds both ends of the pair (the bind on
the write side of the comparison, the `TypeResolver` string switch on the read side) live
in the same namespace. A column carrying a consumer `forcedType` converter is the one
shape where `getDataType()` would interpret the literal in the user-type namespace while
the read-back still reads the raw database literal; no fixture and no known consumer has
a converter-mapped discriminator, and that divergence is documented as a non-goal below
rather than engineered around.

### Guard 1: an unresolvable `@discriminate(on:)` column rejects at classify time

Emitting `<tableLocal>.<javaName>` requires the column to exist as a generated field, so
the silent raw-string fallback in `TypeBuilder.buildTableInterfaceType` must go. On a
failed `findColumn`, the builder returns `UnclassifiedType` carrying the
`AuthorError.UnknownName` rejection minted by `Rejection.unknownColumn(summary, attempt,
candidates)` (the `AttemptKind.COLUMN` factory), with the interface's table columns from
`ctx.catalog.columnJavaNamesOf(...)` as candidates. This mirrors the unknown-table sibling
a few lines above it, which reads `ctx.unknownTableRejection(tableName)`. There is no
`ctx.unknownColumnRejection` counterpart on `BuildContext` today; call the `Rejection`
factory directly rather than adding one, since a single caller does not yet earn the
`BuildContext` helper that the table and foreign-key spaces have.
This is a rejection variant, not a diagnostic beside a surviving verdict: a
`TableInterfaceType` whose discriminator did not resolve becomes a shape the classifier
cannot produce, which is what makes the `ColumnRef` carrier slot below total with no
placeholder.

Today the fallback generates code that fails at query time with a column-does-not-exist
error, so no working consumer relies on it; the rejection is strictly better. The stale
comment pair (the `TypeBuilder` "the validator will report" promise and the
`GraphitronSchemaValidator` "enforced upstream" mirror) is corrected in the same commit,
so the invariant has exactly one enforcer and no dangling claims.

The user manual already states the invariant ("The `on` column must exist on that table.
The build fails if it does not resolve.", `docs/manual/reference/directives/discriminate.adoc`);
this guard makes the documented behavior true.

### Guard 2: closed-domain validation of `@discriminator(value:)`

This guard exists because the mechanism needs it: `DSL.val` with a literal the enum does
not know converts to null and binds NULL, which matches no rows silently. That would
trade the loud per-query failure this item kills for the silent-wrong-answer class, so
the guard is the mechanism's enforcer, not a second feature. Measured on jOOQ 3.20.11:
`DSL.val("NOPE", <enum DataType>).getValue()` is `null`, with no warning of any kind. The
converse also measures as the namespace section claims: `DSL.val("PG-13", ...)` converts to
the `PG_13` constant and binds the database literal `'PG-13'`.

When the resolved discriminator column's `columnClass` is a jOOQ-generated enum
(detected with `EnumMappingResolver`'s established reflection idiom:
`Class.forName(columnClass, false, ctx.codegenLoader())`, walk the constants' literals),
every participant's `@discriminator(value:)` must be one of the enum's literals; an
unknown value rejects with an `AuthorError` naming the column, the Postgres enum type,
the offending value, and the valid literal set. A varchar column's value domain is open
and keeps no value check; enum-ness is what closes the domain
(`EnumMappingResolver`'s `NotEnum` arm already expresses "domain open, no check owed").

`EnumMappingResolver.checkEnumConstants` declares itself the single parity home for the
SDL-value-versus-Java-constant question. Rather than walking `getEnumConstants()` a
second time, the inner comparison (given a Java enum class and a set of target names,
which miss and with what candidates) is lifted out of `checkEnumConstants` so both the
existing GraphQL-enum caller and this discriminator-literal caller project off it, with
`EnumConstantParity.ValueMismatch` as the shared carrier.

Missing `@discriminator` directives and duplicate values across participants remain out
of scope; that unrejected shape is R558's territory and this item deliberately leaves its
gate untouched.

### Model carriage: `ColumnRef` end to end

`GraphitronType.TableInterfaceType.discriminatorColumn` changes from `String` to the
resolved `ColumnRef` (`sqlName`, `javaName`, `columnClass`, `columnType`), which
`TypeBuilder` already holds as a `ColumnEntry` and currently discards. Every intermediate
carrier retypes mechanically, because each is a pure conduit with no consumer that wants
a bare string:

* `QueryField` (both the read-side row and `QueryServiceTableInterfaceField`)
* `MutationField.MutationServiceTableInterfaceField`
* `ChildField.TableInterfaceField` and `ChildField.BatchedTableInterfaceField`
* `DmlReturnExpression.DiscriminatedSingle` / `DiscriminatedList`
* `LaunchSource.DiscriminatedTable`
* `SelectTerm.ScalarSubselect.ParentColumnEquals` (gains the column as `ColumnRef`,
  keeping `value`)

Four `String discriminatorColumn` *parameter* threads carry the same fact between those
records and the render sites, and retype with them: `LauncherCommands.discriminatedReentrySource`
and `discriminatedBranches`, `TypeFetcherGenerator.buildTableInterfaceReprojection`, and
`MultiTablePolymorphicEmitter.emitServiceTableInterfaceMethods` / `buildServiceTableInterfaceFetcher`.

The terminal consumers are the four render sites, which want `sqlName` (the reference)
and `javaName` (the `getDataType()` spelling). This follows "shape the type as precisely
as the fact allows", whose stated exemplar is exactly this pair (`TableRef` for the
resolved table, `ColumnRef` for the resolved column), and it removes the second-spelling
smell: `TableRef.column`'s javadoc names itself the model-side matcher home precisely so
consumers do not collapse to a bare SQL name and re-resolve. The alternative (keep
strings, re-resolve via `table().column(...).orElseThrow()` at the plan boundary) was
considered and dropped: the re-resolution is not total over hand-built pipeline-test refs
and the throw is the defensive restatement the acceptance principle tells us to replace
with producer-side narrowing.

Reviewer note: this is not an extension of a strangler surface; no new leaf, relation, or
model column appears. An existing stringly-typed component becomes the existing typed
one.

### One mint for the qualified reference

The qualified-discriminator formula is currently spelled four times (three in
`DiscriminatedTableFragments`, one in `PathFragments`), agreeing by inspection. The
implementation mints it once, as a package-local
`discriminatorRef(tableLocal, ColumnRef)` fragment in `DiscriminatedTableFragments`, with
the three comparison sites differing only in the operand they attach and the projection's
deliberate `Object.class` divergence visible as one distinguished call beside it, rather
than as matching literals a future edit can split.

## Implementation sites

* `TypeBuilder.buildTableInterfaceType` (Guard 1; keep the `ColumnEntry`, produce the
  `ColumnRef`, reject on failed lookup; Guard 2's literal walk, next to where the
  participants' `discriminatorValue` strings are read).
* `EnumMappingResolver` (lift the constant-comparison core out of `checkEnumConstants`).
* `GraphitronSchemaValidator` (correct the stale "enforced upstream" comment).
* `GraphitronType.TableInterfaceType`, `QueryField`, `MutationField`, `ChildField`,
  `DmlReturnExpression`, `LaunchSource.DiscriminatedTable`,
  `SelectTerm.ScalarSubselect.ParentColumnEquals` (the `String` to `ColumnRef` retype),
  plus their mint sites in `FieldBuilder` and `LauncherCommands.crossTableTerms` and the four
  parameter threads listed under "Model carriage" above.
* `DiscriminatedTableFragments` (`discriminatorRef` mint; typed binds in
  `discriminatorFilter` and `joinedDetailJoinChain`; axis-split javadoc paragraph).
* `PathFragments.parentColumnEquals` (typed bind through the gate's `ColumnRef`).
* `graphitron-sakila-db/src/main/resources/init.sql` (fixture conversion below), and the
  baseline pins the conversion moves: `RootLauncherSqlBaselineTest`, `DmlSqlBaselineTest`,
  and `BatchedChildSqlBaselineTest` for the converted families' statements.
* `docs/manual/reference/directives/discriminate.adoc` and `discriminator.adoc`
  (documentation below).

## Tests

**Fixture conversion is the test strategy, and it enforces at two tiers.** Because the
typed bind renders a visible cast on an enum column (see the rendering table above), the
SQL-baseline tier is the precise enforcer: converting a family's discriminator column makes
the cast appear at exactly the comparison sites the fix touches, and nowhere else. Execution
adds what the baseline cannot see, the value actually round-tripping through the enum's
converter and the routing read-back. Rather than adding a sixth discriminated family, two
existing families' discriminator columns convert to new Postgres enum types in `init.sql`
(literal sets identical to the values already seeded):

* `content.content_type`: covers the `IN` filter, the routing projection and read-back,
  the cross-table participant gate (`FilmContent.rating` via `content_film_id_fkey` is
  the existing `ParentColumnEquals` fixture), the DML follow-up read, and the `@service`
  arm (`ServiceTableInterfaceReturnExecutionTest` and `DmlTableInterfaceReturnExecutionTest`
  both run over `Content`).
* `jti_subject.subject_kind` (re-declared on `jti_app_account` / `jti_person`, which
  convert with it): covers the joined-detail ON-clause site and the composite-shared-key
  base-qualification shape the class javadoc defends.

`party_kind`, `fan_kind` and `signal_kind` (named schema) stay varchar as the control group, so
both value-domain shapes stay exercised. The converted families' baselines gain the enum cast at
each comparison site; the control group's baselines must come back byte-identical.

The control group's *baseline* coverage is narrower than its family count suggests, and the
implementer should know which criterion is actually checkable where. `party_kind` is the only
varchar discriminator with SQL pins at all (six in `RootLauncherSqlBaselineTest`), and it is the
right one to have: its statements carry both the `IN` filter and the joined-detail
`and "party"."party_kind" = ?` ON clause, so the two rendered forms this item changes each keep
a byte-identical varchar witness. `fan_kind` and `signal_kind` appear in no test source at all;
their families are covered at the execution tier only (`GraphQLQueryTest`, `MultiSchemaQueryTest`),
so "unchanged" for them means "still answers correctly", not "renders identically".

Note also that `BatchedChildSqlBaselineTest` and `DmlSqlBaselineTest` pin `content_type`
statements exclusively, so the conversion moves every pin they have: after it, the batched-child
and DML paths keep no varchar baseline control. That is acceptable rather than merely tolerated,
because the rendering decision is the bind's, made by jOOQ's binding at a single fragment family
both paths reuse, and the root launcher retains the varchar witness for both forms. Do not
"restore" the control by leaving one of the two families varchar; the joined-detail ON site has
no other enum-capable fixture.

The two converted families put a `String`-typed SDL field on the now-enum column, in
opposite directions, and each direction resolves differently:

* **Read.** `Subject.subjectKind: String!` is a queryable projection of `jti_subject.subject_kind`
  (declared on the interface and both participants). jOOQ converts an `EnumType` read to its
  literal on a `String` read, and no classifier gate stands in the way:
  `EnumMappingResolver.validateEnumFilter`, the check that rejects "column is a jOOQ enum but
  the GraphQL type is not", is reached only from argument classification (two `FieldBuilder`
  call sites) and the condition-field path, never from a projection. So the field survives the
  conversion and needs no SDL retype. Worth knowing that the tree has no other instance of this
  shape: the one existing enum-column participant field, `FilmContent.rating`, is typed as the
  GraphQL enum `MpaaRating`, not `String`.
* **Write.** `content.content_type` carries no queryable field at all (the `Content` interface
  projects only `contentId` and `title`); its `String` field is `ContentInput.contentType`, a
  mutation *input* on the INSERT/UPSERT path. That path already binds values as
  `DSL.val(in.get("<name>"), Tables.<T>.<COL>.getDataType())`, so a `String` input converts to
  the enum and the generated code compiles unchanged. The consequence is a baseline one: the
  DML `values(...)` pins move to the cast form alongside the SELECT pins. For `content_type`,
  therefore, the routing read-back is pinned through `__discriminator__` rather than through a
  queryable field, which is the site that actually matters for the `TypeResolver`.

Neither direction is left for the implementer to discover; if either turns out otherwise in
practice, that is a finding to surface rather than a fixture to retype.

One coverage limit of this fixture choice, stated so it is a decision rather than an oversight.
Every seeded value in both converted families (`FILM`, `SHORT`, `APP`, `PERSON`) is a valid Java
identifier, so the enum literal and the Java constant name coincide and no converted fixture can
distinguish them. The namespace decision above therefore rests on the lifted core's dashed-literal
unit test and on the pre-existing `MpaaRating` filter path, not on the discriminator round trip
itself. Optional, and the implementer's call: seeding one hyphenated literal into a converted
family would pin the whole chain (bind, projection read-back, `TypeResolver` switch) against the
database literal end to end. It costs the seeded rows and the assertions that name those values.

Tier by tier:

* **Unit (render arms):** `RootLauncherRendererTest`'s `DiscriminatedTable` family gains
  assertions that the emitted comparison operands are `DSL.val(..., ....getDataType())`
  at all three sites and that the projection stays `Object.class`.
  `TypeFetcherGeneratorTest`'s carve-out string pins for the discriminator idiom update
  to the new bind shape.
* **Unit (guards):** the lifted `EnumMappingResolver` core gets direct coverage including
  a dashed literal (`MpaaRating`'s `PG-13` versus constant `PG_13`); Guard 2's rejection
  message pins column, enum type name, value, and candidates. Guard 1 pins
  `UnclassifiedType` with `AuthorError.UnknownName` candidates for an unresolvable `on:`.
* **Pipeline:** `GraphitronSchemaBuilderTest`'s three assertion sites on the
  `@discriminate(on: "kind")`-over-`film` fixture update: the fixtures name a real
  column (the unresolvable shape is no longer a classified verdict), and a rejection row
  covers the old shape. A classification row asserts `TableInterfaceType` carries the
  typed `ColumnRef` (enum `columnClass`) for an enum-discriminated interface. Its two
  `isEqualToIgnoringCase("CONTENT_TYPE")` assertions retype along with the sites below.
* **Guard 1 fixture fallout, which is not compiler-enumerated.** The silent raw-string fallback
  is load-bearing in more fixtures than the guard's own tests. Neither `film.kind` nor
  `film.FILM_TYPE` exists (no table in the fixture has a bare `kind` column), so every interface
  below classifies today *only* because the fallback keeps the unresolved name. Each becomes an
  `UnclassifiedType` under Guard 1, and unlike the retype these are behavioral failures the
  compiler cannot point at. Repoint each `on:` at a real column (`rating` or `text_rating` on
  `film`) rather than deleting the fixture:
** `ClassifiedCorpus`: three examples (`query-service-table-interface`,
   `mutation-service-table-interface`, `table-interface`) whose `@classifiedType(as:
   TableInterfaceType)` claim flips. These are the spec-by-example corpus, so they render into
   the code-generation-triggers documentation; the corpus render and drift verification is part
   of the fix, not a follow-on.
** `CorrelationKeyArmPipelineTest`: `tableInterfaceField_armProjectsTheHopSourceSideColumn` and
   its batched twin.
** `SchemaReachabilityTest`: `MediaItem` in the shared class-level SDL, which
   `everyReachableTypeIsClassified` reads.
** `GraphitronSchemaClassGeneratorTest`'s `AlphaContent @table(name: "film") @discriminate(on:
   "FILM_TYPE")` *degrades silently rather than failing*: with no `AlphaContent` resolver
   emitted, `body.indexOf(...)` yields `-1` and the `isLessThan(zebraIdx)` ordering assertion
   passes vacuously. Its own comment claims the fixture "uses real fixture tables (content,
   film) so TableInterfaceType classification succeeds", which is already only half true.
   Fix the column and keep the assertion meaningful.
* **Mechanical retype fallout.** The `String` to `ColumnRef` change breaks every existing
  assertion that compares a discriminator accessor to a bare string, and every hand-built
  fixture that passes one positionally. Beyond `GraphitronSchemaBuilderTest` above, the known
  sites are `LauncherCommandsPipelineTest` (two `isEqualToIgnoringCase` assertions, on
  `discriminatorColumn()` and on `gate().column()`),
  `BatchedDiscriminatedInterfaceChildPipelineTest` (one), and the `LaunchSource.DiscriminatedTable`
  constructions in `RootLauncherRendererTest` / `TypeFetcherGeneratorTest`. These are compile
  failures, not silent drift, so the compiler enumerates the rest; the list is here so the
  blast radius is known before the retype starts rather than discovered as build noise.
  `GraphitronSchemaClassGeneratorTest`'s `doesNotContain("\"content_type\"")` pin reads the
  emitted `TypeResolver` rather than the model, so it should survive untouched; confirm rather
  than assume.
* **Execution:** the existing discriminated suites over the converted fixtures, plus the
  baselines, as above. No new execution class is expected.

## Documentation

`discriminate.adoc`: the Constraints section notes that the column may be of any type the
catalog resolves, including a Postgres enum, and that for an enum column each
`@discriminator(value:)` must be one of the enum's literals (build-enforced).
`discriminator.adoc`: mirror sentence that `value` names the database literal (for enum
columns, the enum literal as spelled in the database, not a Java constant name).

## Acceptance

* The reproduction schema (an interface over `film` discriminating on `rating`)
  generates, compiles, and answers queries correctly, with rows routing to the right
  concrete types.
* The converted families' SQL baselines show the enum cast
  (`cast(? as "public"."<enum_type>")`) at each discriminator comparison site: the `IN`
  filter, the joined-detail ON clause, the cross-table participant gate, and the DML
  `values(...)` write of `content_type`. The routing projection stays uncast.
* `party_kind`'s baselines in `RootLauncherSqlBaselineTest` (the varchar control, covering both
  the `IN` filter and the joined-detail ON clause) pass byte-identical. A diff there is a defect
  in the approach, not test maintenance. The `fan_kind` and `signal_kind` families carry no SQL
  pins, so their control is their execution suites still answering correctly.
* An unknown `@discriminator(value:)` on an enum column fails the build with an author
  error naming the column, type, value, and literal set; an unresolvable
  `@discriminate(on:)` fails the build naming candidates.
* Full reactor build green (`mvn install -Plocal-db`).

## Settled design notes

1. *Authoring a cast rejected; jOOQ deriving one is the mechanism.* The rejected option is
   emitting a `CAST` ourselves, from the generator, on either side of the comparison: that
   would put cast text into every discriminated statement in the tree, varchar families
   included, for no gain on the ones that already work. What this item does instead is type
   the bind and let jOOQ's enum binding decide the rendering, which yields a cast on exactly
   the enum arm and plain `?` everywhere else. The distinction is *who* decides and *which
   statements pay*, not whether a cast ever appears; it does appear, and the rendering table
   above is the record of it.
2. *Reject-only rejected.* A build-time rejection of enum columns was the cheap
   alternative and is strictly better than the redacted runtime failure, but support is
   the right end state and costs little more once the binds are typed. Guard 1 and
   Guard 2 deliver the rejection quality anyway, for the cases that stay wrong.
3. *Reference stays name-resolved.* Typing the reference (`<tableLocal>.<COL>` or
   `DSL.field(name, dataType)`) is not needed for correctness once the operand is typed,
   and the fully-qualified `TableField` rendering differs from the current baseline text
   (schema segment), so it would churn every pin for no behavioral gain.

## Non-goals

* Converter-mapped (`forcedType`) discriminator columns. The literal namespace decision
  above documents the divergence; no fixture or known consumer has one. If a real
  consumer hits it, that is its own item.
* Missing or duplicate `@discriminator(value:)` participants (R558).
* Any change to the multi-table polymorphic family, which routes on an inline
  `__typename` literal and has no discriminator column.

## Related

Surfaced during the trace for R749 (`participant-projection-alias-collision`), an
unrelated defect on the same discriminated-interface family. Independent of it: this one
fails loudly (a database error on every query) rather than silently.
