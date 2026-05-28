---
id: R238
title: ServiceMethodCall walker carrier (R222 foundation slice)
status: In Progress
bucket: structural
priority: 3
theme: structural-refactor
depends-on: []
created: 2026-05-25
last-updated: 2026-05-28
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

The right-hand side of each non-DSL var-decl dispatches on the `MappingEntry`'s `ValueShape` directly. `Scalar` reads `env.getArgument(...)` (single- or multi-segment path) and applies its leaf transform (one of the four `CallSiteExtraction` leaf arms — `Direct`, `EnumValueOf`, `JooqConvert`, `NodeIdDecodeKeys`). `FromContext` reads a context key. `RecordInput` and `JavaBeanInput` extract to `private static` helpers that construct the bean from its `List<FieldBinding>` (recursion runs through the shared `ValueShape` family). `ListOf` extracts the list at its `sdlPath` and emits an element-wise mapper. The slice retires `CallSiteExtraction`'s non-leaf arms (`ContextArg`, `NestedInputField`, `InputBean`) for these four permits' callsites: every value-source case those arms used to encode now appears as a `MappingEntry` arm or a `ValueShape` arm. The four leaf arms stay on `CallSiteExtraction` unchanged; lifting them into a typed sub-family is a follow-up (see Out of scope).

## Carrier shape

Four families co-locate in `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/`: `ServiceMethodCall`, `MappingEntry`, `ValueShape`, and `ArgPath`. The model lives together because every emit-site needs to see the whole tree at once.

```java
// model/ServiceMethodCall.java
public sealed interface ServiceMethodCall permits Static, Instance {
    String fqClassName();
    String methodName();
    List<MappingEntry> methodArgs();
    TypeName javaReturnType();
}

public record Static(
    String fqClassName,
    String methodName,
    List<MappingEntry> methodArgs,
    TypeName javaReturnType
) implements ServiceMethodCall {
    public Static { methodArgs = List.copyOf(methodArgs); }
}

public record Instance(
    String fqClassName,
    List<MappingEntry> ctorArgs,
    String methodName,
    List<MappingEntry> methodArgs,
    TypeName javaReturnType
) implements ServiceMethodCall {
    public Instance {
        ctorArgs = List.copyOf(ctorArgs);
        methodArgs = List.copyOf(methodArgs);
    }
}
```

```java
// model/MappingEntry.java
public sealed interface MappingEntry permits FromArg, FromContext, FromDsl {}

public record FromArg(String javaName, ValueShape shape) implements MappingEntry {}

public record FromContext(String javaName, TypeName javaType, String contextKey) implements MappingEntry {}

public record FromDsl() implements MappingEntry {}
```

```java
// model/ValueShape.java
public sealed interface ValueShape permits Scalar, ListOf, RecordInput, JavaBeanInput {
    TypeName javaType();
}

public record Scalar(TypeName javaType, ArgPath sdlPath, CallSiteExtraction leafTransform) implements ValueShape {}

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

public record FieldBinding(String sdlFieldName, String javaFieldName, ValueShape shape) {}
```

```java
// model/ArgPath.java
public record ArgPath(String outerArgName, List<String> deeperSegments) {
    public ArgPath { deeperSegments = List.copyOf(deeperSegments); }
    public static ArgPath head(String outerArgName) { return new ArgPath(outerArgName, List.of()); }
}
```

`Scalar.leafTransform` is typed as the existing `CallSiteExtraction` interface. R238's walker only produces four of its seven arms at this slot (`Direct`, `EnumValueOf`, `JooqConvert`, `NodeIdDecodeKeys`); the walker enforces that restriction structurally and a unit test pins it. The three other arms (`ContextArg`, `NestedInputField`, `InputBean`) are exactly what R238's new `MappingEntry` / `ValueShape` model absorbs (`ContextArg` → `FromContext`; `NestedInputField` → multi-segment `ArgPath`; `InputBean` → `RecordInput` / `JavaBeanInput`), but they stay alive on `CallSiteExtraction` for non-R238 callsites (condition, tableMethod, externalField) until those slices migrate. R238 restructures none of `CallSiteExtraction`'s permits; lifting the four leaf arms into a typed sub-family is a follow-up that lands once the other three retire.

`MappingEntry` has three flat arms — none recursive, each carrying its own variant-specific component types (no lifted `javaType()` on the interface). `FromArg` carries a `ValueShape` whose `javaType()` is the slot's Java type; `FromContext` carries an explicit `TypeName javaType`; `FromDsl` carries no fields because the variant identity already determines `DSLContext.class` and the conventional `dsl` local name. Consumers switch on the arm and read the precise component.

`ValueShape` is the unit of recursion, walked through `RecordInput.fields`, `JavaBeanInput.fields`, and `ListOf.elementShape`. Each `ValueShape` node carries its Java type, walker-derived from the SDL schema, so the emitter generates type-correct casts at every step.

Paths live on the data-bearing leaves (`Scalar`, `ListOf`), not on composites. In default mapping every sibling field's `sdlPath` shares a prefix; in the forward-compatible nested `@argMapping` syntax (`paramName: { fieldA: input.x, fieldB: input.other.y, ... }`) sibling paths are independent. The model represents both uniformly: only the path values differ. The nested-form parser isn't part of R238's scope — R249 (`nested-argmapping-syntax`) wires it through `GraphQLSelectionParser`, parallel to R69 (`experimental-construct-type`) on the output side. R238's model doesn't need changes when R249 lands.

`RecordInput` and `JavaBeanInput` are sibling arms rather than one arm with a flag, so the emitter pattern-matches the construction strategy without reflecting on the bean class at emit time.

