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

The governing principle: **every datafetcher is a named `public static` method on the
corresponding `<Type>Fetchers` class**, so the registration site is uniformly a
`<Type>Fetchers::<field>` method reference, for every object type that owns fetchers (root,
table, node, result, nested, connection, edge, and error types). A consumer debugging
`FilmConnection.edges` looks for `FilmConnectionFetchers.edges`; there is no lambda, no bare
`ColumnFetcher` instance, and no inline registration value left to hunt for. Triviality is not a
reason to keep a fetcher off its class; a named method is debuggable and discoverable. This
aligns with the *"Generated code is read and debugged"* principle (`rewrite-design-principles.adoc`),
which prefers a named helper method over an inline expression. The seam is `FetcherEmitter`
(which produces the registration value today) plus `TypeFetcherGenerator.generateTypeSpec`
(which already emits the heavy fetcher methods the method-backed variants reference), extended
to cover the nested, connection, and edge classes.

The one deliberate runtime trade is `ColumnFetcher`: reifying a column read into a plain
`<Type>Fetchers::<field>` method forfeits the `LightDataFetcher` fast-path (see
[Column reads and the light path](#column-reads-and-the-light-path)). This Spec accepts that
trade for uniformity and per-field debuggability; it is reversible behind a measurement if a
wide-table workload ever shows it.

## Audit findings

`FetcherEmitter.dataFetcherValue` / `dataFetcherValueRaw`
(`graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/FetcherEmitter.java`) produces
exactly one registration value per field, in one of these shapes:

| Shape | Variants (emitted form) | R303 |
|---|---|---|
| Single-expression lambda | `ConstructorField`, `NestingField` (`(env) -> env.getSource()`); single-`TableField` first-row read; `ErrorsField` LocalContext (`env -> env.getLocalContext()`) and WrapperArm (`env -> env.getSource() instanceof ErrorList<?> ...`); `PropertyField` / `RecordField` on a **class-backed** parent (accessor read off `env.getSource()`) | **Reify** |
| Multi-line lambda block | `SingleRecordTableField`, `SingleRecordIdField`, `SingleRecordIdFieldFromReturning` (record walk + SELECT / NodeId encode); the `ColumnField` and `CompositeColumnField` NodeId-encode lambdas; the R244/R268 arm-switch ternary (`armSwitchedInlineDataFetcher`) | **Reify** |
| `new ColumnFetcher<>(...)` | plain `ColumnField`, `LookupTableField`, `ComputedField`, `ColumnReferenceField` (Direct), `ParticipantColumnReferenceField`, `PropertyField` / `RecordField` on a **jOOQ-record** parent | **Reify** (light-path trade) |
| `PropertyDataFetcher.fetching(name)` | `ErrorsField` PayloadAccessor transport | **Reify** (resolved accessor) |
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

The only behavioural trade is the column light-path, recorded below; everything else is a pure
relocation of identical logic to named methods.

### Column reads and the light path

`ColumnFetcher` (`ColumnFetcherClassGenerator.java`) implements graphql-java's two-method
`LightDataFetcher`: besides `get(DataFetchingEnvironment)` it overrides
`get(GraphQLFieldDefinition, Object source, Supplier<DataFetchingEnvironment>)` and reads the
column off `source` **without invoking the supplier**, so graphql-java's executor skips
constructing the per-field-per-row `DataFetchingEnvironment`. `LightDataFetcher` is not a
functional interface (two abstract methods), so a method reference `<Type>Fetchers::<field>`
(which can only bind the single-method `DataFetcher` SAM) cannot be a `LightDataFetcher`; the
executor falls back to the heavy `get(env)` entry and constructs the environment. Reifying
`new ColumnFetcher<>(FILM.TITLE)` to `static Object title(env) { return ((Record)
env.getSource()).get(FILM.TITLE); }` therefore trades the env-skipping path for a per-field
named, breakpointable method. We accept that: column reads are dominated by the jOOQ round-trip,
the light path is already bypassed whenever instrumentation needs the environment, and the
decision is reversible (a light-path carve-out for pure column reads can return as its own item
if a profile ever justifies it). The light-preserving alternative, a factory
`static DataFetcher<?> title() { return new ColumnFetcher<>(FILM.TITLE); }`, was rejected: it is
findable but its breakpoint lands in the shared `ColumnFetcher.get`, not in a per-field body, so
it serves the lookup goal weakly and the debugger goal not at all.

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
For every shape that owns its body here it returns `Reified`, where `method` is named
`field.name()`, declared `public static Object <field>(DataFetchingEnvironment env)`, and
`registrationValue` is `CodeBlock.of("$T::$L", fetchersClass, field.name())`. It returns
`Inline` only for the method-backed fall-through, where the method is owned by
`TypeFetcherGenerator`'s switch and `bind` carries the `::` reference unchanged.

Mechanically, every body-producing branch is refactored to emit a **method body** (statement
form: `return <expr>;`, or the existing block with the `($T env) -> {` / `}` wrapper dropped)
that `bind` wraps in the `MethodSpec`:

* the lambda helpers: `armSwitchedInlineDataFetcher`, `buildSingleRecordTableFetcherValue` and
  siblings, the NodeId-encode `ColumnField` / `CompositeColumnField` blocks, the `NestingField` /
  `ConstructorField` passthrough, the single `TableField` read, the `ErrorsField` LocalContext /
  WrapperArm arms, and the class-backed arm of `propertyOrRecordValue`;
* the `ColumnFetcher` branches (plain `ColumnField`, `LookupTableField`, `ComputedField`,
  `ColumnReferenceField` Direct, `ParticipantColumnReferenceField`, and the jOOQ-record arm of
  `propertyOrRecordValue`): the method body is the heavy `get` of `ColumnFetcher` inlined,
  `return ((Record) env.getSource()).get(<column-or-DSL.field>);`. `ColumnFetcher` is no longer
  instantiated at a registration site, so the `ColumnFetcherClassGenerator` output (and its
  `LightDataFetcher` machinery) is no longer emitted; remove that generator from the run;
* the `ErrorsField` PayloadAccessor branch (today `PropertyDataFetcher.fetching(name)`): the
  method body performs the resolved-accessor read via the same `recordBackedAccessorRead`
  helper the class-backed `propertyOrRecordValue` path uses, so the errors list is read through a
  generation-time-resolved accessor rather than graphql-java's runtime property reflection. This
  also closes the R268 `PropertyDataFetcher`-escape hole (see Validator mirror).

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
* Every registration value becomes a `METHOD_REFERENCE`. The `FetcherPipelineTest` wiring-shape
  assertions that pin `LAMBDA`, `COLUMN_FETCHER`, or `PROPERTY_DATA_FETCHER` flip to
  `METHOD_REFERENCE`, and the "no methods" assertions gain the reified method (these are the live
  pins the change moves):
  * `propertyField_onBackedRecord_usesAccessorLambda`: `wiringFor("Container", "value")`
    becomes `METHOD_REFERENCE`; rename the test accordingly.
  * `propertyField_onRecordType_fetchersHasNoMethods` and
    `recordField_onRecordType_fetchersHasNoMethods`: `ContainerFetchers` /
    `FilmDetailsFetchers` now carry the reified `value` / `stats` method; assert its presence
    instead of `methodSpecs().isEmpty()`.
  * the R268 arm-switch pin (`FilmRecordPayload.title`) becomes `METHOD_REFERENCE`, with the
    reified `title` method present on `FilmRecordPayloadFetchers`.
  * any `COLUMN_FETCHER` / `PROPERTY_DATA_FETCHER` wiring assertions flip likewise.
* Since no registration value is a lambda, column-fetcher, or property-data-fetcher any more,
  `DataFetcherKind.{LAMBDA, COLUMN_FETCHER, PROPERTY_DATA_FETCHER}` and their `wiringFor`
  classifier arms become unreachable for registration values; remove the dead arms (and the
  values if nothing else uses them) rather than leave a classifier the output can never produce.
* New per-type classes appear and are asserted by the existing "class is emitted" style
  (`FetcherPipelineTest` already does `assertThat(classes).contains("FilmFetchers", ...)`):
  `<Conn>Fetchers`, `<Edge>Fetchers`, `<ErrorType>Fetchers`, and a `<NestedType>Fetchers` for a
  nested type with no `BatchKeyField` (the case that produced no class before).

Per `rewrite-design-principles.adoc`, no `CodeBlock`-string-equality unit assertion on the
reified method bodies; the kind classification plus the compile/execute tiers carry the proof.
The connection/edge delegate bodies are exercised end-to-end by the existing pagination
execution-tier coverage, unchanged.

## Validator mirror

No new classifier branch is introduced; the classifier model is untouched. There is one
validator-mirror consequence, in the `PropertyDataFetcher` reification:
`FetcherEmitter.resolvesViaPropertyDataFetcher` (`:85-89`) exists because the PayloadAccessor
errors field emits graphql-java's `PropertyDataFetcher`, which under a flipped `Outcome` source
would silently read off the wrong object; the R268 `validateOutcomeChildArmSwitch` rule consults
the predicate to reject that hole. Reifying the PayloadAccessor field to a generation-time
resolved-accessor read removes the `PropertyDataFetcher` emit path the predicate names, so the
predicate and the rule must be reconciled in the same change: either retire the predicate (the
escape it guarded no longer exists, since the reified read is a resolved accessor pinned at
generation time) or repoint it at the new disposition. Because this is the one place the change
touches R268, the implementer should land the PayloadAccessor reification as its own commit with
the predicate/rule reconciliation, after the mechanical relocations. `hasWrapperArmErrors` (the
`sourceIsOutcome` source) is unchanged.

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

None required. The column light-path is handled inline as an accepted, reversible trade (see
[Column reads and the light path](#column-reads-and-the-light-path)); a light-path carve-out for
pure column reads would be filed as its own Backlog item only if a profile later justifies it.
