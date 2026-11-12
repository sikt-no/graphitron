# Roadmap staleness audit: 2026-11-12

A point-in-time review of every active roadmap item under [`roadmap/`](../) against the **current**
state of the codebase on `claude/graphitron-rewrite` (HEAD `1e46f8a`, committed 2026-11-12 00:24 UTC,
audited 2026-11-12). The goal is to find items whose premise no longer holds: work already shipped,
constructs renamed or removed, dependencies that have since landed or been discarded, or specs grown
stale enough to mislead an implementer. Every flagged item carries a recommended action and the reason
for it.

This file is an analysis artifact, not a roadmap item: it lives in a subdirectory so the roadmap-tool
(which scans `roadmap/*.md` non-recursively and requires `id:` front-matter on each) ignores it, and it
is Markdown so the `check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-11-11` staleness audit, which has been deleted; only the latest
**staleness** audit is retained. The other files in this directory are provenance (spikes, censuses,
measurements, lineage notes) that shipped and active items cite by path; they are not staleness audits and
are left in place.

**Baseline and method.** The prior audit measured trunk at `17747b3` (`next-id: R1166`, 366 item files).
This audit measures at `1e46f8a` (`next-id: R1175`, **373** item files, max id R1174): a window of **one
day and 79 commits**. The baseline commit was reachable after deepening the clone, so the delta is a real
commit diff:

1. Parse every `roadmap/*.md` front-matter (id, status, bucket, depends-on) and recheck structure.
2. Diff `17747b3..1e46f8a`: every roadmap file touched, every changelog entry added, and the set of
   identifiers present anywhere in the non-roadmap tree at the baseline and absent now (103 of them).
   Each of the 373 item bodies was tested against that removed set; eight items cite a name removed in
   the window, three of them In Progress items citing their own work.
3. Recheck the prior audit's flags: 32 of the retired names the clusters rest on were re-`grep`ped
   (all still absent, `DerivationStratum` still present in 15 files), and no flagged item was edited in
   the window except those named below, so the flags the prior audit raised carry unless stated
   otherwise.
4. Read the eight new items (R1167 to R1174), the items edited in the window (R427, R534, R920, R1119,
   R1131, R1160) and the window's 40 R876 commits for work that delivers or undercuts a filed item.

As before, the carried flags are **symbol-evidence-backed, not a full prose read of every body**; the
recommended action for most is a re-derive that will confirm or dissolve the flag.

## What changed since the 2026-11-11 audit

- **Done:** nothing reached Done in the window.
- **Discarded:** R1165 (`error-handlers-ordered-by-narrowness`), which went Spec to Ready and was then
  discarded the same day on a Filip ruling that dispatch keeps Graphitron 9's declared order, and R1166
  (`platform-throwables-are-code-facts`), filed and discarded inside the window with it. Neither was
  flagged; nothing to carry.
- **Filed (eight, all current):** R1167 (`error-dispatch-matches-graphitron-9`, Spec), R1168
  (`shadowed-error-handler-is-reported`, Spec, depends on R1167), R1169 (`routine-child-connection`), R1170
  (`legacy-no-rows-affected-class-name`), R1171 (`error-channel-default-member`, depends on R1167), R1172
  (`node-id-field-target-compatible`), R1173 (`element-coordinates-generated`) and R1174
  (`route-defect-code-ownership`), the last six Backlog. Every tree name they cite resolves; the names
  that do not are Graphitron 9 classes, consumer schema names or the items' own proposals. R1172 was
  filed 18 minutes before R1119's `3b2ac07` and is not delivered by it: that commit states key field
  bindings for federation, not the compatibility refusal R1172 asks for.
- **Rewritten:** R1131 (`clean-build-under-five-minutes`) was rewritten from a workstation measurement
  (691 lines changed); the ten deleted names the prior audit listed are gone from its body, so its §C.5
  flag is **resolved**. R920 (`batch-failure-logs-once-not-per-field`, Spec) was reworked through a
  withheld review round and is current.
