# Roadmap staleness audit: 2026-11-20

A point-in-time review of every active roadmap item under [`roadmap/`](../) against the **current**
state of the codebase on `claude/graphitron-rewrite` (HEAD `7f4747d`, committed 2026-11-20 01:06 UTC,
audited 2026-11-20). The goal is to find items whose premise no longer holds: work already shipped,
constructs renamed or removed, dependencies that have since landed or been discarded, or specs grown
stale enough to mislead an implementer. Every flagged item carries a recommended action and the reason
for it.

This file is an analysis artifact, not a roadmap item: it lives in a subdirectory so the roadmap-tool
(which scans `roadmap/*.md` non-recursively and requires `id:` front-matter on each) ignores it, and it
is Markdown so the `check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-11-19` staleness audit, which has been renamed into this file; only the
latest **staleness** audit is retained. The other files in this directory are provenance (spikes,
censuses, measurements, lineage notes) that shipped and active items cite by path; they are not staleness
audits and are left in place.

**Baseline and method.** The prior audit measured trunk at `7248ff7` (`next-id: R1201`, 382 item files).
This audit measures at `7f4747d` (`next-id: R1202`, **383** item files, max id R1201): a window of **about
23 hours and 73 commits**, 43 of them R876's, 11 R1131's, 7 R1201's, 3 R1113's, 2 R881's, one each for
R1119, R1146 and R534, and 4 docs or roadmap bookkeeping. The clone was shallow, so `7248ff7` only became
reachable after deepening it; the delta below is a real commit diff:

1. Parse every `roadmap/*.md` front-matter (id, status, slug) at both commits and diff the two tables.
2. Diff `7248ff7..7f4747d`: every roadmap file touched, every changelog entry added, and the set of
   identifiers present in removed lines of the non-roadmap, non-docs diff and absent from the tree now
   (**121** candidates, most of them test-method names; the substantive ones are the `$source` field
   sigil and everything under it: `FieldSourceSigil`, `SourceSigil`, `UpstreamRoot`, `CarrierDataField`,
   `checkSourceSigilTypeMatch`, `isPayloadDataField`; the walk's record-parent `@splitQuery` lint:
   `warnIfSplitQueryOnRecordParent`, `SPLITQUERY_REDUNDANT_ON_RECORD_PARENT`; the walk's refusals of a
   filter naming no column: `mintCascadeVerdict`, `resolvingTable`; and two generate-time refusals,
   `unemittedProjections` and `unemittedOutboxes`). Each of the 383 item bodies was tested against that
   set. **One item cites one live** (R534, the two `unemitted*` names, in a rejected alternative; §C.1),
   and R654 cites the sigil by its prose name (§B.3).
3. The new item (R1201) and the two items rewritten on their way to Spec (R534, R881) had their
   backticked names checked against the tree; apart from R534's two, every unresolved name is a proposal,
   an Apollo grammar term, a Graphitron 9 name or a consumer's own class.
4. Recheck the prior audit's flags. The retired names the clusters rest on were re-`grep`ped
   (`CompletionData`, `meta_materialize`, `SlotParse`, `looksLikeSourcesShape`, `TableInputType`,
   `RewriteSchemaLoaderTest`, `SourceRowDirectiveResolver`, `graphitron_runtime_class`,
   `graphitron_node_entry`, `ConnectionResult`, `DefaultPageSizeDefects`, `ServiceEmissionCensusTest`,
   `EmittedAnchor`, `ConnectionFetcherClassGenerator`, `isMountFailure`, `emitDecodeAndGroup`,
   `GraphCountGrowthGateTest`, `projectFieldClassification`: all still `grep`=0), so the flags carry
   unless stated otherwise.
5. Read the window's `feat`/`fix` commits (21 of them) for work that delivers or undercuts a filed item,
   and grep the active bodies for each one's subject (the `$source` sigil, a redundant `@splitQuery`,
   `<disabledRules>` and the lint channel, a warning in the build log, a "did you mean" hint, a record
   component or bean member no field fills, the service fetchers and the walk's service path, an
   `Iterable` return, the node fetcher's `id`, a type no root reaches, the defects reference page).
   This was again the higher-yield step: it found R592, R695, R654, R655 and R319, and settled R1139.

As before, the carried flags are **symbol-evidence-backed, not a full prose read of every body**; the
recommended action for most is a re-derive that will confirm or dissolve the flag.

## What changed since the 2026-11-19 audit

- **Done:** none. **Discarded:** none.
- **Filed and kept (one):** **R1201** (`outbox-events-are-schema-types`, Backlog, `depends-on` R1113):
  an `@outbox` event becomes a type in the sending schema, built by a `construct` argument written in
  Apollo's `JSONSelection`. Seven commits shaped it the day it was filed; `LspVocabulary`,
  `directives.graphqls` and the `argMapping` sources it names all resolve. Clean.
- **Moved (three):**
  - **R1113** (`transactional-outbox-directive`) **In Progress -> In Review** (`fcbceef2`), with a
    handover naming what the principles-architect raised. It carries three stale cites into the gate
    (§C.5).
  - **R534** (`record-key-decode-null-write-erasure`) **Backlog -> Spec** (`b1d5864`), rewritten: it
    now changes no generator behaviour, pins the consumer's shape with two execution cases and hands
    the one path that can still lose the null to R876's node "The walk's service path is gone". That
    answers the prior audit's flag (§C.1: old test name, Goal not narrowed). **Resolved**, one nit left.
  - **R881** (`write-carriers-never-read-column-nullability`) **Backlog -> Spec** (`e0d6284`,
    `cdeffbb`), rewritten against column facts read by table and column. Clean; one of its open
    questions gained a precedent the same day (§B.3, light).
- **Actioned from the prior audit (two, the first in several windows):** **R1146** was restated
  (`46afc11`) against the two-shape node fetcher and a node query of its own, with a "Sequencing against
  R876" section and a retired-vocabulary list; `SlotParse` and `emitDecodeAndGroup` are gone from its
  body. **R1168** gained one sentence saying its build-log route landed under R876 (`8d41905f`); the
  rest of its flag stands and grew (§C.5).
- **R876 flipped every `@service` fetcher to the store** (`6af820d`): a field the store states a
  `SERVICE` producer for is emitted from the store or generation stops on a generator bug, and "the
  walk's errors at service fields, their arguments and the input types only services reach are the
  store's to judge". The walk's service classes (`ServiceDirectiveResolver`, `ServiceCatalog`,
  `InputBeanResolver`, the `*Service*Field` variants) are still in the tree and are what R876's open node
  "The walk's service path is gone" deletes. This is the window's largest effect on filed items (§B.3:
  R695, R654, R655; §C.2: R536).
  - *Refusals that moved with it.* A record component no input field fills is `MEMBER_UNFILLED` in the
    store alone (`4a0ec8a`, `34a4de0`, Alf 2026-11-19: refuse, do not default); a filter naming no
    column is `COLUMN_UNRESOLVED` alone, the walk's two refusals deleted (`540f078`); a service
    answering many into a one-object payload is `SERVICE_RETURN_MISMATCH` (`cb51fa9`); a `@service` may
    hand back any `Iterable` (`edb8b21`, Alf).
  - *Reach is a stored fact.* A field nothing in the schema leads to has no producer and no fetcher
    (`e459c34`, Alf 2026-11-19), stated by the new `graphitron_type_schema_reach`.
  - *The node fetcher's argument is ruled by name* (`c8eea51`): `graphitron_occurrence_producer_node`,
    and a node fetcher with no `id` or `ids` is `NODE_FETCHER_WITHOUT_ID`.
  - *The `$source` field sigil is retired* (`f67e17b`, `feat!`): `@field(name: "$source")` on a payload's
    data field goes, and a `$`-name that is no sigil is `FIELD_NAME_SIGIL_UNKNOWN`. The `$source` an
    `argMapping` reads a parent column through is a different thing and is untouched, so R1099, R1201 and
    the routine items that write `$source.<column>` are not affected.
