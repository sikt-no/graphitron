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

`TypeBuilder.buildTableType` (`TypeBuilder.java:241-271`) today branches on whether the GraphQL type has `@node`:

- Has `@node` → `NodeType(typeId, nodeKeyColumns)`
- Otherwise → `TableType` (no node identity)

With the pivot, a third branch: if the GraphQL type has `@table` but no `@node`, and the backing jOOQ table exposes the metadata constants, synthesize `NodeType` using `__ID_TYPE_ID` and `__ID_KEY_COLUMNS`.

**Synthesis is unconditional when metadata is present.** A `@table` type with metadata becomes `NodeType` regardless of whether it declares an `id: ID!` field — the classifier is type-uniform. Behaviour changes only for paths that branch on `NodeType` vs `TableType` at the type level; today those paths (federation entity fetchers, node-interface implementations) are intended to fire for any node-identified table. Step 2 (Scheduling) includes an audit of every `instanceof NodeType` / `switch (... NodeType ...)` site to verify no metadata-carrying table without an `id` field regresses. If the audit surfaces a real divergence, fall back to narrowing synthesis to types declaring at least one `id: ID!`.

**Collision rule — when both `@node` and metadata are present:**

- **Values match exactly** (`typeId` equal, `keyColumns` equal in order and arity) → accept; treat as `NodeType` with those values. Declaring `@node` redundantly is harmless.
- **Values disagree** → classifier error (`UnclassifiedType`), with both sides in the diagnostic (`"@node(typeId: 'Foo') disagrees with KjerneJooqGenerator metadata (typeId: 'Bar')"`). "Explicit wins" is rejected — silent override of the generator's metadata by a typo-prone SDL literal is exactly the drift this pivot eliminates. This rule applies symmetrically to `typeId` and `keyColumns`: encoding base64 IDs whose decode order doesn't match SDL's declared composite would be a correctness bug, not a drift to paper over.
- **`@node` declared without `typeId:` or `keyColumns:`** → no claim about the missing argument; metadata's value is taken verbatim. The presence of `@node` asserts node-identity; the omitted argument does not disagree with anything. The `@node` directive documentation at `graphitron-common/src/main/resources/directives.graphqls:243-244` states "if you do not specify keyColumns, we use the primary key in the database and the order set there" — today's rewrite at `TypeBuilder.java:241-271` stores the omission as an empty `keyColumns` list on `NodeType` (no PK substitution lives in `TypeBuilder`; the documented PK-fallback is a legacy-generator behaviour). Under the pivot, the metadata's list is used verbatim (and the two agree on well-formed schemas, since the PK and the composite-key columns coincide on platform-id tables).

The pipeline-test table below encodes the rule.

`JooqCatalog` grows:

```java
public Optional<NodeIdMetadata> nodeIdMetadata(String tableSqlName);

public record NodeIdMetadata(String typeId, List<ColumnRef> keyColumns) {}
```

Implementation: reflect on the table class for `public static final String __ID_TYPE_ID` and `public static final Field<?>[] __ID_KEY_COLUMNS`; resolve each `Field<?>` to its `ColumnRef` via the existing column-resolution machinery. Returns empty when either constant is absent.

**Malformed-metadata handling.** Both constants must pass sanity checks for `NodeIdMetadata` to be returned: `__ID_TYPE_ID` non-null and non-empty; `__ID_KEY_COLUMNS` non-null, non-empty, and every `Field<?>` entry resolvable to a `ColumnRef` on the same table. Any failure makes the probe return empty and pushes a build-time diagnostic keyed on the table SQL name — surfaced at the classifier boundary as an `UnclassifiedType` ("KjerneJooqGenerator metadata on table 'bar' is malformed: …") rather than silently falling back to `TableType`. Silent fallback on malformed constants would reintroduce the drift the pivot is trying to eliminate.

### Output-side `NodeIdField`

Already exists: `ChildField.NodeIdField(parentTypeName, name, location, nodeTypeId, nodeKeyColumns)`. No schema changes.

Two detection paths feed this variant after the pivot:

1. **Directive-driven (existing).** `FieldBuilder.java:1549-1576` handles the `@nodeId` directive: the `@nodeId(typeName:)` variant at `:1550-1569` produces `NodeIdReferenceField` (unchanged); the bare-`@nodeId` variant at `:1570-1576` produces `NodeIdField` (guarded by `tableType instanceof NodeType` at `:1571`, creation at `:1575`). The bare-`@nodeId` path works unchanged for both declared and synthesized `NodeType` parents — no code change.
2. **Synthesized (new).** On a `NodeType` parent (declared or synthesized), a scalar non-list `ID` field without `@nodeId`, `@reference`, or `@field` that does not match a real column via `resolveColumn` classifies as `NodeIdField(parentTypeName, name, location, nodeType.typeId(), nodeType.nodeKeyColumns())`. This path replaces the deleted platform-id fallback inside the `column.isEmpty()` block at `FieldBuilder.java:1602-1621` (platform-id detection at `:1604-1614`, creation at `:1612`). **Trigger predicate (exact):** `tableType instanceof NodeType nt` AND `"ID".equals(typeName)` AND `!isList` AND `!fieldDef.hasAppliedDirective(DIR_NODE_ID)` AND `!fieldDef.hasAppliedDirective(DIR_REFERENCE)` AND `!fieldDef.hasAppliedDirective(DIR_FIELD)` AND `resolveColumn(columnName, tableType).isEmpty()`. All six clauses match today's platform-id fallback's conditions plus the NodeType narrowing; the only behavioural delta is "no longer requires platform-id accessor methods to exist."

