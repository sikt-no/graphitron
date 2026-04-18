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
- **`__ID_KEY_COLUMNS`** — the underlying `Field<?>` references, in the order the composite ID is decoded into (i.e. the same order `NodeIdStrategy.hasIds(typeId, ids, keyFields)` expects).

Static finals, not instance fields — accessible as `Customer.__ID_TYPE_ID` / `Customer.__ID_KEY_COLUMNS` without needing an instance. Reflection lookup is trivial.

The existing method emissions (`getId()`, `hasId`, `hasIds`) can stay — harmless for non-graphitron callers. The rewrite stops detecting and calling them.

---

## Classification

### Synthesize `NodeType` from metadata

`TypeBuilder.buildTableBoundType` (`TypeBuilder.java:246-270`) today branches on whether the GraphQL type has `@node`:

- Has `@node` → `NodeType(typeId, nodeKeyColumns)`
- Otherwise → `TableType` (no node identity)

With the pivot, a third branch: if the GraphQL type has `@table` but no `@node`, and the backing jOOQ table exposes the metadata constants, synthesize `NodeType` using `__ID_TYPE_ID` and `__ID_KEY_COLUMNS`. Schema-declared `@node` still wins when both are present (explicit over implicit); an error if the two disagree.

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

`TypeBuilder.resolveInputField` (`TypeBuilder.java:558-574` is where `PlatformIdField` currently classifies) becomes: scalar `ID`, not a list, no `@nodeId`; if the backing table resolves to a synthesized-or-declared `NodeType`, classify as `InputField.NodeIdField` carrying the same `typeId` + `keyColumns` pair. The existing `@nodeId`-on-input path (if one exists; check during implementation) folds into this same variant.

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

`CallSiteExtraction` describes how the emitter reads the `Set<String>` of base64 IDs from `env` — direct scalar, list-scalar, input-type field, or input-list-of-inputs-with-field. The four shapes all produce the same downstream emission, differing only in the preamble that assembles the set.

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

| SDL | Expected outcome |
|-----|-----------------|
| `type Foo @table(name: "bar") { id: ID! }` | `ChildField.NodeIdField(nodeTypeId="Bar", nodeKeyColumns=[...])` (synthesized) |
| `type Foo @table(name: "bar") @node(typeId: "Foo") { id: ID! }` | `NodeIdField(nodeTypeId="Foo", ...)` — explicit `@node` wins |
| `type Foo @table(name: "bar") @node(typeId: "Bar") { id: ID! }` | Same as synthesized — matching `@node` accepted |
| `type Foo @table(name: "qux") { id: ID! }` | `UnclassifiedField` (no real column, no metadata, no synthesis) |
| `type Foo @table(name: "bar") { id: ID! @nodeId }` | `NodeIdField` via existing `@nodeId` path |
| `type Foo @table(name: "bar") { id: [ID!]! }` | `UnclassifiedField` — list gate |
| Input parallels of the above | `InputField.NodeIdField` / `UnclassifiedType` |

The `@node`-declared-disagrees-with-synthesized case (different `typeId` or `keyColumns`) surfaces as a classifier error with both values in the message.

---

## Migration

Consumers regenerate jOOQ classes with the new KjerneJooqGenerator release before their rewrite build finds the metadata. During the transition window:

- **Preferred path — hard cut-over.** The rewrite requires the new metadata. Tables that were platform-id under the old generator but lack the constants classify as `TableType` (no synthesized NodeId) — any schema using `id: ID!` on them lands on `UnclassifiedField` with a diagnostic pointing at "regenerate jOOQ classes with KjerneJooqGenerator ≥ X.Y". Clean, simple, discoverable.
- **Fallback — not chosen.** Keeping the old `hasPlatformIdAccessors` detection alongside the new path for N releases would drag the platform-id-specific classification infrastructure through a deprecation window. Avoid unless coordination breaks down.

Because Sikt owns both the generator and the consuming rewrite, the hard cut-over is realistic.

---

## Scheduling

Five commits, roughly in order. Each lands independently and green.

