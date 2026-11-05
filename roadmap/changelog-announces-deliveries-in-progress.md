---
id: R1156
title: "The changelog announces a delivery when it lands, not only at Done"
status: Backlog
bucket: process
priority: 2
theme: tooling
depends-on: []
created: 2026-11-05
last-updated: 2026-11-05
---

# The changelog announces a delivery when it lands, not only at Done

## Goal

A consumer learns of a change to what graphitron accepts, refuses or generates when that change reaches trunk, not when the item carrying it reaches Done. Today `roadmap/workflow.adoc` describes a changelog entry only as part of the Done verdict, so a long item that ships behaviour in steps announces none of it until it closes, which can be weeks after the first breaking change landed.

## Problem

The rule is convention, not mechanism. The roadmap tool's invariant, that an id is either a Done entry in `changelog.md` or on the board but never both, reads only a bullet that begins with the id (`^- R\d+`), and every real entry begins with prose and names its id in its parenthetical. So an entry for an item still on the board already passes the tool; only the workflow text keeps it out.

The cost is real on a long-running item. In the first days of November three changes reached trunk on one open item that change what an author or a consumer sees, each recorded only in the item's own "For the changelog at Done" section: a `@mutation(typeName: UPSERT)` beside `@service` became an error; a same-table `@table` field narrowed by an argument became an error, and the example schema's `Film.sameTableById` was removed; and a same-table `@table` child without `@reference` is refused, which an fs-plattform consumer build hit on eleven fields.

## Plan

A *delivery entry* is a changelog entry written when a delivery lands on trunk, whatever state its item is in.

* **When.** A commit on trunk changes what an author or consumer sees: a breaking change, a new refusal, a new capability. A restructuring that changes no behaviour gets none.
* **Shape.** The Done entry's prose shape, its parenthetical marking the item in progress and naming the landing commit, for example `(slug, R<n> in progress, <sha>)`. Like every entry it never begins with the id.
* **Who.** Whoever puts the commit on trunk writes it, in the same push, so the entry and the change arrive together.
* **At Done.** The Done entry covers what no delivery entry announced and points back to the delivery entries rather than repeating them. The item body's "For the changelog at Done" section holds only what is not yet announced; a line moves out when its delivery entry is written.
* **On revive.** Delivery entries stay, since those deliveries landed; the retraction removes only entries headed by the id, which a delivery entry never is. A delivery later reverted gets an entry of its own saying so.

Edits: the changelog paragraphs in `roadmap/workflow.adoc` (the state table's Done row, the revive paragraph, and the In Review → Done step); the javadoc on `Main.readChangelogDoneIds` in roadmap-tool, saying a delivery entry falls outside the invariant by construction; and the roadmap skill's Done step. Open for Spec: whether the tool should also reject an in-progress marker naming an id that is not on the board.

The first application is the three changes above, written from the open item's "For the changelog at Done" lines.

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
