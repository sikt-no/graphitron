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

R222's Stage 1 foundation slice. R222 names the target shape (per-field carriers populated by producers reading graphql-java primitives directly, validity riding on a `WalkerResult<Ok|Err>` wrapper) but the pattern hasn't landed anywhere in tree yet; every Stage 2/3 slice that follows will inherit whatever conventions Stage 1 sets for the producer substrate, the slot's home (which interface or record carries it), LSP `Diagnostic` code namespace, and source attribution. `MethodCall` is the cleanest carrier to land those conventions on: the smallest consumer surface (~27 references, concentrated in `TypeFetcherGenerator`'s service-call arms), existing groundwork in the extracted `CallParam` record and `RowsMethodCall` factory, real failure modes (`AuthorError.UnknownName`, arity mismatches, unbound resolver-method params) to exercise the `Err` path, and an existing interface (`MethodBackedField`) that already names which permits share a method call. The slice's scope is one slot on `MethodBackedField`, one producer reducing `MethodRef` + SDL into the carrier, one consumer migration (the four sync `@service` permits' shared body in `TypeFetcherGenerator.buildServiceFetcherCommon`), one LSP wire arm projecting `AuthorError` to `graphitron.<code>` Diagnostic records, and one shared emitter consumed polymorphically through the interface. A new `ServiceField` marker rides on `MethodBackedField` to capture the service-call subset for consumers that need to dispatch on it.

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

The slot's home is the existing `MethodBackedField` interface, which already names the property "this field carries a method call by definition." Every implementer gets the slot uniformly; the carrier's existence is interface-required, not Optional-gated. The interface gains one accessor:

```java
// graphitron/src/main/java/no/sikt/graphitron/rewrite/model/MethodBackedField.java
public interface MethodBackedField {
    MethodRef method();          // existing; the raw bound reference
    MethodCall methodCall();     // NEW; reduced, emit-ready
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

**Record components.** All ten implementer records gain a `MethodCall methodCall` component alongside the existing `MethodRef method`. The auto-generated record accessor `methodCall()` satisfies the interface contract. Example for `QueryServiceRecordField`:

```java
record QueryServiceRecordField(
    String parentTypeName,
    String name,
    SourceLocation location,
    ReturnTypeRef returnType,
    MethodRef method,                       // existing
    MethodCall methodCall,                  // NEW
    Optional<ErrorChannel> errorChannel
) implements QueryField, ServiceField, WithErrorChannel
```

The `method: MethodRef` component stays in place for now; consumers other than the migrated service-fetcher path keep reading `method()` directly. The duplication is temporary and inexpensive; both components populate from one walker pass, and `method` retires when follow-up slices migrate the remaining consumers (or no later than R222 Stage 5 when the cross-product permits dissolve).

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
    public WalkerResult<MethodCall> walk(MethodRef method, GraphQLFieldDefinition field);
}
```

Substrate: the resolved `MethodRef` (output of directive resolution) plus the field's SDL definition (`GraphQLFieldDefinition`, source for arg names, types, locations). No graphitron-internal intermediate substrate model between the SDL and the carrier; this is the load-bearing claim of R222 for the walker abstraction.

**Reduction loop** (one pass per method-backed field):

