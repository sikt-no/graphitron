---
id: R101
title: "Custom-scalar Java type configuration (extended-scalars built-in)"
status: Spec
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
`Boolean`, `ID`) at five sites:

- `ServiceCatalog.java:937` (service-method parameter types)
- `FieldBuilder.java:2783` (fetcher-side scalar projection)
- `RowsMethodShape.java:71` (row-shape scalar coercion)
- `AppliedDirectiveEmitter.java:149-152` (SDL default-value emission
  via `Scalars.GraphQL{Name}`)
- `GraphitronSchemaClassGenerator.java:197-201` (load-bearing for
  Phase 4: this is the *current* authority on which scalars get
  registered via `.additionalType(Scalars.GraphQLInt)`...).

`ArgBindingMap.java:199` is *not* a Java-type producer — it's
`GraphQLType` introspection for path-walk error messages — so it's
not in the migration list, but a follow-up pass should route its
diagnostic strings through the resolver for consistency.

Anything else surfaces as `UnclassifiedType`. That's a hard wall the
moment a consumer declares `scalar BigDecimal` or `scalar DateTime`
in their SDL. Sikt's own subgraphs *all* use
`ExtendedScalars.GraphQLBigDecimal` and `ExtendedScalars.DateTime`,
so the rewrite is unusable in production without a story for
extra-spec scalars.

## Design: name the scalar instance, reflect the rest

The consumer points at the `public static final GraphQLScalarType`
constant they want graphitron to use for the scalar:

```graphql
scalar BigDecimal
    @scalarType(scalar: "graphql.scalars.ExtendedScalars.GraphQLBigDecimal")
```

At codegen, graphitron:

1. Loads the named class (`graphql.scalars.ExtendedScalars`) off the
   consumer's compile classpath via the Maven plugin's project
   classpath elements.
2. Reads the named static field (`GraphQLBigDecimal`); confirms it
   is `public static`, not null, and assignable to
   `graphql.schema.GraphQLScalarType`.
3. Pulls the `GraphQLScalarType`'s `Coercing` and reflects on
   `coercing.getClass().getGenericInterfaces()` to recover the
   `Coercing<I, O>` type parameters. The `I` (input) parameter is
   the Java type input-record components, service params, and
   `Field<X>` projections bind to.
4. Emits `.additionalType(graphql.scalars.ExtendedScalars.GraphQLBigDecimal)`
   into the synthesized `GraphitronSchema` so the consumer's
   `Graphitron.buildSchema(...)` hook does not have to wire it.

One reference, no second source of truth. The Java type and the
runtime `Coercing` come from the same instance the consumer points
at, so the misconfiguration class my earlier `javaClass:` sketch
created — "directive says `BigDecimal`, Coercing serializes as
`BigInteger`" — cannot occur by construction.

## Resolution order

1. **Spec built-ins** (`Int`, `Float`, `String`, `Boolean`, `ID`):
   resolved through the same reflection path against
   `graphql.Scalars.GraphQL{Int, Float, String, Boolean, ID}`. Always
   wins; `@scalarType` on a spec built-in name is a hard validation
   error (the GraphQL spec already defines these and the runtime
   already wires them).
2. **`@scalarType(scalar: "...")` declaration** on the SCALAR: the
   consumer's explicit reference. Beats the convention layer.
3. **graphql-java-extended-scalars convention**: a built-in
   *name → static-field-FQN* table — `GraphQLBigDecimal` →
   `graphql.scalars.ExtendedScalars.GraphQLBigDecimal`,
   `DateTime` → `…ExtendedScalars.DateTime`, etc. The table only
   resolves when the named class is on the consumer's classpath; if
   the consumer hasn't pulled the artifact, the convention misses
   and the scalar surfaces unresolved. Same reflection path as the
   directive.

Unresolved → hard validation error at codegen with a message
naming the scalar and pointing at `@scalarType(scalar:)` as the
fix. No silent fallback to `Object`.

The ordering matters: convention loses to explicit override, so a
consumer who imports `extended-scalars` but wants `BigDecimal`
modeled as their own `Money` wrapper just declares
`@scalarType(scalar: "com.example.Scalars.Money")` and graphitron
introspects their constant instead. Spec built-ins win over both
because GraphQL itself binds them.

## Failure modes are validation errors

The reflection path has several ways to fail; all of them are loud
validation errors at codegen, named by their failure mode:

- **Class not found.** The class named in `scalar:` is not on the
  consumer's classpath.
- **Field not found / not public / not static.** Self-explanatory.
- **Field is null at codegen time.** Static initialization side-effect
  the consumer must own.
