# `@nodeId` + `@node` directive support

> **Status:** Ready
>
> Scope: the rewrite supports Relay node identity through two directives, `@node` on types (carries `typeId` + `keyColumns`) and `@nodeId` on fields / arguments (marks an `ID` as a composite-key encoding). Platform-id tables participate as a *synthesis trigger*: KjerneJooqGenerator emits `__NODE_TYPE_ID` + `__NODE_KEY_COLUMNS` constants on each platform-id table, the rewrite reads them and synthesizes `NodeType` unconditionally, then routes projection, filter, mutation binding, and `Query.node` resolution through the same `@nodeId` paths. `PlatformIdField` sum-type variants are deleted; `NodeIdReferenceField` and `QueryField.QueryNodeField` land as terminal consumers of the same machinery. Supersedes the previous platform-id-centric plan (parallel `getId`/`hasId`/`hasIds`-driven classification).

## Why one umbrella, not four

Earlier roadmap snapshots had platform-id migration, `@nodeId` infrastructure, `NodeIdReferenceField` emission, and the `Query.node` resolver as separate items. After the pivot below, all four resolve to the same sum-type variants (`NodeType`, `ChildField.NodeIdField`, `ChildField.NodeIdReferenceField`, `InputField.NodeIdField`, `InputField.NodeIdReferenceField`, `NodeIdArg`, `NodeIdMapping`) and share one helper (`NodeIdStrategy`). Keeping them as separate roadmap rows made the dependency graph look wider than it is; consolidating keeps planning aligned with the code.

An earlier plan classified platform-id as its own sum-type variant (`InputField.PlatformIdField`, `ChildField.PlatformIdField`), reflected on per-table `getId()`/`setId()`/`hasId()`/`hasIds()` methods emitted by `KjerneJooqGenerator`, and would have introduced a parallel filter-emission path (`PlatformIdArg`, `PlatformIdMapping`, platform-id arm in `LookupValuesJoinEmitter`). Two things make that wrong:

- **It invents a parallel type system for something already classified.** Platform-id tables are structurally composite-key node types. The rewrite already has `NodeType` / `ChildField.NodeIdField` (plus the planned input-side and emitter work for `@nodeId`) carrying exactly the metadata a composite key needs: `typeId` + `nodeKeyColumns`. Reflecting on `getId()` to re-derive the same information via method naming + return-type matching is the model-metadata-over-parallel-type-systems anti-pattern, applied twice.
- **Sikt owns KjerneJooqGenerator.** The generator can expose the underlying metadata directly. With `__NODE_TYPE_ID` + `__NODE_KEY_COLUMNS` constants, every place the rewrite would have called `table.hasIds(set)` instead calls `NodeIdStrategy.hasIds(typeId, set, keyColumns)` — the same helper `@nodeId` fields use. One code path, one set of tests.

Trade-off: a KjerneJooqGenerator release is required before the rewrite can depend on the metadata. Sikt controls the release cadence; we coordinate rather than maintain a reflection-based fallback forever. See **Migration** below.

---

## Model

Four terminal variants carry node-identity classification, symmetric across the output and input sides:

| Side | Variant | Trigger | Emission |
|---|---|---|---|
| Output | `ChildField.NodeIdField` | scalar `ID` on a `NodeType` parent; bare `@nodeId` or synthesized | project `nodeKeyColumns`; encode via `NodeIdEncoder.encode(typeId, …)` |
| Output | `ChildField.NodeIdReferenceField` | scalar `ID` with `@nodeId(typeName:)` pointing at another `NodeType`; `joinPath` resolved to that type's table | project target's `nodeKeyColumns` through `joinPath`; encode via `NodeIdEncoder.encode(typeId, …)` |
| Input | `InputField.NodeIdField` | scalar `ID` on a `@table` input whose own table is a `NodeType`; bare `@nodeId` or synthesized | decode via `NodeIdStrategy.unpackIdValues` / `hasIds` / `hasId`; each unpacked value binds to its own-table column |
| Input | `InputField.NodeIdReferenceField` | scalar `ID` on a `@table` input with `@nodeId(typeName:)` pointing at another `NodeType`; `joinPath` resolved to that type's table | decode via `NodeIdStrategy.unpackIdValues`; each unpacked value binds to the reachable column on the target table, reached through `joinPath` (direct FK-bind when the input's own table mirrors the target's key columns) |

