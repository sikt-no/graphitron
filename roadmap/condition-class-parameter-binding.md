---
id: R1020
title: "A @condition parameter typed as a plain Java class is built or rejected, never cast from a Map"
status: In Progress
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

Two phases, because the seam is real: Phase 1 alone closes the runtime failure (a bean parameter becomes a build error instead of a crash), and Phase 2 turns the bean case from an error into a working binding. Each leaves an observable, consistent state, and Phase 2 changes one policy arm plus the rendering rather than revisiting Phase 1's classification.

### Phase 1: one per-slot carrier decision, and the condition site's rejection

*The decision.* Extract the per-parameter body of `InputBeanResolver.enrich` (the gates from loading the element class through the cardinality check, then the jOOQ-record versus bean split) into one function over a parameter, the SDL type resolved at its bound path, and the site's coordinates, returning a sealed outcome: `MapPassThrough` (the Java element type is one a `Map` is assignable to, at matching cardinality), `InputBean`, `JooqRecord`, or `Rejected`. `enrich` becomes its first caller, and turns `MapPassThrough` into the existing "permanent anti-pattern" rejection, so its observable behaviour does not change. The function takes the resolved leaf type rather than a `GraphQLFieldDefinition`, which removes `enrich`'s head-path restriction from the shared part: `enrich` keeps skipping non-head paths, since a `@service` dotted binding onto an input object is not this item's defect.

*The condition site.* The second caller is the `ParamSource.Arg` branch of `ServiceCatalog.reflectTableMethod`, the one place all three slot-typed entry points reach (`ConditionResolver.resolveArg` for an argument, `ConditionResolver.resolveField` for a field, `BuildContext.buildInputFieldCondition` for an input field). When `ServiceCatalog.resolvePathLeafType` resolves the bound path, head or dotted, to an input object (after peeling list and non-null), the branch calls the shared function instead of `legacyArgExtraction`, and states the condition policy per arm:

- `MapPassThrough` keeps `Direct`. This is the shape every existing fixture uses and the one the manual calls carrier-free; graphql-java delivers exactly that type, so admitting it on a condition while `@service` bans it is a per-directive policy over a wire-safe shape, stated at each caller rather than carried as a flag.
- `InputBean`, `JooqRecord` and `Rejected` are all rejected in Phase 1, and the rejection is `WireCoercionError.Assignability` (delivered `java.util.Map`, or `java.util.List` for a list slot, declared the parameter's type, at the parameter's site), the input-object arm of the cast guard `WireCoercionResolver.checkScalar` already applies to scalars. Its message already says what to do: declare the delivered type or a supertype.

A path that passes through a list segment before reaching the input-object leaf is rejected with the same arm in both phases; nothing maps a carrier per element across a list on the condition path.

Scalar and enum leaves keep `legacyArgExtraction` untouched. Their cast guard is R411, which threads the `@service` wire-coercion predicate through the same branch and retires the shim; after this item that is a swap of the one remaining arm of the branch, and the comment at the branch (which today says only `@service` rejects) is updated to name both. The path-step `@condition` caller passes no slot types and is unaffected.

### Phase 2: build the bean

*Classification.* The condition site's `InputBean` arm stops rejecting and installs the extraction, at argument- and field-level `@condition`. The bean plan is keyed on the consuming coordinate (`ClassifyContext.UseSite`: object type, field, argument, descent), which is how a bean member's `@nodeId` decode finds its instruction; those two entry points have that coordinate. An input-field `@condition` is resolved once per input-type definition and placed per use by `ConditionResolver.rewrapForNested`, so it has no consuming coordinate at resolve time; a bean-typed parameter there stays rejected, with a typed message naming the input field and saying to take a `Map` or to move the `@condition` to the consuming field. `JooqRecord` stays rejected on conditions in this item, also with a typed message: its helpers carry the jOOQ shape-contention naming (`JooqRecordHelperNames`), and nothing in the sis case or the manual asks for it. Both remaining refusals leave the glue's `IllegalStateException` arms as invariant throws, unreachable by construction.

