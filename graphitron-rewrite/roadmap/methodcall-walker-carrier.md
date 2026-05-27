---
id: R238
title: ServiceMethodCall walker carrier (R222 foundation slice)
status: Spec
bucket: structural
priority: 3
theme: structural-refactor
depends-on: []
created: 2026-05-25
last-updated: 2026-05-27
---

# ServiceMethodCall walker carrier (R222 foundation slice)

The slice lands R222's walker-carrier pattern on the root sync `@service` paths. Four permits migrate: `QueryServiceTableField`, `QueryServiceRecordField`, `MutationServiceTableField`, `MutationServiceRecordField`. Each loses its `MethodRef method` component and gains `ServiceMethodCall serviceMethodCall`, populated by a producer (`ServiceMethodCallWalker`) that reads the field's SDL definition plus the codegen classloader directly. The fetcher emitter for these four (`buildServiceFetcherCommon`) passes the carrier to a shared `ServiceMethodCallEmitter` that returns the lambda body's statements. Alongside the carrier, the slice lands the plumbing every subsequent walker-carrier slice inherits: the `WalkerResult<C>` sealed wrapper, the sealed `AuthorError.Structural` sub-arm pattern, the LSP `Diagnostic` wire conventions, and the orchestrator's collect-Err-exclude-field flow.

The slice is a complete vertical for root sync `@service`. It retires four legacy carryovers that would otherwise leave the new architecture half-applied: silent first-match method resolution, head-only paths in input-bean instantiation, the `(DSLContext)`-only ctor restriction, and locally-reflected context-key types. Each retirement is a producer-side change in the walker; the carrier and emitter contracts absorb them without further extension.

## Target emitted code

The reducer backtracks from this shape. Each migrated fetcher's lambda body becomes a sequence of var-decls followed by the call statement that assigns the result to a local:

```java
// QueryServiceRecordField example, post-slice
DSLContext dsl = graphitronContext(env).getDslContext(env);                  // emitted once when needed
DomainTypeA paramA = constructParamA(env);                                   // one per non-DSL binding
DomainTypeB paramB = constructParamB(env);
DomainResult result = new DomainService(dsl).method(paramA, paramB);
```

Each `MappingEntry` in the carrier's `methodArgs` (and `ctorArgs` when the carrier is `Instance`) supplies one var-decl plus one identifier in the call's argument list. `FromDsl` is the exception: it shares the prelude's `dsl` local and contributes only the identifier, not a var-decl. Lifting per-param expressions to named locals is the structural shift.

The right-hand side of each non-DSL var-decl dispatches on the `MappingEntry`'s `ValueShape` directly. `Scalar` reads `env.getArgument(...)` (single- or multi-segment path) and applies its `Extraction` leaf transform. `FromContext` reads a context key. `RecordInput` and `JavaBeanInput` extract to `private static` helpers that construct the bean from its `List<FieldBinding>` (recursion runs through the shared `ValueShape` family). `ListOf` extracts the list at its `sdlPath` and emits an element-wise mapper. The slice retires `CallSiteExtraction` for these four permits: every value-source case it used to encode now appears as a `MappingEntry` arm, a `ValueShape` arm, or an `Extraction` leaf.

## Carrier shape

Six sealed families co-locate in `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/`: `ServiceMethodCall`, `CompleteArgMapping` / `MappingEntry`, `ValueShape`, `Extraction`, `ReturnTypeShape`, and `ArgPath`. The model lives together because every emit-site needs to see the whole tree at once.

```java
// model/ServiceMethodCall.java
public sealed interface ServiceMethodCall permits Static, Instance {
    String fqClassName();
    String methodName();
    CompleteArgMapping methodArgs();
    ReturnTypeShape returnShape();
}

public record Static(
    String fqClassName,
    String methodName,
    CompleteArgMapping methodArgs,
    ReturnTypeShape returnShape
) implements ServiceMethodCall {}

public record Instance(
    String fqClassName,
    CompleteArgMapping ctorArgs,
    String methodName,
    CompleteArgMapping methodArgs,
    ReturnTypeShape returnShape
) implements ServiceMethodCall {}
```

