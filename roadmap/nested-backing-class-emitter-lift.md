---
id: R412
title: "Nested backing class emits $-qualified names at the no-Class-in-hand emit sites (backingClassOf, recordColumnReadArgs, FetcherEmitter, ChildField)"
status: Backlog
bucket: bug
priority: 7
theme: codegen-correctness
depends-on: []
created: 2026-07-01
last-updated: 2026-07-01
---

# Nested backing class emits $-qualified names at the no-Class-in-hand emit sites (backingClassOf, recordColumnReadArgs, FetcherEmitter, ChildField)

R370 fixed the `ClassName.bestGuess(binaryName)` nested-class defect (a nested backing class emits the
non-compiling `Outer$Nested` instead of the JLS-legal `Outer.Nested`) at the four sites that had a
structurally-correct name already in hand: the two `AccessorRef` producers, the `@service` return-type
validator (`checkServiceReturnMatchesPayload`), and the `@service` fetcher return type
(`computeServiceRecordReturnType`). The same `bestGuess`-over-`fqClassName()` hazard recurs at a
family of **emit sites that hold no reflected `Class<?>` and no captured structural `TypeName`**, so
they cannot take R370's one-for-one call swap:

- `GeneratorUtils.backingClassOf` (`:399-408`) on the **LifterRef** path (`buildLifterRowKey`, the
  developer-supplied static-lifter arm), flagged in R370's "Sibling latent bug" section: it re-derives
  the parent backing class from `GraphitronType.ResultType.fqClassName()` via `bestGuess` inside the
  emitter, with no `Class<?>` at the site. Also touches the `@sourceRow` / DTO-parent-batching path.
- The recurrences at `recordColumnReadArgs`, `FetcherEmitter`, and several `ChildField` sites, for the
  same reason: no `Class<?>` at the site.

The structural fix for these is to carry the backing class as a `ClassName` on the model (the way
`AccessorRef` already carries `parentBackingClass`) rather than re-parsing a binary string at emit
time; a model-shape change with its own blast radius. Latent for the same reason R370's core bug was:
no nested carrier exercises these paths under compilation today. Scope, enumerate the exact sites, and
decide the model-lift shape when this moves to Spec; R370's Scope-note and Sibling-latent-bug sections
are the starting map.
