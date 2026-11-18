# Roadmap staleness audit: 2026-11-18

A point-in-time review of every active roadmap item under [`roadmap/`](../) against the **current**
state of the codebase on `claude/graphitron-rewrite` (HEAD `f33a232`, committed 2026-11-18 01:16 UTC,
audited 2026-11-18). The goal is to find items whose premise no longer holds: work already shipped,
constructs renamed or removed, dependencies that have since landed or been discarded, or specs grown
stale enough to mislead an implementer. Every flagged item carries a recommended action and the reason
for it.

This file is an analysis artifact, not a roadmap item: it lives in a subdirectory so the roadmap-tool
(which scans `roadmap/*.md` non-recursively and requires `id:` front-matter on each) ignores it, and it
is Markdown so the `check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-11-17` staleness audit, which has been renamed into this file; only the
latest **staleness** audit is retained. The other files in this directory are provenance (spikes,
censuses, measurements, lineage notes) that shipped and active items cite by path; they are not staleness
audits and are left in place.

**Baseline and method.** The prior audit measured trunk at `ac1ef12` (`next-id: R1194`, 383 item files).
This audit measures at `f33a232` (`next-id: R1199`, **381** item files, max id R1198): a window of **about
23 hours and 107 commits**, 43 of them R876's, 23 R1131's, 17 R1119's, 10 R1180's and 4 R1184's. The clone
was shallow, so `ac1ef12` only became reachable after unshallowing; the delta below is a real commit diff:

1. Parse every `roadmap/*.md` front-matter (id, status, slug) at both commits and diff the two tables.
2. Diff `ac1ef12..f33a232`: every roadmap file touched, every changelog entry added, and the set of
   identifiers present in removed lines of the non-roadmap, non-docs diff and absent from the tree now
   (**176** candidates, most of them test-method names; the substantive ones are R1119's retired mount
   and default-tenant runtime: `SessionHook`, `SessionStates`, `RecordingSessionState`, `TenantId`,
   `RequestEntityDefault`, `isMountFailure`, `mountCauseFinder`, `rejectedIdentity`, `withEntityDefault`;
   R1119's DataLoader rework's `HandleMethodBody.emitDecodeAndGroup`; R1131's `GraphCountGrowthGateTest`
   and `WarmStartRefreshTest.aWarmRefreshOverAMultiPackageCatalogCompletes`). Each of the 381 item bodies
   was tested against that set. R1119 cites them only in its retired-vocabulary and history notes; **five
   other items cite one live** (R1113, R1146, R730, R1160, R1108; §B.3, §C.5).
3. Every new item (R1196, R1198) and every item edited in the window (R1184, R505, R1181
   `infinity-in-a-date-or-timestamp-order-column`, which dropped its edge to the shipped R1180) had its backticked names checked against the tree.
   Names that do not resolve are PostgreSQL catalog names, environment variables, or the item's own
   proposals.
4. Recheck the prior audit's flags. The retired names the clusters rest on were re-`grep`ped
   (`CompletionData`, `meta_materialize`, `SlotParse`, `looksLikeSourcesShape`, `TableInputType`,
   `RewriteSchemaLoaderTest`, `SourceRowDirectiveResolver`, `graphitron_runtime_class`,
   `graphitron_node_entry`, `ConnectionResult`, `DefaultPageSizeDefects`, `ServiceEmissionCensusTest`,
   `EmittedAnchor`, `ConnectionFetcherClassGenerator`: all still `grep`=0), so the flags carry unless
   stated otherwise.
5. Read the window's `feat`/`fix` commits (28 of them) for work that delivers or undercuts a filed item,
   and grep the active bodies for each one's subject (jOOQ embeddables, nested bean classes, the accessor
   rule, `@service` naming no method, an input field nothing takes, a store stamped with the model's
   version, the connection source, no default tenant, DataLoaders per type, the retired `<mount>`).

As before, the carried flags are **symbol-evidence-backed, not a full prose read of every body**; the
recommended action for most is a re-derive that will confirm or dissolve the flag.

## What changed since the 2026-11-17 audit

