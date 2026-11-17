# Roadmap staleness audit: 2026-11-17

A point-in-time review of every active roadmap item under [`roadmap/`](../) against the **current**
state of the codebase on `claude/graphitron-rewrite` (HEAD `ac1ef12`, committed 2026-11-17 02:28 UTC,
audited 2026-11-17). The goal is to find items whose premise no longer holds: work already shipped,
constructs renamed or removed, dependencies that have since landed or been discarded, or specs grown
stale enough to mislead an implementer. Every flagged item carries a recommended action and the reason
for it.

This file is an analysis artifact, not a roadmap item: it lives in a subdirectory so the roadmap-tool
(which scans `roadmap/*.md` non-recursively and requires `id:` front-matter on each) ignores it, and it
is Markdown so the `check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-11-16` staleness audit, which has been deleted; only the latest
**staleness** audit is retained. The other files in this directory are provenance (spikes, censuses,
measurements, lineage notes) that shipped and active items cite by path; they are not staleness audits and
are left in place.

**Baseline and method.** The prior audit measured trunk at `d2ba1f7` (`next-id: R1186`, 380 item files).
This audit measures at `ac1ef12` (`next-id: R1194`, **383** item files, max id R1193): a window of **about
26 hours and 171 commits**, 55 of them R876's, 37 R1131's, 17 R1160's, 15 R1113's and 14 R1119's. The clone
was shallow, so `d2ba1f7` only became reachable after unshallowing; the delta below is a real commit diff:

1. Parse every `roadmap/*.md` front-matter (id, status, slug) at both commits and diff the two tables.
2. Diff `d2ba1f7..ac1ef12`: every roadmap file touched, every changelog entry added, and the set of
   identifiers present in removed lines of the non-roadmap, non-docs diff and absent from the tree now
   (**17** of them, among them `graphitron_voucher_claim`, `graphitron_accept_claim`, the two
   `graphitron_ast_*_claim_entry` relations, `code_method_parameter_table`, `MethodParameterTables` and
   `LoaderConstructionTest`). Each of the 383 item bodies was tested against that set: only R876, R1119 and
   R920 cite one, each in a progress note recording its own removal. That is history, not drift.
3. Every new item (R1187, R1188, R1191, R1192, R1193) and every item edited in the window had its
   backticked names checked against the tree. Names that do not resolve are generated-only names
   (`filmsByWireIdCondition`, emitted from `multitenant.graphqls`), Maven or advisory ids, or the item's own
   proposals.
4. Recheck the prior audit's flags. The retired names the clusters rest on were re-`grep`ped
   (`CompletionData`, `meta_materialize`, `SlotParse`, `looksLikeSourcesShape`, `TableInputType`,
   `RewriteSchemaLoaderTest`, `SourceRowDirectiveResolver`, `graphitron_runtime_class`,
   `graphitron_node_entry`, `ConnectionResult`, `DefaultPageSizeDefects`: all still `grep`=0), so the flags
   carry unless stated otherwise. One name came back: `graphitron_ast_error_database_handler_entry`
   (`56433aef6`), which resolves R1167's flag (§C.5).
5. Read the window's `feat`/`fix` commits (43 of them) for work that delivers or undercuts a filed item,
   and grep the active bodies for each one's subject (enum constants, checked exceptions, `@error` field
   accessors, `@service` returning a union or a connection, input cycles, minted-name collisions, tables
   without a primary key, unresolved `@condition` references, nested-read aliases, platform throwables).

As before, the carried flags are **symbol-evidence-backed, not a full prose read of every body**; the
recommended action for most is a re-derive that will confirm or dissolve the flag.

## What changed since the 2026-11-16 audit

- **Done:** R1189 (`condition-directive-reading-consolidation`, filed, restated and delivered inside the
  window as `30600d073`, `feat!`: a `@condition` naming no class or no method fails the build at the
  directive) and R1190 (`http-variable-number-bounds`, `d8f432762`: numbers in GraphQL-over-HTTP
  `variables` and `extensions` are bounded before execution). Both skipped Spec and the Done-gate review at
  the user's direction. R1190 has its changelog line; **R1189 has none** (§D).
- **Discarded:** R1050 (`database-ddl-at-request-time`), absorbed by R1184 on an Alf ruling, with a
  changelog line. **R1185** (`connection-hook-takes-the-key`), absorbed by R1119 as its connection contract
  (`a1d9bb43a`); the file is gone and R1119's body says it is discarded, but there is **no changelog line**
  (§D).
