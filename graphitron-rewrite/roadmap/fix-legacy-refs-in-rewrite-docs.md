---
title: Sweep doc drift between rewrite docs and `model/` taxonomy
status: Backlog
bucket: cleanup
priority: 3
theme: docs
depends-on: [docs-site-asciidoc]
---

# Sweep doc drift between rewrite docs and `model/` taxonomy

The four reference docs under `graphitron-rewrite/docs/` (`code-generation-triggers.md`,
`rewrite-model.md`, `rewrite-design-principles.md`, `argument-resolution.md`) have fallen
behind several recent landings in `model/`. None of the drift breaks the build; all of it
costs a first-time reader credibility. Audited 2026-04-28 against trunk; eight specific
sites below.

The original scope of this item was a pure legacy-ref grep ("`graphitron-common`" + module
count). That scope is preserved at the bottom; the larger driver now is variant-taxonomy
drift caused by federation, the Relay `nodes(ids)` auto-emit, and the `@condition`-on-input-
field work shipping under their own plans without a doc pass.

## Variant-taxonomy drift

One commit, one focused diff. Each row below is a single edit.

- **`QueryEntityField` no longer exists.** Removed in `a24feb4` (federation Phases 1-3);
  `_entities` is now resolved by `federation-graphql-java-support` directly. Still appears
  as a row in `code-generation-triggers.md:134`. Delete the row.
- **`QueryNodesField` is missing from the docs.** Added in `71e439f` (Relay `nodes(ids)`
  auto-emit); a real permit at `model/QueryField.java:25,82`, dispatched at
  `generators/TypeFetcherGenerator.java:347,1416`. Add a row in
  `code-generation-triggers.md`'s Query Fields table and a node in `rewrite-model.md`'s
  Root Field Variants diagram.
- **`InputField` shows 1 variant in the diagram, has 6 in code.** `rewrite-model.md:35`
  stops at `ColumnField`; `model/InputField.java:18-22` permits `ColumnField`,
  `ColumnReferenceField`, `NodeIdField`, `NodeIdReferenceField`, `IdReferenceField`,
  `NestingField`. The Input Fields table at `code-generation-triggers.md:200-205` lists
  three. Update both.
- **`GraphitronType` has 5 variants the docs don't acknowledge.**
  `model/GraphitronType.java:317,328,364,383,401` permit `PlainObjectType`, `EnumType`,
  `ConnectionType`, `EdgeType`, `PageInfoType` — the connection trio is core to the
  `@asConnection` path. Absent from `code-generation-triggers.md`'s Type Classification
  table and from `rewrite-model.md`'s Type Hierarchy diagram.
- **`CallSiteExtraction` shows 5 variants, has 6.** `rewrite-design-principles.md:29` and
  `code-generation-triggers.md:260` enumerate `Direct / EnumValueOf / TextMapLookup /
  ContextArg / JooqConvert`. The 6th is `NestedInputField`
  (`model/CallSiteExtraction.java:100`), used for `@condition` on `INPUT_FIELD_DEFINITION`.
  `argument-resolution.md` does cover it — fix the two laggards to match.
- **Load-bearing examples drift.** `rewrite-design-principles.md:77` says "Two instances on
  trunk today" and lists the `@tableMethod` root fetcher and `ColumnField` parent table.
  The strict-`@service`-return arm is also annotated
  (`ServiceCatalog.java:148`) — three producers, not two.
- **`GraphitronSchema` schematic is wrong.** `code-generation-triggers.md:13-25` shows
  `Map<String, GraphitronField>`. Real shape at `GraphitronSchema.java:23-28` is
  `Map<FieldCoordinates, GraphitronField> fields` plus `Map<String, List<GraphitronField>>
  fieldsByType` plus `List<BuildWarning> warnings`. Fix the diagram.
- **Source map misses 14 generators.** `code-generation-triggers.md:276-285` lists 8.
  `generators/schema/` adds 10 (`ObjectTypeGenerator`, `InputTypeGenerator`,
  `EnumTypeGenerator`, `GraphitronFacadeGenerator`, `GraphitronSchemaClassGenerator`,
  `FetcherRegistrationsEmitter`, `DirectiveDefinitionEmitter`, `AppliedDirectiveEmitter`,
  `GraphQLValueEmitter`, `InputDirectiveInputTypes`); `generators/util/` adds 4
  (`GraphitronContextInterfaceGenerator`, `NodeIdEncoderClassGenerator`,
  `OrderByResultClassGenerator`, `QueryNodeFetcherClassGenerator`); root has
  `QueryConditionsGenerator` alongside `TypeConditionsGenerator`. Restructure the source
  map to acknowledge the schema-emission family rather than re-listing 22 entries.

## Legacy refs (original scope, preserved)

- `code-generation-triggers.md:289` lists the directive SDL location as
  `graphitron-common/src/main/resources/directives.graphqls`. Per changelog entry
  `c31771d`, the rewrite ships its own copy at
  `graphitron-rewrite/graphitron/src/main/resources/directives.graphqls` and
  `RewriteSchemaLoader` auto-injects it. Update the link.
- `rewrite-design-principles.md:113` says "builds the **five** rewrite modules" and lists
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
