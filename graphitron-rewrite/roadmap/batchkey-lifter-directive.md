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
> "missing FK join path and a typed backing class" rejections in
> [`FieldBuilder.classifyChildFieldOnResultType`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java)
> (grep for `"requires a FK join path"`).

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
`deriveBatchKeyForResultType(joinPath, parentResultType)`. That helper
requires the join path's first hop to be a `JoinStep.FkJoin` so it can read
`fkJoin.sourceColumns()` as the parent-side key columns. With no FK in the
catalog, `parsePath` either fails outright or produces an empty / non-FK
first hop, and the field downgrades to `UnclassifiedField` with the deferred
reason quoted in the title.

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

7. **Cardinality coverage.** List (`[T]`) returns are admitted; single (`T`)
   returns are rejected at validate time per Invariant #10 (the existing
   `SplitRowsMethodEmitter.unsupportedReason` stub is promoted to a
   build-time error in this landing). The directive does not change the
   cardinality contract: the field's wrapper drives whether the rows-method
   emits a list-collecting query or a single-row terminal join, exactly as
   for catalog-FK `RecordTableField` today. When single-cardinality emission
   is implemented, Invariant #10 is the single site to relax.

8. **`@lookupKey` interaction.** The existing classifier branch in
   `FieldBuilder.classifyChildFieldOnResultType` produces
   `RecordLookupTableField` when any argument carries `@lookupKey`.
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

10. **Single-cardinality on `RecordTableField` / `RecordLookupTableField`
    rejected at validate time.** The existing
    `SplitRowsMethodEmitter.unsupportedReason` stub at
    [`SplitRowsMethodEmitter.java:327-339`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/SplitRowsMethodEmitter.java)
    rejects single-cardinality emission for these two field variants with a
    "not yet supported" runtime stub. *Validator mirrors classifier
    invariants* (rewrite-design-principles.md) requires this gap to close at
    build time: a classifier acceptance must never land on an emitter arm
    that does not exist. This landing promotes the stub to a validator
    rejection in `GraphitronSchemaValidator.validateRecordTableField` /
    `validateRecordLookupTableField`, fed by the same set the dispatcher
    reads. The lifter directive does not introduce the gap, but it expands
    the surface that lands on the path; closing the principle violation in
    the same landing keeps "problems caught at build time" honest. The stub
    in `SplitRowsMethodEmitter` becomes an `IllegalStateException` (the
    validator already rejected, so reaching emission is a classifier bug).
    When list emission for these variants ships, the validator gate flips
    off in the same landing as the emitter arm. Validator message:
    `"<RecordTableField|RecordLookupTableField> '<parent>.<field>' returns
    a single-cardinality '<T>'; only list returns ('[T]') are supported in
    this release"`.

---

## Model

### `BatchKey.LifterRowKeyed`, `RecordParentBatchKey`, and `LifterRef`

The naive shape (a fifth flat permit on `BatchKey` whose `keyColumns()` accessor
silently changes meaning by variant) widens an interface-level contract from
"parent-side PK/FK columns" to "whatever the DataLoader key consists of",
forcing every polymorphic consumer of `keyColumns()` to re-discover which side
of the join it received columns for. That violates *Sealed hierarchies over
enums for typed information* (variants with different data sharing one
accessor) and *Narrow component types over broad interfaces* (the certainty
"these are parent PKs" is lost across the type). Instead, the landing
introduces a sub-hierarchy plus side-aware accessors, and treats the lifter
path's single hop as a type-system fact rather than a classifier convention.

