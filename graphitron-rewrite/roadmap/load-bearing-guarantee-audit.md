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

Today the contract is enforced by reviewer eyeballs. The principles doc
already says "if you relax a classifier check that an emitter relies on,
audit every emitter site that consumes the corresponding shape, in the same
commit". A matching annotation pair makes that audit mechanical and gives
two practical wins:

- **Find-usages navigation.** Jump from a producer to every consumer and
  back, in the IDE, for free. This is the day-to-day reason to land it.
- **Drift becomes loud.** Remove a load-bearing classifier check and the
  build fails on the next test run, not days later when the generated
  source stops compiling (or months later via `ClassCastException` in
  production).

## Design

### Annotations

Two new annotation types under `no.sikt.graphitron.rewrite.model` (the
package today contains interfaces like `BatchKeyField` that mark contracts
between classifier and emitter; these are the first `@interface`s in the
package):

```java
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
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

`description` documents what shape the emitter is allowed to assume;
`reliesOn` documents what the emitter does with that assumption (no
defensive cast, no null guard, etc.). One emitter method may depend on
multiple checks via `@Repeatable`. Local-variable annotations don't
survive past compile in stock Java, so both are method-level (or
type-level for cases where the contract spans a class).

### Test

`LoadBearingGuaranteeAuditTest` under
`graphitron-rewrite/src/test/java/no/sikt/graphitron/rewrite/model/`
discovers candidate classes by walking `target/classes` and
`target/test-classes` under the rewrite module's package root
(`no/sikt/graphitron/rewrite/`) directly via `Path` enumeration; loads
each class via the test classloader; collects every method/class
annotated with either annotation; groups by key; and asserts:

1. Every key with a `@DependsOnClassifierCheck` consumer has exactly one
   `@LoadBearingClassifierCheck` producer. Failure names both sides.
2. No key has more than one producer. Failure names both producers.
3. `description` and `reliesOn` are non-blank.

Producers without consumers are *allowed*. Some classifier checks reject
shapes for hygiene rather than because an emitter relies on them, and
forcing a fake consumer would muddle the contract.

The inverse asymmetry (a new emitter that *should* depend on a guarantee
but forgets `@DependsOnClassifierCheck`) is not caught here — that drift
mode still falls back to the `*Fetchers` compile failure that the
principles doc already names as the safety net. Acknowledged and out of
scope; covering it would require a markedly different mechanism.

The filesystem walker is intentionally hand-rolled rather than pulled
in via a new dependency (Reflections, ClassGraph). The rewrite module
has no classpath-scanning lib today, the package root is fixed, and a
twenty-line `Files.walk` over `target/classes` is the minimum that fits.

### Sites

Three keys, six sites.

| Key | Producer | Consumer |
|---|---|---|
| `service-catalog-strict-tablemethod-return` | `ServiceCatalog.reflectTableMethod` (the `ClassName.equals` arm rejecting wider return types) | `TypeFetcherGenerator.buildQueryTableMethodFetcher` (declares `<SpecificTable> table = Method.x(...)` with no cast) |
| `service-catalog-strict-service-return` | `ServiceCatalog.reflectServiceMethod` (the `TypeName.equals` arm against `FieldBuilder.computeExpectedServiceReturnType`) | `TypeFetcherGenerator.buildQueryServiceTableFetcher` (typed `Result<FilmRecord>` return rather than `Object`) |
| `column-field-requires-table-backed-parent` | `FieldBuilder.classifyChildFieldOnTableType` (the only construction site of `ChildField.ColumnField`, at `:2110`) | `TypeFetcherGenerator.generateTypeSpec` (the `case ChildField.ColumnField` switch arm at `:288` throws `IllegalStateException` on `parentTable == null`) |

Note on the second key: the consumer is `buildQueryServiceTableFetcher`,
*not* the shared `buildServiceFetcherCommon` helper. The helper is also
called by `buildQueryServiceRecordFetcher`, whose `PojoResultType` /
`ScalarReturnType` path falls back to the developer's reflected return
type (`computeServiceRecordReturnType`) and does *not* depend on the
strict guarantee. Annotating the common helper would claim a contract
that the helper alone does not uniformly enforce.

## Implementation

- `model/LoadBearingClassifierCheck.java`,
  `model/DependsOnClassifierCheck.java`,
  `model/DependsOnClassifierChecks.java`: the annotation triple.
- Six annotations on the six sites in the table above.
- `model/LoadBearingGuaranteeAuditTest.java`: the reflective test.

## Documentation

`rewrite-design-principles.md` § "Classifier guarantees shape emitter
assumptions" gains one paragraph naming the annotation pair as the
enforcement mechanism, with a one-line forward note: when you add a new
load-bearing classifier check, declare it with
`@LoadBearingClassifierCheck` and annotate every dependent emitter with
`@DependsOnClassifierCheck`.

## Tests

The audit test itself is the test deliverable. To prove it actually
fails on drift, the implementer mutation-tests once locally by removing
one `@LoadBearingClassifierCheck` and confirming the test fails with the
expected error pointing at the orphaned consumer. No fixture committed;
implementer-then-reviewer loop verifies it.

## Open questions

None.

## What this enables going forward

Mutation bodies ([mutations.md](mutations.md)) and Stubs #3
(interface/union) each introduce more classifier-emitter contracts.
Each becomes a one-line annotation pair on the producer and consumer.
If a `guarantees.md` reference page ever becomes useful, it generates
trivially off the annotations; deferred until a real signal pushes the
trade.
