# Roadmap staleness audit: 2026-11-16

A point-in-time review of every active roadmap item under [`roadmap/`](../) against the **current**
state of the codebase on `claude/graphitron-rewrite` (HEAD `d2ba1f7`, committed 2026-11-16 00:08 UTC,
audited 2026-11-16). The goal is to find items whose premise no longer holds: work already shipped,
constructs renamed or removed, dependencies that have since landed or been discarded, or specs grown
stale enough to mislead an implementer. Every flagged item carries a recommended action and the reason
for it.

This file is an analysis artifact, not a roadmap item: it lives in a subdirectory so the roadmap-tool
(which scans `roadmap/*.md` non-recursively and requires `id:` front-matter on each) ignores it, and it
is Markdown so the `check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-11-13` staleness audit, which has been deleted; only the latest
**staleness** audit is retained. The other files in this directory are provenance (spikes, censuses,
measurements, lineage notes) that shipped and active items cite by path; they are not staleness audits and
are left in place.

**Baseline and method.** The prior audit measured trunk at `4206b68` (`next-id: R1183`, 378 item files).
This audit measures at `d2ba1f7` (`next-id: R1186`, **380** item files, max id R1186): a window of **three
days and 298 commits**, 214 of them R876's, 17 R1131's, 11 R1119's, 10 R1184's and 7 R1177's. The clone was
shallow, so `4206b68` only became reachable after deepening it; the delta below is a real commit diff:

1. Parse every `roadmap/*.md` front-matter (id, status, slug) at both commits and diff the two tables.
2. Diff `4206b68..d2ba1f7`: every roadmap file touched, every changelog entry added, and the set of
   identifiers present in removed lines of the non-roadmap diff and absent from the tree now (**37** of
   them, among them `TenantConnections`, `SlotParse`, `graphitron_entry_defect_rule`, `graphitron_node_type`,
   `graphitron_argmapping_call`, `graphitron_argmapping_parameter`, `graphitron_occurrence_call_pin`, the
   three `graphitron_element_minted_*` relations and `DefectOwnerGateTest`). Each of the 380 item bodies was
   tested against that set: only three items outside R876 cite one, R1131 as history, and R1146 and
   R1034 as live premise (§A, §C.5). The `TenantConnections` rename to `OwnedConnections` (`a0a97cfc`) was
   swept through every live body in the same commit, so it leaves no drift.
3. Every new item (R1183 to R1186) and every item edited in the window had its backticked names checked
   against an index of every identifier in the tree. Names that do not resolve are consumer names
   (`EndreKullService`, `kregNode`), PostgreSQL catalog columns (`pg_policy`, `polqual`), or the item's own
   proposals (`database_policy`, `DatabaseFactCapture`).
4. Recheck the prior audit's flags. Every flagged item is still on the board with the same status, and the
   retired names the clusters rest on were re-`grep`ped (`CompletionData`, `meta_materialize`,
   `looksLikeSourcesShape`, `TableInputType`, `RewriteSchemaLoaderTest`, `SourceRowDirectiveResolver`,
   `graphitron_runtime_class`, `graphitron_ast_error_database_handler_entry`: all still `grep`=0), so the
   flags carry unless stated otherwise.
5. Read the window's `feat`/`fix` commits (44 of them) for work that delivers or undercuts a filed item,
   and grep the active bodies for each one's subject (`-parameters`, bare `ID` returns, converter-backed
   node ids, polymorphic ids at a `@service`, single-child `@lookupKey`, `@orderBy` shape, catalog
   reclaim, `graphitron_result`).

As before, the carried flags are **symbol-evidence-backed, not a full prose read of every body**; the
recommended action for most is a re-derive that will confirm or dissolve the flag.

## What changed since the 2026-11-13 audit

- **Done:** R1177 (`reclaim-spares-sources-other-graphs-name`, `789887f`): a capture no longer reclaims a
  schema file or classpath entry another graph in the same store still claims. Nothing to carry.