1. Resolve `ServiceCallTarget` from `method.callShape()` (today's `Static` / `InstanceWithDslHolder`).
2. For each `MethodRef.Param` in order, dispatch on `ParamSource` and produce one `ParamBinding`:
   - `Arg` → `FromEnvArg(localName=javaParamName, javaType, argName=path.head(), construction=extraction)`.
   - `Context` → `FromContextKey(...)`.
   - `DslContext` → `FromDslContext("dsl")`. Shared local; if any binding emits `FromDslContext`, the emitter declares the `dsl` local once.
   - `Sourced` (DataLoader batch key) → `FromBatchKeys(localName="keys", container)`.
   - parent-row shapes → `FromSourceRow(...)`.
3. Build `ReturnTypeShape` from `method.returnType()` plus the field's declared return type.
4. Collect `Rejection`s from directive resolution that survived into the walker's input; project each `Rejection.AuthorError` arm into the walker's `Diagnostic` family at the field's `SourceLocation`; emit `Err(authorErrors, diagnostics)` when non-empty. Otherwise `Ok(MethodCall(...), [])`.

**Invocation point.** The walker runs at classification time, between `ServiceDirectiveResolver.Resolved.Success` returning a `MethodRef` and the constructor call building the permit record. Concrete sites in `FieldBuilder.java`:

- Root queries: lines 3130-3141 (the `case Resolved.TableBound / Result / Scalar` arms).
- Root mutations: lines 3254-3288.
- Child fields: lines 3812-3819 and 4827-4853 (`ServiceTableField` / `ServiceRecordField` constructors).
- Table-method permits: their constructor sites (the same pattern; the walker runs here even though the consumer doesn't migrate in this slice).

The walker is invoked uniformly for every `MethodBackedField` implementer's construction. If `Err`, the classifier falls back to `UnclassifiedField` as today (until R222 Stage 4 retires that arm globally).

**Unit-testability** follows from the substrate boundary: hand-construct a `MethodRef` + parse an SDL fragment for the field, call `walk`, assert on the sealed result.

## Consumer migration

One consumer migrates in this slice: `TypeFetcherGenerator.buildServiceFetcherCommon` (graphitron/.../rewrite/generators/TypeFetcherGenerator.java:1406), the shared body for the four sync service permits (`QueryServiceTableField`, `QueryServiceRecordField`, `MutationServiceTableField`, `MutationServiceRecordField`).

**Shared emitter.** A new utility class encapsulates the var-decls-plus-call-expression emission, parameterised on `MethodBackedField`:

```java
// graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/MethodCallEmitter.java
public final class MethodCallEmitter {
    public static Emission emit(MethodBackedField field);

    public record Emission(List<CodeBlock> varDecls, CodeBlock callExpression) {}
}
```

`emit` reads `field.methodCall()`, walks `bindings()` to produce the var-decl list, and composes the call expression from `target` + `methodName` + binding local-names. Each consumer wraps the result in its own context (assignment to a typed local for service paths; embedding in a `dsl.select(...).from(...)` chain for table-method paths; passing through for computed paths). The migrated `buildServiceFetcherCommon` calls `MethodCallEmitter.emit(field)` and composes the surrounding service-fetcher shape.

`ArgCallEmitter.buildMethodBackedCallArgs` is retired at this seam; `ArgCallEmitter.buildArgExtraction` (the per-arm extraction-to-expression switch) survives, since `ArgConstruction` still dispatches through it. `ArgCallEmitter.buildCallArgs` (the condition-filter path that already consumed `List<CallParam>`) is untouched.

**Out of scope for this slice's consumer migration:**

- The DataLoader rows methods (`buildServiceRowsMethod` at line 4416, used by `ChildField.ServiceTableField` / `ChildField.ServiceRecordField`). `FromBatchKeys` and `FromSourceRow` arms close the carrier's binding family; the rows-method-emitter migration is a follow-up slice that reuses the same `MethodCallEmitter`.
- `@tableMethod` permits (`QueryTableMethodTableField`, `ChildField.TableMethodField`, `ChildField.RecordTableMethodField`). The walker populates their `methodCall` slot; their consumers keep reading `method()` until follow-up slices migrate them.
- `@condition` (the `ConditionFilter` arm of `MethodRef`). Different emission shape entirely; lives outside `MethodBackedField` and outside this carrier. Predicate territory, future slice.

Deferrals are deliberate: the slice picks the smallest method-call consumer surface that proves the slot, the walker, and the shared emitter. Follow-up slices migrate the remaining consumers without reopening the carrier or the interface.

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

**Severity**: walker `AuthorError`s project to `Error`. `Information` / `Hint` / `Warning` arms don't enter through this slice (`BuildWarning` migration is a Stage 5 sync).

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

Today these surface via `GraphitronField.UnclassifiedField` and project through `GraphitronSchemaValidator.validateUnclassifiedField`. The slice short-circuits that for service-call paths: the validator stops walking `UnclassifiedField` for fields whose classification produced a walker `Err`; the orchestrator collects walker output's `Err.errors` and surfaces them at the same channel `ValidationReport` uses today (`walkerDiagnostics` slot added per R222).

## Tests

Three tiers:

- **Unit (`@UnitTier`)**: `MethodCallWalkerTest`. Parse a small SDL fragment, hand-construct a `MethodRef`, run `walk`, assert on `MethodCall` shape / `ParamBinding` arms / `Err.errors`. One test per `ParamBinding` arm, one per `Err`-producing rejection. Plus a `MethodCallEmitterTest` for the shared emitter, asserting the var-decl list and call expression on representative inputs (Static target, InstanceWithDsl target, batch-keys path).
- **Pipeline (`@PipelineTier`)**: extend `ServiceRootFetcherPipelineTest` and `GraphitronSchemaBuilderTest` with assertions on `walkerDiagnostics` instead of `UnclassifiedField` projection for method-call failures. The existing `serviceWithMismatchedReturnType_surfacesAsValidationError`, `ARG_CONDITION_ARGMAPPING_DUAL_BOUND`, and friends keep their author-facing wording but assert against the new diagnostic stream.
- **Compilation/Execution (`@CompilationTier` / `@ExecutionTier`)**: `graphitron-sakila-example` provides the regression net. The migration is structurally invariant on generated source; if `mvn install -Plocal-db` stays green end-to-end, the slice is on the rails.

## Vocabulary locked in by this slice

The names below freeze for downstream Stage 2/3 carriers. Future slices may pick different *internal* names, but the patterns codified here are stable:

- **Slot home is an interface, not the universal `GraphitronField` parent.** Subsequent carrier slots follow this pattern: find (or introduce) the narrowest existing interface that already names the property "fields that carry this concern," and put the slot there. The walker is universal across the interface's implementers; consumers of the slot dispatch through the interface.
- **`MethodCall`** as the data record name. Subsequent carrier types use the singular noun (no `Spec` / `Carrier` / `Output` suffix).
- **`ParamBinding`** as the name for per-position binding arms; not "binding" alone (collides with too many existing meanings).
- **`AuthorError.Structural` carries a `code` field**; the message is human-prose, the code is stable. Adding a new structural-failure site means adding a code constant.
- **`AttemptKind.SERVICE_ARG`** lands here; future arg-resolution failures across the codebase project to this kind.
- **`WalkerResult<C>` shape**: sealed `Ok(C carrier, List<Diagnostic> diagnostics)` / `Err(List<AuthorError> errors, List<Diagnostic> diagnostics)`. `Ok`'s compact constructor rejects Error-severity diagnostics; `Err`'s rejects empty errors.
- **Shared emitter is a static utility, not a default method on the data interface.** Emission concerns stay off the data interface; consumers compose the emitter's output into their own context.
- **No `Optional` slots, no `No<Family>` arms when the slot is directive-gated.** Slot presence is determined by interface membership; consumers reading the slot through the interface always get a populated value.

## Out of scope

- Migrating the DataLoader rows methods (`buildServiceRowsMethod`); Stage 2 follow-up.
- Migrating `@tableMethod` permits; Stage 2 follow-up.
- Migrating `@externalField` / `ComputedField` consumers; Stage 2 follow-up.
- Retiring `GraphitronField.UnclassifiedField`; Stage 4.
- Retiring the cross-product field permits; Stage 5 sync.
- The `DataFetcherBuilder` dimensional slot; Stage 3. This slice ships only the *walker carrier*, not the dimensional slot that composes it.
- `ValidationReport` slot collapse; Stage 4. This slice adds `walkerDiagnostics` as a third slot alongside `errors` / `warnings`, doesn't merge them.
- Removing the `MethodRef method` record component from any permit; lands incrementally as follow-up slices migrate the remaining `method()` callers.
