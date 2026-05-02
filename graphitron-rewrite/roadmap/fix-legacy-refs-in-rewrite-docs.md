---
id: R15
title: Sweep doc drift between rewrite docs and `model/` taxonomy
status: Backlog
bucket: cleanup
priority: 3
theme: docs
depends-on: [docs-site-asciidoc]
---

# Sweep doc drift between rewrite docs and `model/` taxonomy

The three reference docs under `graphitron-rewrite/docs/` (`code-generation-triggers.adoc`,
`rewrite-design-principles.adoc`, `argument-resolution.adoc`) have fallen behind several
recent landings in `model/`. None of the drift breaks the build; all of it costs a first-time
reader credibility. Re-audited 2026-05-02 against trunk after the focused sweep below.

The original scope of this item was a pure legacy-ref grep ("`graphitron-common`" + module
count). That scope is preserved at the bottom; the larger driver now is variant-taxonomy
drift caused by federation, the Relay `nodes(ids)` auto-emit, and the `@condition`-on-input-
field work shipping under their own plans without a doc pass.

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
- **`CallSiteExtraction` shows 5 variants, has 6.** `rewrite-design-principles.adoc:29` and
  `code-generation-triggers.adoc:266` enumerate `Direct / EnumValueOf / TextMapLookup /
  ContextArg / JooqConvert`. The 6th is `NestedInputField` (`model/CallSiteExtraction.java`),
  used for `@condition` on `INPUT_FIELD_DEFINITION`. `argument-resolution.adoc` covers it
  already — fix the two laggards to match.
- **`GraphitronSchema` schematic is wrong.** `code-generation-triggers.adoc:13-25` shows
  `Map<String, GraphitronField>`. Real shape at `GraphitronSchema.java:24-30` is five fields:
  `Map<String, GraphitronType> types`, `Map<FieldCoordinates, GraphitronField> fields`,
  `Map<String, List<GraphitronField>> fieldsByType`, `Map<String, EntityResolution>
  entitiesByType`, `List<BuildWarning> warnings`. Fix the diagram.
- **Source map misses ~14 generators.** `code-generation-triggers.adoc`'s Generators table
  lists 8. `generators/schema/` adds ten (`ObjectTypeGenerator`, `InputTypeGenerator`,
  `EnumTypeGenerator`, `GraphitronFacadeGenerator`, `GraphitronSchemaClassGenerator`,
  `FetcherRegistrationsEmitter`, `DirectiveDefinitionEmitter`, `AppliedDirectiveEmitter`,
  `GraphQLValueEmitter`, `InputDirectiveInputTypes`); `generators/util/` adds four
  (`GraphitronContextInterfaceGenerator`, `NodeIdEncoderClassGenerator`,
  `OrderByResultClassGenerator`, `QueryNodeFetcherClassGenerator`); root has
  `QueryConditionsGenerator` alongside `TypeConditionsGenerator`. Restructure the source
  map to acknowledge the schema-emission family rather than re-listing 22 entries.

## Legacy refs (original scope, preserved)

- `code-generation-triggers.adoc:295` still lists the directive SDL location as
  `graphitron-common/src/main/resources/directives.graphqls`. Per changelog entry
  `c31771d`, the rewrite ships its own copy at
  `graphitron-rewrite/graphitron/src/main/resources/directives.graphqls` and
  `RewriteSchemaLoader` auto-injects it. Update the link.
- `rewrite-design-principles.adoc:119` says "builds the **five** rewrite modules" and lists
  five. The aggregator now has eight (adds `graphitron-fixtures-codegen`, `graphitron-lsp`,
  `roadmap-tool`). Update count and list.
- Anywhere "five rewrite modules" or `verify-standalone-build.sh`'s forbidden-coords list
  is paraphrased inconsistently: a final grep pass.

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
