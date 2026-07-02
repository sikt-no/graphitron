---
id: R424
title: "Inline @reference field reads its filter/pagination args from the top-level env, silently dropping them"
status: In Review
bucket: bug
priority: 7
theme: structural-refactor
depends-on: []
created: 2026-07-02
last-updated: 2026-07-02
---

# Inline @reference field reads its filter/pagination args from the top-level env, silently dropping them

> Route runtime argument reads at the two inline emission sites
> (`InlineTableFieldEmitter`, `InlineLookupTableFieldEmitter`) through the in-scope
> `SelectedField` local (`<sf>.getArguments()`) instead of `env.getArgument(...)`,
> via a small sealed `ArgumentValueSource` threaded as a parameter through
> `FkTargetConditionEmitter.emitTerm` → `ArgCallEmitter.buildCallArgs` →
> `buildArgExtraction`. Root/split call sites keep the `Env` variant (byte-identical
> output); the inline sites pass `FromSelectedField(sfName)`. Covers the filter-condition
> path, the inline `first` pagination limit, and the `JooqConvert`+list pre-lift.
> Behaviour is pinned at the execution tier against fixtures the existing `@splitQuery`
> coverage does not reach.

## Problem

An inline (non-`@splitQuery`) `@reference` list field with arguments silently ignores
those arguments at runtime. The generated condition and decode logic are correct, but
the argument *value* is read from the wrong `DataFetchingEnvironment`: the emitted code
calls `env.getArgument("filter")`, where `env` is the top-level operation field's
environment (the ancestor fetcher that builds the whole correlated-`multiset` tree), not
the inline field's own environment. The ancestor has no such argument, so
`env.getArgument(...)` returns `null`, the filter condition collapses to
`noCondition()`, and the field returns unfiltered rows. This is a data-correctness bug:
a client that passes a narrowing filter (or, worse, an authorization-relevant id filter)
gets back rows it did not ask for, with no error.

### Reproducer

Field `Studiekurv.kladder(filter: HentKladderInput): [Soknadskladd!]!` with
`@reference(path: [...])` (no `@splitQuery`), where `HentKladderInput` carries
`@nodeId` filter fields. Querying `megSomSoker { studiekurv { kladder(filter: { opptakId: ["...opptak 2..."] }) { id } } }`
against seed data whose only kladd belongs to opptak 1 returns that kladd anyway
(expected: empty). The sibling field `Soker.soknader(filter:)` behaves correctly for the
identical filter shape *only because* it is `@splitQuery`: `@splitQuery` gives it a
dedicated fetcher whose `env` genuinely is the field's own environment, so
`env.getArgument("filter")` resolves.

Discovered via an opptak-subgraph reproducer (`MegSomSokerQueryIT.kladderOpptakIdFilterIsIgnored`)
on graphitron 10.0.0-RC23.

### Root cause

All condition-argument emission funnels through one chain:
`FkTargetConditionEmitter.emitTerm` → `ArgCallEmitter.buildCallArgs` →
`ArgCallEmitter.buildArgExtraction` (a switch over the sealed `CallSiteExtraction`).
Every arm that reads a runtime argument value emits `env.getArgument(...)`. That is
correct at every call site *except* the two inline emitters, which emit inside the
generated `<Type>.$fields(sel, table, env)` method where `env` belongs to the ancestor
fetcher. The field's own arguments live on the in-scope `SelectedField` local (already
threaded into both emitters as `sfName` for `getSelectionSet()`);
`SelectedField.getArguments()` returns `Map<String, Object>` with the same value shape
`env.getArgument` yields (input objects arrive as nested `Map`s), so the downstream
nested-Map traversal and decode helpers are unaffected by the source swap.

Call-site classification (verified against every `emitTerm`/`buildCallArgs` consumer):

