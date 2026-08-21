# Roadmap staleness audit: 2026-08-21

A point-in-time review of every active roadmap item under [`roadmap/`](../) against the
**current** state of the codebase on `claude/graphitron-rewrite` (HEAD `e9dc149`, committed
2026-08-20 22:18, audited 2026-08-21). The goal is to find items whose premise no longer holds:
work already shipped, constructs renamed or removed, dependencies that have since landed or been
discarded, or specs grown stale enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a subdirectory so the
roadmap-tool (which scans `roadmap/*.md` non-recursively and requires `id:` front-matter on each)
ignores it, and it is Markdown so the `check-adoc-tables` build step (which scans `.adoc` only)
leaves it alone.

This audit supersedes the `2026-08-20` staleness audit, which has been deleted; only the latest
**staleness** audit is retained. The other fifteen files in this directory are **not** staleness
audits and are left in place (deleting them would strand lineage that shipped items and active
items cite by path, and they are provenance I did not author): `2026-06-16-source-operation-target-reframe.md`,
`2026-06-30-release-planning.md`, `2026-07-04-r222-r333-conformance-analysis.md`,
`2026-07-26-fcis-command-layer-distance.md`, `2026-08-05-fact-base-h2-spike.md`,
`2026-08-05-h2-functions-jooq-spike.md`, `2026-08-06-demand-exemption-census.md`,
`2026-08-06-directive-consumer-census.md`, `2026-08-06-fact-base-impact-sweep.md`,
`2026-08-06-graphql-java-diff-spike.md`, `2026-08-06-r222-lineage.md`,
`2026-08-06-structural-classifier-census.md`, `2026-08-19-github-issue-roadmap-linkage.md`,
`2026-08-20-nodeid-relation-impact-sweep.md` (the `@nodeId`-relation architecture-drift companion
to the prior audit, whose findings this audit takes as read for the nodeId cluster), and
`classification-test-dsl-inventory.md`.

## Headline: three Done items staled no premise, one flagged item was already discarded at the prior audit's commit, and the two edited flags now carry their own re-anchor notes

The window since the prior audit's commit `72ee865` (2026-08-20 17:20) to HEAD `e9dc149`
(2026-08-20 22:18) is short: **three items reached Done (R746, R759, R760) and none staled an
active item's premise.** The prior audit's own lesson holds again: a Done item stales the board
only by deleting a construct active items name, and each of this window's three retired only
test-internal, storage-internal, or unread constructs.

- **R759 (`store-boot-compiles-java`, Done)** took the wire spelling out of storage and deleted the
  `CREATE ALIAS` that carried inline Java source (so no store boot compiles Java). Retired
  `ValidationReport.sourceUris`, `addCanonical`, `canonicalUri`. Re-verified `grep`=0 in main; **no
  active item cites any of them.** The two `CREATE ALIAS` mentions surviving in `roadmap/` are
  generic H2-capability prose in the decode spike (`graphitron-decodes-read-rows-not-ast.md`) and a
  provenance line in R733, neither a live cite. Residue filed as **R768**.
- **R760 (`delete-assignability-closure`, filed and Done in-window)** deleted `intent_class_assignable`
  / `INTENT_CLASS_ASSIGNABLE` (an all-pairs transitive closure with no production reader that did not
  terminate on a real census) and deduplicated `intent_authored_field_claim`'s `lookup_bearing`
  recursion. Re-verified `grep`=0 in main. **It broke no premise**, but its deletion leaves one new
  reference-drift row: **R762** (new this window) names `intent_class_assignable` in the present tense
  as the consumer of `jvm_class_supertype` (new §C.15). R762 was filed knowing of R760 (it cites it),
  so the deletion strengthens R762's own argument rather than falsifying it; only the tense drifted.
- **R746 (`materialization-dependency-order`, In Review -> Done)** derived the materialization refresh
  order from the store's own catalog (`meta_materialize_dependency`, `MaterializeDependencies.populate`,
  `Materializations.refreshOrder`). Retired the test-internal `theRegistryNeedsNoOrderingYet` and
  `MaterializeRegistryGateTest.closureOf`; **no live item cites either.** Residue filed as **R761**.

**One prior-audit flag was a phantom at that audit's own commit: R714.** The 2026-08-20 audit
introduced **R714** (`assembled-schema-owns-the-sdl-census`) as a *new* §C.14 flag, but R714 was
**already discarded** (absorbed into R743) before that audit's commit `72ee865`. Its file is gone
and no id `R714` resolves on this branch. This repeats the prior audit's own R709 pattern: it was
authored against a lagging baseline (`7c07683`, 2026-08-19) and shipped stale about items that had
already moved by the time it committed. Two more of its statements were stale the same way:
**R668** (`nodeid-effective-at-every-coordinate` argMapping work) was recorded In Review but had
already reached **Done**, and its **board figures** (251 files, next-id R742) trailed the true
board at its commit (next-id R760). **R714 leaves the flag set.**

