---
id: R244
title: "Error-channel slice 1: Outcome transport, retire @error payload-class construction"
status: In Review
bucket: structural
depends-on: []
created: 2026-05-26
last-updated: 2026-06-02
---

# Error-channel slice 1: Outcome transport, retire @error payload-class construction

First slice of a staged rewrite that eats the entire `@error`-channel machinery one transport at a time. The end state across all slices: every **outcome field** hands graphql-java a typed `Outcome<T>` source carrying either the resolved value (`Success`) or the mapped errors (`ErrorList`), and the **outcome type**'s child fetchers switch on the arm. No developer payload class is constructed on the error path, no `localContext` side channel, no sentinel jOOQ record. The vocabulary (error type, errors field, outcome type, outcome field, the `Outcome` witness) is defined in "Vocabulary and model" below; the rest of the spec uses those terms precisely.

**This slice is scoped to retiring the domain-object *construction* path, for `@service` and `@tableMethod` outcome fields only.** Today those fields' error path reflects on the developer's payload class and builds it (all-fields-ctor or bean-setter "construction shapes") with the matched errors slotted in; the success path already hands back the raw jOOQ record, so construction is an error-path-only behaviour. That construction machinery (`ErrorChannel.PayloadClass`, the `PayloadConstructionShape` family, the `payloadFactory*` / `declareEarlyPayload*` emitters, `ErrorRouter.dispatch`) is what slice 1 deletes, replaced by the `Outcome` wrapper. R238 set the conventions for input-walking carriers (`MethodCall` on `MethodBackedField`); the walker half of this slice mirrors that on the output side, walking the outcome type rather than the field's arguments.

**Why the wrapper, and not localContext (verified).** An earlier draft proposed `DataFetcherResult.data(null).localContext(errors)`, with the errors field reading `env.getLocalContext()`. A pure graphql-java 25.0 spike proved this silently drops errors: a null source makes graphql-java's `completeValueForObject` short-circuit *all* the outcome type's children, so the errors field is never fetched and `localContext` is never read ; the response is `{outcomeField: null}` with the error swallowed, no top-level error either. The shipped DML transport already documents exactly this and works around it with a non-null sentinel record (see the `ErrorChannel.LocalContext` javadoc). `Outcome` resolves it by construction: the source is always non-null, so graphql-java always descends; the data fields project `Success.value` (null on `ErrorList`, so they render null and their own children are not visited) and the errors field projects `ErrorList.errors`. A second spike confirmed both arms round-trip to the expected `{data, errors}` wire shape. Both spikes are reproduced as a pinned execute-tier test (see Tests).

The slice ships:

- **A generic runtime wrapper `Outcome<T>`** ; sealed `Success<T>(T value) | ErrorList(List<Object> errors)`, emitted into the runtime-support package. This is the *request-time* GraphQL source object that witnesses the fork, deliberately distinct from the *classify-time* `ErrorChannel` carrier.
- **An `OutcomeType` classification** ; the named model concept for an object type that forks (carries an errors field). Computed once, with the single-errors-field invariant enforced by the validator. Replaces today's per-field "first field that looks like errors wins" derivation.
- **`ErrorChannel.PayloadClass` replaced by `ErrorChannel.Mapped(List<ErrorType> mappedErrorTypes, String mappingsConstantName)`** ; the classify-time descriptor of which `@error` types map and which `ErrorMappings` constant drives them. `ErrorChannel.LocalContext` is left untouched (DML still uses it), so `ErrorChannel` becomes sealed `Mapped | LocalContext`. `WithErrorChannel.errorChannel()` keeps its `Optional<ErrorChannel>` shape (absence = no channel = redact-only). No interface split, no `NoChannel` arm, no `Optional` removal in this slice ; those are deferred (see "Deferred to later slices").
- **A producer `ErrorChannelWalker`** resolving the channel on an outcome type's errors field: identify mapped `@error` types, run the channel-level rule checks (rule 7 multi-VALIDATION, rule 8 duplicate match-criteria), reflect on handler source classes for per-`@error`-type accessor coverage, derive the mappings-constant name. Subsumes the in-scope role of `FieldBuilder.resolveErrorChannel`, `checkChannelLevelHandlerRules`, `checkDuplicateMatchCriteria`, `checkErrorTypeSourceAccessors`.
- **Emitter rewrite for the in-scope fields.** The success path wraps the existing jOOQ-record return in `Success(...)`; the error path returns `ErrorList(matchedErrors)` after the `(Mapping, cause)` match-walk; the unmapped fallback stays `ErrorRouter.redact(...)` (a real top-level GraphQL error, distinct from the typed channel). The validator Jakarta-violation pre-step and the async `.exceptionally(...)` tail emit the same `ErrorList(...)`. `Mapping.match(Throwable)` and `redact` survive; the payload-factory router `ErrorRouter.dispatch` retires.
- **Arm-switching child fetchers** for every immediate field of an in-scope outcome type: the data fields unwrap `Success.value` before their existing column/key read (null on `ErrorList`), the errors field reads `ErrorList.errors`. Pinned by a validator check (see "Outcome-child arm-switch invariant") so an un-switched field is a build-time failure, not a silent runtime hole.
- **A new `ChildField.ErrorsField.Transport.WrapperArm`** arm; in-scope errors fields read `ErrorList.errors` off the `Outcome` source. `Transport.LocalContext` (DML) and `Transport.PayloadAccessor` (non-channel parents) are untouched. The `Transport` taxonomy gains an arm; nothing collapses in this slice.
- R201 (`honor-field-directive-in-payload-construction-shape`) retires as moot; R241 (`retire-error-payloadclass-transport`) is superseded.

**Two transports coexist after slice 1.** DML (`MutationDmlRecordField`, `MutationBulkDmlRecordField`, and the four `DmlTableField` permits) stays on the sentinel/`localContext` transport, selected per-field by the catch-arm emitter. This is a sound intermediate: the `Outcome` source object and the sentinel/`localContext` shape share no runtime type, and `MutationField`'s interface-level `WithErrorChannel` membership is undisturbed because the carrier stays `Optional<ErrorChannel>` with `LocalContext` intact. Migrating DML onto `Outcome` and retiring the sentinel machinery is a later slice (see "Deferred to later slices").

## Vocabulary and model

The `@error`-handling domain has five layers; only the leaf had a precise name before this slice. The terms below are the model's vocabulary going forward.

