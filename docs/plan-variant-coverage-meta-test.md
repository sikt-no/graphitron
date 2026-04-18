# Variant-Coverage Meta-Test Plan

> **Status:** Draft. Five design decisions ship with recommended directions and
> alternatives. Per the roadmap (`docs/rewrite-roadmap.md` P1 #1), this
> subsumes `plan-docs-as-index-into-tests.md` step 5 and extends it from one
> generator map to the full sealed taxonomy.

## Overview

Add a meta-test that iterates every sealed root in
`graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/model/` and
asserts that every concrete leaf permit has **both** (a) at least one
classification test case and (b) at least one generator branch — or a
documented allowlist entry with a reason.

This closes a recurring class of bug: a new sealed leaf is added to the
model but nobody writes a test case for it, or no generator dispatches on
it, and the gap is only noticed when a schema hits the missing path at
build or request time (the `ObjectBased` gap called out under `docs/rewrite-roadmap.md` P2 #5 is the
canonical example).

## Current State

### What's already in place

- **`GeneratorCoverageTest`** (`graphitron-rewrite/src/test/java/no/sikt/graphitron/rewrite/generators/GeneratorCoverageTest.java:18-47`).
  A single-assertion JUnit 5 test that walks six sealed roots
  (`GraphitronField`, `RootField`, `QueryField`, `MutationField`,
  `ChildField`, `InputField`) via a recursive `sealedLeaves(Class<?>)`
  helper (lines 25-31) and asserts that every key in
  `TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS` is a legitimate sealed
  leaf. Guards the map against stale entries; does **not** check that
  every leaf has coverage.
- **`TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS`**
  (`graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java:119-191`).
  `Map<Class<? extends GraphitronField>, String>` with 33 entries across
  `QueryField` (6) / `MutationField` (6) / `ChildField` (21). Consumed by
  `stub(f)` (line 914) which throws `UnsupportedOperationException` with
  the map-registered reason.
- **Sealed `switch` in `TypeFetcherGenerator.generateTypeSpec`**
  (lines 210-286). Exhaustive over `GraphitronField`; six real arms, ~33
  stub arms, three defensive "cannot occur here" arms
  (`InputField`/`NotGeneratedField`/`UnclassifiedField`).
- **26 parameterised classification enums** in
  `graphitron-rewrite/src/test/java/no/sikt/graphitron/rewrite/GraphitronSchemaBuilderTest.java`
  (~150-180 cases total). Each enum case is
  `(description: String, sdl: String, assertions: Consumer<GraphitronSchema>)`
  driven by `@ParameterizedTest @EnumSource(...)`. The link from a case
  to the sealed leaf it covers is **currently implicit** — it lives in
  the description string (`"→ VariantName"`) and in
  `assertThat(...).isInstanceOf(X.class)` inside the assertion lambda.
  No machine-readable metadata.

### Sealed-root inventory

17 sealed roots in `model/` (full table below). Grouped by usage:

| Group | Roots | Transitive leaves |
|---|---|---|
| **Field taxonomy** | `GraphitronField`, `RootField`, `QueryField`, `MutationField`, `ChildField` (incl. nested `TableTargetField`), `InputField` | 34 |
| **Type taxonomy** | `GraphitronType` (incl. nested `TableBackedType`, `ResultType`, `InputType`) | 15 |
| **Return shape** | `ReturnTypeRef` | 4 |
| **Narrow components** | `BatchKey`, `CallSiteExtraction`, `FieldWrapper`, `JoinStep`, `MethodRef.Param`, `OrderBySpec`, `ParamSource`, `ParticipantRef`, `WhereFilter` | 28 |

Total: ~81 transitive leaves if every sealed root counts.

### Key discoveries

- **Only `TypeFetcherGenerator` dispatches on a sealed root.**
  `TypeClassGenerator` and `TypeConditionsGenerator` use `instanceof`
  filtering on specific leaves, not exhaustive sealed switches. The other
  generators (`ConnectionHelperClassGenerator`, `GraphitronValuesClassGenerator`, …)
  emit singleton utility classes and don't dispatch per-variant at all.
- **`getPermittedSubclasses()` has exactly one caller** today —
  `GeneratorCoverageTest.sealedLeaves` (line 26). The recursive leaf walker
  is the right seed for the meta-test; it just needs to be factored out
  so both tests can share it.
- **No `ClassificationCase` interface exists yet.**
  `plan-docs-as-index-into-tests.md:105-148` sketches it but labelled
  it deferred until the taxonomy stabilised. It has.
- **Descriptions are already normalised** to `"trigger → VariantName"`
  form (step 2 of `plan-docs-as-index-into-tests.md` is done), so the
  retrofit can mostly mechanically extract the variant class from the
  description — but the roadmap explicitly rejects string matching as
  the runtime check, so this helps retrofit speed, not test reliability.

## What We're NOT Doing

- **Not converting `TypeClassGenerator` or `TypeConditionsGenerator`
  to sealed switches.** That's a separate refactor; this plan only
  *observes* generator coverage, it doesn't reshape generators.
- **Not implementing `plan-docs-as-index-into-tests.md` steps 3 and 4**
  (re-sectioning enums, rewiring the docs). The interface this plan
  introduces is a prerequisite for step 5 but independent of 3 and 4.
- **Not enforcing coverage for narrow component sealed types in phase 1.**
  `BatchKey`/`CallSiteExtraction`/etc. are real but the assertion shape
  differs (they're *attributes* of classified fields, not classification
  outcomes themselves). Phase 3 tackles them as a follow-up.
- **Not merging `GeneratorCoverageTest` into the new test.** Keep the
  existing narrow check; add a broader sibling. Each has one job.
- **No retroactive allowlist.** Every existing leaf either gets
  coverage or gets a documented exception in the allowlist with a
  one-line reason — no blanket suppressions.

---

## Five Design Decisions

Each section presents options, tradeoffs, and a recommended direction.
The decisions are not independent — decision 1 shapes the effort estimate
for decision 2, and decision 3 depends on decision 5's scope. Read in
order.

### Decision 1: How cases declare the variant they cover

**Option A — `ClassificationCase` interface with `Set<Class<?>> variants()`** *(recommended)*

```java
interface ClassificationCase {
    /** The sealed leaves this case is the primary coverage for. */
    Set<Class<?>> variants();
}

enum TableFieldCase implements ClassificationCase {
    SINGLE_RETURN_TYPE(
        "@table return type (default) → TableField",
        Set.of(TableField.class),
        """
        type Film @table(name: "film") { title: String }
        type Query { film: Film }
        """,
        schema -> assertThat(schema.field("Query", "film")).isInstanceOf(TableField.class)),
    …
}
```

- **Pros:** machine-readable, survives description rewrites, compiler
  catches typos (`Set.of(TableFiield.class)` won't compile). A `Set`
  (not single `Class<?>`) handles cases that cover multiple leaves — e.g.
  `TableInputTypeCase.EXPLICIT_TABLE_DIRECTIVE` classifies a whole input
  tree, legitimately covering `TableInputType` + the `ColumnField`
  variants inside it.
- **Cons:** retrofit cost — 26 enums, ~150-180 cases touched to add the
  parameter. Each case gains one constructor argument; the change is
  purely additive and mechanical.
- **Effort:** ~half a day with a scripted retrofit pulling variants from
  the already-normalised descriptions, followed by human review of the
  handful of multi-variant cases.

**Option B — Description-string matching (parse `→ VariantName`)**

- **Pros:** zero code changes to existing enums; step 2 of
  `plan-docs-as-index-into-tests.md` already normalised descriptions to
  this shape.
- **Cons:** exactly the failure mode `plan-docs-as-index-into-tests.md`
  called out ("strictly less reliable than an interface"). A reviewer
  rewording `"→ PropertyField"` to `"→ property field"` silently drops
  coverage. A typo in the variant name isn't a compile error. Cases
  covering multiple variants have no natural encoding.
- **Rejected** — the roadmap is explicit here.

**Option C — Scan `isInstanceOf(X.class)` from the assertion lambda**

- **Pros:** zero retrofit.
- **Cons:** fragile (not every assertion ends in `isInstanceOf` — some
  cast first and assert on fields); bytecode parsing or AST inspection
  adds tooling complexity; silently wrong when the assertion shape
  drifts. Too clever.
- **Rejected.**

**Option D — Annotation on each constant**

```java
enum TableFieldCase {
    @Covers(TableField.class)
    SINGLE_RETURN_TYPE(…);
}
```

- **Pros:** metadata is visually separate from constructor args.
- **Cons:** enum constants can't carry annotations readable at runtime
  via standard reflection without custom machinery; adds a parallel
  path to the interface we'd eventually want anyway; harder to enforce
  via the compiler.
- **Rejected** in favour of A.

**Recommendation: A.** The retrofit cost is paid once; the reliability
gain is permanent; the interface is the shape
`plan-docs-as-index-into-tests.md` already planned for step 5.

### Decision 2: Which sealed roots are in scope

**Option A — Only the field + type taxonomy roots** *(recommended for phase 1)*

Scope: `GraphitronField` (and its transitive nested sealeds `RootField`,
`QueryField`, `MutationField`, `ChildField`, `TableTargetField`,
`InputField`) and `GraphitronType` (and its transitive nested sealeds
`TableBackedType`, `ResultType`, `InputType`). That's 49 leaves.

- **Pros:** these are the taxonomies that classification tests actually
  classify *into*. A case's `variants()` naturally references one of
  them. The SDL-in → classified-schema-out pipeline is the existing
  test shape.
- **Cons:** narrow component taxonomies (`BatchKey`, `ParamSource`,
  `CallSiteExtraction`, …) are left out of phase 1. They're real
  coverage gaps, but the test shape differs — see phase 3.

**Option B — All 17 sealed roots, phase 1**

- **Pros:** maximally exhaustive from day one.
- **Cons:** narrow components are *attributes* of classified fields, not
  classification outcomes. Writing "a case that covers
  `BatchKey.ObjectBased`" means writing a test that builds a schema,
  finds a field that should have an `ObjectBased` batch key, and
  asserts on that attribute. This is a different assertion shape — an
  `AttributeCase` rather than a `ClassificationCase`. Mixing both in
  one interface conflates concerns.

**Option C — Field/type plus a curated generator-relevant subset of narrow types**

E.g., add `BatchKey` and `CallSiteExtraction` (both drive real
generator branching) but skip `FieldWrapper` and `JoinStep` (structural).

- **Pros:** covers the generator-relevant ones without the full sweep.
- **Cons:** "generator-relevant" isn't a stable line — today's
  structural component is tomorrow's dispatch key. Becomes a
  judgment-call list that rots.

**Recommendation: A for phase 1, C as a curated extension in phase 3,
B only if/when an `AttributeCase`-style test shape has proven itself.**

### Decision 3: How "generator branch exists" is checked

**Option A — Keep `NOT_IMPLEMENTED_REASONS`; add sibling `IMPLEMENTED_LEAVES` and `NOT_DISPATCHED_LEAVES` sets** *(recommended)*

```java
class TypeFetcherGenerator {
    static final Map<Class<? extends GraphitronField>, String>
        NOT_IMPLEMENTED_REASONS = Map.ofEntries(…);         // existing, unchanged

    static final Set<Class<? extends GraphitronField>>
        IMPLEMENTED_LEAVES = Set.of(
            ChildField.ColumnField.class,
            QueryField.QueryLookupTableField.class,
            QueryField.QueryTableField.class,
            ChildField.ServiceTableField.class,
            ChildField.SplitTableField.class,
            ChildField.SplitLookupTableField.class);        // new

    static final Set<Class<? extends GraphitronField>>
        NOT_DISPATCHED_LEAVES = Set.of(
            GraphitronField.NotGeneratedField.class,
            GraphitronField.UnclassifiedField.class,
            /* + all four InputField.* leaves */ );         // new
}
```

Meta-test asserts
`IMPLEMENTED_LEAVES ∪ NOT_IMPLEMENTED_REASONS.keySet() ∪ NOT_DISPATCHED_LEAVES = sealedLeaves(GraphitronField)`
and that the three sets are disjoint.

- **Pros:** keeps existing machinery; each set is a single-concept
  declaration; forces contributors to move a leaf between sets when
  implementing. Easy to review — a PR that implements a stub should
  show one line removed from `NOT_IMPLEMENTED_REASONS` and one line
  added to `IMPLEMENTED_LEAVES`.
- **Cons:** two sets to maintain. But that's the point — the
  maintenance *is* the signal.

**Option B — Single `GeneratorStatus` enum map**

```java
enum GeneratorStatus { IMPLEMENTED, STUBBED, NOT_DISPATCHED }
static final Map<Class<…>, GeneratorStatus> STATUSES = …;
```

- **Pros:** one map, one source of truth.
- **Cons:** loses the reason strings that `NOT_IMPLEMENTED_REASONS`
  carries; retrofits every existing entry; bigger blast radius for
  smaller payoff.

**Option C — Parse the switch body via source reflection**

- **Rejected** as fragile — too close to option C of decision 1.

**Option D — `@GeneratesFor(Class<?>)` annotations on generator methods**

- **Pros:** metadata next to the code.
- **Cons:** doesn't naturally fit a pattern switch (arms aren't methods);
  introduces a parallel encoding to the existing map; worse ergonomics.

**Recommendation: A.** Additive, keeps the existing `stub(f)` mechanism,
and `GeneratorCoverageTest` extends to assert the partition.

### Decision 4: Allowlist shape

Used for leaves that legitimately need no case or no branch.

**Option A — Typed allowlist sets in the meta-test** *(recommended)*

```java
// In VariantCoverageTest
private static final Map<Class<?>, String> NO_CLASSIFICATION_CASE_REQUIRED = Map.of(
    /* currently empty — every leaf should have a case */);

private static final Map<Class<?>, String> NO_GENERATOR_BRANCH_REQUIRED = Map.of(
    /* populated implicitly via TypeFetcherGenerator.NOT_DISPATCHED_LEAVES */);
```

- **Pros:** centralised, paired with reasons, easy to audit. Lives with
  the test, not sprayed across `model/`.
- **Cons:** the file growing over time. Acceptable — each entry carries
  a reason string, so it reads as prose.

**Option B — `@NoClassificationCase("reason")` / `@NoGeneratorBranch("reason")` annotations on permits**

```java
@NoGeneratorBranch("input fields never reach the fetcher switch")
record ColumnField(…) implements InputField { … }
```

- **Pros:** discoverable at the permit site.
- **Cons:** adds annotation machinery; spreads cross-cutting coverage
  metadata across the model; makes model changes noisier; the same
  annotation would repeat across all four `InputField.*` leaves when
  the root cause is structural (filtered before dispatch).

**Option C — One annotation on the sealed root**

```java
@NoGeneratorBranch(reason = "filtered before dispatch")
sealed interface InputField … {}
```

- **Pros:** one declaration, applies to all permits.
- **Cons:** can't easily express partial exceptions (some permits exempt,
  others not); still annotation machinery.

