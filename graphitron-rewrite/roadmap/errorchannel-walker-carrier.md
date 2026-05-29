---
id: R244
title: "Error-channel slice 1: PayloadOrErrors transport, retire @error payload-class construction"
status: Spec
bucket: structural
depends-on: []
created: 2026-05-26
last-updated: 2026-05-29
---

# Error-channel slice 1: PayloadOrErrors transport, retire @error payload-class construction

First slice of a staged rewrite that eats the entire `@error`-channel machinery one transport at a time. The end state across all slices: every fetcher-emitting payload field hands graphql-java a typed `PayloadOrErrors<T>` source carrying either the resolved value (`Ok`) or the mapped errors (`Errors`), and the payload type's child fetchers switch on the arm. No developer payload class is constructed on the error path, no `localContext` side channel, no sentinel jOOQ record.

**This slice is scoped to retiring the domain-object *construction* path, for `@service` and `@tableMethod` fields only.** Today those fields' error path reflects on the developer's payload class and builds it (all-fields-ctor or bean-setter "construction shapes") with the matched errors slotted in; the success path already hands back the raw jOOQ record, so construction is an error-path-only behaviour. That construction machinery (`ErrorChannel.PayloadClass`, the `PayloadConstructionShape` family, the `payloadFactory*` / `declareEarlyPayload*` emitters, `ErrorRouter.dispatch`) is what slice 1 deletes, replaced by the `PayloadOrErrors` wrapper. R238 set the conventions for input-walking carriers (`MethodCall` on `MethodBackedField`); the walker half of this slice mirrors that on the output side, walking the SDL payload type rather than the field's arguments.

**Why the wrapper, and not localContext (verified).** An earlier draft proposed `DataFetcherResult.data(null).localContext(errors)`, with the payload's `errors` field reading `env.getLocalContext()`. A pure graphql-java 25.0 spike proved this silently drops errors: a null payload source makes graphql-java's `completeValueForObject` short-circuit *all* the payload's children, so the `errors` field is never fetched and `localContext` is never read ; the response is `{payload: null}` with the error swallowed, no top-level error either. The shipped DML transport already documents exactly this and works around it with a non-null sentinel record (see the `ErrorChannel.LocalContext` javadoc). `PayloadOrErrors` resolves it by construction: the source is always non-null, so graphql-java always descends; the data field projects `Ok.value` (null on `Errors`, so it renders null and its own children are not visited) and the `errors` field projects `Errors.errors`. A second spike confirmed both arms round-trip to the expected `{data, errors}` wire shape. Both spikes are reproduced as a pinned execute-tier test (see Tests).

The slice ships:

- **A generic runtime wrapper `PayloadOrErrors<T>`** ; sealed `Ok(T value) | Errors(List<Object> errors)`, emitted into the runtime-support package. This is the *request-time* GraphQL source object, deliberately distinct from the *classify-time* `ErrorChannel` carrier. (Name open; see "Naming".) It cannot be `Result` (collides with jOOQ `org.jooq.Result<Record>`, the existing DataFetcher return type) or `Resolved` (the builder-step result sealed type).
- **`ErrorChannel.PayloadClass` replaced by `ErrorChannel.Mapped(List<ErrorType> mappedErrorTypes, String mappingsConstantName)`** ; the classify-time descriptor of which `@error` types map and which `ErrorMappings` constant drives them. `ErrorChannel.LocalContext` is left untouched (DML still uses it), so `ErrorChannel` becomes sealed `Mapped | LocalContext`. `WithErrorChannel.errorChannel()` keeps its `Optional<ErrorChannel>` shape (absence = no channel = redact-only). No interface split, no `NoChannel` arm, no `Optional` removal in this slice ; those are deferred (see "Deferred to later slices").
- **A producer `ErrorChannelWalker`** reducing the SDL payload type to `ErrorChannel.Mapped` (or no-channel): detect the errors-shaped field, identify mapped `@error` types, run the channel-level rule checks (rule 7 multi-VALIDATION, rule 8 duplicate match-criteria), reflect on handler source classes for per-`@error`-type accessor coverage, derive the mappings-constant name. Subsumes `BuildContext.detectErrorsFieldShape`, the in-scope role of `FieldBuilder.resolveErrorChannel`, `checkChannelLevelHandlerRules`, `checkDuplicateMatchCriteria`, `checkErrorTypeSourceAccessors`.
- **Emitter rewrite for the in-scope fields.** The success path wraps the existing jOOQ-record return in `Ok(...)`; the error path returns `Errors(matchedErrors)` after the `(Mapping, cause)` match-walk; the unmapped fallback stays `ErrorRouter.redact(...)` (a real top-level GraphQL error, distinct from the typed channel). The validator Jakarta-violation pre-step and the async `.exceptionally(...)` tail emit the same `Errors(...)`. `Mapping.match(Throwable)` and `redact` survive; the payload-factory router `ErrorRouter.dispatch` retires.
- **Arm-switching child fetchers** for every immediate field of an in-scope (wrapper-typed) payload: the data fields unwrap `Ok.value` before their existing column/key read (null on `Errors`), the errors field reads `Errors.errors`. Pinned by a validator check (see "Payload-child arm-switch invariant") so an un-switched field is a build-time failure, not a silent runtime hole.
- **A new `ChildField.ErrorsField.Transport.WrapperArm`** arm; in-scope errors fields read `Errors.errors` off the wrapper source. `Transport.LocalContext` (DML) and `Transport.PayloadAccessor` (non-channel parents) are untouched. The `Transport` taxonomy gains an arm; nothing collapses in this slice.
- R201 (`honor-field-directive-in-payload-construction-shape`) retires as moot; R241 (`retire-error-payloadclass-transport`) is superseded.