The platform-id fallback at `FieldBuilder.java:1602-1621` (detection `:1604-1614`, creation `:1612`) is deleted as part of Step 5; the new Path 2 detection lands in Step 3 alongside the emission code (Step 2's pipeline test for bare `id: ID!` on a synthesized NodeType asserts `NodeIdField` classification and relies on Path 2 being in place).

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

**`InputField.NodeIdField` is the first input-side NodeId classifier.** Today the `@nodeId` directive is only read on *output* fields (`FieldBuilder.java:1549-1576`); on the input side, `TypeBuilder.java:563` uses `!hasDirective(@nodeId)` purely as an exclusion gate for the platform-id fallback — there is no existing input-side `@nodeId` classifier path. This variant covers both SDL shapes uniformly:

- `input FooLookup { id: ID! }` on an `@table` whose backing jOOQ table carries the metadata constants → synthesized-route `NodeIdField`.
- `input FooLookup { id: ID! @nodeId }` (declared) → same `NodeIdField` classification.

Both paths produce the same variant carrying `(typeId, keyColumns)`; the declared-`@nodeId` path is added as part of Step 4 (classifier + argument side land together).

### Argument side — `NodeIdArg`

`FieldBuilder.classifyArgument` (`FieldBuilder.java:624-end-of-method`) currently binds scalar args via `findColumn` — entry at `:659` ("Scalar arg: bind to column"), `findColumn` call at `:661`, `UnboundArg` fallback at `:663-666`. Add a pre-step at the scalar-binding entry (before `:659`): if `typeName == "ID"`, the target table is `NodeType`, and no `@nodeId` directive on the arg, emit `ArgumentRef.ScalarArg.NodeIdArg(name, typeName, nonNull, list, nodeTypeId, keyColumns, extraction, argCondition, isLookupKey)` instead of looking for a column. This is the unified replacement for the previously-proposed `PlatformIdArg`.

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

**Refactor scope.** Today `LookupMapping` is a concrete record; turning it into a sealed interface renames the record to `ColumnMapping` and migrates every existing consumer. Affected call sites, from one sweep: `FieldBuilder.java` (imports, `projectForLookup`, the Javadoc at `:585`, the `LookupField` comment at `:921`), `LookupValuesJoinEmitter.java` (top-of-file Javadoc + `columns()` accesses inside the existing arm), `GraphitronSchemaValidator.java:207-210`, `ArgumentRef.java:16` (Javadoc reference), `ChildField.java:154/:167/:306`, `QueryField.java:38`, `LookupField.java:17/:27`. Nested `LookupMapping.LookupColumn` stays on `ColumnMapping` unchanged — callers referencing `LookupMapping.LookupColumn` update to `ColumnMapping.LookupColumn`. Step 4 therefore lands three axes together (rename + sealed-interface introduction + new permit + emitter dispatch arm), not just the new permit. Budget accordingly.

**Why the variants are asymmetric.** `ColumnMapping` carries per-column `CallSiteExtraction` inside each `LookupColumn` because each lookup key is an independent argument (or input-type field) extracted separately. `NodeIdMapping` carries *one* `extraction` at the top level because a NodeId is a single argument (a base64 string or list of strings) that decodes into N target columns — the extraction happens once, then `NodeIdStrategy.hasIds` expands it across `keyColumns`. Pushing `extraction` into each `nodeKeyColumn` would lie about independence that doesn't exist.

**`CallSiteExtraction` for `NodeIdArg`.** Four argument shapes feed a `NodeIdArg`: scalar `ID` arg, list `[ID!]`, nested input-type field, nested list-of-inputs with an ID-bearing field. Scalar and list cases use the existing `Direct` permit; the two nested shapes land in a new permit added in Step 4:

```java
record NestedInputId(String argName, List<String> path, int listDepth) implements CallSiteExtraction {}
```

`argName` is the outer argument name; `path` walks named fields through successive input types to reach the ID-bearing leaf; `listDepth` counts list wrappers encountered along the path (`0` = flat scalar, `1` = outer list of inputs, `≥2` reserved for deeper shapes if they surface later). The `LookupValuesJoinEmitter` `NodeIdMapping` arm reads these to assemble a `Set<String>` before the `hasIds` call — iterating `listDepth` list levels plus `path.size()` field accesses with null-guards per legacy's `_nit_in != null ? _nit_in.getId() : null` pattern.

This permit is intentionally narrow and independent of argres Phase 3's `InputColumnBinding`. Phase 3 lands a richer input-to-column binding mechanism that `NestedInputId` is a strict subset of; once shipped, this permit collapses into `InputColumnBinding` as a mechanical follow-up. Keeping it small now lets Step 4 ship without waiting on Phase 3.

**Block-or-land trigger.** If argres Phase 3's `InputColumnBinding` is Approved or closer on the roadmap at Step 4 kickoff, Step 4 **blocks** on Phase 3 shipping first and consumes `InputColumnBinding` directly — the temporary permit is never added. If Phase 3 is still Unplanned/Draft when Step 4 is ready to start, add `NestedInputId` and track the collapse in Open points. The choice is made at Step 4 kickoff, not now; the point is that "land-then-delete" is only justified when Phase 3 genuinely can't be pulled forward. See Open points for the collapse tracking if the independent path is taken.

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
- `FieldBuilder` output-side platform-id fallback (the platform-id sub-block at `FieldBuilder.java:1604-1614` inside the `column.isEmpty()` block at `:1602-1621`), input-side platform-id fallback (`TypeBuilder.java:558-574`).
- `JooqCatalog.platformIdOutputMethodNames(String)`, `JooqCatalog.hasPlatformIdAccessors(String, String, String)`, `JooqCatalog.recordHasPlatformIdAccessors(Class, String, String)`, `JooqCatalog.sqlToAccessorSuffix(String)` (the last one is unused once the accessor-suffix derivation is gone).
- `TypeFetcherGenerator.IMPLEMENTED_LEAVES` entries for `PlatformIdField` variants + their switch arms.
- `ChildPlatformIdFieldValidationTest`, `PlatformIdFieldValidationTest` (the input-side validator; the repo has one file, not two), the `PlatformIdField` assertions in `PlatformIdPipelineTest`. The pipeline test stays but asserts `NodeIdField` outcomes; the synthetic fixture adds the `__ID_TYPE_ID` + `__ID_KEY_COLUMNS` constants on `Bar`.
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
| `type Foo @table(name: "bar") @node(typeId: "Bar") { id: ID! }` (metadata says `typeId="Bar"`, different `keyColumns`) | Classifier error — partial disagreement still errors; `keyColumns` mismatch reported with both sides |
| `type Foo @table(name: "bar") @node(keyColumns: ["ID_1", "ID_2"]) { id: ID! }` (metadata matches keyColumns but declares a different `typeId`) | Classifier error — typeId mismatch reported; keyColumns agreement is not a waiver |
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

- **Preferred path — hard cut-over.** The rewrite requires the new metadata. Tables that were platform-id under the old generator but lack the constants classify as `TableType` (no synthesized NodeId) — any schema using `id: ID!` on them lands on `UnclassifiedField` with a diagnostic pointing at "regenerate jOOQ classes with KjerneJooqGenerator ≥ X.Y". Clean, simple, discoverable. Step 5 replaces the current column-not-found diagnostic at `FieldBuilder.java:1617-1620` with the regenerate-jOOQ phrasing; on the input side, `TypeBuilder.java:571-573`'s `"no accessor methods (…) found on record class"` is swapped for the same message. The "regenerate jOOQ classes with KjerneJooqGenerator ≥ X.Y" literal is the canonical diagnostic — every platform-id migration failure produces this exact phrase so consumers can grep for it in build logs.
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
2. **`TypeBuilder` synthesizes `NodeType`** — `buildTableType` reads `nodeIdMetadata` **unconditionally** for every `@table` type and branches on the four `(has @node, has metadata)` combinations:
    - neither → `TableType` (unchanged).
    - `@node` only → existing path, typeId/keyColumns from SDL (unchanged).
    - metadata only → synthesize `NodeType` with metadata values.
    - both → compare per the collision rule (values match, or one side omits; error-on-disagree per the Classification section).

    The platform-id fallback at `FieldBuilder.java:1602-1621` is *not* touched in this step; it remains the path for bare `id: ID!` on metadata-carrying tables until Step 3 adds the Path-2 NodeType short-circuit. **`NodeType` audit (gating):** grep every `instanceof NodeType` / `switch (... NodeType ...)` site in the rewrite and verify generator behaviour is unchanged for metadata-carrying tables without `id: ID!` fields — the unconditional synthesis rule must not regress pure-inline nested types. If any site fires undesirably, narrow synthesis to types declaring at least one `id: ID!` and re-run the audit. **Test migration:** `PlatformIdPipelineTest` gains a type-level assertion per case — the test reads `processedSchema.getType("Foo")` and asserts `instanceof NodeType` with `typeId="Bar"` and `nodeKeyColumns` matching the fixture constants. Field-level assertions for bare `id: ID!` stay on `PlatformIdField` in this step (Path 2 detection lands in Step 3, and the platform-id fallback at `FieldBuilder.java:1602-1621` is still reached for metadata-carrying tables until Step 3 short-circuits it). Field-level assertions for `id: ID! @nodeId` flip from the current `UnclassifiedField` ("@nodeId requires the containing type to have @node") to `NodeIdField` in this step, because the synthesized `NodeType` now satisfies the `NodeType` guard at `FieldBuilder.java:1571`. Step 3 then flips bare-`id: ID!` field assertions from `PlatformIdField` to `NodeIdField` and also asserts the emitted body via `TypeClassGenerator`. `ChildPlatformIdFieldValidationTest` / `PlatformIdFieldValidationTest` stay green — they assert structure of the stub path, still reachable from any fixture without metadata. Collision-rule test cases land here: full disagreement (typeId and keyColumns both differ), partial disagreement (typeId matches, keyColumns differ, and vice versa), and the omitted-args-delegate cases (one side supplies what the other omits).
3. **`ChildField.NodeIdField` detection + emission** — add the Path-2 detection at `FieldBuilder`, placed *inside* the existing `column.isEmpty()` block (`FieldBuilder.java:1602-1621`), **before** the platform-id `platformIdMethods.contains(getterName)` check at `:1611`, so metadata-carrying tables short-circuit into `NodeIdField` instead of `PlatformIdField`. Trigger predicate per the Output-side section's six clauses. Move `ChildField.NodeIdField` from `NOT_IMPLEMENTED_REASONS` (line 210-211) into **`PROJECTED_LEAVES`** (line 140) — consistent with `TableField`'s placement after G5, since projection lives in `TypeClassGenerator.$fields`, not as a per-field fetcher method. The fetcher switch arm at `TypeFetcherGenerator.java:311` changes from `stub(f)` to `{ }` (empty arm, as `TableField` has today at line 314). Implement projection in `TypeClassGenerator.$fields` via `NodeIdStrategy.createId`. Reuses existing helper. **Test migration:** `PlatformIdPipelineTest`'s bare `id: ID!` cases flip from `PlatformIdField` to `NodeIdField` (classification + emission). New execution test in `graphitron-rewrite-test-spec` for `id` field on a metadata-carrying table (gated on Step 1's test-spec-constants mechanism landing; see Compile-tier coverage).
4. **`NodeIdArg` + `LookupMapping` sum type + emitter branches** — classifier produces `NodeIdArg` for scalar ID args targeting NodeType; declared-`@nodeId`-on-input folds in here; `projectForLookup` produces `NodeIdMapping`; `LookupValuesJoinEmitter` dispatches on variant; non-lookup filter path handles the same. Pipeline-test coverage for both lookup and non-lookup shapes. **Test migration:** the `InputField.PlatformIdField` assertions in `PlatformIdFieldValidationTest` flip to `InputField.NodeIdField`. New execution test for a lookup query using platform-id keys.
5. **Delete `PlatformIdField` sum-type variants + supporting catalog methods** — the variant records, the `FieldBuilder`/`TypeBuilder` fallbacks, `hasPlatformIdAccessors`, `platformIdOutputMethodNames`, `sqlToAccessorSuffix` (if no other caller), `IMPLEMENTED_LEAVES` entries, `ChildPlatformIdFieldValidationTest` / `PlatformIdFieldValidationTest`. Partition invariants in the variant-coverage meta-test resolve automatically since the record no longer exists. **Test migration:** the two variant-specific test classes are deleted; no replacements needed — pipeline coverage for `NodeIdField` outcomes shipped in steps 2-4.