The carrier itself is sealed. `Static` carries one round (`methodArgs`); `Instance` carries two (`ctorArgs` then `methodArgs`). Field order on `Instance` mirrors evaluation order. The walker enforces two asymmetries across the rounds: `FromArg` is invalid in `ctorArgs` (field arguments aren't in scope at ctor time, raising `ServiceMethodCallError.CtorParamFromArg`), and two `FromDsl` entries cannot coexist in the same round (the shared `dsl` local would collide, raising `ServiceMethodCallError.MultipleDslContextSlots`). Multi-arg constructors with `FromContext` and `FromDsl` slots are first-class; the legacy `(DSLContext)`-only ctor restriction retires in this slice. `FromContext` and `FromDsl` cannot reach bean fields by construction — `FieldBinding.shape` is `ValueShape`, and `ValueShape` has no such arms.

`javaReturnType` on the carrier is the Java `TypeName` of the method's declared return type, used by the emitter to type the `result` local. The GraphQL-side classification of the field's return (`TableBoundReturnType` / `ResultReturnType` / `ScalarReturnType` / `PolymorphicReturnType`) lives on the existing `ReturnTypeRef` record component on each permit; the walker validates that the Java return type is shape-compatible with `ReturnTypeRef` at stage 5 and raises `ServiceMethodCallError.ReturnTypeMismatch` on disagreement. The two classifications are orthogonal — Java-side vs GraphQL-side — and stay in their respective homes rather than merging into a single sealed shape.

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

This diverges from R222's stated destination, which names `MethodBackedField` as the slot home with `ServiceField extends MethodBackedField` as a pure marker. The divergence is deliberate: R222 envisioned a unified `MethodCall` carrier across all 10 `MethodBackedField` implementers (service, condition, tableMethod, externalField), so the slot could live on the broad interface. R238 ships a *service-specific* carrier (`ServiceMethodCall` with `Static` and `Instance` arms) because the four root sync service permits are the only callsites in scope; promoting the slot to `MethodBackedField` would force the other six implementers to either grow no-op slot accessors or wait for their own carrier slices to land first. The sibling design lets R238 ship narrow and lets subsequent slices add their own carrier-bearing sub-interfaces alongside `ServiceField` (e.g. `ConditionField`, `TableMethodField`). `MethodBackedField` and its existing `MethodRef method()` contract stay alive for the six non-scope implementers until those slices migrate them. R222's umbrella note should be updated to reflect this destination shape; tracked separately.

The slot is interface-required, never `Optional` and never null. Consumers reading through `ServiceField` always get a populated carrier. The construction-time invariant: `FieldBuilder` constructs a `QueryServiceRecordField` (etc.) only when the walker returned `Ok`; on walker `Err` the orchestrator's collect-Err-exclude-field flow blocks the constructor call entirely, so no field ever exists with an Err-state slot.

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

1. **Parse the `@service` directive.** Read the service class FQ name, method name, and `argMapping` string. The argMapping parser returns a flat `ParsedArgMapping(LinkedHashMap<String, List<String>> javaParamToPath)`. Nested `@argMapping` syntax (R249, `nested-argmapping-syntax`) is out of scope here; when it lands, R249 reworks the parser's return shape — likely as a sealed split, but pinning that now would over-fit a feature that hasn't been built.
2. **Load class and resolve method.** Use the classloader; collect every declared method whose name matches. Filter by ctor+method arity match against the field's binding budget (SDL args plus declared context keys plus any `DSLContext` slots). Reject zero matches with `UnknownName(SERVICE_METHOD, ...)` and multi-match with `ServiceMethodCallError.AmbiguousMethod(className, methodName, candidateSignatures)`. Check `-parameters` availability on the surviving candidate.
3. **Build the per-round `List<MappingEntry>`.** For each round (the ctor for `Instance` carriers, plus the method always), iterate the Java parameter slots and classify each:
   - `DSLContext`-typed slot → `FromDsl()`.
   - Slot name matching a declared context key → `FromContext(name, localJavaType, key)`, where `localJavaType` is the walker's own reflection of the Java param's declared type at this site. The walker has no read dependency on `ContextArgumentClassifier`; the classifier later harvests these site-local types from the assembled `ServiceField` carriers and folds them per name into `ResolvedContextArg`. The emitter reads the cross-site agreed type from `GraphitronSchema.contextArguments().resolved().get(key).javaType()` at emit time. See "ContextArgumentClassifier harvest update" under Consumer migration.
   - Otherwise an SDL arg source → resolve the slot's `@argMapping` entry (explicit override or implicit identity default) to an `ArgPath` from the field args root; produce `FromArg(name, shape)` per stage 4. In `ctorArgs`, `FromArg` raises `ServiceMethodCallError.CtorParamFromArg`. Two `FromDsl()` entries in the same round raise `ServiceMethodCallError.MultipleDslContextSlots`.

   Implicit and explicit entries are indistinguishable downstream — every parameter slot has a `MappingEntry`, even those the user didn't mention.
4. **Derive `ValueShape` for each `FromArg` entry.** Starting from the SDL type at the path's leaf and the Java target type, recurse directly on the SDL type:
   - `GraphQLScalarType` / `GraphQLEnumType` → `Scalar(javaType, sdlPath, leafTransform)` where `leafTransform` is one of the four `CallSiteExtraction` leaf arms (`Direct` / `EnumValueOf` / `JooqConvert` / `NodeIdDecodeKeys`).
   - `GraphQLList` → `ListOf(sdlPath, elementShape)`; recurse on the element SDL type with the element's Java target.
   - `GraphQLInputObjectType` → `RecordInput` or `JavaBeanInput` (pick by inspecting the Java class — record component layout vs no-arg ctor + setters); recurse on each SDL field with its matching Java component target, producing one `FieldBinding` per field.

   The recursion is a four-way `switch` over `GraphQLInputType`; the visitor framework's polymorphic dispatch adds no value over the direct form here because the four cases are known and exhaustive. A `Set<GraphQLNamedType>` visited-set guards against cycles in the SDL input-object graph; existing `InputBeanResolver` carries the equivalent guard, and its discipline transfers verbatim. Validation errors map to `ServiceMethodCallError.InputBeanShape` (bean-shape rejections), `ServiceMethodCallError.ArgMappingPathRejected` (path doesn't resolve), and `ServiceMethodCallError.ParameterUnbindable` (Java target doesn't match the SDL leaf).
5. **Validate the Java return type against `ReturnTypeRef`.** Compute the Java method's declared return type, check shape-compatibility with the field's `ReturnTypeRef` arm, and raise `ServiceMethodCallError.ReturnTypeMismatch` on disagreement. Store the validated `TypeName` for the emitter to use as the `result` local's declared type.
6. **Build the carrier arm.** Static methods produce `Static(fqClassName, methodName, methodArgs, javaReturnType)`. Instance methods produce `Instance(fqClassName, ctorArgs, methodName, methodArgs, javaReturnType)`.
7. **Return the result.** `Ok(...)` on success; `Err(authorErrors, diagnostics)` when any stage produced typed errors. Errors accumulate; the walker doesn't short-circuit at the first failure.

The walker is invoked from `FieldBuilder` at each constructor site for the four service permits. Reflection helpers extracted from today's `ServiceCatalog.reflectServiceMethod` and `ArgBindingMap`, plus the new direct-recursive `ValueShape` builder, live in `walker/internal/`; they no longer surface on the public model boundary. R238 does not restructure `CallSiteExtraction` itself; lifting its four leaf arms into a typed sub-family is a follow-up that lands once `ContextArg`, `NestedInputField`, and `InputBean` retire from `CallSiteExtraction` (their R238 replacements are in `MappingEntry` / `ValueShape`, but non-R238 callsites still use them).

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

1. **DSL prelude** (when needed): `DSLContext dsl = graphitronContext(env).getDslContext(env);`. Emitted if the carrier is `Instance` or any top-level entry (in either round's mapping list) is `FromDsl`.
2. **Var-decls**, walking `ctorArgs` (when the carrier is `Instance`) followed by `methodArgs`. Per entry:
   - `FromArg` emits `Type javaName = expr;` where `expr` is the `ValueShape` evaluation. Leaf `Scalar` and shallow extractions inline; `ListOf`, `RecordInput`, and `JavaBeanInput` extract to `private static` helpers that recurse through nested `FieldBinding`s.
   - `FromContext` emits `Type javaName = (Type) graphitronContext(env).getContextArgument(env, "contextKey");` (same `graphitronContext(env)` static factory the DSL prelude uses).
   - `FromDsl` contributes no var-decl (it shares the prelude's `dsl`).
3. **Final assignment**:
   - `Static` arm: `ReturnType result = ClassName.methodName(methodArgs);`
   - `Instance` arm: `ReturnType result = new ClassName(ctorArgs).methodName(methodArgs);`

The call's actual-args list at each position reads from the entry's `javaName()`: `paramA`, `paramB`, ..., with `dsl` wherever a `FromDsl` entry sits.

`ArgCallEmitter.buildMethodBackedCallArgs` and `buildArgExtraction` retire at this seam for the four service permits' callers; the new emitter dispatches on `MappingEntry` and `ValueShape` directly. Both helpers stay alive for non-scope sites (condition, tableMethod, etc.) until later slices migrate them.

### ContextArgumentClassifier harvest update

`ContextArgumentClassifier.collectFromField` (`graphitron/src/main/java/no/sikt/graphitron/rewrite/ContextArgumentClassifier.java:109-121`) today walks `MethodBackedField.method().params()` to harvest each site's `ParamSource.Context` references for the per-name `ResolvedContextArg` fold. The four service permits drop out of that walk after R238 (they no longer implement `MethodBackedField`). The classifier grows a sibling arm that walks `ServiceField.serviceMethodCall()` and projects every `FromContext` entry across both rounds (`ctorArgs` and `methodArgs`) into the same per-name conflict-site map. `ConflictSite.site` widens from `MethodRef` to a sealed identifier with two arms — the existing `MethodRef` coordinate and a new `ServiceMethodCall` coordinate — and the `Rejection.AuthorError.TypeConflict` message renderer dispatches on the arm.

The agreed-type contract on `ResolvedContextArg` is unchanged; the emitter reads `GraphitronSchema.contextArguments().resolved().get(key).javaType()` to type the `GraphitronContext.getContextArgument(env, key)` cast — single source of truth, no emit-time reflection. The "locally-reflected context-key types" carryover the slice retires is the emitter side, not the walker's own param reflection: the walker reflects locally to feed the classifier's fold, and the emitter ignores `FromContext.javaType` entirely in favour of the agreed value.

Phase ordering: `ServiceMethodCallWalker` runs at `FieldBuilder` time and stores **site-local** declared types on `FromContext`; `GraphitronSchema`'s constructor invokes `ContextArgumentClassifier.classify` over the assembled fields and folds across both harvest arms (`MethodBackedField` and `ServiceField`); the emitter consumes the cached `Classification` at emit time. The walker has no read dependency on the classifier and no back-patching of `FromContext` is needed.

`MappingsConstantNameDedup.withResolvedChannel` (`graphitron/src/main/java/no/sikt/graphitron/rewrite/MappingsConstantNameDedup.java:191-198`) rebuilds the four service permits in its error-channel rewrite; each `f.method()` swaps to `f.serviceMethodCall()`, and the `MethodRef` ctor argument becomes `ServiceMethodCall`.

## Plumbing fixed by this slice

Every subsequent walker-carrier slice inherits these conventions.

### `WalkerResult<C>`

`Diagnostic` and `Severity` are new graphitron-side types introduced by this slice (no LSP-protocol type leaks into the model). `Diagnostic` is a record carrying `severity` (a graphitron `Severity` enum with arms `Error` / `Warning` / `Information` / `Hint`, paralleling LSP `DiagnosticSeverity`), `code`, `source` (always `"graphitron"`), `message`, `relatedInformation` (the LSP-shape fields R222's "unified diagnostic surface" sketches). The LSP module's `Diagnostics` projector maps graphitron `Diagnostic` to `org.eclipse.lsp4j.Diagnostic` at the wire boundary; no graphitron code below the LSP module imports lsp4j. R238 ships only the shape R238's wire conventions need; future slices may extend the record without source-incompat by adding optional components.

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

### `ServiceMethodCallError` as a sub-seal of `AuthorError`

R238 adds one new arm to `AuthorError` — a sealed sub-family scoped to this walker:

```java
sealed interface AuthorError permits UnknownName, Structural, ServiceMethodCallError, /* ...existing arms... */ {}

sealed interface ServiceMethodCallError extends AuthorError permits
    ServiceMethodCallError.ClassNotLoadable,
    ServiceMethodCallError.AmbiguousMethod,
    ServiceMethodCallError.ReturnTypeMismatch,
    ServiceMethodCallError.InstanceHolderMissingCtor,
    ServiceMethodCallError.CtorParamFromArg,
    ServiceMethodCallError.MultipleDslContextSlots,
    ServiceMethodCallError.ParameterNamesMissing,
    ServiceMethodCallError.ParameterUnbindable,
    ServiceMethodCallError.InputBeanShape,
    ServiceMethodCallError.ArgMappingParseError,
    ServiceMethodCallError.ArgMappingUnknownArg,
    ServiceMethodCallError.ArgMappingPathRejected
{ String message(); }
```

Each typed sub-arm carries its specific data (class names, method names, expected vs actual types, suggested fixes). Today's `Rejection.AuthorError.Structural(String reason)` arm is untouched: the ~265 non-R238 callsites continue producing it and continue projecting to a code-less wire diagnostic exactly as today. Subsequent walker slices (condition, tableMethod, externalField) each add their own sibling sub-seal (`ConditionWalkerError`, `TableMethodWalkerError`, ...) rather than piling typed arms under a single flat `Structural` — keeps `AuthorError` permits one-row-per-walker as the dimensional pivot scales, and lets each walker's arm-to-code mapping live next to its own family declaration.

### LSP wire conventions

- **Code namespace**: `graphitron.service-method-call.<arm>`. Codes are stable strings written next to the arm declaration, not derived from the type name (avoids coupling a wire contract to a Java identifier).
- **`source: "graphitron"`** on every diagnostic.
- **Severity**: walker `AuthorError`s project to `Severity.Error`.
- **Source attribution**: every diagnostic's primary `SourceLocation` is the field's own SDL location (`GraphQLFieldDefinition.getDefinition().getSourceLocation()`). For nested-path errors, the offending segment goes in `Diagnostic.relatedInformation` instead of retargeting the primary location.

Arm-to-code mapping for this slice:

| `AuthorError` arm | LSP `code` |
|---|---|
| `UnknownName(AttemptKind.SERVICE_METHOD)` | `graphitron.service-method-call.unknown-method` |
| `ServiceMethodCallError.ClassNotLoadable` | `graphitron.service-method-call.class-not-loadable` |
| `ServiceMethodCallError.AmbiguousMethod` | `graphitron.service-method-call.ambiguous-method` |
| `ServiceMethodCallError.ReturnTypeMismatch` | `graphitron.service-method-call.return-type-mismatch` |
| `ServiceMethodCallError.InstanceHolderMissingCtor` | `graphitron.service-method-call.instance-holder-missing-ctor` |
| `ServiceMethodCallError.CtorParamFromArg` | `graphitron.service-method-call.ctor-param-from-arg` |
| `ServiceMethodCallError.MultipleDslContextSlots` | `graphitron.service-method-call.multiple-dsl-context-slots` |
| `ServiceMethodCallError.ParameterNamesMissing` | `graphitron.service-method-call.parameter-names-missing` |
| `ServiceMethodCallError.ParameterUnbindable` | `graphitron.service-method-call.parameter-unbindable` |
| `ServiceMethodCallError.InputBeanShape` | `graphitron.service-method-call.input-bean-shape` |
| `ServiceMethodCallError.ArgMappingParseError` | `graphitron.service-method-call.arg-mapping-parse-error` |
| `ServiceMethodCallError.ArgMappingUnknownArg` | `graphitron.service-method-call.arg-mapping-unknown-arg` |
| `ServiceMethodCallError.ArgMappingPathRejected` | `graphitron.service-method-call.arg-mapping-path-rejected` |

### Orchestrator flow

`ValidationReport` gains a `walkerDiagnostics: List<Diagnostic>` slot alongside `errors` / `warnings`. When the walker returns `Err`, the orchestrator collects the typed `AuthorError`s, projects each to a `Diagnostic` per the arm-to-code table, and writes them into `walkerDiagnostics`. The field is excluded from the classified set; downstream generation is blocked when any field produced an `Err`. The four service permits never construct as `UnclassifiedField`; the wire to the editor (`Workspace.setBuildOutput` → `Diagnostics.compute` → `LanguageClient.publishDiagnostics`) is live as today, with `Diagnostics.validatorDiagnostics` gaining an arm projecting the walker `Diagnostic` family.

## Producer-side failure modes

The walker routes today's existing rejections through `WalkerResult.Err` as the listed typed arm:

| Source today | New arm |
|---|---|
| Class not loadable | `ServiceMethodCallError.ClassNotLoadable(className)` |
| Unknown service method | `UnknownName(SERVICE_METHOD, attempt, candidates)` |
| Multiple methods match name+arity (new) | `ServiceMethodCallError.AmbiguousMethod(className, methodName, candidateSignatures)` |
| Return-type mismatch | `ServiceMethodCallError.ReturnTypeMismatch(expected, actual)` |
| Instance ctor unresolvable from context/DSL | `ServiceMethodCallError.InstanceHolderMissingCtor(className, attemptedSignature)` |
| Ctor slot bound to SDL arg (new) | `ServiceMethodCallError.CtorParamFromArg(className, paramName)` |
| Two `DSLContext` slots in same round (new) | `ServiceMethodCallError.MultipleDslContextSlots(className, round)` |
| Missing `-parameters` compile flag | `ServiceMethodCallError.ParameterNamesMissing(className, methodName)` |
| Parameter not matching arg/context/source | `ServiceMethodCallError.ParameterUnbindable(paramName, available, suggestion)` |
| `argMapping` parse error | `ServiceMethodCallError.ArgMappingParseError(rawMapping, parserDetail)` |
| `argMapping` unknown arg ref | `ServiceMethodCallError.ArgMappingUnknownArg(javaParam, refHead, availableArgs)` |
| `argMapping` path rejected | `ServiceMethodCallError.ArgMappingPathRejected(javaParam, offendingSegment, reason)` |
| Input-bean shape rejections | `ServiceMethodCallError.InputBeanShape(beanClass, reason)` |

Each typed arm carries the data its message and its LSP `relatedInformation` need. The walker collects across stages; multiple errors from one field surface together.

## Out of scope

- `ChildField.ServiceTableField` and `ChildField.ServiceRecordField`. These two child-field service permits keep `MethodRef method` and continue implementing `MethodBackedField`. Their child-field-shaped state (`joinPath`, `sourceKey`, `loaderRegistration`) is orthogonal to the call payload; a subsequent slice migrates them with a child-field carrier alongside those slots.
- The four other `MethodBackedField` implementers (`ChildField.TableMethodField`, `ChildField.RecordTableMethodField`, `ChildField.ComputedField` for `@externalField`, `QueryField.QueryTableMethodTableField`). `MethodBackedField` and its `MethodRef method()` contract stay alive until those slices migrate them.
- Nested `@argMapping` syntax. R238 ships only the flat parser; R249 (`nested-argmapping-syntax`) reworks the parser's return shape and wires the `ObjectValue` branch in `GraphQLSelectionParser`. R238's `MappingEntry` / `ValueShape` model already represents nested paths uniformly (leaves carry their full `ArgPath`), so R249 lights up new producer logic without retrofitting downstream consumers.
- Restructuring `CallSiteExtraction`. R238 walks past it for its own callsites and emits the four leaf arms (`Direct`, `EnumValueOf`, `JooqConvert`, `NodeIdDecodeKeys`) at `Scalar.leafTransform`; the walker enforces the restriction. Lifting them into a typed `LeafTransform` sub-family — and retiring `ContextArg` / `NestedInputField` / `InputBean` from `CallSiteExtraction` — waits until the condition / tableMethod / externalField slices migrate their callsites off those arms.
- `ArgCallEmitter.buildMethodBackedCallArgs` / `buildArgExtraction`. These retire from the four service permits' callers but stay live for non-scope sites; later slices retire them per-callsite.

## Tests

Three tiers.

**Unit (`@UnitTier`)**, two test classes.

`ServiceMethodCallWalkerTest` parses a small SDL fragment, configures a test `ClassLoader` with fixture resolver classes, calls `walk`, and asserts on the sealed result. Coverage: one positive case per `MappingEntry` arm (`FromArg`, `FromContext`, `FromDsl`); one positive case per `ValueShape` arm (`Scalar`, `ListOf`, `RecordInput`, `JavaBeanInput`); one positive case per leaf-arm at a `Scalar` position (`Direct`, `EnumValueOf`, `JooqConvert`, `NodeIdDecodeKeys`); a walker-discipline test asserting the walker never produces a non-leaf `CallSiteExtraction` arm (`ContextArg`, `NestedInputField`, `InputBean`) at `Scalar.leafTransform`; one rejection case per `ServiceMethodCallError.*` arm plus `UnknownName(SERVICE_METHOD)`; arity-match disambiguation across overloads (one case where arity picks a single candidate, one where two candidates remain and `AmbiguousMethod` fires); deep-path bean instantiation (a path terminating at depth 2 on an input-object slot produces a `RecordInput` whose `fields` themselves contain a nested `RecordInput`); multi-arg ctor (a service class with `(DSLContext, ContextArg)` ctor produces `Instance` with two `ctorArgs`); site-local context-arg `javaType` (a fixture whose service slot declares a context arg of type `String` asserts the walker emits `FromContext` carrying `ClassName.get(String.class)` — the walker's own reflection of the param, independent of any cross-site fold); SDL-side cycle (an input-object that references itself transitively rejects with `ServiceMethodCallError.InputBeanShape` rather than recursing forever); shape recursion depth (a fixture with `RecordInput → ListOf → RecordInput → Scalar` exercises every shape arm in one descent).

`ServiceMethodCallEmitterTest` asserts on the returned `List<CodeBlock>` statement-by-statement. Coverage: the `Static` arm with and without a `FromDsl` method entry; the `Static` arm with a `RecordInput` method entry (asserts a `private static` helper is emitted and called); the `Instance` arm with a single `FromDsl` ctor entry; the `Instance` arm with a multi-arg ctor mixing `FromDsl` and `FromContext`; the cross-round case (instance ctor binds `dsl`, method also takes `DSLContext`) asserts that the prelude is emitted exactly once and both call positions read `dsl`.

**Pipeline (`@PipelineTier`)**: extend `ServiceRootFetcherPipelineTest` with end-to-end assertions on `walkerDiagnostics` for the rejection cases whose firing graphql-java doesn't catch upstream: `AmbiguousMethod`, `CtorParamFromArg`, `MultipleDslContextSlots`, `ParameterUnbindable`, `ArgMappingPathRejected`, `InputBeanShape`, `ReturnTypeMismatch`. Each fires from one Spec'd fixture and projects through to its arm-to-code mapping; the unit-tier provides per-arm coverage of the remaining `ServiceMethodCallError` arms. Existing positive-witness tests keep their author-facing wording but assert against the typed arms instead of stringly-typed rejection prose. Body-string assertions on fetcher emission retire (per `rewrite-design-principles.adoc`'s ban on code-string assertions) and get replaced by structural assertions on the `ServiceMethodCall` carrier. Extend `ContextArgumentTypeAgreementTest` (`@PipelineTier`) with a fixture exercising the new `ServiceField` harvest arm: two `@service` sites declaring the same context key fold into a single `ResolvedContextArg` when their site-local types match, and into a `Rejection.AuthorError.TypeConflict` carrying both `ServiceMethodCall` coordinates when they disagree.

**Compilation / Execution (`@CompilationTier` / `@ExecutionTier`)**: `graphitron-sakila-example` provides the regression net. The migration is structurally invariant on observable behaviour; `mvn install -Plocal-db` end-to-end-green is the safety net.

## Handoff (2026-05-28)

This item is **In Progress** with an additive landing on trunk through commit `f90a2f3`. The spec's vertical is not complete; this section records what shipped, what was deliberately deferred, and the concrete files a continuation session must touch.

### What shipped

Five commits on `claude/graphitron-rewrite`:

1. `9ebfa34` — roadmap Ready → In Progress.
2. `9451dff` — additive model types under `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/`: `ServiceMethodCall` (sealed `Static` / `Instance`), `MappingEntry` (sealed `FromArg` / `FromContext` / `FromDsl`), `ValueShape` (sealed `Scalar` / `ListOf` / `RecordInput` / `JavaBeanInput` plus `FieldBinding`), `ArgPath`, `ServiceField`, `WalkerResult<C>` (sealed `Ok` / `Err`), `Diagnostic` + `RelatedInformation`, `Severity` enum, `ServiceMethodCallError` sub-seal of `Rejection.AuthorError` with all 12 typed arms and stable `lspCode()`.
3. `9e60c7d` — `walker/ServiceMethodCallWalker` (translator over a resolved `MethodRef.Service`; not fresh reflection — see "Walker substrate" below) and `generators/ServiceMethodCallEmitter` (statement-list emitter with placeholders for composite `ValueShape` arms — see "Emitter composite arms" below).
4. `8c11a2f` — 17 unit-tier tests across `ServiceMethodCallWalkerTest` and `ServiceMethodCallEmitterTest`. Coverage: every `MappingEntry` arm, every non-composite `ValueShape` arm, `Static` / `Instance` carriers, multi-segment `ArgPath` flattening, `MultipleDslContextSlots` cross-round invariant, DSL prelude single-emission discipline.
5. `f90a2f3` — additive cutover on the four root sync `@service` permits. Each record gains `ServiceMethodCall serviceMethodCall` *alongside* the existing `MethodRef method`, and implements both `ServiceField` and `MethodBackedField`. `FieldBuilder.buildServiceField` wraps the resolver output with the walker, threads both slots into the constructor, and short-circuits to `UnclassifiedField` on walker `Err`. `MappingsConstantNameDedup.withResolvedChannel` rebuilds the four service permits carrying both slots through the error-channel rewrite. `TestFixtures.stubServiceCall(MethodRef.Service)` projects through the walker so tests fill the new slot from the existing `MethodRef.Service` fixtures. Plus CI-side drift-protection fixes (`RejectionSeverityCoverageTest` sample-map for 12 new arms; `typed-rejection.adoc` paragraph + drift enumeration update).

### What's deferred and why

The spec calls for the four permits to **drop** `MethodRef method` and **stop** implementing `MethodBackedField`. The additive landing keeps both slots live. The blocker is consumer migration on the emitter side:

- `TypeFetcherGenerator.buildServiceFetcherCommon` (at `graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java:1333`) reads `MethodRef.Service.callShape()` to decide static-vs-instance, calls `serviceCallTarget(MethodRef.Service, ClassName)` (same file, line 1393) to emit either `ClassName` or `new ClassName(dsl)`, and calls `ArgCallEmitter.buildMethodBackedCallArgs(ctx, method, null, conditionsClassName)` to render the call's actual-arg list. All three reach into `MethodRef.Service.params()` for the per-param `ParamSource` / `CallSiteExtraction` data.
- `ServiceMethodCallEmitter.emit(ServiceMethodCall, outputPackage)` covers the `Static` / `Instance` fork and the var-decl prelude correctly for `Scalar`, `FromContext`, and `FromDsl` entries (asserted by `ServiceMethodCallEmitterTest`). It does **not** yet cover composite `ValueShape` arms: `RecordInput` and `JavaBeanInput` return a `/* R238: bean construct ... pending helper extraction */ null` placeholder, and `ListOf` returns a similar placeholder. Wiring real emission for these arms requires plumbing through `InputBeanInstantiationEmitter` (at `graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/InputBeanInstantiationEmitter.java`) which today is driven by `CallSiteExtraction.InputBean` arms appearing in `MethodRef.Service.params()`.

The retirement step needs the emitter to produce output **equivalent** to today's `ArgCallEmitter.buildMethodBackedCallArgs` for every shape the existing service-permit population uses. Until composite `ValueShape` emission is finished, switching `buildServiceFetcherCommon` to consume `serviceMethodCall()` instead of `method()` would regress every `@service` method that takes an input-bean argument.

### Concrete next steps

Pick one of the two strategies; the choice depends on how the implementer values "spec-true retirement" vs "minimum-risk landing".

**Strategy A — finish the destructive cutover (spec-true).**

1. Wire composite `ValueShape` emission in `ServiceMethodCallEmitter`. The existing `InputBeanInstantiationEmitter` registers per-bean helpers and emits `create<Bean>(env.getArgument("arg"))` at the call site; the new emitter needs to feed the same registration flow from a `ValueShape.RecordInput` / `ValueShape.JavaBeanInput` walk. The shape-mapping is one-to-one with today's `CallSiteExtraction.InputBean` (each `FieldBinding` in the new model corresponds to one in the old). For `ListOf`, today's path uses the same helper with the `List<Bean>` parameter type; the emitter calls `createBeans(env.getArgument("arg"))` and the helper streams. Reuse `InputBeanInstantiationEmitter`'s code generation directly rather than re-deriving.
2. Migrate `TypeFetcherGenerator.buildServiceFetcherCommon` to take `ServiceMethodCall` instead of `MethodRef`. Replace `serviceCallTarget(MethodRef.Service, ClassName)` with a switch on `ServiceMethodCall.Static` / `Instance`. Replace `ArgCallEmitter.buildMethodBackedCallArgs(...)` with the statement-list returned by `ServiceMethodCallEmitter.emit(carrier, outputPackage)` — the existing one-line `addStatement("$T result = $L.$L($L)", ...)` becomes a `forEach(builder::addStatement)`.
3. Drop `MethodRef method` from the four permit record components. Drop `MethodBackedField` from each `implements` clause. Drop the `, method,` constructor arguments from every call site (the 4 in `FieldBuilder`, the 4 in `MappingsConstantNameDedup.withResolvedChannel`, plus every test site updated in `f90a2f3`'s `, method, TestFixtures.stubServiceCall(method), Optional.` pattern collapses to `, TestFixtures.stubServiceCall(method), Optional.`).
4. Update `ContextArgumentClassifier.collectFromField` (at `graphitron/src/main/java/no/sikt/graphitron/rewrite/ContextArgumentClassifier.java:109-121`). Today's `if (field instanceof MethodBackedField mbf)` branch catches the four permits via `MethodBackedField`; after retirement that path no longer fires for them. Add a sibling `if (field instanceof ServiceField sf)` branch that walks `sf.serviceMethodCall()` and projects every `FromContext` entry (across both `ctorArgs` and `methodArgs`) into the same per-name conflict-site map. The `ConflictSite` record at `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/ConflictSite.java` widens its `site` component to a sealed identifier with `MethodRef` and `ServiceMethodCall` arms; the existing `Rejection.AuthorError.TypeConflict` message renderer dispatches on the arm.
5. Drop `OutputField.peelToClassName(method.returnType())` in `QueryServiceRecordField.domainReturnType()` (`graphitron/src/main/java/no/sikt/graphitron/rewrite/model/QueryField.java`) in favour of `serviceMethodCall.javaReturnType()`; same swap in `MutationServiceRecordField.domainReturnType()`.
6. Retire `TestFixtures.stubServiceCall(MethodRef.Service)` — once the record drops `method`, the fixture no longer needs a paired `MethodRef`. Tests construct `ServiceMethodCall` either directly (record literals) or via the walker.
7. Add the `ValidationReport.walkerDiagnostics: List<Diagnostic>` slot and wire the orchestrator's collect-Err-exclude-field flow through to LSP. The `Diagnostics` projector arm in `graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/diagnostics/Diagnostics.java` maps each `ServiceMethodCallError.lspCode()` to the LSP `Diagnostic.code` field; `Diagnostic.severity` projects from graphitron `Severity` to lsp4j `DiagnosticSeverity` via an exhaustive arm-by-arm switch.
8. Author pipeline-tier tests per the Tests section: extend `ServiceRootFetcherPipelineTest` with `walkerDiagnostics` assertions for the seven rejection arms whose firing graphql-java doesn't catch upstream; extend `ContextArgumentTypeAgreementTest` with the `ServiceField` harvest arm fixture.
9. The full pipeline (`mvn -f graphitron-rewrite/pom.xml install -Plocal-db`) is the regression net. Watch the `graphitron-sakila-example` compilation-tier and execution-tier fixtures — they exercise every emit shape the four service permits land.

**Strategy B — accept the additive landing as the slice and split the retirement.**

Push the additive landing as R238's permanent shape. File a sibling roadmap item (`retire-methodref-from-service-permits` or similar) that does steps 1–6 above as its own vertical, with the composite-emission wiring as its primary work. Pros: R238 is shipped, the carrier is live, every future walker slice has a working precedent. Cons: the spec's "retires four legacy carryovers" language is unmet by R238 itself; the dual-slot state lives on trunk indefinitely until the sibling lands.

If choosing Strategy B, the Spec section above needs a paragraph noting the carve-out (the legacy slot stays live), and the "Out of scope" list grows an entry pointing at the sibling item.

### Walker substrate (deviation from spec)

The spec says `ServiceMethodCallWalker` reads "the field's SDL definition plus the codegen classloader directly. No graphitron-internal intermediate model." The shipped walker takes a resolved `MethodRef.Service` and translates it. This was a deliberate scope choice: today's `ServiceCatalog.reflectServiceMethod` is 1258 LOC of battle-tested reflection; duplicating it in `walker/internal/` per the spec's "extract" phrasing would have doubled the slice's footprint without changing the observable shape.

The translator boundary lives at one place: `FieldBuilder.buildServiceField` calls the resolver first, then passes the `MethodRef.Service` to the walker. A continuation session that prefers the spec's substrate can:

- Inline the resolver's parse-and-reflect work into the walker (move `ServiceDirectiveResolver.resolve`'s body under `walker/internal/`).
- Have `FieldBuilder` call the walker directly with `(GraphQLFieldDefinition, ClassLoader)`.
- Drop the `MethodRef.Service` intermediate from the four permit construction paths entirely.

This is independent of Strategy A vs B; the substrate choice is orthogonal to the retirement of the legacy record slot.

### Emitter composite arms (the actual implementation work)

`ServiceMethodCallEmitter.compositeHelperCall(beanClass, fields, path)` (lines 178–182) and `listExpression(list)` (lines 170–174) return placeholder `CodeBlock`s. The real path:

- For `RecordInput(class, fields)`: emit a static helper on the enclosing fetcher class named `create<TypeName>(Map<String,Object> map)` that walks `fields` field-by-field, materialising each via the same per-`ValueShape`-arm dispatch, then calls the record's canonical constructor positionally. The helper is deduplicated per bean class across the whole `*Fetchers` file.
- For `JavaBeanInput(class, fields)`: same, but instantiate via the no-arg constructor and apply each field via `set<JavaFieldName>(value)`.
- For `ListOf(path, element)`: emit `env.getArgument(path).stream().map(create<TypeName>).toList()` when the element is a composite; for `ListOf(path, Scalar(...))` the existing emitter's `Scalar` arm already handles the list-of-scalars case via the leaf's transform.

The existing `InputBeanInstantiationEmitter` and `InputBeanResolver` already perform this work for `CallSiteExtraction.InputBean`; reusing them keeps the helper-emission shape consistent across cutover sites.

### Tests still owed

Per the Tests section above the unit-tier coverage is partial:

- `ServiceMethodCallWalkerTest` does not yet have one rejection case per `ServiceMethodCallError.*` arm — only `MultipleDslContextSlots` fires from the current translator. The other arms (`ClassNotLoadable`, `AmbiguousMethod`, `ReturnTypeMismatch`, `InstanceHolderMissingCtor`, `CtorParamFromArg`, `ParameterNamesMissing`, `ParameterUnbindable`, `InputBeanShape`, `ArgMappingParseError`, `ArgMappingUnknownArg`, `ArgMappingPathRejected`) need walker-side production paths (today they're produced by the upstream resolver in different prose forms, not by the walker). A continuation session that absorbs reflection into the walker (per "Walker substrate" above) gets these production paths naturally; the additive landing doesn't.
- `ServiceMethodCallEmitterTest` doesn't yet cover composite `ValueShape` emission (the test for `RecordInput` would assert the emitted helper invocation; pending the emitter work above).
- Pipeline tier extensions (`ServiceRootFetcherPipelineTest`, `ContextArgumentTypeAgreementTest`) are not yet added.

### Risk inventory for the continuation session

- **`MappingsConstantNameDedup.withResolvedChannel` rebuild order.** The four `case` arms today reconstruct each permit with `f.method(), f.serviceMethodCall(), present` in component order. When `method` drops, the arm becomes `f.serviceMethodCall(), present`. Don't forget any.
- **`ConflictSite.site` widening** is an existing record component change; every reader currently does `cs.site().className()` and `cs.site().methodName()`. Widening to a sealed split breaks those readers unless the typed accessor stays callable via dispatch (`site instanceof MethodRef m ? m.className() : ((ServiceMethodCall) site).fqClassName()`). Plan the widening through the existing `Rejection.AuthorError.TypeConflict` message renderer.
- **`graphitron-sakila-example` is the regression net.** Watch its compilation and execution tiers. Any service method using an input bean is the first canary for composite `ValueShape` emission.
- **CI drift-protection.** Any further sealed-permit additions on `Rejection` (or on a future `Severity` enum extension) need both `RejectionSeverityCoverageTest.sampleFor()` and `graphitron-rewrite/docs/typed-rejection.adoc`'s enumeration updated in the same commit.

### Useful entry points

- The carrier model: `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/ServiceMethodCall.java` + siblings.
- The walker: `graphitron/src/main/java/no/sikt/graphitron/rewrite/walker/ServiceMethodCallWalker.java`. Read the class javadoc; it explains the substrate choice and stage layout.
- The emitter: `graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/ServiceMethodCallEmitter.java`. Read the class javadoc and `compositeHelperCall` / `listExpression` for the placeholder boundary.
- The cutover seam: `graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java`, search for `buildServiceField` and the four `serviceResolver.resolve(...)` switches.
- The legacy emitter path that still drives generation: `TypeFetcherGenerator.buildServiceFetcherCommon` at `graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java:1333`.
