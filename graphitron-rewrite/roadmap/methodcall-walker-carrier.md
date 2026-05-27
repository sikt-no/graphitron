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

The right-hand side of each non-DSL var-decl dispatches on the `MappingEntry`'s `ValueShape` directly. `Scalar` reads `env.getArgument(...)` (single- or multi-segment path) and applies its `LeafTransform`. `FromContext` reads a context key. `RecordInput` and `JavaBeanInput` extract to `private static` helpers that construct the bean from its `List<FieldBinding>` (recursion runs through the shared `ValueShape` family). `ListOf` extracts the list at its `sdlPath` and emits an element-wise mapper. The slice retires `CallSiteExtraction`'s top-level arms for these four permits: every value-source case it used to encode now appears as a `MappingEntry` arm, a `ValueShape` arm, or a `LeafTransform` arm (the four leaf arms move into the new `LeafTransform` sub-seal of `CallSiteExtraction`).

## Carrier shape

Five families co-locate in `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/`: `ServiceMethodCall`, `CompleteArgMapping` / `MappingEntry`, `ValueShape`, `ArgPath`, and a sub-seal added to the existing `CallSiteExtraction`. The model lives together because every emit-site needs to see the whole tree at once.

```java
// model/ServiceMethodCall.java
public sealed interface ServiceMethodCall permits Static, Instance {
    String fqClassName();
    String methodName();
    CompleteArgMapping methodArgs();
    TypeName javaReturnType();
}

public record Static(
    String fqClassName,
    String methodName,
    CompleteArgMapping methodArgs,
    TypeName javaReturnType
) implements ServiceMethodCall {}

public record Instance(
    String fqClassName,
    CompleteArgMapping ctorArgs,
    String methodName,
    CompleteArgMapping methodArgs,
    TypeName javaReturnType
) implements ServiceMethodCall {}
```

