---
id: R238
title: MethodCall walker carrier (R222 foundation slice)
status: Spec
bucket: structural
priority: 3
theme: structural-refactor
depends-on: []
created: 2026-05-25
last-updated: 2026-05-25
---

# MethodCall walker carrier (R222 foundation slice)

R222's Stage 1 foundation slice, scope-expanded after self-review to cover all of `MethodBackedField`'s implementer set end-to-end. R222 names the target shape (per-field carriers populated by producers reading graphql-java primitives directly, validity riding on a `WalkerResult<Ok|Err>` wrapper) but the pattern hasn't landed anywhere in tree yet; every Stage 2/3 slice that follows will inherit whatever conventions Stage 1 sets for the producer substrate, the slot's home, the LSP `Diagnostic` code namespace, and source attribution. The slice ships:

- A new `MethodCall` carrier on `MethodBackedField`, *replacing* the existing `MethodRef method()` accessor. All ten implementer records swap their `method: MethodRef` component for `methodCall: MethodCall`. `MethodCall` dissolves `MethodRef.NonCondition` (and its `Service` / `StaticOnly` sub-arms); `MethodRef.ConditionFilter` survives outside this slice's scope until `@condition` retires from the `MethodRef` family in a future slice.
- A single producer (`MethodCallWalker`) reading SDL primitives plus the codegen classloader directly. It subsumes today's `ServiceDirectiveResolver`, `ServiceCatalog.reflectServiceMethod`, and the equivalent directive resolution for `@tableMethod` and `@externalField`. The walker IS the directive resolver for the ten permits; no intermediate graphitron-internal `MethodRef` model survives.
- Consumer migration for all ten `MethodBackedField` implementers (four sync `@service` permits, two DataLoader `@service` child variants, two `@tableMethod` permits, `ChildField.RecordTableMethodField`, `ChildField.ComputedField`).
- A shared `MethodCallEmitter` parameterised on `MethodBackedField`, consumed polymorphically by all ten consumers.
- Sealed sub-arms of `AuthorError.Structural` replacing the existing stringly-typed-reason shape, projecting per-arm to LSP `Diagnostic` codes in the `graphitron.method-call.<leaf>` namespace. No stringly-typed `code: String` field on the wire-format boundary; the projection rides on the type system.
- `UnclassifiedField` retirement for these ten permits: classification of method-backed fields routes failures through `WalkerResult.Err` collected by the orchestrator, never falling back to `UnclassifiedField`. The wider `UnclassifiedField` retirement (R222 Stage 4) lands per other carrier slices.

A new `ServiceField` marker extending `MethodBackedField` (pure, no declarations) anchors the service-call subset for future consumers that need to dispatch on it.

## Target emitted code

The reducer backtracks from this shape. Every service-backed field's lambda body, after the slice, reads as a straight walk over the carrier's binding list followed by the call line:

```java
// QueryServiceRecordField example, post-slice
DSLContext dsl = graphitronContext(env).getDslContext(env);                  // only when needsDsl
DomainTypeA paramA = constructParamA(env);                                   // one per ParamBinding arm
DomainTypeB paramB = constructParamB(env);
DomainResult result = new DomainService(dsl).method(paramA, paramB);
```

For `@tableMethod` and `@externalField` (not migrated in this slice but populated by the same walker), the same shape with a different surrounding composition: a `Table`-returning call followed by a `dsl.select(...).from(table)...fetch()` chain, or a `Record`-returning call that the field's lambda returns directly. The slice's claim is that one walk over `field.methodCall().bindings()` produces the var-decls, and a second short composition produces the call line; today, `ArgCallEmitter.buildMethodBackedCallArgs` interleaves these two by inlining each param's extraction directly into the call-line argument list. Lifting the per-param expression to a named local is the structural shift the carrier enables.

