---
id: R265
title: "Inline ThrowOnMismatch arms emit non-compiling new GraphqlErrorException(String)"
status: Spec
bucket: cleanup
priority: 5
theme: model-cleanup
depends-on: []
created: 2026-05-30
last-updated: 2026-05-31
---

# Inline ThrowOnMismatch arms emit non-compiling new GraphqlErrorException(String)

## Problem

graphql-java 25's `GraphqlErrorException` has only a protected builder constructor; there is no
public `GraphqlErrorException(String)`. The correct construction is
`GraphqlErrorException.newErrorException().message(..).build()`.

Two NodeId `ThrowOnMismatch` emitters still emit `throw new $T($S)` with `$T = GraphqlErrorException`,
which **does not compile**:

- `generators/ArgCallEmitter.java:395` (list throw arm), `:432` (arity-1 scalar throw arm), `:451`
  (arity-N scalar throw arm) — three sites in one file.
- `generators/CompositeDecodeHelperRegistry.java:98` (list throw arm), `:113` (scalar throw arm) —
  two sites.

This is latent, not live: no compilation-tier fixture currently exercises a `ThrowOnMismatch` arm
(a top-level non-`@lookupKey` scalar / list `@nodeId` argument on a node-type table, classified in
`FieldBuilder.java:1129`). The broken string is generated only by pipeline-tier tests that stop at
the `TypeSpec` shape and never run `javac` over it. R195's `decode<Record>` helper became the first
*compile-tested* NodeId throw and failed against the real graphql-java API, surfacing the class.

The same shape is **already correct** at three other sites, which proves the fix and bounds the
scope:

- `generators/LookupValuesJoinEmitter.java:330` (parameterized message: `...for argument '<name>'`)
- `generators/TypeFetcherGenerator.java:2033` and `:2835` (parameterized message: `...input field '<name>'`)
- `generators/InputBeanInstantiationEmitter.java:294` (bare constant message; R195 landed this)

## Design fork: patch in place, do not centralize (resolved)

The construction shape `GraphqlErrorException.newErrorException().message($S).build()` is now
duplicated across five-plus sites: three render it correctly, two render the non-compiling variant.
The tempting move is to single-home it behind one emitter helper (`GraphqlErrors.throwNodeIdMismatch()`).
Per principles-architect, **do not.** This Spec patches the two broken files in place and introduces
no shared helper and no model field.

- **"One predicate, one home" does not reach this surface.** That rule governs *classification
  decisions over the model* (a derived name, which side of a join holds the FK) that can drift in
  *meaning*. What is duplicated here is the construction syntax for a third-party type; it cannot
  drift in meaning, only be right or wrong against a fixed external API. There is no model field,
  no consumer evaluating a predicate.
- **The part that actually varies is the message, and it must stay per-site.** Three correct sites
  parameterize the message with call-site context (`argument '<name>'`, `input field '<name>'`); the
  two broken sites and `InputBeanInstantiationEmitter` use the bare constant. A helper that
  single-homed the message would erase the context three sites deliberately carry (an **Error
  quality** regression); a helper that parameterized it would wrap a single builder call and save
  nothing.
- **A helper would freeze the contortion R260 is removing.** The two ArgCallEmitter scalar arms wrap
  the throw in a `(($T<?>) () -> { throw ...; }).get()` Supplier-lambda to stay in expression
  position. That construct is the canonical offender named by "Generated code is read and debugged",
  and **R260** (`nodeid-decode-emitter-readability`) owns deleting it. Centralizing now would mint a
  `throwNodeIdMismatchExpression()` whose whole job is to emit the construct a sibling item is trying
  to dissolve.

**Boundary with R260 (order-independent siblings, no `depends-on`).** R265 and R260 overlap on
`ArgCallEmitter.buildNodeIdDecodeExtraction` (`:355-455`). R265 swaps *only* the exception
construction inside the existing arms (`new $T($S)` -> `newErrorException().message($S).build()`),
leaving the Supplier-lambda wrapper exactly as today; the lambda is R260's to remove. If R260 lands
first it rewrites these arms into statement-position helpers and naturally uses the builder form,
making R265's scalar-arm edits moot (no regression: the builder form is what R260 would write). If
R265 lands first it makes the arms compile now and R260 rewrites them later. Neither blocks the
other. R265 must **not** "helpfully" lift the scalar arms into a named decode helper — that is
R260's charter and widens R265's blast radius into the three currently-correct sites for no
compile-correctness gain.

**Why not hang the message on the model arm.** Floated and rejected: putting the message template on
`CallSiteExtraction.NodeIdDecodeKeys.ThrowOnMismatch` pushes against "Wire-format encoding is a
boundary concern, never a model concern" (the arm is the boundary carrier R50 introduced; it must
not grow an emitted-string template) and against generation-thinking (the human-readable message is
presentation the emitter supplies from call-site context, not a classified fact). The arm already
carries the right fact: the `ThrowOnMismatch` vs `SkipMismatchedElement` fork. Leave the message in
the emitters.

