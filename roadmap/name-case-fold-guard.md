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

A case fold is minted only where two namespaces meet. The generator has exactly one such boundary for the names it mints onto units: a generated class address becomes a file name, and case-insensitive filesystems (APFS, NTFS) collapse case there. Method names never cross that boundary, so a folded method-name comparison is never a collision check; it is the rejection of pairs whose emitted names are distinct, which R889 establishes is a bug. The guard turns that argument into a build failure:

- **Minted method names: zero homes.** No statement may fold or case-insensitively compare a `.methodName()`.
- **Minted unit addresses: one home.** A fold of `.fqcn()` / `.simpleName()` is legal only inside the projection address census.

## Plan

1. **The guard.** `MintedNameCaseFoldGuardTest`, a `@UnitTier` structural scan in test package `no.sikt.graphitron.plan`, beside the censuses it polices; shape cloned from `TableNameComparisonCaseGuardTest` (recursive walk, pattern list, exclusion, vacuity assertion, offending-sites failure message). Scan root is `src/main/java/no/sikt/graphitron`, the whole module main tree: the precedent's `rewrite` subtree would miss the `plan` package where every census lives.

2. **The forbidden shape**, spelling-closed and stated honestly in the guard's javadoc, like the precedent. Within one statement, a minted-name accessor (`.fqcn()`, `.methodName()`, `.simpleName()`) co-occurring with a fold spelling (`.toLowerCase(`, `.toUpperCase(`, `.equalsIgnoreCase(`, `.compareToIgnoreCase(`), both operand orientations for the comparison spellings. Statement-bounded by `[^;]*`, which crosses newlines but never a `;`: the launcher census's key build spanned a line break (`(… + "#" + row.unit().methodName())` newline `.toLowerCase(ROOT)`), and the shape that must *not* trip, camelling a first character out of an accessor landed in a local variable (`render/TableLocal.java`, `render/PathFragments.java`), always sits behind a semicolon.

3. **The one excluded home: the projection address census.** Lexically two files, `plan/ProjectionRelation.java` (the relation-constructor backstop) and `plan/ProjectionCommands.java` (the producer's `AddressCensus` plus the validator mirror `addressCollisions`/`record`), because a census in this tree structurally spans producer, validator mirror, and relation backstop; R889 describes the same three-site agreement for the launcher census. The exclusion is per census, not per file, and the guard's javadoc says so: if the census's stringly folded-key shape is ever collapsed to fewer sites, the exclusion list shrinks with it.

4. **Exclusion-rot assertion.** Beyond the precedent: the guard also asserts each excluded file still *contains* the fold shape. If the projection census ever drops its fold, the stale exclusion fails the build and gets removed, rather than standing as a silent invitation. (The precedent guard cannot notice `TableRef` losing its raw comparison; if this assertion proves its worth, retrofitting one there is separate Backlog material.)

5. **Pattern-sanity fixtures.** A second test method in the same class runs the same scan function over embedded text-block snippets: each forbidden orientation trips (including the newline-spanning launcher key build, lifted verbatim from the pre-R889 tree), and the legitimate separate-statement camelling shape does not. This pins the statement-boundedness, the regex bit most likely to rot, without waiting for a real regression to probe it.

6. **Javadoc.** `RoutineWriteRelation`'s prohibition sentence (post-R889 wording) gains the guard as its enforcement, named in prose as `{@code MintedNameCaseFoldGuardTest}` (a `{@link}` cannot resolve a test symbol from main javadoc, and the reference gate would fail it). The `AddressCensus` javadoc in `ProjectionCommands` and `ProjectionRelation`'s class javadoc name themselves the guard's one legitimate home, pointing the filesystem rationale at `Rejection.InvalidSchema.CaseFoldCollision` with a resolvable `{@link}`.

## What the scan deliberately does not cover

Stated in the guard's javadoc, so the honest-scope precedent carries over:

- **Raw-string folds.** `GraphitronSchemaBuilder.rejectCaseInsensitiveTypeCollisions` folds authored GraphQL type names into file stems. Same filesystem rationale, but a different namespace boundary (authored names, not minted ones), guarded by its own typed rejection (`CaseFoldCollision`) rather than a census throw, and lexically fold-of-a-raw-string (`entry.getKey().toLowerCase(ROOT)`), so the accessor-anchored scan never sees it. It is out of scope, not a second home.
- **Intermediate-variable evasion.** An accessor landed in a local and folded in a later statement escapes the scan, the same trade the precedent guard makes; the guard is a tripwire against the analogy-borrowing failure mode, not a data-flow analysis.
- **Other spellings.** `String.CASE_INSENSITIVE_ORDER`, hand-rolled char-wise folds. Closed list, stated.

## Verification done while writing this spec

The accessor-fold co-occurrence sites in today's main tree are exactly four: the two projection-census homes (`ProjectionRelation` constructor, `ProjectionCommands.AddressCensus.add`) and the two launcher folds R889 removes (`LauncherRelation` constructor, `LauncherCommands` ~line 1160). The `record()` fold in `ProjectionCommands` (line ~573) folds a plain `String` parameter, invisible to the accessor-anchored pattern, but sits in an excluded file regardless. The `simpleName()` uses in `render/` (`TableLocal`, `PathFragments`) are first-character camelling behind a semicolon boundary and do not trip the statement-bounded pattern. No `equalsIgnoreCase`/`compareToIgnoreCase` co-occurs with any minted-name accessor today.

## Sequencing

Blocked on R889 landing (`depends-on` in front-matter): implemented first, the guard is born with the launcher census as a second home and cannot state its invariant. Do not move to In Progress before R889 is Done.

## Retired vocabulary

- "Do not add either by analogy" as a review-only enforcement: the sentence in `RoutineWriteRelation`'s javadoc is reworded to cite the guard, so no prohibition in census javadoc stands without a build gate behind it.
