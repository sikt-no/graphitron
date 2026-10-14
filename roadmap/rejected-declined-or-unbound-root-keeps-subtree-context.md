---
id: R1027
title: "A rejected Declined or Unbound tenant root does not void its subtree tenant context"
status: In Progress
bucket: bug
priority: 3
theme: diagnostics
depends-on: []
created: 2026-10-13
last-updated: 2026-10-14
---

# A rejected Declined or Unbound tenant root does not void its subtree tenant context

## Goal

In a database-per-tenant build, a field or dispatch entry that needs a tenant and cannot get one is the only error it causes. That covers a field whose arguments decline to route (`Reading.Declined`) or name no tenant at all (`Reading.Unbound`), and an `_entities` or `Query.node` entry whose key does not carry the tenant column. The fields below it are judged as they will be once the author applies a fix the rejection offers, and every such fix hands the subtree a tenant. Today such a rejection hands its subtree nothing, so every tenant-scoped field below it rejects too with "no ancestor established a tenant context", and so does everything below those: on sis one entity key produced 670 such errors (bisected: removing the key leaves none). This extends to Declined, Unbound and dispatch rejections the forward-looking posture R1023 adopted for optional-only (`Conditional`) fields, so that "a site that needs a tenant hands one down in every build that passes" holds for every reading. A build that passes classifies exactly as it does today, with one deliberate exception: a `@tenantFanOut` field below a type that `_entities` routes to one tenant is rejected, as the `@tenantFanOut` reference already promises for "any ancestor that fixed the tenant". Otherwise only the error list of a failing build shrinks.

A minimal pair over the sakila fixture with `film_id` as the tenant column:

```graphql
type Film @table(name: "film") @key(fields: "title") {   # the key does not carry film_id
    title: String
    inventories: [Inventory!]! @splitQuery
}
type Inventory @table(name: "inventory") { inventoryId: Int }
```

Today: two errors, `Film` ("its federation key alternative #0 does not carry tenant column 'film_id'") and `Film.inventories` ("no ancestor established a tenant context"), plus one per tenant-scoped field below `Inventory`. After: the `Film` error alone, now naming its fix. The same holds with `type Query { inventories: [Inventory!]! }` as the unbound root of a `Film`/`Inventory` cycle: `Query.inventories` rejects, and `Inventory.film` and `Film.inventories` below it inherit.

## Evidence: a federation entity key as the rejected root

Reported from sis on 2026-10-14. A new stable entity `type FeideBruker @key(fields: "brukernavn")`, with `@table` on `PERSON` and a sis `@splitQuery` contribution `personProfil`, rejects correctly: "'FeideBruker' reaches tenant-scoped table 'PERSON' with no tenant binding in scope: its federation key alternative #0 does not carry tenant column 'INSTITUSJONSNR_EIER'". This is a root kind the Backlog goal did not name: an `_entities` key alternative, not a field's arguments.

The same build reports a cascade below `PersonProfil`, into `StudentVedLarested`, `Studieprogram`, `Emne`, `FagpersonVedLarested` and others. sis bisected it on 2026-10-14 against graphitron trunk `9e6f1fa49`, with every other author error from that rebase fixed. The entity alone produces 671 errors: the one rejection above, 649 "no ancestor established a tenant context", and 21 nested fields judged on their own optional slots (for example `Emne.undervisningsenheter` "takes its tenant only from optional values (terminer)", and `Studieprogram.kull`). Removing only `@key(fields: "brukernavn")` from `FeideBruker` takes the build to 0 errors. The 21 optional-slot errors are therefore not separate `Conditional` roots: they only surface because the voided parent context leaves those fields to be judged on their own. All 671 are this item's.

A second root kind showed up in the same work. `Query.sisNode(id: ID!): Node @notGenerated @inaccessible` is a node-shaped root field that classification rejects (`@notGenerated` is retired). It voided the tenant context of every `Node` type and produced a cascade of about 680 errors of its own. Removing the directive, so the field generates as an ordinary node lookup, cleared it. So a rejected root whose return type is `Node` reaches every node type at once. This item does not cover it: classification rejects that field outright, so it is a `GraphitronField.UnclassifiedField`, which is not an `OutputField`, and the fold records no site for it; `edgeEstablishesContext` answers false for its site-less edge. The rule below is a predicate over sites, built on a rejected site's reach, reading and offered fixes, none of which a classification-rejected field has, so it needs an argument of its own. That case is R1031. The field's own rejection was not missing: R1030 reproduced every variant on trunk and each reported it, so the cascade buried it, and R1030 was discarded.

