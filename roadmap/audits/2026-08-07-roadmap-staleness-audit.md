# Roadmap staleness audit: 2026-08-07

A point-in-time review of every active roadmap item under [`roadmap/`](../)
against the **current** state of the codebase on `claude/graphitron-rewrite`
(HEAD `453b916`, committed 2026-08-06 22:42, audited 2026-08-07). The goal is
to find items whose premise no longer holds: work already shipped, constructs
renamed or removed, dependencies that have since landed or been discarded, or
specs grown stale enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-08-06` staleness audit, which has been deleted;
only the latest **staleness** audit is retained. The other twelve files in this
directory are **not** staleness audits and are left in place (deleting them would
strand lineage that shipped items and 26 active items cite by path). They are:

- `2026-06-16-source-operation-target-reframe.md`, the permanent lineage document
  for R316 (Done).
- `2026-06-30-release-planning.md`, the first-release scoping working document,
  edited in place as scope iterates.
- `2026-07-04-r222-r333-conformance-analysis.md`, the R222/R333 conformance record.
  R222 was discarded this window (see below); this file is a historical
  conformance snapshot, not a staleness review, and is left in place.
- `2026-07-26-fcis-command-layer-distance.md`, the FCIS command-layer distance
  analysis (symbol-anchored snapshot with a stated re-derivation method).
- `2026-08-05-fact-base-h2-spike.md` and `2026-08-05-h2-functions-jooq-spike.md`,
  the two grounding spikes for the fact-base stack that R595's body cites by path.
- `2026-08-06-fact-base-impact-sweep.md`, the **architecture-drift companion** to
  this audit. It records the whole-board sweep of which items the adopted
  fact-base architecture (R595 substrate, R589 derivation) subsumes, reshapes, or
  consumes. This staleness audit tracks *symbol and reference* drift; that sweep
  tracks *architecture* drift. The two overlap on the items that carry both a stale
  cite and a fact-base note; the overlap is marked in the recommended actions below.
- `2026-08-06-r222-lineage.md`, the absorption ledger and rejected-design record
  preserved when R222 was discarded.
- `2026-08-06-demand-exemption-census.md`, `2026-08-06-directive-consumer-census.md`,
  `2026-08-06-graphql-java-diff-spike.md`, `2026-08-06-structural-classifier-census.md`,
  the four grounding censuses/spikes filed alongside the fact-base work.
- `classification-test-dsl-inventory.md`, the permanent corpus-retirement inventory
  (its "closed and historical" banner is intact).

## Headline: an active window (55 commits, 3 of them main-source), so the drift set moved: R222 and R69 were discarded and R583/R584 ran to Done, retiring the R222 flags from the board; R580's In-Review rework retired `NODE_ID_SHADOWS_COLUMN` / `warnShadowedIdColumn` (cited only by active items, so no new flag from it); a board-wide fact-base sweep annotated 26 items; and one genuinely new stale cite surfaced, R122 leaning on the discarded R222

Unlike the prior (frozen) window, main source moved this window: **3 of the 55
commits** touched `graphitron`/`graphitron-mcp` main and test sources. The change
they make to the drift set is small and clean:

- **The board lost four items.** R583 (`nodeid-target-keys-typeid-axis-coverage`)
  and R584 (`mcp-server-instruction-routing`) ran to **Done** (independent
  approval); R222 (`dimensional-model-pivot`) and R69 (`experimental-construct-type`)
  were **Discarded** by user decision. None of the four sat in §A. R222 was the big
  one: it carried the §C.6 `dimensional-model-pivot` row and threaded through the
  R333 refresh, so discarding it **removes those flags from the board** and their
  residues were re-homed (unified diagnostic stream → R601, directive-location
  narrowing → R602, Stage 5/6 deletion inventory → R333's *What dissolves*, and
  R411 dropped its carve-out dependency).
- **The one new symbol retirement created no new flag.** R580's In-Review rework
  (a shadowed `id` column now rejects instead of warning) retired
  `LintRule.NODE_ID_SHADOWS_COLUMN` and `FieldBuilder.warnShadowedIdColumn`
  (`grep` = 0 for both, replaced by `rejectShadowedIdColumn` /
  `Rejection.structural`). The only two files citing those names are R580 itself
  (In Review, prose is the work) and R473 (`explicit-nodeid-grammar`, Spec), which
  was **reconciled against this exact rework this window** (7 commits, one literally
  titled "R580: shadowing rejects rather than warns; reconcile with R473"). Neither
  is a flag target. See cross-cutting observation 4 for the R580-overtakes-R473 note.
- **A board-wide fact-base sweep landed.** 26 active items gained a
  `## Fact-base note (2026-08-06)` section recording whether R595/R589's
  architecture subsumes, reshapes, or consumes them, all pointing at
  `2026-08-06-fact-base-impact-sweep.md`. This is architecture drift, tracked in
  that companion; the sweep's calls are folded into the recommended actions here
  for the items that also carry a stale cite (R221, R427, R562, R533, R565, R213).