**Recommendation: A.** Keep allowlist in the test file; keep `model/`
free of test metadata.

### Decision 5: Scope of "generator branch"

**Option A — Only `TypeFetcherGenerator` in phase 1** *(recommended)*

- **Pros:** only generator with real sealed dispatch today; asserting
  against a non-dispatch generator would be meaningless.
- **Cons:** `TypeClassGenerator`'s `instanceof` filtering is also a form
  of coverage that could rot (new `ChildField` leaf added, filter
  untouched, silent drop). Not handled in phase 1.

**Option B — Also `TypeClassGenerator` and `TypeConditionsGenerator`**

- **Pros:** broader safety net.
- **Cons:** both use `instanceof` chains, not sealed switches.
  Introspection shape differs. Either:
  - (i) convert them to sealed switches first (out-of-scope refactor), or
  - (ii) write a parallel `FIELD_TYPES_HANDLED_BY_TYPE_CLASS_GENERATOR`
    set duplicated manually — ritualistic rather than structural.

**Option C — Parametric helper usable by any future sealed-dispatch generator**

The meta-test exposes a helper
`assertSealedDispatchComplete(Class<?> root, Set<Class<?>> implemented, Set<Class<?>> stubbed, Set<Class<?>> notDispatched, Map<Class<?>,String> allowlist)`.
Phase 1 calls it once (for `TypeFetcherGenerator` / `GraphitronField`).
If `TypeConditionsGenerator` ever gets sealed-switched, adding coverage
is one call to the helper.

