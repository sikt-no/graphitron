---
id: R1
title: "`BatchKey` lifter directive"
status: Spec
bucket: architecture
priority: 1
theme: service
depends-on: []
---

# `BatchKey` lifter directive

> Re-enable DataLoader batching on `@record` parents that lack a database-resolvable
> FK to the child field's `@table`. The schema author supplies a static Java method
> that lifts a batch-key `RowN` out of the parent DTO; the classifier reflects on it,
> the emitter feeds it into the existing `BatchKey.RowKeyed` path with a synthetic
> first-hop `FkJoin`. Co-closes the `RecordTableField` / `RecordLookupTableField`
> "missing FK join path and a typed backing class" rejection at
> [`FieldBuilder.java:1900`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java)
> and [`:1907`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java).

---

## Motivation

Mutation Payloads, plain DTOs returned by `@service` queries, and any other
`@record`-typed object whose backing class is not a jOOQ `TableRecord` cannot host
batch-loaded child fields today. The canonical hit is a Sikt-side schema like:

```graphql
type SettKvotesporsmalAlgoritmePayload @record(record: {className: "no.example.SettKvotesporsmalAlgoritmePayload"}) {
    kvotesporsmal: Kvotesporsmal!     # @table-bound; rejected today
    errors: [SettKvotesporsmalAlgoritmeError!]
}
```

`SettKvotesporsmalAlgoritmePayload` is a developer-authored POJO with no jOOQ
catalog entry. The `kvotesporsmal` field's classifier path lands at
`FieldBuilder.classifyChildFieldOnResultType` →
`deriveBatchKeyForResultType(joinPath, parentResultType)`
([`:1973-1982`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java)).
That helper requires the join path's first hop to be a `JoinStep.FkJoin` so it can
read `fkJoin.sourceColumns()` as the parent-side key columns. With no FK in the
catalog, `parsePath` either fails outright or produces an empty / non-FK first
hop, and the field downgrades to `UnclassifiedField` with the deferred reason
quoted in the title.

The legacy generator handled this case via `BatchKey.ObjectBased`, a free-form
arm that bypassed the column-keyed DataLoader path entirely. It was removed in
the `batchkey-remove-objectbased` landing (see
[`changelog.md`](changelog.md) line 12); the rejection text explicitly points
here for the replacement. This item is the replacement.

The existing column-keyed DataLoader path (`BatchKey.RowKeyed` →
`SplitRowsMethodEmitter.buildListMethod` → `DataLoaderFactory.newDataLoader`)
already does everything we need *if* the classifier can produce a `RowKeyed`
batch key and a synthetic first-hop `FkJoin`. The plan is to give the schema
author a directive that supplies both.

---

## Surface

### Directive

Added to [`directives.graphqls`](../graphitron/src/main/resources/no/sikt/graphitron/rewrite/schema/directives.graphqls):

```graphql
"""
Supplies a DTO-to-batch-key lifting function for a child field whose @record
parent has no FK metadata in the jOOQ catalog (i.e. the parent's backing class
is not a jOOQ TableRecord, or the parent type carries no @record class at all).

The lifter is a static Java method that extracts the batch-key value(s) from
the parent instance. The lifted RowN is matched against the named target
columns on the field's @table return type to drive a column-keyed DataLoader.

Required when a field on a non-table-backed @record parent returns a @table
type and would otherwise be rejected as 'RecordTableField (or
RecordLookupTableField) requires a FK join path and a typed backing class for
batch key extraction'. Has no effect on @table parents (use @reference there)
and no effect on JooqTableRecordType parents (the catalog FK already drives
batching).
"""
directive @batchKeyLifter(
  """
  Static Java method (className, method) that takes the parent's backing class
  and returns a jOOQ Row1..RowN. The arity must equal targetColumns.size();
  each RowN type argument must equal the corresponding targetColumn's Java
  column class.
  """
  lifter: ExternalCodeReference!

  """
  SQL names of columns on the field's @table return type. Lifted RowN values
  match these columns positionally; the rows-method emitter uses them as the
  JOIN target on a synthetic first-hop FkJoin.
  """
  targetColumns: [String!]!
) on FIELD_DEFINITION
```

