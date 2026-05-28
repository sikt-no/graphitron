---
id: R244
title: ErrorChannel walker carrier (R222 Stage 2 slice on @service + @tableMethod)
status: Spec
bucket: structural
depends-on: []
created: 2026-05-26
last-updated: 2026-05-28
---

# ErrorChannel walker carrier (R222 Stage 2 slice on @service + @tableMethod)

R222 Stage 2 slice on the output-walking surface, scoped to `WithErrorChannel` implementers backed by `@service` or `@tableMethod` (everything that currently classifies through `FieldBuilder.resolveErrorChannel` and emits an `ErrorChannel.PayloadClass` arm). R238 set the conventions for input-walking carriers (`MethodCall` on `MethodBackedField`); this slice mirrors the pattern on the output side, walking the SDL payload type rather than the field's arguments. The slice ships:

- A reduced `ErrorChannel` carrier — sealed `Mapped | NoChannel` two-arm — replacing the existing `PayloadClass | LocalContext` sealed split. `Mapped` carries `(List<ErrorType> mappedErrorTypes, String mappingsConstantName)` and nothing else. The construction-shape branches (`payloadClass`, `errorsSlot`, `defaultedSlots`) and the entire `PayloadConstructionShape` family retire.
- A single producer (`ErrorChannelWalker`) that reduces the SDL output payload type: detect the errors-shaped field, identify mapped `@error` types, run channel-level rule checks (today's rule 7 multi-VALIDATION + rule 8 duplicate match-criteria), reflect on each handler's source class for per-`@error`-type accessor coverage, derive the mappings-constant name. Substrate: payload `GraphQLObjectType` plus the codegen classloader. Subsumes `BuildContext.detectErrorsFieldShape`, `FieldBuilder.resolveErrorChannel`, `checkChannelLevelHandlerRules`, `checkDuplicateMatchCriteria`, `checkErrorTypeSourceAccessors`, and the dedup call into `MappingsConstantNameDedup`.
- Catch-arm emitter rewrite for the in-scope `WithErrorChannel` implementers. The shape: inline the `(Mapping, cause)` match loop at every catch site; on match emit `return DataFetcherResult.<P>newResult().data(null).localContext(List.of(t)).build();`. The genericising `ErrorRouter.dispatch` and per-fetcher `payloadFactory` lambda retire. `Mapping.match(Throwable)` and `ErrorRouter.redact(Throwable, env)` survive as the narrow runtime primitives.
- Validator pre-step rewrite: `declareEarlyPayloadFromErrors` collapses to `return DataFetcherResult.<P>newResult().data(null).localContext(__violations).build();`. The `declareEarlyPayload*` helper family retires.
- Async tail (`asyncWrapTail`) rewrites to the same shape on the `.exceptionally(...)` path.
- Per-field null-source guards on data-channel `ChildField` variants that can sibling an `ErrorsField` *under an `@service` / `@tableMethod` payload*: `PropertyField`, `RecordField`, `ServiceRecordField`, `ServiceTableField`, `TableMethodField`, `RecordTableMethodField`, `ConstructorField`, `NestingField`, `ComputedField`. Many short-circuit naturally via graphql-java's `PropertyDataFetcher`; the audit confirms and adds explicit `if (source == null) return null;` where missing.
- `ChildField.ErrorsField.Transport.PayloadAccessor` arm retires; errors universally read via `env.getLocalContext()` for in-scope fields. DML's `Transport.LocalContext` arm survives outside scope.
- R201 (`honor-field-directive-in-payload-construction-shape`) retires as moot.
- R241 (`retire-error-payloadclass-transport`) supersedes-by this item; the umbrella discards. R241's framing as "retire transport variant + route through LocalContext" was the wrong shape per R222's dimensional-slot principle; R244 is the same direction reframed correctly.

The DML carriers (`MutationDmlRecordField`, `MutationBulkDmlRecordField`) stay on their existing `ErrorChannel.LocalContext` sentinel-based shape. Their migration onto the same inline emit pattern is a sibling Stage 2 slice filed at this item's In Progress mark. That follow-on extends the null-source-guard sweep across `SingleRecord*` data-channel variants, lifts the catch-arm rewrite to those fetchers, and retires `ErrorRouter.dispatchToLocalContext`, the sentinel emitters (`singleRecordSentinelFor`, `bulkRecordSentinelFor`), `LOCAL_CONTEXT_GUARDED_DATA_CHANNEL_VARIANTS`, and `GraphitronSchemaValidator.validateLocalContextErrorsFieldGuards`.

## Target emitted code

The reducer backtracks from this shape. Every `@service` or `@tableMethod` fetcher's catch arm reads as a literal mapping-walk followed by either the inline match-return or the redact fallback:

```java
// QueryServiceRecordField example, post-slice
public static DataFetcherResult<SakPayload> sak(DataFetchingEnvironment env) {
    try {
        SakPayload result = SakService.run(...);
        return DataFetcherResult.<SakPayload>newResult().data(result).build();
    } catch (Throwable e) {
        for (Mapping m : ErrorMappings.SAK_PAYLOAD) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (m.match(t)) {
                    return DataFetcherResult.<SakPayload>newResult()
                        .data(null)
                        .localContext(List.of(t))
                        .build();
                }
            }
        }
        return ErrorRouter.redact(e, env);
    }
}
```

The two-deep `for` is emitted inline at every catch site. graphql-java's child fetchers for the SDL payload's data fields read `env.getSource() == null` and return null via `PropertyDataFetcher`'s natural null-source behaviour or graphitron's `@record`-accessor lambda. The SDL payload's `errors` field's child fetcher reads `env.getLocalContext()` and returns the matched throwable list, surfaced through per-`@error`-type field DataFetchers as today.

Validator pre-step (Jakarta-violation early return) has the same shape:

```java
// inside buildServiceFetcherCommon, pre-step
if (!__violations.isEmpty()) {
    return DataFetcherResult.<SakPayload>newResult()
        .data(null)
        .localContext(__violations)
        .build();
}
```

No `__earlyPayload` local, no payload-class construction.

Async tail (`.exceptionally(...)` on `CompletableFuture<P>`-shaped child fetchers) lifts the body into a lambda over the throwable, otherwise identical.

## Slot landing on `WithErrorChannel`

The slot's home is the existing `WithErrorChannel` interface, which already names "fetcher-emitting field that may carry a typed-error channel." The slice replaces the interface's single accessor:

```java
// graphitron/src/main/java/no/sikt/graphitron/rewrite/model/WithErrorChannel.java
public interface WithErrorChannel {
    ErrorChannel errorChannel();   // replaces Optional<ErrorChannel>
}
```

The carrier is sealed `Mapped | NoChannel`; consumers pattern-match exhaustively and always get a populated arm. R222's "no Optional slots" applies: the slot is universal across `WithErrorChannel` implementers, presence is payload-shape-gated rather than directive-gated on the implementer, so the `No<Family>` arm is the right encoding for absence (rather than interface non-membership, which R238 uses for directive-gated cases).

**DML temporary split.** DML carriers (`MutationField.MutationDmlRecordField`, `MutationField.MutationBulkDmlRecordField`) currently implement `WithErrorChannel` and populate the legacy `LocalContext` arm. In this slice's scope they keep their behaviour, but they no longer fit the reduced `Mapped | NoChannel` shape. The pragmatic resolution: split off a sibling `WithDmlErrorTransport` interface carrying the legacy sentinel-based transport (a new `DmlErrorTransport` record, lifted verbatim from the existing `ErrorChannel.LocalContext`). DML field permits move from `WithErrorChannel` to `WithDmlErrorTransport`. The split is acknowledged as temporary; the DML-absorption follow-on re-unifies them under `WithErrorChannel` when the DML catch-arm rewrites to the inline shape.

## Carrier shape

```java
// graphitron/src/main/java/no/sikt/graphitron/rewrite/model/ErrorChannel.java
public sealed interface ErrorChannel permits ErrorChannel.Mapped, ErrorChannel.NoChannel {

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

    record NoChannel() implements ErrorChannel {}
}
```

The carrier dissolves `ErrorChannel.PayloadClass` entirely (with its `payloadClass`, `errorsSlot`, `defaultedSlots` components) and absorbs the meaningful payload of `ErrorChannel.LocalContext` into `Mapped`. The existing `ErrorChannel.LocalContext` record moves to a new top-level type `DmlErrorTransport` outside the sealed root, with identical fields. The shared accessors that today live on the sealed `ErrorChannel` root (`mappedErrorTypes`, `mappingsConstantName`) retire — consumers pattern-match on the carrier and read off the `Mapped` arm directly.

## Producer (`ErrorChannelWalker`)

```java
// graphitron/src/main/java/no/sikt/graphitron/rewrite/walker/ErrorChannelWalker.java
public final class ErrorChannelWalker {
    public WalkerResult<ErrorChannel> walk(
        GraphQLObjectType payloadType,
        ClassLoader codegenLoader,
        MappingsConstantNameDedup dedup
    );
}
```

**Substrate is the SDL output payload type directly**, paired with the codegen classloader for handler-class reflection and the build-scoped name-dedup helper. This is the output-walking analogue of R238's `ServiceMethodCallWalker` (`graphitron/src/main/java/no/sikt/graphitron/rewrite/walker/ServiceMethodCallWalker.java`): same producer-as-thin-layer-over-graphql-java pattern, different SDL surface.

R238's shipped walker took a pragmatic substrate concession — it translates from an upstream resolved `MethodRef.Service` rather than reflecting directly on SDL+classloader, because today's `ServiceCatalog.reflectServiceMethod` is 1258 LOC of battle-tested reflection that a translator-walker avoids duplicating. R244 does **not** take that concession: the @error-channel walk has no comparably large intermediate to translate from (today's `FieldBuilder.resolveErrorChannel` is ~200 LOC inlined into the classifier), so the direct-substrate shape is achievable in scope. The walker absorbs `resolveErrorChannel`, `checkChannelLevelHandlerRules`, `checkDuplicateMatchCriteria`, `checkErrorTypeSourceAccessors`, and `BuildContext.detectErrorsFieldShape` verbatim under `walker/internal/`.

**Walker stages** (one pass per `WithErrorChannel` field's payload type):

1. **Find the errors-shaped field on the payload.** Walk `payloadType.getFieldDefinitions()` in source order; the first field whose shape matches the "polymorphic list/union/interface of `@error` types" predicate is the errors carrier. Subsumes `BuildContext.detectErrorsFieldShape` and the `liftToErrorsField` lift rules. Absence: emit `Ok(NoChannel)`; the fetcher's catch arm routes through `ErrorRouter.redact(e, env)`.
2. **Identify mapped `@error` types.** Extract the `@error` types from the field's polymorphic shape (list element, union members, interface implementations). Non-empty by structural rule; one-element for `[SomeError]`, multi-element for unions / interfaces.
3. **Run channel-level rules.** Rule 7 (no two VALIDATION handlers in one channel), rule 8 (no duplicate match-criteria across the flattened handler list). Failure: typed `ErrorChannelWalkerError.ChannelRuleViolation(payloadTypeName, errorsFieldName, ruleNumber, detail)` arm.
4. **Reflect on handler source-classes for accessor coverage.** Per (channel, `@error` type, handler), walk the `@error` type's declared SDL fields and verify the handler's source class exposes a `PropertyDataFetcher`-visible accessor. `path` and `message` are exempt (populated by per-`@error`-type synthesised DataFetchers). Subsumes `checkErrorTypeSourceAccessors`. Failure: typed `ErrorChannelWalkerError.HandlerSourceAccessorMissing(...)` arm.
5. **Resolve the mappings-constant name.** Use `MappingsConstantNameDedup`: derive from `SCREAMING_SNAKE(payloadSdlName)`; on collision, append the 8-hex SHA-256 suffix per the existing dedup rules. The name is build-scoped, so the walker takes the dedup helper as a constructor arg.
6. **Emit result.** `Ok(Mapped(types, constName), [])` on success; `Ok(NoChannel(), [])` when no errors-shaped field found; `Err(authorErrors, diagnostics)` on any structural failure. `Err` collects across stages; the walker doesn't short-circuit at the first failure.

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

**Shared emitter.** A new utility encapsulates the catch-arm body emission, parameterised on the carrier:

```java
// graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/ChannelCatchArmEmitter.java
public final class ChannelCatchArmEmitter {
    public static CodeBlock emit(ErrorChannel channel, TypeName payloadType, String outputPackage);
}
```

Dispatches on the sealed carrier exhaustively:

- `Mapped` arm: emit the inline mapping-walk loop + match-return + `redact` fallback shown in the target-code section.
- `NoChannel` arm: emit `return ErrorRouter.redact(e, env);` (the redact-only catch arm).

`asyncWrapTail` consumes the same emitter through a small wrapping context that lifts the result into the `.exceptionally(...)` lambda. The validator pre-step (`emitJakartaValidatorPreStep`) consumes a sibling helper `ChannelEarlyReturnEmitter.emit(channel, payloadType, violationsLocal)` that emits the same shape for the violations-list early return.

`TypeFetcherGenerator.catchArm`, `dispatchCatchArm`, `payloadFactoryLambda`, `payloadFactoryLambdaCtor`, `payloadFactoryLambdaSetters`, `declareEarlyPayloadFromErrors`, `declareEarlyPayloadCtor`, `declareEarlyPayloadSetters` retire at this seam.

## Cutover sequencing (additive, then destructive)

R238's actual landing sequence is the precedent: ship the new shape additively first, cut consumers over while both shapes coexist, then retire the legacy slot in a final commit. R244 follows the same pattern to keep each commit reviewable and to bound the dual-implementation window to a few commits rather than the lifetime of a feature branch.

Commit-level sequencing (subject to revision at In Progress time; the load-bearing claim is the *shape* of the sequence, not the exact commit count):

1. **Add the new types.** `ErrorChannel.Mapped`, `ErrorChannel.NoChannel`, `ErrorChannelWalkerError` sibling sub-seal, `DmlErrorTransport` record (lifted from `ErrorChannel.LocalContext`), `WithDmlErrorTransport` sibling interface. The existing `ErrorChannel.PayloadClass | LocalContext` sealed split stays intact at this commit — `Mapped | NoChannel` is added under the same `ErrorChannel` sealed root with widened permits.
2. **Add the walker and emitter.** `ErrorChannelWalker`, `ChannelCatchArmEmitter`, `ChannelEarlyReturnEmitter`, with unit-tier tests. Walker is unwired at this commit; nothing reads its output yet.
3. **Additive cutover.** Each in-scope `WithErrorChannel` implementer record (the 9 from the consumer migration table) gains a `ErrorChannel.Mapped` / `NoChannel` slot alongside the existing `Optional<ErrorChannel> errorChannel()` slot. `FieldBuilder` populates both at construction. The interface gains a new accessor (e.g. `errorChannelV2()`) returning the new shape; the legacy `errorChannel()` stays alive. DML implementers gain `WithDmlErrorTransport` membership while keeping their legacy `Optional<ErrorChannel>` accessor populated with `LocalContext`. Consumer code (`TypeFetcherGenerator`, `MappingsConstantNameDedup`, `CatalogBuilder`) still reads the legacy shape.
4. **Cut consumers over to the new slot.** `TypeFetcherGenerator.buildServiceFetcherCommon` (and the eight other entry points) switch to `ChannelCatchArmEmitter.emit(field.errorChannelV2(), ...)`. `asyncWrapTail` and the validator pre-step switch in the same commit. `MappingsConstantNameDedup` reads `Mapped.mappingsConstantName()` via the new accessor. `CatalogBuilder.errorChannelName` reads the new shape. Errors-field transport rewires to `LocalContext` for in-scope variants (the `ChildField.ErrorsField.Transport.PayloadAccessor` arm becomes dead code at this commit but is not yet deleted).
5. **Retire legacy.** `ErrorChannel.PayloadClass` arm deletes. The interface's legacy `Optional<ErrorChannel> errorChannel()` deletes, and the new accessor renames to the canonical name (`errorChannel()` returning non-Optional `ErrorChannel`). `ErrorsSlot`, `DefaultedSlot`, `NonBoundSetter`, `PayloadConstructionShape` family delete. `TypeFetcherGenerator`'s payload-factory-lambda emitters and `declareEarlyPayload*` family delete. `ChildField.ErrorsField.Transport.PayloadAccessor` arm deletes; `Transport` collapses to single-arm. `FieldBuilder.resolveErrorChannel` deletes (the walker absorbs it). `MappingsConstantNameDedup.withResolvedChannel` rebuilds the 9 permits with the new component shape.

Step 3 is where the dual-implementation window opens; step 5 closes it. The window is bounded to a single PR or a short PR sequence, not a feature branch. R238 ran the equivalent window from `f90a2f3` (additive cutover) through `e6b6c1c` (retire legacy slot) — five commits, all on trunk in linear order.

`WithDmlErrorTransport` survives step 5 by design: DML implementers keep populating it through the same classifier callsite they use today. The DML follow-on slice (filed as a Backlog item at this slice's In Progress mark) re-unifies DML into `WithErrorChannel` returning `Mapped | NoChannel`, retires `WithDmlErrorTransport`, and deletes the DML transport machinery (`ErrorRouter.dispatchToLocalContext`, sentinel emitters, `LOCAL_CONTEXT_GUARDED_DATA_CHANNEL_VARIANTS`, `validateLocalContextErrorsFieldGuards`).

## Per-field null-source guard sweep

The catch arm emits `data(null)`, so each in-scope payload's data-channel child fetcher must short-circuit on null source. graphql-java's `PropertyDataFetcher.fetching(name)` returns null on null source naturally; graphitron's `@record`-accessor lambdas under `FetcherEmitter.propertyOrRecordValue` likewise. The audit confirms the gap and adds explicit `if (source == null) return null;` to the variants below where missing:

- `ChildField.PropertyField`, `ChildField.RecordField`: read through `PropertyDataFetcher` or `@record`-accessor; usually safe but audit confirms.
- `ChildField.ServiceRecordField`, `ChildField.ServiceTableField`: child `@service` fetchers; need explicit guard so the service method isn't called with a null source row.
- `ChildField.TableMethodField`, `ChildField.RecordTableMethodField`: child `@tableMethod` fetchers; same.
- `ChildField.ConstructorField`, `ChildField.NestingField`: `env.getSource()` passthrough; null-source already returns null implicitly.
- `ChildField.ComputedField`: accessor call on source; needs explicit guard.

Variants outside scope (DML `SingleRecord*` family) are unchanged; they retain their existing sentinel-based behaviour until the DML-absorption follow-on.

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

`Rejection.AuthorError.Structural(String reason)` is untouched: existing non-R244 callsites continue producing it. R244 introduces no new `Structural` callsites and no `Other` catch-all under the new sub-seal — every channel-rejection path in scope maps to a typed arm.

## What retires

Model:

- `ErrorChannel.PayloadClass` arm (whole record).
- `ErrorsSlot`, `DefaultedSlot`, `NonBoundSetter` (records + their files).
- `PayloadConstructionShape` sealed family + `AllFieldsCtor`, `MutableBean`, `SetterBinding`.
- `ChildField.ErrorsField.Transport.PayloadAccessor` arm (only `LocalContext` survives, only for DML).
- `WithErrorChannel`'s `Optional<ErrorChannel>` accessor.
- `ErrorChannel`'s shared `mappedErrorTypes()` / `mappingsConstantName()` accessors on the sealed root.

Classifier:

- `FieldBuilder.resolvePayloadConstructionShape` + `tryMutableBean` + helpers (`formatCtorSignatures`, `javaBeanSetterName`).
- `FieldBuilder.resolveErrorChannel` + `buildErrorChannelCtorArm` + `buildErrorChannelBeanArm` + `collectDefaultedSlots` + `collectNonBoundSetters` + `defaultLiteralFor`.
- `FieldBuilder.checkChannelLevelHandlerRules`, `checkDuplicateMatchCriteria`, `checkErrorTypeSourceAccessors` (absorbed into the walker).
- `BuildContext.detectErrorsFieldShape` (moves into the walker as an internal helper).

Emitter:

- `TypeFetcherGenerator.payloadFactoryLambda`, `payloadFactoryLambdaCtor`, `payloadFactoryLambdaSetters`.
- `TypeFetcherGenerator.declareEarlyPayloadFromErrors`, `declareEarlyPayloadCtor`, `declareEarlyPayloadSetters`.
- `TypeFetcherGenerator.dispatchCatchArm` (replaced by `ChannelCatchArmEmitter.emit`).
- `TypeFetcherGenerator.catchArm` overload split (one shape now; ctor-arm specifics gone).
- `ErrorRouter.dispatch` (the generic per-fetcher payload-factory router). `Mapping.match(Throwable)` survives; `redact(Throwable, env)` survives.

Audit annotations:

- `@LoadBearingClassifierCheck(key = "payload-construction.*")` producer annotations + their `@DependsOnClassifierCheck` consumer pairs.

Tests:

- `PayloadConstructionShapeTest` (7 cases) deletes.
- `FetcherPipelineTest`'s R154 cases (`serviceMutation_setterShapePayload_emitsSetterFactory`, `_allFieldsCtorPayload_emitsCtorFactory_unchanged`, `_bothShapesPresent_prefersCtorFactory`, `dmlMutation_setterShapePayload_emitsSetterFactory`): rewrite around the new emission shape or delete (some become irrelevant under inline emission).
- `ErrorRouterClassGeneratorTest`'s tests for the retired `dispatch` method delete; `redact` and `Mapping.match` tests survive.
- `ErrorChannelClassificationTest`'s positive cases rewrite to assert on `WalkerResult.Ok(ErrorChannel.Mapped)`; rejection cases rewrite to assert on the typed `ErrorChannelWalkerError.*` sub-arms via `WalkerResult.Err`.
- New `ErrorChannelWalkerTest` (unit tier) and `ChannelCatchArmEmitterTest` (unit tier) mirror R238's `ServiceMethodCallWalkerTest` / `ServiceMethodCallEmitterTest` structure (at `graphitron/src/test/java/no/sikt/graphitron/rewrite/walker/` and `generators/`): one positive arm per `Mapped` shape, one per `ErrorChannelWalkerError.*` rejection, one for `NoChannel`.
- Execute-tier `GraphQLQueryTest` cases for the catch-arm round-trip continue to pass without changes (the architectural shift is structurally invariant on observable behaviour).

Documentation:

- `error-handling-parity.md` sections describing the PayloadClass transport and the construction-shape escape hatches retire. New section pins the contract: "After R244, all `@service` / `@tableMethod` payload error transport is the inline `data(null).localContext(...)` shape; the generator never constructs the developer's payload class on the error path."
- Javadocs across `TypeFetcherGenerator`, `FieldBuilder`, `WithErrorChannel`, `ChildField.ErrorsField`, `ErrorRouterClassGenerator` update to reflect the single-arm carrier and inline catch-arm shape.

## Tests

Three tiers, mirroring R238:

- **Unit (`@UnitTier`)**: `ErrorChannelWalkerTest` (~10 cases: one per `Mapped` source shape — single `@error`, union, interface, list; one per `ErrorChannelWalkerError.*` arm — rule 7 / rule 8 / handler-accessor-missing; `NoChannel` when payload has no errors-shaped field) and `ChannelCatchArmEmitterTest` (3 cases: `Mapped` body shape, `NoChannel` body shape, async-tail lambda wrapping).
- **Pipeline (`@PipelineTier`)**: extend `ErrorChannelClassificationTest` with assertions on `walkerDiagnostics` instead of `Rejection.structural` projection for channel-rejection failures. Existing positive-witness tests keep their author-facing wording but assert against typed `ErrorChannelWalkerError.*` arms. Pipeline cases asserting fetcher body-string content fail under the new emission shape (inline mapping-walk vs the old `ErrorRouter.dispatch(...)` single-statement call); per `rewrite-design-principles.adoc`'s ban on code-string assertions, those assertions retire here and get replaced by structural assertions on the `ErrorChannel.Mapped` carrier.
- **Compilation / Execution (`@CompilationTier` / `@ExecutionTier`)**: `graphitron-sakila-example` regression net. The migration is structurally invariant on observable response shape (`{film: null, errors: [...]}` vs the old constructed-payload `{film: null, errors: [...]}` are identical at the GraphQL wire), so `mvn install -Plocal-db` end-to-end-green is the safety net.

## Out of scope

- DML migration (`MutationDmlRecordField`, `MutationBulkDmlRecordField`): files separately at this item's In Progress mark. Scope: extend null-source guards across `SingleRecord*`, rewrite the DML catch arm to the inline shape, retire `dispatchToLocalContext` + `singleRecordSentinelFor` + `bulkRecordSentinelFor` + `LOCAL_CONTEXT_GUARDED_DATA_CHANNEL_VARIANTS` + `validateLocalContextErrorsFieldGuards` + `DmlErrorTransport` (re-unify with `WithErrorChannel`).
- `@condition`-bound paths: not in `WithErrorChannel` (they don't emit fetchers).
- Universal `UnclassifiedField` retirement: R222 Stage 4. This slice retires `UnclassifiedField` for the error-channel rejection paths only; broader retirement is per other carrier slices.
- The `DataFetcherBuilder` dimensional slot composition: R222 Stage 3. This slice ships the walker carrier and the shared emitter, not the dimensional slot that would compose `MethodCall` × `ErrorChannel` × … into one emit-ready form.
- `Rejection.AuthorError.Structural(String reason)` callsites outside the in-scope rejection paths: untouched. R244 introduces no new `Structural` callsites; channel-rejection paths in scope each map to a typed `ErrorChannelWalkerError` arm.

## Supersedes

- **R241** (`retire-error-payloadclass-transport`, Spec): discarded. R241's framing ("retire transport variant, route through LocalContext") was the wrong shape per R222's dimensional-slot principle — no transport carrier should survive at all. R244 reframes the same direction as a Stage 2 walker-carrier slice. The `SlettPoengformelPayload` incident that motivated R241 lands as a non-event after R244 — the generator never reflects on the payload class.
- **R201** (`honor-field-directive-in-payload-construction-shape`, Backlog): moot. The construction-shape machinery R201 targets retires here.

## R222 umbrella drift

R238's spec already flagged that R222's destination needs updating: R222 originally named `MethodBackedField` as the slot home with `ServiceField extends MethodBackedField` as a pure marker, but R238 shipped `ServiceField` as a **sibling** of `MethodBackedField` because the carrier is service-specific and broader promotion would force no-op slots on six non-scope implementers. R244 introduces a parallel drift: `WithDmlErrorTransport` lives as a sibling of `WithErrorChannel` during the transition, even though R222's destination unifies everything under one carrier-bearing interface. Both drifts are transitional surface area justified by the same scope-bounding principle ("don't promote a slot to a broader interface until every implementer has its carrier ready"), and the umbrella note for R222 should absorb the revised pattern when next revisited. Neither drift is load-bearing on R244's correctness; both are visible in the In Progress diff.
