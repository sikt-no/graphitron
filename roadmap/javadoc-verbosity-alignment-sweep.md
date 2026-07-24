---
id: R524
title: "Trim verbose javadoc and align comments with the terse-and-pinned conventions"
status: Spec
bucket: cleanup
priority: 7
theme: docs
depends-on: []
created: 2026-07-24
last-updated: 2026-07-24
---

# Trim verbose javadoc and align comments with the terse-and-pinned conventions

Several main-source classes carry long narrative javadoc and comment blocks that were written while the design was still moving: they restate what the code already shows, walk through implementation history, or argue for decisions in paragraphs of unpinned prose. The R483 drift audit corrected claims that had become *wrong*, but its unit of work was accuracy, not length; a comment can be fully accurate today and still be the kind of prose that diverges as the code matures, because nothing mechanical breaks when it goes stale. The conventions have since firmed up (CLAUDE.md "Javadoc conventions": prefer terse over verbose, name live things via `{@link}` so the R492 reference gate checks them, no transient citations per the R482/R484 guards), and the existing comment stock predates them.

## Census (2026-07-24)

Measured over the `GuardScope.IN_SCOPE_MODULES` hand-authored trees (`src/main/java` + `src/test/java`): 1,098 files, 236k lines, of which 58.9k (24.9%) are comment lines; 43 contiguous comment blocks of 40+ lines and a further 242 of 25-39 lines. Top files by comment volume: `FieldBuilder` (2,896 comment lines), `TypeFetcherGenerator` (2,528), `BuildContext` (1,284, 41% of the file), `MultiTablePolymorphicEmitter` (781), `TypeBuilder` (748), `JooqCatalog` (713, 44%), `ChildField` (624, 46%), `GraphitronSchemaBuilder` (559), with `MutationField` (49%), `GraphitronType` (57%), and `ArgCallEmitter` (47%) leading by share. Largest single blocks: `ConnectionRuntimeClassGenerator` (118 lines), `MutationField.MutationBulkDmlRecordField` (60), `GraphitronTransactionProviderGenerator` (56), `JoinStep` (54).

Spot-checking the largest blocks shows they are not dead narration: they carry real contracts, several already pinned to named tests. But they are shot through with the drift-prone material the conventions ban. **The verdict unit is therefore the claim, not the block**; block length alone is a symptom, never the defect.

## Trimming rubric

Each claim inside a comment or javadoc region gets exactly one verdict:

1. **Transition narration** ("was carved off onto X", "is now structurally pinned by", "no longer", "the latter used to"): delete, or restate as a present-tense fact if the fact itself is load-bearing. History lives in `roadmap/changelog.md`.
2. **Future-work promises** ("a future UPSERT lift adds...", "at that point this relaxes to..."): delete. The roadmap owns futures; javadoc describing an unbuilt design is unpinned prose that rots the moment the plan changes (and per the conventions the item cannot even be cited).
3. **Code restatement** (prose quoting the guard conditions, enum arms, or call chain the reader can see below it): delete, or reduce to an `{@link}` where it names a non-local symbol.
4. **Load-bearing claims** (invariants, fail-closed contracts, non-obvious rationale, cross-module coupling a reader cannot recover from the code): keep, terse. If unpinned, pin it: `{@link}` for symbol claims (checked by the reference gate), a named test for behavioral claims, a published-docs pointer for design rationale. A claim that cannot be cleanly pinned or deleted is routed to a follow-on item, never rewritten into fresh confident prose (the R483 routing discipline).

Formatting damage rides along where touched (e.g. the flush-left javadoc lines inside `MutationField.MutationBulkDmlRecordField`).

## Mechanics

Reuse the R483 shape, which this repo has already validated: a batched reader fan-out over the census-ranked worklist, each batch's edits passed through an adversarial verify stage prompted to *restore* deletions, i.e. to argue that a deleted claim was load-bearing and unrecoverable from the code (R483's verify stage caught exactly one wrongful deletion out of 90 edits; this sweep deletes far more aggressively, so the stage matters more here). Start from the top of the census and work down; the tail (files under ~100 comment lines with no 25+ line block) is explicitly allowed to go untouched, since the cost of sweeping it exceeds the drift risk it carries.

Acceptance:

- Full reactor green under `mvn install -Plocal-db`, with the `{@link}` reference gate and `RoadmapReferenceGuardTest` active (both guards constrain the rewrite direction).
- Edits are comment/javadoc-only, plus any test added to pin a kept behavioral claim.
- A re-run of the census showing the 40+ line block count and total comment share materially reduced, reported in the item's Done summary with before/after numbers (no hard numeric gate; the rubric, not a quota, decides each claim).
- Follow-ons filed for every claim routed out rather than resolved.

The sweep is re-runnable; if the methodology proves cheap, a recurring cadence can be considered as a follow-on.

## Out of scope

- Adding missing orientation javadoc and `package-info.java` files: [`source-orientation-javadocs.md`](source-orientation-javadocs.md). The two items are complementary and can share a Spec-time file census.
- Generated-output javadoc and hygiene: [`generated-output-hygiene-sweep.md`](generated-output-hygiene-sweep.md).
- Fixing individually tracked drifted claims (R494, R496, R498); this sweep must not rewrite the prose those items pin.
