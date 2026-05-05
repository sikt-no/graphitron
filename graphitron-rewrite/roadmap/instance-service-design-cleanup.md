---
id: R87
title: "Tighten instance-service @service design after architect review"
status: Backlog
bucket: architecture
theme: service
depends-on: []
---

# Tighten instance-service @service design after architect review

The instance-`@service` support shipped on commit `6da0351` (branch `claude/fix-mutation-fetchers-error-rBjD2`) restored legacy parity (downstream consumers with `public ServiceName(DSLContext ctx)` constructors and instance methods compile again) but landed without a Spec. A post-hoc principles-architect review surfaced seven tensions, ordered by architectural weight; this item tracks the cleanup. The bug fix itself is sound (the `serviceCallTarget` call-site fork is the right shape); what's wrong is how the static-vs-instance axis is carried in the model, where the contract is mirrored, and at what tier the new behaviour is tested.

## Tensions to address

1. **`MethodRef.isStatic()` is an enum-style accessor whose semantics differ per arm.** For `@service` it's a real classification axis (`true | false`, both reachable); for `@tableMethod` and `@externalField` it's a structural invariant (the latter actively rejects non-static; the former does not but should); for `ConditionFilter` (the `@condition` implementor) it's a category mistake — `@condition` expressions don't have a Java method to be static-or-not. The `default true` on the interface papers over all three. Stronger shape: a sealed `CallShape.{Static | InstanceWithDslHolder(...)}` component on `MethodRef.Basic`, exhaustive switch in `serviceCallTarget`, no shape-flag on `ConditionFilter`, and `needsDsl` pre-resolved on the `CallShape` arm rather than recomputed alongside the boolean fork at `TypeFetcherGenerator.java:~1190` and `~2435`. Highest-leverage tension.

2. **The 4/5/6-arg `MethodRef.Basic` constructor ladder hides the modifier from `@tableMethod`.** `reflectTableMethod` reflects on a real `Method` and could read `Modifier.isStatic` for free, but it threads `isStatic=true` via the 5-arg compat constructor's default. Today fine because `@tableMethod` author convention is static; tomorrow a silent default is exactly the failure mode this branch was opened to fix. Stronger shape: drop the compat constructors, force every reflection site to thread the bit explicitly. Backwards compat for tests is what test factories are for.

3. **The new `TypeFetcherGeneratorTest` cases assert against `code().toString()` substrings.** The principle is explicit: code-string assertions on generated method bodies are banned at every tier; they test implementation, not behaviour, and break on every refactor. The two new tests at `TypeFetcherGeneratorTest.java:1004-1007` and `:1025-1028` assert literal `"new com.example.Service(dsl).doThing("` substrings. The behaviour the bug fix restores is "the generated source compiles when the developer's `@service` is an instance class" — that's a compilation-tier claim. Stronger shape: pipeline-tier test asserting on `MethodRef.isStatic()` (or the lifted `CallShape` arm) and the emitted method's signature; plus a `graphitron-sakila-example` fixture exercising an instance service so the original Sikt regression is caught at compilation tier.

4. **No validator mirror for the new emitter assumption.** The `@LoadBearingClassifierCheck(key = "service-catalog-instance-service-holder-shape")` producer rejects non-static methods without a `(DSLContext)` ctor; `UnclassifiedField` carries the rejection through to the validator. That works, but the principle ("validator mirrors classifier invariants") asks for a dispatch set the validator can independently enumerate. Add a focused validator-tier test driving `TestInstanceServiceStubNoCtor` through a real schema and asserting the AUTHOR_ERROR surface, or a producer-side audit that the rejection arm is reachable from the validator's own code path.

5. **Per-call `new Service(dsl)` is a runtime-extension-point question we didn't answer.** The emitter hardcodes "the framework constructs your service holder" into the generated `*Fetchers.java`. Matches the legacy generator, but the rewrite's pitch is that business logic is reachable through *explicit extension points*, and an emitter-side `new` baked into generated code is the opposite of one. The choice may still be right (the constructor shape is narrow, generated code is read-only, consumers can swap by changing the schema-bound class), but the question deserves an explicit answer. Two options: (a) accept the legacy shape with a one-line note in `runtime-extension-points.adoc` ("instance `@service` classes are constructed per-call via `new C(DSLContext)`; for DI or shared state, make the method static and inject at the holder"); (b) carve out a `ServiceHolderFactory` extension point that the emitter calls instead of `new` — Spec-sized.

6. **Stylistic: dedupe the two consumer `reliesOn` strings on the load-bearing key.** The contract is correctly factored as one key with two consumers (`buildServiceFetcherCommon` and `buildServiceRowsMethod` rely on the same `(DSLContext)`-ctor guarantee), but the prose duplicates "without checking the holder shape at emit time" almost verbatim. Trim the rows-method consumer's `reliesOn` to "same guarantee as `buildServiceFetcherCommon`" so the rewrite-once-when-it-changes site is unambiguous.

7. **Retroactive roadmap/changelog entry for the parity restoration.** R32's changelog text is on file saying the rewrite *assumed static services*; this branch reverses that assumption. Bug-fix branches are explicitly outside the Backlog → Spec flow, so this isn't a workflow violation, but downstream consumers (Sikt opptak-subgraph and others on the same migration path) need the contract change discoverable somewhere. Cheapest fix: a one-line entry in `graphitron-rewrite/roadmap/changelog.md` capturing commit `6da0351` and the design choices §1, §2, §5 above as deferred follow-ups under this R-item.

## References

- Principles: `graphitron-rewrite/docs/rewrite-design-principles.adoc` (sealed-over-enum, narrow component types, validator mirrors classifier, banned code-string assertions, fork rule), `graphitron-codegen-parent/docs/graphitron-principles.adoc` (separate business logic from API, explicit extension points, stability through simplicity).
- Originating commit: `6da0351` ("Support instance-method @service classes via (DSLContext) constructor").
- Architect review session ran against branch `claude/fix-mutation-fetchers-error-rBjD2`.

## Suggested execution order

§3 + §2 are concrete and cheap; can ship together as a first slice. §1 (`CallShape` sealed lift) is the architecturally cleanest fix and the largest refactor — every `MethodRef.Basic` constructor in main and tests touches the diff; ships as its own slice with §6 folded in. §4 is a test-coverage slice. §5 needs a design call (a/b above) before it can be Spec'd; logging it as a deferred sub-question is acceptable for this item, or it can be carved off into its own R-item once the call is made. §7 lands when the parent item moves to Done so the changelog entry can cite the closing commit SHA.