- **Filed (five):** R1187 (`pgjdbc-pin-through-jooq-postgres-extensions`, Backlog: the root pom has no
  `<dependencyManagement>` entry for the driver, still true), R1188 (`column-coercion-cost-is-bounded`,
  Backlog, **half delivered in the window**, §B.3), R1191 (`float-argument-on-numeric-column-cast`,
  Backlog), R1192 (`fan-out-worker-releases-its-lease`, Backlog, depends on R1119) and R1193
  (`outbox-error-tenant-timeout-end-to-end`, Backlog). R1189 and R1190 were filed and closed inside the
  window.
- **Moved:** **R1184** went back **Ready -> Spec** (`1fc12eee1`) to absorb R1050's on-request database
  definitions and was re-specced twice (`e7c6c2f41`, `2cb770744`). **R920** went **In Review -> Ready ->
  In Progress -> In Review** (`ce93b2d0d`, `b8561895d`, `5a0ceb2b3`) for a Round 3 rework that replaced
  `LoaderConstructionTest` with behaviour and emission pins (`fc64e2764`); it is In Review again from
  2026-11-16 12:36 UTC. **R1180** (Spec) took review round 1's revisions.
- **R876 kept moving (55 commits).** Mostly refusals and stored deferrals for `@service` shapes the walk
  used to keep or drop (`SERVICE_RETURN_MISMATCH`, `SERVICE_UNION_RETURN`,
  `SERVICE_CONNECTION_UNSUPPORTED`, `SERVICE_BATCH_SHAPE_UNSUPPORTED`, `SERVICE_SOURCES_NOT_KEYS`,
  `KEY_TABLE_WITHOUT_PRIMARY_KEY`, `INPUT_CYCLE_EXPANDED`, `UNROUTED_CHILD`, ten `feat!` commits among
  them), `ENUM_CONSTANT_MISSING` (`79efa9067`), `ERROR_FIELD_UNREADABLE` (`b2f574d48`),
  `CHECKED_EXCEPTION_UNHANDLED` (`cd6ef673c`), `MINTED_NAME_CONTESTED` and `MINTED_TYPE_DECLARED_SHORT`
  (`29a3082fc`), the platform's throwables captured as a source of kind `PLATFORM` (`8cd9d91a6`), and a
  DATABASE error handler's `code`, `sql_state` and `matches` captured again (`56433aef6`). The model is
  now **256 views and 382 tables** (250 and 376 at the prior audit).
- **R1119** made `claims:` into `vouchesFor:` (`18c1a921b`, `feat!`) and bound a voucher to no caller
  (`71c09176a`, `feat!`), and absorbed R1185. **R1113** landed `@outbox(on: ERROR)` (`50c9e042e`,
  `18779ff51`, `a483d1ca5`) and a connection-release fix. **R1131** recorded B1 done at 105 s.
- **Effect on flags.** **One resolved:** R1167 (§C.5; the relation its sentence names exists again). **Two
  new:** R931 (§B.3: `29a3082fc` delivered its store half) and R1188 (§B.3: Backlog with half its Goal
  shipped). **Sharpened:** R1168 (§C.5: `8cd9d91a6` delivered its "platform throwables" plan section),
  R938 (view count 256), R1113 (its R876 overlap is now written into its body, one stale precedent left).
  **One structural flag resolved** (`next-id` is ahead of every id again) and **two added** (R1189 and
  R1185 left without changelog lines).

**Flag totals.** Prior ~117; one resolved, two added: **~118 flagged items: 5 §A (+R901 structural) /
~43 §B / ~69 §C**, plus two board-level structural flags. Item counts are given per cluster because many
flagged items cite the same retired names. **Nothing the prior audit recommended was actioned in the
window**: R209, R193, R213, R899, R761, R861, R758, R846, R1033, R1034, R562, R898 and R901 are all still
as they were, the four stale Ready items (R427, R555, R684, R730) still name `CompletionData`, R1168 still
carries its dead store name, R1146 still plans against `SlotParse`, and the R427 open question is still
undecided.

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
  `graphitron_node_type`, `SlotParse` and the argMapping call relations, and this window
  `code_method_parameter_table`. `DerivationStratum` is still present (8 Java files, unchanged) and is the
  next thing it dissolves. This window's R876 work was mostly additive (refusals and stored deferrals), so
  it staled little and delivered two filed items' goals in part (R931, R1168).
