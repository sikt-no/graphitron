---
id: R316
title: "Decompose SourceKey: evict target/path to TableTargetField slots, migrate source-object facts to Carrier.Source, leave a source-field key"
status: Backlog
bucket: structural
priority: 3
theme: structural-refactor
depends-on: []
created: 2026-06-16
last-updated: 2026-06-16
---

# Decompose SourceKey into a source-field key

`SourceKey` is `(target, columns, path, wrap, cardinality, reader)`, but a walk under the
source-object / source-field vocabulary (see R222's *What `SourceKey` decomposes into*) shows it
bundles three separable concerns, only one of which is a source *key*. This item performs the
mechanical simplification: shrink `SourceKey` to the genuine source-field key and move the rest to
slots that already exist (or, for the source-object facts, to the carrier). The bulk of it is
deletion, not new design.

## What the model says today

`SourceKey` carries, per source-bearing field:

- `target` (TableRef) — the table the rows-method reads `FROM`; the element table an accessor's
  return value projects into; the leaf of the join path. Confirmed by tracing
  `SplitRowsMethodEmitter` (`.from(terminalAlias)`, `terminalAlias` = last alias = `returnType.table()`)
  and `GeneratorUtils.buildAccessorKey` (`elementTable = sourceKey.target()`, the accessor's returned
  record projects `.into(Tables.<target>.<pkCols>)`). It is the **target** table, correctly named;
  it is `null` only for the parent-IS-source polymorphic case.
- `path` (List<JoinStep>) — the FK/`@reference` join route from the source object to `target`.
- `columns`, `wrap`, `cardinality`, `reader` — the source-side key plus its read mechanism.

## The redundancy (target / path)

`target` and `path` already have first-class homes on the `TableTargetField` sealed interface:

- `ReturnTypeRef.TableBoundReturnType returnType()` — the target table (`returnType.table()`).
- `List<JoinStep> joinPath()` — the join route.

These two slots are universal across every table-bound variant, including the non-source ones
(`TableField`, `LookupTableField`, `TableMethodField`) that carry **no `SourceKey` at all** — proof
that target and path are properties of any table-bound field, orthogonal to whether it is
DataLoader-backed. The source-bearing split variants carry both the field-level slots and a
`SourceKey` whose `target`/`path` duplicate them.

The duplication is provably dead:

- **`SourceKey.path()` has zero readers in the generator.** Every rows-method emitter reads the
  field-level slot (`stf.joinPath()`, `rtf.joinPath()`, `lf.joinPath()`, ...). The `SourceKey` copy
  is constructed and never consumed.
- **`SourceKey.target()` has four reader sites** (`CatalogBuilder`, `FetcherEmitter`,
  `GeneratorUtils` x3 methods), each on a carrier that also exposes `returnType.table()` for the same
  table. The rows-method prelude itself reads `returnType.table()` (`terminalTable`), not
  `sourceKey.target()`.

## The source-object facts (migrate to the carrier)

Three facts inside `SourceKey` are properties of the source **object** (the parent at
`env.getSource()`), identical for every field defined on that parent type, yet re-encoded per field:

- the source object's **shape** — already on `Carrier.Source` as `SourceShape` (`Table | Record`);
  `Reader`'s permit set re-implies it (column/accessor/lifter readers ≈ a table-row source;
  service/result/produced readers ≈ a record source).
- the source object's **backing class** — today in `AccessorRef.parentBackingClass` and the
  `@sourceRow` lifter's cast target (`(BackingClass) env.getSource()`).
- the source object's **`env.getSource()` envelope** — `SourceEnvelope` (`DIRECT` vs
  `OUTCOME_SUCCESS`), carried per-field on `Reader.ResultRowWalk` but deliberately **not** on
  `Reader.ProducedRecordRead`, which already hoists it to the type level as `sourceIsOutcome`. That
  asymmetry is the live symptom: the model already disagrees with itself on whether the envelope is a
  per-field or a per-parent-type fact.

These belong on a richer `Carrier.Source` source-object descriptor (shape + backing class + envelope),
not smeared across each field's `SourceKey`. `Reader` should keep only the field's
extraction-mechanism axis, not the source-object-shape axis it currently conflates.

## What stays: the source-field extraction strategy

After the moves, the irreducible content is the extraction strategy itself, `reader` plus its method
refs (`AccessorRef`'s `parentBackingClass` / `methodName` / `elementClass`, `LifterRef`'s
`declaringClass` / `methodName`), which nothing else can derive. Of the other three components, two are
provably redundant and one is a pre-resolution convenience:

- **`wrap` — derive from `reader`.** It is a pure function of the extraction arm: `AccessorCall ⇒
  Wrap.Record`, every other row-key reader (`ColumnRead` / `SourceRowsCall` / PK-off-record) ⇒
  `Wrap.Row`. The compact-constructor invariants (`SourceRowsCall ⇒ Row`, `AccessorCall ⇒ Record`) are
  themselves the proof that `wrap` is not an independent axis. `keyElementType()` and
  `buildKeyExtraction`'s `switch` take `reader` instead.
