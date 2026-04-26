---
title: "Stub #3: Interface / union fetchers"
status: Spec
bucket: stubs
priority: 1
---

# Stub #3: Interface / union fetchers

Six leaves in `TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS` covering GraphQL interface and union return types. The work splits by implementation complexity.

**Track A** (`TableInterfaceType` variants) is fully mechanical: single-table polymorphism with a discriminator column, mirroring the existing `QueryTableField` / `TableTargetField` emission pattern. High confidence.

**Track B** (multi-table polymorphic variants) requires a design decision before coding: these fields carry only a `PolymorphicReturnType` with no table or method binding, so no fetcher can be auto-generated without additional context.

The companion cleanup item (`typeresolver-wiring-interface-union.md`) is absorbed here. Track A closes it for `TableInterfaceType`; Track B closes it for `InterfaceType` / `UnionType`.

Priority number `#3` must stay stable: it is embedded in emitted reason strings consumed by existing schema authors.

---

## Track A: `TableInterfaceType` (single-table discriminated)

**Variants:** `QueryField.QueryTableInterfaceField`, `ChildField.TableInterfaceField`

Both carry `TableBoundReturnType` and implement `SqlGeneratingField`. Both are structurally identical to their non-interface counterparts (`QueryTableField`, `SplitTableField`, etc.) except the SELECT must project the discriminator column so the TypeResolver can identify the concrete type at runtime.

### Phase A1: Fixture additions

No fixture SDL currently exercises `TableInterfaceType`, `InterfaceType`, or `UnionType` (other than `Node`). Add at minimum:

- A `@table @discriminate` interface using an existing Sakila table that has a natural enum-like column, or extend the fixture DB with a small discriminated table. Both concrete implementor types must appear in the fixture query root.
- At least one `@asConnection` wrapper on the field to confirm pagination interacts correctly.

The `InterfaceType` and `UnionType` fixtures needed for Track B classification tests can be added in the same commit.

### Phase A2: Classifier — add `discriminatorColumn` to field records

`QueryTableInterfaceField` and `ChildField.TableInterfaceField` do not currently carry the discriminator column name; the generator cannot project it. The classifier has `tableInterfaceType.discriminatorColumn()` in scope and must pass it through.

Touch points:
- `QueryField.QueryTableInterfaceField` — add `String discriminatorColumn` component; classifier populates from `tableInterfaceType.discriminatorColumn()`.
- `ChildField.TableInterfaceField` — same addition.
- `FieldBuilder.classifyQueryField` (around line 1640) and `classifyObjectReturnChildField` (around line 375) — pass the value at construction.
- Any existing tests constructing these records must add the new argument; `GeneratorCoverageTest` will fail to compile until the record arity is fixed.

### Phase A3: Generator — fetcher methods

**`buildQueryTableInterfaceFieldFetcher`** (new method in `TypeFetcherGenerator`):
- Mirrors `buildQueryTableFetcher` exactly.
- The `select(...)` clause adds the discriminator column alongside `$fields(...)` unconditionally: `DSL.field(DSL.name(tableLocalName, field.discriminatorColumn()))`.
- Return type and `isList` / Connection branching follow `QueryTableField` precedent.

**`buildTableInterfaceFieldFetcher`** (new method, child variant):
- Mirrors the batch-key / rows-method pattern of `SplitTableField` / `RecordTableField`.
- Same discriminator column projection.

Switch arms in `generateTypeSpec` change from `stub(f)` to the new builder calls. Both classes move from `NOT_IMPLEMENTED_REASONS` to `IMPLEMENTED_LEAVES`. `needsGraphitronContextHelper` already activates for any `SqlGeneratingField`; no change needed there.

### Phase A4: TypeResolver wiring

Touch point: `GraphitronSchemaClassGenerator.generate()`, after the existing `hasNodeTypes` block (around line 92). For each `TableInterfaceType` in `schema.types()`, emit:

```
codeRegistry.typeResolver("<InterfaceName>", env -> {
    Record record = (Record) env.getObject();
    if (record == null) return null;
    String value = String.valueOf(record.get("<discriminatorColumn>"));
    if ("<value1>".equals(value)) return env.getSchema().getObjectType("<TypeName1>");
    ...
    return null;
});
```

The `participants` list on `TableInterfaceType` provides the `(discriminatorValue, typeName)` pairs. Skip `ParticipantRef.Unbound` entries. No new generator class is needed: this is a CodeBlock loop in `GraphitronSchemaClassGenerator`.

### Phase A5: Tests

- **Classifier unit tests:** `QueryTableInterfaceField` and `ChildField.TableInterfaceField` construct with `discriminatorColumn` populated correctly.
- **`TypeFetcherGeneratorTest` snapshots:** assert the discriminator column appears in the SELECT clause for both variants.
- **`GraphitronSchemaClassGeneratorTest`:** assert `typeResolver(...)` registration appears for a schema with a `TableInterfaceType`.
- **Pipeline / execution tests:** compile and run the fixture SDL; assert concrete type resolution works at runtime.

---

## Track B: Multi-table polymorphic

**Variants:** `QueryField.QueryInterfaceField`, `QueryField.QueryUnionField`, `ChildField.InterfaceField`, `ChildField.UnionField`

All four carry only `PolymorphicReturnType` — no table, no `MethodRef`. The classifier produces them when the return type is a multi-table `InterfaceType` or `UnionType` and no `@service` / `@tableMethod` is present.

### Design decision (resolve before coding)

Without a table binding or a method reference, there is no SQL for Graphitron to generate. Two options:

**Option 1 — Reject at classification.** These variants become `UnclassifiedField` when the field has no `@service`. The error message should say: "Multi-table interface/union fields require `@service` for developer-supplied dispatch, or `@table @discriminate` on the interface type for single-table polymorphism." The four variants disappear from the model; their `NOT_IMPLEMENTED_REASONS` entries and `stub(f)` arms are removed.

**Option 2 — Keep variants, improve the stub message.** The fetcher body emits a better `UnsupportedOperationException` message pointing to the two options above. Structurally the same as today but more actionable.

Recommendation: **Option 1.** Consistent with how the classifier already rejects other unsupported patterns. The model variants (`QueryInterfaceField`, etc.) may survive as separate classified paths if `@service` + interface-return becomes an explicit classification in the future.

### TypeResolver wiring for `InterfaceType` / `UnionType`

Even under Option 1, `InterfaceType` and `UnionType` exist in the schema (e.g. the `Node` interface, or user types whose fields are all `@service`-backed). Their TypeResolvers still need wiring.

Pattern: emit `codeRegistry.typeResolver("<Name>", env -> { ... })` for each non-`Node` `InterfaceType` and `UnionType` in `schema.types()`. Use the `__typename` convention: the resolver reads `record.get("__typename")` (same contract as `QueryNodeFetcher.registerTypeResolver`) and calls `env.getSchema().getObjectType(value)`. Document this as a required contract for `@service` methods returning multi-table interface / union types (see `graphitroncontext-extension-point-docs.md`).

---

## Order and gating

Track A is independent of Track B. Ship Track A first.

Within Track A: A2 must land before A3 (record arity change breaks compilation). A4 is independent of A3 but logically belongs in the same PR. A1 can be a separate preparatory commit.

Track B's design decision is a prerequisite for any Track B code.

---

## Non-goals

- Per-participant sub-queries for multi-table interface fetchers without `@service` (requires a new directive or classification path).
- `NodeIdReferenceField` JOIN-projection form (tracked separately under Cleanup).
- TypeResolver for the built-in `Node` interface (already wired via `QueryNodeFetcher.registerTypeResolver`).