| Call site | `env` provenance | Verdict |
|---|---|---|
| `QueryConditionsGenerator` | root/`@splitQuery` fetcher's own env | correct, keep `Env` |
| `TypeFetcherGenerator` (fetcher bodies) | field's own env | correct, keep `Env` |
| `MultiTablePolymorphicEmitter` | field's own env | correct, keep `Env` |
| `SplitRowsMethodEmitter` | DataLoader-backed fetcher's own env (documented at `SplitRowsMethodEmitter.java:805`) | correct, keep `Env` |
| `InlineTableFieldEmitter` | ancestor env inside `$fields` | **wrong**, pass `FromSelectedField(sfName)` |
| `InlineLookupTableFieldEmitter` | ancestor env inside `$fields` | **wrong**, pass `FromSelectedField(sfName)` |

The same defect hits inline pagination: `InlineTableFieldEmitter.java:210` emits
`env.getArgument("first")` for the `first` limit on an inline list field.
`InlineLookupTableFieldEmitter` has no analogous pagination read (verified: its only
`env` uses are threading `env` onward into nested `$fields` calls, which is correct —
each nested level re-derives its own `SelectedField`).

## Design

### `ArgumentValueSource`, threaded as a parameter

A small sealed value type in the generators package (an emission helper, sibling of
`CompositeDecodeHelperRegistry`; not a model type — the env-vs-SelectedField fork varies
by emission scope, not by model content, so per generation-thinking it stays out of the
model):

```java
sealed interface ArgumentValueSource {
    record Env() implements ArgumentValueSource {}
    record FromSelectedField(String sfLocal) implements ArgumentValueSource {}
}
```

Threaded as an explicit parameter through `FkTargetConditionEmitter.emitTerm` /
`ArgCallEmitter.buildCallArgs` / `buildArgExtraction`. Not carried on
`TypeFetcherEmissionContext`: the ctx is a class-scoped scratchpad, while the argument
source is emission-point-scoped — `NestingField` recursion declares a fresh
`SelectedField` local per depth, so the source changes per recursion depth exactly like
the already-threaded `sfName`. `FromSelectedField(sfName)` packages that existing thread
with its meaning. A sealed two-variant type is preferred over a nullable `String sfName`
parameter (the nullable-sentinel form is the tri-state smell the sub-taxonomy principle
warns against).

`Env` emits `env.getArgument(name)` (status quo, byte-identical output at all root/split
sites). `FromSelectedField(sf)` emits `sf.getArguments().get(name)` with a cast where the
env form relied on generic-method target-typing (below).

### Per-arm audit of `ArgCallEmitter`

Every `env.getArgument` site in `ArgCallEmitter`, classified. "Inline-reachable" means
the arm can appear in a `WhereFilter.callParams()` emitted by the two inline emitters.

Inline-reachable arms — route the runtime read through the source:

- `Direct` (`:275`): the `Env` form emits a bare, *uncast* `env.getArgument(name)` and
  relies on generic-method target-typing; unchanged. `FromSelectedField` cannot target-type
  (`Map.get` is statically `Object`), so it emits `(<RawParamType>) sf.getArguments().get(name)`,
  deriving the cast target with the `rawComponent(...)` helper already used by the
  `NestedInputField` leaf path (`:502`). Scalar casts are checked; a generic param type
  (e.g. `List<String>`) makes the cast unchecked, see the suppression note below. This is
  the first cast the `Direct` arm has ever emitted, so the byte-identical invariant at the
  `Env` sites holds only if the suppression predicate stays `FromSelectedField`-scoped
  (see below).
- `EnumValueOf` (`:279`): `FromSelectedField` reads the wire value once via
  `(String) sf.getArguments().get(name)` in both the null-guard and the `valueOf` call.
- `JooqConvert` scalar (`:296`): swap the wire read; no cast needed (`DSL.val` takes
  `Object`; null in, null out).