The "construct `ParamX`" expression is a single sealed-arm dispatch (today's `CallSiteExtraction`, retained as `ArgConstruction`); whether it inlines as an expression or extracts as a `private static T constructParamX(DataFetchingEnvironment env)` helper is the emitter's choice per arm. For this slice, only `InputBean` extracts to a helper (preserving today's `InputBeanInstantiationEmitter` output); the other arms inline. The carrier shape doesn't force the choice.

## Slot landing on `MethodBackedField`

The slot's home is the existing `MethodBackedField` interface, which already names the property "this field carries a method call by definition." The slice **replaces** the interface's single accessor:

```java
// graphitron/src/main/java/no/sikt/graphitron/rewrite/model/MethodBackedField.java
public interface MethodBackedField {
    MethodCall methodCall();     // replaces method()
}
```

A new pure-marker interface captures the service-call subset:

```java
// graphitron/src/main/java/no/sikt/graphitron/rewrite/model/ServiceField.java
public interface ServiceField extends MethodBackedField {
    // marker; no new declarations
}
```

**Who implements what.** The six service-call permits switch from implementing `MethodBackedField` directly to implementing `ServiceField` (and inherit `MethodBackedField` transitively):

- `QueryField.QueryServiceTableField`
- `QueryField.QueryServiceRecordField`
- `MutationField.MutationServiceTableField`
- `MutationField.MutationServiceRecordField`
- `ChildField.ServiceTableField`
- `ChildField.ServiceRecordField`

The other four `MethodBackedField` implementers keep implementing the broader interface directly: `QueryField.QueryTableMethodTableField`, `ChildField.TableMethodField`, `ChildField.RecordTableMethodField`, `ChildField.ComputedField`.

**Record components.** All ten implementer records swap `MethodRef method` for `MethodCall methodCall`. The auto-generated record accessor satisfies the interface contract. Example for `QueryServiceRecordField`:

```java
record QueryServiceRecordField(
    String parentTypeName,
    String name,
    SourceLocation location,
    ReturnTypeRef returnType,
    MethodCall methodCall,                  // replaces MethodRef method
    Optional<ErrorChannel> errorChannel
) implements QueryField, ServiceField, WithErrorChannel
```

`MethodRef.NonCondition` (and its `Service` / `StaticOnly` sub-arms) retires; the slice has no surviving `MethodRef`-typed reader of these directives. `MethodRef.ConditionFilter` is unaffected; the `@condition` directive's binding family lives outside this slice and outside `MethodBackedField`.

**Why not `GraphitronField` and why not Optional.** R222's destination sketch put `methodCall()` as a universal slot on `GraphitronField`; this slice corrects that. A column-projected leaf is never a method call; the universal slot would force a `No<MethodCall>` arm that no consumer ever reads, and the producer's exhaustiveness benefit from `No<Family>` is paid in dead code here. The producer is directive-gated (`MethodBackedField` membership is the gate); slot-absence is encoded by interface non-membership.

## Carrier shape

```java
// graphitron/src/main/java/no/sikt/graphitron/rewrite/model/MethodCall.java
public record MethodCall(
    ServiceCallTarget target,            // how to obtain the receiver expression
    String methodName,
    List<ParamBinding> bindings,         // ordered, one per Java method parameter
    ReturnTypeShape returnShape
) {}

public sealed interface ServiceCallTarget
    permits ServiceCallTarget.Static, ServiceCallTarget.InstanceWithDsl {
    String fqClassName();
}

public sealed interface ParamBinding
    permits FromEnvArg, FromContextKey, FromDslContext, FromBatchKeys, FromSourceRow {
    String localName();                  // var-decl identifier emitted at call site
    TypeName javaType();                 // var-decl type
}

public record FromEnvArg(
    String localName, TypeName javaType,
    String argName,                      // GraphQL argument name (head of arg-binding path)
    ArgConstruction construction         // how to turn the env entry into the typed value
) implements ParamBinding {}

public record FromContextKey(
    String localName, TypeName javaType, String contextKey
) implements ParamBinding {}

public record FromDslContext(String localName) implements ParamBinding {}

public record FromBatchKeys(
    String localName, TypeName javaType,
    KeyContainer container               // LIST / SET / MAPPED_SET
) implements ParamBinding {}

public record FromSourceRow(
    String localName, TypeName javaType, SourceRowShape shape
) implements ParamBinding {}
```

`ArgConstruction` is today's `CallSiteExtraction` carried unchanged: `Direct`, `EnumValueOf`, `ContextArg`, `JooqConvert`, `NestedInputField`, `NodeIdDecodeKeys`, `InputBean`. The slice does not redesign extraction; the carrier just lifts those arms one level up so the emitter walks bindings instead of method-params plus extraction in lockstep.

`ReturnTypeShape` is a thin wrapper over a JavaPoet `TypeName` plus the jOOQ-shape hint (`ScalarReturnType`, `PojoResultType`, `Result<XRecord>`, `List<XRecord>`, `XRecord`); the existing return-type computation in `computeMutationServiceRecordReturnType` and friends lifts onto this. Bookkeeping; nothing structural.

`FromBatchKeys` and `FromSourceRow` cover the `ChildField.ServiceTableField` / `ServiceRecordField` `Sourced` param case and the parent-row case; the slot lives on those records even though their consumer migration is a follow-up slice. The carrier admits both root and child shapes; consumer migration is incremental.

## Producer (`MethodCallWalker`)

```java
// graphitron/src/main/java/no/sikt/graphitron/rewrite/walker/MethodCallWalker.java
public final class MethodCallWalker {
    public WalkerResult<MethodCall> walk(
        GraphQLFieldDefinition field,
        DirectiveContext directiveCtx,    // @service / @tableMethod / @externalField / @field-name extraction
        ClassLoader codegenLoader         // for reflection on the user's resolver class
    );
}
```

**Substrate is the SDL directly**, paired with the codegen classloader for reflection. `MethodRef` and its directive resolvers don't intermediate. The walker subsumes today's directive-resolution machinery for the 10 permits' directives: `ServiceDirectiveResolver.resolve`, `ServiceCatalog.reflectServiceMethod`, the `@tableMethod` and `@externalField` resolution paths. Reflection helpers (`ArgBindingMap.of`, `InputBeanResolver.enrich`, signature-shape checks) may survive as walker-internal helpers, but they no longer appear in the public model surface.

**Walker stages** (one pass per method-backed field):

1. **Parse directive.** Read `@service` / `@tableMethod` / `@externalField` / `@field(name:)` off the field's SDL definition. Determine the target class FQ name, method name, and `argMapping` string. Failure: SDL-shape `AuthorError.Structural.*` arm.
2. **Load class and resolve method.** Use the codegen classloader to load the class; locate the method by name and shape; check `-parameters` availability. Failures: typed arms (class-not-loadable, unknown-method, parameter-names-missing).
3. **Bind arguments.** Parse `argMapping`, resolve binding paths against the field's SDL arguments, derive the `ArgConstruction` for each method parameter. Failures: typed arms (arg-mapping parse error, unknown arg ref, path rejected, parameter unbindable, input-bean shape).
4. **Build `MethodCall`.** Resolve `ServiceCallTarget` from the method's static / instance shape (instance methods require a `(DSLContext)` constructor). Build the `ParamBinding` list from the resolved params:
   - `@service` arg with extraction → `FromEnvArg`
   - Context-key arg → `FromContextKey`
   - `DSLContext`-typed param → `FromDslContext("dsl")` (shared local; emitter dedupes if multiple bindings ask for it)
   - DataLoader batch-key param → `FromBatchKeys(localName="keys", container)`
   - Parent-row param → `FromSourceRow`
5. **Build `ReturnTypeShape`** from the method's return type plus the field's declared return type; check shape compatibility (return-type-mismatch is a typed `Structural` arm).
6. **Emit result.** `Ok(MethodCall(...), [])` on success; `Err(authorErrors, diagnostics)` if any step produced typed errors. `Err` collects across stages; the walker doesn't short-circuit at the first failure.

**Invocation point.** The walker is invoked from `FieldBuilder` at each constructor site for a `MethodBackedField` implementer. The sites today are around the `ServiceDirectiveResolver.Resolved.Success` arms (root query, root mutation, child) and the analogous `@tableMethod` / `@externalField` resolution sites; under the slice, those resolution sites collapse into a single walker call. (The earlier draft cited specific line numbers; these will drift with refactoring, so naming the call by enclosing method + permit-being-constructed is more durable than line citations.)

**No fallback to `UnclassifiedField`.** When `walk` returns `Err`, the orchestrator collects the typed `AuthorError`s into the build's diagnostic stream and the field is excluded from the classified set; downstream generation is blocked when any `Err` is present. The 10 permits never construct as `UnclassifiedField`. See the dedicated section below.

**Unit-testability** follows from the substrate boundary: parse an SDL fragment, configure a small test `ClassLoader` with the fixture resolver class, call `walk`, assert on the sealed result. The walker is a pure function of its inputs; no graphitron classification context.

## Consumer migration

All ten `MethodBackedField` implementers' consumers migrate in this slice. Each reads `field.methodCall()` and composes the emitter's output into its own surrounding context. The complete migration list:

| Permit | Consumer entry point (`TypeFetcherGenerator`) |
|---|---|
| `QueryField.QueryServiceTableField` | `buildServiceFetcherCommon` |
| `QueryField.QueryServiceRecordField` | `buildServiceFetcherCommon` |
| `MutationField.MutationServiceTableField` | `buildServiceFetcherCommon` |
| `MutationField.MutationServiceRecordField` | `buildServiceFetcherCommon` |
| `ChildField.ServiceTableField` | `buildServiceRowsMethod` + `buildServiceDataFetcher` |
| `ChildField.ServiceRecordField` | `buildServiceRowsMethod` + `buildServiceDataFetcher` |
| `QueryField.QueryTableMethodTableField` | `buildQueryTableMethodFetcher` |
| `ChildField.TableMethodField` | `buildChildTableMethodFetcher` |
| `ChildField.RecordTableMethodField` | `buildRecordBasedDataFetcher` + `SplitRowsMethodEmitter` |
| `ChildField.ComputedField` | the computed-field fetcher emitter |

**Shared emitter.** A new utility class encapsulates the var-decls-plus-call-expression emission, parameterised on `MethodBackedField`:

```java
// graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/MethodCallEmitter.java
public final class MethodCallEmitter {
    public static Emission emit(MethodBackedField field);

    public record Emission(List<CodeBlock> varDecls, CodeBlock callExpression) {}
}
```

`emit` reads `field.methodCall()`, walks `bindings()` to produce the var-decl list (one `Type localName = constructX(env);` per binding, with `FromDslContext` deduped into a single `DSLContext dsl = ...` prelude when present), and composes the call expression from `target` + `methodName` + binding local-names. Each consumer wraps the result in its own context:

- **Sync `@service` fetchers** (`buildServiceFetcherCommon`): assignment to typed local, return value.
- **DataLoader `@service` rows methods** (`buildServiceRowsMethod`): assignment, then return statement.
- **`@tableMethod` fetchers**: assignment to `Table`-typed local, then `dsl.select(...).from(table)...fetch()` composition.
- **`RecordTableMethodField`**: split-rows pattern over the table-returning call.
- **`ComputedField`**: passthrough of the call expression as the field's return value (typically a parent-record accessor call).

`ArgCallEmitter.buildMethodBackedCallArgs` is retired at this seam. `ArgCallEmitter.buildArgExtraction` (the per-arm extraction-to-expression switch) survives, since `ArgConstruction` still dispatches through it. `ArgCallEmitter.buildCallArgs` (the condition-filter path that consumes `List<CallParam>` for `@condition`) is untouched.

**`@condition` is not in scope.** The `@condition` directive's binding lives outside `MethodBackedField` (it rides on `MethodRef.ConditionFilter`, a peer of the `NonCondition` arms this slice dissolves). Predicate-carrier territory; future slice.

**`@DependsOnClassifierCheck` annotations.** The migrated emitter methods carry classifier-check annotations today (`buildServiceFetcherCommon`, `buildServiceRowsMethod`). The annotation is in retirement under R237; the slice removes the annotations from migrated methods where the classifier checks they reference now live inside the walker's failure modes. Any non-migrated annotations stay put.

## LSP wire format conventions (fixed by this slice)

Every subsequent Stage 2/3 slice inherits the conventions below.

**Code namespace and projection.** Codes live in the `graphitron.<dotted-leaf>` namespace and are stable strings. The wire-format adapter projects per `AuthorError` arm; the code is derived from the arm's type, not stored as a stringly-typed field on the model.

`AuthorError.Structural` becomes a sealed sub-family. Each sub-arm carries the typed data its message needs; each maps to one LSP code:

```java
sealed interface AuthorError permits UnknownName, Structural, AccessorMismatch, /* ...existing arms... */ {}

sealed interface Structural extends AuthorError permits
    Structural.ClassNotLoadable,
    Structural.ReturnTypeMismatch,
    Structural.InstanceHolderMissingCtor,
    Structural.ParameterNamesMissing,
    Structural.ParameterUnbindable,
    Structural.InputBeanShape,
    Structural.BatchParamAtRoot,
    Structural.ArgMappingParseError,
    Structural.ArgMappingUnknownArg,
    Structural.ArgMappingPathRejected,
    Structural.Other                          // catch-all for sites this slice doesn't sub-arm
{ String message(); }
```

Arm-to-code mapping:

| `AuthorError` arm | LSP `code` |
|---|---|
| `UnknownName(AttemptKind.SERVICE_METHOD)` | `graphitron.method-call.unknown-method` |
| `Structural.ClassNotLoadable` | `graphitron.method-call.class-not-loadable` |
| `Structural.ReturnTypeMismatch` | `graphitron.method-call.return-type-mismatch` |
| `Structural.InstanceHolderMissingCtor` | `graphitron.method-call.instance-holder-missing-ctor` |
| `Structural.ParameterNamesMissing` | `graphitron.method-call.parameter-names-missing` |
| `Structural.ParameterUnbindable` | `graphitron.method-call.parameter-unbindable` |
| `Structural.InputBeanShape` | `graphitron.method-call.input-bean-shape` |
| `Structural.BatchParamAtRoot` | `graphitron.method-call.batch-param-at-root` |
| `Structural.ArgMappingParseError` | `graphitron.method-call.arg-mapping-parse-error` |
| `Structural.ArgMappingUnknownArg` | `graphitron.method-call.arg-mapping-unknown-arg` |
| `Structural.ArgMappingPathRejected` | `graphitron.method-call.arg-mapping-path-rejected` |
| `Structural.Other` | `graphitron.structural` (fallback for unmigrated sites) |

**Existing `Rejection.structural(reason)` callsites** outside the 10 method-backed paths (in other classifiers, validators, builders) project to `Structural.Other(reason)` until their own slices migrate them to typed sub-arms. The wire-format adapter handles `Other` with the fallback code; behaviour for existing callers is preserved.

**Code stability scope.** Codes are stable strings at the slice's scope (rename within R238 happens before merge; rename after merge is a breaking change to the wire). Stage 2/3 slices inherit the namespace convention and the sealed-sub-arm pattern but pick their own leaves; renaming an existing code across slices is the same kind of breaking change as renaming a Java API.

**Source attribution**: every diagnostic's `SourceLocation` is the field's own SDL location (`GraphQLFieldDefinition.getDefinition().getSourceLocation()`). For nested-input-path errors (`ArgMappingPathRejected`), the offending segment goes in `Diagnostic.relatedInformation` rather than retargeting the primary location; the convention "primary location is always the field-under-walk" stays stable across slices.

**`source: "graphitron"`**: constant for every diagnostic, mirrors the LSP standard field.

**Severity**: walker `AuthorError`s project to `Error`. `Information` / `Hint` / `Warning` arms don't enter through this slice (`BuildWarning` migration is a Stage 5 sync).

## Producer-side failure modes

The slice routes the following existing rejections through `WalkerResult.Err` as the listed typed arm:

| Source today | New `AuthorError` arm |
|---|---|
| Class not loadable (`Rejection.structural`, `ServiceCatalog`) | `Structural.ClassNotLoadable(className)` |
| Unknown service method (`Rejection.unknownServiceMethod`, `ServiceCatalog`) | `UnknownName(SERVICE_METHOD, attempt, candidates)` (unchanged shape) |
| Return-type mismatch (`ServiceCatalog`) | `Structural.ReturnTypeMismatch(expected, actual)` |
| Instance method without `(DSLContext)` ctor (`ServiceCatalog`) | `Structural.InstanceHolderMissingCtor(className)` |
| Missing `-parameters` compile flag (`ServiceCatalog`) | `Structural.ParameterNamesMissing(className, methodName)` |
| Parameter not matching arg/context/source (`ServiceCatalog`) | `Structural.ParameterUnbindable(paramName, available, suggestion)` |
| `List<Row>` / `List<Record>` at root (`ServiceCatalog`) | `Structural.BatchParamAtRoot(className, methodName)` |
| `argMapping` parse error (`ArgBindingMap.parseArgMapping`) | `Structural.ArgMappingParseError(rawMapping, parserDetail)` |
| `argMapping` unknown arg ref (`ArgBindingMap.of` → `UnknownArgRef`) | `Structural.ArgMappingUnknownArg(javaParam, refHead, availableArgs)` |
| `argMapping` path rejected (`ArgBindingMap.of` → `PathRejected`) | `Structural.ArgMappingPathRejected(javaParam, offendingSegment, reason)` |
| Input-bean shape rejections (`InputBeanResolver` family) | `Structural.InputBeanShape(beanClass, reason)` |

Each typed arm carries the data its message and its LSP `relatedInformation` need. The walker collects across stages: multiple `Err`s from one field surface together, not just the first.

## `UnclassifiedField` retirement for these ten permits

R222's wrapper-everywhere claim ("validity rides on the wrapper, not the carrier") lands here for the method-backed paths. After the slice, classification of any field whose directives place it under `MethodBackedField` proceeds through `MethodCallWalker` and one of two terminal outcomes:

- **`Ok(MethodCall)`**: the orchestrator constructs the appropriate permit record (one of the ten) with the carrier slot populated. The field enters the classified set.
- **`Err(authorErrors, diagnostics)`**: the orchestrator collects the `AuthorError`s into the build's diagnostic stream. The field does *not* enter the classified set; downstream generation is blocked when any field has produced an `Err`. The field is not constructed as `UnclassifiedField`.

`GraphitronSchemaValidator.validateUnclassifiedField` no longer needs to walk the ten permits' failure paths. The validator's surface narrows by exactly that much; cross-type invariant checks unaffected.

The wider `UnclassifiedField` retirement (R222 Stage 4) covers the rest of the field model: input-side classification, the column-leaf permits, the remaining directive paths. This slice doesn't retire `UnclassifiedField` entirely; it retires it for the method-backed paths only. The orchestrator-side channel (`walkerDiagnostics: List<Diagnostic>` on `ValidationReport`, per R222) accepts the new typed errors today and accumulates additional arms as further slices migrate.

## Tests

Three tiers:

- **Unit (`@UnitTier`)**: `MethodCallWalkerTest` and `MethodCallEmitterTest`. The walker tests parse a small SDL fragment, configure a test `ClassLoader` with fixture resolver classes, call `walk`, and assert on `Ok(MethodCall)` shape (per arm: `FromEnvArg`, `FromContextKey`, `FromDslContext`, `FromBatchKeys`, `FromSourceRow`) and on `Err.errors` (per `Structural.*` sub-arm; per `UnknownName(SERVICE_METHOD)`). One test per `ParamBinding` arm, one per `Structural` sub-arm. Emitter tests cover representative `MethodCall` inputs (static target, instance-with-dsl target, batch-keys path, computed-field passthrough) and assert on the `(varDecls, callExpression)` `Emission` shape.
- **Pipeline (`@PipelineTier`)**: extend `ServiceRootFetcherPipelineTest`, `GraphitronSchemaBuilderTest`, and the existing table-method pipeline tests with assertions on `walkerDiagnostics` instead of `UnclassifiedField` projection for method-call failures. The existing positive-witness tests (`serviceWithMismatchedReturnType_surfacesAsValidationError`, `serviceWithListOfRecordReturn_isAccepted`, `ARG_CONDITION_ARGMAPPING_DUAL_BOUND`, etc.) keep their author-facing wording but assert against the typed `Structural.*` arms and the new diagnostic stream. **Audit body-string assertions**: pipeline tests asserting fetcher method-body string content will fail under the new emission shape (var-decls per binding instead of inlined call args). Per `rewrite-design-principles.adoc`'s ban on code-string assertions, those assertions retire in this slice and get replaced by structural assertions on the `MethodCall` carrier.
- **Compilation/Execution (`@CompilationTier` / `@ExecutionTier`)**: `graphitron-sakila-example` provides the regression net. The migration is structurally invariant on the generated source's observable behaviour (the generated fetcher's semantics are unchanged); if `mvn install -Plocal-db` stays green end-to-end, the slice is on the rails.

## Vocabulary locked in by this slice

The names below freeze for downstream Stage 2/3 carriers. Future slices may pick different *internal* names, but the patterns codified here are stable:

- **Slot home is an interface, not the universal `GraphitronField` parent.** Subsequent carrier slots follow this pattern: find (or introduce) the narrowest existing interface that already names the property "fields that carry this concern," and put the slot there. The walker is universal across the interface's implementers; consumers of the slot dispatch through the interface.
- **The carrier IS the model.** No "raw" intermediate model coexists with the reduced carrier on the field record. `MethodCall` dissolves `MethodRef.NonCondition`; subsequent carriers similarly retire whatever intermediate models they replace, not coexist with them.
- **`MethodCall`** as the data record name. Subsequent carrier types use the singular noun (no `Spec` / `Carrier` / `Output` suffix).
- **`ParamBinding`** as the name for per-position binding arms; not "binding" alone (collides with too many existing meanings).
- **`AuthorError.Structural` is sealed with typed sub-arms**, not a stringly-typed reason. LSP codes derive from the sub-arm's type, not from a `code: String` field. Migrating a `Structural` callsite to a typed sub-arm is the pattern for adding LSP-projectable failure modes.
- **`Structural.Other(String reason)`** is the transitional catch-all for `Structural` callsites not yet sub-armed. Slices retire `Other` callsites by sub-arming them; the wire adapter's fallback code covers what's left.
- **`WalkerResult<C>` shape**: sealed `Ok(C carrier, List<Diagnostic> diagnostics)` / `Err(List<AuthorError> errors, List<Diagnostic> diagnostics)`. `Ok`'s compact constructor rejects Error-severity diagnostics; `Err`'s rejects empty errors.
- **Shared emitter is a static utility, not a default method on the data interface.** Emission concerns stay off the data interface; consumers compose the emitter's output into their own context.
- **No `Optional` slots, no `No<Family>` arms when the slot is directive-gated.** Slot presence is determined by interface membership; consumers reading the slot through the interface always get a populated value.
- **No `UnclassifiedField` fallback for fields the slice covers.** `Err` paths route through the orchestrator's diagnostic channel, not through a sentinel permit. Each subsequent slice retires `UnclassifiedField` for its own field set.

## Out of scope

- `@condition` (the `MethodRef.ConditionFilter` arm): different binding family, lives outside `MethodBackedField`. The slice retains `MethodRef.ConditionFilter` as a top-level type so `@condition` paths keep working; future predicate-carrier slice subsumes it.
- Retiring `GraphitronField.UnclassifiedField` globally: R222 Stage 4. The slice retires it for the ten method-backed permits only.
- Retiring the cross-product field permits: R222 Stage 5 sync.
- The `DataFetcherBuilder` dimensional slot: R222 Stage 3. This slice ships only the walker carrier and the shared emitter, not the dimensional slot that would compose multiple carriers.
- `ValidationReport` slot collapse: R222 Stage 4. This slice adds typed walker errors to `walkerDiagnostics` (per R222) but doesn't merge it with `errors` / `warnings`.
- Retiring other `Rejection.AuthorError.Structural` callsites outside the ten method-backed paths: they route through `Structural.Other(reason)` until their own slices migrate them to typed sub-arms.