```java
public sealed interface BatchKey
        permits BatchKey.RowKeyed, BatchKey.RecordKeyed,
                BatchKey.MappedRowKeyed, BatchKey.MappedRecordKeyed,
                BatchKey.LifterRowKeyed {

    /** Common to every permit; drives the SOURCES parameter type. */
    String javaTypeName();

    /**
     * Sub-hierarchy: keys whose columns name the parent-side PK/FK. The four
     * existing catalog-resolvable permits live here. {@code parentKeyColumns}
     * names the side explicitly so polymorphic consumers cannot confuse it
     * with target-side columns supplied by a lifter.
     */
    sealed interface ParentKeyed extends BatchKey
            permits RowKeyed, RecordKeyed, MappedRowKeyed, MappedRecordKeyed {
        List<ColumnRef> parentKeyColumns();
    }

    /**
     * Sub-hierarchy: keys produced for {@code @record} (non-table) parents.
     * Permits {@link RowKeyed} (catalog FK on a record parent) and
     * {@link LifterRowKeyed} (developer-supplied lifter). This is the input
     * type for {@code GeneratorUtils.buildRecordParentKeyExtraction}; any
     * future caller routing a {@link RecordKeyed} or mapped variant here is
     * a compile error rather than a runtime {@code IllegalStateException}.
     */
    sealed interface RecordParentBatchKey extends BatchKey
            permits RowKeyed, LifterRowKeyed {}

    record RowKeyed(List<ColumnRef> parentKeyColumns)
            implements ParentKeyed, RecordParentBatchKey {
        @Override public String javaTypeName() {
            return containerType("List", "Row", parentKeyColumns);
        }
    }
    // RecordKeyed, MappedRowKeyed, MappedRecordKeyed: unchanged shape, but
    // their column accessor is renamed parentKeyColumns() (§1a).

    record LifterRef(
        ClassName declaringClass,   // pre-resolved JavaPoet ClassName, never re-parsed
        String methodName           // simple static-method name on declaringClass
    ) {}

    record LifterRowKeyed(
        JoinStep.LiftedHop hop,     // single source of truth for the target-side
                                    // column tuple AND the single-hop invariant;
                                    // see "Single-hop guarantee" below
        LifterRef lifter
    ) implements RecordParentBatchKey {

        /**
         * Target-side columns the lifter materialises. Named distinctly from
         * {@link ParentKeyed#parentKeyColumns()} because the SQL identity is
         * different (target table, not parent). Both produce {@code RowN<...>}
         * of the same Java types — that is the lifter contract.
         */
        public List<ColumnRef> targetKeyColumns() { return hop.targetColumns(); }

        @Override public String javaTypeName() {
            return containerType("List", "Row", hop.targetColumns());
        }
    }
}
```

`javaTypeName` reuses the same `RowN` envelope as `RowKeyed`: the DataLoader
key type is identical, only the extraction site differs.

**Why `LifterRowKeyed` carries its `LiftedHop` directly.** The naive shape
duplicated the `List<ColumnRef>` on both `LifterRowKeyed.keyColumns` and
`LiftedHop.targetColumns`, with the classifier responsible for keeping them
identical. A future refactor that populates one and not the other emits silent
JOIN garbage: DataLoader keys carry one tuple, the JOIN matches another, no
test fails until first dispatch. Holding the `LiftedHop` once on the BatchKey
collapses the two slots into a single source of truth and makes the single-hop
invariant a type fact (one record, one hop) rather than a classifier
convention (see "Single-hop guarantee" below).

**Pre-resolved `ClassName` over flat strings.** Storing `lifter.declaringClass`
as a JavaPoet `ClassName` rather than a `String` puts the resolution at the
classify boundary (where `ServiceCatalog`-style reflection already produced a
`Class<?>`) and lets the emitter use it directly via `$T`, no `ClassName.bestGuess`
at emission. The parent's backing class is *not* captured on `LifterRowKeyed`:
the field's parent `ResultType` already carries `fqClassName`, and the key-extraction
emitter receives `ResultType` the same way `buildRecordKeyExtraction` does today
(`GeneratorUtils.java:200-208`). Duplicating it on the BatchKey would create
two sources of truth for the same parent.

**`keyColumns()` is no longer an interface-level accessor.** The legacy
`BatchKey.keyColumns()` accessor is removed in this landing; the four catalog
permits expose `parentKeyColumns()` (under `ParentKeyed`) and `LifterRowKeyed`
exposes `targetKeyColumns()`. Code that genuinely needs side-agnostic access
to "the column tuple that drives the SOURCES type" gets it via
`javaTypeName()` (which is already side-agnostic by construction); code that
needs the columns themselves switches on identity, which is exactly when the
parent-vs-target distinction matters. This restores the principle that a
shared accessor's meaning does not depend on the runtime variant.

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
`MappedRecordKeyed`) keep their shape (the column accessor is renamed
`parentKeyColumns()` under the new `ParentKeyed` sub-interface). Adding
`LifterRowKeyed` requires four emitter-side changes, each driven by sealed
identity or a narrowed parameter type rather than `instanceof`:

- [`GeneratorUtils.keyElementType`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/GeneratorUtils.java)
  — sealed-type switch over `BatchKey`. The four `ParentKeyed` permits read
  `parentKeyColumns()`; `LifterRowKeyed` reads `targetKeyColumns()` (§2a).
- [`GeneratorUtils.buildRecordParentKeyExtraction`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/GeneratorUtils.java)
  — parameter narrows from `BatchKey` to
  `BatchKey.RecordParentBatchKey`. The switch has exactly two arms (`RowKeyed`,
  `LifterRowKeyed`) — the @service-only permits cannot reach this method by
  type, so no defensive throwing arms (§2b).
- [`GeneratorUtils.buildKeyExtraction`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/GeneratorUtils.java)
  — the `@table`-parent variant; parameter narrows to `BatchKey.ParentKeyed`.
  `LifterRowKeyed` is excluded by the type system, so no `IllegalStateException`
  arm is needed (§2a).