```java
// model/CompleteArgMapping.java
public record CompleteArgMapping(List<MappingEntry> entries) {
    public CompleteArgMapping { entries = List.copyOf(entries); }
}

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

public record Scalar(TypeName javaType, ArgPath sdlPath, CallSiteExtraction.LeafTransform leafTransform) implements ValueShape {}

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

```java
// model/CallSiteExtraction.java  (existing file — sub-seal added by this slice)
public sealed interface CallSiteExtraction permits
    CallSiteExtraction.LeafTransform,         // new sub-seal
    CallSiteExtraction.ContextArg,            // unchanged, retained for non-R238 callers
    CallSiteExtraction.NestedInputField,      // unchanged, retained for non-R238 callers
    CallSiteExtraction.InputBean              // unchanged, retained for non-R238 callers
{
    /** Leaf transforms applied at a Scalar position. R238's only Extraction surface. */
    sealed interface LeafTransform extends CallSiteExtraction permits Direct, EnumValueOf, JooqConvert, NodeIdDecodeKeys {}

    record Direct() implements LeafTransform {}
    record EnumValueOf(String enumClassName) implements LeafTransform {}
    record JooqConvert(String columnJavaName) implements LeafTransform {}
    sealed interface NodeIdDecodeKeys extends LeafTransform permits SkipMismatchedElement, ThrowOnMismatch { /* unchanged */ }

    /* ContextArg / NestedInputField / InputBean retain their existing shapes; they're the
       three arms R238's new model absorbs (ContextArg → FromContext; NestedInputField →
       multi-segment ArgPath; InputBean → RecordInput / JavaBeanInput) and stay alive in
       CallSiteExtraction only for non-R238 callsites (condition, tableMethod, externalField). */
}
```

`MappingEntry` has three flat arms — none recursive, each carrying its own variant-specific component types (no lifted `javaType()` on the interface). `FromArg` carries a `ValueShape` whose `javaType()` is the slot's Java type; `FromContext` carries an explicit `TypeName javaType`; `FromDsl` carries no fields because the variant identity already determines `DSLContext.class` and the conventional `dsl` local name. Consumers switch on the arm and read the precise component.

`ValueShape` is the unit of recursion, walked through `RecordInput.fields`, `JavaBeanInput.fields`, and `ListOf.elementShape`. Each `ValueShape` node carries its Java type, walker-derived from the SDL schema, so the emitter generates type-correct casts at every step.

Paths live on the data-bearing leaves (`Scalar`, `ListOf`), not on composites. In default mapping every sibling field's `sdlPath` shares a prefix; in the forward-compatible nested `@argMapping` syntax (`paramName: { fieldA: input.x, fieldB: input.other.y, ... }`) sibling paths are independent. The model represents both uniformly: only the path values differ. The nested-form parser isn't part of R238's scope — R249 (`nested-argmapping-syntax`) wires it through `GraphQLSelectionParser`, parallel to R69 (`experimental-construct-type`) on the output side. R238's model doesn't need changes when R249 lands.

`RecordInput` and `JavaBeanInput` are sibling arms rather than one arm with a flag, so the emitter pattern-matches the construction strategy without reflecting on the bean class at emit time. `LeafTransform` is structurally locked to `Scalar` (no other arm permits it).

The carrier itself is sealed. `Static` carries one round (`methodArgs`); `Instance` carries two (`ctorArgs` then `methodArgs`). Field order on `Instance` mirrors evaluation order. The walker enforces two asymmetries across the rounds: `FromArg` is invalid in `ctorArgs` (field arguments aren't in scope at ctor time, raising `Structural.CtorParamFromArg`), and two `FromDsl` entries cannot coexist in the same round (the shared `dsl` local would collide, raising `Structural.MultipleDslContextSlots`). Multi-arg constructors with `FromContext` and `FromDsl` slots are first-class; the legacy `(DSLContext)`-only ctor restriction retires in this slice. `FromContext` and `FromDsl` cannot reach bean fields by construction — `FieldBinding.shape` is `ValueShape`, and `ValueShape` has no such arms.

`javaReturnType` on the carrier is the Java `TypeName` of the method's declared return type, used by the emitter to type the `result` local. The GraphQL-side classification of the field's return (`TableBoundReturnType` / `ResultReturnType` / `ScalarReturnType` / `PolymorphicReturnType`) lives on the existing `ReturnTypeRef` record component on each permit; the walker validates that the Java return type is shape-compatible with `ReturnTypeRef` at stage 5 and raises `Structural.ReturnTypeMismatch` on disagreement. The two classifications are orthogonal — Java-side vs GraphQL-side — and stay in their respective homes rather than merging into a single sealed shape.

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

1. **Parse the `@service` directive.** Read the service class FQ name, method name, and `argMapping` string. The argMapping parser returns a sealed `ParsedArgMapping.{Flat | Nested}` result:

   ```java
   public sealed interface ParsedArgMapping permits Flat, Nested {
       record Flat(LinkedHashMap<String, List<String>> javaParamToPath) implements ParsedArgMapping {}
       record Nested(/* per-entry decomposition, populated by R249 */) implements ParsedArgMapping {}
   }
   ```

   R238 ships only the `Flat` arm; R249 (`nested-argmapping-syntax`) lights up the `Nested` arm by dispatching on `ObjectValue` from `GraphQLSelectionParser`. The sealed return shape lets R249 extend the parser without retrofitting R238's call sites.
2. **Load class and resolve method.** Use the classloader; collect every declared method whose name matches. Filter by ctor+method arity match against the field's binding budget (SDL args plus declared context keys plus any `DSLContext` slots). Reject zero matches with `UnknownName(SERVICE_METHOD, ...)` and multi-match with `Structural.AmbiguousMethod(className, methodName, candidateSignatures)`. Check `-parameters` availability on the surviving candidate.
3. **Build `CompleteArgMapping` per round.** For each round (the ctor for `Instance` carriers, plus the method always), iterate the Java parameter slots and classify each:
   - `DSLContext`-typed slot → `FromDsl()`.
   - Slot name matching a declared context key → `FromContext(name, localJavaType, key)`, where `localJavaType` is the walker's own reflection of the Java param's declared type at this site. The walker has no read dependency on `ContextArgumentClassifier`; the classifier later harvests these site-local types from the assembled `ServiceField` carriers and folds them per name into `ResolvedContextArg`. The emitter reads the cross-site agreed type from `GraphitronSchema.contextArguments().resolved().get(key).javaType()` at emit time. See "ContextArgumentClassifier harvest update" under Consumer migration.
   - Otherwise an SDL arg source → resolve the slot's `@argMapping` entry (explicit override or implicit identity default) to an `ArgPath` from the field args root; produce `FromArg(name, shape)` per stage 4. In `ctorArgs`, `FromArg` raises `Structural.CtorParamFromArg`. Two `FromDsl()` entries in the same round raise `Structural.MultipleDslContextSlots`.
   
   Implicit and explicit entries are indistinguishable downstream — the artifact is "complete" in the sense that every parameter slot has a `MappingEntry`, even those the user didn't mention.
4. **Derive `ValueShape` for each `FromArg` entry.** Starting from the SDL type at the path's leaf and the Java target type, recurse directly on the SDL type:
   - `GraphQLScalarType` / `GraphQLEnumType` → `Scalar(javaType, sdlPath, leafTransform)` where `leafTransform` is the matching `CallSiteExtraction.LeafTransform` arm (`Direct` / `EnumValueOf` / `JooqConvert` / `NodeIdDecodeKeys`).
   - `GraphQLList` → `ListOf(sdlPath, elementShape)`; recurse on the element SDL type with the element's Java target.
   - `GraphQLInputObjectType` → `RecordInput` or `JavaBeanInput` (pick by inspecting the Java class — record component layout vs no-arg ctor + setters); recurse on each SDL field with its matching Java component target, producing one `FieldBinding` per field.

   The recursion is a four-way `switch` over `GraphQLInputType`; the visitor framework's polymorphic dispatch adds no value over the direct form here because the four cases are known and exhaustive. A `Set<GraphQLNamedType>` visited-set guards against cycles in the SDL input-object graph; existing `InputBeanResolver` carries the equivalent guard, and its discipline transfers verbatim.

   The Java-driven descent (forward-compatible with R249's nested `@argMapping` syntax) follows the same shape recursion but iterates the Java target's record-components or JavaBean properties as the controlling structure, looking up each leaf's SDL path independently through a flat `GraphQLInputObjectType.getField` chain. Both forms converge on the same `ValueShape` tree. Validation errors map to `Structural.InputBeanShape` (bean-shape rejections), `Structural.ArgMappingPathRejected` (path doesn't resolve), and `Structural.ParameterUnbindable` (Java target doesn't match the SDL leaf).
5. **Validate the Java return type against `ReturnTypeRef`.** Compute the Java method's declared return type, check shape-compatibility with the field's `ReturnTypeRef` arm, and raise `Structural.ReturnTypeMismatch` on disagreement. Store the validated `TypeName` for the emitter to use as the `result` local's declared type.
6. **Build the carrier arm.** Static methods produce `Static(fqClassName, methodName, methodArgs, javaReturnType)`. Instance methods produce `Instance(fqClassName, ctorArgs, methodName, methodArgs, javaReturnType)`.
7. **Return the result.** `Ok(...)` on success; `Err(authorErrors, diagnostics)` when any stage produced typed errors. Errors accumulate; the walker doesn't short-circuit at the first failure.

The walker is invoked from `FieldBuilder` at each constructor site for the four service permits. Reflection helpers extracted from today's `ServiceCatalog.reflectServiceMethod` and `ArgBindingMap`, plus the new direct-recursive `ValueShape` builder, live in `walker/internal/`; they no longer surface on the public model boundary. The slice also adds the `CallSiteExtraction.LeafTransform` sub-seal to `CallSiteExtraction.java`; the four leaf arms (`Direct`, `EnumValueOf`, `JooqConvert`, `NodeIdDecodeKeys`) move to implement `LeafTransform`, which itself extends `CallSiteExtraction`. Existing callers reading `CallSiteExtraction` still resolve; new R238 callsites accept the narrower `LeafTransform`.

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

### ContextArgumentClassifier harvest update

`ContextArgumentClassifier.collectFromField` (`graphitron/src/main/java/no/sikt/graphitron/rewrite/ContextArgumentClassifier.java:109-121`) today walks `MethodBackedField.method().params()` to harvest each site's `ParamSource.Context` references for the per-name `ResolvedContextArg` fold. The four service permits drop out of that walk after R238 (they no longer implement `MethodBackedField`). The classifier grows a sibling arm that walks `ServiceField.serviceMethodCall()` and projects every `FromContext` entry across both rounds (`ctorArgs` and `methodArgs`) into the same per-name conflict-site map. `ConflictSite.site` widens from `MethodRef` to a sealed identifier with two arms — the existing `MethodRef` coordinate and a new `ServiceMethodCall` coordinate — and the `Rejection.AuthorError.TypeConflict` message renderer dispatches on the arm.

The agreed-type contract on `ResolvedContextArg` is unchanged; the emitter reads `GraphitronSchema.contextArguments().resolved().get(key).javaType()` to type the `GraphitronContext.getContextArgument(env, key)` cast — single source of truth, no emit-time reflection. The "locally-reflected context-key types" carryover the slice retires is the emitter side, not the walker's own param reflection: the walker reflects locally to feed the classifier's fold, and the emitter ignores `FromContext.javaType` entirely in favour of the agreed value.

Phase ordering: `ServiceMethodCallWalker` runs at `FieldBuilder` time and stores **site-local** declared types on `FromContext`; `GraphitronSchema`'s constructor invokes `ContextArgumentClassifier.classify` over the assembled fields and folds across both harvest arms (`MethodBackedField` and `ServiceField`); the emitter consumes the cached `Classification` at emit time. The walker has no read dependency on the classifier and no back-patching of `FromContext` is needed.

`MappingsConstantNameDedup.dedupConstantNames` (`graphitron/src/main/java/no/sikt/graphitron/rewrite/MappingsConstantNameDedup.java:191-198`) rebuilds the four service permits in its dedup pass; each `f.method()` swaps to `f.serviceMethodCall()`, and the `MethodRef` ctor argument becomes `ServiceMethodCall`.

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

Each typed sub-arm carries its specific data (class names, method names, expected vs actual types, suggested fixes). `Structural.Other(String reason)` is the transitional catch-all that wraps today's `Rejection.AuthorError.Structural(String reason)` for callsites outside R238's migration set; the four service permits' callsites produce only the typed sub-arms above. Non-R238 callsites (condition, tableMethod, externalField) continue producing `Rejection.AuthorError.Structural(reason)` until their slice migrates; that prose payload reaches the wire through `Structural.Other` and projects to the generic `graphitron.structural` LSP code. **R238 introduces no new `Structural.Other` callsites** — the arm exists only so the migrating decomposition doesn't break the existing-callsite contract while subsequent slices retire it from the inside out.

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

## Out of scope

- `ChildField.ServiceTableField` and `ChildField.ServiceRecordField`. These two child-field service permits keep `MethodRef method` and continue implementing `MethodBackedField`. Their child-field-shaped state (`joinPath`, `sourceKey`, `loaderRegistration`) is orthogonal to the call payload; a subsequent slice migrates them with a child-field carrier alongside those slots.
- The four other `MethodBackedField` implementers (`ChildField.TableMethodField`, `ChildField.RecordTableMethodField`, `ChildField.ComputedField` for `@externalField`, `QueryField.QueryTableMethodTableField`). `MethodBackedField` and its `MethodRef method()` contract stay alive until those slices migrate them.
- Nested `@argMapping` syntax. The `ParsedArgMapping.Nested` arm is sealed in but lit up by R249, which wires the `ObjectValue` branch in `GraphQLSelectionParser`.
- R222's umbrella note. The unified-`MethodCall`-on-`MethodBackedField` framing R222 stated is superseded by R238's per-carrier sibling design; the umbrella entry needs a refresh tracked separately, but R238 itself ships narrow.
- `ArgCallEmitter.buildMethodBackedCallArgs` / `buildArgExtraction`. These retire from the four service permits' callers but stay live for non-scope sites; later slices retire them per-callsite.

## Tests

Three tiers.

**Unit (`@UnitTier`)**, two test classes.

`ServiceMethodCallWalkerTest` parses a small SDL fragment, configures a test `ClassLoader` with fixture resolver classes, calls `walk`, and asserts on the sealed result. Coverage: one positive case per `MappingEntry` arm (`FromArg`, `FromContext`, `FromDsl`); one positive case per `ValueShape` arm (`Scalar`, `ListOf`, `RecordInput`, `JavaBeanInput`); one positive case per `LeafTransform` arm (`Direct`, `EnumValueOf`, `JooqConvert`, `NodeIdDecodeKeys`); one rejection case per `Structural.*` sub-arm plus `UnknownName(SERVICE_METHOD)`; arity-match disambiguation across overloads (one case where arity picks a single candidate, one where two candidates remain and `AmbiguousMethod` fires); deep-path bean instantiation (a path terminating at depth 2 on an input-object slot produces a `RecordInput` whose `fields` themselves contain a nested `RecordInput`); multi-arg ctor (a service class with `(DSLContext, ContextArg)` ctor produces `Instance` with two `ctorArgs`); site-local context-arg `javaType` (a fixture whose service slot declares a context arg of type `String` asserts the walker emits `FromContext` carrying `ClassName.get(String.class)` — the walker's own reflection of the param, independent of any cross-site fold); SDL-side cycle (an input-object that references itself transitively rejects with `Structural.InputBeanShape` rather than recursing forever); shape recursion depth (a fixture with `RecordInput → ListOf → RecordInput → Scalar` exercises every shape arm in one descent).

`ServiceMethodCallEmitterTest` asserts on the returned `List<CodeBlock>` statement-by-statement. Coverage: the `Static` arm with and without a `FromDsl` method entry; the `Static` arm with a `RecordInput` method entry (asserts a `private static` helper is emitted and called); the `Instance` arm with a single `FromDsl` ctor entry; the `Instance` arm with a multi-arg ctor mixing `FromDsl` and `FromContext`; the cross-round case (instance ctor binds `dsl`, method also takes `DSLContext`) asserts that the prelude is emitted exactly once and both call positions read `dsl`.

**Pipeline (`@PipelineTier`)**: extend `ServiceRootFetcherPipelineTest` with end-to-end assertions on `walkerDiagnostics` for the rejection cases whose firing graphql-java doesn't catch upstream: `AmbiguousMethod`, `CtorParamFromArg`, `MultipleDslContextSlots`, `ParameterUnbindable`, `ArgMappingPathRejected`, `InputBeanShape`, `ReturnTypeMismatch`. Each fires from one Spec'd fixture and projects through to its arm-to-code mapping; the unit-tier provides per-arm coverage of the remaining structural arms. Existing positive-witness tests keep their author-facing wording but assert against the typed arms instead of stringly-typed rejection prose. Body-string assertions on fetcher emission retire (per `rewrite-design-principles.adoc`'s ban on code-string assertions) and get replaced by structural assertions on the `ServiceMethodCall` carrier. Extend `ContextArgumentTypeAgreementTest` (`@PipelineTier`) with a fixture exercising the new `ServiceField` harvest arm: two `@service` sites declaring the same context key fold into a single `ResolvedContextArg` when their site-local types match, and into a `Rejection.AuthorError.TypeConflict` carrying both `ServiceMethodCall` coordinates when they disagree.

**Compilation / Execution (`@CompilationTier` / `@ExecutionTier`)**: `graphitron-sakila-example` provides the regression net. The migration is structurally invariant on observable behaviour; `mvn install -Plocal-db` end-to-end-green is the safety net.
