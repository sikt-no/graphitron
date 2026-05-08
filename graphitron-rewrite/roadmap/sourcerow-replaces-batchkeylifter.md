---
id: R110
title: "Replace @batchKeyLifter with @sourceRow composing with @reference"
status: In Review
bucket: architecture
priority: 7
theme: service
depends-on: []
---

# Replace @batchKeyLifter with @sourceRow composing with @reference

`@batchKeyLifter` is replaced by `@sourceRow`, a renamed and rescoped directive that
(a) drops `targetColumns`, deriving the expected parent-side tuple from `@reference`'s
first hop (path-keyed) or the leaf target's PK (leaf-keyed); (b) composes with
`@reference` so multi-hop paths from a non-table-backed `@record` parent become
expressible; (c) uses flat `(className, method)` args instead of the
`ExternalCodeReference` wrapper.

## Shipped

- **Directive surface.** `directives.graphqls`: `@batchKeyLifter` removed,
  `@sourceRow(className, method)` added. `BuildContext.DIR_BATCH_KEY_LIFTER` →
  `DIR_SOURCE_ROW`; `ARG_LIFTER` and `ARG_TARGET_COLUMNS` removed.
- **Model split.** `BatchKey.LifterRowKeyed` split into `LifterLeafKeyed`
  (single `JoinStep.LiftedHop`, no `@reference`) and `LifterPathKeyed`
  (resolved `JoinStep.FkJoin` chain, `@reference`-composed) under a new
  `LifterKeyed` sub-seal of `RecordParentBatchKey`. The sub-seal exposes
  `path()`, `parentSideColumns()`, and `lifter()` so emitter sites consume
  both shapes uniformly. `LifterPathKeyed`'s compact constructor enforces
  non-empty path.
- **Resolver.** `BatchKeyLifterDirectiveResolver` →
  `SourceRowDirectiveResolver`. Drops `targetColumns` reading; derives the
  expected parent-side tuple from `@reference`'s first-hop source-side or the
  leaf target's PK; routes to the correct `LifterKeyed` permit. Two
  diagnostic templates distinguish the `@reference` and leaf-PK
  arity / per-position-type mismatch cases. `@reference` parse failure
  surfaces directly, without re-validating against the lifter.
- **Load-bearing key audit.** `lifter-classifies-as-record-table-field` →
  `sourcerow-classifies-as-record-table-field`;
  `lifter-batchkey-is-lifterrowkeyed` → split into
  `sourcerow-leafkey-batchkey-is-lifterleafkeyed` +
  `sourcerow-pathkey-batchkey-is-lifterpathkeyed`. Every consumer's
  `@DependsOnClassifierCheck` updated; `LoadBearingGuaranteeAuditTest` green.
- **Consumers.** `FieldBuilder` directive lookup, classifier integration, and
  rejection messages updated. `GeneratorUtils.buildRecordParentKeyExtraction`
  switch arm collapses both lifter permits onto the existing
  `buildLifterRowKey` call site (the lifter emit shape is identical).
  `SplitRowsMethodEmitter`'s `WithTarget` capability handles both permit
  shapes; only annotation / comment text changed there.
- **Tests.** `BatchKeyLifterCase` → `SourceRowClassificationCase`. SDL
  fixtures rewritten to `@sourceRow [+ @reference]`. New cases:
  `LEAF_PK_NO_REFERENCE`, `LEAF_PK_ARITY_MISMATCH`, `REFERENCE_PARSE_FAILURE`.
  Removed cases that no longer apply (`EMPTY_TARGET_COLUMNS`,
  `UNKNOWN_TARGET_COLUMN`, `TARGET_COLUMN_SCOPED_TO_RETURN_TABLE`).
  `BatchKeyTest` covers the new permits, including the `LifterPathKeyed`
  empty-path invariant. All 1465 graphitron tests pass.
- **Sakila fixtures.** `CreateFilmPayload.language` migrated to leaf-PK
  (`@sourceRow` alone). New Story 1 fixture `CustomerAddressSummary` with
  `@sourceRow + @reference(path: [{key: "customer_address_id_fkey"}])`,
  backed by Java record + lifter + service classes; exposed via
  `Query.customerAddressSummary`. AsciiDoc tag markers
  (`sourcerow-leafpk`, `sourcerow-story-1`) on the schema fixture.
- **Documentation.** New how-to `docs/manual/how-to/source-row.adoc` walks
  the leaf-PK and path-keyed shapes with full SDL + Java + rejection-message
  examples. Renamed reference page `directives/batchKeyLifter.adoc` →
  `directives/sourceRow.adoc` rewritten for the new directive surface. Swept
  `external-code.adoc`, `handle-services.adoc`, `result-types.adoc`,
  `record.adoc`, `notGenerated.adoc`, `diagnostics-glossary.adoc`,
  `condition.adoc`, and the reference / how-to indexes.
- **Internal docs.** `rewrite-design-principles.adoc` updated for the
  `LifterKeyed` sub-seal and the renamed classifier keys. `BatchKey`
  class-level Javadoc updated to reflect the split.

`mvn install -Plocal-db` (with full docs render) is green at the close
commit.

## Roadmap entries (siblings / dependencies)

- **Replaces** the `@batchKeyLifter` directive (now removed). The directive
  had not been adopted outside test fixtures and the example `@record`
  payload, so the migration was internal-only.
- **Sibling of** [R71 (`recordn-key-parity-lifter-and-non-jooq-record-parents.md`)](recordn-key-parity-lifter-and-non-jooq-record-parents.md):
  R71 extends the lifter return type from `Row1..Row22` to also accept
  `Record1..Record22`; that work narrows to extending
  `SourceRowDirectiveResolver`'s return-type validation now that R110 has
  shipped first.
- **Adjacent to** [R74 (`accessor-row-record-shapes.md`)](accessor-row-record-shapes.md):
  the auto-derive accessor path on jOOQ-backed `@record` parents. R110 does
  not touch that path; the two designs sit on opposite sides of the "is the
  parent jOOQ-backed?" fork.
