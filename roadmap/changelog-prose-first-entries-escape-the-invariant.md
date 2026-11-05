---
id: R1157
title: "A Done entry that leads with prose escapes the board-or-changelog check and the revive retraction"
status: Backlog
bucket: bug
priority: 2
theme: tooling
depends-on: []
created: 2026-11-05
last-updated: 2026-11-05
---

# A Done entry that leads with prose escapes the board-or-changelog check and the revive retraction

## Goal

Every Done entry in `roadmap/changelog.md` is seen by the roadmap tool, whatever its first words, so the invariant that an id is either a Done entry or on the board holds for every item, and a revive retracts the Done entry it should.

## Problem

`Main.changelogDoneIds` and the revive retraction read an entry's id only from a bullet that begins with it (`CHANGELOG_BULLET_HEAD`, `^- (R\d+)\b`). Entries now lead with what changed for a reader and name the id in a parenthetical, `(slug, R<n>, ...)`. Of the 761 top-level bullets, 584 begin `- R<n>`; of the rest, 76 are prose-first entries carrying an id (R1143, R1144, R1142, R1115 among them) and invisible to both. For those items the board-or-changelog check never runs, and a revive leaves the Done entry standing beside the restored item.

Found in the round 1 review of `commit-subject-says-the-kind-of-change`.

## Plan

The tool reads an entry's id where entries now carry it, the parenthetical, and still accepts the id-first form the older entries use. `Discarded:` entries stay outside, since discarding is not a landing. The Spec decides the parenthetical's exact grammar, whether `workflow.adoc` names one form for new entries, and whether the older entries are rewritten to it.