- [`SplitRowsMethodEmitter.emitParentInputAndFkChain`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/SplitRowsMethodEmitter.java)
  — the prelude's `(BatchKey.RowKeyed) batchKey` cast collapses to the sealed
  `RecordParentBatchKey` accessor pair; the `(JoinStep.FkJoin) joinPath.get(0)`
  cast becomes a `JoinStep.WithTarget` capability read for the uniform
  accessors and a sealed switch only at the JOIN-on predicate (§2d).

### `JoinStep.LiftedHop` and the `WithTarget` capability

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

Instead, add a third permit to the sealed `JoinStep` interface plus a small
**`WithTarget` capability** that captures what `FkJoin` and `LiftedHop` share
without forcing every reader to switch on identity to recover it:

```java
public sealed interface JoinStep
        permits JoinStep.FkJoin, JoinStep.ConditionJoin, JoinStep.LiftedHop {

    /**
     * Capability mixed in by hops that pre-resolve a target table the prelude
     * joins to. Lets the rows-method prelude's FK-chain loop read
     * {@code step.targetTable()}, {@code step.targetColumns()}, and
     * {@code step.alias()} polymorphically — i.e. exactly where the accessors
     * mean the same thing on every implementor. The genuine identity fork
     * (FK source-side columns vs. lifter target-side columns for the JOIN-on
     * predicate) stays a sealed switch where it belongs.
     */
    interface WithTarget {
        TableRef targetTable();
        List<ColumnRef> targetColumns();
        String alias();
    }

    record FkJoin(
        // ... existing components unchanged ...
    ) implements JoinStep, WithTarget {}

    record ConditionJoin(MethodRef condition, String alias)
        implements JoinStep {}

    /**
     * One hop pre-keyed by a {@link BatchKey.LifterRowKeyed} — no foreign key,
     * no traversal direction, no source-side columns. The DataLoader key tuple
     * carried by the BatchKey *is* the target-column tuple, so the JOIN-on
     * predicate of the rows-method becomes
     * {@code target.<targetColumns[i]> = parentInput.field(i+1)} directly.
     *
     * <p>{@code LifterRowKeyed} holds a single {@code LiftedHop} on its own
     * record; the classifier publishes the same instance through
     * {@code joinPath = [hop]} for back-compat with the existing rows-method
     * loop, but the BatchKey is the source of truth.
     */
    record LiftedHop(
        TableRef targetTable,
        List<ColumnRef> targetColumns,
        String alias
    ) implements JoinStep, WithTarget {}
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
`joinPath = [hop]` from the same `LiftedHop` instance the BatchKey holds.
`FieldBuilder.classifyChildFieldOnResultType` forks early on a
`hasBatchKeyLifter` predicate and routes through `resolveBatchKeyLifter`
(§1e); downstream code sees a sealed `JoinStep` whose identity tells it
which JOIN flavour to emit.

**Single-hop guarantee, type-system-enforced.** Because `LifterRowKeyed`
holds a single `LiftedHop` (not a `List<LiftedHop>`), the single-hop
invariant is a type fact rather than a classifier convention: there is
nowhere for a second hop to live on the lifter path. The rows-method
prelude relies on this to skip the multi-hop FK-chain bridging logic, but
no `@DependsOnClassifierCheck` annotation pairs with the guarantee: there
is no classifier check to depend on, only a model shape. The prelude
documents the reliance with a plain javadoc note (§2d); plain find-usages
on `LifterRowKeyed.hop` recovers the same navigation an annotation would,
without bending the load-bearing audit's "every consumer key has a
producer" rule.

---

## Plan

### Phase 1 — Model and directive plumbing

**Goal:** parse the directive, resolve the lifter, produce the new
`BatchKey.LifterRowKeyed` and `JoinStep.LiftedHop`. No emission changes.

#### 1a. Model permits, sub-interfaces, refs, and accessor renames

Six coordinated additions, all in `model/`:

1. `LifterRef(ClassName declaringClass, String methodName)` — new record,
   sibling of `MethodRef` rather than a `MethodRef` permit (rationale in
   §Model). Lives at `model/LifterRef.java`.
2. **`BatchKey.ParentKeyed`** — sealed sub-interface, permits the four
   existing variants (`RowKeyed`, `RecordKeyed`, `MappedRowKeyed`,
   `MappedRecordKeyed`). Declares `List<ColumnRef> parentKeyColumns()`. The
   four records are renamed to expose `parentKeyColumns()` (was `keyColumns()`).
3. **`BatchKey.RecordParentBatchKey`** — sealed sub-interface, permits
   `RowKeyed` and `LifterRowKeyed`. No methods; it exists purely as the
   parameter type for `GeneratorUtils.buildRecordParentKeyExtraction`,
   making "the @service-only permits cannot reach this method" a
   compile-time fact.
4. **`BatchKey.LifterRowKeyed(JoinStep.LiftedHop hop, LifterRef lifter)`** —
   fifth top-level permit. Implements `RecordParentBatchKey` (not `ParentKeyed`).
   Exposes `targetKeyColumns()` (delegates to `hop.targetColumns()`); uses
   `containerType("List", "Row", hop.targetColumns())` from `javaTypeName()`.
   Holding the `LiftedHop` directly makes the column tuple a single source of
   truth and the single-hop invariant a type-system fact.
5. **`JoinStep.WithTarget`** — non-sealed capability interface (members:
   `targetTable`, `targetColumns`, `alias`). Mixed in by `FkJoin` and
   `LiftedHop`; used by the rows-method prelude wherever the same accessors
   mean the same thing on every implementor (§2d).
6. `JoinStep.LiftedHop(TableRef targetTable, List<ColumnRef> targetColumns,
   String alias)` — third permit on the sealed `JoinStep` interface,
   implementing `WithTarget`.

**`BatchKey.keyColumns()` removal.** The naive plan widened the interface-level
`keyColumns()` accessor to "DataLoader-key columns regardless of side". This
landing instead **removes** that accessor from `BatchKey` entirely:

- Mechanical rename of the four catalog records' `keyColumns` component to
  `parentKeyColumns`. (One-liner in each record header; all references inside
  `BatchKey.java` and call sites are tracked by the compiler.)
- Lift the rename through every reader: `GeneratorUtils.keyElementType`,
  `GeneratorUtils.buildRowKeyType`, `MethodRef.Param.Sourced.typeName()` etc.
  All four current readers are switching on identity already (so they can
  call the renamed accessor) or projecting through `containerType`. The
  renames are a single search-and-replace landing alongside the new permit.
- Update the interface-level javadoc on `BatchKey` to drop "always come from
  the parent type's `TableRef.primaryKeyColumns()`" and "Parent types without
  a `TableRef` cannot produce a `BatchKey`" wording. The new wording reads:
  "Side-aware accessors live on the sub-interfaces: `ParentKeyed` exposes
  `parentKeyColumns()` for the four catalog variants; `LifterRowKeyed`
  exposes `targetKeyColumns()`."

This pattern is what *Sealed hierarchies over enums for typed information*
calls for: variants whose data has different meaning carry different
accessors, and code that claims uniformity is forced to express that claim
either through identity or through a capability — never through a shared
accessor whose meaning silently depends on the variant.

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
classifier guarantee is produced by `resolveBatchKeyLifter` (§1e) and
annotated there (annotations target methods, not locals); the branch in
`classifyChildFieldOnResultType` consumes that helper's result:

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
    // joinPath publishes the same hop instance held by the BatchKey; the
    // List wrap is the rows-method emitter's existing API surface.
    var joinPath = List.<JoinStep>of(batchKey.hop());
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

A new private method on `FieldBuilder`. It is the sole producer of two
distinct facts the emitter side later relies on. Each fact gets its own
load-bearing key so a future relaxation invalidates only the consumers that
actually relied on the relaxed half:

```java
@LoadBearingClassifierCheck(
    key = "lifter-classifies-as-record-table-field",
    description = "A field carrying @batchKeyLifter classifies as "
        + "RecordTableField or RecordLookupTableField (never any other "
        + "variant). RecordParentBatchKey-typed call sites in GeneratorUtils "
        + "depend on this routing.")
