# Roadmap staleness audit: 2026-08-19

A point-in-time review of every active roadmap item under [`roadmap/`](../)
against the **current** state of the codebase on `claude/graphitron-rewrite`
(HEAD `f6e9c34`, committed 2026-08-18 22:31, audited 2026-08-19). The goal is
to find items whose premise no longer holds: work already shipped, constructs
renamed or removed, dependencies that have since landed or been discarded, or
specs grown stale enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-08-18` staleness audit, which has been deleted;
only the latest **staleness** audit is retained. The other thirteen files in this
directory are **not** staleness audits and are left in place (deleting them would
strand lineage that shipped items and active items cite by path, and they are
provenance I did not author). They are:

- `2026-06-16-source-operation-target-reframe.md`, the permanent lineage document
  for R316 (Done).
- `2026-06-30-release-planning.md`, the first-release scoping working document,
  edited in place as scope iterates.
- `2026-07-04-r222-r333-conformance-analysis.md`, the R222/R333 conformance record
  (R222 discarded 2026-08-06; this is a historical conformance snapshot).
- `2026-07-26-fcis-command-layer-distance.md`, the FCIS command-layer distance
  analysis (symbol-anchored snapshot with a stated re-derivation method).
- `2026-08-05-fact-base-h2-spike.md` and `2026-08-05-h2-functions-jooq-spike.md`,
  the two grounding spikes for the fact-base stack.
- `2026-08-06-fact-base-impact-sweep.md`, the **architecture-drift companion** to
  this audit. It records the whole-board sweep of which items the adopted
  fact-base architecture (R595 substrate, R589 derivation) subsumes, reshapes, or
  consumes. This staleness audit tracks *symbol and reference* drift; that sweep
  tracks *architecture* drift.
- `2026-08-06-r222-lineage.md`, the absorption ledger and rejected-design record
  preserved when R222 was discarded.
- `2026-08-06-demand-exemption-census.md`, `2026-08-06-directive-consumer-census.md`,
  `2026-08-06-graphql-java-diff-spike.md`, `2026-08-06-structural-classifier-census.md`,
  the four grounding censuses/spikes filed alongside the fact-base work.
- `classification-test-dsl-inventory.md`, the permanent corpus-retirement inventory
  (its "closed and historical" banner is intact).

## Headline: two big architectural items reached Done and staled six active items, one of which the prior audit missed. R704's routine read surface and R642's mcp-store cut retired live symbols that six items still name; R650's cross-table-subselect conversion (In Review last window, now Done) obsoletes R76's whole premise, which the prior grep missed because the item spells the join `leftJoin`, not "LEFT JOIN". Two prior flags left the board (R7 discarded, R323 re-specced), and all 32 long-standing retired symbols re-verify grep=0

Where the prior window retired a small symbol set and staled exactly one item,
**this window shipped two large architectural items and staled six**, because
each cut a construct that active items across the routine and mcp/lsp clusters
still name as live current state. **Four items reached Done since the prior
audit**: **R642** (`catalog-facts-readers-move-to-the-store`) and **R704**
(`routine-composition-surface-from-facts`) after the prior audit's commit, and
**R650** (`root-connection-over-discriminated-interface`) and **R488**
(concept-explainer pages) in the gap between the prior audit's analysis snapshot
and its commit (the prior audit recorded R650 as In Review). Two prior flags left
the board: **R7** (`decompose-typefetchergenerator`) was **discarded** (superseded
by R682), and **R323** (`nestingfield-multiparent-*`) was **re-specced whole** and
moved Backlog to In Review, born-current, so it exits §C.

- **R704 (`routine-composition-surface-from-facts`, Done)** shipped the whole
  `@routine` read surface: it deleted the five carve-outs (a root routine field
  silently dropping `@defaultOrder`; deferred `@orderBy`/`@condition`; rejected
  `@asConnection`; a demanded restated `@table` on the return type; a refused
  implicit child hop out of the result), reshaped `validateListRequiresOrdering`
  (the `Chain` exemption is gone, replaced by a routine arm through
  `listOrderingDiagnostic`), and captured the catalog facts as relations
  (`sql_table.table_type`, `sql_routine`, `sql_routine_parameter`, the hop-view
  `NAME_MATCH` arm). It absorbed **R622** and **R659** on the way and repointed the
  inbound routine citations (R448, R660, R662, R663, R677). **Two items drift on
  it: R709** (its `sql_routine` capture is delivered, §B.11) and **R662** (two Notes
  bullets name deferrals R704 removed, §C.13). R663, R660, R677, R668 re-verify
  clean (R663 was explicitly re-checked against the post-R704 tree).
- **R642 (`catalog-facts-readers-move-to-the-store`, Done)** cut graphitron-mcp off
  the generator: it deleted the `CatalogFacts` class, dropped the `edges` mcp tool,
  collapsed `services`/`conditions`/`records` into one `code` tool, and moved mcp's
  reads off the taxonomy types and the language-server `Workspace`. **Three items
  name a retired symbol as live**: **R594** (the `edges` tool, §C.12), **R684** (the
  `graphitron-mcp imports CatalogFacts` example, Ready, §C.12), and **R638** (an
  internal `CatalogFacts` contradiction, In Progress, noted in cross-cutting rather
  than flagged because it is the live edit zone and already half-aware).
- **R650 (`root-connection-over-discriminated-interface`, Done)** converted the
  discriminated-interface **cross-table participant `@reference` join** from a
  step-mutated `LEFT JOIN` into a capped correlated subselect, split
  `DiscriminatedTableFragments.assembly` into `projection` + `joinedStep`, and
  retired `crossTableJoinChain`, `crossTableAliasDeclarations` (as a join concept),
  `CrossTableField.aliasVarName()`, `TABLE_INTERFACE_ROOT_CONNECTION_DEFERRED`, and
  the discriminated-interface cross-table "LEFT JOIN" wording (`RetiredVocabularyGuardTest`).
  **R76 drifts (§B.12), and the prior audit missed it**: R76's whole premise is that
  interface fetchers emit conditional cross-table joins via `step = step.leftJoin(...)`
  in `DiscriminatedTableFragments`, and it proposes a `$fieldsJoin` helper composing
  those joins. The surviving `step.leftJoin(...)` block is now only the joined-detail
  join. The prior audit's cross-table `LEFT JOIN` grep did not catch R76 because the
  item spells the mechanism `leftJoin` / "cross-table joins", never the phrase.
- **R488 (concept-explainer pages, Done)** replaced `ConceptPages.readTitles` with
  `readPages`. No active item cited `readTitles`; **no external item drifts.**

Net: **1 §A / 12 §B / 27 §C / 0 §D**, flag total **40**, up three from the prior
audit's 37. New this window: R76 and R709 enter §B; R594, R662, R684 enter §C.
Leaving: R7 (discarded) and R323 (re-specced to In Review). **None of the surviving
carried flags was edited this window**, so every carried flag holds at its prior
line anchors, and all **32** long-standing retired symbols re-verify `grep`=0 at this
HEAD (including `remoteIfReferenceJoin`/`translatedFkRejection`, the R649 family,
the `Split*`/`Record*` merge, `TableInputType`, the `Operation` seal, and the R57
`FilterBinding` reshape). **R193 and R213 remain the two overdue subsumption
candidates**, unmoved; **R333, R427, R555 remain the standing Ready-set refreshes**,
untouched, and **R684 joins them in the Ready set as a fresh re-anchor.**

## Changes since the 2026-08-18 audit

The prior audit stated a baseline of next-id **R702** (231 item files) and recorded
R650 as In Review, R642 as In Progress, R57 as the window's one Done. Current HEAD
is `f6e9c34` (2026-08-18 22:31), next-id **R721**, **243** item files (measured,
excluding `README.md` and `changelog.md`). The window spans the R702 -> R720 id
allocations, four Done transitions, three discards, and one full re-spec.

**Items that reached Done this window, and what each did to the symbol set:**

- **R704 (`routine-composition-surface-from-facts`, In Review -> Done):** the
  `@routine` read surface, re-derived from catalog facts. Deleted the five carve-outs
  above; reshaped `validateListRequiresOrdering`; added `sql_routine`,
  `sql_routine_parameter`, `sql_table.table_type`, and the routine intent relations.
  Absorbed R622 and R659; spun out R717/R719/R720 and repointed R448/R660/R662/R663/R677.
- **R642 (`catalog-facts-readers-move-to-the-store`, In Review -> Done):**
  graphitron-mcp reads only the store. Deleted `CatalogFacts`; dropped the `edges` tool;
  collapsed the three code tools into one; moved mcp off the taxonomy types.
- **R650 (`root-connection-over-discriminated-interface`, Done):** `@asConnection` over a
  single-table discriminated interface at both the root and child coordinates, via
  correlated subselects rather than the cross-table `LEFT JOIN`. Retired the identifiers
  and phrase listed in the headline.
- **R488 (concept-explainer pages, Done):** the explainer-page item-declaration contract;
  `readTitles` -> `readPages`.

**Verification that these Done items staled six items (the window's decisive check):**
the retired identifiers `CatalogFacts`, `crossTableJoinChain`, `crossTableAliasDeclarations`,
`CrossTableField.aliasVarName()`, `TABLE_INTERFACE_ROOT_CONNECTION_DEFERRED`, and
`ConceptPages.readTitles` all `grep`=0 across the item files (R650's and R488's retired
*identifiers* stale nobody). The six drifts are premise/prose drifts, not identifier hits:
R709's capture premise is delivered (`sql_routine` exists), R662's and R594's Notes name
removed deferrals/tools, R684's and R638's example names the deleted `CatalogFacts`, and
R76's cross-table-join mechanism is gone. The cross-table `LEFT JOIN` wording re-verifies:
the LEFT JOIN mentions in R333, R242, R393, R112 are the unrelated base/detail joined-table
use, R697 has none at HEAD, and R76 was the one item naming the retired mechanism, missed
before because it never uses the phrase.

**Items filed this window (R702 -> R720), all born-current except R709** (every cited
current-state symbol resolves to a live main-source location):
R702 (`exact-catalog-name-comparisons`), R703 (`list-valued-bean-member-unchecked-cast`),
R705 (`condition-join-hops-in-reference-filter-paths`), R706 (`store-contention-fails-fast`, Spec),
R707 (`jooq-record-param-pipeline-body-string-assertions`), R708 (`projection-selection-gate-depth-leak`),
R710 (`jooq-node-metadata-as-stated-facts`, Ready), R711 (`nodehood-derives-from-two-corpora`),
R712 (`three-strata-capture-derive-query`, Spec), R713 (`graphitron-decodes-read-rows-not-ast`),
R714 (`assembled-schema-owns-the-sdl-census`), R715 (`decodes-normalize-internal-grammars`, Spec),
R716 (`mcp-boundary-guard-generator-package-coverage`), R717 (`routine-carrier-residual-path-correlation`),
R718 (`column-scope-admits-the-chain-terminus`), R719 (`routine-carrier-discriminator-from-payload-shape`),
R720 (`routine-binding-prose-names-the-directive`). **R704 was filed and reached Done in-window.**
**R709 (`catalog-routine-facts`) filed already stale** (its `sql_routine` capture shipped in
R704 the same window; §B.11). Next-id is now **R721**.

**Discards this window:** **R7** (`decompose-typefetchergenerator`, superseded by R682);
**R622** (`routine-carrier-explicit-data-field-path`, superseded by R704); **R659**
(`routine-chain-order-directive-silent-noop`, superseded wholesale by R704). None left a
dangling `depends-on:` edge.

**Transitions:** R704, R642, R650, R488 to Done; **R323** Backlog -> In Review (re-specced whole);
R693 In Review; R710 to Ready; R712/R715/R706 to Spec; R642 and others as above. Actively
drafted and correctly **not** flagged as stale: R680, R638, R347 (In Progress), R693, R323
(In Review), and the Spec-tier fact-model / routine / lsp clusters (R682, R685, R686, R687,
R697, R706, R712, R715), all born-current.

**Board accounting.** **243 item files** today (measured), up from 231. Status distribution:
**210 Backlog, 22 Spec, 6 Ready, 3 In Progress, 2 In Review, 0 Done**. Tombstone-free
(`grep` for `status: Done` in `roadmap/*.md` = 0). No duplicate `id:`; `changelog.md` carries
`next-id: R721`, clearing the max allocated id (R720). A `depends-on:` sweep resolves all
**twelve** non-empty edges (up from eight) to present files. The roadmap-tool regenerates
`README.md` with **no drift** at this HEAD. The only structural nits are the same four
**legacy** items still missing a `bucket:` key (§D), all pre-dating this window.

## Scope and method

All **243** `R<n>` item files were reviewed. Every driving symbol below was re-checked against
a fresh `grep` of the main sources (`graphitron`, `graphitron-mcp`, `graphitron-lsp`,
`graphitron-model`, `graphitron-maven-plugin`, `graphitron-fixtures-codegen`), not carried on
the prior audit's word.

**R704 / R642 / R650 / R488 retirements, verified at this HEAD:** `CatalogFacts` `grep`=0 in
main real code (deleted class, no source references); the `edges` mcp tool file is gone;
`crossTableJoinChain`, `crossTableAliasDeclarations`, `CrossTableField.aliasVarName()`,
`TABLE_INTERFACE_ROOT_CONNECTION_DEFERRED` `grep`=0 (guard-string / test-only hits excluded);
`ConceptPages.readTitles` `grep`=0. `sql_routine` and `sql_routine_parameter` are live in
`graphitron-model.sql` and `JooqCatalog.RoutineCallFacts`. The surviving `step.leftJoin(...)`
in `DiscriminatedTableFragments` (`:314`, `:340`) is the joined-detail join only, not the
retired cross-table mechanism.

**Long-standing retirements, re-verified still retired at this HEAD (`grep`=0 in main real
code; `{@code}`/`{@link}`/comment/guard-string/test-name hits excluded):** `remoteIfReferenceJoin`,
`translatedFkRejection`, the R649 family (`reflectServiceMethod`, `looksLikeSourcesShape`,
`validateRootInvariants`), `CompileDependencyGraphBuilder`, `RowsMethodBody` / `RowsMethodSkeleton`,
`QueryConditionsGenerator` / `TypeClassGenerator` / `TypeConditionsGenerator`, `buildNonTableInputType`,
`LookupValuesJoinEmitter`, `InlineTableFieldEmitter` / `InlineLookupTableFieldEmitter` /
`InlineColumnReferenceFieldEmitter`, `FkTargetConditionEmitter`, `ParentProjectionContainmentCheck`,
`MutationConditions` (phantom), the `Split*` / `Record*` variant names, `BatchedLookupTableField`,
`ServiceCatalog.PkLessParent`, `MutationInputResolver.resolveInput`, `TableInputType` /
`.inputFields()`, the `Operation` seal arms, `Rejection.Deferred.planSlug`, and the R57
`liftedSourceColumns`-on-carriers reshape (the sealed `FilterBinding` with
`Local(List<ColumnRef> ownTableColumns)` / `Remote` is live on the two reference carriers, while
the resolver-side `liftedSourceColumns` on `JoinPath` and `Resolved.FkTarget.DirectFk` survives).
`AUTHOR_ERROR` `grep`=0 in `FieldRegistry.java`. Prefix greps still misjudge the retired
service/mutation symbols (`PkLessParent` returns the surviving `SourcesOnPkLessParent` arm;
`resolveInput` returns `RecordBindingResolver.resolveInput`; `Operation` returns comments and a
string-literal fact key; `liftedSourceColumns` returns the surviving resolver-side component);
every flag below keys on the fully-qualified member.

## A. Obsolete: should leave the active roadmap (1)

| Item | Status | Why obsolete | Recommended action |
|---|---|---|---|
| **R209** field-registry-typed-rejection-trace | Backlog | **Carried (7 windows); still Backlog, still fully delivered.** The entire deliverable ("remove the `RejectionKind.AUTHOR_ERROR` default arm and emit `RejectionKind.of(rejection)` consistently with `traceOutput`") is shipped by **R585**. Re-verified at the symbol this window: `AUTHOR_ERROR` `grep`=0 in `FieldRegistry.java`, `RejectionKind.of(...)` emitted on the `Unresolved` arms. Nothing remains to do; this window did not touch it. | **Discard**, recording R585 as the delivery vehicle. The symbol check is done and clean; retire to lineage. |

R520 (`table-on-input-removal-housekeeping`) stays a coherent deliberately-deferred docs tail,
not obsolete.

## B. Outdated: needs re-spec (premise or targets materially changed) (12)

Ten carry forward from the prior audit unchanged (none edited this window; each re-verified at
the symbol with the premise-target still `grep`=0 and a live successor); **two are new this
window: R76** (R650's cross-table-subselect conversion, §B.12) **and R709** (R704's routine-fact
capture, §B.11). **R193 and R213 remain the two overdue subsumption candidates** (R649/R585/R589
shipped what they scoped); running both re-checks, most likely closing both as subsumed, is the
cheapest board-cleaning available.

| Item | Status | What changed | Recommended action |
|---|---|---|---|
| **R193** service-param-classification-sealed-hierarchy | Backlog | **R649 shipped the deliverable.** The item asks to refactor `reflectServiceMethod`'s `sourcesShape.isEmpty()` predicate chain (anchored at `ServiceCatalog.java:258-329`) into a sealed classifier plus one switch that owns the rejection text. R649 delivered exactly that: `reduceClaims` mints one sealed `ParamRole` per parameter, the classify phase owns rejection ordering, and both the anchor (`reflectServiceMethod`) and `looksLikeSourcesShape` are now `grep`=0. | **Re-derive the residue against shipped R649; most likely close as subsumed.** If a thin diagnostic-arm residue survives `ParamRole`, re-spec it onto the shipped classifier and drop the `reflectServiceMethod:258-329` / `looksLikeSourcesShape` anchors; otherwise discard, recording R649 as the delivery vehicle. |
| **R72** slim-servicecatalog-to-lookup | Backlog | **Premise materially changed by R649.** The current-state diagnosis is a line-by-line census of pre-R649 `ServiceCatalog` (`reflectServiceMethod` ~170 lines; duplicated message text; not-found and parameter-names rejections at specific line cites). R649 dissolved `reflectServiceMethod` into `decodeServiceMethod` / `reduceClaims` / `bindServiceMethod` and moved policy toward the resolver, part of what this item wanted. `reflectTableMethod` and `reflectExternalField` survive. | **Re-spec.** Re-derive the diagnosis against the post-R649 split: measure how much slimming R649 already accomplished, drop every stale `ServiceCatalog.java:NNN` line cite, and re-baseline the remaining goal on the new shape. |
| **R213** input-field-rejection-attribution | Backlog | **Escalated, still unactioned (now five windows overdue).** R585 inverted the load-bearing premise (the record now carries a `SourceLocation` the item still says it lacks), and R589's occurrence-path derivation (Done) delivers the attribution split the item asks for. The item's own "re-check after R589 slice 5" note is long due and nobody has run it. | **Re-derive the residue against shipped R585 + R589; most likely close as subsumed.** If a thin residue survives, re-spec onto the shipped record and drop the "grows/has no `SourceLocation`" claims; otherwise discard, recording R585 + R589 as the delivery vehicles. |
| **R66** rejection-string-carrier-widening | Backlog | Carried. Phase **A2** ("widen `Unresolved.reason: String` -> `rejection: Rejection`") was verbatim what **R585** shipped. Phases A1 (`ParsedPath.errorMessage`), A3 (`UnboundArg.reason`), B1 (`EnumValidation.Mismatch`), B2 (`TypeBuilder` aggregations) survive. | **Re-spec:** strike A2 as delivered by R585; re-baseline the four surviving phases onto the shipped record; fix the `:25-30` anchors. |
| **R34** nodeid-migration-quickfix | Backlog | Carried, self-contradictory. The "shim facts" driver names three WARN sites **R473 deleted** and **R27 was discarded**; the discard commit half-reconciled the item, leaving the mechanism sections still naming the deleted sites as the live gap. | **Re-spec:** the "shim facts" driver is void. The migration goal survives, but its source must be re-derived onto R473's landed grammar rejections or the R589 claim relation. Retitle off "shim facts". |
| **R122** compound-entity-mutations | Backlog | Carried. "Design space narrows under R222" leans on R222's discarded recursive `InputUsage` model; R222 left the board 2026-08-06. `TableTargetField` (added by this item) is live. | **Re-spec the "narrows under R222" section**: drop the discarded `InputUsage` carrier; re-express the nested-input model against the captured `intent_`/`applied_` relations. Keep the compound-mutation goal, `@reference(path:)` flattening, and `TableTargetField`. |
| **R462** nested-fetcher-outgoing-field-edges | Spec | Carried. Central target `CompileDependencyGraphBuilder.addFieldEdges` deleted (`grep`=0); the body's own dissolution condition (`:163`) has occurred. Also cites absent `FkTargetConditionEmitter.emitTerm` (`:45`) and phantom `MutationConditions` (`:57`) as live. | **Re-derive against the plan-projected recompile graph.** Confirm whether the nested-fetcher per-field edge is now modeled under `EmitPlan`; if closed, **discard**, else **re-spec**. In the same pass repoint `FkTargetConditionEmitter.emitTerm` -> `FkTargetConditionFilter` via `ConditionCommands` and drop `MutationConditions`. |
| **R545** model-free-of-emit-vocabulary | Backlog | Carried. `RowsMethodBody` / `RowsMethodSkeleton` deleted, so the diagnosis and second deliverable are gone. First deliverable survives: `ClassName` / `TypeName` are still model-pervasive. | **Re-spec.** Drop the `RowsMethodBody` diagnosis and second deliverable; keep and re-baseline the first (`JavaTypeRef` replacing `TypeName` / `ClassName`) against the post-R549 model. |
| **R85** helper-emission-non-fetcher-hosts | Backlog | Carried. Both named edit-target generators deleted: `QueryConditionsGenerator` (R552), `TypeClassGenerator` (R549). Successors `render/ConditionGlueRenderer` / `render/ProjectionUnitRenderer` live. Also names absent `InlineTableFieldEmitter.java:144` / `InlineLookupTableFieldEmitter.java:218` as live host files. | **Re-derive against the new `render/` layer.** Determine whether the renderers still exhibit the duplicated helper-emission problem; drop every dead `QueryConditionsGenerator.java:NNN` cite and the two absent `Inline*Emitter.java:NNN` file cites. |
| **R71** recordn-key-parity-lifter-and-non-jooq-record-parents | Backlog | Carried. R431 (Done) deleted the target surface: the body cites `SourceKey.Reader.SourceRowsCall` as "the live surface", but `SourceKey` is a plain record with no `Reader`. Successors `KeyLift` / `LifterRef` / sealed `Wrap` live; "R431 ... plans to decompose" reads present tense but R431 is Done. | **Re-spec the current-state / approach section** against the decomposed `KeyLift` / `LifterRef` / `Wrap` model; drop the `SourceKey.Reader.SourceRowsCall` re-anchor note and the tense error. |
| **R709** catalog-routine-facts | Backlog | **New this window; premise delivered by R704 (Done, same window).** The item's whole basis ("the catalog census holds tables, columns, keys and schemas, and no routines"; "what it takes: `sql_routine` alongside `sql_table`, carrying the generated class FQN and the generated method name") is false: `sql_routine` and `sql_routine_parameter` already exist in `graphitron-model.sql`, carrying schema, name, generated `Routines` class FQN, method name, and parameters in declaration order, captured by `JooqCatalog.RoutineCallFacts`. R668's own note records this as "Landed, in R704 slice 7". R704 states the relations "have no generator consumer yet", so a thin residue may survive (an LSP view resolving a `@routine` application to the class/method pair, plus the producer reader's third arm). | **Re-derive the residue against shipped R704; re-spec down to the un-delivered view/reader work, or discard if that residue is tracked elsewhere (e.g. within R668).** Drop the "no routines" census claim and the `sql_routine`/`sql_routine_parameter` deliverable; recording R704 as the capture delivery vehicle. |
| **R76** participant-fieldsjoin-helpers | Backlog | **New this window; premise obsoleted by R650 (Done). The prior audit missed this** because the item spells the mechanism `leftJoin` / "cross-table joins", never the phrase the cross-table `LEFT JOIN` grep keyed on. R76's premise is that discriminated-interface fetchers emit conditional **cross-table** joins via `step = step.leftJoin(alias).on(...)` in `DiscriminatedTableFragments`, and it proposes a `$fieldsJoin` helper composing those cross-table joins. R650 converted cross-table participant fields to capped correlated subselects (`PathFragments.scalarInnerSelect`) and split `assembly` into `projection` + `joinedStep`; the surviving `step.leftJoin(...)` (`:314`,`:340`) is the joined-detail join only. | **Re-spec.** The step-mutation idiom the item targets now applies only to the joined-detail `LEFT JOIN`; the cross-table-join framing and the per-participant cross-table `$fieldsJoin` design are obsolete. Re-derive whether any join-composition duplication survives on the joined-detail side, or discard if R650's subselect conversion removed the motivating duplication. |

## C. Outdated: update references only (work valid, refs stale) (27)

Substance intact; names and line numbers drifted. Twenty-four carry forward from the prior audit
(not one edited this window, every long-standing driving symbol re-verified still `grep`=0), and
**three are new this window: R594 and R684** (§C.12, R642's mcp-store cut) **and R662** (§C.13,
R704's routine deferral removal). Two rows left §C: **R7** (discarded) and **R323** (re-specced to
In Review, born-current). §C.9 records the absent-`*Emitter` driver, which hits already-listed rows
and adds no distinct item.

### C.0 `Operation` seal fully retired (carried; R563 slice 7)

`Operation` and every `Operation.<Arm>` reference are `grep`=0. Successor: `OperationMember`.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R427** relevance-ranked-search | Ready | `:335` "`operation()` stays `Fetch`" cites the retired accessor; `:339`/`:1162` cite `Operation.Facet` as a live precedent type. | **Re-anchor.** Restate against the member-derived summary fold; repoint `Operation.Facet` onto `OperationMember.Facet`. A **Ready** item; refresh before pickup. |
| **R382** multitable-interface-query-orderby-lowering | Backlog | `:19` cites `operation()` as the live mechanism hardcoding `new OrderBySpec.None()`. | **Re-anchor** to where the hardcoded `OrderBySpec.None` now lives (`OperationMember.OrderBy` sourcing, or `MultiTablePolymorphicEmitter`); verify the ordering gap reproduces. |
| **R562** synthesised-connection-fields-as-coordinates | Backlog | `:17-18` name `Operation.Count`/`Operation.Facet` arms as the observable gap; all three names retired. | **Re-anchor** to `OperationMember.Count`/`Facet`. Model question intact. |

### C.1 Lookup-triplet dissolution drift (carried; R563 slice 6a)

A lookup leaf re-anchors to `BatchedTableField` (or `TableField` / `QueryTableField`) **plus a
lookup member**.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R533** localcontext-guard-predicate-single-source | Backlog | `:15` names `BatchedLookupTableField` as the open edge; `:19` self-corrects but the lead is stale. | **Re-anchor** the `:15` lead to the post-dissolution sibling. |
| **R557** split-query-marker-sweep | Backlog | `:17`'s "consumed (the batched leaves)" lists `BatchedLookupTableField` alongside `BatchedTableField` / `BatchedPivotField`. | **Re-anchor**: drop `BatchedLookupTableField` (now `BatchedTableField` + lookup member). |

### C.2 `@table`-on-input rejection -> deprecation drift (carried; R566)

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R565** unclassified-input-arg-cascade-diagnostic | Backlog | Title (`:3`, `:13`) still leads with the retired `@table`-on-input **rejection** as the driver; the body already frames it against the current state. | **Re-anchor (not full re-spec).** Retitle/re-lead onto a still-current rejection; demote `@table`-on-input to historical framing. |

### C.3 `planSlug` / `SourceKey.Reader` removal drift (carried)

R484 removed `Rejection.Deferred.planSlug`; R431 removed the `SourceKey.Reader` interface.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R454** routine-write-result-shapes | Backlog | `:18` names deferred shapes as "typed `Deferred`s pointing at this item's planSlug"; gone. | **Re-anchor:** deferred shapes surface via `StubKey.VariantClass`. |
| **R447** routine-chain-fetch-form-breadth | Backlog | `:18`/`:33` "`planSlug` points here"; `:24`/`:26` name `SplitLookupTableField` / `RecordTableField` as live; `:23` names the absent `InlineTableFieldEmitter` as live beside the real `SplitRowsMethodEmitter`. **Plus a soft R704 overlap:** its parked note on "connection pagination over a chain containing a routine node" partially overlaps R704's shipped routine-chain pagination. The four deferred fetch-forms themselves are untouched by R704. | **Re-anchor:** drop the `planSlug` phrasing; repoint to `BatchedTableField` (lookup twin: **+ lookup member**); drop the `InlineTableFieldEmitter` cite. **At Spec, check** whether R704 subsumed the parked pagination note. |
| **R180** record-parent-column-read-helper | Spec | `:35` says "R431 ... now In Progress" (R431 is **Done**) and names `SourceKey.Reader.AccessorCall` as live. | **Re-anchor** the `AccessorCall` carrier onto the decomposed model; fix the R431 tense. |
| **R505** tenant-index-parent-row-routing | Backlog | `:21` names "a column read off the parent row (the `SourceKey.Reader` family)". Live via `KeyLift.FkColumns`. | **Re-anchor** the one parenthetical. |

### C.4 Leaf-merge drift: `Split*` / `Record*` -> `Batched*` (carried; R7 and R323 left this window)

`SplitRowsMethodEmitter` is **not** renamed and is correct wherever it appears. **R7** left (discarded,
superseded by R682, whose emitter half empties `TypeFetcherGenerator` into `render` rather than
decomposing it); **R323** left (re-specced whole to In Review, born-current, now naming
`BatchedTableField` / `ResultKeyAliasedField` correctly and carrying its own retired-vocabulary sweep).

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R109** list-valued-external-field-multiset | Spec | `:51`'s planned enum arm asserts "`RecordTableField` with `BatchKey.AccessorKeyedMany`". (The `:92` `Result<Record>` cite is a live untyped-accessor fact, unaffected by R617.) | **Re-anchor** to `BatchedTableField`. |
| **R242** dml-payload-positional-alignment | Spec | `:37-38`'s R305 lineage note "collapsed it into `RecordTableField`"; `:107`/`:148`/`:150`/`:284` cite `LookupValuesJoinEmitter`. (The `:21`/`:194` `Result<Record>` cites are the live DML-RETURNING path, unaffected by R617.) | **Re-anchor** to `BatchedTableField`; repoint `LookupValuesJoinEmitter` to the render values-join family. |
| **R288** inline-interface-and-tablemethod-children | Backlog | `:33-34` name `SplitTableField` / `RecordTableField`; `:24` cites the absent `InlineTableFieldEmitter`. `SplitRowsMethodEmitter` fine. | **Re-anchor** the two variant names to `BatchedTableField`; drop the `InlineTableFieldEmitter` cite. |
| **R116** composite-key-row2-source-row-coverage | Backlog | `:15`'s planned case "classifies as `RecordTableField` with a `LifterPathKeyed`". | **Re-anchor** to `BatchedTableField`. |

### C.5 `ColumnBackedField` dissolution drift (carried; R508)

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R333** coordinate-lowers-to-datafetcher-queryparts | Ready | Retired carriers named live across `:570`, `:694`, `:706`, `:727`, `:747-748`, `:798-799`, `:1112`, `:1140`, `:1145`, `:2009`. Part of the one R333 refresh (§C.7). | **Re-anchor** the carrier names to `ColumnBackedField`. Part of the one R333 pass. |

### C.6 `TableInputType` / `resolveInput` removal drift, plus the `UnboundField` reshape (carried; R519 + R515; R589)

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R234** jooq-embedded-and-udt-input-backings | Backlog | `:15` cites `TypeBuilder.buildNonTableInputType` (`grep`=0) as the live dispatch to extend. | **Re-anchor** to the current `TypeBuilder` input-classification path (`buildInputType`). |
| **R257** updaterows-walker-sdl-substrate | Backlog | `:17` calls `resolveInput` "the legacy resolver" (`MutationInputResolver.resolveInput` gone R515); `:15`/`:19` reach carriers "via `TableInputType.inputFields()`" (gone R519). | **Re-anchor** both: `resolveInput` -> `admitMutationInputFields`, `TableInputType.inputFields()` -> per-consumer input resolution. |
| **R337** input-nesting-projection-classification | Backlog | `:30` reaches an input object's fields "via `TableInputType.inputFields()`" as the LSP-hover mechanism. | **Re-anchor** the one mechanism cite to per-consumer input resolution. |
| **R245** wire-condition-emit-on-mutations | Backlog | `:76` locates the `@condition` slot composition "in `MutationInputResolver.resolveInput`" (method gone R515). `:124`'s test bullet "Override `@condition` on an `UnboundField`" names the pre-R589 carrier. | **Re-anchor** `:76` to `admitMutationInputFields`, and `:124` to `ConditionOwnedField`. |

### C.7 Condition + projection emitter dissolution drift (carried; R552 + R549)

Condition emission is `render/ConditionGlueRenderer`; projection `render/ProjectionUnitRenderer`.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R333** coordinate-lowers-to-datafetcher-queryparts | Ready | **Refresh still incomplete; not touched at all this window.** Still at the symbol: 5x `TypeClassGenerator`, 5x `collectRequiredProjection`, 5x `methodgraph`, `LookupValuesJoinEmitter`, 2x `ParentProjectionContainmentCheck`, 1x `TypeConditionsGenerator`, 4x `InlineTableFieldEmitter`, plus the §C.0/§C.5 carriers and the §C.9 absent-emitter names (21 stale symbol cites). The body is a pure implementation plan whose class names are wrong, and it is **Ready**, so the stale prose bites the next implementer to pick it up. | **Finish the refresh in one pass** (this row + §C.0 + §C.5 + §C.6 + §C.1 + §C.9): repoint `TypeClassGenerator` / `collectRequiredProjection` -> `ProjectionUnitRenderer` / `ProjectionCommands`; drop the `TypeConditionsGenerator`, `ParentProjectionContainmentCheck`, `methodgraph`, `operation()` cites; re-anchor `LookupValuesJoinEmitter` to the render lookup family and the `Operation` seal cites to `OperationMember`; replace the `Inline*Emitter` projection-arm names with the render projection layer and `FkTargetConditionEmitter` with `FkTargetConditionFilter` via `ConditionCommands`. Rows citing `SplitRowsMethodEmitter` stay (live). |
| **R231** emit-text-mapped-enum-fields-as-enum-type | Backlog | `:39` locates "the field-type-emit fork (likely in `TypeClassGenerator` or ...)". | **Re-anchor** the investigation locus to `ProjectionUnitRenderer` / `plan/ProjectionCommands`. |
| **R35** source-orientation-javadocs | Backlog | `:42` enumerates classes for a javadoc sweep, including `TypeClassGenerator` and `TypeConditionsGenerator`, both deleted. | **Re-anchor** the enumeration: drop the two deleted names. Low priority; scope illustrative. Note the R7 discard folds the `TypeFetcherGenerator` layout-javadoc fallback onto this item's own partial-mitigation option. |

### C.8 `InputFieldResolution.Unresolved` reshape drift (empty; R585)

R585 reshaped `Unresolved` to `(fieldName, SourceLocation, Rejection)`. Its consumers R66, R213 (§B)
and R209 (§A) hold the residue. **No item remains in this subsection.**

### C.9 Absent projection/condition `*Emitter` names (carried; render-layer refactor)

A family of per-arm projection/condition emitter names several items cite as **live** current-state
classes, all **`grep`=0 across every main tree** at this HEAD. The work they name lives today in
`ProjectionUnitRenderer` / `ProjectionCommands`, `ConditionGlueRenderer` / `ConditionCommands`, and
`FkTargetConditionFilter`. This subsection adds **no distinct flagged item**; every citer is already
listed above. **R7 left the citer set this window** (discarded).

| Absent name | Cited-as-live in | Live successor |
|---|---|---|
| `InlineColumnReferenceFieldEmitter` | R333 (`:1890`) | render projection layer (`ProjectionUnitRenderer` / `ProjectionCommands`) |
| `InlineTableFieldEmitter` | R333 (`:1752`,`:1891`), R85 (`:20`,`:45`), R447 (`:23`), R288 (`:24`) | render projection layer |
| `InlineLookupTableFieldEmitter` | R333 (`:1891`), R85 (`:21`,`:46`) | render projection layer |
| `FkTargetConditionEmitter` | R333 (`:1893`), R462 (`:45`, `.emitTerm`) | `FkTargetConditionFilter` via `ConditionCommands` |
| `MutationConditions` (phantom) | R462 (`:57`) | none; drop the shim name |

### C.10 `reflectServiceMethod` / `PkLessParent` / `validateRootInvariants` removal drift (carried; R649)

R649's phase split retired `ServiceCatalog.reflectServiceMethod` (its logic is now
`decodeServiceMethod` + `reduceClaims` + `bindServiceMethod`, decoding to a typed `ServiceSignature`),
`ServiceCatalog.PkLessParent`, and `validateRootInvariants`. The two items below name those as live
current-state symbols; both keep their work valid and need only a repoint. (R72 and R193 land in §B
instead, because for them R649 changed the premise, not just a name.)

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R555** deprecate-externalfield-fold-into-service | Ready | Four present-tense cites of `reflectServiceMethod` (`:41`, `:52`, `:103`, `:106`). Every other symbol the item cites (`reflectExternalField`, `validateRootListTableBoundReturnPair`, `validateChildServiceReturnType`, `pickMethod`) survives. | **Re-anchor.** Repoint `reflectServiceMethod` -> `decodeServiceMethod` / `ServiceSignature`. R649's "one entry that picks the method once and reads the raw return type" is exactly the seam this item's Design section wants, so the refresh strengthens it. A **Ready** item; refresh before pickup. |
| **R47** service-short-classname-resolution | Backlog | `:15`/`:29`/`:51` name `ServiceCatalog.reflectServiceMethod` (the `Class.forName(className)` site) and "the three/four reflect* sites" as the live edit targets. The `Class.forName` load now lives in `decodeServiceMethod`; `reflectTableMethod`/`reflectExternalField` survive but `reflectServiceMethod` is gone. | **Re-anchor** the class-load site to `decodeServiceMethod` and re-enumerate the reflect* sites. Goal (short class-name resolution) intact. Sequence with R72 as its body already notes. |

### C.11 `FilterBinding` reshape drift (carried; R57)

R57 replaced the `liftedSourceColumns` slot on the two reference carriers
(`InputField.ColumnBackedReferenceField`, `ArgumentRef.ScalarArg.ColumnBackedArg`) with the sealed
`FilterBinding` (`Local(List<ColumnRef> ownTableColumns)` / payload-free `Remote`). The identically
named component on the resolver's `JoinPath` and `Resolved.FkTarget.DirectFk` **survives**, so only
the downstream carrier accessor drifted.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R135** multi-hop-nodeid-fk-permutation-test | Backlog | `:17` pins "the composite `InputField.ColumnBackedReferenceField.liftedSourceColumns()` ends in `[k1, k2]` order"; `:23` says "the existing `BodyParam.{RowEq,RowIn}` emission consumes `liftedSourceColumns` positionally". The carrier accessor is gone; the tuple is now `FilterBinding.Local(ownTableColumns)`. The `:13`/`:21` resolver-side cites are **current**. | **Re-anchor** the `:17` and `:23` carrier cites onto `FilterBinding.Local`. The test-plan goal (multi-hop permuted terminal hop resolves `DirectFk`, not `TranslatedFk`) is untouched and stays open. |

### C.12 `CatalogFacts` / mcp-tool-surface drift (new this window; R642)

R642 cut graphitron-mcp off the generator: it deleted the `CatalogFacts` class (`grep`=0 in main
real code), dropped the `edges` mcp tool, collapsed `services`/`conditions`/`records` into one `code`
tool, and moved mcp's reads off the taxonomy types. The two items below name a retired symbol as live
current state; both keep their work valid and need only a repoint. (R638, In Progress, carries the same
`CatalogFacts` drift as an internal contradiction; it is the live edit zone and already half-aware, so
it is noted in cross-cutting rather than flagged.)

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R684** consumers-share-relations-not-queries | Ready | `:94-96` asserts present-tense that "`graphitron-lsp` and `graphitron-mcp` both import generator types from `graphitron` today (`LspSchemaSnapshot`, `CatalogFacts`, `CompletionData`, `FieldClassification`, `TypeBackingShape` ...)". Post-R642 both halves are false: `graphitron-mcp` compiles against `graphitron-model` + jOOQ only, and `CatalogFacts` is deleted. The rule the item states is unaffected. | **Re-anchor** the example to `graphitron-lsp` only and drop `CatalogFacts`. A **Ready** item; refresh before pickup. |
| **R594** mcp-snapshot-axis-key-naming | Backlog | `:18` premise "Four MCP tools report the live snapshot's availability ... `diagnostics` and `edges` call it." R642 dropped the `edges` tool, so the "four tools" count and the "`edges` call[s] it" claim are wrong. `McpWire.writeSnapshotAxes`, `SchemaView`, `GraphitronMcpServer.statusResult`, `snapshotAvailability` survive, so the key-spelling cleanup goal survives. | **Re-anchor** the caller set against the post-R642 tool surface and drop `edges`. |

### C.13 Routine read-surface deferral removal drift (new this window; R704)

R704 removed the `@routine` read-surface carve-outs (`@orderBy`/`@condition` deferral, `@asConnection`
rejection) and built the pagination arm. The item below states two of those removed deferrals as
current fact.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R662** routine-chain-ordering-spans-nodes | Backlog | The "Notes for whoever picks this up" bullets state as current fact that "`@orderBy` is deferred on routine-backed fields today" and "`@asConnection` over a routine chain is rejected or deferred today". R704 removed both deferrals and built the pagination arm. R704 repointed R662's front-matter `depends-on` to `[]` in the same commit, but the body's closing "Depends on R704 (`roadmap/routine-composition-surface-from-facts.md`)" line still names R704's deleted item-file path (a dangling xref of the kind R596 tracks). | **Re-anchor** the two Notes bullets onto R704's shipped read surface. The core multi-node-ordering premise survives. Repoint the dangling `routine-composition-surface-from-facts.md` path onto R704's changelog id. |

## D. Structural: (0)

Empty of blocking defects. `changelog.md` carries `next-id: R721`, clearing the max allocated id
(R720). No duplicate `id:`, no `status: Done` tombstones in `roadmap/*.md`, and the twelve non-empty
`depends-on:` edges resolve to present files (R7/R622/R659's discards and R704/R642/R650/R488's Done
transitions left no dangling edge). The roadmap-tool regenerates `README.md` with **no drift** at this
HEAD. The seventeen items filed this window carry well-formed front-matter and read born-current, with
the one exception (R709) flagged in §B.11.

One **pre-existing, non-blocking** hygiene note, surviving unchanged:

1. Four **legacy** items still lack a `bucket:` key: R242 (`dml-payload-positional-alignment`),
   R109 (`list-valued-external-field-multiset`), R252 (`multi-file-federation-fixture-coverage`),
   R180 (`record-parent-column-read-helper`). Build tolerates the omission; fold a `bucket:` in
   whenever each is next edited.

## Cross-cutting observations

1. **A two-big-Done window stales more than a symbol-retiring one, and it stales by premise, not by
   identifier.** R704 and R642 each shipped an architecture (the routine read surface; the mcp/store
   cut), and the six items they staled mostly do not name a retired identifier at all: they state a
   removed deferral, a dropped tool, or a delivered capture as current fact. Only R76 and R684 name a
   dead symbol. The audit-relevant lesson is that an identifier grep alone would have caught two of the
   six; the other four needed the premise read. **R76 is the clean example**: it spells the retired
   mechanism `leftJoin` / "cross-table joins", never "LEFT JOIN", so the prior audit's phrase grep
   passed it while the mechanism underneath had been converted to a subselect.

2. **R193 and R213 are the two overdue subsumption candidates, and neither moved.** R193 asked for the
   sealed parameter classifier that R649's `reduceClaims`/`ParamRole` shipped; R213 holds the same shape
   against R585/R589. Running both re-checks, and most likely closing both as subsumed, is the cheapest
   board-cleaning available; deferring only lets the stale prose keep misleading. **R709 now joins them**
   as a same-window subsumption: its `sql_routine` capture shipped in R704 before R709 was even picked up.

3. **The fact-model / routine / lsp clusters are the live edit zone, and their symbol churn is correctly
   not flagged, with one contradiction worth naming.** R680, R638, R347 (In Progress), R693, R323
   (In Review), and the Spec-tier fact-model / routine items reshape live relations as they draft;
   their internal churn is not board staleness. The one blemish is **R638** (`lsp-reads-the-fact-store`,
   In Progress): its `:578` correctly records that R642 shipped and took `CatalogFacts` with it, while
   `:69` and `:296` still list `CatalogFacts` as a live import and a "what retires" target. That is an
   internal contradiction the implementer will hit and reconcile as the item lands; it is not flagged as
   board staleness because the item is actively driven and already half-aware.

4. **Prefix greps still misjudge the retired service and mutation symbols.** A bare `grep` for
   `PkLessParent` returns the surviving `SourcesOnPkLessParent` error arm; one for `resolveInput`
   returns the unrelated `RecordBindingResolver.resolveInput`; one for `Operation` returns comments and
   a string-literal fact key; one for `liftedSourceColumns` returns the surviving resolver-side
   component; one for `CatalogFacts` returns only comments and guard strings. Every flag here keys on the
   fully-qualified member, not the prefix, and each stands.

5. **The Ready set is where stale prose bites soonest, and it grew this window.** The Ready set is now
   R333, R427, R467, R555, R684, R710. R333 (§C.5/§C.7), R427 (§C.0), R555 (§C.10) carry stale cites, and
   **R684 (§C.12) is a fresh entrant** carrying the `CatalogFacts` mcp example. R467
   (`upgrade-graphql-java-26`) and R710 (`jooq-node-metadata-as-stated-facts`, born-current) are clean.
   **Refreshing R333, R427, R555, and R684 before pickup remains the highest-value hygiene action on the
   board.**

---

_Review date: 2026-08-19._

