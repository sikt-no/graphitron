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
> the emitter feeds it into the existing column-keyed DataLoader path with a
> single-hop `JoinStep.LiftedHop`. Co-closes the `RecordTableField` /
> `RecordLookupTableField`
> "missing FK join path and a typed backing class" rejection at
> [`FieldBuilder.java:2035`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java)
> and [`:2042`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java).

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
([`:2108-2117`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java)).
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
already does everything we need *if* the classifier can produce a row-shaped
batch key and a single-hop join step that names the target columns directly.
The plan introduces a sibling `BatchKey` permit (`LifterRowKeyed`) and a
sibling `JoinStep` permit (`LiftedHop`) — both narrowly typed to what the
lifter contract supplies — and gives the schema author a directive that
populates them.

### Relationship to mutation-payload work (R22 / R12)

The canonical use case is a mutation-payload field, but this item is
classifier infrastructure for *any* `@record` parent without catalog FK,
including `@service`-returned DTOs and federation entity stubs. It does not
depend on R22 (`mutations.md`, Spec) landing.

[**R22 — Mutation bodies**](mutations.md): `Invariant #14` restricts DML
mutation returns to `ID` / `[ID]` / `T` / `[T]` (table-bound). A
`SettKvotesporsmalAlgoritmePayload` return is not a DML shape; it is reached
via a `@service` mutation (R22 §Phase 6, `MutationServiceRecordField`). The
service returns the payload POJO; this directive classifies the payload's
batched child fields. R22 and R1 are independent: R22 emits the mutation
body, R1 enables payload-side child batching, and the two compose without
coordination.

[**R12 — Error-handling parity**](error-handling-parity.md): introduces
`ErrorsField` for `errors: [SomeUnion!]!` fields on payloads. R12 and R1
attach to *different* fields on the same payload type — R12 to the `errors`
slot, R1 to data fields like `kvotesporsmal`. Both can apply to the same
payload without interaction. R12's `Optional<ErrorChannel>` slot on
fetcher-emitting variants attaches uniformly to `RecordTableField` /
`RecordLookupTableField` regardless of whether the batch key came from a
catalog FK or from a lifter, so no R1 changes are required when R12 lands.

---

## Surface

### Directive

Added to [`directives.graphqls`](../graphitron/src/main/resources/no/sikt/graphitron/rewrite/schema/directives.graphqls):

```graphql
"""
Supplies a DTO-to-batch-key lifting function for a child field whose @record
parent has no FK metadata in the jOOQ catalog. Applies when the parent's
backing class is a plain POJO or a Java record (not a jOOQ Record /
TableRecord). The parent's @record(record: {className: ...}) must declare a
backing class; the directive is rejected on @record types with no class.

The lifter is a static Java method that extracts the batch-key value(s) from
the parent instance. The lifted RowN is matched against the named target
columns on the field's @table return type to drive a column-keyed DataLoader.

Required when a field on a non-table-backed @record parent returns a @table
type and would otherwise be rejected as 'RecordTableField (or
RecordLookupTableField) requires a FK join path and a typed backing class for
batch key extraction'. Rejected on @table parents (use @reference there) and
on jOOQ-backed @record parents (JooqTableRecordType / JooqRecordType — the
catalog record already drives batching).
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
  JOIN target on a single-hop JoinStep.LiftedHop.
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

- `@table` parents (the FK on the parent table already classifies; see
  `classifyChildFieldOnTableType`). The directive is rejected on a
  `@table`-parent field with an AUTHOR_ERROR pointing at `@reference`.
- Plain Java records (`JavaRecordType` parent) with a resolvable FK in the
  catalog: the existing FK-driven path classifies correctly and the directive
  is unnecessary there. `JavaRecordType` parents *without* a catalog FK are
  in scope and admitted (see Invariant #1).
- jOOQ-backed `@record` parents (`JooqTableRecordType` and `JooqRecordType`).
  The catalog record's columns are the keying contract; AUTHOR_ERROR points
  the author at the existing FK path / catalog `@reference`.
- The directive on a non-`TableBoundReturnType` field. `RecordField` /
  `PropertyField` (scalar-or-record returns) do not batch via DataLoader; the
  directive has no semantics there. AUTHOR_ERROR.
- The five `@service` polymorphic-return rejections lifted by R12
  (`error-handling-parity`); those are about typed errors, not DTO batching.

---

## Invariants

1. **Application context.** `@batchKeyLifter` may appear only on a child field
   whose:
   - parent classifies as `GraphitronType.PojoResultType` **or**
     `GraphitronType.JavaRecordType`, both with a non-null `fqClassName`,
     **and**
   - return type resolves to `ReturnTypeRef.TableBoundReturnType`.

   `JavaRecordType` is admitted because a Java `record` DTO with no jOOQ
   catalog FK has the same problem as a POJO; the existing record-key
   extraction in
   [`GeneratorUtils.buildRecordKeyExtraction:200-202`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/GeneratorUtils.java)
   already treats both as backing-class-driven. The lifter applies symmetrically.
   `JooqRecordType` and `JooqTableRecordType` parents stay rejected (the catalog
   record's columns are the keying contract; use the existing FK path).

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
   ([`ServiceCatalog.java:155-227`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/ServiceCatalog.java)),
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
   corresponding target `ColumnRef.columnClass()` by exact equality (boxed
   names; jOOQ catalog columns expose boxed types and reflection on
   `Row1<Long>` recovers `Long`, not `long`, so no autobox tolerance is
   needed). Mismatch produces AUTHOR_ERROR with the offending position
   called out:
   `"lifter '<name>' Row<N> arity 3 does not match targetColumns size 2"`,
   or
   `"lifter '<name>' RowN type at position 1 ('java.lang.Long') does not
   match target column 'film_id' Java type ('java.lang.Integer')"`.

   **Wildcard `RowN` type-args are rejected.** A lifter declared as
   `Row1<? extends Number>` defeats the structural equality check and
   would force a runtime cast at the rows-method JOIN site. AUTHOR_ERROR:
   `"lifter '<name>' RowN type at position N has wildcard '<? extends X>';
   declare a concrete type matching target column '<col>' (Java type '<T>')"`.
   Implementation: the arity / column-type match check rejects any
   `TypeName` that is a `WildcardTypeName` rather than a `ClassName`. This
   also rules out raw `Row1` (no type args) on the same code path.

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
   [`SplitRowsMethodEmitter.java:327-339`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/SplitRowsMethodEmitter.java);
   that stub stays in place and the lifter path inherits it.)