```java
// model/CompleteArgMapping.java
public record CompleteArgMapping(List<MappingEntry> entries) {
    public CompleteArgMapping { entries = List.copyOf(entries); }
}

public sealed interface MappingEntry permits FromArg, FromContext, FromDsl {
    String javaName();
    TypeName javaType();
}

public record FromArg(String javaName, ValueShape shape) implements MappingEntry {
    @Override public TypeName javaType() { return shape.javaType(); }
}

public record FromContext(String javaName, TypeName javaType, String contextKey) implements MappingEntry {}

public record FromDsl() implements MappingEntry {
    @Override public String javaName() { return "dsl"; }
    @Override public TypeName javaType() { return ClassName.get(DSLContext.class); }
}
```

```java
// model/ValueShape.java
public sealed interface ValueShape permits Scalar, ListOf, RecordInput, JavaBeanInput {
    TypeName javaType();
}

public record Scalar(TypeName javaType, ArgPath sdlPath, Extraction extraction) implements ValueShape {}

public record ListOf(ArgPath sdlPath, ValueShape elementShape) implements ValueShape {
    @Override public TypeName javaType() {
        return ParameterizedTypeName.get(ClassName.get(List.class), elementShape.javaType());
    }
}

public record RecordInput(ClassName javaClass, List<FieldBinding> fields) implements ValueShape {
    @Override public TypeName javaType() { return javaClass; }
}

public record JavaBeanInput(ClassName javaClass, List<FieldBinding> fields) implements ValueShape {
    @Override public TypeName javaType() { return javaClass; }
}

public record FieldBinding(String javaFieldName, ValueShape shape) {}
```

```java
// model/Extraction.java
public sealed interface Extraction permits Direct, EnumValueOf, JooqConvert, NodeIdDecodeKeys {}
public record Direct() implements Extraction {}
public record EnumValueOf(ClassName enumClass) implements Extraction {}
public record JooqConvert(/* converter spec */) implements Extraction {}
public record NodeIdDecodeKeys(/* key descriptors */) implements Extraction {}
```

`MappingEntry` is the unit of binding at top-level slots: three flat arms (`FromArg`, `FromContext`, `FromDsl`), none recursive. `ValueShape` is the unit of recursion, walked through `RecordInput.fields`, `JavaBeanInput.fields`, and `ListOf.elementShape`. The two roles separate cleanly because env-arg entry happens once per slot, but value shape (bean of list of bean of scalar...) can recurse arbitrarily. Each `ValueShape` node carries its Java type, walker-derived from the SDL schema, so the emitter generates type-correct casts at every step.

Paths live on the data-bearing leaves (`Scalar`, `ListOf`), not on composites. In default mapping every sibling field's `sdlPath` shares a prefix; in the forward-compatible nested `@argMapping` syntax (`paramName: { fieldA: input.x, fieldB: input.other.y, ... }`) sibling paths are independent. The model represents both uniformly: only the path values differ. The nested-form parser isn't part of R238's scope — R249 (`nested-argmapping-syntax`) wires it through `GraphQLSelectionParser`, parallel to R69 (`experimental-construct-type`) on the output side. R238's model doesn't need changes when R249 lands.

`RecordInput` and `JavaBeanInput` are sibling arms rather than one arm with a flag, so the emitter pattern-matches the construction strategy without reflecting on the bean class at emit time. `Extraction` is structurally locked to `Scalar` (no other arm permits it).