- **Pros:** locks the pattern; growth is cheap.
- **Cons:** marginal over A; until a second generator arrives, the
  helper has one caller.

**Recommendation: A with the structure of C.** Write the helper once
(phase 1, single caller). Adding coverage for a converted
`TypeClassGenerator` later is then one line.

---

## Chosen Approach Summary

| # | Decision | Choice |
|---|---|---|
| 1 | Variant declaration | `ClassificationCase` interface with `Set<Class<?>> variants()` |
| 2 | Sealed roots in phase 1 | `GraphitronField` + `GraphitronType` and their nested sealeds (49 leaves) |
| 3 | Branch-existence check | `IMPLEMENTED_LEAVES` + `NOT_DISPATCHED_LEAVES` sibling sets alongside `NOT_IMPLEMENTED_REASONS` |
| 4 | Allowlist | Typed `Map<Class<?>, String>` in the meta-test (reasons required) |
| 5 | Generator scope | `TypeFetcherGenerator` only; helper parameterised for future generators |

## Implementation Approach

Three phases. Each is independently shippable; phase 2 has the real cost
(retrofitting 26 enums), phases 1 and 3 are bounded.

---

## Phase 1: Generator-branch coverage

### Overview

Extend `GeneratorCoverageTest` (or add a sibling) to assert that every
sealed leaf of `GraphitronField` lands in exactly one of three sets:
implemented, stubbed, or not-dispatched. This is a straightforward
extension of the existing machinery and delivers immediate value.