8. **`@lookupKey` interaction.** The classifier branch at
   [`FieldBuilder.java:2032-2038`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java)
   produces `RecordLookupTableField` when any argument carries `@lookupKey`.
   The lifter directive is orthogonal: a lifted-key `RecordLookupTableField`
   classifies the same way; only the join-path identity (`LiftedHop` instead
   of `FkJoin`) and the key-extraction call site change. No new field
   variant is needed; the existing `RecordLookupTableField` accepts the new
   `BatchKey.LifterRowKeyed` (§Model below) without further work.

9. **Connection wrapper rejection.** A field declared as
   `@asConnection` cannot use the lifter directive. The connection
   classifier expands to `Connection<T>` and routes through pagination
   helpers that don't share the rows-method DataLoader path. AUTHOR_ERROR:
   `"@batchKeyLifter is not supported on @asConnection fields"`.

---

## Model

### `BatchKey.LifterRowKeyed` and `LifterRef`

Add a fifth permit on the sealed `BatchKey` interface
([`BatchKey.java`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/model/BatchKey.java))
plus a small typed reference next to it:

```java
record LifterRef(
    ClassName declaringClass,   // pre-resolved JavaPoet ClassName, never re-parsed
    String methodName           // simple static-method name on declaringClass
) {}

record LifterRowKeyed(
    List<ColumnRef> keyColumns, // DataLoader-key columns; on this variant the
                                // parent has no jOOQ presence, so they are the
                                // target-side columns the lifter feeds in.
    LifterRef lifter
) implements BatchKey {
    @Override
    public String javaTypeName() {
        return containerType("List", "Row", keyColumns);
    }
}
```

`javaTypeName` reuses the same `RowN` envelope as `RowKeyed`: the DataLoader
key type is identical, only the extraction site differs.

**`keyColumns` semantics across variants.** Adding `LifterRowKeyed` makes the
`BatchKey.keyColumns()` accessor uniformly mean *"the column tuple that
constitutes the DataLoader key"*. On `RowKeyed` / `RecordKeyed` /
`MappedRowKeyed` / `MappedRecordKeyed` those columns happen to be parent-side
FK or PK columns (the only thing the catalog could supply for a record
parent); on `LifterRowKeyed` they are target-side columns the lifter materialises.
Both interpretations produce the same `RowN<...>` shape with the same column
types — that is the whole point of the lifter contract. The existing
`BatchKey` interface-level javadoc currently says "PK columns from the parent
table"; this landing widens it to the DataLoader-key reading and removes the
parent-only wording in the same commit (Phase 1a), so consumers reading
`batchKey.keyColumns()` polymorphically see one consistent meaning.

**Pre-resolved `ClassName` over flat strings.** Storing `lifter.declaringClass`
as a JavaPoet `ClassName` rather than a `String` puts the resolution at the
classify boundary (where `ServiceCatalog`-style reflection already produced a
`Class<?>`) and lets the emitter use it directly via `$T`, no `ClassName.bestGuess`
at emission. The parent's backing class is *not* captured on `LifterRowKeyed`:
the field's parent `ResultType` already carries `fqClassName`, and the key-extraction
emitter receives `ResultType` the same way `buildRecordKeyExtraction` does today
(`GeneratorUtils.java:200-208`). Duplicating it on the BatchKey would create
two sources of truth for the same parent.

**Why not reuse `MethodRef.Basic`?** `MethodRef.Basic` carries `params:
List<Param>` and `returnType: TypeName` — both redundant for a lifter
(`params` is one fixed `Typed(BackingClass)`; `returnType` is fully recoverable
from `keyColumns`). More importantly, `MethodRef` consumers like
[`ArgCallEmitter`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/ArgCallEmitter.java)
walk `params()` to build call-site argument lists; a lifter ref must never
flow through that path. `LifterRef` is a purpose-typed sibling of `MethodRef` that
carries exactly what the lifter call site needs, no more.

