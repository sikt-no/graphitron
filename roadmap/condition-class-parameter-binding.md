---
id: R1020
title: "A @condition parameter typed as a plain Java class is built or rejected, never cast from a Map"
status: In Review
bucket: bug
priority: 5
theme: codegen-correctness
depends-on: []
created: 2026-10-12
last-updated: 2026-10-12
---

# A @condition parameter typed as a plain Java class is built or rejected, never cast from a Map

## Goal

A `@condition` method parameter bound to a GraphQL input-object argument receives a value of its declared Java type, or the build fails naming the parameter and the shapes it may take. Today a parameter typed as a consumer class (a Java record, or a class with setters) compiles cleanly and throws `ClassCastException: LinkedHashMap` on every request, because the generated condition call casts graphql-java's raw argument map straight to the class. When this lands, a bean-shaped parameter on a field- or argument-level `@condition` is built from the map exactly as the same class would be for a `@service` parameter, a `Map` parameter keeps working as it does now, and every other type is a build error naming the parameter and the type to declare. That is also what the user manual already promises: `how-to/add-custom-conditions.adoc` says field-level placement delivers an input-typed argument "as the carrier type the input is bound to", and nothing in the generator delivers it.

The minimal pair, from the sis schema that surfaced it:

```graphql
type Query {
  personProfiler(filter: QueryPersonProfilerFilterInput!): [Person!]!
    @condition(condition: {className: "no.sikt.sis.PersonConditions",
                           method: "queryPersonProfilerFilterCondition",
                           argMapping: "input: filter"},
               override: true)
}
```

```java
// works today, and keeps working
static Condition queryPersonProfilerFilterCondition(Person t, Map<String, Object> input)
// compiles, then ClassCastException on every request; after this item it is built from the map
static Condition queryPersonProfilerFilterCondition(Person t, QueryPersonProfilerFilterRecord input)
// compiles, then ClassCastException on every request; after this item a build error
static Condition queryPersonProfilerFilterCondition(Person t, String input)
```

## What happens today

A *condition parameter* is a value parameter of the reflected `@condition` method, other than its jOOQ `Table` slot. All three slot-typed entry points (`ConditionResolver.resolveArg` for an argument, `ConditionResolver.resolveField` for a field, `BuildContext.buildInputFieldCondition` for an input field) reflect the method through `ServiceCatalog.reflectTableMethod`. That method binds each condition parameter to a path (an argument, or a dotted walk into one) and gives it an extraction from `ServiceCatalog.legacyArgExtraction`, which looks at the Java type only to ask whether it is an enum: an enum gets `CallSiteExtraction.EnumValueOf`, everything else `CallSiteExtraction.Direct`. Nothing compares the Java type with the GraphQL type at the bound path.

The generated fetcher passes the field's raw `env.getArguments()` to the generated `<Parent>Conditions.<field>Condition(table, args)` glue method, and `ConditionGlueRenderer.extractionExpr` renders `Direct` as `($T) args.get($S)` with the parameter's declared type. For an input object, graphql-java's value is a `Map`, so only `Map` (any parameterization) and `Object` survive the cast; every sakila `@condition` that takes an input object takes `Map<String, Object>` (`InputFieldConditionFixtures`). A list-shaped slot is worse: `(List<Bean>) args.get("x")` succeeds under erasure and fails at the first element access inside the consumer's method.

The `@service` path closed the same gap long ago. `InputBeanResolver.enrich` rewrites a `Direct` input-object parameter into `CallSiteExtraction.InputBean` (a plan for building a record through its canonical constructor, or a class through its public no-arg constructor and setters, field by field from the SDL input type) or `CallSiteExtraction.JooqRecord`, and rejects with a named message every pairing it cannot build: an unloadable class, a JDK / jOOQ / enum / array element type, a list-versus-single cardinality mismatch, a non-public, abstract, or recursive bean, a class with no no-arg constructor, a field set with no bijection to the SDL fields. `InputBeanInstantiationEmitter` renders the plan as `create<Bean>` helpers. The condition side refuses both arms outright: `ConditionGlueRenderer.extractionExpr` throws `IllegalStateException("InputBean is a @service parameter concept and never a condition binding")`.

