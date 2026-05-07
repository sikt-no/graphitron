---
id: R101
title: "Custom-scalar Java type configuration (extended-scalars built-in)"
status: Backlog
bucket: architecture
priority: 6
theme: model-cleanup
depends-on: [emit-input-records]
---

# Custom-scalar Java type configuration (extended-scalars built-in)

Graphitron has to know the Java type behind every GraphQL scalar at
generate time: it materializes input-record component types
(R94), service-call argument types, and `Field<X>` projection types
without a runtime introspection step. Today the mapping is
hard-coded to the five spec built-ins (`Int`, `Float`, `String`,
`Boolean`, `ID`) at four sites:

- `ServiceCatalog.java:937` (service-method parameter types)
- `FieldBuilder.java:2783` (fetcher-side scalar projection)
- `RowsMethodShape.java:71` (row-shape scalar coercion)
- `ArgBindingMap.java:199` (argMapping diagnostics)

Anything else surfaces as `UnclassifiedType`. That's a hard wall the
moment a consumer declares `scalar BigDecimal` or `scalar DateTime`
in their SDL. Sikt's own subgraphs *all* use
`ExtendedScalars.GraphQLBigDecimal` and `ExtendedScalars.DateTime`,
so the rewrite is unusable in production without a story for
extra-spec scalars.

## What graphitron actually needs from a scalar at generate time

Just the **Java type** the input record component / service param /
projection field will use. Nothing else:

- The `Coercing` lives in the *consumer's* `RuntimeWiring`-equivalent
  hook (`Graphitron.buildSchema(Consumer<GraphQLSchema.Builder>)`,
  per the changelog entry at `81fa607` + follow-ups). Graphitron
  does not register Coercings, does not depend on
  `graphql-java-extended-scalars` at runtime, and does not need to
  know the Coercing's class name. The consumer is already calling
  `.additionalType(ExtendedScalars.GraphQLBigDecimal)` (or
  equivalent) on the schema builder; graphitron's emitted types
  just need to *type-check* against the resulting runtime
  `Object`.
- The Java type is enough to drive: input-record component
  declarations (R94), record canonical-ctor synthesis, service
  parameter binding (`ArgBindingMap`), `Field<X>` declarations on
  fetcher row-shape projections, and arg-coercion diagnostics.

## Three resolution sources, in priority order

1. **Spec built-ins** (`Int`, `Float`, `String`, `Boolean`, `ID`):
   the existing hardcoded mapping, lifted into a shared registry.
   Always wins; `@scalarType` on a spec built-in is a hard error
   from the LSP / build (the GraphQL spec already defines these).
2. **`@scalarType(javaClass: "...")` on a SCALAR declaration** in
   the consumer's SDL: the explicit per-consumer escape. Takes
   precedence over the convention layer; this is the migration
   path for any scalar graphitron doesn't recognize by name and the
   override knob for consumers who want a different Java type than
   the convention picks (e.g. `BigDecimal` mapped to a domain
   wrapper).
3. **graphql-java-extended-scalars convention**: a built-in name →
   Java-type registry that recognizes the names `extended-scalars`
   exports (`GraphQLBigDecimal` → `BigDecimal`, `DateTime` →
   `OffsetDateTime`, `Date` → `LocalDate`, `Time` → `LocalTime`,
   `BigInteger`, `Long`, `Short`, `Byte`, `Duration`, `UUID`,
   `URL`, `Object`, etc.). Convention layer; loses to
   `@scalarType`.

If none of the three resolves, the field surfaces as
`UnclassifiedType` with a rejection that names the scalar and points
at `@scalarType` as the fix. No silent fallback to `Object`.

The resolution-order ordering matters: convention loses to explicit
override so a consumer who imports `extended-scalars` but wants
`BigDecimal` modeled as their own `Money` wrapper can still get it
without a fork; spec built-ins win over both because GraphQL itself
binds them.

## Directive shape

