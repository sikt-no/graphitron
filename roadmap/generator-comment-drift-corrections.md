---
id: R526
title: "Correct stale generator and model comment claims routed out of the javadoc sweep"
status: Ready
bucket: cleanup
priority: 4
theme: model-cleanup
depends-on: []
created: 2026-07-24
last-updated: 2026-07-24
---

# Correct stale generator and model comment claims routed out of the javadoc sweep

The comment-trimming sweep (the R524 changelog entry records it) found comment claims that are factually wrong or reference dead code, and routed them here instead of rewriting them, because each needs a code-level decision (fix the claim, fix the code, or delete the dead API) that a comment-only sweep must not make. Spec-time investigation verified every site and overturned two of the routed premises; the item stays a per-site corrections item (taxonomy changes it uncovered are filed as R528/R529 and named per claim), and where a claim's real fix is owned elsewhere the resolution here is the hand-off, never a parallel rewrite.

## 1. `GraphitronType.ResultType#fqClassName`: the routed premise is inverted

Routed as "every construction path supplies a non-null value; enforce or correct the caveat". Investigation: the truth probe fires the other way. Three live main-source sites mint `JooqTableRecordType` with a `null` `fqClassName` (`FieldBuilder` twice, `TypeBuilder` once) as a stand-in for "the parent's runtime source is a projected table row"; nothing is reflected there. So the interface caveat ("or `null` when not specified") is *true*, and the false doc is `JooqTableRecordType`'s own arm claim that `fqClassName` "is the binary class name". The reflection-derived paths always supply it; the stand-in population never does.

Plan: correct the arm javadoc to name both populations and where the null arises (the stand-in mint sites), and leave the interface caveat standing. The shape fix, a distinct class-less carrier arm so the slot stops being meaningless for one population, is R528's; this item's doc correction must name the split as the intended end state without blessing the null as design. Same-edit rule: `ReturnTypeRef.ResultReturnType#fqClassName` carries the same fact as a copied string with its own may-be-null caveat and four null-forking consumers in `FieldBuilder`; correct both carriers' docs in one edit so they state one coherent contract (what the copy's `null` means relative to the source's), and leave the carry-the-arm-identity redesign to R528.

## 2. `GraphitronType` InputType: the garbled parenthetical hides a lost axis

The sentence "The builder reflects on the method parameter type the input flows into (or `@table`) at build time" parses to nothing. Investigation: reconstructing it faithfully would document that backed-vs-unbacked on the input side is carried by a nullable backing class (`PojoInputType` with `null`), the very pattern the result side retired by permit identity, and the null feeds `CatalogBuilder.projectTypeBackingShapes`' fork into a *result*-named `NoBacking.UnbackedResult()` for an input type.

Plan: reconstruct the sentence honestly and non-blessing: the backing comes from reflecting the method parameter type the input flows into, or from resolving the input's `@table` directive; when neither resolves, `PojoInputType` carries a `null` backing class, an axis not yet carried by permit identity. Do not write the null up as design; the `Backed`/`Unbacked` split is R528's. `TypeClassification`'s restatement of the same fact is corrected in the same edit.

## 3. `TypeFetcherGenerator#buildTableMethodParentCorrelation`: routed to R527

The comment claims the empty-slot fallback's `DSL.noCondition()` is "runtime-throwing"; it is a silent no-op. This is the fifth restatement of the empty-slot `On.ColumnPairs` state whose resolution R527 owns (a compact-constructor invariant making empty slots unrepresentable, all restatement sites reconciled in one commit); R527's inventory has been amended to include this site. This item does not touch it: a local comment fix here would add a differently-worded fifth statement of a state R527 is about to delete. Provenance stays recorded by this section.

Adjacent finding, filed as R529: the same method's `unsupportedPath` block emits a runtime `UnsupportedOperationException` for a classifier-recognised sub-shape (multi-hop / condition-joined `@tableMethod`), which "Rejections: validator mirrors classifier invariants" says must be a build-time rejection. Out of scope here; named so the corrected surroundings do not read as clean.

## 4. `TypeFetcherGenerator`: two "Mapped is not produced yet" sites guard an unenforced partition

Both the `catchArm` and `asyncRouterCall` switches carry "Additive window: Mapped is not produced yet ... lands in a later commit" comments over `IllegalStateException` arms whose messages repeat the claim. The seam (`ChannelCatchArmEmitter`) has landed and `ErrorChannel.Mapped` is live. The deeper defect: one sealed `ErrorChannel` is dispatched by two emit seams that partition its arms between them, each seam's complement asserted only by a runtime throw plus prose, and `ChannelCatchArmEmitter`'s own `PayloadClass` throw carries the same stale future ("until it is deleted in slice-1 commit 4", a string R521 also tracks). Mirroring that site would copy a stale exemplar, not a healthy one. No open roadmap item owns finishing the cutover.

