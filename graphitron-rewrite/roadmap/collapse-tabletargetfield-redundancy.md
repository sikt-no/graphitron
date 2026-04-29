---
title: "Collapse `BatchKeyField` validator/emitter redundancy"
status: Backlog
bucket: cleanup
priority: 3
theme: model-cleanup
depends-on: []
---

# Collapse `BatchKeyField` validator/emitter redundancy

Promote `unsupportedReason()` from four parallel static overloads on `SplitRowsMethodEmitter` to a default method on `BatchKeyField`, so the validator's four-arm chain at `GraphitronSchemaValidator.java:160-180` collapses to a single `instanceof BatchKeyField` check and the emitter/validator lock-step (currently convention-enforced) becomes compiler-enforced.

## Where the redundancy lives

1. **Validator (`GraphitronSchemaValidator.java:160-180`).** Four near-identical `instanceof` arms for `SplitTableField`, `SplitLookupTableField`, `RecordTableField`, `RecordLookupTableField`. Each arm calls a same-named `SplitRowsMethodEmitter.unsupportedReason(<concreteType>)` overload and forwards the result into the same `ValidationError(RejectionKind.DEFERRED, ...)` constructor. Adding a fifth batched variant requires a fifth arm.
2. **Emitter (`SplitRowsMethodEmitter.java`).** Four overloads of `unsupportedReason(...)` (lines 266, 299, 327, 365), one per batched variant. Two are nearly identical (cardinality + condition-join checks); the other two share the condition-join check.

Both sites have to stay in lock-step (`SplitRowsMethodEmitter.java:260-264`: "moving a branch from here to a real emitter body must update this predicate in the same commit"). Today that's enforced by convention only.

## Refactor

Promote `unsupportedReason()` to an instance method on `BatchKeyField`:

```java
public interface BatchKeyField {
    BatchKey batchKey();
    String rowsMethodName();

    /** Empty when the variant's rows-method body is emittable; non-empty when the
     *  emitter falls back to a runtime stub. Validator and emitter share this predicate
     *  so they cannot drift. */
    default Optional<String> unsupportedReason() { return Optional.empty(); }
}
```

`ServiceTableField` keeps the default (always emittable today); the four batched variants override with the bodies currently in `SplitRowsMethodEmitter`'s static overloads. The four static overloads are deleted.

The validator collapses from four arms to one:

```java
if (field instanceof BatchKeyField bk) {
    bk.unsupportedReason().ifPresent(msg -> errors.add(new ValidationError(
        RejectionKind.DEFERRED, field.qualifiedName(),
        "Field '" + field.qualifiedName() + "': " + msg, field.location())));
}
```

The four call sites in `SplitRowsMethodEmitter` (lines 238, 280, 313, 347 of the form `var stubReason = unsupportedReason(<typedField>);`) become virtual calls (`var stubReason = field.unsupportedReason();`).

## Optional follow-on (not required for this item)

Seal `BatchKeyField` to `permits SplitTableField, SplitLookupTableField, ServiceTableField, RecordTableField, RecordLookupTableField`. Today the interface is open, intentionally per its javadoc ("intentionally standalone (does not extend `GraphitronField`) so that it can be applied as an orthogonal capability without being restricted by the sealed hierarchy"). Sealing trades that flexibility for exhaustive `switch` over `BatchKeyField`. Revisit if the set is closed in practice.

## History

The earlier framing of this item ("six `Table*Field` variants share identical components; evaluate sealed intermediates `StandardTableField` / `RecordBoundField`") inverted the actual redundancy axis. No two `TableTargetField` leaves share an identical component set, and the orthogonal capability interfaces (`SqlGeneratingField`, `BatchKeyField`, `LookupField`, `MethodBackedField`) already cover what intermediate sealed types would. The structural redundancy that does exist is on the **batch axis**, not the parent-type axis, hence the retitle.

## Non-goals

- **Sealed intermediates `StandardTableField` / `RecordBoundField`.** Rejected: split on the wrong axis (parent type), would bisect the redundant validator chain rather than collapse it, and would force ~27 `instanceof` sites and ~18 named validation tests to re-evaluate. The capability-interface approach above achieves the same collapse with one new method.
- **Changes to the main `TypeFetcherGenerator.generateTypeSpec` switch (`TypeFetcherGenerator.java:294-402`).** Each leaf emits a different shape (rows-method names, participant handling, scatter helpers); intermediates do not collapse those arms.
- **Changes to the cardinality/shape predicates at `TypeFetcherGenerator.java:447-485`.** Those branch on concrete identity (`SplitTableField` vs. `RecordTableField`), not on "is batched".

## Success criteria

- `GraphitronSchemaValidator.validateVariantIsImplemented` collapses from 4 arms to 1.
- `SplitRowsMethodEmitter` no longer carries `unsupportedReason` overloads; the bodies live on the records.
- `mvn test -pl graphitron-rewrite -Pquick` and `mvn install -f graphitron-rewrite/pom.xml -Plocal-db` pass without test changes (the lock-step is now compiler-enforced rather than convention-enforced).