- **R1163 is Done** and its changelog entry states the direction: the strata account is the
  gatherers' capture and anchor phases. Items that cite "stratum" or the `intent_` names as live
  mechanisms are the stale set.

## Status snapshot (383 item files)

[`roadmap/README.md`](../README.md) is the roll-up. By `status:` front-matter: Backlog 332, Spec 35,
Ready 9, In Progress 5, In Review 1, Done 1 (the Done one is a tombstone, §D).

- **In Progress:** R876 (`derived-read-cost-is-a-shape-problem`), R1113 (`transactional-outbox-directive`),
  R1119 (`signed-reference-entities-across-subgraphs`), R1131 (`clean-build-under-five-minutes`), R1160
  (`tests-read-shared-fact-stores`).
- **In Review:** R920 (`batch-failure-logs-once-not-per-field`). Its Done gate found a Round 3 issue, it
  went back through Ready and In Progress for the rework (`fc64e2764`), and it is In Review again from
  2026-11-16 12:36 UTC, awaiting an independent-session Done review. Its body is current (the deleted
  `LoaderConstructionTest` is cited only as the rework's own history).
- **Ready:** R427, R467, R555, R684, R730, R838, R1137, R1167, R1168. **R1184**
  (`database-policy-gatherer`) left Ready for Spec this window to absorb R1050; its re-spec is current.

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
| **R938** view-count-is-paid-at-every-store-open | Backlog | Counts 120 views and `deriveDependencies`; registration derivation is gone, and the view count has moved the other way: **161 two audits ago, 250 at the prior audit, 256 now** (the 2026-11-14/15 split of `graphitron_entry_defect_rule` into per-stage rule views, then this window's refusal views; tables 317 to 376 to 382). The cost question is sharper, not dissolved, and R1131's C1 now owns a view-on-view node ordered by execution cost. | **Re-measure against R1131's 2026-11-15 profile, then re-spec or fold into R1131.** |
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

### B.3 New this window: work landed against a filed item that still states the whole goal

| Item | Status | What changed | Recommended action |
|---|---|---|---|
| **R931** synthesised-type-exact-name-collision | Backlog | Goal: an exact name collision between a type `@asConnection` mints and another registered type is a typed rejection, not last-registration-wins in `ConnectionPromoter.registerSynthesised` (schema-transform side) and "a silently misrouted mint" (capture side). R876's `29a3082fc` delivered the capture side: a name minted more than one way is `MINTED_NAME_CONTESTED` at each carrier, stored as `graphitron_minted_conflict` and never withheld silently, and a declared type that falls short of what would be minted is `MINTED_TYPE_DECLARED_SHORT` (a conforming declared type is accepted by design). `ConnectionPromoter.registerSynthesised` still exists on the walk's side, which R876 is dissolving; last edited 2026-09-07. | **Re-derive against `29a3082fc`**: if the walk-side last-wins cannot be reached once the store refuses the schema first, **discard, recording `29a3082fc`**; otherwise narrow the Goal to the walk side. |
| **R1188** column-coercion-cost-is-bounded | Backlog | Filed 2026-11-16 with two halves. The first, a digit and magnitude bound before any arithmetic in `ColumnValues`, **landed** as `9e78856a2` (`ColumnValues.inRange`, gates waived by the user) and the body's progress note says so. The second, a configurable limit on how many representations or ids `_entities` and `Query.nodes` take, is open with Spec-level questions recorded (where the limit is configured, one limit or two, a finite default refusing requests that succeed today). The Goal still states both halves, and the status says no work has started. | **Narrow the Goal to the representation limit** (move the landed bound to a "Delivered" note, or record it as its own changelog line) and **move to Spec**, since the open questions are already Spec questions. Every tree name it cites resolves. |

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
| **R1168** shadowed-error-handler-is-reported | Ready | **Carried, sharpened.** Two of its plan sections are now out of step with the tree. (1) Its "Platform throwables" section plans a capture so that `java.lang.RuntimeException` and other JDK throwables have `code_throwable` and `code_throwable_supertype` rows; R876's `8cd9d91a6` (2026-11-16) **delivered that**: the classpath gatherer reads `jrt:/` as a source of kind `PLATFORM` and writes the public throwables of `java.base` and `java.sql` into those relations, on an Alf ruling about the source kind. (2) Its plan for `GraphitronClientException` says "`graphitron_runtime_class` is the precedent for a class no classpath declares: add a kind for it"; `238f6ac` deleted `graphitron_runtime_class`, still `grep`=0. | **Move back to Spec**: strike the platform-capture section as delivered by `8cd9d91a6` (its fact-document bullet may already be pinned by `code-enum-constant`'s `java.sql.SQLException` ancestry) and re-decide the generated client exception's home against the `PLATFORM` source kind, consulting the principles-architect. The shadowing rule itself stands. |
| **R427** relevance-ranked-search | Ready | `CompletionData`, `SearchBacking`, ... (19 of 59 names gone). | **Re-spec the Current state section** before pickup. |
| **R555** deprecate-externalfield-fold-into-service | Ready | `CompletionData`, `ClasspathScanner`, `PkLessParent`, `AuthoredClaimConflicts`. | Refresh cites before pickup. |
| **R684** consumers-share-relations-not-queries | Ready | `CatalogFacts`, `LspSchemaSnapshot`, `CompletionData`. | Refresh cites; part of its premise (CatalogFacts retirement) shipped. |
| **R730** capture-api-residue-after-nodehood-move | Ready | `CompletionData` retired by R876. This window R1131's `5666592d` repointed its four test paths to `graphitron-model` (the capture tests moved there) but left the `CompletionData` cite. | Refresh the remaining cite. |
| **R1146** node-lookup-invalid-id-returns-null | Spec | **Carried, unchanged.** The defect is live (`GraphQLQueryTest.node_garbageInput_returnsNull` still pins `null` with no error, and `QueryNodeFetcherClassGenerator`, `NodeIdEncoder.decodeColumns` and `peekTypeId` all still exist), but its plan has the entity lookup run `decodeColumns` "with that entity's `SlotParse.parseClasses`", and `SlotParse` was removed by `cee54e24` (`feat!`, 2026-11-13: a node id now decodes through a converter-backed key column, its text parsed as the column says). | **Restate that plan step against the converter-backed decode before Spec -> Ready**; the Goal and the outcome table are unaffected. |
| **R427** (addendum) | Ready | **Carried, still undecided:** `28c66dd` (2026-11-12) added an open question raised from R876's connection thread, "Does a hit being an edge reopen decision 1?", marked not decided. | A Ready item carrying an undecided question that may reopen a spec decision should **go back to Spec until it is answered**, or the question be ruled out of scope in the body. |
| **R1113** transactional-outbox-directive | In Progress | **Narrowed.** The item moved this window (15 commits: `@outbox(on: ERROR)` declared, resolved and emitted, `50c9e042e`, `18779ff51`, `a483d1ca5`) and its body now records the R876 split in so many words ("Taken over by R876 (Alf, 2026-11-08)": a service's `$result` source is R876's), so the overlap flag is answered. What is left: the live Rejections bullet still cites the `DefaultPageSizeDefects` precedent, `grep`=0 since the defect rules moved to the store. The `store_graph_session_*`, `SessionHooks` and `ServiceCatalog.*` names sit in review rounds and options, which are history. | **Restate the one precedent** as the store's configuration-sourced defect at the `@asConnection` application; nothing else. |
| **R1160** tests-read-shared-fact-stores | In Progress | **Carried.** `ServiceEmissionCensusTest` absent; its `@capability` / `@exemplifies` header vocabulary is not in the tree while the body still uses it. 17 commits this window, none touching the body. | Minor; author is mid-flight, refresh when next edited. |

**Resolved this window: R1167** (`error-dispatch-matches-graphitron-9`, Ready). The prior audit flagged its
sentence that `graphitron_ast_error_generic_handler_entry` and `graphitron_ast_error_database_handler_entry`
"already hold all of it", because `be3d52c` had removed the DATABASE handler's typed entry. R876's
`56433aef6` (2026-11-16) captured it again, with `code`, `sql_state`, `matches` and `description` columns,
so the sentence is true as written. No action. R1167's own scope (dispatch order) was not touched by the
window's error-channel refusals (`ERROR_FIELD_UNREADABLE`, `CHECKED_EXCEPTION_UNHANDLED`), which judge
other shapes.

R467 (`upgrade-graphql-java-26`), R1137 (`rejected-type-cascades-into-secondary-diagnostics`), R1119, R1131
and R920 (In Review) are clean, as are the Spec items R1169, R1180 (review round 1 revisions taken) and
**R1184** (back in Spec, re-specced this window), the carried new items R1183 and R1186, and four of this
window's five new items: **R1187** (the root pom still has no `<dependencyManagement>` entry for
`org.postgresql:postgresql`, so the premise holds), **R1191** (`maxRentalRate` and `RequestNumberBoundsTest`
resolve; `filmsByWireIdCondition` is generated from `multitenant.graphqls`), **R1192** (depends on R1119,
whose connection contract it names) and **R1193** (`OutboxFailureSettleTest` and `multitenant.graphqls`
resolve). Their "missing" names are library API or PostgreSQL catalog names, Graphitron 9 or consumer
names, generated-only names, or the item's own proposals. R1119 cites `TenantHandDown` (renamed in its own
slice 3) and now the `*_claim` relations its own `18c1a921b` renamed, R1131 `NodeIdDecodeDefectsTest` and
`graphitron_entry_defect_rule`, R1160 `CoordinateSpellingGateTest`, `GraphitronSiteAnchorAgreementTest`
and `MacroExpansionLifecycleTest`, and R920 `LoaderConstructionTest`, each in a progress note recording the
removal; that is history, not drift.

