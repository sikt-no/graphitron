---
id: R303
title: Reify inline datafetchers into named XFetchers methods
status: Spec
bucket: architecture
depends-on: []
created: 2026-06-13
last-updated: 2026-06-13
---

# Reify inline datafetchers into named XFetchers methods

Most generated `DataFetcher`s for a GraphQL object type are emitted as inline value
expressions in `<Type>Type.registerFetchers(GraphQLCodeRegistry.Builder)`: lambdas
(`(env) -> env.getSource()`, the R244/R268 arm-switch ternaries, the R75/R156/R275
record-walking blocks) and `new ColumnFetcher<>(...)` instantiations, all produced by
`FetcherEmitter.dataFetcherValue`. Only the non-trivial method-backed fields resolve to a
`<Type>Fetchers::method` reference. The split means a generated datafetcher often has no
named symbol: you cannot set a breakpoint on it, name it in a stack trace, or look it up by
field. The lambdas are anonymous synthetic methods on the `…Type` class, not on the
`fetchers` package where a consumer would look.

The goal is to reify the **lambda-shaped** datafetchers into named `public static` methods on
the field's `<Type>Fetchers` class, so a lambda at a `FieldCoordinates` is replaced by a
`<Type>Fetchers::<field>` method reference that a consumer can breakpoint, find by name, and
read in a stack trace. Triviality is not a reason to keep a fetcher as a lambda; a named
method is debuggable and discoverable. This aligns with the *"Generated code is read and
debugged"* principle (`rewrite-design-principles.adoc`), which prefers a named helper method
over an inline expression. The seam is `FetcherEmitter` (which produces the registration value
today) plus `TypeFetcherGenerator.generateTypeSpec` (which already emits the heavy fetcher
methods the existing method-backed variants reference).

