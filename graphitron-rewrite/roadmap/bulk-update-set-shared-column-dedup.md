---
id: R342
title: "Structural dedup for bulk UPDATE SET columns written by overlapping carriers"
status: In Review
bucket: feature
priority: 5
theme: nodeid
depends-on: []
created: 2026-06-19
last-updated: 2026-06-22
---

# Structural dedup for bulk UPDATE SET columns written by overlapping carriers

R322 closed the value-agreement gap on three of the four mutation write surfaces: the `@service`
jOOQ-record path, the `@mutation` INSERT path (single-row and bulk: structural dedup + agreement),
and the single-row `@mutation` UPDATE SET path (agreement preamble + the plain-field-vs-plain-field
reject). It deliberately left the **bulk** UPDATE SET path's decode-involving overlap to this
follow-up, on the grounds that, unlike the single-row SET `Map.put` silent last-write-wins R322
fixed, the bulk path fails *loud* (a duplicate derived-table column), so it is self-announcing rather
than a silent drop. R354 then deferred a second bulk-SET shape here: a self-FK `@nodeId` on a
list-input UPDATE, today rejected at validate time rather than emitted.

This item makes the bulk UPDATE SET path dedup shared backing columns and run the value-agreement
check (the bulk analogue of R322's INSERT dedup, `insertColumnPlan` + `emitInsertAgreementPrep`), and
in doing so lifts R354's bulk self-FK reject into working emission.

## Implementation notes (shipped, In Review)

Landed as designed below, with one design fork resolved during In Progress (consulted
`principles-architect`):

- **`setColumnPlan(List<SetGroup>)`** drives the three bulk SET emitters (within-SET dedup, the
  endorsement case). `emitSetBulkCellAdds` hoists per-row decode locals into a Phase-1 helper
  (`emitBulkSetDecodeLocals`, INSERT-style instanceof guard + presence-gated throw) so a composite
  group's cells and a shared column's gather all read one decode; the shared-column branch emits the
  coalesced `DSL.val(firstPresent)` cell with a pairwise `requireColumnAgreement`, reusing R354's
  `appendAgreementValue`.
- **Cross-partition (self-FK WHERE∩SET) fork.** The spec's "the dedup handles it" was ambiguous, since
  `setColumnPlan` only sees SET groups. Resolved as: the two v-*populating* emitters
  (`emitSetVColNameAdds`, `emitSetBulkCellAdds`) skip a SET column already supplied as a WHERE/lookup
  v-column; `emitSetVFieldPuts` keeps the no-op `sets.put` (reads the WHERE v-column, keeps `sets`
  non-empty); and a new `emitBulkKeySetAgreement` emits the per-row check **reusing** the already-present
  per-row decode locals (`bulkKey<gi>` WHERE-side, `bulkSetKey_<gi>` SET-side) rather than re-decoding.
  Per `principles-architect` Q2 this is the cleaner generated output (two decodes/row, not three) and
  the stronger correctness story (the agreement guards the values actually used); the single-decode rule
  the spec stresses is load-bearing for the within-SET *cell* but advisory for the cross-partition check
  (which produces no cell). Verified by reading the emitted fetchers for both shapes.
- **Walker.** Stage 2b deleted; the now-dead `list` parameter dropped from `walk` (two `FieldBuilder`
  call sites + the class javadoc updated). `UnsupportedInputFieldShape` retains its other producers.
- **Tests.** `UpdateRowsWalkerTest`: the bulk self-FK reject test inverted to admit-and-route-all-SET,
  plus a decode-involving-overlap-admits-without-`PlainColumnCollision` test (15 pass). New
  `BulkUpdateSetAgreementExecutionTest` (7 execution-tier tests: within-SET agree/disagree/asymmetric
  ×2, self-FK agree/disagree/omitted). Schema fixtures `updateEndorsementsOverlap` /
  `updateEmailReplies` exercise the compilation tier. Full `mvn install -Plocal-db` reactor green.

## Current behavior (as found, post-R322/R354)

The bulk UPDATE renders `UPDATE t SET c = v.c FROM (VALUES …) AS v(col1, col2, …) WHERE …`. The bulk
path is selected purely by a list-typed `@table` argument: `FieldBuilder.classifyUpdateTableField` sets
`boolean list = inputArg.list()` (`:3691`) and routes to `buildBulkUpdateFetcher`. It is *not*
`@mutation(multiRow:)`, which R246 rejects outright on UPDATE (`FieldBuilder` `:3518`) before the walker
runs, so `multiRow: true` never reaches this path. The column-name list `v(…)` is built by
`TypeFetcherGenerator.emitSetVColNameAdds` (`:2940`), the per-row
cells by `emitSetBulkCellAdds` (`:2978`), and the SET assignment by `emitSetVFieldPuts` (`:3017`), all
walking `List<SetGroup>` per group with no cross-group column dedup (the shape the INSERT path had
before R322's `insertColumnPlan`). `SetGroup` (`:2430`, built by `setGroupsOf` at `:2573`) is keyed by
*access path*, so a `@nodeId` decode and a plain `@field` that both land on one backing column are two
separate groups, and each adds that column independently. Two bulk shapes are unhandled:

- **Decode-involving SET overlap.** Two SET carriers write one column (e.g. a plain `@field` plus a
  `@nodeId` FK reference whose lifted child column coincides) on a list-input (list-typed `@table` arg) UPDATE. The
  column name is added to `v(…)` twice, producing a duplicate column in the derived table: a loud
  Postgres / jOOQ error, not a silent drop. R322's all-plain reject
  (`UpdateRowsError.PlainColumnCollision` in `UpdateRowsWalker`, `:194`) already covers the
  plain-vs-plain case on the bulk path (the walker feeds both single-row and bulk), so what remains is
  only the decode-involving overlap.
- **Self-FK `@nodeId` on a bulk UPDATE.** R354 shipped the single-row self-FK `@nodeId @reference`
  all-SET routing with a cross-partition (WHERE ∩ SET) agreement preamble, but fenced off the bulk
  form at `UpdateRowsWalker` Stage 2b (`:105`) with a clear `UnsupportedInputFieldShape` reject,
  precisely because the emitter would crash on the duplicate `v(…)` column this item removes.

The cleanest framing is by the agreement surfaces the single-row path already has and the bulk path
lacks entirely: the single-row UPDATE runs *both* `emitSetAgreementPreamble` (within-SET overlap,
`:2713`) and R354's `emitKeySetAgreementPreamble` (cross-partition WHERE ∩ SET, `:2811`); the bulk
path runs neither. The two deferred shapes above are exactly those two missing surfaces, within-SET
and WHERE ∩ SET, and one dedup plan supplies both.

## Design

Drive the three bulk emitters off a single per-column SET plan, `setColumnPlan(List<SetGroup>)`,
mirroring `insertColumnPlan`: group the set groups' columns by backing-column `sqlName` into an
ordered list of contributing writers (each carrying its source `SetGroup` index, the slot within that
group's decode, and the `ColumnRef`), keeping every column but marking those with two-or-more writers
`shared`. The contributor grouping is the `[groupIndex, slot]` shape `emitSetAgreementPreamble`
(`:2713`) already builds; this item promotes it to a reusable plan the three emitters read off, so the
column-name list, the per-row cells, and the `sets.put` entries cannot drift out of positional
alignment. Each emitter then emits exactly one entry per distinct column:

- `emitSetVColNameAdds`: one `vColNames.add(...)` per plan column (was: one per group-column).
- `emitSetVFieldPuts`: one `sets.put(t.col, v.field(t.col))` per plan column.
- `emitSetBulkCellAdds`: one `cells.add(...)` per plan column. For a **disjoint** column the existing
  one-writer path is preserved byte-for-byte; for a **shared** column, inside the per-row loop, gather
  the present writers' values, run `NodeIdEncoder.requireColumnAgreement` pairwise against the first
  present writer, and `cells.add` the single coalesced typed `DSL.val(firstPresent, col.getDataType())`.

The presence gate the three emitters apply to a column must be lifted to the plan as well. Today each
emitter gates a group on its own first-row presence (`firstRowSetPresenceExpr`); a disjoint column
keeps that single-writer gate unchanged, but a shared column's gate becomes the *disjunction* of its
contributing writers' first-row presence, and `emitSetVColNameAdds`, `emitSetVFieldPuts`, and
`emitSetBulkCellAdds` must all read the same gate so the `v(…)` column-name list, the per-row cells,
and the `sets.put` entries stay positionally aligned. A column written by a plain `@field` *or* a
nullable `@nodeId` FK can have either writer omitted; the uniform-shape guard (`buildUniformShapeGuard`
+ `buildNestedShapeGuards`, run before the column list is built) makes the present-writer set uniform
across rows, so projecting the first row's disjunction onto every row's cell is safe. This is the one
place the INSERT analogy does not carry: `insertColumnPlan` lists every column unconditionally
(`DSL.defaultValue` when absent), but the bulk UPDATE SET list is conditional (PATCH semantics, hence
the `DSL.val(firstPresent)` above and not `defaultValue`). A gate keyed on a single writer would drop a
shared column from `v(…)` while still emitting its cell (or the reverse), reintroducing the arity
mismatch / duplicate-or-missing-`v`-column crash this item removes, or leave the per-row gather empty so
`DSL.val(firstPresent)` throws.

The agreement must produce the column's single cell, not run as a side preamble. The bulk derived
table forbids the single-row path's last-write-wins `Map.put` affordance (a second `cells.add`
re-introduces the duplicate-column crash), so this mirrors `emitInsertAgreementPrep`'s coalesced-cell
shape (`:2336`) transplanted into the row loop, not `emitSetAgreementPreamble`'s
check-then-let-the-puts-run shape. R354's existing `appendAgreementValue` / `emitAgreementDecodeLocal`
helpers (`:2879`-`:2891`) are the reusable "read this writer's per-row value into the gather list"
half; the bulk path coalesces the gathered list into the cell rather than discarding it after the
check. The per-row decode local must be emitted once per row and read by both the gather list and the
cell (as INSERT reads `<prefix>_<fi>.value<slot+1>()` off the one per-leaf decode record), not
re-decoded per writer; preserving this single-decode property is the one thing to verify at In
Progress.

With the dedup in place, **delete `UpdateRowsWalker` Stage 2b's bulk self-FK reject** (`:105`-`:119`):
a self-FK's SET column that coincides with an identity (WHERE) column is then just another shared
column the dedup handles, and the per-row agreement covers the FK-forces-equal invariant. R354 already
did the cardinality-independent routing (Stage 4-5 key coverage and Stage 6 all-SET routing read
`carrier.selfReference()`, not the `list` flag), so removing Stage 2b exposes a shape the classifier
already routes correctly; the only missing piece is the emitter dedup this item adds. This is the
WHERE ∩ SET agreement surface for the bulk path: the shared column appears in both the WHERE (from the
identity field) and the SET (from the self-FK), and the per-row agreement decodes both and asserts
they match before the DML.

## Scope decisions (design forks, resolved with `principles-architect`)

- **Do not lift the shared overlap-analysis abstraction in this item.** R322's D1 and R354 both named
  R342 as the place to consider lifting the column->ordered-writers analysis into one carrier-agnostic
  writer abstraction, since the grouping now exists in five instantiations
  (`JooqRecordInstantiationEmitter.analyzeOverlap`, `TypeFetcherGenerator.insertColumnPlan`,
  `MutationInputResolver.collectSetColumns`, `emitSetAgreementPreamble`, and R354's
  `emitKeySetAgreementPreamble`). The principled read: the five are a family resemblance, not one
  predicate. Two live at validate time over `InputField`, three at emit time over three different
  carrier models (`InputField`, `CallSiteExtraction.{ColumnBinding,RecordKeyDecode}`, `SetGroup`), and
  R354's site is a *cross-partition* intersection with two contributor lists, not a within-clause
  overlap. A single abstraction spanning all of them would straddle the validate/emit boundary and
  carry which-partition state the within-clause sites never read: a premature consolidation whose blast
  radius is the whole five-site surface. The genuine Generation-thinking lift is a *model-carried
  overlap fact* (each consumer reads the grouping off the model rather than re-deriving it), which is a
  larger change than a feature slice should carry. `setColumnPlan` is a disciplined clone of
  `insertColumnPlan` (the same plan-then-three-aligned-walks structure the INSERT path already
  legitimised), which removes the column-list/cell drift risk for the bulk path without the lift. The
  lift is filed as its own item, **R356** (`unify-shared-column-overlap-analysis`, Backlog).
- **Fold the self-FK bulk shape in (do not split to a separate item).** It is the same emitter, the
  same `FROM (VALUES …)` derived table, and the same `setColumnPlan`; R354 already carries the
  `selfReference` discriminator in the model and routes both cardinalities identically, so folding in
  *removes* Stage 2b rather than importing R354's routing logic. It also supplies the bulk path's
  WHERE ∩ SET agreement surface, the natural companion of the within-SET surface, not a separate
  concern.
- **Per-row agreement is built into the cell (INSERT shape), not a preamble (single-row SET shape).**
  See Design above; the bulk derived table has no last-write-wins affordance to lean on.

## Implementation

All in `graphitron/src/main/java/no/sikt/graphitron/rewrite/`.

- **`generators/TypeFetcherGenerator.java`**
  - Add `setColumnPlan(List<SetGroup>)` returning per-column ordered writers (source group index +
    slot + `ColumnRef`), each column flagged `shared` when it has two-or-more writers. Model it on
    `insertColumnPlan` (`:2290`) and the `[groupIndex, slot]` contributor grouping already in
    `emitSetAgreementPreamble` (`:2713`).
  - Rewrite `emitSetVColNameAdds` (`:2940`), `emitSetBulkCellAdds` (`:2978`), and `emitSetVFieldPuts`
    (`:3017`) to walk the plan, one entry per distinct column. `emitSetBulkCellAdds` grows the
    shared-column coalesced-cell-with-agreement branch (reusing `appendAgreementValue` /
    `emitAgreementDecodeLocal`, `:2879`-`:2891`); disjoint columns stay byte-identical, and the
    no-overlap bulk UPDATE emits exactly as it does today.
  - Confirm the per-row decode local is emitted once per row and shared by the gather list and the
    cell (no per-writer re-decode).
- **`walker/UpdateRowsWalker.java`**
  - Delete Stage 2b's bulk self-FK `UnsupportedInputFieldShape` reject (`:105`-`:119`). Stages 4-5/6
    (`:131`-`:184`) already route self-FK columns wholly to SET regardless of cardinality. Leave the
    plain-collision reject (`:194`) untouched.
  - With Stage 2b gone, `walk`'s `list` parameter has no remaining reader (it gated only Stage 2b; the
    list-typed-carrier reject in `classifyColumnCarrier` reads *that* method's own `list`, `:307`/`:311`,
    not this one). Drop the parameter and update the two call sites (`FieldBuilder` `:3722`, `:3870`);
    the sibling `field` parameter stays (reserved for the SDL-substrate follow-up). Regardless of
    keep-or-drop, the class javadoc (`:42`-`:45`) goes false and must be updated: it states the walker
    "reads it only to defer a self-FK `@reference` on the bulk (list-input) form to R342," which is the
    exact deferral this item removes.
  - If removing Stage 2b leaves `UnsupportedInputFieldShape` with no remaining producer, do not delete
    the variant blindly (it is a general shape-reject): confirm `RejectionSeverityCoverageTest` still
    has a reaching case, and if not, surface the orphaning to the reviewer as part of the item.
    (Confirmed at spec time: the variant retains producers at `UpdateRowsWalker`
    `:248`/`:256`/`:262`/`:273`/`:312`/`:322`, so removing Stage 2b does not orphan it; this bullet is a
    guard against an unexpected regression, not an expected action.)

## Tests

Per the tiers in `rewrite-design-principles.adoc`.

- **Execution (`@ExecutionTier`), the primary and load-bearing net.**
  - A bulk (list-input, list-typed `@table` arg, no `multiRow`) UPDATE whose SET column is written by both a plain `@field`
    and a `@nodeId` FK reference: agreeing rows update the agreed value (proving the dedup, no
    duplicate-`v`-column crash), a disagreeing row throws and the transaction rolls back. Reuse the
    R322 `film_endorsement` / `endorsed_film` overlap shape, lifted to a list input
    (`NodeIdValueAgreementExecutionTest` is the single-element / INSERT sibling).
  - **Asymmetric presence** on that same overlap shape: a list where only one of the two overlapping
    writers (the plain `@field` or the `@nodeId` FK) is supplied. The shared column must still update
    from the present writer, with no missing-or-duplicate `v(…)` column. The both-present agreement
    test above cannot reach this: a presence gate keyed on the wrong writer drops the column silently
    here (there is no duplicate-column crash to announce it), the exact silent-drop class R322 closed
    on the other write surfaces. This is the behavioural pin on the shared-column disjunction gate
    (see Design).
  - The self-FK bulk WHERE ∩ SET case: lift `SelfFkNodeIdUpdateExecutionTest`'s `email` / `mailbox`
    self-FK fixture to a list input (a list-typed `@table` arg as `updateFilms` already uses, no `multiRow`); agreeing rows repoint, a disagreeing row throws
    per row, an omitted nullable leaves the lone decode. This pins that deleting Stage 2b yields
    correct emission, not the reintroduced crash (the validator-mirror gate: the reject is replaced by
    working emission, not just unblocked).
- **Pipeline (`@PipelineTier`).** `UpdateRowsWalker` admits the self-FK bulk shape (no longer rejects
  at Stage 2b) and routes its columns all-SET; the decode-involving bulk SET overlap classifies
  without a `PlainColumnCollision` (it is decode-involving, deferred to the runtime agreement). A
  structural assertion on the walker result, not a fetcher-body string. This inverts the existing
  `UpdateRowsWalkerTest.selfFkReference_onBulkInput_rejectsUnsupportedShape` (`:194`) from a
  reject-assertion into the admit-and-route-all-SET assertion, rather than adding a parallel test.
- **Compilation (`@CompilationTier`).** `graphitron-sakila-example` compiles a bulk self-FK and a bulk
  plain+FK-overlap UPDATE input against the real jOOQ types at Java 17 (the full
  `mvn install -Plocal-db` reactor).
- **No generated-body string assertions.** The dedup's presence is pinned behaviourally at the
  execution tier (agreeing rows succeed where a duplicate column would have crashed), not by grepping
  the emitted `v(…)` list out of the fetcher `toString()`.

## Out of scope

- **The shared overlap-analysis abstraction lift**: R356. This item adds the disciplined sixth walk
  (`setColumnPlan`) and reuses R354's two agreement helpers; the cross-site unification, and the deeper
  model-carried-overlap-fact lift, is R356's whole scope.
- **Non-Postgres dialects.** Execution-tier coverage stays Postgres-only, consistent with the rest of
  the DML surface.
- **Any change to the single-row UPDATE SET, INSERT, or `@service` paths.** They shipped in R322/R354;
  this item only touches the three bulk UPDATE SET emitters and removes the one bulk self-FK fence.

## Relationship to other items

- **R322** (Done): shipped `@service` + INSERT + single-row UPDATE SET agreement and the cross-path
  plain-field reject; left this bulk-SET dedup as the one remaining loud-failure surface, and named the
  abstraction-lift question this item resolves (to R356).
- **R354** (Done): shipped the single-row self-FK `@nodeId` all-SET routing and the WHERE ∩ SET
  agreement preamble, deferring the bulk self-FK form here via the Stage 2b reject this item removes.
- **R328** (Done): made the self-FK shared-column overlap reachable via a natural self-FK shape, and
  named this item as the bulk surface where it still failed loud.
- **R356** (Backlog, filed alongside this spec, depends on it): the shared overlap-analysis
  abstraction lift across the five (now six) instantiation sites.
