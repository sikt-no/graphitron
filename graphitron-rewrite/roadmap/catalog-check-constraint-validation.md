---
id: R92
title: "Surface database CHECK constraints as Jakarta validation rules"
status: Spec
bucket: architecture
priority: 8
theme: mutations-errors
depends-on: []
---

# Surface database CHECK constraints as Jakarta validation rules

> Lift PostgreSQL `CHECK` constraints out of `pg_constraint` and into client-side
> Jakarta validation, so the same predicate the database enforces also rejects bad
> input at the GraphQL boundary before it round-trips to the database. The
> classifier walks `org.jooq.Table.getChecks()` (exposed by jOOQ codegen's
> `<includeCheckConstraints>true</includeCheckConstraints>` toggle), recognizes a
> small fixed vocabulary of expression shapes, and emits a Hibernate Validator
> `ConstraintMapping` that attaches `@Pattern` / `@Min` / `@Max` / `@Size`
> programmatically to the consumer's existing jOOQ record and SDL input classes.
> The runtime path reuses R12 §5's existing `Validator.validate(...)` step and
> `ConstraintViolations.toGraphQLError`; no shadow class, no jOOQ codegen fork, no
> CDI dependency.

This item slots into R12's `ValidationHandler` channel: a violation surfaced by
this work is structurally identical to one surfaced by R12's input-bean
validation, so the wire shape, error routing, and `extensions.constraint`
population are unchanged.

---

## Motivation

Today the rewrite knows nothing about CHECK constraints. The four DML mutation
emitters (`TypeFetcherGenerator.buildMutationInsertFetcher`, ...,
`buildMutationUpsertFetcher`) hand a record to the database with whatever the
caller supplied; constraint violations come back as `SQLException`s and are
caught only by R12 §1's `SqlStateHandler("23514")`, which surfaces the
database's check-name as the wire `message: String!`. That is correct but
suboptimal:

1. *One round-trip per bad input.* The predicate that rejects the input is
   declared in the schema and visible to the catalog, but enforcement lives
   only on the database side. A typo in `rating` ("XYZ" instead of "PG-13")
   travels to the database, gets rejected, comes back as a 23514, and only
   then surfaces. The shorter loop is to evaluate the predicate at the API
   boundary.
2. *Database error messages are vendor-specific.* Postgres reports
   `new row for relation "film" violates check constraint "film_rating_check"`;
   Oracle reports `ORA-02290: check constraint (...) violated`. R12 surfaces
   the raw `getMessage()`, which leaks the constraint name and table name to
   API consumers. Client-side validation produces a stable Jakarta message
   keyed off the constraint *shape*, not the database's identifier.
3. *The schema knows the rule; the API surface should too.* The `@table`
   directive already binds a GraphQL type to a jOOQ table. The CHECK
   constraints on that table are part of the table's contract. Surfacing them
   as Jakarta validation closes the loop the directive opened.

The R12 spec mentions this gap obliquely (validation is described as
input-only) and leaves the door open: §5's wrapper-pre-execution
`Validator.validate(input)` already runs against any class the
`ValidationHandler` channel resolves; widening *what* the validator knows
about does not require changing *when* it runs.

---

## What R12 already gives us

R12 §5 has shipped the runtime machinery this item builds on. The wrapper
emits a conditional pre-execution `Validator.validate(input)` step
(`TypeFetcherGenerator.java:1207-1208`, calling `validatorPreStep` at
`:1305`) gated on the channel carrying a `ValidationHandler`. Each `jakarta.validation.ConstraintViolation` translates
to a `GraphQLError` via `<outputPackage>.schema.ConstraintViolations`
(emitted by `ConstraintViolationsClassGenerator`); the violation's
`getAnnotation().annotationType().getSimpleName()` populates
`extensions.constraint`. The `Validator` itself comes from
`GraphitronContext.getValidator(env)` (default: lazy
`Validation.buildDefaultValidatorFactory().getValidator()`).

The only piece R12 leaves out is *which* constraints the validator knows
about. Today it sees only the constraints the consumer's input bean already
declared via standard annotations. R92 widens that set without touching the
runtime contract.

---

## Design

### Pipeline

```
init.sql
   ↓ (consumer's jOOQ codegen, with <includeCheckConstraints>true>)
Tables.X.getChecks()  ← parsed Condition + name + enforced
   ↓ JooqCatalog.findCheckConstraints(table)              [parse-boundary]
   ↓ CheckRecognizer.recognize(parsed)
   ↓ CheckRecognition = Recognized | Unrecognized          [sealed]
   ↓ ColumnConstraint(ColumnRef, Recognized)               [model carrier]
   ↓ ConstraintMappingEmitter
   ↓ <outputPackage>.schema.GeneratedConstraintMapping     [emitted artifact]
        .toMapping(mapping) → mapping.type(FilmRecord.class)
                                     .field("rating").constraint(new PatternDef()...)
                                     ...
   ↓ DefaultValidatorHolder picks up the mapping at JVM start
   ↓ Validator.validate(record) | Validator.validate(input)  [R12 §5 reuses]
   ↓ ConstraintViolation → GraphQLError → payload.errors    [R12 §5 reuses]
```

