# Roadmap staleness audit: 2026-07-14

A point-in-time review of every active roadmap item under [`roadmap/`](../)
against the **current** state of the codebase on `claude/graphitron-rewrite`
(HEAD `32169ba`, committed 2026-07-13 22:32, audited 2026-07-14). The goal is to
find items whose premise no longer holds: work already shipped, constructs
renamed or removed, dependencies that have since landed, or specs grown stale
enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-07-13` staleness audit, which has been deleted;
only the latest staleness audit is retained. Three siblings in this directory are
**not** staleness audits and are left in place:

- `2026-06-16-source-operation-target-reframe.md` is the `(source, operation,
  target)` reframe analysis, the permanent lineage document for **R316** (Done).
- `2026-06-30-release-planning.md` is the first-release scoping working document,
  meant to be edited in place as scope iterates. It reads further behind again:
  it still names none of the connection-lifecycle, DELETE write-target,
  list-payload-carrier, scalar-resolution, faceted-search, or accessor-unification
  work that closed in the last two windows, so its MUST/SHOULD tables lag further.
  Refreshing it is out of scope for this staleness pass.
- `2026-07-04-r222-r333-conformance-analysis.md` is the R222/R333 conformance
  analysis, a companion to the R314/R333 design session. It is a targeted
  implementation-vs-spec conformance record, not a point-in-time staleness
  review; left in place. (R333 reached **Ready** this window; the analysis was a
  sign-off input and stays as lineage.)

`classification-test-dsl-inventory.md` is R281's permanent corpus-retirement
inventory, doubly stale (against R299/R290 and the R316 corpus recut) and now
also behind R463's corpus recut. It still warrants a "superseded, historical"
banner; that banner has **not** been added (carried from prior audits, see
observation 6).

## Headline: the prior audit's findings were executed, then partly re-drifted

This is the first window in which a staleness audit's recommendations were
actioned end to end, and the flag list collapsed as a result. Three roadmap
maintenance commits landed the 2026-07-13 audit's §A/§B/§C recommendations
verbatim:

- **`da9707a`** discarded five items the audit (and prior triage) marked obsolete:
  **R263** (`decode-helper-typename-first-resolution`, the sole §A row), **R120**
  (`fkjoin-alias-dead-storage`, §B), plus three unflagged-but-delivered backlog
  items **R326** (`render-mermaid-diagrams-on-docs-site`), **R373**
  (`surefire-redirect-test-output`), **R394** (`roadmap-tool-tripwire-buildfailure`).
- **`90f7025`** discarded **R118** (`graphitron-mcp-server`, programme delivered,
  open question carried to new R470) and re-specced the five §B items that were
  still wanted in spirit: **R180**, **R71**, **R24**, **R66**, **R7**; it also
  updated **R170**'s "(R94-blocked)" framing to "(R98-blocked)".
- **`1b38cf1`** re-anchored the twelve §C "update references only" items against
  2026-07-13 line numbers.

Net: the 20 flags from 2026-07-13 (**1 §A / 7 §B / 12 §C**) reduce to
**0 §A / 0 §B / 6 §C** here. **§A and §B are empty.** The surviving §C flags are
**not** the same staleness the last audit found: they are *fresh* drift, because
this window's own code churn moved the very lines that were re-anchored a day
earlier. See §C and observation 2.

## Changes since the 2026-07-13 audit

**41 commits** landed between the prior snapshot (HEAD `ee7a54a`, 2026-07-12
20:43) and this one (HEAD `32169ba`, 2026-07-13 22:32), a dense ~26-hour window.
Beyond the three roadmap-maintenance commits above, four things drove it:

**1. Accessor-resolution unification and the arrival fold both closed.** **R461**
(`unify-sdl-field-accessor-resolution`), filed Backlog last window, drove
Spec -> Ready -> In Progress -> In Review -> **Done**: the four divergent
SDL-field-to-Java-accessor resolvers now sit behind one `ClassAccessorResolver.enumerate`
with a sealed `AccessorProbe (Grounded | NoMatch)` discovery probe (new files
`AccessorProbe.java`, plus `GraphitronType.java` +51). **R463**
(`ancestor-product-arrival-fold`), **Spec** last window, reached **Done**: a new
`Arrival` lattice + `ArrivalIndex` computes the true ancestor-product fold and
populates `Source.OnlyChild`; model+tests only, generated output byte-unchanged.
Both self-deleted their files.

**2. Faceted search (R13) is the big active build.** **R13** (`faceted-search`)
ran In Progress -> In Review -> **Ready** (reworked): a `@asFacet` directive, facet
synthesis on `ConnectionResult`, and a `ConnectionHelper.facets` UNION ALL
resolver. This is what grew `ConnectionPromoter.java` (498 -> **650**),
`ConnectionResultClassGenerator`, and `ConnectionHelperClassGenerator`, and it
re-drifted §C's R10 anchor (below).

**3. Per-participant polymorphic child joins began.** **R458**
(`per-participant-multitable-child-join-paths`) went Ready -> **In Progress** with
slice 1 (`@referenceFor` per-participant join paths on multi-table polymorphic
child fields); new `ParticipantCorrelation.java`, retired `ParticipantFkPath.java`.
Contributed to `FieldBuilder.java` growth (7276 -> **7449**).

**4. Spec-forward sign-offs on the dimensional chain.** **R314**
(`dissolve-reentry-leaves-dimensional-emit`) and **R333**
(`coordinate-lowers-to-datafetcher-queryparts`) both reached **Ready** after
independent review; **R462** (`nested-fetcher-outgoing-field-edges`) went
Backlog -> **Spec**.

**Terminal closures this window (Done, both self-deleted):** **R461**
(`unify-sdl-field-accessor-resolution`), **R463** (`ancestor-product-arrival-fold`).

**New items on the board (all filed 2026-07-13, all fresh, none stale):**

- **R470** (`multilingual-catalog-search-embedding`, **Backlog**): the English-only
  embedding question carried out of the R118 discard.
- **R471** (`direct-sql-onlychild-reentry-emit`, **Backlog**): the direct-SQL
  `OnlyChild` emit deferred from R463, rides the R431 -> R432 -> R314 chain.
- **R472** (`nested-generated-condition-filters-never-emitted`, **Backlog**): filed
  alongside R462's Spec.
- **R473** (`explicit-nodeid-grammar`, **Backlog**).
- **R474** (`mvnd-web-environment`, **Backlog**): adopt mvnd in the web dev
  environment.

**Board accounting.** **131 item files** today, down from the prior audit's 134.
The delta reconciles exactly as `134 + 5 - 8`: five new files (R470, R471, R472,
R473, R474) minus eight deletions, the eight being two Done self-deletions (R461,
R463) and six discards (R263, R120, R118, R326, R373, R394). Status distribution:
**113 Backlog, 13 Spec, 3 Ready (R13, R314, R333), 2 In Progress (R347, R458);
zero In Review, zero Done**. A non-recursive `^status: Done` grep over
`roadmap/*.md` returns nothing (tombstone-free for the tenth window running). No
duplicate `id:`; `next-id: R475` with max allocated id **R474**. A `depends-on`
sweep over all 131 files found **no dangling slugs**; none points at a slug
deleted this window (`decode-helper-typename-first-resolution`,
`fkjoin-alias-dead-storage`, `graphitron-mcp-server`,
`render-mermaid-diagrams-on-docs-site`, `surefire-redirect-test-output`,
`roadmap-tool-tripwire-buildfailure`, `ancestor-product-arrival-fold`,
`unify-sdl-field-accessor-resolution`). The board is structurally clean.

**Net effect on flag counts: 6 flagged, 125 current.** Down from 20 flagged. §A
and §B are now empty; all six surviving flags are §C "update references only,"
and all six are drift *introduced this window* rather than carried over. No item's
substance changed; only line numbers (and one dependency tense) moved.

## Scope and method

All **131** `R<n>` item files were reviewed (plus the non-item
`inference-axis-coverage.adoc` placeholder, correctly excluded). Every symbol the
2026-07-13 re-anchor/re-spec commits set was re-located in the current tree and
compared against the freshly-committed anchor; the five heavily-edited files this
window (`FieldBuilder.java` 7276 -> 7449, `ConnectionPromoter.java` 498 -> 650,
`TypeFetcherGenerator.java` 6900 -> 6960, `GraphitronType.java` 553 -> 604,
`BuildContext.java` 3061 -> 3099) were checked symbol-by-symbol to see which
just-set anchors moved.

**The `SourceKey` decomposition still has not landed.** It remains a filed,
sequenced item (**R431**, Backlog), with R432 depending on it and R314 (now
**Ready**) depending on both. `model/SourceKey.java` is **unchanged at 360** and
still pins `SourceRowsCall -> Wrap.Row` (`SourceKey.java:124-126`, throws
otherwise). So the Row-only asymmetry R71 wants to remove is intact, and
R71 / R234 / R314 / R432 all still need re-checking when R431 lands.

**Anchors that held (re-verified exact, no drift this window):**

- **R92** `validatorPreStep` (`TypeFetcherGenerator.java:2201`, call `:2069`) and
  **R103** `buildPerCellValueList` (`:2622`) / `buildPerCellValueListDeduped`
  (`:2795`) and **R240** `buildQueryTableMethodFetcher` (`:1441`, call `:472`) all
  carry over **verbatim**: the +60 growth in `TypeFetcherGenerator.java` fell
  outside their regions.
- **R242** `FetcherEmitter.buildSingleRecordIdFromReturningFetcherValue`
  (`FetcherEmitter.java:631`, call `:373`) exact; that file unchanged (763).
- **R245** anchors exact; `MutationInputResolver.java` unchanged (695).
- **R24** `STUBBED_VARIANTS` (`TypeFetcherGenerator.java:302`) and the
  `CompositeColumnReferenceField` entry (`:310`) exact.
- **R234** is symbol-anchored (`JooqRecordInputType` `GraphitronType.java:343`,
  `JooqTableRecordInputType` `:358`), so the +51 growth left it correct.
- **R66**, **R7**, **R35** re-spec'd 2026-07-13 to say "re-derive from the named
  symbols / re-measure at pickup," so their line/LOC drift is self-mitigated by
  construction; **R17** is a doc-relocation item with no code anchor.

**Result: 6 items flagged (all §C), 125 current.**

## A. Obsolete: should leave the active roadmap (0)

Empty. The sole prior §A row (**R263**) was discarded this window (`da9707a`),
along with five other delivered/obsolete items. No new obsolete item surfaced:
the discard triage cleared the known ones, and no closure this window stranded a
surviving construct (verified via the `depends-on` sweep and the deleted-slug
symbol check above).

## B. Outdated: needs re-spec (premise or targets materially changed) (0)

Empty, and this is the window's headline. The entire prior §B (**R180**, **R71**,
**R24**, **R66**, **R7** re-spec'd in `90f7025`; **R120** discarded; **R170**
re-framed) was drained. Each re-spec was re-read against current HEAD and no
surviving item's premise is materially misstated:

- **R180** was rebuilt against the collapsed 4-arm `ResultType` seal and the single
  surviving `GeneratorUtils.recordColumnReadArgs` callsite; its `GeneratorUtils`
  anchors are unchanged (that file is untouched, 578). One residual: it names
  **R461** as "In Review as of this re-spec," but R461 is now **Done** (a
  read-only tense update, listed in §C).
- **R66** struck the R58 reversion hedge and re-anchored on named symbols;
  substance holds.
- **R7** refreshed the LOC figure to "6 900 as of 2026-07-13" and told the picker
  to re-measure; `TypeFetcherGenerator.java` is 6960 today, so even the stated
  figure is within ~1%.
- **R24**'s title-symbol and stub anchors (`CompositeColumnReferenceField` in
  `STUBBED_VARIANTS`) are exact.
- **R71**'s live surface (`LifterRef`, `SourceRowsCall`, `Wrap`) is correctly
  anchored; only one bare line reference drifted (§C).

## C. Outdated: update references only (work valid, refs stale) (6)

Substance intact; only line numbers (and one dependency tense) drifted. Unlike
prior windows, **every row here drifted *this* window**: the 2026-07-13 re-anchor
set these correctly, and R13/R458/R461/R463's code churn moved them within a day.
Three are materially stale (a picker following the cited line lands in the wrong
method); three are minor.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R202** honor-field-directive-in-error-type-source-accessors | Backlog | Body cites `FieldBuilder.java:3174-3209`; `checkErrorTypeSourceAccessors` is now defined at **`:3202`** (called `:2977`), the file having grown 7276 -> 7449 under R13/R458. The cited range now lands ~28 lines above the method. Premise unchanged: the accessor still resolves off the raw SDL name with no `@field` read. | **Re-anchor** to `:3202` / call `:2977`. Substance valid and unbuilt. |
| **R236** validator-reference-candidate-hint-terminal-table | Backlog | Body cites `BuildContext.java:2399` / `:2497` / `:2489`; `classifyInputFieldInternal` is now **`:2437`** and the `candidateHint(c, ...)` failure-aggregation **`:2535`** (file 3061 -> 3099). +38 drift. The hint still draws from the path-origin `resolvedTable.tableName()`, not the `@reference` terminal table. | **Re-anchor** to `:2437` / `:2535`. Substance valid; R380's Done note still lists R236 as an open sibling. |
| **R10** drop-assembled-schema-rebuild | Backlog | Body cites `ConnectionPromoter.java:196` for `rebuildAssembledForConnections`; it is now **`:239`** (`synthesiseForField` now `:140`), the file having grown 498 -> 650 under R13's facet UNION ALL resolver. +43 drift. Also missing a `last-updated:` header field. | **Re-anchor** to `:239` / `:140` and stamp `last-updated:`. Substance (drop the assembled-schema rebuild seam) valid and unbuilt. |
| **R201** honor-field-directive-in-payload-construction-shape | Backlog | Body cites `FieldBuilder.java:518-574`; `resolvePayloadConstructionShape` is now **`:521`** (`tryMutableBean` `:586`, `SetterBinding` `:624`). +3 drift, minor. Premise unchanged: `SetterBinding` built off the raw SDL field name, no `@field` read. | **Light re-anchor** (shift the range +3); optional given the small delta. Substance valid, output-side mirror of R200. |
| **R71** recordn-key-parity-lifter-and-non-jooq-record-parents | Backlog | The `SourceKey` pins hold exact (`:124-126`, `SourceRowsCall` record `:288`); only the `FieldBuilder.java:5602` `@sourceRow`-branch reference drifted (the branch is now ~`:5629`, file +173). | **Light re-anchor** of the single `FieldBuilder` line; the symbol-anchored core (`LifterRef`/`SourceRowsCall`/`Wrap`) is correct. Rides R431. |
| **R180** record-parent-column-read-helper | Spec | Names **R461** as "In Review as of this re-spec"; R461 is now **Done** (`ClassAccessorResolver`/`AccessorProbe` shipped, its file self-deleted). `GraphitronType.java` `ResultType` seal moved `:93` -> `:94` (+1). | **Update tense**: R461 is Done, so the "consume the R461 surface" direction is now unblocked, not pending. No substantive change; still sequenced after/with R431. |

**Re-confirmed Current (not flagged):** the five new items **R470** / **R471** /
**R472** / **R473** / **R474** (**Backlog**, all filed 2026-07-13, fresh);
**R462** (`nested-fetcher-outgoing-field-edges`, Backlog -> **Spec** this window);
**R13** (`faceted-search`, **Ready**, reworked v1) on the live `ConnectionPromoter`
seam; **R314** / **R333** (**Ready**, both signed off this window); **R458**
(`per-participant-multitable-child-join-paths`, **In Progress**, slice 1 shipped);
**R347** (`lsp-structural-consolidation`, **In Progress**); **R92** / **R103** /
**R240** / **R242** / **R245** / **R234** / **R24** / **R66** / **R7** / **R35** /
**R17** (anchors held or self-mitigated, see Scope and method); **R222**
(`dimensional-model-pivot`, Spec) stays the umbrella; **R431** / **R432** re-verified
spec-forward. **R461** and **R463** reached **Done** and self-deleted (verified
absent).

## Cross-cutting observations

1. **The audit loop closed for the first time.** Every §A/§B/§C recommendation
   from 2026-07-13 was actioned this window (six discards, five re-specs, twelve
   re-anchors) in `da9707a` / `90f7025` / `1b38cf1`. The flag count fell
   **20 -> 6**; §A and §B are empty.
2. **Re-anchoring is perishable under active development.** Every surviving flag is
   drift *introduced this window*: anchors correctly set on 2026-07-13 were moved
   within ~26 hours by R13 (`FieldBuilder` +173, `ConnectionPromoter` +152), R458,
   R461, and R463 (`GraphitronType` +51). The items that did **not** re-drift are
   exactly the ones re-spec'd to symbol/"re-measure" references (R7, R35, R66) or
   sitting in unchanged files (R92, R103, R240, R242, R245, R24). **Recommendation
   reinforced:** prefer symbol-anchored references over bare line numbers; treat a
   bare line number as stale the moment its file is edited.
3. **Two large model closures, generated output unchanged.** R461 (accessor
   unification) and R463 (arrival fold) both reached Done as model+test-only
   changes; R461's LSP `CatalogBuilder.beanAccessorSlot` routing (finding M19) and
   R463's direct-SQL `OnlyChild` emit (new **R471**, riding R431 -> R432 -> R314)
   were split out rather than folded in.
4. **`SourceKey` is still the pending source-side pivot.** Unchanged at 360; R431
   (`decompose-sourcekey`) still Backlog. When it lands, R71 / R234 / R314 / R432
   all need re-checking. Same standing item as the prior two windows.
5. **No closure this window subsumed a surviving flagged item** (verified at the
   symbol). R461's accessor work touched `ClassAccessorResolver`, not the raw-SDL
   `@field`-read gaps R201/R202 target; R463's arrival fold touched
   `Source`/`Arrival`, not R236's candidate-hint terminal-table gap; R13's facet
   work grew `ConnectionPromoter` but did not touch R10's `rebuildAssembledForConnections`
   removal target.
6. **`classification-test-dsl-inventory.md`** is now triply stale (R299/R290, the
   R316 recut, and R463's corpus recut) and still warrants the "superseded,
   historical" banner; it has **not** been added (carried, left unedited per scope).
7. **Cosmetic front-matter nits, none flag-worthy (carried over).** **R97** lacks
   `created:` / `last-updated:`; **R92** renders no `last-updated:` in the README
   rollup; **R10** lacks `last-updated:` (noted in §C). Neither is a build or
   dependency risk.
8. **`inference-axis-coverage.adoc`** remains an intentional CI-regenerated
   placeholder, not a roadmap item (no `R<n>`), correctly excluded.

---

_Review date: 2026-07-14._
