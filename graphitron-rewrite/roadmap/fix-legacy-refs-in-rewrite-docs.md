---
id: R15
title: Sweep doc drift between rewrite docs and `model/` taxonomy
status: In Review
bucket: cleanup
priority: 3
theme: docs
depends-on: []
---

# Sweep doc drift between rewrite docs and `model/` taxonomy

The three reference docs under `graphitron-rewrite/docs/` (`code-generation-triggers.adoc`,
`rewrite-design-principles.adoc`, `argument-resolution.adoc`) had fallen behind several
recent landings in `model/`. None of the drift broke the build; all of it cost a first-time
reader credibility. This sweep ran across multiple commits; the final pass landed against
trunk `c48e532` (post-R68 / R79 / R81 / R82 / R86).

## Strategy: complete with R86 forward references

R86 (`architecture-chapter.md`) eventually consolidates the typed-rejection narrative,
sealed-hierarchies guidance, and wire-format-boundary principle into the public user-manual
architecture chapter. R86's named scope is a new chapter, not a rewrite of these
contributor-facing docs ; the variant-taxonomy tables, source map, and permit lists land
locally and remain the canonical reference. A single forward-ref note in
`rewrite-design-principles.adoc` flags the principle paragraphs that will eventually move
to the public chapter; nothing else needs a forward ref.

## Landed (final pass against trunk `c48e532`)

### Variant-taxonomy drift

- **`QueryEntityField` row removed.** `_entities` is no longer modelled as a `QueryField`
  permit; it is resolved by `federation-graphql-java-support` through the generated
  `EntityFetcherDispatch` runtime helper. Replaced the row with a footnote pointing at
  `GraphitronSchema.entitiesByType` and `EntityFetcherDispatch`.
- **`QueryNodesField` row added.** Relay `nodes(ids)` auto-emit (R7); dispatched at
  `generators/util/QueryNodeFetcherClassGenerator.java`.
- **`GraphitronType` permits enriched.** Added rows for `PlainObjectType`, `EnumType`,
  `ConnectionType`, `EdgeType`, `PageInfoType` ; the connection trio was core to the
  `@asConnection` path but absent from the Type Classification table.
- **`CallSiteExtraction` enumeration fixed.** Now reads "five direct strategies plus two
  sealed sub-groupers covering nested-input traversal (`NestedInputField`) and NodeId
  decode (`NodeIdDecodeKeys.{SkipMismatchedElement | ThrowOnMismatch}`)" in both
  `code-generation-triggers.adoc:266` (the source-map row) and
  `rewrite-design-principles.adoc:31` (the principle).
- **`GraphitronSchema` schematic corrected.** The diagram at
  `code-generation-triggers.adoc:13-25` now shows all five fields (`types`, `fields`,
  `fieldsByType`, `entitiesByType`, `warnings`) instead of the misleading two-field
  shape.
- **Source-map generators table restructured.** Replaced the nine-row file-by-file list
  with a four-family grouping (fetcher emission, schema emission, error-handling
  emission, runtime helpers). The error-handling family is now visible at the table level
  rather than buried in `schema/`. Helper-emitter classes (`SplitRowsMethodEmitter`,
  `LookupValuesJoinEmitter`, `JoinPathEmitter`, `MultiTablePolymorphicEmitter`,
  `ArgCallEmitter`, `FetcherEmitter`, `HandleMethodBody`, `SelectMethodBody`,
  `ValuesJoinRowBuilder`, `CompositeDecodeHelperRegistry`, etc.) are noted as reached
  from inside a generator and not separate entries.
- **`ArgumentRef` permits refresh.** `argument-resolution.adoc` lines 11-13 now show the
  full sealed shape: `ScalarArg.{ColumnArg | CompositeColumnArg | ColumnReferenceArg |
  CompositeColumnReferenceArg | UnboundArg}`, `InputTypeArg.{TableInputArg | PlainInputArg}`,
  plus top-level `OrderByArg`, `PaginationArgRef`, `UnclassifiedArg`. The R50 composite
  carriers and the two intermediate sealed sub-groupers (`ScalarArg`, `InputTypeArg`) are
  now both visible.
- **`ChildField.ParticipantColumnReferenceField` row added.** Scalar/Enum return-type
  table on a `@table` parent now lists the participant-side FK carrier used on
  `TableInterfaceType` participants.
- **`ChildField.ErrorsField` row added.** `@record` parent table now lists the typed
  error-channel slot on a service payload, with the passthrough-fetcher emission note.
- **`BatchKey` source-map row enumerates both axes.** The Source Map row now reads
  "Two axis sub-hierarchies: `ParentKeyed` (`RowKeyed`, `RecordKeyed`, `MappedRowKeyed`,
  `MappedRecordKeyed`, `TableRecordKeyed`, `MappedTableRecordKeyed`) and
  `RecordParentBatchKey` (`RowKeyed`, `LifterRowKeyed`, `AccessorKeyedSingle`,
  `AccessorKeyedMany`)" ; ten permits across two axes. The companion `BatchKey` Javadoc
  ("Seven permits across two axis sub-hierarchies") was updated in the same commit to
  read "Ten permits across two axis sub-hierarchies (nine unique class names; `RowKeyed`
  appears once on each axis)".

### Principles-doc claims

- **Reflection-permission roster refreshed.** `rewrite-design-principles.adoc:27` no
  longer says "ServiceCatalog ... only places". Today's roster: `ServiceCatalog`,
  `ServiceDirectiveResolver`, `BatchKeyLifterDirectiveResolver`, `FieldBuilder` ; four
  builder-side classifiers convert reflection output into typed model values
  (`MethodRef.Param`, `BatchKey`, `AccessorRef`).
