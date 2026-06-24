---
id: R370
title: "Record-backed parent with a nested backing class emits a non-compiling $-qualified cast"
status: Backlog
bucket: bug
priority: 6
theme: interface-union
depends-on: []
created: 2026-06-24
last-updated: 2026-06-24
---

# Record-backed parent with a nested backing class emits a non-compiling $-qualified cast

## Problem

`AccessorRef` carries the parent's backing class and the accessor's element class as javapoet
`ClassName`s, built via `ClassName.bestGuess(...)` over a *binary* class name
(`TypeBuilder.buildResultTypeFromClass` stores `cls.getName()`, so a nested class arrives as
`Outer$Nested`). `ClassName.bestGuess` splits only on `.`, so the trailing `Outer$Nested` becomes a
single simple name and is emitted verbatim. The resulting cast (`((pkg.Outer$Nested) env.getSource())
.<accessor>()` in the polymorphic fetchers, and the `(($T) src).<accessor>()` / `element.into(...)`
key extraction in `GeneratorUtils.buildAccessorKeySingle` / `buildAccessorKeyMany`) does not compile:
`javac` reads `Outer$Nested` as a top-level class name that does not resolve.

This affects **every `AccessorRef` consumer**, not one arm:

- the list-cardinality polymorphic child fetcher (`buildBatchedListFetcher` via
  `buildRecordParentKeyExtraction` -> `buildAccessorKeySingle` / `buildAccessorKeyMany`), and
- the single-cardinality polymorphic child fetcher (R367, `buildScalarPerParentFetcher`).

Both arms' execution/compilation tests use **top-level** carriers
(`CreateFilmsPayload`, `AddressOccupantCarrier`), so the nested case is only ever exercised at the
classifier/pipeline tier (`AccessorPayloads.SinglePayload` / `ListPayload`, which classify fine) and
never compiled. The defect is therefore latent: a consumer with a nested record/Pojo carrier and a
polymorphic child gets a generated file that fails `javac`, with no build-time rejection from
graphitron.

`AccessorRef`'s own javadoc claims the `ClassName`s are "resolved at the classifier boundary ... so
the emitter never re-parses the binary class name"; `bestGuess` over a binary string quietly violates
that stated contract, so this also brings the code back in line with the type's documentation.

## Mechanism

`FieldBuilder.derivePolymorphicHubSource` builds the `AccessorRef` with
`ClassName.bestGuess(parentFqClassName)` (the backing class, binary) and
`ClassName.bestGuess(elementClass.getName())` (the element class, binary). The reflected `Class<?>`
objects are in hand at that site, so the cast target can be resolved structurally.

## Plan

Construct the `AccessorRef` `ClassName`s from the reflected `Class<?>` via `ClassName.get(Class<?>)`
rather than from a binary-name string via `bestGuess`: `ClassName.get(Class)` already walks
`getEnclosingClass()` (`ClassName.java`), so a nested class resolves to the structurally correct
`Outer.Nested`. Do this at the `AccessorRef` construction boundary so the fix covers all consumers at
once; leave `GraphitronType.fqClassName()`'s binary-name form intact (the `Class.forName(...)`
consumers in `FieldBuilder` depend on the binary name for nested-class loading). Add
compilation-tier coverage with a nested carrier on at least one arm (the single arm's
`AccessorPayloads.SinglePayload` shape, lifted into the example) so the nested cast is compiled, not
just classified.

## Cross-links

Surfaced during R367 review (R367 shipped the single-cardinality arm at parity with the list arm for
top-level carriers and explicitly deferred nested backing classes here). Shares the `AccessorRef`
construction path with R366 (list cardinality).
