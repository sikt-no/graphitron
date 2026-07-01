---
id: R261
title: "Generation-time wire-coercion cast guard across arg-classification sites"
status: Ready
bucket: architecture
priority: 2
theme: structural-refactor
depends-on: [dimensional-model-pivot]
created: 2026-05-29
last-updated: 2026-07-01
---

# Generation-time wire-coercion cast guard across arg-classification sites

## Problem

The generator emits Java that compiles cleanly and then `ClassCastException`s on
the first real request. The motivating instance was
`SakRecord sakId = (SakRecord) raw.get("sakId");` in a generated
`MutationFetchers` (an `@service` input bean whose member is a jOOQ record). That
*specific* jOOQ-record shape is now guarded: `InputBeanResolver.bindField` already
branches a record-typed member to a `@nodeId`-decode leaf or a loud rejection
before `Direct` (R195/R311/R315). What survives is the rest of the family on the
same scalar fall-through: a member or arg whose declared Java type is a numeric PK
type, a width-mismatched numeric, or a domain class still lands on
`CallSiteExtraction.Direct` and emits `(Long) raw.get("id")` / `(Integer) raw.get(...)`.
A four-agent audit found this is **one instance of a single missing invariant
replicated across every arg-classification site**: classify an argument by its
declared Java type, fall through to `CallSiteExtraction.Direct`, emit a raw
`(DeclaredType) wireValue` cast, and never check that graphql-java's coercion for
that SDL type actually yields `DeclaredType`. Only the column-binding path
(`EnumMappingResolver.deriveExtraction` → `JooqConvert`/`EnumValueOf` +
two-argument `DSL.val`) gets this right.

graphql-java delivers `ID` and enum values as `String`, `Int` as `Integer`,
input-objects as `Map<String,Object>`. So a declared cast target of a jOOQ
record, a numeric PK type, a domain class, or a width-mismatched numeric is a
guaranteed runtime crash, invisible at build time.

## Affected sites (HIGH; all compile, all crash at runtime)

| # | SDL surface | Classifier → emitter | Crashing shape |
|---|-------------|----------------------|----------------|
| A | `@service` input-bean **field** | `InputBeanResolver.bindField` scalar arm (`:784`) → `InputBeanInstantiationEmitter` (`:189`) | `(Long) raw.get(...)` for `Int`, `(Integer) raw.get("id")` for `ID`, domain/width-mismatch casts (the jOOQ-record `(SakRecord)` shape is already guarded, R195/R311/R315) |
| B | `@service` scalar **arg** | `ServiceCatalog.argExtraction` (`:795`) → `ServiceMethodCallEmitter.scalarLeaf` (`:201`) | `(Long) env.getArgument("id")` for `ID` |
| C | `@condition` **nested** input field | `ConditionResolver.rewrapForNested` (`:161`, 2-arg ctor → `Direct`) → `ArgCallEmitter` cast site | `(Long) _m.get("filmId")` for nested `ID`/enum |
| D | `@externalField`/accessor **arg** | `FetcherEmitter` (`:757`) | `(Long) env.getArgument(...)` cast to the reflected backing-method param type |
| E | `@service` input-bean **enum field** | `InputBeanResolver.bindField` enum arm (`:766-769`) → `InputBeanInstantiationEmitter` (`:202`) | `Enum.valueOf((String) ...)` with no check that SDL enum value names equal Java constant names → `IllegalArgumentException` |

Structural amplifier (why these reach production, not just a unit test): the
`.inputs` validation carriers (`InputRecordGenerator.fromMap`) use *wire-faithful*
types and are sound, but the `@service` bean is materialised by a **separate**
`createBean` path using *consumer-declared* types. Validation passing gives zero
guarantee `createBean` won't CCE, and nothing bridges them. The dispatch-partition
validator (`GeneratorCoverageTest`) only partitions `GraphitronField` leaves; it
says nothing about `CallSiteExtraction.FieldBinding` leaves or scalar-arg cast
targets, so none of A-E is build-time-checked.