Mutation binding (previous Item 3) remains gated on argres Phase 3 and lands via that plan — but as a `NodeIdField` input variant, not `PlatformIdField`.

---

## Resolved points

- **`__ID_TYPE_ID` naming rule.** KjerneJooqGenerator derives `__ID_TYPE_ID` from the same source today's `@node(typeId: "...")` literals use — the GraphQL type name associated with the table in Sikt's schema conventions. The collision rule above (error on disagree) only works if this holds; if the generator picks a different source (e.g. the raw table-class stem `"CustomerRecord"` while SDL uses `"Customer"`), every hybrid schema fails classification. Pin the rule when the generator change is drafted; the rewrite-side consumer treats `__ID_TYPE_ID` as opaque.
- **Constants as `Field<?>[]` vs. `List<Field<?>>`.** Array is what jOOQ `hasIds` signatures take; list is easier to build. KjerneJooqGenerator emits `Field<?>[]` (the consumer shape); the rewrite converts to `List<ColumnRef>` at the `JooqCatalog` boundary for model storage.

## Compile-tier coverage

`graphitron-rewrite-test-spec` compiles against a real jOOQ catalog — the "compile against real jOOQ is a test tier" principle applies. The synthetic fixture (`platformidfixture`) covers pipeline-level classification, but emitted `nodeIdStrategy.createId(...)` / `hasIds(...)` / `setId(...)` code should also type-check against a real jOOQ table class. Step 1 adds the `__ID_TYPE_ID` + `__ID_KEY_COLUMNS` constants to at least one test-spec jOOQ table — the Sakila candidate is `store` (composite business key, already present in fixtures) or a synthetic table if no Sakila table matches. Execution tests for `NodeIdField` projection and `NodeIdArg` lookup land as part of Step 3 / Step 4 respectively.