Shared runtime helpers: `NodeIdStrategy` (decode / `hasIds` / `hasId` / `setId` / `createId`) and `NodeIdEncoder` (encode). Both input variants carry the same shape fields as their output counterparts plus `nonNull`, `list`, and the optional `ArgConditionRef condition` every `InputField` already carries — except `list`, which is fixed `false` by the classifier (the scalar gate is load-bearing on both sides).

`PlatformIdField` variants on both `ChildField` and `InputField` are deleted. Every former platform-id classification site routes through one of the four rows above; every former `PlatformIdField` emission site routes through `NodeIdStrategy` + `NodeIdEncoder`.

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

### Type-level synthesis and output-side `NodeIdField` (shipped)

`TypeBuilder.buildTableType` reads `__NODE_TYPE_ID` + `__NODE_KEY_COLUMNS` via `JooqCatalog.nodeIdMetadata` and synthesizes `NodeType` unconditionally when metadata is present. Malformed metadata surfaces as `UnclassifiedType` through `nodeIdMetadataDiagnostic`. Disagreement between a declared `@node(...)` and metadata errors symmetrically on both `typeId` and `keyColumns`; `@node` without `typeId:` / `keyColumns:` takes the metadata value verbatim.

`ChildField.NodeIdField` is populated either by the existing bare-`@nodeId` directive path or by the synthesized Path-2 trigger in `FieldBuilder` (scalar non-list `ID` on a `NodeType` parent, no `@nodeId`/`@reference`/`@field`, no real column). `TypeClassGenerator.$fields` projects the raw key columns; `TypeFetcherGenerator` wires a lambda calling a generated `NodeIdEncoder.encode(typeId, …)` utility with URL-safe base64 no-pad + `,`→`%2C` encoding so IDs round-trip against `NodeIdStrategy.unpackIdValues` / `hasIds`.

### Output-side `NodeIdReferenceField` (classifier shipped; emission stubbed)

`ChildField.NodeIdReferenceField` carries `(typeName, targetType, parentTable, nodeTypeId, nodeKeyColumns, joinPath)` and is populated by `FieldBuilder` when a scalar `ID` field declares `@nodeId(typeName: "X")` where `X` resolves to a `NodeType`. The classifier resolves `joinPath` to reach that type's table and pulls `nodeTypeId` + `nodeKeyColumns` off the resolved `NodeType`.

