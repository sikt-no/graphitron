---
id: R265
title: NodeId ThrowOnMismatch decode helper emits non-compiling new GraphqlErrorException(String)
status: Ready
bucket: cleanup
priority: 5
theme: model-cleanup
depends-on: []
created: 2026-05-30
last-updated: 2026-06-02
---

# NodeId ThrowOnMismatch decode helper emits non-compiling new GraphqlErrorException(String)

## Problem

graphql-java 25's `GraphqlErrorException` has only a protected builder constructor; there is no
public `GraphqlErrorException(String)`. The correct construction is
`GraphqlErrorException.newErrorException().message(..).build()`.

The NodeId-decode helper emitter still constructs the broken form at two sites, both inside
`CompositeDecodeHelperRegistry.buildHelper`:

- `generators/CompositeDecodeHelperRegistry.java:113` (list throw arm, inside the `.map(key -> ...)`
  chain), and `:131` (scalar throw arm). Both emit `throw new $T($S)` with `$T = GraphqlErrorException`
  and the shared `MISMATCH_MESSAGE` constant (`:46`), which **does not compile**.

This was a five-site bug across two files when first filed. **R260
(`nodeid-decode-emitter-readability`) has since landed** and reshaped the surface: it lifted every
NodeId decode (all arities, skip and throw) out of `ArgCallEmitter`'s inline arms and through
`CompositeDecodeHelperRegistry` into named statement-form helpers, deleting the three former
`ArgCallEmitter` construction sites and the `((Supplier<X>) () -> { throw ...; }).get()` wrapper that
used to surround them. The broken `new $T($S)` construction rode along into the registry helper
unchanged; `ArgCallEmitter` no longer constructs the exception at all. So the live bug is now two
sites in one method, not five across two files.

The same construction is **already correct** at four other sites, which proves the fix and bounds the
scope:

- `generators/LookupValuesJoinEmitter.java:330` (parameterized message: `...for argument '<name>'`)
- `generators/TypeFetcherGenerator.java:2007` and `:2809` (parameterized message: `...input field '<name>'`)
- `generators/InputBeanInstantiationEmitter.java:326` (bare constant message; R195 landed this)

This survived because no test runs `javac` over a `ThrowOnMismatch` arm. The registry's throw bodies
are exercised only by `CompositeDecodeHelperRegistryTest` (a `@UnitTier` test that asserts
`body.contains("graphql.GraphqlErrorException")` at `:88` and `:153`), and that string-shape
assertion is exactly the layer that did not catch the bug: the FQN renders identically in the broken
and the builder form. R195's `InputBeanInstantiationEmitter` site only got the construction right
because it was the first *compile-tested* NodeId throw.

## Reachability (drives the test scope)

The single production producer of `ThrowOnMismatch` is `FieldBuilder.java:1131`, inside the `ID`-arg
classification block. Tracing its guards settles which throw arm a fixture can actually drive through
`javac`:

- **arity-1, non-list, non-`@lookupKey`** `ID` argument: produces `ScalarArg.ColumnArg(ThrowOnMismatch,
  isLookupKey=false)`, which flows through the condition path
  (`FieldBuilder` `BodyParam.Eq` -> `CallParam` -> `GeneratedConditionFilter` -> `QueryConditionsGenerator`
  -> `ArgCallEmitter:292` -> `registry.register(.., Mode.THROW, list=false)`) to the **scalar arm
  `:131`. Reachable.**
- arity-1, **list**, non-`@lookupKey`: caught by the fall-through guard at `FieldBuilder.java:1123`
  ("not yet wired") and never produces a `ThrowOnMismatch`.
- composite (arity > 1), non-`@lookupKey`: rejected as `UnclassifiedArg` at `FieldBuilder.java:1117`.
- any `@lookupKey` arg: routed by `LookupMappingResolver` to `LookupValuesJoinEmitter` (a
  currently-correct site), never the registry.
- `@nodeId`-decorated argument: classified as `SkipMismatchedElement`, not `ThrowOnMismatch`.

So the registry's **list throw arm (`:113`) is unreachable today**; only the **scalar arm (`:131`)
is**. No validator inspects the extraction inside a `CallParam`, so nothing build-fails the reachable
shape before emission.

The SDL shape that reaches the scalar arm is a non-`@nodeId`, non-`@lookupKey`, arity-1 scalar `ID`
argument on a query field backed by a node-type table, for example:

```graphql
type Query {
  filmByNode(id: ID!): Film
}
```

where `Film` is `@table` + `@node` with a single-column PK and `id` carries neither `@nodeId` nor
`@lookupKey`. Note the inversion the fixture relies on: a **bare** `ID` argument decodes-against-PK
with throw-on-mismatch, while an explicitly `@nodeId`-marked argument skips. This is the only door to
the throw arm; R265 relies on it but does not adjudicate whether the inversion is the intended design
(out of scope; see below).

## Why not centralize the construction

The construction shape is now duplicated across six sites (two broken, four correct). The tempting
move is to single-home it behind one helper. **Do not.** One leg of this rejection stands on its own:

The skip-vs-throw decision is a classified model fact (`CallSiteExtraction.NodeIdDecodeKeys.{SkipMismatchedElement
| ThrowOnMismatch}`, the R50 boundary carrier); the human-readable message is presentation the emitter
supplies from call-site context. Hanging a message template on the model arm pushes against "Wire-format
encoding is a boundary concern, never a model concern" and against generation-thinking. The arm already
carries the right fact (the skip/throw fork); leave the message in the emitters.

(The original Spec also argued against a *shared emitter helper* on two further grounds, both now
moot: the Supplier-lambda contortion a helper would have frozen is gone after R260, and the
"per-site message context would be erased" concern does not apply to the two broken sites, which share
one constant message. A pure emitter-side `CodeBlock` helper would consolidate the six spellings but
provides no compile coverage of the *emitted* throw, so it does not address this bug's root cause;
if desirable as DRY cleanup it is a separate item, not R265.)

## Deliverables

### 1. Fix the two broken construction sites

Switch each `throw new $T($S)` (with `$T = GraphqlErrorException`) to
`throw $T.newErrorException().message($S).build()`, mirroring the four correct sites:

- `CompositeDecodeHelperRegistry.java:131` (scalar throw arm).
- `CompositeDecodeHelperRegistry.java:113` (list throw arm). Fix it too even though it is unreachable
  today: it is still wrong code, and `FieldBuilder.java:1123` flags the list-arity-1 filter path as
  "not yet wired", i.e. a path that could become reachable. The `MISMATCH_MESSAGE` constant is reused
  as-is at both arms.

No message-string changes; no new helper; no signature changes; no model-arm field.

### 2. Regression guard: a compilation-tier fixture exercising the reachable throw arm

The bug survived because no fixture drives a `ThrowOnMismatch` arm through `javac`. The mirror for
"emitter renders non-compiling Java" is a **compilation-tier fixture**, not a pipeline/unit string
assertion (a `contains(".newErrorException()")` guard would re-create the exact "string looked
plausible but didn't compile" blind spot one layer up; code-string assertions on generated bodies are
banned at every tier).

Add a `graphitron-sakila-example` schema field with the SDL shape identified above (non-`@nodeId`,
non-`@lookupKey`, arity-1 scalar `ID` argument on a node-type-backed query field) so the generated
`*Conditions` source compiles its `decode<Type>KeyOrThrow` helper against the real graphql-java 25
API. This drives the **scalar arm (`:131`)**, the one reachable site, and is the load-bearing guard:
it is what would have caught the bug.

**The list arm (`:113`) cannot be compile-covered today**, because no classification produces a list
`ThrowOnMismatch` (see Reachability). Its fix in Deliverable 1 is defensive, and it stays pinned only
by the existing `CompositeDecodeHelperRegistryTest` list-throw unit assertions (`:121`, `:146`-`:153`)
until the list-arity-1 filter path is wired. Do not force an unreachable fixture; cover the reachable
arm and record the gap.

Confirm the routing during implementation against `FieldBuilder.java:1107-1140` (the `ID`-arg block)
and `ArgCallEmitter.java:292` (the `NodeIdDecodeKeys` case) -> `buildNodeIdDecodeExtraction` (`:360`)
-> `registry.register`.

### 3. Resolve the FetcherEmitter:284 thread (verification, not a fix)

`FetcherEmitter.java:284` emits `throw new $T($S)`. Verified: `$T` is `UnsupportedOperationException`
(a String-ctor exception), **not** `GraphqlErrorException`; it is correct and stays out of scope.
Recorded here so it does not resurface as a "sixth site". No other `new $T($S)` site in the
generators resolves `$T` to `GraphqlErrorException`.

## Tests

- **Compilation tier** (`graphitron-sakila-example`): the new fixture generates a `*Conditions` source
  whose `ThrowOnMismatch` scalar decode helper compiles against the real graphql-java 25 API. This is
  the load-bearing guard.
- **Existing unit assertions stay green.** `CompositeDecodeHelperRegistryTest:88` and `:153` assert
  `body.contains("graphql.GraphqlErrorException")`; the builder form still renders the FQN
  `graphql.GraphqlErrorException`, so the fix is non-breaking. No new string assertion is added.

## Affected code

- `generators/CompositeDecodeHelperRegistry.java` - two construction sites (`:113` list, `:131`
  scalar), shared `MISMATCH_MESSAGE` (`:46`), both in `buildHelper` (`:86`).
- `graphitron-sakila-example` schema + (if needed) service/query stubs - the compilation-tier fixture
  for the reachable scalar `ThrowOnMismatch` arm.

## Out of scope

- The list throw arm's *reachability* (wiring the list-arity-1 NodeId filter path); R265 only fixes
  the construction so it compiles if/when reached.
- Whether a bare non-`@nodeId` `ID` argument should get NodeId-decode + throw semantics at all (the
  classification inversion the fixture relies on); a separate question if it is one.
- Any shared exception-construction helper, generated error-helper class, or model-arm message field.
- `FetcherEmitter.java:284` (verified correct: `UnsupportedOperationException`).
</content>
</invoke>