- **Error type** ; a type the `@error` directive marks (leaf), *or* a union/interface whose members are all error types (recursive closure). "Structurally nothing but errors." Model: `GraphitronType.ErrorType` is the leaf; the composite closure is re-derived today inside the errors-field predicate.
- **Errors field** ; a field whose type is a list of an error type. The runtime slot that carries the error list. Model: `ChildField.ErrorsField`.
- **Outcome type** ; an object type that contains an errors field. This is where the success/error fork lives. Its **success projection** is its non-errors (data) fields; its **error projection** is the single errors field. An outcome type is structurally a record-backed object type (today's `GraphitronType.ResultType` family) that additionally carries an errors field, so `OutcomeType` is a classification *within* that family, not a rename of it (`ResultType` stays as the record-backed family name; `org.jooq.Result` / jOOQ `DataType` collisions ruled out reusing those names).
- **Outcome field** ; a field whose type is an outcome type; its fetcher decides the fork at request time. This is graphitron's existing `WithErrorChannel` implementer set.
- **`Outcome<T>` witness** ; the request-time source object the outcome field's fetcher returns: `Success(value)` for the success projection, `ErrorList(errors)` for the error projection.

Reading top to bottom: an **error type** is the element of an **errors field**, which lives on an **outcome type**, which is the result type of an **outcome field**, whose fetcher returns an **`Outcome`** that witnesses which arm fired. The type is the *shape* of the fork (static), the field is the *site* of the fork (runtime decision), the `Outcome` is the *witness*.

**Enforced invariants (first iteration).** Today these are conventions the generator does not check; this slice promotes the load-bearing one to a validator rule (mirror-the-classifier):

- *Exactly one errors field per outcome type.* The binary `Outcome` witness has one error slot, so a type with two errors fields has no well-defined fork. Rejected at classification with a typed `ErrorChannelWalkerError.MultipleErrorsFields` arm. (Zero errors fields = not an outcome type, a plain record type.)
- *The success projection may have any number of data fields.* `Success.value` is the single backing record/value; each data field projects off it as today. One field or many, no constraint.
- *An outcome type is record-backed*, not table- or node-backed (a jOOQ table cannot carry an errors field). Outside the structural shapes this slice classifies; not a new check.

Shapes left for a later pass (not enforced or specially handled this slice, called out so they are not silently mis-handled): a bare (non-list) error-typed field; mixed unions of error and non-error types (simply not an errors field); nested outcome types (legal, each fork independent); an `@error` type declared but never used.

## The runtime wrapper (`Outcome`)

```java
// graphitron/.../runtime support package (alongside ErrorRouter / Mapping)
public sealed interface Outcome<T> permits Outcome.Success, Outcome.ErrorList {
    record Success<T>(T value) implements Outcome<T> {}
    record ErrorList<T>(List<Object> errors) implements Outcome<T> {}  // T phantom on this arm
}
```

`Success.value` holds exactly what the success path produces today (a typed jOOQ `XRecord`, `Result<Record>`, or `List<XRecord>`). `ErrorList.errors` is `List<Object>` because it carries two populations: matched throwables on the catch path and Jakarta `ConstraintViolation` objects on the validator pre-step path (both are what the retired `payloadFactory.apply(...)` received). `Object` is the floor this slice faithfully ports rather than a new untyped leak; a later slice could narrow it to a sealed `MatchedError { Caused(Throwable) | Violation(ConstraintViolation) }` so the per-`@error`-type field DataFetchers switch exhaustively instead of `instanceof`-on-`Object`. Those field DataFetchers read off each element exactly as today. The two arms map one-to-one onto the outcome type's two field families: data fields read `Success.value`, the errors field reads `ErrorList.errors`. The `ErrorList` arm's type parameter is phantom (the error path discards the success type); it exists only so the arm satisfies `Outcome<T>`. `Outcome`'s javadoc is the pinned home of the graphql-java completion invariant (why a non-null source is mandatory, why every immediate child must arm-switch), backed by the execute-tier test in Tests.

The naming avoids the live collisions in this area: not `Result` (jOOQ `org.jooq.Result<Record>` is the DataFetcher return type), not `Resolved` (the builder-step result sealed type); the `ErrorList` arm is named for what it carries rather than `Error` (singular), which would shadow `Rejection.AuthorError` and `GraphitronType.ErrorType`.

## Target emitted code

The reducer backtracks from this shape. Every `@service` / `@tableMethod` fetcher wraps its success return in `Success` and its mapped error in `ErrorList`; the unmapped fallback is unchanged:

```java
// QueryServiceRecordField example, post-slice
public static DataFetcherResult<Outcome<SakRecord>> sak(DataFetchingEnvironment env) {
    try {
        SakRecord result = SakService.run(...);
        return DataFetcherResult.<Outcome<SakRecord>>newResult()
            .data(new Success<>(result)).build();
    } catch (Throwable e) {
        for (Mapping m : ErrorMappings.SAK_PAYLOAD) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (m.match(t)) {
                    return DataFetcherResult.<Outcome<SakRecord>>newResult()
                        .data(new ErrorList<>(List.of(t))).build();
                }
            }
        }
        return ErrorRouter.redact(e, env);   // unmapped -> top-level GraphQL error
    }
}
```

The outcome type `SakPayload`'s child fetchers receive a non-null `Outcome<SakRecord>` as `env.getSource()` and switch on the arm:

```java
// SakPayload.<dataField> fetcher (was: PropertyDataFetcher.fetching("...") / @record accessor)
// The Outcome source is bound to a typed local first; env.getSource() is <T> T getSource(),
// which infers to Object in an instanceof, so a bare `getSource() instanceof Success<SakRecord>`
// is rejected ("Object cannot be safely cast"). The typed local makes the narrowing checked and
// keeps s.value() typed as SakRecord (no blind Object cast reappears).
env -> {
    Outcome<SakRecord> src = env.getSource();
    return src instanceof Success<SakRecord> s ? <existing read off s.value()> : null;
};

// SakPayload.errors fetcher  (Transport.WrapperArm) ; wildcard pattern is always legal
env -> env.getSource() instanceof ErrorList<?> e ? e.errors() : List.of();
```

On `ErrorList`, the data fields return null, so graphql-java renders them null and does not descend into their children; the errors field resolves because the outcome-type source is non-null. **This holds only when the success-projection fields are nullable**: a non-null data field (`Sak!`) that resolves null on the error arm raises `NonNullableFieldWasNullError` and bubbles null up to the outcome field, dropping the sibling errors field (verified by spike, the same failure class the localContext draft had). The nullable-success-projection invariant is enforced as a validator rule (see "Outcome-child arm-switch invariant"). On `Success`, each data field projects `s.value()` (the jOOQ record, typed from the field's resolved `ReturnTypeRef` via the typed-local narrowing above, so no blind cast reappears) and the errors field returns the empty list.

Validator pre-step (Jakarta-violation early return) returns the same `ErrorList` arm:

```java
// inside buildServiceFetcherCommon, pre-step
if (!__violations.isEmpty()) {
    return DataFetcherResult.<Outcome<SakRecord>>newResult()
        .data(new ErrorList<>(__violations)).build();
}
```

No `__earlyPayload` local, no payload-class construction. The async tail (`.exceptionally(...)` on `CompletableFuture`-shaped fetchers) lifts the same `ErrorList(...)` into the lambda over the throwable, and the success `.thenApply(...)` wraps in `Success(...)`; otherwise identical.

## Classify-time carrier (`ErrorChannel`)

The carrier stays on the existing `WithErrorChannel` interface, accessor unchanged:

```java
// graphitron/src/main/java/no/sikt/graphitron/rewrite/model/WithErrorChannel.java
public interface WithErrorChannel {
    Optional<ErrorChannel> errorChannel();   // unchanged in this slice
}
```

`ErrorChannel` is the *classify-time* descriptor ("what to emit"), not the runtime `Outcome` source ("what runs"); the two are kept as separate types deliberately. The walker produces `ErrorChannel.Mapped` for in-scope fields; DML continues to produce `ErrorChannel.LocalContext`; absence is `Optional.empty()`.

**Why `Optional` and the shared carrier stay this slice.** A prior draft narrowed the carrier to a non-Optional `Mapped | NoChannel` and split DML onto a sibling `WithDmlErrorTransport` interface (because `MutationField extends WithErrorChannel` at the interface level, `MutationField.java:17`, so all mutation permits inherit the slot). That works, but it is churn this slice does not need: leaving `Optional<ErrorChannel>` with `LocalContext` intact means `MutationField`'s membership is undisturbed, the DML permits keep their exact behaviour, and slice 1 touches only the `PayloadClass`-using fields. The `Optional` → non-Optional `NoChannel` collapse and the per-permit interface split are bundled with the DML migration (the slice that actually retires `LocalContext`), where they are a single coherent change rather than scaffolding erected and torn down inside one slice. R222's "no Optional slots" is honoured at the end of the staged rewrite, not mid-stream.

## Carrier shape

```java
// graphitron/src/main/java/no/sikt/graphitron/rewrite/model/ErrorChannel.java
public sealed interface ErrorChannel permits ErrorChannel.Mapped, ErrorChannel.LocalContext {

    // NEW ; replaces the retired PayloadClass arm. Drives the Outcome emit.
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

`Mapped` and `LocalContext` carry identical fields by coincidence ; they are distinct types because they drive different emit (`Mapped` → `Outcome`, `LocalContext` → sentinel + `dispatchToLocalContext`). The slice deletes `ErrorChannel.PayloadClass` entirely (with its `payloadClass`, `errorsSlot`, `defaultedSlots` components). The shared accessors that today live on the sealed `ErrorChannel` root (`mappedErrorTypes`, `mappingsConstantName`) can stay on the root (both arms have them) or move to per-arm reads; either is fine and is an implementer's call, since the root no longer has a third arm with a different shape.

## The `OutcomeType` classification

`OutcomeType` is a concrete carrier, not a bare predicate, so the guarantees the walker relies on are structural rather than prose. The classifier builds it only after the single-errors-field and nullable-success-projection checks pass, so possessing an `OutcomeType` *is* the proof those invariants hold:

```java
// graphitron/src/main/java/no/sikt/graphitron/rewrite/model/OutcomeType.java
public record OutcomeType(
    GraphitronType.ResultType backing,        // the record-backed type that forks
    ChildField.ErrorsField errorsField,       // the single errors field (uniqueness already enforced)
    List<ChildField> successProjection        // the non-errors data fields, all nullable (enforced)
) {
    public OutcomeType {
        successProjection = List.copyOf(successProjection);
        if (errorsField == null) throw new IllegalArgumentException("OutcomeType: errorsField must be non-null");
    }
}
```

The classifier is the single producer: it runs `BuildContext.detectErrorsFieldShape`, enforces uniqueness (`MultipleErrorsFields`) and nullability (`NonNullableSuccessProjectionField`), and only then constructs `OutcomeType`. The walker takes the built `OutcomeType` and reads `errorsField` directly rather than re-scanning ; the type makes "exactly one errors field, already found" non-bypassable, instead of a comment the walker trusts.

## Producer (`ErrorChannelWalker`)

```java
// graphitron/src/main/java/no/sikt/graphitron/rewrite/walker/ErrorChannelWalker.java
public final class ErrorChannelWalker {
    public WalkerResult<ErrorChannel.Mapped> walk(
        OutcomeType outcomeType,
        ClassLoader codegenLoader,
        MappingsConstantNameDedup dedup
    );
}
```

The walker runs only for outcome-typed fields. The `OutcomeType` classification has already found the single errors field and enforced uniqueness, so the walker reads the errors field off the classification rather than re-scanning, and always yields `Mapped` or `Err`; a field whose return type is not an outcome type simply has no channel, decided upstream as `Optional.empty()`. Substrate: the classified `OutcomeType` plus the codegen classloader for handler-class reflection and the build-scoped name-dedup helper. This is the output-walking analogue of R238's `ServiceMethodCallWalker` (`graphitron/src/main/java/no/sikt/graphitron/rewrite/walker/ServiceMethodCallWalker.java`): same producer-as-thin-layer-over-graphql-java pattern, different SDL surface.

R238's shipped walker took a pragmatic substrate concession: it translates from an upstream resolved `MethodRef.Service` rather than reflecting directly, because `ServiceCatalog.reflectServiceMethod` is 1258 LOC of battle-tested reflection a translator avoids duplicating. This walker does not need that concession: the in-scope part of `FieldBuilder.resolveErrorChannel` is ~200 LOC, so it absorbs the channel-rule and accessor-coverage checks (`checkChannelLevelHandlerRules`, `checkDuplicateMatchCriteria`, `checkErrorTypeSourceAccessors`) under `walker/internal/` and takes over the in-scope `@error`-type classification role of `resolveErrorChannel`.

**Walker stages** (one pass per in-scope outcome field):

1. **Identify mapped `@error` types** from the outcome type's errors field (list element, union members, interface implementations). Non-empty by the errors-field definition; one-element for `[SomeError]`, multi-element for unions / interfaces.
2. **Run channel-level rules.** Rule 7 (no two VALIDATION handlers in one channel), rule 8 (no duplicate match-criteria across the flattened handler list). Failure: `ErrorChannelWalkerError.ChannelRuleViolation(outcomeTypeName, errorsFieldName, ruleNumber, detail)`.
3. **Reflect on handler source-classes for accessor coverage.** Per (channel, `@error` type, handler), verify the handler's source class exposes a `PropertyDataFetcher`-visible accessor for each of the `@error` type's declared SDL fields. `path` and `message` are exempt (populated by per-`@error`-type synthesised DataFetchers). Failure: `ErrorChannelWalkerError.HandlerSourceAccessorMissing(...)`.
4. **Resolve the mappings-constant name** via `MappingsConstantNameDedup`: `SCREAMING_SNAKE(outcomeTypeName)`, with the 8-hex SHA-256 suffix on collision per the existing dedup rules. Build-scoped, so the walker takes the dedup helper as a constructor arg.
5. **Emit result.** `Ok(Mapped(types, constName), [])` on success; `Err(authorErrors, diagnostics)` on any structural failure, collecting across stages rather than short-circuiting. (`Ok` / `Err` here are `WalkerResult` arms, not the `Outcome` runtime wrapper.)

**Invocation point.** `FieldBuilder`, at each in-scope `WithErrorChannel` constructor site whose return type classified as an `OutcomeType`, calls the walker; non-outcome return types get `Optional.empty()` (no channel, redact-only). Today's sites pass through `resolveErrorChannel` (a stringly-typed `ErrorChannelResult`); under the slice those collapse to the walker call. R238's "no fallback to `UnclassifiedField`" applies: `Err` surfaces through `WalkerResult.Err.errors` and the orchestrator's diagnostic stream; the field leaves the classified set.

**Unit-testability** mirrors R238: parse an SDL fragment, configure a small test `ClassLoader` with fixture handler / `@error`-source classes, classify the outcome type, call `walk`, assert on the sealed result.

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
- `ErrorChannel.Mapped`: emit the inline mapping-walk loop + `return ...data(new ErrorList<>(List.of(t)))...` match-return + `redact` fallback shown in the target-code section.
- `ErrorChannel.LocalContext`: emit today's `return ErrorRouter.dispatchToLocalContext(e, ErrorMappings.CONST, env, sentinel);` (the DML sentinel arm) ; unchanged behaviour, routed through the shared seam so DML and in-scope fields share one emitter rather than two parallel `catchArm` overloads. This arm is reachable only for DML fields; the classifier guarantees in-scope fields never carry `LocalContext`.

The success `Success`-wrap is emitted at the existing `returnSyncSuccess` seam (`new Success<>(result)` instead of the bare `result`). `asyncWrapTail` consumes the same emitter through a small wrapping context that lifts the result into the `.exceptionally(...)` lambda and wraps the `.thenApply(...)` success value in `Success`. The Jakarta-violation pre-step ; the inline `if (!__violations.isEmpty())` block emitted inside `buildServiceFetcherCommon` (`TypeFetcherGenerator.java:1492`), which today calls `declareEarlyPayloadFromErrors(channel, "__violations")` ; switches to a sibling helper `ChannelEarlyReturnEmitter.emit(channel, valueType, violationsLocal)` emitting the `new ErrorList<>(violations)` early return.

`TypeFetcherGenerator.payloadFactoryLambda(+Ctor/+Setters)`, `declareEarlyPayloadFromErrors(+Ctor/+Setters)`, `dispatchCatchArm`, and the `catchArm` ctor-arm overload retire at this seam (the `Mapped` and `LocalContext` arms above subsume their job).

## Cutover sequencing

R238's additive-then-destructive landing is the precedent for the scaffolding and the final delete. One difference matters here: an input carrier could live in a parallel slot while consumers migrated one at a time, because the legacy and new slots were independent. The wrapper is *not* independent ; the moment an outcome field's fetcher returns `Outcome` instead of a bare record, every one of that outcome type's child fetchers must arm-switch in the same commit, because they all read the one shared `env.getSource()`. So the in-scope flip is atomic **per outcome type** (the root/child fetchers of one outcome type move together); it is not atomic across the whole codebase (different outcome types can flip in different commits if that helps review).

Commit-level sequencing (subject to revision at In Progress time; the load-bearing claim is the sequence *shape*):

1. **Add the unwired pieces.** `Outcome<T>` runtime type, the `OutcomeType` classification + its two classify-time validator rules (single-errors-field → `MultipleErrorsFields`; nullable-success-projection → `NonNullableSuccessProjectionField`), `ErrorChannel.Mapped` (added under the sealed root, which temporarily permits `Mapped | PayloadClass | LocalContext`), the rest of the `ErrorChannelWalkerError` sub-seal, its `typed-rejection.adoc` paragraph (else `SealedHierarchyDocCoverageTest` fails), `ChildField.ErrorsField.Transport.WrapperArm`, and the outcome-child arm-switch validator pin (`OUTCOME_TYPE_ARM_SWITCHED_DATA_CHANNEL_VARIANTS` + `validateOutcomeChildArmSwitch`, see below). Nothing produces or consumes `Mapped` yet; `PayloadClass` is intact and remains what in-scope fields classify to. The `OutcomeType` classification and both rules can go live here (observable only through the new additive rejections ; they reject schemas that were already mis-shaped). The `ChannelCatchArmEmitter` switch added in step 2 over the transitional three-arm root must handle the `PayloadClass` arm (throw on the unreachable case) so the sealed switch compiles during the additive window.
2. **Add the walker and emitters.** `ErrorChannelWalker`, `ChannelCatchArmEmitter`, `ChannelEarlyReturnEmitter`, with unit-tier tests. Still unwired.
3. **Flip the in-scope fields to the wrapper.** `FieldBuilder` classifies in-scope outcome fields to `ErrorChannel.Mapped` via the walker over the classified `OutcomeType` (replacing `resolveErrorChannel`'s `PayloadClass` production for those fields). In the same commit: `returnSyncSuccess` wraps in `Success`; `ChannelCatchArmEmitter` / `ChannelEarlyReturnEmitter` / `asyncWrapTail` emit the `ErrorList` arm; every in-scope outcome type's data-field fetchers arm-switch on `Success`; in-scope errors fields move to `Transport.WrapperArm`; `MappingsConstantNameDedup` and `CatalogBuilder.errorChannelName` read `Mapped`. This is the atomic-per-outcome-type flip. DML still classifies to `LocalContext` and emits the sentinel arm through the same `ChannelCatchArmEmitter`, unchanged.
4. **Delete the construction machinery.** `ErrorChannel.PayloadClass` arm (sealed root returns to `Mapped | LocalContext`); `ErrorsSlot`, `DefaultedSlot`, `NonBoundSetter`, `PayloadConstructionShape` family; `FieldBuilder.resolvePayloadConstructionShape` + `tryMutableBean` + `buildErrorChannelCtorArm` + `buildErrorChannelBeanArm` + `collectDefaultedSlots` + `collectNonBoundSetters` + `defaultLiteralFor`; `TypeFetcherGenerator`'s `payloadFactoryLambda*` + `declareEarlyPayload*` + `dispatchCatchArm`; `ErrorRouter.dispatch` (the payload-factory router). `FieldBuilder.resolveErrorChannel` keeps its DML role (`buildDmlField` still calls it; it returns no-channel for the non-wrapper DML returns and `LocalContext` for record-payload DML), so it is trimmed to that role rather than deleted.

The dual-shape window is steps 1-2 (additive, unwired) into step 3 (flip); step 4 closes it. `Mapping.match`, `ErrorRouter.redact`, `ErrorRouter.dispatchToLocalContext`, the sentinel emitters, `LOCAL_CONTEXT_GUARDED_DATA_CHANNEL_VARIANTS`, and `validateLocalContextErrorsFieldGuards` all survive slice 1 untouched ; they belong to the DML transport, retired by a later slice.

## Outcome-child arm-switch invariant

Under the wrapper, every immediate child field of an in-scope outcome type receives a non-null `Outcome` as `env.getSource()`. So each such fetcher must unwrap `Success` before its existing read and return null on `ErrorList`. The data-channel `ChildField` variants that can sibling an `ErrorsField` under an `@service` / `@tableMethod` outcome type, all of which already get an explicit graphitron-emitted fetcher (`FetcherRegistrationsEmitter` registers every classified field; none falls through to graphql-java's default `PropertyDataFetcher`), and the read each wraps:

- `PropertyField`, `RecordField`: today `PropertyDataFetcher.fetching(col)` / `@record`-accessor read off the source ; now `src instanceof Success s ? <that read on s.value()> : null`.
- `ServiceTableField`, `ServiceRecordField`, `TableMethodField`, `RecordTableMethodField`: child `@service` / `@tableMethod` fetchers that read parent keys off the source ; unwrap `Success` first so the method is never invoked on an `ErrorList` source.
- `ConstructorField`, `NestingField`, `ComputedField`: source passthrough / accessor call ; unwrap `Success` first.

**The unwrapped value keeps its narrow type.** Per "Classifier guarantees shape emitter assumptions", the `Success.value()` local is typed from the field's already-resolved `ReturnTypeRef` (e.g. the specific jOOQ `XRecord`) via the typed-local narrowing shown in "Target emitted code", so no blind `Object` cast is reintroduced. The root fetcher emits `Success<X>` and the child emits `instanceof Success<X>` reading the *same* `X` from the *same* `ReturnTypeRef`; that agreement is not carried by the type system across the erased `env.getSource()` seam, so the pin is the cross-module `graphitron-sakila-example` compile (a mismatched `X` is a compile error) plus the execute-tier round-trip, named precisely rather than asserted as a type-system property.

**Success-projection fields must be nullable.** Verified by spike: on the `ErrorList` arm a data field returns null, and if that field's SDL type is non-null (`Sak!`) graphql-java raises `NonNullableFieldWasNullError` and bubbles the null up to the outcome field, *dropping the sibling errors field* ; the typed `@error` payload is silently lost and replaced by a generic top-level error. This is the same failure class as the localContext draft, surfacing through a different mechanism (non-null coercion rather than null-source short-circuit). So slice 1 promotes the nullability convention to a validator rule (mirror-the-classifier, as with the single-errors-field rule): an outcome type whose success projection contains a non-null field is rejected at classify time with `ErrorChannelWalkerError.NonNullableSuccessProjectionField` (unit-tier test, see Tests). The rejection is what keeps the execute-tier round-trip honest: without it, a non-null data field is the nullable-only blind spot the localContext draft's reasoning had, surviving to a production request as a dropped errors field. (This is plausibly a pre-existing latent issue on the `PayloadClass` path, where a defaulted non-null slot left null bubbles the same way; slice 1 owns surfacing it because it makes the wire-shape-unchanged claim.)

**This invariant is pinned, not audited.** An un-switched outcome-type child is a silent runtime hole: graphql-java's default fetcher would read a property off the `Outcome` object itself. The dispatch partition (`GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus`) does *not* catch this: it pins a *global* property (every leaf lands in exactly one dispatch-status set), whereas the arm-switch guarantee is *contextual* (this leaf, *when it is an immediate child of an outcome type*, unwraps `Success`). A `PropertyField` under an outcome type is the same globally-`IMPLEMENTED` leaf as a `PropertyField` anywhere else, so a missing arm-switch leaves the coverage test green. The correct pin is a dedicated validator pass, the direct analogue of the DML `LOCAL_CONTEXT_GUARDED_DATA_CHANNEL_VARIANTS` + `validateLocalContextErrorsFieldGuards` pair: slice 1 adds an `OUTCOME_TYPE_ARM_SWITCHED_DATA_CHANNEL_VARIANTS` allow-list and a `validateOutcomeChildArmSwitch` pass that iterates outcome types and rejects any immediate child whose leaf is off the allow-list. A new data-channel leaf that can appear under an outcome type without an arm-switching fetcher then fails *that* pass at build time, not a production request. (The DML pair stays in place unchanged for the DML transport.)

## AuthorError sub-arms and LSP codes

Following R238's actual precedent: each walker family gets its **own sibling sub-seal of `AuthorError`**, not a sub-arm under `Structural`. R238's `ServiceMethodCallError` (in `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/ServiceMethodCallError.java`) sets the pattern; R244 mirrors it with `ErrorChannelWalkerError`. Quote from R238's spec on the choice: "Subsequent walker slices each add their own sibling sub-seal rather than piling typed arms under a single flat `Structural` — keeps `AuthorError` permits one-row-per-walker as the dimensional pivot scales, and lets each walker's arm-to-code mapping live next to its own family declaration."

`ErrorChannelWalkerError` is the **error-channel domain's** sub-seal, not literally walk()-only: two arms (`MultipleErrorsFields`, `NonNullableSuccessProjectionField`) are raised by the `OutcomeType` *classification* that produces the walker's input, and the rest are raised by `walk()`. They share one family because they share one SDL surface (the outcome type and its errors field) and one LSP namespace; each arm's javadoc names its actual raiser. (The alternative ; folding uniqueness and nullability into `walk()` so the family is strictly walker-raised ; is viable but pushes errors-field detection into the walker that `BuildContext.detectErrorsFieldShape` already centralises; keeping `OutcomeType` construction as the single producer of those two arms is the smaller seam. See finding in the design review.)

```java
// graphitron/src/main/java/no/sikt/graphitron/rewrite/model/ErrorChannelWalkerError.java
public sealed interface ErrorChannelWalkerError extends Rejection.AuthorError permits
    ErrorChannelWalkerError.MultipleErrorsFields,
    ErrorChannelWalkerError.NonNullableSuccessProjectionField,
    ErrorChannelWalkerError.ChannelRuleViolation,
    ErrorChannelWalkerError.HandlerSourceAccessorMissing
{
    /** LSP wire code under the {@code graphitron.error-channel.} namespace. */
    String lspCode();

    @Override default Rejection prefixedWith(String prefix) { return this; }

    /** Raised by the OutcomeType classification: a type carries more than one errors field. */
    record MultipleErrorsFields(
        String outcomeTypeName,
        List<String> errorsFieldNames
    ) implements ErrorChannelWalkerError {
        @Override public String message() { /* names the fields; exactly one is allowed */ }
        @Override public String lspCode() { return "graphitron.error-channel.multiple-errors-fields"; }
    }

    /**
     * Raised by the OutcomeType classification: a success-projection (data) field is non-null,
     * which would bubble null up and drop the errors field on the error arm (see the arm-switch
     * invariant). Success-projection fields must be nullable.
     */
    record NonNullableSuccessProjectionField(
        String outcomeTypeName,
        String fieldName
    ) implements ErrorChannelWalkerError {
        @Override public String message() { /* names the field; must be nullable */ }
        @Override public String lspCode() { return "graphitron.error-channel.non-nullable-success-field"; }
    }

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
| `MultipleErrorsFields` | `graphitron.error-channel.multiple-errors-fields` |
| `NonNullableSuccessProjectionField` | `graphitron.error-channel.non-nullable-success-field` |
| `ChannelRuleViolation(rule=7, ...)` | `graphitron.error-channel.multi-validation` |
| `ChannelRuleViolation(rule=8, ...)` | `graphitron.error-channel.duplicate-match-criteria` |
| `HandlerSourceAccessorMissing` | `graphitron.error-channel.handler-accessor-missing` |

`source: "graphitron"`, severity `Error`, primary `SourceLocation` is the outcome type's SDL location (or the errors field's location when the rule applies at the channel level). Offending handler details (per-`@error`-type, per-handler) go in `Diagnostic.relatedInformation`. The `AuthorError` parent grows one new permit (`ErrorChannelWalkerError`), keeping its arms one-row-per-walker.

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

*Does not retire:* `FieldBuilder.resolveErrorChannel` is trimmed to its DML role (still called by `buildDmlField`; produces no-channel for non-wrapper DML returns), not deleted. `BuildContext.detectErrorsFieldShape` stays a shared helper (called from `GraphitronSchemaBuilder`, `MutationInputResolver`, and the DML path); the `OutcomeType` classification consumes it to find the errors field(s) and enforce uniqueness, and the walker then reads the single errors field off the classification.

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
- `ErrorChannelClassificationTest`'s positive cases rewrite to assert on `WalkerResult.Ok(ErrorChannel.Mapped)`; the no-channel case asserts the field classified with `Optional.empty()` (return type is not an outcome type); rejection cases assert on the typed `ErrorChannelWalkerError.*` sub-arms via `WalkerResult.Err`, including `MultipleErrorsFields` from the `OutcomeType` classification.
- New `ErrorChannelWalkerTest` and `ChannelCatchArmEmitterTest` (unit tier) mirror R238's `ServiceMethodCallWalkerTest` / `ServiceMethodCallEmitterTest`.

Documentation:

- `Outcome`'s javadoc is the canonical home of the contract and the graphql-java completion invariant (non-null source mandatory; success-projection fields nullable; every immediate child arm-switches), backed by the execute-tier test. Javadocs across `TypeFetcherGenerator`, `FieldBuilder`, `WithErrorChannel`, `ChildField.ErrorsField`, `ErrorRouterClassGenerator` update to the wrapper contract.
- **`typed-rejection.adoc` must gain a paragraph for the new sub-seal.** `SealedHierarchyDocCoverageTest` walks `Rejection.permits()` transitively and fails the build for any permit not documented in `typed-rejection.adoc` (see its "Drift protection" section). This slice adds one `AuthorError` permit (`ErrorChannelWalkerError`) with four arms, so the cutover must land a corresponding `typed-rejection.adoc` paragraph in the same change, not as follow-up.
- The dangling `{@code error-handling-parity.md}` references in those javadocs (no such file exists in-tree; it is named only in javadoc and two other roadmap items) get rewritten to state the wrapper contract inline rather than point at the absent doc. R244 does not author the doc; scrubbing the dangling pointers repo-wide is pre-existing debt for a Backlog stub.

## Tests

- **Unit (`@UnitTier`)**: an `OutcomeType` classification test (no errors field → not an outcome type; exactly one → outcome type with the right success projection; two or more → `ErrorChannelWalkerError.MultipleErrorsFields`; a non-null success-projection field → `ErrorChannelWalkerError.NonNullableSuccessProjectionField`); `ErrorChannelWalkerTest` (one per `Mapped` source shape: single `@error`, union, interface, list; one per channel-rule arm: rule 7 / rule 8 / handler-accessor-missing); and `ChannelCatchArmEmitterTest` (the `Optional.empty()` redact-only arm, the `Mapped` → `ErrorList` arm, the `LocalContext` → sentinel arm, and the async-tail lambda wrapping).
- **Pipeline (`@PipelineTier`)**: extend `ErrorChannelClassificationTest` ; positive cases assert on `WalkerResult.Ok(ErrorChannel.Mapped)`, rejection cases on the typed `ErrorChannelWalkerError.*` arms via `WalkerResult.Err`. Any cases asserting fetcher body-string content retire per `rewrite-design-principles.adoc`'s ban on code-string assertions, replaced by structural assertions on the `ErrorChannel.Mapped` carrier and the `OutcomeType` classification.
- **Execution (`@ExecutionTier`) is the primary safety net here, not a backstop.** The wrapper changes the GraphQL source *type* (`Outcome` vs the bare record), so "the wire shape is unchanged" is a claim that must be *tested*, not asserted ; that is exactly the assumption the localContext draft got wrong. Add a generated-pipeline execution test that, for an in-scope `@service` outcome field, asserts both arms round-trip: the `Success` path returns `{data: {...}, errors: []}` and the mapped-error path returns `{data: null, errors: [...]}` with the typed `@error` fields populated, plus the unmapped path producing a redacted top-level error. This reproduces the graphql-java spike against the real generated fetchers and pins the completion invariant `Outcome`'s javadoc states. The existing `GraphQLQueryTest` error-path cases should continue to pass ; if any fail, the wrapper round-trip is wrong, which is the signal we want.
- **Compilation (`@CompilationTier`)**: `mvn install -Plocal-db` end-to-end-green over `graphitron-sakila-example` catches arm-switch / type-narrowing mismatches at the cross-module compile.

## Deferred to later slices

The staged plan eats the rest of the error-channel machinery in subsequent items, each filed at this slice's In Progress mark:

- **DML transport migration.** Move all six DML mutation variants (the four `DmlTableField` permits ; `MutationInsertTableField`, `MutationUpdateTableField`, `MutationDeleteTableField`, `MutationUpsertTableField` ; plus `MutationDmlRecordField`, `MutationBulkDmlRecordField`) onto `Outcome`, and retire `ErrorChannel.LocalContext`, `ErrorRouter.dispatchToLocalContext`, the sentinel emitters (`singleRecordSentinelFor`, `bulkRecordSentinelFor`), `LOCAL_CONTEXT_GUARDED_DATA_CHANNEL_VARIANTS`, and `validateLocalContextErrorsFieldGuards`. The `SingleRecord*` data-channel variants gain the same arm-switch treatment as the in-scope set.
- **Carrier collapse + interface split.** Once `LocalContext` is gone, `ErrorChannel` has a single arm (`Mapped`); the `Optional<ErrorChannel>` accessor collapses to a non-Optional `Mapped | NoChannel` carrier (R222's "no Optional slots"), and `MutationField` stops extending `WithErrorChannel` with membership moving per-permit. Bundled with the DML migration, where it is one coherent change rather than scaffolding.

## Out of scope (entirely)

- `@condition`-bound paths: not in `WithErrorChannel` (they don't emit fetchers).
- Universal `UnclassifiedField` retirement: R222 Stage 4. This slice retires `UnclassifiedField` for the in-scope error-channel rejection paths only.
- The `DataFetcherBuilder` dimensional slot composition: R222 Stage 3. This slice ships the walker carrier and the wrapper transport, not the dimensional slot that would compose `MethodCall` × `ErrorChannel` × … into one emit-ready form.
- `Rejection.AuthorError.Structural(String reason)` callsites outside the in-scope rejection paths: untouched. This slice introduces no new `Structural` callsites; in-scope channel-rejection paths each map to a typed `ErrorChannelWalkerError` arm.

## Progress

**Commit 1 (additive, unwired) ; landed.** The following pieces are in place, observable only
through the new additive rejections; nothing produces or consumes `Mapped` yet, `PayloadClass` is
intact and remains what in-scope fields classify to:

- `Outcome` runtime type, emitted by `OutcomeClassGenerator` into `<outputPackage>.schema.Outcome`
  and registered in `GraphQLRewriteGenerator`. The JavaPoet fork has no `sealed` / `permits` /
  `recordBuilder` support (see the note in `ConnectionHelperClassGenerator`), so `Outcome` is
  emitted as a plain generic interface with two implementing classes (`Success<T>` /
  `ErrorList<T>`) carrying `value()` / `errors()` accessors; the runtime contract (non-null source,
  the two arms, the accessors) is unchanged. The graphql-java completion invariant lives in the
  generated type's javadoc and the generator's class javadoc.
- `OutcomeType` classification record (`model/OutcomeType.java`); carrier, not yet constructed by a
  producer.
- `ErrorChannelWalkerError` sub-seal (`model/ErrorChannelWalkerError.java`) with all four arms,
  added to `Rejection.AuthorError`'s permits, its `typed-rejection.adoc` paragraph, and LSP wiring
  (`Diagnostics.lspCodeOf` forwards the `graphitron.error-channel.*` codes; `severityOf` already
  maps the whole `AuthorError` family to `Error`; `RejectionSeverityCoverageTest` samples added).
- `ErrorChannel.Mapped` arm added under the transitional three-arm sealed root
  (`Mapped | PayloadClass | LocalContext`). Existing exhaustive switches on the root
  (`MappingsConstantNameDedup`, `ErrorMappingsClassGenerator`) handle `Mapped` with live behaviour;
  the emitter switches (`TypeFetcherGenerator.catchArm` / `asyncWrapTail`) throw on the unreachable
  `Mapped` case until the emit seam lands.
- `ChildField.ErrorsField.Transport.WrapperArm` arm; `FetcherEmitter`'s `Transport` switch throws on
  the unreachable `WrapperArm` case until the flip.
- Validator passes `validateOutcomeTypeShape` (single-errors-field → `MultipleErrorsFields`, a pure
  model check, live) and `validateOutcomeChildArmSwitch` (+ `OUTCOME_TYPE_ARM_SWITCHED_DATA_CHANNEL_VARIANTS`
  allow-list). The arm-switch pass is keyed off `Transport.WrapperArm` errors fields, exactly like
  `validateLocalContextErrorsFieldGuards` keys off `Transport.LocalContext`, so it is dormant until
  the flip produces `WrapperArm` fields.

Full pipeline (`mvn install -Plocal-db`) green across all tiers, including the cross-module
sakila-example compile of the generated `Outcome`.

**Commit 2 (additive, unwired) ; landed.** The walker and the two emitters exist and are unit-tested;
nothing calls them yet:

- `ErrorChannelWalker` (`walker/ErrorChannelWalker.java`) resolves `ErrorChannel.Mapped` from a
  classified `OutcomeType`: reads the flattened `@error`-type list off `errorsField().errorTypes()`,
  runs rule 7 / rule 8 (`walker/internal/ChannelRuleChecks`) and accessor coverage
  (`walker/internal/HandlerAccessorCheck`), and stamps the bare `SCREAMING_SNAKE(outcomeTypeName)`
  mappings-constant name. Errors collect across stages into `WalkerResult.Err`; success yields
  `WalkerResult.Ok(Mapped)`. The output-walking analogue of R238's `ServiceMethodCallWalker`.
- The `walker/internal/` checks are typed-arm copies of `FieldBuilder.checkChannelLevelHandlerRules`
  / `checkDuplicateMatchCriteria` / `checkErrorTypeSourceAccessors`: the channel-rule checks return
  the detail string the walker wraps into `ChannelRuleViolation`; the accessor check returns typed
  `HandlerSourceAccessorMissing` arms (and now populates `available` by enumerating the source
  class's public zero-arg accessors). The `FieldBuilder` originals stay live for the `PayloadClass`
  path and DML during the additive window; commit 4 deletes the in-scope copies and rewires DML's
  `detectStructuralDmlErrorChannel` onto the shared `ChannelRuleChecks`.
- `ChannelCatchArmEmitter` (`generators/`) ; the unified sync catch-arm seam over the optional
  carrier: `Optional.empty()` → `redact`; `Mapped` → the inline `(Mapping, cause)` walk returning
  `Outcome.ErrorList`; `LocalContext` → the DML `dispatchToLocalContext` sentinel arm (requires a
  non-null sentinel). `PayloadClass` throws here (it stays on the legacy `dispatchCatchArm` until
  commit 4).
- `ChannelEarlyReturnEmitter` (`generators/`) ; the validator-pre-step early return wrapping the
  violation list in `Outcome.ErrorList`. Channel-agnostic (the wrapper makes the early return
  independent of which `@error` types map), so it consults no carrier.
- Unit tests `ErrorChannelWalkerTest` (6) and `ChannelCatchArmEmitterTest` (5, also covering
  `ChannelEarlyReturnEmitter`) mirror R238's `ServiceMethodCallWalkerTest` /
  `ServiceMethodCallEmitterTest`.

**Substrate revision (In Progress).** The spec sketched `walk(OutcomeType, ClassLoader,
MappingsConstantNameDedup)`. Implementation adjusted the signature to `walk(OutcomeType,
GraphQLSchema, ClassLoader, ReflectTypeResolver)`: the accessor-coverage stage needs the schema (to
read each `@error` type's SDL fields) and a `ReflectTypeResolver` seam (to map those field types
onto reflect types without leaking package-private `BuildContext` into the walker package);
`MappingsConstantNameDedup` is a post-classification cross-field pass with no per-field entry point,
so the walker stamps the bare name and the existing dedup pass rewrites collisions downstream, the
same split the legacy per-field classifier already uses. The async `.exceptionally(...)` tail wiring
deferred from `ChannelCatchArmEmitter` to commit 3 (it lives in `TypeFetcherGenerator.asyncWrapTail`,
which is rewired at the flip); commit 2's emitter covers the sync catch arm only.

**Sequencing revision (In Progress).** The `NonNullableSuccessProjectionField` arm exists in the
seal (so the LSP/doc surface is complete), but its *enforcement* moves from commit 1 to the in-scope
flip (commit 3). Two reasons: a non-null success-projection field is only unsafe under the wrapper
transport (on `PayloadClass` it is at worst the pre-existing latent issue the spec notes), so
enforcing it alongside the flip keeps the rule paired with the transport that makes it load-bearing;
and the success-projection SDL nullability is reachable cleanly only at classify time (where the
`GraphQLFieldDefinition` / `OutcomeType` construction sits), not from the post-classification model
the validator walks. The single-errors-field rule needs only the classified model, so it lands now.

**Commit 3 scope + design decisions (In Progress, from owner review).** The flip is narrowed and
its mechanics pinned by direct owner guidance:

- **`@service` only this flip; `@tableMethod` is scoped out.** In the current model `@tableMethod`
  returns are `TableBound`, so `resolveErrorChannel` already returns `NoChannel` for them
  (`FieldBuilder.java:2035`); they carry no `PayloadClass` to retire. The in-scope set collapses to
  the four root `@service` variants (`buildServiceField`) plus the two child `@service` variants
  (`buildMethodBackedWithChannel`, discriminating `MethodRef.Service` from `TableMethod`). The
  consumer table's `@tableMethod`/`RecordTableMethod` rows defer to a follow-up slice.
- **`Success.value()` is the record the service returns** (a raw jOOQ `XRecord` / `Result` / a custom
  Java record), not a schema-shaped payload class. A service that returns a payload class mirroring
  the GraphQL type is an anti-pattern (the service layer must not adapt to the GraphQL layer); the
  flip neither requires nor preserves it. The child data fields read their column/property off
  `Success.value()` exactly as they read off the bare record today; the win is that the error path
  constructs no payload class.
- **Arm-switch is a generation-time decision, not runtime introspection.** The generator knows
  per-fetcher whether its source is an `Outcome`, so the child emitter is told explicitly (a flag
  threaded from the classifier, e.g. the parent payload's flipped status) and emits the unwrap
  directly. No sibling-scanning at emit time.
- **No `PropertyDataFetcher`.** Every field gets an explicit wired fetcher with full gen-time
  knowledge of what its source is; the flip removes the lone `PropertyDataFetcher.fetching("errors")`
  usage (the `PayloadAccessor` errors transport) in favour of the explicit `WrapperArm` fetcher
  (`source instanceof Outcome.ErrorList<?> errorList -> errorList.errors()`).
- **Generated-code style:** no dunder-prefixed locals (use readable names like `source`, `success`,
  `cause`, `mapping`, `violations`); no ternaries unless a ternary reads better than `if`/`return`.
  Commit 2's `ChannelCatchArmEmitter` loop vars retrofitted accordingly.

The arm-switch for a data child therefore emits an explicit `if`/`return` that narrows the source to
`Outcome.Success<X>`, runs the field's existing read against `success.value()`, and returns null on
the `ErrorList` arm; the errors field's `WrapperArm` fetcher reads `ErrorList.errors()` (empty list on
the `Success` arm). The transport selection (`FieldBuilder.transportForParent`/`selectErrorsTransport`)
flips the errors field of a flipped `@service` payload type from `PayloadAccessor` to `WrapperArm`.

**Arm-switch mechanism settled by principles review (option B).** Commit 3a first prototyped the
arm-switch as a delegation wrapper: narrow to `Success`, rebuild the env with
`DataFetchingEnvironmentImpl.newDataFetchingEnvironment(env).source(value()).build()`, and delegate
to the field's unchanged inner `DataFetcher`. The `principles-architect` review (and the owner)
rejected that: it re-instantiated the inner fetcher per request, asserted env-rebuild transparency on
exactly the nested service/dataloader fetchers least exercised by tests, and hid the read behind an
opaque delegation, against the explicit-fetcher stance ("we wire explicit fetchers with full
generation-time knowledge of what each sees"). The agreed mechanism is the explicit inline read above:
each eligible data child emits its read directly against `success.value()`. The read shapes it must
cover, by parent backing: an accessor call `((Backing) success.value()).getX()` for `@record`-Java-backed
payloads (the `methodCallExpr` / `FieldRead` forms), an inline `((Record) success.value()).get(col)`
for jOOQ-record-backed payloads (the `ColumnFetcher` body, which is just that), and a passthrough of
`success.value()` for `ConstructorField` / `NestingField`. Nested `@service` / `@tableMethod` children
under an outcome type (method-refs to separately generated fetchers) are not inline-readable the same
way; if they occur they are narrowed out of `OUTCOME_TYPE_ARM_SWITCHED_DATA_CHANNEL_VARIANTS` so the
arm-switch validator rejects them with a clear message until a follow-up supports them.

The delegation prototype was removed from `FetcherEmitter` (the `sourceIsOutcome`-set data path now
throws an unreachable marker, like the slice's other additive-window throws), because the inline read
is best built *with* the classifier flip in commit 3b, where the execution-tier round-trip pins it
against the real backing shapes; the `WrapperArm` errors-field emit and the per-type `sourceIsOutcome`
threading (commit 3a) stay, since they are mechanism-independent. The review also produced two fixes
already landed: the `OutcomeClassGenerator` javadoc no longer claims a nullability pin that is not yet
wired, and `FieldBuilder`'s rule-7/8 checks now delegate to `walker/internal/ChannelRuleChecks` so the
two pure rule bodies are not duplicated across the additive window.

**Commit 3b (the atomic `@service` flip) ; landed, all tiers green.** Root `@service` outcome fields
now classify to `ErrorChannel.Mapped` and hand graphql-java a typed `Outcome<X>` source:

- `FieldBuilder.resolveServiceOutcomeChannel` builds an `OutcomeType` and runs the
  `ErrorChannelWalker` to produce `Mapped` (or a typed `ErrorChannelWalkerError` rejection, preserved
  into `UnclassifiedField`); `buildServiceField` routes the four root `@service` variants through it.
  The nullable-success-projection invariant is enforced here at classify time. `@tableMethod` and
  child `@service` are untouched (they classify to `NoChannel`/`PayloadClass` as before).
- `FieldBuilder.transportForParent` flips the errors field of a root-`@service`-produced payload to
  `Transport.WrapperArm`. The signal is "the payload is the unwrapped return type of a root
  `@service` field" read straight off the schema (`isRootServiceProducedPayload`), <em>not</em> the
  `ServiceEmitted` producer binding: that binding only grounds the `@table`-data-field carrier shape
  and is absent for the `@record` scalar payloads in scope (this was the bug that left the first flip
  attempt's child fetchers un-switched).
- `TypeFetcherGenerator.buildServiceFetcherCommon` returns `Outcome<X>` for a `Mapped` field: the
  success path wraps the result in `Outcome.Success`, the catch arm routes through
  `ChannelCatchArmEmitter` (the mapping-walk returning `Outcome.ErrorList`, redact fallthrough), and
  the Jakarta validator pre-step's early return goes through `ChannelEarlyReturnEmitter`. A
  channel-less `@service` field keeps the bare `X` payload and the redact-only catch arm.
- `FetcherEmitter` emits the explicit inline arm-switch read for the in-scope shapes (accessor read
  against `success.value()`, constructor/nesting passthrough), threaded per type by
  `FetcherRegistrationsEmitter.hasWrapperArmErrors`.

The execution-tier round-trip (`GraphQLQueryTest` `filmLookup` / `submitFilmReview` /
`submitSetterShapeFilmReview`, success + mapped-error + happy-path arms) passes unchanged, which is
the load-bearing proof the wire shape is preserved. Pipeline/unit tests that pinned the retired
`@service` `PayloadClass` behaviour were updated: `ErrorChannelClassificationTest`'s positive case
asserts `Mapped` (its two construction-shape cases deleted), `FetcherPipelineTest`'s catch-arm case
asserts the `Outcome` emission (its three payload-factory cases deleted), and
`TypeFetcherGeneratorTest`'s validator-pre-step case carries a `Mapped` channel. Inventory confirmed
no in-scope payload has a non-null success-projection field or a nested `@service`/`@tableMethod`
data child, so neither the nullability rejection nor the unsupported-shape `armSwitchValueExpr` guard
fires on any fixture.

**Commit 4 audit ; the full delete pass is blocked, only one orphan removed.** The plan staged
commit 4 as the deletion of the `PayloadClass` emit + construction machinery "once any remaining
non-flip `PayloadClass` users are accounted for." The accounting is now done, and the answer is that
they are not gone:

- `buildMethodBackedWithChannel` (child `@service` and root + child `@tableMethod` variants) still
  routes through `resolveErrorChannel`, which produces `ErrorChannel.PayloadClass` via
  `resolvePayloadConstructionShape` + `buildErrorChannelCtorArm`/`buildErrorChannelBeanArm`. It is
  wired to ~7 live classify call sites. R244 slice 1 explicitly deferred the child-`@service` and
  `@tableMethod` flips, so these remain live `PayloadClass` users.
- Consequently the `ErrorChannel.PayloadClass` arm, the construction-shape family
  (`PayloadConstructionShape`, `ErrorsSlot`, `DefaultedSlot`, `NonBoundSetter`), `dispatchCatchArm`,
  `payloadFactoryLambda*`, the `catchArm`/`asyncWrapTail` `Mapped`-throws + `PayloadClass` arms, and
  `ErrorRouter.dispatch` all stay. Deleting them would break the deferred paths.

What commit 3b *did* genuinely orphan, and what was removed here: the `declareEarlyPayload*` cluster
in `TypeFetcherGenerator` (`declareEarlyPayloadFromErrors`, `declareEarlyPayloadSetters`,
`declareEarlyPayloadCtor`, `newPayloadFromErrorsCtor`) ; the validator-pre-step early-return payload
construction, replaced by `ChannelEarlyReturnEmitter` and confirmed to have no remaining caller (main
or test). Removed; full pipeline green (63 + 367 tests, execution tier included).

The full `PayloadClass` retirement is therefore correctly a follow-up slice that runs *after* the
child-`@service` and `@tableMethod` flips, not part of R244 slice 1. R244 slice 1 closes with the
`@service` root flip landed and the `PayloadClass` arm intact for the still-deferred field kinds.

## Supersedes

- **R241** (`retire-error-payloadclass-transport`, Spec): discarded. R241's "route through `localContext`" framing was doubly wrong ; a null payload source silently drops errors (verified, see "Why the wrapper, and not localContext"), and the deeper point is that no developer payload class should be constructed or reflected on at all. This slice routes through the typed `Outcome` wrapper instead. The `SlettPoengformelPayload` incident that motivated R241 lands as a non-event ; the generator never reflects on the payload class.
- **R201** (`honor-field-directive-in-payload-construction-shape`, Backlog): moot. The construction-shape machinery R201 targets retires here.
