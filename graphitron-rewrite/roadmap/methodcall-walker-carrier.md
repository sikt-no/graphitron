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

Each `ParamBinding` in the carrier's `methodArgs` (and `ctorArgs` when the carrier is `Instance`) supplies one var-decl plus one identifier in the call's argument list. `DslContext` is the exception: it shares the prelude's `dsl` local and contributes only the identifier, not a var-decl. Lifting per-param expressions to named locals is the structural shift.

The right-hand side of each non-DSL var-decl dispatches on the `ParamBinding` arm directly. `EnvArg` reads `env.getArgument(...)` (single- or multi-segment path) and applies its `Extraction` leaf transform. `ContextArg` reads a context key. `InputBean` extracts to a `private static` helper that constructs the bean from its nested `List<ParamBinding>` fields (recursion runs through the same sealed family). The slice retires `CallSiteExtraction` for these four permits: every value-source case it used to encode now appears as a `ParamBinding` arm or an `Extraction` leaf.

## Carrier shape

Four sealed families co-locate in `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/`: `ServiceMethodCall`, `ParamBinding`, `Extraction`, `ReturnTypeShape`. The model lives together because every emit-site needs to see the whole tree at once.

```java
// model/ServiceMethodCall.java
public sealed interface ServiceMethodCall permits Static, Instance {
    String fqClassName();
    String methodName();
    List<ParamBinding> methodArgs();
    ReturnTypeShape returnShape();
}

public record Static(
    String fqClassName,
    String methodName,
    List<ParamBinding> methodArgs,
    ReturnTypeShape returnShape
) implements ServiceMethodCall {}

public record Instance(
    String fqClassName,
    List<ParamBinding> ctorArgs,
    String methodName,
    List<ParamBinding> methodArgs,
    ReturnTypeShape returnShape
) implements ServiceMethodCall {}
```

```java
// model/ParamBinding.java
public sealed interface ParamBinding permits EnvArg, ContextArg, DslContext, InputBean {
    String name();          // local-var name at top-level slots; bean field name at nested positions
    TypeName javaType();
}

public record EnvArg(
    String name, TypeName javaType,
    ArgPath path,
    Extraction extraction
) implements ParamBinding {}

public record ContextArg(
    String name, TypeName javaType, String contextKey
) implements ParamBinding {}

public record DslContext() implements ParamBinding {
    @Override public String name() { return "dsl"; }
    @Override public TypeName javaType() { return ClassName.get(DSLContext.class); }
}

public record InputBean(
    String name, ClassName beanClass,
    List<ParamBinding> fields
) implements ParamBinding {
    @Override public TypeName javaType() { return beanClass; }
}
```

```java
// model/Extraction.java
public sealed interface Extraction permits Direct, EnumValueOf, JooqConvert, NodeIdDecodeKeys {}
public record Direct() implements Extraction {}
public record EnumValueOf(ClassName enumClass) implements Extraction {}
public record JooqConvert(/* converter spec */) implements Extraction {}
public record NodeIdDecodeKeys(/* key descriptors */) implements Extraction {}
```

`ParamBinding` is recursive: `InputBean.fields` is `List<ParamBinding>`. The tree captures both rounds of binding (method args, ctor args) and any depth of bean composition without a sibling taxonomy. `Extraction` reduces to four leaf-scalar transforms applied to a path-extracted env value; `ContextArg` and `InputBean` are first-class `ParamBinding` arms rather than nested under an extraction enum. `NestedInputField` from today's `CallSiteExtraction` collapses into `EnvArg` with a multi-segment `ArgPath`.

The carrier itself is sealed. `Static` carries one round of bindings (`methodArgs`); `Instance` carries two (`ctorArgs` for the constructor, then `methodArgs` for the method). Field order on `Instance` mirrors evaluation order. The same `ParamBinding` family applies uniformly to both rounds, with two walker-enforced asymmetries: `EnvArg` is invalid in `ctorArgs` (field arguments aren't in scope at ctor time), and `DslContext` is invalid in `InputBean.fields` (beans don't take a DSLContext). Multi-arg constructors with `ContextArg` and `DslContext` slots are first-class; the legacy `(DSLContext)`-only ctor restriction retires in this slice.

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
2. **Load class and resolve method.** Use the classloader; collect every declared method whose name matches. Filter by ctor+method arity match against the field's binding budget (SDL args plus declared context keys plus any DSLContext slots). Reject zero matches with `UnknownName(SERVICE_METHOD, ...)` and multi-match with `Structural.AmbiguousMethod(className, methodName, candidateSignatures)`. Check `-parameters` availability on the surviving candidate.
3. **Resolve `argMapping` paths** against the field's SDL arguments. For each Java method parameter that an SDL arg binds to, build the value subtree as a `ParamBinding`. Paths terminating at an input-object slot produce a recursive `InputBean` with one nested `ParamBinding` per bean field (the `InputBeanResolver` head-only restriction retires here); paths terminating at a scalar produce `EnvArg` with a single- or multi-segment `ArgPath` and the appropriate `Extraction` leaf.
4. **Build bindings per parameter slot.** Iterate the method's parameters and, for instance methods, the ctor's parameters. Classify each slot:
   - SDL arg produces `EnvArg(name, javaType, path, extraction)` (valid only in `methodArgs`; in `ctorArgs` it raises `Structural.CtorParamFromArg`).
   - context-key match produces `ContextArg(name, javaType, contextKey)` where `javaType` is read from the cross-site `ResolvedContextArg` produced by `ContextArgumentClassifier`, not from this site's local Java param reflection.
   - `DSLContext`-typed slot produces `DslContext()`.

   The result is two ordered lists: `methodArgs` always, `ctorArgs` only when the method is instance. Ctor and method slots use the same classification helper; the only difference is which `ParamBinding` arms are admissible (`EnvArg` is excluded for ctor slots; `DslContext` is excluded for `InputBean.fields`).
