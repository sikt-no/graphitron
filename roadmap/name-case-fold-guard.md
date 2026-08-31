---
id: R890
title: "A structural guard gives case-folded name comparison one legitimate home"
status: Spec
bucket: testing
priority: 3
theme: diagnostics
depends-on: [launcher-method-census-folds-case]
created: 2026-08-31
last-updated: 2026-08-31
---

# A structural guard gives case-folded name comparison one legitimate home

The launcher-method census case-folded its uniqueness key for years on a rationale borrowed by analogy from the projection address census, where the fold is justified (a generated class name becomes a file name, and case-insensitive filesystems collide) but where method names never were. The only thing holding the line against further spread was a review-only javadoc instruction ("Do not add either by analogy" in `RoutineWriteRelation`), and it is precisely what failed: a review-only label is an invitation. The tree's existing pattern for this failure mode is `TableNameComparisonCaseGuardTest`: one legitimate home for a case comparison, a structural scan forbidding the shape elsewhere, and touching the guard's assertion as the deliberate review point. A sibling guard should give generated-name case folding its one legitimate home (the projection address census, whose filesystem rationale `Rejection.InvalidSchema.CaseFoldCollision`'s javadoc carries) and fail the build when a name census elsewhere folds its key by analogy. Depends on the launcher census dropping its fold first, or the guard is born with two homes.

## The invariant, at altitude

A case fold is minted only where two namespaces meet; the fact model states this rule for the store side, and this item is its plan-side half. The one boundary the generator's own names cross is Java identifier to file stem: a class address becomes a file path, and case-insensitive filesystems (APFS, NTFS) collapse case there. Two populations cross that same boundary today, each with its own gate: authored GraphQL type names, folded by `GraphitronSchemaBuilder.rejectCaseInsensitiveTypeCollisions` into the typed `Rejection.InvalidSchema.CaseFoldCollision`, and minted projection unit addresses, folded by the projection address census. Method names never cross it, so a folded method-name comparison is not a collision check; it is the rejection of pairs whose emitted names are distinct, which R889 establishes is a bug. The guard turns that argument into a build failure:

- **Minted method names: zero homes.** No statement may fold or case-insensitively compare a minted method name.
- **Minted unit addresses: one home.** The fold is derived once, on the minted-name type itself, and census keys consume the derived value.

The principles-architect review of the first draft reshaped this item: the draft excluded the census's two *files* from the scan, defending a location, where the precedent guard (`TableNameComparisonCaseGuardTest`) defends a *type* (`TableRef.sameTable` is the canonical predicate and the guard excludes its body). The plan below lifts the fold onto the minted-name type first, so this guard gets the same footing.

## Plan

1. **The type-level home first: `UnitRef.fileStem()`.** One derived accessor on the minted-name type, the unit's address as a case-insensitive filesystem sees it (the folded `fqcn()`, `Locale.ROOT`). Its javadoc is where the filesystem rationale lives, with a resolvable `{@link}` to `Rejection.InvalidSchema.CaseFoldCollision`. This is the same single-mint rule the tree already enforces one derivation up: `PackageImportDirectionTest.unitRefsAreMintedOnlyByThePlansNamingVocabulary` pins that unit refs are minted only by `GeneratedUnits`; "the fold of a unit address is computed only on `UnitRef`" is that rule applied to the derived value.

2. **Re-key the projection address census on it.** Three sites spell their own fold today and stop doing so: `ProjectionRelation`'s constructor backstop and `ProjectionCommands.AddressCensus.add` fold `unit().fqcn()`, and the validator mirror's `record()` folds an intermediate `simpleName` string, a third spelling of the same key that quietly disagrees with the other two about what it folds (simple name versus full address). All three consume `fileStem()`. After this step no census spells a fold; deleting the accessor is a compile error at every census, which is a stronger gate than any roster assertion (this is why the first draft's exclusion-rot assertion is gone: the type lift makes it unnecessary).

3. **The guard.** `MintedNameCaseFoldGuardTest`, a `@UnitTier` structural scan in test package `no.sikt.graphitron.plan`, beside the censuses it polices; shape cloned from `TableNameComparisonCaseGuardTest` (recursive walk, pattern list, exclusion, vacuity assertion, offending-sites failure message), with the scan root single-sourced through `GuardScope.locateRepoRoot()` like the rest of the guard family, resolving the whole module main tree (`graphitron/src/main/java`): the precedent's `rewrite` subtree would miss the `plan` package where every census lives. One excluded file: `command/UnitRef.java`, the accessor's own body. A type, like `TableRef`, not a place.

4. **The forbidden shape**, spelling-closed and stated honestly in the guard's javadoc, like the precedent. Within one statement, a fold spelling (`.toLowerCase(`, `.toUpperCase(`, `.equalsIgnoreCase(`, `.compareToIgnoreCase(`) co-occurring with a minted-name anchor: `.unit()`, `UnitRef`, `UnitMethodRef`, or `.fqcn()`. Statement-bounded by `[^;]*`, which crosses newlines but never a `;`: the launcher census's key build spanned a line break (`(… + "#" + row.unit().methodName())` newline `.toLowerCase(ROOT)`), and the shapes that must *not* trip, camelling a first character out of an accessor landed in a local variable (`render/TableLocal.java`, `render/PathFragments.java`), sit behind a semicolon. The anchor set is deliberate: the first draft anchored on `.methodName()` and `.simpleName()`, but `.methodName()` is also spelled by the authored-name refs (`JoinCondition`, `AuthoredMethodRef`), a namespace where a fold can be legitimate, and `.simpleName()` is javapoet vocabulary across ~33 main-source files, so either anchor makes the guard fire a message about name censuses at code that is doing something else, and a false positive with a wrong message invites exclusion-list widening. `.fqcn()` stays in the set although a handful of types beyond `UnitRef` spell it (`EnumMappingResolver.EnumValidation.Valid`, `RecompileSet`'s nodes): each is a Java class address, the file-stem rule covers all of them, so a hit there is review-worthy under the same rule rather than noise. A method-name fold that reaches through neither `.unit()` nor a ref type in the statement escapes, the same intermediate-variable trade the precedent makes; the guard is a tripwire against analogy-borrowing, not a data-flow analysis.

5. **Pattern-sanity fixtures.** A second test method in the same class runs the same scan function over embedded text-block snippets: each forbidden orientation trips (including the newline-spanning launcher key build and `ProjectionRelation`'s pre-lift fold, both lifted verbatim from the pre-fix tree), and the legitimate shapes do not (separate-statement camelling; a single-statement javapoet `simpleName()` camelling, pinning the dropped anchor). This pins the statement-boundedness and the anchor set, the parts most likely to rot, without waiting for a real regression to probe them.

6. **Javadoc.** `RoutineWriteRelation`'s prohibition sentence (post-R889 wording) gains the guard as its enforcement, named in prose as `{@code MintedNameCaseFoldGuardTest}` (a `{@link}` cannot resolve a test symbol from main javadoc, and the reference gate would fail it). The `AddressCensus` javadoc in `ProjectionCommands` and `ProjectionRelation`'s class javadoc point their key at `{@link UnitRef#fileStem()}`, where the rationale now lives. The guard's own javadoc states the rule at the altitude of the invariant section above, and carries its retirement clock in one sentence: the guard polices the transitional plan surface and retires when the address census re-sources onto the store, where the fold rule already has its own statement and enforcers.

7. **Quarantine, not endorsement.** The census's author-facing collision text still splices a folded simple name that names no generated class (`AddressCollision.foldedSimpleName`, and `byFoldedName`, an identity function whose comment apologises for it), the projection twin of the defect R889 repairs on the launcher side. That repair is R891 (`projection-collision-typed-arm`), filed with this spec; the guard javadoc's mention of the census words the exclusion accordingly.

## What the scan deliberately does not cover

Stated in the guard's javadoc, so the honest-scope precedent carries over:

- **Raw-string folds.** `GraphitronSchemaBuilder.rejectCaseInsensitiveTypeCollisions` folds authored GraphQL type names into file stems. That is the *same* boundary and the same rule (Java identifier to file stem), over the authored population rather than the minted one, and it already has its own gate, the typed `CaseFoldCollision` rejection. It is out of *scan* scope for the mechanical reason only: it folds a raw registry key (`entry.getKey()`), no minted-name anchor appears in the statement, so the scan never sees it. The guard javadoc says exactly this; the first draft called it "a different namespace boundary", which the code contradicts.
- **Intermediate-variable evasion.** An anchor landed in a local and folded in a later statement escapes the scan, the same trade the precedent guard makes; the guard is a tripwire against the analogy-borrowing failure mode, not a data-flow analysis.
- **Other spellings.** `String.CASE_INSENSITIVE_ORDER`, hand-rolled char-wise folds. Closed list, stated.

## Verification done while writing this spec

The fold-with-minted-anchor co-occurrence sites in today's main tree are exactly four: the two projection-census sites step 2 re-keys (`ProjectionRelation` constructor, `ProjectionCommands.AddressCensus.add`) and the two launcher folds R889 removes (`LauncherRelation` constructor, `LauncherCommands` ~line 1160). The `record()` fold in `ProjectionCommands` (~line 573) folds an intermediate `String`, invisible to the scan, and is retired by step 2 regardless. The `simpleName()` uses in `render/` (`TableLocal`, `PathFragments`) are first-character camelling; they stop being a false-positive risk once `.simpleName()` leaves the anchor set, and their separate-statement shape is pinned in the fixtures anyway. No `equalsIgnoreCase`/`compareToIgnoreCase` co-occurs with any minted-name anchor today. Accessor-spelling census: `.fqcn()` is spelled by `UnitRef` plus a handful of other class-address types (`EnumMappingResolver`, `RecompileSet`), `.methodName()` also by the authored-name refs `JoinCondition` and `AuthoredMethodRef`, `.simpleName()` by javapoet `ClassName` across ~33 main-source files; the anchor-set reasoning in step 4 follows from this census.

## Sequencing

Blocked on R889 landing (`depends-on` in front-matter): implemented first, the guard is born with the launcher census as a second home and cannot state its invariant. Do not move to In Progress before R889 is Done. Note for the implementing session: steps 1-2 touch main sources, so this item's verification build is the full `mvn install -Plocal-db`, not the roadmap-only scope.

## Retired vocabulary

- "Do not add either by analogy" as a review-only enforcement: the sentence in `RoutineWriteRelation`'s javadoc is reworded to cite the guard, so no prohibition in census javadoc stands without a build gate behind it.
- The open-coded projection census key spellings: `unit().fqcn().toLowerCase(…)` at the census sites and the mirror's folded-`simpleName` intermediate (all replaced by `UnitRef.fileStem()`).
