---
id: R1156
title: "A delivery is announced when it lands, by its commit"
status: Backlog
bucket: process
priority: 2
theme: tooling
depends-on: []
created: 2026-11-05
last-updated: 2026-11-05
---

# A delivery is announced when it lands, by its commit

## Goal

A consumer learns of a change to what graphitron accepts, refuses or generates when that change reaches trunk, not when the item carrying it reaches Done, and can ask what changed between the release candidate they run and the one they are moving to, breaking changes first. Today `roadmap/workflow.adoc` describes a changelog entry only as part of the Done verdict, so a long item that ships behaviour in steps announces none of it until it closes.

## Problem

Behaviour reaches consumers long before Done. R876's "For the changelog at Done" section holds changes that landed on trunk, which release candidates are cut from, weeks before the item can close: `@table` naming a table-valued function is refused; `@routine` loses `columnMapping`; the MCP schema tool's `demand` slot is removed; `graphitron:capture` no longer fails over a schema that parses; a reactor sibling is read from its own output; `graphitron:dev` compiles its own module first. A further breaking change, `@mutation(typeName: UPSERT)` beside `@service` refused (`6733135`), is not in that section at all. A consumer moving from one release candidate to the next has no record of any of them.

Writing a changelog entry with every such delivery (this item as first filed) answers that, but makes `changelog.md` a file every behaviour-changing push prepends to, which conflicts across concurrent sessions, and needs entry shapes the tool must tell apart: Done, delivery, revert.

## Plan