- **Done (three):** R920 (`batch-failure-logs-once-not-per-field`, `635fc01b2`, independent Done review),
  R1180 (`cursor-carries-the-databases-own-text`, Spec -> Ready -> In Progress -> In Review -> Ready ->
  In Progress -> In Review -> Done inside the window, `dcabc1ae3`, independent Done review) and R1187
  (`pgjdbc-pin-through-jooq-postgres-extensions`, `987ddedc9`, landed without Spec or Done review at the
  user's direction, with its changelog line). All three carry changelog lines.
- **Discarded (four, all with changelog lines):** R722 (`service-opt-in-transaction-wrap`, superseded by
  R876's service transaction opt-out node on an Alf ruling, `8b8c50074`); R1194 and R1195, filed and
  folded back into R1119's slice 8e the same day; R1197, filed and merged into R1196 the same day.
- **Filed and kept (two):** R1196 (`fold-global-read-into-default-source`, Backlog: `GlobalRead`,
  `DefaultSource`, `dslDefault` and `GraphitronConnectionInstrumentationGenerator` all resolve) and R1198
  (`graph-name-shared-by-two-executions`, Backlog). Both clean.
- **Moved:** **R1184** (`database-policy-gatherer`) went **Spec -> Ready** (`e4f709ab7`) after three
  more re-spec commits; its unresolved names are its own proposals, PostgreSQL catalog names and
  environment variables. One live cite is stale (§C.5).
- **R1119 retired the mount and the default tenant (17 commits).** A request's connections now come from
  a `ConnectionSource` it is given (`e3a3ecf68`, `feat!`); the plugin's `<mount>` and `<unmount>` are
  retired (`SessionStateBinding` keeps them only to reject them), mounting is the consumer's
  `ConnectionSources.mounting`, `SessionMountException` and `GraphitronSessionHook` are gone; a tenanted
  build's source names the tenants and the request names none, so **the default tenant and
  `@defaultTenant` are gone** (`abe204f25`, `feat!`); entities and nodes load through DataLoaders per type
  (`a871d0730`), which removed `HandleMethodBody.emitDecodeAndGroup`. R505 was updated for the default
  tenant in the same window (`e9c623eb2`). This is the window's main source of new staleness: R1108,
  R468 and R1159 are written against the mount era (§B.3), and R1113 and R1146 lost a cited name each.
- **R876 kept moving (43 commits).** New refusals (`SERVICE_WITHOUT_METHOD`, `SERVICE_INPUT_AS_MAP`,
  `NODE_ID_TABLE_WITHOUT_RECORD`, `MEMBER_UNRESOLVED`, `MEMBER_UNKNOWN` once at the outermost input field,
  `INPUT_CYCLE_EXPANDED` under a `@condition` bean), a class-backed field reading a record component or a
  get/is getter only (`9818f9688`, `feat!`), nested bean classes built and spelled by their source name
  (`6daf7aebb`, `6d2a5f786`), a store stamped with the model's version or code hash so an upgrade opens a
  fresh store (`f3e78efd4`, `6135f0311`), and **jOOQ embeddables** as fields of their table
  (`sql_table_field`, `sql_embeddable_column`, `cc15da5bc`, an Alf ruling on 2026-11-18 that a nested
  input under a jOOQ record binds to the embeddable it names). It also opened a node for **the generated
  client exception resolving through the code family** (`edac463f9`, an Alf ruling), which is R1168's
  plan section. The model is now **256 views and 384 tables** (256 and 382 at the prior audit).
- **R1131** replaced `GraphCountGrowthGateTest` with a plan-reading gate (`64090f7d8`) and consolidated
  `WarmStartRefreshTest`, `CodeCaptureTest` and `PersistentStoreTest` (`c7c5ecbe1` and siblings).
- **Effect on flags.** **None resolved.** **New:** R730 (§B.3: its gate mechanism moved into javac on
  2026-10-31 and its motivating seventh site was deleted this window), R234 (§B.3: R876 now owns
  embeddable input binding), R1108, R468 and R1159 (§B.3: the mount era), and R688/R1109 (§C.1: two
  items for one defect, its input-side twin fixed this window) and R1184 (§C.5: promoted with one name R1119 then deleted). **Sharpened:** R1168 (§C.5: its client
  exception section is now an R876 node with an Alf ruling), R1113 (`isMountFailure`), R1146
  (`emitDecodeAndGroup`), R1160 (`GraphCountGrowthGateTest`). Board-level
  structure: R1189 and R1185 still have no changelog lines, R901 is still a Done file.

**Flag totals.** Prior ~118; none resolved, eight added: **~126 flagged items: 5 §A (+R901 structural) /
~48 §B / ~72 §C**, plus two board-level structural flags. Item counts are given per cluster because many
flagged items cite the same retired names. **Nothing the prior audit recommended was actioned in the
window**: R209, R193, R213, R899, R761, R861, R758, R846, R1033, R1034, R562, R898, R931, R1188 and R901
are all still as they were, the four stale Ready items (R427, R555, R684, R730) still name
`CompletionData`, R1168 still carries its dead store name, R1146 still plans against `SlotParse`, the
R427 open question is still undecided, and neither missing changelog line (R1189, R1185) was written.

## Standing headline (unchanged): R876's dissolution of the `intent_` family and the materialization register stales about 40 Backlog and Spec items at once

- **R682 was discarded, absorbed by R876** (`planners-read-facts-emitters-read-commands`, changelog
  "Discarded"). R876 (`derived-read-cost-is-a-shape-problem`, still **In Progress**) is the single large
  model-level item. It has retired the `intent_` relation family (7 `intent_*` names remain in
  `graphitron-model.sql` against the 100+ the board cites), `meta_materialize` and its `_live`
  registration views (R955: the register is gone, `meta_materialize` is `grep`=0),
  `MaterializeDependencies`, `MaterializeRegistryGateTest`, `CompletionData`, the `@sourceRow` directive
  (`SourceRowDirectiveResolver`, `LifterRef`, `BatchKeyLifterDirectiveResolver` are `grep`=0), and the
  `*ShadowTest` family; the 2026-11-13 window retired the scope-table chain, the runtime connection
  vocabulary relations and `graphitron_routine_entry`, the 2026-11-16 window `graphitron_entry_defect_rule`,
  `graphitron_node_type`, `SlotParse` and the argMapping call relations, and the 2026-11-17 window
  `code_method_parameter_table`. `DerivationStratum` is still present (8 Java files, unchanged) and is the
  next thing it dissolves. This window's R876 work was again mostly additive (refusals, the accessor rule,
  embeddables, store versioning), so it staled little by removal; it reached filed items by **taking
  their subject into its own plan** instead (R234's embeddables, R1168's client exception, R722's
  transaction, the last of which was discarded properly).
- **R1163 is Done** and its changelog entry states the direction: the strata account is the
  gatherers' capture and anchor phases. Items that cite "stratum" or the `intent_` names as live
  mechanisms are the stale set.

## Status snapshot (381 item files)

[`roadmap/README.md`](../README.md) is the roll-up. By `status:` front-matter: Backlog 333, Spec 32,
Ready 10, In Progress 5, In Review 0, Done 1 (the Done one is a tombstone, §D).

- **In Progress:** R876 (`derived-read-cost-is-a-shape-problem`), R1113 (`transactional-outbox-directive`),
  R1119 (`signed-reference-entities-across-subgraphs`), R1131 (`clean-build-under-five-minutes`), R1160
  (`tests-read-shared-fact-stores`).
- **In Review:** none. R920 and R1180 both passed independent-session Done reviews this window.
- **Ready:** R427, R467, R555, R684, R730, R838, R1137, R1167, R1168, and **R1184**
  (`database-policy-gatherer`, Spec -> Ready `e4f709ab7`). Six of the ten carry flags (§B.3, §C.5).

## A. Obsolete: should leave the active roadmap

| Item | Status | Why obsolete | Recommended action |
|---|---|---|---|
| **R209** field-registry-typed-rejection-trace | Backlog | **Carried, fifth audit running.** Entire deliverable (remove the `RejectionKind.AUTHOR_ERROR` default arm, emit `RejectionKind.of(rejection)`) shipped by R585; nothing remains. | **Discard**, recording R585 as the delivery vehicle. |
| **R899** read-budget-with-nothing-materialized | Spec | The item's own demotion note says it "may no longer have a subject" if R876 empties the register. R955 did: `meta_materialize` is `grep`=0 and no registration exists to price. | **Discard**, recording R955. |
| **R761** materialize-dependency-derived-before-stamp | Backlog | Subject is the `meta_materialize_dependency` relation and its stamping order; both gone with the register. | **Discard**, recording R955. |
| **R830** meta-relation-reference-inlined-key-projection | Backlog | Costs and names `meta_materialize` / `meta_materialize_dependency` / `MaterializeDependencies`, all gone. | **Re-measure `meta_relation_reference` on the current store; discard if the 153 ms figure does not reproduce**, else re-spec without the register vocabulary. |
| **R941** owner-read-gate-skips-registered-rule-bodies | Spec | Premise is "registering a relation changes which gates read its rule"; registration and `_live` names are gone. `MetaDeclarationGateTest` may survive, the registration seam does not. | **Discard**, unless the gate's body-reading blind spot still exists on `graphitron_` tables (re-derive first). |
| **R942** per-row-view-naming-fails-the-build | Spec | Goal defined entirely in terms of registrations and `_live` names; 46 of 60 cited names are gone. | **Re-derive**: if unregistered-view naming is still a real hazard keep one sentence of it, else **discard**. |
| **R1034** lsp-nodeid-typename-accepts-every-decode-target | Backlog | **Carried from 2026-11-16, unchanged.** Goal: the LSP stops flagging a `@nodeId(typeName: T)` the build accepts, reading the build's node set instead of `graphitron_node_entry` (the authored-`@node` family, now `grep`=0). R876's `d2192619` (2026-11-13) did exactly that: `DiagnosticFacts` reads `graphitron_node` (authored and inferred nodes) and the containers, and `Diagnostics.judgeNodeTypeName`'s message no longer says the type must carry `@node`; it names a `@node` type, an `implements Node` type over node metadata, or an interface or union over nodes. The one population left over, a `@table` type without `Node` over catalog node metadata, is the shape R876 now refuses (`NODE_ID_TYPE_NAME_UNDECLARED_STUB`, see R1033 in §B.2), so the LSP flagging it agrees with the build. `5e847269` refreshed one relation name in the body but not the Goal. | **Discard, recording `d2192619`**, together with R1033. |
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
| **R938** view-count-is-paid-at-every-store-open | Backlog | Counts 120 views and `deriveDependencies`; registration derivation is gone, and the view count has moved the other way: **161 three audits ago, 250 two audits ago, 256 at the prior audit and now** (the 2026-11-14/15 split of `graphitron_entry_defect_rule` into per-stage rule views, then the 2026-11-16 refusal views; tables 317 to 376 to 382 to 384, the last two `sql_table_field` and `sql_embeddable_column`). The cost question is sharper, not dissolved, and R1131's C1 now owns a view-on-view node ordered by execution cost. | **Re-measure against R1131's 2026-11-15 profile, then re-spec or fold into R1131.** |
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
| **R1033** nodeid-census-of-table-only-targets | Backlog | **Carried from 2026-11-12, reinforced.** Goal is build-time guarantees for a `@nodeId(typeName: T)` whose `T` is a `@table` type without `@node`, decoded from catalog metadata. R876 made that shape a refusal: `288e993` and `9600dc4` remove the walk's catalog decode and `GraphitronSchema.catalogNodeDecodes`, and the store refuses the target as `NODE_ID_TYPE_NAME_UNDECLARED_STUB`; a declared stub is the supported form and gets its encode and decode from `graphitron_node`. `graphitron_node_id_instruction_rule` and `resolved_type_kind`, which its scope notes build on, are also `grep`=0. The walk's stub path itself has since gone (`a0d52c2`: a node this subgraph does not resolve is a `NodeType` carrying `resolvable`) and every `@node` implements `Node` (`51f5901`); `NODE_ID_TYPE_NAME_UNDECLARED_STUB` is still the refusal. The 2026-11-16 window's `5e847269` refreshed its `graphitron_node_type` cite to `graphitron_node` without touching the Goal, and its LSP sibling R1034 was delivered by `d2192619` (§A). | **Discard, recording `288e993` and `9600dc4`**, in the same commit as R1034, unless the owner wants the undeclared-stub refusal relaxed, which would be a new item stated against `graphitron_node`. |
| **R562** synthesised-connection-fields-as-coordinates | Backlog | **Carried, sharpened.** Premise is that a synthesised connection's `totalCount` and `facets` "exist only as emit" with nothing in the fact base. R876 then stored the connection (`graphitron_connection`, `graphitron_edge`, `6be2bbb`, `f91a76d`) and registered every connection, `PageInfo` and facets field (`f3f6285`, `a52cbf8`, `9ebdc2f`); `ExemptionRegistry.OPERATION_KNOWN_GAPS`, the gap it measures by, is `grep`=0. Next the positions a connection's fields fill, `TOTAL_COUNT` and `FACETS` among them, became stored and read (`7cfa62c`), `ConnectionsQuery.byField` reads them, and every connection is a class `ConnectionsEmitter` renders from the store (`238f6ac`); the `ConnectionResult` carrier its body names is gone. | **Close as absorbed by R876** (Discard, recording `7cfa62c` and `238f6ac`) after a one-line check that no reader still treats `totalCount`/`facets` as emit-only; any remainder (the facets derivation) is already R606's. |

### B.3 Recent windows: work landed against a filed item, or its mechanism moved under it

| Item | Status | What changed | Recommended action |
|---|---|---|---|
| **R931** synthesised-type-exact-name-collision | Backlog | Goal: an exact name collision between a type `@asConnection` mints and another registered type is a typed rejection, not last-registration-wins in `ConnectionPromoter.registerSynthesised` (schema-transform side) and "a silently misrouted mint" (capture side). R876's `29a3082fc` delivered the capture side: a name minted more than one way is `MINTED_NAME_CONTESTED` at each carrier, stored as `graphitron_minted_conflict` and never withheld silently, and a declared type that falls short of what would be minted is `MINTED_TYPE_DECLARED_SHORT` (a conforming declared type is accepted by design). `ConnectionPromoter.registerSynthesised` still exists on the walk's side, which R876 is dissolving; last edited 2026-09-07. | **Re-derive against `29a3082fc`**: if the walk-side last-wins cannot be reached once the store refuses the schema first, **discard, recording `29a3082fc`**; otherwise narrow the Goal to the walk side. |
| **R1188** column-coercion-cost-is-bounded | Backlog | Filed 2026-11-16 with two halves. The first, a digit and magnitude bound before any arithmetic in `ColumnValues`, **landed** as `9e78856a2` (`ColumnValues.inRange`, gates waived by the user) and the body's progress note says so. The second, a configurable limit on how many representations or ids `_entities` and `Query.nodes` take, is open with Spec-level questions recorded (where the limit is configured, one limit or two, a finite default refusing requests that succeed today). The Goal still states both halves, and the status says no work has started. | **Narrow the Goal to the representation limit** (move the landed bound to a "Delivered" note, or record it as its own changelog line) and **move to Spec**, since the open questions are already Spec questions. Every tree name it cites resolves. |
| **R730** capture-api-residue-after-nodehood-move | **Ready** | **New.** The item is two things: fix the dangling `{@link}`s in test sources, then extend "the gate" to test sources. Its whole account of the gate ("the `check-link-references` execution of `maven-javadoc-plugin`", the `pom.xml:349` `sourcepath` trap, the `show` level trap, the eleven-reference measurement) describes a mechanism that **no longer exists**: R1131's `1a4c16923` (2026-10-31) moved the gate into javac as `-Xdoclint:reference/protected` on `default-compile`, and the pom comment now says "Main compile only ... The gate never covered test sources". Its motivating seventh site, `WarmStartRefreshTest.aWarmRefreshOverAMultiPackageCatalogCompletes`, was **deleted** this window (`c7c5ecbe1`), and the six dead `NodeDeclaration` imports it lists are at old paths (`graphitron/.../capture/SdlFactCapture.java` now lives in `graphitron-model`). The goal survives and is now cheaper: one `default-testCompile` execution taking `${graphitron.doclint}`, plus whatever that surfaces. | **Move back to Spec and re-spec against the javac gate**: re-measure the test-source backlog with `-Xdoclint:reference` on `testCompile`, drop the javadoc-plugin traps section, drop the deleted seventh site, and decide the `/protected` scope question for package-private JUnit classes (the pom comment calls widening "a separate decision"). |
| **R234** jooq-embedded-and-udt-input-backings | Backlog | **New.** Wants jOOQ embedded and UDT records admitted as non-table input backings. R876 took the embeddable half into its plan this window: an embeddable is now an `EMBEDDABLE` field of `sql_table_field` with `sql_embeddable_column` (`cc15da5bc`), `COLUMN_UNRESOLVED` under an input named like an embeddable names it (`d7515b749`), and the node "A nested input under a jOOQ record binds to the embeddable it names" is open on Alf rulings of 2026-11-17 and 2026-11-18. Its cited code, `TypeBuilder.buildNonTableInputType` (`TypeBuilder.java:1688`), is `grep`=0; last edited 2026-07-13. | **Narrow to UDT records** (or discard if UDTs have no consumer), recording that embeddable binding is R876's node; restate the "today" paragraph against the store's `sql_table_field`. |
| **R1108** manual-catches-up-with-owned-connection-engine | Backlog | **New.** A docs audit taken on 2026-10-27 against the mount-era runtime: `Graphitron.runtime(dataSource, dialect)`, "identity mounting", `SessionMountException`, "A throwing `mount` evicts the connection". R1119 slices 8c.2 and 8c.3 (`e3a3ecf68`, `abe204f25`) replaced that runtime with a `ConnectionSource` the request is given, retired `<mount>`/`<unmount>` (`SessionStateBinding` keeps them only to refuse them), deleted `SessionMountException` and `GraphitronSessionHook`, and rewrote `runtime-api.adoc` and `mojo-configuration.adoc` in passing (both now document `ConnectionSource` and `ConnectionSources.mounting`). Several findings are therefore fixed and several describe code that is gone. | **Re-audit the manual against trunk after R1119's slice 8c** and rewrite the findings list; likely much smaller. Consider folding the remainder into R1119's before-Done documentation bullets. |
| **R468** oracle-ras-session-hook-execution-coverage | Backlog | **New.** States that session identity "mounts through the `<sessionState>` method hooks (a static `<mount>` method ... passed to `<unmount>`)". Those plugin hooks are retired (R1119 `e3a3ecf68`); mounting is now the consumer's `ConnectionSources.mounting`, so the code an Oracle execution test would cover is consumer-side wiring around a generated source. | **Re-spec** against `ConnectionSources.mounting`, or **discard** if Oracle RAS coverage is now a consumer concern rather than generated code. |
| **R1159** evicted-connection-returns-to-pool-dead | Backlog | **New.** Title and premise are "a rejected mount poisons the pool" through `PinnedConnection.evict`. The mount is now the consumer source's (`ConnectionSources.mounting`) and Graphitron "owns each lease's lifecycle" through `release()`/`abort()` (`ConnectionRuntimeClassGenerator`); `abort()` is still how a lease that cannot be settled is discarded, so the Agroal hazard it found may well survive on the settle path. | **Re-derive**: reproduce on the current lease (`abort()` after a failed settle) and retitle off "rejected mount"; discard if the lease path no longer reaches `Connection.abort` on a pooled wrapper. |

## C. Outdated: update references only (work valid, refs stale)

Substance intact; names and line cites drifted. Clusters, with the items each hits:

**C.1 R876-retired names in otherwise-valid items (refresh the cite, keep the plan).**
R846 (see §A), R900 (`argmapping-relation-names-standardise`, `intent_argmapping_binding_leaf`), R934
(`roadmap-bodies-cite-unchecked-store-names`: **its own subject is this very drift**; it should be
Done-or-superseded once the sweep below is run), R961 (`converted-relations-prose-still-calls-them-views`,
`UnregisteredRelationTest`), R854 (`jvm_method_parameter`), R626, R729, R973, R984
(`intent_record_slot_assignable`; the 2026-11-16 window's `744376c8` made the store-fed emitter decode an id of several
node types into the one record slot of whichever member it names, at a `@service` parameter or bean member,
so R984's premise that such a schema "is refused" is partly gone; the per-member overload dispatch it asks
for is still not built, and `BuildContext.admitPolymorphicSlotType` survives only on the walk's side, so
restate the "today" paragraph against `744376c8`), R736, R807, R632 (`intent_authored_claim_conflict`). **R534** (`record-key-decode-null-write-erasure`): retitled (`8bab7ae`) to name any later `fromArray` load and given a sis consumer report, so its premise is live and better evidenced; its opening paragraph still cites `customerUpsert_explicitNullNestedLeaf_collapsesToOmitted`, renamed on 2026-11-10 to `..._besideADecodedNodeId_writesNull`. Action: **drop the old test name and narrow the Goal to the classified-emitter path** its progress note says is left. **R1000** (`upsert-names-its-conflict-target`): cites `EmittedAnchor.arguments` as a twin site R998 converts; `EmittedAnchor` was removed (`dbf5acf`, `graphitron_type` is written by macro expansion) and `graphitron_field_path` was already gone. Action: **re-count the upsert sites** (34 files still call `onDuplicateKeyUpdate`) and drop the two dead names; the guard it proposes is unaffected. **R476** (`connection-helper-totalcount-redaction`): the gap is still real, since `ConnectionsEmitter` routes only the `FACETS` member through `ErrorRouter` and the `TOTAL_COUNT` member is still a bare `totalCount(env)` call, but the template it points at, `ConnectionFetcherClassGenerator.facetsDelegate`, was deleted with the per-type connection fetchers (`238f6ac`). Action: **repoint the template to `ConnectionsEmitter`'s facets arm**; the fix is now one arm in one emitter. **R521** (`generated-output-hygiene-sweep`): three bullets describe `ConnectionResult` (its 11-argument constructor, the unread `beforeCursor`, its multitenant variant); the class is gone and connections are store-rendered classes. Action: **strike those bullets or restate them against the generated connection classes**; the rest of the sweep is unaffected. **Note, not a flag: R606** (`facets-container-derives-from-the-store`) is named by `a52cbf8` as the item that retires the new `Facets` command row, so it is live; its opening claim that the facets shapes "exist only in the assembled-schema synthesis" is one paragraph out of date and should mention that row when next edited.


**C.1b Two items for one defect, its input-side twin fixed this window.** **R688**
(`nested-backing-class-binary-name-in-emit`, Backlog, 2026-08-17) and **R1109**
(`nested-record-class-renders-binary-name`, Backlog, 2026-10-27) describe the same bug: a nested result
class reaches an emit site as its binary `Outer$Inner` name through `ClassName.bestGuess`, so generated
fetchers do not compile. R1109 was filed without naming R688. This window R876's `6d2a5f786` fixed the
same mistake on the input side (`InputBeanResolver` now spells a nested bean with `ClassName.get`) and
`6daf7aebb` made the capture build nested record beans, but neither touched the output emit seats
(`FetcherEmitter` still exists; `bestGuess` is still called in 10 `emit/` and `plan/` classes). Action:
**discard R1109 into R688** (keeping R1109's MutationScopeService reproduction), and restate R688's
"today" against the store-rendered emitters and the `6d2a5f786` precedent.
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
| **R1168** shadowed-error-handler-is-reported | Ready | **Carried, sharpened again.** Two of its plan sections are now out of step with the tree. (1) Its "Platform throwables" section plans a capture so that `java.lang.RuntimeException` and other JDK throwables have `code_throwable` and `code_throwable_supertype` rows; R876's `8cd9d91a6` (2026-11-16) **delivered that** (the classpath gatherer reads `jrt:/` as a source of kind `PLATFORM`). (2) Its plan for the generated `GraphitronClientException` says "`graphitron_runtime_class` is the precedent ... add a kind for it"; `graphitron_runtime_class` is still `grep`=0, and this window **R876 opened a node for exactly this question** (`edac463f9`, "The client exception resolves through the code family, as a class the run is about to write", Alf, 2026-11-17: the model names no class the generator emits; the run hands its own classes to the code capture, pending a ruling on whether the code family may hold an uncompiled class), with `scenarios/error-handler-client-exception` pinning the current behaviour (`b0bdce2c7`). | **Move back to Spec**: strike the platform-capture section as delivered by `8cd9d91a6`, replace the client-exception section with a dependency on R876's node (or scope the shadowing rule to handlers whose class the code family already holds), and consult the principles-architect. The shadowing rule itself stands. |
| **R427** relevance-ranked-search | Ready | `CompletionData`, `SearchBacking`, ... (19 of 59 names gone). | **Re-spec the Current state section** before pickup. |
| **R555** deprecate-externalfield-fold-into-service | Ready | `CompletionData`, `ClasspathScanner`, `PkLessParent`, `AuthoredClaimConflicts`. | Refresh cites before pickup. |
| **R684** consumers-share-relations-not-queries | Ready | `CatalogFacts`, `LspSchemaSnapshot`, `CompletionData`. | Refresh cites; part of its premise (CatalogFacts retirement) shipped. |
| **R730** capture-api-residue-after-nodehood-move | Ready | `CompletionData` retired by R876; more importantly the gate it extends moved into javac and its seventh site was deleted. | See §B.3: **back to Spec**. |
| **R1146** node-lookup-invalid-id-returns-null | Spec | **Carried, sharpened.** The defect is live (`GraphQLQueryTest.node_garbageInput_returnsNull` still pins `null` with no error, and `QueryNodeFetcherClassGenerator`, `NodeIdEncoder.decodeColumns` and `peekTypeId` all still exist), but its plan has the entity lookup run `decodeColumns` "with that entity's `SlotParse.parseClasses`" (`SlotParse` removed by `cee54e24`, 2026-11-13: a node id now decodes through a converter-backed key column), and extracts "the `decodeColumns` call that `HandleMethodBody.emitDecodeAndGroup` emits" into a shared fragment; **`emitDecodeAndGroup` was removed this window** by R1119's `a871d0730` (entities and nodes load through DataLoaders per type, key alternative and connection key). | **Restate the plan against the converter-backed decode and the per-type DataLoader entity load before Spec -> Ready**; the Goal and the outcome table are unaffected. |
| **R427** (addendum) | Ready | **Carried, still undecided:** `28c66dd` (2026-11-12) added an open question raised from R876's connection thread, "Does a hit being an edge reopen decision 1?", marked not decided. | A Ready item carrying an undecided question that may reopen a spec decision should **go back to Spec until it is answered**, or the question be ruled out of scope in the body. |
| **R1113** transactional-outbox-directive | In Progress | **Carried, sharpened.** The R876 overlap is answered in the body. Two live cites are stale: the Rejections bullet still cites the `DefaultPageSizeDefects` precedent (`grep`=0 since the defect rules moved to the store), and Phase 4's failure-outcome plan places the classification "in the emitted runtime, beside `isMountFailure`", which R1119's `e3a3ecf68` renamed to `isAcquisitionFailure`; the same section says "`acquire` calls the `DataSource`", where a request's connections now come from the `ConnectionSource` it is given. Phase 1b's "mounted handle" wording describes the retired plugin `<mount>`. The `SessionHooks` and `ServiceCatalog.*` names sit in review rounds, which are history. | **Restate the precedent and repoint Phase 4 at the connection-source runtime** (`isAcquisitionFailure`, `ConnectionSource.acquire`) when the author next edits it. |
| **R1160** tests-read-shared-fact-stores | In Progress | **Carried, sharpened.** `ServiceEmissionCensusTest` absent; its `@capability` / `@exemplifies` header vocabulary is not in the tree while the body still uses it. Its plan still lists `GraphCountGrowthGateTest` as a store holding chosen graphs; R1131's `64090f7d8` replaced that test with a plan-reading gate this window. | Minor; author is mid-flight, refresh when next edited. |
| **R1184** database-policy-gatherer | Ready | **New, minor.** Promoted this window; every other unresolved name is a proposal, a PostgreSQL catalog name or an environment variable. One line (setting names checked "against the `SessionState` mount") names the runtime class R1119's slice 8c.2 deleted; session settings are now written by the consumer's `ConnectionSources.mounting`. | Repoint the sentence at `<sessionState>`'s handle type and `ConnectionSources.mounting` at pickup. |

**Carried resolution: R1167** (`error-dispatch-matches-graphitron-9`, Ready) stays clean: the
`graphitron_ast_error_database_handler_entry` relation its sentence names was captured again by R876's
`56433aef6` (2026-11-16) and is still present. Its scope (dispatch order) was not touched by this
window's accessor-rule change to `ERROR_FIELD_UNREADABLE` (`a2e20cc11`), which judges how a field is
read, not which handler claims a throw.

R467 (`upgrade-graphql-java-26`), R1137 (`rejected-type-cascades-into-secondary-diagnostics`), R1119 and
R1131 are clean, as are the Spec item R1169, the carried Backlog items R1183, R1186, R1191 (`maxRentalRate`
and `RequestNumberBoundsTest` resolve; `filmsByWireIdCondition` is generated from `multitenant.graphqls`),
R1192 (its "one lease per distinct connection key, released when the operation ends" is exactly what
R1119's `e3a3ecf68` shipped, so the premise is now confirmed by code) and R1193 (`OutboxFailureSettleTest`
and `timedOutTenants` resolve), and both of this window's new items: **R1196** (`GlobalRead`,
`DefaultSource`, `dslDefault` and `GraphitronConnectionInstrumentationGenerator` all resolve, and the
default tenant whose removal it follows is gone) and **R1198** (no tree names). R505
(`tenant-index-parent-row-routing`) was edited this window to drop its default-tenant bullet and is
current. R1181 (`infinity-in-a-date-or-timestamp-order-column`) dropped its `depends-on` to the shipped
R1180 and now names `ColumnValues.writtenByDatabase` and `ConnectionHelper.cursorTexts`, both present.
R1119 cites `TenantId`, `SessionState`, `mountCauseFinder`, `rejectedIdentity`, `@defaultTenant` and the
rest of its slice 8c removals only in its rulings, slice table and retired-vocabulary list; R1131 and
R876 cite `GraphCountGrowthGateTest` only in progress notes recording its replacement; that is history,
not drift.

## D. Structural

- **R901 `trailing-segments-is-a-boolean` is `status: Done` in `roadmap/*.md`** with a `depends-on:
  [derived-read-cost-is-a-shape-problem]` edge, last updated 2026-09-03, and has **no changelog entry**.
  The workflow deletes the file on Done and records it in `changelog.md`. Its body says "Resolved by R876: it
  was a count of nothing, and it is a name now". Recommended action: finish the transition (changelog
  entry, delete the file) or, if it is intentionally parked behind R876, say so in its body and move it
  back to a non-Done status.