`ExternalCodeReference` is the existing nested-input type already used by
`@service`, `@tableMethod`, `@condition`, and `@enum`; reusing it keeps the
classifier's `Class.forName` + method-lookup path uniform.

### Example

```graphql
type SettKvotesporsmalAlgoritmePayload @record(record: {className: "no.example.SettKvotesporsmalAlgoritmePayload"}) {
    kvotesporsmal: Kvotesporsmal! @batchKeyLifter(
        lifter: {className: "no.example.PayloadKeys", method: "kvotesporsmalKey"},
        targetColumns: ["kvotesporsmal_id"]
    )
}
```

```java
package no.example;
import org.jooq.Row1;
import static org.jooq.impl.DSL.row;

public final class PayloadKeys {
    public static Row1<Long> kvotesporsmalKey(SettKvotesporsmalAlgoritmePayload p) {
        return row(p.getKvotesporsmalId());
    }
}
```

The directive is applied per child field. Different fields on the same payload
may target different tables and supply different lifters. There is no
type-level form: most payloads have only one or two batch-loaded children, and
a per-field directive avoids inventing a second mapping language for the
"which lifter for which field" relationship.

### What is *not* in scope

- `@table` parents (rejection arms at `FieldBuilder.java:2015` for
  `@service` polymorphic returns; the FK on the parent table already
  classifies). The directive is rejected on a `@table`-parent field with an
  AUTHOR_ERROR pointing at `@reference`.
- Plain Java records (`JavaRecordType` parent, e.g. `record FilmDto(...)`)
  with a resolvable FK already work. The directive is rejected on those with
  the same AUTHOR_ERROR pointer.
- `JooqTableRecordType` parents (the catalog FK is authoritative). Same
  rejection.
- The directive on a non-`TableBoundReturnType` field. `RecordField` /
  `PropertyField` (scalar-or-record returns) do not batch via DataLoader; the
  directive has no semantics there. AUTHOR_ERROR.
- The five `@service` polymorphic-return rejections lifted by R12
  (`error-handling-parity`); those are about typed errors, not DTO batching.

---

## Invariants

1. **Application context.** `@batchKeyLifter` may appear only on a child field
   whose:
   - parent classifies as `GraphitronType.PojoResultType` with a non-null
     `fqClassName`, **and**
   - return type resolves to `ReturnTypeRef.TableBoundReturnType`.

   Other shapes (table parent, record-with-FK, scalar return, polymorphic
   return) are rejected with AUTHOR_ERROR. Untyped `PojoResultType` (null
   `fqClassName`) is also rejected: without a class to load, there's nothing
   to reflect against. Classifier message:
   `"@batchKeyLifter on '<parent>.<field>' requires the @record parent to
   declare a backing class via @record(record: {className: ...})"`.

2. **Lifter resolution.** `lifter.className` and `lifter.method` must both be
   non-null. The class must load via `Class.forName`; a single static method
   with that name must exist; its single parameter type must be assignable
   from the parent's `fqClassName`. Each failure produces a precise
   AUTHOR_ERROR borrowing the exact message shapes already used by
   `ServiceCatalog.reflectServiceMethod`
   ([`ServiceCatalog.java:154-227`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/ServiceCatalog.java)),
   so error vocabulary stays uniform across `@service`, `@tableMethod`, and
   the new directive.