- **R876 kept moving (40 commits).** New in the store this window: `graphitron_connection`,
  `graphitron_edge`, `graphitron_type_member`, `graphitron_occurrence_built_class`,
  `graphitron_occurrence_binding_member`, `graphitron_occurrence_node_id_shape`, and from R1119
  `graphitron_key_field_binding`. Removed: the field-keyed key row (`graphitron_field_key_row` and its
  accessor, `FieldKeyRows`), `EmittedAnchor`, and `GraphitronSchema.catalogNodeDecodes` with the walk's
  registry of catalog decodes. `graphitron-model.sql` went from 307 to **313** distinct `graphitron_*`
  names and from 143 to **146** views; the `intent_*` names it still defines are unchanged at **9**.
  Connections, `PageInfo` and facets became generated classes whose fields are registered rather than
  read by graphql-java's `PropertyDataFetcher`.
- **Effect on flags.** One resolved (R1131). Three new: **R1033** (§B.2, its subject is now a refusal),
  **R562** (§B.2, connection fields entered the store) and **R1000** (§C.1, one dead cite). Two
  sharpened: **R427** (Ready, gained an undecided question that may reopen a decision, §C.5) and **R534**
  (retitled, old test name still cited, §C.1). R606 is named by R876 as live and is noted in §C.1 for a
  one-paragraph refresh only.

**Flag totals.** Prior ~108; one resolved, three added: **~110 flagged items: 4 §A (+R901 structural) /
~41 §B / ~65 §C.** Item counts are given per cluster because many flagged items cite the same retired
names. **Nothing the prior audit recommended was actioned in the window**: R209, R193, R213, R899, R761,
R861, R758, R846 and R901 are all still as they were, and R898 is still Backlog.

## Standing headline (unchanged): R876's dissolution of the `intent_` family and the materialization register stales about 40 Backlog and Spec items at once

- **R682 was discarded, absorbed by R876** (`planners-read-facts-emitters-read-commands`, changelog
  "Discarded"). R876 (`derived-read-cost-is-a-shape-problem`, still **In Progress**) is the single large
  model-level item. It has retired the `intent_` relation family (9 `intent_*` names remain in
  `graphitron-model.sql` against the 100+ the board cites), `meta_materialize` and its `_live`
  registration views (R955: the register is gone, `meta_materialize` is `grep`=0),
  `MaterializeDependencies`, `MaterializeRegistryGateTest`, `CompletionData`, the `@sourceRow` directive
  (`SourceRowDirectiveResolver`, `LifterRef`, `BatchKeyLifterDirectiveResolver` are `grep`=0), and the
  `*ShadowTest` family. `DerivationStratum` is still present (15 files) and is the next thing it
  dissolves.
- **R1163 is Done** and its changelog entry states the direction: the strata account is the
  gatherers' capture and anchor phases. Items that cite "stratum" or the `intent_` names as live
  mechanisms are the stale set.

## Status snapshot (373 item files)

[`roadmap/README.md`](../README.md) is the roll-up. By `status:` front-matter: Backlog 325, Spec 35,
Ready 7, In Progress 5, In Review 0, Done 1 (the Done one is a tombstone, §D).

- **In Progress:** R876 (`derived-read-cost-is-a-shape-problem`), R1113 (`transactional-outbox-directive`),
  R1119 (`signed-reference-entities-across-subgraphs`), R1131 (`clean-build-under-five-minutes`), R1160
  (`tests-read-shared-fact-stores`).
- **In Review:** none.
- **Ready:** R427, R467, R555, R684, R730, R838, R1137 (the same seven as the prior audit; R1165 passed
  through Ready and was discarded).

## A. Obsolete: should leave the active roadmap

