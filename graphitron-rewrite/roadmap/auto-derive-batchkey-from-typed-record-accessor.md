---
id: R60
title: "Auto-derive BatchKey from typed TableRecord accessor on @record parents"
status: Spec
priority: 1
theme: service
depends-on: []
---

# Auto-derive BatchKey from typed TableRecord accessor on @record parents

## Overview

A child field on a `@record`-typed parent (`PojoResultType` / `JavaRecordType` with non-null `fqClassName`) returning a `@table`-bound type is rejected today by `FieldBuilder.classifyChildFieldOnResultType` when the catalog has no FK metadata to derive the batch key from, with a remediation pointing the author at `@batchKeyLifter`. The directive is the right tool when the parent's batch-key value is a *synthetic* tuple the author computes; it is overkill when the parent's backing class already exposes a typed accessor returning the field's records directly (e.g. `LagreKvotesporsmalSvarPayload.getSvar(): List<SoknadKvotesporsmalSvarRecord>` pointing at `KvoteSporsmalSvar` `@table(table: "soknad_kvotesporsmal_svar")`). In that shape every input the classifier needs is already build-time visible: the parent class is reflectable, the accessor's container axis (List / Set / single) and element class (a concrete `TableRecord` subtype) are visible via reflection, and the element's table's PK supplies the target key columns.

This plan adds a third `BatchKey.RecordParentBatchKey` permit, `AccessorRowKeyed`, sibling to `LifterRowKeyed`, that the classifier auto-derives when an accessor match is found. Routing into `RecordTableField` / `RecordLookupTableField` is unchanged; only the key-extraction code differs (call the accessor, project to PK rows). The lifter directive remains the first-class escape hatch for synthetic tuples.

Splitting `LifterRowKeyed` and `AccessorRowKeyed` rather than overloading the former keeps each variant's invariant tight (per *Sealed hierarchies over enums* and *Narrow component types* in `rewrite-design-principles.adoc`): `LifterRowKeyed` always traces back to the directive resolver; `AccessorRowKeyed` always traces back to the auto-derivation in the classifier; `find-usages` on each tells you exactly where its preconditions are checked.

## Current state

