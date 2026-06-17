---
id: R23
title: "Multi-parent `NestingField` sharing: `TableField` arm"
status: Spec
bucket: architecture
priority: 6
theme: model-cleanup
depends-on: []
last-updated: 2026-06-17
---

# Multi-parent `NestingField` sharing: `TableField` arm

> Refreshed 2026-06-17 against current code. The plan predated R303 (fetcher reification),
> R290 / R305 (the dimensional leaf-set changes), and the nested-module split, so its symbol
> names, paths, and build commands had drifted. The core thesis is unchanged and re-verified:
> the fix is a single validator arm; the emitter already supports the shape.

## Overview

Allow a plain-object (`NestingField`) type to be used under multiple `@table` parents when its
fields include `ChildField.TableField` leaves, not just `ChildField.ColumnField` or nested
`NestingField`. The validator currently hard-rejects every non-Column, non-Nesting leaf in the
shared-shape check. Lift the gate for `TableField` only: the pair needs no additional shape check
beyond the class-equality gate already applied in `compareNestedFieldsShape`, because `returnType()`
derives from the single SDL declaration on the shared nested type. Divergent `joinPath` / `filters`
/ `orderBy` / `pagination` are legitimately per-parent: each parent's `$fields` emits its own
correlated subquery. No emitter or wiring changes needed; today's codegen already supports this shape
once the validator lets it through (re-verified against the post-R303 reified-fetcher path below).

## Current state

`GraphitronSchemaValidator.compareNestedFieldsShape` allows exactly two leaf shapes when a
`NestingField` type is used under multiple `@table` parents:

- `ChildField.ColumnField` — compared by `column().sqlName()` + `column().columnClass()`. Relies on
  jOOQ's name-based `Record.get(Field)` fallback at runtime to project the same-named column across
  parents.
- Inner `ChildField.NestingField` — recurses via
  `compareNestedFieldsShape(rnf, onf, repParent, otherParent, errors)`, threading the outer parent
  names so deep errors still name the original tables.

Everything else lands in the catch-all arm (`} else if (!(rf instanceof ChildField.NestingField))`)
with the self-contained "classifies as X which is not yet supported across multiple parents" message.
That catch-all currently swallows `TableField` along with the genuinely-unsupported BatchKey leaves
(`SplitTableField`, `SplitLookupTableField`, `RecordTableField`, `RecordLookupTableField`, and the
`Record*MethodField` family) and the composite NodeId carriers
(`CompositeColumnReferenceField`).

The gate is over-broad for `TableField`. Emission and wiring for a nested `TableField` are
parent-agnostic (see "Why this is emitter-safe"), so the only gap is the validator shape check.

The stale `#8` roadmap pointer the catch-all message once carried is already gone (removed when the
multi-parent work split into per-variant items), so the message text needs no change.

Real-world reports:
- `sis-graphql-spec`: `EmneStudieprogramKoblingPeriode` shared across `EmneStudieprogramkobling` and
  `StudieprogramEmnekobling`, where the shared `fraTermin` field classifies as `TableField`.
- A nested `Avsender` type shared across `DokumentMelding`, `KvitteringMelding`, and
  `GenerellMelding`, where the shared `organisasjon` field classifies as `TableField`.

The only workaround today is duplicating the nested type per parent in SDL.

## Desired end state

`compareNestedFieldsShape` recognises `ChildField.TableField` as a permitted multi-parent leaf. No
additional shape check is needed beyond the class-equality gate already applied upstream in the
method: both `returnType().returnTypeName()` and `returnType().wrapper()` derive from the single SDL
declaration of the field on the shared nested type, so they are identical by construction across
parents. Fabricating a mismatch would require a classifier bug, not a schema error, which is not what
this validator is for.

`joinPath`, `filters`, `orderBy`, `pagination` are legitimately per-parent and intentionally not
compared: they come from directives on each outer parent's field declaration (which points to the
shared nested type) and from the `@reference` resolution against that outer parent's table context.

### Why this is emitter-safe

No emitter or wiring changes are needed. Two properties of the current (post-R303) pipeline make
divergent per-parent `TableField` shapes safe:

1. **Per-parent `$fields` emission is self-correlated.** `TypeClassGenerator.emitSelectionSwitch` is
   called once per parent's `$fields` method with that parent's `tableArg`. Each selected
   `ChildField.TableField` is emitted through `InlineTableFieldEmitter.buildSwitchArmBody`, which
   generates a `DSL.multiset(...)` keyed off the field's own `joinPath` plus the caller's `tableArg`.
   Each parent's generated SQL embeds its own correlation.
2. **The nested DataFetcher is parent-agnostic.** Post-R303, `TableField` is a `PROJECTED_LEAF`
   (`TypeFetcherGenerator.PROJECTED_LEAVES`): the dispatch switch emits no fetcher method for it; the
   read of the already-projected multiset value is reified by `FetcherEmitter.bind` into a
   `public static` method on `<Type>Fetchers`, registered wrapped in `LightFetcher` (a source-only
   read). The read pulls the projected value from the source `Record` by field name and does not
   consult the outer parent table. The first-parent-wins registration in
   `FetcherRegistrationsEmitter.collectNestedTypes` (`putIfAbsent` on the nested-type name) threads
   the first-seen parent's table as `NestedTypeWiring.representativeParentTable`, but that is consumed
   only for column name-fallback resolution, not by the projected `TableField` read, so first-parent
   choice has no runtime effect for this leaf.

Verification to perform during implementation: confirm the `PROJECTED_LEAVES` / `TableField` bind arm
in `FetcherEmitter.bind` does not read `representativeParentTable` (it should resolve purely by the
projected field name), so the parent-agnostic claim is grounded in the current code, not inherited
from the pre-R303 description.

