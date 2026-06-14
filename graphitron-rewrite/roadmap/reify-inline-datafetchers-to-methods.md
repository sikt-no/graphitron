---
id: R303
title: Reify inline datafetchers into named XFetchers methods
status: In Review
bucket: architecture
depends-on: []
created: 2026-06-13
last-updated: 2026-06-14
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

The governing principle: **every datafetcher is a named `public static` method on the
corresponding `<Type>Fetchers` class**, so the registration site is uniformly a
`<Type>Fetchers::<field>` method reference, for every object type that owns fetchers (root,
table, node, result, nested, connection, edge, and error types). A consumer debugging
`FilmConnection.edges` looks for `FilmConnectionFetchers.edges`; no read logic is left inline in a
lambda or buried in a bare `ColumnFetcher(column)` value. Where a `LightDataFetcher` wrapper is
still needed at the registration site, it wraps a named `<Type>Fetchers` method
(`new LightFetcher<>(FilmFetchers::title)`), so the read itself is always a findable symbol.
Triviality is not a reason to keep a fetcher off its class; a named method is debuggable and
discoverable. This
aligns with the *"Generated code is read and debugged"* principle (`rewrite-design-principles.adoc`),
which prefers a named helper method over an inline expression. The seam is `FetcherEmitter`
(which produces the registration value today) plus `TypeFetcherGenerator.generateTypeSpec`
(which already emits the heavy fetcher methods the method-backed variants reference), extended
to cover the nested, connection, and edge classes.