| Item | Status | Why obsolete | Recommended action |
|---|---|---|---|
| **R209** field-registry-typed-rejection-trace | Backlog | **Carried, fourth audit running.** Entire deliverable (remove the `RejectionKind.AUTHOR_ERROR` default arm, emit `RejectionKind.of(rejection)`) shipped by R585; nothing remains. | **Discard**, recording R585 as the delivery vehicle. |
| **R899** read-budget-with-nothing-materialized | Spec | The item's own demotion note says it "may no longer have a subject" if R876 empties the register. R955 did: `meta_materialize` is `grep`=0 and no registration exists to price. | **Discard**, recording R955. |
| **R761** materialize-dependency-derived-before-stamp | Backlog | Subject is the `meta_materialize_dependency` relation and its stamping order; both gone with the register. | **Discard**, recording R955. |
| **R830** meta-relation-reference-inlined-key-projection | Backlog | Costs and names `meta_materialize` / `meta_materialize_dependency` / `MaterializeDependencies`, all gone. | **Re-measure `meta_relation_reference` on the current store; discard if the 153 ms figure does not reproduce**, else re-spec without the register vocabulary. |
| **R941** owner-read-gate-skips-registered-rule-bodies | Spec | Premise is "registering a relation changes which gates read its rule"; registration and `_live` names are gone. `MetaDeclarationGateTest` may survive, the registration seam does not. | **Discard**, unless the gate's body-reading blind spot still exists on `graphitron_` tables (re-derive first). |
| **R942** per-row-view-naming-fails-the-build | Spec | Goal defined entirely in terms of registrations and `_live` names; 46 of 60 cited names are gone. | **Re-derive**: if unregistered-view naming is still a real hazard keep one sentence of it, else **discard**. |
| **R901** trailing-segments-is-a-boolean | Done | Structural: see §D. | **Finish the Done transition**: record the changelog entry and delete the file (or hold deliberately and say why). |

R520 (`table-on-input-removal-housekeeping`) stays a coherent deliberately-deferred docs tail. R846
(`authored-connection-type-scope-silence`) is a self-declared tombstone scheduled for deletion when R682
reaches Done; **R682 was discarded, so the trigger it names no longer exists.** Recommend: **delete R846
now** (its fifth site shipped as R850) or rewrite its trigger to R876.

## B. Outdated: needs re-spec (premise or targets materially changed)

### B.1 Long-carried flags (still true; recommended actions unchanged)

| Item | Status | What changed | Recommended action |
|---|---|---|---|
| **R762** census-stores-members-it-reads-by-name | Backlog | Still cites `intent_class_assignable`, `intent_type_backing(_seed)`, `jvm_declared_type_ref`: gone or reshaped by R876. `jvm_class_supertype` now has `intent_jvm_ancestor`'s successors. | **Re-spec against the current `graphitron_` family**; the on-demand thesis for by-name relations survives. |
| **R193** service-param-classification-sealed-hierarchy | Backlog | **R649 shipped it**; `looksLikeSourcesShape`, `UnresolvedParam` `grep`=0. Fourth audit overdue. | **Close as subsumed by R649** after a residue check. |
| **R213** input-field-rejection-attribution | Backlog | R585 + R589 shipped the attribution split; `TableInputType` `grep`=0. Overdue. | **Close as subsumed** after a residue check. |
| **R72** slim-servicecatalog-to-lookup | Backlog | Premise dissolved by R649; `BatchKeyLifterDirectiveResolver` also gone. | **Re-spec** against the post-R649/R876 shape, drop `ServiceCatalog.java:NNN` cites. |
| **R66** rejection-string-carrier-widening | Backlog | Phase A2 delivered by R585; A1/A3/B1/B2 survive. | **Re-spec**: strike A2, re-baseline. |
| **R34** nodeid-migration-quickfix | Backlog | Driver void (R473); `SkipMismatchedElement` gone; re-anchor target `intent_node_id_instruction` is itself now reshaped by R876 (R876 has per-depth node-id bindings). | **Re-spec onto the current node-id relations**; retitle off "shim facts". |
| **R122** compound-entity-mutations | Backlog | Leans on R222's discarded `InputUsage`; `BackingClass` `grep`=0. | **Re-spec** against R876's occurrence/write model. |
| **R85** helper-emission-non-fetcher-hosts | Backlog | `QueryConditionsGenerator`, `TypeClassGenerator`, `InlineLookupTableFieldEmitter`, `EmissionContext` `grep`=0. | **Re-derive against `render/`**, drop dead cites. |
| **R71** recordn-key-parity-lifter-and-non-jooq-record-parents | Backlog | `@sourceRow`, `LifterRef`, `LifterRowKeyed`, `SourceRowsCall` all removed by R876. | **Re-spec or discard**: the lifter surface it extends no longer exists. |
| **R76** participant-fieldsjoin-helpers | Backlog | R650 subselect conversion removed the cross-table `$fieldsJoin` framing. | **Re-spec** onto the joined-detail join, or discard. |
| **R430** lsp-compile-diagnostics-publish | Backlog | LSP diagnostics moved onto the capture cadence (R638). | **Re-derive** against the fact-store LSP. |