## D. Structural

- **R901 `trailing-segments-is-a-boolean` is `status: Done` in `roadmap/*.md`** with a `depends-on:
  [derived-read-cost-is-a-shape-problem]` edge, last updated 2026-09-03, and has **no changelog entry**.
  The workflow deletes the file on Done and records it in `changelog.md`. Its body says "Resolved by R876: it
  was a count of nothing, and it is a name now". Recommended action: finish the transition (changelog
  entry, delete the file) or, if it is intentionally parked behind R876, say so in its body and move it
  back to a non-Done status.
- **Resolved: `next-id` is ahead of every id again.** The prior audit found `next-id: R1186` with R1186
  live. The counter is now `R1194` and the highest id on the board is R1193, so a hand allocation can no
  longer collide. R1182 still has no file (absorbed into R876 on allocation), a harmless gap.
- **New: two items left the board with no changelog line.** The workflow records every Done and every
  discard in `changelog.md`, and the changelog is the only place a removed item's outcome survives.
  - **R1189** (`condition-directive-reading-consolidation`): `1b7287117` (`chore(R1189): Backlog -> Done`)
    deleted the file and regenerated the README, and nothing else. The delivery is `30600d073` (`feat!`: a
    `@condition` naming no class or no method fails the build at the directive). Compare R1190, which
    also skipped the gates at the user's direction and does carry its line.
  - **R1185** (`connection-hook-takes-the-key`): `a1d9bb43a` (`chore(R1119): the plan absorbs R1185 ...`)
    deleted the file and recorded the absorption in R1119's body, but wrote no "Discarded" line, unlike
    R1050, which was absorbed by R1184 the same evening with one.

  Recommended action: **add the two missing changelog lines** in the next roadmap commit (a Done line for
  R1189 naming `30600d073` and noting the waived gates; a Discarded line for R1185 naming R1119).