**Two transports coexist after slice 1.** DML (`MutationDmlRecordField`, `MutationBulkDmlRecordField`, and the four `DmlTableField` permits) stays on the sentinel/`localContext` transport, selected per-field by the catch-arm emitter. This is a sound intermediate: the wrapper (a GraphQL source object) and the sentinel/`localContext` shape share no runtime type, and `MutationField`'s interface-level `WithErrorChannel` membership is undisturbed because the carrier stays `Optional<ErrorChannel>` with `LocalContext` intact. Migrating DML onto `PayloadOrErrors` and retiring the sentinel machinery is a later slice (see "Deferred to later slices").

## The runtime wrapper (`PayloadOrErrors`)

```java
// graphitron/.../runtime support package (alongside ErrorRouter / Mapping)
public sealed interface PayloadOrErrors<T> permits PayloadOrErrors.Ok, PayloadOrErrors.Errors {
    record Ok<T>(T value) implements PayloadOrErrors<T> {}
    record Errors<T>(List<Object> errors) implements PayloadOrErrors<T> {}
}
```

`Ok.value` holds exactly what the success path produces today (a typed jOOQ `XRecord`, `Result<Record>`, or `List<XRecord>`). `Errors.errors` holds the matched throwables, the same objects the retired `payloadFactory.apply(List.of(t))` received; per-`@error`-type field DataFetchers read off each throwable exactly as today. The two arms map one-to-one onto the two SDL child-field families: data fields read `Ok.value`, the `errors` field reads `Errors.errors`. Its javadoc is the pinned home of the graphql-java completion invariant (why a non-null source is mandatory, why every immediate child must arm-switch), backed by the execute-tier test in Tests.

**Naming (open; pick at In Progress).** `PayloadOrErrors` is the working name. It cannot be `Result` (jOOQ `org.jooq.Result<Record>` is the existing DataFetcher return type) or `Resolved` (the builder-step result sealed type), and the `Error` arm is singular-avoided because `Rejection.AuthorError` and `GraphitronType.ErrorType` are live model types in this area. Candidates: `PayloadOrErrors.{Ok | Errors}` (self-documenting at the fetcher, arms line up with the two SDL field families); `FetcherOutcome.{Ok | Failed}`; `PayloadResult.{Data | Errors}`. The recommendation is `PayloadOrErrors.{Ok | Errors}`. This is a deliberately bounded choice for the implementer, not a design fork.

## Target emitted code

The reducer backtracks from this shape. Every `@service` / `@tableMethod` fetcher wraps its success return in `Ok` and its mapped error in `Errors`; the unmapped fallback is unchanged:

```java
// QueryServiceRecordField example, post-slice
public static DataFetcherResult<PayloadOrErrors<SakRecord>> sak(DataFetchingEnvironment env) {
    try {
        SakRecord result = SakService.run(...);
        return DataFetcherResult.<PayloadOrErrors<SakRecord>>newResult()
            .data(new Ok<>(result)).build();
    } catch (Throwable e) {
        for (Mapping m : ErrorMappings.SAK_PAYLOAD) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (m.match(t)) {
                    return DataFetcherResult.<PayloadOrErrors<SakRecord>>newResult()
                        .data(new Errors<>(List.of(t))).build();
                }
            }
        }
        return ErrorRouter.redact(e, env);   // unmapped -> top-level GraphQL error
    }
}
```

The payload type `SakPayload`'s child fetchers receive a non-null `PayloadOrErrors<SakRecord>` as `env.getSource()` and switch on the arm:

```java
// SakPayload.<dataField> fetcher (was: PropertyDataFetcher.fetching("...") / @record accessor)
env -> env.getSource() instanceof Ok<SakRecord> ok ? <existing read off ok.value()> : null;

// SakPayload.errors fetcher  (Transport.WrapperArm)
env -> env.getSource() instanceof Errors<?> errs ? errs.errors() : List.of();
```