(Sites F/G/H from the audit — `@nodeId` on non-ID coordinates and federation
encoded `@key` — are a sibling defect filed separately as
`reject-nodeid-on-non-id-coordinates`. The MED list/`Set`/null-element bean
issues are noted under "Secondary scope" below.)

## Why R222 / R238 did not close this

This bug class survived two large structural refactors over exactly these code
paths, and understanding why shapes the fix:

- **R238** (`service-method-call-walker-carrier`, Done) built
  `ServiceMethodCallEmitter` (site B) **as a translator over an already-resolved
  `MethodRef.Service`**, emitting byte-identical behaviour. A behavior-preserving
  translator carries the unsafe `Direct` cast forward unchanged. R238 *designed*
  the right safety net — a `ServiceMethodCallError` taxonomy with ~10 typed arms
  including `input-bean-shape` — but the translator substrate could only produce
  2 of them, so the other 8 were trimmed and **deferred to R256**
  (`service-walker-substrate-absorption`, since Done). The validate-time rejection
  that would catch site B landed there.
- **R222** (`dimensional-model-pivot`, Spec) rejects a non-`TableRecord` jOOQ
  `Record` as an input *backing class* — an orthogonal dimension. Our bug is a
  valid POJO backing whose *member field* is a `TableRecord`. The pivot relocated
  classification into dimensional slots but added no per-leaf wire-coercion check.

The through-line: the refactors were explicitly behavior-preserving, and the one
correctness improvement (typed rejection) was deferred for substrate reasons.
This item is that correctness invariant, framed to ride the in-flight direction
rather than bolt onto the legacy classifier the pivot is dissolving.

## Design (pinned)

The invariant: **for any scalar/enum SDL leaf bound to a consumer-declared Java
type, the classifier must either (a) confirm the declared type equals
graphql-java's coercion output (true raw pass-through), (b) route to a real
conversion (`JooqConvert` / NodeId decode / `EnumValueOf`), or (c) emit a typed
`Rejection`. The `Direct` raw-cast fallthrough stops being a catch-all and becomes
the *narrow* arm the predicate confirms is wire-pass-through.** Rejections ship in
the established shape (typed arm under `Rejection.AuthorError`, `lspCode()`, LSP
`Diagnostic` projection, `RejectionSeverityCoverageTest` sample, `typed-rejection.adoc`
paragraph + drift-list entry), per the R246/R238/R256 precedent.

Four decisions pin the shape (validated read-only with `principles-architect`):

### D1 — The judgment lives at the classifier, not on `ScalarTypeResolver`

`ScalarTypeResolver` is a pure name↔type *mapping* (SDL name → `ScalarResolution`
/ `TypeName` / boolean); it never holds a consumer-declared Java type or a call
site. The wire-coercion check introduces a third operand (the declared type) and a
*verdict* (assignable, or a mismatch tied to a site), which is a classify-time
decision. Folding that verdict into the mapping utility would make it a reader of
types it has never held, the same surface-widening R256 declined for the walker.

So: `ScalarTypeResolver` gains only the *missing forward mapping* it can own as a
total function (SDL scalar leaf → coercion-output `TypeName`, extending the
existing `SPEC_BUILT_INS` / `isClassifiedScalarJavaType` so it stays the single
source of truth for built-ins **and** custom `@scalarType` resolved types). The
*assignability verdict* is homed at the classifier and returns a **sealed result**
(`Resolved | Rejection`), mirroring the `EnumMappingResolver.EnumValidation`
(`Valid` / `Mismatch`) shape one file over, not an `Optional<Mismatch>` bag. The
enum→`String` and input-object→`Map` coercion facts are not in `SPEC_BUILT_INS`
and are not added there: they are already carried structurally by `EnumValueOf` /
`InputBean`, so the classifier reads them from the existing fork, not from the
resolver.

### D2 — `ServiceCatalog.argExtraction` is widened to carry both operands and a rejection arm