- **The board grew by three, net** (seven ids allocated, four left). R597-R603 were
  filed, all **born-current** (read against the post-R563 model, scanned clean of
  every retired symbol; `grep` = 0 for retired-symbol cites in all seven) and **none
  flagged**.
- **One genuinely new stale cite.** R122 (`compound-entity-mutations`, Backlog) was
  not edited this window and its section "Design space narrows under R222" (10
  mentions) leans on R222's now-discarded `InputUsage` recursive model as a live
  design premise. The fact-base sweep, which only touched items it re-annotated,
  did not catch it. New §B flag.

Net: **0 §A / 6 §B / 24 §C / 0 §D**. Flag total holds at **30**, unchanged in count
from the prior window but recomposed: R222 leaves §C via discard; R122 enters §B.
The `Operation`-seal drift (R427, R382, R562) and the input/condition/projection
dissolution drift (§C.6, §C.7) are unchanged: no code moved on those surfaces, and
the edits their items received this window were fact-base notes, not repoints, so
every stale cite carries verbatim.

## Changes since the 2026-08-06 audit

**55 commits** landed between the prior audit's HEAD (`96346c7`, 2026-08-05 19:37)
and this HEAD (`453b916`, 2026-08-06 22:42). **3 touched main/test source**; the
other 52 are roadmap edits.

**The three code commits:**

- **`076e9cc` (R584, Done):** the MCP handshake `instructions` string now routes an
  agent to every advertised tool family via a question-keyed routing table, instead
  of spending a paragraph on `catalog.tables`/`catalog.describe` alone. Adds
  `ServerInstructionsTest` (bidirectional coverage pin, paged-total convention pin,
  character ceiling). Retires no symbol any flag depends on. One residual filed: R598.
- **`a7c9e21` (R583, Done):** pins the `typeId` axis of `BuildContext.resolveTargetKeys`
  on both jOOQ-record decode arms. Coverage only, **no production change**; three
  model-level cases on `CallSiteExtraction`. Retires nothing.
- **`47d11a4` (R580 rework, still In Review):** a shadowed `id` column rejects instead
  of warning. Retires `LintRule.NODE_ID_SHADOWS_COLUMN` and `warnShadowedIdColumn`;
  introduces `rejectShadowedIdColumn` / `Rejection.structural`. 37 fixture sites gain
  an explicit `@nodeId`. Only active items cite the retired names (see Headline).

**Items that left the board (4).** R583 → Done (`e648562`), R584 → Done (`6e0bb3a`),
R222 → Discarded (`8770357`, "the umbrella retires to lineage"), R69 → Discarded
(`6ff008c`, premise void: the directive is not graphitron's; R249's sibling refs
re-pointed at R599). In Review holds R580 alone; In Progress holds R347 alone.

**Seven items filed, all born-current, none flagged:** R597 (`warm-start-model-store`,
Backlog, architecture), R598 (`mcp-degradation-message-tool-prefix`, Backlog, cleanup),
R599 (`remove-stray-directive-declarations`, Backlog, bug), R600
(`roadmap-plans-authored-as-asciidoc`, Backlog, cleanup), R601 (`unified-diagnostic-stream`,
Backlog, structural), R602 (`input-object-directive-locations`, Backlog, cleanup),
R603 (`pipeline-output-facts-family`, Backlog, architecture). R601 and R602 are the
R222 residues cut before discard; R597/R599 were verified fact-base-consistent by the
sweep.

**Transitions:** R595 (`graphitron-model-captures-facts`) Spec → **Ready**; R585
(`input-field-resolution-typed-rejections`) → **Ready**. Ready set is now R333, R427,
R555, R585, R595 (five). R580 Ready → In Progress → **In Review** (with the rework
above); R473 (`explicit-nodeid-grammar`) took the window's densest single-item churn
(7 Spec revisions reconciling with R580). R589 stays Spec, actively drafted.

**Board accounting.** **178 item files** today (`roadmap/*.md` carrying `id:`), up
three net from 175: seven ids allocated (R597-R603), four items left (two Done, two
Discarded). Status distribution: **154 Backlog, 17 Spec, 5 Ready, 1 In Progress, 1 In
Review, 0 Done**. Tombstone-free. No duplicate `id:`; max allocated id **R603**, and
`changelog.md` carries `next-id: R604`, clearing it. A `depends-on:` sweep over all
178 files resolves every edge (all slug-based) to a present file: the four departed
slugs left no dangling `depends-on` edge (R411's carve-out and R249's siblings were
re-pointed on discard). The only structural nit is four **legacy** items still missing
a `bucket:` key (§D), all pre-dating this window.

