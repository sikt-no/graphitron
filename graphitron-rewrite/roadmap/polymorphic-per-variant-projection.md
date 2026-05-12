---
id: R108
title: Per-variant projection on polymorphic fields
status: In Review
bucket: architecture
priority: 3
theme: interface-union
depends-on: []
last-updated: 2026-05-12
---

# Per-variant projection on polymorphic fields

> Stage-2 of the multi-table polymorphic dispatcher over-selects when the
> GraphQL query carries asymmetric inline fragments on a union or interface.
> Each per-typename SELECT today receives the parent's flattened
> `DataFetchingFieldSelectionSet`, and the generated `$fields` walks every
> entry in `getFieldsGroupedByResultKey()` without inspecting which type
> condition each `SelectedField` belongs to. When two participant types
> share a GraphQL field name, the inactive branch projects its own
> same-named column. R108 filters the selection set per participant at the
> emit site so each Stage-2 SELECT projects only columns actually selected
> for that variant.

The single-table interface emitter (`buildInterfaceFieldsList`) uses the
same `$fields(env.getSelectionSet(), table, env)` shape, but its
over-selection is masked by the deduping `LinkedHashSet` in every shape
currently in fixtures. It is *not* folded in here; see "Same-table
dispatch" below.

---

## Motivation

The "selection set drives projection" contract underpins how
graphitron-rewrite reasons about SELECT shape: every emitted projection
either appears because a `SelectedField` was requested, or because a
load-bearing `requiredProjectionColumns` rule injects it
(`TypeClassGenerator.java:214-217`). The contract is what lets the
rewrite-design-principles claim that "the SQL graphitron renders is
exactly what the client asked for, plus what graphitron must add to make
the response well-formed". Stage-2 polymorphic SELECT violates that
contract today.

The violation is not a payload bug. `graphql-java` drops any
SELECT-projected column that the executing fragment did not request before
serialising the response, so wire output stays correct. The cost is a
per-branch wasted column read (one extra column per shared name per
inactive participant per row in the result set) and a quiet hole in the
"projection mirrors selection" invariant that pipeline-tier tests on the
emitter are meant to pin. The next refactor that asserts "every column in
the SELECT corresponds to a `SelectedField` matching this participant"
will fail spuriously today, because it's not true today.

The bug is observable in the rendered SQL. Stage-1 narrow SELECTs are
intentionally minimal (`__typename` + PK, no selection-set consultation),
so they are not affected.

---

## Where the bug lives

The bug lives at the Stage-2 per-typename helper:
`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/MultiTablePolymorphicEmitter.java:1223`

```java
b.addStatement("$T fields = new $T($T.$$fields(env.getSelectionSet(), $L, env))",
    arrayListOfField, arrayListOfField, typeClass, tableLocal);
```

`env.getSelectionSet()` is the parent field's flattened selection set.
Each participant type's generated `$fields` then walks every entry of
`getFieldsGroupedByResultKey()`.

The generated `$fields` body
(`TypeClassGenerator.java:235-294`) loops the flattened selection map and
runs `switch (sf.getName())` with no awareness of which fragment each
`SelectedField` came from. `SelectedField.getObjectTypeNames()` already
carries that information; `$fields` simply does not consult it.

---

## Why existing tests don't catch it

`GraphQLQueryTest.addressOccupants_perBranchWhereScopesToAddress`
(`graphitron-rewrite/graphitron-sakila-example/src/test/java/no/sikt/graphitron/rewrite/test/querydb/GraphQLQueryTest.java:3186`)
queries `... on Customer { firstName } ... on Staff { firstName }`. Both
branches request the field, so the over-selection is invisible: the
inactive branch was going to select `first_name` anyway. The asymmetric
case has no coverage today.

---

## Design

### Filter at the call site, not inside `$fields`

The Backlog body sketched two routes; the Spec settles on **call-site
filter**. Reasoning:

- The bug is polymorphic-only. Non-polymorphic projection passes
  `env.getSelectionSet()` to `<Type>.$fields` and is correct as it stands.
  Threading a `String concreteTypeName` parameter through every
  `$fields` signature pushes the per-variant concern onto every caller,
  including the dozens of single-table, single-type callers that have no
  variance to filter. Per *Generation-thinking* and the project's
  preference for narrow seams, the filter belongs at the point of
  variance — the Stage-2 emit site — not in the shared
  `$fields` contract.
