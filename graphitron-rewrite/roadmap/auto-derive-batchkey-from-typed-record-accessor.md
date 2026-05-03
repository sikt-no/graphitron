---
id: R60
title: "Auto-derive BatchKey from typed TableRecord accessor on @record parents"
status: In Review
priority: 1
theme: service
depends-on: []
---

# Auto-derive BatchKey from typed TableRecord accessor on @record parents

## Shipped

Implementation landed across three commits on `claude/graphitron-rewrite`:

- `14889c1` — classifier auto-derivation, two new `BatchKey.RecordParentBatchKey` permits, four-arm switch in `buildRecordParentKeyExtraction`, paired load-bearing keys, three-option AUTHOR_ERROR rewrite. Build green; all 1236 pre-existing tests pass.
- `aabd7ea` — unit tier (`BatchKeyTest`, 5 cases) and pipeline tier (`AccessorDerivedBatchKeyCase`, 6 cases) covering accept / reject / cardinality / ambiguous / heterogeneous-element corners. 1247 tests passing.
- `b2ae55d` — fixtures (`CreateFilmsPayload` + service), schema entries, execution-tier test (`AccessorDerivedBatchKeyTest`), plus rows-method routing for the loadMany contract. Full `mvn install -Plocal-db` green including execution tier.

### Deviations from the spec body below