3. **RowN return-type contract.** The lifter's reflected return type must be
   `org.jooq.Row1` through `org.jooq.Row22` (jOOQ's `Row<N>` ceiling).
   Returning a raw key column type (e.g. `Long`) is *not* admitted: the rest
   of the pipeline already uses `RowN` uniformly, and admitting bare scalars
   would force a one-arm sentinel through `BatchKey`, `keyElementType`,
   `buildKeyExtraction`, the rows-method JOIN, and the test taxonomy. A
   `Row1<Long>` lifter expresses the single-column case cleanly. AUTHOR_ERROR
   on any other return type:
   `"lifter method '<name>' must return org.jooq.Row1..Row22; got '<actual>'"`.

4. **Arity and column-type match.** The `RowN` arity must equal
   `targetColumns.size()`. Each `RowN` type argument must equal the
   corresponding target `ColumnRef.columnClass()`. Mismatch produces
   AUTHOR_ERROR with the offending position called out:
   `"lifter '<name>' Row<N> arity 3 does not match targetColumns size 2"`,
   or
   `"lifter '<name>' RowN type at position 1 ('java.lang.Long') does not
   match target column 'film_id' Java type ('java.lang.Integer')"`.

5. **Target column resolution.** Each entry in `targetColumns` must resolve to
   a real column on the field's `@table` return type. Resolution uses the
   same `JooqCatalog` lookup paths the rest of the classifier already uses for
   `@field(name:)`. AUTHOR_ERROR on miss:
   `"target column 'X' not found on table '<sql>'"` plus the existing
   candidate-hint suggestion (`BuildContext.candidateHint`).

6. **`targetColumns` non-empty.** A zero-arity lifter is meaningless (no
   batching axis). AUTHOR_ERROR.

7. **Cardinality coverage.** Both single (`T`) and list (`[T]`) returns are
   admitted. The directive does not change the cardinality contract: the
   field's wrapper drives whether the rows-method emits a list-collecting
   query or a single-row terminal join, exactly as for catalog-FK
   `RecordTableField` today. (`SplitRowsMethodEmitter.unsupportedReason` for
   `RecordTableField` rejects single-cardinality with a "not yet supported"
   stub at
   [`SplitRowsMethodEmitter.java:330-333`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/SplitRowsMethodEmitter.java);
   that stub stays in place and the lifter path inherits it.)

8. **`@lookupKey` interaction.** The classifier branch at
   [`FieldBuilder.java:1897-1903`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java)
   produces `RecordLookupTableField` when any argument carries `@lookupKey`.
   The lifter directive is orthogonal: a lifted-key `RecordLookupTableField`
   classifies the same way; only the synthetic first-hop FkJoin and the
   key-extraction call site change. No new variant is needed; the existing
   `RecordLookupTableField` accepts the new `BatchKey.LifterRowKeyed` (§Model
   below) without further work.

9. **Connection wrapper rejection.** A field declared as
   `@asConnection` cannot use the lifter directive. The connection
   classifier expands to `Connection<T>` and routes through pagination
   helpers that don't share the rows-method DataLoader path. AUTHOR_ERROR:
   `"@batchKeyLifter is not supported on @asConnection fields"`.

---

## Model

### `BatchKey.LifterRowKeyed`

Add a fifth permit on the sealed `BatchKey` interface
([`BatchKey.java`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/model/BatchKey.java)):

```java
record LifterRowKeyed(
    List<ColumnRef> keyColumns,    // target-side columns from targetColumns:
    MethodRef lifterMethod,        // resolved Class.method, single parent param
    String parentBackingClassFqn   // fqClassName of the parent PojoResultType
) implements BatchKey {
    @Override
    public String javaTypeName() {
        return containerType("List", "Row", keyColumns);
    }
}
```

`keyColumns` are *target-table* columns (not parent-side, since the parent has
no jOOQ catalog presence). `javaTypeName` reuses the same `RowN` envelope as
`RowKeyed`: the DataLoader key type is identical, only the extraction site
differs. `parentBackingClassFqn` is captured so the emitter can synthesize the
typed cast `((BackingClass) env.getSource())` without re-resolving the
parent's `ResultType` at emission time.

The four existing variants (`RowKeyed`, `RecordKeyed`, `MappedRowKeyed`,
`MappedRecordKeyed`) are untouched. Adding `LifterRowKeyed` requires updating
the four switches in
[`GeneratorUtils.keyElementType`, `buildKeyExtraction`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/GeneratorUtils.java)
and
[`TypeFetcherGenerator.buildServiceDataFetcher`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java).
`keyElementType` adds a single arm reusing `buildRowKeyType`; the others
either fall through with `RowKeyed` (mapped/non-lifter sites) or branch to a
new `buildLifterKeyExtraction` (key-extraction sites; see Phase 2).

### Synthetic `JoinStep.FkJoin`

`SplitRowsMethodEmitter.buildListMethod` reads `joinPath` and emits a JOIN
against the target table on the FK columns. With no real catalog FK, the
classifier synthesizes one
([`JoinStep.java:99-108`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/model/JoinStep.java)):

```java
new JoinStep.FkJoin(
    /* fkName        */ "<synthetic-batchkey-lifter>",
    /* fkJavaConstant*/ "",                       // empty: no Keys constant
    /* originTable   */ TableRef.empty(),         // no parent catalog table
    /* sourceColumns */ targetColumns,            // synthetic: same columns both sides
    /* targetTable   */ field.returnType().table(),
    /* targetColumns */ targetColumns,
    /* whereFilter   */ null,
    /* alias         */ field.name() + "_0"       // matches the existing alias scheme
)
```

`sourceColumns == targetColumns` is the key trick: the rows-method emitter
already builds the JOIN as `target_alias.<targetColumns[i]> = values.<sourceColumns[i]>`,
and on the lifted-key path both sides naturally carry the same columns (the
DataLoader key *is* the target-column tuple). `originTable` empty is
acceptable because no path-direction validation runs on a synthetic step: the
emitter consumes the columns directly and never asks "which side holds the
FK?".

The classifier's path-walker (`BuildContext.parsePath`) is bypassed for
lifter-directived fields: `parsePath` searches the catalog for FK matches and
rejects unresolvable hops. With the lifter present we know the schema author
has supplied the keying contract directly, so we skip the walker and inject
the synthetic step. The branch lives behind the new `hasBatchKeyLifter`
predicate in `FieldBuilder.classifyChildFieldOnResultType`; downstream code
sees a normal `joinPath = [FkJoin]` and continues.

---

## Plan

### Phase 1 — Model and directive plumbing

**Goal:** parse the directive, resolve the lifter, produce the new
`BatchKey.LifterRowKeyed` and synthetic `FkJoin`. No emission changes.

#### 1a. `BatchKey.LifterRowKeyed` permit

Add the new permit on `BatchKey` per §Model. Cover the new arm in
`BatchKey`'s `containerType` reuse (already exhaustive once the permit is
added).