- Fifteen items carry a non-empty `depends-on:`, all resolving to existing files (R1192 is new, on R1119;
  no item depended on the removed R1050, R1185 or R1189). Six of the fifteen depend on R876
  (`derived-read-cost-is-a-shape-problem`), which means **R876 is a single point of staleness**: any
  design change there cascades across R898, R851, R1099, R697, R1096, R901 and the §B.2 set. R857 depends on R872 (`warm-capture-empties-unpartitioned-catalog-relations`), which exists.
- Four legacy items still lack a `bucket:` key: R242, R109, R252, R180 (unchanged from the prior audit,
  harmless; fold in when next edited).
- Numbering gaps are harmless; numbers are never reused.

## Cross-cutting observations

1. **R876 is the root cause of the board's staleness and is still In Progress.** It has dissolved the
   `intent_` family, the register, `CompletionData`, `@sourceRow` and the shadow tests, and the 2026-11-16
   window the monolithic entry-defect rule, `graphitron_node_type`, `SlotParse` and the argMapping call
   relations. Items filed Sep to Nov (R899 to R979 especially) were written against the `intent_`/register
   model that R876 then retired inside weeks. Roughly forty items name relations that do not exist. **Expect
   the same again when R876 lands its remaining nodes**; six items depend on it, and the staleness is
   concentrated, not diffuse. The pattern reaches **Ready** and **Spec** items routinely: R1167 and R1168
   (Ready) each lost a relation within hours of promotion on 2026-11-12, R1146 (Spec) lost `SlotParse`, and
   this window R1168 had a plan section delivered under it. This window also shows the flip side: R876
   restored `graphitron_ast_error_database_handler_entry` and so un-staled R1167 without anyone editing it.
   A Ready item that names store relations or generator classes should be re-checked against the tree on
   the day it is picked up.

