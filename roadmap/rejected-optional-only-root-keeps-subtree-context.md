---
id: R1023
title: "A rejected optional-only tenant root does not void its subtree tenant context"
status: In Review
bucket: bug
priority: 1
theme: diagnostics
depends-on: []
created: 2026-10-13
last-updated: 2026-10-13
---

# A rejected optional-only tenant root does not void its subtree tenant context

## Goal

A field whose tenant arguments are all optional rejects with "takes its tenant only from optional values", and that one rejection is the only error it causes: the fields below it are judged as they will be once the author applies either fix the rejection offers (make an argument non-null, or mark the field `@tenantFanOut`), both of which hand the subtree a tenant. Today the rejected field instead takes the tenant away from every type it reaches, on every route, so each tenant-scoped child of those types rejects too, even where another root hands it a tenant. On sis the 22 intended root rejections arrive buried under roughly 700 consequential ones (719 author errors in all, against 1 at the commit before the regression), and the nested ones read like a routing regression in children that routed fine before.

A minimal pair, from the prototype's test (sakila fixture, where `film` and `inventory` are tenant-scoped):

```graphql
type Film @table(name: "film") {
    inventories: [Inventory!]! @splitQuery
}
type Inventory @table(name: "inventory") { inventoryId: Int }
type Query {
    films(filmId: Int! @field(name: "film_id")): [Film!]!      # names a tenant on every request
    filmsMaybe(filmId: Int @field(name: "film_id")): [Film!]!  # may name none: rejects
}
```

Today this build reports two errors: `Query.filmsMaybe` ("takes its tenant only from optional values (filmId)") and `Film.inventories` ("no ancestor established a tenant context"), although `Film.inventories` runs on the tenant `Query.films` hands it and neither fix to `filmsMaybe` touches it. After this item it reports one, on `Query.filmsMaybe`, and `Film.inventories` inherits its parent's tenant. Without `Query.films` the outcome is the same: one error, on the optional-only root.

No build that fails today passes after this item; it fails on exactly the fields the author has to change. The one place an error is added is a `@tenantFanOut` field below an optional-only field that needs a tenant, which rejects today only after the author has fixed its ancestor (or not at all, below a node or entity type that dispatch enters with a tenant), and now rejects up front (see the rule below).

## Implementation

Terms used below. A *site* is one occurrence of a field in an operation shape; an *edge* is a field seen as a link from its parent type to the types it returns. A field's *reading* says how its own arguments name the tenant: `Guaranteed` (some slot is non-null on every request), `Conditional` (every slot naming the tenant is optional), `Declined` (an argument names the tenant column in a shape that cannot route), or `Unbound`. A type has *tenant context* (`TenantBindingIndex.Fold.tenantContextOf`) when every path from a root to it crosses an edge that hands a tenant down; a tenant-scoped field on such a type classifies `TenantBinding.Inherited`. A site *needs a tenant* when its own statement reaches a tenant-scoped table, or it is a connection-bound root `@service` with no global table in reach (the `needsTenant` and `connectionService` locals of `Fold.armOf` today).

**The premise.** `Fold.siteEstablishesContext` decides whether an edge hands a tenant down. Before `20e11d5af` (the optional-only rejection rework) it asked the direct binding's `divines()`, which held for optional slots; that commit narrowed it to `Reading.Guaranteed`. The context fold is every-path (`edgeEstablishesContext` is an `allMatch` over the edge's sites, and `foldAncestorContexts` closes `withoutContext` forward from the roots along every non-establishing edge), so one Conditional root reaching a type removes context from that type for every other root reaching it. Children of a tenant-scoped `@table` parent route only through the Inherited rung of `armOf` (`parentRowBinding` serves a *global* parent row that carries the tenant column, so it never rescues them), and with context gone they fall to the reading switch: no slot gives "no ancestor established a tenant context", an optional slot gives a second "optional values" rejection.