- **`next-id` is ahead of every id.** The counter is `R1199` and the highest id on the board is R1198
  (R1194, R1195 and R1197 were allocated and discarded inside the window, each with a changelog line).
  R1182 still has no file (absorbed into R876 on allocation), a harmless gap.
- **Carried: two items left the board with no changelog line, still unwritten.** The workflow records
  every Done and every discard in `changelog.md`, and the changelog is the only place a removed item's
  outcome survives. The window wrote nine changelog lines (R920, R1180, R1187 Done; R722, R1194, R1195,
  R1197 discarded), every one of them correctly, but did not backfill these two:
  - **R1189** (`condition-directive-reading-consolidation`): `1b7287117` (`chore(R1189): Backlog -> Done`)
    deleted the file and regenerated the README, and nothing else. The delivery is `30600d073` (`feat!`: a
    `@condition` naming no class or no method fails the build at the directive). Compare R1190 and this
    window's R1187, which also skipped the gates at the user's direction and do carry their lines.
  - **R1185** (`connection-hook-takes-the-key`): `a1d9bb43a` (`chore(R1119): the plan absorbs R1185 ...`)
    deleted the file and recorded the absorption in R1119's body, but wrote no "Discarded" line, unlike
    R1050, R1194 and R1195, each absorbed with one.

  Recommended action: **add the two missing changelog lines** in the next roadmap commit (a Done line for
  R1189 naming `30600d073` and noting the waived gates; a Discarded line for R1185 naming R1119).
