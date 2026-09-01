---
id: R894
title: "Emitter-tier pin: the @reference-carrying input-field @nodeId form emits its decode in the rendered glue"
status: In Progress
bucket: testing
theme: nodeid
depends-on: []
created: 2026-08-31
last-updated: 2026-09-01
---

# Emitter-tier pin: the @reference-carrying input-field @nodeId form emits its decode in the rendered glue

The coordinate from [issue 536](https://github.com/sikt-no/graphitron/issues/536), a list-typed filter input field carrying both `@nodeId(typeName:)` and a multi-hop `@reference(path:)`, is covered at two tiers but not the one between them. The model tier pins that classification plans the decode (`NodeIdPipelineTest.junctionChain_inputField_bindsRemotelyOverTwoHops` asserts the carrier's `NodeIdDecodeKeys` leaf), and the execution tier runs encoded ids end-to-end against PostgreSQL (`TranslatedFkTargetFilterExecutionTest.junctionChain_inputFieldForm_returnsTheSameRows`). No test renders the condition glue for this form and asserts the decode is in it: the emitter-tier decode pins that exist (`ConditionGluePipelineTest`, `NodeIdReferenceFilterPipelineTest`) all cover same-table input fields without `@reference`, or direct query-field arguments.

The gap matters because the model carrying a decode and the glue emitting one can diverge. `ConditionGlueRenderer.nestedExtraction` ends in a fall-through that casts the raw wire traversal to the binding's local type; for a decode-typed binding whose leaf the decode arm fails to recognise, that is a compile-clean cast of base64 strings to the decoded list, the per-request `ClassCastException` the issue describes. Three sites in `ConditionGlueRenderer` test the same leaf independently and must agree: the decode arm in `nestedExtraction`, the `decodeLeafOf` unwrap behind `localType`, and the `NodeIdDecodeKeys` carve-out in `emitsUncheckedLocalCast`. On the current tree they do agree, so the fall-through is unreachable for this coordinate, but nothing pins that. Drift in the arm alone still declares the local at the decoded type and then assigns the wire traversal to it, which is exactly the reported defect and is invisible to every check short of running a request.

## What changes when this lands

Nothing changes for a consumer of graphitron: no emitted code differs and no new diagnostic fires. What changes is the cost of finding a regression in the decode arm. Today the cheapest signal is an execution-tier failure that needs PostgreSQL and only fires for the fixture shapes `graphitron-sakila-example` happens to run; after this item, the same regression fails a pipeline-tier test in seconds, in `graphitron`'s own suite, naming the coordinate and the missing helper. This is a pin, so it is green on the current tree by construction; its value is entirely in what it does when someone edits `ConditionGlueRenderer`.

## The assertion is structural, not a body-string match

The obvious spelling of this pin, asserting that the coordinate's glue method body `contains("decodeCategoryKeysOrThrow(")`, is banned: code-string assertions on generated method bodies are out at every tier (`docs/architecture/principles/development-principles.adoc`, "Behaviour is pinned at the pipeline tier and above"; the pipeline-tier entry in `docs/architecture/how-to/testing.adoc` repeats it). `ConditionGluePipelineTest.twoQueryFields_sharingNodeIdType_emitOneSharedHelper` does match bodies today and is the wrong model to copy; its sibling in the same class says so in a comment and asserts shape instead.

A structural assertion carries the same claim here, because helper emission is call-driven. `CompositeDecodeHelperRegistry.register` is reached from exactly one site in `ConditionGlueRenderer`, the `decodeCall` helper, which is reached from exactly two places: the top-level `NodeIdDecodeKeys` arm of `extractionExpr` and the `NodeIdDecodeKeys` arm of `nestedExtraction`. `collectInto` drains whatever was registered onto the class being built and nothing else adds a `decode<Type>…` method. So for a schema whose only `@nodeId` carrier is the coordinate under test:

> the glue class carries a decode helper **if and only if** a decode arm fired for that binding.

The fall-through the item is about registers nothing, so it is observable as an empty helper set on the class. That makes "which methods does `QueryConditions` carry" a total statement about which arm ran, with no body text read.

Two further structural facts come along for free and are worth asserting, because they pin the shape the local is declared against rather than merely its existence:

* the helper's `MethodSpec.returnType()` is the decoded type: `List<Integer>` at arity 1, `List<Row2<String, String>>` at arity 2. That is the axis (`Keys` versus `Rows`, list versus scalar) that `CompositeDecodeHelperRegistry.helperName` derives its name from, asserted as a type rather than as a naming convention.
* the helper is `private static`, matching the drain contract.

## Implementation

One new test method per arity in `graphitron/src/test/java/no/sikt/graphitron/rewrite/generators/ConditionGluePipelineTest.java`. That class is the home rather than `NodeIdReferenceFilterPipelineTest` because its stated subject is already the decode-helper registry on glue classes, and because it already holds the arg-side multi-hop twin (`multiHopIdentityCarryingLift_emitsHelperOnLiftedTuple`). Adding these two completes a small matrix in one file: `{argument, input field}` by `{lifted local tuple, remote EXISTS}`. Both new methods render through `ConditionRenderTestSupport.renderCommittedConditions`, as every test in the class already does.

**Arity 1, the reported coordinate.** Built with `TestSchemaHelper.buildSchema(sdl)` against the default sakila catalog, because the junction table the chain needs (`film_category`) lives there and not in `nodeidfixture`. The class currently uses `nodeidfixture` for all three of its tests, so this introduces a second catalog into it; `NodeIdPipelineTest` already mixes the two for exactly this reason and carries a comment saying why, which this test should mirror. The SDL is the one `NodeIdPipelineTest.junctionChain_inputField_bindsRemotelyOverTwoHops` classifies, so the two tiers pin the same fixture:

```graphql
type Category implements Node @table(name: "category") @node { id: ID! }
type Film @table(name: "film") { title: String! }
input FilmFilterInput {
    categoryIds: [ID!] @nodeId(typeName: "Category") @reference(path: [
        {key: "film_category_film_id_fkey"},
        {key: "film_category_category_id_fkey"}
    ])
}
type Query { films(in: FilmFilterInput): [Film!] }
```

**Arity 2, the composite twin.** Built with the class's existing `FIXTURE_CTX` against `nodeidfixture`, over the `lift_fail_c` to `lift_fail_b` to `lift_fail_a` chain whose terminal node type has a two-column primary key `(k1, k2)`. The lift predicate fails on this chain, so it binds remotely inside a correlated `EXISTS` just as the junction does, with a `Row2` in place of a scalar `IN`. The arg-side form is already pinned at `NodeIdLeafResolverTest` (the `TranslatedFk` resolution and `decodeLiftFailA` decode method); this is its input-field emitter twin:

```graphql
type LiftFailA implements Node @table(name: "lift_fail_a") @node { id: ID! }
type LiftFailC @table(name: "lift_fail_c") { cId: String! @field(name: "c_id") }
input LiftFailCFilter {
    aIds: [ID!] @nodeId(typeName: "LiftFailA") @reference(path: [
        {key: "lift_fail_c_b_fk"},
        {key: "lift_fail_b_a_fk"}
    ])
}
type Query { liftFailCs(in: LiftFailCFilter): [LiftFailC!] }
```

Both fixtures were rendered against the tree at the time of writing to confirm the spec is describing what the emitter does rather than what it ought to. `QueryConditions` comes out carrying exactly two methods in each case: `[filmsCondition, decodeCategoryKeysOrThrow]` and `[liftFailCsCondition, decodeLiftFailARowsOrThrow]`, with `schema.diagnostics()` empty. The "only `@nodeId` carrier in the fixture" premise the biconditional above rests on is therefore not an assumption to be maintained by hand; it is visible in the method list the test asserts.

Each test asserts, on the rendered `QueryConditions`:

1. the coordinate's glue method is present (`filmsCondition`, `liftFailCsCondition`);
2. the methods whose names start with `decode` are exactly the one expected helper, named `decodeCategoryKeysOrThrow` and `decodeLiftFailARowsOrThrow` respectively. Asserting the whole decode set rather than mere presence is what keeps the biconditional honest if the fixture ever grows a second carrier;
3. that helper is `private static`, and its `returnType()` is `List<Integer>` at arity 1 and `List<Row2<String, String>>` at arity 2.

No assertion reads a method body.

The class javadoc gains a sentence naming the second shape it now covers, since its current text describes only the shared-helper deduplication case. Cite the GitHub issue and the sibling tests by symbol, never this item's id or its `roadmap/` path: `RoadmapReferenceGuardTest` scans comment regions in test sources and fails the build on either.

## What this does not cover

`nestedExtraction` has a second decode path ahead of the leaf arms: `ProjectedKeyReads.readFor`, which serves a condition parameter whose `argMapping` descends into a `@nodeId` input field, and which hosts its decode on `RecordDecodeHelperRegistry` under `decode<Record>` naming rather than on `CompositeDecodeHelperRegistry`. `ConditionRenderTestSupport.renderCommittedConditions` passes `KeyProjectionRelation.empty()`, so that path is inert in every test in this class and these two pin nothing about it. That is the right scope: it is a different mechanism reached by a different authored shape, and R884 is where it is being worked. Do not widen `renderCommittedConditions` to carry a projection relation for this item.

Nor does this item touch the build-time guard that would fail the whole instruction class rather than one coordinate. R893 is that work; this is the fast local pin on the one coordinate known to be good, and the two are complementary rather than sequenced. Neither depends on the other.
