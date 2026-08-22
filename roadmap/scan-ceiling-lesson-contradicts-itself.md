---
id: R802
title: "The scan-count ceiling javadoc contradicts itself about what a ceiling catches"
status: Backlog
bucket: docs
priority: 4
theme: lsp
depends-on: []
created: 2026-08-22
last-updated: 2026-08-22
---

# The scan-count ceiling javadoc contradicts itself about what a ceiling catches

`SurfaceScanCountTest`'s class javadoc states two things about its own ceilings
that cannot both be true. One paragraph says "Every ceiling here was set from both
shapes measured on *this* fixture, and each was confirmed to fail with the defect
reinstated", and the next says "The ceilings alone would not have caught the defect
that prompted them" and that the excess is one "no ceiling anybody would defend".
A contributor adding a ceiling reads both and cannot tell how strong the
instrument is meant to be.

Neither sentence is quite right, and the accurate version was already written
once. It was the corrected step 4 of the item that built this test, R795 in
`roadmap/changelog.md`: both declaration ceilings *do* catch the reverted arm at
this fixture, the type-declaration read going 202 to 323 against a ceiling of 260
and the member read 808 to 929 against 900, and what makes them weak is only that
they sit close to the measured cost, so a regression half this size or the same
regression under a ceiling with generous headroom passes. That plan file was
deleted when the item reached Done, which is what makes this worth its own entry
rather than a note: the correction left the tree with the item, and only the
overstated version survived in the javadoc.

The "each was confirmed to fail" half is also too broad in a second way. Only
`DeclarationDefinitions` and `DeclarationHovers` read `DeclarationFacts`, so of the
seven ceilings only the two declaration ones and hover's can move when the redirect
arm is reverted; the completion, code-action, inlay and diagnostics ceilings are
untouched by that defect and were never mutation-tested against it. Because the
ceilings are asserted in sequence rather than softly, the first failure also masks
the rest, so the test as written cannot demonstrate the claim even for the
surfaces where it holds.

The operative lesson in that paragraph is right and worth keeping: a ceiling is
finished when it has been seen to fail, not when it passes. What needs fixing is
the provenance claim wrapped around it, and the stale paragraph beneath it.