Context, not a requirement: v9 routed this entity to the logged-in user's home institution from the token (`UserInfo.getHjemmeinstitusjonsnr`), with an ownership check. That rule is not R1005's request default tenant, which serves only global reads. sis has not decided which rule it wants.

### What this Spec settles about the entity case

The posture covers it. Every fix that keeps the entity resolvable has to route each representation to a tenant, and routing it hands the entity's rows, and so its subtree, a tenant: adding the tenant column to the key does that today (`TenantBinding.EntityRepBound`), and a token-derived rule like v9's, if sis asks for one, would be a new routing source that does the same. The one fix that hands nothing down removes the entry instead (every alternative `resolvable: false`), and then the type's context is decided by the field edges that reach it, which the fold already judges on their own. So "judge the subtree as if the entry routed" is right under every fix, and no choice of fix has to be known in advance. The 21 optional-slot errors are consequential too (the sis bisect confirms it), not `Conditional` territory: `armOf` runs the inherited-context rung ahead of the field's own reading (R1018), so `Emne.undervisningsenheter` inherits as soon as `Emne` has a context, and its optional `terminer` argument only filters.

Two further facts make the sis outcome robust to how the entity question is answered. The frontier rule below already collapses the cascade without the dispatch half: `FeideBruker.personProfil` would reject alone, and everything below it would inherit. The dispatch half removes that second error too, leaving the entity's own.

## The rule

The tenant fold (`TenantBindingIndex.Fold`) computes "does this type have a tenant on every path from a root" (`withoutContext`, read by `tenantContextOf`) and "is this type below an edge that divines a tenant on some path" (`belowBoundEdge`, read by the `@tenantFanOut` ladder), both before any field gets its verdict. A site counts as establishing context in the first fold through `siteEstablishesContext`, and as divining in the second through `siteDivinesTenant`. R1023 added `handsDownAsIfFixed` to both: an unmarked `Conditional` site whose own statement needs a tenant. This item widens that predicate to every reading but `Guaranteed` (which both folds already count on its own), and adds the dispatch entries.

### Which sites count

`handsDownAsIfFixed(site)` holds when all of these hold:

1. The field carries neither tenancy marker (unchanged: a marked field takes its verdict from the marker's ladder).
2. Its own statement needs a tenant (`needsTenant`, unchanged).
3. Its reach is not cross-scope: it does not touch tenant-scoped and global tables in one statement. That rejection (`armOf`'s first rung after the markers) offers no tenant fix at all; restructuring the field may leave it global. This closes a hole R1023's predicate already has for a cross-scope `Conditional` site.
4. Its reading is not `Guaranteed`.
5. The `@globalData` ladder would not accept the marker on it, asked of the ladder itself (`globalDataArmOf(site) instanceof Verdict.Arm`, the way `acceptsFanOut` asks `fanOutArmOf`). `@globalData` is the one fix that hands nothing down: a root connection service that reaches no table and names no tenant is offered it by `Rejection.AuthorError.UnroutedServiceCall`, and after taking it the service runs on the default source and its subtree really has no tenant. Its children's errors are then real, so they stay reported now. Asking the ladder rather than mirroring the message's `evidence.isEmpty()` test keeps the predicate on the fixes that work: the message also offers `@globalData` to a connection service whose reach is tenant-scoped, where the ladder refuses it.

Node dispatch (`Query.node`) needs no condition of its own: it reaches no table and is not a `ServiceField`, so condition 2 already leaves it out, and the dispatch facts below judge it. A separate `NODE_RESOLVE` test would be a second list of the same condition.

The predicate is a function of the site alone, and the `@globalData` ladder is not free, while `closeForward` asks the edge predicates repeatedly. Compute the counted sites once, before `foldAncestorContexts` runs, and have both folds read that set.

With those conditions, `armOf` can end such a site in only three verdicts: `ParentRowBound` (the parent-row rung), `Inherited` (either inherited rung), or `Rejected` (the declines, optional-only, unrouted-service or no-binding rejection). Each rejection it can end in offers only fixes that hand a tenant down: a required or optional binding on this field or an ancestor, a `@tenant` marker, a node-typed record, a `@splitQuery` hint toward the parent row, or `@tenantFanOut`. The declines' own fixes all land in a slot (`PruneOnMismatch` and the polymorphic `@nodeId` record: bind the column or take one node type's record; a mistyped `@tenant` scalar: declare it with the key's scalar), or, for a tenant column in an UPDATE's SET clause, remove that field, which leaves the WHERE slots or an Unbound reading whose rejection again offers only tenant fixes. `checkedArmOf`'s generator-bug check, unchanged, already holds every counted site to those three verdicts.

### Why a passing build is unchanged

The argument is a fixed point, and `checkedArmOf` is its enforcer. The change only ever turns an edge from passing a context on into establishing one, so the new `withoutContext` is a subset of the old. Suppose a build passes under the new rule. A counted site whose parent type has no context cannot inherit, because both inherited rungs in `armOf` need `tenantContextOf`; `checkedArmOf` throws on any verdict but the three above, so the site ends `ParentRowBound`, which `parentRowBinding` already counts, or `Rejected`, which a passing build has none of. So every edge whose answer changed leaves a type that already has a context, `closeForward` never follows it from the unbound seeds, and the old and new sets are equal. Multi-site edges do not break this: `edgeEstablishesContext` asks `allMatch` over sites that share one parent type. Cycles do not either, since the fold closes forward from the seeds and a cycle cannot supply its own context.

No existing pipeline, compilation or execution test changing is consistent with this, not evidence for it: those fixtures may not exercise the case. The evidence is the argument above plus `checkedArmOf`, which turns any counted site that ends otherwise into a generator-bug failure in every build that runs the fold.

### The any-path fact, and the one behaviour change

`belowBoundEdge` is the any-path fact the `@tenantFanOut` ladder rejects on ("sits under a tenant-bound ancestor"). `siteDivinesTenant` counts `handsDownAsIfFixed` beside `Guaranteed`, as R1023 already does for `Conditional`, now for every counted site. On its own that would also count a counted site that inherits, and so change a passing build wherever the inherited context came from a source that does not itself seed `belowBoundEdge`. Today exactly one source does not: a type `_entities` routes (`byEntityType`). `Query.node` counts as divining (the `NODE_RESOLVE` rung of `siteDivinesTenant`), a parent-row edge divines, and a fanned context is turned away earlier by the "double-fan" rung. The `_entities` gap is a defect on its own: the `@tenantFanOut` reference rejects the marker below "any ancestor that fixed the tenant", and a routed entity row has a fixed tenant, yet a marked field below it is accepted and fans a single-tenant row's batch across every tenant.

So `foldAncestorContexts` also seeds `belowBoundEdge` from the types a dispatch entry routes, routed now or once fixed (see below). With that seed every type that has a context is below a divining or a fanned edge, a counted site that inherits adds nothing new to `belowBoundEdge`, and `siteDivinesTenant` needs no restriction. The two folds then count the same sites except the fan-out marker, which establishes a context but does not divine one. The deliberate change in passing builds is the one the Goal names: a `@tenantFanOut` field below a routed `_entities` type, directly or through inheriting fields, now rejects "sits under a tenant-bound ancestor". No user-manual change: the reference already says so.

### Dispatch entries

A tenant-scoped entity type whose resolvable key alternative lacks the tenant column, and a tenant-scoped node type whose key lacks it, are rejected in `classifyEntityDispatch` and `classifyNodeDispatch`. The fold reads both as entries with no tenant (`unboundDispatchEntry`), and the node case is wider than one type: `nodeDispatchRoutable` goes false, which turns every tenant-scoped node type into an unbound entry and stops `Query.node` from establishing a context.

Every `NodeType` is also an entity entry: `EntityResolutionBuilder` synthesises a `KeyAlternative.NodeId` alternative from its node key columns. So a node type whose key lacks the tenant column is rejected twice today, once with the node text and once as "federation key alternative #0". `classifyEntityDispatch` stops rejecting a `KeyAlternative.NodeId` alternative itself (the node classifier owns that text) while still treating the type as unrouted.

The dispatch facts are today three loosely coupled collections, `byEntityType`, `nodePositions` (read only for key presence) and the `nodeDispatchRoutable` flag, and this item would otherwise add a fourth. Replace them inside `Fold` with one per-type sealed fact, decided once by the two classifiers:

- `Routed(EntityRepBound)`: the entry routes each representation by its tenant column.
- `RoutedOnceFixed`: tenant-scoped, rejected because a resolvable alternative or the node key lacks the tenant column. The fold reads it as routed, since every fix that keeps the entry resolvable routes it, and the one that does not removes the entry, leaving the type to its field edges.
- `ServedUntenanted`: a global entity or node type, served from the default source.

A type with no resolvable alternative, or no entry at all, has no fact. `unboundDispatchEntry` answers true for `ServedUntenanted` only; the "nothing reaches it and no routable surface enters it" seed skips `Routed` and `RoutedOnceFixed`; the new `belowBoundEdge` seed takes both; `siteEstablishesContext`'s `NODE_RESOLVE` rung returns true; `siteDivinesTenant`'s `NODE_RESOLVE` rung and `armOf`'s `NodeIdBound`/`GlobalRead` choice both ask whether any node type's fact is `Routed` or `RoutedOnceFixed`, one question with one answer. `byEntityType`, which `TenantBindingIndex` exports and the emitted dispatcher stamps rows from, becomes the `Routed` projection of the fact, so `routableDispatchSurface`'s "one fact with the emitter" holds by construction. `nodePositions` and `nodeDispatchRoutable` are removed.

A global entity or node type stays an unbound entry, which is correct, since dispatch really serves it from the default source; `anUntenantedEntityUnderABindingRootDeniesItsChildrenAContext` and its siblings guard that line.

The two rejection details name their fix, since the posture rests on the rejection offering one: the entity detail (a `KeyAlternative.Direct` alternative) adds "Add a field mapped to tenant column '<col>' to the key's fields, or mark the alternative resolvable: false.", and the node detail adds "Add tenant column '<col>' to @node(keyColumns:)." `NoTenantBinding`'s generic closing sentence stays; reworking it for dispatch rejections is not this item.

## Implementation

All in `graphitron/src/main/java/no/sikt/graphitron/rewrite/TenantBindingIndex.java` unless noted.

1. `handsDownAsIfFixed`: replace the `Reading.Conditional` test with conditions 3 to 5 above (keeping 1 and 2). Conditions 2 and 3 share `reachedTables(site.field())`; compute it once. Add `acceptsGlobalData(site)` beside `acceptsFanOut`. Compute the counted sites once before the folds. Rewrite the javadoc: the readings it counts, each exclusion and why, and the fixed-point argument with `checkedArmOf` as its enforcer.
2. The dispatch fact: a private sealed interface in `Fold` and one map of it, filled by `classifyEntityDispatch` and `classifyNodeDispatch`; `byEntityType` derived from it for the `TenantBindingIndex` constructor; `nodePositions` and `nodeDispatchRoutable` removed; `classifyEntityDispatch` leaves the rejection of a `KeyAlternative.NodeId` alternative to the node classifier.
3. `unboundDispatchEntry`, the seeds in `foldAncestorContexts` (both the `withoutContext` seed and the new `belowBoundEdge` seed), both `NODE_RESOLVE` rungs and `armOf`'s `NODE_RESOLVE` choice read the fact. Update the javadoc on `unboundDispatchEntry`, `routableDispatchSurface`, `anyBoundAncestor` and `foldAncestorContexts`.
4. The two rejection details gain their fix sentence.
5. Rewrite the comments that state the narrower rule: `DirectBinding`'s javadoc ("That holds for `Reading.Declined` only, which has not adopted the forward-looking posture..."), the `Reading.Declined` case comment in `armOf`, and the `siteEstablishesContext` / `siteDivinesTenant` comments. The `Conditional` case comment in `armOf` ("Where nothing in the field's own SQL needs a tenant it falls through like an unbound field...") stays true and stays.

No change to `armOf`'s rung order, to any `TenantBinding` arm, to the emitters, or to `graphitron-model`: the `@globalData` condition asks the ladder in this file, not the `UnroutedServiceCall` message.

## Tests

All in `TenantBindingClassificationTest` (unit tier, classification only) unless noted. "Alone" means `rejections()` has exactly the named entries.

Flipped (each today pins a consequential "no ancestor established" error):

- `aCycleAlsoEnteredUnboundRejectsOnEveryMember` becomes a test that `Query.inventories` rejects alone with the no-binding detail and `Film.inventories` and `Inventory.film` inherit.
- `aDispatchVetoOnACycleMemberReachesTheWholeCycle` becomes a test that the `Inventory` node-key rejection, now naming `@node(keyColumns:)`, stands alone (no second "federation key alternative #0" entry on `Inventory`) and `Film.inventories` inherits.

Unchanged, and now witnesses for a boundary (rename or re-comment where the name no longer says why): the global-entity and global-node cases (`aCycleEnteredOnlyThroughAnUntenantedEntityRejectsOnEveryMember`, `anUntenantedEntityUnderABindingRootDeniesItsChildrenAContext`, `anUntenantedNodeTypeReachedThroughQueryNodeDeniesItsChildrenAContext`, `anEntityWithOneResolvableAlternativeStillDeniesItsChildrenAContext`), where the frontier field still rejects; the untenanted-root cases (`anUndecodedIdNamesNoTenantSoTheWrappersChildStillRejects`, `aRootServiceReturningAGlobalTableKeepsTheDefaultSourceAndEstablishesNoContext`, `anOptionalOnlyRootThatNeedsNoTenantStillHandsDownNothing`), where the root is not rejected; and the `@globalData` exclusion (`anUndecodedIdHandedAConnectionRejectsAtTheRootAndTheChildStillRejects`, `anUnmarkedScalarMemberHandedAConnectionRejectsAtTheRoot`), where the payload child keeps rejecting because `@globalData` would be accepted on the root.

New or extended:

- Declined: `aPolymorphicRecordIdAtATableReturningRootRejectsWithItsOwnText` additionally asserts `Film.inventories` inherits and the root's decline is the only error.
- Unbound connection service with tenant evidence (the `@globalData` ladder refuses it): the payload child inherits and the root rejects alone.
- Frontier below an untenanted root: `aNestedSplitQueryFieldUnderAGlobalParentWithNoTenantKeyRejects` additionally asserts `Film.inventories` inherits and `EndorsementAbout.film` rejects alone.
- Entity: the Goal's `Film @key(fields: "title")` fixture; the entity rejection, naming its fix, stands alone and `Film.inventories` inherits.
- Node: two tenant-scoped node types, one with a key lacking `film_id`, reached through `Query.node`; the bad type's rejection stands alone and the good type's tenant-scoped child inherits. With every tenant-scoped node type's key lacking `film_id`, `Query.node` classifies `NodeIdBound`, the arm the fold's answer implies.
- Fan-out: a `@tenantFanOut` field below a rejected Unbound root rejects "sits under a tenant-bound ancestor" beside the root's error, mirroring R1023's `markerUnderAnUnmarkedOptionalOnlyAncestorRejectsBesideIt`.
- The `_entities` seed (in `TenantFanOutClassificationTest`): a tenant-scoped entity type with a key carrying `film_id`, reached only through `_entities`, with a `@tenantFanOut` field on it, and a second fixture with the marker one inheriting field further down; both reject "sits under a tenant-bound ancestor". Both are accepted today, and both show the one behaviour change in a passing build. A global entity with a `@tenantFanOut` field below it stays accepted.
- Cross-scope: a field touching a tenant and a global table in one statement with a tenant-scoped child below it; the child still rejects.

R1023's tests (`aRejectedOptionalOnlyRootLeavesItsSubtreeInheritingFromTheOtherRoots`, `anOptionalOnlyConnectionServiceRejectsAloneAndItsSubtreeInherits`, and the two in `TenantFanOutClassificationTest`) stay green unchanged. Any other test asserting a rejection count in a failing fixture may drop by the consequential errors this removes; each such edit is that drop and nothing else. A pipeline, compilation or execution test that changes outside the `_entities` fan-out case contradicts the fixed-point argument and is a finding, not an edit to make.

After landing, a sis rebuild should show the `FeideBruker` rejection alone (the bisect found no optional-only roots of its own), and none of the consequential errors below `PersonProfil`. That is reported back to the sis thread, not a Done gate.

## Other solutions we've considered

- **Count every rejected site that needs a tenant, `@globalData` included.** Simpler predicate, and the author who takes `@globalData` sees the children's errors one build later. Rejected because it breaks the rule R1023 settled on, that the subtree is judged as every offered fix leaves it, and those children's errors are not consequential under that fix: they are what the author must fix next.
- **Suppress consequential errors after the fact.** Classify as today, then drop every "no ancestor established" error whose missing context traces to a rejected site. It needs a provenance trail the fold does not have, and leaves the arms below a rejected root as `null` rather than the arms they will have, so the `@tenantFanOut` ladder and the frontier fields would still be judged against a context that will not exist.
- **Restrict `siteDivinesTenant` to counted sites whose parent type has no context,** instead of seeding `belowBoundEdge` from `_entities`. It also keeps passing builds unchanged, but it does so by preserving the `_entities` gap the `@tenantFanOut` reference contradicts, and it narrows R1023's `Conditional` case to match: a test pinning the accepted fan-out would lock the gap in as intended behaviour. The seed closes the gap and lets the two folds count the same sites.
- **Cover classification-rejected fields here, such as the `@notGenerated` `Node` root in the evidence.** Rejected because the rule below rests on the rejected site's model (its reach, reading and offered fixes), and such a field has no site and no model; judging its subtree "as fixed" would have to be argued from the SDL return type alone, a different argument with its own masking risk. It is R1031, and sis has already cleared its own instance by removing the directive.
- **Leave the dispatch entries out, as the Backlog body left open.** The frontier rule alone would collapse sis's cascade to two errors. Rejected because one rejected node key still voids `Query.node`'s context for every other node type, a much wider cascade, and the argument that every fix routes the entry is as strong as it is for fields.

## Implementation notes

Landed as the plan describes, in `TenantBindingIndex` and the two classification test classes. What the reviewer should check beyond the plan:

- **Tests, by the plan's list.** Flipped: `aCycleAlsoEnteredThroughARejectedUnboundRootRejectsOnlyAtThatRoot` and `aDispatchVetoOnACycleMemberRejectsOnceAndTheCycleInherits`. Extended: `aPolymorphicRecordIdAtATableReturningRootRejectsWithItsOwnText` (Declined) and `aNestedSplitQueryFieldUnderAGlobalParentWithNoTenantKeyRejects` (frontier). New in `TenantBindingClassificationTest`: `anUnroutedConnectionServiceWithTenantEvidenceRejectsAloneAndItsPayloadInherits` (needs the new `TenantServiceStub.retitleFilmRated`), `anEntityKeyMissingTheTenantColumnRejectsAloneNamingItsFix`, `aNodeKeyMissingTheTenantColumnRejectsAloneAndOtherNodeTypesKeepTheirContext`, `nodeDispatchOverOnlyUnroutableTenantScopedTypesIsStillNodeIdBound`, `aFanOutMarkerBelowARejectedUnboundRootRejectsBesideIt` and `aCrossScopeRootHandsItsSubtreeNothing`. New in `TenantFanOutClassificationTest`: `markerOnARoutedEntityRejects`, `markerBelowAnInheritingFieldOfARoutedEntityRejects` and `markerOnAGlobalEntityStaysAccepted`. Run against the pre-change fold, every flipped, extended or new test fails except the two boundary witnesses (cross-scope, global entity), which pass both ways by design. The `@globalData` boundary tests got a comment saying why their child still rejects; no other existing test changed.
- **A node key alternative the node classifier did not reject.** `classifyEntityDispatch` skips the rejection of a `KeyAlternative.NodeId` alternative only when `classifyNodeDispatch` already recorded `RoutedOnceFixed` for the type. The two read different column lists when a `NodeType` has empty `nodeKeyColumns` (the node classifier falls back to the primary key, the synthesised alternative does not), and without the guard such a type could end `RoutedOnceFixed` with no rejection at all, read as routed in a passing build. In that case the entity text is kept, as today, with the node fix sentence. So every `RoutedOnceFixed` comes with a rejection.
- **One passing-build edge outside the named exception.** As the plan says, a type whose entity entry has no resolvable alternative has no dispatch fact, and `Query.node`'s `NodeIdBound`/`GlobalRead` choice reads the facts. So a build whose only tenant-scoped node types all opt out with `@key(fields: "id", resolvable: false)` now classifies `Query.node` `GlobalRead` where it was `NodeIdBound`. Node dispatch serves none of those types, so `GlobalRead` is the correct arm, but it is a change in a passing build that the Goal does not name. No test in the reactor exercises it.
- **A decline added since the Spec.** R1029 landed a decline for a `@nodeId(typeName:)` decode whose target is a `@table` type but not a node type, with a key carrying the tenant column. Its two fixes, making the target a node type or binding through a field mapping to the tenant column, both land in a slot, so the predicate counts it on the same argument as the declines the plan lists.
- **Counted sites** are computed once in `run`, into an identity set, after dispatch classification and before `foldAncestorContexts`. `checkedArmOf` reads the same set, so the enforcer and the folds cannot disagree on which sites they judge.
- **The routed-entry seed** for `belowBoundEdge` iterates the walk's domain, like the `withoutContext` seed beside it.
- After this lands, ask sis for a rebuild: the `FeideBruker` rejection should stand alone, with none of the errors below `PersonProfil`.

## Reviewer findings

### Round 1 (2026-10-14, Spec -> Ready, reviewer session 01DQhqiFocMNbgovLjCfvCaS)

Verdict: request revisions. One finding, on question one. The rest of the plan checks out against the tree: every symbol and test it names exists as named, the fixed-point argument holds over `armOf`'s rungs as they stand, and the dispatch fact replaces three collections with one rather than adding a fourth. Question two passes as far as the plan reaches.

**Finding 1 (question one: the goal and the plan disagree on the widest root kind).** The Evidence section names a rejected `Node`-returning root (`Query.sisNode(id: ID!): Node @notGenerated`, about 680 errors) as "the widest blast radius this item covers" and says "The Spec should treat it alongside the argument and federation-key roots." Nothing in The rule, Implementation or Tests does, and the plan as written cannot reach it. That field is rejected by classification (`FieldBuilder.classifyFieldInner` returns an `UnclassifiedField` for `@notGenerated`), and `UnclassifiedField` is not an `OutputField`, so `Fold.sitesOf` records no site at that coordinate. `buildEdges` still records the edge from the SDL, and `edgeEstablishesContext` answers false for an edge with no sites (`!edgeSites.isEmpty() && ...`). So `Query` seeds `withoutContext` through that edge into every `Node` implementor, and `handsDownAsIfFixed`, being a predicate over sites, is never asked. The same holds for any classification-rejected field on a path, not only `@notGenerated` ones.

What would satisfy it: either the plan takes a position on a site-less edge (what a classification-rejected field hands its subtree, with the argument for why that is right under every fix the rejection offers, the change in `edgeEstablishesContext` / `edgeDivinesTenant`, and a test for the `Node`-returning case), or the Evidence and Goal stop claiming the case and it moves to a Backlog item of its own, so the Done gate is not judged against a promise the plan never made. Which of the two is the author's call.

*Author response:* scoped out. The Evidence paragraph now says this item does not cover a classification-rejected field and why (no site, so no reach, reading or offered fix for the posture to rest on), and points at R1031, filed as Backlog for it. A bullet under Other solutions records the alternative. The Goal never named the case, so it is unchanged; the plan body is unchanged.