**How the constants land on the test-spec table.** KjerneJooqGenerator X.Y does not ship with this plan; the test-spec cannot regenerate and get the constants for free. The production probe reads `<TableClass>.__ID_TYPE_ID` / `__ID_KEY_COLUMNS` by reflecting on the table class itself — so the test-spec's mechanism must also produce constants *on* the generated class, not a sibling file. A `*Ext.java` sibling (earlier-draft suggestion) doesn't deliver the same addressing shape and is rejected.

**Primary mechanism: copy-and-edit a generated table class.**

1. Pick a Sakila fixture table whose shape fits the platform-id test (ideally composite-business-key, terminal-ish in the FK graph to minimise blast radius from step 2).
2. Run `mvn generate-sources` once; copy the produced `target/generated-sources/jooq/.../tables/<Table>.java` into `graphitron-rewrite-test-fixtures/src/main/java/.../tables/<Table>.java` (same package).
3. Add `<excludes><Table></excludes>` to the `<database>` block in `graphitron-rewrite-test-fixtures/pom.xml` so jOOQ doesn't regenerate and trigger a duplicate-class compile error.
4. Hand-edit the copy: add `public static final String __ID_TYPE_ID = "<TypeName>";` and `public static final Field<?>[] __ID_KEY_COLUMNS = { <COL1>, <COL2>, … };`.
5. Post-X.Y: remove the `<excludes>`, delete the hand-edited file, jOOQ regenerates with constants emitted natively.

**Caveat to resolve at implementation time.** Excluding a table from jOOQ codegen can affect `Keys.java` / `Tables.java` references — incoming foreign keys from other tables may drop if jOOQ doesn't emit FK entries against externally-defined classes. Mitigations: (a) prefer a table with no incoming FKs (leaf in the FK graph); (b) if the chosen table has incoming FKs, also copy `Keys.java` and trim to the needed entries, or verify jOOQ's behaviour on the current version emits FKs against excluded classes. Decide at Step 1 kickoff once a concrete table is chosen.

**Fallback if the copy-and-edit scope grows beyond ~2 files.** Do-nothing in the pre-X.Y window — rely on the synthetic `platformidfixture` for pipeline-level coverage and defer real test-spec compile/execution coverage to post-X.Y. Flag this choice in Step 1's commit message so Step 3's execution-test claim tracks the actual state.

(An earlier iteration proposed a custom `JavaGenerator` subclass emitting constants via `generateTableClassFooter`; rejected as more invasive than copy-and-edit and leaving a subclass around the fixture for cleanup post-X.Y.)

## Open points

