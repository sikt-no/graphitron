---
id: R87
title: "Tighten instance-service @service design after architect review"
status: Ready
bucket: architecture
priority: 4
theme: service
depends-on: []
---

# Tighten instance-service @service design after architect review

The instance-`@service` parity fix shipped at `4867dc0` ("Support instance-method @service classes via (DSLContext) constructor") restored the legacy generator's behaviour: a developer `@service` method on an instance class with a `public ServiceName(DSLContext)` constructor compiles cleanly again, and so do downstream consumers (Sikt opptak-subgraph and similar) that hand-rolled this pattern. The bug fix itself is sound — `serviceCallTarget` at `TypeFetcherGenerator.java:1251-1255` is the right call-site fork, and the `service-catalog-instance-service-holder-shape` `@LoadBearingClassifierCheck` / `@DependsOnClassifierCheck` pair makes the producer/consumer contract auditable. But the patch landed without a Spec, and a post-hoc principles-architect review surfaced four model-shape tensions, two test-shape tensions, and one documentation tension. This item ships the cleanup.

The architectural smell at the root: the static-vs-instance axis is carried as a `default true` boolean on `MethodRef`, which papers over three distinct populations — a classification axis on `@service` (`true | false`, both reachable), a structural invariant on `@tableMethod` / `@externalField` (must be static; `@externalField` actively enforces this, `@tableMethod` does not but should), and a category mistake on `ConditionFilter` (a `@condition` expression has no Java method to be static-or-not). The fix is to lift the `@service`-only classification onto a sealed sub-type that the rest of `MethodRef` doesn't see at all.

## Implementation

### Phase A — Lift the static-vs-instance axis onto a sealed `CallShape` (§1, §6)

**Model (`graphitron/src/main/java/no/sikt/graphitron/rewrite/model/MethodRef.java`).**

- Introduce `sealed interface CallShape permits Static, InstanceWithDslHolder` as a nested type on `MethodRef`. `Static` is a singleton record (no fields). `InstanceWithDslHolder` carries `boolean needsDslLocal` pre-resolved (the union of "the holder constructor needs `dsl`" — always true for this arm — and "any param has `ParamSource.DslContext`"). For `Static`, `needsDslLocal` is computed by the caller as today (any-param-`DslContext`); the `CallShape` sealed type does not unify the two cases because they project to different emit shapes (`ServiceClass.method(...)` vs `new ServiceClass(dsl).method(...)`), and the union belongs at the call site.
- Drop `MethodRef.isStatic()` (the `default true` accessor at `MethodRef.java:52`). Drop the `boolean isStatic` component from `MethodRef.Basic` at `MethodRef.java:147`. Drop the 5-arg compat constructor at `:160-163` and the 4-arg compat constructor at `:172-174` — see Phase B for the consequence.
- Add a `CallShape callShape()` accessor. Default implementation on the interface: `return new CallShape.Static();`. `Basic` carries `CallShape callShape` as a record component (sixth slot, replacing the dropped `boolean isStatic`); `ConditionFilter` does not override the default. The `default` on the interface is the right shape here because three of four producers (`ConditionFilter`, table-method reflection, external-field reflection, enum-mapping helpers) are structurally always-static — the boolean default papered that over with prose; the `CallShape.Static()` default papers it over with the type system.
- Update `MethodRef.java:32-46` javadoc accordingly: drop the "may produce false" prose on `isStatic`, replace with one paragraph naming `CallShape.Static` as the fast path that every producer except `ServiceCatalog.reflectServiceMethod` returns.

**Catalog (`graphitron/src/main/java/no/sikt/graphitron/rewrite/ServiceCatalog.java`).**

- `reflectServiceMethod` at `:194-199` keeps the `Modifier.isStatic` read but produces a `CallShape` instead of a boolean: `Static` for static methods, `InstanceWithDslHolder(needsDslLocal=true)` for instance methods that pass `checkServiceInstanceHolderShape`. The 6-arg `Basic` constructor call at `:274-275` threads the `CallShape` through. The `@LoadBearingClassifierCheck` description at `:157-162` updates to "the `InstanceWithDslHolder` arm rejects abstract / interface / no-`(DSLContext)`-ctor holders…"; the consumer-side `reliesOn` strings update in lockstep (see Phase D).
- `reflectTableMethod` at `:393-473` is unchanged in observable behaviour; with the compat constructors gone (Phase B) the `Basic` instantiation at `:466-468` threads `new CallShape.Static()` explicitly.

**Consumer (`graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java`).**