### Changes Required

#### 1. `TypeFetcherGenerator` — add sibling sets

**File:** `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java`

**Changes:** Directly above the existing `NOT_IMPLEMENTED_REASONS` map
(line 119), add two sibling sets that declare the complete dispatch
status of every `GraphitronField` leaf. Keep `NOT_IMPLEMENTED_REASONS`
unchanged — it carries reason strings that the other two sets don't need.

```java
/** Leaves with real arm in {@link #generateTypeSpec}'s switch. */
static final Set<Class<? extends GraphitronField>> IMPLEMENTED_LEAVES = Set.of(
    ChildField.ColumnField.class,
    QueryField.QueryLookupTableField.class,
    QueryField.QueryTableField.class,
    ChildField.ServiceTableField.class,
    ChildField.SplitTableField.class,
    ChildField.SplitLookupTableField.class);

/** Leaves never reachable by the fetcher switch (filtered by {@link #generateForType}). */
static final Set<Class<? extends GraphitronField>> NOT_DISPATCHED_LEAVES = Set.of(
    GraphitronField.NotGeneratedField.class,
    GraphitronField.UnclassifiedField.class,
    InputField.ColumnField.class,
    InputField.ColumnReferenceField.class,
    InputField.PlatformIdField.class,
    InputField.NestingField.class);
```

