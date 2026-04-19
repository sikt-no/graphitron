# Platform-id as synthesized NodeId

> **Status:** Approved
>
> Pivot: KjerneJooqGenerator will be extended to emit `__NODE_TYPE_ID` + `__NODE_KEY_COLUMNS` constants on every platform-id table class. The rewrite reads them, synthesizes a `NodeType` classification, and routes all downstream work (projection, filter, mutation binding) through the same `@nodeId` paths. `PlatformIdField` sum-type variants are deleted. Supersedes the previous four-item plan (`getId`/`hasId`/`hasIds`-driven classification and emission).

## Why the pivot

The earlier plan classified platform-id as its own sum-type variant (`InputField.PlatformIdField`, `ChildField.PlatformIdField`), reflected on per-table `getId()`/`setId()`/`hasId()`/`hasIds()` methods emitted by `KjerneJooqGenerator`, and would have introduced a parallel filter-emission path (`PlatformIdArg`, `PlatformIdMapping`, platform-id arm in `LookupValuesJoinEmitter`). Two things make that wrong:

- **It invents a parallel type system for something already classified.** Platform-id tables are structurally composite-key node types. The rewrite already has `NodeType` / `ChildField.NodeIdField` (plus the planned input-side and emitter work for `@nodeId`) carrying exactly the metadata a composite key needs: `typeId` + `nodeKeyColumns`. Reflecting on `getId()` to re-derive the same information via method naming + return-type matching is the model-metadata-over-parallel-type-systems anti-pattern, applied twice.
- **Sikt owns KjerneJooqGenerator.** The generator can expose the underlying metadata directly. With `__NODE_TYPE_ID` + `__NODE_KEY_COLUMNS` constants, every place the rewrite would have called `table.hasIds(set)` instead calls `NodeIdStrategy.hasIds(typeId, set, keyColumns)` — the same helper `@nodeId` fields use. One code path, one set of tests.

Trade-off: a KjerneJooqGenerator release is required before the rewrite can depend on the metadata. Sikt controls the release cadence; we coordinate rather than maintain a reflection-based fallback forever. See **Migration** below.

---

## KjerneJooqGenerator contract

On every table class where it currently emits `getId()` / `getPersonId()` / `hasId` / `hasIds`, KjerneJooqGenerator additionally emits two public constants:

```java
public static final String __NODE_TYPE_ID = "Customer";
public static final Field<?>[] __NODE_KEY_COLUMNS = { STORE_ID, CUSTOMER_ID };
```

- **`__NODE_TYPE_ID`** — the value used today when encoding the composite ID's base64 prefix. Stable across regens; matches whatever a consumer would write as `@node(typeId: "Customer")` in SDL for the same table.
- **`__NODE_KEY_COLUMNS`** — the underlying `Field<?>` references in positional order. `NodeIdStrategy.unpackIdValues(typeId, base64Id, fields)` pairs the CSV-decoded values positionally with this array, and `NodeIdStrategy.createId(typeId, keyFields)` encodes in the same order. Any reordering between `createId` (encode) and `hasIds` (decode) produces silently-wrong composite keys, so this order is load-bearing.

Static finals, not instance fields — accessible as `Customer.__NODE_TYPE_ID` / `Customer.__NODE_KEY_COLUMNS` without needing an instance. Reflection lookup is trivial.

The existing method emissions (`getId()`, `hasId`, `hasIds`) can stay — harmless for non-graphitron callers. The rewrite stops detecting and calling them.

---

## Classification

### Synthesize `NodeType` from metadata

`TypeBuilder.buildTableType` (`TypeBuilder.java:241-317`) today branches on whether the GraphQL type has `@node`:

- Has `@node` → `NodeType(typeId, nodeKeyColumns)`
- Otherwise → `TableType` (no node identity)

With the pivot, a third branch: if the GraphQL type has `@table` but no `@node`, and the backing jOOQ table exposes the metadata constants, synthesize `NodeType` using `__NODE_TYPE_ID` and `__NODE_KEY_COLUMNS`.