@LoadBearingClassifierCheck(
    key = "lifter-batchkey-is-lifterrowkeyed",
    description = "On the lifter path, the field's BatchKey is "
        + "LifterRowKeyed (and never any other RecordParentBatchKey permit). "
        + "buildRecordParentKeyExtraction's two-arm switch and the "
        + "rows-method prelude's RecordParentBatchKey accessor pair rely on "
        + "this so the type system carries the discrimination.")
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
    // 5. Build the JoinStep.LiftedHop once from the resolved targetTable +
    //    targetColumns + alias, then construct
    //    BatchKey.LifterRowKeyed(hop, lifterRef). The hop is the *only* source
    //    of truth for the target-side column tuple; the BatchKey reads through
    //    it via targetKeyColumns(). No second copy of the column list exists.
    // 6. Resolve ReturnTypeRef.TableBoundReturnType for the field's @table
    //    return. Publish joinPath = [hop] using the same hop instance held
    //    by the BatchKey.
}

private record BatchKeyLifterResult(
    BatchKey.LifterRowKeyed batchKey,         // holds the LiftedHop directly
    ReturnTypeRef.TableBoundReturnType tbReturnType,
    String error
) {
    // No separate liftedHop / targetTable / targetColumns slots: every reader
    // reaches them through batchKey.hop(). One source of truth.
    JoinStep.LiftedHop liftedHop() { return batchKey.hop(); }
    TableRef targetTable()         { return batchKey.hop().targetTable(); }
}
```

The single-hop guarantee is *not* re-stated as a load-bearing key on either
side: it is enforced by the model shape (`LifterRowKeyed` holds one
`LiftedHop`, not a `List<LiftedHop>`), and a `@DependsOnClassifierCheck`
without a paired producer would either bend the audit rule or require a
dummy `@LoadBearingClassifierCheck` that documents nothing the type system
doesn't already say. The §2d consumer that skips multi-hop bridging on the
lifter path documents its reliance with a plain javadoc comment instead.
`LoadBearingGuaranteeAuditTest` is unchanged; no allowlist is added.

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
| Pojo parent + `@batchKeyLifter` valid Row1, list return | `RecordTableField` with `BatchKey.LifterRowKeyed`; `field.batchKey().hop()` is the same instance as `field.joinPath().get(0)` (no duplicated column list); `LifterRef.declaringClass` resolves to the supplied class |
| Pojo parent + `@batchKeyLifter` valid Row2 (composite key), list return | same; `batchKey.targetKeyColumns()` arity 2; `batchKey.hop().targetColumns()` is the same `List<ColumnRef>` instance |
| Pojo parent + `@batchKeyLifter` + `@lookupKey` argument, list return | `RecordLookupTableField` with `BatchKey.LifterRowKeyed`, lookup mapping populated |
| Pojo parent + `@batchKeyLifter`, single-cardinality return | `UnclassifiedField` AUTHOR_ERROR per Invariant #10 — the validator promotes the existing `SplitRowsMethodEmitter.unsupportedReason` stub for `RecordTableField` / `RecordLookupTableField` to a build-time rejection so a classifier acceptance never lands on an emitter that has no arm for it (§2e) |
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

`keyElementType` extends to a five-arm switch; `buildKeyExtraction` narrows
its parameter type so `LifterRowKeyed` is excluded by the type system rather
than by a runtime throw.

**`keyElementType`** at
[`GeneratorUtils.java:150-155`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/GeneratorUtils.java)
becomes a five-arm sealed switch over `BatchKey`. Because `keyColumns()` is
no longer on the interface, each arm reads through the side-aware accessor:

```java
return switch (batchKey) {
    case BatchKey.RowKeyed rk          -> buildRowKeyType(rk.parentKeyColumns());
    case BatchKey.MappedRowKeyed mrk   -> buildRowKeyType(mrk.parentKeyColumns());
    case BatchKey.LifterRowKeyed lrk   -> buildRowKeyType(lrk.targetKeyColumns());
    case BatchKey.RecordKeyed rk       -> buildRecordNKeyType(rk.parentKeyColumns());
    case BatchKey.MappedRecordKeyed mrk-> buildRecordNKeyType(mrk.parentKeyColumns());
};
```

The accessor names make the side distinction visible at every read site —
exactly the property that vanishes if a polymorphic `keyColumns()` survives
on the interface.

**`buildKeyExtraction`** at
[`GeneratorUtils.java:261-289`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/GeneratorUtils.java)
is the `@table`-parent `@splitQuery` accessor
(`(($T) env.getSource()).get(...)`). The lifter never lands here; the
record-parent extractor (§2b) is the lifter's home. Instead of adding a
defensive `IllegalStateException` arm for `LifterRowKeyed`, **narrow the
parameter type from `BatchKey` to `BatchKey.ParentKeyed`**:

```java
public static CodeBlock buildKeyExtraction(
        BatchKey.ParentKeyed batchKey,   // was: BatchKey
        ResultType parentResultType, ...) { ... }