- All Stage-2 dispatches funnel through `buildPerTypenameSelect`. One
  emit site, one helper invocation.
- Keeping `$fields`'s signature stable means R108 lands as a localized
  diff on one emitter method plus one new runtime helper class. No
  ripple across the generators package.

### The helper: a delegating `DataFetchingFieldSelectionSet` view

A new emitted runtime helper, generated by a new
`PolymorphicSelectionSetClassGenerator` under
`generators/util/`, mirrors the existing `ConnectionHelper` pattern: the
class is hand-described in Java once, emitted into
`<outputPackage>.util.PolymorphicSelectionSet`, and referenced by the
emit sites by `ClassName`. The same compile path through
`graphitron-sakila-example` then exercises it.

Shape:

```java
package <outputPackage>.util;

public final class PolymorphicSelectionSet {

    private PolymorphicSelectionSet() {}

    /**
     * Returns a view of {@code source} whose {@link
     * DataFetchingFieldSelectionSet#getFieldsGroupedByResultKey} retains
     * only entries whose {@code SelectedField.getObjectTypeNames()}
     * contains {@code concreteTypeName}. All other methods delegate to
     * {@code source} unchanged.
     */
    public static DataFetchingFieldSelectionSet restrictTo(
            DataFetchingFieldSelectionSet source, String concreteTypeName) {
        // delegating wrapper; see Implementation sites for the methods
        // that materially override versus delegate.
    }
}
```

`$fields` today only reads `getFieldsGroupedByResultKey()` from the
selection set (`TypeClassGenerator.java:243`). The wrapper materially
overrides exactly that method. Every other
`DataFetchingFieldSelectionSet` method delegates to `source` so future
read shapes (e.g. a nested `$fields` recursion that calls
`sf.getSelectionSet()` and re-enters projection) keep working without a
parallel implementation.

This shape is a deliberate, localised wire-boundary adapter, and a small
breach of the project's general preference for narrow typed components
over opaque proxies of third-party interfaces. The justification, named
explicitly so a future reader does not need to re-derive it: `$fields`'s
nested-projection recursion reads `sf.getSelectionSet()`, which only
exists on the `DataFetchingFieldSelectionSet` interface; a bare
`Map<String, List<SelectedField>>` argument would not survive the
recursion contract. Returning `DataFetchingFieldSelectionSet` is the
minimum-disruption shape; the alternative (an emitted typed record with
its own filtered-map accessor, plus a widened `$fields` signature
accepting either shape) breaks the "signature unchanged" constraint that
keeps the diff localised. The helper's class-level javadoc names this
trade-off so the next reader does not interpret the wrapper as a
template for further proxies over wire-format types.

The wrapper sits at the same wire-boundary tier as
`ConnectionHelper.encodeCursor` / `decodeCursor`, which the
rewrite-design-principles' *Wire-format encoding is a boundary concern,
never a model concern* names as the canonical place these adapters
live; the breach is in interface shape (delegating proxy vs. typed
record), not in tier or layering.

A pipeline-tier test (`PolymorphicSelectionSetClassEmitTest`) pins the
emitted helper's source through the existing emit-and-compile path so
the shape is checked at codegen time, not deferred to a runtime trip.

### Emit site

The Stage-2 call site changes to thread `concreteTypeName` through the
helper:

```java
// MultiTablePolymorphicEmitter.buildPerTypenameSelect
var polymorphicSelectionSet = ClassName.get(outputPackage + ".util", "PolymorphicSelectionSet");
b.addStatement("$T fields = new $T($T.$$fields($T.restrictTo(env.getSelectionSet(), $S), $L, env))",
    arrayListOfField, arrayListOfField, typeClass,
    polymorphicSelectionSet, participant.typeName(), tableLocal);
```

The `ClassName` is resolved at the emit site against the per-emit
`outputPackage`, matching the existing `ConnectionHelper` pattern
(`generators/SplitRowsMethodEmitter.java:745-747`).

### Same-table dispatch