- **Field is not a `GraphQLScalarType`.** The reference points at
  something else.
- **Coercing's type parameters erased to `Object`.** The Coercing
  is declared as a raw type or as `Coercing<Object, Object>` so the
  `I` parameter doesn't recover a concrete type. The error names
  the Coercing's class and instructs the consumer to declare
  concrete type parameters.
- **`@scalarType` on a spec built-in.** Hard error per the
  resolution order above.

Each failure mode produces a typed `Rejection` carrier through the
existing classifier infrastructure; the SDL author sees one error
per misconfigured scalar declaration.

Erasure is treated as a *consumer fix*, not a graphitron fallback.
The original sketch carried a `javaClass:` fallback for this case;
that's dropped. If erasure becomes a real obstacle in practice
(some library ships its Coercings raw and the consumer can't fix
it upstream), we'll reopen with a follow-up item — but the default
position is "fix the Coercing's type parameters."

## Built-in convention table

Recognized names that resolve via the convention layer when the
named static-field constants are on the classpath. Both the
`GraphQL`-prefixed and the bare-name forms map to the same constant
(`extended-scalars` exposes both styles depending on import idiom):

| SDL scalar name      | Resolves to                                                 |
|----------------------|-------------------------------------------------------------|
| `BigDecimal`         | `graphql.scalars.ExtendedScalars.GraphQLBigDecimal`         |
| `GraphQLBigDecimal`  | `graphql.scalars.ExtendedScalars.GraphQLBigDecimal`         |
| `BigInteger`         | `graphql.scalars.ExtendedScalars.GraphQLBigInteger`         |
| `GraphQLBigInteger`  | `graphql.scalars.ExtendedScalars.GraphQLBigInteger`         |
| `Long`               | `graphql.scalars.ExtendedScalars.GraphQLLong`               |
| `GraphQLLong`        | `graphql.scalars.ExtendedScalars.GraphQLLong`               |
| `Short`              | `graphql.scalars.ExtendedScalars.GraphQLShort`              |
| `GraphQLShort`       | `graphql.scalars.ExtendedScalars.GraphQLShort`              |
| `Byte`               | `graphql.scalars.ExtendedScalars.GraphQLByte`               |
| `GraphQLByte`        | `graphql.scalars.ExtendedScalars.GraphQLByte`               |
| `DateTime`           | `graphql.scalars.ExtendedScalars.DateTime`                  |
| `Date`               | `graphql.scalars.ExtendedScalars.Date`                      |
| `Time`               | `graphql.scalars.ExtendedScalars.Time`                      |
| `LocalTime`          | `graphql.scalars.ExtendedScalars.LocalTime`                 |
| `LocalDateTime`      | `graphql.scalars.ExtendedScalars.LocalDateTime`             |
| `Duration`           | `graphql.scalars.ExtendedScalars.Duration`                  |
| `UUID`               | `graphql.scalars.ExtendedScalars.UUID`                      |
| `Url`                | `graphql.scalars.ExtendedScalars.Url`                       |
| `Object`             | `graphql.scalars.ExtendedScalars.Object`                    |
| `JSON`               | `graphql.scalars.ExtendedScalars.Json`                      |
| `NonNegativeInt`     | `graphql.scalars.ExtendedScalars.NonNegativeInt`            |
| `NonNegativeFloat`   | `graphql.scalars.ExtendedScalars.NonNegativeFloat`          |
| `PositiveInt`        | `graphql.scalars.ExtendedScalars.PositiveInt`               |
| `PositiveFloat`      | `graphql.scalars.ExtendedScalars.PositiveFloat`             |
| `NegativeInt`        | `graphql.scalars.ExtendedScalars.NegativeInt`               |
| `NegativeFloat`      | `graphql.scalars.ExtendedScalars.NegativeFloat`             |

Java types are not pre-baked into the table; the reflection path
recovers them from the Coercing each time. The convention is
pure-pointer; the Java side is whatever the named constant
exposes. Final list locks at Spec time.

## Directive shape

```graphql
"""
Binds an SDL scalar to a `GraphQLScalarType` constant on the
consumer's classpath. Graphitron reflects on the constant to
recover the Java type for input records, service params, and
Field<X> projections, and registers the constant on the synthesized
schema so the consumer's buildSchema hook does not have to wire it.

Hard validation error if the reference does not resolve, the
constant's Coercing has erased type parameters, or the directive is
applied to a GraphQL spec built-in.
"""
directive @scalarType(scalar: String!) on SCALAR
```

