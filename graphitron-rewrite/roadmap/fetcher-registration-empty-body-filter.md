---
id: R165
title: Filter empty-body entries out of the fetcher-registration keyset
status: Spec
bucket: cleanup
priority: 2
theme: mutations-errors
depends-on: []
created: 2026-05-15
last-updated: 2026-05-15
---

# Filter empty-body entries out of the fetcher-registration keyset

`GraphQLRewriteGenerator.runPipeline` emits two artefacts from one `FetcherRegistrationsEmitter.emit(...)` result and they disagree on which types are in scope. The map is consumed twice:

1. `ObjectTypeGenerator.generate(schema, assembled, fetcherBodies)` looks each per-type body up by name and emits the `<Name>Type.registerFetchers(GraphQLCodeRegistry.Builder)` method only when `fetcherBody != null && !fetcherBody.isEmpty()` (`ObjectTypeGenerator.java:153`).
2. `GraphitronSchemaClassGenerator.generate(..., fetcherBodies.keySet(), ...)` iterates the bare keyset and emits `<Name>Type.registerFetchers(codeRegistry)` for every entry (`GraphitronSchemaClassGenerator.java:91-93`), without consulting whether the body was empty.

Today `FetcherRegistrationsEmitter.emit` does put empty-body entries in the map: `typeBody` (`FetcherRegistrationsEmitter.java:97-108`) returns the result of `buildBody`, which returns an empty `CodeBlock` when the classified-field list is empty (`buildBody` lines 164-169); same shape for `nestedBody` (lines 110-117). Any entry whose body collapses to empty therefore reaches the keyset, and the emitted `GraphitronSchema.build()` body calls a `registerFetchers` method that `ObjectTypeGenerator` never wrote.

The user-visible symptom is a `javac` error in consumer projects:

```
GraphitronSchema.java:[NNN]: cannot find symbol
  symbol:   method registerFetchers(graphql.schema.GraphQLCodeRegistry.Builder)
  location: class …<TypeName>Type
```

The triggering schema in the field report was an unreferenced delete-mutation payload that R156 admits but no other field returns:

```graphql
type SlettRegelverksamlingPayload {
    regelverksamlingId: [ID!] @nodeId
}
```

The deeper "should this type have been pruned in the first place?" question is real but out of scope here; this plan fixes the emit-side invariant. A separate Backlog item (the `graphqlschemavisitor-driven-emission` candidate) tracks the broader reachability/pruning question.

## Invariant

`FetcherRegistrationsEmitter.emit(...)` must return a `Map<String, CodeBlock>` whose values are all non-empty. Equivalently: every key in the returned map corresponds to a type for which `ObjectTypeGenerator` will emit a `registerFetchers` method. The keyset/method-emission contract becomes single-sourced: `ObjectTypeGenerator`'s gate (`!fetcherBody.isEmpty()`) and `GraphitronSchemaClassGenerator`'s call site (one per keyset entry) are made consistent by construction.

## Implementation

One edit in `FetcherRegistrationsEmitter.emit` (`graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/schema/FetcherRegistrationsEmitter.java`):

After both population loops complete, drop empty values before returning:

```java
result.values().removeIf(CodeBlock::isEmpty);
return result;
```

Rationale for filtering at the emitter rather than at one of the two consumers:

- Filtering inside `GraphitronSchemaClassGenerator` only would leave the orphan key in the map and force every future consumer of `FetcherRegistrationsEmitter.emit` to re-derive the same filter. The emitter is the natural authoritative point.
- Filtering at the `typeBody` / `nestedBody` callers (i.e. skipping the `put` when the body is empty) is also viable but spreads the policy across two call sites. A post-pass removal keeps the policy at one line.

`ObjectTypeGenerator`'s `!fetcherBody.isEmpty()` gate stays as-is: it guards against `fetcherBodies.get(name)` returning `null` for object types that don't have a body in the map at all (i.e. types not in the keyset) and is the correct null-safety idiom; the gate is no longer load-bearing for the keyset/method contract.

## Tests

Two new tests, both pinned to the invariant rather than to the specific reproducer schema, so that future emitter changes can't reintroduce the bug class without breaking the regression.

### Unit tier: emitter post-condition

`graphitron/src/test/java/no/sikt/graphitron/rewrite/generators/schema/FetcherRegistrationsEmitterTest.java` (new file).

Assert that for representative SDL fixtures that exercise the type-promotion path (single-record carriers, nested types with no classifiable fields, `@record`-without-`className` types, plain-object types), `FetcherRegistrationsEmitter.emit(schema, "")` returns a map whose `values().stream().noneMatch(CodeBlock::isEmpty)`. Property-style; we don't need to enumerate every promotion shape, just one fixture per code path that can produce an empty body today.

### Pipeline tier: end-to-end regression

`graphitron/src/test/java/no/sikt/graphitron/rewrite/generators/schema/GraphitronSchemaClassGeneratorPipelineTest.java` (new file or extend the existing unit-tier `GraphitronSchemaClassGeneratorTest` body if a `@PipelineTier` companion class fits the convention better).

Wire a fixture that contains an unreferenced payload-shaped type matching the field report: 

```
type Query { x: String }
type SlettRegelverksamlingPayload { regelverksamlingId: [ID!] @nodeId }
```

Run the same generator sequence `GraphQLRewriteGenerator.runPipeline` uses (`FetcherRegistrationsEmitter.emit` → `ObjectTypeGenerator.generate` → `GraphitronSchemaClassGenerator.generate`) and assert:

For every `<Name>Type.registerFetchers(codeRegistry)` call in the emitted `GraphitronSchema.build()` body, the corresponding `<Name>Type` `TypeSpec` returned by `ObjectTypeGenerator` declares a `registerFetchers` method. Regex the call sites, intersect with the emitted class methods, and assert no orphan calls.

This is the bug-class invariant: any future drift between the keyset and the method-emission gate fails the test directly, not via a downstream compile error.

### Compilation tier: opt-in to existing coverage

The fix is also exercised by the existing `mvn compile -pl :graphitron-sakila-example -Plocal-db` check; no new compilation-tier test needed. If a sakila-example fixture can be extended with an unreferenced-payload type without disrupting other coverage, that lands incidentally.

## Out of scope

- Reachability-based pruning of unreferenced SDL types. The orphan payload survives into `schema.types()` because `SchemaGenerator.makeExecutableSchema` parks all defined types into `additionalTypes`; the rewrite has no reachability sweep. The "should have been pruned" framing is real but is its own architectural question, captured in the separate Backlog item floating `GraphQLSchemaVisitor`-driven emission as the broader approach.
- `ObjectTypeGenerator`'s `!fetcherBody.isEmpty()` gate. Stays as null-safety, no longer carries policy weight.
- Any change to `GraphitronSchemaClassGenerator`'s iteration over the keyset. With the emitter's new post-condition the call-site loop is correct as-is.