R462, R333, R116 and now R545 (carried flags) are resolved by discard. R122/R71/R72 now additionally depend on a
removed directive (`@sourceRow`).

### B.2 Items whose premise is the retired `intent_` / register / strata model (R876, R955, R1163)

All cite relations or classes that are `grep`=0 in the tree and defined the item's motivating claim, not
an incidental reference. Recommended default: **re-spec against the `graphitron_` family**, using R876's
body and `graphitron-model.sql` as the source of current names; several may then close.

| Item | Status | Stale premise | Recommended action |
|---|---|---|---|
| **R857** refresh-what-the-edit-touched | Spec | 33 hits on the register; `MaterializationOrderTest`, `RegistrationStarted`, `MaterializeDependencies` gone. R922 (Done) delivered change observation, the first half of the goal. | **Re-spec** to the work R922 left (partition-grain rewrite and re-derive); drop the registration half. |
| **R938** view-count-is-paid-at-every-store-open | Backlog | Counts 120 views and `deriveDependencies`; views are now 129 and registration derivation is gone. Premise numbers stale, cost question may still hold. | **Re-measure, then re-spec.** |
| **R801** view-read-census-and-bridge-closure | Backlog | `meta_materialize_dependency`, `intent_bound_table`, `intent_spelled_table` gone. | **Re-spec or discard**: a census of registered view reads has no registry to read. |
| **R820** graphql-field-named-type-index | Backlog | Targets `intent_mutation_routine_seat`, `intent_carrier_routine_hop`, gone. | **Re-spec** onto the `graphitron_` field coordinate relation, or discard if indexed. |
| **R957** key-prefix-index-folding-at-consumer-scale | Backlog | Names `intent_spelled_table` and the registry gate. | **Re-spec** (re-measure on `sis` against current relations). |
| **R861** producer-registration-after-duplication-removal | Backlog | "Does the carrier producer still want a registration": no registrations exist. | **Discard**, recording R955. |
| **R924** stale-rows-follow-the-declared-keys | Backlog | Cites `meta_materialize_dependency`. | **Re-derive**; likely subsumed by R922's currency model. |
| **R758** fact-model-page-learns-the-registry | Backlog | The registry it wants documented is gone. | **Discard**, recording R955. |
| **R751** meta-family-relations-as-constrained-tables | Backlog | Premise is the `meta_` family stated as VALUES views; `meta_materialize` gone, family shrank. | **Re-derive** against current `meta_` relations. |
| **R827** target-index-exemptions-in-the-model | Backlog | Registered-targets exemptions lived beside `MaterializeRegistryGateTest`; many `intent_*` names gone. | **Re-spec** onto the current exemption set. |
| **R836** fact-schema-prose-plain-language | Spec | Prose sweep names `meta_materialize`, `meta_glossary`, `intent_*` relations; overlaps R1163's prose edge. | **Re-spec against the vocabulary R1163 shipped** (Done this window; the direction now lives in the architecture pages under `docs/architecture/`), striking the relation names that are gone. |
| **R877** graphitron-model-house-cleaning | Spec | Cleanup list names `intent_condition_membership`, `intent_field_scope_table`, `jvm_declared_type_ref`, `meta_materialize`. | **Re-spec**: strike items R876/R955 delivered. |
| **R733** build-wall-clock-guardrail | Backlog | The four `*ShadowTest` classes and `deriveDependencies` it measures are gone; R1131 (In Progress) owns build time. | **Re-derive against R1131, likely close.** |
| **R697** name-matching-stratum | Spec | Retired "stratum" vocabulary in the slug and body; `intent_column_match_claim`, `intent_bound_table` gone. | **Re-spec** and retitle off "stratum": R1163 is Done and the strata account is now the capture and anchor phases. |
| **R677** list-ordering-invariant-enforcement | Spec | `UnlowerableOrderings*`, `AuthoredClaim*`, `MaterializeRegistryGateTest` gone; R876 now owns ordering defects (`UNLOWERABLE_ORDERING`-style). The `MutationRoutineSeatTest` tier it reasons from was deleted (`e446f4c`). | **Re-derive**; likely subsumed or reducible to a test. |
| **R851** corpus-directives-to-expect-equals | Spec | `intent_authored_field_claim`, `LauncherSource`, `LauncherResult` gone. | **Re-spec** against current corpus renderer. |
| **R963** one-resolution-tail-for-the-graphitron-gatherer | Backlog | Lists `FieldEndpoints`, `MacroCapture`, `AuthoredClaimRejectionRows`, `intent_*` tail relations (20 of 30 names gone). | **Re-derive** which tails R876 already merged; likely close. |
| **R898** candidate-tree-reaches-the-key-column | Backlog | `intent_resolved_node_key_column` and `intent_resolved_type_binding` gone. `431e604` (2026-11-10) states the key column an argMapping path projects out of a node id at its landing (`graphitron_argmapping_landing_key_column`, `LandingKeyColumns`) and refuses a projection that resolves to no column, which is this item's goal; the `trailing_segments = 1` hand-off it describes no longer exists. | **Close as absorbed by R876** (Discard, recording `431e604`) once the owner confirms no reader still re-derives the projection; drop its `depends-on` edge with it. |
| **R902** node-default-key-columns-are-a-grain | Backlog | Same `intent_resolved_node_key_column` premise; a stored `graphitron_node_keycolumn` table now exists. | **Re-derive**: check whether that table holds the primary-key default rows; if so close as absorbed by R876. |
| **R812** key-column-read-per-refused-row | Backlog | `AuthoredClaimConflicts`, `intent_resolved_*` gone. | **Re-derive.** |
| **R718** column-scope-admits-the-chain-terminus | Backlog | Seven of seven cited names gone (`intent_field_path_terminus`, `intent_field_column_scope`, ...); the `PATH_TERMINAL` arm it wants guarded left the tree entirely this window, with `PARENT_BINDING` (R915, R852). | **Re-derive against R876's per-depth table bindings; likely subsumed.** |
| **R915** producer-result-is-a-field-grain-fact | Backlog | Ten of eleven names gone. | **Re-derive; likely subsumed.** |
| **R929** store-reads-no-referencefor-step | Backlog | Names `intent_*_reference_step_target`, `intent_node_id_decode_*`; reference-step relations reshaped. | **Re-derive.** |
| **R717** / **R719** / **R660** / **R816** / **R979** routine-write and carrier items | Backlog / Spec | Cite `intent_carrier_routine_hop`, `intent_mutation_routine_seat`, `RoutineWriteFacts`, `UnlowerableOrderings`; R876 retired the routine seat model in this window (R876 body: routine call is a row of `graphitron_occurrence_call`). | **Re-spec each against the call-supertype model**; R979 (`mikado-graph-replaces-the-phase-plan`) also names `RoutineWriteFacts`. For R816, the test its plan says "already" pins the behaviour, `MutationRoutineSeatTest.anAuthoredConditionOnTheFirstHopLeavesTheReReadUnanchored`, was deleted by `e446f4c`; name the fact document that replaced it. |
| **R852** field-walk-declared-target-condition-rung | Backlog | `intent_condition_method_route`, `intent_field_column_scope` gone; R876 states "a `@condition` is a call per table its occurrence binds", and R876 stored it on 2026-11-10 (`ff123e8`, `3227e55`: `graphitron_occurrence_condition`, parameters filled per depth). | **Re-derive against `graphitron_occurrence_condition`; likely subsumed.** |
| **R813** derive-package-names-three-jobs | Backlog | `TypeBackingClasses`, `InputOccurrencePaths`, `DemandResidue` gone. | **Re-spec.** |
| **R917** store-too-large-to-service | Backlog | `intent_column_match_claim`, `intent_resolved_field_claim` gone. | **Re-measure on the `graphitron_` family.** |
| **R698** views-carry-keys-not-payloads | Backlog | Cites `intent_resolved_field_claim`; the claim relations were reshaped. | **Re-derive.** |
| **R912** model-owns-every-gatherer | Backlog | `RewriteContext` gone. | **Re-spec.** |
| **R1099** input-navigation-through-output-fields | Spec | Depends on R876; cites `intent_input_occurrence_path(_step)`, `intent_field_path_node`, `columnMapping`. R876 owns input occurrence paths. | **Re-spec against R876 landed work**, keep depends-on. |
| **R1096** request-language-column-selection | Spec | Depends on R876; `LocalizedField`, `LocalizedError` are proposals, not stale, but the premise relations should be rechecked. | **Light re-check on R876 landing.** |
| **R1033** nodeid-census-of-table-only-targets | Backlog | **New flag.** Goal is build-time guarantees for a `@nodeId(typeName: T)` whose `T` is a `@table` type without `@node`, decoded from catalog metadata. R876 made that shape a refusal this window: `288e993` and `9600dc4` remove the walk's catalog decode and `GraphitronSchema.catalogNodeDecodes`, and the store refuses the target as `NODE_ID_TYPE_NAME_UNDECLARED_STUB`; a declared stub is the supported form and gets its encode and decode from `graphitron_node`. `graphitron_node_id_instruction_rule` and `resolved_type_kind`, which its scope notes build on, are also `grep`=0. | **Discard, recording `288e993` and `9600dc4`**, unless the owner wants the undeclared-stub refusal relaxed, which would be a new item stated against `graphitron_node`. |
| **R562** synthesised-connection-fields-as-coordinates | Backlog | **New flag.** Premise is that a synthesised connection's `totalCount` and `facets` "exist only as emit" with nothing in the fact base. This window R876 stored the connection (`graphitron_connection`, `graphitron_edge`, `6be2bbb`, `f91a76d`) and registered every connection, `PageInfo` and facets field (`f3f6285`, `a52cbf8`, `9ebdc2f`); `ExemptionRegistry.OPERATION_KNOWN_GAPS`, the gap it measures by, is `grep`=0. | **Re-derive against R876's connection thread**: check whether `totalCount` and `facets` now have a stored coordinate; if so close as absorbed by R876, else restate the remainder against `graphitron_connection`. |