Reads top-to-bottom: classify-time work above the dotted line, runtime work
below, with the model carrier (`ColumnConstraint`) as the seam. Every
classifier output is generation-ready per *Generation-thinking*.

### Sealed `CheckRecognition` taxonomy

```java
public sealed interface CheckRecognition
        permits CheckRecognition.Recognized, CheckRecognition.Unrecognized {

    sealed interface Recognized extends CheckRecognition
            permits StringOneOf, NumericRange, LengthBound, RegexMatch, NotNullCheck {

        ColumnRef column();
    }

    record StringOneOf(ColumnRef column, List<String> literals) implements Recognized {}

    record NumericRange(
        ColumnRef column,
        java.util.OptionalLong min,    // inclusive when present
        java.util.OptionalLong max     // inclusive when present
    ) implements Recognized {}

    record LengthBound(ColumnRef column, int min, int max) implements Recognized {}

    record RegexMatch(ColumnRef column, String regex) implements Recognized {}

    record NotNullCheck(ColumnRef column) implements Recognized {}

    record Unrecognized(
        String constraintName,
        String renderedExpression,    // jOOQ's renderInlined form, for error messages only;
                                      // never consumed downstream and never reaches the model
        UnrecognizedReason reason
    ) implements CheckRecognition {}

    enum UnrecognizedReason {
        UNSUPPORTED_OPERATOR,
        CROSS_COLUMN_PREDICATE,
        UNRECOGNIZED_FUNCTION,
        SUB_SELECT,
        OPERATOR_PRECEDENCE_TOO_DEEP,
        NUMERIC_VALUE_LIST          // e.g. CHECK (rating_score IN (1, 2, 3));
                                    // see "Future evolution" for the v2 lift
    }
}
```

The shape applies the *Sub-taxonomies for resolution outcomes* and *Builder-step
results are sealed, not strings or out-params* principles directly. R88's
`AccessorResolution` is the most recent precedent: a sealed `Resolved | Rejected`
with each `Resolved` arm carrying exactly its own data, dispatched on by every
downstream consumer via exhaustive `switch`.

The five `Recognized` arms cover the vocabulary the recognizer commits to in
v1. Postgres normalises CHECK expressions to canonical AST text in
`pg_constraint.consrc`, so the recognizer's surface is narrower than the
syntactic surface a hand-written CHECK offers; it's the *output* of Postgres'
parser that matters, not the input. See "Postgres normalisation" under "Open
architectural decisions" below for the integration test that pins this.