5. **Build `ReturnTypeShape`** from the method's return type plus the field's declared return type; check shape compatibility.
6. **Build the carrier arm.** Static methods produce `Static(fqClassName, methodName, methodArgs, returnShape)`. Instance methods produce `Instance(fqClassName, ctorArgs, methodName, methodArgs, returnShape)`.
7. **Return the result.** `Ok(...)` on success; `Err(authorErrors, diagnostics)` when any stage produced typed errors. Errors accumulate; the walker doesn't short-circuit at the first failure.

The walker is invoked from `FieldBuilder` at each constructor site for the four service permits. Reflection helpers extracted from today's `ServiceCatalog.reflectServiceMethod` and `ArgBindingMap` move into the walker (or its `walker/internal/` package); they no longer surface on the public model boundary.

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

1. **DSL prelude** (when needed): `DSLContext dsl = graphitronContext(env).getDslContext(env);`. Emitted if the carrier is `Instance` or any top-level binding (in either round) is `DslContext`.
2. **Var-decls**, walking `ctorArgs` (when the carrier is `Instance`) followed by `methodArgs`. `EnvArg`, `ContextArg`, and `InputBean` each emit a `Type name = expr;` statement; `DslContext` contributes no var-decl. `InputBean` expansions extract to `private static` helpers that recursively walk the bean's nested `List<ParamBinding>` fields.
3. **Final assignment**:
   - `Static` arm: `ReturnType result = ClassName.methodName(methodArgs);`
   - `Instance` arm: `ReturnType result = new ClassName(ctorArgs).methodName(methodArgs);`

The call's actual-args list at each position reads from the binding's `name()`: `paramA`, `paramB`, ..., with `dsl` wherever a `DslContext` binding sits.

`ArgCallEmitter.buildMethodBackedCallArgs` and `buildArgExtraction` retire at this seam for the four service permits' callers; the new emitter dispatches on `ParamBinding` directly. Both helpers stay alive for non-scope sites (condition, tableMethod, etc.) until later slices migrate them.

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

`ServiceMethodCallWalkerTest` parses a small SDL fragment, configures a test `ClassLoader` with fixture resolver classes, calls `walk`, and asserts on the sealed result. Coverage: one positive case per `ParamBinding` arm (`EnvArg`, `ContextArg`, `DslContext`, `InputBean`); one positive case per `Extraction` leaf (`Direct`, `EnumValueOf`, `JooqConvert`, `NodeIdDecodeKeys`); one rejection case per `Structural.*` sub-arm plus `UnknownName(SERVICE_METHOD)`; arity-match disambiguation across overloads (one case where arity picks a single candidate, one where two candidates remain and `AmbiguousMethod` fires); deep-path bean instantiation (a path terminating at depth 2 on an input-object slot produces a nested `InputBean` whose `fields` themselves contain an `InputBean` arm); multi-arg ctor (a service class with `(DSLContext, ContextArg)` ctor produces `Instance` with two `ctorArgs`); cross-site context-arg projection (the walker's `ContextArg.javaType` matches the cross-site `ResolvedContextArg.javaType`, not the local reflected type, verified via a fixture where two sites declare the same key and the agreed type wins).

`ServiceMethodCallEmitterTest` asserts on the returned `List<CodeBlock>` statement-by-statement. Coverage: the `Static` arm with and without a `DslContext` method param; the `Static` arm with an `InputBean` method param (asserts a `private static` helper is emitted and called); the `Instance` arm with a single `DslContext` ctor binding; the `Instance` arm with a multi-arg ctor mixing `DslContext` and `ContextArg`; the cross-round case (instance ctor binds `dsl`, method also takes `DSLContext`) asserts that the prelude is emitted exactly once and both call positions read `dsl`.

**Pipeline (`@PipelineTier`)**: extend `ServiceRootFetcherPipelineTest` with assertions on `walkerDiagnostics` and typed `Structural.*` arms for the rejection cases. Existing positive-witness tests keep their author-facing wording but assert against the typed arms instead of stringly-typed rejection prose. Body-string assertions on fetcher emission retire (per `rewrite-design-principles.adoc`'s ban on code-string assertions) and get replaced by structural assertions on the `ServiceMethodCall` carrier.

**Compilation / Execution (`@CompilationTier` / `@ExecutionTier`)**: `graphitron-sakila-example` provides the regression net. The migration is structurally invariant on observable behaviour; `mvn install -Plocal-db` end-to-end-green is the safety net.
