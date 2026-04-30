---
id: R21
title: "Load-bearing classifier guarantee audit annotations"
status: In Review
priority: 14
theme: structural-refactor
depends-on: []
---

# Load-bearing classifier guarantee audit annotations

## Overview

[`rewrite-design-principles.md` § "Classifier guarantees shape emitter
assumptions"](../docs/rewrite-design-principles.adoc) names a pattern that
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

Convention for `description` and `reliesOn`: name the *specific arm*,
not the whole method. `reflectTableMethod` has many rejection arms; the
load-bearing one is the strict `ClassName.equals` branch. The annotation
lands on the method, but the string should read "rejects wider return
types via the strict-equality arm" so a reader landing via find-usages
knows which lines matter.

### Test

`LoadBearingGuaranteeAuditTest` under
`graphitron-rewrite/src/test/java/no/sikt/graphitron/rewrite/model/`
discovers candidate classes by walking `target/classes` under the
rewrite module's package root (`no/sikt/graphitron/rewrite/`) directly
via `Path` enumeration; loads each class via the test classloader;
collects every method/class annotated with either annotation; groups by
key; and asserts:

1. The walk yielded a non-zero count of candidate classes. A vacuous
   empty walk (e.g., test invoked before the rewrite module compiled,
   or from an output-dir that doesn't match `target/classes`) would
   make every other assertion hold trivially; fail loudly with a
   diagnostic naming the path that was scanned.
2. Every key with a `@DependsOnClassifierCheck` consumer has exactly one
   `@LoadBearingClassifierCheck` producer. Failure names both sides.
3. No key has more than one producer. Failure names both producers.
4. `description` and `reliesOn` are non-blank.

Test-only classes are not annotated, so the walk is scoped to
`target/classes` only — no need to also walk `target/test-classes`.

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

Note on the third key: the framing for the first two keys is "the
emitter omits a defensive cast or null guard because the classifier
already rejected the bad shape". The `ColumnField` consumer doesn't
quite fit that mould — it explicitly throws `IllegalStateException` on
`parentTable == null`, so the null guard is present, just promoted to a
hard fail. The annotation pair earns its keep here for navigation and
drift annunciation rather than guard elision: a reader landing on the
throw via find-usages jumps to the producer that makes it unreachable,
and a producer-side relaxation that re-enables the path is named
explicitly by the audit failure rather than only via the throw firing
on a real schema.

## Implementation

- `model/LoadBearingClassifierCheck.java`,
  `model/DependsOnClassifierCheck.java`,
  `model/DependsOnClassifierChecks.java`: the annotation triple.
- Six annotations on the six sites in the table above.
- `model/LoadBearingGuaranteeAuditTest.java`: the reflective test.
- `model/auditfixture/OrphanedConsumer.java`: the deliberate-violation
  fixture for the meta-test.

## Documentation

`rewrite-design-principles.md` § "Classifier guarantees shape emitter
assumptions" gains one paragraph naming the annotation pair as the
enforcement mechanism, with a one-line forward note: when you add a new
load-bearing classifier check, declare it with
`@LoadBearingClassifierCheck` and annotate every dependent emitter with
`@DependsOnClassifierCheck`.

## Tests

The audit test itself is the primary test deliverable. To keep
confidence in the failure-detection durable across future refactors of
the walker, a committed meta-test exercises the audit logic against a
deliberately-broken fixture:

- `src/test/java/no/sikt/graphitron/rewrite/model/auditfixture/`
  contains a `OrphanedConsumer` class with a `@DependsOnClassifierCheck`
  whose `key` references a producer that does not exist anywhere in the
  module (e.g., `key = "audit-fixture-orphan"`).
- `LoadBearingGuaranteeAuditTest` exposes the discovery + grouping
  logic via a package-private static method that takes a class iterable
  and returns the set of violations as a structured value
  (`record AuditViolation(...)` or similar). The headline test feeds it
  the production walk; the meta-test feeds it just the fixture class
  and asserts the orphan is reported with the expected key.
- The headline test scopes its walk to exclude `auditfixture/`, so the
  fixture's deliberate violation never trips the production assertion.

This trades a small amount of test surface (one fixture class, one
extra test method) for an automated guarantee that the audit still
detects what it claims to detect, even after the walker is refactored.

## Open questions

None.

## What this enables going forward

Mutation bodies ([mutations.md](mutations.md)) and Stubs #3
(interface/union) each introduce more classifier-emitter contracts.
Each becomes a one-line annotation pair on the producer and consumer.
If a `guarantees.md` reference page ever becomes useful, it generates
trivially off the annotations; deferred until a real signal pushes the
trade.
