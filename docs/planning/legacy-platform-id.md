# Platform-id as synthesized NodeId

> **Status:** Draft
>
> Pivot: KjerneJooqGenerator will be extended to emit `__ID_TYPE_ID` + `__ID_KEY_COLUMNS` constants on every platform-id table class. The rewrite reads them, synthesizes a `NodeType` classification, and routes all downstream work (projection, filter, mutation binding) through the same `@nodeId` paths. `PlatformIdField` sum-type variants are deleted. Supersedes the previous four-item plan (`getId`/`hasId`/`hasIds`-driven classification and emission).

## Why the pivot

The earlier plan classified platform-id as its own sum-type variant (`InputField.PlatformIdField`, `ChildField.PlatformIdField`), reflected on per-table `getId()`/`setId()`/`hasId()`/`hasIds()` methods emitted by `KjerneJooqGenerator`, and would have introduced a parallel filter-emission path (`PlatformIdArg`, `PlatformIdMapping`, platform-id arm in `LookupValuesJoinEmitter`). Two things make that wrong:

- **It invents a parallel type system for something already classified.** Platform-id tables are structurally composite-key node types. The rewrite already has `NodeType` / `ChildField.NodeIdField` (plus the planned input-side and emitter work for `@nodeId`) carrying exactly the metadata a composite key needs: `typeId` + `nodeKeyColumns`. Reflecting on `getId()` to re-derive the same information via method naming + return-type matching is the model-metadata-over-parallel-type-systems anti-pattern, applied twice.
- **Sikt owns KjerneJooqGenerator.** The generator can expose the underlying metadata directly. With `__ID_TYPE_ID` + `__ID_KEY_COLUMNS` constants, every place the rewrite would have called `table.hasIds(set)` instead calls `NodeIdStrategy.hasIds(typeId, set, keyColumns)` — the same helper `@nodeId` fields use. One code path, one set of tests.

Trade-off: a KjerneJooqGenerator release is required before the rewrite can depend on the metadata. Sikt controls the release cadence; we coordinate rather than maintain a reflection-based fallback forever. See **Migration** below.

---

## KjerneJooqGenerator contract

On every table class where it currently emits `getId()` / `getPersonId()` / `hasId` / `hasIds`, KjerneJooqGenerator additionally emits two public constants:

```java
public static final String __ID_TYPE_ID = "Customer";
public static final Field<?>[] __ID_KEY_COLUMNS = { STORE_ID, CUSTOMER_ID };
```

- **`__ID_TYPE_ID`** — the value used today when encoding the composite ID's base64 prefix. Stable across regens; matches whatever a consumer would write as `@node(typeId: "Customer")` in SDL for the same table.
- **`__ID_KEY_COLUMNS`** — the underlying `Field<?>` references in positional order. `NodeIdStrategy.unpackIdValues(typeId, base64Id, fields)` pairs the CSV-decoded values positionally with this array, and `NodeIdStrategy.createId(typeId, keyFields)` encodes in the same order. Any reordering between `createId` (encode) and `hasIds` (decode) produces silently-wrong composite keys, so this order is load-bearing.

Static finals, not instance fields — accessible as `Customer.__ID_TYPE_ID` / `Customer.__ID_KEY_COLUMNS` without needing an instance. Reflection lookup is trivial.

The existing method emissions (`getId()`, `hasId`, `hasIds`) can stay — harmless for non-graphitron callers. The rewrite stops detecting and calling them.

---

## Classification

### Synthesize `NodeType` from metadata

`TypeBuilder.buildTableBoundType` (`TypeBuilder.java:246-270`) today branches on whether the GraphQL type has `@node`:

- Has `@node` → `NodeType(typeId, nodeKeyColumns)`
- Otherwise → `TableType` (no node identity)

With the pivot, a third branch: if the GraphQL type has `@table` but no `@node`, and the backing jOOQ table exposes the metadata constants, synthesize `NodeType` using `__ID_TYPE_ID` and `__ID_KEY_COLUMNS`.