The Backlog left this open: "Confirm during Spec; if shared, fold in.
If not, leave a one-line note." The single-table emit site at
`TypeFetcherGenerator.java:841-842` uses the same shape, but its
over-selection is masked by the deduping `LinkedHashSet` in every
fixture currently exercising it. The shape that breaks the dedup —
two participants of the same `TableInterfaceType` declaring a shared
GraphQL field name backed by *different* columns on the same table —
is not exercised by any fixture today and has no failing test. R108
therefore leaves the single-table call site untouched, with a one-line
javadoc cross-reference at `buildInterfaceFieldsList` pointing back to
this item. A future Backlog item that lands a fixture exercising the
break-the-dedup shape can fold the wrap in then; the `restrictTo`
helper from R108 is reusable as-is at that point. Pulling the fix in
now without a failing test would be speculative scope per the project's
"don't fix what isn't broken" posture.

### Cursor and required-projection columns are untouched

Stage-2's synthetic projections — `__typename` literal
(`MultiTablePolymorphicEmitter.java:1225`), `idx` column
(`:1250-1251`), `__sort__` cursor column (`:1233-1245`) — are added
*after* the `$fields` call and are independent of the selection set.
`requiredProjectionColumns` (Split* SourceKey columns,
`TypeClassGenerator.java:214-217`) are appended inside `$fields`
*outside* the selection-switch loop: the
`for (ColumnRef col : requiredProjectionColumns)` block runs after
`emitSelectionSwitch` returns and is unconditional by design. The
wrapper only restricts what the inner `emitSelectionSwitch` sees from
`sel.getFieldsGroupedByResultKey()`; the required-columns loop reads
from classifier output, not from the selection set. The columns are
load-bearing for fetcher key extraction and must land regardless of
variant; the structural separation (outside the switch, not a fallback
inside it) is what keeps that invariant robust under R108. The filter
changes which selection-driven fields enter the SELECT; it does not
touch any of the above.

### Nested polymorphic dispatch

A `NestingField` inside a polymorphic participant recurses through
`emitSelectionSwitch` (`TypeClassGenerator.java:276-280`) using
`sf.getSelectionSet()` of the parent-level entry. Because the
outer-level filter has already restricted the grouped map to entries
matching this participant, the nested recursion sees only the active
fragment's nested selection. No further filter is needed at depth; the
restriction is per-call, not per-depth. `PolymorphicNestingFilterTest`
(new, pipeline-tier; see Tests below) pins this by classifying a fixture
where a participant's only selected fields are nested under a fragment
specific to a sibling type, and asserting the emitted Stage-2 SELECT
produces no field-specific projections for that participant.

### Guarantee marker

The Backlog acceptance asks for "a `@LoadBearingClassifierCheck` (or
equivalent guarantee marker) on the call site so a future refactor can't
silently revert to passing the parent selection set". The marker is
*not* a classifier guarantee here (no classifier rejection is being
relied on), and the rewrite's design principles ban code-string
assertions on *emitted* method bodies at every tier. The equivalent
that fits both constraints is the precedent set by `UnifiedEmissionPinsTest`
(`graphitron-rewrite/graphitron/src/test/java/no/sikt/graphitron/rewrite/generators/UnifiedEmissionPinsTest.java`):
a regex scan over the **generator source files** (not over emitted
bodies) that counts call sites and asserts the count matches the
expected enumeration.

The R108 pin (`PolymorphicProjectionFilterPinTest`, new, in the same
package):

- Folder-wide scan via `countAcrossGenerators` over
  `src/main/java/no/sikt/graphitron/rewrite/generators/*.java`: counts
  occurrences of `PolymorphicSelectionSet.restrictTo`. Expected count:
  1 (the single Stage-2 site in `MultiTablePolymorphicEmitter.java`).
  `countAcrossGenerators` uses `Files.list` (non-recursive), so the
  new `generators/util/PolymorphicSelectionSetClassGenerator.java` is
  outside the scan and does not contribute to the count.