**DataLoader-key equality.** `Row1<Long>` (and the wider `RowN<...>` family)
is the same key type used by `BatchKey.RowKeyed` today; the lifter path
inherits whatever value-equality contract the existing column-keyed
DataLoader path relies on. (jOOQ's `RowN` implements `equals`/`hashCode` over
its component values; the existing pipeline tests under
[`GraphQLQueryTest`](../../graphitron-test/src/test/java/no/sikt/graphitron/rewrite/test/GraphQLQueryTest.java)
exercise multi-parent batching without explicit cache-correctness assertions
because that contract is treated as a jOOQ guarantee.)

The four existing variants (`RowKeyed`, `RecordKeyed`, `MappedRowKeyed`,
`MappedRecordKeyed`) are untouched. Adding `LifterRowKeyed` requires four
emitter-side changes, each a sealed-switch arm rather than an `instanceof`:

- [`GeneratorUtils.keyElementType`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/GeneratorUtils.java)
  — sealed-type switch, add `LifterRowKeyed` to the row-shape arm (§2a).
- [`GeneratorUtils.buildRecordParentKeyExtraction`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/GeneratorUtils.java)
  — the *record-parent* key-extraction helper (renamed from
  `buildRecordKeyExtraction`) becomes a sealed switch on `BatchKey` with arms
  for the existing record-parent shapes plus `LifterRowKeyed`. The fetcher
  calls this once; no `instanceof` at the call site (§2b).
- [`GeneratorUtils.buildKeyExtraction`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/GeneratorUtils.java)
  — the `@table`-parent variant, separate switch; add an `IllegalStateException`
  arm for `LifterRowKeyed` to keep the switch exhaustive and force any future
  caller mis-routing a lifter key here to fail at build time (§2a).
- [`SplitRowsMethodEmitter.emitParentInputAndFkChain`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/SplitRowsMethodEmitter.java)
  — the prelude's `(BatchKey.RowKeyed) batchKey` cast and the
  `(JoinStep.FkJoin) joinPath.get(0)` cast both become sealed-switches over
  `BatchKey` and `JoinStep` respectively (§2d).

### `JoinStep.LiftedHop` — a third permit

`SplitRowsMethodEmitter.buildListMethod` reads `joinPath` and emits a JOIN
against the target table on the FK columns. The lifter path has no catalog FK,
no traversal direction, and no source-side columns separate from the
target-side: the DataLoader key *is* the target-column tuple. Modelling that
as a `JoinStep.FkJoin` with sentinel-empty `fkName`, `fkJavaConstant`, and
`originTable`, plus duplicated `sourceColumns == targetColumns`, would put
half the record's components into "ignore me on this path" mode and force
every reader of `JoinStep.FkJoin` to remember which fields are real on the
lifter path versus the catalog-FK path. That trades classifier complexity
(synthesise a half-empty record) for emitter complexity (don't read these
fields; trust this comment) — the wrong direction.

Instead, add a third permit to the sealed `JoinStep` interface
([`JoinStep.java:56`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/model/JoinStep.java)):

```java
public sealed interface JoinStep
        permits JoinStep.FkJoin, JoinStep.ConditionJoin, JoinStep.LiftedHop {
    /**
     * One hop pre-keyed by a {@link BatchKey.LifterRowKeyed} — no foreign key,
     * no traversal direction, no source-side columns. The DataLoader key tuple
     * (carried by the BatchKey) is the target-column tuple, so the JOIN-on
     * predicate of the rows-method becomes
     * {@code target.<targetColumns[i]> = parentInput.field(i+1)} directly.
     *
     * <p>Always appears as the sole step on a lifter-classified path
     * ({@link FieldBuilder#classifyChildFieldOnResultType} when the field
     * carries {@code @batchKeyLifter}); the classifier never composes a
     * {@code LiftedHop} with other hops in v1.
     */
    record LiftedHop(
        TableRef targetTable,
        List<ColumnRef> targetColumns,
        String alias
    ) implements JoinStep {}
}
```

`LiftedHop` carries only what the rows-method emitter actually reads on this
path. Each component is non-empty: `targetTable` is the field's `@table`
return; `targetColumns` is what the directive supplied (validated against the
catalog); `alias` follows the existing `fieldName + "_0"` scheme. There are
no sentinels, and `JoinStep` consumers that care about identity become
exhaustive sealed switches automatically (the compiler forces every reader
to make a decision the moment the permit lands).

The classifier's path-walker (`BuildContext.parsePath`) is bypassed for
lifter-directived fields: `parsePath` searches the catalog for FK matches and
rejects unresolvable hops. With the lifter present we know the schema author
has supplied the keying contract directly, so we skip the walker and produce
`joinPath = [LiftedHop]` instead. `FieldBuilder.classifyChildFieldOnResultType`
forks early on a `hasBatchKeyLifter` predicate and routes through
`resolveBatchKeyLifter` (§1e); downstream code sees a sealed `JoinStep` whose
identity tells it which JOIN flavour to emit.