## C. Outdated: update references only (work valid, refs stale)

Substance intact; names and line cites drifted. Clusters, with the items each hits:

**C.1 R876-retired names in otherwise-valid items (refresh the cite, keep the plan).**
R846 (see §A), R900 (`argmapping-relation-names-standardise`, `intent_argmapping_binding_leaf`), R934
(`roadmap-bodies-cite-unchecked-store-names`: **its own subject is this very drift**; it should be
Done-or-superseded once the sweep below is run), R961 (`converted-relations-prose-still-calls-them-views`,
`UnregisteredRelationTest`), R854 (`jvm_method_parameter`), R626, R729, R973, R984
(`intent_record_slot_assignable`), R736, R807, R632 (`intent_authored_claim_conflict`). **R534** (`record-key-decode-null-write-erasure`): retitled this window (`8bab7ae`) to name any later `fromArray` load and given a sis consumer report, so its premise is live and better evidenced; its opening paragraph still cites `customerUpsert_explicitNullNestedLeaf_collapsesToOmitted`, renamed on 2026-11-10 to `..._besideADecodedNodeId_writesNull`. Action: **drop the old test name and narrow the Goal to the classified-emitter path** its progress note says is left. **New: R1000** (`upsert-names-its-conflict-target`): cites `EmittedAnchor.arguments` as a twin site R998 converts; `EmittedAnchor` was removed this window (`dbf5acf`, `graphitron_type` is written by macro expansion) and `graphitron_field_path` was already gone. Action: **re-count the upsert sites** (34 files still call `onDuplicateKeyUpdate`) and drop the two dead names; the guard it proposes is unaffected. **Note, not a flag: R606** (`facets-container-derives-from-the-store`) is named by `a52cbf8` as the item that retires the new `Facets` command row, so it is live; its opening claim that the facets shapes "exist only in the assembled-schema synthesis" is one paragraph out of date and should mention that row when next edited.