- **Consumers with mixed schemas** — some types `@node`, some platform-id. Both synthesize or declare `NodeType`; the classifier should not care. Pipeline-test cases per the tables above cover this.
- **Federation entity lookups.** Legacy's `FetchEntityImplementationDBMethodGenerator` dispatches on `processedSchema.isNodeIdField(field)`. The rewrite's equivalent (when it lands) will match on `ChildField.NodeIdField` — no platform-id branch needed.
- **`JooqCatalog.nodeIdMetadata` memoisation.** Many GraphQL types can reference the same jOOQ table class (base type + paginated-wrapper variants + input-shape siblings). Cache the metadata lookup per table class inside the `JooqCatalog` instance — that instance already lives for the duration of the build, and every lookup is pure reflection. Sub-microsecond per-hit win, but closes a foot-gun if the classifier ever iterates types inside a hot loop.
- **`NestedInputId` → `InputColumnBinding` collapse.** Once argres Phase 3 lands `InputColumnBinding`, the `NestedInputId` permit added in Step 4 collapses into it — same path-walking semantics, wider binding type. Track as a mechanical cleanup commit after Phase 3; not a blocker for this plan, and the permit is narrow enough that the collapse is local to two files (`CallSiteExtraction.java` + `LookupValuesJoinEmitter`).
- **`__ID_KEY_COLUMNS` ordering stability guarantee.** The collision rule's order-sensitive equality check only survives schema re-gens if KjerneJooqGenerator commits to a stable, deterministic column order (e.g. declared-order in the composite-key DDL, then primary-key order, then alphabetical as a tiebreak). A silent reorder between generator releases would re-encode new IDs in a different order than decoded IDs produced pre-upgrade, and `hasIds` would fail to match any of them — silently wrong results across the cut-over. Pin the ordering rule in the KjerneJooqGenerator design doc when the X.Y constants are drafted; the rewrite treats the order as opaque but stable. Release-notes for X.Y+N must call out any ordering change as breaking.
- **Path-2 `!@field` gate — behavioural delta.** Today's platform-id fallback at `FieldBuilder.java:1604-1614` does *not* exclude `@field` — an `id: ID! @field(name: "customer_id")` resolves `columnName` from the directive, misses in `resolveColumn`, and falls through to the platform-id check with `getterName="getCustomerId"`. The Path-2 predicate adds `!fieldDef.hasAppliedDirective(DIR_FIELD)` — so after Step 5 deletes the fallback, any schema using `id: ID! @field(...)` on a platform-id table stops classifying as `NodeIdField` and lands on `UnclassifiedField`. This is a second behavioural delta beyond "no longer requires accessor methods to exist" (the one the Output-side section calls out). Either intentional narrowing (NodeId fields don't meaningfully need `@field` since the columns come from metadata) or a silent migration break. Pin at Step 3 kickoff — if intentional, surface in Migration as a third diagnostic case; if not, drop the `!@field` clause and let `@field(name:)` remain a valid column-rename knob on synthesized NodeId fields.
- **`@node` with `null` typeId / empty keyColumns under no-metadata.** Pre-existing behaviour today: `TypeBuilder.java:254` stores `typeId=null` when `@node` is declared without `typeId:`, and `TypeBuilder.java:255-270` stores an empty `keyColumns` list when `@node` is declared without `keyColumns:` (the directive docs say "we use the primary key" but the rewrite's `TypeBuilder` does no PK substitution — only the empty list is recorded). The pivot preserves both on tables with no metadata (no collision, no synthesis). Downstream consumers that dereference `NodeType.typeId()` or pass `NodeType.nodeKeyColumns()` to `NodeIdStrategy.createId(...)` without empty/null guards will NPE or emit `"typeId:"` (bare prefix) — not a regression introduced by this plan, but the `NodeType` audit at Step 2 should note any sites that would blow up on null typeId or empty keyColumns, and either tolerate them or file a follow-up to implement the documented PK-fallback.

---

## History

- **2026-04-19 (seventh Draft iteration, reviewer pass)** — line-number re-verification against the codebase. Six classes of drift accumulated across the 3rd/5th iterations:
  - **Output-side directive-driven path.** Classification section said `FieldBuilder.java:1541-1567` emits `NodeIdField` for bare `@nodeId`. That range covers the `@nodeId(typeName:)` → `NodeIdReferenceField` path (`:1550-1569`), not the bare-`@nodeId` → `NodeIdField` path (`:1570-1576`, creation at `:1575`). Rewrote Path 1's citation to `:1549-1576` and called out both sub-paths with their line ranges.
  - **Output-side platform-id fallback.** Cited as `FieldBuilder.java:1593-1612` in four places (Classification Path 2, Step 2 twice, Step 5 Deletions). Actual location: `column.isEmpty()` block at `:1602-1621`, platform-id detection `:1604-1614`, creation `:1612`. Corrected in all four places.
  - **Step 3 placement.** Said "inside `:1594-1612`, before the `platformIdMethods.contains(getterName)` check at `:1603`". `:1603` is `String tableSqlName = tableType.table().tableName();`, not the contains-check; that check is at `:1611`. Corrected to "inside `:1602-1621`, before the check at `:1611`".
  - **Step 2 NodeType guard.** Said "synthesized `NodeType` now satisfies the `NodeType` guard at `FieldBuilder.java:1563`". `:1563` is `TableRef parentTable = tableType.table();` inside the `@nodeId(typeName:)` path. The actual bare-`@nodeId` NodeType guard (the one Step 2's claim depends on) is at `:1571`. Corrected.
  - **Migration diagnostic swap.** Said Step 5 "replaces the current column-not-found text at `FieldBuilder.java:1609-1612`". `:1609-1612` is the platform-id detection block (creation at `:1612`); the column-not-found diagnostic literal is at `:1617-1620`. Corrected.
  - **Scalar-arg binding entry.** Said `classifyArgument` binds scalar args via `findColumn` at `:651-660`. `:651-660` is inside the `if (ctx.types.containsKey(typeName))` block handling input-type args; the scalar-binding entry is at `:659` ("Scalar arg: bind to column"), `findColumn` at `:661`. Corrected the pre-step insertion point.
  Also corrected the **`TypeBuilder.java:253-255` claim** that today's rewrite "treats an omitted `keyColumns:` as 'use the PK at codegen time'". The directive docs at `directives.graphqls:243-244` state the PK-fallback intent, but the rewrite's `TypeBuilder` stores the omission as an empty `keyColumns` list with no substitution. Rewrote the paragraph to reflect actual behaviour and added the empty-`keyColumns` foot-gun to the Open points audit item (alongside the pre-existing null-`typeId` concern). Added one new Open point for a second behavioural delta in Path 2's trigger predicate: `!@field` is a new narrowing that today's fallback doesn't have, so `id: ID! @field(name:)` on a platform-id table would regress under Step 5.
- **2026-04-19 (sixth Draft iteration)** — simplified the Step 1 compile-tier mechanism: copy a generated table class into `src/main/java/...`, add `<excludes>` to the jOOQ plugin config, hand-edit the copy to add the two constants. Reverts post-X.Y by dropping the `<excludes>` and the hand-edited file. Replaces the fifth iteration's `JavaGenerator` subclass proposal (now rejected — more invasive than copy-and-edit, leaves a subclass to remove post-X.Y). Single caveat flagged: excluding a table may drop incoming-FK entries in `Keys.java`; mitigations (leaf-table choice or `Keys.java` copy) decided at Step 1 kickoff once a concrete table is picked. Do-nothing fallback preserved for when the copy scope grows beyond ~2 files.
- **2026-04-19 (fifth Draft iteration, reviewer pass)** — three corrections to Step 1 / Step 3 mechanics:
  - **Compile-tier mechanism reworked.** Fourth-iteration text offered "sibling `*Ext.java`" as a fallback when `pom.xml` lacks a jOOQ post-hook. Verified: `graphitron-rewrite-test-fixtures/pom.xml:45-77` configures stock `JavaGenerator` with no `<generator><name>` override, and a sibling file cannot deliver `<TableClass>.__ID_TYPE_ID` addressing — the production probe reflects on the table class itself, so the test-spec must too. Rewrote the section: primary path is a custom `JavaGenerator` subclass emitting `generateTableClassFooter` constants (mirrors what KjerneJooqGenerator will do, reverts cleanly); secondary path is a test-fixture fallback probe in `JooqCatalog.nodeIdMetadata`; explicit do-nothing fallback if the Java-generator wiring exceeds one commit — synthetic fixture covers pipeline tier, execution-tier coverage deferred to post-X.Y and called out in Step 1's commit message.
  - **Step 3 Path-2 placement pinned.** Previously said "runs before the platform-id fallback" without specifying whether "before" meant before the `column.isEmpty()` block entry or inside the block before the `platformIdMethods.contains(getterName)` check. Named the exact placement: inside `FieldBuilder.java:1594-1612`, before the platform-id check at `:1603`. Removes ambiguity for the implementer.
  - **`NodeIdField` partition destination named.** Step 3 previously said "Move `ChildField.NodeIdField` out of `NOT_IMPLEMENTED_REASONS`" without naming the destination set. Named `PROJECTED_LEAVES` (consistent with `TableField` after G5, since projection lives in `$fields` and no fetcher method is emitted). Added explicit line references to `TypeFetcherGenerator.java:210-211` (source), `:140` (destination), `:311` (switch-arm change to `{ }`).
  Also pinned Step 3's new execution test as gated on Step 1's mechanism choice — if deferred, the claim needs to shift to Step 4's roadmap.
- **2026-04-19 (fourth Draft iteration, reviewer pass)** — second parallel reviewer pass layered on top of the third iteration's structural revisions. Six additions:
  - **Test-file name drift.** `InputPlatformIdFieldValidationTest` does not exist in the repo; the input-side validator lives at `graphitron-rewrite/src/test/java/no/sikt/graphitron/rewrite/validation/PlatformIdFieldValidationTest.java`. Fixed four references (Deletions, Step 2 / Step 4 / Step 5 test-migration notes) that survived the third iteration's revision.
  - **Step 2 scope.** Step 2's prose said "classifier reads `nodeIdMetadata` when `@node` absent + `@table` present" — but the collision rule in the Classification section requires the metadata read ALSO when `@node` is present (to validate agreement). Added an explicit four-branch enumeration of `(has @node, has metadata)` combinations at the top of the step.
  - **Malformed-metadata diagnostic.** `JooqCatalog.nodeIdMetadata` spec said "Returns empty when either constant is absent" — said nothing about null/empty typeId or unresolvable `Field<?>` entries in `__ID_KEY_COLUMNS`. Added a "Malformed-metadata handling" paragraph requiring an explicit classifier-boundary diagnostic (`UnclassifiedType` with a "metadata is malformed" reason) rather than silent `TableType` fallback.
  - **Pipeline-test table gap.** Collision-rule coverage had only the full-disagreement and matching rows. Added partial-disagreement cases — `@node(typeId: "Bar")` with metadata-keyColumns mismatch, and `@node(keyColumns: [...])` with metadata-typeId mismatch — each asserts that agreement on one axis is not a waiver for disagreement on the other.
  - **Open points: ordering stability + null typeId.** Added the `__ID_KEY_COLUMNS` ordering-stability requirement as a cross-release concern KjerneJooqGenerator must commit to, and flagged pre-existing `NodeType.typeId()=null` under no-metadata as an audit item (not a regression) for Step 2.
  - **Step 1 test-spec mechanics.** The Compile-tier coverage section called for adding constants to a test-spec jOOQ table but didn't name how — since X.Y of KjerneJooqGenerator isn't shipping with this plan. Added the "How the constants land on the test-spec table" paragraph with two mechanisms (jOOQ post-hook vs sibling `*Ext.java`) and a revert path post-X.Y.
- **2026-04-19 (third Draft iteration, review pass)** — reviewer pass on the pivot design.
  - **Output-side detection gap fixed.** The previous draft's "no new detection logic" claim contradicted the Step 2 pipeline-test expectation that bare `id: ID!` (no `@nodeId`) on a synthesized `NodeType` classifies as `NodeIdField`. Today's `@nodeId`-directive path only fires when the directive is present; bare-ID fields fall through to the platform-id fallback. The Output-side section now documents two detection paths (directive-driven, existing; synthesized, new) with an exact six-clause trigger predicate for the synthesized path and explicit placement of that predicate in Step 3 (before the fallback, so metadata-carrying tables short-circuit).
  - **Step 2/3 boundary re-drawn.** Step 2 now lands type-level synthesis only; Step 3 adds field-level Path-2 detection + emission together; Step 5 deletes the fallback. `PlatformIdPipelineTest` migration is staged across Step 2 (add type-level `instanceof NodeType` assertion; `id: ID! @nodeId` flips to `NodeIdField` courtesy the existing directive path) and Step 3 (bare `id: ID!` flips to `NodeIdField` via Path 2).
  - **`LookupMapping` refactor scope.** Step 4 was turning a record into a sealed interface without naming the ~15 migrating call sites (`FieldBuilder`, `LookupValuesJoinEmitter`, `GraphitronSchemaValidator`, `ChildField`, `QueryField`, `LookupField`, `ArgumentRef` Javadoc). Section now enumerates the sites and labels the step as three axes (rename + sealed-interface + new permit + dispatch).
  - **`UnclassifiedField` diagnostic wording.** Migration promised "regenerate jOOQ classes with KjerneJooqGenerator ≥ X.Y" but today's `FieldBuilder.java:1609-1612` and `TypeBuilder.java:571-574` produce column/accessor-not-found text. Step 5 (fallback deletion) now explicitly swaps both messages to the canonical phrase so consumers can grep for it.
  - **`NestedInputId` block-or-land trigger.** Previous draft committed to landing `NestedInputId` and collapsing it post-Phase-3. Decision now deferred to Step 4 kickoff — if argres Phase 3's `InputColumnBinding` is Approved or closer, Step 4 blocks on it and never adds the temporary permit; otherwise the land-then-collapse path applies.
  - **Line-number fixes.** `TypeBuilder.buildTableBoundType (:246-270)` → `buildTableType (:241-271)`. `FieldBuilder.classifyArgument (:649)` → method range `:616-686` with scalar-binding entry at `:651-660`. Output-side fallback cited as `:1593-1612` everywhere instead of "around 1592."
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