*Rendering.* `ConditionGlueRenderer` hosts the `create<Bean>` helpers, and the `decode<TypeName>Record` and polymorphic decode helpers a bean plan's `@nodeId` members pull in, on the `<Parent>Conditions` class, because the Fetchers-class helpers are `private static` elsewhere. It cannot call `InputBeanInstantiationEmitter` to build them: `render` may not import `rewrite.generators` (`PackageImportDirectionTest`). The bodies therefore move into `render`, on the pattern `RecordDecodeFragments` already set, where the generator side calls them (`generators` importing `render` is allowed), so one body serves both hosts. A render-side registry on the pattern of `RecordDecodeHelperRegistry` collects, names and adds them to the Conditions class, so registering a helper and calling it cannot disagree on its name; the Fetchers-side naming (`FetchersHelperNames`) is not shared, since each class needs collision-free names only within itself. `extractionExpr`'s `InputBean` arm renders the singular or plural helper over `args.get(name)`, and `nestedExtraction` gains an `InputBean` leaf arm for a dotted path (`ParamSource.Arg.callSiteExtraction` wraps a dotted binding in `NestedInputField` with the bean as leaf).

*Type backing.* A condition parameter's bean is a parameter-local carrier: built from the SDL input type and the parameter's class directly, as on `@service`. It does not make the input type class-backed. `RecordBindingResolver` and the store's `graphitron_type_backing_rule`, which both derive an input type's backing class from `@service` and `@externalField` producers only, stay as they are, and the two agree: neither counts a condition. The input type's classification, and the implicit column predicates that `override: false` adds through the `Map` traversal, are unchanged.

### Documentation

Phase 1 corrects `docs/manual/reference/directives/condition.adoc` and `docs/manual/how-to/condition-cascade.adoc` to what holds in between: an input-typed condition parameter is `Map<String, Object>` (a `List` of it for a list slot), any other type a build error. Phase 2 states the typed carrier, now true, with the accepted bean shapes cross-referenced to the `@service` page's input-bean section rather than restated, and the two limits (no bean on an input-field `@condition`, no jOOQ record carrier). `condition-cascade.adoc`'s carrier section, which says a class-backed input's backing class "is reflected from the `@service` / `@condition` method parameter the input flows into", is corrected in Phase 1 to name `@service` only: a condition parameter's bean is parameter-local and backs no type.

## Tests

Phase 1, pipeline tier, beside `ConditionBindingShapePipelineTest` in `graphitron/src/test/java/no/sikt/graphitron/rewrite/`:

- One test per entry point (field-level through `argMapping: "input: filter"`, argument-level, input-field-level on a nesting input field) with a record-typed parameter: rejected with `WireCoercionError.Assignability` naming the parameter's site and type.
- A dotted `argMapping` landing on an input-object leaf, with a bean-typed parameter: rejected the same way.
- `Map<String, Object>`, `Map<?, ?>` and `Object`, and `List<Map<String, Object>>` on a list slot: `Direct`, as today.
- `List<Bean>`, `Set<Map<String, Object>>`, and a single `Map` on a list slot: rejected.
- `@service` rejections unchanged: the existing `@service` input-bean tests (`InputBeanResolverBoxPrimitiveTest`, the `TestInputBean*` fixtures' pipeline coverage) stay green with no edits, which is the evidence that the extraction preserved `enrich`'s behaviour.
- `ConditionBindingShapePipelineTest.aTableSlotNamedAfterAFieldArgumentDoesNotClaimTheArgumentsSlot` binds a record-typed `FilmFilter kriterier` to an input object, whose record component (`navn`) does not even match the SDL field (`title`). The test is about slot claiming, not the carrier: the stub's parameter becomes `Map<String, Object>` so it keeps asserting what it was written for.

Phase 2:

- Pipeline: the field- and argument-level bean cases classify with `CallSiteExtraction.InputBean`, for a record and for a setter class with a nested bean member; unbuildable beans (no no-arg constructor, a member with no SDL counterpart, an interface) reject with the shared function's messages; the input-field-level bean and a `JooqRecord` parameter reject with their named messages.
- Execution tier, the tier today's defect escapes to: condition fixtures beside `InputFieldConditionFixtures` in `graphitron-sakila-service` and query fields in `graphitron-sakila-example`'s schema, one `@condition` taking a record bean and one taking a setter class with a nested bean member (one bound by `argMapping`, one dotted), each filtering rows by a bean member, asserted in `GraphQLQueryTest`. The sakila example compiles the emitted tree at `<release>17</release>` under `-Xlint:all -Werror`, which pins that the Conditions-hosted helpers carry no unchecked cast.
- A `@service` and a `@condition` on the same parent taking the same bean class compile side by side, each generated class hosting its own `create<Bean>`.

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
