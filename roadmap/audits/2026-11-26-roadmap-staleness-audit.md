# Roadmap staleness audit: 2026-11-26

A point-in-time review of every active roadmap item under [`roadmap/`](../) against the **current**
state of the codebase on `claude/graphitron-rewrite` (HEAD `377feed`, committed 2026-11-26 02:14 UTC,
audited 2026-11-26). The goal is to find items whose premise no longer holds: work already shipped,
constructs renamed or removed, dependencies that have since landed or been discarded, or specs grown
stale enough to mislead an implementer. Every flagged item carries a recommended action and the reason
for it.

This file is an analysis artifact, not a roadmap item: it lives in a subdirectory so the roadmap-tool
(which scans `roadmap/*.md` non-recursively and requires `id:` front-matter on each) ignores it, and it
is Markdown so the `check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-11-25` staleness audit, which has been renamed into this file; only the
latest **staleness** audit is retained. The other files in this directory are provenance (spikes,
censuses, measurements, lineage notes) that shipped and active items cite by path; they are not staleness
audits and are left in place.

**Baseline and method.** The prior audit measured trunk at `c514f01` (`next-id: R1209`, 384 item files).
This audit measures at `377feed` (`next-id: R1210`, **383** item files, max id on the board R1209): a
window of **about 29 hours and 74 commits**, one of them the prior audit itself (`7fb1cfb`). Of the other
73, 40 are R876's (23 `refactor`, 5 `feat`, 2 `fix`, 6 `chore`, 2 `docs`, one each `perf` and `test`),
14 R1169's, 5 R1209's, 3 R1178's, 2 R1205's, one each R1202's (its Done), R1131's, a `fix(hooks)` and a
`test` with no item, and 5 rulings as unscoped `docs` commits. The session's clone was shallow (cut at
`29c56d5`, R1202's Done), so the run deepened it to reach `c514f01` before diffing. The delta below is a
real commit diff:

1. Parse every `roadmap/*.md` front-matter (id, status, slug) at both commits and diff the two tables.
2. Diff `c514f01..377feed`: every roadmap file touched, every changelog entry added, and the set of
   identifiers present in removed lines of the non-roadmap, non-docs diff and absent from the tree now
   (**14** candidates, against 32 last window): the walk's `LookupKey` and `NODE_ID_DISPATCH`,
   `LOOKUP_INPUT_FIELD`, `LOOKUP_KEY_CONVERTED`, `ORDER_ARGUMENT_LIST`, `ROUTINE_STEP` (the claim
   rule's pseudo-kind, `d423f29`), `AS_CONNECTION_REJECTED_NO_SPLIT` and `CONNECTION_STRUCTURE_REJECTED`
   (R1169's), `pkBearing`, `to_columns`, and four deleted test methods. Each of the 383 item bodies was
   tested against that set. **No active item cites one.**
3. The new item (R1209) and the moved or rewritten ones (R1169, R1205, R1131, R876) had their backticked
   names checked against the tree; every unresolved name is a proposal (`FixtureCloseEnforcementTest`),
   a JUnit, jOOQ or PostgreSQL name, a consumer name, or, in R1169, a deleted test its own plan names as
   "the refusal that goes".
4. Recheck the prior audit's flags. The retired names the clusters rest on were re-`grep`ped
   (`CompletionData`, `meta_materialize`, `SlotParse`, `looksLikeSourcesShape`, `TableInputType`,
   `RewriteSchemaLoaderTest`, `SourceRowDirectiveResolver`, `graphitron_runtime_class`,
   `graphitron_node_entry`, `ConnectionResult`, `DefaultPageSizeDefects`, `ServiceDirectiveResolver`,
   `JooqRecordInstantiationEmitter`, `ErrorChannelWalker`, `ConditionGlueRenderer`, `isMountFailure`,
   `GraphCountGrowthGateTest`: all still `grep`=0), and no flagged item's file was edited this window
   except R1205, so the flags carry unless stated otherwise.
5. Read the window's `feat`/`fix`/`perf` commits (14 of them), R876's refactors and its body diff, and
   the seven docs commits for work that delivers or undercuts a filed item, and grep the active bodies
   for each one's subject (a child connection always split, an `@orderBy` a client may omit, a split
   child `@routine` as a path routine, a sort key's path resolved hop by hop, the routine-write
   deferrals, participants without or with mismatched keys, a faceted connection shared by two fields,
   the deep-union ban, the editor's `argMapping` at `@routine`). With the removed-names step finding
   nothing again, **every new flag came from the subject sweep and a re-read of R876's open nodes**:
   R1179, R1114, R935, R447 and R626's move, with R557 and R938 sharpened.

As before, the carried flags are **symbol-evidence-backed, not a full prose read of every body**; the
recommended action for most is a re-derive that will confirm or dissolve the flag.

## What changed since the 2026-11-25 audit

- **Done (one): R1202** (`bare-argmapping-list-nodeid-refused-at-field-condition`), In Review -> Done
  in `29c56d5` with an independent-session review, its departures from the signed plan recorded as
  Filip's call. Its changelog line was appended at the tail of the per-item block, as that block's
  other recent entries are.
- **Discarded (one): R1178** (`child-connection-split-without-marker`), answered by Alf's 2026-11-25
  ruling that a child connection starts a query of its own whether or not it is written `@splitQuery`
  (`7545b38`, `07744f4`); its keyless-batch refusal moved to R1169 and its claim rule to R876's "a split
  child `@routine` is a path routine" (`24a4cbd`, with a changelog line). Discarded the same day the
  ruling landed, in the item's own commit, which is the practice.
- **Filed (one): R1209** (`execution-test-connection-leak`): execution tests hold a PostgreSQL
  connection per class until the JVM exits. Filed `026ccf1`, Backlog -> Spec `f64ac5b`, review round 1
  asked for revisions (`a8551b9`), Filip ruled that the class opening a fixture connection closes it,
  with `@AutoClose` and an enforcing test (`630a668`), and the spec was revised twice (`eedfdd9`,
  `7dfbbaf`). Still Spec, awaiting round 2. Clean.
- **Moved:** **R1169** (`routine-child-connection`) Spec -> Ready (`44e648d`, after review round 1's
  revisions, `fbdded5`, `306424c`) -> In Progress (`c212092`) -> In Review (`9d02018`) -> back to Ready
  on a Done-gate round (`b40fbf2`) -> In Progress (`9a0e8a6`) -> **In Review** (`0c83e2c`). It shipped
  three changes with changelog lines: a child connection is always split, `@splitQuery` there inferred
  and redundant (`46c62be`); an `@orderBy` a client may leave out states no order (`51769f3`, `feat!`);
  and a list-valued `@orderBy` never stands in for `@defaultOrder` (`7041451`, `feat!`, Alf
  2026-11-26), each with a migration and an FS-plattform count. **R1205** Backlog -> Spec (`8844d42`),
  re-probed on trunk at `29c56d5` and cross-referenced to R1139 as the prior audit asked, then its walk
  refusal removed from the plan on Filip's decision (`bc7d3c6`): **the prior flag is resolved.**
- **R876** moved more root families to the store (a lookup keyed by an input object's fields, a
  converted value or a node id, `197b6dc`; a root ordered by a key down a path or a method, `80163cf`;
  a root over several tables whose node-id argument decodes as different types, `31b6557`; a faceted
  root connection, `5f86af6`; a root connection over several tables, `7763ab3`; their fetcher edges,
  `377feed`) and an inline list ordered down a path (`56f6a2b`). It resolved a sort key's path hop by
  hop at its own grain (`45f753d`, `52a611a`, with four `SORT_KEY_PATH_*` refusals); made a split child
  `@routine` a path routine, never a `ROUTINE` producer (`d423f29`, retiring `ROUTINE_STEP`); stored the
  four routine-write shapes the emitter does not generate as deferrals (`03dc943`) and a root routine's
  misnamed parameter and a write's unread input as defects (`13632ce`), ahead of the Mutation-root
  `@routine` node R454's slice 1 waits on; moved the participant key refusals to the store (`5048a67`);
  let two fields share a faceted connection type with their own facet grouping (`02f5d99`); fixed a
  recapture after a foreign key was retargeted under its name (`f4181cd`); and split or bracketed every
  view with a union deeper than seven (`c611545` to `ea755c1`). It filed four nodes: a pooled test store
  stays near its live size (`1afb37e`), a routine step's parameter splits by what binds it (`9553290`),
  the editor's claim-conflict reads take the stored defect, and **the editor completes `argMapping` at a
  `@routine`** (`2d6bd49`), the last of which is R626's subject (§B.3).
- **R1131** recorded the deep-union measurement and a split-versus-balanced trial (`d14ce10`).
- The model is now **311 views and 417 tables** (282 and 413 at the prior audit; the 29 new views are
  almost all the deep-union splits); `LintRule` still holds 21 ids.
- **Docs rulings** entered `docs/` as seven commits: a child `@routine`'s split connection is opened on
  the walk and a batched child's fetcher flips before the projection units (`4316620`); a child
  connection starts a query of its own and a written `@splitQuery` there is redundant (`7545b38`), its
  walk exception reaching every child connection (`07744f4`); **a deep union is banned, a view's unions
  nesting at most seven deep** (`e12c5b4`, Alf 2026-11-25, *replacing* the prior window's `a0dc9fa`
  finding that splitting saves no planning); the class that opens a fixture connection closes it
  (`630a668`, Filip); a routine step's parameter splits into a column and an argument subtype
  (`9553290`); and the sort-key path refusals' outer join (`200ed33`).
- **Effect on flags.** **Resolved (one):** R1205 (§B.3), re-probed and cross-referenced as recommended.
  R1202 left the board (Done). **New (four):** R1179 (§B.3: R1169 landed the half its notes wait on,
  and the store already refuses its order argument), R1114 (§B.3: the stored sort-key path relation its
  plan proposes now exists), R935 (§B.3: absorbed by an R876 node and refused in the store since
  before this window, missed by earlier audits) and R447 (§C.1: its deferrals are stored defects, and
  no code names its slug). **Moved and sharpened:** R626 from §C.1 to §B.3 (R876
  filed a node for exactly its completions, and `13632ce` gives its diagnostics half a stored defect).
  **Sharpened:** R557 (a third `@splitQuery` position has a stored verdict), R938 (the deep-union ruling
  traded 29 more views for shallower unions, by design) and R1113 (seven days at the gate).
  Board-level: R1189 and R1185 still have no changelog lines, R901 is still a Done file, R876's
  `5fc05bd` still has no `Breaking:` line, and the `RESULT_SHAPES_CONFLICT` line still says "pending"
  (§D); no `wip` subject reached trunk.

**Flag totals.** Prior ~152; four added, one resolved: **~155 flagged items: 13 §A (+R901 structural) /
~67 §B / ~75 §C**, plus two board-level structural flags. Item counts are given per cluster because many
flagged items cite the same retired names. **Of the prior audit's recommendations, one was actioned as
written** (R1205's re-probe and R1139 cross-reference); R209, R193, R213, R899, R761, R861, R758, R846,
R1033, R1034, R562, R898, R931, R1109, R660, R695, R1139, R655, R536, R665, R274, R402, R707 and R901 are
all as they were, the Ready items R427, R555, R684 and R730 still name `CompletionData`, R730, R555 and
R1168 are still Ready, the R427 open question is still undecided, R1113 has sat In Review for seven days
with its three stale cites, and neither missing changelog line (R1189, R1185) was written.

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
  `code_method_parameter_table`. `DerivationStratum` is still present (unchanged) and is the
  next thing it dissolves. The 2026-11-18 window reached filed items by **taking their subject into its
  own plan** (R234's embeddables, R1168's client exception, R722's transaction, the last of which was
  discarded properly). The 2026-11-19 window did the same once, properly (R606), removed `@globalData`
  and the walk's override reader check, and **delivered a filed item outright without closing it**
  (R660's routine-write ordering). The 2026-11-20 window **flipped every `@service` fetcher to the store**
  (`6af820d`), retired the `$source` field sigil, and moved the first walk lint into the store as a
  warning-severity defect. The 2026-11-23 window deleted the walk's `@service` path outright (`4df4e92`,
  `93f8329`, `a5b3134`, `9a1e2a9`): the ten service leaves, `ServiceDirectiveResolver`, the service
  half of `ServiceCatalog`, `OutcomeType`, `ErrorChannelWalker`, `JooqRecordInstantiationEmitter` and
  the bean-helper queue. Items that polish that code are no longer on a condemned seat, they have no
  seat (R655, R536, R665, R274, R402, R707 in §A), and a Ready item's plan is written into it (R555, §B.3).
  **This window it flipped the filter conditions to the store** (`f34cef0`), deleting
  `ConditionGlueRenderer` and the walk's predicate model, and moved list order and the root read's
  null contract into the store (`bb949ea`, `5fc05bd`). It discarded the three items it emptied in the
  same commits (R677, R561, R903), and one Ready item's plan was again written into a deleted class
  (R1202, since re-planned at implementation and Done on 2026-11-25). **The 2026-11-24 window deleted almost
  nothing and moved six root families to the store** (lookups, `@orderBy` lists, single-table
  connections, node fetchers, discriminated and multi-table roots), which is condemnation again: the
  walk's root paths for those shapes are now code a named node deletes, and the child-side flips are
  named as their second readers. No filed item cites a root-only seat that went, so no flag follows yet.
  **The 2026-11-25 window continued the root flips and began storing what the walk's child paths
  decide** (a split child `@routine` as a path routine, a sort key's path at its grain, the
  routine-write deferrals, the participant key refusals). Again almost nothing a filed item cites was
  deleted; this time the staleness came from R876's *node list*, which filed a node for one Backlog
  item's whole subject (R626) and already carries another's (R935).
- **R1163 is Done** and its changelog entry states the direction: the strata account is the
  gatherers' capture and anchor phases. Items that cite "stratum" or the `intent_` names as live
  mechanisms are the stale set.

## Status snapshot (383 item files)

[`roadmap/README.md`](../README.md) is the roll-up. By `status:` front-matter: Backlog 331, Spec 32,
Ready 11, In Progress 5, In Review 3, Done 1 (the Done one is a tombstone, §D).

- **In Progress:** R876 (`derived-read-cost-is-a-shape-problem`), R454 (`routine-write-result-shapes`,
  slice 0 shipped, slice 1 blocked on R876's Mutation-root `@routine` node, which `03dc943` and
  `13632ce` moved closer), R1119 (`signed-reference-entities-across-subgraphs`, slice 8e.3 left), R1131
  (`clean-build-under-five-minutes`, one measurement commit this window) and R1160
  (`tests-read-shared-fact-stores`, no commit for three windows running).
- **In Review (three), each waiting on a reviewer from a different session than the implementer:**
  **R1113** (`transactional-outbox-directive`), since `fcbceef2` on 2026-11-19, **seven days with no
  review commit** and three stale cites to fix at the gate (§C.5); **R534**
  (`record-key-decode-null-write-erasure`), since `e140bfa` on 2026-11-23, clean (§C.5); and **R1169**
  (`routine-child-connection`), since `0c83e2c` on 2026-11-26, its second Done-gate attempt (§C.5).
- **Spec:** R1205 (re-probed, Questions for Alf or Filip open) and R1209 (awaiting review round 2)
  joined; R1169 left for Ready and onward.
- **Ready:** R427, R467, R555, R684, R730, R838, R1137, R1167, R1168, R1184
  (`database-policy-gatherer`) and **R1206** (`declared-dependency-class-nameable`), unchanged. **Nine of
  the eleven carry flags** (§B.3, §C.5): R427, R555, R684, R730, R838, R1167, R1168, R1184 and R1206.
  Three should go back to Spec before anyone picks them up: R555, R730 and R1168; R1206 needs one
  paragraph and its cost figures refreshed, not a reopen. No Ready item was touched this window.

## A. Obsolete: should leave the active roadmap

| Item | Status | Why obsolete | Recommended action |
|---|---|---|---|
| **R209** field-registry-typed-rejection-trace | Backlog | **Carried, seventh audit running.** Entire deliverable (remove the `RejectionKind.AUTHOR_ERROR` default arm, emit `RejectionKind.of(rejection)`) shipped by R585; nothing remains. | **Discard**, recording R585 as the delivery vehicle. |
| **R899** read-budget-with-nothing-materialized | Spec | The item's own demotion note says it "may no longer have a subject" if R876 empties the register. R955 did: `meta_materialize` is `grep`=0 and no registration exists to price. | **Discard**, recording R955. |
| **R761** materialize-dependency-derived-before-stamp | Backlog | Subject is the `meta_materialize_dependency` relation and its stamping order; both gone with the register. | **Discard**, recording R955. |
| **R830** meta-relation-reference-inlined-key-projection | Backlog | Costs and names `meta_materialize` / `meta_materialize_dependency` / `MaterializeDependencies`, all gone. | **Re-measure `meta_relation_reference` on the current store; discard if the 153 ms figure does not reproduce**, else re-spec without the register vocabulary. |
| **R941** owner-read-gate-skips-registered-rule-bodies | Spec | Premise is "registering a relation changes which gates read its rule"; registration and `_live` names are gone. `MetaDeclarationGateTest` may survive, the registration seam does not. | **Discard**, unless the gate's body-reading blind spot still exists on `graphitron_` tables (re-derive first). |
| **R942** per-row-view-naming-fails-the-build | Spec | Goal defined entirely in terms of registrations and `_live` names; 46 of 60 cited names are gone. | **Re-derive**: if unregistered-view naming is still a real hazard keep one sentence of it, else **discard**. |
| **R1034** lsp-nodeid-typename-accepts-every-decode-target | Backlog | **Carried from 2026-11-16, unchanged.** Goal: the LSP stops flagging a `@nodeId(typeName: T)` the build accepts, reading the build's node set instead of `graphitron_node_entry` (the authored-`@node` family, now `grep`=0). R876's `d2192619` (2026-11-13) did exactly that: `DiagnosticFacts` reads `graphitron_node` (authored and inferred nodes) and the containers, and `Diagnostics.judgeNodeTypeName`'s message no longer says the type must carry `@node`; it names a `@node` type, an `implements Node` type over node metadata, or an interface or union over nodes. The one population left over, a `@table` type without `Node` over catalog node metadata, is the shape R876 now refuses (`NODE_ID_TYPE_NAME_UNDECLARED_STUB`, see R1033 in §B.2), so the LSP flagging it agrees with the build. `5e847269` refreshed one relation name in the body but not the Goal. | **Discard, recording `d2192619`**, together with R1033. |
| **R660** routine-write-key-capture-unordered | Backlog | **Carried from 2026-11-19: delivered by R876.** The item's claim is that a list-returning `@routine` write "produces rows in no defined order, with no directive the author can write to fix it and no diagnostic saying so". All three are now false. The key capture reads the routine's function table `WITH ORDINALITY` and orders by it (`c80ca8159`), and a write returning its records directly is lifted along its path in the delivered order (`cf9b82795`), so the visible order is the routine's and no longer the unordered `IN` re-read's. The directive is `@defaultOrder(fields:)` on the mutation field naming columns of the routine's own result, and the diagnostic is `ROUTINE_WRITE_PLURAL_UNORDERED` (and `ROUTINE_WRITE_PLURAL_ORDER_NOT_A_COLUMN`) where a plural identifying mutation is backed by a table-returning routine with no stated order (`fc065a143`, `feat!`, Alf 2026-11-18; `graphitron_occurrence_input_order` states which producers owe it). Both of its open Spec questions (which seat carries the order; whether to narrow `requiresReFetch()`) were about the walk's `validateListRequiresOrdering`, which the store's verdict now precedes. **Sharpened 2026-11-24:** `bb949ea` deleted `validateListRequiresOrdering` and `validatePaginationRequiresOrdering` outright, stores `LIST_ORDER_UNDETERMINED` for every list wherever it is read, and says the walk's re-fetch exemption "has no store counterpart"; it also discarded R677, which this body still names as the owner of the invariant. Last edited 2026-08-13. | **Discard now, recording `c80ca8159`, `cf9b82795`, `fc065a143` and `bb949ea`.** The one-line check the prior audits asked for is answered by `bb949ea`: the walk's exemption it would have probed is gone. |
| **R695** javabean-unbound-input-field-lint | Backlog | **Carried from 2026-11-20.** Wants a *warning* where an SDL input field binds no member of a JavaBean a `@service` takes, on the premise that `InputBeanResolver.bindJavaBean` is "partial by design" and "a hard reject would break beans that are deliberately partial". The store decided the other way and as an **error**: `MEMBER_UNKNOWN` refuses "an input field nothing takes, under the class its input object is built into: it fills no member under the name it matches on ... so its value would reach nothing", and it excuses a field only where something else takes it (a filter column, a call's parameter or tenant, its own `@condition`). Since the service flip (`6af820d`) the store's verdict is the only one a service's input gets, so `bindJavaBean`'s partiality no longer decides anything. **Sharpened this window:** `a5b3134` deleted `InputBeanResolver.enrich` "and everything only it reached", and `BindingAxis` "collapses to the condition reading", so `bindJavaBean` (still present) is reached only for a `@condition`'s bean and the `@service` path the item's `LintRule` arm and `SourceLocation` threading were planned into is gone. Its record-side twin, a component no field fills, became `MEMBER_UNFILLED` on an Alf ruling to refuse. Last edited 2026-08-17. | **Discard, recording `MEMBER_UNKNOWN`**, after a one-line check with a fact document that a bean setter typo draws it. If consumers do need deliberately partial beans, that is a request to relax a stored error (demote it or excuse a shape), a different item stated against the store. |
| **R655** service-phase-split-residue | Backlog | **Moved from §B.3: the trigger the prior audit named has fired.** Three polish tasks on the walk's `@service` classification, and all three subjects were deleted this window. `ServiceDirectiveResolver` and its `classifySourcesCoordinate` went with the service leaves (`93f8329`); `ServiceCatalog.DecodedParam`, `decodeServiceMethod`, `bindServiceMethod`, `reduceClaims` and "the `ServiceSignature` form of `inferBindingsByType`" went with the walk's service decode (`a5b3134`); `ServiceCatalogTest` has no `reflectServiceMethod_` method left to rename, and `ServiceCoordinatePrecedenceTest` is gone. The one case worth keeping, a record-parent `@service` with no batch parameter returning a scalar, is the kind `f05dca2` ("the store's answer is pinned for the shapes the walk's service tests held") and `0b4afac` moved into fact documents. | **Discard now, recording `93f8329` and `a5b3134`.** If the scalar case is not among the documents `f05dca2` wrote, add it there under R876. |
| **R536** always-empty-service-channel-slots | Backlog | **Moved from §C.2, same trigger.** Wants the four `*Service*Field` variants and `FieldBuilder.buildServiceField` reshaped so an always-empty channel slot cannot be constructed. `93f8329` deleted all ten service leaves, the `ServiceField` interface, `buildServiceField` and `resolveServiceOutcomeChannel`; nine of the names its body cites are `grep`=0. The dead slot went with the variants. | **Discard, recording `93f8329`.** |
| **R665** service-batch-key-residue-pins | Backlog | **New.** Two residues from R648's Done gate: a fires-assertion for the `requireNonNull` pins on `ChildField.ServiceTableField` and `ChildField.ServiceRecordField`, and a wording nit in `FieldBuilder.resolveServiceKeySource`. Both records and the method were deleted by `93f8329` ("the batch-key source" among `FieldBuilder`'s removed builders). A child service's keys are now the store's (`SERVICE_SOURCES_NOT_KEYS`, `fe93802`; `6f86663`). Last edited 2026-08-14. | **Discard, recording `93f8329`.** Nothing of either residue has a home left. |
| **R274** outcometype-carry-success-projection | Backlog | **New.** Wants `OutcomeType` to carry its `successProjection` so the nullable-success invariant lives on the carrier, where today `FieldBuilder.resolveServiceOutcomeChannel` constructs it with `List.of()`. `OutcomeType`, `successProjection` and `resolveServiceOutcomeChannel` were all deleted by `93f8329` ("the service outcome channel"); the invariant's rejection (`NonNullableSuccessProjectionField`) survives without the carrier the item wanted to move it onto. Last edited 2026-10-22. | **Discard, recording `93f8329`**, after a one-line check that a non-null success field beside `errors` is still refused for a `@service` payload by a stored defect. If it is not, that is a refusal for R876 to state, not a carrier to reshape. |
| **R402** retire-bean-helper-queue-valueshape-roundtrip | Backlog | **New.** Wants the `ValueShape` to synthetic `CallSiteExtraction.InputBean` round trip in the bean-helper queue retired. `a5b3134` retired it by deleting both ends: `ServiceMethodCall`, its emitter and walker, "and the model only they read: `MappingEntry`, `ValueShape`, `ArgPath`", and `TypeFetcherGenerator`'s "`MethodBackedField` collectors and their bean, list-decode and polymorphic-decode plumbing" (`registerBeanHelper`, `collectBeanHelpersFromCarrier`, `convertNestedFieldBindings` and `leafForFieldBinding` are all `grep`=0). `CallSiteExtraction.InputBean` survives for the condition reading. Last edited 2026-06-30. | **Discard, recording `a5b3134`.** |
| **R707** jooq-record-param-pipeline-body-string-assertions | Backlog | **New.** Wants `JooqRecordServiceParamPipelineTest` to stop asserting on rendered method-body strings (`methodBody`, `singularHelperBodies`, three `contended...` cases). The test class was deleted by `4df4e92` with the rest of the walk's service tests (`InputBeanGroupingPipelineTest` too), the helpers it asserted on are no longer emitted by the walk, and the principle the item argues from is now stated for every emitter: "every emitted unit is compiled and called at the unit tier" (`42c979d`). Last edited 2026-08-18. | **Discard, recording `4df4e92` and `42c979d`.** |
| **R901** trailing-segments-is-a-boolean | Done | Structural: see §D. | **Finish the Done transition**: record the changelog entry and delete the file (or hold deliberately and say why). |

R520 (`table-on-input-removal-housekeeping`) stays a coherent deliberately-deferred docs tail. R846
(`authored-connection-type-scope-silence`) is a self-declared tombstone scheduled for deletion when R682
reaches Done; **R682 was discarded, so the trigger it names no longer exists.** Recommend: **delete R846
now** (its fifth site shipped as R850) or rewrite its trigger to R876.

## B. Outdated: needs re-spec (premise or targets materially changed)

### B.1 Long-carried flags (still true; recommended actions unchanged)

| Item | Status | What changed | Recommended action |
|---|---|---|---|
| **R762** census-stores-members-it-reads-by-name | Backlog | Still cites `intent_class_assignable`, `intent_type_backing(_seed)`, `jvm_declared_type_ref`: gone or reshaped by R876. **Sharpened this window:** its finding that `jvm_class_supertype` "is written every capture and read by nothing", and its open question "whether `jvm_class_supertype` is captured at all", are both overtaken: that relation is `grep`=0, and `6f772af` captures a class's declared supertypes afresh as `code_class_declared_supertype`, at a new grain, with a reader (the graph-classes stage deciding `code_graph_class.is_jooq_record`). **Sharpened 2026-11-25:** the item's thesis, members read for a class whose name is already written rather than for every class, now has a ruling and a Ready instance. Alf ruled that another corpus may decide which things a capture reads, never what a row says (`f68eef2`, `fact-model.adoc`), and that members stay source-keyed in the `code_` family with only their population following the schema; R1206 (Ready) applies that to declared jars, measuring the blanket alternative at 62% more store on opptak. In the other direction, R454's `0bc0c08` widened member reading to the generated jOOQ catalog wherever it sits (`code_method` on opptak from 4,081 to 16,821 rows), because a holder method is a fact a rule reads. The item's last measurements are from 2026-09-08. | **Re-spec against the current `code_` and `graphitron_` families as a widening of R1206's demand step** (from declared jars to reactor classes, under the `f68eef2` ruling), striking the supertype section and re-measuring the store's size after `0bc0c08`; or **discard** if R876's owners rule that reactor members stay read whole, since every directive arm and completion enumerates them. |
| **R193** service-param-classification-sealed-hierarchy | Backlog | **R649 shipped it**; `looksLikeSourcesShape`, `UnresolvedParam` `grep`=0. **Sharpened this window:** the residue check the earlier audits asked for is moot, since `a5b3134` deleted the walk's whole service parameter classification (`decodeServiceMethod`, `DecodedParam`, `ParamRole`, `ClaimedParams`, `dtoSourcesRejectionReason`, `unambiguousReachablePath`); a service's parameters are rows of `graphitron_occurrence_call_parameter`. Twelfth audit overdue. | **Discard, recording R649 and `a5b3134`.** No residue check is needed any more. |
| **R213** input-field-rejection-attribution | Backlog | R585 + R589 shipped the attribution split; `TableInputType` `grep`=0. Overdue. | **Close as subsumed** after a residue check. |
| **R72** slim-servicecatalog-to-lookup | Backlog | Premise dissolved by R649; `BatchKeyLifterDirectiveResolver` also gone. **Sharpened this window:** the largest of the three resolver-sized routines it wants moved out, `reflectServiceMethod` (~170 lines), is gone, as is `ServiceDirectiveResolver`, the other half of the duplicated message it quotes (`a5b3134`, `93f8329`). `ServiceCatalog` is still 1,755 lines, holding `reflectTableMethod`, `reflectExternalField` and the condition reflection, and the condition flip will take more of it. **2026-11-24:** the condition flip landed (`f34cef0`) as a flip of emission only; `ServiceCatalog` is unchanged at 1,755 lines and its condition reflection is still read by the walk's `ConditionResolver`. | **Hold until the walk's condition classification goes (the flip did not take it), then re-derive**: if what is left is a lookup plus `@tableMethod`, discard; otherwise re-spec against that remainder with no `ServiceCatalog.java:NNN` cites. |
| **R66** rejection-string-carrier-widening | Backlog | Phase A2 delivered by R585; A1/A3/B1/B2 survive. | **Re-spec**: strike A2, re-baseline. |
| **R34** nodeid-migration-quickfix | Backlog | Driver void (R473); `SkipMismatchedElement` gone; re-anchor target `intent_node_id_instruction` is itself now reshaped by R876 (R876 has per-depth node-id bindings). | **Re-spec onto the current node-id relations**; retitle off "shim facts". |
| **R122** compound-entity-mutations | Backlog | Leans on R222's discarded `InputUsage`; `BackingClass` `grep`=0. | **Re-spec** against R876's occurrence/write model. |
| **R85** helper-emission-non-fetcher-hosts | Backlog | `QueryConditionsGenerator`, `TypeClassGenerator`, `InlineLookupTableFieldEmitter`, `EmissionContext` `grep`=0; `ArgCallEmitter.buildArgExtraction` went in the 2026-11-23 window (`93f8329`). **Sharpened 2026-11-24: likely delivered.** Its bug is a `@condition(contextArguments:)` whose generated `<Root>Conditions` method calls a `graphitronContext` helper the host class never declares. The condition flip (`f34cef0`) renders every conditions class from the store, and `aa923ad` states per method whether it takes the request context (`graphitron_occurrence_condition_signature.takes_context`), pinned by `facts/scenarios/condition-signature` with a `contextArguments` condition; under the ruling that every emitted unit is compiled at the unit tier (`42c979d`) the context is a parameter, not a helper lookup. The inline `$fields` half (`InlineTableFieldEmitter`) is the walk's projection, which still exists. | **Re-derive with one compile-tier case** (a `contextArguments` condition at the root and one inline): if both compile, **discard, recording `f34cef0` and `aa923ad`**; otherwise narrow to the inline projection seat. |
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
| **R938** view-count-is-paid-at-every-store-open | Backlog | Counts 120 views and `deriveDependencies`; registration derivation is gone, and the view count has moved the other way: **161 six audits ago, then 250, 256, 264, 268, 274, 280 and 282 now** (the 2026-11-14/15 split of `graphitron_entry_defect_rule` into per-stage rule views, the 2026-11-16 refusal views, the 2026-11-20 window's service, filter and routine-write rule views, and this window's tenant, context-key and generated-scalar rules; tables 317 to 376 to 382 to 384 to 392 to 396 to 405 to 411 to 413). **2026-11-25:** `a0dc9fa` records a measured finding (Alf agreed) that a large union rule view's cost is the plan a session keeps, about 4 to 8 MB for the two rules above 20 arms, and that splitting per arm saves no planning; that is a per-session memory figure this item's per-open framing does not have. The cost question is sharper, not dissolved, and R1131's C1 now owns a view-on-view node ordered by execution cost. **Sharpened 2026-11-20:** R1131 now reports, from the DDL alone, how hard each view is for H2 to plan (`fcb92dd`, `c6b1f57`), bisected the three widest entry-defect rules cold (`241bc01`) and wrote the research up in `2026-11-19-what-h2-spends-a-capture-on.md`, which is the measurement this item asks for, taken per view. **Sharpened 2026-11-26:** the cost question was ruled. `e12c5b4` (Alf, 2026-11-25) replaced the `a0dc9fa` finding with a ban: H2 parses a union left-deep and keeps every level's tokens, so no view may nest a union deeper than seven, gated by `ViewComplexityTest` (`ea755c1`). The eighteen deep views were split into their natural families or bracketed (`c611545` to `ea755c1`), taking the model from 282 to 311 views on purpose, and R1131 recorded the fork's live heap falling from a 3,388 MB to a 2,354 MB mean and a split-versus-balanced trial (`d14ce10`). The board's answer to "what does a view cost" is now per-session plan memory, measured, with view count traded away deliberately; this item's per-open framing has no reader left. | **Discard into R1131 now, recording `e12c5b4` and `ea755c1`** (the ruling and its gate) with the 2026-11-19 audit; if someone still suspects a per-open cost the depth bound does not cover, that is a measurement for R1131's C1 node, not an item. |
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
| **R1099** input-navigation-through-output-fields | Spec | Depends on R876; cites `intent_input_occurrence_path(_step)`, `intent_field_path_node`, `columnMapping`, and `graphitron_field_scope_table`, which `4362525` removed on 2026-11-12 with the whole scope-table chain. R876 owns input occurrence paths. **Sharpened this window:** its scope note "`$context`, replacing `contextArguments`, is a later item" is half overtaken: a `$context` pair fills a `@service` parameter (`ebee5e9`) and the `@condition` case is a named R876 node blocked on the condition flip (`7f47d21`). | **Re-spec against R876 landed work**, keep depends-on, and restate the `$context` sentence against `ebee5e9`. |
| **R1096** request-language-column-selection | Spec | Depends on R876; `LocalizedField`, `LocalizedError` are proposals, not stale, but the premise relations should be rechecked. | **Light re-check on R876 landing.** |
| **R1033** nodeid-census-of-table-only-targets | Backlog | **Carried from 2026-11-12, reinforced.** Goal is build-time guarantees for a `@nodeId(typeName: T)` whose `T` is a `@table` type without `@node`, decoded from catalog metadata. R876 made that shape a refusal: `288e993` and `9600dc4` remove the walk's catalog decode and `GraphitronSchema.catalogNodeDecodes`, and the store refuses the target as `NODE_ID_TYPE_NAME_UNDECLARED_STUB`; a declared stub is the supported form and gets its encode and decode from `graphitron_node`. `graphitron_node_id_instruction_rule` and `resolved_type_kind`, which its scope notes build on, are also `grep`=0. The walk's stub path itself has since gone (`a0d52c2`: a node this subgraph does not resolve is a `NodeType` carrying `resolvable`) and every `@node` implements `Node` (`51f5901`); `NODE_ID_TYPE_NAME_UNDECLARED_STUB` is still the refusal. The 2026-11-16 window's `5e847269` refreshed its `graphitron_node_type` cite to `graphitron_node` without touching the Goal, and its LSP sibling R1034 was delivered by `d2192619` (§A). | **Discard, recording `288e993` and `9600dc4`**, in the same commit as R1034, unless the owner wants the undeclared-stub refusal relaxed, which would be a new item stated against `graphitron_node`. |
| **R562** synthesised-connection-fields-as-coordinates | Backlog | **Carried, sharpened.** Premise is that a synthesised connection's `totalCount` and `facets` "exist only as emit" with nothing in the fact base. R876 then stored the connection (`graphitron_connection`, `graphitron_edge`, `6be2bbb`, `f91a76d`) and registered every connection, `PageInfo` and facets field (`f3f6285`, `a52cbf8`, `9ebdc2f`); `ExemptionRegistry.OPERATION_KNOWN_GAPS`, the gap it measures by, is `grep`=0. Next the positions a connection's fields fill, `TOTAL_COUNT` and `FACETS` among them, became stored and read (`7cfa62c`), `ConnectionsQuery.byField` reads them, and every connection is a class `ConnectionsEmitter` renders from the store (`238f6ac`); the `ConnectionResult` carrier its body names is gone. | **Close as absorbed by R876** (Discard, recording `7cfa62c` and `238f6ac`) after a one-line check that no reader still treats `totalCount`/`facets` as emit-only. **Sharpened 2026-11-19:** what fills a connection's `totalCount` and facets is now stored per producing occurrence (`47b273a77`), the facets records and the producers' closures read the store (`06d2583b0`), and R606, which the prior audit named as holding the remainder, was itself discarded into R876's `@asFacet` node (`6a7b9ef82`). Nothing of this item is left outside R876. |

### B.3 Recent windows: work landed against a filed item, or its mechanism moved under it

| Item | Status | What changed | Recommended action |
|---|---|---|---|
| **R931** synthesised-type-exact-name-collision | Backlog | Goal: an exact name collision between a type `@asConnection` mints and another registered type is a typed rejection, not last-registration-wins in `ConnectionPromoter.registerSynthesised` (schema-transform side) and "a silently misrouted mint" (capture side). R876's `29a3082fc` delivered the capture side: a name minted more than one way is `MINTED_NAME_CONTESTED` at each carrier, stored as `graphitron_minted_conflict` and never withheld silently, and a declared type that falls short of what would be minted is `MINTED_TYPE_DECLARED_SHORT` (a conforming declared type is accepted by design). `ConnectionPromoter.registerSynthesised` still exists on the walk's side, which R876 is dissolving; last edited 2026-09-07. | **Re-derive against `29a3082fc`**: if the walk-side last-wins cannot be reached once the store refuses the schema first, **discard, recording `29a3082fc`**; otherwise narrow the Goal to the walk side. |
| **R1188** column-coercion-cost-is-bounded | Backlog | **Carried.** Filed 2026-11-16 with two halves. The first, a digit and magnitude bound before any arithmetic in `ColumnValues`, **landed** as `9e78856a2` (`ColumnValues.inRange`, gates waived by the user) and the body's progress note says so. The second, a configurable limit on how many representations or ids `_entities` and `Query.nodes` take, is open with Spec-level questions recorded (where the limit is configured, one limit or two, a finite default refusing requests that succeed today). The Goal still states both halves, and the status says no work has started. The 2026-11-19 window's `d319968ea` extended the landed half to every client value bound at a column (`ColumnValues.fromWire`), which the body does not record either. **Sharpened 2026-11-24:** `b1f7d9c` (`feat(scalars)`, no item) bounds a document's numeric literals and any number handed to a `@scalarType` scalar at a thousand digits (`BoundedCoercing`; the document side moved onto graphql-java's own lexer in `c58f386`), the request-side twin of the landed half, also unrecorded here. **Sharpened 2026-11-25:** the progress note was refreshed (`7fb62a7`: the bound is a private precondition of every `ColumnValues` entry point, `2aad5ed`, stated once in the federation reference, `699fb94`) but the Goal and status were not. Meanwhile R1199 settled the configuration question the remaining half leaves open, by precedent: a cap on a lookup's vouched-for values is a runtime hook shaped like `BatchSizeLimit`, `VouchedValueLimit`, default 1000, failing closed below 1 (Filip, 2026-11-24, `5d12118`), and `BatchSizeLimit` already chunks `_entities`, `node` and `nodes` (`batching-model.adoc`). The architect's lean recorded in the body, a build-time parameter in a `store_graph_*` relation, now runs against that precedent. | **Narrow the Goal to the representation limit** (move the landed bound to a "Delivered" note, or record it as its own changelog line) and **move to Spec**, since the open questions are already Spec questions; in Spec, weigh a runtime limit beside `BatchSizeLimit` and `VouchedValueLimit` against the build-time parameter, and say why the two lookups would differ if they do. Every tree name it cites resolves. |
| **R730** capture-api-residue-after-nodehood-move | **Ready** | **Carried from 2026-11-18.** The item is two things: fix the dangling `{@link}`s in test sources, then extend "the gate" to test sources. Its whole account of the gate ("the `check-link-references` execution of `maven-javadoc-plugin`", the `pom.xml:349` `sourcepath` trap, the `show` level trap, the eleven-reference measurement) describes a mechanism that **no longer exists**: R1131's `1a4c16923` (2026-10-31) moved the gate into javac as `-Xdoclint:reference/protected` on `default-compile`, and the pom comment now says "Main compile only ... The gate never covered test sources". Its motivating seventh site, `WarmStartRefreshTest.aWarmRefreshOverAMultiPackageCatalogCompletes`, was **deleted** on 2026-11-17 (`c7c5ecbe1`), and the six dead `NodeDeclaration` imports it lists are at old paths (`graphitron/.../capture/SdlFactCapture.java` now lives in `graphitron-model`). The goal survives and is now cheaper: one `default-testCompile` execution taking `${graphitron.doclint}`, plus whatever that surfaces. | **Move back to Spec and re-spec against the javac gate**: re-measure the test-source backlog with `-Xdoclint:reference` on `testCompile`, drop the javadoc-plugin traps section, drop the deleted seventh site, and decide the `/protected` scope question for package-private JUnit classes (the pom comment calls widening "a separate decision"). |
| **R234** jooq-embedded-and-udt-input-backings | Backlog | **Carried from 2026-11-18.** Wants jOOQ embedded and UDT records admitted as non-table input backings. R876 took the embeddable half into its plan in the 2026-11-18 window: an embeddable is now an `EMBEDDABLE` field of `sql_table_field` with `sql_embeddable_column` (`cc15da5bc`), `COLUMN_UNRESOLVED` under an input named like an embeddable names it (`d7515b749`), and the node "A nested input under a jOOQ record binds to the embeddable it names" is open on Alf rulings of 2026-11-17 and 2026-11-18. Its cited code, `TypeBuilder.buildNonTableInputType` (`TypeBuilder.java:1688`), is `grep`=0; last edited 2026-07-13. | **Narrow to UDT records** (or discard if UDTs have no consumer), recording that embeddable binding is R876's node; restate the "today" paragraph against the store's `sql_table_field`. |
| **R1108** manual-catches-up-with-owned-connection-engine | Backlog | **Carried from 2026-11-18.** A docs audit taken on 2026-10-27 against the mount-era runtime: `Graphitron.runtime(dataSource, dialect)`, "identity mounting", `SessionMountException`, "A throwing `mount` evicts the connection". R1119 slices 8c.2 and 8c.3 (`e3a3ecf68`, `abe204f25`) replaced that runtime with a `ConnectionSource` the request is given, retired `<mount>`/`<unmount>` (`SessionStateBinding` keeps them only to refuse them), deleted `SessionMountException` and `GraphitronSessionHook`, and rewrote `runtime-api.adoc` and `mojo-configuration.adoc` in passing (both now document `ConnectionSource` and `ConnectionSources.mounting`). Several findings are therefore fixed and several describe code that is gone. | **Re-audit the manual against trunk after R1119's slice 8c** and rewrite the findings list; likely much smaller. Consider folding the remainder into R1119's before-Done documentation bullets. |
| **R468** oracle-ras-session-hook-execution-coverage | Backlog | **Carried from 2026-11-18.** States that session identity "mounts through the `<sessionState>` method hooks (a static `<mount>` method ... passed to `<unmount>`)". Those plugin hooks are retired (R1119 `e3a3ecf68`); mounting is now the consumer's `ConnectionSources.mounting`, so the code an Oracle execution test would cover is consumer-side wiring around a generated source. | **Re-spec** against `ConnectionSources.mounting`, or **discard** if Oracle RAS coverage is now a consumer concern rather than generated code. |
| **R1159** evicted-connection-returns-to-pool-dead | Backlog | **Carried from 2026-11-18.** Title and premise are "a rejected mount poisons the pool" through `PinnedConnection.evict`. The mount is now the consumer source's (`ConnectionSources.mounting`) and Graphitron "owns each lease's lifecycle" through `release()`/`abort()` (`ConnectionRuntimeClassGenerator`); `abort()` is still how a lease that cannot be settled is discarded, so the Agroal hazard it found may well survive on the settle path. | **Re-derive**: reproduce on the current lease (`abort()` after a failed settle) and retitle off "rejected mount"; discard if the lease path no longer reaches `Connection.abort` on a pooled wrapper. |
| **R1139** field-condition-override-over-unbound-argument | Backlog | **Carried, sharpened: now very likely delivered.** Goal: under a field-level `@condition(override: true)`, an argument matching no column is accepted when a parameter of the condition method reads it and rejected when none does. The store has stated both halves since the 2026-11-19 window: a filter argument naming no column is `COLUMN_UNRESOLVED` only where "no override reads it" (`95259039f`), and a slot an overriding condition leaves unread is `ARGUMENT_UNBOUND` (`9134c3673`), both read off `graphitron_occurrence_taken`. The prior audit's one open doubt was whether the walk still refused kreg's `spraakkoder` shape first. **The 2026-11-20 window's `540f078` deleted both walk refusals** (`mintCascadeVerdict`'s use-keyed verdict and `projectFilters`' refusal of an `UnboundArg`), keeping only the case of an argument with its own `@condition(override: false)`, which is not this item's. `defects/filter-column-unresolved` pins the shape: `filmsByBogusKeyAndPrefix` has a field-level override whose method reads `titlePrefix`, an argument naming no column, and only the unread `bogusKey` is refused. The check its Direction says to reuse (`SuppressedSlot`, `ReadSet`) is deleted, and the argument-level item it depends on has shipped. | **Discard, recording `9134c3673`, `95259039f` and `540f078`**, after running the item's own minimal case (`@splitQuery` with a `@reference` condition path, which the pinned fact document does not combine) as a fact document. If that combination is still refused, the refusal is a walk leftover and a node of R876, not this item's Direction. **2026-11-24:** R1205 was filed for the `@nodeId` sibling of this shape without naming R1139. **2026-11-25:** R1205 (now Spec) names R1139 and agrees it is delivered; when R1139 is discarded its changelog line should point at R1205 as the case that remains. |
| **R509** bulk-dml-payload-input-order | Backlog | **Carried from 2026-11-19.** Its product stance is that bulk mutation payloads match input order "wherever we can make that true by construction", with a lint wherever not, worked per verb (UPDATE by a threaded `idx`, INSERT by pinned engine behaviour, DELETE undecided). Alf ruled on 2026-11-18 where input order is **owed**: only on a plural identifying field, declared by `@lookupKey` on a root field or taken by an insert, a delete or a routine write with one list argument answering a list (`docs/architecture/reference/code-generation-triggers.adoc`; `graphitron_occurrence_input_order`, `fc065a143`). That settles the scope question the item leaves to Spec, makes the obligation a stored fact a refusal can read instead of a walk-side lint, and does not name bulk UPDATE among the shapes that owe it. Its mechanism cites (`TypeFetcherGenerator.emitKeysTransaction`, the `rows<Name>` companion) are walk-side. Last edited 2026-07-21. | **Re-spec against the ruling**: state each verb as "owes input order per `graphitron_occurrence_input_order`, or does not", decide whether bulk UPDATE is in, and replace the proposed lint with the store's refusal where one applies. Check the same ruling against **R242** (Spec, §C.4), whose positional-`null` contract it now partly states ("a row gone by the time it is read leaves a `null` in its slot"). |
| **R1110** single-field-over-to-many-path-returns-arbitrary-row | Backlog | **Carried from 2026-11-19, light.** The defect is live for reads (`OrderByResolver`, `ProjectionUnitRenderer` and `SplitRowsMethodEmitter` all still exist). Two things moved under it: the "target-side columns cover a candidate key" primitive it says both it and a sibling item need is now a stored fact, `graphitron_join_path_step_to_one` (`b346c4f03`), and for the routine-write case Alf ruled the second of its two "Which fix" options, a single-record field over a path that fans out is a defect, refused as `ROUTINE_WRITE_PATH_FANS_OUT` (`578eab1e7`). | **Keep; restate "Open for Spec"** to name `graphitron_join_path_step_to_one` as the to-many test and record the routine-write ruling as precedent for the read side (or say why reads differ). A good candidate to move to Spec now that the primitive exists. |
| **R592** lint-rule-reference-page | Backlog | **Carried from 2026-11-20, sharpened.** Wants a manual page for "a closed set of fifteen rules" in `LintRule`, on the grounds that `diagnostics-glossary.adoc` is error-only, "a warning has no glossary-shaped home", and a consumer configuring `<lint>` must name ids documented nowhere. `LintRule` held 22 ids at the prior audit and holds **21** now, because a second lint drained into the store this window (`validation-column-unchecked` is the stored warning `VALIDATION_COLUMN_UNCHECKED`, `8fffe9c`). More to the point, the store answered the question for its own warnings in the 2026-11-20 window: every defect code, warnings included, has a guidance fragment and an anchored entry on a reference page rendered from the store (`b489997`, with severity per entry), the editor links to it (`5684aa4`), `<disabledRules>` takes a stored warning's code beside a lint id (`5e2ab52`), and the first walk lint, `splitquery-redundant-on-record-parent`, was retired into the stored warning `SPLIT_QUERY_REDUNDANT` (`e38c49c`), with the commit saying the service-child and discriminated-interface-child lints "join it when they move". So the page it plans would document a set that is draining into one that documents itself, and the coverage meta-test it proposes (`DiagnosticsDocCoverageTest`-shaped) has a store-side equivalent already (the store refuses to create itself while a deferral has no guidance). Last edited 2026-08-04. | **Re-spec, smaller**: decide with R876's owners which `LintRule` ids are staying in Java (the nine `ENGINE` naming rules and the version-lag advisories look like they are) and document only those, on or beside the rendered defects page, so `<disabledRules>` has one place to look ids up. Discard if the ruling is that every lint becomes a stored warning. |
| **R654** resolver-coordinate-verdict-precedence-sweep | Backlog | **Carried from 2026-11-20, sharpened.** It lists four seats where a reflection or binding rejection masks a coordinate-level verdict. Two are gone, and this window removed the code under them, not only the reporting: the `@sourceRow` seat went with the directive, and the "root `@service` residue" seat went with `ServiceDirectiveResolver` and `FieldBuilder`'s four service classify branches (`93f8329`), after `f67e17b` had taken the `$source` sigil rules. A stored verdict has no reflection step ahead of it to be masked by. `@externalField` (`validateComputedField`, `ServiceCatalog.reflectExternalField`) and `@condition` with `@lookupKey` (`FieldBuilder.projectForFilter`) still resolve, though R555 folds `@externalField` into `@service` and the condition flip is R876's next. Last edited 2026-08-13. | **Re-derive the two surviving seats against the store**: for each, write the masking case as a fact document and see which message the build gives now. Narrow the item to whatever still masks, drop the `@sourceRow` and root-`@service` bullets, and discard if neither does. With the condition flip pending, the cheaper course is to **discard and hand the `@condition` with `@lookupKey` case to that flip as a document**. |
| **R555** deprecate-externalfield-fold-into-service | **Ready** | **Moved from §C.5: its plan is written into a class that no longer exists.** The item folds `@externalField` into `@service` by a "classifier fold": a `ServiceCatalog` dispatch entry returning a sealed contract classification, a `Computed` arm on `ServiceDirectiveResolver.Resolved` "placed at all four `FieldBuilder` call sites", a narrow `switch ((ServiceDirectiveResolver.Resolved.Success) resolved)`, and a fix for a non-batched child that "classifies to `ServiceRecordField` today" under `validateServiceRecordField`. `93f8329` deleted `ServiceDirectiveResolver`, `FieldBuilder`'s four service classify branches, `ServiceRecordField` and the service arms of `GraphitronSchemaValidator`; `4df4e92` made the store the only judge of a `@service` field. Nine names in its body went this window, on top of `CompletionData`, `ClasspathScanner`, `PkLessParent` and `AuthoredClaimConflicts` from earlier ones. The goal stands (one directive, a deprecation warning, the `ComputedField` path kept), and `@externalField`'s own seat (`validateComputedField`, `reflectExternalField`) is still in the walk. **Sharpened 2026-11-25: its direction now conflicts with R876's plan.** R876's Mikado node "`@externalField` is `@routine`" makes an `@externalField` method a value form bound to the parent's table, its readers moved to the form, "and the directive is deprecated later as an alias" of `@routine`, not of `@service`. The node went from `blocked` to `open` this window when R454's `a81ed52` made every method state the jOOQ call form its signature offers (execute, value, table, aggregate), which is how a `Field`-returning method is recognised. The two items now plan opposite folds of the same directive. | **Move back to Spec and re-spec against the store**: say what a `@service` whose method returns a jOOQ `Field` is in `graphitron_occurrence_producer_service` terms (a new producer kind, or a refusal that points at `@externalField` until it is one), where the deprecation is raised (`no-deprecated-directive-usage` is an `ENGINE` rule and can stay), and consult the principles-architect. A session that picks this up as written will go looking for four call sites that are gone. **Before any of that, get a ruling from Alf or Filip on the target**: if R876's node stands, **discard R555 into it** with a changelog line naming the node; if `@service` is the target, R876's node line changes instead. The board should not carry a Ready plan and an open node for opposite folds of one directive. |
| **R837** table-less-jooq-record-input | Spec | **New.** Goal: a `@service` parameter that is a jOOQ record with no table behind it generates instead of classifying fine and then failing with the wrong message. Every stage of that sentence was the walk's. Its emitter plan is "a sibling to `JooqRecordInstantiationEmitter`" (deleted, `93f8329`) fed by `ServiceMethodCallEmitter` and `MappingEntry` (deleted, `a5b3134`), with `collectJooqBindings` and `needsDslLocal` also `grep`=0. A service's record is now built by the store's `BuildEmitter.recordHelper`, and what a class is, jOOQ record or not, is decided per graph over captured supertypes (`code_graph_class.is_jooq_record`, `6f772af`). Last edited 2026-08-26. | **Re-derive, then re-spec**: write the table-less record as a fact document and see what the store says today (a build, a stored defect, or a generator bug). The Goal survives only as whatever that shows; the emitter section is rewritten against `BuildEmitter` either way. |
| **R92** catalog-check-constraint-validation | Spec | **Moved from §C.4.** Beyond the long-dead `ConstraintMapping` and `ConstraintSet`, its one integration point is gone: it adds "one additional" step at "the seam call at `TypeFetcherGenerator.java:2069`, calling `validatorPreStep` at `:2201`", and `93f8329` deleted `TypeFetcherGenerator`'s "validation pre-step" with the service fetchers. Whether a service validates is now stated once in the store and read by the emitter (`graphitron_occurrence_producer_service.validates`, `63a0a83`), and a column the validation cannot check is the stored warning `VALIDATION_COLUMN_UNCHECKED` (`8fffe9c`). R1204 also drew a line that bears on its scope: string value validation "is graphql-java-extended-validation's, under R98" (`32a1414`). Last edited 2026-08-06. | **Re-spec against the store-fed validation**: CHECK constraints as captured catalog facts joined to the columns a validating producer writes, not a second pre-step in a generator. Read it beside R98 and R170 so the three validation items share one seat. |
| **R249** nested-argmapping-syntax | Backlog | **New.** Proposes a nested `{ fieldName: <path>, ... }` right-hand side for `argMapping`, resting on "R238's carrier model already accommodates this: paths live on `ValueShape` leaves, not on `MappingEntry` entries". `a5b3134` deleted `MappingEntry`, `ValueShape`, `ArgPath` and the method-call walker; an `argMapping` is now parsed and judged in the store (`graphitron_argmapping_landing`, `ARGMAPPING_UNPARSEABLE`, `a10b7f9`). Two newer items own the neighbouring grammar: R1204 (Done) lets an entry name a generated scalar's component, and R1201 proposes Apollo's `JSONSelection` for `@outbox`'s `construct`, which already has a nested-object form. Last edited 2026-05-27. | **Re-spec or fold into R1201's grammar decision**: if `JSONSelection` is adopted for `construct`, nested `argMapping` should be the same grammar at another site, not a second mini-DSL. Discard if no consumer has asked since May. |
| **R694** service-bean-helper-dedup-by-binding-shape | Backlog | **New.** Says the `create<Bean>` helpers on a `<Type>Fetchers` class are deduplicated by bean class alone, at `InputBeanInstantiationEmitter.collectTransitively` and `TypeFetcherGenerator.registerBeanHelper`, so one bean class backing two SDL input types at two `@service` fields gets one helper. `registerBeanHelper` and `JooqRecordHelperNames` (the record-axis twin it cites as precedent) were deleted by `a5b3134`; `InputBeanInstantiationEmitter.collectTransitively` survives but no `@service` field reaches it, since a service's inputs are built by the store-fed emitter (`BuildEmitter`). Last edited 2026-08-17. | **Re-derive with one fact document** (one bean class, two input types, two services): if the store-fed build keys the helper by occurrence or shape, **discard, recording `a5b3134`**; if it collides, restate against `BuildEmitter`. |
| **R319** warn-on-pruned-unreachable-types | Backlog | **Carried from 2026-11-20, light: unblocked, not stale.** Wants a build warning naming each output type no field reaches. Its 2026-08-06 fact-base note says the deliverable "survives as a detection query over" a derived reachability relation. Both halves now exist: `graphitron_type_schema_reach` states which object, interface and union types the schema leads to (`e459c34`, Alf 2026-11-19, with reach defined as what the schema leads to, not what a result reaches), and a warning-severity defect is logged by the build and suppressible by `<disabledRules>` or `<excludedTypes>` (`8d41905`, `5e2ab52`). Its body still speaks of R279's reachability-pruned classification and R317's single-pass walk. | **Keep; restate as one stored warning** (an anti-join of captured types against `graphitron_type_schema_reach`, with a guidance fragment) and drop the R279/R317 framing. Cheap enough to move to Spec, or to hand to R876 as a node beside `SPLIT_QUERY_REDUNDANT`. |
| **R588** node-without-metadata-diagnostics | Backlog | **Carried from 2026-11-20, light.** The trap is live, but both messages it quotes are the walk's. The first, `column 'id' could not be resolved in the jOOQ table; did you mean: ...`, is now the store's `COLUMN_UNRESOLVED`, whose hint is ranked in `graphitron_entry_defect_hint` (`33d0bcf`) and so still offers the "actively harmful" candidate list; the second is now `NODE_ID_CONTAINING_TYPE_NOT_NODE`. Its plan, "a targeted rejection at the classifier", names the side that no longer reports. | **Restate against the store**: a rule that pre-empts `COLUMN_UNRESOLVED` and its hint where the type implements `Node` over a table with no node metadata, with its own guidance fragment naming `@node`. Check first whether `NODE_WITHOUT_KEY` or `NODE_METADATA_UNREADABLE` already fires there. |
| **R881** write-carriers-never-read-column-nullability | Spec | **Light, not a flag on the plan.** Promoted and rewritten on 2026-11-19 (`e0d6284`, `cdeffbb`); names resolve. Its open question 3 asks for a ruling on whether the unrouted advisory is "a sibling id, a widened `dml-write-length-unrouted`, or none", arguing that retiring a lint id breaks every consumer configuration that disables it. The same day R876 retired a lint id as a breaking change with Alf's agreement (`e38c49c`: "No consumer configures `<lint>` in FS main or this repository") and made stored warning codes suppressible through `<disabledRules>` (`5e2ab52`), which adds a fourth option the question does not list: a warning-severity defect in the store. A second lint id went the same way this window (`validation-column-unchecked`, `8fffe9c`, `feat!` with a `BREAKING-CHANGE` footer), so the precedent is now a practice. | **Add the stored-warning option and the `e38c49c` and `8fffe9c` precedents to question 3** before the Spec review; no other change. |
| **R862** preserve-enum-extraction-through-condition-rewrap | Backlog | **New.** The body already says its defect was fixed under R874 and that the item is now only coverage: "a fixture that proves a jOOQ enum column reaches an input-field `@condition` method as the enum", with the renderer arm that carried the fix named as `ConditionGlueRenderer.nestedExtraction`. That renderer was deleted by the condition flip (`f34cef0`), so the fix it would cover is no longer the code that runs; the store's `ConditionUnitRenderer` renders the call now. The coverage question is more pressing, not less: the enum case may or may not have been carried across. | **Re-target to the store's emitter**: write the enum-to-input-field-condition case as an execution-tier test against the flipped conditions class (or a fact document plus compile), and discard if R876's condition tests already include it. Strike the `ConditionGlueRenderer` paragraph either way. |
| **R776** store-query-mcp-tool | Spec | **New.** Goal: an agent can run its own SQL against the fact store through a `store.query` MCP tool, read-only by the engine rather than by parsing. Its design rests on two claims the tree no longer bears out. It adds "the rights-limited user" to `StoreReader`'s shape; the store already has one, `GraphitronModelStore.READER_USER` (`GRAPHITRON_READER`, "`SELECT` on the schema and nothing else", spelled in the DDL), which the store's reader connects as. And it says the enforcement "is available here but not on the psql door", because H2's wire server needs admin at connect; `32985a3` (`fix(dev)`, landed this window) made the console's client "a rights-limited user holding SELECT on the linked relations and nothing else", with links that "reach the store as its own select-only reader" and a per-session password file, so the psql door now holds the read-only contract structurally too. The MCP `store.console` tool's answer also changed under it (`passwordFile` in place of `password`). Last edited 2026-08-21. | **Re-spec against `READER_USER` and `32985a3`**: the tool's remaining deliverable is the statement surface (SQL from outside, the row ceiling, the answer shape), not the user. Drop the "not on the psql door" contrast and say why an agent needs the tool now that the console is select-only; if the answer is only that MCP is the agent's door, keep it small. |
| **R1179** inline-routine-child-read-surface-crashes | Backlog | **New.** Goal: a filter or an `@orderBy` argument on an unsplit `@routine` child list is answered with a located error or generated code instead of the `ChildField.TableField` constructor's internal throw (still present, `ChildField.java:445`). Two things in it moved this window. Its order half is already answered: the store refuses an `@orderBy` on a list read inside its parent's query as the deferral `ORDER_CHOICE_ON_INLINE_LIST`, before the walk throws, and R1169's plan says so ("R1179 is left with the filter"). And its notes wait on R1169 ("only once R1169 lands; R1169 rewrites that passage"): R1169 shipped its code (`46c62be`, In Review) and rewrote `routine.adoc`, which now says that on the inline list form "a `@defaultOrder` is the whole read surface". The Goal and the sample schema still present both halves as crashing. Last edited 2026-11-12. | **Narrow to the filter**: strike the `@orderBy` half as answered by `ORDER_CHOICE_ON_INLINE_LIST`, replace the "until R1169 lands" notes with what `routine.adoc` now says, and decide whether the filter case is a stored deferral (the same shape as the order one, and cheap) or a render. Priority 4 bug; still a crash for the filter. |
| **R1114** lsp-field-sort-path-landing-table | Backlog | **New.** Goal: the editor completes and checks a `FieldSort.name` beside a `path:` against the table the path lands on, instead of going silent. Its plan is mostly a store relation: "a stored `FieldSort` path relation ... its landing table derived in the anchoring phase from the captured foreign keys". R876 landed that this window at its own grain: `graphitron_occurrence_sort_key_hop` states each hop of a `PATH` sort key, forward or backward and whether it reaches one row (`45f753d`), the landing column is a row of `graphitron_occurrence_sort_key_column` (`52a611a`), the path is captured in the one path grammar (`d33dd52`), and a name off the path is the stored `SORT_KEY_COLUMN_OFF_PATH`. Last edited 2026-10-28. | **Re-spec as the editor reader only**: read the landing table from the sort-key relations (keyed by occurrence, so say how the language server maps a `FieldSort` element to it), drop the proposed relation, and let `SORT_KEY_COLUMN_OFF_PATH` be the unknown-column diagnostic rather than a second check. |
| **R935** inline-child-list-orderby-argument-not-lowered | Backlog | **New, missed by earlier audits.** "Lower a client-supplied ordering onto an inline child list, or refuse the shape", with "which of the two ships" as its question and the plan-tier throw `LauncherCommands.requireResolvedOrderingsAreLowered` as today's answer. The refusal shipped: the store states the choice and refuses the shape with the deferral `ORDER_CHOICE_ON_INLINE_LIST` (category `UNSUPPORTED`, guidance naming `@splitQuery` and `@defaultOrder`), pinned by `defects/inline-list-choice`. The lowering is an open R876 node, "An inline list is emitted from the store in its stated or chosen order, claimed by E", which renders a `NESTED` list's `CHOICE` rows as a run-time switch and deletes the throw and the deferral together. Nothing of the item is outside that node. Last edited 2026-09-08. | **Discard into R876's node**, recording `ORDER_CHOICE_ON_INLINE_LIST` as the refusal half; the node should name `LauncherOrderingClosureTest.anInlineChildListCarryingAnOrderByArgumentIsRefusedAtProduction` as the case to rewrite. |
| **R626** lsp-argmapping-routine-coordinate | Backlog | **Moved from §C.1: R876 filed its subject as a node.** Goal: `@routine(argMapping:)` gets the editor's completions and diagnostics, left side the routine's IN parameters. R876's `2d6bd49` (2026-11-25) filed "The editor completes `argMapping` at a `@routine`" (`open`, off the critical path) with the same design (left side from `sql_routine_parameter` for the routine `name:` resolves to, right side the field's arguments and the departing table's columns after `$source.`) without naming R626. The diagnostics half has a stored answer since `13632ce`: `ARGMAPPING_PARAMETER_UNKNOWN` now judges a root `@routine`'s mapping names against its IN parameters, and the editor shows stored defects. Its cites were already stale (`ArgMappingSupport.resolveMethod`, `intent_resolved_node_key_column`). | **Discard into R876's node**, with a changelog line, carrying over the two things the node lacks: the node-id dot arm (now `graphitron_node_keycolumn`, not the `intent_` name) and the `$session` right-hand literal. Otherwise two sessions will build the same completions. |

## C. Outdated: update references only (work valid, refs stale)

Substance intact; names and line cites drifted. Clusters, with the items each hits:

**C.1 R876-retired names in otherwise-valid items (refresh the cite, keep the plan).**
R846 (see §A), R900 (`argmapping-relation-names-standardise`, `intent_argmapping_binding_leaf`), R934
(`roadmap-bodies-cite-unchecked-store-names`: **its own subject is this very drift**; it should be
Done-or-superseded once the sweep below is run), R961 (`converted-relations-prose-still-calls-them-views`,
`UnregisteredRelationTest`), R854 (`jvm_method_parameter`), R729, R973, R984
(`intent_record_slot_assignable`; the 2026-11-16 window's `744376c8` made the store-fed emitter decode an id of several
node types into the one record slot of whichever member it names, at a `@service` parameter or bean member,
so R984's premise that such a schema "is refused" is partly gone; the per-member overload dispatch it asks
for is still not built, and `BuildContext.admitPolymorphicSlotType` survives only on the walk's side, so
restate the "today" paragraph against `744376c8`), R736, R807, R632 (`intent_authored_claim_conflict`). **R534** (`record-key-decode-null-write-erasure`) is In Review and has a row in §C.5. **R1000** (`upsert-names-its-conflict-target`): cites `EmittedAnchor.arguments` as a twin site R998 converts; `EmittedAnchor` was removed (`dbf5acf`, `graphitron_type` is written by macro expansion) and `graphitron_field_path` was already gone. Action: **re-count the upsert sites** (34 files still call `onDuplicateKeyUpdate`) and drop the two dead names; the guard it proposes is unaffected. **R476** (`connection-helper-totalcount-redaction`): the gap is still real, since `ConnectionsEmitter` routes only the `FACETS` member through `ErrorRouter` and the `TOTAL_COUNT` member is still a bare `totalCount(env)` call, but the template it points at, `ConnectionFetcherClassGenerator.facetsDelegate`, was deleted with the per-type connection fetchers (`238f6ac`). Action: **repoint the template to `ConnectionsEmitter`'s facets arm**; the fix is now one arm in one emitter. **R521** (`generated-output-hygiene-sweep`): three bullets describe `ConnectionResult` (its 11-argument constructor, the unread `beforeCursor`, its multitenant variant); the class is gone and connections are store-rendered classes. Action: **strike those bullets or restate them against the generated connection classes**; the rest of the sweep is unaffected. **Note, not a flag: R606** (`facets-container-derives-from-the-store`) is named by `a52cbf8` as the item that retires the new `Facets` command row, so it is live; its opening claim that the facets shapes "exist only in the assembled-schema synthesis" is one paragraph out of date and should mention that row when next edited.

**From the 2026-11-23 window, all from the deletion of the walk's `@service` path (`93f8329`, `a5b3134`); each keeps its plan and needs one cite fixed or one count redone.** **R778** (`screaming-snake-transform-single-spelling`, Backlog): counts "four verbatim copies" of the CamelCase to `SCREAMING_SNAKE` transform and names `ErrorChannelWalker.toScreamingSnake` as one; that class is gone and `toScreamingSnake` is `grep`=0 across the tree, with the transform now named in `GeneratedUnits` and `ErrorMappingsClassGenerator`. Action: **re-count; discard if one spelling is left**, which looks likely. **R518** (`argmapping-grouping-fanout`, Backlog): its resolver change is placed "in the `argMapping` consumers (`ServiceDirectiveResolver` / ...)"; for a service the consumer is now the store's landing (`graphitron_argmapping_landing`). Action: **repoint that bullet**; check the grouping it wants against R1204's component bindings, which added a second kind of dotted entry. **R725** (`carrier-recognizer-conflates-three-scan-verdicts`, Backlog): the three scans (`scanStructuralDmlPayload`, `scanStructuralRoutineCarrierPayload`, `scanStructuralServiceCarrierPayload`) and `TypeBuilder.carrierBinding` still resolve, but the consumer it says needs one family's verdict, "`FieldBuilder`'s `@service` mutation arm (the guard behind `orphanServiceCarrierReason`)", was deleted. Action: **re-derive who still asks**; with the service consumer gone the conflation may have no reader left, in which case discard. **R334** (`readable-condition-arg-extraction`, Backlog): locates the unreadable nested ternaries in `ArgCallEmitter.buildArgExtraction`, deleted when `ArgCallEmitter` was cut "down to `nestedMapValueExpr`". **Sharpened 2026-11-24:** the condition flip (`f34cef0`) renders every conditions class from the store, so the item's condition half, which its own 2026-07-28 note already handed to R552, is gone with `ConditionGlueRenderer`. What remains is its expanded scope (mutation insert-value ternaries, the polymorphic discriminator rebuilt inline, deep input-path extraction in fetchers and `$fields` mappers), much of which sits in emitters R876 is flipping a root family at a time (`5fc05bd`). Action: **re-audit the generated sakila tree at trunk** and restate the remaining sites against whichever emitter produces each; drop the `buildArgExtraction` and condition paragraphs. R85 (§B.1) cites the same method. **R971** (`accessof-leaf-switch-exhaustive`, Backlog): lists `JooqRecord` among the `CallSiteExtraction` arms "minted only by `InputBeanResolver`"; `CallSiteExtraction.JooqRecord` went with `ColumnBinding` and `RecordKeyDecode`, and `TenantDslEmitter`'s service helpers with them. Action: **re-list the arms** before judging whether the switch is still non-exhaustive. R626 (`lsp-argmapping-routine-coordinate`), carried in this cluster, moved to §B.3 this window.

**New this window, from the condition flip (`f34cef0`).** **R1022** (`multitable-condition-bean-parameter`, Backlog): wants a bean-typed `@condition` parameter on an interface or union field built rather than refused; the refusal it names, `FieldBuilder.isBranchSafeExtraction`, still resolves, but the emit seat it would extend, `InputBeanHelperRegistry` and the walk's glue methods, was deleted, and a participant's condition is now one `ConditionUnitRenderer` method per field and table (with `PARTICIPANTS_SHARE_A_TABLE`, `1c1ac0a`, guaranteeing at most one participant per field and table). Action: **restate the emit half against the store's conditions class**; the refusal half is unchanged until the walk's classification goes. **R1191** (`float-argument-on-numeric-column-cast`, Backlog): the `(BigDecimal) args.get(...)` cast it reports was the walk's condition glue, now deleted; the store's emitter binds a raw value through `ColumnValues.fromWire` by convention (`9922995`). `RequestNumberBoundsTest` still steps around it with `@skip(if: true)`. Action: **drop the `@skip` on one case and run it**; if the query executes, discard, recording `f34cef0`; if not, restate against the store's emitter. Spec's question (accept a `Float` on an exact `NUMERIC` at all) stands either way.

**Carried from 2026-11-19: R1129** (`mutation-transaction-hook`, Backlog, last edited 2026-10-29): its carried-over design registers the hook on `Graphitron.runtime(dataSource, SQLDialect.POSTGRES)`, the mount-era signature; the runtime is now `Graphitron.runtime(dialect)` with a `ConnectionSource` given per request (R1119, `e3a3ecf68`; the prior audit's mount-era sweep missed it). It also promises the hook "at the start of every top-level mutation field's transaction ... `@service` mutations included", which predates `@service(transactionControl: SERVICE)` (`3dfc0ed57`): a service that owns its transactions has no Graphitron transaction for the hook to run in. Action: **refresh the registration example and add the service-owned case to its open question** before it moves to Spec; the item says the design is a draft to be reviewed then, so this is a cite refresh, not a re-spec. **R1125** (`defects-without-an-sdl-position-need-a-home`, Backlog): names `DefaultPageSizeDefects` "and its siblings" as the defect readers a supertype would migrate (`grep`=0 since the defect rules moved to the store), and its first waiting consumer, R1120, has shipped. **R1200** (filed 2026-11-18) is a third consumer: its Open question ("the store has no defect at the grain of a graph's configuration", a new problem relation keyed by graph) is this item's Goal restated, filed without naming it. Action: **add R1200 to "Consumers waiting on it", give R1200 a `depends-on` edge or a sentence pointing here, and drop the dead reader name.** With three consumers the item is worth moving to Spec.


**New this window, found by the child-`@routine` subject sweep.** **R447** (`routine-chain-fetch-form-breadth`, Backlog, last edited 2026-07-08): says its four fetch-form extensions "were left as typed `Deferred` landings whose `planSlug` points here". No code names the slug any more (only `roadmap/README.md` does); the shapes are refused in the store, `ROUTINE_LOOKUP_DEFERRED` for the `@lookupKey` composition among them, and `d423f29` this window extended that deferral to a split child `@routine`, which is now a path routine rather than a `ROUTINE` producer. Its closing line hands the child connection to R1169, now In Review. Action: **restate each bullet against the stored deferral that refuses it** (or say none does) and drop the `planSlug` sentence; the four extensions themselves stand. **Note, not a flag: R1074** (`multi-table-child-connection-truncates-composite-key`) offers "a validate-time rejection of composite participant keys on child connections" as the interim. `5048a67` moved the participant key refusals to the store (`PARTICIPANT_WITHOUT_PRIMARY_KEY`, `PARTICIPANT_KEYS_DIFFER_IN_ARITY`, arms of `graphitron_occurrence_participant_entry_defect_rule`), so that interim, if wanted, is a third arm there rather than a walk validator; the truncation itself is untouched.

**C.1b Two items for one defect, its input-side twin fixed in the 2026-11-18 window.** **R688**
(`nested-backing-class-binary-name-in-emit`, Backlog, 2026-08-17) and **R1109**
(`nested-record-class-renders-binary-name`, Backlog, 2026-10-27) describe the same bug: a nested result
class reaches an emit site as its binary `Outer$Inner` name through `ClassName.bestGuess`, so generated
fetchers do not compile. R1109 was filed without naming R688. R876's `6d2a5f786` (2026-11-17) fixed the
same mistake on the input side (`InputBeanResolver` now spells a nested bean with `ClassName.get`) and
`6daf7aebb` made the capture build nested record beans, but neither touched the output emit seats
(`FetcherEmitter` still exists; `bestGuess` is still called in 10 `emit/` and `plan/` classes). Action:
**discard R1109 into R688** (keeping R1109's MutationScopeService reproduction), and restate R688's
"today" against the store-rendered emitters and the `6d2a5f786` precedent.

**C.2 `CompletionData` / `CatalogBuilder` / `LspSchemaSnapshot` / `CatalogFacts` (dissolved by R876 / R638).**
**R427 (Ready), R555 (Ready, now §B.3), R684 (Ready), R730 (Ready)**, R152, R381, R594, R397, R528, R557. R536 moved to §A.
**R397** (`error-directive-on-query-fields`), sharpened: its opening says the error channel "already works" for query fields because `WithErrorChannel` is implemented by `QueryServiceTableField`, `QueryServiceRecordField` and `QueryServicePolymorphicField`; all three were deleted (`93f8329`) and a service's channel is the store's. `WithErrorChannel` survives on the walk's remaining leaves. **Restate the "today" paragraph against the store-fed service fetcher** before judging what `@error` on a query field still lacks.
**R557** (`split-query-marker-sweep`), carried: beyond `CatalogBuilder.projectFieldClassification`, its model enforcer `TenantBindingIndex.sweepUnreachedMarkers` is described as covering `@globalData`, a directive removed by `a45a9f590`, and one of the inert positions it wants a verdict for, `@splitQuery` beside a child `@service`, is now an engine lint read off the store (`splitquery-redundant-on-service-child`, `936808ba9`), which is the shape a store-side sweep would take. **Sharpened 2026-11-20:** the "record-parent implicit split" it lists as an inert position is now the stored warning `SPLIT_QUERY_REDUNDANT` (`e38c49c`), raised on a table-bound field under a class-backed parent and on every payload data field, with the service-child and discriminated-interface-child lints named to join it. Two of its inert positions therefore have a stored verdict and the code is already named for the rule; **re-spec it as the remaining arms of `SPLIT_QUERY_REDUNDANT` plus a refusal for positions that are neither consumed nor redundant**, and drop the `CatalogBuilder` seam. **Sharpened 2026-11-26:** a third position now has a verdict. R1169's `46c62be` (Alf, 2026-11-25, `7545b38`) makes every child connection split whatever it carries, so `@splitQuery` on a child connection is inferred, and written there it is redundant and warns (`SPLIT_QUERY_REDUNDANT`, pinned by `defects/split-query-redundant`'s `Actor.filmsSplit`). The positions left for the sweep are fewer each window; re-count them before re-specifying.
These four Ready items are the highest-value refresh before pickup: each names `CompletionData` (and R555
also `ClasspathScanner`, `PkLessParent`, `intent_authored_field_claim`; R684 also `CatalogFacts` and
`LspSchemaSnapshot`).

**C.3 `@sourceRow` / lifter / `SourceRowDirectiveResolver` removal.** R180
(`record-parent-column-read-helper`, `SourceRowDirectiveResolver`, `backingClassOf`), R654 (now §B.3), R412
(`buildLifterRowKey`; since 2026-11-23 also `checkServiceReturnMatchesPayload` and `computeServiceRecordReturnType`, two of the emit sites in its title's list, so **re-count its sites**), R135 (`validateLift`, still carried). `@sourceRow` was removed by R876.

**C.4 Carried long-standing drift, still `grep`=0:** `TableInputType` (R337, R257, R213), `looksLikeSourcesShape`
(R220), `ConstraintMapping` / `ConstraintSet` (R98, R170; R92 moved to §B.3), `LookupValuesJoinEmitter` /
`SingleRecordTableFieldFromReturning` (R242), `MutationUpsertTableField` /
`MutationUpdateTableField` / `additionalConditions` (R245, R145), `TypeClassGenerator` /
`TypeConditionsGenerator` (R35), `DescriptionNoteApplier` / `TagApplier` / `RewriteSchemaLoader` (R252,
R298), `JavaTypeKey` (R219), `PayloadAccessor` (R537, R304), `FkHop` (R485), `CatalogRefreshTest`
(R764, R822: R832 reworked it), `SchemaDirectiveRegistry` (R599: R494 deleted it, so R599 may be
subsumed), `RootLauncherSqlBaselineTest` / `DmlSqlBaselineTest` / `BatchedChildSqlBaselineTest` (R774, R669,
R720, R829), `IdReferenceShimClassificationTest` (R615),
`FetcherBodies` (R280), `walkerDiagnostics` (R601), `descendUnknownArgs` (R578), `PojoInput` (R337),
`MutationCompoundInsertField` (R122), `ServiceMethodCallWalker` (R257, since 2026-11-23), `ErrorUnionType` / `TableUnionType` (R278).

**C.5 Ready and In Progress items with stale cites (the cheapest-to-fix, highest-impact set):**

| Item | Status | Stale cite | Action |
|---|---|---|---|
| **R838** declarative-comment-style | Ready | Cites `RewriteSchemaLoaderTest` as one of three tree hits; the class no longer exists. | Update the count to two hits. |
| **R1168** shadowed-error-handler-is-reported | Ready | **Carried, sharpened a fourth time.** Four of its plan sections are now out of step with the tree. (1) "Platform throwables" plans a capture R876's `8cd9d91a6` (2026-11-16) **delivered** (the classpath gatherer reads `jrt:/` as a source of kind `PLATFORM`). (2) The generated `GraphitronClientException` section rests on `graphitron_runtime_class` (`grep`=0) and is an open R876 node since `edac463f9` (Alf, 2026-11-17), with `scenarios/error-handler-client-exception` pinning current behaviour. (3) **From 2026-11-20:** "Warnings in the build log" **landed under R876** (`8d41905`), which the body now says in one sentence while keeping the full plan below it; and its "Demotion" section plans "a list parameter naming codes to report as warnings" beside decision 1's claim that the lint channel's `<disabledRules>` seat "has no path" to defects, where `5e2ab52` made `<disabledRules>` and `<excludedTypes>` suppress a stored warning by code and deleted the `LintConfig.validated` a review round compares against. Demotion (error to warning) and suppression (warning to nothing) are different acts, but they now share a configuration seat the plan does not mention. (4) **New this window:** "Rule 8 goes" lists what to delete: `ChannelRuleChecks.checkDuplicateMatchCriteria`, "its call in `ErrorChannelWalker`", the `FieldBuilder` copy, and `ErrorChannelWalkerTest`'s rule 8 arm. `ErrorChannelWalker` and its test were deleted with the walk's service path (`93f8329`), so half that list is done and the rest needs re-counting (`checkDuplicateMatchCriteria` is in three files now). `8187c8f` also fixed the generated client exception's contract, the subject of section (2). | **Move back to Spec**: strike the platform-capture and build-log sections as delivered (`8cd9d91a6`, `8d41905`), replace the client-exception section with a dependency on R876's node, restate Demotion against `<disabledRules>` and `graphitron_entry_defect_site.suppressed`, and consult the principles-architect. The shadowing rule itself stands, and with three sections delivered or moved the item is now mostly that rule. |
| **R427** relevance-ranked-search | Ready | `CompletionData`, `SearchBacking`, ... (19 of 59 names gone two audits ago). **One more since 2026-11-23:** it points at `QueryServiceTableField` as a leaf carrying a precedent for its own, deleted by `93f8329`. | **Re-spec the Current state section** before pickup. |
| **R555** deprecate-externalfield-fold-into-service | Ready | Its classifier fold is written into `ServiceDirectiveResolver`, deleted on 2026-11-22 (`93f8329`); since 2026-11-24 R876's open node folds `@externalField` into `@routine` instead. | See §B.3: **a ruling on the target, then back to Spec or discard**. |
| **R684** consumers-share-relations-not-queries | Ready | `CatalogFacts`, `LspSchemaSnapshot`, `CompletionData`. | Refresh cites; part of its premise (CatalogFacts retirement) shipped. |
| **R730** capture-api-residue-after-nodehood-move | Ready | `CompletionData` retired by R876; more importantly the gate it extends moved into javac and its seventh site was deleted. | See §B.3: **back to Spec**. |
| **R427** (addendum) | Ready | **Carried, still undecided:** `28c66dd` (2026-11-12) added an open question raised from R876's connection thread, "Does a hit being an edge reopen decision 1?", marked not decided. | A Ready item carrying an undecided question that may reopen a spec decision should **go back to Spec until it is answered**, or the question be ruled out of scope in the body. |
| **R1113** transactional-outbox-directive | **In Review** | **Carried, seven days at the gate with no review commit.** Moved In Progress -> In Review on 2026-11-19 (`fcbceef2`) with phase 4's shipped commits and a handover. The three stale cites the prior audit listed were not touched and now sit in a body a reviewer reads as the record of what shipped: the Rejections bullet cites the `DefaultPageSizeDefects` precedent (`grep`=0 since the defect rules moved to the store); Phase 4, marked shipped, places the classification "in the emitted runtime, beside `isMountFailure`", renamed `isAcquisitionFailure` by R1119's `e3a3ecf68`; and the same section says "`acquire` calls the `DataSource`", where a request's connections come from the `ConnectionSource` it is given. Phase 1b's "mounted handle" describes the retired plugin `<mount>`. The 2026-11-20 window's `edb8b21` also made the outbox's JSON helper take any `Iterable`, and R1201 was filed against this item's `event` default and flat payload. This window R876 added two refusals on its surface: a pom `<outbox>` payload mapping that does not parse fails the build as the configuration is read (`3740eb4`), and a `$context` pair at `@outbox` is `CONTEXT_SITE_UNSUPPORTED` (`ebee5e9`), which agrees with its decision 7 ("`$context`. Not offered"). | **Fix the three cites as part of the In Review -> Done review** (the reviewer should check Phase 4's description against `ConnectionRuntimeClassGenerator` as it is, not as the plan names it), and have the Done entry say that R1201 replaces the coordinate-default event name, so the changelog does not present it as settled. |
| **R1160** tests-read-shared-fact-stores | In Progress | **Carried, sharpened.** `ServiceEmissionCensusTest` absent; its `@capability` / `@exemplifies` header vocabulary is not in the tree while the body still uses it. Its plan still lists `GraphCountGrowthGateTest` as a store holding chosen graphs; R1131's `64090f7d8` replaced that test with a plan-reading gate on 2026-11-17. | Minor; author is mid-flight, refresh when next edited. |
| **R1184** database-policy-gatherer | Ready | **Carried, minor.** Promoted on 2026-11-18; every other unresolved name is a proposal, a PostgreSQL catalog name or an environment variable. One line (setting names checked "against the `SessionState` mount") names the runtime class R1119's slice 8c.2 deleted; session settings are now written by the consumer's `ConnectionSources.mounting`. | Repoint the sentence at `<sessionState>`'s handle type and `ConnectionSources.mounting` at pickup. |
| **R534** record-key-decode-null-write-erasure | **In Review** | **Resolved as a flag; now waiting on a reviewer.** The prior audit found it finished in substance and stalled in state. This window it took the release run (`47ab77d`, sis green on `10.0.0-RC58`, the first release containing `ad8d21c`), which answers the snapshot-or-release question; narrowed `CustomerRecordService`'s javadoc so it names no deleted helper (`a8086fe`); and moved to In Review (`e140bfa`). Its body now names `JooqRecordInstantiationEmitter` and `ServiceVerdictCoverageGateTest` only as history ("was deleted"). | **In Review -> Done review by a different session**; confirm the mutation check its Tests section asks for (swapping `BuildEmitter.recordHelper`'s two loops turns both cases red) is recorded in a commit. |
| **R1169** routine-child-connection | **In Review** | **New, light: not a flag on the delivery.** Its second Done-gate attempt (`0c83e2c`), after round 3 asked for the claims its fact document should state (`4071b05` answered). Every tree name it cites resolves except `GraphitronSchemaBuilderTest.asConnectionOnAChildRoutineChainDefersOnTheChildSeat`, which its plan names twice: once as "the refusal that goes" (correct, history) and once, in the pre-implementation account, as the test that *pins* the child deferral today. Its three changelog lines were written as each change shipped, ahead of Done, with migrations and FS-plattform counts, which is R876's practice. | **In Review -> Done review by a different session**; at Done, mark the "today" passage that cites the deleted test as history, and write R1179's narrowed scope (filter only) into the Done line so the next reader does not look for the order case there. |
| **R1167** error-dispatch-matches-graphitron-9 | Ready | **New, minor; it was clean.** Names `TypeFetcherGenerator.outcomeAsyncWrapTail` twice, as one of two places a batched producer's `.exceptionally` arm receives the wrapper and as one of three callers of `ErrorDispatchFragments.outcomeArm`. The method went with `TypeFetcherGenerator`'s service fetchers (`93f8329`). `FrameEmitter`, `ChannelCatchArmEmitter`, `RoutineWriteEmitter` and `ErrorDispatchFragments` all resolve, so the plan's seats are intact and one fewer. | Drop the two mentions at pickup and re-list `outcomeArm`'s callers; no re-spec. |
| **R1206** declared-dependency-class-nameable | Ready | **New, light: promoted at 08:13, its premise moved at 11:11.** The plan says the code gatherer reads members only of `PROJECT`, `REACTOR` and `SIBLING` entries (`CodeCapture.REACTOR_ORIGINS`), and its new step reads a declared class that no such entry declares. R454's `0bc0c08` then made a second population read with members: the generated jOOQ catalog's classes, found through `sql_schema`, "in whatever entry carries them, a declared jar as much as a reactor module", in `CodeCapture.memberClasses`, and kept out of every directive arm. So a declared jar is already partly read, by another corpus's decision, under the same `f68eef2` ruling this item's own ruling is. Its Cost section's "`code_method` holds 4081 rows, all `REACTOR`" is the pre-`0bc0c08` figure (16,821 after, per R454's body). | **Refresh at pickup, no reopen**: name `memberClasses`' catalog arm as the precedent and the seat the referenced-classes step joins (the three populations should be one union, not two steps), say whether a schema may name a catalog class at all given the arms exclude them, and re-take the opptak figures after `0bc0c08`. |
| **R454** routine-write-result-shapes | In Progress | **Note, not a flag.** Its slice 1 is blocked on R876's new node (`24332cc`), and R876's other new node, a record class belongs to one table (`d402463`), moves the base-table preference R454's plan spells as `sql_table.record_class_fqn` "of a `TABLE`" (and its `SETOF t` guard) onto one stored fact. | The author should repoint those two passages at the node when slice 1 resumes. |

**R1167** (`error-dispatch-matches-graphitron-9`, Ready) is no longer wholly clean: see the row above. The
`graphitron_ast_error_database_handler_entry` relation its sentence names is still present, and its scope
(dispatch order) was not touched by the 2026-11-23 window's `@error` change (`9dfb522`, the store refusing an `@error`
field's `@nodeId` accessor that does not return the node's key), which judges how a field is read, not which
handler claims a throw.

