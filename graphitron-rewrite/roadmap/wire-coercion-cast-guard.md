---
id: R261
title: "Generation-time wire-coercion cast guard across arg-classification sites"
status: Backlog
bucket: architecture
priority: 2
theme: structural-refactor
depends-on: [service-walker-substrate-absorption, dimensional-model-pivot]
created: 2026-05-29
last-updated: 2026-05-29
---

# Generation-time wire-coercion cast guard across arg-classification sites

## Problem

The generator emits Java that compiles cleanly and then `ClassCastException`s on
the first real request. The reported instance was
`SakRecord sakId = (SakRecord) raw.get("sakId");` in a generated
`MutationFetchers` (an `@service` input bean whose member is a jOOQ record).
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
| A | `@service` input-bean **field** | `InputBeanResolver.java:297` → `InputBeanInstantiationEmitter.java:147` | `(SakRecord) raw.get(...)`; also `(Long) raw.get(...)` for `Int`, `(Integer) raw.get("id")` for `ID` |
| B | `@service` scalar **arg** | `ServiceCatalog.argExtraction:735` → `ServiceMethodCallEmitter.scalarLeaf:155` | `(Long) env.getArgument("id")` for `ID` |
| C | `@condition` **nested** input field | `ConditionResolver.rewrapForNested` (2-arg ctor → `Direct`) → `ArgCallEmitter.java:539` | `(Long) _m.get("filmId")` for nested `ID`/enum |
| D | `@externalField`/accessor **arg** | `FetcherEmitter.java:754` | `(Long) env.getArgument(...)` cast to the reflected backing-method param type |
| E | `@service` input-bean **enum field** | `InputBeanResolver.java:293` → `InputBeanInstantiationEmitter.java:150` | `Enum.valueOf((String) ...)` with no check that SDL enum value names equal Java constant names → `IllegalArgumentException` |

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
  (`service-walker-substrate-absorption`, Backlog). The validate-time rejection
  that would catch site B is parked there.
- **R222** (`dimensional-model-pivot`, Spec) rejects a non-`TableRecord` jOOQ
  `Record` as an input *backing class* — an orthogonal dimension. Our bug is a
  valid POJO backing whose *member field* is a `TableRecord`. The pivot relocated
  classification into dimensional slots but added no per-leaf wire-coercion check.

The through-line: the refactors were explicitly behavior-preserving, and the one
correctness improvement (typed rejection) was deferred for substrate reasons.
This item is that correctness invariant, framed to ride the in-flight direction
rather than bolt onto the legacy classifier the pivot is dissolving.

## Direction (to be pinned at Spec, with principles-architect)

The invariant: **for any scalar/enum SDL leaf bound to a consumer-declared Java
type, the producer must either (a) confirm the type matches graphql-java's
coercion output, (b) route to a real conversion (`JooqConvert` / NodeId decode /
`valueOf`), or (c) emit a typed `Rejection`. The `Direct` raw-cast fallthrough is
retired as a catch-all.** Rejections ship in the established walker shape (typed
`*Error` sub-seal of `AuthorError`, `lspCode()`, LSP `Diagnostic` projection,
`RejectionSeverityCoverageTest` sample), per the R246/R238 precedent.

Central design fork (the same one R256 is already wrestling with): the rejection
cannot be produced by a translator over the resolved model, because that substrate
does no fresh reflection. It must live either at the **classifier** that produces
`CallSiteExtraction` (`ServiceCatalog.argExtraction`, `InputBeanResolver`), or in
**R256's substrate absorption** that pulls reflection into the walker. Resolve it
once, consistently with R256.

Per-site landing:

- **A, E (input-bean):** coordinate with R195 (which adds the `@nodeId`-record
  *happy path* and a narrow jOOQ-record rejection) and R256's `input-bean-shape`
  arm. Widen R195's rejection from "jOOQ record only" to the full
  wire-incompatible family (numeric width, ID-as-numeric, domain types,
  enum-name divergence), or absorb it here, so there is exactly one rejection.
- **B (service arg):** the deferred R256 arm. `depends-on` R256.
- **C (`@condition` nested), D (`@externalField`):** out of R256's service-only
  scope; follow the same walker/typed-rejection pattern R222 steers toward, not
  the legacy permit style. May be split into their own slice if the service slice
  lands first.

## Secondary scope (MED, from the audit)

- Reject `Set`-typed and list-of-list bean members at generation (today: sakila
  compile error or an obscure `ClassName.bestGuess("List<...>")` failure).
- Align the null-element contract: `createBeanList` rejects null elements
  unconditionally, throwing spurious `IllegalArgumentException` on a schema-legal
  `[FooInput]` with a null element, while the carrier path tolerates it.
- Retire the latent raw casts in `ServiceMethodCallEmitter.scalarLeaf:159-162`
  (`JooqConvert` / `NodeIdDecodeKeys` / `default` arms emit `($T) rawValue`); dead
  today, a trap if a classifier ever routes a scalar leaf there.

## Tests

Pipeline-tier (primary): an SDL exercising each site with a wire-incompatible
declared type produces a typed rejection (build fails), not a generated cast.
Compilation/execution tiers as the cross-module backstop. A regression test that
the reported `(SakRecord) raw.get(...)` shape can no longer be generated.

## Out of scope

- `@nodeId`-on-non-ID + federation encoded `@key` (sibling item
  `reject-nodeid-on-non-id-coordinates`).
- The verified-sound paths the audit cleared: Relay cursors, federation NODE_ID
  shape, custom `@scalarType` scalars, the column/arg enum path, context args,
  `env.getSource()`/`getObject()` casts, and the `InputRecordGenerator` carriers.