**Single-hop guarantee.** `LiftedHop` paths are always exactly one step in
v1. The classifier upholds this invariant; the rows-method prelude relies on
it to skip the multi-hop FK-chain bridging logic. Both sides wear the
load-bearing-classifier-check annotation pair (§1d producer, §2d consumer).

---

## Plan

### Phase 1 — Model and directive plumbing

**Goal:** parse the directive, resolve the lifter, produce the new
`BatchKey.LifterRowKeyed` and `JoinStep.LiftedHop`. No emission changes.

#### 1a. Model permits and refs

Three coordinated additions, all in `model/`:

1. `LifterRef(ClassName declaringClass, String methodName)` — new record,
   sibling of `MethodRef` rather than a `MethodRef` permit (rationale in
   §Model). Lives at `model/LifterRef.java`.
2. `BatchKey.LifterRowKeyed(List<ColumnRef> keyColumns, LifterRef lifter)` —
   fifth permit on the sealed `BatchKey` interface. Reuses
   `BatchKey.containerType` (already exhaustive over the row/record axis).
3. `JoinStep.LiftedHop(TableRef targetTable, List<ColumnRef> targetColumns,
   String alias)` — third permit on the sealed `JoinStep` interface.

**`BatchKey.keyColumns()` javadoc rewrite.** The interface-level javadoc on
`BatchKey` currently asserts the columns "always come from the parent type's
`TableRef.primaryKeyColumns()` — never from reflection". That wording becomes
false the moment `LifterRowKeyed` lands. Replace it with the
DataLoader-key reading (§Model "`keyColumns` semantics across variants"):
the columns produce the `RowN`/`RecordN` type-args; the SQL identities are
parent-side on every variant whose parent has a catalog table, target-side on
`LifterRowKeyed`. Same edit removes the "Parent types without a `TableRef`
cannot produce a `BatchKey` and fail classification upstream" sentence — the
new permit is precisely the parent-without-`TableRef` case the schema author
opts into via `@batchKeyLifter`.

#### 1b. Directive constants

In the SDL constants file (`Directives.java` or wherever
`DIR_SERVICE`/`DIR_TABLE`/etc. live; the `FieldBuilder` imports list is the
discovery point), add:

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
[`FieldBuilder.java:2009-2055`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java),
add a `hasBatchKeyLifter` branch ahead of the existing `parsePath` call. The
classifier branch is annotated as the producer of two load-bearing
guarantees that the rows-method emitter (§2d) consumes:

```java
@LoadBearingClassifierCheck(
    key = "lifter-path-shape",
    description = "A field carrying @batchKeyLifter classifies with "
        + "joinPath = [JoinStep.LiftedHop] (single hop, sealed-identity "
        + "LiftedHop) and batchKey = BatchKey.LifterRowKeyed. The rows-method "
        + "prelude relies on this pairing to fork on JoinStep identity rather "
        + "than re-deriving it from BatchKey.")
boolean hasLifter = fieldDef.hasAppliedDirective(DIR_BATCH_KEY_LIFTER);
if (hasLifter) {
    var lifterResult = resolveBatchKeyLifter(parentTypeName, fieldDef,
            parentResultType, /* targetSqlTableName */ targetSqlTableName);
    if (lifterResult.error() != null) {
        return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                RejectionKind.AUTHOR_ERROR, lifterResult.error());
    }
    var batchKey = lifterResult.batchKey();
    var joinPath = List.<JoinStep>of(lifterResult.liftedHop());
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

The non-lifter path (existing code at `:2020-2055`) is unchanged. The branch
order matters: `hasBatchKeyLifter` runs first so the AUTHOR_ERROR cases for
the directive on a wrong parent shape (`JooqTableRecordType`,
`JooqRecordType`, untyped `PojoResultType`) surface with the
directive-specific message rather than the generic missing-FK rejection.
Implement those rejections inside `resolveBatchKeyLifter` per Invariant #1.
`PojoResultType` and `JavaRecordType` parents (both with non-null
`fqClassName`) take the lifter path.

**Parallel reject branch on `classifyChildFieldOnTableType`.** A schema author
who places `@batchKeyLifter` on a child field of a `@table` parent must get
the same directive-specific AUTHOR_ERROR rather than a silent ignore. Add a
single early-return at the top of
[`FieldBuilder.classifyChildFieldOnTableType`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java),
mirroring the existing `@multitableReference` / `@service` directive checks
in the method:

```java
if (fieldDef.hasAppliedDirective(DIR_BATCH_KEY_LIFTER)) {
    return new UnclassifiedField(parentTypeName, name, location, fieldDef,
        RejectionKind.AUTHOR_ERROR,
        "@batchKeyLifter is for @record (non-table) parents; "
        + "use @reference on a @table parent");
}
```

A test row in §1f's pipeline matrix already covers this case (`@table`
parent + `@batchKeyLifter` → AUTHOR_ERROR pointing at `@reference`); the
implementation site is now explicit. Same shape goes on
`classifyQueryField` if a future caller places the directive at the query
root — defer a query-root branch until a use case appears, since query roots
classify fundamentally differently and `@batchKeyLifter` has no semantics
without a parent.

#### 1e. `resolveBatchKeyLifter` helper

A new private method on `FieldBuilder`:

```java
private BatchKeyLifterResult resolveBatchKeyLifter(
        String parentTypeName, GraphQLFieldDefinition fieldDef,
        ResultType parentResultType, String targetSqlTableName) {
    // 1. Parent shape: must be PojoResultType or JavaRecordType, both with
    //    non-null fqClassName. (§Invariants 1.) If wrong shape, return error
    //    with AUTHOR_ERROR pointing at the alternative directive (@reference
    //    for @table parents, catalog FK for JooqTableRecordType /
    //    JooqRecordType, etc.).
    // 2. targetColumns: non-empty list (§Invariants 6); each resolves on the
    //    target table (§Invariants 5). Use BuildContext.candidateHint for misses.
    // 3. lifter: className + method via ExternalCodeReference. Class.forName,
    //    locate the static method, verify single parameter assignable from
    //    parent fqClassName, verify return type is org.jooq.RowN
    //    (§Invariants 2-3). Capture the loaded Class<?> as a JavaPoet ClassName.
    // 4. Arity + column-class match (§Invariants 4).
    // 5. Build BatchKey.LifterRowKeyed (with LifterRef) and JoinStep.LiftedHop
    //    per §Model. The LiftedHop's targetColumns are the resolved
    //    List<ColumnRef> from step 2 — same list referenced by the BatchKey.
    // 6. Resolve ReturnTypeRef.TableBoundReturnType for the field's @table return.
}

