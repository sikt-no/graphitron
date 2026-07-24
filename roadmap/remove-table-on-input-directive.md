---
id: R519
title: "Remove @table from input types; delete TableInputType (Phase 3)"
status: Backlog
bucket: architecture
priority: 6
theme: classification-model
depends-on: [consumer-derived-input-tables]
created: 2026-07-24
last-updated: 2026-07-24
---

# Remove `@table` from input types; delete `TableInputType` (Phase 3)

Carved out of R97 (`consumer-derived-input-tables`) as the directive-removal
slice, the way R457/R514/R515 were carved off the mutation write-target axis.
**This item is the home for the general `@table`-on-input removal** (the mantle
R457's changelog and R327's fold assigned to R97 before the deprecation axis was
split into shippable slices).

The prerequisite work has landed: consumer-derived resolution is complete, so
`@table` on an input is now pure redundant metadata everywhere.

- **R97** made the last classification consumers consumer-derived: arg-level
  `@lookupKey` resolves through the consuming field's table, and the global
  input-classification machinery (`findReturnTablesForInput`,
  `isUsedWithOverrideCondition`) is gone. `buildInputType` is directive-driven:
  explicit `@table` → the deprecated `TableInputType` bridge; everything else →
  the plain path.
- **R457 / R515 / R97's UPDATE slice** made every mutation verb's write target
  field-relative (DELETE via `@mutation(table:)`, INSERT via the payload rung,
  UPDATE via the same ladder), so no verb hard-requires `@table` on its input.

What remains is deleting the bridge and the directive.

## Scope

1. **Narrow the directive scope.** `directives.graphqls`: `@table` from
   `OBJECT | INPUT_OBJECT | INTERFACE` to `OBJECT | INTERFACE`. Rewrite the
   "Deprecated on input types" prose above the declaration; after this, `@table`
   on an input is a parse error, not a deprecation.
2. **Delete `TableInputType` and its bridge.** Remove the `@table` arm in
   `TypeBuilder.buildInputType` (the only arm left after R97), so every input
   collapses to the plain path. Delete `TypeBuilder.buildTableInputType`,
   `GraphitronType.TableInputType`, and
   `GraphitronSchemaValidator.validateTableInputType`. **Keep
   `TypeBuilder.resolveInputFields`** — it is shared by the DELETE (R457) and
   arg-level `@lookupKey` (R97) field-derived paths, not just the deleted bridge.
   Deleting the sealed variant cascades exhaustive-switch compile errors across
   ~20 consumer sites (`grep -rn TableInputType graphitron*/src/main`); that is
   the intended driver.
3. **Retire the deprecation / shadow warnings.**
   `emitTableOnInputDeprecationWarnings` and the input half of the "Shadowed by
   `@table`" directive-ignored warning (R96 took the `@record` half).
4. **Migrate all fixtures.** Remove `@table(name: ...)` from every `input`
   declaration: 40 in the main schemas (`schema.graphqls` 38, `federated-schema`
   1, `multitenant` 1) plus inline SDL in `graphitron/src/test/` and LSP
   fixtures. ID/scalar-return mutation fixtures that leaned on the input `@table`
   as their write-target signal move to `@mutation(table:)` on the field. The
   "cross-table reuse" case flips from silent miscompile to classify-time
   `UnclassifiedField`; expect a fixture or two to surface a latent mistarget.
5. **Docs.** `code-generation-triggers.adoc`, `table.adoc`, `deprecations.adoc`,
   and any other reference naming `@table` as input-applicable. The `verify`
   javadoc reference gate fails on any dangling `{@link TableInputType}`.

## Two seams recorded during R97 (build-green cannot catch these)

- **`InputBeanResolver`'s `TableInputType` check (~line 229) is a semantic
  signal**, not a routing artifact: it reads the whole-type verdict to mean
  "graphitron owns the DML" (the D2 rule rejecting a jOOQ-`TableRecord`
  `@service` param). Re-source it from the consuming field's write-target fact
  before deleting the variant. Deleting the arm compiles fine but silently
  changes semantics.
- **The affirmative LSP projection decision.** The `CatalogBuilder` seams that
  read `TableInputType` (`projectTypeClassification`'s `TableInput` arm,
  `projectFieldClassifications`'s `tit.inputFields()` walk, `projectType`'s
  `TableBacking` arm, `parentTableName`) **lose their data source** when the
  variant goes. R97 already restored the *type-level* input hover (the resolved
  table on `TypeClassification.PojoInput.resolvedTables`) and already dropped the
  per-input-field `FieldClassification` coordinates for the auto-promoted subset;
  this phase drops them for the last (explicit-`@table`) subset. Decide
  affirmatively: either extend the per-consumer resolution to re-feed the
  input-field coordinates, or state they are dropped and R337
  (`input-nesting-projection-classification`) owns their re-surfacing. "Build
  green" cannot catch this regression, so it is an acceptance clause.

## Acceptance

`@table` accepts only `OBJECT | INTERFACE`; all fixture SDL migrated; full
reactor green under `mvn install -Plocal-db` (sakila Java-17 compile +
PostgreSQL execution tier); the LSP projection replacement or its deliberate drop
+ R337 handoff is named in the shipped plan body.

## Out of scope

Removing `@table` on `OBJECT` / `INTERFACE` (load-bearing `TableType` /
`TableInterfaceType`). Phase 4 housekeeping (R520).

## Rationale

The fact-model framing this removal rests on ("the input type is not an entity;
its table is a property of the consuming field's fact") lives in the permanent
concept explainer
[`concepts/consumer-derived-input-tables.html`](concepts/consumer-derived-input-tables.html),
which survives R97's Done deletion. In short: `@table` on an input duplicates a
signal already derivable from the consuming field's return-type table, the same
redundancy `@record` was (R96). With consumer-derived resolution complete (R97
Phase 2 + 2b, R457/R515), the directive drives nothing and can be removed.