- Fourteen items carry a non-empty `depends-on:`, all resolving to existing files (R1181 dropped its edge
  to the shipped R1180 this window; no item depended on R722, R920, R1187 or the three same-day discards).
  Six of the fourteen depend on R876 (`derived-read-cost-is-a-shape-problem`), which means **R876 is a
  single point of staleness**: any design change there cascades across R898, R851, R1099, R697, R1096,
  R901 and the §B.2 set. R857 depends on R872 (`warm-capture-empties-unpartitioned-catalog-relations`),
  which exists. R1192 depends on R1119, R1168 and R1171 on R1167.
- Four legacy items still lack a `bucket:` key: R242, R109, R252, R180 (unchanged from the prior audit,
  harmless; fold in when next edited).
- Numbering gaps are harmless; numbers are never reused.

## Cross-cutting observations

1. **R876 is still the root cause of most of the board's staleness and is still In Progress.** It has
   dissolved the `intent_` family, the register, `CompletionData`, `@sourceRow`, the shadow tests, the
   monolithic entry-defect rule, `graphitron_node_type`, `SlotParse` and the argMapping call relations.
   Items filed Sep to Nov (R899 to R979 especially) were written against the `intent_`/register model
   that R876 then retired inside weeks; roughly forty items name relations that do not exist. Six items
   depend on it, and the staleness is concentrated, not diffuse. **This window it staled by absorption
   rather than removal**: it took R234's embeddables, R1168's client exception and R722's transaction
   into its own plan as nodes, and only R722 was closed as it went.