#### 1b. Directive constants

In the SDL constants file (`Directives.java` or wherever
`DIR_SERVICE`/`DIR_TABLE`/etc. live; the FieldBuilder imports list at
[`FieldBuilder.java:1-80`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java)
is the discovery point), add:

```java
public static final String DIR_BATCH_KEY_LIFTER = "batchKeyLifter";
public static final String ARG_LIFTER = "lifter";
public static final String ARG_TARGET_COLUMNS = "targetColumns";
```

Reuse the existing `ARG_CLASS_NAME` / `ARG_METHOD` from
`ExternalCodeReference` consumers (the constants are already declared for
`@service`).

#### 1c. SDL update

Add the directive declaration to
[`directives.graphqls`](../graphitron/src/main/resources/no/sikt/graphitron/rewrite/schema/directives.graphqls)
verbatim per §Surface. Place it after `@reference` /
`@multitableReference` to group with other path-shaping directives.

#### 1d. `FieldBuilder.classifyChildFieldOnResultType` extension

Inside the existing object-return arm at
[`FieldBuilder.java:1873-1919`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java),
add a `hasBatchKeyLifter` branch ahead of the existing `parsePath` call:

```java
boolean hasLifter = fieldDef.hasAppliedDirective(DIR_BATCH_KEY_LIFTER);
if (hasLifter) {
    var lifterResult = resolveBatchKeyLifter(parentTypeName, fieldDef,
            parentResultType, /* targetSqlTableName */ targetSqlTableName);
    if (lifterResult.error() != null) {
        return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                RejectionKind.AUTHOR_ERROR, lifterResult.error());
    }
    var batchKey = lifterResult.batchKey();
    var joinPath = List.of((JoinStep) lifterResult.syntheticFkJoin());
    var tfc = resolveTableFieldComponents(fieldDef, lifterResult.targetTable(), elementTypeName);
    if (tfc.error() != null) {
        return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                RejectionKind.AUTHOR_ERROR, tfc.error());
    }
    if (hasLookupKeyAnywhere(fieldDef)) {
        return new RecordLookupTableField(parentTypeName, name, location,
                lifterResult.tbReturnType(), joinPath,
                tfc.filters(), tfc.orderBy(), tfc.pagination(),
                batchKey, tfc.lookupMapping());
    }
    return new RecordTableField(parentTypeName, name, location,
            lifterResult.tbReturnType(), joinPath,
            tfc.filters(), tfc.orderBy(), tfc.pagination(),
            batchKey);
}
```