This Spec deliberately scopes the change to lambdas on top-level types and leaves three forms
alone, each for a principle-grounded reason recorded under [Non-goals](#non-goals): the
`ColumnFetcher` / `PropertyDataFetcher` value forms (already named classes; reifying
`ColumnFetcher` would discard graphql-java's `LightDataFetcher` fast-path), the shared
connection/edge/`@error` helper references (already named static methods), and nested-type
fields (no `<Type>Fetchers` class exists for them today, so reifying them is a separable
class-proliferation problem).

## Audit findings

`FetcherEmitter.dataFetcherValue` / `dataFetcherValueRaw`
(`graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/FetcherEmitter.java`) produces
exactly one registration value per field, in one of these shapes:

| Shape | Variants (emitted form) | R303 |
|---|---|---|
| Single-expression lambda | `ConstructorField`, `NestingField` (`(env) -> env.getSource()`); single-`TableField` first-row read; `ErrorsField` LocalContext (`env -> env.getLocalContext()`) and WrapperArm (`env -> env.getSource() instanceof ErrorList<?> ...`); `PropertyField` / `RecordField` on a **class-backed** parent (accessor read off `env.getSource()`) | **Reify** |
| Multi-line lambda block | `SingleRecordTableField`, `SingleRecordIdField`, `SingleRecordIdFieldFromReturning` (record walk + SELECT / NodeId encode); the `ColumnField` and `CompositeColumnField` NodeId-encode lambdas; the R244/R268 arm-switch ternary (`armSwitchedInlineDataFetcher`) | **Reify** |
| `new ColumnFetcher<>(...)` | plain `ColumnField`, `LookupTableField`, `ComputedField`, `ColumnReferenceField` (Direct), `ParticipantColumnReferenceField`, `PropertyField` / `RecordField` on a **jOOQ-record** parent | Leave |
| `PropertyDataFetcher.fetching(name)` | `ErrorsField` PayloadAccessor transport | Leave |
| `<Type>Fetchers::<field>` reference | every method-backed variant (`QueryTableField`, `ServiceTableField`, `RecordTableField`, the `Mutation*` carriers, ...) via the fall-through at `FetcherEmitter.java:416` | Already reified |

The same variant can take different shapes by parent backing: `PropertyField` / `RecordField`
emit a `ColumnFetcher` for a jOOQ-record parent but an accessor **lambda** for a class-backed
parent (`propertyOrRecordValue`, `FetcherEmitter.java:886`). So the reify decision keys on the
**emitted value shape**, not the field class. This is why the change lives in `FetcherEmitter`
(which already computes the shape) and not in a field-class partition.

**Dispatch partition is unaffected.** `TypeFetcherGenerator` carries a four-way disjoint
partition over every `GraphitronField` leaf (`IMPLEMENTED_LEAVES`, `PROJECTED_LEAVES`,
`NOT_DISPATCHED_LEAVES`, `STUBBED_VARIANTS.keySet()`, `TypeFetcherGenerator.java:178-295`),
enforced exhaustive-and-disjoint by `GeneratorCoverageTest`. The reified variants today sit in
that partition with an explicit no-method arm: the `-> { }` arms at
`TypeFetcherGenerator.java:465-548` (`ColumnField`, `ConstructorField`, `NestingField`,
`PropertyField`, `RecordField`, `ErrorsField`, the three `SingleRecord*` carriers, …) and the
`PROJECTED_LEAVES` (`TableField`, `CompositeColumnField`, …). The partition keys on class
identity, not on "method vs no-method"; R303 changes what those arms *emit* (a reified method
instead of a no-op) without moving any class between sets. The `GeneratorCoverageTest` and
`ValidateMojo` keys are therefore untouched.

**Two-gate nested coupling (the reason nested types are deferred).** A nested object type gets
a `<Type>Fetchers` class only when it has a `BatchKeyField` leaf, and that gate is encoded at
**two** sites that must agree: `FetcherRegistrationsEmitter.nestedBody` (`:120-121`) decides
whether the wiring references a class, and `TypeFetcherGenerator.collectNestedFetcherClasses`
(`:135`) decides whether the class is emitted. Reifying nested lambdas would mean widening both
gates to "has any reifiable fetcher" in lockstep (or one emits references into a class the
other never generated). Combined with new-class proliferation for every nested object type,
this is a separable problem; R303 leaves nested fields on their inline lambdas and files a
follow-up.

## Decision

Reify the two lambda shapes (single-expression and multi-line block) into named methods on the
`<Type>Fetchers` class of the field's **top-level** owning type (`TableType`, `NodeType`,
`RootType`, `ResultType`), which already has a `<Type>Fetchers` class emitted unconditionally by
`TypeFetcherGenerator.generate` (`:100-109`). Leave the `ColumnFetcher`, `PropertyDataFetcher`,
shared-helper, and nested forms as they are (see Non-goals).

## Implementation

### Single home for the reify decision (`FetcherEmitter`)

Replace `FetcherEmitter`'s "return a value `CodeBlock`" contract with a sealed binding so the
field-name-to-method-name derivation lives in exactly one place and the registration value and
the method declaration cannot drift:

```java
public sealed interface FetcherBinding {
    /** The expression the {@code codeRegistry.dataFetcher(coords, ...)} call receives. */
    CodeBlock registrationValue();

    /** Emitted inline at the registration site; no method on the Fetchers class.
     *  Covers ColumnFetcher, PropertyDataFetcher, and the existing method-backed reference. */
    record Inline(CodeBlock registrationValue) implements FetcherBinding {}

    /** Reified into a named static method on {@code <Type>Fetchers}; the registration site
     *  emits {@code registrationValue()} == the method reference. */
    record Reified(MethodSpec method, CodeBlock registrationValue) implements FetcherBinding {}
}

public static FetcherBinding bind(
        GraphitronField field, ClassName fetchersClass, TableRef parentTable,
        GraphitronType.ResultType resultType, String outputPackage, boolean sourceIsOutcome);
```

`bind` subsumes today's `dataFetcherValue` (including the `sourceIsOutcome` arm-switch fork).
For the two lambda shapes it returns `Reified`, where `method` is named `field.name()`,
declared `public static Object <field>(DataFetchingEnvironment env)`, and `registrationValue`
is `CodeBlock.of("$T::$L", fetchersClass, field.name())`. For every other shape it returns
`Inline` with today's value expression verbatim (the method-backed fall-through becomes an
`Inline` carrying the `::` reference; the method stays owned by `TypeFetcherGenerator`'s switch).

Mechanically: the existing lambda-producing helpers (`armSwitchedInlineDataFetcher`,
`buildSingleRecordTableFetcherValue` and siblings, the NodeId-encode `ColumnField` /
`CompositeColumnField` blocks, the `NestingField` / `ConstructorField` passthrough, the single
`TableField` read, the `ErrorsField` LocalContext / WrapperArm arms, and the class-backed arm
of `propertyOrRecordValue`) are refactored to emit a **method body** (statement form:
`return <expr>;` or the existing block with the `($T env) -> {` / `}` wrapper dropped) rather
than a lambda. `bind` wraps that body in the `MethodSpec`. The `ColumnFetcher` /
`PropertyDataFetcher` branches are untouched.

### Registration site (`FetcherRegistrationsEmitter`)

`registrationEntry` calls `FetcherEmitter.bind(...)` and emits `.registrationValue()`; no
shape switch is needed, since `Inline` and `Reified` both answer `registrationValue()`. No other
change; the connection/edge/`@error` paths are untouched.

### Method collection (`TypeFetcherGenerator.generateTypeSpec`)

In the existing per-field loop, alongside the variant switch, add one line:

```java
if (FetcherEmitter.bind(field, fetchersClass, parentTable, resultType, outputPackage, sourceIsOutcome)
        instanceof FetcherEmitter.Reified r) {
    builder.addMethod(r.method());
}
```

`bind` returns `Reified` only for the lambda variants, which are exactly the `-> { }` no-op
arms and the projected lambda-backed leaves; the heavy-method variants return `Inline`, so
there is no double-emission. The switch's arms stay as they are (they still emit the heavy
methods and the `ColumnFetcher` no-ops); only their stale `/* wired via … env -> env.getSource() */`
comments (`:465-548`) are updated to point at `FetcherEmitter.bind` reifying a named method.