- `serviceCallTarget` at `:1251-1255` switches exhaustively on `CallShape` instead of branching on `isStatic()`. The two arms emit the same code shapes as today (`$T` for `Static`, `new $T(dsl)` for `InstanceWithDslHolder`); a future third arm (e.g. a `ServiceHolderFactory`-shaped extension point — see "Out of scope" below) becomes a compile error at this site rather than a silent fall-through.
- `buildServiceFetcherCommon` at `:1195-1197` and `buildServiceRowsMethod` at `:2447-2449` drop the `boolean needsDsl = !method.isStatic() || …` recomputation and read `needsDslLocal` directly off the `CallShape` arm via the same exhaustive switch (the `DslContext` param check folds into the `Static` arm; the `InstanceWithDslHolder` arm always yields `true`). The two sites converge on a single `boolean needsDsl = needsDslLocalFor(method.callShape(), method.params())` helper colocated with `serviceCallTarget`.
- `EnumMappingResolver.java:220-221` keeps threading the upstream `callShape()` through unchanged (it copies whatever the source method had).

**§6 dedup of the duplicated `reliesOn` prose** lands in this phase. `TypeFetcherGenerator.java:1175-1181` (the `buildServiceFetcherCommon` consumer) keeps the full prose; the rows-method consumer at `:2419-2423` shortens to `"Same guarantee as buildServiceFetcherCommon's static-vs-instance fork."` so the rewrite-once-when-it-changes site is unambiguous.

### Phase B — Drop `MethodRef.Basic` compat constructors, force explicit threading (§2)