2. **R1119 is now the second source of staleness, and it is the runtime's.** Its slice 8c retired the
   plugin `<mount>`/`<unmount>`, `SessionMountException`, `GraphitronSessionHook`, the default tenant and
   `@defaultTenant`, and its slice 8d replaced the entity decode path. R1119 itself handled this well: it
   declares retired vocabulary, it updated R505 in the same window, and it folded R1194, R1195 and R1197
   back with changelog lines. But five items written against the mount-era runtime were not swept (R1108,
   R468, R1159 in §B.3; R1113 and R1184 in §C.5), and one Spec item lost its emit seat (R1146). **R1119's
   retirement sweep at its Done gate should grep for `mount`, `SessionMountException`, `DataSource` and
   `default tenant` across active bodies**, which would catch all five.

3. **R934 (`roadmap-bodies-cite-unchecked-store-names`) is the board diagnosing itself.** The cheapest fix
   to this audit's biggest cluster is mechanical: a gate (or a `check-` step in the roadmap-tool) that fails
   when an item body backticks an `intent_`/`graphitron_`/`meta_` relation name that
   `graphitron-model.sql` does not define. Run as part of the build it would have prevented most of §B.2
   from drifting silently. Recommend promoting R934 to Spec. This window's new flags show its limit: R730,
   R1108 and R1159 are stale on build mechanisms and runtime classes, not on store names, so a name gate
   would not have caught them; only a periodic read against what landed does.

