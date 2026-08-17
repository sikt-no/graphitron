---
id: R700
title: "Diagnostics proportions come from a GROUP BY over the diagnostic view"
status: Backlog
bucket: tooling
priority: 3
theme: tooling
depends-on: []
created: 2026-08-17
last-updated: 2026-08-17
---

# Diagnostics proportions come from a GROUP BY over the diagnostic view

A schema with hundreds of diagnostics needs proportions before entries: how much of it the author
can fix, how much is a shape graphitron does not generate yet, and which cluster to open first. The
`diagnostics.aggregate` tool answered that and `roadmap/catalog-facts-readers-move-to-the-store.md`
dropped it, because what it was is a `GROUP BY` written in Java over a projection, with its own
dimension vocabulary, its own bucket partition, its own elision accounting and a coverage meta-test
holding the partition together. None of that is worth carrying across a substrate change when the
substrate can express the whole thing directly.

The `diagnostic` view is already the relation the `diagnostics` tool reads. Grouping over it is a
`GROUP BY` with counts, so the tool becomes a query whose group keys are columns of that view rather
than an enum the module maintains, and the "these clusters are typed" claim the old wire made stops
needing a meta-test to stay true. What the plan owes is which grouping keys the wire offers, and how
a truncated aggregate says so, since an aggregate that elides silently reads as complete.