`FieldBuilder.classifyChildFieldOnResultType` (the method around L2475 in today's tree; line drift expected, see References) handles `@record`-parent child fields with table-bound returns by:

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

If exactly one such accessor matches, build a `JoinStep.LiftedHop(targetTable = element table, targetColumns = element table PK, alias = fieldName + "_0")` and a `BatchKey.AccessorRowKeyed(hop, accessorRef)` and route into `RecordTableField` / `RecordLookupTableField` as today.

`AccessorRowKeyed` is a new permit of `RecordParentBatchKey`. `buildRecordParentKeyExtraction`'s sealed switch grows a third arm. The DataLoader plumbing is the same column-keyed path used by `RowKeyed` and `LifterRowKeyed` for the **single-element** container case; the **list / set** container cases need a routing decision (see Implementation §3, *Container-axis dispatch*) that this plan resolves explicitly rather than glossing over.

The `@batchKeyLifter` directive continues to work unchanged; nothing in this plan touches `BatchKeyLifterDirectiveResolver`. Authors who already wrote a lifter for shape (b) keep working code; the change only matters going forward, by removing the need to write one.

## What we're NOT doing

- **Free-form DTO parents (shape (a)).** The existing AUTHOR_ERROR remediation continues to fire, with text updated only to reflect the new auto-derivation as a third option ("…back the parent with a typed jOOQ TableRecord, expose a typed accessor returning the child's TableRecord, or supply @batchKeyLifter").
- **`JooqRecordType` / `JooqTableRecordType` parents.** These already get FK-derived batch keys; the new path is unreachable for them. Implementation guards by entering only when `deriveBatchKeyForResultType` returns null, which can only happen on `PojoResultType` / `JavaRecordType`.
- **Multiple matching accessors.** If reflection finds two methods that both satisfy the match rule (e.g. a `getSvar()` and a differently-named accessor for the same element class), reject with AUTHOR_ERROR naming both candidates and asking the author to disambiguate via `@batchKeyLifter`. We will not invent a tie-break.
- **Heterogeneous element types.** If the accessor's element type is a `TableRecord` but does NOT match the field's `@table` return, do not auto-derive. The author may have intended a non-trivial transform; `@batchKeyLifter` is the explicit escape hatch.
- **Inheritance-walking on the parent class.** Match the accessor on the parent class (or its declared supertypes via `Class.getMethods()`, which already walks). No special handling for synthetic / bridge methods beyond `Method.isBridge()` skipping.
- **Cross-cutting BatchKey clean-up.** This plan adds one variant; it does not retune the `RowKeyed`-shared-by-two-sub-hierarchies wrinkle (`RowKeyed` permits both `ParentKeyed` and `RecordParentBatchKey`). That's intentional under *Sealed hierarchies over enums*' "the smell is shared accessors, not shared variants" rule and stays out of scope here.

## Implementation approach

### 1. Add `BatchKey.AccessorRowKeyed`

**File:** `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/BatchKey.java`

a. **New permit** under `RecordParentBatchKey`:

```java
sealed interface RecordParentBatchKey extends BatchKey
        permits RowKeyed, LifterRowKeyed, AccessorRowKeyed {}
```

b. **New record:**

```java
/**
 * Column-based batch key produced when a {@code @record} parent's backing class exposes a
 * typed accessor returning the field's records directly (auto-derived by
 * {@code FieldBuilder.classifyChildFieldOnResultType} when no FK is available in the
 * catalog but the parent class's accessor matches the field's @table return).
 *
 * <p>Drives the same column-keyed DataLoader path as {@link RowKeyed} (single-element
 * accessor) or the mapped path (list / set accessor); only the key-extraction call site
 * (a typed Java accessor on the parent's backing class) and the join-path identity
 * ({@link JoinStep.LiftedHop} instead of {@link JoinStep.FkJoin}) differ from the FK case.
 *
 * <p>Sibling of {@link LifterRowKeyed}: that variant traces back to a {@code @batchKeyLifter}
 * directive resolved in {@code BatchKeyLifterDirectiveResolver}; this variant traces back to
 * the auto-derivation in {@code FieldBuilder.classifyChildFieldOnResultType}. The split keeps
 * each variant's preconditions one find-usages away.
 */
record AccessorRowKeyed(JoinStep.LiftedHop hop, AccessorRef accessor) implements RecordParentBatchKey {

    public List<ColumnRef> targetKeyColumns() { return hop.targetColumns(); }

    @Override
    public String javaTypeName() {
        return containerType("List", "Row", hop.targetColumns());
    }
}
```

c. **New `AccessorRef`** (own file, `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/AccessorRef.java`):

```java
record AccessorRef(
    ClassName parentBackingClass,
    String methodName,
    Container container,
    ClassName elementClass) {

    enum Container { SINGLE, LIST, SET }
}
```

`Container` is an enum (not a sealed hierarchy) because the three cases share the same data; only the codegen branches differ. Per *Sealed hierarchies over enums*, an enum is correct here: same shape, no per-variant data.

### 2. Extend the classifier with the auto-derivation step

**File:** `graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java`

a. **In `classifyChildFieldOnResultType`'s `TableBoundReturnType` arm**, between the FK-derivation null-check and the existing AUTHOR_ERROR rejection (currently around L2585-L2596 in today's tree), insert the accessor-derivation attempt:

```java
var batchKey = deriveBatchKeyForResultType(objectPath.elements(), parentResultType);
if (batchKey == null) {
    // FK metadata absent. Try to auto-derive from a typed accessor on the parent's
    // backing class before falling back to the @batchKeyLifter remediation.
    var accessorBk = deriveBatchKeyFromTypedAccessor(name, fieldDef, parentResultType, tb.table());
    if (accessorBk instanceof AccessorDerivation.Ok ok) {
        batchKey = ok.batchKey();
    } else if (accessorBk instanceof AccessorDerivation.Ambiguous a) {
        yield new UnclassifiedField(parentTypeName, name, location, fieldDef,
            RejectionKind.AUTHOR_ERROR,
            "@record parent '" + parentClassName(parentResultType) + "' exposes more than one "
                + "typed accessor returning '" + tb.table().tableName() + "' records: ["
                + a.candidates() + "]. Disambiguate by adding @batchKeyLifter(...).");
    }
    // AccessorDerivation.None falls through to the existing AUTHOR_ERROR below.
}
if (batchKey == null) {
    yield new UnclassifiedField(parentTypeName, name, location, fieldDef,
        RejectionKind.AUTHOR_ERROR,
        "RecordTableField on a free-form DTO parent requires a typed accessor or "
            + "@batchKeyLifter to lift the batch key; the catalog has no FK metadata for "
            + "the parent class. Either expose a typed accessor on the parent returning "
            + "List<...Record>, Set<...Record>, or ...Record (where ...Record is the "
            + "element type's jOOQ TableRecord); or add @batchKeyLifter(lifter: ..., "
            + "targetColumns: [...]); or back the parent with a typed jOOQ TableRecord "
            + "so the FK can be derived");
}
```

The same insertion applies to the `RecordLookupTableField` branch immediately above (lookup variant); both call `deriveBatchKeyForResultType` and have the same null-rejection. Lift the new derivation call to a single shared snippet rather than duplicating.

b. **`deriveBatchKeyFromTypedAccessor` helper** (new, on `FieldBuilder`):

- Resolve the parent backing class via `parentResultType` switch (`PojoResultType.fqClassName()` / `JavaRecordType.fqClassName()`); return `AccessorDerivation.None` if the class is not load-able (already a precondition for the rejection path, so this is the same branch).
- Iterate `parentClass.getMethods()`. For each method `m`:
  - Skip if `m.isBridge()` or `m.isSynthetic()`.
  - Skip if `m.getParameterCount() != 0` or `Modifier.isStatic(m.getModifiers())`.
  - Compute the candidate names that match: `m.getName().equals(fieldName)`, or `m.getName().equals("get" + ucFirst(fieldName))`, or `m.getName().equals("is" + ucFirst(fieldName))` (the standard JavaBean / record-accessor set; matches what the existing `RowKeyed` extraction already assumes for `PojoResultType`).
  - If the field name doesn't match any of those, continue.
  - Classify the return type via a small `classifyAccessorReturn(Type returnType)` helper that mirrors `ServiceCatalog.classifySourcesType`'s container-and-element walk:
    - `List<E>` where `E` is a concrete `Class<?>` extends `TableRecord` → `(Container.LIST, E)`.
    - `Set<E>` similarly → `(Container.SET, E)`.
    - Bare `E` extending `TableRecord` → `(Container.SINGLE, E)`.
    - Otherwise reject (continue to next method).
  - Compare element class's mapped table against `tb.table()`. The mapped table for a generated jOOQ `TableRecord` subtype is reachable as `((TableRecord) recordClass.getDeclaredConstructor().newInstance()).getTable()`, but invoking the constructor for matching is wasteful. Instead, look up by static field: jOOQ's generated `Tables.<TABLE_NAME>` is the canonical anchor, but the existing `BuildContext` already holds a `Map<Class<?>, TableRef>` keyed by `TableRecord` Class (used by other classifier sites). Confirm during implementation that this map is already accessible from `FieldBuilder`; if not, lift a small helper rather than reflecting on jOOQ internals here.
  - On match: collect a candidate. Continue iterating to detect ambiguity.
- After the loop:
  - 0 candidates → `AccessorDerivation.None`.
  - 1 candidate → build `JoinStep.LiftedHop(targetTable = tb.table(), targetColumns = tb.table().primaryKeyColumns(), alias = name + "_0")` and `AccessorRowKeyed(hop, AccessorRef(...))`; return `AccessorDerivation.Ok(batchKey)`.
  - 2+ candidates → `AccessorDerivation.Ambiguous(candidatesString)`.

`AccessorDerivation` is a small sealed interface local to `FieldBuilder` (a builder-internal sealed hierarchy per the principles doc, §"Builder-step results are sealed") with `Ok` / `None` / `Ambiguous` arms.

