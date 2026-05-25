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

R222's Stage 1 foundation slice. R222 names the target shape (dimensional slots on a single field type, populated by producers reading graphql-java primitives directly, with validity riding on a `WalkerResult<Ok|Err>` wrapper) but the pattern hasn't landed anywhere in tree yet; every Stage 2/3 slice that follows will inherit whatever conventions Stage 1 sets for slot identity, producer substrate, `No<Family>` framing, LSP `Diagnostic` code namespace, and source attribution. `MethodCall` is the cleanest carrier to land those conventions on: the smallest consumer surface (~27 references, concentrated in `TypeFetcherGenerator`'s `@service` / `@externalField` / `@tableMethod` / `@condition` arms), existing groundwork in the extracted `CallParam` record and `RowsMethodCall` factory, real failure modes (`AuthorError.UnknownName`, arity mismatches, unbound resolver-method params) to exercise the `Err` path, and a two-layer composition that demonstrates R222's structure cleanly: the walker carrier holds the bound argument list, and the `DataFetcherBuilder.Service` dimensional slot composes it with reflection on the registration container. The slice's scope is one slot on `GraphitronField`, one producer reading `GraphQLFieldDefinition`, one consumer migration (the method-call dispatch arm in `TypeFetcherGenerator`), one LSP wire arm projecting `AuthorError` to `graphitron.<code>` Diagnostic records. The `MethodArguments` → `MethodCall` rename and the `MethodArgumentBinding` arm family land here too, since R222 keeps them inside the umbrella rather than as external dependencies.

## Target emitted code

The reducer backtracks from this shape. Every `@service`-backed field's lambda body, after the slice, reads as a straight walk over the carrier's binding list followed by the call line:

```java
// QueryServiceRecordField example, post-slice
DSLContext dsl = graphitronContext(env).getDslContext(env);                  // only when needsDsl
DomainTypeA paramA = constructParamA(env);                                   // one per ParamBinding arm
DomainTypeB paramB = constructParamB(env);
DomainResult result = new DomainService(dsl).method(paramA, paramB);
```

For the `@tableMethod` arm, the same shape with no service instantiation and a table-returning method; for DataLoader-backed `ChildField.Service*Field`, the rows-method body reads `keys` and `env` the same way. The slice's claim is that one walk over `MethodCall.Invocation.bindings()` produces the var-decls, and a second short composition produces the call line; today, `ArgCallEmitter.buildMethodBackedCallArgs` interleaves these two by inlining each param's extraction directly into the call-line argument list. Lifting the per-param expression to a named local is the structural shift the carrier enables.

The "construct `ParamX`" expression is a single sealed-arm dispatch (today's `CallSiteExtraction`, retained); whether it inlines as an expression or extracts as a `private static T constructParamX(DataFetchingEnvironment env)` helper is the emitter's choice per arm. For this slice, only `InputBean` extracts to a helper (preserving today's `InputBeanInstantiationEmitter` output); the other arms inline. The carrier shape doesn't force the choice.

## Carrier shape

`MethodCall` is the field-level slot; arms below it are the building blocks. Working names; slices downstream may rename.

```java
sealed interface MethodCall permits MethodCall.Invocation, MethodCall.NoMethodCall {

    record NoMethodCall() implements MethodCall {}

    record Invocation(
        ServiceCallTarget target,            // how to obtain the receiver expression
        String methodName,
        List<ParamBinding> bindings,         // ordered, one per Java method parameter
        ReturnTypeRef returnType,
        List<String> declaredExceptions
    ) implements MethodCall {}
}

sealed interface ServiceCallTarget
    permits StaticClass, InstanceWithDsl, StaticTableMethod {
    String fqClassName();
    // StaticClass:        emits `Foo.method(...)`
    // InstanceWithDsl:    emits `new Foo(dsl).method(...)`
    // StaticTableMethod:  emits `Foo.method(...)` returning a jOOQ Table (separate arm to encode the SQL-tail composition that follows it)
}

sealed interface ParamBinding {
    String localName();                      // var-decl identifier emitted at call site
    TypeName javaType();                     // var-decl type
}

record FromEnvArgument(
    String localName, TypeName javaType,
    String argName,                          // GraphQL argument name (head of the arg-binding path)
    ArgConstruction construction             // how to turn the env entry into the typed value
) implements ParamBinding {}

record FromContextKey(
    String localName, TypeName javaType, String contextKey
) implements ParamBinding {}

record FromDslContext(String localName) implements ParamBinding {}

record FromBatchKeys(
    String localName, TypeName javaType,
    KeyContainer container                   // LIST / SET / MAPPED_SET
) implements ParamBinding {}

record FromSourceRow(
    String localName, TypeName javaType, SourceRowShape shape
) implements ParamBinding {}
```

`ArgConstruction` is today's `CallSiteExtraction` carried unchanged into the slice: `Direct`, `EnumValueOf`, `ContextArg`, `JooqConvert`, `NestedInputField`, `NodeIdDecodeKeys`, `InputBean`. The slice does not redesign extraction; the carrier just lifts those arms one level up so the emitter walks bindings instead of method-params + extraction in lockstep.

`ReturnTypeRef` is a thin wrapper over a JavaPoet `TypeName` plus the jOOQ-shape hint (`ScalarReturnType`, `PojoResultType`, `Result<XRecord>`, `List<XRecord>`, `XRecord`); the existing return-type computation in `computeMutationServiceRecordReturnType` and friends lifts onto this. Bookkeeping; nothing structural.

`NoMethodCall` covers every field that isn't backed by a method invocation: column-projected leaves, connection wrappers, parent-keyed `@field(name:)` overrides where the value lives on a record accessor, etc. The producer returns `Ok(NoMethodCall)` for these; consumers that don't care about method-call semantics never look at the slot.

## Reducer / producer shape

```java
final class MethodCallWalker {
    WalkerResult<MethodCall> walk(GraphQLFieldDefinition field, ResolvedField resolved);
}
```

`ResolvedField` is the existing per-field artifact produced by directive resolution (`@service` / `@externalField` / `@tableMethod` / `@condition` resolvers have already located the `MethodRef` and bound the `argMapping`); the walker's job is the *reduction*, not directive parsing. Substrate: `field.getArguments()` (the SDL arguments in scope) plus `resolved.methodRef()` (the bound `MethodRef`). The substrate boundary is the load-bearing claim of R222: no consumer-internal substrate model intermediates between the SDL and the carrier.

Reduction loop (one pass per field):

1. If `resolved.methodRef()` is empty or the field isn't method-backed, return `Ok(NoMethodCall, [])`.
2. Resolve `ServiceCallTarget` from `MethodRef.callShape()` (today's `Static` / `InstanceWithDslHolder`) and the static-vs-table dichotomy on `MethodRef.StaticOnly` vs `MethodRef.Service`.
3. For each `MethodRef.Param` in order, dispatch on `ParamSource`:
   - `Arg` → `FromEnvArgument(localName=javaParamName, javaType, argName=path.head(), construction=extraction)`. Allocate `localName` uniquely; the Java parameter name is the default.
   - `Context` → `FromContextKey(...)`.
   - `DslContext` → `FromDslContext("dsl")`. (Shared local; if any param emits `FromDslContext`, the prelude declares it once.)
   - `Sourced` (DataLoader batch key) → `FromBatchKeys(localName="keys", container)`.
   - `Source` / parent-row shapes → `FromSourceRow(...)`.
4. Collect any `Rejection`s from directive resolution that survive into the walker's substrate; project each `Rejection.AuthorError` arm into the walker's `Diagnostic` family at the field's `SourceLocation`; emit `Err(authorErrors, diagnostics)` when non-empty. Otherwise `Ok(Invocation(...), [])`.

Unit-testability follows from the substrate boundary: parse an SDL fragment, hand-construct a `ResolvedField` for the method-binding, call `walk`, assert on the sealed result.

## Consumer migration

One consumer migrates in this slice: `TypeFetcherGenerator.buildServiceFetcherCommon` (graphitron/.../rewrite/generators/TypeFetcherGenerator.java:1406), shared body for the four sync service permits (`QueryServiceTableField`, `QueryServiceRecordField`, `MutationServiceTableField`, `MutationServiceRecordField`). The body switches from iterating `MethodRef.params()` + dispatching `ParamSource` + `CallSiteExtraction` inline to iterating `field.methodCall().asInvocation().bindings()` + emitting one var-decl per binding + composing the call line. The `serviceCallTarget` switch over `MethodRef.CallShape` becomes a switch over the carrier's `ServiceCallTarget` arm.

`ArgCallEmitter.buildMethodBackedCallArgs` is retired at this seam; `ArgCallEmitter.buildArgExtraction` (the per-arm extraction-to-expression switch) survives, since `ArgConstruction` still dispatches through it. `ArgCallEmitter.buildCallArgs` (the condition-filter path that already consumed `List<CallParam>`) is untouched in this slice.

Out of scope for this slice's consumer migration:
- The DataLoader rows methods (`buildServiceRowsMethod` at line 4416, used by `ChildField.ServiceTableField` / `ChildField.ServiceRecordField`). The `FromBatchKeys` binding arm lands in the carrier so the family is closed, but the rows-method-emitter migration follows in a Stage 2 slice.
- `@tableMethod` permits (`QueryTableMethodTableField`, `ChildField.TableMethodField`, `ChildField.RecordTableMethodField`). `StaticTableMethod` is in the `ServiceCallTarget` family so the family is closed, but the table-method body emission has SQL-tail composition that's not the foundation slice's pattern to land.
- `@condition` (the `ConditionFilter` arm of `MethodRef`). Different emission shape entirely; lives outside `MethodCall` (predicate-carrier territory, future slice).

These three deferrals are deliberate: the foundation slice picks the smallest method-call surface that closes the carrier's sealed family and demonstrates the pattern. Follow-up slices migrate the remaining consumers without reopening the carrier.

## LSP wire format conventions (fixed by this slice)

Every subsequent Stage 2/3 slice inherits the conventions below.

**Code namespace**: `graphitron.<dotted-leaf>`. Codes are stable strings; the wire-format adapter projects per `AuthorError` leaf type. For this slice:

| `AuthorError` arm | LSP `code` |
|---|---|
| `UnknownName(AttemptKind.SERVICE_METHOD)` | `graphitron.method-call.unknown-method` |
| `UnknownName(AttemptKind.SERVICE_ARG)` (new; see below) | `graphitron.method-call.unknown-arg` |
| `Structural` (return-type mismatch) | `graphitron.method-call.return-type-mismatch` |
| `Structural` (instance ctor missing) | `graphitron.method-call.instance-holder-missing-ctor` |
| `Structural` (`-parameters` flag missing) | `graphitron.method-call.parameter-names-missing` |
| `Structural` (param unbindable) | `graphitron.method-call.parameter-unbindable` |
| `Structural` (input-bean shape rejection) | `graphitron.method-call.input-bean-shape` |
| `Structural` (List<Row>/List<Record> at root) | `graphitron.method-call.batch-param-at-root` |

`Structural` gains a `code: String` field carrying the dotted leaf (the message stays human-prose). This is the slice's contract change to `Rejection.AuthorError`; downstream slices add their own codes the same way. `Rejection.AuthorError.UnknownName` already has `AttemptKind`; we add `AttemptKind.SERVICE_ARG` for unknown-argument-reference errors that today surface as `Structural` (from `ArgBindingMap.of`'s `UnknownArgRef` arm).

**Source attribution**: every diagnostic's `SourceLocation` is the field's own SDL location (`GraphQLFieldDefinition.getDefinition().getSourceLocation()`). For nested-input-path errors (`PathRejected` in `ArgBindingMap.of`), the slice carries the offending segment in `Diagnostic.relatedInformation` rather than retargeting the primary location; this keeps the convention "primary location is always the field-under-walk" stable across slices.

**`source: "graphitron"`**: constant for every diagnostic, mirrors the LSP standard field.

**Severity**: walker `AuthorError`s project to `Error`. `Information` / `Hint` / `Warning` arms don't enter through this slice (`BuildWarning` migration is Stage 5 sync).

## Producer-side failure modes

The slice routes the following existing rejections through `WalkerResult.Err`:

- Class not loadable (`Rejection.structural`, ServiceCatalog:370)
- Unknown service method (`Rejection.unknownServiceMethod`, ServiceCatalog:214); `UnknownName(SERVICE_METHOD)`
- Return-type mismatch (ServiceCatalog:223)
- Instance method without `(DSLContext)` ctor (ServiceCatalog:231)
- Missing `-parameters` compile flag (ServiceCatalog:267)
- Parameter not matching any arg/context/source (ServiceCatalog:341)
- `List<Row>` / `List<Record>` at root (ServiceCatalog:288)
- `argMapping` parse error (`ArgBindingMap.parseArgMapping`)
- `argMapping` unknown arg ref (`ArgBindingMap.of` → `UnknownArgRef`)
- `argMapping` path rejected (`ArgBindingMap.of` → `PathRejected`)
- Input-bean shape rejections (`InputBeanResolver` family)

Today these surface via `GraphitronField.UnclassifiedField` and project through `GraphitronSchemaValidator.validateUnclassifiedField`. The slice short-circuits that: the validator stops walking `UnclassifiedField` for method-backed fields; the orchestrator collects `MethodCall` walker output's `Err.errors` and surfaces them at the same channel `ValidationReport` uses today (`walkerDiagnostics` slot added per R222).

## Tests

Three tiers:

- **Unit (`@UnitTier`)**: `MethodCallWalkerTest`. Parse a small SDL fragment, hand-construct `ResolvedField` for the method binding, run `walk`, assert on `Invocation` arms / `ParamBinding` arms / `Err.errors`. One test per `ParamBinding` arm, one per `Err`-producing rejection.
- **Pipeline (`@PipelineTier`)**: extend `ServiceRootFetcherPipelineTest` and `GraphitronSchemaBuilderTest` with assertions on `walkerDiagnostics` instead of `UnclassifiedField` projection for method-call failures. The existing `serviceWithMismatchedReturnType_surfacesAsValidationError`, `ARG_CONDITION_ARGMAPPING_DUAL_BOUND`, and friends keep their author-facing wording but assert against the new diagnostic stream.
- **Compilation/Execution (`@CompilationTier` / `@ExecutionTier`)**: `graphitron-sakila-example` provides the regression net. The migration is structurally invariant on generated source; if `mvn install -Plocal-db` stays green end-to-end, the slice is on the rails.

## Vocabulary locked in by this slice

The names below freeze for downstream Stage 2/3 carriers. Future slices may pick different *internal* names, but the patterns codified here are stable:

- **`MethodCall`** (slot name, accessor `methodCall()` on `GraphitronField`). Subsequent carrier slots use the same camelCase singular pattern (`pagination()`, `ordering()`, `predicate()`, ...).
- **`No<Family>` arms**: concrete shape is `record NoMethodCall()` with no payload. `No<Family>` arms are records, not enum singletons; subsequent slices follow the same pattern.
- **`ParamBinding`** as a name for the per-position binding arm; not "binding" alone (collides with too many existing meanings).
- **`AuthorError.Structural` carries a `code` field**; the message is human-prose, the code is stable. Adding a new structural-failure site means adding a code constant.
- **`AttemptKind.SERVICE_ARG`** lands here; future arg-resolution failures across the codebase project to this kind.
- **`WalkerResult<C>` shape**: sealed `Ok(C carrier, List<Diagnostic> diagnostics)` / `Err(List<AuthorError> errors, List<Diagnostic> diagnostics)`. `Ok`'s compact constructor rejects Error-severity diagnostics; `Err`'s rejects empty errors.

## Out of scope

- Migrating the DataLoader rows methods (`buildServiceRowsMethod`); Stage 2 follow-up.
- Migrating `@tableMethod` permits; Stage 2 follow-up.
- Retiring `GraphitronField.UnclassifiedField`; Stage 4.
- Retiring the cross-product field permits; Stage 5 sync.
- The `DataFetcherBuilder` dimensional slot; Stage 3. This slice ships only the *walker carrier*, not the dimensional slot that composes it.
- `ValidationReport` slot collapse; Stage 4. This slice adds `walkerDiagnostics` as a third slot alongside `errors` / `warnings`, doesn't merge them.

## Open design questions for review

- **`ResolvedField` shape**: the walker's input is "the field plus its directive-resolved metadata." Today directive resolution writes into the permit-carrying field record; the walker would need a thinner intermediate. Possibilities: (a) a record extracted from the existing permit fields, (b) the walker reads directly from `GraphQLFieldDefinition.getDirectives()` and runs its own resolution. (a) is smaller; (b) is purer per R222's "producers read SDL primitives directly" claim. Recommend (a) for this slice; (b) is a refactor for later.
- **`FromDslContext` as a separate arm vs. an implicit prelude**: every `Invocation` with a non-empty `InstanceWithDsl` target or any `DslContext` param needs the `dsl` local. Either (a) the arm is in `bindings` and the emitter dedupes, or (b) `Invocation` has a `needsDslLocal: boolean` and the binding list excludes it. (a) is more uniform; (b) is closer to today's `CallShape.Static(boolean needsDslLocal)`. Recommend (a).
- **Helper-extraction policy**: the carrier doesn't force the emitter to extract per-param constructions into named static helpers. Should the foundation slice mandate extraction for all arms (uniformity), or preserve today's inline-except-InputBean default? Recommend the latter for this slice; revisit if a downstream slice wants uniformity.