**Synthesis is unconditional when metadata is present.** A `@table` type with metadata becomes `NodeType` regardless of whether it declares an `id: ID!` field — the classifier is type-uniform. Behaviour changes only for paths that branch on `NodeType` vs `TableType` at the type level; today those paths (federation entity fetchers, node-interface implementations) are intended to fire for any node-identified table. Step 2 (Scheduling) includes an audit of every `instanceof NodeType` / `switch (... NodeType ...)` site to verify no metadata-carrying table without an `id` field regresses. If the audit surfaces a real divergence, fall back to narrowing synthesis to types declaring at least one `id: ID!`.

**Collision rule — when both `@node` and metadata are present:**

- **Values match exactly** (`typeId` equal, `keyColumns` equal in order and arity) → accept; treat as `NodeType` with those values. Declaring `@node` redundantly is harmless.
- **Values disagree** → classifier error (`UnclassifiedType`), with both sides in the diagnostic (`"@node(typeId: 'Foo') disagrees with KjerneJooqGenerator metadata (typeId: 'Bar')"`). "Explicit wins" is rejected — silent override of the generator's metadata by a typo-prone SDL literal is exactly the drift this pivot eliminates. This rule applies symmetrically to `typeId` and `keyColumns`: encoding base64 IDs whose decode order doesn't match SDL's declared composite would be a correctness bug, not a drift to paper over.
- **`@node` declared without `typeId:` or `keyColumns:`** → no claim about the missing argument; metadata's value is taken verbatim. The presence of `@node` asserts node-identity; the omitted argument does not disagree with anything. Today `TypeBuilder.java:253-255` already treats an omitted `keyColumns:` as "use the PK at codegen time"; under the pivot, the metadata's list is used instead (and the two agree on well-formed schemas, since the PK and the composite-key columns coincide on platform-id tables).

The pipeline-test table below encodes the rule.

`JooqCatalog` grows:

```java
public Optional<NodeIdMetadata> nodeIdMetadata(String tableSqlName);

public record NodeIdMetadata(String typeId, List<ColumnRef> keyColumns) {}
```

Implementation: reflect on the table class for `public static final String __ID_TYPE_ID` and `public static final Field<?>[] __ID_KEY_COLUMNS`; resolve each `Field<?>` to its `ColumnRef` via the existing column-resolution machinery. Returns empty when either constant is absent.

### Output-side `NodeIdField`

Already exists: `ChildField.NodeIdField(parentTypeName, name, location, nodeTypeId, nodeKeyColumns)`. No schema changes. The classifier path at `FieldBuilder.java:1559-1564` that emits `NodeIdField` for `@nodeId` on a `NodeType` now fires for the synthesized `NodeType` too — no new detection logic. The existing platform-id fallback in `FieldBuilder` (around line 1592) is deleted.

### Input-side `NodeIdField` (new variant)

`InputField` today permits `ColumnField`, `ColumnReferenceField`, `PlatformIdField`, `NestingField`. Add:

```java
record NodeIdField(
    String parentTypeName,
    String name,
    SourceLocation location,
    String nodeTypeId,
    List<ColumnRef> nodeKeyColumns
) implements InputField {}
```

`TypeBuilder.resolveInputField` (`TypeBuilder.java:558-574` is where `PlatformIdField` currently classifies) becomes: scalar `ID`, not a list; if the backing table resolves to a synthesized-or-declared `NodeType`, classify as `InputField.NodeIdField` carrying the same `typeId` + `keyColumns` pair.

**`InputField.NodeIdField` is the first input-side NodeId classifier.** Today the `@nodeId` directive is only read on *output* fields (`FieldBuilder.java:1541-1565`); on the input side, `TypeBuilder.java:563` uses `!hasDirective(@nodeId)` purely as an exclusion gate for the platform-id fallback — there is no existing input-side `@nodeId` classifier path. This variant covers both SDL shapes uniformly:

- `input FooLookup { id: ID! }` on an `@table` whose backing jOOQ table carries the metadata constants → synthesized-route `NodeIdField`.
- `input FooLookup { id: ID! @nodeId }` (declared) → same `NodeIdField` classification.

Both paths produce the same variant carrying `(typeId, keyColumns)`; the declared-`@nodeId` path is added as part of Step 4 (classifier + argument side land together).

### Argument side — `NodeIdArg`