**The rule.** An unmarked `Reading.Conditional` site that needs a tenant hands its subtree a tenant, in both ancestor folds. *Unmarked* means the field carries neither `@tenantFanOut` nor `@globalData` (`Fold.fanMarked` and `Fold.globalMarked` both false): a marked field takes its verdict from its marker's ladder, not from the rungs this item reasons about, and keeps the treatment it has today in both folds.

- `Fold.siteEstablishesContext` (every-path, feeds `tenantContextOf` and the Inherited verdict) gains the rung beside `Reading.Guaranteed`.
- `Fold.siteDivinesTenant` (any-path, feeds `anyBoundAncestor` and the `@tenantFanOut` ladder's "tenant-bound ancestor" rung) gains the same rung, so the two folds keep reading one posture and the `anyBoundAncestor` javadoc's "any-path mirror of `tenantContextOf`" stays true. Under either fix the rejection offers, a `@tenantFanOut` field below such a site rejects (on the bound-ancestor rung after the non-null fix, on the fanned-ancestor rung after the fan-out fix), so it rejects now rather than one fix later. In a build that passes, the rung counts only sites that route by parent row (already divining) or inherit; an inheriting site's tenant came from an upstream divining or fanned edge, which already rejects the fan-out field, except where it came from a routable dispatch surface (`routableDispatchSurface`, a node or entity type entered with a tenant). There a `@tenantFanOut` field below an inheriting optional-only field newly rejects, which is correct: an ancestor fixed the tenant. The implementer pins that case if a fixture reaches it cheaply.

Both rungs call one predicate, `handsDownAsIfFixed(site)` or similar: the site is unmarked, its reading is Conditional, and it needs a tenant. The marker exemption carries weight in `siteDivinesTenant` only. `edgeEstablishesContext` already returns early on `fanMarked(edge)`, but `edgeDivinesTenant` calls `siteDivinesTenant` on every site. A `@tenantFanOut` field that narrows by an optional slot is Conditional and needs a tenant (`fanOutArmOf` rejects one that reaches no tenant-scoped table, and rejects a required slot), and it ends `Arm(FanOut)`. Without the exemption the rung would count it, and the check below would throw on a build that passes. Exempting marked sites in the predicate, rather than adding a `fanMarked` early return to `edgeDivinesTenant`, leaves `belowBoundEdge` below a fanned field exactly as it is today. `needsTenant` and `connectionService` move out of `armOf` into private helpers over the site and its reach (`reachedTables` is static and pure); `armOf` calls them in place of its locals and is otherwise unchanged. Computing the predicate once per site and carrying it as a fact is acceptable too, at the implementer's judgment.

**Why the rule is sound, and what enforces it.** In `armOf`, an unmarked Conditional site that needs a tenant has exactly three outcomes: it routes by its parent row (`parentRowBinding`, which both folds already count); it inherits because its parent type has context, so its subtree has that tenant on every path anyway; or it rejects, so nothing is generated and the subtree is judged as it will be after a fix. The marker paths are closed: a nested marked site rejects, `globalDataArmOf` rejects any reading that is not `Unbound`, and a fanned edge already hands a tenant down. The fold already judges by binding rather than by verdict: a Guaranteed site that rejects as cross-scope establishes context today.

That enumeration depends on the rung order in `armOf` and on what the rejection offers, neither of which a shared predicate pins. So:

- `Fold.run` checks it after classification: every site the shared predicate holds for must have ended `Arm(ParentRowBound)`, `Arm(Inherited)` or `Rejected`; anything else throws an `IllegalStateException` naming the coordinate as a generator bug. The `armOf` loop in `run` maps sites to verdicts already, so the check sits there.
- The fix coupling is stated once, in the javadoc of the shared predicate, with `{@link}`s to `optionalOnlyDetail` (which offers `@tenantFanOut` only where `acceptsFanOut` holds) and to `Rejection.AuthorError.UnroutedServiceCall` (a Conditional connection service always carries the optional-only detail as a decline, so it is never steered to `@globalData`, a fix that would not hand a tenant down). A future message that offers a fix outside those two must revisit the predicate.

A Conditional site that needs no tenant keeps handing down nothing. It falls through to the default source (`armOf`'s documented fall-through), so its subtree really has no tenant, and a tenant-scoped child below it still rejects on its own.

**Comments that state the reversed rule**, rewritten in the same change:

- the comment inside `siteEstablishesContext` ("Only a guaranteed binding names a tenant on every request; an optional-only field either rejects or inherits ...");
- the `Reading.Conditional` case comment in `armOf` ("a tenant-scoped child below it then finds no context and rejects on its own"), which stays true only for the site that needs no tenant;
- the `DirectBinding` javadoc paragraph saying a decline "stops divining for its subtree ... a child inheriting from it would inherit a value that is never computed": scope it to `Declined` and say that Declined has not adopted the forward-looking posture yet (see below), rather than leaving it read as the general rule.

**Docs.** No user-manual change. The tenant-scoping page already describes the intended behaviour for optional arguments ("the build rejects the field") and never mentions the cascade; `tenantFanOut.adoc` already lists "any ancestor that fixed the tenant" among its rejections.

**Out of scope.**

- *Declined and Unbound sites.* The same forward-looking argument applies to them: every decline detail offers a routing fix, and an Unbound site that needs a tenant rejects with "bind an argument", which would also hand a tenant down. Their cascades predate the regression (`divines()` required `declines.isEmpty()` and a slot), sis does not show them at scale, and adopting the posture for them reverses the 14 pinned "no ancestor established" assertions below unbound roots in `TenantBindingClassificationTest`, a larger and separate decision. This item restores the extent the fold had before `20e11d5af`, on the posture stated above; generalising it to "a site that needs a tenant hands one down in every build that passes" is a follow-up item, filed at Spec sign-off if the reviewer agrees the posture is right.
- *Cross-scope Conditional sites* (reach holds both tenant-scoped and global tables) need a tenant, so the rule counts them. They reject regardless; this matches both pre-`20e11d5af` behaviour and how a cross-scope Guaranteed site is treated today.

The prototype at `fd30be5ff` (pushed as `origin/claude/r1023-rejected-optional-only-root-keeps-subtree-context`, a `wip:` commit) covers `siteEstablishesContext`, the helper lift and the first two tests below; the `siteDivinesTenant` rung, the post-classification check and the comment rewrites are not in it yet.

## Tests

All in the generator's unit tier (`TenantBindingClassificationTest`, `TenantFanOutClassificationTest`, both `@UnitTier`). The new behaviour shows only in builds that reject, so no compilation or execution tier can observe it; the execution tier must stay green as a regression check.

- `TenantBindingClassificationTest.aRejectedOptionalOnlyRootLeavesItsSubtreeInheritingFromTheOtherRoots` (in the prototype): the minimal pair above, plus a child `inventoriesBy(filmId: Int @field(name: "film_id"))` carrying its own optional slot. Asserts one rejection, on `Query.filmsMaybe`, and both children `Inherited`. Fails on trunk (`Film.inventories` classifies `null`).
- `TenantFanOutClassificationTest.anUnmarkedOptionalOnlyParentRejectsAndHandsItsChildrenNoTenant` pinned the cascade on purpose. It flips, renamed `anUnmarkedOptionalOnlyParentRejectsAloneAndItsChildrenInherit`: a single rejection on `Query.films`, the only route to `Film`, and `Film.inventories` `Inherited`.
- New, in `TenantFanOutClassificationTest`: a `@tenantFanOut` child below an unmarked optional-only root that needs a tenant rejects on the tenant-bound-ancestor rung, beside the root's own rejection.
- Regression guard for the marker exemption: `TenantFanOutClassificationTest.aNullableTenantSlotOnAMarkedFieldNarrowsTheFanOut` and `aNullableNestedTenantSlotNarrowsTheFanOutThroughItsInputPath` (marked Conditional roots that need a tenant, ending `FanOut` with no rejections) stay green unchanged. Once the post-classification check lands, they are what would throw if the predicate counted marked sites.
- New, the fall-through: a Conditional root that needs no tenant still hands down nothing, so a tenant-scoped child under it rejects "no ancestor established a tenant context". Shape: a root `@service` handed a connection and returning a global `@table` type (so it is not a connection service and its reach is global), with an optional argument naming the tenant; assert the root classifies untenanted and the tenant-scoped child of the returned type rejects on its own. Confirm the root reads `Conditional`, not `Declined`, before relying on the shape.
- No existing "no ancestor established" assertion sits below a Conditional root that needs a tenant (those in `TenantBindingClassificationTest` sit below unbound or global roots); the prototype's green unit tier is the evidence, and the implementer re-runs it.

Acceptance beyond this repo: a sis rerun (`mvn -o clean install -P quick -pl :sis-graphql-spec,:fsapi-rest` on the sis upgrade branch, against this worktree's install) reports 23 author errors: the 22 optional-only root rejections plus the 1 that stood before `20e11d5af`, give or take any `@tenantFanOut` field the fan-out rung newly reaches. A count outside that is a finding to explain before In Review. sis is outside this repo, so the unit tests remain the evidence a reviewer can reproduce.

## Implementation notes

Landed as specified, in one commit on top of the prototype. Two points for the reviewer.

- *The fall-through fixture differs from the suggested shape.* A root `@service` returning a global `@table` reads `Unbound`, not `Conditional`: `collectFromServiceCall` returns before collecting any slot when the reach holds a global table. The test (`TenantBindingClassificationTest.anOptionalOnlyRootThatNeedsNoTenantStillHandsDownNothing`) uses `rateFilm(in: RateFilmInput)` instead, a root service handed no connection with an empty reach, whose decoded node-id slot is optional under the nullable input. Its sibling `anOptionalOnlyConnectionServiceRejectsAloneAndItsSubtreeInherits` (the same input on `rateFilmWithDsl`) rejects with the optional-only detail, which pins that the shape reads `Conditional`, and covers the `connectionService` half of `needsTenant`.
- *Mutation checks.* Each of the three parts was removed in turn against the two test classes: without the `siteDivinesTenant` rung, `markerUnderAnUnmarkedOptionalOnlyAncestorRejectsBesideIt` fails; without the marker exemption, the two narrowing tests throw the post-classification `IllegalStateException`, as the spec predicted; without `needsTenant` in the predicate, the fall-through test throws it too.

sis acceptance (`sis/upgrade-graphitron`, `sis-graphql-spec` against this worktree's install): 22 author errors, every one a root "takes its tenant only from optional values" rejection, none on a child and none from the fan-out rung. The spec expected 23, counting the one error that stood before `20e11d5af`; the sis tree now carries the `eierOrganisasjonskode` argument on `Query.godkjenningssakstatuskoder`, which sis's own status notes record as the fix that took tenancy from 1 to 0, so 22 is the expected count for this tree. `fsapi-rest-spec` runs no graphitron goal.

## Other solutions we've considered

- **Keep the cascade (the status quo).** It was pinned deliberately, on the reading that a rejected root hands its subtree nothing, so the subtree honestly has no tenant. That is true of the rejected build and of no build the author will ship: both fixes the rejection names hand a tenant down, so the child errors describe a state that disappears with the root's fix. They cost the author a search through ~700 consequential errors for the 22 that matter, and read as a regression in fields the author did not touch. The fold already takes the forward-looking reading for Guaranteed sites that reject on other grounds.
- **Suppress consequential errors at report time.** Keep the fold and filter child rejections whose cause is a rejected ancestor. That needs a causal link from each child rejection back to the ancestor that voided its context, which the every-path fold does not record, and it leaves the children classified `null` rather than `Inherited`, so later consumers of the binding (LSP hints, generated docs) still see the wrong answer. Fixing the fact is smaller and adds no second mechanism.
- **Count every Conditional site, needing a tenant or not.** A simpler predicate, but a Conditional site that needs no tenant runs on the default source and genuinely hands its subtree nothing, so its tenant-scoped children would classify `Inherited` with no tenant at runtime. Unsound.
- **Exempt fan-out fields in the fold or in the check instead of in the predicate.** A `fanMarked` early return in `edgeDivinesTenant` would also keep the check sound. But it would change which types land in `belowBoundEdge` below every fanned field, including those that bind no optional slot, which is behaviour this item has no reason to touch. Widening the check to accept `Arm(FanOut)` would make the check enumerate the marker ladders' outcomes as well as `armOf`'s, and the check exists to pin `armOf`'s three-outcome argument alone.
- **Change only the every-path fold.** Leaves `siteDivinesTenant` on the old posture, so the two folds disagree on one reading and a `@tenantFanOut` child below a rejected optional-only root is accepted now and rejected after either fix: the same two-step error discovery this item removes, run in reverse.

## Reviewer findings

### Round 1 (Spec → Ready review): request revisions

Reviewer: a fresh Claude Code session started by the user for this gate (its session ID is not exposed to it; the spec's commits carry no session trailer, so independence rests on the user's choice of session), 2026-10-13.

Question 1 passes. When this lands, a field whose tenant arguments are all optional produces exactly one error, its own, and the fields below it classify as they will once either fix is applied. The roughly 700 knock-on errors on sis go away, and no build that fails today starts passing. Every symbol, test and commit the spec names exists as named, the account of `armOf`'s rung order matches the tree, and the prototype at `fd30be5ff` shows the every-path half working.

Question 2 fails on one point: the predicate as specified, the second rung and the post-classification check do not fit together for `@tenantFanOut` fields.

1. **A fan-out field that narrows by optional slots would trip the new check on a valid build.** The predicate is "reading is Conditional and the site needs a tenant", with no exemption for marked sites. In `Fold.siteEstablishesContext` that is harmless, because `edgeEstablishesContext` returns early on `fanMarked(edge)`, so a fanned site never reaches the rung. `Fold.siteDivinesTenant` has no such early return: `edgeDivinesTenant` calls it on every site. Fanned fields that pass the build fall squarely inside the predicate. Every one of them needs a tenant (`fanOutArmOf` rejects one that reaches no tenant-scoped table), and none reads `Guaranteed`, because `fanOutArmOf` rejects a required slot. So every fanned field with an optional narrowing slot is counted by the new `siteDivinesTenant` rung and ends `Arm(FanOut)`, which is not in the check's allowed set (`ParentRowBound`, `Inherited`, `Rejected`). The spec's own soundness argument says "an *unmarked* Conditional site" and "the marker paths are closed", but neither the predicate nor the check says so. Reproduction: `TenantFanOutClassificationTest.aNullableTenantSlotOnAMarkedFieldNarrowsTheFanOut` (`Query.filmsNarrowable(filmId: Int ...) @tenantFanOut`, expected `FanOut` with no rejections) would throw `IllegalStateException` once the second rung and the check land as written. The prototype does not show this because it has neither. `@globalData` sites are not affected: `globalDataArmOf` rejects any reading that is not `Unbound`, as the spec says.

   Choosing the resolution belongs to the author. Each candidate changes something different:
   - exclude marked sites from the shared predicate;
   - give `edgeDivinesTenant` the same `fanMarked` early return that `edgeEstablishesContext` has;
   - widen the check to accept `Arm(FanOut)` on a fanned site.

   The first two change which types land in `belowBoundEdge` below a fanned field. Today `anyFannedAncestor` hides that difference, because it rejects a nested fan-out one rung before `anyBoundAncestor`. The spec should say which one it means, state the coupling it relies on, and add a test that pins a fanned Conditional root under the new check (the existing narrowing tests do this once the check exists, if the spec names them as the regression guard).

   *Author response:* took the first option. The rule and the shared predicate now cover unmarked sites only (`fanMarked` and `globalMarked` both false), and the post-classification check is stated over the sites that predicate holds for. "The rule" now says why the exemption matters in `siteDivinesTenant` alone. "Other solutions we've considered" says why the other two options lost: the early return would move `belowBoundEdge` below every fanned field, and the widened check would have to enumerate the marker ladders' outcomes. "Tests" names both existing narrowing tests as the regression guard.

Non-blocking: the spec says "~15" pinned "no ancestor established" assertions in `TenantBindingClassificationTest`; the tree has 14.

*Author response:* corrected to 14.
