---
id: R829
title: "The payload-returning UPDATE arms cross-partition agreement check has no compile or execution coverage"
status: Backlog
bucket: bug
priority: 3
theme: mutation-write
depends-on: []
created: 2026-08-25
last-updated: 2026-08-25
---

# The payload-returning UPDATE arms cross-partition agreement check has no compile or execution coverage

`TypeFetcherGenerator` has four consumers of the `UpdateRows` carrier: direct-return and payload-returning, each single-row and bulk. All four now emit the cross-partition value-agreement preamble that compares two input fields' decoded values for one SQL column before any DML runs. Two of them, `buildCarrierUpdateChainSingle` and `buildCarrierBulkPerRowUpdateBody`, gained that preamble only recently, closing a gap where a self-FK overlap reaching a payload-returning UPDATE went unchecked entirely.

The problem is that no generated artifact anywhere in the corpus exercises it. The preamble returns early on an empty obligation list, and every payload-returning UPDATE in `graphitron-sakila-example`'s schema takes `FilmUpdateInput` or `FilmUpdateNestedInput`, neither of which carries a `@nodeId` reference of any kind. So the obligation list is empty at all four payload call sites, the emitter returns before writing a statement, and the code that consumes obligations there is never generated, never compiled, and never executed. The pipeline tier proves those carriers *hold* obligations (`MutationDmlNodeIdClassificationTest` asserts partition, slot and obligation on all four), which is what stops a consumer dropping the component silently, but a carrier assertion is not the same fact as an emitted preamble that compiles and throws.

That matters more here than the usual coverage gap, because the payload arms do not reuse the direct-return arms' emit context. `buildCarrierBulkPerRowUpdateBody` passes `"row"` as the map local rather than `"in"`, and emits into a per-row loop body rather than a post-guard block, so its preamble locals (`keySetAgreeK_<gi>`, `ksaK<gi>`, `keySetAgree<ci>`) are declared once per iteration in a scope the single-row arm never uses. `buildCarrierUpdateChainSingle` emits into `preGuard` rather than `postInGuard`. An emission defect in either, a local collision, a wrong scope, a decode local referenced before declaration, would be invisible to the entire build: the compile tier never sees the code, and the execution tier never runs it.

The fix is a fixture, not a code change. Add a payload-returning UPDATE over an input that carries an overlapping reference, either the existing self-FK shape (`UpdateEmailReplyInput`, whose FK shares `mailbox_id` with `email`'s primary key) or the straddling shape (`UpdateCatalogueItemInput`), on both the single-row and bulk payload arms; then pin the emitted statement in `DmlSqlBaselineTest` and the agreement throw at the execution tier, as the direct-return arms already are. The self-FK spelling is the cheaper of the two and covers the arms' newly-closed gap directly; the straddling spelling additionally exercises the carried decode slot through those arms.

Filed as a non-blocking finding at the Done gate of the item that wired the four consumers. That item's acceptance criteria scoped the four-consumer requirement to the pipeline-tier plan, which it met in full; its execution-tier plan also mentioned a payload-returning variant, and that is the piece this item carries. Related: R821, the *within*-SET value agreement those same two arms are also missing, which is a different fact (not a WHERE/SET overlap) and a behaviour change rather than a coverage gap.