1. **`JooqCatalog.nodeIdMetadata` probe** — reflection over `__ID_TYPE_ID` + `__ID_KEY_COLUMNS`. No callers yet; unit-tested against the synthetic fixture (which gets the constants added in the same commit).
2. **`TypeBuilder` synthesizes `NodeType`** — classifier reads `nodeIdMetadata` when `@node` absent + `@table` present. Existing output-side classifier stops relying on `platformIdOutputMethodNames` / the platform-id fallback. `ChildField.PlatformIdField` still exists but is now unreachable — the variant record is deleted in step 5.
3. **`ChildField.NodeIdField` emission** — move out of `NOT_IMPLEMENTED_REASONS`; implement projection in `TypeClassGenerator.$fields` via `NodeIdStrategy.createId`. Reuses existing helper.
4. **`NodeIdArg` + `LookupMapping` sum type + emitter branches** — classifier produces `NodeIdArg` for scalar ID args targeting NodeType; `projectForLookup` produces `NodeIdMapping`; `LookupValuesJoinEmitter` dispatches on variant; non-lookup filter path handles the same. Pipeline-test coverage for both lookup and non-lookup shapes.
5. **Delete `PlatformIdField` sum-type variants + supporting catalog methods** — the variant records, the `FieldBuilder`/`TypeBuilder` fallbacks, `hasPlatformIdAccessors`, `platformIdOutputMethodNames`, `sqlToAccessorSuffix` (if no other caller), `IMPLEMENTED_LEAVES` entries, `ChildPlatformIdFieldValidationTest` / `InputPlatformIdFieldValidationTest`. Partition invariants in the variant-coverage meta-test resolve automatically since the record no longer exists.

Mutation binding (previous Item 3) remains gated on argres Phase 3 and lands via that plan — but as a `NodeIdField` input variant, not `PlatformIdField`.

---

## Open points

- **Constants as `Field<?>[]` vs. `List<Field<?>>`.** Array is what jOOQ `hasIds` signatures take; list is easier to build. The reflection probe can convert between them. KjerneJooqGenerator emits whichever is easier; the rewrite converts at the `JooqCatalog` boundary.
- **Typed `typeId`.** Nothing prevents KjerneJooqGenerator from deriving `__ID_TYPE_ID` from the table class name (`"CustomerRecord"` → `"Customer"`) or from the schema-level type name. Agree on a rule before the generator ships so it matches `@node(typeId: "...")` conventions consumers already write.
- **Consumers with mixed schemas** — some types `@node`, some platform-id. Both synthesize or declare `NodeType`; the classifier should not care. Worth a pipeline-test case per the table above.
- **Federation entity lookups.** Legacy's `FetchEntityImplementationDBMethodGenerator` dispatches on `processedSchema.isNodeIdField(field)`. The rewrite's equivalent (when it lands) will match on `ChildField.NodeIdField` — no platform-id branch needed.

---

## History

- **2026-04-18 (pivot; this Draft)** — KjerneJooqGenerator will emit `__ID_TYPE_ID` + `__ID_KEY_COLUMNS` constants, letting the rewrite subsume platform-id into the existing `NodeType` / `NodeIdField` classification. Deletes `PlatformIdField` sum-type variants after migration; eliminates the parallel filter-emission path that would have been required to support `table.hasId` / `table.hasIds`. Supersedes the previous four-item plan.
- **2026-04-18 (earlier Draft iteration)** — captured the argument/filter emission gap (previous Item 4); classified as a parallel type-system problem during review. Led to the pivot above.
- **2026-04-18 (P2 #3 follow-up)** — fixed `ChildField.PlatformIdField` dispatch registration after it was incorrectly kept in `NOT_IMPLEMENTED_REASONS`. Obsoleted by this plan — the variant itself goes away.
- **Earlier** — Items 1-2 (output classification, input classification, dispatch, pipeline tests against synthetic fixture) shipped under the parallel-variant approach. Those shipped pieces are undone in Step 5 above; the classification tests migrate to `NodeIdField` outcomes on the same fixture.