## Implementation

Both phases shipped together at `99d89c4f`. The plan sections that described them are collapsed here into what landed and where it departs from the plan.

- **The shared decision is two steps, not one.** `InputBeanResolver.decideCarrier` returns `MapPassThrough`, `Bean`, `JooqRecordInput` or `Rejected` without building anything; `buildBean` and `buildRecordCarrier` build. The plan had one function returning built outcomes, but building writes the decode ledger (a bean's `@nodeId` members, a record's key decodes), so a caller that refuses an arm must not build it first. `enrich` composes the two and keeps its verdicts; the condition site in `ServiceCatalog.conditionCarrier` states its policy per arm.
- **Message for a non-carrier type after Phase 2 (reviewer finding, settled).** `Rejected` (a `String`, a JDK type, an unloadable class, a cardinality mismatch, a non-`List` collection) maps to `WireCoercionError.Assignability` against the delivered `Map` / `List<Map>`, whose message names the type to declare. A bean that cannot be built keeps the shared function's message naming why.
- **The two Phase 2 refusals are typed arms on `WireCoercionError`:** `ConditionBeanAtInputField` and `ConditionJooqRecordCarrier`. A bean class that is itself a jOOQ record is refused with the latter too: without a `@service` producer the input is not classified as a jOOQ-record input, so such a parameter reaches the `Bean` arm and would otherwise be built through setters.
- **A `Set` of beans is refused** (`Assignability`): the helper builds a `List`, which is what graphql-java delivers. `peelJavaListSet` folds `Set` into list-ness, so the condition policy tells them apart itself.
- **The consuming coordinate rides in as `ConditionSite`** (`Consuming(parent, field)` for argument- and field-level, `InputField(type, field)` for input-field level), passed by the three entry points to `reflectTableMethod`. A dotted binding's use site descends the path (`pathUseSite`).
- **Found in passing and fixed, because Phase 1 depends on it:** an argument-level `@condition` that failed to resolve on an argument that still classified was dropped silently, and the field built without the filter. `FieldBuilder.classifyArgument` now returns `UnclassifiedArg` carrying the typed rejection.
- **Rendering** as planned: the bodies moved to `render/InputBeanFragments` behind a `Names` interface that `FetchersHelperNames` implements; `InputBeanInstantiationEmitter` delegates; `render/InputBeanHelperRegistry` hosts them on the conditions class and shares `RecordDecodeHelperRegistry` for single-type member decodes, so a bean member and a key projection decoding one node type are one helper.
- **Limit not in the plan:** a multitable (interface or union) field refuses a bean-typed condition parameter through the existing branch-safety gate (`FieldBuilder.isBranchSafeExtraction`), a clean build error. The branch path calls the same conditions glue, so the gate could admit `InputBean`; that is untested and left for a follow-up. Documented on the `@condition` page.

## Tests

- Pipeline: `ConditionParameterCarrierPipelineTest` (all three entry points; Map shapes; record, setter bean with nested bean, dotted path and list of records built; input-field bean, jOOQ record, unbuildable beans, `Set`, a path through a list, a non-carrier type at field and argument level refused). Fixtures in `TestConditionCarrierStub`. `ConditionBindingShapePipelineTest`'s stub now takes `Map<String, Object>`, and so does `TestConditionStub.inputNestingCondition`, which had the same latent cast.
- `@service` unchanged: the existing input-bean pipeline tests pass with no edits.
- Execution: `GraphQLQueryTest` `filmsByTitleBean_*`, `filmsByTitleBeans_*`, `filmsBySearchBean_*`, `filmTitleEcho_*` over `BeanConditionFixtures` in `graphitron-sakila-service` and the matching `Query` fields in the example schema; `filmTitleEcho` is the `@service` taking the same bean class on the same parent. The example module compiles the emitted tree at `<release>17</release>` under `-Xlint:all -Werror`.

