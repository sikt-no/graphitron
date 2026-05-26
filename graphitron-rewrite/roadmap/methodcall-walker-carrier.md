---
id: R238
title: ServiceMethodCall walker carrier (R222 foundation slice)
status: Spec
bucket: structural
priority: 3
theme: structural-refactor
depends-on: []
created: 2026-05-25
last-updated: 2026-05-26
---

# ServiceMethodCall walker carrier (R222 foundation slice)

The slice lands R222's walker-carrier pattern on the root sync `@service` paths. Four permits migrate: `QueryServiceTableField`, `QueryServiceRecordField`, `MutationServiceTableField`, `MutationServiceRecordField`. Each loses its `MethodRef method` component and gains `ServiceMethodCall serviceMethodCall`, populated by a producer (`ServiceMethodCallWalker`) that reads the field's SDL definition plus the codegen classloader directly. The fetcher emitter for these four (`buildServiceFetcherCommon`) reads the slot and composes the var-decl-per-binding plus call line through a shared `ServiceMethodCallEmitter`. Alongside the carrier, the slice lands the plumbing every subsequent walker-carrier slice inherits: the `WalkerResult<C>` sealed wrapper, the sealed `AuthorError.Structural` sub-arm pattern, the LSP `Diagnostic` wire conventions, and the orchestrator's collect-Err-exclude-field flow.

## Target emitted code

The reducer backtracks from this shape. Each migrated fetcher's lambda body becomes a straight walk over the carrier's binding list followed by the call line:

```java
// QueryServiceRecordField example, post-slice
DSLContext dsl = graphitronContext(env).getDslContext(env);                  // only when needsDsl
DomainTypeA paramA = constructParamA(env);                                   // one per ParamBinding arm
DomainTypeB paramB = constructParamB(env);
DomainResult result = new DomainService(dsl).method(paramA, paramB);
```

One walk over `field.serviceMethodCall().bindings()` produces the var-decls; one composition of `target` + `methodName` + binding local-names produces the call line. Lifting per-param expressions to named locals is the structural shift.

The "construct `ParamX`" expression is a sealed-arm dispatch: the existing `CallSiteExtraction` carried unchanged as `ArgConstruction`, with arms `Direct`, `EnumValueOf`, `ContextArg`, `JooqConvert`, `NestedInputField`, `NodeIdDecodeKeys`, `InputBean`. `InputBean` extracts to a `private static` helper through today's `InputBeanInstantiationEmitter`; the other arms inline.

## Carrier shape

```java
// graphitron/src/main/java/no/sikt/graphitron/rewrite/model/ServiceMethodCall.java
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

public sealed interface ParamBinding
    permits FromEnvArg, FromContextKey, FromDslContext {
    String localName();
    TypeName javaType();
}

public record FromEnvArg(
    String localName, TypeName javaType,
    String argName,
    ArgConstruction construction
) implements ParamBinding {}

public record FromContextKey(
    String localName, TypeName javaType, String contextKey
) implements ParamBinding {}

public record FromDslContext(String localName) implements ParamBinding {}
```

The carrier itself is sealed. `Static` carries one round of bindings (`methodArgs`); `Instance` carries two (`ctorArgs` for the constructor, then `methodArgs` for the method). Field order on `Instance` mirrors evaluation order. The same `ParamBinding` family applies uniformly to both rounds. Today's only instance shape (the `(DSLContext)` service-holder constructor) lands as `Instance(fqClassName, [FromDslContext("dsl")], methodName, methodArgs, returnShape)`. Future ctor shapes (multi-arg constructors, non-DSL dependencies) extend `ctorArgs`, not the sealed family.

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

1. **Parse the `@service` directive.** Read the target class FQ name, method name, and `argMapping` string. Failure: typed `Structural.*` arm.
2. **Load class and resolve method.** Use the classloader; locate the method by name and signature shape; check `-parameters` availability.
3. **Bind arguments.** Parse `argMapping`, resolve binding paths against the field's SDL arguments, derive the `ArgConstruction` per Java parameter.
4. **Build `methodArgs`** per resolved method parameter:
   - `@service` arg with extraction produces `FromEnvArg`
   - context-key arg produces `FromContextKey`
   - `DSLContext`-typed param produces `FromDslContext("dsl")` (shared local; the emitter dedupes against any ctor `FromDslContext` of the same local name)
5. **Build `ctorArgs`** when the method is instance; today's only shape is `[FromDslContext("dsl")]` from the `(DSLContext)` service-holder constructor. Skip for static methods.
6. **Build `ReturnTypeShape`** from the method's return type plus the field's declared return type; check shape compatibility.
7. **Build the carrier arm.** Static methods produce `Static(fqClassName, methodName, methodArgs, returnShape)`. Instance methods produce `Instance(fqClassName, ctorArgs, methodName, methodArgs, returnShape)`.
8. **Emit result.** `Ok(ServiceMethodCall(...), [])` on success; `Err(authorErrors, diagnostics)` when any step produced typed errors. Errors collect across stages; the walker doesn't short-circuit at the first failure.

