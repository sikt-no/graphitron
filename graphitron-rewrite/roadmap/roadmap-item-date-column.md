---
id: R143
title: "Surface a date column on the rendered roadmap table"
status: Backlog
bucket: cleanup
priority: 3
depends-on: []
---

# Surface a date column on the rendered roadmap table

The rolled-up `graphitron-rewrite/roadmap/README.md` table (`Main.java`) shows ID, Item, Status, Plan, but no time dimension; a reader scanning Active cannot tell which Spec items have been sitting unsigned-off for weeks vs days, and there is no signal in the Backlog table that distinguishes recently-filed items from ones parked years ago. Adding a date column gives triage that signal without requiring readers to drop into `git log` per item.

Open design fork (decide on Backlog → Spec):

- **Which date.** Three plausible semantics: (a) *created* — immutable, easiest to backfill via `git log --diff-filter=A --follow` per file, but stops being meaningful once an item enters Spec; (b) *last status transition* — most useful for triage, but requires the `roadmap` skill / tool to stamp it on every transition so authors don't maintain it by hand; (c) *target/due* — carries deadline connotations that don't fit a no-deadline backlog and risks driving the wrong behaviour. Recommendation: (b), with a `last-transition:` front-matter key that the tool's `status` subcommand writes automatically.
- **Backfill.** ~60 existing items need an initial value. For (a) and (b), `git log --diff-filter=A --follow -- <file>` gives a defensible starting point; the validator should accept missing values during the migration window and the tool should emit a one-shot `backfill` subcommand.
- **Render shape.** Either an extra table column (widens an already-wide table) or a `<sub>` suffix on the Item cell next to the existing `blocked by:` annotation. The latter keeps the table grep-friendly at the cost of being less scannable as a column.