R467 (`upgrade-graphql-java-26`), R1137 (`rejected-type-cascades-into-secondary-diagnostics`), R1119 and
R1131 are clean (R1131 recorded the deep-union measurement this window, `d14ce10`, which is the evidence
`e12c5b4` rules on; R1119 had no commit), as are the two Spec items this window moved or filed:
**R1205** (`unrouted-nodeid-under-field-level-override-refused`: re-probed on trunk at `29c56d5` after the
condition flip, its vocabulary paragraph restated, R1139 cross-referenced with the changelog hand-off the
prior audit asked for, and its walk refusal taken out of the plan on Filip's decision, `bc7d3c6`; two
questions for Alf or Filip are open, one of them a missing fact about what RC56 generated) and **R1209**
(`execution-test-connection-leak`: filed and specced this window, Filip's `630a668` ruling written in;
`FixtureCloseEnforcementTest` is a proposal and its other unresolved names are JUnit's, jOOQ's or
PostgreSQL's). R1207 and R1208 (`vouched-fields-guarded-by-requires`, `vouched-for-row-built-in-shape-order`)
are still clean. The earlier windows' **R1203** (`regenerate-after-edit-participant-merge-duplicate`) is
still clean, as are the carried Backlog items R1183, R1186,
R1192 (its "one lease per distinct connection key, released when the operation ends" is exactly what
R1119's `e3a3ecf68` shipped, so the premise is confirmed by code) and R1193 (`OutboxFailureSettleTest`
and `timedOutTenants` resolve), R1196 (`GlobalRead`, `DefaultSource`, `dslDefault` and
`GraphitronConnectionInstrumentationGenerator` all still resolve; the `@globalData` removal,
`a45a9f590`, deleted the marker ladder that fed the split but kept the split itself, so the fold it asks
for is still owed and now has one fewer reader), R1198 (no tree names), **R1200** (clean; see R1125 in
§C.1 for the item its open question belongs to), and **R1201** (`outbox-events-are-schema-types`: its
`depends-on` edge to R1113 is live, and its unresolved names are Apollo grammar terms and proposals).
R1099, R1201 and the routine items that write `$source.<column>` in an `argMapping` are untouched by the
retirement of the `$source` *field* sigil (`f67e17b`), which was `@field(name: "$source")` on a payload's
data field. R505 (`tenant-index-parent-row-routing`) is current. R1181
(`infinity-in-a-date-or-timestamp-order-column`) names `ColumnValues.writtenByDatabase` and
`ConnectionHelper.cursorTexts`, both present. R1119 cites `TenantId`, `SessionState`, `mountCauseFinder`,
`rejectedIdentity`, `@defaultTenant`, the rest of its slice 8c removals and the field-level voucher marker
(`issuing_field`, `VOUCHER_FIELD_REPEATED`, `VOUCHER_ISSUE_ABSORBS_NOTHING`) only in its rulings, slice
table and retired-vocabulary list; R1131 and R876 cite `GraphCountGrowthGateTest` only in progress notes
recording its replacement; that is history, not drift.

## D. Structural

- **R901 `trailing-segments-is-a-boolean` is `status: Done` in `roadmap/*.md`** with a `depends-on:
  [derived-read-cost-is-a-shape-problem]` edge, last updated 2026-09-03, and has **no changelog entry**.
  The workflow deletes the file on Done and records it in `changelog.md`. Its body says "Resolved by R876: it
  was a count of nothing, and it is a name now". Recommended action: finish the transition (changelog
  entry, delete the file) or, if it is intentionally parked behind R876, say so in its body and move it
  back to a non-Done status.