The non-lifter path (existing code at `:1885-1919`) is unchanged. The branch
order matters: `hasBatchKeyLifter` runs first so the AUTHOR_ERROR cases for
the directive on a wrong parent shape (`@table`, `JooqTableRecordType`,
`JavaRecordType`) surface with the directive-specific message rather than the
generic missing-FK rejection. Implement those rejections inside
`resolveBatchKeyLifter` per Invariant #1.

#### 1e. `resolveBatchKeyLifter` helper

A new private method on `FieldBuilder`:

```java
private BatchKeyLifterResult resolveBatchKeyLifter(
        String parentTypeName, GraphQLFieldDefinition fieldDef,
        ResultType parentResultType, String targetSqlTableName) {
    // 1. Parent shape: must be PojoResultType with non-null fqClassName.
    //    (§Invariants 1.) If wrong shape, return error with AUTHOR_ERROR
    //    pointing at the alternative directive (@reference for @table parents,
    //    catalog FK for JooqTableRecordType, etc.).
    // 2. targetColumns: non-empty list (§Invariants 6); each resolves on the
    //    target table (§Invariants 5). Use BuildContext.candidateHint for misses.
    // 3. lifter: className + method via ExternalCodeReference. Class.forName,
    //    locate the static method, verify single parameter assignable from
    //    parent fqClassName, verify return type is org.jooq.RowN
    //    (§Invariants 2-3).
    // 4. Arity + column-class match (§Invariants 4).
    // 5. Build BatchKey.LifterRowKeyed and JoinStep.FkJoin per §Model.
    // 6. Resolve ReturnTypeRef.TableBoundReturnType for the field's @table return.
}

private record BatchKeyLifterResult(
    BatchKey.LifterRowKeyed batchKey,
    JoinStep.FkJoin syntheticFkJoin,
    TableRef targetTable,
    ReturnTypeRef.TableBoundReturnType tbReturnType,
    String error
) {}
```

The class loader / method walker borrows the existing
`ServiceCatalog.reflectServiceMethod` shape but is simpler: only one parameter
to classify, no `Sources`/`Arg`/`Context` slot detection, no
`expectedReturnType` strict-equality (the return-type check is the RowN
discriminator). Place the helper on `ServiceCatalog` if the method-resolution
machinery (e.g. `Class.forName` exception handling, the candidate-hint format
on missing methods) is reusable; otherwise inline it on `FieldBuilder`. First
implementation should inline; lift to `ServiceCatalog` if R6
(`decompose-fieldbuilder`) extracts a shared resolver later.

#### 1f. Pipeline tests

`GraphitronSchemaBuilderTest` additions covering the classifier paths. Each
row asserts the produced field variant and (where applicable) the rejection
message. Place them adjacent to the existing `RecordTableField` cases at
[`GraphitronSchemaBuilderTest.java:1415-1478`](../graphitron/src/test/java/no/sikt/graphitron/rewrite/GraphitronSchemaBuilderTest.java).

