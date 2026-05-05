---
id: R15
title: Sweep doc drift between rewrite docs and `model/` taxonomy
status: Spec
bucket: cleanup
priority: 3
theme: docs
depends-on: []
---

# Sweep doc drift between rewrite docs and `model/` taxonomy

The three reference docs under `graphitron-rewrite/docs/` (`code-generation-triggers.adoc`,
`rewrite-design-principles.adoc`, `argument-resolution.adoc`) have fallen behind several
recent landings in `model/`. None of the drift breaks the build; all of it costs a first-time
reader credibility. Re-audited 2026-05-02 against trunk after the focused sweep below.
Re-audited again on the architecture-study pass (commit `96edb7a`); items 7-12 below
were added in that pass. Reviewer-pass refresh against `cb20a25` (post-R36 / R58 / R59):
counts and rosters retightened; `NodeIdDecodeKeys` added to the `CallSiteExtraction` row
because R58's new "Wire-format encoding is a boundary concern" section
(`rewrite-design-principles.adoc:85`) names it as the worked example, leaving the source-map
table at `code-generation-triggers.adoc:266` and the intro paragraph at
`rewrite-design-principles.adoc:31` as the two laggards.

Re-audited 2026-05-05 against trunk `994e780` (post-R68 UX-review landings). Findings since
the last sweep: the "five rewrite modules" Legacy-refs bullet has shipped (the count claim
is gone and the module list at `rewrite-design-principles.adoc:155` is complete);
`candidateHint` usage stats need a fresh number set (the 2026-05-02 update was already
stale on its own terms). New drift items added in this pass: `ChildField.ParticipantColumnReferenceField`
and `ChildField.ErrorsField` are missing from the Child Fields tables in
`code-generation-triggers.adoc`, and `BatchKey`'s permits list at the source-map row
(line 263) is post-R7-stale (the doc lists 5; the model carries at least 8 across the
two `ParentKeyed` / `RecordParentBatchKey` axis hierarchies). The `depends-on:
[docs-site-asciidoc]` front-matter entry was incorrect (the rewrite-internal docs are
not blocked on the user-manual site move) and has been dropped.

The original scope of this item was a pure legacy-ref grep ("`graphitron-common`" + module
count). That scope is preserved at the bottom; the larger driver now is variant-taxonomy
drift caused by federation, the Relay `nodes(ids)` auto-emit, the `@condition`-on-input-
field work, and the recent `BatchKey` and `ChildField` permit additions, all shipping
under their own plans without a doc pass.

## Landed (2026-05-02 commit)

The 2026-05-02 sweep took out the six items the user flagged as the cheapest big win:

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

## Variant-taxonomy drift (still pending)

One commit, one focused diff. Each row below is a single edit.

- **`QueryEntityField` no longer exists.** Removed in `a24feb4` (federation Phases 1-3);
  `_entities` is now resolved by `federation-graphql-java-support` directly. Still appears
  as a row in `code-generation-triggers.adoc`'s Query Fields table. Delete the row.
- **`QueryNodesField` is missing from the docs.** Added in `71e439f` (Relay `nodes(ids)`
  auto-emit); a real permit at `model/QueryField.java`, dispatched at
  `generators/TypeFetcherGenerator.java`. Add a row in
  `code-generation-triggers.adoc`'s Query Fields table.
- **`GraphitronType` has 5 variants the docs don't acknowledge.**
  `model/GraphitronType.java` permits `PlainObjectType`, `EnumType`, `ConnectionType`,
  `EdgeType`, `PageInfoType` — the connection trio is core to the `@asConnection` path.
  Absent from `code-generation-triggers.adoc`'s Type Classification table.
- **`CallSiteExtraction` shows 5 variants, has 7.** `rewrite-design-principles.adoc:31` and
  `code-generation-triggers.adoc:266` enumerate `Direct / EnumValueOf / TextMapLookup /
  ContextArg / JooqConvert`. The 6th is `NestedInputField` (used for `@condition` on
  `INPUT_FIELD_DEFINITION`). The 7th is the sealed sub-grouper `NodeIdDecodeKeys`
  (`SkipMismatchedElement` / `ThrowOnMismatch`) added in R50 (`model/CallSiteExtraction.java:30`).
  `argument-resolution.adoc` covers `NestedInputField` already; the same doc and the new
  `rewrite-design-principles.adoc:85` "Wire-format encoding is a boundary concern" section
  both already name `NodeIdDecodeKeys` ; the laggards are the source-map table at
  `code-generation-triggers.adoc:266` and the intro paragraph at
  `rewrite-design-principles.adoc:31`. Fix both to enumerate all seven, or rephrase the intro
  as "five extraction strategies plus two sealed sub-groupers covering nested-input traversal
  and NodeId decode."