The commit is the announcement. Commits reaching trunk follow [Conventional Commits 1.0.0](https://www.conventionalcommits.org/en/v1.0.0/), the item id as the scope, and a release's notes are generated from the commits between two release-candidate tags.

* **Subject.** `<type>(<scope>)[!]: <description>`. The description keeps the house style, a sentence stating what is now true. The scope is the item id (`R876`); a commit no item covers takes the module or area (`roadmap`, `docs`). `!` marks a breaking change, and a `BREAKING CHANGE:` footer says what a consumer must change.
* **Types.** `feat` (an author or consumer can do something new), `fix` (behaviour corrected), `refactor`, `perf`, `test`, `docs`, `build`, `ci`, `chore`, and `revert`, whose body names the hash it reverts. Release notes list `feat`, `fix`, `perf` and `revert`, and every `!` whatever its type; the rest are left out.
* **Not for trunk.** `wip`, `draft` and `spike` stay as types that never reach trunk, so the not-for-trunk check in `CLAUDE.md` and the publish skill reads the same prefixes. A harvest replaces them with the type the change is.
* **Who chooses the type.** Whoever puts the commit on trunk: the harvester for harvested work, the author for a direct push.
* **Release notes.** A `roadmap-tool` command, `release-notes <from-tag> <to-tag>`, reads `git log` between two tags and renders breaking changes first, then features, fixes and reverts, each with its scope and short hash. No new dependency.
* **The changelog.** `changelog.md` stays Done-only, the narrative of why an item happened; a Done entry may point at the release notes the item's deliveries appeared in rather than repeating them. An item's "For the changelog at Done" section stays a convention for that narrative and no longer carries the burden of announcing deliveries; R876's opening sentence restating the Done-only rule changes to say so.
* **Gate.** A check in `roadmap-tool`, run in the build, fails on a commit between a fixed cut-over commit and `HEAD` whose subject is not of this shape. History before the cut-over is not rewritten; the first release notes start at the first release candidate after it.

Edit sites: `CLAUDE.md` (Git Workflow: the subject shape, the not-for-trunk types); `roadmap/workflow.adoc` (Publishing, and the In Review -> Done step's changelog sentence); the publish skill's wip check; the roadmap skill's In Review -> Done step; the harvest practice for co-author sessions; R876's "For the changelog at Done" opening; `roadmap-tool` for the release-notes command and the subject gate.

The revive retraction gap the round 1 review found in `changelog.md` is its own item, `changelog-prose-first-entries-escape-the-invariant`, and this one no longer depends on it.

## Reviewer findings

### Round 1: Backlog, pushback before Spec (session_01WkW2JFGZ8sg6MFqgv3yaP3, 2026-11-05)

Not a gate verdict; findings to settle before the item moves to Spec. Read against trunk `075fe6a`.

1. **The premise that every entry begins with prose is false.** 584 of the 761 top-level bullets in `changelog.md` begin `- R<n>`; only the recent entries lead with prose. The invariant held by `Main.changelogDoneIds` is not vacuous over the file as a whole, so "an entry for an item still on the board already passes the tool" holds only for a prose-first entry, not for entries generally.

2. **The prose-first shift is itself a defect the plan leans on.** The recent prose-first Done entries (R1143, R1144, R1142, R1115 and the rest at the head of the file) are invisible to `changelogDoneIds` and to the revive retraction. For those items the board-or-changelog invariant is unchecked and a revive would leave the Done entry standing. The plan distinguishes a Done entry from a delivery entry by "headed by the id" versus not, a line the file's current shape no longer draws. The Spec should name a marker the tool reads for both kinds, for instance the id in the parenthetical, `(slug, R<n>, ...)` for Done and `(slug, R<n> in progress, <sha>)` for a delivery, with `CHANGELOG_BULLET_HEAD` following it. That also answers the open question about rejecting an in-progress marker for an id off the board. Alternatively file the retraction gap as its own item and make this one depend on it; either way the Problem section should not rest on "already passes the tool".

3. **Two of the three motivating examples do not hold on trunk.**
   * UPSERT beside `@service` is R876 (`6733135`) and real, but it is not in R876's "For the changelog at Done" section, so "each recorded only in the item's own section" is wrong for it.
   * The narrowed same-table `@table` field becoming an error, and `Film.sameTableById` removed: not on trunk. `Film.sameTableById` is still in the sakila example schema, and `SameRowTableChildExecutionTest.narrowedForm_readsTheFilteredRows_theSameUnderEveryParent` still pins it as working.
   * A same-table `@table` child without `@reference` refused is R1115's delivery, already announced by R1115's Done entry, so it belongs to another item and was not withheld.

   R876's section carries better evidence, changes that plainly reached trunk long before Done: `@table` naming a table-valued function refused, `@routine` losing `columnMapping`, the MCP `demand` slot removed, `graphitron:capture` no longer failing over a schema that parses, reactor siblings read from their own output, `graphitron:dev` compiling its module first. Use those for the Problem section and for "The first application".

4. **Missing edit sites.** R876's "For the changelog at Done" section opens by restating the rule this item changes ("The entry is written at Done, the board and the changelog holding an id one place at a time"); the plan should say that text changes, and whether such sections remain a convention. The roadmap skill's In Review -> Done step ("append a one-line entry ... capturing the landing commit SHA and the `R<n>` ID") also needs rewording beyond the Done step already listed.

5. **Concurrent prepends.** Writing an entry in the same push as every behaviour-changing commit means many sessions prepending to the head of `changelog.md` at once. Say how a rebase conflict there is resolved, or that the cost is accepted.

6. **A revert entry needs a shape.** "A delivery later reverted gets an entry of its own" should state its form (a `Reverted:` prefix, like `Discarded:`) so neither a reader nor the tool can take it for a Done or a delivery entry.

Sound as filed: the goal, the when and who rules, the Done entry pointing back to delivery entries rather than repeating them, and delivery entries surviving a revive.

### Response to round 1 (2026-11-05)

The plan is retargeted from delivery entries in `changelog.md` to Conventional Commits with generated release notes.

1. Accepted: the premise was false. The Problem no longer rests on it.
2. Accepted as a real defect, filed as `changelog-prose-first-entries-escape-the-invariant`. With `changelog.md` Done-only, this item no longer draws a line between entry kinds, so it does not depend on that fix.
3. Accepted. The Problem now uses R876's section as the review suggests, plus the UPSERT refusal as the case missing from it. The narrowed same-table field was in the harvest queue, not on trunk, and the same-table child refusal is R1115's.
4. Accepted. R876's opening sentence and the roadmap skill's In Review -> Done step are listed edit sites.
5. Answered by the retarget: an announcement is the commit itself, so no push prepends to a shared file.
6. Answered by the retarget: a revert is the `revert` type, its body naming the hash it reverts.