`argExtraction(String typeName, ClassLoader)` (site B) receives only the *Java*
type name, so it structurally cannot compute the coercion-output class; it forks
`EnumValueOf` vs `Direct` on `isEnum()` alone (`:797-801`). Both callers
(`ServiceCatalog:240`, `:642`) already hold the SDL surface. **Thread the SDL leaf
into `argExtraction`**
rather than hoisting the check to the callers: hoisting would have two consumers
re-derive the same predicate (the "one predicate, one home" drift), whereas
threading keeps the fork single-homed and lets every downstream `ParamSource.Arg`
consumer assume the extraction is wire-sound. `argExtraction` returns
`CallSiteExtraction` today, which has no rejection arm; its return type widens to
the sealed `Resolved | Rejection` from D1 so a wire-incompatible arg *rejects*
instead of silently classifying to `Direct`. This is the "classifier guarantees
shape emitter assumptions" lift: the contract ("this extraction is
wire-assignable") moves into the return type.

`InputBeanResolver.bindField` (sites A/E) already returns a `FieldResult.Ok | Fail`
sealed result and already branches before `Direct` for jOOQ-record members (R195);
it gains the same predicate call on the scalar arm, widening R195's narrow
"jOOQ-record only" rejection to the full wire-incompatible family (numeric width,
ID-as-numeric, domain types).

### D3 — Enum-name divergence (site E) is a separate axis, reusing `EnumMappingResolver`

Sites A-D fail because the coercion *class* (String/Integer/Double/Map) does not
assign to the declared type. Site E's declared type *is* the enum and assignment
succeeds; it fails because `Enum.valueOf((String) ...)` throws
`IllegalArgumentException` when the SDL enum value name diverges from the Java
constant name. That is a constant-name-set membership check, not a class
assignability check; they share neither operands nor failure shape, and conflating
them would re-introduce exactly the two-axes-in-one-arm problem R256 warned about
for `input-bean-shape`. So the typed family is **two sibling arms** (see D4), and
site E's parity check **reuses the value-name diff that today lives inside
`EnumMappingResolver.validateEnumFilter`** (`:114-145`; the SDL-value vs
`enumType.values()` comparison with the Levenshtein-hinted candidate is at
`:133-141`) rather than re-implementing parity at the two `@service` `EnumValueOf`
producers (`InputBeanResolver.bindField`'s enum arm `:766-769`,
`ServiceCatalog.argExtraction` `:798`). Today those producers build `EnumValueOf`
with *no* parity check while the column/arg enum path does the full check; this
closes that asymmetry by routing both through one parity home.

One wrinkle the implementer must resolve, not invent around: `validateEnumFilter`
(`:114`) and `deriveExtraction` (`:161`) are `ColumnRef`-coupled, and site E has no
column, only a declared Java enum `Class`. So the reuse is *not* a direct call.
Lift the value-name comparison (`:133-141`) into a column-agnostic helper that
takes the SDL enum type and the Java `enumType.values()` directly and returns the
same `EnumValidation`-shaped `Valid | Mismatch`; have `validateEnumFilter` delegate
to it for the column path (deriving the `Class` from the `ColumnRef` as it does
today) and call the helper from the site-E producers. The single parity home is
the extracted helper, with `validateEnumFilter` as one of its two callers.

### D4 — One sealed wire-coercion error family with two arms

Add a `WireCoercionError` sub-seal of `Rejection.AuthorError` (`lspCode()`
namespace `graphitron.wire-coercion.*`) with two arms, each carrying its
structured components (never a baked string):

- `Assignability(sdlLeaf, coercionOutputClass, declaredType, site)` — sites A-D.
- `EnumConstantDivergence(enumClass, sdlValueName, candidates, site)` — site E,
  populated from the `EnumMappingResolver` parity result.

