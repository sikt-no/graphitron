# Roadmap staleness audit: 2026-07-15

A point-in-time review of every active roadmap item under [`roadmap/`](../)
against the **current** state of the codebase on `claude/graphitron-rewrite`
(HEAD `bab6f35`, committed 2026-07-14 21:34, audited 2026-07-15). The goal is to
find items whose premise no longer holds: work already shipped, constructs
renamed or removed, dependencies that have since landed, or specs grown stale
enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-07-14` staleness audit, which has been deleted;
only the latest staleness audit is retained. Three siblings in this directory are
**not** staleness audits and are left in place:

- `2026-06-16-source-operation-target-reframe.md` is the `(source, operation,
  target)` reframe analysis, the permanent lineage document for **R316** (Done).
- `2026-06-30-release-planning.md` is the first-release scoping working document,
  meant to be edited in place as scope iterates. It reads further behind again:
  it still names none of the connection-lifecycle, faceted-search, scalar-ID,
  or SourceKey-decomposition work that closed or opened in the last windows, so
  its MUST/SHOULD tables lag further. Refreshing it is out of scope for this
  staleness pass.
- `2026-07-04-r222-r333-conformance-analysis.md` is the R222/R333 conformance
  analysis, a companion to the R314/R333 design session. It is a targeted
  implementation-vs-spec conformance record, not a point-in-time staleness
  review; left in place. (R333 reached **Ready** this window; the analysis was a
  sign-off input and stays as lineage.)

`classification-test-dsl-inventory.md` is R281's permanent corpus-retirement
inventory. **Its long-carried "superseded, historical" banner was finally added
this window** (top of that file, "banner added 2026-07-14"), resolving the
observation the last four audits carried. No further action; it stays as lineage.

## Headline: the audit loop closed again, then re-drifted from the same-window churn

The prior audit's six §C flags were drained in `3b7ed93`
("roadmap: drain the 2026-07-14 staleness audit's six fresh-drift flags"): R202,
R201, R10, R71, R236 re-anchored and R180's R461 tense updated. Two of those held
clean, but **the drain landed near the *start* of a busy code window**, and later
commits in the same window moved the very anchors it had just re-set:

- The three items anchored in **`FieldBuilder.java`** and **`GeneratorUtils.java`**
  (**R202**, **R201**, **R180**) re-drifted because R458 slice-1's self-FK fix
  (`116f706`) and R431 slice-1 (`bab6f35`) edited `FieldBuilder.java`, and the
  faceted-search / dimensional work edited `GeneratorUtils.java`, all *after* the
  drain.
- **R71** re-drifted because **R431 slice 1 landed** (`bab6f35`, delete
  `SourceKey.target`), moving every `SourceKey.java` line it cites.
- **R222** (the dimensional-model umbrella) re-drifted for the same reason: R431
  slice 1 executed the `target`-deletion half of R222's own plan, so R222's
  current-state `SourceKey` inventory now describes a shape that no longer exists.
- **R10** and **R236** held exact: `ConnectionPromoter.java`'s only churn
  (`2c8aac4`, R13 rework) landed *before* the drain, and `BuildContext.java` was
  untouched this window.

Net: **0 §A / 0 §B / 5 §C.** §A and §B are empty for the second window running.
All five §C flags are drift, four of them *introduced this window* (R202, R201,
R71, R180) and one factual-shape drift (R222); none is a change of substance.

## Changes since the 2026-07-14 audit

**45 commits** landed between the prior snapshot (HEAD `32169ba`, 2026-07-13
22:32) and this one (HEAD `bab6f35`, 2026-07-14 21:34); one is the audit commit
itself (`db8ee36`), so **44 are post-audit work** in a dense ~23-hour window.
Beyond the drain (`3b7ed93`) and a Spec-tier triage refresh (`692df93`,
`6c94d88`: R222/R92/R109 and R34/R335/R381/R45/R212), five things drove it:

**1. Faceted search (R13) closed.** **R13** (`faceted-search`) ran
In Review -> **Done** (`bff4388`, "faceted search on @asConnection approved")
after a rework pass (`2c8aac4` addressed review findings 1-5). Its 1 592-line file
self-deleted. This was the prior window's big active build; it is now shipped.

**2. The `SourceKey` decomposition went active and landed its first slice.**
**R431** (`decompose-sourcekey`) drove Backlog -> Spec -> Ready -> **In Progress**
across the window (`49d0b66`, `2089d2e`, `38d9c7e`, `19f8c54`, `25a8c75`) and
landed **slice 1** (`bab6f35`, "delete `SourceKey.target`"): the `TableRef target`
component and its two compact-ctor invariants are gone, `SourceKey.java`
360 -> **338**. This is the source-side pivot the last three audits flagged as
"still pending." It is no longer pending; it is executing, and the ride-along
items (R71, R234, R314, R432, R222, R471) now need re-checking each slice.

**3. Node-id crash hardening.** **R477** (batch node-id wrong-arity guard) was
filed and driven Backlog -> Spec -> Ready -> In Review -> **Done** all in-window
(`72762f9` .. `0761713`; guard at `cb42d90`), self-deleting on Done.
**R479** (`connection-cursor-decode-crashes`) was filed -> **Spec** (`ae7967a`).

**4. Dimensional-chain and keyshape sign-offs.** **R333**
(`coordinate-lowers-to-datafetcher-queryparts`) landed its level-1 closure oracle
(`446ad39`; new `EmittedMethodClosure`/`MethodClosureOracleTest`) and reached
**Ready** (`bf9e2da`). **R478** (`keyshape-sealed-variants`) was filed and reached
**Ready** (`289d7ce`). **R314** remains **Ready**.

**5. Three in-place re-scopes / pivots.** **R273** narrowed from the NodeId
mismatch-semantics spec to just the bare scalar-ID argument arm, Spec -> Backlog
(`a6c8f0a`; file `nodeid-skip-mismatch-error-surfacing.md` ->
`bare-scalar-id-arm-modernisation.md`). **R34** pivoted from the sis migration
tracker to LSP `@node`/`@nodeId` quick fixes (`9ed5253`; `sis-rewrite-migration.md`
-> `nodeid-migration-quickfix.md`). **R298** pivoted from a one-shot federation
contract check to Rover/GraphOS integration (`5adf2d8`, `ddf479f`, `39b2be2`;
`federation-tag-first-client-contract-check.md` -> `rover-graphos-integration.md`).

**Terminal closures this window (Done, both self-deleted):** **R13**
(`faceted-search`), **R477** (batch node-id wrong-arity guard, created and closed
in-window).

**New items on the board (all fresh, none stale):**

- **R475** (`conditions-method-duplicate-param-names`, **Backlog**): generated
  `<Type>Conditions` methods break on same-named filter fields across siblings.
- **R476** (`connection-helper-totalcount-redaction`, **Backlog**): route
  `ConnectionHelper.totalCount` failures through the `ErrorRouter` redaction.
- **R478** (`keyshape-sealed-variants`, **Ready**).
- **R479** (`connection-cursor-decode-crashes`, **Spec**).
- **R480** (`oneof-augment-defeated-by-descriptions`, **Backlog**): `@oneOf`
  definition augment defeated by descriptions quoting the definition.

**Board accounting.** **135 item files** today, up from the prior audit's 131.
The delta reconciles as `131 + 5 - 1`: five new ids (R475, R476, R478, R479, R480)
minus one deletion (R13, Done). R477 was created and self-deleted in the same
window (net 0); R273 / R298 / R34 were renamed in place (net 0). Status
distribution: **115 Backlog, 13 Spec, 3 Ready (R314, R333, R478), 1 In Review
(R474), 3 In Progress (R347, R431, R458); zero Done**. A non-recursive
`^status: Done` grep over `roadmap/*.md` returns nothing (tombstone-free for the
eleventh window running). No duplicate `id:`; `next-id: R481` with max allocated
id **R480**. A `depends-on` sweep over all 135 files found **no dangling slugs**;
none points at a slug deleted or renamed this window. The board is structurally
clean.

**Net effect on flag counts: 5 flagged, 130 current.** §A and §B are empty; all
five flags are §C, four of them fresh drift and one a factual-shape update.

## Scope and method

All **135** `R<n>` item files were reviewed (plus the non-item
`inference-axis-coverage.adoc` placeholder, correctly excluded). Every symbol the
drain (`3b7ed93`) and the Spec-triage commits re-set was re-located in the current
tree and compared against the committed anchor. The files edited this window
(`FieldBuilder.java` 7449 -> 7441, `SourceKey.java` 360 -> 338,
`GeneratorUtils.java` +28, `ConnectionPromoter.java` 650 -> 644,
`GraphitronSchemaBuilder.java` +63, `QueryConditionsGenerator.java` +33) were
checked symbol-by-symbol to see which just-set anchors moved.

**Anchors that held (re-verified exact, no drift this window):**

- **R10** `rebuildAssembledForConnections` (`ConnectionPromoter.java:239`),
  `synthesiseForField` (`:140`) exact; the file's only churn (R13 rework) landed
  before the drain re-anchored it. R10 also now carries `last-updated:` (added in
  the drain).
- **R236** `classifyInputFieldInternal` (`BuildContext.java:2437`), candidate-hint
  failure-aggregation (`:2535`) exact; `BuildContext.java` untouched this window
  (3099).
- **R234** `TypeBuilder.buildNonTableInputType` (`TypeBuilder.java:1688`) and the
  two `GraphitronType` arms (`:342` / `:357`) exact; both files untouched.
- **R92** `validatorPreStep` (`TypeFetcherGenerator.java:2201`, call `:2069`),
  **R103** `buildPerCellValueList` (`:2622`) / `buildPerCellValueListDeduped`
  (`:2795`), **R240** `buildQueryTableMethodFetcher` (`:1441`, call `:472`),
  **R242** `buildSingleRecordIdFromReturningFetcherValue`
  (`FetcherEmitter.java:631`, call `:373`), **R24** `STUBBED_VARIANTS` (`:302`)
  with the `CompositeColumnReferenceField` map entry (`:310`) all exact:
  `TypeFetcherGenerator.java` (+1, 6960 -> 6961) and `FetcherEmitter.java` (-2)
  barely moved and outside their regions.
- **R66**, **R7**, **R35** were re-spec'd to "re-derive from named symbols /
  re-measure at pickup," so their line/LOC drift is self-mitigated by
  construction; **R17** is a doc-relocation item with no code anchor.

**Result: 5 items flagged (all §C), 130 current.**

## A. Obsolete: should leave the active roadmap (0)

Empty. No shipment this window stranded a surviving construct (verified via the
`depends-on` sweep and a deleted-slug symbol check). R13 and R477 self-deleted on
Done; the three re-scopes (R273, R34, R298) were rewrites in place, not stranded
shells. Nothing surfaced as delivered-but-still-open.

## B. Outdated: needs re-spec (premise or targets materially changed) (0)

Empty for the second window running. R431 landing slice 1 does **not** invalidate
any item's premise: R222's decomposition plan and the ride-along items (R71,
R234, R314, R432, R471) are being *executed*, not contradicted. The one item
whose current-state facts moved (R222's `SourceKey` inventory) is a reference
update, not a premise change, and sits in §C below.

## C. Outdated: update references only (work valid, refs stale) (5)

Substance intact; only line numbers (and one component-list fact) drifted. Four
rows drifted *this window*, from code churn that landed after the drain re-set
them; the fifth (R222) drifted because R431 slice 1 executed part of its plan.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R202** honor-field-directive-in-error-type-source-accessors | Backlog | Body cites `FieldBuilder.java:3202` (call `:2977`, loop `:3219-3226`, `resolve` `:3221`); `checkErrorTypeSourceAccessors` is now defined at **`:3189`** (called `:2964`), `FieldBuilder.java` having shrunk 7449 -> 7441 under R458 slice-1 and R431 slice-1. -13 drift. Premise unchanged: the accessor still resolves off the raw SDL name with no `@field` read; the R461-Done tense is already correct. | **Re-anchor** to `:3189` / call `:2964` (loop `~:3206-3226`). Substance valid and unbuilt. |
| **R201** honor-field-directive-in-payload-construction-shape | Backlog | Body cites `FieldBuilder.java:521-577`, `tryMutableBean` `:586-630`, `SetterBinding` `:624`, call `:2998`; now `resolvePayloadConstructionShape` **`:508`**, `tryMutableBean` **`:573`**, `SetterBinding` **`:588`/`:611`**, call **`:2985`**. ~-13 drift. Premise unchanged: `SetterBinding` still built off the raw SDL field name, no `@field` read. | **Re-anchor** the ranges -13. Substance valid, output-side mirror of R200. |
| **R71** recordn-key-parity-lifter-and-non-jooq-record-parents | Backlog | Body cites the `SourceKey` compact-ctor pin at `model/SourceKey.java:124-128` and the `SourceRowsCall` record at `:288`; after R431 slice-1 (target deleted, 360 -> 338) the `SourceRowsCall requires Wrap.Row` throw is now **`:115-118`** and the `record SourceRowsCall(LifterRef)` is now **`:267`**. The `FieldBuilder.java:5630` `@sourceRow`-complementarity note is ~stable (now `~:5614-5635`). | **Re-anchor** the two `SourceKey.java` lines; the symbol-anchored core (`LifterRef`/`SourceRowsCall`/`Wrap`) is correct. Rides R431 (now In Progress). |
| **R180** record-parent-column-read-helper | Spec | Body cites `GeneratorUtils.java:290` (`recordColumnReadArgs`), `:274` (`buildFkRowKey`), `:238` (`buildRecordParentKeyExtraction`); after this window's +28 growth they are now **`:300`**, **`:284`**, **`:223`/`:247`**. +10 drift. The R461-Done tense is already correct (added in the drain); only the `GeneratorUtils` line anchors moved. | **Re-anchor** to `:300` / `:284` / `:247`. Substance (consume the R461 accessor surface for record-parent column reads) valid; sequenced after/with R431. |
| **R222** dimensional-model-pivot | Spec | The "researched 2026-06-16" inventory states `SourceKey` is `(target, columns, path, wrap, cardinality, reader)` and that "the four `SourceKey.target()` readers all sit on carriers that also expose `returnType.table()`" (`:241`, `:252-253`, `:315`). **R431 slice 1 deleted the `target` component** (`bab6f35`), so `SourceKey` is now `(columns, path, wrap, cardinality, reader)` and there are **zero** `target()` readers. R222's own plan called for exactly this ("`target`/`path` leave `SourceKey` by deletion"), so this is the plan executing, not a premise change. | **Update the inventory** to note the `target`-deletion half landed via R431 slice 1 (keep the dated research snapshot but add a "since executed" note). Substance and umbrella direction valid. |

**Re-confirmed Current (not flagged):** the five new items **R475** / **R476** /
**R478** / **R479** / **R480** (all filed 2026-07-14, fresh); the three in-place
re-scopes **R273** / **R34** / **R298** (rewritten 2026-07-14, current);
**R431** (`decompose-sourcekey`, **In Progress**, slice 1 shipped);
**R314** / **R333** / **R478** (**Ready**, R333 signed off this window);
**R347** / **R458** (**In Progress**); **R474** (**In Review**); the Spec-triage
set **R92** / **R109** / **R45** / **R212** / **R335** / **R381** (refreshed this
window); **R10** / **R236** / **R234** / **R240** / **R242** / **R103** / **R24** /
**R66** / **R7** / **R35** / **R17** (anchors held or self-mitigated, see Scope and
method); **R222** stays the umbrella. **R13** and **R477** reached **Done** and
self-deleted (verified absent).

## Cross-cutting observations

1. **Re-anchoring is perishable, and the drain proved it twice.** The drain
   (`3b7ed93`) re-set six anchors correctly against the tree it saw, and four of
   them (R202, R201, R71, R180) drifted again within the same ~23-hour window as
   later commits edited `FieldBuilder.java`, `GeneratorUtils.java`, and
   `SourceKey.java`. The items that did **not** re-drift are exactly the
   symbol/"re-measure" re-spec'd ones (R7, R35, R66) and those in files untouched
   this window (R10, R236, R234, R92, R103, R240, R242, R24). **Recommendation
   reinforced (now a workflow convention as of `6a7afbd`):** prefer
   symbol-anchored references over bare line numbers; treat a bare line number as
   stale the moment its file is edited.
2. **`SourceKey` decomposition converted from "pending flag" to "active drift
   source."** For three windows the standing note was "R431 is still Backlog;
   when it lands, R71/R234/R314/R432 need re-checking." It is now **In Progress**
   with slice 1 landed, and that single slice already re-drifted R71 and R222.
   Expect each further R431 slice to move the same cluster; the ride-along items
   should be re-anchored (or better, symbol-anchored) each slice, not at the end.
3. **Two terminal closures, both self-deleted, no tombstones.** R13
   (faceted-search, a 1 592-line spec) and R477 (a full same-window Backlog->Done
   bug fix) both reached Done and removed their files; the board is tombstone-free
   for the eleventh window running.
4. **The `classification-test-dsl-inventory` banner finally landed.** The
   "superseded, historical" banner the last four audits recommended was added
   this window; that carried observation is resolved.
5. **No closure this window subsumed a surviving flagged item** (verified at the
   symbol). R13's facet work grew `ConnectionPromoter` but did not touch R10's
   `rebuildAssembledForConnections` removal target; R431 slice 1 removed
   `SourceKey.target` but the R71/R180/R201/R202 gaps target different symbols;
   R477's node-id guard is a distinct code path from R479's cursor-decode gap.
6. **Minor prose cross-reference to a deleted file:** `relevance-ranked-search.md`
   (`:133`) still names "R13 (`@asFacet` / faceted-search)" as a live sibling;
   R13 is now Done and its file deleted. Not a `depends-on` edge (the tool would
   catch that), just prose; a picker would still find R13 in git history. Optional
   tidy on next edit; not flag-worthy.
7. **Cosmetic front-matter nits, none flag-worthy (carried / partly resolved).**
   **R97** (`consumer-derived-input-tables`) still lacks `created:` /
   `last-updated:`. The prior "R92 renders no `last-updated:`" nit is **resolved**
   (the triage stamped `last-updated: 2026-07-14`). Neither is a build or
   dependency risk.
8. **`inference-axis-coverage.adoc`** remains an intentional CI-regenerated
   placeholder, not a roadmap item (no `R<n>`), correctly excluded.

---

_Review date: 2026-07-15._