```

`ParentKeyed`'s permits are exactly `RowKeyed`, `RecordKeyed`,
`MappedRowKeyed`, `MappedRecordKeyed` — the four catalog variants. Any
future caller mis-routing a `LifterRowKeyed` here is a compile error, not a
runtime throw. The `@table`-parent `@splitQuery` callers always have a
`ParentKeyed` already (the catalog drives them), so the narrowing is a
no-op at every existing call site.

#### 2b. `buildRecordParentKeyExtraction` (rename + narrowed parameter)

Rename the existing `buildRecordKeyExtraction`
([`GeneratorUtils.java:187-214`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/GeneratorUtils.java))
to `buildRecordParentKeyExtraction` and **narrow its parameter type from
`BatchKey` to `BatchKey.RecordParentBatchKey`**. The sealed switch then has
exactly two arms — the only two permits — and no throwing arms for
@service-only variants:

```java
static CodeBlock buildRecordParentKeyExtraction(
        BatchKey.RecordParentBatchKey batchKey,   // was: BatchKey
        GraphitronType.ResultType resultType, String jooqPackage) {
    TypeName keyType = keyElementType(batchKey);
    return switch (batchKey) {
        case BatchKey.RowKeyed rk        -> buildFkRowKey(rk, keyType, resultType, jooqPackage);
        case BatchKey.LifterRowKeyed lrk -> buildLifterRowKey(lrk, keyType, resultType);
    };
    // No `default`, no throwing arm for RecordKeyed / MappedRowKeyed /
    // MappedRecordKeyed — those permits do not extend RecordParentBatchKey,
    // so the compiler refuses to let a caller route them here. Mis-routing
    // is a compile error, not a runtime IllegalStateException.
}

