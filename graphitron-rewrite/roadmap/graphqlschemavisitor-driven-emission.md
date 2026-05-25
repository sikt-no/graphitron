---
id: R166
title: "GraphQLSchemaVisitor as the driver for code generation"
status: Backlog
bucket: architecture
priority: 5
theme: structural-refactor
depends-on: [dimensional-model-pivot]
created: 2026-05-15
last-updated: 2026-05-15
---

# GraphQLSchemaVisitor as the driver for code generation

Backlog stub. Floats an architectural idea that surfaced while diagnosing R165 (`fetcher-registration-empty-body-filter`). Connected to R164 (`field-model-two-axis-pivot`); the two reorganise different surfaces of the same pipeline and a clean field model is the natural foundation for a cleaner emission walk.

## Problem

The generator pipeline today is N independent iterations over `GraphitronSchema.types()` (and `.fields()`), one per emitter (`ObjectTypeGenerator`, `InputTypeGenerator`, `EnumTypeGenerator`, `FetcherRegistrationsEmitter`, `TypeFetcherGenerator`, `GraphitronSchemaClassGenerator`, `QueryNodeFetcherClassGenerator`, …). Each emitter chooses its own skip rules: introspection names, `UnclassifiedType`, root types, federation-injected types, variant-instanceof gates. When those filters drift apart, the emitted code goes inconsistent. R165 is the symptom: `FetcherRegistrationsEmitter` puts an empty-bodied entry in its keyset, `ObjectTypeGenerator` skips emitting the method, `GraphitronSchemaClassGenerator` emits the call site — and the consumer's `javac` fails. R165 patches the specific keyset; the structural bug class (N filters, N opportunities to drift) remains.

A separate but related symptom: `schema.types()` itself contains SDL-declared types that are unreachable from any root operation, because `SchemaGenerator.makeExecutableSchema` parks every defined type into `additionalTypes`. There is no reachability sweep anywhere in the rewrite. R165's narrow fix tolerates the orphan; it does not eliminate it.

## What's to be

