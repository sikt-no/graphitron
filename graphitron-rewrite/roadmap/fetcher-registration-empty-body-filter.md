---
id: R165
title: Filter empty-body entries out of the fetcher-registration keyset
status: In Review
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

`FetcherRegistrationsEmitter.emit(...)` returns a `Map<String, CodeBlock>` in which every entry corresponds to a type for which `ObjectTypeGenerator` will emit a `registerFetchers` method. Equivalently: an entry exists in the map iff the type has at least one classifiable fetcher to wire; empty-body entries cannot occur because no `put` call site constructs one. The keyset/method-emission contract holds *by construction at the emitter*, not by a post-pass scrub. Both consumers, `ObjectTypeGenerator`'s `fetcherBodies.get(name)` lookup and `GraphitronSchemaClassGenerator`'s unfiltered iteration over `fetcherBodies.keySet()`, rely on this guarantee.

## Implementation

The fix gates emptiness at the construction site rather than scrubbing the map after the fact. Generation-thinking applies: the body-producing path either emits a wiring body or it doesn't; "produce then retract" leaves the model in a transient state that the type system doesn't witness, and a future contributor adding a fourth body-producing path would silently re-introduce the bug class until a post-pass author remembers to filter the new shape too.

In `FetcherRegistrationsEmitter` (`graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/schema/FetcherRegistrationsEmitter.java`):

1. Change `typeBody` and `nestedBody` to return `Optional<CodeBlock>`. Each returns `Optional.empty()` exactly when its current implementation would have returned `CodeBlock.builder().build()`, i.e. `nestedBody`'s `ntw.fields().isEmpty()` early-exit (lines 115-117) and `buildBody`'s `fields.isEmpty()` early-exit (lines 167-169). `connectionBody` and `edgeBody` always produce a non-empty body and stay `CodeBlock`-returning.
2. In `emit`, the two `result.put(...)` call sites that today unconditionally insert (line 78 for `typeBody`, line 81 for `nestedBody`) become `typeBody(...).ifPresent(body -> result.put(name, body))` and equivalent for `nestedBody`. Connection / edge wiring stays unconditional. No post-pass scrub is required: empty values cannot have been inserted.

The two `put` call sites already partition along the same predicate a post-pass would re-derive (which body has classifiable fields). Pushing the decision to the construction site does not spread policy; it relocates the existing branch from the body-builders' "return empty" early-exit into the caller's `Optional.ifPresent`, where the keyset invariant naturally lives.

`ObjectTypeGenerator`'s `fetcherBody != null && !fetcherBody.isEmpty()` gate at line 153 collapses to a null check. The `!isEmpty()` half is dead under the new invariant (the emitter never returns empty entries) and the null half guards against types not in the keyset at all. Whether to keep the redundant `!isEmpty()` clause as belt-and-braces or simplify to `fetcherBody != null` is a style call; the Spec defers to the implementer.

### Load-bearing pair

The emitter's "no empty values" invariant is the kind of producer-side classifier guarantee the rewrite already annotates explicitly (see `MutationInputResolver`, `NodeIdLeafResolver`, `GraphitronSchemaBuilder` for prior art). Add the pair:

- `@LoadBearingClassifierCheck` on `FetcherRegistrationsEmitter.emit`, key `fetcher-registrations.no-empty-bodies`, description: every entry in the returned map corresponds to a type for which `ObjectTypeGenerator` will emit a `registerFetchers` method; `typeBody` / `nestedBody` only contribute entries when they have a non-empty wiring body, and `connectionBody` / `edgeBody` always do.
- `@DependsOnClassifierCheck` on the call site in `GraphitronSchemaClassGenerator` that iterates the fetcher-bodies keyset (`GraphitronSchemaClassGenerator.java:91-93`), keyed to the same `fetcher-registrations.no-empty-bodies`, reliesOn: "Iterates the keyset and emits `<Name>Type.registerFetchers(codeRegistry)` once per entry; correct iff every key has a corresponding emitted method, which the emitter guarantees by withholding empty-body entries at construction."

`LoadBearingGuaranteeAuditTest` then keeps the pair honest: future edits that relax the producer surface as orphaned consumers in the audit test, not as a downstream `javac` failure in a consumer project.

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

Run the same generator sequence `GraphQLRewriteGenerator.runPipeline` uses (`FetcherRegistrationsEmitter.emit` → `ObjectTypeGenerator.generate` → `GraphitronSchemaClassGenerator.generate`) and assert the bi-directional invariant between emitted call sites and emitted methods:

- For every `<Name>Type.registerFetchers(codeRegistry)` call in the emitted `GraphitronSchema.build()` body, the corresponding `<Name>Type` `TypeSpec` returned by `ObjectTypeGenerator` declares a `registerFetchers` method (no orphan call sites — the symptom direction).
- For every `<Name>Type` `TypeSpec` declaring a `registerFetchers` method, the emitted `GraphitronSchema.build()` body contains a matching `<Name>Type.registerFetchers(codeRegistry)` call (no orphan methods — the inverse direction the original failure didn't surface, but the same emitter/consumer drift could produce).

Regex the call sites, collect the method-declaring `TypeSpec` names, and assert set equality.

Pinning both directions catches the bug class regardless of which side of the keyset/method contract drifts. A consumer adding a new iteration site or the emitter relaxing its construction-site gate both fail the test directly, not via a downstream compile error.

### Compilation tier: opt-in to existing coverage

The fix is also exercised by the existing `mvn compile -pl :graphitron-sakila-example -Plocal-db` check; no new compilation-tier test needed. If a sakila-example fixture can be extended with an unreferenced-payload type without disrupting other coverage, that lands incidentally.

## Out of scope

- Reachability-based pruning of unreferenced SDL types. The orphan payload survives into `schema.types()` because `SchemaGenerator.makeExecutableSchema` parks all defined types into `additionalTypes`; the rewrite has no reachability sweep. The "should have been pruned" framing is real but is its own architectural question, captured in R166 (the `graphqlschemavisitor-driven-emission` Backlog item) which floats `GraphQLSchemaVisitor`-driven emission as the broader approach.
- Strengthening `emit`'s return type to a record `FetcherBodies` (or `Map<String, NonEmpty<CodeBlock>>`) that carries the non-empty invariant in the type signature rather than as a load-bearing annotation. The principles point at this as the natural endpoint, but the change ripples through both consumers and is best done alongside the broader emission rework in R166 rather than as part of this narrow fix.
- `ObjectTypeGenerator`'s `!fetcherBody.isEmpty()` clause. Either drop it as redundant or keep it as belt-and-braces; either choice is consistent with the new invariant. Style-only decision left to the implementer.
- Any change to `GraphitronSchemaClassGenerator`'s iteration over the keyset. With the emitter's construction-site invariant in place, the call-site loop is correct as-is, and the `@DependsOnClassifierCheck` annotation documents the dependency.