c. **Element-class → `TableRef` lookup.** During implementation, audit `ServiceCatalog` and `BuildContext` for an existing `Class<? extends TableRecord> → TableRef` resolution helper. If one exists (likely yes; service-classification crosses the same line), reuse it. If not, add one to `BuildContext` and route both call sites through it; do not duplicate the logic.

### 3. Container-axis dispatch in `buildRecordParentKeyExtraction`

**File:** `graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/GeneratorUtils.java`

The existing two-arm switch becomes a three-arm switch; the third arm forks on `AccessorRef.container()`:

```java
return switch (batchKey) {
    case BatchKey.RowKeyed rk            -> buildFkRowKey(rk, keyType, resultType, jooqPackage);
    case BatchKey.LifterRowKeyed lrk     -> buildLifterRowKey(lrk, keyType, resultType);
    case BatchKey.AccessorRowKeyed ark   -> buildAccessorRowKey(ark, keyType, resultType);
};
```

`buildAccessorRowKey` emits one of three shapes based on `ark.accessor().container()`:

- **SINGLE**: emit a single key:
  ```
  ElementRecord __elt = ((BackingClass) env.getSource()).<accessor>();
  RowN<...> key = DSL.row(__elt.<pk1>(), __elt.<pk2>());
  ```
  Drives `loader.load(key, env)` (existing `buildRecordBasedDataFetcher` dispatch; no change).

- **LIST / SET**: emit many keys:
  ```
  List<RowN<...>> keys = ((BackingClass) env.getSource()).<accessor>().stream()
      .map(__elt -> DSL.<RowN<...>>row(__elt.<pk1>(), __elt.<pk2>()))
      .toList();   // .collect(Collectors.toSet()) for SET
  ```
  Requires a dispatch change (see §3.b).

b. **Dispatch change in `TypeFetcherGenerator.buildRecordBasedDataFetcher`** (around L2284-L2320).

Today: `loader.load(key, env)` returns `CompletableFuture<List<Record>>` (list field) or `CompletableFuture<Record>` (single field). The lambda contract is: the keys arriving in `(List<RowN> keys, batchEnv) -> rowsMethod(keys, dfe)` are one-per-parent.

For accessor-derived **list / set** containers, *one parent contributes N keys*. Two routing options:

- **Option A, `loadMany` per parent.** The DataFetcher emits `loader.loadMany(keys, env)` (where `keys` is the local `List<RowN<...>>` from §3.a's list/set arm) instead of `loader.load(key, env)`. Crucially, **`V` for the loader becomes `Record` (singular), not `List<Record>`**: each element-PK key maps to exactly one element record, and `loadMany` returns `CompletableFuture<List<V>>` = `CompletableFuture<List<Record>>` directly, which already matches the list-field shape. The existing `valueType = isList ? List<Record> : Record` shortcut in `buildRecordBasedDataFetcher` (which conflates field-cardinality with per-key cardinality, safe today because FK keying is one-per-parent) needs to fork on the new dispatch: for the `AccessorRowKeyed` LIST/SET arm, `valueType = Record` regardless of `isList`. The rows-method's batching contract is unchanged: it still receives a `List<RowN>` (the union across parents) and returns one record per key.
- **Option B, synthesise a parent-id key.** The parent contributes one synthetic Row1 (e.g. `Row1<Integer>` of `System.identityHashCode(parent)`); the rows-method receives the list of synthetics and looks back up. This is a non-starter; the rows-method doesn't see the original parents. Not an option.

**Decision: Option A.** The DataFetcher dispatch becomes:

```java
boolean usesLoadMany =
    batchKey instanceof BatchKey.AccessorRowKeyed ark
    && ark.accessor().container() != AccessorRef.Container.SINGLE;
String dispatchCall = usesLoadMany
    ? "return loader.loadMany(keys, env)\n"
    : "return loader.load(key, env)\n";
```

The keying-statement variable name (`key` vs `keys`) is set by `buildRecordParentKeyExtraction`'s third arm; the dispatch reads the variable by name. `asyncWrapTail` already produces the right async wrap for both branches once `valueType` is set per the rule above. **For single fields with a list/set accessor: that combination is nonsensical (a single child cannot come from a list of element keys) and the classifier rejects it earlier** (single child + list accessor = element-class match against `tb.table()` would still pass shape-check but the cardinality is wrong; call this out at the classifier step §2.b: only allow LIST / SET accessors when `field.returnType().wrapper().isList()`, only allow SINGLE accessor when not).

c. **Constraint summary** for the implementer: container-axis × field-cardinality is a 2×2; only two of the four combinations are legal:

| Field cardinality        | SINGLE accessor | LIST / SET accessor |
|--------------------------|-----------------|---------------------|
| List (`[Type]`)          | reject          | accept              |
| Single (`Type`)          | accept          | reject              |

The classifier enforces both rejections explicitly (separate AUTHOR_ERROR messages) before constructing the `AccessorRowKeyed`. This is a §2.b loop addition, not a §3 codegen concern.

### 4. Wire the new permit into the resolver routing audit

**File:** `graphitron/src/main/java/no/sikt/graphitron/rewrite/BatchKeyLifterDirectiveResolver.java`

Update the class-level Javadoc note about *paired classifier checks*: the `lifter-batchkey-is-lifterrowkeyed` check still holds (the lifter path produces `LifterRowKeyed`, never `AccessorRowKeyed` or `RowKeyed`); the `RecordParentBatchKey` parameter type on `buildRecordParentKeyExtraction` is still the right narrowing. No code change here, just a doc-comment refresh so the next reader sees the three-permit hierarchy reflected.

### 5. Update the rejection message at the existing FK-only failure paths

**File:** `graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java` (the messages quoted in §1 of *Current state*).

Both messages (RecordTableField and RecordLookupTableField branches) get the third option appended: "…or expose a typed accessor on the parent returning `List<...Record>` / `Set<...Record>` / `...Record` (where `...Record` is the element type's jOOQ TableRecord subtype) so the batch key can be derived from the parent's typed Java shape".

## Tests

### Unit tests

**`BatchKeyTest`** (existing or new).

- `AccessorRowKeyed.javaTypeName()` for one-column PK: `"java.util.List<org.jooq.Row1<java.lang.Long>>"`.
- `AccessorRowKeyed.javaTypeName()` for two-column composite PK.
- `AccessorRowKeyed.targetKeyColumns()` returns the same instance as `hop.targetColumns()` (single source of truth, mirrors `LifterRowKeyed`'s own contract).
- The sealed switch over `RecordParentBatchKey` is exhaustive: a small test that switches on a `RecordParentBatchKey` value with `default -> fail()` must not fall through for any concrete instance; verifies the compiler's exhaustiveness check is the gate.

### Pipeline tests

**`GraphitronSchemaBuilderTest`**: five new cases covering the cross-product corners:

1. `ACCESSOR_ROWKEYED_LIST_FIELD_LIST_ACCESSOR`: `[KvoteSporsmalSvar]` on a `@record` parent whose backing class has `getSvar(): List<SoknadKvotesporsmalSvarRecord>`. Expect `RecordTableField` with `batchKey instanceof AccessorRowKeyed ark && ark.accessor().container() == LIST`.
2. `ACCESSOR_ROWKEYED_SINGLE_FIELD_SINGLE_ACCESSOR`: single child `Type` on a record parent with `getOwner(): UserRecord`. Expect `RecordTableField` with `AccessorRowKeyed.container == SINGLE`.
3. `ACCESSOR_ROWKEYED_REJECTS_AMBIGUOUS`: record parent with two accessors both returning `List<XRecord>` for the same `@table` X. Expect `UnclassifiedField` with the ambiguity message naming both accessors.
4. `ACCESSOR_ROWKEYED_REJECTS_CARDINALITY_MISMATCH`: list field with single accessor (and / or single field with list accessor). Expect AUTHOR_ERROR with the cardinality-mismatch text.
5. `ACCESSOR_ROWKEYED_REJECTS_HETEROGENEOUS_ELEMENT`: accessor returns a `TableRecord` whose mapped table is *not* the field's `@table`. Expect AUTHOR_ERROR (falls through to the existing message with the third option appended).

Per the testing-tier rules in `rewrite-design-principles.adoc`, none of these assert on generated code strings; they assert on the classified `BatchKey` variant's identity and component values, plus the `UnclassifiedField` message text.

### Compilation tests

**`GraphitronCompilationTest`**: extend the existing fixtures schema with the two accepting cases (list-list and single-single) and confirm the generated DataFetcher / rows-method compiles. The compilation tier validates that `buildRecordParentKeyExtraction`'s third arm and the `buildRecordBasedDataFetcher` `loadMany` branch produce well-typed code under `<release>17</release>`.

### Execution tests

**`GraphQLQueryTest`** in `graphitron-test`. Fixtures need a payload-shape parent and a list-of-children accessor.

- **Fixture suggestion (concrete):** add a mutation `lagreTodos(input: ...): LagreTodosPayload` whose payload is `LagreTodosPayload(todos: List<TodoRecord>)` (mirrors the in-the-wild `LagreKvotesporsmalSvarPayload.svar` shape). The schema field `todos: [Todo]` returns `@table(table: "todo")`; the payload class exposes `getTodos(): List<TodoRecord>`. Verify a mutation call returns the inserted rows projected through the regular Todo selection set, including any of Todo's own `@table` children.
- **Single-accessor fixture:** add a `getOwner(): UserRecord` accessor on a smaller payload with `owner: User` (single). Verify the resolver returns the user.

The fixture lives in `graphitron-test/src/main/resources/graphql/schema.graphqls` plus the `graphitron-fixtures-codegen` payload class. Use the same tier as R23's two-parent execution test.

*Before landing:* confirm the payload-class shape compiles in `graphitron-fixtures-codegen` (a Java-25 module emitting Java-17-compatible code per `CLAUDE.md`'s release-target note), and that `-Plocal-db` is included in every `mvn install` run during development (per the fixtures-jar clobber footgun).

## Success criteria

### Automated

- `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` passes; includes the new pipeline / compilation / execution cases.
- The five new pipeline-test cases pass with the expected `BatchKey` variant identities and AUTHOR_ERROR texts.
- The two new execution-test cases return the correct rows.
- The classifier sealed-switch exhaustiveness over `RecordParentBatchKey` is verified at compile time (any caller who misses the `AccessorRowKeyed` arm fails to compile).

### Manual

- The in-the-wild `LagreKvotesporsmalSvarPayload.svar` rejection in `sis-graphql-spec` no longer fires when re-run against the new classifier; the field classifies as `RecordTableField` with `AccessorRowKeyed`.
- A repo-wide grep for the old "RecordTableField on a free-form DTO parent requires @batchKeyLifter" string returns zero matches; the updated three-option message replaces both occurrences.

## References

Identifier-level references; line numbers drift (sibling plans have been bitten; the current description's `:2542-2545` already drifted to `:2585-L2596` since R60 was filed). Re-anchor by name during implementation:

- Classifier rejection site: `FieldBuilder.classifyChildFieldOnResultType`'s `TableBoundReturnType` arm, both the plain and lookup branches.
- FK-derivation helper to extend with the accessor fallback: `FieldBuilder.deriveBatchKeyForResultType`.
- Mirror for the reflection walk: `ServiceCatalog.classifySourcesType` (container-and-element classification).
- Codegen switch to extend: `GeneratorUtils.buildRecordParentKeyExtraction` (the two-arm sealed switch); paired with `buildRecordBasedDataFetcher` in `TypeFetcherGenerator` (the `loader.load` / `loader.loadMany` dispatch).
- Sealed taxonomy doc to extend: `BatchKey` (top-level sealed interface and `RecordParentBatchKey` permits clause); `LifterRowKeyed` is the structural template.
- Principles cross-refs: *Sealed hierarchies over enums for typed information* and *Narrow component types over broad interfaces* in `graphitron-rewrite/docs/rewrite-design-principles.adoc`.
- Related (no overlap): `BatchKeyLifterDirectiveResolver` (the existing lifter-directive path); R36-era composite-PK plumbing (the multi-column PK case for the new accessor path uses the same `ColumnRef` list shape).