4. **A discard-and-absorb pattern keeps leaving stale `depends-on`/trigger text behind.** R846 still
   schedules itself for deletion when R682 reaches Done; R682 was discarded into R876, and eight active
   bodies still name R682. A sweep for `R682`, `R333`, `R222`, `R462`, `R972`, `R1174`, `R1050`, `R1185`
   and now `R722` in active item bodies would find them (today `R722` is named by no active body, and
   R1194, R1195 and R1197 only by their absorbing items, which is correct).

5. **The overdue mechanical closes keep surviving.** R193 and R213 (subsumed by R649; R585 and R589) have
   now survived nine audits; R209 (§A) is the third. Together with R1189 and R1185's missing changelog
   lines this is perhaps ten minutes of bookkeeping that would remove five recurring lines from this report.

6. **The Ready set is where stale prose bites soonest.** Of the ten Ready items, **R427, R555, R684, R730,
   R838, R1168 and R1184 carry stale cites**, and R427 also carries an undecided question; R467, R1137 and
   R1167 are clean. Two should go back to Spec rather than be refreshed in place: **R1168** (one plan
   section delivered, another now an R876 node with a pending Alf ruling) and **R730** (the gate it
   extends moved into javac, so its measurement and both traps it warns about describe a deleted
   mechanism). R1184 was promoted at 07:40 UTC this window and carries one runtime name (`SessionState`) that
   R1119 deleted fifty minutes later.