The carrier itself is sealed. `Static` carries one round (`methodArgs`); `Instance` carries two (`ctorArgs` then `methodArgs`). Field order on `Instance` mirrors evaluation order. The walker enforces two asymmetries across the rounds: `FromArg` is invalid in `ctorArgs` (field arguments aren't in scope at ctor time, raising `Structural.CtorParamFromArg`), and two `FromDsl` entries cannot coexist in the same round (the shared `dsl` local would collide, raising `Structural.MultipleDslContextSlots`). Multi-arg constructors with `FromContext` and `FromDsl` slots are first-class; the legacy `(DSLContext)`-only ctor restriction retires in this slice. `FromContext` and `FromDsl` cannot reach bean fields by construction — `FieldBinding.shape` is `ValueShape`, and `ValueShape` has no such arms.

`ReturnTypeShape` wraps a JavaPoet `TypeName` plus a sealed jOOQ-shape hint (`Scalar`, `Pojo`, `JooqResult(recordClass)`, `JooqRecord(recordClass)`). The existing return-type computation in `computeMutationServiceRecordReturnType` and friends lifts onto this.

## Slot landing on `ServiceField`

A new interface declares the slot:

```java
// graphitron/src/main/java/no/sikt/graphitron/rewrite/model/ServiceField.java
public interface ServiceField {
    ServiceMethodCall serviceMethodCall();
}
```

`ServiceField` is a sibling to `MethodBackedField`, not a sub-interface. The four root sync service permits implement `ServiceField` and stop implementing `MethodBackedField`. They drop the `MethodRef method` record component; each gains `ServiceMethodCall serviceMethodCall` as a component, and the auto-generated accessor satisfies the interface contract:

```java
record QueryServiceRecordField(
    String parentTypeName,
    String name,
    SourceLocation location,
    ReturnTypeRef returnType,
    ServiceMethodCall serviceMethodCall,
    Optional<ErrorChannel> errorChannel
) implements QueryField, ServiceField, WithErrorChannel
```

The slot is interface-required, never `Optional` and never null. Consumers reading through `ServiceField` always get a populated carrier.

## Producer (`ServiceMethodCallWalker`)

```java
// graphitron/src/main/java/no/sikt/graphitron/rewrite/walker/ServiceMethodCallWalker.java
public final class ServiceMethodCallWalker {
    public WalkerResult<ServiceMethodCall> walk(
        GraphQLFieldDefinition field,
        ClassLoader codegenLoader
    );
}
```

Substrate: the field's SDL definition (`GraphQLFieldDefinition`) plus the codegen classloader for reflection on the user's service class. No graphitron-internal intermediate model.

Walker stages (one pass per `@service`-bearing root field):

1. **Parse the `@service` directive.** Read the service class FQ name, method name, and `argMapping` string.
2. **Load class and resolve method.** Use the classloader; collect every declared method whose name matches. Filter by ctor+method arity match against the field's binding budget (SDL args plus declared context keys plus any `DSLContext` slots). Reject zero matches with `UnknownName(SERVICE_METHOD, ...)` and multi-match with `Structural.AmbiguousMethod(className, methodName, candidateSignatures)`. Check `-parameters` availability on the surviving candidate.
3. **Build `CompleteArgMapping` per round.** For each round (the ctor for `Instance` carriers, plus the method always), iterate the Java parameter slots and classify each:
   - `DSLContext`-typed slot → `FromDsl()`.
   - Slot name matching a declared context key → `FromContext(name, ResolvedContextArg.javaType, key)`. `javaType` is read from `ContextArgumentClassifier`'s cross-site agreed type, not from this site's local Java param reflection.
   - Otherwise an SDL arg source → resolve the slot's `@argMapping` entry (explicit override or implicit identity default) to an `ArgPath` from the field args root; produce `FromArg(name, shape)` per stage 4. In `ctorArgs`, `FromArg` raises `Structural.CtorParamFromArg`. Two `FromDsl()` entries in the same round raise `Structural.MultipleDslContextSlots`.
   
   Implicit and explicit entries are indistinguishable downstream — the artifact is "complete" in the sense that every parameter slot has a `MappingEntry`, even those the user didn't mention.
4. **Derive `ValueShape` for each `FromArg` entry.** Starting from the SDL type at the path's leaf and the Java target type, build the value subtree:
   - SDL-driven descent (the case R238 covers): extend `graphql.schema.visitor.GraphQLSchemaVisitor` and drive it via `new SchemaTraverser().depthFirst(visitor, leafSdlType)`. `TraverserContext` threads the Java target type as the visitor descends; `visitScalarType` / `visitEnumType` emit `Scalar`, `visitListType` emits `ListOf` and pushes the element's Java target, `visitInputObjectType` emits `RecordInput` or `JavaBeanInput` (chosen from the Java class's structure) and pushes each field's Java target onto the context for the child visit. Post-visit assembly threads the constructed `ValueShape` back up. Cycle detection rides on `TraverserContext.visitedNodes()`. R238 picks the visitor surface from `graphql.schema.visitor` rather than the lower-level `graphql.schema.GraphQLTypeVisitor`, matching the choice R166 (`graphqlschemavisitor-driven-emission`) plans to use for the emitter side.
   - Java-driven descent (forward-compatible with the nested `@argMapping` syntax landing under R249): iterate the Java target's record-components or JavaBean properties, look up each leaf's path from the explicit per-field entry, resolve it through a flat `GraphQLInputObjectType.getField` chain, and assemble the `ValueShape` bottom-up. R238 doesn't ship the nested-form parser; the model and walker just accommodate it.
   
   Both descents converge on the same `ValueShape` tree. Validation errors map to `Structural.InputBeanShape` (bean-shape rejections), `Structural.ArgMappingPathRejected` (path doesn't resolve), and `Structural.ParameterUnbindable` (Java target doesn't match the SDL leaf).