## Documentation

`reference/directives/condition.adoc` § Input-object parameters is the contract; `add-custom-conditions.adoc`, `condition-cascade.adoc` and `result-types.adoc` point at it and stop saying a condition parameter backs the input type. `handle-services.adoc` gains the `input-bean-parameter` anchor it cross-references.

## Other solutions we've considered

- **Reject only, and make `Map` the documented contract.** Smallest, and it closes the crash. It retracts a carrier the manual already promises and steers consumers toward the parameter shape `@service` rejects as an anti-pattern. Phase 1 is this option, shipped as a waypoint rather than the destination.
- **Call `enrich` from the condition path with an "admit Map" flag.** `enrich` is typed on `MethodRef.Service` and a field definition, handles head paths only, and a flag would hide the per-directive policy that the shared outcome type makes each caller state.
- **A condition-specific bean builder, or a copy of the helper bodies in `render`.** Two definitions of a buildable bean, and two bodies per helper, which drift.
- **Counting `@condition` as a producer in `RecordBindingResolver` and the store's type-backing rule.** Would make the input type class-backed, changing its classification, and the implicit-predicate path, for every field consuming it, and would need argument- and input-field-level joins the rule does not have. The parameter-local plan builds the same value without changing what the input type is.
- **Passing a typed carrier from the fetcher instead of `getArguments()`.** Every call site of a condition glue method (top-level queries, inline child fetchers, `@splitQuery` batched fetchers) would each have to build the same carrier.

## Provenance

sis IT run against Oracle 18c on trunk `dd940c703` (`sis/GRAPHITRON10_STATUS.md`, section "Second IT run"). `PersonConditions.queryPersonProfilerFilterCondition(Person, QueryPersonProfilerFilterRecord)`, where `QueryPersonProfilerFilterRecord` is a POJO with setters and nested POJOs; the failing cast is in the generated `QueryConditions.personProfilerCondition`. sis can switch the parameter to `Map<String, Object>` today as a workaround; Phase 1 is what turns the next such mistake into a build error.

## Reviewer findings

### Round 1 (Spec → Ready sign-off)

Signed off: the goal is clear and reachable, and the plan extends shapes already in the tree (`InputBeanResolver.enrich`'s gates, `WireCoercionError.Assignability`, the `RecordDecodeFragments` render-side body pattern). Corrected in this round: the Goal quoted the "carrier type the input is bound to" sentence from `how-to/condition-cascade.adoc`; it is in `how-to/add-custom-conditions.adoc` (line 224).

Non-blocking, for the implementer:

- The Documentation section lists `condition.adoc` and `condition-cascade.adoc`. `add-custom-conditions.adoc` carries the sentence the Goal quotes, so Phase 1 corrects it too.
- Phase 2 changes more than "one policy arm". The Phase 2 tests have unbuildable beans reject with the shared function's messages (Phase 1 maps `Rejected` to `Assignability`), and they add the typed input-field-level and `JooqRecord` refusals. Settle which message a non-bean, non-`Map` type (`String`) gets after Phase 2. The Goal wants a message that names the type to declare, and on a condition that includes `Map`, which `enrich`'s JDK-type message omits.
- `MapPassThrough` ("a `Map` is assignable to" the element type) and `enrich`'s current gate (`Map.class.isAssignableFrom(element)`, checked before cardinality) disagree on edge shapes: `Object`, a `HashMap` subtype, a `Map` on a list slot. Under the existing tests, `enrich` keeps its verdicts but may change wording on these. `peelJavaListSet` also folds `Set` into list-ness, so the condition arm has to tell `Set<Map<…>>` apart from `List<Map<…>>` itself, as the Tests section requires.
- `ConditionResolver.resolveArg(GraphQLArgument)` and `resolveField(GraphQLFieldDefinition)` do not receive the parent type name that `ClassifyContext.UseSite` needs. Their callers in `FieldBuilder` (`classifyArgument`, `projectForFilter`) do, so the coordinate is threaded in one frame.