On `Errors`, the data field returns null, so graphql-java renders it null and does not descend into its children; the `errors` field resolves because the *payload* source is non-null. On `Ok`, the data field projects `ok.value()` (the jOOQ record, typed from the field's resolved `ReturnTypeRef` so no blind cast reappears ; see "Payload-child arm-switch invariant") and the errors field returns the empty list.

Validator pre-step (Jakarta-violation early return) returns the same `Errors` arm:

```java
// inside buildServiceFetcherCommon, pre-step
if (!__violations.isEmpty()) {
    return DataFetcherResult.<PayloadOrErrors<SakRecord>>newResult()
        .data(new Errors<>(__violations)).build();
}
```

No `__earlyPayload` local, no payload-class construction. The async tail (`.exceptionally(...)` on `CompletableFuture`-shaped fetchers) lifts the same `Errors(...)` into the lambda over the throwable, and the success `.thenApply(...)` wraps in `Ok(...)`; otherwise identical.

## Classify-time carrier (`ErrorChannel`)

The carrier stays on the existing `WithErrorChannel` interface, accessor unchanged:

```java
// graphitron/src/main/java/no/sikt/graphitron/rewrite/model/WithErrorChannel.java
public interface WithErrorChannel {
    Optional<ErrorChannel> errorChannel();   // unchanged in this slice
}
```

`ErrorChannel` is the *classify-time* descriptor ("what to emit"), not the runtime `PayloadOrErrors` source ("what runs"); the two are kept as separate types deliberately. The walker produces `ErrorChannel.Mapped` for in-scope fields; DML continues to produce `ErrorChannel.LocalContext`; absence is `Optional.empty()`.

**Why `Optional` and the shared carrier stay this slice.** A prior draft narrowed the carrier to a non-Optional `Mapped | NoChannel` and split DML onto a sibling `WithDmlErrorTransport` interface (because `MutationField extends WithErrorChannel` at the interface level, `MutationField.java:17`, so all mutation permits inherit the slot). That works, but it is churn this slice does not need: leaving `Optional<ErrorChannel>` with `LocalContext` intact means `MutationField`'s membership is undisturbed, the DML permits keep their exact behaviour, and slice 1 touches only the `PayloadClass`-using fields. The `Optional` → non-Optional `NoChannel` collapse and the per-permit interface split are bundled with the DML migration (the slice that actually retires `LocalContext`), where they are a single coherent change rather than scaffolding erected and torn down inside one slice. R222's "no Optional slots" is honoured at the end of the staged rewrite, not mid-stream.

## Carrier shape

```java
// graphitron/src/main/java/no/sikt/graphitron/rewrite/model/ErrorChannel.java
public sealed interface ErrorChannel permits ErrorChannel.Mapped, ErrorChannel.LocalContext {

    // NEW ; replaces the retired PayloadClass arm. Drives the PayloadOrErrors emit.
    record Mapped(
        List<GraphitronType.ErrorType> mappedErrorTypes,
        String mappingsConstantName
    ) implements ErrorChannel {
        public Mapped {
            mappedErrorTypes = List.copyOf(mappedErrorTypes);
            if (mappedErrorTypes.isEmpty()) {
                throw new IllegalArgumentException(
                    "ErrorChannel.Mapped: mappedErrorTypes must be non-empty");
            }
        }
    }

    // UNCHANGED ; DML sentinel transport, retired by a later slice.
    record LocalContext(
        List<GraphitronType.ErrorType> mappedErrorTypes,
        String mappingsConstantName
    ) implements ErrorChannel { /* compact ctor as today */ }
}
```

`Mapped` and `LocalContext` carry identical fields by coincidence ; they are distinct types because they drive different emit (`Mapped` → `PayloadOrErrors`, `LocalContext` → sentinel + `dispatchToLocalContext`). The slice deletes `ErrorChannel.PayloadClass` entirely (with its `payloadClass`, `errorsSlot`, `defaultedSlots` components). The shared accessors that today live on the sealed `ErrorChannel` root (`mappedErrorTypes`, `mappingsConstantName`) can stay on the root (both arms have them) or move to per-arm reads; either is fine and is an implementer's call, since the root no longer has a third arm with a different shape.

## Producer (`ErrorChannelWalker`)

```java
// graphitron/src/main/java/no/sikt/graphitron/rewrite/walker/ErrorChannelWalker.java
public final class ErrorChannelWalker {
    // Optional.empty() == payload has no errors-shaped field (redact-only catch arm).
    public WalkerResult<Optional<ErrorChannel.Mapped>> walk(
        GraphQLObjectType payloadType,
        ClassLoader codegenLoader,
        MappingsConstantNameDedup dedup
    );
}
```

**Substrate is the SDL output payload type directly**, paired with the codegen classloader for handler-class reflection and the build-scoped name-dedup helper. This is the output-walking analogue of R238's `ServiceMethodCallWalker` (`graphitron/src/main/java/no/sikt/graphitron/rewrite/walker/ServiceMethodCallWalker.java`): same producer-as-thin-layer-over-graphql-java pattern, different SDL surface.

R238's shipped walker took a pragmatic substrate concession ; it translates from an upstream resolved `MethodRef.Service` rather than reflecting directly on SDL+classloader, because today's `ServiceCatalog.reflectServiceMethod` is 1258 LOC of battle-tested reflection that a translator-walker avoids duplicating. This walker does **not** take that concession: the `@error`-channel walk has no comparably large intermediate to translate from (the in-scope part of `FieldBuilder.resolveErrorChannel` is ~200 LOC inlined into the classifier), so the direct-substrate shape is achievable in scope. The walker absorbs the channel-rule and accessor-coverage checks (`checkChannelLevelHandlerRules`, `checkDuplicateMatchCriteria`, `checkErrorTypeSourceAccessors`) under `walker/internal/`, and takes over the in-scope `@error`-type classification role of `resolveErrorChannel`. It *consumes* `BuildContext.detectErrorsFieldShape` (a shared helper that stays, see "What retires") rather than absorbing it.

**Walker stages** (one pass per `WithErrorChannel` field's payload type):

1. **Find the errors-shaped field on the payload.** Walk `payloadType.getFieldDefinitions()` in source order; the first field whose shape matches the "polymorphic list/union/interface of `@error` types" predicate is the errors carrier. Subsumes `BuildContext.detectErrorsFieldShape` and the `liftToErrorsField` lift rules. Absence: emit `Ok(Optional.empty())`; the field carries no channel and its catch arm is redact-only.
2. **Identify mapped `@error` types.** Extract the `@error` types from the field's polymorphic shape (list element, union members, interface implementations). Non-empty by structural rule; one-element for `[SomeError]`, multi-element for unions / interfaces.
3. **Run channel-level rules.** Rule 7 (no two VALIDATION handlers in one channel), rule 8 (no duplicate match-criteria across the flattened handler list). Failure: typed `ErrorChannelWalkerError.ChannelRuleViolation(payloadTypeName, errorsFieldName, ruleNumber, detail)` arm.
4. **Reflect on handler source-classes for accessor coverage.** Per (channel, `@error` type, handler), walk the `@error` type's declared SDL fields and verify the handler's source class exposes a `PropertyDataFetcher`-visible accessor. `path` and `message` are exempt (populated by per-`@error`-type synthesised DataFetchers). Subsumes `checkErrorTypeSourceAccessors`. Failure: typed `ErrorChannelWalkerError.HandlerSourceAccessorMissing(...)` arm.
5. **Resolve the mappings-constant name.** Use `MappingsConstantNameDedup`: derive from `SCREAMING_SNAKE(payloadSdlName)`; on collision, append the 8-hex SHA-256 suffix per the existing dedup rules. The name is build-scoped, so the walker takes the dedup helper as a constructor arg.
6. **Emit result.** `Ok(Optional.of(Mapped(types, constName)), [])` on success; `Ok(Optional.empty(), [])` when no errors-shaped field found; `Err(authorErrors, diagnostics)` on any structural failure. `Err` collects across stages; the walker doesn't short-circuit at the first failure.

**Invocation point.** The walker is invoked from `FieldBuilder` at each constructor site for a `WithErrorChannel` implementer in scope. Today's sites pass through `resolveErrorChannel` (which returns a stringly-typed `ErrorChannelResult`); under the slice, those sites collapse into a single walker call. R238's "no fallback to `UnclassifiedField`" applies: `Err` paths surface through `WalkerResult.Err.errors` and the orchestrator's diagnostic stream; the field is excluded from the classified set. The reflection-shape rejections never construct `UnclassifiedField` for the channel-rejection paths.

**Unit-testability** mirrors R238: parse an SDL fragment, configure a small test `ClassLoader` with fixture handler / `@error`-source classes, call `walk`, assert on the sealed result.

## Consumer migration

All `WithErrorChannel` implementers in scope read `field.errorChannel()` and pattern-match the sealed arm:

| `WithErrorChannel` implementer | Consumer entry point (`TypeFetcherGenerator`) |
|---|---|
| `QueryField.QueryServiceTableField` | `buildServiceFetcherCommon` |
| `QueryField.QueryServiceRecordField` | `buildServiceFetcherCommon` |
| `QueryField.QueryTableMethodTableField` | `buildQueryTableMethodFetcher` |
| `MutationField.MutationServiceTableField` | `buildServiceFetcherCommon` |
| `MutationField.MutationServiceRecordField` | `buildServiceFetcherCommon` |
| `ChildField.ServiceTableField` | `buildServiceRowsMethod` + `buildServiceDataFetcher` |
| `ChildField.ServiceRecordField` | `buildServiceRowsMethod` + `buildServiceDataFetcher` |
| `ChildField.TableMethodField` | `buildChildTableMethodFetcher` |
| `ChildField.RecordTableMethodField` | `buildRecordBasedDataFetcher` |

**Shared emitter.** A new utility encapsulates the catch-arm body emission, dispatching on the optional carrier so it serves both transports through one seam:

```java
// graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/ChannelCatchArmEmitter.java
public final class ChannelCatchArmEmitter {
    public static CodeBlock emit(Optional<ErrorChannel> channel, TypeName valueType, String outputPackage);
}
```

- `Optional.empty()`: emit `return ErrorRouter.redact(e, env);` (redact-only catch arm).
- `ErrorChannel.Mapped`: emit the inline mapping-walk loop + `return ...data(new Errors<>(List.of(t)))...` match-return + `redact` fallback shown in the target-code section.
- `ErrorChannel.LocalContext`: emit today's `return ErrorRouter.dispatchToLocalContext(e, ErrorMappings.CONST, env, sentinel);` (the DML sentinel arm) ; unchanged behaviour, routed through the shared seam so DML and in-scope fields share one emitter rather than two parallel `catchArm` overloads. This arm is reachable only for DML fields; the classifier guarantees in-scope fields never carry `LocalContext`.

The success Ok-wrap is emitted at the existing `returnSyncSuccess` seam (`new Ok<>(result)` instead of the bare `result`). `asyncWrapTail` consumes the same emitter through a small wrapping context that lifts the result into the `.exceptionally(...)` lambda and wraps the `.thenApply(...)` success value in `Ok`. The Jakarta-violation pre-step ; the inline `if (!__violations.isEmpty())` block emitted inside `buildServiceFetcherCommon` (`TypeFetcherGenerator.java:1492`), which today calls `declareEarlyPayloadFromErrors(channel, "__violations")` ; switches to a sibling helper `ChannelEarlyReturnEmitter.emit(channel, valueType, violationsLocal)` emitting the `new Errors<>(violations)` early return.

`TypeFetcherGenerator.payloadFactoryLambda(+Ctor/+Setters)`, `declareEarlyPayloadFromErrors(+Ctor/+Setters)`, `dispatchCatchArm`, and the `catchArm` ctor-arm overload retire at this seam (the `Mapped` and `LocalContext` arms above subsume their job).

## Cutover sequencing

R238's additive-then-destructive landing is the precedent for the scaffolding and the final delete. One difference matters here: an input carrier could live in a parallel slot while consumers migrated one at a time, because the legacy and new slots were independent. The wrapper is *not* independent ; the moment a payload field's fetcher returns `PayloadOrErrors` instead of a bare record, every one of that payload type's child fetchers must arm-switch in the same commit, because they all read the one shared `env.getSource()`. So the in-scope flip is atomic **per payload type** (the root/child fetchers of one payload move together); it is not atomic across the whole codebase (different payloads can flip in different commits if that helps review).

Commit-level sequencing (subject to revision at In Progress time; the load-bearing claim is the sequence *shape*):

1. **Add the unwired pieces.** `PayloadOrErrors<T>` runtime type, `ErrorChannel.Mapped` (added under the sealed root, which temporarily permits `Mapped | PayloadClass | LocalContext`), `ErrorChannelWalkerError` sibling sub-seal, `ChildField.ErrorsField.Transport.WrapperArm`, and the payload-child arm-switch validator pin (see below). Nothing produces or consumes `Mapped` yet; `PayloadClass` is intact and remains what in-scope fields classify to.
2. **Add the walker and emitters.** `ErrorChannelWalker`, `ChannelCatchArmEmitter`, `ChannelEarlyReturnEmitter`, with unit-tier tests. Still unwired.
3. **Flip the in-scope fields to the wrapper.** `FieldBuilder` classifies in-scope payload fields to `ErrorChannel.Mapped` via the walker (replacing `resolveErrorChannel`'s `PayloadClass` production for those fields). In the same commit: `returnSyncSuccess` wraps in `Ok`; `ChannelCatchArmEmitter` / `ChannelEarlyReturnEmitter` / `asyncWrapTail` emit the `Errors` arm; every in-scope payload's data-field fetchers arm-switch on `Ok`; in-scope errors fields move to `Transport.WrapperArm`; `MappingsConstantNameDedup` and `CatalogBuilder.errorChannelName` read `Mapped`. This is the atomic-per-payload flip. DML still classifies to `LocalContext` and emits the sentinel arm through the same `ChannelCatchArmEmitter`, unchanged.
4. **Delete the construction machinery.** `ErrorChannel.PayloadClass` arm (sealed root returns to `Mapped | LocalContext`); `ErrorsSlot`, `DefaultedSlot`, `NonBoundSetter`, `PayloadConstructionShape` family; `FieldBuilder.resolvePayloadConstructionShape` + `tryMutableBean` + `buildErrorChannelCtorArm` + `buildErrorChannelBeanArm` + `collectDefaultedSlots` + `collectNonBoundSetters` + `defaultLiteralFor`; `TypeFetcherGenerator`'s `payloadFactoryLambda*` + `declareEarlyPayload*` + `dispatchCatchArm`; `ErrorRouter.dispatch` (the payload-factory router). `FieldBuilder.resolveErrorChannel` keeps its DML role (`buildDmlField` still calls it; it returns no-channel for the non-wrapper DML returns and `LocalContext` for record-payload DML), so it is trimmed to that role rather than deleted.

The dual-shape window is steps 1-2 (additive, unwired) into step 3 (flip); step 4 closes it. `Mapping.match`, `ErrorRouter.redact`, `ErrorRouter.dispatchToLocalContext`, the sentinel emitters, `LOCAL_CONTEXT_GUARDED_DATA_CHANNEL_VARIANTS`, and `validateLocalContextErrorsFieldGuards` all survive slice 1 untouched ; they belong to the DML transport, retired by a later slice.

## Payload-child arm-switch invariant

Under the wrapper, every immediate child field of an in-scope payload type receives a non-null `PayloadOrErrors` as `env.getSource()`. So each such fetcher must unwrap `Ok` before its existing read and return null on `Errors`. The data-channel `ChildField` variants that can sibling an `ErrorsField` under an `@service` / `@tableMethod` payload, all of which already get an explicit graphitron-emitted fetcher (`FetcherRegistrationsEmitter` registers every classified field; none falls through to graphql-java's default `PropertyDataFetcher`), and the read each wraps:

- `PropertyField`, `RecordField`: today `PropertyDataFetcher.fetching(col)` / `@record`-accessor read off the source ; now `src instanceof Ok ok ? <that read on ok.value()> : null`.
- `ServiceTableField`, `ServiceRecordField`, `TableMethodField`, `RecordTableMethodField`: child `@service` / `@tableMethod` fetchers that read parent keys off the source ; unwrap `Ok` first so the method is never invoked on an `Errors` source.
- `ConstructorField`, `NestingField`, `ComputedField`: source passthrough / accessor call ; unwrap `Ok` first.

**The unwrapped value keeps its narrow type.** Per "Classifier guarantees shape emitter assumptions", the `Ok.value()` local is typed from the field's already-resolved `ReturnTypeRef` (e.g. the specific jOOQ `XRecord`), so no blind `Object` cast is reintroduced ; the success path threads a typed record today and the arm-switch must not erase it.

**This invariant is pinned, not audited.** An un-switched payload child is a silent runtime hole: graphql-java's default fetcher would read a property off the `PayloadOrErrors` object itself. So slice 1 declares the set of data-channel `ChildField` leaves admissible as an immediate child of a wrapper-typed payload and has `GraphitronSchemaValidator` reject any wrapper-typed payload whose immediate child resolves to a leaf outside that set, by the same mechanism the dispatch partition uses (`TypeFetcherGenerator`'s `IMPLEMENTED_LEAVES` / `PROJECTED_LEAVES` / ... partition, enforced exhaustive and disjoint by `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus`). This mirrors the existing DML `LOCAL_CONTEXT_GUARDED_DATA_CHANNEL_VARIANTS` + `validateLocalContextErrorsFieldGuards` pair, which stays in place for DML. A new leaf that can appear under a wrapper payload without an arm-switching fetcher then fails the coverage test at build time, not a production request.

## AuthorError sub-arms and LSP codes

Following R238's actual precedent: each walker family gets its **own sibling sub-seal of `AuthorError`**, not a sub-arm under `Structural`. R238's `ServiceMethodCallError` (in `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/ServiceMethodCallError.java`) sets the pattern; R244 mirrors it with `ErrorChannelWalkerError`. Quote from R238's spec on the choice: "Subsequent walker slices each add their own sibling sub-seal rather than piling typed arms under a single flat `Structural` — keeps `AuthorError` permits one-row-per-walker as the dimensional pivot scales, and lets each walker's arm-to-code mapping live next to its own family declaration."

```java
// graphitron/src/main/java/no/sikt/graphitron/rewrite/model/ErrorChannelWalkerError.java
public sealed interface ErrorChannelWalkerError extends Rejection.AuthorError permits
    ErrorChannelWalkerError.ChannelRuleViolation,
    ErrorChannelWalkerError.HandlerSourceAccessorMissing
{
    /** LSP wire code under the {@code graphitron.error-channel.} namespace. */
    String lspCode();

    @Override default Rejection prefixedWith(String prefix) { return this; }

    record ChannelRuleViolation(
        String payloadTypeName,
        String errorsFieldName,
        int ruleNumber,            // 7 or 8 today; future rules slot in
        String detail
    ) implements ErrorChannelWalkerError {
        @Override public String message() { /* prose form, includes detail */ }
        @Override public String lspCode() {
            return switch (ruleNumber) {
                case 7 -> "graphitron.error-channel.multi-validation";
                case 8 -> "graphitron.error-channel.duplicate-match-criteria";
                default -> "graphitron.error-channel.channel-rule-violation";
            };
        }
    }

    record HandlerSourceAccessorMissing(
        String payloadTypeName,
        String errorTypeName,
        String handlerClassName,
        String missingFieldName,
        List<String> available
    ) implements ErrorChannelWalkerError {
        @Override public String message() { /* prose form, includes available list */ }
        @Override public String lspCode() {
            return "graphitron.error-channel.handler-accessor-missing";
        }
    }
}
```

Arm-to-code mapping (stable strings written next to the arm declaration per R238's wire convention, not derived from the Java identifier):

| `ErrorChannelWalkerError` arm | LSP `code` |
|---|---|
| `ChannelRuleViolation(rule=7, ...)` | `graphitron.error-channel.multi-validation` |
| `ChannelRuleViolation(rule=8, ...)` | `graphitron.error-channel.duplicate-match-criteria` |
| `HandlerSourceAccessorMissing` | `graphitron.error-channel.handler-accessor-missing` |

`source: "graphitron"`, severity `Error`, primary `SourceLocation` is the payload type's SDL location (or the errors field's location when the rule applies at the channel level). Offending handler details (per-`@error`-type, per-handler) go in `Diagnostic.relatedInformation`. The `AuthorError` parent grows one new permit (`ErrorChannelWalkerError`), keeping its arms one-row-per-walker.

`Rejection.AuthorError.Structural(String reason)` is untouched: existing non-R244 callsites continue producing it. This slice introduces no new `Structural` callsites and no `Other` catch-all under the new sub-seal ; every in-scope channel-rejection path maps to a typed arm.

## What retires (and what explicitly does not)

Model:

- `ErrorChannel.PayloadClass` arm (whole record); the sealed root returns to `Mapped | LocalContext`.
- `ErrorsSlot`, `DefaultedSlot`, `NonBoundSetter` (records + their files).
- `PayloadConstructionShape` sealed family + `AllFieldsCtor`, `MutableBean`, `SetterBinding`.

*Does not retire this slice:* `ErrorChannel.LocalContext` (DML); `WithErrorChannel.errorChannel()` keeps `Optional<ErrorChannel>`; `MutationField extends WithErrorChannel` is undisturbed; `ChildField.ErrorsField.Transport.PayloadAccessor` and `Transport.LocalContext` both stay (the slice *adds* `Transport.WrapperArm`).

Classifier:

- `FieldBuilder.resolvePayloadConstructionShape` + `tryMutableBean` + helpers (`formatCtorSignatures`, `javaBeanSetterName`).
- `FieldBuilder.buildErrorChannelCtorArm` + `buildErrorChannelBeanArm` + `collectDefaultedSlots` + `collectNonBoundSetters` + `defaultLiteralFor` (the `PayloadClass`-arm producers).
- `FieldBuilder.checkChannelLevelHandlerRules`, `checkDuplicateMatchCriteria`, `checkErrorTypeSourceAccessors` (absorbed into the walker).

*Does not retire:* `FieldBuilder.resolveErrorChannel` is trimmed to its DML role (still called by `buildDmlField`; produces no-channel for non-wrapper DML returns), not deleted. `BuildContext.detectErrorsFieldShape` stays a shared helper (called from `GraphitronSchemaBuilder`, `MutationInputResolver`, and the DML path); the walker consumes it rather than absorbing it.

Emitter:

- `TypeFetcherGenerator.payloadFactoryLambda`, `payloadFactoryLambdaCtor`, `payloadFactoryLambdaSetters`.
- `TypeFetcherGenerator.declareEarlyPayloadFromErrors`, `declareEarlyPayloadCtor`, `declareEarlyPayloadSetters`.
- `TypeFetcherGenerator.dispatchCatchArm` and the `catchArm` ctor-arm overload (the seam moves to `ChannelCatchArmEmitter.emit`).
- `ErrorRouter.dispatch` (the per-fetcher payload-factory router).

*Does not retire:* `Mapping.match(Throwable)`, `ErrorRouter.redact(Throwable, env)`, `ErrorRouter.dispatchToLocalContext` and the sentinel emitters (`singleRecordSentinelFor`, `bulkRecordSentinelFor`) all survive ; they back the DML transport.

Audit annotations:

- Any `@LoadBearingClassifierCheck` / `@DependsOnClassifierCheck` pairs keyed to payload-construction, if present, retire with the checks they guard.

Tests:

- `PayloadConstructionShapeTest` (7 cases) deletes.
- `FetcherPipelineTest`'s R154 cases (`serviceMutation_setterShapePayload_emitsSetterFactory`, `serviceMutation_allFieldsCtorPayload_emitsCtorFactory_unchanged`, `serviceMutation_bothShapesPresent_prefersCtorFactory`): rewrite around the wrapper emission or delete.
- `ErrorRouterClassGeneratorTest`'s tests for the retired `dispatch` method delete; `redact`, `Mapping.match`, and `dispatchToLocalContext` tests survive.
- `ErrorChannelClassificationTest`'s positive cases rewrite to assert on `WalkerResult.Ok(Optional.of(ErrorChannel.Mapped))` (and `Optional.empty()` for no-channel); rejection cases assert on the typed `ErrorChannelWalkerError.*` sub-arms via `WalkerResult.Err`.
- New `ErrorChannelWalkerTest` and `ChannelCatchArmEmitterTest` (unit tier) mirror R238's `ServiceMethodCallWalkerTest` / `ServiceMethodCallEmitterTest`.

Documentation:

- `PayloadOrErrors`'s javadoc is the canonical home of the contract and the graphql-java completion invariant (non-null source mandatory; every immediate child arm-switches), backed by the execute-tier test. Javadocs across `TypeFetcherGenerator`, `FieldBuilder`, `WithErrorChannel`, `ChildField.ErrorsField`, `ErrorRouterClassGenerator` update to the wrapper contract.
- The dangling `{@code error-handling-parity.md}` references in those javadocs (no such file exists in-tree; it is named only in javadoc and two other roadmap items) get rewritten to state the wrapper contract inline rather than point at the absent doc. R244 does not author the doc; scrubbing the dangling pointers repo-wide is pre-existing debt for a Backlog stub.

## Tests

- **Unit (`@UnitTier`)**: `ErrorChannelWalkerTest` (one per `Mapped` source shape: single `@error`, union, interface, list; one per `ErrorChannelWalkerError.*` arm: rule 7 / rule 8 / handler-accessor-missing; `Optional.empty()` when the payload has no errors-shaped field) and `ChannelCatchArmEmitterTest` (the `Optional.empty()` redact-only arm, the `Mapped` → `Errors` arm, the `LocalContext` → sentinel arm, and the async-tail lambda wrapping).
- **Pipeline (`@PipelineTier`)**: extend `ErrorChannelClassificationTest` ; positive cases assert on `WalkerResult.Ok(Optional.of(ErrorChannel.Mapped))`, rejection cases on the typed `ErrorChannelWalkerError.*` arms via `WalkerResult.Err`. Any cases asserting fetcher body-string content retire per `rewrite-design-principles.adoc`'s ban on code-string assertions, replaced by structural assertions on the `ErrorChannel.Mapped` carrier.
- **Execution (`@ExecutionTier`) is the primary safety net here, not a backstop.** The wrapper changes the GraphQL source *type* (`PayloadOrErrors` vs the bare record), so "the wire shape is unchanged" is a claim that must be *tested*, not asserted ; that is exactly the assumption the localContext draft got wrong. Add a generated-pipeline execution test that, for an in-scope `@service` payload field, asserts both arms round-trip: the `Ok` path returns `{data: {...}, errors: []}` and the mapped-error path returns `{data: null, errors: [...]}` with the typed `@error` fields populated, plus the unmapped path producing a redacted top-level error. This reproduces the graphql-java spike against the real generated fetchers and pins the completion invariant `PayloadOrErrors`'s javadoc states. The existing `GraphQLQueryTest` error-path cases should continue to pass ; if any fail, the wrapper round-trip is wrong, which is the signal we want.
- **Compilation (`@CompilationTier`)**: `mvn install -Plocal-db` end-to-end-green over `graphitron-sakila-example` catches arm-switch / type-narrowing mismatches at the cross-module compile.

## Deferred to later slices

The staged plan eats the rest of the error-channel machinery in subsequent items, each filed at this slice's In Progress mark:

- **DML transport migration.** Move all six DML mutation variants (the four `DmlTableField` permits ; `MutationInsertTableField`, `MutationUpdateTableField`, `MutationDeleteTableField`, `MutationUpsertTableField` ; plus `MutationDmlRecordField`, `MutationBulkDmlRecordField`) onto `PayloadOrErrors`, and retire `ErrorChannel.LocalContext`, `ErrorRouter.dispatchToLocalContext`, the sentinel emitters (`singleRecordSentinelFor`, `bulkRecordSentinelFor`), `LOCAL_CONTEXT_GUARDED_DATA_CHANNEL_VARIANTS`, and `validateLocalContextErrorsFieldGuards`. The `SingleRecord*` data-channel variants gain the same arm-switch treatment as the in-scope set.
- **Carrier collapse + interface split.** Once `LocalContext` is gone, `ErrorChannel` has a single arm (`Mapped`); the `Optional<ErrorChannel>` accessor collapses to a non-Optional `Mapped | NoChannel` carrier (R222's "no Optional slots"), and `MutationField` stops extending `WithErrorChannel` with membership moving per-permit. Bundled with the DML migration, where it is one coherent change rather than scaffolding.

## Out of scope (entirely)

- `@condition`-bound paths: not in `WithErrorChannel` (they don't emit fetchers).
- Universal `UnclassifiedField` retirement: R222 Stage 4. This slice retires `UnclassifiedField` for the in-scope error-channel rejection paths only.
- The `DataFetcherBuilder` dimensional slot composition: R222 Stage 3. This slice ships the walker carrier and the wrapper transport, not the dimensional slot that would compose `MethodCall` × `ErrorChannel` × … into one emit-ready form.
- `Rejection.AuthorError.Structural(String reason)` callsites outside the in-scope rejection paths: untouched. This slice introduces no new `Structural` callsites; in-scope channel-rejection paths each map to a typed `ErrorChannelWalkerError` arm.

## Supersedes

- **R241** (`retire-error-payloadclass-transport`, Spec): discarded. R241's "route through `localContext`" framing was doubly wrong ; a null payload source silently drops errors (verified, see "Why the wrapper, and not localContext"), and the deeper point is that no developer payload class should be constructed or reflected on at all. This slice routes through the typed `PayloadOrErrors` wrapper instead. The `SlettPoengformelPayload` incident that motivated R241 lands as a non-event ; the generator never reflects on the payload class.
- **R201** (`honor-field-directive-in-payload-construction-shape`, Backlog): moot. The construction-shape machinery R201 targets retires here.
