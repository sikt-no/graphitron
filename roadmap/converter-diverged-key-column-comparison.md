---
id: R885
title: "Converter-diverged FK key columns emit non-compiling column comparisons"
status: Backlog
bucket: correctness
priority: 1
depends-on: []
created: 2026-08-31
last-updated: 2026-08-31
---

# Converter-diverged FK key columns emit non-compiling column comparisons

The generator compares two database columns by writing `a.eq(b)` in the code it emits. That
only compiles when jOOQ gives both columns the same Java type. A jOOQ *converter* (configured
in a consumer's jOOQ codegen as a `<forcedType>`) changes the Java type of one column: an Oracle
`NUMBER` code column can be exposed as `String` rather than `Short`. When a converter is attached
to only one end of a foreign key, the two ends **diverge**: the referencing column stays
`Field<Short>` and the referenced column becomes `Field<String>`. The generator does not look at
either type, so it emits `l0.LANDNR.eq(table.LANDNR)`, which does not compile because
`Field<String>.eq` has no overload accepting a `Field<Short>`. The whole generated module fails to
compile, and the only way a consumer can proceed is to delete the schema field. Reported as
[issue 540](https://github.com/sikt-no/graphitron/issues/540) against `10.0.0-RC35`, blocking a
subgraph upgrade from `9.3.2`; converters on referenced key columns are ordinary in FS databases,
so other upgrades are likely blocked behind the same fault.

## Why the existing converter fixture does not catch this

`graphitron-sakila-db` already has a converter fixture: the `converter_org` / `converter_campus`
pair in `init.sql`, whose `org_code` columns are typed by the `org_code_domain` domain, with a
`<forcedType>` in `graphitron-sakila-db/pom.xml` selecting **by type** (`includeTypes:
org_code_domain`). Selecting by type applies the converter to both ends of the foreign key at once,
so both sides are `Field<String>` and no divergence arises. The consumer configuration in the issue
selects **by column path** (`includeExpression: .*\.LAND\.LANDNR`), which is what makes the two
ends diverge. The fixture therefore exercises converter binding but not converter divergence, and
no test in the tree pins the diverged shape.

## Emission sites

The reported break is one of several. `ColumnRef` already carries the per-column Java type
(`columnType()`, decided once at the catalog boundary from `Field.getType()`), so every site below
has the information it needs and simply does not consult it. Confirmed by reading; the blast radius
should be re-derived from a real diverged fixture before the fix is scoped.

* `JoinFragments.emitCorrelationWhere` writes `firstAlias.<target>.eq(parentAlias.<source>)` for the
  step-0 correlation of a reach path. This is the shape the issue reports. Note that it emits an
  explicit column equality regardless of keying, where the sibling join emitters dispatch on keying
  and render a catalog foreign key as `.onKey(Keys.<CONSTANT>)`, which is type-blind and therefore
  unaffected. A correlated subquery's `WHERE` has no `.onKey` equivalent, so this site needs a real
  answer rather than a redirect.
* `JoinFragments`'s name-matched join arms (both the `On` and the `JoinBasis` overload) write the
  same column-to-column equality for an inferred, non-catalog pairing.
* `ProjectionUnitRenderer`'s pivot-multiset correlation writes `<pivotAlias>.<target>.eq(table.<source>)`.
* `DiscriminatedTableFragments`'s joined-detail `ON` chain writes `<detailAlias>.<source>.eq(<base>.<target>)`.
* `BatchedRowsFragments` compares a column against a parent-input `VALUES` cell looked up as
  `parentInput.field("<sqlName>", <ownerTable>.<COL>.getDataType())`. The lookup takes its
  `DataType` from the parent column and the comparison receiver from the child column, so a
  divergence mismatches the same way.
* The `VALUES` row machinery in `ValuesJoinRowBuilder` types each row cell from one side's
  `ColumnRef.columnType()` while `cellsCode` binds the value with the other side's `getDataType()`.
  Under divergence the two disagree, so this needs checking on the batched and lookup paths.

## Candidate fix

jOOQ's `Field.coerce(Field)` reinterprets a field's Java type without emitting a SQL `CAST`, so
`l0.LANDNR.eq(table.LANDNR.coerce(l0.LANDNR))` compiles, renders the same SQL text as today, and
keeps the predicate index-friendly. `cast` is the wrong lever here because it puts a real `CAST`
around an indexed column. Emitting the coerce only when the two `columnType()` values are present
and differ keeps every existing approved output byte-identical and makes the divergence visible in
the generated source exactly where it exists. Sites that mix a column reference with a bound value
need the companion rule stated explicitly: bind the value with the `DataType` of the column the
value was *read from*, then coerce the comparison to a single Java type.

`columnType()` is nullable for hand-built placeholder refs, so the comparison has to be
null-tolerant rather than assuming both sides decode.

## Open questions for Spec

* What did `9.3.2` emit here? The reporter states it compiled and was correct, but the shape it
  produced is not recorded in the issue and would inform whether `coerce` matches prior behaviour.
* Is a diverged foreign key ever *semantically* wrong rather than merely awkward to type, for
  example when the converter is not order-preserving and the key participates in pagination
  ordering? If so, the classifier may owe a rejection for some subset rather than a coerce for all.
* Does the fixture want a new table pair, or a second `<forcedType>` selecting one existing column
  by path? A new pair avoids disturbing the `converter_org` / `converter_campus` coverage, at the
  cost of another table in `init.sql`.