| SDL shape | Expected outcome |
|---|---|
| Pojo parent + `@batchKeyLifter` valid Row1, list return | `RecordTableField` with `BatchKey.LifterRowKeyed`, synthetic `FkJoin` first-hop |
| Pojo parent + `@batchKeyLifter` valid Row2 (composite key), list return | same; `keyColumns` arity 2 |
| Pojo parent + `@batchKeyLifter` + `@lookupKey` argument, list return | `RecordLookupTableField` with `BatchKey.LifterRowKeyed`, lookup mapping populated |
| Pojo parent + `@batchKeyLifter`, single-cardinality return | `RecordTableField` (classifies); `SplitRowsMethodEmitter.unsupportedReason` stub fires at emission (existing behaviour, not in scope here) |
| Pojo parent (null `fqClassName`) + `@batchKeyLifter` | `UnclassifiedField` per Invariant #1 |
| `@table` parent + `@batchKeyLifter` | `UnclassifiedField` AUTHOR_ERROR pointing at `@reference` |
| `JooqTableRecordType` parent + `@batchKeyLifter` | `UnclassifiedField` AUTHOR_ERROR pointing at catalog FK |
| `JavaRecordType` parent + `@batchKeyLifter` | `UnclassifiedField` AUTHOR_ERROR (catalog FK governs) |
| Pojo parent + `@batchKeyLifter` on a scalar-return field | `UnclassifiedField` AUTHOR_ERROR (directive on non-`TableBoundReturnType`) |
| `@batchKeyLifter` with missing class | `UnclassifiedField` with `ServiceCatalog`-style "class … could not be loaded" |
| `@batchKeyLifter` with missing method | `UnclassifiedField` with candidate-hint suggestion |
| `@batchKeyLifter` lifter return type is `Long` (not RowN) | `UnclassifiedField` per Invariant #3 |
| `@batchKeyLifter` lifter parameter type incompatible with parent backing class | `UnclassifiedField` (parameter not assignable) |
| `@batchKeyLifter` Row arity 2, targetColumns size 1 | `UnclassifiedField` per Invariant #4 |
| `@batchKeyLifter` Row1<String>, target column is `Integer` | `UnclassifiedField` per Invariant #4 (column-class mismatch) |
| `@batchKeyLifter` empty `targetColumns` list | `UnclassifiedField` per Invariant #6 |
| `@batchKeyLifter` `targetColumns` references a non-existent column | `UnclassifiedField` per Invariant #5 with candidate hint |
| `@batchKeyLifter` on `@asConnection` field | `UnclassifiedField` per Invariant #9 |

---

### Phase 2 — Emission

**Goal:** wire `BatchKey.LifterRowKeyed` through the emitters so the
classified field actually generates a working DataFetcher and rows-method.

#### 2a. `GeneratorUtils.keyElementType`

Extend the four-arm switch at
[`GeneratorUtils.java:150-155`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/GeneratorUtils.java)
to a five-arm form. `LifterRowKeyed` reuses `buildRowKeyType(keyColumns())`
(same `RowN` shape as `RowKeyed` / `MappedRowKeyed`). Switch becomes:

```java
return switch (batchKey) {
    case BatchKey.RowKeyed _, BatchKey.MappedRowKeyed _, BatchKey.LifterRowKeyed _
        -> buildRowKeyType(batchKey.keyColumns());
    case BatchKey.RecordKeyed _, BatchKey.MappedRecordKeyed _
        -> buildRecordNKeyType(batchKey.keyColumns());
};
```

#### 2b. `buildLifterKeyExtraction`

New helper next to `buildRecordKeyExtraction` at
[`GeneratorUtils.java:187-214`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/GeneratorUtils.java).
Emits the lifter-method invocation:

```java
static CodeBlock buildLifterKeyExtraction(BatchKey.LifterRowKeyed batchKey) {
    TypeName keyType = keyElementType(batchKey);
    var backingClass = ClassName.bestGuess(batchKey.parentBackingClassFqn());
    var lifterClass  = ClassName.bestGuess(batchKey.lifterMethod().className());
    return CodeBlock.builder()
        .addStatement("$T key = $T.$L(($T) env.getSource())",
            keyType, lifterClass, batchKey.lifterMethod().method(), backingClass)
        .build();
}
```

Generated body:

```java
Row1<Long> key = PayloadKeys.kvotesporsmalKey((SettKvotesporsmalAlgoritmePayload) env.getSource());
```

The cast carries the parent's backing class so callers don't have to re-route
through a generic `Record`/`Object` reflective accessor.

#### 2c. `TypeFetcherGenerator.buildRecordBasedDataFetcher` arm

The existing record-based DataFetcher at
[`TypeFetcherGenerator.java:1667-1701`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java)
calls `buildRecordKeyExtraction((BatchKey.RowKeyed) batchKey, resultType,
jooqPackage)` unconditionally on line 1698. Split that call:

```java
CodeBlock keyExtraction = batchKey instanceof BatchKey.LifterRowKeyed lrk
    ? GeneratorUtils.buildLifterKeyExtraction(lrk)
    : GeneratorUtils.buildRecordKeyExtraction((BatchKey.RowKeyed) batchKey, resultType, jooqPackage);
```