**C.2 `CompletionData` / `CatalogBuilder` / `LspSchemaSnapshot` / `CatalogFacts` (dissolved by R876 / R638).**
**R427 (Ready), R555 (Ready), R684 (Ready), R730 (Ready)**, R152, R381, R594, R536, R397, R528, R557.
These four Ready items are the highest-value refresh before pickup: each names `CompletionData` (and R555
also `ClasspathScanner`, `PkLessParent`, `intent_authored_field_claim`; R684 also `CatalogFacts` and
`LspSchemaSnapshot`).

**C.3 `@sourceRow` / lifter / `SourceRowDirectiveResolver` removal.** R180
(`record-parent-column-read-helper`, `SourceRowDirectiveResolver`, `backingClassOf`), R654, R412
(`buildLifterRowKey`), R135 (`validateLift`, still carried). `@sourceRow` was removed by R876.

**C.4 Carried long-standing drift, still `grep`=0:** `TableInputType` (R337, R257, R213), `looksLikeSourcesShape`
(R220), `ConstraintMapping` / `ConstraintSet` (R92, R98, R170), `LookupValuesJoinEmitter` /
`SingleRecordTableFieldFromReturning` (R242), `MutationUpsertTableField` /
`MutationUpdateTableField` / `additionalConditions` (R245, R145), `TypeClassGenerator` /
`TypeConditionsGenerator` (R35), `DescriptionNoteApplier` / `TagApplier` / `RewriteSchemaLoader` (R252,
R298), `JavaTypeKey` (R219), `PayloadAccessor` (R537, R304), `FkHop` (R485), `CatalogRefreshTest`
(R764, R822: R832 reworked it), `SchemaDirectiveRegistry` (R599: R494 deleted it, so R599 may be
subsumed), `RootLauncherSqlBaselineTest` / `DmlSqlBaselineTest` / `BatchedChildSqlBaselineTest` (R774, R669,
R720, R829), `QueryConditionsPipelineTest` (R561), `IdReferenceShimClassificationTest` (R615),
`FetcherBodies` (R280), `walkerDiagnostics` (R601), `descendUnknownArgs` (R578), `PojoInput` (R337),
`MutationCompoundInsertField` (R122), `ErrorUnionType` / `TableUnionType` (R278).

