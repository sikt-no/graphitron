---
id: R266
title: "DELETE mutations onto the DeleteRows walker carrier, retiring @value (R222 DELETE slice)"
status: Backlog
bucket: structural
priority: 3
theme: structural-refactor
depends-on: []
created: 2026-06-01
last-updated: 2026-06-01
---

# DELETE mutations onto the DeleteRows walker carrier, retiring @value (R222 DELETE slice)

DELETE is the last partition-bearing DML verb still classified through the legacy `MutationInputResolver` + `TableInputArg` path. R246 moved the direct-`@table`/ID-return UPDATE onto `UpdateRowsWalker` + the `UpdateRows` carrier; R258 moved the payload-returning UPDATE shapes onto the same carrier. Both shipped. The `UpdateRowsField` javadoc already names the `DeleteRowsField` sibling this item lands, and `UpdateRows`'s own javadoc records the design hole DELETE forces: `UpdateRows.Identified`'s compact constructor enforces non-empty `setColumns`, and the family deliberately has no `Broadcast` arm because R246 rejects `multiRow: true` outright. DELETE has no SET clause and *does* support `multiRow: true` broadcast, so it cannot reuse `UpdateRows`; it needs its own carrier. This item is the R222 Stage 2 `DeleteRows` walker-carrier slice, mirroring R246/R258 for the DELETE verb. DELETE is also the last verb whose classification touches the `@value` partition machinery in `MutationInputResolver`, so carving it off retires the `@value` directive outright; this item therefore absorbs and discards R188 (see ["Retires @value entirely"](#retires-value-entirely-absorbs-r188) below).

## Why DELETE is not UpdateRows-minus-SET

The structural difference is in what the matched key means.

* **UPDATE** partitions input columns: the matched PK/UK columns go to WHERE (`keyColumns`), everything else goes to SET (`setColumns`). The matched key is a partition boundary.
* **DELETE** has no SET destination. *Every* admitted input column is a WHERE filter. The matched key is a cardinality *guard* (it proves the WHERE reduces to at most one row), not a partition boundary; non-key filter columns are legitimate extra predicates rather than orphans with nowhere to go.
* **`multiRow: true`** is a real DELETE shape (`deleteFilmsByReleaseYear` filters on the non-PK `release_year`). R246 refused broadcast UPDATE because "covering a PK/UK is *the* single-row UPDATE shape"; broadcast DELETE has a clear meaning and is already supported, so the carrier needs an arm for it.

Sketch (names pin at Spec):

```
sealed interface DeleteRows permits Identified, Broadcast {
    List<KeyColumn> whereColumns();        // all admitted columns; reuses R246's KeyColumn
    record Identified(MatchedKey matchedKey, List<KeyColumn> whereColumns) implements DeleteRows {}  // !multiRow: covers a PK/UK -> single-row guard
    record Broadcast(List<KeyColumn> whereColumns) implements DeleteRows {}                          // multiRow: true: no coverage required
}
```

`MatchedKey`, `KeyColumn`, `JooqCatalog.candidateKeys`, `WalkerResult`, and the WHERE emitters (`buildLookupWhereSingleRow`, `buildBulkLookupRowIn`) are all reusable from R246 as-is.

## Surface to migrate

Three DELETE shapes exist today, spanning three `MutationField` leaves (file: `model/MutationField.java`):

* **Direct ID-return**: `MutationDeleteTableField` (line 94) carries `tableInputArg` + `returnExpression`. Migrate to `DeleteRowsField` (slim `InputArgRef` + `DeleteRows`), dropping `tableInputArg` exactly as R246 did for `MutationUpdateTableField`. Covers `deleteFilms`, `deleteFilmsByReleaseYear`, `deleteFilmActorByNodeId`, `deleteFilmActorsByNodeId`.
* **Payload single**: DELETE rides the shared `MutationDmlRecordField` (line 207, live `DmlKind` range `{INSERT, UPSERT, DELETE}`). Carve DELETE into a `MutationDeletePayloadField` and narrow the shared leaf to `{INSERT, UPSERT}`, exactly as R258 carved UPDATE out. Covers `deleteFilmsIdCarrier`.
* **Payload bulk**: DELETE rides the shared `MutationBulkDmlRecordField` (line 306, live range `{INSERT, DELETE}`). Carve into `MutationBulkDeletePayloadField`, narrow the shared leaf to `{INSERT}`. Covers `deleteFilmsTableCarrier`.

Producers: add `DeleteRowsWalker` (translator over the already-classified `InputField` permits + `JooqCatalog`, the same R238/R246/R257 substrate concession) and `FieldBuilder.classifyDeleteTableField` / `classifyDeletePayloadField` (parallel to `classifyUpdateTableField` line 3376 / `classifyUpdatePayloadField` line 3500).

Emit cutover (carrier-driven in place, no new emitter class, per R246's precedent): `buildMutationDeleteFetcher` (line 1746), `buildRecordDeleteChain` (line 4051), and `buildBulkRecordPerRowDeleteBody` (line 4413) source their WHERE from `deleteRows().whereColumns()` instead of `tableInputArg.fieldBindings()`, feeding the existing `buildLookupWhereSingleRow` / `buildBulkLookupRowIn` helpers unchanged.

Error taxonomy + LSP: a `DeleteRowsError implements Rejection.AuthorError` sub-seal (one `permits` row added to `Rejection.AuthorError`, `lspCode()` under `graphitron.delete-rows.*`), wired into the LSP `Diagnostic` projector, `typed-rejection.adoc`, and `RejectionSeverityCoverageTest`, mirroring `UpdateRowsError`. The arm set differs: **no `NoSetFields`** (there is no SET to be empty); `NoUniqueKeyCoverage` fires only on the non-`multiRow` path; `UnsupportedInputFieldShape` carries over.

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

**Coordination kept from R188.** R145 (UPSERT) must take its conflict-target / SET partition from PK / conflict-key membership rather than re-introducing `@value`; with the directive removed, R145 has no `@value` to fall back to. R245 owns `@condition`-on-mutations *emit*; this item leaves `@condition` in its current state except for the DELETE override-condition decision in fork 4 below.

## Design forks to pin at Spec (consult principles-architect)

1. **Carrier arms.** `Identified` + `Broadcast` (recommended; impossible combinations excluded at production time per R222) versus a single arm carrying a `multiRow` flag.
2. **PK-or-UK vs PK-only.** Adopt PK-or-UK to match R246 and close R188's open question (recommended), versus keeping DELETE PK-only.
3. **WHERE = all admitted columns** (DELETE has no SET home for extras; recommended) versus matched-key-only with a reject-on-extra-columns rule.
4. **`@condition(override: true)`.** DELETE currently *admits* it (`MutationInputResolver` ~477-491, the developer-owned WHERE override hatch); R246 *inverted* R215's admit to a typed `OverrideConditionNotSupported` rejection for UPDATE because the emit half never landed. Spec must check whether DELETE's override-condition actually emits today: if yes, carry it onto the carrier; if no, mirror UPDATE's rejection pending R245.
5. **Slice granularity.** One slice (carrier + walker + all three DELETE shapes) versus two (R246-style direct-return first, then an R258-style payload follow-up). The machinery is proven, so one slice is the default; split only if review wants a tighter blast radius.

## Out of scope

* INSERT onto a carrier (the `InsertRows` slice, R122 partner). INSERT remains the last `tableInputArg`-bearing DML after this lands.
* UPSERT (R145).
* Raw-SDL walker substrate. This item takes the same translator concession R246 took; a `deleterows-walker-sdl-substrate` follow-up mirrors R257 if wanted.
* `@condition` *emit* wiring on mutations (R245). This item retires `@value` but leaves `@condition` half-functional, except for the DELETE override-condition admit/reject decision (fork 4).