There is no runtime trade. The light-fetcher class (renamed `ColumnFetcher` → `LightFetcher`, see
below) stays a `LightDataFetcher`; rather than register a bare method reference (which could only
be a plain `DataFetcher`), the column read becomes a named method on `<Type>Fetchers` that
`LightFetcher` wraps, so the env-skipping fast-path is preserved
while the read gains a per-field symbol (see
[Light path: wrap the read, don't register the bare reference](#light-path-wrap-the-read-dont-register-the-bare-reference)).

## Audit findings

`FetcherEmitter.dataFetcherValue` / `dataFetcherValueRaw`
(`graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/FetcherEmitter.java`) produces
exactly one registration value per field, in one of these shapes:

| Shape | Variants (emitted form) | R303 |
|---|---|---|
| Single-expression lambda | `ConstructorField`, `NestingField` (`(env) -> env.getSource()`); single-`TableField` first-row read; `ErrorsField` LocalContext (`env -> env.getLocalContext()`) and WrapperArm (`env -> env.getSource() instanceof ErrorList<?> ...`); `PropertyField` / `RecordField` on a **class-backed** parent (accessor read off `env.getSource()`) | **Reify** |
| Multi-line lambda block | `SingleRecordTableField`, `SingleRecordIdField`, `SingleRecordIdFieldFromReturning` (record walk + SELECT / NodeId encode); the `ColumnField` and `CompositeColumnField` NodeId-encode lambdas; the R244/R268 arm-switch ternary (`armSwitchedInlineDataFetcher`) | **Reify** |
| `new ColumnFetcher<>(...)` | plain `ColumnField`, `LookupTableField`, `ComputedField`, `ColumnReferenceField` (Direct), `ParticipantColumnReferenceField`, `PropertyField` / `RecordField` on a **jOOQ-record** parent | **Reify** (light, wrapped read) |
| `PropertyDataFetcher.fetching(name)` | `ErrorsField` PayloadAccessor transport | **Deferred → R304** (see [Validator mirror](#validator-mirror)) |
| `<Type>Fetchers::<field>` reference | every method-backed variant (`QueryTableField`, `ServiceTableField`, `RecordTableField`, the `Mutation*` carriers, ...) via the fall-through at `FetcherEmitter.java:416` | Already reified |

The connection/edge field fetchers (`ConnectionHelper::edges` etc., wired by
`FetcherRegistrationsEmitter`) and the `@error`-type `path` / `message` fetchers (wired inline by
`GraphitronSchemaClassGenerator`) are not in `FetcherEmitter`'s shape table above, but fall under
the same principle and are reified onto per-type classes (see [Decision](#decision)).

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

**Two-gate nested coupling.** A nested object type today gets a `<Type>Fetchers` class only when
it has a `BatchKeyField` leaf, and that gate is encoded at **two** sites that must agree:
`FetcherRegistrationsEmitter.nestedBody` (`:120-121`) decides whether the wiring references a
class, and `TypeFetcherGenerator.collectNestedFetcherClasses` (`:135`) decides whether the class
is emitted. Under the uniform principle both gates widen to "has any fetcher" in lockstep (a
nested object type owning any reifiable field gets a class and references into it). The two
sites already evaluate the same predicate, so this Spec lifts it into one shared helper
(`nestedTypeOwnsFetchers(nestedFields)`) that both call, closing the drift hazard rather than
widening two copies. Every nested object type that owns a fetcher now gets a `<Type>Fetchers`
class; the proliferation is the intended outcome (findability), not a cost to avoid.

## Decision

Reify every fetcher onto a named `public static` method on its owning type's `<Type>Fetchers`
class, so registration is uniformly `<Type>Fetchers::<field>`. Concretely:

* **Top-level types** (`TableType`, `NodeType`, `RootType`, `ResultType`) already have a
  `<Type>Fetchers` class emitted unconditionally by `TypeFetcherGenerator.generate` (`:100-109`);
  the lambda, `ColumnFetcher`, and `PropertyDataFetcher` shapes there all become methods on it.
* **Nested object types** get a `<Type>Fetchers` class whenever they own any fetcher (the
  two-gate widening above), not only when they carry a `BatchKeyField`.
* **Connection / edge types** get `<Conn>Fetchers` / `<Edge>Fetchers` classes whose methods
  (`edges`, `nodes`, `pageInfo`, `totalCount`; `node`, `cursor`) **delegate** to the shared
  `ConnectionHelper`. The generic pagination logic stays in `ConnectionHelper` (one home); the
  per-type methods are thin named delegates so the symbol a consumer looks up
  (`FilmConnectionFetchers::edges`) lives on the connection's own class.
* **`@error` types** get their `path` / `message` fetchers reified onto `<ErrorType>Fetchers`
  instead of inline in `GraphitronSchemaClassGenerator`.

Every fetcher becomes a named method on its `<Type>Fetchers` class; the registration value is
either the method reference directly or a `LightDataFetcher` wrapping it (see below). There is no
behavioural trade: the change is a results-preserving relocation of identical logic to named
methods, and it *extends* the light path rather than forfeiting it.

### Light path: wrap the read, don't register the bare reference

A method reference `<Type>Fetchers::<field>` registered directly is a plain `DataFetcher`:
`LightDataFetcher` is not a functional interface (it has two abstract methods, `get(env)` and
`get(fieldDef, source, dfeSupplier)`), so the executor would fall back to the heavy `get(env)`
entry and construct a per-field-per-row `DataFetchingEnvironment`. The fix is not to register the
bare reference but to **wrap it in `LightFetcher`** (the renamed, generalised `ColumnFetcher`),
which stays the `LightDataFetcher`:

```java
// util.LightFetcher<T> implements LightDataFetcher<T>, holding a source-read function
@FunctionalInterface public interface Read<T> { T apply(Object source); }
public T get(DataFetchingEnvironment env) { return read.apply(env.getSource()); }            // heavy entry
public T get(GraphQLFieldDefinition f, Object src, Supplier<DataFetchingEnvironment> s) {     // light entry
    return read.apply(src);                                                                   // supplier untouched
}

// fetchers.FilmFetchers
public static String title(Object source) { return ((Record) source).get(Tables.FILM.TITLE); }

// schema.FilmType.registerFetchers
.dataFetcher(coordinates("Film", "title"), new LightFetcher<>(FilmFetchers::title))
```

The read is a per-field named method on `<Type>Fetchers` (breakpointable, findable, in stack
traces) and the registered value is still a `LightDataFetcher`, so the env-skipping path is kept.
This replaces the old `new ColumnFetcher<>(Tables.FILM.TITLE)` (column-valued) with
`new LightFetcher<>(FilmFetchers::title)` (read-function-valued); the jOOQ column constant moves
from the registration site into the named method. The class is renamed `ColumnFetcher` →
`LightFetcher` (it no longer holds a column, only a read function) and its constructor changes
from a `Field<T>` to a `Read<T>`.

The dividing line is what the read needs:

* **Source-only reads** (plain `ColumnField`, lookup / computed / reference columns, the
  NodeId-encode columns, the single-`TableField` first-row read, the `Outcome` arm-switch, the
  `WrapperArm` errors read, and zero-arg class-backed accessors): the read is a function of
  `env.getSource()` alone, so it goes through the wrapper, `new LightFetcher<>(Fetchers::field)`,
  and stays light. Several of these are heavy lambdas today; wrapping them is a (non-observable)
  improvement, not a regression.
* **Env-dependent reads** (the `SingleRecord*` response SELECTs that need the `DSLContext` and
  selection set, env-injecting accessors that read `env.getArgument(...)` or take the full `env`,
  and the `LocalContext` errors read): these genuinely need the environment, so they stay a
  direct heavy `Fetchers::field` reference whose method takes `DataFetchingEnvironment env`.

Both forms put a named method on `<Type>Fetchers`; only the registration wrapper differs.

## Implementation

### Single home for the reify decision (`FetcherEmitter`)

Replace `FetcherEmitter`'s "return a value `CodeBlock`" contract with a sealed binding so the
field-name-to-method-name derivation lives in exactly one place and the registration value and
the method declaration cannot drift:

```java
public sealed interface FetcherBinding {
    /** The expression the {@code codeRegistry.dataFetcher(coords, ...)} call receives. */
    CodeBlock registrationValue();

    /** No method emitted here; the method is owned by {@code TypeFetcherGenerator}'s switch
     *  (the existing heavy method-backed variants) and {@code bind} carries the `::` reference. */
    record Inline(CodeBlock registrationValue) implements FetcherBinding {}

    /** Reified into a named static method on {@code <Type>Fetchers}. {@code registrationValue}
     *  is either the bare reference {@code Fetchers::field} (env-dependent read) or the light
     *  wrapper {@code new LightFetcher<>(Fetchers::field)} (source-only read). */
    record Reified(MethodSpec method, CodeBlock registrationValue) implements FetcherBinding {}
}

public static FetcherBinding bind(
        GraphitronField field, ClassName fetchersClass, TableRef parentTable,
        GraphitronType.ResultType resultType, String outputPackage, boolean sourceIsOutcome);
```

`bind` subsumes today's `dataFetcherValue` (including the `sourceIsOutcome` arm-switch fork). For
every shape that owns its body here it returns `Reified` with a `method` named `field.name()`:

* **source-only** reads get a `public static <T> <field>(Object source)` method (body reads off
  `source`) and `registrationValue` `CodeBlock.of("new $T<>($T::$L)", columnFetcher, fetchersClass, field.name())`;
* **env-dependent** reads get a `public static Object <field>(DataFetchingEnvironment env)` method
  and `registrationValue` `CodeBlock.of("$T::$L", fetchersClass, field.name())`.

It returns `Inline` only for the method-backed fall-through, where the method is owned by
`TypeFetcherGenerator`'s switch and `bind` carries the `::` reference unchanged.

Mechanically, every body-producing branch is refactored to emit a **method body** (statement
form: `return <expr>;`, or the existing block with the `($T env) -> {` / `}` wrapper dropped)
that `bind` wraps in the `MethodSpec`. The source-only branches take `(Object source)`; the
env-dependent branches take `(DataFetchingEnvironment env)`:

* **source-only, env-dependent → heavy method:** `buildSingleRecordTableFetcherValue` and
  siblings (need the `DSLContext` / selection set) and the `LocalContext` errors read
  (`env.getLocalContext()`) become `(DataFetchingEnvironment env)` methods referenced directly;
  env-injecting accessors in `propertyOrRecordValue` / `methodCallValue` (the `name(env)` and
  per-argument `env.getArgument(...)` forms) likewise stay env-typed;
* **source-only → wrapped light method:** the column-read branches (plain `ColumnField`,
  `LookupTableField`, `ComputedField`, `ColumnReferenceField` Direct,
  `ParticipantColumnReferenceField`, and the jOOQ-record arm of `propertyOrRecordValue`) become
  `(Object source)` methods bodied `return ((Record) source).get(<column-or-DSL.field>);`; the
  NodeId-encode `ColumnField` / `CompositeColumnField` blocks, the single `TableField` first-row
  read, the `Outcome` arm-switch (`armSwitchedInlineDataFetcher`), the `WrapperArm` errors read,
  the `NestingField` / `ConstructorField` passthrough, and zero-arg class-backed accessors all
  become `(Object source)` methods too. The light-fetcher class is **kept but renamed**
  `ColumnFetcher` → `LightFetcher`: its constructor changes from `Field<T>` to `Read<T>` (a
  `T apply(Object source)` SAM), and `ColumnFetcherClassGenerator` is renamed
  `LightFetcherClassGenerator` and emits that shape. `bind` wraps each source-only method as
  `new LightFetcher<>(Fetchers::field)`;
* the `ErrorsField` PayloadAccessor branch (today `PropertyDataFetcher.fetching(name)`):
  **deferred to R304.** The intended reification reads the errors list through the same
  `recordBackedAccessorRead` helper the class-backed path uses, but that helper needs an
  `AccessorResolution.Resolved`, and `ChildField.ErrorsField` / `Transport.PayloadAccessor` carry
  none: the read is left to graphql-java's runtime reflection by design. Resolving it at generation
  time is a classifier change (resolve the errors accessor in `FieldBuilder.liftToErrorsField`),
  which this Spec scoped out ("the classifier model is untouched"). Reaching past the parse boundary
  with emit-time reflection would violate the classification-at-parse-boundary principle, so the
  `PayloadAccessor` arm stays `Inline(PropertyDataFetcher.fetching(name))` and R304 carries the
  classifier-backed reification plus the R268 reconciliation (see [Validator mirror](#validator-mirror)).

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

`bind` returns `Reified` for exactly the variants the switch currently handles with a `-> { }`
no-op arm (`ColumnField`, `ConstructorField`, `NestingField`, `PropertyField`, `RecordField`,
`ComputedField`, `ErrorsField`, `ParticipantColumnReferenceField`, the three `SingleRecord*`
carriers) and the `PROJECTED_LEAVES` (`TableField`, `LookupTableField`, `ColumnReferenceField`,
`CompositeColumnField`); the heavy-method variants return `Inline`, so there is no
double-emission. The switch's arms stay as they are (they still emit the heavy methods and the
projection-only `$fields` handling); only their stale `/* wired via … env -> env.getSource() */`
comments (`:465-548`) are updated to point at `FetcherEmitter.bind` reifying a named method.

### Nested-type fetcher classes (`FetcherRegistrationsEmitter` / `TypeFetcherGenerator`)

Lift the `BatchKeyField`-only gate into a shared `nestedTypeOwnsFetchers(List<ChildField>)`
predicate (true when the nested type owns any field that `bind` resolves to a fetcher), and call
it from both `FetcherRegistrationsEmitter.nestedBody` (`:120-121`, to reference the class) and
`TypeFetcherGenerator.collectNestedFetcherClasses` (`:135`, to emit it). `nestedBody`'s per-field
loop drops its inline-value branch and emits `<NestedType>Fetchers::<field>` via `bind`, exactly
like the top-level `buildBody`; `collectNestedFetcherClasses` calls `generateTypeSpec` for every
nested type the predicate accepts, not only the `BatchKeyField`-bearing ones.

### Connection and edge fetcher classes

`FetcherRegistrationsEmitter.connectionBody` / `edgeBody` (`:140-169`) today wire
`ConnectionHelper::edges` (and `::nodes`, `::pageInfo`, `::totalCount`, `::edgeNode`,
`::edgeCursor`). Generate a `<Conn>Fetchers` class (and `<Edge>Fetchers`) carrying one
`public static` delegate per field, e.g. `static Object edges(DataFetchingEnvironment env) {
return ConnectionHelper.edges(env); }`, and rewire the registration to `<Conn>Fetchers::edges`.
`ConnectionHelper` keeps the generic pagination logic (single home, hand-auditable); the per-type
class exists only to give the field its own lookup symbol. `totalCount` keeps its existing
SDL-presence gate. A small emitter (sibling to the existing helper-class generators under
`generators/util`) produces these delegate classes from the connection/edge type entries the
classifier already populates.

### Error-type fetcher classes

The `@error`-type `path` / `message` fetchers built inline by
`GraphitronSchemaClassGenerator.buildErrorTypeFieldFetchers` (`:471-506`) move to a
`<ErrorType>Fetchers` class with `path` / `message` methods; `GraphitronSchemaClassGenerator`
wires `<ErrorType>Fetchers::path` / `::message` in place of the inline lambdas. The error
type-resolver lambdas for interfaces/unions stay where they are (they are `TypeResolver`s, not
`DataFetcher`s, and out of this principle's scope).

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
* The registration value is now a `METHOD_REFERENCE` (env-dependent reads) or a `COLUMN_FETCHER`
  wrapping a method reference (source-only reads) for every reified shape; the only survivors are
  the `@error` PayloadAccessor `PropertyDataFetcher` (deferred to R304) and the validator-rejected
  `CompositeColumnReferenceField` throwing-lambda stub. The `FetcherPipelineTest` wiring-shape
  assertions and the "no methods" assertions move accordingly (these are the live pins the change
  touches):
  * `propertyField_onRecordType_fetchersHasNoMethods` and
    `recordField_onRecordType_fetchersHasNoMethods`: `ContainerFetchers` / `FilmDetailsFetchers`
    now carry the reified `value` / `stats` method; assert its presence instead of
    `methodSpecs().isEmpty()` (renamed `_reifiesReadMethod`).
  * `propertyField_onBackedRecord_usesAccessorLambda`: `wiringFor("Container", "value")` is a
    `COLUMN_FETCHER` (source-only zero-arg accessor, wrapped) rather than a `LAMBDA`; renamed
    `_wrapsAccessorReadInLightFetcher`, and checks `ContainerFetchers` carries the `value` read method.
  * the R268 arm-switch pin (`FilmRecordPayload.title`): a source-only read, so a
    `COLUMN_FETCHER` wrapping `FilmRecordPayloadFetchers::title`; the method's presence is asserted.
  * `LAMBDA` wiring assertions flip to `METHOD_REFERENCE` (env-dependent) or `COLUMN_FETCHER`
    (source-only) per the read; existing `COLUMN_FETCHER` column pins stay `COLUMN_FETCHER` and
    the `producesNoFetcherMethod` pins (TableField / LookupTableField / ColumnReferenceField /
    ColumnField) flip to assert the reified read method now exists on the class.
* `COLUMN_FETCHER` stays live (it now wraps a method reference, matched on `LightFetcher`) and
  `METHOD_REFERENCE` stays live. `DataFetcherKind.LAMBDA` and `PROPERTY_FETCHER` stay reachable
  (the CompositeColumnReferenceField stub and the deferred PayloadAccessor respectively), so the
  dead-arm removal the original Spec anticipated is itself deferred to R304.
* New per-type classes appear and are asserted by the existing "class is emitted" style:
  `<Conn>Fetchers`, `<Edge>Fetchers`, `<ErrorType>Fetchers`, and a `<NestedType>Fetchers` for a
  nested type with no `BatchKeyField` (the case that produced no class before).
* The light path is preserved: the wrapped reads register a `LightDataFetcher`. An optional unit
  assertion can pin that a column wiring value is a `LightFetcher` (i.e. `instanceof
  LightDataFetcher` at runtime) rather than a bare method reference, guarding the env-skip
  property against an accidental flip to a direct `::` reference.

Per `rewrite-design-principles.adoc`, no `CodeBlock`-string-equality unit assertion on the
reified method bodies; the kind classification plus the compile/execute tiers carry the proof.
The connection/edge delegate bodies are exercised end-to-end by the existing pagination
execution-tier coverage, unchanged.

## Validator mirror

No new classifier branch is introduced; the classifier model is untouched, and as implemented
R303 has **no** validator-mirror consequence. The one consequence the Spec anticipated, in the
`PropertyDataFetcher` reification, did not land: reifying the PayloadAccessor errors field needs a
generation-time resolved accessor that `ChildField.ErrorsField` / `Transport.PayloadAccessor` do
not carry, and producing one is a classifier change this Spec scoped out. So the PayloadAccessor
arm stays `Inline(PropertyDataFetcher.fetching(name))`, the `PropertyDataFetcher` emit path
survives, and `FetcherEmitter.resolvesViaPropertyDataFetcher` (`:85-89`) plus the R268
`validateOutcomeChildArmSwitch` rule are **unchanged** (no reconciliation needed while the path it
guards still exists). The classifier-backed reification and the predicate/rule reconciliation move
to **R304**. `hasWrapperArmErrors` (the `sourceIsOutcome` source) is unchanged.

## Non-goals

* **`ConnectionHelper`'s generic logic is not inlined per type.** The connection/edge per-type
  classes *delegate* to `ConnectionHelper`; the pagination logic stays in one home. Copying the
  bodies into every `<Conn>Fetchers` would multiply identical code for no gain.
* **Interface / union `TypeResolver` lambdas.** The `codeRegistry.typeResolver(name, env -> ...)`
  lambdas in `GraphitronSchemaClassGenerator` (`:139`, `:169`) are `TypeResolver`s, not
  `DataFetcher`s; "every datafetcher is a named method" does not reach them. Out of scope.
* **De-duplicating the heavy-method reference derivation.** Method-backed variants still derive
  their `::` reference at `FetcherEmitter.java:416` while `TypeFetcherGenerator` independently
  names the method `field.name()`. This pre-existing single-fall-through coupling is untouched
  (the reified variants get both halves from `bind`). Folding it in is out of scope.

## Roadmap entries

**R304** (Backlog): reify the `@error` PayloadAccessor errors fetcher. Discovered during
implementation: the PayloadAccessor errors read cannot be reified without a classifier change to
carry a resolved errors accessor, which this Spec scoped out (see
[Validator mirror](#validator-mirror)). R304 carries the classifier-backed reification, the R268
`resolvesViaPropertyDataFetcher` / `validateOutcomeChildArmSwitch` reconciliation, and the
`DataFetcherKind.PROPERTY_FETCHER` retirement.

The light path is preserved by wrapping the named reads in `LightFetcher` (see
[Light path: wrap the read, don't register the bare reference](#light-path-wrap-the-read-dont-register-the-bare-reference)),
so there is no deferred optimization to track there.

## Review feedback (In Review → Ready, 2026-06-14)

The implementation is architecturally sound and the full pipeline is green end-to-end
(`mvn -f graphitron-rewrite/pom.xml install -Plocal-db`, all 11 modules SUCCESS including the
compile-spec and execute-spec tiers). The sealed `FetcherBinding`, the `LightFetcher` `Read<T>`
SAM with the `Object`-typed reified-method return, the shared `nestedTypeOwnsFetchers` gate closing
the two-gate drift, the connection/edge/`@error` delegate classes, and the honest PayloadAccessor
deferral with R304 filed are all delivered as specified, and the tests are faithful
(method-presence + wiring-kind assertions, no banned code-string body assertions beyond the
documented `@error` / LocalContext source-pin exception).

**One blocking finding — stale `ColumnFetcher` documentation.** The global
`ColumnFetcher → LightFetcher` rename left seven javadoc/comment references to the now-deleted
`ColumnFetcher` class, two of which additionally assert the `no per-field method generated`
invariant that R303 *inverted* (`ColumnField` now reifies and collects a per-field method). This
violates the *"Documentation names only live tests/code"* principle in both forms (a name that no
longer resolves, and a false invariant readers trust), and it is exactly the live documentation the
[Comment / javadoc hygiene](#comment--javadoc-hygiene) section committed to leaving accurate
("the live documentation does not describe wiring that no longer exists"). Fix all seven, then
re-request the In Review → Done handoff:

* `TypeFetcherGenerator.java:65-67` (class javadoc) — `ColumnField` "wired via
  `new ColumnFetcher<>(Tables.X.COLUMN)`, no per-field method generated. `ColumnFetcher` implements
  `LightDataFetcher`". Both the class name and the no-method invariant are now false; describe the
  reified source-only read wrapped in `LightFetcher`. **Most material** (first thing a reader of the
  central generator sees).
* `TypeFetcherGenerator.java:370` — `// handled in wiring via ColumnFetcher — no method emitted` on
  the `ColumnField` switch arm; the method *is* now emitted (collected just below the switch). The
  sibling no-op arms at `:465-548` were updated; this arm was missed.
* `FetcherRegistrationsEmitter.java:31` — "inline `ColumnFetcher` bindings".
* `FetcherEmitter.java:144` — "a `ComputedField` or other `ColumnFetcher`-backed leaf".
* `FetcherEmitter.java:277` and `:287` — "what `ColumnFetcher` does" / "`ColumnFetcher`'s
  `((Record) source).get(column)`".
* `ChildField.java:841` — "a `ColumnFetcher` keyed on the GraphQL field name".
* `ExternalFieldDirectiveResolver.java:80` — "the alias shadows it and `ColumnFetcher`".

No other rework required; the fix is mechanical and comment-only.

### Rework landed (In Review, 2026-06-14)

All eight stale `ColumnFetcher` references fixed (the seven above plus
`graphitron-sakila-service/.../FilmExtensions.java`, the `@externalField` fixture javadoc, found
by a repo-wide sweep). The two false-invariant sites (`TypeFetcherGenerator` class javadoc and the
`ColumnField` switch arm) now describe the reified `LightFetcher`-wrapped read; the
`FetcherRegistrationsEmitter` class javadoc list was rewritten end-to-end (the `BatchKeyField`-leaf
nested distinction is gone post-R303, and connection/edge now bind to the per-type
`<Conn>Fetchers` / `<Edge>Fetchers` delegates rather than directly to `ConnectionHelper`), and its
now-javadoc-only `BatchKeyField` import was dropped. A repo-wide `grep ColumnFetcher` over
`src/main` is clean (the intentional `DataFetcherKind.COLUMN_FETCHER` test enum, documented in the
Tests section, is the only surviving spelling and is in test code). Full pipeline re-run green
end-to-end.

## Review feedback (In Review → Ready, 2026-06-14, second pass)

The implementation is architecturally sound and the full pipeline is green end-to-end at the R303
tip (`mvn -f graphitron-rewrite/pom.xml install -Plocal-db`, all 11 modules SUCCESS including the
compile-spec and execute-spec tiers; the current trunk-build failure is an unrelated R290
`graphitron-lsp` regression on `FieldClassification.Constructor`, not R303). The sealed
`FetcherBinding`, the `LightFetcher` `Read<T>` SAM with the `Object`-typed reified return, the
shared `nestedTypeOwnsFetchers` gate, the connection/edge/`@error` delegate classes, the honest
PayloadAccessor deferral with R304 filed, and the faithful method-presence + wiring-kind tests are
all delivered as specified.

**Blocking — the docs-hygiene rework is incomplete.** The previous In Review → Ready cycle requested
fixing stale documentation that "describes wiring that no longer exists," and the
[Comment / javadoc hygiene](#comment--javadoc-hygiene) section committed to correcting the no-op-arm
comments across `TypeFetcherGenerator.java:465-548`. The rework swept the `ColumnFetcher` *spelling*
(clean), but the deeper false invariant it inverted, "no per-field fetcher method / emitted inline",
survives verbatim in sibling sites that do not contain the `ColumnFetcher` token, so the
spelling-grep missed them. These violate *"Documentation names only live tests/code"*
(`rewrite-design-principles.adoc`, narrow false-invariant form): a debugging reader who trusts
"no per-field fetcher method" would not look for the method that R303 now collects, which is exactly
the findability R303 exists to deliver. Fix, then re-request the handoff:

* `TypeFetcherGenerator.java:508-512` (`SingleRecordTableField` switch arm) — "has no per-field
  fetcher method ; its DataFetcher value is emitted inline by FetcherEmitter". Both halves are now
  false: `bind` reifies it to an env-dependent `(DataFetchingEnvironment env)` method collected on
  the class, and the registration is a `<Type>Fetchers::<field>` reference, not an inline value.
  **Inside the committed :465-548 range.**
* `TypeFetcherGenerator.java:513-520` (`SingleRecordIdFieldFromReturning` and `SingleRecordIdField`
  switch arms) — "emits its DataFetcher value inline through FetcherEmitter ; no per-field fetcher
  method is emitted here". Same inversion as above. **Inside the committed :465-548 range.**
* `TypeFetcherGenerator.java:244-245` (`PROJECTED_LEAVES` class javadoc) — "no per-field fetcher
  method is generated". Now false in absolute terms: every projected leaf (`TableField`,
  `LookupTableField`, `ColumnReferenceField`, `CompositeColumnField`, `NestingField`) reifies a read
  method via the `bind` collection below the switch. The dispatch-partition reading ("the switch arm
  emits no method") is still true, so reword to scope the claim to the dispatch switch rather than
  delete it.
* `GraphitronSchemaValidator.java:695-702` (nested-wireable-variants javadoc) — "Inline leaves
  (`ColumnField`, `TableField`, etc.) have className-independent arms in
  `FetcherRegistrationsEmitter`. Class-backed leaves (`SplitTableField`, `SplitLookupTableField`) are
  wired via a per-nested-type `<NestedTypeName>Fetchers` class." R303 dissolved this dichotomy:
  `nestedBody` now binds every nested field via `<NestedType>Fetchers::<field>`, and every nested
  type owning any fetcher gets a `<Type>Fetchers` class (the `nestedTypeOwnsFetchers` widening), so
  the inline-leaf / class-backed-leaf split no longer holds.

No other rework required; the fix is mechanical and comment-only, the same shape as the first pass.
A repo-wide audit of the inverted invariant (not just the `ColumnFetcher` spelling) before
re-handoff would close the class of miss.

### Rework landed (In Progress → In Review, 2026-06-14, second pass)

All four flagged sites fixed, plus two more surfaced by auditing the *inverted invariant* (the
"no per-field fetcher method / read emitted inline" claim) across `src/main` rather than the
`ColumnFetcher` spelling, exactly as the feedback recommended:

* `TypeFetcherGenerator.java` SingleRecord switch arms (`SingleRecordTableField`,
  `SingleRecordIdFieldFromReturning`, `SingleRecordIdField`) now state the read is reified by
  `FetcherEmitter.bind` into a named `(DataFetchingEnvironment env)` method collected below the
  switch, with the arm a no-op because the method is collected there.
* `TypeFetcherGenerator.java` `PROJECTED_LEAVES` javadoc now scopes "emits no fetcher method" to
  the dispatch switch and notes the projected-value read is reified by `FetcherEmitter.bind` and
  collected below.
* `GraphitronSchemaValidator.java` `NESTED_WIREABLE_LEAVES` javadoc now states every leaf is wired
  through the nested type's own `<NestedTypeName>Fetchers` class (reified column/table reads plus
  the class-backed heavy methods), gated by the shared `nestedTypeOwnsFetchers`.
* **Additional (found by the invariant audit):** `TypeFetcherGenerator.java`
  `ParticipantColumnReferenceField` arm ("No per-field fetcher method ; reads it back via
  FetcherEmitter's arm") now describes the reified `LightFetcher`-wrapped source-only read collected
  below; `FetcherEmitter.isInlineArmSwitchedDataField` javadoc ("resolves to an inline value
  expression that reads `env.getSource()`") now describes the reified-method read; and the R268
  arm-switch guarantee javadoc in `GraphitronSchemaValidator` ("inline reads narrow `Success` at the
  registration site") now points at the read reified onto `<Type>Fetchers`.

A repo-wide `grep` over `src/main` for the invariant phrasings (`no per-field`, `emitted inline`,
`className-independent`, `inline value expression`, `DataFetcher value`, …) is now clean of stale
claims. The `graphitron` module compiles and its full test suite stays green; the changes are
comment-only so no behaviour, test, or generated output is affected.