@DependsOnClassifierCheck(
    key = "lifter-batchkey-is-lifterrowkeyed",
    reliesOn = "On the lifter path, RecordParentBatchKey resolves to "
        + "LifterRowKeyed (never RowKeyed) — the classifier produces a "
        + "LifterRowKeyed only when the parent is PojoResultType or "
        + "JavaRecordType with non-null fqClassName, so the backing-class "
        + "cast below is safe.")
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
`LifterRowKeyed` / `LiftedHop` respectively. The replacements lean on the
new model shapes: a `RecordParentBatchKey`-narrowed local for the BatchKey
side, and the `JoinStep.WithTarget` capability for the uniform accessors,
keeping a sealed switch only at the JOIN-on predicate where identity
genuinely matters.

**Cast 1 — BatchKey access.** The prelude's BatchKey local narrows from
`BatchKey` to `BatchKey.RecordParentBatchKey` (the only two BatchKey permits
that ever reach this prelude). The cast collapses to a side-aware sealed
switch:

```java
List<ColumnRef> sourceTypeColumns = switch (batchKey) {
    case BatchKey.RowKeyed rk        -> rk.parentKeyColumns();
    case BatchKey.LifterRowKeyed lrk -> lrk.targetKeyColumns();
};
```

The accessor names make the side distinction explicit at the read site;
there is no shared `keyColumns()` whose meaning the reader has to look up.

**Cast 2 — first-hop access and FK-chain loop, via `WithTarget`.**
`FkJoin` and `LiftedHop` share `targetTable`, `targetColumns`, and `alias`
through the new `JoinStep.WithTarget` capability (§Model). The prelude's
loop reads them uniformly through the capability — *not* through three
separate sealed-switch sites — and reserves a sealed switch only for the
JOIN-on predicate, which is the genuine identity fork. The single
ConditionJoin-impossibility check lifts to one early guard:

```java
@DependsOnClassifierCheck(
    key = "lifter-classifies-as-record-table-field",
    reliesOn = "On a record-parent path, joinPath steps are FkJoin or "
        + "LiftedHop (never ConditionJoin) — both implement WithTarget, so "
        + "the FK-chain loop reads target accessors uniformly through the "
        + "capability without a sealed switch per accessor.")
// Single-hop on the lifter path is enforced structurally:
// LifterRowKeyed holds one LiftedHop, not List<LiftedHop>. No
// @DependsOnClassifierCheck pairs with this guarantee, because there is
// no classifier check to pair with; the model shape itself is the source.
// Find-usages on LifterRowKeyed.hop recovers the same navigation.
private static PreludeBindings emitParentInputAndFkChain(...) {
    // Single early identity guard: the @record-parent path admits no
    // ConditionJoin steps (the @table-parent path takes ConditionJoin).
    for (JoinStep step : joinPath) {
        if (!(step instanceof JoinStep.WithTarget)) {
            throw new IllegalStateException(
                "ConditionJoin cannot appear on a record-parent path");
        }
    }

    // Uniform target-accessor reads via the capability — no per-accessor
    // sealed switch.
    JoinStep.WithTarget firstHop = (JoinStep.WithTarget) joinPath.get(0);
    for (int i = 0; i < joinPath.size(); i++) {
        var step = (JoinStep.WithTarget) joinPath.get(i);
        TableRef tgt = step.targetTable();
        ClassName jooqTableClass = ClassName.get(jooqPackage + ".tables", tgt.javaClassName());
        body.addStatement("$T $L = $T.$L.as($S)",
            jooqTableClass, aliases.get(i), tablesClass, tgt.javaFieldName(),
            fieldName + "_" + step.alias());
    }
}
```

The downstream JOIN-on predicate is the one place identity actually
matters: on the catalog-FK path it reads `FkJoin.sourceColumns` (parent-side
FK columns); on the lifter path it reads `LiftedHop.targetColumns` (the
DataLoader key tuple *is* the target-column tuple by §Model construction).
That site stays a sealed switch — exactly the case the principle reserves
sealed switches for:

