---
id: R413
title: "Parent-input VALUES table in split/reference batch fetchers drops converter-backed column DataType, breaking joins on numeric-domain keys"
status: In Review
bucket: bug
priority: 3
depends-on: []
created: 2026-07-01
last-updated: 2026-07-02
---

# Parent-input VALUES table in split/reference batch fetchers drops converter-backed column DataType, breaking joins on numeric-domain keys

## Problem

The DataLoader rows-method emitted for a `@splitQuery` / `@reference` batch child correlates the target table to a `VALUES (idx, parent_key…)` derived table. `SplitRowsMethodEmitter.emitParentInputAndFkChain` builds each parent-key cell straight from the DataLoader key row (`SplitRowsMethodEmitter.java:265-272`):

```java
rowArgs.add(", k.field$L()", i + 1);   // Field<T> at the key's *Java* type
```

and types both the `Row<N>` type-args (`:189-194`) and the JOIN-predicate RHS field lookup (`emitFromBridgeAndParentJoin`, `:367-378`, `parentInput.field(sqlName, <ColumnClass>.class)`) from `ColumnRef.columnClass()` — the column's **Java** class, not its jOOQ `DataType`. For a column carrying a `Converter`, `columnClass()` is the converted user type (e.g. `java.lang.String`), so the `VALUES` literal renders at the wrong SQL type and the correlation JOIN compares mismatched types.

Concretely, against a real consumer schema (utdanningsregisteret): `Campus.ORGANISASJONSKODE` is the domain `kodeverk.kode_numerisk_domain` (over `BIGINT`) with a jOOQ `Converter` (`Long ⇄ String`). The key value is read as `String` and re-wrapped as a bare `Field<String>`, so the `VALUES` cell renders as `character varying`. The emitted correlation JOIN is then:

```
campus_c0.organisasjonskode  (kode_numerisk_domain / bigint)  =  parentInput.organisasjonskode  (character varying)
```

PostgreSQL has no `bigint-domain = varchar` operator, so the fetch throws `operator does not exist: kodeverk.kode_numerisk_domain = character varying`, and every parent row's child (`UtdanningsmulighetCampus.campus`, `.organisasjon`, …) nulls out with a `DataFetchingException`. Any `@splitQuery` / `@reference` whose join key uses a converter-backed or non-`varchar` domain column is affected; keys on plain `Integer`/`varchar` columns are unaffected, which is why the defect is invisible on the Sakila fixtures.

## Root cause

There are two `VALUES (idx, …)` builders in the generator and they disagree on cell typing:

- **Lookup-input path (correct):** `ValuesJoinRowBuilder.cellsCode` (`util/ValuesJoinRowBuilder.java:133-144`) emits each cell as `DSL.val(value, <tableLocal>.<COL>.getDataType())`, so jOOQ binds through the column's registered `Converter`/`DataType` and renders a correctly-typed JDBC bind (no SQL `CAST`). This is the same `DSL.val(v, col.getDataType())` idiom used pervasively elsewhere (`TypeFetcherGenerator`, `LookupValuesJoinEmitter`, `ConnectionHelperClassGenerator`, …).
- **Parent-input path (buggy):** `SplitRowsMethodEmitter` bypasses that idiom and builds cells from the raw key `Field`, typed by `columnClass()`.

A spec-time audit found the buggy cell shape in four places, not two:

1. `SplitRowsMethodEmitter.emitParentInputAndFkChain` (`:264-271`), the shared prelude for the list / single / connection split rows methods, plus the paired `parentInput.field(sqlName, <Class>.class)` lookups in `emitFromBridgeAndParentJoin` (`:367-378`).
2. `SplitRowsMethodEmitter.emitRecordTableMethodBody` (`:609-666`), the `@tableMethod` variant's duplicated copy (cells + ON-predicate lookups).
3. `SplitRowsMethodEmitter.buildServiceTableLift` (`:1337-1382`), the R285 lift-back re-projection: `DSL.val(rec.get(Tables.X.PK))` with no `DataType`, plus `projectionInput.field(sqlName, <Class>.class)` lookups. Converter-backed target PKs hit the same mismatch.
4. `MultiTablePolymorphicEmitter.buildParentInputValuesEmitter` (`:1783-1819`) with its per-branch ON lookups (`:1749-1758`): the polymorphic batched rows methods carry the same untyped `k.fieldN()` cells keyed on the parent PK.

