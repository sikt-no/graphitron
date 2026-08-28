---
id: R873
title: "Polymorphic child fields under an Outcome payload never emit the wrapper arm-unwrap"
status: Backlog
bucket: bug
priority: 2
theme: codegen-correctness
depends-on: []
created: 2026-08-28
last-updated: 2026-08-28
---

# Polymorphic child fields under an Outcome payload never emit the wrapper arm-unwrap

A payload type carrying an errors field on the `WrapperArm` transport is a *flipped
outcome payload*: at run time its children receive an `Outcome` object as
`env.getSource()`, not the payload instance the author wrote. Every other child-field
emit path knows this. The multi-table polymorphic paths do not: they read the parent off
`env.getSource()` unconditionally, so the cast throws `ClassCastException` on every
request, on both arms, for every polymorphic child field of such a payload.

The generated read is byte-identical whether or not the payload has an errors field. It
happens to be correct only when no wrapper transport is in play.

## Reproduction

Confirmed on trunk (10-SNAPSHOT, after the `@nodeId` dispatch work). Consumer-reported
against 10.0.0-RC35 in
https://github.com/sikt-no/graphitron/issues/526#issuecomment-5449126087, where it
surfaces as

```
class ...schema.Outcome$Success cannot be cast to class ...records.DeaktiverApplikasjonerPayload
```

Generating from SDL where one payload carries both a monomorphic and a polymorphic child
field next to a `WrapperArm` errors field puts the defect and its control in the same
emitted class. The monomorphic sibling narrows:

```java
public static CompletableFuture<DataFetcherResult<Record>> language(DataFetchingEnvironment env) {
  if (!(env.getSource() instanceof Outcome.Success<?> success)) {
    return CompletableFuture.completedFuture(null);
  }
  ...
  Row1<Integer> key = DSL.row(((Record) success.value()).get(Tables.FILM.LANGUAGE_ID));
```

The polymorphic field, same payload, same class, does not:

```java
public static CompletableFuture<DataFetcherResult<List<Record>>> referrers(DataFetchingEnvironment env) {
  ...
  Row1<Integer> key = DSL.row(((Record) env.getSource()).get(Tables.FILM.FILM_ID));
```

The single-valued record-backed arm reproduces the reporter's snippet verbatim, off a
Pojo payload with a typed hub accessor:

```java
Record parentRecord = ((AccessorPayloads.SinglePayload) env.getSource()).film();
```

Removing the errors field from the payload, changing nothing else, makes the same schema
work: the discriminator query, the per-participant projections and the per-type dispatch
are all correct. Only the source binding is wrong.

## Where it comes from

`TypeFetcherGenerator` computes `sourceIsOutcome` once per type from
`FetcherEmitter.hasWrapperArmErrors(fields)` and threads it into
`FetcherEmitter.bind` and `buildBatchedDataFetcher`. None of the eight
`MultiTablePolymorphicEmitter` call sites in the same dispatch switch receive it, so the
emitter has no way to know the parent is wrapped. Two sites then hardcode the source:

- `MultiTablePolymorphicEmitter.buildScalarPerParentFetcher` binds `parentRecord` off
  `env.getSource()` in both its `KeyLift.Accessor` and its table-backed arm.
- The batched fetchers call `GeneratorUtils.buildRecordParentKeyExtraction` on the
  overload that defaults `sourceExpr` to `SOURCE_FROM_ENV`.

The machinery for the fix already exists and is unused on this path:
`buildRecordParentKeyExtraction` has a source-bound overload taking the expression the
backing object is read from, added precisely so an arm-switching caller can repoint the
field's own key extraction at `success.value()` without re-deriving it.

`GraphitronSchemaValidator.validateOutcomeChildArmSwitch` does not catch this either. It
covers the `PropertyDataFetcher` registration-escape family only (per
`FetcherEmitter.resolvesViaPropertyDataFetcher`), and a polymorphic child field registers
a real emitted fetcher, so it passes the guard while still reading off the wrong object.

## Scope at pickup

Whether the fix is threading `sourceIsOutcome` into the polymorphic emitter and repointing
the two source bindings, or hoisting the narrowing prelude to a place every child-field
emitter shares, is the spec's call. The narrower question the spec should answer: whether
the validator's arm-switch invariant can be widened from "does not resolve via
`PropertyDataFetcher`" to something that would have failed the build here, so the next
emit path added under a wrapper payload cannot repeat this silently.

Coverage should pin the emitted narrowing structurally for the polymorphic arms alongside
the existing monomorphic pins in `FetcherPipelineTest`, and add an execution-tier
round-trip over a wrapper payload with a polymorphic child, which is the tier that would
have caught a `ClassCastException` on every request.

Affected shapes, all reproduced or read off the emitter: batched list and connection forms
on both table-backed and record-backed parents, and the single-valued inline form on both.