2. **R934 (`roadmap-bodies-cite-unchecked-store-names`) is the board diagnosing itself.** The cheapest fix
   to this audit's biggest cluster is mechanical: a gate (or a `check-` step in the roadmap-tool) that fails
   when an item body backticks an `intent_`/`graphitron_`/`meta_` relation name that
   `graphitron-model.sql` does not define. Run as part of the build it would have prevented most of §B.2
   from drifting silently. R1119's `vouchesFor:` rename (`18c1a921b`) shows the other half working: the
   renamed relations survive only in the item's own history note. Recommend promoting R934 to Spec.

3. **A discard-and-absorb pattern keeps leaving stale `depends-on`/trigger text behind.** R846 still
   schedules itself for deletion when R682 reaches Done; R682 was discarded into R876. Several items name
   discarded ids as triggers. A sweep for `R682`, `R333`, `R222`, `R462`, `R972`, `R1174` and now `R1050`
   and `R1185` in active item bodies would find them (today R1050 is named only by R1184 and R1185 only by
   R1119, both as the absorbing item, which is correct).

4. **The two overdue subsumption candidates, R193 and R213, have now survived eight audits unactioned.**
   Both are mechanical closes (R649; R585 and R589). R209 (§A) is the third. Closing all three is five
   minutes of work and removes a recurring line from this report.

5. **The Ready set is where stale prose bites soonest.** Of the nine Ready items, **R427, R555, R684, R730,
   R838 and R1168 carry stale cites**, and R427 also carries an undecided question (R467, R1137 and, from
   this window, R1167 are clean). None was refreshed in the window. R1168 should go back to Spec (§C.5): one
   of its plan sections is now delivered and another names a deleted relation, and the replacement touches
   a design choice that wants a principles-architect consultation.

6. **A reachable baseline makes the audit cheap.** This run unshallowed the clone to reach the prior
   audit's commit, diffed against it, and found the one-day delta in seven items (R931, R1188, R1167,
   R1168, R1113, R938, R920) plus three board-level structural changes, rather than re-deriving 383. The
   removed-names test found 17 removed names, all cited only as history. The `feat`/`fix` subject sweep was
   the higher-yield step this time, because R876's window was additive: it found R931 and R1168 by what
   landed, not by what went. Runs should keep doing both.

7. **R876 delivers or overturns Backlog items without closing them.** R898 was the clear case three audits
   ago: its goal landed in `431e604` under R876's scope and the item is still Backlog with a `depends-on`
   edge to R876. R852 and R902 look the same, then R1033 (a refusal), R562 (stored and registered) and R1034
   (`d2192619`). This window adds **R931** (`29a3082fc`, `MINTED_NAME_CONTESTED`) and part of **R1168**
   (`8cd9d91a6`, the platform throwables). When an R876 commit lands something a filed item asked for,
   discarding or narrowing that item in the same commit keeps the board honest. The discard set this audit
   recommends (R209, R899, R761, R861, R758, R1033, R1034, R562, R898, R193/R213 after a residue check, and
   R931 after a re-derive) is now twelve items that each need one changelog line and a file deletion.

8. **Gate-waived work leaves its records half-written.** Three items this window moved outside the
   Spec, Ready and Done-gate states at the user's direction: R1190 (filed and delivered with its changelog
   line, correctly), R1189 (delivered and deleted with no changelog line, §D) and R1188 (half delivered while
   still Backlog with the whole Goal stated, §B.3). Waiving the review gates is the user's call; the
   bookkeeping the gates would have forced (a changelog line, a narrowed Goal, a status that says work has
   started) is still owed, and is what a later reader relies on to know what shipped.

---

_Review date: 2026-11-17._
