# Roadmap staleness audit: 2026-07-23

A point-in-time review of every active roadmap item under [`roadmap/`](../)
against the **current** state of the codebase on `claude/graphitron-rewrite`
(HEAD `93e2654`, committed 2026-07-22 21:46, audited 2026-07-23). The goal is to
find items whose premise no longer holds: work already shipped, constructs
renamed or removed, dependencies that have since landed, or specs grown stale
enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-07-22` staleness audit, which has been deleted;
only the latest staleness audit is retained. Three siblings in this directory are
**not** staleness audits and are left in place:

- `2026-06-16-source-operation-target-reframe.md` is the `(source, operation,
  target)` reframe analysis, the permanent lineage document for **R316** (Done).
- `2026-06-30-release-planning.md` is the first-release scoping working document,
  meant to be edited in place as scope iterates. Its MUST/SHOULD tables continue
  to lag the post-decomposition model and predate the R45 multi-tenant landing
  and the R507 documentation-staleness landing (both Done); refreshing it stays
  out of scope for this staleness pass.
- `2026-07-04-r222-r333-conformance-analysis.md` is the R222/R333 conformance
  analysis, a companion to the R314/R333 design session. It is a targeted
  implementation-vs-spec conformance record, not a point-in-time staleness
  review; left in place.

`classification-test-dsl-inventory.md` is R281's permanent corpus-retirement
inventory; its "superseded, historical" banner is intact. No action; it stays as
lineage.

## Headline: a quiet-for-staleness execution window: three code items ran to Done drift-free, R507 confirmed orthogonal to §C, and the flag set is unchanged at 14

A dense but **staleness-quiet** window: 29 commits, four terminal closures (R507,
R511, R512, R513 all Done and self-deleted), one new item filed and spec'd
(R510), and one existing item advanced to Spec (R282). Four things matter for
staleness, and the net result is **zero new drift**:

- **The three code-touching closures were additive or internal-refactor and
  introduced no roadmap drift.** **R511** (`runtime-adaptive TableRecord key
  extraction`) added a runtime-adaptive arm to `GeneratorUtils.buildKeyExtraction`
  and left the producer side byte-identical: no leaf added or removed, no rename,
  no line-anchor shift in the tracked model files. **R512** (`schema-qualified
  @reference(key:)`) is purely additive grammar: a new `QualifiedForeignKeyName`
  carrier, a `parseQualifiedForeignKeyName`, and a schema-scoped
  `findForeignKey(name, source, schema)` overload, with unqualified behaviour
  byte-for-byte unchanged and no symbol retired. **R513** (`cross-schema Fetchers
  helper-name disambiguation`) introduced `FetchersHelperNames` and **deleted the
  three uncoordinated static `create`/`decode` + `simpleName()` derivations inside
  `ServiceMethodCallEmitter` and `InputBeanInstantiationEmitter`**, but the emitter
  *classes* persist and no roadmap item cites those internal derivations as such.
  A repo-wide search confirms no item's cited symbol was removed or renamed by any
  of the three. The `ChildField` leaf anchors R508 last set are unmoved
  (`ColumnBackedField` `:289`, `BatchedTableField` `:521`, `ServiceTableField`
  `:1029`).

- **R507 reached Done, confirming the prior audit's correction: it does not, and
  by design never will, retire the §C cluster.** **R507**
  (`documentation-staleness-prevention`, In Review -> Done, changelog entry now
  present) shipped its `RetiredVocabularyGuardTest` with an empty allowlist and
  **excludes `roadmap/` from its scan by design** (item bodies are transient; the
  changelog and these audits must be free to name retired terms). Its spec names
  "roadmap item body currency" as out of scope, owned by these audits. The §C
  re-anchor work therefore remains fully an audit responsibility; R507's landing is
  a genuine drift-prevention win for *code and doc* prose, orthogonal to §C. The
  standing expectation set by the prior audit holds: **the §C cluster will not
  self-retire.**

- **Both items that entered Spec this window are current, not born-stale.**
  **R510** (`tenant-fanout-parallel-execution`, new file, Backlog -> Spec) is the
  bounded-parallel-execution substrate extracted from R46; it describes the shipped
  serial runtime accurately (`TenantConnections`, `CompletableFuture.completedFuture`,
  the same-thread `abortExecutor`), all live symbols. **R282**
  (`fk-key-hint-sibling-scope`, Backlog -> Spec) names only live symbols
  (`FkJoinResolution.UnknownForeignKey`, `synthesizeFkJoin`, `findForeignKeyRef`,
  `unknownForeignKeyRejection`, `NodeIdLeafResolver.resolveFkJoinPath`) and is
  untouched by R512's additive FK-grammar work (a different surface). The born-stale
  vetting the prior audit called for was applied to both; neither is flagged.

- **The one edited flagged item had only its non-flagged part fixed.** **R505**
  (`tenant-index-parent-row-routing`) was edited this window to retense its R45
  references to Done, but its born-stale `SourceKey.Reader` carrier phrase (body
  `:21`) was **not** touched. Its §C flag carries unchanged.

Net: **0 §A / 1 §B / 13 §C**, §D empty. **Identical to the prior window's flag
set:** R71 carries in §B; the thirteen §C items all carry (R505's edit did not
touch its flagged phrase). No new flag surfaced and none retired, because the
window's code landings were drift-free and its two new Spec items are current.

## Changes since the 2026-07-22 audit

**29 commits** landed between the prior audit's publish commit (`e52fa28`,
2026-07-22 03:17, "refresh staleness audit to 2026-07-22") and this HEAD
(`93e2654`, 2026-07-22 21:46). Four things drove the window:

**1. Four items ran to Done (all self-deleted).** **R507**
(`documentation-staleness-prevention`) closed In Review -> Done (`36ba087`), spec
retired to `changelog.md`. **R511** (`runtime-adaptive TableRecord key extraction`)
ran the full cycle Backlog -> Spec -> Ready -> In Progress -> In Review -> Done
(`a238d57` .. `4d03583`), making `GeneratorUtils.buildKeyExtraction`'s `TableRecord`
arm runtime-adaptive so a `@service`-returned `@table` parent no longer throws on
missing reserved aliases. **R512** (`schema-qualified @reference(key:)`) ran the
full cycle to Done (`6cf09b9` .. `bc307b7`), adding a `schema.constraint` qualifier
to the `key:` grammar for cross-schema FK-name collisions. **R513** (`cross-schema
Fetchers helper-name disambiguation`) ran the full cycle to Done (`3d8dc46` ..
`14035ac`), introducing `FetchersHelperNames` so a `*Fetchers` class binding two
same-named records across schemas no longer emits colliding `create*` helpers. Each
has a `changelog.md` entry.

**2. One new item filed and spec'd.** **R510**
(`tenant-fanout-parallel-execution`, `e974f7a` Backlog, `77f69f3` Spec, `93e2654`
spec revision) files and specs the bounded parallel execution substrate for tenant
fan-out: a scatter/join helper confining parallelism to one generated runtime
helper while fetchers stay synchronous, thread-safe `TenantConnections` pinning, a
concurrency cap, and a timeout story. Extracted from R46, which now depends on it.
Well-formed front-matter.

**3. One item advanced to Spec.** **R282** (`fk-key-hint-sibling-scope`, Backlog ->
Spec, `2a3229b`) reframed to the record-FK typo surface as the spine with sibling
scoping as symmetry. Fresh Spec, current references.

**4. Two non-flagged items annotated.** **R46**
(`service-multi-tenant-fanout`) gained `depends-on: [tenant-fanout-parallel-execution]`
and resolved its open "parallelism bounds" question to R510 (was "likely R429
config"). **R505** retensed its R45 dependency references to Done (its flagged
`SourceKey.Reader` phrase untouched, see §C.1).

**Terminal closures this window (Done, all self-deleted):** R507, R511, R512, R513.
R333/R427 (Ready), R347 (In Progress) carry work but are not Done; no item is In
Review this window.

**Board accounting.** **131 item files** today (133 `roadmap/*.md` entries minus
`README.md` and `changelog.md`), unchanged from the prior audit's 131: four
closures self-deleted (R507, R511, R512, R513) against one new file (R510); R511/
R512/R513 were filed and closed within the same window, so they net to zero on the
count. Status distribution: **115 Backlog, 13 Spec, 2 Ready (R333, R427), 1 In
Progress (R347), 0 In Review, 0 Done**. A non-recursive `^status: Done` grep over
`roadmap/*.md` returns nothing (tombstone-free for the seventeenth window running).
No duplicate `id:`; max allocated id **R510**, and `next-id: R514` clears it (R511/
R512/R513 files deleted at Done). A `depends-on:` sweep over all 131 item files
resolves every edge, R-number and slug alike, to a present file, including R46's new
slug edge to `tenant-fanout-parallel-execution`. The board is structurally clean.

**Net effect on flag counts: 14 flagged, 117 current.** 0 §A, 1 §B, 13 §C, 0 §D.
Unchanged from the prior window.

## Scope and method

All **131** `R<n>` item files were reviewed (plus the non-item
`inference-axis-coverage.adoc` placeholder, correctly excluded: no `R<n>`). The
claimed model constants were re-verified at the symbol. `ChildField.java` declares
its leaves at the anchors R508 last set, **unmoved this window** (R511/R512/R513 do
not touch `ChildField`): `ColumnBackedField` (`:289`), `BatchedTableField`
(`:521`), `BatchedLookupTableField` (`:655`), `TableMethodField` (`:724`),
`NestingField` (`:875`), `PivotField` (`:911`), `ServiceTableField` (`:1029`), with
**no `CompositeColumnField`, `ColumnField`, `RecordTableField`,
`RecordTableMethodField`, `SplitTableField`, `SplitLookupTableField`, or
`RecordLookupTableField`** as live leaves (a source grep finds the old names only in
`former`/`guarded`/`dissolved` lineage prose; R507's now-Done guard confirms this by
passing with an empty allowlist). `SourceKey.java` is the plain `public record
SourceKey(...)` residue (`:40`) with **no `Reader` interface and no
`SourceRowsCall`**. `KeyLift.java` and `LifterRef.java` are both live (the §B/§C
re-anchor targets exist). The two new symbols this window are present:
`FetchersHelperNames.java` (R513) and `QualifiedForeignKeyName` (R512, in
`JooqCatalog`/`BuildContext`/LSP `Diagnostics`). The three emitter classes R333's
delegation map names are all live (`ServiceMethodCallEmitter`,
`InputBeanInstantiationEmitter`, `JooqRecordInstantiationEmitter`); R513 gutted only
their internal name-derivation, not the classes.

**Anchors that held (re-verified):** the fourteen carried flags were re-checked
against their (unedited, save R505's non-flagged retense) roadmap files; every cited
stale phrase is still literally present, so every flag remains accurate. The
re-anchor *targets* they should point at are all live (`BatchedTableField` `:521`,
`BatchedLookupTableField` `:655`, `TableMethodField` `:724`, `ColumnBackedField`
`:289`, the decomposed `KeyLift`/`LifterRef`/`Wrap` model). R66 / R7 / R35 remain
re-derive-at-pickup by construction.

## A. Obsolete: should leave the active roadmap (0)

Empty. No item's entire premise was invalidated this window. R507, R511, R512, and
R513 all closed cleanly to Done as designed; no item they touched was left orphaned,
and no Backlog item requesting cross-schema FK-name or helper-name handling was
subsumed (the only other cross-schema mention, R282, is a distinct typo-surface
concern). No new duplicate or fully-superseded item surfaced.

## B. Outdated: needs re-spec (premise or targets materially changed) (1)

Unchanged from last window: one item, carried untouched.

| Item | Status | What changed | Recommended action |
|---|---|---|---|
| **R71** recordn-key-parity-lifter-and-non-jooq-record-parents | Backlog | R431 (Done) **deleted the target surface**, not just its line numbers. The body cites `model/SourceKey.java:124-128` (a `SourceRowsCall -> Wrap.Row` compact-ctor pin) and `:288` (`SourceKey.Reader.SourceRowsCall(LifterRef)`); `SourceKey` today is a plain record (`:40`) with **no `Reader` interface and no `SourceRowsCall`**. The body's own 2026-07-13 re-anchor note (`:14`) still names `SourceKey.Reader.SourceRowsCall` / `Wrap` as "the live surface", so that note is itself stale. The sequencing line (`:33`) still reads "R431 ... plans to decompose"; R431 is Done. The mechanism (the lifter contract pin) was relocated by R431 into `KeyLift`/`LifterRef`/`Wrap`, so this is a design-level re-derivation of *where* the work attaches, not a line bump. Not touched this window. | **Re-spec the current-state / approach section** against the decomposed `KeyLift`/`LifterRef`/`Wrap` model. The goal (recordN key parity, non-jOOQ record parents) is intact, so this is a targeted re-derivation of the attachment surface. Drop the "R431 plans to decompose" tense (`:33`); R431 is Done. This is the same shape of fix R222 received; R71 remains the last item carrying the pre-R431 `SourceKey.Reader` shape as a *detailed live approach* rather than a passing mention. |

## C. Outdated: update references only (work valid, refs stale) (13)

Substance intact; names and line numbers drifted. All thirteen carry from the prior
window; none was newly edited into currency (R505's this-window edit fixed only its
R45 tense, not its flagged `SourceKey.Reader` phrase), and no code landing this
window added or cleared a §C entry.

### C.1 `planSlug` / `SourceKey.Reader` removal drift (carried)

R484 (Done) removed `Rejection.Deferred.planSlug`; R431 (Done) removed the
`SourceKey.Reader` interface. Deferrals now anchor by `StubKey.VariantClass` (a
live class) and carry no roadmap-item pointer; column reads off a parent row lift
via `KeyLift.FkColumns`.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R454** routine-write-result-shapes | Backlog | Line 18 describes the deferred shapes as "typed `Deferred`s pointing at this item's planSlug". The `planSlug` field no longer exists, and deferrals no longer carry a roadmap-item pointer. | **Re-anchor** the mechanism sentence: the deferred shapes surface at build time via `StubKey.VariantClass` naming the variant, with no roadmap pointer. Substance (which write result shapes stay deferred) is valid. |
| **R447** routine-chain-fetch-form-breadth | Backlog | Two causes. (a) Lines 18 and 33 name "typed `Deferred` landings whose `planSlug` points here" / "empty planSlug in R435"; `planSlug` is gone. (b) Line 24 (`SplitLookupTableField` composition) and line 26 (`RecordTableField` seam) name merged leaves as live. | **Re-anchor** both: drop the `planSlug` pointer phrasing (deferrals anchor by `StubKey.VariantClass` now), and repoint the two variant names to the `Batched*` leaves. |
| **R180** record-parent-column-read-helper | Spec | Prose (`:35`) says "R431 ... now In Progress" -> R431 is **Done**, and names `SourceKey.Reader.AccessorCall` as a live carrier -> `SourceKey` has no `Reader` interface today. The substance (consume the R461 `ClassAccessorResolver.enumerate` / `AccessorProbe` surface for record-parent column reads) is valid and its machinery is live (`:30`). | **Re-anchor** the `SourceKey.Reader.AccessorCall` carrier onto the decomposed model, and fix the R431 tense to Done. Borderline with §B (the carrier is fully gone, not renamed), but the core machinery it consumes (R461) is live, so a reference repoint suffices. |
| **R505** tenant-index-parent-row-routing | Backlog | Line 21 names "a column read off the parent row (the `SourceKey.Reader` family)" as the carrier for the proposed `ParentRowBound` `TenantBinding` arm. `SourceKey.Reader` was removed by R431, *before* R505 was filed (2026-07-20), so it was born stale. This window's edit retensed R505's R45 references to Done but **left this phrase untouched**. The mechanism (per-row tenant read off a tenant-index parent) is live via `KeyLift.FkColumns`; only the one-phrase carrier name is wrong. | **Re-anchor** the parenthetical: a per-row column read lifts via `KeyLift.FkColumns` (there is no `SourceKey.Reader` family). One-phrase fix; the item's design intent is intact. |

### C.2 R432 leaf-merge / R314 dissolution / R431 anchor drift (carried, unchanged)

`SplitTableField`/`RecordTableField` -> `BatchedTableField`;
`SplitLookupTableField`/`RecordLookupTableField` -> `BatchedLookupTableField`;
`RecordTableMethodField` dissolved onto the record-sourced `BatchedTableField`
carrying `TableExpr.MethodCall`; the surviving `@tableMethod` leaf is
`TableMethodField`. `SplitRowsMethodEmitter` is **not** renamed and is correct
wherever it appears. R504 (Done) and R507 (Done, this window) scrubbed and now guard
these dead names in code, javadoc, and `.adoc`, **but both explicitly leave
`roadmap/` prose untouched** (R507 excludes `roadmap/` from its guard scan by
design), so this cluster stays an audit responsibility.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R403** reintroduce-tablemethod-docs | Backlog | Line 44 names `ChildField.RecordTableMethodField` as the live construct the reintroduced docs would describe. That leaf no longer exists; the DTO-parent `@tableMethod` shape now classifies to a **record-sourced `BatchedTableField`** carrying `TableExpr.MethodCall`. | **Re-anchor** the construct description to the post-R314 shape. |
| **R109** list-valued-external-field-multiset | Spec | Line 51's planned enum arm asserts "`RecordTableField` with `BatchKey.AccessorKeyedMany`". | **Re-anchor** the planned test assertion to `BatchedTableField`. |
| **R462** nested-fetcher-outgoing-field-edges | Spec | Lines 37, 45, 128 name `SplitTableField` / `SplitLookupTableField` as live. `SplitRowsMethodEmitter` in the same passages is correct. R508 re-anchored this item's *composite*-name mentions (`ColumnField`/`CompositeColumnField` -> `ColumnBackedField`) previously, but the `Split*` names it did not own remain. | **Re-anchor** the surviving `Split*` variant names; leave `SplitRowsMethodEmitter` and the already-fixed `ColumnBackedField` mentions untouched. |
| **R242** dml-payload-positional-alignment | Spec | Lines 37-38's R305 lineage note "collapsed it into `RecordTableField`, `ChildField.java:912`" is doubly stale: the name is now `BatchedTableField` (`:521` after R508's net leaf removal), and the cite `:912` today lands inside `PivotField`/`NestingField` territory (`:911`/`:875`), not on any merged leaf. | **Re-anchor** that one name + cite (`BatchedTableField`, `:521`); leave the surrounding R305/R287 history. |
| **R288** inline-interface-and-tablemethod-children | Backlog | Line 35: "a keyed batch (DataLoader, as `SplitTableField` / `RecordTableField` do via `SplitRowsMethodEmitter`)". Variant names stale; emitter fine. | **Re-anchor** the two variant names. |
| **R472** nested-generated-condition-filters-never-emitted | Backlog | Lines 20-21: classifier attaches `GeneratedConditionFilter` to a nested `SplitTableField` / `SplitLookupTableField` / inline `TableField` / `LookupTableField`. (`TableField` / `LookupTableField` are still live.) | **Re-anchor** the two `Split*` names. |
| **R116** composite-key-row2-source-row-coverage | Backlog | Line 15: the planned `COMPOSITE_KEY_ROW2_PATH_KEYED` case "classifies as `RecordTableField` with a `LifterPathKeyed`". | **Re-anchor** to `BatchedTableField`. |
| **R7** decompose-typefetchergenerator | Backlog | Line 30 proposes a hypothetical decomposed emitter `SplitTableFieldEmitter`; a decomposition done today would name it `BatchedTableFieldEmitter`. Illustrative "etc." naming; the list of *existing* emitters (incl. `SplitRowsMethodEmitter` `:32`) is correct. | **Low priority:** refresh the illustrative name at pickup. Not blocking. |

### C.3 R508 composite-column dissolution drift (carried)

R508 (Done) merged `ColumnField`/`CompositeColumnField` -> `ColumnBackedField` and
`ColumnReferenceField`/`CompositeColumnReferenceField` -> `ColumnBackedReferenceField`.
Its re-anchor pass caught five items but missed one.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R51** propertyfield-recordfield-nullable-column | Backlog | Line 13's R50 lineage clause names `ChildField.ColumnField` / `ColumnReferenceField` as the carriers R50 retired `columnName` on; both were merged into `ColumnBackedField` / `ColumnBackedReferenceField` by R508. Namespaced, live-looking cites (not a bare prose mention). The item's own subject (`PropertyField`/`RecordField`, both live) is unaffected. | **Re-anchor** the two lineage names to `ColumnBackedField` / `ColumnBackedReferenceField`; leave the surrounding R50 history. Low priority (lineage-precedent clause, no line cite). |

**No flag (verified live / current / re-baselined earlier):** **R222** (Spec) was
re-baselined by R508 (`InputField` family names with an in-line "dissolved ... in
R508" note); its supersession banner is intact. **R333** (Ready) carries its
`ColumnField`/`SplitTableField`/`:275` mentions as Discovery and "what dissolves"
design substrate, not live claims (R508 re-anchored the live-claim spots); its
delegation-map "dedup-by-class" note (`:1452`) is now mechanically imprecise after
R513 centralised helper naming into cross-class `FetchersHelperNames`, but the
emitter classes it names all still exist and the note is design substrate, so it
stays unflagged (refresh at pickup if the map is reworked). **R24 / R27 / R419**
(all Backlog) were re-anchored to `ColumnBacked*` by R508. **R323**
(`nestingfield-multiparent-batchkey-leaves`, Backlog) self-flagged with a "Re-anchor
at pickup (added 2026-07-15)" note naming the old `Split*`/`Record*` names;
pre-declared, `depends-on` cleared. **R302** and `rename-childfield-to-sourcefield`
mention `SingleRecordTableField` only in R305 lineage clauses. **R510** (Spec) and
**R282** (Spec), both new to Spec this window, were vetted against the current model
and name only live symbols. **R46** (Backlog) was annotated with its R510 dependency
and carries no stale reference.

## D. Structural: (0)

Empty. `changelog.md` carries `next-id: R514`, clearing the max allocated id (R510).
No duplicate `id:`, no `status: Done` tombstones in `roadmap/*.md`, and a
`depends-on:` sweep over all 131 item files resolves every edge, R-number and slug
alike, to a present file (including R46's new slug edge to
`tenant-fanout-parallel-execution`). The new item (R510) carries well-formed
front-matter (`id`, `status`, `bucket`, `created`, `last-updated`).

## Cross-cutting observations

1. **R507 is now Done and confirmed orthogonal to §C; the standing expectation
   holds.** With R507 landed, its `RetiredVocabularyGuardTest` guards code comments,
   javadoc, main-source string literals, authored `.adoc`, and fixture SDL, and it
   explicitly excludes `roadmap/`. The recurring §C re-anchor work stays with these
   audits by design. The empty allowlist proves the R126/R504/R508 code scrubs held.
   **The §C cluster will not self-retire; do not expect it to.**
2. **A drift-free execution window is possible and is the goal.** This window's three
   code landings (R511 runtime-adaptive arm, R512 additive FK grammar, R513 helper-name
   resolver) each avoided renaming or removing a symbol any roadmap item cites: R511
   left the producer side byte-identical, R512 was purely additive, and R513 gutted
   only the *internal* name-derivation of emitters it kept. This is what "additive or
   internal-refactor" buys the roadmap, and it is the pattern to prefer: the model's
   public leaf/carrier vocabulary is the drift surface; churning it (R432/R314/R501/
   R508) shifts anchors, while adding behaviour behind stable names does not.
3. **Born-stale vetting at Spec entry worked this window.** Both items that entered
   Spec (R510 new, R282 advanced) were read against the current model and found
   current, per the prior audit's observation #4. R510 in particular describes the
   live serial runtime accurately despite being a fresh design item. Continue reading
   new- and newly-advanced-item bodies, not just counting them.
4. **A feature-author retense is not a full re-anchor.** R505's edit this window
   correctly retensed its R45 references to Done but left its born-stale
   `SourceKey.Reader` phrase in place, exactly the partial-mitigation pattern the
   prior audit's observation #3 named for R508's re-anchor sweep. The author fixes the
   names they are thinking about (here, the R45 tense the R45 closure prompted); the
   audit remains the only pass that reviews every phrase against the whole model.
5. **The re-platforming trilogy stayed complete and stable.** R431/R432/R314 did not
   reopen; the retired leaf names exist in code only as `former`/`guarded`/`dissolved`
   lineage prose, now guard-enforced by the Done R507. The churn source stayed on
   emit/feature/runtime work (R511/R512/R513) and design filing (R510/R282), not model
   decomposition, as the prior audits predicted.
6. **Carried cosmetic front-matter nit persists.** **R97**
   (`consumer-derived-input-tables`) still lacks `created:` / `last-updated:`. Not a
   build or dependency risk.
7. **`inference-axis-coverage.adoc`** remains an intentional CI-regenerated
   placeholder, not a roadmap item (no `R<n>`), correctly excluded.

---

_Review date: 2026-07-23._