`FieldBuilder.classifyArgument` (`FieldBuilder.java:649`) currently binds scalar args via `findColumn`. Add a pre-step: if `typeName == "ID"`, the target table is `NodeType`, and no `@nodeId` directive on the arg, emit `ArgumentRef.ScalarArg.NodeIdArg(name, typeName, nonNull, list, nodeTypeId, keyColumns, extraction, argCondition, isLookupKey)` instead of looking for a column. This is the unified replacement for the previously-proposed `PlatformIdArg`.

### `LookupMapping` becomes a sum type

```java
sealed interface LookupMapping permits ColumnMapping, NodeIdMapping {
    TableRef targetTable();
}

record ColumnMapping(List<LookupColumn> columns, TableRef targetTable) implements LookupMapping {}

record NodeIdMapping(String nodeTypeId, List<ColumnRef> nodeKeyColumns,
                     CallSiteExtraction extraction, TableRef targetTable) implements LookupMapping {}
```

`projectForLookup` produces `NodeIdMapping` when the single lookup arg is `NodeIdArg`; otherwise `ColumnMapping` as today.

**Why the variants are asymmetric.** `ColumnMapping` carries per-column `CallSiteExtraction` inside each `LookupColumn` because each lookup key is an independent argument (or input-type field) extracted separately. `NodeIdMapping` carries *one* `extraction` at the top level because a NodeId is a single argument (a base64 string or list of strings) that decodes into N target columns — the extraction happens once, then `NodeIdStrategy.hasIds` expands it across `keyColumns`. Pushing `extraction` into each `nodeKeyColumn` would lie about independence that doesn't exist.

**`CallSiteExtraction` for `NodeIdArg`.** Four argument shapes feed a `NodeIdArg`: scalar `ID` arg, list `[ID!]`, nested input-type field, nested list-of-inputs with an ID-bearing field. Scalar and list cases use the existing `Direct` permit; the two nested shapes land in a new permit added in Step 4:

```java
record NestedInputId(String argName, List<String> path, int listDepth) implements CallSiteExtraction {}
```

`argName` is the outer argument name; `path` walks named fields through successive input types to reach the ID-bearing leaf; `listDepth` counts list wrappers encountered along the path (`0` = flat scalar, `1` = outer list of inputs, `≥2` reserved for deeper shapes if they surface later). The `LookupValuesJoinEmitter` `NodeIdMapping` arm reads these to assemble a `Set<String>` before the `hasIds` call — iterating `listDepth` list levels plus `path.size()` field accesses with null-guards per legacy's `_nit_in != null ? _nit_in.getId() : null` pattern.

This permit is intentionally narrow and independent of argres Phase 3's `InputColumnBinding`. Phase 3 lands a richer input-to-column binding mechanism that `NestedInputId` is a strict subset of; once shipped, this permit collapses into `InputColumnBinding` as a mechanical follow-up. Keeping it small now lets Step 4 ship without waiting on Phase 3. See Open points for the collapse tracking.

---

## Generators