## Design

**Fix at the VALUES-emission seam, not at key construction.** DataLoader key rows are produced by several generator sites (`GeneratorUtils.buildKeyExtraction` and siblings) and by developer code (`@sourceRow` lifters return the `RowN` directly), so retyping at construction can never cover every producer. The rows-method's VALUES emission is the one seam every key passes through and is where the SQL typing requirement lives; it is also where the lookup-input path already solves the same problem.

**Rebind each cell as `DSL.val(<scalar>, <owner>.<COL>.getDataType())`.** jOOQ then converts through the column's registered `Converter` and binds at the DB type. Alternatives were verified against the jOOQ 3.20.11 sources and rejected:

- `Field.coerce(dataType)` cannot fix this: `Coerce.accept` delegates rendering to the wrapped `Val`, which binds with its own inferred `DataType`, so the bind stays `varchar`.
- `Field.cast(dataType)` renders a SQL `CAST`, routing the conversion through SQL instead of the Java `Converter`; wrong in general (a `Boolean ⇄ "J"/"N"` converter has no SQL-cast equivalent).

So the scalar value must be in hand. `RecordN`-shaped keys expose it via `k.valueN()`. `RowN`-shaped keys do not (jOOQ `Row` is a schema construct with no value accessors); their cells are bind `Param`s by construction (`DSL.row(value, …)` wraps each value via `DSL.val`), so the generated code extracts the value through a small per-fetcher-class helper emitted alongside the scatter helpers:

```java
private static Object parentKeyCellValue(Field<?> f) {
    if (f instanceof Param<?> p) {
        return p.getValue();
    }
    throw new IllegalStateException(
        "DataLoader key cell must be a bind value (DSL.row over scalar values); got " + f);
}
```

Statement body with a loud throw, not an inline ternary, per the generated-code-legibility principle. For generator-built keys the `Param` cast always holds; for `@sourceRow` lifter keys it becomes a documented contract with a diagnostic instead of today's silently mistyped bind. The contract cannot be mirrored at validate time (whether the lifter body's `RowN` cells are `Param`s is invisible to the signature reflection `SourceRowDirectiveResolver` does), so the runtime diagnostic is the honest fallback; a live execution test pins it (see Tests).

**The `Wrap` axis picks the extraction.** The existing fork `boolean isAccessor = sourceKey.reader() instanceof Reader.AccessorCall` chooses `k.valueN()` vs `k.fieldN()`, but the property actually consulted is the Java row shape, which is the `SourceKey.Wrap` axis (`Record` has `valueN()`, `Row` does not). Rewrite the branch as a switch on `sourceKey.wrap()` (`Wrap.TableRecord` unreachable at this seam); it then reads the axis it forks on and stays correct if the reader-wrap coupling is ever loosened.

**The owner table is a model fact on `ParentCorrelation`, not an emit-time derivation.** The `DataType` handle needs `Tables.<OWNER>.<COL>`; `ColumnRef` carries no table, and which table owns `sourceKey.columns()` was resolved by the classifier when it chose the columns. Rather than re-deriving it per emit site, lift it onto `ParentCorrelation` (whose stated purpose is already to lift correlation forks out of the emitters) as a `parentKeyOwnerTable()` accessor, folding the fork once:

- `OnFkSlots` with `FkJoin` first hop → `fk.originTable()` (hop-0 origin is the side the key columns are drawn from, per `deriveSplitQuerySource` / `deriveFkRecordParentSource` / `SourceRowDirectiveResolver`).
- `OnFkSlots` with `LiftedHop` first hop → `targetTable()` (the key tuple IS the target-column tuple).
- `OnConditionJoin` → `parentTable()` (keys are the parent's own PK).

Sites 1 and 2 read the accessor (`RecordTableMethodField` carries `parentCorrelation`). Site 3 already has the owning table in a local (`table`, the re-projection target). Site 4 has no `ParentCorrelation`; its owner (the parent/hub table) is resolved at its own resolution site (`PolymorphicRecordParentResolution.Resolved` carries it) and gets threaded into the batched rows builders. A `SourceKey.columnsTable` component was considered and rejected: `Reader.ServiceUntypedRecord` keys have columns with no owning table, so the component would be nullable with the invariant enforceable only at consumption sites, whereas `ParentCorrelation` is non-null exactly where this seam is reached.

**One shared cell authority.** Extend `ValuesJoinRowBuilder` with a `cellsCode` variant whose table reference is a `TableRef`/`CodeBlock` constants-class expression (the current one takes a Java-local name string), and route all four parent-input sites through it. The class's single-authority javadoc claim only becomes true with site 4 included, so site 4 is in scope here, not a follow-up: leaving it as a divergent copy recreates exactly the byte-for-byte-copy drift R324 was about.

**JOIN-predicate lookups switch to `field(sqlName, <owner>.<COL>.getDataType())`** at the same sites. Not load-bearing (the derived table's column SQL types come from the cell binds; the predicate renders a column reference either way), included so the looked-up `Field`'s type metadata stays faithful and symmetric with the cells.

**What does not change.** `RowN`/`RecordN` generic type-args stay `columnClass()`-typed: the converter's user type IS the Java-side type; only the bind `DataType` was defective. Key construction (`buildKeyExtraction` and siblings) stays as-is.

## Implementation

- `model/ParentCorrelation.java`: add `parentKeyOwnerTable()` (default accessor folding the three-arm fork above).
- `generators/util/ValuesJoinRowBuilder.java`: `cellsCode` variant taking a constants-class table expression; keep the existing local-name variant for the lookup/dispatcher callers.
- `generators/SplitRowsMethodEmitter.java`:
  - `emitParentInputAndFkChain`: typed cells via the shared builder, switched on `sourceKey.wrap()` (`Record` → `DSL.val(k.valueN(), …)`, `Row` → `DSL.val(parentKeyCellValue(k.fieldN()), …)`).
  - `emitFromBridgeAndParentJoin` + `emitRecordTableMethodBody` + `buildServiceTableLift`: same cell and lookup changes.
  - `buildParentKeyCellValueHelper()`: the new per-fetcher-class helper, emission gated like the scatter helpers (only for classes with a Row-keyed parent-input rows method).
- `generators/MultiTablePolymorphicEmitter.java`: `buildParentInputValuesEmitter` cells + per-branch ON lookups; thread the parent/hub `TableRef` from the resolution site into the batched list/connection rows builders.
- `generators/TypeFetcherGenerator.java`: helper emission gate wiring.
- Existing expectation updates: every split/polymorphic rows-method's emitted text changes; update affected unit/pipeline expectations (shape-level; no new body-substring assertions).

## Tests

The execution tier is the behavioural pin (generated-body substring assertions are banned at every tier); the sakila-example compile tier proves type-correctness of the new emitted shapes for free.

- Fixture DB (`graphitron-sakila-db/src/main/resources/init.sql`): `CREATE DOMAIN` over `BIGINT` (mirroring `kodeverk.kode_numerisk_domain`) plus a small parent/child table pair keyed on it, e.g. `converter_org(org_code <domain> PK, name)` and `converter_campus(campus_id PK, org_code <domain> REFERENCES converter_org)`. Bump `jooq.codegen.schema.version`.
- Codegen converter: a `<forcedTypes>` entry on the public-schema `jooq-codegen` execution mapping the domain to `java.lang.String` via a `Converter<Long, String>` class added to `graphitron-fixtures-codegen` (already on the codegen plugin classpath).
- `graphitron-sakila-example` schema + querydb execution tests:
  - single-cardinality `@splitQuery` over the converter-backed FK (the reported `Campus.organisasjon` shape); pre-fix this reproduces `operator does not exist: <domain> = character varying`, post-fix it returns data;
  - list-cardinality `@splitQuery` (child-holds-FK) over the same key;
  - a `@sourceRow` lifter case whose lifted key column is converter-backed (lifter + backing class in `graphitron-sakila-service`, alongside the existing R110 fixtures), pinning the `parentKeyCellValue` `Param` contract with a live test rather than javadoc.
- Connection cardinality shares `emitParentInputAndFkChain` with list/single, so no separate converter fixture is required there. Polymorphic (site 4) coverage rides on the existing polymorphic execution fixtures plus the shared-authority routing; a converter-backed polymorphic parent key fixture is optional if cheap.

See [composite-key-row2-source-row-coverage](composite-key-row2-source-row-coverage.md) (R116) for the adjacent Row2 source-key coverage.