The walker is invoked from `FieldBuilder` at each constructor site for the four service permits. Reflection helpers extracted from today's `ServiceCatalog.reflectServiceMethod` and `ArgBindingMap` move into the walker (or its `walker/internal/` package); they no longer surface on the public model boundary.

## Consumer migration

`TypeFetcherGenerator.buildServiceFetcherCommon` reads `field.serviceMethodCall()` and emits through the shared utility:

```java
// graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/ServiceMethodCallEmitter.java
public final class ServiceMethodCallEmitter {
    public static Emission emit(ServiceField field);

    public record Emission(List<CodeBlock> varDecls, CodeBlock callExpression) {}
}
```

`emit` pattern-matches on the sealed carrier and reads the bindings directly off the arm. For `Static`: walk `methodArgs` to produce var-decls, emit `ClassName.methodName(methodArgs)`. For `Instance`: walk `ctorArgs` then `methodArgs` to produce var-decls (deduping `FromDslContext` arms across rounds when their local name matches, so an instance method on a DSL-holder service whose method also takes a `DSLContext` gets exactly one `dsl` local), emit `new ClassName(ctorArgs).methodName(methodArgs)`. `buildServiceFetcherCommon` wraps the Emission in assignment-and-return shape over the call expression.

`ArgCallEmitter.buildMethodBackedCallArgs` retires at this seam for the four service permits' callers. The per-arm extraction-to-expression switch (`ArgCallEmitter.buildArgExtraction`) survives, since `ArgConstruction` dispatches through it.

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
    Structural.ReturnTypeMismatch,
    Structural.InstanceHolderMissingCtor,
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
| `Structural.ReturnTypeMismatch` | `graphitron.service-method-call.return-type-mismatch` |
| `Structural.InstanceHolderMissingCtor` | `graphitron.service-method-call.instance-holder-missing-ctor` |
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
| Return-type mismatch | `Structural.ReturnTypeMismatch(expected, actual)` |
| Instance method without `(DSLContext)` ctor | `Structural.InstanceHolderMissingCtor(className)` |
| Missing `-parameters` compile flag | `Structural.ParameterNamesMissing(className, methodName)` |
| Parameter not matching arg/context/source | `Structural.ParameterUnbindable(paramName, available, suggestion)` |
| `argMapping` parse error | `Structural.ArgMappingParseError(rawMapping, parserDetail)` |
| `argMapping` unknown arg ref | `Structural.ArgMappingUnknownArg(javaParam, refHead, availableArgs)` |
| `argMapping` path rejected | `Structural.ArgMappingPathRejected(javaParam, offendingSegment, reason)` |
| Input-bean shape rejections | `Structural.InputBeanShape(beanClass, reason)` |

Each typed arm carries the data its message and its LSP `relatedInformation` need. The walker collects across stages; multiple errors from one field surface together.

## Tests

Three tiers.

**Unit (`@UnitTier`)**: `ServiceMethodCallWalkerTest` covers one positive case per `ParamBinding` arm (`FromEnvArg`, `FromContextKey`, `FromDslContext`) and one rejection case per `Structural.*` sub-arm plus `UnknownName(SERVICE_METHOD)`. Tests parse a small SDL fragment, configure a test `ClassLoader` with fixture resolver classes, call `walk`, and assert on the sealed result. `ServiceMethodCallEmitterTest` covers the `Static` arm, the `Instance` arm with single-`FromDslContext` ctor binding, and the cross-round `FromDslContext` dedup case (instance ctor binds `dsl`, a method param also of type `DSLContext` reuses the same local), asserting on the `(varDecls, callExpression)` `Emission` shape.

**Pipeline (`@PipelineTier`)**: extend `ServiceRootFetcherPipelineTest` with assertions on `walkerDiagnostics` and typed `Structural.*` arms for the rejection cases. Existing positive-witness tests keep their author-facing wording but assert against the typed arms instead of stringly-typed rejection prose. Body-string assertions on fetcher emission retire (per `rewrite-design-principles.adoc`'s ban on code-string assertions) and get replaced by structural assertions on the `ServiceMethodCall` carrier.

**Compilation / Execution (`@CompilationTier` / `@ExecutionTier`)**: `graphitron-sakila-example` provides the regression net. The migration is structurally invariant on observable behaviour; `mvn install -Plocal-db` end-to-end-green is the safety net.