```graphql
"""
Declares the Java type a consumer-provided scalar maps to at
codegen time. Graphitron uses this for input-record component types
(@R94), service parameter types, and Field<X> projection types.

The actual Coercing lives in the consumer's GraphQLSchema.Builder
hook; graphitron does not register it.
"""
directive @scalarType(javaClass: String!) on SCALAR
```

No `coercing:` attribute. Graphitron has no use for it at codegen,
and the consumer already wires the Coercing into their schema
builder hook. Adding it here would duplicate the consumer's
`additionalType(...)` call and create a class of "directive says X,
runtime says Y" misconfigurations exactly analogous to the one R96
removes for `@record`.

`javaClass` is a fully-qualified class name (no `ExternalCodeReference`
nesting; the `className: "..."` shape that R93 Phase 2 is currently
migrating *away* from is not the future — `@scalarType` ships in the
flat-string shape from day one).

## Extended-scalars built-in registry

The full name → Java-type mapping graphitron ships built-in. Drawn
from `graphql-java-extended-scalars`'s public `ExtendedScalars`
constants:

| Scalar name        | Java type                     |
|--------------------|-------------------------------|
| `GraphQLBigDecimal`| `java.math.BigDecimal`        |
| `BigDecimal`       | `java.math.BigDecimal`        |
| `GraphQLBigInteger`| `java.math.BigInteger`        |
| `BigInteger`       | `java.math.BigInteger`        |
| `GraphQLLong`      | `java.lang.Long`              |
| `Long`             | `java.lang.Long`              |
| `GraphQLShort`     | `java.lang.Short`             |
| `Short`            | `java.lang.Short`             |
| `GraphQLByte`      | `java.lang.Byte`              |
| `Byte`             | `java.lang.Byte`              |
| `DateTime`         | `java.time.OffsetDateTime`    |
| `Date`             | `java.time.LocalDate`         |
| `Time`             | `java.time.OffsetTime`        |
| `LocalTime`        | `java.time.LocalTime`         |
| `LocalDateTime`    | `java.time.LocalDateTime`     |
| `Duration`         | `java.time.Duration`          |
| `UUID`             | `java.util.UUID`              |
| `Url`              | `java.net.URL`                |
| `Object`           | `java.lang.Object`            |
| `JSON`             | `java.lang.Object`            |
| `NonNegativeInt`   | `java.lang.Integer`           |
| `NonNegativeFloat` | `java.lang.Double`            |
| `PositiveInt`      | `java.lang.Integer`           |
| `PositiveFloat`    | `java.lang.Double`            |
| `NegativeInt`      | `java.lang.Integer`           |
| `NegativeFloat`    | `java.lang.Double`            |

Both the `GraphQL`-prefixed names and the bare names are recognized;
extended-scalars itself uses both interchangeably depending on
import style, and consumer SDLs in the wild do too. Final list
locks at Spec time; the table above is the working draft.

The registry lives next to the existing scalar-handling sites and is
exposed as a single resolver entry-point (`ScalarTypeResolver` or
similar). The classifier asks the resolver; emitters consume the
result. No emitter directly imports the registry maps.

## Misconfiguration risk

A consumer who declares `scalar BigDecimal` and writes
`@scalarType(javaClass: "java.math.BigInteger")` is asking for two
different things in two places (their Coercing serializes as
`BigDecimal`, graphitron generates `BigInteger`-typed accessors).
Detecting this at codegen would require introspecting the
consumer's runtime wiring, which graphitron explicitly doesn't do.
Out of scope. The error surfaces at compile-time on the consumer's
side as a type mismatch, which is loud enough.

