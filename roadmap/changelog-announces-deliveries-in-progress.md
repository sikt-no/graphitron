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