- Single-file scan over `MultiTablePolymorphicEmitter.java`: counts
  occurrences of `env.getSelectionSet()` passed *directly* as the first
  argument to `$$fields(`. Expected count: 0 after the fix. A regression
  that reverts the Stage-2 site to the unfiltered shape re-introduces a
  match, the count rises, the pin trips. Scoping the second pin to this
  one file (rather than reusing the folder-wide `countAcrossGenerators`
  helper) is deliberate: the same `env.getSelectionSet()` direct-arg
  shape appears in ~12 non-polymorphic emit sites across the package
  (`FetcherEmitter`, `SplitRowsMethodEmitter`, and several
  `TypeFetcherGenerator` sites) that are correct as-is per the
  "Filter at the call site, not inside `$fields`" reasoning above, plus
  the same-table interface emit site at `TypeFetcherGenerator.java:841-842`
  that R108 intentionally leaves alone (see Same-table dispatch above).
  A folder-wide count would couple the pin to those unrelated correct
  sites; a single-file scope pins exactly the Stage-2 invariant.

The pin lives in the same place as `UnifiedEmissionPinsTest` and uses
the same `countAcrossGenerators` helper. The actual behavioural
guarantee — that the rendered SQL no longer over-selects — lives in the
execution-tier SQL-capture test (`PolymorphicProjectionQueryTest`,
below), per *Pipeline tests are the primary behavioural tier*. The pin
is the cheap CI signal; the execution-tier test is the proof.

---

## Implementation sites

A three-file delta:

- New file
  `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/util/PolymorphicSelectionSetClassGenerator.java`:
  emits `<outputPackage>.util.PolymorphicSelectionSet` with the
  `restrictTo` static method. Modeled on
  `ConnectionHelperClassGenerator`. Class-level javadoc names the
  wire-boundary-adapter trade-off described in the Design section.
- `MultiTablePolymorphicEmitter.java`: change the single statement at
  line 1223 to wrap the selection set through `restrictTo`. The
  surrounding `buildPerTypenameSelect` is otherwise unchanged. The
  helper's `ClassName` is resolved inline against `outputPackage + ".util"`,
  matching the existing `ConnectionHelper` reference pattern in
  `SplitRowsMethodEmitter.java:745-747`.
- `GraphQLRewriteGenerator.java` (or wherever generators are registered):
  register the new `PolymorphicSelectionSetClassGenerator` in the
  generator list so the helper class is emitted into every consumer's
  `<outputPackage>.util` package alongside `ConnectionHelper`.
- `TypeFetcherGenerator.java`: no code change. One-line javadoc cross-
  reference added at `buildInterfaceFieldsList` noting that the
  single-table emit site shares the unfiltered-selection-set shape with
  the pre-R108 Stage-2 path, that the deduping `LinkedHashSet` masks
  the over-selection in all currently-exercised fixtures, and that a
  future Backlog item can reuse `PolymorphicSelectionSet.restrictTo` here
  when a fixture exercises the unmasked shape.

---

## Tests

Four tiers.

### Pipeline-tier (primary)

- `PolymorphicSelectionSetClassEmitTest` (new): the emitted
  `PolymorphicSelectionSet` class compiles, and the generated
  `restrictTo` method has the expected signature. Shape-pin via
  `TypeSpec` equivalence against a known-good fixture or
  `JavaFile.toJavaFileObject()` + `javac` + APT, per the rewrite test
  rules (no code-string assertions on emitted bodies).
- `PolymorphicProjectionFilterPinTest` (new, unit-tier per the
  `UnifiedEmissionPinsTest` precedent, marked `@UnitTier`). Two
  assertions. First, a folder-wide scan via `countAcrossGenerators` for
  `PolymorphicSelectionSet.restrictTo` occurrences (expected count: 1,
  in `MultiTablePolymorphicEmitter.java`). Second, a single-file regex
  over `MultiTablePolymorphicEmitter.java` for `env.getSelectionSet()`
  passed directly as the first argument to `$$fields(` (expected count:
  0). The single-file scope on the second assertion is deliberate; see
  Design → Guarantee marker for the rationale (folder-wide would couple
  the pin to ~12 non-polymorphic emit sites that are correct as-is, plus
  the same-table interface site that R108 leaves alone). This is the
  guarantee marker.