**Synthesis is unconditional when metadata is present.** A `@table` type with metadata becomes `NodeType` regardless of whether it declares an `id: ID!` field — the classifier is type-uniform. Behaviour changes only for paths that branch on `NodeType` vs `TableType` at the type level; today those paths (federation entity fetchers, node-interface implementations) are intended to fire for any node-identified table. Step 2 (Scheduling) includes an audit of every `instanceof NodeType` / `switch (... NodeType ...)` site to verify no metadata-carrying table without an `id` field regresses. If the audit surfaces a real divergence, fall back to narrowing synthesis to types declaring at least one `id: ID!`.

**Collision rule — when both `@node` and metadata are present:**

- **Values match exactly** (`typeId` equal, `keyColumns` equal in order and arity) → accept; treat as `NodeType` with those values. Declaring `@node` redundantly is harmless.
- **Values disagree** → classifier error (`UnclassifiedType`), with both sides in the diagnostic (`"@node(typeId: 'Foo') disagrees with KjerneJooqGenerator metadata (typeId: 'Bar')"`). "Explicit wins" is rejected — silent override of the generator's metadata by a typo-prone SDL literal is exactly the drift this pivot eliminates. This rule applies symmetrically to `typeId` and `keyColumns`: encoding base64 IDs whose decode order doesn't match SDL's declared composite would be a correctness bug, not a drift to paper over.
- **`@node` declared without `typeId:` or `keyColumns:`** → no claim about the missing argument; metadata's value is taken verbatim. The presence of `@node` asserts node-identity; the omitted argument does not disagree with anything. The `@node` directive documentation at `graphitron-common/src/main/resources/directives.graphqls:243-244` states "if you do not specify keyColumns, we use the primary key in the database and the order set there" — today's rewrite at `TypeBuilder.java:241-317` stores the omission as an empty `keyColumns` list on `NodeType` (no PK substitution lives in `TypeBuilder`; the documented PK-fallback is a legacy-generator behaviour). Under the pivot, the metadata's list is used verbatim (and the two agree on well-formed schemas, since the PK and the composite-key columns coincide on platform-id tables).

The pipeline-test table below encodes the rule.

`JooqCatalog` grows:

```java
public Optional<NodeIdMetadata> nodeIdMetadata(String tableSqlName);

public record NodeIdMetadata(String typeId, List<ColumnRef> keyColumns) {}
```

Implementation: reflect on the table class for `public static final String __NODE_TYPE_ID` and `public static final Field<?>[] __NODE_KEY_COLUMNS`; resolve each `Field<?>` to its `ColumnRef` via the existing column-resolution machinery. Returns empty when either constant is absent.

**Malformed-metadata handling.** Both constants must pass sanity checks for `NodeIdMetadata` to be returned: `__NODE_TYPE_ID` non-null and non-empty; `__NODE_KEY_COLUMNS` non-null, non-empty, and every `Field<?>` entry resolvable to a `ColumnRef` on the same table. Any failure makes the probe return empty and pushes a build-time diagnostic keyed on the table SQL name — surfaced at the classifier boundary as an `UnclassifiedType` ("KjerneJooqGenerator metadata on table 'bar' is malformed: …") rather than silently falling back to `TableType`. Silent fallback on malformed constants would reintroduce the drift the pivot is trying to eliminate.

### Output-side `NodeIdField`

Already exists: `ChildField.NodeIdField(parentTypeName, name, location, nodeTypeId, nodeKeyColumns)`. No schema changes.

Two detection paths feed this variant after the pivot:

1. **Directive-driven (existing).** `FieldBuilder.java:1559-1586` handles the `@nodeId` directive: the `@nodeId(typeName:)` variant at `:1560-1579` produces `NodeIdReferenceField` (unchanged); the bare-`@nodeId` variant at `:1580-1586` produces `NodeIdField` (guarded by `tableType instanceof NodeType` at `:1581`, creation at `:1585`). The bare-`@nodeId` path works unchanged for both declared and synthesized `NodeType` parents — no code change.
2. **Synthesized (new).** On a `NodeType` parent (declared or synthesized), a scalar non-list `ID` field without `@nodeId`, `@reference`, or `@field` that does not match a real column via `resolveColumn` classifies as `NodeIdField(parentTypeName, name, location, nodeType.typeId(), nodeType.nodeKeyColumns())`. This path replaces the deleted platform-id fallback inside the `column.isEmpty()` block at `FieldBuilder.java:1612-1631` (platform-id detection at `:1614-1624`, creation at `:1622`). **Trigger predicate (exact):** `tableType instanceof NodeType nt` AND `"ID".equals(typeName)` AND `!isList` AND `!fieldDef.hasAppliedDirective(DIR_NODE_ID)` AND `!fieldDef.hasAppliedDirective(DIR_REFERENCE)` AND `!fieldDef.hasAppliedDirective(DIR_FIELD)` AND `resolveColumn(columnName, tableType).isEmpty()`. All six clauses match today's platform-id fallback's conditions plus the NodeType narrowing; the only behavioural delta is "no longer requires platform-id accessor methods to exist."