- **`next-id` is ahead of every id.** The counter is `R1210` and the highest id on the board is R1209,
  filed this window. R1178 (discarded) and R1202 (Done) left with changelog lines. R1194, R1195 and R1197 remain allocated-and-discarded gaps, each with a
  changelog line. R1182 still has no file (absorbed into R876 on allocation), a harmless gap.
- **Carried: two items left the board with no changelog line, still unwritten.** The workflow records
  every Done and every discard in `changelog.md`, and the changelog is the only place a removed item's
  outcome survives. This window wrote five lines, all correctly (R1202's Done entry, R1178's
  "Discarded" line, and R1169's three as each change shipped, two of them `Breaking:` with migrations),
  but did not backfill these two:
  - **R1189** (`condition-directive-reading-consolidation`): `1b7287117` (`chore(R1189): Backlog -> Done`)
    deleted the file and regenerated the README, and nothing else. The delivery is `30600d073` (`feat!`: a
    `@condition` naming no class or no method fails the build at the directive). Compare R1190 and
    R1187, which also skipped the gates at the user's direction and do carry their lines.
  - **R1185** (`connection-hook-takes-the-key`): `a1d9bb43a` (`chore(R1119): the plan absorbs R1185 ...`)
    deleted the file and recorded the absorption in R1119's body, but wrote no "Discarded" line, unlike
    R1050, R1194 and R1195, each absorbed with one.

  Recommended action: **add the two missing changelog lines** in the next roadmap commit (a Done line for
  R1189 naming `30600d073` and noting the waived gates; a Discarded line for R1185 naming R1119).
- **Carried, a note: one of R876's breaking changes has no changelog line.** R876 records each breaking change
  as a `Breaking:` line when it ships (this window `bb949ea` and `f34cef0` did), but `5fc05bd` (`feat!`: a
  root single read is `null` only where it finds no row, so a matched row of nulls now answers an
  object with null fields) carries a `BREAKING-CHANGE:` footer and no line. Recommended action: add the
  line with its migration in the next R876 roadmap commit. (R1119's `0c5ed78`, which deletes the
  generated `VouchedValueLimitExceededException`, follows R1119's own practice of recording breaking
  changes at its Done entry; that is consistent, not a gap.) Still missing at `377feed`; the window's two
  other `feat!` commits, R1169's `51769f3` and `7041451`, have their lines.
- **Carried, closed as a note: seven `wip(R876)` subjects reached trunk on 2026-11-18** (`37749f040` and
  six siblings). Trunk is fast-forward only, so they stay; each is a `feat` by content, which whoever
  writes R876's changelog entry should know. **No `wip`, `draft` or `spike` subject reached trunk this
  window**: all 74 subjects are Conventional Commits with a type and, bar five `docs` and one `test`, a
  scope.
- **Carried, a note: one breaking change shipped with its consumer check open.** The changelog line for
  `RESULT_SHAPES_CONFLICT` (`753df28`) says "FS-plattform impact is pending the six FS captures in the
  build slot". Its sibling `CLASS_SUPERTYPE_NOT_READ` names its impact (15 sites in one module).
  Still "pending" at `377feed`. Recommended action: whoever runs the captures amends the line with the
  count, so the changelog does not carry "pending" into a release note.
- Fifteen items carry a non-empty `depends-on:` (unchanged; R1209 declares none), all resolving to existing files (R1201 has an edge to
  R1113, an item In Review; the edge stays valid until R1113's file is deleted on Done,
  when it should be dropped in the same commit).
  Six of them depend on R876 (`derived-read-cost-is-a-shape-problem`), which means **R876 is a
  single point of staleness**: any design change there cascades across R898, R851, R1099, R697, R1096,
  R901 and the §B.2 set. R857 depends on R872 (`warm-capture-empties-unpartitioned-catalog-relations`),
  which exists. R1192 depends on R1119, R1168 and R1171 on R1167, R98 on R92 and R170 on R98, R298 on R480
  (`oneof-augment-defeated-by-descriptions`), and R1131 on R1160. One edge is **missing**: R1200's
  open question is R1125's subject (§C.1).
- Four legacy items still lack a `bucket:` key: R242, R109, R252, R180 (unchanged from the prior audit,
  harmless; fold in when next edited).
- Numbering gaps are harmless; numbers are never reused.

## Cross-cutting observations

1. **R876 is still the root cause of most of the board's staleness and is still In Progress.** It has
   dissolved the `intent_` family, the register, `CompletionData`, `@sourceRow`, the shadow tests, the
   monolithic entry-defect rule, `graphitron_node_type`, `SlotParse` and the argMapping call relations.
   Items filed Sep to Nov (R899 to R979 especially) were written against the `intent_`/register model
   that R876 then retired inside weeks; roughly forty items name relations that do not exist. Six items
   depend on it, and the staleness is concentrated, not diffuse. **It stales in three ways**: by removal
   (`@globalData`, the `$source` field sigil, the walk's `@service` path, and this window the walk's condition glue), by absorption into its own plan (R606 and
   R722, closed as it went; R234 and R1168's client exception still are not), and by plain delivery
   (R660, and now very likely R1139). **The 2026-11-20 window added a fourth, condemnation,
   and the 2026-11-23 window carried the sentence out.** The service flip (`6af820d`) had deleted almost nothing,
   so no cited name went dark while the walk's service classes became code a named node deletes. Three
   commits then deleted them (`4df4e92`, `93f8329`, `a5b3134`: over 7,000 main lines), and thirty-two
   item bodies cite a name that went. The lesson for the next flip is in the order of events: the
   prior audit could name four condemned items from the flip's commit message, and the deletion found
   another fifteen that only a name sweep could. **The condition flip came this window** (`f34cef0`),
   and the prior audit's forecast for it was half right. It named ten items standing on
   `ConditionResolver`, `ServiceCatalog`, `ArgmappingProjectionDefects` and `InputBeanResolver`; the
   flip was one of emission, all four classes survive, and of the ten only R1202 and R334 were hit, by
   way of `ConditionGlueRenderer`, which the forecast did not name. The name sweep found R862 and R1022
   besides. What went right is the other half: R876's sessions discarded the two items the flip emptied
   (R561, R903) **in the flip's own commit, with changelog lines**, and R677 the same way with
   `bb949ea`. That is the practice observation 8 has asked for since the 2026-11-18 window. The next
   boundary is the walk's condition *classification* (`ConditionResolver`, `FieldBuilder`'s condition
   arms, `ServiceCatalog`'s reflection), and the items standing on it are still R72, R654, R971, R1022,
   R1205 and R218 to R220. **The 2026-11-24 window staled by a fifth way, direction:** R454's slice 0
   unblocked R876's node folding `@externalField` into `@routine`, which a Ready item (R555) plans to fold
   into `@service`. No name went dark; two plans now disagree, and only a ruling settles it. **The
   2026-11-25 window staled by a sixth way, duplication:** R876's node list filed "The editor completes
   `argMapping` at a `@routine`" with R626's design and without its id, and already carried R935's
   lowering as "An inline list is emitted from the store in its stated or chosen order". When R876 files
   a node, a grep of Backlog for its subject (here `argMapping` and `@routine`, `@orderBy` and inline)
   is the cheap check; the node should name the item, and the item be discarded into it.

2. **R1119 is now the second source of staleness, and it is the runtime's.** Its slice 8c retired the
   plugin `<mount>`/`<unmount>`, `SessionMountException`, `GraphitronSessionHook`, the default tenant and
   `@defaultTenant`, and its slice 8d replaced the entity decode path. R1119 itself handled this well: it
   declares retired vocabulary, it updated R505 in the same window, and it folded R1194, R1195 and R1197
   back with changelog lines. But five items written against the mount-era runtime were not swept (R1108,
   R468, R1159 in §B.3; R1113 and R1184 in §C.5), and one Spec item lost its emit seat (R1146). **R1119's
   retirement sweep at its Done gate should grep for `mount`, `SessionMountException`, `DataSource`,
   `Graphitron.runtime(` and `default tenant` across active bodies**, which would catch all five and
   R1129, a sixth this audit found by its `runtime(dataSource, ...)` example.

3. **R934 (`roadmap-bodies-cite-unchecked-store-names`) is the board diagnosing itself.** The cheapest fix
   to this audit's biggest cluster is mechanical: a gate (or a `check-` step in the roadmap-tool) that fails
   when an item body backticks an `intent_`/`graphitron_`/`meta_` relation name that
   `graphitron-model.sql` does not define. Run as part of the build it would have prevented most of §B.2
   from drifting silently. Recommend promoting R934 to Spec. Its limit is still visible: R730, R1108 and R1159
   are stale on build mechanisms and runtime classes, and R660, R1139, R509 and now R695 and R655 are stale on
   *behaviour* (a delivered goal, a ruling), with every name they cite still resolving. A name gate
   catches none of those; only a read of what landed against what is filed does.

4. **A discard-and-absorb pattern keeps leaving stale `depends-on`/trigger text behind.** R846 still
   schedules itself for deletion when R682 reaches Done; R682 was discarded into R876, and eight active
   bodies still name R682. A sweep for `R682`, `R333`, `R222`, `R462`, `R972`, `R1174`, `R1050`, `R1185`
   and now `R722` in active item bodies would find them (today `R722` is named by no active body, and
   R1194, R1195 and R1197 only by their absorbing items, which is correct).

5. **The overdue mechanical closes keep surviving.** R193 and R213 (subsumed by R649; R585 and R589) have
   now survived twelve audits, and R193's residue check is no longer needed (§B.1); R209 (§A) is the third. Together with R1189 and R1185's missing changelog
   lines this is perhaps ten minutes of bookkeeping that would remove five recurring lines from this report.

6. **The Ready set is where stale prose bites soonest, and a new Ready item went stale on arrival again.**
   Of the eleven Ready items, **R427, R555, R684, R730, R838, R1167, R1168, R1184 and R1206 carry stale
   cites**, and R427 also carries an undecided question; only R467 and R1137 are clean. Three should go
   back to Spec rather than be refreshed in place: **R555** (its classifier fold is written into
   `ServiceDirectiveResolver`, and R876 now plans the opposite fold), **R1168** (two plan sections
   delivered, a third an R876 node, a fourth half done by deletion) and **R730** (the gate it extends
   moved into javac). **R1206** repeats last window's R1202 pattern at a smaller scale: promoted at 08:13
   on a "today" that R454's slice 0 changed at 11:11, by a sibling session working the same gatherer. The
   lesson stands and widens: a Spec -> Ready review should ask the In Progress items that touch the same
   seat (here `CodeCapture`) what they are about to land. **The gates moved well again:** R1199 went
   Spec -> Done inside a day with its splits filed (R1207, R1208), R454 reached In Progress, and R1202
   reached In Review on a plan edit that made Filip's call explicit in the body rather than silently
   working around a stale plan. **The 2026-11-25 window touched no Ready item.** The In Review queue is
   three again: R1113 (seven days, three dead cites, still no review commit), R534 (three days) and
   R1169, whose path through the gates (round-1 Spec revisions, a Done-gate round that sent it back to
   Ready, a third round answered by a test commit) is the gates doing their job on a large item.

7. **A reachable baseline makes the audit cheap, and the two sweeps take turns.** Each run diffs
   against the prior audit's commit; this run had to deepen a shallow clone to reach it (below). In the 2026-11-20 window the removed-names test found one nit and the `feat`/`fix` subject
   sweep found the rest; the 2026-11-23 window it was the reverse. The removed-names test found all fourteen new flags and both moves into §A, because the
   window's main event was a deletion, while the subject sweep found only sharpenings (R762 through
   the supertype capture, R1099 through the `$context` pair, R592 and R881 through the second drained
   lint, R92 through the stored `validates`). Two cautions from doing it. The removed set was 1,694
   names and most are test methods and prose words, so the hits need reading, not counting. And a
   cited class can vanish while a same-named mention survives elsewhere: `JooqRecordServiceParamPipelineTest`
   still `grep`s in two test javadocs although the class R707 is about was deleted. Runs should keep
   doing both sweeps, occasionally read Ready bodies end to end, and when a new defect code lands,
   grep Backlog for the item that asked for it as a lint. **This window the two sweeps split the yield**:
   the removed-names test found R1202, R862 and R1022 (all through `ConditionGlueRenderer` and its
   registries), the subject sweep found R85 (a `contextArguments` condition now stored), R1188 and R1191
   (number bounds and the `fromWire` convention) and R1205's stale premise, none of which cite a
   removed name. **The 2026-11-24 window was all subject sweep**: 32 removed names, none cited outside a
   retired-vocabulary section, while the `feat`/`fix` and docs subjects found R776 (a `fix(dev)` with no
   item), R1206 and R762 (a `feat` reading more classes), R555 (a refactor unblocking a node) and R1188 (a
   ruling on a sibling's cap). Two of those five came from commits with no item or another item's scope,
   which a name gate would never see. **The 2026-11-25 window was subject sweep again** (14 removed
   names, none cited), and two of its four new flags came from reading R876's *open nodes* rather than
   its commits (R626, R935), and one from a store relation landing ahead of the item that proposed it
   (R1114). One procedural caution: this session's clone was shallow, cut at `29c56d5`, so the prior
   audit's baseline `c514f01` and every hash it cites looked unresolvable until the run deepened the
   clone (`git fetch --deepen`). A run that finds a cited hash missing should check
   `git rev-parse --is-shallow-repository` before concluding history was rewritten.

8. **R876 delivers or overturns Backlog items without closing them.** R898 was the clear case four audits
   ago (`431e604`); R852, R902, R1033, R562, R1034 and R931 followed. The 2026-11-18 window added **R234**
   (embeddable input binding, now an R876 node) and the second half of **R1168** (the client exception,
   now an R876 node); the 2026-11-19 window added **R660** (delivered) and **R1139**, which the next window's
   `540f078` all but closed; the 2026-11-20 window added **R695** (the store refuses what it wanted linted); the 2026-11-23 window added six by deletion (R655, R536, R665, R274, R402, R707). The
   contrast is **R722** and **R606**, each discarded with a changelog line as R876 took its subject:
   that is the pattern that keeps the board honest, and it is the practice for absorptions by ruling
   but not yet for deliveries by commit. **This window it became the practice for deliveries too**:
   R677, R561 and R903 were discarded in the commits that delivered or deleted their subjects. The
   older backlog of delivered-but-open items did not move. **This window R1178 was discarded the
   day the ruling answered it, in its own commit with a changelog line**, which is the practice working
   for a question item too. The discard set this audit recommends (R209, R899, R761, R861,
   R758, R1033, R1034, R562, R898, R1109, R660, R193, R213 after a residue check, R931, R1139 and R695
   after a one-case check, R655, R536, R665, R274, R402 and R707, and now possibly R85 and R1191 after
   one compile or execution case each) is **twenty-two items, twenty-four with the conditional pair**,
   that each need one changelog line and a file deletion; with this window's two discards into R876
   nodes (R935, R626) it is **twenty-four, twenty-six with the pair**. The six new ones can share one roadmap commit that
   names `93f8329`, `a5b3134` and `4df4e92`, and that commit is best written by R876's own sessions,
   who know which fact document now pins each case.

9. **Gate-waived work is now recording itself properly.** The 2026-11-17 audit found R1189 and R1188 half
   recorded. R1187 (gate-waived, 2026-11-17) carries its changelog line and says the gates were
   waived, and the same-day filing-and-folding episodes (R1194, R1195, R1197) left changelog lines. The
   two older gaps (R1189's line, R1188's narrowed Goal) are still owed.

10. **Rulings are landing as docs and store facts the same day, and filed items are not being read against
    them.** The 2026-11-19 window's docs diff added fifteen dated Alf rulings (input order, fan-out,
    `@globalData`); the 2026-11-20 window added more dated 2026-11-19 (a field nothing reaches has no fetcher; a
    redundant `@splitQuery` is admitted with a warning; a record component no field fills is refused,
    not defaulted; a `@service` may hand back any `Iterable`; the node fetcher's argument is ruled by
    name; a store refusal keeps the fix its walk message suggested). R509, R1110 and R242 are still
    Backlog and Spec items whose open questions the earlier rulings answer in part, and R319, R557,
    R695 and R881's question 3 stand in the same relation to those. This window's five are listed under
    "What changed"; R1146 and R1202 were each rewritten against theirs the day it was given, which is
    the practice working, and observation 12 names the one that filed items have yet to meet. A ruling's author is
    best placed to grep active bodies for its subject when writing the docs commit; this audit is the
    backstop. The 2026-11-24 window's docs commits and rulings reached three filed items they do not
    name: `f68eef2` (another corpus decides what a capture reads) is R762's thesis, Filip's
    `VouchedValueLimit` ruling is R1188's open configuration question, and `bd232ad` replaced the
    one-row-routine ruling the prior audit recorded, which only R454 (its author's own item) cites. The
    2026-11-25 window's rulings reached two filed items they do not name: the deep-union ban
    (`e12c5b4`) settles R938's cost question by trading view count for union depth on purpose, and the
    child-connection ruling (`7545b38`) gives R557 a third `@splitQuery` position with a stored verdict.
    Both rulings were also written into the items that asked for them (R1131, R1169, R1178) the same
    day.

11. **The lint channel is draining into the store, and four items still plan around `LintRule`.**
    `e38c49c` moved the first walk lint to a stored warning and named two more to follow; `5e2ab52`
    made stored warnings suppressible from the lint configuration; `8d41905` logs them in the build.
    This window a second went, `validation-column-unchecked` to `VALIDATION_COLUMN_UNCHECKED`
    (`8fffe9c`), again as a breaking change with a footer, leaving 21 ids in `LintRule`.
    R695 (a new `LintRule` arm), R509 (a `BuildWarning.LintFinding` slug in the `LintRule` registry),
    R592 (a page for the `LintRule` set) and R881 (question 3) were all written when a warning could
    only be a lint. Each should say "warning-severity defect" where it says "lint rule" unless there
    is a reason it must be computed in Java. R555 renders its deprecation through
    `no-deprecated-directive-usage`, an `ENGINE` rule read off the AST, which is the kind that looks
    set to stay; confirm that when it is re-specified.

12. **The plain-join ruling now has its second half.** `db54d97` ruled that a fact that is a plain
    join and filter over stored relations is stated in the query that asks it, not stored; this window
    `9786bbf` (Alf, 2026-11-23) added that a join several rules and readers each build on is stored
    once, so its readers cannot drift apart, and `aa923ad` and `83034e2` are the first relations
    stored under it. The test for a proposed relation is therefore "how many readers build on it", not
    "is it a plain join". R319's reach anti-join and R588's pre-empting rule each have one reader and
    stay queries; R1125's graph-grain problem relation, which several defects would build on (R1200 is the
    latest consumer), is the kind the second half stores. R1146 shipped its served-by-id
    predicate before the refinement, so there is nothing to revisit there.

---

_Review date: 2026-11-26._