## Deliverables

### 1. Fix the five broken construction sites

Switch each `throw new $T($S)` (with `$T = GraphqlErrorException`) to
`throw $T.newErrorException().message($S).build()`, mirroring the three correct sites:

- `ArgCallEmitter.java:395` (list throw arm), `:432` (arity-1 scalar throw arm, inside the Supplier
  lambda), `:451` (arity-N scalar throw arm, inside the Supplier lambda). The Supplier-lambda wrapper
  stays; only the exception construction inside it changes.
- `CompositeDecodeHelperRegistry.java:98` (list throw arm), `:113` (scalar throw arm). The
  `MISMATCH_MESSAGE` constant is reused as-is.

No message-string changes; no new helper; no signature changes.

### 2. Regression guard: a compilation-tier fixture exercising the throw arms

The bug survived because no fixture drives a `ThrowOnMismatch` arm through `javac`. The mirror for
"emitter renders non-compiling Java" is a **compilation-tier fixture**, not a pipeline string
assertion (code-string assertions on generated bodies are banned at every tier, and a
`contains(".newErrorException()")` guard would re-create the exact "string looked plausible but
didn't compile" blind spot one layer up).

Add a `graphitron-sakila-example` schema field (or fields) with a top-level **non-`@lookupKey`**
scalar/list `@nodeId` argument so the `ThrowOnMismatch` arm compiles against the real graphql-java
API. **The fixture set must cover both routing paths and both shapes**, because the five sites split
across two files by arity and list-ness:

- arity-1 scalar and arity-1 list -> inline `ArgCallEmitter` arms (`:432`, `:395`);
- arity > 1 (composite-PK NodeType) scalar and list -> `CompositeDecodeHelperRegistry` arms
  (`:113`, `:98`, registry non-null).

A single arity-1 scalar fixture compiles only one of the five arms; the guard is incomplete unless
it spans arity-1/arity-N and scalar/list. Confirm the exact routing during implementation against
`FieldBuilder.java:1105-1138` (the `ID`-arg/`nodeIdMetadata` block) and
`ArgCallEmitter.perArgExpr -> buildNodeIdDecodeExtraction`; note `FieldBuilder` currently surfaces
**non-`@lookupKey` composite-PK** scalar args as `UnclassifiedArg` (`:1113-1117`) and **non-`@lookupKey`
arity-1 list** falls through to column-name resolution (`:1120`), so the fixture shapes that actually
reach the throw arms need verifying. If a needed shape is not classifiable today, note it and cover
the reachable arms rather than forcing an unreachable fixture.

### 3. Resolve the FetcherEmitter:209 thread (verification, not a fix)

`FetcherEmitter.java:209` emits `throw new $T($S)`. Verified during spec drafting: `$T` is
`UnsupportedOperationException` (a String-ctor exception), **not** `GraphqlErrorException` — it is
correct and stays out of scope. Recorded here so it does not resurface as a "sixth site" mid
implementation. No other `new $T($S)` site in the generators resolves `$T` to `GraphqlErrorException`
(swept via `grep 'new \$T' graphitron/src/main/java`).

## Tests

- **Compilation tier** (`graphitron-sakila-example`): the new fixture(s) generate `*Fetchers`
  sources whose `ThrowOnMismatch` throw arms compile against the real graphql-java 25 API. This is
  the load-bearing guard — it is what would have caught the bug.
- **Existing pipeline assertion stays green.** `CompositeDecodeHelperRegistryTest:88` asserts
  `body.contains("graphql.GraphqlErrorException")`; the builder form still renders the FQN
  `graphql.GraphqlErrorException`, so the fix is non-breaking. (No new string assertion is added —
  see deliverable 2 on why.)

## Affected code

- `generators/ArgCallEmitter.java` — three construction sites (`:395`, `:432`, `:451`).
- `generators/CompositeDecodeHelperRegistry.java` — two construction sites (`:98`, `:113`).
- `graphitron-sakila-example` schema + (if needed) service/query stubs — the compilation-tier
  fixture(s) for the `ThrowOnMismatch` scalar/list, arity-1/arity-N arms.

## Out of scope

- The `(($T<?>) () -> { throw ...; }).get()` Supplier-lambda contortion in the scalar arms — that is
  **R260**'s (`nodeid-decode-emitter-readability`); R265 leaves the wrapper in place and only fixes
  the construction inside it.
- Any shared exception-construction helper or model-arm message field (rejected above).
- `FetcherEmitter.java:209` (verified correct: `UnsupportedOperationException`).