The platform-id fallback at `FieldBuilder.java:1612-1631` (detection `:1614-1624`, creation `:1622`) is deleted as part of Step 5; the new Path 2 detection lands in Step 3 alongside the emission code (Step 2's pipeline test for bare `id: ID!` on a synthesized NodeType asserts `NodeIdField` classification and relies on Path 2 being in place).

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

`TypeBuilder.resolveInputField` (`TypeBuilder.java:632-649` is where `PlatformIdField` currently classifies) becomes: scalar `ID`, not a list; if the backing table resolves to a synthesized-or-declared `NodeType`, classify as `InputField.NodeIdField` carrying the same `typeId` + `keyColumns` pair.

**`InputField.NodeIdField` is the first input-side NodeId classifier.** Today the `@nodeId` directive is only read on *output* fields (`FieldBuilder.java:1559-1586`); on the input side, `TypeBuilder.java:638` uses `!hasDirective(@nodeId)` purely as an exclusion gate for the platform-id fallback — there is no existing input-side `@nodeId` classifier path. This variant covers both SDL shapes uniformly:

- `input FooLookup { id: ID! }` on an `@table` whose backing jOOQ table carries the metadata constants → synthesized-route `NodeIdField`.
- `input FooLookup { id: ID! @nodeId }` (declared) → same `NodeIdField` classification.

Both paths produce the same variant carrying `(typeId, keyColumns)`; the declared-`@nodeId` path is added as part of Step 4 (classifier + argument side land together).

### Argument side — `NodeIdArg`

`FieldBuilder.classifyArgument` (`FieldBuilder.java:634-end-of-method`) currently binds scalar args via `findColumn` — entry at `:669` ("Scalar arg: bind to column"), `findColumn` call at `:671`, `UnboundArg` fallback at `:673-676`. Add a pre-step at the scalar-binding entry (before `:669`): if `typeName == "ID"`, the target table is `NodeType`, and no `@nodeId` directive on the arg, emit `ArgumentRef.ScalarArg.NodeIdArg(name, typeName, nonNull, list, nodeTypeId, keyColumns, extraction, argCondition, isLookupKey)` instead of looking for a column. This is the unified replacement for the previously-proposed `PlatformIdArg`.

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
- `FieldBuilder` output-side platform-id fallback (the platform-id sub-block at `FieldBuilder.java:1614-1624` inside the `column.isEmpty()` block at `:1612-1631`), input-side platform-id fallback (`TypeBuilder.java:632-649`).
- `JooqCatalog.platformIdOutputMethodNames(String)`, `JooqCatalog.hasPlatformIdAccessors(String, String, String)`, `JooqCatalog.recordHasPlatformIdAccessors(Class, String, String)`, `JooqCatalog.sqlToAccessorSuffix(String)` (the last one is unused once the accessor-suffix derivation is gone).
- `TypeFetcherGenerator.IMPLEMENTED_LEAVES` entries for `PlatformIdField` variants + their switch arms.
- `ChildPlatformIdFieldValidationTest`, `PlatformIdFieldValidationTest` (the input-side validator; the repo has one file, not two), the `PlatformIdField` assertions in `PlatformIdPipelineTest`. The pipeline test stays but asserts `NodeIdField` outcomes; the synthetic fixture adds the `__NODE_TYPE_ID` + `__NODE_KEY_COLUMNS` constants on `Bar`.
- The variant-coverage Phase-1 partition entries for `PlatformIdField` — they disappear along with the record.

---

## Synthetic fixture update

`no.sikt.graphitron.rewrite.platformidfixture.tables.Bar` grows:

```java
public static final String __NODE_TYPE_ID = "Bar";
public static final Field<?>[] __NODE_KEY_COLUMNS = { /* a real column or two */ };
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

- **Preferred path — hard cut-over.** The rewrite requires the new metadata. Tables that were platform-id under the old generator but lack the constants classify as `TableType` (no synthesized NodeId) — any schema using `id: ID!` on them lands on `UnclassifiedField` with a diagnostic pointing at "regenerate jOOQ classes with KjerneJooqGenerator ≥ X.Y". Clean, simple, discoverable. Step 5 replaces the current column-not-found diagnostic at `FieldBuilder.java:1627-1630` with the regenerate-jOOQ phrasing; on the input side, `TypeBuilder.java:646-648`'s `"no accessor methods (…) found on record class"` is swapped for the same message. The "regenerate jOOQ classes with KjerneJooqGenerator ≥ X.Y" literal is the canonical diagnostic — every platform-id migration failure produces this exact phrase so consumers can grep for it in build logs.
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

1. **`JooqCatalog.nodeIdMetadata` probe** — *shipped*. Reflection probe landed on the synthetic fixture (constants added to `Bar.java`). Malformed-metadata branches covered by a package-private `JooqCatalog.validateNodeIdMetadata` helper under `JooqCatalogNodeIdMetadataTest`. Compile-tier coverage (real test-spec jOOQ table with hand-edited constants) took the do-nothing fallback — deferred to post-X.Y per the Compile-tier coverage section; Step 3's execution-test claim has to track this. See **Learnings from Step 1** below.
2. **`TypeBuilder` synthesizes `NodeType`** — *shipped*. `buildTableType` reads metadata unconditionally; malformed metadata surfaces as `UnclassifiedType` via the memoized `nodeIdMetadataDiagnostic` sibling. `PlatformIdPipelineTest` gained 8 type-level cases covering the four `(@node, metadata)` branches plus collision rules. `@nodeId` on a metadata-carrying table flips from `UnclassifiedField` → `NodeIdField` via the existing directive path. See **Learnings from Step 2** below.
3. **`ChildField.NodeIdField` detection + emission** — *shipped* (landed as `76ce48d`; refactored after review as `c54c107`). Path-2 detection landed in `FieldBuilder` inside the `column.isEmpty()` block before the platform-id check; six-clause predicate matches the Output-side section (intentional `!@field` narrowing). `NodeIdField` moved from `NOT_IMPLEMENTED_REASONS` into `PROJECTED_LEAVES`; fetcher switch arm emptied. `TypeClassGenerator.$fields` projects the raw key columns (`fields.add(table.COL_N)` per column); `TypeFetcherGenerator` wires a `DataFetcher` lambda that reads the keys off the parent `Record` and calls a generated `NodeIdEncoder.encode(typeId, …)` utility (emitted alongside `ColumnFetcher` in `outputPackage().rewrite`). Wire format matches `NodeIdStrategy` (URL-safe base64 no-pad, `,`→`%2C`, `"typeId:v1,v2,…"`) so IDs round-trip between generators. `PlatformIdPipelineTest`'s bare `id: ID!` cases flipped to `NodeIdField`; `NodeIdFieldValidationTest` flipped STUBBED → PROJECTED. All 482 rewrite tests green. Execution test deferred with Step 1's do-nothing fallback. See **Learnings from Step 3** and **Follow-ups from Step 3 review**.
4. **`NodeIdArg` + `LookupMapping` sum type + emitter branches** — classifier produces `NodeIdArg` for scalar ID args targeting NodeType; declared-`@nodeId`-on-input folds in here; `projectForLookup` produces `NodeIdMapping`; `LookupValuesJoinEmitter` dispatches on variant; non-lookup filter path handles the same. Pipeline-test coverage for both lookup and non-lookup shapes. **Test migration:** the `InputField.PlatformIdField` assertions in `PlatformIdFieldValidationTest` flip to `InputField.NodeIdField`. New execution test for a lookup query using platform-id keys.
5. **Delete `PlatformIdField` sum-type variants + supporting catalog methods** — the variant records, the `FieldBuilder`/`TypeBuilder` fallbacks, `hasPlatformIdAccessors`, `platformIdOutputMethodNames`, `sqlToAccessorSuffix` (if no other caller), `IMPLEMENTED_LEAVES` entries, `ChildPlatformIdFieldValidationTest` / `PlatformIdFieldValidationTest`. Partition invariants in the variant-coverage meta-test resolve automatically since the record no longer exists. **Test migration:** the two variant-specific test classes are deleted; no replacements needed — pipeline coverage for `NodeIdField` outcomes shipped in steps 2-4.

Mutation binding (previous Item 3) remains gated on argres Phase 3 and lands via that plan — but as a `NodeIdField` input variant, not `PlatformIdField`.

### Learnings from Step 3

- **`!@field` narrowing confirmed intentional.** The Output-side Open point asked whether Path-2's `!fieldDef.hasAppliedDirective(DIR_FIELD)` clause (which excludes `id: ID! @field(name:)` from the synthesized `NodeIdField` path) was intentional narrowing or a silent migration break. Resolved as *intentional*: synthesized NodeId fields derive their column set from `__NODE_KEY_COLUMNS` metadata, so `@field(name:)` carries no useful signal on these fields — it would be a silent no-op at best, a cause of drift at worst. After Step 5 deletes the fallback, schemas using `id: ID! @field(...)` on a platform-id table will classify as `UnclassifiedField`; this surfaces in Migration as a third diagnostic case alongside the two existing ones. No code change, just confirming the predicate.
- **Encoding moved out of SQL into the `DataFetcher`.** Initial design (commit `76ce48d`) projected `nodeIdStrategy(env).createId(typeId, ...).as(fieldName)` as an aliased `SelectField<String>` column and read it back via `ColumnFetcher<>(DSL.field(name, String.class))`. Review (see **Follow-ups from Step 3 review**) surfaced a latent compile-time type error and a design smell — `$fields` reaching into `env.getGraphQlContext()` for a strategy instance purely to build a SQL projection. Refactor (`c54c107`): `$fields` projects the raw key columns; a generated `NodeIdEncoder` utility (emitted alongside `ColumnFetcher`) encodes in Java from the `DataFetcher` lambda. Wire format unchanged — IDs round-trip with anything still using `NodeIdStrategy.unpackIdValues` / `hasIds`.
- **Test-spec compile tier pre-existingly broken on trunk.** All `@table` types classify as `UnclassifiedType` in the test-spec `mvn compile -pl :graphitron-rewrite-test-spec -Plocal-db` run — 48 errors on trunk before any Step 3 changes, 48 errors after. Not introduced by Step 3, but its existence means the "Compile-tier coverage" claim for Step 3's `NodeIdField` emission (that the generated `DataFetcher` body and `NodeIdEncoder.encode(typeId, r.get(Tables.X.COL))` calls type-check against a real jOOQ table class) is currently unverifiable. Separate concern from this plan; flagging here so a future Step 3/4 reviewer doesn't mistake a pre-existing test-spec failure for a Step 3 regression. Unit-tier coverage (482 rewrite tests) is the only compile-tier guarantee Step 3 currently holds.
- **Partition migration is mechanical but touches three sites.** Moving `NodeIdField` from `NOT_IMPLEMENTED_REASONS` to `PROJECTED_LEAVES` requires (a) updating the two `Set.of(...)` literals in `TypeFetcherGenerator`, (b) changing the fetcher switch arm from `stub(f)` to an empty `{ }` case, (c) flipping `NodeIdFieldValidationTest` from STUBBED-error-expected to PROJECTED-no-errors-expected (mirrors `LookupTableFieldValidationTest.LIST_PROJECTED`). The partition-invariants meta-test (`VariantCoverageTest` or similar) catches (a) automatically if either set is missed; the other two surface as test failures during the test run. No drift risk.

### Follow-ups from Step 3 review

Review of the initial Step 3 commit (`76ce48d`) surfaced one latent bug and a design smell; both resolved in `c54c107`. Remaining items tracked here:

- **`fields.add(SelectField<String>)` compile-time type error [resolved].** `NodeIdStrategy.createId(...).as(name)` returns `SelectField<String>`, not `Field<String>` (jOOQ 3.19's `row(...).mapping(Class<U>, Function)` is declared `SelectField<U>`). `$fields`'s `List<Field<?>>` therefore rejects it. The `PlatformIdField` arm (`fields.add(table.<accessor>())`) has the same latent bug for the same reason. Masked in both cases by the pre-existingly-broken test-spec compile tier. Resolved by removing all `SelectField`-typed expressions from `$fields` — the NodeIdField arm now projects raw `TableField` key columns, and the encoder moved to Java. The `PlatformIdField` arm remains but is on Step 5's deletion list.
- **Context reach-in from a context-free method [resolved].** `$fields(sel, table, env)` was calling `env.getGraphQlContext().get("nodeIdStrategy")` to build a SQL projection. Dropping the SQL-side ID projection also drops the reach-in — `$fields` stays context-free except for `env` threading that G5 requires independently.
- **Structural unit tests for the refactored emission [open].** `TypeClassGeneratorTest` has no case asserting that a `NodeIdField` causes one `fields.add(table.COL_N)` per key column (count matches `nodeKeyColumns().size()`). `TypeFetcherGeneratorTest` has no case asserting the NodeIdField wiring arm emits a `DataFetcher` lambda (not a `ColumnFetcher`) naming `NodeIdEncoder`. Both fit the project's structural-assertion rule and would catch a regression if the refactor is reverted. Add alongside Step 4 to avoid a separate commit.
- **Execution test against a real jOOQ table [open].** Still blocked on Step 1's deferred test-spec constants. Once Sakila's `store` (or another candidate) carries `__NODE_TYPE_ID` / `__NODE_KEY_COLUMNS`, an end-to-end test can encode an ID through the DataFetcher and round-trip it via `NodeIdStrategy.unpackIdValues` — verifying wire-format compatibility as an executable assertion rather than a manual cross-read of `NodeIdEncoder` vs `NodeIdStrategy` source.

### Learnings from Step 2

- **Diagnostic channel: sibling public method on `JooqCatalog`.** Added `Optional<String> nodeIdMetadataDiagnostic(String tableSqlName)` alongside the existing `nodeIdMetadata` reader, both backed by a memoized internal 3-state sum (`Present` / `Absent` / `Malformed(reason)`). Reflection + validation fire once per build per table. Keeps Step 1's 14 `Optional<NodeIdMetadata>`-shape tests untouched; rejected alternative was folding both into a single `Optional<Result<…>>` return.
- **`validateNodeIdMetadata` kept as the package-private test entry point.** `Optional<NodeIdMetadata>` signature unchanged; internally delegates to the 3-state lookup.
- **`NodeType` audit cleanly passed.** Six `NodeType` reference sites in the rewrite (`TypeBuilder:120` exhaustive-switch no-op, `FieldBuilder:1559`/`:1571` `@nodeId` guards, `GraphitronSchemaValidator:59,169`, `TypeClassGenerator:50` / `TypeFetcherGenerator:67` filter predicates) all behave correctly for metadata-carrying tables without an `id: ID!` field. No narrow-synthesis fallback needed.
- **Collision equality is order-sensitive.** SDL-declared columns compare position-by-position (`sqlName` equalsIgnoreCase) against metadata columns — `[id_1, id_2]` vs `[id_2, id_1]` is an error. Step 4's `LookupMapping.NodeIdMapping` inherits this rule.
- **`FieldBuilder:1572` error message updated** to "@nodeId requires the containing type to be a node type (via @node or KjerneJooqGenerator metadata)". Doc-consistency co-land.
- **Malformed-fixture end-to-end coverage deferred.** Classifier wiring is 3 `Optional.isPresent()` lines; correctness rests on 9 malformed unit tests + 3 `nodeIdMetadataDiagnostic` cases. A dedicated `MalformedBar` fixture isn't budgeted.

### Learnings from Step 1

- **`__NODE_KEY_COLUMNS` references instance fields via the static singleton.** Standard jOOQ emits columns as `public final TableField<...>` instance fields, not statics. The static `__NODE_KEY_COLUMNS` array therefore must qualify each entry through the table's static singleton — the fixture uses `{ BAR.ID_1, BAR.ID_2 }`, mirroring what `KjerneJooqGenerator` will need to emit inside `<Customer>.java`: `{ CUSTOMER.STORE_ID, CUSTOMER.CUSTOMER_ID }`. Source-order matters: `public static final Customer CUSTOMER = new Customer();` must come before `__NODE_KEY_COLUMNS` so the singleton is constructed (and its instance fields initialised) before the array literal evaluates. Pin this in the KjerneJooqGenerator design doc alongside the ordering-stability rule already in Open points.
- **Validator factored package-private for malformed-case testability.** `JooqCatalog.validateNodeIdMetadata(String, Object, Object, Function<String, Optional<ColumnEntry>>)` separates the reflection-read from the validation so each malformed-metadata branch is unit-testable without swapping `static final` fields on the fixture class (Java 17+ reflection on final fields is finicky and the `Field.setAccessible(true) + Field.set(null, ...)` dance is unreliable across JVMs). Step 2 resolved via the sibling-method + 3-state-lookup design (see **Learnings from Step 2**).

---

## Resolved points

- **`__NODE_TYPE_ID` naming rule.** KjerneJooqGenerator derives `__NODE_TYPE_ID` from the same source today's `@node(typeId: "...")` literals use — the GraphQL type name associated with the table in Sikt's schema conventions. The collision rule above (error on disagree) only works if this holds; if the generator picks a different source (e.g. the raw table-class stem `"CustomerRecord"` while SDL uses `"Customer"`), every hybrid schema fails classification. Pin the rule when the generator change is drafted; the rewrite-side consumer treats `__NODE_TYPE_ID` as opaque.
- **Constants as `Field<?>[]` vs. `List<Field<?>>`.** Array is what jOOQ `hasIds` signatures take; list is easier to build. KjerneJooqGenerator emits `Field<?>[]` (the consumer shape); the rewrite converts to `List<ColumnRef>` at the `JooqCatalog` boundary for model storage.

## Compile-tier coverage

`graphitron-rewrite-test-spec` compiles against a real jOOQ catalog, so emitted code should type-check against a real jOOQ table class: Step 3's `DataFetcher` body (`r.get(Tables.X.COL)` + `NodeIdEncoder.encode(...)`) on the output side, and `hasIds(...)` / `setId(...)` on the Step 4 input side.

Step 1 took the do-nothing fallback — the test-spec has no metadata-carrying table today, so real-jOOQ compile/execution coverage for platform-id waits on KjerneJooqGenerator X.Y shipping. The synthetic `platformidfixture` covers pipeline-level classification in the interim. If a pre-X.Y stopgap becomes worth the cost, the archived recipe is copy-and-edit: copy a leaf-table jOOQ class into `src/main/java/...`, add a jOOQ-plugin `<excludes>` entry, hand-edit the constants, revert post-X.Y. Rejected initially because non-leaf tables drag `Keys.java` into scope and the one-file estimate tends to grow.

## Open points

- **Consumers with mixed schemas** — some types `@node`, some platform-id. Both synthesize or declare `NodeType`; the classifier should not care. Pipeline-test cases per the tables above cover this.
- **Federation entity lookups.** Legacy's `FetchEntityImplementationDBMethodGenerator` dispatches on `processedSchema.isNodeIdField(field)`. The rewrite's equivalent (when it lands) will match on `ChildField.NodeIdField` — no platform-id branch needed.
- **`JooqCatalog.nodeIdMetadata` memoisation** *(resolved, Step 2)*. The catalog now caches the internal `NodeIdMetadataLookup` 3-state per `tableSqlName` via a `ConcurrentHashMap`; reflection + validation fire once per build regardless of how many classifier passes probe the same table. Closes the foot-gun preemptively.
- **`NestedInputId` → `InputColumnBinding` collapse.** Once argres Phase 3 lands `InputColumnBinding`, the `NestedInputId` permit added in Step 4 collapses into it — same path-walking semantics, wider binding type. Track as a mechanical cleanup commit after Phase 3; not a blocker for this plan, and the permit is narrow enough that the collapse is local to two files (`CallSiteExtraction.java` + `LookupValuesJoinEmitter`).
- **`__NODE_KEY_COLUMNS` ordering stability guarantee.** The collision rule's order-sensitive equality check only survives schema re-gens if KjerneJooqGenerator commits to a stable, deterministic column order (e.g. declared-order in the composite-key DDL, then primary-key order, then alphabetical as a tiebreak). A silent reorder between generator releases would re-encode new IDs in a different order than decoded IDs produced pre-upgrade, and `hasIds` would fail to match any of them — silently wrong results across the cut-over. Pin the ordering rule in the KjerneJooqGenerator design doc when the X.Y constants are drafted; the rewrite treats the order as opaque but stable. Release-notes for X.Y+N must call out any ordering change as breaking.
- **Path-2 `!@field` gate** *(resolved, Step 3)*. The `!fieldDef.hasAppliedDirective(DIR_FIELD)` narrowing is intentional — synthesized NodeId fields get their column set from `__NODE_KEY_COLUMNS`, so `@field(name:)` carries no useful signal. After Step 5 deletes the fallback, `id: ID! @field(...)` on a platform-id table classifies as `UnclassifiedField`; this surfaces in Migration as a third diagnostic case.
- **`@node` with `null` typeId / empty keyColumns under no-metadata.** Pre-existing behaviour today: `TypeBuilder.java:275` stores `typeId=null` when `@node` is declared without `typeId:`, and `TypeBuilder.java:276-287` stores an empty `keyColumns` list when `@node` is declared without `keyColumns:` (the directive docs say "we use the primary key" but the rewrite's `TypeBuilder` does no PK substitution — only the empty list is recorded). The pivot preserves both on tables with no metadata (no collision, no synthesis). Downstream consumers that dereference `NodeType.typeId()` or pass `NodeType.nodeKeyColumns()` to `NodeIdStrategy.createId(...)` without empty/null guards will NPE or emit `"typeId:"` (bare prefix) — not a regression introduced by this plan, but the `NodeType` audit at Step 2 should note any sites that would blow up on null typeId or empty keyColumns, and either tolerate them or file a follow-up to implement the documented PK-fallback.

---

## History

- **2026-04-18 — pivot to synthesized NodeType.** Earlier the plan was a parallel four-item `PlatformIdField` sum-type variant; the argument/filter emission gap surfaced as a parallel type-system problem. Pivoted to having KjerneJooqGenerator emit `__NODE_TYPE_ID` + `__NODE_KEY_COLUMNS` constants so the rewrite subsumes platform-id into the existing `NodeType` / `NodeIdField` classification. Reviewer pass resolved the `@node`-vs-synthesized contradiction (error-on-disagree), pinned input-side `@nodeId` as the first classifier path, added the compile-tier coverage section, and added release sequencing. Items 1-2 of the previous plan (output/input classification, dispatch, synthetic-fixture pipeline tests) are undone in Step 5; classification tests migrate to `NodeIdField` outcomes on the same fixture.
- **2026-04-19 — Steps 1 + 2 shipped.** Eight Draft iterations between 2026-04-18 and 2026-04-19 consolidated the plan: resolved N1–N4 (error-on-disagree is symmetric, synthesis is unconditional, `NestedInputId` permit deferred to Step 4 kickoff), pinned the Path-2 trigger predicate + placement + `PROJECTED_LEAVES` destination, reworked the compile-tier mechanism to copy-and-edit a generated table class, added the `JooqCatalog.nodeIdMetadata` memoisation + malformed-metadata diagnostic, and corrected line-number drift after each shipped step.
- **2026-04-19 — Step 3 shipped and refactored.** Initial landing (`76ce48d`) emitted `NodeIdStrategy.createId` inside `$fields` via a context reach-in helper. Reviewer flagged a latent compile-time type mismatch (`SelectField<String>` into `List<Field<?>>`) plus the context-reach-in smell; refactored (`c54c107`) to raw key-column projection + Java-side encoding in a wiring-layer DataFetcher backed by a generated `NodeIdEncoder` utility. Encoding no longer lives in SQL.