**C.5 Ready and In Progress items with stale cites (the cheapest-to-fix, highest-impact set):**

| Item | Status | Stale cite | Action |
|---|---|---|---|
| **R838** declarative-comment-style | Ready | Cites `RewriteSchemaLoaderTest` as one of three tree hits; the class no longer exists. | Update the count to two hits. |
| **R427** relevance-ranked-search | Ready | `CompletionData`, `SearchBacking`, ... (19 of 59 names gone). | **Re-spec the Current state section** before pickup. |
| **R555** deprecate-externalfield-fold-into-service | Ready | `CompletionData`, `ClasspathScanner`, `PkLessParent`, `AuthoredClaimConflicts`. | Refresh cites before pickup. |
| **R684** consumers-share-relations-not-queries | Ready | `CatalogFacts`, `LspSchemaSnapshot`, `CompletionData`. | Refresh cites; part of its premise (CatalogFacts retirement) shipped. |
| **R730** capture-api-residue-after-nodehood-move | Ready | `CompletionData` retired by R876. | Refresh. |
| **R427** (addendum) | Ready | **New this window:** `28c66dd` added an open question raised from R876's connection thread, "Does a hit being an edge reopen decision 1?", marked not decided. | A Ready item carrying an undecided question that may reopen a spec decision should **go back to Spec until it is answered**, or the question be ruled out of scope in the body. |
| **R1113** transactional-outbox-directive | In Progress | 17 of 73 names gone; many are its own proposals (`MutationHook`, `HandleMember`) but `DefaultPageSizeDefects`, `store_graph_session_*` moved. R876's outbox commits (2026-11-09/10) overlap its directive. | **Recheck against R876's outbox work**, which `e7141da` shows has redefined the outbox message model; R876's body says R1113's service phase is R876's. Not touched since 2026-11-09. |
| **R1160** tests-read-shared-fact-stores | In Progress | `ServiceEmissionCensusTest` absent; its `@capability` / `@exemplifies` header vocabulary is not in the tree while the body still uses it. Edited four times this window without touching either. | Minor; author is mid-flight, refresh when next edited. |

R467 (`upgrade-graphql-java-26`), R1137 (`rejected-type-cascades-into-secondary-diagnostics`), R1119, R1131
(rewritten this window, one unresolved name, a Maven property) and R920 are clean: their "missing" names are
library API names or the item's own proposals. R1119 and R876 cite `EmittedAnchor` and the field-keyed key
row in progress notes describing their own removal, which is history, not drift.