- **Raw-jOOQ-types boundary roster refreshed.** Reframed `rewrite-design-principles.adoc:29`
  to "the boundary lives at `JooqCatalog`": that is the canonical permitted holder, plus
  `BuildContext` (`ForeignKey` for validation-message FK enumeration) and
  `catalog/CatalogBuilder` (`ForeignKey` + `Table` for the LSP completion-data snapshot).
  `TypeBuilder`, `FieldBuilder`, and `ServiceCatalog` consume the classified output via
  `JooqCatalog` rather than holding raw types directly.
- **`candidateHint` usage stats refreshed.** `rewrite-design-principles.adoc:181` now
  reads "17 occurrences across five files ; 7 in `BuildContext`, 3 in `TypeBuilder`,
  3 in `Rejection`, 2 in `FieldBuilder`, 2 in `EnumMappingResolver`". The shape note
  (Levenshtein-suggestion contract consolidated onto `BuildContext` and `Rejection`)
  is also recorded so the next re-audit knows what the trend is.

### Legacy refs

- **Repo path corrected.** `code-generation-triggers.adoc:245` was
  `graphitron-rewrite/src/main/java/...` (pre-monorepo-restructure relic); now reads
  `graphitron-rewrite/graphitron/src/main/java/...` matching the actual layout.
- **`directives.graphqls` location corrected.** `code-generation-triggers.adoc:295`
  was the legacy `graphitron-common` path; now reads
  `graphitron-rewrite/graphitron/src/main/resources/no/sikt/graphitron/rewrite/schema/directives.graphqls`
  with the `RewriteSchemaLoader` auto-injection note.
- **Legacy README link replaced.** `code-generation-triggers.adoc:296` pointed at the
  legacy `graphitron-codegen-parent/graphitron-java-codegen/README.md`; now points at the
  directive reference in the published manual (`docs/manual/reference/directives/`) and
  flags R86 as the contributor-facing chapter that consolidates runtime extension points.

### R86 forward reference

- **Single inline note in `rewrite-design-principles.adoc` preamble.** The typed-rejection
  narrative ("Builder-step results are sealed, not strings or out-params"), the
  sealed-hierarchies guidance, and the wire-format-boundary principle are slated to
  consolidate into the public user-manual architecture chapter once it lands (R86); until
  then, this contributor-facing reference is the canonical source. Per R15's deeper-pass
  recommendation, no other forward refs were added ; the variant-taxonomy / source-map /
  permit-list content lives at this surface and is not part of R86's named scope.

## Earlier landings (preserved for context)

The 2026-05-02 sweep took out six items the user flagged as the cheapest big win:

- **Java version.** `rewrite-design-principles.adoc:108-113` Java 21 → Java 25; expanded
  feature list (switch patterns, scoped values); noted the `requireJavaVersion` enforcer
  pin and the `<release>17</release>` check on `graphitron-test`.
- **Post-R50 rows.** Replaced the dead `NodeIdField` / `NodeIdReferenceField` rows in
  `code-generation-triggers.adoc:160-167` with the column-shape successors carrying
  `compaction = NodeIdEncodeKeys`, plus new rows for `CompositeColumnField` /
  `CompositeColumnReferenceField`. Same pair added to the Input Fields table at
  `:202-210` and to the Source Map's `InputField` permit list.
- **`@notGenerated`.** Split out from "Conflicting directives" into its own row at
  `code-generation-triggers.adoc:188`, named the `INVALID_SCHEMA` rejection kind, and
  noted the directive is no longer supported.
- **`ConditionFilter` gap.** Removed the entire "Known Gaps" section: the gap closed when
  `FieldBuilder.projectForFilter` started consuming `conditionResolver.resolveField` and
  appending the resulting `ConditionFilter` to the filter list (`FieldBuilder.java:849-861`).
- **Load-bearing count.** "Two instances on trunk today" → "Nine load-bearing keys"; named
  all nine producer keys, kept the two original worked examples, pointed at
  `LoadBearingGuaranteeAuditTest` as the canonical scan.
- **Four-set partition.** Replaced the forward-looking "successor status-map when the
  four-set partition collapses" phrase at `rewrite-design-principles.adoc:67` with the
  concrete partition (`IMPLEMENTED_LEAVES` / `PROJECTED_LEAVES` / `NOT_DISPATCHED_LEAVES`
  / `NOT_IMPLEMENTED_REASONS.keySet()`) and named the `GeneratorCoverageTest` enforcement.

The "five rewrite modules" Legacy-refs bullet shipped earlier (the count claim is gone and
the module list at `rewrite-design-principles.adoc:155` is complete).

## Out of scope (carried over)

- `rewrite-docs-entrypoint.md` ; the truncated `docs/README.md` is a separate item with
  its own structural changes (preamble, pipeline tour, module map). Land first; this
  sweep rebases on top.
- `runtime-extension-points.md` ; tracked under `runtime-extension-points-rewrite.md`;
  a rewrite, not a sweep.
- `docs-as-index-into-tests.md` ; also rewrites `code-generation-triggers.md`; that plan
  notes this sweep should land first so the two passes don't edit overlapping paragraphs.
- The duplicated Javadoc on `ChildField.ServiceTableField` ; the file shows a single
  Javadoc block today, so this Out-of-scope item from the earlier audit is moot.

## Follow-up

A small audit test that reads `getPermittedSubclasses()` on each sealed root in `model/`
and compares to a checked-in expected set would catch the next variant addition before it
reaches main. Out of scope for this plan; file separately if the reviewer agrees the cost
is justified.