```java
List<ColumnRef> joinOnCols = switch (joinPath.get(0)) {
    case JoinStep.FkJoin fk          -> fk.sourceColumns();
    case JoinStep.LiftedHop lh       -> lh.targetColumns();
    case JoinStep.ConditionJoin _    -> throw new IllegalStateException(
        "ConditionJoin cannot appear on a record-parent path");
};
```

After this landing, the prelude has no `instanceof` chain and no defensive
cast where the accessor is uniform; sealed-switch usage is reserved for the
identity fork that genuinely varies. The two paired-key load-bearing facts
(`lifter-classifies-as-record-table-field` and
`lifter-batchkey-is-lifterrowkeyed`) cite distinct keys so a future
relaxation of either guarantee fails the audit only at the consumers that
actually relied on the relaxed half.

The `parentInput` VALUES table's column types come from the same side-aware
accessor used at Cast 1, and on the lifter path those are the target-side
columns, which match the lifter's `RowN` type-args by Invariant #4. The
existing rows-method already binds parent-input values via the column's
jOOQ `DataType` (per the *Column value binding* emitter convention,
`DSL.val(rawValue, col.getDataType())`); `LifterRowKeyed` inherits this
without an emitter change because the side-aware accessor returns
`ColumnRef` instances whose `getDataType()` is the same shape the
`RowKeyed` path already feeds.

#### 2e. Validator updates and load-bearing audit

Two validator-side changes ship with this landing: a sealed-switch update
on the existing `JoinStep`-keyed reader, and the new
single-cardinality gate that closes Invariant #10.

**Sealed-switch update.** As of trunk,
[`GraphitronSchemaValidator`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/GraphitronSchemaValidator.java)
does not branch on `BatchKey` variants in any arm: zero hits for
`BatchKey`, `RowKeyed`, or `RecordKeyed`. The arms relevant to
lifter-classified fields, `validateRecordTableField` (`:664-667`) and
`validateRecordLookupTableField` (`:668-678`), call only
`validateReferencePath` (a no-op stub at `:731-733`) and
`validateCardinality` (switch over `FieldWrapper`, no `BatchKey`
involvement). `validateReferenceLeadsToType` (`:370-384`) reads
`path.getLast().targetTable`; with `JoinStep.LiftedHop` added, this read
becomes either a two-arm sealed switch or — preferably — a `WithTarget`
capability cast, matching the prelude pattern in §2d, and returns the
field's `@table` by construction.

**Invariant #10 — single-cardinality gate.** Promote the existing
`SplitRowsMethodEmitter.unsupportedReason` runtime stub to a build-time
validator rejection. Add an arm to both `validateRecordTableField` and
`validateRecordLookupTableField`:

```java
private Optional<String> validateRecordTableFieldCardinality(
        RecordTableField field) {
    return switch (field.cardinality()) {
        case LIST -> Optional.empty();
        case SINGLE -> Optional.of(
            "RecordTableField '" + field.parentTypeName() + "."
                + field.name() + "' returns a single-cardinality '"
                + field.elementTypeName() + "'; only list returns ('[T]') "
                + "are supported in this release");
    };
}
```

(Same shape on `RecordLookupTableField`.) The validator's "what's
unimplemented?" set lives next to the dispatcher's
`NOT_IMPLEMENTED_REASONS.keySet()` (or its successor when R5's
status-map collapse lands), so the two stay in sync. The
`SplitRowsMethodEmitter.unsupportedReason` stub is replaced by an
`IllegalStateException` — reaching it post-validate means a classifier bug,
not an "unsupported but tolerated" outcome. When list-emission for these
two variants ships, the validator gate flips off in the same landing as
the emitter arm.

