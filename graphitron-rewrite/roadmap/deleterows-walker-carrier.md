---
id: R266
title: DELETE mutations onto the DeleteRows walker carrier, retiring @value (R222 DELETE slice)
status: Ready
bucket: structural
priority: 3
theme: structural-refactor
depends-on: []
created: 2026-06-01
last-updated: 2026-06-08
---

# DELETE mutations onto the DeleteRows walker carrier, retiring @value (R222 DELETE slice)

DELETE is the last partition-bearing DML verb still classified through the legacy `MutationInputResolver` + `TableInputArg` path. R246 moved the direct-`@table`/ID-return UPDATE onto `UpdateRowsWalker` + the `UpdateRows` carrier; R258 moved the payload-returning UPDATE shapes onto the same carrier. Both shipped. The `UpdateRowsField` javadoc already names the `DeleteRowsField` sibling this item lands, and `UpdateRows`'s own javadoc records the design hole DELETE forces: `UpdateRows.Identified`'s compact constructor enforces non-empty `setColumns`, and the family deliberately has no `Broadcast` arm because R246 rejects `multiRow: true` outright. DELETE has no SET clause and *does* support `multiRow: true` broadcast, so it cannot reuse `UpdateRows`; it needs its own carrier. This item is the R222 Stage 2 `DeleteRows` walker-carrier slice, mirroring R246/R258 for the DELETE verb. DELETE is also the last verb whose classification touches the `@value` partition machinery in `MutationInputResolver`, so carving it off retires the `@value` directive outright; this item therefore absorbs and discards R188 (see ["Retires @value entirely"](#retires-value-entirely-absorbs-r188) below).

## Review feedback (In Review → Ready, 2026-06-08)

Implementation landed at `57cb7b0`. The carrier, shared `MatchedKeys` matcher, walker, classifier interception, leaf migrations / `DmlKind` narrowing, the `@value` directive/`DIR_VALUE`/`acceptsValueMarker`/`requiresPkCoverage`/`valueMarkedNames` retirement, the LSP `Diagnostics` + `RejectionSeverityCoverageTest` wiring, `typed-rejection.adoc`, the `directives.graphqls` sweep, and the unit (`DeleteRowsWalkerTest`) + pipeline (`GraphitronSchemaBuilderTest`, `MutationDmlNodeIdClassificationTest`, `SingleRecordPayloadPipelineTest`) tiers all reviewed sound, and the full `mvn install -Plocal-db` (incl. docs render) is green. Two items must close before re-handoff; both are narrow.

1. **Stale `@value` recommendation in a live user-facing rejection message.** `BuildContext.java:1758-1762` (`classifyInputFieldInternal`, the `@lookupKey`-on-input-field guard, reachable for every `@table` input type via `TypeBuilder`/`InputFieldResolver`) still tells authors to "replace it with `@value` on UPDATE value fields", a directive this slice deletes from `directives.graphqls`. An author who follows it hits an unknown-directive error. The twin copy of this same message in `FieldBuilder.java:~994` *was* correctly rewritten to the catalog-derived phrasing in this commit, so this is a half-completed sweep, not a deliberate scope call. This is squarely in the "Schema, test, and doc sweep (absorbed)" scope below. Fix: drop the `@value` clause and align with the FieldBuilder twin ("…the field is a filter by default; the UPDATE SET/WHERE partition is derived from the catalog by the walker"). Re-grep `@value` across non-roadmap, non-comment sites once more after the edit.

2. **Execution-tier UK-covering single-row delete did not ship.** The Tests § Execution bullet promised "a UK-covering single-row delete (new coverage; R246 deferred the UK execution case for UPDATE)" round-tripped against Postgres. The UK case landed only at unit (`DeleteRowsWalkerTest.ukMatch_pkNotCovered_succeedsWithUniqueKey`) and pipeline (`MutationDmlNodeIdClassificationTest.ukCoveringDelete_admitsByUniqueKey_andMatchesUpdateKeyChoice`, a classify-tier assertion on a `parent_node`/`alt_key` fixture, not a round-trip) tiers. The example schema has no UK-covering (non-PK) DELETE mutation and the commit added no execution test, so the net-new behavior (Behavior changes #1, delete-by-UK actually deleting the right row at runtime) is unproven at execution tier. Fix: add a UK-covering DELETE mutation over an executable catalog table that has a UNIQUE distinct from its PK, and an execution round-trip asserting the row is deleted by the UK. If a real UK-covering fixture is genuinely infeasible in the Sakila catalog, surface that here with rationale and an explicit descope rather than substituting the pipeline assertion silently.

Minor (fold into the same pass):

* **Plan housekeeping.** Collapse the shipped Implementation phases to one-line `shipped at 57cb7b0` notes per workflow.adoc; the body still reads as forward-looking.
* **Stale tense, `graphitron-sakila-example/.../schema.graphqls:1330`.** The R258 fixture comment says the `@value` directive "could be retired entirely (R266 …)"; R266 has now retired it, so the conditional reads stale. Trivial.

The reviewer-session ≠ implementer-session rule applies again next cycle.

## Why DELETE is not UpdateRows-minus-SET

The structural difference is in what the matched key means.

* **UPDATE** partitions input columns: the matched PK/UK columns go to WHERE (`keyColumns`), everything else goes to SET (`setColumns`). The matched key is a partition boundary.
* **DELETE** has no SET destination. *Every* admitted input column is a WHERE filter. The matched key is a cardinality *guard* (it proves the WHERE reduces to at most one row), not a partition boundary; non-key filter columns are legitimate extra predicates rather than orphans with nowhere to go.
* **`multiRow: true`** is a real DELETE shape (`deleteFilmsByReleaseYear` filters on the non-PK `release_year`). R246 refused broadcast UPDATE because "covering a PK/UK is *the* single-row UPDATE shape"; broadcast DELETE has a clear meaning and is already supported, so the carrier needs an arm for it.

Carrier shape:

```
sealed interface DeleteRows permits Identified, Broadcast {
    List<KeyColumn> whereColumns();        // all admitted columns; reuses R246's KeyColumn
    record Identified(MatchedKey matchedKey, List<KeyColumn> whereColumns) implements DeleteRows {}  // input covers a PK/UK -> single-row (multiRow flag moot)
    record Broadcast(List<KeyColumn> whereColumns) implements DeleteRows {}                          // multiRow: true AND no PK/UK covered
}
```

`whereColumns` is *every* admitted input column for both arms (a DELETE has no SET partition; the matched key on `Identified` is the single-row guard, not a column subset). `Broadcast`'s compact constructor rejects an empty `whereColumns`, so an empty input cannot degenerate into an unfiltered `DELETE`.

`MatchedKey`, `KeyColumn`, `JooqCatalog.candidateKeys`, `WalkerResult`, and the WHERE emitters (`buildLookupWhereSingleRow`, `buildBulkLookupRowIn`) are all reused from R246. The PK-or-UK match itself (`UpdateRowsWalker` Stages 4-5) is **extracted into a shared helper** both walkers call (see Implementation), so the identification logic is not duplicated. `KeyColumn`'s javadoc (today "WHERE side of an UPDATE") is generalised to cover both verbs.

## Surface to migrate

Three DELETE shapes exist today, spanning three `MutationField` leaves (file: `model/MutationField.java`):

* **Direct ID-return**: `MutationDeleteTableField` (line 94) carries `tableInputArg` + `returnExpression`. Migrate to `DeleteRowsField` (slim `InputArgRef` + `DeleteRows`), dropping `tableInputArg` exactly as R246 did for `MutationUpdateTableField`. Covers `deleteFilms`, `deleteFilmsByReleaseYear`, `deleteFilmActorByNodeId`, `deleteFilmActorsByNodeId`.
* **Payload single**: DELETE rides the shared `MutationDmlRecordField` (line 207, live `DmlKind` range `{INSERT, UPSERT, DELETE}`). Carve DELETE into a `MutationDeletePayloadField` and narrow the shared leaf to `{INSERT, UPSERT}`, exactly as R258 carved UPDATE out. Covers `deleteFilmsIdCarrier`.
* **Payload bulk**: DELETE rides the shared `MutationBulkDmlRecordField` (line 306, live range `{INSERT, DELETE}`). Carve into `MutationBulkDeletePayloadField`, narrow the shared leaf to `{INSERT}`. Covers `deleteFilmsTableCarrier`.

Producers: add `DeleteRowsWalker` (translator over the already-classified `InputField` permits + `JooqCatalog`, the same R238/R246/R257 substrate concession) and `FieldBuilder.classifyDeleteTableField` / `classifyDeletePayloadField` (parallel to `classifyUpdateTableField` line 3376 / `classifyUpdatePayloadField` line 3500). The payload classifier keeps DELETE's existing inline `reclassify` + `classifyDeleteTableProjection` (PK-only RETURNING, no follow-up SELECT); only the input-side WHERE source moves to the carrier.

Emit cutover (carrier-driven in place, no new emitter class, per R246's precedent): `buildMutationDeleteFetcher` (line 1746), `buildRecordDeleteChain` (line 4051), and `buildBulkRecordPerRowDeleteBody` (line 4413) source their WHERE from `deleteRows().whereColumns()` instead of `tableInputArg.fieldBindings()`, feeding the existing `buildLookupWhereSingleRow` / `buildBulkLookupRowIn` helpers unchanged.

Error taxonomy + LSP: a `DeleteRowsError implements Rejection.AuthorError` sub-seal (one `permits` row added to `Rejection.AuthorError`, `lspCode()` under `graphitron.delete-rows.*`), wired into the LSP `Diagnostic` projector, `typed-rejection.adoc`, and `RejectionSeverityCoverageTest`, mirroring `UpdateRowsError`. Three arms: `NoUniqueKeyCoverage` (non-`multiRow` input covers no PK/UK; subsumes R188's `table-has-no-pk`), `UnsupportedInputFieldShape`, and `OverrideConditionNotSupported`. **No `NoSetFields`** (no SET to be empty) and **no `MixedCarrierKeyMembership`** (no SET boundary for a composite carrier to straddle).

## Retires @value entirely (absorbs R188)

R246 absorbed R188's UPDATE-side partition scope; R258 the payload-UPDATE equivalent. This item absorbs **all of R188's remaining scope** and discards it, because carving DELETE off `MutationInputResolver.resolveInput` removes the last live `@value` consumer.

**Why @value goes fully dead here.** After R246/R258, UPDATE is intercepted before `resolveInput` (`FieldBuilder.java:3176-3191`), so only INSERT, DELETE, and UPSERT still reach the `valueMarkedNames` partition loop (`MutationInputResolver.java:356-382`). INSERT and DELETE only ever *reject* `@value` there (`DmlKind.acceptsValueMarker()` is true only for UPDATE), and UPSERT is refused upstream before the loop; so `valueMarkedNames` is already always empty when threaded into `TableInputArg.of` / `buildLookupBindings`. The moment this item routes DELETE through `DeleteRowsWalker` (as R246 did for UPDATE), INSERT becomes the lone verb in `resolveInput`, and it only rejects `@value`. The entire partition machinery is then dead, so this slice deletes it and the directive in one closing act.

**Partition scope (absorbed).** R188's `mutation-input.delete-input-field-non-pk` rule (DELETE input fields must cover a key) and `mutation-input.table-has-no-pk` rejection become the `DeleteRowsWalker`'s coverage check and `DeleteRowsError` arms. R188's flagged "PK-or-UK vs PK-only" divergence resolves here by adopting **PK-or-UK** for DELETE, matching R246.

**Directive retirement (absorbed, the closing act).** Delete, in the same slice:

* the `@value` directive declaration and doc block (`directives.graphqls:221-231`) and the `@value` reference in the `@lookupKey` docstring (`directives.graphqls:201`);
* `BuildContext.DIR_VALUE` (`:97`) and `GraphitronSchemaBuilder`'s `assertDirective(ctx, DIR_VALUE)` (`:495`);
* `DmlKind.acceptsValueMarker()` (`:30`);
* the `valueMarkedNames` accumulation loop, the `@value`-on-INSERT/DELETE rejection clauses, and the `@value` + `@condition` mutual-exclusion check in `MutationInputResolver` (`:356-382`, `:405`, `:423`);
* the `valueMarkedNames` parameter on `ArgumentRef.TableInputArg.of` (`:287-300`) and on `EnumMappingResolver.buildLookupBindings`.

**Schema, test, and doc sweep (absorbed).** Strip `@value` from the Sakila schema (2 sites) and the embedded test SDL (`GraphitronSchemaBuilderTest` ~18, `MutationDmlNodeIdClassificationTest` ~7, `FetcherPipelineTest` ~6, `SingleRecordPayloadPipelineTest` ~6) and rephrase the now-stale `@value` javadoc across `MutationField`, `TypeFetcherGenerator`, `UpdateRowsWalker`, `FieldBuilder`, `DmlKind`. Delete `docs/manual/reference/directives/value.adoc` and rewrite the `@value`-referencing partition prose in `docs/manual/reference/directives/mutation.adoc` to name the catalog-derived PK-or-UK inference rule (R188 carried the replacement prose; lift it from R188's "User documentation" section in git history when drafting).

**Coordination kept from R188.** R145 (UPSERT) must take its conflict-target / SET partition from PK / conflict-key membership rather than re-introducing `@value`; with the directive removed, R145 has no `@value` to fall back to. R245 owns `@condition`-on-mutations *emit*; this item leaves `@condition` in its current state except for the DELETE override-condition, which this item rejects (see Decisions).

## Decisions

Settled with the requester (and, for the carrier-shape fork, with `principles-architect`):

1. **PK-or-UK identification**, matching R246. No PK and no covered UK without `multiRow` is a typed rejection (`NoUniqueKeyCoverage`), closing R188's silent PK-less gap. Admitting a UK-covering DELETE is a deliberate behavior change (see Behavior changes).
2. **WHERE = every admitted input column.** The matched key is the single-row guard, not a column subset; extra non-key columns remain as additional ANDed predicates (preserves today's behavior).
3. **`Identified | Broadcast` arms.** `Broadcast` is the `multiRow: true` shape (no key coverage required); its compact constructor rejects empty `whereColumns`.
4. **Reject `@condition(override: true)`** with `DeleteRowsError.OverrideConditionNotSupported`, mirroring R246. Verified in code that DELETE's override-condition is admit-but-no-emit today (the `UnboundField` never becomes a binding, so no `.where(...)` is produced); rejecting turns a silent no-op into a build error. Real support is R245's.
5. **One slice, all six DELETE shapes** (direct ID-return single/bulk, `multiRow` broadcast, composite-PK NodeId single/bulk, payload ID-carrier + table-carrier). Direct and payload DELETE are the same operation ("match by key, delete, return the PKs"), so one carrier serves both.
6. **Parallel `DeleteRows`, not R222's shared `PredicateCarrier.LookupRows`.** `principles-architect` weighed the fork: a shared `LookupRows` is the principled *end-state* (the PK-or-UK match genuinely is one concern), but introducing it now is the weakest moment, because (a) `Broadcast` has no matched key at all, a shape UPDATE never has, so even a "shared" carrier would need a DELETE-only arm; (b) doing it coherently means re-homing the shipped `UpdateRows` (four leaves, the error arms, UPDATE's whole test tier), which is larger than this slice and churns working code with no forcing function yet; (c) the staged alternative ships two live WHERE representations at once, against R222's "additive cutover, short window" rule. Parallel `DeleteRows` *defers* `LookupRows` cheaply (the shared matcher helper + verb-neutral `MatchedKey` / `KeyColumn` leave a clean seam) without foreclosing it. The `LookupRows` extraction is a future item to be filed when a third consumer (`InsertRows`, or a cross-verb predicate reader) makes the shared contract clearest.

## Implementation

Mirror R246/R258 commit-by-commit (additive cutover, then destructive retirement):

1. **Model.** New `DeleteRows` (sealed `Identified | Broadcast`), `DeleteRowsField` interface (`inputArg()` + `deleteRows()`), and `DeleteRowsError` sub-seal (the three arms above) added to `Rejection.AuthorError`'s `permits`. Generalise the `KeyColumn` javadoc to name both verbs.
2. **Shared matcher.** Extract `UpdateRowsWalker` Stages 4-5 (candidate-key enumeration + PK-first subset match + `MatchedKey` lift) into a shared helper (e.g. `MatchedKeys.firstCovered(JooqCatalog, TableRef, Set<String> coveredSqlNames) -> Optional<MatchedKey>`); call it from both `UpdateRowsWalker` and the new `DeleteRowsWalker`. No carrier-shape change to `UpdateRows`.
3. **Walker.** `DeleteRowsWalker` reshapes the classified `InputField` permits into `whereColumns` (all admitted column carriers), runs the shared matcher: key covered → `Identified`; else `multiRow` → `Broadcast`; else `NoUniqueKeyCoverage`. Reject `@condition(override: true)` (`OverrideConditionNotSupported`) and non-admitted carrier shapes (`UnsupportedInputFieldShape`).
4. **Leaves.** Migrate `MutationDeleteTableField` to `DeleteRowsField` (drop `tableInputArg`, gain `InputArgRef` + `DeleteRows`). Add `MutationDeletePayloadField` / `MutationBulkDeletePayloadField` (both `DeleteRowsField`); narrow `MutationDmlRecordField` to `{INSERT, UPSERT}` and `MutationBulkDmlRecordField` to `{INSERT}` (compact-ctor range checks, as R258 did for UPDATE).
5. **Classifier.** `FieldBuilder.classifyDeleteTableField` (direct) + `classifyDeletePayloadField` (payload), intercepting DELETE before `resolveInput` the way R246/R258 intercept UPDATE. The payload classifier retains the inline `reclassify` + `classifyDeleteTableProjection`.
6. **Emit.** Point `buildMutationDeleteFetcher` / `buildRecordDeleteChain` / `buildBulkRecordPerRowDeleteBody` at `deleteRows().whereColumns()`; reuse the WHERE emitters unchanged.
7. **`@value` retirement, LSP wiring, doc sweep** per the sections above.

## Behavior changes

Three deliberate changes; all surface as build-time diagnostics, none silent:

* A DELETE input covering a **UK but not the PK** now admits (single-row by the UK); today it rejects for not covering the PK.
* A DELETE on a table with **no PK and no covered UK**, without `multiRow`, is now a typed rejection; today the PK-coverage check is skipped and the delete runs unguarded.
* **`@condition(override: true)`** on a DELETE input is now a build error; today it is silently dropped (admit-but-no-emit).

## Tests

Mirror R246/R258's tiering (`rewrite-design-principles.adoc`):

* **Unit** `DeleteRowsWalkerTest`: PK match, UK match, composite-PK (NodeId) match, `multiRow` → `Broadcast`, no-coverage → `NoUniqueKeyCoverage`, empty-`Broadcast` rejection, override-condition → `OverrideConditionNotSupported`, non-admitted shapes.
* **Pipeline** `GraphitronSchemaBuilderTest`: the leaf migrations (direct + both payload leaves carry `DeleteRows`), the `DmlKind`-range narrowing on the shared leaves, the new PK-less / UK-only / override-reject cases, and shared-matcher parity (a UK-covering DELETE and the equivalent UPDATE select the same key).
* **Execution**: round-trip the six Sakila DELETE mutations, including a UK-covering single-row delete (new coverage; R246 deferred the UK execution case for UPDATE) and the `multiRow` broadcast asserting `|affected| > 1`.
* **Compilation**: `graphitron-sakila-example` compiles after the `@value` strip.

## User documentation

* Delete `docs/manual/reference/directives/value.adoc`; rewrite the `@value`-referencing partition prose in `mutation.adoc` to name the catalog-derived PK-or-UK inference rule (lift R188's replacement prose from git history).
* Note in `mutation.adoc` that DELETE identifies rows by PK-or-UK coverage, that extra input columns add ANDed filters, and that `multiRow: true` opts into a broadcast (non-key) delete.

## Out of scope

* The shared `PredicateCarrier.LookupRows` extraction (Decision 6). Deferred to a future item, unfiled by request; the shared matcher helper is the seam it grows from.
* INSERT onto a carrier (the `InsertRows` slice, R122 partner). INSERT remains the last `tableInputArg`-bearing DML after this lands.
* UPSERT (R145).
* `@condition` *emit* wiring on mutations (R245). This item retires `@value` and rejects DELETE override-condition; it does not make `@condition` emit.
* Raw-SDL walker substrate. This item takes the same translator concession R246 took; a `deleterows-walker-sdl-substrate` follow-up mirrors R257 if wanted.