## D. Structural

- **R901 `trailing-segments-is-a-boolean` is `status: Done` in `roadmap/*.md`** with a `depends-on:
  [derived-read-cost-is-a-shape-problem]` edge, last updated 2026-09-03, and has **no changelog entry**.
  The workflow deletes the file on Done and records it in `changelog.md`. Its body says "Resolved by R876: it
  was a count of nothing, and it is a name now". Recommended action: finish the transition (changelog
  entry, delete the file) or, if it is intentionally parked behind R876, say so in its body and move it
  back to a non-Done status.
- `next-id: R1175` clears the max present id (R1174); no duplicate `id:`.
- Thirteen items carry a non-empty `depends-on:`, all resolving to existing files (new this window: R1168 and
  R1171 on R1167, and R1131 on R1160, whose edge was repaired by `41ad0dc` after it named an id instead of a
  slug). Six of the thirteen depend on R876
  (`derived-read-cost-is-a-shape-problem`), which means **R876 is a single point of staleness**: any
  design change there cascades across R898, R851, R1099, R697, R1096, R901 and the §B.2 set. R857 depends on R872 (`warm-capture-empties-unpartitioned-catalog-relations`), which exists.
- Four legacy items still lack a `bucket:` key: R242, R109, R252, R180 (unchanged from the prior audit,
  harmless; fold in when next edited).
- Numbering gaps are harmless; numbers are never reused.

## Cross-cutting observations

1. **R876 is the root cause of the window's staleness and is still In Progress.** It has dissolved the
   `intent_` family, the register, `CompletionData`, `@sourceRow` and the shadow tests, and is mid-way
   through `DerivationStratum`. Items filed Sep to Nov (R899 to R979 especially) were written against the
   `intent_`/register model that R876 then retired inside weeks. Roughly forty items name relations that do
   not exist. **Expect the same again when R876 lands its remaining nodes**; six items depend on it, and
   the staleness is concentrated, not diffuse.

2. **R934 (`roadmap-bodies-cite-unchecked-store-names`) is the board diagnosing itself.** The cheapest fix
   to this audit's biggest cluster is mechanical: a gate (or a `check-` step in the roadmap-tool) that fails
   when an item body backticks an `intent_`/`graphitron_`/`meta_` relation name that
   `graphitron-model.sql` does not define. Run as part of the build it would have prevented most of §B.2
   from drifting silently. Recommend promoting R934 to Spec.

3. **A discard-and-absorb pattern keeps leaving stale `depends-on`/trigger text behind.** R846 still
   schedules itself for deletion when R682 reaches Done; R682 was discarded into R876. Several items name
   discarded ids as triggers. A sweep for `R682`, `R333`, `R222`, `R462`, `R972` in active item bodies would
   find them.

4. **The two overdue subsumption candidates, R193 and R213, have now survived five audits unactioned.** Both
   are mechanical closes (R649; R585 and R589). R209 (§A) is the third. Closing all three is five minutes of
   work and removes a recurring line from this report.

5. **The Ready set is where stale prose bites soonest.** Of the seven Ready items, **R427, R555, R684, R730
   and R838 carry stale cites**, and R427 now also carries an undecided question (R467 and R1137 are clean). Refreshing those before pickup remains the
   highest-value hygiene action, as it was last audit.

6. **A reachable baseline makes the audit cheap.** This run deepened the clone, diffed against the prior
   audit's commit, and found the day's delta in five items (R1033, R562, R1000, R427, R534) rather than re-deriving 373. Daily runs should
   keep doing that; the full symbol sweep is only worth repeating when the window is weeks long.

7. **R876 delivers or overturns Backlog items without closing them.** R898 was the clear case last audit:
   its goal landed in `431e604` under R876's scope and the item is still Backlog with a `depends-on` edge to
   R876. R852 and R902 look the same. This window adds R1033, whose subject R876 turned into a refusal, and
   R562, whose connection fields R876 stored and registered. When an R876 commit lands something a filed item asked for, discarding
   that item in the same commit (as was done for R545) keeps the board honest.

---

_Review date: 2026-11-12._