private record BatchKeyLifterResult(
    BatchKey.LifterRowKeyed batchKey,
    JoinStep.LiftedHop liftedHop,
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
the existing `RecordTableField` block in
[`GraphitronSchemaBuilderTest.java`](../graphitron/src/test/java/no/sikt/graphitron/rewrite/GraphitronSchemaBuilderTest.java)
(grep for `RecordTableField` to find the current cases — line numbers move
with each landing).

| SDL shape | Expected outcome |
|---|---|
| Pojo parent + `@batchKeyLifter` valid Row1, list return | `RecordTableField` with `BatchKey.LifterRowKeyed`, `joinPath = [JoinStep.LiftedHop]`, `LifterRef.declaringClass` resolves to the supplied class |
| Pojo parent + `@batchKeyLifter` valid Row2 (composite key), list return | same; `keyColumns` arity 2; `LiftedHop.targetColumns` arity 2 |
| Pojo parent + `@batchKeyLifter` + `@lookupKey` argument, list return | `RecordLookupTableField` with `BatchKey.LifterRowKeyed`, lookup mapping populated |
| Pojo parent + `@batchKeyLifter`, single-cardinality return | `RecordTableField` (classifies); `SplitRowsMethodEmitter.unsupportedReason` stub fires at emission (existing behaviour, not in scope here) |
| Pojo parent (null `fqClassName`) + `@batchKeyLifter` | `UnclassifiedField` per Invariant #1 |
| `@table` parent + `@batchKeyLifter` | `UnclassifiedField` AUTHOR_ERROR pointing at `@reference` |
| `JooqTableRecordType` parent + `@batchKeyLifter` | `UnclassifiedField` AUTHOR_ERROR pointing at catalog FK |
| `JooqRecordType` parent + `@batchKeyLifter` | `UnclassifiedField` AUTHOR_ERROR pointing at catalog FK |
| `JavaRecordType` parent (non-null `fqClassName`) + `@batchKeyLifter` | `RecordTableField` with `BatchKey.LifterRowKeyed` (admitted, same as `PojoResultType`) |
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
| Pojo parent + `@batchKeyLifter` + `@condition` arg on the field | `RecordTableField` with `BatchKey.LifterRowKeyed`; `tfc.filters()` carries the resolved `WhereFilter` (filter goes through `resolveTableFieldComponents`, not `parsePath`) |
| Pojo parent + `@batchKeyLifter` + `@orderBy` arg on the field, list return | `RecordTableField` with `BatchKey.LifterRowKeyed`; `tfc.orderBy()` carries the resolved `OrderBySpec` |
| Pojo parent + `@batchKeyLifter` + `@field(name: "x")` on the field | classifier ignores `@field(name:)` for the keying axis; `targetColumns` resolves independently of the schema field's column rename. Documents the v1 non-interaction (Open decisions §3) |
| Pojo parent + `@batchKeyLifter` with `targetColumns` whose SQL name exists on multiple tables in the catalog | resolution scopes to the field's `@table` return type only (Invariant #5); a same-named column on an unrelated table is invisible. Test confirms the lookup uses `JooqCatalog` table-scoped resolution, not catalog-wide |

---

### Phase 2 — Emission

**Goal:** wire `BatchKey.LifterRowKeyed` through the emitters so the
classified field actually generates a working DataFetcher and rows-method.

#### 2a. `GeneratorUtils.keyElementType` and `buildKeyExtraction`

Two sealed-type switches must add an arm or compilation breaks.

**`keyElementType`** at
[`GeneratorUtils.java:150-155`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/GeneratorUtils.java)
extends to a five-arm form. `LifterRowKeyed` reuses
`buildRowKeyType(keyColumns())` (same `RowN` shape as `RowKeyed` /
`MappedRowKeyed`). Switch becomes:

```java
return switch (batchKey) {
    case BatchKey.RowKeyed _, BatchKey.MappedRowKeyed _, BatchKey.LifterRowKeyed _
        -> buildRowKeyType(batchKey.keyColumns());
    case BatchKey.RecordKeyed _, BatchKey.MappedRecordKeyed _
        -> buildRecordNKeyType(batchKey.keyColumns());
};
```

**`buildKeyExtraction`** at
[`GeneratorUtils.java:261-289`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/GeneratorUtils.java)
is the `@table`-parent `@splitQuery` accessor
(`(($T) env.getSource()).get(...)`) — a different code path from the
record-parent lifter. The lifter never lands here; the record-parent
extractor (§2b) is the lifter's home. Add an exhaustive arm so the compiler
forces the routing to stay correct:

```java
case BatchKey.LifterRowKeyed _ ->
    throw new IllegalStateException(
        "buildKeyExtraction is the @table-parent path; LifterRowKeyed flows "
        + "through buildRecordParentKeyExtraction");
```

Any future caller that mis-routes a `LifterRowKeyed` to this helper fails at
build time with a precise message rather than a `ClassCastException` on a
real request.

#### 2b. `buildRecordParentKeyExtraction` (rename + sealed switch)

Rename the existing `buildRecordKeyExtraction`
([`GeneratorUtils.java:187-214`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/GeneratorUtils.java))
to `buildRecordParentKeyExtraction` and lift its body to an exhaustive
sealed switch over `BatchKey`. The new arm handles `LifterRowKeyed`; the
existing inner `if/else` over `ResultType` is preserved verbatim for the
catalog-FK arm. Routing flows through the model's sealed identity, not
through an `instanceof` at the call site:

```java
static CodeBlock buildRecordParentKeyExtraction(
        BatchKey batchKey, GraphitronType.ResultType resultType, String jooqPackage) {
    TypeName keyType = keyElementType(batchKey);
    return switch (batchKey) {
        case BatchKey.RowKeyed rk -> buildFkRowKey(rk, keyType, resultType, jooqPackage);
        case BatchKey.LifterRowKeyed lrk -> buildLifterRowKey(lrk, keyType, resultType);
        // The other three permits cannot reach a record parent: RecordKeyed and
        // its mapped sibling are @service SOURCES shapes; MappedRowKeyed is
        // @splitQuery only. The arms throw with a precise routing message.
        case BatchKey.RecordKeyed _, BatchKey.MappedRowKeyed _, BatchKey.MappedRecordKeyed _ ->
            throw new IllegalStateException(
                "buildRecordParentKeyExtraction does not handle "
                + batchKey.getClass().getSimpleName()
                + "; this is the record-parent path (RowKeyed / LifterRowKeyed only)");
    };
}

@DependsOnClassifierCheck(
    key = "lifter-path-shape",
    reliesOn = "FieldBuilder.classifyChildFieldOnResultType produces "
        + "BatchKey.LifterRowKeyed only when the parent is PojoResultType or "
        + "JavaRecordType with non-null fqClassName, so the cast below is safe.")
private static CodeBlock buildLifterRowKey(
        BatchKey.LifterRowKeyed lrk, TypeName keyType,
        GraphitronType.ResultType resultType) {
    ClassName backingClass = backingClassNameOf(resultType); // PojoResultType /
                                                             // JavaRecordType only
    var lifter = lrk.lifter();
    return CodeBlock.builder()
        .addStatement("$T key = $T.$L(($T) env.getSource())",
            keyType, lifter.declaringClass(), lifter.methodName(), backingClass)
        .build();
}
```

Generated body:

```java
Row1<Long> key = PayloadKeys.kvotesporsmalKey((SettKvotesporsmalAlgoritmePayload) env.getSource());
```

`backingClassNameOf` is a tiny `ResultType`-shaped accessor — exactly the
same cast `buildFkRowKey` (formerly the inner else-if chain) already does for
`PojoResultType` / `JavaRecordType` (`GeneratorUtils.java:200-208`). Routing
the lifter arm through it removes the duplicated FQN that `LifterRowKeyed`
would otherwise carry. The `LifterRef.declaringClass` is already a
`ClassName`, so the emitter writes `$T` directly with no `bestGuess`.

#### 2c. `TypeFetcherGenerator.buildRecordBasedDataFetcher` call site

The existing record-based DataFetcher
`buildRecordBasedDataFetcher` at
[`TypeFetcherGenerator.java:1756-1790`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java)
casts unconditionally on line `:1787`:

```java
.addCode(GeneratorUtils.buildRecordKeyExtraction((BatchKey.RowKeyed) batchKey, resultType, jooqPackage))
```

After §2b's rename, the cast disappears; the call passes the `BatchKey`
through and the sealed switch dispatches:

```java
.addCode(GeneratorUtils.buildRecordParentKeyExtraction(batchKey, resultType, jooqPackage))
```

No `instanceof` at the call site, no cast. Adding a future record-parent
batch-key variant is a sealed-switch arm in `GeneratorUtils`, not a fork at
every fetcher site. The lambda body (`return rowsMethodName(keys, dfe)`) is
unchanged because the rows-method already takes a `List<RowN>`; it doesn't
care whether the key came from a catalog FK or a developer lifter.

#### 2d. `SplitRowsMethodEmitter.emitParentInputAndFkChain`

The prelude has two casts that break for the lifter path. The
[`BatchKey.RowKeyed`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/SplitRowsMethodEmitter.java)
cast at line `:131` and the
[`JoinStep.FkJoin`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/SplitRowsMethodEmitter.java)
cast at line `:155` both throw `ClassCastException` against
`LifterRowKeyed` / `LiftedHop` respectively. Both are replaced with the
sealed-interface accessor or a sealed switch on identity.

**Cast 1 — BatchKey access.** `LifterRowKeyed` is a sibling permit on the
sealed `BatchKey`. The cast collapses to the interface accessor:

```java
List<ColumnRef> pkCols = batchKey.keyColumns();
```

The local `rowKeyed` is unused beyond this read; the line drops cleanly.

**Cast 2 — first-hop access and FK-chain loop.** Replace the `(JoinStep.FkJoin)`
cast plus the in-loop cast at `:194` with one sealed switch that accepts both
`FkJoin` and `LiftedHop`. They share the three accessors the prelude reads
(`targetTable`, `targetColumns`, `alias`); their identities are still
distinct downstream where the JOIN-on predicate emits.

```java
@DependsOnClassifierCheck(
    key = "lifter-path-shape",
    reliesOn = "Classifier guarantees joinPath.get(0) is FkJoin or LiftedHop "
        + "(never ConditionJoin for a record-parent lifter); LiftedHop "
        + "appears only as a single-hop path.")
JoinStep firstHop = joinPath.get(0);

for (int i = 0; i < joinPath.size(); i++) {
    JoinStep step = joinPath.get(i);
    TableRef tgt = switch (step) {
        case JoinStep.FkJoin fk -> fk.targetTable();
        case JoinStep.LiftedHop lh -> lh.targetTable();
        case JoinStep.ConditionJoin _ -> throw new IllegalStateException(
            "ConditionJoin cannot appear on a record-parent path");
    };
    ClassName jooqTableClass = ClassName.get(jooqPackage + ".tables", tgt.javaClassName());
    body.addStatement("$T $L = $T.$L.as($S)",
        jooqTableClass, aliases.get(i), tablesClass, tgt.javaFieldName(),
        fieldName + "_" + aliases.get(i));
}
```

The downstream JOIN-on predicate (the `firstHop.sourceColumns()` read
elsewhere in `buildListMethod`) is the one place identity actually matters:
on the catalog-FK path it reads `FkJoin.sourceColumns` (the parent-side FK
columns); on the lifter path it reads `LiftedHop.targetColumns` (because the
DataLoader key tuple *is* the target-column tuple by §Model construction).
That site becomes a small sealed switch as well:

```java
List<ColumnRef> joinOnCols = switch (firstHop) {
    case JoinStep.FkJoin fk -> fk.sourceColumns();
    case JoinStep.LiftedHop lh -> lh.targetColumns();
    case JoinStep.ConditionJoin _ -> throw new IllegalStateException(
        "ConditionJoin cannot appear on a record-parent path");
};
```

After this landing, the prelude has no `instanceof` and no defensive cast;
the `JoinStep` sealed identity carries the FK-vs-lifter fork. Bridging hops
(`i >= 1`) cannot appear on the lifter path because `LiftedHop` is always
single-hop in v1 (§Model "Single-hop guarantee"); the consumer-side
`@DependsOnClassifierCheck` makes that reliance explicit and the audit test
ensures the annotation pair stays balanced.

The `parentInput` VALUES table's column types come from
`batchKey.keyColumns()` directly (now via the sealed accessor), and on the
lifter path those are the target-side columns, which match the lifter's
`RowN` type-args by Invariant #4.

#### 2e. Validator updates and load-bearing audit

Audit complete. As of trunk,
[`GraphitronSchemaValidator`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/GraphitronSchemaValidator.java)
does **not** branch on `BatchKey` variants in any arm: zero hits for
`BatchKey`, `RowKeyed`, or `RecordKeyed` across the file. The two arms
relevant to lifter-classified fields,
`validateRecordTableField` (`:664-667`) and
`validateRecordLookupTableField` (`:668-678`), call only
`validateReferencePath` (a no-op stub at `:731-733`) and `validateCardinality`
(switch over `FieldWrapper`, no `BatchKey` involvement). `JoinStep`-keyed
checks: `validateReferenceLeadsToType` (`:370-384`) reads
`path.getLast().targetTable`; with `JoinStep.LiftedHop` added, this read
becomes a two-arm sealed switch (matching the prelude pattern in §2d) and
returns `lh.targetTable()` for the lifter path — the field's `@table` by
construction.

Phase 2 does not introduce new validator arms beyond that sealed-switch
update. The classifier-side AUTHOR_ERROR rejections (Invariants #1-#9) are
already promoted to build-time errors by the existing
`UnclassifiedField` → validator pipeline; no new gate is needed.

**Load-bearing annotation audit.** The producer/consumer pair introduced in
§1d and §2d (`key = "lifter-path-shape"`) is enforced by
[`LoadBearingGuaranteeAuditTest`](../graphitron/src/test/java/no/sikt/graphitron/rewrite/model/LoadBearingGuaranteeAuditTest.java):
the test walks the rewrite module's compiled output and fails on any
consumer whose key has no producer (or any duplicate producer). The audit
runs as part of `mvn install -Plocal-db`; no separate test wiring is needed.
Adding the annotations therefore costs one line each and gives find-usages
navigation between the classifier guarantee and every emitter that depends
on it.

If future validation logic adds a `BatchKey`-keyed check (e.g. a hypothetical
"every parent table has a PK" gate), `LifterRowKeyed` will participate in the
sealed `switch` exhaustiveness check and force the author to handle it.
Parent-PK existence is moot for `LifterRowKeyed` because the parent has no
jOOQ table; any new gate must specifically exclude this variant.

#### 2f. Pipeline + execution tests

**Pipeline.** Behaviour assertions live at the model layer, not on emitted
source. Two structural tests in `RecordTableFieldPipelineTest` (or the file
housing the existing `@record` parent + `@table` return cases):

1. Lifted `RecordTableField` classifies with
   `field.batchKey() instanceof BatchKey.LifterRowKeyed lrk` and
   `lrk.lifter().declaringClass()` matching the `ClassName` from the
   directive; `field.joinPath()` is a single-element list whose sole element
   is `JoinStep.LiftedHop`. (Per *rewrite-design-principles* "Pipeline tests
   are the primary behavioural tier": no code-string assertions on generated
   method bodies — those are banned at every tier.)
2. The compile-tier (`mvn compile -pl :graphitron-test -Plocal-db`) verifies
   that the emitted DataFetcher body is type-correct: the `key` local is
   typed `Row1<Long>`, the lifter call passes a `SettKvotesporsmalAlgoritmePayload`,
   and `loader.load(key, env)` resolves. No structural body-grep is needed
   to assert the lifter is wired in — compile failure on a mis-wiring is the
   bookkeeping signal.

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
rejections at `FieldBuilder.java:2035` and `:2042` already reference this
roadmap item by hint; tighten that into a directive recommendation now that
the directive exists.

#### 3a. Update rejection messages

Change the strings at
[`FieldBuilder.java:2034-2035`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java)
and `:2041-2042` from:

> "RecordTableField requires a FK join path and a typed backing class for batch key extraction"

to:

> "RecordTableField requires a FK join path and a typed backing class for
> batch key extraction; for free-form DTO parents, supply
> @batchKeyLifter on the field"

Identical edit on the `RecordLookupTableField` arm two lines above. The
existing `dtoSourcesRejectionReason` in `ServiceCatalog.java:452-470`
already points at this roadmap file by name; update it to point at the
directive instead now that the directive ships.

#### 3b. `code-generation-triggers.md`

Add a row to the directive table covering `@batchKeyLifter` (parent shape:
`PojoResultType` or `JavaRecordType`, both with non-null `fqClassName`; field
return: `@table`-bound; emission: `RecordTableField` / `RecordLookupTableField`
with `BatchKey.LifterRowKeyed`). The taxonomy doc is the canonical "what
classifies to what" reference; new directives belong in it as part of the
same landing.

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
- New `JoinStep.LiftedHop` permit (sealed hierarchy now three variants) and
  new `LifterRef(ClassName, String)` typed reference
- `GeneratorUtils.buildRecordKeyExtraction` renamed to
  `buildRecordParentKeyExtraction`, becomes a sealed switch over `BatchKey`;
  `TypeFetcherGenerator.buildRecordBasedDataFetcher` no longer casts to
  `BatchKey.RowKeyed`
- `SplitRowsMethodEmitter.emitParentInputAndFkChain` switches on `JoinStep`
  variants for the FK-chain loop and the JOIN-on predicate; the
  `(JoinStep.FkJoin)` and `(BatchKey.RowKeyed)` casts are removed
- `BatchKey.keyColumns()` javadoc rewritten from "PK columns from the parent
  table" to the DataLoader-key reading
- New `lifter-path-shape` `@LoadBearingClassifierCheck` /
  `@DependsOnClassifierCheck` pair
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
  [`SplitRowsMethodEmitter.java:327-339`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/SplitRowsMethodEmitter.java).
- **Condition-join steps after the LiftedHop.** `LiftedHop` paths are
  single-hop in v1; multi-hop paths from a DTO parent into a target table
  via intermediate join steps are not in scope. A schema author who needs
  that shape should classify the field as `@service` and let the developer
  method walk the path.
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
  schema field be backed by a specifically-named column. `targetColumns`
  here is independently SQL-named — by design, since the directive's job is
  to bridge to a target column directly. The v1 spec treats them as
  non-interacting; a pipeline-test row asserts this (§1f). No richer
  `targetColumns` element type is planned; if a future use case appears,
  raise it then with a concrete shape.