- **`GraphitronSchema` schematic is wrong.** `code-generation-triggers.adoc:13-25` shows
  `Map<String, GraphitronField>`. Real shape at `GraphitronSchema.java:24-30` is five fields:
  `Map<String, GraphitronType> types`, `Map<FieldCoordinates, GraphitronField> fields`,
  `Map<String, List<GraphitronField>> fieldsByType`, `Map<String, EntityResolution>
  entitiesByType`, `List<BuildWarning> warnings`. Fix the diagram.
- **Source map misses ~17 generators.** `code-generation-triggers.adoc`'s Generators table
  lists 8. `generators/schema/` has 13 (`ObjectTypeGenerator`, `InputTypeGenerator`,
  `EnumTypeGenerator`, `GraphitronFacadeGenerator`, `GraphitronSchemaClassGenerator`,
  `FetcherRegistrationsEmitter`, `DirectiveDefinitionEmitter`, `AppliedDirectiveEmitter`,
  `GraphQLValueEmitter`, `InputDirectiveInputTypes`, plus an error-handling sub-family —
  `ConstraintViolationsClassGenerator`, `ErrorMappingsClassGenerator`,
  `ErrorRouterClassGenerator`); `generators/util/` has runtime-helper class generators
  (`ColumnFetcherClassGenerator`, `EntityFetcherDispatchClassGenerator`,
  `GraphitronContextInterfaceGenerator`, `NodeIdEncoderClassGenerator`,
  `OrderByResultClassGenerator`, `QueryNodeFetcherClassGenerator`) alongside emitter helpers
  that aren't generators (`HandleMethodBody`, `SelectMethodBody`, `ValuesJoinRowBuilder`,
  `SchemaDirectiveRegistry`); root has `QueryConditionsGenerator` alongside
  `TypeConditionsGenerator`. Restructure the source map to four families rather than
  re-listing entries: fetcher emission, schema emission, error-handling emission, runtime
  helpers ; that grouping makes the error-handling family visible at the table level instead
  of buried in the schema/ folder.
- **`argument-resolution.adoc` permits list is post-R50 stale.** Lines 11-13 enumerate
  seven `ArgumentRef` variants (`ColumnArg`, `UnboundArg`, `TableInputArg`, `PlainInputArg`,
  `OrderByArg`, `PaginationArgRef`, `UnclassifiedArg`). The model permits at minimum:
  `ScalarArg.{ColumnArg | CompositeColumnArg | ColumnReferenceArg | CompositeColumnReferenceArg | UnboundArg}`,
  `InputTypeArg.{TableInputArg | PlainInputArg}`, plus the three top-level
  `OrderByArg` / `PaginationArgRef` / `UnclassifiedArg`. Three R50-introduced carriers
  (`CompositeColumnArg`, `ColumnReferenceArg`, `CompositeColumnReferenceArg`) plus the two
  intermediate sealed groupers (`ScalarArg`, `InputTypeArg`) are absent from the prose.
- **`ChildField.ParticipantColumnReferenceField` is missing from the docs.** Listed in
  `model/ChildField.java`'s permits clause (`ChildField.java:13`) as a scalar-field
  carrier on `TableInterfaceType` participants, but absent from the Child Fields tables
  at `code-generation-triggers.adoc:158-188`. Add a row in the appropriate sub-table
  (scalar/enum return type) with the directive pattern that produces it.
- **`ChildField.ErrorsField` is missing from the docs.** Listed in `ChildField.java:23`
  as a permit; carries the typed error-channel slot on a service payload. The Type
  Classification table at `code-generation-triggers.adoc:109` mentions `ErrorType` (the
  type-side variant) but the field-side carrier has no row in the Child Fields tables.
- **`BatchKey` source-map row lists 5 permits, has at least 8.**
  `code-generation-triggers.adoc:263` says "`RowKeyed` / `RecordKeyed` / `MappedRowKeyed`
  / `MappedRecordKeyed` / `LifterRowKeyed`". The model splits across two axis sub-hierarchies:
  `ParentKeyed.{RowKeyed | RecordKeyed | MappedRowKeyed | MappedRecordKeyed | TableRecordKeyed
  | MappedTableRecordKeyed}` (six), plus `RecordParentBatchKey.{RowKeyed | LifterRowKeyed |
  AccessorKeyedSingle | AccessorKeyedMany}` (four; `RowKeyed` repeats across both axes).
  Eight unique permits today. Either rephrase the source-map cell to point at the two axes
  rather than enumerate, or list all eight; the BatchKey Javadoc opens with "Seven permits
  across two axis sub-hierarchies" (out of date by one) so the doc cell and the Javadoc
  should be updated together.

## Principles-doc claims overtaken by structural changes

Three claims in `rewrite-design-principles.adoc` whose name lists or counts no longer match
the code. The principle's spirit is intact in each; only the cited evidence is stale.