The convention layer handles the common case (recognize the name,
pick the right type); the override is for the edge case
(consumer's domain wrapper); the misconfiguration "I overrode but
my Coercing is wrong" stays the consumer's problem.

## Phasing

### Phase 1: lift the four hardcoded sites into a shared resolver

Extract the spec-built-in mapping (`Int` → `Integer` etc.) into a
single `ScalarTypeResolver` consulted by `ServiceCatalog`,
`FieldBuilder`, `RowsMethodShape`, and `ArgBindingMap`. Behavior-
neutral: the same five names still resolve, anything else still
surfaces as `UnclassifiedType`. Pure refactor; no SDL change.

### Phase 2: extended-scalars convention layer

Add the built-in name → Java-type registry above. Anything in the
table now classifies; nothing in the table now-fails-loudly with a
rejection naming `@scalarType` as the fix. No directive yet — the
override comes in Phase 3.

### Phase 3: `@scalarType` directive

Declare `directive @scalarType(javaClass: String!) on SCALAR` in
`directives.graphqls`. Wire the resolver's "explicit override"
arm. Update LSP completion + diagnostics: completion on the
`javaClass:` argument should suggest the convention's pick when
the scalar is in the extended-scalars registry, so consumers see
"this is what graphitron would pick anyway, click to confirm" and
the override stays cheap.

### Phase 4: housekeeping

- Migration note in `changelog.md` naming the SHA where the
  resolver lands and the SHA where the directive lands.
- `code-generation-triggers.adoc` row for `@scalarType`.
- Document the resolution order in `docs/README.adoc` (or wherever
  the scalar story lives post-R9).

## Out of scope

- **Coercing registration.** The consumer wires Coercings on their
  `GraphQLSchema.Builder`. Graphitron neither imports
  `graphql-java-extended-scalars` nor generates Coercing
  registrations.
- **A Maven-side scalar mapping configuration.** A pom-level
  scalar→Java map was the original sketch, but it duplicates
  information that lives next to the `scalar X` SDL declaration and
  forks the source-of-truth. SDL-side `@scalarType` keeps the
  declaration in one place; the Maven plugin stays out of it.
- **Coercion-failure error shape.** R94 already settled
  `FromMapResult.{Ok | TypeMismatch}` for input-side coercion; this
  item doesn't change that.
- **Reflective scalar→type discovery from the runtime wiring.** The
  generator runs before the consumer's `Graphitron.buildSchema`
  hook does; introspecting it would require a separate runtime
  pass and a hard runtime dep we're explicitly avoiding.

## Relation to R94 / R96 / R97 / R98

Strictly an enabler. R94 (`emit-input-records.md`) currently ships
with the four hardcoded scalar names; sakila uses zero custom
scalars, so R94 can land on trunk before this item ships. Real
production usability of the R94 + R96 + R97 + R98 cluster — for
Sikt's subgraphs and for any consumer using extended-scalars —
requires this item to ship.

## Tests

- `ScalarTypeResolver` unit tests: spec built-ins resolve, extended
  names resolve, override wins over convention, override on a spec
  name is a hard error, unknown scalar surfaces a rejection that
  names the scalar.
- Pipeline test: a fixture SDL declaring `scalar BigDecimal` (no
  directive) and using it on an input field generates a record
  with a `BigDecimal` component.
- Pipeline test: a fixture SDL declaring `scalar Money` with
  `@scalarType(javaClass: "no.sikt.example.Money")` generates a
  record with a `Money` component.
- Compilation test: the generated record compiles against a
  consumer wiring that registers
  `ExtendedScalars.GraphQLBigDecimal` on the schema builder. (The
  sakila example currently uses zero custom scalars; this needs a
  small new fixture in `graphitron-fixtures-codegen` or a dedicated
  sub-fixture.)
- LSP test: completion on `@scalarType(javaClass: |)` for a
  `scalar GraphQLBigDecimal` declaration suggests
  `java.math.BigDecimal` from the convention table.

## Risk

Low. Phase 1 is a pure refactor. Phase 2 expands the recognized set
without breaking anything (names that were `UnclassifiedType` now
classify). Phase 3 introduces a directive whose only behavioural
weight is "override this convention pick"; absent the directive,
behaviour is identical to Phase 2. Phase 4 is docs.

The one real risk is the convention table's coverage drift over
time as `graphql-java-extended-scalars` adds scalars; the bound
graphql-java-extended-scalars version (which graphitron *does not*
take a runtime dep on, but tracks for the convention layer) is
recorded in `docs/README.adoc` and bumped via changelog.