- **Discarded:** R1174 (`route-defect-code-ownership`), superseded by the 2026-11-14 Alf ruling that nobody
  owns a defect code and no column names a stage (`defects-as-facts.adoc`); its remainder is R876's node
  "A route defect has one writer". No active body leans on defect-code ownership (`owner_stage` survives
  only on relation-ownership columns such as `graphitron_occurrence_source_precedence`, a different
  concept; R1119's "owning stage" passages are about relation ownership too).
- **Filed (four, all current):** R1183 (`propagated-nested-failure-reported-once`, Backlog, from a sis
  report), **R1184** (`database-policy-gatherer`, Backlog to Spec to **Ready** inside the window after one
  revise round), R1185 (`connection-hook-takes-the-key`, Backlog; it already names `OwnedConnections.dslFor`)
  and R1186 (`catalog-sources-are-never-forgotten`, Backlog, the jOOQ-package half of R1177's reclaim, filed
  after `000a3c2d` made a graph drop a catalog it stops naming). Every tree name they cite resolves.
- **Moved:** none of the other active items changed status. **R920** is still In Review, now four days
  without its Done-gate review.
- **R876 kept moving (214 commits).** The large removals: `graphitron_entry_defect_rule` (87k characters in
  65 branches) split into the rule views of the stages whose facts each branch reads (`4a70ef60`), which is
  why the model went from **161 views and 317 tables to 250 views and 376 tables**; `graphitron_node_type`
  folded into `graphitron_node` (`d2192619`, the editor and the MCP read the resolved node);
  `SlotParse` went, a node id decoding through a converter-backed key column (`cee54e24`, `feat!`); the
  argMapping call and parameter relations and `graphitron_occurrence_call_pin` went as calls became one
  binding vocabulary (`3f2bc931`, `35716d2b`, `60ec0ed5`, `2fecb1af`: `ARGUMENT_UNBOUND`,
  `PARAMETER_UNFILLED`, `PARAMETER_MISMATCH`); the generated `TenantConnections` became `OwnedConnections`
  (`a0a97cfc`, `feat!`). It also stated per-call tenant connections (`f025f3f5`, `ac48c2b1`), polymorphic
  node ids at a `@service` parameter or bean member (`744376c8`), the single-child `@lookupKey` lookup
  (`c38f7d38`), and the participant node-id dispatch (`2a39effc`). Six `feat!` commits change what an
  author sees (`cee54e24`, `697945ca`, `6910040e`, `35716d2b`, `60ec0ed5`, `a0a97cfc`).
- **R1131** re-measured: `graphitron` passes at `-Xmx2g` once 60 of the entry-defect rule's branches left
  it, and the item now states the build is back to about 20 minutes before the walk dissolves and 5 to 10
  after (`4b02d9d5`). **R1119** landed slice 7 (`@accept`, voucher decode). **R1160** recorded the
  remaining inline-capture sites as nodes; R1131's `5666592d` moved the capture tests into
  `graphitron-model` and refreshed R730's paths in the same commit.
- **Effect on flags.** None resolved. Three new: **R1034** (§A: R876's `d2192619` delivered its goal),
  **R1146** (§C.5, Spec: its plan's `SlotParse.parseClasses` was removed by `cee54e24`) and a structural
  one, **`next-id` behind a live id** (§D). Sharpened: **R938** (§B.2: the view count it prices is now
  250, not 129), **R1033** (its sibling R1034 is now absorbed, so both close together), and **R984**
  (§C.1: `744376c8` decodes a several-node-type id into one slot; the per-member overload remains).

**Flag totals.** Prior ~114; none resolved, three added: **~117 flagged items: 5 §A (+R901 structural) /
~41 §B / ~70 §C**, plus one board-level structural flag. Item counts are given per cluster because many
flagged items cite the same retired names. **Nothing the prior audit recommended was actioned in the
window**: R209, R193, R213, R899, R761, R861, R758, R846, R1033, R562, R898 and R901 are all still as they
were, the four stale Ready items (R427, R555, R684, R730) still name `CompletionData`, R1167 and R1168 still
carry their dead store names, and the R427 open question is still undecided.

## Standing headline (unchanged): R876's dissolution of the `intent_` family and the materialization register stales about 40 Backlog and Spec items at once

- **R682 was discarded, absorbed by R876** (`planners-read-facts-emitters-read-commands`, changelog
  "Discarded"). R876 (`derived-read-cost-is-a-shape-problem`, still **In Progress**) is the single large
  model-level item. It has retired the `intent_` relation family (7 `intent_*` names remain in
  `graphitron-model.sql` against the 100+ the board cites), `meta_materialize` and its `_live`
  registration views (R955: the register is gone, `meta_materialize` is `grep`=0),
  `MaterializeDependencies`, `MaterializeRegistryGateTest`, `CompletionData`, the `@sourceRow` directive
  (`SourceRowDirectiveResolver`, `LifterRef`, `BatchKeyLifterDirectiveResolver` are `grep`=0), and the
  `*ShadowTest` family; the 2026-11-13 window retired the scope-table chain, the runtime connection
  vocabulary relations and `graphitron_routine_entry`, and this window `graphitron_entry_defect_rule`,
  `graphitron_node_type`, `SlotParse` and the argMapping call relations. `DerivationStratum` is still
  present (8 Java files, down from 11) and is the next thing it dissolves.
- **R1163 is Done** and its changelog entry states the direction: the strata account is the
  gatherers' capture and anchor phases. Items that cite "stratum" or the `intent_` names as live
  mechanisms are the stale set.

## Status snapshot (380 item files)

[`roadmap/README.md`](../README.md) is the roll-up. By `status:` front-matter: Backlog 329, Spec 34,
Ready 10, In Progress 5, In Review 1, Done 1 (the Done one is a tombstone, §D).

- **In Progress:** R876 (`derived-read-cost-is-a-shape-problem`), R1113 (`transactional-outbox-directive`),
  R1119 (`signed-reference-entities-across-subgraphs`), R1131 (`clean-build-under-five-minutes`), R1160
  (`tests-read-shared-fact-stores`).
- **In Review:** R920 (`batch-failure-logs-once-not-per-field`), awaiting an independent-session Done
  review since 2026-11-12. Its body was touched only by the `OwnedConnections` rename; the delivery is
  unchanged, so it is ready for the gate.
- **Ready:** R427, R467, R555, R684, R730, R838, R1137, R1167, R1168, plus **R1184**
  (`database-policy-gatherer`, new this window and clean). R1177 passed through Spec, Ready and In
  Progress to Done inside the window.

## A. Obsolete: should leave the active roadmap

| Item | Status | Why obsolete | Recommended action |
|---|---|---|---|
| **R209** field-registry-typed-rejection-trace | Backlog | **Carried, fifth audit running.** Entire deliverable (remove the `RejectionKind.AUTHOR_ERROR` default arm, emit `RejectionKind.of(rejection)`) shipped by R585; nothing remains. | **Discard**, recording R585 as the delivery vehicle. |
| **R899** read-budget-with-nothing-materialized | Spec | The item's own demotion note says it "may no longer have a subject" if R876 empties the register. R955 did: `meta_materialize` is `grep`=0 and no registration exists to price. | **Discard**, recording R955. |
| **R761** materialize-dependency-derived-before-stamp | Backlog | Subject is the `meta_materialize_dependency` relation and its stamping order; both gone with the register. | **Discard**, recording R955. |
| **R830** meta-relation-reference-inlined-key-projection | Backlog | Costs and names `meta_materialize` / `meta_materialize_dependency` / `MaterializeDependencies`, all gone. | **Re-measure `meta_relation_reference` on the current store; discard if the 153 ms figure does not reproduce**, else re-spec without the register vocabulary. |
| **R941** owner-read-gate-skips-registered-rule-bodies | Spec | Premise is "registering a relation changes which gates read its rule"; registration and `_live` names are gone. `MetaDeclarationGateTest` may survive, the registration seam does not. | **Discard**, unless the gate's body-reading blind spot still exists on `graphitron_` tables (re-derive first). |
| **R942** per-row-view-naming-fails-the-build | Spec | Goal defined entirely in terms of registrations and `_live` names; 46 of 60 cited names are gone. | **Re-derive**: if unregistered-view naming is still a real hazard keep one sentence of it, else **discard**. |
| **R1034** lsp-nodeid-typename-accepts-every-decode-target | Backlog | **New.** Goal: the LSP stops flagging a `@nodeId(typeName: T)` the build accepts, reading the build's node set instead of `graphitron_node_entry` (the authored-`@node` family, now `grep`=0). R876's `d2192619` (2026-11-13) did exactly that: `DiagnosticFacts` reads `graphitron_node` (authored and inferred nodes) and the containers, and `Diagnostics.judgeNodeTypeName`'s message no longer says the type must carry `@node`; it names a `@node` type, an `implements Node` type over node metadata, or an interface or union over nodes. The one population left over, a `@table` type without `Node` over catalog node metadata, is the shape R876 now refuses (`NODE_ID_TYPE_NAME_UNDECLARED_STUB`, see R1033 in §B.2), so the LSP flagging it agrees with the build. `5e847269` refreshed one relation name in the body but not the Goal. | **Discard, recording `d2192619`**, together with R1033. |
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
| **R938** view-count-is-paid-at-every-store-open | Backlog | Counts 120 views and `deriveDependencies`; registration derivation is gone, and the view count has moved the other way: **161 at the prior audit, 250 now** (the 2026-11-14/15 split of `graphitron_entry_defect_rule` into per-stage rule views; tables 317 to 376). The cost question is sharper, not dissolved, and R1131's C1 now owns a view-on-view node ordered by execution cost. | **Re-measure against R1131's 2026-11-15 profile, then re-spec or fold into R1131.** |
| **R801** view-read-census-and-bridge-closure | Backlog | `meta_materialize_dependency`, `intent_bound_table`, `intent_spelled_table` gone. | **Re-spec or discard**: a census of registered view reads has no registry to read. |
| **R820** graphql-field-named-type-index | Backlog | Targets `intent_mutation_routine_seat`, `intent_carrier_routine_hop`, gone. | **Re-spec** onto the `graphitron_` field coordinate relation, or discard if indexed. |
| **R957** key-prefix-index-folding-at-consumer-scale | Backlog | Names `intent_spelled_table` and the registry gate. | **Re-spec** (re-measure on `sis` against current relations). |
| **R861** producer-registration-after-duplication-removal | Backlog | "Does the carrier producer still want a registration": no registrations exist. | **Discard**, recording R955. |
| **R924** stale-rows-follow-the-declared-keys | Backlog | Cites `meta_materialize_dependency`. | **Re-derive**; likely subsumed by R922's currency model. |
| **R758** fact-model-page-learns-the-registry | Backlog | The registry it wants documented is gone. | **Discard**, recording R955. |
| **R751** meta-family-relations-as-constrained-tables | Backlog | Premise is the `meta_` family stated as VALUES views; `meta_materialize` gone, family shrank. | **Re-derive** against current `meta_` relations. |
| **R827** target-index-exemptions-in-the-model | Backlog | Registered-targets exemptions lived beside `MaterializeRegistryGateTest`; many `intent_*` names gone. | **Re-spec** onto the current exemption set. |
| **R836** fact-schema-prose-plain-language | Spec | Prose sweep names `meta_materialize`, `meta_glossary`, `intent_*` relations; overlaps R1163's prose edge. | **Re-spec against the vocabulary R1163 shipped** (Done 2026-11-12; the direction now lives in the architecture pages under `docs/architecture/`), striking the relation names that are gone. |
| **R877** graphitron-model-house-cleaning | Spec | Cleanup list names `intent_condition_membership`, `intent_field_scope_table`, `jvm_declared_type_ref`, `meta_materialize`; since then also `graphitron_routine_entry` (`d275306`). | **Re-spec**: strike items R876/R955 delivered. |
| **R733** build-wall-clock-guardrail | Backlog | The four `*ShadowTest` classes and `deriveDependencies` it measures are gone; R1131 (In Progress) owns build time. | **Re-derive against R1131, likely close.** |
| **R697** name-matching-stratum | Spec | Retired "stratum" vocabulary in the slug and body; `intent_column_match_claim`, `intent_bound_table` gone. | **Re-spec** and retitle off "stratum": R1163 is Done and the strata account is now the capture and anchor phases. |
| **R677** list-ordering-invariant-enforcement | Spec | `UnlowerableOrderings*`, `AuthoredClaim*`, `MaterializeRegistryGateTest` gone; R876 now owns ordering defects (`UNLOWERABLE_ORDERING`-style). The `MutationRoutineSeatTest` tier it reasons from was deleted (`e446f4c`). | **Re-derive**; likely subsumed or reducible to a test. |
| **R851** corpus-directives-to-expect-equals | Spec | `intent_authored_field_claim`, `LauncherSource`, `LauncherResult` gone. | **Re-spec** against current corpus renderer. |
| **R963** one-resolution-tail-for-the-graphitron-gatherer | Backlog | Lists `FieldEndpoints`, `MacroCapture`, `AuthoredClaimRejectionRows`, `intent_*` tail relations (20 of 30 names gone). | **Re-derive** which tails R876 already merged; likely close. |
| **R898** candidate-tree-reaches-the-key-column | Backlog | `intent_resolved_node_key_column` and `intent_resolved_type_binding` gone. `431e604` (2026-11-10) states the key column an argMapping path projects out of a node id at its landing (`graphitron_argmapping_landing_key_column`, `LandingKeyColumns`) and refuses a projection that resolves to no column, which is this item's goal; the `trailing_segments = 1` hand-off it describes no longer exists. | **Close as absorbed by R876** (Discard, recording `431e604`) once the owner confirms no reader still re-derives the projection; drop its `depends-on` edge with it. |
| **R902** node-default-key-columns-are-a-grain | Backlog | Same `intent_resolved_node_key_column` premise; a stored `graphitron_node_keycolumn` table now exists. | **Re-derive**: check whether that table holds the primary-key default rows; if so close as absorbed by R876. |
| **R812** key-column-read-per-refused-row | Backlog | `AuthoredClaimConflicts`, `intent_resolved_*` gone. | **Re-derive.** |
| **R718** column-scope-admits-the-chain-terminus | Backlog | Seven of seven cited names gone (`intent_field_path_terminus`, `intent_field_column_scope`, ...); the `PATH_TERMINAL` arm it wants guarded left the tree in an earlier window, with `PARENT_BINDING` (R915, R852). | **Re-derive against R876's per-depth table bindings; likely subsumed.** |
| **R915** producer-result-is-a-field-grain-fact | Backlog | Ten of eleven names gone. | **Re-derive; likely subsumed.** |
| **R929** store-reads-no-referencefor-step | Backlog | Names `intent_*_reference_step_target`, `intent_node_id_decode_*`; reference-step relations reshaped. | **Re-derive.** |
| **R717** / **R719** / **R660** / **R816** / **R979** routine-write and carrier items | Backlog / Spec | Cite `intent_carrier_routine_hop`, `intent_mutation_routine_seat`, `RoutineWriteFacts`, `UnlowerableOrderings`; R876 retired the routine seat model (R876 body: routine call is a row of `graphitron_occurrence_call`). | **Re-spec each against the call-supertype model**; R979 (`mikado-graph-replaces-the-phase-plan`) also names `RoutineWriteFacts`. For R816, the test its plan says "already" pins the behaviour, `MutationRoutineSeatTest.anAuthoredConditionOnTheFirstHopLeavesTheReReadUnanchored`, was deleted by `e446f4c`; name the fact document that replaced it. |
| **R852** field-walk-declared-target-condition-rung | Backlog | `intent_condition_method_route`, `intent_field_column_scope` gone; R876 states "a `@condition` is a call per table its occurrence binds", and R876 stored it on 2026-11-10 (`ff123e8`, `3227e55`: `graphitron_occurrence_condition`, parameters filled per depth). | **Re-derive against `graphitron_occurrence_condition`; likely subsumed.** |
| **R813** derive-package-names-three-jobs | Backlog | `TypeBackingClasses`, `InputOccurrencePaths`, `DemandResidue` gone. | **Re-spec.** |
| **R917** store-too-large-to-service | Backlog | `intent_column_match_claim`, `intent_resolved_field_claim` gone. | **Re-measure on the `graphitron_` family.** |
| **R698** views-carry-keys-not-payloads | Backlog | Cites `intent_resolved_field_claim`; the claim relations were reshaped. | **Re-derive.** |
| **R912** model-owns-every-gatherer | Backlog | `RewriteContext` gone. | **Re-spec.** |
| **R1099** input-navigation-through-output-fields | Spec | Depends on R876; cites `intent_input_occurrence_path(_step)`, `intent_field_path_node`, `columnMapping`, and `graphitron_field_scope_table`, which `4362525` removed on 2026-11-12 with the whole scope-table chain. R876 owns input occurrence paths. | **Re-spec against R876 landed work**, keep depends-on. |
| **R1096** request-language-column-selection | Spec | Depends on R876; `LocalizedField`, `LocalizedError` are proposals, not stale, but the premise relations should be rechecked. | **Light re-check on R876 landing.** |
| **R1033** nodeid-census-of-table-only-targets | Backlog | **Carried from 2026-11-12, reinforced.** Goal is build-time guarantees for a `@nodeId(typeName: T)` whose `T` is a `@table` type without `@node`, decoded from catalog metadata. R876 made that shape a refusal: `288e993` and `9600dc4` remove the walk's catalog decode and `GraphitronSchema.catalogNodeDecodes`, and the store refuses the target as `NODE_ID_TYPE_NAME_UNDECLARED_STUB`; a declared stub is the supported form and gets its encode and decode from `graphitron_node`. `graphitron_node_id_instruction_rule` and `resolved_type_kind`, which its scope notes build on, are also `grep`=0. The walk's stub path itself has since gone (`a0d52c2`: a node this subgraph does not resolve is a `NodeType` carrying `resolvable`) and every `@node` implements `Node` (`51f5901`); `NODE_ID_TYPE_NAME_UNDECLARED_STUB` is still the refusal. **This window** `5e847269` refreshed its `graphitron_node_type` cite to `graphitron_node` without touching the Goal, and its LSP sibling R1034 was delivered by `d2192619` (§A). | **Discard, recording `288e993` and `9600dc4`**, in the same commit as R1034, unless the owner wants the undeclared-stub refusal relaxed, which would be a new item stated against `graphitron_node`. |
| **R562** synthesised-connection-fields-as-coordinates | Backlog | **Carried, sharpened.** Premise is that a synthesised connection's `totalCount` and `facets` "exist only as emit" with nothing in the fact base. R876 then stored the connection (`graphitron_connection`, `graphitron_edge`, `6be2bbb`, `f91a76d`) and registered every connection, `PageInfo` and facets field (`f3f6285`, `a52cbf8`, `9ebdc2f`); `ExemptionRegistry.OPERATION_KNOWN_GAPS`, the gap it measures by, is `grep`=0. Next the positions a connection's fields fill, `TOTAL_COUNT` and `FACETS` among them, became stored and read (`7cfa62c`), `ConnectionsQuery.byField` reads them, and every connection is a class `ConnectionsEmitter` renders from the store (`238f6ac`); the `ConnectionResult` carrier its body names is gone. | **Close as absorbed by R876** (Discard, recording `7cfa62c` and `238f6ac`) after a one-line check that no reader still treats `totalCount`/`facets` as emit-only; any remainder (the facets derivation) is already R606's. |

## C. Outdated: update references only (work valid, refs stale)

Substance intact; names and line cites drifted. Clusters, with the items each hits:

**C.1 R876-retired names in otherwise-valid items (refresh the cite, keep the plan).**
R846 (see §A), R900 (`argmapping-relation-names-standardise`, `intent_argmapping_binding_leaf`), R934
(`roadmap-bodies-cite-unchecked-store-names`: **its own subject is this very drift**; it should be
Done-or-superseded once the sweep below is run), R961 (`converted-relations-prose-still-calls-them-views`,
`UnregisteredRelationTest`), R854 (`jvm_method_parameter`), R626, R729, R973, R984
(`intent_record_slot_assignable`; this window `744376c8` made the store-fed emitter decode an id of several
node types into the one record slot of whichever member it names, at a `@service` parameter or bean member,
so R984's premise that such a schema "is refused" is partly gone; the per-member overload dispatch it asks
for is still not built, and `BuildContext.admitPolymorphicSlotType` survives only on the walk's side, so
restate the "today" paragraph against `744376c8`), R736, R807, R632 (`intent_authored_claim_conflict`). **R534** (`record-key-decode-null-write-erasure`): retitled (`8bab7ae`) to name any later `fromArray` load and given a sis consumer report, so its premise is live and better evidenced; its opening paragraph still cites `customerUpsert_explicitNullNestedLeaf_collapsesToOmitted`, renamed on 2026-11-10 to `..._besideADecodedNodeId_writesNull`. Action: **drop the old test name and narrow the Goal to the classified-emitter path** its progress note says is left. **R1000** (`upsert-names-its-conflict-target`): cites `EmittedAnchor.arguments` as a twin site R998 converts; `EmittedAnchor` was removed (`dbf5acf`, `graphitron_type` is written by macro expansion) and `graphitron_field_path` was already gone. Action: **re-count the upsert sites** (34 files still call `onDuplicateKeyUpdate`) and drop the two dead names; the guard it proposes is unaffected. **R476** (`connection-helper-totalcount-redaction`): the gap is still real, since `ConnectionsEmitter` routes only the `FACETS` member through `ErrorRouter` and the `TOTAL_COUNT` member is still a bare `totalCount(env)` call, but the template it points at, `ConnectionFetcherClassGenerator.facetsDelegate`, was deleted with the per-type connection fetchers (`238f6ac`). Action: **repoint the template to `ConnectionsEmitter`'s facets arm**; the fix is now one arm in one emitter. **R521** (`generated-output-hygiene-sweep`): three bullets describe `ConnectionResult` (its 11-argument constructor, the unread `beforeCursor`, its multitenant variant); the class is gone and connections are store-rendered classes. Action: **strike those bullets or restate them against the generated connection classes**; the rest of the sweep is unaffected. **Note, not a flag: R606** (`facets-container-derives-from-the-store`) is named by `a52cbf8` as the item that retires the new `Facets` command row, so it is live; its opening claim that the facets shapes "exist only in the assembled-schema synthesis" is one paragraph out of date and should mention that row when next edited.

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
| **R1167** error-dispatch-matches-graphitron-9 | Ready | **Carried, unchanged.** Its "mapping content stays on the model" decision says `graphitron_ast_error_generic_handler_entry` and `graphitron_ast_error_database_handler_entry` "already hold all of it". `be3d52c` (R876, 2026-11-12, after this item's last edit) removed the DATABASE handler's typed decode: nothing read it, and the application stands in the `graphql_ast_` entries. The generic one remains. Still uncorrected at `d2ba1f7`. | **Correct the sentence before pickup**: the later move of mapping content to the store reads DATABASE handlers from their `graphql_ast_` application, not a typed entry. The item's own scope (dispatch order) is unaffected. |
| **R1168** shadowed-error-handler-is-reported | Ready | **Carried, unchanged.** Its plan for `GraphitronClientException` says "`graphitron_runtime_class` is the precedent for a class no classpath declares: add a kind for it". `238f6ac` deleted `graphitron_runtime_class` and `graphitron_runtime_member`; connection classes are now rendered from the store, not listed in a fixed vocabulary. | **Re-decide that one design point before pickup**: the relation it would extend no longer exists, so the generated client exception's ancestry needs a new home (a row of its own, or a fixed `code_throwable_supertype` seed). Consult the principles-architect; the rest of the spec stands. |
| **R427** relevance-ranked-search | Ready | `CompletionData`, `SearchBacking`, ... (19 of 59 names gone). | **Re-spec the Current state section** before pickup. |
| **R555** deprecate-externalfield-fold-into-service | Ready | `CompletionData`, `ClasspathScanner`, `PkLessParent`, `AuthoredClaimConflicts`. | Refresh cites before pickup. |
| **R684** consumers-share-relations-not-queries | Ready | `CatalogFacts`, `LspSchemaSnapshot`, `CompletionData`. | Refresh cites; part of its premise (CatalogFacts retirement) shipped. |
| **R730** capture-api-residue-after-nodehood-move | Ready | `CompletionData` retired by R876. This window R1131's `5666592d` repointed its four test paths to `graphitron-model` (the capture tests moved there) but left the `CompletionData` cite. | Refresh the remaining cite. |
| **R1146** node-lookup-invalid-id-returns-null | Spec | **New.** The defect is live (`GraphQLQueryTest.node_garbageInput_returnsNull` still pins `null` with no error, and `QueryNodeFetcherClassGenerator`, `NodeIdEncoder.decodeColumns` and `peekTypeId` all still exist), but its plan has the entity lookup run `decodeColumns` "with that entity's `SlotParse.parseClasses`", and `SlotParse` was removed by `cee54e24` (`feat!`, 2026-11-13: a node id now decodes through a converter-backed key column, its text parsed as the column says). | **Restate that plan step against the converter-backed decode before Spec -> Ready**; the Goal and the outcome table are unaffected. |
| **R427** (addendum) | Ready | **Carried, still undecided:** `28c66dd` (2026-11-12) added an open question raised from R876's connection thread, "Does a hit being an edge reopen decision 1?", marked not decided. | A Ready item carrying an undecided question that may reopen a spec decision should **go back to Spec until it is answered**, or the question be ruled out of scope in the body. |
| **R1113** transactional-outbox-directive | In Progress | 17 of 73 names gone; many are its own proposals (`MutationHook`, `HandleMember`) but `DefaultPageSizeDefects`, `store_graph_session_*` moved. R876's outbox commits (2026-11-09/10) overlap its directive. | **Recheck against R876's outbox work**, which `e7141da` shows has redefined the outbox message model; R876's body says R1113's service phase is R876's. Its only edit since 2026-11-09 is the `OwnedConnections` rename (`a0a97cfc`), and this window R876 also fixed an `@outbox` payload key's node-id handling (`776f61f4`), so the overlap keeps growing while the item stands still. |
| **R1160** tests-read-shared-fact-stores | In Progress | `ServiceEmissionCensusTest` absent; its `@capability` / `@exemplifies` header vocabulary is not in the tree while the body still uses it. Edited again this window (`9ef46134`, `442e383d`) without touching either. | Minor; author is mid-flight, refresh when next edited. |

R467 (`upgrade-graphql-java-26`), R1137 (`rejected-type-cascades-into-secondary-diagnostics`), R1119, R1131,
R1160 and R920 (In Review) are clean, as are the Spec items R1169 and R1180 and the four new items R1183,
**R1184** (Ready), R1185 and R1186: their "missing" names are library API or PostgreSQL catalog names,
Graphitron 9 or consumer names, or the item's own proposals. R1119 cites `TenantHandDown` (renamed in its own
slice 3), R1131 `NodeIdDecodeDefectsTest` and now `graphitron_entry_defect_rule` (recording that R876 split
it), and R1160 `CoordinateSpellingGateTest`, `GraphitronSiteAnchorAgreementTest` and
`MacroExpansionLifecycleTest`, each in a progress note recording the removal; that is history, not drift.
R1186 depends on nothing but reads as the follow-on to R1177 (Done) and `000a3c2d` (R876: a graph drops a
catalog it stops naming); it names both, so the lineage is in the body.

## D. Structural

- **R901 `trailing-segments-is-a-boolean` is `status: Done` in `roadmap/*.md`** with a `depends-on:
  [derived-read-cost-is-a-shape-problem]` edge, last updated 2026-09-03, and has **no changelog entry**.
  The workflow deletes the file on Done and records it in `changelog.md`. Its body says "Resolved by R876: it
  was a count of nothing, and it is a name now". Recommended action: finish the transition (changelog
  entry, delete the file) or, if it is intentionally parked behind R876, say so in its body and move it
  back to a non-Done status.
- **New: `next-id` names a live id.** `changelog.md` says `next-id: R1186`, and R1186
  (`catalog-sources-are-never-forgotten`) exists: its filing (`9876bcb3`, 2026-11-15) added the file without
  advancing the counter. No duplicate `id:` exists yet, and `roadmap-tool`'s `nextId` takes the larger of the
  counter and max-id + 1, so the next allocation through the tool is still R1187. A hand allocation that
  trusts the counter would collide. Recommended action: **advance the counter to `R1187`** in the next
  roadmap commit (the precedent is R922's Done entry, which corrected the counter from R925 to R926 the same
  way). R1182 still has no file (absorbed into R876 on allocation), a harmless gap.
- Fourteen items carry a non-empty `depends-on:`, all resolving to existing files (unchanged this window;
  the four new items depend on nothing). Six of the fourteen depend on R876
  (`derived-read-cost-is-a-shape-problem`), which means **R876 is a single point of staleness**: any
  design change there cascades across R898, R851, R1099, R697, R1096, R901 and the §B.2 set. R857 depends on R872 (`warm-capture-empties-unpartitioned-catalog-relations`), which exists.
- Four legacy items still lack a `bucket:` key: R242, R109, R252, R180 (unchanged from the prior audit,
  harmless; fold in when next edited).
- Numbering gaps are harmless; numbers are never reused.

## Cross-cutting observations

1. **R876 is the root cause of the board's staleness and is still In Progress.** It has dissolved the
   `intent_` family, the register, `CompletionData`, `@sourceRow` and the shadow tests, and this window
   the monolithic entry-defect rule, `graphitron_node_type`, `SlotParse` and the argMapping call relations.
   Items filed Sep to Nov (R899 to R979 especially) were written against the `intent_`/register model that
   R876 then retired inside weeks. Roughly forty items name relations that do not exist. **Expect the same
   again when R876 lands its remaining nodes**; six items depend on it, and the staleness is concentrated,
   not diffuse. The pattern now reaches **Ready** and **Spec** items routinely: R1167 and R1168 (Ready) each
   lost a relation within hours of promotion on 2026-11-12, and this window R1146 (Spec) lost `SlotParse`.
   A Ready item that names store relations or generator classes should be re-checked against the tree on
   the day it is picked up.

2. **R934 (`roadmap-bodies-cite-unchecked-store-names`) is the board diagnosing itself.** The cheapest fix
   to this audit's biggest cluster is mechanical: a gate (or a `check-` step in the roadmap-tool) that fails
   when an item body backticks an `intent_`/`graphitron_`/`meta_` relation name that
   `graphitron-model.sql` does not define. Run as part of the build it would have prevented most of §B.2
   from drifting silently. `a0a97cfc` shows the other half working: the `TenantConnections` rename swept every
   live body in the same commit, and so left no drift. Recommend promoting R934 to Spec.

3. **A discard-and-absorb pattern keeps leaving stale `depends-on`/trigger text behind.** R846 still
   schedules itself for deletion when R682 reaches Done; R682 was discarded into R876. Several items name
   discarded ids as triggers. A sweep for `R682`, `R333`, `R222`, `R462`, `R972` and now `R1174` in active
   item bodies would find them.

4. **The two overdue subsumption candidates, R193 and R213, have now survived seven audits unactioned.**
   Both are mechanical closes (R649; R585 and R589). R209 (§A) is the third. Closing all three is five
   minutes of work and removes a recurring line from this report.

5. **The Ready set is where stale prose bites soonest.** Of the ten Ready items, **R427, R555, R684, R730,
   R838, R1167 and R1168 carry stale cites**, and R427 also carries an undecided question (R467, R1137 and
   the new R1184 are clean). None was refreshed in the window although R730 was edited. R1167's and R1168's
   are one sentence each and cost minutes; R1168's touches a design choice and wants a principles-architect
   consultation. Refreshing these before pickup remains the highest-value hygiene action.

6. **A reachable baseline makes the audit cheap.** This run deepened a shallow clone to reach the prior
   audit's commit, diffed against it, and found the three-day delta in eight items (R1034, R1146, R1033,
   R938, R984, R730, R1113, R1160) plus one board-level flag, rather than re-deriving 380. The removed-names
   test is the high-yield step: of 37 names the window removed, only three item bodies outside R876 still
   cite one. Runs should keep doing that; the full symbol sweep is only worth repeating when the window is
   weeks long.

7. **R876 delivers or overturns Backlog items without closing them.** R898 was the clear case two audits
   ago: its goal landed in `431e604` under R876's scope and the item is still Backlog with a `depends-on`
   edge to R876. R852 and R902 look the same, then R1033 (a refusal) and R562 (stored and registered). This
   window adds **R1034**, whose LSP fix landed as `d2192619` with a commit subject that names neither item.
   When an R876 commit lands something a filed item asked for, discarding that item in the same commit (as
   was done for R545 and R1174) keeps the board honest. The discard set this audit recommends (R209, R899,
   R761, R861, R758, R1033, R1034, R562, R898, and R193/R213 after a residue check) is now eleven items that
   each need one changelog line and a file deletion.

---

_Review date: 2026-11-16._