### Comment / javadoc hygiene

`FetcherEmitter`'s class javadoc (`:33-36`, "the value expression: a method reference, lambda,
or `new ColumnFetcher<>(...)`") flips to describe the `FetcherBinding` contract. The no-op-arm
comments in `TypeFetcherGenerator` (`:465-548`) are corrected in the same change so the live
documentation does not describe wiring that no longer exists.

## Opportunity (within scope, implementer's call)

The arm-switch reification (`armSwitchedInlineDataFetcher`, `FetcherEmitter.java:162-166`) emits
`(env) -> env.getSource() instanceof Success<?> success ? <read> : null`, a ternary with an
`instanceof` and a `Success<?>` **wildcard** capture. Reifying it to statement form is the
single highest-value win here (it is precisely the un-breakpointable expression the readability
principle targets). The method body already has the element type in hand for class-backed and
jOOQ-record parents, so it can route through the same typed `Success<elementType>` narrowing
that `emitRecordSourceLocal` (`:240-251`) already uses for the method-backed carriers, dropping
the `<?>` capture and converging the two narrowing shapes. Where the element type is not cleanly
available, a statement-form `if (!(env.getSource() instanceof Outcome.Success<?> success)) return null;`
is still strictly better than the inline ternary. Either lands the readability win; the typed
convergence is the bonus.

## Tests

R303 is behaviour-preserving: the generated resolvers do the same work, relocated from lambdas
to named methods. The signals it landed cleanly:

* `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` stays green end-to-end. The
  **compile-spec** tier (sakila-example, `<release>17</release>`) is the structural backstop:
  it compiles the generated `<Type>Fetchers` classes and the `registerFetchers` method
  references against the sakila catalog, so a mis-named method or an unresolved reference fails
  the build. The **execute-spec** tier proves the reified methods behave identically at runtime.
