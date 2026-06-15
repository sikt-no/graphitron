---
id: R312
title: "Thread CompositeDecodeHelperRegistry through inline/split reference-field filter emitters"
status: In Progress
bucket: correctness
priority: 2
theme: nodeid
depends-on: []
created: 2026-06-15
last-updated: 2026-06-15
---

# Thread CompositeDecodeHelperRegistry through inline/split reference-field filter emitters

When a filter input used as the `filter:` argument of a **reference/list child field** (e.g. `soknader(filter: HentSoknadInput): [Soknad!] @reference(...)`) mixes `@nodeId`-decoded fields with `@condition` fields, codegen crashes. `@nodeId`-decoded filter args reach `ArgCallEmitter.buildNodeIdDecodeExtraction(...)` through call sites that pass a **null** `CompositeDecodeHelperRegistry`; that method throws by design when the registry is null (the decode must be lifted into a per-class helper, and only `QueryConditionsGenerator` currently owns and drains a registry). This regressed in the RC10+ rewrite; it worked on 9.3.0. The valid schema has no schema-only workaround.

This is not only an emitter fix: it is a *validator-mirror* gap. `InlineTableField`, `LookupTableField`, and `ColumnReferenceField` are members of `PROJECTED_LEAVES` (`TypeFetcherGenerator.java:253-258`), i.e. the dispatch partition asserts they are fully emittable. Both crashes fire at generator-run time, not validate time, because the classifier accepts a filter+path shape the emitter has no working arm for. R312 closes that gap from the emitter side (it makes the shapes emittable) rather than narrowing the classifier, and pins the result with pipeline tests so the partition's "fully implemented" claim stays honest.

## Reproduction

Reproduced against the current branch with a throwaway pipeline test (since deleted), driving SDL through `TypeFetcherGenerator.generate` / `TypeClassGenerator.generate`. The `nodeidfixture` jOOQ catalog supplies the `bar` (composite-key `@node`, FK `bar_id_1_fkey` → `baz`) and `baz` (single-key `@node`) tables.

**Part A, inline path** (plain `@reference` list field):

```graphql
type Bar implements Node @table(name: "bar") @node { id: ID! name: String }
input BarFilter @table(name: "bar") {
    ids: [ID!] @nodeId(typeName: "Bar")
    cityNames: String @condition(condition: {className: "...TestConditionStub", method: "argCondition"}, override: true)
}
type Baz implements Node @table(name: "baz") @node {
    id: ID!
    bars(filter: BarFilter): [Bar!] @reference(path: [{key: "bar_id_1_fkey"}])
}
type Query { baz: Baz }
```

Crashes with `IllegalStateException: NodeId-decode extraction must be lifted ... none was supplied for decode 'decodeBar'` at `ArgCallEmitter.buildNodeIdDecodeExtraction` (`ArgCallEmitter.java:380`), reached from `InlineTableFieldEmitter.buildInnerSelect` (`:153`) via `TypeClassGenerator.emitSelectionSwitch`.

**Part A, split path**: the same schema with `bars: [Bar!] @splitQuery @reference(...)` crashes identically at `SplitRowsMethodEmitter.buildListMethod` (`:742`), via `TypeFetcherGenerator.generateTypeSpec` (`:412`).

**Part B** (empty-join-path, condition-only): a same-table reference (start table == target table, so `BuildContext.parsePath` returns an empty path) whose filter is condition-only:

```graphql
input BarFilter @table(name: "bar") {
    cityNames: String @condition(condition: {className: "...TestConditionStub", method: "argCondition"}, override: true)
}
type Bar implements Node @table(name: "bar") @node {
    id: ID! name: String
    related(filter: BarFilter): [Bar!]
}
type Query { bar: Bar }
```

Crashes with `IndexOutOfBoundsException: Index -1 out of bounds for length 0` at `InlineTableFieldEmitter.buildArm` (`:65`), on `aliases.get(aliases.size() - 1)` over an empty alias list (`JoinPathEmitter.generateAliases` returns an empty list for an empty path).

Two notes from the reproduction that narrow the scope from the original RC14 report:

* The literal report Part B (a *real-FK* `@reference` + condition-only filter) already generates cleanly on the current branch; only the *empty-join-path* variant still crashes. The provenance caveat in the report (line numbers drifted between RC14 and the branch) covers this.
* `@condition`-only inline and split references (with a real FK) generate fine today; the registry is needed only when a `@nodeId`-decoded leaf is present.

## Design

### Part A — own-and-drain the decode registry at the two class-assembly points

`QueryConditionsGenerator` already implements the lifecycle this needs: construct a `CompositeDecodeHelperRegistry` (`QueryConditionsGenerator.java:62`), thread it into `ArgCallEmitter.buildCallArgs(..., registry)`, then **drain** the lifted private-static decode helpers onto the class it builds (`:74`, `registry.emit().forEach(classBuilder::addMethod)`). The registry-aware `buildCallArgs` / `buildArgExtraction` overloads already exist (`ArgCallEmitter.java:252-271`), so Part A is a *wiring* fix, not a new contract: replace the no-registry `buildCallArgs(ctx, params, className, alias)` call with the registry overload at each reference-field filter site, and drain onto the class that hosts that site.

The two host classes (the call site's decode helper is a private static method that must live on the class the call site is emitted into):

* **`<Type>` class (`TypeClassGenerator`).** Hosts the inline emitters' filter sites: `InlineTableFieldEmitter` (`:153`) and `InlineLookupTableFieldEmitter` (`:223`). The call site is inside the class's static `$fields` method, built by `build$FieldsMethod` → `emitSelectionSwitch`. `emitSelectionSwitch` recurses through `NestingField` (`TypeClassGenerator.java:295`), and nested inline fields share the parent type's class (the same hoisting `collectNestedLookupFields` already relies on at `:143-148`), so **one** registry per `<Type>` class, threaded through the recursion, covers nested inline fields; drain once in `buildTypeSpec` after the `$fields` method is added (`:138`).
* **`<Type>Fetchers` class (`TypeFetcherGenerator`).** Hosts the split rows-method sites (`SplitRowsMethodEmitter.buildListMethod:742`, `buildSingleMethod:857`, `buildConnectionMethod:1052`, reached via the `buildFor*` entry points) and the lookup-rows site (`buildQueryLookupRowsMethod`, `:4071`). One registry per `generateTypeSpec` call, threaded into those emitters, drained onto `builder` (`:340`) at the end of the field loop.

The inline emitters today pass a *throwaway* `new TypeFetcherEmissionContext()` into `buildCallArgs` (`InlineTableFieldEmitter.java:153`, `InlineLookupTableFieldEmitter.java:223`); they must instead accept and thread a `CompositeDecodeHelperRegistry` parameter. The split/lookup emitters already receive the per-class `ctx`; they take the registry the same way.

**Keep the `ArgCallEmitter.java:372-383` throw.** It is the emitter's correct assertion that a NodeId decode must have a host class to drain into. After Part A its `registry == null` branch is reachable only from a wiring bug, never from a valid schema. The one surviving production caller that passes `null` is the `@service`/`@tableMethod` method-backed path (`emitArgExpression` → `buildArgExtraction(..., null)`, `:243-249`); that path's leaves are `InputBean` / `NodeIdDecodeRecord` (decoded inside `create<Bean>` helpers), never `NodeIdDecodeKeys`, so it cannot reach the throw. Leave the `null` overload as the backstop and say so here so a reader does not read the surviving `null` as an oversight.

**Registry lifecycle — avoid a third silently-droppable drain.** A forgotten drain is *silent*: the call site emits `decodeBarKey(...)` referencing a helper that was never added to the class, surfacing as a consumer compile error in `graphitron-sakila-example`, not a generator failure. This is a worse failure locus than the current loud throw. Rather than hand-roll the construct-thread-drain dance a third and fourth time, extract a single bracketing helper (e.g. `CompositeDecodeHelperRegistry.collectInto(TypeSpec.Builder, Consumer<CompositeDecodeHelperRegistry>)`) that creates a registry, runs the field-loop body with it, and drains onto the builder unconditionally — construct and drain co-located so a site cannot register without draining. `QueryConditionsGenerator` adopts the same helper, collapsing the existing hand-rolled copy so there is one drain implementation, not three. The `graphitron-sakila-example` cross-module compile (CompilationTier) stays as the backstop that would catch a regressed drain.

### Part B — extend the existing standalone-path precedent to the sibling emitters

Part B is not a new semantic. An empty `joinPath` is the model's *standalone-lookup shape*: `ParentCorrelation.checkCarrierInvariant` (`ParentCorrelation.java:52-71`) enforces `parentCorrelation == null` exactly when `joinPath.isEmpty()`, uniformly across every carrier variant. `InlineLookupTableFieldEmitter` already honours this shape today — it synthesizes a single terminal alias for the empty path (`:81-101`) and seeds its WHERE with `DSL.noCondition()` (`:202-203`). R312 extends that tested pattern to the three sibling emitters that currently assume `≥1` hop:

* `InlineTableFieldEmitter.buildArm` (`:65`) and `buildInnerSelect`: when `path.isEmpty()`, synthesize one alias for the terminal table (`tf.returnType().table()`, a `TableRef` carrying `tableClass`/`constantsClass`/`javaFieldName`), declare it the same way the per-hop loop declares aliased tables (`:79-82`), skip the JOIN chain (the `i = size-1 .. 1` loop is already empty for an empty path), and **short-circuit before the exhaustive `parentCorrelation` switch** (`:137-142`) — that switch has only `OnFkSlots`/`OnConditionJoin` arms and the correlation is `null` in this shape, so the guard seeds the WHERE with `DSL.noCondition()` and `.and(...)`s the user filters, exactly as the lookup emitter does. It does not add a third switch arm.
* `InlineColumnReferenceFieldEmitter` (`:55-56`): the same empty-path guard for its single-column projection.
* `SplitRowsMethodEmitter.emitParentInputAndFkChain` (`:197-198`): a defensive guard for the empty-path site. The split path is classifier-guarded against empty join paths today (a comment-only contract); the guard makes a regression fail loudly rather than as an opaque `Index -1`.

### Reachability after the fix

Both crash conditions become unreachable for the `PROJECTED_LEAVES` shapes: Part A makes every projected-leaf filter site thread a non-null registry, so no valid schema reaches the `ArgCallEmitter` throw; Part B gives every empty-join-path emitter site a standalone arm. No sub-shape is deferred — both the decode-bearing and the condition-only filters, on both real-FK and empty-join-path references, are handled — so nothing new is added to `STUBBED_VARIANTS`. The pipeline tests below pin this so the dispatch partition's "fully implemented" assertion stays honest.

### Rejected alternatives

* **Reject the empty-join-path shape at classification.** Would make the same model state (`joinPath.isEmpty()` ⇒ null correlation) legal for `LookupTableField` (which emits it today) and illegal for its siblings, contradicting the uniform `checkCarrierInvariant` contract. The standalone emission is the intended reading.
* **Relax the `ArgCallEmitter:372` null-registry throw to an inline fallback.** The throw encodes a real invariant (decode must have a drain target); softening it re-admits the R260 inline expression-trick form the registry exists to replace, and hides wiring bugs.
* **Hang the registry on `TypeFetcherEmissionContext`** (the existing per-class scratchpad already drained for the `graphitronContext` helper). Tempting because `ctx` is already threaded through `buildCallArgs`, but it overloads a fetcher-emission carrier with a concern `TypeClassGenerator` (which builds type classes, not fetchers) would have to instantiate; the bracketing helper keeps the registry's ownership with the class-assembly point that owns the builder, which is the correct SRP seam.

## Implementation

* `CompositeDecodeHelperRegistry.java` — add the static bracketing helper `collectInto(TypeSpec.Builder, Consumer<CompositeDecodeHelperRegistry>)` (construct → run body → `emit().forEach(builder::addMethod)`).
* `ArgCallEmitter.java` — no signature change; the registry overloads already exist. (The no-registry overload stays for the `@service` method-backed path.)
* `InlineTableFieldEmitter.java` — add a `CompositeDecodeHelperRegistry` parameter to `buildSwitchArmBody` → `buildArm` → `buildInnerSelect`; use the registry overload at `:153`; add the empty-`path` standalone arm (alias synthesis + pre-switch `DSL.noCondition()` seed).
* `InlineLookupTableFieldEmitter.java` — add the registry parameter through to `:223`; use the registry overload. (Its empty-path arm already exists.)
* `InlineColumnReferenceFieldEmitter.java` — add the empty-`path` guard at `:55-56`. (No `buildCallArgs` site, so no registry parameter.)
* `SplitRowsMethodEmitter.java` — add a `CompositeDecodeHelperRegistry` parameter to the `buildFor*` entry points and thread it into `buildListMethod`/`buildSingleMethod`/`buildConnectionMethod`; use the registry overload at `:742`/`:857`/`:1052`; add the defensive empty-path guard at `:197-198`.
* `TypeClassGenerator.java` — in `buildTypeSpec`, wrap the `build$FieldsMethod` call in `CompositeDecodeHelperRegistry.collectInto(builder, registry -> ...)`, threading the registry through `build$FieldsMethod` → `emitSelectionSwitch` (including the `NestingField` recursion at `:295`) into the inline emitters.
* `TypeFetcherGenerator.java` — in `generateTypeSpec`, wrap the field loop in `collectInto(builder, registry -> ...)`, threading the registry into the `SplitRowsMethodEmitter.buildFor*` calls and `buildQueryLookupRowsMethod`.
* `QueryConditionsGenerator.java` — refactor the hand-rolled construct (`:62`) + drain (`:74`) to use `collectInto`, so there is one drain implementation.

## Tests

Pipeline tier is primary (SDL → classified model → `TypeSpec`), using the `nodeidfixture` catalog as `QueryConditionsPipelineTest` does:

* **Part A, inline** (`TypeClassGeneratorTest` or a new sibling): the mixed `@nodeId` + `@condition(override: true)` `BarFilter` on an inline `@reference` list field generates a `<Type>` `TypeSpec` without throwing, and the `<Type>` class carries a lifted `private static` `decodeBar*` helper (assert the helper method's presence/modifiers, **not** emitted body strings — code-string body assertions are banned at every tier).
* **Part A, split** (`SplitTableFieldPipelineTest`): the same filter on a `@splitQuery @reference` field generates the `<Type>Fetchers` `TypeSpec` without throwing and carries the lifted helper.
* **Part B, inline** (the reproduced shape): the empty-join-path same-table `related(filter:): [Bar!]` with a condition-only filter generates without throwing.
* **Part B, condition-only real-FK** regression guard: a condition-only `BarFilter` on a real-FK `@reference` (and `@splitQuery`) field generates without throwing (green today; guards against a regression from the Part A wiring).
* **Compilation tier** (`graphitron-sakila-example`): a fixture exercising a mixed `@nodeId` + `@condition` reference filter so the generated decode helper is compiled against real jOOQ — the cross-module backstop for a forgotten drain.

## References

* Reproduction is grounded in the current branch, not just the RC14 bytecode report; the report's provenance caveat (drifted line numbers) explains why the literal Part B no longer reproduces with a real FK.
* Precedent: `QueryConditionsGenerator.java:62,74` (own-and-drain); `InlineLookupTableFieldEmitter.java:81-101,202-203` (existing empty-join-path standalone emission); `ParentCorrelation.java:52-71` (`checkCarrierInvariant`, the standalone-shape invariant).
* Dispatch partition asserting these leaves emittable: `TypeFetcherGenerator.java:253-258` (`PROJECTED_LEAVES`); per-shape deferral mechanism, if ever needed: `STUBBED_VARIANTS` (`:293-294`).
* Registry-aware emission surface: `ArgCallEmitter.java:252-271` (overloads), `:360-389` (`buildNodeIdDecodeExtraction`, the throw at `:372-383`); `CompositeDecodeHelperRegistry.java` (per-class collector).