1. **Single-accessor execution test dropped.** `GraphitronSchemaValidator.validateRecordParentSingleCardinalityRejected` (Invariant #10) still rejects single-cardinality `RecordTableField` returns until the rows-method's single arm ships, so the `AccessorRowKeyedSingle` permit can't be exercised end-to-end yet. The classifier produces it correctly (covered at the pipeline tier; see `ACCESSOR_ROWKEYED_SINGLE_SINGLE_FIELD_SINGLE_ACCESSOR`); the execution tier only covers `Many`. The `Single` permit is fully wired through the emitter (`buildAccessorRowKeySingle` in `GeneratorUtils`), waiting for the validator gate to lift in a future change. Re-enabling the execution-tier coverage is a one-line schema addition + a couple of test methods at that point.

2. **Rows-method routing for `Many`: routes through `buildSingleMethod`, not a new method.** The `DataLoader.loadMany` contract returns `CompletableFuture<List<V>>` where each key maps to exactly one Record (1:1 with the keys list). The existing `buildSingleMethod` already emits that shape (`List<Record>` via `scatterSingleByIdx`), so the `AccessorRowKeyedMany` arm of `buildForRecordTable` reuses it. `buildSingleMethod` was refactored from a `(JoinStep.FkJoin) firstHop` cast to a `(JoinStep.WithTarget) firstHop` cast with a conditional `whereFilter` lift; column-reference reads are uniform across both routes. `TypeFetcherGenerator.hasSingleSplitField` widened to also emit `scatterSingleByIdx` when any `RecordTableField` carries `AccessorRowKeyedMany`.

3. **`Container` axis on `AccessorRowKeyedMany` is preserved on the model but absorbed by emit-time iteration.** The spec emit had `.toList()` for `LIST` and `.collect(Collectors.toSet())` for `SET`; the actual emit is a typed for-loop iterating any `Iterable` and building `List<RowN<...>>` because `DataLoader.loadMany(List<K>)` requires List regardless. The `Container` enum on the BatchKey is left intact (it documents what the parent's accessor returns, which is honest for find-usages), but the codegen no longer forks on it. If a future emit needs the distinction (e.g. preserving order semantics, deduplication), the slot is there.

4. **`DataLoader.loadMany` signature.** The spec emit was `loader.loadMany(keys, env)`. `DataLoader.loadMany` only has `(List<K>)` and `(List<K>, List<Object>)` overloads — no single-Object key-context. The emit passes `Collections.nCopies(keys.size(), env)` so the lambda's `batchEnv.getKeyContextsList().get(0)` lookup still wires through; the batch loader only reads index 0 so the duplication is cheap.

5. **TableRecord accessor name.** The spec showed `__elt.<pk1>()`. jOOQ's generated `TableRecord` exposes `get<UpperFirst>(camelCase)` (e.g. `getFilmId()`), not the fluent style. `buildAccessorRowKey*` uses the `recordGetter(sqlName)` helper to mirror `buildFkRowKey`'s `PojoResultType` arm.

6. **`SplitRowsMethodEmitter.emitParentInputAndFkChain` switch.** The spec called out only `buildRecordParentKeyExtraction`'s four-arm switch and `buildRecordBasedDataFetcher`'s dispatch. The prelude's `pkCols` switch in `SplitRowsMethodEmitter` also needed extension — both new permits read `targetKeyColumns()` (delegated to `hop.targetColumns()`) the same way `LifterRowKeyed` does. Caught by an existing pipeline test failure on first compile, fixed in `14889c1`.

7. **`AccessorDerivation` sealed interface lives on `FieldBuilder` as a private nested type.** Spec proposed it as a builder-internal sealed result. Implemented as expected; sibling private nested type `AccessorMatch` carries the per-method match outcome (Single / Many / CardinalityMismatch), with reduction to `AccessorDerivation` Ok / None / Ambiguous / CardinalityMismatch happening once per call rather than per method.

The two new `@LoadBearingClassifierCheck` keys (`accessor-rowkey-shape-resolved`, `accessor-rowkey-cardinality-matches-field`) are paired against consumer annotations in `GeneratorUtils.buildAccessorRowKey*` and `TypeFetcherGenerator.buildRecordBasedDataFetcher`. `LoadBearingGuaranteeAuditTest` passes; no orphans.

Original spec follows below as the reviewer's reference.

## Overview

A child field on a `@record`-typed parent (`PojoResultType` / `JavaRecordType` with non-null `fqClassName`) returning a `@table`-bound type is rejected today by `FieldBuilder.classifyChildFieldOnResultType` when the catalog has no FK metadata to derive the batch key from, with a remediation pointing the author at `@batchKeyLifter`. The directive is the right tool when the parent's batch-key value is a *synthetic* tuple the author computes; it is overkill when the parent's backing class already exposes a typed accessor returning the field's records directly (e.g. `LagreKvotesporsmalSvarPayload.getSvar(): List<SoknadKvotesporsmalSvarRecord>` pointing at `KvoteSporsmalSvar` `@table(table: "soknad_kvotesporsmal_svar")`). In that shape every input the classifier needs is already build-time visible: the parent class is reflectable, the accessor's container axis (List / Set / single) and element class (a concrete `TableRecord` subtype) are visible via reflection, and the element's table's PK supplies the target key columns.

This plan adds two new `BatchKey.RecordParentBatchKey` permits, `AccessorRowKeyedSingle` and `AccessorRowKeyedMany`, siblings to `LifterRowKeyed`, that the classifier auto-derives when an accessor match is found. Routing into `RecordTableField` / `RecordLookupTableField` is unchanged; only the key-extraction code differs (call the accessor, project to PK rows). The lifter directive remains the first-class escape hatch for synthetic tuples.

Splitting accessor-derivation away from `LifterRowKeyed` keeps each variant's invariant tight (per *Sealed hierarchies over enums* and *Narrow component types* in `rewrite-design-principles.adoc`): `LifterRowKeyed` always traces back to the directive resolver; the two accessor permits always trace back to the auto-derivation in the classifier; `find-usages` on each tells you exactly where its preconditions are checked.

Splitting accessor-derivation again into `Single` and `Many` rather than overloading one permit with a `Container { SINGLE, LIST, SET }` enum applies the same principle one level deeper. Two consumers fork on the predicate: the codegen switch in `buildRecordParentKeyExtraction` (one key vs many keys), and the dispatch decision in `buildRecordBasedDataFetcher` (`loader.load(key, env)` vs `loader.loadMany(keys, env)`, with corresponding loader value type). Per *Sealed hierarchies over enums* ("if two consumers evaluate the same predicate over a model field, the branch belongs in the model"), the field-cardinality axis goes into the type system as `Single` / `Many`, not into a stored enum. The residual `LIST` vs `SET` axis (different terminal collection operator only) stays as a `Container` enum on `Many`, where the data shapes coincide and only one localised emit-time branch differs.

## Current state

`FieldBuilder.classifyChildFieldOnResultType` (currently L2500, line drift expected, see References) handles `@record`-parent child fields with table-bound returns by:

1. Parsing the `@reference` path against the parent's optional SQL anchor (only `JooqTableRecordType` provides one today).
2. Calling `deriveBatchKeyForResultType(joinPath, parentResultType)`, which returns `BatchKey.RowKeyed(fkJoin.sourceColumns())` when the path's first hop is an `FkJoin` and the parent has a non-null backing class; otherwise `null`.
3. When `null`: emit `UnclassifiedField(AUTHOR_ERROR, "RecordTableField on a free-form DTO parent requires @batchKeyLifter to lift the batch key; the catalog has no FK metadata for the parent class. Add @batchKeyLifter(lifter: ..., targetColumns: [...]) on this field, or back the parent with a typed jOOQ TableRecord so the FK can be derived")`.

The rejection fires for two distinct shapes that today share a single error:

- **(a) Truly free-form DTOs** where neither the catalog nor the parent class can yield the key columns. `@batchKeyLifter` is the right answer.
- **(b) Typed-accessor DTOs** where the parent class exposes a method whose return type is `List<X>` / `Set<X>` / `X` for some `X extends TableRecord`, and `X`'s mapped table is the field's `@table` return. The classifier has every input it needs to produce a key, but the FK metadata it queries (jOOQ's `Table.getReferences()`) doesn't see the relationship; the relationship lives in Java type information, not in SQL constraints.

`BatchKey` (`graphitron/src/main/java/no/sikt/graphitron/rewrite/model/BatchKey.java`) is a sealed interface with two sub-hierarchies:

- `ParentKeyed`: `RowKeyed`, `RecordKeyed`, `MappedRowKeyed`, `MappedRecordKeyed` (`@service` SOURCES classification axis).
- `RecordParentBatchKey`: `RowKeyed`, `LifterRowKeyed` (input to `GeneratorUtils.buildRecordParentKeyExtraction`).

`buildRecordParentKeyExtraction` (in `generators/GeneratorUtils.java`, around L199) is a two-arm sealed switch: `RowKeyed` reads the parent's typed accessor for the FK column (delegated through the four `ResultType` subtypes), `LifterRowKeyed` calls the developer-supplied static lifter.

The classifier-rejection at the §1 step-3 site is the only path producing the "add @batchKeyLifter" remediation today, so widening the auto-derivation here is sufficient; no other site needs to learn about the new permit.

## Desired end state

When the FK derivation returns null on a `@record`-typed parent and the field's return type is `@table`-bound, the classifier attempts a second derivation path before rejecting:

- Reflect on the parent class to find a public instance method whose:
  1. name matches the GraphQL field name (camel-case + `get`-prefixed variants, see Implementation §1.b for the exact resolution rule), and
  2. return type is `X`, `List<X>`, or `Set<X>` for some concrete `X extends TableRecord`, and
  3. `X`'s mapped jOOQ table (read via `X.fields()[0].getTable()` or equivalent; see Implementation §1.c for the exact reflection step) is the same `TableRef` as the field's `@table` return.

If exactly one such accessor matches and its container axis aligns with the field's cardinality (single accessor for a single field, list/set accessor for a list field), build a `JoinStep.LiftedHop(targetTable = element table, targetColumns = element table PK, alias = fieldName + "_0")` and either a `BatchKey.AccessorRowKeyedSingle(hop, accessorRef)` or a `BatchKey.AccessorRowKeyedMany(hop, accessorRef, container)` (where `container ∈ {LIST, SET}`), and route into `RecordTableField` / `RecordLookupTableField` as today. The cardinality match is enforced at classify time so the emitter never sees an illegal pairing.

`AccessorRowKeyedSingle` and `AccessorRowKeyedMany` are two new permits of `RecordParentBatchKey`. `buildRecordParentKeyExtraction`'s sealed switch grows from two arms to four. The DataLoader plumbing for `Single` is the same column-keyed path used by `RowKeyed` and `LifterRowKeyed`; the `Many` variant additionally swaps `loader.load` for `loader.loadMany` (see Implementation §3) so that one parent contributing N keys can fan out through the existing batch-loader contract.

The `@batchKeyLifter` directive continues to work unchanged; nothing in this plan touches `BatchKeyLifterDirectiveResolver`. Authors who already wrote a lifter for shape (b) keep working code; the change only matters going forward, by removing the need to write one.

## What we're NOT doing

- **Free-form DTO parents (shape (a)).** The existing AUTHOR_ERROR remediation continues to fire, with text updated only to reflect the new auto-derivation as a third option ("…back the parent with a typed jOOQ TableRecord, expose a typed accessor returning the child's TableRecord, or supply @batchKeyLifter").
- **`JooqRecordType` / `JooqTableRecordType` parents.** These already get FK-derived batch keys; the auto-derivation should not run for them. The live `deriveBatchKeyForResultType` returns null whenever the join path's first hop isn't an `FkJoin`, regardless of parent variant, so the structural unreachability claim isn't free; making it free is part of the implementation. The new derivation switches over `GraphitronType.ResultType`'s four permits as a sealed switch and returns `AccessorDerivation.None` for `JooqRecordType` and `JooqTableRecordType` arms. Per *Sealed hierarchies over enums*, exhaustiveness becomes a compile-time fact rather than a documented assumption; if the `ResultType` taxonomy grows a fifth permit, the switch fails to compile and the implementer decides whether the new variant is accessor-eligible.
- **Multiple matching accessors.** If reflection finds two methods that both satisfy the match rule (e.g. a `getSvar()` and a differently-named accessor for the same element class), reject with AUTHOR_ERROR naming both candidates and asking the author to disambiguate via `@batchKeyLifter`. We will not invent a tie-break.
- **Heterogeneous element types.** If the accessor's element type is a `TableRecord` but does NOT match the field's `@table` return, do not auto-derive. The author may have intended a non-trivial transform; `@batchKeyLifter` is the explicit escape hatch.
- **Inheritance-walking on the parent class.** Match the accessor on the parent class (or its declared supertypes via `Class.getMethods()`, which already walks). No special handling for synthetic / bridge methods beyond `Method.isBridge()` skipping.
- **Cross-cutting BatchKey clean-up.** This plan adds two variants; it does not retune the `RowKeyed`-shared-by-two-sub-hierarchies wrinkle (`RowKeyed` permits both `ParentKeyed` and `RecordParentBatchKey`). That's intentional under *Sealed hierarchies over enums*' "the smell is shared accessors, not shared variants" rule and stays out of scope here.
- **`Row1<T>` / `RowN<...>` → `RecordN<...>` key-emission switch.** Tracked under R61 ([`emit-record1-keys-instead-of-row1.md`](emit-record1-keys-instead-of-row1.md)) for the `ParentKeyed` axis (`RowKeyed` / `MappedRowKeyed`). R60's accessor permits emit `RowN` / `List<RowN>` keys, mirroring `LifterRowKeyed`'s existing convention so the new permits sit consistently next to their sibling. If R61 lands first, R60's accessor permits adopt `RecordN` in the same sweep R61 applies to `RowKeyed` / `LifterRowKeyed`; if R60 lands first, R61 picks up the accessor permits as it generalises. Either ordering works; neither item depends on the other.

## Implementation approach

### 1. Add `BatchKey.AccessorRowKeyedSingle` and `BatchKey.AccessorRowKeyedMany`

**File:** `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/BatchKey.java`

a. **Two new permits** under `RecordParentBatchKey`:

```java
sealed interface RecordParentBatchKey extends BatchKey
        permits RowKeyed, LifterRowKeyed, AccessorRowKeyedSingle, AccessorRowKeyedMany {}
```

b. **New records:**

```java
/**
 * Column-based batch key for a single-cardinality child field on a {@code @record} parent
 * whose backing class exposes a typed accessor returning a single concrete
 * {@code TableRecord}. Auto-derived by {@code FieldBuilder.classifyChildFieldOnResultType}
 * when no FK is available in the catalog but the parent class's accessor matches the
 * field's {@code @table} return.
 *
 * <p>Drives {@code loader.load(key, env)}; loader value type is {@code Record}. Single-key
 * variant; the join-path identity is {@link JoinStep.LiftedHop} (not {@link JoinStep.FkJoin}).
 *
 * <p>Sibling of {@link AccessorRowKeyedMany} (list / set accessor) and {@link LifterRowKeyed}
 * (developer-supplied lifter). The cardinality split between {@code Single} and {@code Many}
 * is in the type system so the dispatch in
 * {@code TypeFetcherGenerator.buildRecordBasedDataFetcher} reads variant identity, not a
 * stored enum.
 */
record AccessorRowKeyedSingle(JoinStep.LiftedHop hop, AccessorRef accessor) implements RecordParentBatchKey {

    public List<ColumnRef> targetKeyColumns() { return hop.targetColumns(); }

    @Override
    public String javaTypeName() {
        return containerType("List", "Row", hop.targetColumns());
    }
}

/**
 * Column-based batch key for a list-cardinality child field on a {@code @record} parent
 * whose backing class exposes a typed accessor returning {@code List<X>} or {@code Set<X>}
 * for some concrete {@code X extends TableRecord}. Each parent contributes N keys (one per
 * element); drives {@code loader.loadMany(keys, env)} with loader value type {@code Record},
 * so {@code loadMany} returns {@code CompletableFuture<List<Record>>} that already matches
 * the list-field shape.
 *
 * <p>{@code container} disambiguates the terminal collector the emitter uses to materialise
 * the per-parent key list ({@code .toList()} for {@code LIST}, {@code .collect(toSet())} for
 * {@code SET}); the dispatch and rows-method shape are identical across the two cases, so
 * a stored enum is appropriate here per *Sealed hierarchies over enums* ("same shape, no
 * per-variant data").
 */
record AccessorRowKeyedMany(JoinStep.LiftedHop hop, AccessorRef accessor, Container container)
        implements RecordParentBatchKey {

    public List<ColumnRef> targetKeyColumns() { return hop.targetColumns(); }

    @Override
    public String javaTypeName() {
        return containerType("List", "Row", hop.targetColumns());
    }

    public enum Container { LIST, SET }
}
```

c. **New `AccessorRef`** (own file, `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/AccessorRef.java`):

```java
record AccessorRef(
    ClassName parentBackingClass,
    String methodName,
    ClassName elementClass) {}
```

`AccessorRef` does not carry the container axis: that information lives on the variant identity (`Single` vs `Many`) plus the `Container` slot on `Many`. Per *Narrow component types over broad interfaces*, the smaller record is the right fit; an `AccessorRef` flowing through `Single` cannot be misread as carrying a list/set marker.

### 2. Extend the classifier with the auto-derivation step

**File:** `graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java`

a. **In `classifyChildFieldOnResultType`'s `TableBoundReturnType` arm**, between the FK-derivation null-check and the existing rejection (currently around L2610-L2621 in today's tree; line drift expected, see References), insert the accessor-derivation attempt. The codebase's rejection API is the `Rejection.structural(message)` factory (returning a `Rejection.AuthorError.Structural`), not an enum form:

```java
var batchKey = deriveBatchKeyForResultType(objectPath.elements(), parentResultType);
if (batchKey == null) {
    // FK metadata absent. Try to auto-derive from a typed accessor on the parent's
    // backing class before falling back to the @batchKeyLifter remediation.
    var accessorBk = deriveBatchKeyFromTypedAccessor(name, fieldDef, parentResultType, tb);
    if (accessorBk instanceof AccessorDerivation.Ok ok) {
        batchKey = ok.batchKey();
    } else if (accessorBk instanceof AccessorDerivation.Ambiguous a) {
        yield new UnclassifiedField(parentTypeName, name, location, fieldDef,
            Rejection.structural("@record parent '" + parentClassName(parentResultType)
                + "' exposes more than one typed accessor returning '" + tb.table().tableName()
                + "' records: [" + a.candidates() + "]. Disambiguate by adding "
                + "@batchKeyLifter(...) on this field."));
    } else if (accessorBk instanceof AccessorDerivation.CardinalityMismatch m) {
        yield new UnclassifiedField(parentTypeName, name, location, fieldDef,
            Rejection.structural(m.message()));
    }
    // AccessorDerivation.None falls through to the existing AUTHOR_ERROR below.
}
if (batchKey == null) {
    yield new UnclassifiedField(parentTypeName, name, location, fieldDef,
        Rejection.structural(
            "RecordTableField on a free-form DTO parent requires a typed accessor or "
            + "@batchKeyLifter to lift the batch key; the catalog has no FK metadata for "
            + "the parent class. Either expose a typed accessor on the parent returning "
            + "List<...Record>, Set<...Record>, or ...Record (where ...Record is the "
            + "element type's jOOQ TableRecord); or add @batchKeyLifter(lifter: ..., "
            + "targetColumns: [...]); or back the parent with a typed jOOQ TableRecord "
            + "so the FK can be derived"));
}
```

The same insertion applies to the `RecordLookupTableField` branch immediately above (lookup variant); both call `deriveBatchKeyForResultType` and have the same null-rejection. Lift the new derivation call to a single shared private helper rather than duplicating.

b. **`deriveBatchKeyFromTypedAccessor` helper** (new, on `FieldBuilder`).

The match rule classifies each candidate method into one of four typed outcomes (`Match.Single` / `Match.Many` / `Match.CardinalityMismatch` / `Match.NoMatch`); cardinality alignment is part of the match rule, not a downstream filter. The four outcomes feed the loop's reduction rule below:

- Resolve the parent backing class via a sealed switch over `parentResultType: GraphitronType.ResultType`'s four permits. `PojoResultType` (with non-null `fqClassName`) and `JavaRecordType` arms feed reflection with `Class.forName(fqClassName)`; `JooqRecordType` and `JooqTableRecordType` arms return `AccessorDerivation.None` (those parents already participate in FK-derivation); `PojoResultType` with null `fqClassName` returns `AccessorDerivation.None` (already a precondition for the rejection path, so this is the same branch). Exhaustiveness is a compile-time fact via the sealed switch; no `default` arm.
- Iterate `parentClass.getMethods()`. For each method `m`:
  - Skip if `m.isBridge()` or `m.isSynthetic()`.
  - Skip if `m.getParameterCount() != 0` or `Modifier.isStatic(m.getModifiers())`.
  - Compute the candidate names that match: `m.getName().equals(fieldName)`, or `m.getName().equals("get" + ucFirst(fieldName))`, or `m.getName().equals("is" + ucFirst(fieldName))` (the standard JavaBean / record-accessor set; matches what the existing `RowKeyed` extraction already assumes for `PojoResultType`).
  - If the field name doesn't match any of those, classify as `NoMatch` (continue to next method without recording).
  - Classify the return type via a small `classifyAccessorReturn(Type returnType)` helper that mirrors `ServiceCatalog.classifySourcesType`'s container-and-element walk:
    - `List<E>` where `E` is a concrete `Class<?>` extends `TableRecord` → `(LIST, E)`.
    - `Set<E>` similarly → `(SET, E)`.
    - Bare `E` extending `TableRecord` → `(SINGLE, E)`.
    - Otherwise classify as `NoMatch`.
  - Compare element class's mapped table against `tb.table()`; on mismatch classify as `NoMatch` (the heterogeneous-element case; falls through to the rewritten three-option rejection so the author can supply `@batchKeyLifter` for the intended transform).
  - **Cardinality alignment** against `tb.wrapper().isList()`:
    - `tb.wrapper().isList() && (axis == LIST || axis == SET)` → `Match.Many(m, axis)`.
    - `!tb.wrapper().isList() && axis == SINGLE` → `Match.Single(m)`.
    - otherwise → `Match.CardinalityMismatch(m, axis, "<single|list> field '" + fieldName + "' has accessor '" + m.getName() + "' returning <a list / set | a single record>; expected <List<...> or Set<...> | a single record>")`.
- Reduction over the collected matches:
  - 0 `Match.Single` and 0 `Match.Many`, 0 `Match.CardinalityMismatch` → `AccessorDerivation.None`.
  - 0 `Match.Single` and 0 `Match.Many`, ≥1 `Match.CardinalityMismatch` → `AccessorDerivation.CardinalityMismatch(joinedMessages)` (joined by `"; "` if multiple candidates only mismatched on cardinality).
  - 2+ `Match.Single`-or-`Match.Many` (any combination) → `AccessorDerivation.Ambiguous(candidatesString)`.
  - exactly 1 `Match.Single` → build `JoinStep.LiftedHop(targetTable = tb.table(), targetColumns = tb.table().primaryKeyColumns(), alias = name + "_0")` and `AccessorRowKeyedSingle(hop, AccessorRef(parentBackingClass, m.getName(), elementClass))`; return `AccessorDerivation.Ok`.
  - exactly 1 `Match.Many(m, axis)` → same hop construction; build `AccessorRowKeyedMany(hop, AccessorRef(...), axis)` (mapping `LIST`/`SET` axis to `Container`); return `AccessorDerivation.Ok`.

The classifier guarantees the cardinality cell of the legality grid (§3.c) by construction; `AccessorRowKeyedSingle` is only emitted when `tb.wrapper().isList()` is false, `AccessorRowKeyedMany` only when it is true. The emitter relies on this without runtime checks (see §4 load-bearing keys).

`AccessorDerivation` is a small sealed interface local to `FieldBuilder` with `Ok(BatchKey.RecordParentBatchKey)` / `None` / `Ambiguous(String)` / `CardinalityMismatch(String)` arms (a builder-internal sealed hierarchy per *Builder-step results are sealed*).

c. **Element-class → `TableRef` lookup.** During implementation, audit `BuildContext` / `JooqCatalog` for an existing `Class<? extends TableRecord> → TableRef` resolution helper. If one exists (likely yes; service-classification crosses the same line), reuse it. If not, add one at the appropriate boundary class. Per *Classification belongs at the parse boundary*, only `JooqCatalog`, `TypeBuilder`, `FieldBuilder`, and `ServiceCatalog` are permitted to hold raw jOOQ types, so the new helper must live in one of those rather than leaking `Class<? extends TableRecord>` reflection into the model.

### 3. Identity-based dispatch in `buildRecordParentKeyExtraction` and `buildRecordBasedDataFetcher`

**File:** `graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/GeneratorUtils.java`

a. The existing two-arm switch over `RecordParentBatchKey` becomes a four-arm switch. Each arm reads variant identity, not a discriminator enum:

```java
return switch (batchKey) {
    case BatchKey.RowKeyed rk                -> buildFkRowKey(rk, keyType, resultType, jooqPackage);
    case BatchKey.LifterRowKeyed lrk         -> buildLifterRowKey(lrk, keyType, resultType);
    case BatchKey.AccessorRowKeyedSingle ars -> buildAccessorRowKeySingle(ars, keyType, resultType);
    case BatchKey.AccessorRowKeyedMany arm   -> buildAccessorRowKeyMany(arm, keyType, resultType);
};
```

`buildAccessorRowKeySingle` emits one key (`key` local), drives `loader.load(key, env)`:
```
ElementRecord __elt = ((BackingClass) env.getSource()).<accessor>();
RowN<...> key = DSL.row(__elt.<pk1>(), __elt.<pk2>());
```

`buildAccessorRowKeyMany` emits many keys (`keys` local), drives `loader.loadMany(keys, env)`. The only branch internal to this arm is the terminal collector (`.toList()` for `Container.LIST`, `.collect(Collectors.toSet())` for `Container.SET`); per *Sealed hierarchies over enums* the enum is appropriate where data shapes coincide and only one local emit-time difference remains:
```
List<RowN<...>> keys = ((BackingClass) env.getSource()).<accessor>().stream()
    .map(__elt -> DSL.<RowN<...>>row(__elt.<pk1>(), __elt.<pk2>()))
    .toList();   // .collect(Collectors.toSet()) for SET
```

b. **Dispatch in `TypeFetcherGenerator.buildRecordBasedDataFetcher`** (around L2419 in today's tree; line drift expected).

Today: `loader.load(key, env)` returns `CompletableFuture<List<Record>>` (list field) or `CompletableFuture<Record>` (single field). The lambda contract is: the keys arriving in `(List<RowN> keys, batchEnv) -> rowsMethod(keys, dfe)` are one-per-parent.

For accessor-derived **list / set** containers, *one parent contributes N keys*. Two routing options were considered:

- **Option A, `loadMany` per parent.** The DataFetcher emits `loader.loadMany(keys, env)` (where `keys` is the local `List<RowN<...>>` from §3.a's `Many` arm) instead of `loader.load(key, env)`. Crucially, **`V` for the loader becomes `Record` (singular), not `List<Record>`**: each element-PK key maps to exactly one element record, and `loadMany` returns `CompletableFuture<List<V>>` = `CompletableFuture<List<Record>>` directly, which already matches the list-field shape. The rows-method's batching contract is unchanged: it still receives a `List<RowN>` (the union across parents) and returns one record per key.
- **Option B, synthesise a parent-id key.** The parent contributes one synthetic `Row1<Integer>` of `System.identityHashCode(parent)`; the rows-method receives the list of synthetics and looks back up. Non-starter: the rows-method doesn't see the original parents.

**Decision: Option A.** The DataFetcher dispatch reads variant identity:

```java
boolean usesLoadMany = batchKey instanceof BatchKey.AccessorRowKeyedMany;
TypeName valueType = (usesLoadMany || !field.returnType().wrapper().isList())
    ? RECORD
    : ParameterizedTypeName.get(LIST, RECORD);
String dispatchCall = usesLoadMany
    ? "return loader.loadMany(keys, env)\n"
    : "return loader.load(key, env)\n";
```

The keying-statement variable name (`key` vs `keys`) is set by `buildRecordParentKeyExtraction`'s arm; the dispatch reads the variable by name. `asyncWrapTail` produces the right async wrap once `valueType` is set per the rule above. The `valueType` rule is identity-typed: `AccessorRowKeyedMany` always → `Record`; `RowKeyed` / `LifterRowKeyed` / `AccessorRowKeyedSingle` → `Record` for single fields, `List<Record>` for list fields (the existing FK rule, unchanged).

c. **Constraint summary** for the legality grid:

| Field cardinality        | SINGLE accessor | LIST / SET accessor |
|--------------------------|-----------------|---------------------|
| List (`[Type]`)          | reject (`AccessorDerivation.CardinalityMismatch`) | accept (`AccessorRowKeyedMany`) |
| Single (`Type`)          | accept (`AccessorRowKeyedSingle`) | reject (`AccessorDerivation.CardinalityMismatch`) |

The classifier enforces both rejections explicitly via the §2.b match rule before constructing either accessor permit. The emitter never sees an illegal combination because `AccessorRowKeyedMany`'s identity guarantees `field.returnType().wrapper().isList() == true` and `AccessorRowKeyedSingle`'s identity guarantees the converse, per *Classifier guarantees shape emitter assumptions* (and the load-bearing keys in §4).

### 4. Declare new `@LoadBearingClassifierCheck` keys

The auto-derivation introduces emitter-side assumptions that each warrant a paired `@LoadBearingClassifierCheck` (producer) / `@DependsOnClassifierCheck` (consumer) annotation per *Classifier guarantees shape emitter assumptions*. `LoadBearingGuaranteeAuditTest` enforces the pairing automatically once the annotations are in place; relaxing a producer in a future change surfaces as orphaned consumers across the codebase.

1. **`accessor-rowkey-shape-resolved`.**
   - **Producer:** `FieldBuilder.deriveBatchKeyFromTypedAccessor`. Returns `AccessorDerivation.Ok` only when reflection has confirmed (a) a single matching public zero-arg non-bridge non-synthetic instance accessor on the parent backing class, (b) returning `X`, `List<X>`, or `Set<X>` for a concrete `X extends TableRecord`, and (c) `X`'s mapped table identical to `tb.table()`.
   - **Consumers:** `GeneratorUtils.buildAccessorRowKeySingle` and `buildAccessorRowKeyMany` cast `env.getSource()` to the resolved backing class and invoke the accessor by name without `instanceof` guards or null checks; `TypeFetcherGenerator.buildRecordBasedDataFetcher` materialises the loader value type as `Record` without defending against a wider declared accessor return.

2. **`accessor-rowkey-cardinality-matches-field`.**
   - **Producer:** `FieldBuilder.deriveBatchKeyFromTypedAccessor`. Returns `AccessorRowKeyedSingle` only when `field.returnType().wrapper().isList()` is false; returns `AccessorRowKeyedMany` only when it is true; mismatched cells become `AccessorDerivation.CardinalityMismatch` rejections (§2.b).
   - **Consumer:** `TypeFetcherGenerator.buildRecordBasedDataFetcher`. The `usesLoadMany` ⇔ `valueType = Record` rule depends on this; an `AccessorRowKeyedMany` on a non-list field would emit code expecting `List<Record>` from a `loadMany` that supplies `Record`, miscompiling generated `*Fetchers`.

The existing `lifter-batchkey-is-lifterrowkeyed` and `lifter-classifies-as-record-table-field` checks remain valid: the lifter path produces `LifterRowKeyed`, never any `AccessorRowKeyed*` permit. Update `BatchKeyLifterDirectiveResolver`'s class-level Javadoc to reflect the four-permit `RecordParentBatchKey` hierarchy so the next reader sees it; no code change there.

The annotations live under `no.sikt.graphitron.rewrite.model`; existing `@LoadBearingClassifierCheck` producers (e.g. `service-catalog-strict-tablemethod-return` in `ServiceCatalog`, `column-field-requires-table-backed-parent` and `lifter-batchkey-is-lifterrowkeyed` in `FieldBuilder`/`GeneratorUtils`) are the structural template.

### 5. Rewrite the existing FK-only rejection message

**File:** `graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java` (RecordTableField branch at L2619, RecordLookupTableField branch at L2613; line-number drift expected, re-anchor by identifier).

Replace both `Rejection.structural(...)` argument strings with the literal three-option text quoted in §2.a (the `if (batchKey == null)` fallback that fires when accessor-derivation also fails). The rewrite is wholesale, not an append: the original sentence's "Add @batchKeyLifter ... or back the parent with a typed jOOQ TableRecord" two-option phrasing is restructured around three options. The lookup variant substitutes `RecordLookupTableField` for `RecordTableField` in the leading clause; the rest of the text is identical.

## Tests

### Unit tests

This plan adds **`BatchKeyTest`** at `graphitron/src/test/java/no/sikt/graphitron/rewrite/model/BatchKeyTest.java` (`@UnitTier`). No `BatchKeyTest` exists today; the new class is the primary unit-tier home for `BatchKey` invariants going forward. Cases:

- `AccessorRowKeyedSingle.javaTypeName()` for one-column PK: `"java.util.List<org.jooq.Row1<java.lang.Long>>"` (matches the convention `LifterRowKeyed` follows today; revisit under R61 if that key-emission convention shifts to `RecordN`).
- `AccessorRowKeyedMany.javaTypeName()` for two-column composite PK.
- `AccessorRowKeyedSingle.targetKeyColumns()` and `AccessorRowKeyedMany.targetKeyColumns()` each return the same instance as `hop.targetColumns()` (single source of truth, mirrors `LifterRowKeyed`'s own contract).
- The sealed switch over `RecordParentBatchKey` is exhaustive across the four permits at compile time: rather than a runtime `default -> fail()` that catches a stale concrete instance, the test is a pattern-matching switch expression with no `default` arm that returns a sentinel for each permit; if a future fifth permit is added without updating the test, the test source itself fails to compile, surfacing the gap at the same site (and on the same build) the production switch site does. A redundant `default -> fail()` would only fire on shapes the compiler already rejects, so it adds no signal.

### Pipeline tests

**`GraphitronSchemaBuilderTest`**: five new cases covering the cross-product corners:

1. `ACCESSOR_ROWKEYED_MANY_LIST_FIELD_LIST_ACCESSOR`: `[KvoteSporsmalSvar]` on a `@record` parent whose backing class has `getSvar(): List<SoknadKvotesporsmalSvarRecord>`. Expect `RecordTableField` with `batchKey instanceof AccessorRowKeyedMany arm && arm.container() == LIST`. A sibling parameterised case asserts the same shape for `Set<...>` accessors with `arm.container() == SET`.
2. `ACCESSOR_ROWKEYED_SINGLE_SINGLE_FIELD_SINGLE_ACCESSOR`: single child `Type` on a record parent with `getOwner(): UserRecord`. Expect `RecordTableField` with `batchKey instanceof AccessorRowKeyedSingle`.
3. `ACCESSOR_ROWKEYED_REJECTS_AMBIGUOUS`: record parent with two accessors both returning `List<XRecord>` for the same `@table` X. Expect `UnclassifiedField` with the ambiguity message naming both accessors.
4. `ACCESSOR_ROWKEYED_REJECTS_CARDINALITY_MISMATCH`: list field with single accessor and single field with list accessor (one parameterised case per direction). Expect `UnclassifiedField` carrying the `AccessorDerivation.CardinalityMismatch` message.
5. `ACCESSOR_ROWKEYED_REJECTS_HETEROGENEOUS_ELEMENT`: accessor returns a `TableRecord` whose mapped table is *not* the field's `@table`. Expect `UnclassifiedField` falling through to the rewritten three-option message from §5.

Per the testing-tier rules in `rewrite-design-principles.adoc`, none of these assert on generated code strings; they assert on the classified `BatchKey` variant's identity and component values, plus the `UnclassifiedField` message text.

### Compilation tests

The compilation-tier surface today is `GeneratedSourcesSmokeTest` and `GeneratedSourcesLintTest` (both `@CompilationTier`, in `graphitron-test/src/test/java/no/sikt/graphitron/rewrite/test/`); they assert that the generator emits the expected classes and that `mvn compile -pl :graphitron-test -Plocal-db` produces well-typed code under `<release>17</release>`. Extend the fixtures schema (`graphitron-test/src/main/resources/graphql/schema.graphqls`) with the two accepting cases (list field with list accessor → `AccessorRowKeyedMany`, single field with single accessor → `AccessorRowKeyedSingle`), and add the two backing payload classes alongside the existing `CreateFilmPayload` (see Execution tests §"Fixture path"). `GeneratedSourcesSmokeTest`'s class roster picks up the new `*Type` / `*Fetchers` classes; `GeneratedSourcesLintTest` continues to assert no missing imports / unused types. The compilation tier covers `buildRecordParentKeyExtraction`'s two new arms and the `buildRecordBasedDataFetcher` `loadMany` branch by virtue of the schema additions; no new test class is needed.

### Execution tests

A new execution-tier test class in `graphitron-test/src/test/java/no/sikt/graphitron/rewrite/test/`, `@ExecutionTier`-annotated, sibling to the existing `MutationPayloadLifterTest` (which exercises `@batchKeyLifter` on `CreateFilmPayload`; the cousin path the new accessor permits replace authoring-side). Suggested name: `AccessorDerivedBatchKeyTest`. Two cases:

- **Many-accessor fixture:** add a mutation `createFilms(input: ...): CreateFilmsPayload` whose payload is a Java record `CreateFilmsPayload(List<FilmRecord> films)` exposing `films(): List<FilmRecord>` via the record's accessor (records produce zero-arg accessors automatically; matches the §2.b match rule's exact-name arm). Schema field `films: [Film]` returns `@table(table: "film")`. Verify the mutation returns rows projected through Film's selection set, including a child `@table` field (e.g. `language`) so the second-hop DataLoader chain is also exercised.
- **Single-accessor fixture:** add a payload `CreateFilmSinglePayload(FilmRecord film)` with schema field `film: Film` (single). Verify the resolver returns the inserted film and resolves at least one of its `@table` children.

**Fixture path.** The payload classes live alongside `CreateFilmPayload` at `graphitron-fixtures/src/main/java/no/sikt/graphitron/rewrite/test/services/` (the Java sources sibling to schema.graphqls, compiled as part of the fixtures jar that `graphitron-test` consumes). The `graphitron-fixtures-codegen` module is a code-generator stub for the NodeId fixture; payload classes do not live there. Schema additions go in `graphitron-test/src/main/resources/graphql/schema.graphqls`. The mutation and service-method scaffolding mirror the existing `recentlyCreatedFilms` / `CreateFilmPayload` shape.

*Before landing:* confirm the payload-class shape compiles in `graphitron-fixtures` (whose generated jOOQ classes are Java 17 compatible per `CLAUDE.md`'s release-target note), and that `-Plocal-db` is included in every `mvn install` run during development (per the fixtures-jar clobber footgun).

## Success criteria

### Automated

- `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` passes; includes the new pipeline / compilation / execution cases.
- The five new pipeline-test cases pass with the expected `BatchKey` variant identities (`AccessorRowKeyedSingle` / `AccessorRowKeyedMany`) and rejection-message texts.
- The two new execution-test cases return the correct rows.
- The classifier sealed-switch exhaustiveness over `RecordParentBatchKey` is verified at compile time (any caller who misses an `AccessorRowKeyedSingle` or `AccessorRowKeyedMany` arm fails to compile).
- `LoadBearingGuaranteeAuditTest` sees both new keys (`accessor-rowkey-shape-resolved`, `accessor-rowkey-cardinality-matches-field`) with paired producer / consumer annotations; no orphans.

### Manual

- The in-the-wild `LagreKvotesporsmalSvarPayload.svar` rejection in `sis-graphql-spec` no longer fires when re-run against the new classifier; the field classifies as `RecordTableField` with `AccessorRowKeyedMany`.
- A repo-wide grep for the old "RecordTableField on a free-form DTO parent requires @batchKeyLifter" string returns zero matches; the rewritten three-option message replaces both occurrences.

## References

Identifier-level references; line numbers drift. Re-anchor by name during implementation:

- Classifier rejection site: `FieldBuilder.classifyChildFieldOnResultType`'s `TableBoundReturnType` arm, both the plain (`RecordTableField`) and lookup (`RecordLookupTableField`) branches. Today the two `Rejection.structural(...)` calls live at L2619 and L2613.
- FK-derivation helper that returns null in the no-FK case: `FieldBuilder.deriveBatchKeyForResultType` (currently L2689).
- Mirror for the reflection walk: `ServiceCatalog.classifySourcesType` (container-and-element classification, currently L594).
- Codegen switch to extend: `GeneratorUtils.buildRecordParentKeyExtraction` (currently L199, two-arm sealed switch over `RecordParentBatchKey`); paired with `TypeFetcherGenerator.buildRecordBasedDataFetcher` (currently L2555, the `loader.load` dispatch).
- Sealed taxonomy doc to extend: `BatchKey` (top-level sealed interface and `RecordParentBatchKey` permits clause); `LifterRowKeyed` is the structural template, including the `JoinStep.LiftedHop` ownership pattern and the `targetKeyColumns()` delegation.
- `JoinStep.LiftedHop` constructor shape: `LiftedHop(TableRef targetTable, List<ColumnRef> targetColumns, String alias)` (currently L167).
- Rejection API: `Rejection.structural(message)` factory (in `model/Rejection.java`), producing a `Rejection.AuthorError.Structural`. Avoid invented enum forms.
- Load-bearing template: `BatchKeyLifterDirectiveResolver` carries paired `@LoadBearingClassifierCheck(key = "lifter-classifies-as-record-table-field")` and `@LoadBearingClassifierCheck(key = "lifter-batchkey-is-lifterrowkeyed")` annotations at L111 and L117; `GeneratorUtils.buildLifterRowKey` (currently L247) carries the matching `@DependsOnClassifierCheck(key = "lifter-batchkey-is-lifterrowkeyed")` annotation (currently L242). New keys in §4 follow the same shape.
- Audit harness: `LoadBearingGuaranteeAuditTest` enforces producer / consumer pairing across the rewrite module.
- Execution-tier sibling test for the cousin path: `MutationPayloadLifterTest` (`graphitron-test/src/test/java/.../test/`) exercises `@batchKeyLifter` on `CreateFilmPayload`. The new `AccessorDerivedBatchKeyTest` mirrors its tier and fixture style.
- Principles cross-refs: *Sealed hierarchies over enums for typed information*, *Narrow component types over broad interfaces*, *Classifier guarantees shape emitter assumptions*, *Builder-step results are sealed*, *Classification belongs at the parse boundary* in `graphitron-rewrite/docs/rewrite-design-principles.adoc`.
- Related roadmap items (no hard dependency, no file collision):
  - R32 ([`service-rows-method-body.md`](service-rows-method-body.md), Spec): strict child-`@service` return-type validation on the `ParentKeyed` axis. Different code site (`ServiceDirectiveResolver`) and different `BatchKey` sub-hierarchy (`ParentKeyed`); R60 lives on the `RecordParentBatchKey` sub-hierarchy. The two specs do not overlap.
  - R61 ([`emit-record1-keys-instead-of-row1.md`](emit-record1-keys-instead-of-row1.md), Backlog): `Row1<T>`/`RowN<...>` → `RecordN<...>` key-emission switch. R60 mirrors `LifterRowKeyed`'s existing `RowN` convention; whichever item lands first, the second sweeps the new permits in along with the existing siblings. See *What we're NOT doing* §"`Row1` / `RowN` → `RecordN` key-emission switch".
  - R36-era composite-PK plumbing (multi-column PK case for the new accessor path uses the same `ColumnRef` list shape).
  - `BatchKeyLifterDirectiveResolver` (the existing lifter-directive path; `LifterRowKeyed` is R60's structural template).
