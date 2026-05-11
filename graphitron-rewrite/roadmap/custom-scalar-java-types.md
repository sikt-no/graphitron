---
id: R101
title: "Custom-scalar Java type configuration (extended-scalars built-in)"
status: Ready
bucket: architecture
priority: 6
theme: model-cleanup
depends-on: []
---

# Custom-scalar Java type configuration (extended-scalars built-in)

Graphitron has to know the Java type behind every GraphQL scalar at
generate time: it materializes input-record component types
(R94), service-call argument types, and `Field<X>` projection types
without a runtime introspection step. Today the mapping is
hard-coded to the five spec built-ins (`Int`, `Float`, `String`,
`Boolean`, `ID`) at five sites, with three different fallback
behaviours for unknown scalars:

- `ServiceCatalog.mapToJavaTypeName` (`ServiceCatalog.java:985-991`,
  service-method parameter-name diagnostic) — switch over the five
  names, returns `null` for anything else, and the caller silently
  skips that candidate path.
- `FieldBuilder.mapGraphQLTypeToReflectType` (`FieldBuilder.java:3048-3054`,
  fetcher-side scalar reflection) — switch over the five names,
  falls back to `Object.class` silently for everything else.
- `RowsMethodShape.standardScalarJavaType` (`RowsMethodShape.java:71-79`,
  row-shape scalar coercion) — switch over the five names, returns
  `null` for everything else. The lone caller chain
  (`strictPerKeyType` → `ChildField.elementType` at
  `ChildField.java:461-468`) handles the null by falling back to
  `String` for `ScalarReturnType`, with an explicit forward-tense
  comment ("Phase A approximation, replaced when the consumer-provided
  scalar registry lands"). So this site has both a producer-side
  switch and a downstream String fallback that R101 retires together.
- `AppliedDirectiveEmitter` (`AppliedDirectiveEmitter.java:148-152`,
  SDL default-value emission via `Scalars.GraphQL{Name}`) — switch
  over the five names, falls back to `GraphQLString` for everything
  else (the line 153-156 comment names this as deliberate for
  federation-namespace late-bound types like `federation__FieldSet`).
- `GraphitronSchemaClassGenerator.java:197-201` (load-bearing for
  Phase 2: this is the *current* authority on which scalars get
  registered via `.additionalType(Scalars.GraphQLInt)`...).

`ArgBindingMap.describeKind` (`ArgBindingMap.java:198`) is *not* a
Java-type producer — it's `GraphQLType` introspection for path-walk
error messages — so it's not in the migration list, but a follow-up
pass should route its diagnostic strings through the resolver for
consistency.

The two silent fallbacks (`Object.class` in `FieldBuilder` and
`GraphQLString` in `AppliedDirectiveEmitter`) are the production
wall: a consumer who declares `scalar BigDecimal` or `scalar DateTime`
in their SDL gets a fetcher that compiles against `Object` and a
schema whose default values silently coerce as strings, without an
error pointing at the misconfigured scalar. Sikt's own subgraphs
*all* use `ExtendedScalars.GraphQLBigDecimal` and
`ExtendedScalars.DateTime`, so the rewrite is unusable in production
without a story for extra-spec scalars.

## Design: name the scalar instance, reflect the rest

The consumer points at the `public static final GraphQLScalarType`
constant they want graphitron to use for the scalar:

```graphql
scalar BigDecimal
    @scalarType(scalar: "graphql.scalars.ExtendedScalars.GraphQLBigDecimal")
```

At codegen, graphitron:

1. Loads the named class (`graphql.scalars.ExtendedScalars`) via
   `Class.forName(name, false, ctx.codegenLoader())` against the
   project-aware classloader R124 landed (now Done; see the
   [changelog](changelog.md) entry for landing SHAs). That loader is
   rooted at `project.getCompileClasspathElements()` plus every reactor
   sibling's `target/classes` and parented on the plugin loader, so a
   consumer's `<dependency>graphql-java-extended-scalars</dependency>`
   declaration is visible to the resolver without any
   `<plugin><dependencies>` boilerplate. R101 sat behind R124 in the
   dep graph because that boilerplate would otherwise have become an
   absolute requirement for any consumer using `@scalarType` or the
   convention layer; R124 lifted the same constraint for every other
   reflection callsite in the codebase, and R101 is one of its
   beneficiaries.
2. Reads the named static field (`GraphQLBigDecimal`); confirms it
   is `public static`, not null, and assignable to
   `graphql.schema.GraphQLScalarType`. The assignability check uses
   `GraphQLScalarType.class` as graphitron sees it; because R124's
   `URLClassLoader` is parented on the plugin loader,
   `GraphQLScalarType` resolves through the parent chain to a single
   `Class<?>` identity, and the assignability check has no
   classloader-isolation footgun.
3. Pulls the `GraphQLScalarType`'s `Coercing` and reflects on
   `coercing.getClass().getGenericInterfaces()` to recover the
   `Coercing<I, O>` type parameters. The `I` (input) parameter is
   the Java type input-record components, service params, and
   `Field<X>` projections bind to. The resolver normalises `I` to the
   boxed form (`int` → `Integer`, `long` → `Long`, etc.) since
   graphql-java's argument coercion produces boxed values into the
   input map regardless of the Coercing's declared parameter shape.
4. Emits `.additionalType(graphql.scalars.ExtendedScalars.GraphQLBigDecimal)`
   into the synthesized `GraphitronSchema` so the consumer's
   `Graphitron.buildSchema(...)` hook does not have to wire it.

One reference, no second source of truth. The Java type and the
runtime `Coercing` come from the same instance the consumer points
at, so the misconfiguration class an earlier `javaClass:` sketch
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

## Resolution carrier shape

Resolution returns a sealed `ScalarResolution`. Per *Builder-step
results are sealed, not strings or out-params*
(`rewrite-design-principles.adoc:61`) and *Sub-taxonomies for
resolution outcomes* (`rewrite-design-principles.adoc:47`), each
rejection arm carries the data its consumers actually need rather
than a single prose `reason` string. This precedent diverges from
R88's `AccessorResolution.Rejected(String reason)` because R88's
failures all reduce to "no candidate matched"; R101's don't.

```java
sealed interface ScalarResolution permits Resolved, Rejected {

    record Resolved(
        TypeName javaType,                  // e.g. ClassName.get(java.math.BigDecimal.class)
        ClassName scalarConstantOwner,      // e.g. ClassName.get(graphql.scalars.ExtendedScalars.class)
        String   scalarConstantField        // e.g. "GraphQLBigDecimal"
    ) implements ScalarResolution {}

    sealed interface Rejected extends ScalarResolution
        permits ClassNotFound, FieldNotFound, FieldNotAccessible,
                NullAtCodegen, NotAScalarType, CoercingErased {

        record ClassNotFound(String fqn) implements Rejected {}

        record FieldNotFound(String className, String fieldName) implements Rejected {}

        record FieldNotAccessible(
            String className, String fieldName,
            boolean isPublic, boolean isStatic
        ) implements Rejected {}

        /** public-static field that evaluates to null at codegen
         *  (an initialization side-effect the consumer must own). */
        record NullAtCodegen(String className, String fieldName) implements Rejected {}

        /** Field is public-static-non-null but not assignable to GraphQLScalarType. */
        record NotAScalarType(
            String className, String fieldName, String actualTypeFqn
        ) implements Rejected {}

        /** Coercing's I type parameter erases to Object. The declarationKind tells
         *  the user-facing message which fix to suggest (extract anonymous class,
         *  declare concrete type parameters, etc.). */
        record CoercingErased(
            String coercingClass, CoercingDeclarationKind declarationKind
        ) implements Rejected {}

        enum CoercingDeclarationKind {
            ANONYMOUS_CLASS,    // new Coercing<X, X>() { ... } — extract to a named class
            RAW_TYPE,           // declared as raw Coercing — declare concrete type parameters
            ERASED_NAMED_CLASS  // named class but parameters resolve to Object — declare concrete parameters
        }
    }
}
```

Each consumer (validator, LSP diagnostic builder, future
`InputComponent` / `ServiceCatalog` / `RowsMethodShape` users) switches
on `ScalarResolution` exhaustively. The LSP fix-its in Phase 4 use
the per-arm fields directly: `ClassNotFound` triggers a "did you mean
…" suggestion against classes on the consumer's compile classpath;
`FieldNotFound` triggers field-name completion off the named class;
`CoercingErased.ANONYMOUS_CLASS` triggers the "extract to a named
class" code action.

## Failure modes are validation errors

Two `Rejection` taxonomies are involved:

- **`Rejection.InvalidSchema.DirectiveConflict`** carries
  `@scalarType` on a spec built-in name. Structurally this is a
  directive conflict between the SDL author's `@scalarType` and the
  GraphQL spec's binding of the name; `AuthorError` (the original
  sketch's categorisation) doesn't fit because the conflict is with
  graphql-java's wiring, not with a missing catalog entry. The
  rejection is raised at directive-read time, *before* the resolver
  is invoked, so the resolver only ever sees a directive that has
  already passed this check.
- **`Rejection.AuthorError.UnknownName` / `Structural`** carries
  every `ScalarResolution.Rejected` arm. Each arm surfaces as one
  error per misconfigured scalar declaration with a per-arm prose
  message. Particular notes:

- **`CoercingErased.ANONYMOUS_CLASS`** is the most realistic case:
  Java's anonymous-class generic reflection erases inferred-from-context
  type args, so a consumer who writes `new Coercing<Money, Money>() { ... }`
  inside a static initializer sees erasure even though the source
  *looks* concrete. The fix is mechanical (extract the anonymous
  class to a named one), and the message says so explicitly.
- **`CoercingErased.RAW_TYPE`** is the consumer who declared
  `Coercing` without type parameters at all; the fix is to add
  them.
- **`CoercingErased.ERASED_NAMED_CLASS`** is a named class whose
  type parameters resolve to `Object` (declared `Coercing<Object, Object>`
  by mistake, or via `extends Coercing` without parameters); the
  message names the class and the parameter declaration site.

Erasure is a *consumer fix*, not a graphitron fallback. The original
sketch carried a `javaClass:` fallback for this case; that's dropped.
If erasure becomes a real obstacle in practice (some library ships its
Coercings raw and the consumer can't fix it upstream), we'll reopen
with a follow-up item — but the default position is "fix the
Coercing's type parameters."

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
- **Coercion-failure error shape.** Input-side coercion failures
  (a consumer hands a `String` where the resolved Java type is
  `BigDecimal`) are R94's concern, not R101's: R94's emitted
  `<Input>.fromMap(map)` factory is the slot that decides what the
  result shape looks like (e.g. `FromMapResult.{Ok | TypeMismatch}`).
  R101 only resolves *what the Java type is*, not what happens when
  a runtime value can't be coerced into it. Whichever item ships
  first carries the live wording; the second one drops the
  forward-tense reference.

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

### Phase 1: shared scalar resolver + first consumer

Build `ScalarTypeResolver` and the reflection engine that returns
`ScalarResolution.{Resolved | Rejected}` per the carrier shape above.
The reflection engine handles the spec built-ins via the same
reflection path against `graphql.Scalars.GraphQL{Int, Float, String,
Boolean, ID}`, so the resolver has a single code path from day one.

Land *one* downstream consumer on the resolver in this phase:
`RowsMethodShape.standardScalarJavaType`. It is the cheapest of the
three to verify for behavior parity: its callers route every
non-spec-built-in through an explicit downstream fallback rather
than a silent producer-side default (`ChildField.elementType` falls
back to `String` for `ScalarReturnType`; `strictPerKeyType` returns
`null` for everything else), and sakila plus the rewrite's pipeline
fixtures only exercise spec built-ins, so the resolver path produces
exactly the existing types on trunk. Phase 1 also retires the
`ChildField.elementType` String-fallback branch (the comment at
`ChildField.java:455-456` flags it as Phase A scaffolding): once the
resolver returns Java types for custom scalars, that branch is dead
and the elementType simplifies to `RowsMethodShape.strictPerKeyType`
plus the method-return-type fallback. The other producer-side
consumers (`ServiceCatalog.mapToJavaTypeName`,
`FieldBuilder.mapGraphQLTypeToReflectType`, `AppliedDirectiveEmitter`)
keep their hardcoded switches in Phase 1 and migrate in Phase 3.

Wiring the first consumer atomically with the producer means Phase 1
ships a fully-formed `@LoadBearingClassifierCheck` /
`@DependsOnClassifierCheck` pair rather than an orphan-producer
annotation that has nothing to depend on it for two phases. Per
*Validator mirrors classifier invariants*
(`rewrite-design-principles.adoc:93-97`), the contract a load-bearing
check documents should be derivable from at least one consumer the
moment the check lands.

The producer-side keys land at this phase:

- `scalar-resolver.coercing-non-erased` — the resolver only returns
  `Resolved` when the `Coercing<I, O>` type parameters are concrete;
  any consumer reading `Resolved.javaType` may assume it is not
  `Object` from a raw / erased Coercing.
- `scalar-resolver.javatype-is-typename` — `Resolved.javaType` is
  `TypeName`-shaped (boxed for primitives, parameterised correctly
  for collection types) and ready to drop into a JavaPoet
  `MethodSpec` / `RecordSpec` declaration without further coercion.

`RowsMethodShape.standardScalarJavaType` declares
`@DependsOnClassifierCheck` against both keys.

A future relaxation of either check ("fall back to `Object` for
unknown", or "return raw `Class<?>`") surfaces as orphaned consumers
across the codebase rather than a silent regression in any one
emitter.

### Phase 2: `@scalarType(scalar: "...")` directive + runtime registration

Declare `directive @scalarType(scalar: String!) on SCALAR` in
`directives.graphqls`. Per the directive-shape principle
(`rewrite-design-principles.adoc`, *Directives carry only what the
SDL author needs to say*), the argument is a flat `String!` rather
than a structured `ScalarTypeReference` input wrapper: the directive
site (`SCALAR`) already disambiguates what's being bound; only the
underlying constant name needs to flow through. Classifier reads.
Resolver consults directive before convention.

`@scalarType` on a spec built-in name produces
`Rejection.InvalidSchema.DirectiveConflict` at directive-read time,
*before* the resolver is invoked (per the carrier-shape note above).
Every other failure mode produces a typed `ScalarResolution.Rejected`
that the validator surfaces through the existing classifier
infrastructure.

Same phase emits `.additionalType(<scalar-fqn>)` into the synthesized
`GraphitronSchema` for every resolved scalar — spec built-in *and*
custom — by routing the registration through the resolver and
retiring the literal block at `GraphitronSchemaClassGenerator.java:197-201`.
Leaving the literal block in place would fork source-of-truth
between the resolver and the generator. This is a real combined load
that the Risk section calls out: a bug in the spec-built-in path
through the resolver regresses every existing consumer in this phase,
not just `@scalarType` users.

**Federation-namespace fallback decision.** `AppliedDirectiveEmitter`
(`AppliedDirectiveEmitter.java:153-156`) today falls back to
`GraphQLString` for unknown scalar names, with an explicit comment
that this is for federation-namespace late-bound types like
`federation__FieldSet` that the federation-jvm transform registers
after graphitron has emitted its schema class. R101's "no silent
fallback" rule contradicts that fallback. Phase 2 resolves it by:

1. Pre-registering the federation-namespace scalars graphitron
   knows the federation-jvm transform will inject (`federation__FieldSet`
   for `@key` / `@requires` / `@provides`, `federation__Scope` for
   `@requiresScopes`, etc.) as resolver-known names that bind to
   `GraphQLString`. The hard-coded fallback becomes a hard-coded
   recognition, with the same effective behaviour but a typed
   `ScalarResolution.Resolved` rather than an `if (unknown) return GraphQLString`
   silent path.
2. Anything *outside* that pre-registered set hits the standard
   "unresolved → `Rejection`" rule. The federation-jvm transform
   contract documents which scalars it injects; the pre-registered
   set comes from that contract.

If a future federation extension ships a new namespaced scalar that
graphitron doesn't yet recognise, the consumer sees a typed rejection
naming the scalar; the fix is a one-line addition to the federation
recognition table. This is strictly safer than the silent
`GraphQLString` fallback (which today coerces, e.g., a
`federation__Policy` enum value as a string with no error).

### Phase 3: convention layer + remaining-site flip

Wire the built-in *name → static-field-FQN* table for
graphql-java-extended-scalars. Flip the three remaining hardcoded
sites (`ServiceCatalog.mapToJavaTypeName`,
`FieldBuilder.mapGraphQLTypeToReflectType`, and the federation
recognition path inside `AppliedDirectiveEmitter`) onto the resolver,
each declaring `@DependsOnClassifierCheck` against the keys named
in Phase 1.

If R94 has already shipped by this phase, `InputComponent.javaType`
joins the consumer list with the same annotations. If R94 ships
later, R94's author adds those annotations as part of R94's input-
record-emitter work; the keys are already on trunk and the integration
is a one-line additional `@DependsOnClassifierCheck` per consumer.

After this phase the five-site hardcoded mapping is fully replaced.

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

Strictly an enabler, and order-independent with R94. R101's five
migration sites (`ServiceCatalog`, `FieldBuilder`, `RowsMethodShape`,
`AppliedDirectiveEmitter`, `GraphitronSchemaClassGenerator`) all
exist on trunk today; none of them are R94 work. R94 in turn ships
with the four hardcoded scalar names and sakila uses zero custom
scalars, so neither item blocks the other and either can land first.

Real production usability of the R94 + R96 + R97 + R98 cluster — for
Sikt's subgraphs and for any consumer using extended-scalars —
requires this item to ship; that's the value graphitron delivers
to consumers, not a sequencing constraint between roadmap items.

**Completion housekeeping.** When R101 ships, update R94's
`emit-input-records.md` so its forward-tense references to the
scalar resolver read in present tense: the `InputComponent.javaType`
comment (currently "today, the four-site hardcoded spec-built-in
mapping … post-R101, the `ScalarTypeResolver`") and the
classifier-invariants paragraph naming R101's load-bearing keys.
The cross-reference is by plan ID and concept (R101's "scalar
resolver erasure-check producer key"), not by literal key string,
so a key rename during R101's build doesn't propagate stale strings
into R94's spec. If R94 ships first, that update is a no-op — its
author folds R101's resolver into the live picture using whatever
key names R101 actually landed.

## Tests

There are no existing test pins on `mapToJavaTypeName`,
`mapGraphQLTypeToReflectType`, or `standardScalarJavaType` (verified
by grep across `graphitron/src/test`). Phase 1's lift onto the
resolver therefore needs to land its own unit-tier coverage rather
than relying on inherited assertions.

`graphql-java-extended-scalars` is added as `<scope>test</scope>` on
the `graphitron` module's pom (and on `graphitron-fixtures-codegen`
where convention-layer fixtures live); the runtime artifact stays
out, per the *Out of scope* note.

### Resolver unit tier

`ScalarTypeResolverTest`: one case per `Rejected` arm, asserting
the typed payload (not just that *some* rejection fired):

- `ClassNotFound("does.not.exist.Class")` — round-trips the
  unknown FQN.
- `FieldNotFound("graphql.Scalars", "GraphQLDoesNotExist")`.
- `FieldNotAccessible(..., isPublic=false, isStatic=true)` for a
  package-private static field; same for `isPublic=true,
  isStatic=false`.
- `NullAtCodegen(...)` for a public-static field whose initialiser
  hasn't run (rare, but real for static-fenced configs).
- `NotAScalarType(..., actualTypeFqn="java.lang.String")` for a
  field that's not a `GraphQLScalarType`.
- `CoercingErased(..., declarationKind=ANONYMOUS_CLASS)` —
  the realistic case: a fixture class exposes
  `public static final GraphQLScalarType MONEY` whose Coercing is
  declared as `new Coercing<Money, Money>() { ... }`. The test
  asserts the declaration kind so the per-arm hint resolves
  correctly.
- `CoercingErased(..., declarationKind=RAW_TYPE)` —
  fixture exposes a raw `Coercing` declaration.
- `CoercingErased(..., declarationKind=ERASED_NAMED_CLASS)` —
  fixture exposes a named class `extends Coercing` with no type
  parameters.

`ScalarResolutionOrderTest`: directive beats convention; convention
beats unresolved; `@scalarType` on a spec built-in produces
`Rejection.InvalidSchema.DirectiveConflict` at the directive-read
boundary, not at the resolver.

### Pipeline tier (primary behavioural signal)

- `customScalar_conventionResolution_emitsRecordWithBigDecimal`:
  fixture SDL declaring `scalar BigDecimal` (no directive,
  extended-scalars on the test classpath) generates a record with
  a `BigDecimal` component and synthesizes
  `.additionalType(graphql.scalars.ExtendedScalars.GraphQLBigDecimal)`.
- `customScalar_directiveResolution_emitsRecordWithCustomType`:
  fixture SDL declaring `scalar Money` with
  `@scalarType(scalar: "com.example.Scalars.MONEY")` generates a
  record with `Money` components and synthesizes
  `.additionalType(com.example.Scalars.MONEY)`.
- `customScalar_directiveBeatsConvention_inSameRecord`: fixture
  SDL declaring *both* `scalar BigDecimal` (no directive, falls to
  convention) *and* `scalar Money @scalarType(...)` (directive
  override) and using both in a single input record. Catches a
  future refactor that accidentally short-circuits one tier of
  the resolution order — the unit-tier `ScalarResolutionOrderTest`
  covers the resolver in isolation; this is the SDL →
  emitted-record end-to-end invariant.
- `customScalar_anonymousClassCoercing_failsWithExtractClassHint`:
  fixture SDL pointing at a constant whose Coercing is anonymous
  fails with the expected `CoercingErased` rejection naming the
  fixture class and instructing the consumer to extract the
  Coercing.

### Compilation + execution tiers

The existing `mvn -f graphitron-rewrite/pom.xml install -Plocal-db`
builds sakila against real jOOQ. Sakila uses zero custom scalars,
so a dedicated sub-fixture (likely `graphitron-fixtures-codegen`'s
`custom-scalar` sub-fixture) carries an end-to-end test: the
generated record + synthesized schema compile and instantiate
without the consumer's `buildSchema` hook touching the scalar.

### Convention-table drift signal

`ConventionTableArtifactDriftTest` (renamed from the original
`ConventionTableAuditTest` to make the shape clear): when
`graphql.scalars.ExtendedScalars` is on the test classpath, reflect
on its public static fields and assert every `GraphQLScalarType`
constant is covered by the convention table or explicitly excluded
(with a reason). This is a *unit-tier drift signal* — its purpose
is precisely that an `extended-scalars` version bump fails the test
and the maintainer either expands the convention table or adds
the new constant to the explicit-exclusions list. It is *not* an
emitter audit on the shape of `LoadBearingGuaranteeAuditTest`
(which audits annotations in graphitron's own bytecode); the test
class name and its javadoc make the difference explicit so the
implementer doesn't put it in the wrong directory or assert in
the wrong style.

### LSP tier

LSP test: completion on `@scalarType(scalar: |)` for a
`scalar GraphQLBigDecimal` declaration suggests
`graphql.scalars.ExtendedScalars.GraphQLBigDecimal` from the
convention table. LSP diagnostic test: an `@scalarType` reference
to a non-existent class surfaces the `ClassNotFound` rejection
inline.

## Risk

Low–medium. Phase 1 is a small additive lift (one consumer wired
to the resolver, four sites unchanged). Phase 3 expands the
recognized set without breaking anything (names that previously
fell back to `Object.class` / `null` / `GraphQLString` now resolve
through the same engine as the spec built-ins).

**Phase 2 carries the combined load.** It ships three things
together: directive-read in the classifier, new typed rejection
arms, *and* retiring the literal `additionalType` block at
`GraphitronSchemaClassGenerator.java:197-201` so all scalar
registration routes through the resolver. This is by design — see
the introductory paragraph of *Phasing* on why splitting them would
leave a half-state — but it means a bug in the spec-built-in path
through the resolver regresses every existing consumer in Phase 2,
not just `@scalarType` users. The primary mitigation is the Phase 1
+ pipeline-tier coverage: the `RowsMethodShape` consumer landed in
Phase 1 has been driving the resolver path against spec built-ins
for one phase by the time Phase 2 ships. A secondary mitigation:
Phase 2 has its own pipeline test that asserts the synthesized
schema's `.additionalType(...)` call list against the existing
five spec-built-in lines for a fixture using only spec built-ins
(no `@scalarType`, no extended-scalars). Any change to that list
is a deliberate decision that surfaces in test diff.

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
and bumped via changelog. `ConventionTableArtifactDriftTest` (see *Tests*)
reflects this into a build-time check.