Use `graphql.schema.visitor.GraphQLSchemaVisitor` (graphql-java 25's type-safe visitor facade, exposing per-kind callbacks: `visitObjectType`, `visitFieldDefinition`, `visitInterfaceType`, `visitUnionType`, `visitInputObjectType`, `visitEnumType`, `visitScalarType`, `visitArgument`, …) driven by `SchemaTraverser.depthFirst(visitor, roots)` seeded with the consumer-reachable surface: root operation types plus federation `@key`-bearing types. The visitor visits the reachable graph transitively (object → field → return type, object → interface, union → members, etc.), looks each visited type/field up in `GraphitronSchema`, and dispatches to the appropriate emitter. The single walk *is* the authoritative "what's in scope" decision; no per-emitter filter can drift from it because there is no per-emitter iteration.

Pruning falls out: unreferenced types never get visited, never get classified-on-demand, never reach an emitter.

The bug class from R165 disappears: there is one driver, one set of visit callbacks, one place where "this name is in scope" is decided.

## Open design questions

The proposal is non-trivial and warrants a Spec pass with the `principles-architect` subagent before commitment. Concerns surfaced in the originating discussion:

1. **Cross-cutting emitters.** `GraphitronSchemaClassGenerator` produces one global file aggregating per-type contributions (`.additionalType(...)`, `.registerFetchers(...)`, TypeResolvers, error fetchers) in alphabetical order. A per-type visit callback can *contribute* but not *finish* — a buffered aggregator pass is still needed alongside the visitor. Same for `EntityFetcherDispatchClassGenerator`, `ErrorRouterClassGenerator`, `ErrorMappingsClassGenerator`, `QueryNodeFetcherClassGenerator`.
2. **Utility-class emitters.** `ConnectionHelperClassGenerator`, `ConnectionResultClassGenerator`, `GraphitronValuesClassGenerator`, `ColumnFetcherClassGenerator`, `OrderByResultClassGenerator`, `PolymorphicSelectionSetClassGenerator`, `ConstraintViolationsClassGenerator`, `GraphitronContextInterfaceGenerator`, `GraphitronFacadeGenerator` produce runtime support classes with no GraphQL counterpart. They have no natural visit callback and continue to be invoked directly outside the visitor. The visitor is *a* driver, not *the* driver.
3. **Variant-vs-kind mismatch.** A single `GraphQLObjectType` classifies to ten-plus `GraphitronType` variants (`TableType`, `NodeType`, `RootType`, the `ResultType` sub-tree, `PlainObjectType`, `ConnectionType`, `EdgeType`, `PageInfoType`, `ErrorType`). `visitObjectType` is one entry; inside the callback we still switch on the looked-up variant, which is what today's emitters already do. Dispatch moves, but doesn't shrink. R164 (`field-model-two-axis-pivot`) — which reorganises the field hierarchy onto the two emission axes — directly affects whether this dispatch can be made naturally pattern-matchable inside the visitor callback or remains a wide `switch`.
4. **Architectural separation today.** `TypeBuilder` / `FieldBuilder` produce `GraphitronSchema` (shape recognition); emitters consume it (emission). Visitor-as-driver re-fuses those phases: emission walks the assembled schema, cross-references the classified model, dispatches. `GraphitronSchema` demotes from "iteration source" to "lookup map." Worth its own evaluation against the design principles.
5. **Determinism and ordering.** Today's emitters sort their output (alphabetical type names, declared-handler order, ...) for stable diffs. Visitor traversal order is implementation-defined. A visitor-driven design must either accumulate-then-sort (which de-emphasises "per visit" emission) or take on ordering discipline as part of the walker contract.
6. **Test ergonomics.** Today's unit tests call emitters directly with crafted inputs (`ObjectTypeGenerator.generate(schema, assembled, fetcherBodies)`). With visitor-as-driver, emitters become callbacks; testing one in isolation means either an in-test visitor driven with a one-type seed, or keeping a direct-call shim for backwards compatibility. Either is real refactor work across the test suite.
7. **Carrier types for per-emitter intermediates.** R165 (`fetcher-registration-empty-body-filter`) parked the type-system carrier strengthening for `FetcherRegistrationsEmitter.emit`'s return: today it is `Map<String, CodeBlock>` whose values must be non-empty by load-bearing-pair convention; the natural endpoint is a `NonEmpty<CodeBlock>` wrapper (compact-constructor checked) or a dedicated `FetcherBodies` record that carries the invariant in the type signature rather than as an annotation pair. The Spec should decide whether the visitor restructure absorbs this strengthening (each aggregator-style emitter publishes a typed contribution carrier) or leaves it as an independent micro-refactor. The principles-architect read on R165 flagged this as the natural endpoint that R165's `Optional<CodeBlock>` deferral should not lose track of.

## Why this is connected to R164

R164 reorganises the `Field` model onto the two dimensions emission already lives along (DataFetcherBuilder, QueryBuilder). The cleanest case for visitor-as-driver is one where the per-visit dispatch can match on the two-axis form (one pattern per `(DataFetcherBuilder, QueryBuilder)` shape, or one per dimension with a small modifier set), rather than on a 28-permit cross-product. Without R164, the `visitFieldDefinition` callback either fans out into the existing permit lattice or duplicates the permit-flattening logic at the visit site. R164 is therefore the natural prerequisite: it shrinks the dispatch surface the visitor has to land on.

The reverse is *not* true. R164 stands on its own; visitor-driven emission is an additional restructure on top.

## Out of scope until Spec

- The reachability question for unreferenced types is the obvious near-term motivator for a visitor walk, and could be tackled standalone (as a small `ReachabilityPruner` post-pass that filters `schema.types()`) without committing to visitor-as-driver. That standalone fix is a defensible smaller-budget alternative to this item; the Spec should make the explicit call between them.
- Federation `_Entity` injection, `@inaccessible` filtering, and directive-application reachability are all orthogonal extensions the same walk can subsume. Out of scope until Spec.

## Phasing hint (pre-Spec)

If this advances past Backlog, the natural shape is:

1. **Reachability slot first.** Land a `ReachabilityPruner` that uses a `GraphQLSchemaVisitor` to compute the reachable-name set and filters `GraphitronSchema.types()` / `.fields()` post-classification. Single, contained change; subsumes R165's narrow fix; pays for the visitor scaffolding even if (2) never happens.
2. **Move one emitter to the visitor.** Pick a clean target (probably `EnumTypeGenerator` or `InputTypeGenerator` — small, per-type, no cross-cutting consolidation) and convert it to a visit callback. Validates the test-ergonomics story without committing the harder cases.
3. **Aggregator-style emitters next.** `ObjectTypeGenerator`, then `TypeFetcherGenerator`, then `FetcherRegistrationsEmitter`. Each one buffers per-visit contributions and finalises after the walk.
4. **Cross-cutting consolidators.** `GraphitronSchemaClassGenerator`, the error consolidators. These become aggregator passes that consume the buffered per-type contributions.
5. **Utility-class emitters stay external.** They have no GraphQL counterpart and remain invoked directly by the pipeline orchestrator.

Each phase is independently shippable and individually reversible. Phasing is illustrative; the Spec author makes the call.
