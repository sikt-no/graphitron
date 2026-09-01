---
id: R907
title: "Active roadmap items render their goal paragraph in the roll-up"
status: Backlog
bucket: dx
priority: 5
theme: tooling
depends-on: []
created: 2026-09-01
last-updated: 2026-09-01
---

# Active roadmap items render their goal paragraph in the roll-up

The roadmap tool renders an item body's first paragraph as the one-line description only for Backlog items (`Main.firstNonHeadingParagraph` is reached only from `Main.appendBacklogLine` and `Main.appendBacklogAdocLine`). Items at Spec and beyond, exactly the ones a reader most wants to evaluate, show only title, status and dates in the Active table of both the `README.md` roll-up and the published status board. Once item bodies lead with a goal paragraph written to stand alone (the goal-first body convention), rendering that paragraph for active items would let a reader judge every in-flight item from the roll-up without opening its file. The Active render is a five-column table in both formats, so the paragraph needs a column or a row continuation, and the checked-in adoc expectations move with it.
