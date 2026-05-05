---
id: R84
title: "Path expressions in argMapping"
status: In Progress
bucket: feature
priority: 5
theme: service
depends-on: []
---

# Path expressions in argMapping

R53 shipped `argMapping` as a flat `javaParam: graphqlArg` mini-DSL on `@service`, `@tableMethod`, and every `@condition` site; the right-hand side names a single GraphQL slot at the directive's scope. This works when the Java method takes the wrapper input wholesale, but the Relay-style mutation pattern (a single `input:` argument carrying several typed sub-fields) leaves the author with two bad choices: accept the GraphQL wrapper into the service signature and unpack it there (leaking GraphQL-input shapes into a service that otherwise has no GraphQL knowledge), or add a thin façade method whose only job is to pluck and forward.

Concrete trigger from the Sikt admissio→opptak migration:

```graphql
extend type Mutation {
    settKvotesporsmalAlgoritme(
        input: SettKvotesporsmalAlgoritmeInput!
    ): SettKvotesporsmalAlgoritmePayload!
        @service(service: {
            className: "...SettKvotesporsmalAlgoritmeService"
            method: "settKvotesporsmalAlgoritme"
        })
}

input SettKvotesporsmalAlgoritmeInput {
    kvotesporsmalId: ID! @nodeId(typeName: "Kvotesporsmal")
    kvotesporsmalAlgoritmeId: ID @nodeId(typeName: "KvotesporsmalAlgoritme")
}
```

The service signature the author wants is `settKvotesporsmalAlgoritme(KvotesporsmalRecord kvotesporsmal, KvotesporsmalAlgoritmeRecord algoritme)`. With today's argMapping there is no way to bind `kvotesporsmal` to `input.kvotesporsmalId` and `algoritme` to `input.kvotesporsmalAlgoritmeId`; the only available slot is the wrapper `input` itself.

## What the extension is

Allow the right-hand side of an `argMapping` entry to be a **dot-path expression** that walks into nested GraphQL input fields, e.g.:

```graphql
@service(service: {
    className: "..."
    method: "settKvotesporsmalAlgoritme"
    argMapping: """
        kvotesporsmal: input.kvotesporsmalId,
        algoritme:     input.kvotesporsmalAlgoritmeId
    """
})
```

Semantics, generation-time:

- The head segment names a slot at the directive's scope (a GraphQL argument for `@service` / `@tableMethod` / argument-level `@condition`; an input field for input-field-level `@condition`).
- Each subsequent segment names a field on the resolved input-object type at that depth. Walking through a scalar, enum, union, or interface is a structural rejection.
- **List segments lift naturally.** Each `[X]` segment along the path adds a `List<>` wrapper to the leaf type. Given:
  ```graphql
  someField(in: A): ...

  input A { a: String, b: [B] }
  input B { b: String,  c: [C] }
  input C { c: String }
  ```
  - path `in.a` → `String`
  - path `in.b` → `List<B>` (i.e. the Java type that today's R53 binding uses for an arg of type `B`)
  - path `in.b.c` → `List<List<C>>`
- **Null coalescing along the path.** Any intermediate-segment null short-circuits the whole leaf to null; no NPE, no per-element exception. List segments map element-wise; an element-level null inside a list propagates as a null entry in the resulting list (consistent with GraphQL's own input-list nullability).
- The leaf type binds to the Java parameter through the same lifters that apply to top-level slots: an `ID! @nodeId(typeName: "X")` leaf becomes `XRecord`, a `@table` leaf becomes its row record, a plain scalar becomes its Java mapping. Lifters apply *under* any `List<>` wrappers, so `in.b.c` with a `@nodeId`-tagged `c` produces `List<List<XRecord>>`.
- Two argMapping entries may resolve to the same leaf path (legal under the same "two overrides binding to the same slot" rule R53 already permits).

## Prior art: Apollo Connectors

Apollo Connectors' mapping language (`@connect(... selection: "...", body: """ $args.input { id quantity } """)`) is the closest existing design in the GraphQL ecosystem. What we borrow and what we don't:

- **Dot-path heads.** Apollo prefixes paths with `$args`, `$this`, `$config`. We have only one scope per directive site, so the prefix is redundant; bare-name heads matching today's R53 syntax are sufficient.
- **Array projection.** Apollo's `$args.filters.value` lifts to `[V]` when an intermediate segment is list-typed. We adopt the same rule, transposed to our static type system: each `[X]` segment adds a `List<>` wrapper. Apollo resolves this at runtime against JSON; we resolve the *type* at generation time against the GraphQL schema (so the Java parameter type is fixed at build time) and emit *value-walking* code that runs at request time over the input map (intermediate-null short-circuit, list element-wise traversal).
- **Subselection blocks** (`$args.input { id quantity }`) and **methods** (`->first`, `->match`, etc.) are not adopted; subselection is a natural follow-up once the flat dot-path lands, methods are a runtime-transform language and have no place in a generation-time parameter binder.

## Parser

Take ownership of the existing `selection/` package (`GraphQLSelectionParser`, `Lexer`, `Token`, `TokenKind`, `ParsedField`, `ParsedArgument`, `ParsedValue`) and rewrite it to serve both `@experimental_constructType(selection: ...)` (R69) and `argMapping` path expressions. The two grammars are the same shape: comma-separated `key: <expression>` entries where the right-hand side resolves to a typed value (a column reference today, a path expression with this item), and the lexer already handles the relevant tokens. One parser, two binders. If the grammars later diverge enough that the shared parser starts costing more than it saves, fork at that point; do not pre-emptively split.

The R53-era `parseArgMapping` in `ArgBindingMap` is too thin to host the path-walking logic and should be retired in favour of the rewritten `selection/` parser.

## Model carrier

R53's `ArgBindingMap.byJavaName: Map<String, String>` stores `javaParam → graphqlArgName`. With path expressions the value side is no longer a single name; it carries a head segment and zero-or-more child segments, plus the list-lifting count along the path. The carrier extension:

- A new sealed type `PathExpr` under `no.sikt.graphitron.rewrite` (alongside `ArgBindingMap`) with two arms: `PathExpr.Head(String name)` for today's bare-name case (preserves R53 wire-compat for every existing fixture) and `PathExpr.Step(PathExpr parent, String fieldName, boolean liftsList)` for each `.<name>` segment. The boolean records whether the schema type at that depth was list-shaped, so list-lifting is precomputed and the emitter never re-asks the GraphQL schema. A leaf-resolution helper produces the final Java type by walking the segment chain, applying the same lifters R53 already applies (nodeId → `XRecord`, table-leaf → row record, scalar → mapped Java type), wrapped in a `List<>` for each `liftsList` segment.
- `ArgBindingMap.byJavaName` retypes from `Map<String, String>` to `Map<String, PathExpr>`. The R53 identity entries become `PathExpr.Head(argName)`. Override entries that today produce `String` produce `PathExpr.Head` (single-segment override) or a `Step` chain (path override).
- Failure shape. `ArgBindingMap.Result` gains a third arm `Result.PathRejected(String message)` for structural rejections produced while resolving a `PathExpr` against the slot's input type; the existing `UnknownArgRef` arm continues to cover "head segment names a slot that doesn't exist." Per the rewrite-design-principles "Builder-step results are sealed" rule, every distinct rejection mode is its own arm rather than a string flag. The structural rejections carried by `PathRejected`:
  - segment names a field that does not exist on the input type at that depth ; pre-fill the closest match via `BuildContext.candidateHint` per the `Error quality` emitter convention;
  - intermediate segment is a scalar, enum, union, or interface (cannot walk into);
  - leaf type cannot be lifted to the Java parameter's type (the same shape R53 already rejects, with the path attached for context).
- Failure precedence (extends R53's `lookupError > argMappingError`): parse-time errors from the rewritten selection parser preempt structural rejections, which preempt the post-reflection per-parameter type-mismatch error. The implementer adds `pathError` to the `ExternalRef` / `ConditionDirective` carriers next to the existing `argMappingError` slot.

The `PathExpr` carrier is an instance of "Sub-taxonomies for resolution outcomes" (rewrite-design-principles): the value side of `byJavaName` previously meant "GraphQL slot name" and the path-expression case can't be expressed as a string without reintroducing a parallel parser at every consumer.

## Touchpoints

The path-expression wire-through threads through every call site R53 already wired:

- `ArgBindingMap.parseArgMapping` retires; the rewritten `selection/` parser replaces it. `ArgBindingMap.of(Set<String>, Map<String, PathExpr>)` retypes accordingly.
- `FieldBuilder.parseExternalRef` (the seam at lines around `ArgBindingMap.parseArgMapping(rawArgMapping)`) feeds the new parser and stores `Map<String, PathExpr>` on `ExternalRef`.
- `BuildContext.resolveConditionRef` and `BuildContext.buildInputFieldCondition` parse via the new parser and call `ArgBindingMap.of(...)` with the slot set in scope.
- `ServiceCatalog.reflectServiceMethod` and `ServiceCatalog.reflectTableMethod` consume the `PathExpr` value side and apply the leaf-type lifter (which already exists for the head-only case) under any `List<>` wrappers.
- `ConditionResolver.resolveArg` and `ConditionResolver.resolveField` ride the same parser path; no fork from the service / `@tableMethod` arms.
- The five `parseExternalRef` structural-inertness rejections R53 introduced (`@externalField`, `@record` × 2, `@enum`) keep their existing rejection shape; path expressions don't change *whether* `argMapping` is allowed at those sites, only what the right-hand side may contain.

The R53 changelog enumerates the same seven sites under their R53-era names (`resolveServiceField`, the two `@tableMethod` arms, `buildArgCondition`, `buildFieldCondition`, `BuildContext.resolveConditionRef`, `buildInputFieldCondition`); since lifted out of `FieldBuilder` per the "Builder-step results are sealed" principle, the current names are `ServiceDirectiveResolver`, the two arms in `TableMethodDirectiveResolver`, `ConditionResolver.resolveArg`, `ConditionResolver.resolveField`, `BuildContext.resolveConditionRef`, and `BuildContext.buildInputFieldCondition`. They are the canonical list and this item touches all of them.

## Tests

Per the "Pipeline tests are the primary behavioural tier" principle, the feature earns coverage at every tier R53 covered:

- *Unit (`ArgBindingMapTest` plus a new `GraphQLSelectionParserTest` extension):* dot-path with one segment, two segments, three segments; each `[X]` projection step; structural rejection through scalar / enum / union / interface; head segment naming a non-existent slot; leaf-type lifter applied under list wrappers (`@nodeId` leaf inside two list segments); two argMapping entries resolving to the same leaf path (the R53 same-slot rule); duplicate Java target across path-bearing entries; whitespace and text-block input parity with R53's parser-input fixtures.
- *Pipeline (`GraphitronSchemaBuilderTest`):* the Relay-mutation shape from this item's trigger (Java signature `(KvotesporsmalRecord, KvotesporsmalAlgoritmeRecord)`, schema input wrapper with two `@nodeId`-tagged sub-fields) classifies cleanly; sibling cases for an `argMapping` walking through a scalar, walking into a list (single and double `[X]`), and binding two Java params to two paths under the same wrapper.
- *Execution (`GraphQLQueryTest`):* a sakila or admissio-shaped fixture with a service method that takes two typed parameters bound to nested input fields; round-trips end-to-end through PostgreSQL. Models on the `filmsByServiceRenamed` precedent established by R53 (`graphitron-sakila-service/.../SampleQueryService.java`, `graphitron-sakila-example/.../GraphQLQueryTest.java:queryServiceTable_filmsByServiceRenamed_overrideBindsArgToDifferentlyNamedJavaParam`).
- *Compilation (`mvn compile -pl :graphitron-sakila-example -Plocal-db`):* the existing tier verifies that the emitted code typechecks against the real jOOQ catalog, including the `List<List<XRecord>>` shapes path expressions can produce. No new assertions; the tier fires automatically.

## Error-message extension

R59 sharpened `ServiceCatalog`'s parameter-mismatch messages with concrete `argMapping: "<javaParam>: <graphqlArg>"` suggestions. Extend the same machinery so the rejection mentions path expressions:

- **Floor:** every parameter-mismatch rejection that already prints an `argMapping` example also mentions that the right-hand side may be a dot-path into a nested input field, so an author who hits the error has a pointer to the capability without having to find it in the docs.
- **Stretch:** when the unmatched Java parameter's type matches a reachable nested path under one of the available slots **unambiguously**, pre-fill the path in the suggestion (e.g. `argMapping: "kvotesporsmal: input.kvotesporsmalId"`). If multiple paths reach a compatible leaf, fall back to the floor hint rather than guess.

## Out of scope for this item

- Subselection blocks (`input { id, name }`); revisit as a follow-up once the dot-path lands.
- Methods / runtime transforms (Apollo's `->first`, `->match`, etc.).
- Renaming or stabilising the directive surface.

## Implementation findings (mid-flight, requesting a second opinion)

Phase A landed at trunk (b3f85cd) and consists of:

- `PathExpr` sealed carrier with `Head` / `Step` arms (`be6b8f7`).
- `ArgBindingMap.byJavaName` retyped from `Map<String, String>` to `Map<String, PathExpr>` (`b3f85cd`). Single-segment Heads only, behaviour-equivalent to R53. Full graphitron unit suite (1320 tests) green.

While doing Phase A, four seam decisions emerged that the original spec leaves implicit and that the next reviewer should weigh in on:

1. **Split `ArgBindingMap.of(...)` into parse-side and resolve-side.** Today's `of(Set<String> graphqlArgNames, Map<String, String> overrides)` only knows slot *names*, not slot *types*. To populate `Step.liftsList` and to reject "walk through scalar / enum / union / interface", the factory needs the GraphQL input type backing each slot. Two shapes available:
   - **Single signature**, take `Map<String, GraphQLInputType> slotTypes` instead of `Set<String>`. Simpler call-site change, but conflates parse-time and schema-walk concerns in one factory.
   - **Two layers**: `parseEntries(raw) → Map<String, List<String>>` (pure syntactic, still in `selection/`), then `ArgBindingMap.resolve(slotTypes, segments) → Result` (schema-aware, walks input types and decides `liftsList`). Cleaner separation; matches the spec's "one parser, two binders" framing for R69 reuse.
   Recommendation: **two layers**. Then R69 reuses `parseEntries(...)` directly without inheriting any of R84's schema-walk logic.

2. **`ParamSource.Arg.graphqlArgName: String` is the runtime carrier that needs a shape change.** Three downstream emitters consume it (`ArgCallEmitter`, `TypeFetcherGenerator`, `EnumMappingResolver` for the `<FIELD>_<ARG>_MAP` field name). The spec doesn't pick a shape. Two options:
   - Replace with `PathExpr path`. Emitters call `path.headName()` for the head (today's behaviour) and walk segments when present.
   - Replace with a sealed `ArgExtraction { HeadOnly(String name) | PathWalk(PathExpr path) }`, which lets the no-path emit stay literally identical and isolates path-walking emit code in its own arm.
   Recommendation: **`PathExpr path` directly.** The single-segment Head case is cheap to emit (the existing `env.getArgument(headName())` shape), and the sealed split would just bookkeep the same distinction the `PathExpr.isHead()` predicate already exposes. `EnumMappingResolver`'s `<FIELD>_<ARG>_MAP` key uses the head name; that survives unchanged.

3. **Parsing of dotted RHS happens at parse time, not at resolve time.** Today's `parseArgMapping("kvotesporsmal: input.kvotesporsmalId")` lands as override `{kvotesporsmal -> "input.kvotesporsmalId"}`, and `ArgBindingMap.of` rejects `"input.kvotesporsmalId"` as an unknown slot via `UnknownArgRef`. So R84 must split the dotted RHS at parse time (head + tail segments) before the slot-name check fires; otherwise dotted overrides would dead-end at the existing typo guard. This isn't optional — it's load-bearing for the feature working at all.

4. **R69 coupling is light.** R69 (currently Backlog) declares `@experimental_constructType(selection: ...)` but has no live consumer of the `selection/` parser yet. R84's "rewrite to serve both" promise reduces to: extract a syntactic-only entry point in `selection/` that emits comma-separated `key: dotted.path` entries as `Map<String, List<String>>`. R69 binds that output to columns (each path is a column reference); R84 binds it to GraphQL input-type walks. No cross-coupling beyond shared parse output.

### Recommended phase staging for the rest

- **Phase B — Pure parse extraction.** Add `selection.parseEntries(raw) → Map<String, List<String>>` (uses existing Lexer/Token/TokenKind). Re-implement `ArgBindingMap.parseArgMapping` to call it, returning segment chains. `parseArgMapping`'s existing test cases get re-aimed onto segment-chain assertions; no semantic change for non-dotted inputs. *No call sites change yet.* Wire-compat: a single-segment override still produces a one-element segment chain.
- **Phase C — Resolve-side `liftsList` and `PathRejected`.** Change `ArgBindingMap.of` signature to take `Map<String, GraphQLInputType> slotTypes` (or equivalent oracle); walk segments against the input-object hierarchy; populate `PathExpr.Step.liftsList`; emit `Result.PathRejected` for the four structural-rejection shapes the spec enumerates. Add `pathError` slot on `ExternalRef` and `ConditionDirective`. Update each call site (`ServiceDirectiveResolver`, `TableMethodDirectiveResolver`, `ConditionResolver.resolveArg/resolveField`, `BuildContext.resolveConditionRef/buildInputFieldCondition`) to pass slot types.
- **Phase D — Runtime emit.** Change `ParamSource.Arg.graphqlArgName: String` to `path: PathExpr`. Update the three downstream emitters to walk segments at runtime: intermediate-null short-circuit, list element-wise traversal for `liftsList=true` segments, leaf-lifter under `List<>` wrappers. Compilation-tier covers the typed shapes (`List<List<XRecord>>`).
- **Phase E — Tests at four tiers.** Pipeline test in `GraphitronSchemaBuilderTest` (Relay-mutation shape, scalar/list path siblings); execution test in `GraphQLQueryTest` modelled on `queryServiceTable_filmsByServiceRenamed_overrideBindsArgToDifferentlyNamedJavaParam`; unit-tier extensions in `ArgBindingMapTest` and `GraphQLSelectionParserTest`. Compilation tier fires automatically.
- **Phase F — Error-message extension.** Floor (mention dot-path option in every parameter-mismatch suggestion) and stretch (pre-fill an unambiguous reachable path in the suggestion).

Each phase is an independent commit and each preserves R53 wire-compat for non-dotted inputs. The first commit that *changes observable behaviour* for path-bearing input is Phase D.

### Open questions for the reviewer

- **Phase staging granularity.** Is six phases too many? Phases B+C could collapse if "parse without `liftsList`, then patch `liftsList` during resolve" is acceptable. The split-layer phasing exists mainly to keep diffs reviewable; concrete review preference welcome.
- **Phase D pre-work.** Does plumbing `PathExpr` through `ParamSource.Arg` warrant its own commit, or fold it into Phase D's emit changes? The carrier change touches every read site; the runtime walking touches a subset. Splitting them keeps the runtime-emit diff focused on path semantics, but adds a no-op intermediate commit.
- **R69 dependency record.** R84 currently declares `depends-on: []`. After Phase B, R69 begins consuming `selection.parseEntries(...)` for free. Should R69's plan body get a one-line note ("parser entry point lands in R84") to record the inverse dependency, or is the implicit one-parser-two-binders shared assumption strong enough?