## What we are NOT doing

- **BatchKey leaves under `NestingField` across parents.** `SplitTableField`, `SplitLookupTableField`,
  `RecordTableField`, `RecordLookupTableField`, and the `Record*MethodField` family all have
  per-field DataLoader or rows-method generation keyed off the outer parent context today.
  Reconciling those across a shared `NestingField` is a larger piece of work; see the follow-up entry
  in the implementation section. The catch-all arm stays as the fallback for them.
- **`LookupTableField` (re-scoping note).** Since R23 was written, `LookupTableField` moved into
  `PROJECTED_LEAVES` alongside `TableField`, so it may now be emitter-safe across parents by the same
  argument. It is left out of this item's titled scope; whether to add a parallel `LookupTableField`
  arm should be decided by repeating the emitter-safe verification above for it (its inline-lookup
  emission and reified read), either folded in here as a second arm or split to its own entry. Do not
  assume it is safe without that check.
- **Deeper inline recursion.** Already works via the existing `NestingField` recursion branch in
  `compareNestedFieldsShape`.
- **Composite / reference NodeId carriers.** `CompositeColumnReferenceField` and friends stay
  rejected (no known real-world demand).
- **Shape-compat of `filters` / `orderBy` / `pagination`.** Deliberately left per-parent (see
  "Desired end state").

## Implementation approach

### 1. Validator: add the `TableField` arm

**File:** `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/GraphitronSchemaValidator.java`

In `compareNestedFieldsShape`, between the `ColumnField` arm and the inner-`NestingField` recursion
arm (i.e. before the `} else if (!(rf instanceof ChildField.NestingField))` catch-all), add a
`TableField` arm that accepts the pair without additional shape comparison:

```java
} else if (rf instanceof ChildField.TableField && of instanceof ChildField.TableField) {
    // TableField is safe to share across parents: each parent's $fields emits its own
    // DSL.multiset arm (per-parent joinPath / filters / orderBy / pagination are
    // intentionally not compared), and the reified projected read (PROJECTED_LEAVES) reads
    // by field name from the source Record without consulting the outer parent table. No
    // further shape check is needed: returnType() derives from the single SDL declaration
    // on the shared nested type and is identical by construction.
}
```

The arm exists only to prevent the catch-all from firing. `TableField` and `NestingField` are
disjoint concrete records; place this arm next to the `ColumnField` arm for readability.

### 2. Follow-up roadmap entry: BatchKey leaves

File a Backlog entry (slug `nestingfield-multiparent-batchkey-leaves`) covering `SplitTableField`,
`SplitLookupTableField`, `RecordTableField`, `RecordLookupTableField`, and the `Record*MethodField`
family under a `NestingField` shared across parents: their DataLoader registration and per-parent
rows-method emission need reconciling (each variant has its own considerations). `TableField` ships
here; `LookupTableField` is the open re-scoping question noted above.

### 3. Tests

**Pipeline test** (`GraphitronSchemaBuilderTest`, `graphitron` module): a case with two `@table`
parents declaring the same nested type, the nested type containing a `TableField` targeting a third
`@table`. The classifier emits no error, and both parents' `NestingField.nestedFields()` contain a
`TableField` with the correct parent-specific `joinPath` (verifying `@reference` resolved against each
outer parent's table context independently). No negative shape-equality test: a return-type mismatch
between the two sides is unreachable from SDL (the shared nested type declares each field once, and
`returnType()` derives from that single declaration); the existing class-equality error is already
covered.

**Execution test** (`graphitron-sakila-example`, the PostgreSQL execution tier): a two-parent
fixture mirroring a real Sakila shape. Candidate: `customer`, `staff`, and `store` all FK to
`address`; pick two (e.g. `Customer` and `Staff`) with a shared nested type exposing `address` as a
`TableField`, and assert via the execution-tier query test that a query against either parent returns
the correct `address` record, exercising each parent's FK-inferred `joinPath` to `address`
independently. Before landing, confirm the chosen Sakila pair and the shared-nested-type shape fit
the existing `$fields` pipeline without new directive plumbing.

`compareNestedFieldsShape` is private and tested transitively through `GraphitronSchemaBuilderTest`;
no direct unit test.

## Success criteria

- `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` passes, including the new
  `GraphitronSchemaBuilderTest` multi-parent `TableField` case and the new `graphitron-sakila-example`
  execution fixture. (`-Plocal-db` is required: see CLAUDE.md's catalog-jar clobber note.)
- The `EmneStudieprogramKoblingPeriode` / `fraTermin` and `Avsender` / `organisasjon` cases classify
  without the "not yet supported across multiple parents" error. Shared nested types that also carry
  BatchKey leaves stay rejected with the same message (out of scope here).

## References

Identifier-level references (line numbers drift; refer to the named symbol):

- Error site and existing multi-parent shape check: the catch-all arm in
  `GraphitronSchemaValidator.compareNestedFieldsShape`.
- Per-parent `$fields` emission: `TypeClassGenerator.emitSelectionSwitch` ->
  `InlineTableFieldEmitter.buildSwitchArmBody`.
- Projected-leaf read reification: `TypeFetcherGenerator.PROJECTED_LEAVES` + `FetcherEmitter.bind`
  (wrapped in `LightFetcher`, the post-R303 successor to the retired `ColumnFetcher` /
  `buildWiringEntry`).
- First-parent-wins nested-type registration: `FetcherRegistrationsEmitter.collectNestedTypes`
  (`putIfAbsent` on nested-type name) -> `FetcherRegistrationsEmitter.NestedTypeWiring`
  (`representativeParentTable`), the post-R303 successor to the retired
  `GraphQLRewriteGenerator.collectNestedTypes` / `GraphitronWiringClassGenerator`.
