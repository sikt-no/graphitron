# Plan — Sealed-Switch Generator Dispatch

Convert the field-dispatch chains in the rewrite generators from `instanceof`
chains terminating in `buildStub(field.name())` to sealed `switch` expressions
with a named `NotImplementedYet` branch per unimplemented variant. Restores
the exhaustive-switch guarantee the sealed hierarchy is meant to provide —
adding a new `permits` variant must become a compile error, not a runtime
`UnsupportedOperationException`.

Tracked as P1 #1 in
[rewrite-roadmap.md](rewrite-roadmap.md#architecture-review-priorities-2026-04-17).

---

## Scope

**In scope:** `TypeFetcherGenerator.generateTypeSpec`
([`TypeFetcherGenerator.java:111-132`](../graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java)).
This is the only generator where the dispatch emits a runtime stub —
`buildQueryLookupFetcher`, `buildQueryTableFetcher`, `buildQueryConnectionFetcher`,
and `buildServiceDataFetcher` are the implemented branches; everything else
falls through to `buildStub`.

**Out of scope for this plan:** `GraphitronWiringClassGenerator`,
`TypeClassGenerator`, `TypeConditionsGenerator`. Their `instanceof` checks
are filters ("emit an entry for this variant? yes/no"), not stub-emitters;
their exhaustiveness matters less and the refactor would be mostly noise.
Convert them only if the meta-test from P1 #2 flags uncovered variants.

---

## Design decisions

### D1: Capability-interface dispatch stays as nested checks

The dispatch mixes sealed variants (`QueryField.QueryTableField`) with
capability interfaces (`BatchKeyField`, `MethodBackedField`,
`SqlGeneratingField`). A sealed `switch` on `ChildField` / `RootField` can't
directly match a capability interface.

**Decision:** keep the outer switch on the sealed hierarchy; resolve capability
within each sealed arm. For example:

```java
case ChildField.ServiceTableField stf -> {
    // stf already implements BatchKeyField + MethodBackedField + SqlGeneratingField
    // by its class declaration — no runtime check needed, narrow component types
    // per the "Narrow component types over broad interfaces" principle.
    builder.addMethod(buildServiceDataFetcher(stf.name(), stf.batchKey(), stf.method(), stf.returnType(), parentTable, className));
    builder.addMethod(buildServiceRowsMethod(stf.batchKey(), stf.returnType()));
}
```

Variants that genuinely need capability dispatch (e.g. split vs service
variants of the same shape) get a small helper method dispatching on
`BatchKey` (itself sealed), not on `instanceof`. The model already carries
narrow component types (roadmap principle "Narrow component types over
broad interfaces"), so most capability checks collapse to direct field
access.

**Rejected alternative:** adding a `generationCategory()` component to every
field variant. This shifts classification into the model for no net gain —
the category is already implicit in the sealed class.

### D2: `NotImplementedYet` as explicit named branches

Two plausible shapes:
- **(a)** One `buildXxxStub(field)` method per unimplemented variant.
- **(b)** A sentinel record returned by the dispatch, handled in one place.

**Decision: (a), explicit named branches.** Each unimplemented variant gets
its own case arm and its own stub builder method, so the set of gaps is
grep-able and each gap has a Javadoc pointing at the tracking issue.
Boilerplate is ~4 lines per variant (case arm + method signature delegating
to a shared `buildStub(fieldName, message)` helper). With ~6 unimplemented
variants, that is ~24 lines — the readability tradeoff comes out in (a)'s
favour.

The shared helper carries a message parameter so stubs fail with an
actionable message:

```java
private static MethodSpec buildStub(String fieldName, String reason) {
    return MethodSpec.methodBuilder(fieldName)
        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
        .returns(Object.class)
        .addParameter(ENV, "env")
        .addStatement("throw new $T($S)", UnsupportedOperationException.class, reason)
        .build();
}

private static MethodSpec buildMutationInsertStub(MutationField.MutationInsertTableField f) {
    return buildStub(f.name(),
        "Mutation generator not yet implemented — tracked as 'Stubs to complete' #4 in rewrite-roadmap.md");
}
```

**Rejected alternative (b):** a sentinel enum. Less boilerplate, but loses
the Javadoc-per-gap hook and makes P2 #4 (validator coupling) harder: the
validator would need runtime introspection to know which variants stub,
rather than a static list.

### D3: Validator coupling (P2 #4) consumes a static set

`TypeFetcherGenerator` exposes a `Set<Class<? extends GraphitronField>>
UNIMPLEMENTED_VARIANTS` constant — the same set the stub arms reference.
`GraphitronSchemaValidator` adds one arm:

```java
if (UNIMPLEMENTED_VARIANTS.contains(field.getClass())) {
    errors.add(new ValidationError(
        field.qualifiedName() + ": field variant "
            + field.getClass().getSimpleName()
            + " is not yet supported by the generator (see rewrite-roadmap.md)",
        field.location()));
}
```

This is not part of this plan's commit — it happens under P2 #4. But the
`UNIMPLEMENTED_VARIANTS` constant is defined in this plan so P2 #4 is a
one-line validator change.

---

## Implementation order

| Step | What |
|---|---|
| 1 | Extract shared `buildStub(String, String)` helper; delete the current single-argument form |
| 2 | Add `UNIMPLEMENTED_VARIANTS` constant listing `Class<? extends GraphitronField>` for each currently-stubbed variant |
| 3 | Replace `generateTypeSpec`'s `instanceof` chain with a sealed `switch` on `GraphitronField`. Fan out to the sealed children (`RootField`, `ChildField`) via nested switches where required. Use `yield`-based switch expressions to compose the `List<MethodSpec>` result |
| 4 | Add one named `buildXxxStub` method per unimplemented variant. Each delegates to `buildStub(name, "...")` with a variant-specific reason string |
| 5 | Prove exhaustiveness: remove every `default ->` arm. Compiler must accept the switch without one |
| 6 | Update `UNIMPLEMENTED_VARIANTS` Javadoc to call out that adding a new permit without adding a case is a compile error, and adding a case that delegates to `buildStub` must also add the class to `UNIMPLEMENTED_VARIANTS` |

Steps 1–6 stay in `TypeFetcherGenerator.java`. No cross-module changes.

---

## Test strategy

### Regression

All 416 existing `graphitron-rewrite` tests must pass unchanged. The
refactor is behaviour-preserving — same methods emitted, same stubs for
the same variants.

### Meta-test (rides along — satisfies P1 #2)

New `GeneratorCoverageTest` under `graphitron-rewrite/src/test/`:

```java
@Test
void everyChildFieldPermitIsHandledByTypeFetcherGenerator() {
    var permits = Stream.concat(
        Arrays.stream(ChildField.class.getPermittedSubclasses()),
        Arrays.stream(RootField.class.getPermittedSubclasses())
    ).collect(toSet());

    var handled = Stream.concat(
        TypeFetcherGenerator.IMPLEMENTED_VARIANTS.stream(),
        TypeFetcherGenerator.UNIMPLEMENTED_VARIANTS.stream()
    ).collect(toSet());

    assertThat(permits).isSubsetOf(handled);
}
```

Adding a new `permits` variant without updating the generator becomes a
compile error (the switch) OR a test failure (the meta-test) — belt and
braces. The meta-test also serves P1 #2 for this generator; analogous
tests for the other three generators can be added incrementally as they
gain equivalent coverage sets.

---

## Interaction with existing plans

- **P1 #2 (variant-coverage meta-test)** — this plan ships the first instance
  of the meta-test as `GeneratorCoverageTest`. P1 #2 generalises it to every
  sealed root and every generator.
- **P2 #4 (validator ≠ can generate)** — consumes `UNIMPLEMENTED_VARIANTS`.
  Single-line validator addition.
- **Stubs to complete (#1–#4 in Remaining Work)** — each newly-implemented
  variant deletes one entry from `UNIMPLEMENTED_VARIANTS` and replaces one
  stub arm with a real implementation. The sealed switch guarantees they
  can't forget to remove the stub.

---

## Risk and reversibility

**Low risk.** Pure refactor of one method's dispatch. No emitted-code
changes. Existing tests assert structural properties (method names,
return types) and are agnostic to dispatch shape.

**Fully reversible.** Revert the commit; `buildStub` goes back to
single-argument and the `instanceof` chain returns. No consumer-visible
change.

**Effort:** half a day to implement, including the meta-test. Review: this
plan.
