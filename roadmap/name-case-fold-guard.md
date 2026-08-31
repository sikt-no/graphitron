---
id: R890
title: "A structural guard gives case-folded name comparison one legitimate home"
status: Backlog
bucket: testing
priority: 3
theme: diagnostics
depends-on: [launcher-method-census-folds-case]
created: 2026-08-31
last-updated: 2026-08-31
---

# A structural guard gives case-folded name comparison one legitimate home

The launcher-method census case-folded its uniqueness key for years on a rationale borrowed by analogy from the projection address census, where the fold is justified (a generated class name becomes a file name, and case-insensitive filesystems collide) but where method names never were. The only thing holding the line against further spread was a review-only javadoc instruction ("Do not add either by analogy" in `RoutineWriteRelation`), and it is precisely what failed: a review-only label is an invitation. The tree's existing pattern for this failure mode is `TableNameComparisonCaseGuardTest`: one legitimate home for a case comparison, a structural scan forbidding the shape elsewhere, and touching the guard's assertion as the deliberate review point. A sibling guard should give generated-name case folding its one legitimate home (the projection address census, whose filesystem rationale `Rejection.InvalidSchema.CaseFoldCollision`'s javadoc carries) and fail the build when a name census elsewhere folds its key by analogy. Depends on the launcher census dropping its fold first, or the guard is born with two homes.
