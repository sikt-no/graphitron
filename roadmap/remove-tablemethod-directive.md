---
id: R535
title: "Remove the @tableMethod directive"
status: In Review
bucket: architecture
priority: 3
theme: model-cleanup
depends-on: []
created: 2026-07-25
last-updated: 2026-07-26
---

# Remove the `@tableMethod` directive

## Problem

R400 withheld `@tableMethod` from the v1 advertised surface but deliberately left the
declaration and machinery intact ("declared and behaviourally unchanged, just outside the
v1 surface"). That leaves a live gap: `directives.graphqls` still declares the directive
and no classify-time rejection exists, so a consumer schema can adopt an unadvertised,
undocumented directive today and generate working code we would then have to support.
User decision 2026-07-25: the directive is not supposed to be exposed at all, no consumer
uses it, so remove the declaration and the machinery now. `@routine` covers the need it
was chasing (per the R277 discard rationale in `changelog.md`).

This item replaces the migration framing this file was originally filed with (migrating
the inline `TableMethodField` leaf onto `TableExpr.MethodCall`, the R314 residue named in
R333's join-path section). With the directive removed, both the inline leaf and the
`MethodCall` node are deleted instead; the residue is discharged by removal.

## Design decisions

- **Full removal, not `REJECTED_ON_USE`.** R400's rejected set keeps a declaration so
  legacy schemas parse and get a friendly classify-time migration message. `@tableMethod`
  does not need that half-state: the migration report's "Legacy-only directives" section
  (`DirectiveSupportReport.renderMigration`) already renders any directive declared by
  legacy but not by the rewrite as removed-in-rewrite with a drop-before-migrating
  instruction, so deleting the declaration outright still leaves migrating consumers a
  clear message. A rewrite schema applying it fails schema validation as an unknown
  directive, which is the correct signal for a directive that was never advertised.
- **`TableExpr.MethodCall` dies with the directive.** Its only construction site is the
  DTO-parent `@tableMethod` arm (`FieldBuilder.java:6252`). The seal reverts to
  `Catalog | RoutineCall`, and the `JoinPathEmitter` wiring-bug guard for a `MethodCall`
  node at a general materialization site is deleted rather than lifted.
- **R403 stays** as the rethink-and-reintroduce parking spot; recovery is anchor-free via
  git history, per the R400 pattern. Update its notes to record that the declaration and
  machinery are now gone, so reintroduction is a fresh design, not a re-advertising edit.
- **The migration guide keeps one replacement pointer.** The generated Legacy-only bullet
  says "Drop them from your schema (or replace per the notes below)"
  (`DirectiveSupportReport.java:638`), but no per-directive notes map exists, and after
  the step-1 sweep no `@tableMethod`-to-`@routine` pointer would survive anywhere. Add one
  hand-authored sentence in the migration guide prose near the generated include, naming
  `@routine` as the replacement for legacy `@tableMethod` usage.

## Scope sweep

1. **Declaration and docs.** Delete `@tableMethod` from `directives.graphqls`; sweep the
   factual mentions in sibling directive docstrings (`@field`, `@externalField`,
   `@tenantFanOut`, the `@record` migration note) and any remaining docs-site inline
   mentions (R400 Stage 2 already deleted the reference page). `legacy-directives.graphqls`
   is untouched: it documents the legacy surface, and its declaration is what routes the
   directive into the report's Legacy-only section.
2. **Resolution and model.** Delete `TableMethodDirectiveResolver`, the root leaf
   (`QueryField.QueryTableMethodTableField`), the child leaf
   (`ChildField.TableMethodField`), the DTO-parent batched arm in `FieldBuilder`
   (`FieldBuilder.java:6165` region), `TableExpr.MethodCall`, and the two classification
   seal arms `FieldClassification.TableMethod` and `FieldClassification.QueryTableMethod`
   (`FieldClassification.java:263` / `:387`, plus their permits and switch rows at
   `:49` / `:62` / `:135` / `:147`). Deleting the seal arms is the taxonomy change that
   drives the cross-module blast radius in step 3: exhaustive switches over the seal live
   in graphitron-lsp and graphitron-mcp, not just prose. Sweep the remaining main-source
   files whose diagnostic ladders, javadoc, or switch arms name the directive or its
   types (a vocabulary grep hits over 50 main-source files across the three modules;
   shared carriers such as `MethodRef` and `MethodBackedField` stay, only their
   `@tableMethod`-specific arms and mentions go). `ServiceCatalog.reflectTableMethod`
   also stays under its current name: `@condition` reflects through it
   (`ConditionResolver.java:80` / `:111`) and it is the sole producer of the surviving
   `MethodRef.StaticOnly`. Its name is directive-derived and its javadoc leads with
   `@tableMethod`, so rewrite the javadoc to describe what it actually does (reflect a
   static, table-parameterised developer method) rather than renaming the method; record
   the keep so the Done-gate sweep does not read it as a survival.
3. **LSP and MCP.** These are structural deletions, not mention sweeps: the
   `tableMethod` entry in `DirectivePolicy.METHOD_BINDING_DIRECTIVES`
   (`graphitron-lsp/.../parsing/DirectivePolicy.java:48`), the `@tableMethod`
   `Behavior.ClassNameBinding` / `Behavior.MethodNameBinding` pair in
   `parsing/LspVocabulary.java:675-681`, and the seal-arm switch rows in
   `parsing/DeclTarget.java`, `inlay/LspClassificationLabels.java`,
   `hover/DeclarationHovers.java`, `graphitron-mcp/.../SchemaView.java`, and
   `EdgeProducer.java`.
4. **Emit.** Delete both fetcher builders, not just the child one:
   `TypeFetcherGenerator.buildChildTableMethodFetcher` (`:1992`, dispatched from `:681`)
   and its root-site cognate `buildQueryTableMethodFetcher` (`:1657`, dispatched from
   `:598`). Drop the two leaves from `TypeFetcherGenerator.IMPLEMENTED_LEAVES`
   (`QueryField.QueryTableMethodTableField` at `:310`, `ChildField.TableMethodField` at
   `:340`): that set is one arm of the four-way `IMPLEMENTED_LEAVES` /
   `NOT_DISPATCHED_LEAVES` / `PROJECTED_LEAVES` / `STUBBED_VARIANTS` partition over the
   `GraphitronField` seal, so the edit is a main-source constant, not a test enumeration.
   Sweep `TypeClassGenerator` and `JoinPathEmitter`.
5. **Report policy.** Drop `tableMethod` from `WITHHELD_FROM_V1` in
   `DirectiveSupportReport` (shrinks to `sourceRow`, `experimental_constructType`);
   regenerate `supported-directives.adoc`; the derived exempt set in
   `DirectiveDocCoverageTest` follows automatically since the directive is no longer
   declared.
6. **Sakila example.** The example schema is main resources, not test source:
   delete the three `@tableMethod` fixtures from
   `graphitron-sakila-example/src/main/resources/graphql/schema.graphqls` (the root
   fetcher at `:341`, `Inventory.filmViaTableMethod` at `:1282`,
   `Film.languageViaTableMethod` at `:1618`) and the prose-only mentions at `:681`,
   `:1294-1297`, and `:1624`; delete `SampleQueryService.tableMethodFilm` /
   `tableMethodLanguage` in graphitron-sakila-service.
7. **Tests.** Delete the `GraphQLQueryTest` execution tests and the R277 `@Disabled`
   test, `TableMethodFieldPipelineTest`, `TableMethodFieldValidationTest`,
   `QueryTableMethodTableFieldValidationTest`, and `TestTableMethodStub`; retire the
   `@tableMethod` rows in `GraphitronSchemaBuilderTest`. In `ClassifiedCorpus` the two
   examples split: `"table-method"` (`ClassifiedCorpus.java:562`) deletes wholesale, but
   `"record-method"` (`:629`) mixes `@lookupKey` and `@tableMethod` in one fixture and is
   edited, not deleted (strip the `@tableMethod` half: the `inventories` field, its
   `@classified` coordinate, the example id, and the explanatory comment). No coordinate
   is orphaned by the edit: Child/Fetch/List/Table/Record is also demonstrated at `:786`.
   Repoint the successor description in the `RetiredVocabularyGuardTest` registry entry
   `Retired("RecordTableMethodField", "a batched leaf with a TableExpr.MethodCall table")`
   (`RetiredVocabularyGuardTest.java:64`): it names a type this item deletes. Sweep the
   remaining test files that mention the vocabulary.
8. **Coverage meta-tests.** Deleting seal arms changes the enumerated variant sets these
   tests police:
   `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus`
   (`generators/GeneratorCoverageTest.java`), `VariantCoverageTest`,
   `ProjectionCoverageTest`, `ReachableSourceShapeCoverageTest`,
   `catalog/FieldClassificationProjectionTest`, `catalog/LspColumnDispatchProjectionTest`,
   the graphitron-mcp `EdgeCoverageTest`, and the LSP behavioural tests. Update their
   enumerations alongside the deletions; no surviving coordinate may lose its last
   demonstration. Two of the enumerations these tests police are main-source constants
   rather than test data, and are edited where they live: the
   `TypeFetcherGenerator.IMPLEMENTED_LEAVES` rows in step 4, and the
   `FieldClassification.TableMethod` / `.QueryTableMethod` entries in
   `EdgeProducer.EDGE_BEARING_FIELDS` (`graphitron-mcp/.../EdgeProducer.java:281` / `:289`).
9. **Roadmap state.** R288 stays as filed (already narrowed to the polymorphic-interface
   N+1 case, independent of `@tableMethod`); R277 is already Discarded. Two Backlog items
   this removal invalidates must be dispositioned in the same commit that ships it, per
   `roadmap/workflow.adoc`'s "reach for Discarded rather than leaving a stale plan in
   Backlog":
   - **R529** (`tablemethod-subshape-buildtime-rejection`) is Discarded. It is wholly
     about lifting `TypeFetcherGenerator`'s `@tableMethod` multi-hop / condition-join
     `unsupportedPath` runtime throw into a build-time rejection; deleting the arm
     discharges it outright, so there is nothing left to renarrow.
   - **R240** (`tablemethod-return-type-token-threading`) is renarrowed, not discarded.
     Its `@tableMethod`-side motivation evaporates (`buildQueryTableMethodFetcher` and
     the strict `ClassName.equals` check it justified both go), but
     `MethodRef.StaticOnly` survives on the `@condition` / `@externalField` producers
     (`ServiceCatalog.java:618` / `:691`), so the type-token lift still has a subject.
     Rewrite its body to drop the deleted anchors and restate the surviving scope.

   R333 (`coordinate-lowers-to-datafetcher-queryparts`) needs more than its residue note
   at `:905-907`: `TableExpr.MethodCall` is also the subject of the node definition at
   `:799`, two model-table rows (`:166`, `:1832`), and the resolution paragraphs at
   `:1863-1865`, `:1901-1903`, `:1915`. Sweep all of them when the item ships; a living
   document describing a deleted seal arm is exactly what the Done-gate retirement sweep
   over roadmap bodies is for.

## Acceptance criteria

- `directives.graphqls` no longer declares `@tableMethod`; a schema applying it fails
  schema validation as an unknown directive.
- No main-source or test-source references to the retired vocabulary remain in any
  module (graphitron, graphitron-lsp, graphitron-mcp, sakila modules); the retirement
  sweep at the Done gate is clean.
- The coverage meta-tests in step 8 enumerate the shrunk variant sets, and no surviving
  coordinate has lost its last demonstration.
- The migration report renders `tableMethod` under "Legacy-only directives" and nowhere
  else; the `directive-support --verify` drift guard is green.
- Full reactor green under `-Plocal-db`.

## Implementation deltas

Two shapes the plan did not anticipate, both found by the compiler and resolved in-flight:

- **`FieldClassification.QueryTableMethod` is renamed, not deleted.** Step 2 called for deleting both
  classification seal arms, but the root arm is load-bearing beyond the directive:
  `QueryField.QueryRoutineTableField` and `MutationField.MutationRoutineWriteField` both project onto
  it (`CatalogBuilder`), and after removal it is the routine projection and nothing else. It is renamed
  `FieldClassification.RoutineBacked`, mirroring the sibling `ServiceBacked`, with its javadoc restated
  around the generated `Routines`-class call surface. The LSP hover / label / decl-target arms and the
  MCP `SchemaView` / `EdgeProducer.EDGE_BEARING_FIELDS` entries follow the rename. The child arm
  `FieldClassification.TableMethod` genuinely had no surviving producer and is deleted as planned.
  Note the projection-record simple name is user-visible (hover headers, inlay labels), so the rename
  is a visible-string change, not an internal refactor.
- **`ServiceCatalog.reflectTableMethod`'s directive-forked knobs collapse.** The method stays, per
  step 2, but with `@tableMethod` gone every surviving caller is `@condition`, passing
  `TableSlotPolicy.REQUIRED` and a null `expectedReturnClass`. The `FORBIDDEN` arm, the
  `TableSlotPolicy` enum, the `expectedReturnClass` parameter, and the strict `ClassName.equals`
  return check are all unreachable, as is `ReflectionError.ReturnContext` (whose only non-`SERVICE`
  arm rendered the `@tableMethod` prose). All are deleted, collapsing `ReturnTypeMismatch` to a single
  message form. Step 9 already anticipated the strict-return check going with R240's renarrowing.

## Review feedback (In Review -> Ready, independent session)

The removal itself is complete and correct: all nine scope-sweep steps verified individually,
full reactor green under `-Plocal-db` (13/13 modules), both in-flight deltas justified and
recorded. One residue blocks the Done gate.

**`ArgCallEmitter.buildMethodBackedCallArgs`'s four-arg overload is now dead, and its javadoc
was garbled by the sweep.** The overload at `ArgCallEmitter.java:107` had exactly two callers,
both deleted by this item: the `TableExpr.MethodCall` arm of `JoinPathEmitter.emitTableExpression`
and the `@tableMethod` fetcher builders in `TypeFetcherGenerator`. Nothing in main or test source
calls it now; both surviving call sites (`TypeFetcherGenerator.java:564` and `:6641`) pass five
arguments. An uncalled method left behind by a removal item is unremoved machinery, which is the
item's own charter.

The javadoc sweep over the same block also mis-substituted. `ArgCallEmitter.java:99-100` now reads
"neither `@service` nor `@service` methods declare a Table parameter" (the `@tableMethod` half of
the original "neither `@service` nor `@tableMethod`" was replaced with `@service`), and the header
at `:88` now claims the emitter serves the "`@condition` call site" while `:101-102` states, still
correctly, that `@condition` emission lives in `QueryConditionsGenerator`. Both live callers are
`@service`; no `@condition` argument list is built here.

Fix: delete the four-arg overload, and restate the surviving five-arg javadoc to name only the
`@service` call surface it actually serves. No test change expected; the build should stay green.

**Resolved.** Both findings confirmed on inspection and fixed together. The four-arg overload is
deleted; the two surviving call sites (`TypeFetcherGenerator.java:564`, the child service
rows-method, and `:6641`, the root service fetcher) already passed five arguments, both with a
{@code null} `tableExpression`, so no call site changed. The five-arg javadoc absorbs the deleted
overload's parameter documentation with the mis-substitution corrected: the header now states that
both call sites are `@service`, the `tableExpression` note reads "a `@service` method declares no
Table parameter" and keeps the `@condition` pointer at `QueryConditionsGenerator` (the one place
`@condition` legitimately appears here), and `sourcesExpression` gains the parameter entry it never
had. `{@link ArgCallEmitter#buildMethodBackedCallArgs}` at `TypeFetcherGenerator.java:6593` is now
unambiguous against a single method. Full reactor green under `-Plocal-db`.

### Second review pass (In Review -> Ready)

The structural half of the rework is right: the four-arg overload is gone, no call site changed,
the mis-substituted "neither `@service` nor `@service`" clause reads correctly, the
`@condition` pointer stays where it belongs, and `sourcesExpression` is documented for the first
time. Retirement sweep clean (surviving mentions are the deliberate keeps: `legacy-directives.graphqls`,
the migration-guide pointer, the generated Legacy-only bullet, `ServiceCatalog.reflectTableMethod`,
`RetiredVocabularyGuardTest`'s registry, R403's body, and the changelog/audit history).
User-manual prose carries no roadmap markers. Full reactor green under `-Plocal-db`, 13/13 modules.

One claim in the replacement javadoc is false, and it is the claim the rework introduced rather
than inherited. `ArgCallEmitter.java:90-91` now reads "Both call sites are `@service`: the root
service fetcher and the child service rows-method." There is no root call site. Both callers are
`ChildField` arms of `TypeFetcherGenerator.generateTypeSpec`'s dispatch:

- `TypeFetcherGenerator.java:564` sits in `case ChildField.ServiceTableField` (`:553`) and feeds
  `SplitRowsMethodEmitter.buildServiceTableLift`, the child service table lift.
- `TypeFetcherGenerator.java:6641` sits in `buildServiceRowsMethod` (`:6613`), whose own javadoc
  says it emits "the rows method backing a `ServiceTableField` or `ServiceRecordField` DataLoader";
  its single caller is `:570`, in `case ChildField.ServiceRecordField` (`:568`).

Root service reads and writes are the separate `QueryServiceTableField` / `QueryServiceRecordField`
/ `MutationService*` permits, and none of them reaches this helper. The pre-removal javadoc's
"root `@service` or `@tableMethod` fetcher" was accurate because the deleted root `@tableMethod`
fetcher was the root caller; with it gone, the surviving population is child-only.

The same mistake propagates into the new `@param sourcesExpression` entry (`:105-108`), which says
the slot is "`null` at the root fetcher, where a Sources slot is rejected". No caller passes null;
both pass `CodeBlock.of("keys")`. The null-rejects-Sources behaviour is real code
(`ArgCallEmitter.java:183`) but is now caller-unreachable, so attributing it to a root fetcher
invents the caller the deleted overload used to be.

Fix: restate the header to name the two child call sites (the service table lift and the service
record rows-method), and reword the `sourcesExpression` entry so the null case describes the guard
rather than a caller. Two clauses; no code or test change expected.

**Resolved.** Both call sites re-verified before editing: `:564` sits in
`case ChildField.ServiceTableField` (`:553`), and `:6641` sits in `buildServiceRowsMethod`, whose
only caller repo-wide is `:570` in `case ChildField.ServiceRecordField`. Neither is reachable from a
root permit, and both pass `CodeBlock.of("keys")`, so the previous javadoc was wrong on both the
root attribution and the null case. The header now names the two child arms explicitly (the
`ServiceTableField` lift-back call feeding `SplitRowsMethodEmitter.buildServiceTableLift`, and the
`ServiceRecordField` rows-method body) and states that root service permits emit elsewhere. The
`sourcesExpression` entry now says both callers pass the batch `keys` parameter and describes the
`null` arm as a caller-unreachable guard rather than attributing it to a caller. Javadoc only; no
code or test change, full reactor green under `-Plocal-db`.

## Retired vocabulary

`@tableMethod`, `TableMethodDirectiveResolver`, `ChildField.TableMethodField`,
`QueryField.QueryTableMethodTableField`, `FieldClassification.TableMethod`,
`FieldClassification.QueryTableMethod`, `TableExpr.MethodCall`,
`ProducerBinding.RootTableMethod`, `groundTableMethodField`,
`ServiceCatalog.TableSlotPolicy`, `ReflectionError.ReturnContext`,
`buildChildTableMethodFetcher`, `buildQueryTableMethodFetcher`,
`buildTableMethodParentCorrelation`,
`TestTableMethodStub`, `TableMethodFieldPipelineTest`,
`TableMethodFieldValidationTest`, `QueryTableMethodTableFieldValidationTest`.