* `FetcherPipelineTest` wiring-shape assertions flip from `LAMBDA` to `METHOD_REFERENCE` and the
  "no methods" assertions gain the reified method (these are the live pins the change moves):
  * `propertyField_onBackedRecord_usesAccessorLambda`: `wiringFor("Container", "value")`
    becomes `METHOD_REFERENCE`; rename the test accordingly.
  * `propertyField_onRecordType_fetchersHasNoMethods` and
    `recordField_onRecordType_fetchersHasNoMethods`: `ContainerFetchers` /
    `FilmDetailsFetchers` now carry the reified `value` / `stats` method; assert its presence
    instead of `methodSpecs().isEmpty()`.
  * the R268 arm-switch pin (`FilmRecordPayload.title`) becomes `METHOD_REFERENCE`, with the
    reified `title` method present on `FilmRecordPayloadFetchers`.
* `TypeSpecAssertions.wiringFor` and its `DataFetcherKind.LAMBDA` value are kept: nested-type
  fields (deferred) still wire as lambdas, so the classifier stays live.

Per `rewrite-design-principles.adoc`, no `CodeBlock`-string-equality unit assertion on the
reified method bodies; the kind classification plus the compile/execute tiers carry the proof.

## Validator mirror

No new classifier branch and no new emitter disposition. `FetcherEmitter.resolvesViaPropertyDataFetcher`
(the R268 PropertyDataFetcher-escape predicate the validator consults) is unchanged because the
PayloadAccessor `PropertyDataFetcher` form is explicitly *not* reified. `hasWrapperArmErrors`
(the `sourceIsOutcome` source) is unchanged. No validator rule is added or moved.

## Non-goals

* **Reifying `ColumnFetcher` (and `PropertyDataFetcher`).** `ColumnFetcher` implements
  graphql-java's `LightDataFetcher` (`TypeFetcherGenerator.java:66-67`,
  `FetcherEmitter.java:74-79`); the light path skips per-field-per-row `DataFetchingEnvironment`
  allocation, and column reads are the highest-cardinality fetcher in a wide-table response.
  Reifying `new ColumnFetcher<>(FILM.TITLE)` into `static Object title(env) { return ((Record)
  env.getSource()).get(FILM.TITLE); }` would force every column onto the heavy path to buy a
  per-field symbol it mostly already has: a `ColumnFetcher` is a *named class* with a
  breakpointable `get`, not an anonymous lambda, so the "method not lambda" rule is satisfied in
  spirit. A `static` method reference cannot recover the light dispatch, so there is no
  have-both option. `PropertyDataFetcher.fetching(name)` is likewise a named graphql-java class.
  Both stay as value forms; registration is therefore not uniformly `::` references, which is
  fine: it is non-uniform today and uniformity is not itself a principle.
* **Nested-type fields.** Deferred per the two-gate coupling and class-proliferation above; see
  Roadmap entries.
* **Shared connection/edge/`@error` helper references.** `ConnectionHelper::edges`,
  `::edgeCursor`, etc. (`FetcherRegistrationsEmitter.java:140-169`) and the `@error`-type
  `path` / `message` fetchers in `GraphitronSchemaClassGenerator` are already named static
  methods on a shared, hand-auditable class. A single shared breakpoint covers every connection;
  fanning them into per-type `<Type>Fetchers::field` copies would multiply identical bodies for
  no legibility gain. Left as a deliberate boundary.
* **De-duplicating the heavy-method reference derivation.** Method-backed variants still derive
  their `::` reference at `FetcherEmitter.java:416` while `TypeFetcherGenerator` independently
  names the method `field.name()`. This pre-existing single-fall-through coupling is untouched
  (R303 does not widen it: the reified variants get both halves from `bind`). Folding it in is
  out of scope.

## Roadmap entries

File one follow-up Backlog item after R303 lands: **reify nested-type inline datafetchers**,
covering the `FetcherRegistrationsEmitter.nestedBody` / `TypeFetcherGenerator.collectNestedFetcherClasses`
two-gate widening and the resulting `<Type>Fetchers`-per-nested-type proliferation.