Same DataLoader registration, same `loader.load(key, env)` terminal. Only the
"how do I get the key out of the parent?" line changes. The lambda body
(`return rowsMethodName(keys, dfe)`) is unchanged because the rows-method
already takes a `List<RowN>`; it doesn't care whether the row came from a
catalog FK or a developer lifter.

#### 2d. `SplitRowsMethodEmitter.buildListMethod`

No code change. The synthetic `JoinStep.FkJoin` produced in Phase 1d satisfies
the emitter's first-hop contract: `path.get(0) instanceof FkJoin` is true,
`fkJoin.targetColumns()` resolves, `fkJoin.targetTable()` is the field's
`@table`, and the JOIN emits as `target.<col> = values.<col>`. Verify by
inspection during Phase 2 (no behavioural change required) and add a comment
on the synthetic-step builder pointing at this property as an invariant.

#### 2e. Validator updates

`GraphitronSchemaValidator` already iterates `BatchKey` variants in a couple
of arms (e.g.
[`SplitTableField` validation, `RecordTableField` validation](../graphitron/src/main/java/no/sikt/graphitron/rewrite/GraphitronSchemaValidator.java)).
Audit each arm: `LifterRowKeyed` should behave identically to `RowKeyed` in
every existing check (parent-PK existence is moot here because there's no
parent table, but the arms keyed on `batchKey instanceof RowKeyed` need to
admit `LifterRowKeyed` too via a sealed-aware refactor or a one-line
`|| batchKey instanceof LifterRowKeyed` widening). Prefer the sealed-aware
refactor — `BatchKey` is now exhaustive over five variants, so each switch
should compile-fail until the arm is added rather than silently doing the
wrong thing.

#### 2f. Pipeline + execution tests

**Pipeline.** Two new structural tests in `RecordTableFieldPipelineTest` (or
the file housing the existing `@record` parent + `@table` return cases):

1. Lifted `RecordTableField` emits a DataFetcher whose body invokes the
   lifter method (assertion shape: `body.toString().contains("PayloadKeys.kvotesporsmalKey(")`).
2. Lifted `RecordTableField` emits a rows-method whose JOIN names the
   target column on both sides (existing rows-method assertion shape).

**Execution.** A new `MutationPayloadLifterTest` in `graphitron-test`:

- Fixture: a Sakila-fitting payload type, e.g.
  `record CreateFilmPayload(Long languageId)` with one batch-loaded child
  field `language: Language`. Lifter is a static method on a test-local
  helper class returning `Row1<Long>`.
- Test: query that returns two payloads with the same `languageId` and a
  third with a different `languageId`. Assert one DataLoader dispatch with
  two distinct keys (the existing scatter-helper test pattern, e.g.
  [`GraphQLQueryTest.java`](../../graphitron-test/src/test/java/no/sikt/graphitron/rewrite/test/GraphQLQueryTest.java)
  SQL-counting fixtures) and correct field values on each parent.

**Compile gate.** `mvn install -Plocal-db` clean. The fixture lifter class
goes in the `graphitron-fixtures` module so the executed-test compile path
sees a real reflective method, not a stubbed reference.

---

### Phase 3 — Documentation and rejection-message backreference

**Goal:** the existing `RecordTableField` / `RecordLookupTableField`
rejections at `FieldBuilder.java:1900` and `:1907` already reference this
roadmap item by hint; tighten that into a directive recommendation now that
the directive exists.

#### 3a. Update rejection messages

Change the strings at
[`FieldBuilder.java:1899-1900`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java)
and `:1906-1907` from:

> "RecordTableField requires a FK join path and a typed backing class for batch key extraction"

to:

> "RecordTableField requires a FK join path and a typed backing class for
> batch key extraction; for free-form DTO parents, supply
> @batchKeyLifter on the field"

Identical edit on the `RecordLookupTableField` arm two lines above. The
existing `dtoSourcesRejectionReason` in `ServiceCatalog.java:444-446`
already points at this roadmap file by name; update it to point at the
directive instead now that the directive ships.

#### 3b. `code-generation-triggers.md`

Add a row to the directive table covering `@batchKeyLifter` (parent shape:
`PojoResultType` with non-null `fqClassName`; field return: `@table`-bound;
emission: `RecordTableField` / `RecordLookupTableField` with
`BatchKey.LifterRowKeyed`). The taxonomy doc is the canonical "what classifies
to what" reference; new directives belong in it as part of the same landing.