Emission is currently stubbed (tracked as Generator stub #8; the stub reason lives at `TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS` for `NodeIdReferenceField`). Step 6 lands it: `TypeClassGenerator.$fields` JOINs through `joinPath` and projects the target's `nodeKeyColumns` under an alias; `TypeFetcherGenerator` wires a lambda that reads the aliased values off the record and calls the same `NodeIdEncoder.encode(typeId, …)` that `NodeIdField` uses.

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

### Input-side `NodeIdReferenceField` (new variant)

Parallel shape on the input side, added to the `InputField` sealed permits alongside `NodeIdField`:

```java
record NodeIdReferenceField(
    String parentTypeName,
    String name,
    SourceLocation location,
    String typeName,
    boolean nonNull,
    TableRef parentTable,
    String nodeTypeId,
    List<ColumnRef> nodeKeyColumns,
    List<JoinStep> joinPath,
    Optional<ArgConditionRef> condition
) implements InputField {}
```

Classified when a scalar `ID` field on a `@table` input declares `@nodeId(typeName: "X")` where `X` is a `NodeType` reachable from the input's own table via `joinPath` (resolved by the same reference-path parser `InputField.ColumnReferenceField` uses). `list` is fixed `false` by the classifier — list inputs collapse the containing `TableInputType` to `UnclassifiedType` per the existing list-gate rule.

Emission (Step 5): decode via `NodeIdStrategy.unpackIdValues`, then bind each unpacked value to its column on the reachable target table. In WHERE contexts (filter / lookup), the bind sits inside a JOIN-through `joinPath` before `hasIds` / `hasId` fires; in FK-mirror cases where the input's own table carries columns congruent with the target's key columns, the bind collapses to a direct same-table column assignment and no JOIN is needed. The dispatch between the two shapes lives in the same emitter arm as `InputField.NodeIdField`.

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

Platform-id stops existing as a separate emission path. Projection and read-back shipped in Step 3 (raw key-column projection + `NodeIdEncoder.encode` in the DataFetcher). Remaining Step 4–6 emission:

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

`InputColumnBinding` carries one `NodeIdBinding` sum variant that serves both `InputField.NodeIdField` (direct own-table bind) and `InputField.NodeIdReferenceField` (bind via `joinPath` or FK-mirror). The mutation generator dispatches on the binding variant, not on the `InputField` subtype, so the two classifier outputs converge on one emission path.

Blocked on the same argres Phase 3 the previous plan's Item 3 was blocked on.

### Output reference — `ChildField.NodeIdReferenceField`

`TypeClassGenerator.$fields` emits a JOIN-through `joinPath` and projects the target's `nodeKeyColumns` under an alias per step of the join. `TypeFetcherGenerator` wires a lambda that reads the aliased values off the record and calls `NodeIdEncoder.encode(typeId, …)` — the same encoder `NodeIdField` uses. Closes the `NodeIdReferenceField` slot in Generator stub #8; the other leaves in stub #8 (`ColumnReferenceField`, `ComputedField`, etc.) stay out-of-scope for this plan.

### Input reference — `InputField.NodeIdReferenceField`

Filter / lookup bind: decode via `NodeIdStrategy.unpackIdValues`, emit a JOIN through `joinPath` if the target isn't the input's own table (or collapse to direct column assignment when the own-table mirrors the target's key columns), then bind each unpacked value to its target column inside the JOIN. Both shapes reuse the `hasIds` / `hasId` helpers introduced for `InputField.NodeIdField`; the `LookupValuesJoinEmitter` `NodeIdMapping` arm extends to carry a `joinPath` alongside `nodeKeyColumns` for the reference variant.

Mutation bind for the reference variant lands through the same argres Phase 3 `InputColumnBinding` channel as `InputField.NodeIdField`; the `NodeIdBinding` variant covers both dispatch arms.

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

## Pipeline-test fixture

Synthetic fixture shipped: `Bar` carries `__NODE_TYPE_ID="Bar"` + `__NODE_KEY_COLUMNS={BAR.ID_1, BAR.ID_2}`; `Qux` carries none (negative case). Steps 4–5 add input-side cases to `PlatformIdFieldValidationTest` / `PlatformIdPipelineTest`:

Output-side cases (synthesized + declared-`@node` + collision + list-gate + negative) shipped in Steps 2–3; `PlatformIdPipelineTest` covers them. Steps 4–5 add the input-side cases below; Step 6 adds the output-reference execution case.

**Input side** (`TypeBuilder.resolveInputField`; same disagreement and list-gate rules):

| SDL | Expected outcome |
|-----|-----------------|
| `input FooLookup @table(name: "bar") { id: ID! }` | `InputField.NodeIdField(nodeTypeId="Bar", nodeKeyColumns=[ID_1, ID_2])` — synthesized |
| `input FooLookup @table(name: "bar") { id: ID! @nodeId }` | `InputField.NodeIdField` via declared `@nodeId` (first-time classifier path; see Input-side section) |
| `input FooLookup @table(name: "bar") @node(typeId: "Foo") { id: ID! }` | Classifier error — same disagreement message as output |
| `input FooLookup @table(name: "bar") { id: [ID!]! }` | `UnclassifiedType` — one unresolved field collapses the `TableInputType` |
| `input FooLookup @table(name: "qux") { id: ID! }` | `UnclassifiedType` — no metadata on `qux` |

**Input reference side** (Step 5; `InputField.NodeIdReferenceField`):

| SDL | Expected outcome |
|-----|-----------------|
| `input FooLookup @table(name: "bar") { relatedId: ID! @nodeId(typeName: "Qux") }` with `bar → qux` reference resolvable | `InputField.NodeIdReferenceField(nodeTypeId="Qux", nodeKeyColumns=[QUX.ID], joinPath=[bar→qux])` |
| same as above but `typeName: "Qux"` not a `NodeType` (no metadata, no `@node`) | Classifier error — "referenced type is not a NodeType" |
| same but `[ID!]!` | `UnclassifiedType` — list-gate applies |
| same but no resolvable FK path from `bar` to `qux` | `UnclassifiedType` — reference-path parser rejects |

**Output reference side** (Step 6; `ChildField.NodeIdReferenceField` emission test-spec cases): at least one real-jOOQ execution case covering projection + encoding through a single-hop FK path on the test-spec Sakila schema (e.g. `Film.languageNodeId: ID! @nodeId(typeName: "Language")` resolving through `film.language_id → language.language_id`).

---

## Migration

Consumers regenerate jOOQ classes with the new KjerneJooqGenerator release before their rewrite build finds the metadata. During the transition window:

- **Preferred path — hard cut-over.** The rewrite requires the new metadata. Tables that were platform-id under the old generator but lack the constants classify as `TableType` (no synthesized NodeId) — any schema using `id: ID!` on them lands on `UnclassifiedField` with a diagnostic pointing at "regenerate jOOQ classes with KjerneJooqGenerator ≥ X.Y". Clean, simple, discoverable. Step 7 replaces the current column-not-found diagnostic at `FieldBuilder.java:1627-1630` with the regenerate-jOOQ phrasing; on the input side, `TypeBuilder.java:646-648`'s `"no accessor methods (…) found on record class"` is swapped for the same message. The "regenerate jOOQ classes with KjerneJooqGenerator ≥ X.Y" literal is the canonical diagnostic — every platform-id migration failure produces this exact phrase so consumers can grep for it in build logs.
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

Commits, roughly in order. Each lands independently and green.

Steps 1–3 shipped on trunk (probe + metadata-driven `NodeType` synthesis + `ChildField.NodeIdField` projection/DataFetcher). Remaining:

4. **`InputField.NodeIdField` + `NodeIdArg` + `LookupMapping` sum type + emitter branches** — classifier produces `InputField.NodeIdField` for scalar `ID` fields on `@table` inputs whose own table is a `NodeType`; classifier produces `NodeIdArg` for scalar `ID` args targeting `NodeType`; declared-`@nodeId` on both input fields and args folds in here; `projectForLookup` produces `NodeIdMapping`; `LookupValuesJoinEmitter` dispatches on variant; non-lookup filter path handles the same. Pipeline-test coverage for both lookup and non-lookup shapes. **Test migration:** the `InputField.PlatformIdField` assertions in `PlatformIdFieldValidationTest` flip to `InputField.NodeIdField`. New execution test for a lookup query using platform-id keys.
5. **`InputField.NodeIdReferenceField` + reference-aware emitter branch** — add the sum-type permit, the classifier path for scalar `ID` with `@nodeId(typeName:)` on `@table` inputs that resolves to a reachable `NodeType` (joinPath resolved by the existing reference-path parser), and the `LookupValuesJoinEmitter` / filter-path dispatch that JOINs through `joinPath` before `hasIds` / `hasId` (or collapses to direct column assignment in the FK-mirror shape). Pipeline-test coverage for reference-variant lookup and non-lookup shapes.
6. **`ChildField.NodeIdReferenceField` emission** — `TypeClassGenerator.$fields` projects target-table key columns via the resolved JOIN; `TypeFetcherGenerator` wires the encode lambda. Remove the `NOT_IMPLEMENTED_REASONS` entry at `TypeFetcherGenerator.java:247-248` and the `stub(f)` dispatch arm at `:358`. Closes the `NodeIdReferenceField` slot in Generator stub #8. Execution-test coverage against the real-jOOQ test-spec.
7. **Delete `PlatformIdField` sum-type variants + supporting catalog methods** — the variant records, the `FieldBuilder`/`TypeBuilder` fallbacks, `hasPlatformIdAccessors`, `platformIdOutputMethodNames`, `sqlToAccessorSuffix` (if no other caller), `IMPLEMENTED_LEAVES` entries, `ChildPlatformIdFieldValidationTest` / `PlatformIdFieldValidationTest`. Partition invariants in the variant-coverage meta-test resolve automatically since the record no longer exists. **Test migration:** the two variant-specific test classes are deleted; no replacements needed — pipeline coverage for `NodeIdField` + `NodeIdReferenceField` outcomes shipped in steps 2-6.

Mutation binding (input-side `setId` / column-bind) remains gated on argres Phase 3 and lands via that plan — as a unified `NodeIdBinding` in `InputColumnBinding`, with one dispatch arm serving both `InputField.NodeIdField` and `InputField.NodeIdReferenceField`.

---

## Compile-tier coverage

`graphitron-rewrite-test` compiles against a real jOOQ catalog, so emitted code should type-check against a real jOOQ table class: Step 3's `DataFetcher` body (`r.get(Tables.X.COL)` + `NodeIdEncoder.encode(...)`) on the output side; `hasIds(...)` / `setId(...)` on the Step 4–5 input side; and the JOIN-projection + encode pair on the Step 6 output-reference side.

Step 1 took the do-nothing fallback — the test-spec has no metadata-carrying table today, so real-jOOQ compile/execution coverage for platform-id waits on KjerneJooqGenerator X.Y shipping. The synthetic `platformidfixture` covers pipeline-level classification in the interim. If a pre-X.Y stopgap becomes worth the cost, the archived recipe is copy-and-edit: copy a leaf-table jOOQ class into `src/main/java/...`, add a jOOQ-plugin `<excludes>` entry, hand-edit the constants, revert post-X.Y. Rejected initially because non-leaf tables drag `Keys.java` into scope and the one-file estimate tends to grow.

## Open points

- **Consumers with mixed schemas** — some types `@node`, some platform-id. Both synthesize or declare `NodeType`; the classifier should not care. Pipeline-test cases per the tables above cover this.
- **Federation entity lookups.** Legacy's `FetchEntityImplementationDBMethodGenerator` dispatches on `processedSchema.isNodeIdField(field)`. The rewrite's equivalent (when it lands) will match on `ChildField.NodeIdField` — no platform-id branch needed.
- **`NestedInputId` → `InputColumnBinding` collapse.** Once argres Phase 3 lands `InputColumnBinding`, the `NestedInputId` permit added in Step 4 collapses into it — same path-walking semantics, wider binding type. Track as a mechanical cleanup commit after Phase 3; not a blocker for this plan, and the permit is narrow enough that the collapse is local to two files (`CallSiteExtraction.java` + `LookupValuesJoinEmitter`).
- **`__NODE_KEY_COLUMNS` ordering stability guarantee.** The collision rule's order-sensitive equality check only survives schema re-gens if KjerneJooqGenerator commits to a stable, deterministic column order (e.g. declared-order in the composite-key DDL, then primary-key order, then alphabetical as a tiebreak). A silent reorder between generator releases would re-encode new IDs in a different order than decoded IDs produced pre-upgrade, and `hasIds` would fail to match any of them — silently wrong results across the cut-over. Pin the ordering rule in the KjerneJooqGenerator design doc when the X.Y constants are drafted; the rewrite treats the order as opaque but stable. Release-notes for X.Y+N must call out any ordering change as breaking.
- **`@node` with `null` typeId / empty keyColumns under no-metadata.** Pre-existing behaviour today: `TypeBuilder.java:275` stores `typeId=null` when `@node` is declared without `typeId:`, and `TypeBuilder.java:276-287` stores an empty `keyColumns` list when `@node` is declared without `keyColumns:` (the directive docs say "we use the primary key" but the rewrite's `TypeBuilder` does no PK substitution — only the empty list is recorded). The pivot preserves both on tables with no metadata (no collision, no synthesis). Downstream consumers that dereference `NodeType.typeId()` or pass `NodeType.nodeKeyColumns()` to `NodeIdStrategy.createId(...)` without empty/null guards will NPE or emit `"typeId:"` (bare prefix) — not a regression introduced by this plan, but the `NodeType` audit at Step 2 should note any sites that would blow up on null typeId or empty keyColumns, and either tolerate them or file a follow-up to implement the documented PK-fallback.