A single argument named `scalar:` — what the SDL author thinks of
("the scalar I want") rather than how the JVM resolves it ("a
field on a class"). The string is a fully-qualified Java reference
to a `public static final GraphQLScalarType`; the `Class.FIELD`
shape is the only one supported (no factory methods, no dynamic
construction; out of scope).

## Out of scope

- **Coercings registered manually by the consumer.** A consumer
  who has a `GraphQLScalarType` they construct dynamically (factory
  method, builder pattern, conditional construction) has no
  `@scalarType` story. They must hoist the construction into a
  `public static final` constant graphitron can point at. If this
  becomes a real friction point we'll reopen with a follow-up item;
  the default position is "expose a constant."
- **Coercings with erased type parameters.** Validation error per
  *Failure modes* above. No `javaClass:` fallback.
- **Maven-side scalar configuration.** A pom-level
  scalar→Java-type map was the original sketch; it duplicates
  information that lives next to the `scalar X` SDL declaration and
  forks the source-of-truth. SDL-side `@scalarType` keeps the
  declaration in one place; the Maven plugin stays out of it.
- **Coercion-failure error shape.** R94 already settled
  `FromMapResult.{Ok | TypeMismatch}` for input-side coercion;
  this item doesn't change that.

## Phasing

Each phase must leave the build green for *all* consumers, not
just consumers who haven't reached for the new feature. The
naive split — directive in one phase, runtime registration in
another — would let a `@scalarType(...)`-using consumer
generate a record whose `Field<BigDecimal>` projection compiles
fine but whose synthesized schema fails to build at startup
(graphql-java cannot resolve `BigDecimal` against any registered
type). So the directive and its `additionalType` emit ship in the
same phase.

### Phase 1: shared scalar resolver + reflection engine

Lift the five hardcoded sites into a single `ScalarTypeResolver`.
Build the reflection engine that loads a
`public static final GraphQLScalarType` field off the consumer's
compile classpath, introspects the Coercing's type parameters, and
returns a `ScalarBinding(TypeName javaType, String constantFqn)`.
Behavior-neutral: the resolver still drives only the spec built-ins
through the existing hardcoded path; reflection engine exists but
isn't yet wired into name resolution.

The resolver's "Coercing's type parameters are not erased to
`Object`" guarantee carries
`@LoadBearingClassifierCheck(key = "scalar-resolver.coercing-non-erased")`
on the producing method. Phase 3's flip lands
`@DependsOnClassifierCheck` on the three downstream consumers
(R94's `InputComponent.javaType`, `ServiceCatalog`'s
`mapToJavaTypeName`, `RowsMethodShape.standardScalarJavaType`) so
a future relaxation of the erasure check ("fall back to `Object`
for unknown") becomes a global review event rather than a silent
regression in the input-record emitter.

### Phase 2: `@scalarType(scalar: "...")` directive + runtime registration

Declare `directive @scalarType(scalar: String!) on SCALAR` in
`directives.graphqls`. Classifier reads. Resolver consults
directive before convention. Each failure mode from *Failure modes*
above produces a named typed rejection through the existing
`Rejection` carrier.

Same phase emits `.additionalType(<scalar-fqn>)` into the
synthesized `GraphitronSchema` for every directive-resolved scalar,
so a `@scalarType`-declaring consumer's schema builds end-to-end
without any manual `.additionalType(...)` wiring on their side.
This requires retiring the literal block at
`GraphitronSchemaClassGenerator.java:197-201` and routing all
scalar registration (spec built-in *and* custom) through the
resolver — leaving the literal block in place would fork
source-of-truth between the resolver and the generator.

### Phase 3: convention layer + four-site flip

Wire the built-in *name → static-field-FQN* table for
graphql-java-extended-scalars. Convert the spec built-in tier to
the same reflection path against `graphql.Scalars.GraphQL{Int,
Float, String, Boolean, ID}` so the resolver has one code path,
not two. Land `@DependsOnClassifierCheck` on the input-record
emitter, the service-call binder, and `RowsMethodShape`. After
this phase the five-site hardcoded mapping is fully replaced.

### Phase 4: housekeeping

- LSP completion on `@scalarType(scalar: |)` for a `scalar X`
  declaration: suggest the convention-table FQN if the SDL name
  matches a recognized name; suggest static `GraphQLScalarType`
  fields off the consumer's classpath otherwise.
- LSP diagnostics surface the validation errors inline.
- Migration note in `changelog.md` naming the SHA where Phases 2
  and 3 land, and instructing consumers to *remove* their manual
  `.additionalType(...)` calls for any scalar graphitron now
  resolves (graphql-java's `GraphQLSchema.Builder.additionalType`
  rejects duplicate type names; see Risk).
- `code-generation-triggers.adoc` row for `@scalarType`.
- Document the resolution order in `docs/README.adoc` (or wherever
  the scalar story lives post-R9).

## Relation to R94 / R96 / R97 / R98

Strictly an enabler. R94 (`emit-input-records.md`) currently ships
with the four hardcoded scalar names; sakila uses zero custom
scalars, so R94 can land on trunk before this item ships. Real
production usability of the R94 + R96 + R97 + R98 cluster — for
Sikt's subgraphs and for any consumer using extended-scalars —
requires this item to ship.

## Tests

- `ScalarTypeResolver` unit tests, one per failure mode in
  *Failure modes*: class-not-found, field-not-found,
  field-not-public-static, field-null, field-not-scalar,
  Coercing-erased, `@scalarType`-on-spec-built-in.
- `ScalarTypeResolver` resolution-order tests: directive beats
  convention, convention beats unresolved, spec built-in beats
  directive.
- Pipeline test: a fixture SDL declaring `scalar BigDecimal` (no
  directive, extended-scalars on classpath) generates a record
  with a `BigDecimal` component and synthesizes
  `.additionalType(graphql.scalars.ExtendedScalars.GraphQLBigDecimal)`
  in the schema builder.
- Pipeline test: a fixture SDL declaring `scalar Money` with
  `@scalarType(scalar: "com.example.Scalars.MONEY")` generates a
  record with `Money` components and synthesizes
  `.additionalType(com.example.Scalars.MONEY)`.
- **Pipeline test (resolution-order interaction):** a fixture SDL
  declaring *both* `scalar BigDecimal` (no directive, falls to
  convention) *and* `scalar Money @scalarType(...)` (directive
  override) and using both in a single input record's components.
  Catches a future refactor that accidentally short-circuits one
  tier of the resolution order — the unit-tier resolver tests
  cover the resolver in isolation; this is the SDL → emitted-record
  end-to-end invariant.
- Compilation test: the generated record + synthesized schema
  compile and instantiate without the consumer's `buildSchema`
  hook touching the scalar. (The sakila example currently uses
  zero custom scalars; this needs a small new fixture in
  `graphitron-fixtures-codegen` or a dedicated sub-fixture.)
- **`ConventionTableAuditTest`:** when `graphql.scalars.ExtendedScalars`
  is on the test classpath, reflect on its public static fields
  and assert every `GraphQLScalarType` constant is covered by the
  convention table or explicitly excluded (with a reason). Turns
  the upstream-drift surface called out in *Risk* into a
  build-time signal.
- LSP test: completion on `@scalarType(scalar: |)` for a
  `scalar GraphQLBigDecimal` declaration suggests
  `graphql.scalars.ExtendedScalars.GraphQLBigDecimal` from the
  convention table.

## Risk

Low. Phase 1 is a pure refactor. Phase 2 ships the directive
together with its `additionalType` registration, so a consumer
who reaches for `@scalarType` gets a schema that builds end-to-end
in the same release that introduces the directive (no half-state
where the type resolves but the schema fails to build). Phase 3
expands the recognized set without breaking anything (names that
previously classified as `UnclassifiedType` now resolve, names that
previously resolved still resolve through the same engine).

**Duplicate `additionalType` is not idempotent in graphql-java.**
`GraphQLSchema.Builder.additionalType` rejects duplicate type names
at build time — a consumer who keeps a manual
`.additionalType(ExtendedScalars.GraphQLBigDecimal)` call after
graphitron has registered the same scalar through the resolver
will see a `SchemaProblem` / type-redefinition error from graphql-
java, not silent tolerance. Mitigation:

- Phase 2 ships with a smoke test that exercises a consumer
  schema-build with both graphitron's registration *and* a manual
  consumer-side `additionalType` for the same scalar, and asserts
  the failure mode is recognizable (so the release notes can name
  it precisely).
- Phase 4's changelog entry instructs consumers to remove the
  manual call. The migration is mechanical: any `.additionalType(s)`
  where `s` resolves through graphitron's table or directive is
  now redundant *and* an error.

The one remaining drift surface is the convention table's coverage
as `graphql-java-extended-scalars` adds scalars; the bound version
(graphitron *does not* take a runtime dep on the artifact, but
tracks for the convention table) is recorded in `docs/README.adoc`
and bumped via changelog. `ConventionTableAuditTest` (see *Tests*)
reflects this into a build-time check.