5. **Build `ReturnTypeShape`** from the method's return type plus the field's declared return type; check shape compatibility.
6. **Build the carrier arm.** Static methods produce `Static(fqClassName, methodName, methodArgs, returnShape)`. Instance methods produce `Instance(fqClassName, ctorArgs, methodName, methodArgs, returnShape)`.
7. **Return the result.** `Ok(...)` on success; `Err(authorErrors, diagnostics)` when any stage produced typed errors. Errors accumulate; the walker doesn't short-circuit at the first failure.

The walker is invoked from `FieldBuilder` at each constructor site for the four service permits. Reflection helpers extracted from today's `ServiceCatalog.reflectServiceMethod` and `ArgBindingMap`, plus the new `GraphQLSchemaVisitor` extension that produces `ValueShape`, live in `walker/internal/`; they no longer surface on the public model boundary.

## Consumer migration

`TypeFetcherGenerator.buildServiceFetcherCommon` passes the carrier into a shared utility and appends the returned statements to its method body:

```java
// graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/ServiceMethodCallEmitter.java
public final class ServiceMethodCallEmitter {
    public static List<CodeBlock> emit(ServiceMethodCall call);
}
```

```java
ServiceMethodCallEmitter.emit(field.serviceMethodCall()).forEach(builder::addStatement);
// then buildServiceFetcherCommon emits its return/catch wrapping over the `result` local
```

The returned list is the ordered statements that produce a local named `result` holding the call's return value:

