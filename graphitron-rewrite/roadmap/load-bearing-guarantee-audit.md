---
title: "Load-bearing classifier guarantee audit annotations"
status: Spec
priority: 14
---

# Load-bearing classifier guarantee audit annotations

## Overview

[`rewrite-design-principles.md` § "Classifier guarantees shape emitter
assumptions"](../docs/rewrite-design-principles.md) names a pattern that
already exists in three places on trunk: a classifier check rejects a shape
the emitter would otherwise have to handle defensively, and the emitter
relies on the rejection to skip casts, null checks, or `instanceof` guards.
The contract is real and load-bearing: relax the classifier check and the
generated `*Fetchers` source stops compiling, or worse, throws at runtime.

Today the contract is enforced by reviewer eyeballs. The principles doc says
"if you relax a classifier check that an emitter relies on, audit every
emitter site that consumes the corresponding shape, in the same commit". A
matching annotation pair makes that audit mechanical: the classifier check
declares which guarantee it produces; each dependent emitter declares which
guarantee it consumes; a reflective test fails the build if a consumer's
key has no producer (so a reviewer who removes the producer immediately
sees what they broke).

## Design

### Annotations

Two annotations under `no.sikt.graphitron.rewrite.model` (alongside
`BatchKeyField` and the other contract markers):

```java
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE, ElementType.FIELD})
public @interface LoadBearingClassifierCheck {
    String key();
    String description();
}

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@Repeatable(DependsOnClassifierChecks.class)
public @interface DependsOnClassifierCheck {
    String key();
    String reliesOn();
}

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface DependsOnClassifierChecks {
    DependsOnClassifierCheck[] value();
}
```

Local-variable annotations don't survive past compile in stock Java, so
both are method-level (or type-level for cases where the contract spans a
class). One emitter method may depend on multiple checks via
`@Repeatable`. `description` on the producer documents what shape the
emitter is allowed to assume; `reliesOn` on the consumer documents what
the emitter does with that assumption (no defensive cast, no null guard,
etc.).

### Test

A new `LoadBearingGuaranteeAuditTest` under
`graphitron-rewrite/src/test/java/no/sikt/graphitron/rewrite/model/`
walks the `no.sikt.graphitron.rewrite` packages via classpath-resource
enumeration (the same pattern `GeneratorCoverageTest` uses today),
collects every method/class/field annotated with either annotation,
groups by key, and asserts:

1. Every key with a `@DependsOnClassifierCheck` consumer has exactly one
   `@LoadBearingClassifierCheck` producer. Failure names both sides.
2. No key has more than one producer. Failure names both producers.
3. `description` and `reliesOn` are non-blank.

Producers without consumers are *allowed*. Some classifier checks reject
shapes for hygiene rather than because an emitter relies on them; not every
rejection is load-bearing for an emitter, and forcing a fake consumer would
muddle the contract.

### Sites annotated in this plan

Three keys, six sites. Each producer is the classifier method that performs
the check; each consumer is the emitter method that consumes the resulting
narrow shape.

| Key | Producer | Consumer(s) |
|---|---|---|
| `service-catalog-strict-tablemethod-return` | `ServiceCatalog.reflectTableMethod` (the `ClassName.equals` arm rejecting wider return types) | `TypeFetcherGenerator.buildQueryTableMethodFetcher` (declares `<SpecificTable> table = Method.x(...)` with no cast) |
| `service-catalog-strict-service-return` | `ServiceCatalog.reflectServiceMethod` (the `TypeName.equals` arm against `FieldBuilder.computeExpectedServiceReturnType`) | `TypeFetcherGenerator.buildServiceFetcherCommon` (typed `Result<FilmRecord>` return rather than `Object`) |
| `column-field-requires-table-backed-parent` | `FieldBuilder.classifyChildField` (returns `ColumnField` only when the parent is `TableBackedType`) | `TypeFetcherGenerator.generateTypeSpec` (the `ColumnField` switch arm at `:289` throws `IllegalStateException` on `parentTable == null`) |

The producer site for the third key may turn out to be split across
`FieldBuilder` and `BuildContext`; the implementer picks whichever method
is the gating boundary for the invariant.

## Implementation

- `model/LoadBearingClassifierCheck.java`, `DependsOnClassifierCheck.java`,
  `DependsOnClassifierChecks.java`: the annotation triple.
- `ServiceCatalog.reflectTableMethod`, `ServiceCatalog.reflectServiceMethod`:
  add `@LoadBearingClassifierCheck`.
- `FieldBuilder.classifyChildField` (or whichever method is the
  `ColumnField` gate, implementer's call): add
  `@LoadBearingClassifierCheck`.
- `TypeFetcherGenerator.buildQueryTableMethodFetcher`,
  `buildServiceFetcherCommon`, `generateTypeSpec`: add
  `@DependsOnClassifierCheck`.
- `model/LoadBearingGuaranteeAuditTest.java`: the reflective test.

## Documentation

`rewrite-design-principles.md` § "Classifier guarantees shape emitter
assumptions" gains one paragraph naming the annotation pair as the
enforcement mechanism, with a one-line forward note: when you add a new
load-bearing classifier check, declare it with
`@LoadBearingClassifierCheck` and annotate every dependent emitter with
`@DependsOnClassifierCheck`. The reflective test is what makes the rule
audit-able.

## Tests

The audit test itself is the test deliverable. To prove it actually fails
on drift, the implementer should land it with the three annotated sites
present, then mutation-test once locally by removing one
`@LoadBearingClassifierCheck` (or its key suffix) and confirming the test
fails with the expected error pointing at the orphaned consumer. No
fixture committed for that mutation; we trust the implementer-then-reviewer
loop to verify it.

## Open questions

None. The shape is intentionally minimal: two annotations, one test, three
existing sites. Anything more (e.g., a registry enum, doc generation off
the annotations) is deferred until a real signal pushes the trade.

## What this enables going forward

- **Static traceability.** IDE find-usages on a producer jumps to every
  consumer; the inverse jumps back. The two-direction navigation is the
  practical day-to-day win.
- **Drift becomes loud.** Removing a classifier check fails the audit test
  on the next build because the consumers still claim a contract that no
  longer holds. Today this surfaces as a generated-source compile failure
  days later (or a `ClassCastException` in production months later).
- **Inventory without grep.** Listing every load-bearing guarantee becomes
  one annotation lookup. Ties cleanly into the roadmap-tool extension space
  if we ever want a generated `guarantees.md` reference page.
- **Pays off as more classifier-emitter contracts land.** Mutation bodies
  ([mutations.md](mutations.md), Stubs #4, biggest chunk of remaining work)
  introduces several. Stubs #3 (interface/union) similarly. Each becomes a
  one-line annotation pair.