Each arm lands with the full five-piece checklist (R246/R238/R256 precedent):
permits-clause entry, `lspCode()`, production at the classify site, a
`RejectionSeverityCoverageTest.sampleFor` branch, and a `typed-rejection.adoc`
paragraph + drift-list entry (`SealedHierarchyDocCoverageTest` scans both). This is
distinct from R256's `ReflectionError` / `ServiceMethodCallError` arms, which carry
reflection-intrinsic and service-binding failures; per the R256 boundary section,
R256 builds the *channel* and the reflection arms, R261 supplies the *coercion
judgment* that flows through it for the service slice and reuses the same predicate
for the non-service sites.

## Per-site landing

- **A, E (input-bean field / enum field):** `InputBeanResolver.bindField`. A widens
  R195's jOOQ-record branch to the full `Assignability` family; E routes the enum
  arm through the column-agnostic parity helper (D3) → `EnumConstantDivergence`.
- **B (service scalar arg):** `ServiceCatalog.argExtraction`, widened per D2. Rides
  R256's now-landed typed-rejection channel.
- **C (`@condition` nested), D (`@externalField` accessor arg):** outside R256's
  service-only scope. These consume the *same* classifier predicate from D1 (the
  predicate is authored once in this item, in Slice 1, and is not re-homed), but
  their channel is R222's dimensional `ConditionCall` / `ExternalFieldCall`
  siblings, which **R222 has not yet pinned** (R222 is `Spec`, and its own body
  notes it "added no per-leaf wire-coercion check"). See "Sequencing" below: this
  asymmetry is the reason C/D are a deferred Slice 2 and may be carved into their
  own item.

## Implementation

File-by-file for Slice 1 (the `@service` sites A, B, E + the shared predicate):

- `ScalarTypeResolver` — add the forward mapping `coercionOutputType(scalarName,
  classifiedTypes) → TypeName` (built-ins from `SPEC_BUILT_INS`, custom scalars from
  `ScalarResolution.Resolved#javaType`), the missing half of the existing
  bidirectional pair. Pure function; no declared-type operand.
- New classify-time predicate (sealed `Resolved | Rejection` result) — the single
  home for the "coercion output assignable to declared type" judgment, consuming
  `ScalarTypeResolver`. Co-locate with the classifiers that call it; do not put the
  verdict on the resolver (D1).
- `model/CallSiteExtraction.java` / the rejection model — add the `WireCoercionError`
  sub-seal of `Rejection.AuthorError` with the `Assignability` and
  `EnumConstantDivergence` arms (D4).
- `ServiceCatalog.argExtraction` (`:795`) — widen signature to take the SDL leaf and
  return the sealed result; produce `Direct` only on confirmed pass-through,
  `EnumValueOf` for enums, else `Assignability` rejection. Thread the SDL leaf from
  callers `:240` / `:642` (D2).
- `InputBeanResolver.bindField` (`:743`) — call the predicate on the scalar arm
  (`else` at `:784`), widening R195's jOOQ-record branch; route the enum arm
  (`:766-769`) through the column-agnostic parity helper → `EnumConstantDivergence`
  (D3).
- `generators/ServiceMethodCallEmitter.scalarLeaf` (`:201-212`) — once the classifier
  guarantees every `Direct` leaf is wire-pass-through, the `($T) rawValue` cast in the
  `Direct` arm is provably safe; the latent `JooqConvert` / `NodeIdDecodeKeys` /
  `default` raw casts (secondary scope) fall out of the same guarantee rather than
  being an independent cleanup.
- `Diagnostics.lspCodeOf` — add the `WireCoercionError` `instanceof` branch
  (`graphitron.wire-coercion.*`).
- `RejectionSeverityCoverageTest`, `typed-rejection.adoc` + its drift list — one
  entry per new arm (the tests fail "(no test sample)" / on doc drift otherwise).

`ArgCallEmitter` (master switch, site C) and `FetcherEmitter` (`:739-762`, site D)
are touched only in Slice 2.

## Sequencing

- **Slice 1 (A, B, E + the shared predicate)** depends only on **R256** (Done),
  whose boundary section already pins the channel Slice 1 rides; the
  `ReflectionError` / `ServiceMethodCallError` seals and untouched `scalarLeaf` are
  on trunk. The predicate
  (D1) and the `WireCoercionError` family (D4) are authored here, once.