`Unrecognized` carries a structured reason rather than a raw "didn't match
anything" sentinel, so strict-mode rejection messages name the specific obstacle
("`CHECK (start_date < end_date)` references two columns; class-level
constraints are out of scope, see R92 future evolution").

### `ColumnConstraint` model carrier

```java
public record ColumnConstraint(ColumnRef column, CheckRecognition.Recognized shape) {}
```

A new collection lives on `GraphitronType.TableType` (the classified `@table`
type carrier):

```java
record TableType(
    String typeName,
    TableRef table,
    List<ColumnConstraint> columnConstraints,   // new; empty when no recognized CHECKs
    // ...existing components...
) implements GraphitronType { }
```

Per *Narrow component types*, `columnConstraints` is `List<ColumnConstraint>`,
not `List<? extends Object>` or `List<Map<String, Object>>` or any wider
interface. Empty list when the table has no CHECKs or has only unrecognized
ones (the strict-mode policy, below, decides whether the latter is even
reachable).

Per *Generation-thinking*, the `Recognized` shape holds parsed values
(`List<String> literals`, `int min`, `String regex`), not strings to be
re-parsed by the emitter. Every emitter consumer switches on the variant
identity and reads typed fields directly.

### Boundary: `JooqCatalog.findCheckConstraints`

Per *Classification belongs at the parse boundary*, raw `org.jooq` types live
behind `JooqCatalog`. One new method:

```java
public List<ParsedCheckConstraint> findCheckConstraints(Table<?> table) {
    return table.getChecks().stream()
        .map(c -> new ParsedCheckConstraint(c.getName(), c.condition(), c.enforced()))
        .toList();
}

public record ParsedCheckConstraint(
    String name,
    org.jooq.Condition condition,   // jOOQ's parsed AST, never re-rendered to text
    boolean enforced
) {}
```

`org.jooq.Condition` is permitted on the `JooqCatalog`-side of the parse
boundary by the same exemption that already permits `org.jooq.Table<?>` and
`org.jooq.ForeignKey<?,?>` there. Per *Wire-format encoding is a boundary
concern*, the SQL expression text never leaves jOOQ's runtime: the catalog
hands the recognizer a parsed `Condition`, the recognizer visits the AST, and
the model carries only the typed `Recognized` outcome. The model never holds a
SQL string.

`ParsedCheckConstraint` is a *pre-classification handoff record*, visible only
to `CheckRecognizer` and not part of the published model surface. Downstream
sites (`TypeBuilder`'s classifier wiring, the emitter, the validator) consume
only `CheckRecognition`. Future maintainers reading `ParsedCheckConstraint`
should not interpret it as a place to plumb `Condition` deeper into the
pipeline; the only legitimate consumer is the recognizer.

The recognizer is on the catalog-side of the boundary too, since it imports
`org.jooq.Condition`. That's a deliberate, narrow exemption matching the
existing four-file boundary list (`ServiceCatalog`,
`ServiceDirectiveResolver`, `BatchKeyLifterDirectiveResolver`, `FieldBuilder`):
the recognizer is a fifth member, dedicated to lifting `Condition` AST shapes
into typed `CheckRecognition` outcomes.

### `CheckRecognizer`

A standalone visitor that walks a parsed `org.jooq.Condition`. The visitor
returns `CheckRecognition`. Single sealed-result entry point per *Builder-step
results are sealed*:

```java
public final class CheckRecognizer {
    public CheckRecognition recognize(
        ParsedCheckConstraint parsed,
        TableRef table   // pre-resolved; the recognizer projects column references
                         // it finds in the AST against this table's column metadata
    );
}
```

`TableRef` is already populated when the recognizer runs (the per-`@table`
classifier resolves it before walking checks). The recognizer reads jOOQ
column references straight from the AST and looks them up against
`table.columns()`; it never builds its own `Map<String, ColumnRef>`.

Recognised shapes (each maps one parsed AST shape to one `Recognized` arm):

| AST shape (jOOQ canonical)                                   | `Recognized` arm                          |
|--------------------------------------------------------------|-------------------------------------------|
| `col = ANY (ARRAY['lit', 'lit', ...])` (string column)       | `StringOneOf(col, ["lit", "lit", ...])`   |
| `col IN ('lit', 'lit', ...)` (string column)                 | `StringOneOf(col, ["lit", "lit", ...])`   |
| `col IN (1, 2, 3)` or any non-string literal list            | `Unrecognized(reason=NUMERIC_VALUE_LIST)` |
| `col >= n` and `col <= m`  (combined via `AND`)              | `NumericRange(col, of(n), of(m))`         |
| `col BETWEEN n AND m`                                        | `NumericRange(col, of(n), of(m))`         |
| `col >= n` (alone)                                           | `NumericRange(col, of(n), empty())`       |
| `col <= m` (alone)                                           | `NumericRange(col, empty(), of(m))`       |
| `length(col) <= n`                                           | `LengthBound(col, 0, n)`                  |
| `length(col) BETWEEN n AND m`                                | `LengthBound(col, n, m)`                  |
| `col ~ 'regex'` / `col ~* 'regex'` / `col SIMILAR TO 'regex'`| `RegexMatch(col, regex)`                  |
| `col IS NOT NULL`                                            | `NotNullCheck(col)`                       |
| anything else                                                | `Unrecognized(name, rendered, reason)`    |

The recognizer's vocabulary is fixed at this v1 list. Adding a shape requires
a new `Recognized` permit and a new emitter arm; the seal forces both to land
together.

### Two scopes share one recognizer

The same `ColumnConstraint` carrier serves both validation surfaces:

1. *Record-side*: the consumer's jOOQ-generated `XxxRecord` class. Bound via
   `mapping.type(XxxRecord.class).field(columnName).constraint(...)`. Catches
   bad records built by hand inside `@service` methods.
2. *Input-side*: the consumer's SDL input bean class. Bound via
   `mapping.type(InputBean.class).field(graphqlInputFieldName).constraint(...)`,
   when the input field maps to a column carrying a recognized CHECK.

R12 already classifies `InputField.ColumnField` per (input-arg, column)
pairing; the input-side mapping reuses that. The recognizer runs once per
(table, column) pair and produces one `ColumnConstraint`; the emitter binds
each `ColumnConstraint` to as many backing classes as the model knows about.

### Host: programmatic `ConstraintMapping`, no shadow class

The emitter produces a single new generated artifact:

```java
package <outputPackage>.schema;

public final class GeneratedConstraintMapping {

    private GeneratedConstraintMapping() {}

    /**
     * Apply graphitron-derived CHECK constraints to a Hibernate Validator
     * configuration. Call from a custom {@code GraphitronContext.getValidator}
     * override, or rely on {@link DefaultValidatorHolder} to do it for you.
     */
    public static ConstraintMapping toMapping(ConstraintMapping mapping) {
        mapping.type(no.sikt.example.tables.records.FilmRecord.class)
            .field("rating")
                .constraint(new PatternDef().regexp("^(G|PG|PG-13|R|NC-17)$"))
            .field("length")
                .constraint(new GenericConstraintDef<>(Min.class).param("value", 1L))
                .constraint(new GenericConstraintDef<>(Max.class).param("value", 240L));

        mapping.type(no.sikt.example.inputs.FilmInput.class)
            .field("rating")
                .constraint(new PatternDef().regexp("^(G|PG|PG-13|R|NC-17)$"));

        // ...one chain per (backing-class) triple of (record, input)...
        return mapping;
    }
}
```

The choice between three candidate hosts settles on programmatic mapping by
elimination:

- *Shadow validation class* (emit `FilmRecordValidationView` with annotations
  and copy fields into it): doubles the model surface, loses property-name
  alignment with the actual record, allocates per validate. The principle
  *Wire-format encoding is a boundary concern* applies here, the `@Constraint`
  annotations on a parallel class would be a parallel type system.
- *Fork jOOQ codegen* (override `printColumnValidationAnnotation` on a custom
  `JavaGenerator` to fire on Records): drags graphitron into the consumer's
  jOOQ codegen pipeline. The fixture pipeline already uses
  `NodeIdFixtureGenerator` (`graphitron-fixtures-codegen/.../NodeIdFixtureGenerator.java`),
  but real consumers ship their own (Sikt's own `KjerneJooqGenerator`); asking
  them to fork or compose is invasive and out of graphitron's control.
- *Programmatic mapping*: references the consumer's actual record and input
  classes by FQN, requires zero source-level changes to either, runs through
  the shape Hibernate Validator's contract anticipates. Settles by elimination.

A pre-spec spike (run during the design conversation, not committed)
confirmed the shape: programmatic mapping attaches constraints to a class
with zero source annotations, violations carry the property name, and the
violation's annotation type is preserved so R12's
`ConstraintViolations.toGraphQLError` reads `extensions.constraint` correctly.
Phase 1 ships a unit-tier `GeneratedConstraintMappingSpikeTest` that pins
this shape against `PatternDef`, `SizeDef`, and `GenericConstraintDef<>(Min/Max)`,
so the spec's "wire shape unchanged relative to R12" claim has a live test
backing it before phase 2 starts emitting record-side code.

### Validator wiring extends the existing seam

`GraphitronContextInterfaceGenerator` already emits `getValidator(env)` with a
`DefaultValidatorHolder` lazy-init holder
(`GraphitronContextInterfaceGenerator.java:76-97`). The default body lazily
builds `Validation.buildDefaultValidatorFactory().getValidator()`. R92
extends the holder to thread `GeneratedConstraintMapping.toMapping(...)`
through the configuration:

```java
public static final class DefaultValidatorHolder {
    static final Validator INSTANCE = build();

    private static Validator build() {
        var cfg = jakarta.validation.Validation
            .byProvider(org.hibernate.validator.HibernateValidator.class)
            .configure();
        var mapping = cfg.createConstraintMapping();
        GeneratedConstraintMapping.toMapping(mapping);
        return cfg.addMapping(mapping).buildValidatorFactory().getValidator();
    }
}
```

The default works out of the box; consumers who override `getValidator(env)`
to plug in a custom factory call `GeneratedConstraintMapping.toMapping(...)`
themselves on their own `ConstraintMapping`. Same shape as the existing
`getDslContext`: a default-runnable seam, an explicit override path for
advanced needs.

CDI is a consumer concern. Quarkus apps wire `@Inject Validator` and pass it
through their `GraphitronContext` impl (already supported by the seam);
plain-SE apps use the default. The rewrite's own emitted code imports
`jakarta.validation.*` and `org.hibernate.validator.*` only; no
`jakarta.inject.*`, no `jakarta.enterprise.*`. The Hibernate Validator runtime
dependency is already pinned (`graphitron-rewrite/pom.xml:80-87`); the new
import in `DefaultValidatorHolder` is the first emitted reference to it.

### Where the wrapper validates

R12 §5's wrapper today validates the SDL input bean
(`TypeFetcherGenerator.java:1158-1170`). R92 adds *one* additional
`Validator.validate(...)` call inside the same wrapper, against the
constructed record, conditional on (a) the channel carries a
`ValidationHandler`, (b) the record class has a
`ColumnConstraint` entry. Specifically, between the body call (`Service.x(...)`
or `dsl.insertInto(...).fetchOne()`) and the payload-assembly step.

Per *Validator mirrors classifier invariants*, the emitter wears
`@DependsOnClassifierCheck` against three narrow keys, each naming a specific
invariant the emitter relies on rather than the umbrella "field is populated"
(per the principle: "a load-bearing key names the invariant the emitter
relies on"). The classifier site
(`TypeBuilder.buildResultType` or wherever `columnConstraints` is populated)
wears the matching `@LoadBearingClassifierCheck` per key:

| Key                                        | Invariant the emitter relies on                                              |
|--------------------------------------------|-----------------------------------------------------------------------------|
| `check-recognition.string-one-of-non-empty` | `StringOneOf.literals` is non-empty                                         |
| `check-recognition.regex-is-java-pattern`   | `RegexMatch.regex` parses as a `java.util.regex.Pattern` (no Postgres-only constructs) |
| `check-recognition.length-bounds-ordered`   | `LengthBound.min <= LengthBound.max`                                        |

The emitter joins `StringOneOf.literals` with `Pattern.quote` per literal and
`|` between, producing `^(\Qlit1\E|\Qlit2\E|...)$`. Per-literal regex
quoting is the emitter's responsibility, not a recognizer invariant: the
recognizer's contract ends at "the literal strings as the AST yielded them".
A `StringOneOf(col, [])` would still fail at emit time without the
non-empty key (`"^()$"` matches only the empty string, which is silently
wrong rather than a compile error), which is why this single key is
load-bearing.

`LoadBearingGuaranteeAuditTest` covers all three keys automatically. A future
relaxation of the recognizer (admitting an empty `StringOneOf`, say) would
surface as an orphaned consumer site rather than a runtime regex failure.

The sequence inside the wrapper, with both R12 §5 and R92 active, is:

```
1. validator.validate(input)         ← R12 §5; rejects bad GraphQL input
2. body call (service or DML)        ← only if step 1 produced no violations
3. validator.validate(record)        ← R92 new; rejects bad service-built record
4. payload assembly                  ← only if step 3 produced no violations
```

Steps 1 and 3 share the same `Validator` instance, the same
`ConstraintMapping` (which carries both input-side and record-side type
chains), and the same `ConstraintViolations.toGraphQLError` translation. The
addition is one `if (!violations.isEmpty()) return DataFetcherResult.../*assemble error payload*/`
block per fetcher, shaped exactly like step 1's existing block.

---

## Strict-mode policy

`Unrecognized` shapes need a build-time decision: WARN (skip, log) or ERROR
(fail). The default is ERROR, with an opt-out flag.

The default applies *Validator mirrors classifier invariants*: any CHECK
expression the recognizer can't model is a silent enforcement gap on the API
side, exactly the kind of "the model has a hole the runtime doesn't know
about" the principle exists to prevent. Strict-mode failure messages name the
constraint, the table, the column (when single-column), and the
`UnrecognizedReason` so the consumer can decide whether to add the shape to
the recognizer's vocabulary or remove the CHECK from the schema.

The opt-out lives on `directives.graphqls` as a directive argument:

```graphql
directive @graphitron(
    # ...existing args...
    strictCheckConstraints: Boolean = true
) on SCHEMA
```

Set `false` to convert ERROR to WARN for `Unrecognized`. The build still
processes recognized CHECKs; only unrecognized ones become warnings. A
build-time report (one line per unrecognized CHECK, with table, column,
reason, and rendered expression) goes to the Maven plugin's stdout.

---

## Implementation sites

The four-file delta (plus model + emitter glue):

- New file `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/model/CheckRecognition.java`:
  sealed interface and five `Recognized` permits (`StringOneOf`,
  `NumericRange`, `LengthBound`, `RegexMatch`, `NotNullCheck`) plus
  `Unrecognized` and `UnrecognizedReason`.
- New file `model/ColumnConstraint.java`: the model carrier.
- New file `JooqCatalog.ParsedCheckConstraint` (nested record on
  `JooqCatalog`, since `org.jooq.Condition` is exempted to that file
  alone): parse-boundary projection.
- New file `recognizer/CheckRecognizer.java`: the visitor. Imports
  `org.jooq.Condition` (under the explicit fifth-file exemption noted in
  the Boundary section); visits the AST via jOOQ's public visitor APIs;
  returns `CheckRecognition`. Detailed shape table above.
- New file `generators/schema/GeneratedConstraintMappingGenerator.java`:
  emits `GeneratedConstraintMapping` (the runtime artifact). Walks every
  classified `TableType` whose `columnConstraints` is non-empty plus every
  classified input bean whose fields map to those columns.
- `JooqCatalog.java`: add `findCheckConstraints(Table<?>)` returning
  `List<ParsedCheckConstraint>`, where each entry holds jOOQ's already-parsed
  `Condition` directly. No text rendering, no parser round-trip.
- `TypeBuilder.buildResultType` (or the per-`@table` resolution site): walk
  `JooqCatalog.findCheckConstraints(table)`, run each through
  `CheckRecognizer.recognize(...)`, partition into `Recognized` and
  `Unrecognized`, attach the recognized list to `TableType.columnConstraints`,
  surface the unrecognized list to the `GraphitronSchemaValidator` (strict-mode
  decides ERROR vs WARN there).
- `GraphitronSchemaValidator.java`: new error kind `UnrecognizedCheckConstraint`
  (parallel to existing `UnclassifiedField` / `UnclassifiedType`); strict-mode
  toggle reads the directive arg.
- `GraphitronContextInterfaceGenerator.java`: extend `DefaultValidatorHolder`
  to route through `GeneratedConstraintMapping.toMapping(...)`. Defaults to
  the no-mapping shape when no constraints classify (so the dep on Hibernate
  Validator stays optional in practice).
- `TypeFetcherGenerator.java`: in the wrapper builder where R12 §5 emits step
  1's `validator.validate(input)`, conditionally emit step 3's
  `validator.validate(record)` (gated on `ValidationHandler` channel + the
  record's `TableType.columnConstraints` being non-empty). Both calls share
  the `validator` local already in scope.
- `directives.graphqls`: add `strictCheckConstraints: Boolean = true` to
  `@graphitron`.
- `graphitron-sakila-db/src/main/resources/init.sql`: add CHECK constraints
  to one or more fixture tables (one per recognized shape, plus one
  unrecognized for strict-mode coverage). Bump
  `<jooq.codegen.schema.version>` so jOOQ regenerates.
- `graphitron-sakila-db/pom.xml`: enable
  `<includeCheckConstraints>true</includeCheckConstraints>` in the codegen
  configuration.

---

## Tests

Four tiers, matching the canonical structure documented in the test-tier
guide:

### Unit-tier

- `CheckRecognizerTest`: invariant-pinning only, not per-shape coverage.
  Asserts the recognizer rejects a multi-column predicate with
  `UnrecognizedReason.CROSS_COLUMN_PREDICATE` rather than partial
  recognition; asserts an empty `IN ()` list (parser-allowed but
  semantically empty) hits `UnrecognizedReason.UNSUPPORTED_OPERATOR`
  rather than producing an empty `StringOneOf`. Per-shape behaviour is
  pipeline-tier work below; this tier exists to pin invariants the
  type system can't.

### Pipeline-tier (the primary behavioural tier)

- `CheckConstraintClassificationTest`: an SDL with `@table` on a fixture table
  carrying recognized CHECKs goes through `GraphitronSchemaBuilder`; assert
  `TableType.columnConstraints()` is populated with the expected shapes.
  One CHECK per recognized shape (`StringOneOf`, `NumericRange`,
  `LengthBound`, `RegexMatch`, `NotNullCheck`); one execution path per
  variant landing here (per *Pipeline tests are the primary behavioural
  tier*).
- `CheckConstraintStrictModeTest`: the same fixture with one unrecognized
  CHECK; assert `GraphitronSchemaValidator` reports
  `UnrecognizedCheckConstraint` under default strict mode and skips it under
  `strictCheckConstraints: false`.
- `GeneratedConstraintMappingEmitTest`: the emitted `GeneratedConstraintMapping`
  class from the same SDL; assert the `TypeSpec` builds the expected fluent
  chain (one `.type(X.class).field(F).constraint(...)` per recognized
  `ColumnConstraint`). Code-string assertions are banned per the rewrite test
  rules; this test runs the emitted `JavaFile.toJavaFileObject()` through
  `javac` and inspects the result via APT or Roaster.

### Compilation-tier

- The existing compile of `graphitron-sakila-example` against real jOOQ
  classes covers `GeneratedConstraintMapping`'s import resolution and
  Hibernate Validator API surface. Add CHECK-bearing tables to the sakila
  fixture so the generated mapping references real jOOQ records.

### Execution-tier (the proof)

- `CheckConstraintExecutionTest`: a mutation that violates each recognized
  CHECK shape gets rejected at the API boundary with a typed error in
  `payload.errors` carrying the expected `extensions.constraint`. The
  database is never touched (verified by a connection counter or by
  asserting no Postgres log entries for the violating queries).
- `ValidUnchangedExecutionTest`: a mutation that satisfies every CHECK runs
  exactly as it does today, with no observable behaviour change. Pins the
  "this is purely additive" claim.

---

## Phasing

Three independent landings; each ships through the canonical
Backlog → Spec → Ready → In Progress → In Review → Done flow on its own.
Phase 1 is purely classifier; phase 2 adds the runtime emit; phase 3 widens
to the input-side. Phases 2 and 3 individually deliver user-visible value;
phase 1 alone surfaces only the build-time strict-mode signal.

### Phase 1: recognizer and model

- New model files (`CheckRecognition`, `ColumnConstraint`,
  `JooqCatalog.ParsedCheckConstraint`).
- `CheckRecognizer`.
- `JooqCatalog.findCheckConstraints`.
- Wire into `TypeBuilder` to populate `TableType.columnConstraints`.
- `UnrecognizedCheckConstraint` schema-validation error kind.
- Strict-mode toggle on `@graphitron`.
- Fixture init.sql gains one CHECK per recognized shape plus one
  unrecognized.
- All unit-tier and pipeline-tier tests above.

Acceptance: build report names every CHECK constraint in the fixture and
classifies it correctly. No emitted code change yet.

Phase 1 ships its `@LoadBearingClassifierCheck` triple
(`string-one-of-non-empty`, `regex-is-java-pattern`, `length-bounds-ordered`)
producer-only: phase 2 attaches the matching `@DependsOnClassifierCheck` on
the emitter side. Producer-only is an allowed shape per
*Classifier guarantees shape emitter assumptions* (rewrite-design-principles.adoc:124,
"Producers without consumers are allowed: some classifier checks reject
shapes for hygiene rather than because an emitter relies on them"); the
producer in phase 1 is hygienic, the phase 2 emitter then makes it
load-bearing.

### Phase 2: record-side mapping emit and runtime wiring

- `GeneratedConstraintMappingGenerator`: emit the `<outputPackage>.schema.GeneratedConstraintMapping`
  class with one `mapping.type(XxxRecord.class)` chain per `TableType` whose
  `columnConstraints` is non-empty.
- `DefaultValidatorHolder` in `GraphitronContextInterfaceGenerator`: wire
  through Hibernate Validator and `GeneratedConstraintMapping.toMapping(...)`.
- `TypeFetcherGenerator`: emit step 3 (`validator.validate(record)`) in the
  wrapper.
- Compilation-tier and execution-tier tests covering record-side validation.

Acceptance: a service method that returns a record violating a CHECK gets
caught by the API-side validator and surfaces as a typed error, with the
database connection never invoked for the violating row.

### Phase 3: input-side mapping emit

- Walk `InputField.ColumnField` per (input-arg, column) and add a
  matching `mapping.type(InputBean.class).field(graphqlFieldName)...` chain
  to `GeneratedConstraintMapping.toMapping(...)`.
- Execution-tier test that a mutation passing an invalid input value gets
  rejected by step 1 (R12 §5's pre-existing path), with the violation
  carrying the same `extensions.constraint` as the record-side path.

Acceptance: bad input rejected at step 1 (before any DB call), satisfying
the original motivation's "shorter loop" goal.

*Phase 3 depends on [`emit-input-records.md`](emit-input-records.md)
(R94).* The "consumer's SDL input bean class" the `mapping.type(...)`
chain references does not exist in the rewrite today — graphitron uses
`Map<String, Object>` end-to-end for SDL inputs (`MutationFetchers.java:55,
75`). R94 emits each SDL `input` type as a graphitron-internal Java
record under `<outputPackage>.inputs`, which is exactly what phase 3
needs as a target. Phases 1 and 2 do not depend on R94 (the record-side
target is the consumer's jOOQ-generated `XxxRecord`, which already
exists); only phase 3 blocks until R94 lands.

---

## Open architectural decisions

1. *Strict-mode default.* ERROR (per the principle), with the directive
   opt-out. Open to flip if early-adopter feedback says it's too noisy on
   real consumer schemas.
2. *Postgres normalisation: integration test pin.* The recognizer eats the
   parsed `Condition` jOOQ hands back; what the schema author wrote is
   irrelevant once Postgres has parsed and re-rendered it into the AST jOOQ
   then ingests. The vocabulary list above is normative against the AST
   shapes jOOQ produces from Postgres-normalised CHECK constraints. Phase 1
   includes a pipeline-tier test that round-trips one CHECK per shape
   through Postgres (via the `local-db` profile) and asserts the recognizer
   classifies it correctly. This pins the recognizer against jOOQ's actual
   AST output, not paper-schema assumptions. (`renderInlined` is used only
   in the diagnostic `Unrecognized.renderedExpression` field for build-time
   error messages; the recognizer never reads it.)
3. *NOT NULL overlap.* Postgres synthesises `CHECK (col IS NOT NULL)` for
   every `NOT NULL` column. With `<includeSystemCheckConstraints>` off (the
   default), these don't appear in `getChecks()`; they live as the column's
   `IS_NULLABLE`. Default policy stays "off" to avoid duplicate
   `@NotNull` emit. Only user-declared `IS NOT NULL` CHECKs (rare; usually
   redundant) classify as `NotNullCheck`, and the emitter dedupes against
   the column's nullability.
4. *Cross-column CHECKs.* `CHECK (start_date < end_date)` is out of scope.
   The recognizer reports `UnrecognizedReason.CROSS_COLUMN_PREDICATE`. A
   future arm could lift to a class-level Hibernate constraint
   (`mapping.type(X.class).constraint(...)`), which is structurally
   supported by Hibernate Validator but adds significant emitter complexity
   for a niche case. Defer.
5. *Cross-column CHECK seal-fork.* The current sealed shape declares
   `Recognized.column()` on the root because every v1 arm constrains a
   single column. A future cross-column arm (decision 4) would either
   force `column()` to become `Optional` (breaking every existing switch)
   or land in a sibling sealed sub-tree. When that future arm lifts,
   split `Recognized` into `SingleColumn` (carrying `column()`) and a
   sibling `MultiColumn` (carrying its own column-set accessor) before
   adding the cross-column permit, rather than retrofitting a nullable
   accessor on the existing root. Per *Sealed hierarchies over enums for
   typed information* (rewrite-design-principles.adoc:25, "sealed sub-
   interfaces per axis rather than inventing a god accessor whose
   meaning depends on the variant").

---

## Future evolution (out of scope)

- *Class-level constraints* for cross-column CHECKs (decision 4 above).
  Implementation note: lift goes through the `SingleColumn`/`MultiColumn`
  seal split flagged in decision 5, not by widening the existing
  `Recognized.column()` accessor.
- *Numeric value lists.* `CHECK (col IN (1, 2, 3))` currently rejects with
  `UnrecognizedReason.NUMERIC_VALUE_LIST`. Lifting it requires either a
  custom `@OneOf(int...)` Hibernate constraint (graphitron emits the
  validator class plus its `ConstraintValidator`) or a per-column
  `@Min`/`@Max` pair when the literals form a contiguous range. Both
  bigger surface than v1; tracked here so the strict-mode error message
  can point at this entry. Worth noting: when the custom `@OneOf` lift
  ships, `StringOneOf` should also migrate from `PatternDef` to
  `@OneOf(String...)` so the string and numeric arms share one constraint
  shape rather than splitting across two encodings (regex vs. value-set).
  Until then, the asymmetry (string `IN` lists emit as `PatternDef`,
  numeric `IN` lists reject) is deliberate and documented here.
- *Custom `ConstraintValidator` per CHECK* for shapes the recognizer
  can't model. Costly (one generated class per CHECK; runtime evaluator
  for arbitrary SQL); rejected up front in the design conversation.
- *DB round-trip evaluator.* Issuing `dsl.fetchValue(check.condition())`
  with the record's columns bound is always-correct but defeats the
  motivation's "shorter loop" goal. Rejected.
- *Lift CHECK metadata into the user-facing GraphQL schema.* A future
  `@graphitron(documentChecks: true)` could serialise recognized
  constraints into SDL descriptions ("must match `^(G|PG|...)$`") so
  schema introspection surfaces the rule. Out of scope here; the runtime
  validation contract is the load-bearing piece.

---

## Non-goals

- Vendor-portability beyond Postgres. Graphitron targets Postgres; the
  recognizer's AST shape table is calibrated against jOOQ's Postgres
  rendering. Other dialects classify whatever CHECKs jOOQ surfaces but
  the recognizer's coverage is not explicitly engineered for them.
- A pluggable recognizer. The five `Recognized` arms are fixed;
  extending the vocabulary is a code change, not a configuration knob.
  Per *Sealed hierarchies over enums*, a new shape adds a permit.
- Replacing R12 §1's `SqlStateHandler("23514")` arm. CHECKs that the
  recognizer can't model (under strict-mode opt-out) still get caught at
  the database side and surfaced via R12's existing path. R92 is purely
  additive: it shrinks the set of CHECKs that reach R12's
  `SqlStateHandler` arm without removing the arm.