Plan: first determine reachability (can a `Mapped`-carrying field reach the legacy seams at all; if yes, that is a live bug to fix, not a comment to edit). Then make the partition compile-checked instead of throw-asserted: narrow what the legacy sites accept to the arms they actually emit (a sealed sub-interface over the legacy-routed arms, or equivalent parameter narrowing), which deletes both throws and both stale comments together, per "Every invariant has an enforcer" (a runtime throw is never one). If implementation finds the narrowing pulls in the full cutover, stop, restate both sites' comments and messages present-tense (no futures, name the live routing), and file the cutover as its own item; do not leave "later commit" promises standing either way. Coordinate the `ChannelCatchArmEmitter` message string with R521 (its habitat is a string literal, owned there; if the narrowing deletes the arm entirely, note it in R521 instead).

## 5. `RecordBindingResolver#fromAnyProducer`: delete, including the set behind it

Zero callers; the only reference keeping it alive is a `{@link}` from the `reachable` field's own javadoc. The stronger reason than dead-API hygiene: `reachable` is a materialized copy of `resultObserved.keySet() ∪ inputObserved.keySet()`, a derived fact maintained apart from its source ("Orthogonal facts are independent axes" names this drift smell), so even a future reader wanting the predicate should derive it at the read site, not inherit a third maintained set.

Plan: delete the method, the `reachable` set, and its population, and fix the field-adjacent javadoc, in one edit. If investigation reveals a site that *should* have been consulting it (a missed reachability check), surface that as a finding instead of silently deleting.

## 6. Dead `buildSplitQueryDataFetcher` / `buildRecordBasedDataFetcher` cites: five sites, three different honest fixes

The routed bullet named three sites; the inventory is five (`MultiTablePolymorphicEmitter` two, `RowsMethodCall` one, `GraphitronSchemaValidator` two). The live replacement seam is `DataLoaderFetcherEmitter`. But repointing everything at it would be dishonest in two places, so the fix differs per site:

- `MultiTablePolymorphicEmitter`'s two "mirrors ..." comments describe a hand-maintained shape agreement that no pin covers; `UnifiedEmissionPinsTest` *explicitly* carves this batched-fetcher family out of the unified-seam pin. A `{@link DataLoaderFetcherEmitter}` would read as verified linkage where none exists (form-1 shaped, form-3 in substance). Restate at intent altitude, naming the exclusion: this family hand-rolls its loader registration and does not route through the unified seam.
- `RowsMethodCall`'s class doc paragraph is refactor provenance ("replaces the three handcrafted inline lambda blocks..."); delete it, per "prefer deletion over rewrite". The class purpose survives, and the call-site count is already pinned by `UnifiedEmissionPinsTest`.
- `GraphitronSchemaValidator`'s two cites sit in the `isLocalContextGuardedDataChannel` null-source-guard rationale, a validator-side hand-list of emitter behavior ("removing the guard from an existing emitter arm must remove the variant here"). Repoint the names to the live fetcher-building path and truth-verify the guard claims against the live emitter arms while doing so. The stronger shape, single-sourcing the null-source-guarded predicate as a classifier-assigned accessor read by both the emitter prelude fork and the validator, is routed: file it as a follow-on at implementation with the sites named (per "Decide once, at the parse boundary", the branch belongs in the model).

## Acceptance

- Full reactor green under `mvn install -Plocal-db` with the `{@link}` reference gate and `RoadmapReferenceGuardTest` active.
- Claims 1, 2: both carriers' docs corrected coherently; no doc blesses a nullable-backing slot as design; R528 named as the shape owner.
- Claim 3: untouched here; R527's amended inventory covers the site (verified at its Done, not this item's).
- Claim 4: no "later commit" / "seam landed" future-promises remain in `TypeFetcherGenerator` (self-verifying: `grep -rn 'seam landed\|later commit\|slice-1 commit' graphitron/src/main/java` is empty or R521-owned string literals only); the arm partition is compile-checked or the cutover is filed.
- Claim 5: `fromAnyProducer`, the `reachable` set, and its population are gone.
- Claim 6: `grep -rn 'buildSplitQueryDataFetcher\|buildRecordBasedDataFetcher' graphitron*/src/main/java` returns nothing; the validator's guard claims are truth-verified, not just renamed.
- Follow-ons filed for everything routed (single-source predicate; any cutover item claim 4 surfaces).