- **Slice 2 (C, D)** depends on **R222** (`Spec`), which has not pinned a per-leaf
  wire-coercion channel for its `ConditionCall` / `ExternalFieldCall` siblings.
  Slice 2 *consumes* the Slice 1 predicate unchanged; it must not re-home it.
- **Recommendation for the reviewer:** because Slice 1 has a resolved channel (R256)
  and Slice 2 does not (R222 still `Spec`), if R222's channel is not pinned by the
  time Slice 1 lands, carve C/D into a separate roadmap item that `depends-on` R222
  and consumes this item's predicate. That would let R261 drop `dimensional-model-pivot`
  from `depends-on` and close on the `@service` slice alone. Keeping them in one item
  is acceptable only if Slice 2 explicitly reuses the Slice 1 predicate with no
  re-derivation. The slice boundary is the decision to confirm at `Spec → Ready`.

## Secondary scope (MED, from the audit)

- Reject `Set`-typed and list-of-list bean members at generation (today: sakila
  compile error or an obscure `ClassName.bestGuess("List<...>")` failure).
- Align the null-element contract: `createBeanList` rejects null elements
  unconditionally, throwing spurious `IllegalArgumentException` on a schema-legal
  `[FooInput]` with a null element, while the carrier path tolerates it.
- Retire the latent raw casts in `ServiceMethodCallEmitter.scalarLeaf` (`:201-212`;
  the `JooqConvert` / `NodeIdDecodeKeys` / `default` arms emit `($T) rawValue`); dead
  today, a trap if a classifier ever routes a scalar leaf there. Framed as falling
  out of the D1 predicate (the classifier now guarantees the leaf is wire-assignable
  or rejected), not an independent cleanup.

## Tests

Pipeline-tier (primary): an SDL exercising each site with a wire-incompatible
declared type produces a typed rejection (build fails), not a generated cast. One
case per arm: `Assignability` (jOOQ record, numeric-width mismatch, ID-as-numeric,
domain type) and `EnumConstantDivergence` (SDL value name with no matching Java
constant). Compilation/execution tiers as the cross-module backstop. A regression
test that the reported `(SakRecord) raw.get(...)` shape can no longer be generated.

**Validator-mirror invariant (per "Validator mirrors classifier invariants").**
Retiring `Direct`-as-catch-all introduces a new invariant: *every `Direct` is
wire-pass-through*. `GeneratorCoverageTest` partitions only `GraphitronField`
leaves (R261 `:50-52`), so nothing mechanically fails the build if a future
classifier emits `Direct` for a wire-incompatible type and the regression silently
returns. Pin the invariant two ways, mirroring R256's approach: (a) the D1
predicate is the **sole** producer of `Direct` across all the in-scope classify
sites (no bare `new Direct()` survives outside it); (b) a pipeline-tier assertion
per site that a wire-incompatible declared type fails the build with the
`WireCoercionError` arm's `lspCode()`. The mirror lives in the
classifier-rejection + pipeline-test pair, not in extending the
`GeneratorCoverageTest` partition.

**Custom-scalar non-regression (pin the audit's clearance).** The predicate asks
"does the declared type equal the *resolved* scalar Java type" (consulting
`ScalarResolution.Resolved#javaType` via `isClassifiedScalarJavaType`), **not** "is
it a spec built-in". A test that a `@scalarType`-resolved declared type classifies
to `Direct` (not a spurious `Assignability` rejection) keeps the audit-cleared
custom-scalar path sound and guards against over-rejection.

## Out of scope

- `@nodeId`-on-non-ID + federation encoded `@key` (sibling item
  `reject-nodeid-on-non-id-coordinates`).
- The verified-sound paths the audit cleared: Relay cursors, federation NODE_ID
  shape, custom `@scalarType` scalars, the column/arg enum path, context args,
  `env.getSource()`/`getObject()` casts, and the `InputRecordGenerator` carriers.