- **R876 gave warnings and guidance a home in the store.**
  - A warning-severity defect is logged by the build (`8d41905`), and a warning's code among
    `<disabledRules>`, or its type among `<excludedTypes>`, suppresses it (`5e2ab52`); `LintConfig.validated`
    is gone, and the generator checks `<disabledRules>` against the store's defect types.
  - The first walk lint moved: a redundant `@splitQuery` under a class-backed parent or on a payload's
    data field is the stored warning `SPLIT_QUERY_REDUNDANT` (`e38c49c`, `feat!`, Alf 2026-11-19), and
    the lint id `splitquery-redundant-on-record-parent` is gone with its editor fix.
  - Every defect code carries fix guidance gathered from one AsciiDoc fragment per code (223 fragments),
    the manual's defects reference is rendered from the store with one anchored entry per code
    (`b489997`), the editor links a defect to its entry and the MCP diagnostics carry the guidance
    (`5684aa4`), and a `COLUMN_UNRESOLVED` message suggests the nearest columns, ranked once in the store
    (`33d0bcf`, `graphitron_entry_defect_hint`).
  - The store's SQL functions are a module of their own, `graphitron-store-functions` (`f29e963`).
  - The model is now **268 views and 396 tables** (264 and 392 at the prior audit).
- **R1131** analysed only the tables a capture changed (`f30528a`), tried and reverted an in-memory test
  population (`4c45ee4`, `1cd71f4`), and reports how hard each view is for H2 to plan
  (`fcb92dd`, `c6b1f57`); the research is in `2026-11-19-what-h2-spends-a-capture-on.md`. It bears on
  R938 (§B.2).
- **R1119** fixed a nullable single field into an `@issue` type answering `null` (`b334863`); no filed
  item covered it.
- **Effect on flags.** **Resolved (two):** R534 (re-specified) and R1146 (plan restated).
  **New:** R695 (§A: the store refuses as an error what it wants linted), R592 (§B.3: the lint set is
  22 rules, not fifteen, and stored warnings now have a rendered reference), R654 (§B.3: two of its four
  seats are gone), R655 (§B.3: three polish tasks on code R876 is deleting), R319 (§B.3, light: both of
  its prerequisites now exist), with R654 moving from §C.3 to §B.3 and a light note on R588. **Sharpened:** R1139 (§B.3: both walk refusals that stood in its way
  were deleted), R1168 (§C.5: `<disabledRules>` now reaches stored warnings), R1113 (§C.5: now In
  Review), R557 (§C.2: its record-parent case is a stored warning), R536 (§C.2), R938 (§B.2) and
  R881 (§B.3, light). Board-level structure: R1189 and R1185 still have no changelog lines and R901 is
  still a Done file (§D); no `wip` subject reached trunk this window.