Platform-id stops existing as a separate emission path. Everything flows through `@nodeId` generators — which this plan co-lands (today they're stubs).

### Projection — `TypeClassGenerator.$fields`

For each `ChildField.NodeIdField` on a type, emit:

```java
case "id" -> fields.add(nodeIdStrategy.createId(
    "Customer",
    new Field<?>[] { table.STORE_ID, table.CUSTOMER_ID }));
```

Uses `NodeIdStrategy.createId(typeId, keyFields)` — already lives in `graphitron-common`. One projection path for both platform-id and declared `@nodeId` types.

### Read-back — `record.into(...)`

The mapping-from-`Record` path derives the ID string via `nodeIdStrategy.createId(record, typeId, keyColumns)`. Same helper, same signature.

### Filter — lookup path

`LookupValuesJoinEmitter` dispatches on `LookupMapping` variant.

`NodeIdMapping` arm skips VALUES+JOIN entirely:

```java
Set<String> ids = /* extraction from env per CallSiteExtraction */;
return dsl
    .select(<typeFieldsCall>)
    .from(table)
    .where(ids.isEmpty()
        ? DSL.noCondition()
        : nodeIdStrategy.hasIds(
            "Customer", ids,
            new Field<?>[] { table.STORE_ID, table.CUSTOMER_ID }))
    .fetch();
```

Input-order preservation (`orderBy(input.idx)`) does not apply — `hasIds` is a set membership check, not a positional join. Legacy accepts this; we match.

### Filter — non-lookup path

Field-level `@condition` / plain-WHERE emission for a `NodeIdArg`:

```java
condition = id != null
    ? nodeIdStrategy.hasId("Customer", id, keyFields)
    : DSL.noCondition();
```

List variant uses `hasIds` with empty-list guard.

### Mutation input binding

`InputColumnBinding` (the shared type argres Phase 3 populates on `TableInputArg.fieldBindings`) includes a `NodeIdField` variant. The mutation generator dispatches:

```
NodeIdBinding  →  nodeIdStrategy.setId(record, input.getId(), typeId, keyFields)
```

Uses `NodeIdStrategy.setId(UpdatableRecordImpl, String, String, Field<?>...)` — already lives in `graphitron-common`. Same helper for platform-id and declared `@nodeId` inputs.

Blocked on the same argres Phase 3 the previous plan's Item 3 was blocked on.

---

## Deletions

After the migration lands, these go away:

- `InputField.PlatformIdField` record + its `permits` entry (`InputField.java`).
- `ChildField.PlatformIdField` record + its `permits` entry + the `$fields` arm in `TypeClassGenerator` that emits `table.<getterName>()`.
- `FieldBuilder` output-side platform-id fallback (around line 1592), input-side platform-id fallback (around `TypeBuilder.java:558-574`).
- `JooqCatalog.platformIdOutputMethodNames(String)`, `JooqCatalog.hasPlatformIdAccessors(String, String, String)`, `JooqCatalog.recordHasPlatformIdAccessors(Class, String, String)`, `JooqCatalog.sqlToAccessorSuffix(String)` (the last one is unused once the accessor-suffix derivation is gone).
- `TypeFetcherGenerator.IMPLEMENTED_LEAVES` entries for `PlatformIdField` variants + their switch arms.
- `ChildPlatformIdFieldValidationTest`, `InputPlatformIdFieldValidationTest`, the `PlatformIdField` assertions in `PlatformIdPipelineTest`. The pipeline test stays but asserts `NodeIdField` outcomes; the synthetic fixture adds the `__ID_TYPE_ID` + `__ID_KEY_COLUMNS` constants on `Bar`.
- The variant-coverage Phase-1 partition entries for `PlatformIdField` — they disappear along with the record.

---

## Synthetic fixture update

`no.sikt.graphitron.rewrite.platformidfixture.tables.Bar` grows:

```java
public static final String __ID_TYPE_ID = "Bar";
public static final Field<?>[] __ID_KEY_COLUMNS = { /* a real column or two */ };
```

Needs at least one real `TableField` column to serve as a key. Add `ID_1`, `ID_2` as varchar columns (matching what a composite key shape would look like). `getId()` and `getPersonId()` can stay as harmless no-ops or be removed — the rewrite no longer reflects on them.

`Qux` keeps no metadata constants — stays the negative-case fixture ("no platform-id / no synthesized NodeType").

`PlatformIdPipelineTest` cases become:

**Output side** (`FieldBuilder` classification against a synthesized or declared `NodeType`):

| SDL | Expected outcome |
|-----|-----------------|
| `type Foo @table(name: "bar") { id: ID! }` | `ChildField.NodeIdField(nodeTypeId="Bar", nodeKeyColumns=[ID_1, ID_2])` — synthesized |
| `type Foo @table(name: "bar") @node(typeId: "Bar") { id: ID! }` | Same — matching `@node` accepted, values from metadata (or equivalently `@node`) |
| `type Foo @table(name: "bar") @node { id: ID! }` | Same — `@node` without args delegates to metadata |
| `type Foo @table(name: "bar") @node(typeId: "Foo") { id: ID! }` | Classifier error — `@node(typeId: "Foo")` disagrees with metadata (`"Bar"`); both values in the diagnostic |
| `type Foo @table(name: "bar") { id: ID! @nodeId }` | `NodeIdField` via the existing `@nodeId` path (directive-driven, not synthesis) |
| `type Foo @table(name: "bar") { id: [ID!]! }` | `UnclassifiedField` — list gate short-circuits |
| `type Foo @table(name: "qux") { id: ID! }` | `UnclassifiedField` — no real column, no metadata, no synthesis |
| `type Foo @table(name: "bar") { name: String }` (no `id` field) | Type classifies as `NodeType` (metadata present); no field-level impact — uniform synthesis rule |

**Input side** (`TypeBuilder.resolveInputField`; same disagreement and list-gate rules):

| SDL | Expected outcome |
|-----|-----------------|
| `input FooLookup @table(name: "bar") { id: ID! }` | `InputField.NodeIdField(nodeTypeId="Bar", nodeKeyColumns=[ID_1, ID_2])` — synthesized |
| `input FooLookup @table(name: "bar") { id: ID! @nodeId }` | `InputField.NodeIdField` via declared `@nodeId` (first-time classifier path; see Input-side section) |
| `input FooLookup @table(name: "bar") @node(typeId: "Foo") { id: ID! }` | Classifier error — same disagreement message as output |
| `input FooLookup @table(name: "bar") { id: [ID!]! }` | `UnclassifiedType` — one unresolved field collapses the `TableInputType` |
| `input FooLookup @table(name: "qux") { id: ID! }` | `UnclassifiedType` — no metadata on `qux` |

---

## Migration

Consumers regenerate jOOQ classes with the new KjerneJooqGenerator release before their rewrite build finds the metadata. During the transition window:

- **Preferred path — hard cut-over.** The rewrite requires the new metadata. Tables that were platform-id under the old generator but lack the constants classify as `TableType` (no synthesized NodeId) — any schema using `id: ID!` on them lands on `UnclassifiedField` with a diagnostic pointing at "regenerate jOOQ classes with KjerneJooqGenerator ≥ X.Y". Clean, simple, discoverable.
- **Fallback — not chosen.** Keeping the old `hasPlatformIdAccessors` detection alongside the new path for N releases would drag the platform-id-specific classification infrastructure through a deprecation window. Avoid unless coordination breaks down.

Because Sikt owns both the generator and the consuming rewrite, the hard cut-over is realistic.

**Release sequencing.** The order is load-bearing, and every consumer needs to follow it:

1. KjerneJooqGenerator X.Y ships with the metadata constants.
2. Consumers regenerate their jOOQ classes against X.Y — their build now has the constants, nothing else changes.
3. Rewrite release Y.Z ships with `minKjerneJooqGeneratorVersion = X.Y` in its docs/compatibility table, synthesis on, `PlatformIdField` deleted.
4. Consumers bump rewrite to Y.Z.

Skipping step 2 before step 4 fails loudly: every `id: ID!` on a (formerly) platform-id table classifies as `UnclassifiedField` with the "regenerate jOOQ classes" diagnostic. Skipping step 3 and upgrading rewrite first is impossible — Y.Z is the first release that requires the constants.

---

## Scheduling

Five commits, roughly in order. Each lands independently and green.

1. **`JooqCatalog.nodeIdMetadata` probe** — reflection over `__ID_TYPE_ID` + `__ID_KEY_COLUMNS`. No callers yet; unit-tested against the synthetic fixture (which gets the constants added in the same commit) and against at least one test-spec jOOQ table so the compile tier exercises real metadata. No test changes needed elsewhere — `PlatformIdField` variants still fire for everything.
2. **`TypeBuilder` synthesizes `NodeType`** — classifier reads `nodeIdMetadata` when `@node` absent + `@table` present. Existing output-side classifier stops relying on `platformIdOutputMethodNames` / the platform-id fallback. `ChildField.PlatformIdField` still exists but is now unreachable for fixtures that carry the metadata. **`NodeType` audit (gating):** grep every `instanceof NodeType` / `switch (... NodeType ...)` site in the rewrite and verify generator behaviour is unchanged for metadata-carrying tables without `id: ID!` fields — the unconditional synthesis rule must not regress pure-inline nested types. If any site fires undesirably, narrow synthesis to types declaring at least one `id: ID!` and re-run the audit. **Test migration:** `PlatformIdPipelineTest` cases flip from asserting `PlatformIdField` to asserting the synthesized-`NodeType` classification path (the final `NodeIdField` assertion doesn't land until step 3 wires emission). `ChildPlatformIdFieldValidationTest` / `InputPlatformIdFieldValidationTest` stay green — they assert structure of the stub path, still reachable from any fixture without metadata. Collision-rule (`@node` disagrees with metadata) test case lands here, plus the omitted-args-delegate test case.
3. **`ChildField.NodeIdField` emission** — move out of `NOT_IMPLEMENTED_REASONS`; implement projection in `TypeClassGenerator.$fields` via `NodeIdStrategy.createId`. Reuses existing helper. **Test migration:** `PlatformIdPipelineTest`'s `NodeIdField` assertions become full (classification + emission). New execution test in `graphitron-rewrite-test-spec` for `id` field on a metadata-carrying table.
4. **`NodeIdArg` + `LookupMapping` sum type + emitter branches** — classifier produces `NodeIdArg` for scalar ID args targeting NodeType; declared-`@nodeId`-on-input folds in here; `projectForLookup` produces `NodeIdMapping`; `LookupValuesJoinEmitter` dispatches on variant; non-lookup filter path handles the same. Pipeline-test coverage for both lookup and non-lookup shapes. **Test migration:** the `InputField.PlatformIdField` assertions in `InputPlatformIdFieldValidationTest` flip to `InputField.NodeIdField`. New execution test for a lookup query using platform-id keys.
5. **Delete `PlatformIdField` sum-type variants + supporting catalog methods** — the variant records, the `FieldBuilder`/`TypeBuilder` fallbacks, `hasPlatformIdAccessors`, `platformIdOutputMethodNames`, `sqlToAccessorSuffix` (if no other caller), `IMPLEMENTED_LEAVES` entries, `ChildPlatformIdFieldValidationTest` / `InputPlatformIdFieldValidationTest`. Partition invariants in the variant-coverage meta-test resolve automatically since the record no longer exists. **Test migration:** the two variant-specific test classes are deleted; no replacements needed — pipeline coverage for `NodeIdField` outcomes shipped in steps 2-4.

Mutation binding (previous Item 3) remains gated on argres Phase 3 and lands via that plan — but as a `NodeIdField` input variant, not `PlatformIdField`.

---

## Resolved points

- **`__ID_TYPE_ID` naming rule.** KjerneJooqGenerator derives `__ID_TYPE_ID` from the same source today's `@node(typeId: "...")` literals use — the GraphQL type name associated with the table in Sikt's schema conventions. The collision rule above (error on disagree) only works if this holds; if the generator picks a different source (e.g. the raw table-class stem `"CustomerRecord"` while SDL uses `"Customer"`), every hybrid schema fails classification. Pin the rule when the generator change is drafted; the rewrite-side consumer treats `__ID_TYPE_ID` as opaque.
- **Constants as `Field<?>[]` vs. `List<Field<?>>`.** Array is what jOOQ `hasIds` signatures take; list is easier to build. KjerneJooqGenerator emits `Field<?>[]` (the consumer shape); the rewrite converts to `List<ColumnRef>` at the `JooqCatalog` boundary for model storage.

## Compile-tier coverage

`graphitron-rewrite-test-spec` compiles against a real jOOQ catalog — the "compile against real jOOQ is a test tier" principle applies. The synthetic fixture (`platformidfixture`) covers pipeline-level classification, but emitted `nodeIdStrategy.createId(...)` / `hasIds(...)` / `setId(...)` code should also type-check against a real jOOQ table class. Step 1 adds the `__ID_TYPE_ID` + `__ID_KEY_COLUMNS` constants to at least one test-spec jOOQ table — the Sakila candidate is `store` (composite business key, already present in fixtures) or a synthetic table if no Sakila table matches. Execution tests for `NodeIdField` projection and `NodeIdArg` lookup land as part of Step 3 / Step 4 respectively.

## Open points

- **Consumers with mixed schemas** — some types `@node`, some platform-id. Both synthesize or declare `NodeType`; the classifier should not care. Pipeline-test cases per the tables above cover this.
- **Federation entity lookups.** Legacy's `FetchEntityImplementationDBMethodGenerator` dispatches on `processedSchema.isNodeIdField(field)`. The rewrite's equivalent (when it lands) will match on `ChildField.NodeIdField` — no platform-id branch needed.
- **`JooqCatalog.nodeIdMetadata` memoisation.** Many GraphQL types can reference the same jOOQ table class (base type + paginated-wrapper variants + input-shape siblings). Cache the metadata lookup per table class inside the `JooqCatalog` instance — that instance already lives for the duration of the build, and every lookup is pure reflection. Sub-microsecond per-hit win, but closes a foot-gun if the classifier ever iterates types inside a hot loop.
- **`NestedInputId` → `InputColumnBinding` collapse.** Once argres Phase 3 lands `InputColumnBinding`, the `NestedInputId` permit added in Step 4 collapses into it — same path-walking semantics, wider binding type. Track as a mechanical cleanup commit after Phase 3; not a blocker for this plan, and the permit is narrow enough that the collapse is local to two files (`CallSiteExtraction.java` + `LookupValuesJoinEmitter`).

---

## History

- **2026-04-19 (second Draft iteration)** — resolved N1–N4 non-trivial choices with author input:
  - **N1.** `@node` without `keyColumns:` (or without `typeId:`) makes no claim about the omitted argument; metadata is taken verbatim. No collision possible on omitted values.
  - **N2.** Error-on-disagree applies symmetrically to `typeId` and `keyColumns`. Encoding IDs whose decode order doesn't match SDL's declared composite is a correctness bug; no escape hatch.
  - **N3.** Synthesis is unconditional when metadata is present — classifier is type-uniform regardless of whether the SDL type declares `id: ID!`. Step 2 adds a `NodeType` audit (grep every branching site) as a gating check; narrow-synthesis fallback kept in reserve if the audit surfaces a regression.
  - **N4.** New `CallSiteExtraction.NestedInputId(argName, path, listDepth)` permit lands in Step 4 independent of argres Phase 3. Collapses into `InputColumnBinding` as a mechanical follow-up once Phase 3 ships (tracked in Open points).
  - **Trivial fixes.** Pipeline table row 2 corrected from the stale "explicit wins" outcome to the classifier-error outcome the collision rule requires; added the `@node` without args case. Input-side table enumerated explicitly (was "Input parallels of the above"). `__ID_KEY_COLUMNS` ordering note now names `NodeIdStrategy.unpackIdValues` as the canonical consumer and calls out the encode/decode symmetry. Added reflection-probe memoisation and the `NestedInputId` collapse to Open points.
- **2026-04-18 (reviewer pass on pivot draft)** — resolved the `@node`-vs-synthesized contradiction (picked "error on disagree"; rejected "explicit wins" as silent-override drift, contrary to the boundary-classification principle). Verified and pinned the input-side `@nodeId` uncertainty — no existing classifier path, `InputField.NodeIdField` is the first; declared `@nodeId` inputs fold in at Step 4. Explained the `ColumnMapping`/`NodeIdMapping` asymmetry (one-arg-N-columns vs. N-independent-args; extraction lives once at top level for NodeId). Pinned `CallSiteExtraction` resolution for the nested-input shapes to Step 4 implementation time, with preferred option noted. Moved `__ID_TYPE_ID` naming from Open to Resolved (match today's `@node(typeId:)` source; pin in KjerneJooqGenerator design doc). Added release sequencing (four-step consumer upgrade order with the "skip step 2" failure mode named). Added compile-tier coverage section (Step 1 extends at least one test-spec jOOQ table with the constants). Expanded Scheduling with per-step test-migration notes so intermediate-state test orange is explicit.
- **2026-04-18 (pivot; this Draft)** — KjerneJooqGenerator will emit `__ID_TYPE_ID` + `__ID_KEY_COLUMNS` constants, letting the rewrite subsume platform-id into the existing `NodeType` / `NodeIdField` classification. Deletes `PlatformIdField` sum-type variants after migration; eliminates the parallel filter-emission path that would have been required to support `table.hasId` / `table.hasIds`. Supersedes the previous four-item plan.
- **2026-04-18 (earlier Draft iteration)** — captured the argument/filter emission gap (previous Item 4); classified as a parallel type-system problem during review. Led to the pivot above.
- **2026-04-18 (P2 #3 follow-up)** — fixed `ChildField.PlatformIdField` dispatch registration after it was incorrectly kept in `NOT_IMPLEMENTED_REASONS`. Obsoleted by this plan — the variant itself goes away.
- **Earlier** — Items 1-2 (output classification, input classification, dispatch, pipeline tests against synthetic fixture) shipped under the parallel-variant approach. Those shipped pieces are undone in Step 5 above; the classification tests migrate to `NodeIdField` outcomes on the same fixture.