The 4-arg constructor at `MethodRef.java:172-174` and the 5-arg constructor at `:160-163` exist to keep test fixtures and the table-method reflection path concise. They default the modifier silently — exactly the failure mode that opened this branch (a downstream consumer's instance method silently miscompiled because static was assumed by default).

- Delete both compat constructors. The single canonical `Basic` constructor takes `(className, methodName, returnType, params, declaredExceptions, callShape)` with no defaults.
- All three production call sites in `ServiceCatalog` (`:274-275`, `:466-468`, `:551`) thread the `CallShape` explicitly. `EnumMappingResolver.java:220-221` already passes the upstream method's `callShape()` through; the call shape stays trivial.
- Test fixtures across `MappingsConstantNameDedupTest`, `TypeFetcherGeneratorTest` (~22 sites), `ErrorMappingsClassGeneratorTest`, `ServiceFieldValidationTest`, `TableFieldValidationTest`, `QueryServiceRecordFieldValidationTest`, `MutationServiceTableFieldValidationTest`, `ComputedFieldValidationTest`, `RecordTableFieldValidationTest`, `MutationServiceRecordFieldValidationTest`, `TableMethodFieldValidationTest` migrate via a `TestFixtures.staticMethodRef(...)` factory mirroring R81's `tableRef(...)` factory pattern. The mass change is mechanical; the factory's existence becomes a sentinel that future tests don't accidentally re-introduce a silent default by calling the bare 4-arg form.

### Phase C — Validator-tier coverage for the instance-holder rejection (§4)

The `service-catalog-instance-service-holder-shape` `@LoadBearingClassifierCheck` rejects abstract / interface / no-`(DSLContext)`-ctor holders structurally; `UnclassifiedField` carries the rejection through to `GraphitronSchemaValidator.validateUnclassifiedField` at `:844`. Today the only test that exercises the rejection is `ServiceCatalogTest.reflectServiceMethod_instanceMethodWithoutDslContextCtor_rejectedWithActionableMessage` — a unit-tier classifier test, not a validator-tier reachability test.

- Add a focused validator-tier test `ServiceFieldValidationTest.instanceServiceHolderShape_noCtor_validatorReportsAuthorError` (or sibling). It builds a real schema using `TestInstanceServiceStubNoCtor` as the `@service` class, runs the schema through `GraphitronSchemaBuilder` + `GraphitronSchemaValidator`, and asserts a `ValidationError` whose message names both options ("make the method static, or add the `(DSLContext)` constructor") and quotes the field's qualified name. This proves the rejection arm is reachable from the validator's own dispatch surface, matching the principle "validator mirrors classifier invariants" at `rewrite-design-principles.adoc:103-105`.
- No producer-side change. The `@LoadBearingClassifierCheck` annotation already captures the contract; the new test gives it independent reachability evidence.

### Phase D — Replace body-string assertions with structural / pipeline / compilation tier coverage (§3)

The two new tests at `TypeFetcherGeneratorTest.java:1004-1007` (`mutationServiceRecordField_instanceMethod_emitsNewServiceWithDsl`) and `:1025-1028` (`mutationServiceRecordField_staticMethod_keepsStaticCallShape`) assert literal `code().toString()` substrings — `"new com.example.Service(dsl).doThing("`, `"com.example.Service.doThing("`, `"DSLContext dsl"`. Banned per `rewrite-design-principles.adoc:128` ("Code-string assertions on generated method bodies are banned at every tier; they test implementation, not behaviour, and break on every refactor").

- Retire both tests. Replace with a focused structural test `MethodRefCallShapeTest` at `graphitron/src/test/java/no/sikt/graphitron/rewrite/model/` that asserts: a `Static` `CallShape` produces the bare-class call-target shape via `serviceCallTarget` (return value is the `$T` form of the service class); an `InstanceWithDslHolder` `CallShape` produces the `new $T(dsl)` form; the exhaustive switch covers both and a hypothetical third arm would be a compile error. Assert via `CodeBlock` structural equality (using `equals` on the rendered `CodeBlock`, not `toString`-substring).
- Add an instance-`@service` fixture to `graphitron-sakila-example` so the original Sikt regression is caught at the compilation tier. The fixture: a `*Service` class on the example's package with a `(DSLContext ctx)` constructor and one instance method bound by an `@service` directive in the example's SDL (extends an existing query, not a brand-new field, so the addition stays minimal). The compile-tier check at `graphitron-sakila-example` already runs under `mvn install -Plocal-db` per `rewrite-design-principles.adoc:136-140`; this fixture leans on it. If the emitter regresses to "always emit static", `javac` rejects `new ServiceClass(dsl).method(...)` against the absent static method, surfacing the bug at the compile boundary rather than a body-string mismatch.

### Phase E — Documentation: anchor the legacy holder shape as the chosen recipe (§5, §7)

§5 of the architect review framed the per-call `new Service(dsl)` emission as a runtime-extension-point question: option (a) accept the legacy shape and document it; option (b) carve out a `ServiceHolderFactory` extension point. The Spec call is **(a)**. Rationale, in order of weight:

1. *Stability through simplicity* (`graphitron-codegen-parent/docs/graphitron-principles.adoc:23`). The legacy shape works in production today across multiple Sikt consumers; introducing a factory adds an extension surface where none was needed.
2. *Schema directives carry business semantics* (`runtime-extension-points.adoc:167-170`). Consumers who need different holder construction can already swap by changing the `@service` class binding in SDL — the indirection is via the schema, not via a runtime hook.
3. *Cross-cutting jOOQ concerns belong on `Configuration`* (`runtime-extension-points.adoc:158-181`). DI and shared state across services compose with the existing `getDslContext` extension point: an instance method that needs more than `DSLContext` should become static and read from a tenant-scoped `DSLContext`'s `Configuration` or from `getContextArgument`.

So:

- Append one paragraph under `runtime-extension-points.adoc`'s "Complementary Technologies" section (between "Where each concern belongs" and "jOOQ Configuration") titled "Instance `@service` holders": "instance `@service` classes are constructed per call via `new ClassName(DSLContext)`. The holder is created fresh per fetcher invocation; do not stash request-scoped state on instance fields. For DI, shared state, or per-tenant decisions, make the method static and inject via the `DSLContext` (`Configuration` for cross-cutting concerns; `getContextArgument` for per-request values)." Keep it ~5 sentences; this is documentation of an existing recipe, not a new contract.
- Append one line to `graphitron-rewrite/roadmap/changelog.md` for §7: "R87 (`4867dc0` + this item's landing commit): `@service` directives now classify instance methods on `(DSLContext)` holders, restoring legacy parity. The static/instance fork lives on `MethodRef.CallShape`; emitter dispatches via `serviceCallTarget`. Out-of-band: `ServiceHolderFactory` extension point not added — see runtime-extension-points.adoc."

## Tests

- **Unit tier on `CallShape`.** `MethodRefCallShapeTest` (new): asserts the sealed `Static` / `InstanceWithDslHolder` arms classify under `ServiceCatalog.reflectServiceMethod` for the three populations (`TestServiceStub` static; `TestInstanceServiceStub` instance with ctor; `TestInstanceServiceStubNoCtor` rejected). Replaces the two retired body-string tests. Asserts via `CodeBlock` structural equality on `serviceCallTarget`'s output.
- **Unit tier on `serviceCallTarget`.** A focused test asserts both `CallShape` arms render the expected `CodeBlock` shape, and that the exhaustive switch is closed (a sealed-switch refactor that drops one arm becomes a compile error, not a runtime fall-through).
- **Validator tier.** `ServiceFieldValidationTest.instanceServiceHolderShape_noCtor_validatorReportsAuthorError` (new, Phase C): drives a real schema bound to `TestInstanceServiceStubNoCtor`, asserts the validator emits one `ValidationError` whose message names both fix options.
- **Pipeline tier.** Existing pipeline tests already cover the static-call emission shape; add one pipeline test driving an instance-`@service` field through `GraphitronSchemaBuilder` to a generated `TypeSpec`, asserting on the resulting fetcher method's signature (return type, parameter list) and the `MethodRef.callShape()` arm of the model — *not* on emitted body strings. Lives at `TypeFetcherGeneratorTest` alongside the existing service-fetcher pipeline tests.
- **Compilation tier.** New instance-`@service` fixture in `graphitron-sakila-example` (Phase D): one `*Service` class with a `(DSLContext)` ctor, one instance method bound by an SDL `@service` directive. The existing `mvn install -Plocal-db` flow compiles the generated source against real jOOQ, catching emitter regressions in the form `javac` rejects.
- **Mass migration verified by existing suites.** Phase B's compat-constructor removal is verified by every test in the `MethodRef.Basic` call-site list above continuing to pass after migration to `TestFixtures.staticMethodRef(...)`. No new assertions land alongside the migration.

## Suggested execution order

Phase A is the architectural core (`CallShape` lift). Phases B and D are concrete and cheap; B can ship in the same slice as A if the fixture migration is mechanical, otherwise B follows in a fresh commit. Phase C is a one-test add and can land before, with, or after A. Phase E is a docs-only commit and lands last so the changelog entry can cite the closing commit SHA.

## Out of scope

- **Carving a `ServiceHolderFactory` runtime extension point (§5 option b).** Rejected per Phase E: the existing `getDslContext` + SDL-level class binding cover the surface; introducing a factory inverts the "stability through simplicity" principle. If a real consumer hits a case the legacy shape doesn't handle (a per-tenant holder where switching to static + `getContextArgument` doesn't suffice), they file it as a fresh roadmap item with the consumer pattern attached.
- **Widening `(DSLContext)` to other holder constructor shapes (no-arg, multi-arg, builder).** The single-`DSLContext`-ctor contract mirrors the legacy generator exactly; widening would need its own design pass and a real consumer asking for it.
- **Lifting the `@tableMethod` static enforcement to a real check.** `reflectTableMethod` at `ServiceCatalog.java:393-473` does not read `Modifier.isStatic` today (tension #2 footnote). Phase B's removal of the compat constructor forces explicit `CallShape` threading, which folds in the absence-of-check by making `Static` the only arm a `@tableMethod` reflection produces. Adding a positive `Modifier.isStatic` check + rejection arm is its own follow-up if instance `@tableMethod` becomes a real failure mode (the existing `@externalField` check at `:513` is the template).
- **Lifting `MethodRef` itself to a sealed hierarchy.** The interface stays open; only the static/instance axis lifts onto the `CallShape` sub-taxonomy. This item is about narrowing one accessor's semantics, not refactoring the interface.

## Success criteria

- `MethodRef.isStatic()` accessor and the `boolean isStatic` component on `Basic` are gone; `CallShape` is the only carrier of the static/instance distinction. `git grep "isStatic"` under `graphitron-rewrite/graphitron/src/main` returns only the `Modifier.isStatic` reflection-API reads in `ServiceCatalog`.
- `MethodRef.Basic` exposes one canonical 6-arg constructor; the 4-arg and 5-arg compat constructors are deleted.
- `serviceCallTarget` switches exhaustively on `CallShape`; the two `needsDsl` recomputation sites at `TypeFetcherGenerator.java:1195-1197` and `:2447-2449` route through the same helper.
- `TypeFetcherGenerator.java:1175-1181` and `:2419-2423`'s `reliesOn` prose no longer duplicates the same paragraph.
- The two body-string tests at `TypeFetcherGeneratorTest.java:1004-1007` and `:1025-1028` are retired; replacement structural / pipeline / compilation-tier coverage is in place.
- One new validator-tier test exercises the instance-holder rejection arm through the validator's own surface.
- `runtime-extension-points.adoc` carries the new "Instance `@service` holders" paragraph; `graphitron-rewrite/roadmap/changelog.md` carries the R87 line.
- `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` passes 10/10 modules, including `graphitron-sakila-example`'s compilation against the new instance-`@service` fixture.

Principles cited: *Sealed over enum-style accessors with per-arm semantics*; *Narrow component types over broad interfaces*; *Validator mirrors classifier invariants*; *Code-string assertions on generated method bodies are banned*; *Stability through simplicity*; *Generation-thinking* (one classification, one carrier).