**Flag totals.** Prior ~132; two resolved, five added, one moved from §C to §B: **~135 flagged items:
7 §A (+R901 structural) / ~57 §B / ~71 §C**, plus two board-level structural flags. Item counts are given per cluster because many
flagged items cite the same retired names. **Two of the prior audit's recommendations were actioned**
(R1146 restated, R534 re-specified, both by their authors on the way through a gate). The rest stand:
R209, R193, R213, R899, R761, R861, R758, R846, R1033, R1034, R562, R898, R931, R1109, R660, R1188 and
R901 are all as they were, the four stale Ready items (R427, R555, R684, R730) still name
`CompletionData`, R730 and R1168 are still Ready, the R427 open question is still undecided, and neither
missing changelog line (R1189, R1185) was written.

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
  (R660's routine-write ordering). This window it **flipped every `@service` fetcher to the store**
  (`6af820d`), which leaves the walk's service classes as code awaiting deletion and every item that
  polishes them as work on a condemned seat (R655, R654, R536, R695), retired the `$source` field sigil,
  and moved the first walk lint into the store as a warning-severity defect.
- **R1163 is Done** and its changelog entry states the direction: the strata account is the
  gatherers' capture and anchor phases. Items that cite "stratum" or the `intent_` names as live
  mechanisms are the stale set.

## Status snapshot (383 item files)

[`roadmap/README.md`](../README.md) is the roll-up. By `status:` front-matter: Backlog 333, Spec 34,
Ready 10, In Progress 4, In Review 1, Done 1 (the Done one is a tombstone, §D).

- **In Progress:** R876 (`derived-read-cost-is-a-shape-problem`), R1119
  (`signed-reference-entities-across-subgraphs`), R1131 (`clean-build-under-five-minutes`), R1160
  (`tests-read-shared-fact-stores`).
- **In Review:** **R1113** (`transactional-outbox-directive`), since `fcbceef2` on 2026-11-19; it needs
  a reviewer from a different session than its implementer, and three stale cites fixed at the gate
  (§C.5). The Backlog count moved by R1201 filed and R534 and R881 promoted to Spec.
- **Ready:** R427, R467, R555, R684, R730, R838, R1137, R1167, R1168, and **R1184**
  (`database-policy-gatherer`). Seven of the ten carry flags (§B.3, §C.5): R427, R555, R684, R730, R838,
  R1168 and R1184.

## A. Obsolete: should leave the active roadmap

| Item | Status | Why obsolete | Recommended action |
|---|---|---|---|
| **R209** field-registry-typed-rejection-trace | Backlog | **Carried, sixth audit running.** Entire deliverable (remove the `RejectionKind.AUTHOR_ERROR` default arm, emit `RejectionKind.of(rejection)`) shipped by R585; nothing remains. | **Discard**, recording R585 as the delivery vehicle. |
| **R899** read-budget-with-nothing-materialized | Spec | The item's own demotion note says it "may no longer have a subject" if R876 empties the register. R955 did: `meta_materialize` is `grep`=0 and no registration exists to price. | **Discard**, recording R955. |
| **R761** materialize-dependency-derived-before-stamp | Backlog | Subject is the `meta_materialize_dependency` relation and its stamping order; both gone with the register. | **Discard**, recording R955. |
| **R830** meta-relation-reference-inlined-key-projection | Backlog | Costs and names `meta_materialize` / `meta_materialize_dependency` / `MaterializeDependencies`, all gone. | **Re-measure `meta_relation_reference` on the current store; discard if the 153 ms figure does not reproduce**, else re-spec without the register vocabulary. |
| **R941** owner-read-gate-skips-registered-rule-bodies | Spec | Premise is "registering a relation changes which gates read its rule"; registration and `_live` names are gone. `MetaDeclarationGateTest` may survive, the registration seam does not. | **Discard**, unless the gate's body-reading blind spot still exists on `graphitron_` tables (re-derive first). |
| **R942** per-row-view-naming-fails-the-build | Spec | Goal defined entirely in terms of registrations and `_live` names; 46 of 60 cited names are gone. | **Re-derive**: if unregistered-view naming is still a real hazard keep one sentence of it, else **discard**. |
| **R1034** lsp-nodeid-typename-accepts-every-decode-target | Backlog | **Carried from 2026-11-16, unchanged.** Goal: the LSP stops flagging a `@nodeId(typeName: T)` the build accepts, reading the build's node set instead of `graphitron_node_entry` (the authored-`@node` family, now `grep`=0). R876's `d2192619` (2026-11-13) did exactly that: `DiagnosticFacts` reads `graphitron_node` (authored and inferred nodes) and the containers, and `Diagnostics.judgeNodeTypeName`'s message no longer says the type must carry `@node`; it names a `@node` type, an `implements Node` type over node metadata, or an interface or union over nodes. The one population left over, a `@table` type without `Node` over catalog node metadata, is the shape R876 now refuses (`NODE_ID_TYPE_NAME_UNDECLARED_STUB`, see R1033 in §B.2), so the LSP flagging it agrees with the build. `5e847269` refreshed one relation name in the body but not the Goal. | **Discard, recording `d2192619`**, together with R1033. |
| **R660** routine-write-key-capture-unordered | Backlog | **Carried from 2026-11-19: delivered by R876.** The item's claim is that a list-returning `@routine` write "produces rows in no defined order, with no directive the author can write to fix it and no diagnostic saying so". All three are now false. The key capture reads the routine's function table `WITH ORDINALITY` and orders by it (`c80ca8159`), and a write returning its records directly is lifted along its path in the delivered order (`cf9b82795`), so the visible order is the routine's and no longer the unordered `IN` re-read's. The directive is `@defaultOrder(fields:)` on the mutation field naming columns of the routine's own result, and the diagnostic is `ROUTINE_WRITE_PLURAL_UNORDERED` (and `ROUTINE_WRITE_PLURAL_ORDER_NOT_A_COLUMN`) where a plural identifying mutation is backed by a table-returning routine with no stated order (`fc065a143`, `feat!`, Alf 2026-11-18; `graphitron_occurrence_input_order` states which producers owe it). Both of its open Spec questions (which seat carries the order; whether to narrow `requiresReFetch()`) were about the walk's `validateListRequiresOrdering`, which the store's verdict now precedes. Last edited 2026-08-13. | **Discard, recording `c80ca8159`, `cf9b82795` and `fc065a143`**, after a one-line check that no routine-write shape still reaches the walk's `requiresReFetch()` exemption unordered; if one does, that is a node of R876's routine-write work, not a separate item. |
| **R695** javabean-unbound-input-field-lint | Backlog | **New.** Wants a *warning* where an SDL input field binds no member of a JavaBean a `@service` takes, on the premise that `InputBeanResolver.bindJavaBean` is "partial by design" and "a hard reject would break beans that are deliberately partial". The store decided the other way and as an **error**: `MEMBER_UNKNOWN` refuses "an input field nothing takes, under the class its input object is built into: it fills no member under the name it matches on ... so its value would reach nothing", and it excuses a field only where something else takes it (a filter column, a call's parameter or tenant, its own `@condition`). Since this window's service flip (`6af820d`) the store's verdict is the only one a service's input gets, so `bindJavaBean`'s partiality no longer decides anything, and the `LintRule` arm and `SourceLocation` threading the item plans would be new machinery in the walk's service path, which R876's open node deletes. Its record-side twin, a component no field fills, became `MEMBER_UNFILLED` this window on an Alf ruling to refuse. Last edited 2026-08-17. | **Discard, recording `MEMBER_UNKNOWN`**, after a one-line check with a fact document that a bean setter typo draws it. If consumers do need deliberately partial beans, that is a request to relax a stored error (demote it or excuse a shape), a different item stated against the store. |
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
| **R938** view-count-is-paid-at-every-store-open | Backlog | Counts 120 views and `deriveDependencies`; registration derivation is gone, and the view count has moved the other way: **161 four audits ago, 250 three audits ago, 256, then 264 and 268 now** (the 2026-11-14/15 split of `graphitron_entry_defect_rule` into per-stage rule views, the 2026-11-16 refusal views, and this window's service, filter and routine-write rule views; tables 317 to 376 to 382 to 384 to 392 to 396). The cost question is sharper, not dissolved, and R1131's C1 now owns a view-on-view node ordered by execution cost. **Sharpened this window:** R1131 now reports, from the DDL alone, how hard each view is for H2 to plan (`fcb92dd`, `c6b1f57`), bisected the three widest entry-defect rules cold (`241bc01`) and wrote the research up in `2026-11-19-what-h2-spends-a-capture-on.md`, which is the measurement this item asks for, taken per view. | **Fold into R1131** (Discard, recording the view-complexity report and the 2026-11-19 audit) unless a per-open cost survives that R1131's per-capture numbers do not cover; if one does, re-spec against that audit's figures. |
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
| **R677** list-ordering-invariant-enforcement | Spec | `UnlowerableOrderings*`, `AuthoredClaim*`, `MaterializeRegistryGateTest` gone; R876 now owns ordering defects (`UNLOWERABLE_ORDERING`-style). The `MutationRoutineSeatTest` tier it reasons from was deleted (`e446f4c`). **Sharpened 2026-11-19:** which producers owe input order is now a stored fact (`graphitron_occurrence_input_order`, `fc065a143`), with the ruling in `docs/architecture/reference/code-generation-triggers.adoc`, and a plural routine write with no order is refused. | **Re-derive against `graphitron_occurrence_input_order`**; likely subsumed or reducible to a test. |
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
| **R562** synthesised-connection-fields-as-coordinates | Backlog | **Carried, sharpened.** Premise is that a synthesised connection's `totalCount` and `facets` "exist only as emit" with nothing in the fact base. R876 then stored the connection (`graphitron_connection`, `graphitron_edge`, `6be2bbb`, `f91a76d`) and registered every connection, `PageInfo` and facets field (`f3f6285`, `a52cbf8`, `9ebdc2f`); `ExemptionRegistry.OPERATION_KNOWN_GAPS`, the gap it measures by, is `grep`=0. Next the positions a connection's fields fill, `TOTAL_COUNT` and `FACETS` among them, became stored and read (`7cfa62c`), `ConnectionsQuery.byField` reads them, and every connection is a class `ConnectionsEmitter` renders from the store (`238f6ac`); the `ConnectionResult` carrier its body names is gone. | **Close as absorbed by R876** (Discard, recording `7cfa62c` and `238f6ac`) after a one-line check that no reader still treats `totalCount`/`facets` as emit-only. **Sharpened 2026-11-19:** what fills a connection's `totalCount` and facets is now stored per producing occurrence (`47b273a77`), the facets records and the producers' closures read the store (`06d2583b0`), and R606, which the prior audit named as holding the remainder, was itself discarded into R876's `@asFacet` node (`6a7b9ef82`). Nothing of this item is left outside R876. |

### B.3 Recent windows: work landed against a filed item, or its mechanism moved under it

| Item | Status | What changed | Recommended action |
|---|---|---|---|
| **R931** synthesised-type-exact-name-collision | Backlog | Goal: an exact name collision between a type `@asConnection` mints and another registered type is a typed rejection, not last-registration-wins in `ConnectionPromoter.registerSynthesised` (schema-transform side) and "a silently misrouted mint" (capture side). R876's `29a3082fc` delivered the capture side: a name minted more than one way is `MINTED_NAME_CONTESTED` at each carrier, stored as `graphitron_minted_conflict` and never withheld silently, and a declared type that falls short of what would be minted is `MINTED_TYPE_DECLARED_SHORT` (a conforming declared type is accepted by design). `ConnectionPromoter.registerSynthesised` still exists on the walk's side, which R876 is dissolving; last edited 2026-09-07. | **Re-derive against `29a3082fc`**: if the walk-side last-wins cannot be reached once the store refuses the schema first, **discard, recording `29a3082fc`**; otherwise narrow the Goal to the walk side. |
| **R1188** column-coercion-cost-is-bounded | Backlog | **Carried.** Filed 2026-11-16 with two halves. The first, a digit and magnitude bound before any arithmetic in `ColumnValues`, **landed** as `9e78856a2` (`ColumnValues.inRange`, gates waived by the user) and the body's progress note says so. The second, a configurable limit on how many representations or ids `_entities` and `Query.nodes` take, is open with Spec-level questions recorded (where the limit is configured, one limit or two, a finite default refusing requests that succeed today). The Goal still states both halves, and the status says no work has started. The 2026-11-19 window's `d319968ea` extended the landed half to every client value bound at a column (`ColumnValues.fromWire`), which the body does not record either. | **Narrow the Goal to the representation limit** (move the landed bound to a "Delivered" note, or record it as its own changelog line) and **move to Spec**, since the open questions are already Spec questions. Every tree name it cites resolves. |
| **R730** capture-api-residue-after-nodehood-move | **Ready** | **Carried from 2026-11-18.** The item is two things: fix the dangling `{@link}`s in test sources, then extend "the gate" to test sources. Its whole account of the gate ("the `check-link-references` execution of `maven-javadoc-plugin`", the `pom.xml:349` `sourcepath` trap, the `show` level trap, the eleven-reference measurement) describes a mechanism that **no longer exists**: R1131's `1a4c16923` (2026-10-31) moved the gate into javac as `-Xdoclint:reference/protected` on `default-compile`, and the pom comment now says "Main compile only ... The gate never covered test sources". Its motivating seventh site, `WarmStartRefreshTest.aWarmRefreshOverAMultiPackageCatalogCompletes`, was **deleted** on 2026-11-17 (`c7c5ecbe1`), and the six dead `NodeDeclaration` imports it lists are at old paths (`graphitron/.../capture/SdlFactCapture.java` now lives in `graphitron-model`). The goal survives and is now cheaper: one `default-testCompile` execution taking `${graphitron.doclint}`, plus whatever that surfaces. | **Move back to Spec and re-spec against the javac gate**: re-measure the test-source backlog with `-Xdoclint:reference` on `testCompile`, drop the javadoc-plugin traps section, drop the deleted seventh site, and decide the `/protected` scope question for package-private JUnit classes (the pom comment calls widening "a separate decision"). |
| **R234** jooq-embedded-and-udt-input-backings | Backlog | **Carried from 2026-11-18.** Wants jOOQ embedded and UDT records admitted as non-table input backings. R876 took the embeddable half into its plan in the 2026-11-18 window: an embeddable is now an `EMBEDDABLE` field of `sql_table_field` with `sql_embeddable_column` (`cc15da5bc`), `COLUMN_UNRESOLVED` under an input named like an embeddable names it (`d7515b749`), and the node "A nested input under a jOOQ record binds to the embeddable it names" is open on Alf rulings of 2026-11-17 and 2026-11-18. Its cited code, `TypeBuilder.buildNonTableInputType` (`TypeBuilder.java:1688`), is `grep`=0; last edited 2026-07-13. | **Narrow to UDT records** (or discard if UDTs have no consumer), recording that embeddable binding is R876's node; restate the "today" paragraph against the store's `sql_table_field`. |
| **R1108** manual-catches-up-with-owned-connection-engine | Backlog | **Carried from 2026-11-18.** A docs audit taken on 2026-10-27 against the mount-era runtime: `Graphitron.runtime(dataSource, dialect)`, "identity mounting", `SessionMountException`, "A throwing `mount` evicts the connection". R1119 slices 8c.2 and 8c.3 (`e3a3ecf68`, `abe204f25`) replaced that runtime with a `ConnectionSource` the request is given, retired `<mount>`/`<unmount>` (`SessionStateBinding` keeps them only to refuse them), deleted `SessionMountException` and `GraphitronSessionHook`, and rewrote `runtime-api.adoc` and `mojo-configuration.adoc` in passing (both now document `ConnectionSource` and `ConnectionSources.mounting`). Several findings are therefore fixed and several describe code that is gone. | **Re-audit the manual against trunk after R1119's slice 8c** and rewrite the findings list; likely much smaller. Consider folding the remainder into R1119's before-Done documentation bullets. |
| **R468** oracle-ras-session-hook-execution-coverage | Backlog | **Carried from 2026-11-18.** States that session identity "mounts through the `<sessionState>` method hooks (a static `<mount>` method ... passed to `<unmount>`)". Those plugin hooks are retired (R1119 `e3a3ecf68`); mounting is now the consumer's `ConnectionSources.mounting`, so the code an Oracle execution test would cover is consumer-side wiring around a generated source. | **Re-spec** against `ConnectionSources.mounting`, or **discard** if Oracle RAS coverage is now a consumer concern rather than generated code. |
| **R1159** evicted-connection-returns-to-pool-dead | Backlog | **Carried from 2026-11-18.** Title and premise are "a rejected mount poisons the pool" through `PinnedConnection.evict`. The mount is now the consumer source's (`ConnectionSources.mounting`) and Graphitron "owns each lease's lifecycle" through `release()`/`abort()` (`ConnectionRuntimeClassGenerator`); `abort()` is still how a lease that cannot be settled is discarded, so the Agroal hazard it found may well survive on the settle path. | **Re-derive**: reproduce on the current lease (`abort()` after a failed settle) and retitle off "rejected mount"; discard if the lease path no longer reaches `Connection.abort` on a pooled wrapper. |
| **R1139** field-condition-override-over-unbound-argument | Backlog | **Carried, sharpened: now very likely delivered.** Goal: under a field-level `@condition(override: true)`, an argument matching no column is accepted when a parameter of the condition method reads it and rejected when none does. The store has stated both halves since the 2026-11-19 window: a filter argument naming no column is `COLUMN_UNRESOLVED` only where "no override reads it" (`95259039f`), and a slot an overriding condition leaves unread is `ARGUMENT_UNBOUND` (`9134c3673`), both read off `graphitron_occurrence_taken`. The prior audit's one open doubt was whether the walk still refused kreg's `spraakkoder` shape first. **This window `540f078` deleted both walk refusals** (`mintCascadeVerdict`'s use-keyed verdict and `projectFilters`' refusal of an `UnboundArg`), keeping only the case of an argument with its own `@condition(override: false)`, which is not this item's. `defects/filter-column-unresolved` pins the shape: `filmsByBogusKeyAndPrefix` has a field-level override whose method reads `titlePrefix`, an argument naming no column, and only the unread `bogusKey` is refused. The check its Direction says to reuse (`SuppressedSlot`, `ReadSet`) is deleted, and the argument-level item it depends on has shipped. | **Discard, recording `9134c3673`, `95259039f` and `540f078`**, after running the item's own minimal case (`@splitQuery` with a `@reference` condition path, which the pinned fact document does not combine) as a fact document. If that combination is still refused, the refusal is a walk leftover and a node of R876, not this item's Direction. |
| **R509** bulk-dml-payload-input-order | Backlog | **Carried from 2026-11-19.** Its product stance is that bulk mutation payloads match input order "wherever we can make that true by construction", with a lint wherever not, worked per verb (UPDATE by a threaded `idx`, INSERT by pinned engine behaviour, DELETE undecided). Alf ruled on 2026-11-18 where input order is **owed**: only on a plural identifying field, declared by `@lookupKey` on a root field or taken by an insert, a delete or a routine write with one list argument answering a list (`docs/architecture/reference/code-generation-triggers.adoc`; `graphitron_occurrence_input_order`, `fc065a143`). That settles the scope question the item leaves to Spec, makes the obligation a stored fact a refusal can read instead of a walk-side lint, and does not name bulk UPDATE among the shapes that owe it. Its mechanism cites (`TypeFetcherGenerator.emitKeysTransaction`, the `rows<Name>` companion) are walk-side. Last edited 2026-07-21. | **Re-spec against the ruling**: state each verb as "owes input order per `graphitron_occurrence_input_order`, or does not", decide whether bulk UPDATE is in, and replace the proposed lint with the store's refusal where one applies. Check the same ruling against **R242** (Spec, §C.4), whose positional-`null` contract it now partly states ("a row gone by the time it is read leaves a `null` in its slot"). |
| **R1110** single-field-over-to-many-path-returns-arbitrary-row | Backlog | **Carried from 2026-11-19, light.** The defect is live for reads (`OrderByResolver`, `ProjectionUnitRenderer` and `SplitRowsMethodEmitter` all still exist). Two things moved under it: the "target-side columns cover a candidate key" primitive it says both it and a sibling item need is now a stored fact, `graphitron_join_path_step_to_one` (`b346c4f03`), and for the routine-write case Alf ruled the second of its two "Which fix" options, a single-record field over a path that fans out is a defect, refused as `ROUTINE_WRITE_PATH_FANS_OUT` (`578eab1e7`). | **Keep; restate "Open for Spec"** to name `graphitron_join_path_step_to_one` as the to-many test and record the routine-write ruling as precedent for the read side (or say why reads differ). A good candidate to move to Spec now that the primitive exists. |
| **R592** lint-rule-reference-page | Backlog | **New.** Wants a manual page for "a closed set of fifteen rules" in `LintRule`, on the grounds that `diagnostics-glossary.adoc` is error-only, "a warning has no glossary-shaped home", and a consumer configuring `<lint>` must name ids documented nowhere. `LintRule` now holds **22** ids. More to the point, the store answered the question for its own warnings this window: every defect code, warnings included, has a guidance fragment and an anchored entry on a reference page rendered from the store (`b489997`, with severity per entry), the editor links to it (`5684aa4`), `<disabledRules>` takes a stored warning's code beside a lint id (`5e2ab52`), and the first walk lint, `splitquery-redundant-on-record-parent`, was retired into the stored warning `SPLIT_QUERY_REDUNDANT` (`e38c49c`), with the commit saying the service-child and discriminated-interface-child lints "join it when they move". So the page it plans would document a set that is draining into one that documents itself, and the coverage meta-test it proposes (`DiagnosticsDocCoverageTest`-shaped) has a store-side equivalent already (the store refuses to create itself while a deferral has no guidance). Last edited 2026-08-04. | **Re-spec, smaller**: decide with R876's owners which `LintRule` ids are staying in Java (the nine `ENGINE` naming rules and the version-lag advisories look like they are) and document only those, on or beside the rendered defects page, so `<disabledRules>` has one place to look ids up. Discard if the ruling is that every lint becomes a stored warning. |
| **R654** resolver-coordinate-verdict-precedence-sweep | Backlog | **New (it sat in §C.3 for `SourceRowDirectiveResolver`).** It lists four seats where a reflection or binding rejection masks a coordinate-level verdict. Two are gone. The `@sourceRow` seat went with the directive. The "root `@service` residue" seat, whose examples are the mutation payload checks at the `FieldBuilder` arms ("orphan service carrier, `$source` sigil rules"), lost the sigil rules outright this window (`f67e17b`: `FieldSourceSigil` and `FieldBuilder`'s handling deleted) and the rest by transfer: since `6af820d` the walk's errors at service fields are not what a build reports, the store's verdicts are, and a stored verdict has no reflection step ahead of it to be masked by. `@externalField` (`validateComputedField`, `reflectExternalField`) and `@condition` with `@lookupKey` (`FieldBuilder.projectForFilter`) still resolve, though R555 (Ready) folds `@externalField` into `@service`. Last edited 2026-08-13. | **Re-derive the two surviving seats against the store**: for each, write the masking case as a fact document and see which message the build gives now. Narrow the item to whatever still masks, drop the `@sourceRow` and root-`@service` bullets, and discard if neither does. |
| **R655** service-phase-split-residue | Backlog | **New.** Three independent polish tasks on the walk's `@service` classification: pin the `Scalar` half of `ServiceDirectiveResolver.classifySourcesCoordinate`'s second trigger, add a `Table` filter to `inferBindingsByType`'s decoded overload (`ServiceCatalog.DecodedParam`), and rename `ServiceCatalogTest`'s `reflectServiceMethod_` methods (39 now, 41 in the body). Every name resolves, and every one is on the path this window's service flip left dead for consumers: a service the store owns is emitted from the store or generation stops, and the walk's errors at service fields are the store's to judge (`6af820d`). R876's open node "The walk's service path is gone" deletes the three classes. The item itself says none of the three breaks a contract. | **Discard** when that node closes, or now with a sentence saying so: a test, a filter and 39 renames in code scheduled for deletion buy nothing. If the first bullet's shape (a record-parent `@service` with no batch parameter returning a scalar) is not pinned by a fact document, hand that one case to R876's node. |
| **R319** warn-on-pruned-unreachable-types | Backlog | **New, light: unblocked, not stale.** Wants a build warning naming each output type no field reaches. Its 2026-08-06 fact-base note says the deliverable "survives as a detection query over" a derived reachability relation. Both halves now exist: `graphitron_type_schema_reach` states which object, interface and union types the schema leads to (`e459c34`, Alf 2026-11-19, with reach defined as what the schema leads to, not what a result reaches), and a warning-severity defect is logged by the build and suppressible by `<disabledRules>` or `<excludedTypes>` (`8d41905`, `5e2ab52`). Its body still speaks of R279's reachability-pruned classification and R317's single-pass walk. | **Keep; restate as one stored warning** (an anti-join of captured types against `graphitron_type_schema_reach`, with a guidance fragment) and drop the R279/R317 framing. Cheap enough to move to Spec, or to hand to R876 as a node beside `SPLIT_QUERY_REDUNDANT`. |
| **R588** node-without-metadata-diagnostics | Backlog | **New, light.** The trap is live, but both messages it quotes are the walk's. The first, `column 'id' could not be resolved in the jOOQ table; did you mean: ...`, is now the store's `COLUMN_UNRESOLVED`, whose hint is ranked in `graphitron_entry_defect_hint` (`33d0bcf`) and so still offers the "actively harmful" candidate list; the second is now `NODE_ID_CONTAINING_TYPE_NOT_NODE`. Its plan, "a targeted rejection at the classifier", names the side that no longer reports. | **Restate against the store**: a rule that pre-empts `COLUMN_UNRESOLVED` and its hint where the type implements `Node` over a table with no node metadata, with its own guidance fragment naming `@node`. Check first whether `NODE_WITHOUT_KEY` or `NODE_METADATA_UNREADABLE` already fires there. |
| **R881** write-carriers-never-read-column-nullability | Spec | **Light, not a flag on the plan.** Promoted and rewritten on 2026-11-19 (`e0d6284`, `cdeffbb`); names resolve. Its open question 3 asks for a ruling on whether the unrouted advisory is "a sibling id, a widened `dml-write-length-unrouted`, or none", arguing that retiring a lint id breaks every consumer configuration that disables it. The same day R876 retired a lint id as a breaking change with Alf's agreement (`e38c49c`: "No consumer configures `<lint>` in FS main or this repository") and made stored warning codes suppressible through `<disabledRules>` (`5e2ab52`), which adds a fourth option the question does not list: a warning-severity defect in the store. | **Add the stored-warning option and the `e38c49c` precedent to question 3** before the Spec review; no other change. |

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
restate the "today" paragraph against `744376c8`), R736, R807, R632 (`intent_authored_claim_conflict`). **R534** (`record-key-decode-null-write-erasure`) is **resolved**: it moved to Spec on 2026-11-19 (`b1d5864`) rewritten against the service flip, cites the renamed `..._besideADecodedNodeId_writesNull` test and hands the classified helper's residue to R876's node. One nit left by the same day's `6af820d`: a rejected alternative names `GraphQLRewriteGenerator.unemittedProjections` and `unemittedOutboxes`, both removed by that commit; drop the two names at the Spec review. **R1000** (`upsert-names-its-conflict-target`): cites `EmittedAnchor.arguments` as a twin site R998 converts; `EmittedAnchor` was removed (`dbf5acf`, `graphitron_type` is written by macro expansion) and `graphitron_field_path` was already gone. Action: **re-count the upsert sites** (34 files still call `onDuplicateKeyUpdate`) and drop the two dead names; the guard it proposes is unaffected. **R476** (`connection-helper-totalcount-redaction`): the gap is still real, since `ConnectionsEmitter` routes only the `FACETS` member through `ErrorRouter` and the `TOTAL_COUNT` member is still a bare `totalCount(env)` call, but the template it points at, `ConnectionFetcherClassGenerator.facetsDelegate`, was deleted with the per-type connection fetchers (`238f6ac`). Action: **repoint the template to `ConnectionsEmitter`'s facets arm**; the fix is now one arm in one emitter. **R521** (`generated-output-hygiene-sweep`): three bullets describe `ConnectionResult` (its 11-argument constructor, the unread `beforeCursor`, its multitenant variant); the class is gone and connections are store-rendered classes. Action: **strike those bullets or restate them against the generated connection classes**; the rest of the sweep is unaffected. **Note, not a flag: R606** (`facets-container-derives-from-the-store`) is named by `a52cbf8` as the item that retires the new `Facets` command row, so it is live; its opening claim that the facets shapes "exist only in the assembled-schema synthesis" is one paragraph out of date and should mention that row when next edited.

**Carried from 2026-11-19: R1129** (`mutation-transaction-hook`, Backlog, last edited 2026-10-29): its carried-over design registers the hook on `Graphitron.runtime(dataSource, SQLDialect.POSTGRES)`, the mount-era signature; the runtime is now `Graphitron.runtime(dialect)` with a `ConnectionSource` given per request (R1119, `e3a3ecf68`; the prior audit's mount-era sweep missed it). It also promises the hook "at the start of every top-level mutation field's transaction ... `@service` mutations included", which predates `@service(transactionControl: SERVICE)` (`3dfc0ed57`): a service that owns its transactions has no Graphitron transaction for the hook to run in. Action: **refresh the registration example and add the service-owned case to its open question** before it moves to Spec; the item says the design is a draft to be reviewed then, so this is a cite refresh, not a re-spec. **R1125** (`defects-without-an-sdl-position-need-a-home`, Backlog): names `DefaultPageSizeDefects` "and its siblings" as the defect readers a supertype would migrate (`grep`=0 since the defect rules moved to the store), and its first waiting consumer, R1120, has shipped. **R1200** (filed 2026-11-18) is a third consumer: its Open question ("the store has no defect at the grain of a graph's configuration", a new problem relation keyed by graph) is this item's Goal restated, filed without naming it. Action: **add R1200 to "Consumers waiting on it", give R1200 a `depends-on` edge or a sentence pointing here, and drop the dead reader name.** With three consumers the item is worth moving to Spec.


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
**R427 (Ready), R555 (Ready), R684 (Ready), R730 (Ready)**, R152, R381, R594, R536, R397, R528, R557.
**R557** (`split-query-marker-sweep`), carried: beyond `CatalogBuilder.projectFieldClassification`, its model enforcer `TenantBindingIndex.sweepUnreachedMarkers` is described as covering `@globalData`, a directive removed by `a45a9f590`, and one of the inert positions it wants a verdict for, `@splitQuery` beside a child `@service`, is now an engine lint read off the store (`splitquery-redundant-on-service-child`, `936808ba9`), which is the shape a store-side sweep would take. **Sharpened this window:** the "record-parent implicit split" it lists as an inert position is now the stored warning `SPLIT_QUERY_REDUNDANT` (`e38c49c`), raised on a table-bound field under a class-backed parent and on every payload data field, with the service-child and discriminated-interface-child lints named to join it. Two of its inert positions therefore have a stored verdict and the code is already named for the rule; **re-spec it as the remaining arms of `SPLIT_QUERY_REDUNDANT` plus a refusal for positions that are neither consumed nor redundant**, and drop the `CatalogBuilder` seam.
**R536** (`always-empty-service-channel-slots`), sharpened: the four `*Service*Field` variants and `FieldBuilder.buildServiceField` it wants reshaped still exist, but since `6af820d` they are the walk's service path, which R876's open node deletes, and `MappingsConstantNameDedup` is `grep`=0. **Re-derive at that node's close; likely discard**, since the dead slot goes with the variants.
These four Ready items are the highest-value refresh before pickup: each names `CompletionData` (and R555
also `ClasspathScanner`, `PkLessParent`, `intent_authored_field_claim`; R684 also `CatalogFacts` and
`LspSchemaSnapshot`).

**C.3 `@sourceRow` / lifter / `SourceRowDirectiveResolver` removal.** R180
(`record-parent-column-read-helper`, `SourceRowDirectiveResolver`, `backingClassOf`), R654 (now §B.3), R412
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
| **R1168** shadowed-error-handler-is-reported | Ready | **Carried, sharpened a third time.** Three of its plan sections are now out of step with the tree. (1) "Platform throwables" plans a capture R876's `8cd9d91a6` (2026-11-16) **delivered** (the classpath gatherer reads `jrt:/` as a source of kind `PLATFORM`). (2) The generated `GraphitronClientException` section rests on `graphitron_runtime_class` (`grep`=0) and is an open R876 node since `edac463f9` (Alf, 2026-11-17), with `scenarios/error-handler-client-exception` pinning current behaviour. (3) **New this window:** "Warnings in the build log" **landed under R876** (`8d41905`), which the body now says in one sentence while keeping the full plan below it; and its "Demotion" section plans "a list parameter naming codes to report as warnings" beside decision 1's claim that the lint channel's `<disabledRules>` seat "has no path" to defects, where `5e2ab52` made `<disabledRules>` and `<excludedTypes>` suppress a stored warning by code and deleted the `LintConfig.validated` a review round compares against. Demotion (error to warning) and suppression (warning to nothing) are different acts, but they now share a configuration seat the plan does not mention. | **Move back to Spec**: strike the platform-capture and build-log sections as delivered (`8cd9d91a6`, `8d41905`), replace the client-exception section with a dependency on R876's node, restate Demotion against `<disabledRules>` and `graphitron_entry_defect_site.suppressed`, and consult the principles-architect. The shadowing rule itself stands, and with three sections delivered or moved the item is now mostly that rule. |
| **R427** relevance-ranked-search | Ready | `CompletionData`, `SearchBacking`, ... (19 of 59 names gone). | **Re-spec the Current state section** before pickup. |
| **R555** deprecate-externalfield-fold-into-service | Ready | `CompletionData`, `ClasspathScanner`, `PkLessParent`, `AuthoredClaimConflicts`. | Refresh cites before pickup. |
| **R684** consumers-share-relations-not-queries | Ready | `CatalogFacts`, `LspSchemaSnapshot`, `CompletionData`. | Refresh cites; part of its premise (CatalogFacts retirement) shipped. |
| **R730** capture-api-residue-after-nodehood-move | Ready | `CompletionData` retired by R876; more importantly the gate it extends moved into javac and its seventh site was deleted. | See §B.3: **back to Spec**. |
| **R1146** node-lookup-invalid-id-returns-null | Spec | **Resolved.** Restated on 2026-11-19 (`46afc11`) as the prior audit asked: the plan judges an id at the node fetcher from a node query of its own, `SlotParse` and `emitDecodeAndGroup` are gone from the body, and a "Sequencing against R876" section states both orders and that the claim on R876's node is agreed before work starts. One later commit touches its seat: `c8eea51` rules the node fetcher's argument by name (`graphitron_occurrence_producer_node`, `NODE_FETCHER_WITHOUT_ID`) while "the fetcher class still spells id and ids". The item already says to re-read its sequencing section at pickup. | None beyond that re-read; name `graphitron_occurrence_producer_node` as where the decoded argument is stated when the section is next edited. |
| **R427** (addendum) | Ready | **Carried, still undecided:** `28c66dd` (2026-11-12) added an open question raised from R876's connection thread, "Does a hit being an edge reopen decision 1?", marked not decided. | A Ready item carrying an undecided question that may reopen a spec decision should **go back to Spec until it is answered**, or the question be ruled out of scope in the body. |
| **R1113** transactional-outbox-directive | **In Review** | **Carried, and now at the gate.** Moved In Progress -> In Review on 2026-11-19 (`fcbceef2`) with phase 4's shipped commits and a handover. The three stale cites the prior audit listed were not touched and now sit in a body a reviewer reads as the record of what shipped: the Rejections bullet cites the `DefaultPageSizeDefects` precedent (`grep`=0 since the defect rules moved to the store); Phase 4, marked shipped, places the classification "in the emitted runtime, beside `isMountFailure`", renamed `isAcquisitionFailure` by R1119's `e3a3ecf68`; and the same section says "`acquire` calls the `DataSource`", where a request's connections come from the `ConnectionSource` it is given. Phase 1b's "mounted handle" describes the retired plugin `<mount>`. This window's `edb8b21` also made the outbox's JSON helper take any `Iterable`, and R1201 was filed against this item's `event` default and flat payload. | **Fix the three cites as part of the In Review -> Done review** (the reviewer should check Phase 4's description against `ConnectionRuntimeClassGenerator` as it is, not as the plan names it), and have the Done entry say that R1201 replaces the coordinate-default event name, so the changelog does not present it as settled. |
| **R1160** tests-read-shared-fact-stores | In Progress | **Carried, sharpened.** `ServiceEmissionCensusTest` absent; its `@capability` / `@exemplifies` header vocabulary is not in the tree while the body still uses it. Its plan still lists `GraphCountGrowthGateTest` as a store holding chosen graphs; R1131's `64090f7d8` replaced that test with a plan-reading gate on 2026-11-17. | Minor; author is mid-flight, refresh when next edited. |
| **R1184** database-policy-gatherer | Ready | **Carried, minor.** Promoted on 2026-11-18; every other unresolved name is a proposal, a PostgreSQL catalog name or an environment variable. One line (setting names checked "against the `SessionState` mount") names the runtime class R1119's slice 8c.2 deleted; session settings are now written by the consumer's `ConnectionSources.mounting`. | Repoint the sentence at `<sessionState>`'s handle type and `ConnectionSources.mounting` at pickup. |

**Carried resolution: R1167** (`error-dispatch-matches-graphitron-9`, Ready) stays clean: the
`graphitron_ast_error_database_handler_entry` relation its sentence names was captured again by R876's
`56433aef6` (2026-11-16) and is still present. Its scope (dispatch order) was not touched by this
window's one `@error` change (`65ba96410`, an enum-typed `@error` field read by a getter returning a
Java enum), which judges how a field is read, not which handler claims a throw.

R467 (`upgrade-graphql-java-26`), R1137 (`rejected-type-cascades-into-secondary-diagnostics`), R1119 and
R1131 are clean, as are the Spec item R1169, the carried Backlog items R1183, R1186, R1191 (`maxRentalRate`
and `RequestNumberBoundsTest` resolve; `filmsByWireIdCondition` is generated from `multitenant.graphqls`),
R1192 (its "one lease per distinct connection key, released when the operation ends" is exactly what
R1119's `e3a3ecf68` shipped, so the premise is now confirmed by code) and R1193 (`OutboxFailureSettleTest`
and `timedOutTenants` resolve), R1196 (`GlobalRead`, `DefaultSource`, `dslDefault` and
`GraphitronConnectionInstrumentationGenerator` all still resolve; the 2026-11-19 window's `@globalData` removal,
`a45a9f590`, deleted the marker ladder that fed the split but kept the split itself, so the fold it asks
for is still owed and now has one fewer reader), R1198 (no tree names), the 2026-11-18 window's two
items, **R1199** (every runtime name resolves, and its `depends-on` edge to R1119 is live) and **R1200**
(clean; see R1125 in §C.1 for the item its open question belongs to), and this window's **R1201**
(`outbox-events-are-schema-types`: its `depends-on` edge to R1113 is live, and its unresolved names are
Apollo grammar terms and proposals). R1099, R1201 and the routine items that write
`$source.<column>` in an `argMapping` are untouched by this window's retirement of the `$source` *field*
sigil (`f67e17b`), which was `@field(name: "$source")` on a payload's data field. R505
(`tenant-index-parent-row-routing`) is current. R1181 (`infinity-in-a-date-or-timestamp-order-column`) dropped its `depends-on` to the shipped
R1180 and now names `ColumnValues.writtenByDatabase` and `ConnectionHelper.cursorTexts`, both present.
R1119 cites `TenantId`, `SessionState`, `mountCauseFinder`, `rejectedIdentity`, `@defaultTenant`, the rest
of its slice 8c removals and the field-level voucher marker (`issuing_field`,
`VOUCHER_FIELD_REPEATED`, `VOUCHER_ISSUE_ABSORBS_NOTHING`) only in its rulings, slice table and
retired-vocabulary list; R1131 and
R876 cite `GraphCountGrowthGateTest` only in progress notes recording its replacement; that is history,
not drift.

## D. Structural

- **R901 `trailing-segments-is-a-boolean` is `status: Done` in `roadmap/*.md`** with a `depends-on:
  [derived-read-cost-is-a-shape-problem]` edge, last updated 2026-09-03, and has **no changelog entry**.
  The workflow deletes the file on Done and records it in `changelog.md`. Its body says "Resolved by R876: it
  was a count of nothing, and it is a name now". Recommended action: finish the transition (changelog
  entry, delete the file) or, if it is intentionally parked behind R876, say so in its body and move it
  back to a non-Done status.
- **`next-id` is ahead of every id.** The counter is `R1202` and the highest id on the board is R1201,
  as it should be after one filing (R1201); R1194, R1195 and R1197 remain allocated-and-discarded
  gaps, each with a changelog line.
  R1182 still has no file (absorbed into R876 on allocation), a harmless gap.
- **Carried: two items left the board with no changelog line, still unwritten.** The workflow records
  every Done and every discard in `changelog.md`, and the changelog is the only place a removed item's
  outcome survives. This window wrote one changelog line (the `$source` sigil break, under R876), correctly, but
  did not backfill these two:
  - **R1189** (`condition-directive-reading-consolidation`): `1b7287117` (`chore(R1189): Backlog -> Done`)
    deleted the file and regenerated the README, and nothing else. The delivery is `30600d073` (`feat!`: a
    `@condition` naming no class or no method fails the build at the directive). Compare R1190 and
    R1187, which also skipped the gates at the user's direction and do carry their lines.
  - **R1185** (`connection-hook-takes-the-key`): `a1d9bb43a` (`chore(R1119): the plan absorbs R1185 ...`)
    deleted the file and recorded the absorption in R1119's body, but wrote no "Discarded" line, unlike
    R1050, R1194 and R1195, each absorbed with one.

  Recommended action: **add the two missing changelog lines** in the next roadmap commit (a Done line for
  R1189 naming `30600d073` and noting the waived gates; a Discarded line for R1185 naming R1119).
- **Carried, closed as a note: seven `wip(R876)` subjects reached trunk on 2026-11-18** (`37749f040` and
  six siblings). Trunk is fast-forward only, so they stay; each is a `feat` by content, which whoever
  writes R876's changelog entry should know. **No `wip`, `draft` or `spike` subject reached trunk this
  window**: all 73 subjects are Conventional Commits with a type and, bar two `docs`, a scope.
- Sixteen items carry a non-empty `depends-on:`, all resolving to existing files (R1201 added an edge to
  R1113 this window, an item now In Review; the edge stays valid until R1113's file is deleted on Done,
  when it should be dropped in the same commit).
  Six of them depend on R876 (`derived-read-cost-is-a-shape-problem`), which means **R876 is a
  single point of staleness**: any design change there cascades across R898, R851, R1099, R697, R1096,
  R901 and the §B.2 set. R857 depends on R872 (`warm-capture-empties-unpartitioned-catalog-relations`),
  which exists. R1192 and R1199 depend on R1119, R1168 and R1171 on R1167. One edge is **missing**: R1200's
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
   (`@globalData`, and this window the `$source` field sigil), by absorption into its own plan (R606 and
   R722, closed as it went; R234 and R1168's client exception still are not), and by plain delivery
   (R660, and now very likely R1139). **This window added a fourth: condemnation.** The service flip
   (`6af820d`) deleted almost nothing, so no cited name went dark, yet it turned the walk's service
   classes into code a named node deletes. Items that polish those classes (R655, R536, R654's service
   seat, R695's `bindJavaBean`) cite names that all resolve and describe work nobody should do. The
   name sweeps cannot see this; only reading what a commit says it stopped using can.

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
   now survived ten audits; R209 (§A) is the third. Together with R1189 and R1185's missing changelog
   lines this is perhaps ten minutes of bookkeeping that would remove five recurring lines from this report.

6. **The Ready set is where stale prose bites soonest.** Of the ten Ready items, **R427, R555, R684, R730,
   R838, R1168 and R1184 carry stale cites**, and R427 also carries an undecided question; R467, R1137 and
   R1167 are clean. Two should go back to Spec rather than be refreshed in place: **R1168** (one plan
   section delivered, another now an R876 node with a pending Alf ruling) and **R730** (the gate it
   extends moved into javac, so its measurement and both traps it warns about describe a deleted
   mechanism). R1184 was promoted at 07:40 UTC on 2026-11-18 and carries one runtime name (`SessionState`) that
   R1119 deleted fifty minutes later. Only R1168 was touched this window, by one sentence, so all seven
   flags stand, and R1168's grew. **The In Review set now has the same problem in a sharper form:** R1113
   reached the Done gate with three cites to names that no longer exist, one in a section marked shipped
   (§C.5). A reviewer is the last reader before that text becomes the changelog's source.

7. **A reachable baseline makes the audit cheap, and the two sweeps take turns.** This run deepened
   the clone to reach the prior audit's commit and diffed against it. The removed-names test found one
   nit (R534's two `unemitted*` names) and pointed at R654 through the `$source` sigil; the `feat`/`fix`
   subject sweep found the rest: R695 through `MEMBER_UNFILLED` and its sibling `MEMBER_UNKNOWN`, R592
   through `<disabledRules>` and the rendered defects page, R655 and R536 through the service flip, R319
   through the reach relation, and it settled R1139 through `540f078`. **R695 is a miss by earlier
   audits**: `MEMBER_UNKNOWN` predates this window, and the item only surfaced because its record-side
   twin landed. Runs should keep doing both sweeps, occasionally read Ready bodies end to end, and when
   a new defect code lands, grep Backlog for the item that asked for it as a lint.

8. **R876 delivers or overturns Backlog items without closing them.** R898 was the clear case four audits
   ago (`431e604`); R852, R902, R1033, R562, R1034 and R931 followed. The 2026-11-18 window added **R234**
   (embeddable input binding, now an R876 node) and the second half of **R1168** (the client exception,
   now an R876 node); the 2026-11-19 window added **R660** (delivered) and **R1139**, which this window's
   `540f078` all but closes; this window adds **R695** (the store refuses what it wanted linted). The
   contrast is **R722** and **R606**, each discarded with a changelog line as R876 took its subject:
   that is the pattern that keeps the board honest, and it is the practice for absorptions by ruling
   but not yet for deliveries by commit. The discard set this audit recommends (R209, R899, R761, R861,
   R758, R1033, R1034, R562, R898, R1109, R660, R193/R213 after a residue check, R931, R1139 and R695
   after a one-case check, and R655 when the walk's service path goes) is now seventeen items that each
   need one changelog line and a file deletion.

9. **Gate-waived work is now recording itself properly.** The 2026-11-17 audit found R1189 and R1188 half
   recorded. R1187 (gate-waived, 2026-11-17) carries its changelog line and says the gates were
   waived, and the same-day filing-and-folding episodes (R1194, R1195, R1197) left changelog lines. The
   two older gaps (R1189's line, R1188's narrowed Goal) are still owed.

10. **Rulings are landing as docs and store facts the same day, and filed items are not being read against
    them.** The 2026-11-19 window's docs diff added fifteen dated Alf rulings (input order, fan-out,
    `@globalData`); this window adds more dated 2026-11-19 (a field nothing reaches has no fetcher; a
    redundant `@splitQuery` is admitted with a warning; a record component no field fills is refused,
    not defaulted; a `@service` may hand back any `Iterable`; the node fetcher's argument is ruled by
    name; a store refusal keeps the fix its walk message suggested). R509, R1110 and R242 are still
    Backlog and Spec items whose open questions the earlier rulings answer in part, and R319, R557,
    R695 and R881's question 3 now stand in the same relation to this window's. A ruling's author is
    best placed to grep active bodies for its subject when writing the docs commit; this audit is the
    backstop.

11. **The lint channel is draining into the store, and four items still plan around `LintRule`.**
    `e38c49c` moved the first walk lint to a stored warning and named two more to follow; `5e2ab52`
    made stored warnings suppressible from the lint configuration; `8d41905` logs them in the build.
    R695 (a new `LintRule` arm), R509 (a `BuildWarning.LintFinding` slug in the `LintRule` registry),
    R592 (a page for the `LintRule` set) and R881 (question 3) were all written when a warning could
    only be a lint. Each should say "warning-severity defect" where it says "lint rule" unless there
    is a reason it must be computed in Java. R555 (Ready) renders its deprecation through
    `no-deprecated-directive-usage`, an `ENGINE` rule read off the AST, which is the kind that looks
    set to stay; confirm that at pickup.

---

_Review date: 2026-11-20._
