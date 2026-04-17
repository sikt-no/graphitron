# Plan — Sealed-Switch Generator Dispatch

Convert the field-dispatch chain in `TypeFetcherGenerator.generateTypeSpec`
from an `instanceof` chain terminating in `buildStub(field.name())` into a
sealed `switch` statement. Restore the exhaustive-switch guarantee the sealed
hierarchy is meant to provide — adding a new `permits` variant must become a
compile error, not a runtime `UnsupportedOperationException`.

Tracked as P1 #1 in
[rewrite-roadmap.md](rewrite-roadmap.md#architecture-review-priorities-2026-04-17).

---

## Scope

**In scope:** `TypeFetcherGenerator.generateTypeSpec`
([`TypeFetcherGenerator.java:111-132`](../graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java)).
This is the only generator where the dispatch emits a runtime stub —
`buildQueryLookupFetcher`, `buildQueryTableFetcher`,
`buildQueryConnectionFetcher`, and `buildServiceDataFetcher` are the
implemented branches; everything else falls through to `buildStub`.

**Out of scope:** `GraphitronWiringClassGenerator`, `TypeClassGenerator`,
`TypeConditionsGenerator`. Their `instanceof` checks are filters ("emit an
entry for this variant? yes/no"), not stub-emitters; exhaustiveness matters
less and the refactor would be mostly noise. Convert them only if the meta-
test from P1 #2 flags uncovered variants.

---

## Counting the leaves

The review of this plan's first draft surfaced that "~6 unimplemented
variants" was a large undercount. The real sealed taxonomy:

| Sealed root | Direct permits | Leaf count |
|---|---|---|
| `QueryField` | 10 (all leaves) | 10 |
| `MutationField` | 6 (all leaves) | 6 |
| `ChildField` | 15 (incl. `TableTargetField` nested sealed) | 15 direct + 8 via `TableTargetField` = 23 |

Of those 39 total leaves, `TypeFetcherGenerator` currently handles
`QueryLookupTableField`, `QueryTableField` (with and without
`FieldWrapper.Connection`), `ColumnField` (when `parentTable != null`), and
`ServiceTableField` + `SplitTableField` via the `BatchKeyField` branch.
Everything else — roughly **30+ leaves** — currently falls through to
`buildStub`.

This count drives the D2 design choice below.

---

## Design decisions

### D1: Capability-interface dispatch stays inside sealed arms

The dispatch mixes sealed variants (`QueryField.QueryTableField`) with
capability interfaces (`BatchKeyField`, `MethodBackedField`,
`SqlGeneratingField`). A sealed `switch` on `ChildField` / `RootField` can't
match capability interfaces directly.

**Decision:** keep the outer switch on the sealed hierarchy; use narrow
component access within each arm. The model already declares narrow
components (roadmap principle *"Narrow component types over broad
interfaces"*) — a `ServiceTableField` statically *is* a `MethodBackedField +
BatchKeyField + SqlGeneratingField`, so each arm calls `.method()`,
`.batchKey()`, etc. directly rather than checking `instanceof`.

```java
case ChildField.ServiceTableField stf -> {
    builder.addMethod(buildServiceDataFetcher(
        stf.name(), stf.batchKey(), stf.method(), stf.returnType(),
        parentTable, className));
    builder.addMethod(buildServiceRowsMethod(stf.batchKey(), stf.returnType()));
}
```

Where genuine capability dispatch is unavoidable (e.g. two variants share a
body shape parameterised by `BatchKey`), dispatch on `BatchKey` (itself
sealed), not on `instanceof`.

**Rejected alternative:** adding a `generationCategory()` component to every
field variant. Redundant — the category is already implicit in the sealed
class.

### D2: Stub reasons live in a single `Map<Class<?>, String>`

At 30+ stubbed variants, an explicit named stub method per variant (the
first-draft shape) would add ~120 lines of one-line method declarations.
The grep-ability argument thins out at that scale: grepping a `Map` entry
is no worse than grepping a method name, and the map is the natural data
structure for the validator to consume under P2 #4.

**Decision:** a single `NOT_IMPLEMENTED_REASONS` map replaces the per-variant
stub methods. Each sealed family has one `NotImplementedYet` arm per
stubbed leaf; the arm calls a shared `stub(field)` helper that looks up
the reason:

```java
private static final Map<Class<? extends GraphitronField>, String>
    NOT_IMPLEMENTED_REASONS = Map.ofEntries(
    Map.entry(MutationField.MutationInsertTableField.class,
        "Mutation generator not yet implemented — 'Stubs to complete' #4"),
    Map.entry(MutationField.MutationUpdateTableField.class,
        "Mutation generator not yet implemented — 'Stubs to complete' #4"),
    // … one entry per stubbed leaf, grouped by comment blocks matching the
    // 'Stubs to complete' categories (G5 inline TableField, G6 split/lookup,
    // query-interface fetchers, mutation DML, misc leaves)
    Map.entry(ChildField.SplitLookupTableField.class,
        "Split/lookup rows method — 'Stubs to complete' #2 (G6)")
);

private static MethodSpec stub(GraphitronField field) {
    String reason = Objects.requireNonNull(
        NOT_IMPLEMENTED_REASONS.get(field.getClass()),
        () -> "No stub reason registered for " + field.getClass().getSimpleName()
              + " — either implement a real generator branch or add an entry to NOT_IMPLEMENTED_REASONS");
    return MethodSpec.methodBuilder(field.name())
        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
        .returns(Object.class)
        .addParameter(ENV, "env")
        .addStatement("throw new $T($S)", UnsupportedOperationException.class, reason)
        .build();
}
```

Each arm for a stubbed leaf is a single line:

```java
case MutationField.MutationInsertTableField f -> builder.addMethod(stub(f));
```

Total cost: ~30 one-line case arms + ~30 map entries = ~60 lines, down
from ~120.

**Rejected alternative (a):** one `buildXxxStub` method per variant. Too
much boilerplate at this leaf count.

**Rejected alternative (b):** a sentinel return type handled in one place.
The validator (P2 #4) would still need the set of stubbed classes; a
separate constant for that alongside a sentinel is worse than the map,
which is the set.

### D3: Validator coupling (P2 #4) consumes `NOT_IMPLEMENTED_REASONS.keySet()`

`NOT_IMPLEMENTED_REASONS.keySet()` is the set of field classes whose
generator throws at request time. `GraphitronSchemaValidator` adds one arm:

```java
if (TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS.containsKey(field.getClass())) {
    errors.add(new ValidationError(
        field.qualifiedName() + ": "
            + TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS.get(field.getClass()),
        field.location()));
}
```

This is not part of this plan's commit — it happens under P2 #4. But the
map shape is designed so that addition is a single lookup.

### D4: `ColumnField` with null `parentTable` is an error, not a silent stub

The current loop short-circuits `ColumnField` only when `parentTable != null`
(line 112–113). `ColumnField` with null `parentTable` falls through to
`buildStub` — a silent runtime failure. A sealed switch must confront this
case directly.

**Decision:** it is unreachable in a classified schema. `ColumnField`
represents a column projection on its parent; the parent must be
table-backed for the column to resolve. The classifier guarantees this
pairing today, but enforces it only implicitly via the table-resolution
path.

The sealed-switch refactor is the natural moment to make the invariant
explicit. Two parts:

1. **Assertion in the switch arm:**
   ```java
   case ChildField.ColumnField cf -> {
       if (parentTable == null) {
           throw new IllegalStateException(
               "ColumnField '" + cf.qualifiedName()
               + "' classified on a non-table-backed parent — "
               + "classifier invariant violated");
       }
       // ColumnField with a table-backed parent is handled in wiring via ColumnFetcher;
       // no method emitted here.
   }
   ```
2. **Validator check (rides along):** `GraphitronSchemaValidator` adds a
   structural rule rejecting any `ColumnField` whose parent type is not
   `TableBackedType`, so the error surfaces at validation time rather than
   as an `IllegalStateException` at generation time. One short arm in the
   existing field-type switch.

This closes the silent-failure path without introducing it as a new
"NotImplementedYet" entry.

---

## Implementation order

| Step | What |
|---|---|
| 1 | Add `NOT_IMPLEMENTED_REASONS: Map<Class<? extends GraphitronField>, String>` constant with an entry per currently-stubbed sealed leaf. Group entries by `Stubs to complete` category using block comments, so the map reads as a roadmap |
| 2 | Add the private `stub(GraphitronField)` helper. Delete the existing single-argument `buildStub(String)` method (no remaining callers after steps 3–4) |
| 3 | Replace the `instanceof` chain in `generateTypeSpec` with a sealed `switch` **statement** (not expression — variants emit 0/1/2 methods, direct `builder.addMethod(...)` calls are cleaner than composing `List<MethodSpec>` via `yield`). Fan out to the sealed children (`RootField` → `QueryField`/`MutationField`; `ChildField` → direct permits and nested `TableTargetField`) via nested switches |
| 4 | Each stubbed leaf gets a one-liner: `case X x -> builder.addMethod(stub(x));`. Each implemented leaf gets the existing method calls, lifted from the `instanceof` chain. `ColumnField` arm follows D4 (assertion + no-op for the happy path) |
| 5 | Confirm exhaustiveness: no `default ->` arms anywhere in the nested switches. Compiler must accept without them |
| 6 | Add the structural validator rule from D4 (reject `ColumnField` on a non-table-backed parent) to `GraphitronSchemaValidator` |
| 7 | Update `NOT_IMPLEMENTED_REASONS` Javadoc to state the invariants: adding a new sealed permit triggers a compile error in the switch; adding a case that calls `stub(f)` must add the class to `NOT_IMPLEMENTED_REASONS`; removing the last `stub(f)` reference for a class must remove the map entry |

Steps 1–5 and 7 stay in `TypeFetcherGenerator.java`. Step 6 touches
`GraphitronSchemaValidator.java` only.

---

## Test strategy

### Regression

All 416 existing `graphitron-rewrite` tests must pass unchanged. The
refactor is behaviour-preserving — same methods emitted, same stubs emit
an `UnsupportedOperationException` (now with an actionable message).

### Map-integrity sanity test (rides along)

Not the full P1 #2 meta-test — that one iterates every sealed root in
`model/` and asserts classification-test coverage, which is a broader
concern. This plan ships a narrower sanity test that guards the map
against rot:

```java
@Test
void notImplementedReasonsContainsOnlyConcreteSealedLeaves() {
    var roots = List.of(
        GraphitronField.class, RootField.class, QueryField.class,
        MutationField.class, ChildField.class, InputField.class);
    var leaves = roots.stream()
        .flatMap(r -> sealedLeaves(r).stream())
        .collect(toSet());

    assertThat(TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS.keySet())
        .as("every map key must be a concrete sealed leaf — no interfaces, no "
            + "classes outside the GraphitronField hierarchy")
        .isSubsetOf(leaves);
}

/** Recursive leaf walker — `getPermittedSubclasses()` is shallow; it returns
 *  `TableTargetField.class` (a nested sealed interface) rather than its eight
 *  concrete implementations. */
private static Set<Class<?>> sealedLeaves(Class<?> type) {
    var direct = type.getPermittedSubclasses();
    if (direct == null || direct.length == 0) return Set.of(type);
    return Arrays.stream(direct)
        .flatMap(p -> sealedLeaves(p).stream())
        .collect(toSet());
}
```

What this catches:
- A class in `NOT_IMPLEMENTED_REASONS` that is not a sealed leaf (typo,
  interface, or stale entry after a variant was removed).
- Nothing else — it does **not** assert that every leaf is either handled
  or in the map. The sealed `switch` already guarantees that via
  compilation; a runtime test adds no signal.

The broader "every sealed leaf has ≥1 classification test case" meta-test
belongs to P1 #2 proper and is not scoped here.

### Compile check

`mvn -pl graphitron-rewrite compile` must succeed without any
`default ->` arms in the refactored switches. This is the load-bearing
guarantee: remove an arm and compilation fails.

---

## Interaction with existing plans

- **P1 #2 (variant-coverage meta-test)** — the sanity test shipped here is
  a narrow slice. P1 #2 proper covers "every leaf has a classification
  test case across every sealed root" and should reuse the `sealedLeaves`
  helper from this plan.
- **P2 #4 (validator ≠ can generate)** — consumes
  `NOT_IMPLEMENTED_REASONS`. Single-arm validator addition, already
  drafted in D3.
- **Stubs to complete (#1–#4 in Remaining Work)** — each newly-implemented
  variant (i) replaces its `case X x -> builder.addMethod(stub(x));` arm
  with a real implementation and (ii) removes its `NOT_IMPLEMENTED_REASONS`
  entry. The map-integrity test fails loudly if step (ii) is forgotten.

---

## Risk and reversibility

**Low risk.** Behaviour-preserving refactor of one method's dispatch plus
two new structural validator arms. Existing tests assert structural
properties (method names, return types) and are agnostic to dispatch
shape. Stub error messages change from the default
`UnsupportedOperationException` to a variant-specific message — strictly
an improvement.

**Fully reversible.** Revert the commit: `buildStub` returns to its
single-argument shape and the `instanceof` chain comes back. No
consumer-visible change.

**Effort:** ~1 day — enumerating the 30+ sealed leaves and writing their
map entries and switch arms is the bulk of the work. The sanity test and
validator additions are short.