#### 2. `GeneratorCoverageTest` — add partition assertion

**File:** `graphitron-rewrite/src/test/java/no/sikt/graphitron/rewrite/generators/GeneratorCoverageTest.java`

**Changes:** Add a second `@Test` asserting that
`IMPLEMENTED_LEAVES`, `NOT_IMPLEMENTED_REASONS.keySet()`, and
`NOT_DISPATCHED_LEAVES` are pairwise disjoint and jointly cover
`sealedLeaves(GraphitronField.class)`. Factor `sealedLeaves` out to a
package-visible utility so phase 2 can reuse it.

```java
@Test
void everyGraphitronFieldLeafHasAKnownDispatchStatus() {
    var leaves = sealedLeaves(GraphitronField.class);
    var implemented = TypeFetcherGenerator.IMPLEMENTED_LEAVES;
    var stubbed = TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS.keySet();
    var notDispatched = TypeFetcherGenerator.NOT_DISPATCHED_LEAVES;

    assertThat(intersection(implemented, stubbed)).as("implemented ∩ stubbed").isEmpty();
    assertThat(intersection(implemented, notDispatched)).as("implemented ∩ notDispatched").isEmpty();
    assertThat(intersection(stubbed, notDispatched)).as("stubbed ∩ notDispatched").isEmpty();

    var union = new HashSet<Class<?>>();
    union.addAll(implemented); union.addAll(stubbed); union.addAll(notDispatched);
    assertThat(union).as("every GraphitronField leaf must have a declared status").isEqualTo(leaves);
}
```