**Net effect on flag counts: 30 flagged, 148 current.** 0 §A, 6 §B, 24 §C, 0 §D.

## Scope and method

All **178** `R<n>` item files were reviewed (plus the non-item placeholders
`inference-axis-coverage.adoc`, `relevance-ranked-search-howto.adoc`,
`relevance-ranked-search-oracle-howto.adoc` and the permanent `workflow.adoc`, all
correctly excluded). Because main source **did** move this window, every driving
symbol below was re-checked against a fresh `grep` of the main sources this pass,
not carried on the prior audit's word.

**Retired symbols, re-verified still retired at this HEAD (`grep` = 0 in main, live
successor named):** `CompileDependencyGraphBuilder`; `RowsMethodBody` /
`RowsMethodSkeleton`; `QueryConditionsGenerator` / `TypeConditionsGenerator`;
`TypeClassGenerator` / `collectRequiredProjection`; `ParentProjectionContainmentCheck`
and the `methodgraph` package (successors `render/ConditionGlueRenderer`,
`render/ProjectionUnitRenderer`, both live); `GraphitronType.TableInputType` /
`buildNonTableInputType` (successor: per-consumer `collectInputFieldRejections`, live
in `GraphitronSchemaValidator`); `MutationInputResolver.resolveInput` (the method;
successor `admitMutationInputFields`, live; the surviving `resolveInput` hits are
`RecordBindingResolver`'s unrelated same-named method and a comment); the lookup
triplet (`LookupTableField` / `BatchedLookupTableField` / `QueryLookupTableField`),
the `LookupField` mixin and `LookupValuesJoinEmitter`; `TableOnInputRejection`; the
`Split*` / `Record*` leaf names (merged to `Batched*`; the two `LookupField` string
hits are a `SplitLookupField` local variable, not the type); the `ColumnField` family
(merged to `ColumnBackedField`; all 13 string hits are `{@code}` javadoc or
`keyColumnFields` substrings); `Rejection.Deferred.planSlug`; `SourceKey.Reader` (one
javadoc hit noting it retired); the `Operation` seal and every `Operation.<Arm>`
reference including `Operation.Facet` / `Operation.Count` (successor `OperationMember`,
27 files live; obligation `MEMBER_ARMS` / `MEMBER_KNOWN_GAPS`). Successors for the two
re-derivation §B items also re-verified live: `KeyLift` / `LifterRef` / `KeyLift.FkColumns`
(R71, R505).

**New retirements this window, verified:** `LintRule.NODE_ID_SHADOWS_COLUMN` and
`FieldBuilder.warnShadowedIdColumn` (`grep` = 0; successor `rejectShadowedIdColumn` /
`Rejection.structural`, live). Cited only by R580 (In Review) and R473 (Spec,
reconciled this window); not a flag driver.

**New items scanned for retired-symbol cites:** R597-R603 all `grep` = 0. Clean.

## A. Obsolete: should leave the active roadmap (0)

Empty. The two items whose entire premise was invalidated to removal this window,
R222 (`dimensional-model-pivot`, thesis delivered one layer down by R563/R519/R595
and two of three load-bearing claims inverted) and R69 (`experimental-construct-type`,
premise void: the directive is not graphitron's), **already left** via the Discarded
transition, so they sit in lineage, not in §A. R520 (`table-on-input-removal-housekeeping`)
stays a coherent deliberately-deferred docs tail, not obsolete.

## B. Outdated: needs re-spec (premise or targets materially changed) (6)

Five carried from the prior window (each re-verified at the symbol: premise-target
still `grep` = 0 with a live successor, and unchanged this window because no code
moved on those surfaces); **R122 is new**, driven by R222's discard.

| Item | Status | What changed | Recommended action |
|---|---|---|---|
| **R122** compound-entity-mutations | Backlog | **New this window.** The section "Design space narrows under R222" (`:64-`, 10 mentions) leans on R222's recursive `InputUsage` model (`(Input, TableRef, List<InputField>)`) as a live design input. R222 was **discarded** this window, and per the fact-base sweep two of its load-bearing claims are inverted (the `Walker`/`InputUsage` graphql-java vocabulary "has no home"; capture decodes once into relations). R122 was not edited this window, so the sweep did not re-annotate it. `TableTargetField` (which this item adds) is live. | **Re-spec the "narrows under R222" section.** Drop the dependence on R222's discarded `InputUsage` carrier; re-express the cross-table nested-input model against the captured `intent_`/`applied_` relations the fact-base architecture adopted (see `2026-08-06-fact-base-impact-sweep.md` §R222). Keep the compound-mutation goal, the `@reference(path:)` flattening, and `TableTargetField`, which are intact. |
| **R462** nested-fetcher-outgoing-field-edges | Spec | Carried. Central target `CompileDependencyGraphBuilder.addFieldEdges` deleted by R549 slice 7 (`grep` = 0); the body's own stated dissolution condition (`:163`) has occurred. Its `addConditionsEdge(fetcher, parentTypeName)` cites are on the deleted builder surface. | **Re-derive against the plan-projected recompile graph.** Confirm at the symbol whether the nested-fetcher outgoing per-field edge is now modeled under `EmitPlan`. If closed (likely), **discard** and record it; if a residue survives, **re-spec** onto the plan-projection. |
| **R545** model-free-of-emit-vocabulary | Backlog | Carried. `RowsMethodBody` / `RowsMethodSkeleton` deleted (`grep` = 0), so the whole diagnosis and the **second deliverable** are gone. The **first deliverable survives**: `ClassName` (~1700 lines, 23 model files) and `TypeName` (28 model files) are still model-pervasive. | **Re-spec.** Drop the `RowsMethodBody` diagnosis and the second deliverable. Keep and re-baseline the first deliverable (`JavaTypeRef` replacing `TypeName` / `ClassName`) against the post-R549 model. |
| **R85** helper-emission-non-fetcher-hosts | Backlog | Carried. Both named edit-target generators are deleted: `QueryConditionsGenerator` (R552) and `TypeClassGenerator` (R549 slice 3.1). Condition emission moved to `render/ConditionGlueRenderer`, projection to `render/ProjectionUnitRenderer` (both live). | **Re-derive against the new `render/` layer.** Determine whether `ConditionGlueRenderer` / `ProjectionUnitRenderer` still exhibit the duplicated helper-emission problem; if so, re-spec onto them. Drop every dead `QueryConditionsGenerator.java:NNN` cite. |
| **R221** validator-walks-plain-input-unbound-fields | Backlog | Carried, and now **also fact-base-annotated.** R519 deleted `TableInputType` / `validateTableInputType` (`grep` = 0, cited live at `:14` and `GraphitronSchemaValidator.java:302-332` `:24`), successor `collectInputFieldRejections` live; R566 removed the `@table`-input classification. The window's edit added only the fact-base note. | **Close as subsumed or re-derive.** The fact-base sweep names this in R589 slice 4/5: the definition-keyed disjunct is `validateInputUnboundField`'s existing predicate, the cascade disjunct evaluates over the derived occurrence path; the item's own note says "do not build a second walker entry point in the meantime." If not building against the store, re-derive around `collectInputFieldRejections` and drop the `validateTableInputType` cites. Either way the stale references must go. |
| **R71** recordn-key-parity-lifter-and-non-jooq-record-parents | Backlog | Carried. R431 (Done) deleted the target surface: the body cites `SourceKey.java` ranges and `SourceKey.Reader.SourceRowsCall` as "the live surface", but `SourceKey` is a plain record with no `Reader`. Successor `KeyLift` / `LifterRef` / sealed `Wrap` live. "R431 ... plans to decompose" reads present tense; R431 is Done. | **Re-spec the current-state / approach section** against the decomposed `KeyLift` / `LifterRef` / `Wrap` model; drop the stale `SourceKey.Reader.SourceRowsCall` re-anchor note and the "R431 plans to decompose" tense. The goal (recordN key parity, non-jOOQ record parents) is intact. |

## C. Outdated: update references only (work valid, refs stale) (24)

Substance intact; names and line numbers drifted. All 24 carried verbatim from the
2026-08-06 audit minus R222 (discarded): every driving symbol re-verified still
`grep` = 0, none repointed in place this window. The edits several of these items
received were `## Fact-base note` annotations, not reference fixes, so the stale
cites persist; where a fact-base note also bears on the recommended action, it is
noted. The `Operation`-seal group (R427, R382, R562) is unchanged because no code
moved on that surface.

### C.0 `Operation` seal fully retired (carried; R563 slice 7)

The `Operation` sealed interface and every `Operation.<Arm>` reference are `grep` = 0
in main, re-confirmed at this HEAD. Successor: `OperationMember` (per-coordinate member
multiset); paired obligation `MEMBER_ARMS` / `MEMBER_KNOWN_GAPS`
(`ExemptionRegistry.java:157`, `:384`).

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R427** relevance-ranked-search | Ready | `:335` "`operation()` stays `Fetch`" cites the retired accessor; `:339`/`:1162` cite `Operation.Facet` as a live precedent **type**. (The window's edit added only a fact-base note; the stale cites are untouched.) | **Re-anchor.** Restate "`operation()` stays `Fetch`" against the member-derived summary fold; repoint the `Operation.Facet` precedent onto `OperationMember.Facet` (or the `MEMBER_KNOWN_GAPS` census carrying the modeled-but-unpopulated Facet arm). Fact-base sweep adds a light reshape (supply-side facts are `catalog_` regions, a decoded directive an `intent_` relation; add the two-model-window placement note). A **Ready** item; refresh before pickup. |
| **R382** multitable-interface-query-orderby-lowering | Backlog | `:19` cites `operation()` as the live mechanism hardcoding `new OrderBySpec.None()` for both interface/union arms. Accessor and seal both retired. | **Re-anchor** the mechanism cite to where the hardcoded `OrderBySpec.None` now lives (the member/fact layer, `OperationMember.OrderBy` sourcing, or `MultiTablePolymorphicEmitter`); verify the ordering gap still reproduces. |
| **R562** synthesised-connection-fields-as-coordinates | Backlog | `:17-18` name "the `Operation.Count` and `Operation.Facet` arms of the `OPERATION_ARMS` obligation (`ExemptionRegistry.OPERATION_KNOWN_GAPS`)" as the observable gap. All three names retired (arms → `OperationMember.Count`/`Facet`; obligation → `MEMBER_ARMS`; map → `MEMBER_KNOWN_GAPS`). `:38` (pre-existing) already carries the reconciling parenthetical, but the lead is unrepointed. | **Re-anchor** the three names in the lead to their `OperationMember` / `MEMBER_ARMS` / `MEMBER_KNOWN_GAPS` successors. The model question is intact; per the fact-base sweep it narrows to whether the minted connection's `totalCount`/`facets` coordinates get demand rows and where their slot facts live (synthesis provenance makes them existence facts by construction). |

### C.1 Lookup-triplet dissolution drift (carried; R563 slice 6a)

Slice 6a folded `LookupTableField` / `BatchedLookupTableField` / `QueryLookupTableField`
(and `LookupField` / `LookupValuesJoinEmitter`) into Fetch siblings plus a lookup member
(all `grep` = 0). A lookup leaf re-anchors to `BatchedTableField` (or `TableField` /
`QueryTableField`) **plus a lookup member**.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R533** localcontext-guard-predicate-single-source | Backlog | `:15` names `BatchedLookupTableField` as the open edge not admitted by the validator. `:19` (pre-existing) self-corrects to "`BatchedTableField` plus a lookup member"; the lead cite is still stale. The window's edit added only a fact-base note. | **Re-anchor** the `:15` lead to the post-dissolution sibling. Fact-base sweep: the single-sourcing subject becomes a derivation view both consumers read, and `isLocalContextGuardedDataChannel`'s allow-list dissolves rather than moves. |
| **R557** split-query-marker-sweep | Backlog | `:17`'s "consumed (the batched leaves)" enumeration lists `BatchedLookupTableField` alongside `BatchedTableField` / `BatchedPivotField`. | **Re-anchor** the enumeration: drop `BatchedLookupTableField` (now `BatchedTableField` + lookup member). The total-switch-over-classified-leaf design is intact. |

### C.2 `@table`-on-input rejection → deprecation drift (carried; R566)

R566 made `@table` on an `INPUT_OBJECT` accepted-ignored-warned rather than a
classify-time rejection (`TableOnInputRejection` / `buildNonTableInputType` `grep` = 0).

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R565** unclassified-input-arg-cascade-diagnostic | Backlog | Title (`:3`, `:13`) still leads with the retired `@table`-on-input **rejection** as the driver; the body (`:16`, `:30`, `:35-46`) already frames it correctly against the current state. The window's edit added only a fact-base note. | **Re-anchor (not full re-spec).** The underlying bug (`resolveDmlWalkerInputArg` conflating "not an input object" with "input did not classify") is real and reachable via any other type-level input rejection. Retitle and re-lead onto a still-current rejection; demote `@table`-on-input to historical framing. Fact-base sweep: restate as "the arm reads the claim view, the second error cannot mint", keeping only the suppress-or-pair residual. |

### C.3 `planSlug` / `SourceKey.Reader` removal drift (carried)

R484 (Done) removed `Rejection.Deferred.planSlug`; R431 (Done) removed the
`SourceKey.Reader` interface. Deferrals anchor by `StubKey.VariantClass`; column reads
lift via `KeyLift.FkColumns`.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R454** routine-write-result-shapes | Backlog | `:18` names the deferred shapes as "typed `Deferred`s pointing at this item's planSlug"; `planSlug` is gone. | **Re-anchor:** deferred shapes surface via `StubKey.VariantClass`, no roadmap pointer. |
| **R447** routine-chain-fetch-form-breadth | Backlog | `:18`/`:33` "`planSlug` points here"; `:24`/`:26` name `SplitLookupTableField` / `RecordTableField` as live. | **Re-anchor:** drop the `planSlug` phrasing; repoint `RecordTableField` to `BatchedTableField`, `SplitLookupTableField` to `BatchedTableField` **+ lookup member**. |
| **R180** record-parent-column-read-helper | Spec | `:35` says "R431 ... now In Progress" (R431 is **Done**) and names `SourceKey.Reader.AccessorCall` as a live carrier. | **Re-anchor** the `AccessorCall` carrier onto the decomposed model; fix the R431 tense to Done. |
| **R505** tenant-index-parent-row-routing | Backlog | `:21` names "a column read off the parent row (the `SourceKey.Reader` family)" as the carrier for `ParentRowBound`. Live via `KeyLift.FkColumns`. | **Re-anchor** the one parenthetical: per-row column read lifts via `KeyLift.FkColumns`. |

### C.4 Leaf-merge drift: `Split*` / `Record*` → `Batched*` (carried)

`SplitTableField` / `RecordTableField` merged to `BatchedTableField` (R432);
`SplitRowsMethodEmitter` is **not** renamed and is correct wherever it appears. The
lookup twins re-anchor to `BatchedTableField` + a lookup member (slice 6a).

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R109** list-valued-external-field-multiset | Spec | `:51`'s planned enum arm asserts "`RecordTableField` with `BatchKey.AccessorKeyedMany`". | **Re-anchor** the planned assertion to `BatchedTableField`. |
| **R242** dml-payload-positional-alignment | Spec | `:37-38`'s R305 lineage note "collapsed it into `RecordTableField`" is stale; `:107`/`:148`/`:150`/`:284` cite `LookupValuesJoinEmitter` as the live values-join edit/fork target (deleted slice 6a). | **Re-anchor** the `RecordTableField` name to `BatchedTableField`, and repoint the `LookupValuesJoinEmitter` cites to the render values-join family (`render/LookupRows`, `render/ValuesJoinRowBuilder`, `ProjectionUnitRenderer`'s lookup arm). The positional-alignment subject is intact. |
| **R288** inline-interface-and-tablemethod-children | Backlog | `:33-34`: "a keyed batch ... as `SplitTableField` / `RecordTableField` do via `SplitRowsMethodEmitter`". Variant names stale; emitter fine. | **Re-anchor** the two variant names to `BatchedTableField`. |
| **R116** composite-key-row2-source-row-coverage | Backlog | `:15`: the planned `COMPOSITE_KEY_ROW2_PATH_KEYED` case "classifies as `RecordTableField` with a `LifterPathKeyed`". | **Re-anchor** to `BatchedTableField`. |
| **R7** decompose-typefetchergenerator | Backlog | `:30` proposes a hypothetical `SplitTableFieldEmitter`; `:32` lists `LookupValuesJoinEmitter` as an emitter already following the target pattern (deleted slice 6a). | **Low priority:** refresh the illustrative name to `BatchedTableFieldEmitter`; repoint the `LookupValuesJoinEmitter` cite to the render lookup family. |
| **R323** nestingfield-multiparent-batchkey-leaves | Backlog | `:18`/`:30` list `SplitTableField`, `SplitLookupTableField`, `RecordTableField`, `RecordLookupTableField` as live classification options. | **Re-anchor** the four names: plain twins to `BatchedTableField`; lookup twins to `BatchedTableField` **+ lookup member**. Multi-parent batch-key substance intact. |

### C.5 `ColumnBackedField` dissolution drift (carried; R508)

R508 (Done) merged `ColumnField` / `CompositeColumnField` / `CompositeColumnReferenceField`
into `ColumnBackedField`. Surviving `ColumnField` string hits in main are `{@code}` javadoc
or `keyColumnFields` substrings.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R333** coordinate-lowers-to-datafetcher-queryparts | Ready | The design doc names the retired carriers as live across many prose regions (`:570`, `:694`, `:706`, `:727`, `:747-748`, `:798-799`, `:1112`, `:1140`, `:1145`, `:2009`). Part of the single R333 refresh (see §C.7). | **Re-anchor** the carrier names to `ColumnBackedField` (and its `columns` / `compaction` components). Part of the one R333 pass. |

### C.6 `TableInputType` / `resolveInput` removal drift (carried; R519 + R515)

R519 (Done) deleted `GraphitronType.TableInputType`, its walk and `buildNonTableInputType`,
moving input classification to per-consumer resolution; R515 (Done) removed
`MutationInputResolver.resolveInput`, hoisting admission to `admitMutationInputFields` (live).
R222 left this subsection via discard.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R213** input-field-rejection-attribution | Backlog | `:64` scope note: "`@table` input types route through `TableInputType` classification ... `UnclassifiedType`". Both gone (and per R566 `@table`-input no longer rejects). Window's edit added only a fact-base note. | **Re-anchor** the one scope-note sentence to per-consumer resolution; state the `@table`-input out-of-scope boundary without the retired path. Fact-base sweep: locations ride the raw facts (definition-keyed at the input field, use-keyed at the occurrence path); check what remains after R589 slice 4. Core subject and `InputFieldResolver` cites valid. |
| **R234** jooq-embedded-and-udt-input-backings | Spec | `:15` cites `TypeBuilder.buildNonTableInputType` (`grep` = 0) as the live dispatch to extend; sibling arms (`JooqRecordInputType` / `JooqTableRecordInputType`) live. | **Re-anchor** the dispatch site to the current `TypeBuilder` input-classification path (`buildInputType`); design intact. |
| **R257** updaterows-walker-sdl-substrate | Backlog | `:17` calls `resolveInput` "the legacy resolver" (gone R515); `:15`/`:19` reach carriers "via `TableInputType.inputFields()`" (gone R519). | **Re-anchor** both dead names: `resolveInput` → `admitMutationInputFields`, `TableInputType.inputFields()` → per-consumer input resolution. |
| **R337** input-nesting-projection-classification | Backlog | `:30` reaches an input object's own fields "via ... `TableInputType.inputFields()`" as the LSP-hover mechanism; that walk is gone (R519). | **Re-anchor** the one mechanism cite to per-consumer input resolution; LSP feature scope intact. |
| **R245** wire-condition-emit-on-mutations | Backlog | `:76` locates the `@condition` slot composition "in `MutationInputResolver.resolveInput`"; gone, composition live in `admitMutationInputFields`. | **Re-anchor** the one sentence to `admitMutationInputFields`. |

### C.7 Condition + projection emitter dissolution drift (carried; R552 + R549)

R552 deleted `TypeConditionsGenerator`; R549 slice 3.1 deleted `TypeClassGenerator` +
`collectRequiredProjection`; R549 slice 7 deleted `ParentProjectionContainmentCheck` and
the `methodgraph` package. Condition emission is `render/ConditionGlueRenderer`; projection
`render/ProjectionUnitRenderer`.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R333** coordinate-lowers-to-datafetcher-queryparts | Ready | **Refresh still incomplete, and now larger.** This window R333 *grew* (+236 lines) absorbing R222's Stage 5/6 deletion inventory into "What dissolves", but its stale cites were not repointed: still 6× `TypeClassGenerator`, 5× `collectRequiredProjection`, 5× `methodgraph`, 3× `LookupValuesJoinEmitter`, 2× `ParentProjectionContainmentCheck`, 1× `TypeConditionsGenerator`, plus the §C.0/§C.5 carriers and the `Operation` seal / "17-arm" cites. `:1972` correctly records R222 "now retired to lineage". | **Finish the refresh in one pass** (this row + §C.0 + §C.5 + §C.6 + §C.1): repoint `TypeClassGenerator` / `collectRequiredProjection` → `ProjectionUnitRenderer` / `ProjectionCommands`; drop the `TypeConditionsGenerator`, `ParentProjectionContainmentCheck`, `methodgraph`, `OutputField.operation()` cites; re-anchor `LookupValuesJoinEmitter` to the render lookup family and the `Operation` seal cites to `OperationMember`. The fact-base sweep's R333 section maps the same regions and says fix symbol-drift in that same pass; rows citing `SplitRowsMethodEmitter` stay (live). |
| **R231** emit-text-mapped-enum-fields-as-enum-type | Backlog | `:39` locates "the field-type-emit fork (likely in `TypeClassGenerator` or ...)"; deleted by R549 slice 3.1. | **Re-anchor** the investigation locus to `ProjectionUnitRenderer` / `plan/ProjectionCommands`. |
| **R35** source-orientation-javadocs | Backlog | `:42` enumerates classes needing a class-level javadoc sweep, including `TypeClassGenerator` and `TypeConditionsGenerator`, both deleted. | **Re-anchor** the enumeration: drop the two deleted names, optionally adding the successor renderers. Low priority; the sweep scope is illustrative. |

## D. Structural: (0)

Empty of blocking defects. `changelog.md` carries `next-id: R604`, clearing the max
allocated id (R603). No duplicate `id:`, no `status: Done` tombstones in `roadmap/*.md`,
and a `depends-on:` sweep over all 178 item files resolves every edge (all slug-based)
to a present file. The four items that left the board this window (R583, R584, R222, R69)
left **no dangling `depends-on` edge**: R411's carve-out on R222 and R249's siblings on
R69 were re-pointed on discard. The seven items filed this window (R597-R603) carry
well-formed front-matter and were read against the current model.

Two **pre-existing, non-blocking** hygiene notes, both surviving unchanged from prior
windows:

1. Four **legacy** items still lack a `bucket:` key: R242 (`dml-payload-positional-alignment`),
   R109 (`list-valued-external-field-multiset`), R252 (`multi-file-federation-fixture-coverage`),
   R180 (`record-parent-column-read-helper`). The roadmap-tool tolerates the omission
   (build green); fold a `bucket:` in whenever each is next edited.
2. Two items carry a prose reference to a departed slug that is **not** a `depends-on`
   edge and does not break the build, but is worth tidying next time each is opened:
   R569 (`mcp-aggregated-diagnostics`, Spec) `:487` phrases a scope boundary as "if
   `mcp-server-instruction-routing` has not [landed]" (R584 is now Done); R122's R222
   references are the §B flag above, not a §D nit.

## Cross-cutting observations

1. **An active window moves the drift set by births, deaths, and one new cite, not
   by mass repoints.** The three code commits retired one symbol pair
   (`NODE_ID_SHADOWS_COLUMN` / `warnShadowedIdColumn`) whose only citers are active
   items, so they created no flag; the flag movement came entirely from the board:
   R222's discard subtracted its §C.6 row and re-homed its residues, and R122's
   untouched dependence on R222 added the only new flag. The 24 carried §C cites did
   not move because no code moved on their surfaces and the edits they got were
   fact-base annotations, not repoints. The lesson repeats: drift is a function of
   *when an item was written* relative to the retirements it cites.

2. **A discard is a retirement event for the items that cited the discarded item.**
   R222 leaving the board is clean for R222 (its lineage is preserved, its residues
   re-homed), but it turned R122's "Design space narrows under R222" section stale
   overnight. The staleness pass, not the architecture sweep, is what catches this:
   the sweep only re-annotates items it is actively reshaping, so an item that merely
   *cited* the discarded one falls through. Whenever an item is discarded, a reverse
   `grep` for its slug across `roadmap/*.md` is the cheap guard.

3. **Fact-base annotation is not reference repoint, and the audit must not conflate
   them.** 26 items gained a `## Fact-base note` this window recording architecture
   subsumption; none of those notes fixed a stale symbol. R221, R427, R562, R533,
   R565, R213 carry *both* a stale cite (this audit) and a subsumption note (the
   companion sweep). The recommended actions above fold the sweep's call into the
   reference fix so a single edit closes both, but the two remain distinct: a symbol
   repoint keeps the spec readable now; the fact-base reshape decides whether the spec
   survives at all.

4. **R580's In-Review rework partially overtakes R473's Spec, and the two were
   reconciled in the same window.** R580's rework already converted the shadowed-`id`
   warning to a rejection and retired `NODE_ID_SHADOWS_COLUMN`; R473's body frames that
   same conversion as *its own* future deliverable. Because R473 took 7 reconciling
   revisions this window (one explicitly against the rework), this is active,
   author-managed spec tension, not drift, and neither is a flag target, the same
   reason R589 (Spec, actively drafted), R580 (In Review) and R347 (In Progress) are
   never flagged. The one thing to watch: if R580 gates to Done, R473's "becomes a
   rejection" deliverable is pre-delivered and its `:361` "R580 is still at Ready"
   line will need a status touch.

5. **The Ready set is where stale prose bites soonest, and R333 is now the worst
   case.** R333, R427, R555, R585 and R595 are picked up next. R333 both carries the
   most stale cites on the board (five drivers) *and* grew this window absorbing
   R222's inventory without repointing, so it is more internally inconsistent than at
   the prior audit. Its refresh, mapped identically by this audit's §C.7 row and the
   fact-base sweep's R333 section, is overdue and should land in one pass before
   pickup. R427's superseded `Operation.Facet` precedent is the other Ready-set
   refresh; R555, R585, R595 are current.

---

_Review date: 2026-08-07._
