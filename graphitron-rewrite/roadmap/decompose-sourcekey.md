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

## What stays: the source-field key

After the moves, `SourceKey` is the key extracted from the source field:

- `columns` — the key tuple lifted off the source object.
- `wrap` — its Java row shape (`Row | Record | TableRecord`).
- `cardinality` — the **source-field** arity (renamed off the misleading `SourceKey.Cardinality`;
  see R222's *bulk is a slot* — it is the field's own one-vs-many, distinct from the source-object
  arrival count on `Carrier.Source`).
- the extraction-mechanism half of `reader` (`ColumnRead` / `AccessorCall` / `SourceRowsCall` /
  PK-off-record), with the source-object-shape half gone to the carrier.

The `parentSourceKey` on `InterfaceField` / `UnionField` is the one place `SourceKey` is bent to
describe the source *object* (parent-identity extraction; `cardinality` hardcoded `ONE` = "one
parent"). It belongs with the source-object descriptor, not a field key — separating it is what lets
`SourceKey.cardinality` mean source-field arity unambiguously.

## Scope

Roughly independent slices, smallest blast radius first:

1. **Delete `SourceKey.path()`.** No readers; remove the component and the construction-site
   arguments. The path-referencing compact-constructor invariant (`ServiceTableRecord` target-aligned
   ⇒ empty `path`) relocates to the field-assembly site where `returnType` + `joinPath` are in hand.
2. **Repoint the four `SourceKey.target()` readers** at the carrier's `returnType.table()`, then
   delete `SourceKey.target()`. The `wrap == target.recordClass()` invariant becomes a cross-slot
   check at assembly (`wrap` vs `returnType.table().recordClass()`).
3. **Rename `SourceKey.Cardinality`** to name source-field arity (e.g. `SourceFieldCardinality`),
   killing the "source-side / target-side" contradiction in the javadoc.
4. **Source-object descriptor on `Carrier.Source`** — fold shape + backing class + envelope into one
   descriptor; collapse the `ResultRowWalk`/`ProducedRecordRead` envelope asymmetry; narrow `Reader`
   to the extraction-mechanism axis. This slice coordinates with the carrier-dimension work in R305
   and the reentry-emit dissolution in R314; the first three slices do not.

## Relationship to other items

- **R222** (dimensional-model-pivot): umbrella. This is the `SourceKey`-specific decomposition its
  *What `SourceKey` decomposes into* subsection points at.
- **R305** (collapse-singlerecordtablefield-into-recordtablefield): expanded `Carrier.Source` with
  source-shape + cardinality and introduced `sourceIsOutcome`. Slice 4's source-object descriptor
  builds directly on that arm; slices 1-3 are independent of it.
- **R314** (dissolve-reentry-leaves-dimensional-emit): switches reentry emit onto the dimensional
  model rather than leaf identity; adjacent to slice 4's `Reader`/carrier reshaping, not blocking.

Not hard-blocked: slices 1-3 (the bulk of the line-count win) touch only `SourceKey`, its
construction sites, and the handful of named readers.