7. **A reachable baseline makes the audit cheap.** This run unshallowed the clone to reach the prior
   audit's commit and diffed against it. The removed-names test was the higher-yield step this time (it
   found R1113, R1146, R730, R1160 and, through the mount vocabulary, R1108), because R1119's window was
   subtractive; the `feat`/`fix` subject sweep found R234 and R1168's new overlap, and R688/R1109 through
   the nested-class fixes. One flag, R730, was missed by every earlier audit: its staleness dates from
   2026-10-31 and is on a build mechanism no name-based check watches. Runs should keep doing both sweeps,
   and occasionally read Ready bodies end to end.

8. **R876 delivers or overturns Backlog items without closing them.** R898 was the clear case four audits
   ago (`431e604`); R852, R902, R1033, R562, R1034 and R931 followed. This window adds **R234** (embeddable
   input binding, now an R876 node) and the second half of **R1168** (the client exception, now an R876
   node). The contrast is **R722**, which R876's `8b8c50074` discarded in the same commit that opened the
   replacing node, with a changelog line: that is the pattern that keeps the board honest. The discard
   set this audit recommends (R209, R899, R761, R861, R758, R1033, R1034, R562, R898, R1109, R193/R213 after
   a residue check, and R931 after a re-derive) is now thirteen items that each need one changelog line
   and a file deletion.

9. **Gate-waived work is now recording itself properly.** The prior audit found R1189 and R1188 half
   recorded. This window's gate-waived item, R1187, carries its changelog line and says the gates were
   waived, and both same-day filing-and-folding episodes (R1194, R1195, R1197) left changelog lines. The
   two older gaps (R1189's line, R1188's narrowed Goal) are still owed.

---

_Review date: 2026-11-18._