**The two flags that were edited this window carry their fix inline now.** **R34** (§B) and **R135**
(§C.11) each gained a dated re-anchor addendum this window, keyed to Findings 3 and 4 of the
`2026-08-20-nodeid-relation-impact-sweep.md` companion. The addenda record the re-derivation target
(for R34, the `intent_node_id_instruction.basis` relation; for R135, `FilterBinding.Local` and the
`validateLift`-stops-being-a-rejection reframing) but do **not** rewrite the stale body prose, so
both flags persist at their sections; they are simply now self-documenting.

Net: **1 §A / 12 §B / 29 §C / 0 §D**, flag total **42**, unchanged in count from the prior audit but
changed in composition. **Leaving:** R714 (discarded before the prior audit's commit). **Entering:**
R762 (§C.15, R760's `intent_class_assignable` deletion). Every other carried flag holds at its prior
line anchors (none but R34 and R135 was edited this window), and every long-standing retired symbol
re-verifies `grep`=0 at this HEAD.

## Changes since the 2026-08-20 audit

Measured from the prior audit's commit `72ee865` to HEAD `e9dc149` (49 commits), because the prior
audit's stated baseline `7c07683` predates its own commit and its figures were already stale there.

**Items that reached Done, and what each did to the symbol set:**

- **R746 (`materialization-dependency-order`, In Review -> Done):** refresh order derived from the
  store catalog; retired `theRegistryNeedsNoOrderingYet` / `closureOf` (test-only). Residue R761.
- **R759 (`store-boot-compiles-java`, Backlog -> Done):** URI spelling out of storage, `CREATE ALIAS`
  Java body deleted; retired `sourceUris` / `addCanonical` / `canonicalUri`. Residue R768.
- **R760 (`delete-assignability-closure`, filed and Done in-window):** `intent_class_assignable`
  deleted, `lookup_bearing` recursion deduplicated. No file added or removed (born and closed inside
  the window).

**Discards:** none in this window. (R714 and R666 were discarded *before* the prior audit's commit;
the prior audit missed R714's discard and flagged it anyway.)

**Items filed this window (R760 -> R768; R761-R768 have live files, all Backlog except R763):**
R761 (`materialize-dependency-derived-before-stamp`, R746 residue), R762 (`census-stores-members-it-reads-by-name`),
R763 (`sakila-example-tests-run-one-at-a-time`, **Spec**), R764 (`model-test-jar-leaks-parallelism-config`),
R765 (`expression-keyed-joins-into-derived-relations`), R766 (`sakila-example-generate-executions-serialize`),
R767 (`maven-plugin-descriptor-runs-twice`), R768 (`store-boots-once-per-test-not-once-per-build`, R759 residue).
All read born-current against the live relations and are correctly **not** flagged.

**Transitions not already listed:** R682 (`planners-read-facts-emitters-read-commands`) Spec -> In
Progress (slice one picked up); R763 Backlog -> Spec. **R152 was re-scoped in place** (see below).

**R152 re-scoped, not flagged.** `lsp-nodetype-hover-column-scoping` (R152, Backlog) was rewritten
this window from the original column-scoping bug (whose fix landed with the LSP's move to the fact
store, `Hovers.nodeColumns` now reading the node's own `graphitron_table` binding) down to the one
residual it still wants: a hover test with two tables sharing a column name with diverging
`graphqlType`. This matches Finding 2 of the nodeId sweep, which called the original obsolete. The
re-scoped item is coherent and born-current; **it is not a staleness flag.** Discard remains the
reasonable alternative if the pin is judged not worth an item.

**Board accounting.** **266** item files today (measured, excluding `README.md` and `changelog.md`).
Status distribution: **225 Backlog, 27 Spec, 8 Ready, 2 In Progress, 4 In Review, 0 Done**.
Tombstone-free (`grep` for `status: Done` in `roadmap/*.md` = 0). No duplicate `id:`; `changelog.md`
carries `next-id: R769`, clearing the max present id (R768). A `depends-on:` sweep resolves all
**six** non-empty edges to present files (down from seven; a Done transition cleared one). The
roadmap-tool **regenerates `README.md` with no drift** at this HEAD (re-run and confirmed clean). The
only structural nits are the same four **legacy** items still missing a `bucket:` key (§D), all
pre-dating this window.

## Scope and method

All **266** `R<n>` item files were reviewed, and every flagged item's file was confirmed present on
this branch (R714 is the one prior-audit flag that does **not** resolve; it was discarded). Every
driving symbol below was re-checked against a fresh `grep` of the main sources (`graphitron`,
`graphitron-mcp`, `graphitron-lsp`, `graphitron-model`, `graphitron-maven-plugin`,
`graphitron-fixtures-codegen`), not carried on the prior audit's word.

**This window's retirements, re-verified `grep`=0 in main real code at this HEAD:**
`ValidationReport.sourceUris` / `addCanonical` / `canonicalUri` (R759); `intent_class_assignable` /
`INTENT_CLASS_ASSIGNABLE` (R760); `theRegistryNeedsNoOrderingYet` / `MaterializeRegistryGateTest.closureOf`
(R746). Their successors are live: `SourceUri.ofDirectory` / `DiagnosticFacts`, `meta_materialize_dependency`
/ `Materializations.refreshOrder`.

**Long-standing retirements, re-verified still retired at this HEAD (`grep`=0 in main real code):**
`LspSchemaSnapshot`, `FieldClassification` (catalog-package), `CatalogFacts`, `reflectServiceMethod`,
`TypeClassGenerator`, `TypeConditionsGenerator`, `QueryConditionsGenerator`, `LookupValuesJoinEmitter`,
`InlineTableFieldEmitter`, `CompileDependencyGraphBuilder`, `buildNonTableInputType`, `RowsMethodBody`,
`ParentProjectionContainmentCheck`, the `Operation` seal arms (successor `OperationMember`, 201 live
refs), and `AUTHOR_ERROR` `grep`=0 in `FieldRegistry.java` (the R209 target; the 15 surviving
`AUTHOR_ERROR` hits are the general `RejectionKind` enum in `RejectionKind` / `BuildContext` /
`FieldBuilder` / the SQL, not the FieldRegistry default arm). **Still live, not stale:** `ColumnBackedField`
(108), `BatchedTableField` (80), `FilterBinding` (79), `ConditionGlueRenderer`, `ProjectionUnitRenderer`,
`FkTargetConditionFilter`, and the reflection reader `JooqCatalog.nodeIdMetadata` (12; the R615/R273/R34/R588
watch chain still holds it).

**R430 sharpened (still §B, not discardable).** The item's premise-break holds: the LSP `Workspace`
diagnostics surface it names (`CompileDiagnostic`, `Workspace.compileDiagnostics()`, `setCompileDiagnostics`)
is `grep`=0 in `graphitron-lsp` main. But R430's own first sub-question, "is the incremental-compile
diagnostic stream still collected anywhere," now has a concrete affirmative: a `CompileDiagnostic`
type lives in `graphitron` core (`rewrite/compile/CompileDiagnostic.java`, with `CompileFacts` /
`IncrementalCompileEngine` / `CompileRound`) and in the maven plugin's `CompileErrorFormatter`. So the
re-spec re-anchors the publish trigger onto that surviving stream rather than discarding.

## A. Obsolete: should leave the active roadmap (1)

| Item | Status | Why obsolete | Recommended action |
|---|---|---|---|
| **R209** field-registry-typed-rejection-trace | Backlog | **Carried; still Backlog, still fully delivered.** The entire deliverable ("remove the `RejectionKind.AUTHOR_ERROR` default arm and emit `RejectionKind.of(rejection)` consistently with `traceOutput`") is shipped by **R585**. Re-verified this window: `AUTHOR_ERROR` `grep`=0 in `FieldRegistry.java`. Nothing remains to do; not touched this window. | **Discard**, recording R585 as the delivery vehicle. |

R520 (`table-on-input-removal-housekeeping`) stays a coherent deliberately-deferred docs tail, not obsolete.

## B. Outdated: needs re-spec (premise or targets materially changed) (12)

All twelve carry forward. Two were edited this window (R34, R135; each gained a re-anchor addendum
but kept its stale body, so the flag persists); the other ten were not touched and re-verified at the
symbol with the premise-target still `grep`=0. **R193 and R213 remain the two overdue subsumption
candidates** (R649 / R585 / R589 shipped what they scoped); running both re-checks, most likely
closing both as subsumed, is the cheapest board-cleaning available.

| Item | Status | What changed | Recommended action |
|---|---|---|---|
| **R193** service-param-classification-sealed-hierarchy | Backlog | **R649 shipped the deliverable.** `reflectServiceMethod` / `looksLikeSourcesShape` `grep`=0; `reduceClaims` mints one sealed `ParamRole` per parameter. | **Re-derive the residue against shipped R649; most likely close as subsumed**, recording R649 as the delivery vehicle if nothing survives. |
| **R72** slim-servicecatalog-to-lookup | Backlog | **Premise materially changed by R649.** R649 dissolved `reflectServiceMethod` (`grep`=0) into `decodeServiceMethod` / `reduceClaims` / `bindServiceMethod`; `reflectTableMethod` / `reflectExternalField` survive. | **Re-spec** against the post-R649 split; drop every stale `ServiceCatalog.java:NNN` line cite. |
| **R213** input-field-rejection-attribution | Backlog | **Escalated, still unactioned.** `InputFieldResolution.Unresolved` now carries a `SourceLocation`; R589's occurrence-path derivation (Done) delivers the attribution split. | **Re-derive the residue against shipped R585 + R589; most likely close as subsumed.** |
| **R66** rejection-string-carrier-widening | Backlog | Phase **A2** was verbatim what **R585** shipped (`Unresolved` carries `Rejection rejection`, not `reason:String`). Phases A1, A3, B1, B2 survive. | **Re-spec:** strike A2 as delivered; re-baseline the four surviving phases; fix the `:25-30` anchors. |
| **R34** nodeid-migration-quickfix | Backlog | Carried, self-contradictory. The "shim facts" driver names three WARN sites deleted by R473; **edited this window** with a re-anchor addendum (dated 2026-08-20) confirming the driver is void and naming `intent_node_id_instruction.basis` as the re-derivation target. Body still leads with the void driver. | **Re-spec** onto `intent_node_id_instruction` (`basis` + `node_type_name` + location); retitle off "shim facts". The addendum already records this. |
| **R122** compound-entity-mutations | Backlog | "Design space narrows under R222" leans on R222's discarded recursive `InputUsage` model (R222 left the board 2026-08-06). `ChildField.TableTargetField` (added by this item) is live. | **Re-spec the "narrows under R222" section**; re-express against the captured `intent_` / `applied_` relations. |
| **R462** nested-fetcher-outgoing-field-edges | Spec | Central target `CompileDependencyGraphBuilder.addFieldEdges` deleted (`grep`=0); the body's own dissolution condition has occurred. Cites absent `FkTargetConditionEmitter.emitTerm` and phantom `MutationConditions` as live. | **Re-derive against the plan-projected recompile graph.** If closed, discard, else re-spec; repoint `FkTargetConditionEmitter.emitTerm` -> `FkTargetConditionFilter` and drop `MutationConditions`. |
| **R545** model-free-of-emit-vocabulary | Backlog | `RowsMethodBody` / `RowsMethodSkeleton` deleted (diagnosis + second deliverable gone); first deliverable survives (`ClassName` / `TypeName` model-pervasive). The `:18` counter-argument names R638-deleted `FieldClassification` / `TypeClassification`. | **Re-spec.** Drop the `RowsMethodBody` diagnosis; keep the first deliverable; update the `:18` counter-argument to the current `rewrite/catalog/` contents. |
| **R85** helper-emission-non-fetcher-hosts | Backlog | Both named edit-target generators deleted: `QueryConditionsGenerator` (R552), `TypeClassGenerator` (R549). Successors `ConditionGlueRenderer` / `ProjectionUnitRenderer` live. Names absent `Inline*Emitter` host files. | **Re-derive against the new `render/` layer**; drop every dead cite. |
| **R71** recordn-key-parity-lifter-and-non-jooq-record-parents | Backlog | R431 (Done) deleted the target surface: `SourceKey` is a plain `record` with no `Reader`. Successors `KeyLift` / `LifterRef` / sealed `Wrap` live; the "R431 ... plans to" tense is wrong (R431 is Done). | **Re-spec the current-state / approach section** against the decomposed model; fix the tense. |
| **R76** participant-fieldsjoin-helpers | Backlog | R650 converted cross-table participant fields to capped correlated subselects; the surviving `step.leftJoin(...)` is the joined-detail join only, so the cross-table `$fieldsJoin` framing is obsolete. | **Re-spec** onto the joined-detail `LEFT JOIN`, or discard if R650's subselect conversion removed the motivating duplication. |
| **R430** lsp-compile-diagnostics-publish | Backlog | **Premise broken by R638 (Done).** `CompileDiagnostic` / `Workspace.compileDiagnostics()` / `setCompileDiagnostics` are `grep`=0 in `graphitron-lsp` main; R638 moved LSP diagnostics onto the capture cadence. `publishDiagnostics` survives. **This window's re-check answers its open question:** the compile-diagnostic stream still lives in `graphitron` core (`CompileFacts` / `IncrementalCompileEngine` / `CompileDiagnostic`). | **Re-derive current state against the fact-store LSP**; re-anchor the publish trigger onto the surviving `graphitron`-core compile stream and keep the publish-against-generated-URI goal. Discard only if that stream is later retired. |

## C. Outdated: update references only (work valid, refs stale) (29)

Substance intact; names and line numbers drifted. Twenty-eight carry forward (R135 edited this
window; the rest untouched, every long-standing driving symbol re-verified still `grep`=0), **R714
leaves** (discarded, §C.14 below), and **one is new this window: R762** (§C.15).

### C.0 `Operation` seal fully retired (carried; R563 slice 7)

`Operation` and every `Operation.<Arm>` reference are `grep`=0. Successor: `OperationMember` (201 refs).

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R427** relevance-ranked-search | Ready | `:335` "`operation()` stays `Fetch`"; `:339`/`:1162` cite `Operation.Facet` as a live type. | **Re-anchor** onto the member-derived summary fold; repoint `Operation.Facet` -> `OperationMember.Facet`. A **Ready** item; refresh before pickup. |
| **R382** multitable-interface-query-orderby-lowering | Backlog | `:19` cites `operation()` as the live mechanism hardcoding `new OrderBySpec.None()`. | **Re-anchor** to where the hardcoded `OrderBySpec.None` now lives; verify the ordering gap reproduces. |
| **R562** synthesised-connection-fields-as-coordinates | Backlog | `:17-18` name `Operation.Count` / `Operation.Facet`; all retired. | **Re-anchor** to `OperationMember.Count` / `Facet`. Model question intact. |

### C.1 Lookup-triplet dissolution drift (carried; R563 slice 6a)

A lookup leaf re-anchors to `BatchedTableField` (or `TableField` / `QueryTableField`) **plus a lookup member**.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R533** localcontext-guard-predicate-single-source | Backlog | `:15` names `BatchedLookupTableField` as the open edge; `:19` self-corrects but the lead is stale. | **Re-anchor** the `:15` lead to the post-dissolution sibling. |
| **R557** split-query-marker-sweep | Backlog | `:17`'s "consumed (the batched leaves)" lists `BatchedLookupTableField`; `:17` also anchors on the R638-deleted `CatalogBuilder.projectFieldClassification` seam. | **Re-anchor**: drop `BatchedLookupTableField`; repoint the "compile-checked-projection seam" onto the surviving leaf-classification mechanism (`GraphitronSchemaBuilder` / fact store). |

### C.2 `@table`-on-input rejection -> deprecation drift (carried; R566)

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R565** unclassified-input-arg-cascade-diagnostic | Backlog | Title (`:3`, `:13`) still leads with the retired `@table`-on-input **rejection**; the body already frames it against the current state. | **Re-anchor (not full re-spec).** Retitle/re-lead onto a still-current rejection; demote `@table`-on-input to historical framing. |

### C.3 `planSlug` / `SourceKey.Reader` removal drift (carried)

R484 removed `Rejection.Deferred.planSlug`; R431 removed the `SourceKey.Reader` interface.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R454** routine-write-result-shapes | Backlog | `:18` names deferred shapes as "typed `Deferred`s pointing at this item's planSlug"; gone. | **Re-anchor:** deferred shapes surface via `StubKey.VariantClass`. |
| **R447** routine-chain-fetch-form-breadth | Backlog | `:18`/`:33` "`planSlug` points here"; `:24`/`:26` name `SplitLookupTableField` / `RecordTableField`; `:23` names absent `InlineTableFieldEmitter`. Parked pagination note softly overlaps R704. | **Re-anchor:** drop `planSlug`; repoint to `BatchedTableField` (+ lookup member); drop `InlineTableFieldEmitter`. **At Spec, check** R704 subsumption. |
| **R180** record-parent-column-read-helper | Spec | `:35` "R431 ... now In Progress" (R431 is **Done**) and names `SourceKey.Reader.AccessorCall` as live. | **Re-anchor** the `AccessorCall` carrier onto the decomposed model; fix the R431 tense. |
| **R505** tenant-index-parent-row-routing | Backlog | `:21` names "a column read off the parent row (the `SourceKey.Reader` family)". Live via `KeyLift.FkColumns`. | **Re-anchor** the one parenthetical. |

### C.4 Leaf-merge drift: `Split*` / `Record*` -> `Batched*` (carried)

`SplitRowsMethodEmitter` is **not** renamed and is correct wherever it appears.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R109** list-valued-external-field-multiset | Spec | `:51`'s planned enum arm asserts "`RecordTableField` with `BatchKey.AccessorKeyedMany`". | **Re-anchor** to `BatchedTableField`. |
| **R242** dml-payload-positional-alignment | Spec | `:37-38` R305 lineage "collapsed it into `RecordTableField`"; `:107`/`:148`/`:150`/`:284` cite `LookupValuesJoinEmitter`. | **Re-anchor** to `BatchedTableField`; repoint `LookupValuesJoinEmitter` to the render values-join family. |
| **R288** inline-interface-and-tablemethod-children | Backlog | `:33-34` name `SplitTableField` / `RecordTableField`; `:24` cites absent `InlineTableFieldEmitter`. | **Re-anchor** the two variant names to `BatchedTableField`; drop the emitter cite. |
| **R116** composite-key-row2-source-row-coverage | Backlog | `:15`'s planned case "classifies as `RecordTableField` with a `LifterPathKeyed`". | **Re-anchor** to `BatchedTableField`. |

### C.5 `ColumnBackedField` dissolution drift (carried; R508)

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R333** coordinate-lowers-to-datafetcher-queryparts | Ready | Retired carriers named live across `:570`, `:694`, `:706`, `:727`, `:747-748`, `:798-799`, `:1112`, `:1140`, `:1145`, `:2009`. Part of the one R333 refresh (§C.7). | **Re-anchor** the carrier names to `ColumnBackedField`. Part of the one R333 pass. |

### C.6 `TableInputType` / `resolveInput` removal drift, plus the `UnboundField` reshape (carried; R519 + R515; R589)

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R234** jooq-embedded-and-udt-input-backings | Backlog | `:15` cites `TypeBuilder.buildNonTableInputType` (`grep`=0) as the live dispatch. | **Re-anchor** to the current `TypeBuilder` input-classification path (`buildInputType`). |
| **R257** updaterows-walker-sdl-substrate | Backlog | `:17` calls `resolveInput` "the legacy resolver" (gone R515); `:15`/`:19` reach via `TableInputType.inputFields()` (gone R519). | **Re-anchor** both: `resolveInput` -> `admitMutationInputFields`, `TableInputType.inputFields()` -> per-consumer input resolution. |
| **R337** input-nesting-projection-classification | Backlog | `:30` reaches an input object's fields "via `TableInputType.inputFields()`" as the LSP-hover mechanism. | **Re-anchor** the one live mechanism cite to per-consumer input resolution. |
| **R245** wire-condition-emit-on-mutations | Backlog | `:76` locates the `@condition` slot in `MutationInputResolver.resolveInput` (gone R515); `:124` names the pre-R589 `UnboundField` carrier. | **Re-anchor** `:76` to `admitMutationInputFields`, `:124` to `ConditionOwnedField`. |

### C.7 Condition + projection emitter dissolution drift (carried; R552 + R549)

Condition emission is `render/ConditionGlueRenderer`; projection `render/ProjectionUnitRenderer`.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R333** coordinate-lowers-to-datafetcher-queryparts | Ready | **Refresh still incomplete; not touched this window.** Still at the symbol: 5x `TypeClassGenerator`, 5x `collectRequiredProjection`, 5x `methodgraph`, `LookupValuesJoinEmitter`, 2x `ParentProjectionContainmentCheck`, 1x `TypeConditionsGenerator`, 4x `InlineTableFieldEmitter`, plus the §C.0/§C.5 carriers and §C.9 absent-emitter names. A pure implementation plan whose class names are wrong, and **Ready**, so it bites the next implementer. | **Finish the refresh in one pass** (this row + §C.0 + §C.5 + §C.6 + §C.1 + §C.9): repoint `TypeClassGenerator` / `collectRequiredProjection` -> `ProjectionUnitRenderer` / `ProjectionCommands`; drop `TypeConditionsGenerator` / `ParentProjectionContainmentCheck` / `methodgraph` / `operation()`; re-anchor `LookupValuesJoinEmitter` and the `Operation` seal cites; replace `Inline*Emitter` names and `FkTargetConditionEmitter` -> `FkTargetConditionFilter`. `SplitRowsMethodEmitter` rows stay (live). |
| **R231** emit-text-mapped-enum-fields-as-enum-type | Backlog | `:39` locates "the field-type-emit fork (likely in `TypeClassGenerator` or ...)". | **Re-anchor** the investigation locus to `ProjectionUnitRenderer` / `plan/ProjectionCommands`. |
| **R35** source-orientation-javadocs | Backlog | `:42` enumerates classes for a javadoc sweep, including deleted `TypeClassGenerator` and `TypeConditionsGenerator`. | **Re-anchor** the enumeration: drop the two deleted names. Low priority. |

### C.8 `InputFieldResolution.Unresolved` reshape drift (empty; R585)

R585 reshaped `Unresolved` to `(fieldName, SourceLocation, Rejection)`. Its residue lives in R66, R213 (§B) and R209 (§A). **No item remains in this subsection.**

### C.9 Absent projection/condition `*Emitter` names (carried; render-layer refactor)

A family of per-arm emitter names several items cite as **live** current-state, all **`grep`=0** at
this HEAD. Their work lives in `ProjectionUnitRenderer` / `ProjectionCommands`, `ConditionGlueRenderer`
/ `ConditionCommands`, and `FkTargetConditionFilter`. **No distinct flagged item**; every citer is
listed above.

| Absent name | Cited-as-live in | Live successor |
|---|---|---|
| `InlineColumnReferenceFieldEmitter` | R333 (`:1890`) | render projection layer |
| `InlineTableFieldEmitter` | R333 (`:1752`,`:1891`), R85 (`:20`,`:45`), R447 (`:23`), R288 (`:24`) | render projection layer |
| `InlineLookupTableFieldEmitter` | R333 (`:1891`), R85 (`:21`,`:46`) | render projection layer |
| `FkTargetConditionEmitter` | R333 (`:1893`), R462 (`:45`, `.emitTerm`) | `FkTargetConditionFilter` via `ConditionCommands` |
| `MutationConditions` (phantom) | R462 (`:57`) | none; drop the shim name |

### C.10 `reflectServiceMethod` / `PkLessParent` / `validateRootInvariants` removal drift (carried; R649)

R649's phase split retired `ServiceCatalog.reflectServiceMethod` (now `decodeServiceMethod` +
`reduceClaims` + `bindServiceMethod`), `ServiceCatalog.PkLessParent`, and `validateRootInvariants`.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R555** deprecate-externalfield-fold-into-service | Ready | Four present-tense cites of `reflectServiceMethod` (`:41`, `:52`, `:103`, `:106`); `:226-227`/`:400`/`:405` describe hover switching on R638-deleted `FieldClassification.Computed` vs `.ServiceBacked`; `:352`/`:491` are javadoc-respell tasks on `FieldClassification.Computed`. | **Re-anchor.** Repoint `reflectServiceMethod` -> `decodeServiceMethod` / `ServiceSignature`; re-anchor Deliverable 4's hover onto `ChildField.ComputedField` (read from the fact store); drop the moot `FieldClassification.Computed` respell tasks. A **Ready** item; refresh before pickup. |
| **R47** service-short-classname-resolution | Backlog | `:15`/`:29`/`:51` name `ServiceCatalog.reflectServiceMethod` and "the three/four reflect* sites" as live edit targets. The load now lives in `decodeServiceMethod`. | **Re-anchor** the class-load site to `decodeServiceMethod` and re-enumerate the reflect* sites. Sequence with R72. |

### C.11 `FilterBinding` reshape drift (carried; R57; edited this window)

R57 replaced the `liftedSourceColumns` slot on the two reference carriers with the sealed
`FilterBinding` (`Local(List<ColumnRef> ownTableColumns)` / payload-free `Remote`). The identically
named component on the resolver's `JoinPath` and `Resolved.FkTarget.DirectFk` **survives**.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R135** multi-hop-nodeid-fk-permutation-test | Backlog | `:17` pins the composite `InputField.ColumnBackedReferenceField.liftedSourceColumns()` ending in `[k1, k2]`; `:23` the `BodyParam.{RowEq,RowIn}` positional consumption. Carrier gone; tuple now `FilterBinding.Local`. **Edited this window** with a dated addendum recording both this re-anchor and a second: `validateLift` stops being a rejection under the `@nodeId` relation move (nodeId sweep Finding 4), so the out-of-scope carve-out goes stale at R728 stage 3. Body still carries the stale cites. | **Re-anchor** the `:17`/`:23` carriers onto `FilterBinding.Local`, and restate the carve-out ("intra-chain permutation now binds remotely rather than rejecting") once R728 stage 3 lands. The test-plan goal is untouched. The addendum records both. |

### C.12 `CatalogFacts` / mcp-tool-surface / `LspSchemaSnapshot` drift (carried; R642 + R638)

R642 cut graphitron-mcp off the generator (`CatalogFacts` `grep`=0, `edges` tool dropped). R638 deleted
`LspSchemaSnapshot` and `FieldClassification` (both `grep`=0). Both items keep their work valid.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R684** consumers-share-relations-not-queries | Ready | `:94-96` asserts `graphitron-lsp` and `graphitron-mcp` both import `LspSchemaSnapshot`, `CatalogFacts`, `CompletionData`, `FieldClassification`, `TypeBackingShape` today. Three of five are dead; the mcp half is doubly false post-R642/R638. Only `CompletionData` / `TypeBackingShape` survive, in `graphitron-lsp` only. | **Re-anchor** the example to `graphitron-lsp` only, keep the two surviving symbols, drop `CatalogFacts` / `LspSchemaSnapshot` / `FieldClassification`. A **Ready** item; refresh before pickup. |
| **R594** mcp-snapshot-axis-key-naming | Backlog | `:16-19` premise names "four MCP tools" (R642 dropped `edges`) and "the same exhaustive switch over the `LspSchemaSnapshot` permits" (R638 deleted the type). Fix surface survives: `McpWire.writeSnapshotAxes`, `snapshotAvailability`, `SchemaView`, `GraphitronMcpServer.statusResult` all live. | **Re-anchor**, not re-spec: drop `edges`, re-count the tools, repoint the switch-source from the `LspSchemaSnapshot` permits onto the fact-store availability/freshness enum. |

### C.13 Routine read-surface deferral removal drift (carried; R704)

R704 removed the `@routine` read-surface carve-outs and built the pagination arm.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R662** routine-chain-ordering-spans-nodes | Backlog | The "Notes for whoever picks this up" bullets state as current fact that "`@orderBy` is deferred on routine-backed fields today" and (`:78`) "`@asConnection` over a routine chain is rejected or deferred today"; R704 removed both. `:84` names R704's deleted item-file path (dangling xref). | **Re-anchor** the two Notes bullets onto R704's shipped read surface; repoint the dangling `routine-composition-surface-from-facts.md` path onto R704's changelog id. Multi-node-ordering premise survives. |

### C.14 `LspSchemaSnapshot` / `FieldClassification` removal drift (carried; R638; one flag left this window)

R638 deleted `LspSchemaSnapshot` and `FieldClassification`. **R714 has left this subsection: it was
discarded (absorbed into R743) before the prior audit's commit, so the prior audit's introduction of
it here was a phantom.** Only R236 remains.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R236** validator-reference-candidate-hint-terminal-table | Backlog | `:27` names the terminal table on the projected `FieldClassification.{ColumnReference,CompositeColumnReference}.tableName()`, and `:31` proposes routing candidate-hint dispatch "through the same `FieldClassification` projection". That projection is deleted (`grep`=0), so design option (a) is no longer buildable. The underlying `BuildContext` candidate-hint bug still stands, and option (b) survives. | **Re-anchor**: repoint the terminal-table source onto the surviving classifier, or adopt option (b). Drop option (a). |

### C.15 `intent_class_assignable` deletion drift (new this window; R760)

R760 (Done, this window) deleted `intent_class_assignable`. One newly-filed item names it in the
present tense; its work is valid and needs only a tense fix.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R762** census-stores-members-it-reads-by-name | Backlog | `:78` "Its only consumer is `intent_class_assignable`, which nothing reads and which does not terminate on a real census (R760)." R760 deleted that view this same window, so the present-tense "is" is stale. R762 cites R760 knowingly, so the deletion **strengthens** its argument (`jvm_class_supertype` now has literally zero consumers), it does not falsify it. | **Re-anchor the tense**: "its only consumer *was* `intent_class_assignable`, deleted by R760; `jvm_class_supertype` now has no consumer at all." The census-depth argument is untouched. |

## D. Structural: (0)

Empty of blocking defects. `changelog.md` carries `next-id: R769`, clearing the max present id
(R768). No duplicate `id:`, no `status: Done` tombstones in `roadmap/*.md`, and the six non-empty
`depends-on:` edges resolve to present files. The roadmap-tool regenerates `README.md` with **no
drift** at this HEAD (re-run and confirmed).

Two **pre-existing, non-blocking** hygiene notes:

1. Four **legacy** items still lack a `bucket:` key: R242 (`dml-payload-positional-alignment`),
   R109 (`list-valued-external-field-multiset`), R252 (`multi-file-federation-fixture-coverage`),
   R180 (`record-parent-column-read-helper`). Build tolerates the omission; fold a `bucket:` in
   whenever each is next edited.
2. **R734, R738 and now R760 leave expected numbering gaps** among the item files: each was allocated
   and either folded away inline or born-and-closed within a single window. Numbers are never reused,
   so the gaps are harmless (`next-id` R769 clears the max present id R768).

## Cross-cutting observations

1. **A Done item stales the board only by deleting a construct active items name, and this window
   none did.** R746, R759, R760 retired only test-internal (`closureOf`, `theRegistryNeedsNoOrderingYet`),
   storage-internal (`sourceUris` / `canonicalUri`), or unread (`intent_class_assignable`) constructs.
   The one drift is R762's own present-tense cite of the relation R760 deleted, and even that
   strengthens R762's argument. Read the premise, not the identifier.

2. **The prior audit was authored on a lagging baseline and shipped stale about three of its own
   claims.** Its baseline `7c07683` (2026-08-19) predates its commit `72ee865`, whose board already
   held next-id R760 (not the audit's stated R742). It recorded **R668 as In Review** (already Done),
   introduced **R714 as a fresh §C.14 flag** (already discarded into R743), and its file/next-id
   figures trailed reality, the same divergent-history failure it diagnosed one window earlier about
   R709. **The lesson stands: before carrying a flag forward, confirm the slug resolves to a present
   file and the status matches the board**, which this audit did (R714 is the one that did not
   resolve, and it was dropped).

3. **R193 and R213 are the two overdue subsumption candidates, and neither moved.** R193 asked for
   the sealed parameter classifier R649's `reduceClaims` / `ParamRole` shipped; R213 holds the same
   shape against R585 / R589. Running both re-checks and most likely closing both as subsumed is the
   cheapest board-cleaning available.

4. **The Ready set turned over again and is where stale prose bites soonest.** It is now R333, R427,
   R555, R663, R684, R724, R730 plus R467 (eight). **R686 left it** (Ready -> In Review this window);
   **R663 entered**. R333 (§C.5/§C.7), R427 (§C.0), R555 (§C.10), and R684 (§C.12) carry stale cites;
   R467 (`upgrade-graphql-java-26`), R663, R724, R730 are clean. **Refreshing R333, R427, R555, and
   R684 before pickup remains the highest-value hygiene action on the board.**

5. **R615 is a watch item, not yet a flag.** `idreffixture-purpose-comment-stale` (R615, Backlog)
   correctly cites the live reflection reader `JooqCatalog.nodeIdMetadata` (12 live refs) and the
   `NodeProvenance.Origin.METADATA` chain, both re-verified live this window. R668 (`@nodeId`
   argMapping work) has reached **Done** but did **not** retire that reflection reader; the reader that
   rewires readers off reflection is still pending. When it lands, re-check R615, R273, R34, and R588,
   whose accuracy depends on the reflection reader surviving.

---

_Review date: 2026-08-21._