### Success Criteria

#### Automated

- [ ] `mvn test -pl :graphitron-rewrite -Dtest=GeneratorCoverageTest` passes.
- [ ] Deleting any entry from `NOT_IMPLEMENTED_REASONS` (without moving it to
  `IMPLEMENTED_LEAVES`) makes the test fail with a clear message naming
  the missing leaf.
- [ ] Adding a new sealed leaf to `ChildField` (locally, via a scratch
  edit) without updating any set makes the test fail.

#### Manual

- [ ] Read the failing-test message for the two scenarios above. Message
  must name the offending class by simple name and say which set it is
  missing from. No opaque "set mismatch" errors.

---

## Phase 2: Classification-case coverage

### Overview

Introduce the `ClassificationCase` interface; retrofit all 26
classification enums in `GraphitronSchemaBuilderTest` to implement it;
add a meta-test that asserts every leaf of `GraphitronField` and
`GraphitronType` has at least one case whose `variants()` contains it.

This is the main lift. ~150-180 enum constants gain one constructor
argument each.

### Changes Required

#### 1. New interface

**File:** `graphitron-rewrite/src/test/java/no/sikt/graphitron/rewrite/ClassificationCase.java` (new)

```java
package no.sikt.graphitron.rewrite;

import java.util.Set;

/**
 * Implemented by every enum constant in the classification test suite.
 * The {@link #variants()} set declares which sealed leaves of
 * {@link no.sikt.graphitron.rewrite.model.GraphitronField} and
 * {@link no.sikt.graphitron.rewrite.model.GraphitronType} a case
 * asserts classification for. The variant-coverage meta-test aggregates
 * these sets across all cases and requires full coverage (minus a
 * documented allowlist).
 *
 * <p>A case usually covers one variant. Cases that classify a whole
 * type tree (e.g. a {@code TableInputType} case that incidentally
 * asserts shape of its {@code InputField} children) may return multiple.
 */
public interface ClassificationCase {
    Set<Class<?>> variants();
}
```

#### 2. Retrofit all 26 enums

**File:** `graphitron-rewrite/src/test/java/no/sikt/graphitron/rewrite/GraphitronSchemaBuilderTest.java`

**Pattern** (example — apply to every enum):

```java
// before
enum TableFieldCase {
    SINGLE_RETURN_TYPE(
        "@table return type (default) → TableField",
        "<sdl>",
        schema -> …),
    …
    TableFieldCase(String description, String sdl, Consumer<GraphitronSchema> assertions) { … }
}

// after
enum TableFieldCase implements ClassificationCase {
    SINGLE_RETURN_TYPE(
        "@table return type (default) → TableField",
        Set.of(TableField.class),
        "<sdl>",
        schema -> …),
    …
    TableFieldCase(String description, Set<Class<?>> variants, String sdl, Consumer<GraphitronSchema> assertions) { … }
    @Override public Set<Class<?>> variants() { return variants; }
}
```

**Retrofit approach** — two passes:

1. **Mechanical pass.** A local script extracts the `→ VariantName` suffix
   from each description, resolves it to a fully-qualified class by
   searching `model/`, and emits the `Set.of(...)` argument. Run this
   once; review the diff.
2. **Manual pass.** Audit the enums that classify type trees (`TableInputTypeCase`,
   `ResultTypeCase`, `ErrorTypeCase`, `RootFieldCase`) — many of these
   cases legitimately cover multiple variants. Expand their `variants()`
   sets based on what their assertion lambda actually checks.

A few cases test **error** paths (ill-formed schemas that should fail
classification). Those still cover a variant — `UnclassifiedField` or
`UnclassifiedType` — and should declare so.

#### 3. New meta-test

**File:** `graphitron-rewrite/src/test/java/no/sikt/graphitron/rewrite/VariantCoverageTest.java` (new)

```java
package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.generators.GeneratorCoverageTest;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import org.junit.jupiter.api.Test;
// …

class VariantCoverageTest {

    /**
     * Leaves that legitimately don't need a classification case. Each entry
     * carries a one-line reason. The goal is for this map to be empty —
     * every schema-reachable leaf should have a case demonstrating the
     * classifier lands there.
     */
    private static final Map<Class<?>, String> NO_CASE_REQUIRED = Map.of(
        // initially empty; populate only when a leaf is unreachable from
        // any valid or invalid schema.
    );

    private static final List<Class<?>> ROOTS = List.of(
        GraphitronField.class, GraphitronType.class);

    @Test
    void everySealedLeafHasAClassificationCase() {
        var leaves = ROOTS.stream()
            .flatMap(r -> GeneratorCoverageTest.sealedLeaves(r).stream())
            .collect(toSet());
        var covered = allClassificationCases().stream()
            .flatMap(c -> c.variants().stream())
            .collect(toSet());
        var missing = leaves.stream()
            .filter(l -> !covered.contains(l))
            .filter(l -> !NO_CASE_REQUIRED.containsKey(l))
            .toList();
        assertThat(missing)
            .as("every sealed leaf must have at least one classification case")
            .isEmpty();
    }

    @Test
    void allowlistEntriesAreStillLeaves() {
        var leaves = ROOTS.stream()
            .flatMap(r -> GeneratorCoverageTest.sealedLeaves(r).stream())
            .collect(toSet());
        assertThat(NO_CASE_REQUIRED.keySet())
            .as("allowlist must not contain stale (non-leaf) classes")
            .allMatch(leaves::contains);
    }

    /** Reflection: find all {@code enum … implements ClassificationCase} inside
     *  {@code GraphitronSchemaBuilderTest} and flatten their constants. */
    private static List<ClassificationCase> allClassificationCases() {
        return Arrays.stream(GraphitronSchemaBuilderTest.class.getDeclaredClasses())
            .filter(Class::isEnum)
            .filter(ClassificationCase.class::isAssignableFrom)
            .flatMap(c -> Arrays.stream(c.getEnumConstants()))
            .map(ClassificationCase.class::cast)
            .toList();
    }
}
```

### Success Criteria

#### Automated

- [ ] `mvn test -pl :graphitron-rewrite` passes (all 438+ existing tests
  still green — the retrofit is purely additive).
- [ ] `VariantCoverageTest.everySealedLeafHasAClassificationCase` passes
  against the retrofitted enums.
- [ ] Deleting a classification case (locally) for a variant whose only
  case was that one makes the meta-test fail, naming the uncovered
  class by simple name.
- [ ] Adding a new sealed leaf to `ChildField` (scratch edit) without a
  case fails the meta-test.

#### Manual

- [ ] Spot-check five retrofitted cases: description's
  `→ VariantName` suffix matches the `variants()` set.
- [ ] Audit multi-variant cases (`TableInputTypeCase`, `RootFieldCase`):
  `variants()` reflects what the assertion lambda actually verifies.

---

## Phase 3 (follow-up): Narrow component coverage

### Overview