The classifier-side AUTHOR_ERROR rejections (Invariants #1-#9) remain
promoted to build-time errors by the existing
`UnclassifiedField` → validator pipeline; no further new gate is needed.

**Load-bearing annotation audit.** The consumer-side annotations introduced
in §2d cite two paired keys: `lifter-classifies-as-record-table-field` and
`lifter-batchkey-is-lifterrowkeyed`, each paired with a
`@LoadBearingClassifierCheck` producer in §1e.
[`LoadBearingGuaranteeAuditTest`](../graphitron/src/test/java/no/sikt/graphitron/rewrite/model/LoadBearingGuaranteeAuditTest.java)
walks the rewrite module's compiled output, groups by key, and fails on
any consumer whose key has no producer (or any duplicate producer); the
test is unchanged by this landing. The single-hop guarantee is *not* a
keyed fact: it is a structural model property (`LifterRowKeyed` holds
one `LiftedHop`, not a list), documented in a plain javadoc comment on
the §2d consumer (see §1e for the rationale: a `@DependsOnClassifierCheck`
without a paired producer would either bend the audit rule or require a
dummy producer that says nothing the type system doesn't already say).
Splitting the formerly-monolithic `lifter-path-shape` key into two paired
keys still ensures a future relaxation of either fact fails the audit
precisely at the consumers that actually relied on the relaxed half.

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
   `field.batchKey() instanceof BatchKey.LifterRowKeyed lrk`,
   `lrk.lifter().declaringClass()` matching the `ClassName` from the
   directive, and `field.joinPath().get(0) == lrk.hop()` (object identity —
   the single source of truth invariant). The list has size 1 by type
   construction (`LifterRowKeyed` holds a single `LiftedHop`, never a list).
   (Per *rewrite-design-principles* "Pipeline tests are the primary
   behavioural tier": no code-string assertions on generated method
   bodies — those are banned at every tier.)
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
rejections in `FieldBuilder.classifyChildFieldOnResultType` already
reference this roadmap item by hint; tighten that into a directive
recommendation now that the directive exists.

#### 3a. Update rejection messages

Change both rejection strings in `FieldBuilder.classifyChildFieldOnResultType`
(grep for `"requires a FK join path"` — the `RecordLookupTableField` arm sits
just ahead of the `RecordTableField` arm) from:

> "RecordTableField requires a FK join path and a typed backing class for batch key extraction"

to:

> "RecordTableField requires a FK join path and a typed backing class for
> batch key extraction; for free-form DTO parents, supply
> @batchKeyLifter on the field"

Identical edit on the `RecordLookupTableField` arm. The existing
`dtoSourcesRejectionReason` in `ServiceCatalog` already points at this
roadmap file by name (grep for `batchkey-lifter-directive`); update it to
point at the directive instead now that the directive ships.

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
- New `BatchKey.LifterRowKeyed` permit (sealed hierarchy now five variants);
  the variant holds its `JoinStep.LiftedHop` directly so the target-side
  column tuple has a single source of truth and the single-hop invariant
  is type-system-enforced
- New `BatchKey.ParentKeyed` and `BatchKey.RecordParentBatchKey` sealed
  sub-interfaces; the four catalog records now expose `parentKeyColumns()`
  (renamed from `keyColumns()`) and `LifterRowKeyed` exposes
  `targetKeyColumns()`. The interface-level `BatchKey.keyColumns()`
  accessor is removed (a shared accessor with variant-dependent meaning
  violated *Sealed hierarchies over enums*).
- New `JoinStep.LiftedHop` permit (sealed hierarchy now three variants),
  new `JoinStep.WithTarget` capability mixed in by `FkJoin` and `LiftedHop`,
  and new `LifterRef(ClassName, String)` typed reference
- `GeneratorUtils.buildRecordKeyExtraction` renamed to
  `buildRecordParentKeyExtraction`; parameter narrows from `BatchKey` to
  `BatchKey.RecordParentBatchKey` so mis-routing the @service-only permits
  here is a compile error rather than a runtime throw
- `GeneratorUtils.buildKeyExtraction` parameter narrows from `BatchKey` to
  `BatchKey.ParentKeyed` for the same reason
- `TypeFetcherGenerator.buildRecordBasedDataFetcher` no longer casts to
  `BatchKey.RowKeyed`
- `SplitRowsMethodEmitter.emitParentInputAndFkChain` reads target accessors
  uniformly via the `JoinStep.WithTarget` capability; sealed-switch usage
  is reserved for the JOIN-on predicate (the genuine identity fork). The
  `(JoinStep.FkJoin)` and `(BatchKey.RowKeyed)` casts are removed.
- New invariant + validator gate: `RecordTableField` and
  `RecordLookupTableField` reject single-cardinality returns at validate
  time (Invariant #10), promoting the previous
  `SplitRowsMethodEmitter.unsupportedReason` runtime stub to a build-time
  error. The stub is replaced by an `IllegalStateException` (post-validate
  reachability is a classifier bug).
- New per-fact `@LoadBearingClassifierCheck` /
  `@DependsOnClassifierCheck` key pairs:
  `lifter-classifies-as-record-table-field` and
  `lifter-batchkey-is-lifterrowkeyed` (both with producer pairs in
  `FieldBuilder.resolveBatchKeyLifter`). The single-hop invariant is a
  structural model property (`LifterRowKeyed` holds one `LiftedHop`, not
  a list) documented in a plain javadoc comment on the rows-method
  prelude rather than as a keyed fact;
  `LoadBearingGuaranteeAuditTest` is unchanged.
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
- **Single-cardinality emission.** `RecordTableField` and
  `RecordLookupTableField` reject single-cardinality returns at validate
  time per Invariant #10 (this landing promotes the existing emitter stub
  to a validator gate). Lifting single-cardinality is a separate emitter
  expansion; when it ships, Invariant #10's validator gate is the single
  site to relax in the same landing as the emitter arm.
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