- **`cardinality` — read `returnType.wrapper().isList()`.** The source-field arity is the field's
  return-type list-ness, on the surviving `TableTargetField.returnType` slot. Construction builds it
  from exactly that for `ColumnRead` / `SourceRowsCall` / PK-off-record, and for `AccessorCall` the
  `AccessorMatch.CardinalityMismatch` rejection forces the accessor's arity to equal the field's, so
  `cardinality == returnType.wrapper().isList()` universally.
- **`columns` — keep only as a pre-resolution.** Each arm's key tuple is derivable from `reader` +
  surviving slots (`AccessorCall` / PK-off-record: `returnType.table().primaryKeyColumns()`;
  `SourceRowsCall`: the `LiftedHop` slots in `joinPath`; `ColumnRead`: `joinPath.get(0)`'s
  `FkJoin.sourceSideColumns()` or the parent PK). The one reason to retain it is the portable
  `(wrap, columns)` consumers (`MethodRef.Param.Sourced`, `ParamSource.Sources`) that hold the key
  shape without a full field / `joinPath` in hand, the same reason the static
  `keyElementType(wrap, columns)` overload exists. Keep as pre-resolution or push the derivation to
  those sites; a judgment call for the slice.

The net: `SourceKey` collapses to roughly the `Reader` sealed type (the extraction strategy + refs). It
stops being a tuple and becomes "the source-field extraction strategy", which is exactly the job:
converting the values read off the source field into a DataLoader row key so the rows-method can
re-enter SQL. (`cardinality`, where it survives in the derivation, is the field's own one-vs-many, the
*source-field* arity — distinct from the source-object arrival count on `Carrier.Source`; see R222's
*bulk is a slot*.)

The `parentSourceKey` on `InterfaceField` / `UnionField` is the one place `SourceKey` is bent to
describe the source *object* (parent-identity extraction; `cardinality` hardcoded `ONE` = "one
parent"). It belongs with the source-object descriptor, not a field key — separating it is what lets
the remaining extraction-strategy reader mean source-field extraction unambiguously.

## Scope

Roughly independent slices, smallest blast radius first:

1. **Delete `SourceKey.path()`.** No readers; remove the component and the construction-site
   arguments. The path-referencing compact-constructor invariant (`ServiceTableRecord` target-aligned
   ⇒ empty `path`) relocates to the field-assembly site where `returnType` + `joinPath` are in hand.
2. **Repoint the four `SourceKey.target()` readers** at the carrier's `returnType.table()`, then
   delete `SourceKey.target()`. The `wrap == target.recordClass()` invariant becomes a cross-slot
   check at assembly (`wrap` vs `returnType.table().recordClass()`).
3. **Derive `cardinality`, don't carry it.** It equals `returnType.wrapper().isList()` in every arm
   (the `AccessorMatch.CardinalityMismatch` rejection forces the accessor case to agree), so delete the
   component and read the field slot. This retires the "source-side / target-side" contradiction in the
   `SourceKey.Cardinality` javadoc outright rather than merely renaming it.
4. **Derive `wrap` from `reader`.** `wrap` is a pure function of the reader arm (`AccessorCall ⇒
   Record`, else `Row`; the compact-constructor invariants prove it), so `keyElementType()` and
   `buildKeyExtraction` take `reader` and `wrap` is deleted. Decide whether `columns` stays as a
   pre-resolution (for the portable `(wrap, columns)` consumers) or is derived at those sites. After
   slices 1–4, `SourceKey` is essentially the `Reader` extraction-strategy sealed type.
5. **Source-object descriptor on `Carrier.Source`** — fold shape + backing class + envelope into one
   descriptor; collapse the `ResultRowWalk`/`ProducedRecordRead` envelope asymmetry; narrow `Reader`
   to the extraction-mechanism axis. This slice coordinates with the carrier-dimension work in R305
   and the reentry-emit dissolution in R314; slices 1–4 do not.

## Relationship to other items

- **R222** (dimensional-model-pivot): umbrella. This is the `SourceKey`-specific decomposition its
  *What `SourceKey` decomposes into* subsection points at.
- **R305** (collapse-singlerecordtablefield-into-recordtablefield): expanded `Carrier.Source` with
  source-shape + cardinality and introduced `sourceIsOutcome`. Slice 5's source-object descriptor
  builds directly on that arm; slices 1-4 are independent of it.
- **R314** (dissolve-reentry-leaves-dimensional-emit): switches reentry emit onto the dimensional
  model rather than leaf identity; adjacent to slice 5's `Reader`/carrier reshaping, not blocking.

Not hard-blocked: slices 1-4 (the bulk of the line-count win) touch only `SourceKey`, its
construction sites, and the handful of named readers.