1. **DSL prelude** (when needed): `DSLContext dsl = graphitronContext(env).getDslContext(env);`. Emitted if the carrier is `Instance` or any top-level entry (in either round's `CompleteArgMapping`) is `FromDsl`.
2. **Var-decls**, walking `ctorArgs.entries()` (when the carrier is `Instance`) followed by `methodArgs.entries()`. Per entry:
   - `FromArg` emits `Type javaName = expr;` where `expr` is the `ValueShape` evaluation. Leaf `Scalar` and shallow extractions inline; `ListOf`, `RecordInput`, and `JavaBeanInput` extract to `private static` helpers that recurse through nested `FieldBinding`s.
   - `FromContext` emits `Type javaName = (Type) GraphitronContext.getContextArgument(env, "contextKey");`.
   - `FromDsl` contributes no var-decl (it shares the prelude's `dsl`).
3. **Final assignment**:
   - `Static` arm: `ReturnType result = ClassName.methodName(methodArgs);`
   - `Instance` arm: `ReturnType result = new ClassName(ctorArgs).methodName(methodArgs);`

The call's actual-args list at each position reads from the entry's `javaName()`: `paramA`, `paramB`, ..., with `dsl` wherever a `FromDsl` entry sits.

`ArgCallEmitter.buildMethodBackedCallArgs` and `buildArgExtraction` retire at this seam for the four service permits' callers; the new emitter dispatches on `MappingEntry` and `ValueShape` directly. Both helpers stay alive for non-scope sites (condition, tableMethod, etc.) until later slices migrate them.

## Plumbing fixed by this slice

Every subsequent walker-carrier slice inherits these conventions.

### `WalkerResult<C>`

```java
public sealed interface WalkerResult<C> {
    record Ok<C>(C carrier, List<Diagnostic> diagnostics) implements WalkerResult<C> {
        public Ok {
            diagnostics = List.copyOf(diagnostics);
            if (diagnostics.stream().anyMatch(d -> d.severity() == Severity.Error)) {
                throw new IllegalArgumentException("Ok cannot carry Error-severity diagnostics");
            }
        }
    }
    record Err<C>(List<AuthorError> errors, List<Diagnostic> diagnostics) implements WalkerResult<C> {
        public Err {
            errors = List.copyOf(errors);
            diagnostics = List.copyOf(diagnostics);
            if (errors.isEmpty()) {
                throw new IllegalArgumentException("Err must carry at least one error");
            }
        }
    }
}
```

### `AuthorError.Structural` as a sealed sub-family

Today's `Structural(String reason)` arm becomes sealed:

```java
sealed interface AuthorError permits UnknownName, Structural, /* ...existing arms... */ {}

sealed interface Structural extends AuthorError permits
    Structural.ClassNotLoadable,
    Structural.AmbiguousMethod,
    Structural.ReturnTypeMismatch,
    Structural.InstanceHolderMissingCtor,
    Structural.CtorParamFromArg,
    Structural.MultipleDslContextSlots,
    Structural.ParameterNamesMissing,
    Structural.ParameterUnbindable,
    Structural.InputBeanShape,
    Structural.ArgMappingParseError,
    Structural.ArgMappingUnknownArg,
    Structural.ArgMappingPathRejected,
    Structural.Other
{ String message(); }
```

Each typed sub-arm carries its specific data (class names, method names, expected vs actual types, suggested fixes). `Structural.Other(String reason)` is the transitional catch-all for existing `Rejection.structural(...)` callsites outside the slice's migration set; they continue to compile and project through a fallback wire code until subsequent slices migrate them.

### LSP wire conventions

- **Code namespace**: `graphitron.<carrier-name>.<leaf>`. Codes derive from the `AuthorError` arm's type, not stored as a string field.
- **`source: "graphitron"`** on every diagnostic.
- **Severity**: walker `AuthorError`s project to `Error`.
- **Source attribution**: every diagnostic's primary `SourceLocation` is the field's own SDL location (`GraphQLFieldDefinition.getDefinition().getSourceLocation()`). For nested-path errors, the offending segment goes in `Diagnostic.relatedInformation` instead of retargeting the primary location.

Arm-to-code mapping for this slice:

| `AuthorError` arm | LSP `code` |
|---|---|
| `UnknownName(AttemptKind.SERVICE_METHOD)` | `graphitron.service-method-call.unknown-method` |
| `Structural.ClassNotLoadable` | `graphitron.service-method-call.class-not-loadable` |
| `Structural.AmbiguousMethod` | `graphitron.service-method-call.ambiguous-method` |
| `Structural.ReturnTypeMismatch` | `graphitron.service-method-call.return-type-mismatch` |
| `Structural.InstanceHolderMissingCtor` | `graphitron.service-method-call.instance-holder-missing-ctor` |
| `Structural.CtorParamFromArg` | `graphitron.service-method-call.ctor-param-from-arg` |
| `Structural.MultipleDslContextSlots` | `graphitron.service-method-call.multiple-dsl-context-slots` |
| `Structural.ParameterNamesMissing` | `graphitron.service-method-call.parameter-names-missing` |
| `Structural.ParameterUnbindable` | `graphitron.service-method-call.parameter-unbindable` |
| `Structural.InputBeanShape` | `graphitron.service-method-call.input-bean-shape` |
| `Structural.ArgMappingParseError` | `graphitron.service-method-call.arg-mapping-parse-error` |
| `Structural.ArgMappingUnknownArg` | `graphitron.service-method-call.arg-mapping-unknown-arg` |
| `Structural.ArgMappingPathRejected` | `graphitron.service-method-call.arg-mapping-path-rejected` |
| `Structural.Other` | `graphitron.structural` |

### Orchestrator flow

`ValidationReport` gains a `walkerDiagnostics: List<Diagnostic>` slot alongside `errors` / `warnings`. When the walker returns `Err`, the orchestrator collects the typed `AuthorError`s, projects each to a `Diagnostic` per the arm-to-code table, and writes them into `walkerDiagnostics`. The field is excluded from the classified set; downstream generation is blocked when any field produced an `Err`. The four service permits never construct as `UnclassifiedField`; the wire to the editor (`Workspace.setBuildOutput` → `Diagnostics.compute` → `LanguageClient.publishDiagnostics`) is live as today, with `Diagnostics.validatorDiagnostics` gaining an arm projecting the walker `Diagnostic` family.

## Producer-side failure modes

The walker routes today's existing rejections through `WalkerResult.Err` as the listed typed arm:

| Source today | New arm |
|---|---|
| Class not loadable | `Structural.ClassNotLoadable(className)` |
| Unknown service method | `UnknownName(SERVICE_METHOD, attempt, candidates)` |
| Multiple methods match name+arity (new) | `Structural.AmbiguousMethod(className, methodName, candidateSignatures)` |
| Return-type mismatch | `Structural.ReturnTypeMismatch(expected, actual)` |
| Instance ctor unresolvable from context/DSL | `Structural.InstanceHolderMissingCtor(className, attemptedSignature)` |
| Ctor slot bound to SDL arg (new) | `Structural.CtorParamFromArg(className, paramName)` |
| Two `DSLContext` slots in same round (new) | `Structural.MultipleDslContextSlots(className, round)` |
| Missing `-parameters` compile flag | `Structural.ParameterNamesMissing(className, methodName)` |
| Parameter not matching arg/context/source | `Structural.ParameterUnbindable(paramName, available, suggestion)` |
| `argMapping` parse error | `Structural.ArgMappingParseError(rawMapping, parserDetail)` |
| `argMapping` unknown arg ref | `Structural.ArgMappingUnknownArg(javaParam, refHead, availableArgs)` |
| `argMapping` path rejected | `Structural.ArgMappingPathRejected(javaParam, offendingSegment, reason)` |
| Input-bean shape rejections | `Structural.InputBeanShape(beanClass, reason)` |

Each typed arm carries the data its message and its LSP `relatedInformation` need. The walker collects across stages; multiple errors from one field surface together.

## Tests

Three tiers.

**Unit (`@UnitTier`)**, two test classes.

`ServiceMethodCallWalkerTest` parses a small SDL fragment, configures a test `ClassLoader` with fixture resolver classes, calls `walk`, and asserts on the sealed result. Coverage: one positive case per `MappingEntry` arm (`FromArg`, `FromContext`, `FromDsl`); one positive case per `ValueShape` arm (`Scalar`, `ListOf`, `RecordInput`, `JavaBeanInput`); one positive case per `Extraction` leaf (`Direct`, `EnumValueOf`, `JooqConvert`, `NodeIdDecodeKeys`); one rejection case per `Structural.*` sub-arm plus `UnknownName(SERVICE_METHOD)`; arity-match disambiguation across overloads (one case where arity picks a single candidate, one where two candidates remain and `AmbiguousMethod` fires); deep-path bean instantiation (a path terminating at depth 2 on an input-object slot produces a `RecordInput` whose `fields` themselves contain a nested `RecordInput`); multi-arg ctor (a service class with `(DSLContext, ContextArg)` ctor produces `Instance` with two `ctorArgs`); cross-site context-arg projection (the walker's `FromContext.javaType` matches the cross-site `ResolvedContextArg.javaType`, not the local reflected type, verified via a fixture where two sites declare the same key and the agreed type wins); visitor-driven shape descent (a fixture with `RecordInput → ListOf → RecordInput → Scalar` exercises every `GraphQLSchemaVisitor` callback the walker overrides).

`ServiceMethodCallEmitterTest` asserts on the returned `List<CodeBlock>` statement-by-statement. Coverage: the `Static` arm with and without a `FromDsl` method entry; the `Static` arm with a `RecordInput` method entry (asserts a `private static` helper is emitted and called); the `Instance` arm with a single `FromDsl` ctor entry; the `Instance` arm with a multi-arg ctor mixing `FromDsl` and `FromContext`; the cross-round case (instance ctor binds `dsl`, method also takes `DSLContext`) asserts that the prelude is emitted exactly once and both call positions read `dsl`.

**Pipeline (`@PipelineTier`)**: extend `ServiceRootFetcherPipelineTest` with assertions on `walkerDiagnostics` and typed `Structural.*` arms for the rejection cases. Existing positive-witness tests keep their author-facing wording but assert against the typed arms instead of stringly-typed rejection prose. Body-string assertions on fetcher emission retire (per `rewrite-design-principles.adoc`'s ban on code-string assertions) and get replaced by structural assertions on the `ServiceMethodCall` carrier.

**Compilation / Execution (`@CompilationTier` / `@ExecutionTier`)**: `graphitron-sakila-example` provides the regression net. The migration is structurally invariant on observable behaviour; `mvn install -Plocal-db` end-to-end-green is the safety net.