- `JooqConvert` + list (`:295`): the arm reads a pre-lifted `<name>Keys` local that only
  `QueryConditionsGenerator` (`:146-153`) and `MultiTablePolymorphicEmitter` (`:1235`)
  declare today. An inline filter carrying this shape currently emits a reference to an
  undeclared local (generated code fails the consumer's compile). Fix by parity: both
  inline emitters gain the same pre-lift loop into their statement builder (before the
  WHERE, alongside the `declareAliases` statements), with the read routed through the
  source. Nothing in classification keys converter-backed list args to `@splitQuery`,
  so the shape is inline-reachable in principle even if no fixture exercises it yet.
- `NestedInputField` root (`:479`): swap the depth-0 read
  (`sf.getArguments().get(outer)`); no cast (the existing `instanceof Map<?, ?>` guard
  owns the runtime shape). The `liftedOuters` dedup is populated only by
  `QueryConditionsGenerator` and `MultiTablePolymorphicEmitter` (the latter via
  `QueryConditionsGenerator.computeLiftedOuters`); both inline sites pass `null` for it
  today, so the lifted path is inert for inline and stays unchanged.
- `NodeIdDecodeKeys` top-level wire (`:303`): swap the wire expression; no cast (the
  lifted decode helper's parameter is `Object wire`,
  `CompositeDecodeHelperRegistry:128`).
- `ContextArg` (`:283`): stays env-based under *both* variants — GraphQL context is
  request-scoped, so the ancestor `env` is legitimately correct. Document this on the arm.

Never-inline arms — not reachable from inline `WhereFilter.callParams()`; guard, don't route:

- `InputBean` (`:335`) and `JooqRecord` (`:350`): live arms (`JooqRecord` is reached at
  the R311 child-`@service` coordinate, not only at the root, per the comment at
  `ArgCallEmitter.java:307-311`), but they are `@service`/input-bean concepts, never
  inline `@reference` filters. All their producers (root and child-`@service`) keep the
  implicit `Env`, so `FromSelectedField` is never actually threaded here. Make the guard
  a defensive `IllegalStateException` on the `FromSelectedField` branch, matching the
  existing guard discipline at `ArgCallEmitter.java:197-218`, so the wrong form can never
  be silently emitted even if a future caller mis-wires the source.
- `buildMethodBackedCallArgs` family and `buildListAwarePathExtraction` (`:636`): root
  `@service`/`@tableMethod` emission; these entry points never receive a
  `FromSelectedField` source (their signatures keep the implicit `Env`), so no change.
- `NodeIdDecodeRecord`: already throws.

Also inside `$fields` scope but explicitly *not* touched: the recursive
`$fields(sf.getSelectionSet(), alias, env)` calls in both inline emitters thread `env`
onward correctly (each nested level re-derives its own `SelectedField`; `env` is only
needed for context reads). State this invariant in the code so a future pass doesn't
"fix" it.

### Inline pagination

`InlineTableFieldEmitter.java:210` swaps to the source-routed read:
`sf.getArguments().get("first") == null ? Integer.MAX_VALUE : (Integer) sf.getArguments().get("first")`
(checked cast, same shape as today's `(Integer) env.getArgument("first")`). Optionally
lift the read into a local for legibility (see below).

### Unchecked-cast suppression at the `$fields` host

`graphitron-sakila-example` compiles generated output with `-Xlint:all -Werror`, so any
new unchecked cast must be suppressed at the narrowest enclosing member, mirroring
`QueryConditionsGenerator:135-142`: `TypeClassGenerator.build$FieldsMethod` stamps
`@SuppressWarnings("unchecked")` on `$fields` when any inline field's filter params
would emit one under `FromSelectedField`.

The predicate must be **source-aware**, not a blanket widening of today's
`CallParam.emitsUncheckedCast`. That method is source-agnostic and is consumed at the
`Env` sites too (`QueryConditionsGenerator:137`, `MultiTablePolymorphicEmitter:1296`) to
decide *their* `@SuppressWarnings` stamp. The new casts (`FromSelectedField` list-typed
`Direct` params, and the `JooqConvert`+list pre-lift) exist *only* under
`FromSelectedField`: their `Env` counterparts are target-typed and warning-free. So
broadening `emitsUncheckedCast` unconditionally would newly stamp `@SuppressWarnings` on
`QueryConditionsGenerator` / `MultiTablePolymorphicEmitter` methods that carry a list
`Direct` param, changing their output and breaking the byte-identical invariant this plan
pins as a non-goal. Route the source into the predicate (pass `ArgumentValueSource`, or
add a `FromSelectedField`-only companion that the `$fields` host calls) so the `Env`
callers see the unchanged answer. Keep whatever shape it takes model-side so the
`$fields` host and any future host cannot drift.

### Generated-code legibility (optional, implementer's call)

When a single condition call reads several args, `FromSelectedField` re-invokes
`<sf>.getArguments()` per read. Lifting `Map<String, Object> <sf>Args = <sf>.getArguments();`
once per switch arm is a "meaningful local" per the generated-code-readability rule.
Fine to skip if the arm reads one arg.

### Rejected alternative

Synthesizing a per-field `DataFetchingEnvironment` for inline fields: heavyweight,
fights graphql-java's execution model, and unnecessary — the `SelectedField` already
carries the resolved arguments (coerced, variables substituted, defaults applied).

### Merged-field note

The `$fields` switch reads `entry.getValue().get(0)` from
`getFieldsGroupedByResultKey()`. Reading arguments off the first merged field is sound:
GraphQL field merging requires identical arguments for fields sharing a result key.

## Tests

Per the tier rubric (pipeline pins shape, execution pins behaviour; body-string
assertions banned at every tier):

- **Pipeline-tier** (`graphitron`, next to `NodeIdReferenceFilterPipelineTest`): an SDL
  with an inline `@reference` list field carrying a filter argument (a `@nodeId` filter
  field and a plain `@condition` arg) classifies and generates end-to-end; assert on the
  classified model / `TypeSpec` structure (the arm exists, the decode helper lifts onto
  the type class), not on `getArguments()` body strings. Add the `JooqConvert`+list
  inline shape here too, pinning that generation succeeds (pre-R424 it emits an
  undeclared local).
- **Execution-tier** (`graphitron-sakila-example`, `GraphQLQueryTest` siblings): new
  schema fixtures — an inline (non-`@splitQuery`) `@reference` list field with (a) a
  `@nodeId`-bearing filter input, (b) a plain `@condition` scalar arg, and (c) a
  `first: Int` pagination arg. Assert against real PostgreSQL that the filter narrows
  the rows (query data where the unfiltered result would differ — the reproducer shape:
  filter targeting a row that does not exist under the parent must return empty), and
  that `first` limits the inline list. Mirror fixture (a) with the identical filter on a
  `@splitQuery` sibling asserting identical results, pinning inline/split parity.
- **Compilation-tier**: the sakila-example compile (with `-Werror`) covers the new casts
  and the suppression plumbing; the new fixtures make it exercise the changed arms.

## Implementation sites

- New file `generators/ArgumentValueSource.java`: the sealed type.
- `generators/ArgCallEmitter.java`: thread the source through `buildCallArgs` /
  `buildArgExtraction`; per-arm changes and root-only guards per the audit above.
- `generators/FkTargetConditionEmitter.java`: accept and forward the source in
  `emitTerm` / `emitFkTargetExists`.
- `generators/InlineTableFieldEmitter.java`: pass `FromSelectedField(sfName)`; fix the
  `first` read at `:210`; add the `JooqConvert`+list pre-lift.
- `generators/InlineLookupTableFieldEmitter.java`: pass `FromSelectedField(sfName)`;
  add the `JooqConvert`+list pre-lift.
- `generators/TypeClassGenerator.java`: conditional `@SuppressWarnings("unchecked")` on
  `$fields`; document the env-threading-into-nested-`$fields` invariant.
- `model/CallParam.java` (or companion): extend `emitsUncheckedCast` for the
  source-induced casts.
- Root/split call sites (`QueryConditionsGenerator`, `TypeFetcherGenerator`,
  `MultiTablePolymorphicEmitter`, `SplitRowsMethodEmitter`): pass `Env`; output
  byte-identical.
- `graphitron-sakila-example` schema + `graphitron` pipeline tests per the test plan.

## Non-goals

- No change to `@splitQuery` / root fetcher emission (already correct; `Env` output is
  byte-identical).
- No per-field synthetic `DataFetchingEnvironment`.
- No change to `ContextArg` semantics (request-scoped context legitimately reads the
  ancestor env).
- No new validator rejection surface: the one inline-reachable-but-broken shape
  (`JooqConvert`+list) is fixed by pre-lift parity rather than rejected.
