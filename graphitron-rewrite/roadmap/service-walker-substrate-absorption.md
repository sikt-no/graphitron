---
id: R256
title: "Absorb the service walker substrate: typed per-arm errors + multi-arg ctors"
status: Spec
bucket: structural
priority: 3
theme: structural-refactor
depends-on: []
created: 2026-05-29
last-updated: 2026-05-29
---

# Absorb the service walker substrate: typed per-arm errors + multi-arg ctors

R238 landed the `ServiceMethodCall` walker carrier across the four root sync `@service` permits, but shipped its walker as a *translator* over a resolved `MethodRef.Service` rather than the spec's fresh SDL+classloader reflection (see R238's "Walker substrate" note). Three consequences of that substrate choice are carved out here as their own vertical, so R238 could land Done without overstating its surface:

1. **Typed per-arm errors.** The walker today produces only `ServiceMethodCallError.MultipleDslContextSlots` and `ServiceMethodCallError.ParameterUnbindable`; every other failure (class-load, ambiguous-method, return-type-mismatch, ctor-param-from-arg, parameter-names-missing, input-bean-shape, the three `argMapping` rejections, instance-holder-missing-ctor) is still produced upstream as `AuthorError.Structural` prose. R238 trimmed the ten unreachable arms from the `ServiceMethodCallError` seal (and from `typed-rejection.adoc` / `RejectionSeverityCoverageTest`) per the "documentation names only live code" principle. Absorbing the resolver's parse-and-reflect work into `walker/internal/` (per R238's "Walker substrate" continuation note) gives each of those rejections a walker-side production path; re-add the typed arm, its `lspCode()`, its `typed-rejection.adoc` paragraph, and its `RejectionSeverityCoverageTest` sample as each one becomes reachable.

2. **Multi-arg constructors / silent first-match retirement.** R238's `Instance` carrier still only ever carries `[FromDsl]` ctor args (the legacy `(DSLContext)`-only restriction is *not* retired), and method resolution still silently picks the first name match rather than disambiguating on arity (the `AmbiguousMethod` arm never fires). Deliver first-class multi-arg ctor resolution (`(DSLContext, ContextArg)` etc.) and arity-based disambiguation with `AmbiguousMethod` on a genuine tie.

3. **Spec-named per-arm tests.** With the production paths live, add the unit-tier coverage R238's Tests section named (one rejection case per `ServiceMethodCallError.*` arm plus `UnknownName(SERVICE_METHOD)`, the walker-discipline test, arity disambiguation, SDL-cycle → input-bean-shape, the multi-arg-ctor case) and the pipeline-tier rejection-arm assertions.

Folded in here as well: R238's minor transitional cruft the Spec phase flagged. `ContextArgumentClassifier.syntheticServiceMethodRef` fabricates an empty `MethodRef.Service` solely to satisfy `ConflictSite.site()`; the bean-helper queue converts `ValueShape` composites back into synthetic `CallSiteExtraction.InputBean`. The cleaner shape is the Consumer-migration §'s sealed `ConflictSite.site` two-arm widening (`MethodRef` | `ServiceMethodCall`).