#### 3c. `rewrite-design-principles.md`

Add a one-paragraph "DTO-parent batching" note explaining the contract: the
schema author owns the key-extraction mapping when the catalog can't supply
it, and the lifter is the single place where the mapping lives. Cross-link
from the existing "Column value binding" section.

#### 3d. Changelog entry

`changelog.md` line at the top of the active list, slug
`batchkey-lifter-directive`, summarising:

- New directive `@batchKeyLifter` on FIELD_DEFINITION
- New `BatchKey.LifterRowKeyed` permit (sealed hierarchy now five variants)
- Synthetic-`FkJoin` plumbing on the rows-method side
- The two `RecordTableField` / `RecordLookupTableField` deferred rejections
  closed for DTO parents
- Test count delta and full-build status per the existing changelog format
- Reference to the closed roadmap item

---

## Non-goals

- **`Set`-keyed mapped DataLoaders for lifted keys.** The existing
  `MappedRowKeyed` / `MappedRecordKeyed` shapes drive
  `newMappedDataLoader`. Adding a `LifterMappedRowKeyed` is a mechanical
  follow-up if a consumer needs `Set`-shaped lifter sources, but the
  production hit is `List`-shaped (single key per parent), and we don't
  pre-build the matrix arm. Track as a Backlog item if it surfaces.
- **`RecordKeyed` lifter variant.** `RecordN`-shaped keys are used by
  `@service` SOURCES parameters (typed `RecordN<...>` rather than
  `RowN<...>`); a DTO-parent lifter has no symmetrical need because the
  lifter is a developer method whose return type is whatever they declare.
  We pin lifters to `RowN` for consistency with the existing column-keyed
  DataLoader path.
- **Single-cardinality emission.** `SplitRowsMethodEmitter.unsupportedReason`
  for `RecordTableField` and `RecordLookupTableField` already rejects
  single-cardinality with a runtime stub. The lifter directive inherits this
  stub. Lifting single-cardinality is a separate emitter expansion, tracked
  by the existing stub message at
  [`SplitRowsMethodEmitter.java:329-333`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/SplitRowsMethodEmitter.java).
- **Condition-join steps after the synthetic FkJoin.** The synthetic step
  is a single hop; multi-hop paths from a DTO parent into a target table via
  intermediate join steps are not in scope. A schema author who needs that
  shape should classify the field as `@service` and let the developer method
  walk the path.
- **Lifters that read the `DataFetchingEnvironment`.** The lifter signature is
  pinned to `(ParentBackingClass) -> RowN<...>`. Passing `env` would make
  lifters into one-off DataFetchers and defeat the batching contract — the
  whole point is that the lifter runs once per parent, after which the
  DataLoader fans out.
- **Lifters as instance methods.** Static-only. Avoids resolving an instance
  receiver; mirrors the existing `@externalField` and `@condition` static
  contract.
- **Infer `targetColumns` from `RowN` type args.** The runtime `RowN` carries
  Java types but not SQL column names; inference would require the schema
  author to declare them somewhere anyway. The directive being explicit makes
  the column-class match check (Invariant #4) precise and the error messages
  actionable.

---

## Open decisions

- **Directive name.** `@batchKeyLifter` is descriptive but verbose.
  Alternatives considered: `@dtoKey`, `@parentKey`, `@lift`. Sticking with
  `batchKeyLifter` because it matches the model name (`BatchKey`) and the
  rejection text downstream code already uses; the verbosity is a feature
  for searchability.
- **Lifter location.** The example places `PayloadKeys` in a separate
  utility class. Should we encourage / require lifters live alongside the
  payload class? No requirement either way; the directive's
  `ExternalCodeReference` admits any FQ class. Convention: a project-local
  `<Payload>Keys` utility class. Document but don't enforce.
- **`@field(name:)` interaction on `targetColumns`.** `@field(name:)` lets a
  schema field be backed by a specifically-named column. `targetColumns` here
  is independently SQL-named. There is no interaction in the v1 spec; if a
  future shape needs per-target-column override of the SQL column name, lift
  the `targetColumns` array elements to a richer input type
  (`{name: String!, javaName: String}`). Defer until a consumer asks.