- **Reflection-permission roster is stale.** `rewrite-design-principles.adoc:27`:
  "`ServiceCatalog.reflectServiceMethod()` and `ServiceCatalog.reflectTableMethod()` are the
  only places that read the reflection `java.lang.reflect.Type` tree." Today three files
  import `java.lang.reflect.Type`: `ServiceCatalog`, `BatchKeyLifterDirectiveResolver`
  (R1, reflects on the developer-supplied lifter), and `FieldBuilder`. Update the roster.
- **Raw-jOOQ-types permission roster is wrong.** `rewrite-design-principles.adoc:29`:
  "`JooqCatalog`, `TypeBuilder`, `FieldBuilder`, and `ServiceCatalog` are the only classes
  permitted to hold raw jOOQ types." Today three files import `org.jooq` raw types directly:
  `JooqCatalog`, `BuildContext` (`ForeignKey` for `@reference` validation messages), and
  `catalog/CatalogBuilder` (`ForeignKey` + `Table` for the LSP completion-data snapshot).
  `TypeBuilder` / `FieldBuilder` / `ServiceCatalog` go through `JooqCatalog`. Either fix the
  list to those three names, or rephrase the principle as "the boundary lives at
  `JooqCatalog`; the only direct consumers of raw jOOQ types are `BuildContext` (for
  validation-message FK enumeration) and the LSP-side `catalog/CatalogBuilder` snapshot;
  classifier code consumes the classified output."
- **`candidateHint` usage stats are stale.** `rewrite-design-principles.adoc:181`:
  "Used in 14 places (5 in `FieldBuilder`, 5 in `TypeBuilder`, 2 in `BuildContext`,
  2 in `ServiceCatalog`)." Today: 17 occurrences across 5 files (7 in `BuildContext`,
  3 in `TypeBuilder`, 3 in `Rejection`, 2 in `FieldBuilder`, 2 in `EnumMappingResolver`);
  `ServiceCatalog` and `BatchKeyLifterDirectiveResolver` no longer reference it directly.
  The shape of the principle is healthier than the original numbers suggest because the
  Levenshtein-suggestion contract has consolidated onto `BuildContext` and `Rejection`
  (the rejection construction site), with classifier-side callers thinning out as
  rejections are produced through the typed sealed-result path. Refresh the stats and
  rephrase the location list as "the rejection-construction sites and the classifiers
  that produce candidate lists" if a future re-audit will be cheap; otherwise enumerate
  the five files with their current counts.

## Legacy refs (original scope, preserved)

- `code-generation-triggers.adoc:245` says "All source lives under
  `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/`." Pre-monorepo-restructure
  relic; the actual layout is `graphitron-rewrite/graphitron/src/main/java/...`. The path
  on disk doesn't exist as written. Single-line edit at the top of the Source Map.
- `code-generation-triggers.adoc:295` still lists the directive SDL location as
  `graphitron-common/src/main/resources/directives.graphqls`. Per changelog entry
  `c31771d`, the rewrite ships its own copy at
  `graphitron-rewrite/graphitron/src/main/resources/no/sikt/graphitron/rewrite/schema/directives.graphqls`
  and `RewriteSchemaLoader` auto-injects it. Update the link to the actual nested path.
- `code-generation-triggers.adoc:296` (the line right after) — companion directive
  reference — points at
  `https://github.com/sikt-no/graphitron/tree/main/graphitron-codegen-parent/graphitron-java-codegen/README.md`,
  the legacy module's README. Either re-point at the rewrite-side doc or drop the link
  if no rewrite-side equivalent exists yet. Paired edit with the line above.
- *(Landed.) The "five rewrite modules" claim at `rewrite-design-principles.adoc:155`
  has been corrected; the doc now lists all nine modules and omits the count claim.*
- Anywhere `verify-standalone-build.sh`'s forbidden-coords list is paraphrased
  inconsistently: a final grep pass (the 2026-05-05 grep finds only one matching site
  and it is correct, so this can probably collapse into "no action").

## Out of scope

- `rewrite-docs-entrypoint.md` — the truncated `docs/README.md` is a separate item with its
  own structural changes (preamble, pipeline tour, module map). Land first; this sweep
  rebases on top.
- `runtime-extension-points.md` — tracked under `runtime-extension-points-rewrite.md`; a
  rewrite, not a sweep.
- `docs-as-index-into-tests.md` — also rewrites `code-generation-triggers.md`; that plan
  notes this sweep should land first so the two passes don't edit overlapping paragraphs.
- The duplicated Javadoc on `ChildField.ServiceTableField` (`model/ChildField.java:217-234`
  has two stacked Javadoc blocks; Java attaches only the closer one). Trivial code fix, not
  doc-sweep work; mention here only so it doesn't get lost.

## Follow-up

A small audit test that reads `getPermittedSubclasses()` on each sealed root in `model/`
and compares to a checked-in expected set would catch the next variant addition before it
reaches main. Out of scope for this plan; file separately if the reviewer agrees the cost
is justified.