- `PolymorphicNestingFilterTest` (new): a pipeline-tier test classifying
  a fixture where a participant's only selected fields are nested under
  a fragment specific to a sibling type, asserting that the emitted
  Stage-2 SELECT for that participant produces no field-specific
  projections beyond the unconditional `__typename` / cursor / idx
  scaffolding. Pins the "no further filter needed at depth" claim from
  the Design section.
- Extend `RecordParentMultiTablePolymorphicPipelineTest` (existing,
  `graphitron-rewrite/graphitron/src/test/java/no/sikt/graphitron/rewrite/RecordParentMultiTablePolymorphicPipelineTest.java`)
  with an asymmetric-fragment fixture: a union whose participants share
  a GraphQL field name backed by different columns on different tables,
  exercised through the full classifier pipeline, with a TypeSpec
  equivalence assertion that pins the per-typename SELECT body shape.

### Compilation-tier

- The existing `graphitron-sakila-example` compile already covers
  `PolymorphicSelectionSet`'s import resolution and the cross-class
  reference from `*Fetchers` to the helper. No new compilation test
  needed; the helper landing in `<outputPackage>.util` is exercised the
  same way `ConnectionHelper` is.

### Execution-tier (the proof)

- `PolymorphicProjectionQueryTest` (new, sibling to
  `CompositeKeyLookupQueryTest`): mirrors the SQL-capture
  `ExecuteListener` pattern at
  `CompositeKeyLookupQueryTest.java:59-66`. Queries:

  ```graphql
  { customers(first: 1) { address { occupants {
      __typename
      ... on Customer { firstName }
  } } } }
  ```

  Asserts that the rendered SQL log contains a SELECT against `"staff"`
  but that SELECT does *not* contain `"staff"."first_name"`, and the
  SELECT against `"customer"` *does* contain `"customer"."first_name"`.
  A second test inverts the asymmetry (`... on Staff { firstName }`)
  to pin both directions. A third test re-asserts the existing
  symmetric case still projects both, so the filter is precise rather
  than overzealous.

### Behavioural

- One `GraphQLQueryTest` test naming the asymmetric case to lock the
  response payload contract alongside the SQL shape. The response is
  already correct today (graphql-java drops the unselected field at
  serialisation); the test pins it so any future regression that
  inverts the bug (under-selecting active branches) fails loudly at the
  payload level as well as the SQL level.

---

## Out of scope

- The Stage-1 narrow SELECT (`__typename` + PK only). Stage-1
  intentionally does not consult the selection set; nothing to filter.
- DataLoader-batched vs inline arms. The projection call site is
  shared between them via `buildPerTypenameSelect`, so the fix lands
  once.
- The `getObjectTypeNames()` semantics for fields declared on the
  interface/union itself rather than inside an inline fragment.
  graphql-java populates `getObjectTypeNames()` with every concrete type
  the field could resolve to, including interface-level fields that
  apply to every participant, so those naturally pass the filter for
  every variant. Confirmed against graphql-java 25's contract; no
  additional handling.
- A `@LoadBearingEmitterInvariant` annotation as a parallel to
  `@LoadBearingClassifierCheck`. The generator-source pin (per
  `UnifiedEmissionPinsTest`) is the established equivalent in the
  codebase; introducing a new annotation shape is out of scope.
- The same-table interface emit site at
  `TypeFetcherGenerator.java:841-842`. See "Same-table dispatch" under
  Design for the reasoning; future Backlog item folds the wrap in when
  a fixture exercises the break-the-dedup shape.

---

## Acceptance

- Asymmetric-fragment query renders one column per branch matching the
  per-fragment selection set, with no shared-name leakage between
  inactive branches and active ones.
- `PolymorphicProjectionFilterPinTest` pins the Stage-2 emit site
  against passing the unfiltered parent selection set; a refactor that
  reverts to `env.getSelectionSet()` re-introduces an unfiltered match
  in the generator source, trips the pin, and fails the build.
- `PolymorphicProjectionQueryTest` asserts the rendered SQL for the
  asymmetric Stage-2 SELECT against `"staff"` does not contain
  `"staff"."first_name"`; this is the behavioural proof that the pin
  underwrites.
- `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` green.

---

## Implementation notes (In Review)

Shipped as planned. Five-file delta:

- New `PolymorphicSelectionSetClassGenerator` under `generators/util/`,
  registered in `GraphQLRewriteGenerator.write(...)` immediately after
  `ConnectionHelperClassGenerator`. Emits `<outputPackage>.util.PolymorphicSelectionSet`
  with a single public static `restrictTo(DataFetchingFieldSelectionSet, String)`
  factory and a private static `Filtered` inner class that delegates every
  `DataFetchingFieldSelectionSet` method to the source, materially overriding only
  `getFieldsGroupedByResultKey()` to filter by `SelectedField.getObjectTypeNames()`.
- `MultiTablePolymorphicEmitter.buildPerTypenameSelect` (line 1223): the
  single `$T.$$fields(env.getSelectionSet(), $L, env)` emit became
  `$T.$$fields($T.restrictTo(env.getSelectionSet(), $S), $L, env)`,
  resolving the helper `ClassName` against the per-emit `outputPackage + ".util"`.
- `TypeFetcherGenerator.buildInterfaceFieldsList`: javadoc cross-reference
  added per the Same-table-dispatch carve-out; no code change at the
  single-table emit site.
- `TypeFetcherGeneratorTest.queryInterfaceField_perTypenameHelpersExist_andCallParticipantFields`:
  updated to match the new wrapped emit shape.

Test additions (five new test files plus extensions to two existing ones):

- `PolymorphicProjectionFilterPinTest` (unit, `graphitron/`): two assertions.
  First, `countAcrossGenerators` over the generators package counts the
  JavaPoet emit-string shape `$T.restrictTo(env.getSelectionSet()` — expected
  1, in `MultiTablePolymorphicEmitter.java`. Second, single-file scan over
  `MultiTablePolymorphicEmitter.java` for direct `$$fields(env.getSelectionSet()`
  — expected 0. Pin pattern uses `$T` as the JavaPoet placeholder so prose
  mentions of the qualified class name in javadoc / comments do not contribute.
- `PolymorphicSelectionSetClassEmitTest` (pipeline, `graphitron/`): structural
  pin of the emitted helper class — single public final class named
  `PolymorphicSelectionSet`, a public static `restrictTo` factory with the
  right signature, a private no-arg constructor, and a private static final
  `Filtered` nested type that implements `DataFetchingFieldSelectionSet`.
  Method bodies are exercised at the compilation tier (sakila-example).
- `PolymorphicNestingFilterTest` (pipeline, `graphitron/`): emits the
  Stage-2 helpers for a polymorphic fixture and counts
  `PolymorphicSelectionSet` references per method — exactly one per
  helper, encoding the "no further filter needed at depth" claim as a
  structural symbol count rather than a body-string assertion.
- `RecordParentMultiTablePolymorphicPipelineTest.unionParticipants_sharedFieldNameBackedByDifferentColumns_classifiesAndGeneratesStage2Helpers`:
  asymmetric-fragment fixture (Inventory + Content both expose `filmId`
  backed by `film_id` on different tables) drives the full SDL → classify
  → emit pipeline and asserts both per-typename helpers exist on
  `FilmFetchers`.
- `PolymorphicProjectionQueryTest` (execution, `graphitron-sakila-example/`):
  three SQL-capture tests over `Address.occupants`. Asymmetric-Customer
  query asserts the Staff Stage-2 SELECT does not contain
  `"staff"."first_name"`; asymmetric-Staff query pins the inverse; the
  symmetric query keeps both. SELECTs are picked by the per-typename
  `"customerinput"` / `"staffinput"` VALUES alias to ignore Stage-1's
  narrow UNION ALL and any unrelated SELECT.
- `GraphQLQueryTest.addressOccupants_asymmetricFragment_responsePayloadDropsInactiveBranch`:
  behavioural pin alongside the SQL-shape proof — the Staff entry in the
  response map omits `firstName` when no Staff fragment requested it,
  while the Customer entry resolves it.

Same-table interface site at `TypeFetcherGenerator.buildInterfaceFieldsList`
is intentionally untouched (per spec Out of scope); the javadoc cross-
reference notes that R108's `restrictTo` is reusable as-is when a fixture
exercises the break-the-dedup shape.