Optional. Extend coverage to narrow component sealed types (`BatchKey`,
`ParamSource`, `CallSiteExtraction`, …) using a different test shape —
"given this SDL, assert that a specific field's attribute is of kind X".

### Not in scope for this plan

The test shape is different enough (attribute assertion vs.
classification outcome) that it warrants a separate plan. Listed here so
the reader knows the phase-1/phase-2 scope decision is deliberate.

Sketch for future work:

```java
interface AttributeCase {
    Set<Class<?>> variants();  // same shape as ClassificationCase
    // plus: the path from schema to the attribute being asserted
}
```

This can share the `sealedLeaves` helper and the allowlist mechanism.

---

## Testing Strategy

### Unit tests (phase 1)

- Phase 1 *is* a unit test. The production change is six lines added
  to two sets in `TypeFetcherGenerator`; the test change exercises them.

### Meta-test behaviour verification (phase 2)

The meta-test is itself the verification mechanism. To prove it works:

1. **Temporarily delete** one classification case (e.g., `TableFieldCase.SINGLE_RETURN_TYPE`).
   Meta-test fails naming `TableField`.
2. **Temporarily add** a sealed leaf to `ChildField` in `model/`.
   Meta-test fails naming the new leaf.
3. **Rewrite** a description from `"→ TableField"` to `"→ TaBlEfIeLd"`
   while keeping `variants()` correct. Meta-test passes — confirming
   reliance is on `variants()`, not description text.

Record the first two scenarios as expected-failure notes in the PR
description. Don't commit them as tests (they'd pollute the suite).

### Manual verification

- [ ] Read the failing-test output for each of the three scenarios above
  and confirm the message is actionable.
- [ ] Confirm that `VariantCoverageTest` runs under 2 seconds (it's
  reflection-heavy; keep an eye on cost).

---

## Sequencing

- **Phase 1 first** (1 PR, ~60 LOC). Low-risk, ships immediately, locks
  the pattern.
- **Phase 2 next** (1 PR, retrofit + new test). Can proceed as soon as
  phase 1 lands. Independent of argument-resolution work.
- **Phase 3 deferred.** Separate plan when narrow-component coverage
  becomes concrete demand (e.g., the `BatchKey.ObjectBased` decision
  from `docs/rewrite-roadmap.md` P2 #5).

## Consequences

### What this plan makes cheap
- Adding a new sealed leaf: test fails until a classification case and a
  dispatch-status declaration exist. Contributors are guided into the
  right shape.
- Reviewing a PR that implements a stubbed generator: one-line move
  from `NOT_IMPLEMENTED_REASONS` to `IMPLEMENTED_LEAVES` is the
  expected signal.

### What this plan makes expensive
- Adding a case that covers multiple variants: a judgment call on the
  `variants()` set. Mitigated by the manual audit step in phase 2.
- Legitimate unreachable leaves: require an allowlist entry with a
  prose reason. Friction is intentional — makes suppression visible.

### What this plan does not address
- `TypeClassGenerator` / `TypeConditionsGenerator` `instanceof`
  filtering can still rot silently. Tracked as a follow-up; tackle once
  either is converted to a sealed switch.
- Coverage for narrow component types (phase 3).
- Docs-as-index steps 3 and 4 (separate plan).

## References

- `docs/rewrite-roadmap.md` — P1 #1 (this plan), P2 #3 (validator consuming the map), P2 #5 (cross-plan ownership).
- `docs/plan-docs-as-index-into-tests.md` — step 5 sketches the `ClassificationCase` interface; this plan supersedes that step.
- `docs/plan-classification-vocabulary-followups.md` — related vocabulary work; not a dependency.
- `graphitron-rewrite/src/test/java/no/sikt/graphitron/rewrite/generators/GeneratorCoverageTest.java` — current narrow coverage test.
- `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java:119-286` — `NOT_IMPLEMENTED_REASONS` map and the sealed switch it guards.
- `graphitron-rewrite/src/test/java/no/sikt/graphitron/rewrite/GraphitronSchemaBuilderTest.java` — 26 classification enums targeted by phase 2.

